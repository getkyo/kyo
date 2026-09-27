package kyo

/** What the bot tells a chat it is doing, shown as a status such as "typing..." for up to 5 seconds or
  * until the bot's next message arrives.
  *
  * Telegram recommends sending one only when the answer will take noticeable time. Each case names what
  * the user is about to receive: `Typing` for text, `UploadPhoto` for a photo, `RecordVoice` or
  * `UploadVoice` for a voice note, and so on.
  *
  * @see
  *   [[kyo.Telegram.sendChatAction]] the operation
  */
enum TelegramChatAction derives CanEqual:
    case Typing
    case UploadPhoto
    case RecordVideo
    case UploadVideo
    case RecordVoice
    case UploadVoice
    case UploadDocument
    case ChooseSticker
    case FindLocation
    case RecordVideoNote
    case UploadVideoNote
end TelegramChatAction
