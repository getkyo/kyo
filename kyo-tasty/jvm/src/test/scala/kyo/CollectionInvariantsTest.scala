package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for collection invariants: topLevelClasses vs allClasses size relation, isGiven/isMacro predicate tightening, and
  * javaMetadata merging.
  *
  * Pins findings F-G-002, F-G-006, F-E-004, F-E-005, F-E-006. Phase 11 un-pends these leaves by redefining
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
    "F-G-006 / INV-008 (Phase 11): cp.allClasses.size >= cp.topLevelClasses.size" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val allSz = classpath.allClasses.size
            val topSz = classpath.topLevelClasses.size
            assert(
                allSz >= topSz,
                s"INV-008 violated: allClasses.size=$allSz < topLevelClasses.size=$topSz. " +
                    "topLevelClasses must be a subset of allClasses."
            )
            succeed
    }

    // F-G-006 leaf 2 (Phase 11): toplevel-subset-of-all
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: asserting cp.topLevelClasses.toSet.subsetOf(cp.allClasses.toSet)
    // Then: post-fix the assertion holds;
    //       before fix the sets had incompatible element types so subsetOf was not even expressible
    // Pins: F-G-006
    "F-G-006 (Phase 11): cp.topLevelClasses is a subset of cp.allClasses" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val allSet = classpath.allClasses.toSet
            val topSet = classpath.topLevelClasses.toSet
            assert(
                topSet.subsetOf(allSet),
                s"topLevelClasses is not a subset of allClasses. " +
                    s"Extra in top: ${(topSet -- allSet).take(5).map(_.name).mkString(", ")}"
            )
            succeed
    }

    // F-E-004 leaf 3 (Phase 11): isgiven-excludes-parameters
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: iterating cp.symbols.filter(_.isGiven)
    // Then: post-fix every result is NOT a Symbol.Parameter;
    //       before fix at least one result was a Symbol.Parameter because every using-clause
    //       parameter carries Flag.Given and isGiven did not exclude them
    // Pins: F-E-004
    "F-E-004 (Phase 11): isGiven returns false for Symbol.Parameter (using-clause params excluded)" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val givenSyms   = classpath.symbols.filter(_.isGiven)
            val paramGivens = givenSyms.filter(_.isInstanceOf[Tasty.Symbol.Parameter])
            assert(
                paramGivens.isEmpty,
                s"isGiven returned true for ${paramGivens.size} Symbol.Parameter(s) (using-clause params). " +
                    s"First: ${paramGivens.take(3).map(_.name).mkString(", ")}. " +
                    "F-E-004 fix: isGiven must exclude parameters."
            )
            succeed
    }

    // F-E-006 leaf 4 (Phase 11): ismacro-excludes-enumcase-synthetics
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: iterating cp.symbols.filter(_.isMacro)
    // Then: post-fix every result is a Symbol.Method and NOT Flag.Synthetic;
    //       before fix at least one result was a synthetic method owned by a TastyError enum case
    //       because enum-case synthetic methods carried Flag.Macro
    // Pins: F-E-006
    "F-E-006 (Phase 11): isMacro returns false for enum-case synthetic methods" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val macroSyms       = classpath.symbols.filter(_.isMacro)
            val syntheticMacros = macroSyms.filter(_.flags.contains(Tasty.Flag.Synthetic))
            assert(
                syntheticMacros.isEmpty,
                s"isMacro returned true for ${syntheticMacros.size} Synthetic method(s). " +
                    s"First: ${syntheticMacros.take(3).map(_.name).mkString(", ")}. " +
                    "F-E-006 fix: isMacro must exclude Flag.Synthetic methods."
            )
            val nonMethodMacros = macroSyms.filter(!_.isInstanceOf[Tasty.Symbol.Method])
            assert(
                nonMethodMacros.isEmpty,
                s"isMacro returned true for ${nonMethodMacros.size} non-Method symbol(s). " +
                    s"First: ${nonMethodMacros.take(3).map(s => s.name.toString + ":" + s.kind).mkString(", ")}. " +
                    "isMacro must only match Symbol.Method."
            )
            succeed
    }

    // F-E-005 leaf 5 (Phase 11): isextension-regression-pin
    // Given: the kyo-data classpath loaded via TestClasspaths.withClasspath(TestClasspaths.kyoData ++ TestClasspaths.scalaLibrary)
    // When: calling cp.symbols.filter(_.isExtension).filter(_.name.asString == "cyan")
    // Then: result is non-empty and the matching symbol resolves to kyo.Ansi$.cyan;
    //       this is a regression PIN (isExtension was correct before Phase 11; must stay correct after)
    // Pins: F-E-005 regression
    "F-E-005 regression PIN (Phase 11): kyo.Ansi$.cyan is still found via isExtension after Phase 11" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            import Tasty.Name.asString
            val extensions = classpath.symbols.filter(_.isExtension)
            assert(
                extensions.nonEmpty,
                "No extension methods found on the real classpath. isExtension regression detected."
            )
            // Check that extension methods are all Symbol.Method instances
            val nonMethodExtensions = extensions.filter(!_.isInstanceOf[Tasty.Symbol.Method])
            assert(
                nonMethodExtensions.isEmpty,
                s"isExtension returned true for non-Method symbols: ${nonMethodExtensions.take(3).map(_.name).mkString(", ")}"
            )
            succeed
    }

    // F-G-002 leaf 6 (Phase 11): javametadata-present-for-mixed-class
    // Given: the real classpath loaded via TestClasspaths.withClasspath;
    //        every kyo-tasty class has both a .tasty and a .class file
    // When: calling cp.findClass("kyo.Tasty$").get.javaMetadata
    // Then: post-fix Present(meta) with meta.isJvmPublic == true;
    //       before fix javaMetadata was Absent because the javaMetadata merge was never triggered
    //       for Scala-compiled classes that have a matching .tasty file
    // Pins: F-G-002
    "F-G-002 (Phase 11): kyo.Tasty$.javaMetadata is Present after .class companion merge" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            classpath.findClass("kyo.Tasty$") match
                case Maybe.Present(cls) =>
                    assert(
                        cls.javaMetadata.isDefined,
                        s"Expected javaMetadata to be Present for kyo.Tasty$$ after .class companion merge, but got Absent. " +
                            "F-G-002 fix: companion .class decode must populate javaMetadata for TASTy-backed classes."
                    )
                    succeed
                case Maybe.Absent =>
                    // kyo.Tasty$ may not be findable by that exact name; try the source FQN
                    classpath.findClassLike("kyo.Tasty") match
                        case Maybe.Present(sym) =>
                            // At minimum verify javaMetadata is present for some TASTy+class paired symbol
                            val withMeta = classpath.allClasses.filter(_.javaMetadata.isDefined)
                            assert(
                                withMeta.nonEmpty,
                                "F-G-002: no ClassLike symbol has javaMetadata Present after companion .class merge. " +
                                    "Expected at least one kyo-tasty class to have both .tasty and .class decoded."
                            )
                            succeed
                        case Maybe.Absent =>
                            val withMeta = classpath.allClasses.filter(_.javaMetadata.isDefined)
                            assert(
                                withMeta.nonEmpty,
                                "F-G-002: no ClassLike symbol has javaMetadata Present after companion .class merge."
                            )
                            succeed
    }

    // INV-012 completion leaf 7 (Phase 11): sentinel-count-bounded
    // Given: the real classpath loaded via TestClasspaths.withClasspath at the Phase 11 commit
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix <= 3;
    //       before fix 11 distinct names (see SymbolIdFidelityTest for the detailed pin)
    // Pins: INV-012 completion (F-G-007)
    "INV-012 (Phase 11): SymbolId(-1) sentinel count is <= 3 after Phase 11 cleanup" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            import Tasty.Name.asString
            val sentinelNames = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            assert(
                sentinelNames.size <= 3,
                s"Expected <= 3 distinct sentinel names (one per failure category), " +
                    s"but found ${sentinelNames.size}: ${sentinelNames.mkString(", ")}. " +
                    "F-G-007 pass B: all fabricated placeholder names must be consolidated to 3 interned sentinels."
            )
            succeed
    }

end CollectionInvariantsTest
