package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** The WHATWG Big5 decoder: a lead byte from 0x81 to 0xFE and a trail byte from 0x40 to 0x7E or 0xA1 to 0xFE give a pointer into index
  * Big5, whose entries above U+FFFF are appended as surrogate pairs. Pointers 1133, 1135, 1164 and 1166 each decode to two code points,
  * a base letter and a combining mark, which the index cannot hold. An unmapped pair is U+FFFD, and its trail byte is decoded again when
  * it is ASCII.
  */
private[kyo] object Big5Decoder extends Decoder:

    def charset: Charset = Charset.Big5

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        val index = IndexBig5.table
        var lead  = 0
        var i     = 0
        while i < bytes.size do
            val b = bytes(i) & 0xff
            i += 1
            if lead != 0 then
                val offset  = if b < 0x7f then 0x40 else 0x62
                val pointer = if (b >= 0x40 && b <= 0x7e) || (b >= 0xa1 && b <= 0xfe) then (lead - 0x81) * 157 + b - offset else -1
                lead = 0
                pointer match
                    case 1133 => discard(out.append(0x00ca.toChar).append(0x0304.toChar))
                    case 1135 => discard(out.append(0x00ca.toChar).append(0x030c.toChar))
                    case 1164 => discard(out.append(0x00ea.toChar).append(0x0304.toChar))
                    case 1166 => discard(out.append(0x00ea.toChar).append(0x030c.toChar))
                    case _    =>
                        val codePoint = if pointer < 0 then -1 else index(pointer)
                        if codePoint >= 0 then discard(out.appendCodePoint(codePoint))
                        else
                            if b < 0x80 then i -= 1
                            out.replacement(i)
                        end if
                end match
            else if b < 0x80 then discard(out.append(b.toChar))
            else if b >= 0x81 && b <= 0xfe then lead = b
            else out.replacement(i)
            end if
        end while
        if lead != 0 then out.replacement(i)
    end run

end Big5Decoder
