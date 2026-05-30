# Phase 04b verify report

Status: PASS

Run-id: phase-04b-verify-1
HEAD: d1cd18e12 (unchanged; Phase 04b is dirty-tree only, will commit on PASS)
Baseline: phase-04b-baseline.txt (only the baseline file itself)
Plan platforms: [jvm] declared, but trait widening cascades into shared/JS/native (correct per feedback_all_platforms_all_tests)
Verification command: `sbt 'project kyo-tasty' 'Test/compile' 'testOnly kyo.MappedByteViewTest'` (plus full test + JS/Native compile)

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: green
  - runs/phase-04b-flow-verify-testOnly-jvm-1.log: 2/2 PASS
  - runs/phase-04b-flow-verify-test-jvm-1.log: 328/328 PASS
  - runs/phase-04b-flow-verify-compile-js-5.log: success (clean Test/compile)
  - runs/phase-04b-flow-verify-compile-native-1.log: success (clean Test/compile)
- reward-hacking grep: 0 hits on added lines, 0 overridden
  - whole-tree grep surfaced 16 hits but all are pre-existing TASTy decoder
    domain terms (`placeholder` for cycle-break sentinels in TypeUnpickler /
    AstUnpickler) inherited from HEAD; diff-scoped grep against `^\+`
    introduced lines: zero hits
- fp-discipline grep: 0 hits on added lines, 0 overridden
  - `null` occurrences in diff are pre-existing `TastyOrigin` 6th-arg
    (`addrMap` null placeholder) reformatted across lines, not new
  - `java.util.concurrent.atomic.AtomicBoolean` import in
    MappedByteViewTest.scala is in `*/jvm/src/test/*`, outside the
    `*/shared/src/*` catalog scope, and required to construct the SUT
    (MappedByteView's `closed` arg is `j.u.c.a.AtomicBoolean`)
- llm-tells grep: 0 hits, 0 overridden (em-dash, en-dash, hedging, boilerplate all clean)
- dev-tag grep: 0 hits, 0 overridden
- plan-diff (three-bucket, with baseline):
  - AUTHORIZED (in plan files_modified for 04b): 2 files
    - kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala
    - kyo-tasty/shared/src/main/scala/kyo/internal/tasty/binary/ByteView.scala
  - AUTHORIZED (in plan tests.files for 04b): 1 file (new)
    - kyo-tasty/jvm/src/test/scala/kyo/MappedByteViewTest.scala
  - AUTHORIZED-CASCADE (caller user invocation: "AUTHORIZED ... 7 reader files
    using positionInt / Math.toIntExact at TastyOrigin construction"; also
    surfaced as cascade gaps in phase-04b-prep.md #2 #3 #4 and resolved per
    D2/D3/D4/D5 in phase-04b-decisions.md): 8 files
    - kyo-tasty/native/src/main/scala/kyo/internal/tasty/binary/MappedByteView.scala (D2: trait widening forces Native override)
    - kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/AstUnpickler.scala (D3)
    - kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TypeUnpickler.scala (D4)
    - kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala (D4)
    - kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/SectionIndex.scala (D5)
    - kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/AttributeUnpickler.scala (D5)
    - kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala (D5)
    - kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala (D5)
  - PRE-EXISTING (in baseline): 0 files
  - DRIFT-FROM-IMPL: 0 files
  - MISSING (plan said modified but not in diff): 0 files
- test-count: expected=2 actual=2 (MappedByteViewTest: 2 tests; matches plan
  `test_count: 2` and `tests.total: 2`)
- stowaway-commit: NONE (HEAD unchanged at d1cd18e12; no commits since impl)
- cross-platform (plan declares [jvm]; trait widening forces all platforms):
  - JVM: 328/328 PASS (full kyo-tasty/test) and 2/2 targeted MappedByteViewTest
  - JS: PASS Test/compile (clean build, 57 main + 41 test sources compiled, 1 pre-existing E029 warn in QueryApiTest.scala:924)
  - Native: PASS Test/compile (clean build, 59 main + 41 test sources compiled, same pre-existing E029 warn)
- organization gate (8a/8b/8c): not re-run — Phase 04b modifies existing in-scope files only; no new package-kyo additions, no multi-type-per-file changes, new test file `MappedByteViewTest.scala` matches existing `MappedByteView.scala`

## Held-out acceptance check (class-B, opus-derived from 02-design.md)

Derived independently from 02-design.md's 64-bit-snapshot story (NOT from the
plan's leaf tests):
- Acceptance: after the cursor widening, reading at a logical position past
  `Int.MaxValue` MUST surface an explicit overflow signal rather than silently
  truncating to a negative Int and reading from offset 0. The new test
  `readByte past Int.MaxValue raises IllegalStateException with mmap segment
  overflow` exercises exactly this (cursor at `Int.MaxValue + 1L`, asserts
  the documented exception message). PASS on JVM.
- Acceptance: the seven reader files that build `TastyOrigin` MUST narrow
  Long view positions ONLY at sites that index a section-relative
  `Array[Byte]` (Int-bounded by definition), via either `positionInt`
  (wrapper using Math.toIntExact) or explicit `Math.toIntExact`, NEVER via
  `.toInt` (silent truncation). Diff sample shows seven `Math.toIntExact`
  conversions and 13 `positionInt` / `readEndInt` wrapper sites; zero
  `.toInt` truncations introduced. PASS by inspection.

## Class-B findings (opus judgment)

None.

Spot-checks against the FLOW-DESIGN catch-list yielded no findings:
- specific-to-catchall: no new catches in diff
- hash/value collision: tests assert on values produced by SUT
  (view.position, intercept of MappedByteView.readByte exception),
  not by the test
- coverage-claim mismatch: leaf 1 explicitly pins position Long return,
  leaf 2 explicitly pins overflow exception; both pin INV-018, B6 as
  declared in 05-plan.yaml
- stub returns expected value: no stubs; real mmap + real MappedByteView
- test controls own signal: position is read from the SUT, cursor set
  via `goto` (the API under widening)
- test bypasses API under test: test calls `view.position` and
  `view.readByte()` directly — the precise widened-cursor public API
- frame-propagation gap: internal binary module, no Kyo effects involved
- test-infra drift: no new test base class; `class MappedByteViewTest
  extends Test` matches existing kyo-tasty test convention
- fabricated facts: prep doc cited concrete line numbers (35-58, 25-26,
  30); decisions doc cited concrete files and call counts. Verified by
  reading both files; no fabrication

## INV verdict

- INV-018 (produced_by Phase 4): SATISFIED. MappedByteView accessors return
  Long cursor positions; overflow guard fires at Int.MaxValue boundary;
  smoke pinned by MappedByteViewTest leaves 1 and 2.
- INV-012 (declared consumed elsewhere; called out in the phase invocation
  as "INV-012 / INV-018 (Long cursor on snapshot mmap)"): the widening
  enables INV-012 by removing the Int-cursor barrier on snapshot mmap; the
  invariant proper is consumed by Phase 04c (truncated JarCentralDirectory
  detection) per 05-plan.yaml line 374. Phase 04b is non-violating for
  INV-012; no JAR-side code touched in this phase.

## Overrides

None. No `// flow-allow:` annotations introduced in the diff.

## Exit code: 0

Ready for commit.
