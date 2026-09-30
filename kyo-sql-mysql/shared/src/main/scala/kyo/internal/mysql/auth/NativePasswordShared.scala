package kyo.internal.mysql.auth

import kyo.Span
import kyo.crypto.Sha1
import kyo.internal.crypto.Bytes

/** mysql_native_password authentication helper.
  *
  * Computes the 20-byte auth response as specified by the MySQL wire protocol: SHA1(password) XOR SHA1(scramble ++ SHA1(SHA1(password)))
  *
  * Digests come from kyo-crypto's [[kyo.crypto.Sha1]], the pure-Scala SHA-1, so the same bytes are produced on every platform without
  * `java.security.MessageDigest`.
  *
  * Note: SHA-1 is used here solely because the MySQL wire protocol specifies it for mysql_native_password. This is NOT a recommendation to
  * use SHA-1 for new password hashing schemes.
  *
  * Reference: MySQL Internals Manual, mysql_native_password Authentication
  */
private[mysql] object NativePasswordShared:

    /** Computes the mysql_native_password auth response.
      *
      * @param password
      *   the plaintext password; if empty, returns [[Span.empty]] (MySQL sentinel for "no password")
      * @param scramble
      *   the 20-byte challenge received in [[kyo.internal.mysql.HandshakeV10.authPluginData]]
      * @return
      *   20-byte auth response, or [[Span.empty]] if `password` is empty
      */
    def computeResponse(password: String, scramble: Span[Byte]): Span[Byte] =
        if password.isEmpty then Span.empty
        else
            val passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            // step1 = SHA1(password)
            val step1 = Sha1.hashArray(passwordBytes)
            // step2 = SHA1(SHA1(password))
            val step2 = Sha1.hashArray(step1)
            // step3 = SHA1(scramble ++ step2)
            val scrambleArr = scramble.toArray
            val combined    = new Array[Byte](scrambleArr.length + step2.length)
            java.lang.System.arraycopy(scrambleArr, 0, combined, 0, scrambleArr.length)
            java.lang.System.arraycopy(step2, 0, combined, scrambleArr.length, step2.length)
            val step3 = Sha1.hashArray(combined)
            // result = step1 XOR step3
            Span.from(Bytes.xor(step1, step3))
        end if
    end computeResponse

end NativePasswordShared
