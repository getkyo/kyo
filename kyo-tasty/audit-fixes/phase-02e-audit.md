# Phase 02e audit

Time: 2026-05-29T22:30:00Z
HEAD: 616de01c9
Phase commit: 616de01c9
Plan cites: ./05-plan.md §Phase 02e (yaml: 05-plan.yaml id="02e")
Design cites: ./02-design.md §"Symbol.TastyOrigin.addrMap visibility (A4)" (lines 81-89), INV-011 (line 603)

## Test count
| Leaf | Status | Notes |
|---|---|---|
| 1: external code cannot reference addrMap | PRESENT_STRICT | AddrMapVisibilityTest.scala:10-17 uses `assertDoesNotCompile` on `origin.addrMap` from `package external`; passes for the right reason (see §Negative-compilation test integrity) |
| 2: addrMap reachable from kyo.internal | PRESENT_VIA_REUSE | AstUnpicklerTest.scala:611 already calls `o.addrMap` from package `kyo`; the JVM `Test/compile` + 8/8 PASS recorded in the commit message confirms reachability. Reuse justified in phase-02e-decisions.md §D4 |

Plan yaml `total: 2` honored via one new strict leaf + one reuse of an existing positive-reachability assertion. Task input explicitly authorized single-test scope.

## CONTRIBUTING.md violations
- None on Phase 02e additions. `import AllowUnsafe.embrace.danger` inside `addrMap` body is gated by `private[kyo]` and labeled with `// Unsafe:` comment per §839 case 3.

## Unsafe markers
- Tasty.scala:860 (`addrMap` body): `// Unsafe: SingleAssign.get() is unsafe-tier; private[kyo] limits callers to kyo.internal.tasty.* §839 case 3 contexts.` Present and accurate.
- Tasty.scala:552 (OnceCell init lambda): comment refreshed to "OnceCell init runs via TreeUnpickler.decodeSync, which reads unsafe-tier helpers." Stale reference to `TastyOrigin.addrMap SingleAssign read` removed.

## Cross-platform consistency
- platforms checked: jvm, js, native
- Per-platform deltas: none. Only `shared/` paths touched. Commit message records JVM 8/8 PASS (incl. new test in 18 ms), JS Test/compile PASS, Native Test/compile PASS.

## Naming convention compliance
- New test type `class AddrMapVisibilityTest extends kyo.Test` in `package external` follows the in-tree convention for negative-compilation tests; no naming deviation.

## Steering deviation
- `git show 616de01c9 --stat`: 7 files, 254 insertions / 2 deletions. Source/test scope: Tasty.scala (5 +/2 -) + AddrMapVisibilityTest.scala (new). Remaining 5 paths are audit-fix workflow artifacts (phase-02d-audit.md, phase-02e-baseline.txt, phase-02e-decisions.md, phase-02e-prep.md, phase-02e-verify.md), which is the expected SLOT-B carryover pattern. No drift.

## Anti-flakiness measures
- `assertDoesNotCompile` is compile-time deterministic; no timing/IO/JIT dependencies. The macro inspects the literal string under the surrounding package scope.

## Architecture substitution check
- Design intent (02-design.md:85): `private[kyo] def addrMap: IntMap[Tasty.Symbol]` (no `(using AllowUnsafe)`). The design explicitly removed the public AllowUnsafe slot.
- HEAD reality (Tasty.scala:859-864): `private[kyo] def addrMap: IntMap[Tasty.Symbol]` with `import AllowUnsafe.embrace.danger` self-supplied inside the body, gated by `private[kyo]`.
- Verdict: MATCH. The §839 case 3 self-supply applied here is consistent with the design's removal of the public AllowUnsafe slot, since the alternative (push the proof requirement up to callers) would require `(using AllowUnsafe)` to remain in some form. Design left the proof-handling unspecified beyond "no public AllowUnsafe slot"; impl chose self-supply, which is one of two design-permitted patterns.

## Pattern consistency with other classpath-init helpers (focus item #1)
- `Classpath.transitionToReady` (Classpath.scala:188-201): `private[kyo]` + `(using AllowUnsafe)` (propagate-the-proof, §828).
- `Classpath.close` (Classpath.scala:204-206): `private[kyo]` + `(using AllowUnsafe)` (propagate-the-proof, §828).
- `addrMap` (Tasty.scala:859-864): `private[kyo]` + internal `import danger` (self-supply, §839 case 3).
- Inconsistency analysis: `transitionToReady` and `close` perform mutating state transitions (`stateRef.unsafe.set`); `addrMap` is a pure read of an initialized `SingleAssign`. The design at 02-design.md:153 explicitly chose propagate-the-proof for the analogous `ClasspathRef.assign` write site. So write-sites propagate, read-sites self-supply, which is a defensible boundary but worth surfacing as a NOTE so Phase 02f and downstream phases don't flip the convention silently.

