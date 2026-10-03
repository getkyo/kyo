package kyo

/** Typed error hierarchy for kyo-slack: a sealed base over `KyoException` with flat leaves, one per
  * failure a caller can tell apart.
  *
  * The leaves fall into four groups:
  *   - the transport and the response: [[kyo.SlackTransportException]] (the HTTP or WebSocket call
  *     failed), [[kyo.SlackRefusedUrlException]] (a url Slack supplied that the module will not send
  *     to), [[kyo.SlackUnexpectedStatusException]] (a non-2xx answer with no Slack body),
  *     [[kyo.SlackDecodeException]] (a body that does not decode), [[kyo.SlackRateLimitException]];
  *   - Slack's own `{"ok":false}` answers: the category [[kyo.SlackApiException]], with a leaf per
  *     error code a caller can act on and [[kyo.SlackOtherApiException]] carrying any other code;
  *   - the Socket Mode connection: [[kyo.SlackLinkDisabledException]];
  *   - an invalid value, the failure of its `init` and never on an `Abort` row:
  *     [[kyo.SlackInvalidRawBlockException]], [[kyo.SlackInvalidConfigException]],
  *     [[kyo.SlackInvalidTokenException]] and [[kyo.SlackInvalidMethodException]].
  *
  * Each public operation fails with its own sealed trait, such as [[kyo.SlackChatPostMessageFailure]],
  * and a leaf extends the trait of every operation that can produce it, so a row names exactly the
  * leaves a caller can meet. No row names `SlackException` itself.
  *
  * Every leaf builds its message from its own typed fields. No leaf holds a kyo-http failure: every
  * kyo-http failure that names a request holds its url, and a `response_url`'s path is its credential.
  * The transport leaf keeps kyo-net's failure behind a connection that could not be made, which names
  * a host and port and never a path, as its `getCause`, out of the leaf's equality. No message renders
  * a token, a `response_url`, or another exception's message.
  *
  * @see
  *   [[kyo.SlackApiException]] Slack's `{"ok":false}` answers
  * @see
  *   [[kyo.SlackTransportException]] Transport failures
  * @see
  *   [[kyo.SlackDecodeException]] Response bodies that do not decode
  */
sealed abstract class SlackException(message: String)(using Frame) extends KyoException(message)

object SlackException:
    given CanEqual[SlackException, SlackException] = CanEqual.derived

    /** The leaves every operation can fail with, whatever it calls: the transport, the status, the
      * decode, the rate limit, and a Slack code without a leaf of its own.
      */
    private[kyo] type Common =
        SlackTransportException | SlackUnexpectedStatusException | SlackDecodeException | SlackRateLimitException |
            SlackOtherApiException

    /** What opening a Socket Mode connection can fail with, on `init` and on every rotation of the loop. */
    private[kyo] type Connect = SlackInitFailure & SlackReceiveFailure

    /** The leaves a POST to a `response_url` can fail with: `Common` and the refusal of a url that is not one the module
      * sends to.
      */
    private[kyo] type ResponseUrl = Common | SlackRefusedUrlException
end SlackException

// --- Operation failures ---

/** What `Slack.init` can fail with: opening the Socket Mode connection. */
sealed trait SlackInitFailure extends SlackException

/** What `Slack.receive` can fail with: the receive loop and the connections it opens, on rotation and for a client
  * built without one.
  */
sealed trait SlackReceiveFailure extends SlackException

/** What `Slack.authTest` can fail with. */
sealed trait SlackAuthTestFailure extends SlackException

/** What `Slack.chatPostMessage` can fail with. */
sealed trait SlackChatPostMessageFailure extends SlackException

/** What `Slack.chatPostEphemeral` can fail with. */
sealed trait SlackChatPostEphemeralFailure extends SlackException

/** What `Slack.chatUpdate` can fail with. */
sealed trait SlackChatUpdateFailure extends SlackException

/** What `Slack.viewsOpen` can fail with. */
sealed trait SlackViewsOpenFailure extends SlackException

/** What `Slack.viewsUpdate` can fail with. */
sealed trait SlackViewsUpdateFailure extends SlackException

