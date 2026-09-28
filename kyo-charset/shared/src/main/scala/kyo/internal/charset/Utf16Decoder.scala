package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** UTF-16 in one byte order, or in the order a byte order mark selects, through the WHATWG shared UTF-16 decoder.
  *
  * The byte order comes from the label, as the MIME charset parameter is authoritative for mail:
  *
  *   - `UTF-16BE` and `UTF-16LE` decode in their own order whatever the text starts with, and a leading U+FEFF is content, a zero width
  *     no-break space (RFC 2781 sections 4.1 and 4.2, Unicode Standard D96 and D97). The WHATWG `decode` instead lets a byte order mark
  *     switch the order and removes it.
  *   - Bare `UTF-16` reads its first two bytes: FE FF selects big-endian and FF FE little-endian, and that mark is removed; anything else
  *     is big-endian and nothing is removed (RFC 2781 section 4.3). WHATWG maps the label to little-endian.
  *
  * Malformed input follows the WHATWG decoder: an unpaired surrogate becomes U+FFFD, a lead surrogate followed by a non-trail unit becomes
  * U+FFFD and the unit is decoded on its own, and an odd final byte or a lead surrogate at the end becomes one U+FFFD.
  */
final private[kyo] class Utf16Decoder private (val charset: Charset, order: Utf16Decoder.Order) extends Decoder:

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        val (bigEndian, start) =
            order match
                case Utf16Decoder.Order.BigEndian     => (true, 0)
                case Utf16Decoder.Order.LittleEndian  => (false, 0)
                case Utf16Decoder.Order.ByteOrderMark =>
                    if bytes.size >= 2 && (bytes(0) & 0xff) == 0xfe && (bytes(1) & 0xff) == 0xff then (true, 2)
                    else if bytes.size >= 2 && (bytes(0) & 0xff) == 0xff && (bytes(1) & 0xff) == 0xfe then (false, 2)
                    else (true, 0)
        // A lead surrogate waiting for its trail unit, or -1; confined to this call.
        var leadSurrogate = -1
        var i             = start
        while i + 1 < bytes.size do
            val first = bytes(i) & 0xff
            val next  = bytes(i + 1) & 0xff
            val unit  = if bigEndian then (first << 8) | next else (next << 8) | first
            i += 2
            if leadSurrogate >= 0 then
                val lead = leadSurrogate
                leadSurrogate = -1
                if unit >= 0xdc00 && unit <= 0xdfff then
                    discard(out.appendCodePoint(0x10000 + ((lead - 0xd800) << 10) + (unit - 0xdc00)))
                else
                    out.replacement(i)
                    if unit >= 0xd800 && unit <= 0xdbff then leadSurrogate = unit
                    else discard(out.append(unit.toChar))
                end if
            else if unit >= 0xd800 && unit <= 0xdbff then leadSurrogate = unit
            else if unit >= 0xdc00 && unit <= 0xdfff then out.replacement(i)
            else discard(out.append(unit.toChar))
            end if
        end while
        if leadSurrogate >= 0 || i < bytes.size then out.replacement(i)
    end run

end Utf16Decoder

private[kyo] object Utf16Decoder:

    private enum Order derives CanEqual:
        case BigEndian, LittleEndian, ByteOrderMark

    val bigEndian: Utf16Decoder     = new Utf16Decoder(Charset.Utf16Be, Order.BigEndian)
    val littleEndian: Utf16Decoder  = new Utf16Decoder(Charset.Utf16Le, Order.LittleEndian)
    val byteOrderMark: Utf16Decoder = new Utf16Decoder(Charset.Utf16, Order.ByteOrderMark)

end Utf16Decoder
