package kyo.internal.tui.pipeline

import kyo.*
import org.scalatest.Assertions.*

/** Test harness for rendering UI and dispatching events. Manages state across render-dispatch-render cycles. */
class Screen(ui: UI, val cols: Int, val rows: Int)(using AllowUnsafe, Frame):
    private val state = new ScreenState(ResolvedTheme.resolve(Theme.Default))

    /** Render the UI and return the grid as a string. */
    def render: String < (Async & Scope) =
        Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows)).andThen(frame)

    /** Current grid as a string. */
    def frame: String =
        state.prevGrid match
            case Present(grid) => RenderToString.gridToString(grid)
            case _             => ""

    /** Assert the current frame matches expected (after padding lines to grid width). */
    def assertFrame(expected: String): org.scalatest.Assertion =
        val exp = expected.stripMargin
            .stripPrefix("\n")
            .linesIterator.toVector
            .map(_.padTo(cols, ' '))
            .mkString("\n")
        val actual = frame
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

    /** Dispatch an event and re-render. */
    def dispatch(event: InputEvent): String < (Async & Scope) =
        Pipeline.dispatchEvent(event, state).andThen(render)

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
end Screen
