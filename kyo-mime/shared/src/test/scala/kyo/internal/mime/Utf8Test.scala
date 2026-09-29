package kyo.internal.mime

import kyo.*

class Utf8Test extends kyo.test.Test[Any]:

    private def bytes(text: String): Seq[Int] = Utf8.encode(text).toArray.toSeq.map(_ & 0xff)

    "one, two, three and four byte sequences" in {
        assert(bytes("") == Seq.empty)
        assert(bytes("aZ~") == Seq(0x61, 0x5a, 0x7e))
        assert(bytes("\u0080é߿") == Seq(0xc2, 0x80, 0xc3, 0xa9, 0xdf, 0xbf))
        assert(bytes("ࠀ€￿") == Seq(0xe0, 0xa0, 0x80, 0xe2, 0x82, 0xac, 0xef, 0xbf, 0xbf))
        assert(bytes("😀􏿿") == Seq(0xf0, 0x9f, 0x98, 0x80, 0xf4, 0x8f, 0xbf, 0xbf))
    }

    "an unpaired surrogate is U+FFFD on every platform: a lone lead, a lone trail, a lead at the end, two leads" in {
        val fffd = Seq(0xef, 0xbf, 0xbd)
        assert(bytes("a\ud800.") == Seq(0x61) ++ fffd ++ Seq(0x2e))
        assert(bytes("a\udc00.") == Seq(0x61) ++ fffd ++ Seq(0x2e))
        assert(bytes("a\ud800") == Seq(0x61) ++ fffd)
        assert(bytes("\ud800𐈀") == fffd ++ Seq(0xf0, 0x90, 0x88, 0x80))
    }

    "agrees with the platform encoder on well-formed text" in {
        val text = "plain, café, €, 😀, \u0000"
        assert(bytes(text) == text.getBytes("UTF-8").toSeq.map(_ & 0xff))
    }

end Utf8Test
