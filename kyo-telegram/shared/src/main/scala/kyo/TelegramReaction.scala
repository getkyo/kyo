package kyo

/** A reaction on a message: one emoji, a custom emoji, or a paid reaction.
  *
  * Telegram accepts only a fixed set of emoji as reactions; `setReaction` with another fails with
  * [[kyo.TelegramOtherApiException]]. `Other` holds a reaction type Telegram added after this module, by
  * its wire name, on reactions that arrive. It cannot be sent: `setReaction` takes a
  * [[kyo.TelegramReaction.Sendable]], which has no `Other`.
  *
  * @see
  *   [[kyo.Telegram.setReaction]] how a bot reacts
  * @see
  *   [[kyo.TelegramReactionUpdate]] a user's reactions arriving
  */
enum TelegramReaction derives CanEqual:
    case Emoji(emoji: String)
    case CustomEmoji(id: String)
    case Paid
    case Other(name: String)
end TelegramReaction

object TelegramReaction:
    /** The reactions a bot can set: every case but `Other`. */
    type Sendable = Emoji | CustomEmoji | Paid.type

    inline given Schema[TelegramReaction] =
        compiletime.error("TelegramReaction has no Schema: kyo-telegram decodes and encodes Telegram's payloads itself")
end TelegramReaction
