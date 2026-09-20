package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.scheduler.InternalClock
import kyo.scheduler.Task

/** The JS-only idle gate on the ring driver, which is [[PollerIoDriverIdleTest]]'s subject for the other half of the posix transport.
  *
  * Both drivers need it and only one of them could be missed: the JS backend registry ranks io_uring above epoll, so a Linux Node
  * application runs on this driver, and a macOS host has no ring at all. Gating this leaf on a real ring rather than sharing the poller's is
  * what keeps that asymmetry visible: on a host without one it cancels by name instead of silently covering nothing.
  *
  * The assertions are the poller's, synchronous and clock-free: what counts as nothing outstanding, and that a submit arriving at a parked
  * chain reclaims the task. Whether a process built this way exits is `kyo-consumer-check`'s `js/net-natives-plugin`, which selects this
  * driver on Linux.
  */
class IoUringDriverIdleTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    /** Cancel unless this host has a usable ring at the depth production builds one at, mirroring the JVM and Native suites' own gate. A
      * ring that initializes at 256 but not at the production depth would let a test pass on a driver an application cannot build.
      */
    private def assumeUring()(using Frame): Unit =
        if !PosixConstants.isLinux then throw new kyo.test.TestCancelled("io_uring is Linux-only")
        val usable =
            try
                val uring = Ffi.load[IoUringBindings]
                val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
                val rc    = uring.io_uring_queue_init(math.max(256, kyo.net.ioPoolSize() * 64), ring, 0)
                // liburing returns -errno directly rather than setting the global one, so the return value is the only honest reading.
                if rc != 0 then
                    ring.close()
                    false
                else
                    uring.io_uring_queue_exit(ring)
                    ring.close()
                    true
                end if
            catch case _: Throwable => false
        if !usable then throw new kyo.test.TestCancelled("no usable io_uring on this kernel or runtime")
    end assumeUring

    "a driver with nothing submitted has nothing to reap" in {
        assumeUring()
        val driver = IoUringDriver.init()
        try assert(driver.idleNow)
        finally driver.close()
    }

    "a queued engine op is work, so the driver is not idle while one is waiting" in {
        assumeUring()
        val driver = IoUringDriver.init()
        try
            // Submitted from this carrier rather than drained, so it is still in the FIFO when the assertion reads it. A turn that parked
            // here would strand it: the reap carrier is the only thing that runs an engine op.
            driver.submitEngineOp(() => ())
            assert(!driver.idleNow)
        finally driver.close()
        end try
    }

    "a submit arriving at a parked chain takes the task, so the work it carries cannot strand" in {
        assumeUring()
        val driver = IoUringDriver.init()
        try
            val parked = new Task:
                def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result = Task.Done
            driver.idleTask = Present(parked)
            driver.submitEngineOp(() => ())
            assert(driver.idleTask.isEmpty)
        finally driver.close()
        end try
    }

end IoUringDriverIdleTest
