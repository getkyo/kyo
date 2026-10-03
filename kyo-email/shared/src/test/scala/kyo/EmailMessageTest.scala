package kyo

class EmailMessageTest extends kyo.test.Test[Any]:

    private val ada   = Email.Address("ada@example.com", Present("Ada Lovelace"))
    private val grace = Email.Address("grace@example.com")

    private def report = Email.Message(
        from = Chunk(ada),
        to = Chunk(grace),
        subject = "Quarterly report",
        messageId = Present(EmailLiterals.messageIdOf("<report-1@example.com>")),
        references = Chunk(EmailLiterals.messageIdOf("<root@example.com>")),
        text = "See attached.",
        html = Present("<p>See attached.</p>"),
        attachments =
            Chunk(Email.Attachment(EmailLiterals.mediaTypeOf("application", "pdf"), Present("q3.pdf"), Span.from("%PDF".getBytes("UTF-8"))))
    )

    "an empty message has no addresses, bodies or parts" in {
        val empty = Email.Message()
        assert(empty.sender == Absent)
        assert(empty.from.isEmpty && empty.to.isEmpty && empty.cc.isEmpty && empty.bcc.isEmpty && empty.replyTo.isEmpty)
        assert(empty.subject == "" && empty.text == "" && empty.html == Absent)
        assert(empty.messageId == Absent && empty.date == Absent)
        assert(empty.attachments.isEmpty && empty.inlineParts.isEmpty && empty.headers.isEmpty)
    }

    "two messages built from the same fields are equal, attachments included" in {
        assert(report == report)
    }

    "messages that differ in one field are not equal" in {
        assert(report != report.copy(subject = "Quarterly report (revised)"))
        assert(report != report.copy(references = Chunk.empty))
        assert(report != report.copy(sender = Present(grace)))
    }

    private def bytes(text: String): Span[Byte] = Span.from(text.getBytes("ISO-8859-1"))

    private def parsed(input: Span[Byte]): Email.Message = Abort.run[EmailParseFailure](Email.Message.parse(input)).eval.getOrThrow

    private def parsed(text: String): Email.Message = parsed(bytes(text))

    private def crlf(lines: String*): String = lines.mkString("", "\r\n", "\r\n")

    // An entity: its header lines, the empty line, and its body.
    private def part(headers: String*)(body: String): String = crlf(headers*) + "\r\n" + body

    // A multipart entity whose parts are the given entities, with its own boundary so multiparts nest.
    private def multipart(subtype: String, boundary: String, parameters: String = "")(parts: String*): String =
        part(s"Content-Type: multipart/$subtype; boundary=$boundary$parameters")(
            parts.map(p => s"--$boundary\r\n$p\r\n").mkString + s"--$boundary--\r\n"
        )

    private def octets(text: String): Span[Byte] = Span.from(text.getBytes("ISO-8859-1"))

    private val plain = part("Content-Type: text/plain; charset=us-ascii")("plain body")
    private val html  = part("Content-Type: text/html; charset=us-ascii")("<p>html body</p>")
    private val png   = part("Content-Type: image/png", "Content-ID: <logo@x>", "Content-Transfer-Encoding: base64")("iVBO")

    private def fileNames(message: Email.Message): Chunk[Maybe[String]] = message.attachments.map(_.fileName)

    private def types(message: Email.Message): Chunk[String] = message.attachments.map(_.contentType.baseType)

    "parse" - {
        "fields" - {
            "RFC 5322 A.1.2: addresses, subject, date and message id" in {
                val m = parsed(EmailRfc5322Examples.mailboxes)
                assert(m.from == Chunk(Email.Address("john.q.public@example.com", Present("Joe Q. Public"))))
                assert(m.to.map(_.address) == Chunk("mary@x.test", "jdoe@example.org", "one@y.test"))
                assert(m.cc.map(_.address) == Chunk("boss@nil.test", "sysservices@example.net"))
                assert(m.subject == "")
                assert(m.messageId == Present(EmailLiterals.messageIdOf("5678.21-Nov-1997@example.com")))
                assert(m.date.map(kyo.internal.email.mime.DateCodec.epochSecondOf) == Present(1057049557L))
            }
            "RFC 5322 A.2: In-Reply-To, References and Reply-To" in {
                val m = parsed(EmailRfc5322Examples.replyToReply)
                assert(m.inReplyTo == Chunk(EmailLiterals.messageIdOf("3456@example.net")))
                assert(m.references ==
                    Chunk(EmailLiterals.messageIdOf("1234@local.machine.example"), EmailLiterals.messageIdOf("3456@example.net")))
                val reply = parsed(EmailRfc5322Examples.reply)
                assert(reply.replyTo == Chunk(Email.Address("smith@home.example", Present("Mary Smith: Personal Account"))))
            }
            "duplicate To and Cc headers concatenate in order; duplicate From, Sender and Subject take the first" in {
                val m = parsed(crlf(
                    "From: a@x",
                    "From: b@x",
                    "Sender: s@x, t@x",
                    "Sender: u@x",
                    "To: t1@x",
                    "Cc: c1@x",
                    "To: t2@x, t3@x",
                    "cc: c2@x",
                    "Subject: first",
                    "Subject: second",
                    "",
                    "body"
                ))
                assert(m.from.map(_.address) == Chunk("a@x"))
                assert(m.sender.map(_.address) == Present("s@x"))
                assert(m.to.map(_.address) == Chunk("t1@x", "t2@x", "t3@x"))
                assert(m.cc.map(_.address) == Chunk("c1@x", "c2@x"))
                assert(m.subject == "first")
            }
            "headers keeps every field in order, named as written and undecoded; subject is decoded" in {
                val m = parsed(crlf("Subject: =?utf-8?q?caf=C3=A9?=", "x-custom:  folded", " value", "Subject: second", "", "b"))
                assert(m.headers == Chunk(
                    Email.Header("Subject", "=?utf-8?q?caf=C3=A9?="),
                    Email.Header("x-custom", "folded value"),
                    Email.Header("Subject", "second")
                ))
                assert(m.subject == "café")
            }
            "an 8-bit subject that is not UTF-8 reads as windows-1252" in {
                assert(parsed("Subject: café \u0080\r\n\r\nb").subject == "café €")
            }
            "an unreadable date is absent and stays in headers; an unreadable message id is absent" in {
                val m = parsed(crlf("Date: not a date", "Message-ID: <>", "", "b"))
                assert(m.date == Absent && m.messageId == Absent)
                assert(m.headers.map(_.name) == Chunk("Date", "Message-ID"))
            }
            "the empty input is the empty message" in {
                assert(parsed("") == Email.Message())
            }
        }

        "bodies" - {
            "a text/plain message is the text, with CRLF made LF and a bare CR kept" in {
                val m = parsed(part("Content-Type: text/plain")("one\r\ntwo\rthree\r\n"))
                assert(m.text == "one\ntwo\rthree\n" && m.html == Absent && m.attachments.isEmpty)
            }
            "a message with no Content-Type is text" in {
                assert(parsed(crlf("Subject: s", "", "hello")).text == "hello\n")
            }
            "a text/html message is the html, and text is empty" in {
                val m = parsed(html)
                assert(m.html == Present("<p>html body</p>") && m.text == "")
            }
            "alternative gives both bodies" in {
                val m = parsed(multipart("alternative", "a")(plain, html))
                assert(m.text == "plain body" && m.html == Present("<p>html body</p>") && m.attachments.isEmpty)
            }
            "alternative takes the last text and the last html; the earlier ones and other alternatives are attachments" in {
                val older    = part("Content-Type: text/plain")("older")
                val calendar = part("Content-Type: text/calendar; method=REQUEST")("BEGIN:VCALENDAR")
                val m        = parsed(multipart("alternative", "a")(older, calendar, plain, html))
                assert(m.text == "plain body")
                assert(types(m) == Chunk("text/plain", "text/calendar"))
                assert(m.attachments(1).contentType.parameter("method") == Present("REQUEST"))
            }
            "alternative whose last child is related gives the html and its inline part" in {
                val m = parsed(multipart("alternative", "a")(plain, multipart("related", "r")(html, png)))
                assert(m.text == "plain body" && m.html == Present("<p>html body</p>"))
                assert(m.inlineParts.map(_.contentId) == Chunk(EmailLiterals.contentIdOf("logo@x")) && m.attachments.isEmpty)
            }
            "mixed: the first child gives the bodies; a later text part is an attachment" in {
                val m = parsed(multipart("mixed", "m")(plain, part("Content-Type: text/plain")("second")))
                assert(m.text == "plain body")
                assert(m.attachments.map(a => new String(a.content.toArray, "US-ASCII")) == Chunk("second"))
            }
            "a text part that is explicitly an attachment is not a body, even first; so is an unknown disposition type" in {
                val attached = part("Content-Type: text/plain", "Content-Disposition: attachment; filename=a.txt")("x")
                val unknown  = part("Content-Type: text/plain", "Content-Disposition: x-unheard-of")("y")
                val m        = parsed(multipart("mixed", "m")(attached, unknown))
                assert(m.text == "" && fileNames(m) == Chunk(Present("a.txt"), Absent))
            }
            "an inline disposition on a text part leaves it a body" in {
                assert(parsed(part("Content-Type: text/plain", "Content-Disposition: inline")("x")).text == "x")
            }
            "an unknown multipart subtype is mixed" in {
                val m = parsed(multipart("x-unheard-of", "u")(plain, html))
                assert(m.text == "plain body" && m.html == Absent && types(m) == Chunk("text/html"))
            }
            "encrypted: every child is an attachment and there is no body" in {
                val control = part("Content-Type: application/pgp-encrypted")("Version: 1")
                val data    = part("Content-Type: application/octet-stream")("-----BEGIN PGP MESSAGE-----")
                val m       = parsed(multipart("encrypted", "e", "; protocol=\"application/pgp-encrypted\"")(control, data))
                assert(m.text == "" && types(m) == Chunk("application/pgp-encrypted", "application/octet-stream"))
            }
            "a text part whose charset has no decoder is an attachment keeping its declared type" in {
                val m = parsed(part("Content-Type: text/plain; charset=x-unheard-of")("x"))
                assert(m.text == "")
                assert(m.attachments.map(_.contentType.parameter("charset")) == Chunk(Present("x-unheard-of")))
                assert(types(m) == Chunk("text/plain"))
            }
            "a declared charset decodes the text; with none, UTF-8 when well-formed and windows-1252 otherwise" in {
                assert(parsed(part("Content-Type: text/plain; charset=iso-8859-1")("café")).text == "café")
                assert(parsed(part("Content-Type: text/plain")("cafÃ©")).text == "café")
                assert(parsed(part("Content-Type: text/plain")("café \u0080")).text == "café €")
            }
            "the default charset of a part with no Content-Type is not a declared one: UTF-8 octets read as UTF-8" in {
                assert(parsed(crlf("Subject: s", "") + "cafÃ©").text == "café")
            }
            "transfer encodings are removed; a truncated base64 segment loses its dangling character" in {
                assert(parsed(part("Content-Transfer-Encoding: base64")("aGVsbG8=")).text == "hello")
                assert(parsed(part("Content-Transfer-Encoding: quoted-printable")("caf=C3=A9=\r\n!")).text == "café!")
                assert(parsed(part("Content-Transfer-Encoding: base64")("aGVsbG8gd")).text == "hello ")
            }
        }

        "related" - {
            "the root is the child named by start; other children with a Content-ID are inline whatever their disposition" in {
                val attachedLogo = part(
                    "Content-Type: image/png",
                    "Content-ID: <logo2@x>",
                    "Content-Disposition: attachment; filename=l.png"
                )("x")
                val m = parsed(multipart("related", "r", "; start=\"<root@x>\"")(
                    png,
                    part("Content-Type: text/html", "Content-ID: <root@x>")("<p>root</p>"),
                    attachedLogo
                ))
                assert(m.html == Present("<p>root</p>"))
                assert(m.inlineParts.map(_.contentId.value) == Chunk("logo@x", "logo2@x"))
                assert(m.inlineParts(1).fileName == Present("l.png"))
            }
            "a start that names no part falls back to the first child; a child without a Content-ID is an attachment" in {
                val m = parsed(multipart("related", "r", "; start=\"<nothing@x>\"")(html, part("Content-Type: image/gif")("GIF8")))
                assert(m.html == Present("<p>html body</p>") && types(m) == Chunk("image/gif") && m.inlineParts.isEmpty)
            }
        }

        "inline parts and attachments" - {
            "a Content-ID alone does not make an inline part outside related" in {
                val m = parsed(multipart("mixed", "m")(plain, png))
                assert(m.inlineParts.isEmpty && types(m) == Chunk("image/png"))
            }
            "an inline disposition with a valid Content-ID is an inline part; without one, or with an invalid one, an attachment" in {
                val inline    = part("Content-Type: image/png", "Content-ID: <a@x>", "Content-Disposition: inline")("1")
                val noId      = part("Content-Type: image/png", "Content-Disposition: inline")("2")
                val invalidId = part("Content-Type: image/png", "Content-ID: <>", "Content-Disposition: inline")("3")
                val m         = parsed(multipart("mixed", "m")(plain, inline, noId, invalidId))
                assert(m.inlineParts.map(_.contentId.value) == Chunk("a@x"))
                assert(m.attachments.map(a => new String(a.content.toArray, "US-ASCII")) == Chunk("2", "3"))
            }
            "fileName comes from Content-Disposition, and from Content-Type's name only when there is no Content-Disposition" in {
                val both        = part("Content-Type: image/png; name=ct.png", "Content-Disposition: attachment; filename=cd.png")("1")
                val nameOnly    = part("Content-Type: image/png; name=ct.png")("2")
                val noFilename  = part("Content-Type: image/png; name=ct.png", "Content-Disposition: attachment")("3")
                val encodedName = part("Content-Type: image/png", "Content-Disposition: attachment; filename*=utf-8''caf%C3%A9.png")("4")
                val m           = parsed(multipart("mixed", "m")(plain, both, nameOnly, noFilename, encodedName))
                assert(fileNames(m) == Chunk(Present("cd.png"), Present("ct.png"), Absent, Present("café.png")))
            }
            "attachment content is transfer-decoded; an unknown transfer encoding keeps the octets as application/octet-stream" in {
                val base64  = part("Content-Type: application/pdf", "Content-Transfer-Encoding: base64")("JVBERg==")
                val unknown = part("Content-Type: application/pdf; name=a.pdf", "Content-Transfer-Encoding: x-uuencode")("begin 644 a")
                val m       = parsed(multipart("mixed", "m")(plain, base64, unknown))
                assert(m.attachments(0) == Email.Attachment(EmailLiterals.mediaTypeOf("application", "pdf"), Absent, octets("%PDF")))
                assert(m.attachments(1) ==
                    Email.Attachment(EmailLiterals.mediaTypeOf("application", "octet-stream"), Present("a.pdf"), octets("begin 644 a")))
            }
            "message/rfc822 is an attachment of that type holding the decoded message; a digest gives one per entry" in {
                val enclosed = crlf("Subject: inner", "", "inner body")
                val m        = parsed(multipart("mixed", "m")(plain, part("Content-Type: message/rfc822")(enclosed)))
                assert(m.attachments == Chunk(Email.Attachment(EmailLiterals.mediaTypeOf("message", "rfc822"), Absent, octets(enclosed))))
                val digest = parsed(multipart("digest", "d")(part()("Subject: one\r\n\r\n1"), part()("Subject: two\r\n\r\n2")))
                assert(types(digest) == Chunk("message/rfc822", "message/rfc822") && digest.text == "")
            }
            "a multipart with no boundary is an attachment of its declared type holding its body" in {
                val m = parsed(part("Content-Type: multipart/mixed")("--x\r\nbody"))
                assert(m.attachments ==
                    Chunk(Email.Attachment(EmailLiterals.mediaTypeOf("multipart", "mixed"), Absent, octets("--x\r\nbody"))))
            }
            "signed: the first child is the body, the signature an attachment; report: likewise" in {
                val signature =
                    part("Content-Type: application/pgp-signature", "Content-Disposition: attachment; filename=signature.asc")("SIG")
                val signed = parsed(multipart("signed", "s", "; protocol=\"application/pgp-signature\"")(plain, signature))
                assert(signed.text == "plain body" && fileNames(signed) == Chunk(Present("signature.asc")))
                val status = part("Content-Type: message/delivery-status")("Action: failed")
                val report = parsed(multipart("report", "p", "; report-type=delivery-status")(plain, status))
                assert(report.text == "plain body" && types(report) == Chunk("message/delivery-status"))
            }
        }

        "failure" - {
            "multiparts nested 101 deep fail with EmailMimeException" in {
                val open  = (1 to 101).map(i => crlf(s"Content-Type: multipart/mixed; boundary=b$i", "", s"--b$i")).mkString
                val input = bytes(open + crlf("", "x"))
                Abort.run[EmailParseFailure](Email.Message.parse(input)).eval match
                    case Result.Failure(ex: EmailMimeException) => assert(ex.problem == EmailMimeException.Problem.NestingTooDeep(100))
                    case other                                  => fail(other.toString)
            }
        }

        "corpora" - {
            import kyo.internal.email.vectors.*
            "every CPython message parses" in {
                val files = EmbeddedCpythonSet.files.filter((name, _) => name.startsWith("msg_"))
                assert(files.size == 45)
                files.foreach((name, content) => assert(Abort.run[EmailParseFailure](Email.Message.parse(content())).eval.isSuccess, name))
            }
            "every Stalwart RFC message parses" in {
                val files = EmbeddedStalwartSet.files.filter((name, _) => name.startsWith("rfc-"))
                assert(files.size == 10)
                files.foreach((name, content) => assert(Abort.run[EmailParseFailure](Email.Message.parse(content())).eval.isSuccess, name))
            }
            "CPython msg_07: the text and the gif attachment with its file name" in {
                val m = parsed(EmbeddedCpythonSet.files("msg_07.txt")())
                assert(m.text == "Hi there,\n\nThis is the dingus fish.\n")
                assert(fileNames(m) == Chunk(Present("dingusfish.gif")) && types(m) == Chunk("image/gif"))
                assert(m.from == Chunk(Email.Address("barry@digicool.com", Present("Barry"))))
            }
            "CPython msg_20: three Cc fields spelled three ways concatenate" in {
                assert(parsed(EmbeddedCpythonSet.files("msg_20.txt")()).cc.map(_.address) ==
                    Chunk("ccc@zzz.org", "ddd@zzz.org", "eee@zzz.org"))
            }
            "CPython msg_29: an RFC 2231 title on the text part, which is the body; msg_32: an RFC 2231 charset" in {
                assert(parsed(EmbeddedCpythonSet.files("msg_29.txt")()).text == "\nHi,\n\nDo you like this message?\n\n-Me\n")
                assert(parsed(EmbeddedCpythonSet.files("msg_32.txt")()).text == "Some message.\n")
            }
            "Stalwart rfc-005 is RFC 2046's simple example: the first part is the text, the second an attachment" in {
                val m = parsed(EmbeddedStalwartSet.files("rfc-005.eml")())
                assert(m.text == "This is implicitly typed plain US-ASCII text.\nIt does NOT end with a linebreak.")
                assert(types(m) == Chunk("text/plain"))
            }
            "Stalwart rfc-007, RFC 8621's test: A is the text and everything else a non-body part" in {
                val m = parsed(EmbeddedStalwartSet.files("rfc-007.eml")())
                assert(m.text == "A" && m.html == Absent)
                assert(m.attachments.size + m.inlineParts.size == 9)
            }
        }
    }

    private def rendered(m: Email.Message): Result[EmailRenderFailure, Span[Byte]] =
        Abort.run[EmailRenderFailure](Email.Message.render(m)).eval

    private def renderFailure(m: Email.Message): EmailRenderFailure =
        rendered(m) match
            case Result.Failure(failure) => failure
            case other                   => throw new IllegalStateException(s"expected a failure: $other")

    private def headerProblem(m: Email.Message): (String, EmailInvalidHeaderException.Problem) =
        renderFailure(m) match
            case ex: EmailInvalidHeaderException => (ex.name, ex.problem)
            case other                           => throw new IllegalStateException(s"expected EmailInvalidHeaderException: $other")

    private val fieldNames = Set(
        "from",
        "sender",
        "to",
        "cc",
        "bcc",
        "reply-to",
        "subject",
        "date",
        "message-id",
        "in-reply-to",
        "references",
        "mime-version",
        "content-type",
        "content-transfer-encoding",
        "content-disposition",
        "content-id"
    )

    private def ordered(m: Email.Message): Chunk[String] =
        Chunk.from(Seq(
            m.date.nonEmpty       -> "Date",
            m.from.nonEmpty       -> "From",
            m.sender.nonEmpty     -> "Sender",
            m.replyTo.nonEmpty    -> "Reply-To",
            m.to.nonEmpty         -> "To",
            m.cc.nonEmpty         -> "Cc",
            m.bcc.nonEmpty        -> "Bcc",
            m.subject.nonEmpty    -> "Subject",
            m.messageId.nonEmpty  -> "Message-ID",
            m.inReplyTo.nonEmpty  -> "In-Reply-To",
            m.references.nonEmpty -> "References"
        ).collect { case (true, name) => name }) ++ Chunk("MIME-Version", "Content-Type")

    private def law(m: Email.Message, label: String)(using kyo.test.AssertScope): Unit =
        val first  = rendered(m).getOrThrow
        val back   = parsed(first)
        val differ = back.productElementNames.zip(back.productIterator.zip(m.productIterator)).collect {
            case (name, (read, model)) if name != "headers" && !read.equals(model) => s"$name: read [$read] model [$model]"
        }
        assert(differ.isEmpty, s"$label\n${differ.mkString("\n")}")
        assert(back == m.copy(headers = back.headers), label)
        assert(back.headers.take(m.headers.size) == m.headers, label)
        val written = back.headers.drop(m.headers.size).map(_.name)
        assert(written.take(ordered(m).size) == ordered(m), label)
        assert(written.drop(ordered(m).size).forall(_ == "Content-Transfer-Encoding") && written.size <= ordered(m).size + 1, label)
        assert(rendered(back).getOrThrow.toArray.sameElements(first.toArray), label)
    end law

    private def isControl(c: Char): Boolean = (c < ' ' && c != '\t') || (c >= '\u007f' && c <= '\u009f')

    "render" - {
        "the report reads back as itself, its headers the ones render wrote" in {
            law(report, "report")
        }
        "pairs" - {
            "an address without @, and one with a line break, fail with EmailInvalidAddressException" in {
                Seq("nobody", "a\r\nBcc: victim@example.com@example.com").foreach { address =>
                    renderFailure(Email.Message(to = Chunk(Email.Address(address)))) match
                        case ex: EmailInvalidAddressException => assert(ex.address == address)
                        case other                            => fail(other.toString)
                }
            }
            "an extra header named Bad Name:, and one whose value holds CRLF, fail with EmailInvalidHeaderException" in {
                assert(headerProblem(Email.Message(headers = Chunk(Email.Header("Bad Name:", "v")))) ==
                    ("Bad Name:", EmailInvalidHeaderException.Problem.InvalidName))
                assert(headerProblem(Email.Message(headers = Chunk(Email.Header("X-A", "v\r\nBcc: victim@example.com")))) ==
                    ("X-A", EmailInvalidHeaderException.Problem.LineBreakInValue))
            }
            "a value with no white space for more than 998 octets is LineTooLong, naming its header" in {
                assert(headerProblem(Email.Message(headers = Chunk(Email.Header("X-A", "x" * 999)))) ==
                    ("X-A", EmailInvalidHeaderException.Problem.LineTooLong))
            }
            "a date before year 0000 is DateOutOfRange" in {
                val early = EmailLiterals.instantOf("-0001-06-01T00:00:00Z")
                assert(headerProblem(Email.Message(date = Present(early))) == ("Date", EmailInvalidHeaderException.Problem.DateOutOfRange))
            }
            "a parameter named with *, even with a token value, is UnwritableParameterName, naming Content-Type" in {
                val odd = Email.Attachment(EmailLiterals.mediaTypeOf("application", "x", "a*b" -> "1"), Absent, Span.empty[Byte])
                assert(headerProblem(Email.Message(attachments = Chunk(odd))) ==
                    ("Content-Type", EmailInvalidHeaderException.Problem.UnwritableParameterName("a*b")))
            }
            "a control character in an extra value is ControlCharacterInValue" in {
                assert(headerProblem(Email.Message(headers = Chunk(Email.Header("X-A", "a\u0000b")))) ==
                    ("X-A", EmailInvalidHeaderException.Problem.ControlCharacterInValue))
            }
            "a parsed message holding a header with a control character renders only once the header is gone" in {
                val m = parsed(crlf("X-Trace: a\u0001b", "Subject: hi", "") + "body")
                assert(headerProblem(m) == ("X-Trace", EmailInvalidHeaderException.Problem.ControlCharacterInValue))
                assert(rendered(m.copy(headers = m.headers.filterNot(_.name == "X-Trace"))).isSuccess)
            }
        }
        "the round-trip law over 1000 generated messages, each coverage item of the generator counted" in {
            import EmailMessageGenerator.*
            val seed = 20260927
            Random.withSeed(seed)(Kyo.fill(1000)(message)).map {
                messages =>
                    messages.zipWithIndex.foreach((m, i) => law(m, s"seed $seed, message $i: $m"))

                val names = (m: Email.Message) =>
                    Seq("from" -> m.from, "sender" -> m.sender.toChunk, "to" -> m.to, "cc" -> m.cc, "bcc" -> m.bcc, "replyTo" -> m.replyTo)
                        .map((field, list) => field -> list.flatMap(_.name.toChunk))
                val lines     = (text: String) => text.split("\r\n|\r|\n", -1).toSeq
                val bodies    = (m: Email.Message) => Seq(m.text) ++ m.html.toChunk
                val fileNames = (m: Email.Message) => m.attachments.flatMap(_.fileName.toChunk) ++ m.inlineParts.flatMap(_.fileName.toChunk)
                val contents  = (m: Email.Message) => m.attachments.map(_.content) ++ m.inlineParts.map(_.content)
                val kinds     = Seq("latin" -> latin, "CJK" -> cjk, "supplementary" -> supplementary, "combining" -> combining)
                val places    = (m: Email.Message) =>
                    Seq(
                        "subject"       -> Seq(m.subject),
                        "file names"    -> fileNames(m),
                        "text"          -> Seq(m.text),
                        "html"          -> m.html.toChunk,
                        "header values" -> m.headers.map(_.value)
                    ) ++ names(m)
                val items = Seq.newBuilder[(String, Email.Message => Boolean)]
                Seq[(String, Email.Message => Boolean)](
                    "from"        -> (_.from.nonEmpty),
                    "sender"      -> (_.sender.nonEmpty),
                    "to"          -> (_.to.nonEmpty),
                    "cc"          -> (_.cc.nonEmpty),
                    "bcc"         -> (_.bcc.nonEmpty),
                    "replyTo"     -> (_.replyTo.nonEmpty),
                    "subject"     -> (_.subject.nonEmpty),
                    "date"        -> (_.date.nonEmpty),
                    "messageId"   -> (_.messageId.nonEmpty),
                    "inReplyTo"   -> (_.inReplyTo.nonEmpty),
                    "references"  -> (_.references.nonEmpty),
                    "text"        -> (_.text.nonEmpty),
                    "html"        -> (_.html.nonEmpty),
                    "attachments" -> (_.attachments.nonEmpty),
                    "inlineParts" -> (_.inlineParts.nonEmpty),
                    "headers"     -> (_.headers.nonEmpty)
                ).foreach { (field, present) =>
                    items += s"$field present" -> present
                    items += s"$field absent"  -> (m => !present(m))
                }
                Seq[(String, Email.Message => Int)](
                    "from"       -> (_.from.size),
                    "to"         -> (_.to.size),
                    "cc"         -> (_.cc.size),
                    "bcc"        -> (_.bcc.size),
                    "replyTo"    -> (_.replyTo.size),
                    "inReplyTo"  -> (_.inReplyTo.size),
                    "references" -> (_.references.size)
                )
                    .foreach { (field, size) =>
                        items += s"$field with one"     -> (m => size(m) == 1)
                        items += s"$field with several" -> (m => size(m) > 1)
                    }
                for text <- Seq(true, false); html <- Seq(true, false); inline <- Seq(true, false); attached <- Seq(true, false) do
                    items += s"shape text=$text html=$html inline=$inline attachments=$attached" ->
                        (m =>
                            m.text.nonEmpty == text && m.html.nonEmpty == html && m.inlineParts.nonEmpty == inline &&
                                m.attachments.nonEmpty == attached
                        )
                end for
                items += "empty html" -> (_.html == Present(""))
                for (kind, samples) <- kinds; place <- places(Email.Message()).map(_._1) do
                    items += s"$kind in $place" -> (m => places(m).toMap.apply(place).exists(v => samples.exists(v.contains)))
                for (label, found) <- Seq[(String, String => Boolean)](
                        "a control"       -> (_.exists(c => isControl(c) && c != '\r' && c != '\n')),
                        "CR"              -> (_.contains('\r')),
                        "LF"              -> (_.contains('\n')),
                        "TAB"             -> (_.contains('\t')),
                        "an encoded word" -> (_.contains("=?"))
                    )
                do
                    items += s"$label in the subject"    -> (m => found(m.subject))
                    items += s"$label in a display name" -> (m => names(m).exists(_._2.exists(found)))
                end for
                items += "an encoded word in a body"  -> (m => bodies(m).exists(_.contains("=?")))
                items += "a delimiter line in a body" -> (m => bodies(m).exists(b => lines(b).exists(_.startsWith("--=_kyo"))))
                items += "a delimiter line in a part" -> (m => contents(m).exists(c => new String(c.toArray, "UTF-8").contains("--=_kyo")))
                lineLengths.foreach { n =>
                    items += s"a body line of $n" -> (m => bodies(m).exists(b => lines(b).exists(_.length == n)))
                    items += s"a subject of $n"   -> (_.subject.length == n)
                }
                items += "a file name needing continuations" -> (m => fileNames(m).exists(_.getBytes("UTF-8").length > 78))
                items += "a part with every octet"           -> (m => contents(m).exists(_.toArray.distinct.length == 256))
                items += "a part with random octets"         -> (m => contents(m).exists(c => c.toArray.exists(_ < 0) && c.size != 256))
                items += "an empty part"                     -> (m => contents(m).exists(_.isEmpty))
                items += "a line with trailing white space"  ->
                    (m => bodies(m).exists(b => lines(b).exists(l => l.endsWith(" ") || l.endsWith("\t"))))
                items += "a line starting From" -> (m => bodies(m).exists(b => lines(b).exists(_.startsWith("From "))))
                items += "a lone dot line"      -> (m => bodies(m).exists(b => lines(b).contains(".")))
                val uncovered = items.result().filterNot((_, holds) => messages.exists(holds)).map(_._1)
                assert(uncovered.isEmpty, uncovered.mkString("not generated: ", ", ", ""))
            }
        }
        "the law over every vendored message of the four corpora, and every refusal warranted by the message" in {
            import kyo.internal.email.vectors.*
            import kyo.internal.email.mime.AddressCodec
            val corpus =
                EmbeddedMime4jSet.files.filter((name, _) => name.endsWith(".msg")) ++
                    EmbeddedCpythonSet.files.filter((name, _) => name.startsWith("msg_")) ++
                    EmbeddedStalwartSet.files.filter((name, _) => name.startsWith("rfc-")) ++
                    EmbeddedThunderbirdSet.files
            assert(corpus.size == 175)
            val refusals = corpus.toSeq.sortBy(_._1).flatMap { (name, content) =>
                val whole = parsed(content())
                val m     = whole.copy(headers = whole.headers.filterNot(h => fieldNames.contains(h.name.toLowerCase)))
                rendered(m) match
                    case Result.Success(_) =>
                        law(m, name)
                        None
                    case Result.Failure(ex: EmailInvalidAddressException) =>
                        val all = m.from ++ m.sender.toChunk ++ m.to ++ m.cc ++ m.bcc ++ m.replyTo
                        assert(all.exists(a => a.address == ex.address && !AddressCodec.isAddrSpec(a.address)), s"$name: $ex")
                        Some(s"$name: address ${ex.address}")
                    case Result.Failure(ex: EmailInvalidHeaderException) =>
                        val values    = m.headers.filter(_.name == ex.name).map(_.value)
                        val warranted = ex.problem match
                            case EmailInvalidHeaderException.Problem.InvalidName =>
                                values.nonEmpty && !ex.name.forall(c => c > ' ' && c < '\u007f' && c != ':')
                            case EmailInvalidHeaderException.Problem.LineBreakInValue =>
                                values.exists(v => v.contains('\r') || v.contains('\n'))
                            case EmailInvalidHeaderException.Problem.ControlCharacterInValue => values.exists(_.exists(isControl))
                            case EmailInvalidHeaderException.Problem.LineTooLong             =>
                                values.exists(_.split("[ \t]+").exists(_.getBytes("UTF-8").length >= 998 - ex.name.length - 2))
                            case other => false
                        assert(warranted, s"$name: $ex")
                        Some(s"$name: ${ex.name} ${ex.problem}")
                    case other =>
                        fail(s"$name: $other")
                end match
            }
            assert(refusals.size == 7, refusals.mkString("\n"))
        }
    }

    final private case class Stored(message: Email.Message, flags: Set[Email.Flag], code: EmailSend.EnhancedStatusCode) derives Schema,
          CanEqual
    final private case class ParameterShape(name: String, value: String) derives Schema
    final private case class MediaTypeShape(mainType: String, subType: String, parameters: Chunk[ParameterShape]) derives Schema
    final private case class StatusCodeShape(statusClass: EmailSend.EnhancedStatusCode.StatusClass, subject: Int, detail: Int)
        derives Schema

    private def rejection[A](decoded: Result[DecodeException, A]): Maybe[Throwable] =
        decoded match
            case Result.Failure(ConstructorRejectedException(_, _, cause: Throwable)) => Present(cause)
            case _                                                                    => Absent

    "a message, flags and a status code round-trip through Json, and a malformed media type, status code or flag fails decode" in {
        val message = report.copy(
            sender = Present(grace),
            cc = Chunk(Email.Address("cc@example.com")),
            bcc = Chunk(Email.Address("bcc@example.com")),
            replyTo = Chunk(ada),
            date = Present(EmailLiterals.instantOf("1996-07-17T09:44:25Z")),
            inReplyTo = Chunk(EmailLiterals.messageIdOf("<root@example.com>")),
            attachments = Chunk(
                Email.Attachment(
                    EmailLiterals.mediaTypeOf("text", "calendar", "method" -> "REQUEST", "charset" -> "UTF-8"),
                    Present("invite.ics"),
                    octets("BEGIN")
                ),
                Email.Attachment(EmailLiterals.mediaTypeOf("application", "pdf"), Absent, octets("%PDF\u0000\u00ff"))
            ),
            inlineParts = Chunk(
                Email.InlinePart(
                    EmailLiterals.contentIdOf("<logo@example.com>"),
                    EmailLiterals.mediaTypeOf("image", "png"),
                    Present("logo.png"),
                    octets("\u0089PNG")
                )
            ),
            headers = Chunk(Email.Header("X-Note", "=?UTF-8?Q?caf=C3=A9?="))
        )
        val others = Chunk("\\Forwarded", "$a%b").flatMap(wire => Email.Flag.fromWire(wire).toChunk)
        assert(others.map(_.wire) == Chunk("\\Forwarded", "$a%b") && others.forall(_.isInstanceOf[Email.Flag.Other]))
        val flags  = Set[Email.Flag](Email.Flag.Seen, Email.Flag.Draft, EmailLiterals.keywordOf("$Forwarded")) ++ others
        val stored = Stored(message, flags, EmailLiterals.statusCodeOf(EmailSend.EnhancedStatusCode.StatusClass.Permanent, 1, 1))
        assert(Json.decode[Stored](Json.encode(stored)) == Result.succeed(stored))

        assert(rejection(Json.decode[Email.MediaType](Json.encode(MediaTypeShape("te xt", "plain", Chunk.empty)))) ==
            Present(MimeInvalidMediaTypeException(MimeException.Violation.NotAToken("type", "te xt"))))
        val duplicated = MediaTypeShape("text", "plain", Chunk(ParameterShape("charset", "a"), ParameterShape("CHARSET", "b")))
        assert(rejection(Json.decode[Email.MediaType](Json.encode(duplicated))) ==
            Present(MimeInvalidMediaTypeException(MimeException.Violation.DuplicateParameter("charset"))))
        val injected = MediaTypeShape("text", "plain", Chunk(ParameterShape("a\r\nX-Evil", "b")))
        assert(rejection(Json.decode[Email.MediaType](Json.encode(injected))) ==
            Present(MimeInvalidMediaTypeException(MimeException.Violation.NotAToken("parameter name", "a\r\nX-Evil"))))

        val code = StatusCodeShape(EmailSend.EnhancedStatusCode.StatusClass.Permanent, 1000, 1)
        assert(rejection(Json.decode[EmailSend.EnhancedStatusCode](Json.encode(code))) ==
            Present(EmailInvalidEnhancedStatusCodeException(EmailInvalidEnhancedStatusCodeException.Violation.SubjectOutOfRange(1000))))

        assert(Json.encode[Email.Flag.Keyword](EmailLiterals.keywordOf("$Forwarded")) == "\"$Forwarded\"")
        assert(Json.decode[Email.Flag.Keyword]("\"$Forwarded\"") == Result.succeed(EmailLiterals.keywordOf("$Forwarded")))
        assert(rejection(Json.decode[Email.Flag.Keyword](Json.encode("two words"))) == Present(EmailInvalidFlagException("two words")))
        Seq("\\Seen", "\\RECENT", "Forwarded", "", "\\a\r\nb", "a b", "a(b").foreach { wire =>
            assert(rejection(Json.decode[Email.Flag.Other](Json.encode(wire))) == Present(EmailInvalidFlagException(wire)))
        }
        Json.decode[Email.Flag.Other]("\"$a%b\"") match
            case Result.Success(other) =>
                assert(other.wire == "$a%b")
                assert(Json.encode[Email.Flag.Other](other) == "\"$a%b\"")
            case failed => fail(s"expected the flag $$a%b, got $failed")
        end match
        assert(rejection(Json.decode[Email.Flag.Keyword](Json.encode("$a%b"))) == Present(EmailInvalidFlagException("$a%b")))
        assert(Json.encode[Email.Flag](Email.Flag.Seen) == "\"\\\\Seen\"")
        assert(Json.decode[Email.Flag](Json.encode("\\SEEN")) == Result.succeed(Email.Flag.Seen))
        Seq("\\Recent", "two words", "a)b", "a\u0001b", "").foreach { wire =>
            assert(rejection(Json.decode[Email.Flag](Json.encode(wire))) == Present(EmailInvalidFlagException(wire)))
        }
    }

end EmailMessageTest
