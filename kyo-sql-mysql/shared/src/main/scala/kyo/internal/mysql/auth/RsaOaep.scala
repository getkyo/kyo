package kyo.internal.mysql.auth

import kyo.*
import kyo.SqlRequestException
import kyo.SqlRequestRsaKeyTooLargeException
import kyo.SqlRequestRsaOaepException
import kyo.internal.crypto.Bytes
import kyo.internal.crypto.Mgf1
import kyo.internal.crypto.Rsa
import kyo.internal.crypto.Sha1

/** RSA-OAEP encryption (RFC 8017 section 7.1.1) with SHA-1 and MGF1-SHA-1, the padding MySQL's password plugins expect.
  *
  * Matches the byte-for-byte output of `javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")` when given the same random
  * seed. The key parsing, the mask generation and the RSA operation come from kyo-data's `kyo.internal.crypto`; this object holds the
  * encoding of the message and maps the parser's neutral failures to this module's leaves.
  *
  * Primary entry point: [[RsaOaep.encrypt]].
  */
private[kyo] object RsaOaep:

    // SHA-1 output length (hLen) per RFC 8017.
    private val hLen = 20

    // SHA-1 of empty string (lHash for empty label).
    // da39a3ee5e6b4b0d3255bfef95601890afd80709
    private val lHash: Array[Byte] = Sha1.hash(Array.empty[Byte])

    /** Encrypts `plaintext` using RSA-OAEP with SHA-1 / MGF1-SHA-1 and an empty label.
      *
      * Equivalent to `javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")` when seeded with the same `random` instance.
      *
      * @param publicKeyPem
      *   PEM-encoded SubjectPublicKeyInfo RSA public key string ("-----BEGIN PUBLIC KEY-----" header)
      * @param plaintext
      *   message to encrypt (must be ≤ k−2·hLen−2 bytes, where k is the key size in bytes)
      * @param random
      *   secure generator for the OAEP seed; pass the ambient [[kyo.SecureRandom]] in production
      * @return
      *   RSA-OAEP ciphertext as a [[Span]] of k bytes
      */
    def encrypt(
        publicKeyPem: String,
        plaintext: Span[Byte],
        random: SecureRandom
    )(using Frame): Span[Byte] < (Sync & Abort[SqlRequestException]) =
        Rsa.encryptionKeyFromPem(publicKeyPem) match
            case Result.Success(key) =>
                val k      = key.sizeInBytes
                val maxLen = k - 2 * hLen - 2
                val mLen   = plaintext.size
                if mLen > maxLen then
                    Abort.fail(SqlRequestRsaOaepException(
                        "EME-OAEP",
                        "plaintext-length",
                        new Exception(s"plaintext $mLen > max $maxLen for ${k * 8}-bit key")
                    ))
                else
                    random.nextBytes(hLen).map { seedSeq =>
                        val em = emeOaepEncode(plaintext.toArray, k, seedSeq.toArray)
                        Rsa.publicOperation(key, em) match
                            case Present(ciphertext) => Span.from(ciphertext)
                            case Absent => bug(s"RSA-OAEP: an encoded message of $k bytes starting with 0x00 is below the modulus")
                    }
                end if
            case Result.Failure(failure) => Abort.fail(leaf(failure))
            case Result.Panic(cause)     => Abort.panic(cause)
        end match
    end encrypt

    /** This module's leaf for each way the shared parser can refuse a server's key. */
    private def leaf(failure: Rsa.SpkiFailure)(using Frame): SqlRequestException =
        import Rsa.DerFailure
        import Rsa.SpkiFailure
        failure match
            case SpkiFailure.PemHeaderMissing =>
                SqlRequestRsaOaepException("PEM", "header-missing", new Exception("missing BEGIN PUBLIC KEY header"))
            case SpkiFailure.PemNotBase64(cause) =>
                SqlRequestRsaOaepException("PEM", "base64", cause)
            case SpkiFailure.Der(DerFailure.EndOfData(tag)) =>
                SqlRequestRsaOaepException("DER", s"tag-0x${tag.toHexString}", new Exception("unexpected end of data"))
            case SpkiFailure.Der(DerFailure.UnexpectedTag(tag, actual, offset)) =>
                SqlRequestRsaOaepException(
                    "DER",
                    s"tag-0x${tag.toHexString}",
                    new Exception(s"found 0x${actual.toHexString} at offset $offset")
                )
            case SpkiFailure.Der(DerFailure.LengthEndOfData) =>
                SqlRequestRsaOaepException("DER", "length", new Exception("unexpected end of data"))
            case SpkiFailure.Der(DerFailure.LengthUnsupported(offset)) =>
                SqlRequestRsaOaepException("DER", "length", new Exception(s"unsupported long-form at offset $offset"))
            case SpkiFailure.Der(DerFailure.SkipPastEnd(count, offset)) =>
                SqlRequestRsaOaepException("DER", "skip", new Exception(s"skip($count) past end at offset $offset"))
            case SpkiFailure.Der(DerFailure.IntegerExceedsData(offset)) =>
                SqlRequestRsaOaepException("DER", "integer", new Exception(s"exceeds data at offset $offset"))
            case SpkiFailure.ModulusTooLarge(bits, limit) =>
                SqlRequestRsaKeyTooLargeException(SqlRequestRsaKeyTooLargeException.Component.Modulus, bits, limit)
            case SpkiFailure.ExponentTooLarge(bits, limit) =>
                SqlRequestRsaKeyTooLargeException(SqlRequestRsaKeyTooLargeException.Component.Exponent, bits, limit)
        end match
    end leaf

    /** EME-OAEP-ENCODE per RFC 8017 §7.1.1. Returns a k-byte encoded message EM. */
    private def emeOaepEncode(m: Array[Byte], k: Int, seed: Array[Byte]): Array[Byte] =
        val mLen  = m.length
        val psLen = k - mLen - 2 * hLen - 2
        // DB = lHash || PS || 0x01 || M   (length = k - hLen - 1)
        val db = new Array[Byte](k - hLen - 1)
        java.lang.System.arraycopy(lHash, 0, db, 0, hLen)
        // PS bytes are already zero (Array.fill default)
        db(hLen + psLen) = 0x01.toByte
        java.lang.System.arraycopy(m, 0, db, hLen + psLen + 1, mLen)

        val dbMask     = Mgf1.sha1(seed, k - hLen - 1)
        val maskedDb   = Bytes.xor(db, dbMask)
        val seedMask   = Mgf1.sha1(maskedDb, hLen)
        val maskedSeed = Bytes.xor(seed, seedMask)

        // EM = 0x00 || maskedSeed || maskedDB
        val em = new Array[Byte](k)
        em(0) = 0x00.toByte
        java.lang.System.arraycopy(maskedSeed, 0, em, 1, hLen)
        java.lang.System.arraycopy(maskedDb, 0, em, 1 + hLen, k - hLen - 1)
        em
    end emeOaepEncode

end RsaOaep
