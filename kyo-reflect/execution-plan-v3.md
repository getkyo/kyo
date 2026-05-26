# kyo-reflect Execution Plan v3

v2 shipped 280 tests across 17 phases. v3 is a focused simplification: delete the typed-projection and Async-cascade complexity that does not pay for itself, leaving a pure-data API with one well-justified effectful accessor (`body`). 8 phases, net test count 246 (down from 280, accounting for deletions and one addition).

---

## Non-Goals (explicit, with rationale)

- **Tree decode parallelism within a single Symbol body decode**: Memo per-Symbol race-and-discard is the design. No coarse lock, no Promise dedup per body decode.
- **Read-write lock or refcount for in-flight body decodes during close**: the ClasspathClosed check (Phase 4) is the contract; in-flight decodes that race with close may observe a stale open window. This is intentional.
- **kyo-sql backend reuse**: separate future work; no shared data-source infrastructure in this scope.
- **Incremental classpath refresh**: full-digest re-decode is provably correct; deferred per v2 Non-Goals.

---

## Phase 1: Delete Reads and Query layer

**Dependencies**: None. This phase establishes the deletion baseline. All later phases depend on the deleted types being gone so subsequent API changes compile cleanly.

**Files to delete**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala`
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/ReadsInstances.scala`
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/ReflectRuntime.scala`
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/TouchedFields.scala`
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/RecordReads.scala`
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Query.scala`
- `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala`
- `kyo-reflect/shared/src/test/scala/kyo/RecordInteropTest.scala`

**Files to produce**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`: remove `Reflect.Reads` trait and companion; remove `Reflect.FieldSet` opaque type and all bit-manipulation helpers; remove `extension (cp: Classpath) def query[A]`; remove `Symbol.touchedFields` field if present; remove any `derives Reads` on internal or example types.
- `kyo-reflect/shared/src/main/scala/kyo/reflect/examples/CodegenExample.scala`: remove any `derives Reads` usage; rewrite illustrative queries using direct `for`/`yield` over `Symbol` accessors.
- `kyo-reflect/shared/src/main/scala/kyo/reflect/examples/IdeHoverExample.scala`: same treatment.
- `kyo-reflect/shared/src/main/scala/kyo/reflect/examples/JavaScalaBridgeExample.scala`: same treatment.
- `kyo-reflect/shared/src/main/scala/kyo/reflect/examples/RuntimeReflectionExample.scala`: same treatment.
- `kyo-reflect-bench/jvm/src/main/scala/kyo/bench/ReflectBench.scala`: remove W6 (schema-driven query) and W7 (typed projection). Keep W1-W5 and W8. Bench will be extended in Phase 7.
- Any test file outside the deleted list that imports `Reflect.Reads` or `Reflect.FieldSet`: drop those imports and usages.

**Public API additions**: none.
**Public API modifications**: none.
**Public API removals**:
- `Reflect.Reads` trait and companion (including `Reads.derived`, `ReadsInstances`).
- `Reflect.FieldSet` opaque type and all associated methods.
- `Classpath.query[A]` extension method.

**Tests**:
- Deleted: `ReadsDerivationTest` (20 tests) and `RecordInteropTest` (14 tests) = -34 tests.
- No new tests.

**Verification command**:
```
sbt 'kyo-reflect/Test/compile' 2>&1 | tail -15
sbt 'kyo-reflect/test' 2>&1 | tail -10
```
Expected: 246 tests passing (280 - 34).

**Supervisor checks**:
- 8 files deleted; confirm via `git status`.
- `grep -r "Reflect.Reads\|Reflect.FieldSet\|\.query\[" kyo-reflect/shared/src/main/scala` returns zero hits.
- `grep -r "Reflect.Reads\|Reflect.FieldSet" kyo-reflect/shared/src/test/scala` returns zero hits.
- JVM tests pass; JS and Native compile clean.

---

## Phase 2: Delete Resolver, Cache.memo wiring, and readyLatch

**Dependencies**: Phase 1. The Reads/Query layer must be gone before this phase lands. In v2, `Query.scala` was the only live consumer of `Resolver`'s Cache.memo semantics. With Query deleted, the Resolver and the readyLatch that backs concurrent `findClass` have no justified consumer. The `findClass`/`findPackage` APIs can revert to `Sync & Abort[ReflectError]` because no caller needs Async suspension.

**Files to delete**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Resolver.scala`

