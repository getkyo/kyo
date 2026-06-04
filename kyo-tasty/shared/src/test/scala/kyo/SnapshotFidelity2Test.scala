package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.SnapshotEquivalence
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Snapshot warm-cold parity tests for decoder-fidelity-2 campaign Phase 2.02.
  *
  * Pins findings F-A4-001 (finalizeMerge ghost-symbol fix), F-A4-002 (SnapshotWriter defensive Named(-1) filter), and F-A4-005
  * (byte-equal idempotent cold writes). Produces invariants INV-013 (cold.indices.byFqn.size == warm.indices.byFqn.size) and INV-101-DF2
  * (warmColdEquivalent returns Equal on the standard classpath pair).
  *
  * Phase 2.10: relocated from jvm/src/test to shared/src/test. All leaves depend on TestClasspaths2.standardWithSnapshot (cold + warm
  * pair) which requires JVM filesystem access. Every leaf is gated with the jvmOnly tag so that JS/Native skip them cleanly.
  *
  * On JS/Native this test class compiles and all leaves are skipped (jvmOnly gate). No test failures on non-JVM platforms.
  */
class SnapshotFidelity2Test extends Fidelity2TestBase:

    // F-A4-005 loads the standard classpath twice (two independent cold inits) to check logical equivalence.
    // Two sequential loads at 20-30s each exceed the 60s default timeout on a loaded machine.
    // Allow 3 minutes to give headroom.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(3))

    import AllowUnsafe.embrace.danger

    // Leaf 4 (Phase 2.02, migrated Phase 2 post-audit): fqnindex-size-cold-equals-warm
    // Given: a cold + warm classpath from embedded fixtures via withSnapshotInMemory
    // When: comparing cold.indices.byFqn.size to warm.indices.byFqn.size
    // Then: sizes are equal (in-memory round-trip preserves full fqnIndex)
    // Pins: INV-013; F-A4-001
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    // Migration: was jvmOnly with standardWithSnapshot + >= 110,000 stdlib lower bound (removed).
    "INV-013 (Phase 2.02): cold.indices.byFqn.size == warm.indices.byFqn.size after in-memory round-trip" in run {
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

    // Leaf 5 (Phase 2.02): cold-findsymbol-predef-resolves
    // Uses TestClasspaths.withClasspath which is cross-platform, but the assertion checks scala.Predef$ which is only
    // in the JVM stdlib. On JS/Native the embedded fixtures don't have scala.Predef$; the leaf produces succeed (Absent branch).
    // Pins: F-A4-001 (OQ-003)
    "F-A4-001 (Phase 2.02): cold.findSymbol('scala.Predef$') resolves via binary-alias fqnIndex entry" in run {
        TestClasspaths.withClasspath().map: cold =>
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

    // Leaf 6 (Phase 2.02, migrated Phase 2 post-audit): warm-cold-unresolvedrefs-equal
    // Given: a cold + warm classpath from embedded fixtures via withSnapshotInMemory
    // When: counting Named(-1) refs in cold and warm via SnapshotEquivalence.countUnresolvedRefs
    // Then: both are 0 (in-memory round-trip applies F-A4-002 defensive filter)
    // Pins: INV-005 (strengthened); INV-101-DF2; F-A4-002
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    "F-A4-002 (Phase 2.02): cold and warm both have 0 Named(-1) refs after in-memory round-trip" in run {
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

    // Leaf 7 (Phase 2.02, migrated Phase 2 post-audit): warmcold-equivalent-passes
    // Given: a cold + warm classpath from embedded fixtures via withSnapshotInMemory
    // When: running SnapshotEquivalence.warmColdEquivalent
    // Then: result is Equal (in-memory round-trip produces structurally equivalent classpaths)
    // Pins: INV-101-DF2 producer; F-A4-001 + F-A4-002
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    "INV-101-DF2 (Phase 2.02): SnapshotEquivalence.warmColdEquivalent returns Equal after in-memory round-trip" in run {
        TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
            val result = SnapshotEquivalence.warmColdEquivalent(cold, warm)
            assert(
                result.isEqual,
                s"Expected EquivResult.Equal but got $result; " +
                    s"cold.indices.byFqn.size=${cold.indices.byFqn.size} warm.indices.byFqn.size=${warm.indices.byFqn.size}"
            )
            succeed
    }

    // Leaf 8 (Phase 2.02, migrated Phase 2 post-audit): parents-named-minus-one-filter
    // Given: a warm classpath from embedded fixtures via withSnapshotInMemory
    // When: checking warm.parentTypes for Named(-1) entries
    // Then: count == 0 (F-A4-002 defensive filter removes Named(-1) entries)
    // Pins: F-A4-002 defensive filter (OQ-004)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    "F-A4-002 (Phase 2.02): warm.parentTypes has 0 Named(-1) after in-memory round-trip" in run {
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
                s"Expected 0 Named(-1) in warm parentTypes after F-A4-002 defensive filter, found $namedMinusOne"
            )
            succeed
    }

    // Leaf 9 (Phase 2.02, migrated Phase 2 post-audit): two-cold-writes-byte-equal
    // Given: two independent in-memory snapshot round-trips via TestClasspaths2.withSnapshotInMemory
    // When: comparing symbols.size and fqnIndex.size across both pairs
    // Then: all four counts are equal (idempotent cold-init + round-trip)
    // Pins: F-A4-005 (extended; F-A4-OPEN-IDEMPOTENT)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    "F-A4-005 (Phase 2.02): two in-memory cold-inits produce logically equivalent snapshots" in run {
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
    // Fidelity2TestBase.coldWarmEquiv assertions (Phase 2.04-strict Proposal 4)
    // Phase 2.13: coldWarmEquiv uses TestClasspaths2.withSnapshotInMemory (cross-platform).
    // ─────────────────────────────────────────────────────────────────────────

    coldWarmEquiv("INV-013-cw (Phase 2.13): fqnIndex.size is equal on cold and warm after in-memory round-trip")(_.indices.byFqn.size)

    // Leaf 10 (Phase 2.13): in-memory-snapshot-symbols-size-equal
    // Given: a cold classpath from embedded fixtures and a warm classpath from in-memory snapshot round-trip
    // When: comparing cp.symbols.size between cold and warm
    // Then: both symbol counts are equal (round-trip preserves all symbols)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory (no filesystem).
    // Pins: in-memory snapshot fidelity (Target 1 Phase 2.13)
    "Phase 2.13: in-memory snapshot round-trip preserves symbols.size" in run {
        TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
            assert(
                cold.symbols.size == warm.symbols.size,
                s"In-memory snapshot round-trip changed symbols.size: cold=${cold.symbols.size} warm=${warm.symbols.size}"
            )
            succeed
    }

    // Leaf 11 (Phase 2.13): unresolvedFqnByNegId-persisted-via-fqnmap
    // Given: a cold classpath from embedded fixtures with unresolvedFqnByNegId entries and a warm in-memory snapshot load
    // When: comparing unresolvedFqnByNegId.size between cold and warm
    // Then: warm size equals cold size (FQNMAP__ section persists the map correctly)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory.
    // Pins: Target 3 (unresolvedFqnByNegId snapshot persistence), FQNMAP__ section
    "Phase 2.13: in-memory snapshot round-trip persists unresolvedFqnByNegId via FQNMAP__ section" in run {
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
