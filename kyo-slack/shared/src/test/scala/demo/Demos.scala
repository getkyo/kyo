package demo

import kyo.*

/** Shared setup for the kyo-slack demos. Each demo (`MentionDemo`, `SlashModalDemo`,
  * `ButtonDemo`, `AppHomeDemo`) is a runnable `KyoApp` focused on one capability; they all
  * read the same two tokens through `connect`, so the demo body is just the handler.
  *
  * {{{
  * export SLACK_APP_TOKEN=xapp-...   # app-level token, scope connections:write
  * export SLACK_BOT_TOKEN=xoxb-...   # bot token (scopes depend on the demo)
  * sbt 'kyo-slackJVM/Test/runMain demo.MentionDemo'
  * }}}
  */
object Demos:

    /** Read the two tokens from the environment and run `f` with the assembled `SlackConfig`,
      * or print how to set them when they are missing.
      */
    def connect[S](f: SlackConfig => Unit < (S & Async & Abort[SlackException] & Scope))(
        using Frame
    ): Unit < (S & Async & Abort[SlackException] & Scope) =
        System.env[String]("SLACK_APP_TOKEN").map { app =>
            System.env[String]("SLACK_BOT_TOKEN").map { bot =>
                (app, bot) match
                    case (Present(a), Present(b)) => f(SlackConfig(SlackToken.AppLevel(a), SlackToken.Bot(b)))
                    case _                        => Console.printLine("set SLACK_APP_TOKEN and SLACK_BOT_TOKEN")
            }
        }
end Demos
