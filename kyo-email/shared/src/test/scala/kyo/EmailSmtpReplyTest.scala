package kyo

class EmailSmtpReplyTest extends kyo.test.Test[Any]:

    "round-trips through Json, with and without an enhanced status code" in {
        Seq(
            EmailSmtp.Reply(
                250,
                Present(EmailLiterals.statusCodeOf(EmailSmtp.EnhancedStatusCode.StatusClass.Success, 0, 0)),
                Chunk("OK")
            ),
            EmailSmtp.Reply(214, Absent, Chunk("Commands:", "HELO EHLO MAIL"))
        ).foreach { reply =>
            assert(Json.decode[EmailSmtp.Reply](Json.encode(reply)) == Result.succeed(reply))
        }
    }

end EmailSmtpReplyTest
