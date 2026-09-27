package kyo

class TelegramKeyboardTest extends kyo.test.Test[Any]:

    "callback data of 1 and of 64 bytes is accepted, counted in UTF-8" in {
        assert(TelegramKeyboard.CallbackData("a").value == "a")
        assert(TelegramKeyboard.CallbackData("a" * 64).value == "a" * 64)
        assert(TelegramKeyboard.CallbackData("é" * 32).value == "é" * 32)
    }

    "callback data outside 1 to 64 bytes panics with its length in bytes" in {
        val lengths = Chunk("", "a" * 65, "é" * 33, "👍" * 17).map { data =>
            intercept[TelegramInvalidCallbackDataException](TelegramKeyboard.CallbackData(data))
        }
        assert(lengths == Chunk(0, 65, 66, 68).map(TelegramInvalidCallbackDataException(_)))
    }

    "the row helpers build the keyboards they name" in {
        import TelegramKeyboard.*
        assert(TelegramKeyboard.inline(Seq(InlineButton.callback("A", "a")), Seq(InlineButton.Url("B", TelegramUrl("tg://user?id=1")))) ==
            Inline(Chunk(
                Chunk(InlineButton.Callback("A", CallbackData("a"))),
                Chunk(InlineButton.Url("B", TelegramUrl("tg://user?id=1")))
            )))
        assert(TelegramKeyboard.reply(Seq(ReplyButton.Text("Hi"))) == Reply(Chunk(Chunk(ReplyButton.Text("Hi")))))
    }

end TelegramKeyboardTest
