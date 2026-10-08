package kyo.internal.email.mime

import kyo.*
import kyo.internal.email.mime.MimeParser.Body
import kyo.internal.email.mime.MimeParser.Encoding
import kyo.internal.email.mime.MimeParser.Entity
import kyo.internal.email.mime.TransferEncoding.Kind

class MimeRendererTest extends kyo.test.Test[Any]:

    private def octets(m: Email.Message): Span[Byte] =
        MimeRenderer.render(m) match
            case Result.Success(bytes) => bytes
            case other                 => throw new IllegalStateException(s"did not render: $other")

    // One char per octet, so a test can see exactly what was written.
    private def wire(m: Email.Message): String = new String(octets(m).toArray, "ISO-8859-1")

    private def failure(m: Email.Message): Result[MimeRenderer.Failure, Unit] = MimeRenderer.render(m).map(_ => ())

    private def root(m: Email.Message): Entity =
        MimeParser.parse(octets(m), EmailLiterals.valid(MediaTypeDefaults.built)) match
            case Result.Success(entity) => entity
            case other                  => throw new IllegalStateException(s"did not parse: $other")

    private def shape(entity: Entity): String =
        entity.body match
            case Body.Leaf(_, _)          => entity.mediaType.baseType
            case Body.Multipart(children) => s"${entity.mediaType.baseType}(${children.map(shape).mkString(",")})"

    private def leaves(entity: Entity): Chunk[Entity] =
        entity.body match
            case Body.Leaf(_, _)          => Chunk(entity)
            case Body.Multipart(children) => children.flatMap(leaves)

    private def encodings(m: Email.Message): Chunk[Encoding] = leaves(root(m)).map(_.encoding)

    private def parsed(m: Email.Message): Email.Message = Abort.run[EmailParseFailure](Email.Message.parse(octets(m))).eval.getOrThrow

    private def bytes(text: String): Span[Byte] = Span.from(text.getBytes("UTF-8"))

    private def attachment(mainType: String, subType: String, content: String, parameters: (String, String)*): Email.Attachment =
        Email.Attachment(EmailLiterals.mediaTypeOf(mainType, subType, parameters*), Absent, bytes(content))

    private val png =
        Email.Attachment(EmailLiterals.mediaTypeOf("image", "png"), Present("logo.png"), Span.from(Array[Byte](-119, 80, 78, 71, 0)))
    private val logo =
        Email.InlinePart(EmailLiterals.contentIdOf("<logo@example.com>"), EmailLiterals.mediaTypeOf("image", "png"), Absent, png.content)
    private val report = Email.Attachment(EmailLiterals.mediaTypeOf("application", "pdf"), Present("q3.pdf"), bytes("%PDF"))

    private val sevenBit        = Encoding.Known(Kind.SevenBit)
    private val eightBit        = Encoding.Known(Kind.EightBit)
    private val quotedPrintable = Encoding.Known(Kind.QuotedPrintable)
    private val base64          = Encoding.Known(Kind.Base64)

    "the tree" - {
        "text alone is one text/plain part, and a message with nothing is an empty one" in {
            assert(shape(root(Email.Message(text = "Hi"))) == "text/plain")
            assert(wire(Email.Message()).endsWith("Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 7bit\r\n\r\n"))
        }
        "html alone is one text/html part" in {
            assert(shape(root(Email.Message(html = Present("<p>Hi</p>")))) == "text/html")
        }
        "text and html are an alternative, the text first" in {
            assert(shape(root(Email.Message(text = "Hi", html = Present("<p>Hi</p>")))) == "multipart/alternative(text/plain,text/html)")
        }
        "html with inline parts is related, inside the alternative when there is also text" in {
            assert(shape(root(Email.Message(html = Present("<img>"), inlineParts = Chunk(logo)))) ==
                "multipart/related(text/html,image/png)")
            assert(shape(root(Email.Message(text = "Hi", html = Present("<img>"), inlineParts = Chunk(logo)))) ==
                "multipart/alternative(text/plain,multipart/related(text/html,image/png))")
        }
        "inline parts with no html are related to the text root, written even when the text is empty" in {
            assert(shape(root(Email.Message(text = "Hi", inlineParts = Chunk(logo)))) == "multipart/related(text/plain,image/png)")
            assert(shape(root(Email.Message(inlineParts = Chunk(logo)))) == "multipart/related(text/plain,image/png)")
        }
        "attachments follow the tree in a mixed; with no body and no inline part the mixed holds only them" in {
            assert(shape(root(Email.Message(
                text = "Hi",
                html = Present("<img>"),
                inlineParts = Chunk(logo),
                attachments = Chunk(report)
            ))) ==
                "multipart/mixed(multipart/alternative(text/plain,multipart/related(text/html,image/png)),application/pdf)")
            assert(shape(root(Email.Message(attachments = Chunk(report, png)))) == "multipart/mixed(application/pdf,image/png)")
        }
    }

    "the message's headers" - {
        "extra headers first, then the fields in a fixed order, then the MIME headers; a header a field renders is skipped" in {
            val m = Email.Message(
                from = Chunk(Email.Address("ada@example.com", Present("Ada Lovelace"))),
                sender = Present(Email.Address("grace@example.com")),
                to = Chunk(Email.Address("to@example.com")),
                cc = Chunk(Email.Address("cc@example.com")),
                bcc = Chunk(Email.Address("bcc@example.com")),
                replyTo = Chunk(Email.Address("reply@example.com")),
                subject = "Hi",
                date = Present(EmailLiterals.instantOf("2024-01-02T03:04:05Z")),
                messageId = Present(EmailLiterals.messageIdOf("<m1@example.com>")),
                inReplyTo = Chunk(EmailLiterals.messageIdOf("<p@example.com>")),
                references = Chunk(EmailLiterals.messageIdOf("<r@example.com>"), EmailLiterals.messageIdOf("<p@example.com>")),
                text = "Hello",
                headers =
                    Chunk(Email.Header("X-Mailer", "kyo"), Email.Header("subject", "ignored"), Email.Header("Received", "from a by b"))
            )
            assert(wire(m) ==
                "X-Mailer: kyo\r\n" +
                "Received: from a by b\r\n" +
                "Date: Tue, 02 Jan 2024 03:04:05 +0000\r\n" +
                "From: Ada Lovelace <ada@example.com>\r\n" +
                "Sender: grace@example.com\r\n" +
                "Reply-To: reply@example.com\r\n" +
                "To: to@example.com\r\n" +
                "Cc: cc@example.com\r\n" +
                "Bcc: bcc@example.com\r\n" +
                "Subject: Hi\r\n" +
                "Message-ID: <m1@example.com>\r\n" +
                "In-Reply-To: <p@example.com>\r\n" +
                "References: <r@example.com> <p@example.com>\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: 7bit\r\n" +
                "\r\n" +
                "Hello")
        }
        "every name a field renders is skipped from headers, in any ASCII case" in {
            val names = Seq(
                "From",
                "Sender",
                "To",
                "Cc",
                "Bcc",
                "Reply-To",
                "Subject",
                "Date",
                "Message-ID",
                "In-Reply-To",
                "References",
                "MIME-Version",
                "Content-Type",
                "Content-Transfer-Encoding",
                "Content-Disposition",
                "Content-ID"
            )
            val m = Email.Message(text = "x", headers = Chunk.from(names.map(n => Email.Header(n.toUpperCase, "injected"))))
            assert(!wire(m).contains("injected"))
        }
        "white space at either end of an extra value is not written; inside it is kept" in {
            assert(wire(Email.Message(headers = Chunk(Email.Header("X-A", " \t a  b\t ")))).startsWith("X-A: a  b\r\n"))
            assert(wire(Email.Message(headers = Chunk(Email.Header("X-A", "   ")))).startsWith("X-A:\r\n"))
        }
        "a non-ASCII extra value and a non-ASCII address are written as UTF-8; display names and subjects as encoded words" in {
            val m = Email.Message(
                to = Chunk(Email.Address("j\u00f6rg@b\u00fccher.example", Present("J\u00f6rg"))),
                subject = "Gr\u00fc\u00dfe",
                headers = Chunk(Email.Header("X-Greeting", "Gr\u00fc\u00dfe"))
            )
            val text = new String(octets(m).toArray, "UTF-8")
            assert(text.contains("X-Greeting: Gr\u00fc\u00dfe\r\n"))
            assert(text.contains("To: =?utf-8?B?SsO2cmc=?= <j\u00f6rg@b\u00fccher.example>\r\n"))
            assert(text.contains("Subject: =?utf-8?"))
        }
        "a name that is not printable ASCII without a colon is refused" in {
            Seq("Bad Name", "", "X:Y", "X-\u00e9", "X-\u007f", "X-\u0001").foreach { name =>
                assert(
                    failure(Email.Message(headers = Chunk(Email.Header(name, "v")))) == Result.fail(MimeRenderer.Failure.InvalidName(name)),
                    name
                )
            }
        }
        "CR or LF in an extra value is a line break; any other control character, C1 included, is refused; a tab is kept" in {
            Seq("a\rb", "a\nb", "a\r\nBcc: victim@example.com").foreach { value =>
                assert(
                    failure(Email.Message(headers = Chunk(Email.Header("X-A", value)))) ==
                        Result.fail(MimeRenderer.Failure.Header("X-A", HeaderCodec.WriteFailure.LineBreakInValue)),
                    value
                )
            }
            Seq("a\u0000b", "a\u001bb", "a\u007fb", "a\u0085b", "a\u009fb").foreach { value =>
                assert(
                    failure(Email.Message(headers = Chunk(Email.Header("X-A", value)))) ==
                        Result.fail(MimeRenderer.Failure.ControlCharacter("X-A")),
                    value
                )
            }
            assert(wire(Email.Message(headers = Chunk(Email.Header("X-A", "a\tb")))).startsWith("X-A: a\tb\r\n"))
        }
        "a value or an address with no white space for more than 998 octets is too long" in {
            assert(failure(Email.Message(headers = Chunk(Email.Header("X-A", "x" * 999)))) ==
                Result.fail(MimeRenderer.Failure.Header("X-A", HeaderCodec.WriteFailure.LineTooLong)))
            assert(failure(Email.Message(to = Chunk(Email.Address("x" * 1000 + "@example.com")))) ==
                Result.fail(MimeRenderer.Failure.Header("To", HeaderCodec.WriteFailure.LineTooLong)))
            assert(wire(Email.Message(headers = Chunk(Email.Header("X-A", "x" * 990)))).startsWith("X-A:\r\n " + "x" * 990 + "\r\n"))
        }
        "an address that is not an addr-spec is refused, naming its header" in {
            assert(failure(Email.Message(cc = Chunk(Email.Address("a b@c")))) ==
                Result.fail(MimeRenderer.Failure.Header("Cc", HeaderCodec.WriteFailure.InvalidAddress("a b@c"))))
            assert(failure(Email.Message(sender = Present(Email.Address("nobody")))) ==
                Result.fail(MimeRenderer.Failure.Header("Sender", HeaderCodec.WriteFailure.InvalidAddress("nobody"))))
        }
        "a date before year 0000 is refused, and a fraction of a second is truncated" in {
            val early = EmailLiterals.instantOf("-0001-12-31T23:59:59Z")
            assert(failure(Email.Message(date = Present(early))) ==
                Result.fail(MimeRenderer.Failure.Header("Date", HeaderCodec.WriteFailure.DateOutOfRange)))
            val fraction = EmailLiterals.instantOf("2024-01-02T03:04:05.999Z")
            assert(wire(Email.Message(date = Present(fraction))).startsWith("Date: Tue, 02 Jan 2024 03:04:05 +0000\r\n"))
        }
        "a media type parameter named with *, even with a token value, is refused, naming the part's Content-Type" in {
            val odd = Email.Attachment(EmailLiterals.mediaTypeOf("application", "x", "a*b" -> "1"), Absent, bytes("x"))
            assert(failure(Email.Message(attachments = Chunk(odd))) ==
                Result.fail(MimeRenderer.Failure.Header("Content-Type", HeaderCodec.WriteFailure.UnwritableParameterName("a*b"))))
        }
    }

    "part headers" - {
        "a text part names utf-8; an attachment its type, disposition and file name; an inline part also its Content-ID" in {
            val text = wire(Email.Message(text = "Hi", html = Present("<img>"), inlineParts = Chunk(logo), attachments = Chunk(report)))
            assert(text.contains("\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 7bit\r\n\r\nHi\r\n--"))
            assert(text.contains("\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: 7bit\r\n\r\n<img>\r\n--"))
            assert(text.contains(
                "\r\nContent-Type: image/png\r\nContent-Transfer-Encoding: base64\r\nContent-Disposition: inline\r\nContent-ID: <logo@example.com>\r\n\r\n"
            ))
            assert(text.contains(
                "\r\nContent-Type: application/pdf\r\nContent-Transfer-Encoding: 7bit\r\nContent-Disposition: attachment; filename=q3.pdf\r\n\r\n%PDF\r\n--"
            ))
        }
        "an attachment with no file name has a disposition without one, so a name parameter of its type stays only there" in {
            val named = Email.Attachment(EmailLiterals.mediaTypeOf("text", "plain", "name" -> "a.txt"), Absent, bytes("x"))
            assert(wire(Email.Message(attachments = Chunk(named))).contains(
                "Content-Type: text/plain; name=a.txt\r\nContent-Transfer-Encoding: 7bit\r\nContent-Disposition: attachment\r\n\r\n"
            ))
            assert(parsed(Email.Message(attachments = Chunk(named))).attachments == Chunk(named))
        }
        "a multipart is marked 8bit only when a part it encloses is" in {
            val forwarded = attachment("message", "rfc822", "Subject: caf\u00e9\r\n\r\nx")
            assert(wire(Email.Message(text = "Hi", attachments = Chunk(forwarded))).contains(
                "Content-Type: multipart/mixed; boundary=\"=_kyo0_0\"\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"
            ))
            assert(wire(Email.Message(text = "Hi", attachments = Chunk(report))).contains(
                "Content-Type: multipart/mixed; boundary=\"=_kyo0_0\"\r\n\r\n"
            ))
        }
    }

    "leaf encodings" - {
        "text is 7bit when it is short-lined printable ASCII, with each LF written CRLF" in {
            assert(encodings(Email.Message(text = "Hello\nworld\n")) == Chunk(sevenBit))
            assert(wire(Email.Message(text = "Hello\nworld\n")).endsWith("\r\n\r\nHello\r\nworld\r\n"))
            assert(encodings(Email.Message(text = "x" * 76)) == Chunk(sevenBit))
        }
        "text is quoted-printable for a long line, trailing white space, a From or . line, =_ or mostly-ASCII text" in {
            Seq(
                "x" * 77,
                "trailing \nx",
                "tab\t\nx",
                "From me\n",
                "a\n.\nb",
                "=_",
                "Gr\u00fc\u00dfe aus K\u00f6ln, sagt J\u00fcrgen, und bis bald im Sommer"
            ).foreach { text =>
                assert(encodings(Email.Message(text = text)) == Chunk(quotedPrintable), text)
                assert(parsed(Email.Message(text = text)).text == text, text)
            }
        }
        "text is base64 when that is shorter than quoted-printable" in {
            val text = "\u6f22\u5b57\u306e\u6587\u7ae0\u3067\u3059"
            assert(encodings(Email.Message(text = text)) == Chunk(base64))
            assert(parsed(Email.Message(text = text)).text == text)
        }
        "a CR already in the text is kept, as quoted-printable =0D when that is not longer than base64" in {
            Seq("line one\rline two", "line one\r\nline two").foreach { text =>
                assert(encodings(Email.Message(text = text)) == Chunk(quotedPrintable), text)
                assert(wire(Email.Message(text = text)).contains("=0D"), text)
                assert(parsed(Email.Message(text = text)).text == text, text)
            }
        }
        "an attachment or inline part is 7bit when clean and base64 otherwise, never quoted-printable" in {
            assert(encodings(Email.Message(attachments = Chunk(attachment("text", "csv", "a,b\r\n1,2\r\n")))) == Chunk(sevenBit))
            Seq("caf\u00e9", "x" * 77, "a\nb", "From here", "=_", "end \r\n").foreach { content =>
                val m = Email.Message(attachments = Chunk(attachment("text", "csv", content)))
                assert(encodings(m) == Chunk(base64), content)
                assert(parsed(m).attachments.map(a => new String(a.content.toArray, "UTF-8")) == Chunk(content), content)
            }
            assert(encodings(Email.Message(inlineParts = Chunk(logo))) == Chunk(sevenBit, base64))
        }
        "a message or multipart leaf is 7bit when 7-bit clean with lines up to 998, whatever its From, . and =_ lines" in {
            val content = "From: a@example.com\r\nSubject: x=_y\r\n\r\nFrom the top\r\n.\r\n" + "x" * 998
            val m       = Email.Message(attachments = Chunk(attachment("message", "rfc822", content)))
            assert(encodings(m) == Chunk(sevenBit))
            assert(parsed(m).attachments.map(a => new String(a.content.toArray, "UTF-8")) == Chunk(content))
        }
        "it is 8bit with high octets and no NUL, bare line end or line past 998, and base64 otherwise" in {
            assert(encodings(Email.Message(attachments = Chunk(attachment("message", "rfc822", "Subject: caf\u00e9\r\n\r\nx")))) ==
                Chunk(eightBit))
            Seq("a\u0000b", "a\nb", "a\rb", "x" * 999).foreach { content =>
                val m = Email.Message(attachments = Chunk(attachment("message", "rfc822", content)))
                assert(encodings(m) == Chunk(base64), content.take(10))
                assert(parsed(m).attachments.map(a => new String(a.content.toArray, "UTF-8")) == Chunk(content), content.take(10))
            }
        }
        "a multipart leaf holding a delimiter line of its own boundary is base64, so it reads back as the leaf" in {
            val tree = attachment("multipart", "mixed", "--b\r\nContent-Type: text/plain\r\n\r\nx\r\n--b--", "boundary" -> "b")
            assert(encodings(Email.Message(attachments = Chunk(tree))) == Chunk(base64))
            assert(parsed(Email.Message(attachments = Chunk(tree))).attachments == Chunk(tree))
            val flat = attachment("multipart", "mixed", "no delimiter here\r\n", "boundary" -> "b")
            assert(encodings(Email.Message(attachments = Chunk(flat))) == Chunk(sevenBit))
            val unbounded = attachment("multipart", "mixed", "--b\r\n")
            assert(encodings(Email.Message(attachments = Chunk(unbounded))) == Chunk(sevenBit))
        }
    }

    "boundaries" - {
        "each multipart's boundary is =_kyo<depth>_0 when nothing it encloses conflicts" in {
            val text = wire(Email.Message(text = "Hi", html = Present("<img>"), inlineParts = Chunk(logo), attachments = Chunk(report)))
            assert(text.contains("multipart/mixed; boundary=\"=_kyo0_0\""))
            assert(text.contains("multipart/alternative; boundary=\"=_kyo1_0\""))
            assert(text.contains("multipart/related; boundary=\"=_kyo2_0\""))
        }
        "the whole form: no preamble, the line end before a delimiter belongs to it, no epilogue, and the last line ends with CRLF" in {
            assert(wire(Email.Message(text = "Hi", attachments = Chunk(report))) ==
                "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/mixed; boundary=\"=_kyo0_0\"\r\n" +
                "\r\n" +
                "--=_kyo0_0\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: 7bit\r\n" +
                "\r\n" +
                "Hi\r\n" +
                "--=_kyo0_0\r\n" +
                "Content-Type: application/pdf\r\n" +
                "Content-Transfer-Encoding: 7bit\r\n" +
                "Content-Disposition: attachment; filename=q3.pdf\r\n" +
                "\r\n" +
                "%PDF\r\n" +
                "--=_kyo0_0--\r\n")
        }
        "content built from the boundaries the construction would choose cannot defeat it" in {
            val lines     = "--=_kyo0_0\n--=_kyo0_0--\n--=_kyo1_0\nrunning =_kyo0_0 text\n"
            val forwarded = attachment("message", "rfc822", (0 to 10).map(k => s"--=_kyo0_$k").mkString("", "\r\n", "\r\n"))
            val m         = Email.Message(text = lines, html = Present(lines), attachments = Chunk(forwarded))
            val text      = wire(m)
            assert(text.contains("multipart/mixed; boundary=\"=_kyo0_11\""))
            assert(text.contains("multipart/alternative; boundary=\"=_kyo1_0\""))
            val written = text.split("\r\n", -1).toSeq
            assert(written.count(_ == "--=_kyo0_11") == 2 && written.count(_ == "--=_kyo0_11--") == 1)
            assert(written.count(_ == "--=_kyo1_0") == 2 && written.count(_ == "--=_kyo1_0--") == 1)
            assert(parsed(m) == m.copy(headers = parsed(m).headers))
        }
        "a line takes every number its digits start with, since the parser ignores what follows a boundary on its line" in {
            val forwarded = attachment("message", "rfc822", "--=_kyo0_0\r\n--=_kyo0_12\r\n")
            val m         = Email.Message(text = "Hi", attachments = Chunk(forwarded))
            assert(wire(m).contains("multipart/mixed; boundary=\"=_kyo0_2\""))
            assert(parsed(m).attachments == Chunk(forwarded))
        }
        "an enclosed leaf declaring a boundary excludes the numbers its delimiter line would take" in {
            val same   = attachment("multipart", "mixed", "x\r\n", "boundary" -> "=_kyo0_0")
            val longer = attachment("multipart", "mixed", "y\r\n", "boundary" -> "=_kyo0_1--")
            val m      = Email.Message(text = "Hi", attachments = Chunk(same, longer))
            assert(wire(m).contains("multipart/mixed; boundary=\"=_kyo0_2\""))
            assert(parsed(m).attachments == Chunk(same, longer))
        }
    }

end MimeRendererTest
