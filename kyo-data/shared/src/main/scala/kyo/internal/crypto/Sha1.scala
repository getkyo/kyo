package kyo.internal.crypto

import scala.annotation.tailrec

/** SHA-1 (FIPS 180-4) in pure Scala, the one implementation every platform runs.
  *
  * `java.security.MessageDigest` is unavailable on Scala.js, Scala Native and Wasm, and kyo-data sits below every module that could supply
  * a platform digest, so the algorithm is written here once: pad the message, split it into 512-bit blocks, and compress each block with
  * the 80-step round function. `hash` digests one array and `hashChunks` digests a sequence of arrays as one message without copying them
  * together. The output is 20 bytes.
  *
  * WARNING: SHA-1 is broken for collision resistance. It exists here only for protocols that fix it by specification (a name-based UUID,
  * a handshake accept key, a legacy password exchange, a mask generation function), none of which relies on collision resistance. It is
  * not a choice for new code.
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
        def processBlock(input: Array[Byte], offset: Int): Unit =
            loadWords(input, 0, offset)
            extendWords(16)
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var j = 0
            while j < 80 do
                val f =
                    if j < 20 then (b & c) | (~b & d)
                    else if j < 40 then b ^ c ^ d
                    else if j < 60 then (b & c) | (b & d) | (c & d)
                    else b ^ c ^ d
                val k =
                    if j < 20 then 0x5a827999
                    else if j < 40 then 0x6ed9eba1
                    else if j < 60 then 0x8f1bbcdc.toInt
                    else 0xca62c1d6.toInt
                val temp = Integer.rotateLeft(a, 5) + f + e + k + w(j)
                e = d
                d = c
                c = Integer.rotateLeft(b, 30)
                b = a
                a = temp
                j += 1
            end while
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