/** What `Slack.viewsPublish` can fail with. */
sealed trait SlackViewsPublishFailure extends SlackException

/** What `Slack.custom` can fail with: only the codes Slack gives one meaning across its whole Web API. */
sealed trait SlackCustomFailure extends SlackException

/** What `Slack.respondEphemeral` can fail with: the POST to a `response_url` and Slack's answer to it. */
sealed trait SlackRespondEphemeralFailure extends SlackException

/** What `Slack.respondInChannel` can fail with: the POST to a `response_url` and Slack's answer to it. */
sealed trait SlackRespondInChannelFailure extends SlackException

/** What `Slack.replaceOriginal` can fail with: the POST to a `response_url` and Slack's answer to it. */
sealed trait SlackReplaceOriginalFailure extends SlackException

/** What `Slack.deleteOriginal` can fail with: the POST to a `response_url` and Slack's answer to it. */
sealed trait SlackDeleteOriginalFailure extends SlackException

// --- Transport and response ---

/** A Slack call failed at the transport, before Slack's answer could be read.
  *
  * `method` is the Web API method, `apps.connections.open`, `socket-connect` for opening the Socket Mode
  * WebSocket, or `response_url`. `host` and `port` are those the call went to, and `timeout` is the bound that
  * elapsed for a `ConnectTimeout` or a `Timeout`. The leaf holds no kyo-http failure, since those name the
  * request's url, and a `response_url`'s path is its credential. For a connection that could not be made
  * (`Connect`, `Dns`, `Tls`, `ConnectTimeout`), kyo-net's failure, which names a host and port and never a
  * path, is the typed `cause`, kept out of the leaf's equality.
  */
final case class SlackTransportException(
    method: String,
    kind: SlackTransportException.Kind,
    host: String,
    port: Int,
    timeout: Maybe[Duration]
)(val cause: Maybe[kyo.net.NetException] = Absent)(using Frame)
    extends SlackException(
        s"Slack $method failed at the transport: ${kind.show} ($host:$port" + timeout.fold("")(d => s", after ${d.show}") + ")."
    )
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure
    with SlackRespondEphemeralFailure with SlackRespondInChannelFailure with SlackReplaceOriginalFailure
    with SlackDeleteOriginalFailure:
    // Not passed to `KyoException`'s constructor: it would embed the cause's message, formatted per
    // environment, into this leaf's `getMessage`, which states only what failed.
    override def getCause(): Throwable = cause.fold(null)(identity)
end SlackTransportException

object SlackTransportException:
    /** What failed, as kyo-http reported it, keeping only fields that hold no url. */
    enum Kind derives CanEqual:
        case Connect, Dns, Tls, ConnectTimeout, Timeout, Protocol, ConnectionClosed, WebSocketHandshake

        /** Every connection of the pool to the host was in use. */
        case PoolExhausted(maxConnections: Int)

        /** The answer's body of `bodySize` exceeded the config's `maxResponseLength`, `maxSize`. */
        case PayloadTooLarge(bodySize: ByteSize, maxSize: ByteSize)

        private[kyo] def show: String =
            this match
                case Connect                            => "connection refused or reset"
                case Dns                                => "host not resolved"
                case Tls                                => "TLS handshake failed"
                case ConnectTimeout                     => "the connection did not open in time"
                case Timeout                            => "no answer in time"
                case Protocol                           => "the answer broke the HTTP protocol"
                case ConnectionClosed                   => "the connection closed before the answer was read"
                case WebSocketHandshake                 => "the WebSocket handshake was refused"
                case PoolExhausted(max)                 => s"all $max connections were in use"
                case PayloadTooLarge(bodySize, maxSize) => s"the answer of ${bodySize.show} exceeds ${maxSize.show}"
    end Kind
end SlackTransportException

/** A url Slack supplied that the module will not send to: a `response_url`, or the Socket Mode url
  * `apps.connections.open` answered, that is not an absolute url of its scheme (http or https for a
  * `response_url`, wss for the socket) on a host in printable ASCII. kyo-http resolves a url with no scheme
  * against a base url and sends a unix-socket url to a local socket, so either would reach a place the url
  * does not name, and a `ws` socket url would send its ticket in clear. `method` is `response_url` or
  * `socket-connect`. The leaf copies nothing of the url, which is the credential.
  */
