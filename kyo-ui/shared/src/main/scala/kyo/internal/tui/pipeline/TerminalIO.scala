package kyo.internal.tui.pipeline

import kyo.*

/** Abstraction over terminal I/O. All methods return computations (Rule 3). Platform implementations wrap system calls in Sync.Unsafe.defer
  * internally.
  *
  * The render loop composes these as pure computation chains — no AllowUnsafe in the loop.
  */
trait TerminalIO:
    // ---- Terminal mode ----
    def enterRawMode(using Frame): Unit < Sync
    def exitRawMode(using Frame): Unit < Sync
    def enterAlternateScreen(using Frame): Unit < Sync
    def exitAlternateScreen(using Frame): Unit < Sync

    // ---- Mouse tracking ----
    def enableMouseTracking(using Frame): Unit < Sync
    def disableMouseTracking(using Frame): Unit < Sync

    // ---- Cursor ----
    def showCursor(using Frame): Unit < Sync
    def hideCursor(using Frame): Unit < Sync

    // ---- Size ----
    def size(using Frame): (Int, Int) < Sync

    // ---- I/O ----
    def write(bytes: Array[Byte])(using Frame): Unit < Sync
    def flush(using Frame): Unit < Sync

    /** Read one complete input event. Suspends until a full event is available. Handles escape sequence buffering and timeouts internally.
      * The mutable parser state is an implementation detail.
      */
    def readEvent(using Frame): InputEvent < Async

    /** Check if input is available without blocking. Returns true if readEvent would return immediately. */
    def hasInput(using Frame): Boolean < Sync = Kyo.lift(false)

    /** Register a platform-specific shutdown hook to restore terminal state on process termination. Default no-op — platform
      * implementations override with actual hook registration.
      */
    def registerShutdownHook(using Frame): Unit < Sync = ()
end TerminalIO
