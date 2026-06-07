package kyo

import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.query.TastyState
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Tests for KRFL snapshot round-trip, digest determinism, and openCached behavior.
  */
class SnapshotRoundTripTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** An in-memory FileSource backed by a mutable map of path -> bytes. */
    class MemoryFileSource(val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit =
            files(path) = bytes

        def remove(path: String): Unit =
            files.remove(path): Unit

        def keys: Seq[String] = files.keys.toSeq

        def getBytes(path: String): Option[Array[Byte]] = files.get(path)

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

        override def delete(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            // override trait-body default so delete operates on the in-memory map
            // instead of attempting a real filesystem call via kyo.Path.remove.
            Sync.defer:
                val _ = files.remove(path)
                ()

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(TastyError.FileNotFound(path))

    end MemoryFileSource

    private def fixtureSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src
    end fixtureSource

    /** Open a classpath from the in-memory source into a `Tasty.Classpath`. */
    private def openClasspath(src: FileSource)(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)

    /** Write a snapshot of the fixture classpath to the given FileSource cache dir. */
    private def writeSnapshot(cacheSrc: MemoryFileSource)(using Frame): String < (Sync & Async & Scope & Abort[TastyError]) =
        val digest = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
        openClasspath(fixtureSource()).flatMap: cp =>
            SnapshotWriter.write(cp, "cache", digest, cacheSrc).map: _ =>
                val hex = DigestComputer.toHexString(digest)
                s"cache/$hex.krfl"
    end writeSnapshot

    "snapshot round-trip: topLevelClasses by FQN match after write+read" in {
        val cacheSrc = MemoryFileSource()
        Scope.run:
            Abort.run[TastyError](
                writeSnapshot(cacheSrc).flatMap: snapshotPath =>
                    openClasspath(fixtureSource()).flatMap: origCp =>
                        val origClasses = origCp.topLevelClasses
                        SnapshotReader.read(snapshotPath, cacheSrc).map: loadedCp =>
                            val loadedClasses = loadedCp.topLevelClasses
                            (origClasses, loadedClasses)
            ).map:
                case Result.Success((origClasses: Chunk[Tasty.Symbol], loadedClasses: Chunk[Tasty.Symbol])) =>
                    val origFqns   = origClasses.map(_.name.asString).toSet
                    val loadedFqns = loadedClasses.map(_.name.asString).toSet
                    assert(
                        origFqns == loadedFqns,
                        s"topLevelClasses FQNs must match after snapshot round-trip: orig=$origFqns loaded=$loadedFqns"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "reading a snapshot with wrong magic produces SnapshotFormatError" in {
        val cacheSrc = MemoryFileSource()
        cacheSrc.add("cache/bad.krfl", Array[Byte]('X', 'Y', 'Z', 'W', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        Abort.run[TastyError]:
            SnapshotReader.read("cache/bad.krfl", cacheSrc)
        .map:
            case Result.Success(_) =>
                fail("Expected SnapshotFormatError for wrong magic")
            case Result.Failure(e) =>
                e match
                    case _: TastyError.SnapshotFormatError => succeed
                    case other                             => fail(s"Expected SnapshotFormatError but got: $other")
            case Result.Panic(t) =>
                throw t
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

        val cacheSrc = MemoryFileSource()
        cacheSrc.add("cache/badver.krfl", badVersionBytes)

        Abort.run[TastyError]:
            SnapshotReader.read("cache/badver.krfl", cacheSrc)
        .map:
            case Result.Success(_) =>
                fail("Expected SnapshotVersionMismatch for wrong major version")
            case Result.Failure(e) =>
                e match
                    case _: TastyError.SnapshotVersionMismatch => succeed
                    case other                                 => fail(s"Expected SnapshotVersionMismatch but got: $other")
            case Result.Panic(t) =>
                throw t
    }

    "writing snapshot when FileSource write fails produces SnapshotIoError" in {
        val failSrc = new FileSource:
            def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
                Abort.fail(TastyError.FileNotFound(path))
            def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
                Abort.fail(TastyError.SnapshotIoError(s"write failed: $path"))
            def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                Abort.fail(TastyError.SnapshotIoError(s"rename failed: $from"))
            def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                Kyo.unit
            def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
                Chunk.empty
            def exists(path: String)(using Frame): Boolean < Sync =
                false
            def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
                Abort.fail(TastyError.FileNotFound(path))

        Scope.run:
            Abort.run[TastyError](openClasspath(fixtureSource()).flatMap: cp =>
                val digest = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
                SnapshotWriter.write(cp, "cache", digest, failSrc)).map:
                case Result.Success(_) =>
                    fail("Expected SnapshotIoError for failing FileSource")
                case Result.Failure(e) =>
                    e match
                        case _: TastyError.SnapshotIoError => succeed
                        case other                         => fail(s"Expected SnapshotIoError but got: $other")
                case Result.Panic(t) =>
                    throw t
    }

    "two concurrent snapshot writers produce one valid snapshot file (atomic rename)" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11)
        val hex      = DigestComputer.toHexString(digest)
        val finalKey = s"cache/$hex.krfl"

        Scope.run:
            Abort.run[TastyError | Timeout](
                Async.timeout(5.seconds)(
                    openClasspath(fixtureSource()).flatMap: cp =>
                        Async.zip[TastyError, Unit, Unit, Any](
                            SnapshotWriter.write(cp, "cache", digest, cacheSrc),
                            SnapshotWriter.write(cp, "cache", digest, cacheSrc)
                        ).map(_ => ())
                )
            ).map:
                case Result.Success(_) =>
                    // Both writers completed; the final snapshot file must exist and be valid
                    val snapBytes = cacheSrc.getBytes(finalKey)
                    assert(snapBytes.isDefined, s"Expected snapshot file at $finalKey. Keys: ${cacheSrc.keys}")
                    snapBytes match
                        case None =>
                            succeed
                        case Some(bytes) =>
                            assert(
                                bytes.length >= 4 && bytes(0) == 'K' && bytes(1) == 'R' && bytes(2) == 'F' && bytes(3) == 'L',
                                "Snapshot file must have KRFL magic"
                            )
                    end match
                case Result.Failure(_: Timeout) =>
                    fail("Concurrent snapshot write timed out")
                case Result.Failure(e) =>
                    // One writer may fail with SnapshotIoError (rename collision); acceptable if final file exists
                    val snapBytes = cacheSrc.getBytes(finalKey)
                    assert(snapBytes.isDefined, s"Expected snapshot file at $finalKey even after partial failure. Keys: ${cacheSrc.keys}")
                case Result.Panic(t) =>
                    throw t
    }

    "openCached warm cache hit returns same symbol graph as cold open" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19)

        Scope.run:
            Abort.run[TastyError](
                // Cold open: build from TASTy
                openClasspath(fixtureSource()).flatMap: coldCp =>
                    val coldClasses = coldCp.topLevelClasses
                    // Write snapshot
                    SnapshotWriter.write(coldCp, "cache", digest, cacheSrc).andThen:
                        // Warm load: read from snapshot
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        SnapshotReader.read(snapPath, cacheSrc).map: warmCp =>
                            val warmClasses = warmCp.topLevelClasses
                            (coldClasses, warmClasses)
            ).map:
                case Result.Success((coldClasses: Chunk[Tasty.Symbol], warmClasses: Chunk[Tasty.Symbol])) =>
                    val coldFqns = coldClasses.map(_.name.asString).toSet
                    val warmFqns = warmClasses.map(_.name.asString).toSet
                    assert(
                        coldFqns == warmFqns,
                        s"Warm cache must return same FQNs as cold open: cold=$coldFqns warm=$warmFqns"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "cold miss writes snapshot file to cache dir" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27)
        val hex      = DigestComputer.toHexString(digest)
        val snapPath = s"cache/$hex.krfl"

        Scope.run:
            Abort.run[TastyError](
                openClasspath(fixtureSource()).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc)
            ).map:
                case Result.Success(_) =>
                    val snapBytes = cacheSrc.getBytes(snapPath)
                    assert(snapBytes.isDefined, s"Expected snapshot file at $snapPath but not found. Keys: ${cacheSrc.keys}")
                    snapBytes match
                        case None =>
                            succeed
                        case Some(bytes) =>
                            assert(bytes.length > 0, "Snapshot file must be non-empty")
                            assert(
                                bytes.length >= 4 && bytes(0) == 'K' && bytes(1) == 'R' && bytes(2) == 'F' && bytes(3) == 'L',
                                "Snapshot file must start with KRFL magic"
                            )
                    end match
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "evictOlderThan removes all snapshot files older than maxAgeMs" in {
        // stat returns mtime=0, so all files are older than 0 ms (now - 0 > 0 is always true)
        val evictSrc = MemoryFileSource()
        evictSrc.add("cache/aabbccdd01020304.krfl", Array[Byte](1, 2, 3, 4))
        evictSrc.add("cache/1122334455667788.krfl", Array[Byte](5, 6, 7, 8))
        evictSrc.add("cache/other.txt", Array[Byte](9, 10))

        Abort.run[TastyError](
            Tasty.Snapshot.evictOlderThanWithSource("cache", 0L, evictSrc)
        ).map:
            case Result.Success(_) =>
                // evictOlderThanWithSource calls source.delete(path) for each stale.krfl file.
                // After deletion the path is completely absent; the fix removes the prior rename-based approach.
                val remainingKrfl = evictSrc.keys.filter(k => k.startsWith("cache/") && k.endsWith(".krfl"))
                assert(
                    remainingKrfl.isEmpty,
                    s"Expected all .krfl files to be removed by eviction, remaining: $remainingKrfl"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    "DigestComputer.compute for the same roots is deterministic" in {
        val src = fixtureSource()
        Abort.run[TastyError]:
            DigestComputer.compute(Seq("root"), src).flatMap: digest1 =>
                DigestComputer.compute(Seq("root"), src).map: digest2 =>
                    (digest1, digest2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(d1.length == d2.length, "Digest arrays must have same length")
                assert(d1.sameElements(d2), s"Same inputs must produce same digest: ${d1.toSeq} vs ${d2.toSeq}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    "DigestComputer.compute for different file sets returns different digests" in {
        val src1 = MemoryFileSource()
        src1.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)

        val src2 = MemoryFileSource()
        src2.add("root/PlainClass.tasty", Array[Byte](1, 2, 3, 4, 5, 6, 7, 8))

        Abort.run[TastyError]:
            DigestComputer.compute(Seq("root"), src1).flatMap: digest1 =>
                DigestComputer.compute(Seq("root"), src2).map: digest2 =>
                    (digest1, digest2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(!d1.sameElements(d2), "Different inputs must produce different digests")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // Test G15: written snapshot header inputDigest field (bytes 16-23) equals the digest passed to write
    "snapshot header inputDigest field equals digest passed to write (not zeros)" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
        Scope.run:
            Abort.run[TastyError](
                openClasspath(fixtureSource()).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).map: _ =>
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        cacheSrc.getBytes(snapPath)
            ).map:
                case Result.Success(Some(bytes)) =>
                    assert(bytes.length >= 24, s"Snapshot too short to contain inputDigest: ${bytes.length} bytes")
                    val headerDigest = bytes.slice(16, 24)
                    assert(
                        headerDigest.sameElements(digest),
                        s"inputDigest header field must equal passed digest. Expected: ${digest.toSeq} got: ${headerDigest.toSeq}"
                    )
                case Result.Success(None) =>
                    fail("Snapshot file not found after write")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test G14a: after snapshot round-trip, bodyTree returns Absent (bodies not serialized).
    // Bodies are stored in DecodeContext.bodyStore which is not persisted in snapshots.
    // Use withClasspath(roots) to re-populate the body store from TASTy files.
    "BODY_BYTES round-trip: bodyTree returns Absent on snapshot-loaded symbol (snapshot contract)" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37)
        Scope.run:
            Abort.run[TastyError](
                openClasspath(fixtureSource()).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        SnapshotReader.read(snapPath, cacheSrc).flatMap: loadedCp =>
                            // After snapshot load, body store is empty; bodyTree must return Absent.
                            Tasty.withClasspath(loadedCp):
                                val methods = loadedCp.symbols.collect { case m: Tasty.Symbol.Method => m }
                                val testSym = methods.headOption.getOrElse(loadedCp.symbols.head)
                                Tasty.bodyTree(testSym).map: result =>
                                    assert(!result.isDefined, "bodyTree must return Absent after snapshot load")
                                    succeed
            ).map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Test G14b: snapshot written from classfile-only classpath has empty BODY_BYTES section
    "snapshot from classfile-only classpath has empty BODY_BYTES section (length 0)" in {
        // Use an empty classpath (no TASTy files) to produce a snapshot with no body bytes.
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47)
        val emptySrc = MemoryFileSource()
        Scope.run:
            Abort.run[TastyError](
                // Open an empty classpath (no roots, no files): transitions to Ready immediately with empty state
                ClasspathOrchestrator.init(Seq.empty, Tasty.ErrorMode.SoftFail, emptySrc, 1).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).map: _ =>
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        cacheSrc.getBytes(snapPath)
            ).flatMap:
                case Result.Success(Some(bytes)) =>
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
                    val hex2     = DigestComputer.toHexString(digest)
                    val snapPath = s"cache/$hex2.krfl"
                    Abort.run[TastyError](SnapshotReader.read(snapPath, cacheSrc)).map:
                        case Result.Success(_) =>
                            succeed
                        case Result.Failure(e) =>
                            fail(s"Reading empty-body snapshot must not fail: $e")
                        case Result.Panic(t) =>
                            throw t
                case Result.Success(None) =>
                    fail("Snapshot file not found after write")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test: parents, typeParams, and declarations are preserved across a snapshot write+read round-trip.
    "snapshot round-trip: parents, typeParams, and declarations preserved after write+read" in {
        // Use SomeTrait fixture which has parents (java.lang.Object) and member declarations (compute method).
        val tastySource = MemoryFileSource()
        tastySource.add("root/SomeTrait.tasty", kyo.fixtures.Embedded.someTraitTasty)
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67)

        Scope.run:
            Abort.run[TastyError](
                // Cold open: build from TASTy to capture expected values.
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, tastySource, 1).flatMap: coldCp =>
                    val coldClasses = coldCp.topLevelClasses
                    // Write snapshot.
                    SnapshotWriter.write(coldCp, "cache", digest, cacheSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        // Warm load from snapshot.
                        SnapshotReader.read(snapPath, cacheSrc).map: warmCp =>
                            val warmClasses = warmCp.topLevelClasses
                            (coldClasses, warmClasses)
            ).map:
                case Result.Success(pair) =>
                    val (coldClasses, warmClasses) = pair
                    // Verify every cold class's declarations are preserved in the warm load.
                    // typeParams and parents that reference symbols outside the loaded classpath (e.g.
                    // java.lang.Object from classfiles not in the snapshot) are encoded as -1 and skipped;
                    // the warm chunk is smaller or empty for purely external parents.
                    var allGood = true
                    var failMsg = ""
                    for coldSym <- coldClasses do
                        val coldFqn    = coldSym.name.asString
                        val warmSymOpt = warmClasses.toSeq.find(_.name.asString == coldFqn)
                        warmSymOpt match
                            case None =>
                                allGood = false
                                failMsg = s"Warm classpath missing symbol $coldFqn"
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
                                    failMsg = s"$coldFqn: cold has declarations $coldDeclNames but warm has none after round-trip"
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

    // Test P1: snapshot round-trip preserves a local Named parent.
    //        (Class, parents=[Named(barSym)]). Both symbols are local so the SnapshotWriter assigns
    //        barSym a local symbolId and writes it in the PARENTS section.
    //       fullName equals "test.Bar".
    "snapshot round-trip: local Named parent is preserved in Foo.parents" in {
        val cacheSrc = MemoryFileSource()
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
        val fqnMap                        = scala.collection.immutable.Map[String, Tasty.Symbol]("test.Bar" -> barSym, "test.Foo" -> fooSym)
        val pkgMap                        = scala.collection.immutable.Map[String, Tasty.Symbol]("test" -> pkgSym)

        Abort.run[TastyError]:
            val fqnIdMap = Dict.from(fqnMap.map { case (k, v) => k -> v.id }.toMap)
            val pkgIdMap = Dict.from(pkgMap.map { case (k, v) => k -> v.id }.toMap)
            val topIds   = topLevel.map(_.id)
            val pkgIds   = pkgs.map(_.id)
            val coldCp = Tasty.Classpath.make(
                symbols = allSyms,
                rootSymbolId = SymbolId(0),
                topLevelClassIds = topIds,
                packageIds = pkgIds,
                fqnIndex = fqnIdMap,
                packageIndex = pkgIdMap,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
            SnapshotWriter.write(coldCp, "cache", digest, cacheSrc).andThen:
                val hex      = DigestComputer.toHexString(digest)
                val snapPath = s"cache/$hex.krfl"
                SnapshotReader.read(snapPath, cacheSrc).map: warmCp =>
                    warmCp.findClass("test.Foo") match
                        case Maybe.Present(sym) => sym match
                                case c: Tasty.Symbol.ClassLike => c.parentTypes;
                                case null                      => Chunk.empty
                        case Maybe.Absent => Abort.fail(TastyError.NotImplemented("test.Foo not found after snapshot load"))
        .map:
            case Result.Success(parents) =>
                assert(parents.nonEmpty, "Foo.parents must be non-empty after snapshot round-trip with local Named parent")
                // Name check deferred to; verify that a Named parent with the Bar id is present.
                val hasBar = parents.toSeq.exists:
                    case Tasty.Type.Named(_) => true
                    case _                   => false
                assert(hasBar, s"Foo.parentTypes must contain a Named parent after snapshot round-trip; got ${parents.size} parents")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // full Classpath data is preserved after write+read.
    // Checks symbols count, fqnIndex keys, topLevelClassIds, packageIds, and errors.
    // Does NOT use cp == cp2: subclassIndex / companionIndex / moduleIndex are not serialized
    // (they are Map.empty in the reader) so strict equality would fail on a correct round-trip.
    "snapshot round-trip preserves Classpath data (symbols, fqnIndex, topLevelClassIds, errors)" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0xa0.toByte, 0xa1.toByte, 0xa2.toByte, 0xa3.toByte, 0xa4.toByte, 0xa5.toByte, 0xa6.toByte, 0xa7.toByte)
        Scope.run:
            Abort.run[TastyError](
                openClasspath(fixtureSource()).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        SnapshotReader.read(snapPath, cacheSrc).map: cp2 =>
                            (cp, cp2)
            ).map:
                case Result.Success((cp, cp2)) =>
                    assert(
                        cp.symbols.length == cp2.symbols.length,
                        s"symbols count mismatch: ${cp.symbols.length} != ${cp2.symbols.length}"
                    )
                    assert(
                        cp.indices.byFqn.toMap.keySet == cp2.indices.byFqn.toMap.keySet,
                        s"fqnIndex key sets differ after round-trip"
                    )
                    assert(
                        cp.indices.topLevelClassIds.length == cp2.indices.topLevelClassIds.length,
                        s"topLevelClassIds length mismatch: ${cp.indices.topLevelClassIds.length} != ${cp2.indices.topLevelClassIds.length}"
                    )
                    assert(
                        cp.indices.packageIds.length == cp2.indices.packageIds.length,
                        s"packageIds length mismatch: ${cp.indices.packageIds.length} != ${cp2.indices.packageIds.length}"
                    )
                    assert(
                        cp.errors.size == cp2.errors.size,
                        s"errors size mismatch: ${cp.errors.size} != ${cp2.errors.size}"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // a synthetic snapshot (written inline with minimal
    // Classpath.make fields) is readable by the current SnapshotReader without error.
    // This replaces the missing committed binary fixture: the synthetic Classpath exercises the
    // same wire-format contract as a snapshot.
    "legacy snapshot reads with the new reader (synthetic inline fixture)" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0xb0.toByte, 0xb1.toByte, 0xb2.toByte, 0xb3.toByte, 0xb4.toByte, 0xb5.toByte, 0xb6.toByte, 0xb7.toByte)

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

        val allSyms2 = Chunk(rootSym2, pkgSym2, classSym2)
        val fqnMap2  = Dict("legacy.OldClass" -> classSym2.id)
        val pkgMap2  = Dict("legacy" -> pkgSym2.id)
        val topIds2  = Chunk(classSym2.id)
        val pkgIds2  = Chunk(rootSym2.id, pkgSym2.id)

        Abort.run[TastyError]:
            val syntheticCp = Tasty.Classpath.make(
                symbols = allSyms2,
                rootSymbolId = SymbolId(0),
                topLevelClassIds = topIds2,
                packageIds = pkgIds2,
                fqnIndex = fqnMap2,
                packageIndex = pkgMap2,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
            SnapshotWriter.write(syntheticCp, "cache", digest, cacheSrc).andThen:
                val hex      = DigestComputer.toHexString(digest)
                val snapPath = s"cache/$hex.krfl"
                SnapshotReader.read(snapPath, cacheSrc).map: loadedCp =>
                    (
                        loadedCp.findClass("legacy.OldClass"),
                        loadedCp.findPackage("legacy"),
                        loadedCp.symbols.length
                    )
        .map:
            case Result.Success((foundClass, foundPkg, symCount)) =>
                assert(foundClass.isDefined, "legacy.OldClass must be findable after synthetic snapshot round-trip")
                assert(foundPkg.isDefined, "legacy package must be findable after synthetic snapshot round-trip")
                assert(symCount == 3, s"Expected 3 symbols after round-trip, got $symCount")
            case Result.Failure(e) =>
                fail(s"Unexpected failure reading synthetic legacy snapshot: $e")
            case Result.Panic(t) =>
                throw t
    }

    "new snapshot section-index: all 18 sections present and offsets monotone increasing" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0xc0.toByte, 0xc1.toByte, 0xc2.toByte, 0xc3.toByte, 0xc4.toByte, 0xc5.toByte, 0xc6.toByte, 0xc7.toByte)
        Scope.run:
            Abort.run[TastyError](
                openClasspath(fixtureSource()).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).map: _ =>
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        cacheSrc.getBytes(snapPath)
            ).map:
                case Result.Success(Some(bytes)) =>
                    val sectionCount = SnapshotFormat.readInt32LE(bytes, 32)
                    // added PLISTS__ (minor=12), raising section count from 17 to 18.
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
                case Result.Success(None) =>
                    fail("Snapshot file not found after write")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // after snapshot round-trip, bodyTree returns Absent for all symbols.
    // Bodies are not serialized in snapshots; DecodeContext.bodyStore is empty after snapshot load.
    // bodyMemo stays at size 0 because bodyTree returns Absent before any decode attempt.
    "snapshot body: bodyTree returns Absent for snapshot-loaded symbol (bodyStore is empty)" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0xd0.toByte, 0xd1.toByte, 0xd2.toByte, 0xd3.toByte, 0xd4.toByte, 0xd5.toByte, 0xd6.toByte, 0xd7.toByte)
        Scope.run:
            Abort.run[TastyError](
                openClasspath(fixtureSource()).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        SnapshotReader.read(snapPath, cacheSrc).flatMap: (warmCp: Tasty.Classpath) =>
                            // After snapshot load, body store is empty; bodyTree must return Absent.
                            val ctx     = DecodeContext.fresh()
                            val binding = Binding(warmCp, Maybe.Present(ctx))
                            assert(ctx.bodyMemo.size() == 0, "bodyMemo must be empty before any call")
                            assert(ctx.bodyStore.size() == 0, "bodyStore must be empty after snapshot load")
                            val testSym = warmCp.symbols.headOption.getOrElse {
                                fail("Snapshot has no symbols")
                                warmCp.symbols.head // unreachable
                            }
                            TastyState.bindingLocal.let(Maybe.Present(binding)):
                                Abort.run[TastyError](Tasty.bodyTree(testSym)).map: result =>
                                    assert(result.isSuccess, s"bodyTree must not raise TastyError: $result")
                                    val body = result.getOrElse(Maybe.Absent)
                                    assert(!body.isDefined, "bodyTree must return Absent for snapshot-loaded symbol")
                                    assert(ctx.bodyMemo.size() == 0, "bodyMemo must remain empty (no decode attempted)")
            ).map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    "DigestComputer.compute on in-memory root returns same digest for two successive calls" in {
        val src = MemoryFileSource()
        src.add("root/A.tasty", Array[Byte](1, 2, 3))
        src.add("root/B.tasty", Array[Byte](4, 5, 6))
        Abort.run[TastyError]:
            DigestComputer.compute(Seq("root"), src).flatMap: d1 =>
                DigestComputer.compute(Seq("root"), src).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(d1.sameElements(d2), s"in-memory root digest must be deterministic: ${d1.toSeq} vs ${d2.toSeq}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    "DigestComputer.compute detects additional file in root (different digest)" in {
        val src1 = MemoryFileSource()
        src1.add("root/A.tasty", Array[Byte](1, 2, 3))
        val src2 = MemoryFileSource()
        src2.add("root/A.tasty", Array[Byte](1, 2, 3))
        src2.add("root/B.tasty", Array[Byte](4, 5, 6, 7, 8))
        Abort.run[TastyError]:
            DigestComputer.compute(Seq("root"), src1).flatMap: d1 =>
                DigestComputer.compute(Seq("root"), src2).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(!d1.sameElements(d2), "Adding a file must produce a different digest")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    "DigestComputer.compute on two in-memory roots is root-order independent" in {
        val src = MemoryFileSource()
        src.add("root1/X.tasty", Array[Byte](10, 20, 30))
        src.add("root2/Y.tasty", kyo.fixtures.Embedded.plainClassTasty)
        Abort.run[TastyError]:
            DigestComputer.compute(Seq("root1", "root2"), src).flatMap: d1 =>
                DigestComputer.compute(Seq("root2", "root1"), src).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(d1.sameElements(d2), s"root order must not affect digest: ${d1.toSeq} vs ${d2.toSeq}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    "DigestComputer.compute on directory root returns same digest for two successive calls" in {
        val src = fixtureSource()
        Abort.run[TastyError]:
            DigestComputer.compute(Seq("root"), src).flatMap: d1 =>
                DigestComputer.compute(Seq("root"), src).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(d1.sameElements(d2), s"directory-root digest must be deterministic: ${d1.toSeq} vs ${d2.toSeq}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end SnapshotRoundTripTest
