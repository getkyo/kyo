package kyo.internal.slack

import kyo.*

/** The request and answer objects of the Web API methods the module calls, each named for its method. A method's
  * object is its own JSON, not a second model of a public type: its fields are the method's arguments and results, typed
  * with the public types.
  *
  * A field is named by its wire key rather than renamed: kyo-schema names a missing renamed field by its Scala name, and a
  * decode failure must name the key Slack left out.
  */
private[kyo] object Methods:

    /** `auth.test` takes no argument. */
    final case class AuthTest() derives Schema

    /** `auth.test`'s answer. Slack documents `bot_id` only for a bot token, the one token the module sends. */
    final case class AuthTestAnswer(
        user_id: SlackId.UserId,
        team_id: SlackId.TeamId,
        bot_id: SlackId.BotId,
        url: String
    ) derives Schema

    /** `apps.connections.open` takes no argument. */
    final case class AppsConnectionsOpen() derives Schema

    /** `apps.connections.open`'s answer: the Socket Mode url, required, so an ok answer without it fails at `url`. */
    final case class AppsConnectionsOpenAnswer(url: String) derives Schema

    final case class ChatPostMessageAnswer(ts: SlackTs) derives Schema

    /** `chat.postEphemeral` answers `message_ts`, not `ts`. */
    final case class ChatPostEphemeralAnswer(message_ts: SlackTs) derives Schema

    final case class ChatUpdateAnswer(ts: SlackTs) derives Schema

    /** The view the `views.open`, `views.update` and `views.publish` answers carry, by its id. */
    final case class ViewRef(id: SlackId.ViewId) derives Schema

    final case class ViewsOpenAnswer(view: ViewRef) derives Schema

    final case class ViewsUpdateAnswer(view: ViewRef) derives Schema

    final case class ViewsPublishAnswer(view: ViewRef) derives Schema

end Methods
