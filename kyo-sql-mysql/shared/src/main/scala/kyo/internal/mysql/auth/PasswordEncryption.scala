package kyo.internal.mysql.auth

import kyo.*
import kyo.SqlRequestException
import kyo.SqlRequestRsaKeyTooLargeException
import kyo.SqlRequestRsaOaepException
import kyo.crypto.*

/** The password's RSA-OAEP round for `sha256_password` and `caching_sha2_password`: the server's PEM key parsed with
  * [[kyo.crypto.Rsa.encryptionKeyFromPem]], a 20-byte seed drawn from the ambient [[kyo.SecureRandom]], the ciphertext from
  * [[kyo.crypto.RsaOaep.encryptSha1]], and every refusal of the parser or the encoder mapped to this module's leaf with its position and tag.
  *
  * Primary entry point: [[PasswordEncryption.encrypt]].
  */
private[kyo] object PasswordEncryption:

    /** Encrypts `plaintext` to the server's key with RSA-OAEP, SHA-1 and MGF1-SHA-1, the padding MySQL's plugins expect.
      *
      * @param publicKeyPem
      *   PEM-encoded SubjectPublicKeyInfo RSA public key string ("-----BEGIN PUBLIC KEY-----" header)
      * @param plaintext
      *   message to encrypt (at most k - 42 bytes, where k is the key size in bytes)
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
                random.nextBytes(20).map { seed =>
                    RsaOaep.encryptSha1(key, plaintext, seed) match
                        case Result.Success(ciphertext)                                => ciphertext
                        case Result.Failure(RsaOaep.Failure.PlaintextTooLong(maximum)) =>
                            // The plaintext is the password, so its length stays out of the message.
                            Abort.fail(SqlRequestRsaOaepException(
                                "EME-OAEP",
                                "plaintext-length",
                                new Exception(s"plaintext longer than $maximum bytes, the most a ${key.sizeInBytes * 8}-bit key takes")
                            ))
                        case Result.Failure(RsaOaep.Failure.SeedLength(expected)) =>
                            bug(s"RSA-OAEP: the seed drawn here is 20 bytes, the encoder asked for $expected")
                        case Result.Panic(cause) => Abort.panic(cause)
                }
            case Result.Failure(failure) => Abort.fail(leaf(failure))
            case Result.Panic(cause)     => Abort.panic(cause)
        end match
    end encrypt

    /** This module's leaf for each way the shared parser can refuse a server's key. */
    private def leaf(failure: Rsa.SpkiFailure)(using Frame): SqlRequestException =
        import Rsa.BoundsFailure
        import Rsa.DerFailure
        import Rsa.SpkiFailure
        failure match
            case SpkiFailure.PemHeaderMissing =>
                SqlRequestRsaOaepException("PEM", "header-missing", new Exception("missing BEGIN PUBLIC KEY header"))
            case SpkiFailure.PemNotBase64(failure) =>
                SqlRequestRsaOaepException("PEM", "base64", new IllegalArgumentException(failure.message))
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
            case SpkiFailure.Bounds(BoundsFailure.ModulusTooLarge(bits, limit)) =>
                SqlRequestRsaKeyTooLargeException(SqlRsaKeyComponent.Modulus, bits, limit)
            case SpkiFailure.Bounds(BoundsFailure.ExponentTooLarge(bits, limit)) =>
                SqlRequestRsaKeyTooLargeException(SqlRsaKeyComponent.Exponent, bits, limit)
            case SpkiFailure.Bounds(BoundsFailure.ModulusTooSmall(bits, minimum)) =>
                SqlRequestRsaKeyTooSmallException(SqlRsaKeyComponent.Modulus, BigInt(bits), BigInt(minimum))
            case SpkiFailure.Bounds(BoundsFailure.ExponentTooSmall(exponent)) =>
                SqlRequestRsaKeyTooSmallException(SqlRsaKeyComponent.Exponent, exponent, BigInt(3))
            case SpkiFailure.Bounds(BoundsFailure.ModulusEven) =>
                SqlRequestRsaKeyEvenException(SqlRsaKeyComponent.Modulus)
            case SpkiFailure.Bounds(BoundsFailure.ExponentEven(_)) =>
                SqlRequestRsaKeyEvenException(SqlRsaKeyComponent.Exponent)
        end match
    end leaf

end PasswordEncryption
