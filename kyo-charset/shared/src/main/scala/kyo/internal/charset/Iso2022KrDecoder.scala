package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** ISO-2022-KR as RFC 1557 defines it: ASCII text, and segments between SO and SI of KS X 1001 pairs, each byte 0x21 to 0x7E, announced
  * once by the designator `ESC $ ) C` at the start of a line before the first SO.
  *
  * A pair `b1 b2` is the EUC-KR pair `b1 + 0x80`, `b2 + 0x80`, so it reaches exactly the KS X 1001 block of index EUC-KR. Input the
  * formal syntax excludes is one U+FFFD per malformed unit: a misplaced or repeated designator (which still designates), an SO before
  * any designator (which still shifts), SI outside a segment, an empty segment, a byte from 0x80 (a segment continues after it), and an
  * ASCII byte other than a pair or SI inside a segment, which ends the segment and is decoded again as ASCII. A line starts after LF,
  * with or without CR before it. The end of the input inside a segment is one U+FFFD, since the syntax requires the closing SI.
  */
private[kyo] object Iso2022KrDecoder extends Decoder:

    def charset: Charset = Charset.Iso2022Kr

    private inline val Esc = 0x1b
    private inline val So  = 0x0e
    private inline val Si  = 0x0f

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        val index          = IndexEucKr.table
        var shifted        = false
        var designated     = false
        var shiftSeen      = false
        var lineStart      = true
        var lead           = 0
        var segmentHasPair = false
        var i              = 0
        while i < bytes.size do
            val b = bytes(i) & 0xff
            i += 1
            if shifted then
                if lead != 0 then
                    val first = lead
                    lead = 0
                    if b >= 0x21 && b <= 0x7e then
                        val codePoint = index((first - 1) * 190 + b + 0x3f)
                        if codePoint >= 0 then discard(out.appendCodePoint(codePoint))
                        else out.replacement(i)
                    else
                        if b < 0x80 then i -= 1
                        out.replacement(i)
                    end if
                else if b >= 0x21 && b <= 0x7e then
                    lead = b
                    segmentHasPair = true
                else if b == Si then
                    if !segmentHasPair then out.replacement(i)
                    shifted = false
                else if b >= 0x80 then out.replacement(i)
                else
                    shifted = false
                    i -= 1
                    out.replacement(i)
                end if
            else if b == Esc then
                val isDesignator =
                    i + 2 < bytes.size &&
                        (bytes(i) & 0xff) == 0x24 &&
                        (bytes(i + 1) & 0xff) == 0x29 &&
                        (bytes(i + 2) & 0xff) == 0x43
                if isDesignator then
                    if designated || shiftSeen || !lineStart then out.replacement(i)
                    designated = true
                    lineStart = false
                    i += 3
                else
                    lineStart = false
                    out.replacement(i)
                end if
            else if b == So then
                if !designated then out.replacement(i)
                shifted = true
                shiftSeen = true
                segmentHasPair = false
                lineStart = false
            else if b == Si || b >= 0x80 then
                lineStart = false
                out.replacement(i)
            else
                lineStart = b == 0x0a
                discard(out.append(b.toChar))
            end if
        end while
        if shifted then out.replacement(i)
    end run

end Iso2022KrDecoder
