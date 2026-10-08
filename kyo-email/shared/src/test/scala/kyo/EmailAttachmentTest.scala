package kyo

class EmailAttachmentTest extends kyo.test.Test[Any]:

    private def bytes(text: String): Span[Byte] = Span.from(text.getBytes("UTF-8"))

    private val pdf    = EmailLiterals.mediaTypeOf("application", "pdf")
    private val invite = EmailLiterals.mediaTypeOf("text", "calendar", "method" -> "REQUEST", "charset" -> "UTF-8")

    "equals an attachment with the same bytes in a different span" in {
        val a = Email.Attachment(pdf, Present("invoice.pdf"), bytes("%PDF-1.7"))
        val b = Email.Attachment(pdf, Present("invoice.pdf"), bytes("%PDF-1.7"))
        assert(a == b)
        assert(a.hashCode == b.hashCode)
    }

    "differs when the bytes differ" in {
        assert(Email.Attachment(pdf, Present("invoice.pdf"), bytes("%PDF-1.7")) !=
            Email.Attachment(pdf, Present("invoice.pdf"), bytes("%PDF-1.6")))
    }

    "differs when the file name differs" in {
        assert(Email.Attachment(pdf, Present("invoice.pdf"), bytes("%PDF-1.7")) != Email.Attachment(pdf, Absent, bytes("%PDF-1.7")))
    }

    "differs when a content type parameter differs" in {
        val cancel = EmailLiterals.mediaTypeOf("text", "calendar", "method" -> "CANCEL", "charset" -> "UTF-8")
        assert(Email.Attachment(invite, Absent, bytes("BEGIN:VCALENDAR")) != Email.Attachment(cancel, Absent, bytes("BEGIN:VCALENDAR")))
    }

    "keeps the parameters of its content type" in {
        val attachment = Email.Attachment(invite, Present("invite.ics"), bytes("BEGIN:VCALENDAR"))
        assert(attachment.contentType.parameter("method") == Present("REQUEST"))
        assert(attachment.contentType.charset == Present("UTF-8"))
    }

end EmailAttachmentTest
