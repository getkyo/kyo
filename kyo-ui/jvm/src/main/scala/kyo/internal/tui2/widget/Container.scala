package kyo.internal.tui2.widget

import kyo.*
import kyo.internal.tui2.*
import scala.annotation.tailrec

/** Container widget — handles all elements with children. Runs flex layout via CPS. */
private[kyo] object Container:

    private val R = kyo.internal.tui2.widget.Render

    def render(
        elem: UI.Element,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        val children = elem.children
        if children.nonEmpty then
            val clipped = rs.overflow == 1 || rs.overflow == 2
            if clipped then ctx.canvas.screen.pushClip(cx, cy, cx + cw, cy + ch)

            val scrollY =
                if rs.overflow == 2 then
                    ctx.registerScrollable(elem)
                    ctx.getScrollY(elem)
                else 0

            // When overflow is set, lay out children at natural (unconstrained) size.
            // The clip rect above handles visual truncation. Without this, measurement
            // sizes the container to fit content, and clipping has nothing to clip.
            val (layoutW, layoutH) =
                if clipped then
                    val isRow         = rs.direction == ResolvedStyle.DirRow
                    val n             = children.size.toInt
                    val unconstrainW  = rs.overflow == 1 // hidden: unconstrain both; scroll: height only
                    val measureAvailW = if unconstrainW then 100000 else cw
                    var mainSum       = 0
                    var crossMax      = 0
                    var i             = 0
                    while i < n do
                        val mw = FlexLayout.measureW(children(i), measureAvailW, 100000, ctx)
                        val mh = FlexLayout.measureH(children(i), if unconstrainW then mw else cw, 100000, ctx)
                        if isRow then
                            mainSum += mw; crossMax = math.max(crossMax, mh)
                        else
                            mainSum += mh; crossMax = math.max(crossMax, mw)
                        end if
                        i += 1
                    end while
                    if n > 1 then mainSum += rs.gap * (n - 1)
                    val natW = if isRow then mainSum else crossMax
                    val natH = if isRow then crossMax else mainSum
                    (if unconstrainW then math.max(cw, natW) else cw, math.max(ch, natH))
                else (cw, ch)

            FlexLayout.arrange(children, cx, cy, layoutW, layoutH, rs, ctx) { (i, rx, ry, rw, rh) =>
                R.render(children(i), rx, ry - scrollY, rw, rh, ctx)
            }

            if clipped then ctx.canvas.screen.popClip()
            if rs.overflow == 2 then ScrollW.paintIndicator(elem, cx, cy, cw, ch, ctx)
        end if
    end render

    def renderColumn(children: Span[UI], x: Int, y: Int, w: Int, h: Int, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Unit =
        @tailrec def loop(i: Int, curY: Int): Unit =
            if i < children.size && curY < y + h then
                val childH = FlexLayout.measureH(children(i), w, h - (curY - y), ctx)
                R.render(children(i), x, curY, w, childH, ctx)
                loop(i + 1, curY + childH)
        loop(0, y)
    end renderColumn

end Container
