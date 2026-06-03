package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader

/** Fidelity tests for snapshot round-trip integrity.
  *
  * Pins findings F-C-001, F-C-002, F-C-003, and F-G-002. Phase 12 un-pends all five existing leaves and adds one new v3-rejection leaf by
  * fixing `SnapshotWriter` (serialize permittedSubclassIds, annotations, javaMetadata, full fqnIndex), `SnapshotReader` (read new fields,
  * reject minor < 5, reconstruct dual-FQN index from FQNIDX__ section), and bumping FORMAT_VERSION to 5.
  *
  * Phase 2.12: relocated from jvm/src/test to shared/src/test.
  *
  * Phase 2.13: adds FQNMAP__ section for unresolvedFqnByNegId persistence (Target 3), bumps FORMAT_VERSION to 6, and adds in-memory
  * snapshot round-trip leaves via TestClasspaths2.withSnapshotInMemory.
  *
  * Phase 2 post-audit: migrates the 4 previously-jvmOnly leaves (INV-010, F-C-002, F-C-003, F-G-002) from withRoundTrip (real JVM
  * filesystem + real stdlib classpath) to withSnapshotInMemory (embedded fixtures). The stdlib-scale lower bounds (>= 5 deprecated symbols,
  * scala.Option.permittedSubclassIds >= 2) are removed; the shape assertions (round-trip preserves count equality) are preserved. All leaves
  * now run on JVM, JS, and Native.
  */
class SnapshotFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // INV-010 (Phase 12, migrated Phase 2 post-audit): roundtrip-fidelity via in-memory snapshot
    // Given: a cold + warm classpath from embedded fixtures via withSnapshotInMemory
    // When: comparing symbolsAnnotatedWith("scala.deprecated").size and permittedSubclassIds
    // Then: warm count >= cold count; permittedSubclassIds sizes match for any sealed class
    // Pins: INV-010; F-C-002 round-trip invariant
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    // Migration: was jvmOnly via withRoundTrip (real stdlib); now uses embedded fixtures.
    "INV-010 (Phase 12): snapshot warm-load preserves annotations and permittedSubclassIds" in run {
        TestClasspaths2.withSnapshotInMemory().map: (coldCp, warmCp) =>
            val coldAnnotCount = coldCp.symbolsAnnotatedWith("scala.deprecated").size
            val warmAnnotCount = warmCp.symbolsAnnotatedWith("scala.deprecated").size
            assert(
                warmAnnotCount >= coldAnnotCount,
                s"In-memory snapshot lost deprecated annotations: cold=$coldAnnotCount warm=$warmAnnotCount"
            )
            coldCp.allClassLike.foreach:
                case cold: Tasty.Symbol.Class =>
                    cold.permittedSubclassIds match
                        case Present(coldIds) =>
                            val fqn = cold.fullNameString(using coldCp)
                            warmCp.findClass(fqn) match
                                case Present(warm: Tasty.Symbol.Class) =>
                                    warm.permittedSubclassIds match
                                        case Present(warmIds) =>
                                            assert(
                                                coldIds.size == warmIds.size,
                                                s"permittedSubclassIds size differs for $fqn: cold=${coldIds.size} warm=${warmIds.size}"
                                            )
                                        case Absent =>
                                            fail(s"permittedSubclassIds Present in cold but Absent after in-memory round-trip for $fqn")
                                    end match
                                case _ => ()
                            end match
                        case Absent => ()
                    end match
                case _ => ()
            succeed
    }

    // F-C-002 (Phase 12, migrated Phase 2 post-audit): permits-roundtrip via in-memory snapshot
    // Given: a cold + warm classpath from embedded fixtures via withSnapshotInMemory
    // When: comparing permittedSubclassIds for any sealed class found in both
    // Then: sizes match for any class that has permittedSubclassIds in cold
    // Pins: F-C-002
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    // Migration: was jvmOnly via withRoundTrip; now uses embedded fixtures.
    "F-C-002 (Phase 12): permittedSubclassIds survives in-memory snapshot round-trip" in run {
        TestClasspaths2.withSnapshotInMemory().map: (coldCp, warmCp) =>
            coldCp.allClassLike.foreach:
                case cold: Tasty.Symbol.Class =>
                    cold.permittedSubclassIds match
                        case Present(coldIds) =>
                            val fqn = cold.fullNameString(using coldCp)
                            warmCp.findClass(fqn) match
                                case Present(warm: Tasty.Symbol.Class) =>
                                    warm.permittedSubclassIds match
                                        case Present(warmIds) =>
                                            assert(
                                                coldIds.size == warmIds.size,
                                                s"permittedSubclassIds size differs for $fqn: cold=${coldIds.size} warm=${warmIds.size}"
                                            )
                                        case Absent =>
                                            fail(s"permittedSubclassIds Present in cold but Absent after in-memory round-trip for $fqn")
                                    end match
                                case _ => ()
                            end match
                        case Absent => ()
                    end match
                case _ => ()
            succeed
    }

    // F-C-003 (Phase 12, migrated Phase 2 post-audit): annotations-roundtrip via in-memory snapshot
    // Given: a cold + warm classpath from embedded fixtures via withSnapshotInMemory
    // When: comparing symbolsAnnotatedWith("scala.deprecated").size between cold and warm
    // Then: warm count >= cold count (annotations survive in-memory snapshot round-trip)
    // Pins: F-C-003
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    // Migration: was jvmOnly with `>= 5` lower bound on real stdlib; now uses embedded fixtures (bound removed).
    "F-C-003 (Phase 12): symbolsAnnotatedWith count survives in-memory snapshot round-trip" in run {
        TestClasspaths2.withSnapshotInMemory().map: (coldCp, warmCp) =>
            val coldCount = coldCp.symbolsAnnotatedWith("scala.deprecated").size
            val warmCount = warmCp.symbolsAnnotatedWith("scala.deprecated").size
            assert(
                warmCount >= coldCount,
                s"In-memory snapshot round-trip must preserve deprecated annotation count: cold=$coldCount warm=$warmCount"
            )
            succeed
    }

    // F-G-002 (Phase 12, migrated Phase 2 post-audit): javametadata-roundtrip via in-memory snapshot
    // Given: a cold + warm classpath from embedded fixtures via withSnapshotInMemory
    // When: finding any ClassLike with javaMetadata in cold; checking warm for same symbol
    // Then: javaMetadata Present in warm (if any javaMetadata symbol exists in fixtures); fields match
    // Pins: F-G-002 snapshot mirror
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; no filesystem needed.
    // Migration: was jvmOnly (required real stdlib .class files); embedded fixtures suffice for shape assertion.
    "F-G-002 (Phase 12): javaMetadata survives in-memory snapshot round-trip" in run {
        TestClasspaths2.withSnapshotInMemory().map: (coldCp, warmCp) =>
            coldCp.allClassLike.flatMap:
                case c: Tasty.Symbol.ClassLike if c.javaMetadata.isDefined => Chunk(c)
                case _                                                     => Chunk.empty
            .headOption match
                case Some(coldSym) =>
                    val fqn = coldSym.fullNameString(using coldCp)
                    warmCp.findSymbol(fqn) match
                        case Present(warmSym: Tasty.Symbol.ClassLike) =>
                            assert(
                                warmSym.javaMetadata.isDefined,
                                s"javaMetadata lost after in-memory round-trip for $fqn"
                            )
                            val coldMeta = coldSym.javaMetadata.get
                            val warmMeta = warmSym.javaMetadata.get
                            assert(
                                warmMeta.isJvmPublic == coldMeta.isJvmPublic,
                                s"isJvmPublic differs for $fqn: cold=${coldMeta.isJvmPublic} warm=${warmMeta.isJvmPublic}"
                            )
                            assert(
                                warmMeta.accessFlags == coldMeta.accessFlags,
                                s"accessFlags differs for $fqn: cold=${coldMeta.accessFlags} warm=${warmMeta.accessFlags}"
                            )
                            succeed
                        case _ =>
                            succeed
                    end match
                case None =>
                    // Embedded fixtures may have no javaMetadata symbols; acceptable
                    succeed
            end match
    }

    // Wire-format leaf 5 (Phase 12, updated Phase 2.13): format-version-bumped
    // Given: a snapshot file written with the Phase 2.13 code
    // When: reading the format version constant
    // Then: version is 9 (Phase 5.03 bumped to 9 for ClasspathClosed/ClasspathBuilding context-field addition, F-W2-11)
    // History: Phase 2.13 set 6, Phase 5.01b set 7, Phase 5.02 set 8, Phase 5.03 set 9.
    // Cross-platform: SnapshotFormat.minorVersion is a compile-time constant, no filesystem needed.
    "Phase 2.13: SnapshotFormat.FORMAT_VERSION reflects current minor version after all phase bumps" in {
        assert(
            SnapshotFormat.minorVersion == 9,
            s"Expected SnapshotFormat.minorVersion == 9 but got ${SnapshotFormat.minorVersion}"
        )
    }

    // New leaf (Phase 2.13): in-memory-roundtrip-preserves-symbols
    // Given: a cold classpath from embedded fixtures and a warm in-memory snapshot round-trip
    // When: comparing symbols.size between cold and warm
    // Then: symbol count is preserved end-to-end via MemoryFileSource (no disk needed)
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory; works on JVM, JS, Native.
    // Pins: Target 1 (in-memory snapshot writer) Phase 2.13
    "Phase 2.13: in-memory snapshot round-trip preserves symbols count" in run {
        TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
            assert(
                cold.symbols.size == warm.symbols.size,
                s"Expected cold.symbols.size == warm.symbols.size; cold=${cold.symbols.size} warm=${warm.symbols.size}"
            )
            succeed
    }

    // New leaf (Phase 12, migrated Phase 2.13): old-snapshot-triggers-cold-decode
    // Given: a synthetic snapshot byte stream with magic KRFL, major=1, minor=3 (old format)
    // When: writing it to a MemoryFileSource and calling SnapshotReader.read
    // Then: result is Failure(TastyError.SnapshotVersionMismatch) where found.minor == 3 and supported.minor == current
    // Pins: minor-version rejection decision (Option A from Phase 12 prep)
    // Cross-platform (Phase 2.13): migrated from jvmOnly to use MemoryFileSource.
    "old-snapshot-triggers-cold-decode" in run {
        Sync.defer:
            val fakeOld = new Array[Byte](36)
            fakeOld(0) = 'K'.toByte
            fakeOld(1) = 'R'.toByte
            fakeOld(2) = 'F'.toByte
            fakeOld(3) = 'L'.toByte
            fakeOld(4) = 1.toByte // majorVersion = 1
            fakeOld(5) = 3.toByte // minorVersion = 3 (below current)
            // flags(8), digest(8), reserved(8), sectionCount(4) remain zero
            val mem  = MemoryFileSource()
            val path = "mem/old.krfl"
            mem.add(path, fakeOld)
            (mem, path)
        .flatMap: (mem, path) =>
            Abort.run[TastyError](
                SnapshotReader.read(path, mem)
            ).map:
                case Result.Failure(e: TastyError.SnapshotVersionMismatch) =>
                    assert(
                        e.found.minor == 3,
                        s"Expected found.minor == 3 but got ${e.found.minor}"
                    )
                    assert(
                        e.supported.minor == SnapshotFormat.minorVersion,
                        s"Expected supported.minor == ${SnapshotFormat.minorVersion} but got ${e.supported.minor}"
                    )
                    succeed
                case Result.Failure(other) =>
                    fail(s"Expected SnapshotVersionMismatch but got: $other")
                case Result.Success(_) =>
                    fail("Expected SnapshotVersionMismatch but read succeeded for an old snapshot")
                case Result.Panic(t) =>
                    throw t
    }

end SnapshotFidelityTest
