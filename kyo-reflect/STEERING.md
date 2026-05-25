## ACTIVE PLAN: execution-plan-v2.md

The v1 plan (`execution-plan.md`) is complete and committed. The active plan is now `kyo-reflect/execution-plan-v2.md`. v1 references in older sections of this file are historical.

## v2 Phase 8 prep concerns (read before writing TreeUnpickler)

PHASE-8-V2-PREP.md flagged two HIGH concerns the agent must address:

1. **TastyOrigin.addrMap is Map.empty at all construction sites.** TreeUnpickler needs the addrMap to resolve SHAREDterm cross-references to other Trees. Either pass `addrMap` as an explicit argument to TreeUnpickler.decodeSync, OR retrofit TastyOrigin to store the live addrMap from AstUnpickler. Pick the simpler path (explicit argument is fine).

2. **TastyOrigin does not store the original byte array.** TreeUnpickler needs the bodyStart..bodyEnd byte slice (or a sub-view). Either pass `bytes: ByteView` directly to decodeSync, OR extend TastyOrigin to hold a `ByteView` reference. Again, explicit argument is simpler.

Implementation hint: the simplest correct path is to have Symbol.body capture `bodyBytes: Array[Byte] | ByteView` plus `addrMap: Map[Int, Symbol]` AT POPULATION TIME (in AstUnpickler walks, or in mergeResults) and pass both to TreeUnpickler.decode. The Memo wraps the lazy decode result.

## v2 Phase 5 test scope cut (CRITICAL — finish before exit)

PHASE-5-V2-INFLIGHT-REVIEW-1.md reports the production wiring is clean and compile passes, but ALL 7 plan-mandated tests for Phase 5 (G20 declaredType) are MISSING. `git diff HEAD -- kyo-reflect/shared/src/test/` is empty; QueryApiTest.scala has zero `declaredType` occurrences.

This is the same scope-cut pattern Phase 3 exhibited. The plan calls for 7 tests for `Symbol.declaredType` in `kyo-reflect/execution-plan-v2.md` Phase 5 (around lines 215-228).

REQUIRED before exit:
1. Read execution-plan-v2.md Phase 5 lines 215-228 to extract the exact 7 test contracts.
2. Add all 7 tests to QueryApiTest (or AstUnpicklerTest where the plan specifies). Each test must be strict (no tautology, no foreach-discards-assert), use existing fixtures (plainClassTasty, baseClassTasty, childClassTasty, genericBoxTasty, someCaseClassTasty, fixtureClassesPackageTasty, arrayRecordClass).
3. Run `sbt 'kyo-reflect/test' 2>&1 | tail -10` AFTER the tests are written. Must report 226 passing (219 prior + 7 new).
4. Quote the verbatim sbt test output in your final report.

DO NOT EXIT until all 7 plan-mandated tests are present, strict, and passing.

## v2 Phase 3 test scope cut (CRITICAL — finish before exit)

Pulse 1 (PHASE-3-V2-INFLIGHT-REVIEW-1.md) reports the production wiring is clean but the test enumeration is significantly incomplete. Plan calls for 7 tests; current state:

- T1 QueryApiTest: `sym.parents` returns AnyRef for a fixture class — MISSING
- T2 QueryApiTest: generic class `GenFoo[T, U]` typeParams length 2 with correct names — MISSING
- T3 QueryApiTest: `sym.declarations` for class with known methods — MISSING
- T4 QueryApiTest: `sym.parents` after classpath close returns ClasspathClosed — MISSING
- T5 QueryApiTest: Java String proxy parents/typeParams/declarations — MISSING
- T6 AstUnpicklerTest: `Pass1Result.parentsBySymbol` contains fixture class entry — WEAKENED (indirect placeholder proxy)
- T7 AstUnpicklerTest: `Pass1Result.childrenByOwner` maps class symbol to members — MISSING

The three ClassfileReaderTest tests added cover raw `ClassfileResult` fields, NOT the public `Symbol` accessors. They do not substitute for the plan tests.

REQUIRED before exit:
1. Add T1, T2, T3, T4, T5 to QueryApiTest, each strict and using existing fixtures (plainClassTasty, baseClassTasty, childClassTasty, etc., plus a new GenFoo fixture if needed for T2; reuse genericBoxTasty if it has type params).
2. Add T7 to AstUnpicklerTest with a direct `parentsBySymbol` / `childrenByOwner` assertion.
3. Strengthen T6 to assert `parentsBySymbol` directly (the same Pass1Result extension that pulse 1 confirmed exists).

