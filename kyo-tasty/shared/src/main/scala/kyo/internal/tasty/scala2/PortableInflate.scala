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

    // Placeholder for the full inflate; subsequent phases add Huffman, blocks, ZLIB wrapper.

end PortableInflate
