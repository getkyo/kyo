package kyo

import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Anchor fidelity test suite for the decoder-fidelity-2 campaign.
  *
  * This file owns the cross-cutting invariant leaves (INV-001, INV-003) and the 44-finding PENDING spine that subsequent phases un-pend.
  * Each F-id from the decoder-fidelity-2 design maps to exactly one PENDING leaf; phases 2.02 through 2.09 un-pend their assigned leaves.
  *
  * Phase 2.01 active leaves (4): no-unknown-tags-on-clean-load, single-tasty-load-zero-warnings, kyo-tasty-jar-zero-warnings,
  * forty-four-pending-leaves-at-phase-2-01.
  */
class RealClasspathFidelity2Test extends Test:

    import AllowUnsafe.embrace.danger

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.01 ACTIVE leaves
    // ─────────────────────────────────────────────────────────────────────────

    // Leaf 4 (Phase 2.01): no-unknown-tags-on-clean-load
    // Given: TestClasspaths2.standardRoots loaded with a warning sink
    // When: counting sink entries whose message matches "unhandled cat" (new wording) or "unknown TASTy type tag" (legacy)
    // Then: post-fix the count is 0; before fix at decoder-fidelity-2 entry the count is 78,501
    // Pins: INV-003 (strengthened); F-A2-001
    "INV-003 (Phase 2.01): no unknown-tag warnings on clean standard-classpath load" in run {
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
    "F-A1-002 (Phase 2.01): single-tasty-load emits zero unknown-tag warnings" in run {
        TestClasspaths2.loadStandardWithSink.map: (_, sink) =>
            // The standard load includes kyo-tasty files. The kyo-tasty jar contributed to the
            // pre-fix 78,501 warnings; post-fix the count must be 0.
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
    "F-A1-003 (Phase 2.01): kyo-tasty jar contributes zero unknown-tag warnings" in run {
        TestClasspaths2.loadStandardWithSink.map: (cp, sink) =>
            assert(
                sink.unknownTagCount == 0,
                s"Expected 0 unknown-tag warnings from kyo-tasty jar (pre-fix >5,000), found ${sink.unknownTagCount}"
            )
            succeed
    }

    // Leaf 10 (Phase 2.01): forty-four-pending-leaves-at-phase-2-01
    // Given: the freshly-built tree at Phase 2.01 commit (no Phase 2.02..2.11 fixes applied)
    // When: examining this test class for pending markers
    // Then: at least 44 leaves report pending status in the Fidelity2 suites;
    //       subsequent phases 2.02..2.09 un-pend their assigned leaves progressively.
    // Pins: INV-001 producer (every finding pinned as a PENDING-failing leaf)
    "INV-001 (Phase 2.01): at least 44 pending fidelity-2 leaves exist at Phase 2.01 commit" in run {
        val worktreeRoot = findWorktreeRoot
        val testDir      = worktreeRoot.resolve("kyo-tasty/jvm/src/test/scala/kyo")
        val allTestFiles =
            java.nio.file.Files
                .walk(testDir)
                .filter(p => p.getFileName.toString.endsWith("Fidelity2Test.scala"))
                .toArray
                .map(_.asInstanceOf[java.nio.file.Path])
        var pendingCount = 0
        allTestFiles.foreach: p =>
            val src = new String(java.nio.file.Files.readAllBytes(p), "UTF-8")
            var idx = 0
            while idx < src.length do
                val found = src.indexOf("in pending", idx)
                if found == -1 then idx = src.length
                else
                    pendingCount += 1
                    idx = found + 1
                end if
            end while
        assert(
            pendingCount >= 44,
            s"Expected >= 44 pending leaves in *Fidelity2Test.scala files, found $pendingCount. " +
                s"Files scanned: ${allTestFiles.map(_.getFileName.toString).mkString(", ")}"
        )
        succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.02..2.11 PENDING leaves (one per F-id; un-pended by later phases)
    // ─────────────────────────────────────────────────────────────────────────

    // F-A2-003: context functions appear in type position (Phase 2.04)
    "F-A2-003 (Phase 2.04 PENDING): context-function Type.ContextFunction present in stdlib" in pending
    // F-A2-004: union/intersection types correct (Phase 2.08)
    "F-A2-004 (Phase 2.08 PENDING): intersection-type Applied collapsed to AndType" in pending
    // F-A2-005: ContextFunction type ADT (Phase 2.04)
    "F-A2-005 (Phase 2.04 PENDING): scala.Conversion.convert declaredType reaches ContextFunction" in pending
    // F-A2-006: match types MatchCase children (Phase 2.08)
    "F-A2-006 (Phase 2.08 PENDING): MATCHCASEtype decoded into Type.MatchCase with correct children" in pending
    // F-A2-007: transparentInline detection (Phase 2.08)
    "F-A2-007 (Phase 2.08 PENDING): transparentInline flag detected correctly on inline methods" in pending
    // F-A2-008: opaqueType paramIds in TypeLambda (Phase 2.08)
    "F-A2-008 (Phase 2.08 PENDING): opaqueType body TypeLambda paramIds resolve to non-sentinel" in pending
    // F-A2-009: scala.reflect FQN normalization (Phase 2.08)
    "F-A2-009 (Phase 2.08 PENDING): scala.reflect.* FQN resolves via FqnNormalizer" in pending
    // F-A2-010: value-form enum cases (Phase 2.05)
    "F-A2-010 (Phase 2.05 PENDING): value-form EnumCase count grows from 2 to > 10" in pending
    // F-A2-011: given/using priorities (Phase 2.08)
    "F-A2-011 (Phase 2.08 PENDING): given priority flags decoded from Using/Implicit modifier bits" in pending
    // F-A2-012: polymorphic function types (Phase 2.08)
    "F-A2-012 (Phase 2.08 PENDING): polymorphic function type decoded as TypeLambda wrapper" in pending
    // F-A2-013: repeated parameters (Phase 2.06)
    "F-A2-013 (Phase 2.06 PENDING): scala.List$.apply first param declaredType is Type.Repeated" in pending
    // F-A2-014: structural types (Phase 2.08)
    "F-A2-014 (Phase 2.08 PENDING): structural type decoded as Type.Refinement with non-empty members" in pending
    // F-A2-015: capture sets experimental (Phase 2.09 confirmation pin)
    "F-A2-015 (Phase 2.09 PENDING): capture-set annotation present on capture-typed symbols" in pending
    // F-A3-001: JDK class symbols via jrt:/ (Phase 2.03)
    "F-A3-001 (Phase 2.03 PENDING): cp.findClassLike('java.lang.String') returns Present after jrt:/ walk" in pending
    // F-A3-002: Java enums (Phase 2.03)
    "F-A3-002 (Phase 2.03 PENDING): java.lang.annotation.RetentionPolicy.isEnum is true" in pending
    // F-A3-003: Java records (Phase 2.03)
    "F-A3-003 (Phase 2.03 PENDING): java.lang.Runtime.Version.isRecord is true" in pending
    // F-A3-004: synthetic static accessors (Phase 2.03)
    "F-A3-004 (Phase 2.03 PENDING): JDK synthetic-static accessor resolves via findSymbol" in pending
    // F-A3-005: JPMS module count confirmation (Phase 2.03)
    "F-A3-005 (Phase 2.03 PENDING): cp.moduleIndex.size == 69 after jrt:/ walker addition" in pending
    // F-A4-001: finalizeMerge ghost fqnIndex entries (Phase 2.02)
    "F-A4-001 (Phase 2.02 PENDING): cold.fqnIndex.size == warm.fqnIndex.size and both >= 110,000" in pending
    // F-A4-002: cold-warm parentTypes discrepancy (Phase 2.02)
    "F-A4-002 (Phase 2.02 PENDING): cold.unresolvedRefs == warm.unresolvedRefs == 0" in pending
    // F-A4-003: snapshot version downgrade (Phase 2.09)
    "F-A4-003 (Phase 2.09 PENDING): snapshot version downgrade detected and handled as FileNotFound" in pending
    // F-A4-004: concurrent reader+writer (Phase 2.09)
    "F-A4-004 (Phase 2.09 PENDING): concurrent snapshot reader+writer does not corrupt the written file" in pending
    // F-A4-005: snapshot idempotency byte-equality (Phase 2.02)
    "F-A4-005 (Phase 2.02 PENDING): two independent cold-init invocations write byte-equal .krfl files" in pending
    // F-A5-001: requireSymbol (Phase 2.07)
    "F-A5-001 (Phase 2.07 PENDING): cp.requireSymbol('non.existent') aborts with TastyError.SymbolNotFound" in pending
    // F-A5-002: SoftFail FileNotFound (Phase 2.07)
    "F-A5-002 (Phase 2.07 PENDING): SoftFail missing-root produces cp.errors.head == FileNotFound" in pending
    // F-A5-003: Java-only jar single-classfile (Phase 2.09)
    "F-A5-003 (Phase 2.09 PENDING): Java-only .class-only jar symbols load correctly with no .tasty" in pending
    // F-A5-004: mid-stream corrupted file (Phase 2.07)
    "F-A5-004 (Phase 2.07 PENDING): mid-stream corrupted .tasty produces structured TastyError with on-disk path" in pending
    // F-A5-005: MalformedSection error reason (Phase 2.07)
    "F-A5-005 (Phase 2.07 PENDING): truncated .tasty error reason is impl-agnostic string not raw exception message" in pending
    // F-A5-006: corrupted file cp.errors path field (Phase 2.07)
    "F-A5-006 (Phase 2.07 PENDING): bit-flipped magic .tasty cp.errors.head.path equals on-disk filename" in pending
    // F-A1-005: cross-ref to F-A3-001 (JPMS JDK count) (Phase 2.03)
    "F-A1-005 (Phase 2.03 PENDING): initWithPlatformModules includes JDK class symbols" in pending
    // F-A1-006: MalformedSection cross-ref (Phase 2.07)
    "F-A1-006 (Phase 2.07 PENDING): MalformedSection cross-ref with impl-agnostic reason" in pending
    // F-A1-007: CorruptedFile path cross-ref (Phase 2.07)
    "F-A1-007 (Phase 2.07 PENDING): CorruptedFile cp.errors path cross-ref" in pending
    // F-A1-008: FqnCollision diagnostic (Phase 2.07)
    "F-A1-008 (Phase 2.07 PENDING): same-FQN collision in two jars emits FqnCollision diagnostic" in pending
    // F-A1-009: unresolvedTypeReferenceCount == 0 (Phase 2.03)
    "F-A1-009 (Phase 2.03 PENDING): cp.unresolvedTypeReferenceCount == 0 on full classpath including JDK" in pending

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private def findWorktreeRoot: java.nio.file.Path =
        var candidate = java.nio.file.Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath
        while candidate != null && !java.nio.file.Files.exists(candidate.resolve("build.sbt")) do
            candidate = candidate.getParent
        require(candidate != null, "Could not locate worktree root (no build.sbt found in ancestors of user.dir)")
        candidate
    end findWorktreeRoot

end RealClasspathFidelity2Test
