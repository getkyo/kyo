package kyo.internal.tasty.scala2

/** Pure-Scala RFC 1950 (ZLIB) inflate. No JVM dependencies. */
object PortableInflate:

    final class InflateException(msg: String, val byteOffset: Long) extends RuntimeException(msg)

    /** Bit-level reader over a byte array. LSB-first per DEFLATE spec. */
    final private[kyo] class BitStream(buf: Array[Byte], var bitOffset: Long):
        def byteOffset: Long = bitOffset >> 3

        def readBit(): Int =
            val byte = buf((bitOffset >> 3).toInt) & 0xff
            val bit  = (byte >> (bitOffset & 7).toInt) & 1
            bitOffset += 1
            bit
        end readBit

        def readBits(n: Int): Int =
            var result = 0
            var i      = 0
            while i < n do
                result |= (readBit() << i)
                i += 1
            result
        end readBits

        def alignToByte(): Int =
            val rem = bitOffset & 7
            if rem != 0 then bitOffset += (8 - rem)
            (bitOffset >> 3).toInt
        end alignToByte

        def readBytes(out: scala.collection.mutable.ArrayBuffer[Byte], len: Int): Unit =
            val start = alignToByte()
            var i     = 0
            while i < len do
                out += buf(start + i)
                i += 1
            bitOffset += (len * 8)
        end readBytes
    end BitStream

    /** Canonical Huffman tree built from code-length arrays per RFC 1951 §3.2.2. */
    final private[kyo] class HuffmanTree private (
        val maxBits: Int,
        val codeToSymbol: Array[Int],
        val bitLengthCounts: Array[Int]
    ):
        def decodeOne(stream: BitStream): Int =
            var code   = 0
            var first  = 0
            var index  = 0
            var len    = 1
            var result = -1
            while len <= maxBits && result < 0 do
                code = (code << 1) | stream.readBit()
                val count = bitLengthCounts(len)
                if code - count < first then
                    result = codeToSymbol(index + (code - first))
                else
                    index += count
                    first = (first + count) << 1
                    len += 1
                end if
            end while
            if result >= 0 then result
            else throw new InflateException(s"invalid Huffman code at bit ${stream.bitOffset}", stream.byteOffset)
        end decodeOne
    end HuffmanTree

    /** Companion for building a HuffmanTree from code-length arrays per RFC 1951 §3.2.2. */
    private[kyo] object HuffmanTree:
        def fromCodeLengths(lengths: Array[Int]): HuffmanTree =
            val maxBits         = lengths.max
            val bitLengthCounts = new Array[Int](maxBits + 1)
            var i               = 0
            while i < lengths.length do
                bitLengthCounts(lengths(i)) += 1
                i += 1
            bitLengthCounts(0) = 0
            val codeToSymbol = new Array[Int](lengths.length)
            val offsets      = new Array[Int](maxBits + 1)
            var sum          = 0
            var len          = 1
            while len <= maxBits do
                offsets(len) = sum
                sum += bitLengthCounts(len)
                len += 1
            end while
            var sym = 0
            while sym < lengths.length do
                val l = lengths(sym)
                if l != 0 then
                    codeToSymbol(offsets(l)) = sym
                    offsets(l) += 1
                sym += 1
            end while
            new HuffmanTree(maxBits, codeToSymbol, bitLengthCounts)
        end fromCodeLengths
    end HuffmanTree

    // Placeholder for the full inflate; subsequent phases add blocks, ZLIB wrapper.

end PortableInflate
