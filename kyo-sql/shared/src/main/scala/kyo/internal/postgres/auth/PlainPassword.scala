package kyo.internal.postgres.auth

import java.nio.charset.StandardCharsets

/** Cleartext password encoding for PostgreSQL AuthenticationCleartextPassword.
  *
  * Simply returns the password encoded as UTF-8 bytes.
  */
object PlainPassword:

    /** Encodes the password as UTF-8 bytes.
      *
      * @param password
      *   the plaintext password
      * @return
      *   UTF-8 bytes of the password
      */
    def encode(password: String): Array[Byte] =
        password.getBytes(StandardCharsets.UTF_8)

end PlainPassword
