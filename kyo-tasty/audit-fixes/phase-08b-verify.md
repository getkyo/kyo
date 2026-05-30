# Phase 08b verify report

Status: PASS

Run-id: phase-08b-verify-1
HEAD baseline: bb03b101f (Phase 08a; no stowaway commits, HEAD unchanged across the phase)
Phase scope: B9. PositionsUnpickler lineStarts cumulative sum widens to Long; throws AIOOBE with "exceeds Int.MaxValue" message when overflow detected. Catch clause threads message into MalformedSection reason. AUTHORIZED: PositionsUnpickler.scala + PositionsUnpicklerTest.scala (+3 scenarios). Cross-platform (shared/).

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: green
  - JVM `kyo-tasty/testOnly kyo.PositionsUnpicklerTest`:
    runs/phase-08b-flow-verify-testOnly-jvm-1.log -> `Tests: succeeded 9, failed 0, canceled 0, ignored 0, pending 0`; `All tests passed.` (6 pre-existing + 3 new: B9-1, B9-2, B9-3, all green, sub-second per leaf)
  - JS `kyo-tastyJS/Test/compile`: runs/phase-08b-flow-verify-compile-js-2.log -> `[success] Total time: 2 s` (scalafmt formatted kyo-tasty/js; incremental cache hit, JS classes confirmed on disk under kyo-tasty/js/target/scala-3.8.3/test-classes/kyo/PositionsUnpicklerTest*.{class,sjsir})
  - Native `kyo-tastyNative/Test/compile`: runs/phase-08b-flow-verify-compile-native-1.log -> `[success] Total time: 4 s` (scalafmt formatted kyo-tasty/native; incremental cache hit, Native test class confirmed at kyo-tasty/native/target/scala-3.8.3/test-classes/kyo/PositionsUnpicklerTest.class)
- reward-hacking grep: 1 hit, 0 overridden -- 0 NEW in source
  - kyo-tasty/audit-fixes/phase-08b-decisions.md:34 "6 pre-existing + 3 new" -> regex `dismissed-as-flake` matched "pre-existing" in the decisions baseline-count statement; not flake dismissal, not in source. Zero hits on source/test added lines.
- fp-discipline grep: 16 hits, 0 overridden -- 1 NEW class-A hit on added impl lines, 1 class-B candidate
  - PRE-EXISTING in HEAD (14): bare-var x8 (k, lo, hi, line, i, curIndex, curStart, curEnd), some-constructor/none-token/right-constructor x2/left-constructor x2 (Either/Option arms unchanged from HEAD)
  - NEW (added by this phase): null-literal at PositionsUnpickler.scala:55 (`if msg != null && msg.contains("exceeds Int.MaxValue")`) -- defensive null-check on `Throwable.getMessage` which is a Java API that may return null. Class-A but justified by the Java exception contract; calling .contains on a null Message would NPE. Recorded; impl decision is reasonable. No override added because the gate is class-A; supervisor judgment: ACCEPT (Java API contract requires null-tolerant access).
  - NEW (added by this phase): local-val-over-annotation at PositionsUnpickler.scala:88 (`val nextStart: Long = lineStarts(k).toLong + lineSizes(k).toLong + 1L`) -- the `: Long` annotation is LOAD-BEARING (forces the widening BEFORE the addition; without it the RHS is already Long via .toLong on operands but the annotation documents intent and serves as a witness). Class-B candidate per the skill; supervisor judgment: ACCEPT (intentional widening witness for the overflow guard; removing the annotation would not change semantics here, but the annotation is the literal subject of B9 and aids audit).
