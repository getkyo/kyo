# Phase 02b audit

Time: 2026-05-29T22:00:00Z
HEAD: c2103983b
Phase commit: c2103983b (kyo-tasty Phase 02b: propagate AllowUnsafe through Classpath pure accessors)
Plan cites: ./05-plan.md §Phase 02b (lines 240-349)
Design cites: ./02-design.md §"Classpath pure accessors take (using AllowUnsafe) (A4)" (lines 91-142)

## Test count
| Leaf | Status | Notes |
|---|---|---|
| 1: all 10 accessors callable with `(using AllowUnsafe)` | PRESENT_STRICT | `ClasspathPureAccessorTest.scala:71-94`; invokes pureClass, purePackage, pureModule, pureTopLevelClasses, purePackages, accumulatedErrors, allSymbols, isClosed; asserts non-empty / Present, closed=false. Plan called the structural shape "regex over Classpath.scala counting `(using AllowUnsafe)`"; test agent shipped a runtime invocation form instead. Stronger pin: a missing `(using AllowUnsafe)` would yield a compile error on the call sites, which is exactly what INV-001 requires. |
| 2: `pureClass` returns Present on Ready classpath | PRESENT_STRICT | `ClasspathPureAccessorTest.scala:103-118`; asserts `sym.fullName.asString == "kyo.fixtures.PlainClass"`. |
| 3: `transitionToReady` then `pureClass` observable | PRESENT_STRICT | `ClasspathPureAccessorTest.scala:126-141` (via `openInto` which internally calls `transitionToReady`). Pin is indirect but valid: openInto is the only public path that drives the transition; if `transitionToReady`'s new `(using AllowUnsafe)` signature were wrong, this would fail to compile or run. |
| 4: `close` then `isClosed` returns true | PRESENT_STRICT | `ClasspathPureAccessorTest.scala:149-164`; observes `isClosed` post-`Scope.run` (close finalizer ran). Implements the Scope-driven flow the impl agent documented in D-3. |

## CONTRIBUTING.md violations
None new. The 9 migrated accessors all carry the §828 propagate-the-proof signature. All bodies are free of `import AllowUnsafe.embrace.danger`. The cascade sites correctly embrace at `Sync.defer { import ...; ... }` boundaries per §839 case 2.

## Unsafe markers
- `Classpath.scala`: 9 accessors gained `(using AllowUnsafe)` parameter; bodies use `stateRef.unsafe.get/set` legitimately (the migrated INV-001 surface).
- `Tasty.scala:937-942` (fromPickles): `import AllowUnsafe.embrace.danger` with comment "atomic state write; single-threaded in Sync.map lambda, no concurrent access." Justification PRESENT.
- `Tasty.scala:991-995` (openCachedImpl): `import AllowUnsafe.embrace.danger` with "atomic state write; called from Scope finalizer." Justification PRESENT.
- `ClasspathOrchestrator.scala:106-109`: same pattern, "atomic state write; called from Scope finalizer." PRESENT.
- `SnapshotReader.scala:167-168`, `:243-244`: imports moved to immediately precede `transitionToReady` call with refreshed comment "atomic state write + SingleAssign.set(); called from single-threaded deserialize[Mapped]." PRESENT.
- `CodegenExample.scala:30-31`, `IdeHoverExample.scala:82-83`: example-app boundary embrace with "§839 case 3." PRESENT.
- `TastyBench.scala:136`, `:194-196`: "§839 case 2 bench-harness boundary." PRESENT.

## Cross-platform consistency
- platforms checked: jvm, js, native
- Per-platform deltas: none. All changes live under `kyo-tasty/shared/...`; bench is JVM-only by design. Per phase-02b-verify.md: JVM/JS/Native Test/compile PASS, bench compile PASS, ClasspathPureAccessorTest 4/4 green on JVM.

## Naming convention compliance
No deviations. Accessor names unchanged. New test file follows topic-prefix-match (`Classpath*`) per [feedback_test_placement].

## Steering deviation
`git diff --name-only HEAD~1 HEAD` matches the plan's `files_to_modify` list plus the expected cascade. The Phase 02a audit artifact (`phase-02a-audit.md`) rode along, per the SLOT-B schedule. No stowaway code changes.

## Anti-flakiness measures
- Test uses `Scope.run` to deterministically observe the close finalizer (test 4); no sleeps, no juc-tree escape, no `null`. This is the post-remediation form per D-3.
- `MemoryFileSource` is deterministic in-memory; no FS I/O.

