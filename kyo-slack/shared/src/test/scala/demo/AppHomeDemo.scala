package demo

import kyo.*
import kyo.SlackBlock.dsl.*

/** Publishing an App Home tab.
  *
  * When a user opens the bot's Home tab, Slack delivers `app_home_opened`; the handler publishes
  * a per-user Home view with `viewsPublish`. The view is a `SlackView` of type `Home` whose body
  * is built from typed blocks.
  *
  * Slack app setup: App Home -> enable the Home Tab; subscribe to the `app_home_opened` bot
  * event. Then open the bot in the sidebar and click its Home tab.
  *
  * {{{
  * sbt 'kyo-slackJVM/Test/runMain demo.AppHomeDemo'
  * }}}
  */
object AppHomeDemo extends KyoApp:

    private def home(user: SlackId.UserId): SlackView =
        SlackView(
            SlackView.Type.Home,
            blocks = blocks(section(s"Welcome <@${user.value}> :wave:\nThis Home tab was published by kyo-slack."))
        )

    run {
        Demos.connect { config =>
            Slack.run(config) {
                case SlackEnvelope.EventsApi(_, SlackEvent.AppHomeOpened(user, _, _)) =>
                    Slack.viewsPublish(user, home(user)).andThen(SlackAck.Ack)
                case _ => SlackAck.Ack
            }
        }
    }
end AppHomeDemo
