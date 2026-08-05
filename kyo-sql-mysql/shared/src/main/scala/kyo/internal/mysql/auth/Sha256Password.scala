package kyo.internal.mysql.auth

import kyo.*
import kyo.SqlRequestException

/** sha256_password authentication helper.
  *
  * Implements the legacy `sha256_password` plugin used by some MySQL 5.7 and 8.0 installations. This plugin lacks the server-side
  * credential cache that `caching_sha2_password` uses, so every connection performs the full RSA exchange (non-TLS) or cleartext-over-TLS.
  *
  * Protocol:
  *   - Over TLS: client sends NUL-terminated cleartext password directly (same as `caching_sha2_password` TLS path).
  *   - Over plaintext: client sends [[RequestPublicKey]], the single byte `0x01`; server replies with an AuthMoreData packet containing the
  *     PEM-encoded RSA public key; client XORs `(passwordBytes ++ [0x00])` with the 20-byte scramble (cycling), RSA-OAEP encrypts the result,
  *     and sends the ciphertext. The server decrypts, re-applies the XOR with the scramble, and recovers the plaintext password.
  *   - With no password, either transport: client sends [[EmptyPassword]] and the exchange ends there.
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

    /** Byte sent by the client to request the server's RSA public key over a non-TLS connection.
      *
      * The server's `sha256_password` plugin replies with the PEM-encoded key only for this exact one-byte response. Anything else, an empty
      * response included, is read as a password attempt, so sending the wrong bytes here does not fail loudly: the RSA round never starts and
      * authentication is refused for every non-empty password.
      */
    val RequestPublicKey: Span[Byte] = Span.from(Array(0x01.toByte))

    /** Response that states there is no password to send, a single NUL.
      *
      * An account with no password authenticates on this alone, with no RSA round and so no public key to fetch, which is why it is a distinct
      * value from [[RequestPublicKey]] rather than an empty payload.
      */
    val EmptyPassword: Span[Byte] = Span.from(Array(0x00.toByte))

    /** Computes the RSA-OAEP encrypted full-auth response for non-TLS connections.
      *
      * The plaintext is built by [[CachingSha2Shared.scrambledPlaintext]] (the NUL-terminated password XOR'd with the scramble, cycling)
      * before RSA-OAEP encryption, identical to `caching_sha2_password`'s full-auth path. The server decrypts and re-applies the XOR to
      * recover the password.
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
        val pemStr = new String(publicKeyPem.toArray, java.nio.charset.StandardCharsets.US_ASCII)
        SecureRandom.get.map(RsaOaep.encrypt(pemStr, CachingSha2Shared.scrambledPlaintext(password, scramble), _))
    end computeEncryptedResponse

end Sha256Password
