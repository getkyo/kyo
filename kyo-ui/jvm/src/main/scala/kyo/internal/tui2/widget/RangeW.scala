package kyo.internal.tui2.widget

import kyo.*
import kyo.internal.tui2.*

/** Range slider widget — renders a filled/unfilled bar and handles arrow keys. */
private[kyo] object RangeW:

    def render(
        ri: UI.RangeInput,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        val value  = ValueResolver.resolveDouble(ri.value, ctx.signals)
        val min    = ri.min.getOrElse(0.0)
        val max    = ri.max.getOrElse(100.0)
        val pct    = if max > min then (value - min) / (max - min) else 0.0
        val filled = math.min(cw, math.max(0, (pct * cw).toInt))
        val style  = rs.cellStyle
        // Filled portion: solid block
        var i = 0
        while i < filled do
            ctx.canvas.screen.set(cx + i, cy, '\u2588', style)
            i += 1
        // Unfilled portion: light shade
        while i < cw do
            ctx.canvas.screen.set(cx + i, cy, '\u2591', style)
            i += 1
    end render

    def handleKey(
        ri: UI.RangeInput,
        event: InputEvent.Key,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Boolean =
        val value = ValueResolver.resolveDouble(ri.value, ctx.signals)
        val step  = ri.step.getOrElse(1.0)
        val min   = ri.min.getOrElse(0.0)
        val max   = ri.max.getOrElse(100.0)
        event.key match
            case UI.Keyboard.ArrowRight | UI.Keyboard.ArrowUp =>
                val v = math.min(max, value + step)
                ValueResolver.setDouble(ri.value, v)
                ri.onChange.foreach(f => ValueResolver.runHandler(f(v)))
                true
            case UI.Keyboard.ArrowLeft | UI.Keyboard.ArrowDown =>
                val v = math.max(min, value - step)
                ValueResolver.setDouble(ri.value, v)
                ri.onChange.foreach(f => ValueResolver.runHandler(f(v)))
                true
            case _ => false
        end match
    end handleKey

    def handleMouse(
        ri: UI.RangeInput,
        elem: UI.Element,
        kind: InputEvent.MouseKind,
        mx: Int,
        my: Int,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Boolean =
        kind match
            case InputEvent.MouseKind.LeftPress | InputEvent.MouseKind.LeftDrag =>
                val packed = ctx.getContentPosition(elem)
                if packed == -1L then return false
                val cx  = ctx.posX(packed)
                val cw  = ctx.posW(packed)
                val min = ri.min.getOrElse(0.0)
                val max = ri.max.getOrElse(100.0)
                if cw > 0 && max > min then
                    val pct  = math.max(0.0, math.min(1.0, (mx - cx).toDouble / cw))
                    val step = ri.step.getOrElse(1.0)
                    // Snap to nearest step
                    val raw     = min + pct * (max - min)
                    val snapped = math.round(raw / step) * step
                    val v       = math.max(min, math.min(max, snapped))
                    ValueResolver.setDouble(ri.value, v)
                    ri.onChange.foreach(f => ValueResolver.runHandler(f(v)))
                end if
                true
            case _ => false
    end handleMouse

end RangeW
