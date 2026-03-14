package kyo.internal.tui2.pipeline

import kyo.*
import kyo.Test

class CompositorTest extends Test:

    val white = RGB(255, 255, 255)
    val red   = RGB(255, 0, 0)
    val black = RGB(0, 0, 0)

    val cellA = Cell('A', white, black, false, false, false, false, false)
    val cellB = Cell('B', red, black, false, false, false, false, false)

    def gridWith(w: Int, h: Int, cells: (Int, Cell)*): CellGrid =
        val arr = Array.fill(w * h)(Cell.Empty)
        cells.foreach { (i, c) => arr(i) = c }
        CellGrid(w, h, Span.fromUnsafe(arr), Chunk.empty)
    end gridWith

    "Compositor" - {
        "empty popup returns base" in {
            val base   = gridWith(3, 2, 0 -> cellA)
            val popup  = CellGrid.empty(3, 2)
            val result = Compositor.composite(base, popup)
            assert(result.cells(0).char == 'A')
            assert(result.cells(1) == Cell.Empty)
        }

        "popup overrides base" in {
            val base   = gridWith(3, 2, 0 -> cellA)
            val popup  = gridWith(3, 2, 0 -> cellB)
            val result = Compositor.composite(base, popup)
            assert(result.cells(0).char == 'B')
            assert(result.cells(0).fg == red)
        }

        "rawSequences concatenated" in {
            val base   = CellGrid(2, 2, Span.fill(4)(Cell.Empty), Chunk((Rect(0, 0, 1, 1), Array[Byte](1))))
            val popup  = CellGrid(2, 2, Span.fill(4)(Cell.Empty), Chunk((Rect(1, 1, 1, 1), Array[Byte](2))))
            val result = Compositor.composite(base, popup)
            assert(result.rawSequences.size == 2)
        }
    }

end CompositorTest
