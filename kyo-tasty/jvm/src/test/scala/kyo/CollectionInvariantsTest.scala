package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for collection invariants: topLevelClasses vs allClasses size relation, isGiven/isMacro predicate tightening, and
  * javaMetadata merging.
  *
  * Pins findings F-G-002, F-G-006, F-E-004, F-E-005, F-E-006. All leaves are PENDING until Phase 11 un-pends them by redefining
  * `topLevelClasses`/`allClasses` to return `Chunk[Symbol.ClassLike]`, tightening `isGiven`/`isMacro`, and merging javaMetadata when both
  * .tasty and .class companions exist.
  */
class CollectionInvariantsTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-G-006 / INV-008 leaf 1 (Phase 11): all-superset-of-toplevel
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: asserting cp.allClasses.size >= cp.topLevelClasses.size
    // Then: post-fix the assertion holds;
    //       before fix cp.topLevelClasses.size == 3,514 and cp.allClasses.size == 1,508
    //       (the ratio was inverted because topLevelClasses over-counted package-level entries
    //       while allClasses filtered to Symbol.Class only, excluding Object and Trait)
    // Pins: INV-008 producer (F-G-006)
    "F-G-006 / INV-008 (Phase 11): cp.allClasses.size >= cp.topLevelClasses.size" in pending

    // F-G-006 leaf 2 (Phase 11): toplevel-subset-of-all
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: asserting cp.topLevelClasses.toSet.subsetOf(cp.allClasses.toSet)
    // Then: post-fix the assertion holds;
    //       before fix the sets had incompatible element types so subsetOf was not even expressible
    // Pins: F-G-006
    "F-G-006 (Phase 11): cp.topLevelClasses is a subset of cp.allClasses" in pending

    // F-E-004 leaf 3 (Phase 11): isgiven-excludes-parameters
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: iterating cp.symbols.filter(_.isGiven)
    // Then: post-fix every result is NOT a Symbol.Parameter;
    //       before fix at least one result was a Symbol.Parameter because every using-clause
    //       parameter carries Flag.Given and isGiven did not exclude them
    // Pins: F-E-004
    "F-E-004 (Phase 11): isGiven returns false for Symbol.Parameter (using-clause params excluded)" in pending

    // F-E-006 leaf 4 (Phase 11): ismacro-excludes-enumcase-synthetics
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: iterating cp.symbols.filter(_.isMacro)
    // Then: post-fix every result is a Symbol.Method whose owner is NOT Symbol.EnumCase;
    //       before fix at least one result was a synthetic method owned by a TastyError enum case
    //       because enum-case synthetic methods carried Flag.Macro
    // Pins: F-E-006
    "F-E-006 (Phase 11): isMacro returns false for enum-case synthetic methods" in pending

    // F-E-005 leaf 5 (Phase 11): isextension-regression-pin
    // Given: the kyo-data classpath loaded via TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary)
    // When: calling cp.symbols.filter(_.isExtension).filter(_.name.asString == "cyan")
    // Then: result is non-empty and the matching symbol resolves to kyo.Ansi$.cyan;
    //       this is a regression PIN (isExtension was correct before Phase 11; must stay correct after)
    // Pins: F-E-005 regression
    "F-E-005 regression PIN (Phase 11): kyo.Ansi$.cyan is still found via isExtension after Phase 11" in pending

    // F-G-002 leaf 6 (Phase 11): javametadata-present-for-mixed-class
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        every kyo-tasty class has both a .tasty and a .class file
    // When: calling cp.findClass("kyo.Tasty$").get.javaMetadata
    // Then: post-fix Present(meta) with meta.isJvmPublic == true;
    //       before fix javaMetadata was Absent because the javaMetadata merge was never triggered
    //       for Scala-compiled classes that have a matching .tasty file
    // Pins: F-G-002
    "F-G-002 (Phase 11): kyo.Tasty$.javaMetadata is Present after .class companion merge" in pending

    // INV-012 completion leaf 7 (Phase 11): sentinel-count-bounded
    // Given: the real classpath loaded via TestClasspaths.withClasspath at the Phase 11 commit
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix <= 3;
    //       before fix 11 distinct names (see SymbolIdFidelityTest for the detailed pin)
    // Pins: INV-012 completion (F-G-007)
    "INV-012 (Phase 11): SymbolId(-1) sentinel count is <= 3 after Phase 11 cleanup" in pending

end CollectionInvariantsTest
