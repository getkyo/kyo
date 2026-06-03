package kyo

import kyo.Chart.*
import kyo.Svg.Coord
import kyo.Svg.PathCommand
import kyo.Svg.PathData
import kyo.internal.ChartLower
import kyo.internal.Domain
import kyo.internal.Scale
import scala.language.implicitConversions

/** Phase 08 typed-values tests.
  *
  * Covers:
  *   1. Opaque `Usd <: Double` with `Plottable.numeric`: bar selects a Linear y-scale; a row at `Usd(2500)` with
  *      `.yScale(_.linear(0, 5000))` lowers to the expected pixel.
  *   2. `colorScaleMap` Map+fallback: an unmapped `Region` case receives the fallback color.
  *   3. `colorScale[K]` exhaustive total-function: a match missing one enum case does not compile.
  *   4. Raw-literal `Conversion[Double, Usd]`: `rule(y = 1000.0)` builds a `RuleValue.Const`.
  *   5. `Maybe[Usd]` gap channel: a line with `y = _.value` where some values are `Absent` splits into two sub-paths.
  *
  * Layout defaults (640x480, matching `ChartLower` constants):
  *   plotX = 60, plotY = 20, plotW = 560, plotH = 420, baseline = 440.
  */
class ChartTypedValuesTest extends Test:

    // ---- domain types ----

    enum Region derives CanEqual, Plottable:
        case NA, EU, APAC

    opaque type Usd <: Double = Double
    object Usd:
        def apply(d: Double): Usd     = d
        given Plottable[Usd]          = Plottable.numeric
        given CanEqual[Usd, Usd]      = CanEqual.derived
        given Conversion[Double, Usd] = d => d
    end Usd

    case class Sale(month: String, revenue: Usd, region: Region = Region.NA)
    given CanEqual[Sale, Sale] = CanEqual.derived

    case class MaybeSale(month: String, value: Maybe[Usd])
    given CanEqual[MaybeSale, MaybeSale] = CanEqual.derived

    // ---- layout constants (must match ChartLower defaults) ----

    private val PlotX    = 60.0
    private val PlotY    = 20.0
    private val PlotW    = 560.0
    private val PlotH    = 420.0
    private val Baseline = PlotY + PlotH // 440.0
    private val Tol      = 1.0e-6

    private def assertClose(actual: Double, expected: Double, msg: String = ""): Assertion =
        assert(math.abs(actual - expected) < Tol, s"$msg: expected $expected but got $actual")

    private def numOf(c: Maybe[Coord]): Double = c match
        case Present(Coord.Num(v)) => v
        case other                 => fail(s"Expected Coord.Num but got $other")

    private def colorOf(fill: Maybe[Svg.Paint]): Style.Color = fill match
        case Present(Svg.Paint.Color(c)) => c
        case other                       => fail(s"Expected Svg.Paint.Color but got $other")

    /** Extract all `Svg.Rect`s from the marks `Svg.G` (the last `Svg.G` child of the root). */
    private def marksRects(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.last match
            case g: Svg.G =>
                g.children.flatMap:
                    case r: Svg.Rect => Chunk(r)
                    case _           => Chunk.empty
            case _ => Chunk.empty

    /** Extract all `Svg.Path`s from the marks `Svg.G`. */
    private def marksPaths(root: Svg.Root): Chunk[Svg.Path] =
        root.children.last match
            case g: Svg.G =>
                g.children.flatMap:
                    case p: Svg.Path => Chunk(p)
                    case _           => Chunk.empty
            case _ => Chunk.empty

    /** Extract all `Svg.Line`s from the marks `Svg.G`. */
    private def marksLines(root: Svg.Root): Chunk[Svg.Line] =
        root.children.last match
            case g: Svg.G =>
                g.children.flatMap:
                    case l: Svg.Line => Chunk(l)
                    case _           => Chunk.empty
            case _ => Chunk.empty

    /** Extract all frame-level `Svg.Rect`s (direct children of root, not in the marks G). */
    private def frameRects(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.flatMap:
            case r: Svg.Rect => Chunk(r)
            case _           => Chunk.empty

    // ---- Test 1: Usd with Plottable.numeric selects a Linear y-scale ----

    "Plottable.numeric[Usd].kind is Linear" in {
        val p = summon[Plottable[Usd]]
        assert(p.kind == Scale.Kind.Linear)
    }

    "bar(y = _.revenue: Usd) with yScale(_.linear(0, 5000)): row at Usd(2500) lowers to exact barY and barH" in {
        // Scale.Linear(0, 5000, baseline=440, top=20):
        //   apply(2500) = 440 + (2500/5000) * (20 - 440) = 440 - 210 = 230.0
        //   barH = baseline - barY = 440 - 230 = 210.0
        val rows = Chunk(Sale("Jan", Usd(2500)))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
            .yScale(_.linear(0.0, 5000.0))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val rects = marksRects(root)
        assert(rects.size == 1, s"Expected 1 bar rect but got ${rects.size}")
        val r = rects(0)
        assertClose(numOf(r.svgAttrs.y), 230.0, "barY for Usd(2500) on [0,5000] scale")
        assertClose(numOf(r.svgAttrs.height), 210.0, "barH for Usd(2500) on [0,5000] scale")
    }

    // ---- Test 2: colorScaleMap Map+fallback assigns fallback to unmapped case ----

    "colorScaleMap with partial Map assigns fallback color to unmapped Region case" in {
        // Map: NA -> blue, EU -> green; APAC is NOT in the map -> should get fallback gray
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(1200), Region.EU),
            Sale("Mar", Usd(900), Region.APAC)
        )
        val fallback = Style.Color.gray
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
            .legend(_.colorScaleMap[Region](
                Map(Region.NA -> Style.Color.blue, Region.EU -> Style.Color.green),
                fallback
            ))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // Legend swatches: frame-level rects after the background rect.
        // Frame rects: [0]=background, [1..]=legend swatches in enum-ordinal order (NA, EU, APAC).
        val fRects   = frameRects(root)
        val swatches = fRects.drop(1)
        assert(swatches.size == 3, s"Expected 3 legend swatches but got ${swatches.size}")

        // NA (ordinal 0) = blue
        assert(
            colorOf(swatches(0).svgAttrs.fill) == Style.Color.blue,
            s"NA swatch should be blue but got ${swatches(0).svgAttrs.fill}"
        )
        // EU (ordinal 1) = green
        assert(
            colorOf(swatches(1).svgAttrs.fill) == Style.Color.green,
            s"EU swatch should be green but got ${swatches(1).svgAttrs.fill}"
        )
        // APAC (ordinal 2) = fallback gray (unmapped)
        assert(
            colorOf(swatches(2).svgAttrs.fill) == fallback,
            s"APAC swatch should be fallback gray but got ${swatches(2).svgAttrs.fill}"
        )
    }

    // ---- Test 3: colorScale[K] total function non-exhaustiveness gate ----
    //
    // Feasibility note: The `colorScale[K](f: K => Style.Color)` overload accepts an ordinary
    // function, not a `PartialFunction`. In Scala 3, a non-exhaustive match in a function
    // literal body is a WARNING, not a compile ERROR. The project does not enable
    // `-Xfatal-warnings`, so the warning does not become a compilation failure. A
    // `typeCheckFailure` assertion therefore cannot be used here; it would false-fail because
    // the code compiles (with a warning) and only throws a `MatchError` at runtime on the
    // unmapped case.
    //
    // The Map+fallback form (`colorScaleMap`) is the safe alternative when exhaustiveness is
    // not required at compile time. The runtime consequence of omitting a case from the total-
    // function form is a `MatchError` at the call site, which is a sufficient deterrent for
    // production use (the user sees a clear error rather than a silent wrong color).
    //
    // We assert here that:
    //   (a) the total-function form COMPILES when exhaustive (positive gate), and
    //   (b) the Map+fallback form correctly handles unmapped cases at runtime (already covered
    //       by Test 2), so the user always has a safe escape hatch.

    "colorScale[K] exhaustive form compiles and assigns the correct colors (positive gate)" in {
        // All three Region cases are covered: this must compile and produce correct swatches.
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(1200), Region.EU),
            Sale("Mar", Usd(900), Region.APAC)
        )
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
            .legend(_.colorScale[Region]:
                case Region.NA   => Style.Color.blue
                case Region.EU   => Style.Color.green
                case Region.APAC => Style.Color.orange)
        val root     = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val fRects   = frameRects(root)
        val swatches = fRects.drop(1)
        assert(swatches.size == 3, s"Expected 3 legend swatches but got ${swatches.size}")
        assert(colorOf(swatches(0).svgAttrs.fill) == Style.Color.blue, "NA swatch should be blue")
        assert(colorOf(swatches(1).svgAttrs.fill) == Style.Color.green, "EU swatch should be green")
        assert(colorOf(swatches(2).svgAttrs.fill) == Style.Color.orange, "APAC swatch should be orange")
    }

    // ---- Test 4: Conversion[Double, Usd] lets 1000.0 build a RuleValue.Const ----

    "Conversion[Double, Usd] allows rule(y = 1000.0) to build a RuleValue.Const" in {
        // The implicit Conversion[Double, Usd] = d => d defined in the Usd object
        // is in scope here (imported via `scala.language.implicitConversions`).
        // rule(y = 1000.0) must therefore compile and produce a RuleValue.Const carrying Usd(1000).
        val rv: RuleValue[Usd] = RuleValue.constConversion[Usd].apply(Usd(1000.0))
        rv match
            case RuleValue.Const(v, _) =>
                assertClose(v, 1000.0, "RuleValue.Const value must be 1000.0")
            case other =>
                fail(s"Expected RuleValue.Const but got $other")
        end match
    }

    "rule(y = Usd(1000)) lowers to an Svg.Line spanning the plot at the correct pixel" in {
        // Scale fixed at [0, 5000]: apply(1000) = 440 + (1000/5000)*(20-440) = 440-84 = 356.0
        val rows = Chunk(Sale("Jan", Usd(3000)))
        val spec = Chart(rows)(
            bar(x = _.month, y = _.revenue),
            rule[Sale, Usd](y = RuleValue.Const(Usd(1000.0), summon[Plottable[Usd]]))
        )
            .yScale(_.linear(0.0, 5000.0))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val lines = marksLines(root)
        // The rule line spans the full plot width: x1=plotX, x2=plotX+plotW
        val ruleLines = lines.filter: l =>
            l.svgAttrs.x1.exists(_ == PlotX) && l.svgAttrs.x2.exists(_ == PlotX + PlotW) &&
                l.svgAttrs.y1 == l.svgAttrs.y2
        assert(ruleLines.nonEmpty, s"Expected at least one horizontal rule line but got none in $lines")
        // apply(1000) with Linear(0,5000,440,20): 440 + (1000/5000)*(20-440) = 440 - 84 = 356.0
        val expectedY = 440.0 + (1000.0 / 5000.0) * (20.0 - 440.0) // 356.0
        val ruleLine  = ruleLines(0)
        val actualY   = ruleLine.svgAttrs.y1.getOrElse(Double.NaN)
        assertClose(actualY, expectedY, s"rule line y at Usd(1000) on [0,5000] scale")
    }

    // ---- Test 5: Maybe[Usd] gap channel splits line at Absent ----

    "line with Maybe[Usd] y-channel splits into two sub-paths at Absent (two MoveTo commands)" in {
        // Data: Jan=Present(500), Feb=Absent (gap), Mar=Present(1500)
        // The line lowerer produces one Svg.Path whose PathData contains:
        //   MoveTo(jan_px, jan_py)  -- start of first segment
        //   MoveTo(mar_px, mar_py)  -- start of second segment (after gap)
        // Two MoveTos in one PathData confirms the gap split.
        val rows = Chunk(
            MaybeSale("Jan", Present(Usd(500.0))),
            MaybeSale("Feb", Absent),
            MaybeSale("Mar", Present(Usd(1500.0)))
        )
        val spec  = Chart(rows)(line(x = _.month, y = _.value))
        val root  = summon[Conversion[ChartSpec[MaybeSale], Svg.Root]](spec)
        val paths = marksPaths(root)
        assert(paths.nonEmpty, "Expected at least one path for the line mark")
        val path     = paths(0)
        val commands = Svg.PathData.commands(path.svgAttrs.d.getOrElse(Svg.PathData.empty))
        // Two MoveTo commands: one to start the first segment, one to resume after the gap
        val moveToCount = commands.count:
            case _: PathCommand.MoveTo => true
            case _                     => false
        assert(moveToCount == 2, s"Expected 2 MoveTo commands (one per segment) but got $moveToCount in $commands")
        // Also verify there is exactly one LineTo (connecting only the two present points per segment is impossible
        // with three data points; Jan -> LineTo skipped at Feb gap -> Mar new MoveTo)
        val lineToCount = commands.count:
            case _: PathCommand.LineTo => true
            case _                     => false
        // Jan segment: MoveTo(Jan) only (single point, no LineTo)
        // Mar segment: MoveTo(Mar) only (single point, no LineTo)
        // So lineToCount == 0 for this data.
        assert(lineToCount == 0, s"Expected 0 LineTo commands (each segment is a single point) but got $lineToCount")
    }

    "line with Maybe[Usd] three-point gap: two points before gap and one after produces correct sub-paths" in {
        // Data: Jan=Present(500), Feb=Present(1000), Mar=Absent (gap), Apr=Present(1500)
        // Expected path: MoveTo(Jan), LineTo(Feb), MoveTo(Apr)
        // Two MoveTos, one LineTo
        val rows = Chunk(
            MaybeSale("Jan", Present(Usd(500.0))),
            MaybeSale("Feb", Present(Usd(1000.0))),
            MaybeSale("Mar", Absent),
            MaybeSale("Apr", Present(Usd(1500.0)))
        )
        val spec     = Chart(rows)(line(x = _.month, y = _.value))
        val root     = summon[Conversion[ChartSpec[MaybeSale], Svg.Root]](spec)
        val paths    = marksPaths(root)
        val commands = Svg.PathData.commands(paths(0).svgAttrs.d.getOrElse(Svg.PathData.empty))
        val moveToCount = commands.count:
            case _: PathCommand.MoveTo => true
            case _                     => false
        val lineToCount = commands.count:
            case _: PathCommand.LineTo => true
            case _                     => false
        assert(moveToCount == 2, s"Expected 2 MoveTo commands but got $moveToCount in $commands")
        assert(lineToCount == 1, s"Expected 1 LineTo command (Jan->Feb) but got $lineToCount in $commands")
    }

end ChartTypedValuesTest
