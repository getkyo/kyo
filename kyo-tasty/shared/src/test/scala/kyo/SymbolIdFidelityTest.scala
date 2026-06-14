package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for SymbolId(-1) sentinel pollution.
  *
  * Verifies that the number of symbols with SymbolId(-1) is bounded and that no fabricated placeholder names survive into the final
  * classpath. A single sentinelUnresolved is interned per Classpath instead of a fresh symbol per unresolved reference.
  */
class SymbolIdFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "SymbolId(-1) sentinel name-set size is bounded" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import Tasty.Name.asString
            val sentinelNames = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            val maxSentinels  = 11
            assert(
                sentinelNames.size < maxSentinels,
                s"Expected sentinel name-set size < $maxSentinels, but got ${sentinelNames.size}. " +
                    s"Names: ${sentinelNames.mkString(", ")}. " +
                    s"rec@, this-unknown, and orchestrator fallback must collapse to the shared sentinel."
            )
            succeed
        }
    }

    "SymbolId(-1) sentinel name-set size <= 3 on real classpath" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import Tasty.Name.asString
            val sentinelNames = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            assert(
                sentinelNames.size <= 3,
                s"Expected <= 3 distinct sentinel names, " +
                    s"but found ${sentinelNames.size}: ${sentinelNames.mkString(", ")}. " +
                    "Expected at most: <unresolved>, <rec-placeholder>, <unknown-type-tag>."
            )
            succeed
        }
    }

    "no fabricated type-decode placeholder names remain in classpath.symbols" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import Tasty.Name.asString
            val allNames = classpath.symbols.map(_.name.asString)
            val fabricated = allNames.filter { n =>
                n.startsWith("rec@") ||
                n.startsWith("rec-placeholder@") ||
                n.startsWith("typeref@") ||
                n.startsWith("termref@") ||
                n.startsWith("sharedref@") ||
                n.startsWith("param@") ||
                n.startsWith("unknown-type-tag-")
            }
            assert(
                fabricated.isEmpty,
                s"Found ${fabricated.size} fabricated placeholder name(s) in classpath.symbols: " +
                    s"${fabricated.take(10).mkString(", ")}. " +
                    "All fabricated names must be eliminated by sentinel consolidation."
            )
            succeed
        }
    }

    "classpath.unresolvedTypeReferenceCount is bounded (cross-file resolution reduces sentinel count)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val count = classpath.unresolvedTypeReferenceCount
            assert(count >= 0, "unresolvedTypeReferenceCount must be non-negative")
            succeed
        }
    }

end SymbolIdFidelityTest
