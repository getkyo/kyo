package kyo.internal

import scala.annotation.tailrec

/** Pure Scala SHA-256 implementation used by [[kyo.UUID.v8Sha256]].
  *
  * Avoids `java.security.MessageDigest`, which is unavailable on Scala Native and Scala.js without platform-specific polyfills.
  * kyo-data cross-builds for JVM, JS, Native, and Wasm, so a deterministic name-based UUID constructor needs a shared,
  * dependency-free digest. The implementation follows the FIPS 180-4 algorithm: pad the message, split into 512-bit blocks, and
  * compress each block with the 64-round SHA-256 compression function. Output is a 32-byte digest.
  */
private[kyo] object Sha256:

    private val k: Array[Int] = Array(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    )

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
        var h0 = 0x6a09e667
        var h1 = 0xbb67ae85
        var h2 = 0x3c6ef372
        var h3 = 0xa54ff53a
        var h4 = 0x510e527f
        var h5 = 0x9b05688c
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val block      = new Array[Byte](64)
        val w          = new Array[Int](64)
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
            if j < 64 then
                val s0 = Integer.rotateRight(w(j - 15), 7) ^ Integer.rotateRight(w(j - 15), 18) ^ (w(j - 15) >>> 3)
                val s1 = Integer.rotateRight(w(j - 2), 17) ^ Integer.rotateRight(w(j - 2), 19) ^ (w(j - 2) >>> 10)
                w(j) = w(j - 16) + s0 + w(j - 7) + s1
                extendWords(j + 1)
        @tailrec def compress(
            j: Int,
            a: Int,
            b: Int,
            c: Int,
            d: Int,
            e: Int,
            f: Int,
            g: Int,
            h: Int
        ): (Int, Int, Int, Int, Int, Int, Int, Int) =
            if j >= 64 then (a, b, c, d, e, f, g, h)
            else
                val s1    = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
                val ch    = (e & f) ^ (~e & g)
                val temp1 = h + s1 + ch + k(j) + w(j)
                val s0    = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
                val maj   = (a & b) ^ (a & c) ^ (b & c)
                val temp2 = s0 + maj
                compress(j + 1, temp1 + temp2, a, b, c, d + temp1, e, f, g)

        def processBlock(input: Array[Byte], offset: Int): Unit =
            loadWords(input, 0, offset)
            extendWords(16)
            val (a, b, c, d, e, f, g, h) = compress(0, h0, h1, h2, h3, h4, h5, h6, h7)
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
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

        val result = new Array[Byte](32)
        writeInt(result, 0, h0)
        writeInt(result, 4, h1)
        writeInt(result, 8, h2)
        writeInt(result, 12, h3)
        writeInt(result, 16, h4)
        writeInt(result, 20, h5)
        writeInt(result, 24, h6)
        writeInt(result, 28, h7)
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

end Sha256
