package kyo.internal.postgres.auth

import java.util.Base64
import kyo.Span
import kyo.internal.auth.PureHash

/** SCRAM-SHA-256 / SCRAM-SHA-256-PLUS (RFC 7677 / RFC 5802) client built on pure-Scala crypto.
  *
  * Handles one full SCRAM exchange for a single connection. All crypto operations are pure compute, no I/O, no Kyo effects.
  *
  * Usage:
  *   1. Construct with `username`, a pre-generated `clientNonce` ([[ScramSha256Shared.encodeNonce]] applied to
  *      `kyo.SecureRandom.nextBytes(24)`), and the [[ChannelBinding]] state the connection is in.
  *   2. Call `clientFinalMessage(serverFirst, password)` after receiving the server's first message.
  *   3. Call `verifyServerSignature(serverFinal)` after receiving the server's final message.
  *
  * Channel binding (RFC 5929 tls-server-end-point): [[ChannelBinding.Bound]] sets the GS2 header to `"p=tls-server-end-point,,"` and makes
  * the `c=` attribute base64-encode `gs2Header || certHash`; [[ChannelBinding.SupportedButNotOffered]] sets it to `"y,,"` and
  * [[ChannelBinding.NotSupported]] to `"n,,"`, both with the header alone as the `c=` value. Which of the last two applies is the difference
  * between reporting a possible downgrade and reporting no capability, so the caller decides it rather than this class inferring it.
  *
  * The state machine lives in [[ScramSha256Base]]; this class supplies the three crypto primitives from
  * [[kyo.internal.auth.PureHash]], so the same bytes are produced on every platform without `javax.crypto`.
  *
  * Reference: RFC 7677 §3 test vectors, RFC 5802 §5
  */
final private[kyo] class ScramSha256Shared(username: String, clientNonce: String, channelBinding: ChannelBinding)
    extends ScramSha256Base(username, clientNonce, channelBinding):

    private[kyo] def sha256(input: Array[Byte]): Array[Byte] =
        PureHash.sha256(input)

    private[kyo] def hmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte] =
        PureHash.hmacSha256(key, data)

    private[kyo] def pbkdf2HmacSha256(password: Array[Byte], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte] =
        PureHash.pbkdf2HmacSha256(password, salt, iterations, keyLength)

end ScramSha256Shared

private[kyo] object ScramSha256Shared:

    /** Encodes raw bytes as standard base64. Used for the client nonce from random bytes.
      *
      * RFC 5802 requires the nonce to consist of printable ASCII characters excluding comma. Base64 satisfies this: its alphabet is A-Z,
      * a-z, 0-9, +, / plus the = pad character.
      */
    def encodeNonce(bytes: Span[Byte]): String =
        Base64.getEncoder.encodeToString(bytes.toArray)

end ScramSha256Shared
