package kyo

/** Slack token capability namespace: two distinct secret types. `AppLevel` (an `xapp-`
  * token with `connections:write`) opens the Socket Mode connection; `Bot` (an `xoxb-`
  * token, or `xoxe.xoxb-` when rotated) authenticates the Web API. The two are NOT interchangeable: a `Bot` token cannot
  * be passed where an `AppLevel` is required, and a plain `String` is neither, so a Web API
  * token can never open the socket (a compile error).
  *
  * Each token is a class whose `toString` renders `SlackToken.AppLevel(<redacted>)` or
  * `SlackToken.Bot(<redacted>)`, so a `SlackConfig`, a log line, or an assertion message that
  * renders a token never shows the secret. `value` is the only way to read it. Tokens compare
  * by value, carry no `Schema`, and ride only the `Authorization` header.
  *
  * `init` checks the token's shape and fails with a [[kyo.SlackInvalidTokenException]] when it
  * cannot be one: empty, not starting with its kind's
  * prefix followed by at least one character, longer than 255 characters, or holding a character
  * other than printable ASCII without space. Slack documents the prefixes and says to "anticipate a
  * string as long as 255 characters" (token lengthening, 2016-08-23), and no alphabet, so nothing
  * narrower is checked; a CR, an LF or a space would end or split the `Authorization` header the
  * token is put in. A token of the right shape that Slack does not know is that call's
  * [[kyo.SlackInvalidAuthException]].
  */
object SlackToken:

    /** The `xapp-` app-level token that opens the Socket Mode connection. */
    final class AppLevel private (val value: String):
        override def equals(other: Any): Boolean =
            other match
                case that: AppLevel => value == that.value
                case _              => false
        override def hashCode: Int    = value.hashCode
        override def toString: String = "SlackToken.AppLevel(<redacted>)"
    end AppLevel

    object AppLevel:
        /** The token `value`, or the [[kyo.SlackInvalidTokenException]] naming why the text cannot be an `xapp-` token. */
        def init(value: String)(using Frame): Result[SlackInvalidTokenException, AppLevel] =
            problemOf(value, Chunk("xapp-")) match
                case Present(problem) => Result.fail(SlackInvalidTokenException(SlackInvalidTokenException.Token.AppLevel, problem))
                case Absent           => Result.succeed(new AppLevel(value))
        given CanEqual[AppLevel, AppLevel] = CanEqual.derived
    end AppLevel

    /** The bot token that authenticates Web API calls: `xoxb-`, or `xoxe.xoxb-` for an app with token rotation on ("Using token
      * rotation", docs.slack.dev/authentication/using-token-rotation). The rotation guide covers bot and user tokens only, so an
      * app-level token keeps its one `xapp-` prefix.
      */
    final class Bot private (val value: String):
        override def equals(other: Any): Boolean =
            other match
                case that: Bot => value == that.value
                case _         => false
        override def hashCode: Int    = value.hashCode
        override def toString: String = "SlackToken.Bot(<redacted>)"
    end Bot

    object Bot:
        /** The token `value`, or the [[kyo.SlackInvalidTokenException]] naming why the text cannot be an `xoxb-` or `xoxe.xoxb-` token. */
        def init(value: String)(using Frame): Result[SlackInvalidTokenException, Bot] =
            problemOf(value, Chunk("xoxb-", "xoxe.xoxb-")) match
                case Present(problem) => Result.fail(SlackInvalidTokenException(SlackInvalidTokenException.Token.Bot, problem))
                case Absent           => Result.succeed(new Bot(value))
        given CanEqual[Bot, Bot] = CanEqual.derived
    end Bot

    /** Slack asks clients to allow tokens of up to this many characters. */
    inline val MaxLength = 255

    private def problemOf(value: String, prefixes: Chunk[String]): Maybe[SlackInvalidTokenException.Problem] =
        import SlackInvalidTokenException.Problem
        if value.isEmpty then Present(Problem.Empty)
        else if value.length > MaxLength then Present(Problem.TooLong(value.length, MaxLength))
        else
            val bad = value.indexWhere(c => !isTokenChar(c))
            if bad >= 0 then Present(Problem.InvalidCharacter(bad))
            else if !prefixes.exists(p => value.startsWith(p) && value.length > p.length) then Present(Problem.Prefix(prefixes))
            else Absent
        end if
    end problemOf

    private def isTokenChar(c: Char): Boolean = c >= '!' && c <= '~'

end SlackToken
