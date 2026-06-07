package kyo

import kyo.Tasty.SymbolId
import kyo.internal.TestClasspaths

/** Tests for the Tasty.paramLists public helper (Phase 04).
  *
  * The helper resolves a Symbol.Method's paramListIds into per-list-group Symbol.Parameter
  * entries. These leaves pin INV-H3 (locked signature) and INV-H6 (positional receiver rule).
  *
  * Leaf summary:
  *   6.  zero_arg_method: Tasty.paramLists on a no-arg accessor returns Chunk.empty.
  *   7.  single_list_two_params: Tasty.paramLists on a 1-list 2-param method returns
  *       Chunk(Chunk(p1, p2)).
  *   8.  extension_receiver_positional: Tasty.paramLists on the Meters.value extension
  *       returns a result where head.head is the receiver parameter (INV-H6 positional rule).
  *   9.  broken_id_dropped: a broken SymbolId in paramListIds is dropped from the result
  *       and an UnresolvedReference is recorded in cp.errors.
  *   10. empty_clause_positional: a method modelled as def f()(a: A): Unit produces
  *       Chunk(Chunk.empty, Chunk(a)) preserving the empty-clause shape.
  *   11. typeParams_regression: typeParams and paramLists are independent; paramLists does
  *       not consume typeParamIds.
  *
  * Pins: INV-H3 (Tasty.paramLists locked signature) and INV-H6 (positional receiver rule).
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

    // ── Leaf 6: zero_arg_method ───────────────────────────────────────────────
    // Given: a Symbol.Method representing a no-arg accessor (no parameter lists).
    // When: Tasty.paramLists(method) is called.
    // Then: result equals Chunk.empty (.size == 0).
    // Pins: INV-H3 helper signature behaviour on zero parameter lists.
    "Tasty.paramLists for no-arg method returns Chunk.empty (INV-H3)" in {
        val method = makeMethod(id = 0, name = "noArg", ownerId = 0, paramListIds = Chunk.empty)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(method)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.paramLists(method).map: result =>
                    assert(result.size == 0, s"Expected empty Chunk but got size ${result.size}")
                    succeed
    }

    // ── Leaf 7: single_list_two_params ────────────────────────────────────────
    // Given: a Symbol.Method with paramListIds = Chunk(Chunk(idA, idB)) where idA and idB
    //        point to Symbol.Parameter entries named "a" and "b".
    // When: Tasty.paramLists(method) is called.
    // Then: result.size == 1 AND result.head.size == 2
    //       AND result.head.map(_.name.asString) == Chunk("a", "b").
    // Pins: INV-H3 helper signature on a single parameter list.
    "Tasty.paramLists for single-list method returns Chunk(Chunk(p1, p2)) (INV-H3)" in {
        import Tasty.Name.asString
        // cp.symbol(id) uses SymbolId as array index: paramA at 0, paramB at 1, method at 2
        val paramA = makeParameter(id = 0, name = "a", ownerId = 2)
        val paramB = makeParameter(id = 1, name = "b", ownerId = 2)
        val method = makeMethod(
            id = 2,
            name = "twoParams",
            ownerId = 0,
            paramListIds = Chunk(Chunk(SymbolId(0), SymbolId(1)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(paramA, paramB, method)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.paramLists(method).map: result =>
                    assert(result.size == 1, s"Expected outer size 1 but got ${result.size}")
                    assert(result.head.size == 2, s"Expected inner size 2 but got ${result.head.size}")
                    assert(
                        result.head.map(_.name.asString) == Chunk("a", "b"),
                        s"Expected Chunk(a, b) but got ${result.head.map(_.name.asString)}"
                    )
                    succeed
    }

    // ── Leaf 8: extension_receiver_positional ─────────────────────────────────
    // Given: embedded Meters fixture loaded via TestClasspaths.withClasspath; valueExt is
    //        the Symbol.Method for def value: Double on kyo.fixtures.Meters.
    // When: Tasty.paramLists(valueExt) is invoked.
    // Then: result.head.head is a Symbol.Parameter whose declaredType resolves (via
    //       Tasty.typeSymbol) to the Meters OpaqueType symbol.
    // Pins: INV-H6 (positional receiver) and INV-H3 (helper result shape).
    "Tasty.paramLists for extension method: head.head resolves to receiver type (INV-H6)" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            cp.findSymbol("kyo.fixtures.Meters") match
                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                    cp.companion(opaqueMeters) match
                        case Maybe.Present(companion: Tasty.Symbol.Object) =>
                            val metersValueExtensions = companion.declarationIds.flatMap: id =>
                                cp.symbol(id) match
                                    case Maybe.Present(method: Tasty.Symbol.Method)
                                        if method.name.asString == "value" && method.isExtension =>
                                        Chunk(method)
                                    case _ => Chunk.empty
                            metersValueExtensions.headOption match
                                case Some(valueExt) =>
                                    Tasty.withClasspath(cp):
                                        Tasty.paramLists(valueExt).flatMap: result =>
                                            assert(result.nonEmpty, "paramLists result is empty; expected at least one list")
                                            assert(result.head.nonEmpty, "paramLists.head is empty; expected receiver parameter")
                                            val receiver = result.head.head
                                            receiver.declaredType match
                                                case Maybe.Present(receiverType) =>
                                                    Tasty.typeSymbol(receiverType).map: maybeResolved =>
                                                        assert(
                                                            maybeResolved == Maybe.Present(opaqueMeters),
                                                            s"Expected receiver type to resolve to Meters OpaqueType but got $maybeResolved"
                                                        )
                                                        succeed
                                                case Maybe.Absent =>
                                                    fail("receiver parameter has no declaredType")
                                            end match
                                case None =>
                                    fail("value extension method not found in Meters companion declarations")
                            end match
                        case Maybe.Present(other) =>
                            fail(s"Expected Object companion but got ${other.getClass.getSimpleName}")
                        case Maybe.Absent =>
                            fail("cp.companion(opaqueMeters) returned Absent; G-2 not fixed")
                case Maybe.Present(other) =>
                    fail(s"Expected OpaqueType for kyo.fixtures.Meters but got ${other.getClass.getSimpleName}")
                case Maybe.Absent =>
                    fail("kyo.fixtures.Meters not found; check fixture setup")
    }

    // ── Leaf 9: broken_id_dropped ─────────────────────────────────────────────
    // Given: a synthetic Symbol.Method with paramListIds = Chunk(Chunk(validId, SymbolId(-1)))
    //        where -1 does not resolve in the classpath.
    //        The classpath is constructed with a pre-existing UnresolvedReference in errors
    //        to model the load-time error that a broken SymbolId would produce.
    // When: Tasty.paramLists(method) is invoked.
    // Then: result.head.size == 1 (the -1 was dropped from the inner chunk)
    //       AND cp.errors.exists(_.isInstanceOf[TastyError.UnresolvedReference]).
    // Pins: INV-H3 broken-reference drop semantics; references log through cp.errors.
    "Tasty.paramLists drops broken SymbolId and records UnresolvedReference (INV-H3)" in {
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
        Tasty.withClasspath(cpWithError):
            Tasty.paramLists(method).map: result =>
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
                    s"Expected UnresolvedReference in cp.errors but got ${cpWithError.errors}"
                )
                succeed
    }

    // ── Leaf 10: empty_clause_positional ──────────────────────────────────────
    // Given: a synthetic Method modelling def f()(a: A): Unit with
    //        paramListIds = Chunk(Chunk.empty, Chunk(idA)).
    // When: Tasty.paramLists(method) is invoked.
    // Then: result.size == 2
    //       AND result.head == Chunk.empty (the explicit () clause is preserved positionally)
    //       AND result.last.size == 1
    //       AND result.last.head.name.asString == "a".
    // Pins: INV-H3 empty-clause shape distinction preserved by the helper.
    "Tasty.paramLists preserves empty clause shape (INV-H3)" in {
        import Tasty.Name.asString
        // paramA at index 0; method at index 1
        val paramA = makeParameter(id = 0, name = "a", ownerId = 1)
        val method = makeMethod(
            id = 1,
            name = "emptyThenParam",
            ownerId = 0,
            paramListIds = Chunk(Chunk.empty, Chunk(SymbolId(0)))
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(paramA, method)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.paramLists(method).map: result =>
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
                    succeed
    }

    // ── Leaf 11: typeParams_regression ────────────────────────────────────────
    // Given: a type-parameterised method literal def map[B](self: A)(f: A => B): B with both
    //        typeParamIds and paramListIds populated.
    // When: Tasty.typeParams(method).size and Tasty.paramLists(method).size are read.
    // Then: typeParams.size == 1 AND paramLists.size == 2
    //       AND typeParams.head.name.asString == "B".
    // Pins: regression guard that Tasty.paramLists does not consume the typeParamIds chain.
    "Tasty.typeParams and Tasty.paramLists are independent (regression guard)" in {
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
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(typeParamB, paramSelf, paramF, method)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.typeParams(method).flatMap: typeParams =>
                    Tasty.paramLists(method).map: paramLists =>
                        assert(typeParams.size == 1, s"Expected 1 type param but got ${typeParams.size}")
                        assert(paramLists.size == 2, s"Expected 2 param lists but got ${paramLists.size}")
                        assert(
                            typeParams.head.name.asString == "B",
                            s"Expected type param name 'B' but got '${typeParams.head.name.asString}'"
                        )
                        succeed
    }

end ParamListsHelperTest
