package kyo.internal.charset

import kyo.*

class HzGb2312DecoderTest extends kyo.test.Test[Any]:

    import DecoderFixtures.*

    private val decoder = HzGb2312Decoder

    private lazy val gb18030: Map[Int, Int] = IndexTableFixtures.entries(EmbeddedIndexGb18030.text)

    // A GB 2312 pair b1 b2 is the GB bytes b1 + 0x80, b2 + 0x80: pointer (lead - 0x81) * 190 + trail - 0x41.
    private def pairCodePoint(first: Int, second: Int): Int = gb18030((first + 0x80 - 0x81) * 190 + second + 0x80 - 0x41)

    private def ascii(s: String): Seq[Int] = s.map(_.toInt)

    private def mismatches(cases: Iterable[(Span[Byte], String)]): Seq[String] =
        cases.iterator.collect {
            case (input, expected) if decoder.decode(input) != expected =>
                s"${show(input)}: ${show(decoder.decode(input))}, expected ${show(expected)}"
        }.take(10).toSeq

    // The three encoded examples of RFC 1843 section 4: the same sentence with no line limit, cut at 42 columns, and cut at each mode
    // switch.
    private val examples: Seq[String] = Seq(
        "This sentence is in ASCII.\nThe next sentence is in GB.~{<:Ky2;S{#,NpJ)l6HK!#~}Bye.",
        "This sentence is in ASCII.\nThe next sentence is in GB.~{<:Ky2;S{#,~}~\n~{NpJ)l6HK!#~}Bye.",
        "This sentence is in ASCII.\nThe next sentence is in GB.~\n~{<:Ky2;S{#,NpJ)l6HK!#~}~\nBye."
    )

    "RFC 1843 section 4" - {
        "the three examples decode to the same text, with no U+FFFD" in {
            val decoded = examples.map(e => decoder.decode(bytes(ascii(e)*)))
            assert(decoded.distinct.size == 1)
            assert(!decoded.head.contains(Decoder.Replacement))
        }
        "the decoded text is the ASCII of example 1 with its GB run decoded pair by pair through index gb18030" in {
            val example = examples.head
            val open    = example.indexOf("~{")
            val close   = example.indexOf("~}")
            val run     = example.substring(open + 2, close)
            val gb      = run.grouped(2).map(pair => text(pairCodePoint(pair(0), pair(1)))).mkString
            assert(decoder.decode(bytes(ascii(example)*)) == example.substring(0, open) + gb + example.substring(close + 2))
            assert(run.length == 20)
        }
    }

    "index gb18030" - {
        "every GB 2312 pair, first byte 0x21 to 0x77 and second 0x21 to 0x7E, decodes as the same bytes 0x80 above do in GBK" in {
            val cases =
                for
                    first  <- 0x21 to 0x77
                    second <- 0x21 to 0x7e
                yield (bytes(ascii("~{") ++ Seq(first, second) ++ ascii("~}")*), text(pairCodePoint(first, second)))
            assert(cases.count(c => c._2.codePointAt(0) >= 0xe000 && c._2.codePointAt(0) <= 0xf8ff) == 687)
            assert(mismatches(cases).isEmpty)
            assert(cases.forall(c =>
                Gb18030Decoder.gbk.decode(Span.from(c._1.toArray.slice(2, 4).map(b => (b + 0x80).toByte))) == c._2
            ))
        }
    }

    "every single byte in each state" - {
        "ASCII" in {
            val cases = (0 to 0xff).map(b => (bytes(b), if b < 0x80 && b != '~' then b.toChar.toString else text(0xfffd)))
            assert(mismatches(cases).isEmpty)
        }
        "after ~ in ASCII mode" in {
            val cases = (0 to 0xff).map { b =>
                val expected =
                    if b == '~' then "~"
                    else if b == '{' then text(0xfffd)
                    else if b == '\n' then ""
                    else if b < 0x80 then text(0xfffd) + b.toChar
                    else text(0xfffd)
                (bytes('~', b), expected)
            }
            assert(mismatches(cases).isEmpty)
        }
        "GB mode, cut short by the end of the input" in {
            val cases = (0 to 0xff).map { b =>
                val expected =
                    if b == '~' || (b >= 0x21 && b <= 0x7d) then text(0xfffd)
                    else if b <= 0x20 || b == 0x7f then text(0xfffd) + b.toChar
                    else text(0xfffd, 0xfffd)
                (bytes(ascii("~{") :+ b*), expected)
            }
            assert(mismatches(cases).isEmpty)
        }
    }

    "malformed input" - {
        "~ followed by another character in ASCII mode is one U+FFFD, and the character is decoded again" in {
            assert(decoder.decode(bytes(ascii("a~xb")*)) == text('a', 0xfffd, 'x', 'b'))
        }
        "~ at the end of the input is one U+FFFD" in {
            assert(decoder.decode(bytes(ascii("a~")*)) == text('a', 0xfffd))
        }
        "~\\n is a line continuation that produces nothing" in {
            assert(decoder.decode(bytes(ascii("a~\nb")*)) == "ab")
        }
        "~ not followed by } in GB mode is one U+FFFD, and the character is decoded again in GB mode" in {
            assert(decoder.decode(bytes(ascii("~{~<:~}")*)) == text(0xfffd, pairCodePoint('<', ':')))
        }
        "a pair whose first byte is 0x78 to 0x7D, rows GB 2312 does not have, is one U+FFFD for both bytes, and the pairs after it keep their alignment" in {
            assert(decoder.decode(bytes(Seq('~'.toInt, '{'.toInt, 0x78, 0x21) ++ ascii("<:=;~}")*)) ==
                text(0xfffd, pairCodePoint('<', ':'), pairCodePoint('=', ';')))
            val cases =
                for
                    first  <- 0x78 to 0x7d
                    second <- 0x21 to 0x7e
                yield (bytes(ascii("~{") ++ Seq(first, second) ++ ascii("<:~}")*), text(0xfffd, pairCodePoint('<', ':')))
            assert(mismatches(cases).isEmpty)
        }
        "a pair cut short by a byte outside 0x21 to 0x7E is one U+FFFD, and the byte is decoded again" in {
            assert(decoder.decode(bytes(ascii("~{<\n")*)) == text(0xfffd, 0xfffd, '\n'))
        }
        "a control byte in GB mode ends the run with one U+FFFD and is decoded as ASCII" in {
            assert(decoder.decode(bytes(ascii("~{<:\nab")*)) == text(pairCodePoint('<', ':'), 0xfffd, '\n', 'a', 'b'))
        }
        "bytes from 0x80 are one U+FFFD in either mode" in {
            assert(decoder.decode(bytes(0x80, 'a', '~', '{', 0xff, '~', '}')) == text(0xfffd, 'a', 0xfffd))
        }
        "a byte from 0x80 in GB mode is one U+FFFD and the run continues, with or without a pending lead byte" in {
            assert(decoder.decode(bytes(Seq('~'.toInt, '{'.toInt, 0x80) ++ ascii("<:~}a")*)) == text(0xfffd, pairCodePoint('<', ':'), 'a'))
            assert(decoder.decode(bytes(Seq('~'.toInt, '{'.toInt, '='.toInt, 0xff) ++ ascii("<:~}a")*)) ==
                text(0xfffd, pairCodePoint('<', ':'), 'a'))
        }
        "the end of the input in GB mode is one U+FFFD, with or without a pending byte" in {
            assert(decoder.decode(bytes(ascii("~{<:")*)) == text(pairCodePoint('<', ':'), 0xfffd))
            assert(decoder.decode(bytes(ascii("~{<")*)) == text(0xfffd))
            assert(decoder.decode(bytes(ascii("~{~")*)) == text(0xfffd))
        }
        "~~ is ~ and ~} in ASCII mode is an error" in {
            assert(decoder.decode(bytes(ascii("~~~}")*)) == text('~', 0xfffd, '}'))
        }
        "empty input decodes to the empty string" in {
            assert(decoder.decode(Span.empty[Byte]) == "")
        }
    }

end HzGb2312DecoderTest
