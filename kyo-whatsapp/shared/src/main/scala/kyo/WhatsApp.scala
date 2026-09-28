package kyo

import kyo.internal.whatsapp.Codec as WhatsAppCodec
import kyo.internal.whatsapp.Graph

/** The client of the WhatsApp Business Cloud API, the value every verb requires: `Env[WhatsApp]` is on each verb's row, so a verb used
  * outside a region that provides a client does not compile.
  *
  * Only the module builds one, from a [[kyo.WhatsAppConfig]]: `WhatsApp.let(config)(v)` provides a client to `v`, and
  * `WhatsAppWebhook.handler(config, webhook)(f)` provides one to every call of `f`. A client holds the config and its own kyo-http
  * `HttpClient` (never the caller's, so no caller filter, base url or relaxed TLS reaches a request that carries the token), released
  * when its region ends. There is no `run` and no `init`: inbound arrives by webhook, and the client holds no connection of its own.
  *
  * The verbs are functions on the companion: `send`, `sendTemplate`, `markRead`, `markReadWithTyping` and `custom` here, and the media
  * verbs on [[kyo.WhatsAppMedia]], whose API has its own host and lifecycle. A client is never a receiver of calls: it carries nothing a
  * caller reads, and it cannot be constructed outside the module.
  *
  * @see
  *   [[kyo.WhatsAppConfig]] the config
  * @see
  *   [[kyo.WhatsAppWebhook]] the inbound webhook
  * @see
  *   [[kyo.WhatsAppException]] the failures
  */
final class WhatsApp private[kyo] (private[kyo] val config: WhatsAppConfig, private[kyo] val http: HttpClient)

/** The entry point and verbs of kyo-whatsapp. */
object WhatsApp:

    /** Builds a client on `config` for the duration of `v`, releasing it afterwards. Building it cannot fail: it opens no connection. */
    def let[A, S](config: WhatsAppConfig)(v: A < (S & Env[WhatsApp]))(using Frame): A < (S & Async) =
        Scope.run(client(config).map(whatsApp => Env.run(whatsApp)(v)))

    /** A client on `config` whose `HttpClient` closes with the enclosing `Scope`. */
    private[kyo] def client(config: WhatsAppConfig)(using Frame): WhatsApp < (Async & Scope) =
        HttpClient.init(defaultTlsConfig = HttpTlsConfig.default).map(new WhatsApp(config, _))

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
        query: Seq[(String, String)] = Seq.empty,
        body: Maybe[In] = Absent
    )(using Frame): Out < (Async & Abort[WhatsAppCustomFailure] & Env[WhatsApp]) =
        Env.use[WhatsApp] { client =>
            val url     = WhatsAppConfig.versioned(client.config, path.value, query)
            val request = bearer(client.config).foldLeft(HttpRequest(method, url))((r, h) => r.addHeader(h._1, h._2))
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
                headers = bearer(client.config) :+ ("Content-Type" -> "application/json"),
                failOnError = false
            )
        }
    end postMessages

    private[kyo] def bearer(config: WhatsAppConfig): Seq[(String, String)] =
        Seq("Authorization" -> s"Bearer ${config.token.value}")

end WhatsApp
