package kyo.internal.postgres.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** MD5 password hashing for PostgreSQL AuthenticationMD5Password.
  *
  * Formula: "md5" + hex(MD5(hex(MD5(password + user)) + rawSalt))
  *
  * The outer hash concatenates the hex digest of the inner hash with the raw 4-byte salt bytes (not hex-encoded).
  *
  * Reference: PostgreSQL §55.2.4 "MD5 Authentication"
  */
object Md5Password:

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
    def encode(password: String, user: String, salt: Array[Byte]): String =
        val inner = md5Hex((password + user).getBytes(StandardCharsets.UTF_8))
        // outer input: inner hex string bytes + raw salt bytes
        val outerInput = inner.getBytes(StandardCharsets.US_ASCII) ++ salt
        "md5" + md5Hex(outerInput)
    end encode

    /** Computes the MD5 digest of the input and returns it as a lowercase hex string. */
    private def md5Hex(input: Array[Byte]): String =
        val digest = MessageDigest.getInstance("MD5")
        val bytes  = digest.digest(input)
        bytes.map(b => "%02x".format(b & 0xff)).mkString
    end md5Hex

end Md5Password
