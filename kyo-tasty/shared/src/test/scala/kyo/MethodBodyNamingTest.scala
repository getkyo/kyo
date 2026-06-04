package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId
import scala.collection.immutable.IntMap

/** Phase 01 plan-mandated test confirming that `body` is a raw field (not an effectful overload) on Symbol.Method in Phase 01.
  *
  * Leaf 24 per plan 05-plan.yaml id:1. Pins: INV-007.
  */
class MethodBodyNamingTest extends Test:

    // ── Leaf 24: body-is-raw-field-no-effectful-overload ─────────────────────

    // Given: a Symbol.Method literal with body = Maybe.Present(SymbolBody(...)).
    // When: read m.body (no `using` clause); call typeCheckErrors("m.body(using cp, frame)")
    //   against an in-scope cp: Classpath and frame: Frame.
    // Then: m.body returns the Maybe[SymbolBody] literal; the typeCheck call returns a non-empty
    //   error list (no `body(using cp, frame)` method exists on Symbol.Method in Phase 01).
    // Pins: INV-007.
    "Leaf 24: Symbol.Method.body is a raw Maybe[SymbolBody] field, no effectful overload" in {
        val bodyRecord = Tasty.SymbolBody(
            bodyStart = 0,
            bodyEnd = 0,
            sectionBytes = Span.empty[Byte],
            names = Span.empty[Tasty.Name],
            sectionOffset = 0,
            addrMap = IntMap.empty
        )
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
            body = Maybe(bodyRecord),
            javaMetadata = Maybe.Absent
        )
        // body is a plain constructor field, no using clause needed
        val rawBody: Maybe[Tasty.SymbolBody] = m.body
        assert(rawBody.isDefined, "m.body must return Maybe.Present when body was passed at construction")

        // typeCheckFailure pins the expected error: Maybe[SymbolBody] does not accept using-clause application
        // because body is a plain field, not an effectful overload with implicit parameters.
        typeCheckFailure("m.body(using ???, ???)")("does not take parameters")
    }

end MethodBodyNamingTest
