package kyo.internal

import scala.annotation.tailrec

/** Tree-walk renderer: reads TuiLayout arrays, calls TuiRenderer drawing methods.
  *
  * Pure tree traversal — all drawing logic (borders, text, clipping) lives in TuiRenderer. Style inheritance and state overlays are handled
  * by TuiStyle (called before paint).
  */
private[kyo] object TuiPainter:

    /** Paint the layout tree into the renderer with two-pass overlay support. Pass 1: flow elements (skip overlays). Pass 2: overlays in
      * tree order. Caller must call `renderer.clear()` first.
      */
    def paint(layout: TuiLayout, renderer: TuiRenderer): Unit =
        if layout.count > 0 then
            paintNode(layout, renderer, 0, skipOverlays = true)
            paintOverlays(layout, renderer)

    private def paintNode(layout: TuiLayout, renderer: TuiRenderer, idx: Int, skipOverlays: Boolean): Unit =
        if idx >= 0 && idx < layout.count then
            val lf = layout.lFlags(idx)
            if skipOverlays && TuiLayout.isOverlay(lf) then ()
            else if !TuiLayout.isHidden(lf) then
                val nx = layout.x(idx); val ny = layout.y(idx)
                val nw = layout.w(idx); val nh = layout.h(idx)
                if nw > 0 && nh > 0 then
                    // Shadow (paint before bg so it appears behind)
                    if layout.shadowClr(idx) != TuiColor.Absent then
                        val sx     = layout.shadowX(idx); val sy = layout.shadowY(idx)
                        val spread = layout.shadowSpread(idx)
                        renderer.fillBg(nx + sx - spread, ny + sy - spread, nw + spread * 2, nh + spread * 2, layout.shadowClr(idx))
                    end if

                    // Background fill
                    if layout.bg(idx) != TuiColor.Absent then
                        renderer.fillBg(nx, ny, nw, nh, layout.bg(idx))

                    val pf = layout.pFlags(idx)
                    val bT = TuiLayout.hasBorderT(lf); val bR = TuiLayout.hasBorderR(lf)
                    val bB = TuiLayout.hasBorderB(lf); val bL = TuiLayout.hasBorderL(lf)
                    val bs = TuiLayout.borderStyle(pf)

                    // Borders
                    if (bT || bR || bB || bL) && bs != TuiLayout.BorderNone then
                        val fgFb = if layout.fg(idx) != TuiColor.Absent then layout.fg(idx)
                        else TuiColor.pack(100, 100, 120)
                        renderer.drawBorder(
                            nx,
                            ny,
                            nw,
                            nh,
                            bs,
                            bT,
                            bR,
                            bB,
                            bL,
                            TuiLayout.isRoundedTL(pf),
                            TuiLayout.isRoundedTR(pf),
                            TuiLayout.isRoundedBR(pf),
                            TuiLayout.isRoundedBL(pf),
                            if layout.bdrClrT(idx) != TuiColor.Absent then layout.bdrClrT(idx) else fgFb,
                            if layout.bdrClrR(idx) != TuiColor.Absent then layout.bdrClrR(idx) else fgFb,
                            if layout.bdrClrB(idx) != TuiColor.Absent then layout.bdrClrB(idx) else fgFb,
                            if layout.bdrClrL(idx) != TuiColor.Absent then layout.bdrClrL(idx) else fgFb,
                            layout.bg(idx)
                        )
                    end if

                    // Text content
                    val mt = layout.text(idx)
                    if mt.isDefined then
                        val bt = if bT then 1 else 0; val br        = if bR then 1 else 0
                        val bb = if bB then 1 else 0; val bl        = if bL then 1 else 0
                        val cx = nx + bl + layout.padL(idx); val cy = ny + bt + layout.padT(idx)
                        val cw = math.max(0, nw - bl - br - layout.padL(idx) - layout.padR(idx))
                        val ch = math.max(0, nh - bt - bb - layout.padT(idx) - layout.padB(idx))
                        if cw > 0 && ch > 0 then
                            renderer.drawText(
                                mt.get,
                                cx,
                                cy,
                                cw,
                                ch,
                                mkStyle(layout, idx),
                                TuiLayout.textAlign(pf),
                                TuiLayout.textTrans(pf),
                                TuiLayout.shouldWrapText(pf),
                                TuiLayout.hasTextOverflow(pf)
                            )
                        end if
                    end if

                    // Children with overflow clipping (hidden=1, scroll=2)
                    val overflow = TuiLayout.overflow(lf)
                    if overflow == 1 || overflow == 2 then
                        val bt = if bT then 1 else 0; val br        = if bR then 1 else 0
                        val bb = if bB then 1 else 0; val bl        = if bL then 1 else 0
                        val cx = nx + bl + layout.padL(idx); val cy = ny + bt + layout.padT(idx)
                        val cw = math.max(0, nw - bl - br - layout.padL(idx) - layout.padR(idx))
                        val ch = math.max(0, nh - bt - bb - layout.padT(idx) - layout.padB(idx))
                        if cw > 0 && ch > 0 then
                            renderer.pushClip(cx, cy, cx + cw, cy + ch)
                            paintChildren(layout, renderer, idx, skipOverlays)
                            renderer.popClip()
                            // Scroll indicator for scrollable containers
                            if overflow == 2 then
                                paintScrollIndicator(layout, renderer, idx, nx, ny, nw, nh, cx, cy, cw, ch)
                        end if
                    else
                        paintChildren(layout, renderer, idx, skipOverlays)
                    end if

                    // Apply filters as post-processing (after all children painted)
                    val filterBits = layout.filterBits(idx)
                    if filterBits != 0 then
                        renderer.applyFilter(nx, ny, nw, nh, filterBits, layout.filterVals, idx * 8)
                end if
            end if
    end paintNode

    private def paintChildren(layout: TuiLayout, renderer: TuiRenderer, idx: Int, skipOverlays: Boolean): Unit =
        @tailrec def loop(ch: Int): Unit =
            if ch != -1 then
                paintNode(layout, renderer, ch, skipOverlays)
                loop(layout.nextSibling(ch))
        loop(layout.firstChild(idx))
    end paintChildren

    /** Pass 2: paint overlay elements in tree order. Overlays that appear later paint on top. Uses skipOverlays=false so the overlay node
      * itself is painted, along with all its descendants. Nested overlays (rare) may paint twice but the result is correct (last write wins
      * on cell buffer).
      */
    private def paintOverlays(layout: TuiLayout, renderer: TuiRenderer): Unit =
        // unsafe: while for array iteration
        var i = 0
        while i < layout.count do
            val lf = layout.lFlags(i)
            if TuiLayout.isOverlay(lf) && !TuiLayout.isHidden(lf) then
                paintNode(layout, renderer, i, skipOverlays = false)
            i += 1
        end while
    end paintOverlays

    /** Paint a vertical scroll indicator on the right edge of a scrollable container. Shows ▲/▼ arrows at top/bottom and a █ thumb
      * proportional to visible content.
      */
    private def paintScrollIndicator(
        layout: TuiLayout,
        renderer: TuiRenderer,
        idx: Int,
        nx: Int,
        ny: Int,
        nw: Int,
        nh: Int,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int
    ): Unit =
        // Compute total content height from children
        val contentH = computeContentHeight(layout, idx, cy)
        if contentH > ch && ch >= 3 then
            val scrollY     = layout.scrollY(idx)
            val maxScroll   = contentH - ch
            val trackX      = nx + nw - 1 // right edge
            val trackTop    = cy
            val trackH      = ch
            val scrollStyle = TuiRenderer.packStyle(fg = TuiColor.pack(120, 120, 140), bg = TuiColor.Absent)

            // Up arrow
            renderer.set(trackX, trackTop, '\u25b2', scrollStyle)
            // Down arrow
            renderer.set(trackX, trackTop + trackH - 1, '\u25bc', scrollStyle)

            // Thumb position within track (excluding arrow rows)
            val innerH = trackH - 2
            if innerH > 0 then
                val thumbSize = math.max(1, innerH * ch / contentH)
                val thumbPos  = if maxScroll > 0 then (innerH - thumbSize) * scrollY / maxScroll else 0
                // unsafe: while for thumb rendering
                var ty = 0
                while ty < thumbSize do
                    val row = trackTop + 1 + thumbPos + ty
                    if row < trackTop + trackH - 1 then
                        renderer.set(trackX, row, '\u2588', scrollStyle)
                    ty += 1
                end while
            end if
        end if
    end paintScrollIndicator

    /** Compute the total content height of a container's children (max bottom edge - top of content area). */
    private def computeContentHeight(layout: TuiLayout, parentIdx: Int, contentTop: Int): Int =
        @tailrec def loop(c: Int, maxBottom: Int): Int =
            if c == -1 then maxBottom - contentTop
            else
                // Children are already offset by scroll, undo to get natural position
                val childBottom = layout.y(c) + layout.scrollY(parentIdx) + layout.h(c)
                loop(layout.nextSibling(c), math.max(maxBottom, childBottom))
        loop(layout.firstChild(parentIdx), contentTop)
    end computeContentHeight

    private inline def mkStyle(layout: TuiLayout, idx: Int): Long =
        val pf = layout.pFlags(idx)
        TuiRenderer.packStyle(
            fg = layout.fg(idx),
            bg = layout.bg(idx),
            bold = TuiLayout.isBold(pf),
            dim = TuiLayout.isDim(pf),
            italic = TuiLayout.isItalic(pf),
            underline = TuiLayout.isUnderline(pf),
            strikethrough = TuiLayout.isStrikethrough(pf)
        )
    end mkStyle

end TuiPainter
