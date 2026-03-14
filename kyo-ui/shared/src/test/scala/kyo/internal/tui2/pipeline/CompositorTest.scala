package kyo.internal.tui2.pipeline

import kyo.*
import kyo.Test

class CompositorTest extends Test:

    "Compositor" - {
        "empty popup returns base" in {
            val base  = CellGrid.empty(3, 2)
            val popup = CellGrid.empty(3, 2)
            base.cells(0) = Cell('A', PackedColor(255, 255, 255), PackedColor(0, 0, 0), false, false, false, false, false)
            val result = Compositor.composite(base, popup)
            assert(result.cells(0).char == 'A')
            assert(result.cells(1) == Cell.Empty)
        }

        "popup overrides base" in {
            val base  = CellGrid.empty(3, 2)
            val popup = CellGrid.empty(3, 2)
            base.cells(0) = Cell('A', PackedColor(255, 255, 255), PackedColor(0, 0, 0), false, false, false, false, false)
            popup.cells(0) = Cell('B', PackedColor(255, 0, 0), PackedColor(0, 0, 0), false, false, false, false, false)
            val result = Compositor.composite(base, popup)
            assert(result.cells(0).char == 'B')
            assert(result.cells(0).fg == 0xff0000)
        }

        "rawSequences concatenated" in {
            val base   = CellGrid(2, 2, Array.fill(4)(Cell.Empty), Chunk((Rect(0, 0, 1, 1), Array[Byte](1))))
            val popup  = CellGrid(2, 2, Array.fill(4)(Cell.Empty), Chunk((Rect(1, 1, 1, 1), Array[Byte](2))))
            val result = Compositor.composite(base, popup)
            assert(result.rawSequences.size == 2)
        }
    }

end CompositorTest
