package kyo.internal.tui.pipeline

import kyo.*
import kyo.Test

class DifferTest extends Test:

    val black = RGB(0, 0, 0)
    val white = RGB(255, 255, 255)
    val red   = RGB(255, 0, 0)

    def mkCell(ch: Char, fg: RGB, bg: RGB = black, bold: Boolean = false): Cell =
        Cell(ch, fg, bg, bold, false, false, false, false)

    def gridWith(w: Int, h: Int, cells: (Int, Cell)*): CellGrid =
        val arr = Array.fill(w * h)(Cell.Empty)
        cells.foreach { (i, c) => arr(i) = c }
        CellGrid(w, h, Span.fromUnsafe(arr), Chunk.empty)
    end gridWith

    "Differ" - {
        "identical grids produce empty output" in {
            val grid   = CellGrid.empty(3, 2)
            val result = Differ.diff(grid, grid)
            assert(result.isEmpty)
        }

        "single cell change emits cursor + SGR + char" in {
            val prev   = CellGrid.empty(3, 2)
            val curr   = gridWith(3, 2, 0 -> mkCell('X', red))
            val result = Differ.diff(prev, curr)
            val s      = new String(result, "UTF-8")
            assert(s.contains("X"))
            assert(s.contains("[1;1H"))        // cursor move
            assert(s.contains("38;2;255;0;0")) // fg color
        }

        "same color not re-emitted on consecutive cells" in {
            val prev    = CellGrid.empty(3, 1)
            val curr    = gridWith(3, 1, 0 -> mkCell('A', red), 1 -> mkCell('B', red))
            val result  = Differ.diff(prev, curr)
            val s       = new String(result, "UTF-8")
            val fgCount = "38;2;255;0;0".r.findAllIn(s).size
            assert(fgCount == 1)
        }

        "full change emits all cells" in {
            val prev = CellGrid.empty(2, 2)
            val curr = gridWith(
                2,
                2,
                0 -> mkCell('A', white),
                1 -> mkCell('B', white),
                2 -> mkCell('C', white),
                3 -> mkCell('D', white)
            )
            val result = Differ.diff(prev, curr)
            val s      = new String(result, "UTF-8")
            assert(s.contains("A"))
            assert(s.contains("B"))
            assert(s.contains("C"))
            assert(s.contains("D"))
        }

        "bold attribute emits SGR 1" in {
            val prev   = CellGrid.empty(1, 1)
            val curr   = gridWith(1, 1, 0 -> mkCell('B', white, bold = true))
            val result = Differ.diff(prev, curr)
            val s      = new String(result, "UTF-8")
            assert(s.contains("\u001b[1m"))
        }

        "rawSequences appended after cells" in {
            val prev   = CellGrid.empty(2, 2)
            val data   = "IMG".getBytes("UTF-8")
            val curr   = CellGrid(2, 2, Span.fill(4)(Cell.Empty), Chunk((Rect(0, 0, 1, 1), data)))
            val result = Differ.diff(prev, curr)
            val s      = new String(result, "UTF-8")
            assert(s.contains("IMG"))
        }
    }

end DifferTest
