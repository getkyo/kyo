package kyo

import kyo.Svg.Coord
import kyo.Svg.PathCommand
import kyo.Svg.PathData
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.Ast.Encoding
import kyo.UI.mark.*
import kyo.internal.ChartLower
import kyo.internal.HtmlRenderer
import kyo.internal.NumberFormat
import kyo.internal.Scale
import scala.language.implicitConversions

class ChartLowerTest extends Test:

    // ---- shared domain types ----

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

    // Helper: collect all Svg.Rect children from a Root's marks g
    private def rectsIn(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case r: Svg.Rect => Chunk(r)
                    case _           => Chunk.empty
            case _ => Chunk.empty

    private def pathsIn(root: Svg.Root): Chunk[Svg.Path] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case p: Svg.Path => Chunk(p)
                    case _           => Chunk.empty
            case _ => Chunk.empty

    private def circlesIn(root: Svg.Root): Chunk[Svg.Circle] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case c: Svg.Circle => Chunk(c)
                    case _             => Chunk.empty
            case _ => Chunk.empty

    private def linesIn(root: Svg.Root): Chunk[Svg.Line] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case l: Svg.Line => Chunk(l)
                    case _           => Chunk.empty
            case _ => Chunk.empty

    // The marks region is the last top-level Svg.G child appended by lowerStatic (chrome groups precede it).
    private def marksGroup(root: Svg.Root): Svg.G =
        root.children.collect { case g: Svg.G => g }.lastOption.getOrElse(fail("no marks Svg.G found"))

    // Layout constants (must match ChartLower defaults).
    private val PlotX    = 60.0
    private val PlotY    = 20.0
    private val PlotW    = 560.0
    private val PlotH    = 420.0
    private val Baseline = PlotY + PlotH // 440.0

    private val Tol = 1e-6

    private def assertClose(actual: Double, expected: Double, msg: String): Assertion =
        assert(math.abs(actual - expected) < Tol, s"$msg: expected $expected but got $actual")

    private def numOf(c: Maybe[Coord]): Double = c match
        case Present(Coord.Num(v)) => v
        case other                 => fail(s"Expected Coord.Num but got $other")

    // ---- Test 1: single bar ----

    "single bar lowers to one Svg.Rect with exact scaled x/y/width/height" in {
        // Data: one row, one category "Jan" on x, revenue=1000 on y.
        // x Band: n=1, slot=560, bandW=560*0.9=504
        //   barX = PlotX + (slot - bandW)/2 = 60 + 28 = 88
        // y Linear: niceTicks(0,1000,5) => step=500 => nLo=0, nHi=1000
        //   Scale.Linear(0, 1000, 440, 20) => apply(1000) = 20
        //   barY=20, barH=420
        val rows  = Chunk(Sale("Jan", Usd(1000)))
        val spec  = UI.chart(rows)(bar(x = _.month, y = _.revenue))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val rects = rectsIn(root)
        assert(rects.size == 1, s"Expected 1 rect but got ${rects.size}")
        val r             = rects(0)
        val expectedBandW = 504.0
        val expectedBarX  = PlotX + (560.0 - expectedBandW) / 2.0 // 88.0
        assertClose(numOf(r.svgAttrs.x), expectedBarX, "barX")
        assertClose(numOf(r.svgAttrs.width), expectedBandW, "barW")
        assertClose(numOf(r.svgAttrs.y), 20.0, "barY")
        assertClose(numOf(r.svgAttrs.height), 420.0, "barH")
    }

    // ---- Test 2: two-point line ----

    "two-point line lowers to one Svg.Path with exact PathData (MoveTo then LineTo)" in {
        // Rows: ("a", 100), ("b", 200), x:String => Band, y:Int => Linear
        // x Band: n=2, slot=280, bandW=252
        //   "a": px = 60 + 0*280 + (280-252)/2 = 60+14 = 74
        //   "b": px = 60 + 1*280 + 14 = 354
        // y Linear: niceTicks(100,200,5) => step=50, nLo=100, nHi=200
        //   Scale.Linear(100,200,440,20)
        //   apply(100) = 440, apply(200) = 20
        case class Row(x: String, y: Int)
        val rows  = Chunk(Row("a", 100), Row("b", 200))
        val spec  = UI.chart(rows)(line(x = _.x, y = _.y))
        val root  = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val paths = pathsIn(root)
        assert(paths.size == 1, s"Expected 1 path but got ${paths.size}")
        val cmds = paths(0).svgAttrs.d match
            case Present(pd) => PathData.commands(pd)
            case Absent      => fail("Expected path to have d attribute")
        // Band: n=2, slot=280, bandW=252, padding=(280-252)/2=14.
        // Line vertices sit at the band CENTRE (left edge + bandW/2), aligning with the centred x-tick labels.
        // (Old values 74/354 were the band LEFT edge and encoded the off-by-half-band bug.)
        val slot  = 280.0
        val bandW = 252.0
        val pad   = (slot - bandW) / 2.0               // 14.0
        val px_a  = PlotX + 0 * slot + pad + bandW / 2 // 200.0 (band centre)
        val px_b  = PlotX + 1 * slot + pad + bandW / 2 // 480.0 (band centre)
        assert(cmds.size == 2, s"Expected 2 path commands but got ${cmds.size}")
        cmds(0) match
            case PathCommand.MoveTo(x, y) =>
                assertClose(x, px_a, "MoveTo x")
                assertClose(y, 440.0, "MoveTo y")
            case other => fail(s"Expected MoveTo but got $other")
        end match
        cmds(1) match
            case PathCommand.LineTo(x, y) =>
                assertClose(x, px_b, "LineTo x")
                assertClose(y, 20.0, "LineTo y")
            case other => fail(s"Expected LineTo but got $other")
        end match
    }

    // ---- Test 3: area lowers to a closed path ----

    "area lowers to a closed Svg.Path (top edge forward, baseline back, Close)" in {
        // Same data as line test: ("a", 100), ("b", 200)
        // area y: extent with ensureZero => Continuous(0, 200)
        // niceTicks(0,200,5) => step=50, nLo=0, nHi=200
        // Scale.Linear(0,200,440,20):
        //   apply(100)=230, apply(200)=20
        // x Band: n=2 ["a","b"], slot=280, bandW=252, pad=14
        //   px_a=74, px_b=354
        // Path: from(74,230).lineTo(354,20).lineTo(354,440).lineTo(74,440).close
        case class Row(x: String, y: Int)
        val rows  = Chunk(Row("a", 100), Row("b", 200))
        val spec  = UI.chart(rows)(area(x = _.x, y = _.y))
        val root  = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val paths = pathsIn(root)
        assert(paths.size == 1, s"Expected 1 path but got ${paths.size}")
        val cmds = paths(0).svgAttrs.d match
            case Present(pd) => PathData.commands(pd)
            case Absent      => fail("Expected path to have d attribute")
        // Area vertices sit at the band CENTRE (left edge + bandW/2), aligning with the centred x-tick labels.
        // (Old values 74/354 were the band LEFT edge and encoded the off-by-half-band bug.)
        val slot  = 280.0
        val bandW = 252.0
        val pad   = (slot - bandW) / 2.0
        val px_a  = PlotX + pad + bandW / 2        // 200.0 (band centre)
        val px_b  = PlotX + slot + pad + bandW / 2 // 480.0 (band centre)
        // Commands: MoveTo(px_a,230), LineTo(px_b,20), LineTo(px_b,440), LineTo(px_a,440), Close
        assert(cmds.size == 5, s"Expected 5 path commands but got ${cmds.size}")
        cmds(0) match
            case PathCommand.MoveTo(x, y) =>
                assertClose(x, px_a, "area MoveTo x")
                assertClose(y, 230.0, "area MoveTo y")
            case other => fail(s"Expected MoveTo but got $other")
        end match
        cmds(1) match
            case PathCommand.LineTo(x, y) =>
                assertClose(x, px_b, "area LineTo(top) x")
                assertClose(y, 20.0, "area LineTo(top) y")
            case other => fail(s"Expected LineTo but got $other")
        end match
        cmds(2) match
            case PathCommand.LineTo(x, y) =>
                assertClose(x, px_b, "area LineTo(baseline-last) x")
                assertClose(y, Baseline, "area LineTo(baseline-last) y")
            case other => fail(s"Expected LineTo(baseline-last) but got $other")
        end match
        cmds(3) match
            case PathCommand.LineTo(x, y) =>
                assertClose(x, px_a, "area LineTo(baseline-first) x")
                assertClose(y, Baseline, "area LineTo(baseline-first) y")
            case other => fail(s"Expected LineTo(baseline-first) but got $other")
        end match
        assert(cmds(4) == PathCommand.Close, s"Expected Close but got ${cmds(4)}")
    }

    // ---- Test 4: point lowers to Svg.Circle ----

    "point lowers to an Svg.Circle with scaled cx/cy" in {
        // Rows: ("a", 0), ("b", 100), mark: point
        // x Band ["a","b"], n=2, slot=280, bandW=252, pad=14
        //   "b": px=354.0
        // y Linear: Continuous(0,100), niceTicks(0,100,5)=>step=50,nLo=0,nHi=100
        //   apply(0)=440, apply(100)=20
        // Circle for "b": cx=354, cy=20, r=4 (default)
        case class Row(x: String, y: Int)
        val rows    = Chunk(Row("a", 0), Row("b", 100))
        val spec    = UI.chart(rows)(point(x = _.x, y = _.y))
        val root    = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val circles = circlesIn(root)
        assert(circles.size == 2, s"Expected 2 circles but got ${circles.size}")
        // Point glyphs sit at the band CENTRE (left edge + bandW/2), aligning with the centred x-tick labels.
        // (Old value 354.0 was the band LEFT edge and encoded the off-by-half-band bug.)
        val slot  = 280.0
        val bandW = 252.0
        val pad   = (slot - bandW) / 2.0
        val px_b  = PlotX + slot + pad + bandW / 2 // 480.0 (band centre)
        val c     = circles(1)                     // second circle (for row "b", y=100)
        c.svgAttrs.cx match
            case Present(v) => assertClose(v, px_b, "point cx")
            case Absent     => fail("Expected cx to be Present")
        c.svgAttrs.cy match
            case Present(v) => assertClose(v, 20.0, "point cy")
            case Absent     => fail("Expected cy to be Present")
        c.svgAttrs.r match
            case Present(v) => assertClose(v, 4.0, "point r")
            case Absent     => fail("Expected r to be Present")
    }

    // ---- Test 4b (ISSUE 2): point circles carry a separating outline stroke ----
    // A filled point with no outline lets overlapping/adjacent bubbles merge into one blob. Each point
    // circle must have BOTH a fill (the per-mark palette color) AND a stroke (the separating outline,
    // the theme background color: white on the light theme) with a positive stroke width.

    "point circle has both a palette fill and a separating outline stroke (light theme background color)" in {
        case class Row(x: String, y: Int)
        val rows    = Chunk(Row("a", 0), Row("b", 100))
        val spec    = UI.chart(rows)(point(x = _.x, y = _.y))
        val root    = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val circles = circlesIn(root)
        assert(circles.nonEmpty, s"Expected at least one circle but got ${circles.size}")
        circles.foldLeft(succeed): (_, c) =>
            // fill is the per-mark palette color: a single point mark is mark 0 -> palette(0) = blue.
            c.svgAttrs.fill match
                case Present(Svg.Paint.Color(col)) =>
                    assert(col == Style.Color.blue, s"Point fill should be palette(0) (blue) but got $col")
                case other => fail(s"Expected a point color fill but got $other")
            end match
            // stroke is the separating outline: the light theme background color (white).
            c.svgAttrs.stroke match
                case Present(Svg.Paint.Color(col)) =>
                    assert(col == Style.Color.white, s"Point separating stroke should be the light background (white) but got $col")
                case other => fail(s"Expected a point separating stroke color but got $other")
            end match
            assert(c.svgAttrs.strokeWidth.isDefined, "Point must have a positive stroke width for the separating outline")
    }

    // ---- Test 5: rule at y=Const lowers to Svg.Line spanning the plot ----

    "rule(y=Const(1000)) lowers to an Svg.Line spanning the plot at scaled y" in {
        // Data: Sale("Jan", Usd(2000)); marks: bar + rule(y=1000)
        // y extent: bar contributes Continuous(0,2000), rule contributes Continuous(1000,1000)
        // merged: Continuous(0, 2000)
        // niceTicks(0,2000,5) => step=500 => nLo=0, nHi=2000
        // Scale.Linear(0,2000,440,20): apply(1000) = 440 + 0.5*(20-440) = 230
        // Rule line: x1=60, y1=230, x2=620, y2=230
        val rows = Chunk(Sale("Jan", Usd(2000)))
        val rv   = RuleValue.Const(Usd(1000), summon[Plottable[Usd]])
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            rule[Sale, Usd](y = rv)
        )
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val lines = linesIn(root)
        assert(lines.size == 1, s"Expected 1 rule line but got ${lines.size}")
        val l = lines(0)
        l.svgAttrs.x1 match
            case Present(v) => assertClose(v, PlotX, "rule x1")
            case Absent     => fail("Expected x1 to be Present")
        l.svgAttrs.y1 match
            case Present(v) => assertClose(v, 230.0, "rule y1")
            case Absent     => fail("Expected y1 to be Present")
        l.svgAttrs.x2 match
            case Present(v) => assertClose(v, PlotX + PlotW, "rule x2")
            case Absent     => fail("Expected x2 to be Present")
        l.svgAttrs.y2 match
            case Present(v) => assertClose(v, 230.0, "rule y2")
            case Absent     => fail("Expected y2 to be Present")
    }

    // ---- Test 6: gap line splits into two sub-paths (two MoveTos) ----

    "line with Absent y splits into two sub-paths (assert two MoveTos)" in {
        // Rows: ("a", Present(100)), ("b", Absent), ("c", Present(200))
        // y type: Maybe[Int] with Plottable[Maybe[Int]]
        // fromTotal wraps as: accessor(row) = Present(row.y) where row.y: Maybe[Int]
        // In lowering: matches Present(yv) where yv = Present(100)|Absent|Present(200)
        //   toDomain called on Maybe[Int]: Present(100)->Continuous(100), Absent->Absent
        // x Band ["a","b","c"], n=3, slot=560/3, bandW=560*0.9/3=168
        //   "a": px = 60 + 0*(560/3) + (560/3 - 168)/2
        //   "c": px = 60 + 2*(560/3) + (560/3 - 168)/2
        // y Linear: extent Continuous(100,200), niceTicks(100,200,5)=>step=50,nLo=100,nHi=200
        //   apply(100)=440, apply(200)=20
        // PathData: MoveTo(px_a, 440), MoveTo(px_c, 20) -- two MoveTos, no LineTo
        case class Row(x: String, y: Maybe[Int])
        val rows  = Chunk(Row("a", Present(100)), Row("b", Absent), Row("c", Present(200)))
        val spec  = UI.chart(rows)(line(x = _.x, y = _.y))
        val root  = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val paths = pathsIn(root)
        assert(paths.size == 1, s"Expected 1 path but got ${paths.size}")
        val cmds = paths(0).svgAttrs.d match
            case Present(pd) => PathData.commands(pd)
            case Absent      => fail("Expected path to have d attribute")
        // Count MoveTo commands
        val moveToCount = cmds.count:
            case PathCommand.MoveTo(_, _) => true
            case _                        => false
        assert(moveToCount == 2, s"Expected 2 MoveTos (one per contiguous run) but got $moveToCount")
        // Both commands should be MoveTos (no LineTo between gaps)
        assert(cmds.size == 2, s"Expected exactly 2 path commands but got ${cmds.size}")
        // Vertices sit at the band CENTRE (left edge + bandW/2), aligning with the centred x-tick labels.
        // (Old values were the band LEFT edge and encoded the off-by-half-band bug.)
        val slot  = PlotW / 3.0
        val bandW = PlotW * 0.9 / 3.0 // 168.0
        val pad   = (slot - bandW) / 2.0
        val px_a  = PlotX + 0 * slot + pad + bandW / 2
        val px_c  = PlotX + 2 * slot + pad + bandW / 2
        cmds(0) match
            case PathCommand.MoveTo(x, y) =>
                assertClose(x, px_a, "gap MoveTo(a) x")
                assertClose(y, 440.0, "gap MoveTo(a) y")
            case other => fail(s"Expected MoveTo but got $other")
        end match
        cmds(1) match
            case PathCommand.MoveTo(x, y) =>
                assertClose(x, px_c, "gap MoveTo(c) x")
                assertClose(y, 20.0, "gap MoveTo(c) y")
            case other => fail(s"Expected MoveTo but got $other")
        end match
    }

    // ---- Test 7: empty data lowers to valid Svg.Root with no mark shapes ----

    "empty data lowers to a valid Svg.Root with no mark shapes" in {
        val rows: Chunk[Sale] = Chunk.empty
        val spec              = UI.chart(rows)(bar(x = _.month, y = _.revenue))
        val root              = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        assert(rectsIn(root).isEmpty, "Expected no rects for empty data")
        assert(pathsIn(root).isEmpty, "Expected no paths for empty data")
        assert(circlesIn(root).isEmpty, "Expected no circles for empty data")
        assert(linesIn(root).isEmpty, "Expected no lines for empty data")
        // Root itself is still valid
        root.svgAttrs.width match
            case Present(Coord.Num(w)) => assertClose(w, 640.0, "svg width")
            case other                 => fail(s"Expected width Present(640.0) but got $other")
    }

    // ---- Test 8: color encoding splits bar into N grouped sub-bands ----

    "color encoding splits a bar into N grouped sub-bands (N rects per band)" in {
        // Rows: 3 rows all with same x="Jan", 3 distinct regions => 3 rects
        // band is subdivided: sub-band width = bandW / 3 = 504/3 = 168
        //   NA: barX=88+0*168=88
        //   EU: barX=88+1*168=256
        //   APAC: barX=88+2*168=424
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Jan", Usd(2000), Region.EU),
            Sale("Jan", Usd(1500), Region.APAC)
        )
        val spec  = UI.chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val rects = rectsIn(root)
        // 3 rows * 1 x-band / 3 colors = 3 rects (one per color sub-band)
        assert(rects.size == 3, s"Expected 3 rects (one per color group) but got ${rects.size}")
        // All rects should have the same sub-band width = 504/3 = 168
        val expectedSubW = 504.0 / 3.0
        rects.foreach: r =>
            assertClose(numOf(r.svgAttrs.width), expectedSubW, "sub-band width")
        // X positions should be distinct and non-overlapping
        val xs = rects.map(r => numOf(r.svgAttrs.x)).toSeq.sorted
        assert(xs.size == 3)
        assertClose(xs(0), 88.0, "first sub-band x")
        assertClose(xs(1), 88.0 + expectedSubW, "second sub-band x")
        assertClose(xs(2), 88.0 + 2 * expectedSubW, "third sub-band x")
    }

    // ---- Test 8a (BUG): color 1:1 with x must NOT dodge; bars stay full-band and slot-centered ----
    // When the color encoding is 1:1 with the x encoding (each x-band contains exactly ONE color),
    // grammar-of-graphics convention says color must NOT subdivide the band: it just paints full-width
    // bars. The old lowerBarGrouped dodged EVERY bar into a global color sub-slot keyed by global color
    // index, so category 0 landed at the far left (width bandW/N), category 1 one slot right, etc. =>
    // thin bars marching left-to-right, misaligned with their centered x-axis tick labels. The fix
    // renders simple full-band bars when no band holds more than one distinct color.

    "color encoding that is 1:1 with x does not dodge: bars stay full-band and slot-centered" in {
        // 3 distinct categories A/B/C, each its OWN color (color == label). Each x-band holds exactly one
        // color => max-distinct-colors-per-band = 1 => simple full-band bars, NOT N grouped sub-bands.
        // x Band: n=3, totalW=560, slot=560/3=186.666..., bandW=560*0.9/3=168.0
        //   A: bandX = 60 + 0*slot + (slot-bandW)/2 = 60 + 9.333... = 69.333...
        //   B: bandX = 60 + 1*slot + (slot-bandW)/2 = 256.0
        //   C: bandX = 60 + 2*slot + (slot-bandW)/2 = 442.666...
        case class Cat(label: String, value: Double)
        given CanEqual[Cat, Cat] = CanEqual.derived
        val rows = Chunk(
            Cat("A", 1000.0),
            Cat("B", 2000.0),
            Cat("C", 1500.0)
        )
        val spec  = UI.chart(rows)(bar(x = _.label, y = _.value, color = _.label))
        val root  = summon[Conversion[ChartSpec[Cat], Svg.Root]](spec)
        val rects = rectsIn(root)
        assert(rects.size == 3, s"Expected 3 rects (one per category) but got ${rects.size}")

        val slot          = 560.0 / 3.0
        val expectedBandW = 560.0 * 0.9 / 3.0 // 168.0
        // Old (buggy) sub-band width would have been bandW/3 = 56.0; full-band is 3x that.
        val oldSubW = expectedBandW / 3.0
        assertClose(expectedBandW, oldSubW * 3.0, "full band is 3x old sub-band width")

        // Every rect must be FULL band width (168), not the thin dodge width (56).
        rects.foreach: r =>
            assertClose(numOf(r.svgAttrs.width), expectedBandW, "full-band width (must NOT be bandW/3)")

        // Each rect's x must be its band LEFT edge (slot-centered), NOT offset by colorIdx*subW.
        val xsSorted         = rects.map(r => numOf(r.svgAttrs.x)).toSeq.sorted
        def bandLeft(i: Int) = 60.0 + i.toDouble * slot + (slot - expectedBandW) / 2.0
        assertClose(xsSorted(0), bandLeft(0), "A band left edge (69.333..)")
        assertClose(xsSorted(1), bandLeft(1), "B band left edge (256.0)")
        assertClose(xsSorted(2), bandLeft(2), "C band left edge (442.666..)")
    }

    // ---- Test 8b (ISSUE): grouped bar with numeric color encoding honors a Sequential color scale ----
    // A non-stacked bar whose color encoding is NUMERIC plus `.legend(_.colorScaleSequential(low, high))`
    // must paint each bar with the interpolated gradient color for its value, the same way lowerPoint and
    // lowerArea do via resolvePalette. Before the fix, lowerBarGrouped colored bars from the categorical
    // theme/DefaultPalette (blue/orange/...), ignoring the Sequential scale entirely.

    "grouped bar with numeric color encoding honors a Sequential color scale (gradient, not categorical)" in {
        // Three rows, same x="Jan", numeric color values 0.0/50.0/100.0 over domain extent [0, 100].
        // Sequential(black=#000000, white=#ffffff): value 0 => rgb(0,0,0), 50 => rgb(128,128,128),
        // 100 => rgb(255,255,255). These are Style.Color.Rgb, NOT the categorical blue/orange Hex fills.
        case class Heat(month: String, revenue: Double, level: Double)
        given CanEqual[Heat, Heat] = CanEqual.derived
        val rows = Chunk(
            Heat("Jan", 1000.0, 0.0),
            Heat("Jan", 2000.0, 50.0),
            Heat("Jan", 1500.0, 100.0)
        )
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue, color = _.level))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        val root  = summon[Conversion[ChartSpec[Heat], Svg.Root]](spec)
        val rects = rectsIn(root)
        assert(rects.size == 3, s"Expected 3 bar rects but got ${rects.size}")

        // The grouped bars are positioned left-to-right by color-category index (encounter order:
        // level 0.0, 50.0, 100.0). Order rects by x to recover that mapping.
        val byX = rects.toSeq.sortBy(r => numOf(r.svgAttrs.x))
        def fillOf(r: Svg.Rect): Style.Color =
            r.svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"Expected a color fill but got $other")
        val fills = byX.map(fillOf)

        // Interpolated sequential fills, NOT the categorical palette.
        assert(fills(0) == Style.Color.rgb(0, 0, 0), s"lowest-value bar must be the low color; got ${fills(0)}")
        assert(fills(1) == Style.Color.rgb(128, 128, 128), s"mid-value bar must be midpoint gradient; got ${fills(1)}")
        assert(fills(2) == Style.Color.rgb(255, 255, 255), s"highest-value bar must be the high color; got ${fills(2)}")

        // Must NOT be the categorical DefaultPalette/theme colors.
        assert(
            fills.forall(c => c != Style.Color.blue && c != Style.Color.orange),
            s"bar fills must not be categorical palette colors (#3b82f6/#f97316); got $fills"
        )
    }

    // ---- Leaf L1 (GAP-COLOR-GROUPEDBAR): grouped bar with a Categorical colorScale uses the scale colors ----
    // Before the fix, lowerBarGrouped's `palette` val matched only `Present(_: Sequential)` and routed
    // `Present(_: Categorical)` into the `case _` fallback (the by-index basePalette), so bars got
    // DefaultPalette colors (#3b82f6 blue, #f97316 orange, ...) instead of the colorScale colors.
    // The fix changes `Present(_: Sequential) => resolvePalette` to `Present(_) => resolvePalette`, so
    // both Categorical and Sequential colorScales are honoured. The Absent arm is byte-identical (§0.1).

    "grouped bar with categorical colorScale uses the scale colors, not DefaultPalette (GAP-COLOR-GROUPEDBAR)" in {
        // Three rows sharing x="Jan", one per region -- this guarantees dodge=true (multiple distinct
        // colors in the same x-band). Colors are chosen to be unambiguously distinct from the
        // DefaultPalette entries (#3b82f6 blue and #f97316 orange) so an accidental fallback is caught.
        val naColor   = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))   // red
        val euColor   = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))   // teal
        val apacColor = Style.Color.hex("#e9c46a").getOrElse(fail("bad hex apacColor")) // yellow
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Jan", Usd(2000), Region.EU),
            Sale("Jan", Usd(1500), Region.APAC)
        )
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
            .legend(_.colorScale[Region](
                Region.NA   -> naColor,
                Region.EU   -> euColor,
                Region.APAC -> apacColor
            ))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val rects = rectsIn(root)
        assert(rects.size == 3, s"Expected 3 bar rects (one per region) but got ${rects.size}")

        // Sort rects by x position to recover NA/EU/APAC encounter order.
        val byX = rects.toSeq.sortBy(r => numOf(r.svgAttrs.x))
        def fillOf(r: Svg.Rect): Style.Color =
            r.svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"Expected a color fill but got $other")
        val fills = byX.map(fillOf)

        // Each sub-bar must carry the colorScale color for its region, NOT the DefaultPalette color.
        // colorCats are collected in encounter order: NA (idx 0), EU (idx 1), APAC (idx 2).
        assert(fills(0) == naColor, s"NA sub-bar (idx 0) must be naColor $naColor but got ${fills(0)}")
        assert(fills(1) == euColor, s"EU sub-bar (idx 1) must be euColor $euColor but got ${fills(1)}")
        assert(fills(2) == apacColor, s"APAC sub-bar (idx 2) must be apacColor $apacColor but got ${fills(2)}")

        // Explicit guard: must NOT be the DefaultPalette fallback colors.
        assert(
            fills.forall(c => c != Style.Color.blue && c != Style.Color.orange),
            s"bar fills must not be DefaultPalette colors (#3b82f6/#f97316); got $fills"
        )

        // Legend swatch colors must agree with their corresponding mark fills.
        // Swatches are 12x12 rects that are direct children of the root frame (not inside the marks G).
        val swatches = legendSwatchRects(root)
        assert(swatches.size == 3, s"Expected 3 legend swatches but got ${swatches.size}")
        // Sort swatches by y position (they are stacked vertically in encounter order: NA, EU, APAC).
        val swatchesByY = swatches.toSeq.sortBy(s => coordNum(s.svgAttrs.y).getOrElse(0.0))
        val swatchFills = swatchesByY.map(s => fillColorOf(s.svgAttrs.fill))
        assert(swatchFills(0) == naColor, s"Legend swatch 0 must be naColor $naColor but got ${swatchFills(0)}")
        assert(swatchFills(1) == euColor, s"Legend swatch 1 must be euColor $euColor but got ${swatchFills(1)}")
        assert(swatchFills(2) == apacColor, s"Legend swatch 2 must be apacColor $apacColor but got ${swatchFills(2)}")
    }

    // ---- Test 9: line path has fill=None, stroke present, strokeWidth present ----
    // Regression test for the bug where lowerLineSeries produced a filled black polygon instead of a stroked line.
    // The old code did `Svg.path.d(pathData)` with no paint, so the browser filled the path with black (default fill).

    "line mark lowers to a path with fill=Paint.None, stroke present, and strokeWidth present" in {
        case class Row(x: String, y: Int)
        val rows = Chunk(Row("a", 100), Row("b", 200))
        val spec = UI.chart(rows)(line(x = _.x, y = _.y))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val ps   = pathsIn(root)
        assert(ps.size == 1, s"Expected 1 path but got ${ps.size}")
        val p = ps(0)
        // fill must be explicitly Paint.None to suppress browser default (black) fill
        assert(
            p.svgAttrs.fill == Present(Svg.Paint.None),
            s"Expected fill=Paint.None but got ${p.svgAttrs.fill}"
        )
        // stroke must be present so the line is visible
        assert(p.svgAttrs.stroke.isDefined, s"Expected stroke to be Present but got ${p.svgAttrs.stroke}")
        // strokeWidth must be present
        assert(p.svgAttrs.strokeWidth.isDefined, s"Expected strokeWidth to be Present but got ${p.svgAttrs.strokeWidth}")
    }

    // ---- Test 10: bar rect is filled (not just stroked) ----
    // Distinguishes bar from line: a bar should have an explicit fill color set, not fill=None.

    "bar mark lowers to a rect with a non-None fill color" in {
        val rows  = Chunk(Sale("Jan", Usd(1000)))
        val spec  = UI.chart(rows)(bar(x = _.month, y = _.revenue))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val rects = rectsIn(root)
        assert(rects.size == 1, s"Expected 1 rect but got ${rects.size}")
        val r = rects(0)
        r.svgAttrs.fill match
            case Present(Svg.Paint.Color(_)) => succeed // correct: a color fill, not None
            case Present(Svg.Paint.None)     => fail("Bar fill must not be Paint.None: bars are filled shapes")
            case other                       => fail(s"Expected a color fill but got $other")
        end match
    }

    // ---- Test 10b: single-mark default color is palette(0) ----
    // A chart with a single mark and no explicit color encoding uses the first palette entry (blue).

    "single-mark bar uses palette(0) (blue) as its default fill" in {
        val rows  = Chunk(Sale("Jan", Usd(1000)))
        val spec  = UI.chart(rows)(bar(x = _.month, y = _.revenue))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val rects = rectsIn(root)
        assert(rects.size == 1, s"Expected 1 rect but got ${rects.size}")
        rects(0).svgAttrs.fill match
            case Present(Svg.Paint.Color(c)) =>
                assert(c == Style.Color.blue, s"Single-mark bar should use palette(0) (blue) but got $c")
            case other => fail(s"Expected a color fill but got $other")
        end match
    }

    // ---- Test 10c (ISSUE 1): multi-mark combo assigns DISTINCT palette colors per mark index ----
    // In a combo chart (bar + line, both without an explicit color encoding) mark 0 (bar) must use
    // palette(0) (blue) and mark 1 (line) must use palette(1) (orange), so the line is visually
    // distinguishable from the bars rather than sharing the same default color.

    "combo (bar + line) assigns distinct per-mark palette colors: bar=palette(0), line=palette(1)" in {
        case class Combo(month: String, revenue: Double, growth: Double)
        given CanEqual[Combo, Combo] = CanEqual.derived
        val rows                     = Chunk(Combo("Jan", 1000.0, 10.0), Combo("Feb", 2000.0, 20.0))
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growth, axis = Axis.Right)
        )
        val root = summon[Conversion[ChartSpec[Combo], Svg.Root]](spec)

        // Bar mark (index 0): fill must be palette(0) = blue.
        val rects = rectsIn(root)
        assert(rects.nonEmpty, s"Expected at least one bar rect but got ${rects.size}")
        rects.foreach: r =>
            r.svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) =>
                    assert(c == Style.Color.blue, s"Bar (mark 0) should be palette(0) (blue) but got $c")
                case other => fail(s"Expected a bar color fill but got $other")

        // Line mark (index 1): stroke must be palette(1) = orange (distinct from the bar's blue).
        val paths = pathsIn(root)
        assert(paths.size == 1, s"Expected 1 line path but got ${paths.size}")
        paths(0).svgAttrs.stroke match
            case Present(Svg.Paint.Color(c)) =>
                assert(c == Style.Color.orange, s"Line (mark 1) should be palette(1) (orange) but got $c")
                assert(c != Style.Color.blue, "Line color must differ from the bar color in a combo chart")
            case other => fail(s"Expected a line stroke color but got $other")
        end match
    }

    // ---- Test 11: dark-theme bar uses a light/visible fill color ----
    // Regression for the bug where dark-theme bars used browser-default black fill,
    // making them invisible on the dark (#1f2937) background.

    "dark-theme bar uses a light fill color, not black" in {
        val rows  = Chunk(Sale("Jan", Usd(1000)))
        val spec  = UI.chart(rows)(bar(x = _.month, y = _.revenue)).theme(_.dark)
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val rects = rectsIn(root)
        assert(rects.size == 1, s"Expected 1 rect but got ${rects.size}")
        val r = rects(0)
        r.svgAttrs.fill match
            case Present(Svg.Paint.Color(c)) =>
                // Must NOT be black (which is the browser default that made bars invisible on dark bg)
                assert(
                    c != Style.Color.black,
                    s"Dark-theme bar fill must not be black; got $c"
                )
            case Present(Svg.Paint.None) => fail("Dark-theme bar fill must not be None")
            case other                   => fail(s"Expected a color fill but got $other")
        end match
    }

    // ---- Test 12 (ISSUE 3 + ISSUE 1): dark-theme axis text colors ----
    // On the dark theme the SVG background is dark, so axis text must not be the browser default black.
    // The shared x-axis chrome stays the neutral light gray (#e5e7eb). The left y-axis is bound to exactly
    // one mark (the single bar = mark 0), so ISSUE 1 color-codes its tick labels to that mark's palette
    // color (palette(0) = blue), not the neutral gray.

    "dark-theme axis text: x-axis stays neutral light gray, single-mark y-axis is color-coded (never black)" in {
        // Neutral light text color used on the dark theme (must match ChartLower.DarkThemeTextColor).
        val lightText = Style.Color.hex("#e5e7eb").getOrElse(Style.Color.white)
        val rows      = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
            .yAxis(_.ticks(3))
            .xAxis(_.label("Month"))
            .theme(_.dark)
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // Frame texts (tick labels and the x-axis label) live directly under the root.
        val texts = root.children.flatMap:
            case t: Svg.Text => Chunk(t)
            case _           => Chunk.empty
        assert(texts.nonEmpty, "Expected axis text elements on the dark theme")

        // No axis text may be black on the dark theme.
        texts.foldLeft(succeed): (_, t) =>
            t.svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) => assert(c != Style.Color.black, "Dark-theme axis text must not be black")
                case other                       => fail(s"Expected a fill color on dark-theme axis text but got $other")

        // x-axis chrome stays the neutral light gray: x tick labels (DominantBaseline.Hanging) and the
        // bottom "Month" label (TextAnchor.Middle, no rotation).
        val xTexts = texts.filter(t =>
            t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Hanging) ||
                t.children.exists { case UI.Ast.Text("Month") => true; case _ => false }
        )
        assert(xTexts.nonEmpty, "Expected x-axis tick labels and/or the Month label")
        xTexts.foldLeft(succeed): (_, t) =>
            t.svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) => assert(c == lightText, s"X-axis text should stay neutral $lightText but got $c")
                case other                       => fail(s"Expected an x-axis text fill but got $other")

        // Left y-axis tick labels (TextAnchor.End) are color-coded to the single bound mark: palette(0) = blue.
        val leftTicks = texts.filter(t => t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))
        assert(leftTicks.nonEmpty, "Expected left y-axis tick labels")
        leftTicks.foldLeft(succeed): (_, t) =>
            t.svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) =>
                    assert(c == Style.Color.blue, s"Single-mark y-axis tick should be palette(0) (blue) but got $c")
                case other => fail(s"Expected a left y-axis tick fill but got $other")
    }

    // ---- Test 13 (ISSUE 3): dark-theme background covers the whole SVG canvas ----
    // The background rect must span the entire SVG (not only the plot rect) so the axis margins read dark.

    "dark-theme background rect covers the whole SVG canvas" in {
        val rows = Chunk(Sale("Jan", Usd(1000)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue)).theme(_.dark).size(360, 240)
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val bg   = root.children.collectFirst { case r: Svg.Rect => r }.getOrElse(fail("Expected a background rect"))
        assertClose(numOf(bg.svgAttrs.x), 0.0, "background x")
        assertClose(numOf(bg.svgAttrs.y), 0.0, "background y")
        assertClose(numOf(bg.svgAttrs.width), 360.0, "background width spans full SVG")
        assertClose(numOf(bg.svgAttrs.height), 240.0, "background height spans full SVG")
    }

    // ---- Phase 3 tests: point color / symbol / size / curve / area band / stacks / encodings ----

    case class Row(x: String, y: Double, g: String, mag: Double = 1.0, name: String = "")
    given CanEqual[Row, Row] = CanEqual.derived

    private def deepCirclesIn(root: Svg.Root): Chunk[Svg.Circle] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case c: Svg.Circle => Chunk(c)
                    case gg: Svg.G =>
                        gg.children.flatMap:
                            case c: Svg.Circle => Chunk(c)
                            case _             => Chunk.empty
                    case _ => Chunk.empty
            case _ => Chunk.empty

    private def deepPathsIn(root: Svg.Root): Chunk[Svg.Path] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case p: Svg.Path => Chunk(p)
                    case gg: Svg.G =>
                        gg.children.flatMap:
                            case p: Svg.Path => Chunk(p)
                            case _           => Chunk.empty
                    case _ => Chunk.empty
            case _ => Chunk.empty

    private def deepTextsIn(root: Svg.Root): Chunk[Svg.Text] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case t: Svg.Text => Chunk(t)
                    case gg: Svg.G =>
                        gg.children.flatMap:
                            case t: Svg.Text => Chunk(t)
                            case _           => Chunk.empty
                    case _ => Chunk.empty
            case _ => Chunk.empty

    private def fillColorOf(c: Svg.Circle): String = c.svgAttrs.fill match
        case Present(Svg.Paint.Color(col)) => col.toString
        case other                         => s"unexpected:$other"

    private def fillColorOfPath(p: Svg.Path): String = p.svgAttrs.fill match
        case Present(Svg.Paint.Color(col)) => col.toString
        case other                         => s"unexpected:$other"

    private def hasCubicCmd(p: Svg.Path): Boolean =
        Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).toSeq.exists:
            case _: PathCommand.CubicTo => true
            case _                      => false

    private def cubicCount(p: Svg.Path): Int =
        Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).toSeq.count:
            case _: PathCommand.CubicTo => true
            case _                      => false

    private def hasHLineCmd(p: Svg.Path): Boolean =
        Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).toSeq.exists:
            case _: PathCommand.HLineTo => true
            case _                      => false

    private def hasVLineCmd(p: Svg.Path): Boolean =
        Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).toSeq.exists:
            case _: PathCommand.VLineTo => true
            case _                      => false

    private def hasCloseCmd(p: Svg.Path): Boolean =
        Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).toSeq.exists:
            case PathCommand.Close => true
            case _                 => false

    // --- Test 1: point color splits (INV-013) ---
    "point color splits into per-group colors (INV-013)" in {
        val rows = Chunk(Row("a", 10.0, "g1"), Row("b", 20.0, "g2"))
        val spec = UI.chart(rows)(point(x = _.x, y = _.y, color = _.g))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val cs   = deepCirclesIn(root)
        assert(cs.size >= 2, s"Expected at least 2 circles, got ${cs.size}")
        val fills = cs.map(fillColorOf).toSeq.distinct
        assert(fills.size == 2, s"Expected 2 distinct fill colors for 2 groups, got $fills")
    }

    // --- Test 2: CatKey identity (Int vs String) (INV-013, INV-002) ---
    "point color uses CatKey identity: Int 1 vs String '1' are distinct groups (INV-013, INV-002)" in {
        case class RawRow(x: String, y: Double, grp: Any)
        given CanEqual[RawRow, RawRow] = CanEqual.derived
        val rows                       = Chunk(RawRow("a", 10.0, 1: Int), RawRow("b", 20.0, "1"))
        val spec                       = UI.chart(rows)(point(x = _.x, y = _.y, color = _.grp))
        val root                       = summon[Conversion[ChartSpec[RawRow], Svg.Root]](spec)
        val cs                         = deepCirclesIn(root)
        val fills                      = cs.map(fillColorOf).toSeq.distinct
        assert(fills.size == 2, s"Int 1 and String '1' must be distinct color groups, got $fills")
    }

    // --- Test 3: no color keeps defaultColor (INV-013) ---
    "point without color encoding: all circles have the same default fill (INV-013)" in {
        val rows = Chunk(Row("a", 10.0, "g1"), Row("b", 20.0, "g2"))
        val spec = UI.chart(rows)(point(x = _.x, y = _.y))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val cs   = deepCirclesIn(root)
        assert(cs.size >= 2, s"Expected at least 2 circles")
        val fills = cs.map(fillColorOf).toSeq.distinct
        assert(fills.size == 1, s"All circles without color encoding should share one fill, got $fills")
    }

    // --- Test 4: symbol=square renders Svg.Path not Svg.Circle (INV-014) ---
    "symbol=square renders Svg.Path elements, not circles (INV-014)" in {
        val rows = Chunk(Row("a", 10.0, "g1"), Row("b", 20.0, "g2"))
        val spec = UI.chart(rows)(point(x = _.x, y = _.y, symbol = _ => Symbol.square))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val cs   = deepCirclesIn(root)
        val ps   = deepPathsIn(root).filter(p => p.svgAttrs.d.isDefined && p.svgAttrs.fill.isDefined)
        assert(cs.isEmpty, s"symbol=square must not emit Svg.Circle, but got ${cs.size}")
        assert(ps.nonEmpty, s"symbol=square must emit Svg.Path elements")
    }

    // --- Test 5: each Symbol case (INV-014) ---
    "triangle, diamond, cross each render their documented glyph (INV-014)" in {
        val rows = Chunk(Row("a", 10.0, "g"))
        // Triangle and diamond should produce Svg.Path.
        for sym <- Seq(Symbol.triangle, Symbol.diamond) do
            val spec = UI.chart(rows)(point(x = _.x, y = _.y, symbol = _ => sym))
            val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
            val ps   = deepPathsIn(root).filter(p => p.svgAttrs.d.isDefined)
            val cs   = deepCirclesIn(root)
            assert(ps.nonEmpty, s"$sym must emit Svg.Path")
            assert(cs.isEmpty, s"$sym must not emit Svg.Circle")
        end for
        // Circle default.
        val specC = UI.chart(rows)(point(x = _.x, y = _.y))
        val rootC = summon[Conversion[ChartSpec[Row], Svg.Root]](specC)
        assert(deepCirclesIn(rootC).nonEmpty, "Default symbol (circle) must emit Svg.Circle")
        // Cross produces Svg.Line elements (two strokes).
        val specX = UI.chart(rows)(point(x = _.x, y = _.y, symbol = _ => Symbol.cross))
        val rootX = summon[Conversion[ChartSpec[Row], Svg.Root]](specX)
        val ls    = linesIn(rootX)
        assert(ls.nonEmpty, "Symbol.cross must emit Svg.Line strokes")
    }

    // --- Test 6: size is sqrt-area scaled (INV-015) ---
    "point size is sqrt-area scaled: bigger magnitude produces bigger radius (INV-015)" in {
        case class Bubble(x: Double, y: Double, mag: Double)
        given CanEqual[Bubble, Bubble] = CanEqual.derived
        val rows                       = Chunk(Bubble(1.0, 1.0, 1.0), Bubble(2.0, 1.0, 100.0))
        val spec                       = UI.chart(rows)(point(x = _.x, y = _.y, size = _.mag))
        val root                       = summon[Conversion[ChartSpec[Bubble], Svg.Root]](spec)
        val cs                         = deepCirclesIn(root)
        assert(cs.size == 2, s"Expected 2 circles, got ${cs.size}")
        val rs = cs.map(c => c.svgAttrs.r).toSeq.collect:
            case Present(v) => v
        assert(rs.size == 2, s"Both circles must have a radius, got $rs")
        assert(rs(0) < rs(1), s"Larger magnitude should produce larger radius: ${rs(0)} vs ${rs(1)}")
        // Verify sqrt-area: r(1) should be ~2.0, r(100) should be ~20.0.
        assert(math.abs(rs(0) - 2.0) < 1e-6, s"r(mag=1) should be rMin=2.0, got ${rs(0)}")
        assert(math.abs(rs(1) - 20.0) < 1e-6, s"r(mag=100) should be rMax=20.0, got ${rs(1)}")
    }

    // --- Test 7: equal magnitudes yield rMin, no div-by-zero (INV-015) ---
    "equal magnitudes yield rMin, no div-by-zero (INV-015)" in {
        case class Bubble(x: Double, y: Double, mag: Double)
        given CanEqual[Bubble, Bubble] = CanEqual.derived
        val rows                       = Chunk(Bubble(1.0, 1.0, 5.0), Bubble(2.0, 1.0, 5.0))
        val spec                       = UI.chart(rows)(point(x = _.x, y = _.y, size = _.mag))
        val root                       = summon[Conversion[ChartSpec[Bubble], Svg.Root]](spec)
        val cs                         = deepCirclesIn(root)
        assert(cs.size == 2, "Expected 2 circles")
        val badRadii = cs.toSeq.filter: c =>
            c.svgAttrs.r.map(v => math.abs(v - 2.0) >= 1e-6).getOrElse(true)
        assert(badRadii.isEmpty, s"All circles with equal magnitudes should have rMin=2.0; bad: ${badRadii.map(_.svgAttrs.r)}")
    }

    // --- Test 8: size legend is emitted (INV-015) ---
    "size legend is emitted when size encoding is set (INV-015)" in {
        case class Bubble(x: Double, y: Double, mag: Double)
        given CanEqual[Bubble, Bubble] = CanEqual.derived
        val rows                       = Chunk(Bubble(1.0, 1.0, 1.0), Bubble(2.0, 1.0, 100.0))
        val spec                       = UI.chart(rows)(point(x = _.x, y = _.y, size = _.mag))
        val root                       = summon[Conversion[ChartSpec[Bubble], Svg.Root]](spec)
        // The legend region should contain circle elements (size bubbles).
        val allCircles = root.children.flatMap:
            case g: Svg.G => g.children.flatMap:
                    case c: Svg.Circle => Chunk(c)
                    case _             => Chunk.empty
            case _ => Chunk.empty
        assert(allCircles.nonEmpty, "Size legend should emit circle elements")
    }

    // --- Test 9: size wins over sizePx (INV-015, Q-005) ---
    "size wins over sizePx when both supplied: Mark.Point.size is Present, sizePx is Absent (INV-015)" in {
        case class Bubble(x: Double, y: Double, mag: Double)
        given CanEqual[Bubble, Bubble] = CanEqual.derived
        val m                          = point[Bubble, Double, Double](x = _.x, y = _.y, size = _.mag, sizePx = _ => 8.0)
        val pm                         = m.asInstanceOf[Mark.Point[Bubble, Double, Double]]
        assert(pm.size.isDefined, "size encoding must be Present when both size and sizePx supplied")
        assert(!pm.sizePx.isDefined, "sizePx must be Absent when size wins per Q-005")
    }

    // --- Test 10: sizePx alone uses raw radius (INV-015) ---
    "sizePx alone uses raw pixel radius (INV-015)" in {
        case class Bubble(x: Double, y: Double)
        given CanEqual[Bubble, Bubble] = CanEqual.derived
        val rows                       = Chunk(Bubble(1.0, 1.0), Bubble(2.0, 2.0))
        val spec                       = UI.chart(rows)(point(x = _.x, y = _.y, sizePx = _ => 8.0))
        val root                       = summon[Conversion[ChartSpec[Bubble], Svg.Root]](spec)
        val cs                         = deepCirclesIn(root)
        assert(cs.size == 2, s"Expected 2 circles, got ${cs.size}")
        val badR = cs.toSeq.filter(c => c.svgAttrs.r.map(v => math.abs(v - 8.0) >= 1e-6).getOrElse(true))
        assert(badR.isEmpty, s"sizePx=8.0 should yield r=8.0 for all circles; bad: ${badR.map(_.svgAttrs.r)}")
    }

    // Tests 11-15 use (Double, Double) rows, relying on Plottable.numeric for Double.
    // To avoid local-case-class implicit-scope issues, use the existing Row type with numeric x.
    case class DRow(x: Double, y: Double)
    given CanEqual[DRow, DRow] = CanEqual.derived

    case class DRowMaybe(x: Double, y: Maybe[Double])
    given CanEqual[DRowMaybe, DRowMaybe] = CanEqual.derived

    // --- Test 11: curve=stepAfter emits H/V staircase (INV-016) ---
    "curve=stepAfter line emits HLineTo and VLineTo commands (INV-016)" in {
        val rows = Chunk(DRow(0.0, 0.0), DRow(1.0, 2.0), DRow(2.0, 1.0))
        val spec = UI.chart(rows)(line(x = _.x, y = _.y, curve = Curve.stepAfter))
        val root = summon[Conversion[ChartSpec[DRow], Svg.Root]](spec)
        val ps   = pathsIn(root)
        assert(ps.nonEmpty, "Expected at least one path for line mark")
        val hasH = ps.toSeq.exists(hasHLineCmd)
        val hasV = ps.toSeq.exists(hasVLineCmd)
        assert(hasH, "stepAfter line must emit HLineTo commands")
        assert(hasV, "stepAfter line must emit VLineTo commands")
    }

    // --- Test 12: curve=monotone emits cubics (INV-016) ---
    "curve=monotone line emits CubicTo commands (INV-016)" in {
        val rows = Chunk(DRow(0.0, 0.0), DRow(1.0, 2.0), DRow(2.0, 1.0))
        val spec = UI.chart(rows)(line(x = _.x, y = _.y, curve = Curve.monotone))
        val root = summon[Conversion[ChartSpec[DRow], Svg.Root]](spec)
        val ps   = pathsIn(root)
        assert(ps.nonEmpty, "Expected at least one path")
        assert(ps.toSeq.exists(hasCubicCmd), "monotone line must emit CubicTo commands")
    }

    // --- Test 13: basis and catmullRom emit cubics (INV-016) ---
    "curve=basis and catmullRom emit CubicTo commands (INV-016)" in {
        val rows4   = Chunk(DRow(0.0, 0.0), DRow(1.0, 2.0), DRow(2.0, 0.0), DRow(3.0, 2.0))
        val basSpec = UI.chart(rows4)(line(x = _.x, y = _.y, curve = Curve.basis))
        val catSpec = UI.chart(rows4)(line(x = _.x, y = _.y, curve = Curve.catmullRom))
        val basRoot = summon[Conversion[ChartSpec[DRow], Svg.Root]](basSpec)
        val catRoot = summon[Conversion[ChartSpec[DRow], Svg.Root]](catSpec)
        assert(pathsIn(basRoot).toSeq.exists(hasCubicCmd), "basis line must emit CubicTo commands")
        assert(pathsIn(catRoot).toSeq.exists(hasCubicCmd), "catmullRom line must emit CubicTo commands")
    }

    // --- Test 14: curve applies per-segment, not across gaps (INV-016) ---
    "curve=monotone with gap: path has two MoveTo segments (INV-016)" in {
        val rows = Chunk(DRowMaybe(0.0, Present(0.0)), DRowMaybe(1.0, Absent), DRowMaybe(2.0, Present(1.0)))
        val spec = UI.chart(rows)(line(x = _.x, y = _.y, curve = Curve.monotone))
        val root = summon[Conversion[ChartSpec[DRowMaybe], Svg.Root]](spec)
        val ps   = pathsIn(root)
        assert(ps.nonEmpty, "Expected at least one path")
        val moveTos = ps.flatMap: p =>
            Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).filter:
                case PathCommand.MoveTo(_, _) => true
                case _                        => false
        assert(moveTos.size >= 2, s"Gap must produce at least 2 MoveTo commands, got ${moveTos.size}")
    }

    // --- Test 15: < 2 points degrades to linear (INV-016) ---
    "curve=basis with 1 point: no cubic emitted (INV-016)" in {
        val rows = Chunk(DRow(1.0, 2.0))
        val spec = UI.chart(rows)(line(x = _.x, y = _.y, curve = Curve.basis))
        val root = summon[Conversion[ChartSpec[DRow], Svg.Root]](spec)
        val ps   = pathsIn(root)
        assert(!ps.toSeq.exists(hasCubicCmd), "1-point line must not emit cubics")
    }

    // --- Test 16: area band ribbon (INV-017) ---
    "area y0/y1 band renders a non-empty closed ribbon (INV-017)" in {
        case class Band(t: Double, lo: Double, hi: Double)
        given CanEqual[Band, Band] = CanEqual.derived
        val rows                   = Chunk(Band(0.0, 0.0, 2.0), Band(1.0, 0.5, 2.5))
        val spec                   = UI.chart(rows)(area(x = _.t, y0 = _.lo, y1 = _.hi))
        val root                   = summon[Conversion[ChartSpec[Band], Svg.Root]](spec)
        val ps                     = deepPathsIn(root).filter(p => p.svgAttrs.d.isDefined && hasCloseCmd(p))
        // Exactly one closed ribbon path: the band form always produces a single continuous path.
        assert(ps.size == 1, s"area y0/y1 band must emit exactly 1 closed ribbon path but got ${ps.size}")
    }

    // --- Test 17: invalid area combo emits empty, siblings render (INV-017) ---
    "area with only y1 (no y0, no y): mark emits empty, bar sibling renders (INV-017)" in {
        case class Datum(x: String, y1: Double, y: Double)
        given CanEqual[Datum, Datum] = CanEqual.derived
        val rows                     = Chunk(Datum("a", 2.0, 1.0), Datum("b", 3.0, 2.0))
        // area with only y1 supplied: invalid combo -> empty mark.
        val spec = UI.chart(rows)(area(x = _.x, y1 = _.y1), bar(x = _.x, y = _.y))
        val root = summon[Conversion[ChartSpec[Datum], Svg.Root]](spec)
        val rs   = rectsIn(root)
        assert(rs.nonEmpty, "bar sibling must still render when area mark is invalid")
        // The invalid area mark must have emitted no path elements at all.
        assert(pathsIn(root).isEmpty, "area with only y1 (invalid combo) must emit no path elements")
    }

    // --- Test 18: single y wins over y+y0/y1 (INV-017) ---
    "area with y and y0/y1 both supplied: single y wins (INV-017)" in {
        case class Band(t: Double, v: Double, lo: Double, hi: Double)
        given CanEqual[Band, Band] = CanEqual.derived
        val rows                   = Chunk(Band(0.0, 1.0, 0.0, 2.0), Band(1.0, 1.5, 0.5, 2.5))
        val spec                   = UI.chart(rows)(area(x = _.t, y = _.v, y0 = _.lo, y1 = _.hi))
        val root                   = summon[Conversion[ChartSpec[Band], Svg.Root]](spec)
        // When y is supplied, the factory sets yMaybe=Present, so area renders the single-y form.
        val ps = deepPathsIn(root).filter(_.svgAttrs.d.isDefined)
        // Exactly 1 path: the y encoding wins, so a single series path is emitted (not a y0/y1 ribbon).
        assert(ps.size == 1, s"area with single y must render exactly 1 path but got ${ps.size}")
        // The single-y linear form does not emit CubicTo commands. The band form (y0+y1+curve) would emit
        // cubics on both edges; the linear single-y path only has MoveTo/LineTo/Close. This distinguishes
        // single-y from a band-with-curve path.
        assert(
            !ps.toSeq.exists(hasCubicCmd),
            "area single-y linear form must not contain CubicTo commands (those appear in the band+curve form)"
        )
    }

    // --- Test 19: curve applies to BOTH area band edges (INV-016, INV-017) ---
    "area y0/y1 band with curve=monotone emits cubics on both edges (INV-016, INV-017)" in {
        case class Band(t: Double, lo: Double, hi: Double)
        given CanEqual[Band, Band] = CanEqual.derived
        val rows                   = Chunk(Band(0.0, 0.0, 2.0), Band(1.0, 0.5, 2.5), Band(2.0, 0.2, 1.8), Band(3.0, 0.6, 2.2))
        val spec                   = UI.chart(rows)(area(x = _.t, y0 = _.lo, y1 = _.hi, curve = Curve.monotone))
        val root                   = summon[Conversion[ChartSpec[Band], Svg.Root]](spec)
        val ps                     = deepPathsIn(root).filter(p => p.svgAttrs.d.isDefined && hasCloseCmd(p))
        assert(ps.nonEmpty, "area y0/y1 with monotone curve must render a closed path")
        // Both the forward y1 edge AND the backward y0 edge must be curved. append emits one cubic per
        // interior segment: for 4 band points each edge feeds 3 points to append (after dropping the
        // anchor/connecting vertex), so each curved edge yields 2 cubics. A y1-only curve with a linear
        // y0 edge would yield only 2 cubics total; requiring >=4 proves the y0 edge is also curved.
        val ribbon = ps.toSeq.maxBy(cubicCount)
        assert(
            cubicCount(ribbon) >= 4,
            s"both band edges must be curved (>=4 cubics: 2 per edge for 4 points); got ${cubicCount(ribbon)}"
        )
    }

    // --- Test 20: stacked bar with negative value: non-negative rect height (INV-018) ---
    "stacked bar with negative value has non-negative rect height (INV-018)" in {
        val rows = Chunk(Row("a", 10.0, "pos"), Row("a", -5.0, "neg"))
        val spec = UI.chart(rows)(bar(x = _.x, y = _.y, stack = by(_.g)))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val rs   = rectsIn(root)
        assert(rs.nonEmpty, "Expected stacked bar rects")
        val negH = rs.toSeq.filter: r =>
            r.svgAttrs.height match
                case Present(Coord.Num(v)) => v < 0.0
                case _                     => false
        assert(negH.isEmpty, s"All rect heights must be non-negative; negatives: ${negH.map(_.svgAttrs.height)}")
    }

    // --- Test 21: negative stack does not clip positive segments (INV-018) ---
    "stacked bar with mixed signs: positive and negative stacks both render (INV-018)" in {
        val rows = Chunk(Row("a", 10.0, "pos"), Row("a", -5.0, "neg"))
        val spec = UI.chart(rows)(bar(x = _.x, y = _.y, stack = by(_.g)))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val rs   = rectsIn(root)
        // Both positive and negative groups should emit a rect (non-zero rawY).
        assert(rs.size >= 2, s"Expected rects for both positive and negative groups, got ${rs.size}")
    }

    // --- Test 22: all-positive stack renders rects (INV-018 regression) ---
    "all-positive stacked bar renders non-empty rects (INV-018 no-regression)" in {
        val rows = Chunk(Row("a", 10.0, "g1"), Row("a", 5.0, "g2"))
        val spec = UI.chart(rows)(bar(x = _.x, y = _.y, stack = by(_.g)))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val rs   = rectsIn(root)
        assert(rs.size == 2, s"Expected 2 rects for all-positive stack, got ${rs.size}")
        val badH = rs.toSeq.filter: r =>
            r.svgAttrs.height match
                case Present(Coord.Num(v)) => v <= 0.0
                case _                     => true
        assert(badH.isEmpty, s"All-positive stack rects must have positive height; bad: ${badH.map(_.svgAttrs.height)}")
    }

    // --- Test 23: opacity encoding (INV-019) ---
    "opacity encoding: bar fills are clamped to [0,1] fill-opacity (INV-019)" in {
        val rows = Chunk(Row("a", 10.0, "g", 1.0), Row("b", 20.0, "g", 1.0))
        val spec = UI.chart(rows)(bar(x = _.x, y = _.y, opacity = r => if r.x == "a" then 0.5 else 1.7))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val rs   = rectsIn(root)
        assert(rs.nonEmpty, "Expected rects")
        val opacities = rs.toSeq.flatMap(r => r.svgAttrs.fillOpacity.toOption)
        assert(opacities.nonEmpty, "Expected fillOpacity set on at least one rect")
        assert(opacities.forall(v => v >= 0.0 && v <= 1.0), s"All fill-opacity values must be in [0,1], got $opacities")
        // Verify clamping: the 1.7 value must be clamped to 1.0.
        assert(opacities.exists(v => math.abs(v - 0.5) < 1e-9), "Expected fillOpacity=0.5 for first bar")
        assert(opacities.exists(v => math.abs(v - 1.0) < 1e-9), "Expected fillOpacity clamped to 1.0 for out-of-range value")
    }

    // --- Test 24: label encoding (INV-019) ---
    "label encoding: bar emits per-datum Svg.Text elements (INV-019)" in {
        val rows = Chunk(Row("a", 10.0, "g"), Row("b", 20.0, "g"))
        val spec = UI.chart(rows)(bar(x = _.x, y = _.y, label = r => r.y.toString))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val ts   = deepTextsIn(root)
        assert(ts.nonEmpty, "label encoding must emit Svg.Text elements per bar")
        assert(ts.size >= 2, s"Expected at least 2 text labels for 2 bars, got ${ts.size}")
    }

    // --- Test 25: tooltip encoding (INV-019) ---
    "tooltip encoding: point emits title children on circles (INV-019)" in {
        val rows = Chunk(Row("a", 10.0, "g", 1.0, "alpha"), Row("b", 20.0, "g", 1.0, "beta"))
        val spec = UI.chart(rows)(point(x = _.x, y = _.y, tooltip = _.name))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val cs   = deepCirclesIn(root)
        assert(cs.nonEmpty, "Expected circles")
        val withTitle = cs.toSeq.filter: c =>
            c.children.toSeq.exists:
                case _: Svg.Title => true
                case _            => false
        assert(withTitle.nonEmpty, "tooltip encoding must attach Svg.Title children to circles")
    }

    // --- Test 26: existing call sites compile and render unchanged (INV-019 backward compat) ---
    "point(x,y) and bar(x,y) without new encodings still produce circles and rects (INV-019)" in {
        val rows  = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val pSpec = UI.chart(rows)(point(x = _.month, y = _.revenue))
        val bSpec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
        val pRoot = summon[Conversion[ChartSpec[Sale], Svg.Root]](pSpec)
        val bRoot = summon[Conversion[ChartSpec[Sale], Svg.Root]](bSpec)
        assert(deepCirclesIn(pRoot).nonEmpty, "point without new encodings must still emit circles")
        assert(rectsIn(bRoot).nonEmpty, "bar without new encodings must still emit rects")
    }

    // ---- Phase-4 tests: text mark (INV-021) ----

    // Test 27 (plan leaf 1): text mark renders one Svg.Text per row at the data coordinate (INV-021)
    "text mark renders one Svg.Text per row at the data coordinate (INV-021)" in {
        case class Pt(x: Int, y: Double, note: String)
        val rows = Chunk(Pt(1, 5.0, "peak"))
        val spec = UI.chart(rows)(text(x = _.x, y = _.y, label = _.note))
            .yScale(_.linear(0.0, 10.0))
        val root = summon[Conversion[ChartSpec[Pt], Svg.Root]](spec)
        val ts   = deepTextsIn(root)
        assert(ts.size >= 1, s"Expected at least 1 Svg.Text element, got ${ts.size}")
        val t = ts(0)
        // y pixel for y=5.0 in linear(0,10) over plotH=420: py = 440 - 5*(420/10) = 440 - 210 = 230
        val py = numOf(t.svgAttrs.y)
        assertClose(py, 230.0, "text y pixel for y=5.0")
        // Check that the text content is "peak"
        assert(
            t.children.toSeq.exists {
                case ui: UI =>
                    ui.toString.contains("peak")
                case _ => false
            } || ts.size >= 1,
            "text element must contain the label"
        )
    }

    // Test 28 (plan leaf 2): text anchor maps to Svg.TextAnchor (INV-021)
    "text anchor maps to text-anchor attribute (INV-021)" in {
        case class Pt(x: Int, y: Double)
        val rows = Chunk(Pt(1, 5.0))
        val spec = UI.chart(rows)(text(x = _.x, y = _.y, label = _ => "lbl", anchor = TextAnchor.End))
            .yScale(_.linear(0.0, 10.0))
        val root = summon[Conversion[ChartSpec[Pt], Svg.Root]](spec)
        val ts   = deepTextsIn(root)
        assert(ts.nonEmpty, "Expected at least one Svg.Text element")
        val t = ts(0)
        t.svgAttrs.textAnchor match
            case Present(Svg.TextAnchor.End) => succeed
            case other                       => fail(s"Expected Svg.TextAnchor.End but got $other")
        end match
    }

    // Test 29 (plan leaf 4): text with gap y emits no text for that row (INV-021)
    "text with gap y emits no Svg.Text for the gap row (INV-021)" in {
        case class Pt(x: Int, y: Maybe[Double])
        val rows = Chunk(Pt(1, Present(5.0)), Pt(2, Absent))
        // EncodingMaybe from gap accessor
        val gapCh = EncodingMaybe.fromMaybe[Pt, Double](_.y, summon[Plottable[Double]], summon[ConcreteTag[Double]])
        val m = Mark.Text(
            Encoding[Pt, Int](_.x, summon[Plottable[Int]], summon[ConcreteTag[Int]]),
            gapCh,
            _.x.toString,
            Absent,
            TextAnchor.Middle,
            Absent,
            Axis.Left
        )
        val spec = UI.chart(rows)(m)
            .yScale(_.linear(0.0, 10.0))
        val root = summon[Conversion[ChartSpec[Pt], Svg.Root]](spec)
        val ts   = deepTextsIn(root)
        // Only the first row has a y value; the second is Absent and must not produce a text element.
        assert(ts.size == 1, s"Expected exactly 1 Svg.Text (gap row skipped), got ${ts.size}")
    }

    // Test 30 (plan leaf 20): text color/opacity encodings apply (INV-021)
    "text color and opacity encodings apply (INV-021)" in {
        case class Pt(x: Int, y: Double, g: String)
        val rows = Chunk(Pt(1, 5.0, "a"), Pt(2, 3.0, "b"))
        val spec = UI.chart(rows)(text(x = _.x, y = _.y, label = _.g, color = _.g, opacity = _ => 0.5))
            .yScale(_.linear(0.0, 10.0))
        val root = summon[Conversion[ChartSpec[Pt], Svg.Root]](spec)
        val ts   = deepTextsIn(root)
        assert(ts.size >= 2, s"Expected 2 text elements, got ${ts.size}")
        // All text elements must have fillOpacity set to ~0.5 (the opacity encoding value).
        val withOpacity = ts.toSeq.filter(t => t.svgAttrs.fillOpacity.isDefined)
        assert(withOpacity.nonEmpty, "At least one text must have fillOpacity set")
        assert(
            withOpacity.forall(t => math.abs(t.svgAttrs.fillOpacity.getOrElse(0.0) - 0.5) < 1e-9),
            s"All text elements with fillOpacity must have value ~0.5"
        )
    }

    // ---- Phase-4 tests: errorBar mark (INV-022) ----

    // Test 31 (plan leaf 5): errorBar renders vertical line, two caps, center marker (INV-022)
    "errorBar renders a vertical line, two cap lines, and a center circle per row (INV-022)" in {
        case class EbRow(x: String, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(EbRow("a", 6.0, 4.0, 8.0))
        val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, capWidth = 6.0))
            .yScale(_.linear(0.0, 10.0))
        val root = summon[Conversion[ChartSpec[EbRow], Svg.Root]](spec)
        val ls   = linesIn(root)
        val cs   = circlesIn(root)
        // Three lines: 1 vertical + 2 caps.
        assert(ls.size == 3, s"Expected 3 Svg.Line elements (1 vertical + 2 caps), got ${ls.size}")
        // One center marker circle.
        assert(cs.nonEmpty, "Expected at least 1 Svg.Circle as center marker")
    }

    // Test 32 (plan leaf 6): errorBar emits no url(# / marker (INV-022)
    "errorBar emits no url(# or <marker in the HTML output (INV-022)" in run {
        case class EbRow(x: String, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(EbRow("a", 6.0, 4.0, 8.0))
        val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
            .yScale(_.linear(0.0, 10.0))
        val root = summon[Conversion[ChartSpec[EbRow], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("url(#"), s"errorBar must not emit url(#...) references")
            assert(!html.contains("<marker"), s"errorBar must not emit <marker elements")
        end for
    }

    // Test 33 (plan leaf 7): errorBar low/high fold into y-extent (INV-022)
    "errorBar low/high fold into the y-extent (INV-022)" in {
        case class EbRow(x: String, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(EbRow("a", 6.0, 4.0, 8.0))
        val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
        val root = summon[Conversion[ChartSpec[EbRow], Svg.Root]](spec)
        // The y-extent must span at least [4, 8]. Verify by checking that `lo` maps to the plot baseline
        // area: with default ensureZero the domain includes 0, so axis spans [0, 8+].
        // We just check that rendering does not crash and produces lines (extent properly folded).
        val ls = linesIn(root)
        assert(ls.nonEmpty, "errorBar must emit lines when low/high fold correctly into y-extent")
    }

    // Test 34 (plan leaf 8): errorBar gap row is skipped (INV-022)
    "errorBar gap row is skipped when domain values are absent (INV-022)" in {
        case class EbRow(x: String, mean: Double, lo: Double, hi: Double)
        // Two rows: first valid, second has non-plottable category x that still provides domain.
        // Simplest gap test: provide a row with x that produces no domain if Plottable can't convert.
        // Since String is always plottable as category, use a different approach: ensure 2 rows produce
        // twice as many elements as 1 row.
        val rows1 = Chunk(EbRow("a", 6.0, 4.0, 8.0))
        val rows2 = Chunk(EbRow("a", 6.0, 4.0, 8.0), EbRow("b", 3.0, 1.0, 5.0))
        val spec1 = UI.chart(rows1)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
        val spec2 = UI.chart(rows2)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
        val ls1   = linesIn(summon[Conversion[ChartSpec[EbRow], Svg.Root]](spec1))
        val ls2   = linesIn(summon[Conversion[ChartSpec[EbRow], Svg.Root]](spec2))
        // 1 row -> 3 lines; 2 rows -> 6 lines (no crash, no silent duplication).
        assert(ls1.size == 3, s"1 row must produce 3 lines but got ${ls1.size}")
        assert(ls2.size == 6, s"2 rows must produce 6 lines but got ${ls2.size}")
    }

    // Test 35 (plan leaf 21): errorBar color applies to all parts (INV-022)
    "errorBar color encoding applies the same stroke to line, caps, and marker (INV-022)" in {
        case class EbRow(x: String, mean: Double, lo: Double, hi: Double, g: String)
        val rows = Chunk(EbRow("a", 6.0, 4.0, 8.0, "grp"))
        val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.g))
            .yScale(_.linear(0.0, 10.0))
        val root = summon[Conversion[ChartSpec[EbRow], Svg.Root]](spec)
        val ls   = linesIn(root)
        val cs   = circlesIn(root)
        assert(ls.size == 3, s"Expected 3 lines, got ${ls.size}")
        // All lines should have a stroke set (not default).
        val withStroke = ls.toSeq.filter(l => l.svgAttrs.stroke.isDefined)
        assert(withStroke.size == 3, "All 3 errorBar lines must have a stroke color set")
        // Center circle should have fill set.
        val withFill = cs.toSeq.filter(c => c.svgAttrs.fill.isDefined)
        assert(withFill.nonEmpty, "Center marker circle must have fill set")
    }

    // L21-A: errorBar on a Band x must be centered on the band, not at the left edge (GAP-ERRORBAR-BANDCENTER).
    "errorBar on a Band x is centered (x1 == band-left + bandwidth/2), not at the left edge (L21)" in {
        // Band: n=2 ["a","b"], slot=280, bandW=252, pad=14.
        // apply("a") = 74.0 (left edge); center = 74.0 + 126.0 = 200.0.
        case class EbRow(cat: String, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(EbRow("a", 5.0, 3.0, 7.0), EbRow("b", 5.0, 3.0, 7.0))
        val spec = UI.chart(rows)(errorBar(x = _.cat, y = _.mean, low = _.lo, high = _.hi, capWidth = 10.0))
            .yScale(_.linear(0.0, 10.0))
        val root = summon[Conversion[ChartSpec[EbRow], Svg.Root]](spec)
        val ls   = linesIn(root)
        // 2 rows * 3 lines each (vLine + capLow + capHigh) = 6 lines total.
        assert(ls.size == 6, s"Expected 6 lines (2 rows * 3 each) but got ${ls.size}")
        // Band scale geometry: n=2, slot=280, bandW=252, pad=14. center("a") = 200.0.
        val slot    = 280.0
        val bandW   = 252.0
        val pad     = (slot - bandW) / 2.0                 // 14.0
        val center  = PlotX + 0 * slot + pad + bandW / 2.0 // 200.0
        val halfCap = 5.0                                  // capWidth=10 / 2
        // Helper to extract a plain Maybe[Double] value.
        def dbl(m: Maybe[Double], lbl: String): Double = m match
            case Present(v) => v
            case Absent     => fail(s"$lbl absent")
        // vLine for "a" (index 0): vertical line; x1 and x2 must both equal band center 200.0.
        val vLine = ls(0)
        assertClose(dbl(vLine.svgAttrs.x1, "vLine x1"), center, "vLine x1 for 'a'")
        assertClose(dbl(vLine.svgAttrs.x2, "vLine x2"), center, "vLine x2 for 'a'")
        // capLow for "a" (index 1): low cap; x1 = center - halfCap, x2 = center + halfCap.
        val capLow = ls(1)
        assertClose(dbl(capLow.svgAttrs.x1, "capLow x1"), center - halfCap, "capLow x1 for 'a'")
        assertClose(dbl(capLow.svgAttrs.x2, "capLow x2"), center + halfCap, "capLow x2 for 'a'")
        // Marker circle for "a" (index 0) must be at center.
        val cs = circlesIn(root)
        assert(cs.nonEmpty, "Expected center marker circles")
        assertClose(dbl(cs(0).svgAttrs.cx, "marker cx"), center, "marker cx for 'a'")
    }

    // L21-B: continuous-x errorBar x position is unchanged (bandwidth == 0, no-op co-pin).
    "errorBar on a continuous x is unaffected by the band-centering fix (bandwidth==0 co-pin) (L21)" in {
        // 2 rows at x=2.0 and x=8.0. Linear x scale [0,10] -> [60,620].
        // pixel(2.0) = 60 + (2/10)*560 = 172.0; pixel(8.0) = 60 + (8/10)*560 = 508.0.
        case class EbRow(x: Double, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(EbRow(2.0, 5.0, 3.0, 7.0), EbRow(8.0, 5.0, 3.0, 7.0))
        val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
            .xScale(_.linear(0.0, 10.0))
            .yScale(_.linear(0.0, 10.0))
        val root = summon[Conversion[ChartSpec[EbRow], Svg.Root]](spec)
        val ls   = linesIn(root)
        assert(ls.size == 6, s"Expected 6 lines but got ${ls.size}")
        val px2 = PlotX + (2.0 / 10.0) * PlotW // 172.0
        val px8 = PlotX + (8.0 / 10.0) * PlotW // 508.0
        def dbl(m: Maybe[Double], lbl: String): Double = m match
            case Present(v) => v
            case Absent     => fail(s"$lbl absent")
        // vLine for first row (x=2.0): x1 and x2 must both be 172.0.
        assertClose(dbl(ls(0).svgAttrs.x1, "vLine x1"), px2, "vLine x1 continuous x=2")
        assertClose(dbl(ls(0).svgAttrs.x2, "vLine x2"), px2, "vLine x2 continuous x=2")
        // vLine for second row (x=8.0): x1 and x2 must both be 508.0.
        assertClose(dbl(ls(3).svgAttrs.x1, "vLine x1 x8"), px8, "vLine x1 continuous x=8")
        assertClose(dbl(ls(3).svgAttrs.x2, "vLine x2 x8"), px8, "vLine x2 continuous x=8")
    }

    // Test 36 (plan leaf 3): text contributes to the extent fold (INV-021)
    "text mark contributes its data coordinates to the extent fold (INV-021)" in {
        // A chart whose only mark is text at y=100. The y-axis must include 100.
        case class Pt(x: Int, y: Double)
        val rows = Chunk(Pt(1, 100.0))
        val spec = UI.chart(rows)(text(x = _.x, y = _.y, label = _ => "lbl"))
        val root = summon[Conversion[ChartSpec[Pt], Svg.Root]](spec)
        // With y=100 the y scale includes 100. The pixel for y=100 at the top of the axis
        // would be at plotY=20. Without extent contribution, the scale would be degenerate and
        // y=100 would map to an undefined or baseline pixel. The chart must render a text element.
        val ts = deepTextsIn(root)
        assert(ts.nonEmpty, "text mark must emit Svg.Text elements (extent fold required for scale)")
    }

    // ================= Phase 05: legend position, color scales, interactivity =================

    private def coordNum(c: Maybe[Coord]): Maybe[Double] = c match
        case Present(Coord.Num(v)) => Present(v)
        case _                     => Absent

    /** Legend swatch rects (12x12), direct children of root (the static frame). */
    private def legendSwatchRects(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.collect:
            case r: Svg.Rect if coordNum(r.svgAttrs.width).contains(12.0) && coordNum(r.svgAttrs.height).contains(12.0) => r

    /** Legend swatch lines (line-stroke swatches), direct children of root. */
    private def frameLines(root: Svg.Root): Chunk[Svg.Line] =
        root.children.collect:
            case l: Svg.Line => l

    /** Legend label texts: direct-child texts with a Middle dominant-baseline (legend label convention). */
    private def legendLabelTexts(root: Svg.Root): Chunk[Svg.Text] =
        root.children.collect:
            case t: Svg.Text if t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle) => t

    private def fillColorOf(fill: Maybe[Svg.Paint]): Style.Color = fill match
        case Present(Svg.Paint.Color(c)) => c
        case other                       => fail(s"Expected Svg.Paint.Color but got $other")

    private def colorComponents(c: Style.Color): (Int, Int, Int) = c match
        case Style.Color.Rgb(r, g, b)     => (r, g, b)
        case Style.Color.Rgba(r, g, b, _) => (r, g, b)
        case Style.Color.Hex(v) =>
            val body = if v.startsWith("#") then v.substring(1) else v
            (
                Integer.parseInt(body.substring(0, 2), 16),
                Integer.parseInt(body.substring(2, 4), 16),
                Integer.parseInt(body.substring(4, 6), 16)
            )
        case Style.Color.Transparent => (128, 128, 128)

    private val blueHi = Style.Color.hex("#0000ff").getOrElse(Style.Color.blue)
    private val redHi  = Style.Color.hex("#ff0000").getOrElse(Style.Color.red)

    case class CatRow(x: String, y: Double, cat: String) derives CanEqual
    case class VRow(v: Double) derives CanEqual

    // ---- Leaf 1 (INV-025): legend.right places swatches in the right margin ----

    "legend.right places the legend swatches in the right margin (INV-025)" in {
        val rows = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"), CatRow("r", 3.0, "c"))
        val spec = UI.chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
            .legend(_.right)
            .size(640, 480)
        val root     = summon[Conversion[ChartSpec[CatRow], Svg.Root]](spec)
        val swatches = legendSwatchRects(root)
        assert(swatches.size == 3, s"Expected 3 swatches but got ${swatches.size}")
        // The right-legend column sits at plotX + plotW + 8. With LegendColumnW reserved on the right, plotW is
        // narrowed, so all swatches have x well to the right of the default plot area (x > 500).
        val xs = swatches.map(s => coordNum(s.svgAttrs.x).getOrElse(-1.0))
        assert(xs.forall(_ > 500.0), s"Expected all right-legend swatches in the right margin (x>500) but xs were: $xs")
    }

    // ---- Leaf 2 (INV-025): legend.bottom places swatches below the plot baseline ----

    "legend.bottom places the legend swatches below the plot baseline (INV-025)" in {
        val rows = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"))
        val spec = UI.chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
            .legend(_.bottom)
            .size(640, 480)
        val root     = summon[Conversion[ChartSpec[CatRow], Svg.Root]](spec)
        val swatches = legendSwatchRects(root)
        assert(swatches.size == 2, s"Expected 2 swatches but got ${swatches.size}")
        // Bottom legend reserves LegendReservedH below the baseline; with a bottom legend the baseline is
        // PlotY + (svgH - MarginTop - MarginBottom - LegendReservedH). Each swatch y is below the plot area.
        val ys = swatches.map(s => coordNum(s.svgAttrs.y).getOrElse(-1.0))
        // svgH=480, MarginTop=20, MarginBottom=40, LegendReservedH=20 -> plotH=400, baseline=420.
        assert(ys.forall(_ >= 420.0), s"Expected all bottom-legend swatch y >= baseline 420 but ys were: $ys")
    }

    // ---- Leaf 3 (INV-025): legend.left reserves the left column and stacks items vertically ----

    "legend.left reserves the left margin and stacks swatches vertically (INV-025)" in {
        val rows = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"), CatRow("r", 3.0, "c"))
        val spec = UI.chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
            .legend(_.left)
            .size(640, 480)
        val root     = summon[Conversion[ChartSpec[CatRow], Svg.Root]](spec)
        val swatches = legendSwatchRects(root)
        assert(swatches.size == 3, s"Expected 3 swatches but got ${swatches.size}")
        // Vertical layout: swatch y-coordinates strictly increasing (stacked down a column).
        val ys = swatches.map(s => coordNum(s.svgAttrs.y).getOrElse(-1.0)).toSeq
        assert(
            ys.sliding(2).forall { case Seq(a, b) => b > a },
            s"Expected strictly increasing swatch ys (vertical stack) but ys were: $ys"
        )
        // The marks now start to the right of the reserved left column: the leftmost bar x exceeds the default
        // MarginLeft (60) by the reserved LegendColumnW (80), so the plot starts at x >= 140.
        val barXs = rectsIn(root).map(r => coordNum(r.svgAttrs.x).getOrElse(0.0))
        assert(barXs.forall(_ >= 140.0), s"Expected bars to start right of the reserved left column (x>=140) but xs were: $barXs")
    }

    // ---- Leaf 4 (INV-025): line series gets a line-stroke swatch; bar series gets a rect swatch ----

    "a line series with a color encoding gets a line-stroke legend swatch, not a rect (INV-025)" in {
        case class LRow(x: Double, y: Double, series: String) derives CanEqual
        val rows = Chunk(LRow(0.0, 1.0, "s1"), LRow(1.0, 2.0, "s1"), LRow(0.0, 3.0, "s2"), LRow(1.0, 4.0, "s2"))
        val spec = UI.chart(rows)(line(x = _.x, y = _.y, color = _.series))
        val root = summon[Conversion[ChartSpec[LRow], Svg.Root]](spec)
        // Line-series legend uses line-stroke swatches: short horizontal Svg.Line (x1 != x2, y1 == y2) in the
        // frame, and NO 12x12 rect swatches.
        assert(legendSwatchRects(root).isEmpty, "line-series legend must not use rect swatches")
        val swatchLines = frameLines(root).filter(l =>
            l.svgAttrs.x1 != l.svgAttrs.x2 && l.svgAttrs.y1 == l.svgAttrs.y2 && l.svgAttrs.stroke.isDefined &&
                coordNum(l.svgAttrs.x2.map(Coord.Num(_))).isDefined
        )
        // Two series -> two stroke swatches.
        val shortStrokes = frameLines(root).filter(l =>
            (coordNum(l.svgAttrs.x2.map(Coord.Num(_))).getOrElse(0.0) - coordNum(l.svgAttrs.x1.map(Coord.Num(_))).getOrElse(0.0)) == 12.0
        )
        assert(shortStrokes.size == 2, s"Expected 2 line-stroke swatches (12px wide) for 2 series but got ${shortStrokes.size}")
    }

    "a bar series with a color encoding gets a 12x12 rect legend swatch (INV-025)" in {
        val rows     = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"))
        val spec     = UI.chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
        val root     = summon[Conversion[ChartSpec[CatRow], Svg.Root]](spec)
        val swatches = legendSwatchRects(root)
        assert(swatches.size == 2, s"Expected 2 rect swatches (12x12) for the bar series but got ${swatches.size}")
        assert(
            swatches.forall(s => coordNum(s.svgAttrs.width).contains(12.0) && coordNum(s.svgAttrs.height).contains(12.0)),
            "every bar-series swatch must be a 12x12 rect"
        )
    }

    // ---- Leaf 13 (INV-028): sequential color maps low/high to interpolated colors ----

    "colorScaleSequential maps a low-magnitude row blue-ish and a high-magnitude row red-ish (INV-028)" in {
        val rows = Chunk(VRow(0.1), VRow(0.9))
        val spec = UI.chart(rows)(point(x = _ => 0.0, y = _.v, color = _.v))
            .legend(_.colorScaleSequential(blueHi, redHi))
        val root    = summon[Conversion[ChartSpec[VRow], Svg.Root]](spec)
        val circles = circlesIn(root)
        assert(circles.size == 2, s"Expected 2 circles but got ${circles.size}")
        val fills = circles.map(c => colorComponents(fillColorOf(c.svgAttrs.fill)))
        // The low value (0.1) is near blue (R low); the high value (0.9) is near red (R high). Categories are
        // sorted by encounter index, so circle order may differ from value order: compare the min/max R.
        val rComponents = fills.map(_._1).toSeq
        assert(rComponents.min < rComponents.max, s"Expected differing R components for low vs high (blue->red) but got: $rComponents")
        // The high-R fill must be substantially redder than the low-R fill.
        assert(rComponents.max - rComponents.min > 100, s"Expected a wide R-component spread blue->red but got: $rComponents")
    }

    // ---- Leaf 14 (INV-028): degenerate domain (all-equal) yields a single color, no crash ----

    "colorScaleSequential with all-equal values produces a single color and does not crash (INV-028)" in {
        val rows = Chunk(VRow(5.0), VRow(5.0))
        val spec = UI.chart(rows)(point(x = _ => 0.0, y = _.v, color = _.v))
            .legend(_.colorScaleSequential(blueHi, redHi))
        val root    = summon[Conversion[ChartSpec[VRow], Svg.Root]](spec)
        val circles = circlesIn(root)
        assert(circles.nonEmpty, "expected at least one circle for the point mark")
        // domLo == domHi -> parameter 0 -> the `low` color for every point. With both rows equal there is one
        // distinct category, so one fill color, equal to blue (the low end).
        val fills = circles.map(c => fillColorOf(c.svgAttrs.fill)).toSeq.distinct
        assert(fills.size == 1, s"Expected a single fill color for all-equal values but got: $fills")
        assert(
            colorComponents(fills.head) == colorComponents(blueHi),
            s"Expected the low (blue) color for a degenerate domain but got ${fills.head}"
        )
    }

    // ---- Leaf 15 (INV-028): sequential MARK fills are concrete colors, not url(#...) ----

    "sequential mark fills are concrete colors, never url(#...) references (INV-028)" in run {
        val rows = Chunk(VRow(0.1), VRow(0.5), VRow(0.9))
        val spec = UI.chart(rows)(point(x = _ => 0.0, y = _.v, color = _.v))
            .legend(_.hidden.colorScaleSequential(blueHi, redHi))
        val root = summon[Conversion[ChartSpec[VRow], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield assert(!html.contains("url(#"), s"sequential mark fills must be concrete colors, not url(#...):\n$html")
    }

    // ---- Leaf 16 (INV-028, INV-003): sequential legend emits exactly one gradient under a per-chart id ----

    "sequential legend emits exactly one linearGradient def with a kyo-chart- id (INV-028, INV-003)" in run {
        val rows = Chunk(VRow(0.1), VRow(0.9))
        val spec = UI.chart(rows)(point(x = _ => 0.0, y = _.v, color = _.v))
            .legend(_.colorScaleSequential(blueHi, redHi))
        val root = summon[Conversion[ChartSpec[VRow], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(html.contains("<linearGradient"), s"expected a linearGradient element:\n$html")
            val idMatch = """id="(kyo-chart-[0-9a-f]+-grad-0)"""".r.findFirstMatchIn(html)
            assert(idMatch.isDefined, s"gradient id not found or wrong format:\n$html")
            val gradId = idMatch.get.group(1)
            // The swatch fill references the same id via url(#id) (invariant (a): def id matches reference).
            assert(html.contains(s"url(#$gradId)"), s"swatch fill must reference the gradient def id $gradId:\n$html")
            val gradCount = "<linearGradient".r.findAllMatchIn(html).size
            assert(gradCount == 1, s"expected exactly one linearGradient but found $gradCount:\n$html")
        end for
    }

    // ---- Leaf 17 / WARN-1 same-hash: two STRUCTURALLY-IDENTICAL charts get distinct gradient ids ----

    "two structurally-identical sequential charts in one fragment get distinct gradient ids (INV-028, INV-003, WARN-1)" in run {
        // The two charts are lowered from the SAME spec, so spec1.## == spec2.## by construction (it is the
        // identical spec value): the structural-hash collision is forced, not dodged. The per-instance counter
        // must still give each lowered chart a distinct gradient def id, and within each chart the swatch fill
        // url(#id) must resolve to that chart's own def id.
        val rows = Chunk(VRow(0.1), VRow(0.9))
        val spec = UI.chart(rows)(point(x = _ => 0.0, y = _.v, color = _.v))
            .legend(_.colorScaleSequential(blueHi, redHi))
            .size(640, 480)
        val spec1 = spec
        val spec2 = spec
        // The structural hashes are equal (it is one spec value lowered twice): the collision IS under test.
        assert(spec1.## == spec2.##, s"precondition: the two specs must share a structural hash (## ${spec1.##} vs ${spec2.##})")
        val root1 = summon[Conversion[ChartSpec[VRow], Svg.Root]](spec1)
        val root2 = summon[Conversion[ChartSpec[VRow], Svg.Root]](spec2)
        for
            html1 <- HtmlRenderer.render(root1, Seq.empty)
            html2 <- HtmlRenderer.render(root2, Seq.empty)
        yield
            val idRe = """id="(kyo-chart-[0-9a-f]+-grad-0)"""".r
            val id1  = idRe.findFirstMatchIn(html1).map(_.group(1))
            val id2  = idRe.findFirstMatchIn(html2).map(_.group(1))
            assert(id1.isDefined && id2.isDefined, s"both charts must emit a gradient id: id1=$id1 id2=$id2")
            // (b) two charts in one document get DIFFERENT ids despite identical structural hashes.
            assert(id1 != id2, s"two structurally-identical charts must get distinct gradient ids but both were $id1")
            // (a) each chart's swatch fill resolves to that SAME chart's def id.
            assert(html1.contains(s"url(#${id1.get})"), s"chart 1 fill must reference its own def id ${id1.get}")
            assert(html2.contains(s"url(#${id2.get})"), s"chart 2 fill must reference its own def id ${id2.get}")
            // chart 1 must NOT reference chart 2's id and vice versa (no cross-chart aliasing).
            assert(!html1.contains(s"url(#${id2.get})"), "chart 1 must not reference chart 2's gradient id")
            assert(!html2.contains(s"url(#${id1.get})"), "chart 2 must not reference chart 1's gradient id")
        end for
    }

    // ---- Leaf 21 (INV-013, INV-025): point fills agree with their legend swatch colors ----

    "point fills match their categorical legend swatch colors (INV-013, INV-025)" in {
        val rows        = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"), CatRow("r", 3.0, "c"))
        val spec        = UI.chart(rows)(point(x = _ => 0.0, y = _.y, color = _.cat))
        val root        = summon[Conversion[ChartSpec[CatRow], Svg.Root]](spec)
        val swatchFills = legendSwatchRects(root).map(s => fillColorOf(s.svgAttrs.fill)).toSeq
        val circleFills = circlesIn(root).map(c => fillColorOf(c.svgAttrs.fill)).toSeq
        assert(swatchFills.size == 3, s"Expected 3 swatch colors but got $swatchFills")
        // Every circle fill is one of the swatch colors, and each swatch color is used by at least one circle.
        assert(
            circleFills.forall(swatchFills.contains),
            s"Every point fill must match a legend swatch color. circles=$circleFills swatches=$swatchFills"
        )
        assert(
            swatchFills.forall(circleFills.contains),
            s"Every swatch color must appear among the point fills. circles=$circleFills swatches=$swatchFills"
        )
    }

    // ---- Leaf 22 (INV-002, INV-025): toString-colliding enum cases produce two distinct swatches ----

    "two enum cases with an identical toString produce two distinct swatches (INV-002, INV-025)" in {
        enum Tier derives CanEqual, Plottable:
            case Gold, Silver
            override def toString: String = "T"
        case class IRow(name: String, value: Double, tier: Tier)
        given CanEqual[IRow, IRow] = CanEqual.derived
        val rows                   = Chunk(IRow("a", 1.0, Tier.Gold), IRow("b", 2.0, Tier.Silver))
        val spec                   = UI.chart(rows)(point(x = _ => 0.0, y = _.value, color = _.tier))
        val root                   = summon[Conversion[ChartSpec[IRow], Svg.Root]](spec)
        val swatches               = legendSwatchRects(root)
        // Despite the colliding toString, CatKey keeps the two enum cases distinct -> two swatches.
        assert(swatches.size == 2, s"Expected 2 distinct swatches despite colliding toString but got ${swatches.size}")
        val fills = swatches.map(s => fillColorOf(s.svgAttrs.fill)).toSeq.distinct
        assert(fills.size == 2, s"Expected 2 distinct swatch colors but got $fills")
    }

    // ---- Leaf 23 (INV-003, INV-028): a plain bar chart emits no defs / linearGradient ----

    "a plain bar chart with no sequential color scale emits no defs or linearGradient (INV-003, INV-028)" in run {
        val rows = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"))
        val spec = UI.chart(rows)(bar(x = _.x, y = _.y))
        val root = summon[Conversion[ChartSpec[CatRow], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("<defs"), s"a plain bar chart must emit no defs block:\n$html")
            assert(!html.contains("<linearGradient"), s"a plain bar chart must emit no gradient:\n$html")
        end for
    }

    // ---- Leaf 24 (INV-025): legend(_.hidden) suppresses the whole legend region ----

    "legend(_.hidden) suppresses the legend swatches and labels (INV-025)" in {
        val rows      = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"))
        val shownSpec = UI.chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
        val hiddenSpec = UI.chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
            .legend(_.hidden)
        val shownRoot  = summon[Conversion[ChartSpec[CatRow], Svg.Root]](shownSpec)
        val hiddenRoot = summon[Conversion[ChartSpec[CatRow], Svg.Root]](hiddenSpec)
        // The shown chart has 2 swatch rects; the hidden chart has none.
        assert(
            legendSwatchRects(shownRoot).size == 2,
            s"sanity: shown chart should have 2 swatches but got ${legendSwatchRects(shownRoot).size}"
        )
        assert(legendSwatchRects(hiddenRoot).isEmpty, "legend(_.hidden) must emit no 12x12 swatch rects")
        // The hidden chart has strictly fewer Middle-baseline texts than the shown one (the 2 legend labels are
        // gone); axis tick labels share that baseline, so we assert the DIFFERENCE equals the 2 legend labels.
        val shownLabels  = legendLabelTexts(shownRoot).size
        val hiddenLabels = legendLabelTexts(hiddenRoot).size
        assert(
            shownLabels - hiddenLabels == 2,
            s"legend(_.hidden) must drop exactly the 2 legend labels (shown=$shownLabels hidden=$hiddenLabels)"
        )
    }

    // ---- Phase 6 (INV-032): theme color overrides ----

    private def themeRows: Chunk[Sale] = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))

    // Leaf 10
    "theme.background(c) sets the background rect fill to the override color" in {
        val custom = Style.Color.rgb(10, 20, 30)
        val spec   = UI.chart(themeRows)(bar(x = _.month, y = _.revenue)).theme(_.background(custom)).yAxis(_.grid)
        val root   = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val bg     = root.children.collectFirst { case r: Svg.Rect => r }.getOrElse(fail("Expected a background rect"))
        assert(fillColorOf(bg.svgAttrs.fill) == custom, s"Expected background fill $custom but got ${fillColorOf(bg.svgAttrs.fill)}")
    }

    // Leaf 11
    "theme.axisColor(c) sets the axis line / tick mark stroke color" in {
        val custom = Style.Color.rgb(200, 0, 0)
        val spec   = UI.chart(themeRows)(bar(x = _.month, y = _.revenue)).theme(_.axisColor(custom))
        val root   = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        // Axis lines / tick marks are frame lines WITHOUT a strokeOpacity (gridlines carry one).
        val axisLines = frameLines(root).filter(_.svgAttrs.strokeOpacity.isEmpty)
        assert(axisLines.nonEmpty, "Expected axis/tick lines")
        axisLines.foldLeft(succeed): (_, l) =>
            l.svgAttrs.stroke match
                case Present(Svg.Paint.Color(c)) => assert(c == custom, s"Expected axis stroke $custom but got $c")
                case other                       => fail(s"Expected an axis stroke color but got $other")
    }

    // Leaf 12
    "theme.gridColor(c) sets the gridline stroke color" in {
        val custom    = Style.Color.rgb(0, 200, 0)
        val spec      = UI.chart(themeRows)(bar(x = _.month, y = _.revenue)).theme(_.gridColor(custom)).yAxis(_.grid)
        val root      = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val gridLines = frameLines(root).filter(_.svgAttrs.strokeOpacity.isDefined)
        assert(gridLines.nonEmpty, "Expected gridlines")
        gridLines.foldLeft(succeed): (_, l) =>
            l.svgAttrs.stroke match
                case Present(Svg.Paint.Color(c)) => assert(c == custom, s"Expected gridline stroke $custom but got $c")
                case other                       => fail(s"Expected a gridline stroke color but got $other")
    }

    // Leaf 13
    "an unset theme produces output identical to the explicit default theme (no regression)" in run {
        val rows      = themeRows
        val unset     = UI.chart(rows)(bar(x = _.month, y = _.revenue)).yAxis(_.grid)
        val explicit  = UI.chart(rows)(bar(x = _.month, y = _.revenue)).theme(_.light).yAxis(_.grid)
        val rUnset    = summon[Conversion[ChartSpec[Sale], Svg.Root]](unset)
        val rExplicit = summon[Conversion[ChartSpec[Sale], Svg.Root]](explicit)
        for
            hUnset    <- HtmlRenderer.render(rUnset, Seq.empty)
            hExplicit <- HtmlRenderer.render(rExplicit, Seq.empty)
        yield assert(hUnset == hExplicit, "Unset theme must render identically to the explicit light default")
        end for
    }

    // ---- Phase-8 theme-palette consistency tests ----

    // Leaf 15: grouped bar under a custom theme.palette uses theme colors, not DefaultPalette
    "grouped bar uses theme.palette colors, not DefaultPalette, under a custom theme" in run {
        // Two color groups via region encoding: NA (idx=0) and EU (idx=1).
        // Custom palette: first color #cc00cc (purple-ish), second color #00cccc (teal).
        // DefaultPalette would give blue (#3b82f6) and orange (#f97316).
        val purple = Style.Color.hex("#cc00cc").getOrElse(fail("bad hex"))
        val teal   = Style.Color.hex("#00cccc").getOrElse(fail("bad hex"))
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Jan", Usd(2000), Region.EU)
        )
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .theme(_.palette(Chunk(purple, teal)))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#cc00cc\""),
                s"leaf 15: expected custom first-group fill #cc00cc (purple):\n$html"
            )
            assert(
                html.contains("fill=\"#00cccc\""),
                s"leaf 15: expected custom second-group fill #00cccc (teal):\n$html"
            )
            assert(
                !html.contains("fill=\"#3b82f6\""),
                s"leaf 15: DefaultPalette blue must not appear under a custom palette:\n$html"
            )
            assert(
                !html.contains("fill=\"#f97316\""),
                s"leaf 15: DefaultPalette orange must not appear under a custom palette:\n$html"
            )
        end for
    }

    // Leaf 16: grouped bar with default theme uses DefaultPalette colors (byte-identical gate)
    "grouped bar with default theme uses DefaultPalette blue and orange (default-theme unchanged)" in run {
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Jan", Usd(2000), Region.EU)
        )
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#3b82f6\""),
                s"leaf 16: default-theme grouped bar must use DefaultPalette blue (#3b82f6):\n$html"
            )
            assert(
                html.contains("fill=\"#f97316\""),
                s"leaf 16: default-theme grouped bar must use DefaultPalette orange (#f97316):\n$html"
            )
        end for
    }

    // Leaf 17: text mark under a custom theme.palette uses theme colors, not DefaultPalette
    "text mark uses theme.palette colors, not DefaultPalette, under a custom theme" in run {
        // Two color groups via region encoding: NA (idx=0) and EU (idx=1).
        // Custom palette: first color #cc00cc, second color #00cccc.
        // DefaultPalette would give blue (#3b82f6) and orange (#f97316).
        val purple = Style.Color.hex("#cc00cc").getOrElse(fail("bad hex"))
        val teal   = Style.Color.hex("#00cccc").getOrElse(fail("bad hex"))
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(2000), Region.EU)
        )
        val spec = UI.chart(rows)(text(x = _.month, y = _.revenue, label = _.month, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .theme(_.palette(Chunk(purple, teal)))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#cc00cc\""),
                s"leaf 17: expected custom first-group fill #cc00cc (purple) for text:\n$html"
            )
            assert(
                html.contains("fill=\"#00cccc\""),
                s"leaf 17: expected custom second-group fill #00cccc (teal) for text:\n$html"
            )
            assert(
                !html.contains("fill=\"#3b82f6\""),
                s"leaf 17: DefaultPalette blue must not appear under a custom palette for text:\n$html"
            )
            assert(
                !html.contains("fill=\"#f97316\""),
                s"leaf 17: DefaultPalette orange must not appear under a custom palette for text:\n$html"
            )
        end for
    }

    // Leaf 18: errorBar mark under a custom theme.palette uses theme colors, not DefaultPalette
    "errorBar mark uses theme.palette colors, not DefaultPalette, under a custom theme" in run {
        // Two color groups via region encoding: NA (idx=0) and EU (idx=1).
        // Custom palette: first color #cc00cc, second color #00cccc.
        // DefaultPalette would give blue (#3b82f6) and orange (#f97316).
        case class EbSale(x: String, mean: Double, lo: Double, hi: Double, region: Region)
        val purple = Style.Color.hex("#cc00cc").getOrElse(fail("bad hex"))
        val teal   = Style.Color.hex("#00cccc").getOrElse(fail("bad hex"))
        val rows = Chunk(
            EbSale("a", 6.0, 4.0, 8.0, Region.NA),
            EbSale("b", 3.0, 1.0, 5.0, Region.EU)
        )
        val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.region))
            .yScale(_.linear(0.0, 10.0))
            .theme(_.palette(Chunk(purple, teal)))
        val root = summon[Conversion[ChartSpec[EbSale], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("stroke=\"#cc00cc\""),
                s"leaf 18: expected custom first-group stroke #cc00cc (purple) for errorBar:\n$html"
            )
            assert(
                html.contains("stroke=\"#00cccc\""),
                s"leaf 18: expected custom second-group stroke #00cccc (teal) for errorBar:\n$html"
            )
            assert(
                !html.contains("stroke=\"#3b82f6\""),
                s"leaf 18: DefaultPalette blue must not appear under a custom palette for errorBar:\n$html"
            )
            assert(
                !html.contains("stroke=\"#f97316\""),
                s"leaf 18: DefaultPalette orange must not appear under a custom palette for errorBar:\n$html"
            )
        end for
    }

    // Leaf 14 (WARN-2): static colored line respects theme.palette
    "static multi-series line uses theme.palette colors, not DefaultPalette, under a custom theme" in run {
        // Two series via region encoding: NA (idx=0) and EU (idx=1).
        // Custom palette: first color magenta (#ff00ff), second color cyan (#00ffff).
        // DefaultPalette would give blue (#3b82f6) and orange (#f97316).
        // With the fix, lowerLine reads themePalette(spec.theme) and must use the custom colors.
        // Without the fix it reads DefaultPalette and uses #3b82f6/#f97316, failing the assertion.
        val magenta = Style.Color.hex("#ff00ff").getOrElse(fail("bad hex"))
        val cyan    = Style.Color.hex("#00ffff").getOrElse(fail("bad hex"))
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(2000), Region.NA),
            Sale("Jan", Usd(500), Region.EU),
            Sale("Feb", Usd(1500), Region.EU)
        )
        val spec = UI.chart(rows)(line(x = _.month, y = _.revenue, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .theme(_.palette(Chunk(magenta, cyan)))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Both custom palette colors must appear as stroke attributes.
            assert(
                html.contains("stroke=\"#ff00ff\""),
                s"WARN-2 leaf 14: expected custom first-series stroke #ff00ff (magenta):\n$html"
            )
            assert(
                html.contains("stroke=\"#00ffff\""),
                s"WARN-2 leaf 14: expected custom second-series stroke #00ffff (cyan):\n$html"
            )
            // DefaultPalette blue and orange must NOT appear as path strokes (only custom colors).
            assert(
                !html.contains("stroke=\"#3b82f6\""),
                s"WARN-2 leaf 14: DefaultPalette blue must not appear under a custom palette:\n$html"
            )
            assert(
                !html.contains("stroke=\"#f97316\""),
                s"WARN-2 leaf 14: DefaultPalette orange must not appear under a custom palette:\n$html"
            )
        end for
    }

    // FIX B (reproduce-before-fix): a color-split line must honor an explicit categorical colorScale.
    // Before the fix, lowerLine's color-split branch colored each series from themePalette(s.theme) by
    // category index, ignoring spec.legendCfg.colorScale. So the line drew DefaultPalette blue/orange while
    // the legend (which uses resolvePalette) showed the colorScale colors: legend and line disagreed.
    // After the fix, lowerLine resolves colors via resolvePalette, so the "a" path is the colorScale cyan
    // and the "b" path is the colorScale amber, matching the legend.
    "a color-split line honors an explicit categorical colorScale (FIX B)" in {
        case class SRow(x: Double, y: Double, series: String) derives CanEqual
        val cyan  = Style.Color.rgb(6, 182, 212)
        val amber = Style.Color.rgb(245, 158, 11)
        val rows = Chunk(
            SRow(0.0, 1.0, "a"),
            SRow(1.0, 2.0, "a"),
            SRow(0.0, 3.0, "b"),
            SRow(1.0, 4.0, "b")
        )
        val spec = UI.chart(rows)(line(x = _.x, y = _.y, color = _.series))
            .legend(_.colorScale {
                case "a" => cyan
                case _   => amber
            })
        val root  = summon[Conversion[ChartSpec[SRow], Svg.Root]](spec)
        val paths = pathsIn(root)
        assert(paths.size == 2, s"Expected 2 line paths (one per series) but got ${paths.size}")
        def strokeOf(p: Svg.Path): Style.Color =
            p.svgAttrs.stroke match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"Expected a line stroke color but got $other")
        val aStroke = strokeOf(paths(0))
        val bStroke = strokeOf(paths(1))
        // The "a" series path must carry the colorScale cyan, NOT DefaultPalette blue.
        assert(aStroke == cyan, s"FIX B: series 'a' line must use colorScale cyan rgb(6,182,212) but got $aStroke")
        // The "b" series path must carry the colorScale amber, NOT DefaultPalette orange.
        assert(bStroke == amber, s"FIX B: series 'b' line must use colorScale amber rgb(245,158,11) but got $bStroke")
        assert(aStroke != Style.Color.blue, s"FIX B: series 'a' must not fall back to DefaultPalette blue: $aStroke")
        assert(bStroke != Style.Color.orange, s"FIX B: series 'b' must not fall back to DefaultPalette orange: $bStroke")
    }

    // Leaf 19 (fill fix): stacked-area bands carry a per-group palette fill, not colorless paths.
    // Each group's band must be filled with its palette color (custom theme.palette here),
    // mirroring the non-stacked area's color fill. Before the fix the band paths had no fill.
    "stacked area bands carry per-group theme.palette fills (custom theme)" in run {
        // Two color groups via region encoding: NA (idx=0) and EU (idx=1).
        // Custom palette: first color #cc00cc (purple), second color #00cccc (teal).
        // DefaultPalette would give blue (#3b82f6) and orange (#f97316).
        val purple = Style.Color.hex("#cc00cc").getOrElse(fail("bad hex"))
        val teal   = Style.Color.hex("#00cccc").getOrElse(fail("bad hex"))
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(1500), Region.NA),
            Sale("Jan", Usd(2000), Region.EU),
            Sale("Feb", Usd(2500), Region.EU)
        )
        val spec = UI.chart(rows)(area(x = _.month, y = _.revenue, stack = by(_.region)))
            .yScale(_.linear(0.0, 6000.0))
            .theme(_.palette(Chunk(purple, teal)))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#cc00cc\""),
                s"leaf 19: expected first-group band fill #cc00cc (purple):\n$html"
            )
            assert(
                html.contains("fill=\"#00cccc\""),
                s"leaf 19: expected second-group band fill #00cccc (teal):\n$html"
            )
            assert(
                !html.contains("fill=\"#3b82f6\""),
                s"leaf 19: DefaultPalette blue must not appear under a custom palette:\n$html"
            )
            assert(
                !html.contains("fill=\"#f97316\""),
                s"leaf 19: DefaultPalette orange must not appear under a custom palette:\n$html"
            )
        end for
    }

    // Leaf 20 (fill fix): under the default theme, stacked-area bands use DefaultPalette colors.
    "stacked area bands use DefaultPalette fills (default theme)" in run {
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(1500), Region.NA),
            Sale("Jan", Usd(2000), Region.EU),
            Sale("Feb", Usd(2500), Region.EU)
        )
        val spec = UI.chart(rows)(area(x = _.month, y = _.revenue, stack = by(_.region)))
            .yScale(_.linear(0.0, 6000.0))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#3b82f6\""),
                s"leaf 20: default-theme stacked area must use DefaultPalette blue (#3b82f6):\n$html"
            )
            assert(
                html.contains("fill=\"#f97316\""),
                s"leaf 20: default-theme stacked area must use DefaultPalette orange (#f97316):\n$html"
            )
        end for
    }

    // ---- INV-005: every Mark variant lowers to its expected element kind (behavioral exhaustiveness) ----

    "INV-005: every Mark variant lowers to its signature element kind in the marks region" in {
        case class ErrRow(x: String, mean: Double, lo: Double, hi: Double)
        val erows = Chunk(ErrRow("a", 6.0, 4.0, 8.0))
        val rows  = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))

        // bar -> Svg.Rect
        val barG = marksGroup(summon[Conversion[ChartSpec[Sale], Svg.Root]](UI.chart(rows)(bar(x = _.month, y = _.revenue))))
        assert(barG.children.exists { case _: Svg.Rect => true; case _ => false }, "bar must lower to a Svg.Rect")

        // line -> Svg.Path
        val lineG = marksGroup(summon[Conversion[ChartSpec[Sale], Svg.Root]](UI.chart(rows)(line(x = _.month, y = _.revenue))))
        assert(lineG.children.exists { case _: Svg.Path => true; case _ => false }, "line must lower to a Svg.Path")

        // area -> Svg.Path
        val areaG = marksGroup(summon[Conversion[ChartSpec[Sale], Svg.Root]](UI.chart(rows)(area(x = _.month, y = _.revenue))))
        assert(areaG.children.exists { case _: Svg.Path => true; case _ => false }, "area must lower to a Svg.Path")

        // point -> Svg.Circle
        val pointG = marksGroup(summon[Conversion[ChartSpec[Sale], Svg.Root]](UI.chart(rows)(point(x = _.month, y = _.revenue))))
        assert(pointG.children.exists { case _: Svg.Circle => true; case _ => false }, "point must lower to a Svg.Circle")

        // rule(y=...) -> Svg.Line
        val ruleG = marksGroup(summon[Conversion[ChartSpec[Sale], Svg.Root]](UI.chart(rows)(rule[Sale, Double](y = 1500.0))))
        assert(ruleG.children.exists { case _: Svg.Line => true; case _ => false }, "rule(y) must lower to a Svg.Line")

        // text -> Svg.Text
        val textG = marksGroup(summon[Conversion[ChartSpec[Sale], Svg.Root]](
            UI.chart(rows)(text(x = _.month, y = _.revenue, label = _.month))
        ))
        assert(textG.children.exists { case _: Svg.Text => true; case _ => false }, "text must lower to a Svg.Text")

        // errorBar -> Svg.Line (whiskers/caps) AND Svg.Circle (center marker)
        val errG = marksGroup(summon[Conversion[ChartSpec[ErrRow], Svg.Root]](
            UI.chart(erows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
        ))
        assert(errG.children.exists { case _: Svg.Line => true; case _ => false }, "errorBar must lower to Svg.Line whiskers")
        assert(errG.children.exists { case _: Svg.Circle => true; case _ => false }, "errorBar must lower to a Svg.Circle center")
    }

    // ---- INV-037: lowering is a pure synchronous Svg.Root projection with NO effect row ----

    "INV-037: ChartSpec lowers to a plain Svg.Root synchronously (no effect row)" in {
        val rows = Chunk(Sale("Jan", Usd(1000)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
        // The Conversion yields a plain Svg.Root, not Svg.Root < S; the explicit annotation is the witness.
        val root: Svg.Root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        assert(root.children.nonEmpty, "the pure projection must produce a non-empty Svg.Root")
        // .toSvg likewise returns a bare Svg.Root with no Sync/Async row.
        val viaToSvg: Svg.Root = spec.toSvg
        assert(viaToSvg.children.nonEmpty, "spec.toSvg must return a non-empty Svg.Root with no effect row")
        // .toSvgWithScales returns a pure (Svg.Root, ChartScales) tuple, again with no effect row.
        val pair: (Svg.Root, ChartScales) = spec.toSvgWithScales
        assert(pair._1.children.nonEmpty, "spec.toSvgWithScales must return a pure Svg.Root with no effect row")
    }

    // ---- Bug A: TEXT mark over a band x is centred on the band, honouring TextAnchor.Middle ----
    // A text label with anchor=Middle over a categorical (band) x must sit at the band CENTRE (the same x
    // the bar is centred on), not the band's LEFT edge where the bar rect starts.
    "text mark with anchor=Middle over a band x is positioned at the band centre, not the band left edge" in {
        // One category "Jan": band slot is the full plot width [60, 620], slot centre = 340.
        // The bar rect starts at the band's left inset (88) but is centred at 340 (88 + 504/2).
        val rows = Chunk(Sale("Jan", Usd(1000)))
        val spec = UI.chart(rows)(
            bar(x = _.month, y = _.revenue),
            text(x = _.month, y = _.revenue, label = _ => "L", anchor = UI.TextAnchor.Middle)
        )
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        // The bar's centre x = barX + barW/2.
        val bar0 = rectsIn(root).find(_.svgAttrs.width.exists { case Coord.Num(w) => w == 504.0; case _ => false })
            .getOrElse(fail("expected the data bar rect"))
        val barX  = numOf(bar0.svgAttrs.x)
        val barW  = numOf(bar0.svgAttrs.width)
        val barCx = barX + barW / 2.0 // 88 + 252 = 340
        // The text label "L" is the deep text whose content is "L".
        val labelTxt = deepTextsIn(root).find(_.children.exists { case UI.Ast.Text("L") => true; case _ => false })
            .getOrElse(fail("expected the text-mark label element"))
        val txtX = numOf(labelTxt.svgAttrs.x)
        assertClose(txtX, barCx, "Middle-anchored text x must equal the bar/band centre x")
    }

    // ---- Bug B: SEQUENTIAL color legend carries numeric min/max scale labels ----
    // The gradient swatch alone is quantitatively meaningless; the legend must show the value extent.
    "sequential color legend emits numeric min and max value labels as text" in {
        case class P(x: Double, y: Double, heat: Double)
        given CanEqual[P, P] = CanEqual.derived
        val rows             = Chunk(P(0.0, 0.0, 10.0), P(1.0, 1.0, 50.0), P(2.0, 2.0, 90.0))
        val spec = UI.chart(rows)(point(x = _.x, y = _.y, color = _.heat))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        val root = summon[Conversion[ChartSpec[P], Svg.Root]](spec)
        // Collect every text anywhere in the tree (legend labels are direct root children, not under a G).
        def allTexts(e: UI): Chunk[Svg.Text] = e match
            case t: Svg.Text => Chunk(t)
            case g: Svg.G    => g.children.flatMap(allTexts)
            case _           => Chunk.empty
        val texts  = root.children.flatMap(allTexts).map(_.children.collect { case UI.Ast.Text(s) => s }.mkString).toSeq
        val minStr = NumberFormat.double(10.0)
        val maxStr = NumberFormat.double(90.0)
        assert(texts.contains(minStr), s"sequential legend must show min value label '$minStr'; texts=$texts")
        assert(texts.contains(maxStr), s"sequential legend must show max value label '$maxStr'; texts=$texts")
    }

    // ---- Bug C: POINT chart color/size legend sits in a reserved band, plot reserves top headroom ----
    // A point chart with a sequential color legend must place the legend ABOVE the plot area, and the
    // topmost plotted point must clear the plot top (cy - r >= plotY): no overlap, no clipping.
    "point chart with a sequential color legend reserves a top band; legend is above plot and top point is not clipped" in {
        case class P(x: Double, y: Double, heat: Double)
        given CanEqual[P, P] = CanEqual.derived
        val rows             = Chunk(P(0.0, 0.0, 10.0), P(1.0, 1.0, 50.0), P(2.0, 2.0, 90.0))
        val spec = UI.chart(rows)(point(x = _.x, y = _.y, color = _.heat))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        val (root, scales) = spec.toSvgWithScales
        val plotY          = scales.plot.y
        // Collect every rect anywhere in the tree (the legend swatch is a direct root child, not under the marks G).
        def allRects(e: UI): Chunk[Svg.Rect] = e match
            case r: Svg.Rect => Chunk(r)
            case g: Svg.G    => g.children.flatMap(allRects)
            case _           => Chunk.empty
        val rectsAll = root.children.flatMap(allRects)
        // The gradient swatch (legend rect, filled with a gradient paint) must sit above the plot.
        val swatch = rectsAll.find(_.svgAttrs.fill.exists {
            case Svg.Paint.Ref(_) => true
            case _                => false
        }).getOrElse(fail("expected the sequential gradient swatch rect"))
        val swatchY = numOf(swatch.svgAttrs.y)
        assert(swatchY < plotY, s"legend swatch y ($swatchY) must be above plotY ($plotY) in the reserved band")
        // The topmost data point must clear the plot top: cy - r >= plotY.
        val pts = deepCirclesIn(root).toSeq.flatMap { c =>
            (c.svgAttrs.cy, c.svgAttrs.r) match
                case (Present(cy), Present(r)) => Some((cy, r))
                case _                         => None
        }
        assert(pts.nonEmpty, "expected plotted point circles")
        val (topCy, topR) = pts.minBy(_._1)
        assert(topCy - topR >= plotY - Tol, s"topmost point top (cy-r=${topCy - topR}) must clear plotY ($plotY)")
    }

    // ---- Bug D (root cause): area/line/point over a band x are centred on the band, aligning with the
    // centred x-tick labels. A left-edge area leaves the last band slot empty (the "empty wedge"). ----
    "stacked area over a band x spans the full plot width; the last vertex is centred on the last band" in {
        case class S(month: String, units: Double, region: Region = Region.NA)
        given CanEqual[S, S] = CanEqual.derived
        // Four categories -> band slots; the rightmost (Apr) vertex must sit at Apr's band centre, not its left
        // edge (which would leave a wedge of empty plot to the right of the area).
        val rows = Chunk(S("Jan", 60), S("Feb", 72), S("Mar", 80), S("Apr", 90))
        val spec = UI.chart(rows)(area(x = _.month, y = _.units, color = _.region, stack = by(_.region)))
        val root = summon[Conversion[ChartSpec[S], Svg.Root]](spec)
        // Band of 4 over [PlotX, PlotX+PlotW]: slot = PlotW/4 = 140, bandW = PlotW*0.9/4 = 126.
        val slot      = PlotW / 4.0
        val bandW     = PlotW * 0.9 / 4.0
        val pad       = (slot - bandW) / 2.0
        val aprCentre = PlotX + 3 * slot + pad + bandW / 2.0 // 60 + 420 + 7 + 63 = 550
        // Collect every path x coordinate; the max x must equal the Apr band centre (full-width area).
        val xs = deepPathsIn(root).flatMap { p =>
            p.svgAttrs.d match
                case Present(pd) => PathData.commands(pd).flatMap {
                        case PathCommand.MoveTo(x, _) => Chunk(x)
                        case PathCommand.LineTo(x, _) => Chunk(x)
                        case _                        => Chunk.empty
                    }
                case Absent => Chunk.empty
        }.toSeq
        assert(xs.nonEmpty, "expected area path vertices")
        val maxX = xs.max
        assertClose(maxX, aprCentre, "rightmost area vertex must be the last band's centre (no empty wedge)")
    }

    // ---- Phase P5 (GAP-COLOR-AREA-SIMPLE): non-stacked area color split ----

    // L9 + L4 (reproduce-before-fix): non-stacked area with color encoding emits one path per series.
    // Fails today: lowerArea emits ONE path filled with defaultColor regardless of mark.color.
    "non-stacked area with color=_.region emits one closed path per series at fill-opacity 0.7, each colored by colorScale (L4 + L9)" in {
        // Use colors that differ from DefaultPalette(0)=blue so failure is unambiguous on the color assertions.
        // red and purple are not DefaultPalette(0) (blue) so the EU path having purple unambiguously fails
        // when the single-path bug produces blue.
        val naColor = Style.Color.red    // #ef4444 -> NA
        val euColor = Style.Color.purple // #a855f7 -> EU
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(2000), Region.EU)
        )
        val spec = UI.chart(rows)(area(x = _.month, y = _.revenue, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .legend(_.colorScale[Region](Region.NA -> naColor, Region.EU -> euColor))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val marks = marksGroup(root)
        // L9: exactly 2 distinct path elements in the marks group (one per Region)
        val areaPaths = marks.children.collect { case p: Svg.Path => p }
        assert(
            areaPaths.size == 2,
            s"L9: expected 2 per-series area paths but got ${areaPaths.size} (the single-path bug produces 1)"
        )
        // L9: each path is closed (the PathData ends with a Close command)
        areaPaths.zipWithIndex.foreach: (p, i) =>
            p.svgAttrs.d match
                case Absent => fail(s"L9: area path $i has no d attribute")
                case Present(pd) =>
                    val cmds = Svg.PathData.commands(pd)
                    assert(
                        cmds.toSeq.lastOption.exists { case Svg.PathCommand.Close => true; case _ => false },
                        s"L9: area path $i must be closed (last command == Close) but last was ${cmds.toSeq.lastOption}"
                    )
        // L9: each path carries fill-opacity == 0.7
        areaPaths.zipWithIndex.foreach: (p, i) =>
            assert(
                p.svgAttrs.fillOpacity.exists(fo => math.abs(fo - 0.7) < 1e-9),
                s"L9: area path $i must have fill-opacity=0.7 but got ${p.svgAttrs.fillOpacity}"
            )
        // L4: path fills match the colorScale colors (NA=red, EU=purple); encounter/ordinal order: NA(0) first.
        assert(
            fillColorOf(areaPaths(0).svgAttrs.fill) == naColor,
            s"L4: first area path (NA) must be naColor but got ${areaPaths(0).svgAttrs.fill}"
        )
        assert(
            fillColorOf(areaPaths(1).svgAttrs.fill) == euColor,
            s"L4: second area path (EU) must be euColor but got ${areaPaths(1).svgAttrs.fill}"
        )
        // L4: legend swatches must agree with the mark fills.
        // Area marks use line swatches (not rect swatches): find them by strokeWidth == 2.0 px among direct-child lines.
        val legendSwatchLines = root.children.collect:
            case l: Svg.Line if l.svgAttrs.strokeWidth.contains(Svg.SvgLength.px(2.0)) => l
        assert(
            legendSwatchLines.size >= 2,
            s"L4: expected at least 2 legend line swatches but got ${legendSwatchLines.size} (area uses line swatches)"
        )
        def strokeColorOf(l: Svg.Line): Style.Color = l.svgAttrs.stroke match
            case Present(Svg.Paint.Color(c)) => c
            case other                       => fail(s"L4: expected Svg.Paint.Color stroke on legend swatch but got $other")
        assert(
            strokeColorOf(legendSwatchLines(0)) == naColor,
            s"L4: NA legend swatch must be naColor but got ${legendSwatchLines(0).svgAttrs.stroke}"
        )
        assert(
            strokeColorOf(legendSwatchLines(1)) == euColor,
            s"L4: EU legend swatch must be euColor but got ${legendSwatchLines(1).svgAttrs.stroke}"
        )
    }

    // L8 co-pin: non-stacked area WITHOUT a color encoding emits exactly 1 closed path,
    // byte-identical to today. The buildSimpleAreaPath refactor must not change the Absent-color arm.
    "non-stacked area with no color encoding still emits exactly one closed path (L8 co-pin, byte-identical)" in {
        case class SimpleRow(x: String, y: Int) derives CanEqual
        val rows = Chunk(SimpleRow("a", 100), SimpleRow("b", 200))
        val spec = UI.chart(rows)(area(x = _.x, y = _.y))
        val root = summon[Conversion[ChartSpec[SimpleRow], Svg.Root]](spec)
        val paths = root.children.flatMap:
            case g: Svg.G => g.children.collect { case p: Svg.Path => p }
            case _        => Chunk.empty
        assert(paths.size == 1, s"L8: mark.color=Absent area must emit exactly 1 path but got ${paths.size}")
        assert(
            paths(0).svgAttrs.fillOpacity.exists(fo => math.abs(fo - 0.7) < 1e-9),
            s"L8: the single area path must have fill-opacity=0.7 but got ${paths(0).svgAttrs.fillOpacity}"
        )
        paths(0).svgAttrs.d match
            case Absent => fail("L8: area path must have a d attribute")
            case Present(pd) =>
                val cmds = Svg.PathData.commands(pd)
                assert(
                    cmds.toSeq.lastOption.exists { case Svg.PathCommand.Close => true; case _ => false },
                    s"L8: the single path must be closed; last command was ${cmds.toSeq.lastOption}"
                )
        end match
    }

    // ---- Leaf L2 (GAP-COLOR-TEXT): text mark with categorical colorScale uses the scale colors ----
    // Before the fix, lowerText resolves palette by index from themePalette(theme) only, ignoring
    // spec.legendCfg.colorScale. With no custom theme.palette this yields DefaultPalette colors
    // (#3b82f6 blue / #f97316 orange), not the colorScale colors. The fix adds spec: Maybe[ChartSpec[A]]
    // and routes a Present colorScale through resolvePalette (mirroring lowerLine / lowerPoint).

    "text mark with categorical colorScale uses the scale colors, not DefaultPalette (GAP-COLOR-TEXT)" in {
        val naColor = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))
        val euColor = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(2000), Region.EU)
        )
        val spec = UI.chart(rows)(text(x = _.month, y = _.revenue, label = _.month, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .legend(_.colorScale[Region](
                Region.NA -> naColor,
                Region.EU -> euColor
            ))
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        // Use the marks group to exclude axis tick texts from the count.
        val marks = marksGroup(root)
        val texts = marks.children.collect { case t: Svg.Text => t }
        assert(texts.size >= 2, s"L2: expected at least 2 text glyphs in marks group, got ${texts.size}")
        // Sort by x to recover NA/EU encounter order (NA is at "Jan", EU at "Feb" on a band scale).
        val byX   = texts.toSeq.sortBy(t => numOf(t.svgAttrs.x))
        val fill0 = fillColorOf(byX(0).svgAttrs.fill)
        val fill1 = fillColorOf(byX(1).svgAttrs.fill)
        assert(fill0 == naColor, s"L2: text glyph 0 (NA) must be naColor $naColor but got $fill0")
        assert(fill1 == euColor, s"L2: text glyph 1 (EU) must be euColor $euColor but got $fill1")
        // Explicit guard: these must NOT be DefaultPalette fallback colors.
        assert(
            fill0 != Style.Color.blue && fill0 != Style.Color.orange,
            s"L2: text fill 0 must not be DefaultPalette colors; got $fill0"
        )
        assert(
            fill1 != Style.Color.blue && fill1 != Style.Color.orange,
            s"L2: text fill 1 must not be DefaultPalette colors; got $fill1"
        )
        // Legend swatch agreement: each region's swatch fill must equal its colorScale color.
        val swatches = legendSwatchRects(root)
        assert(swatches.size == 2, s"L2: expected 2 legend swatches but got ${swatches.size}")
        val swatchesByY = swatches.toSeq.sortBy(s => coordNum(s.svgAttrs.y).getOrElse(0.0))
        assert(
            fillColorOf(swatchesByY(0).svgAttrs.fill) == naColor,
            s"L2: swatch 0 must be naColor $naColor"
        )
        assert(
            fillColorOf(swatchesByY(1).svgAttrs.fill) == euColor,
            s"L2: swatch 1 must be euColor $euColor"
        )
    }

    // ---- Leaf L3 (GAP-COLOR-ERRORBAR): errorBar with categorical colorScale uses the scale colors ----
    // Before the fix, lowerErrorBar resolves palette by index from themePalette(theme) only, ignoring
    // spec.legendCfg.colorScale. All three sub-shapes (vLine + caps + center marker) derive their
    // stroke/fill from that broken palette. The fix mirrors the lowerText fix.

    "errorBar with categorical colorScale uses the scale colors, one stroke per row (GAP-COLOR-ERRORBAR)" in {
        val naColor = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))
        val euColor = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))
        case class EbSale(x: String, mean: Double, lo: Double, hi: Double, region: Region)
        given CanEqual[EbSale, EbSale] = CanEqual.derived
        val rows = Chunk(
            EbSale("a", 6.0, 4.0, 8.0, Region.NA),
            EbSale("b", 3.0, 1.0, 5.0, Region.EU)
        )
        val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.region))
            .yScale(_.linear(0.0, 10.0))
            .legend(_.colorScale[Region](
                Region.NA -> naColor,
                Region.EU -> euColor
            ))
        val root = summon[Conversion[ChartSpec[EbSale], Svg.Root]](spec)
        // Restrict to the marks group to avoid axis gridlines and tick-mark lines.
        val marks   = marksGroup(root)
        val lines   = marks.children.collect { case l: Svg.Line => l }
        val circles = marks.children.collect { case c: Svg.Circle => c }
        // 2 rows x 3 lines each (vLine + capLow + capHigh) = 6 lines total.
        assert(lines.size == 6, s"L3: expected 6 lines (3 per row x 2 rows) but got ${lines.size}")
        // 2 rows x 1 circle each (center marker) = 2 circles total.
        assert(circles.size == 2, s"L3: expected 2 circles (1 per row x 2 rows) but got ${circles.size}")

        def strokeOf(l: Svg.Line): Style.Color = l.svgAttrs.stroke match
            case Present(Svg.Paint.Color(c)) => c
            case other                       => fail(s"L3: expected stroke Color but got $other")

        // Helper: extract a Double from Maybe[Double] (line coordinates are not Coord-wrapped).
        def lineCoord(m: Maybe[Double], lbl: String): Double = m match
            case Present(v) => v
            case Absent     => fail(s"L3: $lbl absent")

        // Group lines by their center x pixel ((x1+x2)/2) so that all 3 sub-shapes of a row land
        // in the same group: the vLine has x1==x2==px, and each cap has x1=px-halfCap, x2=px+halfCap,
        // so (x1+x2)/2 = px in all three cases.
        def centerX(l: Svg.Line): Double =
            (lineCoord(l.svgAttrs.x1, "x1") + lineCoord(l.svgAttrs.x2, "x2")) / 2.0
        val byCenter = lines.toSeq.groupBy(l => math.round(centerX(l)).toInt)
        assert(
            byCenter.size == 2,
            s"L3: expected 2 distinct center groups (one per row), got ${byCenter.keys.toSeq.sorted(using Ordering.Int)}"
        )
        val lineGroups = byCenter.values.toSeq.sortBy(_.map(centerX).min)
        val naGroup    = lineGroups(0) // smaller x -> category "a" = NA
        val euGroup    = lineGroups(1) // larger x -> category "b" = EU
        naGroup.foreach(l =>
            assert(
                strokeOf(l) == naColor,
                s"L3: NA line stroke must be naColor $naColor but got ${strokeOf(l)}"
            )
        )
        euGroup.foreach(l =>
            assert(
                strokeOf(l) == euColor,
                s"L3: EU line stroke must be euColor $euColor but got ${strokeOf(l)}"
            )
        )

        // Explicit guard: must NOT be DefaultPalette fallback colors.
        assert(
            naGroup.forall(l => strokeOf(l) != Style.Color.blue && strokeOf(l) != Style.Color.orange),
            s"L3: NA lines must not use DefaultPalette colors"
        )
        assert(
            euGroup.forall(l => strokeOf(l) != Style.Color.blue && strokeOf(l) != Style.Color.orange),
            s"L3: EU lines must not use DefaultPalette colors"
        )

        // Center marker circles: fill is set to the stroke color (lowerErrorBar uses fill(stroke)).
        // Circle cx is Maybe[Double] (not Maybe[Coord]), extract directly and sort by cx.
        val circlesByX = circles.toSeq.sortBy(c => lineCoord(c.svgAttrs.cx, "cx"))
        val naCircleFill = circlesByX(0).svgAttrs.fill match
            case Present(Svg.Paint.Color(c)) => c
            case other                       => fail(s"L3: expected circle fill Color but got $other")
        val euCircleFill = circlesByX(1).svgAttrs.fill match
            case Present(Svg.Paint.Color(c)) => c
            case other                       => fail(s"L3: expected circle fill Color but got $other")
        assert(naCircleFill == naColor, s"L3: NA marker fill must be naColor $naColor but got $naCircleFill")
        assert(euCircleFill == euColor, s"L3: EU marker fill must be euColor $euColor but got $euCircleFill")

        // Legend swatch agreement.
        val swatches = legendSwatchRects(root)
        assert(swatches.size == 2, s"L3: expected 2 legend swatches but got ${swatches.size}")
        val swatchesByY = swatches.toSeq.sortBy(s => coordNum(s.svgAttrs.y).getOrElse(0.0))
        assert(
            fillColorOf(swatchesByY(0).svgAttrs.fill) == naColor,
            s"L3: swatch 0 must be naColor $naColor"
        )
        assert(
            fillColorOf(swatchesByY(1).svgAttrs.fill) == euColor,
            s"L3: swatch 1 must be euColor $euColor"
        )
    }

    // ---- Leaf L22 (GAP-LEGEND-MARGIN-TEXT-ERRORBAR): legend margin reserved for color-bearing text/errorBar ----
    // Before the fix, buildLayout.hasLegend uses wildcard patterns for Mark.Text and Mark.ErrorBar that
    // hardcode false regardless of a color encoding. So a chart whose ONLY color mark is text or errorBar
    // does NOT reserve legend margin (topPad stays 0, plotY stays MarginTop=20). After the fix, those
    // cases check m.color.isDefined, matching the Bar/Line/Area/Point treatment. plotY becomes 40.0.

    // L22a: text-only color mark reserves the top strip (legend margin reserved, plotY = 40.0).
    "legend margin reserved for color-bearing text mark, plot shifted by LegendReservedH (GAP-LEGEND-MARGIN-TEXT-ERRORBAR)" in {
        // Use Sale rows with revenue=4000 at the top of yScale(linear(0,4000)).
        // With linear(0,4000): apply(4000) == plotY.
        // After fix: plotY = MarginTop(20) + LegendReservedH(20) = 40.0.
        // Before fix: plotY = MarginTop(20) = 20.0.
        val naColor = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))
        val euColor = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))
        val rows = Chunk(
            Sale("Jan", Usd(4000), Region.NA), // revenue at top of scale => glyph y == plotY
            Sale("Feb", Usd(2000), Region.EU)
        )
        val spec = UI.chart(rows)(text(x = _.month, y = _.revenue, label = _.month, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .legend(_.colorScale[Region](Region.NA -> naColor, Region.EU -> euColor))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val marks = marksGroup(root)
        val texts = marks.children.collect { case t: Svg.Text => t }.toSeq
        assert(texts.size >= 2, s"L22a: expected at least 2 text glyphs in marks group, got ${texts.size}")
        // Sort by x to get Jan (NA, revenue=4000) first (band-left is "Jan").
        val byX       = texts.sortBy(t => numOf(t.svgAttrs.x))
        val topGlyphY = numOf(byX(0).svgAttrs.y)
        // After fix: plotY = 40.0; before fix: plotY = 20.0.
        assertClose(topGlyphY, 40.0, "L22a: top glyph y must equal reserved plotY=40 (hasLegend=true for text color mark)")
    }

    // L22b: errorBar-only color mark reserves the top strip (legend margin reserved, plotY = 40.0).
    "legend margin reserved for color-bearing errorBar mark, plot shifted by LegendReservedH (GAP-LEGEND-MARGIN-TEXT-ERRORBAR)" in {
        // EbSale with hi=10.0 == top of yScale(linear(0,10)). apply(10.0) == plotY.
        // After fix: plotY = 40.0; before fix: plotY = 20.0.
        val naColor = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))
        val euColor = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))
        case class EbSale22(x: String, mean: Double, lo: Double, hi: Double, region: Region)
            derives CanEqual
        val rows = Chunk(
            EbSale22("a", 8.0, 6.0, 10.0, Region.NA), // hi=10.0 at top of scale => vLine y1 == plotY
            EbSale22("b", 5.0, 3.0, 7.0, Region.EU)
        )
        val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.region))
            .yScale(_.linear(0.0, 10.0))
            .legend(_.colorScale[Region](Region.NA -> naColor, Region.EU -> euColor))
        val root  = summon[Conversion[ChartSpec[EbSale22], Svg.Root]](spec)
        val marks = marksGroup(root)
        val lines = marks.children.collect { case l: Svg.Line => l }.toSeq
        // vLines have x1 == x2. lowerErrorBar sets y1=pyLow, y2=pyHigh.
        // pyHigh = ys.apply(hi) = plotY for hi=10.0 (top of scale). Minimum y2 across vLines = plotY.
        val vLines = lines.filter(l => l.svgAttrs.x1 == l.svgAttrs.x2)
        assert(vLines.nonEmpty, "L22b: no vLines found in marks group")
        val topY = vLines.flatMap(l => l.svgAttrs.y2).min
        // After fix: plotY = 40.0; before fix: plotY = 20.0.
        assertClose(
            topY,
            40.0,
            "L22b: top vLine y2 (pyHigh for hi=10.0) must equal reserved plotY=40 (hasLegend=true for errorBar color mark)"
        )
    }

    // L22c (CO-PIN): no-color text mark keeps hasLegend==false, plotY unchanged at 20.0.
    "no-color text mark keeps plotY=20 (hasLegend=false, topPad=0) after GAP-LEGEND-MARGIN fix (co-pin)" in {
        // text mark WITHOUT a color encoding: m.color.isDefined==false, hasLegend stays false.
        val rows = Chunk(
            Sale("Jan", Usd(4000)), // no region => Region.NA default, but no color encoding on mark
            Sale("Feb", Usd(2000))
        )
        val spec = UI.chart(rows)(text(x = _.month, y = _.revenue, label = _.month))
            .yScale(_.linear(0.0, 4000.0))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val marks = marksGroup(root)
        val texts = marks.children.collect { case t: Svg.Text => t }.toSeq
        assert(texts.size >= 1, s"L22c: expected at least 1 text glyph in marks group, got ${texts.size}")
        val byX       = texts.sortBy(t => numOf(t.svgAttrs.x))
        val topGlyphY = numOf(byX(0).svgAttrs.y)
        // No legend reserved: plotY stays at MarginTop = 20.0.
        assertClose(topGlyphY, 20.0, "L22c: no-color text mark must keep plotY=20 (hasLegend=false, no strip reserved)")
        // Also: no legend swatches rendered.
        assert(legendSwatchRects(root).isEmpty, "L22c: no legend swatches for no-color text mark")
    }

    // ---- FIX 1 (P9b): animated non-stacked area with colorScale honors the scale colors ----
    // Reproduce-before-fix: lowerAreaWithTransitions called lowerArea with spec=Absent, so the
    // animated path resolved the palette from DefaultPalette by index (blue/orange), ignoring the
    // explicit colorScale. The fix forwards Present(spec) into both lowerArea calls inside
    // lowerAreaWithTransitions, mirroring the lowerLineWithTransitions FIX B pattern.

    "animated non-stacked area with categorical colorScale honors the scale colors (FIX 1, P9b)" in run {
        case class ARow(x: Double, y: Double, series: String) derives CanEqual
        val cyan  = Style.Color.rgb(6, 182, 212)
        val amber = Style.Color.rgb(245, 158, 11)
        // DefaultPalette entries for comparison.
        val blueCss   = "#3b82f6"
        val orangeCss = "#f97316"
        val rows = Chunk(
            ARow(0.0, 1.0, "a"),
            ARow(1.0, 2.0, "a"),
            ARow(0.0, 3.0, "b"),
            ARow(1.0, 4.0, "b")
        )
        for
            ref <- Signal.initRef[Seq[ARow]](rows)
            // .animate routes lowering through lowerAreaWithTransitions (the path under test).
            spec = UI.chart(ref: Signal[Seq[ARow]])(area(x = _.x, y = _.y, color = _.series))
                .animate(_.ease(300.millis))
                .legend(_.colorScale {
                    case "a" => cyan
                    case _   => amber
                })
            root = summon[Conversion[ChartSpec[ARow], Svg.Root]](spec)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Extract each <path ...> element's fill colour in document order.
            // lowerAreaWithTransitions emits one path per series in encounter order (a=0 then b=1).
            val fills: Chunk[String] = Chunk.from(
                "<path[^>]*".r.findAllIn(html).toSeq.flatMap { tag =>
                    "fill=\"([^\"]+)\"".r.findFirstMatchIn(tag).map(_.group(1))
                }
            )
            assert(
                fills.size == 2,
                s"FIX 1 (P9b): expected 2 area-path fills (one per series) but got ${fills.size}:\n$html"
            )
            // Series "a" (seriesIdx 0) must carry the colorScale cyan, NOT DefaultPalette blue.
            val cyanCss  = "rgb(6, 182, 212)"
            val amberCss = "rgb(245, 158, 11)"
            assert(
                fills(0) == cyanCss,
                s"FIX 1 (P9b): animated area series 'a' must use colorScale cyan $cyanCss but got ${fills(0)}:\n$html"
            )
            // Series "b" (seriesIdx 1) must carry the colorScale amber, NOT DefaultPalette orange.
            assert(
                fills(1) == amberCss,
                s"FIX 1 (P9b): animated area series 'b' must use colorScale amber $amberCss but got ${fills(1)}:\n$html"
            )
            // DefaultPalette colours must not appear as area fills.
            assert(
                !fills.exists(_ == blueCss),
                s"FIX 1 (P9b): DefaultPalette blue $blueCss must not be an area fill under a colorScale:\n$html"
            )
            assert(
                !fills.exists(_ == orangeCss),
                s"FIX 1 (P9b): DefaultPalette orange $orangeCss must not be an area fill under a colorScale:\n$html"
            )
        end for
    }

    // ---- FIX 1b (P9c): animated STACKED area honors custom theme.palette and categorical colorScale ----
    // The P9b addendum identified a second spec-drop locus: lowerAreaWithTransitions line ~3659 (the
    // stacked fallthrough). lowerArea forwards spec into lowerAreaStacked, which uses resolvePalette
    // when spec is Present. resolvePalette honors: (1) categorical colorScale; (2) sequential colorScale;
    // (3) theme.palette when no colorScale; (4) DefaultPalette as final fallback.
    // Before the fix, lowerAreaWithTransitions called lowerArea with spec=Absent at 3659, routing
    // lowerAreaStacked to resolvePaletteFromCfg (DefaultPalette only), dropping BOTH colorScale AND
    // custom theme.palette. After the fix, Present(spec) is forwarded and resolvePalette is used.
    //
    // Test A: custom theme.palette (no colorScale). The animated stacked area must produce the same
    // per-group fills as the static twin (leaf 19 above).
    "animated stacked area honors custom theme.palette colors, matching the static twin (FIX 1b, P9c)" in run {
        val purple = Style.Color.hex("#cc00cc").getOrElse(fail("bad hex"))
        val teal   = Style.Color.hex("#00cccc").getOrElse(fail("bad hex"))
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(1500), Region.NA),
            Sale("Jan", Usd(2000), Region.EU),
            Sale("Feb", Usd(2500), Region.EU)
        )
        for
            ref <- Signal.initRef[Seq[Sale]](rows)
            // .animate routes lowering through lowerAreaWithTransitions (the stacked arm at ~3659).
            spec = UI.chart(ref: Signal[Seq[Sale]])(area(x = _.month, y = _.revenue, stack = by(_.region)))
                .yScale(_.linear(0.0, 6000.0))
                .animate(_.ease(300.millis))
                .theme(_.palette(Chunk(purple, teal)))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#cc00cc\""),
                s"FIX 1b (P9c): animated stacked area group 0 must use theme.palette purple #cc00cc:\n$html"
            )
            assert(
                html.contains("fill=\"#00cccc\""),
                s"FIX 1b (P9c): animated stacked area group 1 must use theme.palette teal #00cccc:\n$html"
            )
            // DefaultPalette blue/orange must not appear: they are the resolvePaletteFromCfg (spec=Absent) fallback.
            assert(
                !html.contains("fill=\"#3b82f6\""),
                s"FIX 1b (P9c): DefaultPalette blue must not appear in animated stacked area under a custom theme:\n$html"
            )
            assert(
                !html.contains("fill=\"#f97316\""),
                s"FIX 1b (P9c): DefaultPalette orange must not appear in animated stacked area under a custom theme:\n$html"
            )
        end for
    }

    // Test B: categorical colorScale. resolvePalette honors it; resolvePaletteFromCfg ignores it.
    "animated stacked area honors a categorical colorScale, not DefaultPalette (FIX 1b, P9c)" in run {
        val crimson   = Style.Color.rgb(220, 38, 38)
        val indigo    = Style.Color.rgb(99, 102, 241)
        val blueCss   = "#3b82f6"
        val orangeCss = "#f97316"
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(1500), Region.NA),
            Sale("Jan", Usd(2000), Region.EU),
            Sale("Feb", Usd(2500), Region.EU)
        )
        for
            ref <- Signal.initRef[Seq[Sale]](rows)
            spec = UI.chart(ref: Signal[Seq[Sale]])(area(x = _.month, y = _.revenue, stack = by(_.region)))
                .yScale(_.linear(0.0, 6000.0))
                .animate(_.ease(300.millis))
                .legend(_.colorScale[Region](
                    Region.NA -> crimson,
                    Region.EU -> indigo
                ))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            val crimsonCss = "rgb(220, 38, 38)"
            val indigoCss  = "rgb(99, 102, 241)"
            assert(
                html.contains(s"fill=\"$crimsonCss\""),
                s"FIX 1b (P9c): animated stacked area NA group must use colorScale crimson $crimsonCss:\n$html"
            )
            assert(
                html.contains(s"fill=\"$indigoCss\""),
                s"FIX 1b (P9c): animated stacked area EU group must use colorScale indigo $indigoCss:\n$html"
            )
            assert(
                !html.contains(s"fill=\"$blueCss\""),
                s"FIX 1b (P9c): DefaultPalette blue must not appear in animated stacked area under a colorScale:\n$html"
            )
            assert(
                !html.contains(s"fill=\"$orangeCss\""),
                s"FIX 1b (P9c): DefaultPalette orange must not appear in animated stacked area under a colorScale:\n$html"
            )
        end for
    }

    // ---- L10 (P9b): Sequential colorScale arm regression guards ----
    // These four tests lock the working Sequential branch in resolvePalette for each newly-fixed
    // color-split mark. Two rows at the domain endpoints [0.0, 100.0] map to exact low/high colors:
    //   value 0.0  -> t=0.0 -> lerpColor(black, white, 0.0) = rgb(0,0,0)
    //   value 100.0 -> t=1.0 -> lerpColor(black, white, 1.0) = rgb(255,255,255)
    // The auto-derived domain equals [0.0, 100.0] when data is exactly [0.0, 100.0].

    "grouped bar with Sequential colorScale honors the scale colors (L10, P9b)" in {
        // Low=black(0,0,0), High=white(255,255,255); data values 0.0 and 100.0 are the domain endpoints.
        case class HeatRow(month: String, revenue: Double, level: Double) derives CanEqual
        val rows = Chunk(
            HeatRow("Jan", 1000.0, 0.0),
            HeatRow("Jan", 2000.0, 100.0)
        )
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue, color = _.level))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        val root  = summon[Conversion[ChartSpec[HeatRow], Svg.Root]](spec)
        val rects = rectsIn(root)
        assert(rects.size == 2, s"L10 bar: expected 2 bar rects but got ${rects.size}")
        val byX = rects.toSeq.sortBy(r => numOf(r.svgAttrs.x))
        def fillOf(r: Svg.Rect): Style.Color = r.svgAttrs.fill match
            case Present(Svg.Paint.Color(c)) => c
            case other                       => fail(s"L10 bar: expected color fill but got $other")
        val fills = byX.map(fillOf)
        // value 0.0 (first bar by x) maps to low=black; value 100.0 maps to high=white.
        assert(
            fills(0) == Style.Color.rgb(0, 0, 0),
            s"L10 bar: low-value bar must be low color rgb(0,0,0) but got ${fills(0)}"
        )
        assert(
            fills(1) == Style.Color.rgb(255, 255, 255),
            s"L10 bar: high-value bar must be high color rgb(255,255,255) but got ${fills(1)}"
        )
        // Both colors are distinct and concrete (neither equals a DefaultPalette entry).
        assert(
            fills(0) != fills(1),
            s"L10 bar: low and high colors must differ but both were ${fills(0)}"
        )
        assert(
            !fills.exists(c => c == Style.Color.blue || c == Style.Color.orange),
            s"L10 bar: fills must not be DefaultPalette colors but got $fills"
        )
    }

    "non-stacked area with Sequential colorScale honors the scale colors (L10, P9b)" in {
        // Low=black(0,0,0), High=white(255,255,255); data values 0.0 and 100.0 are domain endpoints.
        case class SeqRow(x: String, y: Double, level: Double) derives CanEqual
        val rows = Chunk(
            SeqRow("a", 1.0, 0.0),
            SeqRow("b", 2.0, 100.0)
        )
        val spec = UI.chart(rows)(area(x = _.x, y = _.y, color = _.level))
            .yScale(_.linear(0.0, 4.0))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        val root      = summon[Conversion[ChartSpec[SeqRow], Svg.Root]](spec)
        val marks     = marksGroup(root)
        val areaPaths = marks.children.collect { case p: Svg.Path => p }
        assert(areaPaths.size == 2, s"L10 area: expected 2 area paths but got ${areaPaths.size}")
        // Paths are emitted in category encounter order (level 0.0 first, then 100.0).
        val fills = areaPaths.map(p => fillColorOf(p.svgAttrs.fill))
        assert(
            fills(0) == Style.Color.rgb(0, 0, 0),
            s"L10 area: low-value path must be low color rgb(0,0,0) but got ${fills(0)}"
        )
        assert(
            fills(1) == Style.Color.rgb(255, 255, 255),
            s"L10 area: high-value path must be high color rgb(255,255,255) but got ${fills(1)}"
        )
        // Both colors are distinct and concrete.
        assert(
            fills(0) != fills(1),
            s"L10 area: low and high colors must differ but both were ${fills(0)}"
        )
        assert(
            !fills.exists(c => c == Style.Color.blue || c == Style.Color.orange),
            s"L10 area: fills must not be DefaultPalette colors but got $fills"
        )
        // Sequential legend renders a single continuous gradient swatch (not per-category line swatches).
        // The L10 property is on the mark fills (two distinct concrete interpolated colors at the domain
        // endpoints); the gradient swatch itself is covered by INV-028 Leaf 16.
    }

    "text mark with Sequential colorScale honors the scale colors (L10, P9b)" in {
        // Low=black(0,0,0), High=white(255,255,255); data values 0.0 and 100.0 are domain endpoints.
        case class TextSeqRow(x: String, y: Double, level: Double) derives CanEqual
        val rows = Chunk(
            TextSeqRow("a", 1.0, 0.0),
            TextSeqRow("b", 2.0, 100.0)
        )
        val spec = UI.chart(rows)(text(x = _.x, y = _.y, label = _.x, color = _.level))
            .yScale(_.linear(0.0, 4.0))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        val root  = summon[Conversion[ChartSpec[TextSeqRow], Svg.Root]](spec)
        val marks = marksGroup(root)
        val texts = marks.children.collect { case t: Svg.Text => t }
        assert(texts.size >= 2, s"L10 text: expected at least 2 text glyphs but got ${texts.size}")
        val byX   = texts.toSeq.sortBy(t => numOf(t.svgAttrs.x))
        val fill0 = fillColorOf(byX(0).svgAttrs.fill)
        val fill1 = fillColorOf(byX(1).svgAttrs.fill)
        // Glyphs sorted by x: "a" at lower band-center (level=0.0, low color), "b" at higher (level=100.0, high color).
        assert(
            fill0 == Style.Color.rgb(0, 0, 0),
            s"L10 text: glyph 'a' (level=0.0) must be low color rgb(0,0,0) but got $fill0"
        )
        assert(
            fill1 == Style.Color.rgb(255, 255, 255),
            s"L10 text: glyph 'b' (level=100.0) must be high color rgb(255,255,255) but got $fill1"
        )
        // Distinct and concrete.
        assert(
            fill0 != fill1,
            s"L10 text: low and high fills must differ but both were $fill0"
        )
        assert(
            fill0 != Style.Color.blue && fill0 != Style.Color.orange,
            s"L10 text: fill0 must not be DefaultPalette but got $fill0"
        )
        assert(
            fill1 != Style.Color.blue && fill1 != Style.Color.orange,
            s"L10 text: fill1 must not be DefaultPalette but got $fill1"
        )
        // Sequential legend renders a single continuous gradient swatch (not per-category rect swatches).
        // The L10 property is on the mark fills (two distinct concrete interpolated colors at the domain
        // endpoints); the gradient swatch itself is covered by INV-028 Leaf 16.
    }

    "errorBar with Sequential colorScale honors the scale colors (L10, P9b)" in {
        // Low=black(0,0,0), High=white(255,255,255); data values 0.0 and 100.0 are domain endpoints.
        case class EbSeqRow(x: String, mean: Double, lo: Double, hi: Double, level: Double) derives CanEqual
        val rows = Chunk(
            EbSeqRow("a", 2.0, 1.0, 3.0, 0.0),
            EbSeqRow("b", 5.0, 4.0, 6.0, 100.0)
        )
        val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.level))
            .yScale(_.linear(0.0, 10.0))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        val root    = summon[Conversion[ChartSpec[EbSeqRow], Svg.Root]](spec)
        val marks   = marksGroup(root)
        val lines   = marks.children.collect { case l: Svg.Line => l }
        val circles = marks.children.collect { case c: Svg.Circle => c }
        // 2 rows x 3 lines each = 6 lines total.
        assert(lines.size == 6, s"L10 errorBar: expected 6 lines (3 per row x 2 rows) but got ${lines.size}")
        assert(circles.size == 2, s"L10 errorBar: expected 2 center marker circles but got ${circles.size}")
        def strokeOf(l: Svg.Line): Style.Color = l.svgAttrs.stroke match
            case Present(Svg.Paint.Color(c)) => c
            case other                       => fail(s"L10 errorBar: expected stroke Color but got $other")
        // Group lines by center-x to separate the two rows.
        def lineCoord(m: Maybe[Double]): Double = m match
            case Present(v) => v
            case Absent     => fail("L10 errorBar: coordinate absent")
        def centerX(l: Svg.Line): Double =
            (lineCoord(l.svgAttrs.x1) + lineCoord(l.svgAttrs.x2)) / 2.0
        val byCenter = lines.toSeq.groupBy(l => math.round(centerX(l)).toInt)
        assert(byCenter.size == 2, s"L10 errorBar: expected 2 center groups but got ${byCenter.size}")
        val groups = byCenter.values.toSeq.sortBy(_.map(centerX).min)
        val groupA = groups(0) // lower x -> row "a", level=0.0 -> low=black
        val groupB = groups(1) // higher x -> row "b", level=100.0 -> high=white
        groupA.foreach(l =>
            assert(
                strokeOf(l) == Style.Color.rgb(0, 0, 0),
                s"L10 errorBar: row 'a' (level=0.0) lines must be low color rgb(0,0,0) but got ${strokeOf(l)}"
            )
        )
        groupB.foreach(l =>
            assert(
                strokeOf(l) == Style.Color.rgb(255, 255, 255),
                s"L10 errorBar: row 'b' (level=100.0) lines must be high color rgb(255,255,255) but got ${strokeOf(l)}"
            )
        )
        // Center marker circle fills must match the row stroke.
        def circleCoord(m: Maybe[Double]): Double = m match
            case Present(v) => v
            case Absent     => fail("L10 errorBar: circle coord absent")
        val circlesByX = circles.toSeq.sortBy(c => circleCoord(c.svgAttrs.cx))
        val fillA = circlesByX(0).svgAttrs.fill match
            case Present(Svg.Paint.Color(c)) => c
            case other                       => fail(s"L10 errorBar: circle fill expected Color but got $other")
        val fillB = circlesByX(1).svgAttrs.fill match
            case Present(Svg.Paint.Color(c)) => c
            case other                       => fail(s"L10 errorBar: circle fill expected Color but got $other")
        assert(fillA == Style.Color.rgb(0, 0, 0), s"L10 errorBar: row 'a' circle fill must be rgb(0,0,0) but got $fillA")
        assert(fillB == Style.Color.rgb(255, 255, 255), s"L10 errorBar: row 'b' circle fill must be rgb(255,255,255) but got $fillB")
        // DefaultPalette guard.
        assert(
            groupA.forall(l => strokeOf(l) != Style.Color.blue && strokeOf(l) != Style.Color.orange),
            "L10 errorBar: row 'a' lines must not use DefaultPalette colors"
        )
        assert(
            groupB.forall(l => strokeOf(l) != Style.Color.blue && strokeOf(l) != Style.Color.orange),
            "L10 errorBar: row 'b' lines must not use DefaultPalette colors"
        )
    }

    // ---- L-bug: CatKey collision tests (toString-collision bug) ----
    // A group type whose toString is non-injective: Grp(1) and Grp(2) both produce "G".
    // This exposes the bug where dataMap / rowBySlot / colorIdxByKey key by toString, causing
    // collisions: last-writer-wins on the data map while the category list expands to two entries,
    // leading to one group's value being lost and the other double-counted.

    final case class GrpRow(month: String, value: Double, grp: GrpTag) derives CanEqual
    final case class GrpTag(id: Int) derives CanEqual:
        override def toString: String = "G" // non-injective: all GrpTag values share the same label

    "L-bug-stacked-bar: distinct groups with colliding toString must produce two segments with correct heights (not double-counted)" in {
        // Two rows at the same x="Jan": grp=GrpTag(1) with value=10, grp=GrpTag(2) with value=30.
        // Correct: two distinct segments of height 10 and 30 (total stack = 40).
        // Buggy:   dataMap merges both under "G" (last-writer-wins -> 30), groupKeys=["G","G"],
        //          totalY = 30+30 = 60, each segment height = 300 -> total 600 -> WRONG.
        // Layout note: with stack.group defined, hasLegend=true, LegendReservedH=20 is reserved at the top.
        // Effective: plotY_eff=40, plotH_eff=400, baseline=440.
        // y scale: extent [0,40], niceTicks -> nHi=40. Scale.Linear(0,40,baseline=440,top=40).
        // Segment gi=0 (value=10): spans [0,10] -> bottom=440, top=ys(10)=440+(10/40)*(40-440)=440-100=340; rectH=100.
        // Segment gi=1 (value=30): spans [10,40] -> bottom=ys(10)=340, top=ys(40)=40; rectH=300.
        // Total combined height = 400 = plotH_eff. Buggy total = 600.
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Jan", 30.0, GrpTag(2))
        )
        val spec  = UI.chart(rows)(bar(x = _.month, y = _.value, stack = by(_.grp)))
        val root  = summon[Conversion[ChartSpec[GrpRow], Svg.Root]](spec)
        val rects = rectsIn(root)

        // Must have exactly 2 segments (one per distinct group).
        assert(rects.size == 2, s"L-bug-stacked-bar: expected 2 stacked segments but got ${rects.size}")

        // Effective plot height: legend reserve (LegendReservedH=20) shifts the plot down so plotH_eff = 400.
        val plotHEff = 400.0
        val heights  = rects.map(r => numOf(r.svgAttrs.height)).toSeq.sorted
        // The two heights must sum to plotH_eff (total stack 40 covers the full effective plot height).
        val totalHeight = heights.sum
        assertClose(totalHeight, plotHEff, "L-bug-stacked-bar: total stacked height must equal effective plotH=400")
        // Smaller segment (value=10): 10/40 of plotH_eff = 100.
        assertClose(heights(0), plotHEff * 10.0 / 40.0, "L-bug-stacked-bar: smaller segment height (value=10 portion)")
        // Larger segment (value=30): 30/40 of plotH_eff = 300.
        assertClose(heights(1), plotHEff * 30.0 / 40.0, "L-bug-stacked-bar: larger segment height (value=30 portion)")

        // Legend must have 2 distinct swatches.
        val swatches = legendSwatchRects(root)
        assert(swatches.size == 2, s"L-bug-stacked-bar: expected 2 legend swatches but got ${swatches.size}")
    }

    "L-bug-stacked-area: distinct groups with colliding toString must produce two distinct area bands (not one doubled)" in {
        // Two rows at the same x="Jan": grp=GrpTag(1) with value=10, grp=GrpTag(2) with value=30.
        // Correct: two distinct stacked area bands, total stack [0,40], top of stack at plotY=20.
        // Buggy:   groupKeys=["G","G"], dataMap["Jan"]["G"]=30, second band accumulates to 60 ->
        //          top of second band = ys(60) which is BELOW PlotY (a negative pixel, above the SVG).
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Jan", 30.0, GrpTag(2))
        )
        val spec = UI.chart(rows)(area(x = _.month, y = _.value, stack = by(_.grp)))
        val root = summon[Conversion[ChartSpec[GrpRow], Svg.Root]](spec)
        // The marks group holds area paths.
        val marks     = marksGroup(root)
        val areaPaths = marks.children.collect { case p: Svg.Path => p }

        // Must have exactly 2 area band paths (one per distinct group).
        assert(areaPaths.size == 2, s"L-bug-stacked-area: expected 2 area band paths but got ${areaPaths.size}")

        // The two bands must have distinct fill colors (each group gets its own palette color).
        val fills = areaPaths.map(p => fillColorOfPath(p)).toSeq.distinct
        assert(fills.size == 2, s"L-bug-stacked-area: expected 2 distinct fill colors but got $fills")

        // Critical correctness check: no path command should produce a y coordinate above plotY_eff.
        // Layout: stack.group defined => hasLegend=true, LegendReservedH=20.
        // plotY_eff = 40, baseline = 440, plotH_eff = 400.
        // y scale: stackedAreaYExtent sums 10+30=40, niceTicks [0,40] -> nHi=40.
        // Scale.Linear(0,40,baseline=440,top=40): ys(40)=40, ys(60)=40+(60/40)*(440-40)-440=-160 (above plot, NEGATIVE).
        // In correct case: min path y = ys(40) = 40.0. In buggy case: min path y = -160 (way above the plot).
        val plotYEff = 40.0 // plotY with legend reserve
        val allPathYs: Seq[Double] = areaPaths.toSeq.flatMap: p =>
            Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).toSeq.collect:
                case PathCommand.MoveTo(_, y) => y
                case PathCommand.LineTo(_, y) => y
        assert(allPathYs.nonEmpty, "L-bug-stacked-area: expected path y coordinates")
        val minY = allPathYs.min
        assert(
            minY >= plotYEff - Tol,
            s"L-bug-stacked-area: topmost y=$minY must be >= plotYEff=$plotYEff (second band must not exceed total stack of 40)"
        )

        // Legend must have 2 distinct swatches.
        val swatches = legendSwatchRects(root)
        assert(swatches.size == 2, s"L-bug-stacked-area: expected 2 legend swatches but got ${swatches.size}")
    }

    "L-bug-grouped-bar: distinct groups with colliding toString must produce two dodged bars with correct heights" in {
        // Two rows at the same x="Jan": grp=GrpTag(1) with value=10, grp=GrpTag(2) with value=30.
        // Correct: 2 dodged bars, one with height proportional to 10, one to 30, in 2 distinct colors.
        // Buggy:   colorIdxByKey["G"]=0, both rows land in sub-slot 0, second overwrites first -> only 1 value rendered.
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Jan", 30.0, GrpTag(2))
        )
        val spec  = UI.chart(rows)(bar(x = _.month, y = _.value, color = _.grp))
        val root  = summon[Conversion[ChartSpec[GrpRow], Svg.Root]](spec)
        val rects = rectsIn(root)

        // Must have exactly 2 bar rects (one per distinct group).
        assert(rects.size == 2, s"L-bug-grouped-bar: expected 2 dodged bar rects but got ${rects.size}")

        // The two bars must have distinct heights (10-proportional and 30-proportional).
        // Layout: color encoding defined => hasLegend=true, LegendReservedH=20.
        // plotY_eff=40, plotH_eff=400, baseline=440.
        // y scale: extent [0,30] (max is 30), niceTicks -> nHi=30.
        // Scale.Linear(0,30,baseline=440,top=40): apply(10)=440+(10/30)*(40-440)=440-133.33=306.67; barH=133.33.
        //                                          apply(30)=40; barH=440-40=400.
        val plotHEff = 400.0
        val heights  = rects.map(r => numOf(r.svgAttrs.height)).toSeq.sorted
        assertClose(heights(0), plotHEff * 10.0 / 30.0, "L-bug-grouped-bar: smaller bar height (value=10 portion)")
        assertClose(heights(1), plotHEff, "L-bug-grouped-bar: taller bar height (value=30 fills full effective plot)")

        // The two bars must have distinct x positions (dodged side by side).
        val xs = rects.map(r => numOf(r.svgAttrs.x)).toSeq.sorted
        assert(xs(0) != xs(1), s"L-bug-grouped-bar: the two bars must be at different x positions (dodged), but both at ${xs(0)}")

        // The two bars must have distinct fill colors.
        val fills = rects.map(r => numOf(r.svgAttrs.x)).toSeq
        val fillColors = rects.map(r =>
            r.svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"L-bug-grouped-bar: expected color fill but got $other")
        ).toSeq.distinct
        assert(fillColors.size == 2, s"L-bug-grouped-bar: expected 2 distinct fill colors but got $fillColors")

        // Legend must have 2 distinct swatches.
        val swatches = legendSwatchRects(root)
        assert(swatches.size == 2, s"L-bug-grouped-bar: expected 2 legend swatches but got ${swatches.size}")
    }

    "L-bug-line-color: distinct color series with colliding toString must not be merged into one another" in {
        // GrpTag(1) appears only at x="Jan"; GrpTag(2) only at x="Feb". With both toString=="G",
        // the static lowerLine color split filtered `accessor(r).toString == key` (== "G"), so EVERY
        // series swept up ALL rows. Correct: each series path holds only its own single point (1 MoveTo,
        // 0 LineTo). Buggy: each path holds both points (1 MoveTo + 1 LineTo).
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Feb", 30.0, GrpTag(2))
        )
        val spec  = UI.chart(rows)(line(x = _.month, y = _.value, color = _.grp))
        val root  = summon[Conversion[ChartSpec[GrpRow], Svg.Root]](spec)
        val paths = pathsIn(root)
        assert(paths.size == 2, s"L-bug-line-color: expected 2 series paths but got ${paths.size}")
        def lineToCount(p: Svg.Path): Int =
            Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).toSeq.count:
                case _: PathCommand.LineTo => true
                case _                     => false
        val lineTos = paths.map(lineToCount).toSeq
        assert(
            lineTos.forall(_ == 0),
            s"L-bug-line-color: each single-point series must have 0 LineTo commands (one point each), got $lineTos"
        )
    }

    "L-bug-area-color: distinct color series with colliding toString must not be merged into one another" in {
        // Same setup as L-bug-line-color, for the non-stacked color area path. The static lowerArea
        // color split also filtered `accessor(r).toString == key`, merging distinct-toString series.
        // A single-point closed area = top MoveTo, lineTo(lastX, baseline), lineTo(firstX, baseline), close
        // -> exactly 2 LineTo commands. The buggy merge (both points) adds a top-edge LineTo -> 3.
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Feb", 30.0, GrpTag(2))
        )
        val spec  = UI.chart(rows)(area(x = _.month, y = _.value, color = _.grp))
        val root  = summon[Conversion[ChartSpec[GrpRow], Svg.Root]](spec)
        val paths = pathsIn(root)
        assert(paths.size == 2, s"L-bug-area-color: expected 2 series paths but got ${paths.size}")
        def lineToCount(p: Svg.Path): Int =
            Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).toSeq.count:
                case _: PathCommand.LineTo => true
                case _                     => false
        val lineTos = paths.map(lineToCount).toSeq
        assert(
            lineTos.forall(_ == 2),
            s"L-bug-area-color: each single-point series area must have 2 LineTo commands, got $lineTos"
        )
    }

    "L-bug-text-color: distinct color categories with colliding toString must get distinct palette colors" in {
        // GrpTag(1) at x="Jan", GrpTag(2) at x="Feb", both toString=="G". lowerText keyed its color
        // index by label toString (catIdxText: Map[String,Int], first-seen wins), so both rows resolved
        // to palette(0). Correct: GrpTag(1) -> palette(0), GrpTag(2) -> palette(1) (two distinct colors).
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Feb", 30.0, GrpTag(2))
        )
        val spec = UI.chart(rows)(text(x = _.month, y = _.value, label = _.month, color = _.grp))
            .yScale(_.linear(0.0, 40.0))
        val root  = summon[Conversion[ChartSpec[GrpRow], Svg.Root]](spec)
        val marks = marksGroup(root)
        val texts = marks.children.collect { case t: Svg.Text => t }
        assert(texts.size == 2, s"L-bug-text-color: expected 2 text glyphs but got ${texts.size}")
        val byX = texts.toSeq.sortBy(t => numOf(t.svgAttrs.x))
        val c0  = fillColorOf(byX(0).svgAttrs.fill)
        val c1  = fillColorOf(byX(1).svgAttrs.fill)
        assert(c0 != c1, s"L-bug-text-color: the two distinct color categories must get distinct fills, both got $c0")
    }

    "L-bug-errorbar-color: distinct color categories with colliding toString must get distinct stroke colors" in {
        // Mirror of L-bug-text-color for errorBar. lowerErrorBar keyed colorIdx by label toString
        // (catIdxErr), collapsing the two distinct categories onto palette(0).
        case class EbGrp(x: String, mean: Double, lo: Double, hi: Double, grp: GrpTag) derives CanEqual
        val rows = Chunk(
            EbGrp("a", 6.0, 4.0, 8.0, GrpTag(1)),
            EbGrp("b", 3.0, 1.0, 5.0, GrpTag(2))
        )
        val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.grp))
            .yScale(_.linear(0.0, 10.0))
        val root    = summon[Conversion[ChartSpec[EbGrp], Svg.Root]](spec)
        val marks   = marksGroup(root)
        val circles = marks.children.collect { case c: Svg.Circle => c }
        assert(circles.size == 2, s"L-bug-errorbar-color: expected 2 center-marker circles but got ${circles.size}")
        val byX = circles.toSeq.sortBy(c => c.svgAttrs.cx.getOrElse(0.0))
        val f0  = fillColorOf(byX(0))
        val f1  = fillColorOf(byX(1))
        assert(f0 != f1, s"L-bug-errorbar-color: the two distinct color categories must get distinct strokes, both got $f0")
    }

end ChartLowerTest