final case class SlackRefusedUrlException(method: String)(using Frame)
    extends SlackException(s"Slack $method: the url Slack supplied is not one the module sends to, so nothing was sent.")
    with SlackInitFailure with SlackReceiveFailure
    with SlackRespondEphemeralFailure with SlackRespondInChannelFailure with SlackReplaceOriginalFailure
    with SlackDeleteOriginalFailure

/** Slack answered a non-2xx status with a body that is not a Slack response, such as an HTML error
  * page from its edge. A non-2xx answer that does carry `{"ok":false,...}` is a [[kyo.SlackApiException]].
  */
final case class SlackUnexpectedStatusException(method: String, status: HttpStatus)(using Frame)
    extends SlackException(s"Slack $method answered HTTP ${status.code} without a Slack response body.")
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure
    with SlackRespondEphemeralFailure with SlackRespondInChannelFailure with SlackReplaceOriginalFailure
    with SlackDeleteOriginalFailure

/** A Slack response body did not decode. `part` says whether the `ok`/`error` envelope or the method's
  * result failed.
  *
  * The leaf locates the failure and holds nothing of the body: `failure` is kyo-schema's decode leaf,
  * `path` the field path it reports (empty when it reports none; for a missing field, ending in that
  * field's name, which comes from the module's own schema), and `position` the offset a parse failure
  * reports. kyo-schema's exception itself is neither a field nor the cause: its fields and
  * message quote bytes of Slack's answer, which can hold a credential (the Socket Mode url's ticket).
  *
  * An answer whose types are right and whose values are not is this failure too, located at the field: an `auth.test` answer
  * with an empty id or a url that is not an absolute http or https url (`ConstructorRejected` at that field), and an
  * `apps.connections.open` answer without its url (`MissingField` at `url`).
  */
final case class SlackDecodeException(
    method: String,
    part: SlackDecodeException.Part,
    failure: SlackDecodeException.Failure,
    path: Chunk[String],
    position: Maybe[Int]
)(using Frame)
    extends SlackException(
        s"Slack $method response ${part.show} did not decode: ${failure.show}" +
            (if path.isEmpty then "" else s" at ${path.mkString(".")}") +
            position.fold("")(p => s", position $p") + "."
    )
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure
    with SlackRespondEphemeralFailure with SlackRespondInChannelFailure with SlackReplaceOriginalFailure
    with SlackDeleteOriginalFailure

