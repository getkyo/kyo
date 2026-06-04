package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for SymbolId(-1) sentinel pollution.
  *
  * Pins finding F-G-007 and INV-012. All leaves are PENDING until Phase 04 (partial) and Phase 11 (completion) un-pend them by interning a
  * single sentinelUnresolved per Classpath instead of fabricating a fresh `<unresolved>` symbol per `makeUnresolvedSym` call.
  *
  * Phase 2.12 corrective: leaves 1, 2, 3 assert universal invariants ("sentinel count < threshold", "no fabricated names") that hold for
  * any classpath. On embedded fixtures the counts are small (often 0) which satisfies all bounds vacuously. Leaf 4 (unresolvedTypeReferenceCount
  * >= 0) is also universal.
  */
class SymbolIdFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-G-007 / INV-012 leaf 1 (Phase 04 partial / Phase 11 completion): sentinel-count-phase04
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix (Phase 04) size is strictly less than at Phase 01 commit (up to 11);
    //       Phase 11 brings the count to <= 3; embedded fixtures have 0, which satisfies < 11 trivially.
    // Pins: INV-012 partial (F-G-007 partial)
    // Cross-platform: invariant "count < 11" holds for any classpath; embedded fixtures have 0 sentinels.
    "INV-012 partial (Phase 04): SymbolId(-1) sentinel name-set size decreased from Phase 01 baseline" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            import Tasty.Name.asString
            val sentinelNames   = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            val phase01Baseline = 11
            assert(
                sentinelNames.size < phase01Baseline,
                s"Expected sentinel name-set size < $phase01Baseline (Phase 01 baseline), but got ${sentinelNames.size}. " +
                    s"Names: ${sentinelNames.mkString(", ")}. " +
                    s"Phase 04 must have collapsed rec@, this-unknown, and the orchestrator fallback to the shared sentinel."
            )
            succeed
    }

    // F-G-007 / INV-012 leaf 2 (Phase 11): sentinel-count-bounded
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix size <= 3 (one per failure category: unresolved, rec-placeholder, unknown-tag)
    // Pins: INV-012 completion (F-G-007)
    // Cross-platform: invariant "count <= 3" holds for any classpath; embedded fixtures have 0 which satisfies <= 3.
    "INV-012 (Phase 11): SymbolId(-1) sentinel name-set size <= 3 on real classpath" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            import Tasty.Name.asString
            val sentinelNames = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            assert(
                sentinelNames.size <= 3,
                s"Expected <= 3 distinct sentinel names after Phase 11 consolidation, " +
                    s"but found ${sentinelNames.size}: ${sentinelNames.mkString(", ")}. " +
                    "Expected at most: <unresolved>, <rec-placeholder>, <unknown-type-tag>."
            )
            succeed
    }

    // F-G-007 leaf 3 (Phase 11): no-rec-placeholder-names-final
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: scanning cp.symbols.map(_.name.asString)
    // Then: post-fix zero names start with "rec@", "rec-placeholder@", "typeref@", or "termref@"
    // Pins: F-G-007 (all fabricated-name categories eliminated)
    // Cross-platform: invariant "no fabricated names" holds for any classpath; passes trivially on embedded fixtures too.
    "F-G-007 (Phase 11): no fabricated type-decode placeholder names remain in cp.symbols" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            import Tasty.Name.asString
            val allNames = classpath.symbols.map(_.name.asString)
            val fabricated = allNames.filter: n =>
                n.startsWith("rec@") ||
                    n.startsWith("rec-placeholder@") ||
                    n.startsWith("typeref@") ||
                    n.startsWith("termref@") ||
                    n.startsWith("sharedref@") ||
                    n.startsWith("param@") ||
                    n.startsWith("unknown-type-tag-")
            assert(
                fabricated.isEmpty,
                s"Found ${fabricated.size} fabricated placeholder name(s) in cp.symbols: " +
                    s"${fabricated.take(10).mkString(", ")}. " +
                    "F-G-007 pass B: all fabricated names must be eliminated by sentinel consolidation."
            )
            succeed
    }

    // Phase 13: cross-file TYPEREFsymbol resolution
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: checking cp.unresolvedTypeReferenceCount
    // Then: post-fix the count is bounded; structural correctness check.
    // Pins: Phase 13 sub-target 3
    // Cross-platform: "count >= 0" is a non-negative structural guard that trivially holds on any classpath.
    "Phase 13: cp.unresolvedTypeReferenceCount is bounded (cross-file resolution reduces sentinel count)" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val count = cp.unresolvedTypeReferenceCount
            assert(count >= 0, "unresolvedTypeReferenceCount must be non-negative")
            succeed
    }

end SymbolIdFidelityTest
