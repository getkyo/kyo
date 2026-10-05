package kyo

import kyo.crypto.Rsa

/** Typed error hierarchy for kyo-teams: a sealed base over `KyoException` with flat leaves, one per failure a caller can tell apart.
  *
  * The leaves fall into five groups:
  *   - the transport and the response: [[kyo.TeamsTransportException]], [[kyo.TeamsRefusedUrlException]],
  *     [[kyo.TeamsUnexpectedStatusException]], [[kyo.TeamsDecodeException]] and [[kyo.TeamsRateLimitException]];
  *   - the outbound token: [[kyo.TeamsTokenRejectedException]], [[kyo.TeamsCredentialException]] and
  *     [[kyo.TeamsUnsupportedTokenTypeException]];
  *   - the Bot Connector's `ErrorResponse` answers: the category [[kyo.TeamsApiException]], with a leaf per code Microsoft gives its
  *     own remedy and [[kyo.TeamsOtherApiException]] carrying any other;
  *   - an inbound request whose token does not verify: the category [[kyo.TeamsAuthenticationException]], a leaf per reason, and
  *     [[kyo.TeamsWebhookDecodeException]] for a body that is not an Activity;
  *   - a value Teams would refuse, the failure of that type's `init` (a `Result`), such as [[kyo.TeamsInvalidConfigException]].
  *
  * Each public operation fails with its own sealed trait, such as [[kyo.TeamsSendFailure]], and a leaf extends the trait of every
  * operation that can produce it, so a row names exactly the leaves a caller can meet. No row names `TeamsException` itself.
  *
  * IMPORTANT: no leaf holds a credential. The client secret travels only in a token request's body and the access token only in a
  * header, and no kyo-http failure keeps either; the transport leaf keeps no kyo-http failure at all, only kyo-net's cause of a failed
  * connection. Text kept from Microsoft (an API leaf's `description`) has the access token and every credential text the config
  * holds replaced by `<redacted>`. Text kept from an inbound token (an issuer, an algorithm, a key id) is bounded and printable ASCII,
  * since a forged token chooses it. The decode leaves name kyo-schema's failure, its path and its position, and keep nothing quoted
  * from the input.
  *
  * @see
  *   [[kyo.TeamsApiException]] the Bot Connector's error answers
  * @see
  *   [[kyo.TeamsAuthenticationException]] inbound requests that do not verify
  * @see
  *   [[kyo.TeamsTransportException]] transport failures
  */
sealed abstract class TeamsException(message: String)(using Frame) extends KyoException(message)

object TeamsException:
    given CanEqual[TeamsException, TeamsException] = CanEqual.derived

    /** What is wrong with a URL a config or a [[kyo.Teams.ServiceUrl]] holds. A position, never the text. */
    enum UrlProblem derives CanEqual:
        /** The text is not a URL kyo-http can parse. */
        case Unparsable

        /** The scheme is not `http` or `https`, or `http` where only `https` is accepted. */
        case Scheme

        /** The URL names no host. */
        case Host

        /** The URL names a Unix socket. */
        case UnixSocket

        /** The authority holds user info, which the URL would render. */
        case UserInfo

        /** The URL holds a query. */
        case Query

        /** The URL holds a fragment. */
        case Fragment

        /** The URL holds a path where only an origin is accepted. */
        case Path

        /** The character at `position` is outside printable ASCII, which kyo-http refuses to send. */
        case Character(position: Int)

        private[kyo] def show: String =
            this match
                case Unparsable          => "is not a URL"
                case Scheme              => "has a scheme that is not accepted here"
                case Host                => "names no host"
                case UnixSocket          => "names a Unix socket"
                case UserInfo            => "holds user info"
                case Query               => "holds a query"
                case Fragment            => "holds a fragment"
                case Path                => "holds a path where only an origin is accepted"
                case Character(position) => s"holds a character outside printable ASCII at position $position"
    end UrlProblem

    /** At most `max` characters of `text`, each printable ASCII or `?`: text chosen by a peer, kept in a leaf. */
    private[kyo] def bounded(text: String, max: Int): String =
        val kept = if text.length > max then text.substring(0, max) else text
        kept.map(c => if c < ' ' || c > '~' then '?' else c)

end TeamsException

/** What `Teams.send` can fail with. */
sealed trait TeamsSendFailure extends TeamsException

/** What `Teams.reply` can fail with. */
sealed trait TeamsReplyFailure extends TeamsException

/** What `Teams.edit` can fail with. */
sealed trait TeamsEditFailure extends TeamsException

/** What `Teams.delete` can fail with. */
sealed trait TeamsDeleteFailure extends TeamsException

/** What `Teams.typing` can fail with. */
sealed trait TeamsTypingFailure extends TeamsException

/** What `Teams.createConversation` can fail with. */
sealed trait TeamsCreateConversationFailure extends TeamsException

