package kyo

class EmailInlinePartTest extends kyo.test.Test[Any]:

    private def bytes(text: String): Span[Byte] = Span.from(text.getBytes("UTF-8"))

    private val png  = EmailLiterals.mediaTypeOf("image", "png")
    private val logo = EmailLiterals.contentIdOf("<logo@example.com>")

    "equals a part with the same bytes in a different span" in {
        val a = Email.InlinePart(logo, png, Present("logo.png"), bytes("\u0089PNG"))
        val b = Email.InlinePart(EmailLiterals.contentIdOf("logo@example.com"), png, Present("logo.png"), bytes("\u0089PNG"))
        assert(a == b)
        assert(a.hashCode == b.hashCode)
    }

    "differs when the content id differs" in {
        assert(Email.InlinePart(logo, png, Absent, bytes("x")) !=
            Email.InlinePart(EmailLiterals.contentIdOf("banner@example.com"), png, Absent, bytes("x")))
    }

    "differs when the bytes differ" in {
        assert(Email.InlinePart(logo, png, Absent, bytes("x")) != Email.InlinePart(logo, png, Absent, bytes("y")))
    }

end EmailInlinePartTest
