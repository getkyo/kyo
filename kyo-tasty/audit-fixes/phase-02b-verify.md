# Phase 02b verify report

Run-id: phase-02b-verify-1
HEAD: d9983f6e3 (Phase 02a tip, impl reports unchanged)
Date: 2026-05-29

Status: FAIL (class-A; LLM-tells em-dash + fp-discipline juc-atomic)

## Class-A gates (mechanical, commit-blocking)

- reward-hacking grep: 17 hits, 0 overridden, 17 PRE-EXISTING (all in
  audit/prep markdown under `kyo-tasty/audit-fixes/` or pre-existing
  source-comment scaladoc strings like "placeholder", "phase C"; none in
  newly-modified lines).
- fp-discipline grep: 217 hits, 0 overridden. Breakdown of NEW hits
  attributable to Phase 02b dirty tree:
  - juc-tree: `ClasspathPureAccessorTest.scala:150` -- NEW. The test
    uses `new java.util.concurrent.atomic.AtomicReference[InternalClasspath](null)`
    to carry a Classpath reference out of the Scope.ensure block. Violates
    Rule 1 (juc-tree) and embeds a `null` (Rule 4). Per
    [feedback_atomic_not_var]: use `AtomicRef` (kyo) for concurrent
    mutable state.
  - null-literal: 14 hits in dirty source files but ALL PRE-EXISTING
    (Tasty.scala, ClasspathOrchestrator.scala, SnapshotReader.scala
    null-tagging in the TASTy reader; unchanged at the line level by
    02b).
  - unsafe-site: many hits, all expected (the migrated accessors
    legitimately read `stateRef.unsafe.get()` inside their bodies; this
    is exactly the §839 case 1 unsafe-tier surface INV-001 governs).
  - private-default-param, private-over-annotation, extension-owned-type,
    bare-var: all PRE-EXISTING (located outside the Phase 02b diff
    hunks; carry-over from Phase 01/02a verify).
  Net new class-A violation: 1 (juc-tree in
  ClasspathPureAccessorTest.scala).
- llm-tells grep: 40 hits, 0 overridden. Breakdown:
  - em-dash: 38 hits in audit/prep markdown under `kyo-tasty/audit-fixes/`
    PRE-EXISTING (process docs).
  - em-dash NEW: 2 hits in `kyo-tasty-bench/jvm/src/main/scala/kyo/bench/TastyBench.scala`
    lines 136 and 195, in NEW comments introduced by the Phase 02b
    cascade fix ("--" replacement needed). Per [feedback_no_em_dashes]:
    hard zero, never use em-dashes. These are NEW DRIFT-FROM-IMPL.
  - hedge-harness: 4 hits, all PRE-EXISTING (existing scaladoc / new
    comments containing the word "harness" or the same em-dash lines
    double-counted; the word "harness" itself is a legitimate domain
    term for the bench harness, not a hedge).
  Net new class-A violation: 2 em-dashes in TastyBench.scala.
- dev-tag grep: 4 hits, 0 overridden, all PRE-EXISTING ("Phase 5/6/7"
  in ClasspathOrchestrator.scala refer to TASTy decoder phases, not
  flow phases; `Pure accessors (v3 Phase 3)` header in Classpath.scala
  is pre-existing).
- plan-diff: AUTHORIZED=2 SUPPORTING-CASCADE=6 PRE-EXISTING=4 DRIFT-FROM-IMPL=0
  (the script's mechanical bucket count differs because the baseline
  file only contains itself; applying the FLOW response protocol
  manually per the supervisor's classification gives the buckets
  above).
  - AUTHORIZED: Classpath.scala, ClasspathPureAccessorTest.scala (new).
  - SUPPORTING-CASCADE: Tasty.scala, ClasspathOrchestrator.scala,
    SnapshotReader.scala, CodegenExample.scala, IdeHoverExample.scala,
    TastyBench.scala. Each gained ONLY `(using AllowUnsafe)` proof,
    file-local `import AllowUnsafe.embrace.danger`, or Sync.defer
    wrapping (per supervisor's acceptance criteria for supporting
    cascade).
  - PRE-EXISTING: phase-02a-audit.md (audit doc from prior phase),
    phase-02b-baseline.txt, phase-02b-decisions.md, phase-02b-prep.md
    (verify-infra artifacts dirty since 02b prep ran).
  - DRIFT-FROM-IMPL: 0 file-level. Two SUB-FILE drifts to flag:
    1. TastyBench.scala: cascade lines 136 and 195 use em-dashes in
       NEW comments. Not acceptable under [feedback_no_em_dashes].
    2. ClasspathPureAccessorTest.scala: test 4 uses
       `juc.AtomicReference` (Rule 1) with `null` (Rule 4). Not
       acceptable under [feedback_atomic_not_var].
