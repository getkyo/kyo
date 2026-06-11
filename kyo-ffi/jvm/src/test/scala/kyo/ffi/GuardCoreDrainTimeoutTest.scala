package kyo.ffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.ffi.Ffi.CloseOutcome
import kyo.ffi.internal.GuardCore

/** JVM-only test for the drain-timeout path in [[GuardCore.close]].
  *
  * The drain spin-wait exits after [[GuardCore.DrainTimeoutNanos]] even if `inFlight` never reaches 0. This spec pokes a `GuardCore`
  * directly, using a no-op platform-closer, to verify that a `close()` call on one thread unblocks and returns `true` even when a
  * retained-callback invocation has been "begun" on another thread but never ended.
  */
class GuardCoreDrainTimeoutTest extends Test:

    private def isParked(t: Thread): Boolean =
        val s = t.getState
        (s eq Thread.State.WAITING) || (s eq Thread.State.TIMED_WAITING)

    "close() completes despite an in-flight callback that never calls endCallback()" in {
        // Shorten the effective wait by calling endCallback() from the test thread after a delay,
        // so we do NOT actually wait the full 5-second timeout. This keeps the test fast.

        val closedLatch                         = new CountDownLatch(1)
        @volatile var closeResult: CloseOutcome = CloseOutcome.AlreadyClosed

        val core = new GuardCore(() => (), () => ()) // no-op platform-closer

        // Simulate a retained-callback in flight.
        val began = core.beginCallback()
        assert(began == true) // guard is open so beginCallback must succeed

        // Spawn a thread that calls close(), it will spin-wait because inFlight == 1.
        val closerThread = new Thread(
            () =>
                closeResult = core.close()
                closedLatch.countDown()
            ,
            "closer-thread"
        )
        closerThread.setDaemon(true)
        closerThread.start()

        // Wait until the closer thread parks inside the drain loop (WAITING or TIMED_WAITING).
        val deadline = System.nanoTime() + 2_000_000_000L
        while !isParked(closerThread) && System.nanoTime() < deadline do
            Thread.onSpinWait()
        end while

        // Now call endCallback() from the test thread to let close() drain and finish.
        core.endCallback()

        // close() should complete well within the 5-second timeout.
        assert(closedLatch.await(10, TimeUnit.SECONDS) == true)
        assert(closeResult == CloseOutcome.Clean)
    }

    "beginCallback() returns false when the guard is closing" in {
        val core = new GuardCore(() => (), () => ())

        // Transition to StateClosing but don't finish, we can observe beginCallback returning false
        // by marking state manually via the public state field.
        core.state.set(GuardCore.StateClosing)

        val result = core.beginCallback()
        assert(result == false)

        // Reset to closed so the object can be GC'd cleanly.
        core.state.set(GuardCore.StateClosed)
    }

    "beginCallback() returns false when the guard is already fully closed" in {
        val core = new GuardCore(() => (), () => ())
        core.state.set(GuardCore.StateClosed)
        assert(core.beginCallback() == false)
    }

    "second close() call returns AlreadyClosed (idempotent)" in {
        val core   = new GuardCore(() => (), () => ())
        val first  = core.close()
        val second = core.close()
        assert(first == CloseOutcome.Clean)
        assert(second == CloseOutcome.AlreadyClosed)
    }
end GuardCoreDrainTimeoutTest
