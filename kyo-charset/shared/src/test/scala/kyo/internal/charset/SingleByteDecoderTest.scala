package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

class SingleByteDecoderTest extends kyo.test.Test[Any]:

    private val everyByte: Span[Byte] = Span.from(Array.tabulate(256)(_.toByte))

    private def expected(raw: String, byte: Int): Char =
        if byte < 0x80 then byte.toChar
        else IndexTableFixtures.entries(raw).get(byte - 0x80).map(_.toChar).getOrElse('\ufffd')

    "each of the 256 byte values decodes to its index entry, and an unmapped one to U+FFFD" - {
        IndexTableFixtures.singleByte.foreach { fixture =>
            fixture.charset.name in {
                val decoder = Decoders.of(fixture.charset)
                val index   = IndexTableFixtures.entries(fixture.raw)
                val byByte  = (0 until 256).map(b => decoder.decode(Span.from(Array(b.toByte))))
                val wanted  = (0 until 256).map(b => expected(fixture.raw, b).toString)
                assert(byByte == wanted)
                assert(decoder.decode(everyByte) == wanted.mkString)
                assert(decoder.charset == fixture.charset)
                val unmapped = (0x80 until 0x100).filterNot(b => index.contains(b - 0x80))
                assert(unmapped.forall(b => byByte(b) == "\ufffd"))
            }
        }
    }

    "the encodings with unmapped bytes are the ones whose index has gaps" in {
        val withGaps = IndexTableFixtures.singleByte.filter(f => IndexTableFixtures.entries(f.raw).size < 128).map(_.charset.name).toSet
        assert(withGaps == Set(
            "ISO-8859-3",
            "ISO-8859-6",
            "ISO-8859-7",
            "ISO-8859-8",
            "ISO-8859-8-I",
            "windows-874",
            "windows-1253",
            "windows-1255",
            "windows-1257"
        ))
    }

    "empty input decodes to the empty string" in {
        val decoder = Decoders.of(Charset.Koi8R)
        assert(decoder.decode(Span.empty[Byte]) == "")
    }

    "a UTF-8 byte order mark is three ordinary bytes in a single-byte charset" in {
        val decoder = Decoders.of(Charset.Windows1252)
        assert(decoder.decode(Span.from(Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte))) == "ï»¿")
    }

end SingleByteDecoderTest
