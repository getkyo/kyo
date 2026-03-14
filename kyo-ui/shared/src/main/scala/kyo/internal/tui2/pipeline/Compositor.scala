package kyo.internal.tui2.pipeline

import scala.annotation.tailrec

/** Pure function: merge popup layer on top of base layer. */
object Compositor:
    def composite(base: CellGrid, popup: CellGrid): CellGrid =
        val result = CellGrid.empty(base.width, base.height)
        @tailrec def loop(i: Int): Unit =
            if i < base.cells.length then
                result.cells(i) =
                    if popup.cells(i) != Cell.Empty then popup.cells(i)
                    else base.cells(i)
                loop(i + 1)
        loop(0)
        result.copy(rawSequences = base.rawSequences.concat(popup.rawSequences))
    end composite
end Compositor
