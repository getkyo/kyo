package kyo

import kyo.EmailLiterals.*

class EmailReceiveInboxEventTest extends kyo.test.Test[Any]:

    private val uid = uidOf(Email.MailboxName.Inbox, uidValidityOf(3857529045L), 42L)

    "round-trips through Json, a received message and every unreadable item" in {
        val received = EmailReceive.InboxEvent.Received(
            uid,
            Set(Email.Flag.Seen, keywordOf("$Work")),
            instantOf("1996-07-17T09:44:25Z"),
            ByteSize.fromBytes(12L),
            Span.from("Subject: a\r\n".getBytes("ISO-8859-1"))
        )
        Seq[EmailReceive.InboxEvent](
            received,
            EmailReceive.InboxEvent.Unreadable(uid, EmailReceive.InboxEvent.Unreadable.Item.InternalDate),
            EmailReceive.InboxEvent.Unreadable(uid, EmailReceive.InboxEvent.Unreadable.Item.Flags),
            EmailReceive.InboxEvent.Unreadable(uid, EmailReceive.InboxEvent.Unreadable.Item.Size),
            EmailReceive.InboxEvent.Unreadable(
                uid,
                EmailReceive.InboxEvent.Unreadable.Item.TooLarge(ByteSize.fromBytes(2000L), ByteSize.fromBytes(1000L))
            )
        ).foreach { event =>
            assert(Json.decode[EmailReceive.InboxEvent](Json.encode(event)) == Result.succeed(event))
        }
    }

end EmailReceiveInboxEventTest
