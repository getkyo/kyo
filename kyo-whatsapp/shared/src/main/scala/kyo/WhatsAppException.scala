package kyo

/** Typed error hierarchy for kyo-whatsapp: a sealed base over `KyoException` with flat leaves, one per failure a caller can tell apart.
  *
  * The leaves fall into four groups:
  *   - the transport and the response: [[kyo.WhatsAppTransportException]], [[kyo.WhatsAppRefusedUrlException]],
  *     [[kyo.WhatsAppRefusedPartException]], [[kyo.WhatsAppUnexpectedStatusException]] and [[kyo.WhatsAppDecodeException]];
  *   - the Graph API's error answers: the category [[kyo.WhatsAppApiException]], with a leaf per error a caller can act on, the
  *     [[kyo.WhatsAppRateLimitException]] and [[kyo.WhatsAppTemplateException]] categories, and [[kyo.WhatsAppOtherApiException]]
  *     carrying any other code;
  *   - the webhook's signature: [[kyo.WhatsAppSignatureMissingException]], [[kyo.WhatsAppSignatureMalformedException]] and
  *     [[kyo.WhatsAppSignatureMismatchException]];
  *   - a value that cannot be built, the failure of its `init` and never on an `Abort` row: [[kyo.WhatsAppInvalidConfigException]],
  *     [[kyo.WhatsAppInvalidWebhookConfigException]], [[kyo.WhatsAppInvalidTokenException]] and [[kyo.WhatsAppInvalidPathException]].
  *
  * Each public operation fails with its own sealed trait, such as [[kyo.WhatsAppSendFailure]], and a leaf extends the trait of every
  * operation that can produce it, so a row names exactly the leaves a caller can meet. No row names `WhatsAppException` itself.
  *
  * IMPORTANT: no leaf holds a kyo-http failure, since every one names its request's url and a pre-signed media url's query is a
  * credential; the transport leaf describes the failure with typed fields. No leaf keeps kyo-schema's failure either, whose fields quote
  * the body it rejected. The text a leaf copies from a Graph error (`description`, `details`) has the access token's value replaced by
  * `<redacted>`. Every leaf builds its message from its own fields.
  *
  * @see
  *   [[kyo.WhatsApp]] the calls that abort with these leaves
  * @see
  *   [[kyo.WhatsAppMedia]] the media calls
  * @see
  *   [[kyo.WhatsApp.Webhook]] signature verification and notification decoding
  */
sealed abstract class WhatsAppException(message: String)(using Frame) extends KyoException(message)

object WhatsAppException:
    given CanEqual[WhatsAppException, WhatsAppException] = CanEqual.derived
end WhatsAppException

/** What `WhatsApp.send` can fail with. A code the operation does not name, such as a template code, is `WhatsAppOtherApiException`. */
sealed trait WhatsAppSendFailure extends WhatsAppException

/** What `WhatsApp.sendTemplate` can fail with: the failures of a send, except the closed service window (templates are how a business
  * writes outside it), plus the six `WhatsAppTemplateException` leaves.
  */
sealed trait WhatsAppSendTemplateFailure extends WhatsAppException

/** What `WhatsApp.markRead` and `WhatsApp.markReadWithTyping` can fail with; they send the same request. */
sealed trait WhatsAppMarkReadFailure extends WhatsAppException

/** What `WhatsApp.custom` can fail with: only the Graph errors that mean the same on every endpoint are named. */
sealed trait WhatsAppCustomFailure extends WhatsAppException

/** What `WhatsAppMedia.upload` can fail with. */
sealed trait WhatsAppUploadFailure extends WhatsAppException

/** What `WhatsAppMedia.download` can fail with: exactly the failures of its two steps. No leaf mixes it in directly. */
sealed trait WhatsAppDownloadFailure extends WhatsAppException

/** What `WhatsAppMedia.resolveUrl` can fail with. */
sealed trait WhatsAppResolveUrlFailure extends WhatsAppDownloadFailure

/** What `WhatsAppMedia.downloadFrom` can fail with. The media host documents no Graph codes, so a Graph-shaped error body is
  * `WhatsAppOtherApiException`.
  */
sealed trait WhatsAppDownloadFromFailure extends WhatsAppDownloadFailure

/** What `WhatsAppMedia.delete` can fail with. */
sealed trait WhatsAppDeleteFailure extends WhatsAppException

/** What `WhatsApp.Webhook.verify` can fail with: the signature header is missing, malformed, or does not match the body. */
sealed trait WhatsAppWebhookVerifyFailure extends WhatsAppException

