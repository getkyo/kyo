package kyo.internal

import java.io.FileOutputStream

/** Pre-allocated byte buffer for zero-allocation ANSI output.
  *
  * Grows only if a frame exceeds capacity (rare, amortized). `reset()` sets pos=0 — no clearing needed.
  */
final private[kyo] class TuiAnsiBuffer(initialCapacity: Int = 65536):

    private var buf = new Array[Byte](initialCapacity)
    private var pos = 0

    def reset(): Unit = pos = 0

    def length: Int = pos

    inline def put(b: Byte): Unit =
        if pos == buf.length then grow()
        buf(pos) = b
        pos += 1
    end put

    inline def putAscii(s: String): Unit =
        var i = 0
        while i < s.length do
            put(s.charAt(i).toByte)
            i += 1
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
        var i = 0
        while i < s.length do
            putChar(s.charAt(i))
            i += 1
    end putUtf8

    def writeTo(out: FileOutputStream): Unit =
        out.write(buf, 0, pos)

    /** Write to an OutputStream (for testing). */
    def writeTo(out: java.io.OutputStream): Unit =
        out.write(buf, 0, pos)

    // ---- ANSI helpers ----

    inline def csi(): Unit =
        put(0x1b)
        put('[')

    def sgr(code: Int): Unit =
        csi()
        putInt(code)
        put('m')
    end sgr

    def moveTo(row: Int, col: Int): Unit =
        csi()
        putInt(row)
        put(';')
        putInt(col)
        put('H')
    end moveTo

    def fgRgb(r: Int, g: Int, b: Int): Unit =
        csi()
        putAscii("38;2;")
        putInt(r); put(';'); putInt(g); put(';'); putInt(b)
        put('m')
    end fgRgb

    def bgRgb(r: Int, g: Int, b: Int): Unit =
        csi()
        putAscii("48;2;")
        putInt(r); put(';'); putInt(g); put(';'); putInt(b)
        put('m')
    end bgRgb

    def fg256(idx: Int): Unit =
        csi()
        putAscii("38;5;")
        putInt(idx)
        put('m')
    end fg256

    def bg256(idx: Int): Unit =
        csi()
        putAscii("48;5;")
        putInt(idx)
        put('m')
    end bg256

    def fg16(idx: Int): Unit =
        csi()
        putInt(if idx < 8 then 30 + idx else 90 + idx - 8)
        put('m')
    end fg16

    def bg16(idx: Int): Unit =
        csi()
        putInt(if idx < 8 then 40 + idx else 100 + idx - 8)
        put('m')
    end bg16

    def sgrReset(): Unit =
        csi()
        put('0')
        put('m')
    end sgrReset

    /** OSC (Operating System Command) introducer: ESC ] */
    inline def osc(code: Int): Unit =
        put(0x1b)
        put(']')
        putInt(code)
        put(';')
    end osc

    /** String Terminator: ESC \ */
    inline def st(): Unit =
        put(0x1b)
        put('\\')

    private def grow(): Unit =
        val newBuf = new Array[Byte](buf.length * 2)
        java.lang.System.arraycopy(buf, 0, newBuf, 0, pos)
        buf = newBuf
    end grow

end TuiAnsiBuffer
