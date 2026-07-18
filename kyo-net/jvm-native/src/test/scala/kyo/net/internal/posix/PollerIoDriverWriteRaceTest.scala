package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.WriteResult

/** Deterministic reproduction + regression guard for the WRITE-path TLS use-after-free race in [[PollerIoDriver]], the symmetric twin of the
  * read-path race covered by [[PollerIoDriverRaceTest]].
  *
  * `PollerIoDriver.write` reads `handle.tls` and drives the engine (`writePlain`, then a `drainCiphertext` loop) synchronously on the engine FIFO
  * worker, under `beginWrite`. A concurrent `closeHandle` runs `PosixHandle.close`, which routes the TLS engine free through the engine FIFO. The
  * write op and the close path run on independent fibers/carriers, so a close can fire while a write is mid-encrypt: a use-after-free, exactly the
  * class the read path was already guarded against.
  *
  * The interleave is forced deterministically by re-entrancy rather than a sleep: a [[RecordingTlsEngine]] over a real BoringSSL engine, on its
  * `onWritePlain` one-shot hook (fired inside the `writePlain` call, after `write` has acquired the resource guard via `beginWrite`), invokes
  * `closeHandle` for the same handle. That is the exact moment the race targets, close firing while the write holds the engine. A correct guard
  * must DEFER the free until the write's `endWrite` completes, so the write keeps using a live engine and the engine is freed once, after all use,
  * never during it. The leaf asserts the engine is freed (by the close) but NEVER used after free, and that a write attempted AFTER the handle is
  * fully closed bails cleanly (Error) without touching the freed engine.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair for real fds) and `TlsRealEngines.assumeTlsReady()` (a real BoringSSL engine). The
  * real engine encrypts the small plaintext into ciphertext that flushPending sends to the (now-closed) fd; the send failing is harmless because
  * the write op already returned Done and the race is about the close-during-encrypt guard, not the send succeeding.
  *
  * Anti-flakiness: the engine handshakes in-memory via `TlsEngineLoopback.handshake` before the write; `onWritePlain` is a one-shot re-entrant
  * latch that fires `closeHandle` while `beginWrite` is held. The deferred engine free is up to three FIFO ops downstream (the write op fires
  * `closeHandle`, the close op runs `requestClose`/`freeResources`, and the engine free is routed through `engineFreeSink` as a third op), so three
  * `fifoBarrier`s in sequence settle it on every scheduler. No sleep.
  *
  * Drives a real BoringSSL engine through a `RecordingTlsEngine` decorator and observes `freeCount.get() == 1`, `!usedAfterFree`,
  * `handle.tls.isEmpty`, `result == Done`, `second == Error`, and `spy.closeCounts == 1`.
  */
class PollerIoDriverWriteRaceTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Submit a marker engine op and return a promise that completes when the FIFO worker runs it. A sequence settles the deferred engine free,
      * which is up to THREE FIFO ops downstream of the write: the write op (writeTls) runs writePlain, which fires closeHandle (enqueuing the close
      * op); the close op runs requestClose -> freeResources, which routes engine.free through engineFreeSink as a third op. Each barrier settles one
      * op, so three in sequence guarantee the free has run regardless of whether the poll carrier had already drained the write op before the first
      * barrier was enqueued (the scheduler-dependent window that made a two-barrier settle flake on Native's green threads). A deterministic,
      * sleep-free settle point.
      */
    private def fifoBarrier(driver: PollerIoDriver)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    /** Complete the handshake for `client`/`server` ON the driver's engine FIFO worker and free the server (unused past the handshake) there too,
      * so the client session is created, handshaked, and later written on one carrier; a cross-carrier real BoringSSL session corrupts.
      */
    private def handshakeOnDriver(driver: PollerIoDriver, client: TlsEngine, server: TlsEngine)(using
        AllowUnsafe
    ): Promise.Unsafe[Unit, Any] =
        val done = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp { () =>
            discard(TlsEngineLoopback.handshake(client, server))
            server.free()
            done.completeDiscard(Result.succeed(()))
        }
        done
    end handshakeOnDriver

    "PollerIoDriver write vs close race" - {
        "a TLS engine freed by closeHandle is never used by an in-flight write (use-after-free guard)" in {
            PosixTestSockets.assumePoller()
            TlsRealEngines.assumeTlsReady()
            // Use a real loopbackPair so close(targetFd) calls a real syscall. The real engine's writePlain produces ciphertext that flushPending
            // tries to send to the (already-closed) fd; that send failing is harmless. The spy records the real close.
            //
            // The DRIVER frees the client engine via closeHandle, so it is created with singleEngine (no auto-free finally that would double-free
            // the native session). The server engine is needed only to complete a real handshake; it is freed on the FIFO worker once the handshake
            // is done, before the race runs. The handshake runs on the FIFO worker so the client session is created, handshaked, and written on one
            // carrier (a cross-carrier real BoringSSL session corrupts).
            val clientEngine = TlsRealEngines.singleEngine(isServer = false)
            val serverEngine = TlsRealEngines.singleEngine(isServer = true)
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val targetFd = acceptedFd
                // A RecordingPollerBackend over the real epoll/kqueue; the poll loop is started because the engine FIFO drains only on the poll-loop
                // carrier (it bounded-waits on the idle poller fd, no fds registered, and drains the engine queue each cycle). create() allocates the
                // real poller fd, freed by driver.close() at the end.
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                discard(driver.start())
                val handle = PosixHandle.socket(targetFd, PosixHandle.DefaultReadBufferSize, Absent)

                val engine = new RecordingTlsEngine(clientEngine)
                engine.onWritePlain = () => driver.closeHandle(handle)
                handle.tls = Present(engine)

                for
                    // Handshake on the FIFO worker (frees the server there too) so the client session is created, handshaked, and written on one
                    // carrier. The onWritePlain hook is set on the wrapper, not the raw engine, so the handshake does not fire it.
                    _ <- handshakeOnDriver(driver, clientEngine, serverEngine).safe.get
                    // Drive a TLS write. Inside the FIFO thunk: writePlain fires closeHandle (the concurrent close), then drainCiphertext
                    // runs, then endWrite releases the guard and submits the deferred engine free as a separate FIFO op. drainCiphertext
                    // runs on the live engine. The write returns Done synchronously.
                    result <- Sync.defer(driver.write(handle, Span(1.toByte, 2.toByte, 3.toByte), 0))
                    // Settle the deferred free deterministically: the free is up to three FIFO ops downstream of the write (write op -> close op ->
                    // free op), so three barriers in sequence guarantee it has run on every scheduler, including Native's green threads where the
                    // poll carrier may not have drained the write op before the first barrier was enqueued.
                    _ <- fifoBarrier(driver).safe.get
                    _ <- fifoBarrier(driver).safe.get
                    _ <- fifoBarrier(driver).safe.get
                    _ = assert(engine.freeCount.get() == 1, s"closeHandle must free the engine exactly once, was ${engine.freeCount.get()}")
                    _ = assert(
                        !engine.usedAfterFree.get(),
                        "use-after-free: the in-flight write touched the engine after closeHandle freed it"
                    )
                    _ = assert(handle.tls.isEmpty, "the engine slot must be cleared once freed")
                    _ = assert(result == WriteResult.Done, s"the in-flight write should complete Done on the live engine, got $result")
                    // A NEW write attempted after the handle is fully closed must bail Error without touching the (freed) engine.
                    writePlainBeforeSecond = engine.writePlainCalls.get()
                    second <- Sync.defer(driver.write(handle, Span(4.toByte), 0))
                    _      <- Sync.defer(driver.close())
                yield
                    // Close the peer fd (driver.close does NOT close socket fds; closeHandle already closed targetFd via spy).
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    assert(second == WriteResult.Error, s"a write on a closed handle must bail Error, got $second")
                    assert(
                        engine.writePlainCalls.get() == writePlainBeforeSecond,
                        "a write on a closed handle must not touch the freed engine at all"
                    )
                    assert(!engine.usedAfterFree.get(), "the post-close write must not touch the freed engine")
                    assert(engine.freeCount.get() == 1, "no re-free on the post-close write")
                    // Real close was recorded: closeHandle called sockets.close(targetFd) exactly once.
                    assert(
                        spy.closeCounts.getOrDefault(targetFd, 0) == 1,
                        s"closeHandle must have closed targetFd=$targetFd exactly once, counts=${spy.closeCounts}"
                    )
                end for
            }
        }
    }

end PollerIoDriverWriteRaceTest
