package kyo

import kyo.internal.charset.Utf8
import kyo.internal.email.mime.DateCodec

/** Random [[Email.Message]] values inside the round-trip law's domain: dates of whole seconds from year 0000, well-formed UTF-16, extra
  * headers named outside the fields `render` writes, with no white space at either end of their values and no control character in them.
  * Everything else the model can hold is drawn: every field absent and present, every body shape, non-ASCII of four kinds, control
  * characters where the model allows them, text that looks like encoded words or delimiters, and lines at the lengths where the encodings
  * change. Every draw comes from the [[Random]] in context, so `Random.withSeed` fixes the messages.
  */
object EmailMessageGenerator:

    val latin: Seq[String]         = Seq("é", "ü", "ß", "ñ", "Ø")
    val cjk: Seq[String]           = Seq("漢", "字", "日本", "中文")
    val supplementary: Seq[String] = Seq("😀", "𝄞", "𠀋")
    val combining: Seq[String]     = Seq("é", "ä", "ñ")
    val controls: Seq[String]      = Seq("\u0001", "\u001b", "\u007f", "\u0085", "\r", "\n", "\t", "\r\n")
    val encodedWords: Seq[String]  = Seq("=?utf-8?q?x?=", "=?UTF-8?B?w6k=?=", "=?iso-8859-1?q?caf=E9?= =?utf-8?q?y?=")
    val delimiters: Seq[String]    = Seq("--=_kyo0_0", "--=_kyo0_0--", "--=_kyo1_0", "--=_kyo2_0", "--=_kyo0_1", "--=_kyo1_0 padding")
    val lineLengths: Seq[Int]      = Seq(75, 76, 77, 78, 79, 997, 998, 999)

    private val atext  = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&'*+-/=?^_`{|}~"
    private val ascii  = atext + "\"(),.:;<>@[\\] "
    private val idText = atext + "\"(),.:;@[\\]"

    private val extraNames = Seq("X-Mailer", "Received", "Comments", "Keywords", "X-Priority", "List-Id", "x-lower", "X-!~")

    def message(using Frame): Email.Message < Sync =
        for
            from    <- addresses
            sender  <- maybe(address)
            to      <- addresses
            cc      <- addresses
            bcc     <- addresses
            replyTo <- addresses
            subject <-
                pick[String](4)(n => if n == 0 then "" else pick[String](4)(m => if m == 0 then sizedLine else text(controls = true)))
            date        <- maybe(Random.nextDouble.map(d => DateCodec.instantOf(-62167219200L + (d * 315537897599L).toLong)))
            messageId   <- maybe(id.map(EmailLiterals.messageIdOf(_)))
            inReplyTo   <- several(id.map(EmailLiterals.messageIdOf(_)))
            references  <- several(id.map(EmailLiterals.messageIdOf(_)))
            text        <- pick[String](2)(n => if n == 0 then "" else body)
            html        <- maybe(pick[String](8)(n => if n == 0 then "" else body))
            attachments <- several(Kyo.zip(mediaType, fileName, content).map(Email.Attachment(_, _, _)))
            inlineParts <- several(Kyo.zip(id, mediaType, fileName, content).map((i, t, f, c) =>
                Email.InlinePart(EmailLiterals.contentIdOf(i), t, f, c)
            ))
            headers <- several(Kyo.zip(one(extraNames), headerValue).map(Email.Header(_, _)))
        yield Email.Message(
            from = from,
            sender = sender,
            to = to,
            cc = cc,
            bcc = bcc,
            replyTo = replyTo,
            subject = subject,
            date = date,
            messageId = messageId,
            inReplyTo = inReplyTo,
            references = references,
            text = text,
            html = html,
            attachments = attachments,
            inlineParts = inlineParts,
            headers = headers
        )

    def one[A](values: Seq[A])(using Frame): A < Sync = Random.nextValue(values)

    // A draw below `bound` mapped to a value; the explicit `A` lets a branch give a plain value where another gives a computation.
    private def pick[A](bound: Int)(f: Int => A < Sync)(using Frame): A < Sync = Random.nextInt(bound).map(f)

    private def maybe[A](value: A < Sync)(using Frame): Maybe[A] < Sync =
        pick[Maybe[A]](2)(n => if n == 0 then Absent else value.map(Present(_)))

    private def several[A](value: A < Sync)(using Frame): Chunk[A] < Sync =
        pick[Chunk[A]](3) {
            case 0 => Chunk.empty[A]
            case 1 => Kyo.fill(1)(value)
            case _ => Random.nextInt(3).map(n => Kyo.fill(2 + n)(value))
        }

    private def addresses(using Frame): Chunk[Email.Address] < Sync = several(address)

    private def address(using Frame): Email.Address < Sync =
        val local = pick[String](5) {
            case 0 => text(controls = false).map(t =>
                    "\"" + t.filter(c => c >= ' ' && c <= '~').replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                )
            case 1 => Kyo.zip(word(atext), word(atext)).map(_ + "." + _)
            case 2 => Kyo.zip(word(atext), one(latin), one(cjk)).map(_ + _ + _)
            case _ => word(atext)
        }
        val domain = pick[String](5) {
            case 0 => Random.nextInt(256).map(n => s"[192.0.2.$n]")
            case 1 => one(latin).map(l => s"b${l}cher.example")
            case _ => word("abcdefghijklmnopqrstuvwxyz0123456789-").map(_ + ".example")
        }
        for
            l    <- local
            d    <- domain
            name <- maybe(pick[String](6)(n => if n == 0 then "" else text(controls = true)))
        yield Email.Address(s"$l@$d", name)
        end for
    end address

    // Drawn again until the module reads it as an id.
    private def id(using Frame): String < Sync =
        Loop.foreach {
            pick[String](4) {
                case 0 => Kyo.zip(word(atext + "(),.:;@[]"), word(atext)).map((l, r) => "\"" + l + "\"@" + r)
                case _ => Kyo.zip(word(idText), word(idText)).map(_ + "@" + _)
            }.map(candidate => if Email.MessageId.read(candidate).isEmpty then Loop.continue else Loop.done(candidate))
        }

    private def word(alphabet: String)(using Frame): String < Sync =
        Random.nextInt(10).map(n => Random.nextString(1 + n, alphabet))

    def text(controls: Boolean)(using Frame): String < Sync =
        Random.nextInt(7).map(n => Kyo.fill(n)(piece(controls))).map(_.mkString)

    private def piece(controls: Boolean)(using Frame): String < Sync =
        pick[String](if controls then 10 else 8) {
            case 0 => word(ascii)
            case 1 => " "
            case 2 => one(latin)
            case 3 => one(cjk)
            case 4 => one(supplementary)
            case 5 => one(combining)
            case 6 => one(encodedWords)
            case 7 => Kyo.zip(word(atext), word(atext)).map(_ + " " + _)
            case 8 => one(EmailMessageGenerator.controls)
            case _ => "\t"
        }

    private def sizedLine(using Frame): String < Sync = one(lineLengths).map(sized(_))

    private def sized(length: Int)(using Frame): String < Sync =
        pick[String](2) {
            case 0 => "x" * length
            case _ => Kyo.fill(length / 10 + 1)(word(atext).map(_.padTo(9, 'y') + " ")).map(_.mkString.take(length))
        }

    private def body(using Frame): String < Sync =
        for
            n         <- Random.nextInt(6)
            lines     <- Kyo.fill(1 + n)(bodyLine)
            separator <- one(Seq("\n", "\n", "\n", "\r\n", "\r"))
        yield lines.mkString(separator)

    private def bodyLine(using Frame): String < Sync =
        pick[String](9) {
            case 0 => sizedLine
            case 1 => word(atext).map("From " + _)
            case 2 => "."
            case 3 => Kyo.zip(word(atext), one(Seq(" ", "\t", "  "))).map(_ + _)
            case 4 => one(delimiters)
            case 5 => one(encodedWords)
            case 6 => word(atext).map("=_" + _)
            case _ => Random.nextInt(4).map(n => text(controls = n == 0))
        }

    private def headerValue(using Frame): String < Sync =
        pick[String](6) {
            case 0 => one(lineLengths.filter(_ < 990)).map(sized(_))
            case 1 => Kyo.zip(word(atext), word(atext)).map(_ + "\t" + _)
            case _ => text(controls = false)
        }.map(value => value.dropWhile(c => c == ' ' || c == '\t').reverse.dropWhile(c => c == ' ' || c == '\t').reverse)

    private def fileName(using Frame): Maybe[String] < Sync =
        maybe(pick[String](4) {
            case 0 => Kyo.fill(12)(Kyo.zip(one(latin), word(atext)).map(_ + _)).map(_.mkString + ".pdf")
            case 1 => text(controls = true)
            case _ => word(atext).map(_ + ".bin")
        })

    private def mediaType(using Frame): Email.MediaType < Sync =
        pick[Email.MediaType](8) {
            case 0 => EmailLiterals.mediaTypeOf("application", "octet-stream")
            case 1 => text(controls = true).map(t => EmailLiterals.mediaTypeOf("image", "png", "name" -> t))
            case 2 => EmailLiterals.mediaTypeOf("text", "plain", "charset" -> "us-ascii", "format" -> "flowed")
            case 3 => EmailLiterals.mediaTypeOf("text", "html", "charset" -> "utf-8")
            case 4 => EmailLiterals.mediaTypeOf("message", "rfc822")
            case 5 => one(Seq("=_kyo0_0", "=_kyo1_0", "=_kyo0_1--", "b", "=_kyo0_")).map(b =>
                    EmailLiterals.mediaTypeOf("multipart", "mixed", "boundary" -> b)
                )
            case 6 => EmailLiterals.mediaTypeOf("multipart", "alternative")
            case _ => Kyo.zip(word("abcdefghijklmnopqrstuvwxyz"), text(controls = true)).map((w, t) =>
                    EmailLiterals.mediaTypeOf("application", "x-" + w, "x-note" -> t)
                )
        }

    private def content(using Frame): Span[Byte] < Sync =
        pick[Span[Byte]](7) {
            case 0 => Span.empty[Byte]
            case 1 => Span.from(Array.tabulate[Byte](256)(_.toByte))
            case 2 => Random.nextInt(300).map(n => Random.nextBytes(n)).map(octets => Span.from(octets.toArray))
            case 3 => Kyo.fill(3)(one(delimiters)).map(d => Utf8.encode(d.mkString("", "\r\n", "\r\n")))
            case 4 => Kyo.zip(text(controls = false), body).map((t, b) =>
                    Utf8.encode("Subject: " + t + "\r\n\r\n" + b.replace("\r", "").replace("\n", "\r\n"))
                )
            case _ => body.map(Utf8.encode(_))
        }

end EmailMessageGenerator
