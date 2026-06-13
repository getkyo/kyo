package kyo.internal

import kyo.*
import kyo.Chart.*
import kyo.Chart.Encoding
import kyo.UI.*
import kyo.UI.Ast.*

/** Plot-rectangle and margin computation for chart layout.
  *
  * Provides the immutable `Layout` case class that carries the outer SVG dimensions and the inner plot
  * rectangle, the margin constants, and the two computation functions: `leftAxisMargin` (which widens the
  * left margin so that wide y-axis tick labels and a rotated axis title fit without clipping) and
  * `buildLayout` (which assembles a full `Layout` from a chart spec's size and margin configuration).
  */
private[kyo] object ChartLayout:

    /** Standard margins used when computing the plot rectangle from the chart size. */
    private val MarginLeft         = 60.0
    private val MarginRightDefault = 20.0
    private val MarginRightAxis    = 60.0
    private val MarginTop          = 20.0
    private val MarginBottom       = 40.0

    /** Default point radius when no `size` encoding is supplied. */
    private val DefaultRadius = 4.0

    /** Reserved space at the top of the plot for the legend (pixels).
      *
      * When a legend is present the plot area is shifted down by this amount so that legend items fit above
      * the bars without overlapping the data.
      */
    private[kyo] val LegendReservedH = 20.0

    /** Reserved width (pixels) for a left- or right-positioned legend column.
      *
      * When the legend sits beside the plot (`LegendPosition.Left` / `Right`), the plot rectangle narrows by
      * this amount so the vertically-stacked swatches and labels do not overlap the data.
      */
    private val LegendColumnW = 80.0

    /** Approximate rendered pixel width of one tick-label glyph at the default font size.
      *
      * Matches the 7.0px-per-char estimate already used by the tooltip overlay so the auto-margin
      * math is consistent with the rest of the lowering. The labels are short numeric strings, so a
      * single average advance is accurate enough to keep the widest label inside the SVG.
      */
    private val TickLabelCharW = 7.0

    /** Pixel column reserved for a rotated (vertical) y-axis title, measured from the SVG edge.
      *
      * The title is centred at `AxisLabelInset` and rotated -90 degrees, so it occupies a thin vertical
      * strip about one cap-height wide plus the inset. Reserving this much keeps the title clear of the
      * tick numbers that sit just inside it.
      */
    private val RotatedAxisTitleW = 14.0 + 12.0

    /** Immutable layout: the outer SVG dimensions and the inner plot rectangle. */
    final private[kyo] case class Layout(
        svgW: Double,
        svgH: Double,
        plotX: Double,
        plotY: Double,
        plotW: Double,
        plotH: Double,
        // Extra pixels of headroom reserved INSIDE the plot top for point glyphs whose centre sits at the data
        // max: the y-scale's top range is pushed down by this much so the topmost point's edge (cy - r) clears
        // plotY. Zero for charts without a reserved top legend band (so legend-free charts get no headroom offset).
        topHeadroom: Double = 0.0
    ):
        def plotBaseline: Double = plotY + plotH
    end Layout

    /** Compute the left margin needed so the widest left y-axis tick label (and the rotated axis title,
      * when present) fits without clipping at the SVG's left edge.
      *
      * The tick label STRINGS depend only on the resolved left y-domain and tick count, not on the plot
      * height, so they can be computed before the final plot rectangle is known: a provisional unit pixel
      * range yields the same tick VALUES (only `Tick.pixel` differs, which is unused here). The required
      * margin is `TickLen + gap + widestLabel`, plus the rotated-title column when a left axis label is set.
      * Returns the configured margin unchanged when it is already wide enough, so a chart with narrow
      * labels keeps its configured margin.
      */
    private[kyo] def leftAxisMargin[A](spec: Chart[A], configuredLeft: Double)(using Frame, AllowUnsafe): Double =
        val rowsMaybe: Maybe[Chunk[A]] = spec.data match
            case DataSource.Static(rs)   => Present(rs)
            case DataSource.Live(signal) =>
                // Unsafe: a point-in-time sample of the live signal's current rows, used only to size the
                // left margin to the labels currently on screen. Pure synchronous read, no suspension.
                // Cannot throw: signal.current returns A < Sync (no Abort in scope), so Abort.run yields
                // Result.Ok and getOrThrow never executes the throw branch.
                Present(Sync.Unsafe.evalOrThrow(signal.current))
        rowsMaybe match
            case Absent => configuredLeft
            case Present(rows) =>
                val cfg = spec.yAxisCfg
                // Resolve the left y-domain exactly as resolveAllScales does (the pixel range is provisional:
                // tick VALUES/labels are pixel-range-independent, only Tick.pixel changes).
                val yExt         = ChartScales.yLeftExtent(rows, spec.marks).getOrElse(Extent.Continuous(0.0, 1.0))
                val yNice        = spec.yScaleOverride.map(_.nice).getOrElse(true)
                val kindOverride = spec.yScaleOverride.flatMap(_.kind)
                val yKind = kindOverride match
                    case Absent => Scale.Kind.Linear
                    case Present(kind) => kind match
                            case ScaleKind.Band         => Scale.Kind.Band
                            case ScaleKind.Log          => Scale.Kind.Log
                            case ScaleKind.Linear(_, _) => Scale.Kind.Linear
                            case ScaleKind.Time         => Scale.Kind.Time
                            case ScaleKind.Point        => Scale.Kind.Point
                            case ScaleKind.Symlog       => Scale.Kind.Symlog
                val extFinal: Extent = kindOverride match
                    case Absent => yExt
                    case Present(kind) => kind match
                            case ScaleKind.Linear(domLo, domHi) => Extent.Continuous(domLo, domHi)
                            case ScaleKind.Log =>
                                ChartScales.yLeftExtentNoZero(rows, spec.marks).getOrElse(Extent.Continuous(1.0, 10.0))
                            case ScaleKind.Band | ScaleKind.Time | ScaleKind.Point | ScaleKind.Symlog => yExt
                val useNice = kindOverride match
                    case Absent => yNice
                    case Present(kind) => kind match
                            case ScaleKind.Linear(_, _)                                               => false
                            case ScaleKind.Log                                                        => false
                            case ScaleKind.Band | ScaleKind.Time | ScaleKind.Point | ScaleKind.Symlog => yNice
                val scale = Scale.fit(yKind, extFinal, 100.0, 0.0, nice = useNice, clamp = false)
                val labels = scale.ticks(cfg.tickCount).map: t =>
                    cfg.tickFormat match
                        case Present(f) => f(t.value)
                        case Absent     => t.label
                val widestLabel = labels.foldLeft(0.0)((mx, s) => math.max(mx, s.length.toDouble * TickLabelCharW))
                val titleCol    = if cfg.axisLabel.isDefined then RotatedAxisTitleW else 0.0
                val needed      = 5.0 + 4.0 + widestLabel + titleCol
                math.max(configuredLeft, needed)
        end match
    end leftAxisMargin

    /** Compute the `Layout` from a chart spec's `size` field.
      *
      * Widens the right margin when a right y-axis is configured so tick labels on the right margin do not overlap
      * the plot area. Widens the LEFT margin so wide left y-tick labels (e.g. 5-digit values) and a rotated left
      * axis title fit without clipping at the SVG edge. Shifts the plot down by `LegendReservedH` when the legend
      * will actually render (not hidden AND at least one mark carries a `color` encoding) so legend rows sit in
      * reserved space above the plot without overlapping the bars.
      */
    private[kyo] def buildLayout(spec: Chart[?])(using Frame, AllowUnsafe): Layout =
        val (w, h) = spec.chartSize
        val m      = spec.marginsCfg
        // The right-axis layout still needs the extra fixed reserve for dual-axis charts; otherwise use the
        // configured right margin.
        val marginRight = if spec.yAxisRightCfg.isDefined then MarginRightAxis else m.right
        val hasLegend = !spec.legendCfg.isHidden && spec.marks.exists:
            case m: Mark.Bar[?, ?, ?]      => m.color.isDefined || m.stack.group.isDefined
            case m: Mark.Line[?, ?, ?]     => m.color.isDefined
            case m: Mark.Area[?, ?, ?]     => m.color.isDefined || m.stack.group.isDefined
            case m: Mark.Point[?, ?, ?]    => m.color.isDefined
            case _: Mark.Rule[?]           => false
            case m: Mark.Text[?, ?, ?]     => m.color.isDefined
            case m: Mark.ErrorBar[?, ?, ?] => m.color.isDefined
        // A point mark with a `size` encoding emits a size legend (representative sample bubbles). That legend
        // always sits in the TOP reserved strip, so the plot must be shifted down to make room even when there
        // is no color legend; otherwise the sample bubbles render over the plotted data (the scatter case).
        val hasSizeLegend = !spec.legendCfg.isHidden && spec.marks.exists:
            case m: Mark.Point[?, ?, ?] => m.size.isDefined
            case _                      => false
        // Reserve margin for the legend on the side it is positioned: Top shifts the plot down (default),
        // Bottom shrinks plot height, Left/Right reserve a column beside the plot.
        val pos        = if hasLegend then spec.legendCfg.position.getOrElse(LegendPosition.Top) else LegendPosition.Top
        val reserveTop = (hasLegend && pos == LegendPosition.Top) || hasSizeLegend
        val topPad     = if reserveTop then LegendReservedH else 0.0
        val bottomPad  = if hasLegend && pos == LegendPosition.Bottom then LegendReservedH else 0.0
        val leftPad    = if hasLegend && pos == LegendPosition.Left then LegendColumnW else 0.0
        val rightPad   = if hasLegend && pos == LegendPosition.Right then LegendColumnW else 0.0
        // Point glyphs are centred on their data value, so the topmost (max-value) point's top edge (cy - r)
        // would cross into the reserved top band and be clipped. Reserve the max point radius as in-plot top
        // headroom (consumed by the y-scale range in resolveAllScales). Only when a top band is reserved, so
        // legend-free point charts reserve no top headroom. A size encoding scales up to DefaultRMax.
        val topHeadroom =
            if !reserveTop then 0.0
            else
                spec.marks.foldLeft(0.0): (mx, mark) =>
                    mark match
                        case m: Mark.Point[?, ?, ?] =>
                            val r = if m.size.isDefined then SizeScale.DefaultRMax else DefaultRadius
                            math.max(mx, r)
                        case (_: Mark.Bar[?, ?, ?] | _: Mark.Line[?, ?, ?] | _: Mark.Area[?, ?, ?] | _: Mark.Rule[?] |
                            _: Mark.Text[?, ?, ?] | _: Mark.ErrorBar[?, ?, ?]) =>
                            mx
        // Grow the configured left margin so wide left y-tick labels + a rotated left axis title clear the SVG edge.
        val marginLeft = leftAxisMargin(spec, m.left)
        Layout(
            svgW = w.toDouble,
            svgH = h.toDouble,
            plotX = marginLeft + leftPad,
            plotY = m.top + topPad,
            plotW = w.toDouble - marginLeft - marginRight - leftPad - rightPad,
            plotH = h.toDouble - m.top - topPad - bottomPad - m.bottom,
            topHeadroom = topHeadroom
        )
    end buildLayout

end ChartLayout
