# Phase 05b verify report

Run-id: phase-05b-verify-1
HEAD: 3e852b129 (Phase 05a; impl reports unchanged)
Working tree: dirty (Phase 05b in-flight)
Scope: B15 JarMappedReader inner try/catch/finally with explicit channel.close() in finally; +2 tests P05b-T1, P05b-T2

Status: FAIL (class-A)

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: GREEN
  - phase-05b-flow-verify-compile-jvm-1.log: `[success] Total time: 3 s`
  - phase-05b-flow-verify-testOnly-jvm-1.log: `Tests: succeeded 14, failed 0, canceled 0, ignored 0, pending 0` / `All tests passed.`
  - Includes both P05b-T1 (empty file, 1 ms) and P05b-T2 (malformed JAR, 2 ms) passing.

- reward-hacking grep: 1 hit, 0 overridden (PRE-EXISTING)
  - `swallow-throwable JvmFileSourceTest.scala:161` — PRE-EXISTING in `case _: Throwable => false` of the classpath-jar skip helper (unchanged from HEAD). NOT introduced by Phase 05b.

- fp-discipline grep: 6 hits, 0 overridden
  - PRE-EXISTING (not Phase-05b introduced):
    - `bare-var total = 0` JarMappedReader.scala:115 — inflater accumulator, unchanged
    - `null-literal` JarMappedReader.scala:37 — `if entry == null` HashMap.get bridge, has `// Unsafe:` justification on line 38, unchanged
    - `null-literal` JarMappedReader.scala:38 — the `// Unsafe:` comment text itself, unchanged
    - `local-val-over-annotation dataOffset: Long` JarMappedReader.scala:76 — unchanged
  - NEW (Phase 05b):
    - **`bare-var mbb: MappedByteBuffer = null` JarMappedReader.scala:152** — temporary holder needed to bind MappedByteBuffer outside the inner try (Java NIO channel.map() return value must outlive the channel.close() in the inner finally). Classifiable as Java-interop bridging analogous to Phase 03a precedent (NameUnpickler:50, SectionIndex:44 null bridges accepted as ACCEPTABLE class-B). Phase 03a was accepted with explanatory commentary; here line 152 lacks an inline `// Unsafe:` comment justifying the var+null pattern.
    - **`null-literal` JarMappedReader.scala:152** — same site, same justification. Same precedent.
  - Verdict: 2 NEW class-A hits at JarMappedReader.scala:152 (`var mbb = null`). Remediation options: (a) add `// flow-allow: java-nio-interop var/null required so channel.close() can run in inner finally while mbb survives` immediately before line 152, OR (b) add a `// Unsafe:` justification comment matching the pattern at line 38.

- llm-tells grep: 0 hits, 0 overridden — clean

- dev-tag grep: 0 hits, 0 overridden — clean (Phase 05b's test comments correctly DEV-tag-free; "Phase 05b - B15" reference is plain documentation per FLOW-DESIGN §8 exception for header markers, not a Phase-N TODO)

- plan-diff: AUTHORIZED=2 PRE-EXISTING=0 DRIFT-FROM-IMPL=0 MISSING=0
  - AUTHORIZED: `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarMappedReader.scala` (plan files_modified)
  - AUTHORIZED: `kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala` (plan tests.files)
  - Note: `flow-verify-plan-diff.sh` reports false DRIFT due to a script limitation (yq expression cannot extract `path:` field from object-form `files_modified` entries). Manual classification per plan yaml: both dirty source files are explicitly authorized. The baseline file `phase-05b-baseline.txt` is only-self-referential (captured after creation), but no third dirty file exists that needs PRE-EXISTING classification.

- test-count: expected=1 actual=2 (OVER-DELIVERED)
  - Plan declares 1 test leaf for B15 ("channel.map failure does not leak channel"). Impl shipped 2 tests (P05b-T1 empty-file, P05b-T2 malformed-content). Both validate the same B15 invariant from complementary angles (pre-map IOException vs post-map IOException; both confirm channel close before propagation). This is NOT a missing-test reward-hack; it is leaf-expansion. Class-A regex gate is strict equality so flags MISMATCH, but supervisor judgment: ACCEPTABLE over-delivery on the same B15 leaf. Recommendation: amend plan or accept as scope-positive.

- stowaway-commit: NONE
  - HEAD is `3e852b129` (Phase 05a, supervisor-authored, `fwbrasil@gmail.com`). No new commits introduced. Dirty tree only. No impl-stdout log exists to grep, but git shows zero new commits since HEAD.

- cross-platform: SKIPPED (plan declares `platforms: [jvm]` for Phase 05b; JVM-only)
  - JVM: 14/14 passed (JvmFileSourceTest)
  - JS: N/A per plan
  - Native: N/A per plan

- organization (Rule 8): not run (Phase 05b touches no package layout, no new files, no new test file basenames; pure edit to existing source + existing test file)

## Class-B findings (opus judgment)

1. **JarMappedReader.scala:152 `var mbb: MappedByteBuffer = null` — Java NIO interop, missing inline justification.** The pattern is structurally required: `channel.map()` must be assigned, channel must be closed in the inner finally, and the mapped buffer must survive into `parseAllEntries(jarPath, mbb)` and the constructor. There is no Kyo-native alternative (Java NIO ownership semantics mandate the channel close, and MappedByteBuffer is a Java type with no immutable-by-default substitute). Phase 03a precedent (`NameUnpickler.scala:50`, `SectionIndex.scala:44`) accepted analogous Java-interop nulls as ACCEPTABLE class-B with rationale documented in the decisions log. Here the impl pattern is correct; the missing piece is the `// Unsafe:` or `// flow-allow:` annotation matching the precedent at line 38. Rationale: bridging Java NIO ownership semantics. ACCEPTABLE-with-annotation.

2. **Test-count over-delivery 2 vs plan 1.** P05b-T1 and P05b-T2 split B15 into pre-map (empty file) and post-map (malformed content) scenarios. Both are substantive: T1 verifies the `size == 0` guard fires and channel closes; T2 verifies a `parseAllEntries` exception still leaves the channel closed (via the `!ClosedChannelException` assertion). Not reward-hacking (both assert the same B15 invariant from real failure paths). Recommendation: amend plan to test_count=2 or accept as scope-positive expansion.

3. **P05b-T2 negative assertion is structural-but-weak.** `assert(!ex.isInstanceOf[java.nio.channels.ClosedChannelException])` proves a `ClosedChannelException` is not constructed AT the propagation point, but does NOT directly prove the channel was closed before propagation; a future impl that closed the channel without throwing would also pass. Phase 04c precedent: stronger assertions also check exception messages. The companion `ex.getMessage != null && nonEmpty` is similarly tolerant. Class-B; the assertion is correct for the current impl (passes), but a stricter close-state check via reflection or a separate "post-error mapped-buffer still readable" assertion would harden it. Not commit-blocking.

## Held-out acceptance check (derived from 02-design.md B15)

Design semantics: after `JarMappedReader.open` throws (for ANY reason), the underlying `FileChannel` MUST be closed BEFORE the exception escapes `open()`. The test pair satisfies this design:
- P05b-T1 exercises the early `size == 0` path: the inner finally closes the channel between `throw` construction and `throw` propagation through `parseAllEntries`. The outer `raf.close()` is a redundant close that succeeds because channel was already closed.
- P05b-T2 exercises the late `parseAllEntries` path: channel was already closed by the inner finally before `parseAllEntries` ran, so the ZIP-parse failure cannot involve channel resources.

Held-out check verdict: PASS by trace. The impl structure satisfies the design semantics independently derived from B15.

## B15 verdict

Implemented correctly. Inner try/catch/finally with explicit `channel.close()` matches the audit's B15 prescription. Both test scenarios pass and assert the right invariants (no `ClosedChannelException`, IOException message hygiene, no `sun.nio.ch` bleed-through).

## Overrides

(none in this phase's diff)

## Remediation list (to clear class-A and reach PASS)

1. **(REQUIRED)** Add `// flow-allow:` or `// Unsafe:` annotation immediately before `JarMappedReader.scala:152` to document the Java-NIO interop var+null pattern. Suggested form, matching the existing line-38 convention:
   ```scala
   // Unsafe: var+null required so channel.close() can run in the inner finally
   // while the MappedByteBuffer survives to parseAllEntries and the constructor
   var mbb: MappedByteBuffer = null
   ```
2. **(SUPERVISOR JUDGMENT)** Decide on the test-count over-delivery: amend plan `tests.total: 2` and add P05b-T2 leaf entry, OR accept under "leaf-expansion within same B15 invariant" as a documented decision in `phase-05b-decisions.md`.

## Ready for commit

NOT YET. One required remediation (annotation on line 152) and one supervisor decision (test-count). After remediation 1, re-run `flow-verify-grep --catalog fp-discipline` to confirm the 2 NEW hits at line 152 are overridden; then PASS.

## Exit code: 1
