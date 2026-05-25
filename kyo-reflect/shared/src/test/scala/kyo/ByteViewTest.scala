package kyo

import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.binary.Varint

class ByteViewTest extends Test:

    // Test 1: peekByte reads at offset without advancing position
    "peekByte reads byte at offset without advancing position" in run {
        val bytes = Array[Byte](10, 20, 30, 40)
        val view  = ByteView(bytes)
        val b     = view.peekByte(2)
        assert(b == 30.toByte)
        assert(view.position == 0)
    }

    // Test 2: readByte advances position by 1 and returns correct byte
    "readByte advances position by 1 and returns correct byte" in run {
        val bytes = Array[Byte](0x5c.toByte, 0xa1.toByte, 0xab.toByte)
        val view  = ByteView(bytes)
        val b0    = view.readByte()
        assert(b0 == 0x5c.toByte)
        assert(view.position == 1)
        val b1 = view.readByte()
        assert(b1 == 0xa1.toByte)
        assert(view.position == 2)
    }

    // Test 3: readByte at end produces ArrayIndexOutOfBoundsException
    "readByte at end produces ArrayIndexOutOfBoundsException" in run {
        val bytes = Array[Byte](0x80.toByte)
        val view  = ByteView(bytes)
        view.readByte() // consume the only byte
        assert(view.remaining == 0)
        assertThrows[ArrayIndexOutOfBoundsException] {
            view.readByte()
        }
    }

    // Test 4: subView shares underlying array, has correct start/end/position
    "subView shares underlying array with correct bounds" in run {
        val bytes = Array[Byte](1, 2, 3, 4, 5)
        val view  = ByteView(bytes)
        val sub   = view.subView(1, 4)
        assert(sub.start == 1)
        assert(sub.end == 4)
        assert(sub.position == 1)
        // Verify same underlying array reference
        assert(sub.bytes eq bytes)
    }

    // Test 5: goto sets position to addr
    "goto sets position to given address" in run {
        val bytes = Array[Byte](0, 1, 2, 3, 4, 5)
        val view  = ByteView(bytes)
        view.goto(3)
        assert(view.position == 3)
        val b = view.readByte()
        assert(b == 3.toByte)
        assert(view.position == 4)
    }

    // Test 6: remaining returns end - position
    "remaining returns end minus current position" in run {
        val bytes = Array[Byte](10, 20, 30, 40, 50)
        val view  = ByteView(bytes)
        assert(view.remaining == 5)
        view.readByte()
        assert(view.remaining == 4)
        view.goto(3)
        assert(view.remaining == 2)
    }

end ByteViewTest

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
        // Encoding: first byte carries the sign via bit 6.
        // -2147483648 in binary: 1000 0000 0000 0000 0000 0000 0000 0000
        // In dotty readLongInt / TastyBuffer.writeLongInt:
        // The encoding shifts right by groups of 7 bits, sign-extending.
        // Working backward from -2147483648:
        //   The low 7 bits of the final accumulation are 0x00, with stop bit: 0x80
        //   Next 7 bits: 0x00, continuation (0x80 CLEAR): 0x00
        //   Next 7 bits: 0x00, continuation: 0x00
        //   Next 7 bits: 0x00, continuation: 0x00
        //   First byte carries sign: bit6=1 means negative
        //   First byte low 6 bits from -2147483648 >> 28 = -16, masked to 6 bits = 0x30
        //   But with sign extension: first byte = ((-16 & 0x3f) | 0x00) with sign in bit6
        //   Actually: first byte = -2147483648L >> 28 = -8, low 7 bits = (-8 & 0x7f) = 0x78
        //   Hmm, let me compute properly using the write algorithm.
        //
        // TastyBuffer.writeLongInt(x): writes high bits first then low bits.
        // For x = -2147483648 = 0xFFFFFFFF80000000L (as Long):
        //   writeLongInt writes starting from the most-significant end.
        //   The actual bytes are: 0x78, 0x00, 0x00, 0x00, 0x80
        //   Verification via readLongInt:
        //     b=0x78, x=(0x78<<1).toByte>>1 = (0xF0).toByte>>1 = -8, i.e. 0xFFFFFFFFFFFFFFF8L
        //     b&0x80==0, continue:
        //     b=0x00, x=(-8<<7)|(0x00)=-1024, i.e. 0xFFFFFFFFFFFFFC00L
        //     b&0x80==0, continue:
        //     b=0x00, x=(-1024<<7)|0=-131072, i.e. 0xFFFFFFFFFFFF0000... wait
        //     Let me just verify: 0x78,0x00,0x00,0x00,0x80 decodes as:
        //     b=0x78=120, x=(120<<1).toByte>>1=(240).toByte>>1=(-16).toLong=-16
        //     (240.toByte = -16 signed)
        //     b&0x80=0, continue
        //     b=0x00, x=(-16L<<7)|0L = -2048
        //     b&0x80=0, continue
        //     b=0x00, x=(-2048L<<7)|0L = -262144
        //     b&0x80=0, continue
        //     b=0x00, x=(-262144L<<7)|0L = -33554432
        //     b&0x80=0, continue
        //     b=0x80, x=(-33554432L<<7)|(0x80&0x7f)=(-4294967296L)|0L=-4294967296L
        //     b&0x80=0x80!=0, stop
        //     result: -4294967296L.toInt = 0 ... that's wrong.
        //
        // Let me recalculate with the actual Int.MinValue = -2147483648:
        // As 32-bit: 10000000_00000000_00000000_00000000
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

end VarintTest
