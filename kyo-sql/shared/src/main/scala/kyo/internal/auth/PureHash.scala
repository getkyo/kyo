package kyo.internal.auth

/** Pure-Scala cryptographic primitives for kyo-sql auth.
  *
  * javax.crypto / java.security.MessageDigest are unavailable on Scala Native, so every digest here is pure Scala. SHA-256 and SHA-1
  * delegate to kyo-data's shared implementations ([[kyo.internal.Sha256]], [[kyo.internal.Sha1]]); this module adds what kyo-data does
  * not carry:
  *   - MD5 (RFC 1321)
  *   - HMAC-SHA-256 (RFC 2104)
  *   - PBKDF2-HMAC-SHA-256 (RFC 2898 §5.2)
  *
  * RSA-OAEP (needed for caching_sha2_password full-auth over non-TLS) lives in the MySQL backend module, not here.
  *
  * These implementations are correct but not constant-time. They are acceptable for kyo-sql because:
  *   - SHA-256/HMAC are used for SCRAM (a protocol that is already timing-safe at the protocol level via the server nonce).
  *   - MD5 is a legacy PostgreSQL auth mode. Constant-time MD5 would not add meaningful security.
  *   - SHA-1 (mysql_native_password) is a deprecated auth mode in MySQL 8.0+. Same argument.
  */
private[kyo] object PureHash:

    // --- SHA-256 ---

    /** Computes SHA-256 of the input bytes. Delegates to kyo-data's shared FIPS 180-4 implementation. */
    def sha256(input: Array[Byte]): Array[Byte] =
        kyo.internal.Sha256.hash(input)

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

    /** Computes SHA-1 of the input bytes, a 20-byte digest. Delegates to kyo-data's shared FIPS 180-4 implementation.
      *
      * SHA-1 is used only for mysql_native_password (a deprecated MySQL auth mode) and RSA-OAEP's MGF1, both fixed by
      * their protocols. Not for new code.
      */
    def sha1(input: Array[Byte]): Array[Byte] =
        kyo.internal.Sha1.hash(input)

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
      *
      * The password is the HMAC key for every one of the thousands of iterations, so the two padded key blocks it produces are derived
      * once here and reused, and the three message buffers are allocated once and refilled: one for the outer hash, one for the first
      * iteration's salt-and-block-index message, and one for the digest-sized message every iteration after it. Calling [[hmacSha256]]
      * per iteration instead re-derives both pads and allocates five arrays each time, which is the shape of work that decides what this
      * costs: key stretching is deliberately iterated, so anything per-iteration is multiplied by the server's iteration count.
      */
    def pbkdf2HmacSha256(password: Array[Byte], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte] =
        val hLen   = 32 // SHA-256 output length
        val blocks = (keyLength + hLen - 1) / hLen
        val result = new Array[Byte](keyLength)

        // The key schedule, derived once: ipad and opad are functions of the password alone.
        val normalizedKey =
            if password.length > SHA256_BLOCK_SIZE then sha256(password)
            else password
        val ipad = new Array[Byte](SHA256_BLOCK_SIZE)
        val opad = new Array[Byte](SHA256_BLOCK_SIZE)
        var k    = 0
        while k < SHA256_BLOCK_SIZE do
            val kb = if k < normalizedKey.length then normalizedKey(k) else 0.toByte
            ipad(k) = (kb ^ 0x36).toByte
            opad(k) = (kb ^ 0x5c).toByte
            k += 1
        end while

        // The outer message is always opad plus a digest. The inner message has two shapes, the salt block on the
        // first iteration of each output block and a digest on every one after it, so each gets its own buffer sized
        // exactly for it: sizing one at the larger and filling part of it would hash the unused tail as well.
        val outerBuf = new Array[Byte](SHA256_BLOCK_SIZE + hLen)
        java.lang.System.arraycopy(opad, 0, outerBuf, 0, SHA256_BLOCK_SIZE)
        val firstInner = new Array[Byte](SHA256_BLOCK_SIZE + salt.length + 4)
        java.lang.System.arraycopy(ipad, 0, firstInner, 0, SHA256_BLOCK_SIZE)
        java.lang.System.arraycopy(salt, 0, firstInner, SHA256_BLOCK_SIZE, salt.length)
        val innerBuf = new Array[Byte](SHA256_BLOCK_SIZE + hLen)
        java.lang.System.arraycopy(ipad, 0, innerBuf, 0, SHA256_BLOCK_SIZE)

        /** One HMAC round over a digest-sized message already sitting in `innerBuf` after the ipad block. */
        def hmacOverInner(): Array[Byte] =
            val innerHash = sha256(innerBuf)
            java.lang.System.arraycopy(innerHash, 0, outerBuf, SHA256_BLOCK_SIZE, hLen)
            sha256(outerBuf)
        end hmacOverInner

        var pos   = 0
        var block = 1
        while block <= blocks do
            // U1 = HMAC(password, salt || INT(block))
            val blockIdx = SHA256_BLOCK_SIZE + salt.length
            firstInner(blockIdx) = ((block >>> 24) & 0xff).toByte
            firstInner(blockIdx + 1) = ((block >>> 16) & 0xff).toByte
            firstInner(blockIdx + 2) = ((block >>> 8) & 0xff).toByte
            firstInner(blockIdx + 3) = (block & 0xff).toByte
            val firstHash = sha256(firstInner)
            java.lang.System.arraycopy(firstHash, 0, outerBuf, SHA256_BLOCK_SIZE, hLen)
            var u = sha256(outerBuf)
            val t = u.clone()

            var c = 1
            while c < iterations do
                java.lang.System.arraycopy(u, 0, innerBuf, SHA256_BLOCK_SIZE, hLen)
                u = hmacOverInner()
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

    // --- XOR ---

    /** XORs two byte arrays element-wise into a fresh `a.length`-byte array.
      *
      * Every caller passes equal-length operands, two digests or a block and its MGF1 mask, so only `a.length` bytes are read from each. The
      * auth schemes use this to combine a key with a signature (the SCRAM client proof, the caching_sha2 fast response, the
      * mysql_native_password response) and OAEP uses it to apply its masks. The resulting byte sequence is what the server re-derives, so it is
      * part of the wire contract.
      */
    def xor(a: Array[Byte], b: Array[Byte]): Array[Byte] =
        val out = new Array[Byte](a.length)
        var i   = 0
        while i < a.length do
            out(i) = (a(i) ^ b(i)).toByte
            i += 1
        end while
        out
    end xor

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
