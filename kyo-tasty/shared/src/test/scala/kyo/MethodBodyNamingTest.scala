package kyo

import kyo.Tasty.SymbolId

/** Phase 09 update: Symbol.Method.body was removed (Cat 17 Option A).
  *
  * Leaf 24 updated: body field no longer exists on Symbol.Method. Accessing `.body` must be a compile error.
  * Pins: Cat 17 Option A.
  */
class MethodBodyNamingTest extends Test:

    // ── Leaf 24: body no longer exists on Symbol.Method ──────────────────────

    // Given: a Symbol.Method constructed without a body field.
    // When: attempt to access .body via compileErrors.
    // Then: the returned string is non-empty (body is not a member of Symbol.Method).
    // Pins: Cat 17 Option A.
    "Leaf 24: Symbol.Method.body does not exist after Phase 09" in {
        val m: Tasty.Symbol.Method = Tasty.Symbol.Method(
            id = SymbolId(99),
            name = Tasty.Name("testMethod"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            declaredType = Maybe.Absent,
            paramListIds = Chunk.empty,
            typeParamIds = Chunk.empty,
            annotations = Chunk.empty,
            javaMetadata = Maybe.Absent
        )
        discard(m)
        // body is no longer a field; accessing it must be a compile error
        assert(
            compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Method).body").nonEmpty,
            "Expected compile error for Symbol.Method.body after Phase 09"
        )
        succeed
    }

end MethodBodyNamingTest
