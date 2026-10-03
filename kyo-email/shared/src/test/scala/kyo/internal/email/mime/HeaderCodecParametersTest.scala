package kyo.internal.email.mime

import kyo.*
import kyo.internal.email.mime.HeaderCodec.Disposition
import kyo.internal.email.mime.HeaderCodec.DispositionKind
import kyo.internal.email.mime.HeaderCodec.Field
import kyo.internal.email.mime.HeaderCodec.ValueText

class HeaderCodecParametersTest extends kyo.test.Test[Any]:

    // The field whose octets are the UTF-8 of `value`.
    private def utf8(value: String): Field = fieldOf(value.getBytes("UTF-8"))

    // Up to 39 characters of the parameter grammar's delimiters, quoting, escapes, a non-ASCII letter and NUL.
    private def drawn(using Frame): String < Sync =
        Random.nextInt(40).map(n => Random.nextString(n, ";=*'%\"\\()/ \tab09\u00e9\u0000?"))

    // The field whose octets are the code units of `value`, each below 0x100.
    private def octets(value: String): Field = fieldOf(value.toCharArray.map(_.toByte))

    private def fieldOf(value: Array[Byte]): Field =
        HeaderCodec.readSection(Span.from("X:".getBytes("UTF-8") ++ value ++ "\r\n\r\n".getBytes("UTF-8")), 0).fields(0)

    private def contentType(value: String): Maybe[Email.MediaType] = HeaderCodec.contentType(utf8(value))

    private def params(value: String): Chunk[(String, String)] =
        contentType(value).map(_.parameters.map(p => (p.name, p.value))).getOrElse(Chunk.empty)

    private def param(value: String, name: String): Maybe[String] = contentType(value).flatMap(_.parameter(name))

    // The first field of an RFC example, given as its printed lines without their indent.
    private def example(lines: String*): Field =
        HeaderCodec.readSection(Span.from((lines.mkString("\r\n") + "\r\n\r\n").getBytes("UTF-8")), 0).fields(0)

    "the value grammar" - {
        "type and subtype are lowercased, parameter values keep their case" in {
            val t = contentType("Text/HTML; Charset=UTF-8")
            assert(t.map(_.baseType) == Present("text/html"))
            assert(t.map(_.parameters.map(p => (p.name, p.value))) == Present(Chunk("charset" -> "UTF-8")))
        }
        "comments and white space may stand between every token, and comments nest" in {
            assert(contentType("(hello) text (plain) / (world) plain (eod); charset=us-ascii") ==
                Present(EmailLiterals.mediaTypeOf("text", "plain", "charset" -> "us-ascii")))
            assert(params("text/plain (a (b) \\) c); x=1") == Chunk("x" -> "1"))
            assert(params(" \t text / plain \t ; \t x \t = \t 1 \t ") == Chunk("x" -> "1"))
        }
        "an unclosed comment where CFWS stands runs to the end of the value; inside an unquoted value ( is data" in {
            assert(params("text/plain; a=\"1\" (unclosed; b=2") == Chunk("a" -> "1"))
            assert(contentType("text/plain (unclosed; a=1") == Present(EmailLiterals.mediaTypeOf("text", "plain")))
            assert(params("text/plain; a=1 (unclosed; b=2") == Chunk("a" -> "1 (unclosed", "b" -> "2"))
        }
        "a quoted string has its quoted pairs resolved, and keeps white space at either end" in {
            assert(param("text/plain; name=\"  a \\\"b\\\" \\\\ c  \"", "name") == Present("  a \"b\" \\ c  "))
        }
        "an unclosed quoted string runs to the end of the value" in {
            assert(param("text/plain; name=\"a b", "name") == Present("a b"))
        }
        "empty parameters, a dangling ;, a parameter with no name and one with no = are skipped" in {
            assert(params("text/plain;;; ; a=1;") == Chunk("a" -> "1"))
            assert(params("text/plain; =x; b; c=2") == Chunk("c" -> "2"))
            assert(params("text/plain ;;;;; = ;; name=\"value\"") == Chunk("name" -> "value"))
        }
        "an empty unquoted value is no value, an empty quoted string is the empty value" in {
            assert(params("text/plain; a=; b=\"\"; c=  ") == Chunk("b" -> ""))
        }
        "a parameter with no ; before it is read, after the subtype and after a value" in {
            assert(params("message/external-body; access-type=mail-server server=\"x\"; y=1") ==
                Chunk("access-type" -> "mail-server", "server" -> "x", "y" -> "1"))
            assert(params("application/x-stuff title=a") == Chunk("title" -> "a"))
            assert(params("application/x-stuff (c) title (d) = a") == Chunk("title" -> "a"))
        }
        "any other text after the subtype makes the value invalid" in {
            Seq(
                "text/plain/format; charset=x",
                "text/plain junk",
                "text",
                "text/",
                "/plain",
                "",
                "(only) (comments)",
                "\u00e1/\u00e9",
                "te xt/plain",
                "text/pl@in"
            ).foreach { value =>
                assert(contentType(value) == Absent, value)
            }
        }
        "other text after a parameter value is skipped to the next ;" in {
            assert(params("text/plain; a=\"1\" junk; b=2") == Chunk("a" -> "1", "b" -> "2"))
        }
        "an unquoted value runs to the next ;, keeping white space, parentheses, ? and = inside it and removing CFWS at its end" in {
            assert(param("text/plain; name=report (final).pdf", "name") == Present("report (final).pdf"))
            assert(param("text/plain; name=a b  c ; x=1", "name") == Present("a b  c"))
            assert(param("text/plain; charset=us-ascii (Plain text)", "charset") == Present("us-ascii"))
            assert(param("text/plain; name=a?b=c", "name") == Present("a?b=c"))
        }
        "an unquoted value written with raw octets from 0x80 keeps them as the field's text" in {
            assert(param("application/pdf; name=r\u00e9sum\u00e9.pdf", "name") == Present("r\u00e9sum\u00e9.pdf"))
            assert(HeaderCodec.contentType(octets("application/pdf; name=r\u00e9sum\u00e9.pdf")).flatMap(_.parameter("name")) ==
                Present("r\u00e9sum\u00e9.pdf"))
        }
        "a parameter named twice keeps its first value" in {
            assert(params("text/plain; a=1; A=2; b=3") == Chunk("a" -> "1", "b" -> "3"))
        }
        "a value keeps control characters: HTAB from a fold and a raw NUL" in {
            assert(param("text/plain; name=\"a\r\n\tb\"", "name") == Present("a\tb"))
            assert(param("text/plain; name=\"a\u0000b\"", "name") == Present("a\u0000b"))
        }
        "a name that is not a token is not a parameter" in {
            assert(params("text/plain; n\u00e1me=1; ok=2") == Chunk("ok" -> "2"))
        }
        "a comment nested 100,000 deep is removed with a counter" in {
            val deep = "(" * 100000 + ")" * 100000
            assert(params(s"text/plain $deep; x=1") == Chunk("x" -> "1"))
        }
        "RFC 2045 5.1: charset=us-ascii (Plain text) and charset=\"us-ascii\" are completely equivalent" in {
            val first  = example("Content-type: text/plain; charset=us-ascii (Plain text)")
            val second = example("Content-type: text/plain; charset=\"us-ascii\"")
            assert(first.name == "Content-type" && second.name == "Content-type")
            assert(HeaderCodec.contentType(first) == Present(EmailLiterals.mediaTypeOf("text", "plain", "charset" -> "us-ascii")))
            assert(HeaderCodec.contentType(first) == HeaderCodec.contentType(second))
        }
    }

    "RFC 2231" - {
        "section 3: the continuation is semantically identical to the whole value" in {
            val continued = example(
                "Content-Type: message/external-body; access-type=URL;",
                " URL*0=\"ftp://\";",
                " URL*1=\"cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar\""
            )
            val whole = example(
                "Content-Type: message/external-body; access-type=URL;",
                "  URL=\"ftp://cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar\""
            )
            assert(HeaderCodec.contentType(continued) == HeaderCodec.contentType(whole))
            assert(HeaderCodec.contentType(whole).flatMap(_.parameter("url")) ==
                Present("ftp://cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar"))
            assert(HeaderCodec.contentType(whole).map(_.parameters.map(_.name)) == Present(Chunk("access-type", "url")))
        }
        "section 4: charset, language and percent escapes" in {
            val field = example("Content-Type: application/x-stuff;", " title*=us-ascii'en-us'This%20is%20%2A%2A%2Afun%2A%2A%2A")
            assert(HeaderCodec.contentType(field) ==
                Present(EmailLiterals.mediaTypeOf("application", "x-stuff", "title" -> "This is ***fun***")))
        }
        "section 4.1: encoded and unencoded segments, with no ; between the parameters as printed" in {
            val field = example(
                "Content-Type: application/x-stuff",
                " title*0*=us-ascii'en'This%20is%20even%20more%20",
                " title*1*=%2A%2A%2Afun%2A%2A%2A%20",
                " title*2=\"isn't it!\""
            )
            assert(HeaderCodec.contentType(field) ==
                Present(EmailLiterals.mediaTypeOf("application", "x-stuff", "title" -> "This is even more ***fun*** isn't it!")))
        }
        "segments arriving out of order are joined by index" in {
            assert(param("text/plain; name*1=\"b\"; name*0=\"a\"; name*2=\"c\"", "name") == Present("abc"))
        }
        "a gap: the segments present are joined in index order" in {
            assert(param("text/plain; name*0=a; name*2=c; name*5=f", "name") == Present("acf"))
        }
        "a duplicate index keeps its first segment" in {
            assert(param("text/plain; name*0=a; name*0=x; name*1=b", "name") == Present("ab"))
        }
        "a leading zero is read as its number" in {
            assert(param("text/plain; name*01=b; name*00=a", "name") == Present("ab"))
        }
        "a section of more than nine digits, or a name of another shape, is a plain parameter of that whole name" in {
            assert(params("text/plain; a*1234567890=x; a*b=1; 3**=2") == Chunk("a*1234567890" -> "x", "a*b" -> "1", "3**" -> "2"))
        }
        "%XX in either case is an octet, and % not followed by two hex digits is kept" in {
            assert(param("text/plain; name*=utf-8''a%c3%A9%zz%4", "name") == Present("a\u00e9%zz%4"))
        }
        "the charset of segment 0 decodes the octets of every segment, encoded or not" in {
            assert(param("text/plain; name*0*=iso-8859-1''caf%E9; name*1=\" au lait\"", "name") == Present("caf\u00e9 au lait"))
            assert(param("text/plain; name*0*=iso-8859-1''a; name*1=\u00e9", "name") == Present("a\u00c3\u00a9"))
        }
        "an unencoded segment's octets are recovered from a field read as windows-1252" in {
            assert(HeaderCodec.contentType(octets("text/plain; name*0*=utf-8''%C3%A9; name*1=\u00e9")).flatMap(_.parameter("name")) ==
                Present("\u00e9\ufffd"))
        }
        "with no encoded segment the segments are joined as text, not decoded again" in {
            val field = octets("text/plain; name*0=\u00c3; name*1=\u00a9; other=\u00e9")
            assert(field.text == ValueText.Windows1252)
            assert(HeaderCodec.contentType(field).flatMap(_.parameter("name")) == Present("\u00c3\u00a9"))
        }
        "an unknown or empty charset decodes as UTF-8 when well-formed, and windows-1252 otherwise" in {
            assert(param("text/plain; name*=x-unknown''caf%C3%A9", "name") == Present("caf\u00e9"))
            assert(param("text/plain; name*=''caf%E9", "name") == Present("caf\u00e9"))
        }
        "segment 0 without the two quotes is data with no charset" in {
            assert(param("text/plain; name*0*=abc%41", "name") == Present("abcA"))
        }
        "only segment 0 carries a charset: a later segment's charset-like prefix is data" in {
            assert(param("text/plain; name*0*=iso-8859-1''a; name*1*=iso-8859-1''b", "name") == Present("aiso-8859-1''b"))
        }
        "the extended form wins over the plain one, in either order, at the position of the first" in {
            assert(params("text/plain; a=1; name=plain; b=2; name*=utf-8''ext") == Chunk("a" -> "1", "name" -> "ext", "b" -> "2"))
            assert(params("text/plain; name*=utf-8''ext; name=plain") == Chunk("name" -> "ext"))
        }
        "the continuation set wins over name*" in {
            assert(param("text/plain; name*=utf-8''single; name*0=con; name*1=tinued", "name") == Present("continued"))
        }
        "an extended value written as a quoted string has its quotes removed first" in {
            assert(param("text/plain; name*=\"iso-8859-1''HasenundFr%F6sche.txt\"", "name") == Present("HasenundFr\u00f6sche.txt"))
        }
        "the language is dropped" in {
            assert(params("text/plain; title*=us-ascii'en-us'x") == Chunk("title" -> "x"))
        }
    }

    "encoded words in a parameter value" - {
        "a word joined to text in a quoted value decodes where it stands" in {
            assert(param("image/png; name=\"=?utf-8?q?=E3=83=8F=E3=83=AD?=.png\"", "name") == Present("\u30cf\u30ed.png"))
        }
        "adjacent words join across white space" in {
            assert(param("image/jpeg; name=\"=?iso-8859-1?B?4Q==?= =?utf-8?B?w6k=?= =?iso-8859-1?q?=ED?=.jpeg\"", "name") ==
                Present("\u00e1\u00e9\u00ed.jpeg"))
        }
        "an unquoted value holding words and white space" in {
            assert(param("image/jpeg; name==?iso-8859-1?B?4Q==?= =?utf-8?B?w6k=?=.jpeg", "name") == Present("\u00e1\u00e9.jpeg"))
        }
        "a word split across continuations is joined before it is decoded" in {
            assert(param("text/plain; name*0=\"=?utf-8?Q?caf\"; name*1=\"=C3=A9?=\"", "name") == Present("caf\u00e9"))
        }
        "a value with an encoded segment is not read for encoded words" in {
            assert(param("text/plain; name*=utf-8''=%3Futf-8%3FQ%3Fa%3F=", "name") == Present("=?utf-8?Q?a?="))
        }
        "a word that does not decode stays as its text" in {
            assert(param("text/plain; name=\"=?x-unknown?Q?a?=.txt\"", "name") == Present("=?x-unknown?Q?a?=.txt"))
        }
    }

    "boundary" - {
        "white space at its end is removed, and white space inside it kept" in {
            assert(param("multipart/mixed; boundary=\"abc \t \"", "boundary") == Present("abc"))
            assert(param("multipart/mixed; boundary=\"simple boundary\"", "boundary") == Present("simple boundary"))
            assert(param("multipart/mixed; boundary=\"  lead\"", "boundary") == Present("  lead"))
        }
    }

    "Content-Disposition" - {
        "RFC 2183 3: inline, and attachment with a file name, a quoted date and a dangling ;" in {
            val inline = example("Content-Disposition: inline")
            assert(inline.name == "Content-Disposition")
            assert(HeaderCodec.contentDisposition(inline) == Present(Disposition(DispositionKind.Inline, Chunk.empty)))
            val attachment = example(
                "Content-Disposition: attachment; filename=genome.jpeg;",
                "  modification-date=\"Wed, 12 Feb 1997 16:29:51 -0500\";"
            )
            assert(HeaderCodec.contentDisposition(attachment) ==
                Present(Disposition(
                    DispositionKind.Attachment,
                    Chunk("filename" -> "genome.jpeg", "modification-date" -> "Wed, 12 Feb 1997 16:29:51 -0500")
                )))
        }
        "inline and attachment in any ASCII case are their kinds; any other type is kept lowercased" in {
            assert(HeaderCodec.contentDisposition(utf8("X-Custom; a=1")) ==
                Present(Disposition(DispositionKind.Other("x-custom"), Chunk("a" -> "1"))))
            assert(HeaderCodec.contentDisposition(utf8("ATTACHMENT")) == Present(Disposition(DispositionKind.Attachment, Chunk.empty)))
            assert(HeaderCodec.contentDisposition(utf8("InLine")) == Present(Disposition(DispositionKind.Inline, Chunk.empty)))
            assert(HeaderCodec.contentDisposition(utf8("inlıne")) == Absent)
        }
        "each kind's label is the type as written" in {
            assert(DispositionKind.Inline.label == "inline")
            assert(DispositionKind.Attachment.label == "attachment")
            assert(DispositionKind.Other("x-custom").label == "x-custom")
        }
        "no type, or other text after it, is no disposition" in {
            Seq("", "; filename=a", "inline/x", "(c)", "\u00e1").foreach { value =>
                assert(HeaderCodec.contentDisposition(utf8(value)) == Absent, value)
            }
        }
        "the file name goes through RFC 2231 and encoded words like any parameter" in {
            assert(HeaderCodec.contentDisposition(utf8("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf")) ==
                Present(Disposition(DispositionKind.Attachment, Chunk("filename" -> "r\u00e9sum\u00e9.pdf"))))
            assert(HeaderCodec.contentDisposition(utf8("attachment; filename=\"=?utf-8?Q?r=C3=A9sum=C3=A9?=.pdf\"")) ==
                Present(Disposition(DispositionKind.Attachment, Chunk("filename" -> "r\u00e9sum\u00e9.pdf"))))
        }
    }

    "Content-Transfer-Encoding" - {
        "CFWS around the label is removed, and the label may be quoted" in {
            assert(HeaderCodec.transferEncoding(utf8("base64")) == Present(TransferEncoding.Kind.Base64))
            assert(HeaderCodec.transferEncoding(utf8(" (x) BASE64 (y (z)) ")) == Present(TransferEncoding.Kind.Base64))
            assert(HeaderCodec.transferEncoding(utf8("\"quoted-printable\"")) == Present(TransferEncoding.Kind.QuotedPrintable))
        }
        "an unknown label, or anything else in the value, is unknown" in {
            Seq("x-uuencode", "base64 junk", "base64; x=1", "(only a comment)", "", "base 64").foreach { value =>
                assert(HeaderCodec.transferEncoding(utf8(value)) == Absent, value)
            }
        }
    }

    "totality" - {
        "10,000 values drawn from the grammar's characters are read without failing" in {
            Random.withSeed(0x6b796f)(Kyo.fill(10000)(drawn)).map { values =>
                val read = values.map { value =>
                    val field = utf8(value)
                    (HeaderCodec.contentType(field), HeaderCodec.contentDisposition(field), HeaderCodec.transferEncoding(field))
                }
                assert(read.size == 10000)
                assert(read.forall((t, d, _) => t.forall(_.mainType.nonEmpty) && d.forall(_.kind.label.nonEmpty)))
            }
        }
        "every type, and every inline or attachment disposition, read from 10,000 generated values writes back and reads back equal, unless a name holds *" in {
            val heads = Seq("text/plain", "Multipart/X-Mixed", "inline", "ATTACHMENT", "x-custom")
            Random.withSeed(0x6b796f)(Kyo.fill(10000)(Kyo.zip(Random.nextValue(heads), drawn).map(_ + "; a=" + _))).map { values =>
                def readBack(units: Result[HeaderCodec.WriteFailure, Chunk[String]]): Result[HeaderCodec.WriteFailure, Field] =
                    units.flatMap(u => HeaderCodec.fold("X", u)).map(field =>
                        HeaderCodec.readSection(Span.from((field + "\r\n").getBytes("UTF-8")), 0).fields(0)
                    )
                def unwritable(names: Chunk[String]): Chunk[Result[HeaderCodec.WriteFailure, Nothing]] =
                    names.filter(_.contains('*')).map(name => Result.fail(HeaderCodec.WriteFailure.UnwritableParameterName(name)))
                val types        = Chunk.from(values.flatMap(v => HeaderCodec.contentType(utf8(v)).toChunk))
                val dispositions = Chunk.from(values.flatMap(v => HeaderCodec.contentDisposition(utf8(v)).toChunk))
                assert(types.size == 4034 && dispositions.size == 5966, s"${types.size} types, ${dispositions.size} dispositions")
                types.foreach { t =>
                    val back = readBack(HeaderCodec.contentTypeUnits(t)).map(HeaderCodec.contentType)
                    assert(back == Result.succeed(Present(t)) || unwritable(t.parameters.map(_.name)).contains(back), s"$t: $back")
                }
                def written(d: Disposition): Maybe[Result[HeaderCodec.WriteFailure, Chunk[String]]] =
                    d.kind match
                        case DispositionKind.Inline     => Present(HeaderCodec.dispositionUnits(DispositionKind.Inline, d.parameters))
                        case DispositionKind.Attachment => Present(HeaderCodec.dispositionUnits(DispositionKind.Attachment, d.parameters))
                        case DispositionKind.Other(_)   => Absent
                val writable = dispositions.flatMap(d => written(d).map(d -> _).toChunk)
                assert(writable.size == 3984, s"${writable.size} writable dispositions")
                writable.foreach { (d, units) =>
                    val back = readBack(units).map(HeaderCodec.contentDisposition)
                    assert(back == Result.succeed(Present(d)) || unwritable(d.parameters.map(_._1)).contains(back), s"$d: $back")
                }
                succeed
            }
        }
    }

    "pathological inputs" - {
        "10,000 continuation segments arriving in reverse order are joined in index order" in {
            val count  = 10000
            val value  = "text/plain" + (count - 1 to 0 by -1).map(i => s"; name*$i=${i % 10}").mkString
            val joined = (0 until count).map(i => (i % 10).toString).mkString
            assert(param(value, "name") == Present(joined))
        }
        "100,000 parameters are all read, in order" in {
            val count = 100000
            val read  = params("text/plain" + (0 until count).map(i => s"; p$i=$i").mkString)
            assert(read.size == count && read.head == ("p0" -> "0") && read.last == (s"p${count - 1}" -> s"${count - 1}"))
        }
        "an unquoted value followed by 200,000 unclosed ( is that text, ( being data" in {
            val tail = " (" * 200000
            assert(param(s"text/plain; a=x$tail", "a") == Present("x" + tail))
        }
        "200,000 closed comments between a value and a parameter with no ; before it" in {
            val comments = " (c)" * 200000
            assert(params(s"text/plain; a=x$comments b=y") == Chunk("a" -> "x", "b" -> "y"))
        }
        "a quoted value followed by a megabyte of ; and white space" in {
            assert(params("text/plain; a=\"1\"" + "; \t" * (1024 * 1024 / 3)) == Chunk("a" -> "1"))
        }
    }

    "Stalwart content_type.json" - {
        import HeaderCodecStalwart.*

        lazy val listed = parseContentType(EmbeddedStalwartContentTypeDifferencesTsv.text)

        "every vector is read" in {
            assert(contentTypeVectors.size == 103)
        }
        "every difference from Stalwart is listed, with its reason" in {
            val unlisted = observedContentType.filterNot(listed.contains)
            assert(unlisted.isEmpty, s"${unlisted.size} differences are not listed:\n${renderContentType(unlisted)}")
        }
        "every listed difference still occurs" in {
            val stale = listed.filterNot(observedContentType.contains)
            assert(stale.isEmpty, s"${stale.size} listed differences no longer occur:\n${renderContentType(stale)}")
        }
        "the list has no duplicate rows, and every other vector agrees" in {
            assert(listed.distinct.size == listed.size)
            val agreeing = contentTypeVectors.zipWithIndex.filterNot((_, i) => listed.exists(_.index == i))
            assert(agreeing.forall((v, _) => moduleReading(v) == referenceReading(v)))
            assert(agreeing.size + listed.size == 103)
        }
    }

end HeaderCodecParametersTest
