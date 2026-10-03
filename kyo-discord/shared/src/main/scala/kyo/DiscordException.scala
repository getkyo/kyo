package kyo

import kyo.crypto.Ed25519

/** Typed error hierarchy for kyo-discord: a sealed base over `KyoException` with flat leaves, one per failure a caller can tell
  * apart.
  *
  * The leaves fall into five groups:
  *   - the transport and the response, shared by every REST call and the Gateway: [[kyo.DiscordTransportException]],
  *     [[kyo.DiscordUnexpectedStatusException]], [[kyo.DiscordDecodeException]] and the rate-limit category
  *     [[kyo.DiscordRateLimitException]];
  *   - Discord's JSON error answers: the category [[kyo.DiscordApiException]], with a leaf per code a caller can act on and
  *     [[kyo.DiscordOtherApiException]] carrying any other;
  *   - the Gateway's close codes and session limits, such as [[kyo.DiscordDisallowedIntentsException]];
  *   - the interactions endpoint's request checks, such as [[kyo.DiscordWebhookSignatureMismatchException]];
  *   - a value Discord would refuse, the failure of that type's `init` (a `Result`), such as [[kyo.DiscordInvalidTokenException]]
  *     and [[kyo.DiscordInvalidConfigException]].
  *
  * Each public operation fails with its own sealed trait, such as [[kyo.DiscordSendFailure]], and a leaf extends the trait of every
  * operation that can produce it, so a row names exactly the leaves a caller can meet. No row names `DiscordException` itself.
  *
  * IMPORTANT: no leaf holds a credential. The bot token travels in a header and an interaction token in the path of the
  * interaction routes, so no leaf keeps a kyo-http failure, whose URL holds that path; the transport leaf keeps only kyo-net's
  * cause of a failed connection, which holds no URL. The text a leaf copies from Discord (an API leaf's `description`, a close
  * frame's `reason`) has both tokens replaced before it is stored. The decode leaf names kyo-schema's failure, its path and its
  * position, never kyo-schema's exception, which quotes the input. Every leaf builds its message from its own fields.
  *
  * @see
  *   [[kyo.DiscordApiException]] Discord's JSON error answers
  * @see
  *   [[kyo.DiscordRateLimitException]] the rate limits
  * @see
  *   [[kyo.DiscordTransportException]] transport failures
  */
sealed abstract class DiscordException(message: String)(using Frame) extends KyoException(message)

object DiscordException:
    given CanEqual[DiscordException, DiscordException] = CanEqual.derived
end DiscordException

// --- Operation failures ---

/** What `Discord.init` can fail with: `GET /gateway/bot`, the session start budget, the connection, Hello, Identify and Ready. */
sealed trait DiscordInitFailure extends DiscordException

/** What `Discord.receive` can fail with: the connection it opens on a client built by `run(config)`, and the receive loop. */
sealed trait DiscordReceiveFailure extends DiscordException

/** What `Discord.send` can fail with. */
sealed trait DiscordSendFailure extends DiscordException

/** What `Discord.edit` can fail with. */
sealed trait DiscordEditFailure extends DiscordException

/** What `Discord.delete` can fail with. */
sealed trait DiscordDeleteFailure extends DiscordException

/** What `Discord.react` can fail with. */
sealed trait DiscordReactFailure extends DiscordException

/** What `Discord.unreact` can fail with. */
sealed trait DiscordUnreactFailure extends DiscordException

/** What `Discord.typing` can fail with. */
sealed trait DiscordTypingFailure extends DiscordException

/** What `Discord.startThread` can fail with, from a message or without one. */
sealed trait DiscordStartThreadFailure extends DiscordException

/** What `Discord.openDm` can fail with. */
sealed trait DiscordOpenDmFailure extends DiscordException

/** What `Discord.registerCommands` can fail with, globally or for a guild. */
sealed trait DiscordRegisterCommandsFailure extends DiscordException

/** What `Discord.followUp` can fail with. */
sealed trait DiscordFollowUpFailure extends DiscordException

/** What `Discord.editResponse` can fail with. */
sealed trait DiscordEditResponseFailure extends DiscordException

/** What `Discord.deleteResponse` can fail with. */
sealed trait DiscordDeleteResponseFailure extends DiscordException

/** What `Discord.channel` can fail with. */
sealed trait DiscordChannelFailure extends DiscordException

/** What `Discord.member` can fail with. */
sealed trait DiscordMemberFailure extends DiscordException

/** What `Discord.message` can fail with. */
sealed trait DiscordMessageFailure extends DiscordException

/** What `Discord.messages` can fail with. */
sealed trait DiscordMessagesFailure extends DiscordException

/** What `Discord.gateway` can fail with. */
sealed trait DiscordGatewayInfoFailure extends DiscordException

/** What `Discord.custom` can fail with: only the answers that mean the same on every route. */
sealed trait DiscordCustomFailure extends DiscordException

/** What `Discord.Webhook.verify` can fail with: the signature headers and the signature. */
sealed trait DiscordWebhookVerifyFailure extends DiscordException

/** What `Discord.Webhook.decode` can fail with: a verified body that is not an interaction. */
sealed trait DiscordWebhookDecodeFailure extends DiscordException

// --- Transport and response ---

/** A request failed at the transport, before Discord's answer could be read. `method` is the route template
  * (`POST /channels/{channel.id}/messages`), never the URL, or `gateway-connect` for the Gateway's socket. `kind` is what
  * kyo-http reported, `host` and `port` are the server the request went to, and `timeout` is the limit that ran out, for the two
  * timeouts. No kyo-http failure is kept, since an interaction route's URL holds the interaction token.
  *
  * `cause` is kyo-net's failure behind a connection that could not be made (an errno, a DNS failure, a TLS handshake error), which
  * holds no URL. It stays out of the leaf's equality and is the `getCause`.
  */
final case class DiscordTransportException(
    method: String,
    kind: DiscordTransportException.Kind,
    host: String,
    port: Int,
    timeout: Maybe[Duration]
)(
    val cause: Maybe[kyo.net.NetException] = Absent
)(using Frame)
    extends DiscordException(
        s"Discord $method to $host:$port failed: ${kind.show}" + timeout.fold("")(t => s" after ${t.show}") + "."
    )
    with DiscordInitFailure with DiscordReceiveFailure with DiscordSendFailure with DiscordEditFailure
    with DiscordDeleteFailure with DiscordReactFailure with DiscordUnreactFailure with DiscordTypingFailure
    with DiscordStartThreadFailure with DiscordOpenDmFailure with DiscordRegisterCommandsFailure with DiscordFollowUpFailure
    with DiscordEditResponseFailure with DiscordDeleteResponseFailure with DiscordChannelFailure with DiscordMemberFailure
    with DiscordMessageFailure with DiscordMessagesFailure with DiscordGatewayInfoFailure with DiscordCustomFailure:
    // Not passed to `KyoException`'s constructor: it would embed the cause's message, formatted per environment, into this leaf's
    // `getMessage`, which states only what failed. `null` is `Throwable.getCause`'s contract for "no cause".
    override def getCause(): Throwable = cause.fold(null)(identity)
