package kyo.internal.mysql.auth

import kyo.*
import kyo.SqlRequestException
import kyo.internal.auth.RsaOaep

/** sha256_password authentication helper.
  *
  * Implements the legacy `sha256_password` plugin used by some MySQL 5.7 and 8.0 installations. This plugin lacks the server-side
  * credential cache that `caching_sha2_password` uses, so every connection performs the full RSA exchange (non-TLS) or cleartext-over-TLS.
  *
  * Protocol:
  *   - Over TLS: client sends NUL-terminated cleartext password directly (same as `caching_sha2_password` TLS path).
  *   - Over plaintext: client sends empty initial auth data; server replies with an AuthMoreData packet containing the PEM-encoded RSA
  *     public key; client XORs `(passwordBytes ++ [0x00])` with the 20-byte scramble (cycling), RSA-OAEP encrypts the result, and sends the
  *     ciphertext. The server decrypts, re-applies the XOR with the scramble, and recovers the plaintext password.
  *
  * The XOR-with-scramble step is identical to `caching_sha2_password`'s RSA full-auth path (see `sha2_password.cc` in MySQL 8.0 source).
  *
  * References:
  *   - MySQL Internals Manual, SHA-256 Pluggable Authentication
  *   - MySQL 8.0 source: `sql/auth/sha2_password.cc`, `Sha2_plain_context_handler::authenticate`
  *   - go-sql-driver/mysql auth.go, sha256PasswordPlugin
  *   - mysql-connector-python sha256_password plugin
  */
private[mysql] object Sha256Password:

    /** Byte sent by the client to request the server's RSA public key over a non-TLS connection. */
    val RequestPublicKey: Span[Byte] = Span.from(Array(0x01.toByte))

    /** Computes the RSA-OAEP encrypted full-auth response for non-TLS connections.
      *
      * The plaintext is NUL-terminated and XOR'd with the scramble (cycling) before RSA-OAEP encryption, identical to
      * `caching_sha2_password`'s full-auth path. The server decrypts and re-applies the XOR to recover the password.
      *
      * @param password
      *   the plaintext password
      * @param scramble
      *   the 20-byte challenge from the HandshakeV10 authPluginData
      * @param publicKeyPem
      *   PEM-encoded RSA public key bytes from the server's AuthMoreData response
      * @return
      *   RSA-OAEP ciphertext to send as AuthMoreDataResponse
      */
    def computeEncryptedResponse(
        password: String,
        scramble: Span[Byte],
        publicKeyPem: Span[Byte]
    )(using Frame): Span[Byte] < (Sync & Abort[SqlRequestException]) =
        val passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val scrambleArr   = scramble.toArray
        // NUL-terminate the password.
        val plaintext = new Array[Byte](passwordBytes.length + 1)
        java.lang.System.arraycopy(passwordBytes, 0, plaintext, 0, passwordBytes.length)
        plaintext(passwordBytes.length) = 0.toByte
        // XOR with scramble (cycling over the scramble bytes), matches caching_sha2_password RSA full-auth.
        val scrambleLen = scrambleArr.length
        if scrambleLen > 0 then
            var i = 0
            while i < plaintext.length do
                plaintext(i) = (plaintext(i) ^ scrambleArr(i % scrambleLen)).toByte
                i += 1
            end while
        end if
        val pemStr = new String(publicKeyPem.toArray, java.nio.charset.StandardCharsets.US_ASCII)
        RsaOaep.encrypt(pemStr, Span.from(plaintext), Random.secure)
    end computeEncryptedResponse

end Sha256Password
