package kyo

import kyo.EmailLiterals.*

class EmailImapSummaryTest extends kyo.test.Test[Any]:

    "round-trips through Json, with its header and structure" in {
        val summary = EmailImap.Summary(
            uidOf(mailboxOf("Archive"), uidValidityOf(3857529045L), 7L),
            Set[Email.Flag](Email.Flag.Answered) ++ Email.Flag.fromWire("\\Forwarded").toList,
            instantOf("1996-07-17T09:44:25Z"),
            ByteSize.fromBytes(2048L),
            Email.Message(
                from = Chunk(Email.Address("ada@example.com", Present("Ada Lovelace"))),
                subject = "Quarterly report",
                headers = Chunk(Email.Header("From", "Ada Lovelace <ada@example.com>"), Email.Header("Subject", "Quarterly report"))
            ),
            EmailImap.Part(
                Chunk(1),
                EmailLiterals.mediaTypeOf("text", "plain"),
                "7bit",
                ByteSize.fromBytes(100L),
                Absent,
                Absent,
                Absent,
                Chunk.empty
            )
        )
        assert(Json.decode[EmailImap.Summary](Json.encode(summary)) == Result.succeed(summary))
    }

end EmailImapSummaryTest
