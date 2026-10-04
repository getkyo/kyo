package kyo

/** What the webhook verifies deliveries with and where it is mounted: the secret Telegram sends in every
  * request's `X-Telegram-Bot-Api-Secret-Token` header, and the path of the route on the bot's server.
  *
  * The same value registers the webhook (`Telegram.setWebhook`, which sends the secret to Telegram) and
  * serves it (`Telegram.Webhook.handler`, which checks it), so the two cannot disagree. It is separate from
  * [[kyo.TelegramConfig]] because the secret is not the bot token: Telegram's server sends it, and only
  * the webhook reads it.
  *
  * `path` is split on `/` by the route, so a leading slash is optional. A character no request path can
  * carry (`?`, `#`, a space, a control character, anything outside ASCII) would mount a route no request
  * reaches, so `init` refuses it with [[kyo.TelegramInvalidWebhookConfigException]].
  *
  * Note: `toString` does not render the secret, whose own `toString` is redacted.
  *
  * @see
  *   [[kyo.Telegram.Webhook.handler]] the route that checks it
  * @see
  *   [[kyo.Telegram.setWebhook]] the registration that sends it
  */
final case class TelegramWebhookConfig private[kyo] (secret: Telegram.SecretToken, path: String) derives CanEqual

object TelegramWebhookConfig:

    /** The webhook config, or a [[kyo.TelegramInvalidWebhookConfigException]] when `path` holds a character no request path carries. */
    def init(secret: Telegram.SecretToken, path: String = "")(using
        Frame
    ): Result[TelegramInvalidWebhookConfigException, TelegramWebhookConfig] =
        badPathCharacter(path) match
            case Present(at) =>
                Result.fail(TelegramInvalidWebhookConfigException(TelegramInvalidWebhookConfigException.Problem.PathCharacter(at)))
            case Absent => Result.succeed(new TelegramWebhookConfig(secret, path))

    private def badPathCharacter(path: String): Maybe[Int] =
        val at = path.indexWhere(c => c <= ' ' || c > '~' || c == '?' || c == '#')
        if at >= 0 then Present(at) else Absent
end TelegramWebhookConfig
