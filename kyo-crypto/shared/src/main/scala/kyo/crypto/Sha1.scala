package kyo.crypto

import kyo.*
import kyo.internal.crypto.Blocks
import scala.annotation.tailrec

/** SHA-1 (FIPS 180-4), in pure Scala, the one implementation every platform runs.
  *
  * `java.security.MessageDigest` is unavailable on Scala.js, Scala Native and Wasm, so the algorithm is written here once: pad the message,
  * split it into 512-bit blocks, and compress each block with the 80-step round function. The output is 20 bytes. [[hash]] digests one
  * value and [[hashAll]] the concatenation of several without building it.
  *
  * WARNING: SHA-1 is broken for collision resistance. It exists only for protocols that fix SHA-1 by specification and do not rely on
  * collision resistance. It must not be chosen for a new design, a signature, a certificate or a password hash.
  *
  * @see
  *   [[Sha256]] and [[Sha512]], the digests for anything new
  * @see
  *   [[Mgf1.sha1]], the mask generation built on it
  * @see
  *   [[kyo.UUID]], whose version 5 extension in this package is built on it
  * @see
  *   [[ConstantTime.isEqual]], the comparison for a received digest
  */
object Sha1:

    /** The 20-byte digest of `input`. */
    def hash(input: Span[Byte]): Span[Byte] =
        // Unsafe: the input array is only read; the digest array is fresh and held by nothing else.
        Span.fromUnsafe(hashArray(input.toArrayUnsafe))

    /** The 20-byte digest of the concatenation of `parts`, computed without concatenating them. */
    def hashAll(parts: Chunk[Span[Byte]]): Span[Byte] =
        // Unsafe: each part's array is only read; the digest array is fresh and held by nothing else.
        Span.fromUnsafe(hashArrays(parts.map(_.toArrayUnsafe)))

    private[kyo] def hashArray(input: Array[Byte]): Array[Byte] =
        hashArrays(Chunk(input))

    private[kyo] def hashArrays(inputs: Chunk[Array[Byte]]): Array[Byte] =
        var h0 = 0x67452301
        var h1 = 0xefcdab89.toInt
        var h2 = 0x98badcfe.toInt
        var h3 = 0x10325476
        var h4 = 0xc3d2e1f0.toInt

        val w = new Array[Int](80)

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

        val buffer = new Blocks.Buffer64(processBlock)
        inputs.foreach(buffer.update)
        buffer.finish(Blocks.writeLongBigEndian)

        val result = new Array[Byte](20)
        Blocks.writeIntBigEndian(result, 0, h0)
        Blocks.writeIntBigEndian(result, 4, h1)
        Blocks.writeIntBigEndian(result, 8, h2)
        Blocks.writeIntBigEndian(result, 12, h3)
        Blocks.writeIntBigEndian(result, 16, h4)
        result
    end hashArrays

end Sha1
