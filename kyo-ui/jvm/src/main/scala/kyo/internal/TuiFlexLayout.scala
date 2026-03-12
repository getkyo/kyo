package kyo.internal

import scala.annotation.tailrec

/** Flex-based layout engine operating on TuiLayout flat arrays.
  *
  * Reads and writes TuiLayout arrays only — no other dependencies. Pure arithmetic.
  */
private[kyo] object TuiFlexLayout:

    /** Bottom-up intrinsic sizing. Reverse traversal ensures children measured before parents. */
    def measure(layout: TuiLayout): Unit =

        inline def sumChildren(layout: TuiLayout, firstChild: Int, row: Boolean)(
            inline f: (Int, Int, Int) => Unit
        ): Unit =
            @tailrec def loop(c: Int, mainSum: Int, crossMax: Int, childCount: Int): Unit =
                if c == -1 then f(mainSum, crossMax, childCount)
                else if TuiLayout.isHidden(layout.lFlags(c)) || TuiLayout.isOverlay(layout.lFlags(c)) then
                    loop(layout.nextSibling(c), mainSum, crossMax, childCount)
                else
                    val cMarW = layout.marL(c) + layout.marR(c)
                    val cMarH = layout.marT(c) + layout.marB(c)
                    val cw    = layout.intrW(c) + cMarW
                    val ch    = layout.intrH(c) + cMarH
                    if row then loop(layout.nextSibling(c), mainSum + cw, math.max(crossMax, ch), childCount + 1)
                    else loop(layout.nextSibling(c), mainSum + ch, math.max(crossMax, cw), childCount + 1)
            loop(firstChild, 0, 0, 0)
        end sumChildren

        @tailrec def loop(i: Int): Unit =
            if i >= 0 then
                if !TuiLayout.isHidden(layout.lFlags(i)) then
                    val maybeTxt = layout.text(i)
                    if maybeTxt.isDefined then
                        layout.intrW(i) = TuiText.naturalWidth(maybeTxt.get)
                        // Preserve wrapped height from a previous arrange pass (text wrapping sets intrH > naturalHeight)
                        layout.intrH(i) = math.max(TuiText.naturalHeight(maybeTxt.get), layout.intrH(i))
                    else
                        val flags = layout.lFlags(i)
                        val row   = TuiLayout.isRow(flags)
                        sumChildren(layout, layout.firstChild(i), row) { (mainSum, crossMax, childCount) =>
                            val gapTotal = if childCount > 1 then layout.gap(i) * (childCount - 1) else 0
                            val bT       = if TuiLayout.hasBorderT(flags) then 1 else 0
                            val bR       = if TuiLayout.hasBorderR(flags) then 1 else 0
                            val bB       = if TuiLayout.hasBorderB(flags) then 1 else 0
                            val bL       = if TuiLayout.hasBorderL(flags) then 1 else 0
                            val insetW   = layout.padL(i) + layout.padR(i) + bL + bR
                            val insetH   = layout.padT(i) + layout.padB(i) + bT + bB

                            if row then
                                layout.intrW(i) = mainSum + gapTotal + insetW
                                layout.intrH(i) = crossMax + insetH
                            else
                                layout.intrW(i) = crossMax + insetW
                                layout.intrH(i) = mainSum + gapTotal + insetH
                            end if
                        }
                    end if

                    // Apply explicit size, then clamp
                    if layout.sizeW(i) >= 0 then layout.intrW(i) = layout.sizeW(i)
                    if layout.sizeH(i) >= 0 then layout.intrH(i) = layout.sizeH(i)
                    if layout.minW(i) >= 0 && layout.intrW(i) < layout.minW(i) then layout.intrW(i) = layout.minW(i)
                    if layout.maxW(i) >= 0 && layout.intrW(i) > layout.maxW(i) then layout.intrW(i) = layout.maxW(i)
                    if layout.minH(i) >= 0 && layout.intrH(i) < layout.minH(i) then layout.intrH(i) = layout.minH(i)
                    if layout.maxH(i) >= 0 && layout.intrH(i) > layout.maxH(i) then layout.intrH(i) = layout.maxH(i)
                end if
                loop(i - 1)

        loop(layout.count - 1)
    end measure

    /** Top-down position assignment. Root fills terminal, children positioned by parent. */
    def arrange(layout: TuiLayout, termW: Int, termH: Int): Unit =
        if layout.count != 0 then
            // Root node fills terminal
            layout.x(0) = 0
            layout.y(0) = 0
            layout.w(0) = termW
            layout.h(0) = termH

            /** Count children, total main size, total flex grow, total flex shrink. CPS to avoid allocation. */
            inline def countChildrenLoop(layout: TuiLayout, firstChild: Int, row: Boolean)(
                inline f: (Int, Int, Double, Double) => Unit
            ): Unit =
                @tailrec def loop(c: Int, totalMain: Int, childCount: Int, totalGrow: Double, totalShrink: Double): Unit =
                    if c == -1 then f(totalMain, childCount, totalGrow, totalShrink)
                    else if TuiLayout.isHidden(layout.lFlags(c)) || TuiLayout.isOverlay(layout.lFlags(c)) then
                        loop(layout.nextSibling(c), totalMain, childCount, totalGrow, totalShrink)
                    else
                        val cMain =
                            if row then layout.intrW(c) + layout.marL(c) + layout.marR(c)
                            else layout.intrH(c) + layout.marT(c) + layout.marB(c)
                        loop(
                            layout.nextSibling(c),
                            totalMain + cMain,
                            childCount + 1,
                            totalGrow + layout.flexGrow(c),
                            totalShrink + layout.flexShrink(c)
                        )
                loop(firstChild, 0, 0, 0.0, 0.0)
            end countChildrenLoop

            @tailrec def positionChildren(
                layout: TuiLayout,
                c: Int,
                row: Boolean,
                contentX: Int,
                contentY: Int,
                contentH: Int,
                contentW: Int,
                alignMode: Int,
                betweenGap: Int,
                childCount: Int,
                pos: Int,
                childIdx: Int,
                freeSpace: Int,
                totalGrow: Double,
                totalShrink: Double
            ): Unit =
                if c != -1 then
                    if TuiLayout.isHidden(layout.lFlags(c)) || TuiLayout.isOverlay(layout.lFlags(c)) then
                        positionChildren(
                            layout,
                            layout.nextSibling(c),
                            row,
                            contentX,
                            contentY,
                            contentH,
                            contentW,
                            alignMode,
                            betweenGap,
                            childCount,
                            pos,
                            childIdx,
                            freeSpace,
                            totalGrow,
                            totalShrink
                        )
                    else
                        val cMarL = layout.marL(c)
                        val cMarR = layout.marR(c)
                        val cMarT = layout.marT(c)
                        val cMarB = layout.marB(c)

                        val cMainSize    = if row then layout.intrW(c) else layout.intrH(c)
                        val cCrossSize   = if row then layout.intrH(c) else layout.intrW(c)
                        val cMainMargin  = if row then cMarL + cMarR else cMarT + cMarB
                        val cCrossMargin = if row then cMarT + cMarB else cMarL + cMarR
                        val crossSpace   = if row then contentH else contentW

                        // Flex grow/shrink adjustment
                        val flexExtra =
                            if freeSpace > 0 && totalGrow > 0.0 then
                                val grow = layout.flexGrow(c)
                                if grow > 0.0 then (freeSpace * grow / totalGrow).toInt else 0
                            else if freeSpace < 0 && totalShrink > 0.0 then
                                val shrink = layout.flexShrink(c)
                                if shrink > 0.0 then (freeSpace * shrink / totalShrink).toInt else 0
                            else 0

                        val adjustedMainSize = math.max(0, cMainSize + flexExtra)

                        val selfStretch = TuiLayout.isStretch(layout.lFlags(c))
                        val crossOffset =
                            if selfStretch then 0
                            else
                                alignMode match
                                    case TuiLayout.AlignStart   => 0
                                    case TuiLayout.AlignCenter  => (crossSpace - cCrossSize - cCrossMargin) / 2
                                    case TuiLayout.AlignEnd     => crossSpace - cCrossSize - cCrossMargin
                                    case TuiLayout.AlignStretch => 0
                                    case _                      => 0

                        val availCrossForChild = math.max(0, crossSpace - cCrossMargin)
                        val childW = if row then adjustedMainSize
                        else if alignMode == TuiLayout.AlignStretch || selfStretch then math.max(0, crossSpace - cMarL - cMarR)
                        else math.min(cCrossSize, availCrossForChild)
                        val childH = if row then
                            if alignMode == TuiLayout.AlignStretch || selfStretch then math.max(0, crossSpace - cMarT - cMarB)
                            else math.min(cCrossSize, availCrossForChild)
                        else adjustedMainSize

                        if row then
                            layout.x(c) = contentX + pos + cMarL + layout.transX(c)
                            layout.y(c) = contentY + crossOffset + cMarT + layout.transY(c)
                        else
                            layout.x(c) = contentX + crossOffset + cMarL + layout.transX(c)
                            layout.y(c) = contentY + pos + cMarT + layout.transY(c)
                        end if

                        layout.w(c) = childW
                        layout.h(c) = childH

                        // Re-wrap text node when width is known and text overflows.
                        // Skip if parent has NoWrap (single-line input) — text scrolls, not wraps.
                        if layout.text(c).isDefined && childW > 0 && layout.intrW(c) > childW then
                            val parentIdx = layout.parent(c)
                            if parentIdx < 0 || !TuiLayout.isNoWrap(layout.lFlags(parentIdx)) then
                                val lines = TuiText.lineCount(layout.text(c).get, childW)
                                layout.intrH(c) = lines
                                layout.h(c) = lines
                            end if
                        end if

                        val nextPos = pos + (if row then layout.w(c) + cMarL + cMarR else layout.h(c) + cMarT + cMarB) +
                            (if childIdx < childCount - 1 then betweenGap else 0)
                        positionChildren(
                            layout,
                            layout.nextSibling(c),
                            row,
                            contentX,
                            contentY,
                            contentH,
                            contentW,
                            alignMode,
                            betweenGap,
                            childCount,
                            nextPos,
                            childIdx + 1,
                            freeSpace,
                            totalGrow,
                            totalShrink
                        )
                    end if

            /** Check if any visible child of a row parent has colspan > 1. */
            @tailrec def hasNonUnitColspan(c: Int): Boolean =
                if c == -1 then false
                else if TuiLayout.isHidden(layout.lFlags(c)) || TuiLayout.isOverlay(layout.lFlags(c)) then
                    hasNonUnitColspan(layout.nextSibling(c))
                else if layout.colspan(c) > 1 then true
                else hasNonUnitColspan(layout.nextSibling(c))

            /** Count total colspan units and visible child count. CPS to avoid tuple allocation. */
            inline def countColspanUnits(firstChild: Int)(inline f: (Int, Int) => Unit): Unit =
                @tailrec def loop(c: Int, totalUnits: Int, childCount: Int): Unit =
                    if c == -1 then f(totalUnits, childCount)
                    else if TuiLayout.isHidden(layout.lFlags(c)) || TuiLayout.isOverlay(layout.lFlags(c)) then
                        loop(layout.nextSibling(c), totalUnits, childCount)
                    else loop(layout.nextSibling(c), totalUnits + layout.colspan(c), childCount + 1)
                loop(firstChild, 0, 0)
            end countColspanUnits

            /** Position children with proportional widths based on colspan. */
            @tailrec def positionChildrenColspan(
                c: Int,
                contentX: Int,
                contentY: Int,
                contentH: Int,
                contentW: Int,
                alignMode: Int,
                gapSize: Int,
                totalUnits: Int,
                childCount: Int,
                pos: Int,
                childIdx: Int
            ): Unit =
                if c != -1 then
                    if TuiLayout.isHidden(layout.lFlags(c)) || TuiLayout.isOverlay(layout.lFlags(c)) then
                        positionChildrenColspan(
                            layout.nextSibling(c),
                            contentX,
                            contentY,
                            contentH,
                            contentW,
                            alignMode,
                            gapSize,
                            totalUnits,
                            childCount,
                            pos,
                            childIdx
                        )
                    else
                        val cMarL = layout.marL(c)
                        val cMarR = layout.marR(c)
                        val cMarT = layout.marT(c)
                        val cMarB = layout.marB(c)
                        val cs    = layout.colspan(c)
                        // Proportional width based on colspan, including gaps between spanned columns
                        val gapsBetweenSpanned = if cs > 1 then gapSize * (cs - 1) else 0
                        val availForGaps       = if childCount > 1 then gapSize * (childCount - 1) else 0
                        val distribW           = math.max(0, contentW - availForGaps)
                        val cellW              = if totalUnits > 0 then (distribW * cs / totalUnits) + gapsBetweenSpanned else 0
                        val childW             = math.max(0, cellW - cMarL - cMarR)
                        val crossSpace         = contentH
                        val cCrossSize         = layout.intrH(c)
                        val cCrossMargin       = cMarT + cMarB

                        val crossOffset = alignMode match
                            case TuiLayout.AlignStart   => 0
                            case TuiLayout.AlignCenter  => (crossSpace - cCrossSize - cCrossMargin) / 2
                            case TuiLayout.AlignEnd     => crossSpace - cCrossSize - cCrossMargin
                            case TuiLayout.AlignStretch => 0
                            case _                      => 0

                        val childH = if alignMode == TuiLayout.AlignStretch then math.max(0, crossSpace - cMarT - cMarB)
                        else math.min(cCrossSize, math.max(0, crossSpace - cCrossMargin))

                        layout.x(c) = contentX + pos + cMarL + layout.transX(c)
                        layout.y(c) = contentY + crossOffset + cMarT + layout.transY(c)
                        layout.w(c) = childW
                        layout.h(c) = childH

                        val nextPos = pos + cellW + cMarL + cMarR +
                            (if childIdx < childCount - 1 then gapSize else 0)
                        positionChildrenColspan(
                            layout.nextSibling(c),
                            contentX,
                            contentY,
                            contentH,
                            contentW,
                            alignMode,
                            gapSize,
                            totalUnits,
                            childCount,
                            nextPos,
                            childIdx + 1
                        )
                    end if

            @tailrec def loop(i: Int): Unit =
                if i < layout.count then
                    val flags = layout.lFlags(i)
                    // Overlay elements get full terminal bounds instead of parent-assigned position
                    if TuiLayout.isOverlay(flags) then
                        layout.x(i) = 0
                        layout.y(i) = 0
                        layout.w(i) = termW
                        layout.h(i) = termH
                    end if
                    if !TuiLayout.isHidden(flags) then
                        val row = TuiLayout.isRow(flags)

                        val bT       = if TuiLayout.hasBorderT(flags) then 1 else 0
                        val bR       = if TuiLayout.hasBorderR(flags) then 1 else 0
                        val bB       = if TuiLayout.hasBorderB(flags) then 1 else 0
                        val bL       = if TuiLayout.hasBorderL(flags) then 1 else 0
                        val contentX = layout.x(i) + bL + layout.padL(i)
                        val contentY = layout.y(i) + bT + layout.padT(i)
                        val contentW =
                            math.max(0, layout.w(i) - bL - bR - layout.padL(i) - layout.padR(i))
                        val contentH =
                            math.max(0, layout.h(i) - bT - bB - layout.padT(i) - layout.padB(i))

                        // Use colspan-proportional layout for table rows or row parents with spanning cells
                        if row && (TuiLayout.isTableRow(flags) || hasNonUnitColspan(layout.firstChild(i))) then
                            countColspanUnits(layout.firstChild(i)) { (totalUnits, childCount) =>
                                val alignMode = TuiLayout.align(flags)
                                positionChildrenColspan(
                                    layout.firstChild(i),
                                    contentX,
                                    contentY,
                                    contentH,
                                    contentW,
                                    alignMode,
                                    layout.gap(i),
                                    totalUnits,
                                    childCount,
                                    0,
                                    0
                                )
                            }
                        else
                            countChildrenLoop(layout, layout.firstChild(i), row) { (totalMain, childCount, totalGrow, totalShrink) =>
                                val gapSize     = layout.gap(i)
                                val gapTotal    = if childCount > 1 then gapSize * (childCount - 1) else 0
                                val mainSpace   = if row then contentW else contentH
                                val rawFree     = mainSpace - totalMain - gapTotal
                                val justifyMode = TuiLayout.justify(flags)
                                val alignMode   = TuiLayout.align(flags)

                                // If there's positive free space and any child has flexGrow, distribute it
                                // If there's negative free space and any child has flexShrink, shrink them
                                val useFlexGrow   = rawFree > 0 && totalGrow > 0.0
                                val useFlexShrink = rawFree < 0 && totalShrink > 0.0
                                // After flex distribution, effective free space is consumed
                                val effectiveFree = if useFlexGrow || useFlexShrink then 0 else math.max(0, rawFree)

                                val mainOffset = justifyMode match
                                    case TuiLayout.JustCenter => effectiveFree / 2
                                    case TuiLayout.JustEnd    => effectiveFree
                                    case TuiLayout.JustAround =>
                                        if childCount > 0 then effectiveFree / childCount / 2 else 0
                                    case TuiLayout.JustEvenly =>
                                        if childCount > 0 then effectiveFree / (childCount + 1) else 0
                                    case _ => 0

                                val betweenGap = justifyMode match
                                    case TuiLayout.JustBetween =>
                                        if childCount > 1 then gapSize + effectiveFree / (childCount - 1) else gapSize
                                    case TuiLayout.JustAround =>
                                        if childCount > 0 then gapSize + effectiveFree / childCount else gapSize
                                    case TuiLayout.JustEvenly =>
                                        if childCount > 0 then gapSize + effectiveFree / (childCount + 1) else gapSize
                                    case _ => gapSize

                                positionChildren(
                                    layout,
                                    layout.firstChild(i),
                                    row,
                                    contentX,
                                    contentY,
                                    contentH,
                                    contentW,
                                    alignMode,
                                    betweenGap,
                                    childCount,
                                    mainOffset,
                                    0,
                                    rawFree,
                                    totalGrow,
                                    totalShrink
                                )
                            }
                        end if

                        // Apply scroll offset to children of scrollable containers
                        if TuiLayout.isScrollable(flags) then
                            applyScrollOffset(layout, layout.firstChild(i), layout.scrollX(i), layout.scrollY(i))
                    end if
                    loop(i + 1)

            loop(0)
        end if
    end arrange

    /** Recursively offset all descendants of a scrollable container by the scroll position. */
    private def applyScrollOffset(layout: TuiLayout, firstChild: Int, sx: Int, sy: Int): Unit =
        @tailrec def loop(c: Int): Unit =
            if c != -1 then
                layout.x(c) = layout.x(c) - sx
                layout.y(c) = layout.y(c) - sy
                applyScrollOffset(layout, layout.firstChild(c), sx, sy)
                loop(layout.nextSibling(c))
        loop(firstChild)
    end applyScrollOffset

    /** Apply an incremental scroll delta to all descendants. Used after adjustTextareaScroll to avoid 1-frame lag. */
    def applyScrollDelta(layout: TuiLayout, firstChild: Int, deltaY: Int): Unit =
        @tailrec def loop(c: Int): Unit =
            if c != -1 then
                layout.y(c) = layout.y(c) - deltaY
                applyScrollDelta(layout, layout.firstChild(c), deltaY)
                loop(layout.nextSibling(c))
        loop(firstChild)
    end applyScrollDelta

end TuiFlexLayout
