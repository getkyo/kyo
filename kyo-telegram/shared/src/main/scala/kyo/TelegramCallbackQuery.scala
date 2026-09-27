package kyo

/** A press of an inline keyboard button whose button carries callback data.
  *
  * Telegram shows a progress indicator on the user's button until the bot answers the query with
  * `Telegram.answerCallback`, so a bot answers every query, even with no text.
  *
  * `message` is the message the button was on. Telegram sends it as a full message when the bot can
  * still read it, and as only its chat and id when it cannot (it was deleted, or is too old); the second
  * case is [[kyo.TelegramCallbackQuery.Source.Inaccessible]]. A button on an inline-mode message has
  * no message, only `inlineMessageId`. `chatInstance` identifies the chat the message was in across
  * bots, for games. `data` is the button's callback data.
  *
  * @see
  *   [[kyo.TelegramKeyboard]] where callback buttons are built
  * @see
  *   [[kyo.Telegram.answerCallback]] the answer
  */
final case class TelegramCallbackQuery(
    id: TelegramId.CallbackQueryId,
    from: TelegramUser,
    chatInstance: String,
    message: Maybe[TelegramCallbackQuery.Source] = Absent,
    inlineMessageId: Maybe[String] = Absent,
    data: Maybe[String] = Absent
) derives CanEqual:
    /** The chat of the message the button was on. */
    def chat: Maybe[TelegramChat] =
        message.map {
            case TelegramCallbackQuery.Source.Accessible(m)         => m.chat
            case TelegramCallbackQuery.Source.Inaccessible(chat, _) => chat
        }
end TelegramCallbackQuery

object TelegramCallbackQuery:

    inline given Schema[TelegramCallbackQuery] =
        compiletime.error("TelegramCallbackQuery has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** The message a pressed button was on. */
    enum Source derives CanEqual:
        case Accessible(message: TelegramMessage)

        /** A message the bot can no longer read: only where it was. */
        case Inaccessible(chat: TelegramChat, message: TelegramId.MessageId)
    end Source

    object Source:
        inline given Schema[Source] =
            compiletime.error("TelegramCallbackQuery.Source has no Schema: kyo-telegram decodes Telegram's payloads itself")

end TelegramCallbackQuery
