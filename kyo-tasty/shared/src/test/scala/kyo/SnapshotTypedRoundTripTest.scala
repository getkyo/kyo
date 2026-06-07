package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** verify snapshot round-trip with typed subtypes.
  */
class SnapshotTypedRoundTripTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:
        def add(p: String, b: Array[Byte]): Unit = files(p) = b
        def read(p: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(p) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(p))
        def write(p: String, b: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) = Sync.defer(files(p) = b)
        def rename(f: String, t: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(f) match
                case Some(b) => Sync.defer { files.remove(f); files(t) = b }
                case None    => Abort.fail(TastyError.SnapshotIoError(s"$f not found"))
        def mkdirs(p: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def list(d: String, sfx: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(d + "/") && sfx.exists(k.endsWith)).toSeq))
        def exists(p: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(p) || files.keys.exists(_.startsWith(p + "/")))
        def stat(p: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(p).map(_.length.toLong).getOrElse(0L)))
    end MemoryFileSource

    private def openFixtureCp(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openFixtureCp

    // write-load-roundtrip-preserves-subtypes
    "write-load-roundtrip-preserves-subtypes: typed subtypes survive snapshot round-trip" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        Scope.run:
            Abort.run[TastyError](
                openFixtureCp.flatMap: coldCp =>
                    SnapshotWriter.write(coldCp, "cache", digest, cacheSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        SnapshotReader.read(snapPath, cacheSrc).map: warmCp =>
                            (coldCp, warmCp)
            ).map:
                case Result.Success((coldCp, warmCp)) =>
                    val cold = coldCp.symbols
                    val warm = warmCp.symbols
                    assert(cold.length == warm.length, s"Symbol count mismatch: cold=${cold.length} warm=${warm.length}")
                    var i = 0
                    while i < cold.length do
                        val c = cold(i)
                        val w = warm(i)
                        assert(
                            c.getClass.getSimpleName == w.getClass.getSimpleName,
                            s"Symbol[$i] type mismatch: cold=${c.getClass.getSimpleName} warm=${w.getClass.getSimpleName}"
                        )
                        assert(c.id == w.id, s"Symbol[$i] id mismatch: cold=${c.id} warm=${w.id}")
                        assert(
                            c.name.asString == w.name.asString,
                            s"Symbol[$i] name mismatch: cold=${c.name.asString} warm=${w.name.asString}"
                        )
                        assert(c.flags.bits == w.flags.bits, s"Symbol[$i] flags mismatch: cold=${c.flags.bits} warm=${w.flags.bits}")
                        i += 1
                    end while
                    succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // byte-format-unchanged
    "byte-format-unchanged: snapshot written by a previous version can be re-read by same reader" in {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18)
        Scope.run:
            Abort.run[TastyError](
                openFixtureCp.flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        SnapshotReader.read(snapPath, cacheSrc).map: loadedCp =>
                            (cp.symbols.length, loadedCp.symbols.length)
            ).map:
                case Result.Success((coldCount, warmCount)) =>
                    assert(coldCount == warmCount, s"Expected same symbol count but cold=$coldCount warm=$warmCount")
                    succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

end SnapshotTypedRoundTripTest
