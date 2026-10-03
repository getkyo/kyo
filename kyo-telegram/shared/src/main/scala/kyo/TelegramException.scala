package kyo

/** Typed error hierarchy for kyo-telegram: a sealed base over `KyoException` with flat leaves, one per
  * failure a caller can tell apart.
  *
  * The leaves fall into four groups:
  *   - the transport and the response: [[kyo.TelegramTransportException]],
  *     [[kyo.TelegramUnexpectedStatusException]], [[kyo.TelegramDecodeException]] and
  *     [[kyo.TelegramRateLimitException]];
  *   - the Bot API's `{"ok":false}` answers: the category [[kyo.TelegramApiException]], with a leaf per
  *     answer a caller can act on and [[kyo.TelegramOtherApiException]] carrying any other;
  *   - the webhook's secret header: [[kyo.TelegramSecretTokenMissingException]] and
  *     [[kyo.TelegramSecretTokenMismatchException]];
  *   - a value Telegram would refuse, the failure of that type's `init` (a `Result`), such as
  *     [[kyo.TelegramInvalidTokenException]] and [[kyo.TelegramInvalidConfigException]].
  *
  * Each public operation fails with its own sealed trait, such as [[kyo.TelegramSendFailure]], and a
  * leaf extends the trait of every operation that can produce it, so a row names exactly the leaves a
  * caller can meet. No row names `TelegramException` itself.
  *
  * IMPORTANT: no leaf holds the bot token, which Telegram puts in the path of every request. The one
  * text a leaf keeps from a body is the `description` of a Bot API answer, which Telegram's server
  * writes, with the token and the webhook secret replaced by `<redacted>` in case a server echoes the
  * request (the token also in its percent-encoded form, as a path carries it); nothing a
  * user wrote, such as a message in an update, is kept. The transport leaf describes a kyo-http
  * failure with typed fields and does not keep the kyo-http exception, whose URL holds that path; it
  * keeps only kyo-net's cause of a failed connection, which holds no URL. The decode leaf names
  * kyo-schema's failure, its path and its position, and does not keep kyo-schema's exception, which
  * quotes the input. Every leaf builds its message from its own fields, and no message renders a
  * secret, a rejected value, or another exception's message.
  *
  * @see
  *   [[kyo.TelegramApiException]] the Bot API's `{"ok":false}` answers
  * @see
  *   [[kyo.TelegramTransportException]] transport failures
  * @see
  *   [[kyo.TelegramDecodeException]] bodies that do not decode
  */
sealed abstract class TelegramException(message: String)(using Frame) extends KyoException(message)

object TelegramException:
    given CanEqual[TelegramException, TelegramException] = CanEqual.derived
end TelegramException

// --- Operation failures ---

/** What `Telegram.run` can fail with: the long-polling loop and every `getUpdates` it makes. */
sealed trait TelegramRunFailure extends TelegramException

/** What `Telegram.getMe` can fail with. */
sealed trait TelegramGetMeFailure extends TelegramException

/** What `Telegram.send` can fail with, whatever the content sent. */
sealed trait TelegramSendFailure extends TelegramException

/** What `Telegram.edit` can fail with. */
sealed trait TelegramEditFailure extends TelegramException

/** What `Telegram.delete` can fail with. */
sealed trait TelegramDeleteFailure extends TelegramException

/** What `Telegram.answerCallback` can fail with. */
sealed trait TelegramAnswerCallbackFailure extends TelegramException

/** What `Telegram.sendChatAction` can fail with. */
sealed trait TelegramSendChatActionFailure extends TelegramException

/** What `Telegram.setReaction` can fail with. */
sealed trait TelegramSetReactionFailure extends TelegramException

/** What `Telegram.setCommands` can fail with. */
sealed trait TelegramSetCommandsFailure extends TelegramException

/** What `Telegram.getFile` can fail with. */
sealed trait TelegramGetFileFailure extends TelegramException

/** What `Telegram.download` can fail with: the file endpoint answers bytes, not a Bot API envelope. */
sealed trait TelegramDownloadFailure extends TelegramException

/** What `Telegram.setWebhook` can fail with. */
sealed trait TelegramSetWebhookFailure extends TelegramException

/** What `Telegram.deleteWebhook` can fail with. */
sealed trait TelegramDeleteWebhookFailure extends TelegramException