**Files to produce**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala`: remove `classLookup` and `packageLookup` `SingleAssign` fields; remove `readyLatch: Latch.Unsafe` field; remove the latch creation in `allocate`; remove the latch release in `transitionToReady`; restore `lookupClass`/`lookupPackage` to read `fqnIndex` directly (the v1 pre-Async form) without `Async` suspension.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala`: remove the `Resolver.makeClassLookup`/`makePackageLookup` build steps in `mergeResults` (the lines that construct Resolver-backed lookup structures before calling `Classpath.transitionToReady`, replacing them with the direct `fqnIndex.toMap`/`packageIndex.toMap` arguments already present in the v3 form).
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`: restore `findClass`, `findPackage`, and `findClassByBinary` signatures to `Sync & Abort[ReflectError]` (remove `Async` from the effect row). Note: full conversion to pure values happens in Phase 3; this phase reduces the row to `Sync & Abort` only.
- `kyo-reflect/shared/src/test/scala/kyo/SymbolResolutionTest.scala`: delete Test 2 (concurrent `findClass` during Building state) because the readyLatch contract no longer exists. Update Test 19 comment to reflect that reference equality holds via the immutable `fqnIndex` HashMap, not via Cache.memo.

**Public API additions**: none.
**Public API modifications**:
- `Reflect.Classpath.findClass`: effect row reduced from `Sync & Async & Abort[ReflectError]` to `Sync & Abort[ReflectError]`.
- `Reflect.Classpath.findPackage`: same reduction.
- `Reflect.Classpath.findClassByBinary`: same reduction.
**Public API removals**: none (Resolver was internal).

**Tests**:
- Deleted: 1 (SymbolResolutionTest Test 2).
- No new tests.

**Verification command**:
```
sbt 'kyo-reflect/test' 2>&1 | tail -10
```
Expected: 245 tests passing (246 - 1).

**Supervisor checks**:
- `Resolver.scala` deleted; confirm via `git status`.
- `grep -r "readyLatch\|classLookup\|packageLookup\|Resolver" kyo-reflect/shared/src/main/scala` returns zero hits.
- `findClass`, `findPackage`, `findClassByBinary` signatures in `Reflect.scala` contain `Sync & Abort[ReflectError]` with no `Async`.
- SymbolResolutionTest Test 19 still passes (sym1 eq sym2 via HashMap identity).
- JS and Native compile clean.

---

## Phase 3: Make Symbol accessors and Classpath extension methods pure

**Dependencies**: Phase 2. The Async cascade must be fully removed before stripping the effect row from individual accessors. Any remaining `Async` in the `Sync & Abort` row would re-widen the return type if left in place when accessor effects are removed.

**Files to produce**: none.

**Files to delete**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`:
  - `Symbol` accessors `parents`, `typeParams`, `declarations`, `declaredType`, `scaladoc`, `position`, and `companion` become pure values: drop the `< (Sync & Abort[ReflectError])` effect row. Each accessor reads its `SingleAssign` or `Memo` field using `AllowUnsafe.embrace.danger` internally and returns the pure value. The `// Unsafe:` comment at each site must document the rationale (reading immutable Ready-state data set during open, before any user access).
  - `Classpath` extension methods `findClass`, `findPackage`, `findClassByBinary`, `findModule`, `topLevelClasses`, `packages`, and `errors` become pure values. Each is a HashMap lookup or a sealed `Chunk` read from immutable `Ready` state. The `checkOpen` guard (if present) is removed from these methods; open/closed precondition is the caller's responsibility for pure accessors, enforced by `body`'s explicit check (Phase 4).
  - `Type.isSubtypeOf(other: Type, cp: Classpath): Boolean` becomes pure (drop the effect row).
- All test files that use the now-pure accessors: drop the `flatMap`/`map`/`Kyo.foreach` ceremony for accessor reads. Tests become straight `for`-comprehensions or plain expressions.

