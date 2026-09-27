package kyo

/** The text of a message or a caption, with the way its formatting is sent.
  *
  * Telegram offers three ways to format, and this type is the choice among them:
  *   - `Plain`: the text as it is, with formatting given as [[kyo.TelegramEntity]] spans. Nothing is
  *     parsed, so nothing needs escaping.
  *   - `MarkdownV2` and `Html`: a [[kyo.TelegramMarkup]] tree, rendered to the mode's markup with every
  *     piece of text escaped, so a caller cannot send malformed markup by accident.
  *
  * `TelegramText("hi")` is plain text with no entities. Telegram's limits are 4096 characters for a
  * message and 1024 for a caption, counted after parsing; longer text is refused by Telegram with a
  * [[kyo.TelegramOtherApiException]].
  *
  * @see
  *   [[kyo.TelegramContent]] where text is sent
  * @see
  *   [[kyo.TelegramMarkup]] the formatted tree
  */
enum TelegramText derives CanEqual:
    case Plain(text: String, entities: Chunk[TelegramEntity] = Chunk.empty)
    case MarkdownV2(markup: TelegramMarkup)
    case Html(markup: TelegramMarkup)
end TelegramText

object TelegramText:
    /** Plain text with no entities. */
    def apply(text: String): TelegramText = Plain(text)
end TelegramText
