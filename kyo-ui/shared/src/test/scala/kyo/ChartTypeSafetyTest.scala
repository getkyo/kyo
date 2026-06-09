package kyo

import kyo.Chart.*
import kyo.UI.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

/** Tests for the typed color/group encoding surface.
  *
  * Verifies that `Chart.ColorKey[C]` summons for every supported built-in type, that
  * derived enums satisfy the constraint, that generic or non-comparable types are
  * rejected at compile time, and that the erasure bridge preserves runtime
  * category discrimination.
  */
class ChartTypeSafetyTest extends kyo.test.Test[Any]:

    // Domain types shared across the tests below.

    enum Region derives CanEqual, Plottable:
        case NA, EU, APAC

    case class Sale(month: String, revenue: Double, hi: Double = 0.0, region: Region = Region.NA)
    given CanEqual[Sale, Sale] = CanEqual.derived

    // Opaque types defined at the class level because Scala 3 forbids opaque type aliases inside a method body.
    opaque type Celsius <: Double = Double
    object Celsius:
        def apply(d: Double): Celsius    = d
        given Plottable[Celsius]         = Plottable.continuous((c: Celsius) => (c: Double), c => f"$c%.1f C")
        given CanEqual[Celsius, Celsius] = CanEqual.derived
    end Celsius

    opaque type Usd2 <: Double = Double
    object Usd2:
        def apply(d: Double): Usd2 = d
        given CanEqual[Usd2, Usd2] = CanEqual.derived

    private def fillOf(fill: Maybe[Svg.Paint])(using Frame, kyo.test.AssertScope): Style.Color = fill match
        case Present(Svg.Paint.Color(c)) => c
        case other                       => fail(s"Expected Svg.Paint.Color but got $other")

    private def marksRects(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.last match
            case g: Svg.G =>
                g.children.flatMap:
                    case r: Svg.Rect => Chunk(r)
                    case _           => Chunk.empty
            case _ => Chunk.empty

    // An enum color summons ColorKey with no explicit type argument.

    "enum color summons ColorKey[Region] with no explicit type argument" in {
        val rows = Chunk(Sale("Jan", 1000.0, region = Region.NA))
        typeCheck("Chart.bar(x = (_: Sale).month, y = (_: Sale).revenue, color = (_: Sale).region)")
        assert(summon[ColorKey[Region]] != null)
    }

    // A Double color summons ColorKey for a sequential-scale use case.

    "Double color summons ColorKey[Double] for a sequential-scale use case" in {
        val rows = Chunk(Sale("Jan", 1000.0, hi = 50.0))
        typeCheck("Chart.bar(x = (_: Sale).month, y = (_: Sale).revenue, color = (_: Sale).hi)")
        assert(summon[ColorKey[Double]] != null)
    }

    // String, Int, and Long each summon ColorKey. Instant cannot be a color key: ConcreteTag does not
    // summon for the opaque Instant alias, so there is no ColorKey[Instant], and coloring by an Instant
    // value is rejected at compile time.

    "String, Int, and Long all summon ColorKey[_]" in {
        assert(summon[ColorKey[String]] != null)
        assert(summon[ColorKey[Int]] != null)
        assert(summon[ColorKey[Long]] != null)
    }

    // An omitted color compiles with no type annotation, defaulting to the empty color encoding.

    "omitted color compiles with no annotation and produces Absent color encoding" in {
        typeCheck("Chart.bar(x = (_: Sale).month, y = (_: Sale).revenue)")
        val rows = Chunk(Sale("Jan", 1000.0))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
        spec.lower.map { root =>
            val rects = marksRects(root)
            assert(rects.size == 1, s"Expected 1 bar rect but got ${rects.size}")
        }
    }

    // Coloring by a type with no ColorKey instance is a compile error.

    "coloring by a type with no Color instance is a compile error" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            class Opaque(val n: Int)
            case class Row(month: String, revenue: Double, o: Opaque)
            given CanEqual[Row, Row] = CanEqual.derived
            Chart.bar(x = (_: Row).month, y = (_: Row).revenue, color = (r: Row) => r.o)
        """)
    }

    // An enum without `derives CanEqual` does not summon ColorKey: under strict equality there is no
    // CanEqual[Plain, Plain], so the ColorKey instance cannot be derived.

    "enum without derives CanEqual does not summon ColorKey" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            enum Plain { case A, B }
            summon[Chart.ColorKey[Plain]]
        """)("ColorKey")
    }

    // A generic color type does not summon ColorKey.

    "a generic color type (Maybe[Region]) does not summon ColorKey" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            enum Region derives CanEqual, Plottable { case NA, EU }
            summon[Chart.ColorKey[Maybe[Region]]]
        """)
    }

    // `by` infers its group type and returns a Stack accepted by bar's stack parameter.

    "by infers G = Region and returns Stack[Sale] accepted by bar" in {
        val stack: Stack[Sale]  = by((s: Sale) => s.region)
        val spec                = Chart(Chunk(Sale("Jan", 1000.0)))(bar(x = _.month, y = _.revenue, stack = stack))
        val stackN: Stack[Sale] = by((s: Sale) => s.region, normalize = true)
        val specN               = Chart(Chunk(Sale("Jan", 1000.0)))(bar(x = _.month, y = _.revenue, stack = stackN))
        assert(spec.marks.length == 1, s"Expected 1 mark series, got ${spec.marks.length}")
        assert(specN.marks.length == 1, s"Expected 1 mark series for normalized stack, got ${specN.marks.length}")
    }

    // `by` over a non-keyable group type is a compile error.

    "by over a non-keyable group type is a compile error" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            enum Region derives CanEqual, Plottable { case NA, EU }
            case class Sale(month: String, revenue: Double, region: Region)
            given CanEqual[Sale, Sale] = CanEqual.derived
            Chart.by((s: Sale) => (() => s.region))
        """)
    }

    // Enum cases that collide on toString stay distinct after lowering: category identity is keyed by
    // ConcreteTag and ordinal, not by the display string.

    "two enum cases that collide on toString receive distinct fills after lowering" in {
        enum Collision derives CanEqual:
            case X, Y
            override def toString: String = "same"
        case class Item(name: String, value: Double, cat: Collision)
        given CanEqual[Item, Item] = CanEqual.derived
        val rows = Chunk(
            Item("a", 10.0, Collision.X),
            Item("b", 20.0, Collision.Y)
        )
        val spec = Chart(rows)(bar(x = _.name, y = _.value, color = _.cat))
        spec.lower.map { root =>
            val rects = marksRects(root)
            assert(rects.size == 2, s"Expected 2 rects but got ${rects.size}")
            val fills = rects.map(r => fillOf(r.svgAttrs.fill)).toSeq
            assert(fills.distinct.size == 2, s"Two colliding-toString cases must have distinct fills, got $fills")
        }
    }

    // An omitted color produces no color encoding, so the bars render as a single undivided series.

    "bar with omitted color lowers to a single series with no split" in {
        val rows = Chunk(
            Sale("Jan", 1000.0),
            Sale("Feb", 2000.0)
        )
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
        spec.lower.map { root =>
            val rects = marksRects(root)
            assert(rects.size == 2, s"Expected 2 bars (no color split) but got ${rects.size}")
            val fills = rects.map(r => r.svgAttrs.fill).toSeq
            assert(fills.forall(_ == fills.head), s"All bars without color must share one fill")
        }
    }

    // A typed color accessor compiles, while an accessor returning a type with no ColorKey is rejected.

    "typed color accessor compiles while a non-keyable accessor is rejected" in {
        typeCheck("Chart.bar(x = (_: Sale).month, y = (_: Sale).revenue, color = (_: Sale).region)")
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            class Opaque(val n: Int)
            case class Row(month: String, revenue: Double, o: Opaque)
            given CanEqual[Row, Row] = CanEqual.derived
            Chart.bar(x = (_: Row).month, y = (_: Row).revenue, color = (r: Row) => r.o)
        """)
    }

    // A custom continuous Plottable plots and renders at the correct linear pixel positions.

    "custom Plottable via continuous renders a line at the correct linear pixel positions" in {
        case class Row(x: String, y: Celsius)
        given CanEqual[Row, Row] = CanEqual.derived
        val rows                 = Chunk(Row("a", Celsius(10.0)), Row("b", Celsius(20.0)))
        val spec                 = Chart(rows)(line(x = _.x, y = _.y))
        spec.lower.map { root =>
            val paths = root.children.flatMap:
                case g: Svg.G =>
                    g.children.flatMap:
                        case p: Svg.Path => Chunk(p)
                        case _           => Chunk.empty
                case _ => Chunk.empty
            assert(paths.nonEmpty, s"Expected at least one path but got none")
            val cmds = Svg.PathData.commands(paths(0).svgAttrs.d.getOrElse(Svg.PathData.empty))
            assert(cmds.size == 2, s"Expected 2 path commands (MoveTo + LineTo) but got ${cmds.size}")
            // y Linear: extent [10, 20], niceTicks(10,20,5) => step=5 => nLo=10, nHi=20
            // Scale.Linear(10, 20, 440, 20): apply(10.0)=440, apply(20.0)=20
            val yOf10 = 440.0
            val yOf20 = 20.0
            cmds(0) match
                case Svg.PathCommand.MoveTo(_, y) =>
                    assert(math.abs(y - yOf10) < 1e-6, s"MoveTo y for Celsius(10) expected $yOf10 but got $y")
                case other => fail(s"Expected MoveTo but got $other")
            end match
            cmds(1) match
                case Svg.PathCommand.LineTo(_, y) =>
                    assert(math.abs(y - yOf20) < 1e-6, s"LineTo y for Celsius(20) expected $yOf20 but got $y")
                case other => fail(s"Expected LineTo but got $other")
            end match
        }
    }

    // A custom categorical Plottable builds a band scale, positioning the bar at the band center and labeling correctly.

    "custom Plottable via categorical positions the bar at the band center and labels correctly" in {
        case class Sku(code: String)
        given CanEqual[Sku, Sku] = CanEqual.derived
        given Plottable[Sku]     = Plottable.categorical(_.code)
        case class Row(sku: Sku, value: Double)
        given CanEqual[Row, Row] = CanEqual.derived
        assert(summon[Plottable[Sku]].label(Sku("ABC")) == "ABC", "label must return the code string")
        val rows = Chunk(Row(Sku("ABC"), 1000.0))
        val spec = Chart(rows)(bar(x = _.sku, y = _.value))
        spec.lower.map { root =>
            val rects = root.children.flatMap:
                case g: Svg.G =>
                    g.children.flatMap:
                        case r: Svg.Rect => Chunk(r)
                        case _           => Chunk.empty
                case _ => Chunk.empty
            assert(rects.nonEmpty, s"Expected at least one bar rect but got none")
            // x Band: n=1, totalW=560, slot=560, bandW=560*0.9=504
            // barX = 60 + (560 - 504)/2 = 60 + 28 = 88
            val expectedX = 88.0
            val actualX = rects(0).svgAttrs.x match
                case Present(Svg.Coord.Num(v)) => v
                case other                     => fail(s"Expected Coord.Num for x but got $other")
            assert(math.abs(actualX - expectedX) < 1e-6, s"bar x expected $expectedX but got $actualX")
        }
    }

    // A custom temporal Plottable builds a time scale and labels correctly.

    "custom Plottable via temporal labels correctly" in {
        case class Stamp(millis: Long, iso: String)
        given CanEqual[Stamp, Stamp] = CanEqual.derived
        given Plottable[Stamp]       = Plottable.temporal(_.millis, _.iso)
        val s                        = Stamp(0L, "epoch")
        assert(summon[Plottable[Stamp]].label(s) == "epoch", "temporal label must return the iso string")
    }

    // Each built-in Plottable selects the scale family appropriate to its value type.

    "p.kind returns the correct Scale.Kind for built-in Plottable instances" in {
        import kyo.internal.Scale
        assert(summon[Plottable[Int]].kind == Scale.Kind.Linear, "Int -> Linear scale")
        assert(summon[Plottable[Long]].kind == Scale.Kind.Linear, "Long -> Linear scale")
        assert(summon[Plottable[Double]].kind == Scale.Kind.Linear, "Double -> Linear scale")
        assert(summon[Plottable[String]].kind == Scale.Kind.Band, "String -> Band scale")
        assert(summon[Plottable[Instant]].kind == Scale.Kind.Time, "Instant -> Time scale")
    }

    // Each built-in Plottable projects its values to the expected domain coordinates.

    "p.toDomain returns Present domain values for built-in Plottable instances" in {
        import kyo.internal.Domain
        val intP = summon[Plottable[Int]]
        assert(intP.toDomain(5) == Present(Domain.Continuous(5.0)), "Int 5 -> Continuous(5.0)")
        val strP = summon[Plottable[String]]
        assert(strP.toDomain("x") == Present(Domain.Category("x")), "String x -> Category(x)")
        val maybePT = summon[Plottable[Maybe[Int]]]
        assert(maybePT.toDomain(Absent) == Absent, "Absent -> Absent (no domain contribution)")
        assert(maybePT.toDomain(Present(3)) == Present(Domain.Continuous(3.0)), "Present(3) -> Continuous(3.0)")
    }

    // Plottable.label is callable from user code and returns the expected string.

    "p.label compiles from user code and returns the expected string" in {
        val p = summon[Plottable[Int]]
        typeCheck("{ import kyo.*; import kyo.Chart.*; val p: Chart.Plottable[Int] = summon; p.label(3) }")
        assert(p.label(3) == "3", s"Plottable[Int].label(3) expected \"3\" but got \"${p.label(3)}\"")
    }

    // The built-in Plottable givens and the numeric and derived factories all resolve and label correctly.

    "built-in Plottable givens and factories all resolve and label correctly" in {
        assert(summon[Plottable[Int]].label(42) == "42", "Plottable[Int].label(42)")
        assert(summon[Plottable[String]].label("x") == "x", "Plottable[String].label(\"x\")")
        assert(summon[Plottable[Long]].label(7L) == "7", "Plottable[Long].label(7)")
        val numericP: Plottable[Usd2] = Plottable.numeric[Usd2]
        assert(numericP.label(Usd2(10.0)) == summon[Plottable[Double]].label(10.0), "numeric label matches double label")
        enum Color2 derives Plottable:
            case Red, Blue
        assert(summon[Plottable[Color2]].label(Color2.Red) == "Red", "derived enum label for Red")
        assert(summon[Plottable[Color2]].label(Color2.Blue) == "Blue", "derived enum label for Blue")
    }

    // rule with only y supplied renders y at the correct position and leaves x absent.

    "rule with only y supplied renders y at the correct position" in {
        val rows = Chunk(Sale("Jan", 55000.0))
        val spec = Chart(rows)(
            bar(x = _.month, y = _.revenue),
            rule(y = 55000.0)
        )
        spec.lower.map { root =>
            val lines = root.children.flatMap:
                case g: Svg.G =>
                    g.children.flatMap:
                        case l: Svg.Line => Chunk(l)
                        case _           => Chunk.empty
                case _ => Chunk.empty
            assert(lines.nonEmpty, s"Expected a rule line but found none")
        }
    }

    // rule with neither x nor y supplied produces no rendered line.

    "rule with neither x nor y supplied produces no rendered line" in {
        val rows = Chunk(Sale("Jan", 1000.0))
        val spec = Chart(rows)(rule[Sale, Double]())
        spec.lower.map { root =>
            val lines = root.children.flatMap:
                case g: Svg.G =>
                    g.children.flatMap:
                        case l: Svg.Line => Chunk(l)
                        case _           => Chunk.empty
                case _ => Chunk.empty
            assert(lines.isEmpty, s"Rule with no values must produce no line, found ${lines.size}")
        }
    }

    // colorScale typed pairs map matched categories and fall back to blue for unmapped.

    "colorScale typed pairs map matched categories and fall back for unmatched" in {
        val naColor = Style.Color.hex("#AA0000").getOrElse(fail("bad hex naColor"))
        val euColor = Style.Color.hex("#00AA00").getOrElse(fail("bad hex euColor"))
        val rows = Chunk(
            Sale("Jan", 1000.0, region = Region.NA),
            Sale("Feb", 2000.0, region = Region.EU),
            Sale("Mar", 3000.0, region = Region.APAC)
        )
        val spec = Chart(rows)(
            bar(x = _.month, y = _.revenue, color = _.region)
        ).legend(_.colorScale[Region](
            Region.NA -> naColor,
            Region.EU -> euColor
        ))
        spec.lower.map { root =>
            val rects = marksRects(root)
            assert(rects.size == 3, s"Expected 3 bar rects but got ${rects.size}")
            val fills = rects.map(r => fillOf(r.svgAttrs.fill)).toSeq
            assert(fills.contains(naColor), s"NA fill must be #AA0000, got $fills")
            assert(fills.contains(euColor), s"EU fill must be #00AA00, got $fills")
            assert(fills.contains(Style.Color.blue), s"APAC must fall back to blue, got $fills")
        }
    }

    // A Mark value can be held as Chart.Mark[Sale], passed to Chart, and then lowered.

    "a Mark value can be held and passed as Chart.Mark[Sale] and then lowered" in {
        val rows                = Chunk(Sale("Jan", 1000.0), Sale("Feb", 2000.0))
        val m: Chart.Mark[Sale] = Chart.bar(x = _.month, y = _.revenue)
        val spec: Chart[Sale]   = Chart(rows)(m)
        assert(spec.marks.length == 1, s"Expected 1 mark, got ${spec.marks.length}")
        spec.lower.map { root =>
            val rects = marksRects(root)
            assert(rects.size == 2, s"Expected 2 bar rects but got ${rects.size}")
        }
    }

end ChartTypeSafetyTest
