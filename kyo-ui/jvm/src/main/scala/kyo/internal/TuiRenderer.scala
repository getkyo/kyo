package kyo.internal

import java.io.FileOutputStream
import kyo.Maybe

/** Double-buffered cell grid with diff-based ANSI emission.
  *
  * The painter writes into the current buffer via set/setWide/fillBg. flush() diffs current vs previous, emits ANSI for changed cells, then
  * swaps buffers. The painter always does a full repaint, so the old previous (now current) is naturally overwritten.
  */
final private[kyo] class TuiRenderer(var width: Int, var height: Int):
    import TuiRenderer.*

    private var size = width * height

    // Current buffer (painter writes here)
    private var curChars: Array[Char]                       = new Array[Char](size)
    private var curStyle: Array[Long]                       = new Array[Long](size)
    private var curWideSyms: java.util.HashMap[Int, String] = new java.util.HashMap[Int, String]()

    // Previous buffer (flush reads here for diff)
    private var prevChars: Array[Char]                       = new Array[Char](size)
    private var prevStyle: Array[Long]                       = new Array[Long](size)
    private var prevWideSyms: java.util.HashMap[Int, String] = new java.util.HashMap[Int, String]()

    private val ansiBuf         = new TuiAnsiBuffer(65536)
    private var forceFullRedraw = true // first frame is always full

    // ---- Clip rect (for scrollable containers) ----
    private var clipX0 = 0
    private var clipY0 = 0
    private var clipX1 = width
    private var clipY1 = height

    /** Set the clip rect. Cells outside this rect are silently dropped by set/setWide/fillBg. */
    def setClip(x0: Int, y0: Int, x1: Int, y1: Int): Unit =
        clipX0 = math.max(0, x0)
        clipY0 = math.max(0, y0)
        clipX1 = math.min(width, x1)
        clipY1 = math.min(height, y1)
    end setClip

    /** Reset clip rect to full screen. */
    def resetClip(): Unit =
        clipX0 = 0; clipY0 = 0; clipX1 = width; clipY1 = height

    // ---- Painter API ----

    /** Set a single BMP character at (x, y) with the given packed style. */
    def set(cx: Int, cy: Int, ch: Char, style: Long): Unit =
        if cx >= clipX0 && cx < clipX1 && cy >= clipY0 && cy < clipY1 then
            val idx = cy * width + cx
            curChars(idx) = ch
            curStyle(idx) = style
            curWideSyms.remove(idx): Unit

    /** Set a wide/multi-codepoint symbol at (x, y). Marks (x+1, y) with SkipBit. */
    def setWide(cx: Int, cy: Int, sym: String, style: Long): Unit =
        if cx >= clipX0 && cx + 1 < clipX1 && cy >= clipY0 && cy < clipY1 then
            val idx = cy * width + cx
            curChars(idx) = '\u0000' // sentinel: look up in wideSyms
            curStyle(idx) = style
            curWideSyms.put(idx, sym)
            // Mark next cell as skip
            val skipIdx = idx + 1
            curChars(skipIdx) = ' '
            curStyle(skipIdx) = style | SkipBit

    /** Fill a rectangular region with a background color (no character change). */
    def fillBg(fx: Int, fy: Int, fw: Int, fh: Int, color: Int): Unit =
        val bgBits = packBg(color)
        var row    = fy
        while row < fy + fh && row < clipY1 do
            if row >= clipY0 then
                var col = fx
                while col < fx + fw && col < clipX1 do
                    if col >= clipX0 then
                        val idx = row * width + col
                        curStyle(idx) = (curStyle(idx) & ~BgMask) | bgBits
                    col += 1
                end while
            end if
            row += 1
        end while
    end fillBg

    /** Clear the current buffer to spaces with default style. */
    def clear(): Unit =
        java.util.Arrays.fill(curChars, ' ')
        java.util.Arrays.fill(curStyle, 0L)
        curWideSyms.clear()
    end clear

    /** Force a full redraw on the next flush (e.g. after resize or resume). */
    def invalidate(): Unit = forceFullRedraw = true

    // ---- Main loop API ----

    /** Diff current vs previous, emit ANSI to out, swap buffers. */
    def flush(out: java.io.OutputStream, colorTier: Int = TrueColor): Unit =
        ansiBuf.reset()

        // Synchronized output begin
        ansiBuf.csi()
        ansiBuf.putAscii("?2026h")

        var lastFg: Long    = -2L // impossible value to force first emission
        var lastBg: Long    = -2L
        var lastAttrs: Long = -2L
        var lastRow         = -1
        var lastCol         = -1

        var i = 0
        while i < size do
            val cs = curStyle(i)

            // Skip cells marked as continuation of wide chars
            if (cs & SkipBit) != 0 then
                () // skip
            else if forceFullRedraw || curChars(i) != prevChars(i) || cs != prevStyle(i) ||
            (curChars(i) == '\u0000') != (prevChars(i) == '\u0000') // wide sym change check
            then
                val row = i / width
                val col = i % width

                // Cursor positioning
                if row != lastRow || col != lastCol then
                    ansiBuf.moveTo(row + 1, col + 1) // ANSI is 1-based

                // Emit style changes
                val fgBits   = cs & FgMask
                val bgBits   = cs & BgMask
                val attrBits = cs & AttrMask

                if fgBits != lastFg || bgBits != lastBg || attrBits != lastAttrs then
                    ansiBuf.sgrReset()
                    emitFg(fgBits, colorTier)
                    emitBg(bgBits, colorTier)
                    emitAttrs(attrBits)
                    lastFg = fgBits
                    lastBg = bgBits
                    lastAttrs = attrBits
                end if

                // Emit character
                val ch = curChars(i)
                if ch == '\u0000' then
                    // Wide symbol
                    Maybe(curWideSyms.get(i)).fold(ansiBuf.putChar(' '))(ansiBuf.putUtf8)
                else
                    ansiBuf.putChar(ch)
                end if

                lastRow = row
                lastCol = col + 1 // cursor advances after write
            end if
            i += 1
        end while

        // SGR reset at end
        ansiBuf.sgrReset()

        // Synchronized output end
        ansiBuf.csi()
        ansiBuf.putAscii("?2026l")

        ansiBuf.writeTo(out)

        // Swap buffers
        val tmpChars = prevChars; prevChars = curChars; curChars = tmpChars
        val tmpStyle = prevStyle; prevStyle = curStyle; curStyle = tmpStyle
        val tmpWide  = prevWideSyms; prevWideSyms = curWideSyms; curWideSyms = tmpWide

        // Clear current buffer for next frame's painter
        java.util.Arrays.fill(curChars, ' ')
        java.util.Arrays.fill(curStyle, 0L)
        curWideSyms.clear()

        forceFullRedraw = false
    end flush

    /** Resize buffers if dimensions changed. */
    def resize(newW: Int, newH: Int): Unit =
        if newW != width || newH != height then
            width = newW
            height = newH
            size = newW * newH
            curChars = new Array[Char](size)
            curStyle = new Array[Long](size)
            curWideSyms = new java.util.HashMap[Int, String]()
            prevChars = new Array[Char](size)
            prevStyle = new Array[Long](size)
            prevWideSyms = new java.util.HashMap[Int, String]()
            java.util.Arrays.fill(curChars, ' ')
            java.util.Arrays.fill(prevChars, ' ')
            forceFullRedraw = true
            resetClip()

    // ---- Private ANSI emission helpers ----

    private def emitFg(fgBits: Long, colorTier: Int): Unit =
        val raw = fgColor(fgBits)
        if raw != TuiColor.Absent then
            colorTier match
                case TrueColor => ansiBuf.fgRgb(TuiColor.r(raw), TuiColor.g(raw), TuiColor.b(raw))
                case Color256  => ansiBuf.fg256(TuiColor.to256(raw))
                case Color16   => ansiBuf.fg16(TuiColor.to16(raw))
                case _         => () // NoColor
        end if
    end emitFg

    private def emitBg(bgBits: Long, colorTier: Int): Unit =
        val raw = bgColor(bgBits)
        if raw != TuiColor.Absent then
            colorTier match
                case TrueColor => ansiBuf.bgRgb(TuiColor.r(raw), TuiColor.g(raw), TuiColor.b(raw))
                case Color256  => ansiBuf.bg256(TuiColor.to256(raw))
                case Color16   => ansiBuf.bg16(TuiColor.to16(raw))
                case _         => () // NoColor
        end if
    end emitBg

    private def emitAttrs(attrBits: Long): Unit =
        val attrs = ((attrBits >>> AttrShift) & 0x3fL).toInt
        if (attrs & BoldAttr) != 0 then ansiBuf.sgr(1)
        if (attrs & DimAttr) != 0 then ansiBuf.sgr(2)
        if (attrs & ItalicAttr) != 0 then ansiBuf.sgr(3)
        if (attrs & UnderlineAttr) != 0 then ansiBuf.sgr(4)
        if (attrs & StrikethroughAttr) != 0 then ansiBuf.sgr(9)
    end emitAttrs

