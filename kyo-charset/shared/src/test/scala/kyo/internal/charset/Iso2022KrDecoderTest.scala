package kyo.internal.charset

import kyo.*

class Iso2022KrDecoderTest extends kyo.test.Test[Any]:

    import DecoderFixtures.*

    private val decoder = Iso2022KrDecoder

    private lazy val eucKr: Map[Int, Int] = IndexTableFixtures.entries(EmbeddedIndexEucKr.text)

    /** The values RFC 1557 section 3's formal syntax gives: ESC, SO and SI in decimal, the one-of-94 range, and the designator's
      * characters after ESC.
      */
    final private case class Syntax(esc: Int, so: Int, si: Int, first: Int, last: Int, designator: Seq[Int]) derives CanEqual

    private val syntax: Syntax = Syntax(27, 14, 15, 33, 126, Seq('$'.toInt, ')'.toInt, 'C'.toInt))

    private def designator: Seq[Int] = syntax.esc +: syntax.designator

    private def isPairByte(b: Int): Boolean = b >= syntax.first && b <= syntax.last

    private def mismatches(cases: Iterable[(Span[Byte], String)]): Seq[String] =
        cases.iterator.collect {
            case (input, expected) if decoder.decode(input) != expected =>
                s"${show(input)}: ${show(decoder.decode(input))}, expected ${show(expected)}"
        }.take(10).toSeq

    "RFC 1557's formal syntax" - {
        "a designated line with a segment decodes, the segment's pairs through the KS X 1001 block of index EUC-KR" in {
            val pair     = Seq(0x30, 0x21)
            val input    = designator ++ "abc".map(_.toInt) ++ Seq(syntax.so) ++ pair ++ Seq(syntax.si) ++ "def\r\n".map(_.toInt)
            val expected = "abc" + text(eucKr((pair(0) - 1) * 190 + pair(1) + 0x3f)) + "def\r\n"
            assert(decoder.decode(bytes(input*)) == expected)
        }
    }

    "index EUC-KR" - {
        "every pair of one-of-94 bytes in a segment decodes to the file's code point for the EUC-KR bytes 0x80 above, or U+FFFD" in {
            val cases =
                for
                    first  <- syntax.first to syntax.last
                    second <- syntax.first to syntax.last
                yield (
                    bytes(designator ++ Seq(syntax.so, first, second, syntax.si)*),
                    eucKr.get((first + 0x80 - 0x81) * 190 + second + 0x80 - 0x41).map(text(_)).getOrElse(text(0xfffd))
                )
            assert(cases.count(_._2 != text(0xfffd)) == 8226)
            assert(mismatches(cases).isEmpty)
        }
        "the Unified Hangul Code entries, lead below 0xA1 or trail below 0xA1, are not reachable from 7-bit pairs" in {
            val reachable = (for
                first  <- syntax.first to syntax.last
                second <- syntax.first to syntax.last
            yield (first - 1) * 190 + second + 0x3f).toSet
            assert(eucKr.keys.count(p => !reachable.contains(p)) == 8822)
            assert(eucKr.keys.filterNot(reachable.contains).forall(p => p / 190 + 0x81 < 0xa1 || p % 190 + 0x41 < 0xa1))
        }
    }

    "every single byte in each state" - {
        "ASCII after the designator" in {
            val cases = (0 to 0xff).map { b =>
                val expected =
                    if b == syntax.esc || b == syntax.si || b >= 0x80 then text(0xfffd)
                    else if b == syntax.so then text(0xfffd)
                    else b.toChar.toString
                (bytes(designator :+ b*), expected)
            }
            assert(mismatches(cases).isEmpty)
        }
        "a segment, cut short by the end of the input" in {
            val cases = (0 to 0xff).map { b =>
                val expected =
                    if isPairByte(b) || b == syntax.si then text(0xfffd)
                    else if b == syntax.esc || b == syntax.so || b >= 0x80 then text(0xfffd, 0xfffd)
                    else text(0xfffd) + b.toChar
                (bytes(designator ++ Seq(syntax.so, b)*), expected)
            }
            assert(mismatches(cases).isEmpty)
        }
    }

    "malformed input" - {
        "SO before any designator is one U+FFFD, and still shifts" in {
            assert(decoder.decode(bytes(syntax.so, 0x30, 0x21, syntax.si)) == text(0xfffd, eucKr((0x30 - 1) * 190 + 0x21 + 0x3f)))
        }
        "a designator not at the start of a line is one U+FFFD, and still designates" in {
            assert(decoder.decode(bytes(('a'.toInt +: designator) ++ Seq(syntax.so, 0x30, 0x21, syntax.si)*)) ==
                text('a', 0xfffd, eucKr((0x30 - 1) * 190 + 0x21 + 0x3f)))
            assert(decoder.decode(bytes(Seq('a'.toInt, '\n'.toInt) ++ designator :+ 'b'.toInt*)) == "a\nb")
        }
        "an ESC that is not a designator takes the start of the line, so a designator after it is one U+FFFD" in {
            assert(decoder.decode(bytes((syntax.esc +: designator) ++ Seq(syntax.so, 0x30, 0x21, syntax.si)*)) ==
                text(0xfffd, 0xfffd, eucKr((0x30 - 1) * 190 + 0x21 + 0x3f)))
        }
        "a line starts after LF, with or without CR before it; a bare CR does not start a line" in {
            assert(decoder.decode(bytes(Seq('a'.toInt, '\r'.toInt, '\n'.toInt) ++ designator :+ 'b'.toInt*)) == "a\r\nb")
            assert(decoder.decode(bytes(Seq('a'.toInt, '\r'.toInt) ++ designator :+ 'b'.toInt*)) == text('a', '\r', 0xfffd, 'b'))
        }
        "a second designator, or one after an SO, is one U+FFFD" in {
            assert(decoder.decode(bytes(designator ++ Seq('\n'.toInt) ++ designator*)) == "\n" + text(0xfffd))
            assert(decoder.decode(bytes(designator ++ Seq(syntax.so, 0x30, 0x21, syntax.si, '\n'.toInt) ++ designator*)) ==
                text(eucKr((0x30 - 1) * 190 + 0x21 + 0x3f), '\n', 0xfffd))
        }
        "ESC not followed by $ ) C is one U+FFFD, and the bytes after it are decoded again" in {
            assert(decoder.decode(bytes(syntax.esc, '$'.toInt, ')'.toInt, 'D'.toInt)) == text(0xfffd, '$', ')', 'D'))
            assert(decoder.decode(bytes(syntax.esc, '$'.toInt)) == text(0xfffd, '$'))
        }
        "SI outside a segment and bytes from 0x80 are one U+FFFD each" in {
            assert(decoder.decode(bytes(designator ++ Seq(syntax.si, 0x80, 0xff, 'a'.toInt)*)) == text(0xfffd, 0xfffd, 0xfffd, 'a'))
        }
        "a byte from 0x80 in a segment is one U+FFFD and the segment continues, with or without a pending lead byte" in {
            val pair = eucKr((0x30 - 1) * 190 + 0x21 + 0x3f)
            assert(decoder.decode(bytes(designator ++ Seq(syntax.so, 0x80, 0x30, 0x21, syntax.si, 'a'.toInt)*)) == text(0xfffd, pair, 'a'))
            assert(decoder.decode(bytes(designator ++ Seq(syntax.so, 0x30, 0xff, 0x30, 0x21, syntax.si, 'a'.toInt)*)) ==
                text(0xfffd, pair, 'a'))
        }
        "an empty segment is one U+FFFD" in {
            assert(decoder.decode(bytes(designator ++ Seq(syntax.so, syntax.si, 'a'.toInt)*)) == text(0xfffd, 'a'))
        }
        "a line end inside a segment ends it with one U+FFFD and is decoded as ASCII" in {
            assert(decoder.decode(bytes(designator ++ Seq(syntax.so, 0x30, 0x21, '\r'.toInt, '\n'.toInt, 0x30, 0x21)*)) ==
                text(eucKr((0x30 - 1) * 190 + 0x21 + 0x3f), 0xfffd, '\r', '\n', 0x30, 0x21))
        }
        "a lead byte followed by a byte that is not one-of-94 is one U+FFFD, and SI after it still closes the segment" in {
            assert(decoder.decode(bytes(designator ++ Seq(syntax.so, 0x30, syntax.si, 'a'.toInt)*)) == text(0xfffd, 'a'))
        }
        "a lead byte followed by a control is two U+FFFD, the cut pair and the byte that ends the segment, then the control" in {
            assert(decoder.decode(bytes(designator ++ Seq(syntax.so, 0x30, '\n'.toInt, 'a'.toInt)*)) == text(0xfffd, 0xfffd, '\n', 'a'))
        }
        "the end of the input inside a segment is one U+FFFD" in {
            assert(decoder.decode(bytes(designator ++ Seq(syntax.so, 0x30, 0x21)*)) == text(eucKr((0x30 - 1) * 190 + 0x21 + 0x3f), 0xfffd))
            assert(decoder.decode(bytes(designator ++ Seq(syntax.so, 0x30)*)) == text(0xfffd))
        }
        "an unmapped pair is one U+FFFD" in {
            assert(!eucKr.contains((0x2d - 1) * 190 + 0x21 + 0x3f))
            assert(decoder.decode(bytes(designator ++ Seq(syntax.so, 0x2d, 0x21, syntax.si)*)) == text(0xfffd))
        }
        "empty input decodes to the empty string" in {
            assert(decoder.decode(Span.empty[Byte]) == "")
        }
    }

end Iso2022KrDecoderTest
