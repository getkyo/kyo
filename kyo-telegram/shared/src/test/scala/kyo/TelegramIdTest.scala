package kyo

class TelegramIdTest extends kyo.test.Test[Any]:

    "numeric ids encode as bare JSON numbers and decode back, beyond 32 bits" in {
        val chat = Telegram.ChatId(-1009876543210L)
        assert(Json.encode(chat) == "-1009876543210")
        assert(Json.decode[Telegram.ChatId]("-1009876543210") == Result.succeed(chat))
        assert(Json.encode(Telegram.UserId(4503599627370495L)) == "4503599627370495")
        assert(Json.decode[Telegram.UpdateId]("2147483648") == Result.succeed(Telegram.UpdateId(2147483648L)))
        assert(Json.encode(Telegram.MessageId(42)) == "42")
        assert(Json.decode[Telegram.MessageThreadId]("7") == Result.succeed(Telegram.MessageThreadId(7)))
    }

    "string ids encode as bare JSON strings and decode back" in {
        assert(Json.encode(Telegram.CallbackQueryId("q1")) == "\"q1\"")
        assert(Json.decode[Telegram.FileId]("\"f1\"") == Result.succeed(Telegram.FileId("f1")))
        assert(Json.decode[Telegram.FileUniqueId]("\"u1\"") == Result.succeed(Telegram.FileUniqueId("u1")))
    }

end TelegramIdTest
