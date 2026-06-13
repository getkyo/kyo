package kyo.whatsapp

import kyo.*
import kyo.whatsapp.internal.Codec as WhatsAppCodec

/** Outbound client facade for the WhatsApp Business Cloud API. Bind a `Config` with `let`
  * and call `send`, `sendTemplate`, `markRead`, `markReadWithTyping`, or `custom` inside the
  * block. Each method reads the active `Config`, encodes the request body via the internal
  * codec, issues an authenticated HTTP call via `HttpClient`, and decodes the response or maps
  * any Graph API error envelope to a typed `WhatsAppError` leaf.
  *
  * Capabilities:
  *   - Send any non-template message (text, media, location, contacts, reaction, interactive)
  *   - Send template messages for messaging outside the 24-hour service window
  *   - Mark inbound messages as read, optionally with a typing indicator
  *   - Issue arbitrary Graph API requests via the `custom` escape hatch
  *
  * All operations require a bound `WhatsApp.Config`. Nest calls inside `WhatsApp.let(config)`
  * to bind the config; panics with an `IllegalStateException` when no config is bound.
  */
object WhatsApp:

    /** Account and session state for all outbound calls. Carries the bearer token, the
      * registered phone-number id (the Cloud API path segment), the Graph API version string
      * (default "v25.0"), and the base URL (default "https://graph.facebook.com"). Immutable
      * value with fluent copy-setters; bind into scope with `WhatsApp.let`. The `apiVersion`
      * segment appears in every request URL as `baseUrl/apiVersion/phoneNumberId/messages`.
      * Fluent copy-setters shadow each field so `config.token("newToken")` returns a copy
      * rather than accessing the field.
      */
    final case class Config(
        token: String,
        phoneNumberId: Id.PhoneNumberId,
        apiVersion: String = "v25.0",
        baseUrl: String = "https://graph.facebook.com"
    ):
        def token(value: String): Config                   = copy(token = value)
        def phoneNumberId(value: Id.PhoneNumberId): Config = copy(phoneNumberId = value)
        def apiVersion(value: String): Config              = copy(apiVersion = value)
        def baseUrl(value: String): Config                 = copy(baseUrl = value)
    end Config

    /** The decoded send response: the message WAMID, the resolved recipient wa_id (if
      * present), and the optional `message_status`. The WAMID in `messageId` is the stable
      * identifier used for reply context, reactions, and mark-as-read. `contactWaId` carries
      * the normalized recipient wa_id returned by the API when available. `status` reflects
      * the Cloud API `message_status` field; `Absent` means the API did not include it.
      */
    final case class SendResult(
        messageId: Id.MessageId,
        contactWaId: Maybe[Id.WhatsAppId] = Absent,
        status: Maybe[SendResult.Status] = Absent
    ) derives CanEqual

    object SendResult:
        /** The `message_status` discriminator decoded from the send response. Known values
          * map to typed case objects; any unrecognized string maps to `Other(value)` so a
          * future API value does not silently collapse to a misleading known state.
          *
          * Known values: `Accepted` (request received), `HeldForQualityAssessment` (held for
          * review), `Paused` (sending paused due to quality). Any other `message_status`
          * string the API may introduce maps to `Other(value)`.
          */
        sealed trait Status derives CanEqual
        object Status:
            case object Accepted                  extends Status
            case object HeldForQualityAssessment  extends Status
            case object Paused                    extends Status
            final case class Other(value: String) extends Status derives CanEqual
        end Status
    end SendResult

    private val local: Local[Maybe[Config]] = Local.init(Absent)

    /** Binds the active Config for the duration of the given computation. Mirrors
      * `HttpClient.let`; nesting is allowed and the inner binding shadows the outer one.
      */
    def let[A, S](config: Config)(v: A < S)(using Frame): A < S =
        local.let(Present(config))(v)

    /** Reads the active Config and passes it to the given function. Panics with a clear
      * message when no Config is bound via `WhatsApp.let`; callers that need a softer
      * failure should guard with `WhatsApp.let` before calling any client method.
      */
    def use[A, S](f: Config => A < S)(using Frame): A < S =
        local.use { mc =>
            mc match
                case Present(c) => f(c)
                case Absent     =>
                    // Usage-defect sentinel: a missing Local binding is a programming error
                    // (not a modeled domain failure), so a plain throw is the row-preserving
                    // mechanism here. The kyo runtime surfaces it as Result.Panic at the
                    // effect boundary without requiring Abort[WhatsAppError] in the static row.
                    throw new IllegalStateException(
                        "WhatsApp.use: no WhatsApp.Config bound; wrap the call in WhatsApp.let(config) { ... }"
                    )
        }

    private def messagesUrl(c: Config): String =
        s"${c.baseUrl}/${c.apiVersion}/${c.phoneNumberId.value}/messages"

    private def bearer(c: Config): Seq[(String, String)] =
        Seq("Authorization" -> s"Bearer ${c.token}")

    private def postEnvelope[A](c: Config, bytes: Span[Byte])(
        decode: Span[Byte] => Result[WhatsAppError.DecodeError, A]
    )(using Frame): A < (Async & Abort[WhatsAppError]) =
        Abort.runWith[HttpException](
            HttpClient.postBinary(
                messagesUrl(c),
                bytes,
                headers = bearer(c) :+ ("Content-Type" -> "application/json")
            )
        ) {
            case Result.Success(respBytes) => Abort.get(decode(respBytes))
            case Result.Failure(e)         => Abort.fail(WhatsAppCodec.mapError(e))
            case Result.Panic(ex)          => Abort.get(WhatsAppCodec.mapTransportPanic(ex))
        }

    /** Sends any non-template outbound message. `replyTo` carries the optional reply context
      * (the Cloud API `context.message_id` field). Returns the decoded send response containing
      * the new message WAMID and the resolved recipient wa_id. Aborts with a typed
      * `WhatsAppError` leaf on Graph API errors, transport failures, or decode failures.
      */
    def send(to: Id.WhatsAppId, message: Message, replyTo: Maybe[Id.MessageId] = Absent)(
        using Frame
    ): SendResult < (Async & Abort[WhatsAppError]) =
        use(c =>
            postEnvelope(c, WhatsAppCodec.encodeSend(to, message, replyTo))(
                WhatsAppCodec.decodeSendResult
            )
        )

    /** Sends a template message. Templates are the only message class that can be sent outside
      * the 24-hour conversation window. `replyTo` carries the optional reply context. Returns
      * the decoded send response. Aborts with a typed `WhatsAppError` leaf on Graph API errors,
      * transport failures, or decode failures.
      */
    def sendTemplate(to: Id.WhatsAppId, template: Template, replyTo: Maybe[Id.MessageId] = Absent)(
        using Frame
    ): SendResult < (Async & Abort[WhatsAppError]) =
        use(c =>
            postEnvelope(c, WhatsAppCodec.encodeTemplate(to, template, replyTo))(
                WhatsAppCodec.decodeSendResult
            )
        )

    /** Marks an inbound message as read. Posts the status:read envelope to the messages
      * endpoint and consumes the `{success:true}` acknowledgment response as `Unit`. Aborts
      * with a typed `WhatsAppError` on any error.
      */
    def markRead(messageId: Id.MessageId)(using Frame): Unit < (Async & Abort[WhatsAppError]) =
        use(c =>
            postEnvelope(c, WhatsAppCodec.encodeMarkRead(messageId, typing = false))(
                WhatsAppCodec.decodeSuccess
            )
        )

    /** Marks an inbound message as read and shows a typing indicator. Posts the status:read
      * envelope extended with a `typing_indicator:{type:text}` field. Returns `Unit` on
      * success. Aborts with a typed `WhatsAppError` on any error.
      */
    def markReadWithTyping(messageId: Id.MessageId)(using Frame): Unit < (Async & Abort[WhatsAppError]) =
        use(c =>
            postEnvelope(c, WhatsAppCodec.encodeMarkRead(messageId, typing = true))(
                WhatsAppCodec.decodeSuccess
            )
        )

    /** Forward-compatible escape hatch for Graph API endpoints not yet typed in this module.
      * Appends `path` to `baseUrl/apiVersion` (the caller supplies the leading slash), applies
      * bearer authentication, and dispatches the exact `method` supplied: GET via `getJson`,
      * POST via `postJson`, DELETE via `deleteJson`, PUT and PATCH via `putJson` and
      * `patchJson`. For body-bearing methods with an absent body, no body is sent. Decodes
      * the response as `Out` via its `Schema`. Maps errors through the same typed envelope
      * path as the typed methods.
      */
    def custom[In: Schema, Out: Schema](
        method: HttpMethod,
        path: String,
        body: Maybe[In] = Absent
    )(using Frame): Out < (Async & Abort[WhatsAppError]) =
        use { c =>
            val url = s"${c.baseUrl}/${c.apiVersion}$path"
            val call: Out < (Async & Abort[HttpException]) =
                if method == HttpMethod.GET then
                    HttpClient.getJson[Out](url, headers = bearer(c))
                else if method == HttpMethod.DELETE then
                    HttpClient.deleteJson[Out](url, headers = bearer(c))
                else if method == HttpMethod.PUT then
                    body match
                        case Present(b) => HttpClient.putJson[Out](url, b, headers = bearer(c))
                        case Absent     => HttpClient.putJson[Out](url, (), headers = bearer(c))
                else if method == HttpMethod.PATCH then
                    body match
                        case Present(b) => HttpClient.patchJson[Out](url, b, headers = bearer(c))
                        case Absent     => HttpClient.patchJson[Out](url, (), headers = bearer(c))
                else
                    body match
                        case Present(b) => HttpClient.postJson[Out](url, b, headers = bearer(c))
                        case Absent     => HttpClient.postJson[Out](url, (), headers = bearer(c))
            Abort.runWith[HttpException](call) {
                case Result.Success(out) => out
                case Result.Failure(e)   => Abort.fail(WhatsAppCodec.mapError(e))
                case Result.Panic(ex)    => Abort.get(WhatsAppCodec.mapTransportPanic(ex))
            }
        }

end WhatsApp
