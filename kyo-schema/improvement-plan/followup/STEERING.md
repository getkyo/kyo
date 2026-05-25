# STEERING — kyo-schema followup campaign

Read this AFTER every compile and test cycle. Follow any new instructions immediately.

## Scope integrity (read every cycle)

- Every line item in `kyo-schema/improvement-plan/followup/execution-plan.md`'s Files to produce/modify/delete and Tests sections is mandatory.
- You may not silently drop, weaken, or substitute. If you cannot implement an item, mark its subtask `pending` with a reason and continue. The supervisor will resolve.
- You do NOT commit. Leave the tree dirty; the supervisor reads `git diff` and commits.
- You do NOT modify any file under `kyo-schema/improvement-plan/`.
- "Simpler" / "redundant" / "probably not needed" / "edge case" / "out of scope" are NOT justifications. Implement as specified or escalate.
- Refactor phases preserve existing behavior byte-for-byte unless the spec explicitly says otherwise.

## NEVER STOP (mirrors the supervisor's hard rule)

The supervisor drives every phase through commit and launches the next immediately. There is no implicit "check in with the user" between phases. The only valid stops:

1. Plan exhausted and final audit green.
2. Genuinely blocked after 3 retries on the same agent AND a concrete repro written to this file.
3. User has explicitly typed "stop" / "pause" in the current turn.

## kyo-schema specifics

- Tests extend `Test` (`kyo-schema/shared/src/test/scala/kyo/Test.scala`), NOT KyoTest.
- `Json.encode[T](value)` returns `String`. `Json.decode[T](jsonString)` returns `Result[DecodeException, T]`.
- Lambda overloads of `.drop(_.field)` / `.rename(_.field, "to")` can fail at `derives Schema` sites because `Schema.derived[X]` strips the Focused refinement. Use string overloads or `Schema[X]` apply.
- When attaching a transformed given (`given Schema[X] = Schema[X].drop(...)`), define X without `derives Schema` to avoid given ambiguity.
- Test fixtures live at package level (top of file), not nested inside the test class.
- SBT JVM project: `kyo-schema/test`. JS/Native: `kyo-schemaJS/test`, `kyo-schemaNative/test`.

## Phase-specific notes

- **Phase 4**: Item 17 tests fold into existing `kyo-data/shared/src/test/scala/kyo/TagTest.scala`. Do NOT create `TagMacroTest.scala`.
- **Phase 8**: Wire format CHANGES for Map[Int/Long/UUID, V] from object form to array-of-pairs. This is intentional per `feedback_no_backcompat`.
- **Phase 11**: When deleting `isSerializableType`, `MacroUtils.platformPrimitiveSymbols` becomes dead code — delete in the same phase. PlatformSymbols.scala files in all 3 platforms also deleted.
- **Phase 13**: IntersectionMacro REUSES `caseClassWriteBody` / `caseClassReadBodyResolved`. Do NOT duplicate the case-class encoding logic.
- **Phase 14**: Test renaming happens LAST after deletes from Phase 8 (KeyCodec), Phase 11 (platform / drift), Phase 12 (drift infra) have already removed files.

## Workflow rules

- No co-author lines on commits.
- Never push to remote — the user pushes.
- No AllowUnsafe / Frame.internal in new code (Frame.internal allowed only inside macro-emitted lambdas where no Frame is reachable; precedent at SerializationMacro.scala:172).
- Use Maybe (not Option), Span (not Array except in arraySchema), Chunk (not Seq), Dict (not Map) where the API choice is yours.
- No effect aliases.
- No `;`-chained statements.
- Use named methods, no symbolic operators.
- For new test files: name matches a prefix of an existing source file (per Item 15).
- No em-dashes / LLM-tells in any prose or comments added (per user style preference).
