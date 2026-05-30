package kyo

import kyo.internal.tasty.scala2.PortableInflate

class PortableInflateTest extends Test:

    // Test 1: BitStream reads single bits LSB-first
    // byte 0xB4 = 0b10110100; LSB-first: [0, 0, 1, 0, 1, 1, 0, 1]
    "BitStream reads single bits LSB-first" in run {
        // flow-allow: §839 case 3; direct BitStream test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val buf    = Array(0xb4.toByte)
        val stream = new PortableInflate.BitStream(buf, 0L)
        val bits   = Array.fill(8)(stream.readBit())
        assert(bits.toSeq == Seq(0, 0, 1, 0, 1, 1, 0, 1), s"Expected [0,0,1,0,1,1,0,1] but got ${bits.toSeq}")
    }

    // Test 2: BitStream.readBits(n) packs LSB-first
    // byte 0xD6 = 0b11010110; readBits(4) => 0b0110 = 6, readBits(4) => 0b1101 = 13
    "BitStream.readBits(n) packs LSB-first" in run {
        // flow-allow: §839 case 3; direct BitStream test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val buf    = Array(0xd6.toByte)
        val stream = new PortableInflate.BitStream(buf, 0L)
        val lo     = stream.readBits(4)
        val hi     = stream.readBits(4)
        assert(lo == 6, s"Expected lo=6 (0b0110) but got $lo")
        assert(hi == 13, s"Expected hi=13 (0b1101) but got $hi")
    }

    // Test 3: HuffmanTree.fromCodeLengths + decodeOne - RFC 1951 §3.2.2 example
    // Symbols A-H (indices 0-7) with lengths [3,3,3,3,3,2,4,4].
    // Canonical codes (MSB-first representation):
    //   sym 5 (F), len 2: 00
    //   sym 0 (A), len 3: 010
    //   sym 1 (B), len 3: 011
    // DEFLATE sends bits LSB-first in each byte.
    // To decode F then A:
    //   F (code 00 = 0): need bits 0,0 at positions 0,1
    //   A (code 010 = 2): need bits 0,1,0 at positions 2,3,4
    // Packed LSB-first into byte 0: bit0=0,bit1=0,bit2=0,bit3=1,bit4=0 => 0b00001000 = 0x08
    // (bits 5-7 are padding zeros)
    "HuffmanTree decodes RFC 1951 §3.2.2 example (F then A)" in run {
        // flow-allow: §839 case 3; direct BitStream/HuffmanTree test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val lengths = Array(3, 3, 3, 3, 3, 2, 4, 4)
        val tree    = PortableInflate.HuffmanTree.fromCodeLengths(lengths)
        // bit layout: positions 0-1 = "00" (F, symbol 5), positions 2-4 = "010" (A, symbol 0)
        // byte 0 = bit0=0 bit1=0 bit2=0 bit3=1 bit4=0 bit5=0 bit6=0 bit7=0 = 0x08
        val buf    = Array(0x08.toByte)
        val stream = new PortableInflate.BitStream(buf, 0L)
        val symF   = tree.decodeOne(stream)
        val symA   = tree.decodeOne(stream)
        assert(symF == 5, s"Expected symbol index 5 (F) but got $symF")
        assert(symA == 0, s"Expected symbol index 0 (A) but got $symA")
    }

    // Test 4: HuffmanTree.decodeOne throws InflateException for invalid code
    // Tree with lengths [2, 2]: sym 0 -> code 00, sym 1 -> code 01 (maxBits=2).
    // Stream bits 1,1 (byte 0x03 = 0b00000011): produces code=3 at len=2 which exceeds table, throws.
    "HuffmanTree decodeOne throws InflateException for invalid Huffman code" in run {
        // flow-allow: §839 case 3; direct HuffmanTree error-path test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val lengths = Array(2, 2)
        val tree    = PortableInflate.HuffmanTree.fromCodeLengths(lengths)
        // bits 1,1 -> code builds to 3 at len=2, exceeds all entries, exhausts maxBits
        val buf    = Array(0x03.toByte)
        val stream = new PortableInflate.BitStream(buf, 0L)
        try
            tree.decodeOne(stream)
            fail("Expected InflateException but no exception was thrown")
        catch
            case _: PortableInflate.InflateException => succeed
        end try
    }

    // Test 5: decodeStoredBlock copies raw bytes and validates LEN ^ NLEN
    // Buffer layout: byte0 = header (BFINAL=1 BTYPE=00 = 0x01, alignment padding auto-skipped),
    // bytes 1-2 = LEN=3 LE, bytes 3-4 = NLEN=~3&0xFFFF=0xFFFC LE, bytes 5-7 = 'A','B','C'
    // The test reads BFINAL+BTYPE (3 bits) first to simulate the block-loop entry, then calls
    // decodeStoredBlock which calls alignToByte() internally before reading LEN/NLEN/data.
    "decodeStoredBlock copies raw bytes (LEN=3, payload ABC)" in run {
        // flow-allow: §839 case 3; direct decodeStoredBlock test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val buf = Array(
            0x01.toByte, // bit0=BFINAL=1, bits1-2=BTYPE=00, bits3-7=padding
            0x03.toByte, // LEN low
            0x00.toByte, // LEN high
            0xfc.toByte, // NLEN low (~3 & 0xff)
            0xff.toByte, // NLEN high (~3 >> 8 & 0xff)
            0x41.toByte, // 'A'
            0x42.toByte, // 'B'
            0x43.toByte  // 'C'
        )
        val stream = new PortableInflate.BitStream(buf, 0L)
        // consume BFINAL (1 bit) and BTYPE (2 bits) to simulate block-loop state
        val _bfinal = stream.readBits(1)
        val _btype  = stream.readBits(2)
        val out     = new scala.collection.mutable.ArrayBuffer[Byte]()
        PortableInflate.decodeStoredBlock(stream, out)
        assert(
            out.toArray.toSeq == Seq(0x41.toByte, 0x42.toByte, 0x43.toByte),
            s"Expected [0x41,0x42,0x43] but got ${out.toArray.map(b => "0x%02x".format(b)).toSeq}"
        )
    }

    // Test 6: decodeFixedHuffmanBlock decodes "AAA"
    // Fixed Huffman literal code for 65 ('A'):
    //   RFC 1951 §3.2.6: literals 0-143 use 8-bit codes starting at canonical 48 (0b00110000).
    //   Code for 65 = 48 + 65 = 113 = 0b01110001 (MSB-first).
    //   The decoder reads bits LSB-first via readBit(), accumulating code = (code << 1) | bit.
    //   First bit read = MSB of the code in canonical terms, so code accumulates to 113.
    //   Bit stream for code 113 in decoder order: bits [0,1,1,1,0,0,0,1].
    //   Packed into a byte (bit0=LSB): 0b10001110 = 0x8E.
    // EOB (256): 7-bit code = 0 (0b0000000), stream bits = [0,0,0,0,0,0,0], packed = 0x00.
    // Encoding "AAA"+EOB = 3*8 + 7 = 31 bits, padded to 4 bytes: [0x8e, 0x8e, 0x8e, 0x00].
    "decodeFixedHuffmanBlock decodes AAA" in run {
        // flow-allow: §839 case 3; direct decodeFixedHuffmanBlock test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        // Hand-computed bytes: 3x literal-65 (0x8E each) then EOB-256 (0x00 with 1 padding bit)
        val buf = Array(
            0x8e.toByte, // literal 'A' (bits [0,1,1,1,0,0,0,1] in stream order)
            0x8e.toByte, // literal 'A'
            0x8e.toByte, // literal 'A'
            0x00.toByte  // EOB 256 (7 zero bits) + 1 padding bit
        )
        val stream = new PortableInflate.BitStream(buf, 0L)
        val out    = new scala.collection.mutable.ArrayBuffer[Byte]()
        PortableInflate.decodeFixedHuffmanBlock(stream, out)
        assert(
            out.toArray.toSeq == Seq(0x41.toByte, 0x41.toByte, 0x41.toByte),
            s"Expected [0x41,0x41,0x41] ('AAA') but got ${out.toArray.map(b => "0x%02x".format(b)).toSeq}"
        )
    }

    // Phase 20d-debt: copyBack rejects out-of-range LZ77 distance.
    // A corrupt deflate stream that emits a backreference with dist > out.length
    // must raise InflateException, not a raw IndexOutOfBoundsException.
    "copyBack rejects LZ77 distance beyond output buffer length" in run {
        val out = scala.collection.mutable.ArrayBuffer[Byte](0x41.toByte, 0x42.toByte) // 2 bytes
        try
            PortableInflate.copyBack(out, dist = 5, len = 3)
            fail("Expected InflateException for dist > out.length")
        catch
            case ex: PortableInflate.InflateException =>
                assert(ex.getMessage.contains("invalid LZ77 distance 5"))
        end try
    }

    // Test 7: ZLIB input shorter than 6 bytes is rejected.
    // Minimum valid ZLIB stream is 2 bytes (CMF+FLG) + at least 1 deflate byte + 4 Adler bytes = 7 bytes,
    // but the guard is set at 6 to catch obviously truncated inputs early.
    "inflate rejects ZLIB input shorter than 6 bytes" in run {
        // flow-allow: §839 case 3; direct inflate error-path test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val bytes = Array(0x78.toByte, 0x9c.toByte, 0x03.toByte, 0x00.toByte)
        try
            PortableInflate.inflate(bytes)
            fail("Expected InflateException but no exception was thrown")
        catch
            case ex: PortableInflate.InflateException =>
                assert(
                    ex.getMessage == "ZLIB input too short (< 6 bytes)",
                    s"Expected 'ZLIB input too short (< 6 bytes)' but got '${ex.getMessage}'"
                )
        end try
    }

    // Test 8: ZLIB stored-block envelope with a corrupted Adler-32 trailer is rejected.
    //
    // Byte construction:
    //   byte[0]=0x78 (CMF): CM=8 (deflate), CINFO=7 (window=32k)
    //   byte[1]=0x9C (FLG): FCHECK: (0x78*256+0x9C)=30876, 30876%31=0, checksum valid; FDICT bit clear
    //   byte[2]=0x01: BFINAL=1 (bit0), BTYPE=00 (bits1-2), rest=padding zeros
    //   byte[3]=0x00, byte[4]=0x00: LEN=0 (little-endian)
    //   byte[5]=0xFF, byte[6]=0xFF: NLEN=0xFFFF (little-endian); LEN ^ NLEN = 0xFFFF OK
    //   bytes[7..10]: Adler-32 of empty payload = 0x00000001 big-endian = [0x00,0x00,0x00,0x01]
    //   We replace the correct Adler [0x00,0x00,0x00,0x01] with wrong [0xFF,0xFF,0xFF,0xFF].
    "inflate rejects Adler-32 mismatch in stored-block ZLIB" in run {
        // flow-allow: §839 case 3; direct inflate Adler-32 error-path test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val bytes = Array(
            0x78.toByte, // CMF
            0x9c.toByte, // FLG (header check: 0x789C % 31 == 0)
            0x01.toByte, // BFINAL=1, BTYPE=00 (stored), padding
            0x00.toByte, // LEN low
            0x00.toByte, // LEN high  -> LEN=0
            0xff.toByte, // NLEN low
            0xff.toByte, // NLEN high -> NLEN=0xFFFF; LEN^NLEN=0xFFFF
            0xff.toByte, // Adler-32 byte 0 (wrong)
            0xff.toByte, // Adler-32 byte 1 (wrong)
            0xff.toByte, // Adler-32 byte 2 (wrong)
            0xff.toByte  // Adler-32 byte 3 (wrong)
        )
        try
            PortableInflate.inflate(bytes)
            fail("Expected InflateException but no exception was thrown")
        catch
            case ex: PortableInflate.InflateException =>
                assert(
                    ex.getMessage.startsWith("Adler-32 mismatch"),
                    s"Expected message starting with 'Adler-32 mismatch' but got '${ex.getMessage}'"
                )
        end try
    }

    // Test 9 (deferred dynamic Huffman, now landing): Full ZLIB round-trip with dynamic Huffman block.
    //
    // Fixture capture: produced via Java's java.util.zip.Deflater(DEFAULT_COMPRESSION) on the input
    // "aaabbbcccdddeeefffggghhh" repeated 50 times (1200 bytes). This highly repetitive ASCII input
    // causes the JVM deflater to emit a dynamic Huffman block (BTYPE=10), covering the
    // decodeDynamicHuffmanBlock + decodeCodeLengths paths that were deferred from Phase 20d.
    //
    // Capture command used (Java):
    //   byte[] input = "aaabbbcccdddeeefffggghhh".repeat(50).getBytes("UTF-8");
    //   DeflaterOutputStream dos = new DeflaterOutputStream(baos, new Deflater(DEFAULT_COMPRESSION));
    //   dos.write(input); dos.finish(); dos.close();
    //   => 42 bytes, BFINAL=1, BTYPE=2 (dynamic Huffman)
    //
    // CMF=0x78 FLG=0x9C: header checksum (0x78*256+0x9C)%31 = 0. No preset dictionary.
    // The expected decompressed bytes are "aaabbbcccdddeeefffggghhh".repeat(50) encoded as UTF-8.
    "inflate full ZLIB round-trip with dynamic Huffman block" in run {
        // flow-allow: §839 case 3; direct inflate round-trip test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val zlibBytes: Array[Byte] = Array(
            0x78.toByte,
            0x9c.toByte,
            0xed.toByte,
            0xc8.toByte,
            0x31.toByte,
            0x01.toByte,
            0x00.toByte,
            0x30.toByte,
            0x0c.toByte,
            0x02.toByte,
            0x30.toByte,
            0xad.toByte,
            0x50.toByte,
            0x68.toByte,
            0xf1.toByte,
            0xaf.toByte,
            0x60.toByte,
            0x36.toByte,
            0x76.toByte,
            0x90.toByte,
            0x33.toByte,
            0x00.toByte,
            0x48.toByte,
            0xce.toByte,
            0x8c.toByte,
            0x24.toByte,
            0xdb.toByte,
            0xbb.toByte,
            0x7b.toByte,
            0x77.toByte,
            0x49.toByte,
            0xd0.toByte,
            0xef.toByte,
            0xf7.toByte,
            0xfb.toByte,
            0x9f.toByte,
            0xfd.toByte,
            0x03.toByte,
            0x07.toByte,
            0x67.toByte,
            0xd7.toByte,
            0x28.toByte
        )
        val expected: Array[Byte] = "aaabbbcccdddeeefffggghhh".repeat(50).getBytes("UTF-8")
        val actual                = PortableInflate.inflate(zlibBytes)
        assert(
            java.util.Arrays.equals(actual, expected),
            s"Decompressed bytes did not match: got ${actual.length} bytes, expected ${expected.length} bytes"
        )
    }

end PortableInflateTest
