package kyo

class EmailReceivePartTest extends kyo.test.Test[Any]:

    "round-trips through Json, a multipart structure with nested parts" in {
        val text = EmailReceive.Part(
            Chunk(1),
            EmailLiterals.mediaTypeOf("text", "plain", "charset" -> "UTF-8"),
            "7bit",
            ByteSize.fromBytes(10L),
            Absent,
            Absent,
            Absent,
            Chunk.empty
        )
        val logo = EmailReceive.Part(
            Chunk(2),
            EmailLiterals.mediaTypeOf("image", "png"),
            "base64",
            ByteSize.fromBytes(400L),
            Present("inline"),
            Present("logo.png"),
            Present(EmailLiterals.contentIdOf("<logo@example.com>")),
            Chunk.empty
        )
        val root = EmailReceive.Part(
            Chunk.empty,
            EmailLiterals.mediaTypeOf("multipart", "related"),
            "7bit",
            ByteSize.fromBytes(0L),
            Absent,
            Absent,
            Absent,
            Chunk(text, logo)
        )
        assert(Json.decode[EmailReceive.Part](Json.encode(root)) == Result.succeed(root))
    }

end EmailReceivePartTest