/** What `Telegram.getWebhookInfo` can fail with. */
sealed trait TelegramGetWebhookInfoFailure extends TelegramException

/** What `Telegram.custom` can fail with: only the answers that mean the same on every Bot API method. */
sealed trait TelegramCustomFailure extends TelegramException

/** What `Telegram.Webhook.verify` can fail with: the secret header. */
sealed trait TelegramWebhookVerifyFailure extends TelegramException

/** What `Telegram.Webhook.decode` can fail with: a webhook body that is not an update. */
sealed trait TelegramWebhookDecodeFailure extends TelegramException

// --- Transport and response ---

/** A Bot API call or a file download failed at the transport, before Telegram's answer could be read.
  * `method` is the Bot API method, or `download`. `kind` is what kyo-http reported, `host` and `port`
  * are the server the request went to, and `timeout` is the limit that ran out, for the two timeouts.
  * No kyo-http failure is kept, since every Telegram URL holds the bot token.
  *
  * `cause` is kyo-net's failure behind a connection that could not be made (an errno, a DNS failure, a
  * TLS handshake error), which holds no URL. It stays out of the leaf's equality and is the `getCause`.
  */
final case class TelegramTransportException(
    method: String,
    kind: TelegramTransportException.Kind,
    host: String,
    port: Int,
    timeout: Maybe[Duration]
)(
    val cause: Maybe[kyo.net.NetException] = Absent
)(using Frame)
    extends TelegramException(
        s"Telegram $method failed at the transport to $host:$port: ${kind.show}" + timeout.fold("")(t => s" after ${t.show}") + "."
    )
    with TelegramRunFailure with TelegramGetMeFailure with TelegramSendFailure with TelegramEditFailure with TelegramDeleteFailure
    with TelegramAnswerCallbackFailure with TelegramSendChatActionFailure with TelegramSetReactionFailure
    with TelegramSetCommandsFailure with TelegramGetFileFailure with TelegramDownloadFailure with TelegramSetWebhookFailure
    with TelegramDeleteWebhookFailure with TelegramGetWebhookInfoFailure with TelegramCustomFailure:
    // Not passed to `KyoException`'s constructor: it would embed the cause's message, formatted per
    // environment, into this leaf's `getMessage`, which states only what failed. `null` is
    // `Throwable.getCause`'s contract for "no cause".
    override def getCause(): Throwable = cause.fold(null)(identity)
end TelegramTransportException

object TelegramTransportException:
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

        /** The response broke HTTP/1.1: a status line or header field outside RFC 9112's grammar, a head larger than
          * the transport's header limit, or a chunked body with broken framing. It maps to a failure, not a panic,
          * because a malformed response is a condition the peer controls.
          */
        case Protocol

        /** The connection closed after the response head and before the body its framing declared was complete, or,
          * over TLS, without the peer's `close_notify` on a body framed by the close.
          */
        case ConnectionClosed

        /** Every one of the pool's `maxConnections` connections to the server was in use. */
        case PoolExhausted(maxConnections: Int)

        /** The response body of `bodySize` exceeded the client's `maxSize`. */
        case PayloadTooLarge(bodySize: ByteSize, maxSize: ByteSize)

        /** The connection closed before the response head arrived. */
        case NoResponseHead

        private[kyo] def show: String =
            this match
                case Connect                            => "could not connect"
                case Dns                                => "the host did not resolve"
                case Tls                                => "the TLS handshake failed"
                case ConnectTimeout                     => "the connection did not open"
                case Timeout                            => "no answer arrived"
                case Protocol                           => "the response broke the HTTP protocol"
                case ConnectionClosed                   => "the connection closed before the response body"
                case PoolExhausted(max)                 => s"all $max connections were in use"
                case PayloadTooLarge(bodySize, maxSize) => s"the response body of ${bodySize.show} exceeds ${maxSize.show}"
                case NoResponseHead                     => "no response head was read"
    end Kind
end TelegramTransportException

/** The module refused to send to a URL: a `file_path` Telegram answered that is not relative segments of
  * letters, digits, `.`, `_` and `-`, or a webhook URL that is not an absolute http or https URL on a host.
  * Nothing of the URL is copied. `method` is `download` or `setWebhook`.
  */
final case class TelegramRefusedUrlException(method: String)(using Frame)
    extends TelegramException(s"Telegram $method refused to send to a URL outside the ones the module sends to.")
    with TelegramDownloadFailure with TelegramSetWebhookFailure

