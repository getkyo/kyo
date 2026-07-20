package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Connection
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.TlsProviderPlatform
import kyo.net.internal.TlsTestCert

// This suite lives in jvm-native/src/test because PosixTransport's TLS path runs on JVM-posix and Native; JS uses the Node transport.

/** Behavioral coverage for the `onComplete` state-machine paths of [[PosixTransport]]: connect/handshake/accept/spawnHandler implemented
  * as non-blocking `onComplete` callbacks.
  *
  * Each test targets a distinct code path:
  *   - TLS handshake via the onComplete state machine: want-read and want-write resume paths.
  *   - TLS handshake failure surfaces a clean Closed error with no hang.
  *   - Multi-fd accept drain: many simultaneous clients, all accepted by the drain-until-EAGAIN loop.
  *   - Handler-throw resilience: a throwing handler does not wedge the accept carrier; subsequent clients are still accepted.
  *   - Plaintext connect via the `resolveAndEncode` onComplete path.
  *
  * All tests use real loopback sockets and a real `PollerIoDriver`. The suite cancels where there is no real poller or no staged TLS provider.
  */
class PosixTransportHandshakeBehaviorTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.NetConfig.default

    private val serverTls = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )
    private val clientTls = NetTlsConfig(trustAll = true)

    private def tlsAvailable: Boolean =
        try
            discard(TlsProviderPlatform.engine(clientTls, "localhost", isServer = false))
            true
        catch case _: Throwable => false

    private def assumeTlsReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport TLS handshake tests need epoll (Linux) or kqueue (macOS/BSD)")
        if !tlsAvailable then cancel("No TLS provider staged for this host")
    end assumeTlsReady

    private def assumePollerReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport tests need epoll (Linux) or kqueue (macOS/BSD)")

    /** Build a transport over a fresh real poller driver, run `body`, then close the driver (this transport is never closed, mirroring
      * production). Each leaf closes its own listeners: with the poller (epoll/kqueue) the accept loop is readiness-driven and never parks in a
      * blocking `accept`, so a listener's own `close()` (on the calling fiber) deregisters the accept interest via `driver.cancel`, which
      * inline-completes the parked accept promise with `Closed`; that completion runs the accept loop's exit branch inline (`IOPromise.flush`),
      * then the listener `shutdown`s + closes the listen fd, all synchronously.
      *
      * The driver's own poll-loop thread is a separate story: `driver.close()` only requests teardown (`submitEngineOp` + `triggerWake()`) and
      * returns immediately, without waiting for the poll-loop carrier to actually run it. Awaiting the driver's own exit fiber after `close()`
      * (rather than discarding it) makes the underlying thread provably gone before this computation completes, closing the window where, under
      * kyo-test's concurrent leaf scheduling, a not-yet-fully-torn-down driver from an earlier leaf could still be alive when the
      * next leaf's own transport starts.
      */
    private def withTransport[A](body: PosixTransport => A < (Async & Abort[NetException | Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[NetException | Closed] & Scope) =
        val driver     = PollerIoDriver.init()
        val transport  = TestTransports.forTesting(driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        val driverDone = driver.start()
        Abort.run[NetException | Closed](body(transport)).map { result =>
            Sync.defer(driver.close()).andThen(
                Abort.run(driverDone.safe.get).unit
            ).andThen(Abort.get(result))
        }
    end withTransport

    /** Read exactly `target` bytes from an unsafe connection's inbound channel, concatenated. */
    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    "PosixTransport handshake and connection behavior" - {

        /** TLS handshake via want-read / want-write resume paths (onComplete):
          *
          * A real TLS connect over a loopback socket pair exercises the full driveHandshake onComplete state machine: the client sends
          * its ClientHello (WANT_WRITE / drainAll), the server sends its response (WANT_READ / recvAndFeed), and so on until FINISHED.
          * The round-trip assertion proves the handshake completed successfully via all three resume arms (onFinished, and both suspension
          * paths for want-read and want-write). The serverCertificateHash assertion proves the engine is attached to the handle.
          */
        "TLS handshake completes via the onComplete state machine: want-read and want-write resume, round-trip asserted" in {
            assumeTlsReady()
            withTransport { transport =>
                for
                    handlerReady <- Channel.init[Unit](1)
                    listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls) { serverConn =>
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    handlerReady.put(()).andThen {
                                        Loop.foreach {
                                            serverConn.inbound.safe.take.map { chunk =>
                                                serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                            }
                                        }
                                    }
                                }.unit
                            }
                        })
                    }.safe.get
                    client <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
                    _      <- handlerReady.take
                    msg = "handshake-want-read-want-write-roundtrip".getBytes("UTF-8")
                    _      <- client.outbound.safe.put(Span.fromUnsafe(msg))
                    echoed <- collect(client, msg.length)
                    certHash = client.serverCertificateHash
                yield
                    client.close()
                    listener.close()
                    assert(echoed.sameElements(msg), s"TLS round-trip mismatch: got '${new String(echoed, "UTF-8")}'")
                    certHash match
                        case Absent     => fail("client did not observe the server certificate hash after handshake")
                        case Present(h) => assert(h.toArray.length == 32, "server cert hash must be 32 bytes (SHA-256)")
                end for
            }
        }

        /** TLS write backpressure: want-write partial-flush resumes via awaitWritable onComplete:
          *
          * A large TLS transfer pushes enough ciphertext through `drainAll` that the socket send buffer fills and `sendAll` returns
          * `WriteResult.Partial`, triggering the `awaitWritable` arm: a promise is registered on the driver, `awaitWritable` arms it, and
          * the `onComplete` callback fires `drainAll()` again once the fd is writable. A deadlock or missing resume would cause this test
          * to hang (which the 15s suite timeout would catch). The final byte-equality assertion proves the full payload arrived intact.
          */
        "TLS write backpressure: awaitWritable onComplete resumes drainAll when the send buffer fills" in {
            assumeTlsReady()
            withTransport { transport =>
                for
                    ready <- Channel.init[Unit](1)
                    listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls) { serverConn =>
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    ready.put(()).andThen {
                                        // Echo all inbound data back until the connection closes.
                                        Loop.foreach {
                                            serverConn.inbound.safe.take.map { chunk =>
                                                serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                            }
                                        }
                                    }
                                }.unit
                            }
                        })
                    }.safe.get
                    client <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
                    _      <- ready.take
                    // A 128 KB payload: large enough to fill the loopback socket send buffer and trigger WriteResult.Partial / awaitWritable.
                    payload = Array.tabulate[Byte](128 * 1024)(i => (i & 0xff).toByte)
                    _      <- client.outbound.safe.put(Span.fromUnsafe(payload))
                    echoed <- collect(client, payload.length)
                yield
                    client.close()
                    listener.close()
                    assert(echoed.sameElements(payload), s"large TLS round-trip mismatch at length ${echoed.length}")
            }
        }

        /** TLS handshake failure: connect against a plaintext server completes with Closed, no hang:
          *
          * A TLS client attempts to connect to a plaintext (non-TLS) server. The plaintext server just echoes raw bytes; the TLS
          * handshake fails because the peer does not speak TLS. The connect fiber must complete with a Closed failure within the test
          * timeout, not hang, proving the `onFailed` arm of the onComplete state machine fires and completes the promise.
          */
        "TLS handshake failure against a plaintext server surfaces Closed within the timeout, no hang" in {
            assumeTlsReady()
            withTransport { transport =>
                for
                    // Plain (non-TLS) server: immediately closes each accepted connection without sending any TLS data.
                    listener <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                        // Close without sending anything: the TLS client will receive a truncated handshake.
                        serverConn.close()
                    }.safe.get
                    outcome <- Abort.run[NetException | Closed](transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get)
                yield
                    listener.close()
                    assert(outcome.isFailure, s"expected Closed on TLS-to-plaintext handshake failure, got $outcome")
            }
        }

        /** Multi-fd accept drain: acceptAll drains many simultaneous connections via the drain-until-EAGAIN loop:
          *
          * Many clients connect simultaneously before the first accept-ready event fires. When the event fires, `acceptAll` must drain
          * all queued connections via the `@tailrec drain()` loop, calling `acceptNow` until it returns EAGAIN, and invoking `handleAccepted`
          * for each. After draining, `scheduleNextAccept` re-arms the driver. All N accepted connections must perform a round-trip, proving
          * each fd was correctly wrapped, started, and handed to the handler.
          */
        "accept loop drains all simultaneously-pending connections before re-arming (multi-fd acceptNow drain)" in {
            assumePollerReady()
            val n = 8
            withTransport { transport =>
                for
                    // Count how many handlers have started, each completes a promise from the channel.
                    handlerCounter <- Channel.init[Unit](n)
                    listener <- transport.listen("127.0.0.1", 0, n * 2) { serverConn =>
                        // Echo once then close.
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    handlerCounter.put(()).andThen {
                                        serverConn.inbound.safe.take.map { chunk =>
                                            serverConn.outbound.safe.put(chunk).andThen(Sync.defer(serverConn.close()))
                                        }
                                    }
                                }.unit
                            }
                        })
                    }.safe.get
                    port = listener.port
                    // Connect all N clients concurrently so they queue up before the accept loop drains.
                    clients <- Async.fillIndexed(n, n) { _ =>
                        transport.connect("127.0.0.1", port).safe.get
                    }
                    // Wait for all N handlers to start.
                    _ <- Kyo.foreach(0 until n)(_ => handlerCounter.take)
                    // Each client sends a message and reads the echo.
                    results <- Async.fillIndexed(n, n) { i =>
                        val msg = s"drain-$i".getBytes("UTF-8")
                        clients(i).outbound.safe.put(Span.fromUnsafe(msg)).andThen {
                            collect(clients(i), msg.length).map { got =>
                                clients(i).close()
                                got.sameElements(msg)
                            }
                        }
                    }
                yield
                    listener.close()
                    assert(
                        results.forall(identity),
                        s"not all $n connections were accepted and round-tripped correctly"
                    )
            }
        }

        /** Handler-throw resilience: a throwing handler does not wedge the accept carrier:
          *
          * A server whose handler throws an exception on the FIRST accepted connection. The accept loop must survive the throw (the panic
          * is logged by `spawnHandler` but not propagated to the accept carrier). A SECOND client then connects and its handler runs
          * successfully, proving the loop is still alive.
          */
        "handler throw on the first connection does not wedge the accept loop; subsequent connections are accepted" in {
            assumePollerReady()
            withTransport { transport =>
                for
                    // Channel latch: the second-connection handler sends one unit when it runs.
                    secondHandled <- Channel.init[Unit](1)
                    // Channel latch: the first handler signals the instant it has crashed, so the test gates the second connect on the actual
                    // throw event rather than guessing a recovery delay.
                    firstThrew <- Channel.init[Unit](1)
                    throwCount = new AtomicInteger(0)
                    listener <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                        if throwCount.getAndIncrement() == 0 then
                            // First connection: throw to simulate a handler crash. The transport catches this via Fiber.Unsafe.init. Signal the
                            // crash latch before throwing so the test can proceed deterministically once the handler has actually run and failed.
                            serverConn.close()
                            discard(firstThrew.unsafe.offer(()))
                            throw new RuntimeException("intentional handler throw")
                        else
                            // Second (and subsequent) connections: signal the latch and close cleanly.
                            discard(Sync.Unsafe.evalOrThrow {
                                Fiber.initUnscoped {
                                    Abort.run[Closed] {
                                        serverConn.inbound.safe.take.andThen(secondHandled.put(()))
                                    }.andThen(Sync.defer(serverConn.close()))
                                }
                            })
                    }.safe.get
                    port = listener.port
                    // First connection: triggers the handler throw; the connection will close.
                    firstClient <- transport.connect("127.0.0.1", port).safe.get
                    // Wait for the first handler to actually crash (the latch it signals before throwing), so the second connect is gated on the
                    // real event rather than a guessed recovery delay.
                    _ <- firstThrew.take
                    // Second connection: the accept loop must still be running.
                    secondClient <- transport.connect("127.0.0.1", port).safe.get
                    // Send something to the second handler so it reads from inbound and signals the latch.
                    _ <- secondClient.outbound.safe.put(Span.fromUnsafe(Array[Byte](1)))
                    _ <- secondHandled.take
                yield
                    firstClient.close()
                    secondClient.close()
                    listener.close()
                    assert(throwCount.get() >= 2, s"expected at least 2 handler invocations, got ${throwCount.get()}")
            }
        }

        /** Plaintext connect via the resolveAndEncode onComplete path:
          *
          * Uses the numeric IP fast path through `resolveAndEncode`, which still exercises the `onComplete` chain from `resolveAndEncode`
          * through `connectImpl` to the connect promise. The round-trip asserts the connection is established and the pumps work.
          */
        "plaintext connect via the resolveAndEncode onComplete path completes and round-trips" in {
            assumePollerReady()
            withTransport { transport =>
                for
                    ready <- Channel.init[Unit](1)
                    listener <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    ready.put(()).andThen {
                                        serverConn.inbound.safe.take.map { chunk =>
                                            serverConn.outbound.safe.put(chunk).andThen(Sync.defer(serverConn.close()))
                                        }
                                    }
                                }.unit
                            }
                        })
                    }.safe.get
                    // Connect using the numeric IP (goes through the resolveAndEncode fast path then onComplete -> connectImpl).
                    client <- transport.connect("127.0.0.1", listener.port).safe.get
                    _      <- ready.take
                    msg = "plaintext-resolve-and-connect".getBytes("UTF-8")
                    _      <- client.outbound.safe.put(Span.fromUnsafe(msg))
                    echoed <- collect(client, msg.length)
                yield
                    client.close()
                    listener.close()
                    assert(echoed.sameElements(msg), s"plaintext round-trip mismatch: got '${new String(echoed, "UTF-8")}'")
            }
        }

    }

end PosixTransportHandshakeBehaviorTest
