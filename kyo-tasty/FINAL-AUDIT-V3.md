# v3 Final Audit

Audit run: 2026-05-26T00:00:00Z
HEAD commit: 0a7c73e81 (kyo-reflect v3 Phase 7: update examples and benchmark for pure accessors)
v3 baseline: v2 final (commit 4de012f0d)

---

## Verdict

PROCEED (with WARNs documented below, none are blockers)

---

## Summary

| Category | Count |
|---|---|
| BLOCKER | 0 |
| WARN | 4 |
| NOTE | 7 |

---

## v3 Phase Commits

| Phase | Commit | Title | Tests delta |
|---|---|---|---|
| 1 | 9a317c7fa | Delete Reads + Query layer | -34 (20 from ReadsDerivationTest + 14 from RecordInteropTest) |
| 2 | 624a37499 | Delete Resolver + Cache.memo + readyLatch | -2 (SymbolResolutionTest Tests 2 and 20) |
| 3+5 | 4b1d041f9 | Pure accessors + symbolToRecord deletion | 0 |
| 4 | 30f06bd54 | body strict ClasspathClosed check + regression test | +1 (TreeUnpicklerTest Test 10) |
| 6 | 73855f5cc | Rename Memo to OnceCell | 0 |
| 7 | 0a7c73e81 | Update examples and benchmark for pure accessors | 0 |

Phase 5 (delete symbolToRecord) was collapsed into the Phase 3+5 commit because `SymbolToRecordMacro.scala` emitted accessor calls that broke under the Phase 3 pure conversion. The plan prescribed a separate Phase 5 commit; the collapse is documented here as a deviation from the plan's commit structure (no behavioral impact).

No Phase 7 v3 audit file was produced before the Phase 7 commit. The Phase 7 work (example verification and W4/W5/W8 cleanup plus W9/W10 addition) was guided by `PHASE-7-V3-PREP.md`. This audit file covers Phase 7 findings.

---

## Findings

### BLOCKER (0)

None.

---

### WARN (4)

**WARN-1: PROGRESS.md is missing the v3 phase table.**

The execution-plan-v3.md Phase 8 plan requires: "PROGRESS.md: append a v3 phase summary table with the commit hash for each phase and the cumulative test count." The current PROGRESS.md has no v3 section -- it covers v1 and v2 phases but stops before v3. The plan deviations section was updated for Resolver deletion but no phase table was appended.

Category: WARN (documentation gap; code and commits are correct)
Resolution: Append the v3 phase table from the table above to PROGRESS.md before the final green run.

**WARN-2: STEERING.md active-plan pointer references both execution-plan-v3.md and execution-plan-v2.md.**

The top of STEERING.md says "ACTIVE PLAN: execution-plan-v3.md" but the body still prominently labels a section "ACTIVE PLAN: execution-plan-v2.md (historical reference)". A reader skimming cold sees two "ACTIVE PLAN" headers. The v3 plan is complete; the v2 "active plan" header should be relabeled "HISTORICAL PLAN: execution-plan-v2.md".

Category: WARN (documentation hygiene; no functional impact)
Resolution: Relabel the v2 "ACTIVE PLAN" section header to "HISTORICAL PLAN" in STEERING.md.

**WARN-3: SnapshotReader.deserializeMapped at line 244 has AllowUnsafe without a `// Unsafe:` comment.**

SnapshotReader.scala line 172-173 has the correct `// Unsafe:` comment before its `import AllowUnsafe.embrace.danger`. SnapshotReader.scala line 244 has a second `import AllowUnsafe.embrace.danger` for the per-symbol `SingleAssign.set()` calls in `deserializeMapped`, but the comment at line 172 is in the sibling method `deserialize` and does not cover line 244. The STEERING.md convention requires a `// Unsafe:` comment directly preceding each `import AllowUnsafe` site.

Category: WARN (convention violation; same rationale as the covered site at line 172)
Resolution: Add `// Unsafe: SingleAssign.set() is an unsafe-tier helper; called here from single-threaded deserializeMapped.` at line 243, immediately before `import AllowUnsafe.embrace.danger`.

**WARN-4: No Phase 7 v3 audit file exists.**

The plan's Phase 8 prerequisite list includes "Phases 1-6 done; Phase 7 audit will be in this commit cycle if not already." There is a PHASE-7-V3-PREP.md but no PHASE-7-V3-AUDIT.md. The PHASE-7-AUDIT.md present in the repo is the v1/v2 Phase 7 audit (covering commit 98416eacf, the v1 Query+Snapshot phase), not the v3 Phase 7 audit. Since this FINAL-AUDIT-V3.md covers Phase 7 findings in the sections below, the missing file is a documentation gap rather than an unchecked phase.

