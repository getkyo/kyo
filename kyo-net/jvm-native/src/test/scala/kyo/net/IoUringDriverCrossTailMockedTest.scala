package kyo.net

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.TlsEngine
import kyo.net.internal.posix.IoUringBindings
import kyo.net.internal.posix.IoUringDriver
import kyo.net.internal.posix.IoUringSqe
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.StubIoUringBindings
import kyo.net.internal.posix.StubSocketBindings
import kyo.net.internal.posix.TestDrivers

/** Deterministic mechanism test for the io_uring STARTTLS raw->TLS cross-tail send wire-order invariant
  * using stub bindings only, no real ring, no TLS provider, no platform gate.
  *
  * Covers the same two mechanisms as IoUringDriverCrossTailSendOrderTest:
  *   - DEFER: with rawSendInFlight set, a writeTls call must NOT submit a TLS send SQE.
  *   - KICK: when onRawSendComplete fires (via the real CQE processing path in the driver), it must
  *     submit the deferred TLS SQE.
  *
  * The StubIoUringBindings parks the reap carrier on a Java monitor (no real io_uring ring), so the
  * tests are deterministic on every platform the test suite compiles for. CQEs are injected by the
  * test fiber via injectRawCqe, which writes the CQE key into the monitor state and notifies the
  * reap carrier. Assertions use FIFO-barrier promises and a send-barrier promise; no Thread.sleep.
  */
