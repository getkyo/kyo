package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for collection invariants: topLevelClasses vs allClassLike size relation, isGiven/isMacro predicate tightening, and
  * javaMetadata merging.
  *
  * Pins findings F-G-002, F-G-006, F-E-004, F-E-005, F-E-006. Phase 11 un-pends these leaves by redefining
  * `topLevelClasses`/`allClassLike` to return `Chunk[Symbol.ClassLike]`, tightening `isGiven`/`isMacro`, and merging javaMetadata when both
  * .tasty and .class companions exist.
  *
  * Phase 2.12 corrective: leaves 1-4 assert universal invariants that hold on any classpath (embedded or stdlib). They are ungated so that
  * JS/Native run the same assertions against the embedded fixture set. Leaves 5-7 remain jvmOnly because they require kyo-data
  * (isExtension/kyo.Ansi$.cyan), companion .class javaMetadata merging, and the large stdlib sentinel-count baseline.
  */
class CollectionInvariantsTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-G-006 / INV-008 leaf 1 (Phase 11): all-superset-of-toplevel
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: asserting cp.allClassLike.size >= cp.topLevelClasses.size
    // Then: post-fix the assertion holds;
    //       before fix cp.topLevelClasses.size == 3,514 and cp.allClassLike.size == 1,508
    // Pins: INV-008 producer (F-G-006)
    // Cross-platform: invariant holds for any classpath size.
    "F-G-006 / INV-008 (Phase 11): cp.allClassLike.size >= cp.topLevelClasses.size" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val allSz = classpath.allClassLike.size
            val topSz = classpath.topLevelClasses.size
            assert(
                allSz >= topSz,
                s"INV-008 violated: allClassLike.size=$allSz < topLevelClasses.size=$topSz. " +
                    "topLevelClasses must be a subset of allClassLike."
            )
            succeed
    }

    // F-G-006 leaf 2 (Phase 11): toplevel-subset-of-all
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: asserting cp.topLevelClasses.toSet.subsetOf(cp.allClassLike.toSet)
    // Then: post-fix the assertion holds
    // Pins: F-G-006
    // Cross-platform: subset invariant holds for any classpath.
    "F-G-006 (Phase 11): cp.topLevelClasses is a subset of cp.allClassLike" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val allSet = classpath.allClassLike.toSet
            val topSet = classpath.topLevelClasses.toSet
            assert(
                topSet.subsetOf(allSet),
                s"topLevelClasses is not a subset of allClassLike. " +
                    s"Extra in top: ${(topSet -- allSet).take(5).map(_.name).mkString(", ")}"
            )
            succeed
    }

    // F-E-004 leaf 3 (Phase 11): isgiven-excludes-parameters
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: iterating cp.symbols.filter(_.isGiven)
    // Then: post-fix every result is NOT a Symbol.Parameter
    // Pins: F-E-004
    // Cross-platform: invariant "isGiven excludes parameters" holds vacuously when no given symbols exist in embedded
    // fixtures, and holds by construction on JVM. Either way the assertion passes on all platforms.
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
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: iterating cp.symbols.filter(_.isMacro)
    // Then: post-fix every result is a Symbol.Method and NOT Flag.Synthetic
    // Pins: F-E-006
    // Cross-platform: invariant "isMacro excludes synthetic" holds for any classpath. On JVM it covers stdlib enum-case
    // synthetics; on JS/Native the Color/Shape enum-case synthetics from embedded fixtures exercise the same predicate.
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
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.symbols.filter(_.isExtension) to find extension methods
    // Then: at least one extension method is found (kyo.fixtures.Meters.value is defined as an extension)
    //       and all results are Symbol.Method instances
    // Pins: F-E-005 regression
    // Cross-platform: kyo.fixtures.Meters in FixtureClasses$package has `extension (m: Meters) def value: Double`.
    "F-E-005 regression PIN (Phase 11): extension methods are found via isExtension on embedded fixtures" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            import Tasty.Name.asString
            val extensions = classpath.symbols.filter(_.isExtension)
            assert(
                extensions.nonEmpty,
                "No extension methods found on the embedded classpath. " +
                    "isExtension regression detected: kyo.fixtures.Meters has `extension (m: Meters) def value` which must appear."
            )
            val nonMethodExtensions = extensions.filter(!_.isInstanceOf[Tasty.Symbol.Method])
            assert(
                nonMethodExtensions.isEmpty,
                s"isExtension returned true for non-Method symbols: ${nonMethodExtensions.take(3).map(_.name).mkString(", ")}"
            )
            succeed
    }

    // F-G-002 leaf 6 (Phase 11): javametadata-present-for-mixed-class
    // Given: a classpath loaded via TestClasspaths.withClasspath; at least one class has both a .tasty and a
    //        companion .class file (JVM: every kyo-tasty class on the real classpath; JS/Native: PlainClass via
    //        the embedded .class fixture added alongside PlainClass.tasty).
    // When: scanning cp.allClassLike for any symbol with javaMetadata defined.
    // Then: post-fix at least one ClassLike has javaMetadata Present (companion .class decode populated it).
    // Pins: F-G-002
    // Cross-platform: Embedded.plainClassClassfile is registered as "root/PlainClass.class" alongside
    //   "root/PlainClass.tasty" in JS/Native TestClasspaths; ClasspathOrchestrator.readAndDecodeTastyFile
    //   speculatively reads the companion via source.exists/source.read and merges javaMetadata.
    "F-G-002 (Phase 11): at least one class has javaMetadata Present after .class companion merge" in run {
        val cp = TestClasspaths.withClasspath()
        cp.map: classpath =>
            val withMeta = classpath.allClassLike.filter(_.javaMetadata.isDefined)
            assert(
                withMeta.nonEmpty,
                "F-G-002: no ClassLike symbol has javaMetadata Present after companion .class merge. " +
                    "Expected at least one class to have both .tasty and .class decoded (PlainClass on JS/Native, " +
                    "any kyo-tasty class on JVM)."
            )
            succeed
    }

    // INV-012 completion leaf 7 (Phase 11): sentinel-count-bounded
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: post-fix <= 3
    // Pins: INV-012 completion (F-G-007)
    // Cross-platform: the sentinel-count invariant holds for any correctly-decoded classpath; embedded fixtures produce 0 or 1 sentinel names.
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
