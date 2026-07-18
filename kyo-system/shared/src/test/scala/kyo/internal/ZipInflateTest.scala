package kyo.internal

/** Tests for [[ZipInflate.inflateRaw]], the ported RFC 1951 raw-DEFLATE decoder that serves
  * [[ZipArchive.readEntry]] for a method-8 (DEFLATED) zip entry.
  */
class ZipInflateTest extends kyo.test.Test[Any]:

    "inflateRaw decodes a hand-built single STORED block (type 0) to its literal bytes" in {
        import kyo.AllowUnsafe.embrace.danger
        // byte0: bit0=BFINAL=1, bits1-2=BTYPE=00, bits3-7=padding to the next byte boundary.
        // bytes1-2: LEN=5 little-endian. bytes3-4: NLEN=~5 & 0xFFFF little-endian. bytes5-9: the
        // 5 literal payload bytes.
        val buffer = Array[Byte](
            0x01, // BFINAL=1, BTYPE=00, padding
            0x05,
            0x00, // LEN=5
            0xfa.toByte,
            0xff.toByte, // NLEN = ~5 & 0xFFFF = 0xFFFA
            1,
            2,
            3,
            4,
            5
        )
        val result = ZipInflate.inflateRaw(buffer, 0L)
        assert(result.toSeq == Seq[Byte](1, 2, 3, 4, 5))
    }

    "inflateRaw throws InflateException on an invalid LZ77 back-reference distance rather than corrupting output or looping" in {
        import kyo.AllowUnsafe.embrace.danger
        // A single final fixed-Huffman block (BFINAL=1, BTYPE=01) whose only symbol is a
        // length/distance pair: length-code 257 (base length 3, no extra bits) at its 7-bit fixed
        // code (value 1), followed by distance-code 0 (base distance 1, no extra bits) at its
        // 5-bit fixed code (value 0). At decode time no literal has been emitted yet (out.length ==
        // 0), so a distance of 1 exceeds the output produced so far and copyBack must throw rather
        // than read out of bounds.
        // Bit stream (LSB-first within each byte), in read order:
        //   bfinal=1; btype bits [1,0] (value 1, fixed Huffman); huffman code for symbol 257 read
        //   MSB-first as 7 bits [0,0,0,0,0,0,1]; distance code for symbol 0 read MSB-first as 5
        //   bits [0,0,0,0,0]. Packed LSB-first into two bytes: byte0 = 0x03, byte1 = 0x02.
        val buffer = Array[Byte](0x03, 0x02)
        try
            ZipInflate.inflateRaw(buffer, 0L)
            fail("expected InflateException for an out-of-range LZ77 distance")
        catch
            case ex: ZipInflate.InflateException =>
                assert(ex.getMessage.contains("invalid LZ77 distance"))
        end try
    }

    "inflateRaw raises InflateException, not a raw index panic, on a STORED block whose LEN overruns the input" in {
        import kyo.AllowUnsafe.embrace.danger
        // BFINAL=1, BTYPE=00; LEN=0xFFF0 (little-endian F0 FF), NLEN=0x000F (0F 00), so
        // LEN ^ NLEN == 0xFFFF passes the consistency check, but the block claims 65520 payload
        // bytes that are absent (the buffer ends here). readBytes must refuse the overrun.
        val buffer = Array[Byte](0x01, 0xf0.toByte, 0xff.toByte, 0x0f, 0x00)
        try
            ZipInflate.inflateRaw(buffer, 0L, buffer.length)
            fail("expected InflateException on a STORED block that overruns the input")
        catch
            case ex: ZipInflate.InflateException =>
                assert(ex.getMessage.contains("overruns"))
        end try
    }

    "inflateRaw raises InflateException, not a raw index panic, on a stream truncated mid-block" in {
        import kyo.AllowUnsafe.embrace.danger
        // BFINAL=1, BTYPE=00 header only: the STORED block's LEN bytes are truncated away, so the
        // next readBit is past the input limit and must surface as a typed end-of-stream failure.
        val buffer = Array[Byte](0x01)
        try
            ZipInflate.inflateRaw(buffer, 0L, buffer.length)
            fail("expected InflateException on a truncated deflate stream")
        catch
            case ex: ZipInflate.InflateException =>
                assert(ex.getMessage.contains("unexpected end"))
        end try
    }

    "inflateRaw decodes a foreign java.util.zip.Deflater-produced raw-deflate stream byte-identical, on all four platforms" in {
        import kyo.AllowUnsafe.embrace.danger
        // Captured once via java.util.zip.Deflater(DEFAULT_COMPRESSION, nowrap = true) on the JVM,
        // fed "aaabbbcccdddeeefffggghhh".repeat(50) (1200 bytes), the identical highly-repetitive
        // ASCII fixture PortableInflateTest.scala uses to force a dynamic Huffman block. `nowrap =
        // true` emits a raw deflate stream with no RFC 1950 ZLIB CMF/FLG header and no Adler-32
        // trailer, matching a zip method-8 entry's own framing exactly.
        val rawDeflate: Array[Byte] = Array(
            237.toByte,
            200.toByte,
            49,
            1,
            0,
            48,
            12,
            2,
            48,
            173.toByte,
            80,
            104,
            241.toByte,
            175.toByte,
            96,
            54,
            118,
            144.toByte,
            51,
            0,
            72,
            206.toByte,
            140.toByte,
            36,
            219.toByte,
            187.toByte,
            123,
            119,
            73,
            208.toByte,
            239.toByte,
            247.toByte,
            251.toByte,
            159.toByte,
            253.toByte,
            3
        )
        val expected = "aaabbbcccdddeeefffggghhh".repeat(50).getBytes("UTF-8")
        val actual   = ZipInflate.inflateRaw(rawDeflate, 0L)
        assert(actual.toSeq == expected.toSeq)
    }

end ZipInflateTest
