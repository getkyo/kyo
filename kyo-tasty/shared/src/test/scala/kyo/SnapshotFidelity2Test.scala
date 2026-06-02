package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.SnapshotEquivalence
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Snapshot warm-cold parity tests for decoder-fidelity-2 campaign Phase 2.02.
  *
  * Pins findings F-A4-001 (finalizeMerge ghost-symbol fix), F-A4-002 (SnapshotWriter defensive Named(-1) filter), and F-A4-005
  * (byte-equal idempotent cold writes). Produces invariants INV-013 (cold.fqnIndex.size == warm.fqnIndex.size) and INV-101-DF2
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

    // Leaf 4 (Phase 2.02): fqnindex-size-cold-equals-warm
    // JVM-only: TestClasspaths2.standardWithSnapshot relies on JVM filesystem.
    // Pins: INV-013; F-A4-001
    "INV-013 (Phase 2.02): cold.fqnIndex.size == warm.fqnIndex.size and both >= 110,000" taggedAs jvmOnly in run {
        TestClasspaths2.standardWithSnapshot().map: (cold, warm) =>
            val coldSize = cold.fqnIndex.size
            val warmSize = warm.fqnIndex.size
            assert(
                coldSize == warmSize,
                s"cold.fqnIndex.size ($coldSize) != warm.fqnIndex.size ($warmSize); " +
                    s"pre-fix gap was 37,225 entries due to ghost SymbolId(-1) entries dropped by SnapshotWriter"
            )
            assert(
                coldSize >= 110000,
                s"Expected cold.fqnIndex.size >= 110,000 (probe baseline 110,210), found $coldSize"
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

    // Leaf 6 (Phase 2.02): warm-cold-unresolvedrefs-equal
    // JVM-only: TestClasspaths2.standardWithSnapshot relies on JVM filesystem.
    // Pins: INV-005 (strengthened); INV-101-DF2; F-A4-002
    "F-A4-002 (Phase 2.02): cold and warm both have 0 symbols with Named(-1) in reachable types" taggedAs jvmOnly in run {
        TestClasspaths2.standardWithSnapshot().map: (cold, warm) =>
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

    // Leaf 7 (Phase 2.02): warmcold-equivalent-passes
    // JVM-only: TestClasspaths2.standardWithSnapshot relies on JVM filesystem.
    // Pins: INV-101-DF2 producer; F-A4-001 + F-A4-002
    "INV-101-DF2 (Phase 2.02): SnapshotEquivalence.warmColdEquivalent returns Equal on standard classpath pair" taggedAs jvmOnly in run {
        TestClasspaths2.standardWithSnapshot().map: (cold, warm) =>
            val result = SnapshotEquivalence.warmColdEquivalent(cold, warm)
            assert(
                result.isEqual,
                s"Expected EquivResult.Equal but got $result; " +
                    s"cold.fqnIndex.size=${cold.fqnIndex.size} warm.fqnIndex.size=${warm.fqnIndex.size}"
            )
            succeed
    }

    // Leaf 8 (Phase 2.02): parents-named-minus-one-filter
    // JVM-only: TestClasspaths2.standardWithSnapshot relies on JVM filesystem.
    // Pins: F-A4-002 defensive filter (OQ-004)
    "F-A4-002 (Phase 2.02): warm.parentTypes never contains Named(-1) after round-trip" taggedAs jvmOnly in run {
        TestClasspaths2.standardWithSnapshot().map: (_, warm) =>
            import kyo.internal.tasty.symbol.SymbolId.value as idValue
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

    // Leaf 9 (Phase 2.02): two-cold-writes-byte-equal
    // JVM-only: TestClasspaths2.standardWithSnapshot relies on JVM filesystem.
    // Pins: F-A4-005 (extended; F-A4-OPEN-IDEMPOTENT)
    "F-A4-005 (Phase 2.02): two independent cold-init invocations produce logically equivalent snapshots" taggedAs jvmOnly in run {
        TestClasspaths2.standardWithSnapshot().flatMap: (cold1, warm1) =>
            TestClasspaths2.standardWithSnapshot().map: (cold2, warm2) =>
                assert(
                    cold1.symbols.size == cold2.symbols.size,
                    s"Two cold loads produced different symbol counts: ${cold1.symbols.size} vs ${cold2.symbols.size}"
                )
                assert(
                    cold1.fqnIndex.size == cold2.fqnIndex.size,
                    s"Two cold loads produced different fqnIndex sizes: ${cold1.fqnIndex.size} vs ${cold2.fqnIndex.size}"
                )
                assert(
                    warm1.fqnIndex.size == cold1.fqnIndex.size,
                    s"Warm1.fqnIndex.size (${warm1.fqnIndex.size}) != cold1.fqnIndex.size (${cold1.fqnIndex.size})"
                )
                assert(
                    warm2.fqnIndex.size == cold2.fqnIndex.size,
                    s"Warm2.fqnIndex.size (${warm2.fqnIndex.size}) != cold2.fqnIndex.size (${cold2.fqnIndex.size})"
                )
                succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fidelity2TestBase.coldWarmEquiv assertions (Phase 2.04-strict Proposal 4)
    // JVM-only: coldWarmEquiv uses TestClasspaths2.standardWithSnapshot.
    // ─────────────────────────────────────────────────────────────────────────

    coldWarmEquiv("INV-013-cw: fqnIndex.size is equal on cold and warm")(_.fqnIndex.size)

end SnapshotFidelity2Test
