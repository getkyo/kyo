package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** The WHATWG single-byte decoder over one index: bytes 0x00 to 0x7F are the same code point, and a byte from 0x80 up is the index entry
  * at `byte - 0x80`, or U+FFFD where the index leaves it unmapped. Every single-byte encoding is this one algorithm over its own table.
  */
final private[kyo] class SingleByteDecoder(val charset: Charset, index: IndexTable) extends Decoder:

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        @scala.annotation.tailrec
        def loop(i: Int): Unit =
            if i < bytes.size then
                val b = bytes(i) & 0xff
                if b < 0x80 then discard(out.append(b.toChar))
                else
                    val cp = index(b - 0x80)
                    if cp < 0 then out.replacement(i) else discard(out.append(cp.toChar))
                end if
                loop(i + 1)
        loop(0)
    end run

end SingleByteDecoder