end DiscordTransportException

object DiscordTransportException:
    /** What went wrong at the transport, as kyo-http reported it. */
    enum Kind derives CanEqual:
        /** The TCP connection was refused or failed. */
        case Connect

        /** The host did not resolve. */
        case Dns

        /** The TLS handshake failed. */
        case Tls

        /** The connection did not open within `timeout`. */
        case ConnectTimeout

        /** The request did not complete within `timeout`. */
        case Timeout

        /** The response broke HTTP/1.1: a head the client refuses (malformed, larger than the transport's `maxHeaderSize`, or a status
          * outside 100 to 599), a malformed chunked body, or a Gateway frame that broke the WebSocket protocol. It maps to a failure,
          * not a panic, because a malformed response is a condition the peer controls.
          */
        case Protocol

        /** The connection closed before the response was complete: before its head, or before the body its framing declared. */
        case ConnectionClosed

        /** The Gateway's WebSocket upgrade was refused. */
        case WebSocketHandshake

        /** Every one of the pool's `maxConnections` connections to the server was in use. */
        case PoolExhausted(maxConnections: Int)

        /** The response body of `bodySize` exceeded the client's `maxSize`. */
        case PayloadTooLarge(bodySize: ByteSize, maxSize: ByteSize)

        private[kyo] def show: String =
            this match
                case Connect                            => "could not connect"
                case Dns                                => "the host did not resolve"
                case Tls                                => "the TLS handshake failed"
                case ConnectTimeout                     => "the connection did not open"
                case Timeout                            => "no answer arrived"
                case Protocol                           => "the response broke the protocol"
                case ConnectionClosed                   => "the connection closed before the response was complete"
                case WebSocketHandshake                 => "the WebSocket upgrade was refused"
                case PoolExhausted(max)                 => s"all $max connections were in use"
                case PayloadTooLarge(bodySize, maxSize) => s"the response body of ${bodySize.show} exceeds ${maxSize.show}"
    end Kind
end DiscordTransportException

/** Discord answered a non-2xx status with a body that is not Discord's `{code, message}`: a Cloudflare page, or `502 GATEWAY
  * UNAVAILABLE`. No body text is kept.
  */
final case class DiscordUnexpectedStatusException(method: String, status: HttpStatus)(using Frame)
    extends DiscordException(s"Discord $method answered HTTP ${status.code} with no Discord error body.")
    with DiscordInitFailure with DiscordReceiveFailure with DiscordSendFailure with DiscordEditFailure
    with DiscordDeleteFailure with DiscordReactFailure with DiscordUnreactFailure with DiscordTypingFailure
    with DiscordStartThreadFailure with DiscordOpenDmFailure with DiscordRegisterCommandsFailure with DiscordFollowUpFailure
    with DiscordEditResponseFailure with DiscordDeleteResponseFailure with DiscordChannelFailure with DiscordMemberFailure
    with DiscordMessageFailure with DiscordMessagesFailure with DiscordGatewayInfoFailure with DiscordCustomFailure

/** A body did not decode. `part` says which: a 2xx answer, an error answer, a Gateway frame, or an interaction the endpoint received.
  * `failure` is which of kyo-schema's decode failures it was, `path` is where in the value it happened, and `position` is the offset
  * in the body when kyo-schema gave one.
  *
  * Nothing is copied from the body: a user's message or a token could be there, so kyo-schema's exception, which quotes the input it
  * could not read, is not kept.
  */
final case class DiscordDecodeException(
    method: String,
    part: DiscordDecodeException.Part,
    failure: DiscordDecodeException.Failure,
    path: Chunk[String],
    position: Maybe[Int]
)(using Frame)
    extends DiscordException(DiscordDecodeException.describe(method, part, failure, path, position))
    with DiscordInitFailure with DiscordReceiveFailure with DiscordSendFailure with DiscordEditFailure
    with DiscordDeleteFailure with DiscordReactFailure with DiscordUnreactFailure with DiscordTypingFailure
    with DiscordStartThreadFailure with DiscordOpenDmFailure with DiscordRegisterCommandsFailure with DiscordFollowUpFailure
    with DiscordEditResponseFailure with DiscordDeleteResponseFailure with DiscordChannelFailure with DiscordMemberFailure
    with DiscordMessageFailure with DiscordMessagesFailure with DiscordGatewayInfoFailure with DiscordCustomFailure

