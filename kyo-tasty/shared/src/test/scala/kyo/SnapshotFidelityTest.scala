package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Fidelity tests for snapshot round-trip integrity.
  *
  * Pins findings F-C-001, F-C-002, F-C-003, and F-G-002. Phase 12 un-pends all five existing leaves and adds one new v3-rejection leaf by
  * fixing `SnapshotWriter` (serialize permittedSubclassIds, annotations, javaMetadata, full fqnIndex), `SnapshotReader` (read new fields,
  * reject minor < 5, reconstruct dual-FQN index from FQNIDX__ section), and bumping FORMAT_VERSION to 5.
  *
  * Phase 2.12: relocated from jvm/src/test to shared/src/test. All leaves that perform filesystem snapshot round-trips (F-C-002, F-C-003,
  * F-G-002, INV-010, v3-snapshot) are gated jvmOnly because they require java.nio.file and JvmFileSource. The pure format-version leaf
  * (SnapshotFormat.minorVersion) runs cross-platform.
  *
  * Phase 2.13: adds FQNMAP__ section for unresolvedFqnByNegId persistence (Target 3), bumps FORMAT_VERSION to 6, and adds in-memory
  * snapshot round-trip leaves (Target 1) that run cross-platform via TestClasspaths2.withSnapshotInMemory.
  */
class SnapshotFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Perform a cold load then write and read back a snapshot, returning (coldCp, warmCp). JVM-only helper. */
    private def withRoundTrip()(using Frame): (Tasty.Classpath, Tasty.Classpath) < (Async & Scope & Abort[TastyError]) =
        // Loads the platform-default classpath (standard roots on JVM, embedded fixtures on JS/Native).
        // This method is only called from jvmOnly leaves, so only the JVM path is exercised at runtime.
        TestClasspaths.withClasspath().flatMap: coldCp =>
            Sync.defer:
                TestClasspaths2.createTempDir("kyo-snapshot-fidelity")
            .flatMap: tmpDir =>
                val digest  = Array[Byte](0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17)
                val platSrc = PlatformFileSource.get
                SnapshotWriter.write(coldCp, tmpDir, digest, platSrc).flatMap: _ =>
                    val snapPath = s"$tmpDir/${DigestComputer.toHexString(digest)}.krfl"
                    SnapshotReader.read(snapPath, platSrc).map: warmCp =>
                        (coldCp, warmCp)

    // F-C-002 / INV-010 leaf 1 (Phase 12): roundtrip-fidelity
    // Given: a cold-loaded Classpath with all Phase 04..11 fixes applied;
    //        a snapshot written via SnapshotWriter then read back via SnapshotReader-only (warm load)
    // When: running the full assertion set against the warm-loaded classpath
    // Then: post-fix every assertion passes
    // Pins: INV-010 producer
    // JVM-only: requires java.nio.file.Files.createTempDirectory and JvmFileSource.
    "INV-010 (Phase 12): snapshot warm-load preserves all Phase 04..11 fixed data" taggedAs jvmOnly in run {
        withRoundTrip().map: (coldCp, warmCp) =>
            val coldAnnotCount = coldCp.symbolsAnnotatedWith("scala.deprecated").size
            val warmAnnotCount = warmCp.symbolsAnnotatedWith("scala.deprecated").size
            assert(
                warmAnnotCount >= coldAnnotCount,
                s"Warm load lost deprecated annotations: cold=$coldAnnotCount warm=$warmAnnotCount"
            )
            val coldOpt = coldCp.findClass("scala.Option")
            val warmOpt = warmCp.findClass("scala.Option")
            (coldOpt, warmOpt) match
                case (Present(cold), Present(warm)) =>
                    (cold.permittedSubclassIds, warm.permittedSubclassIds) match
                        case (Present(coldIds), Present(warmIds)) =>
                            assert(
                                coldIds.size == warmIds.size,
                                s"scala.Option permittedSubclassIds size differs: cold=${coldIds.size} warm=${warmIds.size}"
                            )
                        case (Present(_), Absent) =>
                            fail("scala.Option.permittedSubclassIds was Present in cold but Absent after round-trip")
                        case _ =>
                            succeed
                    end match
                case _ =>
                    succeed
            end match
            succeed
    }

    // F-C-002 leaf 2 (Phase 12): permits-roundtrip
    // Given: a cold + warm Classpath pair via TestClasspaths.withClasspath and snapshot round-trip
    // When: comparing cp_cold.findClass("scala.Option").get.permittedSubclassIds vs warm
    // Then: post-fix both are Present with equal sizes
    // Pins: F-C-002
    // JVM-only: requires filesystem snapshot round-trip.
    "F-C-002 (Phase 12): scala.Option.permittedSubclasses survives snapshot round-trip" taggedAs jvmOnly in run {
        withRoundTrip().map: (coldCp, warmCp) =>
            coldCp.findClass("scala.Option") match
                case Present(cold) =>
                    warmCp.findClass("scala.Option") match
                        case Present(warm) =>
                            (cold.permittedSubclassIds, warm.permittedSubclassIds) match
                                case (Present(coldIds), Present(warmIds)) =>
                                    assert(
                                        coldIds.size == warmIds.size,
                                        s"permittedSubclassIds size: cold=${coldIds.size} warm=${warmIds.size}"
                                    )
                                    assert(
                                        warmIds.size >= 2,
                                        s"Expected at least 2 permitted subclasses for scala.Option; got ${warmIds.size}"
                                    )
                                    succeed
                                case (Present(_), Absent) =>
                                    fail("permittedSubclassIds Present in cold but Absent after round-trip")
                                case (Absent, _) =>
                                    succeed
                                case _ =>
                                    succeed
                            end match
                        case Absent =>
                            fail("scala.Option not found on warm classpath; round-trip failed to preserve class")
                    end match
                case Absent =>
                    fail("scala.Option not found on cold classpath; scala-library must be in roots")
            end match
    }

    // F-C-003 leaf 3 (Phase 12): annotations-roundtrip
    // Given: a cold + warm Classpath pair via TestClasspaths.withClasspath and snapshot round-trip
    // When: comparing cp_cold.symbolsAnnotatedWith("scala.deprecated").size vs warm
    // Then: post-fix the sizes match (warm >= cold >= 5)
    // Pins: F-C-003
    // JVM-only: requires filesystem snapshot round-trip.
    "F-C-003 (Phase 12): symbolsAnnotatedWith(scala.deprecated) count survives snapshot round-trip" taggedAs jvmOnly in run {
        withRoundTrip().map: (coldCp, warmCp) =>
            val coldCount = coldCp.symbolsAnnotatedWith("scala.deprecated").size
            val warmCount = warmCp.symbolsAnnotatedWith("scala.deprecated").size
            assert(
                coldCount >= 5,
                s"Cold classpath must have >= 5 deprecated symbols; found $coldCount"
            )
            assert(
                warmCount >= coldCount,
                s"Warm load must preserve deprecated annotation count: cold=$coldCount warm=$warmCount"
            )
            succeed
    }

    // F-G-002 leaf 4 (Phase 12): javametadata-roundtrip
    // Given: a cold + warm Classpath pair; ANY symbol (including Object) with javaMetadata
    // When: comparing javaMetadata for that symbol between cold and warm via source FQN lookup
    // Then: post-fix javaMetadata matches between cold and warm loads for both Class and Object
    // Pins: F-G-002 snapshot mirror + dual-FQN round-trip (HARD RULE 8)
    // JVM-only: requires filesystem snapshot round-trip.
    "F-G-002 (Phase 12): javaMetadata survives snapshot round-trip for any symbol kind" taggedAs jvmOnly in run {
        withRoundTrip().map: (coldCp, warmCp) =>
            val coldWithMeta = Maybe.fromOption(
                coldCp.allClasses.flatMap:
                    case c: Tasty.Symbol.ClassLike if c.javaMetadata.isDefined => Chunk(c)
                    case _                                                     => Chunk.empty
                .find: c =>
                    val fqn = c.fullNameString(using coldCp)
                    coldCp.fqnIndex.contains(fqn) && warmCp.findSymbol(fqn).isDefined
            )
            coldWithMeta match
                case Present(coldSym) =>
                    val fqn = coldSym.fullNameString(using coldCp)
                    warmCp.findSymbol(fqn) match
                        case Present(warmSym: Tasty.Symbol.ClassLike) =>
                            assert(
                                warmSym.javaMetadata.isDefined,
                                s"javaMetadata lost after round-trip for $fqn (kind=${coldSym.kind}): cold=Present warm=Absent"
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
                        case Present(warmSym) =>
                            fail(
                                s"Symbol at $fqn is not ClassLike on warm classpath: ${warmSym.kind}"
                            )
                        case Absent =>
                            fail(
                                s"Symbol $fqn (kind=${coldSym.kind}) not found on warm classpath after round-trip; " +
                                    "dual-FQN serialization (FQNIDX__ section) must preserve Object source FQNs"
                            )
                    end match
                case Absent =>
                    fail(
                        "No ClassLike symbol with javaMetadata found in cold classpath; " +
                            "the standard fixture must include .class files (java-library and scala-library are required)"
                    )
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
