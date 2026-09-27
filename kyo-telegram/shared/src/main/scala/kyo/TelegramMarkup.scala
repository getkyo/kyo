package kyo

/** Formatted text as a tree, rendered to MarkdownV2 or HTML by [[kyo.TelegramText]] with every piece of
  * text escaped for the chosen mode.
  *
  * Telegram's markup modes each reserve characters: MarkdownV2 eighteen of them outside code, HTML `<`,
  * `>` and `&`. A message whose text holds one unescaped is refused, or worse, formatted differently from
  * what was meant. Building the message as this tree leaves no raw markup to get wrong: `Text` is always
  * literal text, and the renderer writes the markers.
  *
  * `Mention` links to a user by id, which works for users without a username. `Pre`'s language is the
  * text up to its first white space. `Blockquote` quotes each line of its content.
  *
  * @see
  *   [[kyo.TelegramText]] the choice of mode
  * @see
  *   [[kyo.TelegramEntity]] the alternative that needs no escaping
  */
enum TelegramMarkup derives CanEqual:
    case Text(value: String)
    case Bold(content: TelegramMarkup)
    case Italic(content: TelegramMarkup)
    case Underline(content: TelegramMarkup)
    case Strikethrough(content: TelegramMarkup)
    case Spoiler(content: TelegramMarkup)
    case Code(value: String)
    case Pre(value: String, language: Maybe[String] = Absent)
    case Link(content: TelegramMarkup, url: TelegramUrl)
    case Mention(content: TelegramMarkup, user: TelegramId.UserId)
    case Blockquote(content: TelegramMarkup)
    case Concat(parts: Chunk[TelegramMarkup])
end TelegramMarkup

object TelegramMarkup:
    /** The pieces in order. */
    def of(parts: TelegramMarkup*): TelegramMarkup = Concat(Chunk.from(parts))
end TelegramMarkup
