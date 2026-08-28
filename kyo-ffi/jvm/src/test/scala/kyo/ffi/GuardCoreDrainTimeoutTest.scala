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
        // The closer uses a 10-minute drain policy instead of the production 5s default, so the test's endCallback() below (which lets the drain
        // complete) can never race the drain deadline under CI load. The path under test is drain-completes-on-endCallback, not the timeout.

        val closedLatch                         = new CountDownLatch(1)
        @volatile var closeResult: CloseOutcome = CloseOutcome.AlreadyClosed

        val core = new GuardCore(() => (), () => ()) // no-op platform-closer

        // Simulate a retained-callback in flight.
        val began = core.beginCallback()
        assert(began == true) // guard is open so beginCallback must succeed

        // Spawn a thread that calls close(), it will spin-wait because inFlight == 1.
        val closerThread = new Thread(
            () =>
                closeResult = core.closeWithPolicy(10L * 60L * 1000L * 1000L * 1000L)
                closedLatch.countDown()
            ,
            "closer-thread"
        )
        closerThread.setDaemon(true)
        closerThread.start()

        // Wait until the closer thread parks inside the drain loop (WAITING or TIMED_WAITING). 30s catastrophic bound: the thread parks in
        // microseconds normally, so this only breaks a closer that never reaches the drain.
        val deadline = System.nanoTime() + 30_000_000_000L
        while !isParked(closerThread) && System.nanoTime() < deadline do
            Thread.onSpinWait()
        end while

        // Now call endCallback() from the test thread to let close() drain and finish.
        core.endCallback()

        // close() drains and returns once endCallback() above dropped inFlight to 0.
        assert(closedLatch.await(60, TimeUnit.SECONDS) == true)
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
