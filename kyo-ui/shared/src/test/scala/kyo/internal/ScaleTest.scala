package kyo.internal

import kyo.Absent
import kyo.Chunk
import kyo.Maybe
import kyo.Present
import kyo.Test
import kyo.UI
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.mark.*
import scala.language.implicitConversions

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

    "Scale.fit Linear nice expands a non-aligned domain to step-aligned bounds [230,390] -> [200,400]" in {
        // niceTicks(230,390,5) picks step 50; the nice domain must snap DOWN at lo and UP at hi
        // to the nearest step multiple so the endpoints ARE ticks: floor(230/50)*50=200, ceil(390/50)*50=400.
        // The top rendered tick then equals domainMax (400), giving the data max (390) headroom and
        // leaving no bare spine stub above the highest tick.
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(230.0, 390.0), 0.0, 200.0, nice = true)
        scale match
            case Scale.Linear(domainMin, domainMax, _, _, _, _) =>
                assert(domainMin == 200.0, s"Expected domainMin=200.0 but got $domainMin")
                assert(domainMax == 400.0, s"Expected domainMax=400.0 but got $domainMax")
            case other => fail(s"Expected Scale.Linear but got $other")
        end match
        assert(scale.ticks(5).last.value == 400.0, s"Expected top tick 400.0 but got ${scale.ticks(5).last.value}")
    }

    "Scale.fit Linear nice expands a signed non-aligned domain [-7.7,27.1] -> [-10,30]" in {
        // niceTicks(-7.7,27.1,5) picks step 10; floor(-7.7/10)*10=-10, ceil(27.1/10)*10=30.
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(-7.7, 27.1), 0.0, 200.0, nice = true)
        scale match
            case Scale.Linear(domainMin, domainMax, _, _, _, _) =>
                assert(domainMin == -10.0, s"Expected domainMin=-10.0 but got $domainMin")
                assert(domainMax == 30.0, s"Expected domainMax=30.0 but got $domainMax")
            case other => fail(s"Expected Scale.Linear but got $other")
        end match
        assert(scale.ticks(5).last.value == 30.0, s"Expected top tick 30.0 but got ${scale.ticks(5).last.value}")
    }

    "Scale.fit Linear nice leaves an already step-aligned domain [0,4000] unchanged" in {
        // floor/ceil are no-ops when the endpoints already land on step multiples (step 1000),
        // so an aligned domain must NOT expand.
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 4000.0), 0.0, 200.0, nice = true)
        scale match
            case Scale.Linear(domainMin, domainMax, _, _, _, _) =>
                assert(domainMin == 0.0, s"Expected domainMin=0.0 but got $domainMin")
                assert(domainMax == 4000.0, s"Expected domainMax=4000.0 but got $domainMax")
            case other => fail(s"Expected Scale.Linear but got $other")
        end match
        assert(scale.ticks(5).last.value == 4000.0, s"Expected top tick 4000.0 but got ${scale.ticks(5).last.value}")
    }

    "Scale.fit Linear nice [10,210] snaps to [0,250] and the top tick equals domainMax" in {
        // Regression: niceTicks(10,210,5) picks step 50 -> snaps to [0,250]. But niceTicks(0,250,5)
        // independently recomputes step 100 (ticks 0/100/200, top 200 != 250). The old fitLinear
        // asserted that the recomputed top tick == snappedHi, which THREW AssertionError here,
        // crashing the render path. The fix shares the snap step with ticks so the top tick is
        // always domainMax (250), with NO overshoot and NO crash.
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(10.0, 210.0), 0.0, 200.0, nice = true)
        scale match
            case Scale.Linear(domainMin, domainMax, _, _, _, _) =>
                assert(domainMin == 0.0, s"Expected domainMin=0.0 but got $domainMin")
                assert(domainMax == 250.0, s"Expected domainMax=250.0 but got $domainMax")
            case other => fail(s"Expected Scale.Linear but got $other")
        end match
        val tickValues = scale.ticks(5).map(_.value)
        assert(tickValues == Chunk(0.0, 50.0, 100.0, 150.0, 200.0, 250.0), s"Expected 0..250 by 50 but got $tickValues")
        assert(tickValues.last == 250.0, s"Expected top tick 250.0 (== domainMax) but got ${tickValues.last}")
    }

    "Scale.fit Linear invert round-trips" in {
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 100.0), 0.0, 200.0)
        val px    = scale.apply(Domain.Continuous(50.0))
        val back  = scale.invert(px)
        assert(back == Domain.Continuous(50.0))
    }

    "Scale.fit Linear clamps out-of-range values when clamp=true" in {
        // Phase 6 (WARN-1): clamping is now opt-in via the clamp flag (d3 semantics).
        // The default (clamp=false) extrapolates; clamp=true pins out-of-range to the bounds.
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 100.0), 0.0, 200.0, nice = false, clamp = true)
        // value above max should clamp to rangeHi
        assert(scale.apply(Domain.Continuous(200.0)) == 200.0)
        // value below min should clamp to rangeLo
        assert(scale.apply(Domain.Continuous(-50.0)) == 0.0)
    }

    "Scale.fit Linear extrapolates out-of-range values when clamp=false (default)" in {
        // Phase 6 (WARN-1): default clamp=false extrapolates beyond the range.
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 100.0), 0.0, 200.0, nice = false)
        // value above max extrapolates past rangeHi (200): 200.0 -> 400.0
        assert(scale.apply(Domain.Continuous(200.0)) == 400.0)
        // value below min extrapolates below rangeLo (0): -50.0 -> -100.0
        assert(scale.apply(Domain.Continuous(-50.0)) == -100.0)
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

    // ---- Phase 2: Symlog (INV-008) ----

    // Test 1: symlog is finite and zero-centered at 0 (G9).
    // f(-100) = -log10(101) ~ -2.00432, f(100) = log10(101) ~ 2.00432.
    // apply(0) = 0 + (f(0)-fMin)/(fMax-fMin)*200 = (2.00432)/(4.00864)*200 = 100.0 exactly.
    "INV-008: Symlog apply(Continuous(0.0)) is the range midpoint for symmetric domain" in {
        val s   = Scale.Symlog(-100.0, 100.0, 0.0, 200.0, clamp = false)
        val mid = s.apply(Domain.Continuous(0.0))
        assert(math.abs(mid - 100.0) < 1e-9, s"Expected 100.0 but got $mid")
    }

    // Test 2: symlog is symmetric about the zero pixel.
    "INV-008: Symlog apply(v) and apply(-v) are mirror-equidistant from the midpoint" in {
        val s   = Scale.Symlog(-100.0, 100.0, 0.0, 200.0, clamp = false)
        val mid = 100.0
        val v   = 37.0
        val pos = s.apply(Domain.Continuous(v))
        val neg = s.apply(Domain.Continuous(-v))
        assert(math.abs((pos - mid) - (mid - neg)) < 1e-9, s"pos=$pos neg=$neg not symmetric about $mid")
    }

    // Test 3: symlog invert round-trips.
    "INV-008: Symlog invert(apply(v)) recovers v within 1e-6" in {
        val s  = Scale.Symlog(-100.0, 100.0, 0.0, 200.0, clamp = false)
        val px = s.apply(Domain.Continuous(12.5))
        val back = s.invert(px) match
            case Domain.Continuous(v) => v
            case _                    => Double.NaN
        assert(math.abs(back - 12.5) < 1e-6, s"Expected 12.5 but got $back")
    }

    // Test 4: symlog is monotone across a signed domain.
    "INV-008: Symlog pixel positions are strictly increasing across -50,-1,0,1,50" in {
        val s      = Scale.Symlog(-100.0, 100.0, 0.0, 200.0, clamp = false)
        val values = Chunk(-50.0, -1.0, 0.0, 1.0, 50.0)
        val pixels = values.map(v => s.apply(Domain.Continuous(v)))
        // Each pixel must be strictly greater than the previous one.
        val ok = (1 until pixels.size).forall(i => pixels(i) > pixels(i - 1))
        assert(ok, s"Expected strictly increasing pixels but got: $pixels")
    }

    // ---- Phase 2: Band/Ordinal stride ticks (INV-009) ----

    // Test 5: band axis emits all 7 labels when maxTicks >= n.
    "INV-009: Band ticks(7) over 7 keys produces 7 ticks including the last key" in {
        val keys  = Chunk("a", "b", "c", "d", "e", "f", "g")
        val scale = Scale.fit(Scale.Kind.Band, Extent.categories(keys), 0.0, 700.0)
        val ticks = scale.ticks(7)
        assert(ticks.size == 7, s"Expected 7 ticks but got ${ticks.size}")
        assert(ticks(6).label == "g", s"Expected last label 'g' but got '${ticks(6).label}'")
    }

    // Test 6: band axis uses stride so indices 0,3,6 (not first 3) are selected for 7 keys / maxTicks=3.
    "INV-009: Band ticks(3) over 7 keys selects stride-3 indices 0,3,6 (first, fourth, seventh)" in {
        val keys  = Chunk("a", "b", "c", "d", "e", "f", "g")
        val scale = Scale.fit(Scale.Kind.Band, Extent.categories(keys), 0.0, 700.0)
        val ticks = scale.ticks(3)
        assert(ticks.size == 3, s"Expected 3 ticks but got ${ticks.size}")
        assert(ticks(0).label == "a", s"Expected first label 'a' but got '${ticks(0).label}'")
        assert(ticks(1).label == "d", s"Expected second label 'd' (index 3) but got '${ticks(1).label}'")
        assert(ticks(2).label == "g", s"Expected third label 'g' (index 6) but got '${ticks(2).label}'")
    }

    // ---- Phase 2: Log domain drops non-positive (INV-011) ----

    // Test 9: fitLog with a manually constructed extent starting at 10.0 does not floor to 1e-10.
    "INV-011: fitLog over extent [10,1000] sets domainMin == 10.0, not the old 1e-10 floor" in {
        val scale = Scale.fit(Scale.Kind.Log, Extent.continuous(10.0, 1000.0), 0.0, 300.0)
        scale match
            case Scale.Log(domainMin, _, _, _, _) =>
                assert(domainMin == 10.0, s"Expected domainMin=10.0 but got $domainMin")
            case other => fail(s"Expected Scale.Log but got $other")
        end match
    }

    // ---- Phase 2: Clamp (INV-012) ----

    // Test 10: Scale.Linear with clamp=true pins a value beyond domainMax to rangeHi.
    "INV-012: Scale.Linear apply clamps a value beyond domainMax to rangeHi when clamp=true" in {
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 10.0), 0.0, 100.0, nice = false, clamp = true)
        assert(scale.apply(Domain.Continuous(20.0)) == 100.0, "Expected 100.0 (clamped to rangeHi)")
    }

    // Test 10b: with clamp=false (default), the same value extrapolates beyond rangeHi.
    "INV-012: Scale.Linear apply extrapolates a value beyond domainMax when clamp=false" in {
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 10.0), 0.0, 100.0, nice = false)
        assert(scale.apply(Domain.Continuous(20.0)) == 200.0, "Expected 200.0 (extrapolated past rangeHi)")
    }

    // Symlog clamp=true: input 20.0 clamped to domainMax=10 before transform.
    "INV-012: Symlog with clamp=true pins out-of-domain input to the domain boundary" in {
        val s = Scale.Symlog(0.0, 10.0, 0.0, 100.0, clamp = true)
        // apply(20.0) with clamp=true should equal apply(10.0): both domainMax.
        val clamped = s.apply(Domain.Continuous(20.0))
        val atMax   = s.apply(Domain.Continuous(10.0))
        assert(math.abs(clamped - atMax) < 1e-9, s"Clamped=$clamped should equal atMax=$atMax")
    }

    // ---- Phase 11: L11a and L12b -- right-scale kind readback via ChartScales ----

    case class ScRow(x: String, yL: Double, yR: Double)
    given CanEqual[ScRow, ScRow] = CanEqual.derived

    "L11a: yScaleRight(_.log) resolves right scale as Log kind (kind readback via ChartScales, GAP-RIGHTY-SCALE)" in {
        // Use toSvgWithScales (via lowerWithScales) to read the resolved right scale kind.
        // The right scale should be Log after .yScaleRight(_.log).
        // Data: yR=[1.0, 100.0]; with log scale, domain is [1.0, 100.0].
        val rows = kyo.Chunk(ScRow("a", 100.0, 1.0), ScRow("b", 200.0, 100.0))
        val spec = UI.chart(rows)(
            bar(x = _.x, y = _.yL),
            line(x = _.x, y = _.yR, axis = Axis.Right)
        ).yScaleRight(_.log)
        val (_, scales) = spec.toSvgWithScales
        // The right axis should be Log kind.
        scales.yRight match
            case Present(ax) =>
                ax.kind match
                    case ScaleKind.Log => succeed
                    case other         => fail(s"L11a: Expected Log kind for right axis but got $other")
            case Absent =>
                fail("L11a: Expected a right axis (yRight is Present) but got Absent")
        end match
    }

    "L12b: no yScaleRight override -> right scale resolves as Linear (default byte-identity, CO-PIN)" in {
        // Without yScaleRight, the right scale defaults to Linear+nice, matching the old hardcoded call.
        val rows = kyo.Chunk(ScRow("a", 100.0, 0.0), ScRow("b", 200.0, 20.0))
        val spec = UI.chart(rows)(
            bar(x = _.x, y = _.yL),
            line(x = _.x, y = _.yR, axis = Axis.Right)
        )
        val (_, scales) = spec.toSvgWithScales
        scales.yRight match
            case Present(ax) =>
                ax.kind match
                    case ScaleKind.Linear(_, _) => succeed
                    case other                  => fail(s"L12b: Expected Linear kind for default right axis but got $other")
            case Absent =>
                fail("L12b: Expected a right axis (yRight is Present) but got Absent")
        end match
    }

end ScaleTest
