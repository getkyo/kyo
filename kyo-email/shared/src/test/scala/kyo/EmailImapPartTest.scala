package kyo

class EmailImapPartTest extends kyo.test.Test[Any]:

    "round-trips through Json, a multipart structure with nested parts" in {
        val text = EmailImap.Part(
            Chunk(1),
            EmailLiterals.mediaTypeOf("text", "plain", "charset" -> "UTF-8"),
            "7bit",
            ByteSize.fromBytes(10L),
            Absent,
            Absent,
            Absent,
            Chunk.empty
        )
        val logo = EmailImap.Part(
            Chunk(2),
            EmailLiterals.mediaTypeOf("image", "png"),
            "base64",
            ByteSize.fromBytes(400L),
            Present("inline"),
            Present("logo.png"),
            Present(EmailLiterals.contentIdOf("<logo@example.com>")),
            Chunk.empty
        )
        val root = EmailImap.Part(
            Chunk.empty,
            EmailLiterals.mediaTypeOf("multipart", "related"),
            "7bit",
            ByteSize.fromBytes(0L),
            Absent,
            Absent,
            Absent,
            Chunk(text, logo)
        )
        assert(Json.decode[EmailImap.Part](Json.encode(root)) == Result.succeed(root))
    }

end EmailImapPartTest
