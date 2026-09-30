package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** UTF-8: the WHATWG UTF-8 decoder, and the WHATWG encoder.
  *
  * Decoding follows the WHATWG algorithm exactly, which replaces each maximal subpart of an ill-formed sequence with one U+FFFD (Unicode
  * Standard section 3.9): an overlong form, a surrogate, a code point above U+10FFFF, a stray continuation byte and a sequence cut short
  * by the end of the input each produce U+FFFD at the point the standard says. A leading byte order mark (EF BB BF) is removed, as the
  * WHATWG "UTF-8 decode" does; UTF-8 has no byte order, so a leading one is only a signature.
  *
  * Encoding turns each lone surrogate into U+FFFD before encoding it, the WHATWG encoder's conversion to a scalar value string, so the
  * output is always well-formed UTF-8. The JDK's `getBytes` writes `?` there instead, and is not shared across platforms.
  */
private[kyo] object Utf8 extends Decoder:

    def charset: Charset = Charset.Utf8

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        val start =
            if bytes.size >= 3 && (bytes(0) & 0xff) == 0xef && (bytes(1) & 0xff) == 0xbb && (bytes(2) & 0xff) == 0xbf then 3 else 0
        // The decoder's state, named as in the WHATWG algorithm; confined to this call.
        var codePoint = 0
        var needed    = 0
        var seen      = 0
        var lower     = 0x80
        var upper     = 0xbf
        var i         = start
        while i < bytes.size do
            val b = bytes(i) & 0xff
            if needed == 0 then
                if b <= 0x7f then discard(out.append(b.toChar))
                else if b >= 0xc2 && b <= 0xdf then
                    needed = 1
                    codePoint = b & 0x1f
                else if b >= 0xe0 && b <= 0xef then
                    if b == 0xe0 then lower = 0xa0
                    if b == 0xed then upper = 0x9f
                    needed = 2
                    codePoint = b & 0xf
                else if b >= 0xf0 && b <= 0xf4 then
                    if b == 0xf0 then lower = 0x90
                    if b == 0xf4 then upper = 0x8f
                    needed = 3
                    codePoint = b & 0x7
                else out.replacement(i)
                end if
                i += 1
            else if b < lower || b > upper then
                // An unexpected byte ends the sequence as an error and is itself processed again from the initial state.
                codePoint = 0
                needed = 0
                seen = 0
                lower = 0x80
                upper = 0xbf
                out.replacement(i)
            else
                lower = 0x80
                upper = 0xbf
                codePoint = (codePoint << 6) | (b & 0x3f)
                seen += 1
                if seen == needed then
                    discard(out.appendCodePoint(codePoint))
                    codePoint = 0
                    needed = 0
                    seen = 0
                end if
                i += 1
            end if
        end while
        if needed != 0 then out.replacement(i)
    end run

    def encode(text: String): Span[Byte] =
        val out = new Array[Byte](encodedLength(text))
        var i   = 0
        var o   = 0
        while i < text.length do
            val c = text.charAt(i)
            if c < 0x80 then
                out(o) = c.toByte
                o += 1
                i += 1
            else if c < 0x800 then
                out(o) = (0xc0 | (c >> 6)).toByte
                out(o + 1) = (0x80 | (c & 0x3f)).toByte
                o += 2
                i += 1
            else if isPairAt(text, i) then
                val cp = Character.toCodePoint(c, text.charAt(i + 1))
                out(o) = (0xf0 | (cp >> 18)).toByte
                out(o + 1) = (0x80 | ((cp >> 12) & 0x3f)).toByte
                out(o + 2) = (0x80 | ((cp >> 6) & 0x3f)).toByte
                out(o + 3) = (0x80 | (cp & 0x3f)).toByte
                o += 4
                i += 2
            else
                val cp = if Character.isSurrogate(c) then Decoder.Replacement.toInt else c.toInt
                out(o) = (0xe0 | (cp >> 12)).toByte
                out(o + 1) = (0x80 | ((cp >> 6) & 0x3f)).toByte
                out(o + 2) = (0x80 | (cp & 0x3f)).toByte
                o += 3
                i += 1
            end if
        end while
        // Unsafe: `out` is local and fully written; only the span escapes.
        Span.fromUnsafe(out)
    end encode

    // A lone surrogate is encoded as U+FFFD, three bytes, the same length as any other unit from U+0800 up.
    private def encodedLength(text: String): Int =
        var length = 0
        var i      = 0
        while i < text.length do
            val c = text.charAt(i)
            if c < 0x80 then
                length += 1
                i += 1
            else if c < 0x800 then
                length += 2
                i += 1
            else if isPairAt(text, i) then
                length += 4
                i += 2
            else
                length += 3
                i += 1
            end if
        end while
        length
    end encodedLength

    private def isPairAt(text: String, i: Int): Boolean =
        Character.isHighSurrogate(text.charAt(i)) && i + 1 < text.length && Character.isLowSurrogate(text.charAt(i + 1))

end Utf8
