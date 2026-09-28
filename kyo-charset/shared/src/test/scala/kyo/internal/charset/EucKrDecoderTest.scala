package kyo.internal.charset

import kyo.*

class EucKrDecoderTest extends kyo.test.Test[Any]:

    import DecoderFixtures.*

    private val decoder = EucKrDecoder

    private lazy val index: Map[Int, Int] = IndexTableFixtures.entries(EmbeddedIndexEucKr.text)

    // The inverse of the decoder's arithmetic: pointer = (lead - 0x81) * 190 + trail - 0x41.
    private def twoBytes(pointer: Int): Span[Byte] = bytes(pointer / 190 + 0x81, pointer % 190 + 0x41)

    private def ascii(b: Int): String = if b < 0x80 then b.toChar.toString else ""

    private def mismatches(cases: Iterable[(Span[Byte], String)]): Seq[String] =
        cases.iterator.collect {
            case (input, expected) if decoder.decode(input) != expected =>
                s"${show(input)}: ${show(decoder.decode(input))}, expected ${show(expected)}"
        }.take(10).toSeq

    "index EUC-KR" - {
        "every pointer the file maps decodes from its two bytes to the file's code point" in {
            val checked = index.toSeq.map((pointer, codePoint) => (twoBytes(pointer), text(codePoint)))
            assert(IndexTableFixtures.dataLines(EmbeddedIndexEucKr.text) == 17048)
            assert(checked.size == 17048)
            assert(mismatches(checked).isEmpty)
        }
        "every lead byte with each of the 256 trail bytes decodes as the algorithm says" in {
            val cases =
                for
                    lead  <- 0x81 to 0xfe
                    trail <- 0 to 0xff
                yield
                    val expected =
                        if trail >= 0x41 && trail <= 0xfe then
                            index.get((lead - 0x81) * 190 + trail - 0x41).map(text(_)).getOrElse(text(0xfffd) + ascii(trail))
                        else text(0xfffd) + ascii(trail)
                    (bytes(lead, trail), expected)
            assert(mismatches(cases).isEmpty)
        }
        "each single byte decodes as the algorithm says" in {
            val cases = (0 to 0xff).map(b => (bytes(b), if b < 0x80 then ascii(b) else text(0xfffd)))
            assert(mismatches(cases).isEmpty)
        }
    }

    "special cases" - {
        "the lead range is 0x81 to 0xFE and the trail range 0x41 to 0xFE" in {
            assert(decoder.decode(bytes(0x81, 0x41)) == text(index(0)))
            assert(decoder.decode(bytes(0xfd, 0xfe)) == text(index(23749)))
            assert(decoder.decode(bytes(0x80, 0x41)) == text(0xfffd, 0x41))
            assert(decoder.decode(bytes(0xff, 0x41)) == text(0xfffd, 0x41))
        }
        "an ASCII trail byte outside the trail range or of an unmapped pair is decoded again after the U+FFFD" in {
            assert(decoder.decode(bytes(0x81, 0x40)) == text(0xfffd, 0x40))
            assert(!index.contains((0x81 - 0x81) * 190 + 0x5b - 0x41))
            assert(decoder.decode(bytes(0x81, 0x5b)) == text(0xfffd, 0x5b))
        }
        "a non-ASCII byte outside the trail range is consumed by the U+FFFD" in {
            assert(decoder.decode(bytes(0x81, 0xff)) == text(0xfffd))
        }
        "a lead byte at the end of the input is one U+FFFD" in {
            assert(decoder.decode(bytes(0x61, 0xb0)) == text(0x61, 0xfffd))
        }
        "empty input decodes to the empty string" in {
            assert(decoder.decode(Span.empty[Byte]) == "")
        }
    }

    "web-platform-tests euckr-decode-errors.html" - {
        "has 8 cases" in {
            assert(DecoderFixtures.eucKrErrors.size == 8)
        }
        DecoderFixtures.eucKrErrors.foreach { c =>
            c.title in {
                assert(decoder.decode(c.input) == c.expected)
            }
        }
    }

end EucKrDecoderTest
