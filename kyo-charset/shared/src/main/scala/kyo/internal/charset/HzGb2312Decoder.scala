package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** HZ as RFC 1843 defines it: ASCII text in which `~~` is `~`, `~` and a line feed is a line continuation that produces nothing, and
  * `~{` starts a run of GB 2312 pairs that `~}` ends.
  *
  * Inside a run every byte 0x21 to 0x7D starts a pair and 0x21 to 0x7E ends one. A pair with first byte 0x21 to 0x77 is the GB pair
  * `b1 + 0x80`, `b2 + 0x80` and goes through index gb18030, as the same bytes do under the GBK label; a pair with first byte 0x78 to
  * 0x7D is in a row GB 2312 does not have and is one U+FFFD for both bytes, so the pairs after it stay aligned. Malformed input is one
  * U+FFFD per unit: another byte after `~`, a byte from 0x80 (the run continues), a pair cut short, and a control byte inside a run,
  * which the RFC keeps to ASCII mode and which therefore ends the run and is decoded again as ASCII. The end of the input inside a run
  * is one U+FFFD, since a run is enclosed in `~{` and `~}`.
  */
private[kyo] object HzGb2312Decoder extends Decoder:

    def charset: Charset = Charset.HzGb2312

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        val index = IndexGb18030.table
        var gb    = false
        var tilde = false
        var lead  = 0
        var i     = 0
        while i < bytes.size do
            val b = bytes(i) & 0xff
            i += 1
            if !gb then
                if tilde then
                    tilde = false
                    if b == '~' then discard(out.append('~'))
                    else if b == '{' then gb = true
                    else if b != '\n' then
                        if b < 0x80 then i -= 1
                        out.replacement(i)
                    end if
                else if b == '~' then tilde = true
                else if b < 0x80 then discard(out.append(b.toChar))
                else out.replacement(i)
            else if tilde then
                tilde = false
                if b == '}' then gb = false
                else
                    if b < 0x80 then i -= 1
                    out.replacement(i)
                end if
            else if lead != 0 then
                val first = lead
                lead = 0
                if b >= 0x21 && b <= 0x7e then
                    // Rows 0x78 to 0x7D are not GB 2312, although index gb18030 maps their pointers.
                    val codePoint = if first <= 0x77 then index((first - 1) * 190 + b + 0x3f) else -1
                    if codePoint >= 0 then discard(out.appendCodePoint(codePoint))
                    else out.replacement(i)
                else
                    if b < 0x80 then i -= 1
                    out.replacement(i)
                end if
            else if b == '~' then tilde = true
            else if b >= 0x21 && b <= 0x7d then lead = b
            else if b <= 0x20 || b == 0x7f then
                gb = false
                i -= 1
                out.replacement(i)
            else out.replacement(i)
            end if
        end while
        if gb || tilde then out.replacement(i)
    end run

end HzGb2312Decoder
