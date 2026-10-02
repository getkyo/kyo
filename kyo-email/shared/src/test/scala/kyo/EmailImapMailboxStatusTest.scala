package kyo

import kyo.EmailLiterals.*

class EmailImapMailboxStatusTest extends kyo.test.Test[Any]:

    "round-trips through Json" in {
        val status = EmailImap.MailboxStatus(mailboxOf("Archive"), 231L, 3L, 44292L, uidValidityOf(3857529045L))
        assert(Json.decode[EmailImap.MailboxStatus](Json.encode(status)) == Result.succeed(status))
    }

end EmailImapMailboxStatusTest
