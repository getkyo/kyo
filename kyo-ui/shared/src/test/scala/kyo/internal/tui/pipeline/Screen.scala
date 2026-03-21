package kyo.internal.tui.pipeline

import kyo.*
import org.scalatest.Assertions.*

/** Test harness for rendering UI and dispatching events. Manages state across render-dispatch-render cycles. */
class Screen(ui: UI, val cols: Int, val rows: Int, theme: Theme = Theme.Plain)(using AllowUnsafe, Frame):
    private val state = new ScreenState(ResolvedTheme.resolve(theme))

    /** Render the UI and return the grid as a string. */
    def render: String < (Async & Scope) =
        Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows)).andThen(frame)

    /** Current grid as a string. */
    def frame: String =
        state.prevGrid match
            case Present(grid) => RenderToString.gridToString(grid)
            case _             => ""

    /** Assert the current frame matches expected. Expected string uses triple-quote + stripMargin format:
      * {{{
      * s.assertFrame("""
      *     |hello
      *     |world
      *     """)
      * }}}
      * Leading/trailing newlines from triple-quotes are stripped. Each line is padded to `cols` width.
      */
    def assertFrame(expected: String): org.scalatest.Assertion =
        val stripped      = expected.stripMargin
        val isTripleQuote = stripped.startsWith("\n")
        val lines         = stripped.stripPrefix("\n").linesIterator.toVector
        val trimmed       = if isTripleQuote && lines.nonEmpty && lines.last.trim.isEmpty then lines.dropRight(1) else lines
        val exp           = trimmed.map(_.padTo(cols, ' ')).mkString("\n")
        val actual        = frame
        if actual != exp then
            fail(
                s"\nExpected:\n${exp.linesIterator.zipWithIndex.map((l, i) => f"$i%2d|$l|").mkString("\n")}" +
                    s"\nActual:\n${actual.linesIterator.zipWithIndex.map((l, i) => f"$i%2d|$l|").mkString("\n")}"
            )
        else
            succeed
        end if
    end assertFrame

    /** Render and assert the frame matches expected. */
    def renderAndAssert(expected: String): org.scalatest.Assertion < (Async & Scope) =
        render.andThen(assertFrame(expected))

    /** Dispatch an event, yield to let piping fibers settle, then re-render. */
    def dispatch(event: InputEvent): String < (Async & Scope) =
        Pipeline.dispatchEvent(event, state)
            .andThen(Async.sleep(10.millis))
            .andThen(render)

    def click(x: Int, y: Int): String < (Async & Scope) =
        dispatch(InputEvent.Mouse(MouseKind.LeftPress, x, y))

    def key(k: UI.Keyboard): String < (Async & Scope) =
        dispatch(InputEvent.Key(k, ctrl = false, alt = false, shift = false))

    def typeChar(c: Char): String < (Async & Scope) =
        key(UI.Keyboard.Char(c))

    def tab: String < (Async & Scope) =
        dispatch(InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = false))

    def shiftTab: String < (Async & Scope) =
        dispatch(InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = true))

    def enter: String < (Async & Scope) =
        key(UI.Keyboard.Enter)

    def backspace: String < (Async & Scope) =
        key(UI.Keyboard.Backspace)

    def arrowLeft: String < (Async & Scope) =
        key(UI.Keyboard.ArrowLeft)

    def arrowRight: String < (Async & Scope) =
        key(UI.Keyboard.ArrowRight)

    /** Assert all marker strings are present in the current frame. Fails with context on first missing marker. */
    def assertAllPresent(markers: String*): org.scalatest.Assertion =
        val f       = frame
        val missing = markers.filter(m => !f.contains(m))
        if missing.nonEmpty then
            fail(s"Missing: ${missing.mkString(", ")}\nFrame:\n${f.linesIterator.zipWithIndex.map((l, i) => f"$i%2d|$l|").mkString("\n")}")
        else succeed
    end assertAllPresent

    /** Assert no marker string appears in the current frame. */
    def assertNonePresent(markers: String*): org.scalatest.Assertion =
        val f     = frame
        val found = markers.filter(m => f.contains(m))
        if found.nonEmpty then
            fail(
                s"Should be absent but found: ${found.mkString(", ")}\nFrame:\n${f.linesIterator.zipWithIndex.map((l, i) => f"$i%2d|$l|").mkString("\n")}"
            )
        else succeed
        end if
    end assertNonePresent

    /** Find cursor info by traversing the layout tree for `cursorPosition` on handlers. Returns (contentX + cursorPosition, contentY) — the
      * absolute screen position.
      */
    def cursorPos: Maybe[(Int, Int)] =
        state.prevLayout match
            case Present(layout) => findCursorInLaid(layout.base)
            case _               => Absent

    private def findCursorInLaid(laid: Laid): Maybe[(Int, Int)] = laid match
        case n: Laid.Node =>
            n.handlers.cursorPosition match
                case Present((col, row)) => Maybe((n.content.x + col, n.content.y + row))
                case _ =>
                    var i = 0
                    while i < n.children.size do
                        val found = findCursorInLaid(n.children(i))
                        if found.nonEmpty then return found
                        i += 1
                    end while
                    Absent
        case _ => Absent

    def hasCursor: Boolean = cursorPos.nonEmpty
    def cursorCol: Int     = cursorPos.map(_._1).getOrElse(-1)
    def cursorRow: Int     = cursorPos.map(_._2).getOrElse(-1)

    /** Read a cell from the current grid for color/style assertions. */
    def cellAt(col: Int, row: Int): Maybe[Cell] =
        state.prevGrid match
            case Present(grid) =>
                if col >= 0 && col < grid.width && row >= 0 && row < grid.height then
                    Maybe(grid.cells(row * grid.width + col))
                else Absent
            case _ => Absent

    /** Check fg color of a cell. */
    def fgAt(col: Int, row: Int): RGB =
        cellAt(col, row).map(_.fg).getOrElse(RGB.Transparent)

    /** Check bg color of a cell. */
    def bgAt(col: Int, row: Int): RGB =
        cellAt(col, row).map(_.bg).getOrElse(RGB.Transparent)

    def focusableCount: Int             = state.focusableIds.size
    def focusableKeys: Chunk[WidgetKey] = state.focusableIds
    def focusedKey: Maybe[WidgetKey]    = state.focusedId.get()
    def layoutPresent: Boolean          = state.prevLayout.nonEmpty
    def findKeyInLayout(key: WidgetKey): Boolean =
        state.prevLayout match
            case Present(layout) => Dispatch.findByKey(layout.base, key).nonEmpty
            case _               => false
end Screen
