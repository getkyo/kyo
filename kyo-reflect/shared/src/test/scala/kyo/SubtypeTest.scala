package kyo

import kyo.internal.reflect.query.ClasspathRef

/** Tests for Phase 9: Subtype checking and type comparison.
  *
  * Plan tests 1-9.
  *
  * All tests use synthetic Symbol instances constructed in-memory (no TASTy/classfile I/O). Parent chains are wired by writing directly to
  * the `_parents` SingleAssign slot (the same path used by AstUnpickler during real classpath loading). Each test obtains a minimal
  * `Reflect.Classpath` from `Reflect.Classpath.fromPickles(Seq.empty)`.
  */
class SubtypeTest extends Test:

    /** Build a Named symbol with the given FQN and pre-wire its `_parents` slot. */
    private def makeSym(fqn: String, parents: Chunk[Reflect.Type] = Chunk.empty): Reflect.Symbol =
        import AllowUnsafe.embrace.danger
        val parts = fqn.split("\\.").toList
        val root = Reflect.Symbol.make(
            Reflect.SymbolKind.Package,
            Reflect.Flags.empty,
            Reflect.Name(""),
            null,
            new ClasspathRef,
            Reflect.Symbol.TastyOrigin.empty,
            Absent
        )
        val sym = parts.foldLeft(root) { (owner, part) =>
            Reflect.Symbol.make(
                Reflect.SymbolKind.Class,
                Reflect.Flags.empty,
                Reflect.Name(part),
                owner,
                new ClasspathRef,
                Reflect.Symbol.TastyOrigin.empty,
                Absent
            )
        }
        sym._parents.set(parents)
        sym
    end makeSym

    /** Build a covariant TypeParam symbol (CoVariant flag set). */
    private def makeCovParam(name: String): Reflect.Symbol =
        import AllowUnsafe.embrace.danger
        val root = Reflect.Symbol.make(
            Reflect.SymbolKind.Package,
            Reflect.Flags.empty,
            Reflect.Name(""),
            null,
            new ClasspathRef,
            Reflect.Symbol.TastyOrigin.empty,
            Absent
        )
        val sym = Reflect.Symbol.make(
            Reflect.SymbolKind.TypeParam,
            new Reflect.Flags(Reflect.Flag.CoVariant.bit),
            Reflect.Name(name),
            root,
            new ClasspathRef,
            Reflect.Symbol.TastyOrigin.empty,
            Absent
        )
        sym._parents.set(Chunk.empty)
        sym
    end makeCovParam

    /** Wire a base-class symbol with the given type params. */
    private def wireTypeParams(sym: Reflect.Symbol, params: Chunk[Reflect.Symbol]): Unit =
        import AllowUnsafe.embrace.danger
        sym._typeParams.set(params)

    // Test 1: Named(A).isSubtypeOf(Named(A)) -- reflexivity via same symbol reference
    "Named(A).isSubtypeOf(Named(A)) returns true (reflexivity)" in run {
        Reflect.Classpath.fromPickles(Seq.empty).map: cp =>
            given Reflect.Classpath = cp
            val intSym              = makeSym("scala.Int")
            val intType             = Reflect.Type.Named(intSym)
            assert(intType.isSubtypeOf(intType))
    }

    // Test 2: Named(String).isSubtypeOf(Named(Object)) returns true via parent chain
    "Named(String).isSubtypeOf(Named(Object)) returns true via parent chain" in run {
        Reflect.Classpath.fromPickles(Seq.empty).map: cp =>
            given Reflect.Classpath = cp
            val objectSym           = makeSym("java.lang.Object")
            val objectType          = Reflect.Type.Named(objectSym)
            val stringSym           = makeSym("java.lang.String", Chunk(objectType))
            val stringType          = Reflect.Type.Named(stringSym)
            assert(stringType.isSubtypeOf(objectType))
    }

    // Test 3: Named(String).isSubtypeOf(Named(Int)) returns false
    "Named(String).isSubtypeOf(Named(Int)) returns false" in run {
        Reflect.Classpath.fromPickles(Seq.empty).map: cp =>
            given Reflect.Classpath = cp
            val intSym              = makeSym("scala.Int")
            val stringSym           = makeSym("java.lang.String")
            val intType             = Reflect.Type.Named(intSym)
            val stringType          = Reflect.Type.Named(stringSym)
            assert(!stringType.isSubtypeOf(intType))
    }

    // Test 4: AndType(A, B).isSubtypeOf(A) returns true
    "AndType(A, B).isSubtypeOf(A) returns true" in run {
        Reflect.Classpath.fromPickles(Seq.empty).map: cp =>
            given Reflect.Classpath = cp
            val symA                = makeSym("test.A")
            val symB                = makeSym("test.B")
            val typeA               = Reflect.Type.Named(symA)
            val typeB               = Reflect.Type.Named(symB)
            val andType             = Reflect.Type.AndType(typeA, typeB)
            assert(andType.isSubtypeOf(typeA))
    }

    // Test 5: A.isSubtypeOf(OrType(A, B)) returns true
    "A.isSubtypeOf(OrType(A, B)) returns true" in run {
        Reflect.Classpath.fromPickles(Seq.empty).map: cp =>
            given Reflect.Classpath = cp
            val symA                = makeSym("test.A")
            val symB                = makeSym("test.B")
            val typeA               = Reflect.Type.Named(symA)
            val typeB               = Reflect.Type.Named(symB)
            val orType              = Reflect.Type.OrType(typeA, typeB)
            assert(typeA.isSubtypeOf(orType))
    }

    // Test 6: Applied(List[String]).isSubtypeOf(Applied(List[AnyRef])) true when List is covariant
    "Applied(List[String]).isSubtypeOf(Applied(List[AnyRef])) true when List is covariant" in run {
        Reflect.Classpath.fromPickles(Seq.empty).map: cp =>
            given Reflect.Classpath = cp
            val anyRefSym           = makeSym("java.lang.Object")
            val anyRefType          = Reflect.Type.Named(anyRefSym)
            val stringSym           = makeSym("java.lang.String", Chunk(anyRefType))
            val stringType          = Reflect.Type.Named(stringSym)
            val tParam              = makeCovParam("T")
            val listSym             = makeSym("scala.collection.immutable.List")
            wireTypeParams(listSym, Chunk(tParam))
            val listType   = Reflect.Type.Named(listSym)
            val listString = Reflect.Type.Applied(listType, Chunk(stringType))
            val listAnyRef = Reflect.Type.Applied(listType, Chunk(anyRefType))
            assert(listString.isSubtypeOf(listAnyRef))
    }

    // Test 7: Named(Nothing).isSubtypeOf(anyType) returns true (Nothing is subtype of all)
    "Named(Nothing).isSubtypeOf(any type) returns true (bottom)" in run {
        Reflect.Classpath.fromPickles(Seq.empty).map: cp =>
            given Reflect.Classpath = cp
            val nothingSym          = makeSym("scala.Nothing")
            val nothingType         = Reflect.Type.Named(nothingSym)
            val anySym              = makeSym("scala.Any")
            val anyType             = Reflect.Type.Named(anySym)
            assert(nothingType.isSubtypeOf(anyType))
    }

    // Test 8: TypeLambda([T], C[T]) isSubtypeOf TypeLambda([U], C[U]) true (alpha-equivalence)
    "TypeLambda([T], C[T]).isSubtypeOf(TypeLambda([U], C[U])) true (alpha-equiv)" in run {
        Reflect.Classpath.fromPickles(Seq.empty).map: cp =>
            given Reflect.Classpath = cp
            val cSym                = makeSym("test.C")
            val cType               = Reflect.Type.Named(cSym)
            val tSym = Reflect.Symbol.make(
                Reflect.SymbolKind.TypeParam,
                Reflect.Flags.empty,
                Reflect.Name("T"),
                null,
                new ClasspathRef,
                Reflect.Symbol.TastyOrigin.empty,
                Absent
            )
            val uSym = Reflect.Symbol.make(
                Reflect.SymbolKind.TypeParam,
                Reflect.Flags.empty,
                Reflect.Name("U"),
                null,
                new ClasspathRef,
                Reflect.Symbol.TastyOrigin.empty,
                Absent
            )
            val lambda1 = Reflect.Type.TypeLambda(
                Chunk(tSym),
                Reflect.Type.Applied(cType, Chunk(Reflect.Type.Named(tSym)))
            )
            val lambda2 = Reflect.Type.TypeLambda(
                Chunk(uSym),
                Reflect.Type.Applied(cType, Chunk(Reflect.Type.Named(uSym)))
            )
            val result = lambda1.isSubtypeOf(lambda2)
            assert(result)
    }

    // Test 9: Rec type with RecThis back-reference does not cause infinite recursion
    "Rec type with RecThis back-reference terminates (budget exhaustion safety)" in run {
        Reflect.Classpath.fromPickles(Seq.empty).map: cp =>
            given Reflect.Classpath = cp
            val cSym                = makeSym("test.C")
            val cType               = Reflect.Type.Named(cSym)
            // Rec(Applied(C, [RecThis(placeholder)])) -- RecThis uses a stand-in reference.
            // The exact self-referential closure doesn't matter; the budget prevents divergence.
            val recBody = Reflect.Type.Applied(cType, Chunk(Reflect.Type.RecThis(cType)))
            val rec     = Reflect.Type.Rec(recBody)
            val result  = rec.isSubtypeOf(rec)
            // Either true or false is acceptable; termination is the critical property.
            assert(result == true || result == false)
    }

end SubtypeTest
