package kyo.internal

import kyo.Maybe
import scala.annotation.tailrec

/** Double-buffered cell grid with diff-based ANSI emission and high-level drawing methods.
  *
  * Implements DrawSurface operations: fillBg, drawBorder, drawText, pushClip/popClip. All drawing methods take only primitives.
  */
final private[kyo] class TuiRenderer(var width: Int, var height: Int):
    import TuiRenderer.*

    private var size = width * height

    private var curChars: Array[Char]      = new Array[Char](size)
    private var curStyle: Array[Long]      = new Array[Long](size)
    private var curWideSyms: Array[String] = new Array[String](size)

    private var prevChars: Array[Char]      = new Array[Char](size)
    private var prevStyle: Array[Long]      = new Array[Long](size)
    private var prevWideSyms: Array[String] = new Array[String](size)

    private val ansiBuf         = new TuiAnsiBuffer(65536)
    private var forceFullRedraw = true

    private var clipX0 = 0
    private var clipY0 = 0
    private var clipX1 = width
    private var clipY1 = height

    // Clip stack for nested pushClip/popClip (max 8 deep)
    private val clipStack = new Array[Int](32) // 4 ints per entry (x0,y0,x1,y1)
    private var clipDepth = 0

    // ---- Low-level cell access ----

    def set(cx: Int, cy: Int, ch: Char, style: Long): Unit =
        if cx >= clipX0 && cx < clipX1 && cy >= clipY0 && cy < clipY1 then
            val idx = cy * width + cx
            curChars(idx) = ch
            curStyle(idx) = style
            curWideSyms(idx) = null // unsafe: null for absent wide symbol

    def setWide(cx: Int, cy: Int, sym: String, style: Long): Unit =
        if cx >= clipX0 && cx + 1 < clipX1 && cy >= clipY0 && cy < clipY1 then
            val idx = cy * width + cx
            curChars(idx) = '\u0000'
            curStyle(idx) = style
            curWideSyms(idx) = sym
            val skipIdx = idx + 1
            curChars(skipIdx) = ' '
            curStyle(skipIdx) = style | SkipBit

    // ---- DrawSurface: fillBg ----

    def fillBg(fx: Int, fy: Int, fw: Int, fh: Int, color: Int): Unit =
        val bgBits = packBg(color)
        val maxRow = math.min(fy + fh, clipY1)
        val maxCol = math.min(fx + fw, clipX1)
        @tailrec def loopCol(row: Int, col: Int): Unit =
            if col < maxCol then
                if col >= clipX0 then
                    val idx = row * width + col
                    curStyle(idx) = (curStyle(idx) & ~BgMask) | bgBits
                loopCol(row, col + 1)
        @tailrec def loopRow(row: Int): Unit =
            if row < maxRow then
                if row >= clipY0 then loopCol(row, fx)
                loopRow(row + 1)
        loopRow(fy)
    end fillBg

    // ---- DrawSurface: pushClip / popClip ----

    def pushClip(x0: Int, y0: Int, x1: Int, y1: Int): Unit =
        if clipDepth < 8 then
            val base = clipDepth * 4
            clipStack(base) = clipX0
            clipStack(base + 1) = clipY0
            clipStack(base + 2) = clipX1
            clipStack(base + 3) = clipY1
            clipDepth += 1
        end if
        setClip(
            math.max(clipX0, math.max(0, x0)),
            math.max(clipY0, math.max(0, y0)),
            math.min(clipX1, math.min(width, x1)),
            math.min(clipY1, math.min(height, y1))
        )
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

    def setClip(x0: Int, y0: Int, x1: Int, y1: Int): Unit =
        clipX0 = x0; clipY0 = y0; clipX1 = x1; clipY1 = y1
    end setClip

    def resetClip(): Unit =
        clipX0 = 0; clipY0 = 0; clipX1 = width; clipY1 = height

    // ---- DrawSurface: drawBorder ----

    /** Draw borders around a rectangle. All parameters are primitives. */
    def drawBorder(
        nx: Int,
        ny: Int,
        nw: Int,
        nh: Int,
        borderStyle: Int,
        bT: Boolean,
        bR: Boolean,
        bB: Boolean,
        bL: Boolean,
        roundTL: Boolean,
        roundTR: Boolean,
        roundBR: Boolean,
        roundBL: Boolean,
        fgT: Int,
        fgR: Int,
        fgB: Int,
        fgL: Int,
        bg: Int
    ): Unit =
        TuiLayout.borderChars(borderStyle, roundTL, roundTR, roundBR, roundBL) { (tl, tr, br, bl, hz, vt) =>
            if bT then
                val stT = packBorderStyle(fgT, bg)
                if bL then set(nx, ny, tl, packBorderStyle(fgL, bg))
                hline(nx + (if bL then 1 else 0), ny, nx + nw - (if bR then 1 else 0), hz, stT)
                if bR then set(nx + nw - 1, ny, tr, packBorderStyle(fgR, bg))
            end if

            if bB then
                val stB = packBorderStyle(fgB, bg)
                if bL then set(nx, ny + nh - 1, bl, packBorderStyle(fgL, bg))
                hline(nx + (if bL then 1 else 0), ny + nh - 1, nx + nw - (if bR then 1 else 0), hz, stB)
                if bR then set(nx + nw - 1, ny + nh - 1, br, packBorderStyle(fgR, bg))
            end if

            if bL then
                vline(nx, ny + (if bT then 1 else 0), ny + nh - (if bB then 1 else 0), vt, packBorderStyle(fgL, bg))

            if bR then
                vline(nx + nw - 1, ny + (if bT then 1 else 0), ny + nh - (if bB then 1 else 0), vt, packBorderStyle(fgR, bg))
        }
    end drawBorder

    private inline def packBorderStyle(fg: Int, bg: Int): Long =
        TuiRenderer.packStyle(fg = fg, bg = bg)

    private def hline(x0: Int, y: Int, x1: Int, ch: Char, style: Long): Unit =
        @tailrec def loop(x: Int): Unit =
            if x < x1 then
                set(x, y, ch, style)
                loop(x + 1)
        loop(x0)
    end hline

    private def vline(x: Int, y0: Int, y1: Int, ch: Char, style: Long): Unit =
        @tailrec def loop(y: Int): Unit =
            if y < y1 then
                set(x, y, ch, style)
                loop(y + 1)
        loop(y0)
    end vline

    // ---- DrawSurface: drawText ----

    /** Draw text with wrapping, clipping, alignment, and transform. All parameters primitives.
      *
      * @param text
      *   raw text string
      * @param cx
      *   content area x
      * @param cy
      *   content area y
      * @param cw
      *   content area width
      * @param ch
      *   content area height (max lines)
      * @param style
      *   packed style (fg, bg, bold, etc.)
      * @param align
      *   text alignment (0=left, 1=center, 2=right)
      * @param transform
      *   text transform (0=none, 1=upper, 2=lower, 3=capitalize)
      * @param wrap
      *   whether to word-wrap
      * @param ellipsis
      *   whether to add ellipsis on overflow
      */
    def drawText(
        text: String,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        style: Long,
        align: Int,
        transform: Int,
        wrap: Boolean,
        ellipsis: Boolean
    ): Unit =
        val maxX = cx + cw
        // unsafe: while for line counting and rendering
        var lineIdx    = 0
        var totalLines = 0
        var lastStart  = 0
        var lastEnd    = 0

        // First pass: count total lines (needed for truncation detection)
        totalLines = if wrap then TuiText.lineCount(text, cw)
        else TuiText.naturalHeight(text)

        // Second pass: render lines up to ch
        val _ = TuiText.forEachLine(text, cw, wrap) { (start, end) =>
            if lineIdx < ch then
                val lineLen        = end - start
                val isLastVisible  = lineIdx == ch - 1
                val truncated      = isLastVisible && totalLines > ch && ellipsis
                val widthTruncated = lineLen > cw && ellipsis

                // Effective display length
                val displayLen = if truncated || widthTruncated then
                    math.min(lineLen, if cw > 1 then cw - 1 else cw)
                else
                    math.min(lineLen, cw)

                val startX = align match
                    case TuiLayout.TextAlignCenter => cx + math.max(0, (cw - math.min(lineLen, cw)) / 2)
                    case TuiLayout.TextAlignRight  => cx + math.max(0, cw - math.min(lineLen, cw))
                    case _                         => cx

                // Render chars
                // unsafe: while for char-by-char rendering
                var ci         = 0
                var afterSpace = true // for capitalize transform
                while ci < displayLen do
                    val sx = startX + ci
                    if sx >= cx && sx < maxX then
                        val rawCh = text.charAt(start + ci)
                        val transformedCh =
                            if transform == 3 then // capitalize
                                val r = TuiText.capitalizeChar(rawCh, afterSpace)
                                afterSpace = Character.isWhitespace(rawCh)
                                r
                            else
                                TuiText.applyTransform(rawCh, transform)
                        set(sx, cy + lineIdx, transformedCh, style)
                    end if
                    ci += 1
                end while

                // Add ellipsis if truncated
                if (truncated || widthTruncated) && displayLen < cw then
                    val ellipsisX = startX + displayLen
                    if ellipsisX >= cx && ellipsisX < maxX then
                        set(ellipsisX, cy + lineIdx, '\u2026', style)
                else if (truncated || widthTruncated) && cw > 0 then
                    // Overwrite last char with ellipsis
                    val ellipsisX = startX + displayLen - 1
                    if ellipsisX >= cx && ellipsisX < maxX then
                        set(ellipsisX, cy + lineIdx, '\u2026', style)
                end if
            end if
            lineIdx += 1
        }
    end drawText

    // ---- DrawSurface: applyFilter ----

    /** Post-processing pass: apply color filters to a rectangular region of the cell buffer.
      *
      * Reads filterBits to determine which filters are active. filterVals has 8 floats per node: brightness(0), contrast(1), grayscale(2),
      * sepia(3), invert(4), saturate(5), hueRotate(6), blur(7). Blur is approximated as grayscale since TUI has no spatial kernel.
      */
    def applyFilter(fx: Int, fy: Int, fw: Int, fh: Int, bits: Int, vals: Array[Double], valsOffset: Int): Unit =
        if bits == 0 then ()
        else
            val maxRow = math.min(fy + fh, clipY1)
            val maxCol = math.min(fx + fw, clipX1)
            // unsafe: while for performance
            var row = math.max(fy, clipY0)
            while row < maxRow do
                var col = math.max(fx, clipX0)
                while col < maxCol do
                    val idx   = row * width + col
                    val style = curStyle(idx)
                    var fg    = TuiRenderer.fgColor(style)
                    var bg    = TuiRenderer.bgColor(style)

                    if (bits & (1 << 0)) != 0 then // brightness
                        val v = vals(valsOffset + 0)
                        fg = TuiColor.brightness(fg, v)
                        bg = TuiColor.brightness(bg, v)
                    end if
                    if (bits & (1 << 1)) != 0 then // contrast
                        val v = vals(valsOffset + 1)
                        fg = TuiColor.contrast(fg, v)
                        bg = TuiColor.contrast(bg, v)
                    end if
                    if (bits & (1 << 2)) != 0 then // grayscale
                        val v = vals(valsOffset + 2)
                        fg = TuiColor.grayscale(fg, v)
                        bg = TuiColor.grayscale(bg, v)
                    end if
                    if (bits & (1 << 3)) != 0 then // sepia
                        val v = vals(valsOffset + 3)
                        fg = TuiColor.sepia(fg, v)
                        bg = TuiColor.sepia(bg, v)
                    end if
                    if (bits & (1 << 4)) != 0 then // invert
                        val v = vals(valsOffset + 4)
                        fg = TuiColor.invert(fg, v)
                        bg = TuiColor.invert(bg, v)
                    end if
                    if (bits & (1 << 5)) != 0 then // saturate
                        val v = vals(valsOffset + 5)
                        fg = TuiColor.saturate(fg, v)
                        bg = TuiColor.saturate(bg, v)
                    end if
                    if (bits & (1 << 6)) != 0 then // hueRotate
                        val v = vals(valsOffset + 6)
                        fg = TuiColor.hueRotate(fg, v)
                        bg = TuiColor.hueRotate(bg, v)
                    end if
                    if (bits & (1 << 7)) != 0 then // blur → approximate as grayscale
                        val v      = vals(valsOffset + 7)
                        val amount = math.min(1.0, v / 10.0) // normalize: 10px blur → full grayscale
                        fg = TuiColor.grayscale(fg, amount)
                        bg = TuiColor.grayscale(bg, amount)
                    end if

                    // Repack style with filtered colors
                    val attrs = style & TuiRenderer.AttrMask
                    curStyle(idx) = TuiRenderer.packFg(fg) | TuiRenderer.packBg(bg) | attrs
                    col += 1
                end while
                row += 1
            end while
    end applyFilter

    // ---- Buffer management ----

    def clear(): Unit =
        java.util.Arrays.fill(curChars, ' ')
        java.util.Arrays.fill(curStyle, 0L)
        // unsafe: null for absent wide symbols
        java.util.Arrays.fill(curWideSyms.asInstanceOf[Array[Object]], null)
    end clear

    def invalidate(): Unit = forceFullRedraw = true

    def flush(out: java.io.OutputStream, colorTier: Int = TrueColor): Unit =
        ansiBuf.reset()

        ansiBuf.csi()
        ansiBuf.putAscii("?2026h")

        var lastFg: Long    = -2L
        var lastBg: Long    = -2L
        var lastAttrs: Long = -2L
        var lastRow         = -1
        var lastCol         = -1

        @tailrec
        def loop(i: Int): Unit =
            if i < size then
                val cs = curStyle(i)

                if (cs & SkipBit) != 0 then
                    ()
                else if forceFullRedraw || curChars(i) != prevChars(i) || cs != prevStyle(i) ||
                    (curChars(i) == '\u0000') != (prevChars(i) == '\u0000')
                then
                    val row = i / width
                    val col = i % width

                    if row != lastRow || col != lastCol then
                        ansiBuf.moveTo(row + 1, col + 1)

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

                    val ch = curChars(i)
                    if ch == '\u0000' then
                        val wideSym = curWideSyms(i)
                        if wideSym != null then ansiBuf.putUtf8(wideSym) // unsafe: null check for wide symbol
                        else ansiBuf.putChar(' ')
                    else
                        ansiBuf.putChar(ch)
                    end if

                    lastRow = row
                    lastCol = col + 1
                end if
                loop(i + 1)
        loop(0)

        ansiBuf.sgrReset()

        ansiBuf.csi()
        ansiBuf.putAscii("?2026l")

        ansiBuf.writeTo(out)

        val tmpChars = prevChars; prevChars = curChars; curChars = tmpChars
        val tmpStyle = prevStyle; prevStyle = curStyle; curStyle = tmpStyle
        val tmpWide  = prevWideSyms; prevWideSyms = curWideSyms; curWideSyms = tmpWide

        java.util.Arrays.fill(curChars, ' ')
        java.util.Arrays.fill(curStyle, 0L)
        // unsafe: null for absent wide symbols
        java.util.Arrays.fill(curWideSyms.asInstanceOf[Array[Object]], null)

        forceFullRedraw = false
    end flush

    def resize(newW: Int, newH: Int): Unit =
        if newW != width || newH != height then
            width = newW
            height = newH
            size = newW * newH
            curChars = new Array[Char](size)
            curStyle = new Array[Long](size)
            curWideSyms = new Array[String](size)
            prevChars = new Array[Char](size)
            prevStyle = new Array[Long](size)
            prevWideSyms = new Array[String](size)
            java.util.Arrays.fill(curChars, ' ')
            java.util.Arrays.fill(prevChars, ' ')
            forceFullRedraw = true
            resetClip()

    private def emitFg(fgBits: Long, colorTier: Int): Unit =
        val raw = fgColor(fgBits)
        if raw != TuiColor.Absent then
            colorTier match
                case TrueColor => ansiBuf.fgRgb(TuiColor.r(raw), TuiColor.g(raw), TuiColor.b(raw))
                case Color256  => ansiBuf.fg256(TuiColor.to256(raw))
                case Color16   => ansiBuf.fg16(TuiColor.to16(raw))
                case _         => ()
        end if
    end emitFg

    private def emitBg(bgBits: Long, colorTier: Int): Unit =
        val raw = bgColor(bgBits)
        if raw != TuiColor.Absent then
            colorTier match
                case TrueColor => ansiBuf.bgRgb(TuiColor.r(raw), TuiColor.g(raw), TuiColor.b(raw))
                case Color256  => ansiBuf.bg256(TuiColor.to256(raw))
                case Color16   => ansiBuf.bg16(TuiColor.to16(raw))
                case _         => ()
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

    inline val TrueColor = 0
    inline val Color256  = 1
    inline val Color16   = 2
    inline val NoColor   = 3

    inline val FgShift   = 39
    inline val BgShift   = 14
    inline val AttrShift = 8

    val FgMask: Long   = 0x1ffffffL << FgShift
    val BgMask: Long   = 0x1ffffffL << BgShift
    val AttrMask: Long = 0x3fL << AttrShift

    inline val PresenceBit = 0x1000000

    inline val BoldAttr          = 1
    inline val DimAttr           = 2
    inline val ItalicAttr        = 4
    inline val UnderlineAttr     = 8
    inline val StrikethroughAttr = 16
    inline val SkipAttr          = 32

    val SkipBit: Long = SkipAttr.toLong << AttrShift

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

    def packFg(color: Int): Long =
        if color == TuiColor.Absent then 0L
        else ((color.toLong | PresenceBit.toLong) << FgShift)

    def packBg(color: Int): Long =
        if color == TuiColor.Absent then 0L
        else ((color.toLong | PresenceBit.toLong) << BgShift)

    def fgColor(style: Long): Int =
        val field = ((style >>> FgShift) & 0x1ffffffL).toInt
        if (field & PresenceBit) != 0 then field & 0xffffff
        else TuiColor.Absent
    end fgColor

    def bgColor(style: Long): Int =
        val field = ((style >>> BgShift) & 0x1ffffffL).toInt
        if (field & PresenceBit) != 0 then field & 0xffffff
        else TuiColor.Absent
    end bgColor

end TuiRenderer
