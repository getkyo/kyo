package kyo

class TelegramCallbackQueryTest extends kyo.test.Test[Any]:

    private val user = Telegram.User(Telegram.UserId(5L), false, "Ann")
    private val chat = Telegram.Chat(Telegram.ChatId(5L), Telegram.Chat.Type.Private)

    "chat is the chat of the button's message, whether the bot can read it or not, and absent for an inline message" in {
        val message = Telegram.Message(
            Telegram.MessageId(1),
            chat,
            Instant.of(1L.seconds, Duration.Zero),
            Telegram.Message.Content.Text("x", Chunk.empty)
        )
        val id = Telegram.CallbackQueryId("q")
        assert(Chunk(
            Telegram.CallbackQuery(id, user, "ci", Present(Telegram.CallbackQuery.Source.Accessible(message))),
            Telegram.CallbackQuery(id, user, "ci", Present(Telegram.CallbackQuery.Source.Inaccessible(chat, Telegram.MessageId(1)))),
            Telegram.CallbackQuery(id, user, "ci", inlineMessageId = Present("IM"))
        ).map(_.chat) == Chunk(Present(chat), Present(chat), Absent))
    }

    "a button on a message the bot sent: the message is accessible, and its keyboard is not modelled" in {
        import TelegramTest.Fixtures.*
        val head    = s""""id":"4382bfdwdsb323b2d9","from":$aliceUserJson,"chat_instance":"-8204785648543254131","data":"vote:yes""""
        val message = s""""message_id":70,"from":$botJson,"chat":$aliceChatJson,"date":1735689650,"text":"Ship it?""""
        val json    = s"""{$head,"message":{$message,""" +
            """"reply_markup":{"inline_keyboard":[[{"text":"Yes","callback_data":"vote:yes"},{"text":"No","callback_data":"vote:no"}]]}}}"""
        val value = Telegram.CallbackQuery(
            Telegram.CallbackQueryId("4382bfdwdsb323b2d9"),
            aliceUser,
            "-8204785648543254131",
            message = Present(Telegram.CallbackQuery.Source.Accessible(Telegram.Message(
                Telegram.MessageId(70),
                aliceChat,
                at(1735689650),
                Telegram.Message.Content.Text("Ship it?", Chunk.empty),
                from = Present(bot)
            ))),
            data = Present("vote:yes")
        )
        assert(Json.decode[Telegram.CallbackQuery](json) == Result.succeed(value))
        assert(wire(value) == wire(s"""{$head,"message":{$message}}"""))
        assert(value.chat == Present(aliceChat))
    }

    "a message the bot can no longer read is Inaccessible, told apart by its date of 0" in {
        import TelegramTest.Fixtures.*
        val json = s"""{"id":"4382bfdwdsb323b2e0","from":$aliceUserJson,"chat_instance":"-8204785648543254131","data":"vote:no",""" +
            s""""message":{"chat":$aliceChatJson,"message_id":70,"date":0}}"""
        val value = Telegram.CallbackQuery(
            Telegram.CallbackQueryId("4382bfdwdsb323b2e0"),
            aliceUser,
            "-8204785648543254131",
            message = Present(Telegram.CallbackQuery.Source.Inaccessible(aliceChat, Telegram.MessageId(70))),
            data = Present("vote:no")
        )
        assert(Json.decode[Telegram.CallbackQuery](json) == Result.succeed(value))
        assert(wire(value) == wire(json))
    }

    "a message whose content the module does not model is still accessible, since its date is not 0" in {
        import TelegramTest.Fixtures.*
        val message = s"""{"message_id":71,"chat":$aliceChatJson,"date":1735689651,"dice":{"emoji":"🎲","value":4}}"""
        val json    = s"""{"id":"q","from":$aliceUserJson,"chat_instance":"-1","message":$message}"""
        val value   = Telegram.CallbackQuery(
            Telegram.CallbackQueryId("q"),
            aliceUser,
            "-1",
            message = Present(Telegram.CallbackQuery.Source.Accessible(
                Telegram.Message(Telegram.MessageId(71), aliceChat, at(1735689651), Telegram.Message.Content.Unknown(raw(message)))
            ))
        )
        assert(Json.decode[Telegram.CallbackQuery](json) == Result.succeed(value))
        assert(wire(value) == wire(json))
    }

    "inline mode: inline_message_id and no message" in {
        import TelegramTest.Fixtures.*
        val json = s"""{"id":"4382bfdwdsb323b2d9","from":$aliceUserJson,"chat_instance":"-8204785648543254131","data":"vote:yes",""" +
            """"inline_message_id":"AgAAAJ4"}"""
        val value = Telegram.CallbackQuery(
            Telegram.CallbackQueryId("4382bfdwdsb323b2d9"),
            aliceUser,
            "-8204785648543254131",
            inlineMessageId = Present("AgAAAJ4"),
            data = Present("vote:yes")
        )
        assert(Json.decode[Telegram.CallbackQuery](json) == Result.succeed(value))
        assert(wire(value) == wire(json))
        assert(value.chat == Absent)
    }

    "a game button has game_short_name and no data; the game name is not modelled" in {
        import TelegramTest.Fixtures.*
        val json  = s"""{"id":"4382bfdwdsb323b2e1","from":$aliceUserJson,"chat_instance":"-1","game_short_name":"snake"}"""
        val value = Telegram.CallbackQuery(Telegram.CallbackQueryId("4382bfdwdsb323b2e1"), aliceUser, "-1")
        assert(Json.decode[Telegram.CallbackQuery](json) == Result.succeed(value))
        assert(wire(value) == wire(s"""{"id":"4382bfdwdsb323b2e1","from":$aliceUserJson,"chat_instance":"-1"}"""))
    }

end TelegramCallbackQueryTest
