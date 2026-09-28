package kyo

/** What the webhook verifies deliveries with and where it is mounted: the app secret that keys the `X-Hub-Signature-256` HMAC of every
  * POST, the verify token Meta echoes in the registration handshake, and the path of the routes on the caller's server.
  *
  * It is separate from [[kyo.WhatsAppConfig]] because neither secret is the access token: Meta signs with the app secret and echoes the
  * verify token, and only the webhook reads them. They are two different credentials, entered in two places of the Meta dashboard.
  *
  * `path` is split on `/` by the route, so a leading slash is optional. A character no request path can carry (`?`, `#`, a space, a
  * control character, anything outside ASCII) would mount a route no request reaches, so it panics with
  * [[kyo.WhatsAppInvalidWebhookConfigException]] at construction.
  *
  * Note: `toString` does not render either secret, whose own `toString` is redacted.
  *
  * @see
  *   [[kyo.WhatsAppWebhook.handler]] the POST route that verifies with it
  * @see
  *   [[kyo.WhatsAppWebhook.verificationHandler]] the GET handshake that answers with it
  */
final case class WhatsAppWebhookConfig(appSecret: WhatsAppAppSecret, verifyToken: WhatsAppVerifyToken, path: String = "")(using Frame)
    derives CanEqual:
    WhatsAppWebhookConfig.badPathCharacter(path).foreach(at =>
        throw WhatsAppInvalidWebhookConfigException(WhatsAppInvalidWebhookConfigException.Problem.PathCharacter(at))
    )
end WhatsAppWebhookConfig

object WhatsAppWebhookConfig:
    private def badPathCharacter(path: String): Maybe[Int] =
        val at = path.indexWhere(c => c <= ' ' || c > '~' || c == '?' || c == '#')
        if at >= 0 then Present(at) else Absent
end WhatsAppWebhookConfig