- test-count: expected=4 actual=4. PASS.
- stowaway-commit: NONE. HEAD still at d9983f6e3 (unchanged from
  Phase 02a tip; impl never committed).
- cross-platform compile (phase plan declares jvm,js,native):
  - JVM: PASS (Test/compile + ClasspathPureAccessorTest 4/4 succeed in
    `sbt 'kyo-tasty/Test/compile' 'kyo-tasty/testOnly kyo.ClasspathPureAccessorTest'`).
  - JS: PASS (Test/compile via `sbt 'kyo-tastyJS/Test/compile'`).
  - Native: PASS (Test/compile via `sbt 'kyo-tastyNative/Test/compile'`,
    one pre-existing E029 pattern-exhaustivity warn in QueryApiTest:924
    unrelated to 02b).
  - Bench compile: PASS (`sbt 'kyo-tasty-bench/Test/compile'`).
- invariants: INV-001 SECOND SURFACE (Classpath) PRODUCED. Spot
  check: all 10 accessors (`pureClass`, `purePackage`, `pureModule`,
  `pureTopLevelClasses`, `purePackages`, `accumulatedErrors`,
  `allSymbols`, `isClosed`, `transitionToReady`, `close`) now carry
  `(using AllowUnsafe)` in Classpath.scala, and the 7 Tasty.scala
  extension methods that wrap them propagate the proof. The plan-wide
  INV-027 ORDER-VIOLATION reported by `flow-verify-invariants.sh` is
  PRE-EXISTING (producer at phase 31, first consumer at phase 027 by
  yaml ordering); unrelated to 02b.

## Class-B findings (opus judgment)

None surfaced beyond the class-A items above. The 4 Classpath tests
cover the migrated surface end-to-end (compile-time invocation proof
for test 1, runtime correctness for tests 2 and 3, finalizer behavior
for test 4). Test 4 functionally works but its choice of
`juc.AtomicReference` to escape the Scope is a class-A break, not a
class-B judgment call.

## Overrides

None. No `// flow-allow:` annotations in the dirty tree.

## Plan-diff bucket summary

| Bucket | Count | Files |
|---|---|---|
| AUTHORIZED | 2 | Classpath.scala, ClasspathPureAccessorTest.scala |
| SUPPORTING-CASCADE | 6 | Tasty.scala, ClasspathOrchestrator.scala, SnapshotReader.scala, CodegenExample.scala, IdeHoverExample.scala, TastyBench.scala |
| PRE-EXISTING | 4 | phase-02a-audit.md, phase-02b-baseline.txt, phase-02b-decisions.md, phase-02b-prep.md |
| DRIFT-FROM-IMPL | 0 file-level | (two sub-file violations: em-dash in TastyBench, juc.AtomicReference in ClasspathPureAccessorTest) |

## Remediation list (before commit)

1. TastyBench.scala line 136: replace the em-dash (U+2014) in
   `// Unsafe: §839 case 2 bench-harness boundary -- close is unsafe-tier, embraced at Sync.defer site`
   with `--` or a comma per [feedback_no_em_dashes]. Verbatim text:
   `// Unsafe: §839 case 2 bench-harness boundary; close is unsafe-tier, embraced at Sync.defer site`.
2. TastyBench.scala line 195: same fix on
   `// Unsafe: §839 case 2 bench-harness boundary -- Name.asString is unsafe-tier, embraced inside bench-only helper`
   to `// Unsafe: §839 case 2 bench-harness boundary; Name.asString is unsafe-tier, embraced inside bench-only helper`.
3. ClasspathPureAccessorTest.scala line 150: rewrite test 4 to
   propagate the Classpath reference through the Kyo computation
   instead of through a `juc.AtomicReference[_]`. Two valid shapes
   per [feedback_atomic_not_var]: (a) call `rawCp.isClosed` after
   the `Scope.run` has exited by capturing it in a Kyo `Map` /
   `Promise` and reading inside a follow-up `Scope.run`, or (b)
   structure the test so the `isClosed` assertion runs in a
   continuation passed to `Scope.ensure` itself rather than
   smuggling state out via a juc primitive. Approach (a) is cleaner
   and parallels how kyo-core tests verify finalizer effects.
   Removing the `(null)` initialiser also clears the Rule 4
   null-literal hit.

## Decision logic

- Class-A FAIL: 3 un-overridden hits (2 em-dashes + 1 juc-tree). Per
  the skill contract, this is exit 1 (override-needed or fix-source).
  The supervisor returns control to the impl agent or fixes the source
  before re-running verify.
- No stowaway commit; cross-platform GREEN; INV-001 satisfied.
- Substantive plan match: AUTHORIZED list complete, SUPPORTING-CASCADE
  acceptable per supervisor criteria, no file-level off-plan drift.

## Exit code: 1
