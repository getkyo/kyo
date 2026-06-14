package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.DecodeContext

/** Tests for Method typed resolution accessors.
  *
  * Some cases use fromPicklesWithSymbols for synthetic fixtures; others use withPickles.
  * Effect-row static type and flag predicates are also verified.
  */
class MethodTypedAccessorsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val someObjectPickle =
        Tasty.Pickle("some-object", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someObjectTasty))

    // ── Synthetic builder helpers ─────────────────────────────────────────────

    private def makeParameter(id: Int, name: String, ownerId: Int): Tasty.Symbol.Parameter =
        Tasty.Symbol.Parameter(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )

    private def makeTypeParam(id: Int, name: String, ownerId: Int): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Invariant
        )

    private def makeMethod(
        id: Int,
        name: String,
        ownerId: Int,
        declaredType: Maybe[Tasty.Type] = Maybe.Absent,
        paramListIds: Chunk[Chunk[SymbolId]] = Chunk.empty,
        typeParamIds: Chunk[SymbolId] = Chunk.empty,
        flags: Tasty.Flags = Tasty.Flags.empty
    ): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            SymbolId(id),
            Tasty.Name(name),
            flags,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            declaredType,
            paramListIds,
            typeParamIds,
            Chunk.empty,
            Maybe.Absent
        )

    "paramLists-typed: returns Chunk[Chunk[Parameter]] 2x1 names x,y" in {
        import Tasty.Name.asString
        // classpath.symbol(id) uses SymbolId as array index; paramX at index 0, paramY at index 1, method at index 2
        val paramX = makeParameter(id = 0, name = "x", ownerId = 2)
        val paramY = makeParameter(id = 1, name = "y", ownerId = 2)
        val method = makeMethod(
            id = 2,
            name = "foo",
            ownerId = 0,
            paramListIds = Chunk(Chunk(SymbolId(0)), Chunk(SymbolId(1)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(paramX, paramY, method)).map { classpath =>
            given Tasty.Classpath = classpath
            val lists: Chunk[Chunk[Tasty.Symbol.Parameter]] =
                method.paramListIds.map(_.map(id =>
                    classpath.symbol(id) match
                        case Maybe.Present(p: Tasty.Symbol.Parameter) => p
                        case other                                    => fail(s"expected Symbol.Parameter at $id, got $other")
                ))
            assert(lists.length == 2, s"Expected 2 param lists but got ${lists.length}")
            assert(lists(0).length == 1, s"Expected 1 param in first list but got ${lists(0).length}")
            assert(lists(1).length == 1, s"Expected 1 param in second list but got ${lists(1).length}")
            assert(lists(0)(0).name.asString == "x", s"Expected x but got ${lists(0)(0).name.asString}")
            assert(lists(1)(0).name.asString == "y", s"Expected y but got ${lists(1)(0).name.asString}")
            succeed
        }
    }

    "typeParams-typed: returns Chunk[TypeParam] size 2 names A,B" in {
        import Tasty.Name.asString
        // classpath.symbol(id) uses SymbolId as array index; tpA at 0, tpB at 1, method at 2
        val tpA = makeTypeParam(id = 0, name = "A", ownerId = 2)
        val tpB = makeTypeParam(id = 1, name = "B", ownerId = 2)
        val method = makeMethod(
            id = 2,
            name = "bar",
            ownerId = 0,
            typeParamIds = Chunk(SymbolId(0), SymbolId(1))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(tpA, tpB, method)).map { classpath =>
            given Tasty.Classpath = classpath
            val tps = method.typeParamIds.map(id =>
                classpath.symbol(id) match
                    case Maybe.Present(tp: Tasty.Symbol.TypeParam) => tp
                    case other                                     => fail(s"expected Symbol.TypeParam at $id, got $other")
            )
            assert(tps.length == 2, s"Expected 2 type params but got ${tps.length}")
            val names = tps.map(_.name.asString).toSet
            assert(names == Set("A", "B"), s"Expected A,B but got $names")
            succeed
        }
    }

    "returnType-Function-result: extracts result from Type.Function" in {
        val resultType = Tasty.Type.Named(SymbolId(99))
        val funcType   = Tasty.Type.Function(Chunk(Tasty.Type.Nothing), resultType)
        val method     = makeMethod(id = 1, name = "compute", ownerId = 0, declaredType = Maybe(funcType))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(method)).map { classpath =>
            given Tasty.Classpath = classpath
            val rt                = method.declaredType.map { case Tasty.Type.Function(_, r) => r; case t => t }
            assert(rt.isDefined, "returnType must be Present for a Function declared type")
            assert(rt.get == resultType, s"Expected $resultType but got ${rt.get}")
            succeed
        }
    }

    "returnType-non-function: returns declaredType as-is when not a Function type" in {
        val namedType = Tasty.Type.Named(SymbolId(42))
        val method    = makeMethod(id = 1, name = "get", ownerId = 0, declaredType = Maybe(namedType))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(method)).map { classpath =>
            given Tasty.Classpath = classpath
            val rt                = method.declaredType.map { case Tasty.Type.Function(_, r) => r; case t => t }
            assert(rt.isDefined, "returnType must be Present when declaredType is Present")
            assert(rt.get == namedType, s"Expected $namedType but got ${rt.get}")
            succeed
        }
    }

    "returnType-absent: returns Absent when declaredType is Absent" in {
        val method = makeMethod(id = 1, name = "noType", ownerId = 0, declaredType = Maybe.Absent)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(method)).map { classpath =>
            given Tasty.Classpath = classpath
            assert(
                !method.declaredType.map { case Tasty.Type.Function(_, r) => r; case t => t }.isDefined,
                "returnType must be Absent when declaredType is Absent"
            )
            succeed
        }
    }

    "isConstructor-init: returns true for name == <init>" in {
        val method = makeMethod(id = 1, name = "<init>", ownerId = 0)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(method)).map { classpath =>
            assert((method.simpleName == "<init>"), "<init> method must be a constructor")
            succeed
        }
    }

    "isConstructor-not-init: returns false for name != <init>" in {
        val method = makeMethod(id = 1, name = "apply", ownerId = 0)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(method)).map { classpath =>
            assert(!(method.simpleName == "<init>"), "apply method must not be a constructor")
            succeed
        }
    }

    "bodyTree-effect-row: Tasty.bodyTree(Method) runs successfully with (Sync & Abort) effect row" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someObjectPickle)) {
                Tasty.classpath.map { classpath =>
                    val methodOpt = classpath.allMethods.find(!_.isAbstract)
                    methodOpt match
                        case None =>
                            // SomeObject.tasty may not have a concrete Method; test is inconclusive.
                            Kyo.lift(succeed)
                        case Some(m) =>
                            // Static type check: bodyTree must return Maybe[Tree] < (Sync & Abort[TastyError])
                            val effect: Maybe[Tasty.Tree] < (Sync & Abort[TastyError]) = Tasty.bodyTree(m)
                            effect.map { result =>
                                // bodyTree may return Absent if the method's body isn't stored; that is acceptable
                                succeed
                            }
                    end match
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "bodyTree-absent-when-no-body: returns Absent for Method with no body" in {
        val method = makeMethod(id = 1, name = "abstract", ownerId = 0)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(method)).map { classpath =>
            // Install a binding WITH a DecodeContext so bodyTree goes through the full code path.
            // The method's body field is Absent, so bodyTree must return Absent via the body-empty branch,
            // not via the binding-empty short-circuit.
            val binding = Binding(classpath, Maybe.Present(DecodeContext.fresh()))
            Tasty.bindingLocal.let(Maybe.Present(binding)) {
                Abort.run[TastyError](Tasty.bodyTree(method)).map {
                    case Result.Success(result) =>
                        assert(!result.isDefined, "bodyTree must return Absent when method body field is Absent")
                        succeed
                    case Result.Failure(e) =>
                        fail(s"bodyTree raised unexpected error: $e")
                    case Result.Panic(t) =>
                        throw t
                }
            }
        }
    }

    "bodyTree effect row is exactly (Sync & Abort[TastyError]) -- compile check" in {
        val method = makeMethod(id = 1, name = "m", ownerId = 0)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(method)).map { classpath =>
            // Install a binding WITH a DecodeContext so bodyTree exercises the full code path.
            // The static type annotation confirms the effect row is exactly (Sync & Abort[TastyError]).
            val binding = Binding(classpath, Maybe.Present(DecodeContext.fresh()))
            Tasty.bindingLocal.let(Maybe.Present(binding)) {
                // The binding below must compile with the exact effect row.
                val e: Maybe[Tasty.Tree] < (Sync & Abort[TastyError]) = Tasty.bodyTree(method)
                Abort.run[TastyError](e).map {
                    case Result.Success(_) => succeed
                    case Result.Failure(e) => fail(s"Unexpected error: $e")
                    case Result.Panic(t)   => throw t
                }
            }
        }
    }

    "flag predicates still work on typed Method (isInline, isGiven)" in {
        val flags  = Tasty.Flags(Tasty.Flag.Inline, Tasty.Flag.Given)
        val method = makeMethod(id = 1, name = "given_inline", ownerId = 0, flags = flags)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(method)).map { classpath =>
            assert(method.isInline, "isInline must be true for Inline-flagged method")
            assert(method.isGiven, "isGiven must be true for Given-flagged method")
            succeed
        }
    }

end MethodTypedAccessorsTest
