# Phase 17 decisions

Decision 1: Error-shape pattern for `Annotation.args` decode block.
Rationale: The plan specifies "direct try/catch returning Tree on success and using Abort.fail on caught exceptions inside Sync.defer, no Right/Left/Either." The chosen shape is a `Sync.defer` block with an explicit `val result: Tree < Abort[TastyError]` type annotation on the try/catch expression. The success branch returns a plain `Tree` (promoted by Kyo's effect covariance); the catch branches return `Abort.fail(TastyError.MalformedSection(...))`. The `Either/Right/Left` scaffolding from the pre-Phase-17 code was removed. `Symbol.body` still uses `Either` (pre-existing, not in Phase 17 scope).
Time: 2026-05-30T03:50:00Z

Decision 2: `Tree.Unknown` confirmation.
Rationale: `Tree.Unknown(tag: Int, length: Int)` exists at `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` line 530. The arguments `(-1, 0)` for unknown-tag and zero-length match the plan and existing conventions for placeholder trees (no byte offset, no payload).
Time: 2026-05-30T03:50:00Z

Decision 3: `DecodeException.byteOffset` status.
Rationale: `TreeUnpickler.DecodeException` is defined as `final class DecodeException(msg: String) extends RuntimeException(msg)` (no `byteOffset` field). Phase 14a added `byteOffset` to `TastyError.MalformedSection` but did NOT add it to `DecodeException`. The plan comment says "no cursor: DecodeException does not carry a byte offset". Both catch branches pass `0L` as `byteOffset` to `MalformedSection`. This is consistent with the pre-Phase-17 code and with the `Symbol.body` pattern.
Time: 2026-05-30T03:50:00Z

Decision 4: `TastyError.NotImplemented` removal decision.
Rationale: NOT removed. After Phase 17, `grep -rn 'NotImplemented' kyo-tasty/shared/src/main/scala/kyo/` returns 11 call sites: `Symbol.body` (2 sites: Java symbols and non-body symbols), the internal `stub` helper at line 1235, `SnapshotReader.scala` (2 sites), `Scala2PickleReader.scala` (comment), `InflateHook.scala` (2 comments and 1 usage). Since multiple callers remain, the `NotImplemented` case stays in `TastyError`.
Grep evidence: `TastyError.NotImplemented` is used at Tasty.scala:728, 737, 1235; SnapshotReader.scala:576, 579; InflateHook.scala is JVM-only with NotImplemented for JS/Native.
Time: 2026-05-30T03:50:00Z

Decision 5: Fixture strategy for Test A (plan Test 1: @deprecated annotation).
Rationale: No `@deprecated` TASTy fixture exists in `kyo-tasty-fixtures`. The plan says "Document fallback." Test A uses a synthetic `DecodeContext` (built directly via the `private[kyo]` constructor) with `argsPickle = Chunk(TastyFormat.UNITconst.toByte)`. This exercises the critical path: non-null `_decodeCtx` + non-empty `argsPickle` + decode succeeds. The decoded tree is `Tree.Literal(Constant.UnitConst)`. This covers INV-014 and M2 without requiring a full `@deprecated` fixture. If a `@deprecated` fixture is added later (e.g. in Phase 18+ fixture expansion), Test A can be strengthened.
Time: 2026-05-30T03:50:00Z

Decision 6: Updated `TypeUnpicklerTest` test 18c.
Rationale: Test 18c previously asserted `NotImplemented` for a no-context annotation with non-empty pickle. After Phase 17, this case returns `Tree.Unknown(-1, 0)`. The test was updated to match the new behavior. This is not a new test -- it is a conformance fix for an existing test that described the old behavior.
Time: 2026-05-30T03:50:00Z
