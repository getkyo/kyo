## Scope integrity (read every cycle)

- Every line item in the plan's `### Files to produce / modify / delete` and `### Tests` sections is mandatory.
- You may not silently drop, weaken, or substitute. If you cannot implement an item, mark the subtask `pending` with a reason and continue. The supervisor will resolve it.
- You do NOT commit. Leave the tree dirty; the supervisor reads `git diff` and commits.
- You do NOT modify the plan, design doc, validation docs, or open-items audit.
- "Simpler" is not a justification. "Redundant with X" is not a justification. Implement exactly as specified or escalate.
- Refactor phases preserve existing behavior byte-for-byte. Any default or derivation not in the plan must match prior code's computed value; do not invent new values.

## NEVER STOP (mirrored from /implement skill)

Once Stage 2 begins, the supervisor drives every phase through commit and immediately launches the next. The loop only stops when:

1. The plan is fully exhausted (all phases committed and final audit green).
2. A task is genuinely blocked after 3 retries on the same agent AND a concrete repro is in this file for the user.
3. The user has explicitly typed "stop", "pause", or equivalent.

If you find yourself wanting to pause for input mid-plan, re-read this section. The user opened the loop with `/implement`; the loop stays open until the plan is exhausted.

## Resume protocol

If context is compacted or any interruption occurs:

1. Do NOT ask "where were we?" or "should I continue?"
2. Run `TaskList`. The next `in_progress` or `pending` task is the resumption point.
3. Read `PROGRESS.md` to confirm which phases are committed at HEAD.
4. Read this file (STEERING.md) for any pending directives.
5. Resume by re-launching the next agent / verification / commit step.

## Project-specific rules

- **No em-dashes anywhere**: per `feedback_no_em_dashes`, never use `—` or `–` in any output (code, comments, docs, commit messages). Use commas, parentheses, colons, periods.
- **No coauthor**: per `feedback_no_coauthor`, never add `Co-Authored-By` lines to commits.
- **Never push to remote**: per `feedback_no_push`, never run `git push` under any circumstance.
- **kyo public API only, kyo.internal for implementation**: per `feedback_kyo_package`. Within kyo-reflect, internals nest as `kyo.internal.reflect.{binary,tasty,classfile,symbol,type_,query,reads,snapshot}.*` (sub-packages allowed under `kyo.internal`); macro entry points live flat at `kyo.internal.ReflectMacro` / `kyo.internal.SymbolToRecordMacro` per the `StructureMacro` / `TagMacro` precedent.
- **All platforms, all tests**: tests live in `shared/src/test/scala/kyo/` and run on JVM + JS + Native.
- **Span over Array**: per `feedback_prefer_span`. Use `Array[Byte]` only when mutability is strictly needed (e.g., read buffers).
- **No AllowUnsafe / Frame.internal**: per `feedback_no_unsafe`. Propagate `(using Frame)`.
- **No semicolons**: per `feedback_no_semicolons`.
- **Lowercase namespace objects** (`internal`, `isolate`, etc.) per `feedback_lowercase_namespace_objects`.

## Active directives (cleared as agents comply)

### Phase 2 name-table delimiter (BLOCKING for NameUnpickler)

PHASE-2-PREP.md surfaced this concern: the TASTy name table is **byte-count-delimited**, not entry-count-delimited. The header field after the section name is the byte length of the name table; the unpickler reads entries until the cursor reaches `start + byteLength`, not until it has consumed N entries. Implement this exactly as dotty does in `TastyUnpickler.scala` (cite the source). Tests must include: a name table whose entries do not align to a "round" count, and a name table with trailing padding bytes that the unpickler must NOT interpret as an extra entry.

After Phase 2 lands and tests pass, this directive can be cleared.

### Phase 2 NameRef indexing: RESOLVED 0-based empirically

