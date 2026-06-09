package chartsurface

import kyo.*
import kyo.Chart.Plottable

/** Proves the public chart surface from a real user's vantage point.
  *
  * This suite lives outside package `kyo`, so any member marked `private[kyo]` is genuinely inaccessible.
  * It confirms that the internal representation of a `Plottable` (its scale `kind`, its `toDomain`
  * projection, and the internal `any` fallback) never leaks into user code, while the public construction
  * and labeling surface remains fully usable.
  */
class ChartSurfaceTest extends kyo.test.Test[Any]:

    // The internal scale family of a Plottable is not reachable from user code.

    "Plottable.kind is not accessible outside kyo" in {
        typeCheckFailure("summon[kyo.Chart.Plottable[Int]].kind")("cannot be accessed")
    }

    // The internal domain projection of a Plottable is not reachable from user code.

    "Plottable.toDomain is not accessible outside kyo" in {
        typeCheckFailure("summon[kyo.Chart.Plottable[Int]].toDomain(5)")("cannot be accessed")
    }

    // The internal Any fallback instance is not reachable from user code.

    "Plottable.any is not accessible outside kyo" in {
        typeCheckFailure("kyo.Chart.Plottable.any")("cannot be accessed")
    }

    // The public factories build Plottable instances from plain function types alone.

    "Plottable.continuous compiles and builds a working instance" in {
        typeCheck("""kyo.Chart.Plottable.continuous[Double](d => d, d => d.toString)""")
        val p = Plottable.continuous[Double](d => d, d => f"$d%.1f")
        assert(p.label(2.0) == "2.0", s"continuous label expected \"2.0\" but got \"${p.label(2.0)}\"")
    }

    "Plottable.categorical compiles and builds a working instance" in {
        typeCheck("""kyo.Chart.Plottable.categorical[String](s => s)""")
        val p = Plottable.categorical[String](identity)
        assert(p.label("x") == "x", s"categorical label expected \"x\" but got \"${p.label("x")}\"")
    }

    "Plottable.temporal compiles and builds a working instance" in {
        typeCheck("""kyo.Chart.Plottable.temporal[Long](l => l, l => l.toString)""")
        val p = Plottable.temporal[Long](l => l, l => s"t=$l")
        assert(p.label(7L) == "t=7", s"temporal label expected \"t=7\" but got \"${p.label(7L)}\"")
    }

    // The public label method is callable on any Plottable from user code.

    "Plottable.label is accessible outside kyo" in {
        typeCheck("summon[kyo.Chart.Plottable[Int]].label(3)")
        assert(summon[Plottable[Int]].label(3) == "3", "Plottable[Int].label(3) must return \"3\"")
    }

    // The Mark case classes are not reachable from user code; users hold and pass Mark[A] values
    // through the public sealed trait but cannot name or pattern-match the concrete cases.

    "Mark.Bar is not accessible outside kyo" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            enum Region derives CanEqual, Plottable { case NA }
            case class Sale(month: String, revenue: Double, region: Region)
            given CanEqual[Sale, Sale] = CanEqual.derived
            val m: Chart.Mark[Sale] = Chart.bar(
                x = (_: Sale).month,
                y = (_: Sale).revenue,
                color = (_: Sale).region
            )
            m match { case Chart.Mark.Bar(x, y, c, _, _, _, _, _) => x }
        """)("cannot be accessed")
    }

    "Mark.Line is not accessible outside kyo" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            val m: Chart.Mark[String] =
                Chart.line(x = (s: String) => s, y = (s: String) => s.length.toDouble)
            m match { case Chart.Mark.Line(x, y, _, _, _, _, _, _, _) => x }
        """)("cannot be accessed")
    }

    "Mark.Area is not accessible outside kyo" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            val m: Chart.Mark[String] =
                Chart.area(x = (s: String) => s, y = (s: String) => s.length.toDouble)
            m match { case Chart.Mark.Area(_, _, _, _, _, _, _, _, _, _, _) => () }
        """)("cannot be accessed")
    }

    "Mark.Rule is not accessible outside kyo" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            val m: Chart.Mark[String] = Chart.rule(y = 5.0)
            m match { case Chart.Mark.Rule(x, y, _) => x }
        """)("cannot be accessed")
    }

    // The carrier classes that back mark field storage are not reachable from user code.

    "Chart.Encoding is not accessible outside kyo" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            def f(e: Chart.Encoding[String, Int]) = e
        """)("cannot be accessed")
    }

    "Chart.EncodingMaybe is not accessible outside kyo" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            def f(e: Chart.EncodingMaybe[String, Int]) = e
        """)("cannot be accessed")
    }

    "Chart.Grouping is not accessible outside kyo" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            def f(g: Chart.Grouping[String]) = g
        """)("cannot be accessed")
    }

    // The Any-bearing Categorical color scale case is not reachable from user code;
    // Sequential (with fully typed public parameters) remains accessible.

    "ColorScale.Categorical is not accessible outside kyo" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            Chart.LegendConfig.ColorScale.Categorical((_: Any) => Style.Color.blue)
        """)("cannot be accessed")
    }

    "ColorScale.Sequential is still accessible from user code" in {
        typeCheck(
            "import kyo.*; import kyo.Chart.*; " +
                "Chart.LegendConfig.ColorScale.Sequential(Style.Color.blue, Style.Color.red, Absent)"
        )
    }

    // Batch acceptance: all ex-public carriers and the Any-bearing enum case are inaccessible
    // together, confirming no user-visible Any or existential ? remains on the chart surface.

    "Mark, Encoding, EncodingMaybe, Grouping, and ColorScale.Categorical are all inaccessible" in {
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            val _1: Chart.Encoding[String, Int]      = ???
            val _2: Chart.EncodingMaybe[String, Int] = ???
            val _3: Chart.Grouping[String]           = ???
        """)("cannot be accessed")
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            Chart.LegendConfig.ColorScale.Categorical((_: Any) => Style.Color.blue)
        """)("cannot be accessed")
        typeCheckFailure("""
            import kyo.*
            import kyo.Chart.*
            val m: Chart.Mark[String] = Chart.rule(y = 5.0)
            m match { case Chart.Mark.Rule(x, y, _) => x }
        """)("cannot be accessed")
    }

end ChartSurfaceTest
