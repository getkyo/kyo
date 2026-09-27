package kyo

class TelegramCallbackQueryTest extends kyo.test.Test[Any]:

    private val user = TelegramUser(TelegramId.UserId(5L), false, "Ann")
    private val chat = TelegramChat(TelegramId.ChatId(5L), TelegramChat.Type.Private)

    "chat is the chat of the button's message, whether the bot can read it or not, and absent for an inline message" in {
        val message = TelegramMessage(
            TelegramId.MessageId(1),
            chat,
            Instant.of(1L.seconds, Duration.Zero),
            TelegramMessage.Content.Text("x", Chunk.empty)
        )
        val id = TelegramId.CallbackQueryId("q")
        assert(Chunk(
            TelegramCallbackQuery(id, user, "ci", Present(TelegramCallbackQuery.Source.Accessible(message))),
            TelegramCallbackQuery(id, user, "ci", Present(TelegramCallbackQuery.Source.Inaccessible(chat, TelegramId.MessageId(1)))),
            TelegramCallbackQuery(id, user, "ci", inlineMessageId = Present("IM"))
        ).map(_.chat) == Chunk(Present(chat), Present(chat), Absent))
    }

end TelegramCallbackQueryTest
