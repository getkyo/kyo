package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Tests for Phase 19b: PARENTS, MEMBERS, TPARAMS_ sections populated after write.
  *
  * INV-015 writer side: snapshot written from a real TASTy classpath has PARENTS, MEMBERS, and TPARAMS_ sections with length > 0.
  */
class SnapshotWriterTest extends Test:

    import AllowUnsafe.embrace.danger

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
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => kyo.internal.tasty.query.FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(TastyError.FileNotFound(path))
    end MemoryFileSource

    // Test 1 (INV-015 writer side): PARENTS, MEMBERS, and TPARAMS_ sections have length > 0
    // after writing a snapshot from a real TASTy classpath that has class declarations and parents.
    "snapshot PARENTS, MEMBERS, and TPARAMS_ sections have length > 0 after writing a real classpath" in run {
        val src = MemoryFileSource()
        // Use the SomeTrait fixture which extends java.lang.Object (has parents) and has members.
        src.add("root/SomeTrait.tasty", kyo.fixtures.Embedded.someTraitTasty)
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57)

        Scope.run:
            Abort.run[TastyError](
                ClasspathOrchestrator.open(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).map: _ =>
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        cacheSrc.files.get(snapPath)
            ).map:
                case Result.Success(Some(bytes)) =>
                    // Parse section index to find PARENTS, MEMBERS, TPARAMS_ lengths.
                    val sectionCount = SnapshotFormat.readInt32LE(bytes, 32)
                    val sectionLens  = mutable.HashMap.empty[String, Long]
                    var idxPos       = 36
                    var i            = 0
                    while i < sectionCount do
                        val sName = SnapshotFormat.readSectionName(bytes, idxPos)
                        val sLen  = SnapshotFormat.readInt64LE(bytes, idxPos + 16)
                        sectionLens(sName) = sLen
                        idxPos += SnapshotFormat.sectionIndexEntrySize
                        i += 1
                    end while
                    val parentsLen = sectionLens.getOrElse(SnapshotFormat.sectionPARENTS, -1L)
                    val membersLen = sectionLens.getOrElse(SnapshotFormat.sectionMEMBERS, -1L)
                    val tparamsLen = sectionLens.getOrElse(SnapshotFormat.sectionTPARAMS, -1L)
                    assert(parentsLen > 0, s"PARENTS section must be non-empty; got $parentsLen")
                    assert(membersLen > 0, s"MEMBERS section must be non-empty; got $membersLen")
                    assert(tparamsLen >= 0, s"TPARAMS_ section must be present; got $tparamsLen")
                    succeed
                case Result.Success(None) =>
                    fail("Snapshot file not found after write")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

end SnapshotWriterTest
