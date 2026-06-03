package kyo.internal

import kyo.Chunk
import kyo.Test

class ScaleTest extends Test:

    "niceTicks(0, 61200, 5) returns the demo snapped ticks" in {
        // The BarChart demo calls niceTicks(0, 61200, 5).
        // rawStep = 61200 / 4 = 15300; magnitude = 10000; residual = 1.53 -> niceUnit = 2
        // step = 20000; ticks: 0, 20000, 40000, 60000 (4 ticks, 60000 <= 61200 + small epsilon)
        val result = Scale.niceTicks(0.0, 61200.0, 5)
        assert(result == Chunk(0.0, 20000.0, 40000.0, 60000.0))
    }

    "niceTicks(5, 5) returns Chunk(5.0) for degenerate input" in {
        val result = Scale.niceTicks(5.0, 5.0)
        assert(result == Chunk(5.0))
    }

    "Scale.fit Linear apply maps Continuous(50) to 100.0" in {
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 100.0), 0.0, 200.0)
        assert(scale.apply(Domain.Continuous(50.0)) == 100.0)
    }

    "Scale.fit Linear invert round-trips" in {
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 100.0), 0.0, 200.0)
        val px    = scale.apply(Domain.Continuous(50.0))
        val back  = scale.invert(px)
        assert(back == Domain.Continuous(50.0))
    }

    "Scale.fit Linear clamps out-of-range values" in {
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 100.0), 0.0, 200.0)
        // value above max should clamp to rangeHi
        assert(scale.apply(Domain.Continuous(200.0)) == 200.0)
        // value below min should clamp to rangeLo
        assert(scale.apply(Domain.Continuous(-50.0)) == 0.0)
    }

    "Scale.fit Band returns the left edge of band 2 and the center is reconstructable" in {
        // 3 categories, totalWidth = 300, padding = 0.1 (default)
        // slot = 300/3 = 100; bandW = 300 * 0.9 / 3 = 90
        // band 0 xOffset = 0 * 100 + (100 - 90)/2 = 5  -> left edge pixel 5
        // band 1 xOffset = 1 * 100 + (100 - 90)/2 = 105 -> left edge pixel 105
        // band 2 xOffset = 2 * 100 + (100 - 90)/2 = 205 -> left edge pixel 205
        // The center of band "b" (index 1) = left edge + bandwidth/2 = 105 + 45 = 150.
        val scale = Scale.fit(
            Scale.Kind.Band,
            Extent.categories(Chunk("a", "b", "c")),
            0.0,
            300.0
        )
        // apply returns the left edge of the band, not the center.
        val b = Domain.Category("b")
        assert(scale.apply(b) == 105.0)
        assert(scale.bandwidth == 90.0)
        // The center is always reconstructable as: apply(b) + bandwidth/2.
        assert(scale.apply(b) + scale.bandwidth / 2.0 == 150.0)
    }

    "Scale.fit Ordinal returns -1.0 for an unknown category (sentinel, does not collide with index 0)" in {
        val scale = Scale.fit(
            Scale.Kind.Ordinal,
            Extent.categories(Chunk("red", "green", "blue")),
            0.0,
            300.0
        )
        // Known category returns its index.
        assert(scale.apply(Domain.Category("red")) == 0.0)
        assert(scale.apply(Domain.Category("green")) == 1.0)
        // Unknown category must return -1.0 (not 0.0, which would collide with "red").
        assert(scale.apply(Domain.Category("purple")) == -1.0)
    }

end ScaleTest
