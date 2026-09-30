package kyo.internal.charset

import kyo.*

class Iso2022JpDecoderTest extends kyo.test.Test[Any]:

    import DecoderFixtures.*

    private val decoder = Iso2022JpDecoder

    private val Esc = 0x1b

    private lazy val jis0208: Map[Int, Int] = IndexTableFixtures.entries(EmbeddedIndexJis0208.text)

    private val asciiEscape    = Seq(Esc, 0x28, 0x42)
    private val romanEscape    = Seq(Esc, 0x28, 0x4a)
    private val katakanaEscape = Seq(Esc, 0x28, 0x49)
    private val jis0208Escape  = Seq(Esc, 0x24, 0x42)

    private def isPairByte(b: Int): Boolean = b >= 0x21 && b <= 0x7e

    // The ASCII state's rule for one byte that is not ESC.
    private def ascii(b: Int): String = if b <= 0x7f && b != 0x0e && b != 0x0f then b.toChar.toString else text(0xfffd)

    private def mismatches(cases: Iterable[(Span[Byte], String)]): Seq[String] =
        cases.iterator.collect {
            case (input, expected) if decoder.decode(input) != expected =>
                s"${show(input)}: ${show(decoder.decode(input))}, expected ${show(expected)}"
        }.take(10).toSeq

    "index jis0208" - {
        "every pair of bytes 0x21 to 0x7E after ESC $ B decodes to the file's code point, or U+FFFD where it maps none" in {
            val cases =
                for
                    lead  <- 0x21 to 0x7e
                    trail <- 0x21 to 0x7e
                yield (
                    bytes(jis0208Escape ++ Seq(lead, trail)*),
                    jis0208.get((lead - 0x21) * 94 + trail - 0x21).map(text(_)).getOrElse(text(0xfffd))
                )
            val mapped = cases.count(_._2 != text(0xfffd))
            assert(mapped == jis0208.count(_._1 < 94 * 94))
            assert(mapped == 7336)
            assert(mismatches(cases).isEmpty)
        }
        "every pair of bytes other than ESC after ESC $ B decodes as the algorithm says" in {
            val cases =
                for
                    first  <- (0 to 0xff).filter(_ != Esc)
                    second <- (0 to 0xff).filter(_ != Esc)
                yield
                    val expected =
                        if isPairByte(first) then
                            if isPairByte(second) then
                                jis0208.get((first - 0x21) * 94 + second - 0x21).map(text(_)).getOrElse(text(0xfffd))
                            else text(0xfffd)
                        else text(0xfffd, 0xfffd)
                    (bytes(jis0208Escape ++ Seq(first, second)*), expected)
            assert(mismatches(cases).isEmpty)
        }
    }

    "every single byte in each state" - {
        "ASCII" in {
            val cases = (0 to 0xff).map(b => (bytes(b), if b == Esc then text(0xfffd) else ascii(b)))
            assert(mismatches(cases).isEmpty)
        }
        "Roman" in {
            val cases = (0 to 0xff).map { b =>
                val expected =
                    if b == Esc then text(0xfffd)
                    else if b == 0x5c then text(0xa5)
                    else if b == 0x7e then text(0x203e)
                    else ascii(b)
                (bytes(romanEscape :+ b*), expected)
            }
            assert(mismatches(cases).isEmpty)
        }
        "katakana" in {
            val cases = (0 to 0xff).map { b =>
                (bytes(katakanaEscape :+ b*), if b >= 0x21 && b <= 0x5f then text(0xff61 - 0x21 + b) else text(0xfffd))
            }
            assert(mismatches(cases).isEmpty)
        }
        "leading byte, where a pair byte alone is cut short by the end of the input" in {
            val cases = (0 to 0xff).map(b => (bytes(jis0208Escape :+ b*), text(0xfffd)))
            assert(mismatches(cases).isEmpty)
        }
    }

    "every escape of three bytes, ESC and two more, from the ASCII state" in {
        val selecting = Set((0x28, 0x42), (0x28, 0x4a), (0x28, 0x49), (0x24, 0x40), (0x24, 0x42))
        // ASCII-state output for the bytes an invalid escape puts back, each decoded again from the ASCII state.
        def afterRestore(first: Int, second: Int): String =
            if first == Esc then
                if second == 0x24 || second == 0x28 then text(0xfffd) + second.toChar
                else text(0xfffd) + (if second == Esc then text(0xfffd) else ascii(second))
            else ascii(first) + (if second == Esc then text(0xfffd) else ascii(second))
        val cases =
            for
                first  <- 0 to 0xff
                second <- 0 to 0xff
            yield
                val expected =
                    if selecting.contains((first, second)) then ""
                    else text(0xfffd) + afterRestore(first, second)
                (bytes(Esc, first, second), expected)
        assert(mismatches(cases).isEmpty)
    }

    "special cases" - {
        "two escapes with nothing decoded between them are an error: the output flag" in {
            assert(decoder.decode(bytes(asciiEscape ++ asciiEscape :+ 0x50*)) == text(0xfffd, 0x50))
            assert(decoder.decode(bytes((0x50 +: asciiEscape) :+ 0x50*)) == text(0x50, 0x50))
            assert(decoder.decode(bytes(jis0208Escape ++ Seq(0x50) ++ jis0208Escape ++ Seq(0x50, 0x50)*)) ==
                text(0xfffd, jis0208((0x50 - 0x21) * 94 + 0x50 - 0x21)))
        }
        "SO and SI are errors in the ASCII and Roman states" in {
            assert(decoder.decode(bytes(0x0e, 0x0f)) == text(0xfffd, 0xfffd))
            assert(decoder.decode(bytes(romanEscape ++ Seq(0x0e, 0x0f)*)) == text(0xfffd, 0xfffd))
        }
        "the Roman state maps 0x5C to U+00A5 and 0x7E to U+203E" in {
            assert(decoder.decode(bytes(romanEscape ++ Seq(0x5c, 0x5d, 0x7e)*)) == text(0xa5, 0x5d, 0x203e))
        }
        "the katakana state maps 0x21 to 0x5F to U+FF61 to U+FF9F and nothing else" in {
            assert(decoder.decode(bytes(katakanaEscape ++ Seq(0x21, 0x5f, 0x60, 0x0a)*)) == text(0xff61, 0xff9f, 0xfffd, 0xfffd))
        }
        "ESC $ @ and ESC $ B both select jis0208" in {
            val pair = Seq(0x30, 0x21)
            val cp   = jis0208((0x30 - 0x21) * 94)
            assert(decoder.decode(bytes(Seq(Esc, 0x24, 0x40) ++ pair*)) == text(cp))
            assert(decoder.decode(bytes(jis0208Escape ++ pair*)) == text(cp))
        }
        "a byte that cannot start a pair is an error and the leading byte state stays" in {
            assert(decoder.decode(bytes(jis0208Escape ++ Seq(0x0a, 0x30, 0x21)*)) == text(0xfffd, jis0208((0x30 - 0x21) * 94)))
        }
        "ESC after a lead byte is an error that drops the lead and starts an escape" in {
            assert(decoder.decode(bytes(jis0208Escape ++ Seq(0x30) ++ asciiEscape :+ 0x41*)) == text(0xfffd, 0x41))
        }
        "a trail byte outside 0x21 to 0x7E is an error and is consumed" in {
            assert(decoder.decode(bytes(jis0208Escape ++ Seq(0x30, 0x0a, 0x30, 0x21)*)) == text(0xfffd, jis0208((0x30 - 0x21) * 94)))
        }
        "an unmapped pair is an error" in {
            assert(!jis0208.contains((0x29 - 0x21) * 94 + 0x21 - 0x21))
            assert(decoder.decode(bytes(jis0208Escape ++ Seq(0x29, 0x21)*)) == text(0xfffd))
        }
        "an invalid escape start puts the byte back and returns to the last selected state" in {
            assert(decoder.decode(bytes(katakanaEscape ++ Seq(Esc, 0x50)*)) == text(0xfffd, 0xff61 - 0x21 + 0x50))
        }
        "an invalid escape puts back the two bytes after ESC, decoded in the last selected state" in {
            assert(decoder.decode(bytes(Esc, 0x24, 0x50)) == text(0xfffd, 0x24, 0x50))
            assert(decoder.decode(bytes(katakanaEscape ++ Seq(Esc, 0x24, 0x50)*)) ==
                text(0xfffd, 0xff61 - 0x21 + 0x24, 0xff61 - 0x21 + 0x50))
        }
        "the end of the input inside an escape is one error, and the bytes after ESC are decoded" in {
            assert(decoder.decode(bytes(Esc)) == text(0xfffd))
            assert(decoder.decode(bytes(Esc, 0x28)) == text(0xfffd, 0x28))
        }
        "the end of the input in a shifted state is not an error; inside a pair it is one" in {
            assert(decoder.decode(bytes(katakanaEscape :+ 0x21*)) == text(0xff61))
            assert(decoder.decode(bytes(jis0208Escape*)) == "")
            assert(decoder.decode(bytes(jis0208Escape :+ 0x30*)) == text(0xfffd))
        }
        "empty input decodes to the empty string" in {
            assert(decoder.decode(Span.empty[Byte]) == "")
        }
    }

    "web-platform-tests iso-2022-jp-decoder.any.js" - {
        "has 34 cases" in {
            assert(DecoderFixtures.iso2022Jp.size == 34)
        }
        DecoderFixtures.iso2022Jp.foreach { c =>
            c.title in {
                assert(decoder.decode(c.input) == c.expected)
            }
        }
    }

    "web-platform-tests iso2022jp-decode-errors.html" - {
        "has 8 cases, the commented-out one not counted" in {
            assert(DecoderFixtures.iso2022JpErrors.size == 8)
        }
        DecoderFixtures.iso2022JpErrors.foreach { c =>
            c.title in {
                assert(decoder.decode(c.input) == c.expected)
            }
        }
    }

end Iso2022JpDecoderTest
