package kyo

import kyo.EmailLiterals.mediaTypeOf
import kyo.MimeException.Violation

class EmailMediaTypeTest extends kyo.test.Test[Any]:

    private def violationOf(checked: Result[MimeInvalidMediaTypeException, Email.MediaType]): Maybe[Violation] =
        checked match
            case Result.Failure(ex) => Present(ex.violation)
            case _                  => Absent

    "is kyo-mime's MediaType, so a value built by either module is the other's" in {
        val fromMime: kyo.mime.MediaType = EmailLiterals.valid(kyo.mime.MediaType.init("Text", "Plain", "Charset" -> "UTF-8"))
        val email: Email.MediaType       = fromMime
        assert(email == mediaTypeOf("text", "plain", "charset" -> "UTF-8"))
        assert(Email.MediaType.parse("text/plain; charset=UTF-8") == Result.succeed(email))
    }

    "keeps every parameter in order" in {
        val invite = mediaTypeOf("text", "calendar", "method" -> "REQUEST", "charset" -> "UTF-8")
        assert(invite.baseType == "text/calendar")
        assert(invite.parameters.map(p => (p.name, p.value)) == Chunk("method" -> "REQUEST", "charset" -> "UTF-8"))
    }

    "lowercases type, subtype and parameter names, and keeps value case" in {
        val t = mediaTypeOf("Multipart", "Mixed", "Boundary" -> "AbC")
        assert(t.baseType == "multipart/mixed")
        assert(t.parameters.map(p => (p.name, p.value)) == Chunk("boundary" -> "AbC"))
        assert(t == mediaTypeOf("multipart", "mixed", "boundary" -> "AbC"))
        assert(t != mediaTypeOf("multipart", "mixed", "boundary" -> "abc"))
    }

    "looks parameters up case-insensitively" in {
        val t = mediaTypeOf("text", "plain", "charset" -> "iso-8859-1")
        assert(t.parameter("CHARSET") == Present("iso-8859-1"))
        assert(t.charset == Present("iso-8859-1"))
        assert(t.parameter("format") == Absent)
    }

    "accepts non-ASCII parameter values, which the renderer encodes" in {
        assert(mediaTypeOf("application", "pdf", "name" -> "résumé.pdf").parameter("name") == Present("résumé.pdf"))
    }

    "keeps a parameter value holding control characters, as a received value can: the renderer writes it as RFC 2231 %XX" in {
        val value = "a\tb\u0000c\r\nBcc: victim@example.com"
        assert(mediaTypeOf("text", "plain", "name" -> value).parameter("name") == Present(value))
    }

    "init" - {
        "fails with MimeInvalidMediaTypeException on a type, subtype or parameter name that is not a token" in {
            assert(violationOf(Email.MediaType.init("", "plain")) == Present(Violation.NotAToken("type", "")))
            assert(violationOf(Email.MediaType.init("text/html", "plain")) == Present(Violation.NotAToken("type", "text/html")))
            assert(violationOf(Email.MediaType.init("text", "pl ain")) == Present(Violation.NotAToken("subtype", "pl ain")))
            assert(violationOf(Email.MediaType.init("text", "plain", "char=set" -> "x")) ==
                Present(Violation.NotAToken("parameter name", "char=set")))
        }
        "fails on a parameter named twice, in any casing, since a reader keeps only the first" in {
            assert(violationOf(Email.MediaType.init("text", "plain", "charset" -> "a", "CHARSET" -> "b")) ==
                Present(Violation.DuplicateParameter("charset")))
        }
        "removes white space at the end of a boundary, as a reader does, and keeps it at the start and in other parameters" in {
            val t = mediaTypeOf("multipart", "mixed", "boundary" -> " b \t ", "name" -> "n ")
            assert(t.parameters.map(p => (p.name, p.value)) == Chunk("boundary" -> " b", "name" -> "n "))
        }
        "renders the offending value escaped" in {
            Email.MediaType.init("te\nxt", "plain") match
                case Result.Failure(ex) => assert(ex.getMessage.contains("""type te\nxt is not a token"""))
                case other              => fail(s"expected a failure, got $other")
        }
    }

end EmailMediaTypeTest