/** What `Teams.members` can fail with. */
sealed trait TeamsMembersFailure extends TeamsException

/** What `Teams.member` can fail with. */
sealed trait TeamsMemberFailure extends TeamsException

/** What `Teams.custom` can fail with: only the answers that mean the same on every route. */
sealed trait TeamsCustomFailure extends TeamsException

/** What `Teams.Webhook.verify` can fail with: the request's token, the signing keys it is checked against, and the two fields of the
  * body the token binds.
  */
sealed trait TeamsWebhookVerifyFailure extends TeamsException

/** What `Teams.Webhook.decode` can fail with: a body that is not an Activity. */
sealed trait TeamsWebhookDecodeFailure extends TeamsException

/** A request failed at the transport, before the answer could be read. `method` is the route (`POST /v3/conversations`), the token
  * request (`POST oauth2/v2.0/token`, or `GET MSI/token` for a managed identity), or a key fetch (`GET openid-metadata`, `GET jwks`).
  * `kind` is what kyo-http reported, `host` and `port` the server the request went to, and `timeout` the limit that ran out, for the
  * two timeouts. No kyo-http failure is kept.
  *
  * `cause` is kyo-net's failure behind a connection that could not be made (an errno, a DNS failure, a TLS handshake error), which
  * holds no URL. It stays out of the leaf's equality and is the `getCause`.
  */
final case class TeamsTransportException(
    method: String,
    kind: TeamsTransportException.Kind,
    host: String,
    port: Int,
    timeout: Maybe[Duration]
)(
    val cause: Maybe[kyo.net.NetException] = Absent
)(using Frame)
    extends TeamsException(
        s"Teams $method failed at the transport to $host:$port: ${kind.show}" + timeout.fold("")(t => s" after ${t.show}") + "."
    )
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure
    with TeamsWebhookVerifyFailure:
    // Not passed to `KyoException`'s constructor: it would embed the cause's message, formatted per environment, into this leaf's
    // `getMessage`, which states only what failed. `null` is `Throwable.getCause`'s contract for "no cause".
    override def getCause(): Throwable = cause.fold(null)(identity)
end TeamsTransportException

object TeamsTransportException:
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
          * outside 100 to 599), or a malformed chunked body. It maps to a failure, not a panic, because a malformed response is a
          * condition the peer controls.
          */
        case Protocol

        /** The connection closed before the response was complete: before its head, or before the body its framing declared. */
        case ConnectionClosed

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
                case Protocol                           => "the response broke the HTTP protocol"
                case ConnectionClosed                   => "the connection closed before the response was complete"
                case PoolExhausted(max)                 => s"all $max connections were in use"
                case PayloadTooLarge(bodySize, maxSize) => s"the response body of ${bodySize.show} exceeds ${maxSize.show}"
    end Kind
end TeamsTransportException

/** The module refused to send to a URL: a service URL whose origin `TeamsConfig.serviceHosts` does not list, or a `jwks_uri` in the
  * OpenID metadata that is not an absolute https URL on a host. Nothing of the URL is copied.
  */
final case class TeamsRefusedUrlException(method: String)(using Frame)
    extends TeamsException(s"Teams $method refused to send to a URL outside the ones the module sends to.")
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure
    with TeamsWebhookVerifyFailure

/** A non-2xx answer whose body is none of the error bodies Microsoft documents for the request, such as an HTML page from a proxy.
  * No body text is kept.
  */
final case class TeamsUnexpectedStatusException(method: String, status: HttpStatus)(using Frame)
    extends TeamsException(s"Teams $method answered HTTP ${status.code} without an error body the module reads.")
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure
    with TeamsWebhookVerifyFailure

/** An answer did not decode. `part` says which document failed, `failure` which of kyo-schema's decode failures it was, `path` where
  * in the value it happened, and `position` the offset in the body when kyo-schema gave one.
  *
  * Nothing is copied from the body: a token answer holds the access token and an API answer may hold a user's text, so kyo-schema's
  * exception, which quotes the input it could not read, is not kept.
  */
final case class TeamsDecodeException(
    method: String,
    part: TeamsDecodeException.Part,
    failure: TeamsDecodeException.Failure,
    path: Chunk[String],
    position: Maybe[Int]
)(using Frame)
    extends TeamsException(
        s"Teams $method ${part.show} did not decode: ${failure.show}" +
            (if path.isEmpty then "" else s" at ${path.mkString(".")}") +
            position.fold("")(p => s", position $p") + "."
    )
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure
    with TeamsWebhookVerifyFailure

