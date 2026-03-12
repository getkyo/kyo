package kyo.internal.tui2.widget

import kyo.internal.tui2.*

/** Horizontal rule widget — draws a single line of '─' characters. */
private[kyo] object HrW:
    def render(cx: Int, cy: Int, cw: Int, rs: ResolvedStyle, canvas: Canvas): Unit =
        canvas.hline(cx, cy, cw, '\u2500', rs.cellStyle)
end HrW
