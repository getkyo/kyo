package kyo

/** The name of a Bot API method, as `Telegram.custom` calls it: `sendMessage`, `getChatMemberCount`.
  *
  * The name is placed in the request path after the bot token, `/bot<token>/<method>`, so a `/`, `?`, `#`
  * or `%` in it would send the token to a different path or into a query, and a name of only dots would
  * be a `.` or `..` segment. Construction accepts ASCII letters, digits, `.` and `_`, and refuses a name
  * of only dots, panicking with [[kyo.TelegramInvalidMethodException]].
  *
  * @see
  *   [[kyo.Telegram.custom]] the operation that takes it
  */
opaque type TelegramMethod = String

object TelegramMethod:

    /** The method named `name`: one or more of ASCII letters, digits, `.` and `_`, not only dots. */
    def apply(name: String)(using Frame): TelegramMethod =
        if name.isEmpty then throw TelegramInvalidMethodException(TelegramInvalidMethodException.Problem.Empty)
        val bad = name.indexWhere(c =>
            !((c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') || c == '.' || c == '_')
        )
        if bad >= 0 then throw TelegramInvalidMethodException(TelegramInvalidMethodException.Problem.Character(bad))
        if name.forall(_ == '.') then throw TelegramInvalidMethodException(TelegramInvalidMethodException.Problem.Dots)
        name
    end apply

    extension (self: TelegramMethod) def value: String = self

    given CanEqual[TelegramMethod, TelegramMethod] = CanEqual.derived

end TelegramMethod
