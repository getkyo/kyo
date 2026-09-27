package kyo

/** A user changed their reactions to a message.
  *
  * Telegram sends it only to a bot that is an administrator of the chat and that asked for
  * `message_reaction` in its allowed updates, and never for reactions set by bots. `user` is absent
  * when the reaction was anonymous, and `actorChat` then says which chat reacted.
  *
  * @see
  *   [[kyo.TelegramReaction]] the reactions
  * @see
  *   [[kyo.TelegramUpdate.Type.MessageReaction]] the allowed-update type that enables it
  */
final case class TelegramReactionUpdate(
    chat: TelegramChat,
    message: TelegramId.MessageId,
    date: Instant,
    oldReaction: Chunk[TelegramReaction],
    newReaction: Chunk[TelegramReaction],
    user: Maybe[TelegramUser] = Absent,
    actorChat: Maybe[TelegramChat] = Absent
) derives CanEqual

object TelegramReactionUpdate:
    inline given Schema[TelegramReactionUpdate] =
        compiletime.error("TelegramReactionUpdate has no Schema: kyo-telegram decodes Telegram's payloads itself")
