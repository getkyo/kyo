package kyo.internal.tui2.widget

import kyo.UI
import kyo.internal.tui2.*
import scala.annotation.tailrec

/** Scroll indicator widget — draws ▲/▼ arrows and scroll thumb on the right edge. */
private[kyo] object ScrollW:

    def paintIndicator(
        elem: UI.Element,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        ctx: RenderCtx
    ): Unit =
        val scrollY  = ctx.getScrollY(elem)
        val style    = CellStyle(ColorUtils.Absent, ColorUtils.Absent, false, false, false, false, true)
        val thumbCol = cx + cw - 1

        // Draw up/down arrows
        if scrollY > 0 && ch > 0 then
            ctx.canvas.screen.set(thumbCol, cy, '\u25b2', style) // ▲

        if ch > 1 then
            ctx.canvas.screen.set(thumbCol, cy + ch - 1, '\u25bc', style) // ▼
    end paintIndicator

end ScrollW
