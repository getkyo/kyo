package kyo

/** What `Telegram.send` sends: text, one kind of media with an optional caption, or a location.
  *
  * Each case is one Bot API method (`sendMessage`, `sendPhoto`, `sendDocument`, `sendAudio`,
  * `sendVideo`, `sendVoice`, `sendLocation`), and they share their failures, so one operation sends
  * them all. A media file is a [[kyo.TelegramInputFile]]: an id, a URL, or bytes uploaded as multipart.
  *
  * `linkPreview = false` asks Telegram not to show a preview of the first link in the text.
  *
  * @see
  *   [[kyo.Telegram.send]] the operation
  * @see
  *   [[kyo.TelegramText]] how text is formatted
  */
enum TelegramContent derives CanEqual:
    case Text(text: TelegramText, linkPreview: Boolean = true)
    case Photo(file: TelegramInputFile, caption: Maybe[TelegramText] = Absent)
    case Document(file: TelegramInputFile, caption: Maybe[TelegramText] = Absent)
    case Audio(file: TelegramInputFile, caption: Maybe[TelegramText] = Absent)
    case Video(file: TelegramInputFile, caption: Maybe[TelegramText] = Absent)
    case Voice(file: TelegramInputFile, caption: Maybe[TelegramText] = Absent)
    case Location(latitude: Double, longitude: Double)
end TelegramContent

object TelegramContent:
    /** Plain text. */
    def text(text: String): TelegramContent = Text(TelegramText(text))
end TelegramContent
