package kyo

import kyo.internal.TestClasspaths
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
  *   5. Multi-parameter-list round-trip (marked ignore: no multi-list fixture exists yet).
  *
  * Cross-platform: all leaves target shared/src/test (JVM, JS, Native).
  */
class SnapshotParamListsRoundTripTest extends kyo.test.Test[Any]:

    // Cold-loads the standard classpath (kyo-tasty + kyo-data + scala-library) then writes and reads a snapshot.
    // A single cold-load takes 20-30s on a loaded machine; the full leaf exceeds the 60s default.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(3))

    import AllowUnsafe.embrace.danger
    import Tasty.Name.asString
    import Tasty.SymbolId

    //   On JVM: TestClasspaths.kyoTastyFixtures (minimal; only the fixture classes needed
    //           for kyo.fixtures.Meters; avoids loading scala-library which takes 2+ minutes).
    //   On JS/Native: TestClasspaths.withClasspath() uses the embedded fixture set.
    //       assert inner-Chunk sizes match and outer-Chunk size matches.
    "roundtrip_meters_extension_methods_preserve_paramListIds" in {
        import Tasty.Name.asString
        val digest = Array[Byte](0xe0.toByte, 0xe1.toByte, 0xe2.toByte, 0xe3.toByte, 0xe4.toByte, 0xe5.toByte, 0xe6.toByte, 0xe7.toByte)
        // Use the fixture-only subset on JVM to stay well within the 3-minute class timeout.
        // TestClasspaths.withClasspath takes a Seq[String]; on JS/Native the parameter is
        // ignored and the embedded fixture set is always used.
        TestClasspaths.withClasspath(TestClasspaths.kyoTastyFixtures)(Tasty.classpath).map { coldCp =>
            Scope.run {
                Abort.run[TastyError] {
                    val snapPath = s"cache/${DigestComputer.toHexString(digest)}.krfl"
                    val bytes    = SnapshotWriter.serializeToBytes(coldCp, digest)
                    SnapshotReader.readFromBytes(bytes, snapPath).map { warmCp =>
                        // Find the Meters value extension in both cold and warm classpaths.
                        def findValueExt(classpath: Tasty.Classpath): Maybe[Tasty.Symbol.Method] =
                            classpath.findSymbol("kyo.fixtures.Meters") match
                                case Maybe.Present(opaqueMeters: Tasty.Symbol.OpaqueType) =>
                                    classpath.companion(opaqueMeters) match
                                        case Maybe.Present(companion: Tasty.Symbol.Object) =>
                                            companion.declarationIds.flatMap { id =>
                                                classpath.symbol(id) match
                                                    case Maybe.Present(m: Tasty.Symbol.Method)
                                                        if m.name.asString == "value" && m.isExtension =>
                                                        Chunk(m)
                                                    case _ => Chunk.empty
                                            }.headOption match
                                                case Some(m) => Maybe.Present(m)
                                                case None    => Maybe.Absent
                                        case _ => Maybe.Absent
                                case _ => Maybe.Absent
                        end findValueExt
                        (findValueExt(coldCp), findValueExt(warmCp))
                    }
                }.map {
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
            }
        }
    }

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
            fullNameIndex = Dict.empty,
            packageIndex = Dict.from(Map("test" -> pkgSym.id)),
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = Chunk.empty
        )

        val digest = Array[Byte](0xf0.toByte, 0xf1.toByte, 0xf2.toByte, 0xf3.toByte, 0xf4.toByte, 0xf5.toByte, 0xf6.toByte, 0xf7.toByte)
        val bytes  = SnapshotWriter.serializeToBytes(coldCp, digest)

        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(bytes, "<test>").map { warmCp =>
                // Find method symbol 'f' in warm classpath by name.
                val warmMethod = warmCp.symbols.collect {
                    case m: Tasty.Symbol.Method if m.name.asString == "f" => m
                }
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
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "minor_11_snapshot_rejected_with_version_mismatch" in {
        val buffer = new Array[Byte](64)
        buffer(0) = 'K'
        buffer(1) = 'R'
        buffer(2) = 'F'
        buffer(3) = 'L'
        buffer(4) = SnapshotFormat.majorVersion.toByte // major = 1
        buffer(5) = 11.toByte                          // minor = 11 (stale)
        // flags (LE = 0), digest zeros, reserved zeros
        SnapshotFormat.writeInt32LE(buffer, 32, 0) // sectionCount = 0

        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(buffer, "<minor-11-test>")
        }.map {
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
    }

    "plists_section_present_in_minor_12_writer_output" in {
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val emptyCp = Tasty.Classpath.make(
            symbols = Chunk(rootSym),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk(rootSym.id),
            fullNameIndex = Dict.empty,
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

    // No multi-parameter-list method exists in the current fixture set.
    // The fixture (FixtureClasses.scala) only defines single-list and no-arg methods.
    // To activate: add a fixture method with multiple parameter lists, re-embed the TASTy bytes,
    // and remove the .ignore annotation.
    "multi_list_method_roundtrip".ignore(
        "No multi-parameter-list method in embedded fixture set."
    ) in {
        fail("Not yet active")
    }

end SnapshotParamListsRoundTripTest
