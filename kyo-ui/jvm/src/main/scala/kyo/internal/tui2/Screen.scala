package kyo.internal.tui2

import scala.annotation.tailrec

/** Pre-allocated double-buffered cell grid with diff-based ANSI emission.
  *
  * Owns character and style arrays for current and previous frames. Clip stack supports nested overflow:hidden containers. One instance,
  * reused across frames.
  */
final private[kyo] class Screen(var width: Int, var height: Int):

    private var size = width * height

    // Double-buffered cell data
    private var curChars: Array[Char]       = new Array[Char](size)
    private var curStyle: Array[CellStyle]  = new Array[CellStyle](size)
    private var prevChars: Array[Char]      = new Array[Char](size)
    private var prevStyle: Array[CellStyle] = new Array[CellStyle](size)

    private val ansiBuf         = new AnsiBuffer(65536)
    private var forceFullRedraw = true

    // Clip region
    private var clipX0 = 0
    private var clipY0 = 0
    private var clipX1 = width
    private var clipY1 = height

    // Clip stack for nested pushClip/popClip (max 8 deep)
    private val clipStack = new Array[Int](32) // 4 ints per entry (x0,y0,x1,y1)
    private var clipDepth = 0

    // ---- Low-level cell access ----

    def set(x: Int, y: Int, ch: Char, style: CellStyle): Unit =
        if x >= clipX0 && x < clipX1 && y >= clipY0 && y < clipY1 then
            val idx = y * width + x
            curChars(idx) = ch
            curStyle(idx) = style

    def get(x: Int, y: Int): CellStyle =
        if x >= 0 && x < width && y >= 0 && y < height then
            curStyle(y * width + x)
        else
            CellStyle.Empty

    def getChar(x: Int, y: Int): Char =
        if x >= 0 && x < width && y >= 0 && y < height then
            curChars(y * width + x)
        else
            ' '

    // ---- Fill background ----

    def fillBg(fx: Int, fy: Int, fw: Int, fh: Int, color: Int): Unit =
        val maxRow   = math.min(fy + fh, clipY1)
        val maxCol   = math.min(fx + fw, clipX1)
        val startRow = math.max(fy, clipY0)
        val startCol = math.max(fx, clipX0)
        @tailrec def loopCol(row: Int, col: Int): Unit =
            if col < maxCol then
                val idx = row * width + col
                curStyle(idx) = curStyle(idx).withBg(color)
                loopCol(row, col + 1)
        @tailrec def loopRow(row: Int): Unit =
            if row < maxRow then
                loopCol(row, startCol)
                loopRow(row + 1)
        loopRow(startRow)
    end fillBg

    // ---- Clip stack ----

    def pushClip(x0: Int, y0: Int, x1: Int, y1: Int): Unit =
        if clipDepth < 8 then
            val base = clipDepth * 4
            clipStack(base) = clipX0
            clipStack(base + 1) = clipY0
            clipStack(base + 2) = clipX1
            clipStack(base + 3) = clipY1
            clipDepth += 1
        end if
        clipX0 = math.max(clipX0, math.max(0, x0))
        clipY0 = math.max(clipY0, math.max(0, y0))
        clipX1 = math.min(clipX1, math.min(width, x1))
        clipY1 = math.min(clipY1, math.min(height, y1))
    end pushClip

    def popClip(): Unit =
        if clipDepth > 0 then
            clipDepth -= 1
            val base = clipDepth * 4
            clipX0 = clipStack(base)
            clipY0 = clipStack(base + 1)
            clipX1 = clipStack(base + 2)
            clipY1 = clipStack(base + 3)
        else
            resetClip()

    def resetClip(): Unit =
        clipX0 = 0; clipY0 = 0; clipX1 = width; clipY1 = height

    // ---- Buffer management ----

    def clear(): Unit =
        java.util.Arrays.fill(curChars, ' ')
        fillStyleArray(curStyle, CellStyle.Empty)
    end clear

    def invalidate(): Unit = forceFullRedraw = true

    // ---- Diff-based ANSI flush ----

    def flush(sink: Screen.Sink, colorTier: Int): Unit =
        ansiBuf.reset()

        ansiBuf.csi()
        ansiBuf.putAscii("?2026h")

        @tailrec def loop(i: Int, lastStyle: CellStyle, styleSet: Boolean, lastRow: Int, lastCol: Int): Unit =
            if i < size then
                val cs = curStyle(i)
                if forceFullRedraw || curChars(i) != prevChars(i) || cs != prevStyle(i) then
                    val row = i / width
                    val col = i % width

                    if row != lastRow || col != lastCol then
                        ansiBuf.moveTo(row + 1, col + 1)

                    val needStyleChange = !styleSet || cs != lastStyle
                    if needStyleChange then
                        ansiBuf.sgrReset()
                        emitFg(cs.fg, colorTier)
                        emitBg(cs.bg, colorTier)
                        if cs.bold then ansiBuf.sgr(1)
                        if cs.dim then ansiBuf.sgr(2)
                        if cs.italic then ansiBuf.sgr(3)
                        if cs.underline then ansiBuf.sgr(4)
                        if cs.strikethrough then ansiBuf.sgr(9)
                    end if

                    ansiBuf.putChar(curChars(i))

                    loop(i + 1, if needStyleChange then cs else lastStyle, styleSet || needStyleChange, row, col + 1)
                else
                    loop(i + 1, lastStyle, styleSet, lastRow, lastCol)
                end if
        loop(0, CellStyle.Empty, false, -1, -1)

        ansiBuf.sgrReset()

        ansiBuf.csi()
        ansiBuf.putAscii("?2026l")

        sink.write(ansiBuf.array, ansiBuf.length)

        // Swap buffers
        val tmpChars = prevChars; prevChars = curChars; curChars = tmpChars
        val tmpStyle = prevStyle; prevStyle = curStyle; curStyle = tmpStyle

        // Clear current buffer for next frame
        java.util.Arrays.fill(curChars, ' ')
        fillStyleArray(curStyle, CellStyle.Empty)

        forceFullRedraw = false
    end flush

    // ---- Resize ----

    def resize(newW: Int, newH: Int): Unit =
        if newW != width || newH != height then
            width = newW
            height = newH
            size = newW * newH
            curChars = new Array[Char](size)
            curStyle = new Array[CellStyle](size)
            prevChars = new Array[Char](size)
            prevStyle = new Array[CellStyle](size)
            java.util.Arrays.fill(curChars, ' ')
            java.util.Arrays.fill(prevChars, ' ')
            fillStyleArray(curStyle, CellStyle.Empty)
            fillStyleArray(prevStyle, CellStyle.Empty)
            forceFullRedraw = true
            resetClip()

    // ---- Filter application ----

    def applyFilter(fx: Int, fy: Int, fw: Int, fh: Int, bits: Int, vals: Array[Double]): Unit =
        if bits != 0 then
            val maxRow   = math.min(fy + fh, clipY1)
            val maxCol   = math.min(fx + fw, clipX1)
            val startRow = math.max(fy, clipY0)
            val startCol = math.max(fx, clipX0)
            @tailrec def loopCol(row: Int, col: Int): Unit =
                if col < maxCol then
                    val idx   = row * width + col
                    val style = curStyle(idx)
                    val newFg = applyColorFilters(style.fg, bits, vals)
                    val newBg = applyColorFilters(style.bg, bits, vals)
                    curStyle(idx) = style.withFg(newFg).withBg(newBg)
                    loopCol(row, col + 1)
            @tailrec def loopRow(row: Int): Unit =
                if row < maxRow then
                    loopCol(row, startCol)
                    loopRow(row + 1)
            loopRow(startRow)
    end applyFilter

    /** Apply all active color filters to a single color. Pure expression chain — no mutation. */
    private def applyColorFilters(color: Int, bits: Int, vals: Array[Double]): Int =
        val c0 = if (bits & (1 << 0)) != 0 then ColorUtils.brightness(color, vals(0)) else color
        val c1 = if (bits & (1 << 1)) != 0 then ColorUtils.contrast(c0, vals(1)) else c0
        val c2 = if (bits & (1 << 2)) != 0 then ColorUtils.grayscale(c1, vals(2)) else c1
        val c3 = if (bits & (1 << 3)) != 0 then ColorUtils.sepia(c2, vals(3)) else c2
        val c4 = if (bits & (1 << 4)) != 0 then ColorUtils.invert(c3, vals(4)) else c3
        val c5 = if (bits & (1 << 5)) != 0 then ColorUtils.saturate(c4, vals(5)) else c4
        val c6 = if (bits & (1 << 6)) != 0 then ColorUtils.hueRotate(c5, vals(6)) else c5
        if (bits & (1 << 7)) != 0 then ColorUtils.grayscale(c6, math.min(1.0, vals(7) / 10.0)) else c6
    end applyColorFilters

    // ---- Helpers ----

    private def fillStyleArray(arr: Array[CellStyle], value: CellStyle): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < arr.length then
                arr(i) = value
                loop(i + 1)
        loop(0)
    end fillStyleArray

    // ---- ANSI color emission ----

    private def emitFg(color: Int, colorTier: Int): Unit =
        if color != ColorUtils.Absent then
            colorTier match
                case Screen.TrueColor => ansiBuf.fgRgb(ColorUtils.r(color), ColorUtils.g(color), ColorUtils.b(color))
                case Screen.Color256  => ansiBuf.fg256(ColorUtils.to256(color))
                case Screen.Color16   => ansiBuf.fg16(ColorUtils.to16(color))
                case _                => ()
    end emitFg

    private def emitBg(color: Int, colorTier: Int): Unit =
        if color != ColorUtils.Absent then
            colorTier match
                case Screen.TrueColor => ansiBuf.bgRgb(ColorUtils.r(color), ColorUtils.g(color), ColorUtils.b(color))
                case Screen.Color256  => ansiBuf.bg256(ColorUtils.to256(color))
                case Screen.Color16   => ansiBuf.bg16(ColorUtils.to16(color))
                case _                => ()
    end emitBg

end Screen

private[kyo] object Screen:
    inline val TrueColor = 0
    inline val Color256  = 1
    inline val Color16   = 2
    inline val NoColor   = 3

    /** Receives the final ANSI bytes — the exact output that would go to the terminal. */
    trait Sink:
        def write(bytes: Array[Byte], len: Int): Unit

    /** Writes ANSI bytes to an OutputStream (the normal terminal path). */
    final class StreamSink(out: java.io.OutputStream) extends Sink:
        def write(bytes: Array[Byte], len: Int): Unit = out.write(bytes, 0, len)

end Screen
