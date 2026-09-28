package kyo.internal.charset

import kyo.*

class ShiftJisDecoderTest extends kyo.test.Test[Any]:

    import DecoderFixtures.*

    private val decoder = ShiftJisDecoder

    private lazy val jis0208: Map[Int, Int] = IndexTableFixtures.entries(EmbeddedIndexJis0208.text)

    // The inverse of the decoder's arithmetic: pointer = (lead - (0x81 below 0xA0, else 0xC1)) * 188 + trail - (0x40 below 0x7F, else
    // 0x41). A row below 0x1F comes from a lead byte 0x81 to 0x9F, and a later row from 0xE0 up.
    private def twoBytes(pointer: Int): Span[Byte] =
        val row   = pointer / 188
        val trail = pointer % 188
        bytes(row + (if row < 0x1f then 0x81 else 0xc1), trail + (if trail < 0x3f then 0x40 else 0x41))
    end twoBytes

    private def ascii(b: Int): String = if b < 0x80 then b.toChar.toString else ""

    private def isLead(b: Int): Boolean = (b >= 0x81 && b <= 0x9f) || (b >= 0xe0 && b <= 0xfc)

    private def mismatches(cases: Iterable[(Span[Byte], String)]): Seq[String] =
        cases.iterator.collect {
            case (input, expected) if decoder.decode(input) != expected =>
                s"${show(input)}: ${show(decoder.decode(input))}, expected ${show(expected)}"
        }.take(10).toSeq

    "index jis0208" - {
        "every pointer the file maps decodes from its two bytes to the file's code point" in {
            val checked = jis0208.toSeq.map((pointer, codePoint) => (twoBytes(pointer), text(codePoint)))
            assert(IndexTableFixtures.dataLines(EmbeddedIndexJis0208.text) == 7724)
            assert(checked.size == 7724)
            assert(jis0208.keys.forall(p => p < 8836 || p > 10715))
            assert(mismatches(checked).isEmpty)
        }
        "every lead byte with each of the 256 trail bytes decodes as the algorithm says" in {
            val cases =
                for
                    lead  <- (0x81 to 0x9f) ++ (0xe0 to 0xfc)
                    trail <- 0 to 0xff
                yield
                    val expected =
                        if (trail >= 0x40 && trail <= 0x7e) || (trail >= 0x80 && trail <= 0xfc) then
                            val pointer = (lead - (if lead < 0xa0 then 0x81 else 0xc1)) * 188 + trail -
                                (if trail < 0x7f then 0x40 else 0x41)
                            if pointer >= 8836 && pointer <= 10715 then text(0xe000 - 8836 + pointer)
                            else jis0208.get(pointer).map(text(_)).getOrElse(text(0xfffd) + ascii(trail))
                        else text(0xfffd) + ascii(trail)
                    (bytes(lead, trail), expected)
            assert(mismatches(cases).isEmpty)
        }
        "each single byte decodes as the algorithm says" in {
            val cases = (0 to 0xff).map { b =>
                val expected =
                    if b <= 0x80 then b.toChar.toString
                    else if b >= 0xa1 && b <= 0xdf then text(0xff61 - 0xa1 + b)
                    else text(0xfffd)
                (bytes(b), expected)
            }
            assert(mismatches(cases).isEmpty)
        }
    }

    "special cases" - {
        "0x80 is U+0080" in {
            assert(decoder.decode(bytes(0x80)) == text(0x80))
        }
        "0xA1 to 0xDF are half-width katakana, U+FF61 to U+FF9F" in {
            assert(decoder.decode(bytes(0xa1)) == text(0xff61))
            assert(decoder.decode(bytes(0xdf)) == text(0xff9f))
        }
        "pointers 8836 to 10715 are U+E000 upward, all 1880 of them" in {
            val cases = (8836 to 10715).map(p => (twoBytes(p), text(0xe000 - 8836 + p)))
            assert(twoBytes(8836).is(bytes(0xf0, 0x40)))
            assert(twoBytes(10715).is(bytes(0xf9, 0xfc)))
            assert(decoder.decode(bytes(0xf9, 0xfc)) == text(0xe757))
            assert(mismatches(cases).isEmpty)
        }
        "a lead byte from 0x81 to 0x9F is offset by 0x81, and one from 0xE0 to 0xFC by 0xC1" in {
            assert(decoder.decode(bytes(0x81, 0x40)) == text(jis0208(0)))
            assert(decoder.decode(bytes(0x9f, 0xfc)) == text(jis0208(30 * 188 + 187)))
            assert(decoder.decode(bytes(0xe0, 0x40)) == text(jis0208(31 * 188)))
        }
        "0xA0 and 0xFD to 0xFF are errors" in {
            val cases = Seq(0xa0, 0xfd, 0xfe, 0xff).map(b => (bytes(b, 0x61), text(0xfffd, 0x61)))
            assert(mismatches(cases).isEmpty)
        }
        "an ASCII trail byte outside the trail range or of an unmapped pair is decoded again after the U+FFFD" in {
            assert(decoder.decode(bytes(0x81, 0x31)) == text(0xfffd, 0x31))
            assert(decoder.decode(bytes(0x81, 0x7f)) == text(0xfffd, 0x7f))
        }
        "a non-ASCII trail byte outside the trail range is consumed by the U+FFFD" in {
            assert(decoder.decode(bytes(0x81, 0xfd)) == text(0xfffd))
        }
        "a lead byte at the end of the input is one U+FFFD" in {
            assert((0 to 0xff).filter(isLead).forall(b => decoder.decode(bytes(0x61, b)) == text(0x61, 0xfffd)))
        }
        "empty input decodes to the empty string" in {
            assert(decoder.decode(Span.empty[Byte]) == "")
        }
    }

    "web-platform-tests sjis-decode-errors.html" - {
        "has 10 cases" in {
            assert(DecoderFixtures.shiftJisErrors.size == 10)
        }
        DecoderFixtures.shiftJisErrors.foreach { c =>
            c.title in {
                assert(decoder.decode(c.input) == c.expected)
            }
        }
    }

end ShiftJisDecoderTest
