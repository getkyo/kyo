package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Integration test for [[IoUringBindings]]: the `kyo_uring_*` shim symbols resolve and a probe + ring-init round-trips.
  *
  * The `kyonet_posix_uring` shim (with liburing statically linked) is built by the kyo-net build. When it is not built, and on every non-Linux
  * host, the library is absent, so this test skips: it cancels unless the binding loads AND `kyo_uring_probe_available` succeeds on a Linux kernel >= 5.6. When
  * the shim is present (the final Linux gate), it asserts the bound symbols resolve: the probe returns true, `kyo_uring_sizeof` is positive,
  * a ring inits with errno 0, and an SQE is obtained.
  */
class IoUringBindingsTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Load the binding and run the probe; cancel the test unless io_uring is genuinely available on this host. */
    private def loadOrSkip(): IoUringBindings =
        if !PosixConstants.isLinux then cancel("io_uring is Linux-only")
        val loaded =
            try Maybe(Ffi.load[IoUringBindings])
            catch case _: Throwable => Maybe.empty[IoUringBindings]
        val b = loaded.getOrElse(cancel("kyonet_posix_uring shim not built/available (Linux gate)"))
        val available =
            try b.kyo_uring_probe_available(math.max(256, kyo.net.ioPoolSize() * 64))
            catch case _: Throwable => false
        if !available then cancel("io_uring_setup unavailable on this kernel/runtime")
        b
    end loadOrSkip

    "IoUringBindings" - {
        "bound kyo_uring_* symbols resolve and a probe + ring init round-trips" in {
            val b        = loadOrSkip()
            val ringSize = b.kyo_uring_sizeof()
            assert(ringSize > 0L)
            val ring = Buffer.alloc[Byte](ringSize.toInt)
            try
                // io_uring_queue_init returns 0 on success or -errno; the return value is the success signal. Plain Int.
                val init = b.io_uring_queue_init(8, ring, 0)
                assert(init == 0, s"io_uring_queue_init returned $init")
                try
                    val sqe = b.kyo_uring_get_sqe(ring)
                    assert(sqe.isDefined, "get_sqe returned NULL on a fresh ring")
                finally b.io_uring_queue_exit(ring)
                end try
            finally ring.close()
            end try
        }
    }
end IoUringBindingsTest