object TeamsDecodeException:

    /** The leaf for kyo-schema's `ex` while decoding `part` of `method`'s answer, keeping nothing quoted from the input. */
    private[kyo] def of(method: String, part: Part, ex: DecodeException)(using Frame): TeamsDecodeException =
        val (failure, path, position) = Failure.of(ex)
        TeamsDecodeException(method, part, failure, path, position)

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

    object Failure:
        /** The failure kind, path and position of kyo-schema's `ex`, by an exhaustive match over its sealed hierarchy. */
        private[kyo] def of(ex: DecodeException): (Failure, Chunk[String], Maybe[Int]) =
            ex match
                // kyo-schema's path stops at the record; the field it lacks is the schema's own name, not text from the input.
                case e: MissingFieldException          => (MissingField, Chunk.from(e.path) :+ e.fieldName, Absent)
                case e: TypeMismatchException          => (TypeMismatch, Chunk.from(e.path), Absent)
                case e: UnknownVariantException        => (UnknownVariant, Chunk.from(e.path), Absent)
                case e: UnknownFieldException          => (UnknownField, Chunk.from(e.path), Absent)
                case e: NoVariantMatchException        => (NoVariantMatch, Chunk.from(e.path), Absent)
                case e: AmbiguousVariantMatchException => (AmbiguousVariantMatch, Chunk.from(e.path), Absent)
                case e: MissingTagKeyException         => (MissingTagKey, Chunk.from(e.path), Absent)
                case e: ParseException               => (Parse, Chunk.from(e.path), if e.position >= 0 then Present(e.position) else Absent)
                case e: ConstructorRejectedException => (ConstructorRejected, Chunk.from(e.path), Absent)
                case _: TruncatedInputException      => (TruncatedInput, Chunk.empty, Absent)
                case _: TrailingInputException       => (TrailingInput, Chunk.empty, Absent)
                case _: RecordDecodeException        => (RecordDecode, Chunk.empty, Absent)
                case _: LimitExceededException       => (LimitExceeded, Chunk.empty, Absent)
                case _: RangeException               => (Range, Chunk.empty, Absent)
    end Failure

    /** The document that failed to decode. */
    enum Part derives CanEqual:
        /** A 2xx answer of the Bot Connector. */
        case Response

        /** A non-2xx answer's error body. */
        case ErrorBody

        /** The identity platform's or the managed identity endpoint's token answer. */
        case Token

        /** The OpenID metadata document. */
        case Metadata

        /** The signing key set. */
        case Keys

        private[kyo] def show: String =
            this match
                case Response  => "response"
                case ErrorBody => "error body"
                case Token     => "token response"
                case Metadata  => "OpenID metadata"
                case Keys      => "key set"
    end Part
end TeamsDecodeException

/** Teams refused the call for its rate limit, with HTTP 429, from the Bot Connector or from the token endpoint. `retryAfter` is the
  * delay in the `Retry-After` header, `Absent` when there was none it could mean. `code` is the `ErrorResponse` code when the body
  * carried one (`Throttled`), and `operationId` the `X-Correlating-OperationId` header Microsoft support asks for.
  */
final case class TeamsRateLimitException(method: String, retryAfter: Maybe[Duration], code: Maybe[String], operationId: Maybe[String])(
    using Frame
) extends TeamsException(
        s"Teams rate-limited $method" + retryAfter.fold("; Teams sent no retry delay.")(d => s"; retry after ${d.show}.")
    )
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure

/** The identity platform refused the token request with its error object: `code` is its `error`, `errorCodes` its `error_codes`, and
  * `traceId` and `correlationId` the identifiers Microsoft support asks for. Its `error_description` is not kept, since it quotes the
  * request.
  */
final case class TeamsTokenRejectedException(
    code: TeamsTokenRejectedException.Code,
    errorCodes: Chunk[Int],
    traceId: Maybe[String],
    correlationId: Maybe[String]
)(using Frame)
    extends TeamsException(
        s"The identity platform refused the token request: ${code.show}" +
            (if errorCodes.isEmpty then "" else errorCodes.mkString(" (", ", ", ")")) + "."
    )
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure

object TeamsTokenRejectedException:
    /** The `error` of RFC 6749 section 5.2, or another code the identity platform sent. */
    enum Code derives CanEqual:
        case InvalidRequest, InvalidClient, InvalidGrant, UnauthorizedClient, UnsupportedGrantType, InvalidScope

        /** A code outside RFC 6749 section 5.2, at most 64 printable ASCII characters of it. */
        case Other(code: String)

        private[kyo] def show: String =
            this match
                case InvalidRequest       => "invalid_request"
                case InvalidClient        => "invalid_client"
                case InvalidGrant         => "invalid_grant"
                case UnauthorizedClient   => "unauthorized_client"
                case UnsupportedGrantType => "unsupported_grant_type"
                case InvalidScope         => "invalid_scope"
                case Other(code)          => code
    end Code
end TeamsTokenRejectedException

/** A federated credential's assertion computation failed, so no token request was sent. `cause` is what the caller reported when
  * mapping its own failure into this leaf.
  *
  * The message names a `Throwable` cause by its class's simple name only and keeps at most 200 printable ASCII characters of a
  * `String` cause, since the text is the caller's. A `Throwable` cause is the `getCause`.
  */
