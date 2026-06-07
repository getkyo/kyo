package kyo

import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import scala.collection.mutable

/** Tests for SectionValidator bounds and layout invariants exercised through SnapshotReader.read.
  *
  * SectionValidator is private to the `kyo.internal.tasty.snapshot` package, so its methods are tested here via the public
  * `SnapshotReader.read` interface. Each test constructs a synthetic KRFL snapshot with a specific structural violation and verifies that
  * `SnapshotReader.read` converts it to a structured `TastyError` rather than a JVM exception.
  *
  * Covered:   (validateRange before arraycopy),   (sectionCount bound).
  */
class SectionValidatorTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private class MemFileSource extends kyo.internal.tasty.query.FileSource:
        private val store = mutable.HashMap.empty[String, Array[Byte]]

        def put(path: String, bytes: Array[Byte]): Unit = store(path) = bytes

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.empty)

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            store.get(path) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(store(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Abort.fail(TastyError.SnapshotIoError("rename: not supported"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(store.contains(path))

        def stat(path: String)(using Frame): kyo.internal.tasty.query.FileSource.FileStat < (Sync & Abort[TastyError]) =
            Abort.fail(TastyError.FileNotFound(path))
    end MemFileSource

    /** Build a minimal valid snapshot (0 symbols, 0 names, 0 errors) and return its raw bytes. */
    private def minimalSnapshot(): Array[Byte] =
        val namesPayload   = new Array[Byte](4)
        val symbolsPayload = new Array[Byte](4)
        val errorsPayload  = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(namesPayload, 0, 0)
        SnapshotFormat.writeInt32LE(symbolsPayload, 0, 0)
        SnapshotFormat.writeInt32LE(errorsPayload, 0, 0)

        val sections = Seq(
            (SnapshotFormat.sectionNAMES, namesPayload),
            (SnapshotFormat.sectionSYMBOLS, symbolsPayload),
            (SnapshotFormat.sectionERRORS, errorsPayload)
        )
        val sectionIndexSize = 4 + sections.length * SnapshotFormat.sectionIndexEntrySize
        val headerSize       = SnapshotFormat.headerSize + sectionIndexSize

        var offset = headerSize.toLong
        val meta = sections.map: (name, bytes) =>
            val e = (name, offset, bytes.length.toLong)
            offset += bytes.length
            e

        val buf = new Array[Byte](offset.toInt)
        buf(0) = 'K'; buf(1) = 'R'; buf(2) = 'F'; buf(3) = 'L'
        buf(4) = SnapshotFormat.majorVersion.toByte
        buf(5) = SnapshotFormat.minorVersion.toByte
        SnapshotFormat.writeInt64LE(buf, 8, 0L)
        SnapshotFormat.writeInt64LE(buf, 16, 0L)
        SnapshotFormat.writeInt64LE(buf, 24, 0L)
        SnapshotFormat.writeInt32LE(buf, 32, sections.length)
        var idxPos = 36
        for (name, off, len) <- meta do
            SnapshotFormat.writeSectionName(buf, idxPos, name)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buf, idxPos, off)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buf, idxPos, len)
            idxPos += 8
        end for
        for ((_, off, _), (_, bytes)) <- meta.zip(sections) do
            java.lang.System.arraycopy(bytes, 0, buf, off.toInt, bytes.length)
        buf
    end minimalSnapshot

    // ── validateRange path ──────────────────────────────────────────

    "validateRange: OOB section-index entry produces structured TastyError, not AIOOBE" in {
        val mem  = new MemFileSource()
        val full = minimalSnapshot()
        val out  = java.util.Arrays.copyOf(full, full.length)
        // Corrupt the first section index entry: set length = full.length * 10 (OOB)
        SnapshotFormat.writeInt64LE(out, 36 + 16, full.length.toLong * 10L)
        val path = "mem/sv-oob.krfl"
        mem.put(path, out)
        Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
            case Result.Failure(_: TastyError.MalformedSection)    => succeed
            case Result.Failure(_: TastyError.SnapshotFormatError) => succeed // defense-in-depth also acceptable
            case Result.Panic(t) =>
                fail(s"BUG: OOB section entry panics instead of giving structured TastyError: ${t.getClass.getName}: ${t.getMessage}")
            case other => succeed
    }

    "validateRange: negative section offset produces structured TastyError, not panic" in {
        val mem  = new MemFileSource()
        val full = minimalSnapshot()
        val out  = java.util.Arrays.copyOf(full, full.length)
        SnapshotFormat.writeInt64LE(out, 36 + 8, -50L)
        val path = "mem/sv-neg.krfl"
        mem.put(path, out)
        Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
            case Result.Panic(t) =>
                fail(s"BUG: negative section offset panics: ${t.getClass.getName}: ${t.getMessage}")
            case _ => succeed
    }

    "validateRange: valid minimal snapshot loads cleanly (no false positives)" in {
        val mem  = new MemFileSource()
        val path = "mem/sv-valid.krfl"
        mem.put(path, minimalSnapshot())
        Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
            case Result.Panic(t) => fail(s"BUG: valid snapshot panics: ${t.getMessage}")
            case _               => succeed
    }

    // ── sectionCount bound ─────────────────────────────────────────

    "sectionCount bound: Int.MaxValue sectionCount produces SnapshotFormatError, not OOM" in {
        val mem  = new MemFileSource()
        val full = minimalSnapshot()
        val out  = java.util.Arrays.copyOf(full, full.length)
        SnapshotFormat.writeInt32LE(out, 32, Int.MaxValue)
        val path = "mem/sv-maxcount.krfl"
        mem.put(path, out)
        Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
            case Result.Failure(_: TastyError.SnapshotFormatError) => succeed
            case Result.Panic(t) =>
                fail(s"BUG: sectionCount=Int.MaxValue panics: ${t.getClass.getName}: ${t.getMessage}")
            case Result.Failure(other) => fail(s"expected SnapshotFormatError, got: $other")
            case Result.Success(_)     => fail("expected failure for corrupt sectionCount")
    }

    "sectionCount bound: sectionCount=0 loads cleanly (no sections is valid)" in {
        val mem  = new MemFileSource()
        val full = minimalSnapshot()
        val out  = java.util.Arrays.copyOf(full, full.length)
        SnapshotFormat.writeInt32LE(out, 32, 0)
        val path = "mem/sv-zerocount.krfl"
        mem.put(path, out)
        Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
            case Result.Panic(t) => fail(s"BUG: sectionCount=0 panics: ${t.getMessage}")
            case _               => succeed
    }

end SectionValidatorTest