## Visibility correctness (focus item #2)
- All non-comment `\.addrMap` references in `kyo-tasty/shared/src/main/scala/` that resolve to `TastyOrigin.addrMap` (vs local fields / context-record fields): TreeUnpickler.scala:62 (inside `decodeSync`, which already opens `import AllowUnsafe.embrace.danger`). The remaining ~45 references inspected in phase-02e-prep.md are reads of `ctx.addrMap`, `session.addrMap`, `pass1Result.addrMap` (struct fields on `DecodeCtx`, `TreeTypeSession`, `Pass1Result`), unrelated to the `TastyOrigin.addrMap` accessor.
- All accessor callsites are in `kyo.internal.*` (production) or package `kyo` (test). `private[kyo]` admits both. Commit message records compile PASS on all three platforms.

## Negative-compilation test integrity (focus item #3)
- `external/AddrMapVisibilityTest.scala:10-17`:
  - `val origin = kyo.Tasty.Symbol.TastyOrigin.empty` — `TastyOrigin.empty` is a public `def` at Tasty.scala:875, so type resolution succeeds; `origin: TastyOrigin` is well-typed.
  - `origin.addrMap` — the only candidate is the `private[kyo] def addrMap` at Tasty.scala:859. From `package external`, the access is rejected with a visibility error.
- The test cannot pass for the wrong reason: the receiver expression compiles (verified by `TastyOrigin.empty` being public), and `addrMap` is the only member of that name on `TastyOrigin`. A failure to compile here is necessarily a visibility failure.
- Verdict: passes for the right reason. INV-011 structurally enforced.

## Documentation drift (focus item #4)
- Commit message names INV-011, cites the §839 case 3 rationale, and records the 47-callsite reachability check.
- phase-02e-decisions.md §D1–D4 align with the diff: BEFORE/AFTER, the comment refresh, the test-file location override (D3), and the positive-reachability reuse (D4).
- phase-02e-verify.md records exit code 0 and the cross-platform PASS matrix.
- Scaladoc additions: none beyond the inline `// Unsafe:` comment and the refreshed OnceCell-init comment. No drift beyond plan intent.

## API surface integrity (focus item #5)
- Public `(using AllowUnsafe)` slots on Symbol/Origin surface (Tasty.scala): `Name.asString` (69), `Symbol.fullName` (565), `isPackageObject` (570), `scaladoc` (581), `position` (592), `declaredType` (611), `parents` (621), `typeParams` (627), `declarations` (633), `companion` (643). All are pre-existing public-tier accessors and out of Phase 02e scope (covered by other invariants / phases).
- `Origin` ADT: `JavaOrigin` (case object) has no methods that take `AllowUnsafe`. `TastyOrigin` after 02e: no public `AllowUnsafe`-typed members remain (`addrMap` now `private[kyo]`; `bodyStart`/`bodyEnd`/`bodyView`/`sectionBytes`/`names`/`sectionOffset` are plain fields). No new leak; INV-011 boundary is clean.

## Cascade containment (focus item #6)
- `git show 616de01c9 --stat`: 7 files, 254 +/2 -. Source-tree diff is 2 files (Tasty.scala +5/-2, AddrMapVisibilityTest.scala new 19 lines). Remaining 5 files are workflow artifacts (phase-02d-audit.md SLOT-B carryover + four phase-02e-* artifacts).
- No incidental edits to TreeUnpickler, TypeUnpickler, ClasspathOrchestrator, or AstUnpickler. Cascade contained as planned.

## Findings (categorized)
- BLOCKER: none.
- WARN: none.
- NOTE 1: Pattern split between write-sites (propagate-the-proof, §828) and read-sites (self-supply, §839 case 3) is now load-bearing across `Classpath.transitionToReady` / `Classpath.close` vs `TastyOrigin.addrMap`. Phase 02f should preserve this split for `Classpath.open` delegation and not silently flip either side; document the read-vs-write distinction in the design once 02f lands.
- NOTE 2: Plan yaml `tests.files` lists `TastyTest.scala`; impl placed the negative test in a new `external/AddrMapVisibilityTest.scala`. This was correct (a `package external` file is required for `assertDoesNotCompile` to verify `private[kyo]`), but the plan yaml `files_produced: []` did not anticipate the new file. End-of-project cleanup should reconcile the yaml with the as-built test layout.

## Routing
- BLOCKER findings: none; no halt on SLOT-A launch of phase 02f.
- WARN findings: none.
- NOTE 1 → TaskCreate for Phase 02f prep input: confirm Classpath.open delegation keeps `(using AllowUnsafe)` on the underlying state-write helpers (read-vs-write convention preserved).
- NOTE 2 → TaskCreate for end-of-project cleanup: reconcile 05-plan.yaml `files_produced` for 02e with the as-built `external/AddrMapVisibilityTest.scala`.

## Exit code: 0

Overall: ready for Phase 02f. INV-011 produced and structurally enforced; AllowUnsafe no longer appears on any TastyOrigin-public member; the negative-compilation test is sound; cascade is contained.