object DiscordDecodeException:

    /** The failure for kyo-schema's `cause`: its leaf, where it happened and its position, and nothing of the input. A missing or
      * unknown field and a missing tag key name the key at the end of the path.
      */
    private[kyo] def apply(method: String, part: Part, cause: DecodeException)(using Frame): DiscordDecodeException =
        val located: (Failure, Seq[String], Maybe[Int]) =
            cause match
                case e: MissingFieldException          => (Failure.MissingField, e.path :+ e.fieldName, Absent)
                case e: TypeMismatchException          => (Failure.TypeMismatch, e.path, Absent)
                case e: UnknownVariantException        => (Failure.UnknownVariant, e.path, Absent)
                case e: UnknownFieldException          => (Failure.UnknownField, e.path :+ e.fieldName, Absent)
                case e: NoVariantMatchException        => (Failure.NoVariantMatch, e.path, Absent)
                case e: AmbiguousVariantMatchException => (Failure.AmbiguousVariantMatch, e.path, Absent)
                case e: MissingTagKeyException         => (Failure.MissingTagKey, e.path :+ e.tagKey, Absent)
                case e: ParseException                 => (Failure.Parse, e.path, if e.position >= 0 then Present(e.position) else Absent)
                case e: ConstructorRejectedException   => (Failure.ConstructorRejected, e.path, Absent)
                case _: TruncatedInputException        => (Failure.TruncatedInput, Seq.empty, Absent)
                case _: TrailingInputException         => (Failure.TrailingInput, Seq.empty, Absent)
                case _: RecordDecodeException          => (Failure.RecordDecode, Seq.empty, Absent)
                case _: LimitExceededException         => (Failure.LimitExceeded, Seq.empty, Absent)
                case _: RangeException                 => (Failure.Range, Seq.empty, Absent)
        val (failure, path, position) = located
        DiscordDecodeException(method, part, failure, Chunk.from(path), position)
    end apply

    private[kyo] def describe(method: String, part: Part, failure: Failure, path: Chunk[String], position: Maybe[Int]): String =
        s"Discord $method: the ${part.show} did not decode: ${failure.show}" +
            (if path.isEmpty then "" else s" at ${path.mkString(".")}") +
            position.fold("")(p => s", position $p") + "."

    /** Which of kyo-schema's decode failures it was, one case per leaf of its sealed `DecodeException`. */
    enum Failure derives CanEqual:
        case MissingField, TypeMismatch, UnknownVariant, UnknownField, NoVariantMatch, AmbiguousVariantMatch, MissingTagKey, Parse,
            ConstructorRejected, TruncatedInput, TrailingInput, RecordDecode, LimitExceeded, Range

        private[kyo] def show: String =
            this match
                case MissingField          => "a required field is missing"
                case TypeMismatch          => "a value has the wrong type"
                case UnknownVariant        => "a variant is not known"
                case UnknownField          => "a field is not known"
                case NoVariantMatch        => "no variant matches"
                case AmbiguousVariantMatch => "more than one variant matches"
                case MissingTagKey         => "a variant's tag is missing"
                case Parse                 => "the JSON does not parse"
                case ConstructorRejected   => "a value was refused by its constructor"
                case TruncatedInput        => "the input ends early"
                case TrailingInput         => "input follows the value"
                case RecordDecode          => "a record does not decode"
                case LimitExceeded         => "a decoding limit was exceeded"
                case Range                 => "a number is out of range"
    end Failure

    /** The part of a body that failed to decode. */
    enum Part derives CanEqual:
        /** The body of a 2xx answer. */
        case Response

        /** Discord's error body, `{code, message, errors?}`, or a 429's `{message, retry_after, global, code?}`. */
        case Error

        /** A Gateway frame, `{op, d, s, t}`. */
        case Frame

        /** An interaction the endpoint received, after its signature verified. */
        case Interaction

        private[kyo] def show: String =
            this match
                case Response    => "response body"
                case Error       => "error body"
                case Frame       => "Gateway frame"
                case Interaction => "interaction"
    end Part
end DiscordDecodeException

// --- Rate limits ---

/** Discord refused the request for a rate limit, or the module's own bucket wait would exceed the request timeout. The leaf is the
  * kind of limit. `retryAfter` is the delay Discord sent, in the 429's `retry_after` or its `Retry-After` header, or the bucket's
  * reset for a wait the module refused, and `Absent` when neither was sent. `code` is the error code of the 429's body, when it has
  * one.
  *
  * Note: no 429 is retried unless `DiscordConfig.retry` is set; with it set, this leaf surfaces when the schedule is done.
  */
sealed abstract class DiscordRateLimitException(message: String)(using Frame) extends DiscordException(message)
    with DiscordInitFailure with DiscordReceiveFailure with DiscordSendFailure with DiscordEditFailure
    with DiscordDeleteFailure with DiscordReactFailure with DiscordUnreactFailure with DiscordTypingFailure
    with DiscordStartThreadFailure with DiscordOpenDmFailure with DiscordRegisterCommandsFailure with DiscordFollowUpFailure
    with DiscordEditResponseFailure with DiscordDeleteResponseFailure with DiscordChannelFailure with DiscordMemberFailure
    with DiscordMessageFailure with DiscordMessagesFailure with DiscordGatewayInfoFailure with DiscordCustomFailure:
    def method: String
    def retryAfter: Maybe[Duration]
    def code: Maybe[Discord.Code]
end DiscordRateLimitException

object DiscordRateLimitException:
    private[kyo] def describe(method: String, limit: String, retryAfter: Maybe[Duration]): String =
        s"Discord rate-limited $method ($limit)" + retryAfter.fold("; Discord sent no retry delay.")(d => s"; retry after ${d.show}.")
end DiscordRateLimitException

/** A 429 with `X-RateLimit-Scope: user`, the bucket of this route and resource, or a wait on that bucket the module refused because
  * it would exceed the request timeout (nothing was sent). `bucket` is the `X-RateLimit-Bucket` Discord named.
  */
final case class DiscordRouteRateLimitException(
    method: String,
    retryAfter: Maybe[Duration],
    code: Maybe[Discord.Code],
    bucket: Maybe[String]
)(
    using Frame
) extends DiscordRateLimitException(DiscordRateLimitException.describe(method, "route", retryAfter))

/** A 429 with `X-RateLimit-Scope: shared`: a limit the resource shares with other applications. `bucket` is the bucket named. */
final case class DiscordSharedRateLimitException(
    method: String,
    retryAfter: Maybe[Duration],
    code: Maybe[Discord.Code],
    bucket: Maybe[String]
)(using Frame) extends DiscordRateLimitException(DiscordRateLimitException.describe(method, "shared", retryAfter))

/** A 429 with `X-RateLimit-Global: true` or scope `global`: the application's limit across every route. */
final case class DiscordGlobalRateLimitException(method: String, retryAfter: Maybe[Duration], code: Maybe[Discord.Code])(using Frame)
    extends DiscordRateLimitException(DiscordRateLimitException.describe(method, "global", retryAfter))

/** A 429 with no Discord JSON body: Cloudflare's restriction after too many invalid requests ("10,000 per 10 minutes",
  * `topics/rate-limits.mdx`, "Invalid Request Limit aka Cloudflare bans"). `retryAfter` is the `Retry-After` header when sent.
  */
final case class DiscordBlockedException(method: String, retryAfter: Maybe[Duration])(using Frame)
    extends DiscordRateLimitException(DiscordRateLimitException.describe(method, "blocked by Cloudflare", retryAfter)):
    def code: Maybe[Discord.Code] = Absent
end DiscordBlockedException

// --- Discord's JSON error answers ---

/** Discord answered with its error body, `{"code": code, "message": description}`. A leaf exists for each code a caller can act on,
  * on the operations that can receive it; any other code is a [[kyo.DiscordOtherApiException]]. A leaf standing for one code fixes
  * `code`; a leaf grouping codes with one remedy carries the code it received. `description` is Discord's own text, with the bot token
  * and the request's interaction token replaced before it is stored.
  */
sealed abstract class DiscordApiException(message: String)(using Frame) extends DiscordException(message):
    def method: String
    def code: Discord.Code
    def description: String
end DiscordApiException

object DiscordApiException:
    private[kyo] def describe(method: String, code: Discord.Code, description: String): String =
        s"Discord $method failed with code ${code.value}: $description"
end DiscordApiException

