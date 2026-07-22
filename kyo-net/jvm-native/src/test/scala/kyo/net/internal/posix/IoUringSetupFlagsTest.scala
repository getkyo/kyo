package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Verifies that the SETUP-flag tier selector is correct, so `io_uring_queue_init` is never called with a flag set the running kernel rejects.
  *
  * Three leaves:
  *
  *   1. `selectRingFlagsByVersion`: pure-function test of `IoUringDriver.selectRingFlags`. Platform-agnostic (no FFI). Real representative inputs
  *      per tier plus off-by-one boundary values. Asserts concrete flag values.
  *   2. `kernelVersionProbeIsReal`: exercises the REAL `kyo_uring_kernel_version()` uname FFI on the test host. On Linux it must return a plausible
  *      packed version (> 0, >= 5000 for any io_uring-capable kernel). Cancels on non-Linux: the io_uring shim is Linux-only and loading it off-Linux
  *      hangs, so the probe runs only where the shim exists.
  *   3. `queueInitSucceedsWithProbedFlags`: on a real Linux ring (podman), `queue_init` with the probe-selected flags returns 0. Cancels on
  *      macOS (io_uring is Linux-only).
  */
class IoUringSetupFlagsTest extends Test:

    import AllowUnsafe.embrace.danger

    // Flag constants matching the values in IoUringDriver companion (stable liburing ABI).
    private val CoopTaskrun  = 1 << 8
    private val TaskrunFlag  = 1 << 9
    private val Tier519Flags = CoopTaskrun | TaskrunFlag

    "IoUringSetupFlags" - {

        "selectRingFlagsByVersion: pure selector maps each tier correctly with correct boundary values" in {
            // Tier >= 5.19 (5019): COOP_TASKRUN|TASKRUN_FLAG. SINGLE_ISSUER and DEFER_TASKRUN are excluded because both
            // flags constrain io_uring_enter to the ring-creator thread; the driver creates the ring in init() and runs
            // the reap loop on a different fiber carrier, so those flags cause -EEXIST on the reap carrier's io_uring_enter.
            assert(IoUringDriver.selectRingFlags(5019) == Tier519Flags, s"5019 expected tier-5.19 flags $Tier519Flags")
            assert(IoUringDriver.selectRingFlags(6000) == Tier519Flags, s"6000 expected tier-5.19 flags $Tier519Flags")
            assert(IoUringDriver.selectRingFlags(6001) == Tier519Flags, s"6001 expected tier-5.19 flags $Tier519Flags")
            assert(IoUringDriver.selectRingFlags(9999) == Tier519Flags, s"9999 expected tier-5.19 flags $Tier519Flags")
            // Tier < 5.19: no task-run flags
            assert(IoUringDriver.selectRingFlags(5018) == 0, s"5018 expected 0 flags")
            assert(IoUringDriver.selectRingFlags(4019) == 0, s"4019 expected 0 flags")
            assert(IoUringDriver.selectRingFlags(0) == 0, s"0 expected 0 flags")
            // Off-by-one boundary: 5018 yields 0, 5019 yields 5.19 tier
            assert(IoUringDriver.selectRingFlags(5018) == 0, "5018 must get 0 flags (below 5.19 tier)")
            assert(IoUringDriver.selectRingFlags(5019) == Tier519Flags, "5019 must get 5.19 tier (boundary)")
        }

        "kernelVersionProbeIsReal: the uname FFI returns a plausible result on the host" in {
            // The io_uring shim is Linux-only; loading IoUringBindings on a non-Linux host hangs, so cancel off-Linux before the load. The
            // probe is meaningful on ANY Linux kernel (the uname FFI works regardless of whether io_uring itself is available), so this guards
            // on the platform alone rather than on io_uring availability.
            if !PosixConstants.isLinux then cancel("io_uring is Linux-only")
            val uring   = Ffi.load[IoUringBindings]
            val version = uring.kyo_uring_kernel_version()
            // A Linux 5.6+ kernel is the floor for io_uring; any io_uring-capable kernel will be >= 5000 in the packed encoding.
            assert(version > 0, s"kyo_uring_kernel_version returned $version on Linux; expected > 0")
            assert(version >= 5000, s"kyo_uring_kernel_version returned $version; expected >= 5000 for any io_uring-capable kernel")
        }

        "queueInitSucceedsWithProbedFlags: queue_init with the probe-selected flags returns 0 on the real kernel" in {
            PosixTestSockets.assumeUring()
            val uring = Ffi.load[IoUringBindings]
            val depth = math.max(256, kyo.net.ioPoolSize() * 64)
            val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
            val flags = IoUringDriver.selectRingFlags(uring.kyo_uring_kernel_version())
            val rc    = uring.io_uring_queue_init(depth, ring, flags)
            try
                assert(rc == 0, s"io_uring_queue_init with probe-selected flags=$flags returned $rc (expected 0)")
            finally
                if rc == 0 then uring.io_uring_queue_exit(ring)
                ring.close()
            end try
        }
    }
end IoUringSetupFlagsTest
