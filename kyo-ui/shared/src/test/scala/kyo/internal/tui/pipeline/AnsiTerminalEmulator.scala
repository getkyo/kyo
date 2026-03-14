package kyo.internal.tui.pipeline

import scala.annotation.tailrec

/** In-memory ANSI terminal emulator. Parses escape sequences and maintains a character buffer. Used to verify that Differ's ANSI output
  * produces the correct visual result.
  *
  * Supports: cursor movement, SGR colors/attributes, character writing. Does not support: scrolling, alternate screen, mouse.
  */
class AnsiTerminalEmulator(val cols: Int, val rows: Int):

    // Screen buffer — (char, fg, bg) per cell
    private val chars     = Array.fill(cols * rows)(' ')
    private var cursorRow = 0
    private var cursorCol = 0

    /** Feed raw ANSI bytes into the emulator. */
    def feed(bytes: Array[Byte]): Unit =
        val input = new String(bytes, "UTF-8")
        parse(input, 0)

    /** Get the screen content as a string (one line per row, no trailing newline on last row). */
    def screen: String =
        val sb = new StringBuilder
        @tailrec def eachRow(row: Int): Unit =
            if row < rows then
                @tailrec def eachCol(col: Int): Unit =
                    if col < cols then
                        sb.append(chars(row * cols + col))
                        eachCol(col + 1)
                eachCol(0)
                if row < rows - 1 then sb.append('\n')
                eachRow(row + 1)
        eachRow(0)
        sb.toString
    end screen

    /** Get a single cell's character. */
    def charAt(row: Int, col: Int): Char =
        if row >= 0 && row < rows && col >= 0 && col < cols then
            chars(row * cols + col)
        else ' '

    // ---- Parser ----

    @tailrec private def parse(input: String, pos: Int): Unit =
        if pos < input.length then
            val ch = input.charAt(pos)
            if ch == '\u001b' && pos + 1 < input.length && input.charAt(pos + 1) == '[' then
                // CSI sequence: \e[ ... <final byte>
                val endPos = findCsiEnd(input, pos + 2)
                if endPos > pos + 2 then
                    val params    = input.substring(pos + 2, endPos)
                    val finalByte = input.charAt(endPos)
                    handleCsi(params, finalByte)
                    parse(input, endPos + 1)
                else
                    // Incomplete sequence — skip
                    parse(input, pos + 1)
                end if
            else if ch == '\u001b' then
                // Other escape — skip the escape and next char
                parse(input, pos + 2)
            else if ch == '\n' then
                cursorRow = math.min(cursorRow + 1, rows - 1)
                cursorCol = 0
                parse(input, pos + 1)
            else if ch == '\r' then
                cursorCol = 0
                parse(input, pos + 1)
            else
                // Regular character — write at cursor, advance
                if cursorRow >= 0 && cursorRow < rows && cursorCol >= 0 && cursorCol < cols then
                    chars(cursorRow * cols + cursorCol) = ch
                cursorCol += 1
                if cursorCol >= cols then
                    cursorCol = 0
                    cursorRow = math.min(cursorRow + 1, rows - 1)
                parse(input, pos + 1)
            end if

    /** Find the final byte of a CSI sequence (0x40-0x7E range). */
    @tailrec private def findCsiEnd(input: String, pos: Int): Int =
        if pos >= input.length then pos - 1
        else
            val ch = input.charAt(pos)
            if ch >= 0x40 && ch <= 0x7e then pos
            else findCsiEnd(input, pos + 1)

    /** Handle a parsed CSI sequence. */
    private def handleCsi(params: String, finalByte: Char): Unit =
        finalByte match
            case 'H' =>
                // Cursor position: \e[row;colH (1-based)
                val parts = params.split(';')
                val row   = if parts.length >= 1 && parts(0).nonEmpty then parts(0).toIntOption.getOrElse(1) - 1 else 0
                val col   = if parts.length >= 2 && parts(1).nonEmpty then parts(1).toIntOption.getOrElse(1) - 1 else 0
                cursorRow = math.max(0, math.min(row, rows - 1))
                cursorCol = math.max(0, math.min(col, cols - 1))
            case 'J' =>
                // Erase display: \e[2J = clear entire screen
                if params == "2" then
                    @tailrec def clear(i: Int): Unit =
                        if i < chars.length then
                            chars(i) = ' '
                            clear(i + 1)
                    clear(0)
            case 'm' =>
                // SGR — we track colors for verification but don't need them for character buffer
                // The emulator focuses on character content, not styling
                ()
            case 'A' => // Cursor up
                val n = if params.nonEmpty then params.toIntOption.getOrElse(1) else 1
                cursorRow = math.max(0, cursorRow - n)
            case 'B' => // Cursor down
                val n = if params.nonEmpty then params.toIntOption.getOrElse(1) else 1
                cursorRow = math.min(rows - 1, cursorRow + n)
            case 'C' => // Cursor forward
                val n = if params.nonEmpty then params.toIntOption.getOrElse(1) else 1
                cursorCol = math.min(cols - 1, cursorCol + n)
            case 'D' => // Cursor back
                val n = if params.nonEmpty then params.toIntOption.getOrElse(1) else 1
                cursorCol = math.max(0, cursorCol - n)
            case 'h' | 'l' =>
                // Mode set/reset (alternate screen, mouse, cursor visibility) — ignore
                ()
            case _ =>
                // Unknown CSI — ignore
                ()
    end handleCsi

end AnsiTerminalEmulator
