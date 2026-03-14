package kyo.internal.tui2.pipeline

import kyo.*
import scala.annotation.tailrec

/** Compares two cell grids and emits the minimal terminal escape sequences to update the screen.
  *
  * Tracks what was last written to the terminal (colors, text attributes, cursor position) and only emits sequences when something actually
  * changed. For example, if two consecutive cells share the same foreground color, the color sequence is emitted only once.
  */
object Differ:

    /** What the terminal currently looks like — tracks the last-emitted colors, text attributes, and cursor position so we can skip
      * redundant escape sequences.
      */
    private case class TermState(
        fg: Maybe[PackedColor],
        bg: Maybe[PackedColor],
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strikethrough: Boolean,
        dimmed: Boolean,
        cursorPos: Maybe[(Int, Int)]
    )

    private val Initial = TermState(
        Absent,
        Absent,
        false,
        false,
        false,
        false,
        false,
        Absent
    )

    def diff(prev: CellGrid, curr: CellGrid): Array[Byte] =
        val buf = new java.io.ByteArrayOutputStream(curr.width * curr.height * 4)

        @tailrec def eachCol(col: Int, row: Int, term: TermState): TermState =
            if col >= curr.width then term
            else
                val idx      = row * curr.width + col
                val currCell = curr.cells(idx)
                val prevCell = if idx < prev.cells.length then prev.cells(idx) else Cell.Empty
                if currCell != prevCell then
                    if !term.cursorPos.contains((row, col)) then
                        moveCursorTo(buf, row, col)

                    val afterAttrs = updateTextAttributes(buf, term, currCell)
                    val afterFg    = updateFgColor(buf, afterAttrs, currCell.fg)
                    val afterBg    = updateBgColor(buf, afterFg, currCell.bg)

                    writeChar(buf, currCell.char)
                    eachCol(col + 1, row, afterBg.copy(cursorPos = Maybe((row, col + 1))))
                else
                    eachCol(col + 1, row, term)
                end if
        end eachCol

        @tailrec def eachRow(row: Int, term: TermState): TermState =
            if row >= curr.height then term
            else eachRow(row + 1, eachCol(0, row, term))

        discard(eachRow(0, Initial))

        // Raw sequences (e.g., image protocol escapes) are appended verbatim after cell output
        @tailrec def eachRaw(i: Int): Unit =
            if i < curr.rawSequences.size then
                val (rect, bytes) = curr.rawSequences(i)
                moveCursorTo(buf, rect.y, rect.x)
                buf.write(bytes)
                eachRaw(i + 1)
        eachRaw(0)

        buf.toByteArray
    end diff

    // ---- Attribute tracking ----

    /** Terminals don't support turning off individual text attributes (e.g., just bold). The only way is to reset everything, then
      * re-enable what's needed. So if any attribute changed, we reset all and rebuild, which also invalidates tracked colors (they must be
      * re-emitted).
      */
    private def updateTextAttributes(buf: java.io.ByteArrayOutputStream, term: TermState, cell: Cell): TermState =
        if cell.bold != term.bold || cell.italic != term.italic ||
            cell.underline != term.underline || cell.strikethrough != term.strikethrough ||
            cell.dimmed != term.dimmed
        then
            resetAllAttributes(buf)
            if cell.bold then enableBold(buf)
            if cell.dimmed then enableDim(buf)
            if cell.italic then enableItalic(buf)
            if cell.underline then enableUnderline(buf)
            if cell.strikethrough then enableStrikethrough(buf)
            term.copy(
                fg = Absent,
                bg = Absent,
                bold = cell.bold,
                italic = cell.italic,
                underline = cell.underline,
                strikethrough = cell.strikethrough,
                dimmed = cell.dimmed
            )
        else term

    private def updateFgColor(buf: java.io.ByteArrayOutputStream, term: TermState, fg: PackedColor): TermState =
        if fg != PackedColor.Transparent && !term.fg.contains(fg) then
            writeFgColor(buf, fg)
            term.copy(fg = Maybe(fg))
        else term

    private def updateBgColor(buf: java.io.ByteArrayOutputStream, term: TermState, bg: PackedColor): TermState =
        if bg != PackedColor.Transparent && !term.bg.contains(bg) then
            writeBgColor(buf, bg)
            term.copy(bg = Maybe(bg))
        else term

    private def writeChar(buf: java.io.ByteArrayOutputStream, char: Char): Unit =
        val ch = if char == '\u0000' then ' ' else char
        if ch < 128 then buf.write(ch.toInt)
        else buf.write(ch.toString.getBytes("UTF-8"))
    end writeChar

    // ---- Terminal escape sequence primitives ----
    //
    // ANSI escape sequences start with ESC (0x1B = 27) followed by '[', parameters, and a final byte.
    // SGR (Select Graphic Rendition) sequences set text attributes: ESC [ <code> m
    // Color sequences use 24-bit true color: ESC [ 38;2;r;g;b m (fg) / ESC [ 48;2;r;g;b m (bg)
    // Cursor movement: ESC [ <row> ; <col> H (1-based coordinates)

    private inline val Esc = 27

    private def moveCursorTo(buf: java.io.ByteArrayOutputStream, row: Int, col: Int): Unit =
        buf.write(Esc); buf.write('[')
        writeDecimal(buf, row + 1) // terminal uses 1-based coordinates
        buf.write(';')
        writeDecimal(buf, col + 1)
        buf.write('H')
    end moveCursorTo

    private def resetAllAttributes(buf: java.io.ByteArrayOutputStream): Unit =
        buf.write(Esc); buf.write('['); buf.write('0'); buf.write('m')

    private def enableBold(buf: java.io.ByteArrayOutputStream): Unit =
        buf.write(Esc); buf.write('['); buf.write('1'); buf.write('m')

    private def enableDim(buf: java.io.ByteArrayOutputStream): Unit =
        buf.write(Esc); buf.write('['); buf.write('2'); buf.write('m')

    private def enableItalic(buf: java.io.ByteArrayOutputStream): Unit =
        buf.write(Esc); buf.write('['); buf.write('3'); buf.write('m')

    private def enableUnderline(buf: java.io.ByteArrayOutputStream): Unit =
        buf.write(Esc); buf.write('['); buf.write('4'); buf.write('m')

    private def enableStrikethrough(buf: java.io.ByteArrayOutputStream): Unit =
        buf.write(Esc); buf.write('['); buf.write('9'); buf.write('m')

    private def writeFgColor(buf: java.io.ByteArrayOutputStream, color: PackedColor): Unit =
        buf.write(Esc); buf.write('['); buf.write('3'); buf.write('8'); buf.write(';')
        buf.write('2'); buf.write(';')
        writeDecimal(buf, color.r); buf.write(';')
        writeDecimal(buf, color.g); buf.write(';')
        writeDecimal(buf, color.b); buf.write('m')
    end writeFgColor

    private def writeBgColor(buf: java.io.ByteArrayOutputStream, color: PackedColor): Unit =
        buf.write(Esc); buf.write('['); buf.write('4'); buf.write('8'); buf.write(';')
        buf.write('2'); buf.write(';')
        writeDecimal(buf, color.r); buf.write(';')
        writeDecimal(buf, color.g); buf.write(';')
        writeDecimal(buf, color.b); buf.write('m')
    end writeBgColor

    private def writeDecimal(buf: java.io.ByteArrayOutputStream, v: Int): Unit =
        if v >= 100 then
            buf.write('0' + v / 100)
            buf.write('0' + (v / 10) % 10)
            buf.write('0' + v        % 10)
        else if v >= 10 then
            buf.write('0' + v / 10)
            buf.write('0' + v % 10)
        else
            buf.write('0' + v)

end Differ
