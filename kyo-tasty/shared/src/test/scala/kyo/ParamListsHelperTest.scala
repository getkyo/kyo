package kyo

/** Pending reproduction tests for the Tasty.paramLists public helper.
  *
  * The helper does not exist yet (added in Phase 04). Every leaf in this file is marked
  * `.ignore` so the suite stays green at Phase 01. Phase 04 removes the `.ignore` markers,
  * fleshes out the bodies to call Tasty.paramLists, and verifies the behaviour pinned here.
  *
  * Leaf summary (all ignored until Phase 04):
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

    // ── Leaf 6: zero_arg_method (ignored until Phase 04) ──────────────────────
    // Given: a Symbol.Method representing a no-arg accessor (no parameter lists).
    // When: Tasty.paramLists(method) is called.
    // Then: result equals Chunk.empty (.size == 0).
    // Pins: INV-H3 helper signature behaviour on zero parameter lists.
    "Tasty.paramLists for no-arg method returns Chunk.empty (INV-H3)".ignore(
        "Tasty.paramLists helper added in Phase 04"
    ) in {
        fail("Tasty.paramLists not yet implemented; body populated in Phase 04")
    }

    // ── Leaf 7: single_list_two_params (ignored until Phase 04) ──────────────
    // Given: a Symbol.Method with paramListIds = Chunk(Chunk(idA, idB)) where idA and idB
    //        point to Symbol.Parameter entries named "a" and "b".
    // When: Tasty.paramLists(method) is called.
    // Then: result.size == 1 AND result.head.size == 2
    //       AND result.head.map(_.name.asString) == Chunk("a", "b").
    // Pins: INV-H3 helper signature on a single parameter list.
    "Tasty.paramLists for single-list method returns Chunk(Chunk(p1, p2)) (INV-H3)".ignore(
        "Tasty.paramLists helper added in Phase 04"
    ) in {
        fail("Tasty.paramLists not yet implemented; body populated in Phase 04")
    }

    // ── Leaf 8: extension_receiver_positional (ignored until Phase 04) ────────
    // Given: embedded Meters fixture loaded via TestClasspaths.withClasspath; valueExt is
    //        the Symbol.Method for def value: Double on kyo.fixtures.Meters.
    // When: Tasty.paramLists(valueExt) is invoked.
    // Then: result.head.head.simpleName.asString == the receiver parameter name (dotty emits
    //       the synthetic receiver as the first PARAM of the first parameter list, before any
    //       user-written parameter clause)
    //       AND result.head.head.declaredType.flatMap(Tasty.typeSymbol).get == metersOpaqueType.
    // Pins: INV-H6 (positional receiver) and INV-H3 (helper result shape).
    "Tasty.paramLists for extension method: head.head resolves to receiver type (INV-H6)".ignore(
        "Tasty.paramLists helper added in Phase 04"
    ) in {
        fail("Tasty.paramLists not yet implemented; body populated in Phase 04")
    }

    // ── Leaf 9: broken_id_dropped (ignored until Phase 04) ────────────────────
    // Given: a synthetic Symbol.Method with paramListIds = Chunk(Chunk(validId, SymbolId(-1)))
    //        where -1 does not resolve in the classpath.
    // When: Tasty.paramLists(method) is invoked.
    // Then: result.head.size == 1 (the -1 was dropped)
    //       AND cp.errors.exists(_.isInstanceOf[TastyError.UnresolvedReference]).
    // Pins: INV-H3 broken-reference drop semantics; references log through cp.errors.
    "Tasty.paramLists drops broken SymbolId and records UnresolvedReference (INV-H3)".ignore(
        "Tasty.paramLists helper added in Phase 04"
    ) in {
        fail("Tasty.paramLists not yet implemented; body populated in Phase 04")
    }

    // ── Leaf 10: empty_clause_positional (ignored until Phase 04) ─────────────
    // Given: a synthetic Method modelling def f()(a: A): Unit with
    //        paramListIds = Chunk(Chunk.empty, Chunk(idA)).
    // When: Tasty.paramLists(method) is invoked.
    // Then: result.size == 2
    //       AND result.head == Chunk.empty (the explicit () clause is preserved positionally)
    //       AND result.last.size == 1
    //       AND result.last.head.name.asString == "a".
    // Pins: INV-H3 empty-clause shape distinction preserved by the helper.
    "Tasty.paramLists preserves empty clause shape (INV-H3)".ignore(
        "Tasty.paramLists helper added in Phase 04"
    ) in {
        fail("Tasty.paramLists not yet implemented; body populated in Phase 04")
    }

    // ── Leaf 11: typeParams_regression (ignored until Phase 04) ──────────────
    // Given: a type-parameterised method literal def map[B](self: A)(f: A => B): B with both
    //        typeParamIds and paramListIds populated.
    // When: Tasty.typeParams(method).size and Tasty.paramLists(method).size are read.
    // Then: typeParams.size == 1 AND paramLists.size == 2
    //       AND typeParams.head.name.asString == "B".
    // Pins: regression guard that Tasty.paramLists does not consume the typeParamIds chain.
    "Tasty.typeParams and Tasty.paramLists are independent (regression guard)".ignore(
        "Tasty.paramLists helper added in Phase 04"
    ) in {
        fail("Tasty.paramLists not yet implemented; body populated in Phase 04")
    }

end ParamListsHelperTest
