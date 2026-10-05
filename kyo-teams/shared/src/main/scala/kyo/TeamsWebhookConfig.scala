package kyo

/** Where the webhook the Bot Connector posts Activities to is mounted: the path of the route on the bot's server, such as `api/messages`.
  *
  * The webhook verifies each delivery's token with the signing keys of the [[kyo.Teams]] client it runs with, so unlike a webhook whose
  * peer signs with a configured secret, it holds no key of its own: the keys are fetched from the OpenID metadata, and the bounds of that
  * verification are settings of [[kyo.TeamsConfig]], shared with `Teams.Webhook.verify`.
  *
  * `path` is split on `/` by the route, so a leading slash is optional. A character no request path can carry (`?`, `#`, a space, a
  * control character, anything outside ASCII) would mount a route no request reaches, so `init` refuses it with
  * [[kyo.TeamsInvalidWebhookConfigException]].
  *
  * @see
  *   [[kyo.Teams.Webhook.handler]] the route mounted at it
  */
final case class TeamsWebhookConfig private[kyo] (path: String) derives CanEqual

object TeamsWebhookConfig:

    /** The webhook config, or a [[kyo.TeamsInvalidWebhookConfigException]] when `path` holds a character no request path carries. */
    def init(path: String = "")(using Frame): Result[TeamsInvalidWebhookConfigException, TeamsWebhookConfig] =
        val at = path.indexWhere(c => c <= ' ' || c > '~' || c == '?' || c == '#')
        if at >= 0 then Result.fail(TeamsInvalidWebhookConfigException(TeamsInvalidWebhookConfigException.Problem.PathCharacter(at)))
        else Result.succeed(new TeamsWebhookConfig(path))
    end init
end TeamsWebhookConfig
