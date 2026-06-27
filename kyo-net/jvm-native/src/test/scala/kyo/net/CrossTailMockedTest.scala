package kyo.net

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.posix.IoUringBindings
import kyo.net.internal.posix.IoUringDriver
import kyo.net.internal.posix.IoUringSqe
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.SocketBindings
import kyo.net.internal.posix.TestDrivers
import kyo.net.internal.tls.TlsEngine

/** Deterministic mechanism test for the io_uring STARTTLS raw->TLS cross-tail send wire-order invariant
  * using stub bindings only, no real ring, no TLS provider, no platform gate.
  *
  * Covers the same two mechanisms as CrossTailSendOrderTest:
  *   - DEFER: with rawSendInFlight set, a writeTls call must NOT submit a TLS send SQE.
  *   - KICK: when onRawSendComplete fires (via the real CQE processing path in the driver), it must
  *     submit the deferred TLS SQE.
  *
  * The StubIoUringBindings parks the reap carrier on a Java monitor (no real io_uring ring), so the
  * tests are deterministic on every platform the test suite compiles for. CQEs are injected by the
  * test fiber via injectRawCqe, which writes the CQE key into the monitor state and notifies the
  * reap carrier. Assertions use FIFO-barrier promises and a send-barrier promise; no Thread.sleep.
  */
