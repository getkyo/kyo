package kyo

import kyo.Tasty.SymbolId
import kyo.internal.TestClasspaths

/** Tests for the classpath.paramLists pure instance helper.
  *
  * Verifies that paramListIds are resolved correctly into per-list-group Symbol.Parameter entries,
  * including edge cases: no-arg methods, multi-list methods, extension method receivers,
  * broken ids, empty clauses, and independence from typeParamIds.
  */
class ParamListsHelperTest extends kyo.test.Test[Any]:

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
            Maybe.Absent,
            paramListIds,
            typeParamIds,
            Chunk.empty,
            Maybe.Absent
        )

    "classpath.paramLists for no-arg method returns Chunk.empty" in {
        val method = makeMethod(id = 0, name = "noArg", ownerId = 0, paramListIds = Chunk.empty)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(method)).map { classpath =>
            val result = classpath.paramLists(method)
            assert(result.size == 0, s"Expected empty Chunk but got size ${result.size}")
        }
    }

    "classpath.paramLists for single-list method returns Chunk(Chunk(p1, p2))" in {
        import Tasty.Name.asString
        // classpath.symbol(id) uses SymbolId as array index: paramA at 0, paramB at 1, method at 2
        val paramA = makeParameter(id = 0, name = "a", ownerId = 2)
        val paramB = makeParameter(id = 1, name = "b", ownerId = 2)
        val method = makeMethod(
            id = 2,
            name = "twoParams",
            ownerId = 0,
            paramListIds = Chunk(Chunk(SymbolId(0), SymbolId(1)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(paramA, paramB, method)).map { classpath =>
            val result = classpath.paramLists(method)
            assert(result.size == 1, s"Expected outer size 1 but got ${result.size}")
            assert(result.head.size == 2, s"Expected inner size 2 but got ${result.head.size}")
            assert(
                result.head.map(_.name.asString) == Chunk("a", "b"),
                s"Expected Chunk(a, b) but got ${result.head.map(_.name.asString)}"
            )
        }
    }

    "classpath.paramLists for extension method: head.head resolves to receiver type" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                    classpath.companion(opaqueMeters) match
                        case Maybe.Present(companion: Tasty.Symbol.Object) =>
                            val metersValueExtensions = companion.declarationIds.flatMap { id =>
                                classpath.symbol(id) match
                                    case Maybe.Present(method: Tasty.Symbol.Method)
                                        if method.name.asString == "value" && method.isExtension =>
                                        Chunk(method)
                                    case _ => Chunk.empty
                            }
                            metersValueExtensions.headOption match
                                case Some(valueExt) =>
                                    val result = classpath.paramLists(valueExt)
                                    assert(result.nonEmpty, "paramLists result is empty; expected at least one list")
                                    assert(result.head.nonEmpty, "paramLists.head is empty; expected receiver parameter")
                                    val receiver = result.head.head
                                    receiver.declaredType match
                                        case Maybe.Present(receiverType) =>
                                            assert(
                                                classpath.typeSymbol(receiverType) == Maybe.Present(opaqueMeters),
                                                s"Expected receiver type to resolve to Meters OpaqueType but got ${classpath.typeSymbol(receiverType)}"
                                            )
                                        case Maybe.Absent =>
                                            fail("receiver parameter has no declaredType")
                                    end match
                                case None =>
                                    fail("value extension method not found in Meters companion declarations")
                            end match
                        case Maybe.Present(other) =>
                            fail(s"Expected Object companion but got ${other.getClass.getSimpleName}")
                        case Maybe.Absent =>
                            fail("classpath.companion(opaqueMeters) returned Absent")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.fixtures.Meters but got ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.fixtures.Meters not found; check fixture setup")
        }
    }

    "classpath.paramLists drops broken SymbolId and records UnresolvedReference" in {
        import Tasty.Name.asString
        // paramA at index 0; method at index 1; SymbolId(-1) is out-of-range and resolves to Absent.
        val paramA = makeParameter(id = 0, name = "a", ownerId = 1)
        val method = makeMethod(
            id = 1,
            name = "brokenRef",
            ownerId = 0,
            paramListIds = Chunk(Chunk(SymbolId(0), SymbolId(-1)))
        )
        // Pre-populate errors to model a broken-reference condition from load time.
        val cpWithError = Tasty.Classpath(
            symbols = Chunk(paramA, method),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk(TastyError.UnresolvedReference("brokenRef", -1)),
            modules = Chunk.empty,
            rootSymbolId = SymbolId(0)
        )
        val result = cpWithError.paramLists(method)
        assert(result.size == 1, s"Expected outer size 1 but got ${result.size}")
        assert(
            result.head.size == 1,
            s"Expected 1 parameter after dropping broken id, but got ${result.head.size}"
        )
        assert(
            result.head.head.name.asString == "a",
            s"Expected kept parameter name 'a' but got '${result.head.head.name.asString}'"
        )
        assert(
            cpWithError.errors.exists(_.isInstanceOf[TastyError.UnresolvedReference]),
            s"Expected UnresolvedReference in classpath.errors but got ${cpWithError.errors}"
        )
    }

    "classpath.paramLists preserves empty clause shape" in {
        import Tasty.Name.asString
        // paramA at index 0; method at index 1
        val paramA = makeParameter(id = 0, name = "a", ownerId = 1)
        val method = makeMethod(
            id = 1,
            name = "emptyThenParam",
            ownerId = 0,
            paramListIds = Chunk(Chunk.empty, Chunk(SymbolId(0)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(paramA, method)).map { classpath =>
            val result = classpath.paramLists(method)
            assert(result.size == 2, s"Expected outer size 2 but got ${result.size}")
            assert(
                result.head == Chunk.empty,
                s"Expected first list to be Chunk.empty but got ${result.head}"
            )
            assert(result.last.size == 1, s"Expected last list size 1 but got ${result.last.size}")
            assert(
                result.last.head.name.asString == "a",
                s"Expected param name 'a' but got '${result.last.head.name.asString}'"
            )
        }
    }

    "classpath.typeParams and Tasty.paramLists are independent (regression guard)" in {
        import Tasty.Name.asString
        // typeParamB at 0, paramSelf at 1, paramF at 2, method at 3
        val typeParamB = makeTypeParam(id = 0, name = "B", ownerId = 3)
        val paramSelf  = makeParameter(id = 1, name = "self", ownerId = 3)
        val paramF     = makeParameter(id = 2, name = "f", ownerId = 3)
        val method = makeMethod(
            id = 3,
            name = "map",
            ownerId = 0,
            paramListIds = Chunk(Chunk(SymbolId(1)), Chunk(SymbolId(2))),
            typeParamIds = Chunk(SymbolId(0))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(typeParamB, paramSelf, paramF, method)).map { classpath =>
            val typeParams = classpath.typeParams(method)
            val paramLists = classpath.paramLists(method)
            assert(typeParams.size == 1, s"Expected 1 type param but got ${typeParams.size}")
            assert(paramLists.size == 2, s"Expected 2 param lists but got ${paramLists.size}")
            assert(
                typeParams.head.name.asString == "B",
                s"Expected type param name 'B' but got '${typeParams.head.name.asString}'"
            )
        }
    }

end ParamListsHelperTest
