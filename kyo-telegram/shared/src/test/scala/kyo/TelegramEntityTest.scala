package kyo

class TelegramEntityTest extends kyo.test.Test[Any]:

    "of reads the entity's span in UTF-16 code units, and nothing outside the text" in {
        val text = "👍 /start now"
        assert(TelegramEntity(TelegramEntity.Kind.BotCommand, 3, 6).of(text) == Present("/start"))
        assert(TelegramEntity(TelegramEntity.Kind.Bold, 0, 2).of(text) == Present("👍"))
        assert(TelegramEntity(TelegramEntity.Kind.Bold, 10, 5).of(text) == Absent)
        assert(TelegramEntity(TelegramEntity.Kind.Bold, -1, 1).of(text) == Absent)
        assert(TelegramEntity(TelegramEntity.Kind.Bold, 1, Int.MaxValue).of(text) == Absent)
    }

end TelegramEntityTest
