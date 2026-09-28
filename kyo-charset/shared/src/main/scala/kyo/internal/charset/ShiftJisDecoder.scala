package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** The WHATWG Shift_JIS decoder. Bytes up to 0x80 are themselves and 0xA1 to 0xDF are half-width katakana, U+FF61 to U+FF9F. A lead
  * byte from 0x81 to 0x9F or 0xE0 to 0xFC and a trail byte from 0x40 to 0x7E or 0x80 to 0xFC give a pointer into index jis0208, except
  * that pointers 8836 to 10715 are the Windows end-user-defined characters, U+E000 to U+E757. An unmapped pair is U+FFFD, and its trail
  * byte is decoded again when it is ASCII.
  */
private[kyo] object ShiftJisDecoder extends Decoder:

    def charset: Charset = Charset.ShiftJis

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        val index = IndexJis0208.table
        var lead  = 0
        var i     = 0
        while i < bytes.size do
            val b = bytes(i) & 0xff
            i += 1
            if lead != 0 then
                val offset     = if b < 0x7f then 0x40 else 0x41
                val leadOffset = if lead < 0xa0 then 0x81 else 0xc1
                val pointer    =
                    if (b >= 0x40 && b <= 0x7e) || (b >= 0x80 && b <= 0xfc) then (lead - leadOffset) * 188 + b - offset else -1
                lead = 0
                val codePoint =
                    if pointer >= 8836 && pointer <= 10715 then 0xe000 - 8836 + pointer
                    else if pointer < 0 then -1
                    else index(pointer)
                if codePoint >= 0 then discard(out.appendCodePoint(codePoint))
                else
                    if b < 0x80 then i -= 1
                    out.replacement(i)
                end if
            else if b <= 0x80 then discard(out.append(b.toChar))
            else if b >= 0xa1 && b <= 0xdf then discard(out.append((0xff61 - 0xa1 + b).toChar))
            else if (b >= 0x81 && b <= 0x9f) || (b >= 0xe0 && b <= 0xfc) then lead = b
            else out.replacement(i)
            end if
        end while
        if lead != 0 then out.replacement(i)
    end run

end ShiftJisDecoder
