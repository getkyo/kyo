package kyo

/** A message in a chat, as it arrives in an update or as Telegram answers a send or an edit.
  *
  * `content` is what the message holds: text, one kind of media with its caption, a location, or
  * `Unknown` for anything the module does not model (a sticker, a poll, a service message such as a
  * member joining). An `Unknown` keeps the whole message as Telegram sent it.
  *
  * `id` is the message's id within its chat. `from` is absent in channels, where `senderChat` says
  * who posted. `thread` is the forum topic or reply thread. `replyTo` is the message this one
  * replies to, as Telegram sent it, without its own `replyTo`.
  *
  * @see
  *   [[kyo.TelegramUpdate]] where messages arrive
  * @see
  *   [[kyo.Telegram.send]] which answers the message it sent
  */
final case class TelegramMessage(
    id: TelegramId.MessageId,
    chat: TelegramChat,
    date: Instant,
    content: TelegramMessage.Content,
    from: Maybe[TelegramUser] = Absent,
    senderChat: Maybe[TelegramChat] = Absent,
    thread: Maybe[TelegramId.MessageThreadId] = Absent,
    editDate: Maybe[Instant] = Absent,
    replyTo: Maybe[TelegramMessage] = Absent
) derives CanEqual:
    /** The message's text or its media's caption. */
    def text: Maybe[String] =
        import TelegramMessage.Content.*
        content match
            case Text(text, _)                                                                                     => Present(text)
            case Photo(_, _) | Document(_, _) | Audio(_, _) | Video(_, _) | Voice(_, _) | Location(_) | Unknown(_) => caption.map(_.text)
        end match
    end text

    /** The caption of a media message. */
    def caption: Maybe[TelegramMessage.Caption] =
        import TelegramMessage.Content.*
        content match
            case Photo(_, caption)                     => caption
            case Document(_, caption)                  => caption
            case Audio(_, caption)                     => caption
            case Video(_, caption)                     => caption
            case Voice(_, caption)                     => caption
            case Text(_, _) | Location(_) | Unknown(_) => Absent
        end match
    end caption
end TelegramMessage

object TelegramMessage:

    inline given Schema[TelegramMessage] =
        compiletime.error("TelegramMessage has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** The text of a caption and its entities. */
    final case class Caption(text: String, entities: Chunk[TelegramEntity] = Chunk.empty) derives CanEqual

    object Caption:
        inline given Schema[Caption] =
            compiletime.error("TelegramMessage.Caption has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** What a message holds. */
    enum Content derives CanEqual:
        case Text(text: String, entities: Chunk[TelegramEntity])
        case Photo(sizes: Chunk[TelegramMedia.PhotoSize], caption: Maybe[Caption])
        case Document(document: TelegramMedia.Document, caption: Maybe[Caption])
        case Audio(audio: TelegramMedia.Audio, caption: Maybe[Caption])
        case Video(video: TelegramMedia.Video, caption: Maybe[Caption])
        case Voice(voice: TelegramMedia.Voice, caption: Maybe[Caption])
        case Location(location: TelegramMedia.Location)

        /** Content the module does not model, with the whole message as Telegram sent it. */
        case Unknown(raw: TelegramRawJson)
    end Content

    object Content:
        inline given Schema[Content] =
            compiletime.error("TelegramMessage.Content has no Schema: kyo-telegram decodes Telegram's payloads itself")

end TelegramMessage