/** Status 401: Discord does not accept the bot token. `code` is the one it answered (0, 40001 or 50014). */
final case class DiscordUnauthorizedException(method: String, code: Discord.Code, description: String)(using Frame)
    extends DiscordApiException(s"Discord $method was refused: the bot token is not valid (code ${code.value}).")
    with DiscordInitFailure with DiscordReceiveFailure with DiscordSendFailure with DiscordEditFailure
    with DiscordDeleteFailure with DiscordReactFailure with DiscordUnreactFailure with DiscordTypingFailure
    with DiscordStartThreadFailure with DiscordOpenDmFailure with DiscordRegisterCommandsFailure with DiscordFollowUpFailure
    with DiscordEditResponseFailure with DiscordDeleteResponseFailure with DiscordChannelFailure with DiscordMemberFailure
    with DiscordMessageFailure with DiscordMessagesFailure with DiscordGatewayInfoFailure with DiscordCustomFailure

/** Code 50013: the bot sees the resource but lacks the permission to act on it. */
final case class DiscordMissingPermissionsException(method: String, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, Discord.Code(50013), description))
    with DiscordSendFailure with DiscordEditFailure with DiscordDeleteFailure with DiscordReactFailure with DiscordUnreactFailure
    with DiscordTypingFailure with DiscordStartThreadFailure with DiscordMessagesFailure with DiscordMessageFailure
    with DiscordFollowUpFailure with DiscordEditResponseFailure:
    def code: Discord.Code = Discord.Code(50013)
end DiscordMissingPermissionsException

/** Code 50001: the bot cannot see the resource. */
final case class DiscordMissingAccessException(method: String, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, Discord.Code(50001), description))
    with DiscordSendFailure with DiscordEditFailure with DiscordDeleteFailure with DiscordReactFailure with DiscordUnreactFailure
    with DiscordTypingFailure with DiscordStartThreadFailure with DiscordChannelFailure with DiscordMessageFailure
    with DiscordMessagesFailure with DiscordMemberFailure with DiscordRegisterCommandsFailure:
    def code: Discord.Code = Discord.Code(50001)
end DiscordMissingAccessException

/** Code 10003: the channel does not exist, or no longer does. */
final case class DiscordUnknownChannelException(method: String, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, Discord.Code(10003), description))
    with DiscordSendFailure with DiscordEditFailure with DiscordDeleteFailure with DiscordReactFailure with DiscordUnreactFailure
    with DiscordTypingFailure with DiscordStartThreadFailure with DiscordChannelFailure with DiscordMessageFailure
    with DiscordMessagesFailure:
    def code: Discord.Code = Discord.Code(10003)
end DiscordUnknownChannelException

/** Code 10008: the message does not exist in the channel, or no longer does. */
final case class DiscordUnknownMessageException(method: String, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, Discord.Code(10008), description))
    with DiscordEditFailure with DiscordDeleteFailure with DiscordReactFailure with DiscordUnreactFailure
    with DiscordStartThreadFailure with DiscordMessageFailure:
    def code: Discord.Code = Discord.Code(10008)
end DiscordUnknownMessageException

/** Code 10014: the emoji does not exist, or the bot cannot use it. */
final case class DiscordUnknownEmojiException(method: String, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, Discord.Code(10014), description))
    with DiscordReactFailure with DiscordUnreactFailure:
    def code: Discord.Code = Discord.Code(10014)
end DiscordUnknownEmojiException

/** Code 10013: the user does not exist. */
final case class DiscordUnknownUserException(method: String, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, Discord.Code(10013), description))
    with DiscordOpenDmFailure with DiscordMemberFailure:
    def code: Discord.Code = Discord.Code(10013)
end DiscordUnknownUserException

/** Code 10007: the user is not a member of the guild. */
final case class DiscordUnknownMemberException(method: String, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, Discord.Code(10007), description))
    with DiscordMemberFailure:
    def code: Discord.Code = Discord.Code(10007)
end DiscordUnknownMemberException

/** Code 10004: the guild does not exist, or the bot is not in it. */
final case class DiscordUnknownGuildException(method: String, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, Discord.Code(10004), description))
    with DiscordMemberFailure with DiscordRegisterCommandsFailure:
    def code: Discord.Code = Discord.Code(10004)
end DiscordUnknownGuildException

/** Code 50007: the user does not accept direct messages from the bot. */
final case class DiscordCannotMessageUserException(method: String, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, Discord.Code(50007), description))
    with DiscordSendFailure:
    def code: Discord.Code = Discord.Code(50007)
end DiscordCannotMessageUserException

/** Code 50035: Discord refused fields of the request body. `fieldErrors` is the body's `errors` tree flattened, one entry per refused
  * field: the path of keys and array indexes to it, and Discord's code and text for each refusal.
  */
final case class DiscordInvalidFormBodyException(
    method: String,
    description: String,
    fieldErrors: Chunk[DiscordInvalidFormBodyException.FieldError]
)(
    using Frame
) extends DiscordApiException(
        DiscordApiException.describe(method, Discord.Code(50035), description) +
            (if fieldErrors.isEmpty then "" else fieldErrors.map(_.show).mkString(" (", "; ", ")"))
    )
    with DiscordSendFailure with DiscordEditFailure with DiscordStartThreadFailure with DiscordRegisterCommandsFailure
    with DiscordFollowUpFailure with DiscordEditResponseFailure:
    def code: Discord.Code = Discord.Code(50035)
end DiscordInvalidFormBodyException

object DiscordInvalidFormBodyException:
    /** One refused field: `path` from the body's root, and Discord's `code` and `description` for the refusal. */
    final case class FieldError(path: Chunk[String], code: String, description: String) derives CanEqual:
        private[kyo] def show: String = s"${path.mkString(".")}: $code $description"
end DiscordInvalidFormBodyException

/** Codes 200000, 200001 and 240000: Discord's moderation or AutoMod blocked the content. `code` says which. */
final case class DiscordBlockedByModerationException(method: String, code: Discord.Code, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, code, description))
    with DiscordSendFailure with DiscordEditFailure with DiscordStartThreadFailure with DiscordFollowUpFailure
    with DiscordEditResponseFailure

/** Codes 30010 (reactions), 30015 (attachments), 30032 and 30034 (commands) and 160006 (active threads): a maximum was reached.
  * `code` says which.
  */
final case class DiscordMaximumReachedException(method: String, code: Discord.Code, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, code, description))
    with DiscordReactFailure with DiscordSendFailure with DiscordRegisterCommandsFailure with DiscordStartThreadFailure

/** Code 160004: a thread was already started from this message. */
final case class DiscordThreadAlreadyExistsException(method: String, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, Discord.Code(160004), description))
    with DiscordStartThreadFailure:
    def code: Discord.Code = Discord.Code(160004)
