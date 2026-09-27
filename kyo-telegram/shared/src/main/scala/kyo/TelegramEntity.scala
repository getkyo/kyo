package kyo

/** One formatted or recognized span of a message's text or caption: a mention, a link, bold text, a
  * code block.
  *
  * Telegram describes formatting as entities beside plain text rather than as markup inside it.
  * Inbound messages always carry entities; outbound, [[kyo.TelegramText.Plain]] sends them with the
  * text, which avoids escaping entirely.
  *
  * IMPORTANT: `offset` and `length` count UTF-16 code units, as Telegram does. A Scala `String`'s
  * `length` and `substring` count the same units, so `text.substring(offset, offset + length)` is the
  * entity's text; a count of code points or bytes is not.
  *
  * @see
  *   [[kyo.TelegramText]] the outbound choice between entities and markup
  */
final case class TelegramEntity(kind: TelegramEntity.Kind, offset: Int, length: Int) derives CanEqual:
    /** The part of `text` this entity covers, when it lies inside it. */
    def of(text: String): Maybe[String] =
        if offset >= 0 && length >= 0 && offset.toLong + length <= text.length then Present(text.substring(offset, offset + length))
        else Absent
end TelegramEntity

object TelegramEntity:

    inline given Schema[TelegramEntity] = compiletime.error("TelegramEntity has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** What the span is. `Other` holds an entity type Telegram added after this module, by its wire name. */
    enum Kind derives CanEqual:
        case Mention
        case Hashtag
        case Cashtag
        case BotCommand
        case Url
        case Email
        case PhoneNumber
        case Bold
        case Italic
        case Underline
        case Strikethrough
        case Spoiler
        case Blockquote
        case ExpandableBlockquote
        case Code

        /** A code block, with its programming language when one was given. */
        case Pre(language: Maybe[String])

        /** Text linked to `url`. */
        case TextLink(url: TelegramUrl)

        /** A mention of a user who has no username. */
        case TextMention(user: TelegramUser)

        /** A custom emoji, by the id of its sticker. */
        case CustomEmoji(id: String)

        case Other(name: String)
    end Kind

    object Kind:
        inline given Schema[Kind] = compiletime.error("TelegramEntity.Kind has no Schema: kyo-telegram decodes Telegram's payloads itself")
    end Kind

end TelegramEntity
