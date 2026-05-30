package kyo

import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.snapshot.DigestComputer
import scala.collection.mutable

/** Tests for DigestComputer determinism.
  *
  * Verifies that the FNV-1a 64-bit computation produces identical digests for identical inputs and distinct digests for distinct inputs.
  * Uses an in-memory FileSource to stay cross-platform and avoid filesystem I/O.
  *
  * Pins: T2.
  */
class DigestComputerTest extends Test:

    final class SimpleMemoryFileSource extends FileSource:

        private val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty

        def add(path: String, bytes: Array[Byte]): Unit =
            files(path) = bytes

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

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(TastyError.FileNotFound(path))

    end SimpleMemoryFileSource

    // Test 1: same input produces same digest (determinism).
    //
    // Given: in-memory source with one .tasty file containing bytes [1, 2, 3].
    // When: DigestComputer.compute run twice on the same root.
    // Then: both digest arrays are equal element-by-element.
    // Pins: T2.
    "DigestComputer.compute for the same input bytes produces the same digest twice" in run {
        val src = SimpleMemoryFileSource()
        src.add("root/test.tasty", Array[Byte](1, 2, 3))
        Abort.run[TastyError]:
            DigestComputer.compute(Seq("root"), src).flatMap: d1 =>
                DigestComputer.compute(Seq("root"), src).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(d1.sameElements(d2), s"Same input must produce same digest: ${d1.toSeq} vs ${d2.toSeq}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // Test 2: different content produces different digest (content-hash mode).
    //
    // Given: two in-memory sources each with one .tasty file -- [1, 2, 3] vs [1, 2, 4].
    //        Same path and size so stat-based compute cannot distinguish them;
    //        computeParanoid hashes file content directly.
    // When: DigestComputer.computeParanoid run on each source.
    // Then: the two digest arrays are NOT equal element-by-element.
    // Pins: T2.
    "DigestComputer.computeParanoid for different input bytes produces different digests" in run {
        val src1 = SimpleMemoryFileSource()
        src1.add("root/test.tasty", Array[Byte](1, 2, 3))
        val src2 = SimpleMemoryFileSource()
        src2.add("root/test.tasty", Array[Byte](1, 2, 4))
        Abort.run[TastyError]:
            DigestComputer.computeParanoid(Seq("root"), src1).flatMap: d1 =>
                DigestComputer.computeParanoid(Seq("root"), src2).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(!d1.sameElements(d2), s"Different inputs must produce different digests but both were ${d1.toSeq}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end DigestComputerTest