end DiscordThreadAlreadyExistsException

/** Codes 50083 (the thread is archived) and 160005 (the thread is locked). `code` says which. */
final case class DiscordThreadClosedException(method: String, code: Discord.Code, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, code, description))
    with DiscordSendFailure with DiscordEditFailure with DiscordReactFailure

/** Codes 40005 (the request entity is too large) and 50045 (a file is larger than the maximum). `code` says which. */
final case class DiscordPayloadTooLargeException(method: String, code: Discord.Code, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, code, description))
    with DiscordSendFailure with DiscordEditFailure with DiscordFollowUpFailure with DiscordEditResponseFailure

/** Codes 10015 and 50027 on an interaction route: the interaction token expired ("Interaction tokens are valid for 15 minutes",
  * `interactions/receiving-and-responding.mdx`). `code` says which.
  */
final case class DiscordInteractionExpiredException(method: String, code: Discord.Code, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, code, description))
    with DiscordFollowUpFailure with DiscordEditResponseFailure with DiscordDeleteResponseFailure

/** Any Discord error code with no leaf of its own on the operation that received it. */
final case class DiscordOtherApiException(method: String, status: HttpStatus, code: Discord.Code, description: String)(using Frame)
    extends DiscordApiException(DiscordApiException.describe(method, code, description) + s" (HTTP ${status.code})")
    with DiscordInitFailure with DiscordReceiveFailure with DiscordSendFailure with DiscordEditFailure
    with DiscordDeleteFailure with DiscordReactFailure with DiscordUnreactFailure with DiscordTypingFailure
    with DiscordStartThreadFailure with DiscordOpenDmFailure with DiscordRegisterCommandsFailure with DiscordFollowUpFailure
    with DiscordEditResponseFailure with DiscordDeleteResponseFailure with DiscordChannelFailure with DiscordMemberFailure
    with DiscordMessageFailure with DiscordMessagesFailure with DiscordGatewayInfoFailure with DiscordCustomFailure

// --- The Gateway ---

/** The Gateway closed with 4004: the bot token in Identify was not accepted. */
final case class DiscordAuthenticationFailedException()(using Frame)
    extends DiscordException("Discord's Gateway closed with 4004: the bot token in Identify was not accepted.")
    with DiscordInitFailure with DiscordReceiveFailure

/** The Gateway closed with 4010: the `shard` sent in Identify is not valid for the bot. */
final case class DiscordInvalidShardException(shard: Discord.Shard)(using Frame)
    extends DiscordException(s"Discord's Gateway closed with 4010: shard ${shard.show} is not valid.")
    with DiscordInitFailure with DiscordReceiveFailure

/** The Gateway closed with 4011: the bot is in too many guilds for one connection and must shard. */
final case class DiscordShardingRequiredException()(using Frame)
    extends DiscordException("Discord's Gateway closed with 4011: the bot must shard its connection.")
    with DiscordInitFailure with DiscordReceiveFailure

/** The Gateway closed with 4013: `intents` is not a valid intents value. */
final case class DiscordInvalidIntentsException(intents: Discord.Intents)(using Frame)
    extends DiscordException(s"Discord's Gateway closed with 4013: intents ${intents.value} are not valid.")
    with DiscordInitFailure with DiscordReceiveFailure

/** The Gateway closed with 4014: `intents` holds a privileged intent not enabled for the application in the developer portal. */
final case class DiscordDisallowedIntentsException(intents: Discord.Intents)(using Frame)
    extends DiscordException(
        s"Discord's Gateway closed with 4014: intents ${intents.value} hold a privileged intent the application is not approved for."
    )
    with DiscordInitFailure with DiscordReceiveFailure

/** The Gateway closed with `code` and `reason` where no reconnection follows: on 4012, on a close before Ready under the reconnect
  * policy `Off` (after Ready, `Off` ends `receive` cleanly instead), and on the close that found the backoff schedule done.
  * A drop with no close frame carries 1006. `reason` is Discord's text, with the bot token replaced before it is stored.
  */
final case class DiscordGatewayClosedException(code: Int, reason: String)(using Frame)
    extends DiscordException(s"Discord's Gateway closed with $code" + (if reason.isEmpty then "." else s": $reason."))
    with DiscordInitFailure with DiscordReceiveFailure

/** `GET /gateway/bot` answered `session_start_limit.remaining` 0: another Identify would count against the daily budget, past which
  * Discord resets the token. No Identify was sent. `total` is the budget and `resetAfter` the time until it refills.
  */
final case class DiscordSessionStartLimitException(total: Int, resetAfter: Duration)(using Frame)
    extends DiscordException(s"Discord's session start budget of $total is spent; it refills in ${resetAfter.show}.")
    with DiscordInitFailure with DiscordReceiveFailure

/** The module refused to connect to a Gateway URL Discord supplied (`url` of `GET /gateway/bot`, or a `resume_gateway_url`) that is
  * not `wss` with a host. Nothing of the URL is copied.
  */
final case class DiscordRefusedUrlException(method: String)(using Frame)
    extends DiscordException(s"Discord $method refused a Gateway URL that is not wss with a host.")
    with DiscordInitFailure with DiscordReceiveFailure

// --- The interactions endpoint ---

/** An interaction request arrived without `header`. */
final case class DiscordWebhookMissingHeaderException(header: DiscordWebhookMissingHeaderException.Header)(using Frame)
    extends DiscordException(s"Discord interaction request carries no ${header.name} header.")
    with DiscordWebhookVerifyFailure

object DiscordWebhookMissingHeaderException:
    /** A header the signature check reads. */
    enum Header derives CanEqual:
        case Signature, Timestamp

        private[kyo] def name: String =
            this match
                case Signature => "X-Signature-Ed25519"
                case Timestamp => "X-Signature-Timestamp"
    end Header
end DiscordWebhookMissingHeaderException

/** An interaction request's `header` is not in its documented shape: the signature is 128 hex characters, the timestamp 1 to 20
  * decimal digits.
  */
final case class DiscordWebhookMalformedHeaderException(
    header: DiscordWebhookMissingHeaderException.Header,
    problem: DiscordWebhookMalformedHeaderException.Problem
)(using Frame) extends DiscordException(s"Discord interaction request's ${header.name} header ${problem.show}.")
    with DiscordWebhookVerifyFailure

