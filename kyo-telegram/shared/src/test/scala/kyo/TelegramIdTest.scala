package kyo

class TelegramIdTest extends kyo.test.Test[Any]:

    "numeric ids encode as bare JSON numbers and decode back, beyond 32 bits" in {
        val chat = TelegramId.ChatId(-1009876543210L)
        assert(Json.encode(chat) == "-1009876543210")
        assert(Json.decode[TelegramId.ChatId]("-1009876543210") == Result.succeed(chat))
        assert(Json.encode(TelegramId.UserId(4503599627370495L)) == "4503599627370495")
        assert(Json.decode[TelegramId.UpdateId]("2147483648") == Result.succeed(TelegramId.UpdateId(2147483648L)))
        assert(Json.encode(TelegramId.MessageId(42)) == "42")
        assert(Json.decode[TelegramId.MessageThreadId]("7") == Result.succeed(TelegramId.MessageThreadId(7)))
    }

    "string ids encode as bare JSON strings and decode back" in {
        assert(Json.encode(TelegramId.CallbackQueryId("q1")) == "\"q1\"")
        assert(Json.decode[TelegramId.FileId]("\"f1\"") == Result.succeed(TelegramId.FileId("f1")))
        assert(Json.decode[TelegramId.FileUniqueId]("\"u1\"") == Result.succeed(TelegramId.FileUniqueId("u1")))
    }

end TelegramIdTest
