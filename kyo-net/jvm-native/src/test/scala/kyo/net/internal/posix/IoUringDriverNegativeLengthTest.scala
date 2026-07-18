package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Reproduction + regression guard for the io_uring C shim signed-length-to-unsigned cast (CWE-190 / CWE-195 / CWE-805) in
  * [[IoUringBindings]] / [[IoUringDriver]].
  *
  * The `kyo_uring_prep_read` / `prep_write` / `prep_recv` / `prep_send` shim functions take a SIGNED length on the Scala side (`int nbytes` /
  * `long len`) but the underlying `io_uring_prep_*` helpers take an UNSIGNED length (`unsigned` / `size_t`). A negative length cast to unsigned
  * wraps to a huge value, which at the io_uring submission boundary becomes an out-of-bounds kernel read/write. The shim is the C trust
  * boundary where a future signedness bug in a length computation becomes a kernel OOB, so it carries a defensive non-negativity guard: each
  * prep_* function checks the length BEFORE the cast and refuses to prepare the SQE when it is negative, returning -1 (rejected) instead of 0
  * (prepared).
  *
  * The rejection MUST be OBSERVABLE, not a silent SQE drop: a bare drop would leave the ring waiting on a CQE that never arrives (a hang, worse
  * than the theoretical OOB). [[IoUringDriver.submitRecv]] maps a non-zero `prep_recv` return to a failed read promise (`Closed`); the send
  * flush paths leave the in-flight guard clear and re-queue the remainder (the same not-submitted handling as SQ-full).
  *
  * Two leaves:
  *   - The C trust-boundary leaf drives a genuinely negative length straight into the real shim over a real SQE and asserts the shim REJECTS it
  *     (returns -1) while a non-negative length is PREPARED (returns 0). This pins the finding at the boundary it names with a real bad value.
  *   - The driver observable-rejection leaf forces `prep_recv` to reject once (the documented single-value injection, the same style as
  *     [[RecordingIoUringBindings]]'s `cqe_res` override) and asserts the read promise FAILS `Closed` rather than hanging on a never-arriving CQE.
  *
  * Reproduce-first: with the C guard removed (each prep_* returning 0 unconditionally), the boundary leaf FAILS because the negative length is
  * cast and an OOB SQE is prepared (return 0, no rejection); the driver leaf's injected reject path would never be reached so the promise would
  * hang on a real recv. With the guard in place both leaves pass.
  *
  * Gate: [[PosixTestSockets.assumeUring]] (a real io_uring ring at production depth). io_uring is Linux-only and a cgroup-capped host cancels
  * cleanly (TestCanceled), so these are CI-validated on native Linux and cancel locally on a non-Linux / restricted host. The guard itself is
  * platform-shared C; the leaves need a real ring only to obtain a real SQE pointer to prepare against.
  *
  * Anti-flakiness: the boundary leaf is a synchronous in-memory sequence of prep calls over one freshly-initialized ring (no socket, no peer, no
  * timer). The driver leaf synchronizes on the read promise via `Async.timeout` as the deadlock ceiling only; the observable rejection completes
  * the promise synchronously inside `awaitRead`, so it resolves immediately. No sleep, no busy-spin.
  */
class IoUringDriverNegativeLengthTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Allocate a real io_uring ring at production depth, run `body` with the real bindings and ring, then tear the ring down. The leaf is gated
      * by [[PosixTestSockets.assumeUring]], so this is reached only when a real production-depth ring is available.
      */
    private def withRealRing[A](body: (IoUringBindings, Buffer[Byte]) => A)(using Frame): A =
        val uring = Ffi.load[IoUringBindings]
        val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
        val rc    = uring.io_uring_queue_init(math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64), ring, 0)
        if rc != 0 then
            ring.close()
            throw Closed("IoUringDriverNegativeLengthTest", summon[Frame], s"queue_init failed: rc=$rc")
        try body(uring, ring)
        finally
            uring.io_uring_queue_exit(ring)
            ring.close()
        end try
    end withRealRing

    /** A [[RecordingIoUringBindings]] subclass that forces `kyo_uring_prep_recv` to return -1 (the C non-negativity-guard rejection) exactly once
      * after `armReject`, then delegates. Every other ring op runs for real. The same single-value-injection style as the base decorator's
      * documented `cqe_res` override: it exercises the driver's observable-rejection mapping without depending on a contrived negative
      * `readBufferSize` (the [[PosixHandle.socket]] factory allocates a buffer of that size, so a negative one is not constructible there).
      */
    final private class RecvRejectingUring(real: IoUringBindings, realRing: Buffer[Byte])
        extends RecordingIoUringBindings(real, realRing):
        import AllowUnsafe.embrace.danger
        private val injectPending = new java.util.concurrent.atomic.AtomicBoolean(false)

        /** Arm the one-shot prep_recv rejection: the next prep_recv reports -1 (rejected, SQE not prepared) instead of preparing the SQE. */
        def armReject(): Unit = injectPending.set(true)

        override def kyo_uring_prep_recv(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using
            AllowUnsafe
        ): Int =
            recvLens.add(len)
            if injectPending.compareAndSet(true, false) then -1
            else real.kyo_uring_prep_recv(sqe, fd, buf, len, flags)
        end kyo_uring_prep_recv
    end RecvRejectingUring

    "IoUringDriver negative length (CWE-190/195/805)" - {

        "the C shim rejects a negative prep length and prepares a non-negative one" in {
            PosixTestSockets.assumeUring()
            withRealRing { (uring, ring) =>
                // A negative length must be REJECTED at the C trust boundary (return -1, no SQE prepared) so it never reaches the unsigned cast
                // where it would wrap to a huge size and become an OOB kernel read/write. A non-negative length must be PREPARED (return 0), so
                // the guard is not a blanket rejection. Each prep call grabs a fresh SQE from the real ring (depth is well above 5).
                def sqe(): Ffi.Handle[IoUringSqe] =
                    uring.kyo_uring_get_sqe(ring) match
                        case Present(s) => s
                        case Absent     => fail("real ring unexpectedly out of SQEs at production depth")

                val buf = Buffer.alloc[Byte](64)
                try
                    // recv: the prep_recv / prep_send path takes a signed `long len`.
                    assert(uring.kyo_uring_prep_recv(sqe(), 0, buf, -1L, 0) == -1, "prep_recv must reject a negative length")
                    assert(uring.kyo_uring_prep_recv(sqe(), 0, buf, 64L, 0) == 0, "prep_recv must prepare a non-negative length")
                    // send: same signed `long len`.
                    assert(uring.kyo_uring_prep_send(sqe(), 0, buf, -1L, 0) == -1, "prep_send must reject a negative length")
                    assert(uring.kyo_uring_prep_send(sqe(), 0, buf, 64L, 0) == 0, "prep_send must prepare a non-negative length")
                    // read/write: the prep_read / prep_write path takes a signed `int nbytes`.
                    assert(uring.kyo_uring_prep_read(sqe(), 0, buf, -1, 0L) == -1, "prep_read must reject a negative nbytes")
                    assert(uring.kyo_uring_prep_read(sqe(), 0, buf, 64, 0L) == 0, "prep_read must prepare a non-negative nbytes")
                    assert(uring.kyo_uring_prep_write(sqe(), 0, buf, -1, 0L) == -1, "prep_write must reject a negative nbytes")
                    assert(uring.kyo_uring_prep_write(sqe(), 0, buf, 64, 0L) == 0, "prep_write must prepare a non-negative nbytes")
                    succeed
                finally buf.close()
                end try
            }
        }

        "a rejected recv prep fails the read promise observably (Closed), not a silent SQE drop / hang" in {
            PosixTestSockets.assumeUring()
            val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
            val realUring = Ffi.load[IoUringBindings]
            val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
            val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
            if rc != 0 then
                realRing.close()
                throw Closed("RecvRejectingUring", summon[Frame], s"queue_init failed: rc=$rc")
            val recording = new RecvRejectingUring(realUring, realRing)
            val driver    = TestDrivers.forBindings(recording, realRing)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    // Arm the one-shot prep_recv rejection BEFORE awaitRead, so the recv SQE for this read is refused at the C boundary. The
                    // driver must FAIL the read promise observably (Closed) instead of dropping the SQE silently (which would leave the promise
                    // waiting on a CQE that never arrives, a hang). The 5s ceiling exists only to turn a regression (hang) into a failed test.
                    recording.armReject()
                    val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(acceptedH, promise)
                    Abort.run[Timeout | Closed](Async.timeout(5.seconds)(promise.safe.get)).map { outcome =>
                        driver.closeHandle(acceptedH)
                        discard(Ffi.load[SocketBindings].close(client))
                        outcome match
                            case Result.Failure(_: Closed)  => succeed
                            case Result.Failure(_: Timeout) => fail("read hung: rejection was silently dropped, no CQE ever arrived")
                            case other                      => fail(s"expected the read promise to fail Closed, got $other")
                        end match
                    }
                }
            }
        }
    }

end IoUringDriverNegativeLengthTest
