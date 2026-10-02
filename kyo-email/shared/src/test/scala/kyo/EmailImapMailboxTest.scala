package kyo

class EmailImapMailboxTest extends kyo.test.Test[Any]:

    "attributes are read in any case of their name, RFC 9051's and the special uses alike" in {
        assert(EmailImap.Mailbox.Attribute.fromWire("\\NOSELECT") == EmailImap.Mailbox.Attribute.NoSelect)
        assert(EmailImap.Mailbox.Attribute.fromWire("\\haschildren") == EmailImap.Mailbox.Attribute.HasChildren)
        assert(EmailImap.Mailbox.Attribute.fromWire("\\Sent") == EmailImap.Mailbox.Attribute.Sent)
        assert(EmailImap.Mailbox.Attribute.fromWire("\\junk") == EmailImap.Mailbox.Attribute.Junk)
        assert(EmailImap.Mailbox.Attribute.fromWire("\\Important") == EmailImap.Mailbox.Attribute.Important)
    }

    "an attribute the module does not model is kept as written" in {
        assert(EmailImap.Mailbox.Attribute.fromWire("\\XList") == EmailImap.Mailbox.Attribute.Other("\\XList"))
    }

    "round-trips through Json" in {
        val attributes = Set[EmailImap.Mailbox.Attribute](EmailImap.Mailbox.Attribute.Sent, EmailImap.Mailbox.Attribute.Other("\\XList"))
        Seq(
            EmailImap.Mailbox(EmailLiterals.mailboxOf("Entwürfe"), Present('/'), attributes),
            EmailImap.Mailbox(Email.MailboxName.Inbox, Absent, Set.empty)
        ).foreach { mailbox =>
            assert(Json.decode[EmailImap.Mailbox](Json.encode(mailbox)) == Result.succeed(mailbox))
        }
    }

end EmailImapMailboxTest
