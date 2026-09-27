package kyo

/** A change to a message the bot sent: its text, its media's caption, or its inline keyboard.
  *
  * Each case is one Bot API method (`editMessageText`, `editMessageCaption`, `editMessageReplyMarkup`).
  * A keyboard that is `Absent` removes the one the message had. Only an inline keyboard can be set on
  * an edit.
  *
  * IMPORTANT: an edit that would leave the message exactly as it is fails with
  * [[kyo.TelegramMessageNotModifiedException]], which a bot that re-renders a message on every button
  * press usually recovers.
  *
  * @see
  *   [[kyo.Telegram.edit]] the operation
  */
enum TelegramEdit derives CanEqual:
    case Text(text: TelegramText, keyboard: Maybe[TelegramKeyboard.Inline] = Absent, linkPreview: Boolean = true)
    case Caption(caption: Maybe[TelegramText], keyboard: Maybe[TelegramKeyboard.Inline] = Absent)
    case Keyboard(keyboard: Maybe[TelegramKeyboard.Inline])
end TelegramEdit
