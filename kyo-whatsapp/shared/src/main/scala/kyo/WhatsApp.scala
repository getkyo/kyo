package kyo

import kyo.crypto.ConstantTime
import kyo.crypto.Hmac
import kyo.internal.charset.Utf8
import kyo.internal.whatsapp.Codec as WhatsAppCodec
import kyo.internal.whatsapp.Graph

/** The client of the WhatsApp Business Cloud API, the value every verb requires: `Env[WhatsApp]` is on each verb's row, so a verb used
  * outside a region that provides a client does not compile.
  *
  * Only the module builds one, from a [[kyo.WhatsAppConfig]]. `WhatsApp.run(config)(v)` builds a client for the duration of `v`;
  * `init` builds one closed with the enclosing `Scope`, `initUnscoped` one closed only by `close`, and `run(client)(v)` provides a built
  * client to `v`. `WhatsApp.Webhook.handler(webhook)(f)` hands the client of its caller's region to every call of `f`. A client holds the
  * config and its own kyo-http `HttpClient` (never the caller's, so no caller filter, base url or relaxed TLS reaches a request that
  * carries the token). Inbound arrives by webhook, so a client holds no connection of its own and building one cannot fail.
  *
  * The verbs are functions on the companion: `send`, `sendTemplate`, `markRead`, `markReadWithTyping` and `custom` here, and the media
  * verbs on [[kyo.WhatsAppMedia]], whose API has its own host and lifecycle. A client is never a receiver of calls: it carries nothing a
  * caller reads, and it cannot be constructed outside the module.
  *
  * @see
  *   [[kyo.WhatsAppConfig]] the config
  * @see
  *   [[kyo.WhatsApp.Webhook]] the inbound webhook
  * @see
  *   [[kyo.WhatsAppException]] the failures
  */
final class WhatsApp private[kyo] (private[kyo] val config: WhatsAppConfig, private[kyo] val http: HttpClient)