**Public API additions**: none.
**Public API modifications**:
- `Symbol.parents`: return type changes from `Chunk[Type] < (Sync & Abort[ReflectError])` to `Chunk[Type]`.
- `Symbol.typeParams`: same.
- `Symbol.declarations`: same.
- `Symbol.declaredType`: same.
- `Symbol.scaladoc`: was already pure in v2; confirm no regression.
- `Symbol.position`: was already pure in v2; confirm no regression.
- `Symbol.companion`: same.
- `Classpath.findClass`, `findPackage`, `findClassByBinary`, `findModule`, `topLevelClasses`, `packages`, `errors`: drop effect row.
- `Type.isSubtypeOf`: drop effect row.
**Public API removals**: none.

**Tests**:
- No tests removed.
- No tests added.

**Verification command**:
```
sbt 'kyo-reflect/test' 2>&1 | tail -10
```
Expected: 245 tests passing.

**Supervisor checks**:
- `grep "def parents" kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` shows return type `Chunk[Type]` with no `<` effect row.
- All test files compile and pass without effect threading on the listed accessors.
- `sym.body` is the only `Symbol` accessor with a `< (Sync & Abort[ReflectError])` return type.
- JS and Native compile clean.

---

## Phase 4: body strict ClasspathClosed check and regression test

**Dependencies**: Phase 3. After Phase 3, `body` is the sole effectful `Symbol` accessor. This phase adds an explicit closed-classpath guard to `body` so callers receive a clean `Abort.fail(ReflectError.ClasspathClosed)` rather than an unspecified failure when the classpath is closed while a `body` decode is pending.

**Files to produce**: none.

**Files to delete**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`: in `Symbol.body`, add an explicit state check before touching the `OnceCell` (name from Phase 6; still called `Memo` until that phase). The check reads `sym.home.cp.state.unsafe.get()` inside the existing `Sync.defer` with `AllowUnsafe.embrace.danger`. If the state is `State.Closed`, return `Abort.fail(ReflectError.ClasspathClosed)` immediately. The `// Unsafe:` comment documents: "Reading classpath state under AllowUnsafe to detect closed classpath before body decode; state transitions are monotonic (Closed is terminal) so a stale read returns a conservative result."
- `kyo-reflect/shared/src/test/scala/kyo/TreeUnpicklerTest.scala`: add one regression test. The test opens a classpath in a `Scope`, captures a `Symbol` reference for a method with a known body, exits the `Scope` (which closes the classpath), then calls `sym.body` and asserts the result is `Result.fail(ReflectError.ClasspathClosed)`.

**Public API additions**: none.
**Public API modifications**: none (existing `body` signature is unchanged; behavior change only).
**Public API removals**: none.

**Tests**:
- Added: 1 (TreeUnpicklerTest regression test for ClasspathClosed on body after scope exit).

**Verification command**:
```
sbt 'kyo-reflect/testOnly kyo.TreeUnpicklerTest' 2>&1 | tail -10
```
Expected: all TreeUnpicklerTest tests pass including the new regression test. Total suite: 246 tests.

**Supervisor checks**:
- `grep "ClasspathClosed" kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` shows the check inside the `body` accessor.
- The new regression test is in `TreeUnpicklerTest.scala`.
- The new test passes with the classpath intentionally closed before `body` is called.

---

## Phase 5: Delete symbolToRecord

**Dependencies**: Phase 1 (`RecordInteropTest` and `RecordReads.scala` already deleted). Phase 5 cleans up the macro entry point and the `Reflect.symbolToRecord` extension that bridged to the Reads layer. No dependency on Phases 2-4 because `SymbolToRecordMacro` is compile-time only and independent of runtime accessor changes.

**Files to delete**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/SymbolToRecordMacro.scala`

**Files to produce**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`: remove the `inline def symbolToRecord[F](sym: Symbol)(using Frame): Record[F] < (...)` extension method and all supporting types introduced for this extension (any `SymbolToRecord` helper types or type aliases).

**Public API additions**: none.
**Public API modifications**: none.
**Public API removals**:
- `Reflect.symbolToRecord` extension method.

**Tests**:
- No tests to remove (`RecordInteropTest` was deleted in Phase 1).
- No new tests.

**Verification command**:
```
sbt 'kyo-reflect/Test/compile' 2>&1 | tail -10
```
Expected: compiles clean; 246 tests passing.

**Supervisor checks**:
- `SymbolToRecordMacro.scala` deleted; confirm via `git status`.
- `grep "symbolToRecord" kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` returns zero hits.
- `grep -r "symbolToRecord" kyo-reflect/shared/src/test/scala` returns zero hits.

