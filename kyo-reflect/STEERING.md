## ACTIVE PLAN: execution-plan-v3.md

v2 is complete (commit 4de012f0d + the kyo.Path migration commit). v3 is the simplification plan:
delete Reads/Query/Resolver/Cache.memo/readyLatch, make all accessors pure values, keep `body` as
the lone effectful accessor with strict ClasspathClosed check, delete symbolToRecord, rename
Memo to OnceCell, update examples and bench.

v2-era directives in this file are historical; the v3 directives below take precedence.

## v3 overnight directive (user going to bed; supervisor proceeds autonomously)

> "go ahead. I'll go to bed to do a proper validation of the plan and proceed to execution
> autonomously. Do not stop. Be diligent and fix ALL issues before starting a new phase"

Interpretation:
- Stage 1 design and validation auto-run; no user-approval pause.
- Stage 2 executes autonomously; never pause for user input.
- Every WARN from per-phase audits MUST be drained before the next phase launches.
- "Don't leave any issues behind": pending markers, TODO/FIXME/XXX, weakened tests, etc. all
  must be resolved as they surface. If an agent claims completion with deferrals, restart it
  with the specific items quoted verbatim.
- All platforms (JVM + JS + Native) green at every phase commit. JVM-only is not green.

## ACTIVE PLAN: execution-plan-v2.md (historical reference)

The v1 plan (`execution-plan.md`) is complete and committed. The active plan is now `kyo-reflect/execution-plan-v2.md`. v1 references in older sections of this file are historical.

## v2 Phase 8 prep concerns (RESOLVED in commit e08e70478)

Both HIGH concerns from PHASE-8-V2-PREP.md were addressed in Phase 8:
- TastyOrigin stores the live addrMap from AstUnpickler (explicit argument pattern used).
- TastyOrigin stores the original section byte slice via sectionBytes + bodyStart/bodyEnd fields.
Phase 8 committed as e08e70478 with 9 tests. RESOLVED.

## v2 Phase 5 test scope cut (RESOLVED in commit 7f61ea2d0)

All 7 plan-mandated tests for Phase 5 (G20 declaredType) are present in QueryApiTest. The scope-cut concern from
PHASE-5-V2-INFLIGHT-REVIEW-1.md was addressed before Phase 5 committed. 278 tests passing confirmed in FINAL-AUDIT-V2.md.
RESOLVED.

## v2 Phase 3 test scope cut (RESOLVED in commit f431545af)

All 7 plan-mandated tests for Phase 3 (parents/typeParams/declarations) are present and passing. The scope-cut concern from
PHASE-3-V2-INFLIGHT-REVIEW-1.md was addressed before Phase 3 committed. 278 tests passing confirmed in FINAL-AUDIT-V2.md.
RESOLVED.

## v2 Phase 1 Resolver wiring (RESOLVED in commit 321724cb9)

Cleanup batch task #121 tracks the null+var -> SingleAssign migration.

## v2 Phase 1 Resolver wiring incomplete (was BLOCKER, now resolved 321724cb9)

PHASE-1-V2-AUDIT.md found that Resolver.makeClassLookup and makePackageLookup are defined but never called from Classpath.lookupClass / lookupPackage. The lookups still read fqnIndex directly. The readyLatch (Building-state gate) works correctly, the Async expansion is correct, the AllowUnsafe comments are correct. Only the Cache.memo Promise dedup is missing.

Functional impact: Test 19 still passes (sym1 eq sym2 via HashMap identity), but the Promise dedup machinery promised in the plan is dead code. The commit message inaccurately claims "Cache.memo is now wired".

Resolution: a Phase 1 fixup commit must land BEFORE Phase 3 launches. The fixup:
1. Build `classLookup` and `packageLookup` fields on `Classpath` during `allocate`.
2. Update `lookupClass` and `lookupPackage` to call `classLookup(fqn)` / `packageLookup(fqn)` instead of reading `fqnIndex` directly.
3. Verify Test 19 still passes; verify Test 2 still passes; add a more focused dedup test that proves Cache.memo's Promise dedup (e.g. count how many times the underlying resolution function is invoked under N concurrent callers — should be 1).

After fixup commits: clear this section.

Also fix the stale comment in Test 19 (line 101) saying "Resolver.scala was deleted" — Resolver is back.

## v2 Phase 1 Async deviation (supervisor-approved)

The v2 plan Phase 1 says "Public API modifications: none" but wiring `Cache.memo` into `lookupClass`/`lookupPackage` fundamentally requires `Async` in the effect row (Promise dedup is intrinsically async; the only alternative is `Fiber.block` which STEERING forbids). The v1 final WARN drain deleted Resolver.scala citing exactly this constraint; v2 resurrected the wiring intent without resolving it.

Supervisor decision: ACCEPT the Async addition.