/** What `WhatsApp.Webhook.decode` can fail with: a webhook body that is not a notification envelope. */
sealed trait WhatsAppWebhookDecodeFailure extends WhatsAppException

/** A call failed at the transport, before the answer could be read. `method` is the operation (`send`, `downloadFrom`, ...), `host` and
  * `port` the server the request went to, and `timeout` the limit that ran out, for the two timeouts. No kyo-http failure is kept, since
  * each names the request's url.
  *
  * `cause` is kyo-net's failure behind a connection that could not be made, which names a host and port and no url. It stays out of the
  * leaf's equality and is the `getCause`.
  */
final case class WhatsAppTransportException(
    method: String,
    kind: WhatsAppTransportException.Kind,
    host: String,
    port: Int,
    timeout: Maybe[Duration]
)(val cause: Maybe[kyo.net.NetException] = Absent)(using Frame)
    extends WhatsAppException(
        s"WhatsApp $method failed at the transport to $host:$port: ${kind.show}" + timeout.fold("")(t => s" after ${t.show}") + "."
    )
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppMarkReadFailure with WhatsAppCustomFailure
    with WhatsAppUploadFailure with WhatsAppResolveUrlFailure with WhatsAppDownloadFromFailure with WhatsAppDeleteFailure:
    // Not passed to `KyoException`'s constructor: it would embed the cause's message into this leaf's `getMessage`, which states only
    // what failed. `null` is `Throwable.getCause`'s contract for "no cause".
    override def getCause(): Throwable = cause.fold(null)(identity)
end WhatsAppTransportException

object WhatsAppTransportException:
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

        /** The response broke the HTTP protocol: a head larger than kyo-http's header limit, a status code outside 100 to 599, or a chunked
          * body whose framing does not parse. It maps to a failure, not a panic, because a malformed response is a condition the peer
          * controls.
          */
        case Protocol

        /** The connection closed after the response head and before the body its framing declared was complete, or, over TLS, ended a
          * close-framed body without the peer's `close_notify`.
          */
        case ConnectionClosed

        /** Every one of the pool's `maxConnections` connections to the server was in use. */
        case PoolExhausted(maxConnections: Int)

        /** The response body of `bodySize` exceeded the config's `maxResponseLength`, `maxSize`. */
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
                case NoResponseHead                     => "the connection closed before the response head"
    end Kind
end WhatsAppTransportException

/** The module refused to send to a URL: a media URL that is not an absolute http or https URL on a host (`downloadFrom`), or a media id
  * that is not one path segment (`resolveUrl`, `delete`). Nothing of the URL or the id is copied: a pre-signed media URL's query is a
  * credential.
  */
final case class WhatsAppRefusedUrlException(method: String)(using Frame)
    extends WhatsAppException(s"WhatsApp $method refused to send to a URL outside the ones the module sends to.")
    with WhatsAppResolveUrlFailure with WhatsAppDownloadFromFailure with WhatsAppDeleteFailure

/** The module refused to upload a value it would have to write into a multipart part head: a `filename`, or the mime of a
  * `MediaType.Other`, holding a character outside printable ASCII, a `"` or a `\`. kyo-http writes both into the part head as they are,
  * so a quote or a line break there would end the parameter or the header and let the rest of the value add one. Nothing of the value is
  * copied: a filename can come from a customer's message.
  */
final case class WhatsAppRefusedPartException(method: String, field: WhatsAppRefusedPartException.Field)(using Frame)
    extends WhatsAppException(s"WhatsApp $method refused a ${field.show} it cannot write into a multipart part head.")
    with WhatsAppUploadFailure

object WhatsAppRefusedPartException:
    /** Which upload argument was refused. */
    enum Field derives CanEqual:
        case Filename, MediaType

        def show: String = this match
            case Filename  => "filename"
            case MediaType => "media type"
    end Field
end WhatsAppRefusedPartException

/** A non-2xx answer whose body is not a Graph error, such as a media host's 404 or a proxy's error page. No body text is kept. */
final case class WhatsAppUnexpectedStatusException(method: String, status: HttpStatus)(using Frame)
    extends WhatsAppException(s"WhatsApp $method answered HTTP ${status.code} without a Graph error body.")
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppMarkReadFailure with WhatsAppCustomFailure
    with WhatsAppUploadFailure with WhatsAppResolveUrlFailure with WhatsAppDownloadFromFailure with WhatsAppDeleteFailure

