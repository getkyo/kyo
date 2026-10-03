package kyo.crypto

import kyo.*
import kyo.internal.crypto.Blocks

/** PBKDF2 (RFC 8018 section 5.2) with HMAC-SHA-256 as its pseudorandom function, the derivation SCRAM-SHA-256 salts a password with.
  *
  * `DK = T1 || T2 || ...` where `Ti = U1 xor U2 xor ... xor Uc`, `U1 = HMAC(P, S || INT(i))` and `Uj = HMAC(P, Uj-1)`.
  *
  * `iterations` must be at least 1 and `keyLength` at least 0; a value outside those bounds is refused with a [[Pbkdf2.Failure]] before
  * any work runs. The count is work the caller commits to before calling, and when it comes from a peer, as SCRAM's server-first message
  * carries it, the caller bounds it first. The derivation runs on the calling carrier with no suspension point.
  *
  * The password is the HMAC key for every one of the thousands of iterations, so the two padded key blocks it produces are derived once
  * and reused, and the three message buffers are allocated once and refilled: one for the outer hash, one for the first iteration's
  * salt-and-block-index message, and one for the digest-sized message of every iteration after it. Going through `Hmac.sha256` per
  * iteration would re-derive both pads and allocate per call, and key stretching multiplies anything per-iteration by the iteration count.
  *
  * @see
  *   [[Hmac.sha256]], the pseudorandom function
  * @see
  *   [[Sha256]], the hash under it
  * @see
  *   [[ConstantTime.isEqual]], the comparison for a proof derived from the key
  */
object Pbkdf2:

    /** Why [[hmacSha256]] derived nothing: an argument outside the bounds the derivation is defined for, carried as the value offered.
      *
      * @see
      *   [[hmacSha256]], which produces it
      */
    enum Failure derives CanEqual:

        /** An iteration count below 1, which RFC 8018 defines no derivation for. */
        case Iterations(iterations: Int)

        /** A negative key length. */
        case KeyLength(keyLength: Int)
    end Failure

    private val BlockSize = 64

    private val DigestSize = 32

    /** `keyLength` bytes derived from `password` and `salt` over `iterations` rounds, or the bound the arguments break. */
    def hmacSha256(password: Span[Byte], salt: Span[Byte], iterations: Int, keyLength: Int): Result[Failure, Span[Byte]] =
        // Unsafe: the password and salt arrays are only read; the key array is fresh and held by nothing else.
        hmacSha256Array(password.toArrayUnsafe, salt.toArrayUnsafe, iterations, keyLength).map(Span.fromUnsafe)

    private[kyo] def hmacSha256Array(
        password: Array[Byte],
        salt: Array[Byte],
        iterations: Int,
        keyLength: Int
    ): Result[Failure, Array[Byte]] =
        if iterations < 1 then Result.fail(Failure.Iterations(iterations))
        else if keyLength < 0 then Result.fail(Failure.KeyLength(keyLength))
        else Result.succeed(derive(password, salt, iterations, keyLength))

    private def derive(password: Array[Byte], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte] =
        val blocks = Blocks.count(keyLength, DigestSize)
        val result = new Array[Byte](keyLength)

        val normalizedKey =
            if password.length > BlockSize then Sha256.hashArray(password)
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
            val innerHash = Sha256.hashArray(innerBuf)
            java.lang.System.arraycopy(innerHash, 0, outerBuf, BlockSize, DigestSize)
            Sha256.hashArray(outerBuf)
        end hmacOverInner

        var pos   = 0
        var block = 1
        while block <= blocks do
            val blockIdx = BlockSize + salt.length
            firstInner(blockIdx) = ((block >>> 24) & 0xff).toByte
            firstInner(blockIdx + 1) = ((block >>> 16) & 0xff).toByte
            firstInner(blockIdx + 2) = ((block >>> 8) & 0xff).toByte
            firstInner(blockIdx + 3) = (block & 0xff).toByte
            val firstHash = Sha256.hashArray(firstInner)
            java.lang.System.arraycopy(firstHash, 0, outerBuf, BlockSize, DigestSize)
            var u = Sha256.hashArray(outerBuf)
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
    end derive

end Pbkdf2
