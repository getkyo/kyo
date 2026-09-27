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
end SafepointTest
