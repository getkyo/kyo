package kyo.internal.postgres.auth

import java.util.Base64
import kyo.Maybe
import kyo.Span

/** Pure-Scala SCRAM-SHA-256 / SCRAM-SHA-256-PLUS implementation using only pure-Scala crypto (no javax.crypto).
  *
  * This class exists so that on JVM we can test byte-identical output between the pure-Scala path and the javax.crypto path (ScramSha256 on
  * JVM). On JVM, ScramSha256 is shadowed by the jvm/ override which uses javax.crypto, this class is never shadowed.
  *
  * Only used by tests (cross-platform parity leaf). Production code uses ScramSha256.
  *
  * Crypto is inlined here rather than delegating to PureHash, because PureHash is excluded from the JVM classpath by the build filter.
  */
final private[kyo] class ScramSha256Shared(username: String, clientNonce: String, channelBinding: Maybe[Span[Byte]])
    extends ScramSha256Base(username, clientNonce, channelBinding):

    // --- Inlined pure-Scala crypto (mirrors PureHash, which is excluded from JVM classpath) ---

    private val SHA256_K: Array[Int] = Array(
        0x428a2f98,
        0x71374491,
        0xb5c0fbcf.toInt,
        0xe9b5dba5.toInt,
        0x3956c25b,
        0x59f111f1,
        0x923f82a4.toInt,
        0xab1c5ed5.toInt,
        0xd807aa98.toInt,
        0x12835b01,
        0x243185be,
        0x550c7dc3,
        0x72be5d74,
        0x80deb1fe.toInt,
        0x9bdc06a7.toInt,
        0xc19bf174.toInt,
        0xe49b69c1.toInt,
        0xefbe4786.toInt,
        0x0fc19dc6,
        0x240ca1cc,
        0x2de92c6f,
        0x4a7484aa,
        0x5cb0a9dc,
        0x76f988da,
        0x983e5152.toInt,
        0xa831c66d.toInt,
        0xb00327c8.toInt,
        0xbf597fc7.toInt,
        0xc6e00bf3.toInt,
        0xd5a79147.toInt,
        0x06ca6351,
        0x14292967,
        0x27b70a85,
        0x2e1b2138,
        0x4d2c6dfc,
        0x53380d13,
        0x650a7354,
        0x766a0abb,
        0x81c2c92e.toInt,
        0x92722c85.toInt,
        0xa2bfe8a1.toInt,
        0xa81a664b.toInt,
        0xc24b8b70.toInt,
        0xc76c51a3.toInt,
        0xd192e819.toInt,
        0xd6990624.toInt,
        0xf40e3585.toInt,
        0x106aa070,
        0x19a4c116,
        0x1e376c08,
        0x2748774c,
        0x34b0bcb5,
        0x391c0cb3,
        0x4ed8aa4a,
        0x5b9cca4f,
        0x682e6ff3,
        0x748f82ee,
        0x78a5636f,
        0x84c87814.toInt,
        0x8cc70208.toInt,
        0x90befffa.toInt,
        0xa4506ceb.toInt,
        0xbef9a3f7.toInt,
        0xc67178f2.toInt
    )

    private[kyo] def sha256(input: Array[Byte]): Array[Byte] = pureSha256(input)

    private def pureSha256(input: Array[Byte]): Array[Byte] =
        var h0 = 0x6a09e667
        var h1 = 0xbb67ae85.toInt
        var h2 = 0x3c6ef372
        var h3 = 0xa54ff53a.toInt
        var h4 = 0x510e527f
        var h5 = 0x9b05688c.toInt
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val msgLen = input.length
        val bitLen = msgLen.toLong * 8L
        val padLen = ((55 - msgLen % 64 + 64) % 64) + 1
        val total  = msgLen + padLen + 8
        val padded = new Array[Byte](total)
        java.lang.System.arraycopy(input, 0, padded, 0, msgLen)
        padded(msgLen) = 0x80.toByte
        // Performance: while/var crypto loop, encapsulated pure-Scala block function; CONTRIBUTING permits this.
        var shift = 56
        var idx   = total - 8
        while shift >= 0 do
            padded(idx) = ((bitLen >>> shift) & 0xff).toByte
            idx += 1
            shift -= 8
        end while

        val w           = new Array[Int](64)
        var blockOffset = 0
        while blockOffset < padded.length do
            var j = 0
            while j < 16 do
                w(j) = ((padded(blockOffset + j * 4) & 0xff) << 24) |
                    ((padded(blockOffset + j * 4 + 1) & 0xff) << 16) |
                    ((padded(blockOffset + j * 4 + 2) & 0xff) << 8) |
                    (padded(blockOffset + j * 4 + 3) & 0xff)
                j += 1
            end while
            while j < 64 do
                val s0 = Integer.rotateRight(w(j - 15), 7) ^ Integer.rotateRight(w(j - 15), 18) ^ (w(j - 15) >>> 3)
                val s1 = Integer.rotateRight(w(j - 2), 17) ^ Integer.rotateRight(w(j - 2), 19) ^ (w(j - 2) >>> 10)
                w(j) = w(j - 16) + s0 + w(j - 7) + s1
                j += 1
            end while
            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var h = h7
            j = 0
            while j < 64 do
                val S1    = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
                val ch    = (e & f) ^ (~e & g)
                val temp1 = h + S1 + ch + SHA256_K(j) + w(j)
                val S0    = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
                val maj   = (a & b) ^ (a & c) ^ (b & c)
                val temp2 = S0 + maj
                h = g; g = f; f = e; e = d + temp1; d = c; c = b; b = a; a = temp1 + temp2
                j += 1
            end while
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
            blockOffset += 64
        end while

        val result = new Array[Byte](32)
        def wi(buf: Array[Byte], off: Int, v: Int): Unit =
            buf(off) = ((v >>> 24) & 0xff).toByte; buf(off + 1) = ((v >>> 16) & 0xff).toByte
            buf(off + 2) = ((v >>> 8) & 0xff).toByte; buf(off + 3) = (v & 0xff).toByte
        wi(result, 0, h0); wi(result, 4, h1); wi(result, 8, h2); wi(result, 12, h3)
        wi(result, 16, h4); wi(result, 20, h5); wi(result, 24, h6); wi(result, 28, h7)
        result
    end pureSha256

    private val HMAC_BLOCK = 64

    private[kyo] def hmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte] = pureHmacSha256(key, data)

    private def pureHmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte] =
        val nk = if key.length > HMAC_BLOCK then pureSha256(key) else key
        val pk = new Array[Byte](HMAC_BLOCK)
        java.lang.System.arraycopy(nk, 0, pk, 0, nk.length)
        val ipad = new Array[Byte](HMAC_BLOCK); val opad = new Array[Byte](HMAC_BLOCK)
        // Performance: while/var crypto loop, encapsulated pure-Scala block function; CONTRIBUTING permits this.
        var i = 0
        while i < HMAC_BLOCK do
            ipad(i) = (pk(i) ^ 0x36).toByte; opad(i) = (pk(i) ^ 0x5c).toByte; i += 1
        end while
        val inner = new Array[Byte](HMAC_BLOCK + data.length)
        java.lang.System.arraycopy(ipad, 0, inner, 0, HMAC_BLOCK)
        java.lang.System.arraycopy(data, 0, inner, HMAC_BLOCK, data.length)
        val ih    = pureSha256(inner)
        val outer = new Array[Byte](HMAC_BLOCK + 32)
        java.lang.System.arraycopy(opad, 0, outer, 0, HMAC_BLOCK)
        java.lang.System.arraycopy(ih, 0, outer, HMAC_BLOCK, 32)
        pureSha256(outer)
    end pureHmacSha256

    private[kyo] def pbkdf2HmacSha256(password: Array[Byte], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte] =
        purePbkdf2HmacSha256(password, salt, iterations, keyLength)

    private def purePbkdf2HmacSha256(password: Array[Byte], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte] =
        val hLen   = 32
        val blocks = (keyLength + hLen - 1) / hLen
        val result = new Array[Byte](keyLength)
        var pos    = 0
        var block  = 1
        // Performance: while/var crypto loop, encapsulated pure-Scala block function; CONTRIBUTING permits this.
        while block <= blocks do
            val saltBlock = new Array[Byte](salt.length + 4)
            java.lang.System.arraycopy(salt, 0, saltBlock, 0, salt.length)
            saltBlock(salt.length) = ((block >>> 24) & 0xff).toByte
            saltBlock(salt.length + 1) = ((block >>> 16) & 0xff).toByte
            saltBlock(salt.length + 2) = ((block >>> 8) & 0xff).toByte
            saltBlock(salt.length + 3) = (block & 0xff).toByte
            var u = pureHmacSha256(password, saltBlock)
            val t = u.clone()
            var c = 1
            while c < iterations do
                u = pureHmacSha256(password, u)
                var i = 0
                while i < hLen do
                    t(i) = (t(i) ^ u(i)).toByte; i += 1
                c += 1
            end while
            val copyLen = math.min(hLen, keyLength - pos)
            java.lang.System.arraycopy(t, 0, result, pos, copyLen)
            pos += copyLen
            block += 1
        end while
        result
    end purePbkdf2HmacSha256

end ScramSha256Shared

private[kyo] object ScramSha256Shared:
    def apply(username: String, clientNonce: String, channelBinding: Maybe[Span[Byte]]): ScramSha256Shared =
        new ScramSha256Shared(username, clientNonce, channelBinding)
end ScramSha256Shared
