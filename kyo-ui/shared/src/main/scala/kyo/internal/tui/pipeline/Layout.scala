package kyo.internal.tui.pipeline

import kyo.*
import scala.annotation.tailrec

/** Pure function: Styled → LayoutResult. Measures and positions every node in the tree.
  *
  * Handles flex layout (row/column, grow/shrink, justify, align, wrap), table layout (column width distribution, colspan), popup
  * extraction, overlay positioning, scroll offsets, and overflow clipping. No state reads, no side effects.
  */
object Layout:

    // ---- Public entry point ----

    def layout(styled: Styled, viewport: Rect): LayoutResult =
        val popups    = ChunkBuilder.init[Laid]
        val rootAvail = resolveAvailable(styled, viewport)
        val base      = arrange(styled, rootAvail, viewport, popups)
        LayoutResult(base, popups.result())
    end layout

    /** Compute the available rect for a node based on its explicit size and parent available space. */
    private def resolveAvailable(node: Styled, parentAvail: Rect): Rect = node match
        case Styled.Node(_, cs, _, _) =>
            val w = cs.width match
                case Length.Auto => parentAvail.w
                case explicit    => Length.resolve(explicit, parentAvail.w)
            val h = cs.height match
                case Length.Auto => parentAvail.h
                case explicit    => Length.resolve(explicit, parentAvail.h)
            Rect(parentAvail.x, parentAvail.y, w, h)
        case _ => parentAvail

    // ---- Intrinsic size measurement ----

    /** Measure intrinsic width of a node without layout context. Percentages and Auto return 0. */
    private def measureWidth(node: Styled): Int = node match
        case Styled.Node(_, cs, _, children) =>
            Length.resolveOrAuto(cs.width, 0) match
                case Present(w) if w > 0 => w
                case _ =>
                    val chrome = Length.resolve(cs.padLeft, 0) + Length.resolve(cs.padRight, 0) +
                        cs.borderLeft.value.toInt + cs.borderRight.value.toInt +
                        Length.resolve(cs.marLeft, 0) + Length.resolve(cs.marRight, 0)
                    val childrenSize =
                        if cs.direction == Style.FlexDirection.column then maxChildWidth(children, 0, 0)
                        else sumChildWidths(children, Length.resolve(cs.gap, 0), 0, 0)
                    chrome + childrenSize
        case Styled.Text(value, cs) =>
            val lines = splitLines(value, Int.MaxValue, cs.textWrap)
            maxLineWidth(lines, Length.resolve(cs.letterSpacing, 0), 0, 0)
        case Styled.Cursor(_) => 1

    /** Measure intrinsic height of a node given a constrained width. Percentages and Auto return 0. */
    private def measureHeight(node: Styled, availWidth: Int): Int = node match
        case Styled.Node(_, cs, _, children) =>
            Length.resolveOrAuto(cs.height, 0) match
                case Present(h) if h > 0 => h
                case _ =>
                    val chrome = Length.resolve(cs.padTop, 0) + Length.resolve(cs.padBottom, 0) +
                        cs.borderTop.value.toInt + cs.borderBottom.value.toInt +
                        Length.resolve(cs.marTop, 0) + Length.resolve(cs.marBottom, 0)
                    val contentW = availWidth - chrome
                    val childrenSize =
                        if cs.direction == Style.FlexDirection.column then
                            sumChildHeights(children, Length.resolve(cs.gap, 0), contentW, 0, 0)
                        else maxChildHeight(children, contentW, 0, 0)
                    chrome + childrenSize
        case Styled.Text(value, cs) =>
            val spacing      = Length.resolve(cs.letterSpacing, 0)
            val charsPerLine = availWidth / math.max(1, 1 + spacing)
            val lines        = splitLines(value, charsPerLine, cs.textWrap)
            lines.size * cs.lineHeight
        case Styled.Cursor(_) => 1

    // Measurement helpers

    @tailrec private def maxChildWidth(children: Chunk[Styled], i: Int, max: Int): Int =
        if i >= children.size then max
        else maxChildWidth(children, i + 1, math.max(max, measureWidth(children(i))))

    @tailrec private def sumChildWidths(children: Chunk[Styled], gap: Int, i: Int, sum: Int): Int =
        if i >= children.size then sum
        else sumChildWidths(children, gap, i + 1, sum + measureWidth(children(i)) + (if i > 0 then gap else 0))

    @tailrec private def maxChildHeight(children: Chunk[Styled], availW: Int, i: Int, max: Int): Int =
        if i >= children.size then max
        else maxChildHeight(children, availW, i + 1, math.max(max, measureHeight(children(i), availW)))

    @tailrec private def sumChildHeights(children: Chunk[Styled], gap: Int, availW: Int, i: Int, sum: Int): Int =
        if i >= children.size then sum
        else sumChildHeights(children, gap, availW, i + 1, sum + measureHeight(children(i), availW) + (if i > 0 then gap else 0))

    @tailrec private def maxLineWidth(lines: Chunk[String], letterSpacing: Int, i: Int, max: Int): Int =
        if i >= lines.size then max
        else maxLineWidth(lines, letterSpacing, i + 1, math.max(max, lines(i).length * (1 + letterSpacing)))

    // ---- Main layout: arrange ----

    /** Position a node within the given available rect. The available rect is authoritative — it represents the size assigned by the parent
      * (flex, table, or viewport).
      */
    private def arrange(
        node: Styled,
        available: Rect,
        clip: Rect,
        popups: ChunkBuilder[Laid]
    ): Laid = node match
        case Styled.Text(value, cs) =>
            val w = math.min(measureWidth(node), available.w)
            val h = measureHeight(node, available.w)
            Laid.Text(value, cs, Rect(available.x, available.y, w, h), clip)

        case Styled.Cursor(charOffset) =>
            Laid.Cursor(Rect(available.x + charOffset, available.y, 1, 1))

        case Styled.Node(tag, cs, handlers, children) =>
            val marL = Length.resolve(cs.marLeft, available.w)
            val marR = Length.resolve(cs.marRight, available.w)
            val marT = Length.resolve(cs.marTop, available.h)

            // available.w is authoritative (set by parent flex/table/viewport)
            val outerX = available.x + marL
            val outerY = available.y + marT
            val outerW = available.w - marL - marR

            val brdL = cs.borderLeft.value.toInt
            val brdR = cs.borderRight.value.toInt
            val brdT = cs.borderTop.value.toInt
            val brdB = cs.borderBottom.value.toInt
            val padL = Length.resolve(cs.padLeft, outerW)
            val padR = Length.resolve(cs.padRight, outerW)
            val padT = Length.resolve(cs.padTop, outerW)
            val padB = Length.resolve(cs.padBottom, outerW)

            val contentX = outerX + brdL + padL
            val contentY = outerY + brdT + padT
            val contentW = outerW - brdL - brdR - padL - padR

            // Height: use available.h (set by parent/flex), Auto means content-determined
            val marB = Length.resolve(cs.marBottom, available.h)
            val explicitH = cs.height match
                case Length.Auto => Absent
                case _           => Maybe(available.h - marT - marB)

            // Separate children: popups are extracted, overlays deferred, rest flows
            val (flow, overlays) =
                categorizeChildren(children, outerX, outerY, explicitH, available, popups, clip)

            // Flex or table layout for flow children
            val contentH = explicitH.getOrElse(Int.MaxValue) - brdT - brdB - padT - padB
            val laidChildren =
                if tag == ElemTag.Table then
                    layoutTable(flow, contentX, contentY, contentW, clip, popups)
                else
                    layoutFlex(
                        flow,
                        cs,
                        contentX,
                        contentY,
                        contentW,
                        math.max(0, contentH),
                        clip,
                        cs.scrollTop,
                        cs.scrollLeft,
                        popups
                    )

            // Compute actual height if auto
            val actualContentH =
                if explicitH.nonEmpty then math.max(0, contentH)
                else computeContentHeight(laidChildren, contentY)

            val actualOuterH =
                if explicitH.nonEmpty then explicitH.get
                else actualContentH + brdT + brdB + padT + padB

            // Layout overlay children
            val allChildren =
                if overlays.isEmpty then laidChildren
                else laidChildren.concat(layoutOverlayChildren(overlays, contentX, contentY, contentW, actualContentH, clip, popups))

            // Compute clip for children
            val childClip =
                if cs.overflow == Style.Overflow.hidden || cs.overflow == Style.Overflow.scroll then
                    clip.intersect(Rect(contentX, contentY, contentW, actualContentH))
                else clip

            val bounds  = Rect(outerX, outerY, outerW, actualOuterH)
            val content = Rect(contentX, contentY, contentW, actualContentH)
            Laid.Node(tag, cs, handlers, bounds, content, childClip, allChildren)

    // ---- Child categorization ----

    private def categorizeChildren(
        children: Chunk[Styled],
        outerX: Int,
        outerY: Int,
        outerH: Maybe[Int],
        available: Rect,
        popups: ChunkBuilder[Laid],
        clip: Rect
    ): (Chunk[Styled], Chunk[Styled]) =
        @tailrec def loop(i: Int, flow: Chunk[Styled], overlays: Chunk[Styled]): (Chunk[Styled], Chunk[Styled]) =
            if i >= children.size then (flow, overlays)
            else
                children(i) match
                    case n: Styled.Node if n.tag == ElemTag.Popup =>
                        val h         = outerH.getOrElse(0)
                        val popupRect = Rect(outerX, outerY + math.max(h, 0) + 1, available.w, available.h)
                        popups.addOne(arrange(n, popupRect, Rect(0, 0, available.w, available.h), popups))
                        loop(i + 1, flow, overlays)
                    case n: Styled.Node if n.style.position == Style.Position.overlay =>
                        loop(i + 1, flow, overlays.append(n))
                    case other =>
                        loop(i + 1, flow.append(other), overlays)
        loop(0, Chunk.empty, Chunk.empty)
    end categorizeChildren

    // ---- Content height computation ----

    private def computeContentHeight(children: Chunk[Laid], contentY: Int): Int =
        if children.isEmpty then 0
        else findMaxBottom(children, contentY, 0, contentY)

    @tailrec private def findMaxBottom(children: Chunk[Laid], contentY: Int, i: Int, maxY: Int): Int =
        if i >= children.size then maxY - contentY
        else
            val bottom = children(i) match
                case n: Laid.Node   => n.bounds.y + n.bounds.h
                case t: Laid.Text   => t.bounds.y + t.bounds.h
                case c: Laid.Cursor => c.pos.y + c.pos.h
            findMaxBottom(children, contentY, i + 1, math.max(maxY, bottom))

    // ---- Overlay layout ----

    private def layoutOverlayChildren(
        overlays: Chunk[Styled],
        contentX: Int,
        contentY: Int,
        contentW: Int,
        contentH: Int,
        clip: Rect,
        popups: ChunkBuilder[Laid]
    ): Chunk[Laid] =
        @tailrec def loop(i: Int, acc: Chunk[Laid]): Chunk[Laid] =
            if i >= overlays.size then acc
            else
                overlays(i) match
                    case ov: Styled.Node =>
                        val ovX   = contentX + Length.resolve(ov.style.translateX, contentW)
                        val ovY   = contentY + Length.resolve(ov.style.translateY, contentH)
                        val avail = resolveAvailable(ov, Rect(ovX, ovY, contentW, contentH))
                        val laid  = arrange(ov, avail, clip, popups)
                        loop(i + 1, acc.append(laid))
                    case other =>
                        val laid = arrange(other, Rect(contentX, contentY, contentW, contentH), clip, popups)
                        loop(i + 1, acc.append(laid))
        loop(0, Chunk.empty)
    end layoutOverlayChildren

    // ---- Flex layout ----

    private def layoutFlex(
        children: Chunk[Styled],
        cs: FlatStyle,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        clip: Rect,
        scrollTop: Int,
        scrollLeft: Int,
        popups: ChunkBuilder[Laid]
    ): Chunk[Laid] =
        if children.isEmpty then Chunk.empty
        else
            val isColumn  = cs.direction == Style.FlexDirection.column
            val mainSize  = if isColumn then ch else cw
            val crossSize = if isColumn then cw else ch
            val n         = children.size
            val gapPx     = Length.resolve(cs.gap, if isColumn then ch else cw)

            // Measurement arrays — mutable parallel arrays for flex distribution
            // (grow/shrink modifies sizes in-place, freshly allocated per call)
            val childMainSizes  = new Array[Int](n)
            val childCrossSizes = new Array[Int](n)
            val childGrow       = new Array[Double](n)
            val childShrink     = new Array[Double](n)

            // Measure each child
            val totalMain =
                measureFlexChildren(children, isColumn, cw, ch, gapPx, childMainSizes, childCrossSizes, childGrow, childShrink, 0, 0)

            // Distribute: grow or shrink
            val freeSpace = mainSize - totalMain
            if freeSpace > 0 then
                val totalGrowFactor = sumDoubles(childGrow, 0, 0.0)
                if totalGrowFactor > 0 then
                    distributeGrow(childMainSizes, childGrow, freeSpace, totalGrowFactor, n, 0)
            else if freeSpace < 0 then
                val totalShrinkFactor = sumDoubles(childShrink, 0, 0.0)
                if totalShrinkFactor > 0 then
                    distributeShrink(childMainSizes, childShrink, -freeSpace, totalShrinkFactor, n, 0)
            end if

            // Justify: compute starting position and extra spacing between items
            val actualTotal = sumMainWithGaps(childMainSizes, gapPx, n, 0, 0)
            val remaining   = mainSize - actualTotal

            val startPos = cs.justify match
                case Style.Justification.start        => 0
                case Style.Justification.center       => remaining / 2
                case Style.Justification.end          => remaining
                case Style.Justification.spaceBetween => 0
                case Style.Justification.spaceAround  => if n > 0 then remaining / (n * 2) else 0
                case Style.Justification.spaceEvenly  => if n > 0 then remaining / (n + 1) else 0
                case _                                => 0

            val extraGap = cs.justify match
                case Style.Justification.spaceBetween => if n > 1 then remaining / (n - 1) else 0
                case Style.Justification.spaceAround  => if n > 0 then remaining / n else 0
                case Style.Justification.spaceEvenly  => if n > 0 then remaining / (n + 1) else 0
                case _                                => 0

            // Position each child
            positionChildren(
                children,
                childMainSizes,
                childCrossSizes,
                cs.align,
                isColumn,
                cx,
                cy,
                crossSize,
                gapPx + extraGap,
                scrollTop,
                scrollLeft,
                clip,
                popups,
                0,
                startPos,
                Chunk.empty
            )

    // Flex measurement

    @tailrec private def measureFlexChildren(
        children: Chunk[Styled],
        isColumn: Boolean,
        cw: Int,
        ch: Int,
        gap: Int,
        mainSizes: Array[Int],
        crossSizes: Array[Int],
        grow: Array[Double],
        shrink: Array[Double],
        i: Int,
        totalMain: Int
    ): Int =
        if i >= children.size then totalMain
        else
            children(i) match
                case nd: Styled.Node =>
                    val mainParent  = if isColumn then ch else cw
                    val crossParent = if isColumn then cw else ch
                    val mainLen     = if isColumn then nd.style.height else nd.style.width
                    val crossLen    = if isColumn then nd.style.width else nd.style.height
                    mainSizes(i) = mainLen match
                        case Length.Auto => if isColumn then measureHeight(nd, cw) else measureWidth(nd)
                        case explicit    => Length.resolve(explicit, mainParent)
                    crossSizes(i) = crossLen match
                        case Length.Auto =>
                            val intrinsic = if isColumn then measureWidth(nd) else measureHeight(nd, cw)
                            if intrinsic > 0 then intrinsic else crossParent // auto fills cross axis
                        case explicit => Length.resolve(explicit, crossParent)
                    grow(i) = nd.style.flexGrow
                    shrink(i) = nd.style.flexShrink
                case t: Styled.Text =>
                    mainSizes(i) = if isColumn then measureHeight(t, cw) else measureWidth(t)
                    crossSizes(i) = if isColumn then measureWidth(t) else measureHeight(t, cw)
                case _: Styled.Cursor =>
                    mainSizes(i) = 1
                    crossSizes(i) = 1
            end match
            measureFlexChildren(
                children,
                isColumn,
                cw,
                ch,
                gap,
                mainSizes,
                crossSizes,
                grow,
                shrink,
                i + 1,
                totalMain + mainSizes(i) + (if i > 0 then gap else 0)
            )

    // Flex distribution

    @tailrec private def distributeGrow(sizes: Array[Int], grow: Array[Double], freeSpace: Int, totalGrow: Double, n: Int, i: Int): Unit =
        if i < n then
            if grow(i) > 0 then
                sizes(i) += (freeSpace * grow(i) / totalGrow).toInt
            distributeGrow(sizes, grow, freeSpace, totalGrow, n, i + 1)

    @tailrec private def distributeShrink(
        sizes: Array[Int],
        shrink: Array[Double],
        deficit: Int,
        totalShrink: Double,
        n: Int,
        i: Int
    ): Unit =
        if i < n then
            if shrink(i) > 0 then
                sizes(i) = math.max(0, sizes(i) - (deficit * shrink(i) / totalShrink).toInt)
            distributeShrink(sizes, shrink, deficit, totalShrink, n, i + 1)

    @tailrec private def sumDoubles(arr: Array[Double], i: Int, acc: Double): Double =
        if i >= arr.length then acc
        else sumDoubles(arr, i + 1, acc + arr(i))

    @tailrec private def sumMainWithGaps(sizes: Array[Int], gap: Int, n: Int, i: Int, acc: Int): Int =
        if i >= n then acc
        else sumMainWithGaps(sizes, gap, n, i + 1, acc + sizes(i) + (if i > 0 then gap else 0))

    // Flex positioning

    @tailrec private def positionChildren(
        children: Chunk[Styled],
        mainSizes: Array[Int],
        crossSizes: Array[Int],
        align: Style.Alignment,
        isColumn: Boolean,
        cx: Int,
        cy: Int,
        crossSize: Int,
        totalGap: Int,
        scrollTop: Int,
        scrollLeft: Int,
        clip: Rect,
        popups: ChunkBuilder[Laid],
        i: Int,
        mainPos: Int,
        acc: Chunk[Laid]
    ): Chunk[Laid] =
        if i >= children.size then acc
        else
            val childMain  = mainSizes(i)
            val childCross = crossSizes(i)

            val crossPos = align match
                case Style.Alignment.start   => 0
                case Style.Alignment.center  => (crossSize - childCross) / 2
                case Style.Alignment.end     => crossSize - childCross
                case Style.Alignment.stretch => 0
                case _                       => 0

            val childCrossActual = if align == Style.Alignment.stretch then crossSize else childCross

            val (childX, childY, childW, childH) =
                if isColumn then (cx + crossPos - scrollLeft, cy + mainPos - scrollTop, childCrossActual, childMain)
                else (cx + mainPos - scrollLeft, cy + crossPos - scrollTop, childMain, childCrossActual)

            val laid = arrange(children(i), Rect(childX, childY, childW, childH), clip, popups)
            positionChildren(
                children,
                mainSizes,
                crossSizes,
                align,
                isColumn,
                cx,
                cy,
                crossSize,
                totalGap,
                scrollTop,
                scrollLeft,
                clip,
                popups,
                i + 1,
                mainPos + childMain + totalGap,
                acc.append(laid)
            )

    // ---- Table layout ----

    private def layoutTable(
        rows: Chunk[Styled],
        cx: Int,
        cy: Int,
        cw: Int,
        clip: Rect,
        popups: ChunkBuilder[Laid]
    ): Chunk[Laid] =
        val numCols = countTableColumns(rows, 0, 0)
        if numCols == 0 then Chunk.empty
        else
            val colWidths = new Array[Int](numCols)
            measureTableColumns(rows, colWidths, 0)

            val usedWidth = sumInts(colWidths, 0, 0)
            if usedWidth < cw then
                val extra = (cw - usedWidth) / numCols
                addToAll(colWidths, extra, 0)

            layoutTableRows(rows, colWidths, numCols, cx, cy, cw, clip, popups, 0, cy, Chunk.empty)
        end if
    end layoutTable

    @tailrec private def countTableColumns(rows: Chunk[Styled], i: Int, maxCols: Int): Int =
        if i >= rows.size then maxCols
        else
            rows(i) match
                case Styled.Node(_, _, _, cells) =>
                    countTableColumns(rows, i + 1, math.max(maxCols, countRowCols(cells, 0, 0)))
                case _ =>
                    countTableColumns(rows, i + 1, maxCols)

    @tailrec private def countRowCols(cells: Chunk[Styled], j: Int, count: Int): Int =
        if j >= cells.size then count
        else
            val span = cells(j) match
                case n: Styled.Node => n.handlers.colspan
                case _              => 1
            countRowCols(cells, j + 1, count + span)

    @tailrec private def measureTableColumns(rows: Chunk[Styled], colWidths: Array[Int], i: Int): Unit =
        if i < rows.size then
            rows(i) match
                case Styled.Node(_, _, _, cells) =>
                    measureRowColumns(cells, colWidths, 0, 0)
                case _ =>
            end match
            measureTableColumns(rows, colWidths, i + 1)

    @tailrec private def measureRowColumns(cells: Chunk[Styled], colWidths: Array[Int], j: Int, col: Int): Unit =
        if j < cells.size then
            val colspan = cells(j) match
                case n: Styled.Node => n.handlers.colspan
                case _              => 1
            if colspan == 1 && col < colWidths.length then
                colWidths(col) = math.max(colWidths(col), measureWidth(cells(j)))
            measureRowColumns(cells, colWidths, j + 1, col + colspan)

    @tailrec private def sumInts(arr: Array[Int], i: Int, acc: Int): Int =
        if i >= arr.length then acc
        else sumInts(arr, i + 1, acc + arr(i))

    @tailrec private def addToAll(arr: Array[Int], extra: Int, i: Int): Unit =
        if i < arr.length then
            arr(i) += extra
            addToAll(arr, extra, i + 1)

    @tailrec private def layoutTableRows(
        rows: Chunk[Styled],
        colWidths: Array[Int],
        numCols: Int,
        cx: Int,
        cy: Int,
        cw: Int,
        clip: Rect,
        popups: ChunkBuilder[Laid],
        i: Int,
        rowY: Int,
        acc: Chunk[Laid]
    ): Chunk[Laid] =
        if i >= rows.size then acc
        else
            rows(i) match
                case row: Styled.Node =>
                    val (cellLaid, rowHeight) =
                        layoutTableCells(row.children, colWidths, numCols, cx, rowY, clip, popups, 0, 0, cx, 0, Chunk.empty)
                    val rowNode = Laid.Node(
                        ElemTag.Div,
                        row.style,
                        row.handlers,
                        Rect(cx, rowY, cw, rowHeight),
                        Rect(cx, rowY, cw, rowHeight),
                        clip,
                        cellLaid
                    )
                    layoutTableRows(rows, colWidths, numCols, cx, cy, cw, clip, popups, i + 1, rowY + rowHeight, acc.append(rowNode))
                case other =>
                    val laid = arrange(other, Rect(cx, rowY, cw, 1), clip, popups)
                    layoutTableRows(rows, colWidths, numCols, cx, cy, cw, clip, popups, i + 1, rowY + 1, acc.append(laid))

    @tailrec private def layoutTableCells(
        cells: Chunk[Styled],
        colWidths: Array[Int],
        numCols: Int,
        cx: Int,
        rowY: Int,
        clip: Rect,
        popups: ChunkBuilder[Laid],
        j: Int,
        col: Int,
        cellX: Int,
        rowHeight: Int,
        acc: Chunk[Laid]
    ): (Chunk[Laid], Int) =
        if j >= cells.size then (acc, rowHeight)
        else
            val cell = cells(j)
            val colspan = cell match
                case n: Styled.Node => n.handlers.colspan
                case _              => 1
            val cellW = spanWidth(colWidths, numCols, col, colspan, 0, 0)
            val cellH = measureHeight(cell, cellW)
            val laid  = arrange(cell, Rect(cellX, rowY, cellW, cellH), clip, popups)
            layoutTableCells(
                cells,
                colWidths,
                numCols,
                cx,
                rowY,
                clip,
                popups,
                j + 1,
                col + colspan,
                cellX + cellW,
                math.max(rowHeight, cellH),
                acc.append(laid)
            )

    @tailrec private def spanWidth(colWidths: Array[Int], numCols: Int, col: Int, colspan: Int, k: Int, w: Int): Int =
        if k >= colspan || col + k >= numCols then w
        else spanWidth(colWidths, numCols, col, colspan, k + 1, w + colWidths(col + k))

    // ---- Helpers ----

    /** Split text into lines, applying word wrap at `maxWidth` characters. */
    private[pipeline] def splitLines(text: String, maxWidth: Int, textWrap: Style.TextWrap): Chunk[String] =
        if textWrap == Style.TextWrap.noWrap then Chunk(text)
        else
            val parts = text.split('\n')
            splitParts(parts, maxWidth, 0, Chunk.empty)

    @tailrec private def splitParts(parts: Array[String], maxWidth: Int, i: Int, acc: Chunk[String]): Chunk[String] =
        if i >= parts.length then acc
        else
            val line = parts(i)
            if line.length <= maxWidth || maxWidth <= 0 then
                splitParts(parts, maxWidth, i + 1, acc.append(line))
            else
                splitParts(parts, maxWidth, i + 1, wrapLine(line, maxWidth, 0, acc))
            end if

    @tailrec private def wrapLine(line: String, maxWidth: Int, pos: Int, acc: Chunk[String]): Chunk[String] =
        if pos >= line.length then acc
        else
            val end = math.min(pos + maxWidth, line.length)
            wrapLine(line, maxWidth, end, acc.append(line.substring(pos, end)))

end Layout