/** Telegram answered a non-2xx status with a body that is not a Bot API response, such as an HTML page
  * from a proxy. For a file download, any non-2xx answer. A non-2xx answer that carries
  * `{"ok":false,...}` is a [[kyo.TelegramApiException]].
  */
final case class TelegramUnexpectedStatusException(method: String, status: HttpStatus)(using Frame)
    extends TelegramException(s"Telegram $method answered HTTP ${status.code} without a Bot API response body.")
    with TelegramRunFailure with TelegramGetMeFailure with TelegramSendFailure with TelegramEditFailure with TelegramDeleteFailure
    with TelegramAnswerCallbackFailure with TelegramSendChatActionFailure with TelegramSetReactionFailure
    with TelegramSetCommandsFailure with TelegramGetFileFailure with TelegramDownloadFailure with TelegramSetWebhookFailure
    with TelegramDeleteWebhookFailure with TelegramGetWebhookInfoFailure with TelegramCustomFailure

/** A body did not decode. `part` says whether the `ok` envelope, the method's `result`, or an update
  * failed. `failure` is which of kyo-schema's decode failures it was, `path` is where in the value it
  * happened, and `position` is the offset in the body when kyo-schema gave one.
  *
  * Nothing is copied from the body: a user's message or a credential could be there, so kyo-schema's
  * exception, which quotes the input it could not read, is not kept.
  */
final case class TelegramDecodeException(
    method: String,
    part: TelegramDecodeException.Part,
    failure: TelegramDecodeException.Failure,
    path: Chunk[String],
    position: Maybe[Int]
)(using Frame)
    extends TelegramException(
        s"Telegram $method ${part.show} did not decode: ${failure.show}" +
            (if path.isEmpty then "" else s" at ${path.mkString(".")}") +
            position.fold("")(p => s", position $p") + "."
    )
    with TelegramRunFailure with TelegramGetMeFailure with TelegramSendFailure with TelegramEditFailure with TelegramDeleteFailure
    with TelegramAnswerCallbackFailure with TelegramSendChatActionFailure with TelegramSetReactionFailure
    with TelegramSetCommandsFailure with TelegramGetFileFailure with TelegramSetWebhookFailure with TelegramDeleteWebhookFailure
    with TelegramGetWebhookInfoFailure with TelegramCustomFailure with TelegramWebhookDecodeFailure

object TelegramDecodeException:

    /** The leaf for kyo-schema's `ex` while decoding `part` of `method`'s answer, keeping nothing quoted from the input. */
    private[kyo] def of(method: String, part: Part, ex: DecodeException)(using Frame): TelegramDecodeException =
        def at(failure: Failure, path: Seq[String], position: Maybe[Int] = Absent) =
            TelegramDecodeException(method, part, failure, Chunk.from(path), position)
        ex match
            case e: MissingFieldException          => at(Failure.MissingField, e.path)
            case e: TypeMismatchException          => at(Failure.TypeMismatch, e.path)
            case e: UnknownVariantException        => at(Failure.UnknownVariant, e.path)
            case e: UnknownFieldException          => at(Failure.UnknownField, e.path)
            case e: NoVariantMatchException        => at(Failure.NoVariantMatch, e.path)
            case e: AmbiguousVariantMatchException => at(Failure.AmbiguousVariantMatch, e.path)
            case e: MissingTagKeyException         => at(Failure.MissingTagKey, e.path)
            case e: ParseException                 => at(Failure.Parse, e.path, if e.position >= 0 then Present(e.position) else Absent)
            case e: ConstructorRejectedException   => at(Failure.ConstructorRejected, e.path)
            case _: TruncatedInputException        => at(Failure.TruncatedInput, Seq.empty)
            case _: TrailingInputException         => at(Failure.TrailingInput, Seq.empty)
            case _: RecordDecodeException          => at(Failure.RecordDecode, Seq.empty)
            case _: LimitExceededException         => at(Failure.LimitExceeded, Seq.empty)
            case _: RangeException                 => at(Failure.Range, Seq.empty)
        end match
    end of

    /** The leaf for a decode that kyo-schema failed, or that the module's own check refused at a path. */
    private[kyo] def ofDecoded(method: String, part: Part, failure: DecodeException | kyo.internal.telegram.BotApi.Rejected)(using
        Frame
    ): TelegramDecodeException =
        failure match
            case ex: DecodeException                                  => of(method, part, ex)
            case kyo.internal.telegram.BotApi.Rejected(path, failure) =>
                TelegramDecodeException(method, part, failure, path, Absent)

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
        /** The envelope every answer has: `ok`, and on failure `error_code`, `description`, `parameters`. */
        case Envelope

        /** The method's `result`. */
        case Result

        /** An update, from `getUpdates` or from a webhook request. */
        case Update

        private[kyo] def show: String =
            this match
                case Envelope => "response envelope"
                case Result   => "result"
                case Update   => "update"
    end Part
