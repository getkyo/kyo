# Phase 02d verify report

Run-id: phase-02d-verify-1
HEAD at verify time: 1892acb54 (Phase 02c)
Dirty tree: 2 modified source files (Tasty.scala, TastyTest.scala), 4 untracked audit-fixes artifacts (phase-02c-audit.md, phase-02d-baseline.txt, phase-02d-decisions.md, phase-02d-prep.md)

Status: PASS (all class-A grep hits classified PRE-EXISTING, AUTHORIZED-ARTIFACT, or AUTHORIZED-CASCADE)

## Class-A gates (mechanical, commit-blocking)

### reward-hacking grep
- 4 hits, 4 classified, 0 NEW DRIFT.
  - `kyo-tasty/audit-fixes/phase-02c-audit.md:34` `deferral-next-phase` "Phase 02d prep should explicitly state ...": Phase 02c artifact, untouched by 02d. PRE-EXISTING.
  - `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:177` `deferral-for-now` "may be a placeholder symbol": line 177 is in scaladoc unrelated to Phase 02d edit region (lines 695-739). PRE-EXISTING.
  - `kyo-tasty/audit-fixes/phase-02d-prep.md:109` `scope-substitution` "simplified" / "edge case" inside explanatory prose describing the plan's BEFORE/AFTER pseudocode. AUTHORIZED-ARTIFACT (FLOW prep doc).
  - `kyo-tasty/audit-fixes/phase-02d-prep.md:136` `scope-substitution` discusses where to place tests; explanatory not reward-hacking. AUTHORIZED-ARTIFACT.

### fp-discipline grep
- 47 hits, 0 NEW DRIFT, 1 AUTHORIZED structural (the desired Sync.Unsafe.defer bridge), 3 SUPPORTING comment lines, 43 PRE-EXISTING.
- AUTHORIZED (the Phase 02d objective itself):
  - `Tasty.scala:698` `unsafe-site` literal `Sync.Unsafe.defer:` — the exact bridge the phase introduces.
- SUPPORTING (Phase 02d comment annotations explaining the bridge):
  - `Tasty.scala:700, 708, 714` comment lines referring to "Sync.Unsafe.defer" by name (per Decision 1 rationale comments).
- PRE-EXISTING (regions untouched by Phase 02d, all outside lines 695-739):
  - bare-var x4 (lines 755, 777, 786, 1022)
  - bespoke-mutable-collection x2 (754, 776)
  - nondeterministic-time x2 (1142, 1169)
  - some-constructor x2 (232, 876)
  - null-literal x7 (194, 225, 649, 654, 756, 778, 849, 872)
  - private-over-annotation x2, local-val-over-annotation x1, extension-owned-type x3
  - unsafe-site (pre-existing imports) x6 (392, 553, 753, 769, 941, 995, 1019)
  - either/right/left-constructor x9 retained from the prior try/catch block (lines 201-209, 716, 717, 720, 725, 731, 733, 734); the try/catch was reindented under Sync.Unsafe.defer but its Either-shape body is structurally identical.
- NEW DRIFT: 0 hits. No new asInstanceOf, juc-tree, null, bare-var, or import-danger introduced. Verified by `git diff | grep '^+' | grep -E '—|–|asInstanceOf|null|juc'` returning empty.

### llm-tells grep
- 2 hits, both em-dash in `kyo-tasty/audit-fixes/phase-02c-audit.md` (lines 24, 28). 02c artifact, untouched by 02d. PRE-EXISTING.
- 0 NEW em-dashes or en-dashes introduced in source or 02d artifacts.

### dev-tag grep
- 0 hits unoverridden, 3 overrides (all pre-existing in TastyTest.scala lines 87, 93, 94, untouched by 02d).

### open-question grep
- 2 hits, both PRE-EXISTING or AUTHORIZED-ARTIFACT:
  - `phase-02c-audit.md:9` `option-a-b` — 02c artifact. PRE-EXISTING.
  - `phase-02d-prep.md:56` `for-now-placeholder` matches `stub("Symbol.body")` inside a code block citing the existing API surface. AUTHORIZED-ARTIFACT.

