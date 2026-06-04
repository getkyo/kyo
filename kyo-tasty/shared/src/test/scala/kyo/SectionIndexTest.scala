package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.SectionIndex

/** Tests for SectionIndex.read bounds checks (Phase 03a, findings C4 and INV-010). */
class SectionIndexTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Build a names array with `n` entries, each named "name0", "name1", etc. */
    private def makeNames(n: Int): Array[Tasty.Name] =
        Array.tabulate(n) { i =>
            val s     = s"name$i"
            val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            Tasty.Name(kyo.internal.tasty.binary.Utf8.decode(bytes, 0, bytes.length))
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
                case Result.Failure(TastyError.MalformedSection("SectionIndex", reason, _)) =>
                    assert(
                        reason.contains("nameRef=99") || reason.contains("out of range"),
                        s"Expected reason to contain 'nameRef=99' or 'out of range' but was: $reason"
                    )
                case other =>
                    fail(s"Expected MalformedSection but got: $other")
        }
    }

    // Test (Phase 03a INV-010 / updated Phase 08): oversized sectionLen yields MalformedSection
    "negative sectionLen yields MalformedSection" in run {
        // names.length == 1; nameRef=0 (valid).
        // sectionLen encoded as 5-byte Nat: 0x7f,0x7f,0x7f,0x7f,0xff.
        // Pre-Phase-08: Int accumulator overflows to negative; sectionLen < 0 guard fires.
        // Post-Phase-08 (Q-002 delegation to readLongNat): readLongNat returns 34359738367L;
        // readNat truncates to 34359738367L.toInt = -1; sectionLen < 0 guard fires as before.
        // Both paths produce MalformedSection("SectionIndex", ...).
        val names         = makeNames(1)
        val nameRefBytes  = encodeNat(0)
        val sectionLenNeg = Array(0x7f.toByte, 0x7f.toByte, 0x7f.toByte, 0x7f.toByte, 0xff.toByte)
        val sectionBytes  = nameRefBytes ++ sectionLenNeg
        val view          = ByteView(sectionBytes)
        Abort.run[TastyError] {
            SectionIndex.read(view, names)
        }.map { result =>
            result match
                case Result.Failure(TastyError.MalformedSection("SectionIndex", _, _)) =>
                    succeed
                case other =>
                    fail(s"Expected MalformedSection but got: $other")
        }
    }

    // Test (Phase 21d T2): two-section encoding -- lookup of second section returns correct offset and length
    "two-section encoding: get(ASTs) returns Present with correct offset and length" in run {
        // Build a names array: names(0) = Name("NAMES"), names(1) = Name("ASTs").
        val names = Array(Tasty.Name("NAMES"), Tasty.Name("ASTs"))
        // Encode section table:
        //   Section 1: nameRef=0 (1 byte), len=10 (1 byte), 10 zero payload bytes. Header at offset 0.
        //              Payload starts at offset 2.
        //   Section 2: nameRef=1 (1 byte), len=20 (1 byte), 20 zero payload bytes. Header at offset 12.
        //              Payload starts at offset 14.
        val section1Header  = encodeNat(0) ++ encodeNat(10)
        val section1Payload = Array.fill(10)(0.toByte)
        val section2Header  = encodeNat(1) ++ encodeNat(20)
        val section2Payload = Array.fill(20)(0.toByte)
        val sectionBytes    = section1Header ++ section1Payload ++ section2Header ++ section2Payload
        val view            = ByteView(sectionBytes)
        Abort.run[TastyError] {
            SectionIndex.read(view, names)
        }.map { result =>
            result match
                case Result.Success(index) =>
                    index.get("ASTs") match
                        case Present((offset, length)) =>
                            assert(length == 20, s"Expected length=20 but got $length")
                            assert(offset == 14, s"Expected offset=14 but got $offset")
                        case Absent =>
                            fail("Expected Present for ASTs section but got Absent")
                case other =>
                    fail(s"Expected successful SectionIndex but got: $other")
        }
    }

end SectionIndexTest