object DiscordWebhookMalformedHeaderException:
    /** What is wrong with the header's value. Never the value itself. */
    enum Problem derives CanEqual:
        /** The value has `length` characters, not the documented length. */
        case Length(length: Int)

        /** The value is not hex. */
        case Hex(failure: kyo.Hex.Failure)

        /** The value is not 1 to 20 decimal digits. */
        case NotSeconds

        private[kyo] def show: String =
            this match
                case Length(length) => s"has $length characters"
                case Hex(failure)   => s"is not hex (${failure.message})"
                case NotSeconds     => "is not 1 to 20 decimal digits"
    end Problem
end DiscordWebhookMalformedHeaderException

/** An interaction request's signature does not verify against the application's public key, over the timestamp and the body. */
final case class DiscordWebhookSignatureMismatchException()(using Frame)
    extends DiscordException("Discord interaction request's signature does not verify.")
    with DiscordWebhookVerifyFailure

/** A verified interaction request's body did not decode. Fields as [[kyo.DiscordDecodeException]]'s. */
final case class DiscordWebhookDecodeException(
    part: DiscordDecodeException.Part,
    failure: DiscordDecodeException.Failure,
    path: Chunk[String],
    position: Maybe[Int]
)(using Frame) extends DiscordException(DiscordDecodeException.describe("interactions endpoint", part, failure, path, position))
    with DiscordWebhookDecodeFailure

// --- Values Discord would refuse (the failure of an `init`) ---

/** Text that cannot be a [[kyo.Discord.Token]] or a [[kyo.Discord.InteractionToken]], refused by its `init`. */
final case class DiscordInvalidTokenException(token: DiscordInvalidTokenException.Token, problem: DiscordInvalidTokenException.Problem)(
    using Frame
) extends DiscordException(s"${token.show} is not usable: ${problem.show(token)}.")

object DiscordInvalidTokenException:
    /** Which secret was refused. */
    enum Token derives CanEqual:
        case Bot, Interaction

        private[kyo] def show: String =
            this match
                case Bot         => "Discord.Token"
                case Interaction => "Discord.InteractionToken"

        private[kyo] def allowed: String =
            this match
                case Bot         => "printable ASCII other than space"
                case Interaction => "A-Z a-z 0-9 . _ -"
    end Token

    /** What is wrong with the text. A position, never the character, since the character is part of the secret. */
    enum Problem derives CanEqual:
        case Empty
        case TooLong(length: Int, max: Int)
        case Character(position: Int)

        private[kyo] def show(token: Token): String =
            this match
                case Empty                => "it is empty"
                case TooLong(length, max) => s"it has $length characters, more than $max"
                case Character(position)  => s"the character at position $position is not ${token.allowed}"
    end Problem
end DiscordInvalidTokenException

/** Hex that is not a usable Ed25519 public key, refused by `Discord.PublicKey.init`. `problem` keeps kyo-crypto's reason, so an
  * operator sees which rule the key broke.
  */
final case class DiscordInvalidPublicKeyException(problem: DiscordInvalidPublicKeyException.Problem)(using Frame)
    extends DiscordException(s"Discord.PublicKey is not usable: ${problem.show}.")

object DiscordInvalidPublicKeyException:
    /** Why the key was refused: the text is not hex, or the bytes are not a key kyo-crypto accepts. */
    enum Problem derives CanEqual:
        case Hex(failure: kyo.Hex.Failure)
        case Key(failure: Ed25519.KeyFailure)

        private[kyo] def show: String =
            this match
                case Hex(failure) => s"it is not hex (${failure.message})"
                case Key(failure) => s"it is not an Ed25519 public key (${showKeyFailure(failure)})"
    end Problem

    private def showKeyFailure(failure: Ed25519.KeyFailure): String =
        failure match
            case Ed25519.KeyFailure.Length(length) => s"$length bytes, where a key is 32"
            case Ed25519.KeyFailure.YNotReduced    => "the encoding of y is not canonical"
            case Ed25519.KeyFailure.NotOnCurve     => "no point on the curve has this y"
            case Ed25519.KeyFailure.ZeroWithSign   => "x is 0 with the sign bit set"
            case Ed25519.KeyFailure.SmallOrder     => "the point has small order"
end DiscordInvalidPublicKeyException

/** Text that is not a snowflake id, refused by an id's `parse`. */
final case class DiscordInvalidIdException(problem: DiscordInvalidIdException.Problem)(using Frame)
    extends DiscordException(s"Discord id is not usable: ${problem.show}.")

object DiscordInvalidIdException:
    /** What is wrong with the text. */
    enum Problem derives CanEqual:
        case Empty

        /** The character at `position` is not a decimal digit. */
        case Character(position: Int)

        /** The number does not fit in 64 unsigned bits. */
        case Overflow

        private[kyo] def show: String =
            this match
                case Empty               => "it is empty"
                case Character(position) => s"the character at position $position is not a decimal digit"
                case Overflow            => "it does not fit in 64 unsigned bits"
    end Problem
end DiscordInvalidIdException

/** A path that is not relative segments under the API base, refused by `Discord.Path.init`. */
final case class DiscordInvalidPathException(problem: DiscordInvalidPathException.Problem)(using Frame)
    extends DiscordException(s"Discord.Path is not usable: ${problem.show}.")

object DiscordInvalidPathException:
    /** What is wrong with the path. A position, never the text, which `custom` may have built from a token. */
    enum Problem derives CanEqual:
        case Empty

        /** The character at `position` is not one of the segment alphabet, or a `/` that starts the path, ends it or doubles. */
        case Character(position: Int)

        /** The segment starting at `position` is `.` or `..`, which would move the request off the API base. */
        case DotSegment(position: Int)

        private[kyo] def show: String =
            this match
                case Empty                => "it is empty"
                case Character(position)  => s"the character at position $position is not one of A-Z a-z 0-9 . _ - @ or a single inner /"
                case DotSegment(position) => s"the segment at position $position is . or .."
    end Problem
end DiscordInvalidPathException

/** A [[kyo.DiscordConfig]] setting with a value it cannot use, refused by `DiscordConfig.init` or by `Discord.Shard.init`. */
final case class DiscordInvalidConfigException(problem: DiscordInvalidConfigException.Problem)(using Frame)
    extends DiscordException(s"DiscordConfig.${problem.show}.")

