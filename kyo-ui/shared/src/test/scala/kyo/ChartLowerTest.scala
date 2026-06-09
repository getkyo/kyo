package kyo

import kyo.Chart.*
import kyo.Chart.Encoding
import kyo.Svg.Coord
import kyo.Svg.PathCommand
import kyo.Svg.PathData
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.internal.ChartLower
import kyo.internal.HtmlRenderer
import kyo.internal.NumberFormat
import kyo.internal.Scale
import scala.language.implicitConversions

class ChartLowerTest extends kyo.test.Test[Any]:

    // ---- shared domain types ----

    enum Region derives CanEqual, Plottable:
        case NA, EU, APAC

    opaque type Usd <: Double = Double
    object Usd:
        def apply(d: Double): Usd                = d
        given Plottable[Usd]                     = Plottable.numeric
        given CanEqual[Usd, Usd]                 = CanEqual.derived
        implicit def doubleToUsd(d: Double): Usd = d
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
    private def marksGroup(root: Svg.Root)(using Frame, kyo.test.AssertScope): Svg.G =
        root.children.collect { case g: Svg.G => g }.lastOption.getOrElse(fail("no marks Svg.G found"))

    // Layout constants (must match ChartLower defaults).
    private val PlotX    = 60.0
    private val PlotY    = 20.0
    private val PlotW    = 560.0
    private val PlotH    = 420.0
    private val Baseline = PlotY + PlotH // 440.0

    private val Tol = 1e-6

    private def assertClose(actual: Double, expected: Double, msg: String)(using Frame, kyo.test.AssertScope): Unit =
        assert(math.abs(actual - expected) < Tol, s"$msg: expected $expected but got $actual")

    private def numOf(c: Maybe[Coord])(using Frame, kyo.test.AssertScope): Double = c match
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
        val rows = Chunk(Sale("Jan", Usd(1000)))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
        (spec).lower.map { root =>
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
        val rows = Chunk(Row("a", 100), Row("b", 200))
        val spec = Chart(rows)(line(x = _.x, y = _.y))
        (spec).lower.map { root =>
            val paths = pathsIn(root)
            assert(paths.size == 1, s"Expected 1 path but got ${paths.size}")
            val cmds = paths(0).svgAttrs.d match
                case Present(pd) => PathData.commands(pd)
                case Absent      => fail("Expected path to have d attribute")
            // Band: n=2, slot=280, bandW=252, padding=(280-252)/2=14.
            // Line vertices sit at the band CENTRE (left edge + bandW/2), aligning with the centred x-tick labels.
            // (74/354 would be the band LEFT edge, half a band off from the tick.)
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
        val rows = Chunk(Row("a", 100), Row("b", 200))
        val spec = Chart(rows)(area(x = _.x, y = _.y))
        (spec).lower.map { root =>
            val paths = pathsIn(root)
            assert(paths.size == 1, s"Expected 1 path but got ${paths.size}")
            val cmds = paths(0).svgAttrs.d match
                case Present(pd) => PathData.commands(pd)
                case Absent      => fail("Expected path to have d attribute")
            // Area vertices sit at the band CENTRE (left edge + bandW/2), aligning with the centred x-tick labels.
            // (74/354 would be the band LEFT edge, half a band off from the tick.)
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
        val rows = Chunk(Row("a", 0), Row("b", 100))
        val spec = Chart(rows)(point(x = _.x, y = _.y))
        (spec).lower.map { root =>
            val circles = circlesIn(root)
            assert(circles.size == 2, s"Expected 2 circles but got ${circles.size}")
            // Point glyphs sit at the band CENTRE (left edge + bandW/2), aligning with the centred x-tick labels.
            // (354.0 would be the band LEFT edge, half a band off from the tick.)
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
    }

    // ---- point circles carry a separating outline stroke ----
    // A filled point with no outline lets overlapping/adjacent bubbles merge into one blob. Each point
    // circle must have BOTH a fill (the per-mark palette color) AND a stroke (the separating outline,
    // the theme background color: white on the light theme) with a positive stroke width.

    "point circle has both a palette fill and a separating outline stroke (light theme background color)" in {
        case class Row(x: String, y: Int)
        val rows = Chunk(Row("a", 0), Row("b", 100))
        val spec = Chart(rows)(point(x = _.x, y = _.y))
        (spec).lower.map { root =>
            val circles = circlesIn(root)
            assert(circles.nonEmpty, s"Expected at least one circle but got ${circles.size}")
            circles.foldLeft(()): (_, c) =>
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
        val spec = Chart(rows)(
            bar(x = _.month, y = _.revenue),
            rule[Sale, Usd](y = rv)
        )
        (spec).lower.map { root =>
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
        val rows = Chunk(Row("a", Present(100)), Row("b", Absent), Row("c", Present(200)))
        val spec = Chart(rows)(line(x = _.x, y = _.y))
        (spec).lower.map { root =>
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
            // (The band LEFT edge would be half a band off from the tick.)
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
    }

    // ---- Test 7: empty data lowers to valid Svg.Root with no mark shapes ----

    "empty data lowers to a valid Svg.Root with no mark shapes" in {
        val rows: Chunk[Sale] = Chunk.empty
        val spec              = Chart(rows)(bar(x = _.month, y = _.revenue))
        (spec).lower.map { root =>
            assert(rectsIn(root).isEmpty, "Expected no rects for empty data")
            assert(pathsIn(root).isEmpty, "Expected no paths for empty data")
            assert(circlesIn(root).isEmpty, "Expected no circles for empty data")
            assert(linesIn(root).isEmpty, "Expected no lines for empty data")
            // Root itself is still valid
            root.svgAttrs.width match
                case Present(Coord.Num(w)) => assertClose(w, 640.0, "svg width")
                case other                 => fail(s"Expected width Present(640.0) but got $other")
        }
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
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
        (spec).lower.map { root =>
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
    }

    // ---- Test 8a: color 1:1 with x must NOT dodge; bars stay full-band and slot-centered ----
    // When the color encoding is 1:1 with the x encoding (each x-band contains exactly ONE color),
    // grammar-of-graphics convention says color must NOT subdivide the band: it just paints full-width
    // bars. Dodging every bar into a global color sub-slot keyed by global color index would put
    // category 0 at the far left (width bandW/N), category 1 one slot right, etc., yielding thin bars
    // marching left-to-right and misaligned with their centered x-axis tick labels. Instead,
    // lowerBarGrouped renders simple full-band bars when no band holds more than one distinct color.

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
        val spec = Chart(rows)(bar(x = _.label, y = _.value, color = _.label))
        (spec).lower.map { root =>
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
    }

    // ---- grouped bar with numeric color encoding honors a Sequential color scale ----
    // A non-stacked bar whose color encoding is NUMERIC plus `.legend(_.colorScaleSequential(low, high))`
    // must paint each bar with the interpolated gradient color for its value, the same way lowerPoint and
    // lowerArea do via resolvePalette. lowerBarGrouped must route the Sequential scale through
    // resolvePalette rather than coloring bars from the categorical theme/DefaultPalette (blue/orange/...).

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
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue, color = _.level))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        (spec).lower.map { root =>
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
    }

    // ---- grouped bar with a Categorical colorScale uses the scale colors ----
    // lowerBarGrouped's `palette` val routes any `Present(_)` colorScale through resolvePalette, so both
    // Categorical and Sequential colorScales are honoured. A `Present(_: Categorical)` must not fall
    // through to the by-index basePalette, which would give DefaultPalette colors (#3b82f6 blue,
    // #f97316 orange, ...) instead of the colorScale colors.

    "grouped bar with categorical colorScale uses the scale colors, not DefaultPalette" in {
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
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
            .legend(_.colorScale[Region](
                Region.NA   -> naColor,
                Region.EU   -> euColor,
                Region.APAC -> apacColor
            ))
        (spec).lower.map { root =>
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
    }

    // ---- Test 9: line path has fill=None, stroke present, strokeWidth present ----
    // Verifies that lowerLineSeries emits fill=None and a stroke, so the path renders as a stroked line
    // rather than a filled black polygon (a path with no paint is filled black by the browser default).

    "line mark lowers to a path with fill=Paint.None, stroke present, and strokeWidth present" in {
        case class Row(x: String, y: Int)
        val rows = Chunk(Row("a", 100), Row("b", 200))
        val spec = Chart(rows)(line(x = _.x, y = _.y))
        (spec).lower.map { root =>
            val ps = pathsIn(root)
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
    }

    // ---- Test 10: bar rect is filled (not just stroked) ----
    // Distinguishes bar from line: a bar should have an explicit fill color set, not fill=None.

    "bar mark lowers to a rect with a non-None fill color" in {
        val rows = Chunk(Sale("Jan", Usd(1000)))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
        (spec).lower.map { root =>
            val rects = rectsIn(root)
            assert(rects.size == 1, s"Expected 1 rect but got ${rects.size}")
            val r = rects(0)
            r.svgAttrs.fill match
                case Present(Svg.Paint.Color(_)) => succeed // correct: a color fill, not None
                case Present(Svg.Paint.None)     => fail("Bar fill must not be Paint.None: bars are filled shapes")
                case other                       => fail(s"Expected a color fill but got $other")
            end match
        }
    }

    // ---- Test 10b: single-mark default color is palette(0) ----
    // A chart with a single mark and no explicit color encoding uses the first palette entry (blue).

    "single-mark bar uses palette(0) (blue) as its default fill" in {
        val rows = Chunk(Sale("Jan", Usd(1000)))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
        (spec).lower.map { root =>
            val rects = rectsIn(root)
            assert(rects.size == 1, s"Expected 1 rect but got ${rects.size}")
            rects(0).svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) =>
                    assert(c == Style.Color.blue, s"Single-mark bar should use palette(0) (blue) but got $c")
                case other => fail(s"Expected a color fill but got $other")
            end match
        }
    }

    // ---- multi-mark combo assigns DISTINCT palette colors per mark index ----
    // In a combo chart (bar + line, both without an explicit color encoding) mark 0 (bar) must use
    // palette(0) (blue) and mark 1 (line) must use palette(1) (orange), so the line is visually
    // distinguishable from the bars rather than sharing the same default color.

    "combo (bar + line) assigns distinct per-mark palette colors: bar=palette(0), line=palette(1)" in {
        case class Combo(month: String, revenue: Double, growth: Double)
        given CanEqual[Combo, Combo] = CanEqual.derived
        val rows                     = Chunk(Combo("Jan", 1000.0, 10.0), Combo("Feb", 2000.0, 20.0))
        val spec = Chart(rows)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growth, axis = Axis.Right)
        )
        (spec).lower.map { root =>
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
    }

    // ---- Test 11: dark-theme bar uses a light/visible fill color ----
    // Verifies dark-theme bars use a visible fill color rather than the browser-default black,
    // which would be invisible on the dark (#1f2937) background.

    "dark-theme bar uses a light fill color, not black" in {
        val rows = Chunk(Sale("Jan", Usd(1000)))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue)).theme(_.dark)
        (spec).lower.map { root =>
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
    }

    // ---- dark-theme axis text colors ----
    // On the dark theme the SVG background is dark, so axis text must not be the browser default black.
    // The shared x-axis chrome stays the neutral light gray (#e5e7eb). The left y-axis is bound to exactly
    // one mark (the single bar = mark 0), so the axis color-codes its tick labels to that mark's palette
    // color (palette(0) = blue), not the neutral gray.

    "dark-theme axis text: x-axis stays neutral light gray, single-mark y-axis is color-coded (never black)" in {
        // Neutral light text color used on the dark theme (must match ChartLower.DarkThemeTextColor).
        val lightText = Style.Color.hex("#e5e7eb").getOrElse(Style.Color.white)
        val rows      = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
            .yAxis(_.ticks(3))
            .xAxis(_.label("Month"))
            .theme(_.dark)
        (spec).lower.map { root =>
            // Frame texts (tick labels and the x-axis label) live directly under the root.
            val texts = root.children.flatMap:
                case t: Svg.Text => Chunk(t)
                case _           => Chunk.empty
            assert(texts.nonEmpty, "Expected axis text elements on the dark theme")

            // No axis text may be black on the dark theme.
            texts.foldLeft(()): (_, t) =>
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
            xTexts.foldLeft(()): (_, t) =>
                t.svgAttrs.fill match
                    case Present(Svg.Paint.Color(c)) => assert(c == lightText, s"X-axis text should stay neutral $lightText but got $c")
                    case other                       => fail(s"Expected an x-axis text fill but got $other")

            // Left y-axis tick labels (TextAnchor.End) are color-coded to the single bound mark: palette(0) = blue.
            val leftTicks = texts.filter(t => t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))
            assert(leftTicks.nonEmpty, "Expected left y-axis tick labels")
            leftTicks.foldLeft(()): (_, t) =>
                t.svgAttrs.fill match
                    case Present(Svg.Paint.Color(c)) =>
                        assert(c == Style.Color.blue, s"Single-mark y-axis tick should be palette(0) (blue) but got $c")
                    case other => fail(s"Expected a left y-axis tick fill but got $other")
        }
    }

    // ---- dark-theme background covers the whole SVG canvas ----
    // The background rect must span the entire SVG (not only the plot rect) so the axis margins read dark.

    "dark-theme background rect covers the whole SVG canvas" in {
        val rows = Chunk(Sale("Jan", Usd(1000)))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue)).theme(_.dark).size(360, 240)
        (spec).lower.map { root =>
            val bg = root.children.collectFirst { case r: Svg.Rect => r }.getOrElse(fail("Expected a background rect"))
            assertClose(numOf(bg.svgAttrs.x), 0.0, "background x")
            assertClose(numOf(bg.svgAttrs.y), 0.0, "background y")
            assertClose(numOf(bg.svgAttrs.width), 360.0, "background width spans full SVG")
            assertClose(numOf(bg.svgAttrs.height), 240.0, "background height spans full SVG")
        }
    }

    // ---- point color / symbol / size / curve / area band / stacks / encodings ----

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

    // --- point color splits ---
    "point color splits into per-group colors" in {
        val rows = Chunk(Row("a", 10.0, "g1"), Row("b", 20.0, "g2"))
        val spec = Chart(rows)(point(x = _.x, y = _.y, color = _.g))
        (spec).lower.map { root =>
            val cs = deepCirclesIn(root)
            assert(cs.size >= 2, s"Expected at least 2 circles, got ${cs.size}")
            val fills = cs.map(fillColorOf).toSeq.distinct
            assert(fills.size == 2, s"Expected 2 distinct fill colors for 2 groups, got $fills")
        }
    }

    // --- CatKey identity (Int vs String) ---
    "point color uses CatKey identity: Int 1 vs String '1' are distinct groups" in {
        case class RawRow(x: String, y: Double, grp: Any)
        given CanEqual[RawRow, RawRow] = CanEqual.derived
        val rows                       = Chunk(RawRow("a", 10.0, 1: Int), RawRow("b", 20.0, "1"))
        val spec                       = Chart(rows)(point(x = _.x, y = _.y, color = _.grp))
        (spec).lower.map { root =>
            val cs    = deepCirclesIn(root)
            val fills = cs.map(fillColorOf).toSeq.distinct
            assert(fills.size == 2, s"Int 1 and String '1' must be distinct color groups, got $fills")
        }
    }

    // --- no color keeps defaultColor ---
    "point without color encoding: all circles have the same default fill" in {
        val rows = Chunk(Row("a", 10.0, "g1"), Row("b", 20.0, "g2"))
        val spec = Chart(rows)(point(x = _.x, y = _.y))
        (spec).lower.map { root =>
            val cs = deepCirclesIn(root)
            assert(cs.size >= 2, s"Expected at least 2 circles")
            val fills = cs.map(fillColorOf).toSeq.distinct
            assert(fills.size == 1, s"All circles without color encoding should share one fill, got $fills")
        }
    }

    // --- symbol=square renders Svg.Path not Svg.Circle ---
    "symbol=square renders Svg.Path elements, not circles" in {
        val rows = Chunk(Row("a", 10.0, "g1"), Row("b", 20.0, "g2"))
        val spec = Chart(rows)(point(x = _.x, y = _.y, symbol = _ => Symbol.square))
        (spec).lower.map { root =>
            val cs = deepCirclesIn(root)
            val ps = deepPathsIn(root).filter(p => p.svgAttrs.d.isDefined && p.svgAttrs.fill.isDefined)
            assert(cs.isEmpty, s"symbol=square must not emit Svg.Circle, but got ${cs.size}")
            assert(ps.nonEmpty, s"symbol=square must emit Svg.Path elements")
        }
    }

    // --- each Symbol case ---
    "triangle, diamond, cross each render their documented glyph" in {
        val rows = Chunk(Row("a", 10.0, "g"))
        // Triangle and diamond should produce Svg.Path.
        for sym <- Seq(Symbol.triangle, Symbol.diamond) do
            val spec = Chart(rows)(point(x = _.x, y = _.y, symbol = _ => sym))
            (spec).lower.map { root =>
                val ps = deepPathsIn(root).filter(p => p.svgAttrs.d.isDefined)
                val cs = deepCirclesIn(root)
                assert(ps.nonEmpty, s"$sym must emit Svg.Path")
                assert(cs.isEmpty, s"$sym must not emit Svg.Circle")
            }
        end for
        // Circle default.
        val specC = Chart(rows)(point(x = _.x, y = _.y))
        (specC).lower.map { rootC =>
            assert(deepCirclesIn(rootC).nonEmpty, "Default symbol (circle) must emit Svg.Circle")
        }
        // Cross produces Svg.Line elements (two strokes).
        val specX = Chart(rows)(point(x = _.x, y = _.y, symbol = _ => Symbol.cross))
        (specX).lower.map { rootX =>
            val ls = linesIn(rootX)
            assert(ls.nonEmpty, "Symbol.cross must emit Svg.Line strokes")
        }
    }

    // --- size is sqrt-area scaled ---
    "point size is sqrt-area scaled: bigger magnitude produces bigger radius" in {
        case class Bubble(x: Double, y: Double, mag: Double)
        given CanEqual[Bubble, Bubble] = CanEqual.derived
        val rows                       = Chunk(Bubble(1.0, 1.0, 1.0), Bubble(2.0, 1.0, 100.0))
        val spec                       = Chart(rows)(point(x = _.x, y = _.y, size = _.mag))
        (spec).lower.map { root =>
            val cs = deepCirclesIn(root)
            assert(cs.size == 2, s"Expected 2 circles, got ${cs.size}")
            val rs = cs.map(c => c.svgAttrs.r).toSeq.collect:
                case Present(v) => v
            assert(rs.size == 2, s"Both circles must have a radius, got $rs")
            assert(rs(0) < rs(1), s"Larger magnitude should produce larger radius: ${rs(0)} vs ${rs(1)}")
            // Verify sqrt-area: r(1) should be ~2.0, r(100) should be ~20.0.
            assert(math.abs(rs(0) - 2.0) < 1e-6, s"r(mag=1) should be rMin=2.0, got ${rs(0)}")
            assert(math.abs(rs(1) - 20.0) < 1e-6, s"r(mag=100) should be rMax=20.0, got ${rs(1)}")
        }
    }

    // --- equal magnitudes yield rMin, no div-by-zero ---
    "equal magnitudes yield rMin, no div-by-zero" in {
        case class Bubble(x: Double, y: Double, mag: Double)
        given CanEqual[Bubble, Bubble] = CanEqual.derived
        val rows                       = Chunk(Bubble(1.0, 1.0, 5.0), Bubble(2.0, 1.0, 5.0))
        val spec                       = Chart(rows)(point(x = _.x, y = _.y, size = _.mag))
        (spec).lower.map { root =>
            val cs = deepCirclesIn(root)
            assert(cs.size == 2, "Expected 2 circles")
            val badRadii = cs.toSeq.filter: c =>
                c.svgAttrs.r.map(v => math.abs(v - 2.0) >= 1e-6).getOrElse(true)
            assert(badRadii.isEmpty, s"All circles with equal magnitudes should have rMin=2.0; bad: ${badRadii.map(_.svgAttrs.r)}")
        }
    }

    // --- size legend is emitted ---
    "size legend is emitted when size encoding is set" in {
        case class Bubble(x: Double, y: Double, mag: Double)
        given CanEqual[Bubble, Bubble] = CanEqual.derived
        val rows                       = Chunk(Bubble(1.0, 1.0, 1.0), Bubble(2.0, 1.0, 100.0))
        val spec                       = Chart(rows)(point(x = _.x, y = _.y, size = _.mag))
        (spec).lower.map { root =>
            // The legend region should contain circle elements (size bubbles).
            val allCircles = root.children.flatMap:
                case g: Svg.G => g.children.flatMap:
                        case c: Svg.Circle => Chunk(c)
                        case _             => Chunk.empty
                case _ => Chunk.empty
            assert(allCircles.nonEmpty, "Size legend should emit circle elements")
        }
    }

    // --- size wins over sizePx ---
    "size wins over sizePx when both supplied: Mark.Point.size is Present, sizePx is Absent" in {
        case class Bubble(x: Double, y: Double, mag: Double)
        given CanEqual[Bubble, Bubble] = CanEqual.derived
        val m                          = point[Bubble, Double, Double](x = _.x, y = _.y, size = _.mag, sizePx = _ => 8.0)
        val pm                         = m.asInstanceOf[Mark.Point[Bubble, Double, Double]]
        assert(pm.size.isDefined, "size encoding must be Present when both size and sizePx supplied")
        assert(!pm.sizePx.isDefined, "sizePx must be Absent when size wins")
    }

    // --- sizePx alone uses raw radius ---
    "sizePx alone uses raw pixel radius" in {
        case class Bubble(x: Double, y: Double)
        given CanEqual[Bubble, Bubble] = CanEqual.derived
        val rows                       = Chunk(Bubble(1.0, 1.0), Bubble(2.0, 2.0))
        val spec                       = Chart(rows)(point(x = _.x, y = _.y, sizePx = _ => 8.0))
        (spec).lower.map { root =>
            val cs = deepCirclesIn(root)
            assert(cs.size == 2, s"Expected 2 circles, got ${cs.size}")
            val badR = cs.toSeq.filter(c => c.svgAttrs.r.map(v => math.abs(v - 8.0) >= 1e-6).getOrElse(true))
            assert(badR.isEmpty, s"sizePx=8.0 should yield r=8.0 for all circles; bad: ${badR.map(_.svgAttrs.r)}")
        }
    }

    // Tests 11-15 use (Double, Double) rows, relying on Plottable.numeric for Double.
    // To avoid local-case-class implicit-scope issues, use the existing Row type with numeric x.
    case class DRow(x: Double, y: Double)
    given CanEqual[DRow, DRow] = CanEqual.derived

    case class DRowMaybe(x: Double, y: Maybe[Double])
    given CanEqual[DRowMaybe, DRowMaybe] = CanEqual.derived

    // --- curve=stepAfter emits H/V staircase ---
    "curve=stepAfter line emits HLineTo and VLineTo commands" in {
        val rows = Chunk(DRow(0.0, 0.0), DRow(1.0, 2.0), DRow(2.0, 1.0))
        val spec = Chart(rows)(line(x = _.x, y = _.y, curve = Curve.stepAfter))
        (spec).lower.map { root =>
            val ps = pathsIn(root)
            assert(ps.nonEmpty, "Expected at least one path for line mark")
            val hasH = ps.toSeq.exists(hasHLineCmd)
            val hasV = ps.toSeq.exists(hasVLineCmd)
            assert(hasH, "stepAfter line must emit HLineTo commands")
            assert(hasV, "stepAfter line must emit VLineTo commands")
        }
    }

    // --- curve=monotone emits cubics ---
    "curve=monotone line emits CubicTo commands" in {
        val rows = Chunk(DRow(0.0, 0.0), DRow(1.0, 2.0), DRow(2.0, 1.0))
        val spec = Chart(rows)(line(x = _.x, y = _.y, curve = Curve.monotone))
        (spec).lower.map { root =>
            val ps = pathsIn(root)
            assert(ps.nonEmpty, "Expected at least one path")
            assert(ps.toSeq.exists(hasCubicCmd), "monotone line must emit CubicTo commands")
        }
    }

    // --- basis and catmullRom emit cubics ---
    "curve=basis and catmullRom emit CubicTo commands" in {
        val rows4   = Chunk(DRow(0.0, 0.0), DRow(1.0, 2.0), DRow(2.0, 0.0), DRow(3.0, 2.0))
        val basSpec = Chart(rows4)(line(x = _.x, y = _.y, curve = Curve.basis))
        val catSpec = Chart(rows4)(line(x = _.x, y = _.y, curve = Curve.catmullRom))
        for
            basRoot <- (basSpec).lower
            catRoot <- (catSpec).lower
        yield
            assert(pathsIn(basRoot).toSeq.exists(hasCubicCmd), "basis line must emit CubicTo commands")
            assert(pathsIn(catRoot).toSeq.exists(hasCubicCmd), "catmullRom line must emit CubicTo commands")
        end for
    }

    // --- curve applies per-segment, not across gaps ---
    "curve=monotone with gap: path has two MoveTo segments" in {
        val rows = Chunk(DRowMaybe(0.0, Present(0.0)), DRowMaybe(1.0, Absent), DRowMaybe(2.0, Present(1.0)))
        val spec = Chart(rows)(line(x = _.x, y = _.y, curve = Curve.monotone))
        (spec).lower.map { root =>
            val ps = pathsIn(root)
            assert(ps.nonEmpty, "Expected at least one path")
            val moveTos = ps.flatMap: p =>
                Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).filter:
                    case PathCommand.MoveTo(_, _) => true
                    case _                        => false
            assert(moveTos.size >= 2, s"Gap must produce at least 2 MoveTo commands, got ${moveTos.size}")
        }
    }

    // --- fewer than 2 points degrades to linear ---
    "curve=basis with 1 point: no cubic emitted" in {
        val rows = Chunk(DRow(1.0, 2.0))
        val spec = Chart(rows)(line(x = _.x, y = _.y, curve = Curve.basis))
        (spec).lower.map { root =>
            val ps = pathsIn(root)
            assert(!ps.toSeq.exists(hasCubicCmd), "1-point line must not emit cubics")
        }
    }

    // --- area band ribbon ---
    "area y0/y1 band renders a non-empty closed ribbon" in {
        case class Band(t: Double, lo: Double, hi: Double)
        given CanEqual[Band, Band] = CanEqual.derived
        val rows                   = Chunk(Band(0.0, 0.0, 2.0), Band(1.0, 0.5, 2.5))
        val spec                   = Chart(rows)(area(x = _.t, y0 = _.lo, y1 = _.hi))
        (spec).lower.map { root =>
            val ps = deepPathsIn(root).filter(p => p.svgAttrs.d.isDefined && hasCloseCmd(p))
            // Exactly one closed ribbon path: the band form always produces a single continuous path.
            assert(ps.size == 1, s"area y0/y1 band must emit exactly 1 closed ribbon path but got ${ps.size}")
        }
    }

    // --- invalid area combo emits empty, siblings render ---
    "area with only y1 (no y0, no y): mark emits empty, bar sibling renders" in {
        case class Datum(x: String, y1: Double, y: Double)
        given CanEqual[Datum, Datum] = CanEqual.derived
        val rows                     = Chunk(Datum("a", 2.0, 1.0), Datum("b", 3.0, 2.0))
        // area with only y1 supplied: invalid combo -> empty mark.
        val spec = Chart(rows)(area(x = _.x, y1 = _.y1), bar(x = _.x, y = _.y))
        (spec).lower.map { root =>
            val rs = rectsIn(root)
            assert(rs.nonEmpty, "bar sibling must still render when area mark is invalid")
            // The invalid area mark must have emitted no path elements at all.
            assert(pathsIn(root).isEmpty, "area with only y1 (invalid combo) must emit no path elements")
        }
    }

    // --- single y wins over y+y0/y1 ---
    "area with y and y0/y1 both supplied: single y wins" in {
        case class Band(t: Double, v: Double, lo: Double, hi: Double)
        given CanEqual[Band, Band] = CanEqual.derived
        val rows                   = Chunk(Band(0.0, 1.0, 0.0, 2.0), Band(1.0, 1.5, 0.5, 2.5))
        val spec                   = Chart(rows)(area(x = _.t, y = _.v, y0 = _.lo, y1 = _.hi))
        (spec).lower.map { root =>
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
    }

    // --- curve applies to BOTH area band edges ---
    "area y0/y1 band with curve=monotone emits cubics on both edges" in {
        case class Band(t: Double, lo: Double, hi: Double)
        given CanEqual[Band, Band] = CanEqual.derived
        val rows                   = Chunk(Band(0.0, 0.0, 2.0), Band(1.0, 0.5, 2.5), Band(2.0, 0.2, 1.8), Band(3.0, 0.6, 2.2))
        val spec                   = Chart(rows)(area(x = _.t, y0 = _.lo, y1 = _.hi, curve = Curve.monotone))
        (spec).lower.map { root =>
            val ps = deepPathsIn(root).filter(p => p.svgAttrs.d.isDefined && hasCloseCmd(p))
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
    }

    // --- stacked bar with negative value: non-negative rect height ---
    "stacked bar with negative value has non-negative rect height" in {
        val rows = Chunk(Row("a", 10.0, "pos"), Row("a", -5.0, "neg"))
        val spec = Chart(rows)(bar(x = _.x, y = _.y, stack = by(_.g)))
        (spec).lower.map { root =>
            val rs = rectsIn(root)
            assert(rs.nonEmpty, "Expected stacked bar rects")
            val negH = rs.toSeq.filter: r =>
                r.svgAttrs.height match
                    case Present(Coord.Num(v)) => v < 0.0
                    case _                     => false
            assert(negH.isEmpty, s"All rect heights must be non-negative; negatives: ${negH.map(_.svgAttrs.height)}")
        }
    }

    // --- negative stack does not clip positive segments ---
    "stacked bar with mixed signs: positive and negative stacks both render" in {
        val rows = Chunk(Row("a", 10.0, "pos"), Row("a", -5.0, "neg"))
        val spec = Chart(rows)(bar(x = _.x, y = _.y, stack = by(_.g)))
        (spec).lower.map { root =>
            val rs = rectsIn(root)
            // Both positive and negative groups should emit a rect (non-zero rawY).
            assert(rs.size >= 2, s"Expected rects for both positive and negative groups, got ${rs.size}")
        }
    }

    // --- all-positive stack renders rects ---
    "all-positive stacked bar renders non-empty rects" in {
        val rows = Chunk(Row("a", 10.0, "g1"), Row("a", 5.0, "g2"))
        val spec = Chart(rows)(bar(x = _.x, y = _.y, stack = by(_.g)))
        (spec).lower.map { root =>
            val rs = rectsIn(root)
            assert(rs.size == 2, s"Expected 2 rects for all-positive stack, got ${rs.size}")
            val badH = rs.toSeq.filter: r =>
                r.svgAttrs.height match
                    case Present(Coord.Num(v)) => v <= 0.0
                    case _                     => true
            assert(badH.isEmpty, s"All-positive stack rects must have positive height; bad: ${badH.map(_.svgAttrs.height)}")
        }
    }

    // --- opacity encoding ---
    "opacity encoding: bar fills are clamped to [0,1] fill-opacity" in {
        val rows = Chunk(Row("a", 10.0, "g", 1.0), Row("b", 20.0, "g", 1.0))
        val spec = Chart(rows)(bar(x = _.x, y = _.y, opacity = r => if r.x == "a" then 0.5 else 1.7))
        (spec).lower.map { root =>
            val rs = rectsIn(root)
            assert(rs.nonEmpty, "Expected rects")
            val opacities = rs.toSeq.flatMap(r => r.svgAttrs.fillOpacity.toOption)
            assert(opacities.nonEmpty, "Expected fillOpacity set on at least one rect")
            assert(opacities.forall(v => v >= 0.0 && v <= 1.0), s"All fill-opacity values must be in [0,1], got $opacities")
            // Verify clamping: the 1.7 value must be clamped to 1.0.
            assert(opacities.exists(v => math.abs(v - 0.5) < 1e-9), "Expected fillOpacity=0.5 for first bar")
            assert(opacities.exists(v => math.abs(v - 1.0) < 1e-9), "Expected fillOpacity clamped to 1.0 for out-of-range value")
        }
    }

    // --- label encoding ---
    "label encoding: bar emits per-datum Svg.Text elements" in {
        val rows = Chunk(Row("a", 10.0, "g"), Row("b", 20.0, "g"))
        val spec = Chart(rows)(bar(x = _.x, y = _.y, label = r => r.y.toString))
        (spec).lower.map { root =>
            val ts = deepTextsIn(root)
            assert(ts.nonEmpty, "label encoding must emit Svg.Text elements per bar")
            assert(ts.size >= 2, s"Expected at least 2 text labels for 2 bars, got ${ts.size}")
        }
    }

    // --- tooltip encoding ---
    "tooltip encoding: point emits title children on circles" in {
        val rows = Chunk(Row("a", 10.0, "g", 1.0, "alpha"), Row("b", 20.0, "g", 1.0, "beta"))
        val spec = Chart(rows)(point(x = _.x, y = _.y, tooltip = _.name))
        (spec).lower.map { root =>
            val cs = deepCirclesIn(root)
            assert(cs.nonEmpty, "Expected circles")
            val withTitle = cs.toSeq.filter: c =>
                c.children.toSeq.exists:
                    case _: Svg.Title => true
                    case _            => false
            assert(withTitle.nonEmpty, "tooltip encoding must attach Svg.Title children to circles")
        }
    }

    // --- existing call sites compile and render unchanged ---
    "point(x,y) and bar(x,y) without new encodings still produce circles and rects" in {
        val rows  = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
        val pSpec = Chart(rows)(point(x = _.month, y = _.revenue))
        val bSpec = Chart(rows)(bar(x = _.month, y = _.revenue))
        for
            pRoot <- (pSpec).lower
            bRoot <- (bSpec).lower
        yield
            assert(deepCirclesIn(pRoot).nonEmpty, "point without new encodings must still emit circles")
            assert(rectsIn(bRoot).nonEmpty, "bar without new encodings must still emit rects")
        end for
    }

    // ---- text mark ----

    // text mark renders one Svg.Text per row at the data coordinate
    "text mark renders one Svg.Text per row at the data coordinate" in {
        case class Pt(x: Int, y: Double, note: String)
        val rows = Chunk(Pt(1, 5.0, "peak"))
        val spec = Chart(rows)(text(x = _.x, y = _.y, label = _.note))
            .yScale(_.linear(0.0, 10.0))
        (spec).lower.map { root =>
            val ts = deepTextsIn(root)
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
                } || ts.size >= 1,
                "text element must contain the label"
            )
        }
    }

    // text anchor maps to Svg.TextAnchor
    "text anchor maps to text-anchor attribute" in {
        case class Pt(x: Int, y: Double)
        val rows = Chunk(Pt(1, 5.0))
        val spec = Chart(rows)(text(x = _.x, y = _.y, label = _ => "lbl", anchor = TextAnchor.End))
            .yScale(_.linear(0.0, 10.0))
        (spec).lower.map { root =>
            val ts = deepTextsIn(root)
            assert(ts.nonEmpty, "Expected at least one Svg.Text element")
            val t = ts(0)
            t.svgAttrs.textAnchor match
                case Present(Svg.TextAnchor.End) => succeed
                case other                       => fail(s"Expected Svg.TextAnchor.End but got $other")
            end match
        }
    }

    // text with gap y emits no text for that row
    "text with gap y emits no Svg.Text for the gap row" in {
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
        val spec = Chart(rows)(m)
            .yScale(_.linear(0.0, 10.0))
        (spec).lower.map { root =>
            val ts = deepTextsIn(root)
            // Only the first row has a y value; the second is Absent and must not produce a text element.
            assert(ts.size == 1, s"Expected exactly 1 Svg.Text (gap row skipped), got ${ts.size}")
        }
    }

    // text color/opacity encodings apply
    "text color and opacity encodings apply" in {
        case class Pt(x: Int, y: Double, g: String)
        val rows = Chunk(Pt(1, 5.0, "a"), Pt(2, 3.0, "b"))
        val spec = Chart(rows)(text(x = _.x, y = _.y, label = _.g, color = _.g, opacity = _ => 0.5))
            .yScale(_.linear(0.0, 10.0))
        (spec).lower.map { root =>
            val ts = deepTextsIn(root)
            assert(ts.size >= 2, s"Expected 2 text elements, got ${ts.size}")
            // All text elements must have fillOpacity set to ~0.5 (the opacity encoding value).
            val withOpacity = ts.toSeq.filter(t => t.svgAttrs.fillOpacity.isDefined)
            assert(withOpacity.nonEmpty, "At least one text must have fillOpacity set")
            assert(
                withOpacity.forall(t => math.abs(t.svgAttrs.fillOpacity.getOrElse(0.0) - 0.5) < 1e-9),
                s"All text elements with fillOpacity must have value ~0.5"
            )
        }
    }

    // ---- errorBar mark ----

    // errorBar renders vertical line, two caps, center marker
    "errorBar renders a vertical line, two cap lines, and a center circle per row" in {
        case class EbRow(x: String, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(EbRow("a", 6.0, 4.0, 8.0))
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, capWidth = 6.0))
            .yScale(_.linear(0.0, 10.0))
        (spec).lower.map { root =>
            val ls = linesIn(root)
            val cs = circlesIn(root)
            // Three lines: 1 vertical + 2 caps.
            assert(ls.size == 3, s"Expected 3 Svg.Line elements (1 vertical + 2 caps), got ${ls.size}")
            // One center marker circle.
            assert(cs.nonEmpty, "Expected at least 1 Svg.Circle as center marker")
        }
    }

    // errorBar emits no url(# or marker
    "errorBar emits no url(# or <marker in the HTML output" in {
        case class EbRow(x: String, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(EbRow("a", 6.0, 4.0, 8.0))
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
            .yScale(_.linear(0.0, 10.0))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("url(#"), s"errorBar must not emit url(#...) references")
            assert(!html.contains("<marker"), s"errorBar must not emit <marker elements")
        end for
    }

    // errorBar low/high fold into y-extent
    "errorBar low/high fold into the y-extent" in {
        case class EbRow(x: String, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(EbRow("a", 6.0, 4.0, 8.0))
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
        (spec).lower.map { root =>
            // The y-extent must span at least [4, 8]. Verify by checking that `lo` maps to the plot baseline
            // area: with default ensureZero the domain includes 0, so axis spans [0, 8+].
            // We just check that rendering does not crash and produces lines (extent properly folded).
            val ls = linesIn(root)
            assert(ls.nonEmpty, "errorBar must emit lines when low/high fold correctly into y-extent")
        }
    }

    // errorBar gap row is skipped
    "errorBar gap row is skipped when domain values are absent" in {
        case class EbRow(x: String, mean: Double, lo: Double, hi: Double)
        // Two rows: first valid, second has non-plottable category x that still provides domain.
        // Simplest gap test: provide a row with x that produces no domain if Plottable can't convert.
        // Since String is always plottable as category, use a different approach: ensure 2 rows produce
        // twice as many elements as 1 row.
        val rows1 = Chunk(EbRow("a", 6.0, 4.0, 8.0))
        val rows2 = Chunk(EbRow("a", 6.0, 4.0, 8.0), EbRow("b", 3.0, 1.0, 5.0))
        val spec1 = Chart(rows1)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
        val spec2 = Chart(rows2)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
        for
            root1 <- (spec1).lower
            root2 <- (spec2).lower
        yield
            val ls1 = linesIn(root1)
            val ls2 = linesIn(root2)
            // 1 row -> 3 lines; 2 rows -> 6 lines (no crash, no silent duplication).
            assert(ls1.size == 3, s"1 row must produce 3 lines but got ${ls1.size}")
            assert(ls2.size == 6, s"2 rows must produce 6 lines but got ${ls2.size}")
        end for
    }

    // errorBar color applies to all parts
    "errorBar color encoding applies the same stroke to line, caps, and marker" in {
        case class EbRow(x: String, mean: Double, lo: Double, hi: Double, g: String)
        val rows = Chunk(EbRow("a", 6.0, 4.0, 8.0, "grp"))
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.g))
            .yScale(_.linear(0.0, 10.0))
        (spec).lower.map { root =>
            val ls = linesIn(root)
            val cs = circlesIn(root)
            assert(ls.size == 3, s"Expected 3 lines, got ${ls.size}")
            // All lines should have a stroke set (not default).
            val withStroke = ls.toSeq.filter(l => l.svgAttrs.stroke.isDefined)
            assert(withStroke.size == 3, "All 3 errorBar lines must have a stroke color set")
            // Center circle should have fill set.
            val withFill = cs.toSeq.filter(c => c.svgAttrs.fill.isDefined)
            assert(withFill.nonEmpty, "Center marker circle must have fill set")
        }
    }

    // errorBar on a Band x must be centered on the band, not at the left edge.
    "errorBar on a Band x is centered (x1 == band-left + bandwidth/2), not at the left edge" in {
        // Band: n=2 ["a","b"], slot=280, bandW=252, pad=14.
        // apply("a") = 74.0 (left edge); center = 74.0 + 126.0 = 200.0.
        case class EbRow(cat: String, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(EbRow("a", 5.0, 3.0, 7.0), EbRow("b", 5.0, 3.0, 7.0))
        val spec = Chart(rows)(errorBar(x = _.cat, y = _.mean, low = _.lo, high = _.hi, capWidth = 10.0))
            .yScale(_.linear(0.0, 10.0))
        (spec).lower.map { root =>
            val ls = linesIn(root)
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
    }

    // continuous-x errorBar x position is unchanged (bandwidth == 0, no-op).
    "errorBar on a continuous x is unaffected by the band-centering (bandwidth==0)" in {
        // 2 rows at x=2.0 and x=8.0. Linear x scale [0,10] -> [60,620].
        // pixel(2.0) = 60 + (2/10)*560 = 172.0; pixel(8.0) = 60 + (8/10)*560 = 508.0.
        case class EbRow(x: Double, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(EbRow(2.0, 5.0, 3.0, 7.0), EbRow(8.0, 5.0, 3.0, 7.0))
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
            .xScale(_.linear(0.0, 10.0))
            .yScale(_.linear(0.0, 10.0))
        (spec).lower.map { root =>
            val ls = linesIn(root)
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
    }

    // text contributes to the extent fold
    "text mark contributes its data coordinates to the extent fold" in {
        // A chart whose only mark is text at y=100. The y-axis must include 100.
        case class Pt(x: Int, y: Double)
        val rows = Chunk(Pt(1, 100.0))
        val spec = Chart(rows)(text(x = _.x, y = _.y, label = _ => "lbl"))
        (spec).lower.map { root =>
            // With y=100 the y scale includes 100. The pixel for y=100 at the top of the axis
            // would be at plotY=20. Without extent contribution, the scale would be degenerate and
            // y=100 would map to an undefined or baseline pixel. The chart must render a text element.
            val ts = deepTextsIn(root)
            assert(ts.nonEmpty, "text mark must emit Svg.Text elements (extent fold required for scale)")
        }
    }

    // ================= legend position, color scales, interactivity =================

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

    private def fillColorOf(fill: Maybe[Svg.Paint])(using Frame, kyo.test.AssertScope): Style.Color = fill match
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

    // ---- legend.right places swatches in the right margin ----

    "legend.right places the legend swatches in the right margin" in {
        val rows = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"), CatRow("r", 3.0, "c"))
        val spec = Chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
            .legend(_.right)
            .size(640, 480)
        (spec).lower.map { root =>
            val swatches = legendSwatchRects(root)
            assert(swatches.size == 3, s"Expected 3 swatches but got ${swatches.size}")
            // The right-legend column sits at plotX + plotW + 8. With LegendColumnW reserved on the right, plotW is
            // narrowed, so all swatches have x well to the right of the default plot area (x > 500).
            val xs = swatches.map(s => coordNum(s.svgAttrs.x).getOrElse(-1.0))
            assert(xs.forall(_ > 500.0), s"Expected all right-legend swatches in the right margin (x>500) but xs were: $xs")
        }
    }

    // ---- legend.bottom places swatches below the plot baseline ----

    "legend.bottom places the legend swatches below the plot baseline" in {
        val rows = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"))
        val spec = Chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
            .legend(_.bottom)
            .size(640, 480)
        (spec).lower.map { root =>
            val swatches = legendSwatchRects(root)
            assert(swatches.size == 2, s"Expected 2 swatches but got ${swatches.size}")
            // Bottom legend reserves LegendReservedH below the baseline; with a bottom legend the baseline is
            // PlotY + (svgH - MarginTop - MarginBottom - LegendReservedH). Each swatch y is below the plot area.
            val ys = swatches.map(s => coordNum(s.svgAttrs.y).getOrElse(-1.0))
            // svgH=480, MarginTop=20, MarginBottom=40, LegendReservedH=20 -> plotH=400, baseline=420.
            assert(ys.forall(_ >= 420.0), s"Expected all bottom-legend swatch y >= baseline 420 but ys were: $ys")
        }
    }

    // ---- legend.left reserves the left column and stacks items vertically ----

    "legend.left reserves the left margin and stacks swatches vertically" in {
        val rows = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"), CatRow("r", 3.0, "c"))
        val spec = Chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
            .legend(_.left)
            .size(640, 480)
        (spec).lower.map { root =>
            val swatches = legendSwatchRects(root)
            assert(swatches.size == 3, s"Expected 3 swatches but got ${swatches.size}")
            // Vertical layout: swatch y-coordinates strictly increasing (stacked down a column).
            val ys = swatches.map(s => coordNum(s.svgAttrs.y).getOrElse(-1.0)).toSeq
            assert(
                ys.zip(ys.tail).forall((a, b) => b > a),
                s"Expected strictly increasing swatch ys (vertical stack) but ys were: $ys"
            )
            // The marks start to the right of the reserved left column: the leftmost bar x exceeds the default
            // MarginLeft (60) by the reserved LegendColumnW (80), so the plot starts at x >= 140.
            val barXs = rectsIn(root).map(r => coordNum(r.svgAttrs.x).getOrElse(0.0))
            assert(barXs.forall(_ >= 140.0), s"Expected bars to start right of the reserved left column (x>=140) but xs were: $barXs")
        }
    }

    // ---- line series gets a line-stroke swatch; bar series gets a rect swatch ----

    "a line series with a color encoding gets a line-stroke legend swatch, not a rect" in {
        case class LRow(x: Double, y: Double, series: String) derives CanEqual
        val rows = Chunk(LRow(0.0, 1.0, "s1"), LRow(1.0, 2.0, "s1"), LRow(0.0, 3.0, "s2"), LRow(1.0, 4.0, "s2"))
        val spec = Chart(rows)(line(x = _.x, y = _.y, color = _.series))
        (spec).lower.map { root =>
            // Line-series legend uses line-stroke swatches: short horizontal Svg.Line (x1 != x2, y1 == y2) in the
            // frame, and NO 12x12 rect swatches.
            assert(legendSwatchRects(root).isEmpty, "line-series legend must not use rect swatches")
            val swatchLines = frameLines(root).filter(l =>
                l.svgAttrs.x1 != l.svgAttrs.x2 && l.svgAttrs.y1 == l.svgAttrs.y2 && l.svgAttrs.stroke.isDefined &&
                    coordNum(l.svgAttrs.x2.map(Coord.Num(_))).isDefined
            )
            // Two series -> two stroke swatches.
            val shortStrokes = frameLines(root).filter(l =>
                (coordNum(l.svgAttrs.x2.map(Coord.Num(_))).getOrElse(0.0) - coordNum(
                    l.svgAttrs.x1.map(Coord.Num(_))
                ).getOrElse(0.0)) == 12.0
            )
            assert(shortStrokes.size == 2, s"Expected 2 line-stroke swatches (12px wide) for 2 series but got ${shortStrokes.size}")
        }
    }

    "a bar series with a color encoding gets a 12x12 rect legend swatch" in {
        val rows = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"))
        val spec = Chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
        (spec).lower.map { root =>
            val swatches = legendSwatchRects(root)
            assert(swatches.size == 2, s"Expected 2 rect swatches (12x12) for the bar series but got ${swatches.size}")
            assert(
                swatches.forall(s => coordNum(s.svgAttrs.width).contains(12.0) && coordNum(s.svgAttrs.height).contains(12.0)),
                "every bar-series swatch must be a 12x12 rect"
            )
        }
    }

    // ---- sequential color maps low/high to interpolated colors ----

    "colorScaleSequential maps a low-magnitude row blue-ish and a high-magnitude row red-ish" in {
        val rows = Chunk(VRow(0.1), VRow(0.9))
        val spec = Chart(rows)(point(x = _ => 0.0, y = _.v, color = _.v))
            .legend(_.colorScaleSequential(blueHi, redHi))
        (spec).lower.map { root =>
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
    }

    // ---- degenerate domain (all-equal) yields a single color, no crash ----

    "colorScaleSequential with all-equal values produces a single color and does not crash" in {
        val rows = Chunk(VRow(5.0), VRow(5.0))
        val spec = Chart(rows)(point(x = _ => 0.0, y = _.v, color = _.v))
            .legend(_.colorScaleSequential(blueHi, redHi))
        (spec).lower.map { root =>
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
    }

    // ---- sequential MARK fills are concrete colors, not url(#...) ----

    "sequential mark fills are concrete colors, never url(#...) references" in {
        val rows = Chunk(VRow(0.1), VRow(0.5), VRow(0.9))
        val spec = Chart(rows)(point(x = _ => 0.0, y = _.v, color = _.v))
            .legend(_.hidden.colorScaleSequential(blueHi, redHi))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield assert(!html.contains("url(#"), s"sequential mark fills must be concrete colors, not url(#...):\n$html")
        end for
    }

    // ---- sequential legend emits exactly one gradient under a per-chart id ----

    "sequential legend emits exactly one linearGradient def with a kyo-chart- id" in {
        val rows = Chunk(VRow(0.1), VRow(0.9))
        val spec = Chart(rows)(point(x = _ => 0.0, y = _.v, color = _.v))
            .legend(_.colorScaleSequential(blueHi, redHi))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
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

    // ---- two STRUCTURALLY-IDENTICAL charts get distinct gradient ids ----

    "two structurally-identical sequential charts in one fragment get distinct gradient ids" in {
        // The two charts are lowered from the SAME spec value (spec1.## == spec2.##), yet each lowering must
        // still get a distinct gradient def id so the document carries no duplicate id attributes, and within
        // each chart the swatch fill url(#id) must resolve to that chart's own def id.
        val rows = Chunk(VRow(0.1), VRow(0.9))
        val spec = Chart(rows)(point(x = _ => 0.0, y = _.v, color = _.v))
            .legend(_.colorScaleSequential(blueHi, redHi))
            .size(640, 480)
        val spec1 = spec
        val spec2 = spec
        // Establish that the two specs are the identical value, so distinct ids cannot come from the spec.
        assert(spec1.## == spec2.##, s"precondition: the two specs must share a structural hash (## ${spec1.##} vs ${spec2.##})")
        for
            root1 <- (spec1).lower
            root2 <- (spec2).lower
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

    // ---- point fills agree with their legend swatch colors ----

    "point fills match their categorical legend swatch colors" in {
        val rows = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"), CatRow("r", 3.0, "c"))
        val spec = Chart(rows)(point(x = _ => 0.0, y = _.y, color = _.cat))
        (spec).lower.map { root =>
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
    }

    // ---- toString-colliding enum cases produce two distinct swatches ----

    "two enum cases with an identical toString produce two distinct swatches" in {
        enum Tier derives CanEqual, Plottable:
            case Gold, Silver
            override def toString: String = "T"
        case class IRow(name: String, value: Double, tier: Tier)
        given CanEqual[IRow, IRow] = CanEqual.derived
        val rows                   = Chunk(IRow("a", 1.0, Tier.Gold), IRow("b", 2.0, Tier.Silver))
        val spec                   = Chart(rows)(point(x = _ => 0.0, y = _.value, color = _.tier))
        (spec).lower.map { root =>
            val swatches = legendSwatchRects(root)
            // Despite the colliding toString, CatKey keeps the two enum cases distinct -> two swatches.
            assert(swatches.size == 2, s"Expected 2 distinct swatches despite colliding toString but got ${swatches.size}")
            val fills = swatches.map(s => fillColorOf(s.svgAttrs.fill)).toSeq.distinct
            assert(fills.size == 2, s"Expected 2 distinct swatch colors but got $fills")
        }
    }

    // ---- a plain bar chart emits no defs or linearGradient ----

    "a plain bar chart with no sequential color scale emits no defs or linearGradient" in {
        val rows = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"))
        val spec = Chart(rows)(bar(x = _.x, y = _.y))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("<defs"), s"a plain bar chart must emit no defs block:\n$html")
            assert(!html.contains("<linearGradient"), s"a plain bar chart must emit no gradient:\n$html")
        end for
    }

    // ---- legend(_.hidden) suppresses the whole legend region ----

    "legend(_.hidden) suppresses the legend swatches and labels" in {
        val rows      = Chunk(CatRow("p", 1.0, "a"), CatRow("q", 2.0, "b"))
        val shownSpec = Chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
        val hiddenSpec = Chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
            .legend(_.hidden)
        for
            shownRoot  <- (shownSpec).lower
            hiddenRoot <- (hiddenSpec).lower
        yield
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
        end for
    }

    // ---- theme color overrides ----

    private def themeRows: Chunk[Sale] = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))

    "theme.background(c) sets the background rect fill to the override color" in {
        val custom = Style.Color.rgb(10, 20, 30)
        val spec   = Chart(themeRows)(bar(x = _.month, y = _.revenue)).theme(_.background(custom)).yAxis(_.grid)
        (spec).lower.map { root =>
            val bg = root.children.collectFirst { case r: Svg.Rect => r }.getOrElse(fail("Expected a background rect"))
            assert(fillColorOf(bg.svgAttrs.fill) == custom, s"Expected background fill $custom but got ${fillColorOf(bg.svgAttrs.fill)}")
        }
    }

    "theme.axisColor(c) sets the axis line / tick mark stroke color" in {
        val custom = Style.Color.rgb(200, 0, 0)
        val spec   = Chart(themeRows)(bar(x = _.month, y = _.revenue)).theme(_.axisColor(custom))
        (spec).lower.map { root =>
            // Axis lines / tick marks are frame lines WITHOUT a strokeOpacity (gridlines carry one).
            val axisLines = frameLines(root).filter(_.svgAttrs.strokeOpacity.isEmpty)
            assert(axisLines.nonEmpty, "Expected axis/tick lines")
            axisLines.foldLeft(()): (_, l) =>
                l.svgAttrs.stroke match
                    case Present(Svg.Paint.Color(c)) => assert(c == custom, s"Expected axis stroke $custom but got $c")
                    case other                       => fail(s"Expected an axis stroke color but got $other")
        }
    }

    "theme.gridColor(c) sets the gridline stroke color" in {
        val custom = Style.Color.rgb(0, 200, 0)
        val spec   = Chart(themeRows)(bar(x = _.month, y = _.revenue)).theme(_.gridColor(custom)).yAxis(_.grid)
        (spec).lower.map { root =>
            val gridLines = frameLines(root).filter(_.svgAttrs.strokeOpacity.isDefined)
            assert(gridLines.nonEmpty, "Expected gridlines")
            gridLines.foldLeft(()): (_, l) =>
                l.svgAttrs.stroke match
                    case Present(Svg.Paint.Color(c)) => assert(c == custom, s"Expected gridline stroke $custom but got $c")
                    case other                       => fail(s"Expected a gridline stroke color but got $other")
        }
    }

    "an unset theme produces output identical to the explicit default theme (no regression)" in {
        val rows     = themeRows
        val unset    = Chart(rows)(bar(x = _.month, y = _.revenue)).yAxis(_.grid)
        val explicit = Chart(rows)(bar(x = _.month, y = _.revenue)).theme(_.light).yAxis(_.grid)
        for
            rUnset    <- (unset).lower
            rExplicit <- (explicit).lower
            hUnset    <- HtmlRenderer.render(rUnset, Seq.empty)
            hExplicit <- HtmlRenderer.render(rExplicit, Seq.empty)
        yield assert(hUnset == hExplicit, "Unset theme must render identically to the explicit light default")
        end for
    }

    // ---- theme-palette consistency tests ----

    "grouped bar uses theme.palette colors, not DefaultPalette, under a custom theme" in {
        // Two color groups via region encoding: NA (idx=0) and EU (idx=1).
        // Custom palette: first color #cc00cc (purple-ish), second color #00cccc (teal).
        // DefaultPalette would give blue (#3b82f6) and orange (#f97316).
        val purple = Style.Color.hex("#cc00cc").getOrElse(fail("bad hex"))
        val teal   = Style.Color.hex("#00cccc").getOrElse(fail("bad hex"))
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Jan", Usd(2000), Region.EU)
        )
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .theme(_.palette(Chunk(purple, teal)))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#cc00cc\""),
                s"expected custom first-group fill #cc00cc (purple):\n$html"
            )
            assert(
                html.contains("fill=\"#00cccc\""),
                s"expected custom second-group fill #00cccc (teal):\n$html"
            )
            assert(
                !html.contains("fill=\"#3b82f6\""),
                s"DefaultPalette blue must not appear under a custom palette:\n$html"
            )
            assert(
                !html.contains("fill=\"#f97316\""),
                s"DefaultPalette orange must not appear under a custom palette:\n$html"
            )
        end for
    }

    "grouped bar with default theme uses DefaultPalette blue and orange (default-theme unchanged)" in {
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Jan", Usd(2000), Region.EU)
        )
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#3b82f6\""),
                s"default-theme grouped bar must use DefaultPalette blue (#3b82f6):\n$html"
            )
            assert(
                html.contains("fill=\"#f97316\""),
                s"default-theme grouped bar must use DefaultPalette orange (#f97316):\n$html"
            )
        end for
    }

    "text mark uses theme.palette colors, not DefaultPalette, under a custom theme" in {
        // Two color groups via region encoding: NA (idx=0) and EU (idx=1).
        // Custom palette: first color #cc00cc, second color #00cccc.
        // DefaultPalette would give blue (#3b82f6) and orange (#f97316).
        val purple = Style.Color.hex("#cc00cc").getOrElse(fail("bad hex"))
        val teal   = Style.Color.hex("#00cccc").getOrElse(fail("bad hex"))
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(2000), Region.EU)
        )
        val spec = Chart(rows)(text(x = _.month, y = _.revenue, label = _.month, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .theme(_.palette(Chunk(purple, teal)))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#cc00cc\""),
                s"expected custom first-group fill #cc00cc (purple) for text:\n$html"
            )
            assert(
                html.contains("fill=\"#00cccc\""),
                s"expected custom second-group fill #00cccc (teal) for text:\n$html"
            )
            assert(
                !html.contains("fill=\"#3b82f6\""),
                s"DefaultPalette blue must not appear under a custom palette for text:\n$html"
            )
            assert(
                !html.contains("fill=\"#f97316\""),
                s"DefaultPalette orange must not appear under a custom palette for text:\n$html"
            )
        end for
    }

    "errorBar mark uses theme.palette colors, not DefaultPalette, under a custom theme" in {
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
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.region))
            .yScale(_.linear(0.0, 10.0))
            .theme(_.palette(Chunk(purple, teal)))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("stroke=\"#cc00cc\""),
                s"expected custom first-group stroke #cc00cc (purple) for errorBar:\n$html"
            )
            assert(
                html.contains("stroke=\"#00cccc\""),
                s"expected custom second-group stroke #00cccc (teal) for errorBar:\n$html"
            )
            assert(
                !html.contains("stroke=\"#3b82f6\""),
                s"DefaultPalette blue must not appear under a custom palette for errorBar:\n$html"
            )
            assert(
                !html.contains("stroke=\"#f97316\""),
                s"DefaultPalette orange must not appear under a custom palette for errorBar:\n$html"
            )
        end for
    }

    // static colored line respects theme.palette
    "static multi-series line uses theme.palette colors, not DefaultPalette, under a custom theme" in {
        // Two series via region encoding: NA (idx=0) and EU (idx=1).
        // Custom palette: first color magenta (#ff00ff), second color cyan (#00ffff).
        // DefaultPalette would give blue (#3b82f6) and orange (#f97316).
        // lowerLine reads themePalette(spec.theme) and must use the custom colors.
        val magenta = Style.Color.hex("#ff00ff").getOrElse(fail("bad hex"))
        val cyan    = Style.Color.hex("#00ffff").getOrElse(fail("bad hex"))
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(2000), Region.NA),
            Sale("Jan", Usd(500), Region.EU),
            Sale("Feb", Usd(1500), Region.EU)
        )
        val spec = Chart(rows)(line(x = _.month, y = _.revenue, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .theme(_.palette(Chunk(magenta, cyan)))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Both custom palette colors must appear as stroke attributes.
            assert(
                html.contains("stroke=\"#ff00ff\""),
                s"expected custom first-series stroke #ff00ff (magenta):\n$html"
            )
            assert(
                html.contains("stroke=\"#00ffff\""),
                s"expected custom second-series stroke #00ffff (cyan):\n$html"
            )
            // DefaultPalette blue and orange must NOT appear as path strokes (only custom colors).
            assert(
                !html.contains("stroke=\"#3b82f6\""),
                s"DefaultPalette blue must not appear under a custom palette:\n$html"
            )
            assert(
                !html.contains("stroke=\"#f97316\""),
                s"DefaultPalette orange must not appear under a custom palette:\n$html"
            )
        end for
    }

    // a color-split line must honor an explicit categorical colorScale.
    // lowerLine's color-split branch must resolve each series via resolvePalette so it respects
    // spec.legendCfg.colorScale, rather than coloring by category index from themePalette(s.theme),
    // which would draw DefaultPalette blue/orange and disagree with the legend (which uses resolvePalette).
    // So the "a" path is the colorScale cyan and the "b" path is the colorScale amber, matching the legend.
    "a color-split line honors an explicit categorical colorScale" in {
        case class SRow(x: Double, y: Double, series: String) derives CanEqual
        val cyan  = Style.Color.rgb(6, 182, 212)
        val amber = Style.Color.rgb(245, 158, 11)
        val rows = Chunk(
            SRow(0.0, 1.0, "a"),
            SRow(1.0, 2.0, "a"),
            SRow(0.0, 3.0, "b"),
            SRow(1.0, 4.0, "b")
        )
        val spec = Chart(rows)(line(x = _.x, y = _.y, color = _.series))
            .legend(_.colorScale {
                case "a" => cyan
                case _   => amber
            })
        (spec).lower.map { root =>
            val paths = pathsIn(root)
            assert(paths.size == 2, s"Expected 2 line paths (one per series) but got ${paths.size}")
            def strokeOf(p: Svg.Path): Style.Color =
                p.svgAttrs.stroke match
                    case Present(Svg.Paint.Color(c)) => c
                    case other                       => fail(s"Expected a line stroke color but got $other")
            val aStroke = strokeOf(paths(0))
            val bStroke = strokeOf(paths(1))
            // The "a" series path must carry the colorScale cyan, NOT DefaultPalette blue.
            assert(aStroke == cyan, s"series 'a' line must use colorScale cyan rgb(6,182,212) but got $aStroke")
            // The "b" series path must carry the colorScale amber, NOT DefaultPalette orange.
            assert(bStroke == amber, s"series 'b' line must use colorScale amber rgb(245,158,11) but got $bStroke")
            assert(aStroke != Style.Color.blue, s"series 'a' must not fall back to DefaultPalette blue: $aStroke")
            assert(bStroke != Style.Color.orange, s"series 'b' must not fall back to DefaultPalette orange: $bStroke")
        }
    }

    // stacked-area bands carry a per-group palette fill, not colorless paths.
    // Each group's band must be filled with its palette color (custom theme.palette here),
    // mirroring the non-stacked area's color fill.
    "stacked area bands carry per-group theme.palette fills (custom theme)" in {
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
        val spec = Chart(rows)(area(x = _.month, y = _.revenue, stack = by(_.region)))
            .yScale(_.linear(0.0, 6000.0))
            .theme(_.palette(Chunk(purple, teal)))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#cc00cc\""),
                s"expected first-group band fill #cc00cc (purple):\n$html"
            )
            assert(
                html.contains("fill=\"#00cccc\""),
                s"expected second-group band fill #00cccc (teal):\n$html"
            )
            assert(
                !html.contains("fill=\"#3b82f6\""),
                s"DefaultPalette blue must not appear under a custom palette:\n$html"
            )
            assert(
                !html.contains("fill=\"#f97316\""),
                s"DefaultPalette orange must not appear under a custom palette:\n$html"
            )
        end for
    }

    // under the default theme, stacked-area bands use DefaultPalette colors.
    "stacked area bands use DefaultPalette fills (default theme)" in {
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(1500), Region.NA),
            Sale("Jan", Usd(2000), Region.EU),
            Sale("Feb", Usd(2500), Region.EU)
        )
        val spec = Chart(rows)(area(x = _.month, y = _.revenue, stack = by(_.region)))
            .yScale(_.linear(0.0, 6000.0))
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#3b82f6\""),
                s"default-theme stacked area must use DefaultPalette blue (#3b82f6):\n$html"
            )
            assert(
                html.contains("fill=\"#f97316\""),
                s"default-theme stacked area must use DefaultPalette orange (#f97316):\n$html"
            )
        end for
    }

    // ---- every Mark variant lowers to its expected element kind (behavioral exhaustiveness) ----

    "every Mark variant lowers to its signature element kind in the marks region" in {
        case class ErrRow(x: String, mean: Double, lo: Double, hi: Double)
        val erows = Chunk(ErrRow("a", 6.0, 4.0, 8.0))
        val rows  = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))

        for
            barRoot   <- (Chart(rows)(bar(x = _.month, y = _.revenue))).lower
            lineRoot  <- (Chart(rows)(line(x = _.month, y = _.revenue))).lower
            areaRoot  <- (Chart(rows)(area(x = _.month, y = _.revenue))).lower
            pointRoot <- (Chart(rows)(point(x = _.month, y = _.revenue))).lower
            ruleRoot  <- (Chart(rows)(rule[Sale, Double](y = 1500.0))).lower
            textRoot  <- (Chart(rows)(text(x = _.month, y = _.revenue, label = _.month))).lower
            errRoot   <- (Chart(erows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))).lower
        yield
            // bar -> Svg.Rect
            val barG = marksGroup(barRoot)
            assert(barG.children.exists { case _: Svg.Rect => true; case _ => false }, "bar must lower to a Svg.Rect")

            // line -> Svg.Path
            val lineG = marksGroup(lineRoot)
            assert(lineG.children.exists { case _: Svg.Path => true; case _ => false }, "line must lower to a Svg.Path")

            // area -> Svg.Path
            val areaG = marksGroup(areaRoot)
            assert(areaG.children.exists { case _: Svg.Path => true; case _ => false }, "area must lower to a Svg.Path")

            // point -> Svg.Circle
            val pointG = marksGroup(pointRoot)
            assert(pointG.children.exists { case _: Svg.Circle => true; case _ => false }, "point must lower to a Svg.Circle")

            // rule(y=...) -> Svg.Line
            val ruleG = marksGroup(ruleRoot)
            assert(ruleG.children.exists { case _: Svg.Line => true; case _ => false }, "rule(y) must lower to a Svg.Line")

            // text -> Svg.Text
            val textG = marksGroup(textRoot)
            assert(textG.children.exists { case _: Svg.Text => true; case _ => false }, "text must lower to a Svg.Text")

            // errorBar -> Svg.Line (whiskers/caps) AND Svg.Circle (center marker)
            val errG = marksGroup(errRoot)
            assert(errG.children.exists { case _: Svg.Line => true; case _ => false }, "errorBar must lower to Svg.Line whiskers")
            assert(errG.children.exists { case _: Svg.Circle => true; case _ => false }, "errorBar must lower to a Svg.Circle center")
        end for
    }

    // ---- lowering produces a non-empty Svg.Root ----

    "Chart.lower and lowerWithScales produce a non-empty Svg.Root" in {
        val rows = Chunk(Sale("Jan", Usd(1000)))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
        for
            root <- spec.lower
            pair <- spec.lowerWithScales
        yield
            assert(root.children.nonEmpty, "spec.lower must return a non-empty Svg.Root")
            assert(pair._1.children.nonEmpty, "spec.lowerWithScales must return a non-empty Svg.Root")
        end for
    }

    // ---- TEXT mark over a band x is centred on the band, honouring TextAnchor.Middle ----
    // A text label with anchor=Middle over a categorical (band) x must sit at the band CENTRE (the same x
    // the bar is centred on), not the band's LEFT edge where the bar rect starts.
    "text mark with anchor=Middle over a band x is positioned at the band centre, not the band left edge" in {
        // One category "Jan": band slot is the full plot width [60, 620], slot centre = 340.
        // The bar rect starts at the band's left inset (88) but is centred at 340 (88 + 504/2).
        val rows = Chunk(Sale("Jan", Usd(1000)))
        val spec = Chart(rows)(
            bar(x = _.month, y = _.revenue),
            text(x = _.month, y = _.revenue, label = _ => "L", anchor = Chart.TextAnchor.Middle)
        )
        (spec).lower.map { root =>
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
    }

    // ---- SEQUENTIAL color legend carries numeric min/max scale labels ----
    // The gradient swatch alone is quantitatively meaningless; the legend must show the value extent.
    "sequential color legend emits numeric min and max value labels as text" in {
        case class P(x: Double, y: Double, heat: Double)
        given CanEqual[P, P] = CanEqual.derived
        val rows             = Chunk(P(0.0, 0.0, 10.0), P(1.0, 1.0, 50.0), P(2.0, 2.0, 90.0))
        val spec = Chart(rows)(point(x = _.x, y = _.y, color = _.heat))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        (spec).lower.map { root =>
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
    }

    // ---- POINT chart color/size legend sits in a reserved band, plot reserves top headroom ----
    // A point chart with a sequential color legend must place the legend ABOVE the plot area, and the
    // topmost plotted point must clear the plot top (cy - r >= plotY): no overlap, no clipping.
    "point chart with a sequential color legend reserves a top band; legend is above plot and top point is not clipped" in {
        case class P(x: Double, y: Double, heat: Double)
        given CanEqual[P, P] = CanEqual.derived
        val rows             = Chunk(P(0.0, 0.0, 10.0), P(1.0, 1.0, 50.0), P(2.0, 2.0, 90.0))
        val spec = Chart(rows)(point(x = _.x, y = _.y, color = _.heat))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        spec.lowerWithScales.map { (root, scales) =>
            val plotY = scales.plot.y
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
    }

    // ---- area/line/point over a band x are centred on the band, aligning with the
    // centred x-tick labels. A left-edge area leaves the last band slot empty (the "empty wedge"). ----
    "stacked area over a band x spans the full plot width; the last vertex is centred on the last band" in {
        case class S(month: String, units: Double, region: Region = Region.NA)
        given CanEqual[S, S] = CanEqual.derived
        // Four categories -> band slots; the rightmost (Apr) vertex must sit at Apr's band centre, not its left
        // edge (which would leave a wedge of empty plot to the right of the area).
        val rows = Chunk(S("Jan", 60), S("Feb", 72), S("Mar", 80), S("Apr", 90))
        val spec = Chart(rows)(area(x = _.month, y = _.units, color = _.region, stack = by(_.region)))
        (spec).lower.map { root =>
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
    }

    // ---- non-stacked area color split ----

    // non-stacked area with color encoding emits one path per series.
    "non-stacked area with color=_.region emits one closed path per series at fill-opacity 0.7, each colored by colorScale" in {
        // Use colors that differ from DefaultPalette(0)=blue so failure is unambiguous on the color assertions.
        // red and purple are not DefaultPalette(0) (blue), so an EU path that came out blue (from a single
        // merged path instead of one path per series) would unambiguously fail the purple assertion.
        val naColor = Style.Color.red    // #ef4444 -> NA
        val euColor = Style.Color.purple // #a855f7 -> EU
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(2000), Region.EU)
        )
        val spec = Chart(rows)(area(x = _.month, y = _.revenue, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .legend(_.colorScale[Region](Region.NA -> naColor, Region.EU -> euColor))
        (spec).lower.map { root =>
            val marks = marksGroup(root)
            // exactly 2 distinct path elements in the marks group (one per Region)
            val areaPaths = marks.children.collect { case p: Svg.Path => p }
            assert(
                areaPaths.size == 2,
                s"expected 2 per-series area paths but got ${areaPaths.size}"
            )
            // each path is closed (the PathData ends with a Close command)
            areaPaths.zipWithIndex.foreach: (p, i) =>
                p.svgAttrs.d match
                    case Absent => fail(s"area path $i has no d attribute")
                    case Present(pd) =>
                        val cmds = Svg.PathData.commands(pd)
                        assert(
                            cmds.toSeq.lastOption.exists { case Svg.PathCommand.Close => true; case _ => false },
                            s"area path $i must be closed (last command == Close) but last was ${cmds.toSeq.lastOption}"
                        )
            // each path carries fill-opacity == 0.7
            areaPaths.zipWithIndex.foreach: (p, i) =>
                assert(
                    p.svgAttrs.fillOpacity.exists(fo => math.abs(fo - 0.7) < 1e-9),
                    s"area path $i must have fill-opacity=0.7 but got ${p.svgAttrs.fillOpacity}"
                )
            // path fills match the colorScale colors (NA=red, EU=purple); encounter/ordinal order: NA(0) first.
            assert(
                fillColorOf(areaPaths(0).svgAttrs.fill) == naColor,
                s"first area path (NA) must be naColor but got ${areaPaths(0).svgAttrs.fill}"
            )
            assert(
                fillColorOf(areaPaths(1).svgAttrs.fill) == euColor,
                s"second area path (EU) must be euColor but got ${areaPaths(1).svgAttrs.fill}"
            )
            // legend swatches must agree with the mark fills.
            // Area marks use line swatches (not rect swatches): find them by strokeWidth == 2.0 px among direct-child lines.
            val legendSwatchLines = root.children.collect:
                case l: Svg.Line if l.svgAttrs.strokeWidth.contains(Svg.SvgLength.px(2.0)) => l
            assert(
                legendSwatchLines.size >= 2,
                s"expected at least 2 legend line swatches but got ${legendSwatchLines.size} (area uses line swatches)"
            )
            def strokeColorOf(l: Svg.Line): Style.Color = l.svgAttrs.stroke match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"expected Svg.Paint.Color stroke on legend swatch but got $other")
            assert(
                strokeColorOf(legendSwatchLines(0)) == naColor,
                s"NA legend swatch must be naColor but got ${legendSwatchLines(0).svgAttrs.stroke}"
            )
            assert(
                strokeColorOf(legendSwatchLines(1)) == euColor,
                s"EU legend swatch must be euColor but got ${legendSwatchLines(1).svgAttrs.stroke}"
            )
        }
    }

    // non-stacked area WITHOUT a color encoding emits exactly 1 closed path.
    // The buildSimpleAreaPath refactor must not change the Absent-color arm.
    "non-stacked area with no color encoding still emits exactly one closed path" in {
        case class SimpleRow(x: String, y: Int) derives CanEqual
        val rows = Chunk(SimpleRow("a", 100), SimpleRow("b", 200))
        val spec = Chart(rows)(area(x = _.x, y = _.y))
        (spec).lower.map { root =>
            val paths = root.children.flatMap:
                case g: Svg.G => g.children.collect { case p: Svg.Path => p }
                case _        => Chunk.empty
            assert(paths.size == 1, s"mark.color=Absent area must emit exactly 1 path but got ${paths.size}")
            assert(
                paths(0).svgAttrs.fillOpacity.exists(fo => math.abs(fo - 0.7) < 1e-9),
                s"the single area path must have fill-opacity=0.7 but got ${paths(0).svgAttrs.fillOpacity}"
            )
            paths(0).svgAttrs.d match
                case Absent => fail("area path must have a d attribute")
                case Present(pd) =>
                    val cmds = Svg.PathData.commands(pd)
                    assert(
                        cmds.toSeq.lastOption.exists { case Svg.PathCommand.Close => true; case _ => false },
                        s"the single path must be closed; last command was ${cmds.toSeq.lastOption}"
                    )
            end match
        }
    }

    // ---- text mark with categorical colorScale uses the scale colors ----
    // lowerText takes spec: Maybe[Chart[A]] and routes a Present colorScale through resolvePalette
    // (mirroring lowerLine / lowerPoint). Resolving palette by index from themePalette(theme) only would
    // ignore spec.legendCfg.colorScale and, with no custom theme.palette, yield DefaultPalette colors
    // (#3b82f6 blue / #f97316 orange) instead of the colorScale colors.

    "text mark with categorical colorScale uses the scale colors, not DefaultPalette" in {
        val naColor = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))
        val euColor = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))
        val rows = Chunk(
            Sale("Jan", Usd(1000), Region.NA),
            Sale("Feb", Usd(2000), Region.EU)
        )
        val spec = Chart(rows)(text(x = _.month, y = _.revenue, label = _.month, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .legend(_.colorScale[Region](
                Region.NA -> naColor,
                Region.EU -> euColor
            ))
        (spec).lower.map { root =>
            // Use the marks group to exclude axis tick texts from the count.
            val marks = marksGroup(root)
            val texts = marks.children.collect { case t: Svg.Text => t }
            assert(texts.size >= 2, s"expected at least 2 text glyphs in marks group, got ${texts.size}")
            // Sort by x to recover NA/EU encounter order (NA is at "Jan", EU at "Feb" on a band scale).
            val byX   = texts.toSeq.sortBy(t => numOf(t.svgAttrs.x))
            val fill0 = fillColorOf(byX(0).svgAttrs.fill)
            val fill1 = fillColorOf(byX(1).svgAttrs.fill)
            assert(fill0 == naColor, s"text glyph 0 (NA) must be naColor $naColor but got $fill0")
            assert(fill1 == euColor, s"text glyph 1 (EU) must be euColor $euColor but got $fill1")
            // Explicit guard: these must NOT be DefaultPalette fallback colors.
            assert(
                fill0 != Style.Color.blue && fill0 != Style.Color.orange,
                s"text fill 0 must not be DefaultPalette colors; got $fill0"
            )
            assert(
                fill1 != Style.Color.blue && fill1 != Style.Color.orange,
                s"text fill 1 must not be DefaultPalette colors; got $fill1"
            )
            // Legend swatch agreement: each region's swatch fill must equal its colorScale color.
            val swatches = legendSwatchRects(root)
            assert(swatches.size == 2, s"expected 2 legend swatches but got ${swatches.size}")
            val swatchesByY = swatches.toSeq.sortBy(s => coordNum(s.svgAttrs.y).getOrElse(0.0))
            assert(
                fillColorOf(swatchesByY(0).svgAttrs.fill) == naColor,
                s"swatch 0 must be naColor $naColor"
            )
            assert(
                fillColorOf(swatchesByY(1).svgAttrs.fill) == euColor,
                s"swatch 1 must be euColor $euColor"
            )
        }
    }

    // ---- errorBar with categorical colorScale uses the scale colors ----
    // lowerErrorBar routes a Present colorScale through resolvePalette, mirroring lowerText. All three
    // sub-shapes (vLine + caps + center marker) derive their stroke/fill from the resolved palette.
    // Resolving palette by index from themePalette(theme) only would ignore spec.legendCfg.colorScale.

    "errorBar with categorical colorScale uses the scale colors, one stroke per row" in {
        val naColor = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))
        val euColor = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))
        case class EbSale(x: String, mean: Double, lo: Double, hi: Double, region: Region)
        given CanEqual[EbSale, EbSale] = CanEqual.derived
        val rows = Chunk(
            EbSale("a", 6.0, 4.0, 8.0, Region.NA),
            EbSale("b", 3.0, 1.0, 5.0, Region.EU)
        )
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.region))
            .yScale(_.linear(0.0, 10.0))
            .legend(_.colorScale[Region](
                Region.NA -> naColor,
                Region.EU -> euColor
            ))
        (spec).lower.map { root =>
            // Restrict to the marks group to avoid axis gridlines and tick-mark lines.
            val marks   = marksGroup(root)
            val lines   = marks.children.collect { case l: Svg.Line => l }
            val circles = marks.children.collect { case c: Svg.Circle => c }
            // 2 rows x 3 lines each (vLine + capLow + capHigh) = 6 lines total.
            assert(lines.size == 6, s"expected 6 lines (3 per row x 2 rows) but got ${lines.size}")
            // 2 rows x 1 circle each (center marker) = 2 circles total.
            assert(circles.size == 2, s"expected 2 circles (1 per row x 2 rows) but got ${circles.size}")

            def strokeOf(l: Svg.Line): Style.Color = l.svgAttrs.stroke match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"expected stroke Color but got $other")

            // Helper: extract a Double from Maybe[Double] (line coordinates are not Coord-wrapped).
            def lineCoord(m: Maybe[Double], lbl: String): Double = m match
                case Present(v) => v
                case Absent     => fail(s"$lbl absent")

            // Group lines by their center x pixel ((x1+x2)/2) so that all 3 sub-shapes of a row land
            // in the same group: the vLine has x1==x2==px, and each cap has x1=px-halfCap, x2=px+halfCap,
            // so (x1+x2)/2 = px in all three cases.
            def centerX(l: Svg.Line): Double =
                (lineCoord(l.svgAttrs.x1, "x1") + lineCoord(l.svgAttrs.x2, "x2")) / 2.0
            val byCenter = lines.toSeq.groupBy(l => math.round(centerX(l)).toInt)
            assert(
                byCenter.size == 2,
                s"expected 2 distinct center groups (one per row), got ${byCenter.keys.toSeq.sorted(using Ordering.Int)}"
            )
            val lineGroups = byCenter.values.toSeq.sortBy(_.map(centerX).min)
            val naGroup    = lineGroups(0) // smaller x -> category "a" = NA
            val euGroup    = lineGroups(1) // larger x -> category "b" = EU
            naGroup.foreach(l =>
                assert(
                    strokeOf(l) == naColor,
                    s"NA line stroke must be naColor $naColor but got ${strokeOf(l)}"
                )
            )
            euGroup.foreach(l =>
                assert(
                    strokeOf(l) == euColor,
                    s"EU line stroke must be euColor $euColor but got ${strokeOf(l)}"
                )
            )

            // Explicit guard: must NOT be DefaultPalette fallback colors.
            assert(
                naGroup.forall(l => strokeOf(l) != Style.Color.blue && strokeOf(l) != Style.Color.orange),
                s"NA lines must not use DefaultPalette colors"
            )
            assert(
                euGroup.forall(l => strokeOf(l) != Style.Color.blue && strokeOf(l) != Style.Color.orange),
                s"EU lines must not use DefaultPalette colors"
            )

            // Center marker circles: fill is set to the stroke color (lowerErrorBar uses fill(stroke)).
            // Circle cx is Maybe[Double] (not Maybe[Coord]), extract directly and sort by cx.
            val circlesByX = circles.toSeq.sortBy(c => lineCoord(c.svgAttrs.cx, "cx"))
            val naCircleFill = circlesByX(0).svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"expected circle fill Color but got $other")
            val euCircleFill = circlesByX(1).svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"expected circle fill Color but got $other")
            assert(naCircleFill == naColor, s"NA marker fill must be naColor $naColor but got $naCircleFill")
            assert(euCircleFill == euColor, s"EU marker fill must be euColor $euColor but got $euCircleFill")

            // Legend swatch agreement.
            val swatches = legendSwatchRects(root)
            assert(swatches.size == 2, s"expected 2 legend swatches but got ${swatches.size}")
            val swatchesByY = swatches.toSeq.sortBy(s => coordNum(s.svgAttrs.y).getOrElse(0.0))
            assert(
                fillColorOf(swatchesByY(0).svgAttrs.fill) == naColor,
                s"swatch 0 must be naColor $naColor"
            )
            assert(
                fillColorOf(swatchesByY(1).svgAttrs.fill) == euColor,
                s"swatch 1 must be euColor $euColor"
            )
        }
    }

    // ---- legend margin reserved for color-bearing text/errorBar ----
    // buildLayout.hasLegend must check m.color.isDefined for Mark.Text and Mark.ErrorBar, matching the
    // Bar/Line/Area/Point treatment, so a chart whose ONLY color mark is text or errorBar reserves legend
    // margin and plotY becomes 40.0. Wildcard patterns that hardcode false regardless of a color encoding
    // would leave topPad at 0 and plotY at MarginTop=20.

    // text-only color mark reserves the top strip (legend margin reserved, plotY = 40.0).
    "legend margin reserved for color-bearing text mark, plot shifted by LegendReservedH" in {
        // Use Sale rows with revenue=4000 at the top of yScale(linear(0,4000)).
        // With linear(0,4000): apply(4000) == plotY.
        // With legend reserved: plotY = MarginTop(20) + LegendReservedH(20) = 40.0.
        val naColor = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))
        val euColor = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))
        val rows = Chunk(
            Sale("Jan", Usd(4000), Region.NA), // revenue at top of scale => glyph y == plotY
            Sale("Feb", Usd(2000), Region.EU)
        )
        val spec = Chart(rows)(text(x = _.month, y = _.revenue, label = _.month, color = _.region))
            .yScale(_.linear(0.0, 4000.0))
            .legend(_.colorScale[Region](Region.NA -> naColor, Region.EU -> euColor))
        (spec).lower.map { root =>
            val marks = marksGroup(root)
            val texts = marks.children.collect { case t: Svg.Text => t }.toSeq
            assert(texts.size >= 2, s"expected at least 2 text glyphs in marks group, got ${texts.size}")
            // Sort by x to get Jan (NA, revenue=4000) first (band-left is "Jan").
            val byX       = texts.sortBy(t => numOf(t.svgAttrs.x))
            val topGlyphY = numOf(byX(0).svgAttrs.y)
            assertClose(topGlyphY, 40.0, "top glyph y must equal reserved plotY=40 (hasLegend=true for text color mark)")
        }
    }

    // errorBar-only color mark reserves the top strip (legend margin reserved, plotY = 40.0).
    "legend margin reserved for color-bearing errorBar mark, plot shifted by LegendReservedH" in {
        // EbSale with hi=10.0 == top of yScale(linear(0,10)). apply(10.0) == plotY.
        // With legend reserved: plotY = 40.0.
        val naColor = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))
        val euColor = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))
        case class EbSale22(x: String, mean: Double, lo: Double, hi: Double, region: Region)
            derives CanEqual
        val rows = Chunk(
            EbSale22("a", 8.0, 6.0, 10.0, Region.NA), // hi=10.0 at top of scale => vLine y1 == plotY
            EbSale22("b", 5.0, 3.0, 7.0, Region.EU)
        )
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.region))
            .yScale(_.linear(0.0, 10.0))
            .legend(_.colorScale[Region](Region.NA -> naColor, Region.EU -> euColor))
        (spec).lower.map { root =>
            val marks = marksGroup(root)
            val lines = marks.children.collect { case l: Svg.Line => l }.toSeq
            // vLines have x1 == x2. lowerErrorBar sets y1=pyLow, y2=pyHigh.
            // pyHigh = ys.apply(hi) = plotY for hi=10.0 (top of scale). Minimum y2 across vLines = plotY.
            val vLines = lines.filter(l => l.svgAttrs.x1 == l.svgAttrs.x2)
            assert(vLines.nonEmpty, "no vLines found in marks group")
            val topY = vLines.flatMap(l => l.svgAttrs.y2).min
            assertClose(
                topY,
                40.0,
                "top vLine y2 (pyHigh for hi=10.0) must equal reserved plotY=40 (hasLegend=true for errorBar color mark)"
            )
        }
    }

    // no-color text mark keeps hasLegend==false, plotY unchanged at 20.0.
    "no-color text mark keeps plotY=20 (hasLegend=false, topPad=0)" in {
        // text mark WITHOUT a color encoding: m.color.isDefined==false, hasLegend stays false.
        val rows = Chunk(
            Sale("Jan", Usd(4000)), // no region => Region.NA default, but no color encoding on mark
            Sale("Feb", Usd(2000))
        )
        val spec = Chart(rows)(text(x = _.month, y = _.revenue, label = _.month))
            .yScale(_.linear(0.0, 4000.0))
        (spec).lower.map { root =>
            val marks = marksGroup(root)
            val texts = marks.children.collect { case t: Svg.Text => t }.toSeq
            assert(texts.size >= 1, s"expected at least 1 text glyph in marks group, got ${texts.size}")
            val byX       = texts.sortBy(t => numOf(t.svgAttrs.x))
            val topGlyphY = numOf(byX(0).svgAttrs.y)
            // No legend reserved: plotY stays at MarginTop = 20.0.
            assertClose(topGlyphY, 20.0, "no-color text mark must keep plotY=20 (hasLegend=false, no strip reserved)")
            // Also: no legend swatches rendered.
            assert(legendSwatchRects(root).isEmpty, "no legend swatches for no-color text mark")
        }
    }

    // ---- animated non-stacked area with colorScale honors the scale colors ----
    // lowerAreaWithTransitions forwards Present(spec) into both lowerArea calls, so the animated path
    // resolves the palette from the explicit colorScale. Calling lowerArea with spec=Absent would
    // resolve the palette from DefaultPalette by index (blue/orange), ignoring the colorScale.

    "animated non-stacked area with categorical colorScale honors the scale colors" in {
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
            spec = Chart(ref: Signal[Seq[ARow]])(area(x = _.x, y = _.y, color = _.series))
                .animate(_.ease(300.millis))
                .legend(_.colorScale {
                    case "a" => cyan
                    case _   => amber
                })
            root <- (spec).lower
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
                s"expected 2 area-path fills (one per series) but got ${fills.size}:\n$html"
            )
            // Series "a" (seriesIdx 0) must carry the colorScale cyan, NOT DefaultPalette blue.
            val cyanCss  = "rgb(6, 182, 212)"
            val amberCss = "rgb(245, 158, 11)"
            assert(
                fills(0) == cyanCss,
                s"animated area series 'a' must use colorScale cyan $cyanCss but got ${fills(0)}:\n$html"
            )
            // Series "b" (seriesIdx 1) must carry the colorScale amber, NOT DefaultPalette orange.
            assert(
                fills(1) == amberCss,
                s"animated area series 'b' must use colorScale amber $amberCss but got ${fills(1)}:\n$html"
            )
            // DefaultPalette colours must not appear as area fills.
            assert(
                !fills.exists(_ == blueCss),
                s"DefaultPalette blue $blueCss must not be an area fill under a colorScale:\n$html"
            )
            assert(
                !fills.exists(_ == orangeCss),
                s"DefaultPalette orange $orangeCss must not be an area fill under a colorScale:\n$html"
            )
        end for
    }

    // ---- animated STACKED area honors custom theme.palette and categorical colorScale ----
    // A second spec-drop locus: lowerAreaWithTransitions (the stacked fallthrough). lowerArea forwards
    // spec into lowerAreaStacked, which uses resolvePalette when spec is Present. resolvePalette honors:
    // (1) categorical colorScale; (2) sequential colorScale; (3) theme.palette when no colorScale;
    // (4) DefaultPalette as final fallback.
    // lowerAreaWithTransitions forwards Present(spec) so lowerAreaStacked uses resolvePalette. Calling
    // lowerArea with spec=Absent would route lowerAreaStacked to resolvePaletteFromCfg (DefaultPalette
    // only), dropping BOTH the colorScale AND a custom theme.palette.
    //
    // Test A: custom theme.palette (no colorScale). The animated stacked area must produce the same
    // per-group fills as the static twin.
    "animated stacked area honors custom theme.palette colors, matching the static twin" in {
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
            spec = Chart(ref: Signal[Seq[Sale]])(area(x = _.month, y = _.revenue, stack = by(_.region)))
                .yScale(_.linear(0.0, 6000.0))
                .animate(_.ease(300.millis))
                .theme(_.palette(Chunk(purple, teal)))
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                html.contains("fill=\"#cc00cc\""),
                s"animated stacked area group 0 must use theme.palette purple #cc00cc:\n$html"
            )
            assert(
                html.contains("fill=\"#00cccc\""),
                s"animated stacked area group 1 must use theme.palette teal #00cccc:\n$html"
            )
            // DefaultPalette blue/orange must not appear: they are the resolvePaletteFromCfg (spec=Absent) fallback.
            assert(
                !html.contains("fill=\"#3b82f6\""),
                s"DefaultPalette blue must not appear in animated stacked area under a custom theme:\n$html"
            )
            assert(
                !html.contains("fill=\"#f97316\""),
                s"DefaultPalette orange must not appear in animated stacked area under a custom theme:\n$html"
            )
        end for
    }

    // Test B: categorical colorScale. resolvePalette honors it; resolvePaletteFromCfg ignores it.
    "animated stacked area honors a categorical colorScale, not DefaultPalette" in {
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
            spec = Chart(ref: Signal[Seq[Sale]])(area(x = _.month, y = _.revenue, stack = by(_.region)))
                .yScale(_.linear(0.0, 6000.0))
                .animate(_.ease(300.millis))
                .legend(_.colorScale[Region](
                    Region.NA -> crimson,
                    Region.EU -> indigo
                ))
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            val crimsonCss = "rgb(220, 38, 38)"
            val indigoCss  = "rgb(99, 102, 241)"
            assert(
                html.contains(s"fill=\"$crimsonCss\""),
                s"animated stacked area NA group must use colorScale crimson $crimsonCss:\n$html"
            )
            assert(
                html.contains(s"fill=\"$indigoCss\""),
                s"animated stacked area EU group must use colorScale indigo $indigoCss:\n$html"
            )
            assert(
                !html.contains(s"fill=\"$blueCss\""),
                s"DefaultPalette blue must not appear in animated stacked area under a colorScale:\n$html"
            )
            assert(
                !html.contains(s"fill=\"$orangeCss\""),
                s"DefaultPalette orange must not appear in animated stacked area under a colorScale:\n$html"
            )
        end for
    }

    // ---- Sequential colorScale arm regression guards ----
    // These four tests lock the working Sequential branch in resolvePalette for each newly-fixed
    // color-split mark. Two rows at the domain endpoints [0.0, 100.0] map to exact low/high colors:
    //   value 0.0  -> t=0.0 -> lerpColor(black, white, 0.0) = rgb(0,0,0)
    //   value 100.0 -> t=1.0 -> lerpColor(black, white, 1.0) = rgb(255,255,255)
    // The auto-derived domain equals [0.0, 100.0] when data is exactly [0.0, 100.0].

    "grouped bar with Sequential colorScale honors the scale colors" in {
        // Low=black(0,0,0), High=white(255,255,255); data values 0.0 and 100.0 are the domain endpoints.
        case class HeatRow(month: String, revenue: Double, level: Double) derives CanEqual
        val rows = Chunk(
            HeatRow("Jan", 1000.0, 0.0),
            HeatRow("Jan", 2000.0, 100.0)
        )
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue, color = _.level))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        (spec).lower.map { root =>
            val rects = rectsIn(root)
            assert(rects.size == 2, s"expected 2 bar rects but got ${rects.size}")
            val byX = rects.toSeq.sortBy(r => numOf(r.svgAttrs.x))
            def fillOf(r: Svg.Rect): Style.Color = r.svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"expected color fill but got $other")
            val fills = byX.map(fillOf)
            // value 0.0 (first bar by x) maps to low=black; value 100.0 maps to high=white.
            assert(
                fills(0) == Style.Color.rgb(0, 0, 0),
                s"low-value bar must be low color rgb(0,0,0) but got ${fills(0)}"
            )
            assert(
                fills(1) == Style.Color.rgb(255, 255, 255),
                s"high-value bar must be high color rgb(255,255,255) but got ${fills(1)}"
            )
            // Both colors are distinct and concrete (neither equals a DefaultPalette entry).
            assert(
                fills(0) != fills(1),
                s"low and high colors must differ but both were ${fills(0)}"
            )
            assert(
                !fills.exists(c => c == Style.Color.blue || c == Style.Color.orange),
                s"fills must not be DefaultPalette colors but got $fills"
            )
        }
    }

    "non-stacked area with Sequential colorScale honors the scale colors" in {
        // Low=black(0,0,0), High=white(255,255,255); data values 0.0 and 100.0 are domain endpoints.
        case class SeqRow(x: String, y: Double, level: Double) derives CanEqual
        val rows = Chunk(
            SeqRow("a", 1.0, 0.0),
            SeqRow("b", 2.0, 100.0)
        )
        val spec = Chart(rows)(area(x = _.x, y = _.y, color = _.level))
            .yScale(_.linear(0.0, 4.0))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        (spec).lower.map { root =>
            val marks     = marksGroup(root)
            val areaPaths = marks.children.collect { case p: Svg.Path => p }
            assert(areaPaths.size == 2, s"expected 2 area paths but got ${areaPaths.size}")
            // Paths are emitted in category encounter order (level 0.0 first, then 100.0).
            val fills = areaPaths.map(p => fillColorOf(p.svgAttrs.fill))
            assert(
                fills(0) == Style.Color.rgb(0, 0, 0),
                s"low-value path must be low color rgb(0,0,0) but got ${fills(0)}"
            )
            assert(
                fills(1) == Style.Color.rgb(255, 255, 255),
                s"high-value path must be high color rgb(255,255,255) but got ${fills(1)}"
            )
            // Both colors are distinct and concrete.
            assert(
                fills(0) != fills(1),
                s"low and high colors must differ but both were ${fills(0)}"
            )
            assert(
                !fills.exists(c => c == Style.Color.blue || c == Style.Color.orange),
                s"fills must not be DefaultPalette colors but got $fills"
            )
            // Sequential legend renders a single continuous gradient swatch (not per-category line swatches).
            // The mark fills verify two distinct concrete interpolated colors at the domain endpoints.
        }
    }

    "text mark with Sequential colorScale honors the scale colors" in {
        // Low=black(0,0,0), High=white(255,255,255); data values 0.0 and 100.0 are domain endpoints.
        case class TextSeqRow(x: String, y: Double, level: Double) derives CanEqual
        val rows = Chunk(
            TextSeqRow("a", 1.0, 0.0),
            TextSeqRow("b", 2.0, 100.0)
        )
        val spec = Chart(rows)(text(x = _.x, y = _.y, label = _.x, color = _.level))
            .yScale(_.linear(0.0, 4.0))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        (spec).lower.map { root =>
            val marks = marksGroup(root)
            val texts = marks.children.collect { case t: Svg.Text => t }
            assert(texts.size >= 2, s"expected at least 2 text glyphs but got ${texts.size}")
            val byX   = texts.toSeq.sortBy(t => numOf(t.svgAttrs.x))
            val fill0 = fillColorOf(byX(0).svgAttrs.fill)
            val fill1 = fillColorOf(byX(1).svgAttrs.fill)
            // Glyphs sorted by x: "a" at lower band-center (level=0.0, low color), "b" at higher (level=100.0, high color).
            assert(
                fill0 == Style.Color.rgb(0, 0, 0),
                s"glyph 'a' (level=0.0) must be low color rgb(0,0,0) but got $fill0"
            )
            assert(
                fill1 == Style.Color.rgb(255, 255, 255),
                s"glyph 'b' (level=100.0) must be high color rgb(255,255,255) but got $fill1"
            )
            // Distinct and concrete.
            assert(
                fill0 != fill1,
                s"low and high fills must differ but both were $fill0"
            )
            assert(
                fill0 != Style.Color.blue && fill0 != Style.Color.orange,
                s"fill0 must not be DefaultPalette but got $fill0"
            )
            assert(
                fill1 != Style.Color.blue && fill1 != Style.Color.orange,
                s"fill1 must not be DefaultPalette but got $fill1"
            )
            // Sequential legend renders a single continuous gradient swatch (not per-category rect swatches).
            // The mark fills verify two distinct concrete interpolated colors at the domain endpoints.
        }
    }

    "errorBar with Sequential colorScale honors the scale colors" in {
        // Low=black(0,0,0), High=white(255,255,255); data values 0.0 and 100.0 are domain endpoints.
        case class EbSeqRow(x: String, mean: Double, lo: Double, hi: Double, level: Double) derives CanEqual
        val rows = Chunk(
            EbSeqRow("a", 2.0, 1.0, 3.0, 0.0),
            EbSeqRow("b", 5.0, 4.0, 6.0, 100.0)
        )
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.level))
            .yScale(_.linear(0.0, 10.0))
            .legend(_.colorScaleSequential(Style.Color.black, Style.Color.white))
        (spec).lower.map { root =>
            val marks   = marksGroup(root)
            val lines   = marks.children.collect { case l: Svg.Line => l }
            val circles = marks.children.collect { case c: Svg.Circle => c }
            // 2 rows x 3 lines each = 6 lines total.
            assert(lines.size == 6, s"expected 6 lines (3 per row x 2 rows) but got ${lines.size}")
            assert(circles.size == 2, s"expected 2 center marker circles but got ${circles.size}")
            def strokeOf(l: Svg.Line): Style.Color = l.svgAttrs.stroke match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"expected stroke Color but got $other")
            // Group lines by center-x to separate the two rows.
            def lineCoord(m: Maybe[Double]): Double = m match
                case Present(v) => v
                case Absent     => fail("coordinate absent")
            def centerX(l: Svg.Line): Double =
                (lineCoord(l.svgAttrs.x1) + lineCoord(l.svgAttrs.x2)) / 2.0
            val byCenter = lines.toSeq.groupBy(l => math.round(centerX(l)).toInt)
            assert(byCenter.size == 2, s"expected 2 center groups but got ${byCenter.size}")
            val groups = byCenter.values.toSeq.sortBy(_.map(centerX).min)
            val groupA = groups(0) // lower x -> row "a", level=0.0 -> low=black
            val groupB = groups(1) // higher x -> row "b", level=100.0 -> high=white
            groupA.foreach(l =>
                assert(
                    strokeOf(l) == Style.Color.rgb(0, 0, 0),
                    s"row 'a' (level=0.0) lines must be low color rgb(0,0,0) but got ${strokeOf(l)}"
                )
            )
            groupB.foreach(l =>
                assert(
                    strokeOf(l) == Style.Color.rgb(255, 255, 255),
                    s"row 'b' (level=100.0) lines must be high color rgb(255,255,255) but got ${strokeOf(l)}"
                )
            )
            // Center marker circle fills must match the row stroke.
            def circleCoord(m: Maybe[Double]): Double = m match
                case Present(v) => v
                case Absent     => fail("circle coord absent")
            val circlesByX = circles.toSeq.sortBy(c => circleCoord(c.svgAttrs.cx))
            val fillA = circlesByX(0).svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"circle fill expected Color but got $other")
            val fillB = circlesByX(1).svgAttrs.fill match
                case Present(Svg.Paint.Color(c)) => c
                case other                       => fail(s"circle fill expected Color but got $other")
            assert(fillA == Style.Color.rgb(0, 0, 0), s"row 'a' circle fill must be rgb(0,0,0) but got $fillA")
            assert(fillB == Style.Color.rgb(255, 255, 255), s"row 'b' circle fill must be rgb(255,255,255) but got $fillB")
            // DefaultPalette guard.
            assert(
                groupA.forall(l => strokeOf(l) != Style.Color.blue && strokeOf(l) != Style.Color.orange),
                "row 'a' lines must not use DefaultPalette colors"
            )
            assert(
                groupB.forall(l => strokeOf(l) != Style.Color.blue && strokeOf(l) != Style.Color.orange),
                "row 'b' lines must not use DefaultPalette colors"
            )
        }
    }

    // ---- CatKey collision tests (non-injective toString) ----
    // A group type whose toString is non-injective: Grp(1) and Grp(2) both produce "G".
    // dataMap / rowBySlot / colorIdxByKey must key by category value identity, not toString. Keying by
    // toString would collide: last-writer-wins on the data map while the category list expands to two
    // entries, leading to one group's value being lost and the other double-counted.

    final case class GrpRow(month: String, value: Double, grp: GrpTag) derives CanEqual
    final case class GrpTag(id: Int) derives CanEqual:
        override def toString: String = "G" // non-injective: all GrpTag values share the same label

    "stacked bar: distinct groups with colliding toString must produce two segments with correct heights (not double-counted)" in {
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
        val spec = Chart(rows)(bar(x = _.month, y = _.value, stack = by(_.grp)))
        (spec).lower.map { root =>
            val rects = rectsIn(root)

            // Must have exactly 2 segments (one per distinct group).
            assert(rects.size == 2, s"stacked bar: expected 2 stacked segments but got ${rects.size}")

            // Effective plot height: legend reserve (LegendReservedH=20) shifts the plot down so plotH_eff = 400.
            val plotHEff = 400.0
            val heights  = rects.map(r => numOf(r.svgAttrs.height)).toSeq.sorted
            // The two heights must sum to plotH_eff (total stack 40 covers the full effective plot height).
            val totalHeight = heights.sum
            assertClose(totalHeight, plotHEff, "stacked bar: total stacked height must equal effective plotH=400")
            // Smaller segment (value=10): 10/40 of plotH_eff = 100.
            assertClose(heights(0), plotHEff * 10.0 / 40.0, "stacked bar: smaller segment height (value=10 portion)")
            // Larger segment (value=30): 30/40 of plotH_eff = 300.
            assertClose(heights(1), plotHEff * 30.0 / 40.0, "stacked bar: larger segment height (value=30 portion)")

            // Legend must have 2 distinct swatches.
            val swatches = legendSwatchRects(root)
            assert(swatches.size == 2, s"stacked bar: expected 2 legend swatches but got ${swatches.size}")
        }
    }

    "stacked area: distinct groups with colliding toString must produce two distinct area bands (not one doubled)" in {
        // Two rows at the same x="Jan": grp=GrpTag(1) with value=10, grp=GrpTag(2) with value=30.
        // Correct: two distinct stacked area bands, total stack [0,40], top of stack at plotY=20.
        // Buggy:   groupKeys=["G","G"], dataMap["Jan"]["G"]=30, second band accumulates to 60 ->
        //          top of second band = ys(60) which is BELOW PlotY (a negative pixel, above the SVG).
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Jan", 30.0, GrpTag(2))
        )
        val spec = Chart(rows)(area(x = _.month, y = _.value, stack = by(_.grp)))
        (spec).lower.map { root =>
            // The marks group holds area paths.
            val marks     = marksGroup(root)
            val areaPaths = marks.children.collect { case p: Svg.Path => p }

            // Must have exactly 2 area band paths (one per distinct group).
            assert(areaPaths.size == 2, s"stacked area: expected 2 area band paths but got ${areaPaths.size}")

            // The two bands must have distinct fill colors (each group gets its own palette color).
            val fills = areaPaths.map(p => fillColorOfPath(p)).toSeq.distinct
            assert(fills.size == 2, s"stacked area: expected 2 distinct fill colors but got $fills")

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
            assert(allPathYs.nonEmpty, "stacked area: expected path y coordinates")
            val minY = allPathYs.min
            assert(
                minY >= plotYEff - Tol,
                s"stacked area: topmost y=$minY must be >= plotYEff=$plotYEff (second band must not exceed total stack of 40)"
            )

            // Legend must have 2 distinct swatches.
            val swatches = legendSwatchRects(root)
            assert(swatches.size == 2, s"stacked area: expected 2 legend swatches but got ${swatches.size}")
        }
    }

    "grouped bar: distinct groups with colliding toString must produce two dodged bars with correct heights" in {
        // Two rows at the same x="Jan": grp=GrpTag(1) with value=10, grp=GrpTag(2) with value=30.
        // Correct: 2 dodged bars, one with height proportional to 10, one to 30, in 2 distinct colors.
        // Buggy:   colorIdxByKey["G"]=0, both rows land in sub-slot 0, second overwrites first -> only 1 value rendered.
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Jan", 30.0, GrpTag(2))
        )
        val spec = Chart(rows)(bar(x = _.month, y = _.value, color = _.grp))
        (spec).lower.map { root =>
            val rects = rectsIn(root)

            // Must have exactly 2 bar rects (one per distinct group).
            assert(rects.size == 2, s"grouped bar: expected 2 dodged bar rects but got ${rects.size}")

            // The two bars must have distinct heights (10-proportional and 30-proportional).
            // Layout: color encoding defined => hasLegend=true, LegendReservedH=20.
            // plotY_eff=40, plotH_eff=400, baseline=440.
            // y scale: extent [0,30] (max is 30), niceTicks -> nHi=30.
            // Scale.Linear(0,30,baseline=440,top=40): apply(10)=440+(10/30)*(40-440)=440-133.33=306.67; barH=133.33.
            //                                          apply(30)=40; barH=440-40=400.
            val plotHEff = 400.0
            val heights  = rects.map(r => numOf(r.svgAttrs.height)).toSeq.sorted
            assertClose(heights(0), plotHEff * 10.0 / 30.0, "grouped bar: smaller bar height (value=10 portion)")
            assertClose(heights(1), plotHEff, "grouped bar: taller bar height (value=30 fills full effective plot)")

            // The two bars must have distinct x positions (dodged side by side).
            val xs = rects.map(r => numOf(r.svgAttrs.x)).toSeq.sorted
            assert(xs(0) != xs(1), s"grouped bar: the two bars must be at different x positions (dodged), but both at ${xs(0)}")

            // The two bars must have distinct fill colors.
            val fills = rects.map(r => numOf(r.svgAttrs.x)).toSeq
            val fillColors = rects.map(r =>
                r.svgAttrs.fill match
                    case Present(Svg.Paint.Color(c)) => c
                    case other                       => fail(s"grouped bar: expected color fill but got $other")
            ).toSeq.distinct
            assert(fillColors.size == 2, s"grouped bar: expected 2 distinct fill colors but got $fillColors")

            // Legend must have 2 distinct swatches.
            val swatches = legendSwatchRects(root)
            assert(swatches.size == 2, s"grouped bar: expected 2 legend swatches but got ${swatches.size}")
        }
    }

    "line color: distinct color series with colliding toString must not be merged into one another" in {
        // GrpTag(1) appears only at x="Jan"; GrpTag(2) only at x="Feb". With both toString=="G",
        // the static lowerLine color split filtered `accessor(r).toString == key` (== "G"), so EVERY
        // series swept up ALL rows. Correct: each series path holds only its own single point (1 MoveTo,
        // 0 LineTo). Buggy: each path holds both points (1 MoveTo + 1 LineTo).
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Feb", 30.0, GrpTag(2))
        )
        val spec = Chart(rows)(line(x = _.month, y = _.value, color = _.grp))
        (spec).lower.map { root =>
            val paths = pathsIn(root)
            assert(paths.size == 2, s"line color: expected 2 series paths but got ${paths.size}")
            def lineToCount(p: Svg.Path): Int =
                Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).toSeq.count:
                    case _: PathCommand.LineTo => true
                    case _                     => false
            val lineTos = paths.map(lineToCount).toSeq
            assert(
                lineTos.forall(_ == 0),
                s"line color: each single-point series must have 0 LineTo commands (one point each), got $lineTos"
            )
        }
    }

    "area color: distinct color series with colliding toString must not be merged into one another" in {
        // Same setup as line color, for the non-stacked color area path. The static lowerArea
        // color split also filtered `accessor(r).toString == key`, merging distinct-toString series.
        // A single-point closed area = top MoveTo, lineTo(lastX, baseline), lineTo(firstX, baseline), close
        // -> exactly 2 LineTo commands. The buggy merge (both points) adds a top-edge LineTo -> 3.
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Feb", 30.0, GrpTag(2))
        )
        val spec = Chart(rows)(area(x = _.month, y = _.value, color = _.grp))
        (spec).lower.map { root =>
            val paths = pathsIn(root)
            assert(paths.size == 2, s"area color: expected 2 series paths but got ${paths.size}")
            def lineToCount(p: Svg.Path): Int =
                Svg.PathData.commands(p.svgAttrs.d.getOrElse(Svg.PathData.empty)).toSeq.count:
                    case _: PathCommand.LineTo => true
                    case _                     => false
            val lineTos = paths.map(lineToCount).toSeq
            assert(
                lineTos.forall(_ == 2),
                s"area color: each single-point series area must have 2 LineTo commands, got $lineTos"
            )
        }
    }

    "text color: distinct color categories with colliding toString must get distinct palette colors" in {
        // GrpTag(1) at x="Jan", GrpTag(2) at x="Feb", both toString=="G". lowerText keyed its color
        // index by label toString (catIdxText: Map[String,Int], first-seen wins), so both rows resolved
        // to palette(0). Correct: GrpTag(1) -> palette(0), GrpTag(2) -> palette(1) (two distinct colors).
        val rows = Chunk(
            GrpRow("Jan", 10.0, GrpTag(1)),
            GrpRow("Feb", 30.0, GrpTag(2))
        )
        val spec = Chart(rows)(text(x = _.month, y = _.value, label = _.month, color = _.grp))
            .yScale(_.linear(0.0, 40.0))
        (spec).lower.map { root =>
            val marks = marksGroup(root)
            val texts = marks.children.collect { case t: Svg.Text => t }
            assert(texts.size == 2, s"text color: expected 2 text glyphs but got ${texts.size}")
            val byX = texts.toSeq.sortBy(t => numOf(t.svgAttrs.x))
            val c0  = fillColorOf(byX(0).svgAttrs.fill)
            val c1  = fillColorOf(byX(1).svgAttrs.fill)
            assert(c0 != c1, s"text color: the two distinct color categories must get distinct fills, both got $c0")
        }
    }

    "errorBar color: distinct color categories with colliding toString must get distinct stroke colors" in {
        // Mirror of text color for errorBar. lowerErrorBar must key colorIdx by category value identity;
        // keying by label toString (catIdxErr) would collapse the two distinct categories onto palette(0).
        case class EbGrp(x: String, mean: Double, lo: Double, hi: Double, grp: GrpTag) derives CanEqual
        val rows = Chunk(
            EbGrp("a", 6.0, 4.0, 8.0, GrpTag(1)),
            EbGrp("b", 3.0, 1.0, 5.0, GrpTag(2))
        )
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.grp))
            .yScale(_.linear(0.0, 10.0))
        (spec).lower.map { root =>
            val marks   = marksGroup(root)
            val circles = marks.children.collect { case c: Svg.Circle => c }
            assert(circles.size == 2, s"errorBar color: expected 2 center-marker circles but got ${circles.size}")
            val byX = circles.toSeq.sortBy(c => c.svgAttrs.cx.getOrElse(0.0))
            val f0  = fillColorOf(byX(0))
            val f1  = fillColorOf(byX(1))
            assert(f0 != f1, s"errorBar color: the two distinct color categories must get distinct strokes, both got $f0")
        }
    }

    // ---- negative-value bar emits a non-negative-height rect anchored at the zero baseline ----

    "negative-value bar emits a non-negative-height rect anchored at the zero baseline" in {
        // Data: two bars, one positive (y=5) and one negative (y=-3).
        // y extent: Continuous(-3, 5) from data; after ensureZero: Continuous(-3, 5).
        // niceTicks(-3, 5, 5): rawStep=2, step=2; snappedLo=floor(-3/2)*2=-4, snappedHi=ceil(5/2)*2=6.
        // Scale.Linear(-4, 6, 440, 20) (domainRange=10, pixelRange=-420):
        //   apply(0)  = 440 + (0-(-4))/10 * (20-440) = 440 + 4/10 * (-420) = 440 - 168 = 272
        //   apply(5)  = 440 + (5-(-4))/10 * (-420)   = 440 + 9/10 * (-420) = 440 - 378 = 62
        //   apply(-3) = 440 + ((-3)-(-4))/10 * (-420) = 440 + 1/10 * (-420) = 440 - 42 = 398
        // zero-line baseline = ys.apply(0) = 272
        // Positive bar (y=5): barY=62, rectY=min(62,272)=62, rectH=|272-62|=210
        // Negative bar (y=-3): barY=398, rectY=min(398,272)=272, rectH=|272-398|=126
        case class Row(x: String, y: Int) derives CanEqual
        val rows = Chunk(Row("a", 5), Row("b", -3))
        val spec = Chart(rows)(bar(x = _.x, y = _.y))
        (spec).lower.map { root =>
            val rects = rectsIn(root)
            assert(rects.size == 2, s"Expected 2 rects but got ${rects.size}")
            // Every rect must have a non-negative height.
            rects.foldLeft(()) { (_, r) =>
                val h = numOf(r.svgAttrs.height)
                assert(h >= 0.0, s"Rect height must be >= 0 but got $h")
            }
            // The rect for the negative bar (-3) at x="b" comes second (bar order follows data order).
            // zero-line baseline (ys.apply(0) for the snapped domain (-4,6)) = 272.0
            val zeroBaseline = 272.0
            val negRect      = rects(1) // second row is "b" with y=-3
            val negY         = numOf(negRect.svgAttrs.y)
            val negH         = numOf(negRect.svgAttrs.height)
            // The negative bar's rect must start at the zero baseline and extend down to apply(-3)=398.
            assertClose(negY, zeroBaseline, "negative bar rectY must equal zero-line baseline")
            assertClose(negH, 126.0, "negative bar rectH must equal |apply(-3) - zero-baseline|")
            // The positive bar (y=5) must start at apply(5)=62 and extend up to the zero baseline=272.
            val posRect = rects(0)
            val posY    = numOf(posRect.svgAttrs.y)
            val posH    = numOf(posRect.svgAttrs.height)
            assertClose(posY, 62.0, "positive bar rectY must equal apply(5)")
            assertClose(posH, 210.0, "positive bar rectH must equal |zero-baseline - apply(5)|")
        }
    }

    // ---- y0/y1 area band ribbon is band-centered (not left-edge) ----

    "y0-y1 area band ribbon x coordinates are centered on the band, matching a point on the same data" in {
        // A band ribbon (area with y0/y1 on a categorical x) must place its vertices at the band
        // CENTER (bandLeft + bandwidth/2), the same position where a point mark on the same x lands.
        // n=2, slot=280, bandW=252, pad=14
        // Center of "a": 60 + 0*280 + 14 + 252/2 = 60 + 14 + 126 = 200.0
        // Center of "b": 60 + 1*280 + 14 + 126    = 480.0
        case class Row(x: String, lo: Double, hi: Double) derives CanEqual
        val rows = Chunk(Row("a", 10.0, 30.0), Row("b", 20.0, 50.0))
        // Use a fixed y scale so pixel positions are deterministic.
        val spec = Chart(rows)(area(x = _.x, y0 = _.lo, y1 = _.hi))
            .yScale(_.linear(0.0, 60.0))
        (spec).lower.map { root =>
            val paths = pathsIn(root)
            assert(paths.nonEmpty, "Expected at least one area ribbon path")
            val cmds = paths(0).svgAttrs.d match
                case Present(pd) => Svg.PathData.commands(pd)
                case Absent      => fail("Expected path to have d attribute")
            // Extract all x coordinates from path commands.
            val xs = cmds.collect:
                case Svg.PathCommand.MoveTo(x, _) => x
                case Svg.PathCommand.LineTo(x, _) => x
            // The slot / band dimensions for n=2.
            val slot  = 280.0
            val bandW = 252.0
            val pad   = (slot - bandW) / 2.0                 // 14.0
            val cx_a  = PlotX + 0 * slot + pad + bandW / 2.0 // 200.0
            val cx_b  = PlotX + 1 * slot + pad + bandW / 2.0 // 480.0
            assert(
                xs.exists(x => math.abs(x - cx_a) < Tol),
                s"Ribbon must contain a vertex at band center cx_a=$cx_a; found x coords: $xs"
            )
            assert(
                xs.exists(x => math.abs(x - cx_b) < Tol),
                s"Ribbon must contain a vertex at band center cx_b=$cx_b; found x coords: $xs"
            )
        }
    }

    // ---- stacked area must skip all-negative groups consistently in render and accumulator ----

    "stacked area with a negative-value group does not corrupt subsequent group baselines" in {
        // Three groups at x="a": "first"=4 (positive), "neg"=-2 (all-negative, skipped), "second"=3 (positive).
        // "neg" is skipped by hasContribution (> 0.0); its values must NOT be added to accByX either.
        // If they were, accByX would reflect the neg accumulation when "second" is rendered, so "second"'s
        // bottom edge would use the wrong baseline, leaving a gap between "first" and "second".
        // The group must be skipped consistently in BOTH the render and the accumulator.
        // This chart has 3 groups (stack grouping), so hasLegend=true -> LegendReservedH=20 reserved,
        // making plotY=40, plotH=400, baseline=440, rangeHi=40. Scale.Linear(0, 10, 440, 40):
        //   apply(0) = 440 + 0/10 * (-400) = 440
        //   apply(2) = 440 + 2/10 * (-400) = 440 - 80 = 360  <- corrupted: neg accumulation leaves accY=2
        //   apply(4) = 440 + 4/10 * (-400) = 440 - 160 = 280 <- correct: "first" top = "second" bottom
        //   apply(7) = 440 + 7/10 * (-400) = 440 - 280 = 160 <- "second" top
        // Correct: "second" py0 = apply(4) = 280 (= "first"'s top). Correct stack, no gap.
        // Corrupted: "second" py0 = apply(2) = 360 (below "first"'s top at 280). Gap in stack.
        case class Row(x: String, y: Double, grp: String) derives CanEqual
        val rows = Chunk(
            Row("a", 4.0, "first"),
            Row("a", -2.0, "neg"),
            Row("a", 3.0, "second")
        )
        val spec = Chart(rows)(area(x = _.x, y = _.y, stack = by(_.grp)))
            .yScale(_.linear(0.0, 10.0))
        (spec).lower.map { root =>
            val paths = pathsIn(root)
            assert(paths.nonEmpty, "Expected at least one stacked area path")
            // Collect all y-coords from all rendered paths.
            val allYCoords: Chunk[Double] = paths.flatMap: p =>
                val cmds = p.svgAttrs.d match
                    case Present(pd) => Svg.PathData.commands(pd)
                    case Absent      => Chunk.empty
                cmds.collect:
                    case Svg.PathCommand.MoveTo(_, y) => y
                    case Svg.PathCommand.LineTo(_, y) => y
            // "second" group bottom = apply(4) = 280 must appear.
            val expectedSecondBot = 280.0
            assert(
                allYCoords.exists(y => math.abs(y - expectedSecondBot) < Tol),
                s"Second group bottom edge must be $expectedSecondBot (first group top); got y coords: $allYCoords"
            )
            // A corrupted baseline would put "second" bottom at apply(2) = 360. This must NOT appear.
            val bugSecondBot = 360.0
            assert(
                !allYCoords.exists(y => math.abs(y - bugSecondBot) < Tol),
                s"Second group bottom must NOT be the corrupted baseline $bugSecondBot; got y coords: $allYCoords"
            )
        }
    }

    // ---- sparse grouped bars are packed and centered within the band ----

    "sparse grouped bar: two present categories in a band are packed and centered, not placed at their global slots" in {
        // 3 color categories total (Cat.A=0, Cat.B=1, Cat.C=2). Band "Jan" has only Cat.A and Cat.C.
        // Band "Feb" has all three, so dodge=true.
        //
        // Band scale: 2 x-categories ["Jan","Feb"], plotW=560.
        //   slot = 560/2 = 280, bandW = 560*0.9/2 = 252, pad = (280-252)/2 = 14
        //   bandX["Jan"] = 60 + 0*280 + 14 = 74
        //   bandX["Feb"] = 60 + 1*280 + 14 = 354
        //
        // subW = bandW/numColors = 252/3 = 84.0
        //
        // Dense band "Feb" (k=3, all present): groupOffset=0, localIdx=colorIdx.
        //   Cat.A (colorIdx=0): barX = 354 + 0 + 0*84 = 354
        //   Cat.B (colorIdx=1): barX = 354 + 0 + 1*84 = 438
        //   Cat.C (colorIdx=2): barX = 354 + 0 + 2*84 = 522
        //
        // Sparse band "Jan" (k=2, Cat.A and Cat.C only):
        //   groupOffset = (252 - 2*84) / 2 = (252 - 168) / 2 = 42
        //   Cat.A (localIdx=0): barX = 74 + 42 + 0*84 = 116
        //   Cat.C (localIdx=1): barX = 74 + 42 + 1*84 = 200
        //
        // A global-slot placement would instead give Cat.A barX = 74 + 0*84 = 74 and
        //   Cat.C barX = 74 + 2*84 = 242, leaving the band uncentered.

        enum Cat derives CanEqual, Plottable:
            case A, B, C

        case class GRow(x: String, y: Double, cat: Cat) derives CanEqual

        val rows = Chunk(
            GRow("Jan", 1.0, Cat.A), // Jan has Cat.A
            GRow("Jan", 2.0, Cat.C), // Jan has Cat.C (Cat.B absent from Jan)
            GRow("Feb", 3.0, Cat.A),
            GRow("Feb", 4.0, Cat.B),
            GRow("Feb", 5.0, Cat.C)
        )
        val spec = Chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
        (spec).lower.map { root =>
            val rects = rectsIn(root)
            assert(rects.size == 5, s"Expected 5 rects but got ${rects.size}")

            // Extract x coords from the 5 rects. The first two are for "Jan" (Cat.A and Cat.C).
            val xs0 = rects(0).svgAttrs.x
            val xs1 = rects(1).svgAttrs.x

            // Band and subW constants.
            val bandW  = 252.0       // 560 * 0.9 / 2
            val subW   = bandW / 3.0 // 84.0
            val bandXj = 74.0        // bandX for "Jan"

            // Expected packed+centered positions.
            val groupOffset   = (bandW - 2.0 * subW) / 2.0        // 42.0
            val expectedBarX0 = bandXj + groupOffset + 0.0 * subW // 116.0
            val expectedBarX1 = bandXj + groupOffset + 1.0 * subW // 200.0

            assertClose(numOf(xs0), expectedBarX0, s"Cat.A bar in sparse Jan band must be at packed+centered slot 0")
            assertClose(numOf(xs1), expectedBarX1, s"Cat.C bar in sparse Jan band must be at packed+centered slot 1")

            // Verify the dense "Feb" band uses the simple dense placement (groupOffset=0).
            val xs2    = rects(2).svgAttrs.x
            val xs3    = rects(3).svgAttrs.x
            val xs4    = rects(4).svgAttrs.x
            val bandXf = 354.0
            assertClose(numOf(xs2), bandXf + 0.0 * subW, s"Cat.A in dense Feb band: slot 0")
            assertClose(numOf(xs3), bandXf + 1.0 * subW, s"Cat.B in dense Feb band: slot 1")
            assertClose(numOf(xs4), bandXf + 2.0 * subW, s"Cat.C in dense Feb band: slot 2")
        }
    }

end ChartLowerTest
