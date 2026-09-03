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
end SafepointTest
