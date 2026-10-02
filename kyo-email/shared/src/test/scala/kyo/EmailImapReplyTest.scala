package kyo

class EmailImapReplyTest extends kyo.test.Test[Any]:

    "round-trips through Json, an untagged line with a literal kept inline" in {
        val reply = EmailImap.Reply(Chunk("* ID (\"name\" \"Dovecot\")", "* 1 FETCH (BODY[] {5}\r\nhello)"), "ID completed.")
        assert(Json.decode[EmailImap.Reply](Json.encode(reply)) == Result.succeed(reply))
    }

end EmailImapReplyTest
