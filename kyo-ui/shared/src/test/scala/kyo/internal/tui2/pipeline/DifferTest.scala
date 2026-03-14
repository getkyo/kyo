package kyo.internal.tui2.pipeline

import kyo.*
import kyo.Test

class DifferTest extends Test:

    val black = PackedColor(0, 0, 0)
    val white = PackedColor(255, 255, 255)
    val red   = PackedColor(255, 0, 0)

    def cell(ch: Char, fg: PackedColor, bg: PackedColor = black, bold: Boolean = false): Cell =
        Cell(ch, fg, bg, bold, false, false, false, false)

    "Differ" - {
        "identical grids produce empty output" in {
            val grid   = CellGrid.empty(3, 2)
            val result = Differ.diff(grid, grid)
            assert(result.isEmpty)
        }

        "single cell change emits cursor + SGR + char" in {
            val prev = CellGrid.empty(3, 2)
            val curr = CellGrid.empty(3, 2)
            curr.cells(0) = cell('X', red)
            val result = Differ.diff(prev, curr)
            val s      = new String(result, "UTF-8")
            assert(s.contains("X"))
            assert(s.contains("[1;1H"))        // cursor move
            assert(s.contains("38;2;255;0;0")) // fg color
        }

        "same color not re-emitted on consecutive cells" in {
            val prev = CellGrid.empty(3, 1)
            val curr = CellGrid.empty(3, 1)
            curr.cells(0) = cell('A', red)
            curr.cells(1) = cell('B', red)
            val result  = Differ.diff(prev, curr)
            val s       = new String(result, "UTF-8")
            val fgCount = "38;2;255;0;0".r.findAllIn(s).size
            assert(fgCount == 1)
        }

        "full change emits all cells" in {
            val prev = CellGrid.empty(2, 2)
            val curr = CellGrid.empty(2, 2)
            for i <- 0 until 4 do
                curr.cells(i) = cell(('A' + i).toChar, white)
            val result = Differ.diff(prev, curr)
            val s      = new String(result, "UTF-8")
            assert(s.contains("A"))
            assert(s.contains("B"))
            assert(s.contains("C"))
            assert(s.contains("D"))
        }

        "bold attribute emits SGR 1" in {
            val prev = CellGrid.empty(1, 1)
            val curr = CellGrid.empty(1, 1)
            curr.cells(0) = cell('B', white, bold = true)
            val result = Differ.diff(prev, curr)
            val s      = new String(result, "UTF-8")
            assert(s.contains("\u001b[1m"))
        }

        "rawSequences appended after cells" in {
            val prev   = CellGrid.empty(2, 2)
            val data   = "IMG".getBytes("UTF-8")
            val curr   = CellGrid(2, 2, Array.fill(4)(Cell.Empty), Chunk((Rect(0, 0, 1, 1), data)))
            val result = Differ.diff(prev, curr)
            val s      = new String(result, "UTF-8")
            assert(s.contains("IMG"))
        }
    }

end DifferTest
