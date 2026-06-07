package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for collection invariants: topLevelClasses vs allClassLike size relation,
  * isGiven/isMacro predicate tightening, and javaMetadata merging.
  *
  * `topLevelClasses` and `allClassLike` return `Chunk[Symbol.ClassLike]`; `isGiven`/`isMacro` are
  * tightened; javaMetadata is merged when both tasty and .class companions exist.
  *
  * The cross-platform tests assert universal invariants that hold on any classpath (embedded or
  * stdlib); they run on JS/Native against the embedded fixture set. A few cases remain jvmOnly
  * because they require kyo-data (isExtension/kyo.Ansi$.cyan), companion .class javaMetadata
  * merging, or the large stdlib sentinel-count baseline.
  */
class CollectionInvariantsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    //   all-superset-of-toplevel
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: asserting cp.allClassLike.size >= cp.topLevelClasses.size
    // Then: the assertion holds;
    //       before fix cp.topLevelClasses.size == 3,514 and cp.allClassLike.size == 1,508
    // Cross-platform: invariant holds for any classpath size.
    "cp.allClassLike.size >= cp.topLevelClasses.size" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val allSz = classpath.allClassLike.size
            val topSz = classpath.topLevelClasses.size
            assert(
                allSz >= topSz,
                s"violated: allClassLike.size=$allSz < topLevelClasses.size=$topSz. " +
                    "topLevelClasses must be a subset of allClassLike."
            )
            succeed
    }

    //   toplevel-subset-of-all
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: asserting cp.topLevelClasses.toSet.subsetOf(cp.allClassLike.toSet)
    // Then: the assertion holds
    // Cross-platform: subset invariant holds for any classpath.
    "cp.topLevelClasses is a subset of cp.allClassLike" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val allSet = classpath.allClassLike.toSet
            val topSet = classpath.topLevelClasses.toSet
            assert(
                topSet.subsetOf(allSet),
                s"topLevelClasses is not a subset of allClassLike. " +
                    s"Extra in top: ${(topSet -- allSet).take(5).map(_.name).mkString(", ")}"
            )
            succeed
    }

    //   isgiven-excludes-parameters
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: iterating cp.symbols.filter(_.isGiven)
    // Then: every result is NOT a Symbol.Parameter
    // Cross-platform: invariant "isGiven excludes parameters" holds vacuously when no given symbols exist in embedded
    // fixtures, and holds by construction on JVM. Either way the assertion passes on all platforms.
    "isGiven returns false for Symbol.Parameter (using-clause params excluded)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val givenSyms   = classpath.symbols.filter(_.isGiven)
            val paramGivens = givenSyms.filter(_.isInstanceOf[Tasty.Symbol.Parameter])
            assert(
                paramGivens.isEmpty,
                s"isGiven returned true for ${paramGivens.size} Symbol.Parameter(s) (using-clause params). " +
                    s"First: ${paramGivens.take(3).map(_.name).mkString(", ")}. " +
                    "fix: isGiven must exclude parameters."
            )
            succeed
    }

    //   ismacro-excludes-enumcase-synthetics
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib; JS/Native: embedded fixtures)
    // When: iterating cp.symbols.filter(_.isMacro)
    // Then: every result is a Symbol.Method and NOT Flag.Synthetic
    // Cross-platform: invariant "isMacro excludes synthetic" holds for any classpath. On JVM it covers stdlib enum-case
    // synthetics; on JS/Native the Color/Shape enum-case synthetics from embedded fixtures exercise the same predicate.
    "isMacro returns false for enum-case synthetic methods" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val macroSyms       = classpath.symbols.filter(_.isMacro)
            val syntheticMacros = macroSyms.filter(_.flags.contains(Tasty.Flag.Synthetic))
            assert(
                syntheticMacros.isEmpty,
                s"isMacro returned true for ${syntheticMacros.size} Synthetic method(s). " +
                    s"First: ${syntheticMacros.take(3).map(_.name).mkString(", ")}. " +
                    "fix: isMacro must exclude Flag.Synthetic methods."
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

    //   isextension-regression-pin
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.symbols.filter(_.isExtension) to find extension methods
    // Then: at least one extension method is found (kyo.fixtures.Meters.value is defined as an extension)
    //       and all results are Symbol.Method instances
    // Cross-platform: kyo.fixtures.Meters in FixtureClasses$package has `extension (m: Meters) def value: Double`.
    "regression PIN : extension methods are found via isExtension on embedded fixtures" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
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

    //   javametadata-present-for-mixed-class
    // Given: a classpath loaded via TestClasspaths.withClasspath; at least one class has both a.tasty and a
    //        companion.class file (JVM: every kyo-tasty class on the real classpath; JS/Native: PlainClass via
    //        the embedded.class fixture added alongside PlainClass.tasty).
    // When: scanning cp.allClassLike for any symbol with javaMetadata defined.
    // Then: at least one ClassLike has javaMetadata Present (companion.class decode populated it).
    // Cross-platform: Embedded.plainClassClassfile is registered as "root/PlainClass.class" alongside
    //   "root/PlainClass.tasty" in JS/Native TestClasspaths; ClasspathOrchestrator.readAndDecodeTastyFile
    //   speculatively reads the companion via source.exists/source.read and merges javaMetadata.
    "at least one class has javaMetadata Present after .class companion merge" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            val withMeta = classpath.allClassLike.filter(_.javaMetadata.isDefined)
            assert(
                withMeta.nonEmpty,
                "no ClassLike symbol has javaMetadata Present after companion .class merge. " +
                    "Expected at least one class to have both .tasty and .class decoded (PlainClass on JS/Native, " +
                    "any kyo-tasty class on JVM)."
            )
            succeed
    }

    // completion leaf 7: sentinel-count-bounded
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: computing cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size
    // Then: <= 3
    // Cross-platform: the sentinel-count invariant holds for any correctly-decoded classpath; embedded fixtures produce 0 or 1 sentinel names.
    "SymbolId(-1) sentinel count is <= 3" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            import Tasty.Name.asString
            val sentinelNames = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            assert(
                sentinelNames.size <= 3,
                s"Expected <= 3 distinct sentinel names (one per failure category), " +
                    s"but found ${sentinelNames.size}: ${sentinelNames.mkString(", ")}. " +
                    "pass B: all fabricated placeholder names must be consolidated to 3 interned sentinels."
            )
            succeed
    }

end CollectionInvariantsTest
