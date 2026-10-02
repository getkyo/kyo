package demo

import kyo.*
import kyo.SlackBlock.dsl.*

/** Slash command to a modal, with a live update and a submission.
  *
  * Running the slash command opens a modal (`viewsOpen`) built from typed blocks: a section, a
  * text input, and a button, with a Submit. Clicking the in-modal button refreshes it in place
  * (`viewsUpdate`, keyed off the `viewId` that block_actions now carries). Submitting it delivers
  * a `view_submission` whose `stateJson` holds the typed values; the handler closes the modal by
  * returning `ViewResponse(Clear)`. Cancelling delivers a `view_closed` (the modal sets `notifyOnClose`).
  *
  * Slack app setup: Socket Mode on; Interactivity on; a slash command (e.g. `/demo`); bot scope
  * `commands`.
  *
  * {{{
  * sbt 'kyo-slackJVM/Test/runMain demo.SlashModalDemo'
  * }}}
  */
object SlashModalDemo extends KyoApp:

    private def modal(prompt: String): SlackView =
        SlackView(
            SlackView.Type.Modal,
            callbackId = Present("demo_modal"),
            title = Present("kyo-slack"),
            submit = Present("Submit"),
            close = Present("Cancel"),
            notifyOnClose = true,
            blocks = blocks(
                section(prompt),
                input("Your name", textInput("value")),
                actions(button("Refresh", "refresh"))
            )
        )

    run {
        Demos.connect { config =>
            val loop = Slack.run(config)([A] =>
                (env: SlackEnvelope[A]) =>
                    env match
                        case e: SlackEnvelope.SlashCommand =>
                            Slack.viewsOpen(e.payload.triggerId, modal("Fill this in and submit."))
                                .andThen(SlackAck.CommandResponse(SlackAck.CommandResponse.Visibility.Ephemeral, "opened a modal"))

                        case e: SlackEnvelope.Interactive =>
                            e.payload match
                                case SlackInteraction.BlockActions(_, _, _, Present(viewId), _, _, _) =>
                                    Slack.viewsUpdate(viewId, modal("Refreshed in place :sparkles:")).andThen(SlackAck.Ack)
                                case _: SlackInteraction.ViewSubmission =>
                                    SlackAck.ViewResponse(SlackAck.ViewAction.Clear)
                                case _ => SlackAck.Ack

                        case _: SlackEnvelope.Acknowledged => SlackAck.Ack
                        case _: SlackEnvelope.Plain        => Kyo.unit
            )
            loop
        }
    }
end SlashModalDemo