Run `sbt 'kyo-reflect/test' 2>&1 | tail -10` and confirm 215 passing (208 prior + 7 new). Re-read STEERING.md after each compile/test cycle.

DO NOT EXIT until all 7 plan-mandated tests are present and strict.

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

### Phase 4 wiring (RESOLVED, applied in ad01c90b7)

Both signature and TEMPLATE parent wiring completed. decodeTemplateParents walks Type nodes between TypeParam/Param and SELFDEF/VALDEF/DEFDEF/TYPEDEF/modifier. Cleared.

### Phase 5 fixes (RESOLVED, applied in 79bea87b1)

All 4 BLOCKING fixes applied: ByteView.Heap.copyBytes replaces asInstanceOf; parents wired; javaSpecific populated; throwsTypes wired. Tests 3, 11, 12 strict. Cleared.

### Phase 5b (RESOLVED, applied in 8a66b6e61)

All STEERING directives complied: tests 4 and 5 now assert both Java and TASTy sides. Cleared.

### Phase 6 critical fixes (BLOCKING before commit)

Pulse 1 found 5 real issues confirmed by supervisor inspection. ALL must be applied before re-running tests.

**1. Macro entry NOT wired in Reflect.scala**. Line 354 still has `inline def derived[A]: Reads[A] = scala.compiletime.error("...Phase 0 stub; lands in Phase 6")`. The Phase 6 macro at `kyo.internal.ReflectMacro.derivedImpl` exists but is disconnected from the public API. Replace the compiletime.error stub with `${ kyo.internal.ReflectMacro.derivedImpl[A] }` splice (using the standard Scala 3 macro entry pattern). Without this, `derives Reflect.Reads` cannot work at all.

**2. `ReadsDerivationTest.scala` is ABSENT**. All 18 plan-mandated tests are missing. Create the file at `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala`. Implement all 18 tests from the plan (product derivation, recursive case classes, built-in instances, given override, touched-fields, sum-type guard, higher-kinded guard, hygiene). Tests MUST be strict (no tautological assertions).

**3. `asInstanceOf[Term]` at `ReflectMacro.scala:343`** in `paramss.head.head.asInstanceOf[Term]`. Violates `feedback_no_casts`. Replace with a proper match on the tree shape, or use the `quotes.reflect` Term API directly. If the tree has multiple shapes, pattern-match each explicitly.

**4. `asInstanceOf` at `TouchedFields.scala:102`** in `items.asInstanceOf[List[quotes.reflect.Tree]]` inside `GotoQueue.drain`. Same violation. Replace with proper typing: declare `items: List[quotes.reflect.Tree]` from the start (parameterize `GotoQueue` over the `Quotes` instance) or use a sealed type for the queue payload.