Affected public APIs:
- `Reflect.Classpath.findClass(fqn: String)(using Frame): Maybe[Symbol] < (Sync & Abort[ReflectError])` becomes `Maybe[Symbol] < (Sync & Async & Abort[ReflectError])`.
- `Reflect.Classpath.findPackage(fqn: String)(using Frame): Maybe[Symbol] < (Sync & Abort[ReflectError])` becomes `Maybe[Symbol] < (Sync & Async & Abort[ReflectError])`.
- `Reflect.Classpath.findClassByBinary(binaryName: String)(using Frame)` follows `findClass` (Sync & Async & Abort).

This is the minimum viable change to wire Promise dedup. Test 2's `pending` is removed; the test asserts that two concurrent findClass calls during Building return the same Symbol (reference-equal via Cache.memo).

Document in PROGRESS.md under "Plan deviations during execution".

## v2 Project rules (carry forward from v1; all still binding)

- No em-dashes (`—` or `–`) in any output: code, comments, docs, commit messages.
- No `Co-Authored-By` lines in commits.
- No `git push` under any circumstance.
- No `AllowUnsafe` extension to NEW sites without a `// Unsafe: <reason>` comment AND supervisor approval. Existing authorized sites: Memo/SingleAssign primitives (v1 cleanup batch 1), AtomicRef CAS state-machine transitions in Classpath/ClasspathOrchestrator/SnapshotWriter (v1 Phase 7), and the same justified-bridging pattern.
- No `Frame.internal` ANYWHERE in production code. ZERO occurrences. Propagate `(using Frame)` on every public method.
- No `asInstanceOf` in macro source. Emitted `asInstanceOf` AS GENERATED CODE inside `'{...}` quotes is allowed.
- No `null` in new code (use sentinel objects). Existing hot-path `null` sentinels in Interner/SnapshotReader/ClassfileUnpickler/ConstantPool are documented and accepted.
- No default parameters on internal/private APIs (`feedback_no_default_params_internal`).
- No explicit `[E]` on `Abort.fail` that inference could resolve.
- Tests live ONLY at `kyo-reflect/shared/src/test/scala/kyo/`. Cross-platform: JVM + JS + Native. JVM-only tests use `taggedAs jvmOnly`.
- `kyo` is public API only; `kyo.internal` is implementation. Macro entry points are flat under `kyo.internal` (e.g., `ReflectMacro`, `SymbolToRecordMacro`).
- `Maybe` over `Option`, `Chunk` over `Seq` (storage), `Span` over `Array` (immutable), `Result` over `Either`.
- Sequential cross-platform test runs (per `feedback_sequential_test_runs`): never run JVM/JS/Native suites in parallel.

## Scope integrity (read every cycle)

- Every line item in the plan's `### Files to produce / modify / delete` and `### Tests` sections is mandatory.
- You may not silently drop, weaken, or substitute. If you cannot implement an item, mark the subtask `pending` with a reason and continue. The supervisor will resolve it.
- You do NOT commit. Leave the tree dirty; the supervisor reads `git diff` and commits.
- You do NOT modify the plan, design doc, validation docs, or open-items audit.
- "Simpler" is not a justification. "Redundant with X" is not a justification. Implement exactly as specified or escalate.
- Refactor phases preserve existing behavior byte-for-byte. Any default or derivation not in the plan must match prior code's computed value; do not invent new values.

## Overnight directive (2026-05-24, user going to bed)

> "Please don't stop, don't leave ANY issues behind. Fix all issues before starting a new phase."

Interpretation, binding for the rest of the night:

1. **Zero pending audit items between phases.** Before launching Phase N+1's impl agent, every WARN and NOTE from Phase N's audit must be addressed (fixed in code or explicitly documented + accepted with rationale in PROGRESS.md). No carry-forward.

