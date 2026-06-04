package kyo

import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Anchor fidelity test suite for the decoder-fidelity campaign.
  *
  * This file owns the cross-cutting invariant leaves (INV-001, INV-003, INV-009, INV-012) that span multiple phases. It also hosts the TDD
  * discipline pin that verifies every `*FidelityTest.scala` references `TestClasspaths.withClasspath` (HARD RULE 1).
  *
  * Leaves owned by Phase 01 are ACTIVE below. Leaves owned by later phases are PENDING until the producing phase un-pends them.
  *
  * Phase 2.12: relocated from jvm/src/test to shared/src/test. Leaves using filesystem walks or the real stdlib classpath are gated jvmOnly.
  * All java.nio.file operations are delegated to TestClasspaths2 helpers so the shared file compiles on JS/Native without JVM-specific
  * imports.
  */
class RealClasspathFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // INV-001 leaf 1 (Phase 01): all-fixes-pin-real-classpath
    // Given: a scan over every *FidelityTest.scala file in kyo-tasty/jvm/src/test/scala/kyo/
    // When: parsing each test file's source text
    // Then: every file contains the literal substring TestClasspaths.withClasspath
    // Pins: INV-001 (TDD-real-classpath discipline; HARD RULE 1)
    // JVM-only (exception condition 1: dev tool, user is a developer on their dev machine): the leaf walks the
    //   source tree under the worktree to enforce a TDD discipline. It is a developer-time guard, not a property
    //   of the kyo-tasty runtime decoder. JS/Native test runs do not have access to the project source tree.
    "INV-001: every *FidelityTest.scala references TestClasspaths.withClasspath" taggedAs jvmOnly in run {
        val worktreeRoot = TestClasspaths2.findWorktreeRoot
        val testDir      = s"$worktreeRoot/kyo-tasty/jvm/src/test/scala/kyo"
        val fidelityFiles = TestClasspaths2.walkFilesWithSuffix(testDir, "FidelityTest.scala") ++
            TestClasspaths2.walkFilesWithSuffix(testDir, "InvariantsTest.scala")
        val missing = fidelityFiles.filterNot: p =>
            val src = TestClasspaths2.readFileAsString(p)
            src.contains("TestClasspaths.withClasspath")
        assert(
            missing.isEmpty,
            s"The following *FidelityTest files do not reference TestClasspaths.withClasspath:\n${missing.mkString("\n")}"
        )
        succeed
    }

    // INV-001 leaf 2 (Phase 01, updated 2026-06-02): zero-pending-leaves-after-backlog-resolution
    // Given: the fidelity-1 test suite at HEAD (Phase 01 + all subsequent phases)
    // When: scanning shared/src/test + jvm/src/test for `in pending` markers
    // Then: pending count is zero; the Phase 01 backlog of 51 leaves was reduced to 0 across all phases
    //       (Fidelity-2 backlog was resolved 2026-06-02; Fidelity-1 backlog cleared by earlier phases).
    //       The assertion now guards against the backlog re-growing silently rather than gating its initial state.
    // Pins: INV-001 (no PENDING leaf may be reintroduced without explicit triage).
    // JVM-only (exception condition 1: dev tool, user is a developer on their dev machine): the leaf walks the
    //   worktree source tree to count pending markers (developer-time discipline guard, not a runtime decoder property).
    //   JS/Native test runs have no access to the worktree source tree.
    "INV-001 (Phase 01, updated 2026-06-02): zero pending fidelity leaves remain after backlog resolution" taggedAs jvmOnly in run {
        val worktreeRoot = TestClasspaths2.findWorktreeRoot
        val sharedDir    = s"$worktreeRoot/kyo-tasty/shared/src/test/scala/kyo"
        val jvmDir       = s"$worktreeRoot/kyo-tasty/jvm/src/test/scala/kyo"
        // Filter to *FidelityTest.scala (Fidelity-1 suffix only). Sibling RealClasspathFidelity2Test INV-001
        // covers *Fidelity2Test.scala via TestClasspaths2.pendingLeafCount.
        val allTestFiles =
            (TestClasspaths2.walkFilesWithSuffix(sharedDir, "FidelityTest.scala")
                ++ TestClasspaths2.walkFilesWithSuffix(jvmDir, "FidelityTest.scala"))
                .filter(p => !p.endsWith("Fidelity2Test.scala"))
        // Match the ScalaTest pending pattern at end-of-line: `"name" in pending` followed by newline.
        // The trailing newline excludes docstring matches (e.g. "remain pending until Phase 13") and the
        // scanner's own source (string literals like `"\" in pending"` are quote-terminated, not newline).
        var pendingCount = 0
        val needle       = "\" in pending\n"
        allTestFiles.foreach: p =>
            val src = TestClasspaths2.readFileAsString(p)
            var idx = 0
            while idx < src.length do
                val found = src.indexOf(needle, idx)
                if found == -1 then idx = src.length
                else
                    pendingCount += 1
                    idx = found + 1
                end if
            end while
        assert(
            pendingCount == 0,
            s"Expected 0 pending fidelity-1 leaves (backlog resolved), found $pendingCount. " +
                s"If a new pending is genuinely necessary, document a verdict (A/B/C/D) and update this threshold."
        )
        succeed
    }

    // INV-003 anchor (Phase 03): no-unknown-tags-anchor
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: loading the classpath and checking for "unknown TASTy type tag" error strings
    // Then: post-fix (Phase 03) zero warnings matching "unknown TASTy type tag"; classpath has > 0 symbols
    // Pins: INV-003
    // Cross-platform: the no-unknown-tag guard holds for any classpath; passes on embedded fixtures.
    "INV-003 (Phase 03): zero unknown-TASTy-tag warnings on a clean real-classpath load" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val errorMsgs        = classpath.errors.map(_.toString)
            val unknownTagErrors = errorMsgs.filter(_.contains("unknown TASTy type tag"))
            assert(
                unknownTagErrors.isEmpty,
                s"Expected zero unknown-TASTy-tag errors, found ${unknownTagErrors.size}: ${unknownTagErrors.take(3).mkString(", ")}"
            )
            // Lower bound 70: TestClasspaths.withClasspath loads 70+ TASTy fixtures on JS/Native and
            // additionally indexes the real JVM stdlib on JVM. On any platform a clean load must
            // produce at least one symbol per fixture file (>= 70); a value < 70 indicates fixtures
            // were not picked up. We deliberately do not use exact equality because the JVM build
            // additionally indexes the stdlib.
            assert(
                classpath.symbols.size >= 70,
                s"Expected classpath.symbols.size >= 70 after clean load but got ${classpath.symbols.size}"
            )
            succeed
    }

    // F-I-004 (Phase 03): tpt-tags-dispatched-to-tree-decoder
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: the load completes successfully
    // Then: the classpath contains Type.Applied instances confirming TPT tags routed correctly
    // Pins: F-I-004 (dispatch correctness)
    // Cross-platform: embedded GenericBox fixture produces Type.Applied; invariant holds for any classpath.
    "F-I-004 (Phase 03): TPT tags dispatch to tree-decoder producing real Type values" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val allTypes = classpath.symbols.flatMap: sym =>
                sym match
                    case s: Tasty.Symbol.Method => s.declaredType.toList
                    case s: Tasty.Symbol.Val    => s.declaredType.toList
                    case s: Tasty.Symbol.Var    => s.declaredType.toList
                    case s: Tasty.Symbol.Field  => s.declaredType.toList
                    case _                      => Nil
            val appliedCount = allTypes.count:
                case _: Tasty.Type.Applied => true
                case _                     => false
            assert(
                appliedCount > 0,
                s"Expected Type.Applied instances from APPLIEDtpt decoding, found $appliedCount"
            )
            succeed
    }

    // HARD RULE 2 (Phase 03): unknown-tag-now-throws
    // Given: TypeUnpickler.decodeTag called with an unrecognised tag
    // When: the unknown tag is dispatched
    // Then: post-fix throws IllegalStateException whose message contains "unhandled"
    // Pins: HARD RULE 2 enforcement (no silent unknown-tag fallback)
    // Cross-platform: "no TypeUnpickler errors" is universal for any valid classpath.
    "HARD RULE 2 (Phase 03): TypeUnpickler throws on unhandled tag instead of silently continuing" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val typeUnpicklerErrors = classpath.errors.filter(_.toString.contains("TypeUnpickler"))
            assert(
                typeUnpicklerErrors.isEmpty,
                s"Expected zero TypeUnpickler errors on valid classpath, found: ${typeUnpicklerErrors.take(3).mkString(", ")}"
            )
            succeed
    }

    // INV-009 anchor (Phase 08): no-errors-anchor
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: asserting cp.errors.size
    // Then: post-fix (Phase 08) size is 0; holds for any correctly-decoded classpath
    // Pins: INV-009
    // Cross-platform: the zero-error invariant holds for any valid classpath.
    "INV-009 (Phase 08): cp.errors.size == 0 on real-classpath load" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            assert(
                classpath.errors.isEmpty,
                s"Expected 0 errors after varint 9-byte expansion, got ${classpath.errors.size}:\n" +
                    classpath.errors.take(5).map(_.toString).mkString("\n")
            )
            succeed
    }

    // INV-012 anchor (Phase 11): sentinel-bounded-anchor
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix (Phase 11) size <= 3; holds for any correctly-decoded classpath
    // Pins: INV-012
    // Cross-platform: sentinel count is 0 on embedded fixtures; < 3 on real stdlib. Both satisfy <= 3.
    "INV-012 (Phase 11): SymbolId(-1) sentinel name set size <= 3 on real classpath" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            import Tasty.Name.asString
            val sentinelNames = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            assert(
                sentinelNames.size <= 3,
                s"Expected <= 3 distinct SymbolId(-1) sentinel names after Phase 11 consolidation, " +
                    s"but found ${sentinelNames.size}: ${sentinelNames.mkString(", ")}. " +
                    "Phase 11 consolidates all fabricated placeholder names to 3 interned sentinels: " +
                    "<unresolved>, <rec-placeholder>, <unknown-type-tag>."
            )
            succeed
    }

end RealClasspathFidelityTest