object SlackDecodeException:
    /** kyo-schema's decode leaf that rejected the body, by kind. */
    enum Failure derives CanEqual:
        case MissingField, TypeMismatch, UnknownVariant, UnknownField, NoVariantMatch, AmbiguousVariantMatch, MissingTagKey, Parse,
            ConstructorRejected, TruncatedInput, TrailingInput, RecordDecode, LimitExceeded, Range

        private[kyo] def show: String =
            this match
                case Parse                 => "unparseable input"
                case MissingField          => "missing field"
                case TypeMismatch          => "type mismatch"
                case UnknownVariant        => "unknown variant"
                case UnknownField          => "unknown field"
                case NoVariantMatch        => "no variant matched"
                case AmbiguousVariantMatch => "ambiguous variant"
                case MissingTagKey         => "missing tag key"
                case ConstructorRejected   => "value rejected"
                case TruncatedInput        => "truncated input"
                case TrailingInput         => "trailing input"
                case RecordDecode          => "record decode failure"
                case LimitExceeded         => "limit exceeded"
                case Range                 => "number out of range"
    end Failure

    /** The leaf for kyo-schema's `e`, keeping its kind, path and position and none of its text. */
    private[kyo] def apply(method: String, part: Part, e: DecodeException)(using Frame): SlackDecodeException =
        val (failure, path, position) = located(e)
        SlackDecodeException(method, part, failure, path, position)

    /** kyo-schema's `e` as its kind, the field path it reports, and the offset a parse failure reports, with none of its text. The
      * match has no wildcard, so a leaf kyo-schema adds fails to compile here instead of arriving unnamed.
      */
    private[kyo] def located(e: DecodeException): (Failure, Chunk[String], Maybe[Int]) =
        e match
            case p: ParseException =>
                (Failure.Parse, Chunk.from(p.path), if p.position >= 0 then Present(p.position) else Absent)
            case m: MissingFieldException          => (Failure.MissingField, Chunk.from(m.path :+ m.fieldName), Absent)
            case t: TypeMismatchException          => (Failure.TypeMismatch, Chunk.from(t.path), Absent)
            case u: UnknownVariantException        => (Failure.UnknownVariant, Chunk.from(u.path), Absent)
            case u: UnknownFieldException          => (Failure.UnknownField, Chunk.from(u.path), Absent)
            case n: NoVariantMatchException        => (Failure.NoVariantMatch, Chunk.from(n.path), Absent)
            case a: AmbiguousVariantMatchException => (Failure.AmbiguousVariantMatch, Chunk.from(a.path), Absent)
            case m: MissingTagKeyException         => (Failure.MissingTagKey, Chunk.from(m.path), Absent)
            case c: ConstructorRejectedException   => (Failure.ConstructorRejected, Chunk.from(c.path), Absent)
            case _: TruncatedInputException        => (Failure.TruncatedInput, Chunk.empty, Absent)
            case _: TrailingInputException         => (Failure.TrailingInput, Chunk.empty, Absent)
            case _: RecordDecodeException          => (Failure.RecordDecode, Chunk.empty, Absent)
            case _: LimitExceededException         => (Failure.LimitExceeded, Chunk.empty, Absent)
            case _: RangeException                 => (Failure.Range, Chunk.empty, Absent)
        end match
    end located

    /** The part of a response that failed to decode. */
    enum Part derives CanEqual:
        /** The status envelope: `ok`, and on failure `error` and its companions. */
        case Envelope

        /** The method's own result fields. */
        case Payload

        private[kyo] def show: String =
            this match
                case Envelope => "envelope"
                case Payload  => "payload"
    end Part
end SlackDecodeException

/** Slack rate-limited the call, by HTTP 429 or by the code `ratelimited` or `rate_limited`.
  * `retryAfter` is the delay Slack sent in `Retry-After`, or `Absent` when it sent none it could mean.
  */
final case class SlackRateLimitException(method: String, retryAfter: Maybe[Duration])(using Frame)
    extends SlackException(
        s"Slack rate-limited $method" + retryAfter.fold("; Slack sent no usable Retry-After.")(d => s"; retry after ${d.show}.")
    )
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure
    with SlackRespondEphemeralFailure with SlackRespondInChannelFailure with SlackReplaceOriginalFailure
    with SlackDeleteOriginalFailure

// --- Slack's {"ok":false} answers ---

/** Slack answered `{"ok":false,"error":code}`. A leaf exists for each code a caller can act on (a
  * credential, a scope, the arguments passed), on the operations whose Slack documentation lists that
  * code. Any other code is a [[kyo.SlackOtherApiException]]. `messages` is Slack's
  * `response_metadata.messages`, empty when Slack sent none.
  */
sealed abstract class SlackApiException(message: String)(using Frame) extends SlackException(message):
    def method: String
    def code: String
    def messages: Chunk[String]
end SlackApiException

object SlackApiException:
    private[kyo] def describe(method: String, code: String, messages: Chunk[String]): String =
        s"Slack $method answered $code" + (if messages.isEmpty then "." else messages.mkString(": ", "; ", "."))
end SlackApiException

/** `invalid_auth`: the token is not valid. */
final case class SlackInvalidAuthException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "invalid_auth", messages))
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure:
    def code: String = "invalid_auth"
end SlackInvalidAuthException

/** `not_authed`: no token reached Slack, as with an empty token. */
final case class SlackNotAuthedException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "not_authed", messages))
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure:
    def code: String = "not_authed"
end SlackNotAuthedException

/** `token_revoked`: the token was revoked, or its app removed. */
final case class SlackTokenRevokedException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "token_revoked", messages))
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure:
    def code: String = "token_revoked"
end SlackTokenRevokedException