2. **The carry-forward cleanup TaskList items (#73 Phase 1 WARN, #74 Phase 2 WARN, #75 Phase 3 WARN, #76 Phase 4 WARN, #77 javaMetadata defaults, #78 Phase 5 WARN, #79 Phase 5b WARN) must be DRAINED before Phase 6b begins.** They piled up because the prior nightly loop tolerated them; the user's directive says do not. After Phase 6 commits, the next action is a dedicated cleanup sweep that closes all 7 tasks, then Phase 6b.

3. **Phase 6 audit (when it lands) gets the same treatment.** If audit produces WARNs, fix them BEFORE Phase 6b launches.

4. **"All platforms" remains in force.** JVM + JS + Native green for every phase commit. No "JVM passing, JS will be fixed in a later phase" — that is a deferred issue and the user said zero deferred issues.

5. **No new TaskCreate cleanup tasks may be added during the campaign without being closed in the same nightly window.** If an audit finds a WARN you cannot fix immediately, escalate as a STEERING directive, not as a deferred task.

This directive supersedes any prior implicit "queue for post-commit audit" workflow. The supervisor drains the queue before opening a new phase.

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

### Phase 2 name-table delimiter (RESOLVED in commit c77ea0d89)

Phase 2 implemented byte-count-delimited name table reading. Test 12 in NameUnpicklerTest covers the trailing padding bytes
requirement. RESOLVED.

### Phase 2 NameRef indexing: RESOLVED 0-based empirically

The earlier directive (insisting 1-based per dotty `TastyFormat.scala` spec block "starting from 1") was based on an ambiguous spec line. The Phase 2 impl verified empirically against a real scalac-compiled TASTy file (`PlainClass.tasty` fixture): section header `0x80` decodes to NAT=0, resolving to `names[0]='ASTs'`. Real TASTy emitters use 0-based array indices for NameRef encoding; the spec's "starting from 1" appears to refer to human ordinal counting, not the on-wire encoding. Tests on the real fixture pass with 29 expected names. RESOLVED: 0-based is correct.

If Phase 3+ surfaces a NameRef-related decoding bug on real TASTy, revisit; otherwise cleared.

### Phase 3 TYPEDEF discrimination (CRITICAL from PHASE-3-PREP.md, COMPLIED per pulse 1)

`TYPEDEF` tag discrimination via TEMPLATE peek is implemented in `AstUnpickler.scala` per the directive. Cleared after Phase 3 commits.

### Phase 3 qualified-modifier sub-tree skip (CRITICAL from PHASE-3-PREP.md, COMPLIED per pulse 1)

PRIVATEqualified/PROTECTEDqualified sub-tree skip is implemented per the directive. Cleared after Phase 3 commits.

### Phase 3 fixes (RESOLVED, applied in e29f81a34)

All 4 BLOCKING fixes from pulse 1 applied by the fix-up agent before commit: Pass1Result.placeholders added; null.asInstanceOf replaced with Unresolved sentinel; fromTagAndFlags top-level dispatch added; Test 23 latch order corrected. Cleared.

### Phase 4 wiring (RESOLVED, applied in ad01c90b7)

Both signature and TEMPLATE parent wiring completed. decodeTemplateParents walks Type nodes between TypeParam/Param and SELFDEF/VALDEF/DEFDEF/TYPEDEF/modifier. Cleared.

### Phase 5 fixes (RESOLVED, applied in 79bea87b1)

All 4 BLOCKING fixes applied: ByteView.Heap.copyBytes replaces asInstanceOf; parents wired; javaSpecific populated; throwsTypes wired. Tests 3, 11, 12 strict. Cleared.

### Phase 5b (RESOLVED, applied in 8a66b6e61)

All STEERING directives complied: tests 4 and 5 now assert both Java and TASTy sides. Cleared.

### Phase 6 critical fixes (RESOLVED in commit 82ad3bdfa + 45b7bf5ce)

All 5 blocking issues were fixed before Phase 6 committed:
1. Macro entry wired via `${ kyo.internal.ReflectMacro.derivedImpl[A] }`.
2. `ReadsDerivationTest.scala` created with all 18 plan-mandated tests.
3. `asInstanceOf[Term]` at `ReflectMacro.scala` replaced with proper pattern match.
4. `asInstanceOf` in `TouchedFields.scala` replaced with proper typing.
5. `ReadsInstances` exported in `Reflect.Reads` companion.
Phase 6 cleanup batch (45b7bf5ce) drained the remaining audit WARNs. RESOLVED.

### Phase 6 lazy self-reference design (RESOLVED in commit 82ad3bdfa)

The supervisor-blessed design was implemented verbatim in Phase 6. `ReflectRuntime.readFieldsLazy` helper added.
`ReadsDerivationTest` Test 3 validates the recursive case class path. RESOLVED.

### Phase 6b Frame.internal violation (RESOLVED in commit 83e31ea5d + 9cd415633)

All `Frame.internal` occurrences in `SymbolToRecordMacro.scala` were replaced. `Reflect.symbolToRecord` now takes
`(using Frame)` and the macro propagates the Frame via `Expr.summon`. Phase 6b cleanup batch (9cd415633) confirms
zero Frame.internal in any production source. RESOLVED.

### Phase 6 missing tests (RESOLVED in commit 82ad3bdfa + 45b7bf5ce)

Tests 5 and 18 were added in Phase 6 commit and verified in cleanup batch 4. All 18 plan-mandated tests present. RESOLVED.

### Phase 6 64-field cap (RESOLVED in commit 82ad3bdfa)

`ReflectMacro.buildProduct` has the 64-field guard: `report.errorAndAbort(...)` if `caseFields.length > 64`.
`ReadsDerivationTest` Test 16 verifies the error path. RESOLVED.
