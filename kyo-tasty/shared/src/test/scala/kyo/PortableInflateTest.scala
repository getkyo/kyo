package kyo

import kyo.internal.tasty.scala2.PortableInflate

class PortableInflateTest extends Test:

    // Test 1: BitStream reads single bits LSB-first
    // byte 0xB4 = 0b10110100; LSB-first: [0, 0, 1, 0, 1, 1, 0, 1]
    "BitStream reads single bits LSB-first" in run {
        val buf    = Array(0xb4.toByte)
        val stream = new PortableInflate.BitStream(buf, 0L)
        val bits   = Array.fill(8)(stream.readBit())
        assert(bits.toSeq == Seq(0, 0, 1, 0, 1, 1, 0, 1), s"Expected [0,0,1,0,1,1,0,1] but got ${bits.toSeq}")
    }

    // Test 2: BitStream.readBits(n) packs LSB-first
    // byte 0xD6 = 0b11010110; readBits(4) => 0b0110 = 6, readBits(4) => 0b1101 = 13
    "BitStream.readBits(n) packs LSB-first" in run {
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

end PortableInflateTest
