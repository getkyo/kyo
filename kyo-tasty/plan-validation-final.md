# kyo-reflect Plan: Final Validation

## Verdict: FAIL

One mechanical numeric inconsistency (Phase 7 test count) and one missing exclusion-rationale (Phase 5/4 sequencing not labeled as such, only embedded in `Dependencies`). Everything else is contract-ready.

## Check A: 32 audit items

| # | Resolution in plan? | Line citation |
|---|---------------------|---------------|
| 1 | YES | 470 (`report.errorAndAbort("requires a named class symbol")`) |
| 2 | YES | 471 (pure-only -> all kinds; structural -> Class/Trait/Object) |
| 3 | YES | 607 (`fs.readFileSync` + `Int8Array` copy) |
| 4 | YES | 616 (`Arena.ofShared`/`MemorySegment`, MappedByteBuffer dropped) |
| 5 | YES | 19 (`crossProject(JSPlatform, JVMPlatform, NativePlatform).crossType(CrossType.Full).in(file("kyo-reflect-fixtures"))`, `% Test` link) |
| 6 | YES | 15 (verbatim copy, package `kyo`, no edits) |
| 7 | YES | 599 (cites `kyo-core/shared/src/main/scala/kyo/Cache.scala` with signature) |
| 8 | YES | 600 (`kyo.Promise` from `kyo-core/.../Promise.scala`) |
| 9 | YES | 59 (zigzag, references `dotty.tools.tasty.TastyBuffer.readInt`) |
| 10 | YES | 135 (QUALIFIED=1...TARGETSIGNED=62 enumerated) |
| 11 | YES | 344 (`Wildcard(lower, upper)`; `+T -> (Nothing, T)`, `-T -> (T, Object)`, `* -> (Nothing, Object)`) |
| 12 | YES | 602 (`exists` returns `Boolean < Sync` with explicit rationale: short-circuit guard, `false` on error) |
| 13 | YES | 203 (constraint stated: no Phase 3 code may call `home.checkOpen`/`home.get`; audit of `computeFullName`/`computeBinaryName`) |
| 14 | YES | 209 (Phase 3 declares both `TastyOrigin` and `JavaOrigin`; Phase 5 only adds construction sites) |
| 15 | YES | 442 (test 18 explicitly defers `Unresolved` coverage to Phase 7 t21 with synthesized partial-classpath fixture) |
| 16 | YES | 240 (`addrMap(T1Addr).name.asString == "T1"`, `addrMap(T2Addr).name.asString == "T2"`) |
| 17 | YES | 310 (fixture `type Tup[X] = X match { case Int => String; case _ => Int }`; `cases.size == 2`, named scrutinee `X`) |
| 18 | YES | 434 (assert `methodName.asString == "enclosingMethodFixture"`) |
| 19 | YES | 680 (`topLevelClasses.size == n-1` AND `errors.size == 1`) |
| 20 | YES | 509 (assert `touchedFields` excludes `FieldSet` bits appearing only in `Bind` pattern) |
| 21 | YES | 201 (`ClassLiteral(typeRef)` without symbol resolution at decode time) |
| 22 | YES | 617 (JVM/Native real mtime; JS-Node `fs.statSync().mtimeMs`; JS-browser `openCached` unavailable) |
| 23 | YES | 618 (explicit no-op on browser via FileSource guard) |
| 24 | YES | 186 (Native: `Test/resourceDirectory` copy + `scalanative.runtime.resource.EmbeddedResource` lookup or test-runner shim) |
| 25 | YES | 600 (`ClasspathClosed` only; missing FQN returns `Absent`) |
| 26 | YES | 615 (adds `ReflectError.SnapshotIoError(cause: String)`) and 671 (test 24a) |
| 27 | YES | 672 (test 24b: missing root produces `Abort.fail(ReflectError.FileNotFound)`) |
| 28 | YES | 396 (JDK 25 + jrt:/ assumption + fixture-bytes fallback) |
| 29 | YES | 617 (FNV-1a 64-bit hash, ~30 LOC pure Scala, no external dep, SHA-256 dropped) |
| 30 | YES | 673 (`Async.parallel(2)`, no `Thread.sleep`, `Async.timeout(1.second)`, flakiness budget zero) |
| 31 | YES | 244 (`kyo.Latch` two-fiber fixture + `Async.timeout(1.second)`) |
| 32 | YES | 684 (test 36: `AtomicInt` increment in `Scope.run` acquire, decrement in finalizer, assert counter == 0) |

