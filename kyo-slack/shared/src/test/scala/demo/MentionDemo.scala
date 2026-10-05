package demo

import kyo.*

/** The smallest useful Socket Mode bot: reply when the app is @-mentioned.
  *
  * The core loop: `Slack.run` builds the client and `Slack.receive` opens the connection, the handler receives one typed
  * `SlackEnvelope[A]` at a time, you match the case you care about, and what you return is the
  * answer that case requires: the `SlackAck` that IS the acknowledgement (there is no ack method
  * to call), or nothing for `Hello`, `Disconnect` and `UnknownFrame`. `Slack.send` takes no token: the
  * client `Slack.run` builds holds the config's bot token.
  *
  * Slack app setup: Socket Mode on; bot scopes `app_mentions:read` + `chat:write`; subscribe
  * to the `app_mention` bot event; invite the bot to a channel, then `@mention` it.
  *
  * {{{
  * sbt 'kyo-slackJVM/Test/runMain demo.MentionDemo'
  * }}}
  */
object MentionDemo extends KyoApp:

    run {
        Demos.connect { config =>
            val loop = Slack.run(config)(Slack.receive([A] =>
                (env: SlackEnvelope[A]) =>
                    env match
                        case e: SlackEnvelope.EventsApi =>
                            e.payload.event match
                                case SlackEvent.AppMention(channel, user, text, _) =>
                                    Slack.send(SlackMessage(channel, s"hi <@${user.value}>, you said: $text"))
                                        .andThen(SlackAck.Ack)
                                case _ => SlackAck.Ack
                        case _: SlackEnvelope.Acknowledged => SlackAck.Ack
                        case _: SlackEnvelope.Plain        => Kyo.unit
            ))
            loop
        }
    }
end MentionDemo
