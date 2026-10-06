package kyo

import kyo.EmailLiterals.*

class EmailReceiveMailboxStatusTest extends kyo.test.Test[Any]:

    "round-trips through Json" in {
        val status = EmailReceive.MailboxStatus(mailboxOf("Archive"), 231L, 3L, 44292L, uidValidityOf(3857529045L))
        assert(Json.decode[EmailReceive.MailboxStatus](Json.encode(status)) == Result.succeed(status))
    }

end EmailReceiveMailboxStatusTest
