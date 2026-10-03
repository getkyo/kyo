package kyo.internal.email.smtp

import kyo.*
import kyo.EmailSend.EnhancedStatusCode.StatusClass
import kyo.internal.email.smtp.SmtpCodec.*

class SmtpCodecTest extends kyo.test.Test[Any]:

    private def bytes(text: String): Span[Byte] = Span.from(text.getBytes("UTF-8"))

    private def text(octets: Span[Byte]): String = new String(octets.toArray, "UTF-8")

    // Each line fed to `next` in turn, stopping at the first step that is not More.
    private def read(lines: String*): Step =
        lines.foldLeft[Maybe[Step]](Absent) { (step, line) =>
            step match
                case Present(Step.More(partial)) => Present(next(Present(partial), bytes(line)))
                case Present(done)               => Present(done)
                case Absent                      => Present(next(Absent, bytes(line)))
        }.getOrElse(throw new IllegalStateException("no line"))

    "replies" - {
        "one line, with text, with none, and with a bare code (RFC 5321 section 4.2)" in {
            assert(read("250 OK") == Step.Complete(Reply(250, Chunk("OK"))))
            assert(read("250 ") == Step.Complete(Reply(250, Chunk(""))))
            assert(read("354") == Step.Complete(Reply(354, Chunk(""))))
        }
        "lines with a hyphen continue it, and the first with a space ends it" in {
            assert(read("250-smtp.example.com", "250-SIZE 35882577", "250 AUTH PLAIN LOGIN") ==
                Step.Complete(Reply(250, Chunk("smtp.example.com", "SIZE 35882577", "AUTH PLAIN LOGIN"))))
            assert(read("250-first") == Step.More(Partial(250, Chunk("first"), 11)))
        }
        "the text is UTF-8, as RFC 6531 section 3.7.4 allows once SMTPUTF8 is in use" in {
            assert(read("550 boîte inconnue") == Step.Complete(Reply(550, Chunk("boîte inconnue"))))
        }
        "a line not in the reply grammar is malformed" in {
            Chunk("", "25", "250x", "2500", "abc def", "650 no such class", "260 second digit past 5", " 250 lead").foreach { line =>
                assert(read(line) == Step.Malformed(line))
            }
        }
        "a continuation line with another code is malformed" in {
            assert(read("250-first", "251 second") == Step.Malformed("251 second"))
        }
        "a malformed line past ASCII is shown with its octets escaped" in {
            assert(read("5é0 x") == Step.Malformed("5\\xc3\\xa90 x"))
        }
        "a reply past ReplyLimit is malformed, and remaining is what the next line may take" in {
            val line  = "250-" + "x" * 1020
            val count = ReplyLimit / (line.length + 2) - 1
            val step  = read(Seq.fill(count)(line)*)
            step match
                case Step.More(partial) =>
                    assert(partial.octets == count * (line.length + 2))
                    assert(remaining(Present(partial)) == ReplyLimit - partial.octets - 2)
                    val fits = "250 " + "y" * (remaining(Present(partial)) - 4)
                    assert(next(Present(partial), bytes(fits)).isInstanceOf[Step.Complete])
                    assert(next(Present(partial), bytes(fits + "y")) == Step.Malformed(fits + "y"))
                case other => fail(s"expected More, got $other")
            end match
            assert(remaining(Absent) == ReplyLimit - 2)
        }
    }

    "enhanced status codes" - {
        "read from the start of the text when its class is the reply's first digit (RFC 3463, RFC 2034)" in {
            assert(enhanced(550, "5.1.1 User unknown") ==
                Present((EmailLiterals.statusCodeOf(StatusClass.Permanent, 1, 1), "User unknown")))
            assert(enhanced(452, "4.2.2 Mailbox full") ==
                Present((EmailLiterals.statusCodeOf(StatusClass.PersistentTransient, 2, 2), "Mailbox full")))
            assert(enhanced(250, "2.0.0") == Present((EmailLiterals.statusCodeOf(StatusClass.Success, 0, 0), "")))
            assert(enhanced(535, "5.7.139 Authentication unsuccessful") ==
                Present((EmailLiterals.statusCodeOf(StatusClass.Permanent, 7, 139), "Authentication unsuccessful")))
        }
        "none when the class differs from the reply code, or the text is not a code" in {
            assert(enhanced(550, "4.1.1 mismatched class") == Absent)
            assert(enhanced(550, "3.1.1 no such class") == Absent)
            assert(enhanced(550, "5.1 two fields") == Absent)
            assert(enhanced(550, "5.1.1000 four digits") == Absent)
            assert(enhanced(550, "5..1 empty field") == Absent)
            assert(enhanced(550, "User unknown") == Absent)
            assert(enhanced(550, "") == Absent)
        }
    }

    "EHLO extensions" - {
        "each line after the greeting, the keyword uppercased with its parameters" in {
            val ehlo = Reply(250, Chunk("smtp.example.com greets you", "size 35882577", "8BITMIME", "AUTH PLAIN LOGIN XOAUTH2", "STARTTLS"))
            val offered = extensions(ehlo)
            assert(offered == Extensions(Chunk(
                ("SIZE", Chunk("35882577")),
                ("8BITMIME", Chunk.empty),
                ("AUTH", Chunk("PLAIN", "LOGIN", "XOAUTH2")),
                ("STARTTLS", Chunk.empty)
            )))
            assert(offered.offers("STARTTLS"))
            assert(!offered.offers("SMTPUTF8"))
            assert(offered.parameters("AUTH") == Present(Chunk("PLAIN", "LOGIN", "XOAUTH2")))
            assert(offered.parameters("PIPELINING") == Absent)
        }
        "the legacy AUTH=mechanism form names the same mechanisms" in {
            assert(extensions(Reply(250, Chunk("host", "AUTH=PLAIN LOGIN"))).parameters("AUTH") == Present(Chunk("PLAIN", "LOGIN")))
        }
        "a one-line reply offers nothing" in {
            assert(extensions(Reply(250, Chunk("host"))) == Extensions(Chunk.empty))
        }
    }

    "the data section" - {
        "a dot at the start of a line is doubled, and the end line follows (RFC 5321 section 4.5.2)" in {
            assert(text(dataSection(bytes(".first\r\nmid.dle\r\n..two\r\n.\r\nlast\r\n"))) ==
                "..first\r\nmid.dle\r\n...two\r\n..\r\nlast\r\n.\r\n")
        }
        "a message without a final line end gets one before the end line" in {
            assert(text(dataSection(bytes("body"))) == "body\r\n.\r\n")
            assert(text(dataSection(bytes("body\r\n."))) == "body\r\n..\r\n.\r\n")
        }
        "an empty message is the end line alone" in {
            assert(text(dataSection(Span.empty[Byte])) == ".\r\n")
        }
        "octets past ASCII pass unchanged" in {
            assert(text(dataSection(bytes("é.\r\n.é\r\n"))) == "é.\r\n..é\r\n.\r\n")
        }
    }

end SmtpCodecTest