/** The entry point and verbs of kyo-whatsapp. */
object WhatsApp:

    /** Builds a client on `config` for the duration of `v`, closing it afterwards. */
    def run[A, S](config: WhatsAppConfig)(v: A < (S & Env[WhatsApp]))(using Frame): A < (S & Async) =
        Scope.run(init(config).map(client => run(client)(v)))

    /** Provides `client` to `v`. The client stays open; it is closed by its `Scope` (`init`) or by `close` (`initUnscoped`). */
    def run[A, S](client: WhatsApp)(v: A < (S & Env[WhatsApp]))(using Frame): A < S =
        Env.run(client)(v)

    /** A client on `config`, closed when the enclosing `Scope` ends. Building it cannot fail: the client opens no connection until a verb
      * sends a request.
      */
    def init(config: WhatsAppConfig)(using Frame): WhatsApp < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(config))(close)

    /** A client on `config`, which nothing closes but the caller's `close`: the form for a client whose lifetime no `Scope` describes.
      * Prefer `init`.
      */
    def initUnscoped(config: WhatsAppConfig)(using Frame): WhatsApp < Sync =
        HttpClient.initUnscoped(defaultTlsConfig = HttpTlsConfig.default).map(new WhatsApp(config, _))

    /** Closes `client`'s `HttpClient` without waiting for requests in flight; a verb called on it afterwards fails. Idempotent. */
    def close(client: WhatsApp)(using Frame): Unit < Async =
        client.http.closeNow

    /** Sends any non-template message. `replyTo` is the optional reply context (the Cloud API's `context.message_id`). Answers the new
      * message's id and the recipient's resolved `wa_id`.
      */
    def send(to: WhatsAppId.WaId, message: WhatsAppMessage, replyTo: Maybe[WhatsAppId.MessageId] = Absent)(
        using Frame
    ): WhatsAppSendResult < (Async & Abort[WhatsAppSendFailure] & Env[WhatsApp]) =
        Env.use[WhatsApp] { client =>
            postMessages[WhatsAppSendFailure](client, SendMethod, WhatsAppCodec.encodeSend(to, message, replyTo))
                .map(body => Abort.get(WhatsAppCodec.decodeSendResult(SendMethod, body)))
        }

    /** Sends a template message, the only message class that can be sent outside the 24-hour conversation window. */
    def sendTemplate(to: WhatsAppId.WaId, template: WhatsAppTemplate, replyTo: Maybe[WhatsAppId.MessageId] = Absent)(
        using Frame
    ): WhatsAppSendResult < (Async & Abort[WhatsAppSendTemplateFailure] & Env[WhatsApp]) =
        Env.use[WhatsApp] { client =>
            postMessages[WhatsAppSendTemplateFailure](client, SendTemplateMethod, WhatsAppCodec.encodeTemplate(to, template, replyTo))
                .map(body => Abort.get(WhatsAppCodec.decodeSendResult(SendTemplateMethod, body)))
        }

    /** Marks an inbound message as read. */
    def markRead(messageId: WhatsAppId.MessageId)(using Frame): Unit < (Async & Abort[WhatsAppMarkReadFailure] & Env[WhatsApp]) =
        markReadWith(messageId, typing = false)

    /** Marks an inbound message as read and shows a typing indicator. It fails as `markRead` does, since it is the same request. */
    def markReadWithTyping(messageId: WhatsAppId.MessageId)(using
        Frame
    ): Unit < (Async & Abort[WhatsAppMarkReadFailure] & Env[WhatsApp]) =
        markReadWith(messageId, typing = true)

    /** Calls a Graph API endpoint the module does not model: `method` to `{baseUrl}/{apiVersion}/{path}`, with `query` percent-encoded by
      * the module and the bearer token. Each method is sent as itself. POST, PUT and PATCH send `body` as JSON, or the JSON encoding of
      * `()` when it is absent; every other method sends no body, and `body` is not used. The answer is decoded as `Out`. Only the Graph
      * errors that mean the same on every endpoint are named; any other code is `WhatsAppOtherApiException`.
      */
    def custom[In: Schema, Out: Schema](
        method: HttpMethod,
        path: WhatsAppPath,
        query: HttpQueryParams = HttpQueryParams.empty,
        body: Maybe[In] = Absent
    )(using Frame): Out < (Async & Abort[WhatsAppCustomFailure] & Env[WhatsApp]) =
        Env.use[WhatsApp] { client =>
            val url     = WhatsAppConfig.versioned(client.config, path.value, query)
            val request = HttpRequest(method, url).addHeader("Authorization", bearerValue(client.config))
            val route   = HttpRoute[Any, Any, Nothing](method, HttpRoute.RequestDef[Any](""))
            Graph.call[WhatsAppCustomFailure](client, CustomMethod, url) { http =>
                if method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH then
                    val payload = body.map(Json.encodeBytes(_)).getOrElse(Json.encodeBytes(()))
                    http.sendWith(
                        route.request(_.bodyBinary).response(_.bodyBinary),
                        request.addHeader("Content-Type", "application/json").addField("body", payload)
                    )(identity)
                else http.sendWith(route.response(_.bodyBinary), request)(identity)
            }.map(bytes => Abort.get(WhatsAppCodec.decodeCustom[Out](CustomMethod, bytes)))
        }

    /** Receiving notifications by webhook: the `HttpHandler`s a bot mounts on its own `HttpServer` at `webhook.path`.
      *
      *   - `verificationHandler` answers Meta's registration handshake, a GET echoing `hub.challenge` when `hub.verify_token` matches the
      *     [[kyo.WhatsAppWebhookConfig]]'s verify token (compared in constant time), and 403 otherwise.
      *   - `handler` verifies each POST's `X-Hub-Signature-256` against the app secret, decodes its body, and runs the callback on every
      *     notification with the caller's client, so the callback calls the verbs directly.
      *   - `verify` and `decode` are the two steps on their own, for a bot that serves the endpoint itself.
      *
      * `handler` requires `Env[WhatsApp]` and every delivery's callback runs on that client, so the server is served inside the client's
      * region: `WhatsApp.run(config)(WhatsApp.Webhook.handler(webhook)(f).map(h => HttpServer.init(...)(h).map(_.await)))`. A handler
      * returned out of the region holds a closed client.
      *
      * What the handler answers decides what Meta does next. A request without a matching signature gets 403 and is not decoded. A body
      * that is not a notification envelope gets 200 and a log line naming the failure, since Meta would redeliver it forever otherwise. A
      * callback that fails or panics gets 500, and Meta redelivers the whole POST: the notifications already processed arrive again, so the
      * callback deduplicates by the message or status id each one carries.
      *
      * @see
      *   [[kyo.WhatsAppNotification]] what the callback receives
      */
    object Webhook:

        /** The header Meta signs a POST in. Public because a bot that serves the endpoint itself reads this header to call `verify`. */
        inline val SignatureHeader = "X-Hub-Signature-256"

        /** A GET `HttpHandler` at `webhook.path` for the registration handshake: 200 with `hub.challenge` as the body on a verify token
          * match, 403 otherwise. The echoed token is compared with the configured one in constant time over their UTF-8 bytes, so the time
          * of a 403 does not reveal how long a prefix matched.
          */
        def verificationHandler(webhook: WhatsAppWebhookConfig)(using
            Frame
        ): HttpHandler[
            "mode" ~ Maybe[String] & "token" ~ Maybe[String] & "challenge" ~ Maybe[String],
            "body" ~ String,
            Nothing
        ] =
            val expected = Utf8.encode(webhook.verifyToken.value)
            HttpRoute.getRaw(webhook.path)
                .request(_.queryOpt[String]("mode", wireName = "hub.mode"))
                .request(_.queryOpt[String]("token", wireName = "hub.verify_token"))
                .request(_.queryOpt[String]("challenge", wireName = "hub.challenge"))
                .response(_.bodyText)
                .handler[Nothing] { req =>
                    (req.fields.mode, req.fields.token, req.fields.challenge) match
                        case (Present("subscribe"), Present(t), Present(c)) if ConstantTime.isEqual(Utf8.encode(t), expected) =>
                            HttpResponse.ok(c)
                        case _ =>
                            HttpResponse.halt(HttpResponse.forbidden)
                }
        end verificationHandler

        /** A POST `HttpHandler` at `webhook.path` that verifies, decodes and runs `f` on each notification, with the caller's client
          * provided to `f`. `f` runs on the server's fibers, after this call returns, so the handler holds that client: the server must be
          * served within the client's region (`WhatsApp.run`), whose end closes it.
          *
          * The handler's error type is `f`'s own `E` and nothing of the module's: a signature failure halts with 403 and a decode failure
          * is acknowledged, so neither reaches the error channel.
          */
        def handler[E](webhook: WhatsAppWebhookConfig)(
            f: WhatsAppNotification => Unit < (Async & Abort[E] & Env[WhatsApp])
        )(using Frame): HttpHandler["body" ~ Span[Byte], Any, E] < Env[WhatsApp] =
            Env.use[WhatsApp] { whatsApp =>
                HttpRoute.postRaw(webhook.path).request(_.bodyBinary).handler[E] { req =>
                    val body = req.fields.body
                    verify(webhook, req.headers.get(SignatureHeader), body) match
                        case Result.Success(_) =>
                            // The callback runs after the decode result is matched, outside this Abort.run, so a failure of the caller's
                            // own is never taken for the module's decode failure.
                            Abort.run[WhatsAppWebhookDecodeFailure](decode(body)).map {
                                case Result.Success(notifications) =>
                                    Kyo.foreach(notifications)(n => Env.run(whatsApp)(f(n))).andThen(HttpResponse.ok)
                                case Result.Failure(e: WhatsAppDecodeException) =>
                                    Log.warn(s"WhatsApp webhook acknowledged a body it cannot decode: ${e.show}").andThen(HttpResponse.ok)
                                case Result.Panic(ex) => Abort.panic(ex)
                            }
                        case Result.Failure(_: WhatsAppWebhookVerifyFailure) => HttpResponse.halt(HttpResponse.forbidden)
                        case Result.Panic(ex)                                => Abort.panic(ex)
                    end match
                }
            }

        /** Checks the `X-Hub-Signature-256` header against the lowercase-hex HMAC-SHA256 of the raw body under the app secret, in constant
          * time. Total and pure.
          */
        def verify(webhook: WhatsAppWebhookConfig, signatureHeader: Maybe[String], body: Span[Byte])(using
            Frame
        ): Result[WhatsAppWebhookVerifyFailure, Unit] =
            signatureHeader match
                case Absent          => Result.fail(WhatsAppSignatureMissingException())
                case Present(header) =>
                    if !header.startsWith("sha256=") then Result.fail(WhatsAppSignatureMalformedException())
                    else
                        Hex.decode(header.substring("sha256=".length)) match
                            case Result.Success(tag) if !tag.isEmpty =>
                                if Hmac.verifySha256(Utf8.encode(webhook.appSecret.value), body, tag) then Result.unit
                                else Result.fail(WhatsAppSignatureMismatchException())
                            case _ => Result.fail(WhatsAppSignatureMalformedException())

        /** Decodes a verified body into its notifications, one per message and per status of each `entry[].changes[]`. An unknown type,
          * status or change, and a message, status or change that does not decode, is an `Unknown` case, so the rest of the POST is still
          * delivered; only a body that is not a notification envelope fails.
          */
        def decode(body: Span[Byte])(using Frame): Chunk[WhatsAppNotification] < Abort[WhatsAppWebhookDecodeFailure] =
            Abort.get(WhatsAppCodec.decodeNotifications(body))

    end Webhook

    private[kyo] inline val SendMethod         = "send"
    private[kyo] inline val SendTemplateMethod = "sendTemplate"
    private[kyo] inline val MarkReadMethod     = "markRead"
    private[kyo] inline val CustomMethod       = "custom"

    private def markReadWith(messageId: WhatsAppId.MessageId, typing: Boolean)(using
        Frame
    ): Unit < (Async & Abort[WhatsAppMarkReadFailure] & Env[WhatsApp]) =
        Env.use[WhatsApp] { client =>
            postMessages[WhatsAppMarkReadFailure](client, MarkReadMethod, WhatsAppCodec.encodeMarkRead(messageId, typing))
                .map(body => Abort.get(WhatsAppCodec.decodeSuccess(MarkReadMethod, body)))
        }

    private def postMessages[F >: Graph.Common](client: WhatsApp, method: String, bytes: Span[Byte])(using
        Frame,
        scala.reflect.TypeTest[WhatsAppException, F]
    ): Span[Byte] < (Async & Abort[F]) =
        val url = WhatsAppConfig.versioned(client.config, s"${client.config.phoneNumberId.value}/messages")
        Graph.call[F](client, method, url) { _ =>
            HttpClient.postBinaryResponse(
                url,
                bytes,
                headers = bearer(client.config).add("Content-Type", "application/json"),
                failOnError = false
            )
        }
    end postMessages

    private[kyo] def bearer(config: WhatsAppConfig): HttpHeaders =
        HttpHeaders.empty.add("Authorization", bearerValue(config))

    private[kyo] def bearerValue(config: WhatsAppConfig): String = s"Bearer ${config.token.value}"

end WhatsApp
