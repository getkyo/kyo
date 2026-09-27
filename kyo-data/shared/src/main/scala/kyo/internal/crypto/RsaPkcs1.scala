package kyo.internal.crypto

import kyo.*

/** RSASSA-PKCS1-v1_5 signature verification with SHA-256 (RFC 8017 section 8.2.2), the RS256 of JSON Web Signatures.
  *
  * The signature is raised to the public exponent and the result compared whole with the encoded message EMSA-PKCS1-v1_5 (section 9.2)
  * builds for the message's digest. Nothing in the recovered block is parsed, so a malformed padding, an alternative `DigestInfo` encoding
  * (a missing NULL parameter, long-form lengths, trailing bytes) or a different hash cannot verify: any block other than the one expected
  * is a mismatch.
  *
  * Note: the comparison is over public values (the signature and a digest of a message the verifier already holds), so it is not constant
  * time and does not need to be.
  */
private[kyo] object RsaPkcs1:

    /** DER prefix of the SHA-256 `DigestInfo`, RFC 8017 section 9.2, note 1. */
    private val Sha256DigestInfoPrefix: Array[Byte] =
        Array(
            0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00, 0x04, 0x20
        ).map(_.toByte)

    /** Whether `signature` is a valid RS256 signature of `message` under `key`. A signature whose length is not the modulus length in bytes,
      * or whose value is not below the modulus, is not valid. No argument is modified.
      */
    def verifySha256(key: Rsa.PublicKey, message: Array[Byte], signature: Array[Byte]): Boolean =
        Rsa.publicOperation(key, signature) match
            case Present(recovered) => java.util.Arrays.equals(recovered, encode(Sha256.hash(message), key.sizeInBytes))
            case Absent             => false

    /** EMSA-PKCS1-v1_5 for SHA-256: `0x00 0x01 PS 0x00 DigestInfo`, with `PS` the `0xff` bytes that fill `length`. */
    private def encode(digest: Array[Byte], length: Int): Array[Byte] =
        val infoLength = Sha256DigestInfoPrefix.length + digest.length
        val encoded    = new Array[Byte](length)
        encoded(1) = 0x01
        java.util.Arrays.fill(encoded, 2, length - infoLength - 1, 0xff.toByte)
        java.lang.System.arraycopy(Sha256DigestInfoPrefix, 0, encoded, length - infoLength, Sha256DigestInfoPrefix.length)
        java.lang.System.arraycopy(digest, 0, encoded, length - digest.length, digest.length)
        encoded
    end encode

end RsaPkcs1