final case class TeamsCredentialException(cause: String | Throwable)(using Frame)
    extends TeamsException(
        "The federated credential's assertion could not be obtained: " +
            (cause match
                case t: Throwable => t.getClass.getSimpleName
                case s: String    => TeamsException.bounded(s, 200)) + "."
    )
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure:
    // Not passed to `KyoException`'s constructor: it would embed the cause's message into this leaf's `getMessage`, which keeps no
    // unbounded caller text. `null` is `Throwable.getCause`'s contract for "no cause".
    override def getCause(): Throwable =
        cause match
            case t: Throwable => t
            case _: String    => null
end TeamsCredentialException

/** The token endpoint issued a token whose `token_type` is not `Bearer`, the only type the identity platform documents. `tokenType`
  * is at most 32 printable ASCII characters of it.
  */
final case class TeamsUnsupportedTokenTypeException(tokenType: String)(using Frame)
    extends TeamsException(s"The token endpoint issued a $tokenType token, not a bearer token.")
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure

/** The Bot Connector answered a 4xx or 5xx with an `ErrorResponse` body. A leaf exists for each `(status, code)` Microsoft gives its
  * own remedy, on the operations that can receive it; any other answer is a [[kyo.TeamsOtherApiException]]. A named leaf stands for
  * one code, so its `status` and `code` are fixed by the leaf; only the catch-all carries what it received. `description` is the
  * body's `message`, with the access token and the config's credential text replaced by `<redacted>`, and `operationId` the
  * `X-Correlating-OperationId` header Microsoft support asks for.
  */
sealed abstract class TeamsApiException(message: String)(using Frame) extends TeamsException(message):
    def method: String
    def status: HttpStatus
    def code: String
    def description: String
    def operationId: Maybe[String]
end TeamsApiException

object TeamsApiException:
    private[kyo] def describe(method: String, status: Int, code: String, description: String): String =
        s"Teams $method answered $status $code: $description"
end TeamsApiException

/** 400 `BadArgument`: the request is malformed or holds a value the Bot Connector does not accept. */
final case class TeamsBadArgumentException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 400, "BadArgument", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsCreateConversationFailure with TeamsMembersFailure
    with TeamsMemberFailure:
    def status: HttpStatus = HttpStatus(400)
    def code: String       = "BadArgument"
end TeamsBadArgumentException

/** 401 `BotNotRegistered`: the bot has no registration for the app id the token was issued to. */
final case class TeamsBotNotRegisteredException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 401, "BotNotRegistered", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure:
    def status: HttpStatus = HttpStatus(401)
    def code: String       = "BotNotRegistered"
end TeamsBotNotRegisteredException

/** 403 `BotDisabledByAdmin`: the tenant's administrator blocked the bot. */
final case class TeamsBotDisabledByAdminException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 403, "BotDisabledByAdmin", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure:
    def status: HttpStatus = HttpStatus(403)
    def code: String       = "BotDisabledByAdmin"
end TeamsBotDisabledByAdminException

/** 403 `BotNotInConversationRoster`: the bot is not a member of the conversation, as after it was removed from a team. */
final case class TeamsBotNotInConversationException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 403, "BotNotInConversationRoster", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsMembersFailure with TeamsMemberFailure:
    def status: HttpStatus = HttpStatus(403)
    def code: String       = "BotNotInConversationRoster"
end TeamsBotNotInConversationException

/** 403 `ConversationBlockedByUser`: the user blocked the bot in a one-on-one conversation. */
final case class TeamsConversationBlockedByUserException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 403, "ConversationBlockedByUser", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsTypingFailure:
    def status: HttpStatus = HttpStatus(403)
    def code: String       = "ConversationBlockedByUser"
end TeamsConversationBlockedByUserException

/** 403 `ForbiddenOperationException`: the bot is not installed for the user or in the conversation it addresses. */
final case class TeamsNotInstalledException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 403, "ForbiddenOperationException", description))
    with TeamsSendFailure with TeamsCreateConversationFailure:
    def status: HttpStatus = HttpStatus(403)
    def code: String       = "ForbiddenOperationException"
end TeamsNotInstalledException

/** 403 `InvalidBotApiHost`: the service URL is not one the Bot Connector serves the bot's conversations from. */
final case class TeamsInvalidBotApiHostException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 403, "InvalidBotApiHost", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure:
    def status: HttpStatus = HttpStatus(403)
    def code: String       = "InvalidBotApiHost"
end TeamsInvalidBotApiHostException

/** 403 `NotEnoughPermissions`: the bot lacks the permission the operation needs. */
final case class TeamsNotEnoughPermissionsException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 403, "NotEnoughPermissions", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure:
    def status: HttpStatus = HttpStatus(403)
    def code: String       = "NotEnoughPermissions"
