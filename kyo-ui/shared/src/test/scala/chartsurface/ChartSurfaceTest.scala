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

end ChartSurfaceTest
