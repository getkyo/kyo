# Phase 03b verify report

Run-id: phase-03b-verify-1
HEAD: bfde82de6 (unchanged; impl agent did not commit)
Baseline: `kyo-tasty/audit-fixes/phase-03b-baseline.txt`
Scope: B10 / INV-010, Interner.bytesEqual bounds guard plus 4 new InternerTest scenarios.
Authorized: Interner.scala, InternerTest.scala. No SUPPORTING-CASCADE.

Status: FAIL (class-A: cross-platform JS 2/4 new-test failures; class-B: INV-010 structured-error contract weakened, test-count over-delivery, test-bypasses-API on Test1/Test2)

## Class-A gates (mechanical, commit-blocking)

- reward-hacking grep (in-scope files): 0 hits, 0 overridden. PASS.
- fp-discipline grep (in-scope files): 23 hits, 0 NEW (all pre-existing in Interner.scala; baseline-on-HEAD count identical at 23). PASS.
- llm-tells grep (in-scope files): 0 hits. PASS.
- dev-tag grep (in-scope files): 0 hits (the test comment `// Phase 03b` does not match `\bPhase\s+\d+\b` because `3b` has no word-boundary after `\d+`; catalog regex limitation, not a hit). PASS.
- plan-diff (with baseline): expected files per yaml = `Interner.scala` (files_modified[0].path) and `InternerTest.scala` (tests.files[0]). Actual dirty source files = both. Manual classification:
  - AUTHORIZED: `Interner.scala`, `InternerTest.scala`
  - PRE-EXISTING/flow-artifact (untracked): `phase-03a-audit.md`, `phase-03b-baseline.txt`, `phase-03b-decisions.md`
  - DRIFT-FROM-IMPL: 0
  Note: `flow-verify-plan-diff.sh` yq filter does not extract `.path` from object-typed `files_modified`; manual reconciliation against the plan YAML confirms PASS. PASS (manual).
- test-count: expected=1 (plan declares one leaf "bytesEqual rejects out-of-range"), actual=4 new tests (4 B10 scenarios). OVER-DELIVERY +3. Each new test addresses a distinct guard condition (offset+length overflow, negative offset, negative length hash-collision path, valid zero-length positive). Class-B over-delivery against plan, not a class-A fail. WARN.
- stowaway-commit: NONE. HEAD remains `bfde82de6`. PASS.
- cross-platform (plan declares `platforms: [jvm, js, native]`):
  - JVM: 15/15 (4/4 new B10 tests pass). PASS.
  - JS: 13/15 (2/4 new B10 tests FAIL). Tests "B10/INV-010: intern throws AIOOBE when offset + length > bytes.length" (line 226) and "B10: intern throws AIOOBE for negative offset" (line 236) intercept `ArrayIndexOutOfBoundsException` but Scala.js throws `org.scalajs.linker.runtime.UndefinedBehaviorError` instead. JS does NOT promote raw OOB array access to AIOOBE; only the explicit `throw new ArrayIndexOutOfBoundsException(...)` in `bytesEqual` is caught (Test 3 passes). Tests 1 and 2 reach computeHash's raw `bytes(offset+i)` access before the guard fires. FAIL.
  - Native: 15/15 (4/4 new B10 tests pass). PASS.
  JS LINK: PASS (kyo-tastyJS/Test/compile succeeds; the 03a-debt cross-platform port is intact).
- Rule 8 organization: 41 pre-existing violations across kyo-tasty; none introduced by Phase 03b. `Interner.scala` lives under `kyo/internal/tasty/symbol/` (not scanned by the script's `kyo/*.scala` top-level check). No new violations. PASS for this phase delta.

## Class-B findings (opus judgment)

1. **(rule 13: Test bypasses the API under test, AND cross-platform contract gap)** `InternerTest.scala:226` and `:236` — Tests 1 (offset+length > bytes.length) and 2 (negative offset) intend to exercise the `bytesEqual` guard but the JVM call path reaches `computeHash`'s raw `bytes(offset+i)` access first, which throws JVM-native AIOOBE; the explicit `bytesEqual` guard is defence-in-depth and unreachable on the no-collision path. On Scala.js, raw OOB throws `UndefinedBehaviorError`, not AIOOBE, so the tests fail. Mitigation options: (a) move the guard from `bytesEqual` to `intern()` entry so it always fires before any array access, (b) catch the wider `Throwable` and assert message content, (c) use the hash-collision pre-seed pattern (Test 3's approach) for all four cases. Option (a) most aligns with INV-010 intent and is cross-platform-safe.

2. **(INV-010 interpretation drift)** INV-010 specifies "reject out-of-bounds reads with a structured `TastyError.MalformedSection` rather than an uncaught exception." Phase 03b throws `ArrayIndexOutOfBoundsException` (a JVM uncaught exception by INV-010's literal reading). However, Phase 03a established the same precedent (Varint throws `MalformedVarintException`, ByteView throws raw AIOOBE), and Phase 03a-verify accepted this. INV-010 honored in spirit (bounds rejected, not silently corrupted) but NOT strictly with `TastyError.MalformedSection`. Recommend either tightening INV-010's wording in 04-invariants.md to acknowledge precedent OR routing all five guard sites through a unifying `MalformedSection` wrapping pass in a later phase.

3. **(rule 7 / scope: Test-count over-delivery)** Plan declares 1 leaf for Phase 03b; impl delivered 4. Each new test addresses a distinct B10/INV-010 condition (overflow, negative offset, negative length via hash collision, positive zero-length). Decisions log justifies coverage. Class-B because over-delivery on the same INV is preferable to under-coverage, but plan-as-contract is structurally violated. Recommend amending plan retroactively or accepting precedent.

## Overrides

(no `// flow-allow:` markers in the diff)

## INV-010 verdict

PARTIAL. The guard rejects out-of-bounds reads (intent honored), but throws `ArrayIndexOutOfBoundsException` rather than the literal `TastyError.MalformedSection` named in the invariant text. Consistent with the Phase 03a precedent already accepted by Phase 03a-verify. Documentation drift, not implementation drift; recommend INV-010 text amendment.

## Cross-platform ledger

| Platform | Compile | InternerTest result | Verdict |
|---|---|---|---|
| JVM | PASS | 15/15 (4/4 new) | PASS |
| JS | PASS | 13/15 (2/4 new FAIL) | FAIL |
| Native | PASS | 15/15 (4/4 new) | PASS |

JS regressions are NEW (introduced by Phase 03b's tests). JS link still PASSES post-03a-debt (the inherited red from prior phases is resolved). The 2 failures here are Phase-03b-introduced and must not be dismissed as inherited.

## Ready for

REMEDIATION, not commit. Two paths:

- (preferred) Move the `bytesEqual` guard to the `intern()` entry point so every call path hits the explicit AIOOBE throw before any raw array access. Tests 1 and 2 will then PASS on JS because Scala.js will see the explicit `throw new ArrayIndexOutOfBoundsException` rather than raw OOB. Also strengthens defence-in-depth uniformly. Re-run JS InternerTest to confirm 4/4 green; commit Phase 03b once cross-platform parity is restored.
- (alternative) Restructure Tests 1 and 2 to use the hash-collision pre-seed pattern (Test 3's approach) so both reach `bytesEqual` before `computeHash` throws. Less robust, leaves the actual guard unreachable on no-collision paths.

Recommend path 1.

## Exit code: 1