/** A body did not decode. `part` says whether a call's answer or a webhook notification failed, `failure` which of kyo-schema's decode
  * failures it was, `path` where in the value it happened, and `position` the offset in the body when kyo-schema gave one.
  *
  * Nothing is copied from the body: a message a person wrote or a credential could be there, so kyo-schema's exception, which quotes the
  * input it could not read, is not kept. An unknown message type, status or change field is not this failure: it decodes to an `Unknown`
  * case.
  *
  * An answer whose types are right and whose values are not is this failure too, located at the field: a send answer whose `messages` is
  * empty (`MissingField` at `messages`), and an acknowledgement of `{"success": false}` (`ConstructorRejected` at `success`).
  */
final case class WhatsAppDecodeException(
    method: String,
    part: WhatsAppDecodeException.Part,
    failure: WhatsAppDecodeException.Failure,
    path: Chunk[String],
    position: Maybe[Int]
)(using Frame)
    extends WhatsAppException(WhatsAppDecodeException.show(method, part, failure, path, position))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppMarkReadFailure with WhatsAppCustomFailure
    with WhatsAppUploadFailure with WhatsAppResolveUrlFailure with WhatsAppDeleteFailure with WhatsAppWebhookDecodeFailure:
    /** The message as one line, without the source rendering `getMessage` adds in development. */
    private[kyo] def show: String = WhatsAppDecodeException.show(method, part, failure, path, position)
end WhatsAppDecodeException

object WhatsAppDecodeException:

    private def show(method: String, part: Part, failure: Failure, path: Chunk[String], position: Maybe[Int]): String =
        s"WhatsApp $method ${part.show} did not decode: ${failure.show}" +
            (if path.isEmpty then "" else s" at ${path.mkString(".")}") +
            position.fold("")(p => s", position $p") + "."

    /** The leaf for kyo-schema's `ex` while decoding `part` of `method`, keeping nothing quoted from the input. */
    private[kyo] def of(method: String, part: Part, ex: DecodeException)(using Frame): WhatsAppDecodeException =
        def at(failure: Failure, path: Seq[String], position: Maybe[Int] = Absent) =
            WhatsAppDecodeException(method, part, failure, Chunk.from(path), position)
        // No wildcard: a kyo-schema leaf added upstream fails to compile here instead of arriving unnamed.
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

    /** The body that failed to decode. */
    enum Part derives CanEqual:
        /** A call's answer. */
        case Response

        /** A webhook notification. */
        case Notification

        private[kyo] def show: String =
            this match
                case Response     => "response"
                case Notification => "notification"
    end Part
end WhatsAppDecodeException

/** The Cloud API answered with a Graph error object. A leaf exists for each error a caller can act on, on the operations that can
  * receive it; any other code is a [[kyo.WhatsAppOtherApiException]].
  *
  * `code` is Meta's error code and `subcode` its `error_subcode`. `description` is Meta's `message` and `details` its
  * `error_data.details`, which often says what to do (code 131047's message is "Re-engagement message"; its details say the 24 hours
  * passed); both have the access token's value replaced by `<redacted>`. `traceId` is `fbtrace_id`, the id Meta support asks for.
  *
  * A leaf that stands for one code fixes `code`; a leaf grouping several codes with one remedy (`WhatsAppAccessDeniedException`,
  * `WhatsAppInvalidParameterException`, `WhatsAppServiceUnavailableException`) and the catch-all carry the code they received.
  */
sealed abstract class WhatsAppApiException(message: String)(using Frame) extends WhatsAppException(message):
    def method: String
    def code: Int
    def subcode: Maybe[Int]
    def description: String
    def details: Maybe[String]
    def traceId: Maybe[String]
end WhatsAppApiException

object WhatsAppApiException:
    private[kyo] def describe(
        method: String,
        code: Int,
        subcode: Maybe[Int],
        description: String,
        details: Maybe[String],
        traceId: Maybe[String]
    ): String =
        s"WhatsApp $method answered Graph error $code" + subcode.fold("")(s => s", subcode $s") + s": $description" +
            details.fold("")(d => s" ($d)") + traceId.fold("")(t => s" [fbtrace_id $t]")
end WhatsAppApiException

