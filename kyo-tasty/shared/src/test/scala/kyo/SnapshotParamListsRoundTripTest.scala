package kyo

import kyo.internal.TestClasspaths
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Round-trip tests for PLISTS__ section (minor=12 wire format bump).
  *
  * Covers:
  *   1. Meters extension methods preserve paramListIds shape through write+read.
  *   2. A no-arg method round-trips with paramListIds == Chunk(Chunk.empty).
  *   3. A minor=11 snapshot header is rejected with TastyError.SnapshotVersionMismatch.
  *   4. The raw bytes of a written snapshot contain the "PLISTS__" tag.
  *   5. Multi-parameter-list round-trip (marked ignore: no multi-list fixture exists; see decisions.md).
  *
  * Cross-platform: all leaves target shared/src/test (JVM, JS, Native).
  */
class SnapshotParamListsRoundTripTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger
    import Tasty.Name.asString
    import Tasty.SymbolId

    /** Minimal in-memory FileSource for snapshot round-trip without a real filesystem. */
    class MemoryFileSource(val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends kyo.internal.tasty.query.FileSource:
        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): kyo.internal.tasty.query.FileSource.FileStat < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(bytes) =>
                    kyo.internal.tasty.query.FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                case None => Abort.fail(TastyError.FileNotFound(path))
    end MemoryFileSource

    // ── Leaf 1: roundtrip_meters_extension_methods_preserve_paramListIds ──────
    // Given: cross-platform classpath loaded from embedded fixtures (Meters opaque type).
    // When: SnapshotWriter.write + SnapshotReader.read round-trip.
    // Then: the Meters value extension method has identical paramListIds shape after reload;
    //       assert inner-Chunk sizes match and outer-Chunk size matches.
    // Pins: INV-H2 (snapshot round-trip preserves paramListIds shape).
    "roundtrip_meters_extension_methods_preserve_paramListIds" in {
        import Tasty.Name.asString
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0xe0.toByte, 0xe1.toByte, 0xe2.toByte, 0xe3.toByte, 0xe4.toByte, 0xe5.toByte, 0xe6.toByte, 0xe7.toByte)
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: coldCp =>
            Scope.run:
                Abort.run[TastyError](
                    SnapshotWriter.write(coldCp, "cache", digest, cacheSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        SnapshotReader.read(snapPath, cacheSrc).map: warmCp =>
                            // Find the Meters value extension in both cold and warm classpaths.
                            def findValueExt(cp: Tasty.Classpath): Maybe[Tasty.Symbol.Method] =
                                cp.findSymbol("kyo.fixtures.Meters") match
                                    case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                                        cp.companion(opaqueMeters) match
                                            case Maybe.Present(companion: Tasty.Symbol.Object) =>
                                                companion.declarationIds.flatMap: id =>
                                                    cp.symbol(id) match
                                                        case Maybe.Present(m: Tasty.Symbol.Method)
                                                            if m.name.asString == "value" && m.isExtension =>
                                                            Chunk(m)
                                                        case _ => Chunk.empty
                                                .headOption match
                                                    case Some(m) => Maybe.Present(m)
                                                    case None    => Maybe.Absent
                                            case _ => Maybe.Absent
                                    case _ => Maybe.Absent
                            end findValueExt
                            (findValueExt(coldCp), findValueExt(warmCp))
                ).map:
                    case Result.Success((coldMaybe, warmMaybe)) =>
                        coldMaybe match
                            case Maybe.Absent =>
                                fail("Meters value extension not found in cold classpath; fixture setup problem")
                            case Maybe.Present(coldMethod) =>
                                warmMaybe match
                                    case Maybe.Absent =>
                                        fail("Meters value extension not found in warm (snapshot-loaded) classpath")
                                    case Maybe.Present(warmMethod) =>
                                        val coldLists = coldMethod.paramListIds
                                        val warmLists = warmMethod.paramListIds
                                        assert(
                                            coldLists.size == warmLists.size,
                                            s"paramListIds outer size mismatch: cold=${coldLists.size} warm=${warmLists.size}"
                                        )
                                        var i = 0
                                        while i < coldLists.size do
                                            assert(
                                                coldLists(i).size == warmLists(i).size,
                                                s"paramListIds[$i] inner size mismatch: cold=${coldLists(i).size} warm=${warmLists(i).size}"
                                            )
                                            i += 1
                                        end while
                                        succeed
                    case Result.Failure(e) =>
                        fail(s"Unexpected failure: $e")
                    case Result.Panic(t) =>
                        throw t
    }

    // ── Leaf 2: roundtrip_empty_paramListIds_for_noarg_method ─────────────────
    // Given: a synthetic Method with paramListIds = Chunk(Chunk.empty) (empty clause).
    // When: SnapshotWriter.serializeToBytes + SnapshotReader.readFromBytes round-trip.
    // Then: the warm-loaded method has paramListIds == Chunk(Chunk.empty) (not Chunk.empty).
    // Pins: INV-H2 (empty-clause preserved; distinct from no-parameter-lists).
    "roundtrip_empty_paramListIds_for_noarg_method" in {
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val pkgSym  = Tasty.Symbol.Package(SymbolId(1), Tasty.Name("test"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        // A method with paramListIds = Chunk(Chunk.empty): represents `def f(): Unit`.
        val methodSym = Tasty.Symbol.Method(
            SymbolId(2),                  // id
            Tasty.Name("f"),              // name
            Tasty.Flags.empty,            // flags
            SymbolId(1),                  // ownerId
            Maybe.Absent,                 // scaladoc
            Maybe.Absent,                 // sourcePosition
            Maybe.Absent,                 // declaredType
            Chunk(Chunk.empty[SymbolId]), // paramListIds: Chunk(Chunk.empty) = one empty clause
            Chunk.empty[SymbolId],        // typeParamIds
            Chunk.empty,                  // annotations
            Maybe.Absent                  // javaMetadata
        )

        val allSyms = Chunk(rootSym, pkgSym, methodSym)
        val coldCp = Tasty.Classpath.make(
            symbols = allSyms,
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk(rootSym.id, pkgSym.id),
            fqnIndex = Dict.empty,
            packageIndex = Dict.from(Map("test" -> pkgSym.id)),
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = Chunk.empty
        )

        val digest = Array[Byte](0xf0.toByte, 0xf1.toByte, 0xf2.toByte, 0xf3.toByte, 0xf4.toByte, 0xf5.toByte, 0xf6.toByte, 0xf7.toByte)
        val bytes  = SnapshotWriter.serializeToBytes(coldCp, digest)

        Abort.run[TastyError]:
            SnapshotReader.readFromBytes(bytes, "<test>").map: warmCp =>
                // Find method symbol 'f' in warm classpath by name.
                val warmMethod = warmCp.symbols.collect:
                    case m: Tasty.Symbol.Method if m.name.asString == "f" => m
                warmMethod.headOption match
                    case None =>
                        fail("Method 'f' not found in warm classpath after round-trip")
                    case Some(m) =>
                        assert(
                            m.paramListIds.size == 1,
                            s"Expected paramListIds.size == 1 (empty clause preserved), got ${m.paramListIds.size}"
                        )
                        assert(
                            m.paramListIds.head.size == 0,
                            s"Expected paramListIds.head.size == 0 (empty inner list), got ${m.paramListIds.head.size}"
                        )
                        succeed
                end match
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 3: minor_11_snapshot_rejected_with_version_mismatch ─────────────
    // Given: a synthesised KRFL byte array with major=1, minor=11, valid header, sectionCount=0.
    // When: SnapshotReader.readFromBytes is invoked.
    // Then: result is Failure(TastyError.SnapshotVersionMismatch) with found.minor==11
    //       and supported.minor==12.
    // Pins: INV-H5 (REJECT-old policy; no synthesis for minor < 12).
    "minor_11_snapshot_rejected_with_version_mismatch" in {
        val buf = new Array[Byte](64)
        buf(0) = 'K'
        buf(1) = 'R'
        buf(2) = 'F'
        buf(3) = 'L'
        buf(4) = SnapshotFormat.majorVersion.toByte // major = 1
        buf(5) = 11.toByte                          // minor = 11 (stale)
        // flags (LE = 0), digest zeros, reserved zeros
        SnapshotFormat.writeInt32LE(buf, 32, 0) // sectionCount = 0

        Abort.run[TastyError]:
            SnapshotReader.readFromBytes(buf, "<minor-11-test>")
        .map:
            case Result.Success(_) =>
                fail("Expected SnapshotVersionMismatch for minor=11 snapshot but got success")
            case Result.Failure(e) =>
                e match
                    case mismatch: TastyError.SnapshotVersionMismatch =>
                        assert(
                            mismatch.found.minor == 11,
                            s"Expected found.minor == 11, got ${mismatch.found.minor}"
                        )
                        assert(
                            mismatch.supported.minor == 12,
                            s"Expected supported.minor == 12, got ${mismatch.supported.minor}"
                        )
                        succeed
                    case other =>
                        fail(s"Expected SnapshotVersionMismatch but got: ${other.getClass.getSimpleName}: $other")
            case Result.Panic(t) =>
                throw t
    }

    // ── Leaf 4: plists_section_present_in_minor_12_writer_output ─────────────
    // Given: a minimal synthetic classpath written by SnapshotWriter.serializeToBytes.
    // When: the raw bytes are scanned for the 8-char tag "PLISTS__" in the section index.
    // Then: the tag is found (presence check; confirms the writer always emits the section).
    // Pins: writer always emits PLISTS__ unconditionally (even when count=0).
    "plists_section_present_in_minor_12_writer_output" in {
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val emptyCp = Tasty.Classpath.make(
            symbols = Chunk(rootSym),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk(rootSym.id),
            fqnIndex = Dict.empty,
            packageIndex = Dict.empty,
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = Chunk.empty
        )

        val digest = Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val bytes  = SnapshotWriter.serializeToBytes(emptyCp, digest)

        // Scan the section index for the PLISTS__ name.
        val sectionCount = SnapshotFormat.readInt32LE(bytes, 32)
        val foundNames   = scala.collection.mutable.Set.empty[String]
        var idxPos       = 36
        var i            = 0
        while i < sectionCount do
            val name = SnapshotFormat.readSectionName(bytes, idxPos)
            foundNames += name
            idxPos += SnapshotFormat.sectionIndexEntrySize
            i += 1
        end while

        assert(
            foundNames.contains(SnapshotFormat.sectionPLISTS),
            s"Expected PLISTS__ section in minor=12 snapshot output but not found. Sections found: ${foundNames.toSet}"
        )
        succeed
    }

    // ── Leaf 5: multi_list_method_roundtrip ───────────────────────────────────
    // No multi-parameter-list method exists in the current fixture set.
    // The fixture (FixtureClasses.scala) only defines single-list and no-arg methods.
    // This leaf is .ignore per plan instruction: document the gap and skip rather than silently drop.
    // To activate: add a fixture method such as `def curried(a: Int)(b: Int): Int = a + b`
    // to kyo-tasty-fixtures/shared/src/main/scala/kyo/fixtures/FixtureClasses.scala,
    // re-embed the TASTy bytes, and remove the .ignore annotation.
    "multi_list_method_roundtrip".ignore(
        "No multi-parameter-list method in embedded fixture set. See decisions.md Leaf 5 for activation steps."
    ) in {
        // Not active: no multi-parameter-list method in embedded fixture set (see decisions.md Leaf 5).
        fail("Not yet active; see decisions.md Leaf 5")
    }

end SnapshotParamListsRoundTripTest