/** `token_expired`: the token has expired. */
final case class SlackTokenExpiredException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "token_expired", messages))
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure:
    def code: String = "token_expired"
end SlackTokenExpiredException

/** `account_inactive`: the token belongs to a deleted user or workspace. */
final case class SlackAccountInactiveException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "account_inactive", messages))
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure:
    def code: String = "account_inactive"
end SlackAccountInactiveException

/** `not_allowed_token_type`: the method does not accept this kind of token. */
final case class SlackNotAllowedTokenTypeException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "not_allowed_token_type", messages))
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure:
    def code: String = "not_allowed_token_type"
end SlackNotAllowedTokenTypeException

/** `missing_scope`: the token lacks a scope the method needs. `needed` and `provided` are Slack's
  * scope lists, empty when Slack sent none.
  */
final case class SlackMissingScopeException(method: String, needed: Chunk[String], provided: Chunk[String], messages: Chunk[String])(
    using Frame
) extends SlackApiException(
        SlackApiException.describe(method, "missing_scope", messages) +
            s" Needed: ${needed.mkString(",")}; the token has: ${provided.mkString(",")}."
    )
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure:
    def code: String = "missing_scope"
end SlackMissingScopeException

/** `invalid_arguments`: Slack rejected the arguments; `messages` usually says which. */
final case class SlackInvalidArgumentsException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "invalid_arguments", messages))
    with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure:
    def code: String = "invalid_arguments"
end SlackInvalidArgumentsException

/** `channel_not_found`: the channel passed does not exist or is not visible to the bot. */
final case class SlackChannelNotFoundException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "channel_not_found", messages))
    with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure:
    def code: String = "channel_not_found"
end SlackChannelNotFoundException

/** `not_in_channel`: the bot is not a member of the channel passed. */
final case class SlackNotInChannelException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "not_in_channel", messages))
    with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure:
    def code: String = "not_in_channel"
end SlackNotInChannelException

/** `is_archived`: the channel passed is archived. */
final case class SlackIsArchivedException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "is_archived", messages))
    with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure:
    def code: String = "is_archived"
end SlackIsArchivedException

/** `user_not_in_channel`: the user an ephemeral message targets is not in the channel. */
final case class SlackUserNotInChannelException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "user_not_in_channel", messages))
    with SlackChatPostEphemeralFailure:
    def code: String = "user_not_in_channel"
end SlackUserNotInChannelException

/** `no_text`: the message has no text. */
final case class SlackNoTextException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "no_text", messages))
    with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure:
    def code: String = "no_text"
end SlackNoTextException

/** `msg_too_long`: the message text is too long. */
final case class SlackMsgTooLongException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "msg_too_long", messages))
    with SlackChatPostEphemeralFailure with SlackChatUpdateFailure:
    def code: String = "msg_too_long"
end SlackMsgTooLongException

/** `msg_blocks_too_long`: the message's blocks are too long. */
final case class SlackMsgBlocksTooLongException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "msg_blocks_too_long", messages))
    with SlackChatPostMessageFailure:
    def code: String = "msg_blocks_too_long"
end SlackMsgBlocksTooLongException

/** `invalid_blocks`: Slack rejected the blocks. */
final case class SlackInvalidBlocksException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "invalid_blocks", messages))
    with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure:
    def code: String = "invalid_blocks"
end SlackInvalidBlocksException

/** `invalid_blocks_format`: the blocks do not follow Block Kit's shape. */
final case class SlackInvalidBlocksFormatException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "invalid_blocks_format", messages))
    with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure:
    def code: String = "invalid_blocks_format"
end SlackInvalidBlocksFormatException

/** `cannot_reply_to_message`: the message `threadTs` names cannot have thread replies. */
final case class SlackCannotReplyToMessageException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "cannot_reply_to_message", messages))
    with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure:
    def code: String = "cannot_reply_to_message"
end SlackCannotReplyToMessageException

/** `message_not_found`: no message exists at the timestamp passed. */
final case class SlackMessageNotFoundException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "message_not_found", messages))
    with SlackChatUpdateFailure:
    def code: String = "message_not_found"
