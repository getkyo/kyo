package kyo

/** The secret a bot chooses when it registers a webhook, which Telegram then sends in the
  * `X-Telegram-Bot-Api-Secret-Token` header of every webhook request.
  *
  * It is how a webhook tells Telegram's requests from anyone else's: the bot passes it to
  * `setWebhook`, and the webhook handler compares the header with it in constant time. It is a
  * different kind of secret from the [[kyo.TelegramToken]], and a type of its own, so one cannot be
  * passed where the other belongs. `toString` renders `TelegramSecretToken(<redacted>)`, `value` is
  * the only way to read it, and it compares by value and carries no `Schema`.
  *
  * IMPORTANT: Telegram accepts 1 to 256 characters from `A-Z a-z 0-9 _ -` (the `secret_token`
  * parameter of `setWebhook`). Construction checks this and panics with a
  * [[kyo.TelegramInvalidTokenException]] otherwise, so a secret Telegram would refuse at
  * registration is refused where it is written.
  *
  * @see
  *   [[kyo.TelegramToken]] the bot token, a different kind
  */
final class TelegramSecretToken private (val value: String):
    override def equals(other: Any): Boolean =
        other match
            case that: TelegramSecretToken => value == that.value
            case _                         => false
    override def hashCode: Int    = value.hashCode
    override def toString: String = "TelegramSecretToken(<redacted>)"
end TelegramSecretToken

object TelegramSecretToken:

    /** Builds a secret, panicking with a [[kyo.TelegramInvalidTokenException]] when Telegram would refuse it. */
    def apply(value: String)(using Frame): TelegramSecretToken =
        problemOf(value) match
            case Present(problem) => throw TelegramInvalidTokenException(TelegramInvalidTokenException.Token.Secret, problem)
            case Absent           => new TelegramSecretToken(value)

    given CanEqual[TelegramSecretToken, TelegramSecretToken] = CanEqual.derived

    /** The longest secret `setWebhook` accepts. */
    inline val MaxLength = 256

    private def problemOf(value: String): Maybe[TelegramInvalidTokenException.Problem] =
        if value.isEmpty then Present(TelegramInvalidTokenException.Problem.Empty)
        else if value.length > MaxLength then Present(TelegramInvalidTokenException.Problem.TooLong(value.length, MaxLength))
        else
            val bad = value.indexWhere(c => !isSecretChar(c))
            if bad >= 0 then Present(TelegramInvalidTokenException.Problem.InvalidCharacter(bad))
            else Absent
    end problemOf

    private def isSecretChar(c: Char): Boolean =
        (c >= 'A' && c <= 'Z') ||
            (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '_' || c == '-'

end TelegramSecretToken
