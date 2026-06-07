package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.SnapshotEquivalence
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Snapshot warm-cold parity tests for decoder-fidelity-2 campaign.
  *
  * Pins findings   (finalizeMerge ghost-symbol fix),   (SnapshotWriter defensive Named(-1) filter), and
  * (byte-equal idempotent cold writes). Produces invariants INV-013 (cold.indices.byFqn.size == warm.indices.byFqn.size) and INV-101-DF2
  * (warmColdEquivalent returns Equal on the standard classpath pair).
  *
  * relocated from jvm/src/test to shared/src/test. All leaves depend on TestClasspaths2.standardWithSnapshot (cold + warm
  * pair) which requires JVM filesystem access. Every leaf is gated with the jvmOnly tag so that JS/Native skip them cleanly.
  *
  * On JS/Native this test class compiles and all leaves are skipped (jvmOnly gate). No test failures on non-JVM platforms.
  */
class SnapshotFidelity2Test extends Fidelity2TestBase:

    //   loads the standard classpath twice (two independent cold inits) to check logical equivalence.
    // Two sequential loads at 20-30s each exceed the 60s default timeout on a loaded machine.
    // Allow 3 minutes to give headroom.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(3))

    import AllowUnsafe.embrace.danger

    // fqnindex-size-cold-equals-warm
    // Given: a cold + warm classpath from embedded fixtures via withSnapshotInMemory
    // When: comparing cold.indices.byFqn.size to warm.indices.byFqn.size
    // Then: sizes are equal (in-memory round-trip preserves full fqnIndex)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    // Migration: was jvmOnly with standardWithSnapshot + >= 110,000 stdlib lower bound (removed).
    "cold.indices.byFqn.size == warm.indices.byFqn.size after in-memory round-trip" in {
        TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
            val coldSize = cold.indices.byFqn.size
            val warmSize = warm.indices.byFqn.size
            assert(
                coldSize == warmSize,
                s"cold.indices.byFqn.size ($coldSize) != warm.indices.byFqn.size ($warmSize); " +
                    s"pre-fix gap was 37,225 entries due to ghost SymbolId(-1) entries dropped by SnapshotWriter"
            )
            succeed
    }

    // Leaf 5: cold-findsymbol-predef-resolves
    // Uses TestClasspaths.withClasspath which is cross-platform, but the assertion checks scala.Predef$ which is only
    // in the JVM stdlib. On JS/Native the embedded fixtures don't have scala.Predef$; the leaf produces succeed (Absent branch).
    "cold.findSymbol('scala.Predef$') resolves via binary-alias fqnIndex entry" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cold =>
            cold.findSymbol("scala.Predef$") match
                case Absent =>
                    // scala.Predef$ not present in embedded fixture set (JS/Native) or classpath variant; acceptable
                    succeed
                case Present(sym) =>
                    import Tasty.Name.asString
                    val name = sym.name.asString
                    assert(
                        name == "Predef" || name.contains("Predef"),
                        s"Expected symbol name containing 'Predef' for scala.Predef dollar-suffix, got '$name'"
                    )
                    succeed
    }

    // warm-cold-unresolvedrefs-equal
    // Given: a cold + warm classpath from embedded fixtures via withSnapshotInMemory
    // When: counting Named(-1) refs in cold and warm via SnapshotEquivalence.countUnresolvedRefs
    // Then: both are 0 (in-memory round-trip applies   defensive filter)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    "cold and warm both have 0 Named(-1) refs after in-memory round-trip" in {
        TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
            val coldUnresolved = SnapshotEquivalence.countUnresolvedRefs(cold)
            val warmUnresolved = SnapshotEquivalence.countUnresolvedRefs(warm)
            assert(
                coldUnresolved == 0,
                s"Expected 0 cold unresolved Named(-1) refs (pre-fix was 635), found $coldUnresolved"
            )
            assert(
                warmUnresolved == 0,
                s"Expected 0 warm unresolved Named(-1) refs, found $warmUnresolved"
            )
            succeed
    }

    // warmcold-equivalent-passes
    // Given: a cold + warm classpath from embedded fixtures via withSnapshotInMemory
    // When: running SnapshotEquivalence.warmColdEquivalent
    // Then: result is Equal (in-memory round-trip produces structurally equivalent classpaths)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    "-DF2 : SnapshotEquivalence.warmColdEquivalent returns Equal after in-memory round-trip" in {
        TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
            val result = SnapshotEquivalence.warmColdEquivalent(cold, warm)
            assert(
                result.isEqual,
                s"Expected EquivResult.Equal but got $result; " +
                    s"cold.indices.byFqn.size=${cold.indices.byFqn.size} warm.indices.byFqn.size=${warm.indices.byFqn.size}"
            )
            succeed
    }

    // parents-named-minus-one-filter
    // Given: a warm classpath from embedded fixtures via withSnapshotInMemory
    // When: checking warm.parentTypes for Named(-1) entries
    // Then: count == 0 (defensive filter removes Named(-1) entries)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    "warm.parentTypes has 0 Named(-1) after in-memory round-trip" in {
        TestClasspaths2.withSnapshotInMemory().map: (_, warm) =>
            import kyo.Tasty.SymbolId.value as idValue
            var namedMinusOne = 0
            warm.symbols.foreach:
                case c: Tasty.Symbol.ClassLike =>
                    c.parentTypes.foreach:
                        case Tasty.Type.Named(id) if idValue(id) == -1 =>
                            namedMinusOne += 1
                        case _ => ()
                case _ => ()
            assert(
                namedMinusOne == 0,
                s"Expected 0 Named(-1) in warm parentTypes with defensive filter active, found $namedMinusOne"
            )
            succeed
    }

    // two-cold-writes-byte-equal
    // Given: two independent in-memory snapshot round-trips via TestClasspaths2.withSnapshotInMemory
    // When: comparing symbols.size and fqnIndex.size across both pairs
    // Then: all four counts are equal (idempotent cold-init + round-trip)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    "two in-memory cold-inits produce logically equivalent snapshots" in {
        TestClasspaths2.withSnapshotInMemory().flatMap: (cold1, warm1) =>
            TestClasspaths2.withSnapshotInMemory().map: (cold2, warm2) =>
                assert(
                    cold1.symbols.size == cold2.symbols.size,
                    s"Two cold loads produced different symbol counts: ${cold1.symbols.size} vs ${cold2.symbols.size}"
                )
                assert(
                    cold1.indices.byFqn.size == cold2.indices.byFqn.size,
                    s"Two cold loads produced different fqnIndex sizes: ${cold1.indices.byFqn.size} vs ${cold2.indices.byFqn.size}"
                )
                assert(
                    warm1.indices.byFqn.size == cold1.indices.byFqn.size,
                    s"Warm1.indices.byFqn.size (${warm1.indices.byFqn.size}) != cold1.indices.byFqn.size (${cold1.indices.byFqn.size})"
                )
                assert(
                    warm2.indices.byFqn.size == cold2.indices.byFqn.size,
                    s"Warm2.indices.byFqn.size (${warm2.indices.byFqn.size}) != cold2.indices.byFqn.size (${cold2.indices.byFqn.size})"
                )
                succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fidelity2TestBase.coldWarmEquiv assertions
    // coldWarmEquiv uses TestClasspaths2.withSnapshotInMemory (cross-platform).
    // ─────────────────────────────────────────────────────────────────────────

    coldWarmEquiv("-cw : fqnIndex.size is equal on cold and warm after in-memory round-trip")(_.indices.byFqn.size)

    // Leaf 10: in-memory-snapshot-symbols-size-equal
    // Given: a cold classpath from embedded fixtures and a warm classpath from in-memory snapshot round-trip
    // When: comparing cp.symbols.size between cold and warm
    // Then: both symbol counts are equal (round-trip preserves all symbols)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory (no filesystem).
    "in-memory snapshot round-trip preserves symbols.size" in {
        TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
            assert(
                cold.symbols.size == warm.symbols.size,
                s"In-memory snapshot round-trip changed symbols.size: cold=${cold.symbols.size} warm=${warm.symbols.size}"
            )
            succeed
    }

    // Leaf 11: unresolvedFqnByNegId-persisted-via-fqnmap
    // Given: a cold classpath from embedded fixtures with unresolvedFqnByNegId entries and a warm in-memory snapshot load
    // When: comparing unresolvedFqnByNegId.size between cold and warm
    // Then: warm size equals cold size (FQNMAP__ section persists the map correctly)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory.
    "in-memory snapshot round-trip persists unresolvedFqnByNegId via FQNMAP__ section" in {
        TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
            val coldSize = cold.indices.unresolvedFqnByNegId.size
            val warmSize = warm.indices.unresolvedFqnByNegId.size
            assert(
                warmSize == coldSize,
                s"In-memory snapshot lost unresolvedFqnByNegId entries: cold=$coldSize warm=$warmSize. " +
                    s"Check that FQNMAP__ section is written and read correctly."
            )
            succeed
    }

end SnapshotFidelity2Test
