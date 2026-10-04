package kyo.internal.discord

import java.nio.charset.StandardCharsets.US_ASCII
import kyo.*
import kyo.crypto.Ed25519

/** The interactions endpoint's request check (`interactions/overview.mdx`, "Security and Authorization").
  *
  * The checks run cheapest first, and each refusal returns before the next check: the headers are present, the timestamp is 1 to 20
  * ASCII digits, the signature is 128 hex characters, and only then the Ed25519 verification over the timestamp's bytes followed by
  * the raw body. A forged request therefore reaches the scalar multiplications only when it is well formed. Discord sends invalid
  * signatures on purpose and removes an endpoint that accepts one.
  */
private[kyo] object Signature:

    def verify(key: Discord.PublicKey, signature: Maybe[String], timestamp: Maybe[String], body: Span[Byte])(using
        Frame
    ): Result[DiscordWebhookVerifyFailure, Unit] =
        import DiscordWebhookMissingHeaderException.Header
        signature match
            case Absent       => Result.fail(DiscordWebhookMissingHeaderException(Header.Signature))
            case Present(sig) =>
                timestamp match
                    case Absent         => Result.fail(DiscordWebhookMissingHeaderException(Header.Timestamp))
                    case Present(stamp) => check(key, sig, stamp, body)
        end match
    end verify

    private def check(key: Discord.PublicKey, sig: String, stamp: String, body: Span[Byte])(using
        Frame
    ): Result[DiscordWebhookVerifyFailure, Unit] =
        import DiscordWebhookMissingHeaderException.Header
        import DiscordWebhookMalformedHeaderException.Problem
        if !isSeconds(stamp) then Result.fail(DiscordWebhookMalformedHeaderException(Header.Timestamp, Problem.NotSeconds))
        else if sig.length != SignatureHexLength then
            Result.fail(DiscordWebhookMalformedHeaderException(Header.Signature, Problem.Length(sig.length)))
        else
            Hex.decode(sig) match
                case Result.Success(decoded) =>
                    if Ed25519.verify(key.key, Span.from(stamp.getBytes(US_ASCII)) ++ body, decoded) then Result.unit
                    else Result.fail(DiscordWebhookSignatureMismatchException())
                case Result.Failure(failure) =>
                    Result.fail(DiscordWebhookMalformedHeaderException(Header.Signature, Problem.Hex(failure)))
                case Result.Panic(t) => Result.panic(t)
        end if
    end check

    // 64 bytes: R and S of an Ed25519 signature.
    private inline val SignatureHexLength = 128

    private def isSeconds(stamp: String): Boolean =
        stamp.nonEmpty && stamp.length <= 20 && stamp.forall(c => c >= '0' && c <= '9')

end Signature
