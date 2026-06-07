package kyo
import kyo.internal.tasty.symbol.SymbolKind

/** Tests for Subtype checking and type comparison.
  *
  * All tests use synthetic Symbol instances with distinct SymbolId values so that Named(id) equality matches correctly in Subtyping. Each
  * test uses makeTestClasspath to register symbols at their id.value indices so cp.symbol(id) resolves correctly.
  */
class SubtypeTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger
    import kyo.Tasty.SymbolId

    // Counter for assigning unique SymbolIds in tests.
    private var nextId: Int = 0
    private def freshId(): SymbolId =
        val id = nextId
        nextId += 1
        SymbolId(id)
    end freshId

    /** Build a Symbol with a unique id and the given FQN / parents. */
    private def makeSym(fqn: String, parents: Chunk[Tasty.Type] = Chunk.empty): Tasty.Symbol.Class =
        val leafName = fqn.split("\\.").last
        Tasty.Symbol.Class(
            id = freshId(),
            name = Tasty.Name(leafName),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(-1),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = parents,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
    end makeSym

    private def makeCovParam(name: String): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            id = freshId(),
            name = Tasty.Name(name),
            flags = Tasty.Flags(Tasty.Flag.CoVariant),
            ownerId = SymbolId(-1),
            sourcePosition = Maybe.Absent,
            bounds = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            variance = Tasty.Variance.Covariant
        )
    end makeCovParam

    /** Wire a base-class symbol with the given type params.
      *
      * Returns the updated symbol with typeParamIds populated.
      */
    private def wireTypeParams(sym: Tasty.Symbol.Class, params: Chunk[Tasty.Symbol]): Tasty.Symbol.Class =
        sym.copy(typeParamIds = params.map(_.id))

    /** Build a test Classpath populated with the given symbols, indexed by their id.value.
      *
      * All symbols must have distinct non-negative id.value to be reachable via cp.symbol(id). Any gap in the id range is
      * filled with a sentinel Package symbol.
      */
    private def makeTestClasspath(syms: Chunk[Tasty.Symbol])(using Frame): Tasty.Classpath < Sync =
        val maxId = syms.foldLeft(-1)((m, s) => math.max(m, s.id.value))
        val arr   = new Array[Tasty.Symbol](maxId + 1)
        // Fill with sentinel first
        val sentinel =
            Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name("<sentinel>"), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)
        var fi = 0
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

    "Named(A).isSubtypeOf(Named(A)) returns Sub (reflexivity)" in {
        nextId = 0
        val intSym  = makeSym("scala.Int")
        val intType = Tasty.Type.Named(intSym.id)
        makeTestClasspath(Chunk(intSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(intType, intType).map(v => assert(v == Tasty.SubtypeVerdict.Sub))
    }

    "Named(String).isSubtypeOf(Named(Object)) returns Sub via parent chain" in {
        nextId = 0
        val objectSym  = makeSym("java.lang.Object")
        val objectType = Tasty.Type.Named(objectSym.id)
        val stringSym  = makeSym("java.lang.String", Chunk(objectType))
        val stringType = Tasty.Type.Named(stringSym.id)
        makeTestClasspath(Chunk(objectSym, stringSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(stringType, objectType).map(v => assert(v == Tasty.SubtypeVerdict.Sub))
    }

    "Named(String).isSubtypeOf(Named(Int)) returns NotSub" in {
        nextId = 0
        val intSym     = makeSym("scala.Int")
        val stringSym  = makeSym("java.lang.String")
        val intType    = Tasty.Type.Named(intSym.id)
        val stringType = Tasty.Type.Named(stringSym.id)
        makeTestClasspath(Chunk(intSym, stringSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(stringType, intType).map(v => assert(v == Tasty.SubtypeVerdict.NotSub))
    }

    "AndType(A, B).isSubtypeOf(A) returns Sub" in {
        nextId = 0
        val symA    = makeSym("test.A")
        val symB    = makeSym("test.B")
        val typeA   = Tasty.Type.Named(symA.id)
        val typeB   = Tasty.Type.Named(symB.id)
        val andType = Tasty.Type.AndType(typeA, typeB)
        makeTestClasspath(Chunk(symA, symB)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(andType, typeA).map(v => assert(v == Tasty.SubtypeVerdict.Sub))
    }

    "A.isSubtypeOf(OrType(A, B)) returns Sub" in {
        nextId = 0
        val symA   = makeSym("test.A")
        val symB   = makeSym("test.B")
        val typeA  = Tasty.Type.Named(symA.id)
        val typeB  = Tasty.Type.Named(symB.id)
        val orType = Tasty.Type.OrType(typeA, typeB)
        makeTestClasspath(Chunk(symA, symB)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(typeA, orType).map(v => assert(v == Tasty.SubtypeVerdict.Sub))
    }

    "Applied(List[String]).isSubtypeOf(Applied(List[AnyRef])) Sub when List is covariant" in {
        nextId = 0
        val anyRefSym  = makeSym("java.lang.Object")
        val anyRefType = Tasty.Type.Named(anyRefSym.id)
        val stringSym  = makeSym("java.lang.String", Chunk(anyRefType))
        val stringType = Tasty.Type.Named(stringSym.id)
        val tParam     = makeCovParam("T")
        var listSym    = makeSym("scala.collection.immutable.List")
        listSym = wireTypeParams(listSym, Chunk(tParam))
        val listType   = Tasty.Type.Named(listSym.id)
        val listString = Tasty.Type.Applied(listType, Chunk(stringType))
        val listAnyRef = Tasty.Type.Applied(listType, Chunk(anyRefType))
        makeTestClasspath(Chunk(anyRefSym, stringSym, tParam, listSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(listString, listAnyRef).map(v => assert(v == Tasty.SubtypeVerdict.Sub))
    }

    "Named(Nothing).isSubtypeOf(any type) returns Sub (bottom)" in {
        nextId = 0
        val nothingSym  = makeSym("scala.Nothing")
        val nothingType = Tasty.Type.Named(nothingSym.id)
        val anySym      = makeSym("scala.Any")
        val anyType     = Tasty.Type.Named(anySym.id)
        makeTestClasspath(Chunk(nothingSym, anySym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(nothingType, anyType).map(v => assert(v == Tasty.SubtypeVerdict.Sub))
    }

    "TypeLambda([T], C[T]).isSubtypeOf(TypeLambda([U], C[U])) Sub (alpha-equiv)" in {
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
        makeTestClasspath(Chunk(cSym, tSym, uSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(lambda1, lambda2).map: result =>
                    assert(result == Tasty.SubtypeVerdict.Sub)
                    succeed
    }

    "Rec type with RecThis back-reference terminates (budget exhaustion safety)" in {
        nextId = 0
        val cSym    = makeSym("test.C")
        val cType   = Tasty.Type.Named(cSym.id)
        val recBody = Tasty.Type.Applied(cType, Chunk(Tasty.Type.RecThis(cType)))
        val rec     = Tasty.Type.Rec(recBody)
        makeTestClasspath(Chunk(cSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(rec, rec).map: result =>
                    assert(
                        result == Tasty.SubtypeVerdict.Sub ||
                            result == Tasty.SubtypeVerdict.NotSub ||
                            result == Tasty.SubtypeVerdict.Indeterminate
                    )
                    succeed
    }

    "Int <: Any returns Sub" in {
        nextId = 0
        val intSym  = makeSym("scala.Int")
        val anySym  = makeSym("scala.Any")
        val intType = Tasty.Type.Named(intSym.id)
        val anyType = Tasty.Type.Named(anySym.id)
        makeTestClasspath(Chunk(intSym, anySym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(intType, anyType).map(v => assert(v == Tasty.SubtypeVerdict.Sub))
    }

    "String <: Int returns NotSub" in {
        nextId = 0
        val stringSym  = makeSym("java.lang.String")
        val intSym     = makeSym("scala.Int")
        val stringType = Tasty.Type.Named(stringSym.id)
        val intType    = Tasty.Type.Named(intSym.id)
        makeTestClasspath(Chunk(stringSym, intSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(stringType, intType).map(v => assert(v == Tasty.SubtypeVerdict.NotSub))
    }

    "budget exhaustion returns Indeterminate" in {
        nextId = 0
        val stringSym  = makeSym("java.lang.String")
        val intSym     = makeSym("scala.Int")
        val stringType = Tasty.Type.Named(stringSym.id)
        val intType    = Tasty.Type.Named(intSym.id)
        makeTestClasspath(Chunk(stringSym, intSym)).map: cp =>
            val result = kyo.internal.tasty.type_.Subtyping.isSubtype(stringType, intType, cp, budget = 0, null)
            assert(result == Tasty.SubtypeVerdict.Indeterminate)
            succeed
    }

    "real 66-deep Rec chain exhausts default budget=64 and returns Indeterminate" in {
        nextId = 0
        val leafSym          = makeSym("RecBudgetLeaf")
        val leaf: Tasty.Type = Tasty.Type.Named(leafSym.id)
        var t: Tasty.Type    = leaf
        var i                = 0
        while i < 66 do
            t = Tasty.Type.Rec(t)
            i += 1
        end while
        makeTestClasspath(Chunk(leafSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(t, t).map: result =>
                    assert(
                        result == Tasty.SubtypeVerdict.Indeterminate,
                        s"Expected Indeterminate from real 66-deep Rec chain (budget=64) but got $result"
                    )
                    succeed
    }

    "empty parent chain returns NotSub" in {
        nextId = 0
        val fooSym  = makeSym("test.Foo")
        val barSym  = makeSym("test.Bar")
        val fooType = Tasty.Type.Named(fooSym.id)
        val barType = Tasty.Type.Named(barSym.id)
        makeTestClasspath(Chunk(fooSym, barSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(fooType, barType).map: result =>
                    assert(
                        result == Tasty.SubtypeVerdict.NotSub,
                        "Expected NotSub for symbol with empty parentTypes (no parent chain)"
                    )
                    succeed
    }

    // ── tests: ADT sentinel cases Type.Any and Type.Nothing ──────────

    // Test F003-1: Named(X).isSubtypeOf(Type.Any) returns Sub via ADT sentinel arm
    "Named(X).isSubtypeOf(Type.Any) == Sub (ADT sentinel)" in {
        nextId = 0
        val intSym  = makeSym("scala.Int")
        val intType = Tasty.Type.Named(intSym.id)
        makeTestClasspath(Chunk(intSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(intType, Tasty.Type.Any).map: v =>
                    assert(v == Tasty.SubtypeVerdict.Sub, s"Expected Sub but got $v")
    }

    // Test F003-2: Type.Nothing.isSubtypeOf(Named(X)) returns Sub via ADT sentinel arm
    "Type.Nothing.isSubtypeOf(Named(X)) == Sub (ADT sentinel)" in {
        nextId = 0
        val stringSym  = makeSym("java.lang.String")
        val stringType = Tasty.Type.Named(stringSym.id)
        makeTestClasspath(Chunk(stringSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(Tasty.Type.Nothing, stringType).map: v =>
                    assert(v == Tasty.SubtypeVerdict.Sub, s"Expected Sub but got $v")
    }

    // Test F003-3: Type.Nothing.isSubtypeOf(Type.Any) returns Sub (both ADT sentinels)
    "Type.Nothing.isSubtypeOf(Type.Any) == Sub (both ADT sentinels)" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(Tasty.Type.Nothing, Tasty.Type.Any).map: v =>
                    assert(v == Tasty.SubtypeVerdict.Sub, s"Expected Sub but got $v")
    }

    // Test F003-4: Type.Any.isSubtypeOf(Named(X)) returns NotSub (Any is not sub of random type)
    "Type.Any.isSubtypeOf(Named(X)) == NotSub (negative pinning)" in {
        nextId = 0
        val intSym  = makeSym("scala.Int")
        val intType = Tasty.Type.Named(intSym.id)
        makeTestClasspath(Chunk(intSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(Tasty.Type.Any, intType).map: v =>
                    assert(v == Tasty.SubtypeVerdict.NotSub, s"Expected NotSub but got $v")
    }

    // Test F003-5: Named(X).isSubtypeOf(Type.Nothing) returns NotSub (Nothing is bottom type)
    "Named(X).isSubtypeOf(Type.Nothing) == NotSub (negative pinning)" in {
        nextId = 0
        val stringSym  = makeSym("java.lang.String")
        val stringType = Tasty.Type.Named(stringSym.id)
        makeTestClasspath(Chunk(stringSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(stringType, Tasty.Type.Nothing).map: v =>
                    assert(v == Tasty.SubtypeVerdict.NotSub, s"Expected NotSub but got $v")
    }

end SubtypeTest
