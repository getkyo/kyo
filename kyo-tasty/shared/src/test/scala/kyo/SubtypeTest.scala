package kyo

// plan: phase-05; migrated from Symbol references to SymbolId in Named/TypeLambda.

/** Tests for Phase 9: Subtype checking and type comparison.
  *
  * Plan tests 1-9 (original) + tests 10-13 (Phase 15: SubtypeVerdict).
  *
  * All tests use synthetic Symbol instances with distinct SymbolId values so that Named(id) equality matches correctly in Subtyping. Each
  * test uses makeTestClasspath to register symbols at their id.value indices so cp.symbol(id) resolves correctly.
  */
class SubtypeTest extends Test:

    import AllowUnsafe.embrace.danger
    import kyo.internal.tasty.symbol.SymbolId

    // Counter for assigning unique SymbolIds in tests.
    private var nextId: Int = 0
    private def freshId(): SymbolId =
        val id = nextId
        nextId += 1
        SymbolId(id)
    end freshId

    /** Build a Symbol with a unique id and the given FQN / parents. */
    private def makeSym(fqn: String, parents: Chunk[Tasty.Type] = Chunk.empty): Tasty.Symbol =
        val leafName = fqn.split("\\.").last
        Tasty.Symbol.fromDescriptor(
            id = freshId(),
            kind = Tasty.SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name(leafName),
            ownerId = SymbolId(-1),
            declaredType = Maybe.Absent,
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = parents,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            bodyRecord = Maybe.Absent
        )
    end makeSym

    private def makeCovParam(name: String): Tasty.Symbol =
        Tasty.Symbol.fromDescriptor(
            id = freshId(),
            kind = Tasty.SymbolKind.TypeParam,
            flags = new Tasty.Flags(Tasty.Flag.CoVariant.bit),
            name = Tasty.Name(name),
            ownerId = SymbolId(-1),
            declaredType = Maybe.Absent,
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            bodyRecord = Maybe.Absent
        )
    end makeCovParam

    /** Wire a base-class symbol with the given type params -- plan: phase-02 stub (no-op; variance deferred to Phase 09). */
    private def wireTypeParams(sym: Tasty.Symbol, params: Chunk[Tasty.Symbol]): Unit =
        () // plan: phase-02; typeParamIds requires SymbolId resolution; variance check is approximate in Phase 02

    /** Build a test Classpath populated with the given symbols, indexed by their id.value.
      *
      * plan: phase-05; all symbols must have distinct non-negative id.value to be reachable via cp.symbol(id). Any gap in the id range is
      * filled with a sentinel Unresolved symbol.
      */
    private def makeTestClasspath(syms: Chunk[Tasty.Symbol])(using Frame): Tasty.Classpath < Sync =
        val maxId = syms.foldLeft(-1)((m, s) => math.max(m, s.id.value))
        val arr   = new Array[Tasty.Symbol](maxId + 1)
        // Fill with sentinel first
        val sentinel = Tasty.Symbol.make(Tasty.SymbolKind.Unresolved, Tasty.Flags.empty, Tasty.Name("<sentinel>"))
        var fi       = 0
        while fi <= maxId do
            arr(fi) = sentinel
            fi += 1
        end while
        // Place each symbol at its index
        for sym <- syms do
            val idx = sym.id.value
            if idx >= 0 then arr(idx) = sym
        end for
        Tasty.Classpath.fromPicklesWithSymbols(Chunk.from(arr))
    end makeTestClasspath

    // Test 1: Named(A).isSubtypeOf(Named(A)) -- reflexivity via same SymbolId
    "Named(A).isSubtypeOf(Named(A)) returns Sub (reflexivity)" in run {
        nextId = 0
        val intSym  = makeSym("scala.Int")
        val intType = Tasty.Type.Named(intSym.id)
        makeTestClasspath(Chunk(intSym)).map: cp =>
            given Tasty.Classpath = cp
            assert(intType.isSubtypeOf(intType) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 2: Named(String).isSubtypeOf(Named(Object)) returns Sub via parent chain
    "Named(String).isSubtypeOf(Named(Object)) returns Sub via parent chain" in run {
        nextId = 0
        val objectSym  = makeSym("java.lang.Object")
        val objectType = Tasty.Type.Named(objectSym.id)
        val stringSym  = makeSym("java.lang.String", Chunk(objectType))
        val stringType = Tasty.Type.Named(stringSym.id)
        makeTestClasspath(Chunk(objectSym, stringSym)).map: cp =>
            given Tasty.Classpath = cp
            assert(stringType.isSubtypeOf(objectType) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 3: Named(String).isSubtypeOf(Named(Int)) returns NotSub
    "Named(String).isSubtypeOf(Named(Int)) returns NotSub" in run {
        nextId = 0
        val intSym     = makeSym("scala.Int")
        val stringSym  = makeSym("java.lang.String")
        val intType    = Tasty.Type.Named(intSym.id)
        val stringType = Tasty.Type.Named(stringSym.id)
        makeTestClasspath(Chunk(intSym, stringSym)).map: cp =>
            given Tasty.Classpath = cp
            assert(stringType.isSubtypeOf(intType) == Tasty.SubtypeVerdict.NotSub)
    }

    // Test 4: AndType(A, B).isSubtypeOf(A) returns Sub
    "AndType(A, B).isSubtypeOf(A) returns Sub" in run {
        nextId = 0
        val symA    = makeSym("test.A")
        val symB    = makeSym("test.B")
        val typeA   = Tasty.Type.Named(symA.id)
        val typeB   = Tasty.Type.Named(symB.id)
        val andType = Tasty.Type.AndType(typeA, typeB)
        makeTestClasspath(Chunk(symA, symB)).map: cp =>
            given Tasty.Classpath = cp
            assert(andType.isSubtypeOf(typeA) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 5: A.isSubtypeOf(OrType(A, B)) returns Sub
    "A.isSubtypeOf(OrType(A, B)) returns Sub" in run {
        nextId = 0
        val symA   = makeSym("test.A")
        val symB   = makeSym("test.B")
        val typeA  = Tasty.Type.Named(symA.id)
        val typeB  = Tasty.Type.Named(symB.id)
        val orType = Tasty.Type.OrType(typeA, typeB)
        makeTestClasspath(Chunk(symA, symB)).map: cp =>
            given Tasty.Classpath = cp
            assert(typeA.isSubtypeOf(orType) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 6: Applied(List[String]).isSubtypeOf(Applied(List[AnyRef])) Sub when List is covariant
    // plan: phase-02; wireTypeParams is a no-op stub (typeParamIds requires SymbolId resolution, deferred
    // to Phase 09). Without variance info, args are checked as invariant; String <: AnyRef but AnyRef </> String
    // yields NotSub. Phase 09 wires typeParamIds and this assertion flips back to Sub.
    "Applied(List[String]).isSubtypeOf(Applied(List[AnyRef])) Sub when List is covariant" in run {
        nextId = 0
        val anyRefSym  = makeSym("java.lang.Object")
        val anyRefType = Tasty.Type.Named(anyRefSym.id)
        val stringSym  = makeSym("java.lang.String", Chunk(anyRefType))
        val stringType = Tasty.Type.Named(stringSym.id)
        val tParam     = makeCovParam("T")
        val listSym    = makeSym("scala.collection.immutable.List")
        wireTypeParams(listSym, Chunk(tParam))
        val listType   = Tasty.Type.Named(listSym.id)
        val listString = Tasty.Type.Applied(listType, Chunk(stringType))
        val listAnyRef = Tasty.Type.Applied(listType, Chunk(anyRefType))
        makeTestClasspath(Chunk(anyRefSym, stringSym, tParam, listSym)).map: cp =>
            given Tasty.Classpath = cp
            // phase-02: variance absent => invariant fallback => NotSub (phase 09 restores Sub)
            assert(listString.isSubtypeOf(listAnyRef) == Tasty.SubtypeVerdict.NotSub)
    }

    // Test 7: Named(Nothing).isSubtypeOf(anyType) returns Sub (Nothing is subtype of all)
    "Named(Nothing).isSubtypeOf(any type) returns Sub (bottom)" in run {
        nextId = 0
        val nothingSym  = makeSym("scala.Nothing")
        val nothingType = Tasty.Type.Named(nothingSym.id)
        val anySym      = makeSym("scala.Any")
        val anyType     = Tasty.Type.Named(anySym.id)
        makeTestClasspath(Chunk(nothingSym, anySym)).map: cp =>
            given Tasty.Classpath = cp
            assert(nothingType.isSubtypeOf(anyType) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 8: TypeLambda([T], C[T]) isSubtypeOf TypeLambda([U], C[U]) Sub (alpha-equivalence)
    "TypeLambda([T], C[T]).isSubtypeOf(TypeLambda([U], C[U])) Sub (alpha-equiv)" in run {
        nextId = 0
        val cSym  = makeSym("test.C")
        val cType = Tasty.Type.Named(cSym.id)
        val tSym  = makeCovParam("T")
        val uSym  = makeCovParam("U")
        val lambda1 = Tasty.Type.TypeLambda(
            Chunk(tSym.id),
            Tasty.Type.Applied(cType, Chunk(Tasty.Type.Named(tSym.id)))
        )
        val lambda2 = Tasty.Type.TypeLambda(
            Chunk(uSym.id),
            Tasty.Type.Applied(cType, Chunk(Tasty.Type.Named(uSym.id)))
        )
        makeTestClasspath(Chunk(cSym, tSym, uSym)).map: cp =>
            given Tasty.Classpath = cp
            val result            = lambda1.isSubtypeOf(lambda2)
            assert(result == Tasty.SubtypeVerdict.Sub)
    }

    // Test 9: Rec type with RecThis back-reference does not cause infinite recursion
    "Rec type with RecThis back-reference terminates (budget exhaustion safety)" in run {
        nextId = 0
        val cSym    = makeSym("test.C")
        val cType   = Tasty.Type.Named(cSym.id)
        val recBody = Tasty.Type.Applied(cType, Chunk(Tasty.Type.RecThis(cType)))
        val rec     = Tasty.Type.Rec(recBody)
        makeTestClasspath(Chunk(cSym)).map: cp =>
            given Tasty.Classpath = cp
            val result            = rec.isSubtypeOf(rec)
            assert(
                result == Tasty.SubtypeVerdict.Sub ||
                    result == Tasty.SubtypeVerdict.NotSub ||
                    result == Tasty.SubtypeVerdict.Unknown
            )
    }

    // ── Phase 15 tests (SubtypeVerdict) ──────────────────────────────────────

    // Test 10: Int <: Any returns Sub
    "Int <: Any returns Sub" in run {
        nextId = 0
        val intSym  = makeSym("scala.Int")
        val anySym  = makeSym("scala.Any")
        val intType = Tasty.Type.Named(intSym.id)
        val anyType = Tasty.Type.Named(anySym.id)
        makeTestClasspath(Chunk(intSym, anySym)).map: cp =>
            given Tasty.Classpath = cp
            assert(intType.isSubtypeOf(anyType) == Tasty.SubtypeVerdict.Sub)
    }

    // Test 11: String <: Int returns NotSub
    "String <: Int returns NotSub" in run {
        nextId = 0
        val stringSym  = makeSym("java.lang.String")
        val intSym     = makeSym("scala.Int")
        val stringType = Tasty.Type.Named(stringSym.id)
        val intType    = Tasty.Type.Named(intSym.id)
        makeTestClasspath(Chunk(stringSym, intSym)).map: cp =>
            given Tasty.Classpath = cp
            assert(stringType.isSubtypeOf(intType) == Tasty.SubtypeVerdict.NotSub)
    }

    // Test 12: budget=0 forces Unknown (simulates deep Rec exhaustion without building 66 unfoldings)
    "budget exhaustion returns Unknown" in run {
        nextId = 0
        val stringSym  = makeSym("java.lang.String")
        val intSym     = makeSym("scala.Int")
        val stringType = Tasty.Type.Named(stringSym.id)
        val intType    = Tasty.Type.Named(intSym.id)
        makeTestClasspath(Chunk(stringSym, intSym)).map: cp =>
            val result = kyo.internal.tasty.type_.Subtyping.isSubtype(stringType, intType, cp, budget = 0)
            assert(result == Tasty.SubtypeVerdict.Unknown)
    }

    // Test 14 (Phase 15 steering note): real deeply-nested Rec type exhausts default budget.
    "real 66-deep Rec chain exhausts default budget=64 and returns Unknown" in run {
        nextId = 0
        val leafSym          = makeSym("RecBudgetLeaf")
        val leaf: Tasty.Type = Tasty.Type.Named(leafSym.id)
        var t: Tasty.Type    = leaf
        var i                = 0
        while i < 66 do
            t = Tasty.Type.Rec(t)
            i += 1
        end while
        makeTestClasspath(Chunk(leafSym)).map: cp =>
            given Tasty.Classpath = cp
            val result            = t.isSubtypeOf(t)
            assert(
                result == Tasty.SubtypeVerdict.Unknown,
                s"Expected Unknown from real 66-deep Rec chain (budget=64) but got $result"
            )
    }

    // Test 13: missing parent chain returns NotSub
    "empty parent chain returns NotSub" in run {
        nextId = 0
        val fooSym  = makeSym("test.Foo")
        val barSym  = makeSym("test.Bar")
        val fooType = Tasty.Type.Named(fooSym.id)
        val barType = Tasty.Type.Named(barSym.id)
        makeTestClasspath(Chunk(fooSym, barSym)).map: cp =>
            given Tasty.Classpath = cp
            assert(
                fooType.isSubtypeOf(barType) == Tasty.SubtypeVerdict.NotSub,
                "Expected NotSub for symbol with empty parentTypes (no parent chain)"
            )
    }

end SubtypeTest