object DiscordInvalidConfigException:
    /** The setting and what is wrong with it. */
    enum Problem derives CanEqual:
        case BaseUrl(problem: UrlProblem)
        case Shard(id: Int, count: Int)
        case InteractionDeadline(value: Duration, max: Duration)
        case GlobalRateLimit(value: Int)
        case RetryMaxDelay(value: Duration)
        case RequestTimeout(value: Duration)
        case TransferTimeout(value: Duration)
        case ConnectTimeout(value: Duration)

        private[kyo] def show: String =
            this match
                case BaseUrl(problem)                => s"baseUrl ${problem.show}"
                case Shard(id, count)                => s"shard must have 0 <= id < count; got id $id of count $count"
                case InteractionDeadline(value, max) =>
                    s"interactionDeadline must be positive and less than ${max.show}; got ${value.show}"
                case GlobalRateLimit(value) => s"globalRateLimit must be at least 1 request per second; got $value"
                case RetryMaxDelay(value)   => s"retryMaxDelay must be positive and finite; got ${value.show}"
                case RequestTimeout(value)  => s"requestTimeout must be positive and finite; got ${value.show}"
                case TransferTimeout(value) => s"transferTimeout must be positive and finite; got ${value.show}"
                case ConnectTimeout(value)  => s"connectTimeout must be positive and finite; got ${value.show}"
    end Problem

    /** What is wrong with a base URL. */
    enum UrlProblem derives CanEqual:
        case Scheme
        case Host
        case UnixSocket
        case Query
        case TrailingSlash

        /** The host holds `@`: user info written into it, which the config would render. */
        case UserInfo

        /** The URL's character at `position` is outside printable ASCII, which kyo-http refuses to send. */
        case Character(position: Int)

        private[kyo] def show: String =
            this match
                case Scheme              => "must be an http or https URL"
                case Host                => "must name a host"
                case UnixSocket          => "must not be a Unix socket"
                case UserInfo            => "must hold no user info"
                case Query               => "must hold no query"
                case TrailingSlash       => "must not end with / after a path"
                case Character(position) => s"must be printable ASCII; the character at position $position is not"
    end UrlProblem
end DiscordInvalidConfigException

/** A [[kyo.DiscordWebhookConfig]] path no request can reach, refused by `DiscordWebhookConfig.init`. */
final case class DiscordInvalidWebhookConfigException(problem: DiscordInvalidWebhookConfigException.Problem)(using Frame)
    extends DiscordException(s"DiscordWebhookConfig is not usable: ${problem.show}.")

object DiscordInvalidWebhookConfigException:
    /** What is wrong with the config. */
    enum Problem derives CanEqual:
        /** The path's character at `position` is `?`, `#`, a space, a control character or outside ASCII. */
        case PathCharacter(position: Int)

        private[kyo] def show: String =
            this match
                case PathCharacter(position) => s"the path's character at position $position cannot appear in a request path"
    end Problem
end DiscordInvalidWebhookConfigException

/** A message Discord would refuse, refused by the `init` of [[kyo.Discord.Message.Create]], [[kyo.Discord.Message.Edit]],
  * [[kyo.Discord.Message.Page]], [[kyo.Discord.Embed]], [[kyo.Discord.AllowedMentions]] or [[kyo.Discord.File]]. Lengths count
  * characters (Unicode code points), as Discord does. No text of the message is kept.
  */
final case class DiscordInvalidMessageException(problem: DiscordInvalidMessageException.Problem)(using Frame)
    extends DiscordException(s"Discord message is not usable: ${problem.show}.")

object DiscordInvalidMessageException:
    /** What is wrong, with the documented bound. */
    enum Problem derives CanEqual:
        /** No content, embed, component or file: Discord requires one of them. */
        case Empty
        case ContentLength(length: Int, max: Int)
        case EmbedCount(count: Int, max: Int)
        case EmbedTotal(characters: Int, max: Int)
        case ComponentCount(count: Int, max: Int)
        case FileCount(count: Int, max: Int)

        /** A flag outside the ones a message can be sent with. */
        case Flags(value: Int)
        case PageLimit(value: Int)
        case EmbedTitle(length: Int, max: Int)
        case EmbedDescription(length: Int, max: Int)
        case EmbedFieldCount(count: Int, max: Int)
        case EmbedFieldName(index: Int, length: Int, max: Int)
        case EmbedFieldValue(index: Int, length: Int, max: Int)
        case EmbedFooter(length: Int, max: Int)
        case EmbedAuthor(length: Int, max: Int)
        case EmbedColor(value: Int)
        case MentionCount(kind: String, count: Int, max: Int)

        /** A mention kind both parsed from the content and listed explicitly, which Discord refuses. */
        case MentionConflict(kind: String)
        case FileName(length: Int)

        /** The file name's character at `position` is `"`, `\`, `/`, a control character or a CR or LF. */
        case FileNameCharacter(position: Int)
        case FileDescription(length: Int, max: Int)

        private[kyo] def show: String =
            this match
                case Empty                           => "it has no content, embed, component or file"
                case ContentLength(length, max)      => s"content has $length characters, more than $max"
                case EmbedCount(count, max)          => s"it has $count embeds, more than $max"
                case EmbedTotal(characters, max)     => s"its embeds hold $characters characters, more than $max"
                case ComponentCount(count, max)      => s"it has $count top-level components, more than $max"
                case FileCount(count, max)           => s"it has $count files, more than $max"
                case Flags(value)                    => s"flags $value hold a flag a message cannot be sent with"
                case PageLimit(value)                => s"a page limit must be 1 to 100; got $value"
                case EmbedTitle(length, max)         => s"an embed title has $length characters, more than $max"
                case EmbedDescription(length, max)   => s"an embed description has $length characters, more than $max"
                case EmbedFieldCount(count, max)     => s"an embed has $count fields, more than $max"
                case EmbedFieldName(i, length, max)  => s"embed field $i's name has $length characters, outside 1 to $max"
                case EmbedFieldValue(i, length, max) =>
                    s"embed field $i's value has $length characters, outside 1 to $max"
                case EmbedFooter(length, max)       => s"an embed footer has $length characters, more than $max"
                case EmbedAuthor(length, max)       => s"an embed author name has $length characters, more than $max"
                case EmbedColor(value)              => s"an embed color must be 0 to 0xFFFFFF; got $value"
                case MentionCount(kind, count, max) => s"allowed mentions list $count $kind, more than $max"
                case MentionConflict(kind)          => s"allowed mentions both parse and list $kind"
                case FileName(length)               => s"a file name has $length characters, outside 1 to 1024"
                case FileNameCharacter(position)    => s"a file name's character at position $position cannot be in a file name"
                case FileDescription(length, max)   => s"a file description has $length characters, more than $max"
    end Problem
end DiscordInvalidMessageException

/** A message component Discord would refuse, refused by the component's `init`. */
final case class DiscordInvalidComponentException(problem: DiscordInvalidComponentException.Problem)(using Frame)
    extends DiscordException(s"Discord component is not usable: ${problem.show}.")

