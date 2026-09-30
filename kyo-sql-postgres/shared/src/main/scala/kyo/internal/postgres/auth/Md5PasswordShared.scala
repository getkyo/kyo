package kyo.internal.postgres.auth

import java.nio.charset.StandardCharsets
import kyo.Hex
import kyo.Span
import kyo.crypto.Md5

/** MD5 password hashing for PostgreSQL AuthenticationMD5Password.
  *
  * Formula: "md5" + hex(MD5(hex(MD5(password + user)) + rawSalt))
  *
  * The outer hash concatenates the hex digest of the inner hash with the raw 4-byte salt bytes (not hex-encoded).
  *
  * Digests come from kyo-crypto's [[kyo.crypto.Md5]], so the same bytes are produced on every platform without `java.security.MessageDigest`.
  *
  * Reference: PostgreSQL §55.2.4 "MD5 Authentication"
  */
private[kyo] object Md5PasswordShared:

    /** Computes the MD5-hashed password for PostgreSQL authentication.
      *
      * @param password
      *   the user's plaintext password
      * @param user
      *   the username
      * @param salt
      *   the 4-byte salt from the AuthenticationMD5Password message
      * @return
      *   the "md5" + 32-hex-character string to send as the PasswordMessage
      */
    def encode(password: String, user: String, salt: Span[Byte]): String =
        val inner = Hex.encodeArray(Md5.hashArray((password + user).getBytes(StandardCharsets.UTF_8)))
        // outer input: inner hex string bytes + raw salt bytes
        val outerInput = inner.getBytes(StandardCharsets.US_ASCII) ++ salt.toArray
        "md5" + Hex.encodeArray(Md5.hashArray(outerInput))
    end encode

end Md5PasswordShared
