package kyo.internal.postgres.auth

import java.util.Base64
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Span
import kyo.SqlException

/** Shared SCRAM-SHA-256 / SCRAM-SHA-256-PLUS state machine (RFC 7677 / RFC 5802).
  *
  * Contains the full protocol state machine: `clientFirstMessage`, `clientFinalMessage`, `verifyServerSignature`, `parseServerFirst`,
  * `xor`, `b64encode`/`b64decode`, and all message-construction helpers. The only platform-specific bits are the three crypto primitives
  * exposed as `protected def`s that each concrete subclass overrides:
  *   - [[hmacSha256]], HMAC-SHA-256
  *   - [[sha256]], SHA-256 hash
  *   - [[pbkdf2HmacSha256]], PBKDF2-HMAC-SHA-256
  *
  * Approach: full `abstract class ScramSha256Base` extraction. The abstract crypto methods add one virtual dispatch per call (immaterial
  * versus network latency) and make the crypto override points explicit and auditable.
  *
  * Subclasses:
  *   - `shared/auth/ScramSha256`, Scala Native; overrides crypto with [[kyo.internal.auth.PureHash]].
  *   - `jvm/auth/ScramSha256`, JVM; overrides crypto with `javax.crypto`.
  *   - [[ScramSha256Shared]], test-parity only; overrides crypto with inlined pure-Scala (PureHash is excluded from JVM classpath).
  */
abstract class ScramSha256Base(username: String, clientNonce: String, channelBinding: Maybe[Span[Byte]]):

    // GS2 header: "p=tls-server-end-point,," for PLUS, "n,," for non-PLUS.
    private val gs2Header: String = channelBinding match
        case Present(_) => "p=tls-server-end-point,,"
        case Absent     => "n,,"

    // The c= value is base64(gs2Header_bytes ++ cbData).
    // For non-PLUS: cbData = empty, so c= is base64("n,,").
    // For PLUS: cbData = certHash bytes.
    private val cBindingValue: String = channelBinding match
        case Present(certHash) =>
            val gs2Bytes = gs2Header.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            // Span.from: Array[Byte] escape, required for crypto primitive APIs
            val hashBytes = certHash.toArray // Span.from boundary: crypto bridge
            val combined  = new Array[Byte](gs2Bytes.length + hashBytes.length)
            java.lang.System.arraycopy(gs2Bytes, 0, combined, 0, gs2Bytes.length)
            java.lang.System.arraycopy(hashBytes, 0, combined, gs2Bytes.length, hashBytes.length)
            b64encode(combined)
        case Absent =>
            // base64("n,,") = "biws"
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
            val clientProof        = xor(clientKey, clientSignature)
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
    def verifyServerSignature(serverFinal: String, expectedServerSignature: Array[Byte])(using frame: Frame): Result[SqlException, Unit] =
        // server-final-message = server-error / ("v=" base64(ServerSignature))
        if serverFinal.startsWith("e=") then
            Result.fail(SqlException.Connection(s"SCRAM authentication failed: server reported error: ${serverFinal.drop(2)}", frame))
        else if serverFinal.startsWith("v=") then
            val b64sig = serverFinal.drop(2)
            Result.catching[IllegalArgumentException](b64decode(b64sig))
                .mapFailure(_ => SqlException.Decode(s"SCRAM: malformed server-final v= value: $b64sig", Maybe.Absent, frame))
                .flatMap { sig =>
                    if Span.from(sig).constantTimeEquals(Span.from(expectedServerSignature)) then Result.unit
                    else Result.fail(SqlException.Connection("SCRAM authentication failed: server signature mismatch", frame))
                }
        else
            Result.fail(SqlException.Decode(s"SCRAM: malformed server-final-message: $serverFinal", Maybe.Absent, frame))

    // --- Private helpers ---

    /** Parses server-first-message into (serverNonce, rawSalt, iterations). */
    private def parseServerFirst(serverFirst: String)(using frame: Frame): Result[SqlException, (String, Array[Byte], Int)] =
        val parts  = serverFirst.split(",")
        val rField = Maybe.fromOption(parts.find(_.startsWith("r=")).map(_.drop(2)))
        val sField = Maybe.fromOption(parts.find(_.startsWith("s=")).map(_.drop(2)))
        val iField = Maybe.fromOption(parts.find(_.startsWith("i=")).map(_.drop(2)))
        (rField, sField, iField) match
            case (Maybe.Present(r), Maybe.Present(s), Maybe.Present(i)) =>
                if !r.startsWith(clientNonce) then
                    Result.fail(SqlException.Connection(
                        s"SCRAM: server nonce does not extend client nonce. Server nonce: $r, client nonce: $clientNonce",
                        frame
                    ))
                else
                    Result.catching[IllegalArgumentException](b64decode(s))
                        .mapFailure(_ =>
                            SqlException.Decode(
                                s"SCRAM: malformed salt in server-first-message: $s",
                                Maybe.Absent,
                                frame
                            )
                        )
                        .flatMap { salt =>
                            val iter = Maybe.fromOption(i.toIntOption).getOrElse(0)
                            if iter <= 0 then Result.fail(SqlException.Decode(s"SCRAM: invalid iteration count: $i", Maybe.Absent, frame))
                            else Result.Success((r, salt, iter))
                        }
            case _ =>
                Result.fail(SqlException.Decode(
                    s"SCRAM: missing required fields (r, s, i) in server-first-message: $serverFirst",
                    Maybe.Absent,
                    frame
                ))
        end match
    end parseServerFirst

    /** HMAC-SHA-256. Implemented by each concrete subclass using its available crypto backend. */
    private[kyo] def hmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte]

    /** SHA-256 hash. Implemented by each concrete subclass using its available crypto backend. */
    private[kyo] def sha256(input: Array[Byte]): Array[Byte]

    /** PBKDF2-HMAC-SHA-256 (RFC 2898 §5.2). Implemented by each concrete subclass using its available crypto backend. */
    private[kyo] def pbkdf2HmacSha256(password: Array[Byte], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte]

    private def xor(a: Array[Byte], b: Array[Byte]): Array[Byte] =
        val result = new Array[Byte](a.length)
        // Performance: while/var crypto loop, encapsulated pure-Scala block function; CONTRIBUTING permits this.
        var i = 0
        while i < a.length do
            result(i) = (a(i) ^ b(i)).toByte
            i += 1
        result
    end xor

    private def b64encode(bytes: Array[Byte]): String =
        Base64.getEncoder.encodeToString(bytes)

    private def b64decode(s: String): Array[Byte] =
        Base64.getDecoder.decode(s)

end ScramSha256Base
