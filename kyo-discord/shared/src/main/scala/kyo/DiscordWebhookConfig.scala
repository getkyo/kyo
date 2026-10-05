package kyo

/** What the interactions endpoint verifies requests with and where it is mounted: the application's public key, and the path of the
  * route on the bot's server.
  *
  * Discord signs every request to the endpoint with the application's Ed25519 key and expects the endpoint to refuse one whose
  * signature does not verify (`interactions/overview.mdx`, "Setting Up an Endpoint"). It is separate from [[kyo.DiscordConfig]]
  * because the key is not the bot token: the developer portal shows it, and only the endpoint reads it.
  *
  * `path` is split on `/` by the route, so a leading slash is optional. A character no request path can carry (`?`, `#`, a space, a
  * control character, anything outside ASCII) would mount a route no request reaches, so `init` refuses it with
  * [[kyo.DiscordInvalidWebhookConfigException]].
  *
  * @see
  *   [[kyo.Discord.PublicKey]] the key held here
  */
final case class DiscordWebhookConfig private[kyo] (publicKey: Discord.PublicKey, path: String) derives CanEqual

object DiscordWebhookConfig:

    /** The webhook config, or a [[kyo.DiscordInvalidWebhookConfigException]] when `path` holds a character no request path carries. */
    def init(publicKey: Discord.PublicKey, path: String = "")(using
        Frame
    ): Result[DiscordInvalidWebhookConfigException, DiscordWebhookConfig] =
        val at = path.indexWhere(c => c <= ' ' || c > '~' || c == '?' || c == '#')
        if at >= 0 then
            Result.fail(DiscordInvalidWebhookConfigException(DiscordInvalidWebhookConfigException.Problem.PathCharacter(at)))
        else Result.succeed(new DiscordWebhookConfig(publicKey, path))
    end init
end DiscordWebhookConfig