---

## Phase 6: Rename Memo to OnceCell

**Dependencies**: Phase 5. All deletion phases are complete; the public API surface is stable. The rename is independent of runtime behavior changes and is placed here to follow the deletion sweep cleanly. No test count change.

**Files to delete**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Memo.scala`

**Files to produce**:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/OnceCell.scala`: same implementation as `Memo.scala` with the type and object renamed to `OnceCell`. Scaladoc rewritten: "A write-once cell that computes its value lazily. May call `init()` more than once under concurrent first-access; stores exactly one result; subsequent reads return the cached value without recomputation. Distinct from `kyo.Cache.memo`, which uses Promise-based deduplication (and adds `Async` to the caller's effect row)."

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala`: rename `_bodyMemo: kyo.internal.reflect.symbol.Memo[Tree]` to `_bodyOnce: kyo.internal.reflect.symbol.OnceCell[Tree]`. Update all references to `_bodyMemo` and to the `Memo` type within this file.
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala`: rename `string: Memo[String]` to `string: OnceCell[String]`. Update all references.
- Any other file that references `kyo.internal.reflect.symbol.Memo` (verify via `grep -r "reflect.symbol.Memo\|import.*Memo" kyo-reflect/`): apply the same rename.

**Public API additions**: none.
**Public API modifications**: none (internal rename; no public API touches `Memo` or `OnceCell` directly).
**Public API removals**: none.

**Tests**:
- No tests removed.
- No tests added.

**Verification command**:
```
sbt 'kyo-reflect/Test/compile' 2>&1 | tail -10
sbt 'kyo-reflect/test' 2>&1 | tail -5
```
Expected: 246 tests passing.

**Supervisor checks**:
- `grep -r "reflect\.symbol\.Memo\b" kyo-reflect/shared/src/main/scala` returns zero hits.
- `OnceCell.scala` present at `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/OnceCell.scala`.
- `OnceCell.scala` scaladoc contains the "Distinct from `kyo.Cache.memo`" sentence.
- `Memo.scala` deleted; confirm via `git status`.

---

## Phase 7: Update examples and benchmark

**Dependencies**: Phases 1-6. The public API surface is stable. Examples and the benchmark can only be updated once the accessor signatures and type removals are finalized.

**Files to produce**: none.

**Files to delete**: none.

**Files to modify**:
- `kyo-reflect/shared/src/main/scala/kyo/reflect/examples/CodegenExample.scala`: update to use direct `for`/`yield` over `Symbol` accessors. No `Reads`, no `Query`, all accessors now return pure values.
- `kyo-reflect/shared/src/main/scala/kyo/reflect/examples/IdeHoverExample.scala`: update to use pure accessors. The hover example walks `cp.topLevelClasses`, then `sym.declarations`, checks `sym.position`, and returns `sym.scaladoc`. All steps are pure; the final result is assembled in a plain `for`-comprehension.
- `kyo-reflect/shared/src/main/scala/kyo/reflect/examples/JavaScalaBridgeExample.scala`: update to use pure accessors.
- `kyo-reflect/shared/src/main/scala/kyo/reflect/examples/RuntimeReflectionExample.scala`: update to use pure accessors.
- `kyo-reflect-bench/jvm/src/main/scala/kyo/bench/ReflectBench.scala`: confirm W1-W5 and W8 still compile and run after the accessor pure conversion. Add two new workloads:
  - W9 (hover-shaped query): for a fixture classpath, walk `cp.topLevelClasses`, then for each class walk `sym.declarations` filtering by `sym.position` for a target line range, and return `sym.name.asString + " " + sym.scaladoc.getOrElse("")`. Pure; no `Sync`.
  - W10 (find-references-shaped query): for a target `Symbol`, walk all method `sym.declarations` whose `sym.body` is available, decode each body via `.body`, count reference occurrences matching a target FQN via `Tree` pattern matching. Uses `Kyo.foreach` to parallelize body decodes. Reports hit count.

**Public API additions**: none.
**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
- No tests removed.
- No tests added.