**5. `ReadsInstances` not exported**. `Reflect.Reads` companion object has no `given` delegation to instances in `kyo.internal.reflect.reads.ReadsInstances`. Without this, `summon[Reads[Reflect.Name]]` cannot find the built-in instances. Add `given` declarations or `export` statements in the `Reads` companion (in Reflect.scala or via `import` in user code's perspective).

After ALL 5 are applied, re-run targeted tests + cross-platform compile.

### Phase 6 lazy self-reference design (supervisor-blessed)

The prior impl agent thrashed for ~45 minutes on Scala 3 macro hygiene for the lazy self-reference (`derives Reflect.Reads` on a recursive case class like `case class Node(name: Name, children: Chunk[Node])`). Do not re-derive this. Use the following design verbatim:

The macro emits the entire lazy-product body as a single outer quote where the recursive reference is resolved by **referring to the lazy val from inside the same quote**, not by smuggling a name across splice boundaries:

```scala
'{
    lazy val instance: Reflect.Reads[A] = new Reflect.Reads[A]:
        val symbolKinds   = $symbolKindsExpr
        val needsBodies   = false
        val touchedFields = $touchedFieldsExpr
        private val _ctor: Array[Any] => A = $ctorExpr
        // Built outside the recursive ref: a Chunk of readers for non-recursive fields only.
        private val _nonRecReaders: Chunk[Reflect.Symbol => Any < (Sync & Abort[ReflectError])] = $nonRecReadersExpr
        // Bitmask: which slot indices are recursive (read via `instance`).
        private val _isRecSlot: Long = $isRecSlotMaskExpr
        // Bitmask: which slot indices are Chunk[Self] (vs single Self).
        private val _isChunkSelf: Long = $isChunkSelfMaskExpr
        def read(sym: Reflect.Symbol)(using Frame): A < (Sync & Abort[ReflectError]) =
            kyo.internal.reflect.reads.ReflectRuntime.readFieldsLazy[A](
                sym, _nonRecReaders, _isRecSlot, _isChunkSelf, instance, _ctor
            )
    instance
}
```

`ReflectRuntime.readFieldsLazy` is a new helper in `kyo.internal.reflect.reads` that walks slot indices, picks a reader from `_nonRecReaders` for non-recursive slots, and uses the passed-in `instance` (the resolved lazy val) for recursive slots. The `instance` reference inside the outer quote resolves at user-code compile time to the surrounding `lazy val instance`, which is hygienically valid.

No `thunk` / `() => Reflect.Reads[A]` indirection. The lazy val itself is the indirection.

For non-recursive products, emit the eager form without `readFieldsLazy` (the same straightforward `Chunk(reader0, reader1, ...).map(...).collect` pattern documented in DESIGN.md section on Reads.derived).

This design is supervisor-blessed; the impl agent must implement it verbatim.

### Phase 6b Frame.internal violation (BLOCKING before commit)

`SymbolToRecordMacro.scala` uses `kyo.Frame.internal` in 10+ emitted positions (lines 201, 203, 207, 209, 213, 215, 219, 221, 225, 227 etc). This violates `feedback_no_unsafe`: "never use AllowUnsafe or Frame.internal, use safe APIs, propagate Frame".

Fix: modify `Reflect.symbolToRecord` in `Reflect.scala` to take `(using Frame)` on the inline def. Inside the macro, generate `'{ ${sym}.parents(using ${frameExpr}).flatMap(...) }` where `frameExpr: Expr[Frame]` is obtained via `Expr.summon[Frame]` (or by capturing the caller's Frame via the inline-def's using parameter and splicing it). The result: zero Frame.internal references.

Acceptable variant: the inline def can be `inline def symbolToRecord[F](sym: Symbol)(using Frame): Record[F] < (Sync & Abort[ReflectError]) = ${ ... }`. The macro then references the `Frame` from the using parameter via standard quotes.reflect lookup or `quoted.runtime.Expr.summonOrPanic`. If the macro cannot synthesize the splice cleanly, use a small runtime helper in `ReflectRuntime` that accepts `(using Frame)` and bridges. The end state must have NO `Frame.internal` in any production source file.

If the `asInstanceOf[Record[F]]` at line 76 (emitted in `'{ Record.empty.asInstanceOf[Record[F]] }`) is only reachable on the F = Any edge case, guard the macro to emit `Record.empty[F]` directly (typing the empty constructor) so the cast is unnecessary. If `Record.empty` is not generic, accept the emitted cast (it is runtime code, not macro source, per STEERING).

### Phase 6 missing tests (BLOCKING before commit)

The impl agent stopped at 16/18 tests. The two missing tests are not optional; they are the core semantic checks:

**Test 5** (plan line 498): `Reads[Simple].read(sym)` decodes a fixture symbol into a `Simple` whose `name == sym.name` and `flags == sym.flags`. Construction of `sym` must use a fixture (build a small `Reflect.Symbol` via a fresh small TASTy or via the existing `PlainClass.tasty` fixture; use the simplest path that gives a real `Reflect.Symbol` with a known `Name` and `Flags`). Strict assertions on both fields.

**Test 18** (plan line 511): `Reads.read` on a real fixture symbol from `PlainClass.tasty` (already loaded by `AstUnpicklerTest` and `NameUnpicklerTest`) returns the expected product value for a simple two-field case class. Load the fixture via `getClass.getResourceAsStream("/kyo/fixtures/PlainClass.tasty")`, decode into a `Reflect.Symbol` for the class, then run a `derived Reads[Simple]` against it and assert the decoded `Simple.name.asString` matches "PlainClass" (or whatever the fixture top-level is).

### Phase 6 64-field cap (BLOCKING before commit)

`ReflectMacro.buildProduct` MUST `report.errorAndAbort(s"Reflect.Reads.derived supports up to 64 fields; got ${caseFields.length}")` if `caseFields.length > 64`. This guard runs at macro expansion time, NOT at runtime. The Long bitmask cap (`_isRecSlot: Long`, `_isChunkSelf: Long`) makes this a hard limit; without the guard, a 65-field case class would silently truncate.
