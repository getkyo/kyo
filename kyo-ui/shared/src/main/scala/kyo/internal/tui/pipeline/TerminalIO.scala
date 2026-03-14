package kyo.internal.tui.pipeline

import kyo.*

/** Abstraction over terminal I/O. All methods return computations (Rule 3).
  * Platform implementations wrap system calls in Sync.Unsafe.defer internally.
  *
  * The render loop composes these as pure computation chains — no AllowUnsafe in the loop.
  */
trait TerminalIO:
    // ---- Terminal mode ----
    def enterRawMode(using Frame): Unit < IO
    def exitRawMode(using Frame): Unit < IO
    def enterAlternateScreen(using Frame): Unit < IO
    def exitAlternateScreen(using Frame): Unit < IO

    // ---- Mouse tracking ----
    def enableMouseTracking(using Frame): Unit < IO
    def disableMouseTracking(using Frame): Unit < IO

    // ---- Cursor ----
    def showCursor(using Frame): Unit < IO
    def hideCursor(using Frame): Unit < IO

    // ---- Size ----
    def size(using Frame): (Int, Int) < IO

    // ---- I/O ----
    def write(bytes: Array[Byte])(using Frame): Unit < IO
    def flush(using Frame): Unit < IO

    /** Read one complete input event. Blocks until a full event is parsed.
      * Handles escape sequence buffering and timeouts internally.
      * The mutable parser state is an implementation detail.
      */
    def readEvent(using Frame): InputEvent < IO
end TerminalIO
