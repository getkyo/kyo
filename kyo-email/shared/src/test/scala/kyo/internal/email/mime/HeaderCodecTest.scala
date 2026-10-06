package kyo.internal.email.mime

import kyo.*
import kyo.charset.Charset
import kyo.internal.email.mime.HeaderCodec.Field
import kyo.internal.email.mime.HeaderCodec.ValueText

class HeaderCodecTest extends kyo.test.Test[Any]:

    // `{XX}` is the octet XX; every other character is its own octet and is below 0x80.
    private def wire(text: String): Span[Byte] =
        val out = Array.newBuilder[Byte]
        var i   = 0
        while i < text.length do
            if text.charAt(i) == '{' && i + 3 < text.length && text.charAt(i + 3) == '}' then
                out += Integer.parseInt(text.substring(i + 1, i + 3), 16).toByte
                i += 4
            else
                out += text.charAt(i).toByte
                i += 1
        end while
        Span.from(out.result())
    end wire

    private def section(text: String): HeaderCodec.Section = HeaderCodec.readSection(wire(text), 0)

    private def pairs(text: String): Seq[(String, String)] = section(text).fields.toSeq.map(f => (f.name, f.value))

    private def unstructured(text: String): String = HeaderCodec.decodeUnstructured(text)

    private def keeps(text: String)(using kyo.test.AssertScope): Unit =
        assert(unstructured(text) == text, s"[$text] became [${unstructured(text)}]")

    private def latin1(bytes: Span[Byte]): String = bytes.toArray.map(b => (b & 0xff).toChar).mkString

    "header sections: malformed and unusual input becomes fields and a body, never a failure" - {
        "a header line with no colon ends the section and starts the body, the mbox separator among them" in {
            val mbox = section("From alice@example.com Mon Jan 1 12:34:56 2024\r\nSubject: x\r\n\r\nbody")
            assert(mbox.fields.isEmpty && mbox.bodyStart == 0)
            val input = "Subject: a\r\nno colon here\r\nTo: b\r\n\r\n"
            val later = section(input)
            assert(later.fields.toSeq.map(f => (f.name, f.value)) == Seq("Subject" -> "a"))
            assert(later.bodyStart == input.indexOf("no colon"))
        }
        "a continuation line with no field before it: the section is empty and the body starts at that line" in {
            assert(section(" Subject: a\r\nTo: b\r\n") == HeaderCodec.Section(Chunk.empty, 0))
            assert(section("\tX: y\r\n\r\n") == HeaderCodec.Section(Chunk.empty, 0))
        }
        "a header section with no empty line after it, ending at the end of the input: every line is a header and the body is empty" in {
            val input = "Subject: a\r\nTo: b"
            assert(pairs(input) == Seq("Subject" -> "a", "To" -> "b"))
            assert(section(input).bodyStart == input.length)
            assert(section("Subject: a\r\n").bodyStart == "Subject: a\r\n".length)
        }
        "a header section followed directly by a non-header line: the section ends and that line starts the body" in {
            val input  = "Subject: a\r\nHello world\r\n"
            val result = section(input)
            assert(result.fields.map(_.name) == Chunk("Subject"))
            assert(input.substring(result.bodyStart) == "Hello world\r\n")
        }
        "a line longer than 998 octets is read whole" in {
            val long = "x" * 5000
            assert(pairs(s"Subject: $long\r\n\r\n") == Seq("Subject" -> long))
        }
        "a header longer than any limit keeps its whole unfolded value" in {
            val folds = 20000
            val input = "Subject: a" + ("\r\n w" * folds) + "\r\n\r\nbody"
            assert(pairs(input) == Seq("Subject" -> ("a" + " w" * folds)))
            assert(section(input).bodyStart == input.length - 4)
        }
        "bare LF line ends, and LF mixed with CRLF, are line ends" in {
            val input = "A: 1\nB: 2\r\nC: x\n y\r\n z\n\nbody"
            assert(pairs(input) == Seq("A" -> "1", "B" -> "2", "C" -> "x y z"))
            assert(input.substring(section(input).bodyStart) == "body")
        }
        "a bare CR in a header value becomes one SP" in {
            assert(pairs("Subject: a\rb\r\n\r\n") == Seq("Subject" -> "a b"))
            assert(pairs("Subject: a\r \rb\r\n\r\n") == Seq("Subject" -> "a   b"))
            val doubled = "Subject: a\r\r\n\r\nbody"
            assert(pairs(doubled) == Seq("Subject" -> "a"))
            assert(doubled.substring(section(doubled).bodyStart) == "body")
        }
        "octets from 0x80 with no encoded word: UTF-8 when well-formed, and an 8-bit header that is not UTF-8 is windows-1252" in {
            val result = section("Subject: caf{C3}{A9}\r\nTo: caf{E9}\r\nX: {C3}{A9}{E9}\r\nY: plain\r\n\r\n").fields
            assert(result(0) == Field("Subject", "caf\u00e9", ValueText.Utf8))
            assert(result(1) == Field("To", "caf\u00e9", ValueText.Windows1252))
            assert(result(2) == Field("X", "\u00c3\u00a9\u00e9", ValueText.Windows1252))
            assert(result(3) == Field("Y", "plain", ValueText.UsAscii))
        }
    }

    "lines and fields" - {
        "the name is kept as written, and white space before the colon is the obsolete form, not part of the name" in {
            assert(pairs("SUBJECT \t: x\r\nsUbJeCt:y\r\n\r\n") == Seq("SUBJECT" -> "x", "sUbJeCt" -> "y"))
        }
        "a name holding a character outside ftext is not a header line" in {
            assert(section("Bad Name: x\r\n\r\n").fields.isEmpty)
            assert(section("Bad{C3}{A9}: x\r\n\r\n").fields.isEmpty)
            assert(section(": x\r\n\r\n").fields.isEmpty)
        }
        "unfolding removes only the line end; SP and HTAB at either end are removed and internal white space is kept" in {
            assert(pairs("Subject: \t a \t b \r\n\t c  \r\n\r\n") == Seq("Subject" -> "a \t b \t c"))
        }
        "an empty value, and a value of white space only, are empty" in {
            assert(pairs("X:\r\nY: \t \r\nZ:\r\n \r\n\r\n") == Seq("X" -> "", "Y" -> "", "Z" -> ""))
        }
        "the empty line ending the section is consumed, as CRLF or as LF" in {
            assert(section("A: 1\n\nbody").bodyStart == 6)
            assert(section("A: 1\r\n\r\nbody").bodyStart == 8)
            assert(section("\r\nbody").bodyStart == 2)
        }
        "reading starts at the given offset" in {
            val input  = wire("junk\r\nA: 1\r\n\r\nrest")
            val result = HeaderCodec.readSection(input, 6)
            assert(result.fields.map(f => (f.name, f.value)) == Chunk("A" -> "1"))
            assert(result.bodyStart == 14)
        }
        "a CR at the end of the input with no LF after it is data" in {
            assert(pairs("Subject: a\rb") == Seq("Subject" -> "a b"))
            assert(pairs("Subject: a\r") == Seq("Subject" -> "a"))
        }
        "a multi-octet sequence cut by a fold is not well-formed UTF-8" in {
            assert(section("X: {C3}\r\n {A9}\r\n\r\n").fields == Chunk(Field("X", "\u00c3 \u00a9", ValueText.Windows1252)))
        }
        "windows-1252 gives each of the 128 octets from 0x80 its own character" in {
            val high  = (0x80 to 0xff).map(b => f"{$b%02X}").mkString
            val value = section(s"X: $high\r\n\r\n").fields(0).value
            assert(value.length == 128 && value.distinct.length == 128)
            assert(value.charAt(0) == '\u20ac' && value.charAt(1) == '\u0081' && value.charAt(0x20) == '\u00a0')
        }
        "a four-octet UTF-8 character becomes its code point" in {
            assert(section("X: {F0}{9F}{98}{80}\r\n\r\n").fields(0) == Field("X", "\ud83d\ude00", ValueText.Utf8))
        }
    }

    "endsBefore" - {
        "is asked at every line before the line is read, with the line's start and the start of its line end" in {
            val input  = wire("A: 1\r\n 2\r\nB: 3\n\nbody")
            val asked  = ChunkBuilder.init[(Int, Int)]
            val result = HeaderCodec.readSection(
                input,
                0,
                (start, end) =>
                    discard(asked.addOne((start, end)))
                    false
            )
            assert(asked.result() == Chunk((0, 4), (6, 8), (10, 14), (15, 15)))
            assert(result.fields.map(f => (f.name, f.value)) == Chunk("A" -> "1 2", "B" -> "3"))
        }
        "a line it accepts ends the section there and is left for the caller, a delimiter holding a colon among them" in {
            val text      = "Content-Type: text/plain\r\n--a:b\r\nX: y\r\n"
            val delimiter = (start: Int, end: Int) => end - start >= 2 && latin1(wire(text).slice(start, start + 2)) == "--"
            val result    = HeaderCodec.readSection(wire(text), 0, delimiter)
            assert(result.fields.map(_.name) == Chunk("Content-Type"))
            assert(result.bodyStart == text.indexOf("--a:b"))
            assert(pairs(text).map(_._1) == Seq("Content-Type", "--a", "X"))
        }
        "a continuation line it accepts ends the field and the section before it" in {
            val input  = wire("A: 1\r\n 2\r\n\r\n")
            val result = HeaderCodec.readSection(input, 0, (start, _) => start == 6)
            assert(result == HeaderCodec.Section(Chunk(Field("A", "1", ValueText.UsAscii)), 6))
        }
    }

    "encoded words and the 8-bit rule" - {
        "a field with raw UTF-8 and an encoded word" in {
            val field = section("Subject: caf{C3}{A9} =?ISO-8859-1?Q?na=EFve?=\r\n\r\n").fields(0)
            assert(field == Field("Subject", "caf\u00e9 =?ISO-8859-1?Q?na=EFve?=", ValueText.Utf8))
            assert(unstructured(field.value) == "caf\u00e9 na\u00efve")
        }
        "a field with raw windows-1252 and an encoded word" in {
            val field = section("Subject: caf{E9} =?UTF-8?Q?na=C3=AFve?=\r\n\r\n").fields(0)
            assert(field == Field("Subject", "caf\u00e9 =?UTF-8?Q?na=C3=AFve?=", ValueText.Windows1252))
            assert(unstructured(field.value) == "caf\u00e9 na\u00efve")
        }
        "a raw octet inside an encoded word leaves it as text" in {
            val field = section("Subject: =?UTF-8?Q?caf{C3}{A9}?=\r\n\r\n").fields(0)
            assert(field.value == "=?UTF-8?Q?caf\u00e9?=")
            keeps(field.value)
        }
    }

    "encoded words (RFC 2047): recognition, joining, and what stays as text" - {
        "=?UTF-8?Q?=C3?= =?UTF-8?Q?=A9?= is joined into one character" in {
            assert(unstructured("=?UTF-8?Q?=C3?= =?UTF-8?Q?=A9?=") == "\u00e9")
        }
        "=?UTF-8?B?w6k=?= =?UTF-8?B?w6k=?= is two whole characters" in {
            assert(unstructured("=?UTF-8?B?w6k=?= =?UTF-8?B?w6k=?=") == "\u00e9\u00e9")
        }
        "words of different charsets are decoded apart" in {
            assert(unstructured("=?UTF-8?Q?=C3?= =?ISO-8859-1?Q?=A9?=") == "\ufffd\u00a9")
        }
        "words join by the encoding their charsets resolve to, whatever the labels and encoding letters" in {
            assert(unstructured("=?utf-8?B?w6k=?= =?UTF8?q?=C3?= =?utf-8?Q?=A9?=") == "\u00e9\u00e9")
        }
        "a word that stays as its text breaks a run" in {
            assert(unstructured("=?utf-8?Q?=C3?= =?x-unknown?Q?a?= =?utf-8?Q?=A9?=") == "\ufffd =?x-unknown?Q?a?= \ufffd")
        }
        "the ISO-2022-JP pair decodes apart, each word from the initial state, with no U+FFFD" in {
            val pair = "=?ISO-2022-JP?B?GyRCJEIbKEI=?= =?ISO-2022-JP?B?GyRCJEQbKEI=?="
            assert(unstructured(pair) == "\u3062\u3064")
            val joined = Base64.decode("GyRCJEIbKEI=").getOrElse(Span.empty[Byte]).toArray ++
                Base64.decode("GyRCJEQbKEI=").getOrElse(Span.empty[Byte]).toArray
            assert(Charset.Iso2022Jp.decode(Span.from(joined)).contains('\ufffd'))
        }
        "the other stateful charsets decode each word alone too" in {
            def alone(label: String, charset: Charset, first: String, second: String)(using kyo.test.AssertScope): Unit =
                val expected = charset.decode(wire(first)) + charset.decode(wire(second))
                assert(expected != charset.decode(Span.from(wire(first).toArray ++ wire(second).toArray)))
                val input = s"=?$label?Q?${qEscape(first)}?= =?$label?Q?${qEscape(second)}?="
                assert(unstructured(input) == expected, input)
            end alone
            alone("UTF-7", Charset.Utf7, "+AOk", "AOk-")
            alone("HZ-GB-2312", Charset.HzGb2312, "~{0!", "0!~}")
            alone("ISO-2022-KR", Charset.Iso2022Kr, "{1B}$)C{0E}0!", "0!{0F}")
        }
        "a word whose charset is unknown stays as its text" in {
            keeps("=?x-unknown?Q?a?=")
            assert(unstructured("=?x-unknown?Q?a?= =?utf-8?Q?b?=") == "=?x-unknown?Q?a?= b")
        }
        "a word whose encoding is neither B nor Q stays as its text" in {
            keeps("=?utf-8?X?a?=")
            keeps("=?utf-8?BQ?a?=")
        }
        "B text with a character outside the base64 alphabet and = stays as its text" in {
            keeps("=?utf-8?B?w6k-?=")
            keeps("=?utf-8?B?w6k.?=")
        }
        "B text whose alphabet characters number 4k+1 stays as its text" in {
            keeps("=?utf-8?B?w6kxQ?=")
            keeps("=?utf-8?B?Q?=")
        }
        "Q text with = not followed by two hexadecimal digits stays as its text" in {
            keeps("=?utf-8?Q?a=4?=")
            keeps("=?utf-8?Q?a=G1?=")
            keeps("=?utf-8?Q?a=?=")
        }
        "Q text with a space or a ? stays as its text" in {
            keeps("=?utf-8?Q?a b?=")
            keeps("=?utf-8?Q?a?b?=")
        }
        "missing = padding in B text is not an error" in {
            assert(unstructured("=?utf-8?B?w6k?=") == "\u00e9")
        }
        "octets malformed in the charset become U+FFFD, not a bad word" in {
            assert(unstructured("=?utf-8?Q?=FF?=") == "\ufffd")
        }
        "the language suffix is dropped" in {
            assert(unstructured("=?US-ASCII*EN?Q?Keith_Moore?=") == "Keith Moore")
        }
        "words written back to back are split, and join like adjacent words" in {
            assert(unstructured("=?utf-8?Q?a?==?utf-8?Q?b?=") == "ab")
            assert(unstructured("=?utf-8?Q?=C3?==?utf-8?Q?=A9?=") == "\u00e9")
        }
        "an encoded word joined to text in its token decodes where it stands, and the text around it is kept as written" in {
            assert(unstructured("x=?utf-8?Q?a?=") == "xa")
            assert(unstructured("=?utf-8?Q?a?=x") == "ax")
            assert(unstructured("a=?utf-8?Q?b?=c") == "abc")
            assert(unstructured("(=?utf-8?Q?a?=)") == "(a)")
            assert(unstructured("[SUSPECTED SPAM]=?utf-8?B?VGhpcyBpcyB0aGUgb3JpZ2luYWwgc3ViamVjdA==?=") ==
                "[SUSPECTED SPAM]This is the original subject")
            assert(unstructured("Some text =?utf-8?Q??=here") == "Some text here")
        }
        "text joined to a word ends its run, and white space before the next word still joins it" in {
            assert(unstructured("=?utf-8?Q?=C3?=-=?utf-8?Q?=A9?=") == "�-�")
            assert(unstructured("x =?utf-8?Q?=C3?= =?utf-8?Q?=A9?=y") == "x éy")
        }
        "a word joined to text that stays as its text keeps that text too, and a word after it in the token decodes" in {
            assert(unstructured("a=?x-unknown?Q?b?==?utf-8?Q?c?=d") == "a=?x-unknown?Q?b?=cd")
        }
        "a failed attempt leaves the =? that starts inside its last characters to be read as a word" in {
            assert(unstructured("=??=?utf-8?Q?a?=") == "=??a")
            assert(unstructured("=?utf-8?Q?x=?utf-8?Q?a?=") == "=?utf-8?Q?xa")
        }
        "white space between an encoded word and text is kept" in {
            assert(unstructured("a =?utf-8?Q?b?= c") == "a b c")
            assert(unstructured("=?utf-8?Q?a?=  \t x") == "a  \t x")
        }
        "a word longer than 75 characters decodes" in {
            val long = "a" * 300
            assert(unstructured(s"=?utf-8?Q?$long?=") == long)
        }
        "Q hexadecimal digits in either case, and _ as a space" in {
            assert(unstructured("=?utf-8?Q?=c3=A9_x?=") == "\u00e9 x")
        }
        "an empty encoded text decodes to nothing" in {
            assert(unstructured("=?utf-8?Q??=") == "")
            assert(unstructured("a =?utf-8?B??= b") == "a  b")
        }
        "a charset label holding a . is read, and resolves" in {
            assert(unstructured("=?ANSI_X3.4-1968?Q?a?=") == "a")
        }
    }

    "RFC 2047" - {
        // Section 8's table of encoded forms and how they are displayed; the fifth form is folded onto a second line.
        val rows = Seq(
            (Seq("(=?ISO-8859-1?Q?a?=)"), "(a)"),
            (Seq("(=?ISO-8859-1?Q?a?= b)"), "(a b)"),
            (Seq("(=?ISO-8859-1?Q?a?= =?ISO-8859-1?Q?b?=)"), "(ab)"),
            (Seq("(=?ISO-8859-1?Q?a?=  =?ISO-8859-1?Q?b?=)"), "(ab)"),
            (Seq("(=?ISO-8859-1?Q?a?=", "    =?ISO-8859-1?Q?b?=)"), "(ab)"),
            (Seq("(=?ISO-8859-1?Q?a_b?=)"), "(a b)"),
            (Seq("(=?ISO-8859-1?Q?a?= =?ISO-8859-2?Q?_b?=)"), "(a b)")
        )

        def crlf(lines: String*): String = lines.mkString("", "\r\n", "\r\n")

        rows.zipWithIndex.foreach { case ((encoded, displayed), index) =>
            s"section 8, row ${index + 1}: ${encoded.mkString("<CRLF>")} decodes in unstructured text to $displayed" in {
                val value = section(crlf(s"Comments: ${encoded.head}" +: encoded.tail*) + "\r\n").fields(0).value
                assert(unstructured(value) == displayed)
            }
        }

        "section 8: the fields, and the Subject's two words of different charsets joined without the space between them" in {
            val fields = section(crlf(
                "From: =?US-ASCII?Q?Keith_Moore?= <moore@cs.utk.edu>",
                "To: =?ISO-8859-1?Q?Keld_J=F8rn_Simonsen?= <keld@dkuug.dk>",
                "CC: =?ISO-8859-1?Q?Andr=E9?= Pirard <PIRARD@vm1.ulg.ac.be>",
                "Subject: =?ISO-8859-1?B?SWYgeW91IGNhbiByZWFkIHRoaXMgeW8=?=",
                " =?ISO-8859-2?B?dSB1bmRlcnN0YW5kIHRoZSBleGFtcGxlLg==?="
            )).fields
            assert(fields.map(f => (f.name, f.value)) == Chunk(
                "From"    -> "=?US-ASCII?Q?Keith_Moore?= <moore@cs.utk.edu>",
                "To"      -> "=?ISO-8859-1?Q?Keld_J=F8rn_Simonsen?= <keld@dkuug.dk>",
                "CC"      -> "=?ISO-8859-1?Q?Andr=E9?= Pirard <PIRARD@vm1.ulg.ac.be>",
                "Subject" -> "=?ISO-8859-1?B?SWYgeW91IGNhbiByZWFkIHRoaXMgeW8=?= =?ISO-8859-2?B?dSB1bmRlcnN0YW5kIHRoZSBleGFtcGxlLg==?="
            ))
            assert(unstructured(fields(3).value) == "If you can read this you understand the example.")
        }
        "section 8: two header blocks" in {
            val first = section(crlf(
                "From: =?ISO-8859-1?Q?Olle_J=E4rnefors?= <ojarnef@admin.kth.se>",
                "To: ietf-822@dimacs.rutgers.edu, ojarnef@admin.kth.se",
                "Subject: Time for ISO 10646?"
            )).fields
            assert(first.map(f => (f.name, f.value)) == Chunk(
                "From"    -> "=?ISO-8859-1?Q?Olle_J=E4rnefors?= <ojarnef@admin.kth.se>",
                "To"      -> "ietf-822@dimacs.rutgers.edu, ojarnef@admin.kth.se",
                "Subject" -> "Time for ISO 10646?"
            ))
            assert(unstructured(first(2).value) == "Time for ISO 10646?")
            val second = section(crlf(
                "To: Dave Crocker <dcrocker@mordor.stanford.edu>",
                "Cc: ietf-822@dimacs.rutgers.edu, paf@comsol.se",
                "From: =?ISO-8859-1?Q?Patrik_F=E4ltstr=F6m?= <paf@nada.kth.se>",
                "Subject: Re: RFC-HDR care and feeding"
            )).fields
            assert(second.map(_.name) == Chunk("To", "Cc", "From", "Subject"))
            assert(second(2).value == "=?ISO-8859-1?Q?Patrik_F=E4ltstr=F6m?= <paf@nada.kth.se>")
            assert(unstructured(second(3).value) == "Re: RFC-HDR care and feeding")
        }
        "section 8: folded fields keep the white space after each fold" in {
            val fields = section(crlf(
                "From: Nathaniel Borenstein <nsb@thumper.bellcore.com>",
                "      (=?iso-8859-8?b?7eXs+SDv4SDp7Oj08A==?=)",
                "To: Greg Vaudreuil <gvaudre@NRI.Reston.VA.US>, Ned Freed",
                "   <ned@innosoft.com>, Keith Moore <moore@cs.utk.edu>",
                "Subject: Test of new header generator",
                "MIME-Version: 1.0",
                "Content-type: text/plain; charset=ISO-8859-1"
            )).fields
            assert(fields.map(f => (f.name, f.value)) == Chunk(
                "From"    -> "Nathaniel Borenstein <nsb@thumper.bellcore.com>      (=?iso-8859-8?b?7eXs+SDv4SDp7Oj08A==?=)",
                "To"      -> "Greg Vaudreuil <gvaudre@NRI.Reston.VA.US>, Ned Freed   <ned@innosoft.com>, Keith Moore <moore@cs.utk.edu>",
                "Subject" -> "Test of new header generator",
                "MIME-Version" -> "1.0",
                "Content-type" -> "text/plain; charset=ISO-8859-1"
            ))
        }
        "section 2: an encoded word holding spaces is four atoms, and the same text encoded is one word" in {
            keeps("=?iso-8859-1?q?this is some text?=")
            assert(unstructured("=?iso-8859-1?q?this=20is=20some=20text?=") == "this is some text")
        }
    }

    "Stalwart unstructured.json" - {
        import HeaderCodecStalwart.*

        lazy val listed = parse(EmbeddedStalwartUnstructuredDifferencesTsv.text)

        "every vector is read" in {
            assert(vectors.size == 30)
        }
        "every difference from Stalwart is listed, with its reason" in {
            val unlisted = observed.filterNot(listed.contains)
            assert(unlisted.isEmpty, s"${unlisted.size} differences are not listed:\n${render(unlisted)}")
        }
        "every listed difference still occurs" in {
            val stale = listed.filterNot(observed.contains)
            assert(stale.isEmpty, s"${stale.size} listed differences no longer occur:\n${render(stale)}")
        }
        "the list has no duplicate rows, and every other vector agrees" in {
            assert(listed.distinct.size == listed.size)
            val agreeing = vectors.zipWithIndex.filterNot((_, i) => listed.exists(_.index == i))
            assert(agreeing.forall((v, _) => HeaderCodec.decodeUnstructured(field(v).value) == v.expected))
            assert(agreeing.size + listed.size == 30)
        }
    }

    "pathological inputs" - {
        final class CountingText(text: String) extends CharSequence:
            var reads: Long              = 0
            def length: Int              = text.length
            def charAt(index: Int): Char =
                reads += 1; text.charAt(index)
            def subSequence(start: Int, end: Int): CharSequence =
                reads += end - start; text.subSequence(start, end)
            override def toString: String =
                reads += text.length; text
        end CountingText

        val fragments                           = Seq("=?", "=?a?", "=?a?q?", "=?=?")
        def megabyte(separator: String): String =
            val unit = fragments.mkString(separator) + separator
            unit * (1024 * 1024 / unit.length + 1)

        "a Subject of one megabyte of =? fragments is the text, unchanged" in {
            Seq("", " ").foreach { separator =>
                val text   = megabyte(separator)
                val result = section(s"Subject: $text\r\n\r\n").fields(0)
                assert(result.value == text.reverse.dropWhile(_ == ' ').reverse)
                assert(HeaderCodec.decodeUnstructured(result.value) == result.value)
            }
        }
        "the unstructured decoder reads each character a bounded number of times" in {
            // `=?a` repeated has no `?=` anywhere, so a recognizer that scanned ahead for `?=` from each `=?` would read it quadratically.
            val inputs = Seq(megabyte(""), megabyte(" "), "=?a" * (64 * 1024 / 3), "=?utf-8?q?" + "a" * (1024 * 1024))
            val counts = inputs.map { text =>
                val counting = new CountingText(text)
                assert(HeaderCodec.decodeUnstructured(counting) == text)
                (counting.reads, text.length)
            }
            assert(counts.forall((reads, length) => reads <= 4L * length), counts.map((r, l) => s"$r reads of $l").mkString(", "))
        }
    }

    private def qEscape(text: String): String =
        latin1(wire(text)).flatMap { c =>
            if c > ' ' && c < 0x7f && c != '=' && c != '?' && c != '_' then c.toString else f"=${c.toInt}%02X"
        }

end HeaderCodecTest
