package kyo.internal.mysql.auth

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import kyo.*
import kyo.SqlException
import kyo.internal.auth.RsaOaep

/** caching_sha2_password authentication helper.
  *
  * Implements both fast-path and full-auth (RSA-OAEP) paths for MySQL's default auth plugin (MySQL 8.0+).
  *
  * Fast-path formula: hash1 = SHA256(password) hash2 = SHA256(hash1) xorWith = SHA256(hash2 || scramble) response = hash1 XOR xorWith
  *
  * Full-auth (non-TLS) formula: plaintext = (password-bytes XOR scramble-bytes-cycling) ++ [0x00] ciphertext = RSA-OAEP(plaintext,
  * serverPublicKey)
  *
  * References:
  *   - MySQL Internals Manual — caching_sha2_password Authentication
  *   - go-sql-driver/mysql auth.go — scrambleSHA256Password
  *   - mysql-connector-python caching_sha2_password plugin
  */
private[mysql] object CachingSha2:

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
            val hash1         = sha256(passwordBytes)
            val hash2         = sha256(hash1)
            val scrambleArr   = scramble.toArray
            val combined      = new Array[Byte](hash2.length + scrambleArr.length)
            java.lang.System.arraycopy(hash2, 0, combined, 0, hash2.length)
            java.lang.System.arraycopy(scrambleArr, 0, combined, hash2.length, scrambleArr.length)
            val xorWith = sha256(combined)
            val result  = new Array[Byte](32)
            var i       = 0
            while i < 32 do
                result(i) = (hash1(i) ^ xorWith(i)).toByte
                i += 1
            end while
            Span.from(result)
        end if
    end computeFastResponse

    /** Computes the full-auth RSA-OAEP encrypted response for non-TLS connections.
      *
      * XORs the NUL-terminated password bytes with the scramble (cycling), then encrypts with the server's RSA public key using
      * RSA/ECB/OAEPWithSHA-1AndMGF1Padding via JDK's `javax.crypto.Cipher` for JVM performance.
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
    )(using Frame): Span[Byte] < (Sync & Abort[SqlException.Request]) =
        val passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val scrambleArr   = scramble.toArray
        // NUL-terminate the password.
        val plaintext = new Array[Byte](passwordBytes.length + 1)
        java.lang.System.arraycopy(passwordBytes, 0, plaintext, 0, passwordBytes.length)
        plaintext(passwordBytes.length) = 0.toByte
        // XOR with scramble (cycling over the scramble bytes).
        val scrambleLen = scrambleArr.length
        if scrambleLen > 0 then
            var i = 0
            while i < plaintext.length do
                plaintext(i) = (plaintext(i) ^ scrambleArr(i % scrambleLen)).toByte
                i += 1
            end while
        end if
        // Parse PEM key and encrypt using JDK Cipher.
        // Narrow catch to GeneralSecurityException so unexpected panics (OOM, Error subclasses) propagate as panics.
        Abort.catching[GeneralSecurityException](gse =>
            SqlException.Request(s"RSA-OAEP encryption failed: ${gse.getMessage}", Maybe.Present("<caching_sha2_password>"), summon[Frame])
        ) {
            val pubKey = decodePemKey(publicKeyPem)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, pubKey)
            val ciphertext = cipher.doFinal(plaintext)
            Span.from(ciphertext)
        }
    end computeFullAuthResponse

    /** Parses a PEM-encoded RSA public key (PKCS#8 SubjectPublicKeyInfo format).
      *
      * Strips "-----BEGIN PUBLIC KEY-----" / "-----END PUBLIC KEY-----" headers and newlines, base64-decodes the body, and uses JDK's
      * `KeyFactory("RSA")` + `X509EncodedKeySpec` to produce a `java.security.PublicKey`.
      *
      * @param pem
      *   PEM-encoded RSA public key bytes (may include headers and newlines)
      * @return
      *   the decoded `java.security.PublicKey`
      * @throws IllegalArgumentException
      *   if the PEM data is malformed or the key cannot be parsed
      */
    def decodePemKey(pem: Span[Byte]): java.security.PublicKey =
        val pemStr = new String(pem.toArray, java.nio.charset.StandardCharsets.US_ASCII)
        val cleaned = pemStr
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s+", "")
        val derBytes = Base64.getDecoder.decode(cleaned)
        val spec     = new X509EncodedKeySpec(derBytes)
        val kf       = KeyFactory.getInstance("RSA")
        kf.generatePublic(spec)
    end decodePemKey

    /** Computes SHA-256 of the given bytes. JDK MessageDigest is allowed for crypto primitives — see STEERING.md. */
    private def sha256(input: Array[Byte]): Array[Byte] =
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(input)
    end sha256

end CachingSha2
