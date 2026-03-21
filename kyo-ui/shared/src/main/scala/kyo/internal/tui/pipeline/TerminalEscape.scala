package kyo.internal.tui.pipeline

/** Shared terminal escape sequence constants. Referenced by both implementation and tests to prevent desynchronization.
  */
object TerminalEscape:
    val EnterAlternateScreen  = "\u001b[?1049h"
    val ExitAlternateScreen   = "\u001b[?1049l"
    val ClearScreen           = "\u001b[2J"
    val CursorHome            = "\u001b[H"
    val ShowCursor            = "\u001b[?25h"
    val HideCursor            = "\u001b[?25l"
    val EnableAllMotionMouse  = "\u001b[?1003h"
    val DisableAllMotionMouse = "\u001b[?1003l"
    val EnableSgrMouse        = "\u001b[?1006h"
    val DisableSgrMouse       = "\u001b[?1006l"
    val EnableBracketedPaste  = "\u001b[?2004h"
    val DisableBracketedPaste = "\u001b[?2004l"
    val ResetAttributes       = "\u001b[0m"
    val CursorStyleBar        = "\u001b[5 q" // blinking bar (I-beam)
    val CursorStyleBlock      = "\u001b[1 q" // blinking block
    val CursorStyleDefault    = "\u001b[0 q" // reset to default

    /** Position cursor at 1-based row, col. */
    def cursorPosition(row: Int, col: Int): String =
        s"\u001b[${row + 1};${col + 1}H"

    val EnterSequence: String =
        EnterAlternateScreen + ClearScreen + CursorHome

    val ExitSequence: String =
        DisableBracketedPaste + DisableSgrMouse + DisableAllMotionMouse +
            ShowCursor + ExitAlternateScreen + ResetAttributes
end TerminalEscape
