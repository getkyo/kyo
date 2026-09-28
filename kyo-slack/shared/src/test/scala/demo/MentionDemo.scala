package demo

import kyo.*

/** The smallest useful Socket Mode bot: reply when the app is @-mentioned.
  *
  * The core loop: `Slack.run` opens the connection, the handler receives one typed
  * `SlackEnvelope[A]` at a time, you match the case you care about, and what you return is the
  * answer that case requires: the `SlackAck` that IS the acknowledgement (there is no ack method
  * to call), or nothing for `Hello`, `Disconnect` and `UnknownFrame`. `Slack.chatPostMessage` takes no token: the
  * client `run` provides holds the config's bot token.
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
            val loop = Slack.run(config)([A] =>
                (env: SlackEnvelope[A]) =>
                    env match
                        case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, user, text, _), _) =>
                            Slack.chatPostMessage(SlackMessage(channel, s"hi <@${user.value}>, you said: $text"))
                                .andThen(SlackAck.Ack)
                        case _: SlackEnvelope.Acknowledged => SlackAck.Ack
                        case _: SlackEnvelope.Plain        => Kyo.unit
            )
            loop
        }
    }
end MentionDemo
