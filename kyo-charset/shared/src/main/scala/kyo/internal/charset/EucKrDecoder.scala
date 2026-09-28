package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** The WHATWG EUC-KR decoder: a lead byte from 0x81 to 0xFE and a trail byte from 0x41 to 0xFE give a pointer into index EUC-KR, which
  * covers KS X 1001 and the Unified Hangul Code extensions (Windows code page 949). An unmapped pair is U+FFFD, and its trail byte is
  * decoded again when it is ASCII.
  */
private[kyo] object EucKrDecoder extends Decoder:

    def charset: Charset = Charset.EucKr

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        val index = IndexEucKr.table
        var lead  = 0
        var i     = 0
        while i < bytes.size do
            val b = bytes(i) & 0xff
            i += 1
            if lead != 0 then
                val codePoint = if b >= 0x41 && b <= 0xfe then index((lead - 0x81) * 190 + b - 0x41) else -1
                lead = 0
                if codePoint >= 0 then discard(out.appendCodePoint(codePoint))
                else
                    if b < 0x80 then i -= 1
                    out.replacement(i)
                end if
            else if b < 0x80 then discard(out.append(b.toChar))
            else if b >= 0x81 && b <= 0xfe then lead = b
            else out.replacement(i)
            end if
        end while
        if lead != 0 then out.replacement(i)
    end run

end EucKrDecoder
