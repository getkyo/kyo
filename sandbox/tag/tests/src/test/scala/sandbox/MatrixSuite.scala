package sandbox

import kyo.Tag
import sandbox.Shapes.*
import scala.compiletime.testing.typeCheckErrors

/** One assertion per matrix cell. ACCEPT cells compare encodings byte for byte with the
  * out-of-scope derivation; REFUSE cells assert the exact code.
  */
class MatrixSuite extends munit.FunSuite:

    val collapsed  = "[Tag.opaque.collapsed]"
    val unwalkable = "[Tag.opaque.unwalkable]"
    val givenCode  = "[Tag.opaque.given]"

    def refused(code: String)(errs: List[String])(using munit.Location): Unit =
        assert(errs.exists(_.contains(code)), s"expected $code, got: $errs")

    def same[A](inside: Tag[A], outside: Tag[A])(using munit.Location): Unit =
        assertEquals(encoding(inside), encoding(outside))

    test("Upper (Frame)") {
        same(Upper.Fr.derived, Tag[Upper.Fr])
        refused(collapsed)(Upper.Fr.inferredErrors)
        refused(collapsed)(Upper.Fr.stringErrors)
        refused(collapsed)(Upper.Fr.nestedErrors)
        refused(collapsed)(Upper.Fr.secondArgErrors)
        same(Upper.Fr.unrelated, Tag[Int])
        same(Upper.Fr.unrelatedNested, Tag[List[Int]])
    }

    test("Lower (bounds differ from underlying)") {
        same(Lower.Pet.derived, Tag[Lower.Pet])
        refused(collapsed)(Lower.Pet.inferredErrors)
        refused(collapsed)(Lower.Pet.mammalErrors)
        same(Lower.Pet.catOk, Tag[Cat])
        same(Lower.Pet.animalOk, Tag[Animal])
        // Subtyping agrees with the typer outside the scope.
        assertEquals(Tag[Cat] <:< Tag[Lower.Pet], true)
        assertEquals(Tag[Lower.Pet] <:< Tag[Animal], true)
        assertEquals(Tag[Mammal] <:< Tag[Lower.Pet], false)
        assertEquals(typeCheckErrors("summon[Mammal <:< Lower.Pet]").nonEmpty, true)
    }

    test("Union (JsonRpcId)") {
        same(Union.Id.derived, Tag[Union.Id])
        refused(collapsed)(Union.Id.inferredErrors)
        refused(collapsed)(Union.Id.unionErrors)
        refused(collapsed)(Union.Id.reorderedErrors)
        refused(collapsed)(Union.Id.widerErrors)
        refused(collapsed)(Union.Id.nestedWiderErrors)
        same(Union.Id.memberOk, Tag[String])
        println("union inside : " + encoding(Union.Id.otherUnionOk).replace("\n", " / "))
        println("union outside: " + encoding(Tag[String | Int]).replace("\n", " / "))
        assertEquals(Union.Id.otherUnionOk =:= Tag[String | Int], true)
        same(Union.Id.optionUnionOk, Tag[Option[String] | Long])
        assert(encoding(Tag[Union.Id]) != encoding(Tag[String | Long]))
    }

    test("Inter (Async)") {
        same(Inter.Async.derived, Tag[Inter.Async])
        refused(collapsed)(Inter.Async.interErrors)
        refused(collapsed)(Inter.Async.widerErrors)
        same(Inter.Async.memberOk, Tag[Sync])
        same(Inter.Async.otherInterOk, Tag[Sync & Extra])
    }

    test("Param (every variance)") {
        same(Param.Box.derivedInt, Tag[Param.Box[Int]])
        refused(collapsed)(Param.Box.inferredErrors)
        refused(collapsed)(Param.Box.listIntErrors)
        refused(collapsed)(Param.Box.nestedErrors)
        same(Param.Box.vectorOk, Tag[Vector[Int]])
        refused(collapsed)(Param.Box.boxOfListErrors)
        same(Param.Mixed.derived, Tag[Param.Mixed[Int, String]])
        refused(collapsed)(Param.Mixed.fnErrors)
        refused(collapsed)(Param.Mixed.contraErrors)
        refused(collapsed)(Param.Mixed.curriedErrors)
        same(Param.Mixed.otherFnOk, Tag[(Int, Int) => Int])
        assert(encoding(Tag[Param.Box[Int]]) != encoding(Tag[Param.Box[String]]))
        assertEquals(Tag[Param.Cov[Int]] <:< Tag[Param.Cov[AnyVal]], true)
        assertEquals(Tag[Param.Contra[AnyVal]] <:< Tag[Param.Contra[Int]], true)
        assertEquals(Tag[Param.Box[Int]] <:< Tag[Param.Box[AnyVal]], false)
    }

    test("Wild (Span)") {
        same(Wild.Sp.derived, Tag[Wild.Sp[Int]])
        refused(collapsed)(Wild.Sp.arrayWildErrors)
        refused(collapsed)(Wild.Sp.nestedWildErrors)
        same(Wild.Sp.arrayIntOk, Tag[Array[Int]])
    }

    test("Chain in one template") {
        same(Chain.Outer.derived, Tag[Chain.Outer])
        same(Chain.Outer.innerDerived, Tag[Chain.Inner])
        refused(collapsed)(Chain.Outer.inferredErrors)
        refused(collapsed)(Chain.Outer.intErrors)
        assert(encoding(Tag[Chain.Outer]) != encoding(Tag[Chain.Inner]))
        assertEquals(Tag[Chain.Outer] <:< Tag[Chain.Inner], true)
    }

    test("Chain across templates") {
        same(ChainB.Outer.derived, Tag[ChainB.Outer])
        refused(collapsed)(ChainB.Outer.inferredErrors)
        refused(collapsed)(ChainB.Outer.innerErrors)
        same(ChainB.Outer.intOk, Tag[Int])
        assert(encoding(Tag[ChainB.Outer]) != encoding(Tag[ChainA.Inner]))
    }

    test("Two brands in separate templates") {
        same(BrandB.Metres.derived, Tag[BrandB.Metres])
        same(BrandB.Metres.feetOk, Tag[BrandA.Feet])
        same(BrandB.Metres.feetInferredOk, Tag[BrandA.Feet])
        refused(collapsed)(BrandB.Metres.doubleErrors)
        assert(encoding(Tag[BrandA.Feet]) != encoding(Tag[BrandB.Metres]))
    }

    test("Higher-kinded unapplied") {
        same(Higher.Wrap.derived, Tag[Higher.Holder[Higher.Wrap]])
        refused(collapsed)(Higher.Wrap.listErrors)
        same(Higher.Wrap.inferredOk, Tag[Higher.Holder[Higher.Wrap]])
        same(Higher.Wrap.vectorOk, Tag[Higher.Holder[Vector]])
    }

    test("Unwalkable underlyings refuse everything in scope") {
        refused(unwalkable)(MatchT.Pick.anyErrors)
        refused(unwalkable)(MatchT.Pick.derivedErrors)
        refused(unwalkable)(Refined.R.anyErrors)
    }

    test("Trait template") {
        val mixer = new TraitMixer
        refused(collapsed)(mixer.inferredErrors)
        refused(collapsed)(mixer.intErrors)
        same(mixer.intOk, Tag[Int])
        same(mixer.tvInferred, mixer.tvDerived)
        assert(encoding(mixer.tvDerived) != encoding(Tag[Int]))
    }

    test("Sites within one template") {
        refused(collapsed)(Nested.templateErrors)
        refused(collapsed)(Nested.N.Deeper.deeperErrors)
        refused(collapsed)(Nested.Sibling.siblingErrors)
        same(Nested.N.Deeper.derived, Tag[Nested.N])
        same(NestedOutside.intOk, Tag[Int])
        same(NestedOutside.nOk, Tag[Nested.N])
        same(NestedOutside.nInferred, Tag[Nested.N])
    }

    test("Package-level opaque type") {
        same(sandbox.pkg.PkgId.derived, Tag[sandbox.pkg.PkgId])
        same(sandbox.pkg.PkgId.inferredUntyped, Tag[sandbox.pkg.PkgId])
        refused(collapsed)(sandbox.pkg.PkgId.inferredTypedErrors)
        refused(collapsed)(sandbox.pkg.PkgId.longErrors)
        same(sandbox.pkg.PkgId.unrelated, Tag[Int])
        same(sandbox.pkg.PkgOther.longOk, Tag[Long])
        same(sandbox.pkg.PkgOther.idOk, Tag[sandbox.pkg.PkgId])
        same(sandbox.pkg.PkgOther.idInferred, Tag[sandbox.pkg.PkgId])
        same(sandbox.pkg.PkgIdHolder.longOk, Tag[Long])
    }

    test("Fiber shape") {
        same(FiberShape.Fib.derived, Tag[FiberShape.Fib[Int, Any]])
        same(FiberShape.Fib.promiseWithKyoOk, Tag[FiberShape.Promise[Any, FiberShape.Kyo[Int, FiberShape.AsyncE & Any]]])
        refused(collapsed)(FiberShape.Fib.collapsedErrors)
        refused(collapsed)(FiberShape.Fib.kyoCollapsedErrors)
        same(FiberShape.Fib.promiseOtherOk, Tag[FiberShape.Promise[Int, Int]])
    }

    test("S9: inline bodies defined in scope, expanded here") {
        import InlineScope.Iv
        refused(collapsed)(Iv.inScopeSummonErrors)
        same(Iv.deriveIv(), Tag[Iv])
        same(Iv.tagOfInferred(Iv(1)), Tag[Iv])
        same(Iv.tagViaUsing(Iv(1)), Tag[Iv])
    }

    test("RESIDUAL S9: summonInline of the opaque type itself in an inline body defined in scope") {
        import InlineScope.Iv
        // Typed in scope, where Iv is Int; resolved at this expansion, where nothing is transparent
        // and the macro sees a plain Int with this test as its owner. Measured, not closable in the
        // macro: the only remedy is Tag.derive[Iv] in the body, which survives.
        assertEquals(encoding(Iv.tagOfIv()), encoding(Tag[Int]))
    }

    test("RESIDUAL: an inline given for the opaque type, defined in scope, intercepts in scope") {
        import InlineGivenHarm.Cg2
        assertEquals(encoding(Cg2.listInferred), encoding(Tag[Cg2[Int]]))
    }

    test("fact: union member order is not canonical in the encoding, only in equality") {
        val a = Tag[String | Int]
        val b = Tag[Int | String]
        assertEquals(a =:= b, true)
        println("union order byte-equal: " + (encoding(a) == encoding(b)))
    }

    test("RESIDUAL: an inline given's definition cannot be refused, its body never runs there") {
        assertEquals(ChainedGiven.Cg.givenErrors, Nil)
    }

    test("Givens for the opaque type defined in scope are refused") {
        refused(givenCode)(ChainedGiven.Cg.plainGivenErrors)
        refused(givenCode)(ChainedGiven.Cg.defGivenErrors)
        refused(givenCode)(ChainedGiven.Cg.anonymousGivenErrors)
        same(ChainedGiven.Cg.otherGivenOk, Tag[Vector[Int]])
    }

    test("Cache poisoning: an out-of-scope derivation earlier in the run does not leak into a scope") {
        same(Poison.First.long, Tag[Long])
        refused(collapsed)(Poison.Second.D.longErrors)
    }
end MatrixSuite
