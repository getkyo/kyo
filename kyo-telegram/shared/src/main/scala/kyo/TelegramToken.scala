package kyo

/** The bot token that authenticates every Bot API call, as issued by BotFather.
  *
  * Telegram puts the token in the URL path of every call (`/bot<token>/<method>`) and of every file
  * download (`/file/bot<token>/<file_path>`), so it is a secret that travels in a path. The class keeps
  * it from printing: `toString` renders `TelegramToken(<redacted>)`, so a `TelegramConfig`, a log line
  * or an assertion message that renders one never shows it. `value` is the only way to read it.
  * Tokens compare by value and carry no `Schema`.
  *
  * IMPORTANT: construction checks the token's shape and panics with a
  * [[kyo.TelegramInvalidTokenException]] when it cannot be one: empty, longer than 80 characters (the
  * Bot API server's own bound), without the `:` between the bot id and the secret part, or holding a
  * character outside `A-Z a-z 0-9 _ - :`. Any other character would change the URL the token is put
  * in (`/` adds a segment, `?` starts a query, `#` a fragment, `%` an escape), so a malformed token is
  * refused before it can address another path. A token of the right shape that Telegram does not
  * know is that call's [[kyo.TelegramUnauthorizedException]].
  *
  * @see
  *   [[kyo.TelegramConfig]] where the token is held
  * @see
  *   [[kyo.TelegramSecretToken]] the webhook's secret, a different kind
  */
final class TelegramToken private (val value: String):
    override def equals(other: Any): Boolean =
        other match
            case that: TelegramToken => value == that.value
            case _                   => false
    override def hashCode: Int    = value.hashCode
    override def toString: String = "TelegramToken(<redacted>)"
end TelegramToken

object TelegramToken:

    /** Builds a token, panicking with a [[kyo.TelegramInvalidTokenException]] when the text cannot be one. */
    def apply(value: String)(using Frame): TelegramToken =
        problemOf(value) match
            case Present(problem) => throw TelegramInvalidTokenException(TelegramInvalidTokenException.Token.Bot, problem)
            case Absent           => new TelegramToken(value)

    given CanEqual[TelegramToken, TelegramToken] = CanEqual.derived

    /** The Bot API server refuses a token longer than this (`ClientManager.cpp`, `send`). */
    inline val MaxLength = 80

    private def problemOf(value: String): Maybe[TelegramInvalidTokenException.Problem] =
        if value.isEmpty then Present(TelegramInvalidTokenException.Problem.Empty)
        else if value.length > MaxLength then Present(TelegramInvalidTokenException.Problem.TooLong(value.length, MaxLength))
        else
            val bad = value.indexWhere(c => !isTokenChar(c))
            if bad >= 0 then Present(TelegramInvalidTokenException.Problem.InvalidCharacter(bad))
            else if value.indexOf(':') < 0 then Present(TelegramInvalidTokenException.Problem.NoColon)
            else Absent
    end problemOf

    private def isTokenChar(c: Char): Boolean =
        (c >= 'A' && c <= 'Z') ||
            (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '_' || c == '-' || c == ':'

end TelegramToken
