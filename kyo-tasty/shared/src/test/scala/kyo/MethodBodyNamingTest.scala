package kyo

import kyo.Tasty.SymbolId

/** update: Symbol.Method.body was removed (Cat 17 Option A).
  *
  * Leaf 24 updated: body field no longer exists on Symbol.Method. Accessing `.body` must be a compile error.
  */
class MethodBodyNamingTest extends kyo.test.Test[Any]:

    // ── Leaf 24: body no longer exists on Symbol.Method ──────────────────────

    // Given: a Symbol.Method constructed without a body field.
    // When: attempt to access.body via compileErrors.
    // Then: the returned string is non-empty (body is not a member of Symbol.Method).
    "Symbol.Method.body does not exist (body field removed)" in {
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
        val __tcErrors1 = compiletime.testing.typeCheckErrors("(??? : kyo.Tasty.Symbol.Method).body").length

        assert(__tcErrors1 > 0, "Expected compile error for Symbol.Method.body (body field removed)")
        succeed
    }

end MethodBodyNamingTest
