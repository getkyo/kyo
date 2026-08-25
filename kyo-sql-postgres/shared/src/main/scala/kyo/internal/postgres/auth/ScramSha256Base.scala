package kyo.internal.postgres.auth

import java.util.Base64
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Span
import kyo.SqlConnectionScramFailedException
import kyo.SqlConnectionScramIterationsTooHighException
import kyo.SqlDecodeScramFormatException
import kyo.SqlException
import kyo.internal.auth.PureHash

/** Which of RFC 5802's three GS2 channel-binding flags the client is entitled to send.
  *
  * The grammar is `gs2-cbind-flag = ("p=" cb-name) / "n" / "y"` and the three are not interchangeable. `n` states that the client cannot do
  * channel binding at all. `p=` states that it is binding, and carries the binding data. `y` states that the client CAN bind but the server's
  * mechanism list named no `-PLUS` variant, and RFC 5802 §6 requires a server that does support channel binding to fail an exchange whose
  * flag is `y`: only an intermediary editing the advertised list can produce that combination, so the flag is what turns a stripped `-PLUS`
  * into a detected attack instead of a silent downgrade.
  *
  * Collapsing `y` into `n` therefore removes the one signal the mechanism exists for, which is why absence is modelled as two distinct states
  * rather than one.
  */
private[kyo] enum ChannelBinding derives CanEqual:

    /** The connection offers nothing to bind to, so no claim about the server's support is being made. */
    case NotSupported

    /** The connection can supply binding data and the server advertised no `-PLUS` mechanism. */
    case SupportedButNotOffered

    /** The client is binding to `certificateHash`, the RFC 5929 `tls-server-end-point` value for this connection. */
    case Bound(certificateHash: Span[Byte])

end ChannelBinding

/** Shared SCRAM-SHA-256 / SCRAM-SHA-256-PLUS state machine (RFC 7677 / RFC 5802).
  *
  * Contains the full protocol state machine: `clientFirstMessage`, `clientFinalMessage`, `verifyServerSignature`, `parseServerFirst`,
  * `xor`, `b64encode`/`b64decode`, and all message-construction helpers. The only platform-specific bits are the three crypto primitives
  * left abstract for each concrete subclass to supply:
  *   - [[hmacSha256]], HMAC-SHA-256
  *   - [[sha256]], SHA-256 hash
  *   - [[pbkdf2HmacSha256]], PBKDF2-HMAC-SHA-256
  *
  * Leaving the three crypto primitives abstract costs one virtual dispatch per call (immaterial versus network latency) and keeps the
  * override points explicit and auditable.
  *
  * [[ScramSha256Shared]] is the subclass the driver uses; it supplies all three primitives from [[kyo.internal.auth.PureHash]], so the
  * exchange behaves identically on every platform.
  */
