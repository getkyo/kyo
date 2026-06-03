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

end ChartLowerTest