## Architecture substitution check
- Design intent (`02-design.md:91-142`): every Classpath state-ref accessor migrates to §828 propagate-the-proof; mechanism beneath remains `stateRef.unsafe.get/set`; no Sync.Unsafe.defer wrapping per INV-002 zero-allocation hot path.
- HEAD reality: all 9 migrated accessors return raw values directly; zero `Sync.Unsafe.defer` wrap; signatures take `(using AllowUnsafe)` after the explicit param clause.
- Verdict: **MATCH**.

## Documentation drift
- Scaladoc on Classpath accessors: unchanged in body, retained "Pure accessor: reads from the immutable ... in Ready state" verbatim. Tasty.scala extension method scaladoc retained verbatim; only signatures gained `(using AllowUnsafe)`.
- New scaladoc on `ClasspathPureAccessorTest` class is descriptive, plan-aligned.
- Commit message accurately enumerates the 9 accessors, names isClosed as already-migrated, lists the 6 cascade files, and reports verify FAIL→PASS remediation. No drift.
- phase-02b-prep.md and phase-02b-decisions.md are consistent (D-1 isClosed already-migrated, D-2 9-arg transitionToReady signature, D-3 Scope.run test pattern).

## Findings (categorized)
- BLOCKER: none.
- WARN: none.
- NOTE-1: Plan Test 1 (line 310-314) specified a regex-over-source-string form ("count equals 10"); shipped test is a runtime invocation form. The shipped form is stronger (compile-checked + runtime). Document for ClasspathRef test (Phase 02c) so the same runtime style is reused intentionally rather than by accident.
- NOTE-2: `isClosed` is included in the test 1 invocation set even though it was migrated pre-Phase 02b (commit c737c9ee6, the kyo-reflect→kyo-tasty rename). Inclusion is correct (the test pins the full Classpath surface, not just Phase 02b deltas); just record that it predates this phase.

## Routing
- BLOCKER findings: none. SLOT-A for Phase 02d unblocked.
- WARN findings: none.
- NOTE-1, NOTE-2: surface to Phase 02c prep so the ClasspathRef accessor test inherits the runtime-invocation pattern and the "10-vs-9 migrated" framing carries forward.

## isClosed skip verification
At HEAD (`Classpath.scala:27`): `private[kyo] def isClosed(using AllowUnsafe): Boolean = stateRef.unsafe.get() == Classpath.State.Closed`. No inner `import danger`. Migrated in commit c737c9ee6 ("kyo: rename kyo-reflect module to kyo-tasty"), pre-dating Phase 02b. Skip is justified per D-1.

## Bench cascade quality
- `TastyBench.scala:136`: `Scope.ensure(Sync.defer { import AllowUnsafe.embrace.danger; InternalClasspath.close(rawCp) })` — one-line embrace at Sync.defer boundary. Was a bare `Sync.defer(InternalClasspath.close(rawCp))` pre-phase. Cascade is import + Sync.defer wrap only.
- `TastyBench.scala:194-196` (countTreeRefs): added `// Unsafe: ...` comment + `import AllowUnsafe.embrace.danger` + `import Tasty.Name.asString` at method head; added `end match` closer. The `end match` addition is mechanical and consistent with the file's style (other methods use `end <name>`). No logic change.
- Em-dashes confirmed absent at HEAD (zero hits for `—|–`).

## Remediation history check
Initial verify (phase-02b-verify.md) flagged 3 NEW issues: 2 em-dashes in TastyBench (cascade comments), 1 juc-tree + null in ClasspathPureAccessorTest. All 3 fixed in the same Phase 02b commit:
- TastyBench em-dashes replaced with `;` separators inside one-line embrace (line 136) and with comma-separated phrasing (lines 194-196). Zero em-dashes at HEAD.
- juc-tree + null replaced with `Scope.run` pure-data-flow form: test 4 binds `rawCp` as the `Result.Success(rawCp)` payload after `Scope.run` exits. Zero `AtomicReference` / `null` at HEAD in the test file.
Remediations did not regress: cross-platform compile + 4/4 test pass per verify report.

## Overall
**PASS — ready for next phase.**

Phase 02b is a clean, faithful execution of the design's INV-001 second surface. Architecture matches (zero `Sync.Unsafe.defer` wraps, raw returns). Cascade is contained (6 files, import + signature propagation only; one mechanical `end match` closer). Tests genuinely pin INV-001 via runtime invocation that would fail to compile if any signature were wrong. Remediation history clean (3 verify issues fixed in-commit, no regressions). NOTE-1 / NOTE-2 routed to Phase 02c prep as carry-forward context, not blockers.
