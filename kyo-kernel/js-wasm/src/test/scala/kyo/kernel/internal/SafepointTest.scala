package kyo.kernel.internal

import kyo.Flag
import org.scalatest.freespec.AnyFreeSpec

class SafepointTest extends AnyFreeSpec:

    "a direct first touch of the period flag initializes it exactly once" in {
        val value = Safepoint.period()
        assert(value == maxStackDepth)
        val registered = Flag.get("kyo.kernel.internal.Safepoint.period")
        assert(registered.nonEmpty)
        assert(registered.get eq Safepoint.period)
    }

    "a slice deadline is judged on monotonic time, unmoved by a wall-clock step" in {
        val date     = scala.scalajs.js.Dynamic.global.Date
        val original = date.now
        Safepoint.deadline(java.lang.System.nanoTime() / 1000000L + 60000L)
        date.updateDynamic("now")((() => 4.0e15): scala.scalajs.js.Function0[Double])
        val expired =
            try Safepoint.stopped(0)
            finally
                date.updateDynamic("now")(original)
                Safepoint.deadline(Long.MaxValue)
        assert(!expired, "a wall-clock step forward expired a slice with a minute left")
    }

    "a slice that starts just before its deadline can still be stopped once the deadline passes" in {
        val performance = scala.scalajs.js.Dynamic.global.performance
        val original    = performance.now
        val deadline    = java.lang.System.nanoTime() / 1000000L + 60000L
        var reads       = 0
        // The first reading falls just short of the deadline and every later one is past it, so a slice entry that reads the clock
        // twice sees the deadline arrive between its two reads.
        performance.updateDynamic("now")({ () =>
            reads += 1
            if reads == 1 then (deadline - 1).toDouble else (deadline + 1).toDouble
        }: scala.scalajs.js.Function0[Double])
        val (stoppedAtEntry, stoppedAfter) =
            try
                Safepoint.deadline(deadline)
                val atEntry = Safepoint.consumeStopped(0)
                (atEntry, Safepoint.stopped(0))
            finally
                performance.updateDynamic("now")(original)
                Safepoint.deadline(Long.MaxValue)
        assert(!stoppedAtEntry, "the slice was stopped at entry, before its deadline")
        assert(stoppedAfter, "the deadline passed, but the slice can no longer be stopped")
    }
end SafepointTest
