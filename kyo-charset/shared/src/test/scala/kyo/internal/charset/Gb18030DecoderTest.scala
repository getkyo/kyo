package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

class Gb18030DecoderTest extends kyo.test.Test[Any]:

    import DecoderFixtures.*

    private val decoder = Gb18030Decoder.gb18030

    private lazy val index: Map[Int, Int] = IndexTableFixtures.entries(EmbeddedIndexGb18030.text)

    private lazy val ranges: IndexedSeq[(Int, Int)] =
        IndexTableFixtures.entries(EmbeddedIndexGb18030Ranges.text).toIndexedSeq.sortBy(_._1)

    // The inverse of the decoder's two-byte arithmetic: pointer = (lead - 0x81) * 190 + trail - (0x40 below 0x7F, else 0x41).
    private def twoBytes(pointer: Int): Span[Byte] =
        val trail = pointer % 190
        bytes(pointer / 190 + 0x81, trail + (if trail < 0x3f then 0x40 else 0x41))

    // The inverse of the four-byte arithmetic: pointer = (b1 - 0x81) * 12600 + (b2 - 0x30) * 1260 + (b3 - 0x81) * 10 + b4 - 0x30.
    private def fourBytes(pointer: Int): Seq[Int] =
        Seq(pointer / 12600 + 0x81, pointer % 12600 / 1260 + 0x30, pointer % 1260 / 10 + 0x81, pointer % 10 + 0x30)

    private def ascii(b: Int): String = if b < 0x80 then b.toChar.toString else ""

    private def mismatches(cases: Iterable[(Span[Byte], String)]): Seq[String] =
        cases.iterator.collect {
            case (input, expected) if decoder.decode(input) != expected =>
                s"${show(input)}: ${show(decoder.decode(input))}, expected ${show(expected)}"
        }.take(10).toSeq

    "index gb18030" - {
        "every pointer the file maps decodes from its two bytes to the file's code point" in {
            val checked = index.toSeq.map((pointer, codePoint) => (twoBytes(pointer), text(codePoint)))
            assert(IndexTableFixtures.dataLines(EmbeddedIndexGb18030.text) == 23940)
            assert(checked.size == 23940)
            assert(mismatches(checked).isEmpty)
        }
        "every lead byte with each of the 256 second bytes decodes as the algorithm says" in {
            val cases =
                for
                    lead   <- 0x81 to 0xfe
                    second <- 0 to 0xff
                yield
                    val expected =
                        if second >= 0x30 && second <= 0x39 then text(0xfffd)
                        else if (second >= 0x40 && second <= 0x7e) || (second >= 0x80 && second <= 0xfe) then
                            val offset = if second < 0x7f then 0x40 else 0x41
                            index.get((lead - 0x81) * 190 + second - offset).map(text(_)).getOrElse(text(0xfffd) + ascii(second))
                        else text(0xfffd) + ascii(second)
                    (bytes(lead, second), expected)
            assert(mismatches(cases).isEmpty)
        }
    }

    "index gb18030 ranges" - {
        "every range start the file lists decodes from its four bytes to the file's code point" in {
            val checked = ranges.map((pointer, codePoint) => (bytes(fourBytes(pointer)*), text(codePoint)))
            assert(IndexTableFixtures.dataLines(EmbeddedIndexGb18030Ranges.text) == 207)
            assert(checked.size == 207)
            assert(mismatches(checked).isEmpty)
        }
        "every one of the 1587600 four-byte sequences decodes to its ranges code point, or U+FFFD outside the ranges" in {
            val count    = 126 * 10 * 126 * 10
            val input    = new Array[Byte](count * 4)
            val expected = new java.lang.StringBuilder
            var range    = 0
            (0 until count).foreach { pointer =>
                val sequence = fourBytes(pointer)
                sequence.indices.foreach(k => input(pointer * 4 + k) = sequence(k).toByte)
                while range + 1 < ranges.size && ranges(range + 1)._1 <= pointer do range += 1
                val codePoint =
                    if (pointer > 39419 && pointer < 189000) || pointer > 1237575 then 0xfffd
                    else if pointer == 7457 then 0xe7c7
                    else ranges(range)._2 + pointer - ranges(range)._1
                discard(expected.appendCodePoint(codePoint))
            }
            assert(decoder.decode(Span.from(input)) == expected.toString)
        }
    }

    "special cases" - {
        "byte 0x80 is U+20AC" in {
            assert(decoder.decode(bytes(0x80)) == text(0x20ac))
        }
        "byte 0xFF is an error" in {
            assert(decoder.decode(bytes(0xff)) == text(0xfffd))
            assert(decoder.decode(bytes(0x61, 0xff, 0x62)) == text(0x61, 0xfffd, 0x62))
        }
        "a four-byte sequence goes through the ranges index" in {
            assert(decoder.decode(bytes(0x81, 0x30, 0x81, 0x30)) == text(0x80))
            assert(decoder.decode(bytes(0x81, 0x30, 0x81, 0x31)) == text(0x81))
        }
        "pointer 39419 is the last before the gap and decodes; 39420 is an error" in {
            assert(fourBytes(39419) == Seq(0x84, 0x31, 0xa4, 0x39))
            assert(decoder.decode(bytes(fourBytes(39419)*)) == text(0xffff))
            assert(decoder.decode(bytes(fourBytes(39420)*)) == text(0xfffd))
        }
        "pointers above 39419 and below 189000 are errors; 189000 is U+10000" in {
            assert(decoder.decode(bytes(fourBytes(188999)*)) == text(0xfffd))
            assert(decoder.decode(bytes(fourBytes(189000)*)) == text(0x10000))
        }
        "pointer 1237575 is U+10FFFF; above it is an error" in {
            assert(decoder.decode(bytes(fourBytes(1237575)*)) == text(0x10ffff))
            assert(decoder.decode(bytes(fourBytes(1237576)*)) == text(0xfffd))
            assert(decoder.decode(bytes(0xfe, 0x39, 0xfe, 0x39)) == text(0xfffd))
        }
        "pointer 7457 is U+E7C7, which the ranges arithmetic would not give" in {
            assert(fourBytes(7457) == Seq(0x81, 0x35, 0xf4, 0x37))
            assert(decoder.decode(bytes(0x81, 0x35, 0xf4, 0x37)) == text(0xe7c7))
        }
        "a supplementary code point is appended as a surrogate pair" in {
            val decoded = decoder.decode(bytes(fourBytes(189000)*))
            assert(decoded.length == 2)
            assert(Character.isHighSurrogate(decoded.charAt(0)) && Character.isLowSurrogate(decoded.charAt(1)))
        }
        "an ASCII second byte that is not a digit ends the pair as an error and is decoded again" in {
            assert(decoder.decode(bytes(0x81, 0x28)) == text(0xfffd, 0x28))
            assert(decoder.decode(bytes(0x81, 0x7f)) == text(0xfffd, 0x7f))
        }
        "a non-ASCII second byte outside the trail range is consumed by the error" in {
            assert(decoder.decode(bytes(0x81, 0xff)) == text(0xfffd))
        }
        "a third byte outside 0x81 to 0xFE is an error that puts back the second and third bytes" in {
            assert(decoder.decode(bytes(0x81, 0x30, 0x28)) == text(0xfffd, 0x30, 0x28))
            assert(decoder.decode(bytes(0x81, 0x30, 0xff)) == text(0xfffd, 0x30, 0xfffd))
        }
        "a fourth byte that is not a digit is an error that puts back the second, third and fourth bytes" in {
            assert(decoder.decode(bytes(0x81, 0x30, 0x81, 0x28)) == text(0xfffd, 0x30, 0xfffd, 0x28))
            assert(decoder.decode(bytes(0x81, 0x30, 0xa1, 0xa1)) == text(0xfffd, 0x30, index((0xa1 - 0x81) * 190 + 0xa1 - 0x41)))
        }
        "the end of the input inside a sequence of two, three or four bytes is one U+FFFD" in {
            assert(decoder.decode(bytes(0x81)) == text(0xfffd))
            assert(decoder.decode(bytes(0x81, 0x30)) == text(0xfffd))
            assert(decoder.decode(bytes(0x81, 0x30, 0x81)) == text(0xfffd))
            assert(decoder.decode(bytes(0x61, 0x81, 0x30, 0x81)) == text(0x61, 0xfffd))
        }
        "0xA3 0xA0 is U+3000, as the index maps it for deployed content" in {
            assert(decoder.decode(bytes(0xa3, 0xa0)) == text(0x3000))
        }
        "empty input decodes to the empty string" in {
            assert(decoder.decode(Span.empty[Byte]) == "")
        }
    }

    "GBK" - {
        "is the gb18030 decoder, reporting GBK" in {
            assert(Decoders.of(Charset.Gbk).charset == Charset.Gbk)
            assert(Decoders.of(Charset.Gb18030).charset == Charset.Gb18030)
            assert(Gb18030Decoder.gbk.decode(bytes(0x80)) == text(0x20ac))
            assert(Gb18030Decoder.gbk.decode(bytes(0x81, 0x30, 0x81, 0x30)) == text(0x80))
        }
        "decodes every two-byte pair as gb18030 does" in {
            val input = Span.from((for
                lead   <- 0x81 to 0xfe
                second <- 0 to 0xff
                b      <- Seq(lead, second)
            yield b.toByte).toArray)
            assert(Gb18030Decoder.gbk.decode(input) == decoder.decode(input))
        }
    }

    "gb2312, which WHATWG maps to GBK" - {
        "A1A4 decodes to U+00B7 as WHATWG's index has it; the GB2312 standard has U+30FB" in {
            val gb2312 = Charset.resolve("gb2312").getOrElse(fail("no charset for gb2312"))
            assert(gb2312 == Charset.Gbk)
            assert(gb2312.decode(bytes(0xa1, 0xa4)) == text(0xb7))
        }
        "A1AA decodes to U+2014 as WHATWG's index has it; the GB2312 standard has U+2015" in {
            val gb2312 = Charset.resolve("gb2312").getOrElse(fail("no charset for gb2312"))
            assert(gb2312.decode(bytes(0xa1, 0xaa)) == text(0x2014))
        }
    }

    "web-platform-tests gb18030-decoder.any.js" - {
        "has 68 cases with literal byte inputs" in {
            assert(DecoderFixtures.gb18030.size == 68)
        }
        DecoderFixtures.gb18030.foreach { c =>
            c.title in {
                assert(decoder.decode(c.input) == c.expected)
                assert(Gb18030Decoder.gbk.decode(c.input) == c.expected)
            }
        }
    }

end Gb18030DecoderTest
