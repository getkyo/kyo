package kyo

import kyo.EmailLiterals.*

class EmailImapInboxEventTest extends kyo.test.Test[Any]:

    private val uid = uidOf(Email.MailboxName.Inbox, uidValidityOf(3857529045L), 42L)

    "round-trips through Json, a received message and every unreadable item" in {
        val received = EmailImap.InboxEvent.Received(
            uid,
            Set(Email.Flag.Seen, keywordOf("$Work")),
            instantOf("1996-07-17T09:44:25Z"),
            ByteSize.fromBytes(12L),
            Span.from("Subject: a\r\n".getBytes("ISO-8859-1"))
        )
        Seq[EmailImap.InboxEvent](
            received,
            EmailImap.InboxEvent.Unreadable(uid, EmailImap.InboxEvent.Unreadable.Item.InternalDate),
            EmailImap.InboxEvent.Unreadable(uid, EmailImap.InboxEvent.Unreadable.Item.Flags),
            EmailImap.InboxEvent.Unreadable(uid, EmailImap.InboxEvent.Unreadable.Item.Size),
            EmailImap.InboxEvent.Unreadable(
                uid,
                EmailImap.InboxEvent.Unreadable.Item.TooLarge(ByteSize.fromBytes(2000L), ByteSize.fromBytes(1000L))
            )
        ).foreach { event =>
            assert(Json.decode[EmailImap.InboxEvent](Json.encode(event)) == Result.succeed(event))
        }
    }

end EmailImapInboxEventTest
