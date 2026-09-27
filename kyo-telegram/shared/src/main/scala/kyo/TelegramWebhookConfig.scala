package kyo

/** What the webhook verifies deliveries with and where it is mounted: the secret Telegram sends in every
  * request's `X-Telegram-Bot-Api-Secret-Token` header, and the path of the route on the bot's server.
  *
  * The same value registers the webhook (`Telegram.setWebhook`, which sends the secret to Telegram) and
  * serves it (`TelegramWebhook.handler`, which checks it), so the two cannot disagree. It is separate from
  * [[kyo.TelegramConfig]] because the secret is not the bot token: Telegram's server sends it, and only
  * the webhook reads it.
  *
  * `path` is split on `/` by the route, so a leading slash is optional. A character no request path can
  * carry (`?`, `#`, a space, a control character, anything outside ASCII) would mount a route no request
  * reaches, so it panics with [[kyo.TelegramInvalidWebhookConfigException]] at construction.
  *
  * Note: `toString` does not render the secret, whose own `toString` is redacted.
  *
  * @see
  *   [[kyo.TelegramWebhook.handler]] the route that checks it
  * @see
  *   [[kyo.Telegram.setWebhook]] the registration that sends it
  */
final case class TelegramWebhookConfig(secret: TelegramSecretToken, path: String = "")(using Frame) derives CanEqual:
    TelegramWebhookConfig.badPathCharacter(path).foreach(at =>
        throw TelegramInvalidWebhookConfigException(TelegramInvalidWebhookConfigException.Problem.PathCharacter(at))
    )
end TelegramWebhookConfig

object TelegramWebhookConfig:
    private def badPathCharacter(path: String): Maybe[Int] =
        val at = path.indexWhere(c => c <= ' ' || c > '~' || c == '?' || c == '#')
        if at >= 0 then Present(at) else Absent
end TelegramWebhookConfig
