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
        // Reproduces the RC6 macOS/arm64 Native link failure. The binding is generated when kyo is COMPILED but linked on the
        // consumer's host, so an artifact published from Linux carried `@extern` declarations for all 30 `kyo_uring_*` / `io_uring_*`
        // symbols while `kyo_uring.c` compiled to nothing off Linux, and a macOS `nativeLink` died with "symbol(s) not found for
        // architecture arm64". Building kyo on macOS produced the mirror-image artifact: a throwing Scala stub. Both are the build host
        // deciding a target-platform question. With the shim defining every symbol on every platform, the probe resolves and answers
        // with a value. Off Linux this case FAILED before the fix (UnsupportedOperationException from the generated stub on Native, a
        // symbol lookup failure on the JVM), and the whole Native test binary failed to link when the bindings were emitted for real.
        "is callable on every platform: io_uring's absence is a false probe, not a missing symbol" in {
            val b = Ffi.load[IoUringBindings]
            if PosixConstants.isLinux then
                // On Linux availability depends on the kernel and the sandbox, so pin what is platform-independent instead: the ring
                // size the driver allocates against is a real, positive figure rather than the stub's 0.
                assert(b.kyo_uring_sizeof() > 0L)
            else
                assert(!b.kyo_uring_probe_available(256))
                assert(b.kyo_uring_sizeof() == 0L)
            end if
            succeed
        }

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
