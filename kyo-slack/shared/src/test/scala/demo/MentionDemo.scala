package demo

import kyo.*

/** The smallest useful Socket Mode bot: reply when the app is @-mentioned.
  *
  * The core loop: `Slack.run` opens the connection, the handler receives one typed
  * `SlackEnvelope` at a time, you match the case you care about, and the `SlackAck` you
  * return IS the acknowledgement (there is no ack method to call). `Slack.chatPostMessage`
  * inside the handler needs no token; the bot token from the config is bound around the loop.
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
            Slack.run(config) {
                case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, user, text, _)) =>
                    Slack.chatPostMessage(SlackMessage(channel, s"hi <@${user.value}>, you said: $text"))
                        .andThen(SlackAck.Ack)
                case _ => SlackAck.Ack
            }
        }
    }
end MentionDemo
