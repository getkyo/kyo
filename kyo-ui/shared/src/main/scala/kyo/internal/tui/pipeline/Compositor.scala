package kyo.internal.tui.pipeline

import kyo.*

/** Pure function: merge popup layer on top of base layer. */
object Compositor:
    def composite(base: CellGrid, popup: CellGrid): CellGrid =
        val cells = base.cells.mapIndexed { (i, baseCell) =>
            val popupCell = popup.cells(i)
            if popupCell != Cell.Empty then popupCell else baseCell
        }
        CellGrid(base.width, base.height, cells, base.rawSequences.concat(popup.rawSequences))
    end composite
end Compositor
