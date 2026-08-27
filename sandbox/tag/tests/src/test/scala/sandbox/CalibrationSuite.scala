package sandbox

import kyo.Tag
import scala.compiletime.testing.typeCheckErrors

/** The harness must detect known bugs before it is trusted. Expected results are recorded per
  * macro version in scripts/calibrate.sh.
  */
class CalibrationSuite extends munit.FunSuite:

    def refusedWith(code: String)(errs: List[String])(using munit.Location): Unit =
        assert(errs.exists(_.contains(code)), s"expected $code, got: $errs")

    test("S2: a given Tag[Feet] defined where Feet is transparent is refused") {
        refusedWith("[Tag.opaque.given]")(TwoBrands.givenErrors)
    }

    test("S2: an inferred Metres inside the two-brand scope is refused") {
        refusedWith("[Tag.opaque.collapsed]")(TwoBrands.metresInferredErrors)
    }

    test("a genuine Tag[Double] inside the two-brand scope is refused") {
        refusedWith("[Tag.opaque.collapsed]")(TwoBrands.doubleErrors)
    }

    test("explicit derivations inside equal the ones outside, and the two brands differ") {
        assertEquals(encoding(TwoBrands.feetDerived), encoding(Tag[TwoBrands.Feet]))
        assertEquals(encoding(TwoBrands.metresDerived), encoding(Tag[TwoBrands.Metres]))
        assert(encoding(TwoBrands.feetDerived) != encoding(TwoBrands.metresDerived))
        assertEquals(encoding(TwoBrands.moduleTypeOk), encoding(Tag[TwoBrands.type]))
    }

    test("S6: parameterized opaque types keep their arguments") {
        assert(encoding(Tag[Opt.Opt[Int]]) != encoding(Tag[Opt.Opt[String]]))
    }

    test("Opt: explicit inside equals outside; inferred is refused, also nested") {
        assertEquals(encoding(Opt.Opt.derivedInt), encoding(Tag[Opt.Opt[Int]]))
        refusedWith("[Tag.opaque.collapsed]")(Opt.Opt.inferredIntErrors)
        refusedWith("[Tag.opaque.collapsed]")(Opt.Opt.listErrors)
        assertEquals(encoding(Opt.Opt.unrelated), encoding(Tag[List[String]]))
    }

    test("Duration: explicit inside equals outside") {
        assertEquals(encoding(Time.Duration.derived), encoding(Tag[Time.Duration]))
        assertEquals(encoding(Time.Duration.unrelated), encoding(Tag[Int]))
    }

    test("MEASURE: inline using parameter in scope") {
        println("inline inferred, no expected type: " + Time.Duration.inlineInferredErrors.map(_.take(40)))
        println("inline inferred, expected type:    " + Time.Duration.inlineInferredTypedErrors.map(_.take(40)))
        println("emitLike inline Tag[Eff[V]]:       " + Time.Duration.emitLikeErrors.map(_.take(40)))
        println("emitLike inline, expected type:    " + Time.Duration.emitLikeTypedErrors.map(_.take(40)))
        println("emitLike plain Tag[Eff[V]]:        " + Time.Duration.emitLikePlainErrors.map(_.take(40)))
        println("listLike inline Tag[List[V]]:      " + Time.Duration.listLikeErrors.map(_.take(40)))
        println("emitReal typed equals outside:     " + (encoding(Time.Duration(1L).emitReal) == encoding(Tag[Eff[Time.Duration]])))
        println("emitReal untyped equals outside:   " + (encoding(Time.Duration(1L).emitRealUntyped) == encoding(Tag[Eff[Time.Duration]])))
    }

    test("Duration: Tag.apply, an inferred Duration and a genuine Long are all refused in scope") {
        refusedWith("[Tag.opaque.collapsed]")(Time.Duration.applyErrors)
        refusedWith("[Tag.opaque.collapsed]")(Time.Duration.inferredErrors)
        refusedWith("[Tag.opaque.collapsed]")(Time.Duration.genuineLongErrors)
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
