package kyo.internal.tui2

import scala.annotation.tailrec

/** Stateless drawing helpers over Screen.
  *
  * Provides drawString, drawChar, hline, vline, border, drawText, and applyFilters. All methods delegate to Screen.set for clipping.
  */
final private[kyo] class Canvas(val screen: Screen):

    /** Raw escape sequences to emit after cell flush (for images). */
    val rawSequences: RawSequenceCollector = new RawSequenceCollector

    /** Draw a substring of s starting at offset, up to maxW characters. Returns chars drawn. */
    def drawString(x: Int, y: Int, maxW: Int, s: String, offset: Int, style: CellStyle): Int =
        val end = math.min(s.length, offset + maxW)
        @tailrec def loop(i: Int, col: Int): Int =
            if i >= end then col - x
            else
                screen.set(col, y, s.charAt(i), style)
                loop(i + 1, col + 1)
        loop(offset, x)
    end drawString

    def drawChar(x: Int, y: Int, ch: Char, style: CellStyle): Unit =
        screen.set(x, y, ch, style)

    def hline(x: Int, y: Int, w: Int, ch: Char, style: CellStyle): Unit =
        @tailrec def loop(col: Int): Unit =
            if col < x + w then
                screen.set(col, y, ch, style)
                loop(col + 1)
        loop(x)
    end hline

    def vline(x: Int, y: Int, h: Int, ch: Char, style: CellStyle): Unit =
        @tailrec def loop(row: Int): Unit =
            if row < y + h then
                screen.set(x, row, ch, style)
                loop(row + 1)
        loop(y)
    end vline

    /** Draw borders around a rectangle. Reads border fields from ResolvedStyle. */
    def border(x: Int, y: Int, w: Int, h: Int, rs: ResolvedStyle): Unit =
        if w < 1 || h < 1 then ()
        else
            fillBorderChars(rs.borderStyle, rs.roundTL, rs.roundTR, rs.roundBR, rs.roundBL)
            val tl      = borderBuf(0); val tr = borderBuf(1); val br = borderBuf(2)
            val bl      = borderBuf(3); val hz = borderBuf(4); val vt = borderBuf(5)
            val bgColor = rs.bg

            if rs.borderT then
                val stT = CellStyle(rs.borderColorT, bgColor, false, false, false, false, false)
                if rs.borderL then screen.set(x, y, tl, CellStyle(rs.borderColorL, bgColor, false, false, false, false, false))
                hlineRange(x + (if rs.borderL then 1 else 0), y, x + w - (if rs.borderR then 1 else 0), hz, stT)
                if rs.borderR then screen.set(x + w - 1, y, tr, CellStyle(rs.borderColorR, bgColor, false, false, false, false, false))
            end if

            if rs.borderB then
                val stB = CellStyle(rs.borderColorB, bgColor, false, false, false, false, false)
                if rs.borderL then
                    screen.set(x, y + h - 1, bl, CellStyle(rs.borderColorL, bgColor, false, false, false, false, false))
                hlineRange(x + (if rs.borderL then 1 else 0), y + h - 1, x + w - (if rs.borderR then 1 else 0), hz, stB)
                if rs.borderR then
                    screen.set(x + w - 1, y + h - 1, br, CellStyle(rs.borderColorR, bgColor, false, false, false, false, false))
            end if

            if rs.borderL then
                val stL = CellStyle(rs.borderColorL, bgColor, false, false, false, false, false)
                vlineRange(x, y + (if rs.borderT then 1 else 0), y + h - (if rs.borderB then 1 else 0), vt, stL)

            if rs.borderR then
                val stR = CellStyle(rs.borderColorR, bgColor, false, false, false, false, false)
                vlineRange(x + w - 1, y + (if rs.borderT then 1 else 0), y + h - (if rs.borderB then 1 else 0), vt, stR)
    end border

    /** Draw text with wrapping, alignment, transform, and ellipsis. */
    def drawText(
        text: String,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        style: CellStyle,
        align: Int,
        transform: Int,
        wrap: Boolean,
        ellipsis: Boolean
    ): Unit =
        val maxX = cx + cw
        val totalLines =
            if wrap then TextMetrics.lineCount(text, cw)
            else TextMetrics.naturalHeight(text)
        drawTextLines(text, cx, cy, cw, ch, maxX, style, align, transform, wrap, ellipsis, totalLines)
    end drawText

    private def drawTextLines(
        text: String,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        maxX: Int,
        style: CellStyle,
        align: Int,
        transform: Int,
        wrap: Boolean,
        ellipsis: Boolean,
        totalLines: Int
    ): Unit =
        val _ = TextMetrics.foldLines(text, cw, wrap, 0) { (lineIdx, start, end) =>
            if lineIdx < ch then
                drawTextLine(text, start, end, cx, cy, cw, ch, maxX, style, align, transform, ellipsis, totalLines, lineIdx)
            lineIdx + 1
        }
    end drawTextLines

    private def drawTextLine(
        text: String,
        start: Int,
        end: Int,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        maxX: Int,
        style: CellStyle,
        align: Int,
        transform: Int,
        ellipsis: Boolean,
        totalLines: Int,
        lineIdx: Int
    ): Unit =
        val lineLen        = end - start
        val isLastVisible  = lineIdx == ch - 1
        val truncated      = isLastVisible && totalLines > ch && ellipsis
        val widthTruncated = lineLen > cw && ellipsis

        val displayLen =
            if truncated || widthTruncated then
                math.min(lineLen, if cw > 1 then cw - 1 else cw)
            else
                math.min(lineLen, cw)

        val startX = align match
            case ResolvedStyle.TextAlignCenter => cx + math.max(0, (cw - math.min(lineLen, cw)) / 2)
            case ResolvedStyle.TextAlignRight  => cx + math.max(0, cw - math.min(lineLen, cw))
            case _                             => cx

        drawLineChars(text, start, displayLen, startX, cy + lineIdx, maxX, cx, style, transform)

        if (truncated || widthTruncated) && displayLen < cw then
            val ellipsisX = startX + displayLen
            if ellipsisX >= cx && ellipsisX < maxX then
                screen.set(ellipsisX, cy + lineIdx, '\u2026', style)
        else if (truncated || widthTruncated) && cw > 0 then
            val ellipsisX = startX + displayLen - 1
            if ellipsisX >= cx && ellipsisX < maxX then
                screen.set(ellipsisX, cy + lineIdx, '\u2026', style)
        end if
    end drawTextLine

    /** Apply color filters to a rectangular region. Delegates to Screen.applyFilter. */
    def applyFilters(x: Int, y: Int, w: Int, h: Int, bits: Int, vals: Array[Double]): Unit =
        screen.applyFilter(x, y, w, h, bits, vals)

    // ---- Private helpers ----

    private def drawLineChars(
        text: String,
        start: Int,
        displayLen: Int,
        startX: Int,
        y: Int,
        maxX: Int,
        minX: Int,
        style: CellStyle,
        transform: Int
    ): Unit =
        @tailrec def loop(ci: Int, afterSpace: Boolean): Unit =
            if ci < displayLen then
                val sx = startX + ci
                if sx >= minX && sx < maxX then
                    val rawCh = text.charAt(start + ci)
                    val ch =
                        if transform == 3 then TextMetrics.capitalizeChar(rawCh, afterSpace)
                        else TextMetrics.applyTransform(rawCh, transform)
                    screen.set(sx, y, ch, style)
                    loop(ci + 1, if transform == 3 then Character.isWhitespace(rawCh) else afterSpace)
                else
                    loop(ci + 1, afterSpace)
                end if
            end if
        end loop
        loop(0, true)
    end drawLineChars

    private def hlineRange(x0: Int, y: Int, x1: Int, ch: Char, style: CellStyle): Unit =
        @tailrec def loop(x: Int): Unit =
            if x < x1 then
                screen.set(x, y, ch, style)
                loop(x + 1)
        loop(x0)
    end hlineRange

    private def vlineRange(x: Int, y0: Int, y1: Int, ch: Char, style: CellStyle): Unit =
        @tailrec def loop(y: Int): Unit =
            if y < y1 then
                screen.set(x, y, ch, style)
                loop(y + 1)
        loop(y0)
    end vlineRange

    // Pre-allocated buffer for border chars: [tl, tr, br, bl, hz, vt]
    private val borderBuf = new Array[Char](6)

    /** Fill border char buffer for a border style. Zero-alloc (writes to pre-allocated array). */
    private def fillBorderChars(
        style: Int,
        roundTL: Boolean,
        roundTR: Boolean,
        roundBR: Boolean,
        roundBL: Boolean
    ): Unit =
        val hz = style match
            case ResolvedStyle.BorderSolid  => '\u2500'
            case ResolvedStyle.BorderDashed => '\u2504'
            case ResolvedStyle.BorderDotted => '\u2508'
            case _                          => ' '
        val vt = style match
            case ResolvedStyle.BorderSolid  => '\u2502'
            case ResolvedStyle.BorderDashed => '\u2506'
            case ResolvedStyle.BorderDotted => '\u250a'
            case _                          => ' '
        if style == 0 then
            borderBuf(0) = ' '; borderBuf(1) = ' '; borderBuf(2) = ' '
            borderBuf(3) = ' '; borderBuf(4) = ' '; borderBuf(5) = ' '
        else
            borderBuf(0) = if roundTL then '\u256d' else '\u250c'
            borderBuf(1) = if roundTR then '\u256e' else '\u2510'
            borderBuf(2) = if roundBR then '\u256f' else '\u2518'
            borderBuf(3) = if roundBL then '\u2570' else '\u2514'
            borderBuf(4) = hz
            borderBuf(5) = vt
        end if
    end fillBorderChars

end Canvas
