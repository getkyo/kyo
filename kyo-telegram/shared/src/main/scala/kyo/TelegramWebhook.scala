package kyo

import java.nio.charset.StandardCharsets.UTF_8
import kyo.internal.telegram.WireCodec

/** Receiving updates by webhook: an `HttpHandler` the bot mounts on its own `HttpServer`, which Telegram
  * POSTs every update to once `Telegram.setWebhook` registered its URL.
  *
  * `handler` checks the `X-Telegram-Bot-Api-Secret-Token` header against the [[kyo.TelegramWebhookConfig]]'s
  * secret, the one `setWebhook` registered, decodes the body into a [[kyo.TelegramUpdate]], and runs the
  * callback with a client built from the [[kyo.TelegramConfig]], so the callback calls the verbs
  * directly. The comparison is in constant time, so the time of a refusal does not reveal how much of
  * the secret matched. `verify` and `decode` are the two steps on their own, for a bot that serves the
  * endpoint itself.
  *
  * Building the handler is an effect: it opens the client once, in the enclosing `Scope` (the one the
  * `HttpServer` runs in), and every delivery's callback runs on that client. Its connections close when
  * the `Scope` does.
  *
  * What the handler answers decides what Telegram does next. A request without the right secret gets
  * 403 and is not decoded. A body that is not an update gets 200 and a log line, since Telegram would
  * redeliver it forever otherwise. A callback that fails or panics gets 500, and Telegram redelivers the
  * update a bounded number of times, so the callback deduplicates by `update.id`.
  *
  * IMPORTANT: Telegram keeps at most `maxConnections` deliveries open at a time (40 by default, set by
  * `TelegramWebhookOptions`), so callbacks can run concurrently and a slow one holds a delivery open.
  *
  * @see
  *   [[kyo.Telegram.setWebhook]] registering the URL
  * @see
  *   [[kyo.Telegram.run]] the alternative, long polling
  */
object TelegramWebhook:

    /** The header Telegram sends the secret in. */
    inline val SecretHeader = "X-Telegram-Bot-Api-Secret-Token"

    /** An `HttpHandler` for POSTs at `webhook.path` that verifies, decodes and runs `f` on each update, with the
      * client built from `config` provided to `f`. The client is built once, here, and closes with the enclosing `Scope`.
      */
    def handler[E](config: TelegramConfig, webhook: TelegramWebhookConfig)(
        f: TelegramUpdate => Unit < (Async & Abort[E] & Env[Telegram])
    )(using Frame): HttpHandler["body" ~ Span[Byte], Any, E] < (Async & Scope) =
        Telegram.client(config).map { telegram =>
            HttpRoute.postRaw(webhook.path).request(_.bodyBinary).handler[E] { req =>
                verify(webhook, req.headers.get(SecretHeader)) match
                    case Result.Success(_) =>
                        // The callback runs after the decode result is matched, outside this Abort.run, so a failure of the caller's own is
                        // never taken for the module's decode failure.
                        Abort.run[TelegramWebhookDecodeFailure](decode(req.fields.body)).map {
                            case Result.Success(update)                     => Env.run(telegram)(f(update)).andThen(HttpResponse.ok)
                            case Result.Failure(e: TelegramDecodeException) =>
                                Log.warn(s"Telegram webhook acknowledged a body that is not an ${e.part.show}").andThen(HttpResponse.ok)
                            case Result.Panic(ex) => Abort.panic(ex)
                        }
                    case Result.Failure(_: TelegramWebhookVerifyFailure) => HttpResponse.halt(HttpResponse.forbidden)
                    case Result.Panic(ex)                                => Abort.panic(ex)
            }
        }

    /** Checks the secret header against `webhook.secret`, in constant time over the UTF-8 bytes. */
    def verify(webhook: TelegramWebhookConfig, header: Maybe[String])(using Frame): Result[TelegramWebhookVerifyFailure, Unit] =
        header match
            case Absent         => Result.fail(TelegramSecretTokenMissingException())
            case Present(value) =>
                if Span.from(value.getBytes(UTF_8)).constantTimeEquals(Span.from(webhook.secret.value.getBytes(UTF_8))) then Result.unit
                else Result.fail(TelegramSecretTokenMismatchException())

    /** Decodes a webhook body into the update it carries. */
    def decode(body: Span[Byte])(using Frame): TelegramUpdate < Abort[TelegramWebhookDecodeFailure] =
        val decoded: WireCodec.Decoded[TelegramUpdate] = Json.decodeBytes[Structure.Value](body).flatMap(WireCodec.decodeUpdate)
        decoded match
            case Result.Success(update)  => update
            case Result.Failure(failure) =>
                Abort.fail(TelegramDecodeException.ofDecoded(DecodeMethod, TelegramDecodeException.Part.Update, failure))
            case Result.Panic(ex) => Abort.panic(ex)
        end match
    end decode

    private inline val DecodeMethod = "webhook"

end TelegramWebhook
