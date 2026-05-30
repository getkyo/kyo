package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.binary.MalformedVarintException
import kyo.internal.tasty.binary.Varint

class VarintTest extends Test:

    private def viewOf(bytes: Array[Byte]): ByteView = ByteView(bytes)

    // Test 7: readNat decodes 0
    "readNat decodes 0 (single terminating byte 0x80)" in run {
        // 0 encoded: (0 & 0x7f) | 0x80 = 0x80
        val view = viewOf(Array(0x80.toByte))
        assert(Varint.readNat(view) == 0)
    }

    // Test 8: readNat decodes 127
    "readNat decodes 127 (single byte 0xFF)" in run {
        // 127 encoded: (127 & 0x7f) | 0x80 = 0x7f | 0x80 = 0xff
        val view = viewOf(Array(0xff.toByte))
        assert(Varint.readNat(view) == 127)
    }

    // Test 9: readNat decodes 128 (two bytes)
    "readNat decodes 128 (two-byte encoding)" in run {
        // 128 = 0x80: high 7 bits = 0x01, low 7 bits = 0x00
        // Continuation byte (0x80 CLEAR): 0x01
        // Terminating byte (0x80 SET):   0x80
        val view = viewOf(Array(0x01.toByte, 0x80.toByte))
        assert(Varint.readNat(view) == 128)
    }

    // Test 10: readNat decodes 16383 (two-byte maximum)
    "readNat decodes 16383 (max two-byte value)" in run {
        // 16383 = 0x3FFF: high 7 bits = 0x7F, low 7 bits = 0x7F
        // Continuation byte: 0x7F
        // Terminating byte: 0x7F | 0x80 = 0xFF
        val view = viewOf(Array(0x7f.toByte, 0xff.toByte))
        assert(Varint.readNat(view) == 16383)
    }

    // Test 11: readNat decodes Int.MaxValue (5 bytes)
    "readNat decodes Int.MaxValue (5-byte encoding)" in run {
        // Int.MaxValue = 2147483647 = 0x7FFFFFFF
        // Split into 5 groups of 7 bits: 0x07, 0x7F, 0x7F, 0x7F, 0x7F
        // Encoding: continuation bytes have 0x80 CLEAR, last byte has 0x80 SET
        // bytes: 0x07, 0x7F, 0x7F, 0x7F, 0xFF
        val view = viewOf(Array(0x07.toByte, 0x7f.toByte, 0x7f.toByte, 0x7f.toByte, 0xff.toByte))
        assert(Varint.readNat(view) == Int.MaxValue)
    }

    // Test 12: readInt decodes -1 (dotty 2's complement, NOT zigzag)
    "readInt decodes -1 using dotty sign-extension semantics" in run {
        // -1 in dotty readLongInt: single byte 0xFF
        // First byte b = 0xFF, x = ((0xFF << 1).toByte >> 1) = (0xFE.toByte >> 1) = -1
        // b & 0x80 = 0x80 != 0, so loop doesn't continue
        // result: -1
        val view = viewOf(Array(0xff.toByte))
        assert(Varint.readInt(view) == -1)
    }

    // Test 13: readInt decodes Int.MinValue
    "readInt decodes Int.MinValue using dotty sign-extension semantics" in run {
        // Int.MinValue = -2147483648 = 0x80000000 in 2's complement
        // Encoding in 5 bytes (groups of 7 from high to low):
        //   bits 34-28: only bit 31 is set in Int.MinValue, as Long = 0xFFFFFFFF80000000L
        //   Actually Int.MinValue.toLong = -2147483648L
        //   Highest 7 bits of the 35-bit signed representation:
        //   -2147483648L = 0xFFFFFFFF80000000L in binary
        //   bits[34:28] = 0x7F (all 1s for negative sign extension), but wait...
        //   Let's use the write loop from TastyBuffer.writeLongInt:
        //   It writes `(x >> 6)` first in a loop, then the last byte.
        //   writeLongInt(-2147483648):
        //     x = -2147483648L
        //     First iteration: if (x >>> 6) != 0L && (x >>> 6) != -1L => write & continue
        //       x >>> 6 = 0x03FFFFFE00000000 >> ... wait, unsigned shift of negative Long:
        //       -2147483648L >>> 6 = 0x03FFFFFFFFFFFE00... no
        //       Actually -2147483648L in hex = 0xFFFFFFFF80000000L
        //       0xFFFFFFFF80000000L >>> 6 = 0x03FFFFFFFFFFFE00L >> no...
        //       Let me just use the actual value: 0xFFFFFFFF80000000 >>> 6 = 0x03FFFFFFFFFE0000
        //       That is not -1L, so we continue writing.
        //     This is getting complex. Let's just use known test vectors from dotty source tests.
        //
        // From dotty test vectors, Int.MinValue encodes as: 0x78, 0x00, 0x00, 0x00, 0x80
        // (Each non-final byte: 0x78, 0x00, 0x00, 0x00 has 0x80 CLEAR = continuation)
        // Final byte: 0x80 has 0x80 SET = terminating, low 7 bits = 0x00)
        // Decode check:
        //   b=0x78=120, x=((120<<1).toByte>>1).toLong
        //     120<<1=240, 240.toByte = -16 (since 240 > 127), -16.toByte>>1 = -8
        //     x = -8L
        //   0x78 & 0x80 = 0, continue
        //   b=0x00, x=(-8L<<7)|(0L) = -1024L
        //   0x00 & 0x80 = 0, continue
        //   b=0x00, x=(-1024L<<7)|0L = -131072L
        //   0x00 & 0x80 = 0, continue
        //   b=0x00, x=(-131072L<<7)|0L = -16777216L
        //   0x00 & 0x80 = 0, continue
        //   b=0x80=128, x=(-16777216L<<7)|(128&0x7f)=(-2147483648L)|0L = -2147483648L
        //   0x80 & 0x80 = 0x80 != 0, stop
        //   result: -2147483648L.toInt = -2147483648 = Int.MinValue  CORRECT!
        val view = viewOf(Array(0x78.toByte, 0x00.toByte, 0x00.toByte, 0x00.toByte, 0x80.toByte))
        assert(Varint.readInt(view) == Int.MinValue)
    }

    // Test 14: readLongNat decodes Long.MaxValue
    "readLongNat decodes Long.MaxValue" in run {
        // Long.MaxValue = 0x7FFFFFFFFFFFFFFF = 9223372036854775807L
        // Split into 9 groups of 7 bits + 1 bit:
        // 0x7F = 63 bits total in groups: need 10 groups but last is partial
        // Long.MaxValue in 9 bytes (big-endian base-128):
        // 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF
        // first byte 0x00 (7 high bits = 0, continuation since 0x80 CLEAR)
        // next 7 bytes 0xFF each (7 bits = 0x7F, continuation)
        // last byte 0xFF (7 bits = 0x7F, terminating since 0x80 SET)
        // Wait: 0xFF has 0x80 SET, so 0x7F & 0x7f = 0x7F, and 0xFF means terminating.
        // Decode: x = (0<<7)|... let me trace:
        // b=0x00, x=(0L<<7)|(0L&0x7f)=0, 0x00&0x80=0 continue
        // b=0xFF, x=(0L<<7)|(0xFF&0x7f)=0x7f, 0xFF&0x80=0x80 STOP
        // That only gives 127. Wrong.
        //
        // Let me recalculate. For Long.MaxValue=0x7FFFFFFFFFFFFFFF:
        // 63 bits. In big-endian base-128, we need ceil(63/7)=9 bytes.
        // Byte 1 (MSB group, 7 bits): bits[62:56] of LongMax = 0x00 (since bit 63=0 for positive)
        //   Wait, LongMax = 0111...1 (63 ones). Highest 7 bits = 0x00? No.
        //   0x7FFFFFFFFFFFFFFF: bits 62 down to 0 are all 1.
        //   Group them 7 at a time from the top:
        //   bits[62:56] = 0x7F (7 bits all 1, but bit 63 is 0 so this is the top group)
        //   Wait: 63 bits = 9 groups of 7: 9*7=63. Perfect fit.
        //   bits[62:56] = 0x7F (7 ones)
        //   bits[55:49] = 0x7F
        //   ... all 9 groups = 0x7F
        //
        // Encoding: first 8 groups have 0x80 CLEAR (continuation), last has 0x80 SET:
        // bytes: 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0xFF
        // Decode:
        //   b=0x7F, x=0x7F, 0x7F&0x80=0 continue
        //   b=0x7F, x=(0x7FL<<7)|0x7fL=0x3FFFL, continue
        //   b=0x7F, x=(0x3FFFL<<7)|0x7fL=0x1FFFffL, continue
        //   ... after 8 iterations x = 0x0FFFFFFFFFFFL... let me just verify the count:
        //   After 9 bytes (8 continuation + 1 terminating), each adding 7 bits of 1:
        //   x = 2^63 - 1 = Long.MaxValue. YES.
        val bytes = Array(
            0x7f.toByte,
            0x7f.toByte,
            0x7f.toByte,
            0x7f.toByte,
            0x7f.toByte,
            0x7f.toByte,
            0x7f.toByte,
            0x7f.toByte,
            0xff.toByte
        )
        val view = viewOf(bytes)
        assert(Varint.readLongNat(view) == Long.MaxValue)
    }

    // Test (Phase 03a B4): readNat rejects continuation past 5 bytes
    "readNat rejects continuation past 5 bytes (Int overflow guard)" in run {
        // 6 bytes with 0x80 CLEAR = 6 continuation bytes; cap is 5.
        val view = viewOf(Array.fill(6)(0x00.toByte))
        try
            Varint.readNat(view)
            fail("Expected MalformedVarintException but no exception was thrown")
        catch
            case ex: MalformedVarintException =>
                assert(
                    ex.getMessage.contains("continuation runs past 5"),
                    s"Expected message to contain 'continuation runs past 5' but was: ${ex.getMessage}"
                )
        end try
    }

    // Test (Phase 03a B4): readLongNat rejects continuation past 10 bytes
    "readLongNat rejects continuation past 10 bytes (Long overflow guard)" in run {
        // 11 bytes with 0x80 CLEAR = 11 continuation bytes; cap is 10.
        val view = viewOf(Array.fill(11)(0x00.toByte))
        try
            Varint.readLongNat(view)
            fail("Expected MalformedVarintException but no exception was thrown")
        catch
            case ex: MalformedVarintException =>
                assert(
                    ex.getMessage.contains("continuation runs past 10"),
                    s"Expected message to contain 'continuation runs past 10' but was: ${ex.getMessage}"
                )
        end try
    }

    // Test (Phase 03a B4 boundary): readLongNat accepts exactly 10 bytes
    "readLongNat accepts exactly 10-byte encoding without throwing" in run {
        // 9 bytes with 0x80 CLEAR (continuation) then 1 terminating byte with 0x80 SET.
        // 0x81 has 0x80 CLEAR (bit 7 = 1 = 0x80, wait: 0x81 = 1000_0001, so bit 7 IS set).
        // Need bytes with continuation bit CLEAR: 0x01 through 0x7F (bit 7 clear).
        // Then a terminating byte with bit 7 SET: 0x81 or 0xFF etc.
        // Use 9 bytes of 0x01 (continuation, bit 7 clear) then 0x81 (terminating, bit 7 set).
        val bytes  = Array.fill(9)(0x01.toByte) :+ 0x81.toByte
        val view   = viewOf(bytes)
        val result = Varint.readLongNat(view)
        // Verify the round-trip: 9 continuation bytes of 0x01 (7 low bits = 1) then
        // terminating 0x81 (7 low bits = 1). Each byte contributes 7 bits of value 1,
        // so result = sum of 1 shifted by 7*i for i in 0..9 = (2^70 - 1) / (2^7 - 1),
        // but truncated to Long (64 bits). The exact value is:
        // 0x01 accumulated 10 times: (1<<63)|(1<<56)|(1<<49)|...|(1<<7)|(1<<0) folded to Long.
        // What matters for this boundary test is that no exception is thrown.
        // We also verify the position advanced past all 10 bytes.
        assert(view.position == 10)
    }

    // Test (Phase 21a T2-1): writeNat then readNat round-trip
    "writeNat then readNat round-trips 1234" in run {
        val buf = scala.collection.mutable.ArrayBuffer.empty[Byte]
        Varint.writeNat(buf, 1234)
        val view = viewOf(buf.toArray)
        assert(Varint.readNat(view) == 1234)
    }

    // Test (Phase 21a T2-2): writeLongNat then readLongNat round-trip
    "writeLongNat then readLongNat round-trips 9_999_999_999L" in run {
        val buf = scala.collection.mutable.ArrayBuffer.empty[Byte]
        Varint.writeLongNat(buf, 9_999_999_999L)
        val view = viewOf(buf.toArray)
        assert(Varint.readLongNat(view) == 9_999_999_999L)
    }

end VarintTest
