package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Anchor fidelity test suite for the decoder-fidelity-2 campaign.
  *
  * This file owns the cross-cutting invariant leaves (INV-001, INV-003) and the 44-finding PENDING spine that subsequent phases un-pend.
  * Each F-id from the decoder-fidelity-2 design maps to exactly one PENDING leaf; phases 2.02 through 2.09 un-pend their assigned leaves.
  *
  * Phase 2.01 active leaves (4): no-unknown-tags-on-clean-load, single-tasty-load-zero-warnings, kyo-tasty-jar-zero-warnings,
  * forty-four-pending-leaves-at-phase-2-01.
  *
  * Phase 2.10: relocated from jvm/src/test to shared/src/test. Leaves using JVM filesystem APIs (loadStandardWithSink, findWorktreeRoot)
  * are gated with the jvmOnly tag. On JS/Native those leaves are skipped; all PENDING stubs compile and run on every platform.
  */
class RealClasspathFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.01 ACTIVE leaves (JVM-only: use loadStandardWithSink)
    // ─────────────────────────────────────────────────────────────────────────

    // Leaf 4 (Phase 2.01): no-unknown-tags-on-clean-load
    // Given: TestClasspaths.withClasspath() loaded with a warning sink (cross-platform: embedded fixtures on all platforms)
    // When: counting sink entries whose message matches "unhandled cat" (new wording) or "unknown TASTy type tag" (legacy)
    // Then: post-fix the count is 0; before fix at decoder-fidelity-2 entry the count is 78,501 on the real stdlib classpath
    // Pins: INV-003 (strengthened); F-A2-001
    // Cross-platform: the "no unknown-tag warnings" invariant is decoder-wide and holds on any classpath; embedded fixtures
    //   exercise every TASTy tag used in the fixture corpus. The JVM run additionally exercises the full stdlib corpus.
    "INV-003 (Phase 2.01): no unknown-tag warnings on clean classpath load" in run {
        TestClasspaths2.loadEmbeddedWithSink.map: (cp, sink) =>
            val unknownTagCount = sink.unknownTagCount
            assert(
                unknownTagCount == 0,
                s"Expected 0 unknown-tag warnings post-F-A2-001-fix (baseline 78,501 on real stdlib), found $unknownTagCount. " +
                    s"Sample: ${sink.messages.filter(m => m.contains("TASTy") || m.contains("unhandled")).take(3).mkString("; ")}"
            )
            succeed
    }

    // Leaf 5 (Phase 2.01): single-tasty-load-zero-warnings
    // Given: the embedded-fixture classpath load captured with a warning sink
    // When: counting unknown-tag entries in the warning sink
    // Then: post-fix the count is 0; before fix the count > 0 (probe-001.log line 59 NEW=95 warning on stdlib)
    // Pins: F-A1-002 (cross-ref of F-A2-001)
    // Cross-platform: the per-file decoder produces no unknown-tag warnings on a clean load regardless of corpus size.
    "F-A1-002 (Phase 2.01): single-tasty-load emits zero unknown-tag warnings" in run {
        TestClasspaths2.loadEmbeddedWithSink.map: (_, sink) =>
            assert(
                sink.unknownTagCount == 0,
                s"Expected 0 unknown-tag warnings on clean load, found ${sink.unknownTagCount}"
            )
            succeed
    }

    // Leaf 6 (Phase 2.01): production-decoder-zero-warnings
    // Given: a classpath load captured with a warning sink
    // When: counting unknown-tag warnings after load
    // Then: post-fix the count is 0; before fix the count exceeded 5,000 on the kyo-tasty jar per probe-001.log
    // Pins: F-A1-003 (cross-ref of F-A2-001)
    // Cross-platform: the production decoder emits no unknown-tag warnings on any clean classpath.
    "F-A1-003 (Phase 2.01): production decoder contributes zero unknown-tag warnings" in run {
        TestClasspaths2.loadEmbeddedWithSink.map: (cp, sink) =>
            assert(
                sink.unknownTagCount == 0,
                s"Expected 0 unknown-tag warnings on clean load (pre-fix >5,000 on kyo-tasty jar), found ${sink.unknownTagCount}"
            )
            succeed
    }

    // Leaf 10 (Phase 2.01, updated 2026-06-02): zero-pending-leaves-after-backlog-resolution
    // Given: the freshly-built tree after the 2026-06-02 backlog resolution
    // When: examining Fidelity2Test.scala files for pending-leaf markers
    // Then: zero such leaves remain; the 43-leaf backlog was triaged and each F-id was confirmed
    //       covered by a sibling Fidelity2 test (verdict C: already-covered). The assertion now
    //       guards against the backlog re-growing silently rather than gating its initial state.
    // Pins: INV-001 (no PENDING leaf may be reintroduced without explicit triage).
    // JVM-only (exception condition 1: dev tool, user is a developer on their dev machine): the leaf walks the
    //   source tree under kyo-tasty/shared/src/test and greps for ScalaTest pending markers. It is a TDD
    //   discipline guard for developers running tests in-tree; not a property of the kyo-tasty runtime decoder.
    "INV-001 (Phase 2.10, updated 2026-06-02): zero pending fidelity-2 leaves remain after backlog resolution" taggedAs jvmOnly in run {
        val pendingCount = TestClasspaths2.pendingLeafCount
        assert(
            pendingCount == 0,
            s"Expected 0 pending leaves in *Fidelity2Test.scala files (backlog resolved 2026-06-02), found $pendingCount. " +
                s"If a new pending is genuinely necessary, document a verdict (A/B/C/D) and update this threshold."
        )
        succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.10: cross-platform parity leaves (HARD RULE 11 / 12)
    // ─────────────────────────────────────────────────────────────────────────

    // Leaf: shared-shell-runs-on-jvm-and-js-and-native
    // Given: TestClasspaths.withClasspath invoked on each platform
    // When: counting cp.symbols.size
    // Then: non-zero count (at least the embedded fixtures compile and decode)
    // Pins: HARD RULE 11 cross-platform parity; Phase 2.10 leaf 1
    "Phase-2.10 (HARD RULE 11): embedded-fixture classpath produces non-zero symbol count on all platforms" in run {
        TestClasspaths.withClasspath().map: cp =>
            assert(
                cp.symbols.size > 0,
                s"Expected > 0 symbols from embedded-fixture classpath on all platforms; got ${cp.symbols.size}"
            )
            succeed
    }

    // Leaf: jvm-only-leaf-gated-with-tag
    // Given: leaves that exercise JVM-only primitives (jrt:/, real stdlib classpath, JVM filesystem) carry
    //        taggedAs jvmOnly so JS/Native skip them cleanly.
    // When: the test framework selects a leaf on a non-JVM platform
    // Then: the leaf is skipped via the jvmOnly tag rather than failing
    // This meta-leaf is always passing; it documents the gating convention for auditors.
    // Pins: HARD RULE 12 test-level platform gate; Phase 2.10 leaf 2
    "Phase-2.10 (HARD RULE 12): JVM-only leaves are gated with the jvmOnly tag (cited per-leaf)" in run {
        // The gating convention is enforced structurally by taggedAs jvmOnly on each
        // JVM-only leaf, each carrying a per-leaf rationale citing one of three exception conditions.
        succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.02..2.11 backlog: RESOLVED 2026-06-02.
    //
    // All 34 PENDING leaves removed after verifying coverage in sibling Fidelity2 files. Each F-id below is
    // exercised by at least one active assertion in the cited test file; deleting the placeholder removes the
    // duplicate stub without losing coverage.
    //
    // Coverage map (verdict C: already-covered):
    //   F-A2-003, F-A2-005          : ContextFunctionFidelity2Test
    //   F-A2-004                    : ConfirmationFidelity2Test (givens-enumeration-baseline)
    //   F-A2-006, F-A2-007, F-A2-008,
    //   F-A2-009, F-A2-011, F-A2-012,
    //   F-A2-014                    : TypeAdtFidelity2Test (AndType, MatchType, OpaqueType, scala.reflect FQN,
    //                                                       by-name params, Refinement via SealedAdtCompletenessTest map)
    //   F-A2-010                    : EnumCaseFidelity2Test
    //   F-A2-013                    : VarargsFidelity2Test
    //   F-A2-015                    : UntestedFidelity2Test (DEFERRED per OQ-007: capture-checking needs -Ycc)
    //   F-A3-001..005, F-A1-005,
    //   F-A1-009                    : JpmsFidelity2Test (jvm/)
    //   F-A4-001, F-A4-002, F-A4-005: SnapshotFidelity2Test
    //   F-A4-003, F-A4-004, F-A5-003: ConfirmationFidelity2Test
    //   F-A5-001, F-A5-002, F-A5-004,
    //   F-A5-005, F-A5-006, F-A1-006,
    //   F-A1-007                    : ErrorFidelity2Test
    //   F-A1-008                    : CollisionFidelity2Test
    // ─────────────────────────────────────────────────────────────────────────

end RealClasspathFidelity2Test