end SlackMessageNotFoundException

/** `cant_update_message`: the bot may not update the message at the timestamp passed. */
final case class SlackCantUpdateMessageException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "cant_update_message", messages))
    with SlackChatUpdateFailure:
    def code: String = "cant_update_message"
end SlackCantUpdateMessageException

/** `expired_trigger_id`: the trigger id has expired; Slack allows 3 seconds after the interaction. */
final case class SlackExpiredTriggerIdException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "expired_trigger_id", messages))
    with SlackViewsOpenFailure:
    def code: String = "expired_trigger_id"
end SlackExpiredTriggerIdException

/** `exchanged_trigger_id`: the trigger id was already used. */
final case class SlackExchangedTriggerIdException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "exchanged_trigger_id", messages))
    with SlackViewsOpenFailure:
    def code: String = "exchanged_trigger_id"
end SlackExchangedTriggerIdException

/** `invalid_trigger_id`: the trigger id is malformed. */
final case class SlackInvalidTriggerIdException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "invalid_trigger_id", messages))
    with SlackViewsOpenFailure:
    def code: String = "invalid_trigger_id"
end SlackInvalidTriggerIdException

/** `view_too_large`: the view exceeds Slack's size limit. */
final case class SlackViewTooLargeException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "view_too_large", messages))
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure:
    def code: String = "view_too_large"
end SlackViewTooLargeException

/** `not_found`: the view id passed does not exist. */
final case class SlackNotFoundException(method: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, "not_found", messages))
    with SlackViewsUpdateFailure:
    def code: String = "not_found"
end SlackNotFoundException

/** Any `{"ok":false}` code with no leaf of its own on the operation that received it. */
final case class SlackOtherApiException(method: String, code: String, messages: Chunk[String])(using Frame)
    extends SlackApiException(SlackApiException.describe(method, code, messages))
    with SlackInitFailure with SlackReceiveFailure
    with SlackAuthTestFailure with SlackChatPostMessageFailure with SlackChatPostEphemeralFailure with SlackChatUpdateFailure
    with SlackViewsOpenFailure with SlackViewsUpdateFailure with SlackViewsPublishFailure with SlackCustomFailure
    with SlackRespondEphemeralFailure with SlackRespondInChannelFailure with SlackReplaceOriginalFailure
    with SlackDeleteOriginalFailure

// --- Socket Mode connection ---

/** Slack disabled the Socket Mode link (`disconnect` with reason `link_disabled`). Terminal under
  * every reconnect policy.
  */
final case class SlackLinkDisabledException()(using Frame)
    extends SlackException("Slack disabled the Socket Mode link (disconnect reason link_disabled).")
    with SlackReceiveFailure

// --- Invalid values (the failure of an init, never on a row) ---

/** The text given to `SlackBlock.Raw.init` is not an RFC 8259 JSON object.
  *
  * The text is read by kyo-schema-json, and the leaf holds its reading, never the text: a caller's block can
  * carry what its user typed.
  */
final case class SlackInvalidRawBlockException(problem: SlackInvalidRawBlockException.Problem)(using Frame)
    extends SlackException(s"SlackBlock.Raw is not a JSON object: ${problem.show}.")

object SlackInvalidRawBlockException:
    /** Why the text is not a JSON object. */
    enum Problem derives CanEqual:
        /** The text is not JSON: `failure` is kyo-schema's decode leaf by kind (`Parse`, or `LimitExceeded` for nesting past
          * its depth bound), and `position` the character offset where its reader stopped, when it reports one.
          */
        case NotJson(failure: SlackDecodeException.Failure, position: Maybe[Int])

        /** The text is JSON, but its top-level value is not an object, and a block is one. */
        case NotAnObject

        private[kyo] def show: String =
            this match
                case NotJson(failure, position) => failure.show + position.fold("")(p => s" at position $p")
                case NotAnObject                => "the top-level value is not an object"
    end Problem
end SlackInvalidRawBlockException

/** A `SlackConfig` setting holds a value it cannot use: the failure of `SlackConfig.init` and of a bounded field's setter. */
final case class SlackInvalidConfigException(problem: SlackInvalidConfigException.Problem)(using Frame)
    extends SlackException(s"SlackConfig.${problem.show}.")

