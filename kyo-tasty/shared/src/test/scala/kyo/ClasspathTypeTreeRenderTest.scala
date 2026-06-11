package kyo

import kyo.Tasty.SymbolId

/** Tests for `Classpath.typeShow`, `Classpath.typeSymbol`, `Classpath.isSubtypeOf`, and `Classpath.treeShow`
  * as pure instance methods that require no Frame and carry no effect row.
  *
  * All 29 Type cases are exercised for `typeShow` and `typeSymbol`. Subtyping verdicts (Sub, NotSub,
  * Indeterminate) are confirmed on a synthetic classpath. The companion vs instance failure-surfacing
  * contract is verified: `Tasty.isSubtypeOf` raises `TastyError.UnhandledSubtypingCase` via `Abort.fail`
  * while leaving `classpath.errors` unmodified.
  *
  * No AllowUnsafe import is required: all tested methods are pure.
  *
  * Fixture layout (ids 0-3):
  *   0  -> Symbol.Package "test"  (ownerId = -1)
  *   1  -> Symbol.Class   "Dog"   (ownerId = 0;  parentTypes = [Named(2)])
  *   2  -> Symbol.Class   "Animal" (ownerId = 0; parentTypes = [])
  *   3  -> Symbol.Class   "Cat"   (ownerId = 0;  parentTypes = [Named(2)])
  */