/** The access token has expired (code 190). */
final case class WhatsAppTokenExpiredException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppApiException(WhatsAppApiException.describe(method, 190, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppMarkReadFailure with WhatsAppCustomFailure
    with WhatsAppUploadFailure with WhatsAppResolveUrlFailure with WhatsAppDeleteFailure:
    def code: Int = 190
end WhatsAppTokenExpiredException

/** The token lacks the permission the call needs (codes 0, 3, 10, 200 to 299, 131005). */
final case class WhatsAppAccessDeniedException(
    method: String,
    code: Int,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppApiException(WhatsAppApiException.describe(method, code, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppMarkReadFailure with WhatsAppCustomFailure
    with WhatsAppUploadFailure with WhatsAppResolveUrlFailure with WhatsAppDeleteFailure

/** A request parameter is missing or invalid (codes 100, 131008, 131009, 135000). */
final case class WhatsAppInvalidParameterException(
    method: String,
    code: Int,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppApiException(WhatsAppApiException.describe(method, code, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppMarkReadFailure with WhatsAppCustomFailure
    with WhatsAppUploadFailure with WhatsAppResolveUrlFailure with WhatsAppDeleteFailure

/** A temporary Meta-side failure, retryable (codes 131000, 131016). */
final case class WhatsAppServiceUnavailableException(
    method: String,
    code: Int,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppApiException(WhatsAppApiException.describe(method, code, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppMarkReadFailure with WhatsAppCustomFailure
    with WhatsAppUploadFailure with WhatsAppResolveUrlFailure with WhatsAppDeleteFailure

/** The message could not be delivered (code 131026). The Cloud API gives no distinct code for a recipient without a WhatsApp account, so
  * that case arrives here too.
  */
final case class WhatsAppUndeliverableException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppApiException(WhatsAppApiException.describe(method, 131026, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure:
    def code: Int = 131026
end WhatsAppUndeliverableException

/** The recipient is the sender's own number (code 131021). */
final case class WhatsAppSenderIsRecipientException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppApiException(WhatsAppApiException.describe(method, 131021, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure:
    def code: Int = 131021
end WhatsAppSenderIsRecipientException

/** A free-form send after the 24-hour customer service window closed (code 131047); only a template is deliverable. */
final case class WhatsAppWindowClosedException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppApiException(WhatsAppApiException.describe(method, 131047, subcode, description, details, traceId))
    with WhatsAppSendFailure:
    def code: Int = 131047
end WhatsAppWindowClosedException

/** The Cloud API could not use the media of a message or an upload (code 131053). */
final case class WhatsAppMediaUploadException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppApiException(WhatsAppApiException.describe(method, 131053, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppUploadFailure:
    def code: Int = 131053
end WhatsAppMediaUploadException

/** A rate limit was hit. Meta tells four limits apart, each with its own remedy. Meta documents no retry delay; `retryAfter` is the
  * answer's `Retry-After` when one was sent, which is how a call configured with `WhatsAppConfig.retry` reports a wait longer than its
  * `retryMaxDelay`.
  */
sealed abstract class WhatsAppRateLimitException(message: String)(using Frame) extends WhatsAppApiException(message):
    def retryAfter: Maybe[Duration]

/** The app reached its API call rate limit (code 4). */
final case class WhatsAppAppRateLimitException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String],
    retryAfter: Maybe[Duration] = Absent
)(using Frame)
    extends WhatsAppRateLimitException(WhatsAppApiException.describe(method, 4, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppMarkReadFailure with WhatsAppCustomFailure
    with WhatsAppUploadFailure with WhatsAppResolveUrlFailure with WhatsAppDeleteFailure:
    def code: Int = 4
end WhatsAppAppRateLimitException

/** The WhatsApp Business Account reached its rate limit (code 80007). */
final case class WhatsAppBusinessAccountRateLimitException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String],
    retryAfter: Maybe[Duration] = Absent
)(using Frame)
    extends WhatsAppRateLimitException(WhatsAppApiException.describe(method, 80007, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppMarkReadFailure with WhatsAppCustomFailure
    with WhatsAppUploadFailure with WhatsAppResolveUrlFailure with WhatsAppDeleteFailure:
    def code: Int = 80007
end WhatsAppBusinessAccountRateLimitException

/** The Cloud API message throughput was reached (code 130429). */
final case class WhatsAppThroughputRateLimitException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String],
    retryAfter: Maybe[Duration] = Absent
)(using Frame)
    extends WhatsAppRateLimitException(WhatsAppApiException.describe(method, 130429, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure:
    def code: Int = 130429
end WhatsAppThroughputRateLimitException

/** Too many messages from the sender to the same recipient in a short time (code 131056). */
final case class WhatsAppRecipientPairRateLimitException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String],
    retryAfter: Maybe[Duration] = Absent
)(using Frame)
    extends WhatsAppRateLimitException(WhatsAppApiException.describe(method, 131056, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure:
    def code: Int = 131056
end WhatsAppRecipientPairRateLimitException

/** A template send the Cloud API rejected. Every template leaf belongs to `sendTemplate` only. */
sealed abstract class WhatsAppTemplateException(message: String)(using Frame) extends WhatsAppApiException(message)
    with WhatsAppSendTemplateFailure

/** The named template does not exist in the requested language, or is not approved (code 132001). */
final case class WhatsAppTemplateNotFoundException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppTemplateException(WhatsAppApiException.describe(method, 132001, subcode, description, details, traceId)):
    def code: Int = 132001
end WhatsAppTemplateNotFoundException

/** The parameter count differs from the template's placeholders (code 132000). */
final case class WhatsAppTemplateParameterCountException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppTemplateException(WhatsAppApiException.describe(method, 132000, subcode, description, details, traceId)):
    def code: Int = 132000
end WhatsAppTemplateParameterCountException

/** A parameter does not match the format the template expects (code 132012). */
final case class WhatsAppTemplateParameterFormatException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppTemplateException(WhatsAppApiException.describe(method, 132012, subcode, description, details, traceId)):
    def code: Int = 132012
end WhatsAppTemplateParameterFormatException

/** The template content violates WhatsApp policy (code 132007). */
final case class WhatsAppTemplateContentPolicyException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppTemplateException(WhatsAppApiException.describe(method, 132007, subcode, description, details, traceId)):
    def code: Int = 132007
end WhatsAppTemplateContentPolicyException

/** The template is paused (code 132015). */
final case class WhatsAppTemplatePausedException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppTemplateException(WhatsAppApiException.describe(method, 132015, subcode, description, details, traceId)):
    def code: Int = 132015
end WhatsAppTemplatePausedException

/** The filled template text exceeds the length limit (code 132005). */
final case class WhatsAppTemplateTextTooLongException(
    method: String,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppTemplateException(WhatsAppApiException.describe(method, 132005, subcode, description, details, traceId)):
    def code: Int = 132005
end WhatsAppTemplateTextTooLongException

/** A Graph error code with no leaf of its own on the operation that received it. */
final case class WhatsAppOtherApiException(
    method: String,
    code: Int,
    subcode: Maybe[Int],
    description: String,
    details: Maybe[String],
    traceId: Maybe[String]
)(using Frame)
    extends WhatsAppApiException(WhatsAppApiException.describe(method, code, subcode, description, details, traceId))
    with WhatsAppSendFailure with WhatsAppSendTemplateFailure with WhatsAppMarkReadFailure with WhatsAppCustomFailure
    with WhatsAppUploadFailure with WhatsAppResolveUrlFailure with WhatsAppDownloadFromFailure with WhatsAppDeleteFailure

/** The request has no `X-Hub-Signature-256` header. */
final case class WhatsAppSignatureMissingException()(using Frame)
    extends WhatsAppException("X-Hub-Signature-256 header is missing.") with WhatsAppWebhookVerifyFailure

/** The header lacks the `sha256=` prefix or its remainder is not even-length hex. */
final case class WhatsAppSignatureMalformedException()(using Frame)
    extends WhatsAppException("X-Hub-Signature-256 header is malformed.") with WhatsAppWebhookVerifyFailure

/** The header is well formed but does not match the HMAC-SHA256 of the body. */
final case class WhatsAppSignatureMismatchException()(using Frame)
    extends WhatsAppException("X-Hub-Signature-256 does not match the body.") with WhatsAppWebhookVerifyFailure

/** A [[kyo.WhatsAppConfig]] setting holds a value it cannot use: the failure of `WhatsAppConfig.init`. */
final case class WhatsAppInvalidConfigException(problem: WhatsAppInvalidConfigException.Problem)(using Frame)
    extends WhatsAppException(s"WhatsAppConfig.${problem.show}.")

object WhatsAppInvalidConfigException:
    /** The setting and what is wrong with it. The base URL's text is not rendered, only the problem with it. */
    enum Problem derives CanEqual:
        case BaseUrl(problem: UrlProblem)
        case ApiVersion
        case PhoneNumberId
        case RequestTimeout(value: Duration)
        case ConnectTimeout(value: Duration)
        case RetryMaxDelay(value: Duration)

        private[kyo] def show: String =
            this match
                case BaseUrl(problem)      => s"baseUrl ${problem.show}"
                case ApiVersion            => "apiVersion must be v, digits, a dot and digits, as in v25.0"
                case PhoneNumberId         => "phoneNumberId must be one or more ASCII digits"
                case RequestTimeout(value) => s"requestTimeout must be positive and finite; got ${value.show}"
                case ConnectTimeout(value) => s"connectTimeout must be positive and finite; got ${value.show}"
                case RetryMaxDelay(value)  => s"retryMaxDelay must be positive and finite; got ${value.show}"
    end Problem

    /** What is wrong with a base URL. */
    enum UrlProblem derives CanEqual:
        case Scheme
        case Host
        case UnixSocket
        case Query
        case TrailingSlash

        /** The URL's character at `position` is outside printable ASCII, which kyo-http refuses to send. */
        case Character(position: Int)

        /** The host carries userinfo (`user:password@`), which the config's rendering would show. */
        case UserInfo

        private[kyo] def show: String =
            this match
                case Scheme              => "must be an http or https URL"
                case Host                => "must name a host"
                case UnixSocket          => "must not be a Unix socket"
                case Query               => "must hold no query"
                case TrailingSlash       => "must not end with / after a path"
                case Character(position) => s"must be printable ASCII; the character at position $position is not"
                case UserInfo            => "must hold no user info"
    end UrlProblem
end WhatsAppInvalidConfigException

/** The text given to the `init` of a [[kyo.WhatsAppToken]], [[kyo.WhatsAppAppSecret]] or [[kyo.WhatsAppVerifyToken]] cannot be one. */
final case class WhatsAppInvalidTokenException(token: WhatsAppInvalidTokenException.Token, problem: WhatsAppInvalidTokenException.Problem)(
    using Frame
) extends WhatsAppException(s"${token.show} is not usable: ${problem.show}.")

object WhatsAppInvalidTokenException:
    /** Which secret was refused. */
    enum Token derives CanEqual:
        case AccessToken, AppSecret, VerifyToken

        private[kyo] def show: String =
            this match
                case AccessToken => "WhatsAppToken"
                case AppSecret   => "WhatsAppAppSecret"
                case VerifyToken => "WhatsAppVerifyToken"
    end Token

    /** What is wrong with the text. A position, never the character, since the character is part of the secret. */
    enum Problem derives CanEqual:
        case Empty
        case InvalidCharacter(position: Int)

        private[kyo] def show: String =
            this match
                case Empty                      => "it is empty"
                case InvalidCharacter(position) => s"the character at position $position is not allowed"
    end Problem
end WhatsAppInvalidTokenException

/** The path given to `WhatsAppWebhookConfig.init` is one no request can reach. */
final case class WhatsAppInvalidWebhookConfigException(problem: WhatsAppInvalidWebhookConfigException.Problem)(using Frame)
    extends WhatsAppException(s"WhatsAppWebhookConfig is not usable: ${problem.show}.")

object WhatsAppInvalidWebhookConfigException:
    /** What is wrong with the config. */
    enum Problem derives CanEqual:
        /** The path's character at `position` is `?`, `#`, a space, a control character or outside ASCII. */
        case PathCharacter(position: Int)

        private[kyo] def show: String =
            this match
                case PathCharacter(position) => s"the path's character at position $position cannot appear in a request path"
    end Problem
end WhatsAppInvalidWebhookConfigException

/** The text given to `WhatsAppPath.init` is not relative path segments. */
final case class WhatsAppInvalidPathException(problem: WhatsAppInvalidPathException.Problem)(using Frame)
    extends WhatsAppException(s"WhatsAppPath is not usable: ${problem.show}.")

object WhatsAppInvalidPathException:
    /** What is wrong with the path. The path itself is not rendered. */
    enum Problem derives CanEqual:
        /** The path is empty. */
        case Empty

        /** The segment at `index` is empty: a leading, trailing or doubled `/`. */
        case EmptySegment(index: Int)

        /** The segment at `index` is `.` or `..`, which moves the request. */
        case DotSegment(index: Int)

        /** The character at `position` is not an ASCII letter, a digit, `.`, `_`, `-` or `/`. */
        case Character(position: Int)

        private[kyo] def show: String =
            this match
                case Empty               => "the path is empty"
                case EmptySegment(index) => s"segment $index is empty"
                case DotSegment(index)   => s"segment $index is . or .."
                case Character(position) => s"the character at position $position is not one of A-Z a-z 0-9 . _ - /"
    end Problem
end WhatsAppInvalidPathException
