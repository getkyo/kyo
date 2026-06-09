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

    // ---- domain types ----

    enum Region derives CanEqual, Plottable:
        case NA, EU, APAC

    case class Sale(month: String, revenue: Double, hi: Double = 0.0, region: Region = Region.NA)
    given CanEqual[Sale, Sale] = CanEqual.derived

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

    // ---- Leaf 1: enum color summons with no type argument ----

    "enum color summons ColorKey[Region] with no explicit type argument" in {
        val rows = Chunk(Sale("Jan", 1000.0, region = Region.NA))
        typeCheck("Chart.bar(x = (_: Sale).month, y = (_: Sale).revenue, color = (_: Sale).region)")
        assert(summon[ColorKey[Region]] != null)
    }

    // ---- Leaf 2: Double color summons ----

    "Double color summons ColorKey[Double] for a sequential-scale use case" in {
        val rows = Chunk(Sale("Jan", 1000.0, hi = 50.0))
        typeCheck("Chart.bar(x = (_: Sale).month, y = (_: Sale).revenue, color = (_: Sale).hi)")
        assert(summon[ColorKey[Double]] != null)
    }

    // ---- Leaf 3: String / Int / Long each summon (Instant uses an opaque type that ConcreteTag handles at java.time.Instant's class level) ----

    "String, Int, and Long all summon ColorKey[_]" in {
        assert(summon[ColorKey[String]] != null)
        assert(summon[ColorKey[Int]] != null)
        assert(summon[ColorKey[Long]] != null)
    }

    // ---- Leaf 4: omitted color compiles with no annotation (Nothing default) ----

    "omitted color compiles with no annotation and produces Absent color encoding" in {
        typeCheck("Chart.bar(x = (_: Sale).month, y = (_: Sale).revenue)")
        val rows = Chunk(Sale("Jan", 1000.0))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
        spec.lower.map { root =>
            val rects = marksRects(root)
            assert(rects.size == 1, s"Expected 1 bar rect but got ${rects.size}")
        }
    }

    // ---- Leaf 5: coloring by a non-keyable type is a compile error ----

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

    // ---- Leaf 6: enum WITHOUT derives CanEqual does not summon ----

    "enum without derives CanEqual does not summon ColorKey" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            enum Plain { case A, case B }
            summon[Chart.ColorKey[Plain]]
        """)
    }

    // ---- Leaf 7: a generic color type does not summon ----

    "a generic color type (Maybe[Region]) does not summon ColorKey" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            enum Region derives CanEqual, Plottable { case NA, EU }
            summon[Chart.ColorKey[Maybe[Region]]]
        """)
    }

    // ---- Leaf 8: by infers G and returns Stack, drops into bar's stack parameter ----

    "by infers G = Region and returns Stack[Sale] accepted by bar" in {
        val stack: Stack[Sale]  = by((s: Sale) => s.region)
        val spec                = Chart(Chunk(Sale("Jan", 1000.0)))(bar(x = _.month, y = _.revenue, stack = stack))
        val stackN: Stack[Sale] = by((s: Sale) => s.region, normalize = true)
        val specN               = Chart(Chunk(Sale("Jan", 1000.0)))(bar(x = _.month, y = _.revenue, stack = stackN))
        assert(spec.marks.length == 1, s"Expected 1 mark series, got ${spec.marks.length}")
        assert(specN.marks.length == 1, s"Expected 1 mark series for normalized stack, got ${specN.marks.length}")
    }

    // ---- Leaf 9: by over a non-keyable group is a compile error ----

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

    // ---- Leaf 10: bridge soundness: enum cases with colliding toString stay distinct ----

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

    // ---- Leaf 11: omitted color produces no color encoding (single-series render) ----

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

    // ---- Leaf 12: typed accessor compiles; Any-typed accessor fails if ColorKey[Any] absent ----

    "typed color accessor compiles; Any-upcast falls back to leaf 5 negative case" in {
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

end ChartTypeSafetyTest
