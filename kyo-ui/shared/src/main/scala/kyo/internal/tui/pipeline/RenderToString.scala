package kyo.internal.tui.pipeline

import kyo.*
import scala.annotation.tailrec

/** Headless rendering utility — renders UI to a plain text string without terminal I/O. Useful for testing and snapshot validation.
  */
object RenderToString:

    /** Render a UI tree to a string representation of the terminal grid. */
    def render(ui: UI, cols: Int, rows: Int, theme: Theme = Theme.Plain)(using Frame): String < (Async & Scope) =
        Sync.Unsafe.defer {
            val resolved = ResolvedTheme.resolve(theme)
            val state    = new ScreenState(resolved)
            Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows)).andThen {
                state.prevGrid match
                    case Present(grid) => gridToString(grid)
                    case _             => ""
            }
        }

    /** Convert a CellGrid to a plain text string (one line per row). */
    def gridToString(grid: CellGrid): String =
        val sb = new StringBuilder
        @tailrec def eachRow(row: Int): Unit =
            if row < grid.height then
                @tailrec def eachCol(col: Int): Unit =
                    if col < grid.width then
                        val cell = grid.cells(row * grid.width + col)
                        val ch   = if cell.char == '\u0000' then ' ' else cell.char
                        sb.append(ch)
                        eachCol(col + 1)
                eachCol(0)
                if row < grid.height - 1 then sb.append('\n')
                eachRow(row + 1)
        eachRow(0)
        sb.toString
    end gridToString

end RenderToString
