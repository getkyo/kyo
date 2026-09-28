package kyo.internal.charset

import kyo.*

class EucJpDecoderTest extends kyo.test.Test[Any]:

    import DecoderFixtures.*

    private val decoder = EucJpDecoder

    private lazy val jis0208: Map[Int, Int] = IndexTableFixtures.entries(EmbeddedIndexJis0208.text)
    private lazy val jis0212: Map[Int, Int] = IndexTableFixtures.entries(EmbeddedIndexJis0212.text)

    // The inverse of the decoder's arithmetic: pointer = (lead - 0xA1) * 94 + trail - 0xA1, for pointers below 94 * 94 = 8836.
    private def pair(pointer: Int): Seq[Int] = Seq(pointer / 94 + 0xa1, pointer % 94 + 0xa1)

    private def ascii(b: Int): String = if b < 0x80 then b.toChar.toString else ""

    private def mismatches(cases: Iterable[(Span[Byte], String)]): Seq[String] =
        cases.iterator.collect {
            case (input, expected) if decoder.decode(input) != expected =>
                s"${show(input)}: ${show(decoder.decode(input))}, expected ${show(expected)}"
        }.take(10).toSeq

    "index jis0208" - {
        "every pointer below 8836 the file maps decodes from its two bytes; the others are beyond EUC-JP's two bytes" in {
            val (reachable, beyond) = jis0208.toSeq.partition(_._1 < 8836)
            val checked             = reachable.map((pointer, codePoint) => (bytes(pair(pointer)*), text(codePoint)))
            assert(IndexTableFixtures.dataLines(EmbeddedIndexJis0208.text) == 7724)
            assert(checked.size == 7336)
            assert(beyond.size == 388 && beyond.forall(_._1 > 8835))
            assert(mismatches(checked).isEmpty)
        }
        "every lead byte from 0xA1 to 0xFE with each of the 256 trail bytes decodes as the algorithm says" in {
            val cases =
                for
                    lead  <- 0xa1 to 0xfe
                    trail <- 0 to 0xff
                yield
                    val expected =
                        if trail >= 0xa1 && trail <= 0xfe then
                            jis0208.get((lead - 0xa1) * 94 + trail - 0xa1).map(text(_)).getOrElse(text(0xfffd))
                        else text(0xfffd) + ascii(trail)
                    (bytes(lead, trail), expected)
            assert(mismatches(cases).isEmpty)
        }
    }

    "index jis0212" - {
        "every pointer the file maps decodes from 0x8F and its two bytes" in {
            val checked = jis0212.toSeq.map((pointer, codePoint) => (bytes(0x8f +: pair(pointer)*), text(codePoint)))
            assert(IndexTableFixtures.dataLines(EmbeddedIndexJis0212.text) == 6067)
            assert(checked.size == 6067)
            assert(mismatches(checked).isEmpty)
        }
        "0x8F and every second byte from 0xA1 to 0xFE with each of the 256 third bytes decodes as the algorithm says" in {
            val cases =
                for
                    lead  <- 0xa1 to 0xfe
                    trail <- 0 to 0xff
                yield
                    val expected =
                        if trail >= 0xa1 && trail <= 0xfe then
                            jis0212.get((lead - 0xa1) * 94 + trail - 0xa1).map(text(_)).getOrElse(text(0xfffd))
                        else text(0xfffd) + ascii(trail)
                    (bytes(0x8f, lead, trail), expected)
            assert(mismatches(cases).isEmpty)
        }
    }

    "half-width katakana" - {
        "0x8E with each of the 256 second bytes decodes as the algorithm says" in {
            val cases = (0 to 0xff).map { b =>
                (bytes(0x8e, b), if b >= 0xa1 && b <= 0xdf then text(0xff61 - 0xa1 + b) else text(0xfffd) + ascii(b))
            }
            assert(mismatches(cases).isEmpty)
        }
        "0x8E 0xA1 is U+FF61 and 0x8E 0xDF is U+FF9F" in {
            assert(decoder.decode(bytes(0x8e, 0xa1)) == text(0xff61))
            assert(decoder.decode(bytes(0x8e, 0xdf)) == text(0xff9f))
        }
    }

    "special cases" - {
        "0x8F with a second byte outside 0xA1 to 0xFE is an error, and an ASCII one is decoded again" in {
            val cases = (0 to 0xff).filterNot(b => b >= 0xa1 && b <= 0xfe).map(b => (bytes(0x8f, b), text(0xfffd) + ascii(b)))
            assert(mismatches(cases).isEmpty)
        }
        "the jis0212 index applies to the one character after 0x8F only" in {
            val (pointer, codePoint) = jis0212.minBy(_._1)
            assert(decoder.decode(bytes(0x8f +: pair(pointer) ++: pair(0)*)) == text(codePoint, jis0208(0)))
        }
        "each single byte decodes as the algorithm says" in {
            val cases = (0 to 0xff).map { b =>
                (bytes(b), if b < 0x80 then ascii(b) else text(0xfffd))
            }
            assert(mismatches(cases).isEmpty)
        }
        "the lead bytes are 0x8E, 0x8F and 0xA1 to 0xFE; 0x80 to 0x8D, 0x90 to 0xA0 and 0xFF are errors on their own" in {
            val notLead = (0x80 to 0xff).filterNot(b => b == 0x8e || b == 0x8f || (b >= 0xa1 && b <= 0xfe))
            val cases   = notLead.map(b => (bytes(b, 0x61), text(0xfffd, 0x61)))
            assert(notLead.size == 32)
            assert(mismatches(cases).isEmpty)
        }
        "the end of the input after a lead byte, or after 0x8F and a second byte, is one U+FFFD" in {
            assert(decoder.decode(bytes(0x61, 0xb0)) == text(0x61, 0xfffd))
            assert(decoder.decode(bytes(0x61, 0x8f, 0xb0)) == text(0x61, 0xfffd))
            assert(decoder.decode(bytes(0x61, 0x8e)) == text(0x61, 0xfffd))
        }
        "empty input decodes to the empty string" in {
            assert(decoder.decode(Span.empty[Byte]) == "")
        }
    }

    "web-platform-tests eucjp-decode-errors.html" - {
        "has 11 cases" in {
            assert(DecoderFixtures.eucJpErrors.size == 11)
        }
        DecoderFixtures.eucJpErrors.foreach { c =>
            c.title in {
                assert(decoder.decode(c.input) == c.expected)
            }
        }
    }

end EucJpDecoderTest
