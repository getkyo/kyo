package kyo.internal.auth

/** Pure-Scala cryptographic primitives for Scala Native.
  *
  * javax.crypto / java.security.MessageDigest are unavailable on Scala Native. This module implements the subset needed by kyo-sql auth:
  *   - SHA-256 (FIPS 180-4)
  *   - MD5 (RFC 1321)
  *   - HMAC-SHA-256 (RFC 2104)
  *   - PBKDF2-HMAC-SHA-256 (RFC 2898 §5.2)
  *
  * RSA-OAEP (needed for CachingSha2 full-auth over non-TLS) is NOT implemented here. That path throws UnsupportedOperationException — see
  * CachingSha2.scala.
  *
  * These implementations are correct but not constant-time. They are acceptable for kyo-sql because:
  *   - SHA-256/HMAC are used for SCRAM (a protocol that is already timing-safe at the protocol level via the server nonce).
  *   - MD5 is a legacy PostgreSQL auth mode. Constant-time MD5 would not add meaningful security.
  *   - SHA-1 (mysql_native_password) is a deprecated auth mode in MySQL 8.0+. Same argument.
  */
private[kyo] object PureHash:

    // --- SHA-256 ---

    /** SHA-256 round constants (first 32 bits of the fractional parts of the cube roots of the first 64 primes). */
    private val K: Array[Int] = Array(
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

    /** Computes SHA-256 of the input bytes. */
    def sha256(input: Array[Byte]): Array[Byte] =
        // Initial hash values (first 32 bits of fractional parts of sqrt of first 8 primes)
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

        // Pad: append 0x80 then zeros then 64-bit big-endian length, total length ≡ 56 mod 64
        val padded =
            val padLen = ((55 - msgLen % 64 + 64) % 64) + 1
            val total  = msgLen + padLen + 8
            val buf    = new Array[Byte](total)
            java.lang.System.arraycopy(input, 0, buf, 0, msgLen)
            buf(msgLen) = 0x80.toByte
            // Append bit length as big-endian 64-bit
            var shift = 56
            var i     = total - 8
            while shift >= 0 do
                buf(i) = ((bitLen >>> shift) & 0xff).toByte
                i += 1
                shift -= 8
            end while
            buf
        end padded

        val w = new Array[Int](64)

        // Process 512-bit (64-byte) blocks
        var blockOffset = 0
        while blockOffset < padded.length do
            // Prepare message schedule
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

            // Initialize working variables
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7

            // Compression
            j = 0
            while j < 64 do
                val S1    = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
                val ch    = (e & f) ^ (~e & g)
                val temp1 = h + S1 + ch + K(j) + w(j)
                val S0    = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
                val maj   = (a & b) ^ (a & c) ^ (b & c)
                val temp2 = S0 + maj
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

            blockOffset += 64
        end while

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
    end sha256

    // --- MD5 ---

    /** MD5 per-round shift amounts. */
    private val S: Array[Int] = Array(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    )

    /** MD5 precomputed table T[i] = floor(2^32 * abs(sin(i+1))). */
    private val T: Array[Int] = Array(
        0xd76aa478.toInt,
        0xe8c7b756.toInt,
        0x242070db,
        0xc1bdceee.toInt,
        0xf57c0faf.toInt,
        0x4787c62a,
        0xa8304613.toInt,
        0xfd469501.toInt,
        0x698098d8,
        0x8b44f7af.toInt,
        0xffff5bb1.toInt,
        0x895cd7be.toInt,
        0x6b901122,
        0xfd987193.toInt,
        0xa679438e.toInt,
        0x49b40821,
        0xf61e2562.toInt,
        0xc040b340.toInt,
        0x265e5a51,
        0xe9b6c7aa.toInt,
        0xd62f105d.toInt,
        0x02441453,
        0xd8a1e681.toInt,
        0xe7d3fbc8.toInt,
        0x21e1cde6,
        0xc33707d6.toInt,
        0xf4d50d87.toInt,
        0x455a14ed,
        0xa9e3e905.toInt,
        0xfcefa3f8.toInt,
        0x676f02d9,
        0x8d2a4c8a.toInt,
        0xfffa3942.toInt,
        0x8771f681.toInt,
        0x6d9d6122,
        0xfde5380c.toInt,
        0xa4beea44.toInt,
        0x4bdecfa9,
        0xf6bb4b60.toInt,
        0xbebfbc70.toInt,
        0x289b7ec6,
        0xeaa127fa.toInt,
        0xd4ef3085.toInt,
        0x04881d05,
        0xd9d4d039.toInt,
        0xe6db99e5.toInt,
        0x1fa27cf8,
        0xc4ac5665.toInt,
        0xf4292244.toInt,
        0x432aff97,
        0xab9423a7.toInt,
        0xfc93a039.toInt,
        0x655b59c3,
        0x8f0ccc92.toInt,
        0xffeff47d.toInt,
        0x85845dd1.toInt,
        0x6fa87e4f,
        0xfe2ce6e0.toInt,
        0xa3014314.toInt,
        0x4e0811a1,
        0xf7537e82.toInt,
        0xbd3af235.toInt,
        0x2ad7d2bb,
        0xeb86d391.toInt
    )

    /** Computes MD5 of the input bytes. Returns 16-byte digest. */
    def md5(input: Array[Byte]): Array[Byte] =
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt
        var c0 = 0x98badcfe.toInt
        var d0 = 0x10325476

        val msgLen = input.length
        val bitLen = msgLen.toLong * 8L

        // Pad: append 0x80, then zeros, then little-endian 64-bit length; total ≡ 0 mod 64
        val padded =
            val padLen = ((55 - msgLen % 64 + 64) % 64) + 1
            val total  = msgLen + padLen + 8
            val buf    = new Array[Byte](total)
            java.lang.System.arraycopy(input, 0, buf, 0, msgLen)
            buf(msgLen) = 0x80.toByte
            // Append bit length as little-endian 64-bit
            var i    = total - 8
            var bits = bitLen
            while i < total do
                buf(i) = (bits & 0xff).toByte
                bits >>>= 8
                i += 1
            end while
            buf
        end padded

        val M = new Array[Int](16)

        var blockOffset = 0
        while blockOffset < padded.length do
            // Load block as 16 little-endian 32-bit words
            var j = 0
            while j < 16 do
                M(j) = ((padded(blockOffset + j * 4) & 0xff)) |
                    ((padded(blockOffset + j * 4 + 1) & 0xff) << 8) |
                    ((padded(blockOffset + j * 4 + 2) & 0xff) << 16) |
                    ((padded(blockOffset + j * 4 + 3) & 0xff) << 24)
                j += 1
            end while

            var A = a0
            var B = b0
            var C = c0
            var D = d0

            j = 0
            while j < 64 do
                val fval: Int =
                    if j < 16 then (B & C) | (~B & D)
                    else if j < 32 then (D & B) | (~D & C)
                    else if j < 48 then B ^ C ^ D
                    else C ^ (B | ~D)
                val gidx: Int =
                    if j < 16 then j
                    else if j < 32 then (5 * j + 1) % 16
                    else if j < 48 then (3 * j + 5) % 16
                    else (7 * j)                    % 16
                val dtemp = D
                D = C
                C = B
                B = B + Integer.rotateLeft(A + fval + T(j) + M(gidx), S(j))
                A = dtemp
                j += 1
            end while

            a0 += A
            b0 += B
            c0 += C
            d0 += D

            blockOffset += 64
        end while

        val result = new Array[Byte](16)
        writeIntLE(result, 0, a0)
        writeIntLE(result, 4, b0)
        writeIntLE(result, 8, c0)
        writeIntLE(result, 12, d0)
        result
    end md5

    /** Converts MD5 digest to lowercase hex string. */
    def md5Hex(input: Array[Byte]): String =
        val bytes = md5(input)
        bytes.map(b => "%02x".format(b & 0xff)).mkString
    end md5Hex

    // --- SHA-1 ---

    /** Computes SHA-1 of the input bytes. Returns 20-byte digest.
      *
      * SHA-1 is used only for mysql_native_password (a deprecated MySQL auth mode). Not recommended for new code.
      */
    def sha1(input: Array[Byte]): Array[Byte] =
        var h0 = 0x67452301
        var h1 = 0xefcdab89.toInt
        var h2 = 0x98badcfe.toInt
        var h3 = 0x10325476
        var h4 = 0xc3d2e1f0.toInt

        val msgLen = input.length
        val bitLen = msgLen.toLong * 8L
        val padLen = ((55 - msgLen % 64 + 64) % 64) + 1
        val total  = msgLen + padLen + 8
        val padded = new Array[Byte](total)
        java.lang.System.arraycopy(input, 0, padded, 0, msgLen)
        padded(msgLen) = 0x80.toByte
        // Append big-endian 64-bit bit length
        var shift = 56
        var idx   = total - 8
        while shift >= 0 do
            padded(idx) = ((bitLen >>> shift) & 0xff).toByte
            idx += 1
            shift -= 8
        end while

        val w = new Array[Int](80)

        var blockOffset = 0
        while blockOffset < total do
            var j = 0
            while j < 16 do
                w(j) = ((padded(blockOffset + j * 4) & 0xff) << 24) |
                    ((padded(blockOffset + j * 4 + 1) & 0xff) << 16) |
                    ((padded(blockOffset + j * 4 + 2) & 0xff) << 8) |
                    (padded(blockOffset + j * 4 + 3) & 0xff)
                j += 1
            end while
            while j < 80 do
                w(j) = Integer.rotateLeft(w(j - 3) ^ w(j - 8) ^ w(j - 14) ^ w(j - 16), 1)
                j += 1

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4

            j = 0
            while j < 80 do
                val (f, k) =
                    if j < 20 then ((b & c) | (~b & d), 0x5a827999)
                    else if j < 40 then (b ^ c ^ d, 0x6ed9eba1)
                    else if j < 60 then ((b & c) | (b & d) | (c & d), 0x8f1bbcdc.toInt)
                    else (b ^ c ^ d, 0xca62c1d6.toInt)
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

            blockOffset += 64
        end while

        val result = new Array[Byte](20)
        writeInt(result, 0, h0)
        writeInt(result, 4, h1)
        writeInt(result, 8, h2)
        writeInt(result, 12, h3)
        writeInt(result, 16, h4)
        result
    end sha1

    // --- HMAC-SHA-256 ---

    private val SHA256_BLOCK_SIZE = 64

    /** HMAC-SHA-256 per RFC 2104.
      *
      * If key is longer than 64 bytes, it is hashed first. Key is zero-padded to 64 bytes.
      */
    def hmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte] =
        val normalizedKey =
            if key.length > SHA256_BLOCK_SIZE then sha256(key)
            else key
        val paddedKey = new Array[Byte](SHA256_BLOCK_SIZE)
        java.lang.System.arraycopy(normalizedKey, 0, paddedKey, 0, normalizedKey.length)

        val ipad = new Array[Byte](SHA256_BLOCK_SIZE)
        val opad = new Array[Byte](SHA256_BLOCK_SIZE)
        var i    = 0
        while i < SHA256_BLOCK_SIZE do
            ipad(i) = (paddedKey(i) ^ 0x36).toByte
            opad(i) = (paddedKey(i) ^ 0x5c).toByte
            i += 1
        end while

        val inner = new Array[Byte](SHA256_BLOCK_SIZE + data.length)
        java.lang.System.arraycopy(ipad, 0, inner, 0, SHA256_BLOCK_SIZE)
        java.lang.System.arraycopy(data, 0, inner, SHA256_BLOCK_SIZE, data.length)
        val innerHash = sha256(inner)

        val outer = new Array[Byte](SHA256_BLOCK_SIZE + 32)
        java.lang.System.arraycopy(opad, 0, outer, 0, SHA256_BLOCK_SIZE)
        java.lang.System.arraycopy(innerHash, 0, outer, SHA256_BLOCK_SIZE, 32)
        sha256(outer)
    end hmacSha256

    // --- PBKDF2-HMAC-SHA-256 ---

    /** PBKDF2-HMAC-SHA-256 per RFC 2898 §5.2.
      *
      * DK = T1 || T2 || … where Ti = U1 XOR U2 XOR … XOR Uc U1 = HMAC(P, S || INT(i)); Uc = HMAC(P, Uc-1)
      */
    def pbkdf2HmacSha256(password: Array[Byte], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte] =
        val hLen   = 32 // SHA-256 output length
        val blocks = (keyLength + hLen - 1) / hLen
        val result = new Array[Byte](keyLength)
        var pos    = 0
        var block  = 1
        while block <= blocks do
            // U1 = HMAC(password, salt || INT(block))
            val saltBlock = new Array[Byte](salt.length + 4)
            java.lang.System.arraycopy(salt, 0, saltBlock, 0, salt.length)
            saltBlock(salt.length) = ((block >>> 24) & 0xff).toByte
            saltBlock(salt.length + 1) = ((block >>> 16) & 0xff).toByte
            saltBlock(salt.length + 2) = ((block >>> 8) & 0xff).toByte
            saltBlock(salt.length + 3) = (block & 0xff).toByte

            var u = hmacSha256(password, saltBlock)
            val t = u.clone()

            var c = 1
            while c < iterations do
                u = hmacSha256(password, u)
                var i = 0
                while i < hLen do
                    t(i) = (t(i) ^ u(i)).toByte
                    i += 1
                c += 1
            end while

            val copyLen = math.min(hLen, keyLength - pos)
            java.lang.System.arraycopy(t, 0, result, pos, copyLen)
            pos += copyLen
            block += 1
        end while
        result
    end pbkdf2HmacSha256

    // --- Utilities ---

    /** Write big-endian 32-bit integer. */
    private def writeInt(buf: Array[Byte], offset: Int, value: Int): Unit =
        buf(offset) = ((value >>> 24) & 0xff).toByte
        buf(offset + 1) = ((value >>> 16) & 0xff).toByte
        buf(offset + 2) = ((value >>> 8) & 0xff).toByte
        buf(offset + 3) = (value & 0xff).toByte
    end writeInt

    /** Write little-endian 32-bit integer (for MD5). */
    private def writeIntLE(buf: Array[Byte], offset: Int, value: Int): Unit =
        buf(offset) = (value & 0xff).toByte
        buf(offset + 1) = ((value >>> 8) & 0xff).toByte
        buf(offset + 2) = ((value >>> 16) & 0xff).toByte
        buf(offset + 3) = ((value >>> 24) & 0xff).toByte
    end writeIntLE

end PureHash
