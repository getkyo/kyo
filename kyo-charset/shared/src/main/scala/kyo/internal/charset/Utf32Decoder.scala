package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** UTF-32 in one byte order, or in the order a byte order mark selects. WHATWG does not define UTF-32; the rules are the Unicode
  * Standard's.
  *
  *   - `UTF-32BE` and `UTF-32LE` decode in their own order, and a leading U+FEFF is content, a zero width no-break space (Unicode
  *     Standard D99 and D100).
  *   - Bare `UTF-32` reads its first four bytes: 00 00 FE FF selects big-endian and FF FE 00 00 little-endian, and that mark is removed;
  *     anything else is big-endian and nothing is removed (Unicode Standard D101).
  *
  * A four-byte unit above U+10FFFF or in the surrogate range is ill-formed (Unicode Standard D90) and becomes one U+FFFD, and one to three
  * bytes left at the end become one U+FFFD.
  */
final private[kyo] class Utf32Decoder private (val charset: Charset, order: Utf32Decoder.Order) extends Decoder:

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        def at(i: Int): Int    = bytes(i) & 0xff
        val (bigEndian, start) =
            order match
                case Utf32Decoder.Order.BigEndian     => (true, 0)
                case Utf32Decoder.Order.LittleEndian  => (false, 0)
                case Utf32Decoder.Order.ByteOrderMark =>
                    if bytes.size >= 4 && at(0) == 0 && at(1) == 0 && at(2) == 0xfe && at(3) == 0xff then (true, 4)
                    else if bytes.size >= 4 && at(0) == 0xff && at(1) == 0xfe && at(2) == 0 && at(3) == 0 then (false, 4)
                    else (true, 0)
        @scala.annotation.tailrec
        def loop(i: Int): Unit =
            if i + 3 < bytes.size then
                val unit =
                    if bigEndian then (at(i).toLong << 24) | (at(i + 1) << 16) | (at(i + 2) << 8) | at(i + 3)
                    else (at(i + 3).toLong << 24) | (at(i + 2) << 16) | (at(i + 1) << 8) | at(i)
                if unit > 0x10ffff || (unit >= 0xd800 && unit <= 0xdfff) then out.replacement(i)
                else discard(out.appendCodePoint(unit.toInt))
                loop(i + 4)
            else if i < bytes.size then out.replacement(i)
        loop(start)
    end run

end Utf32Decoder

private[kyo] object Utf32Decoder:

    private enum Order derives CanEqual:
        case BigEndian, LittleEndian, ByteOrderMark

    val bigEndian: Utf32Decoder     = new Utf32Decoder(Charset.Utf32Be, Order.BigEndian)
    val littleEndian: Utf32Decoder  = new Utf32Decoder(Charset.Utf32Le, Order.LittleEndian)
    val byteOrderMark: Utf32Decoder = new Utf32Decoder(Charset.Utf32, Order.ByteOrderMark)

end Utf32Decoder