class CrossTailMockedTest extends Test:

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

    // StubSocketBindings: all syscalls are no-ops; only shutdown and close matter at driver teardown.
    private object StubSocketBindings extends SocketBindings:
        override def socket(domain: Int, `type`: Int, protocol: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            Ffi.Outcome.fromValueErrno[Int](0L, 0)
        override def bind(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            Ffi.Outcome.fromValueErrno[Int](0L, 0)
        override def listen(fd: Int, backlog: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            Ffi.Outcome.fromValueErrno[Int](0L, 0)
        override def setsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Int)(using
            AllowUnsafe
        ): Ffi.Outcome[Int] =
            Ffi.Outcome.fromValueErrno[Int](0L, 0)
        override def getsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Buffer[Int])(using
            AllowUnsafe
        ): Ffi.Outcome[Int] =
            Ffi.Outcome.fromValueErrno[Int](0L, 0)
        override def getsockname(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
            Ffi.Outcome.fromValueErrno[Int](0L, 0)
        override def getpeername(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
            Ffi.Outcome.fromValueErrno[Int](0L, 0)
        override def fstat(fd: Int, buf: Buffer[Byte])(using AllowUnsafe): Ffi.Outcome[Int] =
            Ffi.Outcome.fromValueErrno[Int](0L, 0)
        override def shutdown(fd: Int, how: Int)(using AllowUnsafe): Int = 0
        override def connect(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any] =
            Fiber.Unsafe.fromResult(Result.succeed(Ffi.Outcome.fromValueErrno[Int](0L, 0)))
        override def accept(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any] =
            Fiber.Unsafe.fromResult(Result.succeed(Ffi.Outcome.fromValueErrno[Int](0L, 0)))
        override def recv(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
            Fiber.Unsafe.fromResult(Result.succeed(Ffi.Outcome.fromValueErrno[Long](0L, 0)))
        override def send(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
            Fiber.Unsafe.fromResult(Result.succeed(Ffi.Outcome.fromValueErrno[Long](0L, 0)))
        override def sendNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long] =
            Ffi.Outcome.fromValueErrno[Long](0L, 0)
        override def recvNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long] =
            Ffi.Outcome.fromValueErrno[Long](0L, 0)
        override def acceptNow(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
            Ffi.Outcome.fromValueErrno[Int](0L, 0)
        override def read(fd: Int, buf: Buffer[Byte], count: Long)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
            Fiber.Unsafe.fromResult(Result.succeed(Ffi.Outcome.fromValueErrno[Long](0L, 0)))
        override def close(fd: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any] =
            Fiber.Unsafe.fromResult(Result.succeed(0))
    end StubSocketBindings

    /** Monitor-based io_uring stub: no real ring, CQE injection via injectRawCqe, send counting.
      *
      * CQE addressing: the injected CQE "address" IS the key (cqe_get_data64(addr) = addr). WakeKey = -1L
      * is the sentinel for the eventfd multishot poll CQE. The sqe_set_data64 stub filters WakeKey so that
      * armWake's set_data64(-1L) call does not overwrite lastDataKey.
      *
      * Parking: kyo_uring_submit_and_wait_timeout blocks on a Java monitor for up to 50 ms. An injected
      * CQE or eventfd write wakes it; a timeout emits a WakeKey CQE so the reap loop cycles harmlessly.
      */
    private class StubIoUringBindings extends IoUringBindings:
        val sendCount: AtomicInteger                = new AtomicInteger(0)
        @volatile var lastDataKey: Long             = 0L
        @volatile private var sendBarrierTarget     = Int.MaxValue
        val sendBarrierP: Promise.Unsafe[Unit, Any] = Promise.Unsafe.init[Unit, Any]()

        private val monitor                        = new Object
        @volatile private var woken                = false
        @volatile private var pendingCqeKey: Long  = 0L
        @volatile private var pendingCqeRes: Int   = 0
        @volatile private var deliveredCqeRes: Int = 0

        // Constants private to IoUringDriver; replicated here for the stub.
        private val WakeKeyConst: Long = -1L // IoUringDriver.WakeKey
        private val FeatNodrop: Int    = 2   // IoUringDriver.FeatNodrop = 1 << 1
        private val CqeFMore: Int      = 2   // IORING_CQE_F_MORE
        private val MaxWaitMs: Long    = 50L

        def setSendBarrier(target: Int): Unit = sendBarrierTarget = target

        /** Wake the reap carrier with a fake raw CQE so that complete(key, res) fires onRawSendComplete. */
        def injectRawCqe(key: Long, res: Int): Unit =
            monitor.synchronized {
                pendingCqeKey = key
                pendingCqeRes = res
                woken = true
                monitor.notifyAll()
            }

        // --- real liburing exports ---

        override def io_uring_queue_init(entries: Int, ring: Buffer[Byte], flags: Int)(using AllowUnsafe): Int = 0
        override def io_uring_queue_exit(ring: Buffer[Byte])(using AllowUnsafe): Unit                          = ()
        override def io_uring_submit(ring: Buffer[Byte])(using AllowUnsafe): Int                               = 1

        // --- kyo_uring.c shim wrappers ---

        override def kyo_uring_sizeof()(using AllowUnsafe): Long = 256L

        override def kyo_uring_get_sqe(ring: Buffer[Byte])(using AllowUnsafe): Maybe[Ffi.Handle[IoUringSqe]] =
            Present(Ffi.Handle.wrap[IoUringSqe](new Object))

        override def kyo_uring_prep_read(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], nbytes: Int, offset: Long)(using
            AllowUnsafe
        ): Int = 0

        override def kyo_uring_prep_write(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], nbytes: Int, offset: Long)(using
            AllowUnsafe
        ): Int = 0

        override def kyo_uring_prep_recv(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using
            AllowUnsafe
        ): Int = 0

        // Count every send SQE; fire sendBarrierP when the target is reached.
        override def kyo_uring_prep_send(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using
            AllowUnsafe
        ): Int =
            val n = sendCount.incrementAndGet()
            if n >= sendBarrierTarget then sendBarrierP.completeDiscard(Result.succeed(()))
            0
        end kyo_uring_prep_send

        override def kyo_uring_prep_accept(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int], flags: Int)(using
            AllowUnsafe
        ): Unit = ()

        override def kyo_uring_prep_connect(sqe: Ffi.Handle[IoUringSqe], fd: Int, addr: Buffer[Byte], addrlen: Int)(using
            AllowUnsafe
        ): Unit = ()

        override def kyo_uring_prep_poll_multishot(sqe: Ffi.Handle[IoUringSqe], fd: Int, pollMask: Int)(using AllowUnsafe): Unit = ()

        // armWake calls set_data64(sqe, WakeKey=-1L); filter it so the raw-send key in lastDataKey
        // is not overwritten before the KICK test captures it.
        override def kyo_uring_sqe_set_data64(sqe: Ffi.Handle[IoUringSqe], data: Long)(using AllowUnsafe): Unit =
            if data != WakeKeyConst then lastDataKey = data

        // Not called by the reap loop (submit_and_wait_timeout is the active path); no-op.
        override def kyo_uring_wait_cqe_timeout(ring: Buffer[Byte], cqePtr: Buffer[Long], timeoutNs: Long)(using
            AllowUnsafe
        ): Fiber.Unsafe[Int, Any] =
            Fiber.Unsafe.fromResult(Result.succeed(0))

        // The main parking point for the reap carrier. Blocks on the Java monitor until a CQE is
        // injected, an eventfd write fires, or MaxWaitMs elapses. Writes the CQE address into cqePtr
        // (either the real injected key or WakeKeyConst for an empty turn).
        override def kyo_uring_submit_and_wait_timeout(ring: Buffer[Byte], cqePtr: Buffer[Long], timeoutNs: Long)(using
            AllowUnsafe
        ): Fiber.Unsafe[Int, Any] =
            monitor.synchronized {
                val deadline       = java.lang.System.currentTimeMillis() + MaxWaitMs
                var deadlinePassed = false
                while !woken && pendingCqeKey == 0L && !deadlinePassed do
                    val remaining = deadline - java.lang.System.currentTimeMillis()
                    if remaining > 0 then monitor.wait(remaining)
                    else deadlinePassed = true
                end while
                if pendingCqeKey != 0L then
                    val key = pendingCqeKey
                    deliveredCqeRes = pendingCqeRes
                    pendingCqeKey = 0L
                    cqePtr.set(0, key)
                else
                    // Either eventfd write (woken=true) or deadline: emit WakeKey for an empty cycle.
                    woken = false
                    cqePtr.set(0, WakeKeyConst)
                end if
            }
            Fiber.Unsafe.fromResult(Result.succeed(0))
        end kyo_uring_submit_and_wait_timeout

        override def kyo_uring_kernel_version()(using AllowUnsafe): Int                 = 0
        override def kyo_uring_get_features(ring: Buffer[Byte])(using AllowUnsafe): Int = FeatNodrop

        override def kyo_uring_prep_multishot_accept(
            sqe: Ffi.Handle[IoUringSqe],
            fd: Int,
            addr: Buffer[Byte],
            addrlen: Buffer[Int],
            flags: Int
        )(using AllowUnsafe): Unit = ()

        // WakeKey CQEs carry CqeFMore so the multishot poll stays armed without re-arming.
        override def kyo_uring_cqe_get_flags(cqe: Long)(using AllowUnsafe): Int =
            if cqe == WakeKeyConst then CqeFMore else 0

        override def kyo_uring_recv_multishot_flag()(using AllowUnsafe): Int = 0

        // Always report the completion queue empty; CQEs are injected only via submit_and_wait_timeout.
        override def kyo_uring_peek_cqe(ring: Buffer[Byte], cqePtr: Buffer[Long])(using AllowUnsafe): Int = -1

        // The CQE "address" IS the key: cqe_get_data64(addr) = addr.
        override def kyo_uring_cqe_get_data64(cqe: Long)(using AllowUnsafe): Long = cqe

        override def kyo_uring_cqe_res(cqe: Long)(using AllowUnsafe): Int =
            if cqe == WakeKeyConst then 0 else deliveredCqeRes

        override def kyo_uring_cqe_seen(ring: Buffer[Byte], cqe: Long)(using AllowUnsafe): Unit = ()

        // eventfd: create returns a valid fd; write wakes the monitor; read and close are no-ops.
        override def kyo_uring_eventfd_create(initval: Int, flags: Int)(using AllowUnsafe): Int = 1

        override def kyo_uring_eventfd_write(fd: Int)(using AllowUnsafe): Int =
            monitor.synchronized { woken = true; monitor.notifyAll() }
            0

        override def kyo_uring_eventfd_read(fd: Int)(using AllowUnsafe): Int  = 0
        override def kyo_uring_eventfd_close(fd: Int)(using AllowUnsafe): Int = 0

        override def kyo_uring_probe_available(depth: Int)(using AllowUnsafe): Boolean = false
    end StubIoUringBindings

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

end CrossTailMockedTest
