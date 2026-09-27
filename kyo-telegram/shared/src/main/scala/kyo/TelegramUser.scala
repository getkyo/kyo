package kyo

/** A Telegram user or bot, as it appears on a message, a callback query, a membership change, or the
  * answer of `getMe`.
  *
  * Only `id`, `isBot` and `firstName` are always present. Telegram omits the others when the user has
  * not set them or the bot may not see them, so they are `Maybe`. `languageCode` is the IETF tag of the
  * user's client language and is sent only on what the user did themselves.
  *
  * @see
  *   [[kyo.TelegramId.UserId]] the id
  * @see
  *   [[kyo.TelegramMessage]] where a user sends
  */
final case class TelegramUser(
    id: TelegramId.UserId,
    isBot: Boolean,
    firstName: String,
    lastName: Maybe[String] = Absent,
    username: Maybe[String] = Absent,
    languageCode: Maybe[String] = Absent
) derives CanEqual

object TelegramUser:
    inline given Schema[TelegramUser] = compiletime.error("TelegramUser has no Schema: kyo-telegram decodes Telegram's payloads itself")