**Verification command**:
```
sbt 'kyo-reflect/Test/compile' 2>&1 | tail -10
sbt 'kyo-reflect/test' 2>&1 | tail -5
sbt 'kyo-reflect-bench/compile' 2>&1 | tail -10
sbt 'kyo-reflect-benchJVM/run' 2>&1 | tail -25
```
Expected: 246 tests passing; bench prints W1-W10 results without throwing.

**Supervisor checks**:
- All four example files compile.
- `ReflectBench.scala` contains W9 and W10 workload sections.
- Bench run prints results for W1 through W10 without any exception.

---

## Phase 8: Final audit and green run

**Dependencies**: Phases 1-7. All changes landed. This phase produces the audit document, updates governance files, and verifies the final test count on all three platforms.

**Files to produce**:
- `kyo-reflect/FINAL-AUDIT-V3.md`: cross-cutting audit covering: deleted-file completeness (8 + 1 + 1 = 10 files deleted across Phases 1 and 5 and 6); accessor purity (all `Symbol` accessors except `body` are pure); `body` ClasspathClosed check present; `OnceCell` scaladoc accurate; examples compile; bench runs W1-W10; no `AllowUnsafe` at new sites without a `// Unsafe:` comment; no `Frame.internal`; no `asInstanceOf` in macro source (macro is deleted, confirm zero macro files remain); no em-dashes in any modified file; test count on JVM = 246, JS = 246, Native = 246.

**Files to delete**: none.

**Files to modify**:
- `kyo-reflect/PROGRESS.md`: append a v3 phase summary table with the commit hash for each phase and the cumulative test count.
- `kyo-reflect/STEERING.md`: if any v3-specific directives were added during execution, mark them RESOLVED. Clear any pending v3 directives. Confirm the active plan pointer is `execution-plan-v3.md`.

**Public API additions**: none.
**Public API modifications**: none.
**Public API removals**: none.

**Tests**:
- No tests removed.
- No tests added.

**Verification command** (run sequentially per `feedback_sequential_test_runs`):
```
sbt 'kyo-reflect/test' 2>&1 | tail -5
sbt 'kyo-reflectJS/test' 2>&1 | tail -5
sbt 'kyo-reflectNative/test' 2>&1 | tail -5
```

**Supervisor checks**:
- JVM: 246 tests passing.
- JS: 246 tests passing.
- Native: 246 tests passing.
- `FINAL-AUDIT-V3.md` reports 0 BLOCKER and 0 WARN.
- `PROGRESS.md` contains the v3 phase table with commit hashes.
- `STEERING.md` active plan pointer updated to `execution-plan-v3.md`.

---

## Summary table

| Phase | Name | Deletions | New tests | Delta | Cumulative |
|-------|------|-----------|-----------|-------|------------|
| 1 | Delete Reads and Query layer | 8 files | 0 | -34 | 246 |
| 2 | Delete Resolver, Cache.memo, readyLatch | 1 file | 0 | -1 | 245 |
| 3 | Make accessors pure | 0 | 0 | 0 | 245 |
| 4 | body ClasspathClosed check and regression | 0 | 1 | +1 | 246 |
| 5 | Delete symbolToRecord | 1 file | 0 | 0 | 246 |
| 6 | Rename Memo to OnceCell | 1 file (replaced) | 0 | 0 | 246 |
| 7 | Update examples and benchmark | 0 | 0 | 0 | 246 |
| 8 | Final audit and green run | 0 | 0 | 0 | 246 |

**v2 final test count**: 280
**v3 final test count**: 246

---

## Non-goals

The following items are explicitly out of scope for v3. No phase may include any of them, even partially.

- Tree decode parallelism within a single `Symbol.body` call. The `OnceCell` per-Symbol race-and-discard semantics are the design. Promise-based dedup (which would require `Async`) is not justified by any current workload.
- Read-write lock or refcount for in-flight body decodes during classpath close. The ClasspathClosed check added in Phase 4 is the safety boundary; in-flight decodes that race close observe a conservative failure, not a hard guarantee.
- kyo-sql backend reuse. kyo-reflect and kyo-sql share no runtime infrastructure; a future extraction of `kyo-net` is a separate concern.
- Incremental classpath refresh. Full-digest re-decode is correct; incremental invalidation hazards are not addressed in v3.
- Any new TASTy format support or new classfile attribute readers. v3 is a subtraction plan only.
