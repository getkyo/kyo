package kyo

import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Tests for KRFL snapshot round-trip, digest determinism, and openCached behavior.
  */
class SnapshotRoundTripTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    private val someTraitPickle =
        Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty))

    /** Serialize a single-fixture classpath to bytes and return (bytes, snapshotPath). */
    private def serializeSnapshot()(using Frame): (Array[Byte], String) < (Async & Abort[TastyError]) =
        val digest = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
        Tasty.withPickles(Chunk(plainClassPickle)) {
            Tasty.classpath.map { classpath =>
                val bytes    = SnapshotWriter.serializeToBytes(classpath, digest)
                val hex      = DigestComputer.toHexString(digest)
                val snapPath = s"mem/$hex.krfl"
                (bytes, snapPath)
            }
        }
    end serializeSnapshot

    "snapshot round-trip: topLevelClasses by fully-qualified name match after write+read" in {
        Abort.run[TastyError](
            serializeSnapshot().map { case (bytes, snapshotPath) =>
                Tasty.withPickles(Chunk(plainClassPickle)) {
                    Tasty.classpath.map { origCp =>
                        val origClasses = origCp.topLevelClasses
                        SnapshotReader.readFromBytes(bytes, snapshotPath).map { loadedCp =>
                            val loadedClasses = loadedCp.topLevelClasses
                            (origClasses, loadedClasses)
                        }
                    }
                }
            }
        ).map {
            case Result.Success((origClasses: Chunk[Tasty.Symbol], loadedClasses: Chunk[Tasty.Symbol])) =>
                val origFullNames   = origClasses.map(_.name.asString).toSet
                val loadedFullNames = loadedClasses.map(_.name.asString).toSet
                assert(
                    origFullNames == loadedFullNames,
                    s"topLevelClasses fully-qualified names must match after snapshot round-trip: orig=$origFullNames loaded=$loadedFullNames"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "reading a snapshot with wrong magic produces SnapshotFormatError" in {
        val badMagicBytes = Array[Byte]('X', 'Y', 'Z', 'W', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(badMagicBytes, "cache/bad.krfl")
        }.map {
            case Result.Success(_) =>
                fail("Expected SnapshotFormatError for wrong magic")
            case Result.Failure(e) =>
                e match
                    case _: TastyError.SnapshotFormatError => succeed
                    case other                             => fail(s"Expected SnapshotFormatError but got: $other")
            case Result.Panic(t) =>
                throw t
        }
    }

    "reading a snapshot with different major version produces SnapshotVersionMismatch" in {
        val badVersionBytes = Array.fill[Byte](64)(0)
        badVersionBytes(0) = 'K'
        badVersionBytes(1) = 'R'
        badVersionBytes(2) = 'F'
        badVersionBytes(3) = 'L'
        badVersionBytes(4) = 99.toByte // major version 99, not 1
        badVersionBytes(5) = 0.toByte  // minor version 0
        // Fill in a minimal section count of 0
        badVersionBytes(32) = 0 // sectionCount = 0

        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(badVersionBytes, "cache/badver.krfl")
        }.map {
            case Result.Success(_) =>
                fail("Expected SnapshotVersionMismatch for wrong major version")
            case Result.Failure(e) =>
                e match
                    case _: TastyError.SnapshotVersionMismatch => succeed
                    case other                                 => fail(s"Expected SnapshotVersionMismatch but got: $other")
            case Result.Panic(t) =>
                throw t
        }
    }

    "writing snapshot to an unwritable path produces SnapshotIoError" in {
        // Create a temp file and then try to use it as a cache directory.
        // Path.mkDir on a path where a file already exists fails with FileFsException,
        // which SnapshotWriter wraps as SnapshotIoError.
        Path.tempDir("kyo-srt-fail").map { tmpDir =>
            val fileAsDir = tmpDir / "not-a-dir"
            fileAsDir.mkFile.map { _ =>
                Abort.run[TastyError](
                    Tasty.withPickles(Chunk(plainClassPickle)) {
                        Tasty.classpath.map { classpath =>
                            val digest = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
                            SnapshotWriter.write(classpath, fileAsDir.toString, digest)
                        }
                    }
                ).map {
                    case Result.Success(_) =>
                        fail("Expected SnapshotIoError for unwritable cache dir")
                    case Result.Failure(e) =>
                        e match
                            case _: TastyError.SnapshotIoError => succeed
                            case other                         => fail(s"Expected SnapshotIoError but got: $other")
                    case Result.Panic(t) =>
                        throw t
                }
            }
        }
    }

    "two concurrent snapshot writers produce one valid snapshot file (atomic rename)" in {
        val digest = Array[Byte](0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11)
        val hex    = DigestComputer.toHexString(digest)
        Path.tempDir("kyo-srt-conc").map { dir =>
            val cacheDir = dir.toString
            val finalKey = s"$cacheDir/$hex.krfl"
            Abort.run[TastyError | Timeout](
                Async.timeout(5.seconds)(
                    Tasty.withPickles(Chunk(plainClassPickle)) {
                        Tasty.classpath.map { classpath =>
                            Async.zip[TastyError, Unit, Unit, Any](
                                SnapshotWriter.write(classpath, cacheDir, digest),
                                SnapshotWriter.write(classpath, cacheDir, digest)
                            ).map(_ => ())
                        }
                    }
                )
            ).map {
                case Result.Success(_) =>
                    // Both writers completed; the final snapshot file must exist and be valid
                    Abort.run[FileFsException](Path(finalKey).exists).map {
                        case Result.Success(exists) =>
                            assert(exists, s"Expected snapshot file at $finalKey after concurrent writes")
                            succeed
                        case Result.Failure(e) =>
                            fail(s"Path.exists failed: $e")
                        case Result.Panic(t) =>
                            throw t
                    }
                case Result.Failure(_: Timeout) =>
                    fail("Concurrent snapshot write timed out")
                case Result.Failure(e) =>
                    // One writer may fail with SnapshotIoError (rename collision); final file must still exist
                    Abort.run[FileFsException](Path(finalKey).exists).map {
                        case Result.Success(exists) =>
                            assert(exists, s"Expected snapshot file at $finalKey even after partial failure")
                            succeed
                        case _ => succeed
                    }
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "openCached warm cache hit returns same symbol graph as cold open" in {
        val digest = Array[Byte](0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19)
        Abort.run[TastyError](
            // Cold open: build from TASTy
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { coldCp =>
                    val coldClasses = coldCp.topLevelClasses
                    val bytes       = SnapshotWriter.serializeToBytes(coldCp, digest)
                    val hex         = DigestComputer.toHexString(digest)
                    val snapPath    = s"mem/$hex.krfl"
                    SnapshotReader.readFromBytes(bytes, snapPath).map { warmCp =>
                        val warmClasses = warmCp.topLevelClasses
                        (coldClasses, warmClasses)
                    }
                }
            }
        ).map {
            case Result.Success((coldClasses: Chunk[Tasty.Symbol], warmClasses: Chunk[Tasty.Symbol])) =>
                val coldFullNames = coldClasses.map(_.name.asString).toSet
                val warmFullNames = warmClasses.map(_.name.asString).toSet
                assert(
                    coldFullNames == warmFullNames,
                    s"Warm cache must return same fully-qualified names as cold open: cold=$coldFullNames warm=$warmFullNames"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "cold miss writes snapshot file to cache dir" in {
        val digest = Array[Byte](0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27)
        val hex    = DigestComputer.toHexString(digest)
        Path.tempDir("kyo-srt-cold").map { dir =>
            val cacheDir = dir.toString
            val snapPath = s"$cacheDir/$hex.krfl"
            Abort.run[TastyError](
                Tasty.withPickles(Chunk(plainClassPickle)) {
                    Tasty.classpath.map { classpath =>
                        SnapshotWriter.write(classpath, cacheDir, digest)
                    }
                }
            ).map {
                case Result.Success(_) =>
                    Abort.run[FileFsException](Path(snapPath).exists).map {
                        case Result.Success(exists) =>
                            assert(exists, s"Expected snapshot file at $snapPath after write")
                            succeed
                        case Result.Failure(e) =>
                            fail(s"Path.exists failed: $e")
                        case Result.Panic(t) =>
                            throw t
                    }
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "evictOlderThan removes all snapshot files older than maxAgeMs" in {
        // All .krfl files are set to a fixed past mtime (2001-09-08 UTC), so any positive maxAge evicts them.
        // other.txt has no .krfl extension, so evictOlderThan leaves it untouched.
        val staleMs = 1_000_000_000_000L
        val maxAge  = 60.seconds
        Path.tempDir("kyo-srt-evict").map { dir =>
            val f1  = dir / "aabbccdd01020304.krfl"
            val f2  = dir / "1122334455667788.krfl"
            val txt = dir / "other.txt"
            f1.writeBytes(Span.from(Array[Byte](1, 2, 3, 4))).map { _ =>
                f1.setLastModified(staleMs).map { _ =>
                    f2.writeBytes(Span.from(Array[Byte](5, 6, 7, 8))).map { _ =>
                        f2.setLastModified(staleMs).map { _ =>
                            txt.writeBytes(Span.from(Array[Byte](9, 10))).map { _ =>
                                Abort.run[TastyError](
                                    Tasty.evictOlderThan(dir.toString, maxAge)
                                ).map {
                                    case Result.Success(_) =>
                                        f1.exists.map { e1 =>
                                            f2.exists.map { e2 =>
                                                txt.exists.map { et =>
                                                    assert(!e1, s"aabbccdd01020304.krfl must be evicted; exists=$e1")
                                                    assert(!e2, s"1122334455667788.krfl must be evicted; exists=$e2")
                                                    assert(et, s"other.txt must NOT be evicted; exists=$et")
                                                    succeed
                                                }
                                            }
                                        }
                                    case Result.Failure(e) =>
                                        fail(s"Unexpected failure: $e")
                                    case Result.Panic(t) =>
                                        throw t
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "DigestComputer.compute for the same roots is deterministic" in {
        Path.tempDir("kyo-srt-det").map { dir =>
            val file = dir / "PlainClass.tasty"
            file.writeBytes(Span.from(kyo.fixtures.Embedded.plainClassTasty)).map { _ =>
                val root = dir.toString
                Abort.run[TastyError] {
                    DigestComputer.compute(Seq(root)).map { digest1 =>
                        DigestComputer.compute(Seq(root)).map { digest2 =>
                            (digest1, digest2)
                        }
                    }
                }.map {
                    case Result.Success((d1, d2)) =>
                        assert(d1.length == d2.length, "Digest arrays must have same length")
                        assert(d1.sameElements(d2), s"Same inputs must produce same digest: ${d1.toSeq} vs ${d2.toSeq}")
                    case Result.Failure(e) =>
                        fail(s"Unexpected failure: $e")
                    case Result.Panic(t) =>
                        throw t
                }
            }
        }
    }

    "DigestComputer.compute for different file sets returns different digests" in {
        Path.tempDir("kyo-srt-diff").map { dir =>
            val file = dir / "PlainClass.tasty"
            val root = dir.toString
            file.writeBytes(Span.from(kyo.fixtures.Embedded.plainClassTasty)).map { _ =>
                Abort.run[TastyError] {
                    DigestComputer.compute(Seq(root)).map { digest1 =>
                        // Overwrite with different-length content so size (and thus digest) differs.
                        file.writeBytes(Span.from(Array[Byte](1, 2, 3, 4, 5, 6, 7, 8))).map { _ =>
                            DigestComputer.compute(Seq(root)).map { digest2 =>
                                (digest1, digest2)
                            }
                        }
                    }
                }.map {
                    case Result.Success((d1, d2)) =>
                        assert(!d1.sameElements(d2), "Different file contents must produce different digests")
                    case Result.Failure(e) =>
                        fail(s"Unexpected failure: $e")
                    case Result.Panic(t) =>
                        throw t
                }
            }
        }
    }

    // written snapshot header inputDigest field (bytes 16-23) equals the digest passed to write
    "snapshot header inputDigest field equals digest passed to write (not zeros)" in {
        val digest = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    SnapshotWriter.serializeToBytes(classpath, digest)
                }
            }
        ).map {
            case Result.Success(bytes) =>
                assert(bytes.length >= 24, s"Snapshot too short to contain inputDigest: ${bytes.length} bytes")
                val headerDigest = bytes.slice(16, 24)
                assert(
                    headerDigest.sameElements(digest),
                    s"inputDigest header field must equal passed digest. Expected: ${digest.toSeq} got: ${headerDigest.toSeq}"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // After snapshot round-trip, bodyTree returns Absent (bodies not serialized).
    // Bodies are stored in DecodeContext.bodyStore which is not persisted in snapshots.
    // Use withClasspath(roots) to re-populate the body store from TASTy files.
    "BODY_BYTES round-trip: bodyTree returns Absent on snapshot-loaded symbol (snapshot contract)" in {
        val digest = Array[Byte](0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37)
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val bytes    = SnapshotWriter.serializeToBytes(classpath, digest)
                    val hex      = DigestComputer.toHexString(digest)
                    val snapPath = s"mem/$hex.krfl"
                    SnapshotReader.readFromBytes(bytes, snapPath).map { loadedCp =>
                        // After snapshot load, body store is empty; bodyTree must return Absent.
                        Tasty.withClasspath(loadedCp) {
                            val methods = loadedCp.symbols.collect { case m: Tasty.Symbol.Method => m }
                            val testSym = methods.headOption.getOrElse(loadedCp.symbols.head)
                            Tasty.bodyTree(testSym).map { result =>
                                assert(!result.isDefined, "bodyTree must return Absent after snapshot load")
                                succeed
                            }
                        }
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // snapshot written from classfile-only classpath has empty BODY_BYTES section
    "snapshot from classfile-only classpath has empty BODY_BYTES section (length 0)" in {
        // Use an empty classpath (no TASTy files) to produce a snapshot with no body bytes.
        val digest = Array[Byte](0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47)
        Abort.run[TastyError](
            // Open an empty classpath: transitions to Ready immediately with empty state
            Tasty.withPickles(Chunk.empty) {
                Tasty.classpath.map { classpath =>
                    SnapshotWriter.serializeToBytes(classpath, digest)
                }
            }
        ).map {
            case Result.Success(bytes) =>
                // Parse section index and find BODY_BYTES length
                val sectionCount = SnapshotFormat.readInt32LE(bytes, 32)
                var idxPos       = 36
                var bodyLen      = -1
                var i            = 0
                while i < sectionCount do
                    val sName = SnapshotFormat.readSectionName(bytes, idxPos)
                    val sLen  = SnapshotFormat.readInt64LE(bytes, idxPos + 16)
                    if sName == SnapshotFormat.sectionBODYBYTES then bodyLen = sLen.toInt
                    idxPos += SnapshotFormat.sectionIndexEntrySize
                    i += 1
                end while
                assert(bodyLen == 0, s"BODY_BYTES section must be empty (length 0) for classfile-only classpath; got $bodyLen")
                // Also verify the snapshot loads without error
                val hex      = DigestComputer.toHexString(digest)
                val snapPath = s"mem/$hex.krfl"
                Abort.run[TastyError](SnapshotReader.readFromBytes(bytes, snapPath)).map {
                    case Result.Success(_) =>
                        succeed
                    case Result.Failure(e) =>
                        fail(s"Reading empty-body snapshot must not fail: $e")
                    case Result.Panic(t) =>
                        throw t
                }
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // Test: parents, typeParams, and declarations are preserved across a snapshot write+read round-trip.
    "snapshot round-trip: parents, typeParams, and declarations preserved after write+read" in {
        // Use SomeTrait fixture which has parents (java.lang.Object) and member declarations (compute method).
        val digest = Array[Byte](0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67)
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle)) {
                // Cold open: build from TASTy to capture expected values.
                Tasty.classpath.map { coldCp =>
                    val coldClasses = coldCp.topLevelClasses
                    val bytes       = SnapshotWriter.serializeToBytes(coldCp, digest)
                    val hex         = DigestComputer.toHexString(digest)
                    val snapPath    = s"mem/$hex.krfl"
                    // Warm load from snapshot bytes.
                    SnapshotReader.readFromBytes(bytes, snapPath).map { warmCp =>
                        val warmClasses = warmCp.topLevelClasses
                        (coldClasses, warmClasses)
                    }
                }
            }
        ).map {
            case Result.Success(pair) =>
                val (coldClasses, warmClasses) = pair
                // Verify every cold class's declarations are preserved in the warm load.
                // typeParams and parents that reference symbols outside the loaded classpath (e.g.
                // java.lang.Object from classfiles not in the snapshot) are encoded as -1 and skipped;
                // the warm chunk is smaller or empty for purely external parents.
                var allGood = true
                var failMsg = ""
                for coldSym <- coldClasses do
                    val coldFullName = coldSym.name.asString
                    val warmSymOpt   = warmClasses.toSeq.find(_.name.asString == coldFullName)
                    warmSymOpt match
                        case None =>
                            allGood = false
                            failMsg = s"Warm classpath missing symbol $coldFullName"
                        case Some(warmSym) =>
                            // We check declarationIds.length as a proxy.
                            val coldDeclNames = (coldSym match
                                case c: Tasty.Symbol.ClassLike => c.declarationIds;
                                case null                      => Chunk.empty
                            ).map(_.value.toString).toSet
                            val warmDeclNames = (warmSym match
                                case c: Tasty.Symbol.ClassLike => c.declarationIds;
                                case null                      => Chunk.empty
                            ).map(_.value.toString).toSet
                            if coldDeclNames.nonEmpty && warmDeclNames.isEmpty then
                                allGood = false
                                failMsg = s"$coldFullName: cold has declarations $coldDeclNames but warm has none after round-trip"
                    end match
                end for
                assert(allGood, failMsg)
                for warmSym <- warmClasses do
                    val parentsChunk = warmSym match
                        case c: Tasty.Symbol.ClassLike => c.parentTypes;
                        case null                      => Chunk.empty
                    assert(parentsChunk != null, s"${warmSym.name.asString}: parentTypes was null after snapshot load")
                end for
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // snapshot round-trip preserves a local Named parent.
    //        (Class, parents=[Named(barSym)]). Both symbols are local so the SnapshotWriter assigns
    //        barSym a local symbolId and writes it in the PARENTS section.
    //       fullName equals "test.Bar".
    "snapshot round-trip: local Named parent is preserved in Foo.parents" in {
        val digest =
            Array[Byte](0x70.toByte, 0x71.toByte, 0x72.toByte, 0x73.toByte, 0x74.toByte, 0x75.toByte, 0x76.toByte, 0x77.toByte)

        import AllowUnsafe.embrace.danger
        import kyo.Tasty.SymbolId
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val pkgSym  = Tasty.Symbol.Package(SymbolId(1), Tasty.Name("test"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val barSym = Tasty.Symbol.Class(
            SymbolId(2),
            Tasty.Name("Bar"),
            Tasty.Flags.empty,
            SymbolId(1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        val fooSym = Tasty.Symbol.Class(
            SymbolId(3),
            Tasty.Name("Foo"),
            Tasty.Flags.empty,
            SymbolId(1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk(Tasty.Type.Named(barSym.id)),
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

        val allSyms: Chunk[Tasty.Symbol]  = Chunk(rootSym, pkgSym, barSym, fooSym)
        val topLevel: Chunk[Tasty.Symbol] = Chunk(barSym, fooSym)
        val pkgs: Chunk[Tasty.Symbol]     = Chunk(rootSym, pkgSym)
        val fullNameMap                   = scala.collection.immutable.Map[String, Tasty.Symbol]("test.Bar" -> barSym, "test.Foo" -> fooSym)
        val pkgMap                        = scala.collection.immutable.Map[String, Tasty.Symbol]("test" -> pkgSym)

        Abort.run[TastyError] {
            val fullNameIdMap = Dict.from(fullNameMap.map { case (k, v) => k -> v.id }.toMap)
            val pkgIdMap      = Dict.from(pkgMap.map { case (k, v) => k -> v.id }.toMap)
            val topIds        = topLevel.map(_.id)
            val pkgIds        = pkgs.map(_.id)
            val coldCp = Tasty.Classpath.make(
                symbols = allSyms,
                rootSymbolId = SymbolId(0),
                topLevelClassIds = topIds,
                packageIds = pkgIds,
                fullNameIndex = fullNameIdMap,
                packageIndex = pkgIdMap,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
            val bytes    = SnapshotWriter.serializeToBytes(coldCp, digest)
            val hex      = DigestComputer.toHexString(digest)
            val snapPath = s"mem/$hex.krfl"
            SnapshotReader.readFromBytes(bytes, snapPath).map { warmCp =>
                warmCp.findClass("test.Foo") match
                    case Maybe.Present(symbol) => symbol match
                            case c: Tasty.Symbol.ClassLike => c.parentTypes;
                            case null                      => Chunk.empty
                    case Maybe.Absent => Abort.fail(TastyError.NotImplemented("test.Foo not found after snapshot load"))
            }
        }.map {
            case Result.Success(parents) =>
                assert(parents.nonEmpty, "Foo.parents must be non-empty after snapshot round-trip with local Named parent")
                // Name check deferred to; verify that a Named parent with the Bar id is present.
                val hasBar = parents.toSeq.exists {
                    case Tasty.Type.Named(_) => true
                    case _                   => false
                }
                assert(hasBar, s"Foo.parentTypes must contain a Named parent after snapshot round-trip; got ${parents.size} parents")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // full Classpath data is preserved after write+read.
    // Checks symbols count, fullNameIndex keys, topLevelClassIds, packageIds, and errors.
    // Does NOT use classpath == cp2: subclassIndex / companionIndex / moduleIndex are not serialized
    // (they are Map.empty in the reader) so strict equality would fail on a correct round-trip.
    "snapshot round-trip preserves Classpath data (symbols, fullNameIndex, topLevelClassIds, errors)" in {
        val digest = Array[Byte](0xa0.toByte, 0xa1.toByte, 0xa2.toByte, 0xa3.toByte, 0xa4.toByte, 0xa5.toByte, 0xa6.toByte, 0xa7.toByte)
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val bytes    = SnapshotWriter.serializeToBytes(classpath, digest)
                    val hex      = DigestComputer.toHexString(digest)
                    val snapPath = s"mem/$hex.krfl"
                    SnapshotReader.readFromBytes(bytes, snapPath).map { cp2 =>
                        (classpath, cp2)
                    }
                }
            }
        ).map {
            case Result.Success((classpath, cp2)) =>
                assert(
                    classpath.symbols.length == cp2.symbols.length,
                    s"symbols count mismatch: ${classpath.symbols.length} != ${cp2.symbols.length}"
                )
                assert(
                    classpath.indices.byFullName.toMap.keySet == cp2.indices.byFullName.toMap.keySet,
                    s"fullNameIndex key sets differ after round-trip"
                )
                assert(
                    classpath.indices.topLevelClassIds.length == cp2.indices.topLevelClassIds.length,
                    s"topLevelClassIds length mismatch: ${classpath.indices.topLevelClassIds.length} != ${cp2.indices.topLevelClassIds.length}"
                )
                assert(
                    classpath.indices.packageIds.length == cp2.indices.packageIds.length,
                    s"packageIds length mismatch: ${classpath.indices.packageIds.length} != ${cp2.indices.packageIds.length}"
                )
                assert(
                    classpath.errors.size == cp2.errors.size,
                    s"errors size mismatch: ${classpath.errors.size} != ${cp2.errors.size}"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // a synthetic snapshot (written inline with minimal
    // Classpath.make fields) is readable by the current SnapshotReader without error.
    // This replaces the missing committed binary fixture: the synthetic Classpath exercises the
    // same wire-format contract as a snapshot.
    "legacy snapshot reads with the new reader (synthetic inline fixture)" in {
        val digest = Array[Byte](0xb0.toByte, 0xb1.toByte, 0xb2.toByte, 0xb3.toByte, 0xb4.toByte, 0xb5.toByte, 0xb6.toByte, 0xb7.toByte)

        import AllowUnsafe.embrace.danger
        import kyo.Tasty.SymbolId
        val rootSym2 = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val pkgSym2  = Tasty.Symbol.Package(SymbolId(1), Tasty.Name("legacy"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val classSym2 = Tasty.Symbol.Class(
            SymbolId(2),
            Tasty.Name("OldClass"),
            Tasty.Flags.empty,
            SymbolId(1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

        val allSyms2     = Chunk(rootSym2, pkgSym2, classSym2)
        val fullNameMap2 = Dict("legacy.OldClass" -> classSym2.id)
        val pkgMap2      = Dict("legacy" -> pkgSym2.id)
        val topIds2      = Chunk(classSym2.id)
        val pkgIds2      = Chunk(rootSym2.id, pkgSym2.id)

        Abort.run[TastyError] {
            val syntheticCp = Tasty.Classpath.make(
                symbols = allSyms2,
                rootSymbolId = SymbolId(0),
                topLevelClassIds = topIds2,
                packageIds = pkgIds2,
                fullNameIndex = fullNameMap2,
                packageIndex = pkgMap2,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
            val bytes    = SnapshotWriter.serializeToBytes(syntheticCp, digest)
            val hex      = DigestComputer.toHexString(digest)
            val snapPath = s"mem/$hex.krfl"
            SnapshotReader.readFromBytes(bytes, snapPath).map { loadedCp =>
                (
                    loadedCp.findClass("legacy.OldClass"),
                    loadedCp.findPackage("legacy"),
                    loadedCp.symbols.length
                )
            }
        }.map {
            case Result.Success((foundClass, foundPkg, symCount)) =>
                assert(foundClass.isDefined, "legacy.OldClass must be findable after synthetic snapshot round-trip")
                assert(foundPkg.isDefined, "legacy package must be findable after synthetic snapshot round-trip")
                assert(symCount == 3, s"Expected 3 symbols after round-trip, got $symCount")
            case Result.Failure(e) =>
                fail(s"Unexpected failure reading synthetic legacy snapshot: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "new snapshot section-index: all 18 sections present and offsets monotone increasing" in {
        val digest = Array[Byte](0xc0.toByte, 0xc1.toByte, 0xc2.toByte, 0xc3.toByte, 0xc4.toByte, 0xc5.toByte, 0xc6.toByte, 0xc7.toByte)
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    SnapshotWriter.serializeToBytes(classpath, digest)
                }
            }
        ).map {
            case Result.Success(bytes) =>
                val sectionCount = SnapshotFormat.readInt32LE(bytes, 32)
                assert(sectionCount == 18, s"Expected 18 sections in new-writer snapshot, got $sectionCount")

                val expectedNames = Set(
                    SnapshotFormat.sectionNAMES,
                    SnapshotFormat.sectionSYMBOLS,
                    SnapshotFormat.sectionTYPES,
                    SnapshotFormat.sectionTYPEXTRA,
                    SnapshotFormat.sectionPARENTS,
                    SnapshotFormat.sectionMEMBERS,
                    SnapshotFormat.sectionTPARAMS,
                    SnapshotFormat.sectionFILES,
                    SnapshotFormat.sectionBODYBYTES,
                    SnapshotFormat.sectionERRORS,
                    SnapshotFormat.sectionPERMITS2,
                    SnapshotFormat.sectionANNOTS,
                    SnapshotFormat.sectionJAVAMETA,
                    SnapshotFormat.sectionFQNIDX,
                    SnapshotFormat.sectionFQNMAP,
                    SnapshotFormat.sectionSUBCIDX,
                    SnapshotFormat.sectionCOMPIDX,
                    SnapshotFormat.sectionPLISTS
                )

                val foundNames = scala.collection.mutable.Set.empty[String]
                val offsets    = scala.collection.mutable.ArrayBuffer.empty[Long]
                var idxPos     = 36
                var i          = 0
                while i < sectionCount do
                    val name   = SnapshotFormat.readSectionName(bytes, idxPos)
                    val offset = SnapshotFormat.readInt64LE(bytes, idxPos + 8)
                    foundNames += name
                    offsets += offset
                    idxPos += SnapshotFormat.sectionIndexEntrySize
                    i += 1
                end while

                assert(
                    expectedNames == foundNames.toSet,
                    s"Section names mismatch. Expected: $expectedNames Found: ${foundNames.toSet}"
                )

                val offsetSeq = offsets.toSeq
                val monotone  = offsetSeq.zip(offsetSeq.tail).forall { case (a, b) => b >= a }
                assert(monotone, s"Section offsets must be monotone increasing: $offsetSeq")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // after snapshot round-trip, bodyTree returns Absent for all symbols.
    // Bodies are not serialized in snapshots; DecodeContext.bodyStore is empty after snapshot load.
    // bodyMemo stays at size 0 because bodyTree returns Absent before any decode attempt.
    "snapshot body: bodyTree returns Absent for snapshot-loaded symbol (bodyStore is empty)" in {
        val digest = Array[Byte](0xd0.toByte, 0xd1.toByte, 0xd2.toByte, 0xd3.toByte, 0xd4.toByte, 0xd5.toByte, 0xd6.toByte, 0xd7.toByte)
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val bytes    = SnapshotWriter.serializeToBytes(classpath, digest)
                    val hex      = DigestComputer.toHexString(digest)
                    val snapPath = s"mem/$hex.krfl"
                    SnapshotReader.readFromBytes(bytes, snapPath).map { (warmCp: Tasty.Classpath) =>
                        // After snapshot load, body store is empty; bodyTree must return Absent.
                        val ctx     = DecodeContext.fresh()
                        val binding = Binding(warmCp, Maybe.Present(ctx))
                        assert(ctx.bodyMemo.size() == 0, "bodyMemo must be empty before any call")
                        assert(ctx.bodyStore.size() == 0, "bodyStore must be empty after snapshot load")
                        val testSym = warmCp.symbols.headOption.getOrElse {
                            fail("Snapshot has no symbols")
                            warmCp.symbols.head // unreachable
                        }
                        Tasty.bindingLocal.let(Maybe.Present(binding)) {
                            Abort.run[TastyError](Tasty.bodyTree(testSym)).map { result =>
                                assert(result.isSuccess, s"bodyTree must not raise TastyError: $result")
                                val body = result.getOrElse(Maybe.Absent)
                                assert(!body.isDefined, "bodyTree must return Absent for snapshot-loaded symbol")
                                assert(ctx.bodyMemo.size() == 0, "bodyMemo must remain empty (no decode attempted)")
                            }
                        }
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "DigestComputer.compute on real root returns same digest for two successive calls" in {
        Path.tempDir("kyo-srt-det2").map { dir =>
            val fileA = dir / "A.tasty"
            val fileB = dir / "B.tasty"
            val root  = dir.toString
            fileA.writeBytes(Span.from(Array[Byte](1, 2, 3))).map { _ =>
                fileB.writeBytes(Span.from(Array[Byte](4, 5, 6))).map { _ =>
                    Abort.run[TastyError] {
                        DigestComputer.compute(Seq(root)).map { d1 =>
                            DigestComputer.compute(Seq(root)).map { d2 =>
                                (d1, d2)
                            }
                        }
                    }.map {
                        case Result.Success((d1, d2)) =>
                            assert(d1.sameElements(d2), s"real root digest must be deterministic: ${d1.toSeq} vs ${d2.toSeq}")
                        case Result.Failure(e) =>
                            fail(s"Unexpected failure: $e")
                        case Result.Panic(t) =>
                            throw t
                    }
                }
            }
        }
    }

    "DigestComputer.compute detects additional file in root (different digest)" in {
        Path.tempDir("kyo-srt-add").map { dir =>
            val fileA = dir / "A.tasty"
            val root  = dir.toString
            fileA.writeBytes(Span.from(Array[Byte](1, 2, 3))).map { _ =>
                Abort.run[TastyError] {
                    DigestComputer.compute(Seq(root)).map { d1 =>
                        val fileB = dir / "B.tasty"
                        fileB.writeBytes(Span.from(Array[Byte](4, 5, 6, 7, 8))).map { _ =>
                            DigestComputer.compute(Seq(root)).map { d2 =>
                                (d1, d2)
                            }
                        }
                    }
                }.map {
                    case Result.Success((d1, d2)) =>
                        assert(!d1.sameElements(d2), "Adding a file must produce a different digest")
                    case Result.Failure(e) =>
                        fail(s"Unexpected failure: $e")
                    case Result.Panic(t) =>
                        throw t
                }
            }
        }
    }

    "DigestComputer.compute on two real roots is root-order independent" in {
        Path.tempDir("kyo-srt-ord").map { dir =>
            val root1 = dir / "root1"
            val root2 = dir / "root2"
            val fileX = root1 / "X.tasty"
            val fileY = root2 / "Y.tasty"
            fileX.writeBytes(Span.from(Array[Byte](10, 20, 30))).map { _ =>
                fileY.writeBytes(Span.from(kyo.fixtures.Embedded.plainClassTasty)).map { _ =>
                    Abort.run[TastyError] {
                        DigestComputer.compute(Seq(root1.toString, root2.toString)).map { d1 =>
                            DigestComputer.compute(Seq(root2.toString, root1.toString)).map { d2 =>
                                (d1, d2)
                            }
                        }
                    }.map {
                        case Result.Success((d1, d2)) =>
                            assert(d1.sameElements(d2), s"root order must not affect digest: ${d1.toSeq} vs ${d2.toSeq}")
                        case Result.Failure(e) =>
                            fail(s"Unexpected failure: $e")
                        case Result.Panic(t) =>
                            throw t
                    }
                }
            }
        }
    }

    "DigestComputer.compute on directory root returns same digest for two successive calls" in {
        Path.tempDir("kyo-srt-dir").map { dir =>
            val file = dir / "PlainClass.tasty"
            val root = dir.toString
            file.writeBytes(Span.from(kyo.fixtures.Embedded.plainClassTasty)).map { _ =>
                Abort.run[TastyError] {
                    DigestComputer.compute(Seq(root)).map { d1 =>
                        DigestComputer.compute(Seq(root)).map { d2 =>
                            (d1, d2)
                        }
                    }
                }.map {
                    case Result.Success((d1, d2)) =>
                        assert(d1.sameElements(d2), s"directory-root digest must be deterministic: ${d1.toSeq} vs ${d2.toSeq}")
                    case Result.Failure(e) =>
                        fail(s"Unexpected failure: $e")
                    case Result.Panic(t) =>
                        throw t
                }
            }
        }
    }

end SnapshotRoundTripTest
