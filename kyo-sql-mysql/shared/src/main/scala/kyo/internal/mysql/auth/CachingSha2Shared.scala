package kyo.internal.mysql.auth

import kyo.*
import kyo.SqlRequestException
import kyo.internal.auth.PureHash

/** caching_sha2_password authentication helper.
  *
  * Implements both fast-path and full-auth (RSA-OAEP) paths for MySQL's default auth plugin (MySQL 8.0+).
  *
  * Fast-path formula: hash1 = SHA256(password) hash2 = SHA256(hash1) xorWith = SHA256(hash2 || scramble) response = hash1 XOR xorWith
  *
  * Full-auth (non-TLS) formula: plaintext = (password-bytes ++ [0x00]) XOR scramble-bytes-cycling ciphertext = RSA-OAEP(plaintext,
  * serverPublicKey)
  *
  * Digests come from [[kyo.internal.auth.PureHash.sha256]] and the encryption from [[kyo.internal.mysql.auth.RsaOaep.encrypt]], both pure Scala,
  * so the same bytes are produced on every platform without `java.security` or `javax.crypto`.
  *
  * References:
  *   - MySQL Internals Manual, caching_sha2_password Authentication
  *   - go-sql-driver/mysql auth.go, scrambleSHA256Password
  *   - mysql-connector-python caching_sha2_password plugin
  */
private[mysql] object CachingSha2Shared:

    /** Computes the fast-path auth response.
      *
      * Returns [[Span.empty]] for an empty password (MySQL "no password" sentinel). For a non-empty password returns a 32-byte SHA256-XOR
      * response: hash1 = SHA256(password) hash2 = SHA256(hash1) xorWith = SHA256(hash2 || scramble) response = hash1 XOR xorWith
      *
      * @param password
      *   the plaintext password; empty string returns [[Span.empty]]
      * @param scramble
      *   the 20-byte challenge from [[kyo.internal.mysql.HandshakeV10.authPluginData]]
      * @return
      *   32-byte response, or [[Span.empty]] if password is empty
      */
    def computeFastResponse(password: String, scramble: Span[Byte]): Span[Byte] =
        if password.isEmpty then Span.empty
        else
            val passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            val hash1         = PureHash.sha256(passwordBytes)
            val hash2         = PureHash.sha256(hash1)
            val scrambleArr   = scramble.toArray
            val combined      = new Array[Byte](hash2.length + scrambleArr.length)
            java.lang.System.arraycopy(hash2, 0, combined, 0, hash2.length)
            java.lang.System.arraycopy(scrambleArr, 0, combined, hash2.length, scrambleArr.length)
            val xorWith = PureHash.sha256(combined)
            Span.from(PureHash.xor(hash1, xorWith))
        end if
    end computeFastResponse

    /** Computes the full-auth RSA-OAEP encrypted response for non-TLS connections.
      *
      * Builds the plaintext with [[scrambledPlaintext]], then encrypts it with the server's RSA public key using RSA-OAEP with SHA-1 and
      * MGF1-SHA-1, the padding MySQL expects. The OAEP seed comes from the ambient [[kyo.SecureRandom]].
      *
      * @param password
      *   the plaintext password
      * @param scramble
      *   the 20-byte scramble from the handshake
      * @param publicKeyPem
      *   PEM-encoded RSA public key bytes from the server's AuthMoreData response
      * @return
      *   RSA-OAEP ciphertext to send as AuthMoreDataResponse
      */
    def computeFullAuthResponse(
        password: String,
        scramble: Span[Byte],
        publicKeyPem: Span[Byte]
    )(using Frame): Span[Byte] < (Sync & Abort[SqlRequestException]) =
        val pemStr = new String(publicKeyPem.toArray, java.nio.charset.StandardCharsets.US_ASCII)
        SecureRandom.get.map(RsaOaep.encrypt(pemStr, scrambledPlaintext(password, scramble), _))
    end computeFullAuthResponse

    /** Builds the RSA plaintext for the full-auth path: the NUL-terminated password bytes XOR'd with the scramble, cycling over the scramble
      * when the password is longer than it.
      *
      * The server re-applies the same XOR after decrypting to recover the password, so this transformation is part of the wire contract. An
      * empty scramble leaves the NUL-terminated password unchanged.
      *
      * @param password
      *   the plaintext password
      * @param scramble
      *   the 20-byte scramble from the handshake
      * @return
      *   `password.length + 1` bytes ready for RSA-OAEP encryption
      */
    def scrambledPlaintext(password: String, scramble: Span[Byte]): Span[Byte] =
        val passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val scrambleArr   = scramble.toArray
        // NUL-terminate the password.
        val plaintext = new Array[Byte](passwordBytes.length + 1)
        java.lang.System.arraycopy(passwordBytes, 0, plaintext, 0, passwordBytes.length)
        plaintext(passwordBytes.length) = 0.toByte
        // XOR with scramble (cycling over the scramble bytes).
        val scrambleLen = scrambleArr.length
        if scrambleLen > 0 then
            // Performance: while/var crypto loop; its mutable state does not escape this pure function.
            var i = 0
            while i < plaintext.length do
                plaintext(i) = (plaintext(i) ^ scrambleArr(i % scrambleLen)).toByte
                i += 1
            end while
        end if
        Span.from(plaintext)
    end scrambledPlaintext

end CachingSha2Shared