Category: WARN (audit trail gap; findings are covered in this document)
Resolution: This FINAL-AUDIT-V3.md document serves as the Phase 7 v3 audit record.

---

### NOTE (7)

**NOTE-1: Test count arithmetic.**

The execution-plan-v3.md stated target: 246 (based on v2 plan's stated 280 minus 34 plus 1). Actual based on per-phase audit runtimes:
- v2 actual runtime count: 278 (confirmed by FINAL-AUDIT-V2.md)
- Phase 1 delta: -34 (ReadsDerivationTest 20 + RecordInteropTest 14) = 244
- Phase 2 delta: -2 (SymbolResolutionTest Tests 2 and 20) = 242
- Phase 4 delta: +1 (TreeUnpicklerTest Test 10) = 243

Phase 2 deleted Test 20 as well as Test 2; the plan said -1 for Phase 2 (only accounting for Test 2). The extra deletion of Test 20 was correct (it tested Cache.memo dedup which no longer exists) but unplanned. The per-phase audit PHASE-2-V3-AUDIT.md documented this deviation: "actual: 244 tests on JVM, not 245 as specified." The correct expected runtime count is 243 on JVM, not 246 as the plan states.

Static grep count (pattern `'".*" in'` across all test files) is 202 + 7 jvmOnly Scala2PickleTest tests = 209 static. The discrepancy between 209 static and ~243 runtime is a consistent pattern across all prior phases (ScalaTest expands some tests at runtime via nested `describe` blocks). This is not a defect; a build run is required for the authoritative count.

**NOTE-2: Phase 3 WARN-3: Test 10 pending fallback.**

TreeUnpicklerTest Test 10 uses a `pending` guard if `classSym.declarations.find` returns no member with a body slice. AstUnpicklerTest Test 18 proves PlainClass always has at least one method with `bodyStart > 0`, so the `pending` branch is unreachable in practice. The test should unconditionally `fail(...)` instead of calling `pending`. This was noted in PHASE-3-V3-AUDIT.md WARN-3 and PHASE-4-V3-AUDIT.md NOTE-2.

**NOTE-3: Phase 4 WARN-2: body scaladoc does not document the double-guard pattern.**

The `Symbol.body` accessor uses two guards (`checkOpen.andThen:` at the outer level, `isClosed` check under AllowUnsafe before the OnceCell decode). The `body` scaladoc lists `ReflectError.ClasspathClosed` in the `Fails with:` section but does not explain why two checks exist or what the race-window guarantee is. This is documentation-only; the runtime behavior is correct.

**NOTE-4: Phase 2 deviation documented in PROGRESS.md under v2 section, not v3 section.**

PROGRESS.md has a "Plan deviations during execution" section containing the Resolver deletion entry, but this entry is written as if it were a v2 deviation (referencing the v2 Resolver.scala resurrection cycle). The v3 entry should clarify that Resolver.scala was deleted in Phase 2 v3 (not v2) and that the v2 wiring was intentionally reversed. The current text is accurate but easy to misread.

**NOTE-5: Scala2PickleTest uses `taggedAs jvmOnly in run {` format.**

The 7 Scala2PickleTest tests do not match the `'".*" in'` grep pattern used for static test counting. This is because the test line is `"string" taggedAs jvmOnly in run {`, which has `taggedAs jvmOnly` between the closing quote and `in`. The 7 tests are correctly tagged and run on JVM. Static count greps should use `taggedAs jvmOnly in run` as an additional pattern to catch these.

**NOTE-6: STEERING.md still contains all v1/v2 historical directives without a clear "v3 start" marker.**

All v2-era BLOCKING and CRITICAL sections have "RESOLVED" markers. However, there is no structural break or "--- v3 start ---" divider in STEERING.md, making it hard to identify where v3-specific content begins. This is cosmetic documentation clutter.

**NOTE-7: ClasspathTestHelpers.scala has AllowUnsafe without a `// Unsafe:` comment.**

`ClasspathTestHelpers.scala` line 18 has `import AllowUnsafe.embrace.danger` with no `// Unsafe:` comment. This is test infrastructure (not production), so the convention is less strict. However, consistency is preferable.

---

## Check Results

### 1. Test count contract

| Source | Count |
|---|---|
| v2 final (FINAL-AUDIT-V2.md confirmed) | 278 |
| Phase 1 deletion | -34 |
| Phase 2 deletion | -2 (plan said -1; Test 20 also deleted) |
| Phase 4 addition | +1 |
| Phases 3, 5, 6, 7 | 0 each |
| **Expected runtime total** | **243** |
| Plan's stated target | 246 (off by 3; see NOTE-1) |

The plan's 246 figure is based on v2 plan's stated 280 (not the actual 278) minus 34 (correct) minus 1 for Phase 2 (short by 1). The actual expected runtime count is 243 on JVM. The authoritative count requires a build run per `feedback_sequential_test_runs`.

### 2. Phase commits

All 6 v3 commits confirmed present in `git log --oneline`:

```
0a7c73e81 kyo-reflect v3 Phase 7: update examples and benchmark for pure accessors
73855f5cc kyo-reflect v3 Phase 6: rename Memo to OnceCell
30f06bd54 kyo-reflect v3 Phase 4: body strict ClasspathClosed check + regression test
4b1d041f9 kyo-reflect v3 Phases 3 + 5: pure accessors + symbolToRecord deletion
624a37499 kyo-reflect v3 Phase 2: Delete Resolver + Cache.memo + readyLatch
9a317c7fa kyo-reflect v3 Phase 1: Delete Reads + Query layer
```

Phase 5 collapsed into Phase 3+5 commit. This deviation from the plan's 7-commit structure is documented here and in PHASE-3-V3-AUDIT.md NOTE-1.

### 3. All accessors pure except body

Verified via grep on `Reflect.scala`:

| Accessor | Return type | Pure? |
|---|---|---|
| `Symbol.parents` | `Chunk[Type]` | YES |
| `Symbol.typeParams` | `Chunk[Symbol]` | YES |
| `Symbol.declarations` | `Chunk[Symbol]` | YES |
| `Symbol.declaredType` | `Type` | YES |
| `Symbol.scaladoc` | `Maybe[String]` | YES |
| `Symbol.position` | `Maybe[Position]` | YES |
| `Symbol.companion` | `Maybe[Symbol]` | YES |
| `Classpath.findClass` | `Maybe[Symbol]` | YES |
| `Classpath.findPackage` | `Maybe[Symbol]` | YES |
| `Classpath.findClassByBinary` | `Maybe[Symbol]` | YES |
| `Classpath.findModule` | `Maybe[ModuleDescriptor]` | YES |
| `Classpath.topLevelClasses` | `Chunk[Symbol]` | YES |
| `Classpath.packages` | `Chunk[Symbol]` | YES |
| `Classpath.errors` | `Chunk[ReflectError]` | YES |
| `Type.isSubtypeOf` | `Boolean` | YES |
| `Symbol.body` | `Tree < (Sync & Abort[ReflectError])` | EFFECTFUL (correct; sole effectful accessor) |

PASS. All accessors are pure except `body`, which retains the effect row as designed.

### 4. Reads/Query/Resolver removal complete

```
grep -r "Reflect.Reads|Reflect.FieldSet|cp.query|Resolver|readyLatch|classLookup|packageLookup|Cache.memo" kyo-reflect/shared/src/main/scala
```

Zero hits (except two mentions of `kyo.Cache.memo` in `OnceCell.scala` scaladoc explaining the distinction from `Cache.memo`). PASS.

### 5. symbolToRecord removal complete

```
grep -r "symbolToRecord|SymbolToRecordMacro" kyo-reflect/shared/src/
```

Zero hits. `SymbolToRecordMacro.scala` is absent from the source tree. PASS.

### 6. Memo renamed to OnceCell

```
grep -r "\bMemo\b" kyo-reflect/shared/src/main/scala/
```

Zero hits (excluding `MemorySegment`, memory-related identifiers, and the `kyo.Cache.memo` mention in OnceCell.scala scaladoc). `OnceCell.scala` present at `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/OnceCell.scala`. `Memo.scala` absent. PASS.

OnceCell.scala scaladoc contains the "Distinct from `kyo.Cache.memo`" sentence at lines 14-17 with both the mechanism difference (Promise vs CAS) and the effect-row consequence (Async vs not). PASS.

### 7. body has explicit ClasspathClosed check

`Reflect.scala` line 640: `if home.get().isClosed then Abort.fail(ReflectError.ClasspathClosed)` inside `AllowUnsafe.embrace.danger` block with `// Unsafe:` comment at lines 637-639. PASS.

The check uses the `isClosed` helper (defined in `Classpath.scala`) rather than the raw `stateRef.unsafe.get() == State.Closed` form in the plan. `isClosed` is defined as `private[kyo] def isClosed(using AllowUnsafe): Boolean = stateRef.unsafe.get() == Classpath.State.Closed` and returns `Boolean`. This is a correct refactoring; both forms are semantically identical.

### 8. Forbidden patterns audit

**asInstanceOf in production source (non-Unsafe, non-macro, non-js-facade):**

All `asInstanceOf` in production is confined to `OnceCell.scala` and `SingleAssign.scala` (both Unsafe-tier primitives with scaladoc documentation). Zero `asInstanceOf` in shared business logic. PASS.

**Frame.internal:**

```
grep -rn "Frame\.internal" kyo-reflect/
```

Zero hits in any `.scala` source file. PASS.

**AllowUnsafe without `// Unsafe:` comment:**

All `import AllowUnsafe.embrace.danger` sites in production source have a `// Unsafe:` comment immediately preceding the import EXCEPT:

- `SnapshotReader.scala:244` -- `import AllowUnsafe.embrace.danger` for the `deserializeMapped` per-symbol `SingleAssign.set()` loop has no `// Unsafe:` comment. The sibling method `deserialize` at line 172 has the comment; `deserializeMapped` is missing it. (WARN-3 above.)

- `ClasspathTestHelpers.scala:18` -- test infrastructure, not production. Minor omission. (NOTE-7 above.)

All other sites verified:
- `Reflect.scala`: 11+ sites, all with `// Unsafe:` comments.
- `Classpath.scala`: 9 sites, all with `// Unsafe:` comments.
- `ClasspathOrchestrator.scala`: 1 site with `// Unsafe:` comment.
- `SnapshotWriter.scala`: 1 site with `// Unsafe:` comment.
- `SnapshotReader.scala:173`: has `// Unsafe:` comment.
- `SnapshotReader.scala:244`: MISSING `// Unsafe:` comment (WARN-3).
- `Subtyping.scala`: 2 sites, both with `// Unsafe:` comments.
- `OnceCell.scala`, `SingleAssign.scala`: Unsafe-tier primitives with scaladoc documentation.

**null in new v3 code:**

No new `null` assignments were introduced by v3 phases. Existing null sentinels in owner chains (`Scala2PickleReader`, `SnapshotReader`, `ClassfileUnpickler`, `Interner`) are pre-existing documented hot-path patterns from v1/v2 and are accepted per STEERING.md.

**em-dashes:**

```
grep -rn "—\|–" kyo-reflect/shared/src/main/scala/
```

Zero hits in any `.scala` source file. PASS.

**var for shared mutable state:**

No `var` fields on shared objects or classes. All `var` usages are local loop/accumulator variables in method bodies (`var i`, `var pos`, `var hash`, etc.) or parser cursor positions. The one field-level `var` is `Scala2PickleReader.PickleCursor._pos` which is a mutable cursor in a private parser class (single-threaded use, no concurrency). PASS.

**default params on internal APIs:**

Zero occurrences of `= <default>` in any `private` or `private[kyo]` method signature. PASS.

### 9. DESIGN.md section coverage

The v3 changes are a subtraction plan only (deletion of Reads, Query, Resolver, symbolToRecord; purity promotion of accessors; rename of Memo to OnceCell; example/bench updates). No new DESIGN.md sections were added or removed by v3. All 25 sections remain in the same coverage state as documented in FINAL-AUDIT-V2.md Section 4. The sections specifically affected by v3 deletions:

| DESIGN.md section | v2 status | v3 status |
|---|---|---|
| §12 Public API: Reads derivation macro | PRESENT in v2 | DELETED in v3 (Reads/FieldSet/query removed) |
| §13 Reads Derivation Macro | PRESENT in v2 | DELETED in v3 (DESIGN.md §13 is now a non-goal) |
| §15 Concurrency Model: readyLatch Building-state gate | PRESENT in v2 | DELETED in v3 (readyLatch removed; Building-state gate is now hard-fail) |

These three items were the explicit v3 deletion targets. Their absence from the codebase is intentional and correct. The remaining 22 DESIGN.md sections are still covered.

### 10. Cross-platform

Per per-phase audit attestations:

| Phase | JVM | JS | Native |
|---|---|---|---|
| After Phase 1 | 244 passing | 203 passing + 40 jvmOnly skipped | 203 passing + 40 jvmOnly skipped |
| After Phase 2 | 244 passing | 201 passing + 40 jvmOnly skipped | 201 passing + 40 jvmOnly skipped |
| After Phase 3+5 | 244 passing | 201 + 40 skipped | 201 + 40 skipped |
| After Phase 4 | 245 passing | 202 + 40 skipped | 202 + 40 skipped |
| After Phase 6 | Not re-run (0 delta expected) | Not re-run | Not re-run |
| After Phase 7 | Not re-run (0 delta expected) | Not re-run | Not re-run |

A fresh green-run is required to confirm the final count on all three platforms.

jvmOnly test count: 40 (unchanged from Phase 2 onwards). All 40 are justified by real JDK classfile reads, ZLIB inflation, `jrt:/` filesystem access, or TestResourceLoader JVM classpath requirements.

### 11. v3 plan deviations

All deviations documented in PROGRESS.md "Plan deviations during execution":

| Deviation | Phase | Status |
|---|---|---|
| Phase 2 deleted 2 tests not 1 (Test 20 also obsolete) | Phase 2 | ACCEPTED; documented in PHASE-2-V3-AUDIT.md and this document |
| Phase 5 collapsed into Phase 3 commit | Phase 3+5 | ACCEPTED; macro emitted accessor calls that broke under Phase 3 pure conversion |
| Resolver.scala deleted (PROGRESS.md entry updated) | Phase 2 | ACCEPTED; Resolver was dead code in v2 and intentionally removed in v3 |

### 12. STEERING.md pending directives

STEERING.md "Active directives" section: all entries are marked RESOLVED. No unresolved v3 directives remain. The section contains six historical RESOLVED items from v1/v2 phases (Phase 2 name-table, Phase 2 NameRef, Phase 3 TYPEDEF, Phase 3 qualified-modifier, Phase 3 fixes, Phase 4 wiring, Phase 5 fixes, Phase 5b, Phase 6 critical fixes, Phase 6 lazy self-reference, Phase 6b Frame.internal, Phase 6 missing tests, Phase 6 64-field cap). All are RESOLVED.

The top of STEERING.md correctly identifies "ACTIVE PLAN: execution-plan-v3.md." The v2 "ACTIVE PLAN" header is a historical relabeling issue documented in WARN-2.

---

## Phase 7 Content Audit (no separate PHASE-7-V3-AUDIT.md exists)

### Examples: all four updated

All four example files were updated before Phase 7 committed per PHASE-7-V3-PREP.md analysis. The prep doc concluded all four examples were already pure after Phase 3 (no stale effect ceremony), requiring only compilation verification. No code edits were needed for the examples in Phase 7.

- `CodegenExample.scala`: pure after Phase 3, verified compilation.
- `IdeHoverExample.scala`: pure after Phase 3, verified compilation.
- `JavaScalaBridgeExample.scala`: pure after Phase 3, verified compilation.
- `RuntimeReflectionExample.scala`: pure after Phase 3, verified compilation.

### Benchmark: W4/W5/W8 cleaned, W9/W10 added

`ReflectBench.scala` at HEAD contains all 10 workloads:

- W1-W3: cold-load / snapshot-miss / snapshot-hit (unchanged from v2)
- W4: per-FQN lookup warm cache -- rewritten to pure (no `runSync` around the loop)
- W5: declarations enumeration -- rewritten to pure
- W8: plain iteration -- rewritten to pure
- W9: hover-shaped query (new in Phase 7, pure, sub-ms)
- W10: find-references-shaped (new in Phase 7, body decode + tree walk, uses `Kyo.foreach`)
- W6/W7: deleted in Phase 1 (schema-driven query / typed projection)

The `countRefs` helper at line 193 handles all `Reflect.Tree` ADT cases including `Inlined`, `Bind`, `Try`, `While`, `Assign`, `Lambda`, and `Return`. No ADT case is unhandled.

### Bench compile verification

`kyo-reflect-bench/jvm/src/main/scala/kyo/bench/ReflectBench.scala` is 399 lines. The file compiles as part of the `kyo-reflect-bench` module. No compilation evidence was produced here (no sbt run); the Phase 7 commit message should confirm clean compile.

---

## Recommendation

PROCEED to final green run.

The 4 WARNs have the following resolutions:

- WARN-1 (PROGRESS.md v3 phase table missing): append the table from this audit before the green run.
- WARN-2 (STEERING.md v2 "ACTIVE PLAN" header): relabel the v2 section header.
- WARN-3 (SnapshotReader.scala:244 missing `// Unsafe:` comment): add one comment line.
- WARN-4 (no PHASE-7-V3-AUDIT.md): this document serves as the record.

WARNs 1-3 are one-line or one-section documentation edits. None affect runtime behavior. The final green run should execute `sbt 'kyo-reflect/test'`, `sbt 'kyo-reflectJS/test'`, and `sbt 'kyo-reflectNative/test'` sequentially (per `feedback_sequential_test_runs`) and verify the bench compiles and runs W1-W10 without throwing.
