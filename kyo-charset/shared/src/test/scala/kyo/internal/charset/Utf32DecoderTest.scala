package kyo.internal.charset

import kyo.*

class Utf32DecoderTest extends kyo.test.Test[Any]:

    private def units(bigEndian: Boolean, values: Long*): Span[Byte] =
        Span.from(values.flatMap { u =>
            val be = Seq(((u >> 24) & 0xff).toByte, ((u >> 16) & 0xff).toByte, ((u >> 8) & 0xff).toByte, (u & 0xff).toByte)
            if bigEndian then be else be.reverse
        }.toArray)

    private def bytes(values: Int*): Span[Byte] = Span.from(values.map(_.toByte).toArray)

    "in each byte order" - {
        Seq(true, false).foreach { bigEndian =>
            val decoder = if bigEndian then Utf32Decoder.bigEndian else Utf32Decoder.littleEndian
            val name    = decoder.charset.name
            s"$name decodes BMP and supplementary code points" in {
                assert(decoder.decode(units(bigEndian, 0x61L, 0x20acL, 0x1f600L, 0x10ffffL)) == "a€😀\udbff\udfff")
            }
            s"$name turns a code point above U+10FFFF into U+FFFD" in {
                assert(decoder.decode(units(bigEndian, 0x61L, 0x110000L, 0xffffffffL, 0x62L)) == "a\ufffd\ufffdb")
            }
            s"$name turns a surrogate code point into U+FFFD" in {
                assert(decoder.decode(units(bigEndian, 0xd800L, 0xdfffL, 0x61L)) == "\ufffd\ufffda")
            }
            s"$name turns one to three bytes left at the end into one U+FFFD" in {
                val one = units(bigEndian, 0x61L).toArray
                assert(decoder.decode(Span.from(one :+ 0.toByte)) == "a\ufffd")
                assert(decoder.decode(Span.from(one ++ Array[Byte](0, 0))) == "a\ufffd")
                assert(decoder.decode(Span.from(one ++ Array[Byte](0, 0, 0))) == "a\ufffd")
            }
            s"$name keeps a leading U+FEFF as content" in {
                assert(decoder.decode(units(bigEndian, 0xfeffL, 0x61L)) == "\ufeffa")
            }
        }
    }

    "bare UTF-32" - {
        val decoder = Utf32Decoder.byteOrderMark
        "with 00 00 FE FF is big-endian and drops the mark" in {
            assert(decoder.decode(bytes(0, 0, 0xfe, 0xff, 0, 0, 0, 0x61)) == "a")
        }
        "with FF FE 00 00 is little-endian and drops the mark" in {
            assert(decoder.decode(bytes(0xff, 0xfe, 0, 0, 0x61, 0, 0, 0)) == "a")
        }
        "without a mark is big-endian (Unicode Standard D101)" in {
            assert(decoder.decode(bytes(0, 0, 0, 0x61, 0, 1, 0xf6, 0)) == "a😀")
        }
        "drops only the first mark" in {
            assert(decoder.decode(bytes(0, 0, 0xfe, 0xff, 0, 0, 0xfe, 0xff)) == "\ufeff")
        }
    }

end Utf32DecoderTest