object SlackInvalidConfigException:
    /** The setting that failed validation, with what it held. */
    enum Problem derives CanEqual:
        case KeepAliveInterval(value: Duration)
        case AckDeadline(value: Duration)
        case RequestTimeout(value: Duration)
        case ConnectTimeout(value: Duration)
        case BaseUrl(problem: UrlProblem)

        private[kyo] def show: String =
            this match
                case KeepAliveInterval(value) => s"keepAliveInterval must be a positive, finite duration; got ${value.show}"
                case AckDeadline(value)       => s"ackDeadline must be a positive, finite duration; got ${value.show}"
                case RequestTimeout(value)    => s"requestTimeout must be a positive, finite duration; got ${value.show}"
                case ConnectTimeout(value)    => s"connectTimeout must be a positive, finite duration; got ${value.show}"
                case BaseUrl(problem)         => s"baseUrl must be an absolute http or https url on a host: ${problem.show}"
    end Problem

    /** Why a url is not one the module sends to. */
    enum UrlProblem derives CanEqual:
        case Scheme, Host, UnixSocket, Query, TrailingSlash

        /** The url's character at `position` is outside printable ASCII, which kyo-http refuses to send. */
        case Character(position: Int)

        /** The host carries user info (`user:password@`), which the config's rendering would show. */
        case UserInfo

        private[kyo] def show: String =
            this match
                case Scheme              => "its scheme is not http or https"
                case Host                => "it has no host"
                case UnixSocket          => "it names a unix socket"
                case Query               => "it has a query"
                case TrailingSlash       => "its path ends with a slash"
                case Character(position) => s"the character at position $position is outside printable ASCII"
                case UserInfo            => "it holds user info"
    end UrlProblem
end SlackInvalidConfigException

/** The text given to a [[kyo.SlackToken]] `init` cannot be a Slack token. `token` names which kind was being built. */
final case class SlackInvalidTokenException(token: SlackInvalidTokenException.Token, problem: SlackInvalidTokenException.Problem)(
    using Frame
) extends SlackException(s"${token.show} is not a Slack token: ${problem.show}.")

object SlackInvalidTokenException:
    /** Which token was refused. */
    enum Token derives CanEqual:
        case AppLevel, Bot

        private[kyo] def show: String =
            this match
                case AppLevel => "SlackToken.AppLevel"
                case Bot      => "SlackToken.Bot"
    end Token

    /** What is wrong with the text. A position, never the character, since the character is part of the secret. */
    enum Problem derives CanEqual:
        case Empty

        /** The text does not start with one of the `expected` prefixes followed by at least one character. */
        case Prefix(expected: Chunk[String])
        case TooLong(length: Int, max: Int)
        case InvalidCharacter(position: Int)

        private[kyo] def show: String =
            this match
                case Empty                      => "it is empty"
                case Prefix(expected)           => s"it does not start with ${expected.mkString(" or ")} followed by the token"
                case TooLong(length, max)       => s"it has $length characters, more than $max"
                case InvalidCharacter(position) => s"the character at position $position is not printable ASCII other than space"
    end Problem
end SlackInvalidTokenException

/** The name given to `SlackMethod.init` is not a Web API method name. */
final case class SlackInvalidMethodException(problem: SlackInvalidMethodException.Problem)(using Frame)
    extends SlackException(s"SlackMethod is not usable: ${problem.show}.")

object SlackInvalidMethodException:
    /** What is wrong with the name. The name itself is never kept: it is a part of the url the bot token goes to. */
    enum Problem derives CanEqual:
        case Empty

        /** The name is only dots, a `.` or `..` segment that would send the bot token to another path. */
        case Dots

        /** The character at `position` is not an ASCII letter, a digit, `.` or `_`. */
        case Character(position: Int)

        private[kyo] def show: String =
            this match
                case Empty               => "the name is empty"
                case Dots                => "the name is only dots"
                case Character(position) => s"the character at position $position is not an ASCII letter, a digit, '.' or '_'"
    end Problem
end SlackInvalidMethodException