All 32 items resolved.

## Check B: 3 supervisor items

A. Phase 5/Phase 4 sequencing rationale: **PARTIAL PASS**. The Phase 5 `Dependencies` block at line 337 explains the relationship ("Depends on Phase 4 ONLY for the `Type` ADT case classes ... Does not use Phase 4's `TypeUnpickler` ..."). Not labeled "sequencing rationale" but the substantive content (why Phase 5 is sequenced after Phase 4 and what it does/doesn't use) is present.

B. module-info.class exclusion with rationale at Phase 5: **PASS** - line 339 ("Non-Goals for Phase 5: `module-info.class`. ... Rationale: kyo-reflect reads class metadata; module-info declares which packages a module exports, which is a runtime ClassLoader concern, not a metadata concern.").

C. Snapshot incremental refresh exclusion at Phase 7: **PASS** - line 612 ("Excluded from Phase 7 snapshot: incremental snapshot refresh. ... Rationale: incremental refresh would require per-file digest tracking, partial-snapshot reuse, cascading invalidation of cross-file type references, and stale-arena handling ... Impl agents must not add incremental refresh to Phase 7.").

## Check C: Supervisor overrides

Item 4 MemorySegment: **PASS** - line 616 uses `java.lang.foreign.Arena.ofShared().allocate(size, 1)` returning `MemorySegment`. `MappedByteBuffer` never appears in the plan. Rationale baked: "JDK 25 makes `MemorySegment` available; aligned with `kyo-offheap`'s `Memory[Byte]` API; supports snapshots larger than 2 GB cleanly; explicit Arena close produces deterministic release."

Item 29 FNV-1a: **PASS** - line 617 uses "FNV-1a 64-bit hash of sorted (path, mtime, size) tuples (approximately 30 LOC of pure Scala, identical on all platforms, no external dependency)". `SHA-256` never appears. Rationale baked: "non-cryptographic and is sufficient for cache-invalidation purposes; users who need cryptographic-strength digests may supply a custom digest function via a future `Classpath.openCached(roots, digest = customDigest)` hook, which is NOT part of v1."

## Check D: 13 rules

- Rule 1 (banned phrases): **PASS** - `grep -i -E "TBD|polish|consider|if time permits|tighten|investigate further|figure it out|depending on"` returns zero hits. Line 3 was rephrased to "that depends on" (no longer matches).
- Rule 2 (priority language): **PASS** - `grep -E "priority|importance|urgency|tier|nice to have|critical to ship|focus on .* first"` returns zero hits.
- Rule 3 (concrete leaves): **PASS** - spot-checked Phase 3 t19/t21/t22/t23, Phase 4 t4/t11/t16/t17/t21, Phase 7 t15/t19/t25/t28/t32/t35/t36. All carry concrete identifiers, fixture references, and exact assertions. No "edge cases" / "additional tests" placeholders.
- Rule 4 (phase isolation): **PASS** - Phase 3 `ClasspathRef` `SingleAssign` constraint explicitly audited at line 203 (no `home.checkOpen`/`home.get` calls in Phase 3); Phase 5b t18 `Unresolved` deferred to Phase 7 t21 (line 442). All forward references explicit and slot-based.
- Rule 5 (total tests per phase): **FAIL** - Phase 7 declares `Total tests: 36` at line 686 but the list (lines 647-684) contains 38 entries: tests 1-32 plus 24a, 24b, 33, 34, 35, 36. Also the supervisor check at line 704 says "All 32 tests pass" (stale). Summary table at line 722 says 36/cumulative 194 (consistent with declared, not actual). Three numbers do not agree: declared 36, supervisor 32, actual 38. **Cumulative total should be 196, not 194.**
- Rule 6 (DESIGN.md leaves): **PASS** - per-class declaration tables added at Phase 3 line 200 with t21-t23 covering flat-array vs HashMap cutover and CAS-swap visibility. `findClassByBinary` added at Phase 7 line 629 (extension method) with t33-t34. Cross-classpath FQN structural equality covered by Phase 7 t35. Inner-`Scope.run` finalizer/FD release on interrupt covered by Phase 7 t36. mmap path detailed at line 616 (JVM `MemorySegment`, Native POSIX `mmap` FFI, JS Array fallback).
- Rule 7 (dependency justification): **PASS** - every phase Dependencies line cites specific symbols/APIs.
- Rule 8 (signature-level API delta): **PASS** - every Public API block names full signatures.
- Rule 9 (exact verification command): **PASS** - every phase ends with `sbt 'project kyo-reflect; testOnly kyo.XTest ...'`; Phase 7 splits JS and Native into separate sbt invocations per `feedback_sequential_test_runs`.
- Rule 10 (specific supervisor checks): **PASS** - 4-7 specific bullets per phase. (Phase 7 has the stale "All 32 tests pass" which is a Rule 5 issue, not Rule 10.)
- Rule 11 (Phase 0.5 fixes Version + fixtures): **PASS** - lines 18 (`Version(28, 8, 0)`) and 19 (`kyo-reflect-fixtures` cross-project).
- Rule 12 (Test.scala in Phase 0.5): **PASS** - line 15 places `kyo-reflect/shared/src/test/scala/kyo/Test.scala` (verbatim from kyo-actor) in Phase 0.5's `Files to produce`; line 32 has `FixtureCompilationTest` test 2 confirming `Test.scala` compiles.
- Rule 13 (macro location consistent): **PASS** - macro entry points at `kyo/internal/ReflectMacro.scala` (line 469) and `kyo/internal/SymbolToRecordMacro.scala` (line 540) are flat. Internal helpers under `kyo/internal/reflect/reads/*` (line 477 `ReadsInstances.scala` renamed from `Reads.scala` to avoid collision; line 478 `TouchedFields.scala`; line 539 `RecordReads.scala`). Convention: flat for top-level macro entry, sub-package for internal supporting types. Naming collision with `Reflect.Reads` resolved.

## Check E: Regressions

The 35 edits introduced one new defect:

1. **Phase 7 test count drift (3-way disagreement)** - Adding tests 24a, 24b, 33, 34, 35, 36 raised the actual count from 32 to 38, but neither `Total tests: 36` (line 686), the supervisor "All 32 tests pass" line (704), nor the summary table cell "36 | 194" (line 722) were synchronized. Correct numbers: total tests = 38, supervisor "all 38 tests pass", summary table cell `38 | 196`.

No other regressions detected. All other edits land cleanly and resolve their audit items without breaking adjacent prose.

## Check F: Impl-time questions

Reading the full plan as an implementation agent would, the only place I would have to ask is:

1. **Phase 7 test count**: Which is the contract - 32, 36, or 38? Once a human picks 38 and synchronizes the three numbers, the agent has no remaining ambiguity.

Every other implementation question is answered:

- All file paths absolute and complete
- All public signatures verbatim
- All test assertions specify concrete identifiers and expected values
- All fixture inputs named (by class, by file, by attribute)
- All cross-platform divergences resolved per platform (JVM/JS-Node/JS-browser/Native)
- All deferred items explicitly excluded with rationale and "Impl agents must not" guard rails (module-info.class, incremental snapshot refresh)
- All cross-phase seams (SingleAssign slots, UnresolvedRef placeholders, ADT case introduction vs construction) explicit with constraint audits
- All error paths enumerated (ReflectError ADT cases tied to specific test numbers)
- All concurrency tests bounded (`Async.timeout(1.second)`, `Async.parallel(N)`, zero `Thread.sleep`, named `kyo.Latch` / `AtomicInt` mechanisms)

## Summary

The plan is **one numeric fix away from contract-ready**. The single FAIL is mechanical: Phase 7 lists 38 tests (1-32 + 24a + 24b + 33 + 34 + 35 + 36) but three separate places say 32 or 36. Fixing this requires three edits:

1. Line 686: change `**Total tests**: 36` to `**Total tests**: 38`.
2. Line 704: change "All 32 tests pass on JVM" to "All 38 tests pass on JVM".
3. Line 722: change `| 36 | 194 |` to `| 38 | 196 |`, and line 724 change `Total: **194 tests**` to `Total: **196 tests**`.

After this single mechanical fix, the plan is contract-ready: 196 tests across 10 phases (Phase 0.5 = 2, Phase 1 = 24, Phase 2 = 15, Phase 3 = 23, Phase 4 = 24, Phase 5 = 20, Phase 5b = 18, Phase 6 = 18, Phase 6b = 14, Phase 7 = 38), every audit item resolved, every supervisor item baked, both overrides honored, all 13 contract rules satisfied, and zero implementation-time questions remaining (after the three-edit numeric resync).
