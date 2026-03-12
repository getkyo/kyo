package kyo.internal.tui2

import kyo.*
import kyo.internal.tui2.widget.Render
import kyo.internal.tui2.widget.ValueResolver
import kyo.internal.tui2.widget.WidgetRegistry
import scala.annotation.tailrec

/** Flex-based layout engine for the tui2 pipeline.
  *
  * Operates on UI tree nodes directly (not flat arrays). Uses scratch arrays from RenderCtx for per-child sizing data. CPS arrange method
  * calls emit for each positioned child — zero allocation.
  */
private[kyo] object FlexLayout:

    private def ensureCapacity(ctx: RenderCtx, n: Int): Unit =
        if ctx.layoutIntBuf.length < n * 4 then
            ctx.layoutIntBuf = new Array[Int](n * 4)
        if ctx.layoutDblBuf.length < n * 2 then
            ctx.layoutDblBuf = new Array[Double](n * 2)
    end ensureCapacity

    /** Measure the intrinsic width of a UI node. */
    def measureW(ui: UI, availW: Int, availH: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int =
        ui match
            case UI.Text(value) =>
                TextMetrics.naturalWidth(value)
            case elem: UI.Element =>
                val rs = ctx.measureRs
                rs.inherit(ctx)
                rs.applyProps(ctx.theme.styleFor(elem))
                resolveStyleInto(elem, rs, ctx)
                // Hidden elements take no space
                if rs.hidden || ValueResolver.resolveBoolean(elem.attrs.hidden, ctx.signals) then 0
                else
                    val bt      = if rs.borderT then 1 else 0
                    val br      = if rs.borderR then 1 else 0
                    val bb      = if rs.borderB then 1 else 0
                    val bl      = if rs.borderL then 1 else 0
                    val insetW  = rs.padL + rs.padR + bl + br
                    val marginW = rs.marL + rs.marR
                    val widget  = WidgetRegistry.lookup(elem)
                    val rawW =
                        if rs.sizeW >= 0 then rs.sizeW
                        else
                            val intrinsic = widget.measureWidth(elem, availW - insetW - marginW, ctx)
                            if intrinsic >= 0 then
                                intrinsic + insetW
                            else
                                val extra    = widget.extraWidth(elem)
                                val children = elem.children
                                val isRow    = rs.direction == ResolvedStyle.DirRow
                                val childrenW = if children.nonEmpty then
                                    measureChildrenW(children, availW - extra - insetW - marginW, availH, isRow, rs.gap, ctx)
                                else 0
                                extra + childrenW + insetW
                            end if
                        end if
                    end rawW
                    val clampedW =
                        val lo = if rs.minW >= 0 then math.max(rawW, rs.minW) else rawW
                        if rs.maxW >= 0 then math.min(lo, rs.maxW) else lo
                    clampedW + marginW
                end if
            case UI.Fragment(children) =>
                measureChildrenW(children, availW, availH, false, 0, ctx)
            case UI.Reactive(signal) =>
                measureW(ctx.resolve(signal), availW, availH, ctx)
            case fe: UI.Foreach[?] =>
                ctx.signals.add(fe.signal)
                val seq  = ctx.resolve(fe.signal)
                val size = seq.size
                val arr  = new Array[UI](size)
                @tailrec def fillW(i: Int): Unit =
                    if i < size then
                        arr(i) = ValueResolver.foreachApply(fe, i, seq(i))
                        fillW(i + 1)
                fillW(0)
                measureChildrenW(Span.fromUnsafe(arr), availW, availH, false, 0, ctx)
            case _ => 0

    /** Measure the intrinsic height of a UI node. */
    def measureH(ui: UI, availW: Int, availH: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int =
        ui match
            case UI.Text(value) =>
                TextMetrics.lineCount(value, availW)
            case elem: UI.Element =>
                val rs = ctx.measureRs
                rs.inherit(ctx)
                rs.applyProps(ctx.theme.styleFor(elem))
                resolveStyleInto(elem, rs, ctx)
                // Hidden elements take no space
                if rs.hidden || ValueResolver.resolveBoolean(elem.attrs.hidden, ctx.signals) then 0
                else
                    val bt      = if rs.borderT then 1 else 0
                    val bb      = if rs.borderB then 1 else 0
                    val insetH  = rs.padT + rs.padB + bt + bb
                    val marginH = rs.marT + rs.marB
                    val widget  = WidgetRegistry.lookup(elem)
                    val rawH =
                        if rs.sizeH >= 0 then rs.sizeH
                        else
                            val intrinsic = widget.measureHeight(elem, availW, ctx)
                            if intrinsic >= 0 then
                                intrinsic + insetH
                            else
                                val children = elem.children
                                if children.isEmpty then math.max(1, insetH)
                                else
                                    val isRow   = rs.direction == ResolvedStyle.DirRow
                                    val marginW = rs.marL + rs.marR
                                    val insetW  = rs.padL + rs.padR + (if rs.borderL then 1 else 0) + (if rs.borderR then 1 else 0)
                                    measureChildrenH(
                                        children,
                                        availW - insetW - marginW,
                                        availH - insetH - marginH,
                                        isRow,
                                        rs.gap,
                                        ctx
                                    ) + insetH
                                end if
                            end if
                        end if
                    end rawH
                    val clampedH =
                        val lo = if rs.minH >= 0 then math.max(rawH, rs.minH) else rawH
                        if rs.maxH >= 0 then math.min(lo, rs.maxH) else lo
                    clampedH + marginH
                end if
            case UI.Fragment(children) =>
                measureChildrenH(children, availW, availH, false, 0, ctx)
            case UI.Reactive(signal) =>
                measureH(ctx.resolve(signal), availW, availH, ctx)
            case fe: UI.Foreach[?] =>
                ctx.signals.add(fe.signal)
                val seq  = ctx.resolve(fe.signal)
                val size = seq.size
                val arr  = new Array[UI](size)
                @tailrec def fillH(i: Int): Unit =
                    if i < size then
                        arr(i) = ValueResolver.foreachApply(fe, i, seq(i))
                        fillH(i + 1)
                fillH(0)
                measureChildrenH(Span.fromUnsafe(arr), availW, availH, false, 0, ctx)
            case _ => 1

    /** CPS layout — calls emit(childIndex, x, y, w, h) for each child position. */
    def arrange(
        children: Span[UI],
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(emit: (Int, Int, Int, Int, Int) => Unit)(using Frame, AllowUnsafe): Unit =
        val n = children.size
        if n > 0 then
            ensureCapacity(ctx, n)
            val isRow = rs.direction == ResolvedStyle.DirRow
            val gap   = rs.gap

            // Phase 3: Track which children have explicit cross-axis size
            val crossExplicit = new Array[Boolean](n)

            // Measure children into scratch arrays
            // layoutIntBuf: [mainSize(0..n-1), crossSize(n..2n-1)]
            // layoutDblBuf: [flexGrow(0..n-1), flexShrink(n..2n-1)]
            @tailrec def measureLoop(i: Int, accMain: Int, accGrow: Double, accVis: Int): (Int, Double, Int) =
                if i < n then
                    val child = children(i)
                    val mw    = measureW(child, cw, ch, ctx)
                    val mh    = measureH(child, cw, ch, ctx)
                    if isRow then
                        ctx.layoutIntBuf(i) = mw
                        ctx.layoutIntBuf(n + i) = mh
                    else
                        ctx.layoutIntBuf(i) = mh
                        ctx.layoutIntBuf(n + i) = mw
                    end if
                    // Read child's flex-grow/shrink, min/max, and resolve percentage sizes
                    child match
                        case elem: UI.Element =>
                            val mrs = ctx.measureRs
                            mrs.inherit(ctx)
                            mrs.applyProps(ctx.theme.styleFor(elem))
                            resolveStyleInto(elem, mrs, ctx)
                            ctx.layoutDblBuf(i) = mrs.flexGrow
                            ctx.layoutDblBuf(n + i) = mrs.flexShrink
                            ctx.layoutIntBuf(2 * n + i) = if isRow then mrs.minW else mrs.minH
                            ctx.layoutIntBuf(3 * n + i) = if isRow then mrs.maxW else mrs.maxH
                            // Phase 3: Track explicit cross-axis size
                            val crossSizeField = if isRow then mrs.sizeH else mrs.sizeW
                            crossExplicit(i) = crossSizeField >= 0 || crossSizeField < -1 // px/em or pct
                            // Resolve percentage sizes against container
                            val pctMain = if isRow then mrs.sizeW else mrs.sizeH
                            if pctMain < -1 then
                                val pct       = -(pctMain + 1)
                                val mainAvail = if isRow then cw else ch
                                ctx.layoutIntBuf(i) = (mainAvail * pct) / 100
                            end if
                            val pctCross = if isRow then mrs.sizeH else mrs.sizeW
                            if pctCross < -1 then
                                val pct        = -(pctCross + 1)
                                val crossAvail = if isRow then ch else cw
                                ctx.layoutIntBuf(n + i) = (crossAvail * pct) / 100
                            end if
                        case _ =>
                            ctx.layoutDblBuf(i) = 0.0
                            ctx.layoutDblBuf(n + i) = 1.0
                            ctx.layoutIntBuf(2 * n + i) = -1
                            ctx.layoutIntBuf(3 * n + i) = -1
                            crossExplicit(i) = false
                    end match
                    measureLoop(i + 1, accMain + ctx.layoutIntBuf(i), accGrow + ctx.layoutDblBuf(i), accVis + 1)
                else (accMain, accGrow, accVis)
            val (totalMain, totalGrow, visCount) = measureLoop(0, 0, 0.0, 0)

            val mainAvail  = if isRow then cw else ch
            val crossAvail = if isRow then ch else cw

            if rs.flexWrap == 1 then
                arrangeWrapped(n, children, cx, cy, cw, ch, isRow, gap, mainAvail, crossAvail, crossExplicit, rs, ctx, emit)
            else
                val gapTotal    = if visCount > 1 then gap * (visCount - 1) else 0
                val remainSpace = mainAvail - totalMain - gapTotal

                // Distribute remaining space via flex-grow
                if remainSpace > 0 && totalGrow > 0 then
                    @tailrec def growLoop(i: Int): Unit =
                        if i < n then
                            val grow = ctx.layoutDblBuf(i)
                            if grow > 0 then
                                ctx.layoutIntBuf(i) += (remainSpace * grow / totalGrow).toInt
                            growLoop(i + 1)
                    growLoop(0)
                end if

                // Distribute overflow via flex-shrink (weighted by shrink * basis per CSS spec)
                if remainSpace < 0 then
                    var totalWeightedShrink = 0.0
                    @tailrec def accumShrink(i: Int): Unit =
                        if i < n then
                            totalWeightedShrink += ctx.layoutDblBuf(n + i) * ctx.layoutIntBuf(i)
                            accumShrink(i + 1)
                    accumShrink(0)
                    if totalWeightedShrink > 0 then
                        @tailrec def shrinkLoop(i: Int): Unit =
                            if i < n then
                                val shrink = ctx.layoutDblBuf(n + i)
                                val basis  = ctx.layoutIntBuf(i)
                                if shrink > 0 && basis > 0 then
                                    val reduction = ((-remainSpace).toDouble * shrink * basis / totalWeightedShrink).toInt
                                    ctx.layoutIntBuf(i) = math.max(0, basis - reduction)
                                shrinkLoop(i + 1)
                        shrinkLoop(0)
                    end if
                end if

                // Clamp to min/max constraints (applied after grow/shrink per CSS spec)
                clampMinMax(n, ctx)

                // Compute justification offsets
                val justifyGap  = computeJustifyGap(rs.justify, remainSpace, gapTotal, gap, visCount)
                val startOffset = computeJustifyStart(rs.justify, remainSpace, visCount)
                val align       = rs.align // capture before emit loop — emit overwrites ctx.rs

                // Snapshot sizes into local arrays before emit loop — nested arrange
                // calls reuse ctx.layoutIntBuf and would corrupt our data.
                val mainSizes  = new Array[Int](n)
                val crossSizes = new Array[Int](n)
                @tailrec def snapshot(i: Int): Unit =
                    if i < n then
                        mainSizes(i) = math.max(0, ctx.layoutIntBuf(i))
                        val measuredCross = math.max(0, ctx.layoutIntBuf(n + i))
                        crossSizes(i) =
                            if align == ResolvedStyle.AlignStretch && !crossExplicit(i) then
                                crossAvail // Phase 3: stretch to container
                            else
                                math.min(measuredCross, crossAvail)
                        snapshot(i + 1)
                snapshot(0)

                // Emit positions
                @tailrec def emitLoop(i: Int, mainPos: Int): Unit =
                    if i < n then
                        val mainSz  = mainSizes(i)
                        val crossSz = crossSizes(i)

                        val crossOffset = computeAlignOffset(align, crossAvail, crossSz)

                        if isRow then
                            emit(i, cx + mainPos, cy + crossOffset, mainSz, crossSz)
                        else
                            emit(i, cx + crossOffset, cy + mainPos, crossSz, mainSz)
                        end if

                        emitLoop(i + 1, mainPos + mainSz + justifyGap)
                emitLoop(0, startOffset)
            end if
        end if
    end arrange

    /** Multi-line (flex-wrap) layout. Breaks children into lines, then positions each line. */
    private def arrangeWrapped(
        n: Int,
        children: Span[UI],
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        isRow: Boolean,
        gap: Int,
        mainAvail: Int,
        crossAvail: Int,
        crossExplicit: Array[Boolean],
        rs: ResolvedStyle,
        ctx: RenderCtx,
        emit: (Int, Int, Int, Int, Int) => Unit
    )(using Frame, AllowUnsafe): Unit =
        // Determine line breaks — a line breaks when accumulated main size exceeds mainAvail
        val lineStarts = new Array[Int](n + 1) // max n lines + sentinel
        var lineCount  = 0
        var accMain    = 0
        var lineItems  = 0
        lineStarts(0) = 0
        lineCount = 1

        var i = 0
        while i < n do
            val itemMain  = ctx.layoutIntBuf(i)
            val gapBefore = if lineItems > 0 then gap else 0
            if lineItems > 0 && accMain + gapBefore + itemMain > mainAvail then
                // Start new line
                lineStarts(lineCount) = i
                lineCount += 1
                accMain = itemMain
                lineItems = 1
            else
                accMain += gapBefore + itemMain
                lineItems += 1
            end if
            i += 1
        end while
        lineStarts(lineCount) = n // sentinel

        // Clamp min/max
        clampMinMax(n, ctx)

        // Snapshot sizes into local arrays — nested arrange calls reuse ctx.layoutIntBuf
        val mainSizes  = new Array[Int](n)
        val crossSizes = new Array[Int](n)
        var si         = 0
        while si < n do
            mainSizes(si) = ctx.layoutIntBuf(si)
            crossSizes(si) = ctx.layoutIntBuf(n + si)
            si += 1
        end while

        // For each line, apply grow within that line, then emit
        val align    = rs.align
        var crossPos = 0
        var line     = 0
        while line < lineCount do
            val lineStart   = lineStarts(line)
            val lineEnd     = lineStarts(line + 1)
            val itemsInLine = lineEnd - lineStart

            // Compute line's total main size and max cross size
            var lineMainTotal = 0
            var lineCrossMax  = 0
            var lineGrow      = 0.0
            var j             = lineStart
            while j < lineEnd do
                lineMainTotal += mainSizes(j)
                lineCrossMax = math.max(lineCrossMax, crossSizes(j))
                lineGrow += ctx.layoutDblBuf(j)
                j += 1
            end while

            val lineGapTotal = if itemsInLine > 1 then gap * (itemsInLine - 1) else 0
            val lineRemain   = mainAvail - lineMainTotal - lineGapTotal

            // Grow within this line
            if lineRemain > 0 && lineGrow > 0 then
                j = lineStart
                while j < lineEnd do
                    val grow = ctx.layoutDblBuf(j)
                    if grow > 0 then
                        mainSizes(j) += (lineRemain * grow / lineGrow).toInt
                    j += 1
                end while
            end if

            // Emit positions for this line
            var mainPos = 0
            j = lineStart
            while j < lineEnd do
                val mainSz = math.max(0, mainSizes(j))
                val crossSz =
                    if align == ResolvedStyle.AlignStretch && !crossExplicit(j) then
                        lineCrossMax // Phase 3: stretch to line's max cross size
                    else
                        math.max(0, math.min(crossSizes(j), lineCrossMax))
                val crossOffset = computeAlignOffset(align, lineCrossMax, crossSz)

                if isRow then
                    emit(j, cx + mainPos, cy + crossPos + crossOffset, mainSz, crossSz)
                else
                    emit(j, cx + crossPos + crossOffset, cy + mainPos, crossSz, mainSz)
                end if

                mainPos += mainSz + gap
                j += 1
            end while

            crossPos += lineCrossMax + gap
            line += 1
        end while
    end arrangeWrapped

    private def clampMinMax(n: Int, ctx: RenderCtx): Unit =
        @tailrec def clampLoop(i: Int): Unit =
            if i < n then
                var s      = ctx.layoutIntBuf(i)
                val minVal = ctx.layoutIntBuf(2 * n + i)
                val maxVal = ctx.layoutIntBuf(3 * n + i)
                if minVal >= 0 then s = math.max(s, minVal)
                if maxVal >= 0 then s = math.min(s, maxVal)
                ctx.layoutIntBuf(i) = s
                clampLoop(i + 1)
        clampLoop(0)
    end clampMinMax

    // ---- Private helpers ----

    private def resolveStyleInto(elem: UI.Element, rs: ResolvedStyle, ctx: RenderCtx)(using Frame, AllowUnsafe): Unit =
        rs.applyProps(ValueResolver.resolveStyle(elem.attrs.uiStyle, ctx.signals))

    private def measureChildrenW(children: Span[UI], availW: Int, availH: Int, isRow: Boolean, gap: Int, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Int =
        @tailrec def loop(i: Int, mainSum: Int, crossMax: Int, count: Int): Int =
            if i >= children.size then
                val gapTotal = if count > 1 then gap * (count - 1) else 0
                if isRow then mainSum + gapTotal else crossMax
            else
                val cw = measureW(children(i), availW, availH, ctx)
                if isRow then loop(i + 1, mainSum + cw, math.max(crossMax, 0), count + 1)
                else loop(i + 1, mainSum, math.max(crossMax, cw), count + 1)
        loop(0, 0, 0, 0)
    end measureChildrenW

    private def measureChildrenH(children: Span[UI], availW: Int, availH: Int, isRow: Boolean, gap: Int, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Int =
        @tailrec def loop(i: Int, mainSum: Int, crossMax: Int, count: Int): Int =
            if i >= children.size then
                val gapTotal = if count > 1 then gap * (count - 1) else 0
                if isRow then crossMax else mainSum + gapTotal
            else
                val ch = measureH(children(i), availW, availH, ctx)
                if isRow then loop(i + 1, mainSum, math.max(crossMax, ch), count + 1)
                else loop(i + 1, mainSum + ch, math.max(crossMax, 0), count + 1)
        loop(0, 0, 0, 0)
    end measureChildrenH

    private def computeAlignOffset(align: Int, crossAvail: Int, crossSz: Int): Int =
        align match
            case ResolvedStyle.AlignCenter  => math.max(0, (crossAvail - crossSz) / 2)
            case ResolvedStyle.AlignEnd     => math.max(0, crossAvail - crossSz)
            case ResolvedStyle.AlignStretch => 0
            case _                          => 0 // AlignStart

    private def computeJustifyGap(justify: Int, remain: Int, totalGap: Int, gap: Int, count: Int): Int =
        if count <= 1 then gap
        else
            justify match
                case ResolvedStyle.JustBetween => if remain > 0 then (remain + totalGap) / (count - 1) else gap
                case ResolvedStyle.JustAround  => if remain > 0 then (remain + totalGap) / count else gap
                case ResolvedStyle.JustEvenly  => if remain > 0 then (remain + totalGap) / (count + 1) else gap
                case _                         => gap

    private def computeJustifyStart(justify: Int, remain: Int, count: Int): Int =
        if remain <= 0 || count <= 0 then 0
        else
            justify match
                case ResolvedStyle.JustCenter => remain / 2
                case ResolvedStyle.JustEnd    => remain
                case ResolvedStyle.JustAround => remain / count / 2
                case ResolvedStyle.JustEvenly => remain / (count + 1)
                case _                        => 0

end FlexLayout
