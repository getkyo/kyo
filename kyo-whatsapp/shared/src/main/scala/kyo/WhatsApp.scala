package kyo

import kyo.internal.whatsapp.Codec as WhatsAppCodec

/** Outbound client facade for the WhatsApp Business Cloud API. Bind a `WhatsAppConfig` with
  * `let` and call `send`, `sendTemplate`, `markRead`, `markReadWithTyping`, or `custom` inside
  * the block. Each method reads the active `WhatsAppConfig`, encodes the request body via the
  * internal codec, issues an authenticated HTTP call via `HttpClient`, and decodes the response
  * or maps any Graph API error envelope to a typed `WhatsAppError` leaf.
  *
  * The public surface is split one-type-per-file, WhatsApp-prefixed, in package `kyo`: the
  * message, interactive, template, contact, media, notification, webhook, config, send-result,
  * and id types are sibling top-level types (`WhatsAppMessage`, `WhatsAppInteractive`,
  * `WhatsAppTemplate`, `WhatsAppContact`, `WhatsAppMedia`, `WhatsAppNotification`,
  * `WhatsAppWebhook`, `WhatsAppConfig`, `WhatsAppSendResult`, `WhatsAppId`), reachable with
  * `import kyo.*`. This object is the facade verbs only.
  *
  * Capabilities:
  *   - Send any non-template message (text, media, location, contacts, reaction, interactive)
  *   - Send template messages for messaging outside the 24-hour service window
  *   - Mark inbound messages as read, optionally with a typing indicator
  *   - Issue arbitrary Graph API requests via the `custom` escape hatch
  *
  * `WhatsApp` deliberately has no `run`/`init`: the outbound client is stateless and has no
  * resource of its own to open, and inbound is HTTP webhooks returned as `HttpHandler` values
  * by `WhatsAppWebhook`. The `let`/`use` ambient-context pair is the matching kyo convention,
  * mirroring `HttpClient.let`.
  *
  * Client operations read the active `WhatsAppConfig`. Nest calls inside `WhatsApp.let(config)`
  * to bind a config explicitly; when no config is bound, `use` falls back to a default
  * `WhatsAppConfig` loaded from `kyo.StaticFlag` values (system properties
  * `kyo.WhatsApp.flags.token`, `kyo.WhatsApp.flags.phoneNumberId`, `kyo.WhatsApp.flags.apiVersion`,
  * `kyo.WhatsApp.flags.baseUrl`, or the corresponding `KYO_WHATSAPP_FLAGS_*` environment
  * variables). An unset token yields an empty token, so the Graph call returns a typed auth
  * error rather than crashing.
  */
object WhatsApp:

    /** Binds the active WhatsAppConfig for the duration of the given computation. Mirrors
      * `HttpClient.let`; nesting is allowed and the inner binding shadows the outer one.
      */
    def let[A, S](config: WhatsAppConfig)(v: A < S)(using Frame): A < S =
        local.let(Present(config))(v)

    /** Reads the active WhatsAppConfig and passes it to the given function. When no config is
      * bound via `WhatsApp.let`, falls back to a default config loaded from `kyo.StaticFlag`
      * values (system properties `kyo.WhatsApp.flags.token`, `kyo.WhatsApp.flags.phoneNumberId`,
      * `kyo.WhatsApp.flags.apiVersion`, `kyo.WhatsApp.flags.baseUrl`, or the matching
      * `KYO_WHATSAPP_FLAGS_*` environment variables). An unset token yields an empty token, so
      * the Graph call returns a typed auth error rather than crashing.
      */
    def use[A, S](f: WhatsAppConfig => A < S)(using Frame): A < S =
        local.use { mc =>
            mc match
                case Present(c) => f(c)
                case Absent     => f(defaultConfig)
        }

    /** Sends any non-template outbound message. `replyTo` carries the optional reply context
      * (the Cloud API `context.message_id` field). Returns the decoded send response containing
      * the new message WAMID and the resolved recipient wa_id. Aborts with a typed
      * `WhatsAppError` leaf on Graph API errors, transport failures, or decode failures.
      */
    def send(to: WhatsAppId.WaId, message: WhatsAppMessage, replyTo: Maybe[WhatsAppId.MessageId] = Absent)(
        using Frame
    ): WhatsAppSendResult < (Async & Abort[WhatsAppError]) =
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
    def sendTemplate(to: WhatsAppId.WaId, template: WhatsAppTemplate, replyTo: Maybe[WhatsAppId.MessageId] = Absent)(
        using Frame
    ): WhatsAppSendResult < (Async & Abort[WhatsAppError]) =
        use(c =>
            postEnvelope(c, WhatsAppCodec.encodeTemplate(to, template, replyTo))(
                WhatsAppCodec.decodeSendResult
            )
        )

    /** Marks an inbound message as read. Posts the status:read envelope to the messages
      * endpoint and consumes the `{success:true}` acknowledgment response as `Unit`. Aborts
      * with a typed `WhatsAppError` on any error.
      */
    def markRead(messageId: WhatsAppId.MessageId)(using Frame): Unit < (Async & Abort[WhatsAppError]) =
        use(c =>
            postEnvelope(c, WhatsAppCodec.encodeMarkRead(messageId, typing = false))(
                WhatsAppCodec.decodeSuccess
            )
        )

    /** Marks an inbound message as read and shows a typing indicator. Posts the status:read
      * envelope extended with a `typing_indicator:{type:text}` field. Returns `Unit` on
      * success. Aborts with a typed `WhatsAppError` on any error.
      */
    def markReadWithTyping(messageId: WhatsAppId.MessageId)(using Frame): Unit < (Async & Abort[WhatsAppError]) =
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

    // --- Internal ---

    private val local: Local[Maybe[WhatsAppConfig]] = Local.init(Absent)

    private object flags:
        object token         extends StaticFlag[String]("")
        object phoneNumberId extends StaticFlag[String]("")
        object apiVersion    extends StaticFlag[String]("v25.0")
        object baseUrl       extends StaticFlag[String]("https://graph.facebook.com")
    end flags

    private def defaultConfig: WhatsAppConfig =
        WhatsAppConfig(
            token = flags.token(),
            phoneNumberId = WhatsAppId.PhoneNumberId(flags.phoneNumberId()),
            apiVersion = flags.apiVersion(),
            baseUrl = flags.baseUrl()
        )

    private def messagesUrl(c: WhatsAppConfig): String =
        s"${c.baseUrl}/${c.apiVersion}/${c.phoneNumberId.value}/messages"

    private def bearer(c: WhatsAppConfig): Seq[(String, String)] =
        Seq("Authorization" -> s"Bearer ${c.token}")

    private def postEnvelope[A](c: WhatsAppConfig, bytes: Span[Byte])(
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

end WhatsApp
