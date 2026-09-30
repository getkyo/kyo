package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** The WHATWG gb18030 decoder, which the Encoding Standard also makes GBK's decoder, so both instances run the same algorithm and differ
  * only in the encoding they report.
  *
  * A byte from 0x81 to 0xFE starts a sequence. With a second byte from 0x30 to 0x39 it is a four-byte sequence, whose pointer goes through
  * the gb18030 ranges index; otherwise the second byte gives a two-byte pointer into index gb18030. Byte 0x80 alone is U+20AC. On an
  * error the decoder puts back the bytes the algorithm restores and decodes them again from the initial state: the second byte of a
  * two-byte pair when it is ASCII, the second byte and the rest after a broken four-byte sequence.
  */
final private[kyo] class Gb18030Decoder private (val charset: Charset) extends Decoder:

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        val index  = IndexGb18030.table
        val ranges = IndexGb18030Ranges.table
        // The decoder's state, named as in the WHATWG algorithm; confined to this call. A restore moves `i` back over the restored
        // bytes, which are always the ones just read, so each restore still drops at least the first byte and the loop ends.
        var first  = 0
        var second = 0
        var third  = 0
        var i      = 0
        while i < bytes.size do
            val b = bytes(i) & 0xff
            i += 1
            if third != 0 then
                if b < 0x30 || b > 0x39 then
                    i -= 3
                    out.replacement(i)
                else
                    val pointer   = (first - 0x81) * 12600 + (second - 0x30) * 1260 + (third - 0x81) * 10 + b - 0x30
                    val codePoint = Gb18030Decoder.rangesCodePoint(ranges, pointer)
                    if codePoint >= 0 then discard(out.appendCodePoint(codePoint)) else out.replacement(i)
                end if
                first = 0
                second = 0
                third = 0
            else if second != 0 then
                if b >= 0x81 && b <= 0xfe then third = b
                else
                    i -= 2
                    first = 0
                    second = 0
                    out.replacement(i)
                end if
            else if first != 0 then
                if b >= 0x30 && b <= 0x39 then second = b
                else
                    val lead = first
                    first = 0
                    val offset    = if b < 0x7f then 0x40 else 0x41
                    val codePoint =
                        if (b >= 0x40 && b <= 0x7e) || (b >= 0x80 && b <= 0xfe) then index((lead - 0x81) * 190 + b - offset) else -1
                    if codePoint >= 0 then discard(out.appendCodePoint(codePoint))
                    else
                        if b < 0x80 then i -= 1
                        out.replacement(i)
                    end if
                end if
            else if b < 0x80 then discard(out.append(b.toChar))
            else if b == 0x80 then discard(out.append(Gb18030Decoder.Euro))
            else if b <= 0xfe then first = b
            else out.replacement(i)
            end if
        end while
        if first != 0 || second != 0 || third != 0 then out.replacement(i)
    end run

end Gb18030Decoder

private[kyo] object Gb18030Decoder:

    val gb18030: Gb18030Decoder = new Gb18030Decoder(Charset.Gb18030)
    val gbk: Gb18030Decoder     = new Gb18030Decoder(Charset.Gbk)

    private val Euro = 0x20ac.toChar

    /** The WHATWG "index gb18030 ranges code point" for `pointer`, or `-1` where it is null. */
    private[charset] def rangesCodePoint(ranges: IndexTable.Ranges, pointer: Int): Int =
        if (pointer > 39419 && pointer < 189000) || pointer > 1237575 then -1
        else if pointer == 7457 then 0xe7c7
        else
            val at = ranges.lastAtOrBelow(pointer)
            ranges.codePoint(at) + pointer - ranges.pointer(at)
    end rangesCodePoint

end Gb18030Decoder