object DiscordInvalidComponentException:
    /** What is wrong, with the documented bound. */
    enum Problem derives CanEqual:
        case CustomId(length: Int)
        case Label(length: Int, max: Int)
        case Url(length: Int)

        /** A link button without a URL or with a custom id, or another button without a custom id or with a URL. */
        case ButtonTarget
        case OptionCount(count: Int)
        case OptionText(field: String, length: Int)
        case Placeholder(length: Int, max: Int)

        /** `minValues` and `maxValues` outside 0 to 25 and 1 to 25, or the minimum above the maximum or above the options. */
        case Values(min: Int, max: Int)
        case TextLength(min: Int, max: Int)
        case Value(length: Int)

        /** An action row that is empty, holds more than 5 buttons, or holds a select or text input beside anything else. */
        case RowContents(count: Int)

        private[kyo] def show: String =
            this match
                case CustomId(length)          => s"a custom id has $length characters, outside 1 to 100"
                case Label(length, max)        => s"a label has $length characters, more than $max"
                case Url(length)               => s"a button URL has $length characters, outside 1 to 512"
                case ButtonTarget              => "a link button needs a URL and no custom id; any other button a custom id and no URL"
                case OptionCount(count)        => s"a string select has $count options, outside 1 to 25"
                case OptionText(field, length) => s"a select option's $field has $length characters, outside its bound of 100"
                case Placeholder(length, max)  => s"a placeholder has $length characters, more than $max"
                case Values(min, max)          => s"minValues $min and maxValues $max are outside what the select allows"
                case TextLength(min, max)      => s"minLength $min and maxLength $max are outside 0 to 4000 and 1 to 4000, or reversed"
                case Value(length)             => s"a text input value has $length characters, more than 4000"
                case RowContents(count)        => s"an action row of $count components must hold 1 to 5 buttons or one other component"
    end Problem
end DiscordInvalidComponentException

/** An application command Discord would refuse, refused by `Discord.Command.Create.init`, `Option.init` or `Choice.init`. */
final case class DiscordInvalidCommandException(problem: DiscordInvalidCommandException.Problem)(using Frame)
    extends DiscordException(s"Discord command is not usable: ${problem.show}.")

object DiscordInvalidCommandException:
    /** What is wrong, with the documented bound. */
    enum Problem derives CanEqual:
        case NameLength(length: Int)

        /** The name's character at `position` is not a lowercase letter, a digit, `-`, `_` or `'`. */
        case NameCharacter(position: Int)
        case DescriptionLength(length: Int, max: Int)

        /** A user or message command with a description, or options, which only a slash command has. */
        case NotChatInput
        case OptionCount(count: Int)
        case ChoiceCount(count: Int)
        case ChoiceName(length: Int)

        /** A choice's string value is longer than 100 characters. */
        case ChoiceValue(length: Int)

        /** A required option after an optional one. */
        case RequiredOrder(name: String)

        /** Choices and autocomplete on one option. */
        case ChoicesWithAutocomplete(name: String)
        case StringLength(min: Int, max: Int)

        private[kyo] def show: String =
            this match
                case NameLength(length)      => s"a name has $length characters, outside 1 to 32"
                case NameCharacter(position) => s"a name's character at position $position is not a lowercase letter, a digit, - _ or '"
                case DescriptionLength(length, max) => s"a description has $length characters, outside 1 to $max"
                case NotChatInput                   => "only a slash command has a description and options"
                case OptionCount(count)             => s"$count options, more than 25"
                case ChoiceCount(count)             => s"$count choices, more than 25"
                case ChoiceName(length)             => s"a choice name has $length characters, outside 1 to 100"
                case ChoiceValue(length)            => s"a choice value has $length characters, more than 100"
                case RequiredOrder(name)            => s"required option $name follows an optional one"
                case ChoicesWithAutocomplete(name)  => s"option $name has both choices and autocomplete"
                case StringLength(min, max)         => s"minLength $min and maxLength $max are outside 0 to 6000 and 1 to 6000, or reversed"
    end Problem
end DiscordInvalidCommandException

/** An emoji a reaction cannot be made with, refused by `Discord.Reaction.init`. */
final case class DiscordInvalidReactionException(problem: DiscordInvalidReactionException.Problem)(using Frame)
    extends DiscordException(s"Discord reaction is not usable: ${problem.show}.")

object DiscordInvalidReactionException:
    /** What is wrong with the emoji. */
    enum Problem derives CanEqual:
        /** No name, as Discord sends a deleted custom emoji; a reaction's path names the emoji by `name` or `name:id`. */
        case NoName

        private[kyo] def show: String =
            this match
                case NoName => "the emoji has no name"
    end Problem
end DiscordInvalidReactionException

/** A thread Discord would refuse to start, refused by `Discord.Thread.Start.init`. */
final case class DiscordInvalidThreadException(problem: DiscordInvalidThreadException.Problem)(using Frame)
    extends DiscordException(s"Discord thread is not usable: ${problem.show}.")

object DiscordInvalidThreadException:
    /** What is wrong, with the documented bound. */
    enum Problem derives CanEqual:
        case NameLength(length: Int)

        /** An archive duration other than 60, 1440, 4320 or 10080 minutes. */
        case AutoArchiveDuration(minutes: Int)
        case RateLimitPerUser(seconds: Int)

        /** A channel type that is not a thread's. */
        case ThreadType(value: Int)

        private[kyo] def show: String =
            this match
                case NameLength(length)           => s"a name has $length characters, outside 1 to 100"
                case AutoArchiveDuration(minutes) => s"an archive duration must be 60, 1440, 4320 or 10080 minutes; got $minutes"
                case RateLimitPerUser(seconds)    => s"a slow mode must be 0 to 21600 seconds; got $seconds"
                case ThreadType(value)            => s"channel type $value is not a thread type"
    end Problem
end DiscordInvalidThreadException

/** An interaction answer Discord would refuse, refused by `Discord.InteractionResponse.Autocomplete.init` or `Modal.init`. */
final case class DiscordInvalidInteractionResponseException(problem: DiscordInvalidInteractionResponseException.Problem)(using Frame)
    extends DiscordException(s"Discord interaction answer is not usable: ${problem.show}.")

object DiscordInvalidInteractionResponseException:
    /** What is wrong, with the documented bound. */
    enum Problem derives CanEqual:
        case ChoiceCount(count: Int)
        case CustomId(length: Int)
        case Title(length: Int)
        case ComponentCount(count: Int)

        private[kyo] def show: String =
            this match
                case ChoiceCount(count)    => s"$count autocomplete choices, more than 25"
                case CustomId(length)      => s"a modal custom id has $length characters, outside 1 to 100"
                case Title(length)         => s"a modal title has $length characters, outside 1 to 45"
                case ComponentCount(count) => s"a modal has $count components, outside 1 to 5"
    end Problem
end DiscordInvalidInteractionResponseException
