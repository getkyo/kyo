package kyo.internal.postgres.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kyo.Maybe
import kyo.Span

/** Pure SCRAM-SHA-256 / SCRAM-SHA-256-PLUS (RFC 7677 / RFC 5802) implementation using javax.crypto (JVM only).
  *
  * Handles one full SCRAM exchange for a single connection. All crypto operations are pure JDK compute, no I/O, no Kyo effects.
  *
  * Usage:
  *   1. Construct with `username`, a pre-generated `clientNonce` (base64, from `kyo.Random.nextBytes(24)`), and optionally a
  *      `channelBinding` cert hash for SCRAM-SHA-256-PLUS.
  *   2. Call `clientFinalMessage(serverFirst, password)` after receiving the server's first message.
  *   3. Call `verifyServerSignature(serverFinal)` after receiving the server's final message.
  *
  * Channel binding (RFC 5929 tls-server-end-point): when `channelBinding = Present(certHash)`, the GS2 header switches from `"n,,"` to
  * `"p=tls-server-end-point,,"` and the `c=` attribute in client-final-message base64-encodes `gs2Header || certHash` instead of just the
  * gs2Header.
  *
  * Produces output byte-identical to [[kyo.internal.postgres.auth.ScramSha256Shared]] for the same inputs, the two implementations are
  * independent but verified to be equivalent by the cross-platform parity test in `ScramSha256ParityTest`.
  *
  * Reference: RFC 7677 §3 test vectors, RFC 5802 §5
  */
final class ScramSha256(username: String, clientNonce: String, channelBinding: Maybe[Span[Byte]])
    extends ScramSha256Base(username, clientNonce, channelBinding):

    private[kyo] def hmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte] =
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(key, "HmacSHA256"))
        mac.doFinal(data)
    end hmacSha256

    private[kyo] def sha256(input: Array[Byte]): Array[Byte] =
        MessageDigest.getInstance("SHA-256").digest(input)

    private[kyo] def pbkdf2HmacSha256(password: Array[Byte], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte] =
        // PBKDF2 spec: password is treated as a char array for PBEKeySpec.
        // We use the raw password bytes converted to chars (ISO-8859-1 mapping, byte-safe).
        val chars   = password.map(b => (b & 0xff).toChar)
        val spec    = new PBEKeySpec(chars, salt, iterations, keyLength * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        try factory.generateSecret(spec).getEncoded
        finally spec.clearPassword()
    end pbkdf2HmacSha256

end ScramSha256

object ScramSha256:

    /** Encodes raw bytes as URL-safe base64 without padding. Used for the client nonce from random bytes.
      *
      * RFC 5802 requires the nonce to consist of printable ASCII characters excluding comma. Base64 without padding satisfies this
      * (characters are A-Z, a-z, 0-9, +, /).
      */
    def encodeNonce(bytes: Span[Byte]): String =
        Base64.getEncoder.encodeToString(bytes.toArray)

end ScramSha256