### plan-diff
- AUTHORIZED:
  - `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` — plan files_modified entry for 02d. Edit confined to Symbol.body TastyOrigin branch (lines 695-739).
- AUTHORIZED-DEVIATION:
  - `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala` — plan declares `tests.files: [TreeUnpicklerTest.scala]`. Decisions log (phase-02d-decisions.md §4) explicitly justifies placing tests in the existing prefix-matching `TastyTest.scala` per steering rule "new test files appear only for new source files" and INV-007 prefix-match. The two new scenarios extend `TastyTest.scala` rather than create a phase-coded artifact file.
- PRE-EXISTING (untracked artifacts):
  - `phase-02c-audit.md`, `phase-02d-baseline.txt`, `phase-02d-decisions.md`, `phase-02d-prep.md` — FLOW workflow artifacts.
- DRIFT-FROM-IMPL: 0.
- Script note: as in Phase 02c, `flow-verify-plan-diff.sh` flagged Tasty.scala and TastyTest.scala as DRIFT because its yq query reads the YAML `files_modified[].path` objects as opaque strings. Manual classification per the plan entry and Decision 4 overrides the script verdict. Plan-diff verdict: PASS.

### test-count
- Expected per task brief: 2 new scenarios + 6 existing in TastyTest.scala = 8.
- Actual: 8 (verified by grep `'^\s*"[^"]*" in {'`).
- JVM testOnly kyo.TastyTest: `Tests: succeeded 8, failed 0`. All 8 PASS including the 2 new Phase 02d scenarios.

### invariants
- INV-002 PRODUCED by Phase 02d. The Symbol.body TastyOrigin branch now wraps the unsafe reads in `Sync.Unsafe.defer:` (Tasty.scala:698) and the freestanding `import AllowUnsafe.embrace.danger` is removed from the method body. Two source-text invariant tests (`Symbol.body TastyOrigin branch has no import AllowUnsafe.embrace.danger`, `Symbol.body TastyOrigin branch uses Sync.Unsafe.defer`) lock the invariant. `flow-verify-invariants.sh` reports `INV-002 OK`.
- INV-001 consumer chain remains satisfied (Symbol.body still calls (using AllowUnsafe)-typed `home.isAssigned`, `home.get()`, `home.get().isClosed`, `_bodyOnce.get()`; coverage comes from the enclosing Sync.Unsafe.defer's implicit AllowUnsafe).
- Pre-existing INV-003 and INV-027 ORDER-VIOLATIONs are plan-shape issues unrelated to 02d (same as 02c report).

### stowaway-commit
- NONE detected. No `git add` or `git commit` in `phase-02d-decisions.md`. Impl agent did not commit inside its dispatch.

### cross-platform
- JVM: `kyo-tasty/Test/compile` PASS; `kyo-tasty/testOnly kyo.TastyTest` 8/8 PASS in 539ms.
- JS:  `kyo-tastyJS/Test/compile` PASS (12s, 1 main + 1 test scalafmt-formatted then compiled clean).
- Native: `kyo-tastyNative/Test/compile` PASS (12s, same shape as JS).
- Verdict: PASS on all three platforms declared by plan entry 02d.

## Class-B findings (opus judgment)

None surfaced this run. Verify mode is mechanical; the opus catch-list was not invoked separately.

## Signature preservation

`Symbol.body` public signature at HEAD diff:
```scala
def body(using Frame): Tree < (Sync & Abort[TastyError])
```
Unchanged from pre-02d. Diff confined to method body. No caller cascades expected or present.

## Overrides

No new `// flow-allow:` annotations introduced by Phase 02d. Pre-existing overrides in `TastyTest.scala` (lines 87, 93, 94 — phase-reference-in-comment for INV-021 production-source test) carry over unchanged.

## Summary

INV-002 PRODUCED by Phase 02d. Symbol.body TastyOrigin branch bridged through `Sync.Unsafe.defer`; the freestanding `import AllowUnsafe.embrace.danger` removed; public signature unchanged. Two source-text invariant tests (8 total in TastyTest.scala) PASS on JVM; JS and Native Test/compile clean. No DRIFT-FROM-IMPL, no NEW class-A hits, no stowaway commit, no class-B findings. Ready for commit.

## Exit code: 0
