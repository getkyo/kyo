package kyo.crypto

import kyo.*

/** RSASSA-PKCS1-v1_5 signature verification with SHA-256 (RFC 8017 section 8.2.2), the RS256 of JSON Web Signatures.
  *
  * The signature is raised to the public exponent and the result compared whole with the encoded message EMSA-PKCS1-v1_5 (section 9.2)
  * builds for the message's digest. Nothing in the recovered block is parsed, so a malformed padding, an alternative `DigestInfo` encoding
  * (a missing NULL parameter, long-form lengths, trailing bytes) or a different hash cannot verify: any block other than the one expected
  * is a mismatch. A signature whose length is not the modulus length in bytes, or whose value is not below the modulus, is not valid, so
  * a valid signature plus the modulus does not verify.
  *
  * The key is a [[Rsa.VerificationKey]], which met RFC 7518's 2048-bit floor; a key that passed only the encryption checks cannot be
  * passed here.
  *
  * Note: the comparison is over public values (the signature and a digest of a message the verifier already holds), so it is not constant
  * time and does not need to be.
  *
  * @see
  *   [[Rsa.VerificationKey]], the key it takes, and [[Rsa.verificationKeyFromJwk]], which reads one
  * @see
  *   [[Sha256.hash]], the digest the encoded message is built around
  * @see
  *   [[Base64.decodeUrl]], the decoder a JWS signature goes through
  * @see
  *   [[Ed25519.verify]], the other signature verification of the module
  */
object RsaPkcs1:

    /** DER prefix of the SHA-256 `DigestInfo`, RFC 8017 section 9.2, note 1. */
    private val Sha256DigestInfoPrefix: Array[Byte] =
        Array(
            0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00, 0x04, 0x20
        ).map(_.toByte)

    /** Whether `signature` is a valid RS256 signature of `message` under `key`. No argument is modified. */
    def verifySha256(key: Rsa.VerificationKey, message: Span[Byte], signature: Span[Byte]): Boolean =
        // Unsafe: both arrays are only read.
        verifySha256Array(key, message.toArrayUnsafe, signature.toArrayUnsafe)

    private[kyo] def verifySha256Array(key: Rsa.VerificationKey, message: Array[Byte], signature: Array[Byte]): Boolean =
        Rsa.publicOperation(key, signature) match
            case Present(recovered) => java.util.Arrays.equals(recovered, encode(Sha256.hashArray(message), key.sizeInBytes))
            case Absent             => false

    /** EMSA-PKCS1-v1_5 for SHA-256: `0x00 0x01 PS 0x00 DigestInfo`, with `PS` the `0xff` bytes that fill `length`. */
    private def encode(digest: Array[Byte], length: Int): Array[Byte] =
        val infoLength = Sha256DigestInfoPrefix.length + digest.length
        // length is at least 256 because Rsa.VerificationKey enforces MinModulusBits; below infoLength + 11 the fill range is invalid.
        val encoded = new Array[Byte](length)
        encoded(1) = 0x01
        java.util.Arrays.fill(encoded, 2, length - infoLength - 1, 0xff.toByte)
        java.lang.System.arraycopy(Sha256DigestInfoPrefix, 0, encoded, length - infoLength, Sha256DigestInfoPrefix.length)
        java.lang.System.arraycopy(digest, 0, encoded, length - digest.length, digest.length)
        encoded
    end encode

end RsaPkcs1
