# kyo-reflect Execution Plan Validation

Overall verdict: **FAIL** (one Rule-1 hit, one Rule-4 cross-phase reference, several Rule-6 coverage gaps, one Rule-12 wrong placement, one Rule-13 inconsistency).

## Rule 1: No vague phrasings
**FAIL (borderline).** The only matched banned phrase is "depending on" at line 3 ("a cross JVM/JS/Native project depending on `kyo-core`"). This reads as a factual dependency description rather than a hedge, so it is semantically clean, but the literal banned phrase is present. Recommendation: rephrase to "that depends on `kyo-core`" to fully comply. No other banned phrases (TBD / polish / consider / if time permits / tighten / investigate further / we'll figure it out) appear anywhere in the file.

## Rule 2: No priority/preference language
**PASS.** `grep -E "priority|importance|urgency|tier|nice to have|critical to ship|focus on .* first"` returns zero hits. The plan tables (lines 691-703, 710-767) use columns `Phase | Name | New tests | Cumulative` and `DESIGN.md section | Covered by phase` only. No ordering connotation language anywhere.

## Rule 3: Every leaf concrete
**PASS.** Spot-checked Phase 7's 32-test list (longest). Sampling: test 4 ("`cp.findPackage("kyo.fixtures")` returns `Present(pkg)` with `pkg.kind == SymbolKind.Package`"), test 19 ("two concurrent `findClass("kyo.fixtures.FixtureClasses")` calls produce reference-equal `Symbol` instances"), test 25 ("two concurrent snapshot writers for the same input produce one valid snapshot file"), test 28 (`evictOlderThan(0.millis)` removes all snapshot and tmp files), test 32 (Phase B interruption with synthesized corrupt TASTy). All are one-sentence concrete scenarios with specific identifiers and expected outcomes. No "concurrency tests" / "edge cases" placeholders. Phase 1 tests 7-14 (Varint specific values 0/127/128/16383/Int.MaxValue/-1/Int.MinValue/Long.MaxValue) are similarly concrete.

## Rule 4: Phase compiles in isolation
**FAIL.** Phase 3 (line 200) introduces `kyo/internal/reflect/query/ClasspathRef.scala` containing a forward reference placeholder for `Reflect.Classpath`. Phase 3's `Symbol.makeSymbol` signature (line 196) takes `home: ClasspathRef`, which depends on a future type. The plan acknowledges this: "set via `SingleAssign` during Phase 7 orchestration". As a forward declaration this works (the type itself is local), but Phase 3 cannot exercise `home` until Phase 7. Phase 4's `UnresolvedRef.replaceSlot` (line 266) writes resolved symbols set in Phase 7. Both are flagged as deliberate slot-based seams, so isolation holds *for compilation*; they pass compilation in isolation. Phase 4 file `TypeUnpickler.scala` (line 265) accepts `addrMap: Map[Int, Reflect.Symbol]` which comes from Phase 3's `Pass1Result` (also stated in Phase 4 deps). No Phase 5b → Phase 5 backflow detected. Result: Phase 3 → Phase 7 forward `SingleAssign` is the only soft violation; isolation of pure compilation is preserved.

## Rule 5: Total test count stated per phase
**PASS.** Every phase contains an explicit `**Total tests**: N` line: 0.5=1, 1=24, 2=15, 3=20, 4=24, 5=20, 5b=18, 6=18, 6b=14, 7=32. Sum 186 matches the table on line 704.

## Rule 6: Every DESIGN.md section 6-16 leaf appears
**FAIL (partial).** Coverage table lines 710-767 maps most leaves. Gaps found:

- §6 `Positions` and `Comments` sections explicitly *skipped in v1* per DESIGN.md line 214: not stated as a non-deliverable in the plan, but skipping is the correct outcome; this is a documentation-only gap.
- §7 `Symbol cache` per-class declaration tables (`Dict[Name, Symbol]` for ≤8 members, `AtomicRef[Map]` CAS-swap) at DESIGN line 429 is **not mentioned in any phase**. Likely belongs in Phase 3 or Phase 7.
- §9 `Type.RecPlaceholder` (line 503 of DESIGN.md) is used in the merge pseudocode but is not in the public `Type` ADT. The plan covers the merge logic in Phase 4 test 4 (Rec cycle), but does not call out `RecPlaceholder` as an internal type — minor omission.
- §11 "Cross-classpath uniformity" claim (DESIGN line 607) is not tested. Phase 7 tests cover `findClass` on a single classpath only, not cross-classpath symbol comparison via `fullName` (DESIGN line 392).
- §12 `findClassByBinary(binaryName)` mentioned at DESIGN line 576 as a separate entry point. **Not in the plan or skeleton.**
- §13 `Maybe.fold` semantic (DESIGN line 867) is a usage note, not a deliverable; ignorable.
- §16 `JVM mmap` and `Native mmap` and `JS read-into-Array fallback` (table rows 765-767) say "Phase 7" but Phase 7's `SnapshotReader` and platform `FileSource` writeups (lines 597-605) do not mention `FileChannel.map` / POSIX `mmap` FFI / browser blob fallback specifically. Tests 22-30 cover round-trip semantics, not the mmap performance path.
- §15 `Async.foreach` interruption tests: test 32 covers per-fiber failure but DESIGN line 1202-1208 also specifies inner-`Scope.run` finalizers firing on interrupt. No test verifies finalizer ordering / FD release on interrupt.

## Rule 7: Each phase has dependency justification
**PASS.** Each `**Dependencies**` line cites specific symbols or APIs. Phase 2 → "needs `ByteView`, `Varint`, `Utf8`, `TastyHeader`, `TastyFormat` tag constants". Phase 6 → "needs `Reflect.Symbol` API surface stable; `TypeArena` and resolved symbol graph stable enough for `touchedFields` analysis to be meaningful." No hand-waved entries.

## Rule 8: Public API delta is signature-level
**PASS.** Public API entries cite full signatures where they change. Phase 6b: `inline def symbolToRecord[F](sym: Symbol): Record[F] < (Sync & Abort[ReflectError])`. Phase 7: `extension (cp: Classpath) def query[A](using Reads[A]): Query[A]`. Phase 0.5: explicit `Version(28, 8, 0)` constant change. Phase 6: stub-to-macro change with full body. No placeholder "add the X method" entries.

## Rule 9: Verification command is exact
**PASS.** Every phase ends with a runnable `sbt 'project kyo-reflect; testOnly kyo.XTest kyo.YTest'` block plus a cross-platform compile/test. Phase 7 splits JS and Native into separate `sbt` invocations per `feedback_sequential_test_runs`. The form `sbt 'kyo-reflect-fixtures/compile; kyo-reflect-fixturesJS/Test/compile'` in Phase 0.5 is valid sbt syntax.

## Rule 10: Supervisor checks are specific
**PASS.** Every phase has 4-6 `**Supervisor checks**` bullets citing specific file paths, constant values, or test numbers. Example: Phase 5 line 386-387 "test 11 passes", "tests 14-18 pass". No "make sure it works" entries.

## Rule 11: Phase 0.5 / prereq fixes correctly handled
**PASS.** Phase 0.5 (lines 7-49) explicitly addresses both known Phase 0 issues: TASTy version constant `Version(28, 9, 1)` → `Version(28, 8, 0)` (line 17), and adding `kyo-reflect-fixtures` cross-project (line 18). The fixture sub-module includes per-tag-category Scala source. Verification command is exact and supervisor checks are concrete.

## Rule 12: Test base class properly used
**FAIL.** The plan places `kyo-reflect/shared/src/test/scala/kyo/Test.scala` (copy from kyo-actor) in Phase 1's `**Files to produce**` (line 65). Per the instruction it should land in Phase 0.5 (alongside the fixtures sub-module) so that Phase 0.5's `FixtureCompilationTest` can extend it. As written, Phase 0.5 test 1 (line 30) has no documented base class, and Phase 1 introduces `Test` only when its own tests appear. This is a minor sequencing slip; functionally Phase 0.5's one test can use `AnyFreeSpec` directly, but the plan does not say so.

## Rule 13: Macro location matches kyo convention
**FAIL (inconsistency).** The plan resolves the macro entry point at `kyo/internal/ReflectMacro.scala` (Phase 6 line 460, flat path) and `kyo/internal/SymbolToRecordMacro.scala` (Phase 6b line 531, flat path). This matches the requested convention for the macro entry. However, supporting types are split:

- `kyo/internal/reflect/reads/Reads.scala` (Phase 6 line 468) — sub-package
- `kyo/internal/reflect/reads/TouchedFields.scala` (Phase 6 line 469) — sub-package
- `kyo/internal/reflect/reads/RecordReads.scala` (Phase 6b line 530) — sub-package

So the plan does pick "sub-packages for internal types, flat for macro entry," which is the third acceptable option in the rule statement. But Phase 6 line 468 declares a *built-in `Reflect.Reads` instances object* named `Reads.scala`, which collides with the existing top-level `kyo.Reflect.Reads` trait in `Reflect.scala`. Different namespace (`kyo.internal.reflect.reads.Reads` vs `kyo.Reflect.Reads`), but the basename clash will confuse readers. Renaming to `ReadsInstances.scala` would resolve it. Counted as a FAIL because the rule asks for consistent treatment and "Reads.scala holding instances, ReflectMacro.scala holding the macro" is split across two conventions.

## Cross-reference: DESIGN.md leaves → plan phases

| Leaf | Section | Phase | Status |
|---|---|---|---|
| Magic / version / UUID / tooling-version | §6.1 | 1 | covered |
| Name table eager decode | §6.2 | 2 | covered |
| Attributes (explicitNulls, captureChecked, isJava, isOutline, scala2StdLib, sourceFile) | §6.3 | 2 | covered |
| Skeleton-eager AST + lazy bodies | §6.4 | 3 | covered |
| `Addr -> Symbol` forward-ref table | §6.4 | 3 | covered (test 19) |
| `SHAREDtype` per-file `Addr -> Type` cache | §6.4 | 4 | covered (test 16) |
| Positions/Comments deferred | §6 | none | NOT mentioned as non-deliverable |
| Reads-driven pruning (needsBodies / touchedFields → unpickler) | §6 | 6 | covered (declared) — Phase 7 wiring to unpickler not detailed |
| All 14 SymbolKind cases | §7 | 5b test 18 | covered |
| ~42 Flags | §7 | 3 line 195, 5b line 403 | covered |
| `Symbol.home` + checkOpen + ClasspathClosed | §7, §15 | 3, 7 | covered (test 15) |
| `Symbol.companion`, `javaSpecific`, `isPackageObject` | §7 | 3, 5b, 6b | covered |
| Symbol cache (Cache.memo + per-class decl tables) | §7 | 7 (cache.memo only) | per-class decl table NOT mentioned |
| 32-shard intern + lazy String via Memo | §8 | 2 | covered |
| All 23 (DESIGN lists 23, not 26) Type cases | §9 | 4 | covered via TypeUnpickler tests |
| Normalization (FunctionN, TupleN, CtxFunN, Array, AndType+Singleton) | §9 | 4 | covered tests 6-11 |
| Per-thread TypeArena | §9 | 4 | covered |
| Phase C merge (bottom-up + inProgress cycle-break) | §9 | 4 (logic) + 7 (orchestrator) | covered |
| Constant pool lazy UTF-8 | §10 | 5 | covered |
| Generic signature parser (all wildcards, arrays, methods) | §10 | 5 | covered tests 13-20 |
| InnerClasses FQN canonicalization | §10, §11 | 5b | covered tests 1-3 |
| JavaMetadata (throws, annotations, enclosingMethod, accessFlags, recordComponents) | §7, §11 | 5b | covered tests 6-10 |
| Java records | §7, §11 | 5b test 8 | covered |
| Scala enum mapping | §7 | 3 test 13 | covered |
| Unresolved sentinel + soft-fail vs strict | §7, §15 | 7 tests 17-18 | covered |
| Cross-classpath structural equality on FQN | §7 | none | NOT TESTED |
| Classpath.open/openCached/fromPickles/strict | §12 | 7 | covered |
| `extension query[A]` + combinators | §12 | 7 | covered tests 9-14 |
| `classFqn` helper | §12 | 0 (skeleton) | covered |
| `findClassByBinary` | §12 | none | NOT IN PLAN |
| Reads derives, built-in instances, recursive, sum-guard, HK-guard, custom given, transitive, streaming, hygiene | §13 | 6 | covered tests 1-18 |
| symbolToRecord macro + field-to-accessor table | §11, §12 | 6b | covered |
| FileSource JVM/JS/Native | §14 | 7 | covered (mmap path not detailed) |
| Phase A/B/C orchestration | §15 | 7 tests 31-32 | covered (interrupt-finalizer ordering untested) |
| KRFL all 9 sections, magic, header, atomic rename, digest policy, endianness, versioning, eviction | §16 | 7 | covered tests 22-30 |
| Mmap (JVM `FileChannel.map`, Native POSIX `mmap`, JS blob fallback) | §16 | 7 | NAMED in coverage table; NOT in `Files to produce` |

## Summary

The plan is structurally sound, signature-level precise, and concrete at the test leaf. Six concrete fixes will bring it to contract-ready:

1. **Rephrase line 3** to remove the literal phrase "depending on" → "that depends on" (Rule 1).
2. **Move `Test.scala` copy from Phase 1 to Phase 0.5** and explicitly state Phase 0.5's `FixtureCompilationTest` extends it (Rule 12).
3. **Rename `kyo/internal/reflect/reads/Reads.scala` → `ReadsInstances.scala`** (or similar) to avoid name collision with the public `Reflect.Reads` (Rule 13).
4. **Add per-class declaration tables** (DESIGN §7 line 429) as a Phase 3 or Phase 7 deliverable, with a test for the `Dict[Name, Symbol]` ≤8 / `mutable.HashMap` >8 cutover (Rule 6).
5. **Add `findClassByBinary` to Phase 7** public API (or state explicitly that it is deferred to v2) (Rule 6, §12).
6. **Add Phase 7 tests** for: (a) cross-classpath FQN-structural-equality (§7); (b) inner-`Scope.run` finalizer firing on `Async.foreach` interrupt with file-handle release verification (§15); (c) mmap path verification per platform — `FileChannel.map` on JVM, POSIX `mmap` FFI on Native, blob-decode fallback on JS browser (§16) (Rule 6).

With these patches, the plan meets all 13 contract rules. Word count: ~1450.
