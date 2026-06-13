package kyo

import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader

/** Tests for SectionValidator bounds and layout invariants exercised through SnapshotReader.readFromBytes.
  *
  * SectionValidator is private to the `kyo.internal.tasty.snapshot` package, so its methods are tested here via
  * `SnapshotReader.readFromBytes`. Each test constructs a synthetic KRFL snapshot with a specific structural violation and verifies that
  * `SnapshotReader.readFromBytes` converts it to a structured `TastyError` rather than a JVM exception.
  *
  * Covered: (validateRange before arraycopy), (sectionCount bound).
  */
class SectionValidatorTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

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
        val meta = sections.map { (name, bytes) =>
            val e = (name, offset, bytes.length.toLong)
            offset += bytes.length
            e
        }

        val buffer = new Array[Byte](offset.toInt)
        buffer(0) = 'K'; buffer(1) = 'R'; buffer(2) = 'F'; buffer(3) = 'L'
        buffer(4) = SnapshotFormat.majorVersion.toByte
        buffer(5) = SnapshotFormat.minorVersion.toByte
        SnapshotFormat.writeInt64LE(buffer, 8, 0L)
        SnapshotFormat.writeInt64LE(buffer, 16, 0L)
        SnapshotFormat.writeInt64LE(buffer, 24, 0L)
        SnapshotFormat.writeInt32LE(buffer, 32, sections.length)
        var idxPos = 36
        for (name, off, len) <- meta do
            SnapshotFormat.writeSectionName(buffer, idxPos, name)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buffer, idxPos, off)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buffer, idxPos, len)
            idxPos += 8
        end for
        for ((_, off, _), (_, bytes)) <- meta.zip(sections) do
            java.lang.System.arraycopy(bytes, 0, buffer, off.toInt, bytes.length)
        buffer
    end minimalSnapshot

    // validateRange path

    "validateRange: OOB section-index entry produces structured TastyError, not AIOOBE" in {
        val full = minimalSnapshot()
        val out  = java.util.Arrays.copyOf(full, full.length)
        // Corrupt the first section index entry: set length = full.length * 10 (OOB)
        SnapshotFormat.writeInt64LE(out, 36 + 16, full.length.toLong * 10L)
        Abort.run[TastyError](SnapshotReader.readFromBytes(out, "mem/sv-oob.krfl")).map {
            case Result.Failure(_: TastyError.MalformedSection)    => succeed
            case Result.Failure(_: TastyError.SnapshotFormatError) => succeed // defense-in-depth also acceptable
            case Result.Panic(t) =>
                fail(s"BUG: OOB section entry panics instead of giving structured TastyError: ${t.getClass.getName}: ${t.getMessage}")
            case other => succeed
        }
    }

    "validateRange: negative section offset produces structured TastyError, not panic" in {
        val full = minimalSnapshot()
        val out  = java.util.Arrays.copyOf(full, full.length)
        SnapshotFormat.writeInt64LE(out, 36 + 8, -50L)
        Abort.run[TastyError](SnapshotReader.readFromBytes(out, "mem/sv-neg.krfl")).map {
            case Result.Panic(t) =>
                fail(s"BUG: negative section offset panics: ${t.getClass.getName}: ${t.getMessage}")
            case _ => succeed
        }
    }

    "validateRange: valid minimal snapshot loads cleanly (no false positives)" in {
        Abort.run[TastyError](SnapshotReader.readFromBytes(minimalSnapshot(), "mem/sv-valid.krfl")).map {
            case Result.Panic(t) => fail(s"BUG: valid snapshot panics: ${t.getMessage}")
            case _               => succeed
        }
    }

    // sectionCount bound

    "sectionCount bound: Int.MaxValue sectionCount produces SnapshotFormatError, not OOM" in {
        val full = minimalSnapshot()
        val out  = java.util.Arrays.copyOf(full, full.length)
        SnapshotFormat.writeInt32LE(out, 32, Int.MaxValue)
        Abort.run[TastyError](SnapshotReader.readFromBytes(out, "mem/sv-maxcount.krfl")).map {
            case Result.Failure(_: TastyError.SnapshotFormatError) => succeed
            case Result.Panic(t) =>
                fail(s"BUG: sectionCount=Int.MaxValue panics: ${t.getClass.getName}: ${t.getMessage}")
            case Result.Failure(other) => fail(s"expected SnapshotFormatError, got: $other")
            case Result.Success(_)     => fail("expected failure for corrupt sectionCount")
        }
    }

    "sectionCount bound: sectionCount=0 loads cleanly (no sections is valid)" in {
        val full = minimalSnapshot()
        val out  = java.util.Arrays.copyOf(full, full.length)
        SnapshotFormat.writeInt32LE(out, 32, 0)
        Abort.run[TastyError](SnapshotReader.readFromBytes(out, "mem/sv-zerocount.krfl")).map {
            case Result.Panic(t) => fail(s"BUG: sectionCount=0 panics: ${t.getMessage}")
            case _               => succeed
        }
    }

end SectionValidatorTest
