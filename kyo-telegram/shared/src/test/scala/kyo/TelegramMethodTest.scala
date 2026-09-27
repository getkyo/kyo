package kyo

class TelegramMethodTest extends kyo.test.Test[Any]:

    // A KyoException's message quotes the source around its frame, so the name is passed in rather than written at the call.
    private def rejected(name: String)(using kyo.test.AssertScope): TelegramInvalidMethodException =
        intercept[TelegramInvalidMethodException](TelegramMethod(name))

    "a name of ASCII letters, digits, dots and underscores is accepted and is its value" in {
        val names = Chunk("getChatMemberCount", "x", "sendMessage2", "a.b", "get_me", "._")
        assert(names.map(TelegramMethod(_).value) == names)
    }

    "a name that would move the token's request elsewhere panics at the first character outside the alphabet, or as only dots" in {
        import TelegramInvalidMethodException.Problem
        val found = Chunk(
            intercept[TelegramInvalidMethodException](TelegramMethod("x/../y")),
            intercept[TelegramInvalidMethodException](TelegramMethod("getMe?x")),
            intercept[TelegramInvalidMethodException](TelegramMethod("getMe#x")),
            intercept[TelegramInvalidMethodException](TelegramMethod("get%2FMe")),
            intercept[TelegramInvalidMethodException](TelegramMethod("getMe-2")),
            intercept[TelegramInvalidMethodException](TelegramMethod("getéMe")),
            intercept[TelegramInvalidMethodException](TelegramMethod("..")),
            intercept[TelegramInvalidMethodException](TelegramMethod(".")),
            intercept[TelegramInvalidMethodException](TelegramMethod(""))
        )
        assert(found == Chunk(
            Problem.Character(1),
            Problem.Character(5),
            Problem.Character(5),
            Problem.Character(3),
            Problem.Character(5),
            Problem.Character(3),
            Problem.Dots,
            Problem.Dots,
            Problem.Empty
        ).map(TelegramInvalidMethodException(_)))
    }

    "the message names the position and never the name" in {
        val e = rejected("getMe?hidden")
        assert(e.getMessage.contains("TelegramMethod is not usable: the character at position 5 is not one of A-Z a-z 0-9 . _."))
        assert(!e.getMessage.contains("hidden"))
    }

end TelegramMethodTest
