package kyo

class TelegramCommandTest extends kyo.test.Test[Any]:

    "a name of 1 and of 32 characters from a-z 0-9 _ with a description of 1 and of 256 is accepted" in {
        val short = TelegramCommand("a", "d")
        val long  = TelegramCommand("start_2" + "x" * 25, "d" * 256)
        assert((short.name, short.description) == ("a", "d"))
        assert((long.name, long.description) == ("start_2" + "x" * 25, "d" * 256))
    }

    "construction panics with what Telegram would refuse" in {
        import TelegramInvalidCommandException.Problem
        val found = Chunk(
            intercept[TelegramInvalidCommandException](TelegramCommand("", "d")),
            intercept[TelegramInvalidCommandException](TelegramCommand("a" * 33, "d")),
            intercept[TelegramInvalidCommandException](TelegramCommand("Start", "d")),
            intercept[TelegramInvalidCommandException](TelegramCommand("go-on", "d")),
            intercept[TelegramInvalidCommandException](TelegramCommand("go", "")),
            intercept[TelegramInvalidCommandException](TelegramCommand("go", "d" * 257))
        )
        assert(found == Chunk(
            Problem.NameLength(0),
            Problem.NameLength(33),
            Problem.NameCharacter(0),
            Problem.NameCharacter(2),
            Problem.DescriptionLength(0),
            Problem.DescriptionLength(257)
        ).map(TelegramInvalidCommandException(_)))
    }

end TelegramCommandTest
