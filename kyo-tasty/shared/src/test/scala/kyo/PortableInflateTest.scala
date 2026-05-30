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

end PortableInflateTest
