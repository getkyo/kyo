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

### Phase 3 fixes (RESOLVED, applied in e29f81a34)

All 4 BLOCKING fixes from pulse 1 applied by the fix-up agent before commit: Pass1Result.placeholders added; null.asInstanceOf replaced with Unresolved sentinel; fromTagAndFlags top-level dispatch added; Test 23 latch order corrected. Cleared.

### Phase 4 placeholders wiring (PARTIAL: signature wired, TEMPLATE parents not)

VALDEF/TYPEPARAM/PARAM type positions are now wired through TypeUnpickler (pulse 2 confirmed). However, AstUnpickler.scala lines 207-209 still skips the TEMPLATE payload entirely (`view.goto(templatePayloadEnd)`) with a comment "parents are decoded lazily". This contradicts plan line 279 which requires PARENT TYPE positions to flow through TypeUnpickler as well; class extends/implements chains currently do NOT produce UnresolvedRef placeholders.

Update the TEMPLATE handling block (around lines 200-220):
- After consuming the TEMPLATE tag and reading `templatePayloadEnd`, do NOT immediately `view.goto(templatePayloadEnd)`.
- Walk the TEMPLATE payload structure (TypeParam* Param* parent_Type* self_ValDef? Stat*) and at each `parent_Type` position call `TypeUnpickler.readTypeIntoSession` so parent types decode into Reflect.Type values and contribute UnresolvedRefs to `typeSession.placeholders`.
- After the parent block, advance to `templatePayloadEnd` (the rest of the body is still decoded by the existing member walk).
- Add a test that asserts a class fixture (e.g., FixtureClasses.SomeCaseClass which extends Product) produces parent type refs in `r.placeholders`.

Remove the misleading comment "Phase 4: skip the template payload (parents are decoded lazily)" — that contradicts the plan and was a scope substitution.

After this lands and tests pass, this directive can be cleared.
