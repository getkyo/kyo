package kyo.internal.tui.pipeline

import kyo.*

/** Test-only TerminalIO that uses provided streams. No system calls, no /dev/tty. Escape sequences use TerminalEscape constants for
  * consistency.
  */
class StreamTerminalIO(
    val out: java.io.ByteArrayOutputStream,
    val in: java.io.InputStream
)(using AllowUnsafe) extends TerminalIO:

    def enterRawMode(using Frame): Unit < Sync = ()
    def exitRawMode(using Frame): Unit < Sync  = ()

    def enterAlternateScreen(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            out.write(TerminalEscape.EnterSequence.getBytes)
            out.flush()
        }

    def exitAlternateScreen(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            out.write(TerminalEscape.ExitAlternateScreen.getBytes)
            out.flush()
        }

    def enableMouseTracking(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            out.write((TerminalEscape.EnableAllMotionMouse + TerminalEscape.EnableSgrMouse + TerminalEscape.EnableBracketedPaste).getBytes)
            out.flush()
        }

    def disableMouseTracking(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            out.write(
                (TerminalEscape.DisableBracketedPaste + TerminalEscape.DisableSgrMouse + TerminalEscape.DisableAllMotionMouse).getBytes
            )
            out.flush()
        }

    def showCursor(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            out.write(TerminalEscape.ShowCursor.getBytes)
            out.flush()
        }

    def hideCursor(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            out.write(TerminalEscape.HideCursor.getBytes)
            out.flush()
        }

    def size(using Frame): (Int, Int) < Sync =
        (80, 24) // fixed test size

    def write(bytes: Array[Byte])(using Frame): Unit < Sync =
        Sync.Unsafe.defer { out.write(bytes) }

    def flush(using Frame): Unit < Sync =
        Sync.Unsafe.defer { out.flush() }

    def readEvent(using Frame): InputEvent < Async =
        Async.sleep(Duration.Infinity).andThen(readEvent) // blocks forever — test must stop session

end StreamTerminalIO
