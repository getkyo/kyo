package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.Test

/** Regression guard for exception containment in [[IoUringDriver]]'s reap cycle.
  *
  * The reap loop runs as a scheduler task, one cycle per activation, each re-arming the next before returning. A `Throwable` escaping a cycle
  * would therefore end the chain outright: the worker hands an escaping exception to its uncaught handler and returns Done, so nothing re-arms.
  * The driver would be silently dead with the ring, cqePtr and wake eventfd still held, every pending operation parked forever, and its
  * done-fiber never completed. `IoDriverPool.awaitTornDown` then waits on a promise nothing will complete.
  *
  * The cycle catches `Throwable` rather than only `NonFatal` (a silently dead driver is worse than a rethrow) and routes it to the same terminal
  * exit the closed path uses, which runs the teardown. This pins BOTH halves: the done-fiber completes as a panic AND the ring is released. The
  * teardown assertion is the one that matters, since before the loop ran on the scheduler a crash completed the promise with the ring still held.
  *
  * Runs over the ringless [[StubIoUringBindings]], so it exercises the real cycle and terminal on every platform rather than only where a
  * kernel io_uring is available.
  */
class IoUringDriverCrashContainmentTest extends Test:

    import AllowUnsafe.embrace.danger

    "IoUringDriver reap-cycle crash containment" - {
        "a throw inside a reap cycle completes the done-fiber as a panic and still tears the ring down" in {
            val stub = new StubIoUringBindings
            val ring = Buffer.alloc[Byte](stub.kyo_uring_sizeof().toInt)
            val drv  = TestDrivers.forBindings(stub, ring, StubSocketBindings)

            // Arm before start so the failure lands in the first cycle's wait, inside the body where containment must hold.
            stub.throwOnWait.set(true)
            val done = drv.start()

            // Without containment this get would hang: the chain would be gone with the promise never completed.
            done.safe.getResult.map { result =>
                assert(
                    result.isPanic,
                    s"a Throwable escaping a reap cycle must complete the done-fiber as a panic, got: $result"
                )
                // The teardown proof. A crash previously completed the promise with the ring still held, which is the leak
                // IoDriverPool.awaitTornDown documented; routing the crash through the terminal exit is what closes it.
                assert(
                    stub.queueExitCount.get() == 1,
                    s"the terminal exit must release the ring exactly once after a crashed cycle, " +
                        s"got ${stub.queueExitCount.get()} io_uring_queue_exit calls"
                )
                assert(
                    stub.eventfdCloseCount.get() == 1,
                    s"the terminal exit must close the wake eventfd after a crashed cycle, " +
                        s"got ${stub.eventfdCloseCount.get()} closes"
                )
                succeed
            }
        }
    }

end IoUringDriverCrashContainmentTest
