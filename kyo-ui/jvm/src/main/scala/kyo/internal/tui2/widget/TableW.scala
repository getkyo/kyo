package kyo.internal.tui2.widget

import kyo.*
import kyo.internal.tui2.*
import scala.annotation.tailrec

/** Table widget — computes column widths with colspan/rowspan support. */
private[kyo] object TableW:

    private val R = kyo.internal.tui2.widget.Render

    // Private occupancy buffer — avoids collision with FlexLayout scratch buffers
    private val occupancyBuf = new Array[Int](64)

    def render(
        table: UI.Table,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        if table.children.isEmpty then ()
        else
            val numCols = countLogicalColumns(table)
            if numCols <= 0 then ()
            else
                val colWidth = math.max(1, cw / numCols)
                // Zero the occupancy buffer for this frame
                java.util.Arrays.fill(occupancyBuf, 0, math.min(numCols, occupancyBuf.length), 0)

                @tailrec def renderRows(i: Int, curY: Int): Unit =
                    if i < table.children.size && curY < cy + ch then
                        table.children(i) match
                            case row: UI.Tr =>
                                decrementOccupied(numCols)
                                renderRow(row, cx, curY, cw, colWidth, numCols, rs, ctx)
                                renderRows(i + 1, curY + 1)
                            case _ =>
                                renderRows(i + 1, curY)
                        end match
                renderRows(0, cy)
            end if

    /** Count logical columns = max across all rows of sum(cell.colspan.getOrElse(1)). */
    private def countLogicalColumns(table: UI.Table): Int =
        @tailrec def loopRows(i: Int, maxCols: Int): Int =
            if i >= table.children.size then maxCols
            else
                table.children(i) match
                    case row: UI.Tr =>
                        val rowCols = sumColspans(row, 0, 0)
                        loopRows(i + 1, math.max(maxCols, rowCols))
                    case _ => loopRows(i + 1, maxCols)
        loopRows(0, 0)
    end countLogicalColumns

    @tailrec
    private def sumColspans(row: UI.Tr, i: Int, acc: Int): Int =
        if i >= row.children.size then acc
        else
            val cs = cellColspan(row.children(i))
            sumColspans(row, i + 1, acc + cs)

    /** Get colspan for a table cell element. Inline to avoid call overhead on hot path. */
    inline def cellColspan(child: UI): Int =
        child match
            case td: UI.Td => td.colspan.getOrElse(1)
            case th: UI.Th => th.colspan.getOrElse(1)
            case _         => 1

    /** Get rowspan for a table cell element. Inline to avoid call overhead on hot path. */
    inline def cellRowspan(child: UI): Int =
        child match
            case td: UI.Td => td.rowspan.getOrElse(1)
            case th: UI.Th => th.rowspan.getOrElse(1)
            case _         => 1

    private def renderRow(
        row: UI.Tr,
        cx: Int,
        cy: Int,
        cw: Int,
        colWidth: Int,
        numCols: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        @tailrec def loop(i: Int, logicalCol: Int): Unit =
            if i < row.children.size && logicalCol < numCols then
                val nextFreeCol = skipOccupied(logicalCol, numCols)
                if nextFreeCol >= numCols then ()
                else
                    val adjustedX  = cx + nextFreeCol * colWidth
                    val child      = row.children(i)
                    val cs         = cellColspan(child)
                    val heightSpan = cellRowspan(child)
                    val cellW =
                        if nextFreeCol + cs >= numCols then cx + cw - adjustedX
                        else cs * colWidth
                    markOccupied(nextFreeCol, cs, heightSpan)
                    child match
                        case cell: UI.Element =>
                            R.render(cell, adjustedX, cy, cellW, heightSpan, ctx)
                        case _ => ()
                    end match
                    loop(i + 1, nextFreeCol + cs)
                end if
        loop(0, 0)
    end renderRow

    @tailrec private def skipOccupied(col: Int, numCols: Int): Int =
        if col >= numCols then col
        else if occupancyBuf(col) > 0 then skipOccupied(col + 1, numCols)
        else col

    private def markOccupied(startCol: Int, colspan: Int, rowspan: Int): Unit =
        if rowspan > 1 then
            @tailrec def loop(c: Int): Unit =
                if c < startCol + colspan && c < occupancyBuf.length then
                    occupancyBuf(c) = rowspan - 1
                    loop(c + 1)
            loop(startCol)

    private def decrementOccupied(len: Int): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < len then
                if occupancyBuf(i) > 0 then occupancyBuf(i) -= 1
                loop(i + 1)
        loop(0)
    end decrementOccupied

end TableW
