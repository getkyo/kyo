package kyo

import kyo.Chart.*
import kyo.UI.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

class ChartSpecTest extends kyo.test.Test[Any]:

    // ---- domain types used across tests ----

    enum Region derives CanEqual, Plottable:
        case NA, EU, APAC

    opaque type Usd <: Double = Double
    object Usd:
        def apply(d: Double): Usd     = d
        given Plottable[Usd]          = Plottable.numeric
        given CanEqual[Usd, Usd]      = CanEqual.derived
        given Conversion[Double, Usd] = d => d
    end Usd

    case class Sale(month: String, revenue: Usd, region: Region)
    given CanEqual[Sale, Sale] = CanEqual.derived

    val sales: Chunk[Sale] = Chunk(
        Sale("Jan", Usd(1000), Region.NA),
        Sale("Feb", Usd(2000), Region.EU),
        Sale("Mar", Usd(1500), Region.APAC)
    )

    // ---- inference: bar with color builds Chart.Spec[Sale] ----

    "bar with color infers Chart.Spec[Sale] without annotations" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue, color = _.region))
        assert(spec.marks.length == 1)
        spec.marks.head match
            case Mark.Bar(_, _, color, _, _, _, _, _) =>
                assert(color.isDefined)
            case other =>
                fail(s"Expected Mark.Bar but got $other")
        end match
        succeed
    }

    // ---- sentinel: bar without color yields Absent ----

    "bar without color yields Absent, bar with color yields Present" in {
        val specNoColor = Chart(sales)(bar(x = _.month, y = _.revenue))
        val specColor   = Chart(sales)(bar(x = _.month, y = _.revenue, color = _.region))

        specNoColor.marks.head match
            case Mark.Bar(_, _, color, _, _, _, _, _) =>
                assert(color == Absent)
            case other =>
                fail(s"Expected Mark.Bar but got $other")
        end match

        specColor.marks.head match
            case Mark.Bar(_, _, color, _, _, _, _, _) =>
                assert(color.isDefined)
            case other =>
                fail(s"Expected Mark.Bar but got $other")
        end match

        succeed
    }

    // ---- grouping carrier: by(_.region) and normalize ----

    "by(_.region) yields Grouping with Present group and normalize false" in {
        val g = by[Sale](_.region)
        assert(g.group.isDefined)
        assert(g.normalize == false)
    }

    "by(_.region, normalize = true) yields Grouping with normalize true" in {
        val g = by[Sale](_.region, normalize = true)
        assert(g.group.isDefined)
        assert(g.normalize == true)
    }

    // ---- config threading ----

    "size(640, 360) sets spec.size" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue)).size(640, 360)
        assert(spec.chartSize == (640, 360))
    }

    "yAxis(_.grid.ticks(5)) sets showGrid true and tickCount 5" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue)).yAxis(_.grid.ticks(5))
        // side field removed (dead/false knob per design §GAP-AXISCONFIG-SIDE)
        assert(spec.yAxisCfg.showGrid == true)
        assert(spec.yAxisCfg.tickCount == 5)
    }

    "key(_.month) sets spec.key to Present" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue)).key(_.month)
        assert(spec.key.isDefined)
    }

    "onHover(ref) stores the ref in spec.onHover" in {
        Signal.initRef(Maybe.empty[Sale]).map: ref =>
            val spec = Chart(sales)(bar(x = _.month, y = _.revenue)).onHover(ref)
            spec.onHover match
                case Present(storedRef) => assert(storedRef eq ref)
                case Absent             => fail("Expected onHover to be Present")
    }

    // ---- two overloads: Chunk and Signal ----

    "Chart(chunk)(...) produces DataSource.Static" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue))
        spec.data match
            case DataSource.Static(_) => succeed
            case DataSource.Live(_)   => fail("Expected Static but got Live")
    }

    "Chart(signal)(...) produces DataSource.Live" in {
        Signal.initRef[Seq[Sale]](sales).map: sig =>
            val spec = Chart(sig)(bar(x = _.month, y = _.revenue))
            spec.data match
                case DataSource.Live(_)   => succeed
                case DataSource.Static(_) => fail("Expected Live but got Static")
    }

    // ---- legend config ----

    "legend(_.top) sets position to Top" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue)).legend(_.top)
        spec.legendCfg.position match
            case Present(LegendPosition.Top) => succeed
            case other                       => fail(s"Expected Present(LegendPosition.Top) but got $other")
    }

    // ---- combo mark ----

    "combo chart with bar + rule has marks.length == 2" in {
        val spec = Chart(sales)(
            bar(x = _.month, y = _.revenue),
            rule[Sale, Usd](y = RuleValue.Const(Usd(1000), summon[Plottable[Usd]]))
        )
        assert(spec.marks.length == 2)
    }

    // ---- positive typeCheck: the named-param forms compile ----

    "positive typeCheck: bar(x = _.month, y = _.revenue, color = _.region) compiles" in {
        // Test using the types already defined in this test class
        typeCheck("""
            import kyo.*
            import kyo.UI.*
            import kyo.UI.Ast.*
            import kyo.Chart.*
            enum Region2 derives CanEqual, Plottable:
                case NA, EU, APAC
            case class Sale2(month: String, revenue: Double, region: Region2)
            val sales: Chunk[Sale2] = Chunk.empty
            val spec = Chart(sales)(bar(x = _.month, y = _.revenue, color = _.region))
            val _: Chart.Spec[Sale2] = spec
        """)
    }

    "positive typeCheck: Chart(chunk) infers Chart.Spec and Chart(signal) infers DataSource.Live at runtime" in {
        typeCheck("""
            import kyo.*
            import kyo.UI.*
            import kyo.UI.Ast.*
            import kyo.Chart.*
            case class Row(x: String, y: Int)
            given CanEqual[Row, Row] = CanEqual.derived
            val chunk: Chunk[Row] = Chunk.empty
            val specStatic: Chart.Spec[Row] = Chart(chunk)(bar(x = _.x, y = _.y))
        """)
        // Signal inference is covered by the runtime "Chart(signal)(...) produces DataSource.Live" test.
        succeed
    }

    // ---- compile gates: shouldNot typeCheck ----

    "rule(color = ...) does not compile" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.UI.*
            import kyo.UI.Ast.*
            import kyo.Chart.*
            case class Row(x: String, y: Int, c: String)
            val rows: Chunk[Row] = Chunk.empty
            Chart(rows)(rule(color = _.c))
        """)("does not have a parameter color")
    }

    "bar(size = ...) does not compile" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.UI.*
            import kyo.UI.Ast.*
            import kyo.Chart.*
            case class Row(x: String, y: Int, s: Double)
            val rows: Chunk[Row] = Chunk.empty
            Chart(rows)(bar(x = _.x, y = _.y, size = _.s))
        """)("does not have a parameter size")
    }

    "chained bar(...).color(...) does not compile" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.UI.*
            import kyo.UI.Ast.*
            import kyo.Chart.*
            case class Row(x: String, y: Int, c: String)
            val rows: Chunk[Row] = Chunk.empty
            val m = bar(x = (r: Row) => r.x, y = (r: Row) => r.y)
            m.color(_.c)
        """)("value color is not a member")
    }

    "by(_.region).normalized does not compile" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.UI.*
            import kyo.UI.Ast.*
            import kyo.Chart.*
            enum Region derives CanEqual:
                case NA, EU
            case class Row(x: String, y: Int, region: Region)
            by[Row](_.region).normalized
        """)("value normalized is not a member")
    }

    // ---- rule constant form ----

    "rule with Const carries the value in RuleValue.Const" in {
        val rv: RuleValue[Usd] = RuleValue.Const(Usd(1000), summon[Plottable[Usd]])
        rv match
            case RuleValue.Const(v, _) => assert(v == 1000.0)
            case _                     => fail("Expected RuleValue.Const")
    }

    // ---- line mark has correct structure ----

    "line mark with color has color Present" in {
        case class Row(x: String, y: Int, series: String)
        val rows = Chunk(Row("a", 1, "s1"), Row("b", 2, "s2"))
        val spec = Chart(rows)(line(x = _.x, y = _.y, color = _.series))
        spec.marks.head match
            case Mark.Line(_, _, color, curve, defined, _, _, _, _) =>
                assert(color.isDefined)
                assert(curve == Curve.linear)
                assert(defined == Absent)
            case other => fail(s"Expected Mark.Line but got $other")
        end match
        succeed
    }

    // ---- point mark has size Present when supplied ----

    "point mark with size has size Present" in {
        case class Row(x: Int, y: Int, sz: Double)
        val rows = Chunk(Row(1, 2, 3.0))
        val spec = Chart(rows)(point(x = _.x, y = _.y, size = _.sz))
        spec.marks.head match
            case Mark.Point(_, _, _, size, _, symbol, _, _, _, _) =>
                assert(size.isDefined)
                assert(symbol == Absent)
            case other => fail(s"Expected Mark.Point but got $other")
        end match
        succeed
    }

    // ---- axis marker ----

    "line with axis = Axis.Right stores Axis.Right" in {
        val spec = Chart(sales)(line(x = _.month, y = _.revenue, axis = Axis.Right))
        spec.marks.head match
            case Mark.Line(_, _, _, _, _, _, _, _, axis) =>
                assert(axis == Axis.Right)
            case other => fail(s"Expected Mark.Line but got $other")
        end match
        succeed
    }

    // ---- Chart.Spec converts to Svg.Root ----

    "Chart.Spec[Sale] converts to Svg.Root via given Conversion" in {
        val spec: Chart.Spec[Sale] = Chart(sales)(bar(x = _.month, y = _.revenue))
        val root: Svg.Root         = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
        succeed
    }

    // ---- Phase-4 tests: rule defaults and interaction config (INV-020, D23) ----

    // Test: rule(y=80.0) compiles via Conversion with no null (INV-020, plan leaf 9)
    "rule(y=80.0) builds Mark.Rule with Const via implicit Conversion (INV-020)" in {
        val m = rule[Sale, Double](y = 80.0)
        m match
            case Mark.Rule(Absent, Present(RuleValue.Const(v, _)), Axis.Left) =>
                // v is erased to Any in the pattern; cast is safe because we know the Plottable[Double] evidence.
                assert(v.asInstanceOf[Double] == 80.0, s"rule y value must be 80.0, got $v")
            case other =>
                fail(s"Expected Mark.Rule(Absent, Present(Const(80.0,...)), Left) but got $other")
        end match
    }

    // Test: rule with both positions Unset is skipped at lowering (INV-020, plan leaf 10)
    "rule() with both positions Unset emits no rule line while the sibling bar renders (INV-020)" in {
        val m    = rule[Sale, Double]()
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue), m)
        val root = summon[Conversion[Chart.Spec[Sale], Svg.Root]](spec)
        // The marks live in a single Svg.G (chrome axis lines are direct root children, not in a G).
        // Scope the line check to the marks group so axis lines do not leak into the assertion.
        val marksGroups = root.children.collect { case g: Svg.G => g }.filter(g =>
            g.children.exists { case _: Svg.Rect => true; case _ => false }
        )
        assert(marksGroups.nonEmpty, "the sibling bar must still render inside a marks Svg.G")
        val marksRects = marksGroups.flatMap(_.children.collect { case r: Svg.Rect => r })
        val marksLines = marksGroups.flatMap(_.children.collect { case l: Svg.Line => l })
        assert(marksRects.nonEmpty, "the sibling bar must still render a rect")
        assert(marksLines.isEmpty, "a rule() with both positions Unset must emit NO Svg.Line (skipped at lowering)")
    }

    // Test: no null.asInstanceOf remains in Chart.scala rule factory (INV-020, plan leaf 11)
    "rule default produces a RuleValue.Unset sentinel that is matchable (INV-020)" in {
        val rv = RuleValue.unset[Double]
        // Must be the singleton Unset, not null. Check via isInstanceOf (type erasure means we cannot
        // pattern match RuleValue.Unset against RuleValue[Double] without a CanEqual widening).
        assert(rv.isInstanceOf[RuleValue.Unset.type], s"Expected RuleValue.Unset but got $rv")
    }

    // Test: existing rule call sites with explicit RuleValue.Const still compile (INV-020, plan leaf 22)
    "existing rule(y = RuleValue.Const(...)) call sites still produce the same Mark.Rule (INV-020)" in {
        val rv1 = RuleValue.Const(Usd(1000), summon[Plottable[Usd]])
        val m1  = rule[Sale, Usd](y = rv1)
        m1 match
            case Mark.Rule(Absent, Present(RuleValue.Const(v, _)), Axis.Left) =>
                // v is erased; cast safe because we know the Plottable[Usd] evidence.
                assert(v.asInstanceOf[Double] == 1000.0, s"rule y Const value must be 1000.0, got $v")
            case other =>
                fail(s"Expected Mark.Rule with Const(1000) but got $other")
        end match
    }

    // Test: Chart.Spec.InteractionConfig.default has all highlighting disabled (D23)
    "Chart.Spec.InteractionConfig.default has all highlights disabled (D23)" in {
        val cfg = Chart.Spec.InteractionConfig.default
        assert(!cfg.hoverHighlight, "hoverHighlight must be false by default")
        assert(!cfg.selectHighlight, "selectHighlight must be false by default")
        assert(cfg.hoverStyle == Absent, "hoverStyle must be Absent by default")
        assert(cfg.selectStyle == Absent, "selectStyle must be Absent by default")
    }

    // Test: interaction extension method updates interactionCfg (D23)
    "interaction extension method updates interactionCfg (D23)" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue))
            .interaction(_.highlightSelect)
        assert(spec.interactionCfg.selectHighlight, "highlightSelect must set selectHighlight=true")
        assert(!spec.interactionCfg.hoverHighlight, "hoverHighlight must remain false")
    }

    // Test: text factory builds Mark.Text with correct fields (D9)
    "text factory builds Mark.Text with label and anchor (D9)" in {
        val m = text[Sale, String, Usd](x = _.month, y = _.revenue, label = _.month, anchor = TextAnchor.End)
        m match
            case Mark.Text(_, _, lbl, color, anchor, _, Axis.Left) =>
                assert(color == Absent, "color must be Absent when not supplied")
                assert(anchor == TextAnchor.End, "anchor must be TextAnchor.End")
                assert(lbl(sales(0)) == "Jan", "label accessor must return _.month")
            case other =>
                fail(s"Expected Mark.Text but got $other")
        end match
    }

    // Test: errorBar factory builds Mark.ErrorBar with correct fields (D10)
    "errorBar factory builds Mark.ErrorBar with correct encodings (D10)" in {
        case class Eb(x: String, mean: Usd, lo: Usd, hi: Usd)
        val eb = Eb("a", Usd(6.0), Usd(4.0), Usd(8.0))
        val m  = errorBar[Eb, String, Usd](x = _.x, y = _.mean, low = _.lo, high = _.hi, capWidth = 10.0)
        m match
            case Mark.ErrorBar(xCh, yCh, lowCh, highCh, color, cap, Axis.Left) =>
                assert(cap == 10.0, s"capWidth must be 10.0, got $cap")
                assert(color == Absent, "color must be Absent")
                // Encoding accessors return the erased type Y; cast safe because Usd <: Double.
                assert(yCh.accessor(eb).asInstanceOf[Double] == 6.0, s"y accessor must return 6.0")
                assert(lowCh.accessor(eb).asInstanceOf[Double] == 4.0, s"low accessor must return 4.0")
                assert(highCh.accessor(eb).asInstanceOf[Double] == 8.0, s"high accessor must return 8.0")
            case other =>
                fail(s"Expected Mark.ErrorBar but got $other")
        end match
    }

    private def assertClose(actual: Double, expected: Double, msg: String)(using Frame, kyo.test.AssertScope): Unit =
        assert(math.abs(actual - expected) < 1e-9, s"$msg: expected $expected but got $actual")

    // ---- Phase 6: a11y, responsive, margins ----

    private def rootOf[A](spec: Chart.Spec[A]): Svg.Root =
        summon[Conversion[Chart.Spec[A], Svg.Root]](spec)

    private def titleTextsIn(root: Svg.Root): Chunk[String] =
        root.children.flatMap:
            case t: Svg.Title => Chunk(t.text)
            case _            => Chunk.empty

    private def descTextsIn(root: Svg.Root): Chunk[String] =
        root.children.flatMap:
            case d: Svg.Desc => Chunk(d.text)
            case _           => Chunk.empty

    // Leaf 14
    "title(t) adds an Svg.Title child carrying the title text" in {
        val root = rootOf(Chart(sales)(bar(x = _.month, y = _.revenue)).title("Revenue by month"))
        assert(titleTextsIn(root).toSeq.contains("Revenue by month"), "Expected a <title> child with the text")
    }

    // Leaf 15
    "title(t) sets role=img on the root svg" in {
        val root = rootOf(Chart(sales)(bar(x = _.month, y = _.revenue)).title("T"))
        assert(root.attrs.role == Present("img"), s"Expected role=img but got ${root.attrs.role}")
    }

    // Leaf 16
    "desc(d) adds an Svg.Desc child carrying the description text" in {
        val root = rootOf(Chart(sales)(bar(x = _.month, y = _.revenue)).desc("Monthly totals"))
        assert(descTextsIn(root).toSeq.contains("Monthly totals"), "Expected a <desc> child with the text")
    }

    // Leaf 17
    "ariaLabel(l) sets aria-label on the root svg" in {
        val root = rootOf(Chart(sales)(bar(x = _.month, y = _.revenue)).ariaLabel("chart"))
        assert(root.attrs.ariaAttrs.get("label") == Some("chart"), s"Expected aria-label=chart but got ${root.attrs.ariaAttrs}")
    }

    // Leaf 18
    "no a11y configured -> no title, no desc, no role, no aria-label" in {
        val root = rootOf(Chart(sales)(bar(x = _.month, y = _.revenue)))
        assert(titleTextsIn(root).isEmpty, "Expected no <title>")
        assert(descTextsIn(root).isEmpty, "Expected no <desc>")
        assert(root.attrs.role == Absent, "Expected no role")
        assert(!root.attrs.ariaAttrs.contains("label"), "Expected no aria-label")
    }

    // Leaf 19
    "responsive uses width=100% and a viewBox with no fixed pixel height" in {
        val root = rootOf(Chart(sales)(bar(x = _.month, y = _.revenue)).size(400, 300).responsive)
        assert(
            root.svgAttrs.width == Present(Svg.Coord.Len(Svg.SvgLength.Pct(100.0))),
            s"Expected width=100% but got ${root.svgAttrs.width}"
        )
        assert(root.svgAttrs.height == Absent, s"Expected no fixed height but got ${root.svgAttrs.height}")
        assert(root.svgAttrs.viewBox.isDefined, "Expected a viewBox")
    }

    // Leaf 20
    "responsive(ratio) keeps width=100% and a viewBox and sets preserveAspectRatio" in {
        val root = rootOf(Chart(sales)(bar(x = _.month, y = _.revenue)).responsive(16.0 / 9.0))
        assert(root.svgAttrs.width == Present(Svg.Coord.Len(Svg.SvgLength.Pct(100.0))), "Expected width=100%")
        assert(root.svgAttrs.viewBox.isDefined, "Expected a viewBox")
        assert(root.svgAttrs.preserveAspectRatio.isDefined, "Expected preserveAspectRatio for an explicit ratio")
    }

    // Leaf 21
    "responsive and size are mutually exclusive with last-set-wins" in {
        // size after responsive -> fixed wins.
        val sizeWins = rootOf(Chart(sales)(bar(x = _.month, y = _.revenue)).responsive.size(400, 300))
        assert(
            sizeWins.svgAttrs.width == Present(Svg.Coord.Num(400.0)),
            s"size-after-responsive must keep fixed width, got ${sizeWins.svgAttrs.width}"
        )
        assert(sizeWins.svgAttrs.height == Present(Svg.Coord.Num(300.0)), "size-after-responsive must keep fixed height")
        // responsive after size -> responsive wins.
        val respWins = rootOf(Chart(sales)(bar(x = _.month, y = _.revenue)).size(400, 300).responsive)
        assert(respWins.svgAttrs.width == Present(Svg.Coord.Len(Svg.SvgLength.Pct(100.0))), "responsive-after-size must use width=100%")
        assert(respWins.svgAttrs.height == Absent, "responsive-after-size must have no fixed height")
    }

    // Leaf (margins)
    "margins(...) shifts the plot rectangle by the configured left/top margins" in {
        val base     = Chart(sales)(bar(x = _.month, y = _.revenue))
        val (_, sc0) = base.lowerWithScales
        val (_, sc1) = base.margins(_.left(120.0)).lowerWithScales
        // Increasing the left margin moves plot.x right by the delta from the default (60).
        assertClose(sc1.plot.x - sc0.plot.x, 60.0, "left margin delta shifts plot.x")
    }

end ChartSpecTest
