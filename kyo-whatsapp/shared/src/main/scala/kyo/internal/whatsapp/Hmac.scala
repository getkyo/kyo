package kyo.internal.whatsapp

import scala.annotation.tailrec

/** Pure-Scala SHA-256 (FIPS 180-4) and HMAC-SHA256 (RFC 2104) for webhook signature
  * verification, plus lowercase-hex rendering and a constant-time byte comparison.
  *
  * Avoids java.security.MessageDigest and javax.crypto.Mac, which are unavailable on
  * Scala Native and JS, mirroring the WebSocket Sha1 precedent. The implementation is
  * integer arithmetic only, so it is identical on every platform. The digest comparison
  * is constant-time: it inspects the full byte width regardless of where a mismatch
  * occurs, so the expected digest is not leaked through a timing side channel. This is
  * not a general-purpose crypto facility; it is the irreducible minimum the
  * X-Hub-Signature-256 check needs.
  */
private[kyo] object Hmac:

    final private val BlockSize = 64 // SHA-256 block size in bytes

    final private val K: Array[Int] = Array(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    )

    /** The SHA-256 digest of the input, a 32-byte array (FIPS 180-4). */
    def sha256(input: Array[Byte]): Array[Byte] =
        var h0 = 0x6a09e667; var h1 = 0xbb67ae85; var h2 = 0x3c6ef372; var h3 = 0xa54ff53a
        var h4 = 0x510e527f; var h5 = 0x9b05688c; var h6 = 0x1f83d9ab; var h7 = 0x5be0cd19

        val msgLen   = input.length
        val bitLen   = msgLen.toLong * 8
        val padLen   = (55 - msgLen % 64 + 64) % 64 + 1
        val totalLen = msgLen + padLen + 8
        val padded   = new Array[Byte](totalLen)
        java.lang.System.arraycopy(input, 0, padded, 0, msgLen)
        padded(msgLen) = 0x80.toByte
        @tailrec def appendBitLength(shift: Int, i: Int): Unit =
            if shift >= 0 then
                padded(i) = ((bitLen >>> shift) & 0xff).toByte
                appendBitLength(shift - 8, i + 1)
        appendBitLength(56, totalLen - 8)

        val w = new Array[Int](64)
        @tailrec def loadWords(j: Int, offset: Int): Unit =
            if j < 16 then
                w(j) = ((padded(offset + j * 4) & 0xff) << 24) |
                    ((padded(offset + j * 4 + 1) & 0xff) << 16) |
                    ((padded(offset + j * 4 + 2) & 0xff) << 8) |
                    (padded(offset + j * 4 + 3) & 0xff)
                loadWords(j + 1, offset)
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
                val temp1 = h + s1 + ch + K(j) + w(j)
                val s0    = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
                val maj   = (a & b) ^ (a & c) ^ (b & c)
                val temp2 = s0 + maj
                compress(j + 1, temp1 + temp2, a, b, c, d + temp1, e, f, g)
        @tailrec def processBlocks(offset: Int): Unit =
            if offset < totalLen then
                loadWords(0, offset)
                extendWords(16)
                val (a, b, c, d, e, f, g, h) = compress(0, h0, h1, h2, h3, h4, h5, h6, h7)
                h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
                processBlocks(offset + 64)
        processBlocks(0)

        val result = new Array[Byte](32)
        writeInt(result, 0, h0); writeInt(result, 4, h1); writeInt(result, 8, h2); writeInt(result, 12, h3)
        writeInt(result, 16, h4); writeInt(result, 20, h5); writeInt(result, 24, h6); writeInt(result, 28, h7)
        result
    end sha256

    /** HMAC-SHA256 of `message` under `key` (RFC 2104): a 32-byte tag. */
    def hmacSha256(key: Array[Byte], message: Array[Byte]): Array[Byte] =
        val blockKey =
            if key.length > BlockSize then
                val k = new Array[Byte](BlockSize)
                java.lang.System.arraycopy(sha256(key), 0, k, 0, 32)
                k
            else
                val k = new Array[Byte](BlockSize)
                java.lang.System.arraycopy(key, 0, k, 0, key.length)
                k
        val oKeyPad = new Array[Byte](BlockSize)
        val iKeyPad = new Array[Byte](BlockSize)
        @tailrec def pad(i: Int): Unit =
            if i < BlockSize then
                oKeyPad(i) = (blockKey(i) ^ 0x5c).toByte
                iKeyPad(i) = (blockKey(i) ^ 0x36).toByte
                pad(i + 1)
        pad(0)
        val inner = new Array[Byte](BlockSize + message.length)
        java.lang.System.arraycopy(iKeyPad, 0, inner, 0, BlockSize)
        java.lang.System.arraycopy(message, 0, inner, BlockSize, message.length)
        val innerHash = sha256(inner)
        val outer     = new Array[Byte](BlockSize + 32)
        java.lang.System.arraycopy(oKeyPad, 0, outer, 0, BlockSize)
        java.lang.System.arraycopy(innerHash, 0, outer, BlockSize, 32)
        sha256(outer)
    end hmacSha256

    /** Lowercase hexadecimal rendering of the bytes (the X-Hub-Signature-256 form). */
    def hexLower(bytes: Array[Byte]): String =
        val sb = new StringBuilder(bytes.length * 2)
        @tailrec def loop(i: Int): Unit =
            if i < bytes.length then
                val v = bytes(i) & 0xff
                sb.append(HexChars(v >>> 4))
                sb.append(HexChars(v & 0x0f))
                loop(i + 1)
        loop(0)
        sb.toString
    end hexLower

    /** Constant-time equality over two byte arrays: inspects the full width regardless of
      * where bytes differ, so no timing side channel leaks the expected digest. Unequal
      * lengths return false but still iterate the longer length.
      */
    def constantTimeEquals(a: Array[Byte], b: Array[Byte]): Boolean =
        val len  = math.max(a.length, b.length)
        var diff = a.length ^ b.length
        @tailrec def loop(i: Int): Unit =
            if i < len then
                val av = if i < a.length then a(i) else 0
                val bv = if i < b.length then b(i) else 0
                diff |= (av ^ bv)
                loop(i + 1)
        loop(0)
        diff == 0
    end constantTimeEquals

    final private val HexChars: Array[Char] = "0123456789abcdef".toCharArray

    private def writeInt(buf: Array[Byte], offset: Int, value: Int): Unit =
        buf(offset) = ((value >>> 24) & 0xff).toByte
        buf(offset + 1) = ((value >>> 16) & 0xff).toByte
        buf(offset + 2) = ((value >>> 8) & 0xff).toByte
        buf(offset + 3) = (value & 0xff).toByte
    end writeInt

end Hmac
