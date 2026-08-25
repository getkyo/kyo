package kyo.scheduler

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kyo.scheduler.util.Threads
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class InternalClockTest extends AnyFreeSpec with NonImplicitAssertions {

    "stop" in {
        // The update loop exits on the stop flag, so a terminated executor is the exact signal that no further update
        // can be published and the reported time is frozen. Uses its own executor because the shared pool never terminates.
        val executor = Executors.newSingleThreadExecutor(Threads("test-internal-clock"))
        val clock    = new InternalClock(executor)
        try {
            val ticked = awaitTick(clock, clock.currentMillis())
            clock.stop()
            executor.shutdown()
            assert(executor.awaitTermination(30, TimeUnit.SECONDS), "the update loop should exit after stop()")
            val frozen = clock.currentMillis()
            assert(frozen >= ticked)
            assert(clock.currentMillis() == frozen, "a stopped clock reports the last published timestamp")
        } finally {
            clock.stop()
            executor.shutdownNow()
            ()
        }
    }

    "currentMillis" in {
        // A `source` AtomicLong the test moves pins the reported value exactly, with no reference to real time. `currentMillis()`
        // catching up to each set value proves the loop keeps resampling rather than latching, and gives monotonicity an exact target.
        val source   = new AtomicLong(1000L)
        val executor = Executors.newSingleThreadExecutor(Threads("test-internal-clock"))
        val clock    = new InternalClock(executor, () => source.get())
        try {
            assert(awaitValue(clock, 1000L) == 1000L, "the clock did not publish its source's initial value")
            source.set(2000L)
            val advanced = awaitValue(clock, 2000L)
            assert(advanced == 2000L, "the clock did not resample its source after it moved")
            assert(advanced > 1000L, s"the report did not move forward with the source, $advanced")
        } finally {
            clock.stop()
            executor.shutdownNow()
            ()
        }
    }

    /** Reads the clock until it publishes a value other than `previous`. The deadline is a give-up valve for a dead update
      * thread, not a bound anything is asserted against.
      */
    private def awaitTick(clock: InternalClock, previous: Long): Long = {
        val deadline = System.nanoTime() + 30L * 1000 * 1000 * 1000
        var current  = clock.currentMillis()
        while (current == previous) {
            assert(System.nanoTime() < deadline, s"the clock stopped publishing updates at $previous")
            Thread.`yield`()
            current = clock.currentMillis()
        }
        current
    }

    /** Reads the clock until it publishes exactly `target`. The deadline is a give-up valve for a thread that never reaches
      * the value, not a bound anything is asserted against.
      */
    private def awaitValue(clock: InternalClock, target: Long): Long = {
        val deadline = System.nanoTime() + 30L * 1000 * 1000 * 1000
        var current  = clock.currentMillis()
        while (current != target) {
            assert(System.nanoTime() < deadline, s"the clock never published $target, last saw $current")
            Thread.`yield`()
            current = clock.currentMillis()
        }
        current
    }
}
