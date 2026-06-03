package kyo

import kyo.Chart.*
import scala.language.implicitConversions

class ChartSpecTest extends Test:

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

    // ---- inference: bar with color builds ChartSpec[Sale] ----

    "bar with color infers ChartSpec[Sale] without annotations" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue, color = _.region))
        assert(spec.marks.length == 1)
        spec.marks.head match
            case Mark.Bar(_, _, color, _, _) =>
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
            case Mark.Bar(_, _, color, _, _) =>
                assert(color == Absent)
            case other =>
                fail(s"Expected Mark.Bar but got $other")
        end match

        specColor.marks.head match
            case Mark.Bar(_, _, color, _, _) =>
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

    "yAxis(_.left.grid.ticks(5)) sets side Left, grid true, tickCount 5" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue)).yAxis(_.left.grid.ticks(5))
        spec.yAxisCfg.side match
            case Present(Side.Left) => succeed
            case other              => fail(s"Expected Present(Side.Left) but got $other")
        assert(spec.yAxisCfg.showGrid == true)
        assert(spec.yAxisCfg.tickCount == 5)
    }

    "key(_.month) sets spec.key to Present" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue)).key(_.month)
        assert(spec.key.isDefined)
    }

    "onHover(ref) stores the ref in spec.onHover" in {
        run {
            Signal.initRef(Maybe.empty[Sale]).map: ref =>
                val spec = Chart(sales)(bar(x = _.month, y = _.revenue)).onHover(ref)
                spec.onHover match
                    case Present(storedRef) => assert(storedRef eq ref)
                    case Absent             => fail("Expected onHover to be Present")
        }
    }

    // ---- two overloads: Chunk and Signal ----

    "Chart(chunk)(...) produces DataSource.Static" in {
        val spec = Chart(sales)(bar(x = _.month, y = _.revenue))
        spec.data match
            case DataSource.Static(_) => succeed
            case DataSource.Live(_)   => fail("Expected Static but got Live")
    }

    "Chart(signal)(...) produces DataSource.Live" in {
        run {
            Signal.initRef(sales).map: sig =>
                val spec = Chart(sig)(bar(x = _.month, y = _.revenue))
                spec.data match
                    case DataSource.Live(_)   => succeed
                    case DataSource.Static(_) => fail("Expected Live but got Static")
        }
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
            import kyo.Chart.*
            enum Region2 derives CanEqual, Plottable:
                case NA, EU, APAC
            case class Sale2(month: String, revenue: Double, region: Region2)
            val sales: Chunk[Sale2] = Chunk.empty
            val spec = Chart(sales)(bar(x = _.month, y = _.revenue, color = _.region))
            val _: ChartSpec[Sale2] = spec
        """)
    }

    "positive typeCheck: Chart(chunk) and Chart(signal) both infer" in {
        typeCheck("""
            import kyo.*
            import kyo.Chart.*
            case class Row(x: String, y: Int)
            given CanEqual[Row, Row] = CanEqual.derived
            val chunk: Chunk[Row] = Chunk.empty
            val specStatic: ChartSpec[Row] = Chart(chunk)(bar(x = _.x, y = _.y))
        """)
    }

    // ---- compile gates: shouldNot typeCheck ----

    "rule(color = ...) does not compile" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            case class Row(x: String, y: Int, c: String)
            val rows: Chunk[Row] = Chunk.empty
            Chart(rows)(rule(color = _.c))
        """)("does not have a parameter color")
    }

    "bar(size = ...) does not compile" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            case class Row(x: String, y: Int, s: Double)
            val rows: Chunk[Row] = Chunk.empty
            Chart(rows)(bar(x = _.x, y = _.y, size = _.s))
        """)("does not have a parameter size")
    }

    "chained bar(...).color(...) does not compile" in {
        typeCheckFailure("""
            import kyo.*
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
            case Mark.Line(_, _, color, curve, defined, _) =>
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
            case Mark.Point(_, _, _, size, symbol, _) =>
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
            case Mark.Line(_, _, _, _, _, axis) =>
                assert(axis == Axis.Right)
            case other => fail(s"Expected Mark.Line but got $other")
        end match
        succeed
    }

    // ---- ChartSpec converts to Svg.Root ----

    "ChartSpec[Sale] converts to Svg.Root via given Conversion" in {
        val spec: ChartSpec[Sale] = Chart(sales)(bar(x = _.month, y = _.revenue))
        val root: Svg.Root        = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        succeed
    }

end ChartSpecTest
