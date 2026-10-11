package kyo.crypto

import kyo.*
import kyo.internal.crypto.Bytes

/** RSA-OAEP encryption (RFC 8017 section 7.1.1) with SHA-1, MGF1-SHA-1 and an empty label, the RFC's default instantiation (appendix
  * A.2.1).
  *
  * The output is byte for byte what `javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")` produces from the same
  * seed. The seed is the 20 random bytes EME-OAEP masks the message with, and it must come from a cryptographic source: the caller draws
  * it, from its `SecureRandom`, because this module has no effect dependency and a seed the function drew itself could not be pinned in
  * a test. A seed that is not exactly 20 bytes is an [[RsaOaep.Failure.SeedLength]].
  *
  * The key is a [[Rsa.EncryptionKey]], so its size was bounded before this runs. A plaintext longer than `k - 42` bytes, `k` the key's
  * length in bytes, is an [[RsaOaep.Failure.PlaintextTooLong]] carrying that maximum and never the plaintext's length, since the
  * plaintext is a password.
  *
  * SHA-1 here is a hash of the empty label and the expansion function inside MGF1, neither a commitment to attacker-chosen data, so
  * SHA-1's broken collision resistance does not bear on it. Nothing here is constant time: the seed and the plaintext go through the
  * same arithmetic whatever their values, but no claim is made about the modular exponentiation.
  *
  * @see
  *   [[Rsa.EncryptionKey]], the key it encrypts to, and [[Rsa.encryptionKeyFromPem]], which reads one
  * @see
  *   [[RsaOaep.Failure]], what it refuses with
  * @see
  *   [[Mgf1.sha1]], the mask generation inside the encoding
  * @see
  *   [[Sha1.hash]], the hash of the empty label
  */
object RsaOaep:

    /** Why [[encryptSha1]] produced no ciphertext: the plaintext exceeds what the key takes, or the seed is not the 20 bytes SHA-1 OAEP
      * masks with. Both are checked before any arithmetic, the seed first.
      *
      * Neither case carries a length of the plaintext or a byte of the seed: the plaintext is a password, and the seed is the one secret
      * the encoding has. [[Failure.PlaintextTooLong]] carries the maximum the key takes, `k - 42` for a key of `k` bytes, so a caller can
      * say what would fit; [[Failure.SeedLength]] carries the length required.
      *
      * @see
      *   [[encryptSha1]], which produces it
      * @see
      *   [[Rsa.EncryptionKey.sizeInBytes]], the `k` the maximum is derived from
      * @see
      *   [[Rsa.SpkiFailure]], the failures of reading the key itself
      */
    enum Failure derives CanEqual:

        /** The plaintext is longer than `maximum` bytes, the most the key's size allows. */
        case PlaintextTooLong(maximum: Int)

        /** The seed is not `expected` bytes long. */
        case SeedLength(expected: Int)

    end Failure

    /** SHA-1's output length, `hLen` in RFC 8017. */
    private val DigestSize = 20

    /** `SHA-1("")`, the `lHash` of an empty label. */
    private val EmptyLabelHash: Array[Byte] = Sha1.hashArray(Array.emptyByteArray)

    /** The ciphertext of `plaintext` under `key` with `seed` as the OAEP seed: exactly `key.sizeInBytes` bytes. No argument is modified. */
    def encryptSha1(key: Rsa.EncryptionKey, plaintext: Span[Byte], seed: Span[Byte]): Result[Failure, Span[Byte]] =
        val k       = key.sizeInBytes
        val maximum = k - 2 * DigestSize - 2
        if seed.size != DigestSize then Result.fail(Failure.SeedLength(DigestSize))
        else if plaintext.size > maximum then Result.fail(Failure.PlaintextTooLong(maximum))
        else
            // Unsafe: the plaintext and seed arrays are only read.
            val encoded = encode(plaintext.toArrayUnsafe, k, seed.toArrayUnsafe)
            Rsa.publicOperation(key, encoded) match
                // Unsafe: the ciphertext array is fresh and held by nothing else.
                case Present(ciphertext) => Result.succeed(Span.fromUnsafe(ciphertext))
                case Absent              => bug(s"RSA-OAEP: an encoded message of $k bytes starting with 0x00 is below the modulus")
            end match
        end if
    end encryptSha1

    /** EME-OAEP-ENCODE of RFC 8017 section 7.1.1, step 2: the `k`-byte `0x00 || maskedSeed || maskedDB` for `message` under `seed`. */
    private[kyo] def encode(message: Array[Byte], k: Int, seed: Array[Byte]): Array[Byte] =
        val paddingLength = k - message.length - 2 * DigestSize - 2
        // DB = lHash || PS || 0x01 || M, of k - hLen - 1 bytes; PS is the zero fill the fresh array already holds.
        val db = new Array[Byte](k - DigestSize - 1)
        java.lang.System.arraycopy(EmptyLabelHash, 0, db, 0, DigestSize)
        db(DigestSize + paddingLength) = 0x01.toByte
        java.lang.System.arraycopy(message, 0, db, DigestSize + paddingLength + 1, message.length)

        val dbMask     = mask(Mgf1.sha1Array(seed, k - DigestSize - 1))
        val maskedDb   = Bytes.xor(db, dbMask)
        val seedMask   = mask(Mgf1.sha1Array(maskedDb, DigestSize))
        val maskedSeed = Bytes.xor(seed, seedMask)

        val encoded = new Array[Byte](k)
        java.lang.System.arraycopy(maskedSeed, 0, encoded, 1, DigestSize)
        java.lang.System.arraycopy(maskedDb, 0, encoded, 1 + DigestSize, k - DigestSize - 1)
        encoded
    end encode

    /** The mask MGF1 produced. Both lengths OAEP asks for are positive for any key above the encryption floor, so a refusal is a bug. */
    private def mask(result: Result[Mgf1.Failure, Array[Byte]]): Array[Byte] =
        result match
            case Result.Success(bytes) => bytes
            case other                 => bug(s"RSA-OAEP: MGF1 refused a mask length the key bounds make positive: $other")
    end mask

end RsaOaep
