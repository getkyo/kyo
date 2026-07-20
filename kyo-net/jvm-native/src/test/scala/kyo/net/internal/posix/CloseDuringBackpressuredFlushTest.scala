package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.WriteResult

/** Close racing a BACKPRESSURED TLS flush in [[PollerIoDriver]]: a use-after-free / double-free / hang probe for the write-backpressure path.
  *
  * The setup puts a TLS handle into backpressure: the real engine emits ciphertext that the (full) real socket buffer cannot accept, so
  * [[PollerIoDriver.flushPending]] EAGAINs with bytes still in `pendingCipher` and [[PollerIoDriver.armWritableForFlush]] parks on writability
  * with `flushReArmPending == true`. A close fired in that state must:
  *   - free the engine EXACTLY once (not zero, not twice), counted via a [[RecordingTlsEngine]] over a real engine;
  *   - clear the pending-cipher state (`pendingCipher` Absent, `pendingCipherSent` 0);
  *   - never use the engine after `free()` (no UAF), even though a writable re-submit and the close run on different carriers;
  *   - complete the close (no hang);
  *   - cause a subsequent `write` on the closed handle to return `Error` (not crash, not touch the freed engine).
  *
  * Three leaves drive the three orderings the field's two writers can produce:
  *   - close while the flush is PARKED on writability (the peer never reads, so the real socket never becomes writable and the flush stays
  *     parked deterministically);
  *   - close fired from INSIDE an in-flight re-submitted flush (the peer is drained so the real socket becomes writable and the re-flush runs;
  *     `RecordingSocketBindings.onSend` fires `closeHandle` re-entrantly while `beginWrite` is held);
  *   - a looped genuinely-concurrent close vs the real writable-event re-flush race.
  *
  * Coherence: the backend is a [[RecordingPollerBackend]] over the real epoll/kqueue, the socket is a real `smallBufferedPair`, and the engine is
  * a real BoringSSL engine post-handshake wrapped in a [[RecordingTlsEngine]] for free-count / use-after-free observation. The real kernel
  * produces the EAGAIN backpressure and the writable readiness; there is no scripted event source. The flush race needs no engine hook;
  * the socket-level `onSend` hook is the latch, and the engine decorator observes `freeCount` only.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair for real EAGAIN) and `TlsRealEngines.assumeTlsReady()` (a real BoringSSL engine).
  * JS uses `sendNow`; all leaves gate on isJS.
  *
  * Anti-flakiness: the engine handshakes in-memory via `TlsEngineLoopback.handshake` before any write; `backend.registeredWrite(writeFd).safe.get`
  * latches on the real `registerWrite` (the flush arm); `fifoBarrier` proves the deferred free ran; `spy.onSend` fires before delegating to real
  * (while `beginWrite` is held). No sleep.
  *
  * Drives a real BoringSSL engine through a `RecordingTlsEngine` decorator. All three leaves assert `freeCount.get() == 1`, `!usedAfterFree`,
  * `pendingCipher.isEmpty`, `write-after-close == Error`, and `spy.closeCounts.getOrDefault(targetFd, 0) == 1`.
  */
class CloseDuringBackpressuredFlushTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Allocate a real BoringSSL client engine the DRIVER owns (freed once via `closeHandle`, so `singleEngine` not `withEngines` to avoid a
      * double-free of the native session). The engine is NOT handshaked here: it is handshaked on the driver's engine FIFO worker via
      * [[handshakeOnDriver]] so the session is created, handshaked, and later written on one carrier (a cross-carrier real BoringSSL session
      * corrupts).
      */
    private def clientEngine()(using Frame, AllowUnsafe): TlsEngine =
        TlsRealEngines.singleEngine(isServer = false)

    /** Complete the in-memory handshake for `client` against a fresh server ON the driver's engine FIFO worker, freeing the server (unused past the
      * handshake) there too, and return a promise that completes once both have run.
      */
    private def handshakeOnDriver(driver: PollerIoDriver, client: TlsEngine)(using Frame, AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val done   = Promise.Unsafe.init[Unit, Any]()
        val server = TlsRealEngines.singleEngine(isServer = true)
        driver.submitEngineOp { () =>
            discard(TlsEngineLoopback.handshake(client, server))
            server.free()
            done.completeDiscard(Result.succeed(()))
        }
        done
    end handshakeOnDriver

    /** Submit a marker engine op and return a promise that completes when the FIFO worker runs it. Awaiting it proves every engine op submitted
      * before it (the close's deferred free, a re-submitted flush, etc.) has run to completion: a deterministic, sleep-free settle point.
      */
    private def fifoBarrier(driver: PollerIoDriver)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    /** Drain all available bytes from `peerFd` so the WRITE fd's send buffer empties and becomes writable again. */
    private def drainAll(peerFd: Int)(using AllowUnsafe): Unit =
        val buf = Buffer.alloc[Byte](65536)
        try
            var more = true
            while more do
                val r = Ffi.load[SocketBindings].recvNow(peerFd, buf, 65536L, PosixConstants.MSG_DONTWAIT)
                if r.value <= 0 then more = false
            end while
        finally buf.close()
        end try
    end drainAll

    "close racing a backpressured TLS flush" - {

        "close while the flush is parked on writability: frees once, clears pending state, no UAF, write-after-close is Error" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                // Gate on BoringSSL specifically, BEFORE any socket exists. assumeTlsReady accepts OpenSSL too, but every leaf here builds its
                // engine with TlsRealEngines.singleEngine, which requires BoringSSL and cancels without it. Cancelling from there instead left
                // the loopback pair already created and unreclaimed, one leaked pair per leaf, which is only visible on a host that has OpenSSL
                // but not BoringSSL.
                if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
                PosixTestSockets.assumePoller()
                // The peer NEVER reads, so the 600 KB write encrypts and fills the real send buffer (EAGAIN with bytes pending) and the flush parks
                // armed on writability that can never fire: the close lands deterministically while the flush is parked. No hook here.
                PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (writeFd, peerFd) =>
                    val payload   = Array.fill[Byte](600000)(42.toByte)
                    val spy       = RecordingSocketBindings(Ffi.load[SocketBindings])
                    val real      = PollerBackend.default()
                    val pollerFd  = real.create()
                    val backend   = RecordingPollerBackend(real)
                    val driver    = TestDrivers.forBackend(backend, pollerFd, spy)
                    val handle    = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
                    val rawEngine = clientEngine()
                    val engine    = new RecordingTlsEngine(rawEngine)
                    handle.tls = Present(engine)
                    discard(driver.start())

                    for
                        // Handshake on the FIFO worker so the client session is created, handshaked, and written on one carrier.
                        _ <- handshakeOnDriver(driver, rawEngine).safe.get
                        // Write into a non-draining socket: the flush EAGAINs with bytes pending and arms writability (registerWrite #1).
                        w <- Sync.defer(driver.write(handle, Span.fromUnsafe(payload), 0))
                        _ = assert(w == WriteResult.Done, s"backpressured write should return Done, got $w")
                        // Latch on the real registerWrite executing on the change worker (the flush arm).
                        _ <- backend.registeredWrite(writeFd).safe.get
                        // Barrier: the write's engine op (including its endWrite, which releases the guard) has fully completed.
                        _ <- fifoBarrier(driver).safe.get
                        // The flush must be PARKED on writability before the close. `flushReArmPending` is not a single stable sample here: the
                        // 600 KB tail over the 4096-byte send buffer parks on the first EAGAIN (registerWrite #1, the latch above), but the kernel
                        // then drains a few KB of the loopback into the peer's recv buffer (the peer never reads, but the recv buffer absorbs a
                        // bounded amount), firing the EPOLLOUT edge that clears `flushReArmPending` and re-submits the flush. The re-flush sends more,
                        // EAGAINs again, and re-arms (`flushReArmPending` true again). `flushReArmPending` therefore toggles false<->true through that bounded
                        // churn before the buffers fill and it settles stably true. Sample it as an eventual condition, not a single instant, so a
                        // transient mid-churn false does not flake the precondition; a genuinely lost re-arm (the real-bug shape) keeps it false and
                        // surfaces as the per-test timeout.
                        _ <- assertEventually(Sync.defer(handle.flushReArmPending))
                        _ = assert(handle.pendingCipher.exists(_.size > handle.pendingCipherSent), "pendingCipher must hold unsent bytes")
                        // Close while the flush is parked. requestClose defers the free to endWrite; two fifoBarriers prove it ran (the close
                        // submits the deferred free op, which a barrier behind it completes after).
                        _ = driver.closeHandle(handle)
                        _ <- fifoBarrier(driver).safe.get
                        _ <- fifoBarrier(driver).safe.get
                        // A write on the closed handle must bail Error.
                        freedBefore = engine.freeCount.get()
                        after <- Sync.defer(driver.write(handle, Span.fromUnsafe(Array[Byte](1, 2)), 0))
                    yield
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, peerFd)
                        discard(assert(engine.freeCount.get() == 1, s"engine must be freed exactly once, was ${engine.freeCount.get()}"))
                        discard(assert(!engine.usedAfterFree.get(), "no engine method may run after free"))
                        discard(assert(!handle.flushReArmPending, "the writable Failure branch must clear flushReArmPending"))
                        discard(assert(handle.pendingCipher.isEmpty, "pendingCipher must be cleared by the close"))
                        discard(assert(handle.pendingCipherSent == 0, "pendingCipherSent must be reset by the close"))
                        discard(assert(handle.tls.isEmpty, "the engine slot must be cleared once freed"))
                        discard(assert(after == WriteResult.Error, s"a write on a closed handle must bail Error, got $after"))
                        discard(assert(
                            spy.closeCounts.getOrDefault(writeFd, 0) == 1,
                            s"closeHandle must have closed writeFd=$writeFd exactly once, counts=${spy.closeCounts}"
                        ))
                        assert(engine.freeCount.get() == freedBefore, "the post-close write must not re-free the engine")
                    end for
                }
        }

        "close fired from inside an in-flight re-submitted flush: free is deferred, runs once, flush sees a live engine (no UAF)" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                // Gate on BoringSSL specifically, BEFORE any socket exists. assumeTlsReady accepts OpenSSL too, but every leaf here builds its
                // engine with TlsRealEngines.singleEngine, which requires BoringSSL and cancels without it. Cancelling from there instead left
                // the loopback pair already created and unreclaimed, one leaked pair per leaf, which is only visible on a host that has OpenSSL
                // but not BoringSSL.
                if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
                PosixTestSockets.assumePoller()
                // The close hook is installed only AFTER the initial write's flush has EAGAINed and armed writability, so the initial flush's
                // sends do not fire it. The spy's onSend fires BEFORE the real send delegate (while beginWrite is held) on the first send of the
                // RE-SUBMITTED flush after the peer is drained. The drain makes the real socket writable, so the real backend fires a real write
                // event the poll loop turns into the re-flush; the hook fires closeHandle re-entrantly with the free deferred to endWrite.
                PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (writeFd, peerFd) =>
                    val payload    = Array.fill[Byte](600000)(43.toByte)
                    val closeFired = new AtomicBoolean(false)
                    val hookFired  = Promise.Unsafe.init[Unit, Any]()
                    val driverBox  = new java.util.concurrent.atomic.AtomicReference[PollerIoDriver]()
                    val handleBox  = new java.util.concurrent.atomic.AtomicReference[PosixHandle]()
                    val spy        = RecordingSocketBindings(Ffi.load[SocketBindings])
                    val real       = PollerBackend.default()
                    val pollerFd   = real.create()
                    val backend    = RecordingPollerBackend(real)
                    val driver     = TestDrivers.forBackend(backend, pollerFd, spy)
                    val handle     = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
                    val rawEngine  = clientEngine()
                    val engine     = new RecordingTlsEngine(rawEngine)
                    handle.tls = Present(engine)
                    driverBox.set(driver)
                    handleBox.set(handle)
                    discard(driver.start())

                    for
                        // Handshake on the FIFO worker so the client session is created, handshaked, and written on one carrier.
                        _ <- handshakeOnDriver(driver, rawEngine).safe.get
                        // Backpressure the write: the flush sends until the buffer fills, EAGAINs with bytes pending, and arms writability.
                        w <- Sync.defer(driver.write(handle, Span.fromUnsafe(payload), 0))
                        _ = assert(w == WriteResult.Done, s"backpressured write should return Done, got $w")
                        _ <- backend.registeredWrite(writeFd).safe.get
                        _ <- fifoBarrier(driver).safe.get
                        // The flush must be PARKED on writability before the close hook is installed. As in the first leaf, `flushReArmPending` toggles
                        // through a bounded not-writable->writable churn (the kernel drains a few KB of the loopback into the peer's recv buffer,
                        // firing the EPOLLOUT edge that clears the arm and re-submits the flush) before settling stably true once the buffers fill, so
                        // it is sampled as an eventual condition rather than a single instant. A genuinely lost re-arm keeps it false and times out.
                        _ <- assertEventually(Sync.defer(handle.flushReArmPending))
                        // Install the close hook NOW (the initial flush has armed): it fires on the first send of the re-flush.
                        _ = spy.onSend = () =>
                            if closeFired.compareAndSet(false, true) then
                                driverBox.get().closeHandle(handleBox.get())
                                hookFired.completeDiscard(Result.succeed(()))
                        // Drain the peer fully: the real socket becomes writable, the real backend fires the write event, and the poll loop
                        // re-submits the flush. The flush acquires beginWrite, calls sockets.send -> onSend fires closeHandle re-entrantly.
                        _ = drainAll(peerFd)
                        // Latch on the close hook actually firing inside the re-flush (a real Promise.Unsafe completed by the onSend hook).
                        _     <- hookFired.safe.get
                        _     <- fifoBarrier(driver).safe.get
                        _     <- fifoBarrier(driver).safe.get
                        after <- Sync.defer(driver.write(handle, Span.fromUnsafe(Array[Byte](7, 8)), 0))
                    yield
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, peerFd)
                        discard(assert(closeFired.get(), "the in-flight-flush close hook must have fired"))
                        discard(assert(
                            engine.freeCount.get() == 1,
                            s"engine must be freed exactly once (deferred), was ${engine.freeCount.get()}"
                        ))
                        discard(assert(!engine.usedAfterFree.get(), "the in-flight flush must run on a live engine; no method after free"))
                        discard(assert(handle.pendingCipher.isEmpty, "pendingCipher must be cleared once the handle is freed"))
                        discard(assert(handle.pendingCipherSent == 0, "pendingCipherSent must be reset"))
                        discard(assert(handle.tls.isEmpty, "the engine slot must be cleared once freed"))
                        discard(assert(
                            spy.closeCounts.getOrDefault(writeFd, 0) == 1,
                            s"closeHandle must have closed writeFd=$writeFd exactly once, counts=${spy.closeCounts}"
                        ))
                        assert(after == WriteResult.Error, s"a write on the closed handle must bail Error, got $after")
                    end for
                }
        }

        "looped concurrent close vs writable event: free-once and no-UAF under any interleaving" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                // Gate on BoringSSL specifically, BEFORE any socket exists. assumeTlsReady accepts OpenSSL too, but every leaf here builds its
                // engine with TlsRealEngines.singleEngine, which requires BoringSSL and cancels without it. Cancelling from there instead left
                // the loopback pair already created and unreclaimed, one leaked pair per leaf, which is only visible on a host that has OpenSSL
                // but not BoringSSL.
                if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
                PosixTestSockets.assumePoller()
                // 10 independent close-vs-writable races, each one fully reproducing the interleaving (the race fires on every iteration, it is not
                // rare). The 600 KB payload over the 4096-byte send buffer is what RELIABLY parks the flush: the engine encrypts the whole plaintext
                // into pendingCipher, the flush sends what the send buffer accepts, EAGAINs with the remainder pending, and arms writability EVERY
                // iteration (the `registeredWrite` latch below fires only from `armWritableForFlush`, so it is the per-iteration park proof). A
                // smaller payload does NOT park reliably: at these shrunk buffers the kernel drains the loopback in the background fast enough that a
                // payload at or below ~256 KB sometimes flushes in full with no EAGAIN, so no writability is armed and the close-vs-writable race
                // window disappears. The per-iteration cost is dominated by encrypting 600 KB up to the park point (~0.3 s on Scala Native, which has
                // no JIT and slower crypto); 50 iterations overran the Test base's 15 s deadlock ceiling, so the count is 10 (~3 s here, comfortable
                // margin for a slower CI host) while keeping the parking mechanism and the free-once / no-UAF assertions below unchanged.
                val iterations = 10

                def oneRace(i: Int): Unit < Async =
                    PosixTestSockets.smallBufferedPair(sndBuf = 4096, rcvBuf = 4096).map { case (writeFd, peerFd) =>
                        val payload   = Array.fill[Byte](600000)(((i % 251) + 1).toByte)
                        val spy       = RecordingSocketBindings(Ffi.load[SocketBindings])
                        val real      = PollerBackend.default()
                        val pollerFd  = real.create()
                        val backend   = RecordingPollerBackend(real)
                        val driver    = TestDrivers.forBackend(backend, pollerFd, spy)
                        val handle    = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
                        val rawEngine = clientEngine()
                        val engine    = new RecordingTlsEngine(rawEngine)
                        handle.tls = Present(engine)
                        discard(driver.start())
                        for
                            // Handshake on the FIFO worker so the client session is created, handshaked, and written on one carrier.
                            _ <- handshakeOnDriver(driver, rawEngine).safe.get
                            w <- Sync.defer(driver.write(handle, Span.fromUnsafe(payload), 0))
                            _ = assert(w == WriteResult.Done, s"iter $i: backpressured write should return Done, got $w")
                            _ <- backend.registeredWrite(writeFd).safe.get
                            // Race: close on one fiber, draining the peer (which fires the real writable event and re-submits a flush) on another.
                            closeFiber <- Fiber.initUnscoped(Sync.defer(driver.closeHandle(handle)))
                            fireFiber  <- Fiber.initUnscoped(Sync.defer(drainAll(peerFd)))
                            _          <- closeFiber.get
                            _          <- fireFiber.get
                            _          <- fifoBarrier(driver).safe.get
                            _          <- fifoBarrier(driver).safe.get
                            after      <- Sync.defer(driver.write(handle, Span.fromUnsafe(Array[Byte](9)), 0))
                        yield
                            driver.close()
                            PosixTestSockets.closePeerForEof(spy, peerFd)
                            discard(assert(
                                engine.freeCount.get() == 1,
                                s"iter $i: engine must be freed exactly once, was ${engine.freeCount.get()}"
                            ))
                            discard(assert(!engine.usedAfterFree.get(), s"iter $i: no engine method may run after free"))
                            discard(assert(handle.pendingCipher.isEmpty, s"iter $i: pendingCipher must be cleared"))
                            discard(assert(handle.pendingCipherSent == 0, s"iter $i: pendingCipherSent must be reset"))
                            discard(assert(handle.tls.isEmpty, s"iter $i: engine slot must be cleared"))
                            discard(assert(after == WriteResult.Error, s"iter $i: write on closed handle must be Error, got $after"))
                        end for
                    }
                end oneRace

                Loop(0) { i =>
                    if i >= iterations then Loop.done(succeed)
                    else oneRace(i).map(_ => Loop.continue(i + 1))
                }
            end if
        }
    }

end CloseDuringBackpressuredFlushTest
