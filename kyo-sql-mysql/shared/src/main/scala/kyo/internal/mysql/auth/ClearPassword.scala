package kyo.internal.mysql.auth

import kyo.*

/** mysql_clear_password authentication helper.
  *
  * Implements the `mysql_clear_password` plugin used by PAM/LDAP backends and cloud-managed MySQL (e.g. RDS IAM auth, some Azure
  * offerings).
  *
  * Protocol: send the NUL-terminated UTF-8 password bytes as the auth response. No hashing, no encryption. Consequently this plugin MUST be
  * used only over a TLS connection. The caller ([[kyo.internal.mysql.exchange.HandshakeExchange]]) is responsible for enforcing that
  * constraint before calling [[encode]].
  *
  * References:
  *   - MySQL Internals Manual, Cleartext Plugin Authentication
  *   - MySQL 8.0 source: `libmysql/authentication_win/handshake.cc`
  *   - go-sql-driver/mysql auth.go, clearPasswordPlugin
  */
private[mysql] object ClearPassword:

    /** Encodes the password as a NUL-terminated UTF-8 byte span ready to send as the auth response.
      *
      * An absent (empty) password is encoded as a single NUL byte, matching MySQL's wire expectation.
      */
    def encode(password: Maybe[String]): Span[Byte] =
        password match
            case Maybe.Present(pw) =>
                val pwBytes = pw.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                val buf     = new Array[Byte](pwBytes.length + 1)
                java.lang.System.arraycopy(pwBytes, 0, buf, 0, pwBytes.length)
                buf(pwBytes.length) = 0.toByte
                Span.from(buf)
            case Maybe.Absent =>
                Span.from(Array(0.toByte))

end ClearPassword
