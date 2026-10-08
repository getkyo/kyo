package kyo.internal.email.mime

import kyo.*
import kyo.internal.email.mime.TransferEncoding.Kind

class TransferEncodingTest extends kyo.test.Test[Any]:

    private def octets(values: Int*): Span[Byte] = Span.from(values.map(_.toByte).toArray)
    private def ascii(s: String): Span[Byte]     = Span.from(s.map(_.toByte).toArray)
    private def text(bytes: Span[Byte]): String  = bytes.toArray.map(b => (b & 0xff).toChar).mkString
    private def hex(bytes: Span[Byte]): String   = bytes.toArray.map(b => f"${b & 0xff}%02X").mkString(" ")

    private def base64(input: Span[Byte]): TransferEncoding.Decoded =
        EmailLiterals.valid(TransferEncoding.decodeBase64(input, 0, input.size))

    private def decode(kind: Kind, input: Span[Byte], from: Int, until: Int): TransferEncoding.Decoded =
        EmailLiterals.valid(TransferEncoding.decode(kind, input, from, until))
    private def qp(input: Span[Byte]): Span[Byte] = TransferEncoding.decodeQuotedPrintable(input, 0, input.size)

    private def decodes(input: Span[Byte], expected: Span[Byte], truncated: Boolean = false)(using kyo.test.AssertScope): Unit =
        val decoded = base64(input)
        assert(hex(decoded.content) == hex(expected), s"${text(input)}: ${hex(decoded.content)}, expected ${hex(expected)}")
        assert(decoded.truncated == truncated, s"${text(input)}: truncated ${decoded.truncated}")
    end decodes

    private def lines(encoded: Span[Byte]): Seq[String] = text(encoded).split("\r\n", -1).toSeq

    // Octets from a fixed, deterministic sequence that visits every value.
    private def pattern(length: Int): Span[Byte] = Span.from(Array.tabulate(length)(i => ((i * 37 + 11) % 256).toByte))

    "labels" - {
        "the five encodings of RFC 2045 section 6.1, in any ASCII case and with surrounding white space" in {
            assert(TransferEncoding.resolve("base64") == Present(Kind.Base64))
            assert(TransferEncoding.resolve("Base64") == Present(Kind.Base64))
            assert(TransferEncoding.resolve("BASE64") == Present(Kind.Base64))
            assert(TransferEncoding.resolve("bAsE64") == Present(Kind.Base64))
            assert(TransferEncoding.resolve(" Quoted-Printable\t") == Present(Kind.QuotedPrintable))
            assert(TransferEncoding.resolve("7BIT") == Present(Kind.SevenBit))
            assert(TransferEncoding.resolve("8bit") == Present(Kind.EightBit))
            assert(TransferEncoding.resolve("Binary") == Present(Kind.Binary))
        }
        "any other label is unknown, including one that matches only through non-ASCII folding" in {
            assert(TransferEncoding.resolve("x-uuencode") == Absent)
            assert(TransferEncoding.resolve("") == Absent)
            assert(TransferEncoding.resolve("b\u0131nary") == Absent)
            assert(TransferEncoding.resolve("base 64") == Absent)
        }
        "only SP and HTAB around the label are ignored; any other control character makes it unknown" in {
            assert(TransferEncoding.resolve(" \t base64 \t ") == Present(Kind.Base64))
            assert(TransferEncoding.resolve("\u0000base64") == Absent)
            assert(TransferEncoding.resolve("base64\u000b") == Absent)
            assert(TransferEncoding.resolve("\u000cbase64") == Absent)
            assert(TransferEncoding.resolve("base64\r\n") == Absent)
        }
    }

    "decode and encode by kind" - {
        // Decodes differently as base64 (`SGk=` is "Hi", `3D` one more octet) and as quoted-printable (`=3D` is `=`), and is not the
        // identity.
        val input = ascii("xxSGk=3Dyy")
        "Base64 goes to the base64 decoder, carrying the truncated flag" in {
            val decoded = decode(Kind.Base64, input, 2, 6)
            assert(text(decoded.content) == "Hi" && !decoded.truncated)
            assert(text(decode(Kind.Base64, input, 2, 8).content) == "Hi\u00dc")
            val truncated = decode(Kind.Base64, ascii("QUJDR"), 0, 5)
            assert(text(truncated.content) == "ABC" && truncated.truncated)
        }
        "QuotedPrintable goes to the quoted-printable decoder" in {
            val decoded = decode(Kind.QuotedPrintable, input, 2, 8)
            assert(text(decoded.content) == "SGk=" && !decoded.truncated)
        }
        "encode by kind matches the encoder of that kind, and differs between the two" in {
            val content = ascii("caf\u00e9 = x")
            assert(text(TransferEncoding.encode(Kind.Base64, content)) == text(TransferEncoding.encodeBase64(content)))
            assert(text(TransferEncoding.encode(Kind.QuotedPrintable, content)) == "caf=E9 =3D x")
            assert(text(TransferEncoding.encode(Kind.Base64, content)) != text(content))
        }
        "7bit, 8bit and binary decode to the range as it is and encode to the content as it is" in {
            Seq(Kind.SevenBit, Kind.EightBit, Kind.Binary).foreach { kind =>
                val decoded = decode(kind, input, 2, 8)
                assert(text(decoded.content) == "SGk=3D" && !decoded.truncated, kind.label)
                assert(text(TransferEncoding.encode(kind, ascii("café = x"))) == "café = x", kind.label)
            }
        }
    }

    "Decoded" - {
        "equal content in different arrays, and the same flag, are equal with equal hashes; a different flag or content is not" in {
            val a = TransferEncoding.Decoded(ascii("abc"), truncated = false)
            val b = TransferEncoding.Decoded(ascii("abc"), truncated = false)
            assert(!a.content.toArray.eq(b.content.toArray))
            assert(a == b && a.hashCode == b.hashCode)
            assert(a != TransferEncoding.Decoded(ascii("abc"), truncated = true))
            assert(a != TransferEncoding.Decoded(ascii("abd"), truncated = false))
            assert(a != TransferEncoding.Decoded(ascii("ab"), truncated = false))
        }
    }

    "identity encodings" - {
        "decode copies the range, and encode returns the content" in {
            val input = ascii("headers\r\n\r\nbody \u00ff\r\n")
            Kind.values.filterNot(k => k == Kind.Base64 || k == Kind.QuotedPrintable).foreach { kind =>
                val decoded = decode(kind, input, 11, input.size)
                assert(text(decoded.content) == "body \u00ff\r\n" && !decoded.truncated)
                assert(TransferEncoding.encode(kind, input).is(input))
            }
            succeed
        }
    }

    "base64 decoding" - {
        "the inputs of the kyo-data Base64 probe, through the MIME layer" in {
            decodes(ascii("QUJD"), ascii("ABC"))
            decodes(ascii("QUJDREVG"), ascii("ABCDEF"))
            decodes(ascii("QUJD\r\nREVG"), ascii("ABCDEF"))
            decodes(ascii("QUJD\nREVG"), ascii("ABCDEF"))
            decodes(ascii("QUJD REVG"), ascii("ABCDEF"))
            decodes(ascii("QUJD\tREVG"), ascii("ABCDEF"))
            decodes(ascii("QUJD*REVG"), ascii("ABCDEF"))
            decodes(octets('Q', 'U', 'J', 'D', 0xe9, 'R', 'E', 'V', 'G'), ascii("ABCDEF"))
            decodes(ascii("QQ=="), ascii("A"))
            decodes(ascii("QQ==QUJD"), ascii("AABC"))
            decodes(ascii("QUI=QUJD"), ascii("ABABC"))
            decodes(ascii("QQ="), ascii("A"))
            decodes(ascii("QQ"), ascii("A"))
            decodes(ascii("QUI"), ascii("AB"))
            decodes(ascii("Q"), Span.empty[Byte], truncated = true)
            decodes(ascii("Q==="), Span.empty[Byte], truncated = true)
            decodes(ascii("=QUJ"), ascii("AB"))
            decodes(ascii("QUJD===="), ascii("ABC"))
            decodes(ascii("QR=="), ascii("A"))
            decodes(ascii("QUJD\r\n"), ascii("ABC"))
        }
        "characters outside the alphabet are ignored (RFC 2045 section 6.8): line breaks, lines longer than 76, controls, octets from 0x80" in {
            val encoded = "SGVsbG8sIFdvcmxkIQ=="
            decodes(ascii(encoded.grouped(3).mkString("\r\n")), ascii("Hello, World!"))
            decodes(ascii(encoded.flatMap(c => s"$c\u0000!\u007f-")), ascii("Hello, World!"))
            decodes(ascii(Seq.fill(40)("QUJD").mkString), ascii("ABC" * 40))
        }
        "padding in the middle starts a new segment instead of ending the data" in {
            decodes(ascii("QQ==\r\nQg=="), ascii("AB"))
            decodes(ascii("QUJD=QUJD"), ascii("ABCABC"))
        }
        "missing padding is supplied" in {
            decodes(ascii("SGk"), ascii("Hi"))
            decodes(ascii("SA"), ascii("H"))
        }
        "an = with no data before it, and a run of =, contribute nothing" in {
            decodes(ascii("="), Span.empty[Byte])
            decodes(ascii("===QUJD===="), ascii("ABC"))
        }
        "non-zero bits after the last octet are ignored" in {
            decodes(ascii("QR=="), ascii("A"))
            decodes(ascii("QUJ="), ascii("AB"))
            decodes(ascii("QUK="), ascii("AB"))
        }
        "a segment of 4k+1 characters is truncated: its last character is dropped, the rest decodes, and the result says so" in {
            decodes(ascii("QUJDR"), ascii("ABC"), truncated = true)
            decodes(ascii("QUJDR=QUJD"), ascii("ABCABC"), truncated = true)
            decodes(ascii("QUJDR=\r\nQUJD"), ascii("ABCABC"), truncated = true)
        }
        "an empty body decodes to nothing" in {
            decodes(Span.empty[Byte], Span.empty[Byte])
            decodes(ascii("\r\n \r\n"), Span.empty[Byte])
        }
        "only the given range is decoded" in {
            val input   = ascii("xxQUJDyy")
            val decoded = EmailLiterals.valid(TransferEncoding.decodeBase64(input, 2, 6))
            assert(text(decoded.content) == "ABC")
        }
        "a 25 MB body decodes whole" in {
            val content = pattern(19 * 1000 * 1000)
            val encoded = TransferEncoding.encodeBase64(content)
            assert(encoded.size > 25 * 1000 * 1000)
            assert(base64(encoded).content.is(content) && !base64(encoded).truncated)
        }
        "data ending just before, at, and just after a piece boundary decodes exactly, as does a segment starting at one" in {
            val pieceOctets = TransferEncoding.PieceSize / 4 * 3
            Seq(pieceOctets - 3, pieceOctets - 1, pieceOctets, pieceOctets + 1, pieceOctets + 2, pieceOctets + 3).foreach { size =>
                val content = pattern(size)
                val decoded = base64(TransferEncoding.encodeBase64(content))
                assert(decoded.content.is(content) && !decoded.truncated, s"$size octets")
            }
            val whole = TransferEncoding.encodeBase64(pattern(pieceOctets))
            decodes(Span.from(whole.toArray ++ ascii("=QUJD").toArray), Span.from(pattern(pieceOctets).toArray ++ ascii("ABC").toArray))
            decodes(
                Span.from(whole.toArray ++ ascii("Q").toArray),
                pattern(pieceOctets),
                truncated = true
            )
        }
    }

    "base64 encoding" - {
        "lines of 76 characters separated by CRLF, no line break after the last" in {
            val encoded = lines(TransferEncoding.encodeBase64(pattern(1000)))
            assert(encoded.init.forall(_.length == 76))
            assert(encoded.last.nonEmpty && encoded.last.length <= 76)
            assert(encoded.mkString == Base64.encode(pattern(1000)))
        }
        "57 octets fill one line exactly, and 58 start a second" in {
            assert(lines(TransferEncoding.encodeBase64(pattern(57))) == Seq(Base64.encode(pattern(57))))
            assert(lines(TransferEncoding.encodeBase64(pattern(58))).map(_.length) == Seq(76, 4))
        }
        "empty content encodes to nothing" in {
            assert(TransferEncoding.encodeBase64(Span.empty[Byte]).isEmpty)
        }
    }

    "quoted-printable decoding" - {
        "=XX is the octet, with hexadecimal digits in either case" in {
            assert(hex(qp(ascii("=3D=0C=e9=Fa"))) == "3D 0C E9 FA")
            assert(text(qp(ascii("a=3db"))) == "a=b")
        }
        "= at the end of a line is a soft line break, also with spaces and tabs after it and after a bare LF" in {
            assert(text(qp(ascii("ab=\r\ncd"))) == "abcd")
            assert(text(qp(ascii("ab= \t \r\ncd"))) == "abcd")
            assert(text(qp(ascii("ab=\ncd"))) == "abcd")
            assert(text(qp(ascii("ab = \r\ncd"))) == "ab cd")
        }
        "= as the last octet of the content, with or without white space after it, is a soft line break" in {
            assert(text(qp(ascii("last line="))) == "last line")
            assert(text(qp(ascii("last line=  "))) == "last line")
        }
        "= followed by anything else is kept with what follows, including = as the second-to-last octet" in {
            assert(text(qp(ascii("a=G1b"))) == "a=G1b")
            assert(text(qp(ascii("a=\u00e9b"))) == "a=\u00e9b")
            assert(text(qp(ascii("a=4"))) == "a=4")
            assert(text(qp(ascii("a==3D"))) == "a==")
            assert(text(qp(ascii("100% =)"))) == "100% =)")
        }
        "trailing spaces and tabs on a line are deleted" in {
            assert(text(qp(ascii("ab  \r\ncd\t\r\nef \t"))) == "ab\r\ncd\r\nef")
            assert(text(qp(ascii("ab=20\r\n"))) == "ab \r\n")
        }
        "a hard line break is CRLF in the output, whether the input used CRLF or LF" in {
            assert(text(qp(ascii("a\r\nb\nc\n"))) == "a\r\nb\r\nc\r\n")
        }
        "a range inside the input decodes as the parser hands it over" in {
            val input = ascii("head\r\nab=3Dcd\r\nef=\r\n--b\r\nx=41=4")
            // Starting in the middle of a line.
            assert(text(TransferEncoding.decodeQuotedPrintable(input, 8, 15)) == "=cd\r\n")
            // Starting at an LF whose CR lies before the range.
            assert(text(TransferEncoding.decodeQuotedPrintable(input, 5, 8)) == "\r\nab")
            // Ending on `=` with its line end outside the range: a soft break, as a part before a delimiter reaches the decoder.
            assert(text(TransferEncoding.decodeQuotedPrintable(input, 15, 18)) == "ef")
            // Ending between `=` and its two digits, and after a complete `=41` followed by `=4`.
            assert(text(TransferEncoding.decodeQuotedPrintable(input, 25, 28)) == "x=4")
            assert(text(TransferEncoding.decodeQuotedPrintable(input, 25, 31)) == "xA=4")
        }
        "a CR that is not part of a line end is kept" in {
            assert(hex(qp(ascii("a\rb"))) == "61 0D 62")
        }
        "lines longer than 76 characters, controls and octets above 126 decode as they are" in {
            val long = "x" * 200
            assert(text(qp(ascii(long))) == long)
            assert(hex(qp(octets(0x01, 0x7f, 0xe9, 0xff))) == "01 7F E9 FF")
        }
        "the RFC 2045 section 6.7 example decodes to its raw line" in {
            val encoded = Seq("Now's the time =", "for all folk to come=", " to the aid of their country.")
            assert(text(qp(ascii(encoded.mkString("\r\n")))) == "Now's the time for all folk to come to the aid of their country.")
        }
    }

    "quoted-printable encoding" - {
        "= and every octet outside printable ASCII, space and tab are =XX in upper case (rules 1 and 2)" in {
            assert(text(TransferEncoding.encodeQuotedPrintable(octets('a', '=', 0x0c, 0xe9, 0xff, '~', '!'))) == "a=3D=0C=E9=FF~!")
        }
        "a space or tab before a line end, or at the end of the content, is encoded (rule 3)" in {
            assert(text(TransferEncoding.encodeQuotedPrintable(ascii("a b \r\nc\t"))) == "a b=20\r\nc=09")
        }
        "CRLF is a hard line break; a lone CR or LF is =0D or =0A (rule 4)" in {
            assert(text(TransferEncoding.encodeQuotedPrintable(ascii("a\r\nb\nc\rd"))) == "a\r\nb=0Ac=0Dd")
        }
        "lines are at most 76 characters, soft breaks included, and never split =XX (rule 5)" in {
            val plain = TransferEncoding.encodeQuotedPrintable(ascii("a" * 200))
            assert(lines(plain).map(_.length) == Seq(76, 76, 50))
            assert(lines(plain).init.forall(_.endsWith("=")))
            val escaped = lines(TransferEncoding.encodeQuotedPrintable(Span.from(Array.fill(80)(0xe9.toByte))))
            assert(escaped.forall(_.length <= 76))
            assert(escaped.forall(l => l.stripSuffix("=").grouped(3).forall(_ == "=E9")))
        }
        "a line of exactly 76 characters needs no soft break; 77 does" in {
            assert(lines(TransferEncoding.encodeQuotedPrintable(ascii("b" * 76))) == Seq("b" * 76))
            assert(lines(TransferEncoding.encodeQuotedPrintable(ascii("b" * 77))) == Seq("b" * 75 + "=", "bb"))
        }
        "From at the start of a line, and a dot at the start of a line, are encoded (RFC 2049 section 3 item 8)" in {
            assert(text(TransferEncoding.encodeQuotedPrintable(ascii("From me\r\n.\r\nx From\r\nFromage"))) ==
                "=46rom me\r\n=2E\r\nx From\r\nFromage")
        }
        "empty content encodes to nothing" in {
            assert(TransferEncoding.encodeQuotedPrintable(Span.empty[Byte]).isEmpty)
        }
    }

    "round trips" - {
        val allOctets = Span.from(Array.tabulate(256)(_.toByte))
        "every octet value, through base64 and quoted-printable" in {
            assert(base64(TransferEncoding.encodeBase64(allOctets)).content.is(allOctets))
            assert(qp(TransferEncoding.encodeQuotedPrintable(allOctets)).is(allOctets))
        }
        "every length from 0 to 200, through base64 and quoted-printable" in {
            (0 to 200).foreach { n =>
                val content = pattern(n)
                assert(base64(TransferEncoding.encodeBase64(content)).content.is(content), s"base64, length $n")
                assert(qp(TransferEncoding.encodeQuotedPrintable(content)).is(content), s"quoted-printable, length $n")
            }
            succeed
        }
        "text lines at and around 76 characters, with and without trailing spaces, keep their text and stay within 76" in {
            for
                length <- 70 to 82
                tail   <- Seq("", " ", "\t", "=", ".")
            do
                val line    = "y" * length + tail
                val content = ascii(s"$line\r\n$line\r\n$line")
                val encoded = TransferEncoding.encodeQuotedPrintable(content)
                assert(lines(encoded).forall(_.length <= 76), s"length $length, tail [$tail]")
                assert(qp(encoded).is(content), s"length $length, tail [$tail]")
            end for
            succeed
        }
    }

    "the base64 alphabet of RFC 2045 section 6.8 Table 1" - {
        "every value encodes to the character the table gives, and decodes back" in {
            // The table's characters for the values 0 to 63, in order.
            val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            table.zipWithIndex.foreach { (char, value) =>
                val encoded = Base64.encode(octets(value << 2))
                assert(text(TransferEncoding.encodeBase64(octets(value << 2))).charAt(0) == char && encoded.charAt(0) == char)
                assert(((base64(ascii(s"${char}A==")).content(0) & 0xff) >>> 2) == value)
            }
            succeed
        }
    }

end TransferEncodingTest