end TelegramDecodeException

/** `download` was given a [[kyo.Telegram.File]] whose `path` is absent: Telegram answered `getFile`
  * without a `file_path`, so there is nothing to download.
  */
final case class TelegramNoFilePathException(file: Telegram.FileId)(using Frame)
    extends TelegramException(s"Telegram file ${file.value} has no file_path to download.")
    with TelegramDownloadFailure

/** Telegram refused the call for flood control, by `error_code` 429 or HTTP 429. `retryAfter` is the
  * delay Telegram sent, in `parameters.retry_after` or in `Retry-After`, or `Absent` when it sent none
  * it could mean.
  */
final case class TelegramRateLimitException(method: String, retryAfter: Maybe[Duration])(using Frame)
    extends TelegramException(
        s"Telegram rate-limited $method" + retryAfter.fold("; Telegram sent no retry delay.")(d => s"; retry after ${d.show}.")
    )
    with TelegramRunFailure with TelegramGetMeFailure with TelegramSendFailure with TelegramEditFailure with TelegramDeleteFailure
    with TelegramAnswerCallbackFailure with TelegramSendChatActionFailure with TelegramSetReactionFailure
    with TelegramSetCommandsFailure with TelegramGetFileFailure with TelegramSetWebhookFailure with TelegramDeleteWebhookFailure
    with TelegramGetWebhookInfoFailure with TelegramCustomFailure

// --- The Bot API's {"ok":false} answers ---

/** Telegram answered `{"ok":false,"error_code":code,"description":description}`. A leaf exists for
  * each answer a caller can act on, on the operations that can receive it; any other answer is a
  * [[kyo.TelegramOtherApiException]]. A named leaf stands for one `error_code`, so its `code` is fixed
  * by the leaf; only the catch-all carries the code it received. `description` is Telegram's own text,
  * with the bot token and the request's webhook secret replaced by `<redacted>`.
  */
sealed abstract class TelegramApiException(message: String)(using Frame) extends TelegramException(message):
    def method: String
    def code: Int
    def description: String
end TelegramApiException

object TelegramApiException:
    private[kyo] def describe(method: String, code: Int, description: String): String =
        s"Telegram $method answered $code: $description"
end TelegramApiException

/** `error_code` 400 with `migrate_to_chat_id`: the group was upgraded to a supergroup, and `chat` is the
  * supergroup's id. Calls to the old id fail until the caller uses the new one.
  */
final case class TelegramMigratedException(method: String, description: String, chat: Telegram.ChatId)(using Frame)
    extends TelegramApiException(
        TelegramApiException.describe(method, 400, description) + s"; the group is now the supergroup ${chat.value}."
    )
    with TelegramSendFailure with TelegramEditFailure with TelegramDeleteFailure with TelegramSendChatActionFailure
    with TelegramSetReactionFailure with TelegramCustomFailure:
    def code: Int = 400
end TelegramMigratedException

/** `error_code` 401: Telegram does not accept the bot token. */
final case class TelegramUnauthorizedException(method: String, description: String)(using Frame)
    extends TelegramApiException(TelegramApiException.describe(method, 401, description))
    with TelegramRunFailure with TelegramGetMeFailure with TelegramSendFailure with TelegramEditFailure with TelegramDeleteFailure
    with TelegramAnswerCallbackFailure with TelegramSendChatActionFailure with TelegramSetReactionFailure
    with TelegramSetCommandsFailure with TelegramGetFileFailure with TelegramSetWebhookFailure with TelegramDeleteWebhookFailure
    with TelegramGetWebhookInfoFailure with TelegramCustomFailure:
    def code: Int = 401
end TelegramUnauthorizedException

