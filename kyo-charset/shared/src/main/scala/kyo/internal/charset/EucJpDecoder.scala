package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** The WHATWG EUC-JP decoder. Two bytes from 0xA1 to 0xFE give a pointer into index jis0208; 0x8F followed by two such bytes gives a
  * pointer into index jis0212; 0x8E followed by a byte from 0xA1 to 0xDF is half-width katakana, U+FF61 to U+FF9F. An unmapped or
  * malformed pair is U+FFFD, and its last byte is decoded again when it is ASCII.
  */
private[kyo] object EucJpDecoder extends Decoder:

    def charset: Charset = Charset.EucJp

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        val jis0208 = IndexJis0208.table
        val jis0212 = IndexJis0212.table
        var lead    = 0
        var in0212  = false
        var i       = 0
        while i < bytes.size do
            val b = bytes(i) & 0xff
            i += 1
            if lead == 0x8e && b >= 0xa1 && b <= 0xdf then
                lead = 0
                discard(out.append((0xff61 - 0xa1 + b).toChar))
            else if lead == 0x8f && b >= 0xa1 && b <= 0xfe then
                in0212 = true
                lead = b
            else if lead != 0 then
                val codePoint =
                    if lead >= 0xa1 && lead <= 0xfe && b >= 0xa1 && b <= 0xfe then
                        val pointer = (lead - 0xa1) * 94 + b - 0xa1
                        if in0212 then jis0212(pointer) else jis0208(pointer)
                    else -1
                lead = 0
                in0212 = false
                if codePoint >= 0 then discard(out.appendCodePoint(codePoint))
                else
                    if b < 0x80 then i -= 1
                    out.replacement(i)
                end if
            else if b < 0x80 then discard(out.append(b.toChar))
            else if b == 0x8e || b == 0x8f || (b >= 0xa1 && b <= 0xfe) then lead = b
            else out.replacement(i)
            end if
        end while
        if lead != 0 then out.replacement(i)
    end run

end EucJpDecoder
