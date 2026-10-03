package kyo.mime

import kyo.*

class DispositionTest extends kyo.test.Test[Any]:

    private def disposition(value: String): Maybe[Disposition] = Disposition.parse(value).toMaybe

    // A literal the test knows to be valid; a violation here is a defect in the test itself.
    private def dispositionOf(kind: String, parameters: (String, String)*): Disposition =
        Disposition.init(kind, parameters*) match
            case Result.Success(d) => d
            case other             => throw new AssertionError(s"the test literal is not a disposition: $other")

    "parse" - {
        "RFC 2183 3: inline, and attachment with a file name, a quoted date and a dangling ;" in {
            assert(disposition("inline") == Present(dispositionOf("inline")))
            assert(disposition("attachment; filename=genome.jpeg;   modification-date=\"Wed, 12 Feb 1997 16:29:51 -0500\";") ==
                Present(dispositionOf("attachment", "filename" -> "genome.jpeg", "modification-date" -> "Wed, 12 Feb 1997 16:29:51 -0500")))
        }
        "the type is any token, lowercased" in {
            assert(disposition("X-Custom; a=1") == Present(dispositionOf("x-custom", "a" -> "1")))
            assert(disposition("ATTACHMENT").map(_.kind) == Present("attachment"))
            assert(disposition("InLine").map(_.kind) == Present("inline"))
            assert(disposition("form-data; name=\"f\"; filename=\"a.txt\"").map(d => (d.kind, d.name, d.filename)) ==
                Present(("form-data", Present("f"), Present("a.txt"))))
        }
        "a type that is not a token, no type, or other text after it, is no disposition" in {
            Seq("", "; filename=a", "inline/x", "(c)", "á", "inlıne").foreach { value =>
                assert(
                    Disposition.parse(value) ==
                        Result.fail(MimeInvalidDispositionException(MimeException.Violation.NotWellFormed(value))),
                    value
                )
            }
        }
        "RFC 6266: filename* wins over filename, and decodes as UTF-8" in {
            assert(disposition("attachment; filename=\"EURO rates\"; filename*=utf-8''%e2%82%ac%20rates").flatMap(_.filename) ==
                Present("€ rates"))
            assert(disposition("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf").flatMap(_.filename) ==
                Present("résumé.pdf"))
        }
        "a form-data part written with filename before name gives each parameter its own value" in {
            val d = disposition("form-data; filename=\"a.txt\"; name=\"f\"")
            assert(d.flatMap(_.name) == Present("f"))
            assert(d.flatMap(_.filename) == Present("a.txt"))
        }
        "a quoted pair in a file name is resolved, so a quote cannot end the value early" in {
            assert(disposition("form-data; name=\"a\\\"; b=c\"").flatMap(_.name) == Present("a\"; b=c"))
        }
    }

    "parseFormData" - {
        "reads the name and file name a browser writes, a backslash staying a backslash" in {
            assert(Disposition.parseFormData("form-data; name=\"f\"; filename=\"C:\\dir\\a.txt\"") ==
                Result.succeed(dispositionOf("form-data", "name" -> "f", "filename" -> "C:\\dir\\a.txt")))
            assert(Disposition.parseFormData("form-data; name=\"f\"") == Result.succeed(dispositionOf("form-data", "name" -> "f")))
            assert(Disposition.parseFormData("form-data; name=\"\"") == Result.succeed(dispositionOf("form-data", "name" -> "")))
        }
        "decodes %0A, %0D and %22 and nothing else" in {
            assert(Disposition.parseFormData("form-data; name=\"a%22b%0D%0Ac\"; filename=\"%41%0a%25.txt\"") ==
                Result.succeed(dispositionOf("form-data", "name" -> "a\"b\r\nc", "filename" -> "%41%0a%25.txt")))
        }
        "reads back what the FormData style writes, hostile values included" in {
            Seq("a\"b", "a\r\nX-Injected: yes", "C:\\dir\\a.txt", "\\", "€ rates.csv", "", "a;b=c").foreach { value =>
                val written = dispositionOf("form-data", "name" -> "f", "filename" -> value).render(Parameters.Style.FormData)
                written match
                    case Result.Success(line) =>
                        assert(!line.contains('\r') && !line.contains('\n'), s"$value: $line")
                        assert(Disposition.parseFormData(line).map(_.filename) == Result.succeed(Present(value)), s"$value: $line")
                    case other => fail(s"$value: expected a rendered line, got $other")
                end match
            }
            succeed
        }
        "a literal %22 reads back as a quote, as it does from a browser" in {
            assert(dispositionOf("form-data", "name" -> "%22").render(Parameters.Style.FormData) ==
                Result.succeed("form-data; name=\"%22\""))
            assert(Disposition.parseFormData("form-data; name=\"%22\"").map(_.name) == Result.succeed(Present("\"")))
        }
        "anything but that exact shape is no form-data disposition" in {
            Seq(
                "form-data; name=f",
                "Form-Data; name=\"f\"",
                "attachment; name=\"f\"",
                "form-data;name=\"f\"",
                "form-data; name=\"f",
                "form-data; name=\"a\nb\"",
                "form-data; name=\"a\rb\"",
                "form-data; name=\"f\"; filename*=UTF-8''a.txt",
                "form-data; name=\"f\"; filename=\"a\nb\"",
                "form-data; name=\"f\" ",
                "form-data; name=\"f\"; filename=\"a\"; x=\"y\"",
                ""
            ).foreach { text =>
                assert(
                    Disposition.parseFormData(text) ==
                        Result.fail(MimeInvalidDispositionException(MimeException.Violation.NotWellFormed(text))),
                    text
                )
            }
            succeed
        }
    }

    "construction" - {
        "checks the type and the parameters like a media type" in {
            assert(Disposition.init("in line").map(_.kind) ==
                Result.fail(MimeInvalidDispositionException(MimeException.Violation.NotAToken("disposition type", "in line"))))
            assert(Disposition.init("attachment", "filename" -> "a", "FILENAME" -> "b").map(_.kind) ==
                Result.fail(MimeInvalidDispositionException(MimeException.Violation.DuplicateParameter("filename"))))
            assert(Disposition.init("attachment", "náme" -> "1").map(_.kind) ==
                Result.fail(MimeInvalidDispositionException(MimeException.Violation.NotAToken("parameter name", "náme"))))
        }
    }

    "render" - {
        "writes a download name as a quoted string, and a non-ASCII or control one as filename* (RFC 8187)" in {
            assert(dispositionOf("attachment", "filename" -> "report.pdf").render == Result.succeed("attachment; filename=report.pdf"))
            assert(dispositionOf("attachment", "filename" -> "my report.pdf").render ==
                Result.succeed("attachment; filename=\"my report.pdf\""))
            assert(dispositionOf("attachment", "filename" -> "€ rates.csv").render ==
                Result.succeed("attachment; filename*=UTF-8''%E2%82%AC%20rates.csv"))
            assert(dispositionOf("attachment", "filename" -> "€ rates.csv").render(Parameters.Style.Mime) ==
                Result.succeed("attachment; filename*=utf-8''%E2%82%AC%20rates.csv"))
            assert(dispositionOf("form-data", "name" -> "f", "filename" -> "a\"\r\nX-Injected: yes").render ==
                Result.succeed("form-data; name=f; filename*=UTF-8''a%22%0D%0AX-Injected%3A%20yes"))
        }
        "never lets a quote, CR or LF through unescaped, in either style" in {
            val hostile = Seq("a\"b", "a\rb", "a\nb", "a\r\nX: y", "\"", "\\")
            hostile.foreach { value =>
                Seq(Parameters.Style.Http, Parameters.Style.Mime).foreach { style =>
                    dispositionOf("form-data", "filename" -> value).render(style) match
                        case Result.Success(line) =>
                            assert(!line.contains('\r') && !line.contains('\n'), s"$value: $line")
                            assert(Disposition.parse(line).map(_.filename) == Result.succeed(Present(value)), s"$value: $line")
                        case other => fail(s"$value: expected a rendered line, got $other")
                }
            }
            succeed
        }
        "in the MIME style a long file name becomes RFC 2231 continuations that read back whole" in {
            val name = "a very long file name with spaces that does not fit on one line of seventy-six octets.pdf"
            dispositionOf("attachment", "filename" -> name).render(Parameters.Style.Mime) match
                case Result.Success(line) =>
                    assert(line.contains("filename*0=") && line.contains("filename*1="))
                    assert(Disposition.parse(line).map(_.filename) == Result.succeed(Present(name)))
                case other => fail(s"expected a rendered line, got $other")
            end match
        }
    }

    "Schema" - {
        "round-trips through JSON" in {
            val d = dispositionOf("attachment", "filename" -> "résumé.pdf")
            assert(Json.decode[Disposition](Json.encode(d)) == Result.succeed(d))
            assert(Json.decode[Disposition]("""{"kind":"in line","parameters":[]}""").isFailure)
        }
        "a rejected disposition names the decoding caller's Frame" in {
            Json.decode[Disposition]("""{"kind":"in line","parameters":[]}""") match
                case Result.Failure(e: ConstructorRejectedException) =>
                    e.rejection match
                        case leaf: MimeException => assert(leaf.frame == e.frame, s"rejected at ${leaf.frame}, decoded at ${e.frame}")
                        case other               => fail(s"expected the constructor's own failure, got $other")
                case other => fail(s"expected a ConstructorRejectedException, got $other")
        }
    }

end DispositionTest
