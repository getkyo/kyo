package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.SectionIndex
import kyo.internal.tasty.symbol.Interner

/** Tests for SectionIndex.read bounds checks (Phase 03a, findings C4 and INV-010). */
class SectionIndexTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Build a names array with `n` entries, each named "name0", "name1", etc. */
    private def makeNames(n: Int): Array[Tasty.Name] =
        val interner = new Interner(numShards = 4, initialShardCapacity = 8)
        Array.tabulate(n) { i =>
            val s     = s"name$i"
            val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            Tasty.Name.wrap(interner.intern(bytes, 0, bytes.length))
        }
    end makeNames

    /** Encode an unsigned Nat in big-endian base-128 (TASTy: last byte has 0x80 SET, others have 0x80 CLEAR). */
    private def encodeNat(n: Int): Array[Byte] =
        if n < 128 then
            Array((n | 0x80).toByte)
        else
            Array(((n >> 7) & 0x7f).toByte, ((n & 0x7f) | 0x80).toByte)
    end encodeNat

    // Test (Phase 03a C4): nameRef out-of-range yields MalformedSection
    "nameRef out-of-range yields MalformedSection" in run {
        // names.length == 3; first section entry has nameRef=99 (out of range).
        val names        = makeNames(3)
        val sectionBytes = encodeNat(99) ++ encodeNat(0)
        val view         = ByteView(sectionBytes)
        Abort.run[TastyError] {
            SectionIndex.read(view, names)
        }.map { result =>
            result match
                case Result.Failure(TastyError.MalformedSection("SectionIndex", reason)) =>
                    assert(
                        reason.contains("nameRef=99") || reason.contains("out of range"),
                        s"Expected reason to contain 'nameRef=99' or 'out of range' but was: $reason"
                    )
                case other =>
                    fail(s"Expected MalformedSection but got: $other")
        }
    }

    // Test (Phase 03a INV-010): negative sectionLen yields MalformedSection
    "negative sectionLen yields MalformedSection" in run {
        // names.length == 1; nameRef=0 (valid).
        // sectionLen encoded as 5-byte Nat: 0x7f,0x7f,0x7f,0x7f,0xff.
        // Decoding as Int via readNat accumulates 127*(1+128+16384+2097152+268435456) overflowing to negative.
        // The 5th byte has 0x80 SET (terminating) so the overflow guard (>=5 bytes) does not fire.
        val names         = makeNames(1)
        val nameRefBytes  = encodeNat(0)
        val sectionLenNeg = Array(0x7f.toByte, 0x7f.toByte, 0x7f.toByte, 0x7f.toByte, 0xff.toByte)
        val sectionBytes  = nameRefBytes ++ sectionLenNeg
        val view          = ByteView(sectionBytes)
        Abort.run[TastyError] {
            SectionIndex.read(view, names)
        }.map { result =>
            result match
                case Result.Failure(TastyError.MalformedSection("SectionIndex", _)) =>
                    succeed
                case other =>
                    fail(s"Expected MalformedSection but got: $other")
        }
    }

end SectionIndexTest
