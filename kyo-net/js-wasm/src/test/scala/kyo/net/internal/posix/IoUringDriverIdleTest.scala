package kyo.net.internal.posix

import kyo.*
import kyo.scheduler.InternalClock
import kyo.scheduler.Task

/** The JS-only idle gate on the ring driver, asserted synchronously and without a clock: what counts as nothing outstanding, and that a
  * submit arriving at a parked chain takes the task.
  *
  * The JS registry ranks io_uring above epoll, so a Linux Node application runs on this driver. Each leaf cancels where no ring initializes
  * at the production depth, which includes every macOS host, rather than passing without exercising anything.
  */
class IoUringDriverIdleTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    "a driver with nothing submitted has nothing to reap" in {
        UringGate.assumeUring()
        val driver = IoUringDriver.init()
        try assert(driver.idleNow)
        finally driver.close()
    }

    "a queued engine op is work, so the driver is not idle while one is waiting" in {
        UringGate.assumeUring()
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
        UringGate.assumeUring()
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
