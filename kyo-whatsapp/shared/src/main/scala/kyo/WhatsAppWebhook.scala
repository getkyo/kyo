package kyo

import kyo.crypto.ConstantTime
import kyo.crypto.Hmac
import kyo.internal.charset.Utf8
import kyo.internal.whatsapp.Codec as WhatsAppCodec

/** Receiving notifications by webhook: the `HttpHandler`s a bot mounts on its own `HttpServer` at `webhook.path`.
  *
  *   - `verificationHandler` answers Meta's registration handshake, a GET echoing `hub.challenge` when `hub.verify_token` matches the
  *     [[kyo.WhatsAppWebhookConfig]]'s verify token (compared in constant time), and 403 otherwise.
  *   - `handler` verifies each POST's `X-Hub-Signature-256` against the app secret, decodes its body, and runs the callback on every
  *     notification with the caller's client, so the callback calls the verbs directly.
  *   - `verify` and `decode` are the two steps on their own, for a bot that serves the endpoint itself.
  *
  * `handler` requires `Env[WhatsApp]` and every delivery's callback runs on that client, so the server is served inside the client's
  * region: `WhatsApp.run(config)(WhatsAppWebhook.handler(webhook)(f).map(h => HttpServer.init(...)(h).map(_.await)))`. A handler
  * returned out of the region holds a closed client.
  *
  * What the handler answers decides what Meta does next. A request without a matching signature gets 403 and is not decoded. A body that
  * is not a notification envelope gets 200 and a log line naming the failure, since Meta would redeliver it forever otherwise. A callback
  * that fails or panics gets 500, and Meta redelivers the whole POST: the notifications already processed arrive again, so the callback
  * deduplicates by the message or status id each one carries.
  *
  * @see
  *   [[kyo.WhatsAppNotification]] what the callback receives
  */
object WhatsAppWebhook:

    /** The header Meta signs a POST in. Public because a bot that serves the endpoint itself reads this header to call `verify`. */
    inline val SignatureHeader = "X-Hub-Signature-256"

    /** A GET `HttpHandler` at `webhook.path` for the registration handshake: 200 with `hub.challenge` as the body on a verify token match,
      * 403 otherwise. The echoed token is compared with the configured one in constant time over their UTF-8 bytes, so the time of a 403
      * does not reveal how long a prefix matched.
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

    /** A POST `HttpHandler` at `webhook.path` that verifies, decodes and runs `f` on each notification, with the caller's client provided
      * to `f`. `f` runs on the server's fibers, after this call returns, so the handler holds that client: the server must be served
      * within the client's region (`WhatsApp.run`), whose end closes it.
      *
      * The handler's error type is `f`'s own `E` and nothing of the module's: a signature failure halts with 403 and a decode failure is
      * acknowledged, so neither reaches the error channel.
      */
    def handler[E](webhook: WhatsAppWebhookConfig)(
        f: WhatsAppNotification => Unit < (Async & Abort[E] & Env[WhatsApp])
    )(using Frame): HttpHandler["body" ~ Span[Byte], Any, E] < Env[WhatsApp] =
        Env.use[WhatsApp] { whatsApp =>
            HttpRoute.postRaw(webhook.path).request(_.bodyBinary).handler[E] { req =>
                val body = req.fields.body
                verify(webhook, req.headers.get(SignatureHeader), body) match
                    case Result.Success(_) =>
                        // The callback runs after the decode result is matched, outside this Abort.run, so a failure of the caller's own is
                        // never taken for the module's decode failure.
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

end WhatsAppWebhook
