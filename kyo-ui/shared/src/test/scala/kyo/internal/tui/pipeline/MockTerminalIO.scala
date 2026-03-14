package kyo.internal.tui.pipeline

import kyo.*
import scala.collection.mutable

/** Mock TerminalIO for testing TuiBackend without a real terminal. Records setup/teardown calls, provides scripted input events via
  * Channel, captures ANSI output.
  */
class MockTerminalIO(
    termCols: Int,
    termRows: Int,
    val eventChannel: Channel[InputEvent]
)(using AllowUnsafe) extends TerminalIO:

    // ---- Recorded calls ----
    val setupCalls: mutable.ArrayBuffer[String]   = mutable.ArrayBuffer.empty
    val cleanupCalls: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    val writtenBytes: mutable.ArrayBuffer[Byte]   = mutable.ArrayBuffer.empty

    // ---- Terminal mode ----

    def enterRawMode(using Frame): Unit < Sync =
        Sync.Unsafe.defer { setupCalls.addOne("enterRawMode") }

    def exitRawMode(using Frame): Unit < Sync =
        Sync.Unsafe.defer { cleanupCalls.addOne("exitRawMode") }

    def enterAlternateScreen(using Frame): Unit < Sync =
        Sync.Unsafe.defer { setupCalls.addOne("enterAlternateScreen") }

    def exitAlternateScreen(using Frame): Unit < Sync =
        Sync.Unsafe.defer { cleanupCalls.addOne("exitAlternateScreen") }

    // ---- Mouse tracking ----

    def enableMouseTracking(using Frame): Unit < Sync =
        Sync.Unsafe.defer { setupCalls.addOne("enableMouseTracking") }

    def disableMouseTracking(using Frame): Unit < Sync =
        Sync.Unsafe.defer { cleanupCalls.addOne("disableMouseTracking") }

    // ---- Cursor ----

    def showCursor(using Frame): Unit < Sync =
        Sync.Unsafe.defer { cleanupCalls.addOne("showCursor") }

    def hideCursor(using Frame): Unit < Sync =
        Sync.Unsafe.defer { setupCalls.addOne("hideCursor") }

    // ---- Size ----

    def size(using Frame): (Int, Int) < Sync =
        Sync.Unsafe.defer { (termCols, termRows) }

    // ---- I/O ----

    def write(bytes: Array[Byte])(using Frame): Unit < Sync =
        Sync.Unsafe.defer { writtenBytes.addAll(bytes) }

    def flush(using Frame): Unit < Sync =
        Sync.Unsafe.defer { () }

    // ---- Input ----

    /** Takes from the channel — suspends if empty, resumes when an event is put or fiber is interrupted. */
    def readEvent(using Frame): InputEvent < Async =
        Abort.run[Closed](eventChannel.take).map {
            case Result.Success(event) => event
            case _                     => Async.sleep(Duration.Infinity).andThen(readEvent)
        }

end MockTerminalIO