The earlier directive (insisting 1-based per dotty `TastyFormat.scala` spec block "starting from 1") was based on an ambiguous spec line. The Phase 2 impl verified empirically against a real scalac-compiled TASTy file (`PlainClass.tasty` fixture): section header `0x80` decodes to NAT=0, resolving to `names[0]='ASTs'`. Real TASTy emitters use 0-based array indices for NameRef encoding; the spec's "starting from 1" appears to refer to human ordinal counting, not the on-wire encoding. Tests on the real fixture pass with 29 expected names. RESOLVED: 0-based is correct.

If Phase 3+ surfaces a NameRef-related decoding bug on real TASTy, revisit; otherwise cleared.

### Phase 3 TYPEDEF discrimination (CRITICAL from PHASE-3-PREP.md, COMPLIED per pulse 1)

`TYPEDEF` tag discrimination via TEMPLATE peek is implemented in `AstUnpickler.scala` per the directive. Cleared after Phase 3 commits.

### Phase 3 qualified-modifier sub-tree skip (CRITICAL from PHASE-3-PREP.md, COMPLIED per pulse 1)

PRIVATEqualified/PROTECTEDqualified sub-tree skip is implemented per the directive. Cleared after Phase 3 commits.

### Phase 3 pulse 1 critical fixes (BLOCKING before commit)

In-flight pulse 1 surfaced four real issues confirmed by supervisor inspection. Apply ALL before re-running tests:

1. **`Pass1Result.placeholders` field MISSING**. The plan (execution-plan.md line 202) requires `Pass1Result(symbols: Chunk[Reflect.Symbol], addrMap: Map[Int, Reflect.Symbol], placeholders: Chunk[UnresolvedRef])`. Current impl has `(symbols, addrMap, rootSymbol)`. ADD `placeholders` (UnresolvedRef may be a Phase 4 type; for Phase 3 add a forward-declared internal `UnresolvedRef` sentinel type, OR use `placeholders: Chunk[(Int, kyo.Reflect.Name)]` if UnresolvedRef can't be declared until Phase 4 — the plan calls for the field's existence so Phase 4 can fill it in). `rootSymbol` may stay if needed by Phase 3 internally, but `placeholders` is non-negotiable per plan and per Phase 4 dependency declaration (line 267).

2. **`null.asInstanceOf[Reflect.Symbol]` at `Constant.scala:73`** violates `feedback_no_casts`. The `Reflect.Constant.ClassConst(Reflect.Type.Named(...))` line uses a null cast as a Symbol placeholder. Replace with: (a) `Reflect.Type.Named(unresolvedSentinel)` where `unresolvedSentinel` is a constant `Reflect.Symbol` with `kind = SymbolKind.Unresolved`, OR (b) introduce a `Constant.ClassConst(typeRef: Reflect.Type)` wrapper that does not require a Symbol at Phase 3 (delegate resolution to Phase 4). Pick the cleaner approach but NO `asInstanceOf` and NO `null`.

3. **`fromTagAndFlags(tag: Int, flags: Long): SymbolKind`** is REQUIRED by plan line 196 as the SymbolKind companion's dispatch function. Current impl replaced it with three narrower helpers (`fromTypedefTemplateFlags`, `fromTypedefTypeFlags`, `fromValdefFlags`) without escalation. ADD the top-level `fromTagAndFlags` that internally dispatches to the three narrower helpers based on the `tag` parameter. The narrower helpers may stay as private internal optimizations, but the plan-mandated public surface must exist.

4. **Test 23 (CAS-swap visibility) latch ordering is broken**. As written, `latch.release` fires BEFORE `table.populate`, so the reader fiber observes an empty table and the race scenario the plan requires cannot manifest. Restructure: reader fiber awaits the latch, writer fiber calls `table.populate(...)` AND THEN `latch.release`, so the reader is unblocked only after the populate completes. The point of the test is to verify the AtomicRef CAS-swap is visible across fibers; the current order verifies nothing.

After ALL four are applied, re-run targeted tests AND cross-platform compile. Do not commit until verified green.
