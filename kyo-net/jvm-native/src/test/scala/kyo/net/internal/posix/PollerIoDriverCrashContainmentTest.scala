package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Regression guard for exception containment in [[PollerIoDriver]]'s poll cycle.
  *
  * The poll runs as a scheduler task, one cycle per activation, and each cycle re-arms the next one before returning. A `Throwable` escaping a
  * cycle body would therefore end the chain outright: `Worker.runTask` hands an escaping exception to the thread's uncaught handler and returns
  * `Done`, so nothing re-arms. The driver would then be silently dead with no teardown run, its done-fiber never completed, every pending read
  * and write hanging forever, and a later `close()` hanging too, because `close()`'s rescue path is gated on `teardownComplete`, which only the
  * terminal exit sets.
  *
  * The cycle therefore catches `Throwable` (not just `NonFatal`: a silently dead driver is worse than a rethrow) and routes it to the same single
  * terminal exit the closed path uses, completing the done-fiber as a panic. This test pins that contract.
  *
  * The throw is injected with [[RecordingPollerBackend.throwOnPoll]], armed BEFORE `start()` so the very first cycle throws inside
  * `backend.poll`. That is deterministic with no sleep and no real syscall to race: the failure lands in the cycle body, which is exactly where
  * containment has to hold.
  */
class PollerIoDriverCrashContainmentTest extends Test:

    import AllowUnsafe.embrace.danger

    "PollerIoDriver poll-cycle crash containment" - {
        "a throw inside a poll cycle completes the done-fiber as a panic instead of killing the chain silently" in {
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            // Arm before start() so the first cycle throws inside backend.poll, with no real wait to race.
            backend.throwOnPoll.set(true)
            val driver = TestDrivers.forBackend(backend, pollerFd, spy)
            val done   = driver.start()

            // Containment must complete the done-fiber. Without it this get would hang: the chain would be gone with the promise never completed.
            done.safe.getResult.map { result =>
                // close() after a crashed cycle must return rather than hang. The terminal exit already ran teardownComplete, so close() takes its
                // post-teardown rescue path instead of waiting on a consumer that no longer exists.
                driver.close()
                assert(
                    result.isPanic,
                    s"a Throwable escaping a poll cycle must complete the driver's done-fiber as a panic, got: $result"
                )
                succeed
            }
        }
    }

end PollerIoDriverCrashContainmentTest
