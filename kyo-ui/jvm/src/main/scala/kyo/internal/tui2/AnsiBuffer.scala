package kyo.internal.tui2

import scala.annotation.tailrec

/** Pre-allocated byte buffer for zero-allocation ANSI output.
  *
  * Grows only if a frame exceeds capacity (rare, amortized). reset() sets pos=0.
  */
final private[kyo] class AnsiBuffer(initialCapacity: Int = 65536):

    private var buf = new Array[Byte](initialCapacity)
    private var pos = 0

    def reset(): Unit      = pos = 0
    def length: Int        = pos
    def array: Array[Byte] = buf

    inline def put(b: Byte): Unit =
        if pos == buf.length then grow()
        buf(pos) = b
        pos += 1
    end put

    inline def putAscii(s: String): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < s.length then
                put(s.charAt(i).toByte)
                loop(i + 1)
        loop(0)
    end putAscii

    def putInt(n: Int): Unit =
        if n < 10 then put((n + '0').toByte)
        else
            putInt(n / 10)
            put((n % 10 + '0').toByte)

    def putChar(ch: Char): Unit =
        if ch < 0x80 then put(ch.toByte)
        else if ch < 0x800 then
            put((0xc0 | (ch >> 6)).toByte)
            put((0x80 | (ch & 0x3f)).toByte)
        else
            put((0xe0 | (ch >> 12)).toByte)
            put((0x80 | ((ch >> 6) & 0x3f)).toByte)
            put((0x80 | (ch & 0x3f)).toByte)

    def putUtf8(s: String): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < s.length then
                putChar(s.charAt(i))
                loop(i + 1)
        loop(0)
    end putUtf8

    def writeTo(out: java.io.OutputStream): Unit =
        out.write(buf, 0, pos)

    // ---- ANSI helpers ----

    inline def csi(): Unit =
        put(0x1b)
        put('[')

    def sgr(code: Int): Unit =
        csi(); putInt(code); put('m')

    def moveTo(row: Int, col: Int): Unit =
        csi(); putInt(row); put(';'); putInt(col); put('H')

    def fgRgb(r: Int, g: Int, b: Int): Unit =
        csi(); putAscii("38;2;"); putInt(r); put(';'); putInt(g); put(';'); putInt(b); put('m')

    def bgRgb(r: Int, g: Int, b: Int): Unit =
        csi(); putAscii("48;2;"); putInt(r); put(';'); putInt(g); put(';'); putInt(b); put('m')

    def fg256(idx: Int): Unit =
        csi(); putAscii("38;5;"); putInt(idx); put('m')

    def bg256(idx: Int): Unit =
        csi(); putAscii("48;5;"); putInt(idx); put('m')

    def fg16(idx: Int): Unit =
        csi(); putInt(if idx < 8 then 30 + idx else 90 + idx - 8); put('m')

    def bg16(idx: Int): Unit =
        csi(); putInt(if idx < 8 then 40 + idx else 100 + idx - 8); put('m')

    def sgrReset(): Unit =
        csi(); put('0'); put('m')

    private def grow(): Unit =
        val newBuf = new Array[Byte](buf.length * 2)
        java.lang.System.arraycopy(buf, 0, newBuf, 0, pos)
        buf = newBuf
    end grow

end AnsiBuffer
