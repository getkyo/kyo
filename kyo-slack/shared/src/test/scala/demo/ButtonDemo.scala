package demo

import kyo.*
import kyo.SlackBlock.dsl.*

/** Block Kit buttons and the `response_url` update path.
  *
  * Send the message `menu` and the bot posts a message with a typed Block Kit button. Clicking
  * it delivers a `block_actions` interaction carrying the originating channel; the handler
  * returns `BlockActionsResponse`, which acks the socket bare and then updates the message out
  * of band over the correlated `response_url`.
  *
  * Slack app setup: Socket Mode on; Interactivity on; bot scope `chat:write`; subscribe to
  * `message.channels`.
  *
  * {{{
  * sbt 'kyo-slackJVM/Test/runMain demo.ButtonDemo'
  * }}}
  */
object ButtonDemo extends KyoApp:

    private val menu = blocks(actions(button("Click me", "go")))

    run {
        Demos.connect { config =>
            Slack.run(config) {
                case SlackEnvelope.EventsApi(_, SlackEvent.Message(channel, _, text, _, _)) if text.trim == "menu" =>
                    Slack.chatPostMessage(SlackMessage(channel, "pick one:", blocks = menu))
                        .andThen(SlackAck.Ack)

                case SlackEnvelope.Interactive(_, SlackInteraction.BlockActions(user, _, Present(channel), _, actions)) =>
                    val clicked = if actions.isEmpty then "?" else actions(0).actionId.value
                    SlackAck.BlockActionsResponse(SlackMessage(channel, s"<@${user.value}> clicked `$clicked`"))

                case _ => SlackAck.Ack
            }
        }
    }
end ButtonDemo
