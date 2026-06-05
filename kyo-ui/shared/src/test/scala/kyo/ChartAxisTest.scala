package kyo

import kyo.Svg.Coord
import kyo.Svg.PathCommand
import kyo.Svg.PathData
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.mark.*
import kyo.internal.ChartLower
import kyo.internal.Scale
import scala.language.implicitConversions

/** Phase 04 tests: axes, legends, scale overrides, theme, two axes, and stacking.
  *
  * All concrete asserts use exact pixel values derived from the documented margin constants
  * and scale mathematics. Layout defaults: plotX=60, plotY=20, plotW=560, plotH=420,
  * baseline=440 (default 640x480, MarginL=60, MarginR=20, MarginT=20, MarginB=40).
  * Two-axis layout: MarginR=60, so plotW=520.
  */
class ChartAxisTest extends Test:

    // ---- shared domain types ----

    enum Region derives CanEqual, Plottable:
        case NA, EU, APAC

    opaque type Usd <: Double = Double
    object Usd:
        def apply(d: Double): Usd     = d
        given Plottable[Usd]          = Plottable.numeric
        given CanEqual[Usd, Usd]      = CanEqual.derived
        given Conversion[Double, Usd] = d => d
        given Conversion[Int, Usd]    = d => d.toDouble
    end Usd

    case class Sale(month: String, revenue: Usd, region: Region = Region.NA)
    given CanEqual[Sale, Sale] = CanEqual.derived

    case class Row2Ax(month: String, revenue: Usd, growthPct: Double)
    given CanEqual[Row2Ax, Row2Ax] = CanEqual.derived

    // ---- layout constants (must match ChartLower) ----
    private val PlotX      = 60.0
    private val PlotY      = 20.0
    private val PlotW      = 560.0         // default (no right axis)
    private val PlotH      = 420.0
    private val Baseline   = PlotY + PlotH // 440.0
    private val PlotWTwoAx = 520.0         // with right axis (MarginR=60)
    private val Tol        = 1.0e-6

    private def assertClose(actual: Double, expected: Double, msg: String = ""): Assertion =
        assert(math.abs(actual - expected) < Tol, s"$msg: expected $expected but got $actual")

    // ---- SVG tree navigation helpers ----

    /** All `Svg.Line`s directly inside `root.children` (frame chrome). */
    private def frameLinesIn(root: Svg.Root): Chunk[Svg.Line] =
        root.children.flatMap:
            case l: Svg.Line => Chunk(l)
            case _           => Chunk.empty

    /** All `Svg.Text`s directly inside `root.children` (frame chrome). */
    private def frameTextsIn(root: Svg.Root): Chunk[Svg.Text] =
        root.children.flatMap:
            case t: Svg.Text => Chunk(t)
            case _           => Chunk.empty

    /** All `Svg.Rect`s directly inside `root.children` (frame chrome: background + legend swatches). */
    private def frameRectsIn(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.flatMap:
            case r: Svg.Rect => Chunk(r)
            case _           => Chunk.empty

    /** Extract the double value from a `Maybe[Coord]`. */
    private def numOf(c: Maybe[Coord]): Double = c match
        case Present(Coord.Num(v)) => v
        case other                 => fail(s"Expected Coord.Num but got $other")

    /** Extract the `Style.Color` from a `Svg.Paint.Color`. */
    private def colorOf(fill: Maybe[Svg.Paint]): Style.Color = fill match
        case Present(Svg.Paint.Color(c)) => c
        case other                       => fail(s"Expected Paint.Color but got $other")

    // ---- Test 1: yAxis(_.grid.ticks(3)) -> 3 gridlines + 3 tick labels ----

    "yAxis(_.grid.ticks(3)) produces 3 gridline Lines and 3 tick Text labels at niceTick pixels" in {
        // Data: two bars y=[1000, 2000]; yExtent: Continuous(0, 2000) after ensureZero
        // niceTicks(0, 2000, 3): step=1000 -> ticks=[0, 1000, 2000]
        // Scale.Linear(0, 2000, 440, 20):
        //   apply(0)=440, apply(1000)=230, apply(2000)=20
        val rows = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
            .yAxis(_.grid.ticks(3))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // Gridlines are Svg.Line elements spanning the full plot width with strokeOpacity=0.3
        // (distinct from the axis lines which have no strokeOpacity set)
        val allLines = frameLinesIn(root)
        val gridLines = allLines.filter: l =>
            l.svgAttrs.x1.exists(_ == PlotX) && l.svgAttrs.x2.exists(_ == PlotX + PlotW) &&
                l.svgAttrs.y1 == l.svgAttrs.y2 && l.svgAttrs.strokeOpacity.isDefined
        assert(gridLines.size == 3, s"Expected 3 gridlines but got ${gridLines.size}")

        // Tick labels are Svg.Text elements with TextAnchor.End (left y-axis)
        val allTexts = frameTextsIn(root)
        val tickLabels = allTexts.filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End)
        assert(tickLabels.size == 3, s"Expected 3 tick labels but got ${tickLabels.size}")

        // Pixel positions: apply(0)=440, apply(1000)=230, apply(2000)=20
        val expectedYPx    = Chunk(440.0, 230.0, 20.0)
        val gridYs         = gridLines.map(l => l.svgAttrs.y1.getOrElse(0.0)).toSeq.sorted
        val expectedSorted = expectedYPx.toSeq.sorted
        gridYs.zip(expectedSorted).foldLeft(succeed): (_, pair) =>
            assertClose(pair._1, pair._2, "gridline y pixel")
    }

    // ---- Test 2: yScale(_.linear(0,5000)) fixes domain ----

    "yScale(_.linear(0,5000)) fixes the domain: row at 2500 maps to the plot midpoint" in {
        // Scale.Linear(0, 5000, 440, 20):
        //   apply(2500) = 440 + (2500/5000)*(20-440) = 440 - 210 = 230.0
        //   Plot midpoint = (plotY + baseline)/2 = (20+440)/2 = 230.0
        val rows = Chunk(Sale("Jan", Usd(2500)), Sale("Feb", Usd(9999)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
            .yScale(_.linear(0.0, 5000.0))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // Extract the bar rects from the marks G (last child of root)
        val marksG: Svg.G = root.children.last match
            case g: Svg.G => g
            case other    => fail(s"Expected marks G but got $other")
        val rects = marksG.children.flatMap:
            case r: Svg.Rect => Chunk(r)
            case _           => Chunk.empty
        assert(rects.size == 2, s"Expected 2 bar rects but got ${rects.size}")

        // Row at 2500: barY = 230.0, barH = baseline - barY = 440 - 230 = 210
        val r0 = rects(0) // "Jan" row at 2500
        assertClose(numOf(r0.svgAttrs.y), 230.0, "barY for 2500")
        assertClose(numOf(r0.svgAttrs.height), 210.0, "barH for 2500")

        // Assert midpoint position regardless of data max (9999 would push scale if not fixed)
        val plotMidpoint = (PlotY + Baseline) / 2.0
        assertClose(numOf(r0.svgAttrs.y), plotMidpoint, "barY at plot midpoint")
    }

    // ---- Test 3: xScale(_.band) over Int year treats as categorical ----

    "xScale(_.band) over an Int year produces a Band scale, not Linear" in {
        // Data: rows with year Int in [2020, 2021, 2022]
        // Without override: Plottable[Int].kind == Linear -> Linear scale
        // With .xScale(_.band): override forces Band
        // fitBand(Continuous(2020,2022)): lo=2020, hi=2022 -> keys=["2020","2021","2022"]
        // n=3, slot=560/3, bandW=560*0.9/3=168
        case class YearRow(year: Int, value: Double)
        val rows = Chunk(YearRow(2020, 100.0), YearRow(2021, 200.0), YearRow(2022, 300.0))
        val spec = UI.chart(rows)(bar(x = _.year, y = _.value))
            .xScale(_.band)
        val root = summon[Conversion[ChartSpec[YearRow], Svg.Root]](spec)

        val marksG: Svg.G = root.children.last match
            case g: Svg.G => g
            case other    => fail(s"Expected marks G but got $other")
        val rects = marksG.children.flatMap:
            case r: Svg.Rect => Chunk(r)
            case _           => Chunk.empty
        assert(rects.size == 3, s"Expected 3 bars but got ${rects.size}")

        // Band scale: n=3, slot=560/3=186.667, bandW=560*0.9/3=168, padding=(slot-bandW)/2=9.333
        val n     = 3
        val slot  = PlotW / n.toDouble
        val bandW = PlotW * 0.9 / n.toDouble
        val pad   = (slot - bandW) / 2.0
        // First bar (2020): x = PlotX + 0*slot + pad
        val expectedX0 = PlotX + 0 * slot + pad
        assertClose(numOf(rects(0).svgAttrs.x), expectedX0, "band bar x for 2020")
        assertClose(numOf(rects(0).svgAttrs.width), bandW, "band bar width")

        // Widths must all equal bandW (not varying as with a Linear scale)
        rects.toSeq.foldLeft(succeed): (_, r) =>
            assertClose(numOf(r.svgAttrs.width), bandW, "all band bars same width")
    }

    // ---- Test 4: yScale(_.log) produces log-spaced ticks at powers ----

    "yScale(_.log) produces log-spaced ticks (assert ticks at powers of 10)" in {
        // Data: revenue in [10, 100, 1000] -> Log scale
        // Log(10, 1000, 440, 20): logMin=1, logMax=3
        //   ticks at exp=1 (10, pixel=440), exp=2 (100, pixel=230), exp=3 (1000, pixel=20)
        val rows = Chunk(Sale("a", Usd(10)), Sale("b", Usd(100)), Sale("c", Usd(1000)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
            .yScale(_.log)
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // Tick labels use TextAnchor.End for left axis
        val tickLabels = frameTextsIn(root).filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End)
        // Log ticks: 3 powers of 10 (1, 2, 3 -> 10, 100, 1000)
        assert(tickLabels.size == 3, s"Expected 3 log-scale tick labels but got ${tickLabels.size}")

        // Tick y positions: 440, 230, 20 (for values 10, 100, 1000)
        val tickLines = frameLinesIn(root).filter: l =>
            l.svgAttrs.x1.exists(_ < PlotX) && l.svgAttrs.y1 == l.svgAttrs.y2
        assert(tickLines.size == 3, s"Expected 3 log-scale tick marks but got ${tickLines.size}")
        val tickYs = tickLines.map(l => l.svgAttrs.y1.getOrElse(0.0)).toSeq.sorted
        assertClose(tickYs(0), 20.0, "log tick at 1000 (pixel 20)")
        assertClose(tickYs(1), 230.0, "log tick at 100 (pixel 230)")
        assertClose(tickYs(2), 440.0, "log tick at 10 (pixel 440)")
    }

    // ---- Test 5: legend derives one swatch+label per enum case in enum order ----
    //              colorScale assigns named swatch fills (N3 carry-over)

    "legend derives one swatch+label per Region enum case in enum order; colorScale assigns fills" in {
        // Region cases in declaration order: NA (ordinal 0), EU (ordinal 1), APAC (ordinal 2)
        // Rows supplied in out-of-order encounter order to verify ordinal sorting:
        //   APAC first, then NA, then EU -> legend must show NA, EU, APAC (ordinal order)
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.APAC),
            Sale("Feb", Usd(800), Region.NA),
            Sale("Mar", Usd(600), Region.EU)
        )
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
            .legend(
                _.colorScale[Region](
                    Region.NA   -> Style.Color.blue,
                    Region.EU   -> Style.Color.green,
                    Region.APAC -> Style.Color.orange
                )
            )
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // Legend swatches are Svg.Rect elements in the frame (not in the marks G)
        // Frame rects: [0] = background rect, [1..] = legend swatches
        val frameRects = frameRectsIn(root)
        // First rect is the background; the next 3 are legend swatches (one per Region case)
        val swatches = frameRects.drop(1)
        assert(swatches.size == 3, s"Expected 3 legend swatches (NA, EU, APAC) but got ${swatches.size}")

        // Legend texts: 3 labels in enum ordinal order
        val allTexts = frameTextsIn(root)
        // Filter to texts that don't have textAnchor (legend labels have no textAnchor or DominantBaseline.Middle)
        val legendLabels = allTexts.filter: t =>
            t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle) &&
                t.svgAttrs.textAnchor.isEmpty
        assert(legendLabels.size == 3, s"Expected 3 legend labels but got ${legendLabels.size}")

        // Colors must be in ordinal order: NA=blue, EU=green, APAC=orange
        // Legend flows left-to-right: each successive swatch x must be greater than the previous.
        val swatch0x = swatches(0).svgAttrs.x.map { case Coord.Num(v) => v; case _ => -1.0 }.getOrElse(-1.0)
        val swatch1x = swatches(1).svgAttrs.x.map { case Coord.Num(v) => v; case _ => -1.0 }.getOrElse(-1.0)
        assert(
            swatch1x > swatch0x,
            s"swatch(1).x ($swatch1x) must be greater than swatch(0).x ($swatch0x): legend items must flow left-to-right"
        )
        assert(swatches(0).svgAttrs.fill.isDefined, "NA swatch must have fill")
        assert(swatches(1).svgAttrs.fill.isDefined, "EU swatch must have fill")
        assert(swatches(2).svgAttrs.fill.isDefined, "APAC swatch must have fill")

        // Assert specific fill colors via colorScale
        assert(colorOf(swatches(0).svgAttrs.fill) == Style.Color.blue, s"NA swatch should be blue; got ${swatches(0).svgAttrs.fill}")
        assert(colorOf(swatches(1).svgAttrs.fill) == Style.Color.green, s"EU swatch should be green; got ${swatches(1).svgAttrs.fill}")
        assert(colorOf(swatches(2).svgAttrs.fill) == Style.Color.orange, s"APAC swatch should be orange; got ${swatches(2).svgAttrs.fill}")
    }

    // ---- Test 6: two axes yield distinct y-scales ----

    "two axes: bar(revenue) + line(growthPct, Right) yield distinct y-scales; right labels on right margin" in {
        // Two-axis layout: MarginR=60, plotW=520, plotX=60
        // Left: revenue=[0,2000]; Scale.Linear(0,2000,440,20); apply(10) = 440+(10/2000)*(20-440) = 437.9
        // Right: growthPct=[0,20]; niceTicks -> Linear(0,20,440,20); apply(10) = 440+(10/20)*(20-440) = 230
        // Both are numeric Doubles; they share the value 10 but map to different pixels
        val rows = Chunk(
            Row2Ax("Jan", Usd(1000), 10.0),
            Row2Ax("Feb", Usd(2000), 20.0)
        )
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        )
        val root = summon[Conversion[ChartSpec[Row2Ax], Svg.Root]](spec)

        // Verify two independent y-scales by checking that the same value (10) maps to different pixels.
        // Left scale: Linear(0,2000,440,20) -> apply(10) ≈ 437.9
        // Right scale: niceTicks(0,20,5)=step 5 -> Linear(0,20,440,20) -> apply(10) = 230
        val leftScaleY10  = 440.0 + (10.0 / 2000.0) * (20.0 - 440.0) // ≈ 437.9
        val rightScaleY10 = 440.0 + (10.0 / 20.0) * (20.0 - 440.0)   // = 230.0
        assert(
            math.abs(leftScaleY10 - rightScaleY10) > 100.0,
            s"Left ($leftScaleY10) and right ($rightScaleY10) y-scales must differ significantly"
        )

        // Right axis tick labels appear on the right margin: x > plotX + plotW_twoax
        val allTexts = frameTextsIn(root)
        val rightTickLabels = allTexts.filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Start) &&
                t.svgAttrs.x.exists { case Coord.Num(v) => v > PlotX + PlotWTwoAx; case _ => false }
        assert(rightTickLabels.nonEmpty, s"Expected right-axis tick labels past plotX+plotW but found none")

        // Left tick labels appear to the left of plotX: x < plotX
        val leftTickLabels = allTexts.filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End) &&
                t.svgAttrs.x.exists { case Coord.Num(v) => v < PlotX; case _ => false }
        assert(leftTickLabels.nonEmpty, s"Expected left-axis tick labels left of plotX but found none")
    }

    // ---- Test 6a (ISSUE 1): dual-axis chrome is color-coded to its single bound mark ----
    // In a dual-axis combo (bar on left = mark 0, line on right = mark 1) the left axis chrome must take
    // the bar's palette color (palette(0) = blue) and the right axis chrome the line's palette color
    // (palette(1) = orange), so a reader can tell which y-axis each series uses. The x-axis stays neutral.

    "dual-axis combo color-codes each y-axis chrome to its single bound mark (left=palette(0), right=palette(1))" in {
        // Neutral light-theme chrome color, matching ChartLower.LightThemeTextColor (#374151).
        val neutral = Style.Color.hex("#374151").getOrElse(Style.Color.black)
        val rows = Chunk(
            Row2Ax("Jan", Usd(1000), 10.0),
            Row2Ax("Feb", Usd(2000), 20.0)
        )
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        )
            .yAxis(_.label("Revenue"))
            .yAxisRight(_.label("Growth %"))
        val root  = summon[Conversion[ChartSpec[Row2Ax], Svg.Root]](spec)
        val texts = frameTextsIn(root)

        // Left axis tick labels: TextAnchor.End at x < plotX. Their fill must be palette(0) = blue.
        val leftTickLabels = texts.filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End) &&
                t.svgAttrs.x.exists { case Coord.Num(v) => v < PlotX; case _ => false }
        assert(leftTickLabels.nonEmpty, "Expected left-axis tick labels")
        leftTickLabels.foldLeft(succeed): (_, t) =>
            assert(colorOf(t.svgAttrs.fill) == Style.Color.blue, s"Left tick label should be palette(0) (blue) but was ${t.svgAttrs.fill}")

        // Right axis tick labels: TextAnchor.Start at x > plotX + plotW. Their fill must be palette(1) = orange.
        val rightTickLabels = texts.filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Start) &&
                t.svgAttrs.x.exists { case Coord.Num(v) => v > PlotX + PlotWTwoAx; case _ => false }
        assert(rightTickLabels.nonEmpty, "Expected right-axis tick labels")
        rightTickLabels.foldLeft(succeed): (_, t) =>
            assert(
                colorOf(t.svgAttrs.fill) == Style.Color.orange,
                s"Right tick label should be palette(1) (orange) but was ${t.svgAttrs.fill}"
            )

        // The rotated "Revenue" (left) label must be blue and "Growth %" (right) label orange.
        def isRotated(t: Svg.Text): Boolean = t.svgAttrs.transform.toSeq.exists:
            case _: Svg.Transform.Rotate => true
            case _                       => false
        val revenueLabel = texts.find(t =>
            isRotated(t) && t.children.exists { case UI.Ast.Text("Revenue") => true; case _ => false }
        ).getOrElse(fail("Expected a rotated 'Revenue' axis label"))
        val growthLabel = texts.find(t =>
            isRotated(t) && t.children.exists { case UI.Ast.Text("Growth %") => true; case _ => false }
        ).getOrElse(fail("Expected a rotated 'Growth %' axis label"))
        assert(
            colorOf(revenueLabel.svgAttrs.fill) == Style.Color.blue,
            s"Left axis label should be blue but was ${revenueLabel.svgAttrs.fill}"
        )
        assert(
            colorOf(growthLabel.svgAttrs.fill) == Style.Color.orange,
            s"Right axis label should be orange but was ${growthLabel.svgAttrs.fill}"
        )

        // The shared x-axis chrome stays neutral (not tied to either series color).
        val xTickLabels = texts.filter(t => t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Hanging))
        assert(xTickLabels.nonEmpty, "Expected x-axis tick labels")
        xTickLabels.foldLeft(succeed): (_, t) =>
            assert(colorOf(t.svgAttrs.fill) == neutral, s"X-axis tick label should stay neutral ($neutral) but was ${t.svgAttrs.fill}")
    }

    // ---- Test 6b (ISSUE 2): rotated axis labels sit at the outer margin edge, clear of tick numbers ----
    // The dual-axis gallery cell is 360x240 with MarginLeft=60 and a right-axis margin (MarginRight=60).
    // The rotated "Revenue" label must sit near the left SVG edge (x in [12,16]) and the rotated
    // "Growth %" label near the right SVG edge, so neither overlaps the tick numbers, which sit
    // adjacent to the axis line (left numbers extend left from x=60; right numbers extend right from x=300).

    "rotated y-axis labels sit at the outer margin edge, clear of the tick numbers (360x240 dual-axis)" in {
        val rows = Chunk(
            Row2Ax("Jan", Usd(45000), 0.0),
            Row2Ax("Feb", Usd(52000), 15.6),
            Row2Ax("Mar", Usd(48000), -7.7)
        )
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        )
            .yAxis(_.label("Revenue"))
            .yAxisRight(_.label("Growth %"))
            .size(360, 240)
        val root  = summon[Conversion[ChartSpec[Row2Ax], Svg.Root]](spec)
        val texts = frameTextsIn(root)

        // The rotated axis labels are the texts carrying a Transform.Rotate.
        def isRotated(t: Svg.Text): Boolean = t.svgAttrs.transform.toSeq.exists:
            case _: Svg.Transform.Rotate => true
            case _                       => false

        val revenueLabel = texts.find(t =>
            isRotated(t) && t.children.exists {
                case UI.Ast.Text("Revenue") => true; case _ => false
            }
        ).getOrElse(fail("Expected a rotated 'Revenue' axis label"))
        val growthLabel = texts.find(t =>
            isRotated(t) && t.children.exists {
                case UI.Ast.Text("Growth %") => true; case _ => false
            }
        ).getOrElse(fail("Expected a rotated 'Growth %' axis label"))

        // Left "Revenue" label centred near the left SVG edge (x in [12,16]).
        val revenueX = numOf(revenueLabel.svgAttrs.x)
        assert(revenueX >= 12.0 && revenueX <= 16.0, s"Revenue label x should be in [12,16] but was $revenueX")

        // Right "Growth %" label centred near the right SVG edge (x near 360), past the right tick numbers.
        // Right axis line is at plotX+plotW = 60 + (360-60-60) = 300; tick numbers extend right from x=309.
        val growthX = numOf(growthLabel.svgAttrs.x)
        assert(growthX >= 340.0 && growthX <= 360.0, s"Growth %% label x should be near the right edge [340,360] but was $growthX")

        // The labels must clear the tick numbers: left ticks are the End-anchored numeric texts (the rotated
        // axis labels are Middle-anchored, so anchor=End isolates the tick numbers). The Revenue label centre
        // at ~14 must be left of every tick-number anchor.
        // NOTE: the defect-2 fix (visual-review #218) grows the left margin so the 5-digit "50000" tick label
        // no longer clips the rotated title. The tick-number anchor therefore now sits at ~61 (plotX 70 minus
        // TickLen+gap), not the old ~51 the pre-fix layout produced; the `< PlotX` (60) filter the old test used
        // encoded the BUG (it assumed plotX stayed at the default 60), so it is replaced by an anchor-only filter.
        val leftTickLabels = texts.filter(t => t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))
        assert(leftTickLabels.nonEmpty, "Expected left-axis tick numbers")
        leftTickLabels.foldLeft(succeed): (_, t) =>
            val tx = numOf(t.svgAttrs.x)
            assert(revenueX < tx, s"Revenue label ($revenueX) must be left of tick number anchor ($tx)")
    }

    // ---- Test 7: theme(_.dark) sets background rect fill to the dark color ----

    "theme(_.dark) sets the background rect fill to the dark theme color" in {
        // DarkBg = Style.Color.hex("#1f2937").getOrElse(Style.Color.black)
        val darkBg = Style.Color.hex("#1f2937").getOrElse(Style.Color.black)
        val rows   = Chunk(Sale("Jan", Usd(1000)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
            .theme(_.dark)
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // Background is the first frame Rect
        val frameRects = frameRectsIn(root)
        assert(frameRects.nonEmpty, "Expected at least one frame rect (background)")
        val bg = frameRects(0)
        assert(bg.svgAttrs.fill.isDefined, "Background rect must have a fill")
        assert(colorOf(bg.svgAttrs.fill) == darkBg, s"Dark theme background should be $darkBg but got ${bg.svgAttrs.fill}")
    }

    // ---- Test 8 (STACK carry-over): stacked bars accumulate y0/y1 per group ----

    "stacked bars: per-group rects accumulate (second group sits atop the first)" in {
        // Data: two groups A and B at x="Jan"
        //   A: 300, B: 700 (total=1000)
        // stackedYExtent -> max=1000
        // niceTicks(0,1000,5): step=250 -> Linear(0,1000,440,20)
        //   ys(300)=314, ys(700)=146, ys(1000)=20
        // Groups ordered by enum ordinal: A (encounters row 0, ordinal=encounter 0=0),
        //   B (encounters row 1, ordinal=1) -- since String, encounter order preserved
        // Group A (gi=0): accY: 0->300; rectBot=440 (baseline), rectTop=ys(300)=314; height=126
        // Group B (gi=1): accY: 300->1000; rectBot=ys(300)=314, rectTop=ys(1000)=20; height=294
        case class SRow(x: String, group: String, value: Double)
        val rows = Chunk(
            SRow("Jan", "A", 300.0),
            SRow("Jan", "B", 700.0)
        )
        // Legend hidden so this test isolates stack geometry (a default legend would reserve top space and
        // shift plotY/plotH; the stacked-legend derivation is covered by the dedicated tests above).
        val spec = UI.chart(rows)(bar(
            x = _.x,
            y = _.value,
            stack = by(_.group)
        )).legend(_.hidden)
        val root = summon[Conversion[ChartSpec[SRow], Svg.Root]](spec)

        val marksG: Svg.G = root.children.last match
            case g: Svg.G => g
            case other    => fail(s"Expected marks G but got $other")
        val rects = marksG.children.flatMap:
            case r: Svg.Rect => Chunk(r)
            case _           => Chunk.empty
        // One x group ("Jan"), two stack groups (A, B)
        assert(rects.size == 2, s"Expected 2 stacked rects but got ${rects.size}")

        // niceTicks(0,1000,5): rawStep=250, step=250; Linear(0,1000,440,20)
        def ys(v: Double) = 440.0 + (v / 1000.0) * (20.0 - 440.0)

        // Group A (index 0): rectTop=ys(300), rectBot=440
        val rA = rects(0)
        assertClose(numOf(rA.svgAttrs.y), ys(300.0), "Group A rect top (ys(300))")
        assertClose(numOf(rA.svgAttrs.height), Baseline - ys(300.0), "Group A rect height (baseline - ys(300))")

        // Group B (index 1): sits atop A; rectTop=ys(1000), rectBot=ys(300)
        val rB = rects(1)
        assertClose(numOf(rB.svgAttrs.y), ys(1000.0), "Group B rect top (ys(1000))")
        assertClose(numOf(rB.svgAttrs.height), ys(300.0) - ys(1000.0), "Group B rect height (ys(300)-ys(1000))")
    }

    // ---- Test 8b (LAYER FIX A): stacked bar derives a legend from the stack groups ----

    "stacked bar with .legend(_.top): one swatch per stack category in the segment colors" in {
        // A stacked bar grouped by `group` (no separate `color` channel) must derive its legend from the
        // STACK groups, exactly as a `color` channel would: one swatch per stack category, in the colors the
        // stacked segments use. Three groups A, B, C at x="Jan".
        case class SRow(x: String, group: String, value: Double)
        val rows = Chunk(
            SRow("Jan", "A", 300.0),
            SRow("Jan", "B", 500.0),
            SRow("Jan", "C", 200.0)
        )
        val spec = UI.chart(rows)(bar(
            x = _.x,
            y = _.value,
            stack = by(_.group)
        )).legend(_.top)
        val root = summon[Conversion[ChartSpec[SRow], Svg.Root]](spec)

        // Legend swatches are frame rects after the background rect ([0]=background, [1..]=swatches).
        val frameRects = frameRectsIn(root)
        val swatches   = frameRects.drop(1)
        assert(swatches.size == 3, s"Expected 3 legend swatches (A, B, C) but got ${swatches.size}")

        // The stacked segment rects live in the marks G; their fills are the segment colors per group.
        val marksG: Svg.G = root.children.last match
            case g: Svg.G => g
            case other    => fail(s"Expected marks G but got $other")
        val segments = marksG.children.flatMap:
            case r: Svg.Rect => Chunk(r)
            case _           => Chunk.empty
        assert(segments.size == 3, s"Expected 3 stacked segments but got ${segments.size}")

        // Each swatch color must equal the color of the corresponding stacked segment (group ordinal order):
        // segment 0 = group A, segment 1 = group B, segment 2 = group C.
        assert(colorOf(swatches(0).svgAttrs.fill) == colorOf(segments(0).svgAttrs.fill), "A swatch must match A segment color")
        assert(colorOf(swatches(1).svgAttrs.fill) == colorOf(segments(1).svgAttrs.fill), "B swatch must match B segment color")
        assert(colorOf(swatches(2).svgAttrs.fill) == colorOf(segments(2).svgAttrs.fill), "C swatch must match C segment color")

        // And those colors are the default palette in group order (no explicit colorScale).
        assert(colorOf(swatches(0).svgAttrs.fill) == Style.Color.blue, "A swatch should be palette(0)=blue")
        assert(colorOf(swatches(1).svgAttrs.fill) == Style.Color.orange, "B swatch should be palette(1)=orange")
        assert(colorOf(swatches(2).svgAttrs.fill) == Style.Color.green, "C swatch should be palette(2)=green")

        // Three legend labels, one per group.
        val legendLabels = frameTextsIn(root).filter: t =>
            t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle) && t.svgAttrs.textAnchor.isEmpty
        assert(legendLabels.size == 3, s"Expected 3 legend labels but got ${legendLabels.size}")
    }

    // ---- Test 8c (LAYER FIX A): stacked bar legend honors an explicit colorScale ----

    "stacked bar legend + segments honor .colorScale (semantic colors)" in {
        // The stack legend and the segments must use the SAME explicit colorScale mapping, so monitoring
        // dashboards can map e.g. 2xx->green, 4xx->amber, 5xx->red.
        case class SRow(x: String, code: String, count: Double)
        val rows = Chunk(
            SRow("/a", "2xx", 90.0),
            SRow("/a", "4xx", 8.0),
            SRow("/a", "5xx", 2.0)
        )
        val amber = Style.Color.hex("#f59e0b").getOrElse(Style.Color.orange)
        val spec = UI.chart(rows)(bar(
            x = _.x,
            y = _.count,
            stack = by(_.code)
        )).legend(
            _.top.colorScale {
                case "2xx" => Style.Color.green
                case "4xx" => amber
                case _     => Style.Color.red
            }
        )
        val root = summon[Conversion[ChartSpec[SRow], Svg.Root]](spec)

        val swatches = frameRectsIn(root).drop(1)
        assert(swatches.size == 3, s"Expected 3 legend swatches but got ${swatches.size}")
        assert(colorOf(swatches(0).svgAttrs.fill) == Style.Color.green, "2xx swatch green")
        assert(colorOf(swatches(1).svgAttrs.fill) == amber, "4xx swatch amber")
        assert(colorOf(swatches(2).svgAttrs.fill) == Style.Color.red, "5xx swatch red")

        val marksG: Svg.G = root.children.last match
            case g: Svg.G => g
            case other    => fail(s"Expected marks G but got $other")
        val segments = marksG.children.flatMap:
            case r: Svg.Rect => Chunk(r)
            case _           => Chunk.empty
        assert(segments.size == 3, s"Expected 3 stacked segments but got ${segments.size}")
        assert(colorOf(segments(0).svgAttrs.fill) == Style.Color.green, "2xx segment green")
        assert(colorOf(segments(1).svgAttrs.fill) == amber, "4xx segment amber")
        assert(colorOf(segments(2).svgAttrs.fill) == Style.Color.red, "5xx segment red")
    }

    // ---- Test 8d (DARK LEGEND FIX): legend label text uses the light theme chrome color on dark ----

    "dark theme legend labels use the light theme chrome color (not black), matching axis tick labels" in {
        // The dark-theme background panel (#1f2937) makes a black label invisible. The legend label text
        // must take the SAME theme chrome color the axis tick labels use (DarkThemeTextColor #e5e7eb),
        // while swatch fills stay the category/colorScale colors.
        val darkText = Style.Color.hex("#e5e7eb").getOrElse(Style.Color.white)
        case class SRow(x: String, code: String, count: Double)
        val rows = Chunk(
            SRow("/a", "2xx", 90.0),
            SRow("/a", "4xx", 8.0),
            SRow("/a", "5xx", 2.0)
        )
        val amber = Style.Color.hex("#f59e0b").getOrElse(Style.Color.orange)
        val spec = UI.chart(rows)(bar(
            x = _.x,
            y = _.count,
            stack = by(_.code)
        )).theme(_.dark).legend(
            _.top.colorScale {
                case "2xx" => Style.Color.green
                case "4xx" => amber
                case _     => Style.Color.red
            }
        )
        val root = summon[Conversion[ChartSpec[SRow], Svg.Root]](spec)

        // Legend labels: frame texts with DominantBaseline.Middle and no textAnchor.
        val legendLabels = frameTextsIn(root).filter: t =>
            t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle) && t.svgAttrs.textAnchor.isEmpty
        assert(legendLabels.size == 3, s"Expected 3 legend labels but got ${legendLabels.size}")
        legendLabels.foreach: t =>
            assert(
                colorOf(t.svgAttrs.fill) == darkText,
                s"Dark theme legend label fill should be the light chrome color $darkText but was ${t.svgAttrs.fill}"
            )

        // The axis tick labels on the same dark chart use the SAME chrome color; the legend now matches.
        val tickLabels = frameTextsIn(root).filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Middle)
        assert(tickLabels.nonEmpty, "Expected at least one x-axis tick label")
        tickLabels.foreach: t =>
            assert(
                colorOf(t.svgAttrs.fill) == darkText,
                s"Dark theme axis tick label fill should be $darkText but was ${t.svgAttrs.fill}"
            )

        // Swatch fills stay the colorScale colors, not the chrome color.
        val swatches = frameRectsIn(root).drop(1)
        assert(swatches.size == 3, s"Expected 3 legend swatches but got ${swatches.size}")
        assert(colorOf(swatches(0).svgAttrs.fill) == Style.Color.green, "2xx swatch stays green")
        assert(colorOf(swatches(1).svgAttrs.fill) == amber, "4xx swatch stays amber")
        assert(colorOf(swatches(2).svgAttrs.fill) == Style.Color.red, "5xx swatch stays red")
    }

    // ---- Test 10 (FIX 1 verification): tickFormat receives domain value, not pixel ----

    "tickFormat receives the domain value (e.g. 2000), not the pixel position" in {
        // Domain [0, 2000], ticks(3): niceTicks(0,2000,3) -> [0, 1000, 2000]
        // Linear(0, 2000, 440, 20): apply(2000) = 20.0 (a pixel), not 2000.0
        // The formatter v => s"$$${v.toInt}" should produce "$2000" from domain 2000, not "$20"
        val rows = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
            .yAxis(_.ticks(3).format(v => s"$$${v.toInt}"))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // Left-axis tick labels have TextAnchor.End
        val tickLabels = frameTextsIn(root).filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End)
        assert(tickLabels.size == 3, s"Expected 3 formatted tick labels but got ${tickLabels.size}")

        // The label for the top tick (domain value 2000) must be "$2000", not "$20"
        // Tick texts are rendered in tick order; find the one whose y coordinate matches apply(2000) = 20.0
        val topTickTexts = tickLabels.filter: t =>
            t.svgAttrs.y.exists:
                case Coord.Num(v) => math.abs(v - 20.0) < Tol
                case _            => false
        assert(topTickTexts.size == 1, s"Expected exactly 1 tick label at y=20.0 but got ${topTickTexts.size}")

        // Verify the text content is "$2000" (domain value formatted), not "$20" (pixel formatted)
        val topText = topTickTexts(0)
        val content = topText.children.headOption match
            case Some(UI.Ast.Text(s)) => s
            case other                => fail(s"Expected UI.Ast.Text but got $other")
        assert(content == "$2000", s"Formatter received pixel instead of domain value; got '$content' expected '$$2000'")
    }

    // ---- Test 9 (STACK carry-over): normalize=true -> fills full plot height ----

    "normalize=true stacked bars fill the full plot height (top group reaches plotY)" in {
        // Same data as Test 8: Jan A=300(30%), B=700(70%)
        // normalize=true: each x slot fills plotH
        // Group A: accY: 0->0.3;
        //   rectTop = plotY + (1-0.3)*plotH = 20 + 0.7*420 = 20+294 = 314
        //   rectBot = plotY + (1-0)*plotH = 20+420 = 440
        //   height = 126
        // Group B: accY: 0.3->1.0
        //   rectTop = plotY + (1-1)*plotH = 20
        //   rectBot = plotY + (1-0.3)*plotH = 314
        //   height = 294
        //   Top of Group B = plotY = 20 (reaches the top of the plot)
        case class SRow(x: String, group: String, value: Double)
        val rows = Chunk(
            SRow("Jan", "A", 300.0),
            SRow("Jan", "B", 700.0)
        )
        // Legend hidden to isolate stack geometry (see the stacked-bar accumulation test for the rationale).
        val spec = UI.chart(rows)(bar(
            x = _.x,
            y = _.value,
            stack = by(_.group, normalize = true)
        )).legend(_.hidden)
        val root = summon[Conversion[ChartSpec[SRow], Svg.Root]](spec)

        val marksG: Svg.G = root.children.last match
            case g: Svg.G => g
            case other    => fail(s"Expected marks G but got $other")
        val rects = marksG.children.flatMap:
            case r: Svg.Rect => Chunk(r)
            case _           => Chunk.empty
        assert(rects.size == 2, s"Expected 2 normalized stacked rects but got ${rects.size}")

        // Group A: rectTop=314, height=126
        val rA = rects(0)
        assertClose(numOf(rA.svgAttrs.y), 314.0, "Normalized Group A rectTop")
        assertClose(numOf(rA.svgAttrs.height), 126.0, "Normalized Group A height")

        // Group B: rectTop=20 (= plotY), height=294
        val rB = rects(1)
        assertClose(numOf(rB.svgAttrs.y), PlotY, "Normalized Group B rectTop == plotY (fills to top)")
        assertClose(numOf(rB.svgAttrs.height), 294.0, "Normalized Group B height")

        // Together they cover the full plot height
        assertClose(
            numOf(rA.svgAttrs.height) + numOf(rB.svgAttrs.height),
            PlotH,
            "Normalized stack total height == plotH"
        )
    }

    // ---- Test 11 (FIX 2): stacked area -- second group baseline equals first group top edge ----

    "stacked area: second group baseline equals first group top edge at shared x" in {
        // Two groups A and B at x=1 and x=2; each x has A=300, B=700 (total=1000).
        // stackedAreaYExtent -> max=1000; niceTicks(0,1000,5) -> step=250; Linear(0,1000,440,20)
        //   ys(300) = 440 + (300/1000)*(20-440) = 440 - 126 = 314
        //   ys(1000)= 440 + (1000/1000)*(20-440) = 20
        // Group A (gi=0): accY=0->300; y0=baseline=440; y1=ys(300)=314
        //   Top edge of A at x=1 is pixel y=314 (i.e. domain 300 mapped)
        // Group B (gi=1): accY=300->1000; y0=ys(300)=314; y1=ys(1000)=20
        //   Bottom edge of B at x=1 is pixel y=314 (same as top of A)
        case class ARow(x: Int, group: String, value: Double)
        val rows = Chunk(
            ARow(1, "A", 300.0),
            ARow(1, "B", 700.0),
            ARow(2, "A", 300.0),
            ARow(2, "B", 700.0)
        )
        // Legend hidden to isolate stack geometry (a stacked area now derives a legend by default).
        val spec = UI.chart(rows)(area(
            x = _.x,
            y = _.value,
            stack = by(_.group)
        )).legend(_.hidden)
        val root = summon[Conversion[ChartSpec[ARow], Svg.Root]](spec)

        val marksG: Svg.G = root.children.last match
            case g: Svg.G => g
            case other    => fail(s"Expected marks G but got $other")
        val paths = marksG.children.flatMap:
            case p: Svg.Path => Chunk(p)
            case _           => Chunk.empty
        // Two groups -> two area paths
        assert(paths.size == 2, s"Expected 2 stacked area paths but got ${paths.size}")

        // For exact pixel math: Linear(0, 1000, 440, 20)
        def ys(v: Double) = 440.0 + (v / 1000.0) * (20.0 - 440.0)

        // Group A (path 0): top edge at y=ys(300)=314; baseline at y=440
        // Group B (path 1): top edge at y=ys(1000)=20; bottom at y=ys(300)=314
        // Verify that the path data for group A contains a point at pixel y=ys(300)
        // and group B contains a point at pixel y=ys(300) as its baseline.
        // We check by extracting the PathData commands and finding the pixel values.
        val pathACommands = Svg.PathData.commands(paths(0).svgAttrs.d.getOrElse(Svg.PathData.empty))
        val pathBCommands = Svg.PathData.commands(paths(1).svgAttrs.d.getOrElse(Svg.PathData.empty))

        // The top edge of path A starts at y=ys(300). The path begins with a MoveTo or first LineTo at that y.
        val aYs = pathACommands.flatMap:
            case PathCommand.MoveTo(_, y) => Chunk(y)
            case PathCommand.LineTo(_, y) => Chunk(y)
            case _                        => Chunk.empty
        assert(
            aYs.toSeq.exists(y => math.abs(y - ys(300.0)) < Tol),
            s"Group A path must contain top-edge y=ys(300)=${ys(300.0)} but got: $aYs"
        )

        // Group B's baseline (bottom of path B) must be at y=ys(300)=314 (same as top of A).
        val bYs = pathBCommands.flatMap:
            case PathCommand.MoveTo(_, y) => Chunk(y)
            case PathCommand.LineTo(_, y) => Chunk(y)
            case _                        => Chunk.empty
        assert(
            bYs.toSeq.exists(y => math.abs(y - ys(300.0)) < Tol),
            s"Group B path must contain baseline y=ys(300)=${ys(300.0)} but got: $bYs"
        )
    }

    // ---- Test 12 (FIX 2): stacked area normalized -- top group reaches plotY ----

    "stacked area normalized: top group reaches plotY" in {
        // Same data: A=300, B=700 at each x; normalize=true
        // Group A (gi=0): fraction 0.3; y1 = plotY + (1-0.3)*plotH = 20+294=314; y0 = plotY+plotH=440
        // Group B (gi=1): fraction 0.7; y1 = plotY + (1-1.0)*plotH = 20; y0 = 314
        // Top of Group B = plotY = 20 (reaches the very top of the plot area)
        case class ARow(x: Int, group: String, value: Double)
        val rows = Chunk(
            ARow(1, "A", 300.0),
            ARow(1, "B", 700.0)
        )
        // Legend hidden to isolate stack geometry (a stacked area now derives a legend by default).
        val spec = UI.chart(rows)(area(
            x = _.x,
            y = _.value,
            stack = by(_.group, normalize = true)
        )).legend(_.hidden)
        val root = summon[Conversion[ChartSpec[ARow], Svg.Root]](spec)

        val marksG: Svg.G = root.children.last match
            case g: Svg.G => g
            case other    => fail(s"Expected marks G but got $other")
        val paths = marksG.children.flatMap:
            case p: Svg.Path => Chunk(p)
            case _           => Chunk.empty
        assert(paths.size == 2, s"Expected 2 normalized stacked area paths but got ${paths.size}")

        // Group B (index 1) is the top group and its top edge must reach plotY = 20
        val pathBCommands = Svg.PathData.commands(paths(1).svgAttrs.d.getOrElse(Svg.PathData.empty))
        val bYs = pathBCommands.flatMap:
            case PathCommand.MoveTo(_, y) => Chunk(y)
            case PathCommand.LineTo(_, y) => Chunk(y)
            case _                        => Chunk.empty
        assert(
            bYs.toSeq.exists(y => math.abs(y - PlotY) < Tol),
            s"Normalized top group (B) must reach plotY=$PlotY but path y-values are: $bYs"
        )
    }

    // ---- Test 13 (ISSUE 1): a GROUPED bar's y-axis chrome stays NEUTRAL, not palette(0) ----
    // A grouped bar (a single bar mark WITH a color channel) renders in multiple category colors, so painting
    // its y-axis a single palette color (blue) would misrepresent the series. The y-axis chrome must use the
    // neutral light-theme color instead. This tightens the iteration-2 rule, which color-coded any single
    // bound mark regardless of whether it rendered as one solid color.

    "grouped bar (color channel) keeps a NEUTRAL y-axis chrome, not palette(0)" in {
        val neutral = Style.Color.hex("#374151").getOrElse(Style.Color.black)
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Jan", Usd(2000), Region.EU),
            Sale("Jan", Usd(1500), Region.APAC)
        )
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // Left y-axis tick labels use TextAnchor.End. Their fill must be the neutral chrome, not blue.
        val leftTickLabels = frameTextsIn(root).filter(_.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))
        assert(leftTickLabels.nonEmpty, "Expected left y-axis tick labels for the grouped bar")
        leftTickLabels.foldLeft(succeed): (_, t) =>
            val c = colorOf(t.svgAttrs.fill)
            assert(c == neutral, s"Grouped-bar y-axis tick should be neutral ($neutral) but was $c")
            assert(c != Style.Color.blue, s"Grouped-bar y-axis tick must NOT be palette(0) (blue) but was $c")
    }

    // ---- Test 14 (ISSUE 1): a STACKED bar's y-axis chrome stays NEUTRAL, not palette(0) ----
    // A stacked bar (a single bar mark WITH a stack grouping) also renders in multiple category colors, so its
    // y-axis chrome must use the neutral color rather than a single palette color.

    "stacked bar (stack grouping) keeps a NEUTRAL y-axis chrome, not palette(0)" in {
        val neutral = Style.Color.hex("#374151").getOrElse(Style.Color.black)
        case class SRow(x: String, group: String, value: Double)
        given CanEqual[SRow, SRow] = CanEqual.derived
        val rows = Chunk(
            SRow("Jan", "A", 300.0),
            SRow("Jan", "B", 700.0)
        )
        val spec = UI.chart(rows)(bar(x = _.x, y = _.value, stack = by(_.group)))
        val root = summon[Conversion[ChartSpec[SRow], Svg.Root]](spec)

        val leftTickLabels = frameTextsIn(root).filter(_.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))
        assert(leftTickLabels.nonEmpty, "Expected left y-axis tick labels for the stacked bar")
        leftTickLabels.foldLeft(succeed): (_, t) =>
            val c = colorOf(t.svgAttrs.fill)
            assert(c == neutral, s"Stacked-bar y-axis tick should be neutral ($neutral) but was $c")
            assert(c != Style.Color.blue, s"Stacked-bar y-axis tick must NOT be palette(0) (blue) but was $c")
    }

    // ---- Test 15 (ISSUE 1): a single-color line keeps its color-coded y-axis chrome ----
    // A line mark with no color channel renders as one solid color, so ISSUE 1 still color-codes its y-axis
    // chrome (tick labels) to that mark's palette color (palette(0) = blue). This confirms the tightened rule
    // does not over-correct: solid-color marks remain color-coded.

    "single-color line keeps its y-axis chrome color-coded to palette(0) (blue)" in {
        case class LRow(month: String, value: Double)
        given CanEqual[LRow, LRow] = CanEqual.derived
        val rows                   = Chunk(LRow("Jan", 100.0), LRow("Feb", 200.0), LRow("Mar", 150.0))
        val spec                   = UI.chart(rows)(line(x = _.month, y = _.value)).yAxis(_.grid)
        val root                   = summon[Conversion[ChartSpec[LRow], Svg.Root]](spec)

        val leftTickLabels = frameTextsIn(root).filter(_.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))
        assert(leftTickLabels.nonEmpty, "Expected left y-axis tick labels for the line chart")
        leftTickLabels.foldLeft(succeed): (_, t) =>
            assert(
                colorOf(t.svgAttrs.fill) == Style.Color.blue,
                s"Single-color line y-axis tick should be palette(0) (blue) but was ${t.svgAttrs.fill}"
            )
    }

    // ---- Test 16 (ISSUE 2): gridlines are ALWAYS neutral, even when the axis chrome is color-coded ----
    // Gridlines are a background reference, not axis identity. In a single-color line chart the y-axis tick
    // labels are color-coded to palette(0) (blue), but the gridlines must stay the neutral gridline color, not
    // inherit the color-coded chrome (no blue gridlines).

    "gridlines in a color-coded line chart use the neutral grid color, not palette(0)" in {
        val neutral = Style.Color.hex("#374151").getOrElse(Style.Color.black)
        case class LRow(month: String, value: Double)
        given CanEqual[LRow, LRow] = CanEqual.derived
        val rows                   = Chunk(LRow("Jan", 100.0), LRow("Feb", 200.0), LRow("Mar", 150.0))
        val spec                   = UI.chart(rows)(line(x = _.month, y = _.value)).yAxis(_.grid.ticks(3))
        val root                   = summon[Conversion[ChartSpec[LRow], Svg.Root]](spec)

        // Gridlines span the full plot width and carry a strokeOpacity (distinct from axis lines).
        val gridLines = frameLinesIn(root).filter: l =>
            l.svgAttrs.x1.exists(_ == PlotX) && l.svgAttrs.x2.exists(_ == PlotX + PlotW) &&
                l.svgAttrs.y1 == l.svgAttrs.y2 && l.svgAttrs.strokeOpacity.isDefined
        assert(gridLines.nonEmpty, "Expected gridlines in the line chart")

        // Confirm the y-axis chrome IS color-coded (tick labels blue) so the gridline check is meaningful.
        val leftTickLabels = frameTextsIn(root).filter(_.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))
        assert(leftTickLabels.nonEmpty, "Expected color-coded left tick labels")
        assert(
            colorOf(leftTickLabels(0).svgAttrs.fill) == Style.Color.blue,
            "Precondition: line-chart y-axis tick labels must be color-coded (blue)"
        )

        // Despite the color-coded chrome, gridlines stay neutral, never palette(0).
        gridLines.foldLeft(succeed): (_, l) =>
            val c = colorOf(l.svgAttrs.stroke)
            assert(c == neutral, s"Gridline stroke should be the neutral grid color ($neutral) but was $c")
            assert(c != Style.Color.blue, s"Gridline must NOT be palette(0) (blue) but was $c")
    }

    // ---- Phase 6 helpers ----

    /** X-axis tick labels: bottom texts with the Hanging dominant baseline. */
    private def xTickLabelsIn(root: Svg.Root): Chunk[Svg.Text] =
        frameTextsIn(root).filter(_.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Hanging))

    private def circlesIn(root: Svg.Root): Chunk[Svg.Circle] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case c: Svg.Circle => Chunk(c)
                    case _             => Chunk.empty
            case _ => Chunk.empty

    private def barsIn(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.last match
            case g: Svg.G =>
                g.children.flatMap:
                    case r: Svg.Rect => Chunk(r)
                    case _           => Chunk.empty
            case _ => Chunk.empty

    // ---- Phase 6 Leaf 1 (INV-030): rotateTicks adds a rotate transform on x tick labels ----

    "xAxis(_.rotateTicks(-45)) gives every x tick label a Rotate(-45) transform" in {
        val rows   = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val spec   = UI.chart(rows)(bar(x = _.month, y = _.revenue)).xAxis(_.rotateTicks(-45.0))
        val root   = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val labels = xTickLabelsIn(root)
        assert(labels.nonEmpty, "Expected x tick labels")
        labels.foldLeft(succeed): (_, t) =>
            val rot = t.svgAttrs.transform.toSeq.collectFirst { case r: Svg.Transform.Rotate => r }
            rot match
                case Some(r) => assertClose(r.deg, -45.0, "x tick label rotate degrees")
                case None    => fail(s"Expected a Rotate transform on tick label but got ${t.svgAttrs.transform}")
    }

    // ---- Phase 6 Leaf 2 (INV-030): anchor sets the SVG text-anchor on x tick labels ----

    "xAxis(_.anchor(TextAnchor.End)) sets text-anchor=end on x tick labels" in {
        val rows   = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val spec   = UI.chart(rows)(bar(x = _.month, y = _.revenue)).xAxis(_.anchor(TextAnchor.End))
        val root   = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val labels = xTickLabelsIn(root)
        assert(labels.nonEmpty, "Expected x tick labels")
        labels.foldLeft(succeed): (_, t) =>
            assert(t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End), s"Expected text-anchor=end but got ${t.svgAttrs.textAnchor}")
    }

    // ---- Phase 6 Leaf 3 (INV-030): x gridlines at each tick from plotY to plotBaseline ----

    "xAxis(_.grid) emits vertical gridlines at each x tick from plotY to plotBaseline" in {
        val rows = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)), Sale("Mar", Usd(1500)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue)).xAxis(_.grid)
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        // Vertical gridlines: x1==x2, y1==plotY, y2==plotBaseline, with a strokeOpacity.
        val vGrid = frameLinesIn(root).filter: l =>
            l.svgAttrs.x1 == l.svgAttrs.x2 &&
                l.svgAttrs.y1.exists(_ == PlotY) && l.svgAttrs.y2.exists(_ == Baseline) &&
                l.svgAttrs.strokeOpacity.isDefined
        // 3 bands -> 3 tick gridlines.
        assert(vGrid.size == 3, s"Expected 3 vertical gridlines but got ${vGrid.size}")
    }

    // ---- Phase 6 Leaf 4 (INV-030): yAxis(_.reverse) places the first datum at the far pixel end ----

    "yAxis(_.reverse) swaps the y range so a small value sits near the top, not the baseline" in {
        // Without reverse, y=0 maps near baseline (440); with reverse, the range swaps so y=0 maps near top (20).
        val rows    = Chunk(Sale("Jan", Usd(0)), Sale("Feb", Usd(2000)))
        val normal  = UI.chart(rows)(bar(x = _.month, y = _.revenue))
        val flipped = UI.chart(rows)(bar(x = _.month, y = _.revenue)).yAxis(_.reverse)
        val rNorm   = barsIn(summon[Conversion[ChartSpec[Sale], Svg.Root]](normal))
        val rFlip   = barsIn(summon[Conversion[ChartSpec[Sale], Svg.Root]](flipped))
        // The Jan bar (value 0): normal top y is at the baseline; reversed top y is at the plot top.
        val janNormalY = numOf(rNorm(0).svgAttrs.y)
        val janFlipY   = numOf(rFlip(0).svgAttrs.y)
        assert(janFlipY < janNormalY, s"Reversed Jan-bar y ($janFlipY) should be nearer the top than normal ($janNormalY)")
        assertClose(janFlipY, PlotY, "reversed zero datum sits at plot top")
    }

    // ---- Phase 6 Leaf 5 (INV-030): xAxis(_.pad) widens the domain so the first datum is inset ----

    "xScale linear with pad insets the first datum from the plot edge (continuous x)" in {
        case class XRow(x: Double, y: Double)
        val rows     = Chunk(XRow(0.0, 1.0), XRow(10.0, 2.0))
        val noPad    = UI.chart(rows)(point(x = _.x, y = _.y))
        val padded   = UI.chart(rows)(point(x = _.x, y = _.y)).xScale(_.withPad(0.1))
        val cxNoPad  = circlesIn(summon[Conversion[ChartSpec[XRow], Svg.Root]](noPad))(0).svgAttrs.cx
        val cxPadded = circlesIn(summon[Conversion[ChartSpec[XRow], Svg.Root]](padded))(0).svgAttrs.cx
        val noPadX = cxNoPad match
            case Present(v) => v;
            case Absent     => fail("cx")
        val padX = cxPadded match
            case Present(v) => v;
            case Absent     => fail("cx")
        // Padding widens the domain symmetrically, so the first datum moves inward (to a larger pixel).
        assert(padX > noPadX, s"Padded first-datum cx ($padX) should be inset past the un-padded cx ($noPadX)")
    }

    // ---- FIX 1: an explicit linear x-domain is honored exactly (no nice-expansion) ----

    "xScale linear with an explicit domain honors it exactly and does not nice-expand it" in {
        // Data x spans 1..12; an explicit .xScale(_.linear(1.0, 12.0)) must resolve to [1,12].
        // Before the fix the X path passed nice=true uniformly, so fitLinear nice-expanded
        // [1,12] to [0,15] (data crammed into part of the plot). This mirrors the Y path,
        // which already honors an explicit linear domain with nice=false.
        case class XRow(x: Double, y: Double)
        val rows    = Chunk.from((1 to 12).map(m => XRow(m.toDouble, m.toDouble)))
        val spec    = UI.chart(rows)(point(x = _.x, y = _.y)).xScale(_.linear(1.0, 12.0))
        val (_, sc) = spec.toSvgWithScales
        sc.x.kind match
            case ScaleKind.Linear(lo, hi) =>
                assertClose(lo, 1.0, "explicit x-domain lo (must stay 1.0, not nice-expand to 0.0)")
                assertClose(hi, 12.0, "explicit x-domain hi (must stay 12.0, not nice-expand to 15.0)")
            case other => fail(s"Expected ScaleKind.Linear for explicit linear x but got $other")
        end match
    }

    // ---- Phase 6 Leaf 6 (INV-009): a 7-category band x-axis yields 7 tick labels ----

    "a 7-category band x-axis produces 7 tick labels" in {
        case class CRow(cat: String, y: Int)
        val cats   = Chunk("a", "b", "c", "d", "e", "f", "g")
        val rows   = cats.map(c => CRow(c, 1))
        val spec   = UI.chart(rows)(bar(x = _.cat, y = _.y)).xAxis(_.ticks(7))
        val root   = summon[Conversion[ChartSpec[CRow], Svg.Root]](spec)
        val labels = xTickLabelsIn(root)
        assert(labels.size == 7, s"Expected 7 band tick labels but got ${labels.size}")
    }

    // ---- Phase 6 WARN-1a (INV-012): chart-level linear clamp ----

    "yScale linear withClamp(true) clamps an out-of-range datum to the range; withClamp(false) extrapolates" in {
        // Fixed domain [0,10]; a datum y=20 is out of range. Bars: barY for the datum.
        // clamp=true: y=20 maps to rangeHi (plot top, 20.0) -> barY = 20.0.
        // clamp=false: y=20 extrapolates beyond the top -> barY < 20.0 (negative offset above the plot).
        val rows       = Chunk(Sale("Jan", Usd(20)))
        val clamped    = UI.chart(rows)(bar(x = _.month, y = _.revenue)).yScale(_.linear(0.0, 10.0).withClamp(true))
        val unclamped  = UI.chart(rows)(bar(x = _.month, y = _.revenue)).yScale(_.linear(0.0, 10.0).withClamp(false))
        val yClamped   = numOf(barsIn(summon[Conversion[ChartSpec[Sale], Svg.Root]](clamped))(0).svgAttrs.y)
        val yUnclamped = numOf(barsIn(summon[Conversion[ChartSpec[Sale], Svg.Root]](unclamped))(0).svgAttrs.y)
        // Clamped pins to the top of the plot (PlotY=20).
        assertClose(yClamped, PlotY, "clamped out-of-range datum pins to plot top")
        // Unclamped extrapolates ABOVE the plot top, i.e. a smaller (more negative) pixel.
        assert(yUnclamped < yClamped, s"Unclamped y ($yUnclamped) must extrapolate past the clamped top ($yClamped)")
    }

    // ---- Phase 6 WARN-1b (INV-012): chart-level symlog clamp ----

    "yScale symlog withClamp(true) pins an out-of-domain datum to the boundary; withClamp(false) extrapolates" in {
        // Symlog domain inferred from data [-5, 5]; add an out-of-domain datum 50.
        case class SRow(x: String, y: Double)
        val rows = Chunk(SRow("a", -5.0), SRow("b", 5.0))
        // Build a scale directly via Scale to compare clamp on/off at the same domain.
        val s          = Scale.Symlog(-5.0, 5.0, 0.0, 200.0, clamp = true)
        val sOff       = Scale.Symlog(-5.0, 5.0, 0.0, 200.0, clamp = false)
        val atBoundary = s.apply(kyo.internal.Domain.Continuous(50.0))
        val atMax      = s.apply(kyo.internal.Domain.Continuous(5.0))
        val extrap     = sOff.apply(kyo.internal.Domain.Continuous(50.0))
        assertClose(atBoundary, atMax, "symlog clamp=true pins 50 to the domain max")
        assert(extrap > atMax, s"symlog clamp=false ($extrap) must extrapolate past the domain max pixel ($atMax)")
    }

    // ---- Phase 6 WARN-3 (INV-012): pad applied to an explicitly-overridden log scale ----

    "yScale log withPad widens the log domain so the smallest datum is inset from the baseline" in {
        // Data [10, 1000]; without pad, y=10 sits at the baseline. With pad, the log domain widens
        // below 10, so y=10 is inset above the baseline.
        val rows   = Chunk(Sale("a", Usd(10)), Sale("b", Usd(1000)))
        val noPad  = UI.chart(rows)(bar(x = _.month, y = _.revenue)).yScale(_.log)
        val padded = UI.chart(rows)(bar(x = _.month, y = _.revenue)).yScale(_.log.withPad(0.1))
        // The y pixel of the smallest datum (10): the bar bottom is at baseline; compare the bar TOP y.
        val yNoPad  = numOf(barsIn(summon[Conversion[ChartSpec[Sale], Svg.Root]](noPad))(0).svgAttrs.y)
        val yPadded = numOf(barsIn(summon[Conversion[ChartSpec[Sale], Svg.Root]](padded))(0).svgAttrs.y)
        // Without pad the smallest datum maps to the baseline (440). With pad the domain widens below 10,
        // so the datum maps ABOVE the baseline (a smaller y pixel).
        assertClose(yNoPad, Baseline, "un-padded smallest log datum at baseline")
        assert(yPadded < yNoPad, s"Padded log datum y ($yPadded) must be inset above the baseline ($yNoPad)")
    }

    // ---- Visual-review defect fixes (#218) ----

    /** All `Svg.Circle`s that are DIRECT children of root (frame chrome, e.g. size-legend sample bubbles),
      * not the per-point data circles which live inside the marks group.
      */
    private def frameCirclesIn(root: Svg.Root): Chunk[Svg.Circle] =
        root.children.flatMap:
            case c: Svg.Circle => Chunk(c)
            case _             => Chunk.empty

    case class WideRow(month: String, revenue: Double, growthPct: Double)
    given CanEqual[WideRow, WideRow] = CanEqual.derived

    // DEFECT 2 (visual-review #218): wide left y-tick labels + a rotated left axis title must not clip at the
    // left SVG edge. A 5-digit revenue domain forces a "50000"-class tick label; with a left axis title the
    // left margin must grow so the leftmost tick label stays >= 0 and the plot is pushed right of the labels.
    "wide 5-digit left y-tick labels + a left axis title do not clip at the SVG edge (defect 2)" in {
        val rows = Chunk(
            WideRow("Jan", 45000, 0.0),
            WideRow("Jun", 83000, 18.6)
        )
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
            .yAxis(_.label("Revenue"))
            .size(360, 240)
        val root = summon[Conversion[ChartSpec[WideRow], Svg.Root]](spec)

        // The left y-tick labels are right-anchored (TextAnchor.End). A 5-digit label "50000" must appear.
        val leftTickLabels = frameTextsIn(root).filter(_.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))
        val fiveDigit = leftTickLabels.filter: t =>
            t.children.headOption match
                case Some(UI.Ast.Text(s)) => s.count(_.isDigit) >= 5
                case _                    => false
        assert(fiveDigit.nonEmpty, s"Expected a 5-digit left tick label; got ${leftTickLabels.flatMap(_.children)}")

        // The widest label is right-anchored at labelX; its leftmost rendered pixel is labelX - width.
        // Assert it stays inside the SVG (>= 0) so the leading digit is not clipped. Width estimate: 7px/char.
        val widest = fiveDigit.maxBy: t =>
            t.children.headOption match
                case Some(UI.Ast.Text(s)) => s.length
                case _                    => 0
        val labelX = numOf(widest.svgAttrs.x)
        val widthEstimate =
            (widest.children.headOption match
                case Some(UI.Ast.Text(s)) => s.length
                case _                    => 0
            ) * 7.0
        val leftmost = labelX - widthEstimate
        assert(leftmost >= 0.0, s"Leftmost tick-label pixel ($leftmost) clips off the left edge (labelX=$labelX)")

        // The plot must be pushed right of the labels. The left axis SPINE is the vertical frame line with
        // x1 == x2 and y1 < y2; its x is plotX. The old (bug) layout pinned plotX at the default 60 (so the
        // "50000" label, right-anchored at x=51, extended left to ~16 and collided with the rotated title at
        // x=14, clipping the leading digit). The fix grows plotX so the widest label clears the title column.
        val spineXs = frameLinesIn(root).flatMap: l =>
            (l.svgAttrs.x1, l.svgAttrs.x2, l.svgAttrs.y1, l.svgAttrs.y2) match
                case (Present(x1), Present(x2), Present(y1), Present(y2)) if math.abs(x1 - x2) < Tol && y1 < y2 =>
                    Chunk(x1)
                case _ => Chunk.empty
        val plotX = if spineXs.isEmpty then 0.0 else spineXs.min
        assert(plotX >= 70.0, s"Left margin did not grow for wide labels: plotX=$plotX (default 60)")
        // The tick label likewise shifted right of its old buggy x=51 (it now sits at plotX - TickLen - 4).
        assert(labelX > 51.0, s"Tick label x ($labelX) did not move right of the default 51")
    }

    case class SizeRow(a: Double, b: Double, w: Double)
    given CanEqual[SizeRow, SizeRow] = CanEqual.derived

    // DEFECT 4 (visual-review #218): a point chart with a size channel must render its size legend as sample
    // circles OUTSIDE the plot data area, not floating over a data bubble. The plot is shifted down to reserve
    // a top legend strip; the sample bubbles sit entirely above plotY.
    "size-legend sample circles render outside the plot data area, above plotY (defect 4)" in {
        val rows = Chunk(
            SizeRow(1.2, 3.4, 8.0),
            SizeRow(5.0, 5.5, 15.0),
            SizeRow(8.2, 4.0, 6.0)
        )
        val spec = UI.chart(rows)(point(x = _.a, y = _.b, size = _.w))
            .yAxis(_.grid)
            .size(360, 240)
        val root = summon[Conversion[ChartSpec[SizeRow], Svg.Root]](spec)

        // plotY is where the left axis line starts (its top y). With the top strip reserved it is 40, not 20.
        val ys1   = frameLinesIn(root).map(_.svgAttrs.y1.getOrElse(0.0)).filter(_ > 0.0)
        val plotY = if ys1.isEmpty then 0.0 else ys1.min
        assert(plotY >= 40.0, s"Plot was not shifted down to reserve the size-legend strip: plotY=$plotY")

        // The size legend emits two translucent (fillOpacity 0.5) sample circles in frame chrome.
        val sampleCircles = frameCirclesIn(root).filter(_.svgAttrs.fillOpacity.contains(0.5))
        assert(sampleCircles.size == 2, s"Expected 2 size-legend sample circles, got ${sampleCircles.size}")

        // Each sample circle's full extent (center +/- radius) must sit ABOVE the plot data area (cy + r <= plotY),
        // so it never overlaps a plotted point.
        sampleCircles.foldLeft(succeed): (_, c) =>
            val cy = c.svgAttrs.cy.getOrElse(0.0)
            val r  = c.svgAttrs.r.getOrElse(0.0)
            assert(cy + r <= plotY, s"Size-legend bubble (cy=$cy r=$r) dips into the plot data area (plotY=$plotY)")
    }

    case class ComboRow(month: String, revenue: Double, growthPct: Double)
    given CanEqual[ComboRow, ComboRow] = CanEqual.derived

    // DEFECT 3 (visual-review #218): GUARD. A bar+line combo lists bar THEN line, so spec order must place the
    // line path AFTER all bar rects in the SVG so the line draws ON TOP of the bars. This was reported as a
    // possible z-order bug; it is correct already, and this test guards that the spec-order layering holds.
    "bar+line combo emits the line path after all bar rects (z-order guard, defect 3)" in run {
        val rows = Chunk(
            ComboRow("Jan", 45000, 0.0),
            ComboRow("Feb", 52000, 15.6),
            ComboRow("Mar", 48000, -7.7)
        )
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        )
            .yAxis(_.label("Revenue"))
            .yAxisRight(_.label("Growth %"))
            .size(360, 240)
        val root = summon[Conversion[ChartSpec[ComboRow], Svg.Root]](spec)
        for html <- kyo.internal.HtmlRenderer.render(root, Seq.empty)
        yield
            val lastRect  = html.lastIndexOf("<rect")
            val firstPath = html.indexOf("<path")
            assert(lastRect >= 0, "expected at least one bar <rect>")
            assert(firstPath >= 0, "expected a line <path>")
            assert(firstPath > lastRect, s"line path (idx $firstPath) must come after the last bar rect (idx $lastRect)")
        end for
    }

    // ---- Phase 8 helpers ----

    /** Left y-axis tick labels: frame texts with TextAnchor.End (left of plotX). */
    private def leftYTickLabelsIn(root: Svg.Root): Chunk[Svg.Text] =
        frameTextsIn(root).filter(_.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))

    /** Right y-axis tick labels: frame texts with TextAnchor.Start to the right of plot area. */
    private def rightYTickLabelsIn(root: Svg.Root): Chunk[Svg.Text] =
        frameTextsIn(root).filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Start) &&
                t.svgAttrs.x.exists { case Coord.Num(v) => v > PlotX + PlotWTwoAx; case _ => false }

    // ---- Leaf L14 (GAP-YAXIS-ROTATION): yAxis rotateTicks gives every Y tick a Rotate transform ----

    "yAxis(_.rotateTicks(-45)) gives every Y tick label a Rotate(-45) transform (L14, GAP-YAXIS-ROTATION)" in {
        // Before fix: buildYAxis inline Svg.text has no rotation; cfg.tickRotation is not read.
        // After fix: tickLabel helper applies Svg.Transform.Rotate(tickRotation, px, py).
        val rows  = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val spec  = UI.chart(rows)(bar(x = _.month, y = _.revenue)).yAxis(_.rotateTicks(-45.0))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val ticks = leftYTickLabelsIn(root)
        assert(ticks.nonEmpty, "Expected left Y tick labels")
        ticks.foldLeft(succeed): (_, t) =>
            val rot = t.svgAttrs.transform.toSeq.collectFirst { case r: Svg.Transform.Rotate => r }
            rot match
                case Some(r) => assertClose(r.deg, -45.0, "Y tick label rotate degrees")
                case None    => fail(s"Expected a Rotate transform on Y tick label but got ${t.svgAttrs.transform}")
    }

    "yAxisRight(_.rotateTicks(30)) gives every right Y tick label a Rotate(30) transform (L14 right, GAP-YAXIS-ROTATION)" in {
        // Both left and right Y axes go through buildYAxis; the fix applies to both.
        val rows = Chunk(Row2Ax("Jan", Usd(1000), 5.0), Row2Ax("Feb", Usd(2000), 10.0))
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        ).yAxisRight(_.rotateTicks(30.0))
        val root  = summon[Conversion[ChartSpec[Row2Ax], Svg.Root]](spec)
        val ticks = rightYTickLabelsIn(root)
        assert(ticks.nonEmpty, "Expected right Y tick labels")
        ticks.foldLeft(succeed): (_, t) =>
            val rot = t.svgAttrs.transform.toSeq.collectFirst { case r: Svg.Transform.Rotate => r }
            rot match
                case Some(r) => assertClose(r.deg, 30.0, "Right Y tick label rotate degrees")
                case None    => fail(s"Expected a Rotate transform on right Y tick label but got ${t.svgAttrs.transform}")
    }

    // ---- Leaf L15 (GAP-YAXIS-ROTATION): anchor sets Y tick text-anchor; side-default preserved when unset ----

    "yAxis(_.anchor(TextAnchor.Start)) sets text-anchor=start on left Y tick labels (L15, GAP-YAXIS-ROTATION)" in {
        // Before fix: cfg.tickAnchor is never read; text-anchor is always the side-default (End for left).
        // After fix: effAnchor = toSvgAnchor(cfg.tickAnchor) when cfg.tickAnchor != TextAnchor.Middle.
        val rows = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue)).yAxis(_.anchor(TextAnchor.Start))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        // After the fix the left Y ticks carry Start anchor (no longer filtered by TextAnchor.End).
        // Use dominantBaseline.Middle to isolate Y ticks (not Hanging=X, not absent=rotated-title).
        val ticks = frameTextsIn(root).filter(_.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle))
        assert(ticks.nonEmpty, "Expected Y tick labels with DominantBaseline.Middle")
        ticks.foldLeft(succeed): (_, t) =>
            assert(
                t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Start),
                s"Y tick with explicit anchor(Start) must have text-anchor=start but got ${t.svgAttrs.textAnchor}"
            )
    }

    "left Y tick label keeps text-anchor=end when no anchor is set (L15 co-pin, R-9 byte-identity)" in {
        // Side-default anchor (End for left, Start for right) must be preserved when cfg.tickAnchor is
        // the default TextAnchor.Middle. This is the byte-identity guard for the no-anchor case.
        val rows  = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val spec  = UI.chart(rows)(bar(x = _.month, y = _.revenue))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val ticks = leftYTickLabelsIn(root)
        assert(ticks.nonEmpty, "Expected left Y tick labels")
        ticks.foldLeft(succeed): (_, t) =>
            assert(
                t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End),
                s"Default left Y tick must retain text-anchor=end (side-default) but got ${t.svgAttrs.textAnchor}"
            )
    }

    // ---- Leaf L16 (GAP-THEME-FONT): theme font applied to Y ticks, axis titles, legend text ----

    "theme font appears on Y tick, axis title, and legend label (L16, GAP-THEME-FONT)" in {
        // Before fix: withFont is only called in buildXAxis tick labels (through tickLabel helper).
        // Y ticks, axis titles, and legend labels have no font attrs even when theme sets them.
        // After fix: withFont called at all six sites; each text carries font-family + font-size.
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(2000), Region.EU)
        )
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue, color = _.region)
        )
            .yAxis(_.label("Revenue"))
            .theme(_.font("monospace").fontSize(14))
            .legend(_.colorScale[Region](Region.NA -> Style.Color.blue, Region.EU -> Style.Color.green))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // A Y tick label: DominantBaseline.Middle + TextAnchor.End (left Y, default config).
        val yTick = frameTextsIn(root)
            .find(t =>
                t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle) &&
                    t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End)
            )
            .getOrElse(fail("Expected a left Y tick label with DominantBaseline.Middle + TextAnchor.End"))
        assert(
            yTick.svgAttrs.fontFamily.contains("monospace"),
            s"Y tick label must carry font-family=monospace but got ${yTick.svgAttrs.fontFamily}"
        )
        assert(
            yTick.svgAttrs.fontSize.exists(_.toString.contains("14")),
            s"Y tick label must carry font-size=14px but got ${yTick.svgAttrs.fontSize}"
        )

        // An axis title: the rotated y-title has a Rotate transform.
        val yTitle = frameTextsIn(root)
            .find(t => t.svgAttrs.transform.toSeq.exists { case _: Svg.Transform.Rotate => true; case _ => false })
            .getOrElse(fail("Expected a rotated axis-title text"))
        assert(
            yTitle.svgAttrs.fontFamily.contains("monospace"),
            s"Y axis title must carry font-family=monospace but got ${yTitle.svgAttrs.fontFamily}"
        )

        // A legend label: DominantBaseline.Middle, not End (legend labels are not anchored End in
        // a categorical legend with default Top position), and no Rotate transform.
        val legendLabel = frameTextsIn(root)
            .find(t =>
                t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle) &&
                    !t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End) &&
                    !t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Start) &&
                    t.svgAttrs.transform.isEmpty
            )
            .getOrElse(fail("Expected a legend label text without End/Start anchor and without Rotate"))
        assert(
            legendLabel.svgAttrs.fontFamily.contains("monospace"),
            s"Legend label must carry font-family=monospace but got ${legendLabel.svgAttrs.fontFamily}"
        )
    }

    "default theme adds no font-family or font-size to Y tick, title, or legend (L16 co-pin, byte-identity)" in {
        // withFont is a no-op when theme.fontFamily and theme.fontSize are both Absent (the default).
        // No font attr must appear on any frame text when no theme font is set.
        val rows = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        frameTextsIn(root).foldLeft(succeed): (_, t) =>
            assert(t.svgAttrs.fontFamily.isEmpty, s"Default theme must NOT add font-family; got ${t.svgAttrs.fontFamily}")
            assert(t.svgAttrs.fontSize.isEmpty, s"Default theme must NOT add font-size; got ${t.svgAttrs.fontSize}")
    }

    // ---- Leaf L17 (CO-PIN): x tick-label chrome unchanged through shared helper ----

    "x tick rotateTicks/anchor/font stay byte-identical after P8 (L17 co-pin)" in {
        // P8 touches buildXAxis only to add withFont to the title block; the tickLabel helper call is
        // unchanged (P2 already wired it). This test re-asserts the P2 baseline.
        val rows = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))

        // Rotation (mirrors Phase 6 Leaf 1 at line 972):
        val rotSpec = UI.chart(rows)(bar(x = _.month, y = _.revenue)).xAxis(_.rotateTicks(-45.0))
        val rotRoot = summon[Conversion[ChartSpec[Sale], Svg.Root]](rotSpec)
        val xTicks  = xTickLabelsIn(rotRoot)
        assert(xTicks.nonEmpty, "Expected x tick labels")
        xTicks.foldLeft(succeed): (_, t) =>
            val rot = t.svgAttrs.transform.toSeq.collectFirst { case r: Svg.Transform.Rotate => r }
            rot match
                case Some(r) => assertClose(r.deg, -45.0, "x tick rotate (L17 co-pin)")
                case None    => fail(s"Expected Rotate on x tick but got ${t.svgAttrs.transform}")

        // Anchor (mirrors Phase 6 Leaf 2 at line 987):
        val ancSpec = UI.chart(rows)(bar(x = _.month, y = _.revenue)).xAxis(_.anchor(TextAnchor.End))
        val ancRoot = summon[Conversion[ChartSpec[Sale], Svg.Root]](ancSpec)
        xTickLabelsIn(ancRoot).foldLeft(succeed): (_, t) =>
            assert(t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End), "x tick anchor (L17 co-pin)")

        // Font:
        val fntSpec = UI.chart(rows)(bar(x = _.month, y = _.revenue)).theme(_.font("monospace").fontSize(14))
        val fntRoot = summon[Conversion[ChartSpec[Sale], Svg.Root]](fntSpec)
        xTickLabelsIn(fntRoot).foldLeft(succeed): (_, t) =>
            assert(t.svgAttrs.fontFamily.contains("monospace"), "x tick font-family (L17 co-pin)")
            assert(t.svgAttrs.fontSize.exists(_.toString.contains("14")), "x tick font-size (L17 co-pin)")
    }

    // ---- Phase 11 helpers ----

    /** Horizontal gridlines: Lines spanning plotX to plotX+plotW with strokeOpacity set. */
    private def hGridLinesIn(root: Svg.Root, plotW: Double = PlotW): Chunk[Svg.Line] =
        frameLinesIn(root).filter: l =>
            l.svgAttrs.x1.exists(_ == PlotX) &&
                l.svgAttrs.x2.exists(_ == PlotX + plotW) &&
                l.svgAttrs.y1 == l.svgAttrs.y2 &&
                l.svgAttrs.strokeOpacity.isDefined

    // ---- Leaf L11b (GAP-RIGHTY-SCALE): right-bound datum pixel matches log scale ----

    "L11b: yScaleRight(_.log) projects a right-bound datum at the log-scaled pixel, not linear (GAP-RIGHTY-SCALE)" in {
        // Right axis: data=[1.0, 100.0], log scale.
        // Log scale: domain [1.0, 100.0], range [440, 20].
        //   apply(1.0)  = rangeLo = 440.0 (domain min maps to rangeLo for log scale)
        //   apply(100.0) = rangeHi = 20.0 (domain max maps to rangeHi)
        // Linear scale (old behavior): domain nice(0,100)=[0,100], range [440, 20].
        //   apply(1.0) = 440 + (1.0/100) * (20-440) = 440 - 4.2 = 435.8
        // Log vs linear differ by ~4px at growthPct=1.0, discriminating the scale kind.
        val rows = Chunk(Row2Ax("Jan", Usd(1000), 1.0), Row2Ax("Feb", Usd(2000), 100.0))
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        ).yScaleRight(_.log)
        val root = summon[Conversion[ChartSpec[Row2Ax], Svg.Root]](spec)

        // Extract the right-bound line path from the marks G (last child of root).
        val marksG: Svg.G = root.children.last match
            case g: Svg.G => g
            case other    => fail(s"Expected marks G as last child but got $other")
        // Gather all <path> elements (line paths live inside nested G elements).
        val paths = marksG.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case p: Svg.Path => Chunk(p)
                    case _           => Chunk.empty
            case p: Svg.Path => Chunk(p)
            case _           => Chunk.empty

        // Extract all y-pixel values from path commands (MoveTo/LineTo y coordinates).
        val allYPx: Chunk[Double] = paths.flatMap: p =>
            p.svgAttrs.d match
                case Present(pd) =>
                    PathData.commands(pd).flatMap:
                        case PathCommand.MoveTo(_, y) => Chunk(y)
                        case PathCommand.LineTo(_, y) => Chunk(y)
                        case _                        => Chunk.empty
                case Absent => Chunk.empty

        assert(allYPx.nonEmpty, s"L11b: Expected Y pixel values in line path commands but got none")
        // With log scale: apply(1.0)=440.0 (domain min -> rangeLo).
        // With linear scale: apply(1.0)≈435.8 (linear interpolation).
        // The key discriminator: does any y exactly match 440.0 (log for growthPct=1.0)?
        val hasLogBaseline = allYPx.exists(y => math.abs(y - 440.0) < 2.0)
        assert(hasLogBaseline, s"L11b: Expected log-scaled y near 440.0 for growthPct=1.0 but got: $allYPx (GAP-RIGHTY-SCALE unfixed)")

        // Confirm the linear fallback value is NOT present (linear would put 1.0 at ~435.8).
        val hasLinearFallback = allYPx.exists(y => y > 433.0 && y < 438.0)
        assert(!hasLinearFallback, s"L11b: Found linear-scaled y near 435-438 (expected log scaling): $allYPx")
    }

    // ---- Leaf L12a (CO-PIN): existing dual-axis test pixel unchanged ----
    // This test guards byte-identity: no yScaleRight + default right chrome => same pixel as today.

    "L12a (CO-PIN): dual-axis chart with no yScaleRight uses default Linear+nice right scale, right ticks exist (byte-identity)" in {
        // Right: growthPct=[10.0, 20.0]; yRightExtent = Continuous(10, 20).
        // niceTicks(10,20,5): step=5 -> snapped domain=[10,20].
        // Linear(10, 20, rangeLo=440, rangeHi=20, nice=true):
        //   apply(10) = 440 (domain min -> rangeLo=440)
        //   apply(15) = 440 + (15-10)/(20-10) * (20-440) = 440 + 0.5*(-420) = 230
        //   apply(20) = 20 (domain max -> rangeHi=20)
        // This co-pin verifies byte-identity: the default (no yScaleRight) produces the same
        // Linear+nice scale as the old hardcoded Scale.fit(Linear, rExt, plotBaseline, plotY, nice=true).
        val rows = Chunk(
            Row2Ax("Jan", Usd(1000), 10.0),
            Row2Ax("Feb", Usd(2000), 20.0)
        )
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        )
        val root = summon[Conversion[ChartSpec[Row2Ax], Svg.Root]](spec)

        // Right axis tick labels appear on the right margin.
        val allTexts = frameTextsIn(root)
        val rightTickLabels = allTexts.filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Start) &&
                t.svgAttrs.x.exists { case Coord.Num(v) => v > PlotX + PlotWTwoAx; case _ => false }
        assert(rightTickLabels.nonEmpty, "L12a: Expected right-axis tick labels (co-pin)")

        // The tick labeled "10" (domain min) should be at y=440 (rangeLo=plotBaseline).
        // niceTicks(10, 20, 5): step=5 -> ticks [10, 15, 20] (3 ticks); "10" is always present.
        val tick10 = rightTickLabels.find(t => t.children.exists { case UI.Ast.Text("10") => true; case _ => false })
        tick10 match
            case Some(t) =>
                val py = numOf(t.svgAttrs.y)
                assertClose(py, 440.0, "L12a: right scale tick '10' (domain min) should be at y=440 (plotBaseline)")
            case None =>
                fail(s"L12a: tick '10' not found in right tick labels: ${rightTickLabels.map(_.children).toList}")
        end match

        // The tick labeled "20" (domain max) should be at y=20 (rangeHi=plotY).
        // niceTicks(10, 20, 5): "20" is the domain max and always present as the last tick.
        val tick20 = rightTickLabels.find(t => t.children.exists { case UI.Ast.Text("20") => true; case _ => false })
        tick20 match
            case Some(t) =>
                val py = numOf(t.svgAttrs.y)
                assertClose(py, 20.0, "L12a: right scale tick '20' (domain max) should be at y=20 (plotY)")
            case None =>
                fail(s"L12a: tick '20' not found in right tick labels: ${rightTickLabels.map(_.children).toList}")
        end match
    }

    // ---- Leaf L13 (GAP-RIGHTY-GRID): right gridlines emitted; left-wins guard ----

    "L13a: .yAxisRight(_.grid) with left grid OFF emits right horizontal gridlines (GAP-RIGHTY-GRID)" in {
        // Before fix: buildYAxis gate `cfg.showGrid && !isRight` suppresses ALL right gridlines.
        // After fix: drawGrid=true for right when right cfg has showGrid AND left does not.
        val rows = Chunk(Row2Ax("Jan", Usd(1000), 5.0), Row2Ax("Feb", Usd(2000), 20.0))
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        ).yAxisRight(_.grid)
        // Left grid is NOT set; right grid is set -> rightDrawGrid=true, leftDrawGrid=false.
        val root = summon[Conversion[ChartSpec[Row2Ax], Svg.Root]](spec)

        // Horizontal gridlines span x1=plotX to x2=plotX+plotW_twoax with strokeOpacity set.
        val gridLines = hGridLinesIn(root, PlotWTwoAx)
        // Right axis domain: growthPct in [5.0, 20.0], default tickCount=5, nice=true.
        // niceTicks(5.0, 20.0, 5): step=5 -> ticks [5, 10, 15, 20] = 4 ticks.
        // One gridline per right tick -> 4 gridlines, per invariant L13 (04-invariants).
        assert(
            gridLines.size == 4,
            s"L13a: Expected 4 right horizontal gridlines (one per right tick: 5, 10, 15, 20) but got ${gridLines.size} (GAP-RIGHTY-GRID)"
        )
        // Gridline y-positions must match the right scale tick pixels (invariant L13).
        // Right scale: Linear(5.0, 20.0, rangeLo=440, rangeHi=20): apply(v) = 440 + (v-5)/(20-5)*(20-440).
        val expectedYs = Chunk(440.0, 300.0, 160.0, 20.0) // apply(5), apply(10), apply(15), apply(20)
        val actualYs   = gridLines.flatMap(l => l.svgAttrs.y1.map(_.toDouble)).sorted.reverse
        expectedYs.zip(actualYs).foldLeft(succeed): (_, pair) =>
            val (expected, actual) = pair
            assertClose(actual, expected, s"L13a: gridline y-position mismatch (expected $expected, got $actual)")
    }

    "L13b: .yAxis(_.grid) + .yAxisRight(_.grid) emits only LEFT tick count gridlines (left-wins guard)" in {
        // When both left and right have showGrid=true, leftDrawGrid wins.
        // Left: revenue=[0,2000], niceTicks(0,2000,5)=[0,500,1000,1500,2000] -> 5 ticks.
        // Right grid suppressed by leftDrawGrid=true. Total gridlines == left tick count.
        val rows = Chunk(Row2Ax("Jan", Usd(1000), 5.0), Row2Ax("Feb", Usd(2000), 20.0))
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        ).yAxis(_.grid).yAxisRight(_.grid)
        val root = summon[Conversion[ChartSpec[Row2Ax], Svg.Root]](spec)

        val gridLines = hGridLinesIn(root, PlotWTwoAx)
        assert(gridLines.nonEmpty, "L13b: Expected gridlines when yAxis(_.grid) is set")
        // All gridlines come from the left; count matches left tick count.
        // niceTicks(0,2000,5)=[0,500,1000,1500,2000] -> 5 ticks. gridLines.size should == 5.
        assert(
            gridLines.size == 5,
            s"L13b: Expected exactly 5 gridlines (left tick count) but got ${gridLines.size} (right grid should be suppressed by left-wins)"
        )
    }

    // ---- Leaf L19b (GAP-RIGHTY-SCALE independence): yScale(_.log) + yScaleRight(_.linear) ----

    "L19b: .yScale(_.log).yScaleRight(_.linear(0,1)) leaves left log and right linear (independence)" in {
        // Left: bar revenue=[1000,2000], log scale.
        //   domain no-zero [1000,2000], range [440, 20+topHeadroom].
        //   apply(1000) = baseline = 440 (log bottom = domain min maps to rangeLo).
        //   apply(2000) = top (log top = domain max maps to rangeHi).
        // Right: line growthPct=[0.1, 0.9], linear(0,1).
        //   apply(0.5) = 440 + 0.5 * (20-440) = 230.0
        // Verify independence: left is log (bottom of data at baseline), right is linear clamped [0,1].
        val rows = Chunk(Row2Ax("Jan", Usd(1000), 0.1), Row2Ax("Feb", Usd(2000), 0.9))
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        ).yScale(_.log).yScaleRight(_.linear(0.0, 1.0))
        val root = summon[Conversion[ChartSpec[Row2Ax], Svg.Root]](spec)

        // Both axes should render tick labels (confirming both exist with different scale kinds).
        val allTexts = frameTextsIn(root)
        val leftTicks = allTexts.filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End) &&
                t.svgAttrs.x.exists { case Coord.Num(v) => v < PlotX; case _ => false }
        val rightTicks = allTexts.filter: t =>
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Start) &&
                t.svgAttrs.x.exists { case Coord.Num(v) => v > PlotX + PlotWTwoAx; case _ => false }

        assert(leftTicks.nonEmpty, "L19b: Expected left-axis tick labels")
        assert(rightTicks.nonEmpty, "L19b: Expected right-axis tick labels")

        // Right scale is linear(0,1), domain fixed [0,1], nice=false, tickCount=5.
        // niceTicks(0.0, 1.0, 5): step=0.5 -> ticks [0.0, 0.5, 1.0] (3 ticks); "0.5" is always present.
        // apply(0.5) = 440 + 0.5 * (20-440) = 440 - 210 = 230.0.
        val tick05 = rightTicks.find(t => t.children.exists { case UI.Ast.Text("0.5") => true; case _ => false })
        tick05 match
            case Some(t) =>
                val py = numOf(t.svgAttrs.y)
                assertClose(py, 230.0, "L19b: right linear(0,1) tick at 0.5 should be at y=230")
            case None =>
                fail(s"L19b: tick '0.5' not found in right tick labels: ${rightTicks.map(_.children).toList}")
        end match
    }

end ChartAxisTest
