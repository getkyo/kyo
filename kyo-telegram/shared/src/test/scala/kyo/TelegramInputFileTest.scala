package kyo

class TelegramInputFileTest extends kyo.test.Test[Any]:

    private def bytes(values: Int*): Span[Byte] = Span.from(values.map(_.toByte).toArray)

    "uploads of the same name, type and content are equal, with equal hashes, though their spans are distinct" in {
        val a = Telegram.InputFile.Upload("a.png", bytes(1, 2, 3), Present("image/png"))
        val b = Telegram.InputFile.Upload("a.png", bytes(1, 2, 3), Present("image/png"))
        assert((a == b, a.hashCode == b.hashCode) == (true, true))
    }

    "uploads that differ in content, name or type are not equal" in {
        val base = Telegram.InputFile.Upload("a.png", bytes(1, 2, 3), Present("image/png"))
        assert(Chunk(
            base.copy(bytes = bytes(1, 2, 4)),
            base.copy(bytes = bytes(1, 2)),
            base.copy(name = "b.png"),
            base.copy(contentType = Absent)
        ).map(_ == base) == Chunk(false, false, false, false))
    }

    "content that holds an upload compares by the upload's bytes" in {
        def photo = Telegram.Content.Photo(Telegram.InputFile.Upload("a.png", bytes(9, 8), Absent))
        assert(photo == photo)
    }

end TelegramInputFileTest