end TeamsNotEnoughPermissionsException

/** 403 with the `MessageWritesBlocked` sub-code: the user blocked, muted or uninstalled the bot, so it may not write to them. */
final case class TeamsMessageWritesBlockedException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 403, "MessageWritesBlocked", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsTypingFailure:
    def status: HttpStatus = HttpStatus(403)
    def code: String       = "MessageWritesBlocked"
end TeamsMessageWritesBlockedException

/** 404 `ActivityNotFoundInConversation`: the activity does not exist in the conversation, or no longer does. */
final case class TeamsActivityNotFoundException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 404, "ActivityNotFoundInConversation", description))
    with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure:
    def status: HttpStatus = HttpStatus(404)
    def code: String       = "ActivityNotFoundInConversation"
end TeamsActivityNotFoundException

/** 404 `ConversationNotFound`: the conversation does not exist, or the bot cannot see it. */
final case class TeamsConversationNotFoundException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 404, "ConversationNotFound", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsMembersFailure with TeamsMemberFailure:
    def status: HttpStatus = HttpStatus(404)
    def code: String       = "ConversationNotFound"
end TeamsConversationNotFoundException

/** 412 `PreconditionFailed`: a concurrent operation on the conversation conflicted; Microsoft says to retry. */
final case class TeamsPreconditionFailedException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 412, "PreconditionFailed", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsCreateConversationFailure:
    def status: HttpStatus = HttpStatus(412)
    def code: String       = "PreconditionFailed"
end TeamsPreconditionFailedException

