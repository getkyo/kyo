package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.scheduler.InternalClock
import kyo.scheduler.Task

/** The JS-only idle gate: a driver with nothing to poll for parks its cycle chain instead of re-arming it.
  *
  * Why this is a JS test and not a shared one: the gate exists because on Node the poll is a `koffi.callAsync` request, which is one of the
  * things the runtime counts when it decides whether the process may exit, so a driver that re-arms forever keeps the process alive forever.
  * JVM and Native poll on a thread the process already owns and deliberately do NOT park, so asserting this there would assert the opposite.
  *
  * The two halves are pinned separately and synchronously, with no clock and no scheduler turn in either assertion: whether a given driver
  * state is idle, and whether a submit arriving at a parked chain takes the task and re-arms it. Pinning the second at `triggerWake` covers
  * every submit, because that is the one hook `submitChange`, `submitEngineOp` and `close` all reach. That a process built this way actually
  * exits is what `kyo-consumer-check`'s `js/net-natives-plugin` measures, since only a real process can show it.
  *
  * [[IoUringDriver]] carries the same gate and has no unit-level guard here: it cannot be constructed without a ring, so a test would cancel
  * on every host this suite runs on but Linux. Its end-to-end guard is the same fixture, which selects io_uring there.
  */
class PollerIoDriverIdleTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    private def newDriver(using Frame): PollerIoDriver =
        val backend = PollerBackend.default()
        PollerIoDriver.init(backend, backend.create(), Ffi.load[SocketBindings])

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
            driver.idleTask = parked
            driver.submitEngineOp(() => ())
            assert(driver.idleTask eq null)
        finally driver.close()
        end try
    }

end PollerIoDriverIdleTest
