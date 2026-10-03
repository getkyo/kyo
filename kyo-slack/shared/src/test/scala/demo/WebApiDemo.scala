package demo

import kyo.*

/** Calling the Web API from inside a handler, driven by message keywords.
  *
  * Shows that the `chat.*` verbs need no token argument (the client `Slack.run` builds holds it), and the
  * three common replies: a threaded reply, an edit, and an ephemeral (visible only to one
  * user). Type a single keyword in a channel the bot is in:
  *   - `ping`    -> `chatPostMessage` as a threaded reply
  *   - `edit`    -> `chatPostMessage` then `chatUpdate` to change it in place
  *   - `whisper` -> `chatPostEphemeral`, visible only to you
  *
  * Slack app setup: Socket Mode on; bot scope `chat:write`; subscribe to `message.channels`.
  *
  * {{{
  * sbt 'kyo-slackJVM/Test/runMain demo.WebApiDemo'
  * }}}
  */
object WebApiDemo extends KyoApp:

    run {
        Demos.connect { config =>
            val loop = Slack.run(config)(Slack.receive([A] =>
                (env: SlackEnvelope[A]) =>
                    env match
                        case e: SlackEnvelope.EventsApi =>
                            e.payload.event match
                                case SlackEvent.Message(channel, user, text, ts, _) =>
                                    text.trim match
                                        case "ping" =>
                                            Slack.chatPostMessage(SlackMessage(
                                                channel,
                                                "pong",
                                                threadTs = Present(ts)
                                            )).andThen(SlackAck.Ack)
                                        case "edit" =>
                                            Slack.chatPostMessage(SlackMessage(channel, "working...")).map { posted =>
                                                Slack.chatUpdate(channel, posted, SlackMessage(channel, "done")).andThen(SlackAck.Ack)
                                            }
                                        case "whisper" =>
                                            Slack.chatPostEphemeral(
                                                SlackMessage(channel, "only you can see this"),
                                                user
                                            ).andThen(SlackAck.Ack)
                                        case _ => SlackAck.Ack
                                case _ => SlackAck.Ack
                        case _: SlackEnvelope.Acknowledged => SlackAck.Ack
                        case _: SlackEnvelope.Plain        => Kyo.unit
            ))
            loop
        }
    }
end WebApiDemo
