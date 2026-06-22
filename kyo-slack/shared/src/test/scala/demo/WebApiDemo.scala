package demo

import kyo.*

/** Calling the Web API from inside a handler, driven by message keywords.
  *
  * Shows that `chat.*` methods need no token argument (the bot token is ambient), and the
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
            Slack.run(config) {
                case SlackEnvelope.EventsApi(_, SlackEvent.Message(channel, user, text, ts, _)) =>
                    text.trim match
                        case "ping" =>
                            Slack.chatPostMessage(SlackMessage(channel, "pong", threadTs = Present(ts))).andThen(SlackAck.Ack)
                        case "edit" =>
                            Slack.chatPostMessage(SlackMessage(channel, "working...")).map { posted =>
                                Slack.chatUpdate(channel, posted, SlackMessage(channel, "done")).andThen(SlackAck.Ack)
                            }
                        case "whisper" =>
                            Slack.chatPostEphemeral(SlackMessage(channel, "only you can see this"), user).andThen(SlackAck.Ack)
                        case _ => SlackAck.Ack
                case _ => SlackAck.Ack
            }
        }
    }
end WebApiDemo
