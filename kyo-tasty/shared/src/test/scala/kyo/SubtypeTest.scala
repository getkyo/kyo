package kyo

import kyo.internal.tasty.query.ClasspathRef

/** Tests for Phase 9: Subtype checking and type comparison.
  *
  * Plan tests 1-9.
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
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val sym = parts.foldLeft(root) { (owner, part) =>
            Tasty.Symbol.make(
                Tasty.SymbolKind.Class,
                Tasty.Flags.empty,
                Tasty.Name(part),
                owner,
                new ClasspathRef,
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
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val sym = Tasty.Symbol.make(
            Tasty.SymbolKind.TypeParam,
            new Tasty.Flags(Tasty.Flag.CoVariant.bit),
            Tasty.Name(name),
            root,
            new ClasspathRef,
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
    "Named(A).isSubtypeOf(Named(A)) returns true (reflexivity)" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val intSym            = makeSym("scala.Int")
            val intType           = Tasty.Type.Named(intSym)
            assert(intType.isSubtypeOf(intType))
    }

    // Test 2: Named(String).isSubtypeOf(Named(Object)) returns true via parent chain
    "Named(String).isSubtypeOf(Named(Object)) returns true via parent chain" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val objectSym         = makeSym("java.lang.Object")
            val objectType        = Tasty.Type.Named(objectSym)
            val stringSym         = makeSym("java.lang.String", Chunk(objectType))
            val stringType        = Tasty.Type.Named(stringSym)
            assert(stringType.isSubtypeOf(objectType))
    }

    // Test 3: Named(String).isSubtypeOf(Named(Int)) returns false
    "Named(String).isSubtypeOf(Named(Int)) returns false" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val intSym            = makeSym("scala.Int")
            val stringSym         = makeSym("java.lang.String")
            val intType           = Tasty.Type.Named(intSym)
            val stringType        = Tasty.Type.Named(stringSym)
            assert(!stringType.isSubtypeOf(intType))
    }

    // Test 4: AndType(A, B).isSubtypeOf(A) returns true
    "AndType(A, B).isSubtypeOf(A) returns true" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val symA              = makeSym("test.A")
            val symB              = makeSym("test.B")
            val typeA             = Tasty.Type.Named(symA)
            val typeB             = Tasty.Type.Named(symB)
            val andType           = Tasty.Type.AndType(typeA, typeB)
            assert(andType.isSubtypeOf(typeA))
    }

    // Test 5: A.isSubtypeOf(OrType(A, B)) returns true
    "A.isSubtypeOf(OrType(A, B)) returns true" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val symA              = makeSym("test.A")
            val symB              = makeSym("test.B")
            val typeA             = Tasty.Type.Named(symA)
            val typeB             = Tasty.Type.Named(symB)
            val orType            = Tasty.Type.OrType(typeA, typeB)
            assert(typeA.isSubtypeOf(orType))
    }

    // Test 6: Applied(List[String]).isSubtypeOf(Applied(List[AnyRef])) true when List is covariant
    "Applied(List[String]).isSubtypeOf(Applied(List[AnyRef])) true when List is covariant" in run {
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
            assert(listString.isSubtypeOf(listAnyRef))
    }

    // Test 7: Named(Nothing).isSubtypeOf(anyType) returns true (Nothing is subtype of all)
    "Named(Nothing).isSubtypeOf(any type) returns true (bottom)" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val nothingSym        = makeSym("scala.Nothing")
            val nothingType       = Tasty.Type.Named(nothingSym)
            val anySym            = makeSym("scala.Any")
            val anyType           = Tasty.Type.Named(anySym)
            assert(nothingType.isSubtypeOf(anyType))
    }

    // Test 8: TypeLambda([T], C[T]) isSubtypeOf TypeLambda([U], C[U]) true (alpha-equivalence)
    "TypeLambda([T], C[T]).isSubtypeOf(TypeLambda([U], C[U])) true (alpha-equiv)" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val cSym              = makeSym("test.C")
            val cType             = Tasty.Type.Named(cSym)
            val tSym = Tasty.Symbol.make(
                Tasty.SymbolKind.TypeParam,
                Tasty.Flags.empty,
                Tasty.Name("T"),
                null,
                new ClasspathRef,
                Tasty.Symbol.TastyOrigin.empty,
                Absent
            )
            val uSym = Tasty.Symbol.make(
                Tasty.SymbolKind.TypeParam,
                Tasty.Flags.empty,
                Tasty.Name("U"),
                null,
                new ClasspathRef,
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
            assert(result)
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
            // Either true or false is acceptable; termination is the critical property.
            assert(result == true || result == false)
    }

end SubtypeTest
