# STEERING — kyo-schema improvement campaign

Read this before every compile or test cycle. If new instructions are added below, follow them immediately.

## Scope integrity (read every cycle)

- Every line item in the plan's Files to produce/modify/delete and Tests sections is mandatory.
- You may not silently drop, weaken, or substitute. If you cannot implement an item, mark the subtask `pending` with a reason and continue. The supervisor will resolve it.
- You do NOT commit. Leave the tree dirty; the supervisor reads `git diff` and commits.
- You do NOT modify the plan or analysis docs (`kyo-schema/improvement-plan/*.md`).
- "Simpler" is not a justification. "Redundant with X" is not a justification. Implement exactly as specified or escalate.
- Refactor phases preserve existing behavior byte-for-byte. Any default or derivation not in the plan must match prior code's computed value; do not invent new values.

## kyo-schema specifics

- Use the `Test` base class (`kyo-schema/shared/src/test/scala/kyo/Test.scala`), NOT `KyoTest`.
- `Json.encode[T](value)` returns `String` directly. `Json.decode[T](jsonString)` returns `Result[DecodeException, T]`. Match this in tests (see `CodecTest.scala` for the established pattern).
- Lambda overloads of `.drop(_.field)` / `.rename(_.field, "to")` can fail to compile at `derives Schema` sites because `Schema.derived[X]` strips the `Focused` refinement. Use string overloads `.drop("name")` / `.rename("from", "to")` OR use `Schema[X]` (with `transparent inline apply`) instead of `Schema.derived[X]`.
- When attaching a transformed given (`given Schema[X] = Schema[X].drop(...)`), define `X` without `derives Schema` to avoid given ambiguity.
- Top-level test fixtures (case classes, sealed traits used in tests) must live at package level (top of file), NOT nested inside the test class, because the schema-derivation macros need package-level visibility.
- `Schema.derived[X]` does NOT pre-compute the `metaApply` field path's `isSerializableType` gate; the gate runs only via the `Schema[X]` apply syntax.

## Workflow rules (from CLAUDE.md)

- No co-author lines on commits.
- Never push to remote — the user pushes.
- Tests rigor: failing tests that expose bugs are the deliverable; never weaken a test to make it pass.
- For bug fixes specifically: failing tests in a commit BEFORE the fix commit. Two commits per bug-fix phase.
- No "out of scope" — every test failure is owned. If it's truly orthogonal, it goes into a follow-up plan section, never silently dismissed.
- Targeted test runs per sub-phase (`testOnly *SpecificTest`); full suite only at phase-group boundaries and final audit.
- No semicolons, no AllowUnsafe, no Frame.internal in new code (Frame.internal is allowed in macro-generated code for the writeTo/readFrom path; see SerializationMacro.scala for the established precedent at L172).
- Use Maybe (not Option), Span (not Array, except in new Array-given phases), Chunk (not Seq) where the API choice is yours.
- Effect aliases banned: spell `Sync` etc. at each use site.

## Common kyo-schema macro patterns

- `Expr.summon[Schema[T]]` returns `Option[Expr[Schema[T]]]`. Provide an error path via `report.errorAndAbort` when summoning fails for derive-required types.
- `Frame.internal` is the macro-emitted Frame; use it inside emitted lambda bodies where no caller Frame is reachable. Precedent: SerializationMacro.scala throwing `TypeMismatchException` uses `Frame.internal`.
- New macro emission paths that route through `SchemaSerializer.writeTo` / `readFrom` need a Frame: `writeTo` takes `using Frame`; pass `Frame.internal` in macro-emitted writer lambdas, pass `$reader.frame` (from the in-scope Reader) in macro-emitted reader lambdas.
- `private[kyo]` members of `kyo.internal.SchemaSerializer` are reachable from anywhere in the `kyo.internal` package — no visibility widening needed.
