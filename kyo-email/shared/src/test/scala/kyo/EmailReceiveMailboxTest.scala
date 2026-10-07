package kyo

class EmailReceiveMailboxTest extends kyo.test.Test[Any]:

    "attributes are read in any case of their name, RFC 9051's and the special uses alike" in {
        assert(EmailReceive.Mailbox.Attribute.fromWire("\\NOSELECT") == EmailReceive.Mailbox.Attribute.NoSelect)
        assert(EmailReceive.Mailbox.Attribute.fromWire("\\haschildren") == EmailReceive.Mailbox.Attribute.HasChildren)
        assert(EmailReceive.Mailbox.Attribute.fromWire("\\Sent") == EmailReceive.Mailbox.Attribute.Sent)
        assert(EmailReceive.Mailbox.Attribute.fromWire("\\junk") == EmailReceive.Mailbox.Attribute.Junk)
        assert(EmailReceive.Mailbox.Attribute.fromWire("\\Important") == EmailReceive.Mailbox.Attribute.Important)
    }

    "an attribute the module does not model is kept as written" in {
        assert(EmailReceive.Mailbox.Attribute.fromWire("\\XList") == EmailReceive.Mailbox.Attribute.Other("\\XList"))
    }

    "round-trips through Json" in {
        val attributes =
            Set[EmailReceive.Mailbox.Attribute](EmailReceive.Mailbox.Attribute.Sent, EmailReceive.Mailbox.Attribute.Other("\\XList"))
        Seq(
            EmailReceive.Mailbox(EmailLiterals.mailboxOf("Entwürfe"), Present('/'), attributes),
            EmailReceive.Mailbox(Email.MailboxName.Inbox, Absent, Set.empty)
        ).foreach { mailbox =>
            assert(Json.decode[EmailReceive.Mailbox](Json.encode(mailbox)) == Result.succeed(mailbox))
        }
    }

end EmailReceiveMailboxTest
