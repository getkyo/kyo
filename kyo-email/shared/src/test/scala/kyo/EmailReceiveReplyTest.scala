package kyo

class EmailReceiveReplyTest extends kyo.test.Test[Any]:

    "round-trips through Json, an untagged line with a literal kept inline" in {
        val reply = EmailReceive.Reply(Chunk("* ID (\"name\" \"Dovecot\")", "* 1 FETCH (BODY[] {5}\r\nhello)"), "ID completed.")
        assert(Json.decode[EmailReceive.Reply](Json.encode(reply)) == Result.succeed(reply))
    }

end EmailReceiveReplyTest
