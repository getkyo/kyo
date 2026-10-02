package kyo.crypto

import kyo.*
import kyo.internal.crypto.Blocks
import scala.annotation.tailrec

/** SHA-256 (FIPS 180-4), in pure Scala, the one implementation every platform runs.
  *
  * `java.security.MessageDigest` is unavailable on Scala.js, Scala Native and Wasm, so the algorithm is written here once: pad the message,
  * split it into 512-bit blocks, and compress each block with the 64-round function. The output is 32 bytes.
  *
  * [[hash]] digests one value. [[hashAll]] digests the concatenation of several without building it, which is what a MAC or a
  * domain-separated derivation needs.
  *
  * A digest is not a MAC: to authenticate a message under a key use [[Hmac.sha256]], and to compare a received digest or tag use
  * [[ConstantTime.isEqual]], never `==`.
  *
  * @see
  *   [[Hmac.sha256]], the MAC built on it
  * @see
  *   [[Pbkdf2.hmacSha256]], the key derivation built on it
  * @see
  *   [[RsaPkcs1.verifySha256]], the signature verification built on it
  * @see
  *   [[Sha512]], the wider digest of the same family
  */
object Sha256:

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

    /** The 32-byte digest of `input`. */
    def hash(input: Span[Byte]): Span[Byte] =
        // Unsafe: the input array is only read; the digest array is fresh and held by nothing else.
        Span.fromUnsafe(hashArray(input.toArrayUnsafe))

    /** The 32-byte digest of the concatenation of `parts`, computed without concatenating them. */
    def hashAll(parts: Chunk[Span[Byte]]): Span[Byte] =
        // Unsafe: each part's array is only read; the digest array is fresh and held by nothing else.
        Span.fromUnsafe(hashArrays(parts.map(_.toArrayUnsafe)))

    private[kyo] def hashArray(input: Array[Byte]): Array[Byte] =
        hashArrays(Chunk(input))

    private[kyo] def hashArrays(inputs: Chunk[Array[Byte]]): Array[Byte] =
        var h0 = 0x6a09e667
        var h1 = 0xbb67ae85
        var h2 = 0x3c6ef372
        var h3 = 0xa54ff53a
        var h4 = 0x510e527f
        var h5 = 0x9b05688c
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val w = new Array[Int](64)

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
        def processBlock(input: Array[Byte], offset: Int): Unit =
            loadWords(input, 0, offset)
            extendWords(16)
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7
            var j = 0
            while j < 64 do
                val s1    = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
                val ch    = (e & f) ^ (~e & g)
                val temp1 = h + s1 + ch + k(j) + w(j)
                val s0    = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
                val maj   = (a & b) ^ (a & c) ^ (b & c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
                j += 1
            end while
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
        end processBlock

        val buffer = new Blocks.Buffer64(processBlock)
        inputs.foreach(buffer.update)
        buffer.finish(Blocks.writeLongBigEndian)

        val result = new Array[Byte](32)
        Blocks.writeIntBigEndian(result, 0, h0)
        Blocks.writeIntBigEndian(result, 4, h1)
        Blocks.writeIntBigEndian(result, 8, h2)
        Blocks.writeIntBigEndian(result, 12, h3)
        Blocks.writeIntBigEndian(result, 16, h4)
        Blocks.writeIntBigEndian(result, 20, h5)
        Blocks.writeIntBigEndian(result, 24, h6)
        Blocks.writeIntBigEndian(result, 28, h7)
        result
    end hashArrays

end Sha256
