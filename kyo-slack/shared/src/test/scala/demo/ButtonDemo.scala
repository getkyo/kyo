package demo

import kyo.*
import kyo.SlackBlock.dsl.*

/** Block Kit buttons and a reply through the interaction's `response_url`.
  *
  * Send the message `menu` and the bot posts a message with a typed Block Kit button. Clicking
  * it delivers a `block_actions` interaction carrying its `response_url`; the handler acks at once
  * and, on a forked fiber, replaces the clicked message with who clicked it through
  * `Slack.replaceOriginal`.
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
            val loop = Slack.run(config)([A] =>
                (env: SlackEnvelope[A]) =>
                    env match
                        case e: SlackEnvelope.EventsApi =>
                            e.payload.event match
                                case SlackEvent.Message(channel, _, text, _, _) if text.trim == "menu" =>
                                    Slack.chatPostMessage(SlackMessage(channel, "pick one:", blocks = menu))
                                        .andThen(SlackAck.Ack)
                                case _ => SlackAck.Ack

                        case e: SlackEnvelope.Interactive =>
                            e.payload match
                                case click: SlackInteraction.BlockActions =>
                                    val clicked = if click.actions.isEmpty then "?" else click.actions(0).actionId.value
                                    click.responseUrl match
                                        case Present(url) =>
                                            Fiber.initUnscoped(
                                                Abort.run[SlackReplaceOriginalFailure](
                                                    Slack.replaceOriginal(url, SlackReply(s"<@${click.user.id.value}> clicked `$clicked`"))
                                                ).map {
                                                    case Result.Success(_) => Kyo.unit
                                                    case Result.Failure(e) => Log.warn(s"replaceOriginal failed: ${e.getMessage}")
                                                    case Result.Panic(t)   => Abort.panic(t)
                                                }
                                            ).andThen(SlackAck.Ack)
                                        case Absent => SlackAck.Ack
                                    end match
                                case _ => SlackAck.Ack

                        case _: SlackEnvelope.Acknowledged => SlackAck.Ack
                        case _: SlackEnvelope.Plain        => Kyo.unit
            )
            loop
        }
    }
end ButtonDemo