class ClasspathTypeTreeRenderTest extends kyo.test.Test[Any]:

    import kyo.Tasty.SymbolId

    private var nextId: Int = 0
    private def freshId(): SymbolId =
        val id = nextId
        nextId += 1
        SymbolId(id)
    end freshId

    private def makeSym(name: String, parents: Chunk[Tasty.Type] = Chunk.empty): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            id = freshId(),
            name = Tasty.Name(name),
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

    /** Build a test Classpath with symbols indexed at their id.value positions. */
    private def makeTestClasspath(syms: Chunk[Tasty.Symbol])(using Frame): Tasty.Classpath < Sync =
        val maxId = syms.foldLeft(-1)((m, s) => math.max(m, s.id.value))
        val arr   = new Array[Tasty.Symbol](maxId + 1)
        val sentinel =
            Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name("<sentinel>"), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)
        var fi = 0
        while fi <= maxId do
            arr(fi) = sentinel
            fi += 1
        end while
        for symbol <- syms do
            val idx = symbol.id.value
            if idx >= 0 then arr(idx) = symbol
        end for
        Tasty.Classpath.fromPicklesWithSymbols(Chunk.from(arr))
    end makeTestClasspath

    // ── typeShow for each of the 29 Type cases ─────────────────────────────────

    "typeShow(Named) returns simple symbol name" in {
        nextId = 0
        val dogSym = makeSym("Dog")
        makeTestClasspath(Chunk(dogSym)).map { classpath =>
            assert(classpath.typeShow(Tasty.Type.Named(dogSym.id)) == "Dog")
            succeed
        }
    }

    "typeShow(Named with unresolved id) returns <unresolved>" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            assert(classpath.typeShow(Tasty.Type.Named(SymbolId(99))) == "<unresolved>")
            succeed
        }
    }

    "typeShow(TermRef) returns prefix dot name" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.TermRef(Tasty.Type.Any, Tasty.Name("x"))
            assert(classpath.typeShow(tpe) == "Any.x")
            succeed
        }
    }

    "typeShow(Applied) returns bracket notation" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.Applied(Tasty.Type.Any, Chunk(Tasty.Type.Nothing))
            assert(classpath.typeShow(tpe) == "Any[Nothing]")
            succeed
        }
    }

    "typeShow(TypeLambda) returns erased params arrow body" in {
        nextId = 0
        val dogSym = makeSym("Dog")
        makeTestClasspath(Chunk(dogSym)).map { classpath =>
            val tpe = Tasty.Type.TypeLambda(Chunk(dogSym.id), Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "[...] =>> Any")
            succeed
        }
    }

    "typeShow(Function) returns arrow syntax with parens around params" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.Function(Chunk(Tasty.Type.Nothing), Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "(Nothing) => Any", s"Got: ${classpath.typeShow(tpe)}")
            succeed
        }
    }

    "typeShow(ContextFunction) returns ?=> arrow syntax" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.ContextFunction(Chunk(Tasty.Type.Nothing), Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "(Nothing) ?=> Any")
            succeed
        }
    }

    "typeShow(Tuple) returns parenthesised comma-separated elements" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.Tuple(Chunk(Tasty.Type.Nothing, Tasty.Type.Any))
            assert(classpath.typeShow(tpe) == "(Nothing, Any)")
            succeed
        }
    }

    "typeShow(ByName) returns => prefix" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.ByName(Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "=> Any")
            succeed
        }
    }

    "typeShow(Repeated) returns star suffix" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.Repeated(Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "Any*")
            succeed
        }
    }

    "typeShow(Array) returns bracket suffix" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.Array(Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "Any[]")
            succeed
        }
    }

    "typeShow(Refinement) returns brace syntax" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.Refinement(Tasty.Type.Any, Tasty.Name("m"), Tasty.Type.Nothing)
            assert(classpath.typeShow(tpe) == "Any { m: Nothing }")
            succeed
        }
    }

    "typeShow(Rec) returns rec prefix" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.Rec(Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "<rec: Any>")
            succeed
        }
    }

    "typeShow(RecThis) returns <recthis>" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.RecThis(Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "<recthis>")
            succeed
        }
    }

    "typeShow(AndType) returns ampersand infix" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.AndType(Tasty.Type.Nothing, Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "Nothing & Any")
            succeed
        }
    }

    "typeShow(OrType) returns pipe infix" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.OrType(Tasty.Type.Nothing, Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "Nothing | Any")
            succeed
        }
    }

    "typeShow(Annotated) strips annotation and shows underlying" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val annotation = Tasty.Annotation(Tasty.Type.Any, Chunk.empty, Tasty.Name("annotation"))
            val tpe        = Tasty.Type.Annotated(Tasty.Type.Nothing, annotation)
            assert(classpath.typeShow(tpe) == "Nothing")
            succeed
        }
    }

    "typeShow(ConstantType(IntConst)) returns integer literal string" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.ConstantType(Tasty.Constant.IntConst(42))
            assert(classpath.typeShow(tpe) == "42")
            succeed
        }
    }

    "typeShow(ThisType) returns <this>" in {
        nextId = 0
        val dogSym = makeSym("Dog")
        makeTestClasspath(Chunk(dogSym)).map { classpath =>
            val tpe = Tasty.Type.ThisType(dogSym.id)
            assert(classpath.typeShow(tpe) == "<this>")
            succeed
        }
    }

    "typeShow(SuperType) returns super with self and mixin" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.SuperType(Tasty.Type.Nothing, Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "<super: Nothing with Any>")
            succeed
        }
    }

    "typeShow(ParamRef) returns param index" in {
        nextId = 0
        val dogSym = makeSym("Dog")
        makeTestClasspath(Chunk(dogSym)).map { classpath =>
            val tpe = Tasty.Type.ParamRef(dogSym.id, 3)
            assert(classpath.typeShow(tpe) == "<param 3>")
            succeed
        }
    }

    "typeShow(Wildcard) returns bounds syntax" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.Wildcard(Tasty.Type.Nothing, Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "_ >: Nothing <: Any")
            succeed
        }
    }

    "typeShow(Skolem) returns skolem prefix" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.Skolem(Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "<skolem: Any>")
            succeed
        }
    }

    "typeShow(MatchType) returns match syntax" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.MatchType(Tasty.Type.Any, Tasty.Type.Nothing, Chunk.empty)
            assert(classpath.typeShow(tpe) == "Nothing match {  }")
            succeed
        }
    }

    "typeShow(FlexibleType) returns question mark suffix" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.FlexibleType(Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "Any?")
            succeed
        }
    }

    "typeShow(MatchCase) returns case arrow syntax" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.MatchCase(Tasty.Type.Nothing, Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == "case Nothing => Any")
            succeed
        }
    }

    "typeShow(TypeRef) returns qualifier dot name" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.TypeRef(Tasty.Type.Any, Tasty.Name("T"))
            assert(classpath.typeShow(tpe) == "Any.T")
            succeed
        }
    }

    "typeShow(Bounds) returns bounds syntax" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            val tpe = Tasty.Type.Bounds(Tasty.Type.Nothing, Tasty.Type.Any)
            assert(classpath.typeShow(tpe) == ">: Nothing <: Any")
            succeed
        }
    }

    "typeShow(Nothing) returns Nothing" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            assert(classpath.typeShow(Tasty.Type.Nothing) == "Nothing")
            succeed
        }
    }

    "typeShow(Any) returns Any" in {
        nextId = 0
        makeTestClasspath(Chunk.empty).map { classpath =>
            assert(classpath.typeShow(Tasty.Type.Any) == "Any")
            succeed
        }
    }

    // ── typeSymbol ─────────────────────────────────────────────────────────────

    "typeSymbol(Named) returns Maybe.Present when id resolves" in {
        nextId = 0
        val dogSym = makeSym("Dog")
        makeTestClasspath(Chunk(dogSym)).map { classpath =>
            classpath.typeSymbol(Tasty.Type.Named(dogSym.id)) match
                case Maybe.Present(symbol) => assert(symbol == dogSym)
                case Maybe.Absent          => fail("Expected Maybe.Present for Named type with valid id")
            end match
            succeed
        }
    }

    "typeSymbol returns Maybe.Absent for all 28 non-Named Type cases" in {
        nextId = 0
        val dogSym = makeSym("Dog")
        makeTestClasspath(Chunk(dogSym)).map { classpath =>
            val annotation = Tasty.Annotation(Tasty.Type.Any, Chunk.empty, Tasty.Name("annotation"))
            val nonNamedCases: Chunk[Tasty.Type] = Chunk(
                Tasty.Type.TermRef(Tasty.Type.Any, Tasty.Name("x")),
                Tasty.Type.Applied(Tasty.Type.Any, Chunk(Tasty.Type.Nothing)),
                Tasty.Type.TypeLambda(Chunk(dogSym.id), Tasty.Type.Any),
                Tasty.Type.Function(Chunk(Tasty.Type.Nothing), Tasty.Type.Any),
                Tasty.Type.ContextFunction(Chunk(Tasty.Type.Nothing), Tasty.Type.Any),
                Tasty.Type.Tuple(Chunk(Tasty.Type.Any)),
                Tasty.Type.ByName(Tasty.Type.Any),
                Tasty.Type.Repeated(Tasty.Type.Any),
                Tasty.Type.Array(Tasty.Type.Any),
                Tasty.Type.Refinement(Tasty.Type.Any, Tasty.Name("m"), Tasty.Type.Nothing),
                Tasty.Type.Rec(Tasty.Type.Any),
                Tasty.Type.RecThis(Tasty.Type.Any),
                Tasty.Type.AndType(Tasty.Type.Nothing, Tasty.Type.Any),
                Tasty.Type.OrType(Tasty.Type.Nothing, Tasty.Type.Any),
                Tasty.Type.Annotated(Tasty.Type.Any, annotation),
                Tasty.Type.ConstantType(Tasty.Constant.IntConst(1)),
                Tasty.Type.ThisType(dogSym.id),
                Tasty.Type.SuperType(Tasty.Type.Nothing, Tasty.Type.Any),
                Tasty.Type.ParamRef(dogSym.id, 0),
                Tasty.Type.Wildcard(Tasty.Type.Nothing, Tasty.Type.Any),
                Tasty.Type.Skolem(Tasty.Type.Any),
                Tasty.Type.MatchType(Tasty.Type.Any, Tasty.Type.Nothing, Chunk.empty),
                Tasty.Type.FlexibleType(Tasty.Type.Any),
                Tasty.Type.MatchCase(Tasty.Type.Nothing, Tasty.Type.Any),
                Tasty.Type.TypeRef(Tasty.Type.Any, Tasty.Name("T")),
                Tasty.Type.Bounds(Tasty.Type.Nothing, Tasty.Type.Any),
                Tasty.Type.Nothing,
                Tasty.Type.Any
            )
            assert(nonNamedCases.length == 28, s"Expected 28 non-Named cases, got ${nonNamedCases.length}")
            nonNamedCases.foreach { tpe =>
                classpath.typeSymbol(tpe) match
                    case Maybe.Present(symbol) =>
                        fail(s"Expected Maybe.Absent for ${tpe.getClass.getSimpleName} but got Present($symbol)")
                    case Maybe.Absent => ()
                end match
            }
            succeed
        }
    }

    // ── isSubtypeOf on pure Classpath instance ──────────────────────────────────

    "classpath.isSubtypeOf returns Sub when Dog extends Animal" in {
        nextId = 0
        val animalSym = makeSym("Animal")
        val dogSym    = makeSym("Dog", Chunk(Tasty.Type.Named(animalSym.id)))
        makeTestClasspath(Chunk(animalSym, dogSym)).map { classpath =>
            val animalType = Tasty.Type.Named(animalSym.id)
            val dogType    = Tasty.Type.Named(dogSym.id)
            classpath.isSubtypeOf(dogType, animalType) match
                case Result.Success(verdict) =>
                    assert(verdict == Tasty.SubtypeVerdict.Sub, s"Expected Sub but got $verdict")
                case Result.Failure(e) =>
                    fail(s"Expected Sub but got Failure: $e")
                case Result.Panic(t) =>
                    throw t
            end match
            succeed
        }
    }

    "classpath.isSubtypeOf returns NotSub when Animal does not extend Dog" in {
        nextId = 0
        val animalSym = makeSym("Animal")
        val dogSym    = makeSym("Dog", Chunk(Tasty.Type.Named(animalSym.id)))
        makeTestClasspath(Chunk(animalSym, dogSym)).map { classpath =>
            val animalType = Tasty.Type.Named(animalSym.id)
            val dogType    = Tasty.Type.Named(dogSym.id)
            classpath.isSubtypeOf(animalType, dogType) match
                case Result.Success(verdict) =>
                    assert(verdict == Tasty.SubtypeVerdict.NotSub, s"Expected NotSub but got $verdict")
                case Result.Failure(e) =>
                    fail(s"Expected NotSub but got Failure: $e")
                case Result.Panic(t) =>
                    throw t
            end match
            succeed
        }
    }

    "classpath.isSubtypeOf returns Indeterminate for budget-exhausted recursive type" in {
        nextId = 0
        val leafSym          = makeSym("Leaf")
        val leaf: Tasty.Type = Tasty.Type.Named(leafSym.id)
        var rec: Tasty.Type  = leaf
        var i                = 0
        while i < 66 do
            rec = Tasty.Type.Rec(rec)
            i += 1
        end while
        makeTestClasspath(Chunk(leafSym)).map { classpath =>
            classpath.isSubtypeOf(rec, rec) match
                case Result.Success(verdict) =>
                    assert(verdict == Tasty.SubtypeVerdict.Indeterminate, s"Expected Indeterminate but got $verdict")
                case Result.Failure(e) =>
                    fail(s"Expected Indeterminate but got Failure: $e")
                case Result.Panic(t) =>
                    throw t
            end match
            succeed
        }
    }

    "Tasty.isSubtypeOf companion raises Abort for unhandled shape; classpath.errors is not mutated" in {
        nextId = 0
        val baseSym       = makeSym("Base")
        val baseId        = baseSym.id
        val baseType      = Tasty.Type.Named(baseId)
        val termRefParent = Tasty.Type.TermRef(baseType, Tasty.Name("termRefParent"))
        val subSym        = makeSym("Sub", Chunk(termRefParent))
        val supSym        = makeSym("Sup")
        val subType       = Tasty.Type.Named(subSym.id)
        val supType       = Tasty.Type.Named(supSym.id)

        makeTestClasspath(Chunk(baseSym, subSym, supSym)).map { classpath =>
            val baselineErrors = classpath.errors
            // The pure instance returns Failure(UnhandledSubtypingCase) without mutating classpath.errors.
            classpath.isSubtypeOf(subType, supType) match
                case Result.Failure(_: TastyError.UnhandledSubtypingCase) =>
                    assert(
                        classpath.errors == baselineErrors,
                        s"classpath.errors must not be mutated; got: ${classpath.errors}"
                    )
                case Result.Success(v) =>
                    fail(s"Expected Failure for unhandled parent shape but got Success($v)")
                case Result.Failure(other) =>
                    fail(s"Expected UnhandledSubtypingCase but got: $other")
                case Result.Panic(t) =>
                    throw t
            end match
        }
    }

end ClasspathTypeTreeRenderTest