abstract private[kyo] class ScramSha256Base(username: String, clientNonce: String, channelBinding: ChannelBinding):

    // GS2 header, one per RFC 5802 flag: "p=tls-server-end-point,," when binding, "y,," when the client could bind and
    // the server offered no -PLUS mechanism, "n,," when the connection offers nothing to bind to.
    private val gs2Header: String = channelBinding match
        case ChannelBinding.Bound(_)               => "p=tls-server-end-point,,"
        case ChannelBinding.SupportedButNotOffered => "y,,"
        case ChannelBinding.NotSupported           => "n,,"

    // The c= value is base64(gs2Header_bytes ++ cbData). cbData is the certificate hash when binding and empty
    // otherwise, so c= is base64("n,,") = "biws" for the n flag and base64("y,,") = "eSws" for the y flag. The header
    // is inside the value, which is what puts the flag in the transcript the server signs over.
    private val cBindingValue: String = channelBinding match
        case ChannelBinding.Bound(certHash) =>
            val gs2Bytes = gs2Header.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            // Span.from: Array[Byte] escape, required for crypto primitive APIs
            val hashBytes = certHash.toArray // Span.from boundary: crypto bridge
            val combined  = new Array[Byte](gs2Bytes.length + hashBytes.length)
            java.lang.System.arraycopy(gs2Bytes, 0, combined, 0, gs2Bytes.length)
            java.lang.System.arraycopy(hashBytes, 0, combined, gs2Bytes.length, hashBytes.length)
            b64encode(combined)
        case ChannelBinding.SupportedButNotOffered | ChannelBinding.NotSupported =>
            b64encode(gs2Header.getBytes(java.nio.charset.StandardCharsets.UTF_8))

    /** The client-first-message-bare (without GS2 header). */
    private val clientFirstBare: String = s"n=$username,r=$clientNonce"

    /** The full client-first-message as sent to the server. */
    val clientFirstMessage: String = gs2Header + clientFirstBare

    /** Derives the client-final-message from the server's first message.
      *
      * @param serverFirst
      *   the server-first-message string (e.g. "r=...,s=...,i=...")
      * @param password
      *   the user's plaintext password
      * @return
      *   Result.Success((clientFinalMessage, serverSignature)) on success, or Result.Failure(SqlException) on failure
      */
    def clientFinalMessage(serverFirst: String, password: String)(using Frame): Result[SqlException, (String, Array[Byte])] =
        parseServerFirst(serverFirst).map { case (serverNonce, salt, iterations) =>
            val saltedPassword     = pbkdf2HmacSha256(password.getBytes(java.nio.charset.StandardCharsets.UTF_8), salt, iterations, 32)
            val clientKey          = hmacSha256(saltedPassword, "Client Key".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            val storedKey          = sha256(clientKey)
            val clientFinalWoProof = s"c=$cBindingValue,r=$serverNonce"
            val authMessage        = s"$clientFirstBare,$serverFirst,$clientFinalWoProof"
            val clientSignature    = hmacSha256(storedKey, authMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            val clientProof        = PureHash.xor(clientKey, clientSignature)
            val clientFinal        = s"$clientFinalWoProof,p=${b64encode(clientProof)}"

            val serverKey = hmacSha256(saltedPassword, "Server Key".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            val serverSig = hmacSha256(serverKey, authMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8))

            (clientFinal, serverSig)
        }

    /** Verifies the server's final message signature.
      *
      * @param serverFinal
      *   the server-final-message string (e.g. "v=...")
      * @param expectedServerSignature
      *   the server signature computed during `clientFinalMessage`
      * @return
      *   Result.Success(()) if valid, Result.Failure(SqlException) if signature mismatch or malformed
      */
    def verifyServerSignature(serverFinal: String, expectedServerSignature: Array[Byte])(using Frame): Result[SqlException, Unit] =
        // server-final-message = server-error / ("v=" base64(ServerSignature))
        if serverFinal.startsWith("e=") then
            Result.fail(SqlConnectionScramFailedException(s"server reported error: ${serverFinal.drop(2)}"))
        else if serverFinal.startsWith("v=") then
            val b64sig = serverFinal.drop(2)
            Result.catching[IllegalArgumentException](b64decode(b64sig))
                .mapFailure(_ => SqlDecodeScramFormatException("v", b64sig))
                .flatMap { sig =>
                    if Span.from(sig).constantTimeEquals(Span.from(expectedServerSignature)) then Result.unit
                    else Result.fail(SqlConnectionScramFailedException("server signature mismatch"))
                }
        else
            Result.fail(SqlDecodeScramFormatException("server-final-message", serverFinal))

    // --- Private helpers ---

    /** Parses server-first-message into (serverNonce, rawSalt, iterations). */
    private def parseServerFirst(serverFirst: String)(using Frame): Result[SqlException, (String, Array[Byte], Int)] =
        val parts  = serverFirst.split(",")
        val rField = Maybe.fromOption(parts.find(_.startsWith("r=")).map(_.drop(2)))
        val sField = Maybe.fromOption(parts.find(_.startsWith("s=")).map(_.drop(2)))
        val iField = Maybe.fromOption(parts.find(_.startsWith("i=")).map(_.drop(2)))
        (rField, sField, iField) match
            case (Maybe.Present(r), Maybe.Present(s), Maybe.Present(i)) =>
                if !r.startsWith(clientNonce) then
                    Result.fail(SqlConnectionScramFailedException(
                        s"server nonce does not extend client nonce (server=$r client=$clientNonce)"
                    ))
                else
                    Result.catching[IllegalArgumentException](b64decode(s))
                        .mapFailure(_ => SqlDecodeScramFormatException("s", s))
                        .flatMap { salt =>
                            // Parsed as a Long so a count above Int.MaxValue is refused for being too high rather than
                            // for being unparseable, and so no unreadable field turns into a numeric stand-in.
                            Maybe.fromOption(i.toLongOption) match
                                case Absent => Result.fail(SqlDecodeScramFormatException("i", i))
                                case Present(iter) =>
                                    if iter <= 0 then Result.fail(SqlDecodeScramFormatException("i", i))
                                    else if iter > ScramSha256Base.MaxIterations then
                                        Result.fail(SqlConnectionScramIterationsTooHighException(iter, ScramSha256Base.MaxIterations))
                                    else Result.Success((r, salt, iter.toInt))
                            end match
                        }
            case _ =>
                Result.fail(SqlDecodeScramFormatException("server-first-message", serverFirst))
        end match
    end parseServerFirst

    /** HMAC-SHA-256. Implemented by each concrete subclass using its available crypto backend. */
    private[kyo] def hmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte]

    /** SHA-256 hash. Implemented by each concrete subclass using its available crypto backend. */
    private[kyo] def sha256(input: Array[Byte]): Array[Byte]

    /** PBKDF2-HMAC-SHA-256 (RFC 2898 §5.2). Implemented by each concrete subclass using its available crypto backend. */
    private[kyo] def pbkdf2HmacSha256(password: Array[Byte], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte]

    private def b64encode(bytes: Array[Byte]): String =
        Base64.getEncoder.encodeToString(bytes)

    private def b64decode(s: String): Array[Byte] =
        Base64.getDecoder.decode(s)

end ScramSha256Base

private[kyo] object ScramSha256Base:

    /** Highest server-chosen PBKDF2 iteration count the client will perform for one SCRAM exchange.
      *
      * The count arrives in the server's first message, so the peer picks how much work the connect path does, and it does that work in a
      * loop with no suspension point: the carrier is held for the whole derivation and no caller-side timeout or interrupt shortens it. On
      * this machine the pure-Scala derivation costs roughly 1.5 microseconds per iteration, so the ceiling caps one exchange near 300
      * milliseconds where an uncapped `i=2000000000` would hold a carrier for about 49 minutes.
      *
      * PostgreSQL's `scram_iterations` defaults to 4096. The ceiling is about fifty times that, which leaves headroom for a deliberately
      * hardened server while keeping the work bounded before it starts. A server configured above it fails with
      * [[kyo.SqlConnectionScramIterationsTooHighException]], which names both the count asked for and this limit.
      */
    val MaxIterations: Int = 200000

end ScramSha256Base
