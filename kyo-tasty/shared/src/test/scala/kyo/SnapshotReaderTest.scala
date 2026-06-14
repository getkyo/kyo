package kyo

import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader

/** Tests for SnapshotReader version handling with old (minor=2) snapshots.
  *
  * breaking change: minor=4 is a breaking bump (PERMITS2/ANNOTS_/JAVAMETA sections added).
  * breaking change: minor=6 is a breaking bump (FQNMAP__ section added).
  * Snapshots with minor < 6 are rejected with SnapshotVersionMismatch to force cold re-decode.
  */
class SnapshotReaderTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // must be rejected with SnapshotVersionMismatch to force cold re-decode.
    "minor=2 snapshot (no PARENTS/MEMBERS/TPARAMS_ sections) is rejected with SnapshotVersionMismatch" in {
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
        val sectionMeta = sections.map { (name, bytes) =>
            val entry = (name, offset, bytes.length.toLong)
            offset += bytes.length
            entry
        }

        val totalSize = offset.toInt
        val buffer    = new Array[Byte](totalSize)

        // Magic
        buffer(0) = 'K'
        buffer(1) = 'R'
        buffer(2) = 'F'
        buffer(3) = 'L'
        // Version: major=1, minor=2 (old minor without TPARAMS_ section)
        buffer(4) = 1.toByte
        buffer(5) = 2.toByte
        // flags (LE = 0)
        SnapshotFormat.writeInt64LE(buffer, 8, 0L)
        // digest (zeros)
        // reserved (zeros)
        // section count
        SnapshotFormat.writeInt32LE(buffer, 32, sectionCount)
        // section index
        var idxPos = 36
        for (name, sectionOffset, sectionLen) <- sectionMeta do
            SnapshotFormat.writeSectionName(buffer, idxPos, name)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buffer, idxPos, sectionOffset)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buffer, idxPos, sectionLen)
            idxPos += 8
        end for
        // Copy payloads
        for ((_, sectionOffset, _), (_, bytes)) <- sectionMeta.zip(sections) do
            java.lang.System.arraycopy(bytes, 0, buffer, sectionOffset.toInt, bytes.length)

        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(buffer, "cache/minor2.krfl")
        }.map {
            case Result.Failure(e: TastyError.SnapshotVersionMismatch) =>
                // minor < 6 snapshots are rejected to force cold re-decode.
                assert(e.found.minor == 2, s"Expected found.minor == 2, got ${e.found.minor}")
                assert(
                    e.supported.minor == SnapshotFormat.minorVersion,
                    s"Expected supported.minor == ${SnapshotFormat.minorVersion}, got ${e.supported.minor}"
                )
                succeed
            case Result.Success(_) =>
                fail(s"Expected SnapshotVersionMismatch for minor=2 snapshot (below minimum of ${SnapshotFormat.minorVersion})")
            case Result.Failure(e) =>
                fail(s"Expected SnapshotVersionMismatch but got unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

end SnapshotReaderTest
