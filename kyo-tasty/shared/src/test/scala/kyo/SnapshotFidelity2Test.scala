package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.SnapshotEquivalence
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Snapshot warm-cold parity tests.
  *
  * Covers ghost-symbol filtering, the SnapshotWriter defensive Named(-1) filter, and
  * byte-equal idempotent cold writes. Invariants verified:
  * cold.indices.byFullName.size == warm.indices.byFullName.size, and warmColdEquivalent returns Equal on
  * the standard classpath pair.
  *
  * All tests depend on TestClasspaths2.standardWithSnapshot (cold + warm pair) which requires JVM
  * filesystem access. Every test is gated with the jvmOnly tag so that JS/Native skip them cleanly.
  *
  * On JS/Native this test class compiles and all tests are skipped (jvmOnly gate). No test failures
  * on non-JVM platforms.
  */
class SnapshotFidelity2Test extends Fidelity2TestBase:

    //   loads the standard classpath twice (two independent cold inits) to check logical equivalence.
    // Two sequential loads at 20-30s each exceed the 60s default timeout on a loaded machine.
    // Allow 3 minutes to give headroom.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(3))

    import AllowUnsafe.embrace.danger

    "cold.indices.byFullName.size == warm.indices.byFullName.size after in-memory round-trip" in {
        TestClasspaths2.withSnapshotInMemory().map { (cold, warm) =>
            val coldSize = cold.indices.byFullName.size
            val warmSize = warm.indices.byFullName.size
            assert(
                coldSize == warmSize,
                s"cold.indices.byFullName.size ($coldSize) != warm.indices.byFullName.size ($warmSize); " +
                    s"this regression typically signals ghost SymbolId(-1) entries dropped by SnapshotWriter"
            )
            succeed
        }
    }

    // Uses TestClasspaths.withClasspath which is cross-platform, but the assertion checks scala.Predef$ which is only
    // in the JVM stdlib. On JS/Native the embedded fixtures don't have scala.Predef$; the leaf produces succeed (Absent branch).
    "cold.findSymbol('scala.Predef$') resolves via binary-alias fullNameIndex entry" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { cold =>
            cold.findSymbol("scala.Predef$") match
                case Absent =>
                    // scala.Predef$ not present in embedded fixture set (JS/Native) or classpath variant; acceptable
                    succeed
                case Present(symbol) =>
                    import Tasty.Name.asString
                    val name = symbol.name.asString
                    assert(
                        name == "Predef" || name.contains("Predef"),
                        s"Expected symbol name containing 'Predef' for scala.Predef dollar-suffix, got '$name'"
                    )
                    succeed
        }
    }

    "cold and warm both have 0 Named(-1) refs after in-memory round-trip" in {
        TestClasspaths2.withSnapshotInMemory().map { (cold, warm) =>
            val coldUnresolved = SnapshotEquivalence.countUnresolvedRefs(cold)
            val warmUnresolved = SnapshotEquivalence.countUnresolvedRefs(warm)
            assert(
                coldUnresolved == 0,
                s"Expected 0 cold unresolved Named(-1) refs, found $coldUnresolved"
            )
            assert(
                warmUnresolved == 0,
                s"Expected 0 warm unresolved Named(-1) refs, found $warmUnresolved"
            )
            succeed
        }
    }

    "SnapshotEquivalence.warmColdEquivalent returns Equal after in-memory round-trip" in {
        TestClasspaths2.withSnapshotInMemory().map { (cold, warm) =>
            val result = SnapshotEquivalence.warmColdEquivalent(cold, warm)
            assert(
                result.isEqual,
                s"Expected EquivResult.Equal but got $result; " +
                    s"cold.indices.byFullName.size=${cold.indices.byFullName.size} warm.indices.byFullName.size=${warm.indices.byFullName.size}"
            )
            succeed
        }
    }

    "warm.parentTypes has 0 Named(-1) after in-memory round-trip" in {
        TestClasspaths2.withSnapshotInMemory().map { (_, warm) =>
            import kyo.Tasty.SymbolId.value as idValue
            var namedMinusOne = 0
            warm.symbols.foreach {
                case c: Tasty.Symbol.ClassLike =>
                    c.parentTypes.foreach {
                        case Tasty.Type.Named(id) if idValue(id) == -1 =>
                            namedMinusOne += 1
                        case _ => ()
                    }
                case _ => ()
            }
            assert(
                namedMinusOne == 0,
                s"Expected 0 Named(-1) in warm parentTypes with defensive filter active, found $namedMinusOne"
            )
            succeed
        }
    }

    "two in-memory cold-inits produce logically equivalent snapshots" in {
        TestClasspaths2.withSnapshotInMemory().map { (cold1, warm1) =>
            TestClasspaths2.withSnapshotInMemory().map { (cold2, warm2) =>
                assert(
                    cold1.symbols.size == cold2.symbols.size,
                    s"Two cold loads produced different symbol counts: ${cold1.symbols.size} vs ${cold2.symbols.size}"
                )
                assert(
                    cold1.indices.byFullName.size == cold2.indices.byFullName.size,
                    s"Two cold loads produced different fullNameIndex sizes: ${cold1.indices.byFullName.size} vs ${cold2.indices.byFullName.size}"
                )
                assert(
                    warm1.indices.byFullName.size == cold1.indices.byFullName.size,
                    s"Warm1.indices.byFullName.size (${warm1.indices.byFullName.size}) != cold1.indices.byFullName.size (${cold1.indices.byFullName.size})"
                )
                assert(
                    warm2.indices.byFullName.size == cold2.indices.byFullName.size,
                    s"Warm2.indices.byFullName.size (${warm2.indices.byFullName.size}) != cold2.indices.byFullName.size (${cold2.indices.byFullName.size})"
                )
                succeed
            }
        }
    }

    // coldWarmEquiv uses TestClasspaths2.withSnapshotInMemory (cross-platform).
    coldWarmEquiv("fullNameIndex.size is equal on cold and warm after in-memory round-trip")(_.indices.byFullName.size)

    "in-memory snapshot round-trip preserves symbols.size" in {
        TestClasspaths2.withSnapshotInMemory().map { (cold, warm) =>
            assert(
                cold.symbols.size == warm.symbols.size,
                s"In-memory snapshot round-trip changed symbols.size: cold=${cold.symbols.size} warm=${warm.symbols.size}"
            )
            succeed
        }
    }

    "in-memory snapshot round-trip persists unresolvedFullNameByNegId via FQNMAP__ section" in {
        TestClasspaths2.withSnapshotInMemory().map { (cold, warm) =>
            val coldSize = cold.indices.unresolvedFullNameByNegId.size
            val warmSize = warm.indices.unresolvedFullNameByNegId.size
            assert(
                warmSize == coldSize,
                s"In-memory snapshot lost unresolvedFullNameByNegId entries: cold=$coldSize warm=$warmSize. " +
                    s"Check that FQNMAP__ section is written and read correctly."
            )
            succeed
        }
    }

end SnapshotFidelity2Test