/** `error_code` 403: the bot may not act in the chat, as when a user blocked it or it was removed. */
final case class TelegramForbiddenException(method: String, description: String)(using Frame)
    extends TelegramApiException(TelegramApiException.describe(method, 403, description))
    with TelegramSendFailure with TelegramEditFailure with TelegramDeleteFailure with TelegramSendChatActionFailure
    with TelegramSetReactionFailure with TelegramCustomFailure:
    def code: Int = 403
end TelegramForbiddenException

/** `error_code` 409: another `getUpdates` or a webhook is in the way. `getUpdates` fails this way while
  * a webhook is set, and when another instance of the bot polls.
  */
final case class TelegramConflictException(method: String, description: String)(using Frame)
    extends TelegramApiException(TelegramApiException.describe(method, 409, description))
    with TelegramRunFailure with TelegramSetWebhookFailure:
    def code: Int = 409
end TelegramConflictException

/** `error_code` 400: the chat does not exist or the bot cannot see it. */
final case class TelegramChatNotFoundException(method: String, description: String)(using Frame)
    extends TelegramApiException(TelegramApiException.describe(method, 400, description))
    with TelegramSendFailure with TelegramEditFailure with TelegramDeleteFailure with TelegramSendChatActionFailure
    with TelegramSetReactionFailure:
    def code: Int = 400
end TelegramChatNotFoundException

/** `error_code` 400: the message does not exist in the chat, or no longer does. */
final case class TelegramMessageNotFoundException(method: String, description: String)(using Frame)
    extends TelegramApiException(TelegramApiException.describe(method, 400, description))
    with TelegramEditFailure with TelegramDeleteFailure with TelegramSetReactionFailure:
    def code: Int = 400
end TelegramMessageNotFoundException

/** `error_code` 400: the edit would leave the message's content and keyboard exactly as they are. */
final case class TelegramMessageNotModifiedException(method: String, description: String)(using Frame)
    extends TelegramApiException(TelegramApiException.describe(method, 400, description))
    with TelegramEditFailure:
    def code: Int = 400
end TelegramMessageNotModifiedException

/** `error_code` 400: the file is larger than the Bot API lets a bot download (20 MB on Telegram's servers). */
final case class TelegramFileTooBigException(method: String, description: String)(using Frame)
    extends TelegramApiException(TelegramApiException.describe(method, 400, description))
    with TelegramGetFileFailure:
    def code: Int = 400
end TelegramFileTooBigException

/** Any `{"ok":false}` answer with no leaf of its own on the operation that received it. */
final case class TelegramOtherApiException(method: String, code: Int, description: String)(using Frame)
    extends TelegramApiException(TelegramApiException.describe(method, code, description))
    with TelegramRunFailure with TelegramGetMeFailure with TelegramSendFailure with TelegramEditFailure with TelegramDeleteFailure
    with TelegramAnswerCallbackFailure with TelegramSendChatActionFailure with TelegramSetReactionFailure
    with TelegramSetCommandsFailure with TelegramGetFileFailure with TelegramSetWebhookFailure with TelegramDeleteWebhookFailure
    with TelegramGetWebhookInfoFailure with TelegramCustomFailure

// --- Webhook secret ---

/** A webhook request arrived without the `X-Telegram-Bot-Api-Secret-Token` header. */
final case class TelegramSecretTokenMissingException()(using Frame)
    extends TelegramException("Telegram webhook request carries no X-Telegram-Bot-Api-Secret-Token header.")
    with TelegramWebhookVerifyFailure

/** A webhook request's `X-Telegram-Bot-Api-Secret-Token` header does not match the secret. */
final case class TelegramSecretTokenMismatchException()(using Frame)
    extends TelegramException("Telegram webhook request's X-Telegram-Bot-Api-Secret-Token does not match the secret.")
    with TelegramWebhookVerifyFailure

// --- Values Telegram would refuse (the failure of an `init`) ---

/** Text that cannot be a [[kyo.Telegram.Token]] or a [[kyo.Telegram.SecretToken]], refused by its `init`. */
final case class TelegramInvalidTokenException(token: TelegramInvalidTokenException.Token, problem: TelegramInvalidTokenException.Problem)(
    using Frame
) extends TelegramException(s"${token.show} is not usable: ${problem.show(token)}.")

