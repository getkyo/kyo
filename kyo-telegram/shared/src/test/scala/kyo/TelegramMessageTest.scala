package kyo

class TelegramMessageTest extends kyo.test.Test[Any]:

    private val chat = TelegramChat(TelegramId.ChatId(5L), TelegramChat.Type.Private)
    private val at   = Instant.of(1L.seconds, Duration.Zero)
    private val file = TelegramMedia.Document(TelegramId.FileId("F"), TelegramId.FileUniqueId("U"))

    private def message(content: TelegramMessage.Content) = TelegramMessage(TelegramId.MessageId(1), chat, at, content)

    "text is a text message's text or a media message's caption" in {
        import TelegramMessage.*
        val caption = Present(Caption("cap"))
        assert(Chunk(
            message(Content.Text("hi", Chunk.empty)),
            message(Content.Document(file, caption)),
            message(Content.Document(file, Absent)),
            message(Content.Location(TelegramMedia.Location(1, 2)))
        ).map(m => (m.text, m.caption)) == Chunk(
            (Present("hi"), Absent),
            (Present("cap"), caption),
            (Absent, Absent),
            (Absent, Absent)
        ))
    }

    "a type Telegram sends has no Schema: the module alone reads Telegram's JSON" in {
        typeCheckFailure("summon[Schema[TelegramMessage]]")(
            "TelegramMessage has no Schema: kyo-telegram decodes Telegram's payloads itself"
        )
    }

end TelegramMessageTest
