package kyo.internal

import scala.annotation.tailrec

/** Pure Scala SHA-1 implementation used by [[kyo.UUID.v5]].
  *
  * Avoids `java.security.MessageDigest`, which is unavailable on Scala Native and Scala.js without platform-specific polyfills.
  * kyo-data cross-builds for JVM, JS, Native, and Wasm, so a deterministic name-based UUID constructor needs a shared,
  * dependency-free digest. The implementation follows the FIPS 180-4 algorithm: pad the message, split into 512-bit blocks, and
  * compress each block with the 80-step SHA-1 round function. Output is a 20-byte digest.
  *
  * Note: SHA-1 is cryptographically broken but is the algorithm RFC 9562 mandates for version 5 UUIDs. This is not used for any
  * security-sensitive operation.
  */
private[kyo] object Sha1:

    def hash(input: Array[Byte]): Array[Byte] =
        hashChunks(Seq(input))

    private[kyo] def paddingSize(byteLength: Long): Int =
        val remainder = (byteLength & 63L).toInt
        if remainder < 56 then 64 - remainder
        else 128 - remainder
    end paddingSize

    private[kyo] def bitLength(byteLength: Long): Long =
        byteLength << 3

    private[kyo] def hashChunks(inputs: Seq[Array[Byte]]): Array[Byte] =
        var h0 = 0x67452301
        var h1 = 0xefcdab89.toInt
        var h2 = 0x98badcfe.toInt
        var h3 = 0x10325476
        var h4 = 0xc3d2e1f0.toInt

        val block      = new Array[Byte](64)
        val w          = new Array[Int](80)
        var blockSize  = 0
        var byteLength = 0L

        @tailrec def loadWords(input: Array[Byte], j: Int, offset: Int): Unit =
            if j < 16 then
                w(j) = ((input(offset + j * 4) & 0xff) << 24) |
                    ((input(offset + j * 4 + 1) & 0xff) << 16) |
                    ((input(offset + j * 4 + 2) & 0xff) << 8) |
                    (input(offset + j * 4 + 3) & 0xff)
                loadWords(input, j + 1, offset)
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

        def processBlock(input: Array[Byte], offset: Int): Unit =
            loadWords(input, 0, offset)
            extendWords(16)
            val (a, b, c, d, e) = compress(0, h0, h1, h2, h3, h4)
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
        end processBlock

        def update(input: Array[Byte]): Unit =
            byteLength += input.length.toLong
            var offset = 0
            if blockSize > 0 then
                val copied = math.min(64 - blockSize, input.length)
                java.lang.System.arraycopy(input, 0, block, blockSize, copied)
                blockSize += copied
                offset += copied
                if blockSize == 64 then
                    processBlock(block, 0)
                    blockSize = 0
            end if
            while offset <= input.length - 64 do
                processBlock(input, offset)
                offset += 64
            val remaining = input.length - offset
            if remaining > 0 then
                java.lang.System.arraycopy(input, offset, block, 0, remaining)
                blockSize = remaining
        end update

        inputs.foreach(update)

        val padding = paddingSize(byteLength)
        block(blockSize) = 0x80.toByte
        blockSize += 1
        if padding > 64 then
            java.util.Arrays.fill(block, blockSize, 64, 0.toByte)
            processBlock(block, 0)
            blockSize = 0
        end if
        java.util.Arrays.fill(block, blockSize, 56, 0.toByte)
        writeLong(block, 56, bitLength(byteLength))
        processBlock(block, 0)

        val result = new Array[Byte](20)
        writeInt(result, 0, h0)
        writeInt(result, 4, h1)
        writeInt(result, 8, h2)
        writeInt(result, 12, h3)
        writeInt(result, 16, h4)
        result
    end hashChunks

    private def writeLong(buf: Array[Byte], offset: Int, value: Long): Unit =
        var i = 0
        while i < 8 do
            buf(offset + i) = (value >>> (56 - i * 8)).toByte
            i += 1
    end writeLong

    private def writeInt(buf: Array[Byte], offset: Int, value: Int): Unit =
        buf(offset) = ((value >>> 24) & 0xff).toByte
        buf(offset + 1) = ((value >>> 16) & 0xff).toByte
        buf(offset + 2) = ((value >>> 8) & 0xff).toByte
        buf(offset + 3) = (value & 0xff).toByte
    end writeInt

end Sha1
