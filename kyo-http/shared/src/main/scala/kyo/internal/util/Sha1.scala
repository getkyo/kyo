package kyo.internal.util

import scala.annotation.tailrec

/** Pure Scala SHA-1 implementation used by WebSocketCodec for the Sec-WebSocket-Accept key.
  *
  * Avoids java.security.MessageDigest, which is unavailable on Scala Native. The implementation follows the FIPS 180-4 algorithm: pad the
  * message, split into 512-bit blocks, and compress each block with the 80-step SHA-1 round function. Output is a 20-byte digest.
  *
  * Note: SHA-1 is cryptographically broken but remains the mandatory algorithm for WebSocket handshakes per RFC 6455 §4.1. This is not used
  * for any security-sensitive operation.
  */
private[internal] object Sha1:

    def hash(input: Array[Byte]): Array[Byte] =
        var h0 = 0x67452301
        var h1 = 0xefcdab89.toInt
        var h2 = 0x98badcfe.toInt
        var h3 = 0x10325476
        var h4 = 0xc3d2e1f0.toInt

        // Pre-processing: pad message
        val msgLen   = input.length
        val bitLen   = msgLen.toLong * 8
        val padLen   = (55 - msgLen % 64 + 64) % 64 + 1
        val totalLen = msgLen + padLen + 8
        val padded   = new Array[Byte](totalLen)
        java.lang.System.arraycopy(input, 0, padded, 0, msgLen)
        padded(msgLen) = 0x80.toByte
        // Append length in bits as big-endian 64-bit
        @tailrec def appendBitLength(shift: Int, i: Int): Unit =
            if shift >= 0 then
                padded(i) = ((bitLen >>> shift) & 0xff).toByte
                appendBitLength(shift - 8, i + 1)
        appendBitLength(56, totalLen - 8)

        // Process 512-bit blocks
        val w = new Array[Int](80)
        @tailrec def loadWords(j: Int, offset: Int): Unit =
            if j < 16 then
                w(j) = ((padded(offset + j * 4) & 0xff) << 24) |
                    ((padded(offset + j * 4 + 1) & 0xff) << 16) |
                    ((padded(offset + j * 4 + 2) & 0xff) << 8) |
                    (padded(offset + j * 4 + 3) & 0xff)
                loadWords(j + 1, offset)
        @tailrec def extendWords(j: Int): Unit =
            if j < 80 then
                w(j) = Integer.rotateLeft(w(j - 3) ^ w(j - 8) ^ w(j - 14) ^ w(j - 16), 1)
                extendWords(j + 1)
        @tailrec def compress(j: Int, a: Int, b: Int, c: Int, d: Int, e: Int): (Int, Int, Int, Int, Int) =
            if j >= 80 then (a, b, c, d, e)
            else
                val (f, k) =
                    if j < 20 then ((b & c) | (~b & d), 0x5a827999)
                    else if j < 40 then (b ^ c ^ d, 0x6ed9eba1)
                    else if j < 60 then ((b & c) | (b & d) | (c & d), 0x8f1bbcdc.toInt)
                    else (b ^ c ^ d, 0xca62c1d6.toInt)
                val temp = Integer.rotateLeft(a, 5) + f + e + k + w(j)
                compress(j + 1, temp, a, Integer.rotateLeft(b, 30), c, d)
        @tailrec def processBlocks(offset: Int): Unit =
            if offset < totalLen then
                loadWords(0, offset)
                extendWords(16)
                val (a, b, c, d, e) = compress(0, h0, h1, h2, h3, h4)
                h0 += a
                h1 += b
                h2 += c
                h3 += d
                h4 += e
                processBlocks(offset + 64)
        processBlocks(0)

        // Produce hash
        val result = new Array[Byte](20)
        writeInt(result, 0, h0)
        writeInt(result, 4, h1)
        writeInt(result, 8, h2)
        writeInt(result, 12, h3)
        writeInt(result, 16, h4)
        result
    end hash

    private def writeInt(buf: Array[Byte], offset: Int, value: Int): Unit =
        buf(offset) = ((value >>> 24) & 0xff).toByte
        buf(offset + 1) = ((value >>> 16) & 0xff).toByte
        buf(offset + 2) = ((value >>> 8) & 0xff).toByte
        buf(offset + 3) = (value & 0xff).toByte
    end writeInt

end Sha1
