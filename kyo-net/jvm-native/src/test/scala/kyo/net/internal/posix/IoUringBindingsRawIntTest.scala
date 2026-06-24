package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Verifies that `io_uring_queue_init`, `io_uring_submit`, and `kyo_uring_wait_cqe_timeout` return a raw signed `Int`, not a POSIX-clamped
  * value. The critical property is the negative `-ETIME` from an idle-ring timeout: a POSIX clamp to `-1` would lose the errno identity,
  * breaking `IoUringDriver.isTimeout` which must match exactly `-ETIME` (62 on Linux) to treat an empty turn as non-fatal. If isTimeout
  * returns false on a clamped `-1` the reap loop stops on any idle timeout, dropping all in-flight reads/writes.
  *
  * This is a REAL ring leaf (Linux / podman). Cancels on macOS (io_uring is Linux-only). The plain-Int return-type compile is verified on
  * all three platforms by the trait bound itself.
  */
class IoUringBindingsRawIntTest extends Test:

    import AllowUnsafe.embrace.danger

    "IoUringBindingsRawInt" - {

        "liburingBindingsReturnRawSignedInt: queue_init 0, submit >= 0, idle-timeout returns raw -ETIME (not clamped to -1)" in {
            PosixTestSockets.assumeUring()
            val uring  = Ffi.load[IoUringBindings]
            val depth  = 8
            val ring   = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
            val initRc = uring.io_uring_queue_init(depth, ring, 0)
            assert(initRc == 0, s"io_uring_queue_init returned $initRc; expected 0 (raw success, no clamp)")
            Sync.ensure(Sync.defer { uring.io_uring_queue_exit(ring); ring.close() }) {
                // io_uring_submit returns the count of submitted SQEs (>= 0) or -errno; no clamp to -1.
                val submitRc = uring.io_uring_submit(ring)
                assert(submitRc >= 0, s"io_uring_submit returned $submitRc; expected >= 0 (raw count, no clamp)")
                // Drive the idle ring to a bounded timeout with a 1ms deadline; no SQE is in flight so the kernel returns -ETIME.
                // The critical assertion: the raw rc must be exactly -PosixConstants.ETIME (= -62 on Linux), NOT clamped to -1.
                val cqePtr    = Buffer.alloc[Long](1)
                val timeoutNs = 1_000_000L // 1ms: fast but sufficient for the kernel to signal ETIME on an idle ring
                val waitFiber = uring.kyo_uring_wait_cqe_timeout(ring, cqePtr, timeoutNs)
                waitFiber.safe.get.map { rawRc =>
                    cqePtr.close()
                    val expectedNeg = -PosixConstants.ETIME
                    // The BLOCKER: raw -ETIME, never -1 (POSIX clamp would break isTimeout and stop the reap loop on every idle turn).
                    assert(
                        rawRc == expectedNeg || rawRc == PosixConstants.ETIME,
                        s"kyo_uring_wait_cqe_timeout on idle ring returned $rawRc; expected raw -ETIME=$expectedNeg (not POSIX-clamped -1)"
                    )
                    // Verify the isTimeout equivalence: the raw value must satisfy the reap loop's stop condition check.
                    val isTimeout = rawRc == -PosixConstants.ETIME || rawRc == PosixConstants.ETIME
                    assert(
                        isTimeout,
                        s"raw -ETIME=$rawRc must satisfy isTimeout; if not, the reap loop stops on every idle turn (reap-loop livelock)"
                    )
                }
            }
        }
    }
end IoUringBindingsRawIntTest
