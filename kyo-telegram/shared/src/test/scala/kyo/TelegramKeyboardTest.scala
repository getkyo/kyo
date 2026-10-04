package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramKeyboardTest extends kyo.test.Test[Any]:

    "callback data of 1 and of 64 bytes is accepted, counted in UTF-8" in {
        val data = Chunk("a", "a" * 64, "é" * 32)
        assert(data.map(Telegram.Keyboard.CallbackData.init(_).map(_.value)) == data.map(Result.succeed(_)))
    }

    "callback data outside 1 to 64 bytes fails with its length in bytes" in {
        val found = Chunk("", "a" * 65, "é" * 33, "👍" * 17).map(Telegram.Keyboard.CallbackData.init(_))
        assert(found == Chunk(0, 65, 66, 68).map(n => Result.fail(TelegramInvalidCallbackDataException(n))))
    }

    "a lone surrogate counts the 3 bytes it is sent as, not 1" in {
        val found = Telegram.Keyboard.CallbackData.init("a" * 62 + "\uD800")
        assert(found == Result.fail(TelegramInvalidCallbackDataException(65)))
    }

    "a callback button with data outside 1 to 64 bytes fails as its data does" in {
        assert(Telegram.Keyboard.InlineButton.callback("A", "") == Result.fail(TelegramInvalidCallbackDataException(0)))
    }

    "callback data is a JSON string, and data outside 1 to 64 bytes fails the decode" in {
        val data = Telegram.Keyboard.CallbackData.init("vote:1").getOrThrow
        assert(Json.encode(data) == """"vote:1"""")
        assert(Json.decode[Telegram.Keyboard.CallbackData](""""vote:1"""") == Result.succeed(data))
        assert(Json.decode[Telegram.Keyboard.CallbackData](s""""${"a" * 65}"""").failure.exists(
            _.isInstanceOf[ConstructorRejectedException]
        ))
    }

    "each keyboard is Telegram's reply markup object, both ways" in {
        import Telegram.Keyboard.*
        val link      = Telegram.Url.init("https://getkyo.io").getOrThrow
        val keyboards = Chunk[Telegram.Keyboard](
            Telegram.Keyboard.inline(Seq(InlineButton.callback("Done", "standup:done").getOrThrow, InlineButton.Url("Docs", link))),
            Reply(
                Chunk(Chunk(ReplyButton.Text("Hi"), ReplyButton.requestContact("Phone"), ReplyButton.requestLocation("Where"))),
                resize = true,
                oneTime = true,
                persistent = true,
                placeholder = Present("Say hi")
            ),
            Telegram.Keyboard.remove,
            Telegram.Keyboard.forceReply(Present("Your status"))
        )
        val json = Chunk(
            """{"inline_keyboard":[[{"text":"Done","callback_data":"standup:done"},{"text":"Docs","url":"https://getkyo.io"}]]}""",
            """{"keyboard":[[{"text":"Hi"},{"text":"Phone","request_contact":true},{"text":"Where","request_location":true}]],""" +
                """"resize_keyboard":true,"one_time_keyboard":true,"is_persistent":true,"input_field_placeholder":"Say hi"}""",
            """{"remove_keyboard":true}""",
            """{"force_reply":true,"input_field_placeholder":"Your status"}"""
        )
        assert(keyboards.map(wire(_)) == json.map(wire))
        assert(json.map(Json.decode[Telegram.Keyboard](_)) == keyboards.map(Result.succeed(_)))
    }

    "a reply keyboard without its flags reads them as false, as Telegram does" in {
        assert(Json.decode[Telegram.Keyboard]("""{"keyboard":[[{"text":"Hi"}]]}""") ==
            Result.succeed(Telegram.Keyboard.Reply(Chunk(Chunk(Telegram.Keyboard.ReplyButton.Text("Hi"))))))
    }

    "a marker that is false is not the object it marks" in {
        assert(Json.decode[Telegram.Keyboard]("""{"remove_keyboard":false}""").isFailure)
    }

    "an inline button of a kind the module does not model is Other, written back as Telegram sent it" in {
        val json = """{"text":"Play","callback_game":{}}"""
        Json.decode[Telegram.Keyboard.InlineButton](json) match
            case Result.Success(other @ Telegram.Keyboard.InlineButton.Other(_)) =>
                assert(wire(other: Telegram.Keyboard.InlineButton) == wire(json))
            case unexpected => fail(s"expected Other, got $unexpected")
        end match
    }

    "callback data is counted in bytes: 16 four-byte emoji fit, 17 do not" in {
        assert(Chunk("👍" * 16, "👍" * 17).map(Telegram.Keyboard.CallbackData.init(_).isSuccess) == Chunk(true, false))
    }

    "a button's style is kept both ways, and a style the module does not model is Other" in {
        import Telegram.Keyboard.*
        val json = """{"inline_keyboard":[[{"text":"Delete","callback_data":"rm:42","style":"danger"},""" +
            """{"text":"Docs","url":"https://kyo.dev/docs","style":"primary"},{"text":"Ok","callback_data":"ok","style":"success"},""" +
            """{"text":"New","callback_data":"n","style":"glow"},{"text":"Plain","callback_data":"p"}]]}"""
        val keyboard = Inline(Chunk(Chunk(
            InlineButton.callback("Delete", "rm:42", Present(Style.Danger)).getOrThrow,
            InlineButton.Url("Docs", Telegram.Url.init("https://kyo.dev/docs").getOrThrow, Present(Style.Primary)),
            InlineButton.callback("Ok", "ok", Present(Style.Success)).getOrThrow,
            InlineButton.callback("New", "n", Present(Style.Other("glow"))).getOrThrow,
            InlineButton.callback("Plain", "p").getOrThrow
        )))
        assert(Json.decode[Telegram.Keyboard](json) == Result.succeed(keyboard))
        assert(wire(keyboard: Telegram.Keyboard) == wire(json))
    }

    "request buttons are read before text, so a contact or location button is not read as a text button" in {
        import Telegram.Keyboard.*
        val json = """{"keyboard":[[{"text":"Share phone","request_contact":true},{"text":"Share location","request_location":true}],""" +
            """[{"text":"Cancel"}]],"resize_keyboard":true,"one_time_keyboard":true,"input_field_placeholder":"Pick one"}"""
        val keyboard = Reply(
            Chunk(
                Chunk(ReplyButton.requestContact("Share phone"), ReplyButton.requestLocation("Share location")),
                Chunk(ReplyButton.Text("Cancel"))
            ),
            resize = true,
            oneTime = true,
            placeholder = Present("Pick one")
        )
        assert(Json.decode[Telegram.Keyboard](json) == Result.succeed(keyboard))
        assert(wire(keyboard: Telegram.Keyboard) == wire(json))
    }

    "is_persistent true is written, and false is omitted" in {
        import Telegram.Keyboard.*
        val persistent = Reply(Chunk(Chunk(ReplyButton.Text("Menu"))), persistent = true)
        assert(Json.decode[Telegram.Keyboard]("""{"keyboard":[[{"text":"Menu"}]],"is_persistent":true}""") == Result.succeed(persistent))
        assert(wire(persistent: Telegram.Keyboard) == wire("""{"keyboard":[[{"text":"Menu"}]],"is_persistent":true}"""))
        assert(wire(persistent.copy(persistent = false): Telegram.Keyboard) == wire("""{"keyboard":[[{"text":"Menu"}]]}"""))
    }

    "a button given as a bare string, which the module never writes, is not read" in {
        assert(Json.decode[Telegram.Keyboard]("""{"keyboard":[["Yes","No"]]}""").isFailure)
    }

    "an inline keyboard with the 10.x force_reply flag reads as the inline keyboard, and the flag is dropped" in {
        import Telegram.Keyboard.*
        val keyboard = Inline(Chunk(Chunk(InlineButton.callback("Yes", "vote:yes").getOrThrow)))
        assert(Json.decode[Telegram.Keyboard]("""{"inline_keyboard":[[{"text":"Yes","callback_data":"vote:yes"}]],"force_reply":true}""") ==
            Result.succeed(keyboard))
        assert(wire(keyboard: Telegram.Keyboard) == wire("""{"inline_keyboard":[[{"text":"Yes","callback_data":"vote:yes"}]]}"""))
    }

    "the row helpers build the keyboards they name" in {
        import Telegram.Keyboard.*
        val link = Telegram.Url.init("tg://user?id=1").getOrThrow
        assert(Telegram.Keyboard.inline(Seq(InlineButton.callback("A", "a").getOrThrow), Seq(InlineButton.Url("B", link))) ==
            Inline(Chunk(
                Chunk(InlineButton.Callback("A", CallbackData.init("a").getOrThrow)),
                Chunk(InlineButton.Url("B", link))
            )))
        assert(Telegram.Keyboard.reply(Seq(ReplyButton.Text("Hi"))) == Reply(Chunk(Chunk(ReplyButton.Text("Hi"))), resize = true))
    }

end TelegramKeyboardTest
