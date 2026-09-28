package kyo.internal.charset

import kyo.*

class Big5DecoderTest extends kyo.test.Test[Any]:

    import DecoderFixtures.*

    private val decoder = Big5Decoder

    private lazy val index: Map[Int, Int] = IndexTableFixtures.entries(EmbeddedIndexBig5.text)

    // The pointers the Encoding Standard decodes to two code points, from its table in the Big5 decoder.
    private val pairs: Map[Int, String] =
        Map(1133 -> text(0xca, 0x304), 1135 -> text(0xca, 0x30c), 1164 -> text(0xea, 0x304), 1166 -> text(0xea, 0x30c))

    // The inverse of the decoder's arithmetic: pointer = (lead - 0x81) * 157 + trail - (0x40 below 0x7F, else 0x62).
    private def twoBytes(pointer: Int): Span[Byte] =
        val trail = pointer % 157
        bytes(pointer / 157 + 0x81, trail + (if trail < 0x3f then 0x40 else 0x62))

    private def ascii(b: Int): String = if b < 0x80 then b.toChar.toString else ""

    private def mismatches(cases: Iterable[(Span[Byte], String)]): Seq[String] =
        cases.iterator.collect {
            case (input, expected) if decoder.decode(input) != expected =>
                s"${show(input)}: ${show(decoder.decode(input))}, expected ${show(expected)}"
        }.take(10).toSeq

    "index Big5" - {
        "every pointer the file maps decodes from its two bytes to the file's code point" in {
            val checked = index.toSeq.map((pointer, codePoint) => (twoBytes(pointer), text(codePoint)))
            assert(IndexTableFixtures.dataLines(EmbeddedIndexBig5.text) == 18590)
            assert(checked.size == 18590)
            assert(mismatches(checked).isEmpty)
        }
        "every lead byte with each of the 256 trail bytes decodes as the algorithm says" in {
            val cases =
                for
                    lead  <- 0x81 to 0xfe
                    trail <- 0 to 0xff
                yield
                    val expected =
                        if (trail >= 0x40 && trail <= 0x7e) || (trail >= 0xa1 && trail <= 0xfe) then
                            val pointer = (lead - 0x81) * 157 + trail - (if trail < 0x7f then 0x40 else 0x62)
                            pairs.get(pointer).orElse(index.get(pointer).map(text(_))).getOrElse(text(0xfffd) + ascii(trail))
                        else text(0xfffd) + ascii(trail)
                    (bytes(lead, trail), expected)
            assert(mismatches(cases).isEmpty)
        }
        "each single byte decodes as the algorithm says" in {
            val cases = (0 to 0xff).map { b =>
                (bytes(b), if b < 0x80 then ascii(b) else text(0xfffd))
            }
            assert(mismatches(cases).isEmpty)
        }
    }

    "special cases" - {
        "pointers 1133, 1135, 1164 and 1166 each decode to two code points" in {
            assert(twoBytes(1133).is(bytes(0x88, 0x62)))
            assert(decoder.decode(bytes(0x88, 0x62)) == text(0xca, 0x304))
            assert(decoder.decode(bytes(0x88, 0x64)) == text(0xca, 0x30c))
            assert(decoder.decode(bytes(0x88, 0xa3)) == text(0xea, 0x304))
            assert(decoder.decode(bytes(0x88, 0xa5)) == text(0xea, 0x30c))
            assert(Seq(1133, 1135, 1164, 1166).forall(p => !index.contains(p)))
        }
        "a code point above U+FFFF is appended as a surrogate pair" in {
            val supplementary = index.filter(_._2 > 0xffff)
            assert(supplementary.size == 1713)
            val (pointer, codePoint) = supplementary.minBy(_._1)
            val decoded              = decoder.decode(twoBytes(pointer))
            assert(decoded.length == 2 && decoded.codePointAt(0) == codePoint)
        }
        "an ASCII trail byte of an unmapped pair is decoded again after the U+FFFD" in {
            assert(decoder.decode(bytes(0x81, 0x31)) == text(0xfffd, 0x31))
            assert(decoder.decode(bytes(0x81, 0x40)) == text(0xfffd, 0x40))
        }
        "a non-ASCII trail byte of an unmapped pair is consumed by the U+FFFD" in {
            assert(decoder.decode(bytes(0x81, 0x80)) == text(0xfffd))
            assert(decoder.decode(bytes(0x81, 0xff)) == text(0xfffd))
        }
        "0x80 and 0xFF are not lead bytes" in {
            assert(decoder.decode(bytes(0x80, 0x61)) == text(0xfffd, 0x61))
            assert(decoder.decode(bytes(0xff, 0x61)) == text(0xfffd, 0x61))
        }
        "a lead byte at the end of the input is one U+FFFD" in {
            assert(decoder.decode(bytes(0x61, 0xa4)) == text(0x61, 0xfffd))
        }
        "empty input decodes to the empty string" in {
            assert(decoder.decode(Span.empty[Byte]) == "")
        }
    }

    "web-platform-tests big5-decode-errors.html" - {
        "has 8 cases" in {
            assert(DecoderFixtures.big5Errors.size == 8)
        }
        DecoderFixtures.big5Errors.foreach { c =>
            c.title in {
                assert(decoder.decode(c.input) == c.expected)
            }
        }
    }

end Big5DecoderTest
