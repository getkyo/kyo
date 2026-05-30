package kyo

import kyo.internal.tasty.query.ClasspathRef

/** Tests for Phase 9: Subtype checking and type comparison.
  *
  * Plan tests 1-9 (original) + tests 10-13 (Phase 15: SubtypeVerdict).
  *
  * All tests use synthetic Symbol instances constructed in-memory (no TASTy/classfile I/O). Parent chains are wired by writing directly to
  * the `_parents` SingleAssign slot (the same path used by AstUnpickler during real classpath loading). Each test obtains a minimal
  * `Tasty.Classpath` from `Tasty.Classpath.fromPickles(Seq.empty)`.
  */
class SubtypeTest extends Test:

    /** Build a Named symbol with the given FQN and pre-wire its `_parents` slot. */
    private def makeSym(fqn: String, parents: Chunk[Tasty.Type] = Chunk.empty): Tasty.Symbol =
        import AllowUnsafe.embrace.danger
        val parts = fqn.split("\\.").toList
        val root = Tasty.Symbol.make(
            Tasty.SymbolKind.Package,
            Tasty.Flags.empty,
            Tasty.Name(""),
            null,
            ClasspathRef.init(),
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val sym = parts.foldLeft(root) { (owner, part) =>
            Tasty.Symbol.make(
                Tasty.SymbolKind.Class,
                Tasty.Flags.empty,
                Tasty.Name(part),
                owner,
                ClasspathRef.init(),
                Tasty.Symbol.TastyOrigin.empty,
                Absent
            )
        }
        sym._parents.set(parents)
        sym
    end makeSym

    /** Build a covariant TypeParam symbol (CoVariant flag set). */
    private def makeCovParam(name: String): Tasty.Symbol =
        import AllowUnsafe.embrace.danger
        val root = Tasty.Symbol.make(
            Tasty.SymbolKind.Package,
            Tasty.Flags.empty,
            Tasty.Name(""),
            null,
            ClasspathRef.init(),
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val sym = Tasty.Symbol.make(
            Tasty.SymbolKind.TypeParam,
            new Tasty.Flags(Tasty.Flag.CoVariant.bit),
            Tasty.Name(name),
            root,
            ClasspathRef.init(),
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        sym._parents.set(Chunk.empty)
        sym
    end makeCovParam

    /** Wire a base-class symbol with the given type params. */
    private def wireTypeParams(sym: Tasty.Symbol, params: Chunk[Tasty.Symbol]): Unit =
        import AllowUnsafe.embrace.danger
        sym._typeParams.set(params)

    // Test 1: Named(A).isSubtypeOf(Named(A)) -- reflexivity via same symbol reference
    "Named(A).isSubtypeOf(Named(A)) returns Sub (reflexivity)" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val intSym            = makeSym("scala.Int")
            val intType           = Tasty.Type.Named(intSym)
            assert(intType.isSubtypeOf(intType) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 2: Named(String).isSubtypeOf(Named(Object)) returns Sub via parent chain
    "Named(String).isSubtypeOf(Named(Object)) returns Sub via parent chain" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val objectSym         = makeSym("java.lang.Object")
            val objectType        = Tasty.Type.Named(objectSym)
            val stringSym         = makeSym("java.lang.String", Chunk(objectType))
            val stringType        = Tasty.Type.Named(stringSym)
            assert(stringType.isSubtypeOf(objectType) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 3: Named(String).isSubtypeOf(Named(Int)) returns NotSub
    "Named(String).isSubtypeOf(Named(Int)) returns NotSub" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val intSym            = makeSym("scala.Int")
            val stringSym         = makeSym("java.lang.String")
            val intType           = Tasty.Type.Named(intSym)
            val stringType        = Tasty.Type.Named(stringSym)
            assert(stringType.isSubtypeOf(intType) == Tasty.SubtypeVerdict.NotSub)
    }

    // Test 4: AndType(A, B).isSubtypeOf(A) returns Sub
    "AndType(A, B).isSubtypeOf(A) returns Sub" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val symA              = makeSym("test.A")
            val symB              = makeSym("test.B")
            val typeA             = Tasty.Type.Named(symA)
            val typeB             = Tasty.Type.Named(symB)
            val andType           = Tasty.Type.AndType(typeA, typeB)
            assert(andType.isSubtypeOf(typeA) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 5: A.isSubtypeOf(OrType(A, B)) returns Sub
    "A.isSubtypeOf(OrType(A, B)) returns Sub" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val symA              = makeSym("test.A")
            val symB              = makeSym("test.B")
            val typeA             = Tasty.Type.Named(symA)
            val typeB             = Tasty.Type.Named(symB)
            val orType            = Tasty.Type.OrType(typeA, typeB)
            assert(typeA.isSubtypeOf(orType) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 6: Applied(List[String]).isSubtypeOf(Applied(List[AnyRef])) Sub when List is covariant
    "Applied(List[String]).isSubtypeOf(Applied(List[AnyRef])) Sub when List is covariant" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val anyRefSym         = makeSym("java.lang.Object")
            val anyRefType        = Tasty.Type.Named(anyRefSym)
            val stringSym         = makeSym("java.lang.String", Chunk(anyRefType))
            val stringType        = Tasty.Type.Named(stringSym)
            val tParam            = makeCovParam("T")
            val listSym           = makeSym("scala.collection.immutable.List")
            wireTypeParams(listSym, Chunk(tParam))
            val listType   = Tasty.Type.Named(listSym)
            val listString = Tasty.Type.Applied(listType, Chunk(stringType))
            val listAnyRef = Tasty.Type.Applied(listType, Chunk(anyRefType))
            assert(listString.isSubtypeOf(listAnyRef) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 7: Named(Nothing).isSubtypeOf(anyType) returns Sub (Nothing is subtype of all)
    "Named(Nothing).isSubtypeOf(any type) returns Sub (bottom)" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val nothingSym        = makeSym("scala.Nothing")
            val nothingType       = Tasty.Type.Named(nothingSym)
            val anySym            = makeSym("scala.Any")
            val anyType           = Tasty.Type.Named(anySym)
            assert(nothingType.isSubtypeOf(anyType) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 8: TypeLambda([T], C[T]) isSubtypeOf TypeLambda([U], C[U]) Sub (alpha-equivalence)
    "TypeLambda([T], C[T]).isSubtypeOf(TypeLambda([U], C[U])) Sub (alpha-equiv)" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            import AllowUnsafe.embrace.danger
            given Tasty.Classpath = cp
            val cSym              = makeSym("test.C")
            val cType             = Tasty.Type.Named(cSym)
            val tSym = Tasty.Symbol.make(
                Tasty.SymbolKind.TypeParam,
                Tasty.Flags.empty,
                Tasty.Name("T"),
                null,
                ClasspathRef.init(),
                Tasty.Symbol.TastyOrigin.empty,
                Absent
            )
            val uSym = Tasty.Symbol.make(
                Tasty.SymbolKind.TypeParam,
                Tasty.Flags.empty,
                Tasty.Name("U"),
                null,
                ClasspathRef.init(),
                Tasty.Symbol.TastyOrigin.empty,
                Absent
            )
            val lambda1 = Tasty.Type.TypeLambda(
                Chunk(tSym),
                Tasty.Type.Applied(cType, Chunk(Tasty.Type.Named(tSym)))
            )
            val lambda2 = Tasty.Type.TypeLambda(
                Chunk(uSym),
                Tasty.Type.Applied(cType, Chunk(Tasty.Type.Named(uSym)))
            )
            val result = lambda1.isSubtypeOf(lambda2)
            assert(result == Tasty.SubtypeVerdict.Sub)
    }

    // Test 9: Rec type with RecThis back-reference does not cause infinite recursion
    "Rec type with RecThis back-reference terminates (budget exhaustion safety)" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val cSym              = makeSym("test.C")
            val cType             = Tasty.Type.Named(cSym)
            // Rec(Applied(C, [RecThis(placeholder)])) -- RecThis uses a stand-in reference.
            // The exact self-referential closure doesn't matter; the budget prevents divergence.
            val recBody = Tasty.Type.Applied(cType, Chunk(Tasty.Type.RecThis(cType)))
            val rec     = Tasty.Type.Rec(recBody)
            val result  = rec.isSubtypeOf(rec)
            // Sub, NotSub, or Unknown are all acceptable; termination is the critical property.
            assert(
                result == Tasty.SubtypeVerdict.Sub ||
                    result == Tasty.SubtypeVerdict.NotSub ||
                    result == Tasty.SubtypeVerdict.Unknown
            )
    }

    // ── Phase 15 tests (SubtypeVerdict) ──────────────────────────────────────

    // Test 10: Int <: Any returns Sub
    "Int <: Any returns Sub" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val intSym            = makeSym("scala.Int")
            val anySym            = makeSym("scala.Any")
            val intType           = Tasty.Type.Named(intSym)
            val anyType           = Tasty.Type.Named(anySym)
            assert(intType.isSubtypeOf(anyType) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 11: String <: Int returns NotSub
    "String <: Int returns NotSub" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val stringSym         = makeSym("java.lang.String")
            val intSym            = makeSym("scala.Int")
            val stringType        = Tasty.Type.Named(stringSym)
            val intType           = Tasty.Type.Named(intSym)
            assert(stringType.isSubtypeOf(intType) == Tasty.SubtypeVerdict.NotSub)
    }

    // Test 12: budget=0 forces Unknown (simulates deep Rec exhaustion without building 66 unfoldings)
    // Fallback: calling isSubtype directly with budget=0 produces Unknown, which is what would happen
    // if a deeply-nested Rec type exhausted the budget during a real check.
    "budget exhaustion returns Unknown" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            import AllowUnsafe.embrace.danger
            val stringSym  = makeSym("java.lang.String")
            val intSym     = makeSym("scala.Int")
            val stringType = Tasty.Type.Named(stringSym)
            val intType    = Tasty.Type.Named(intSym)
            val result     = kyo.internal.tasty.type_.Subtyping.isSubtype(stringType, intType, Tasty.Classpath.unwrap(cp), budget = 0)
            assert(result == Tasty.SubtypeVerdict.Unknown)
    }

    // Test 14 (Phase 15 steering note): real deeply-nested Rec type exhausts default budget.
    // Build a Rec-wrapped chain 66 levels deep: Rec(Rec(Rec(...Named...))).
    // When isSubtypeOf is called with the default budget=64, each Rec unfolding decrements
    // the budget by 1. After 64 Rec unfolds the budget reaches 0 and isSubtype returns Unknown.
    // This exercises real recursive traversal through 66 Rec levels (vs the budget=0 shortcut in Test 12).
    // Fixture approach: 66 nested Rec wrappers around a Named leaf.
    // Pins: Phase 15 audit NOTE (real-recursion coverage).
    "real 66-deep Rec chain exhausts default budget=64 and returns Unknown" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val leafSym           = makeSym("RecBudgetLeaf")
            val leaf: Tasty.Type  = Tasty.Type.Named(leafSym)
            // Build 66 levels of Rec wrapping: Rec(Rec(Rec(...Named...))).
            // The default budget is 64; 66 unfolds will exceed it.
            var t: Tasty.Type = leaf
            var i             = 0
            while i < 66 do
                t = Tasty.Type.Rec(t)
                i += 1
            end while
            val result = t.isSubtypeOf(t)
            assert(
                result == Tasty.SubtypeVerdict.Unknown,
                s"Expected Unknown from real 66-deep Rec chain (budget=64) but got $result"
            )
    }

    // Test 13: missing parent chain returns Unknown
    // A symbol whose _parents slot has never been set produces Unknown (not NotSub), because the
    // absence of parent data means we cannot definitively say the relation does not hold.
    "missing parent chain returns Unknown" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            import AllowUnsafe.embrace.danger
            // Build Foo without setting _parents (slot is unset)
            val root = Tasty.Symbol.make(
                Tasty.SymbolKind.Package,
                Tasty.Flags.empty,
                Tasty.Name(""),
                null,
                ClasspathRef.init(),
                Tasty.Symbol.TastyOrigin.empty,
                Absent
            )
            val fooSym = Tasty.Symbol.make(
                Tasty.SymbolKind.Class,
                Tasty.Flags.empty,
                Tasty.Name("Foo"),
                root,
                ClasspathRef.init(),
                Tasty.Symbol.TastyOrigin.empty,
                Absent
            )
            // _parents intentionally NOT set on fooSym
            val barSym  = makeSym("test.Bar")
            val fooType = Tasty.Type.Named(fooSym)
            val barType = Tasty.Type.Named(barSym)
            assert(fooType.isSubtypeOf(barType) == Tasty.SubtypeVerdict.Unknown)
    }

end SubtypeTest
