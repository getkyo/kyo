package kyo

import kyo.Tasty.SymbolId

/** Verifies that Symbol.Method.body does not exist: accessing it must be a compile error. */
class MethodBodyNamingTest extends kyo.test.Test[Any]:

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
