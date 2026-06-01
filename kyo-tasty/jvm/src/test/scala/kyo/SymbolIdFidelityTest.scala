package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for SymbolId(-1) sentinel pollution.
  *
  * Pins finding F-G-007 and INV-012. All leaves are PENDING until Phase 04 (partial) and Phase 11 (completion) un-pend them by interning a
  * single sentinelUnresolved per Classpath instead of fabricating a fresh `<unresolved>` symbol per `makeUnresolvedSym` call.
  */
class SymbolIdFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-G-007 / INV-012 leaf 1 (Phase 04 partial / Phase 11 completion): sentinel-count-phase04
    // Given: the real classpath loaded via TestClasspaths.withClasspath at the Phase 04 commit
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix (Phase 04) size is strictly less than at Phase 01 commit (up to 11);
    //       Phase 11 brings the count to <= 3;
    //       before Phase 04 every makeUnresolvedSym call produces a fresh name like
    //       termref@N, typeref@N, rec@N, this-unknown, ann, unknown-tpt, <unresolved>
    // Pins: INV-012 partial (F-G-007 partial)
    "INV-012 partial (Phase 04): SymbolId(-1) sentinel name-set size decreased from Phase 01 baseline" in pending

    // F-G-007 / INV-012 leaf 2 (Phase 11): sentinel-count-bounded
    // Given: the real classpath loaded via TestClasspaths.withClasspath at the Phase 11 commit
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix size <= 3 (one per failure category: unresolved, rec-placeholder, unknown-tag);
    //       before fix size > 3 (up to 11 distinct fabricated names)
    // Pins: INV-012 completion (F-G-007)
    "INV-012 (Phase 11): SymbolId(-1) sentinel name-set size <= 3 on real classpath" in pending

    // F-G-007 leaf 3 (Phase 11): no-rec-placeholder-names-final
    // Given: the real classpath loaded via TestClasspaths.withClasspath at the Phase 11 commit
    // When: scanning cp.symbols.map(_.name.asString)
    // Then: post-fix zero names start with "rec@", "rec-placeholder@", "typeref@", or "termref@";
    //       before fix all four prefixes appeared in cp.symbols as side effects of type decoding
    // Pins: F-G-007 (all fabricated-name categories eliminated)
    "F-G-007 (Phase 11): no fabricated type-decode placeholder names remain in cp.symbols" in pending

end SymbolIdFidelityTest
