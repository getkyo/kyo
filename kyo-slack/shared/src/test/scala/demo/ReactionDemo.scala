package demo

import kyo.*

/** Handling a typed event and calling an unmodeled Web API method.
  *
  * When someone adds an emoji reaction, the bot reacts back with `:eyes:` using
  * `Slack.custom`, the escape hatch for any Web API method kyo-slack does not wrap. The method
  * name is a `SlackMethod`, checked when it is built; the request body is your own case class
  * deriving `Schema`; the response is decoded to the type you ask for.
  *
  * Slack app setup: Socket Mode on; bot scopes `reactions:read` + `reactions:write`;
  * subscribe to the `reaction_added` bot event.
  *
  * {{{
  * sbt 'kyo-slackJVM/Test/runMain demo.ReactionDemo'
  * }}}
  */
object ReactionDemo extends KyoApp:

    /** The `reactions.add` request body and a minimal `{"ok":true}` response. */
    case class AddReaction(channel: SlackId.ChannelId, timestamp: SlackTs, name: String) derives Schema
    case class ApiOk(ok: Boolean) derives Schema

    run {
        Demos.connect { config =>
            val reactionsAdd = SlackMethod("reactions.add")
            val loop         = Slack.run(config)([A] =>
                (env: SlackEnvelope[A]) =>
                    env match
                        case SlackEnvelope.EventsApi(_, SlackEvent.ReactionAdded(_, reaction, itemChannel, itemTs), _)
                            if reaction != "eyes" =>
                            Slack.custom[AddReaction, ApiOk](reactionsAdd, AddReaction(itemChannel, itemTs, "eyes"))
                                .andThen(SlackAck.Ack)
                        case _: SlackEnvelope.Acknowledged => SlackAck.Ack
                        case _: SlackEnvelope.Plain        => Kyo.unit
            )
            loop
        }
    }
end ReactionDemo
