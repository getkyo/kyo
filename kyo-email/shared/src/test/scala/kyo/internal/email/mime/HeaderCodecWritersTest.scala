package kyo.internal.email.mime

import kyo.*
import kyo.internal.email.mime.HeaderCodec.DispositionKind
import kyo.internal.email.mime.HeaderCodec.WordContext
import kyo.internal.email.mime.HeaderCodec.WriteFailure

class HeaderCodecWritersTest extends kyo.test.Test[Any]:

    private def written(name: String, units: Chunk[String]): String =
        HeaderCodec.fold(name, units) match
            case Result.Success(field) => field
            case other                 => throw new IllegalStateException(s"$name did not fold: $other")

    private def readBack(field: String): HeaderCodec.Field =
        HeaderCodec.readSection(Span.from((field + "\r\n").getBytes("UTF-8")), 0).fields(0)

    private def octets(line: String): Int = line.getBytes("UTF-8").length

    // Every CRLF is a fold (followed by SP or HTAB) except the last, which ends the field; no other CR or LF; no line past `limit`.
    private def wellFolded(field: String, limit: Int)(using kyo.test.AssertScope): Unit =
        assert(field.endsWith("\r\n"), field)
        val lines = field.dropRight(2).split("\r\n", -1).toSeq
        assert(lines.forall(l => !l.contains('\r') && !l.contains('\n')), field)
        assert(lines.drop(1).forall(l => l.nonEmpty && (l.charAt(0) == ' ' || l.charAt(0) == '\t')), field)
        assert(lines.forall(l => octets(l) <= limit), lines.map(octets).mkString(","))
    end wellFolded

    private def unstructured(text: String): String = written("Subject", HeaderCodec.unstructuredUnits(text))

    // The parameter's segments as the Content-Type writer emits them, without the head and the SP and ; around each unit.
    private def parameterUnits(name: String, value: String): Result[WriteFailure, Chunk[String]] =
        HeaderCodec.contentTypeUnits(EmailLiterals.mediaTypeOf("x", "y", name -> value)).map(_.drop(1).map(_.drop(1).stripSuffix(";")))

    private def segments(name: String, value: String): Chunk[String] =
        parameterUnits(name, value) match
            case Result.Success(s) => s
            case other             => throw new IllegalStateException(s"$name=$value: $other")

    private def contentTypeField(mediaType: Email.MediaType): String =
        HeaderCodec.contentTypeUnits(mediaType) match
            case Result.Success(units) => written("Content-Type", units)
            case other                 => throw new IllegalStateException(s"$mediaType: $other")

    private def readParameter(value: String): Maybe[String] =
        HeaderCodec.contentType(readBack(contentTypeField(EmailLiterals.mediaTypeOf("x", "y", "p" -> value)))).flatMap(_.parameter("p"))

    // Texts drawn from pieces of every kind the writers treat differently.
    private val pieces = Seq(
        "abc",
        "XYZ 019",
        " ",
        "  ",
        "\t",
        "\r",
        "\n",
        "\r\n",
        "\u0000",
        "=?utf-8?q?x?=",
        "=?",
        "?=",
        "_",
        "\"",
        "\\",
        "=",
        "?",
        "(",
        ")",
        ";",
        "%",
        "*",
        "'",
        ".",
        "\u00e9",
        "\u00df",
        "\u6f22\u5b57",
        "\ud83d\ude00",
        "e\u0301",
        "a" * 40,
        "\u00e9" * 30
    )

    private def generated(seed: Int, count: Int)(using Frame): Seq[String] < Sync =
        val sized  = Seq(75, 76, 77, 78, 79, 997, 998, 999).flatMap(n => Seq("a" * n, ("ab " * n).take(n), "\u00e9" * n))
        val quoted = (80 to 400 by 20).map(n => ("quote \"q\" back \\ " * n).take(n))
        Random.withSeed(seed)(Kyo.fill(count)(Random.nextInt(12).map(n => Kyo.fill(n)(Random.nextValue(pieces)))))
            .map(drawn => drawn.map(_.mkString) ++ sized ++ quoted)
    end generated

    "fold" - {
        "an empty value is the name and a colon" in {
            assert(HeaderCodec.fold("Subject", Chunk.empty) == Result.succeed("Subject:\r\n"))
        }
        "units share a line while it stays within 78 octets, and a fold comes before the unit that would pass it" in {
            val units = Chunk("a" * 30, " " + "b" * 30, " " + "c" * 30)
            assert(HeaderCodec.fold("X", units) == Result.succeed(s"X: ${"a" * 30} ${"b" * 30}\r\n ${"c" * 30}\r\n"))
        }
        "a fold may come before the first unit, which then starts the second line" in {
            val unit = "w" * 75
            assert(HeaderCodec.fold("A-Long-Field-Name", Chunk(unit)) == Result.succeed(s"A-Long-Field-Name:\r\n $unit\r\n"))
        }
        "a unit is never broken, and a unit alone may take its line up to 998 octets" in {
            assert(HeaderCodec.fold("X", Chunk("a" * 997)) == Result.succeed(s"X:\r\n ${"a" * 997}\r\n"))
        }
        "a unit that cannot fit in 998 octets on a line of its own fails with LineTooLong, counting octets" in {
            assert(HeaderCodec.fold("X", Chunk("a" * 998)) == Result.fail(WriteFailure.LineTooLong))
            assert(HeaderCodec.fold("X", Chunk("\u00e9" * 499)) == Result.fail(WriteFailure.LineTooLong))
            assert(HeaderCodec.fold("X", Chunk("\u00e9" * 498)).isSuccess)
        }
        "a CR or LF in any unit fails with LineBreakInValue" in {
            Seq("a\rb", "a\nb", "a\r\nb").foreach { unit =>
                assert(HeaderCodec.fold("X", Chunk("ok", " " + unit)) == Result.fail(WriteFailure.LineBreakInValue), unit)
            }
        }
        "the line limit counts UTF-8 octets" in {
            val field = written("X", Chunk("\u00e9" * 30, " " + "\u00e9" * 30))
            assert(field == s"X: ${"\u00e9" * 30}\r\n ${"\u00e9" * 30}\r\n")
        }
    }

    "extra header values" - {
        "are split before each run of white space, and read back as written" in {
            assert(HeaderCodec.rawUnits("a  b\tc") == Chunk("a", "  b", "\tc"))
            val value = ("word " * 40) + "end\t\tx"
            val field = written("X-Extra", HeaderCodec.rawUnits(value))
            wellFolded(field, 78)
            assert(readBack(field).value == value)
        }
        "a CR or LF in the value fails with LineBreakInValue" in {
            assert(HeaderCodec.fold("X-Extra", HeaderCodec.rawUnits("a\r\nBcc: victim@example.com")) ==
                Result.fail(WriteFailure.LineBreakInValue))
        }
    }

    "unstructured text" - {
        "printable ASCII with single or repeated spaces is written as it is" in {
            assert(unstructured("Hello  world") == "Subject: Hello  world\r\n")
            assert(HeaderCodec.unstructuredUnits("a b  c") == Chunk("a", " b", "  c"))
        }
        "the empty text is no units" in {
            assert(unstructured("") == "Subject:\r\n")
        }
        "a space at either end, =?, a TAB, a control or non-ASCII character, or a run of 77 without a space, makes it encoded words" in {
            Seq(" a", "a ", "a =?x?= b", "a\tb", "a\u0000", "caf\u00e9", "a" * 77).foreach { text =>
                assert(HeaderCodec.unstructuredUnits(text).forall(_.trim.startsWith("=?utf-8?")), text)
                assert(HeaderCodec.decodeUnstructured(readBack(unstructured(text)).value) == text, text)
            }
            assert(HeaderCodec.unstructuredUnits("a" * 76) == Chunk("a" * 76))
        }
        "generated texts read back equal, and every line stays within 78 octets" in {
            generated(0x6b796f, 2000).map { texts =>
                val encoded = texts.count(t => HeaderCodec.unstructuredUnits(t).headOption.exists(_.startsWith("=?")))
                texts.foreach { text =>
                    val field = unstructured(text)
                    wellFolded(field, 78)
                    assert(HeaderCodec.decodeUnstructured(readBack(field).value) == text, text)
                }
                assert(encoded > 1000 && encoded < texts.size - 100, s"$encoded of ${texts.size} encoded")
            }
        }
    }

    "encoded words" - {
        "Q when shorter, B when shorter, and Q on a tie" in {
            assert(HeaderCodec.encodedWords("abc", WordContext.Text) == Chunk("=?utf-8?Q?abc?="))
            assert(HeaderCodec.encodedWords("\u6f22\u5b57", WordContext.Text) == Chunk("=?utf-8?B?5ryi5a2X?="))
            assert(HeaderCodec.encodedWords("\u0000a", WordContext.Text) == Chunk("=?utf-8?Q?=00a?="))
        }
        "Q writes SP as _, and =, ?, _ as escapes in text" in {
            assert(HeaderCodec.encodedWords("hello world again=?_.", WordContext.Text) == Chunk("=?utf-8?Q?hello_world_again=3D=3F=5F.?="))
        }
        "in a phrase Q keeps only letters, digits and ! * + - / literal" in {
            assert(HeaderCodec.encodedWords("abcdef.b!*+-/(c)", WordContext.Phrase) == Chunk("=?utf-8?Q?abcdef=2Eb!*+-/=28c=29?="))
        }
        "words are cut at code point boundaries, each at most 75 characters, and after the first each starts with SP" in {
            val text  = "\ud83d\ude00\u00e9a" * 60
            val words = HeaderCodec.encodedWords(text, WordContext.Text)
            assert(words.size > 1)
            assert(words.forall(w => w.trim.length <= 75))
            assert(words.drop(1).forall(_.startsWith(" =?")) && words.head.startsWith("=?"))
            assert(words.forall(w => !HeaderCodec.decodeUnstructured(w.trim).contains('\ufffd')))
            assert(HeaderCodec.decodeUnstructured(words.mkString) == text)
        }
        "each word is as long as fits: joining a piece to the next would pass 75" in {
            val words = HeaderCodec.encodedWords("x" * 200, WordContext.Text)
            assert(words.map(_.trim.length) == Chunk(75, 75, 75, 23))
        }
        "a lone surrogate is written as U+FFFD, in a subject and in a display name" in {
            assert(HeaderCodec.encodedWords("a\ud800b", WordContext.Text) == Chunk("=?utf-8?B?Ye+/vWI=?="))
            assert(HeaderCodec.decodeUnstructured(HeaderCodec.encodedWords("a\ud800b", WordContext.Text).mkString) == "a�b")
            assert(HeaderCodec.phraseUnits("J\udc00") == Chunk("=?utf-8?B?Su+/vQ==?="))
        }
    }

    "display names" - {
        "atoms when the name is atext words separated by single spaces" in {
            assert(HeaderCodec.phraseUnits("Ada Lovelace") == Chunk("Ada", " Lovelace"))
        }
        "an atom or quoted string past 76 octets is written as encoded words instead, so every name folds" in {
            Seq("a" * 77, "a b" + "," * 74).foreach { name =>
                val units = HeaderCodec.phraseUnits(name)
                assert(units.head.startsWith("=?utf-8?"), name)
                assert(HeaderCodec.decodeUnstructured(readBack(written("From", units)).value) == name)
            }
            assert(HeaderCodec.phraseUnits("a" * 76) == Chunk("a" * 76))
        }
        "a quoted string when every character is printable ASCII or SP, with quoted pairs" in {
            assert(HeaderCodec.phraseUnits("Lovelace, Ada \"The\" \\ One") == Chunk("\"Lovelace, Ada \\\"The\\\" \\\\ One\""))
            assert(HeaderCodec.phraseUnits(" padded ") == Chunk("\" padded \""))
        }
        "encoded words otherwise, and for any name holding =?" in {
            Seq("J\u00fcrgen", "a\r\nb", "=?utf-8?q?x?=", "tab\there").foreach { name =>
                val units = HeaderCodec.phraseUnits(name)
                assert(units.head.startsWith("=?utf-8?"), name)
                assert(HeaderCodec.decodeUnstructured(units.mkString) == name, name)
            }
        }
        "generated names read back equal through the reader of their form" in {
            generated(0x70687261, 2000).map { names =>
                val byForm = names.groupBy { name =>
                    val units = HeaderCodec.phraseUnits(name)
                    val field = written("From", units)
                    wellFolded(field, 78)
                    val value = readBack(field).value
                    if units.isEmpty then
                        assert(name.isEmpty && value.isEmpty)
                        "empty"
                    else if units.head.startsWith("=?") then
                        assert(HeaderCodec.decodeUnstructured(value) == name, name)
                        "encoded"
                    else if units.head.startsWith("\"") then
                        assert(HeaderCodec.contentType(readBack(s"Content-Type: x/y; p=$value\r\n")).flatMap(_.parameter("p")) ==
                            Present(name))
                        "quoted"
                    else
                        assert(value == name, name)
                        "atoms"
                    end if
                }
                assert(
                    Seq("encoded", "quoted", "atoms").forall(f => byForm.get(f).exists(_.size >= 20)),
                    byForm.view.mapValues(_.size).toMap.toString
                )
            }
        }
    }

    "parameters" - {
        "a token past 76 octets as a segment is written as quoted continuations" in {
            val split = segments("name", "t" * 100)
            assert(split.size == 2 && split.head.startsWith("name*0=\"") && split.forall(_.length <= 76))
            assert(readParameter("t" * 100) == Present("t" * 100))
        }
        "a token, a quoted string, and the quoted string with its quoted pairs" in {
            assert(segments("charset", "utf-8") == Chunk("charset=utf-8"))
            assert(segments("name", "a b.pdf") == Chunk("name=\"a b.pdf\""))
            assert(segments("name", "say \"hi\" \\o/") == Chunk("name=\"say \\\"hi\\\" \\\\o/\""))
            assert(segments("name", "") == Chunk("name=\"\""))
        }
        "a quoted segment of 76 octets is one segment; one of 77 becomes quoted continuations" in {
            val fits = "a b" + "c" * 66
            assert(segments("name", fits) == Chunk(s"name=\"$fits\"") && s"name=\"$fits\"".length == 76)
            val longer = fits + "d"
            val split  = segments("name", longer)
            assert(split.size == 2 && split.forall(_.length <= 76))
            assert(split.head.startsWith("name*0=\"") && split(1).startsWith("name*1=\""))
            assert(readParameter(longer) == Present(longer))
        }
        "quoted continuations are never cut inside a quoted pair" in {
            val value = "\"\\" * 60
            val split = segments("name", value)
            assert(split.size > 1 && split.forall(_.length <= 76))
            split.foreach { segment =>
                val content = segment.substring(segment.indexOf("=\"") + 2, segment.length - 1)
                assert(segment.endsWith("\"") && content.reverse.takeWhile(_ == '\\').length % 2 == 0, segment)
            }
            assert(readParameter(value) == Present(value))
        }
        "non-ASCII, control characters and =? go to RFC 2231, with %XX for every octet outside attribute-char" in {
            assert(segments("name", "caf\u00e9.pdf") == Chunk("name*=utf-8''caf%C3%A9.pdf"))
            assert(segments("name", "a\r\n\tb") == Chunk("name*=utf-8''a%0D%0A%09b"))
            assert(segments("name", "=?utf-8?q?x?=") == Chunk("name*=utf-8''%3D%3Futf-8%3Fq%3Fx%3F%3D"))
        }
        "a lone surrogate in a parameter value is written as U+FFFD" in {
            assert(segments("name", "a\ud800.pdf") == Chunk("name*=utf-8''a%EF%BF%BD.pdf"))
            assert(readParameter("a\ud800.pdf") == Present("a�.pdf"))
        }
        "a long RFC 2231 value becomes encoded continuations, never cut inside a %XX" in {
            val value = "\u00e9" * 60
            val split = segments("name", value)
            assert(split.size > 1 && split.forall(_.length <= 76))
            assert(split.head.startsWith("name*0*=utf-8''") && split(1).startsWith("name*1*=%"))
            assert(split.forall(s => !s.matches(".*%[0-9A-F]?$")))
            assert(readParameter(value) == Present(value))
        }
        "a name holding * is unwritable whatever its value, a token and a quoted string included" in {
            assert(parameterUnits("a*b", "1") == Result.fail(WriteFailure.UnwritableParameterName("a*b")))
            assert(parameterUnits("a*b", "x y") == Result.fail(WriteFailure.UnwritableParameterName("a*b")))
            assert(parameterUnits("a*b", "caf\u00e9") == Result.fail(WriteFailure.UnwritableParameterName("a*b")))
            assert(parameterUnits("a*1", "x") == Result.fail(WriteFailure.UnwritableParameterName("a*1")))
            assert(parameterUnits("x*", "x") == Result.fail(WriteFailure.UnwritableParameterName("x*")))
            assert(parameterUnits("a*b", "c" * 80) == Result.fail(WriteFailure.UnwritableParameterName("a*b")))
        }
        "generated values read back equal through the parameter reader, and every line stays within 78 octets" in {
            generated(0x706172, 2000).map { values =>
                val forms = values.groupBy { value =>
                    val field =
                        contentTypeField(EmailLiterals.mediaTypeOf("application", "octet-stream", "p" -> value, "q" -> ("q" + value)))
                    wellFolded(field, 78)
                    val read = HeaderCodec.contentType(readBack(field))
                    assert(read.flatMap(_.parameter("p")) == Present(value), value)
                    assert(read.flatMap(_.parameter("q")) == Present("q" + value), value)
                    segments("p", value).head.takeWhile(c => c != '=')
                }
                assert(
                    Seq("p", "p*0", "p*", "p*0*").forall(f => forms.get(f).exists(_.size >= 20)),
                    forms.view.mapValues(_.size).toMap.toString
                )
            }
        }
    }

    "Content-Type and Content-Disposition" - {
        "the head, then each segment as a unit, with ; ending the unit before it" in {
            assert(HeaderCodec.contentTypeUnits(EmailLiterals.mediaTypeOf("text", "plain")) == Result.succeed(Chunk("text/plain")))
            assert(HeaderCodec.contentTypeUnits(EmailLiterals.mediaTypeOf("text", "plain", "charset" -> "utf-8", "format" -> "flowed")) ==
                Result.succeed(Chunk("text/plain;", " charset=utf-8;", " format=flowed")))
            assert(HeaderCodec.dispositionUnits(DispositionKind.Attachment, Chunk("filename" -> "r\u00e9sum\u00e9.pdf")) ==
                Result.succeed(Chunk("attachment;", " filename*=utf-8''r%C3%A9sum%C3%A9.pdf")))
        }
        "a disposition of each writable kind reads back equal" in {
            val parameters = Chunk("filename" -> ("\u00e9" * 50 + ".pdf"), "size" -> "12")
            Seq[DispositionKind.Inline.type | DispositionKind.Attachment.type](DispositionKind.Inline, DispositionKind.Attachment).foreach {
                kind =>
                    val units = HeaderCodec.dispositionUnits(kind, parameters) match
                        case Result.Success(u) => u
                        case other             => throw new IllegalStateException(other.toString)
                    val field = written("Content-Disposition", units)
                    wellFolded(field, 78)
                    assert(
                        HeaderCodec.contentDisposition(readBack(field)) == Present(HeaderCodec.Disposition(kind, parameters)),
                        kind.label
                    )
            }
        }
    }

    "injection" - {
        "a Subject, a display name and a parameter value holding CR, LF, CRLF and a lone HTAB: the field's only CRLFs are folds and its end" in {
            val hostile = "x\ry\nz\r\nBcc: victim@example.com\tw"
            val subject = unstructured(hostile)
            wellFolded(subject, 78)
            assert(HeaderCodec.decodeUnstructured(readBack(subject).value) == hostile)
            val name = written("From", HeaderCodec.phraseUnits(hostile))
            wellFolded(name, 78)
            assert(HeaderCodec.decodeUnstructured(readBack(name).value) == hostile)
            val parameter = contentTypeField(EmailLiterals.mediaTypeOf("text", "plain", "name" -> hostile))
            wellFolded(parameter, 78)
            assert(HeaderCodec.contentType(readBack(parameter)).flatMap(_.parameter("name")) == Present(hostile))
            Seq(subject, name, parameter).foreach { field =>
                assert(HeaderCodec.readSection(Span.from((field + "\r\n").getBytes("UTF-8")), 0).fields.size == 1, field)
            }
        }
    }

end HeaderCodecWritersTest
