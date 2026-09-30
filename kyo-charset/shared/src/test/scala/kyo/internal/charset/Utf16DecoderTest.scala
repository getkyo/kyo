package kyo.internal.charset

import kyo.*

class Utf16DecoderTest extends kyo.test.Test[Any]:

    // Code units written out as bytes in the given order, so each case states its input as UTF-16 code units.
    private def units(bigEndian: Boolean, values: Int*): Span[Byte] =
        Span.from(values.flatMap { u =>
            val hi = ((u >> 8) & 0xff).toByte
            val lo = (u & 0xff).toByte
            if bigEndian then Seq(hi, lo) else Seq(lo, hi)
        }.toArray)

    private def bytes(values: Int*): Span[Byte] = Span.from(values.map(_.toByte).toArray)

    "in each byte order" - {
        Seq(true, false).foreach { bigEndian =>
            val decoder = if bigEndian then Utf16Decoder.bigEndian else Utf16Decoder.littleEndian
            val name    = decoder.charset.name
            s"$name decodes BMP and supplementary characters" in {
                assert(decoder.decode(units(bigEndian, 0x61, 0x20ac, 0xd83d, 0xde00)) == "a€😀")
            }
            s"$name turns an unpaired lead surrogate into U+FFFD and keeps the next unit" in {
                assert(decoder.decode(units(bigEndian, 0x61, 0x62, 0xd800, 0x77, 0x78)) == "ab\ufffdwx")
            }
            s"$name turns a lead followed by another lead into U+FFFD and pairs the second" in {
                assert(decoder.decode(units(bigEndian, 0xd800, 0xd83d, 0xde00)) == "\ufffd😀")
                assert(decoder.decode(units(bigEndian, 0xd800, 0xd800)) == "\ufffd\ufffd")
            }
            s"$name turns an unpaired trail surrogate into U+FFFD" in {
                assert(decoder.decode(units(bigEndian, 0x61, 0x62, 0xdfff, 0x77, 0x78)) == "ab\ufffdwx")
                assert(decoder.decode(units(bigEndian, 0xdfff, 0xd800)) == "\ufffd\ufffd")
            }
            s"$name turns a lead surrogate at the end into one U+FFFD" in {
                assert(decoder.decode(units(bigEndian, 0xd800)) == "\ufffd")
            }
            s"$name turns an odd final byte into one U+FFFD" in {
                assert(decoder.decode(Span.from(units(bigEndian, 0x61).toArray :+ 0x62.toByte)) == "a\ufffd")
            }
            s"$name turns a lead surrogate and an odd final byte into one U+FFFD" in {
                assert(decoder.decode(Span.from(units(bigEndian, 0xd800).toArray :+ 0x62.toByte)) == "\ufffd")
            }
            s"$name keeps a leading U+FEFF as content, and does not let a byte order mark change the order" in {
                assert(decoder.decode(units(bigEndian, 0xfeff, 0x61)) == "\ufeffa")
                assert(decoder.decode(units(bigEndian, 0xfffe, 0x61)) == "\ufffea")
            }
            s"$name decodes empty input to the empty string" in {
                assert(decoder.decode(Span.empty[Byte]) == "")
            }
        }
    }

    "bare UTF-16" - {
        val decoder = Utf16Decoder.byteOrderMark
        "with FE FF is big-endian and drops the mark" in {
            assert(decoder.decode(bytes(0xfe, 0xff, 0x00, 0x61, 0x20, 0xac)) == "a€")
        }
        "with FF FE is little-endian and drops the mark" in {
            assert(decoder.decode(bytes(0xff, 0xfe, 0x61, 0x00, 0xac, 0x20)) == "a€")
        }
        "without a mark is big-endian (RFC 2781 section 4.3), where WHATWG would read little-endian" in {
            assert(decoder.decode(bytes(0x00, 0x61, 0x20, 0xac)) == "a€")
        }
        "drops only the first mark" in {
            assert(decoder.decode(bytes(0xfe, 0xff, 0xfe, 0xff, 0x00, 0x61)) == "\ufeffa")
        }
        "with only a mark decodes to the empty string" in {
            assert(decoder.decode(bytes(0xff, 0xfe)) == "")
        }
    }

end Utf16DecoderTest
