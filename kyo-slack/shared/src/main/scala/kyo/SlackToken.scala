package kyo

/** Slack token capability namespace: two distinct opaque token types over
  * `String`. `AppLevel` (an `xapp-` token with `connections:write`) opens the
  * Socket Mode connection; `Bot` (an `xoxb-` token) authenticates the Web API. The
  * two are NOT interchangeable: a `Bot` token cannot be passed where an `AppLevel`
  * is required, so a Web API token can never open the socket (a compile error).
  *
  * Tokens carry no `Schema` and no `toString` that renders the secret: they ride
  * the `Authorization` header and the connect body, never a decoded frame or a log
  * line.
  */
object SlackToken:

    opaque type AppLevel = String
    opaque type Bot      = String

    object AppLevel:
        def apply(s: String): AppLevel            = s
        extension (t: AppLevel) def value: String = t
        given CanEqual[AppLevel, AppLevel]        = CanEqual.derived
    end AppLevel

    object Bot:
        def apply(s: String): Bot            = s
        extension (t: Bot) def value: String = t
        given CanEqual[Bot, Bot]             = CanEqual.derived
    end Bot

end SlackToken