end TuiRenderer

private[kyo] object TuiRenderer:

    // ---- Color tiers ----
    inline val TrueColor = 0
    inline val Color256  = 1
    inline val Color16   = 2
    inline val NoColor   = 3

    // ---- Style Long bit layout ----
    //   63    39 38    14 13   8 7      0
    //   ┌──────┬──────┬──────┬──────────┐
    //   │  fg  │  bg  │ attrs│ reserved │
    //   │25 bit│25 bit│ 6 bit│  8 bit   │
    //   └──────┴──────┴──────┴──────────┘

    inline val FgShift   = 39
    inline val BgShift   = 14
    inline val AttrShift = 8

    val FgMask: Long   = 0x1ffffffL << FgShift
    val BgMask: Long   = 0x1ffffffL << BgShift
    val AttrMask: Long = 0x3fL << AttrShift

    // Presence bit for colors (bit 24 within the 25-bit field)
    inline val PresenceBit = 0x1000000

    // Attr bits
    inline val BoldAttr          = 1
    inline val DimAttr           = 2
    inline val ItalicAttr        = 4
    inline val UnderlineAttr     = 8
    inline val StrikethroughAttr = 16
    inline val SkipAttr          = 32

    val SkipBit: Long = SkipAttr.toLong << AttrShift

    /** Pack fg + bg + attrs into a style Long. */
    def packStyle(
        fg: Int = TuiColor.Absent,
        bg: Int = TuiColor.Absent,
        bold: Boolean = false,
        dim: Boolean = false,
        italic: Boolean = false,
        underline: Boolean = false,
        strikethrough: Boolean = false
    ): Long =
        var s = 0L
        s |= packFg(fg)
        s |= packBg(bg)
        var attrs = 0
        if bold then attrs |= BoldAttr
        if dim then attrs |= DimAttr
        if italic then attrs |= ItalicAttr
        if underline then attrs |= UnderlineAttr
        if strikethrough then attrs |= StrikethroughAttr
        s |= (attrs.toLong << AttrShift)
        s
    end packStyle

    /** Pack a foreground color into the fg bits of a style Long. */
    def packFg(color: Int): Long =
        if color == TuiColor.Absent then 0L
        else ((color.toLong | PresenceBit.toLong) << FgShift)

    /** Pack a background color into the bg bits of a style Long. */
    def packBg(color: Int): Long =
        if color == TuiColor.Absent then 0L
        else ((color.toLong | PresenceBit.toLong) << BgShift)

    /** Extract fg color from a style Long (or full fg bits). Returns packed RGB or -1. */
    def fgColor(style: Long): Int =
        val field = ((style >>> FgShift) & 0x1ffffffL).toInt
        if (field & PresenceBit) != 0 then field & 0xffffff
        else TuiColor.Absent
    end fgColor

    /** Extract bg color from a style Long (or full bg bits). Returns packed RGB or -1. */
    def bgColor(style: Long): Int =
        val field = ((style >>> BgShift) & 0x1ffffffL).toInt
        if (field & PresenceBit) != 0 then field & 0xffffff
        else TuiColor.Absent
    end bgColor

end TuiRenderer
