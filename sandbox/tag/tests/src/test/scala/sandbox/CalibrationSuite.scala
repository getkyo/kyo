package sandbox

import kyo.Tag
import scala.compiletime.testing.typeCheckErrors

/** The harness must detect known bugs before it is trusted. Expected results are recorded per
  * macro version in scripts/calibrate.sh.
  */
class CalibrationSuite extends munit.FunSuite:

    test("S2: an inferred Metres inside the two-brand scope must not get Feet's tag") {
        val feet   = encoding(Tag[TwoBrands.Feet])
        val metres = encoding(TwoBrands.metresInferred)
        assert(feet != metres, s"both are $feet")
    }

    test("S6: parameterized opaque types keep their arguments") {
        assert(encoding(Tag[Opt.Opt[Int]]) != encoding(Tag[Opt.Opt[String]]))
    }

    test("Tag.derive inside equals Tag outside") {
        assertEquals(encoding(Time.Duration.derived), encoding(Tag[Time.Duration]))
    }

    test("report: what the scope does to each provenance") {
        println("Tag[Duration] in scope:          " + Time.Duration.applyErrors)
        println("inferred(Duration(1L)) in scope: " + Time.Duration.inferredErrors)
        println("inferred(1L) in scope:           " + Time.Duration.genuineLongErrors)
        println("inferred(Opt(1)) in scope:       " + Opt.Opt.inferredIntErrors)
    }

    test("a genuine Tag[Long] inside Duration's scope is never silently Duration") {
        assert(Time.Duration.genuineLongErrors.nonEmpty)
    }

    test("transparency would make Tag[Int] a subtype of Tag[Opt[Int]] against the typer") {
        // What a transparent Tag[Opt[Int]] would encode: the underlying with Present erased too.
        val transparent = Tag[Absent | Int | PresentAbsent]
        val typerSays   = typeCheckErrors("summon[Int <:< Opt.Opt[Int]]").isEmpty
        assertEquals(typerSays, false)
        assertEquals(Tag[Int] <:< transparent, true)
        // The nominal tag agrees with the typer.
        assertEquals(Tag[Int] <:< Tag[Opt.Opt[Int]], false)
    }
end CalibrationSuite
