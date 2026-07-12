package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.Test

// This suite lives in jvm-native/src/test because PosixTransport's accept loop runs on JVM-posix and Native; JS uses the Node transport.

/** Deterministic shutdown-ordering guard for the accept loop's `acceptLoopsActive` counter (`PosixTransport.activeAcceptLoops`).
  *
  * The mechanism: each listener runs one accept loop. `startAcceptLoop` increments `acceptLoopsActive` and then arms `driver.awaitAccept`
  * synchronously, all before `listen(...)` completes its promise, so once the listen fiber resolves the count is exactly 1 and an accept is
  * poller-armed. Shutdown relies on an inline-completion guarantee: `transport.close()` -> `PosixListener.close()` -> `driver.cancel(handle)`
  * completes the parked accept promise with `Closed` INLINE (`IOPromise.complete` runs its `onComplete` callbacks inline), and the accept loop's
  * `Result.Failure` arm decrements the counter, all on the calling fiber before `close()` returns. So `activeAcceptLoops` must read 0 the instant
  * `close()` returns, with no poll or sleep.
  *
  * There is deliberately no `drainAcceptLoops` test helper, precisely because this inline path makes a drain-wait unnecessary. Nothing else asserted the
  * underlying guarantee. These leaves pin it: if `close()` ever stopped inline-completing the parked accept (so the count could linger nonzero
  * after `close()` returns), the immediate-after-close assertion fails deterministically.
  *
  * Three leaves, all deterministic with no client connect, no sleep, no poll:
  *   - "transport.close() winds the loop down inline": one listener, no client ever connects (the accept stays poller-armed). Assert the count is
  *     1 right after `listen` returns, then 0 the instant `transport.close()` returns.
  *   - "listener.close() winds its own loop down inline": same, but closing the single listener directly (the path `transport.close()` fans out to).
  *   - "transport.close() drains every listener's loop inline": three listeners, each with a poller-armed accept (count 3), then one
  *     `transport.close()` drains all of them to 0 inline.
  */
class AcceptLoopShutdownTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.TransportConfig.default

    private def assumePollerReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport accept-loop tests need epoll (Linux) or kqueue (macOS/BSD)")

    /** Build a transport over a fresh real poller driver, run `body`, then close the driver. The body owns transport shutdown so each leaf can
      * assert the `activeAcceptLoops` count at the exact instant a `close()` returns.
      */
    private def withTransport[A](body: PosixTransport => A < (Async & Abort[NetException | Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[NetException | Closed] & Scope) =
        val driver    = PollerIoDriver.init(transportConfig)
        val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        discard(driver.start())
        Abort.run[NetException | Closed](body(transport)).map { result =>
            Sync.defer(driver.close()).andThen(Abort.get(result))
        }
    end withTransport

    "accept-loop shutdown ordering" - {

        /** transport.close() winds the accept loop down to 0 inline.
          *
          * No client ever connects, so the accept stays poller-armed for the loop's whole life. `listen(...).safe.get` resolves only after
          * `startAcceptLoop` has incremented the counter and armed `awaitAccept`, so the count is deterministically 1 at that point. Then
          * `transport.close()` deregisters the accept (inline-completing the parked promise with Closed) and the loop's failure arm decrements
          * the counter, all before `close()` returns: the count must read 0 immediately, with no poll.
          */
        "transport.close() winds the accept loop down to 0 inline (no poll, no sleep)" in {
            assumePollerReady()
            withTransport { transport =>
                (for
                    listener <- transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get
                    armed = transport.activeAcceptLoops
                    afterClose <- Sync.defer {
                        transport.close()
                        transport.activeAcceptLoops
                    }
                yield
                    discard(listener)
                    assert(armed == 1L, s"accept loop should be armed (count 1) right after listen returns, was $armed")
                    assert(afterClose == 0L, s"accept loop must drain to 0 immediately after transport.close() returns, was $afterClose")
                ): Unit < (Async & Abort[NetException | Closed] & Scope)
            }
        }

        /** listener.close() winds its own loop down to 0 inline.
          *
          * The single-listener path that `transport.close()` fans out to: closing the listener directly must inline-complete its parked accept and
          * decrement the counter before `close()` returns.
          */
        "listener.close() winds its own accept loop down to 0 inline (no poll, no sleep)" in {
            assumePollerReady()
            withTransport { transport =>
                (for
                    listener <- transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get
                    armed = transport.activeAcceptLoops
                    afterClose <- Sync.defer {
                        listener.close()
                        transport.activeAcceptLoops
                    }
                yield
                    assert(armed == 1L, s"accept loop should be armed (count 1) right after listen returns, was $armed")
                    assert(afterClose == 0L, s"accept loop must drain to 0 immediately after listener.close() returns, was $afterClose")
                ): Unit < (Async & Abort[NetException | Closed] & Scope)
            }
        }

        /** transport.close() drains every listener's accept loop to 0 inline.
          *
          * Three listeners, each with a poller-armed accept (count 3, none with a client). One `transport.close()` fans out to all three
          * `PosixListener.close()` calls; each inline-completes its parked accept and decrements the counter, so the total must read 0 the instant
          * `close()` returns.
          */
        "transport.close() drains every listener's accept loop to 0 inline (no poll, no sleep)" in {
            assumePollerReady()
            withTransport { transport =>
                (for
                    l1 <- transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get
                    l2 <- transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get
                    l3 <- transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get
                    armed = transport.activeAcceptLoops
                    afterClose <- Sync.defer {
                        transport.close()
                        transport.activeAcceptLoops
                    }
                yield
                    discard(l1, l2, l3)
                    assert(armed == 3L, s"three armed accept loops should give count 3 after the three listens, was $armed")
                    assert(
                        afterClose == 0L,
                        s"all three accept loops must drain to 0 immediately after transport.close() returns, was $afterClose"
                    )
                ): Unit < (Async & Abort[NetException | Closed] & Scope)
            }
        }

    }

end AcceptLoopShutdownTest
