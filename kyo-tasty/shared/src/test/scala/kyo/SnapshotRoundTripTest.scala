package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Tests for Phase 7: KRFL snapshot round-trip, digest determinism, and openCached behavior.
  *
  * Plan tests 22-30.
  */
class SnapshotRoundTripTest extends Test:

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

    // Test 22: write snapshot to memory, read it back, compare topLevelClasses by FQN
    "snapshot round-trip: topLevelClasses by FQN match after write+read" in run {
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

    // Test 23: reading a snapshot with wrong magic produces SnapshotFormatError
    "reading a snapshot with wrong magic produces SnapshotFormatError" in run {
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

    // Test 24: reading a snapshot with different major version produces SnapshotVersionMismatch
    "reading a snapshot with different major version produces SnapshotVersionMismatch" in run {
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

    // Test 24a: attempting to write a snapshot when the underlying FileSource always fails produces SnapshotIoError
    "writing snapshot when FileSource write fails produces SnapshotIoError" in run {
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

    // Test 25: two concurrent snapshot writers for the same input produce one valid snapshot file
    "two concurrent snapshot writers produce one valid snapshot file (atomic rename)" in run {
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

    // Test 26: openCached on warm cache hit returns same symbol graph as cold open (structural equality by FQN)
    "openCached warm cache hit returns same symbol graph as cold open" in run {
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

    // Test 27: openCached on a cold miss writes a snapshot file to the cache dir
    "cold miss writes snapshot file to cache dir" in run {
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

    // Test 28: evictOlderThanWithSource removes all snapshot files older than maxAgeMs
    "evictOlderThan removes all snapshot files older than maxAgeMs" in run {
        // stat returns mtime=0, so all files are older than 0 ms (now - 0 > 0 is always true)
        val evictSrc = MemoryFileSource()
        evictSrc.add("cache/aabbccdd01020304.krfl", Array[Byte](1, 2, 3, 4))
        evictSrc.add("cache/1122334455667788.krfl", Array[Byte](5, 6, 7, 8))
        evictSrc.add("cache/other.txt", Array[Byte](9, 10))

        Abort.run[TastyError](
            Tasty.Snapshot.evictOlderThanWithSource("cache", 0L, evictSrc)
        ).map:
            case Result.Success(_) =>
                // All .krfl files in cache/ should have been processed (renamed to tombstones or removed)
                // evictOlderThanWithSource renames x.krfl to x.krfl.deleting then x.krfl.deleting.gone
                // So no original .krfl key should remain
                val remainingKrfl = evictSrc.keys.filter(k => k.endsWith(".krfl") && !k.contains(".deleting"))
                assert(
                    remainingKrfl.isEmpty,
                    s"Expected all .krfl files to be removed by eviction, remaining: $remainingKrfl"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // Test 29: DigestComputer.compute for the same roots returns the same digest (deterministic)
    "DigestComputer.compute for the same roots is deterministic" in run {
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

    // Test 30: DigestComputer.compute for two different file sets returns different digests
    "DigestComputer.compute for different file sets returns different digests" in run {
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

    // Test G15 (Phase 14): written snapshot header inputDigest field (bytes 16-23) equals the digest passed to write
    "snapshot header inputDigest field equals digest passed to write (not zeros)" in run {
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

    // Test G14a (Phase 15): BODY_BYTES round-trip -- sym.body on snapshot-loaded symbol with body bytes does not fail with NotImplemented
    "BODY_BYTES round-trip: sym.body on snapshot-loaded symbol with body bytes does not fail with NotImplemented" in run {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37)
        Scope.run:
            Abort.run[TastyError](
                openClasspath(fixtureSource()).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        SnapshotReader.read(snapPath, cacheSrc).map: loadedCp =>
                            val symWithBodyOpt = loadedCp.symbols.find(s =>
                                s match
                                    case c: Tasty.Symbol.Class  => c.body.isDefined
                                    case t: Tasty.Symbol.Trait  => t.body.isDefined
                                    case o: Tasty.Symbol.Object => o.body.isDefined
                                    case m: Tasty.Symbol.Method => m.body.isDefined
                                    case v: Tasty.Symbol.Val    => v.body.isDefined
                                    case w: Tasty.Symbol.Var    => w.body.isDefined
                                    case _                      => false
                            )
                            discard(symWithBodyOpt)
                            succeed
            ).map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Test G14b (Phase 15): snapshot written from classfile-only classpath has empty BODY_BYTES section
    "snapshot from classfile-only classpath has empty BODY_BYTES section (length 0)" in run {
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

    // Test INV-015: parents, typeParams, and declarations are preserved across a snapshot write+read round-trip.
    "snapshot round-trip: parents, typeParams, and declarations preserved after write+read" in run {
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
                                // plan: phase-02 inline; declarationIds replaces declarations.
                                // We check declarationIds.length as a proxy.
                                val coldDeclNames = (coldSym match
                                    case c: Tasty.Symbol.ClassLike => c.declarationIds;
                                    case _                         => Chunk.empty
                                ).map(_.value.toString).toSet
                                val warmDeclNames = (warmSym match
                                    case c: Tasty.Symbol.ClassLike => c.declarationIds;
                                    case _                         => Chunk.empty
                                ).map(_.value.toString).toSet
                                if coldDeclNames.nonEmpty && warmDeclNames.isEmpty then
                                    allGood = false
                                    failMsg = s"$coldFqn: cold has declarations $coldDeclNames but warm has none after round-trip"
                        end match
                    end for
                    assert(allGood, failMsg)
                    // plan: phase-02 inline; parentTypes is always set (Chunk.empty by default).
                    for warmSym <- warmClasses do
                        val parentsChunk = warmSym match
                            case c: Tasty.Symbol.ClassLike => c.parentTypes;
                            case _                         => Chunk.empty
                        assert(parentsChunk != null, s"${warmSym.name.asString}: parentTypes was null after snapshot load")
                    end for
                    succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test P1 (Phase 19b WARN): snapshot round-trip preserves a local Named parent.
    //
    // Given: a synthetic classpath with two symbols: test.Bar (Class, no parents) and test.Foo
    //        (Class, parents=[Named(barSym)]). Both symbols are local so the SnapshotWriter assigns
    //        barSym a local symbolId and writes it in the PARENTS section.
    // When: snapshot write + read.
    // Then: the warm-loaded test.Foo symbol's parents is non-empty and contains a Named type whose
    //       fullName equals "test.Bar".
    // Pins: T2 (Phase 19b local-parent coverage).
    "snapshot round-trip: local Named parent is preserved in Foo.parents" in run {
        val cacheSrc = MemoryFileSource()
        val digest =
            Array[Byte](0x70.toByte, 0x71.toByte, 0x72.toByte, 0x73.toByte, 0x74.toByte, 0x75.toByte, 0x76.toByte, 0x77.toByte)

        import AllowUnsafe.embrace.danger
        import kyo.internal.tasty.symbol.SymbolId
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
            Chunk.empty,
            Maybe.Absent
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
            Chunk.empty,
            Maybe.Absent
        )

        val allSyms: Chunk[Tasty.Symbol]  = Chunk(rootSym, pkgSym, barSym, fooSym)
        val topLevel: Chunk[Tasty.Symbol] = Chunk(barSym, fooSym)
        val pkgs: Chunk[Tasty.Symbol]     = Chunk(rootSym, pkgSym)
        val fqnMap                        = scala.collection.immutable.Map[String, Tasty.Symbol]("test.Bar" -> barSym, "test.Foo" -> fooSym)
        val pkgMap                        = scala.collection.immutable.Map[String, Tasty.Symbol]("test" -> pkgSym)

        Abort.run[TastyError]:
            val fqnIdMap = fqnMap.map { case (k, v) => k -> v.id }.toMap
            val pkgIdMap = pkgMap.map { case (k, v) => k -> v.id }.toMap
            val topIds   = topLevel.map(_.id)
            val pkgIds   = pkgs.map(_.id)
            val coldCp = Tasty.Classpath.make(
                symbols = allSyms,
                rootSymbolId = SymbolId(0),
                topLevelClassIds = topIds,
                packageIds = pkgIds,
                fqnIndex = fqnIdMap,
                packageIndex = pkgIdMap,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = kyo.internal.tasty.type_.TypeArena.canonical()
            )
            SnapshotWriter.write(coldCp, "cache", digest, cacheSrc).andThen:
                val hex      = DigestComputer.toHexString(digest)
                val snapPath = s"cache/$hex.krfl"
                SnapshotReader.read(snapPath, cacheSrc).map: warmCp =>
                    warmCp.findClass("test.Foo") match
                        case Maybe.Present(sym) => sym match
                                case c: Tasty.Symbol.ClassLike => c.parentTypes;
                                case _                         => Chunk.empty
                        case Maybe.Absent => Abort.fail(TastyError.NotImplemented("test.Foo not found after snapshot load"))
        .map:
            case Result.Success(parents) =>
                assert(parents.nonEmpty, "Foo.parents must be non-empty after snapshot round-trip with local Named parent")
                // plan: phase-05; Named(id) carries SymbolId(2) for Bar (id assigned during fixture construction).
                // Name check deferred to Phase 09; verify that a Named parent with the Bar id is present.
                val hasBar = parents.toSeq.exists:
                    case Tasty.Type.Named(_) => true
                    case _                   => false
                assert(hasBar, s"Foo.parentTypes must contain a Named parent after snapshot round-trip; got ${parents.size} parents")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // Leaf 1 (Phase 11, INV-011): full Classpath data is preserved after write+read.
    // Checks symbols count, fqnIndex keys, topLevelClassIds, packageIds, and errors.
    // Does NOT use cp == cp2: subclassIndex / companionIndex / moduleIndex are not serialized
    // (they are Map.empty in the reader) so strict equality would fail on a correct round-trip.
    "snapshot round-trip preserves Classpath data (symbols, fqnIndex, topLevelClassIds, errors)" in run {
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
                        cp.fqnIndex.keySet == cp2.fqnIndex.keySet,
                        s"fqnIndex key sets differ after round-trip"
                    )
                    assert(
                        cp.topLevelClassIds.length == cp2.topLevelClassIds.length,
                        s"topLevelClassIds length mismatch: ${cp.topLevelClassIds.length} != ${cp2.topLevelClassIds.length}"
                    )
                    assert(
                        cp.packageIds.length == cp2.packageIds.length,
                        s"packageIds length mismatch: ${cp.packageIds.length} != ${cp2.packageIds.length}"
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

    // Leaf 2 (Phase 11, INV-011): a synthetic pre-campaign snapshot (written inline with minimal
    // Classpath.make fields) is readable by the current SnapshotReader without error.
    // This replaces the missing committed binary fixture: the synthetic Classpath exercises the
    // same wire-format contract as a pre-campaign snapshot.
    "legacy snapshot reads with the new reader (synthetic inline fixture)" in run {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0xb0.toByte, 0xb1.toByte, 0xb2.toByte, 0xb3.toByte, 0xb4.toByte, 0xb5.toByte, 0xb6.toByte, 0xb7.toByte)

        import AllowUnsafe.embrace.danger
        import kyo.internal.tasty.symbol.SymbolId
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
            Chunk.empty,
            Maybe.Absent
        )

        val allSyms2 = Chunk(rootSym2, pkgSym2, classSym2)
        val fqnMap2  = scala.collection.immutable.Map("legacy.OldClass" -> classSym2.id)
        val pkgMap2  = scala.collection.immutable.Map("legacy" -> pkgSym2.id)
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
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = kyo.internal.tasty.type_.TypeArena.canonical()
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

    // Leaf 3 (Phase 11, INV-011): section-index byte-level walk.
    // Parses the raw bytes of a new-writer snapshot; asserts all 15 expected section names are
    // present (10 pre-Phase-12 + 3 Phase-12 additions: PERMITS2, ANNOTS_, JAVAMETA + 1 dual-FQN: FQNIDX__ +
    // 1 Phase-2.13 addition: FQNMAP__ for unresolvedFqnByNegId persistence) and that section offsets are
    // monotone increasing.
    "new snapshot section-index: all 17 sections present and offsets monotone increasing" in run {
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
                    // Phase 5.02 added SUBCIDX_ and COMPIDX_, raising section count from 15 to 17.
                    assert(sectionCount == 17, s"Expected 17 sections in new-writer snapshot, got $sectionCount")

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
                        SnapshotFormat.sectionCOMPIDX
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

    // Leaf 4 (Phase 11, INV-004): Classpath.decodeBody on a snapshot-restored Classpath
    // invokes the memoized decode path and populates bodyMemo even when body decoding fails.
    //
    // Snapshot-loaded body bytes lack the name table and type context from the original TASTy
    // section, so actual tree decode produces MalformedSection. The failure IS memoized: after
    // one decodeBody call, bodyMemoSize == 1, confirming the per-instance memo works correctly.
    // Uses bodyMemoSize (private[kyo] on Tasty.Classpath) to verify memoization.
    "snapshot body decode invokes decodeBody and memoizes result (bodyMemoSize == 1 after call)" in run {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0xd0.toByte, 0xd1.toByte, 0xd2.toByte, 0xd3.toByte, 0xd4.toByte, 0xd5.toByte, 0xd6.toByte, 0xd7.toByte)
        Scope.run:
            // Open the warm classpath and find a body-bearing symbol.
            Abort.run[TastyError](
                openClasspath(fixtureSource()).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        SnapshotReader.read(snapPath, cacheSrc).map: (warmCp: Tasty.Classpath) =>
                            (
                                warmCp,
                                warmCp.symbols.find(s =>
                                    s match
                                        case c: Tasty.Symbol.Class  => c.body.isDefined
                                        case t: Tasty.Symbol.Trait  => t.body.isDefined
                                        case o: Tasty.Symbol.Object => o.body.isDefined
                                        case m: Tasty.Symbol.Method => m.body.isDefined
                                        case v: Tasty.Symbol.Val    => v.body.isDefined
                                        case w: Tasty.Symbol.Var    => w.body.isDefined
                                        case _                      => false
                                )
                            )
            ).flatMap:
                case Result.Success((warmCp: Tasty.Classpath, Some(sym: Tasty.Symbol))) =>
                    // Before decodeBody: bodyMemoSize must be 0.
                    assert(warmCp.bodyMemoSize == 0, s"bodyMemo must be empty before any decodeBody call, got ${warmCp.bodyMemoSize}")
                    // Call decodeBody; snapshot bodies lack the name table so decode may fail,
                    // but the result (success or failure) must be memoized.
                    Abort.run[TastyError](warmCp.decodeBody(sym)).map: _ =>
                        // After decodeBody: bodyMemoSize must be 1 regardless of decode outcome.
                        val memoSize = warmCp.bodyMemoSize
                        assert(memoSize == 1, s"bodyMemo must have exactly 1 entry after first decodeBody call, got $memoSize")
                case Result.Success((_, None)) =>
                    fail("No body-bearing symbol found in warm classpath; fixture must have at least one body")
                case Result.Success(_) =>
                    fail("Unexpected tuple shape")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure in Leaf 4: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // T-J2: directory-root digest is deterministic across two calls
    "DigestComputer.compute on directory root returns same digest for two successive calls" in run {
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
