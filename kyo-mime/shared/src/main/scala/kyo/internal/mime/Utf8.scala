package kyo.internal.mime

import kyo.*

/** UTF-8 encoding in which an unpaired surrogate becomes U+FFFD (EF BF BD), the replacement character, where every platform's
  * `getBytes` writes `?`: a reader can tell U+FFFD from text, and cannot tell that `?` from a written one.
  */
private[kyo] object Utf8:

    private inline val Replacement = 0xfffd

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
                val cp = if Character.isSurrogate(c) then Replacement else c.toInt
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