object TelegramInvalidTokenException:
    /** Which secret was refused. */
    enum Token derives CanEqual:
        case Bot, Secret

        private[kyo] def show: String =
            this match
                case Bot    => "Telegram.Token"
                case Secret => "Telegram.SecretToken"

        private[kyo] def allowed: String =
            this match
                case Bot    => "A-Z a-z 0-9 _ - :"
                case Secret => "A-Z a-z 0-9 _ -"
    end Token

    /** What is wrong with the text. A position, never the character, since the character is part of the secret. */
    enum Problem derives CanEqual:
        case Empty
        case TooLong(length: Int, max: Int)

        /** A bot token has no `:` between the bot id and the secret part. */
        case NoColon
        case InvalidCharacter(position: Int)

        private[kyo] def show(token: Token): String =
            this match
                case Empty                      => "it is empty"
                case TooLong(length, max)       => s"it has $length characters, more than $max"
                case NoColon                    => "it has no ':' between the bot id and the secret"
                case InvalidCharacter(position) => s"the character at position $position is not one of ${token.allowed}"
    end Problem
end TelegramInvalidTokenException

/** A [[kyo.TelegramConfig]] setting with a value it cannot use, refused by `TelegramConfig.init`. */
final case class TelegramInvalidConfigException(problem: TelegramInvalidConfigException.Problem)(using Frame)
    extends TelegramException(s"TelegramConfig.${problem.show}.")

object TelegramInvalidConfigException:
    /** The setting and what is wrong with it. */
    enum Problem derives CanEqual:
        case BaseUrl(problem: UrlProblem)
        case PollTimeout(value: Duration)
        case PollLimit(value: Int)
        case RequestTimeout(value: Duration)
        case TransferTimeout(value: Duration)
        case ConnectTimeout(value: Duration)
        case MaxResponseLength(value: ByteSize, max: ByteSize)
        case RetryMaxDelay(value: Duration)

        private[kyo] def show: String =
            this match
                case BaseUrl(problem)       => s"baseUrl ${problem.show}"
                case PollTimeout(value)     => s"pollTimeout must be a whole number of seconds from 1 to ${Int.MaxValue}; got ${value.show}"
                case PollLimit(value)       => s"pollLimit must be between 1 and 100; got $value"
                case RequestTimeout(value)  => s"requestTimeout must be positive and finite; got ${value.show}"
                case TransferTimeout(value) => s"transferTimeout must be positive and finite; got ${value.show}"
                case ConnectTimeout(value)  => s"connectTimeout must be positive and finite; got ${value.show}"
                case MaxResponseLength(value, max) =>
                    s"maxResponseLength must be from 1 byte to ${max.show}; got ${value.show}"
                case RetryMaxDelay(value) => s"retryMaxDelay must be positive and finite; got ${value.show}"
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
end TelegramInvalidConfigException

/** Inline keyboard callback data outside the 1 to 64 bytes Telegram accepts, refused by `CallbackData.init`. */
final case class TelegramInvalidCallbackDataException(length: Int)(using Frame)
    extends TelegramException(s"Telegram callback data must be 1 to 64 bytes in UTF-8; got $length.")

/** A bot command or command menu Telegram would refuse, refused by `Command.init` or `Command.Menu.init`. */
final case class TelegramInvalidCommandException(problem: TelegramInvalidCommandException.Problem)(using Frame)
    extends TelegramException(s"Telegram.Command is not usable: ${problem.show}.")

object TelegramInvalidCommandException:
    /** What is wrong with the command. */
    enum Problem derives CanEqual:
        /** The name has `length` characters, outside 1 to 32. */
        case NameLength(length: Int)

        /** The name's character at `position` is not a lowercase letter, a digit or `_`. */
        case NameCharacter(position: Int)

        /** The description has `length` characters, outside 1 to 256. */
        case DescriptionLength(length: Int)

        /** A menu has `count` commands, more than 100. */
        case TooMany(count: Int)

        private[kyo] def show: String =
            this match
                case NameLength(length)        => s"the name must have 1 to 32 characters; got $length"
                case NameCharacter(position)   => s"the name's character at position $position is not one of a-z 0-9 _"
                case DescriptionLength(length) => s"the description must have 1 to 256 characters; got $length"
                case TooMany(count)            => s"a menu holds at most 100 commands; got $count"
    end Problem
end TelegramInvalidCommandException

