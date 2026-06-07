package kyo.internal

import kyo.Absent
import kyo.Chart
import kyo.Chart.*
import kyo.Chunk
import kyo.Maybe
import kyo.Present
import kyo.Svg
import kyo.UI
import kyo.UI.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

class ScaleTest extends kyo.test.Test[Any]:

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
        // niceTicks(10,210,5) picks step 50 -> snaps to [0,250]. But niceTicks(0,250,5)
        // independently recomputes step 100 (ticks 0/100/200, top 200 != 250). Asserting the recomputed
        // top tick == snappedHi would throw AssertionError here and crash the render path. fitLinear
        // instead shares the snap step with ticks so the top tick is always domainMax (250), with NO
        // overshoot and NO crash.
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
        // Clamping is opt-in via the clamp flag (d3 semantics).
        // The default (clamp=false) extrapolates; clamp=true pins out-of-range to the bounds.
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 100.0), 0.0, 200.0, nice = false, clamp = true)
        // value above max should clamp to rangeHi
        assert(scale.apply(Domain.Continuous(200.0)) == 200.0)
        // value below min should clamp to rangeLo
        assert(scale.apply(Domain.Continuous(-50.0)) == 0.0)
    }

    "Scale.fit Linear extrapolates out-of-range values when clamp=false (default)" in {
        // default clamp=false extrapolates beyond the range.
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

    // ---- Symlog ----

    // symlog is finite and zero-centered at 0.
    // f(-100) = -log10(101) ~ -2.00432, f(100) = log10(101) ~ 2.00432.
    // apply(0) = 0 + (f(0)-fMin)/(fMax-fMin)*200 = (2.00432)/(4.00864)*200 = 100.0 exactly.
    "Symlog apply(Continuous(0.0)) is the range midpoint for symmetric domain" in {
        val s   = Scale.Symlog(-100.0, 100.0, 0.0, 200.0, clamp = false)
        val mid = s.apply(Domain.Continuous(0.0))
        assert(math.abs(mid - 100.0) < 1e-9, s"Expected 100.0 but got $mid")
    }

    // symlog is symmetric about the zero pixel.
    "Symlog apply(v) and apply(-v) are mirror-equidistant from the midpoint" in {
        val s   = Scale.Symlog(-100.0, 100.0, 0.0, 200.0, clamp = false)
        val mid = 100.0
        val v   = 37.0
        val pos = s.apply(Domain.Continuous(v))
        val neg = s.apply(Domain.Continuous(-v))
        assert(math.abs((pos - mid) - (mid - neg)) < 1e-9, s"pos=$pos neg=$neg not symmetric about $mid")
    }

    // symlog invert round-trips.
    "Symlog invert(apply(v)) recovers v within 1e-6" in {
        val s  = Scale.Symlog(-100.0, 100.0, 0.0, 200.0, clamp = false)
        val px = s.apply(Domain.Continuous(12.5))
        val back = s.invert(px) match
            case Domain.Continuous(v) => v
            case _                    => Double.NaN
        assert(math.abs(back - 12.5) < 1e-6, s"Expected 12.5 but got $back")
    }

    // symlog is monotone across a signed domain.
    "Symlog pixel positions are strictly increasing across -50,-1,0,1,50" in {
        val s      = Scale.Symlog(-100.0, 100.0, 0.0, 200.0, clamp = false)
        val values = Chunk(-50.0, -1.0, 0.0, 1.0, 50.0)
        val pixels = values.map(v => s.apply(Domain.Continuous(v)))
        // Each pixel must be strictly greater than the previous one.
        val ok = (1 until pixels.size).forall(i => pixels(i) > pixels(i - 1))
        assert(ok, s"Expected strictly increasing pixels but got: $pixels")
    }

    // ---- Band stride ticks ----

    // band axis emits all 7 labels when maxTicks >= n.
    "Band ticks(7) over 7 keys produces 7 ticks including the last key" in {
        val keys  = Chunk("a", "b", "c", "d", "e", "f", "g")
        val scale = Scale.fit(Scale.Kind.Band, Extent.categories(keys), 0.0, 700.0)
        val ticks = scale.ticks(7)
        assert(ticks.size == 7, s"Expected 7 ticks but got ${ticks.size}")
        assert(ticks(6).label == "g", s"Expected last label 'g' but got '${ticks(6).label}'")
    }

    // band axis uses stride so indices 0,3,6 (not first 3) are selected for 7 keys / maxTicks=3.
    "Band ticks(3) over 7 keys selects stride-3 indices 0,3,6 (first, fourth, seventh)" in {
        val keys  = Chunk("a", "b", "c", "d", "e", "f", "g")
        val scale = Scale.fit(Scale.Kind.Band, Extent.categories(keys), 0.0, 700.0)
        val ticks = scale.ticks(3)
        assert(ticks.size == 3, s"Expected 3 ticks but got ${ticks.size}")
        assert(ticks(0).label == "a", s"Expected first label 'a' but got '${ticks(0).label}'")
        assert(ticks(1).label == "d", s"Expected second label 'd' (index 3) but got '${ticks(1).label}'")
        assert(ticks(2).label == "g", s"Expected third label 'g' (index 6) but got '${ticks(2).label}'")
    }

    // ---- Log domain drops non-positive ----

    // fitLog with a manually constructed extent starting at 10.0 does not floor to 1e-10.
    "fitLog uses the actual extent minimum as domainMin" in {
        val scale = Scale.fit(Scale.Kind.Log, Extent.continuous(10.0, 1000.0), 0.0, 300.0)
        scale match
            case Scale.Log(domainMin, _, _, _, _) =>
                assert(domainMin == 10.0, s"Expected domainMin=10.0 but got $domainMin")
            case other => fail(s"Expected Scale.Log but got $other")
        end match
    }

    // ---- Clamp ----

    // Scale.Linear with clamp=true pins a value beyond domainMax to rangeHi.
    "Scale.Linear apply clamps a value beyond domainMax to rangeHi when clamp=true" in {
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 10.0), 0.0, 100.0, nice = false, clamp = true)
        assert(scale.apply(Domain.Continuous(20.0)) == 100.0, "Expected 100.0 (clamped to rangeHi)")
    }

    // with clamp=false (default), the same value extrapolates beyond rangeHi.
    "Scale.Linear apply extrapolates a value beyond domainMax when clamp=false" in {
        val scale = Scale.fit(Scale.Kind.Linear, Extent.continuous(0.0, 10.0), 0.0, 100.0, nice = false)
        assert(scale.apply(Domain.Continuous(20.0)) == 200.0, "Expected 200.0 (extrapolated past rangeHi)")
    }

    // Symlog clamp=true: input 20.0 clamped to domainMax=10 before transform.
    "Symlog with clamp=true pins out-of-domain input to the domain boundary" in {
        val s = Scale.Symlog(0.0, 10.0, 0.0, 100.0, clamp = true)
        // apply(20.0) with clamp=true should equal apply(10.0): both domainMax.
        val clamped = s.apply(Domain.Continuous(20.0))
        val atMax   = s.apply(Domain.Continuous(10.0))
        assert(math.abs(clamped - atMax) < 1e-9, s"Clamped=$clamped should equal atMax=$atMax")
    }

    // ---- right-scale kind readback via Chart.Scales ----

    case class ScRow(x: String, yL: Double, yR: Double)
    given CanEqual[ScRow, ScRow] = CanEqual.derived

    "yScaleRight(_.log) resolves right scale as Log kind (kind readback via Chart.Scales)" in {
        // Use lowerWithScales (via lowerWithScales) to read the resolved right scale kind.
        // The right scale should be Log after .yScaleRight(_.log).
        // Data: yR=[1.0, 100.0]; with log scale, domain is [1.0, 100.0].
        val rows = kyo.Chunk(ScRow("a", 100.0, 1.0), ScRow("b", 200.0, 100.0))
        val spec = Chart(rows)(
            bar(x = _.x, y = _.yL),
            line(x = _.x, y = _.yR, axis = Axis.Right)
        ).yScaleRight(_.log)
        val (_, scales) = spec.lowerWithScales
        // The right axis should be Log kind.
        scales.yRight match
            case Present(ax) =>
                ax.kind match
                    case ScaleKind.Log => succeed
                    case other         => fail(s"Expected Log kind for right axis but got $other")
            case Absent =>
                fail("Expected a right axis (yRight is Present) but got Absent")
        end match
    }

    "no yScaleRight override -> right scale resolves as Linear+nice by default" in {
        // Without yScaleRight the right scale resolves as Linear+nice by default.
        val rows = kyo.Chunk(ScRow("a", 100.0, 0.0), ScRow("b", 200.0, 20.0))
        val spec = Chart(rows)(
            bar(x = _.x, y = _.yL),
            line(x = _.x, y = _.yR, axis = Axis.Right)
        )
        val (_, scales) = spec.lowerWithScales
        scales.yRight match
            case Present(ax) =>
                ax.kind match
                    case ScaleKind.Linear(_, _) => succeed
                    case other                  => fail(s"Expected Linear kind for default right axis but got $other")
            case Absent =>
                fail("Expected a right axis (yRight is Present) but got Absent")
        end match
    }

    // ---- niceTicks with an inverted domain (min > max) must return finite ascending ticks ----

    "niceTicks(100.0, 0.0, 5) returns finite ascending ticks including 0 and 100" in {
        // An inverted domain (min=100, max=0) with rawStep=(0-100)/(5-1)=-25 would give
        // log10(-25)=NaN, producing NaN ticks. niceTicks uses sorted bounds internally to avoid this.
        val result = Scale.niceTicks(100.0, 0.0, 5)
        assert(result.forall(v => !v.isNaN && !v.isInfinite), s"All ticks must be finite, got $result")
        assert(result.nonEmpty, "Expected non-empty ticks for inverted domain")
        // Ticks must be in ascending order and span [0, 100].
        assert(result.exists(v => math.abs(v - 0.0) < 1e-9), s"Ticks must include 0.0, got $result")
        assert(result.exists(v => math.abs(v - 100.0) < 1e-9), s"Ticks must include 100.0, got $result")
        // Ascending check
        result.zipWithIndex.foldLeft(()) { (_, vi) =>
            val (v, i) = vi
            if i > 0 then
                assert(v >= result(i - 1), s"Ticks must be ascending but got $result")
        }
    }

    "inverted linear domain via yScale(_.linear(100.0, 0.0)) produces no NaN in lowered SVG" in {
        // An inverted domain passed via yScale(_.linear(100,0)) triggers niceTicks(100,0,5) which
        // produced NaN ticks. Verify no "NaN" substring appears in tick label text nodes.
        case class Row(x: String, y: Double)
        given scala.CanEqual[Row, Row] = scala.CanEqual.derived
        val rows                       = kyo.Chunk(Row("a", 20.0), Row("b", 80.0))
        val spec = Chart(rows)(line(x = _.x, y = _.y))
            .yScale(_.linear(100.0, 0.0))
        val root = (spec).lower
        // Collect all Svg.Text nodes anywhere in the tree (axes, labels, ticks).
        def collectTexts(children: kyo.Chunk[UI]): kyo.Chunk[Svg.Text] =
            children.flatMap:
                case t: Svg.Text => kyo.Chunk(t)
                case g: Svg.G    => collectTexts(g.children)
                case _           => kyo.Chunk.empty
        val allTexts = collectTexts(root.children)
        // None of the text nodes should carry a "NaN" string child.
        allTexts.foldLeft(()) { (_, t) =>
            t.children.foldLeft(()) { (_, child) =>
                child match
                    case UI.Ast.Text(s) =>
                        assert(!s.contains("NaN"), s"Tick label must not contain NaN but got: $s")
                    case _ => ()
            }
        }
    }

    // ---- Band.invert returns the correct category for reversed pixel ranges ----

    "Band.invert returns the correct category for both normal and reversed pixel ranges" in {
        // Normal range: rangeLo=0, rangeHi=300, keys=["a","b","c"]
        // slot=100, bandW=90, pad=5
        // "a" left edge = 0 + 0*100 + 5 = 5;  center = 50
        // "b" left edge = 0 + 1*100 + 5 = 105; center = 150
        // "c" left edge = 0 + 2*100 + 5 = 205; center = 250
        // invert(50)  -> slot index = floor((50-0)/100) = 0 -> "a"   (correct)
        // invert(150) -> floor((150-0)/100) = 1 -> "b"   (correct)
        // invert(250) -> floor((250-0)/100) = 2 -> "c"   (correct)
        val keys         = kyo.Chunk("a", "b", "c")
        val normalBand   = Scale.fit(Scale.Kind.Band, Extent.categories(keys), 0.0, 300.0)
        val reversedBand = Scale.fit(Scale.Kind.Band, Extent.categories(keys), 300.0, 0.0)

        // Normal range: verify invert at each band's slot midpoint
        normalBand.invert(50.0) match
            case Domain.Category(k) => assert(k == "a", s"Normal invert(50) should be 'a' but got '$k'")
            case other              => fail(s"Expected Category but got $other")
        normalBand.invert(150.0) match
            case Domain.Category(k) => assert(k == "b", s"Normal invert(150) should be 'b' but got '$k'")
            case other              => fail(s"Expected Category but got $other")
        normalBand.invert(250.0) match
            case Domain.Category(k) => assert(k == "c", s"Normal invert(250) should be 'c' but got '$k'")
            case other              => fail(s"Expected Category but got $other")

        // Reversed range: rangeLo=300, rangeHi=0, slot=-100 (negative slot corrupts the formula).
        // apply("a") in reversed band: rangeLo=300, slot=-100, bandW=-90, pad=-5
        //   "a" -> 300 + 0*(-100) + (-100 - (-90))/2 = 300 - 5 = 295
        //   "b" -> 300 + 1*(-100) - 5 = 195
        //   "c" -> 300 + 2*(-100) - 5 = 95
        // invert(295) should map back to "a", invert(195) -> "b", invert(95) -> "c".
        reversedBand.invert(295.0) match
            case Domain.Category(k) => assert(k == "a", s"Reversed invert(295) should be 'a' but got '$k'")
            case other              => fail(s"Expected Category but got $other")
        reversedBand.invert(195.0) match
            case Domain.Category(k) => assert(k == "b", s"Reversed invert(195) should be 'b' but got '$k'")
            case other              => fail(s"Expected Category but got $other")
        reversedBand.invert(95.0) match
            case Domain.Category(k) => assert(k == "c", s"Reversed invert(95) should be 'c' but got '$k'")
            case other              => fail(s"Expected Category but got $other")
    }

end ScaleTest
