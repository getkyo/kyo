package kyo.internal.tui2.widget

import kyo.*
import kyo.Maybe.*
import kyo.internal.tui2.*
import scala.annotation.tailrec

/** Rendering entry point — walks the UI tree, resolves styles, paints elements, and dispatches to widgets. */
private[kyo] object Render:

    /** Main render dispatch — handles all UI node types. */
    def render(ui: UI, x: Int, y: Int, w: Int, h: Int, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Unit =
        if w > 0 && h > 0 then
            ui match
                case elem: UI.Element =>
                    renderElement(elem, x, y, w, h, ctx)

                case UI.Text(value) =>
                    val inh = ctx.inherited
                    ctx.canvas.drawText(
                        value,
                        x,
                        y,
                        w,
                        h,
                        inh.cellStyle,
                        inh.textAlign,
                        inh.textTransform,
                        inh.textWrap,
                        inh.textOverflow
                    )

                case UI.Reactive(signal) =>
                    render(ctx.resolve(signal), x, y, w, h, ctx)

                case fe: UI.Foreach[?] =>
                    ctx.signals.add(fe.signal)
                    renderForeach(fe, x, y, w, h, ctx)

                case UI.Fragment(children) =>
                    Container.renderColumn(children, x, y, w, h, ctx)

                case _ => ()

    /** Render an element: resolve style -> paint box -> dispatch to widget. */
    def renderElement(
        elem: UI.Element,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        val rs = ctx.rs
        rs.inherit(ctx)

        // Apply theme defaults -> user style -> pseudo-state overlays
        resolveAllStyles(elem, rs, ctx)

        // Register element identifier for Label.forId lookup
        elem.attrs.identifier.foreach(id => ctx.identifiers.register(id, elem))

        if rs.hidden then ()
        else if rs.overlay then ctx.overlays.add(elem)
        else
            ctx.recordPosition(elem, x, y, w, h)
            paintElement(elem, x, y, w, h, rs, ctx)
        end if
    end renderElement

    private def resolveAllStyles(
        elem: UI.Element,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        // Theme defaults
        rs.applyProps(ctx.theme.styleFor(elem))
        // User style
        rs.applyProps(ValueResolver.resolveStyle(elem.attrs.uiStyle, ctx.signals))
        // Attrs-level hidden (separate from Style.HiddenProp)
        if ValueResolver.resolveBoolean(elem.attrs.hidden, ctx.signals) then
            rs.hidden = true
        // Pseudo-state overlays (with theme defaults for focus/disabled)
        val isDisabled = resolveDisabled(elem, ctx)
        if isDisabled then
            val ds = rs.disabledStyle.getOrElse(ctx.theme.defaultDisabledStyle)
            rs.applyProps(ds)
        else
            val isFocused = ctx.focus.isFocused(elem)
            val isHovered = ctx.hoverTarget.exists(_ eq elem)
            val isActive  = ctx.activeTarget.exists(_ eq elem)
            if isHovered then rs.hoverStyle.foreach(rs.applyProps)
            if isFocused then
                val fs = rs.focusStyle.getOrElse(ctx.theme.defaultFocusStyle)
                rs.applyProps(fs)
            if isActive then rs.activeStyle.foreach(rs.applyProps)
        end if
    end resolveAllStyles

    private def paintElement(
        elem: UI.Element,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        // Apply margins and translate offset
        val tx    = x + rs.transX + rs.marL
        val ty    = y + rs.transY + rs.marT
        val adjW0 = w - rs.marL - rs.marR
        val adjH0 = h - rs.marT - rs.marB

        // Phase 4: Constrain to explicit size (border-box = content + padding + border)
        val adjW = constrainToBorderBox(
            adjW0,
            rs.sizeW,
            rs.padL,
            rs.padR,
            rs.borderL,
            rs.borderR,
            rs.flexGrow
        )
        val adjH = constrainToBorderBox(
            adjH0,
            rs.sizeH,
            rs.padT,
            rs.padB,
            rs.borderT,
            rs.borderB,
            rs.flexGrow
        )

        if adjW <= 0 || adjH <= 0 then return

        // Shadow
        if rs.shadowColor != ColorUtils.Absent then
            ctx.canvas.screen.fillBg(
                tx + rs.shadowX - rs.shadowSpread,
                ty + rs.shadowY - rs.shadowSpread,
                adjW + rs.shadowSpread * 2,
                adjH + rs.shadowSpread * 2,
                rs.shadowColor
            )
        end if

        // Background (gradient or solid)
        if rs.gradientDir >= 0 then
            paintGradient(tx, ty, adjW, adjH, rs, ctx)
        else if rs.bg != ColorUtils.Absent then
            ctx.canvas.screen.fillBg(tx, ty, adjW, adjH, rs.bg)
        end if

        // Widget lookup for selfRendered check and rendering
        val widget         = WidgetRegistry.lookup(elem)
        val isSelfRendered = widget.selfRendered

        // Border — skip for self-rendered widgets
        val bt = if rs.borderT then 1 else 0
        val br = if rs.borderR then 1 else 0
        val bb = if rs.borderB then 1 else 0
        val bl = if rs.borderL then 1 else 0
        if !isSelfRendered && (bt | br | bb | bl) != 0 && rs.borderStyle != 0 then
            ctx.canvas.border(tx, ty, adjW, adjH, rs)

        // Content rect — self-rendered widgets get the full box area
        val cx = if isSelfRendered then tx else tx + bl + rs.padL
        val cy = if isSelfRendered then ty else ty + bt + rs.padT
        val cw = if isSelfRendered then adjW else adjW - bl - br - rs.padL - rs.padR
        val ch = if isSelfRendered then adjH else adjH - bt - bb - rs.padT - rs.padB

        if cw > 0 && ch > 0 then
            // Cache content rect for mouse click→cursor mapping
            ctx.recordContentPosition(elem, cx, cy, cw, ch)

            // Phase 6: Only save/restore list context for Ul/Ol (not every element)
            val isList        = elem.isInstanceOf[UI.Ul] || elem.isInstanceOf[UI.Ol]
            val prevListKind  = ctx.listKind
            val prevListIndex = ctx.listIndex
            if isList then
                elem match
                    case _: UI.Ul => ctx.listKind = 1; ctx.listIndex = 0
                    case _: UI.Ol => ctx.listKind = 2; ctx.listIndex = 0
                    case _        => ()
            end if

            // Phase 2: Save/restore inherited style with exception safety
            ctx.saveInherited()
            ctx.inherited.applyFrom(rs)
            try
                widget.render(elem, cx, cy, cw, ch, rs, ctx)
            finally
                ctx.restoreInherited()
                if isList then
                    ctx.listKind = prevListKind
                    ctx.listIndex = prevListIndex
            end try
        end if

        // Post-process filters
        if rs.filterBits != 0 then
            ctx.canvas.applyFilters(tx, ty, adjW, adjH, rs.filterBits, rs.filterVals)
    end paintElement

    /** Phase 4: Constrain available size to explicit border-box size. */
    private inline def constrainToBorderBox(
        avail: Int,
        contentSize: Int,
        padStart: Int,
        padEnd: Int,
        borderStart: Boolean,
        borderEnd: Boolean,
        flexGrow: Double
    ): Int =
        if flexGrow > 0.0 then
            avail // flex-grow can expand beyond explicit size
        else if contentSize >= 0 then
            val borderBox = contentSize + padStart + padEnd +
                (if borderStart then 1 else 0) + (if borderEnd then 1 else 0)
            math.min(avail, borderBox)
        else if contentSize < -1 then
            // Percentage: resolve against available
            val pct = -(contentSize + 1)
            math.min(avail, (avail * pct) / 100)
        else
            avail // auto: use full available

    // ---- Helpers ----

    private def resolveDisabled(elem: UI.Element, ctx: RenderCtx)(using Frame, AllowUnsafe): Boolean =
        elem match
            case hd: UI.HasDisabled =>
                ValueResolver.resolveBoolean(hd.disabled, ctx.signals)
            case _ => false

    private def renderForeach(
        fe: UI.Foreach[?],
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        val seq  = ctx.resolve(fe.signal)
        val size = seq.size
        val arr  = new Array[UI](size)
        @tailrec def fill(i: Int): Unit =
            if i < size then
                arr(i) = ValueResolver.foreachApply(fe, i, seq(i))
                fill(i + 1)
        fill(0)
        Container.renderColumn(Span.fromUnsafe(arr), x, y, w, h, ctx)
    end renderForeach

    /** Render per-cell gradient background. */
    private def paintGradient(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    ): Unit =
        val stops = rs.gradientStops
        if stops < 2 || w <= 0 || h <= 0 then return
        val maxX = math.max(1, w - 1).toDouble
        val maxY = math.max(1, h - 1).toDouble
        var py   = 0
        while py < h do
            var px = 0
            while px < w do
                // Compute normalized position (0.0–1.0) based on direction
                val t = rs.gradientDir match
                    case 0 => px / maxX                                       // toRight
                    case 1 => (maxX - px) / maxX                              // toLeft
                    case 2 => (maxY - py) / maxY                              // toTop
                    case 3 => py / maxY                                       // toBottom
                    case 4 => (px / maxX + (maxY - py) / maxY) / 2.0          // toTopRight
                    case 5 => ((maxX - px) / maxX + (maxY - py) / maxY) / 2.0 // toTopLeft
                    case 6 => (px / maxX + py / maxY) / 2.0                   // toBottomRight
                    case _ => ((maxX - px) / maxX + py / maxY) / 2.0          // toBottomLeft
                val color = interpolateGradient(t, rs, stops)
                ctx.canvas.screen.fillBg(x + px, y + py, 1, 1, color)
                px += 1
            end while
            py += 1
        end while
    end paintGradient

    /** Interpolate gradient color at position t (0.0–1.0). */
    private def interpolateGradient(t: Double, rs: ResolvedStyle, stops: Int): Int =
        if t <= rs.gradientPositions(0) then rs.gradientColors(0)
        else if t >= rs.gradientPositions(stops - 1) then rs.gradientColors(stops - 1)
        else
            // Find surrounding stops
            var i = 1
            while i < stops && rs.gradientPositions(i) < t do i += 1
            val p0 = rs.gradientPositions(i - 1)
            val p1 = rs.gradientPositions(i)
            val c0 = rs.gradientColors(i - 1)
            val c1 = rs.gradientColors(i)
            val f  = if p1 > p0 then (t - p0) / (p1 - p0) else 0.0
            // blend(src, dst, alpha): alpha=1.0 → src, alpha=0.0 → dst
            ColorUtils.blend(c1, c0, f)
    end interpolateGradient

end Render