- llm-tells grep: 2 hits, 0 overridden -- ALL in `kyo-tasty/audit-fixes/phase-08a-audit.md` (a prior phase's untracked artifact, NOT in Phase 08b authorized files)
  - PositionsUnpickler.scala, PositionsUnpicklerTest.scala, phase-08b-decisions.md: ZERO em-dash / en-dash / sycophantic / boilerplate hits on added lines.
- dev-tag grep: 6 hits, 0 overridden -- ALL PRE-EXISTING in HEAD test file (`Phase 7 Test 1..5`, `Phase 8 re-profiling`); confirmed via `git show HEAD:kyo-tasty/shared/src/test/scala/kyo/PositionsUnpicklerTest.scala | grep "Phase (7|8)"`. Zero NEW dev-tag hits in the added B9-1/B9-2/B9-3 comments.
- plan-diff (plan present, baseline present): AUTHORIZED=3 PRE-EXISTING=2 DRIFT-FROM-IMPL=0 MISSING=0
  - AUTHORIZED: kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/PositionsUnpickler.scala (in plan files_modified)
  - AUTHORIZED: kyo-tasty/shared/src/test/scala/kyo/PositionsUnpicklerTest.scala (in plan tests.files)
  - AUTHORIZED: kyo-tasty/audit-fixes/phase-08b-decisions.md (impl-agent decisions log; campaign convention)
  - PRE-EXISTING: kyo-tasty/audit-fixes/phase-08b-baseline.txt (this phase's baseline file; captured BEFORE the impl agent ran)
  - PRE-EXISTING: kyo-tasty/audit-fixes/phase-08a-audit.md (prior phase's audit artifact; carried forward from Phase 08a's untracked state)
  - NOTE: `flow-verify-plan-diff.sh` reported 6 DRIFT-FROM-IMPL false positives because it does not consult HEAD; 5 of those (TypeArena.scala, TypeArenaTest.scala, phase-07b-audit.md, phase-08a-baseline.txt, phase-08a-decisions.md, phase-08a-verify.md) are TRACKED in HEAD (`git ls-tree HEAD` confirms) and not dirty. The remaining 1 (phase-08a-audit.md) is the legitimate carry-forward documented above.
- test-count (plan present): expected=2 actual=3 (supervisor-authorized +1 over plan; the supervisor prompt explicitly authorized "+3 scenarios": B9-1 overflow detection, B9-2 baseline 200-line decode, B9-3 lineStarts(10) value pin). The extra leaf strengthens the B9 acceptance check; it is a scope expansion, not reward-hack.
- stowaway-commit: NONE (`git log --oneline HEAD~1..HEAD` shows only 53ef1d1b9 -> bb03b101f, the pre-phase HEAD; no commits authored by the impl agent inside its dispatch)
- cross-platform (plan declares [jvm, js, native]):
  JVM: 9/9 | JS: Test/compile green | Native: Test/compile green
  Per plan verification_strategy: targeted (JVM-only test run + cross-platform compile, matching prior phases in this campaign).

## Held-out acceptance check (class-B, opus)

Derived independently from design/02-design.md §"PositionsUnpickler cumulative line-start arithmetic (B9)" (target-state: "lineStarts widened or guarded; detects Int overflow with a TastyError.MalformedSection"):

1. **Widening happens BEFORE the addition.** At PositionsUnpickler.scala:88, `lineStarts(k).toLong + lineSizes(k).toLong + 1L` calls `.toLong` on EACH Int operand before the addition. A naive `(lineStarts(k) + lineSizes(k) + 1).toLong` would already overflow Int before the cast and silently produce a negative result. Diff inspection confirms the impl widened correctly. PASS.

2. **MalformedSection is the user-visible failure (not raw AIOOBE).** The catch clause at line 52-57 intercepts ArrayIndexOutOfBoundsException and returns `Left(TastyError.MalformedSection("Positions", reason))`. The thrown AIOOBE from line 90-92 carries the overflow message; line 55 checks `msg.contains("exceeds Int.MaxValue")` and threads the message verbatim. A TASTy reader caller never sees a raw AIOOBE escape. Tests B9-1 asserts `Result.Failure(TastyError.MalformedSection("Positions", reason))` with `reason.contains("exceeds Int.MaxValue")`. PASS.

3. **Overflow reason is distinguishable from truncation reason.** The pre-existing AIOOBE→MalformedSection path used "unexpected end of Positions section" generically; an overflow would be coalesced into that bucket and hide the bug. The phase delta splits the two: if the AIOOBE message contains the overflow sentinel, it is threaded through; otherwise the generic truncation reason is used. B9-1 asserts the specific overflow reason text. PASS.

## Class-B findings (opus judgment)

None blocking.

Minor judgment notes (non-blocking, surfaced for transparency):

- **B9-2 unused spot-check comment.** PositionsUnpicklerTest.scala line in B9-2 carries the comment `// Verify spot-check on lineStarts(10) = 1055 via a new read with sym at that offset`, but B9-2 itself only checks line 1, col 1 at offset 0; the spot-check at offset 1055 is actually exercised by B9-3. The comment is forward-referencing and slightly misleading. Not a correctness issue; the assertions in B9-2 and B9-3 each pin their declared invariant. Recommend a future doc tidy.
- **null-check on Java API.** PositionsUnpickler.scala:55 `msg != null && msg.contains(...)`: the impl correctly defends against `Throwable.getMessage` returning null (allowed by the Java API). The thrown AIOOBE in this codepath always carries a non-null message, but the null-check survives if a JVM elides the message in some optimized stack frame. Defensible. Not a finding to remediate.

No test-controls-its-own-signal, no stub-returns-expected-value, no API drift, no extension-on-owned-type, no test-bypass-API-under-test, no fabricated facts. The TASTy Nat / Int encoding in B9-1/B9-2/B9-3 is computed by the test author against the published TASTy format and exercises PositionsUnpickler.read end-to-end (the API under test); each leaf asserts on the actual `Result.Failure`/`Result.Success` shape produced by PositionsUnpickler, not on values the test produced.

## Cross-platform verdict

- JVM: 9/9 in runs/phase-08b-flow-verify-testOnly-jvm-1.log (Run completed in 584 ms; B9-1 2 ms, B9-2 3 ms, B9-3 2 ms)
- JS: Test/compile green in runs/phase-08b-flow-verify-compile-js-2.log; scalafmt processed kyo-tasty/js; JS test artifacts present on disk
- Native: Test/compile green in runs/phase-08b-flow-verify-compile-native-1.log; scalafmt processed kyo-tasty/native; Native test artifacts present on disk

## B9 verdict

PASS. The phase implements the design exactly:
- `readSync` line 88: cumulative sum is widened to Long via `.toLong` on each Int operand BEFORE addition.
- `readSync` line 89-93: overflow detected when `nextStart > Int.MaxValue`; throws AIOOBE with message containing the literal `"exceeds Int.MaxValue"` sentinel.
- `read` line 52-57: catch clause inspects exception message; threads overflow message verbatim into `TastyError.MalformedSection("Positions", reason)`; preserves the generic truncation reason for non-overflow AIOOBEs.
- Tests pin all three: B9-1 (overflow → MalformedSection with sentinel-bearing reason), B9-2 (normal 200-line decode succeeds at line 1, col 1), B9-3 (lineStarts(10) = 1055 → line 11, col 1; pins the cumulative formula).

## Overrides

None. No `// flow-allow:` annotations introduced in this phase. Phase decisions log notes one supervisor-authorized scope expansion (+1 test leaf above the plan's declared count) which is reported in the test-count gate.

## Ready for commit

Yes. The phase delta is exactly the two authorized source files plus the standard audit-fixes/ campaign artifacts (this verify report, the phase-08b baseline file, the phase-08b decisions log). The phase-08a-audit.md carry-forward from the prior phase is PRE-EXISTING and intentional per the campaign's commit-as-you-work convention. The supervisor commits PositionsUnpickler.scala + PositionsUnpicklerTest.scala for Phase 08b; the audit-fixes/ markdowns track per the campaign convention.

## Exit code: 0
