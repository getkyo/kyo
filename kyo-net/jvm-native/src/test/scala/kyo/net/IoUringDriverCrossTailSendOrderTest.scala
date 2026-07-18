package kyo.net

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.posix.IoUringBindings
import kyo.net.internal.posix.IoUringDriver
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTestSockets
import kyo.net.internal.posix.RecordingIoUringBindings
import kyo.net.internal.posix.SocketBindings
import kyo.net.internal.posix.TestDrivers

/** Deterministic mechanism test for the io_uring STARTTLS raw->TLS cross-tail send wire-order invariant.
  *
  * During a STARTTLS upgrade the handshake's final flight is sent as a raw send SQE (via writeRaw while handle.tls is Absent). Before that
  * SQE's CQE reaps, the upgrade flips handle.tls to Present and the first post-upgrade writeTls enqueues a TLS app-data FIFO op. If
  * flushTls submitted the TLS SQE immediately it would place two send SQEs on one fd that io_uring may reap out of order, reordering the
  * wire bytes so the peer reads a byte-shifted record and aborts with bad_record_mac.
  *
  * Two mechanisms keep the wire order:
  *   - DEFER: flushTls returns without submitting when rawSendInFlight is set or the raw tail has unsent bytes.
  *   - KICK: onRawSendComplete calls flushTls after fully draining the raw tail, sending the deferred TLS bytes.
  *
  * These are the primary deterministic repros: removing the defer condition from flushTls makes them fail; the mechanism intact makes them
  * pass. They run on a real io_uring ring with a real BoringSSL engine so the ciphertext is genuine.
  *
  * Leaf 1 (raw-in-flight-defers-tls-send): sets rawSendInFlight on the handle, submits a writeTls, waits for the FIFO op via a FIFO
  * barrier, and asserts that no TLS send SQE was submitted (defer fired). Leaf 2 (raw-drain-kick-sends-tls): continues from the deferred
  * state, simulates the raw tail draining (clears rawSendInFlight and rawPending), submits another writeTls, and asserts that a TLS send
  * SQE is now submitted (the deferred ciphertext is sent once the raw block clears).
  *
  * Gate: PosixTestSockets.assumeUring() (cancels cleanly off Linux or where the production ring cannot init) and
  * TlsRealEngines.assumeTlsReady() (cancels when no TLS provider is staged). Anti-flakiness: all waits use FIFO-barrier promises
  * (submitEngineOp thunk submitted after the test op); no sleep. Async.timeout is only the deadlock ceiling.
  */
class IoUringDriverCrossTailSendOrderTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Allocate a real io_uring ring at production depth, wrap it in a recording spy, build a driver, run body, then close the driver. */
    private def withRecordingDriver[A](
        body: (IoUringDriver, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("IoUringDriverCrossTailSendOrderTest", summon[Frame], s"io_uring_queue_init failed: rc=$rc")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))
    end withRecordingDriver

    /** Submit a marker engine op and return a promise that completes once the FIFO worker runs it, proving every engine op submitted before it
      * (including the preceding writeTls FIFO op) has run.
      */
    private def fifoBarrier(drv: IoUringDriver): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        drv.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    "cross-tail send wire-order: io_uring raw->TLS STARTTLS transition (real ring, real BoringSSL engine)" - {

        "raw-in-flight-defers-tls-send: flushTls must not submit a TLS SQE while rawSendInFlight is set" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeTlsReady()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the test write")
                withRecordingDriver { (drv, recording) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        // Post-STARTTLS state: upgrade has fired, TLS engine is present.
                        handle.tls = Present(serverEngine)
                        // Prevent double-free: the driver calls engineFreeSink when it tears down the handle, but withEngines
                        // owns the single real free. A no-op sink keeps the native session alive for withEngines.
                        handle.engineFreeSink = _ => ()
                        // Simulate: the handshake's final raw flight is still in flight (its send SQE has not reaped yet).
                        handle.rawSendInFlight = true

                        val plain       = Array.tabulate[Byte](16)(i => (i + 1).toByte)
                        val sendsBefore = recording.sendBufs.size()

                        // drv.write routes to writeTls (handle.tls is Present) and enqueues a FIFO op:
                        //   appendPending (encrypts plain into pendingCipher) then flushTls.
                        // flushTls sees rawSendInFlight = true and DEFERS: no kyo_uring_prep_send must be called.
                        discard(drv.write(handle, Span.fromUnsafe(plain), 0))

                        // FIFO barrier: submitted after the writeTls FIFO op, so it fires only after that op has fully run.
                        Abort.run[Timeout | Closed](Async.timeout(30.seconds)(fifoBarrier(drv).safe.get)).map { _ =>
                            val sendsAfter = recording.sendBufs.size()
                            // Teardown: clear tls before closeHandle so the driver does not free the engine (withEngines owns the free).
                            handle.tls = Absent
                            drv.closeHandle(handle)
                            discard(sock.close(peerFd))
                            assert(
                                sendsAfter == sendsBefore,
                                s"the defer must fire: no kyo_uring_prep_send may be called while rawSendInFlight is set, " +
                                    s"but got ${sendsAfter - sendsBefore} extra send(s)"
                            )
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "raw-drain-kick-sends-tls: once rawSendInFlight clears, flushTls must submit the deferred TLS SQE" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeTlsReady()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the test writes")
                withRecordingDriver { (drv, recording) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(serverEngine)
                        handle.engineFreeSink = _ => ()
                        // Simulate: raw send in flight (the handshake's final flight).
                        handle.rawSendInFlight = true

                        val plain = Array.tabulate[Byte](16)(i => (i + 1).toByte)

                        // Write 1: rawSendInFlight = true -> flushTls defers; ciphertext lands in pendingCipher, no SQE submitted.
                        discard(drv.write(handle, Span.fromUnsafe(plain), 0))

                        // FIFO barrier 1: confirms the Write 1 FIFO op (appendPending + deferred flushTls) has fully run.
                        Abort.run[Timeout | Closed](Async.timeout(30.seconds)(fifoBarrier(drv).safe.get)).flatMap { _ =>
                            assert(
                                !handle.sendInFlight,
                                "after the defer: sendInFlight must be false (the TLS SQE was deferred, not submitted)"
                            )

                            // Simulate: the raw tail is now fully drained (onRawSendComplete would clear these after the raw CQE reaps).
                            handle.rawSendInFlight = false
                            handle.rawPending = Absent

                            val sendsBefore = recording.sendBufs.size()

                            // Write 2: rawSendInFlight is now clear, rawTailHasUnsent is false; flushTls must submit the deferred
                            // ciphertext (from Write 1) plus any new ciphertext from Write 2 as a TLS send SQE.
                            discard(drv.write(handle, Span.fromUnsafe(plain), 0))

                            // FIFO barrier 2: fires after the Write 2 FIFO op runs, which called flushTls and submitted the SQE.
                            Abort.run[Timeout | Closed](Async.timeout(30.seconds)(fifoBarrier(drv).safe.get)).map { _ =>
                                val sendsAfter = recording.sendBufs.size()
                                handle.tls = Absent
                                drv.closeHandle(handle)
                                discard(sock.close(peerFd))
                                assert(
                                    sendsAfter > sendsBefore,
                                    s"the kick must fire: kyo_uring_prep_send must be called once rawSendInFlight is cleared, " +
                                        s"but sendBufs before=$sendsBefore after=$sendsAfter"
                                )
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringDriverCrossTailSendOrderTest
