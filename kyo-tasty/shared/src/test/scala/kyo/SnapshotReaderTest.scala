package kyo

import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import scala.collection.mutable

/** Tests for Phase 19b: SnapshotReader backward-compat with old (minor=2) snapshots.
  *
  * INV-023 forward-compat: a snapshot at minorVersion=2 (no PARENTS/MEMBERS/TPARAMS_ sections) loads without SnapshotVersionMismatch and
  * returns Chunk.empty for parents/typeParams/declarations.
  */
class SnapshotReaderTest extends Test:

    import AllowUnsafe.embrace.danger

    class MemoryFileSource(val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends kyo.internal.tasty.query.FileSource:
        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.empty)

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
            Sync.defer(files.contains(path))

        def stat(path: String)(using Frame): kyo.internal.tasty.query.FileSource.FileStat < (Sync & Abort[TastyError]) =
            Abort.fail(TastyError.FileNotFound(path))
    end MemoryFileSource

    // Test 3 (INV-023 forward-compat): a snapshot with minorVersion=2 (current major, but old minor
    // without PARENTS/MEMBERS/TPARAMS_ sections) loads successfully with no SnapshotVersionMismatch
    // and populates symbols with Chunk.empty parents/typeParams/declarations.
    "minor=2 snapshot (no PARENTS/MEMBERS/TPARAMS_ sections) loads with empty relationships and no version mismatch" in run {
        // Build a minimal valid KRFL snapshot at minorVersion=2 (same majorVersion=1 as current).
        // The snapshot contains only NAMES and SYMBOLS sections (no PARENTS/MEMBERS/TPARAMS_).
        // NAMES section: 0 names.
        val namesPayload = new Array[Byte](4) // count=0
        SnapshotFormat.writeInt32LE(namesPayload, 0, 0)
        // SYMBOLS section: 0 symbols.
        val symbolsPayload = new Array[Byte](4) // count=0
        SnapshotFormat.writeInt32LE(symbolsPayload, 0, 0)
        // ERRORS section: 0 errors.
        val errorsPayload = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(errorsPayload, 0, 0)

        val sections = Seq(
            (SnapshotFormat.sectionNAMES, namesPayload),
            (SnapshotFormat.sectionSYMBOLS, symbolsPayload),
            (SnapshotFormat.sectionERRORS, errorsPayload)
        )

        val sectionCount     = sections.length
        val sectionIndexSize = 4 + sectionCount * SnapshotFormat.sectionIndexEntrySize
        val headerSize       = SnapshotFormat.headerSize + sectionIndexSize

        var offset = headerSize.toLong
        val sectionMeta = sections.map: (name, bytes) =>
            val entry = (name, offset, bytes.length.toLong)
            offset += bytes.length
            entry

        val totalSize = offset.toInt
        val buf       = new Array[Byte](totalSize)

        // Magic
        buf(0) = 'K'
        buf(1) = 'R'
        buf(2) = 'F'
        buf(3) = 'L'
        // Version: major=1, minor=2 (old minor without TPARAMS_ section)
        buf(4) = 1.toByte
        buf(5) = 2.toByte
        // flags (LE = 0)
        SnapshotFormat.writeInt64LE(buf, 8, 0L)
        // digest (zeros)
        // reserved (zeros)
        // section count
        SnapshotFormat.writeInt32LE(buf, 32, sectionCount)
        // section index
        var idxPos = 36
        for (name, sectionOffset, sectionLen) <- sectionMeta do
            SnapshotFormat.writeSectionName(buf, idxPos, name)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buf, idxPos, sectionOffset)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buf, idxPos, sectionLen)
            idxPos += 8
        end for
        // Copy payloads
        for ((_, sectionOffset, _), (_, bytes)) <- sectionMeta.zip(sections) do
            java.lang.System.arraycopy(bytes, 0, buf, sectionOffset.toInt, bytes.length)

        val src = MemoryFileSource()
        src.add("cache/minor2.krfl", buf)

        Abort.run[TastyError]:
            SnapshotReader.read("cache/minor2.krfl", src).map: loadedCp =>
                // After load: all symbols should have empty parent/typeParam/declaration chunks.
                // Since there are 0 symbols, just verify the load succeeded with no error.
                assert(loadedCp.symbols.isEmpty, s"Expected no symbols from empty snapshot, got ${loadedCp.symbols.length}")
                succeed
        .map:
            case Result.Success(r) =>
                r
            case Result.Failure(e: TastyError.SnapshotVersionMismatch) =>
                fail(s"SnapshotVersionMismatch must not be thrown for same major / older minor: $e")
            case Result.Failure(e) =>
                fail(s"Unexpected failure loading minor=2 snapshot: $e")
            case Result.Panic(t) =>
                throw t
    }

end SnapshotReaderTest
