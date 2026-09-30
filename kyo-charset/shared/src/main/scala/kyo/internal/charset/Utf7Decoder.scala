package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** UTF-7 as RFC 2152 defines it. Set D, Set O, space, TAB, CR and LF are themselves; `+-` is `+`; `+` and a base64 character start a
  * run of modified base64 carrying UTF-16 code units, most significant octet first, which ends at the first character outside the
  * base64 alphabet, a `-` there being absorbed.
  *
  * Ill-formed input is one U+FFFD per unit: a `+` followed by anything but base64 or `-` (the character is decoded again), a byte no
  * rule lets appear directly (`\`, `~`, the other controls, 0x80 up), a run whose leftover bits are not 0, 2 or 4 zero bits, and an
  * unpaired surrogate. A surrogate pair is matched within one run.
  */
private[kyo] object Utf7Decoder extends Decoder:

    def charset: Charset = Charset.Utf7

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        // The base64 run's state; confined to this call.
        var i                  = 0
        var inRun              = false
        var bits               = 0
        var count              = 0
        var high               = -1
        def unit(u: Int): Unit =
            if high >= 0 then
                if u >= 0xdc00 && u <= 0xdfff then
                    discard(out.appendCodePoint(0x10000 + ((high - 0xd800) << 10) + (u - 0xdc00)))
                    high = -1
                else
                    out.replacement(i)
                    high = -1
                    unit(u)
            else if u >= 0xd800 && u <= 0xdbff then high = u
            else if u >= 0xdc00 && u <= 0xdfff then out.replacement(i)
            else discard(out.append(u.toChar))
        def endRun(): Unit =
            if high >= 0 then out.replacement(i)
            if count >= 6 || bits != 0 then out.replacement(i)
            inRun = false
            bits = 0
            count = 0
            high = -1
        end endRun
        while i < bytes.size do
            val b = bytes(i) & 0xff
            if inRun then
                val value = Utf7Decoder.base64Value(b)
                if value >= 0 then
                    i += 1
                    bits = (bits << 6) | value
                    count += 6
                    if count >= 16 then
                        count -= 16
                        unit((bits >>> count) & 0xffff)
                        bits &= (1 << count) - 1
                    end if
                else
                    endRun()
                    if b == '-' then i += 1
                end if
            else
                i += 1
                if b == '+' then
                    val next = if i < bytes.size then bytes(i) & 0xff else -1
                    if next == '-' then
                        i += 1
                        discard(out.append('+'))
                    else if next >= 0 && Utf7Decoder.base64Value(next) >= 0 then inRun = true
                    else out.replacement(i)
                    end if
                else if Utf7Decoder.isDirect(b) then discard(out.append(b.toChar))
                else out.replacement(i)
                end if
            end if
        end while
        if inRun then endRun()
    end run

    /** The six-bit value of a base64 character (RFC 2045 alphabet, `+` and `/`), or -1. */
    private[charset] def base64Value(b: Int): Int =
        if b >= 'A' && b <= 'Z' then b - 'A'
        else if b >= 'a' && b <= 'z' then b - 'a' + 26
        else if b >= '0' && b <= '9' then b - '0' + 52
        else if b == '+' then 62
        else if b == '/' then 63
        else -1

    /** Set D, Set O, and the white space of Rule 3. */
    private[charset] def isDirect(b: Int): Boolean =
        (b >= 'A' && b <= 'Z') ||
            (b >= 'a' && b <= 'z') ||
            (b >= '0' && b <= '9') || DirectPunctuation.indexOf(b) >= 0

    // Set D's nine specials, Set O's twenty characters, then space, TAB, CR, LF.
    private val DirectPunctuation = "'(),-./:?!\"#$%&*;<=>@[]^_`{|} \t\r\n"

end Utf7Decoder
