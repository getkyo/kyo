package kyo

import kyo.Chart.*
import kyo.Svg.Coord
import kyo.Svg.PathCommand
import kyo.Svg.PathData
import kyo.internal.ChartLower
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
        val spec  = Chart(rows)(bar(x = _.month, y = _.revenue))
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
        val spec  = Chart(rows)(line(x = _.x, y = _.y))
        val root  = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val paths = pathsIn(root)
        assert(paths.size == 1, s"Expected 1 path but got ${paths.size}")
        val cmds = paths(0).svgAttrs.d match
            case Present(pd) => PathData.commands(pd)
            case Absent      => fail("Expected path to have d attribute")
        // Band: n=2, slot=280, bandW=252, padding=(280-252)/2=14
        val slot  = 280.0
        val bandW = 252.0
        val pad   = (slot - bandW) / 2.0   // 14.0
        val px_a  = PlotX + 0 * slot + pad // 74.0
        val px_b  = PlotX + 1 * slot + pad // 354.0
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
        val spec  = Chart(rows)(area(x = _.x, y = _.y))
        val root  = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val paths = pathsIn(root)
        assert(paths.size == 1, s"Expected 1 path but got ${paths.size}")
        val cmds = paths(0).svgAttrs.d match
            case Present(pd) => PathData.commands(pd)
            case Absent      => fail("Expected path to have d attribute")
        val slot  = 280.0
        val bandW = 252.0
        val pad   = (slot - bandW) / 2.0
        val px_a  = PlotX + pad        // 74.0
        val px_b  = PlotX + slot + pad // 354.0
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
        val spec    = Chart(rows)(point(x = _.x, y = _.y))
        val root    = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val circles = circlesIn(root)
        assert(circles.size == 2, s"Expected 2 circles but got ${circles.size}")
        val slot  = 280.0
        val bandW = 252.0
        val pad   = (slot - bandW) / 2.0
        val px_b  = PlotX + slot + pad // 354.0
        val c     = circles(1)         // second circle (for row "b", y=100)
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
        val spec    = Chart(rows)(point(x = _.x, y = _.y))
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
        val spec = Chart(rows)(
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
        val spec  = Chart(rows)(line(x = _.x, y = _.y))
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
        val slot  = PlotW / 3.0
        val bandW = PlotW * 0.9 / 3.0 // 168.0
        val pad   = (slot - bandW) / 2.0
        val px_a  = PlotX + 0 * slot + pad
        val px_c  = PlotX + 2 * slot + pad
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
        val spec              = Chart(rows)(bar(x = _.month, y = _.revenue))
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

    // ---- Test 8: color channel splits bar into N grouped sub-bands ----

    "color channel splits a bar into N grouped sub-bands (N rects per band)" in {
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
        val spec  = Chart(rows)(bar(x = _.month, y = _.revenue, color = _.region))
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

    // ---- Test 9: line path has fill=None, stroke present, strokeWidth present ----
    // Regression test for the bug where lowerLineSeries produced a filled black polygon instead of a stroked line.
    // The old code did `Svg.path.d(pathData)` with no paint, so the browser filled the path with black (default fill).

    "line mark lowers to a path with fill=Paint.None, stroke present, and strokeWidth present" in {
        case class Row(x: String, y: Int)
        val rows = Chunk(Row("a", 100), Row("b", 200))
        val spec = Chart(rows)(line(x = _.x, y = _.y))
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
        val spec  = Chart(rows)(bar(x = _.month, y = _.revenue))
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
    // A chart with a single mark and no explicit color channel uses the first palette entry (blue).

    "single-mark bar uses palette(0) (blue) as its default fill" in {
        val rows  = Chunk(Sale("Jan", Usd(1000)))
        val spec  = Chart(rows)(bar(x = _.month, y = _.revenue))
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
    // In a combo chart (bar + line, both without an explicit color channel) mark 0 (bar) must use
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
            .yAxis(_.left)
            .yAxisRight(_.right)
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
        val spec  = Chart(rows)(bar(x = _.month, y = _.revenue)).theme(_.dark)
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
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
            .yAxis(_.left.ticks(3))
            .xAxis(_.bottom.label("Month"))
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
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue)).theme(_.dark).size(360, 240)
        val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val bg   = root.children.collectFirst { case r: Svg.Rect => r }.getOrElse(fail("Expected a background rect"))
        assertClose(numOf(bg.svgAttrs.x), 0.0, "background x")
        assertClose(numOf(bg.svgAttrs.y), 0.0, "background y")
        assertClose(numOf(bg.svgAttrs.width), 360.0, "background width spans full SVG")
        assertClose(numOf(bg.svgAttrs.height), 240.0, "background height spans full SVG")
    }

end ChartLowerTest