class IoUringDriverCrossTailMockedTest extends Test:

    import AllowUnsafe.embrace.danger

    // PassThroughEngine: writePlain stores the byte count; drainCiphertext returns it once then 0
    // so encryptPlaintext terminates after one record (no infinite drain loop).
    private class PassThroughEngine extends TlsEngine:
        private var pendingLen = 0
        override def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
            pendingLen = len
            len
        override def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
            val n = pendingLen
            pendingLen = 0
            n
        end drainCiphertext
        override def handshakeStep()(using AllowUnsafe): Int                             = 1
        override def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int = 0
        override def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = 0
        override def hasBufferedPlaintext(using AllowUnsafe): Boolean                    = false
        override def readBuffered()(using AllowUnsafe): Span[Byte]                       = Span.empty
        override def certSha256()(using AllowUnsafe): Maybe[Span[Byte]]                  = Absent
        override def shutdownStep()(using AllowUnsafe): Int                              = 0
        override def free()(using AllowUnsafe): Unit                                     = ()
    end PassThroughEngine

    /** Submit a marker engine op; the returned promise completes once the FIFO worker processes it,
      * proving every engine op enqueued before it has already run.
      */
    private def fifoBarrier(drv: IoUringDriver): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        drv.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    /** Build a driver over stub bindings, start the reap loop, run body, then close the driver. */
    private def withMockedDriver[A](
        body: (IoUringDriver, StubIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val stub = new StubIoUringBindings
        val ring = Buffer.alloc[Byte](stub.kyo_uring_sizeof().toInt)
        val drv  = TestDrivers.forBindings(stub, ring, StubSocketBindings)
        discard(drv.start())
        Sync.ensure(Sync.defer(drv.close()))(body(drv, stub))
    end withMockedDriver

    "cross-tail send wire-order: ringless mocked-bindings (no io_uring, no TLS, all platforms)" - {

        /** DEFER: flushTls must not submit a TLS send SQE while rawSendInFlight is set. */
        "defer: with rawSendInFlight set a writeTls must not submit a TLS send SQE" in {
            withMockedDriver { (drv, stub) =>
                val handle = PosixHandle.socket(42, PosixHandle.DefaultReadBufferSize, Absent)
                // Post-STARTTLS state: TLS engine installed, handshake's final raw flight still in flight.
                handle.tls = Present(new PassThroughEngine)
                handle.engineFreeSink = _ => ()
                handle.rawSendInFlight = true

                val plain = Array.tabulate[Byte](16)(i => (i + 1).toByte)

                // drv.write routes to writeTls (tls is Present), enqueues a FIFO op: encryptPlaintext
                // then flushTls. flushTls sees rawSendInFlight = true and DEFERS: no prep_send.
                discard(drv.write(handle, Span.fromUnsafe(plain), 0))

                // FIFO barrier: fires only after the writeTls FIFO op has fully run.
                Abort.run[Timeout | Closed](Async.timeout(30.seconds)(fifoBarrier(drv).safe.get)).map { _ =>
                    handle.tls = Absent
                    drv.closeHandle(handle)
                    assert(
                        stub.sendCount.get() == 0,
                        s"the defer must fire: no kyo_uring_prep_send may be called while rawSendInFlight " +
                            s"is set, but got ${stub.sendCount.get()} send(s)"
                    )
                }
            }
        }

        /** KICK: onRawSendComplete (via the real CQE path in the driver) must submit the deferred TLS SQE. */
        "kick: onRawSendComplete must submit the deferred TLS SQE after the raw CQE reaps" in {
            withMockedDriver { (drv, stub) =>
                // Start with tls=Absent so the first write goes through writeRaw, producing a
                // PendingOp.Write in the driver's pending map and recording rawKey in lastDataKey.
                val handle = PosixHandle.socket(42, PosixHandle.DefaultReadBufferSize, Absent)

                val rawBytes = Array.tabulate[Byte](16)(i => (i + 1).toByte)
                val tlsBytes = Array.tabulate[Byte](16)(i => (i + 17).toByte)

                // Write 1: tls=Absent -> writeRaw -> flushRaw registers PendingOp.Write with rawKey=1L.
                discard(drv.write(handle, Span.fromUnsafe(rawBytes), 0))

                // FIFO barrier 1: confirms flushRaw has run; rawKey is now stable in lastDataKey.
                Abort.run[Timeout | Closed](Async.timeout(30.seconds)(fifoBarrier(drv).safe.get)).flatMap { _ =>
                    val rawKey = stub.lastDataKey

                    // Upgrade to TLS: install engine; engineFreeSink is a no-op so the test owns lifetime.
                    handle.tls = Present(new PassThroughEngine)
                    handle.engineFreeSink = _ => ()

                    // Write 2: tls=Present, rawSendInFlight=true (set by flushRaw above) -> writeTls
                    // enqueues FIFO op: encryptPlaintext + deferred flushTls (no SQE submitted yet).
                    discard(drv.write(handle, Span.fromUnsafe(tlsBytes), 0))

                    // FIFO barrier 2: confirms the writeTls FIFO op ran and flushTls was deferred.
                    Abort.run[Timeout | Closed](Async.timeout(30.seconds)(fifoBarrier(drv).safe.get)).flatMap { _ =>
                        assert(
                            !handle.sendInFlight,
                            "after the defer: sendInFlight must be false (TLS SQE was deferred, not submitted)"
                        )
                        val sendsBefore = stub.sendCount.get()

                        // Set the barrier BEFORE injecting the CQE so it is always visible to the
                        // kyo_uring_prep_send call that onRawSendComplete triggers.
                        stub.setSendBarrier(sendsBefore + 1)

                        // Inject the raw send CQE: reap loop calls complete(rawKey, 16) ->
                        // submitEngineOp { onRawSendComplete } -> flushTls -> prep_send -> sendBarrierP.
                        stub.injectRawCqe(rawKey, 16)

                        Abort.run[Timeout | Closed](Async.timeout(30.seconds)(stub.sendBarrierP.safe.get)).map { _ =>
                            handle.tls = Absent
                            drv.closeHandle(handle)
                            assert(
                                stub.sendCount.get() > sendsBefore,
                                s"the kick must fire: kyo_uring_prep_send must be called after the raw CQE " +
                                    s"reaps, but sendCount before=$sendsBefore after=${stub.sendCount.get()}"
                            )
                        }
                    }
                }
            }
        }
    }

end IoUringDriverCrossTailMockedTest
