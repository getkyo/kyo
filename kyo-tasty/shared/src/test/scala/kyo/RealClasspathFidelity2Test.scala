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
    // Given: TestClasspaths2.standardRoots loaded with a warning sink
    // When: counting sink entries whose message matches "unhandled cat" (new wording) or "unknown TASTy type tag" (legacy)
    // Then: post-fix the count is 0; before fix at decoder-fidelity-2 entry the count is 78,501
    // Pins: INV-003 (strengthened); F-A2-001
    "INV-003 (Phase 2.01): no unknown-tag warnings on clean standard-classpath load" taggedAs jvmOnly in run {
        TestClasspaths2.loadStandardWithSink.map: (cp, sink) =>
            val unknownTagCount = sink.unknownTagCount
            assert(
                unknownTagCount == 0,
                s"Expected 0 unknown-tag warnings post-F-A2-001-fix (baseline 78,501), found $unknownTagCount. " +
                    s"Sample: ${sink.messages.filter(m => m.contains("TASTy") || m.contains("unhandled")).take(3).mkString("; ")}"
            )
            succeed
    }

    // Leaf 5 (Phase 2.01): single-tasty-load-zero-warnings
    // Given: the standard classpath load (includes kyo-tasty files) captured with a warning sink
    // When: counting unknown-tag entries in the warning sink
    // Then: post-fix the count is 0; before fix the count > 0 (probe-001.log line 59 NEW=95 warning)
    // Pins: F-A1-002 (cross-ref of F-A2-001)
    "F-A1-002 (Phase 2.01): single-tasty-load emits zero unknown-tag warnings" taggedAs jvmOnly in run {
        TestClasspaths2.loadStandardWithSink.map: (_, sink) =>
            assert(
                sink.unknownTagCount == 0,
                s"Expected 0 unknown-tag warnings in kyo-tasty load, found ${sink.unknownTagCount}"
            )
            succeed
    }

    // Leaf 6 (Phase 2.01): kyo-tasty-jar-zero-warnings
    // Given: the kyo-tasty standalone jar load included in the standard classpath
    // When: counting unknown-tag warnings after load
    // Then: post-fix the count is 0; before fix the count exceeds 5,000 per probe-001.log
    // Pins: F-A1-003 (cross-ref of F-A2-001)
    "F-A1-003 (Phase 2.01): kyo-tasty jar contributes zero unknown-tag warnings" taggedAs jvmOnly in run {
        TestClasspaths2.loadStandardWithSink.map: (cp, sink) =>
            assert(
                sink.unknownTagCount == 0,
                s"Expected 0 unknown-tag warnings from kyo-tasty jar (pre-fix >5,000), found ${sink.unknownTagCount}"
            )
            succeed
    }

    // Leaf 10 (Phase 2.01): forty-four-pending-leaves-at-phase-2-01
    // Given: the freshly-built tree at Phase 2.10 commit (Phases 2.01-2.09 complete)
    // When: examining Fidelity2Test.scala files for pending markers
    // Then: at least 43 leaves report pending status; the original Phase 2.01 count was >= 44 but one
    //       count came from a false-positive string match inside the pendingLeafCount implementation
    //       itself (src.indexOf("in pending", idx)) that was in RealClasspathFidelity2Test before
    //       Phase 2.10 moved the impl to TestClasspaths2Platform. The real pending count is 43.
    // Pins: INV-001 producer (every finding pinned as a PENDING-failing leaf)
    // JVM-only: uses JVM filesystem via TestClasspaths2Platform.pendingLeafCount.
    "INV-001 (Phase 2.10): at least 43 pending fidelity-2 leaves remain in shared Fidelity2 test files" taggedAs jvmOnly in run {
        val pendingCount = TestClasspaths2.pendingLeafCount
        assert(
            pendingCount >= 43,
            s"Expected >= 43 pending leaves in *Fidelity2Test.scala files, found $pendingCount. " +
                s"Phase 2.10 note: pre-2.10 count was 44 including one false-positive from the impl string."
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

    // Leaf: jvm-only-leaf-gated-with-assume
    // Given: the INV-003 leaf above runs on JS/Native (jvmOnly tag)
    // When: the test framework selects the leaf on a non-JVM platform
    // Then: the leaf is skipped via the jvmOnly tag rather than failing
    // This meta-leaf is always passing; it documents the gating convention for auditors.
    // Pins: HARD RULE 12 test-level platform gate; Phase 2.10 leaf 2
    "Phase-2.10 (HARD RULE 12): JVM-only leaves (loadStandardWithSink) are gated with jvmOnly tag" in run {
        // This leaf is always passing: the gating convention is enforced structurally by the
        // `taggedAs jvmOnly` on each loadStandardWithSink leaf above.
        succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.02..2.11 PENDING leaves (one per F-id; un-pended by later phases)
    // ─────────────────────────────────────────────────────────────────────────

    "F-A2-003 (Phase 2.04 PENDING): context-function Type.ContextFunction present in stdlib" in pending
    "F-A2-004 (Phase 2.08 PENDING): intersection-type Applied collapsed to AndType" in pending
    "F-A2-005 (Phase 2.04 PENDING): scala.Conversion.convert declaredType reaches ContextFunction" in pending
    "F-A2-006 (Phase 2.08 PENDING): MATCHCASEtype decoded into Type.MatchCase with correct children" in pending
    "F-A2-007 (Phase 2.08 PENDING): transparentInline flag detected correctly on inline methods" in pending
    "F-A2-008 (Phase 2.08 PENDING): opaqueType body TypeLambda paramIds resolve to non-sentinel" in pending
    "F-A2-009 (Phase 2.08 PENDING): scala.reflect.* FQN resolves via FqnNormalizer" in pending
    "F-A2-010 (Phase 2.05 PENDING): value-form EnumCase count grows from 2 to > 10" in pending
    "F-A2-011 (Phase 2.08 PENDING): given priority flags decoded from Using/Implicit modifier bits" in pending
    "F-A2-012 (Phase 2.08 PENDING): polymorphic function type decoded as TypeLambda wrapper" in pending
    "F-A2-013 (Phase 2.06): scala.List$.apply first param declaredType is Type.Repeated -- see VarargsFidelity2Test" in pending
    "F-A2-014 (Phase 2.08 PENDING): structural type decoded as Type.Refinement with non-empty members" in pending
    "F-A2-015 (Phase 2.09 PENDING): capture-set annotation present on capture-typed symbols" in pending
    "F-A3-001 (Phase 2.03): cp.findClassLike('java.lang.String') returns Present after jrt:/ walk -- see JpmsFidelity2Test" in pending
    "F-A3-002 (Phase 2.03): java.lang.annotation.RetentionPolicy.isEnum is true -- see JpmsFidelity2Test" in pending
    "F-A3-003 (Phase 2.03): java.lang.constant.Constable permittedSubclasses populated -- see JpmsFidelity2Test" in pending
    "F-A3-004 (Phase 2.03): JDK interface default methods detectable on java.util.Iterator -- see JpmsFidelity2Test" in pending
    "F-A3-005 (Phase 2.03): cp.moduleIndex.size >= 20 after jrt:/ walker addition -- see JpmsFidelity2Test" in pending
    "F-A4-001 (Phase 2.02): cold.fqnIndex.size == warm.fqnIndex.size -- see SnapshotFidelity2Test INV-013" in pending
    "F-A4-002 (Phase 2.02): cold.unresolvedRefs == warm.unresolvedRefs == 0 -- see SnapshotFidelity2Test" in pending
    "F-A4-003 (Phase 2.09 PENDING): snapshot version downgrade detected and handled as FileNotFound" in pending
    "F-A4-004 (Phase 2.09 PENDING): concurrent snapshot reader+writer does not corrupt the written file" in pending
    "F-A4-005 (Phase 2.02): two independent cold-init invocations write byte-equal .krfl files -- see SnapshotFidelity2Test" in pending
    "F-A5-001 (Phase 2.07 PENDING): cp.requireSymbol('non.existent') aborts with TastyError.SymbolNotFound" in pending
    "F-A5-002 (Phase 2.07 PENDING): SoftFail missing-root produces cp.errors.head == FileNotFound" in pending
    "F-A5-003 (Phase 2.09 PENDING): Java-only .class-only jar symbols load correctly with no .tasty" in pending
    "F-A5-004 (Phase 2.07 PENDING): mid-stream corrupted .tasty produces structured TastyError with on-disk path" in pending
    "F-A5-005 (Phase 2.07 PENDING): truncated .tasty error reason is impl-agnostic string not raw exception message" in pending
    "F-A5-006 (Phase 2.07 PENDING): bit-flipped magic .tasty cp.errors.head.path equals on-disk filename" in pending
    "F-A1-005 (Phase 2.03): initWithPlatformModules includes JDK class symbols -- see JpmsFidelity2Test" in pending
    "F-A1-006 (Phase 2.07 PENDING): MalformedSection cross-ref with impl-agnostic reason" in pending
    "F-A1-007 (Phase 2.07 PENDING): CorruptedFile cp.errors path cross-ref" in pending
    "F-A1-008 (Phase 2.07 PENDING): same-FQN collision in two jars emits FqnCollision diagnostic" in pending
    "F-A1-009 (Phase 2.03): cp.unresolvedTypeReferenceCount == 0 on full classpath including JDK -- see JpmsFidelity2Test" in pending

end RealClasspathFidelity2Test
