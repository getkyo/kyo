package kyo.internal.crypto

/** PBKDF2 (RFC 8018 section 5.2) with HMAC-SHA-256 as its pseudorandom function, the derivation SCRAM-SHA-256 salts a password with.
  *
  * `DK = T1 || T2 || ...` where `Ti = U1 xor U2 xor ... xor Uc`, `U1 = HMAC(P, S || INT(i))` and `Uj = HMAC(P, Uj-1)`.
  *
  * The password is the HMAC key for every one of the thousands of iterations, so the two padded key blocks it produces are derived once
  * and reused, and the three message buffers are allocated once and refilled: one for the outer hash, one for the first iteration's
  * salt-and-block-index message, and one for the digest-sized message of every iteration after it. Going through [[Hmac.sha256]] per
  * iteration would re-derive both pads and allocate per call, and key stretching multiplies anything per-iteration by the iteration
  * count the peer chose.
  */
private[kyo] object Pbkdf2:

    private val BlockSize = 64

    private val DigestSize = 32

    /** `keyLength` bytes derived from `password` and `salt` over `iterations` rounds. No argument is modified. */
    def hmacSha256(password: Array[Byte], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte] =
        val blocks = (keyLength + DigestSize - 1) / DigestSize
        val result = new Array[Byte](keyLength)

        val normalizedKey =
            if password.length > BlockSize then Sha256.hash(password)
            else password
        val ipad = new Array[Byte](BlockSize)
        val opad = new Array[Byte](BlockSize)
        var k    = 0
        while k < BlockSize do
            val kb = if k < normalizedKey.length then normalizedKey(k) else 0.toByte
            ipad(k) = (kb ^ 0x36).toByte
            opad(k) = (kb ^ 0x5c).toByte
            k += 1
        end while

        val outerBuf = new Array[Byte](BlockSize + DigestSize)
        java.lang.System.arraycopy(opad, 0, outerBuf, 0, BlockSize)
        val firstInner = new Array[Byte](BlockSize + salt.length + 4)
        java.lang.System.arraycopy(ipad, 0, firstInner, 0, BlockSize)
        java.lang.System.arraycopy(salt, 0, firstInner, BlockSize, salt.length)
        val innerBuf = new Array[Byte](BlockSize + DigestSize)
        java.lang.System.arraycopy(ipad, 0, innerBuf, 0, BlockSize)

        def hmacOverInner(): Array[Byte] =
            val innerHash = Sha256.hash(innerBuf)
            java.lang.System.arraycopy(innerHash, 0, outerBuf, BlockSize, DigestSize)
            Sha256.hash(outerBuf)
        end hmacOverInner

        var pos   = 0
        var block = 1
        while block <= blocks do
            val blockIdx = BlockSize + salt.length
            firstInner(blockIdx) = ((block >>> 24) & 0xff).toByte
            firstInner(blockIdx + 1) = ((block >>> 16) & 0xff).toByte
            firstInner(blockIdx + 2) = ((block >>> 8) & 0xff).toByte
            firstInner(blockIdx + 3) = (block & 0xff).toByte
            val firstHash = Sha256.hash(firstInner)
            java.lang.System.arraycopy(firstHash, 0, outerBuf, BlockSize, DigestSize)
            var u = Sha256.hash(outerBuf)
            val t = u.clone()

            var c = 1
            while c < iterations do
                java.lang.System.arraycopy(u, 0, innerBuf, BlockSize, DigestSize)
                u = hmacOverInner()
                var i = 0
                while i < DigestSize do
                    t(i) = (t(i) ^ u(i)).toByte
                    i += 1
                c += 1
            end while

            val copyLen = math.min(DigestSize, keyLength - pos)
            java.lang.System.arraycopy(t, 0, result, pos, copyLen)
            pos += copyLen
            block += 1
        end while
        result
    end hmacSha256

end Pbkdf2
