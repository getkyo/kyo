package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for collection invariants: topLevelClasses vs allClassLike size relation,
  * isGiven/isMacro predicate correctness, and javaMetadata merging via companion .class files.
  */
class CollectionInvariantsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

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

    // kyo.fixtures.Meters in FixtureClasses$package has `extension (m: Meters) def value: Double`.
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
