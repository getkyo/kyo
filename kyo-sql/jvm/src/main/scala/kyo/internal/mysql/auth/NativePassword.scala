package kyo.internal.mysql.auth

import java.security.MessageDigest
import kyo.Span

/** mysql_native_password authentication helper.
  *
  * Computes the 20-byte auth response as specified by the MySQL wire protocol: SHA1(password) XOR SHA1(scramble ++ SHA1(SHA1(password)))
  *
  * Note: SHA-1 is used here solely because the MySQL wire protocol specifies it for mysql_native_password. This is NOT a recommendation to
  * use SHA-1 for new password hashing schemes.
  *
  * Reference: MySQL Internals Manual — mysql_native_password Authentication
  */
object NativePassword:

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
            val step1 = sha1(passwordBytes)
            // step2 = SHA1(SHA1(password))
            val step2 = sha1(step1)
            // step3 = SHA1(scramble ++ step2)
            val scrambleArr = scramble.toArray
            val combined    = new Array[Byte](scrambleArr.length + step2.length)
            java.lang.System.arraycopy(scrambleArr, 0, combined, 0, scrambleArr.length)
            java.lang.System.arraycopy(step2, 0, combined, scrambleArr.length, step2.length)
            val step3 = sha1(combined)
            // result = step1 XOR step3
            val result = new Array[Byte](20)
            var i      = 0
            while i < 20 do
                result(i) = (step1(i) ^ step3(i)).toByte
                i += 1
            end while
            Span.from(result)
        end if
    end computeResponse

    /** Computes SHA-1 of the given bytes. JDK MessageDigest is allowed for crypto primitives — see STEERING.md.
      */
    private def sha1(input: Array[Byte]): Array[Byte] =
        val md = MessageDigest.getInstance("SHA-1")
        md.digest(input)
    end sha1

end NativePassword
