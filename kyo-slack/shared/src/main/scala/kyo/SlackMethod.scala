package kyo

/** The name of a Web API method, as `Slack.custom` calls it: `users.list`, `conversations.history`.
  *
  * The name is placed in the request url after the base, `{baseUrl}/{method}`, so a `/`, `?`, `#`,
  * `%` or space in it would send the bot token to another path or put text into a query, and a name of
  * only dots would be a `.` or `..` segment. Every Web API method name is made of ASCII letters,
  * digits, `.` and `_`, and `init` accepts nothing else and no name of only dots, failing with
  * [[kyo.SlackInvalidMethodException]]. A name is
  * never stripped to a shorter one: a caller who wrote a query meant something the call would not do.
  *
  * @see
  *   [[kyo.Slack.custom]] the operation that takes it
  */
opaque type SlackMethod = String

object SlackMethod:

    /** The method named `name`, or the problem: a name must be one or more ASCII letters, digits, `.` or `_`, not only dots. */
    def init(name: String)(using Frame): Result[SlackInvalidMethodException, SlackMethod] =
        import SlackInvalidMethodException.Problem
        val bad = name.indexWhere(c => !isMethodChar(c))
        if name.isEmpty then Result.fail(SlackInvalidMethodException(Problem.Empty))
        else if bad >= 0 then Result.fail(SlackInvalidMethodException(Problem.Character(bad)))
        else if name.forall(_ == '.') then Result.fail(SlackInvalidMethodException(Problem.Dots))
        else Result.succeed(name)
        end if
    end init

    extension (self: SlackMethod) def value: String = self

    given CanEqual[SlackMethod, SlackMethod] = CanEqual.derived

    private def isMethodChar(c: Char): Boolean =
        (c >= 'a' && c <= 'z') ||
            (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '.' || c == '_'

end SlackMethod