/** A [[kyo.Telegram.WebhookOptions]] value Telegram does not accept, refused by `WebhookOptions.init`. */
final case class TelegramInvalidWebhookOptionsException(problem: TelegramInvalidWebhookOptionsException.Problem)(using Frame)
    extends TelegramException(s"Telegram.WebhookOptions is not usable: ${problem.show}.")

object TelegramInvalidWebhookOptionsException:
    /** What is wrong with the options. */
    enum Problem derives CanEqual:
        /** `maxConnections` is outside 1 to 100. */
        case MaxConnections(value: Int)

        private[kyo] def show: String =
            this match
                case MaxConnections(value) => s"maxConnections must be between 1 and 100; got $value"
    end Problem
end TelegramInvalidWebhookOptionsException

/** A [[kyo.TelegramWebhookConfig]] path no request can reach, refused by `TelegramWebhookConfig.init`. */
final case class TelegramInvalidWebhookConfigException(problem: TelegramInvalidWebhookConfigException.Problem)(using Frame)
    extends TelegramException(s"TelegramWebhookConfig is not usable: ${problem.show}.")

object TelegramInvalidWebhookConfigException:
    /** What is wrong with the config. */
    enum Problem derives CanEqual:
        /** The path's character at `position` is `?`, `#`, a space, a control character or outside ASCII. */
        case PathCharacter(position: Int)

        private[kyo] def show: String =
            this match
                case PathCharacter(position) => s"the path's character at position $position cannot appear in a request path"
    end Problem
end TelegramInvalidWebhookConfigException

/** A [[kyo.Telegram.CallbackAnswer]] value Telegram does not accept, refused by `CallbackAnswer.init`. */
final case class TelegramInvalidCallbackAnswerException(problem: TelegramInvalidCallbackAnswerException.Problem)(using Frame)
    extends TelegramException(s"Telegram.CallbackAnswer is not usable: ${problem.show}.")

object TelegramInvalidCallbackAnswerException:
    /** What is wrong with the answer. */
    enum Problem derives CanEqual:
        /** The text has `length` characters, more than 200. */
        case TextLength(length: Int)

        /** The cache time is not a whole number of seconds from 0 to `Int.MaxValue`. */
        case CacheTime(value: Duration)

        private[kyo] def show: String =
            this match
                case TextLength(length) => s"the text must have at most 200 characters; got $length"
                case CacheTime(value)   => s"cacheTime must be a whole number of seconds from 0 to ${Int.MaxValue}; got ${value.show}"
    end Problem
end TelegramInvalidCallbackAnswerException

/** A name that is not a Bot API method name, refused by `Method.init`. */
final case class TelegramInvalidMethodException(problem: TelegramInvalidMethodException.Problem)(using Frame)
    extends TelegramException(s"Telegram.Method is not usable: ${problem.show}.")

object TelegramInvalidMethodException:
    /** What is wrong with the name. The name itself is never kept: it is a part of the URL the token is in. */
    enum Problem derives CanEqual:
        /** The name is empty. */
        case Empty

        /** The name is only dots, a path segment that moves the request. */
        case Dots

        /** The character at `position` is not an ASCII letter, a digit, `.` or `_`. */
        case Character(position: Int)

        private[kyo] def show: String =
            this match
                case Empty               => "the name is empty"
                case Dots                => "the name is only dots"
                case Character(position) => s"the character at position $position is not one of A-Z a-z 0-9 . _"
    end Problem
end TelegramInvalidMethodException

/** Text that is not an http, https or tg URL, refused by `Url.init`. */
final case class TelegramInvalidUrlException(problem: TelegramInvalidUrlException.Problem)(using Frame)
    extends TelegramException(s"Telegram.Url is not usable: ${problem.show}.")

object TelegramInvalidUrlException:
    /** What is wrong with the text. A position, never the text, since a URL may carry a credential. */
    enum Problem derives CanEqual:
        /** The text does not start with `http://`, `https://` or `tg://`, in any ASCII case. */
        case Scheme

        /** Nothing follows the scheme. */
        case Empty

        /** The character at `position` is not printable ASCII other than space. */
        case Character(position: Int)

        private[kyo] def show: String =
            this match
                case Scheme              => "it does not start with http://, https:// or tg://"
                case Empty               => "nothing follows the scheme"
                case Character(position) => s"the character at position $position is not printable ASCII other than space"
    end Problem
end TelegramInvalidUrlException