/** 413 `MessageSizeTooBig`: the activity is larger than the Bot Connector accepts. */
final case class TeamsMessageTooLargeException(method: String, description: String, operationId: Maybe[String])(using Frame)
    extends TeamsApiException(TeamsApiException.describe(method, 413, "MessageSizeTooBig", description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsCreateConversationFailure:
    def status: HttpStatus = HttpStatus(413)
    def code: String       = "MessageSizeTooBig"
end TeamsMessageTooLargeException

/** Any `ErrorResponse` with no leaf of its own on the operation that received it. */
final case class TeamsOtherApiException(method: String, status: HttpStatus, code: String, description: String, operationId: Maybe[String])(
    using Frame
) extends TeamsApiException(TeamsApiException.describe(method, status.code, code, description))
    with TeamsSendFailure with TeamsReplyFailure with TeamsEditFailure with TeamsDeleteFailure with TeamsTypingFailure
    with TeamsCreateConversationFailure with TeamsMembersFailure with TeamsMemberFailure with TeamsCustomFailure

/** An inbound request whose `Authorization` token does not verify against the Bot Framework's OpenID signing keys, one leaf per
  * reason, in the order the checks run. The webhook answers each with 401, and [[kyo.TeamsMissingEndorsementException]] with 403.
  */
sealed abstract class TeamsAuthenticationException(message: String)(using Frame) extends TeamsException(message)
    with TeamsWebhookVerifyFailure

/** The request carries no `Authorization` header. */
final case class TeamsMissingAuthorizationException()(using Frame)
    extends TeamsAuthenticationException("Teams request carries no Authorization header.")

/** The `Authorization` header is not a bearer JSON Web Token the module can read. */
final case class TeamsMalformedTokenException(problem: TeamsMalformedTokenException.Problem)(using Frame)
    extends TeamsAuthenticationException(s"Teams request's token is malformed: ${problem.show}.")

object TeamsMalformedTokenException:
    /** What is wrong with the token. */
    enum Problem derives CanEqual:
        /** The header's scheme is not `Bearer` followed by one space. */
        case NotBearer

        /** The token has `length` characters, more than `TeamsConfig.maxTokenLength`. */
        case TooLong(length: Int, max: Int)

        /** The token has `count` dot-separated segments, not three. */
        case Segments(count: Int)

        /** A segment is not canonical unpadded base64url. */
        case Base64(part: Part, failure: kyo.Base64.Failure)

        /** The header or the claims are not the JSON object the module reads. */
        case Json(part: Part, failure: TeamsDecodeException.Failure)

        /** The header names no `kid`. */
        case MissingKeyId

        /** The claims lack `claim`. */
        case MissingClaim(claim: Claim)

        private[kyo] def show: String =
            this match
                case NotBearer             => "the scheme is not Bearer"
                case TooLong(length, max)  => s"it has $length characters, more than $max"
                case Segments(count)       => s"it has $count segments, not 3"
                case Base64(part, failure) => s"the ${part.show} is not canonical base64url ($failure)"
                case Json(part, failure)   => s"the ${part.show} does not decode: ${failure.show}"
                case MissingKeyId          => "the header names no kid"
                case MissingClaim(claim)   => s"the claims lack ${claim.show}"
    end Problem

    /** A segment of the token. */
    enum Part derives CanEqual:
        case Header, Claims, Signature

        private[kyo] def show: String =
            this match
                case Header    => "header"
                case Claims    => "claims"
                case Signature => "signature"
    end Part

    /** A claim the module requires. */
    enum Claim derives CanEqual:
        case Issuer, Audience, Expiry, ServiceUrl

        private[kyo] def show: String =
            this match
                case Issuer     => "iss"
                case Audience   => "aud"
                case Expiry     => "exp"
                case ServiceUrl => "serviceUrl"
    end Claim
end TeamsMalformedTokenException

/** The token's `alg` is not `RS256`, the one algorithm the module verifies. `algorithm` is at most 16 printable ASCII characters of it. */
final case class TeamsUnsupportedAlgorithmException(algorithm: String)(using Frame)
    extends TeamsAuthenticationException(s"Teams request's token is signed with $algorithm, not RS256.")

/** The OpenID metadata no longer lists `RS256` among its `id_token_signing_alg_values_supported`, so no token can be verified with the
  * algorithm the module pins. `algorithms` are the listed values, each at most 16 printable ASCII characters.
  */
final case class TeamsMetadataAlgorithmException(algorithms: Chunk[String])(using Frame)
    extends TeamsAuthenticationException(s"The Bot Framework's OpenID metadata lists ${algorithms.mkString(", ")}, not RS256.")

/** The token's `iss`, or the OpenID metadata's `issuer`, is not `TeamsConfig.issuer`. `issuer` is at most 256 printable ASCII
  * characters of it.
  */
final case class TeamsWrongIssuerException(issuer: String)(using Frame)
    extends TeamsAuthenticationException(s"Teams token issuer $issuer is not the configured issuer.")

/** The token's `aud` does not contain the bot's app id. `audience` holds at most 8 values, each at most 128 printable ASCII
  * characters.
  */
final case class TeamsWrongAudienceException(audience: Chunk[String])(using Frame)
    extends TeamsAuthenticationException(s"Teams token audience ${audience.mkString(", ")} does not name the bot.")

/** The token expired at `expiredAt`, more than `skew` before `now`. */
final case class TeamsTokenExpiredException(expiredAt: Instant, now: Instant, skew: Duration)(using Frame)
    extends TeamsAuthenticationException(s"Teams token expired at ${expiredAt.show}; it is ${now.show}, allowing ${skew.show} of skew.")

/** The token is valid only from `notBefore`, more than `skew` after `now`. */
final case class TeamsTokenNotYetValidException(notBefore: Instant, now: Instant, skew: Duration)(using Frame)
    extends TeamsAuthenticationException(
        s"Teams token is valid from ${notBefore.show}; it is ${now.show}, allowing ${skew.show} of skew."
    )

/** No signing key has the token's `kid`, after the refetch the key cache allows. `keyId` is at most 128 printable ASCII characters. */
final case class TeamsUnknownKeyException(keyId: String)(using Frame)
    extends TeamsAuthenticationException(s"Teams token names signing key $keyId, which the key set does not hold.")

/** The signing key `keyId` is not an RSA key kyo-crypto accepts for RS256. */
final case class TeamsInvalidKeyException(keyId: String, problem: Rsa.KeyFailure)(using Frame)
    extends TeamsAuthenticationException(s"Teams signing key $keyId is not usable: $problem.")

/** The token's signature does not verify under its signing key. */
final case class TeamsSignatureMismatchException()(using Frame)
    extends TeamsAuthenticationException("Teams token's signature does not verify.")

/** The signing key does not endorse the Activity's `channelId`. The webhook answers it with 403. `channelId` is at most 64 printable
  * ASCII characters.
  */
final case class TeamsMissingEndorsementException(channelId: String)(using Frame)
    extends TeamsAuthenticationException(s"Teams signing key does not endorse channel $channelId.")

/** The token's `serviceUrl` claim is not the Activity's `serviceUrl`. */
final case class TeamsServiceUrlMismatchException()(using Frame)
    extends TeamsAuthenticationException("Teams token's serviceUrl claim is not the Activity's serviceUrl.")

/** The Activity's `channelId` is not `msteams`, the one channel the module accepts. `channelId` is at most 64 printable ASCII
  * characters.
  */
final case class TeamsUnsupportedChannelException(channelId: String)(using Frame)
    extends TeamsAuthenticationException(s"Teams request comes from channel $channelId, not msteams.")

/** An inbound body is not an Activity the module reads. `failure` is which of kyo-schema's decode failures it was, `path` where in the
  * body, and `position` the offset when kyo-schema gave one. Nothing is copied from the body. The webhook answers it with 400.
  */
final case class TeamsWebhookDecodeException(failure: TeamsDecodeException.Failure, path: Chunk[String], position: Maybe[Int])(using
    Frame
) extends TeamsException(
        s"Teams request body is not an Activity: ${failure.show}" +
            (if path.isEmpty then "" else s" at ${path.mkString(".")}") +
            position.fold("")(p => s", position $p") + "."
    )
    with TeamsWebhookVerifyFailure with TeamsWebhookDecodeFailure

object TeamsWebhookDecodeException:
    private[kyo] def of(ex: DecodeException)(using Frame): TeamsWebhookDecodeException =
        val (failure, path, position) = TeamsDecodeException.Failure.of(ex)
        TeamsWebhookDecodeException(failure, path, position)
end TeamsWebhookDecodeException

/** A [[kyo.TeamsConfig]] setting with a value it cannot use, refused by `TeamsConfig.init`. */
final case class TeamsInvalidConfigException(problem: TeamsInvalidConfigException.Problem)(using Frame)
    extends TeamsException(s"TeamsConfig.${problem.show}.")

object TeamsInvalidConfigException:
    import TeamsException.UrlProblem

    /** The setting and what is wrong with it. */
    enum Problem derives CanEqual:
        case LoginUrl(problem: UrlProblem)
        case OpenIdMetadataUrl(problem: UrlProblem)

        /** The managed identity's token endpoint. */
        case IdentityEndpoint(problem: UrlProblem)

        /** The entry of `serviceHosts` at `index`. */
        case ServiceHost(index: Int, problem: UrlProblem)

        /** `serviceHosts` is empty, so no call could be sent. */
        case NoServiceHosts

        /** `scope` is empty (`Absent`), or its character at the position is outside an OAuth scope token. */
        case Scope(position: Maybe[Int])

        /** `issuer` is empty. */
        case Issuer
        case ClockSkew(value: Duration)
        case TokenRefreshMargin(value: Duration)
        case KeysMaxAge(value: Duration)
        case KeysMinRefresh(value: Duration, keysMaxAge: Duration)
        case MaxTokenLength(value: Int)
        case RequestTimeout(value: Duration)
        case ConnectTimeout(value: Duration)
        case RetryMaxDelay(value: Duration)

        private[kyo] def show: String =
            this match
                case LoginUrl(problem)           => s"loginUrl ${problem.show}"
                case OpenIdMetadataUrl(problem)  => s"openIdMetadataUrl ${problem.show}"
                case IdentityEndpoint(problem)   => s"credential's identity endpoint ${problem.show}"
                case ServiceHost(index, problem) => s"serviceHosts($index) ${problem.show}"
                case NoServiceHosts              => "serviceHosts must list at least one origin"
                case Scope(position)             =>
                    position.fold("scope must not be empty")(at =>
                        s"scope's character at position $at is outside an OAuth scope token (RFC 6749 section 3.3)"
                    )
                case Issuer                        => "issuer must not be empty"
                case ClockSkew(value)              => s"clockSkew must be finite; got ${value.show}"
                case TokenRefreshMargin(value)     => s"tokenRefreshMargin must be finite; got ${value.show}"
                case KeysMaxAge(value)             => s"keysMaxAge must be positive and finite; got ${value.show}"
                case KeysMinRefresh(value, maxAge) => s"keysMinRefresh must be at most keysMaxAge (${maxAge.show}); got ${value.show}"
                case MaxTokenLength(value)         => s"maxTokenLength must be positive; got $value"
                case RequestTimeout(value)         => s"requestTimeout must be positive and finite; got ${value.show}"
                case ConnectTimeout(value)         => s"connectTimeout must be positive and finite; got ${value.show}"
                case RetryMaxDelay(value)          => s"retryMaxDelay must be positive and finite; got ${value.show}"
    end Problem
end TeamsInvalidConfigException

/** Text that cannot be the credential `token` names, refused by its `init`. */
final case class TeamsInvalidTokenException(token: TeamsInvalidTokenException.Token, problem: TeamsInvalidTokenException.Problem)(using
    Frame
) extends TeamsException(s"${token.show} is not usable: ${problem.show}.")

object TeamsInvalidTokenException:
    /** Which credential was refused. */
    enum Token derives CanEqual:
        /** [[kyo.Teams.ClientSecret]], the app password. */
        case ClientSecret

        /** [[kyo.Teams.ClientAssertion]], a signed assertion standing for the app. */
        case ClientAssertion

        /** [[kyo.Teams.IdentityHeader]], the managed identity endpoint's header value. */
        case IdentityHeader

        private[kyo] def show: String =
            this match
                case ClientSecret    => "Teams.ClientSecret"
                case ClientAssertion => "Teams.ClientAssertion"
                case IdentityHeader  => "Teams.IdentityHeader"
    end Token

    /** What is wrong with the text. A position, never the character, since the character is part of the secret. */
    enum Problem derives CanEqual:
        case Empty
        case TooLong(length: Int, max: Int)

        /** The character at `position` is outside what the credential may hold. */
        case Character(position: Int)

        private[kyo] def show: String =
            this match
                case Empty                => "it is empty"
                case TooLong(length, max) => s"it has $length characters, more than $max"
                case Character(position)  => s"the character at position $position is not one it may hold"
    end Problem
end TeamsInvalidTokenException

/** Text that cannot be the id `id` names, refused by its `init`. */
final case class TeamsInvalidIdException(id: TeamsInvalidIdException.Id, problem: TeamsInvalidIdException.Problem)(using Frame)
    extends TeamsException(s"${id.show} is not usable: ${problem.show}.")

object TeamsInvalidIdException:
    /** Which id was refused. */
    enum Id derives CanEqual:
        case App, Tenant, Conversation, Activity, User, Channel, Team, AadObject

        private[kyo] def show: String =
            this match
                case App          => "Teams.AppId"
                case Tenant       => "Teams.TenantId"
                case Conversation => "Teams.ConversationId"
                case Activity     => "Teams.ActivityId"
                case User         => "Teams.UserId"
                case Channel      => "Teams.ChannelId"
                case Team         => "Teams.TeamId"
                case AadObject    => "Teams.AadObjectId"
    end Id

    /** What is wrong with the text. */
    enum Problem derives CanEqual:
        case Empty

        private[kyo] def show: String =
            this match
                case Empty => "it is empty"
    end Problem
end TeamsInvalidIdException

/** Text that is not a service URL the module can send to, refused by `Teams.ServiceUrl.init`. */
final case class TeamsInvalidServiceUrlException(problem: TeamsException.UrlProblem)(using Frame)
    extends TeamsException(s"Teams.ServiceUrl is not usable: it ${problem.show}.")

/** Segments that cannot form a [[kyo.Teams.Path]], refused by its `init`. */
final case class TeamsInvalidPathException(problem: TeamsInvalidPathException.Problem)(using Frame)
    extends TeamsException(s"Teams.Path is not usable: ${problem.show}.")

object TeamsInvalidPathException:
    /** What is wrong with the segments. */
    enum Problem derives CanEqual:
        /** No segment was given. */
        case Empty

        /** The segment at `index` is empty, which would join two slashes. */
        case EmptySegment(index: Int)

        /** The segment at `index` is `.` or `..`, which would move the request off the service URL's path. */
        case DotSegment(index: Int)

        private[kyo] def show: String =
            this match
                case Empty               => "it has no segment"
                case EmptySegment(index) => s"segment $index is empty"
                case DotSegment(index)   => s"segment $index is . or .."
    end Problem
end TeamsInvalidPathException

/** A [[kyo.TeamsWebhookConfig]] path no request can reach, refused by `TeamsWebhookConfig.init`. */
final case class TeamsInvalidWebhookConfigException(problem: TeamsInvalidWebhookConfigException.Problem)(using Frame)
    extends TeamsException(s"TeamsWebhookConfig is not usable: ${problem.show}.")

object TeamsInvalidWebhookConfigException:
    /** What is wrong with the config. */
    enum Problem derives CanEqual:
        /** The path's character at `position` is `?`, `#`, a space, a control character or outside ASCII. */
        case PathCharacter(position: Int)

        private[kyo] def show: String =
            this match
                case PathCharacter(position) => s"the path's character at position $position cannot appear in a request path"
    end Problem
end TeamsInvalidWebhookConfigException

/** An Adaptive Card value Teams would refuse, refused by its `init`. */
final case class TeamsInvalidCardException(problem: TeamsInvalidCardException.Problem)(using Frame)
    extends TeamsException(s"Teams.Card is not usable: ${problem.show}.")

object TeamsInvalidCardException:
    /** What is wrong with the card. */
    enum Problem derives CanEqual:
        /** The version is not `major.minor` with each part 0 to 99. */
        case Version

        /** A refresh lists `count` user ids, more than the 60 Teams accepts. */
        case RefreshUsers(count: Int, max: Int)

        private[kyo] def show: String =
            this match
                case Version                  => "the version is not major.minor"
                case RefreshUsers(count, max) => s"a refresh lists $count user ids, more than $max"
    end Problem
end TeamsInvalidCardException

/** A page request Teams would refuse, refused by `Teams.Member.Page.init`. */
final case class TeamsInvalidPageException(problem: TeamsInvalidPageException.Problem)(using Frame)
    extends TeamsException(s"Teams.Member.Page is not usable: ${problem.show}.")

object TeamsInvalidPageException:
    /** What is wrong with the page request. */
    enum Problem derives CanEqual:
        /** The size is outside `min` to `max`, the bounds Teams documents. */
        case Size(value: Int, min: Int, max: Int)

        private[kyo] def show: String =
            this match
                case Size(value, min, max) => s"the size must be from $min to $max; got $value"
    end Problem
end TeamsInvalidPageException
