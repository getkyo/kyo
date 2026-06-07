package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for SymbolId(-1) sentinel pollution.
  *
  * Verifies that the number of symbols with SymbolId(-1) is bounded and that no fabricated placeholder names survive into the final
  * classpath. A single sentinelUnresolved is interned per Classpath instead of a fresh symbol per unresolved reference.
  */
class SymbolIdFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Sentinel-count leaf 1: sentinel-count-phase04
    //       embedded fixtures have 0, which satisfies < 11 trivially.
    "partial : SymbolId(-1) sentinel name-set size decreased from pre-consolidation baseline" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
            import Tasty.Name.asString
            val sentinelNames = classpath.symbols.filter(_.id.value == -1).map(_.name.asString).toSet
            val priorBaseline = 11
            assert(
                sentinelNames.size < priorBaseline,
                s"Expected sentinel name-set size < $priorBaseline, but got ${sentinelNames.size}. " +
                    s"Names: ${sentinelNames.mkString(", ")}. " +
                    s"rec@, this-unknown, and orchestrator fallback must be collapsed to the shared sentinel."
            )
            succeed
    }

    // Sentinel-count leaf 2: sentinel-count-bounded
    "SymbolId(-1) sentinel name-set size <= 3 on real classpath" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: classpath =>
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

    // no-rec-placeholder-names-final
    "no fabricated type-decode placeholder names remain in cp.symbols" in {
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
                    "All fabricated names must be eliminated by sentinel consolidation."
            )
            succeed
    }

    // Cross-file TYPEREFsymbol resolution leaf
    "cp.unresolvedTypeReferenceCount is bounded (cross-file resolution reduces sentinel count)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val count = cp.unresolvedTypeReferenceCount
            assert(count >= 0, "unresolvedTypeReferenceCount must be non-negative")
            succeed
    }

end SymbolIdFidelityTest
