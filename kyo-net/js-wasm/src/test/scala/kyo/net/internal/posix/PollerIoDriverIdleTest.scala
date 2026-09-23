package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.scheduler.InternalClock
import kyo.scheduler.Task

/** The JS-only idle gate: a driver with nothing to poll for parks its cycle chain instead of re-arming it.
  *
  * JS only because the gate is. On Node the poll is a `koffi.callAsync` request, which Node counts when it decides whether the process may
  * exit, so a driver that re-arms forever keeps the process alive. JVM and Native poll on a thread the process already owns and do not park,
  * so asserting this there would assert the opposite.
  *
  * Both halves are asserted synchronously, with no clock and no scheduler turn: whether a driver state is idle, and whether a submit arriving
  * at a parked chain takes the task. The second is pinned at `triggerWake` because every path that gives the driver work reaches it.
  */
class PollerIoDriverIdleTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    private def newDriver(using Frame): PollerIoDriver =
        if !PosixConstants.isLinux && !PosixConstants.isMacOrBsd then cancel("no posix poller on this OS; the Node floor serves it")
        val backend = PollerBackend.default()
        PollerIoDriver.init(backend, backend.create(), Ffi.load[SocketBindings])
    end newDriver

    "a driver with nothing registered has nothing to poll for" in {
        val driver = newDriver
        try assert(driver.idleNow)
        finally driver.close()
    }

    "a queued engine op is work, so the driver is not idle while one is waiting" in {
        val driver = newDriver
        try
            // Submitted from this carrier rather than drained, so it is still in the FIFO when the assertion reads it. A cycle that parked
            // here would strand the op: nothing else would run it.
            driver.submitEngineOp(() => ())
            assert(!driver.idleNow)
        finally driver.close()
        end try
    }

    "a submit arriving at a parked chain takes the task, so the work it carries cannot strand" in {
        val driver = newDriver
        try
            // Stands in for a cycle that parked: the gate stores the cycle's own task, and what matters is that the next submit reclaims
            // whatever is there rather than leaving it.
            val parked = new Task:
                def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result = Task.Done
            driver.idleTask = Present(parked)
            driver.submitEngineOp(() => ())
            assert(driver.idleTask.isEmpty)
        finally driver.close()
        end try
    }

end PollerIoDriverIdleTest
