package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** JVM-only tests for SnapshotRoundTrip that require `FileChannel.map` (mmap).
  *
  * Per post-audit (cross-platform parity), the four digest leaves (T-J1, T-J3, T-J4, T-J5) were migrated to
  * `shared/src/test/scala/kyo/SnapshotDigestTest.scala` using `MemoryFileSource`. G16b (post-close sym.body) was migrated to
  * `shared/src/test/scala/kyo/SnapshotRoundTripTest.scala` since sym.body will be cross-platform when implements it.
  *
  * Remaining leaf:
  *   - G16a: writes a snapshot to a real temp file and reads it back via mmap (`PlatformMmapReader.readMapped`). Tests the mmap path itself,
  *     which is a JVM `FileChannel.map` concern; no cross-platform analog.
  */
class SnapshotRoundTripJvmTest extends Test:

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

    // Test G16a: mmap-loaded snapshot has same FQN set as cold-loaded classpath (jvmOnly).
    // Uses PlatformFileSource (real filesystem) to write the snapshot to a temp file, then
    // loads it via readMapped. Verifies that the mmap path loads successfully and the FQN set matches
    // the cold-loaded classpath, confirming no TASTy re-decode happened.
    "mmap-loaded snapshot has same FQN set as cold-loaded classpath" in run {
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

end SnapshotRoundTripJvmTest
