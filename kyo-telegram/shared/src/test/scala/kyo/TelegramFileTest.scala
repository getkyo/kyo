package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramFileTest extends kyo.test.Test[Any]:

    private val id     = Telegram.FileId("BQACAgIAAxkBAAIB")
    private val unique = Telegram.FileUniqueId("AgADzQ0AAqS")

    "a File is Telegram's JSON, both ways" in {
        val json = """{"file_id":"BQACAgIAAxkBAAIB","file_unique_id":"AgADzQ0AAqS","file_size":20480,"file_path":"documents/file_1.pdf"}"""
        val file = Telegram.File(id, unique, Present(20480.bytes), Present("documents/file_1.pdf"))
        assert(Json.decode[Telegram.File](json) == Result.succeed(file))
        assert(wire(file) == wire(json))
    }

    "a File without a path, as for one too big to download" in {
        val json = """{"file_id":"BQACAgIAAxkBAAIB","file_unique_id":"AgADzQ0AAqS"}"""
        assert(Json.decode[Telegram.File](json) == Result.succeed(Telegram.File(id, unique)))
        assert(wire(Telegram.File(id, unique)) == wire(json))
    }

end TelegramFileTest
