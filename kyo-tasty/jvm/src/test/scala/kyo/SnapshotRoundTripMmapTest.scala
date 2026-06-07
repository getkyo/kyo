package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Writes a snapshot to a real temp file and reads it back via FileChannel.map, exercising the mmap reader path
  * (PlatformMmapReader.readMapped) end-to-end.
  */
class SnapshotRoundTripMmapTest extends kyo.test.Test[Any]:

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

    private def openClasspath(src: FileSource)(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)

    "mmap-loaded snapshot has same FQN set as cold-loaded classpath" in {
        val fixtSrc = fixtureSource()
        val digest  = Array[Byte](0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57)
        val tmpDir  = java.io.File.createTempFile("kyo-tasty-mmap-test", "").getAbsolutePath
        val _       = new java.io.File(tmpDir).delete()
        val _       = new java.io.File(tmpDir).mkdirs()
        val platSrc = PlatformFileSource.get

        Scope.run:
            Abort.run[TastyError](
                openClasspath(fixtSrc).flatMap: origCp =>
                    val origClasses = origCp.topLevelClasses
                    SnapshotWriter.write(origCp, tmpDir, digest, platSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"$tmpDir/$hex.krfl"
                        SnapshotReader.readMapped(snapPath, platSrc).map: warmCp =>
                            val warmClasses = warmCp.topLevelClasses
                            (
                                origClasses.map(_.name.asString).toSet,
                                warmClasses.map(_.name.asString).toSet
                            )
            ).map:
                case Result.Success((origFqns: Set[String] @unchecked, warmFqns: Set[String] @unchecked)) =>
                    assert(
                        origFqns == warmFqns,
                        s"mmap-loaded FQNs must match cold-loaded FQNs: cold=$origFqns mmap=$warmFqns"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

end SnapshotRoundTripMmapTest
