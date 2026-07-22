package kyo.internal

import kyo.*
import kyo.Chart.*
import kyo.Chart.Encoding
import kyo.UI.*
import kyo.UI.Ast.*

/** Axis tick, label, and gridline rendering for chart frames.
  *
  * Provides axis-chrome color resolution (`axisChromeColor`, `axisChromeColorFor`, `gridlineColor`,
  * `pointSeparatorColor`, `themePalette`, `markDefaultColor`, `markAxisOf`, `isSolidColorMark`), the
  * chart default palette (`DefaultPalette`), the theme background constants (`DarkBg`, `LightBg`), and
  * the frame-assembly functions: `buildBackground`, `buildAxisLines`, `buildYAxis`, `buildXAxis`,
  * and `buildFrame` (which assembles all frame chrome and legend into a flat element chunk).
  */
private[kyo] object ChartAxes:

    import ChartLayout.Layout

    /** Swatch size (pixels) for legend color boxes (shared with legend builders). */
    private val SwatchSize = 12.0

    /** Vertical gap between legend rows (shared with legend builders). */
    private val LegendRowH = 18.0

    /** Horizontal gap between swatch and label text in the legend (shared with legend builders). */
    private val SwatchLabelGap = 6.0

    /** Tick mark half-length (pixels extending past the axis line). */
    private val TickLen = 5.0

    /** Inset (pixels) from the SVG edge at which a rotated y-axis label is centred.
      *
      * The label is placed at the outer edge of its margin column (left label near `AxisLabelInset`, right
      * label near `svgW - AxisLabelInset`) so it clears the tick numbers that sit adjacent to the axis line.
      */
    private val AxisLabelInset = 14.0

    /** Dark-theme background color. */
    private[kyo] val DarkBg: Style.Color = Style.Color.hex("#1f2937").getOrElse(Style.Color.black)

    /** Light-theme background color. */
    private[kyo] val LightBg: Style.Color = Style.Color.white

    /** Axis chrome color (tick lines, tick labels, axis labels) on the light theme: a dark gray readable on white. */
    private val LightThemeTextColor: Style.Color = Style.Color.hex("#374151").getOrElse(Style.Color.black)

    /** Axis chrome color (tick lines, tick labels, axis labels) on the dark theme: a light gray readable on dark. */
    private val DarkThemeTextColor: Style.Color = Style.Color.hex("#e5e7eb").getOrElse(Style.Color.white)

    /** Default chart colors used when no `colorScale` override is set. */
    private[kyo] val DefaultPalette: Chunk[Style.Color] = Chunk(
        Style.Color.blue,
        Style.Color.orange,
        Style.Color.green,
        Style.Color.red,
        Style.Color.purple,
        Style.Color.pink,
        Style.Color.yellow,
        Style.Color.gray
    )

    /** Resolve the axis chrome color (tick lines, tick labels, axis labels) for the given theme. */
    private[kyo] def axisChromeColor(theme: Theme): Style.Color =
        theme.axisColor.getOrElse(if theme.isDark then DarkThemeTextColor else LightThemeTextColor)

    /** The y-axis a data-series mark binds to. Rule marks are reference annotations, not series, and are
      * excluded from axis-to-mark binding by the caller.
      */
    private[kyo] def markAxisOf[A](mark: Mark[A]): Axis = mark match
        case m: Mark.Bar[A, ?, ?]      => m.axis
        case m: Mark.Line[A, ?, ?]     => m.axis
        case m: Mark.Area[A, ?, ?]     => m.axis
        case m: Mark.Point[A, ?, ?]    => m.axis
        case m: Mark.Rule[A]           => m.axis
        case m: Mark.Text[A, ?, ?]     => m.axis
        case m: Mark.ErrorBar[A, ?, ?] => m.axis

    /** Whether a mark renders as ONE solid color, i.e. it has no `color` encoding and is not stack-grouped.
      *
      * A grouped or stacked mark (a `color` encoding present, or a `stack` grouping present) renders in
      * multiple category colors, so its y-axis must not be color-coded to a single palette color. Rule marks
      * are reference annotations and never count as a solid-color series here. Line and Point marks have no
      * stacking, so only their `color` encoding matters.
      */
    private def isSolidColorMark[A](mark: Mark[A]): Boolean = mark match
        case m: Mark.Bar[A, ?, ?]      => m.color.isEmpty && m.stack.group.isEmpty
        case m: Mark.Area[A, ?, ?]     => m.color.isEmpty && m.stack.group.isEmpty
        case m: Mark.Line[A, ?, ?]     => m.color.isEmpty
        case m: Mark.Point[A, ?, ?]    => m.color.isEmpty
        case _: Mark.Rule[A]           => false
        case _: Mark.Text[A, ?, ?]     => false
        case _: Mark.ErrorBar[A, ?, ?] => false

    /** Resolve the chrome color for one y-axis so a reader can tell which axis a series uses.
      *
      * When exactly ONE data-series mark (Bar/Line/Area/Point, not Rule) binds to `axis` AND that mark renders
      * as one solid color (no `color` encoding, not stack-grouped), the axis chrome (tick labels, tick marks,
      * axis line, rotated axis label) takes that mark's palette color via its per-mark palette index
      * (mark 0 -> palette(0), mark 1 -> palette(1), ...). When zero or multiple marks bind to the axis, or the
      * single bound mark is grouped/stacked (multi-color), the neutral theme chrome color is used so a single
      * palette color does not misrepresent a multi-color series and the chart stays legible.
      */
    private[kyo] def axisChromeColorFor[A](theme: Theme, marks: Chunk[Mark[A]], axis: Axis): Style.Color =
        // An explicit theme axis color is a deliberate override and wins over per-mark color-coding.
        theme.axisColor match
            case Present(c) => c
            case Absent =>
                val bound: Chunk[(Mark[A], Int)] = marks.zipWithIndex.collect:
                    case (m, i) if !m.isInstanceOf[Mark.Rule[?]] && markAxisOf(m) == axis => (m, i)
                bound match
                    case Chunk((mark, idx)) if isSolidColorMark(mark) => markDefaultColor(theme, idx)
                    case _                                            => axisChromeColor(theme)
    end axisChromeColorFor

    /** Gridline color: ALWAYS the neutral theme chrome color, regardless of whether the axis chrome is
      * color-coded to a bound mark. Gridlines are a background reference, not axis identity, so they read as a
      * neutral light gray on the light theme and a subtle light line on the dark theme (the strokeOpacity is
      * applied at the draw site).
      */
    private[kyo] def gridlineColor(theme: Theme): Style.Color =
        theme.gridColor.getOrElse(axisChromeColor(theme))

    /** Separating outline color for `point` circles: the theme background color (white on light, the dark
      * background on dark) so each filled bubble is bordered by a thin contrasting ring and adjacent bubbles
      * read as distinct discs instead of merging.
      */
    private[kyo] def pointSeparatorColor(theme: Theme): Style.Color =
        if theme.isDark then DarkBg else LightBg

    /** Resolve the palette used for per-mark default colors: the theme palette when set, else `DefaultPalette`. */
    private[kyo] def themePalette(theme: Theme): Chunk[Style.Color] =
        theme.palette.getOrElse(DefaultPalette)

    /** Resolve the default color for the mark at `markIndex` (cycling the theme palette).
      *
      * Used when a mark has no explicit `color` encoding so that a multi-mark chart (e.g. a bar plus a line)
      * gives each mark a distinct palette color: mark 0 uses palette(0), mark 1 uses palette(1), and so on.
      */
    private[kyo] def markDefaultColor(theme: Theme, markIndex: Int): Style.Color =
        val p = themePalette(theme)
        if p.isEmpty then DefaultPalette(0) else p(markIndex % p.size)
    end markDefaultColor

    /** Build the static frame elements: background rect, axis lines, gridlines, tick marks, tick labels,
      * axis labels, and the color legend.
      *
      * Returns a flat `Chunk` of `Svg.SvgElement`s that are prepended to the root before the marks group.
      * All shapes are plain rects/lines/text; no `url(#id)` references are emitted.
      */
    private[kyo] def buildFrame[A](
        layout: Layout,
        xs: Scale,
        ysL: Scale,
        ysR: Maybe[Scale],
        spec: Chart[A],
        rows: Chunk[A],
        gradPrefix: String
    )(using Frame): Chunk[Svg.SvgElement] =
        // Per-axis chrome color: when exactly one mark binds to an axis, its chrome matches that mark's
        // palette color so a reader can tell which axis a series uses; otherwise the neutral theme color.
        val leftChrome  = axisChromeColorFor(spec.theme, spec.marks, Axis.Left)
        val rightChrome = axisChromeColorFor(spec.theme, spec.marks, Axis.Right)
        val gridColor   = gridlineColor(spec.theme)
        // Left-wins tie-break: when BOTH axes set showGrid, only left emits gridlines (prevents doubles).
        val leftDrawGrid  = spec.yAxisCfg.showGrid
        val rightDrawGrid = spec.yAxisRightCfg.exists(_.showGrid) && !leftDrawGrid
        val background    = buildBackground(layout, spec.theme)
        val axisLines     = buildAxisLines(layout, ysR, spec.theme, leftChrome, rightChrome)
        val leftAxis      = buildYAxis(layout, ysL, spec.yAxisCfg, isRight = false, spec.theme, leftChrome, gridColor, leftDrawGrid)
        val rightAxis = ysR match
            case Present(ysR_) =>
                buildYAxis(
                    layout,
                    ysR_,
                    spec.yAxisRightCfg.getOrElse(AxisConfig.default),
                    isRight = true,
                    spec.theme,
                    rightChrome,
                    gridColor,
                    rightDrawGrid
                )
            case Absent => Chunk.empty
        val xAxisElems  = buildXAxis(layout, xs, spec.xAxisCfg, spec.theme)
        val legendElems = ChartLegend.buildLegend(layout, spec, rows, gradPrefix)
        background ++ axisLines ++ leftAxis ++ rightAxis ++ xAxisElems ++ legendElems
    end buildFrame

    /** Background rectangle filled with the theme color.
      *
      * Covers the WHOLE SVG canvas (not only the plot rectangle) so that on the dark theme the entire chart,
      * including the axis margins where tick numbers and axis labels live, reads as dark. The light theme uses
      * white, matching the page background.
      */
    private[kyo] def buildBackground(layout: Layout, theme: Theme)(using Frame): Chunk[Svg.SvgElement] =
        val color = theme.background.getOrElse(if theme.isDark then DarkBg else LightBg)
        Chunk(
            Svg.rect
                .x(0.0)
                .y(0.0)
                .width(layout.svgW)
                .height(layout.svgH)
                .fill(Svg.Paint.Color(color))
        )
    end buildBackground

    /** Axis lines bordering the plot area (left + bottom + optional right).
      *
      * The left axis line takes `leftChrome` and the right axis line `rightChrome` so each vertical axis
      * line matches its bound series. The bottom (shared x) axis line keeps the neutral theme chrome.
      */
    private[kyo] def buildAxisLines(
        layout: Layout,
        ysR: Maybe[Scale],
        theme: Theme,
        leftChrome: Style.Color,
        rightChrome: Style.Color
    )(using Frame): Chunk[Svg.SvgElement] =
        val chrome = axisChromeColor(theme)
        val leftLine = Svg.line
            .x1(layout.plotX).y1(layout.plotY)
            .x2(layout.plotX).y2(layout.plotBaseline)
            .stroke(Svg.Paint.Color(leftChrome))
        val bottomLine = Svg.line
            .x1(layout.plotX).y1(layout.plotBaseline)
            .x2(layout.plotX + layout.plotW).y2(layout.plotBaseline)
            .stroke(Svg.Paint.Color(chrome))
        val rightLine: Maybe[Svg.SvgElement] = ysR match
            case Present(_) =>
                val rx = layout.plotX + layout.plotW
                Present(
                    Svg.line
                        .x1(rx).y1(layout.plotY)
                        .x2(rx).y2(layout.plotBaseline)
                        .stroke(Svg.Paint.Color(rightChrome))
                )
            case Absent => Absent
        val base = Chunk[Svg.SvgElement](leftLine, bottomLine)
        rightLine match
            case Present(l) => base.append(l)
            case Absent     => base
    end buildAxisLines

    /** Build the left or right y-axis: gridlines (if enabled), tick marks, and tick labels.
      *
      * `chrome` colors this axis's IDENTITY chrome (tick marks, tick labels, rotated axis label). It matches
      * the bound series when exactly one solid-color mark binds to this axis, else the neutral theme color.
      * `gridColor` colors the gridlines and is ALWAYS the neutral theme color: gridlines are a background
      * reference, not axis identity, so they never take a color-coded chrome.
      */
    private[kyo] def buildYAxis(
        layout: Layout,
        ys: Scale,
        cfg: AxisConfig,
        isRight: Boolean,
        theme: Theme,
        chrome: Style.Color,
        gridColor: Style.Color,
        drawGrid: Boolean = false
    )(using Frame): Chunk[Svg.SvgElement] =
        val ticks  = ys.ticks(cfg.tickCount)
        val axisX  = if isRight then layout.plotX + layout.plotW else layout.plotX
        val labelX = if isRight then axisX + TickLen + 4.0 else axisX - TickLen - 4.0
        val anchor = if isRight then Svg.TextAnchor.Start else Svg.TextAnchor.End

        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= ticks.size then acc
            else
                val tick = ticks(i)
                val py   = tick.pixel
                // gridline: always the neutral grid color, never the (possibly color-coded) axis chrome.
                // drawGrid is pre-computed by the caller using the left-wins tie-break:
                //   leftDrawGrid  = spec.yAxisCfg.showGrid
                //   rightDrawGrid = spec.yAxisRightCfg.exists(_.showGrid) && !leftDrawGrid
                val grid: Maybe[Svg.SvgElement] =
                    if drawGrid then
                        Present(
                            Svg.line
                                .x1(layout.plotX).y1(py)
                                .x2(layout.plotX + layout.plotW).y2(py)
                                .stroke(Svg.Paint.Color(gridColor))
                                .strokeOpacity(0.3)
                        )
                    else Absent
                // tick mark
                val tickMark: Svg.SvgElement =
                    if isRight then
                        Svg.line
                            .x1(axisX).y1(py)
                            .x2(axisX + TickLen).y2(py)
                            .stroke(Svg.Paint.Color(chrome))
                    else
                        Svg.line
                            .x1(axisX - TickLen).y1(py)
                            .x2(axisX).y2(py)
                            .stroke(Svg.Paint.Color(chrome))
                // tick label: apply the formatter to the domain value, not the pixel position
                val labelStr = cfg.tickFormat match
                    case Present(f) => f(tick.value)
                    case Absent     => tick.label
                // Resolve the effective anchor. When the user has not set cfg.tickAnchor
                // (the default TextAnchor.Middle), keep the side-default (End for left, Start for
                // right). Only switch to the configured anchor when the user explicitly called
                // .anchor(...) with a non-default value.
                val effAnchor: Svg.TextAnchor =
                    if cfg.tickAnchor != TextAnchor.Middle then toSvgAnchor(cfg.tickAnchor)
                    else anchor // side-default: End for left, Start for right
                val tickLabelElem: Svg.SvgElement =
                    tickLabel(labelX, py, labelStr, chrome, Svg.DominantBaseline.Middle, effAnchor, cfg, theme)
                val elements = grid match
                    case Present(g) => Chunk[Svg.SvgElement](g, tickMark, tickLabelElem)
                    case Absent     => Chunk[Svg.SvgElement](tickMark, tickLabelElem)
                loop(i + 1, acc ++ elements)
        val base = loop(0, Chunk.empty)
        // axis label: rotate vertically so the full string fits in the margin column without clipping.
        // The label sits at the OUTER edge of its margin so it never overlaps the tick numbers, which sit
        // adjacent to the axis line (left numbers extend left from the axis, right numbers extend right).
        //   Left axis: rotate -90 degrees, centred near the left edge of the SVG (x = AxisLabelInset).
        //   Right axis: rotate +90 degrees, centred near the right edge of the SVG (x = svgW - AxisLabelInset).
        cfg.axisLabel match
            case Present(lbl) =>
                val midY = layout.plotY + layout.plotH / 2.0
                val labelElem: Svg.SvgElement =
                    if isRight then
                        val cx = layout.svgW - AxisLabelInset
                        withTitleFont(
                            theme,
                            Svg.text
                                .x(cx)
                                .y(midY)
                                .textAnchor(Svg.TextAnchor.Middle)
                                .dominantBaseline(Svg.DominantBaseline.Auto)
                                .fill(Svg.Paint.Color(chrome))
                                .transform(Svg.Transform.Rotate(90.0, Present(cx), Present(midY)))
                        ).apply(lbl)
                    else
                        val cx = AxisLabelInset
                        withTitleFont(
                            theme,
                            Svg.text
                                .x(cx)
                                .y(midY)
                                .textAnchor(Svg.TextAnchor.Middle)
                                .dominantBaseline(Svg.DominantBaseline.Auto)
                                .fill(Svg.Paint.Color(chrome))
                                .transform(Svg.Transform.Rotate(-90.0, Present(cx), Present(midY)))
                        ).apply(lbl)
                base.append(labelElem)
            case Absent => base
        end match
    end buildYAxis

    /** Map a configured `TextAnchor` to the SVG text-anchor token.
      *
      * Used by `tickLabel` and by `buildXAxis` when setting the anchor on x tick labels.
      * Callers that need a side-default anchor (e.g. `buildYAxis`) resolve it before calling
      * `tickLabel`, passing the already-resolved `Svg.TextAnchor` directly.
      */
    private def toSvgAnchor(a: TextAnchor): Svg.TextAnchor =
        a match
            case TextAnchor.Start  => Svg.TextAnchor.Start
            case TextAnchor.Middle => Svg.TextAnchor.Middle
            case TextAnchor.End    => Svg.TextAnchor.End

    /** Apply theme font family and font size to an Svg.Text element when the theme sets them.
      *
      * A no-op when both `theme.fontFamily` and `theme.fontSize` are `Absent`; the element is
      * returned unchanged in that case. Called from `tickLabel` and directly from axis-title
      * and legend-text sites (which are not rotated and are not passed through `tickLabel`).
      */
    private[kyo] def withFont(theme: Theme, t: Svg.Text): Svg.Text =
        val t1 = theme.fontFamily.fold(t)(f => t.fontFamily(f))
        theme.fontSize.fold(t1)(px => t1.fontSize(Svg.SvgLength.Px(px)))

    /** Apply theme font family and the axis-title font size to an Svg.Text element.
      *
      * Axis titles size from `theme.titleFontSize`, falling back to `theme.fontSize`, so a dense
      * dashboard can pair small tick labels with readable titles. A no-op (element unchanged) when
      * the theme sets no font field, matching `withFont`.
      */
    private[kyo] def withTitleFont(theme: Theme, t: Svg.Text): Svg.Text =
        val t1 = theme.fontFamily.fold(t)(f => t.fontFamily(f))
        theme.titleFontSize.orElse(theme.fontSize).fold(t1)(px => t1.fontSize(Svg.SvgLength.Px(px)))

    /** Apply theme font, configured anchor, and configured rotation to a tick-label text element.
      *
      * Builds an `Svg.text` at `(x, y)` for `labelStr`, fills it with `chrome`, sets
      * `dominantBaseline`, applies `theme.fontFamily`/`theme.fontSize` when set (via `withFont`),
      * sets `anchor` as the SVG text-anchor, and rotates about `(x, y)` when `cfg.tickRotation !=
      * 0.0`. This is the single tick-label chrome path shared by `buildXAxis` and `buildYAxis`
      * so the two axes cannot drift.
      *
      * `anchor` is CALLER-RESOLVED: `buildXAxis` passes `toSvgAnchor(cfg.tickAnchor)`; `buildYAxis`
      * passes its side-default (`Svg.TextAnchor.End` for left, `Svg.TextAnchor.Start` for right)
      * unless the user set `cfg.tickAnchor` explicitly (the anchor override logic). This design keeps the
      * helper anchor-agnostic and avoids the helper reading `cfg` for a side-dependent default it
      * cannot know.
      *
      * `baseline` is also caller-supplied: x-axis uses `Svg.DominantBaseline.Hanging` (label below
      * the tick mark); y-axis uses `Svg.DominantBaseline.Middle` (label beside the tick mark).
      */
    private def tickLabel(
        x: Double,
        y: Double,
        labelStr: String,
        chrome: Style.Color,
        baseline: Svg.DominantBaseline,
        anchor: Svg.TextAnchor,
        cfg: AxisConfig,
        theme: Theme
    )(using Frame): Svg.SvgElement =
        val base: Svg.Text =
            withFont(
                theme,
                Svg.text
                    .x(x)
                    .y(y)
                    .textAnchor(anchor)
                    .dominantBaseline(baseline)
                    .fill(Svg.Paint.Color(chrome))
            ).apply(labelStr)
        if cfg.tickRotation != 0.0 then
            base.transform(Svg.Transform.Rotate(cfg.tickRotation, Present(x), Present(y)))
        else base
    end tickLabel

    /** Build the x-axis: tick marks and tick labels along the bottom. */
    private[kyo] def buildXAxis(
        layout: Layout,
        xs: Scale,
        cfg: AxisConfig,
        theme: Theme
    )(using Frame): Chunk[Svg.SvgElement] =
        val ticks   = xs.ticks(cfg.tickCount)
        val axisY   = layout.plotBaseline
        val chrome  = axisChromeColor(theme)
        val gridCol = gridlineColor(theme)
        val labelY  = axisY + TickLen + 4.0

        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= ticks.size then acc
            else
                val tick = ticks(i)
                val px   = tick.pixel
                // Vertical gridline at this tick when showGrid is enabled.
                val withGrid =
                    if cfg.showGrid then
                        acc.append(
                            Svg.line
                                .x1(px).y1(layout.plotY)
                                .x2(px).y2(layout.plotBaseline)
                                .stroke(Svg.Paint.Color(gridCol))
                                .strokeOpacity(0.5)
                        )
                    else acc
                val tickMark: Svg.SvgElement =
                    Svg.line
                        .x1(px).y1(axisY)
                        .x2(px).y2(axisY + TickLen)
                        .stroke(Svg.Paint.Color(chrome))
                // tick label: apply the formatter to the domain value when present
                val xLabelStr = cfg.tickFormat match
                    case Present(f) => f(tick.value)
                    case Absent     => tick.label
                // Map cfg.tickAnchor to the SVG token and build the tick label element with
                // font, anchor, and optional rotation applied via the shared tickLabel helper.
                val tickLabelElem: Svg.SvgElement =
                    tickLabel(
                        px,
                        labelY,
                        xLabelStr,
                        chrome,
                        Svg.DominantBaseline.Hanging,
                        toSvgAnchor(cfg.tickAnchor),
                        cfg,
                        theme
                    )
                loop(i + 1, withGrid.append(tickMark).append(tickLabelElem))
        val base = loop(0, Chunk.empty)
        // axis label
        cfg.axisLabel match
            case Present(lbl) =>
                val labelElem: Svg.SvgElement =
                    withTitleFont(
                        theme,
                        Svg.text
                            .x(layout.plotX + layout.plotW / 2.0)
                            .y(layout.svgH - 4.0)
                            .textAnchor(Svg.TextAnchor.Middle)
                            .fill(Svg.Paint.Color(chrome))
                    ).apply(lbl)
                base.append(labelElem)
            case Absent => base
        end match
    end buildXAxis

end ChartAxes
