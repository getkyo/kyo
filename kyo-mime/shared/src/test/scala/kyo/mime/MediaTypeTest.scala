package kyo.mime

import kyo.*

class MediaTypeTest extends kyo.test.Test[Any]:

    private def contentType(value: String): Maybe[MediaType] = MediaType.parse(value).toMaybe

    private def params(value: String): Chunk[(String, String)] =
        contentType(value).map(_.parameters.map(p => (p.name, p.value))).getOrElse(Chunk.empty)

    private def param(value: String, name: String): Maybe[String] = contentType(value).flatMap(_.parameter(name))

    private def violation(value: String): Maybe[MimeException.Violation] =
        MediaType.parse(value) match
            case Result.Failure(e) => Present(e.violation)
            case _                 => Absent

    // A literal the test knows to be valid; a violation here is a defect in the test itself.
    private def mediaTypeOf(mainType: String, subType: String, parameters: (String, String)*): MediaType =
        MediaType.init(mainType, subType, parameters*) match
            case Result.Success(t) => t
            case other             => throw new AssertionError(s"the test literal is not a media type: $other")

    "the value grammar" - {
        "type and subtype are lowercased, parameter values keep their case" in {
            val t = contentType("Text/HTML; Charset=UTF-8")
            assert(t.map(_.baseType) == Present("text/html"))
            assert(t.map(_.parameters.map(p => (p.name, p.value))) == Present(Chunk("charset" -> "UTF-8")))
            assert(t.flatMap(_.charset) == Present("UTF-8"))
        }
        "comments and white space may stand between every token, and comments nest" in {
            assert(contentType("(hello) text (plain) / (world) plain (eod); charset=us-ascii") ==
                Present(mediaTypeOf("text", "plain", "charset" -> "us-ascii")))
            assert(params("text/plain (a (b) \\) c); x=1") == Chunk("x" -> "1"))
            assert(params(" \t text / plain \t ; \t x \t = \t 1 \t ") == Chunk("x" -> "1"))
        }
        "an unclosed comment where CFWS stands runs to the end of the value; inside an unquoted value ( is data" in {
            assert(params("text/plain; a=\"1\" (unclosed; b=2") == Chunk("a" -> "1"))
            assert(contentType("text/plain (unclosed; a=1") == Present(mediaTypeOf("text", "plain")))
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
        "any other text after the subtype makes the value invalid, with the violation naming it" in {
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
                assert(violation(value) == Present(MimeException.Violation.NotWellFormed(value)), value)
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
        "a parameter named twice keeps its first value" in {
            assert(params("text/plain; a=1; A=2; b=3") == Chunk("a" -> "1", "b" -> "3"))
        }
        "a value keeps control characters" in {
            assert(param("text/plain; name=\"a\tb\"", "name") == Present("a\tb"))
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
            val first  = MediaType.parse("text/plain; charset=us-ascii (Plain text)")
            val second = MediaType.parse("text/plain; charset=\"us-ascii\"")
            assert(first == Result.succeed(mediaTypeOf("text", "plain", "charset" -> "us-ascii")))
            assert(first == second)
        }
        "RFC 9110 8.3.1: the type, subtype and parameter names are case-insensitive, parameter values are not" in {
            assert(MediaType.parse("Text/HTML;Charset=\"utf-8\"") == MediaType.parse("text/html;charset=\"utf-8\""))
            assert(MediaType.parse("text/html;charset=\"UTF-8\"").map(_.charset) == Result.succeed(Present("UTF-8")))
        }
    }

    "RFC 2231 in a Content-Type" - {
        "section 3: the continuation is semantically identical to the whole value" in {
            val continued = contentType(
                "message/external-body; access-type=URL; URL*0=\"ftp://\"; URL*1=\"cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar\""
            )
            val whole =
                contentType("message/external-body; access-type=URL; URL=\"ftp://cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar\"")
            assert(continued == whole)
            assert(whole.flatMap(_.parameter("url")) == Present("ftp://cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar"))
            assert(whole.map(_.parameters.map(_.name)) == Present(Chunk("access-type", "url")))
        }
        "section 4: charset, language and percent escapes" in {
            assert(contentType("application/x-stuff; title*=us-ascii'en-us'This%20is%20%2A%2A%2Afun%2A%2A%2A") ==
                Present(mediaTypeOf("application", "x-stuff", "title" -> "This is ***fun***")))
        }
        "section 4.1: encoded and unencoded segments, with and without ; between the parameters" in {
            val expected = Present(mediaTypeOf("application", "x-stuff", "title" -> "This is even more ***fun*** isn't it!"))
            assert(contentType(
                "application/x-stuff; title*0*=us-ascii'en'This%20is%20even%20more%20; title*1*=%2A%2A%2Afun%2A%2A%2A%20; title*2=\"isn't it!\""
            ) == expected)
            assert(contentType(
                "application/x-stuff title*0*=us-ascii'en'This%20is%20even%20more%20 title*1*=%2A%2A%2Afun%2A%2A%2A%20 title*2=\"isn't it!\""
            ) == expected)
        }
        "an encoded value decodes as UTF-8 by default, whatever charset it names (RFC 8187), unless the caller decodes it" in {
            assert(param("text/plain; name*=utf-8''caf%C3%A9", "name") == Present("caf\u00e9"))
            assert(param("text/plain; name*=iso-8859-1''caf%E9", "name") == Present("caf\ufffd"))
            val latin1 = MediaType.parse(
                "text/plain; name*=iso-8859-1''caf%E9",
                decode = {
                    case Parameters.Value.Encoded(_, _, octets) => octets.toArray.map(b => (b & 0xff).toChar).mkString
                    case Parameters.Value.Text(text)            => text
                }
            )
            assert(latin1.map(_.parameter("name")) == Result.succeed(Present("caf\u00e9")))
        }
    }

    "construction" - {
        "checks tokens and duplicate names, and lowercases the type, subtype and names" in {
            assert(MediaType.init("Text", "Plain", "Charset" -> "UTF-8").map(_.render) ==
                Result.succeed(Result.succeed("text/plain; charset=UTF-8")))
            assert(MediaType.init("te xt", "plain").map(_.baseType) ==
                Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.NotAToken("type", "te xt"))))
            assert(MediaType.init("text", "pl@in").map(_.baseType) ==
                Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.NotAToken("subtype", "pl@in"))))
            assert(MediaType.init("text", "plain", "n\u00e1me" -> "1").map(_.baseType) ==
                Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.NotAToken("parameter name", "n\u00e1me"))))
            assert(MediaType.init("text", "plain", "a" -> "1", "A" -> "2").map(_.baseType) ==
                Result.fail(MimeInvalidMediaTypeException(MimeException.Violation.DuplicateParameter("a"))))
        }
        "the failure's message names the violation" in {
            MediaType.init("text", "pl@in") match
                case Result.Failure(e) =>
                    assert(e.violation == MimeException.Violation.NotAToken("subtype", "pl@in"))
                    assert(e.getMessage.contains("invalid media type: subtype pl@in is not a token"))
                case other => fail(s"expected a failure, got $other")
        }
        "a boundary loses trailing white space, as a reader would remove it" in {
            assert(mediaTypeOf("multipart/mixed".split("/")(0), "mixed", "boundary" -> "abc \t ").parameter("boundary") == Present("abc"))
            assert(param("multipart/mixed; boundary=\"simple boundary\"", "boundary") == Present("simple boundary"))
            assert(param("multipart/mixed; boundary=\"  lead\"", "boundary") == Present("  lead"))
        }
        "the failure's message escapes controls and cuts a long token" in {
            def message(mainType: String): String =
                MediaType.init(mainType, "x") match
                    case Result.Failure(e) => e.getMessage
                    case other             => fail(s"expected a failure, got $other")
            assert(message("a\r\nb").contains("a\\r\\nb"))
            val long = message("x" * 300 + " ")
            assert(long.contains("x" * 200 + "..."))
            assert(!long.contains("x" * 201))
        }
    }

    "render" - {
        "writes a token bare, a printable value quoted, and the rest as RFC 8187 in the HTTP style" in {
            assert(mediaTypeOf("text", "plain", "charset" -> "utf-8").render == Result.succeed("text/plain; charset=utf-8"))
            assert(mediaTypeOf("text", "plain", "name" -> "a b \"c\"").render == Result.succeed("text/plain; name=\"a b \\\"c\\\"\""))
            assert(mediaTypeOf("text", "plain", "name" -> "r\u00e9sum\u00e9.pdf").render ==
                Result.succeed("text/plain; name*=UTF-8''r%C3%A9sum%C3%A9.pdf"))
            assert(mediaTypeOf("text", "plain", "name" -> "a\r\nb").render == Result.succeed("text/plain; name*=UTF-8''a%0D%0Ab"))
        }
        "reads back equal" in {
            val types = Seq(
                mediaTypeOf("text", "plain", "charset"            -> "utf-8"),
                mediaTypeOf("text", "plain", "name"               -> "a b \"c\" \\ d"),
                mediaTypeOf("application", "octet-stream", "name" -> "r\u00e9sum\u00e9 (final).pdf", "x" -> "1"),
                mediaTypeOf("text", "plain", "name"               -> "a\r\nb\u0000c")
            )
            types.foreach { t =>
                assert(t.render.flatMap(MediaType.parse(_)) == Result.succeed(t), t.toString)
                assert(t.render(Parameters.Style.Mime).flatMap(MediaType.parse(_)) == Result.succeed(t), t.toString)
            }
            succeed
        }
        "refuses a name holding *, which RFC 2231 reserves" in {
            assert(mediaTypeOf("text", "plain", "a*b" -> "1").render ==
                Result.fail(MimeInvalidParameterException(MimeException.Violation.UnwritableParameterName("a*b"))))
        }
    }

    "Schema" - {
        "round-trips through JSON and refuses invalid parts" in {
            val t = mediaTypeOf("text", "plain", "charset" -> "utf-8")
            assert(Json.decode[MediaType](Json.encode(t)) == Result.succeed(t))
            assert(Json.decode[MediaType]("""{"mainType":"te xt","subType":"plain","parameters":[]}""").isFailure)
            assert(Json.decode[MediaType]("""{"mainType":"text","subType":"plain","parameters":[{"name":"a b","value":"1"}]}""").isFailure)
        }
    }

    "totality" - {
        "10,000 values drawn from the grammar's characters are read without failing" in {
            val alphabet = ";=*'%\"\\()/ \tab09\u00e9\u0000?"
            val random   = new scala.util.Random(0x6b796f)
            val values   = Seq.fill(10000)(Seq.fill(random.nextInt(40))(alphabet(random.nextInt(alphabet.length))).mkString)
            val read     = values.map(value => (MediaType.parse(value), Disposition.parse(value)))
            assert(read.size == 10000)
            assert(read.forall((t, d) => t.forall(_.mainType.nonEmpty) && d.forall(_.kind.nonEmpty)))
        }
        "every type read from 10,000 generated values writes back and reads back equal in both styles, unless a name holds *" in {
            val alphabet = ";=*'%\"\\()/ \tab09\u00e9\u0000?"
            val random   = new scala.util.Random(0x6b796f)
            val values   =
                Seq.fill(10000)("text/plain; a=" + Seq.fill(random.nextInt(40))(alphabet(random.nextInt(alphabet.length))).mkString)
            val types = values.flatMap(v => MediaType.parse(v).toMaybe.toChunk)
            assert(types.size > 9000)
            types.foreach { t =>
                Seq(Parameters.Style.Http, Parameters.Style.Mime).foreach { style =>
                    val back    = t.render(style).flatMap(MediaType.parse(_))
                    val starred = t.parameters.map(_.name).find(_.contains('*')).map(name =>
                        Result.fail(MimeInvalidParameterException(MimeException.Violation.UnwritableParameterName(name)))
                    )
                    assert(back == Result.succeed(t) || starred.contains(back), s"$t: $back")
                }
            }
            succeed
        }
    }

end MediaTypeTest
