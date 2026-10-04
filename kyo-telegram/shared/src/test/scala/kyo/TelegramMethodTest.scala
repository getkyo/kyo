package kyo

class TelegramMethodTest extends kyo.test.Test[Any]:

    "a name of ASCII letters, digits, dots and underscores is accepted and is its value" in {
        val names = Chunk("getChatMemberCount", "x", "sendMessage2", "a.b", "get_me", "._")
        assert(names.map(Telegram.Method.init(_).map(_.value)) == names.map(Result.succeed(_)))
    }

    "a name that would move the token's request elsewhere fails at the first character outside the alphabet, or as only dots" in {
        import TelegramInvalidMethodException.Problem
        val names = Chunk("x/../y", "getMe?x", "getMe#x", "get%2FMe", "getMe-2", "getéMe", "..", ".", "")
        assert(names.map(Telegram.Method.init(_)) == Chunk(
            Problem.Character(1),
            Problem.Character(5),
            Problem.Character(5),
            Problem.Character(3),
            Problem.Character(5),
            Problem.Character(3),
            Problem.Dots,
            Problem.Dots,
            Problem.Empty
        ).map(p => Result.fail(TelegramInvalidMethodException(p))))
    }

    // A KyoException's message quotes the source around its frame, so the name is passed in rather than written at the call.
    private def rejected(name: String): Maybe[TelegramInvalidMethodException] = Telegram.Method.init(name).failure

    "the message names the position and never the name" in {
        val e = rejected("getMe?hidden")
        assert(e.exists(_.getMessage.contains("Telegram.Method is not usable: the character at position 5 is not one of A-Z a-z 0-9 . _.")))
        assert(!e.exists(_.getMessage.contains("hidden")))
    }

end TelegramMethodTest
