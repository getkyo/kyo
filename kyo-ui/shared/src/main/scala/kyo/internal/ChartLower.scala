package kyo.internal

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.*
// Explicit named import so the bare `Channel` resolves to the chart channel carrier
// (kyo.UI.Ast.Channel) rather than kyo-core's `kyo.Channel`, which `import kyo.*` also brings.
import kyo.UI.Ast.Channel

/** Lowers a `ChartSpec[A]` to an `Svg.Root` for static data.
  *
  * This is the single internal function that produces a real SVG tree from the immutable chart intermediate
  * representation. Layout is computed from `size` and margin constants; scales are resolved from the marks'
  * channels via `Plottable` and `Scale.fit`; each mark is lowered to its corresponding SVG primitive. The
  * static frame (axes, gridlines, tick marks, tick labels, legend) is built by `buildFrame`.
  *
  * Phase 04 adds: static frame chrome (axes/gridlines/ticks/labels/legend), two-y-scale resolution,
  * theme application, scale-override plumbing, and stacked-bar / stacked-area lowering.
  */
private[kyo] object ChartLower:

    /** Standard margins used when computing the plot rectangle from the chart size. */
    private val MarginLeft         = 60.0
    private val MarginRightDefault = 20.0
    private val MarginRightAxis    = 60.0
    private val MarginTop          = 20.0
    private val MarginBottom       = 40.0

    /** Default point radius when no `size` channel is supplied. */
    private val DefaultRadius = 4.0

    /** Stroke width (pixels) of the separating outline drawn around each `point` circle so that overlapping
      * or adjacent bubbles read as distinct discs rather than merging into one blob.
      */
    private val PointStrokeWidth = 1.5

    /** Swatch size (pixels) for legend color boxes. */
    private val SwatchSize = 12.0

    /** Vertical gap between legend rows. */
    private val LegendRowH = 18.0

    /** Horizontal gap between swatch and label text in the legend. */
    private val SwatchLabelGap = 6.0

    /** Tick mark half-length (pixels extending past the axis line). */
    private val TickLen = 5.0

    /** Inset (pixels) from the SVG edge at which a rotated y-axis label is centred.
      *
      * The label is placed at the outer edge of its margin column (left label near `AxisLabelInset`, right
      * label near `svgW - AxisLabelInset`) so it clears the tick numbers that sit adjacent to the axis line.
      */
    private val AxisLabelInset = 14.0

    /** Default chart colors used when no `colorScale` override is set. */
    private val DefaultPalette: Chunk[Style.Color] = Chunk(
        Style.Color.blue,
        Style.Color.orange,
        Style.Color.green,
        Style.Color.red,
        Style.Color.purple,
        Style.Color.pink,
        Style.Color.yellow,
        Style.Color.gray
    )

    /** Dark-theme background color. */
    private val DarkBg: Style.Color = Style.Color.hex("#1f2937").getOrElse(Style.Color.black)

    /** Light-theme background color. */
    private val LightBg: Style.Color = Style.Color.white

    /** Axis chrome color (tick lines, tick labels, axis labels) on the light theme: a dark gray readable on white. */
    private val LightThemeTextColor: Style.Color = Style.Color.hex("#374151").getOrElse(Style.Color.black)

    /** Axis chrome color (tick lines, tick labels, axis labels) on the dark theme: a light gray readable on dark. */
    private val DarkThemeTextColor: Style.Color = Style.Color.hex("#e5e7eb").getOrElse(Style.Color.white)

    /** Resolve the axis chrome color (tick lines, tick labels, axis labels) for the given theme. */
    private def axisChromeColor(theme: Theme): Style.Color =
        if theme.isDark then DarkThemeTextColor else LightThemeTextColor

    /** The y-axis a data-series mark binds to. Rule marks are reference annotations, not series, and are
      * excluded from axis-to-mark binding by the caller.
      */
    private def markAxisOf[A](mark: Mark[A]): Axis = mark match
        case m: Mark.Bar[A, ?, ?]      => m.axis
        case m: Mark.Line[A, ?, ?]     => m.axis
        case m: Mark.Area[A, ?, ?]     => m.axis
        case m: Mark.Point[A, ?, ?]    => m.axis
        case m: Mark.Rule[A]           => m.axis
        case m: Mark.Text[A, ?, ?]     => m.axis
        case m: Mark.ErrorBar[A, ?, ?] => m.axis

    /** Whether a mark renders as ONE solid color, i.e. it has no `color` channel and is not stack-grouped.
      *
      * A grouped or stacked mark (a `color` channel present, or a `stack` grouping present) renders in
      * multiple category colors, so its y-axis must not be color-coded to a single palette color. Rule marks
      * are reference annotations and never count as a solid-color series here. Line and Point marks have no
      * stacking, so only their `color` channel matters.
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
      * as one solid color (no `color` channel, not stack-grouped), the axis chrome (tick labels, tick marks,
      * axis line, rotated axis label) takes that mark's palette color via its per-mark palette index
      * (mark 0 -> palette(0), mark 1 -> palette(1), ...). When zero or multiple marks bind to the axis, or the
      * single bound mark is grouped/stacked (multi-color), the neutral theme chrome color is used so a single
      * palette color does not misrepresent a multi-color series and the chart stays legible.
      */
    private def axisChromeColorFor[A](theme: Theme, marks: Chunk[Mark[A]], axis: Axis): Style.Color =
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
    private def gridlineColor(theme: Theme): Style.Color =
        axisChromeColor(theme)

    /** Separating outline color for `point` circles: the theme background color (white on light, the dark
      * background on dark) so each filled bubble is bordered by a thin contrasting ring and adjacent bubbles
      * read as distinct discs instead of merging.
      */
    private def pointSeparatorColor(theme: Theme): Style.Color =
        if theme.isDark then DarkBg else LightBg

    /** Resolve the palette used for per-mark default colors: the theme palette when set, else `DefaultPalette`. */
    private def themePalette(theme: Theme): Chunk[Style.Color] =
        theme.palette.getOrElse(DefaultPalette)

    /** Resolve the default color for the mark at `markIndex` (cycling the theme palette).
      *
      * Used when a mark has no explicit `color` channel so that a multi-mark chart (e.g. a bar plus a line)
      * gives each mark a distinct palette color: mark 0 uses palette(0), mark 1 uses palette(1), and so on.
      */
    private def markDefaultColor(theme: Theme, markIndex: Int): Style.Color =
        val p = themePalette(theme)
        if p.isEmpty then DefaultPalette(0) else p(markIndex % p.size)
    end markDefaultColor

    /** Reserved space at the top of the plot for the legend (pixels).
      *
      * When a legend is present the plot area is shifted down by this amount so that legend items fit above
      * the bars without overlapping the data.
      */
    private val LegendReservedH = 20.0

    /** Reserved width (pixels) for a left- or right-positioned legend column.
      *
      * When the legend sits beside the plot (`LegendPosition.Left` / `Right`), the plot rectangle narrows by
      * this amount so the vertically-stacked swatches and labels do not overlap the data.
      */
    private val LegendColumnW = 80.0

    /** Immutable layout: the outer SVG dimensions and the inner plot rectangle. */
    final private[kyo] case class Layout(
        svgW: Double,
        svgH: Double,
        plotX: Double,
        plotY: Double,
        plotW: Double,
        plotH: Double
    ):
        def plotBaseline: Double = plotY + plotH
    end Layout

    /** Compute the `Layout` from a chart spec's `size` field.
      *
      * Widens the right margin when a right y-axis is configured so tick labels on the right margin do not overlap
      * the plot area. Shifts the plot down by `LegendReservedH` when the legend will actually render (not hidden
      * AND at least one mark carries a color channel) so legend rows sit in reserved space above the plot without
      * overlapping the bars.
      */
    private def buildLayout(spec: ChartSpec[?]): Layout =
        val (w, h)      = spec.chartSize
        val marginRight = if spec.yAxisRightCfg.isDefined then MarginRightAxis else MarginRightDefault
        val hasLegend = !spec.legendCfg.isHidden && spec.marks.exists:
            case m: Mark.Bar[?, ?, ?]      => m.color.isDefined || m.stack.group.isDefined
            case m: Mark.Line[?, ?, ?]     => m.color.isDefined
            case m: Mark.Area[?, ?, ?]     => m.color.isDefined || m.stack.group.isDefined
            case m: Mark.Point[?, ?, ?]    => m.color.isDefined
            case _: Mark.Rule[?]           => false
            case _: Mark.Text[?, ?, ?]     => false
            case _: Mark.ErrorBar[?, ?, ?] => false
        // Reserve margin for the legend on the side it is positioned: Top shifts the plot down (default),
        // Bottom shrinks plot height, Left/Right reserve a column beside the plot.
        val pos       = if hasLegend then spec.legendCfg.position.getOrElse(LegendPosition.Top) else LegendPosition.Top
        val topPad    = if hasLegend && pos == LegendPosition.Top then LegendReservedH else 0.0
        val bottomPad = if hasLegend && pos == LegendPosition.Bottom then LegendReservedH else 0.0
        val leftPad   = if hasLegend && pos == LegendPosition.Left then LegendColumnW else 0.0
        val rightPad  = if hasLegend && pos == LegendPosition.Right then LegendColumnW else 0.0
        Layout(
            svgW = w.toDouble,
            svgH = h.toDouble,
            plotX = MarginLeft + leftPad,
            plotY = MarginTop + topPad,
            plotW = w.toDouble - MarginLeft - marginRight - leftPad - rightPad,
            plotH = h.toDouble - MarginTop - topPad - bottomPad - MarginBottom
        )
    end buildLayout

    // ---- extent collection helpers ----

    /** Fold domain values from a sequence of rows through a channel into an `Extent`.
      *
      * `Absent` returns from `toDomain` (gap values) are skipped and contribute nothing to the extent.
      */
    private def foldExtent(rows: Chunk[?], domainFn: Any => Maybe[Domain]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= rows.size then acc
            else
                domainFn(rows(i)) match
                    case Absent => loop(i + 1, acc)
                    case Present(d) =>
                        val newAcc = d match
                            case Domain.Continuous(v) =>
                                // catalog #2: drop NaN/Inf to avoid poisoning min/max
                                if !ChartFoundations.isFiniteDouble(v) then acc
                                else
                                    acc match
                                        case Absent => Present(Extent.Continuous(v, v))
                                        case Present(Extent.Continuous(lo, hi)) =>
                                            Present(Extent.Continuous(math.min(lo, v), math.max(hi, v)))
                                        case Present(Extent.Categories(_)) => Present(Extent.Continuous(v, v))
                            case Domain.Category(key) =>
                                acc match
                                    case Absent                           => Present(Extent.Categories(Chunk(key)))
                                    case Present(Extent.Categories(keys)) =>
                                        // INV-006: Chunk-native exists, no toSeq round-trip
                                        if keys.exists(_ == key) then Present(Extent.Categories(keys))
                                        else Present(Extent.Categories(keys.append(key)))
                                    case Present(Extent.Continuous(_, _)) => Present(Extent.Categories(Chunk(key)))
                            case Domain.Temporal(ms) =>
                                // catalog #2: drop non-finite timestamps (e.g. Long overflow to Infinity)
                                if !ChartFoundations.isFiniteDouble(ms.toDouble) then acc
                                else
                                    acc match
                                        case Absent => Present(Extent.Continuous(ms.toDouble, ms.toDouble))
                                        case Present(Extent.Continuous(lo, hi)) =>
                                            Present(Extent.Continuous(math.min(lo, ms.toDouble), math.max(hi, ms.toDouble)))
                                        case Present(Extent.Categories(_)) => Present(Extent.Continuous(ms.toDouble, ms.toDouble))
                        loop(i + 1, newAcc)
        loop(0, Absent)
    end foldExtent

    /** Collect x-extent across all marks' x channels for the given rows. */
    private def xExtent[A](rows: Chunk[A], marks: Chunk[Mark[A]]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= marks.size then acc
            else
                val markExtent = marks(i) match
                    case m: Mark.Bar[A, ?, ?]      => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r.asInstanceOf[A])))
                    case m: Mark.Line[A, ?, ?]     => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r.asInstanceOf[A])))
                    case m: Mark.Area[A, ?, ?]     => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r.asInstanceOf[A])))
                    case m: Mark.Point[A, ?, ?]    => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r.asInstanceOf[A])))
                    case _: Mark.Rule[A]           => Absent
                    case m: Mark.Text[A, ?, ?]     => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r.asInstanceOf[A])))
                    case m: Mark.ErrorBar[A, ?, ?] => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r.asInstanceOf[A])))
                val merged = mergeExtents(acc, markExtent)
                loop(i + 1, merged)
        loop(0, Absent)
    end xExtent

    /** Collect y-extent across all marks on `Axis.Left` for the given rows.
      *
      * When the mark has a `stack` grouping, the extent uses the STACKED maxima: for each x value the
      * contributions of all stack groups are summed, and the maximum sum is used. For `normalize = true` the
      * extent is fixed to `[0, 1]`.
      */
    private def yLeftExtent[A](rows: Chunk[A], marks: Chunk[Mark[A]]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= marks.size then acc
            else
                val markExtent: Maybe[Extent] = marks(i) match
                    case m: Mark.Bar[A, ?, ?] if m.axis == Axis.Left =>
                        val base = m.stack.group match
                            case Absent =>
                                foldExtent(rows, r => m.y.plottable.toDomain(m.y.accessor(r.asInstanceOf[A])))
                            case Present(_) =>
                                if m.stack.normalize then Present(Extent.Continuous(0.0, 1.0))
                                else stackedYExtent(rows, m)
                        ensureZero(base)
                    case m: Mark.Line[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(rows, r => m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.Area[A, ?, ?] if m.axis == Axis.Left =>
                        val base = m.y match
                            case Present(ch) =>
                                m.stack.group match
                                    case Present(_) =>
                                        if m.stack.normalize then Present(Extent.Continuous(0.0, 1.0))
                                        else stackedAreaYExtent(rows, m, ch)
                                    case Absent =>
                                        foldExtent(rows, r => ch.accessor(r.asInstanceOf[A]).flatMap(v => ch.plottable.toDomain(v)))
                            case Absent =>
                                val e0 = m.y0 match
                                    case Present(ch) => foldExtent(rows, r => ch.plottable.toDomain(ch.accessor(r.asInstanceOf[A])))
                                    case Absent      => Absent
                                val e1 = m.y1 match
                                    case Present(ch) => foldExtent(rows, r => ch.plottable.toDomain(ch.accessor(r.asInstanceOf[A])))
                                    case Absent      => Absent
                                mergeExtents(e0, e1)
                        ensureZero(base)
                    case m: Mark.Point[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(rows, r => m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.Rule[A] if m.axis == Axis.Left =>
                        m.y match
                            case Present(RuleValue.Const(v, pl)) => pl.asInstanceOf[Plottable[Any]].toDomain(v.asInstanceOf[Any]) match
                                    case Present(d) => Present(extentFromDomain(d))
                                    case Absent     => Absent
                            case _ => Absent
                    case m: Mark.Text[A, ?, ?] if m.axis == Axis.Left =>
                        // INV-021: text y channel contributes to y-extent; gap rows (Absent) are skipped.
                        foldExtent(rows, r => m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.ErrorBar[A, ?, ?] if m.axis == Axis.Left =>
                        // INV-022: all three y-channels (low, high, y) fold into the y-extent.
                        val eY    = foldExtent(rows, r => m.y.plottable.toDomain(m.y.accessor(r.asInstanceOf[A])))
                        val eLow  = foldExtent(rows, r => m.low.plottable.toDomain(m.low.accessor(r.asInstanceOf[A])))
                        val eHigh = foldExtent(rows, r => m.high.plottable.toDomain(m.high.accessor(r.asInstanceOf[A])))
                        ensureZero(mergeExtents(mergeExtents(eY, eLow), eHigh))
                    case _ => Absent
                val merged = mergeExtents(acc, markExtent)
                loop(i + 1, merged)
        loop(0, Absent)
    end yLeftExtent

    /** Collect y-extent across all marks on `Axis.Left` WITHOUT applying `ensureZero`.
      *
      * Used for logarithmic scale resolution, where the domain must not include zero (log(0) is undefined).
      */
    private def yLeftExtentNoZero[A](rows: Chunk[A], marks: Chunk[Mark[A]]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= marks.size then acc
            else
                val markExtent: Maybe[Extent] = marks(i) match
                    case m: Mark.Bar[A, ?, ?] if m.axis == Axis.Left =>
                        // INV-011: filter non-positive values so the log scale domain starts at the smallest positive value.
                        foldExtent(
                            rows,
                            r =>
                                m.y.plottable.toDomain(m.y.accessor(r.asInstanceOf[A])).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.Line[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(
                            rows,
                            r =>
                                m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.Point[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(
                            rows,
                            r =>
                                m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.Text[A, ?, ?] if m.axis == Axis.Left =>
                        // INV-021: text y contributes to the log-scale no-zero extent; positive values only.
                        foldExtent(
                            rows,
                            r =>
                                m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.ErrorBar[A, ?, ?] if m.axis == Axis.Left =>
                        // INV-022: low/high/y for log scale; drop non-positive.
                        def posExtent(ch: Channel[A, ?]) = foldExtent(
                            rows,
                            r =>
                                ch.plottable.toDomain(ch.accessor(r.asInstanceOf[A])).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                        mergeExtents(
                            mergeExtents(posExtent(m.y.asInstanceOf[Channel[A, ?]]), posExtent(m.low.asInstanceOf[Channel[A, ?]])),
                            posExtent(m.high.asInstanceOf[Channel[A, ?]])
                        )
                    case _ => Absent
                val merged = mergeExtents(acc, markExtent)
                loop(i + 1, merged)
        loop(0, Absent)
    end yLeftExtentNoZero

    /** Collect y-extent for marks on `Axis.Right`. */
    private def yRightExtent[A](rows: Chunk[A], marks: Chunk[Mark[A]]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= marks.size then acc
            else
                val markExtent: Maybe[Extent] = marks(i) match
                    case m: Mark.Bar[A, ?, ?] if m.axis == Axis.Right =>
                        ensureZero(foldExtent(rows, r => m.y.plottable.toDomain(m.y.accessor(r.asInstanceOf[A]))))
                    case m: Mark.Line[A, ?, ?] if m.axis == Axis.Right =>
                        foldExtent(rows, r => m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.Area[A, ?, ?] if m.axis == Axis.Right =>
                        val base = m.y match
                            case Present(ch) => foldExtent(rows, r => ch.accessor(r.asInstanceOf[A]).flatMap(v => ch.plottable.toDomain(v)))
                            case Absent      => Absent
                        ensureZero(base)
                    case m: Mark.Point[A, ?, ?] if m.axis == Axis.Right =>
                        foldExtent(rows, r => m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.Text[A, ?, ?] if m.axis == Axis.Right =>
                        // INV-021: text y contributes to right-axis extent when on the right axis.
                        foldExtent(rows, r => m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.ErrorBar[A, ?, ?] if m.axis == Axis.Right =>
                        // INV-022: low/high/y contribute to right-axis extent.
                        val eY    = foldExtent(rows, r => m.y.plottable.toDomain(m.y.accessor(r.asInstanceOf[A])))
                        val eLow  = foldExtent(rows, r => m.low.plottable.toDomain(m.low.accessor(r.asInstanceOf[A])))
                        val eHigh = foldExtent(rows, r => m.high.plottable.toDomain(m.high.accessor(r.asInstanceOf[A])))
                        ensureZero(mergeExtents(mergeExtents(eY, eLow), eHigh))
                    case _ => Absent
                val merged = mergeExtents(acc, markExtent)
                loop(i + 1, merged)
        loop(0, Absent)
    end yRightExtent

    /** Compute the stacked y-extent for a Bar mark with a stack grouping.
      *
      * For each distinct x value, separately tracks the sum of positive contributions
      * (posSum) and the sum of negative contributions (negSum). Returns
      * `Extent.Continuous(minNegSum, maxPosSum)` so that negative stacks extend the
      * axis below zero. `ensureZero` in `yLeftExtent` ensures zero is always in-domain.
      */
    private def stackedYExtent[A, X, Y](rows: Chunk[A], mark: Mark.Bar[A, X, Y]): Maybe[Extent] =
        // Build xKey -> (posSum, negSum) across all groups at that x slot.
        @scala.annotation.tailrec
        def loop(i: Int, sums: Map[String, (Double, Double)]): Map[String, (Double, Double)] =
            if i >= rows.size then sums
            else
                val row = rows(i)
                val xKey = mark.x.plottable.toDomain(mark.x.accessor(row)) match
                    case Present(d) => domainKey(d)
                    case Absent     => ""
                val yVal = mark.y.plottable.toDomain(mark.y.accessor(row)) match
                    case Present(Domain.Continuous(v)) => v
                    case _                             => 0.0
                val (pos, neg) = sums.getOrElse(xKey, (0.0, 0.0))
                val newPair    = if yVal >= 0.0 then (pos + yVal, neg) else (pos, neg + yVal)
                loop(i + 1, sums.updated(xKey, newPair))
        val sums = loop(0, Map.empty)
        if sums.isEmpty then Absent
        else
            val maxPosSum = sums.values.map(_._1).fold(0.0)(math.max)
            val minNegSum = sums.values.map(_._2).fold(0.0)(math.min)
            Present(Extent.Continuous(minNegSum, maxPosSum))
        end if
    end stackedYExtent

    /** Compute the stacked y-extent for an Area mark with a stack grouping.
      *
      * For each distinct x value, sums the y contributions of all stack groups and returns the maximum sum as
      * the extent upper bound (lower bound is zero after `ensureZero`). Mirrors `stackedYExtent` for bars.
      */
    private def stackedAreaYExtent[A, X, Y](rows: Chunk[A], mark: Mark.Area[A, X, Y], yCh: ChannelMaybe[A, Y]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, totals: Map[String, Double]): Map[String, Double] =
            if i >= rows.size then totals
            else
                val row = rows(i)
                val xKey = mark.x.plottable.toDomain(mark.x.accessor(row)) match
                    case Present(d) => domainKey(d)
                    case Absent     => ""
                val yVal = yCh.accessor(row) match
                    case Present(yv) => yCh.plottable.toDomain(yv) match
                            case Present(Domain.Continuous(v)) => v
                            case _                             => 0.0
                    case Absent => 0.0
                loop(i + 1, totals.updated(xKey, totals.getOrElse(xKey, 0.0) + yVal))
        val totals = loop(0, Map.empty)
        if totals.isEmpty then Absent
        else
            val maxTotal = totals.values.fold(0.0)(math.max)
            Present(Extent.Continuous(0.0, maxTotal))
        end if
    end stackedAreaYExtent

    private def domainKey(d: Domain): String = d match
        case Domain.Continuous(v) => NumberFormat.double(v)
        case Domain.Category(key) => key
        case Domain.Temporal(ms)  => ms.toString

    private def extentFromDomain(d: Domain): Extent = d match
        case Domain.Continuous(v) => Extent.Continuous(v, v)
        case Domain.Category(key) => Extent.Categories(Chunk(key))
        case Domain.Temporal(ms)  => Extent.Continuous(ms.toDouble, ms.toDouble)

    private def ensureZero(e: Maybe[Extent]): Maybe[Extent] = e match
        case Present(Extent.Continuous(lo, hi)) => Present(Extent.Continuous(math.min(lo, 0.0), hi))
        case other                              => other

    private def mergeExtents(a: Maybe[Extent], b: Maybe[Extent]): Maybe[Extent] = (a, b) match
        case (Absent, x) => x
        case (x, Absent) => x
        case (Present(ea), Present(eb)) =>
            (ea, eb) match
                case (Extent.Continuous(lo1, hi1), Extent.Continuous(lo2, hi2)) =>
                    Present(Extent.Continuous(math.min(lo1, lo2), math.max(hi1, hi2)))
                case (Extent.Categories(k1), Extent.Categories(k2)) =>
                    // INV-006: Chunk-native exists, no toSeq round-trip
                    val merged = k2.foldLeft(k1)((acc, k) => if acc.exists(_ == k) then acc else acc.append(k))
                    Present(Extent.Categories(merged))
                case (Extent.Continuous(lo, hi), Extent.Categories(_)) => Present(Extent.Continuous(lo, hi))
                case (Extent.Categories(_), Extent.Continuous(lo, hi)) => Present(Extent.Continuous(lo, hi))
        case _ => Absent

    // ---- scale resolution ----

    /** Holds all three resolved scales from a single combined pass (catalog #34, INV-004). */
    private case class ResolvedScales(xs: Scale, ysL: Scale, ysR: Maybe[Scale])

    /** Resolves all three scales in one combined pass (catalog #34, INV-004).
      *
      * Preserves every per-mark/per-axis branch exactly (stacked vs simple, ensureZero,
      * log-no-zero path, right-axis vs left-axis). Output is byte-identical to the prior
      * three-fold path for any chart configuration.
      *
      * `computeRight`: when `false` the right scale is `Absent` without computing `yRightExtent`.
      */
    private def resolveAllScales[A](
        rows: Chunk[A],
        marks: Chunk[Mark[A]],
        layout: Layout,
        xOverride: Maybe[ScaleOverride],
        yOverride: Maybe[ScaleOverride],
        computeRight: Boolean,
        xAxisCfg: AxisConfig = AxisConfig.default,
        yAxisCfg: AxisConfig = AxisConfig.default
    ): ResolvedScales =

        // Compute effective pad (INV-007, Q-003): ScaleOverride wins over AxisConfig.
        def effectivePad(ov: Maybe[ScaleOverride], axisCfg: AxisConfig): Double =
            ov.flatMap(o => if o.pad != 0.0 then Present(o.pad) else Absent).getOrElse(axisCfg.padding)

        // Apply symmetric fractional padding to a continuous extent (G5).
        def padExtent(ext: Extent, pad: Double): Extent = ext match
            case Extent.Continuous(lo, hi) if pad != 0.0 =>
                val delta = pad * (hi - lo)
                Extent.Continuous(lo - delta, hi + delta)
            case other => other

        // X scale
        val xExt     = xExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
        val xPad     = effectivePad(xOverride, xAxisCfg)
        val xNice    = xOverride.map(_.nice).getOrElse(true)
        val xReverse = xAxisCfg.reversed

        val xKindOpt: Maybe[Scale.Kind] = xOverride.flatMap(_.kind) match
            case Present(ScaleKind.Band)         => Present(Scale.Kind.Band)
            case Present(ScaleKind.Log)          => Present(Scale.Kind.Log)
            case Present(ScaleKind.Linear(_, _)) => Present(Scale.Kind.Linear)
            case Present(ScaleKind.Time)         => Present(Scale.Kind.Time)
            case Present(ScaleKind.Ordinal)      => Present(Scale.Kind.Ordinal)
            case Present(ScaleKind.Point)        => Present(Scale.Kind.Point)
            case Present(ScaleKind.Symlog)       => Present(Scale.Kind.Symlog)
            case _                               => Absent
        val xKind = xKindOpt.getOrElse(inferKind(xExt, marks, isX = true))
        val (xExtFinal, xLoRaw, xHiRaw) = xOverride.flatMap(_.kind) match
            case Present(ScaleKind.Linear(domLo, domHi)) => (Extent.Continuous(domLo, domHi), layout.plotX, layout.plotX + layout.plotW)
            case _                                       => (padExtent(xExt, xPad), layout.plotX, layout.plotX + layout.plotW)
        // Swap range bounds when reverse=true so the first datum appears at the far end (D20).
        val (xLo, xHi) = if xReverse then (xHiRaw, xLoRaw) else (xLoRaw, xHiRaw)
        val xs         = Scale.fit(xKind, xExtFinal, xLo, xHi, nice = xNice)

        // Y left scale
        val yExt     = yLeftExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
        val yPad     = effectivePad(yOverride, yAxisCfg)
        val yNice    = yOverride.map(_.nice).getOrElse(true)
        val yReverse = yAxisCfg.reversed

        val yKindOpt: Maybe[Scale.Kind] = yOverride.flatMap(_.kind) match
            case Present(ScaleKind.Band)         => Present(Scale.Kind.Band)
            case Present(ScaleKind.Log)          => Present(Scale.Kind.Log)
            case Present(ScaleKind.Linear(_, _)) => Present(Scale.Kind.Linear)
            case Present(ScaleKind.Time)         => Present(Scale.Kind.Time)
            case Present(ScaleKind.Ordinal)      => Present(Scale.Kind.Ordinal)
            case Present(ScaleKind.Point)        => Present(Scale.Kind.Point)
            case Present(ScaleKind.Symlog)       => Present(Scale.Kind.Symlog)
            case _                               => Absent
        val yKind    = yKindOpt.getOrElse(Scale.Kind.Linear)
        val baseline = layout.plotBaseline
        val top      = layout.plotY
        // Swap range bounds when reverse=true (D20).
        val (yRLoBase, yRHiBase) = if yReverse then (top, baseline) else (baseline, top)
        val (yExtFinal, yRLo, yRHi, useNice) = yOverride.flatMap(_.kind) match
            case Present(ScaleKind.Linear(domLo, domHi)) => (Extent.Continuous(domLo, domHi), yRLoBase, yRHiBase, false)
            // G7: log scale uses the no-zero extent computation
            case Present(ScaleKind.Log) =>
                val rawExt = yLeftExtentNoZero(rows, marks).getOrElse(Extent.Continuous(1.0, 10.0))
                (rawExt, yRLoBase, yRHiBase, false)
            case _ => (padExtent(yExt, yPad), yRLoBase, yRHiBase, yNice)
        val ysL = Scale.fit(yKind, yExtFinal, yRLo, yRHi, nice = useNice)

        // Y right scale (optional)
        val ysR: Maybe[Scale] =
            if computeRight then
                val rExt = yRightExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
                Present(Scale.fit(Scale.Kind.Linear, rExt, layout.plotBaseline, layout.plotY, nice = true))
            else Absent

        ResolvedScales(xs, ysL, ysR)
    end resolveAllScales

    private def inferKind[A](ext: Extent, marks: Chunk[Mark[A]], isX: Boolean): Scale.Kind =
        ext match
            case Extent.Categories(_)    => Scale.Kind.Band
            case Extent.Continuous(_, _) => Scale.Kind.Linear

    // ---- static frame chrome ----

    /** Build the static frame elements: background rect, axis lines, gridlines, tick marks, tick labels,
      * axis labels, and the color legend.
      *
      * Returns a flat `Chunk` of `Svg.SvgElement`s that are prepended to the root before the marks group.
      * All shapes are plain rects/lines/text; no `url(#id)` references are emitted (N4-guard satisfied).
      */
    private[kyo] def buildFrame[A](
        layout: Layout,
        xs: Scale,
        ysL: Scale,
        ysR: Maybe[Scale],
        spec: ChartSpec[A],
        rows: Chunk[A],
        gradPrefix: String
    )(using Frame): Chunk[Svg.SvgElement] =
        // Per-axis chrome color: when exactly one mark binds to an axis, its chrome matches that mark's
        // palette color so a reader can tell which axis a series uses; otherwise the neutral theme color.
        val leftChrome  = axisChromeColorFor(spec.theme, spec.marks, Axis.Left)
        val rightChrome = axisChromeColorFor(spec.theme, spec.marks, Axis.Right)
        val gridColor   = gridlineColor(spec.theme)
        val background  = buildBackground(layout, spec.theme)
        val axisLines   = buildAxisLines(layout, ysR, spec.theme, leftChrome, rightChrome)
        val leftAxis    = buildYAxis(layout, ysL, spec.yAxisCfg, isRight = false, spec.theme, leftChrome, gridColor)
        val rightAxis = ysR match
            case Present(ysR_) =>
                buildYAxis(
                    layout,
                    ysR_,
                    spec.yAxisRightCfg.getOrElse(AxisConfig.default),
                    isRight = true,
                    spec.theme,
                    rightChrome,
                    gridColor
                )
            case Absent => Chunk.empty
        val xAxisElems  = buildXAxis(layout, xs, spec.xAxisCfg, spec.theme)
        val legendElems = buildLegend(layout, spec, rows, gradPrefix)
        background ++ axisLines ++ leftAxis ++ rightAxis ++ xAxisElems ++ legendElems
    end buildFrame

    /** Background rectangle filled with the theme color.
      *
      * Covers the WHOLE SVG canvas (not only the plot rectangle) so that on the dark theme the entire chart,
      * including the axis margins where tick numbers and axis labels live, reads as dark. The light theme uses
      * white, matching the page background.
      */
    private def buildBackground(layout: Layout, theme: Theme)(using Frame): Chunk[Svg.SvgElement] =
        val color = if theme.isDark then DarkBg else LightBg
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
    private def buildAxisLines(
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
    private def buildYAxis(
        layout: Layout,
        ys: Scale,
        cfg: AxisConfig,
        isRight: Boolean,
        theme: Theme,
        chrome: Style.Color,
        gridColor: Style.Color
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
                // gridline: always the neutral grid color, never the (possibly color-coded) axis chrome
                val grid: Maybe[Svg.SvgElement] =
                    if cfg.showGrid && !isRight then
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
                val tickLabel: Svg.SvgElement =
                    Svg.text
                        .x(labelX)
                        .y(py)
                        .textAnchor(anchor)
                        .dominantBaseline(Svg.DominantBaseline.Middle)
                        .fill(Svg.Paint.Color(chrome))
                        .apply(labelStr)
                val elements = grid match
                    case Present(g) => Chunk[Svg.SvgElement](g, tickMark, tickLabel)
                    case Absent     => Chunk[Svg.SvgElement](tickMark, tickLabel)
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
                        Svg.text
                            .x(cx)
                            .y(midY)
                            .textAnchor(Svg.TextAnchor.Middle)
                            .dominantBaseline(Svg.DominantBaseline.Auto)
                            .fill(Svg.Paint.Color(chrome))
                            .transform(Svg.Transform.Rotate(90.0, Present(cx), Present(midY)))
                            .apply(lbl)
                    else
                        val cx = AxisLabelInset
                        Svg.text
                            .x(cx)
                            .y(midY)
                            .textAnchor(Svg.TextAnchor.Middle)
                            .dominantBaseline(Svg.DominantBaseline.Auto)
                            .fill(Svg.Paint.Color(chrome))
                            .transform(Svg.Transform.Rotate(-90.0, Present(cx), Present(midY)))
                            .apply(lbl)
                base.append(labelElem)
            case Absent => base
        end match
    end buildYAxis

    /** Build the x-axis: tick marks and tick labels along the bottom. */
    private def buildXAxis(
        layout: Layout,
        xs: Scale,
        cfg: AxisConfig,
        theme: Theme
    )(using Frame): Chunk[Svg.SvgElement] =
        val ticks  = xs.ticks(cfg.tickCount)
        val axisY  = layout.plotBaseline
        val chrome = axisChromeColor(theme)

        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= ticks.size then acc
            else
                val tick = ticks(i)
                val px   = tick.pixel
                val tickMark: Svg.SvgElement =
                    Svg.line
                        .x1(px).y1(axisY)
                        .x2(px).y2(axisY + TickLen)
                        .stroke(Svg.Paint.Color(chrome))
                // tick label: apply the formatter to the domain value when present
                val xLabelStr = cfg.tickFormat match
                    case Present(f) => f(tick.value)
                    case Absent     => tick.label
                val tickLabel: Svg.SvgElement =
                    Svg.text
                        .x(px)
                        .y(axisY + TickLen + 4.0)
                        .textAnchor(Svg.TextAnchor.Middle)
                        .dominantBaseline(Svg.DominantBaseline.Hanging)
                        .fill(Svg.Paint.Color(chrome))
                        .apply(xLabelStr)
                loop(i + 1, acc.append(tickMark).append(tickLabel))
        val base = loop(0, Chunk.empty)
        // axis label
        cfg.axisLabel match
            case Present(lbl) =>
                val labelElem: Svg.SvgElement =
                    Svg.text
                        .x(layout.plotX + layout.plotW / 2.0)
                        .y(layout.svgH - 4.0)
                        .textAnchor(Svg.TextAnchor.Middle)
                        .fill(Svg.Paint.Color(chrome))
                        .apply(lbl)
                base.append(labelElem)
            case Absent => base
        end match
    end buildXAxis

    /** Build the legend `Svg.g` elements for any color channel present in the marks.
      *
      * Categories are collected in enum-ordinal order when the color values are enums (N3 carry-over:
      * real `Plottable` ordering, not encounter order). The palette comes from `legendCfg.colorScaleFn`
      * when set; otherwise from `theme.palette` or the `DefaultPalette`.
      *
      * When no mark has a `color` channel but a Bar or Area mark carries a `stack` grouping, the legend is
      * derived from the STACK groups instead: one swatch+label per stack category, in the same colors the
      * stacked segments use. This is the same derivation as for a `color` channel, applied to the grouping
      * accessor that colors the stacked segments.
      */
    private def buildLegend[A](
        layout: Layout,
        spec: ChartSpec[A],
        rows: Chunk[A],
        gradPrefix: String
    )(using Frame): Chunk[Svg.SvgElement] =
        if spec.legendCfg.isHidden then Chunk.empty
        else
            // Prefer a color channel; fall back to the stack grouping that colors the stacked segments.
            val colorItems: Chunk[Svg.SvgElement] = legendChannel(spec.marks) match
                case Absent           => Chunk.empty
                case Present(colorCh) =>
                    // categories: Chunk[(label, rawValue)] sorted by enum ordinal when applicable
                    val categories = collectColorCategoriesWithRaw(rows, colorCh)
                    if categories.isEmpty then Chunk.empty
                    else
                        spec.legendCfg.colorScale match
                            case Present(LegendConfig.ColorScale.Sequential(lo, hi, _)) =>
                                // Sequential legend: a single continuous gradient swatch under a doc-unique def id.
                                buildSequentialLegend(layout, spec, lo, hi, gradPrefix)
                            case _ =>
                                val palette = resolvePalette(spec, categories)
                                buildLegendItems(layout, categories, palette, spec.legendCfg, spec.marks, axisChromeColor(spec.theme))
                    end if

            // Size legend: emitted when any Point mark has a size channel (INV-015, test 8).
            val sizeItems: Chunk[Svg.SvgElement] = spec.marks.flatMap:
                case m: Mark.Point[A, ?, ?] =>
                    m.size match
                        case Present(fn) =>
                            buildSizeLegend(layout, rows, m, axisChromeColor(spec.theme), colorItems.size)
                        case Absent => Chunk.empty
                case _ => Chunk.empty

            colorItems ++ sizeItems
        end if
    end buildLegend

    /** Build a continuous gradient swatch for a sequential color scale (INV-028).
      *
      * Emits an `Svg.defs` carrying one `linearGradient` (low at offset 0, high at offset 1) under a
      * document-unique def id, plus a swatch `Svg.rect` filled with that gradient via `url(#id)`. The id comes
      * from `gradPrefix` (allocated once per chart instance), so two charts on one page never alias the same
      * gradient even when their specs are structurally identical: the def id and the swatch's `url(#id)` always
      * match within one chart, and differ across charts.
      */
    private def buildSequentialLegend[A](
        layout: Layout,
        spec: ChartSpec[A],
        lo: Style.Color,
        hi: Style.Color,
        gradPrefix: String
    )(using Frame): Chunk[Svg.SvgElement] =
        val gradId = s"$gradPrefix-grad-0"
        val gradient = Svg.linearGradient
            .withSvg(Svg.linearGradient.svgAttrs.copy(defId = Present(gradId)))
            .x1(0).y1(0).x2(1).y2(0)
            .apply(
                Svg.stop.offset(0.0).stopColor(lo),
                Svg.stop.offset(1.0).stopColor(hi)
            )
        val (legendX, legendY) = spec.legendCfg.position.getOrElse(LegendPosition.Top) match
            case LegendPosition.Top    => (layout.plotX, MarginTop + (LegendReservedH - SwatchSize) / 2.0)
            case LegendPosition.Bottom => (layout.plotX, layout.plotBaseline + (LegendReservedH - SwatchSize) / 2.0)
            case LegendPosition.Left   => (MarginLeft / 2.0 - SwatchSize, layout.plotY)
            case LegendPosition.Right  => (layout.plotX + layout.plotW + 8.0, layout.plotY)
        val swatch: Svg.SvgElement =
            Svg.rect
                .x(legendX)
                .y(legendY)
                .width(SwatchSize * 4.0)
                .height(SwatchSize)
                .fill(gradient.paint)
        Chunk(Svg.defs(gradient), swatch)
    end buildSequentialLegend

    /** Build representative size-bubble legend items for a point mark with a size channel.
      *
      * Emits two representative circles (min and max magnitude) with their magnitude labels,
      * placed in the legend region to the right of any color-legend items.
      */
    private def buildSizeLegend[A, X, Y](
        layout: Layout,
        rows: Chunk[A],
        mark: Mark.Point[A, X, Y],
        labelColor: Style.Color,
        colorItemCount: Int
    )(using Frame): Chunk[Svg.SvgElement] =
        mark.size match
            case Absent      => Chunk.empty
            case Present(fn) =>
                // Fold magnitude extent over all rows with defined y.
                @scala.annotation.tailrec
                def foldMag(i: Int, mn: Double, mx: Double): (Double, Double) =
                    if i >= rows.size then (mn, mx)
                    else
                        val row = rows(i)
                        if mark.y.accessor(row).isDefined then
                            val mag = fn(row)
                            foldMag(i + 1, math.min(mn, mag), math.max(mx, mag))
                        else foldMag(i + 1, mn, mx)
                        end if
                val (magMin, magMax) = foldMag(0, Double.MaxValue, Double.MinValue)
                if magMin == Double.MaxValue then Chunk.empty
                else
                    val scale = SizeScale(magMin, magMax, SizeScale.DefaultRMin, SizeScale.DefaultRMax)
                    val rMin  = scale.radius(magMin)
                    val rMax  = scale.radius(magMax)
                    // Place size bubbles in the legend region after color swatches.
                    val startX  = layout.plotX + colorItemCount.toDouble * 80.0
                    val legendY = MarginTop + LegendReservedH / 2.0
                    val minCirc = Svg.circle
                        .cx(startX + rMin)
                        .cy(legendY)
                        .r(rMin)
                        .fill(Svg.Paint.Color(DefaultPalette(0)))
                        .fillOpacity(0.5)
                    val minLabel = Svg.text
                        .x(startX + rMin * 2.0 + 4.0)
                        .y(legendY)
                        .dominantBaseline(Svg.DominantBaseline.Middle)
                        .fill(Svg.Paint.Color(labelColor))
                        .apply(NumberFormat.double(magMin))
                    val maxCirc = Svg.circle
                        .cx(startX + rMin * 2.0 + 50.0 + rMax)
                        .cy(legendY)
                        .r(rMax)
                        .fill(Svg.Paint.Color(DefaultPalette(0)))
                        .fillOpacity(0.5)
                    val maxLabel = Svg.text
                        .x(startX + rMin * 2.0 + 50.0 + rMax * 2.0 + 4.0)
                        .y(legendY)
                        .dominantBaseline(Svg.DominantBaseline.Middle)
                        .fill(Svg.Paint.Color(labelColor))
                        .apply(NumberFormat.double(magMax))
                    Chunk(minCirc, minLabel, maxCirc, maxLabel)
                end if
    end buildSizeLegend

    /** The channel that drives the legend: the first `color` channel, or, when none is present, the first
      * Bar/Area `stack` grouping (which colors the stacked segments).
      */
    private def legendChannel[A](marks: Chunk[Mark[A]]): Maybe[Channel[A, ?]] =
        findColorChannel(marks).orElse(findStackGroup(marks))

    /** Find the first color channel across all marks. */
    private def findColorChannel[A](marks: Chunk[Mark[A]]): Maybe[Channel[A, ?]] =
        @scala.annotation.tailrec
        def loop(i: Int): Maybe[Channel[A, ?]] =
            if i >= marks.size then Absent
            else
                marks(i) match
                    case m: Mark.Bar[A, ?, ?]      => m.color.orElse(loop(i + 1))
                    case m: Mark.Line[A, ?, ?]     => m.color.orElse(loop(i + 1))
                    case m: Mark.Area[A, ?, ?]     => m.color.orElse(loop(i + 1))
                    case m: Mark.Point[A, ?, ?]    => m.color.orElse(loop(i + 1))
                    case m: Mark.Text[A, ?, ?]     => m.color.orElse(loop(i + 1))
                    case m: Mark.ErrorBar[A, ?, ?] => m.color.orElse(loop(i + 1))
                    case _: Mark.Rule[A]           => loop(i + 1)
        loop(0)
    end findColorChannel

    /** Find the first Bar/Area `stack` grouping accessor, wrapped as a string-keyed `Channel`.
      *
      * The wrapped channel mirrors how `lowerBarStacked` colors stacked segments: the grouping accessor
      * keyed as a string category. Deriving the legend from this channel produces one swatch per stack
      * category in the same colors the segments use.
      */
    private def findStackGroup[A](marks: Chunk[Mark[A]]): Maybe[Channel[A, ?]] =
        @scala.annotation.tailrec
        def loop(i: Int): Maybe[Channel[A, ?]] =
            if i >= marks.size then Absent
            else
                val groupMaybe = marks(i) match
                    case m: Mark.Bar[A, ?, ?]  => m.stack.group
                    case m: Mark.Area[A, ?, ?] => m.stack.group
                    case _                     => Absent
                groupMaybe match
                    case Present(g) => Present(Channel[A, Any](g, Plottable.string.asInstanceOf[Plottable[Any]]))
                    case Absent     => loop(i + 1)
        loop(0)
    end findStackGroup

    /** Collect distinct color category labels in enum-ordinal order when possible, encounter order otherwise.
      *
      * For enum values (detected via `scala.reflect.Enum`), the first encountered value's ordinal is used as
      * a sort key so that the legend lists cases in enum declaration order rather than encounter order. This
      * satisfies the N3 carry-over: "order via the real color accessor's Plottable, not the string stand-in".
      *
      * Returns `Chunk[(label, rawValue)]` where `rawValue` is the first encountered raw color-channel value
      * for that label. The raw value is passed to `colorScaleFn` so typed enum functions can be applied
      * directly (no label-to-K roundtrip).
      */
    private def collectColorCategoriesWithRaw[A](rows: Chunk[A], colorCh: Channel[A, ?]): Chunk[(String, Any)] =
        // Dedup by CatKey (INV-002, WARN-2): delegate to ChartFoundations.distinctKeyed (O(n), Set-backed)
        // instead of inlining a fresh HashSet. distinctKeyed returns first-seen (CatKey, row) in encounter
        // order; derive (label, rawValue, ordinal) from each representative row.
        val distinct = ChartFoundations.distinctKeyed(rows, r => ChartFoundations.categoryKey(colorCh.accessor(r)))
        val triples = distinct.zipWithIndex.map: (pair, encounterIdx) =>
            val raw   = colorCh.accessor(pair._2)
            val label = raw.toString
            val ordinal = raw match
                case e: scala.reflect.Enum => e.ordinal
                case _                     => encounterIdx // stable encounter-order index
            (label, raw, ordinal)
        // Sort by ordinal so enum cases appear in declaration order.
        // toSeq here is acceptable: it is a terminal operation on a small collection, not a hot membership scan.
        triples.toSeq.sortBy(_._3).foldLeft(Chunk.empty[(String, Any)])((acc, t) => acc.append((t._1, t._2)))
    end collectColorCategoriesWithRaw

    /** Collect category labels (without raw values) for use in stacked-bar lowering. */
    private def collectColorCategories[A](rows: Chunk[A], colorCh: Channel[A, ?]): Chunk[String] =
        collectColorCategoriesWithRaw(rows, colorCh).map(_._1)

    /** Resolve the palette: explicit `colorScale` first (applied to raw values), then `theme.palette`,
      * then `DefaultPalette`.
      *
      * A `Categorical` scale applies its total function to each category's RAW color-channel value (e.g. the
      * actual enum case), not the label string, so typed pairs/functions work without a label-to-K roundtrip.
      * A `Sequential` scale derives the numeric domain extent from the categories (or its `domain` override)
      * and interpolates each raw value's color between `low` and `high`.
      */
    private def resolvePalette[A](spec: ChartSpec[A], categories: Chunk[(String, Any)]): Chunk[Style.Color] =
        spec.legendCfg.colorScale match
            case Present(LegendConfig.ColorScale.Categorical(fn)) =>
                categories.map { case (_, raw) => fn(raw) }
            case Present(LegendConfig.ColorScale.Sequential(lo, hi, domOv)) =>
                val (domLo, domHi) = domainExtentOf(categories, domOv)
                categories.map { case (_, raw) => sequentialColor(raw, lo, hi, domLo, domHi) }
            case Absent =>
                spec.theme.palette match
                    case Present(p) => categories.zipWithIndex.map: (_, i) =>
                            p.toSeq.apply(i % p.size)
                    case Absent => categories.zipWithIndex.map: (_, i) =>
                            DefaultPalette.toSeq.apply(i % DefaultPalette.size)
    end resolvePalette

    /** Numeric extent over the raw category values, used to normalize a sequential color scale.
      *
      * When `domainOv` is `Present`, it is used directly. Otherwise the extent folds the numeric raw values
      * (Double/Int/Long/Float); non-numeric values are skipped. When no value is numeric the fallback `(0, 1)`
      * keeps the scale well-defined.
      */
    private def domainExtentOf(categories: Chunk[(String, Any)], domainOv: Maybe[(Double, Double)]) =
        domainOv match
            case Present(d) => d
            case Absent =>
                @scala.annotation.tailrec
                def fold(i: Int, lo: Double, hi: Double): (Double, Double) =
                    if i >= categories.size then (lo, hi)
                    else
                        val raw = categories(i)._2
                        val v = raw match
                            case n: Double => n
                            case n: Int    => n.toDouble
                            case n: Long   => n.toDouble
                            case n: Float  => n.toDouble
                            case _         => Double.NaN
                        if java.lang.Double.isFinite(v) then fold(i + 1, math.min(lo, v), math.max(hi, v))
                        else fold(i + 1, lo, hi)
                fold(0, Double.MaxValue, Double.MinValue) match
                    case (lo, _) if lo == Double.MaxValue => (0.0, 1.0)
                    case result                           => result
    end domainExtentOf

    /** Interpolate `lo`..`hi` at the parameter derived from `raw`'s position in the domain `[domLo, domHi]`.
      *
      * The parameter is clamped to `[0, 1]`; a degenerate domain (`domHi <= domLo`) yields the `lo` color
      * (parameter 0) with no division by zero. A non-numeric raw value maps to `domLo` (parameter 0).
      */
    private def sequentialColor(raw: Any, lo: Style.Color, hi: Style.Color, domLo: Double, domHi: Double) =
        val v: Double = raw match
            case n: Double => n
            case n: Int    => n.toDouble
            case n: Long   => n.toDouble
            case n: Float  => n.toDouble
            case _         => domLo
        val t = if domHi <= domLo then 0.0 else math.max(0.0, math.min(1.0, (v - domLo) / (domHi - domLo)))
        lerpColor(lo, hi, t)
    end sequentialColor

    /** Linear per-channel RGB interpolation between two colors at parameter `t` in `[0, 1]`.
      *
      * RGB components are extracted from the color ADT directly (hex strings of 3/4/6/8 digits, rgb, rgba), so
      * named constants and `Style.Color.hex(...)` inputs all interpolate correctly. The result is an rgb color.
      */
    private def lerpColor(lo: Style.Color, hi: Style.Color, t: Double) =
        val (r0, g0, b0) = colorComponents(lo)
        val (r1, g1, b1) = colorComponents(hi)
        val r            = math.round(r0 + (r1 - r0) * t).toInt
        val g            = math.round(g0 + (g1 - g0) * t).toInt
        val b            = math.round(b0 + (b1 - b0) * t).toInt
        Style.Color.rgb(r, g, b)
    end lerpColor

    /** Extract the 8-bit R/G/B components of a color, normalizing hex (3/4/6/8 digit) and rgb/rgba forms.
      *
      * Falls back to mid-gray for `transparent` or an unparseable hex; in practice every named constant and
      * `Style.Color.hex`/`rgb` input parses, so the fallback is a safety net, not a normal path.
      */
    private def colorComponents(c: Style.Color) =
        def parseHex(value: String): (Int, Int, Int) =
            val body = if value.startsWith("#") then value.substring(1) else value
            // Expand 3/4-digit shorthand (#rgb / #rgba) to 6 digits by doubling each nibble.
            val rgb =
                if body.length == 3 || body.length == 4 then
                    val r = body.charAt(0); val g = body.charAt(1); val b = body.charAt(2)
                    s"$r$r$g$g$b$b"
                else body.substring(0, math.min(6, body.length))
            if rgb.length == 6 then
                (
                    Integer.parseInt(rgb.substring(0, 2), 16),
                    Integer.parseInt(rgb.substring(2, 4), 16),
                    Integer.parseInt(rgb.substring(4, 6), 16)
                )
            else (128, 128, 128)
            end if
        end parseHex
        c match
            case Style.Color.Hex(value)       => parseHex(value)
            case Style.Color.Rgb(r, g, b)     => (r, g, b)
            case Style.Color.Rgba(r, g, b, _) => (r, g, b)
            case Style.Color.Transparent      => (128, 128, 128)
        end match
    end colorComponents

    /** True when the legend-driving mark (the one that carries the color channel) is a line or area mark.
      *
      * Line and area series get a short line-stroke swatch in the legend (matching how the data reads as a
      * stroke); bar and point series get the filled-rect swatch. When no color-bearing mark is found the
      * default is the rect swatch.
      */
    private def legendUsesLineSwatch[A](marks: Chunk[Mark[A]]): Boolean =
        @scala.annotation.tailrec
        def loop(i: Int): Boolean =
            if i >= marks.size then false
            else
                marks(i) match
                    case m: Mark.Line[A, ?, ?] => m.color.isDefined || loop(i + 1)
                    case m: Mark.Area[A, ?, ?] => m.color.isDefined || loop(i + 1)
                    case _                     => loop(i + 1)
        loop(0)
    end legendUsesLineSwatch

    /** Build the legend swatch+label elements, positioned per `cfg.position`.
      *
      * `categories` is `Chunk[(label, rawValue)]` in enum-ordinal order; `palette` the resolved color per
      * category. Top and Bottom lay the entries horizontally; Left and Right stack them vertically. Line/area
      * series get a short line-stroke swatch, bar/point series a filled rect swatch. When `cfg.isInteractive`,
      * each swatch+label pair carries an `onClick` that toggles its label in `cfg.hiddenSeries`.
      *
      * `labelColor` is the theme chrome color (the same `axisChromeColor` the axis tick labels use) so labels
      * stay readable against the panel background; the swatch fills keep the category/colorScale colors.
      */
    private def buildLegendItems[A](
        layout: Layout,
        categories: Chunk[(String, Any)],
        palette: Chunk[Style.Color],
        cfg: LegendConfig,
        marks: Chunk[Mark[A]],
        labelColor: Style.Color
    )(using Frame): Chunk[Svg.SvgElement] =
        // Origin and flow direction per legend position. `vertical` stacks items down a column (Left/Right);
        // otherwise items flow horizontally across the reserved band (Top/Bottom).
        val (legendX, legendY, vertical) = cfg.position.getOrElse(LegendPosition.Top) match
            case LegendPosition.Top    => (layout.plotX, MarginTop + (LegendReservedH - SwatchSize) / 2.0, false)
            case LegendPosition.Bottom => (layout.plotX, layout.plotBaseline + (LegendReservedH - SwatchSize) / 2.0, false)
            case LegendPosition.Left   => (MarginLeft / 2.0 - SwatchSize, layout.plotY, true)
            case LegendPosition.Right  => (layout.plotX + layout.plotW + 8.0, layout.plotY, true)

        val useLineSwatch = legendUsesLineSwatch(marks)
        val itemGap       = 16.0

        // Click action toggling one series label in the user's hiddenSeries ref (INV-026).
        def toggleAction(label: String): Maybe[Any < Async] =
            if cfg.isInteractive then
                cfg.hiddenSeries.map(ref => ref.updateAndGet(s => if s.contains(label) then s - label else s + label))
            else Absent

        @scala.annotation.tailrec
        def loop(i: Int, curX: Double, curY: Double, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= categories.size then acc
            else
                val (cat, _) = categories(i)
                val color    = if i < palette.size then palette(i) else DefaultPalette.toSeq.apply(i % DefaultPalette.size)
                val clickAttrs = toggleAction(cat) match
                    case Present(a) => UI.Ast.Attrs(onClick = Present(a))
                    case Absent     => UI.Ast.Attrs()
                val swatch: Svg.SvgElement =
                    if useLineSwatch then
                        // Short horizontal stroke at the swatch's vertical centre.
                        Svg.line
                            .x1(curX)
                            .y1(curY + SwatchSize / 2.0)
                            .x2(curX + SwatchSize)
                            .y2(curY + SwatchSize / 2.0)
                            .stroke(Svg.Paint.Color(color))
                            .strokeWidth(2.0)
                            .withAttrs(clickAttrs)
                    else
                        Svg.rect
                            .x(curX)
                            .y(curY)
                            .width(SwatchSize)
                            .height(SwatchSize)
                            .fill(Svg.Paint.Color(color))
                            .withAttrs(clickAttrs)
                val label: Svg.SvgElement =
                    Svg.text
                        .x(curX + SwatchSize + SwatchLabelGap)
                        .y(curY + SwatchSize / 2.0)
                        .dominantBaseline(Svg.DominantBaseline.Middle)
                        .fill(Svg.Paint.Color(labelColor))
                        .withAttrs(clickAttrs)
                        .apply(cat)
                val approxLabelW = cat.length.toDouble * 7.0
                val (nextX, nextY) =
                    if vertical then (curX, curY + LegendRowH)
                    else (curX + SwatchSize + SwatchLabelGap + approxLabelW + itemGap, curY)
                loop(i + 1, nextX, nextY, acc.append(swatch).append(label))
        loop(0, legendX, legendY, Chunk.empty)
    end buildLegendItems

    // ---- marks region ----

    // ---- Phase 07: interaction helpers ----

    /** Build `UI.Ast.Attrs` that wire hover and click handlers for a single data row.
      *
      * When `spec.onHover` is `Present`, the hover handler sets the ref to `Present(row)` and the unhover
      * handler sets it to `Absent`. When `spec.onSelect` is `Present`, the click handler sets it to
      * `Present(row)`. When an `internalHoverRef` is supplied (tooltip mode), each setter also writes that
      * ref so the tooltip overlay tracks hover independently of the user's ref.
      *
      * Only attaches handlers when the corresponding ref is configured; static charts with no interaction
      * configured receive no handler fields (keeping the attrs clean).
      */
    private def buildInteractionAttrs[A](
        row: A,
        spec: ChartSpec[A],
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]]
    )(using Frame): UI.Ast.Attrs =
        val hasHover   = spec.onHover.isDefined
        val hasSelect  = spec.onSelect.isDefined
        val hasTooltip = internalHoverRef.isDefined
        if !hasHover && !hasSelect && !hasTooltip then UI.Ast.Attrs()
        else
            // Hover action: set user ref + internal tooltip ref (if present), combined sequentially.
            val hoverAction: Maybe[Any < Async] =
                if hasHover || hasTooltip then
                    val actions: Seq[Any < Async] =
                        spec.onHover.map(_.set(Present(row))).toOption.toSeq ++
                            internalHoverRef.map(_.set(Present(row))).toOption.toSeq
                    val combined: Any < Async = actions.foldLeft((): Any < Async)((acc, a) => acc.andThen(a))
                    Present(combined)
                else Absent
            // Unhover action: clear user ref + internal tooltip ref, combined sequentially.
            val unhoverAction: Maybe[Any < Async] =
                if hasHover || hasTooltip then
                    val actions: Seq[Any < Async] =
                        spec.onHover.map(_.set(Absent)).toOption.toSeq ++
                            internalHoverRef.map(_.set(Absent)).toOption.toSeq
                    val combined: Any < Async = actions.foldLeft((): Any < Async)((acc, a) => acc.andThen(a))
                    Present(combined)
                else Absent
            // Click action: set select ref.
            val clickAction: Maybe[Any < Async] =
                spec.onSelect.map(_.set(Present(row)))
            UI.Ast.Attrs(
                onHover = hoverAction,
                onUnhover = unhoverAction,
                onClick = clickAction
            )
        end if
    end buildInteractionAttrs

    /** Built-in highlight (INV-024): the active mark's visual feedback driven by the user's hover/select ref.
      *
      * `ref` is the SAME `SignalRef[Maybe[A]]` the caller passed to `onHover`/`onSelect`; there is no internal
      * interaction cell. `style` is the optional caller-supplied `Style`; when `Absent` the default highlight
      * (a dark stroke outline) is applied to the active mark. The active mark is the one whose source row equals
      * the current value the ref holds; every other mark renders unchanged.
      */
    final private[kyo] case class Highlight[A](ref: Signal.SignalRef[Maybe[A]], style: Maybe[Style])

    /** Resolve the built-in highlight for a chart, if configured.
      *
      * `selectHighlight` (driven by `onSelect`) wins over `hoverHighlight` (driven by `onHover`) when both are
      * enabled, mirroring the click-over-hover precedence of the interaction handlers. Returns `Absent` when no
      * highlight is enabled OR the matching ref is not configured (the documented no-op, INV-024).
      */
    private def resolveHighlight[A](spec: Maybe[ChartSpec[A]]): Maybe[Highlight[A]] =
        spec match
            case Absent => Absent
            case Present(s) =>
                val cfg = s.interactionCfg
                if cfg.selectHighlight then
                    s.onSelect.map(ref => Highlight(ref, cfg.selectStyle))
                else if cfg.hoverHighlight then
                    s.onHover.map(ref => Highlight(ref, cfg.hoverStyle))
                else Absent
                end if
    end resolveHighlight

    /** Translate a highlight `Style` into the SVG attribute changes that realize it on a shape element.
      *
      * `Style` is the CSS-oriented prop bag (`kyo.Style`); the highlight maps the visually meaningful color /
      * opacity props onto the shape's `fill`/`stroke`/`opacity` SVG attributes: a background color brightens the
      * fill, a text/border color becomes a stroke outline, and an opacity prop sets the element opacity. When the
      * style carries no recognized prop (or is `Absent`), the default highlight is a dark 2px stroke outline so
      * the active mark reads as emphasized without depending on a caller-supplied color.
      */
    private def applyHighlightStyle(el: Svg.SvgElement, style: Maybe[Style]): Svg.SvgElement =
        val base = el.svgAttrs
        val styled: Svg.SvgAttrs = style match
            case Absent =>
                // Default highlight: a visible dark stroke outline (does not overwrite the fill).
                base.copy(stroke = Present(Svg.Paint.Color(Style.Color.black)), strokeWidth = Present(Svg.SvgLength.px(2.0)))
            case Present(s) =>
                s.props.foldLeft(base): (attrs, prop) =>
                    prop match
                        case Style.Prop.BgColor(c) =>
                            attrs.copy(fill = Present(Svg.Paint.Color(c)))
                        case Style.Prop.TextColor(c) =>
                            attrs.copy(
                                stroke = Present(Svg.Paint.Color(c)),
                                strokeWidth = if attrs.strokeWidth.isDefined then attrs.strokeWidth else Present(Svg.SvgLength.px(2.0))
                            )
                        case Style.Prop.BorderColorProp(top, _, _, _) =>
                            attrs.copy(
                                stroke = Present(Svg.Paint.Color(top)),
                                strokeWidth = if attrs.strokeWidth.isDefined then attrs.strokeWidth else Present(Svg.SvgLength.px(2.0))
                            )
                        case Style.Prop.OpacityProp(v) =>
                            attrs.copy(opacity = Present(math.max(0.0, math.min(1.0, v))))
                        case _ => attrs
        el.withSvg(styled)
    end applyHighlightStyle

    /** Wrap one mark's row-tagged shapes in the built-in highlight (INV-024).
      *
      * When `highlight` is `Absent`, the shapes are returned unchanged (no-op; non-interactive and plain
      * interactive charts are byte-identical to before). When `Present`, all the mark's shapes are wrapped in a
      * single `Reactive` region driven by the user's `ref`: on each emission, the shape whose source row equals
      * the ref's current value gets `applyHighlightStyle`, and every other shape renders unchanged. The region is
      * driven directly by the user ref (`ref.render`), so no internal mutable interaction cell is created.
      *
      * The `Reactive` node is wrapped in an `Svg.G` so the result is an `Svg.SvgElement` that flows through the
      * marks-region fold (mirroring the reactive-rule path).
      */
    private def withHighlight[A](
        tagged: Chunk[(A, Svg.SvgElement)],
        highlight: Maybe[Highlight[A]]
    )(using Frame): Chunk[Svg.SvgElement] =
        highlight match
            case Absent                       => tagged.map(_._2)
            case Present(_) if tagged.isEmpty => Chunk.empty
            case Present(h) =>
                given CanEqual[Maybe[A], Maybe[A]] = CanEqual.derived
                val reactive: UI.Ast.Reactive[Svg.G] =
                    h.ref.render: active =>
                        val children: Chunk[Svg.SvgElement] = tagged.map: (row, el) =>
                            if active == Present(row) then applyHighlightStyle(el, h.style) else el
                        children.foldLeft(Svg.g)((g, c) => g(c))
                Chunk(Svg.g(reactive))
    end withHighlight

    /** Build the SVG `<g>` containing all lowered marks for static data.
      *
      * When `spec` is `Present`, interaction handlers are attached to each shape-per-row element
      * (bars, circles) so `spec.onHover`/`spec.onSelect` refs receive the hovered/selected datum.
      * Reactive rule values are lowered inside a `Svg.G` wrapper whose child is a `Reactive[Svg.Line]`.
      */
    private[kyo] def marksRegion[A](
        rows: Chunk[A],
        marks: Chunk[Mark[A]],
        layout: Layout,
        xs: Scale,
        ysL: Scale,
        ysR: Maybe[Scale] = Absent,
        spec: Maybe[ChartSpec[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Svg.G =
        // Each mark with no explicit color channel uses a DISTINCT palette color by its index so that a
        // multi-mark chart (e.g. a bar plus a line) shows e.g. blue bars and an orange line. A single-mark
        // chart uses palette(0). Grouped/stacked color-channel marks keep mapping color categories to the
        // palette (handled inside the per-mark lowerers).
        val theme = spec.map(_.theme).getOrElse(Theme.default)
        val allShapes: Chunk[UI] = marks.zipWithIndex.flatMap: (mark, markIdx) =>
            val markColor = markDefaultColor(theme, markIdx)
            val ys = mark match
                case m: Mark.Bar[A, ?, ?]      => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                case m: Mark.Line[A, ?, ?]     => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                case m: Mark.Area[A, ?, ?]     => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                case m: Mark.Point[A, ?, ?]    => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                case m: Mark.Rule[A]           => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                case m: Mark.Text[A, ?, ?]     => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                case m: Mark.ErrorBar[A, ?, ?] => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
            mark match
                case m: Mark.Bar[A, ?, ?] =>
                    spec match
                        case Present(s) => lowerBar(rows, m, layout, xs, ys, markColor, s, internalHoverRef).asInstanceOf[Chunk[UI]]
                        case Absent     => lowerBar(rows, m, layout, xs, ys, markColor).asInstanceOf[Chunk[UI]]
                case m: Mark.Line[A, ?, ?] =>
                    spec match
                        case Present(s) =>
                            lowerLine(rows, m, layout, xs, ys, markColor, Present(s), internalHoverRef).asInstanceOf[Chunk[UI]]
                        case Absent => lowerLine(rows, m, layout, xs, ys, markColor).asInstanceOf[Chunk[UI]]
                case m: Mark.Area[A, ?, ?] =>
                    // INV-023: thread spec/internalHoverRef into lowerArea so interaction fires for area marks.
                    lowerArea(rows, m, layout, xs, ys, markColor, spec, internalHoverRef).asInstanceOf[Chunk[UI]]
                case m: Mark.Point[A, ?, ?] =>
                    spec match
                        case Present(s) =>
                            lowerPoint(
                                rows,
                                m,
                                layout,
                                xs,
                                ys,
                                markColor,
                                Present(s),
                                internalHoverRef,
                                theme,
                                resolveHighlight(Present(s))
                            ).asInstanceOf[Chunk[UI]]
                        case Absent => lowerPoint(rows, m, layout, xs, ys, markColor, theme = theme).asInstanceOf[Chunk[UI]]
                case m: Mark.Rule[A]           => lowerRuleChildren(m, layout, xs, ys)
                case m: Mark.Text[A, ?, ?]     => lowerText(m, rows, xs, ys, markColor, theme).asInstanceOf[Chunk[UI]]
                case m: Mark.ErrorBar[A, ?, ?] => lowerErrorBar(m, rows, xs, ys, markColor, theme).asInstanceOf[Chunk[UI]]
            end match
        allShapes.foldLeft(Svg.g): (g, el) =>
            el match
                case r: Svg.Rect   => g(r)
                case p: Svg.Path   => g(p)
                case c: Svg.Circle => g(c)
                case l: Svg.Line   => g(l)
                case t: Svg.Text   => g(t)
                case inner: Svg.G  => g(inner)
                case _             => g
    end marksRegion

    // ---- per-mark lowerers ----

    /** Lower a `Mark.Bar` to a `Chunk` of `Svg.Rect`s.
      *
      * When the mark has no `color` channel each row produces one rect spanning the full band. When a `color`
      * channel is present the band is subdivided into sub-bands (grouped) or the rects stack vertically
      * (stacked, when `mark.stack.group` is `Present`).
      *
      * When `spec` is supplied, hover and click handlers are attached to each rect so `spec.onHover` and
      * `spec.onSelect` refs track the hovered/selected row. Stacked bars do not carry per-row interaction
      * because each stacked segment represents a partial view of a group rather than a single row.
      */
    private def lowerBar[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        spec: ChartSpec[A] = null.asInstanceOf[ChartSpec[A]],
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val specMaybe: Maybe[ChartSpec[A]] = if spec != null then Present(spec) else Absent
        val highlight                      = resolveHighlight(specMaybe)
        mark.stack.group match
            case Present(_) =>
                lowerBarStacked(rows, mark, layout, xs, ys, specMaybe)
            case Absent =>
                mark.color match
                    case Absent =>
                        lowerBarSimple(rows, mark, layout, xs, ys, defaultColor, specMaybe, internalHoverRef, highlight)
                    case Present(colorCh) =>
                        lowerBarGrouped(
                            rows,
                            mark,
                            colorCh.asInstanceOf[Channel[A, Any]],
                            layout,
                            xs,
                            ys,
                            specMaybe,
                            internalHoverRef,
                            highlight
                        )
        end match
    end lowerBar

    private def lowerBarSimple[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultFill: Style.Color,
        spec: Maybe[ChartSpec[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        highlight: Maybe[Highlight[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val baseline = layout.plotBaseline
        // A bar with no explicit color channel uses the per-mark default fill (palette color by mark index),
        // not the browser default (black), so it is visible and distinct from other marks in a combo chart.
        // The bar rect is tagged with its source row so the built-in highlight (INV-024) can re-style the
        // active row; label texts are not row-shapes and stay outside the highlight region.
        @scala.annotation.tailrec
        def loop(
            i: Int,
            bars: Chunk[(A, Svg.SvgElement)],
            labels: Chunk[Svg.SvgElement]
        ): (Chunk[(A, Svg.SvgElement)], Chunk[Svg.SvgElement]) =
            if i >= rows.size then (bars, labels)
            else
                val row = rows(i)
                // catalog #2: drop non-finite domain values; filterFinite returns Absent for NaN/Inf
                val yDomain = ChartFoundations.filterFinite(mark.y.plottable.toDomain(mark.y.accessor(row)))
                val (nextBars, nextLabels) = yDomain match
                    case Absent => (bars, labels)
                    case Present(yd) =>
                        val xDomain = mark.x.plottable.toDomain(mark.x.accessor(row))
                        xDomain match
                            case Absent => (bars, labels)
                            case Present(xd) =>
                                val barX   = xs.apply(xd)
                                val barW   = xs.bandwidth
                                val barY   = ys.apply(yd)
                                val barH   = baseline - barY
                                val iAttrs = spec.map(s => buildInteractionAttrs(row, s, internalHoverRef)).getOrElse(UI.Ast.Attrs())
                                val baseRect = Svg.rect
                                    .x(barX)
                                    .y(barY)
                                    .width(barW)
                                    .height(barH)
                                    .fill(Svg.Paint.Color(defaultFill))
                                    .withAttrs(iAttrs)
                                // Opacity channel (INV-019).
                                val withOpacity = mark.opacity match
                                    case Present(fn) => baseRect.fillOpacity(math.max(0.0, math.min(1.0, fn(row))))
                                    case Absent      => baseRect
                                // Tooltip channel (INV-019).
                                val withTooltip = mark.tooltip match
                                    case Present(fn) => withOpacity(Svg.title(fn(row)))
                                    case Absent      => withOpacity
                                // Label channel: emit Svg.text above the bar (INV-019).
                                val labelElems: Chunk[Svg.SvgElement] = mark.label match
                                    case Present(fn) =>
                                        val lx = barX + barW / 2.0
                                        val ly = barY - 2.0
                                        Chunk(
                                            Svg.text
                                                .x(lx)
                                                .y(ly)
                                                .textAnchor(Svg.TextAnchor.Middle)
                                                .dominantBaseline(Svg.DominantBaseline.Auto)
                                                .fill(Svg.Paint.Color(defaultFill))
                                                .apply(fn(row))
                                        )
                                    case Absent => Chunk.empty
                                (bars.append((row, withTooltip)), labels ++ labelElems)
                        end match
                loop(i + 1, nextBars, nextLabels)
        val (bars, labels) = loop(0, Chunk.empty, Chunk.empty)
        withHighlight(bars, highlight) ++ labels
    end lowerBarSimple

    private def lowerBarGrouped[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        colorCh: Channel[A, Any],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        spec: Maybe[ChartSpec[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        highlight: Maybe[Highlight[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        // Collect distinct color keys in enum-ordinal order (N3 carry-over)
        val colorKeys: Chunk[String] = collectColorCategories(rows, colorCh)
        val numColors                = colorKeys.size
        val palette                  = resolvePaletteFromCfg(colorKeys)
        val baseline                 = layout.plotBaseline

        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[(A, Svg.SvgElement)]): Chunk[(A, Svg.SvgElement)] =
            if i >= rows.size then acc
            else
                val row     = rows(i)
                val yDomain = mark.y.plottable.toDomain(mark.y.accessor(row))
                val nextAcc = yDomain match
                    case Absent => acc
                    case Present(yd) =>
                        val xDomain = mark.x.plottable.toDomain(mark.x.accessor(row))
                        xDomain match
                            case Absent => acc
                            case Present(xd) =>
                                val bandX     = xs.apply(xd)
                                val bandW     = xs.bandwidth
                                val subW      = bandW / numColors.toDouble
                                val colorKey  = colorCh.accessor(row).toString
                                val colorIdx  = colorKeys.toSeq.indexOf(colorKey)
                                val barX      = bandX + colorIdx.toDouble * subW
                                val barY      = ys.apply(yd)
                                val barH      = baseline - barY
                                val fillColor = if colorIdx >= 0 && colorIdx < palette.size then palette(colorIdx) else DefaultPalette(0)
                                val iAttrs    = spec.map(s => buildInteractionAttrs(row, s, internalHoverRef)).getOrElse(UI.Ast.Attrs())
                                val r = Svg.rect.x(barX).y(barY).width(subW).height(barH).fill(Svg.Paint.Color(fillColor)).withAttrs(iAttrs)
                                acc.append((row, r))
                        end match
                loop(i + 1, nextAcc)
        withHighlight(loop(0, Chunk.empty), highlight)
    end lowerBarGrouped

    /** Lower a stacked `Mark.Bar`: for each distinct x value, stack group rects vertically so that each
      * group's bar sits on top of the previous group's bar.
      *
      * The groups are visited in the order they are first encountered in the data (which for enum `group`
      * accessors will be enum-ordinal order after sorting). `normalize = true` scales each x slot so the
      * total stack height equals the plot height.
      *
      * For each x group:
      *   1. Compute each group's raw y value.
      *   2. If normalizing, compute `totalY`; each group's fraction = `groupY / totalY`.
      *   3. Accumulate pixel positions: y0 starts at the baseline; for each group, y1 = ys.apply(accumulated).
      */
    private def lowerBarStacked[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        spec: Maybe[ChartSpec[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val groupFn  = mark.stack.group.getOrElse((_: A) => "")
        val baseline = layout.plotBaseline
        val plotTop  = layout.plotY

        // 1. Collect all distinct x keys in encounter order
        val xKeys: Chunk[String] = rows.foldLeft(Chunk.empty[String]): (acc, row) =>
            mark.x.plottable.toDomain(mark.x.accessor(row)) match
                case Present(d) =>
                    val k = domainKey(d)
                    if acc.toSeq.contains(k) then acc else acc.append(k)
                case Absent => acc

        // 2. Collect all distinct group keys (with their raw values) in enum-ordinal order.
        // The raw values feed the same palette resolution the legend uses, so each stacked segment is
        // painted in exactly the color its legend swatch shows (honoring an explicit `colorScale`).
        val groupCh: Channel[A, Any]        = Channel(groupFn, Plottable.string.asInstanceOf[Plottable[Any]])
        val groupCats: Chunk[(String, Any)] = collectColorCategoriesWithRaw(rows, groupCh)
        val groupKeys: Chunk[String]        = groupCats.map(_._1)
        val groupPalette: Chunk[Style.Color] = spec match
            case Present(s) => resolvePalette(s, groupCats)
            case Absent     => resolvePaletteFromCfg(groupKeys)

        // 3. Build xKey -> groupKey -> yValue map
        @scala.annotation.tailrec
        def buildMap(i: Int, m: Map[String, Map[String, Double]]): Map[String, Map[String, Double]] =
            if i >= rows.size then m
            else
                val row = rows(i)
                val xKeyOpt = mark.x.plottable.toDomain(mark.x.accessor(row)) match
                    case Present(d) => Present(domainKey(d))
                    case Absent     => Absent
                val yValOpt = mark.y.plottable.toDomain(mark.y.accessor(row)) match
                    case Present(Domain.Continuous(v)) => Present(v)
                    case _                             => Absent
                val next = (xKeyOpt, yValOpt) match
                    case (Present(xk), Present(yv)) =>
                        val groupKey = groupFn(row).toString
                        val inner    = m.getOrElse(xk, Map.empty)
                        m.updated(xk, inner.updated(groupKey, yv))
                    case _ => m
                buildMap(i + 1, next)
        val dataMap = buildMap(0, Map.empty)

        // 4. For each x key, emit stacked rects
        @scala.annotation.tailrec
        def loopX(xi: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if xi >= xKeys.size then acc
            else
                val xKey = xKeys(xi)
                val xDomain = mark.x.plottable.toDomain(mark.x.accessor(
                    rows.toSeq.find(r =>
                        mark.x.plottable.toDomain(mark.x.accessor(r)) match
                            case Present(d) => domainKey(d) == xKey
                            case Absent     => false
                    ).getOrElse(rows(0))
                ))
                val bandX = xDomain match
                    case Present(d) => xs.apply(d)
                    case Absent     => xs.apply(Domain.Category(xKey))
                val bandW    = xs.bandwidth
                val groupMap = dataMap.getOrElse(xKey, Map.empty)
                val totalY   = groupKeys.foldLeft(0.0)((s, gk) => s + groupMap.getOrElse(gk, 0.0))

                // posAcc and negAcc are threaded as loopGroup parameters, reset to 0.0 for each
                // new x-key iteration (INV-018). posAcc accumulates positive contributions upward
                // from the baseline; negAcc accumulates negative contributions downward.
                @scala.annotation.tailrec
                def loopGroup(gi: Int, posAcc: Double, negAcc: Double, acc2: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
                    if gi >= groupKeys.size then acc2
                    else
                        val gk   = groupKeys(gi)
                        val rawY = groupMap.getOrElse(gk, 0.0)
                        val effectiveY =
                            if mark.stack.normalize then
                                if totalY > 0.0 then rawY / totalY else 0.0
                            else rawY
                        // Signed stack: positive values stack upward; negative values stack downward.
                        // (segLo, segHi) bound the segment; nextPosAcc/nextNegAcc carry forward.
                        val (segLo, segHi, nextPosAcc, nextNegAcc) =
                            if mark.stack.normalize then
                                // Normalize mode only handles positive values; treat as before.
                                val hi = posAcc + effectiveY; (posAcc, hi, hi, negAcc)
                            else if effectiveY >= 0.0 then
                                val hi = posAcc + effectiveY; (posAcc, hi, hi, negAcc)
                            else
                                val lo = negAcc + effectiveY; (lo, negAcc, posAcc, lo)
                        val topPx =
                            if mark.stack.normalize then
                                plotTop + (1.0 - segHi) * layout.plotH
                            else ys.apply(Domain.Continuous(segHi))
                        val botPx =
                            if mark.stack.normalize then
                                plotTop + (1.0 - segLo) * layout.plotH
                            else if segLo == 0.0 && segHi >= 0.0 then baseline
                            else ys.apply(Domain.Continuous(segLo))
                        // rectY and rectH are always geometry-safe: min/abs ensure non-negative height.
                        val rectY     = math.min(topPx, botPx)
                        val rectH     = math.abs(topPx - botPx)
                        val fillColor = if gi < groupPalette.size then groupPalette(gi) else DefaultPalette(gi % DefaultPalette.size)
                        // Skip emission when the group contributes nothing at this x slot.
                        val nextAcc2 =
                            if rawY == 0.0 then acc2
                            else
                                // Apply opacity channel if present.
                                val baseRect = Svg.rect
                                    .x(bandX)
                                    .y(rectY)
                                    .width(bandW)
                                    .height(rectH)
                                    .fill(Svg.Paint.Color(fillColor))
                                // Look up the row for this x+group combination to apply per-datum channels.
                                // (There is at most one row per x+group; take the first match.)
                                val rowForSlot: Maybe[A] = rows.toSeq
                                    .find: r =>
                                        val xMatch = mark.x.plottable.toDomain(mark.x.accessor(r)) match
                                            case Present(d) => domainKey(d) == xKey
                                            case Absent     => false
                                        val gMatch = groupFn(r).toString == gk
                                        xMatch && gMatch
                                    .fold[Maybe[A]](Absent)(Present(_))
                                val withChannels: Chunk[Svg.SvgElement] = rowForSlot match
                                    case Absent => Chunk(baseRect)
                                    case Present(r) =>
                                        val withOpacity = mark.opacity match
                                            case Present(fn) => baseRect.fillOpacity(math.max(0.0, math.min(1.0, fn(r))))
                                            case Absent      => baseRect
                                        val labelElems: Chunk[Svg.SvgElement] = mark.label match
                                            case Present(fn) =>
                                                val labelStr = fn(r)
                                                val lx       = bandX + bandW / 2.0
                                                val ly       = rectY - 2.0
                                                Chunk(
                                                    Svg.text
                                                        .x(lx)
                                                        .y(ly)
                                                        .textAnchor(Svg.TextAnchor.Middle)
                                                        .dominantBaseline(Svg.DominantBaseline.Auto)
                                                        .fill(Svg.Paint.Color(fillColor))
                                                        .apply(labelStr)
                                                )
                                            case Absent => Chunk.empty
                                        val withTooltip = mark.tooltip match
                                            case Present(fn) => withOpacity(Svg.title(fn(r)))
                                            case Absent      => withOpacity
                                        Chunk(withTooltip) ++ labelElems
                                acc2 ++ withChannels
                        loopGroup(gi + 1, nextPosAcc, nextNegAcc, nextAcc2)
                val rectsForX = loopGroup(0, 0.0, 0.0, Chunk.empty)
                loopX(xi + 1, acc ++ rectsForX)
        loopX(0, Chunk.empty)
    end lowerBarStacked

    /** Convenience: resolve a palette from DefaultPalette for grouped bar (no spec available here). */
    private def resolvePaletteFromCfg(categories: Chunk[String]): Chunk[Style.Color] =
        categories.zipWithIndex.map: (_, i) =>
            DefaultPalette.toSeq.apply(i % DefaultPalette.size)

    /** Lower a `Mark.Line` to a `Chunk` of `Svg.Path`s.
      *
      * Each `Absent` y value (gap) closes the current sub-path and starts a new `MoveTo` segment. The result may
      * contain multiple `MoveTo` commands in a single `PathData` (one for each contiguous run of defined points).
      * When a `color` channel is present each distinct color series produces its own path.
      *
      * When `spec` is supplied, hover and click handlers are attached to each path. A line path represents all
      * rows in a series, so the hover handler publishes `Absent` (line paths cover multiple rows and no single
      * row is hovered). Per-row interaction is better suited to `point` marks layered over the line.
      */
    private def lowerLine[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Line[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        spec: Maybe[ChartSpec[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        mark.color match
            case Absent =>
                // No color channel: single series whose stroke is the per-mark default color (palette by mark index).
                // INV-023: pass spec/internalHoverRef so click/hover handlers are attached to the path.
                Chunk(lowerLineSeries(rows, mark, layout, xs, ys, defaultColor, spec, internalHoverRef))
            case Present(colorCh) =>
                val colorKeys: Chunk[String] = rows.foldLeft(Chunk.empty[String]): (acc, row) =>
                    val key = colorCh.accessor(row.asInstanceOf[A]).toString
                    if acc.toSeq.contains(key) then acc else acc.append(key)
                colorKeys.zipWithIndex.map: (key, idx) =>
                    val seriesRows  = rows.filter(r => colorCh.accessor(r).toString == key)
                    val strokeColor = DefaultPalette.toSeq.apply(idx % DefaultPalette.size)
                    // INV-023: thread interaction into each per-series path.
                    lowerLineSeries(seriesRows, mark, layout, xs, ys, strokeColor, spec, internalHoverRef)
    end lowerLine

    // Collect all pixel (x, y) pairs for each contiguous defined segment in the series.
    // A gap (undefined row, Absent y, or non-finite coordinates) ends a segment.
    // Returns a Chunk of segments, each segment is a non-empty Chunk of pixel pairs.
    private def collectLineSegments[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Line[A, X, Y],
        xs: Scale,
        ys: Scale
    ): Chunk[Chunk[(Double, Double)]] =
        @scala.annotation.tailrec
        def loop(i: Int, curSeg: Chunk[(Double, Double)], segs: Chunk[Chunk[(Double, Double)]]): Chunk[Chunk[(Double, Double)]] =
            if i >= rows.size then
                if curSeg.isEmpty then segs else segs.append(curSeg)
            else
                val row = rows(i)
                val isDefined = mark.defined match
                    case Present(fn) => fn(row)
                    case Absent      => true
                if !isDefined then
                    // Gap: flush the current segment if non-empty and start fresh.
                    val nextSegs = if curSeg.isEmpty then segs else segs.append(curSeg)
                    loop(i + 1, Chunk.empty, nextSegs)
                else
                    mark.y.accessor(row) match
                        case Absent =>
                            val nextSegs = if curSeg.isEmpty then segs else segs.append(curSeg)
                            loop(i + 1, Chunk.empty, nextSegs)
                        case Present(yv) =>
                            val xd = mark.x.plottable.toDomain(mark.x.accessor(row))
                            val yd = mark.y.plottable.toDomain(yv)
                            (xd, yd) match
                                case (Present(x), Present(y)) =>
                                    val px = xs.apply(x)
                                    val py = ys.apply(y)
                                    // WARN-2 (phase-1 audit): skip non-finite pixel coordinates.
                                    if java.lang.Double.isFinite(px) && java.lang.Double.isFinite(py) then
                                        loop(i + 1, curSeg.append((px, py)), segs)
                                    else
                                        val nextSegs = if curSeg.isEmpty then segs else segs.append(curSeg)
                                        loop(i + 1, Chunk.empty, nextSegs)
                                    end if
                                case _ =>
                                    val nextSegs = if curSeg.isEmpty then segs else segs.append(curSeg)
                                    loop(i + 1, Chunk.empty, nextSegs)
                            end match
                    end match
                end if
        loop(0, Chunk.empty, Chunk.empty)
    end collectLineSegments

    private def lowerLineSeries[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Line[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        strokeColor: Style.Color = Style.Color.blue,
        spec: Maybe[ChartSpec[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Svg.Path =
        // Collect contiguous defined segments. Each segment is then threaded through
        // CurvePath.append for the chosen interpolation (INV-016, G13 from prep.md).
        val segments = collectLineSegments(rows, mark, xs, ys)
        val pathData: Svg.PathData = segments.foldLeft(Svg.PathData.empty): (pd, seg) =>
            if seg.isEmpty then pd
            else
                val p0 = seg(0)
                // moveTo the first point of the segment; then append remaining points via CurvePath.
                val startPd = if Svg.PathData.commands(pd).isEmpty then Svg.PathData.from(p0._1, p0._2) else pd.moveTo(p0._1, p0._2)
                CurvePath.append(startPd, seg.drop(1), mark.curve)
        // A line path must be stroked, not filled. Without explicit fill=none the browser fills the
        // closed polygon formed by the path endpoints, producing a black bowtie artefact.
        val basePath = Svg.path
            .d(pathData)
            .fill(Svg.Paint.None)
            .stroke(Svg.Paint.Color(strokeColor))
            .strokeWidth(2.0)
        // Opacity channel: for a line, apply as strokeOpacity (INV-019).
        val withOpacity = mark.opacity match
            case Present(fn) =>
                // Use the first row to evaluate line-level opacity (series-level channel, not per-datum).
                rows.toSeq.headOption match
                    case Some(r) => basePath.strokeOpacity(math.max(0.0, math.min(1.0, fn(r))))
                    case None    => basePath
            case Absent => basePath
        // Tooltip channel: attach a title child on the path element for browser tooltip.
        val withTooltip = mark.tooltip match
            case Present(fn) =>
                rows.toSeq.headOption match
                    case Some(r) => withOpacity(Svg.title(fn(r)))
                    case None    => withOpacity
            case Absent => withOpacity
        // INV-023: attach interaction attrs to the line path. The representative row for a line series
        // is the first row of the series chunk (path-level, not per-datum).
        spec match
            case Absent => withTooltip
            case Present(s) =>
                rows.toSeq.headOption match
                    case None    => withTooltip
                    case Some(r) => withTooltip.withAttrs(buildInteractionAttrs(r, s, internalHoverRef))
        end match
    end lowerLineSeries

    /** Lower a `Mark.Text` to one `Svg.text` per row at `(x, y)` (INV-021).
      *
      * Gap rows (where the `y` ChannelMaybe returns `Absent`) produce no text element. The
      * `anchor` is mapped from `kyo.TextAnchor` to `Svg.TextAnchor`; both enums have the same
      * case names but live in different packages and must not be cast. When `mark.color` is
      * `Present`, each row receives its category palette color; otherwise `defaultColor` is used.
      * `opacity` sets `fillOpacity` clamped to [0,1].
      */
    private def lowerText[A, X, Y](
        mark: Mark.Text[A, X, Y],
        rows: Chunk[A],
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        theme: Theme
    )(using Frame): Chunk[Svg.SvgElement] =
        // TextAnchor mapping: two distinct enums, explicit match required (prep.md gotcha 5).
        val svgAnchor = mark.anchor match
            case TextAnchor.Start  => Svg.TextAnchor.Start
            case TextAnchor.Middle => Svg.TextAnchor.Middle
            case TextAnchor.End    => Svg.TextAnchor.End
        // Resolve per-category palette when mark.color is Present.
        val colorCatsWithRaw: Chunk[(String, Any)] = mark.color match
            case Present(ch) => collectColorCategoriesWithRaw(rows, ch.asInstanceOf[Channel[A, Any]])
            case Absent      => Chunk.empty
        val palette: Chunk[Style.Color] =
            if colorCatsWithRaw.isEmpty then Chunk.empty
            else
                colorCatsWithRaw.zipWithIndex.map: (_, i) =>
                    DefaultPalette.toSeq.apply(i % DefaultPalette.size)
        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= rows.size then acc
            else
                val row = rows(i)
                // Gap check: skip rows where y is Absent.
                val nextAcc = mark.y.accessor(row) match
                    case Absent => acc
                    case Present(yv) =>
                        val xd = mark.x.plottable.toDomain(mark.x.accessor(row))
                        val yd = mark.y.plottable.toDomain(yv)
                        (xd, yd) match
                            case (Present(x), Present(y)) =>
                                val px = xs.apply(x)
                                val py = ys.apply(y)
                                val fillColor: Style.Color = mark.color match
                                    case Absent => defaultColor
                                    case Present(ch) =>
                                        val catKey = ch.accessor(row.asInstanceOf[A]).toString
                                        val idx    = colorCatsWithRaw.toSeq.indexWhere(_._1 == catKey)
                                        if idx >= 0 && idx < palette.size then palette(idx) else defaultColor
                                val baseText = Svg.text
                                    .x(px)
                                    .y(py)
                                    .textAnchor(svgAnchor)
                                    .fill(Svg.Paint.Color(fillColor))
                                // Apply opacity if set.
                                val withOpacity = mark.opacity match
                                    case Present(fn) => baseText.fillOpacity(math.max(0.0, math.min(1.0, fn(row))))
                                    case Absent      => baseText
                                acc.append(withOpacity(mark.label(row)))
                            case _ => acc
                        end match
                loop(i + 1, nextAcc)
        loop(0, Chunk.empty)
    end lowerText

    /** Lower a `Mark.ErrorBar` to SVG elements per row (INV-022).
      *
      * Each row yields: one vertical `Svg.line` from `low` to `high` at `x`, two horizontal cap
      * `Svg.line`s of `capWidth` pixels centered at `x`, and one `Svg.circle` center marker at `y`.
      * All elements use plain `Svg.line` and `Svg.circle` with NO `url(#id)` or `<marker>` references.
      * When `mark.color` is `Present`, each row's stroke uses its category palette color.
      */
    private def lowerErrorBar[A, X, Y](
        mark: Mark.ErrorBar[A, X, Y],
        rows: Chunk[A],
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        theme: Theme
    )(using Frame): Chunk[Svg.SvgElement] =
        val colorCatsWithRaw: Chunk[(String, Any)] = mark.color match
            case Present(ch) => collectColorCategoriesWithRaw(rows, ch.asInstanceOf[Channel[A, Any]])
            case Absent      => Chunk.empty
        val palette: Chunk[Style.Color] =
            if colorCatsWithRaw.isEmpty then Chunk.empty
            else
                colorCatsWithRaw.zipWithIndex.map: (_, i) =>
                    DefaultPalette.toSeq.apply(i % DefaultPalette.size)
        val halfCap = mark.capWidth / 2.0
        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= rows.size then acc
            else
                val row   = rows(i)
                val xd    = mark.x.plottable.toDomain(mark.x.accessor(row))
                val yd    = mark.y.plottable.toDomain(mark.y.accessor(row))
                val lowD  = mark.low.plottable.toDomain(mark.low.accessor(row))
                val highD = mark.high.plottable.toDomain(mark.high.accessor(row))
                val nextAcc = (xd, yd, lowD, highD) match
                    case (Present(x), Present(y), Present(lo), Present(hi)) =>
                        val px     = xs.apply(x)
                        val py     = ys.apply(y)
                        val pyLow  = ys.apply(lo)
                        val pyHigh = ys.apply(hi)
                        val colorIdx = mark.color match
                            case Absent => -1
                            case Present(ch) =>
                                val catKey = ch.accessor(row.asInstanceOf[A]).toString
                                colorCatsWithRaw.toSeq.indexWhere(_._1 == catKey)
                        val color: Style.Color =
                            if colorIdx >= 0 && colorIdx < palette.size then palette(colorIdx) else defaultColor
                        val stroke = Svg.Paint.Color(color)
                        // Vertical line: low -> high at x.
                        val vLine = Svg.line
                            .x1(px).y1(pyLow)
                            .x2(px).y2(pyHigh)
                            .stroke(stroke)
                            .strokeWidth(1.5)
                        // Low cap: horizontal line at pyLow.
                        val capLow = Svg.line
                            .x1(px - halfCap).y1(pyLow)
                            .x2(px + halfCap).y2(pyLow)
                            .stroke(stroke)
                            .strokeWidth(1.5)
                        // High cap: horizontal line at pyHigh.
                        val capHigh = Svg.line
                            .x1(px - halfCap).y1(pyHigh)
                            .x2(px + halfCap).y2(pyHigh)
                            .stroke(stroke)
                            .strokeWidth(1.5)
                        // Center marker: small circle at y.
                        val marker = Svg.circle
                            .cx(px).cy(py).r(3.0)
                            .fill(stroke)
                        acc.append(vLine).append(capLow).append(capHigh).append(marker)
                    case _ =>
                        acc
                loop(i + 1, nextAcc)
        loop(0, Chunk.empty)
    end lowerErrorBar

    /** Lower a `Mark.Area` to closed `Svg.Path`(s).
      *
      * Three dispatch paths:
      *   1. `mark.stack.group` is `Present` and `mark.y` is `Present`: stacked area (groups sit atop each other).
      *   2. `mark.y` is `Present` and no stack: single area fills between y values and the plot baseline.
      *   3. `mark.y` is `Absent`: the `y0`/`y1` band form (INV-017: closed ribbon between two edges).
      */
    private def lowerArea[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Area[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color = DefaultPalette(0),
        spec: Maybe[ChartSpec[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val baseline = layout.plotBaseline
        mark.y match
            case Present(yCh) =>
                mark.stack.group match
                    case Present(_) =>
                        // INV-023: thread spec/internalHoverRef into stacked area so interaction fires per group.
                        lowerAreaStacked(rows, mark, yCh, layout, xs, ys, spec, internalHoverRef)
                    case Absent =>
                        // Collect (px, py) pairs, skipping non-finite gaps (WARN-2 from phase-1 audit).
                        val points: Chunk[(Double, Double)] = rows.flatMap: row =>
                            yCh.accessor(row) match
                                case Absent => Chunk.empty
                                case Present(yv) =>
                                    val xd = mark.x.plottable.toDomain(mark.x.accessor(row))
                                    val yd = yCh.plottable.toDomain(yv)
                                    (xd, yd) match
                                        case (Present(x), Present(y)) =>
                                            val px = xs.apply(x)
                                            val py = ys.apply(y)
                                            if java.lang.Double.isFinite(px) && java.lang.Double.isFinite(py) then
                                                Chunk((px, py))
                                            else Chunk.empty
                                        case _ => Chunk.empty
                                    end match
                        if points.isEmpty then Chunk.empty
                        else
                            // Top edge forward via CurvePath (INV-016 applied to area).
                            val startPd = Svg.PathData.from(points(0)._1, points(0)._2)
                            val topPd   = CurvePath.append(startPd, points.drop(1), mark.curve)
                            // Baseline back: from last x at baseline to first x at baseline, then close.
                            val lastX  = points(points.size - 1)._1
                            val firstX = points(0)._1
                            val pd2    = topPd.lineTo(lastX, baseline).lineTo(firstX, baseline).close
                            // Apply opacity channel if present; default fillOpacity=0.7 (INV-019).
                            val baseOpacity = 0.7
                            val opacity = mark.opacity match
                                case Present(fn) =>
                                    rows.toSeq.headOption.map(r => math.max(0.0, math.min(1.0, fn(r)))).getOrElse(baseOpacity)
                                case Absent => baseOpacity
                            val basePath = Svg.path.d(pd2).fill(Svg.Paint.Color(defaultColor)).fillOpacity(opacity)
                            val withTooltip = mark.tooltip match
                                case Present(fn) =>
                                    rows.toSeq.headOption match
                                        case Some(r) => basePath(Svg.title(fn(r)))
                                        case None    => basePath
                                case Absent => basePath
                            // INV-023: attach interaction attrs to the single area path.
                            // The representative row is the first defined row.
                            val withInteraction = spec match
                                case Absent => withTooltip
                                case Present(s) =>
                                    rows.toSeq.headOption match
                                        case None    => withTooltip
                                        case Some(r) => withTooltip.withAttrs(buildInteractionAttrs(r, s, internalHoverRef))
                            Chunk(withInteraction)
                        end if
            case Absent =>
                // y0/y1 band form: render a closed ribbon between the two edges (INV-017).
                buildBandRibbon(rows, mark, xs, ys, defaultColor)
        end match
    end lowerArea

    /** Build the closed ribbon path for the area band form (`y0` and `y1` supplied, `y` absent).
      *
      * The ribbon runs forward along the y1 edge (curve-interpolated), then backward along the
      * y0 edge, then closes. Invalid combinations (only `y0` or only `y1`, or neither) emit
      * `Chunk.empty` so that sibling marks still render (Q-005 / INV-017 / G9 from prep.md).
      */
    private def buildBandRibbon[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Area[A, X, Y],
        xs: Scale,
        ys: Scale,
        fillColor: Style.Color
    )(using Frame): Chunk[Svg.SvgElement] =
        // Both y0 and y1 must be Present for a valid band (G9 from prep.md).
        (mark.y0, mark.y1) match
            case (Present(ch0), Present(ch1)) =>
                // Collect (xPx, y0Px, y1Px) triples for each row, skipping non-finite values (WARN-2).
                val pts: Chunk[(Double, Double, Double)] = rows.flatMap: row =>
                    val xd  = mark.x.plottable.toDomain(mark.x.accessor(row))
                    val y0d = ch0.plottable.toDomain(ch0.accessor(row))
                    val y1d = ch1.plottable.toDomain(ch1.accessor(row))
                    (xd, y0d, y1d) match
                        case (Present(xdom), Present(y0dom), Present(y1dom)) =>
                            val xPx  = xs.apply(xdom)
                            val y0Px = ys.apply(y0dom)
                            val y1Px = ys.apply(y1dom)
                            if java.lang.Double.isFinite(xPx) && java.lang.Double.isFinite(y0Px) && java.lang.Double.isFinite(y1Px) then
                                Chunk((xPx, y0Px, y1Px))
                            else Chunk.empty
                        case _ => Chunk.empty
                    end match
                if pts.isEmpty then Chunk.empty
                else
                    // Forward along y1 edge (curved per mark.curve).
                    val y1pts     = pts.map(t => (t._1, t._3))
                    val startPd   = Svg.PathData.from(y1pts(0)._1, y1pts(0)._2)
                    val forwardPd = CurvePath.append(startPd, y1pts.drop(1), mark.curve)
                    // Backward along y0 edge, traversed in reverse order, curved per mark.curve too
                    // (leaf 19): both band edges must reflect the curve, not just the forward y1 edge.
                    // The connecting edge from the last y1 vertex down to the last y0 vertex is a single
                    // lineTo; the remaining reversed y0 vertices feed CurvePath.append so the y0 edge curves.
                    val y0ptsRev    = Chunk.from(pts.toSeq.reverse.map(t => (t._1, t._2)))
                    val connectedPd = forwardPd.lineTo(y0ptsRev(0)._1, y0ptsRev(0)._2)
                    val ribbonPd    = CurvePath.append(connectedPd, y0ptsRev.drop(1), mark.curve).close
                    val baseOpacity = 0.7
                    val opacity = mark.opacity match
                        case Present(fn) =>
                            rows.toSeq.headOption.map(r => math.max(0.0, math.min(1.0, fn(r)))).getOrElse(baseOpacity)
                        case Absent => baseOpacity
                    val basePath = Svg.path.d(ribbonPd).fill(Svg.Paint.Color(fillColor)).fillOpacity(opacity)
                    val withTooltip = mark.tooltip match
                        case Present(fn) =>
                            rows.toSeq.headOption match
                                case Some(r) => basePath(Svg.title(fn(r)))
                                case None    => basePath
                        case Absent => basePath
                    Chunk(withTooltip)
                end if
            case _ =>
                // Only one edge supplied or neither: invalid combo, skip deterministically (INV-017).
                Chunk.empty
        end match
    end buildBandRibbon

    /** Lower a stacked `Mark.Area`: for each distinct group, emit a closed area path whose baseline is the
      * top edge of the previous group at each x position.
      *
      * Groups are visited in encounter order (enum-ordinal order for enum group accessors). For
      * `normalize = true`, the y contribution of each group at each x is expressed as a fraction of the
      * total at that x, and the path fills to the plot height.
      *
      * For each group:
      *   1. Compute `y0` (bottom of this band) as the sum of all previous groups' y values at each x.
      *   2. Compute `y1` (top of this band) as `y0 + this group's y value`.
      *   3. Emit a closed path: top edge forward (y1 points), then bottom edge backward (y0 points), close.
      *   4. Skip emission entirely when the group contributes zero at every x slot.
      */
    private def lowerAreaStacked[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Area[A, X, Y],
        yCh: ChannelMaybe[A, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        spec: Maybe[ChartSpec[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val groupFn  = mark.stack.group.getOrElse((_: A) => "")
        val baseline = layout.plotBaseline
        val plotTop  = layout.plotY

        // Collect all distinct x keys in encounter order
        val xKeys: Chunk[String] = rows.foldLeft(Chunk.empty[String]): (acc, row) =>
            mark.x.plottable.toDomain(mark.x.accessor(row)) match
                case Present(d) =>
                    val k = domainKey(d)
                    if acc.toSeq.contains(k) then acc else acc.append(k)
                case Absent => acc

        // Collect all distinct group keys in encounter order
        val groupCh: Channel[A, Any] = Channel(groupFn, Plottable.string.asInstanceOf[Plottable[Any]])
        val groupKeys: Chunk[String] = collectColorCategories(rows, groupCh)

        // Build xKey -> groupKey -> yValue map
        @scala.annotation.tailrec
        def buildMap(i: Int, m: Map[String, Map[String, Double]]): Map[String, Map[String, Double]] =
            if i >= rows.size then m
            else
                val row = rows(i)
                val xKeyOpt = mark.x.plottable.toDomain(mark.x.accessor(row)) match
                    case Present(d) => Present(domainKey(d))
                    case Absent     => Absent
                val yValOpt = yCh.accessor(row) match
                    case Present(yv) => yCh.plottable.toDomain(yv) match
                            case Present(Domain.Continuous(v)) => Present(v)
                            case _                             => Absent
                    case Absent => Absent
                val next = (xKeyOpt, yValOpt) match
                    case (Present(xk), Present(yv)) =>
                        val gk    = groupFn(row).toString
                        val inner = m.getOrElse(xk, Map.empty)
                        m.updated(xk, inner.updated(gk, yv))
                    case _ => m
                buildMap(i + 1, next)
        val dataMap = buildMap(0, Map.empty)

        // Compute per-x totals for normalization
        val xTotals: Map[String, Double] = xKeys.foldLeft(Map.empty[String, Double]): (acc, xk) =>
            val groupMap = dataMap.getOrElse(xk, Map.empty)
            acc.updated(xk, groupKeys.foldLeft(0.0)((s, gk) => s + groupMap.getOrElse(gk, 0.0)))

        // For each group, compute the pixel x values and the y0/y1 pixel pairs at each x slot.
        // `accumulatedFractions` tracks, for each xKey, how much of the stack has been consumed so far.
        @scala.annotation.tailrec
        def loopGroups(
            gi: Int,
            accByX: Map[String, Double],
            acc: Chunk[Svg.SvgElement]
        ): Chunk[Svg.SvgElement] =
            if gi >= groupKeys.size then acc
            else
                val gk = groupKeys(gi)

                // Build (px, py0, py1) for each x slot in order
                val bands: Chunk[(Double, Double, Double)] = xKeys.flatMap: xk =>
                    val groupMap = dataMap.getOrElse(xk, Map.empty)
                    val rawY     = groupMap.getOrElse(gk, 0.0)
                    val accY     = accByX.getOrElse(xk, 0.0)
                    val total    = xTotals.getOrElse(xk, 0.0)
                    val xDomain = mark.x.plottable.toDomain(mark.x.accessor(
                        rows.toSeq
                            .find: r =>
                                mark.x.plottable.toDomain(mark.x.accessor(r)) match
                                    case Present(d) => domainKey(d) == xk
                                    case Absent     => false
                            .getOrElse(rows(0))
                    ))
                    val px = xDomain match
                        case Present(d) => xs.apply(d)
                        case Absent     => xs.apply(Domain.Category(xk))
                    val (py0, py1) =
                        if mark.stack.normalize then
                            val f0 = if total > 0.0 then accY / total else 0.0
                            val f1 = if total > 0.0 then (accY + rawY) / total else 0.0
                            (plotTop + (1.0 - f0) * layout.plotH, plotTop + (1.0 - f1) * layout.plotH)
                        else
                            val bot = if accY == 0.0 then baseline else ys.apply(Domain.Continuous(accY))
                            val top = ys.apply(Domain.Continuous(accY + rawY))
                            (bot, top)
                    Chunk((px, py0, py1))

                // Skip groups that contribute nothing at every x (FIX 3a: no zero-height paths)
                val hasContribution = xKeys.toSeq.exists: xk =>
                    dataMap.getOrElse(xk, Map.empty).getOrElse(gk, 0.0) > 0.0

                val newAcc =
                    if !hasContribution then acc
                    else
                        // Top edge forward (y1 values), then bottom edge backward (y0 values), then close
                        val topEdge: Svg.PathData = bands.tail.foldLeft(
                            Svg.PathData.from(bands(0)._1, bands(0)._3)
                        ): (pd, b) =>
                            pd.lineTo(b._1, b._3)
                        val fullPath = bands.toSeq.reverse.foldLeft(topEdge): (pd, b) =>
                            pd.lineTo(b._1, b._2)
                        val basePath = Svg.path.d(fullPath.close)
                        // INV-023: attach interaction attrs to each group path.
                        // The representative row is the first row in this group.
                        val withInteraction = spec match
                            case Absent => basePath
                            case Present(s) =>
                                val groupFn = mark.stack.group.getOrElse((_: A) => "")
                                rows.toSeq.find(r => groupFn(r).toString == gk) match
                                    case None    => basePath
                                    case Some(r) => basePath.withAttrs(buildInteractionAttrs(r, s, internalHoverRef))
                        acc.append(withInteraction)

                // Update accumulated fractions for the next group
                val newAccByX = xKeys.foldLeft(accByX): (m, xk) =>
                    val groupMap = dataMap.getOrElse(xk, Map.empty)
                    m.updated(xk, m.getOrElse(xk, 0.0) + groupMap.getOrElse(gk, 0.0))

                loopGroups(gi + 1, newAccByX, newAcc)
        loopGroups(0, Map.empty, Chunk.empty)
    end lowerAreaStacked

    /** Lower a `Mark.Point` to glyphs (circle, square, triangle, diamond, or cross).
      *
      * Color channel (INV-013): when `mark.color` is `Present`, rows are split by
      * `ChartFoundations.categoryKey` and each category gets a distinct palette color
      * via the same `resolvePalette` the legend uses. Without a color channel, all
      * points use `defaultColor`.
      *
      * Size channel (INV-015): `sizePx` overrides with raw pixel radius; `size` uses a
      * sqrt-area scale built from the magnitude extent over all rows. Absent: `DefaultRadius`.
      *
      * Symbol channel (INV-014): dispatches on `Symbol` to circle (`Svg.circle`) or a
      * path glyph helper (square, triangle, diamond, cross).
      *
      * Channels (INV-019): `opacity` sets `fillOpacity`; `label` emits an `Svg.text`
      * above the glyph; `tooltip` attaches a `<title>` child.
      */
    private def lowerPoint[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Point[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        spec: Maybe[ChartSpec[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        theme: Theme = Theme.default,
        highlight: Maybe[Highlight[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val separator = pointSeparatorColor(theme)

        // Build the sqrt-area size scale once from the full row set (G5 from prep.md).
        val sizeScale: Maybe[SizeScale] = mark.size match
            case Present(fn) =>
                // Fold the magnitude extent, skipping rows with Absent y (gap rows).
                @scala.annotation.tailrec
                def foldMag(i: Int, mn: Double, mx: Double): (Double, Double) =
                    if i >= rows.size then (mn, mx)
                    else
                        val row   = rows(i)
                        val hasPt = mark.y.accessor(row).isDefined
                        if hasPt then
                            val mag = fn(row)
                            foldMag(i + 1, math.min(mn, mag), math.max(mx, mag))
                        else foldMag(i + 1, mn, mx)
                        end if
                val (magMin, magMax) = foldMag(0, Double.MaxValue, Double.MinValue)
                val safeMin          = if magMin == Double.MaxValue then 0.0 else magMin
                val safeMax          = if magMax == Double.MinValue then 0.0 else magMax
                Present(SizeScale(safeMin, safeMax, SizeScale.DefaultRMin, SizeScale.DefaultRMax))
            case Absent => Absent

        // Resolve color categories when a color channel is present (INV-013, G6 from prep.md).
        val colorResolved: Maybe[(Chunk[(String, Any)], Chunk[Style.Color])] = mark.color match
            case Present(colorCh) =>
                val cats = collectColorCategoriesWithRaw(rows, colorCh)
                val palette = spec match
                    case Present(s) => resolvePalette(s, cats)
                    case Absent     => cats.zipWithIndex.map((_, i) => DefaultPalette.toSeq.apply(i % DefaultPalette.size))
                Present((cats, palette))
            case Absent => Absent

        // Build per-row color lookup: CatKey -> Style.Color.
        val colorByKey: scala.collection.immutable.Map[ChartFoundations.CatKey, Style.Color] =
            colorResolved match
                case Absent => scala.collection.immutable.Map.empty
                case Present((cats, palette)) =>
                    cats.zipWithIndex.foldLeft(scala.collection.immutable.Map.empty[ChartFoundations.CatKey, Style.Color]): (m, pair) =>
                        val ((label, raw), idx) = pair
                        val key                 = ChartFoundations.categoryKey(raw)
                        val color = if idx < palette.size then palette(idx) else DefaultPalette.toSeq.apply(idx % DefaultPalette.size)
                        m.updated(key, color)

        // Glyph elements are tagged with their source row so the built-in highlight (INV-024) can re-style the
        // active row's glyph(s); label texts are not row-shapes and stay outside the highlight region.
        @scala.annotation.tailrec
        def loop(
            i: Int,
            glyphs: Chunk[(A, Svg.SvgElement)],
            labels: Chunk[Svg.SvgElement]
        ): (Chunk[(A, Svg.SvgElement)], Chunk[Svg.SvgElement]) =
            if i >= rows.size then (glyphs, labels)
            else
                val row = rows(i)
                val nextAcc: (Chunk[(A, Svg.SvgElement)], Chunk[Svg.SvgElement]) = mark.y.accessor(row) match
                    case Absent => (glyphs, labels)
                    case Present(yv) =>
                        val xd = mark.x.plottable.toDomain(mark.x.accessor(row))
                        val yd = mark.y.plottable.toDomain(yv)
                        (xd, yd) match
                            case (Present(x), Present(y)) =>
                                val cx = xs.apply(x)
                                val cy = ys.apply(y)
                                // WARN-2 (phase-1 audit): skip non-finite pixel coordinates.
                                if !java.lang.Double.isFinite(cx) || !java.lang.Double.isFinite(cy) then (glyphs, labels)
                                else
                                    // Resolve radius: sizePx > size > DefaultRadius.
                                    val r = mark.sizePx match
                                        case Present(fn) => fn(row)
                                        case Absent =>
                                            sizeScale match
                                                case Present(sc) => sc.radius(mark.size.map(_(row)).getOrElse(DefaultRadius))
                                                case Absent      => DefaultRadius

                                    // Resolve fill color.
                                    val fillColor = mark.color match
                                        case Absent => defaultColor
                                        case Present(colorCh) =>
                                            val key = ChartFoundations.categoryKey(colorCh.accessor(row))
                                            colorByKey.getOrElse(key, defaultColor)

                                    val iAttrs = spec.map(s => buildInteractionAttrs(row, s, internalHoverRef)).getOrElse(UI.Ast.Attrs())

                                    // Resolve symbol and build the glyph shape (INV-014).
                                    val sym = mark.symbol.map(_(row)).getOrElse(Symbol.circle)

                                    // Opacity channel (INV-019).
                                    val opacity = mark.opacity match
                                        case Present(fn) => Present(math.max(0.0, math.min(1.0, fn(row))))
                                        case Absent      => Absent

                                    // Build glyph elements (circle or path-based).
                                    val glyphElems: Chunk[Svg.SvgElement] = sym match
                                        case Symbol.circle =>
                                            val base = Svg.circle
                                                .cx(cx).cy(cy).r(r)
                                                .fill(Svg.Paint.Color(fillColor))
                                                .stroke(Svg.Paint.Color(separator))
                                                .strokeWidth(PointStrokeWidth)
                                                .withAttrs(iAttrs)
                                            val withOp = opacity match
                                                case Present(op) => base.fillOpacity(op)
                                                case Absent      => base
                                            val withTip = mark.tooltip match
                                                case Present(fn) => withOp(Svg.title(fn(row)))
                                                case Absent      => withOp
                                            Chunk(withTip)
                                        case Symbol.cross =>
                                            // Cross: two perpendicular Svg.Line elements (INV-014, G7 from prep.md).
                                            val h = Svg.line.x1(cx - r).y1(cy).x2(cx + r).y2(cy)
                                                .stroke(Svg.Paint.Color(fillColor)).strokeWidth(PointStrokeWidth + 0.5)
                                                .withAttrs(iAttrs)
                                            val v = Svg.line.x1(cx).y1(cy - r).x2(cx).y2(cy + r)
                                                .stroke(Svg.Paint.Color(fillColor)).strokeWidth(PointStrokeWidth + 0.5)
                                            Chunk(h, v)
                                        case _ =>
                                            val pd = sym match
                                                case Symbol.square   => squarePath(cx, cy, r)
                                                case Symbol.triangle => trianglePath(cx, cy, r)
                                                case Symbol.diamond  => diamondPath(cx, cy, r)
                                                case _               => squarePath(cx, cy, r) // unreachable
                                            val base = Svg.path.d(pd)
                                                .fill(Svg.Paint.Color(fillColor))
                                                .stroke(Svg.Paint.Color(separator))
                                                .strokeWidth(PointStrokeWidth)
                                                .withAttrs(iAttrs)
                                            val withOp = opacity match
                                                case Present(op) => base.fillOpacity(op)
                                                case Absent      => base
                                            val withTip = mark.tooltip match
                                                case Present(fn) => withOp(Svg.title(fn(row)))
                                                case Absent      => withOp
                                            Chunk(withTip)

                                    // Label channel: emit Svg.text above the glyph (INV-019).
                                    val labelElems: Chunk[Svg.SvgElement] = mark.label match
                                        case Present(fn) =>
                                            val labelStr = fn(row)
                                            Chunk(
                                                Svg.text
                                                    .x(cx)
                                                    .y(cy - r - 2.0)
                                                    .textAnchor(Svg.TextAnchor.Middle)
                                                    .dominantBaseline(Svg.DominantBaseline.Auto)
                                                    .fill(Svg.Paint.Color(fillColor))
                                                    .apply(labelStr)
                                            )
                                        case Absent => Chunk.empty

                                    (glyphs ++ glyphElems.map(g => (row, g)), labels ++ labelElems)
                                end if
                            case _ => (glyphs, labels)
                        end match
                loop(i + 1, nextAcc._1, nextAcc._2)
        val (glyphs, labels) = loop(0, Chunk.empty, Chunk.empty)
        withHighlight(glyphs, highlight) ++ labels
    end lowerPoint

    // ---- Symbol glyph path helpers (INV-014) ----

    /** Square path centered at (cx, cy) with half-width r.
      * Corners at (cx-r, cy-r), (cx+r, cy-r), (cx+r, cy+r), (cx-r, cy+r).
      */
    private def squarePath(cx: Double, cy: Double, r: Double): Svg.PathData =
        Svg.PathData.from(cx - r, cy - r)
            .lineTo(cx + r, cy - r)
            .lineTo(cx + r, cy + r)
            .lineTo(cx - r, cy + r)
            .close

    /** Equilateral triangle centered at (cx, cy) with circumradius r.
      * Apex at (cx, cy-r); base vertices at (cx-r*sin(pi/3), cy+r/2) and (cx+r*sin(pi/3), cy+r/2).
      */
    private def trianglePath(cx: Double, cy: Double, r: Double): Svg.PathData =
        val h = r * math.sin(math.Pi / 3.0)
        Svg.PathData.from(cx, cy - r)
            .lineTo(cx + h, cy + r / 2.0)
            .lineTo(cx - h, cy + r / 2.0)
            .close
    end trianglePath

    /** Diamond path (rotated square) centered at (cx, cy) with arm length r.
      * Points: top (cx, cy-r), right (cx+r, cy), bottom (cx, cy+r), left (cx-r, cy).
      */
    private def diamondPath(cx: Double, cy: Double, r: Double): Svg.PathData =
        Svg.PathData.from(cx, cy - r)
            .lineTo(cx + r, cy)
            .lineTo(cx, cy + r)
            .lineTo(cx - r, cy)
            .close

    /** Lower a `Mark.Rule` to an `Svg.Line` spanning the full plot width (horizontal rule) or height (vertical rule).
      *
      * `Const` rule values produce a static `Svg.Line` immediately. `Reactive` rule values produce a
      * `Svg.G` wrapping a `Reactive[Svg.Line]` so the line position tracks the signal. The returned
      * `Chunk[Svg.SvgElement]` contains only static elements; call `lowerRuleChildren` when reactive
      * rules must be included as `UI` children (which the marks region uses).
      */
    private def lowerRule[A](
        mark: Mark.Rule[A],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Chunk[Svg.SvgElement] =
        lowerRuleChildren(mark, layout, xs, ys).collect:
            case el: Svg.SvgElement => el

    /** Lower a `Mark.Rule` to a `Chunk[UI]` that may include `Reactive` nodes.
      *
      * `Const` rule values produce a static `Svg.Line`. `Reactive` rule values produce a `Svg.G` whose
      * single child is a `Reactive[Svg.Line]` that re-renders whenever the signal emits a new value. This
      * `Svg.G` is itself a `Svg.SvgElement` so it fits into the marks-region fold without type widening.
      *
      * N4-guard: no `url(#id)` references are emitted (plain lines only).
      */
    private def lowerRuleChildren[A](
        mark: Mark.Rule[A],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Chunk[UI] =
        val xChildren: Chunk[UI] = mark.x match
            case Present(RuleValue.Const(v, pl)) =>
                pl.asInstanceOf[Plottable[Any]].toDomain(v) match
                    case Present(d) =>
                        // For a category domain, place the vertical rule at the band center so the line
                        // bisects the bar/data point rather than aligning to the band left wall.
                        val leftEdge = xs.apply(d)
                        val px = d match
                            case Domain.Category(_) => leftEdge + xs.bandwidth / 2.0
                            case _                  => leftEdge
                        Chunk(Svg.line.x1(px).y1(layout.plotY).x2(px).y2(layout.plotBaseline))
                    case Absent => Chunk.empty
            case Present(RuleValue.Reactive(signal, pl)) =>
                // Unsafe: signal.asInstanceOf[Signal[Any]] is type-erasure-safe because RuleValue.Reactive
                // carries the matching Plottable that was paired with the signal at construction time.
                val reactiveChild: UI.Ast.Reactive[Svg.Line] =
                    signal.asInstanceOf[Signal[Any]].render: v =>
                        pl.asInstanceOf[Plottable[Any]].toDomain(v) match
                            case Present(d) =>
                                // For a category domain, place the vertical rule at the band center
                                // (left edge + half bandwidth) rather than the band left edge, so the
                                // line bisects the bar/data point rather than aligning to its left wall.
                                val leftEdge = xs.apply(d)
                                val px = d match
                                    case Domain.Category(_) => leftEdge + xs.bandwidth / 2.0
                                    case _                  => leftEdge
                                Svg.line.x1(px).y1(layout.plotY).x2(px).y2(layout.plotBaseline)
                            case Absent =>
                                // Absent value: emit a zero-length invisible line (not emitting nothing
                                // avoids a type mismatch; the renderer skips zero-length lines).
                                Svg.line.x1(0.0).y1(0.0).x2(0.0).y2(0.0)
                val wrapper: Svg.G = Svg.g(reactiveChild)
                Chunk(wrapper)
            case _ => Chunk.empty
        val yChildren: Chunk[UI] = mark.y match
            case Present(RuleValue.Const(v, pl)) =>
                pl.asInstanceOf[Plottable[Any]].toDomain(v) match
                    case Present(d) =>
                        val py = ys.apply(d)
                        Chunk(Svg.line.x1(layout.plotX).y1(py).x2(layout.plotX + layout.plotW).y2(py))
                    case Absent => Chunk.empty
            case Present(RuleValue.Reactive(signal, pl)) =>
                val reactiveChild: UI.Ast.Reactive[Svg.Line] =
                    signal.asInstanceOf[Signal[Any]].render: v =>
                        pl.asInstanceOf[Plottable[Any]].toDomain(v) match
                            case Present(d) =>
                                val py = ys.apply(d)
                                Svg.line.x1(layout.plotX).y1(py).x2(layout.plotX + layout.plotW).y2(py)
                            case Absent =>
                                Svg.line.x1(0.0).y1(0.0).x2(0.0).y2(0.0)
                val wrapper: Svg.G = Svg.g(reactiveChild)
                Chunk(wrapper)
            case _ => Chunk.empty
        xChildren ++ yChildren
    end lowerRuleChildren

    // ---- Phase 06: keyed transitions and animation ----

    /** Per-key geometry captured from one render frame.
      *
      * `Bar` stores the scaled height and y-coordinate so the next render can compute SMIL `from`/`to`
      * values for bar SMIL transitions.
      *
      * `LinePath` stores the full `PathData` produced by the previous emission. On the next emission,
      * if the new path has the same command count as the stored one (a structural match: same number
      * of MoveTo/LineTo/Close in the same order), a declarative SMIL `animate` is emitted on the `d`
      * attribute with `from` = the previous rendered `d` string and `to` = the new one. The browser
      * drives the interpolation; no fiber is required, which fits the pure `Svg.Root` lowering.
      *
      * If the command counts DIFFER (e.g. a category was added or removed), the path SNAPS with no
      * animate child. This is a documented v1 limitation: a structural path morph requires a bounded
      * stepped-interpolation fiber that can only be launched from an effectful mount hook, which the
      * pure `Svg.Root` lowering does not provide. `AnimateConfig.morphSteps` is reserved for a future
      * effectful chart mount API and is not used here.
      */
    sealed private[kyo] trait MarkGeom
    private[kyo] object MarkGeom:
        final case class Bar(height: Double, y: Double)   extends MarkGeom
        final case class LinePath(pathData: Svg.PathData) extends MarkGeom
    end MarkGeom

    /** Three-slot transition state for a live chart, held in a chart-private `AtomicRef.Unsafe`.
      *
      * Slots:
      *   - `lastRows`: the row chunk used in the most-recently-committed render.
      *   - `fromGeom`: the geometry that was current BEFORE the last row change (the animation origin).
      *   - `currentGeom`: the geometry produced BY the last row change (the animation target).
      *
      * The three-slot design makes the render projection idempotent across repeated pulls of the same
      * emission. On each call to `marksRegionWithTransitions(rows)`:
      *   - if `rows == lastRows` (repeat pull of the same emission): use the stored `fromGeom` and
      *     `currentGeom` unchanged; do NOT write the ref. Every repeat pull reproduces the same
      *     from-to pair.
      *   - else (a genuinely new emission): compute `newGeom` from the incoming rows; write
      *     `TransState(rows, fromGeom = currentGeom, currentGeom = newGeom)`. This write happens
      *     exactly once per distinct emission regardless of how many times the engine re-pulls it.
      *
      * Invariant: writes occur only when `rows != lastRows`. Repeated pulls of one emission are
      * therefore idempotent: they always see the same stable `fromGeom`/`currentGeom` pair and
      * produce identical SVG output. Writes are serialized by the reactive engine's single-threaded
      * emission model, so no concurrent-write hazard exists.
      */
    final private[kyo] case class TransState[A](
        lastRows: Chunk[A],
        fromGeom: Map[String, MarkGeom],
        currentGeom: Map[String, MarkGeom]
    )

    private[kyo] object TransState:
        /** Initial state: empty sentinel last-rows so the first genuine emission is always treated as new.
          *
          * Both geometry maps are empty, so every key in the first emission is an ENTER (animates from
          * the baseline). Repeat pulls of that first emission compare `rows == Chunk.empty` and reuse
          * the stored from/to, which is the correct stable ENTER animation.
          */
        def empty[A]: TransState[A] = TransState(Chunk.empty[A], Map.empty, Map.empty)
    end TransState

    /** Compute the string key for a row in a given mark, using the spec's key function or falling back to
      * the x channel.
      *
      * Default: the x channel's domain string (`domainKey`). Override: `spec.key` when `Present`.
      */
    private def rowKey[A](spec: ChartSpec[A], mark: Mark[A], row: A): String =
        spec.key match
            case Present(kf) => kf(row)
            case Absent =>
                mark match
                    case m: Mark.Bar[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => domainKey(d)
                            case Absent     => ""
                    case m: Mark.Line[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => domainKey(d)
                            case Absent     => ""
                    case m: Mark.Area[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => domainKey(d)
                            case Absent     => ""
                    case m: Mark.Point[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => domainKey(d)
                            case Absent     => ""
                    case m: Mark.Text[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => domainKey(d)
                            case Absent     => ""
                    case m: Mark.ErrorBar[A, ?, ?] =>
                        m.x.plottable.toDomain(m.x.accessor(row)) match
                            case Present(d) => domainKey(d)
                            case Absent     => ""
                    case _: Mark.Rule[A] => ""
    end rowKey

    /** Format a `Duration` as a CSS/SMIL duration string (e.g. "0.3s"). */
    private def formatDur(d: Duration): String =
        val ms = d.toMillis
        if ms % 1000 == 0 then s"${ms / 1000}s"
        else s"${ms / 1000.0}s"
    end formatDur

    /** Build one SMIL `Svg.Animate` child that animates a single numeric attribute over the config duration.
      *
      * Uses `begin("0s")` and `repeatCount("1")` so the animation plays once on re-render, matching the demo
      * pattern from BarChart.scala:206-207. N4-guard: no `url(#id)` refs.
      */
    private def smilAnimate(attributeName: String, from: Double, to: Double, dur: String)(using Frame): Svg.Animate =
        Svg.animate
            .attributeName(attributeName)
            .from(from)
            .to(to)
            .dur(dur)
            .begin("0s")
            .repeatCount("1")
    end smilAnimate

    /** Build one SMIL `Svg.Animate` child that animates the `d` path attribute using string `from`/`to`.
      *
      * Used for the declarative line/area path morph: when the previous and new paths have the same
      * command structure (same count and types), the browser interpolates the vertex coordinates.
      * N4-guard: no `url(#id)` refs.
      */
    private def smilAnimatePath(fromD: String, toD: String, dur: String)(using Frame): Svg.Animate =
        Svg.animate
            .attributeName("d")
            .from(fromD)
            .to(toD)
            .dur(dur)
            .begin("0s")
            .repeatCount("1")
    end smilAnimatePath

    /** Render a `PathData` to its SVG `d` attribute string, using the same format as `HtmlRenderer`.
      *
      * The result is identical to what `HtmlRenderer` would write for `svgAttr(sb, "d", ...)` so the
      * SMIL `from`/`to` strings round-trip correctly through the browser's SMIL engine. Integers are
      * rendered without a decimal point; fractions use the shortest representation.
      *
      * Used for path-morph SMIL `animate` children: the previous path's rendered string becomes `from`
      * and the new path's rendered string becomes `to`.
      */
    private def renderPathDataStr(d: Svg.PathData): String =
        def fmtD(v: Double): String = NumberFormat.double(v)
        def cmd(c: Svg.PathCommand): String = c match
            case Svg.PathCommand.MoveTo(x, y)   => s"M${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.MoveBy(dx, dy) => s"m${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.LineTo(x, y)   => s"L${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.LineBy(dx, dy) => s"l${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.HLineTo(x)     => s"H${fmtD(x)}"
            case Svg.PathCommand.HLineBy(dx)    => s"h${fmtD(dx)}"
            case Svg.PathCommand.VLineTo(y)     => s"V${fmtD(y)}"
            case Svg.PathCommand.VLineBy(dy)    => s"v${fmtD(dy)}"
            case Svg.PathCommand.CubicTo(c1x, c1y, c2x, c2y, x, y) =>
                s"C${fmtD(c1x)} ${fmtD(c1y)} ${fmtD(c2x)} ${fmtD(c2y)} ${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.CubicBy(c1x, c1y, c2x, c2y, dx, dy) =>
                s"c${fmtD(c1x)} ${fmtD(c1y)} ${fmtD(c2x)} ${fmtD(c2y)} ${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.SmoothCubicTo(c2x, c2y, x, y) =>
                s"S${fmtD(c2x)} ${fmtD(c2y)} ${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.SmoothCubicBy(c2x, c2y, dx, dy) =>
                s"s${fmtD(c2x)} ${fmtD(c2y)} ${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.QuadTo(cx, cy, x, y)   => s"Q${fmtD(cx)} ${fmtD(cy)} ${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.QuadBy(cx, cy, dx, dy) => s"q${fmtD(cx)} ${fmtD(cy)} ${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.SmoothQuadTo(x, y)     => s"T${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.SmoothQuadBy(dx, dy)   => s"t${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.ArcTo(rx, ry, xRot, largeArc, sweep, x, y) =>
                val la = if largeArc then 1 else 0
                val sw = if sweep then 1 else 0
                s"A${fmtD(rx)} ${fmtD(ry)} ${fmtD(xRot)} $la $sw ${fmtD(x)} ${fmtD(y)}"
            case Svg.PathCommand.ArcBy(rx, ry, xRot, largeArc, sweep, dx, dy) =>
                val la = if largeArc then 1 else 0
                val sw = if sweep then 1 else 0
                s"a${fmtD(rx)} ${fmtD(ry)} ${fmtD(xRot)} $la $sw ${fmtD(dx)} ${fmtD(dy)}"
            case Svg.PathCommand.Close => "Z"
        Svg.PathData.commands(d).map(cmd).mkString(" ")
    end renderPathDataStr

    /** Lower a simple bar mark with keyed enter/update SMIL transitions.
      *
      * For each row:
      *   - UPDATE (key in `fromGeom`): emit a rect with two `Svg.Animate` children: one for `height`
      *     (from previous height to new) and one for `y` (from previous y to new).
      *   - ENTER (key absent in `fromGeom`): emit a rect with two `Svg.Animate` children where `from`
      *     is the baseline (height=0, y=baseline), animating from the baseline up to the new position.
      *   - DISABLED (animation not enabled): emit the rect with no animate children.
      *
      * Accumulates the new per-key `MarkGeom.Bar` into `newGeom` and returns it alongside the shapes.
      * `fromGeom` is the stable animation-origin map for this emission (pre-computed by the caller from
      * the `TransState`; not modified here).
      */
    private def lowerBarSimpleWithTransitions[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultFill: Style.Color,
        spec: ChartSpec[A],
        fromGeom: Map[String, MarkGeom],
        newGeom: Map[String, MarkGeom]
    )(using Frame): (Chunk[Svg.SvgElement], Map[String, MarkGeom]) =
        val baseline = layout.plotBaseline
        val durStr   = formatDur(spec.animateCfg.duration)
        val animOk   = spec.animateCfg.enabled
        @scala.annotation.tailrec
        def loop(
            i: Int,
            acc: Chunk[Svg.SvgElement],
            geom: Map[String, MarkGeom]
        ): (Chunk[Svg.SvgElement], Map[String, MarkGeom]) =
            if i >= rows.size then (acc, geom)
            else
                val row     = rows(i)
                val yDomain = mark.y.plottable.toDomain(mark.y.accessor(row))
                val nextResult = yDomain match
                    case Absent => (acc, geom)
                    case Present(yd) =>
                        val xDomain = mark.x.plottable.toDomain(mark.x.accessor(row))
                        xDomain match
                            case Absent => (acc, geom)
                            case Present(xd) =>
                                val barX  = xs.apply(xd)
                                val barW  = xs.bandwidth
                                val barY  = ys.apply(yd)
                                val barH  = baseline - barY
                                val key   = rowKey(spec, mark, row)
                                val newG2 = geom.updated(key, MarkGeom.Bar(barH, barY))
                                val r: Svg.Rect =
                                    if !animOk then
                                        Svg.rect.x(barX).y(barY).width(barW).height(barH).fill(Svg.Paint.Color(defaultFill))
                                    else
                                        val (fromH, fromY) = fromGeom.get(key) match
                                            case Some(MarkGeom.Bar(ph, py)) => (ph, py)
                                            case _                          => (0.0, baseline) // enter from baseline
                                        val rectBase = Svg.rect.x(barX).y(barY).width(barW).height(barH).fill(Svg.Paint.Color(defaultFill))
                                        rectBase(
                                            smilAnimate("height", fromH, barH, durStr),
                                            smilAnimate("y", fromY, barY, durStr)
                                        )
                                (acc.append(r), newG2)
                        end match
                loop(i + 1, nextResult._1, nextResult._2)
        loop(0, Chunk.empty, newGeom)
    end lowerBarSimpleWithTransitions

    /** Lower a line mark with keyed-transition awareness, emitting a declarative SMIL path morph when
      * the previous and new paths have the same command structure.
      *
      * When animation is enabled and a previous `MarkGeom.LinePath` entry exists in `fromGeom` for the
      * same path slot, the command counts of the previous and new paths are compared:
      *   - Same count (structural match, stable x-categories, changing y-values): the path is emitted
      *     with one `Svg.animate` child `attributeName="d" from={prevD} to={newD}`. The browser drives
      *     the interpolation declaratively; no fiber or mount hook is required.
      *   - Different count (structural change, e.g. a category added or removed): the path snaps with no
      *     animate child. This is a documented v1 limitation: a structural path morph requires a bounded
      *     stepped-interpolation fiber that can only be launched from an effectful mount hook, which the
      *     pure `Svg.Root` lowering does not provide. `AnimateConfig.morphSteps` is reserved for a
      *     future effectful chart mount API.
      *
      * The current `PathData` is always recorded in `newGeom` under `"line-{index}"` keys so the next
      * emission can use it as the `from` path.
      *
      * N4-guard: no `url(#id)` refs.
      */
    private def lowerLineWithTransitions[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Line[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        spec: ChartSpec[A],
        fromGeom: Map[String, MarkGeom],
        newGeom: Map[String, MarkGeom]
    )(using Frame): (Chunk[Svg.SvgElement], Map[String, MarkGeom]) =
        val animOk = spec.animateCfg.enabled
        val durStr = formatDur(spec.animateCfg.duration)
        // Compute the raw paths first (same as non-transition path but we need the PathData for morph).
        val rawPaths: Chunk[Svg.Path] = mark.color match
            case Absent =>
                Chunk(lowerLineSeries(rows, mark, layout, xs, ys, defaultColor))
            case Present(colorCh) =>
                val colorKeys: Chunk[String] = rows.foldLeft(Chunk.empty[String]): (acc, row) =>
                    val key = colorCh.accessor(row.asInstanceOf[A]).toString
                    if acc.toSeq.contains(key) then acc else acc.append(key)
                colorKeys.zipWithIndex.map: (key, idx) =>
                    val seriesRows  = rows.filter(r => colorCh.accessor(r).toString == key)
                    val strokeColor = DefaultPalette.toSeq.apply(idx % DefaultPalette.size)
                    lowerLineSeries(seriesRows, mark, layout, xs, ys, strokeColor)
        // Build the base geom key offset to avoid collision with earlier-pass keys.
        val keyOffset = newGeom.size
        // For each raw path: optionally attach a SMIL animate on `d`, then record the new PathData.
        val (elems, updatedGeom) = rawPaths.foldLeft((Chunk.empty[Svg.SvgElement], newGeom)):
            case ((accElems, accGeom), rawPath) =>
                val idx      = accElems.size
                val pathKey  = "line-" + (keyOffset + idx).toString
                val newPd    = rawPath.svgAttrs.d.getOrElse(Svg.PathData.empty)
                val newGeom2 = accGeom.updated(pathKey, MarkGeom.LinePath(newPd))
                val emittedPath: Svg.SvgElement =
                    if !animOk then rawPath
                    else
                        fromGeom.get(pathKey) match
                            case Some(MarkGeom.LinePath(prevPd)) =>
                                val prevCount = Svg.PathData.commands(prevPd).size
                                val newCount  = Svg.PathData.commands(newPd).size
                                if prevCount == newCount && prevCount > 0 then
                                    // Structural match: same command count -> declarative SMIL morph.
                                    val fromD = renderPathDataStr(prevPd)
                                    val toD   = renderPathDataStr(newPd)
                                    rawPath(smilAnimatePath(fromD, toD, durStr))
                                else
                                    // Structural change (category added/removed): path snaps, no animate.
                                    rawPath
                                end if
                            case _ =>
                                // No previous path for this slot (first emission or new series): snap.
                                rawPath
                (accElems.append(emittedPath), newGeom2)
        (elems, updatedGeom)
    end lowerLineWithTransitions

    /** Lower an area mark with keyed-transition awareness, emitting a declarative SMIL path morph when
      * the previous and new paths have the same command structure.
      *
      * Mirrors `lowerLineWithTransitions` for area paths. A simple (non-stacked) area mark produces one
      * closed path; the SMIL morph fires when the previous path's command count equals the new one.
      *
      * Stacked area marks fall through to the plain `lowerArea` because multi-path stacking uses
      * per-group path indices that would need per-group geometry tracking not yet plumbed in v1.
      *
      * Structural path morphs (different command count) snap: see `lowerLineWithTransitions` scaladoc
      * for the v1 limitation note.
      *
      * N4-guard: no `url(#id)` refs.
      */
    private def lowerAreaWithTransitions[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Area[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        spec: ChartSpec[A],
        fromGeom: Map[String, MarkGeom],
        newGeom: Map[String, MarkGeom]
    )(using Frame): (Chunk[Svg.SvgElement], Map[String, MarkGeom]) =
        // Stacked area: fall through to plain lowerArea (no per-group path tracking yet).
        val isStacked = mark.y.isDefined && mark.stack.group.isDefined
        if isStacked then
            val elems = lowerArea(rows, mark, layout, xs, ys, defaultColor)
            (elems, newGeom)
        else
            val animOk = spec.animateCfg.enabled
            val durStr = formatDur(spec.animateCfg.duration)
            val rawPaths: Chunk[Svg.Path] = lowerArea(rows, mark, layout, xs, ys, defaultColor).collect:
                case p: Svg.Path => p
            val keyOffset = newGeom.size
            val (elems, updatedGeom) = rawPaths.foldLeft((Chunk.empty[Svg.SvgElement], newGeom)):
                case ((accElems, accGeom), rawPath) =>
                    val idx      = accElems.size
                    val pathKey  = "area-" + (keyOffset + idx).toString
                    val newPd    = rawPath.svgAttrs.d.getOrElse(Svg.PathData.empty)
                    val newGeom2 = accGeom.updated(pathKey, MarkGeom.LinePath(newPd))
                    val emittedPath: Svg.SvgElement =
                        if !animOk then rawPath
                        else
                            fromGeom.get(pathKey) match
                                case Some(MarkGeom.LinePath(prevPd)) =>
                                    val prevCount = Svg.PathData.commands(prevPd).size
                                    val newCount  = Svg.PathData.commands(newPd).size
                                    if prevCount == newCount && prevCount > 0 then
                                        val fromD = renderPathDataStr(prevPd)
                                        val toD   = renderPathDataStr(newPd)
                                        rawPath(smilAnimatePath(fromD, toD, durStr))
                                    else
                                        rawPath
                                    end if
                                case _ =>
                                    rawPath
                    (accElems.append(emittedPath), newGeom2)
            (elems, updatedGeom)
        end if
    end lowerAreaWithTransitions

    /** Build the marks region `Svg.G` for a reactive emission with keyed enter/update transitions.
      *
      * `stateRef` is the chart-private `AtomicRef.Unsafe[TransState[A]]` created once in `lowerLive`.
      * On each call:
      *   1. Read the current `TransState` `t`.
      *   2. If `rows.equals(t.lastRows)` (repeat pull of the same emission): use `t.fromGeom` as the
      *      animation origin and `t.currentGeom` as the target; do NOT write `stateRef`. Every repeat
      *      pull sees the same stable pair and produces identical SVG output.
      *   3. If `rows` differ from `t.lastRows` (genuinely new emission): produce shapes AND geometry
      *      in a single pass; write `TransState(rows, fromGeom = t.currentGeom, currentGeom = newGeom)`
      *      exactly once.
      *
      * The `==` comparison uses Chunk's structural equality. Writes are serialized by the reactive
      * engine's single-threaded emission model, so no concurrent-write hazard exists.
      *
      * Unsafe: `stateRef.get()` and `stateRef.set()` bypass kyo effects tracking. This is sound
      * because the ref is private to this chart instance and writes occur only on genuine row changes.
      */
    private def marksRegionWithTransitions[A](
        rows: Chunk[A],
        spec: ChartSpec[A],
        layout: Layout,
        xs: Scale,
        ysL: Scale,
        ysR: Maybe[Scale],
        stateRef: AtomicRef.Unsafe[TransState[A]]
    )(using Frame): Svg.G =
        import AllowUnsafe.embrace.danger
        val t            = stateRef.get()
        val animOk       = spec.animateCfg.enabled
        val isRepeatPull = rows.equals(t.lastRows)
        // The animation origin is the geometry from the previous emission.
        // For a repeat pull it is t.fromGeom (already stored); for a new emission it is t.currentGeom.
        val fromGeom: Map[String, MarkGeom] =
            if isRepeatPull then t.fromGeom else t.currentGeom
        // Produce SVG shapes and accumulate new geometry in a single pass.
        // Each mark with no explicit color channel uses a distinct palette color by its index (per-mark default).
        val (shapes, newGeom) = spec.marks.zipWithIndex.foldLeft((Chunk.empty[Svg.SvgElement], Map.empty[String, MarkGeom])):
            case ((accElems, accGeom), (mark, markIdx)) =>
                val markColor = markDefaultColor(spec.theme, markIdx)
                val ys = mark match
                    case m: Mark.Bar[A, ?, ?]      => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                    case m: Mark.Line[A, ?, ?]     => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                    case m: Mark.Area[A, ?, ?]     => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                    case m: Mark.Point[A, ?, ?]    => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                    case m: Mark.Rule[A]           => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                    case m: Mark.Text[A, ?, ?]     => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                    case m: Mark.ErrorBar[A, ?, ?] => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                mark match
                    case m: Mark.Bar[A, ?, ?] if m.stack.group.isEmpty && m.color.isEmpty =>
                        // Simple ungrouped bar: supports keyed SMIL transitions.
                        // fromGeom is the stable animation origin for this call (same on every pull of this emission).
                        val (elems, geom) = lowerBarSimpleWithTransitions(rows, m, layout, xs, ys, markColor, spec, fromGeom, accGeom)
                        (accElems ++ elems, geom)
                    case m: Mark.Line[A, ?, ?] if animOk =>
                        // Line path: declarative SMIL `d` morph when command counts match; snaps otherwise.
                        // fromGeom carries the previous PathData for the `from` side of the SMIL animate.
                        val (elems, geom) = lowerLineWithTransitions(rows, m, layout, xs, ys, markColor, spec, fromGeom, accGeom)
                        (accElems ++ elems, geom)
                    case m: Mark.Area[A, ?, ?] if animOk =>
                        // Area path: same declarative SMIL `d` morph discipline as line.
                        val (elems, geom) = lowerAreaWithTransitions(rows, m, layout, xs, ys, markColor, spec, fromGeom, accGeom)
                        (accElems ++ elems, geom)
                    case m: Mark.Bar[A, ?, ?]  => (accElems ++ lowerBar(rows, m, layout, xs, ys, markColor, spec), accGeom)
                    case m: Mark.Line[A, ?, ?] => (accElems ++ lowerLine(rows, m, layout, xs, ys, markColor), accGeom)
                    case m: Mark.Area[A, ?, ?] => (accElems ++ lowerArea(rows, m, layout, xs, ys, markColor), accGeom)
                    case m: Mark.Point[A, ?, ?] =>
                        (
                            accElems ++ lowerPoint(
                                rows,
                                m,
                                layout,
                                xs,
                                ys,
                                markColor,
                                Present(spec),
                                Absent,
                                spec.theme,
                                resolveHighlight(Present(spec))
                            ),
                            accGeom
                        )
                    case m: Mark.Rule[A] => (accElems ++ lowerRule(m, layout, xs, ys), accGeom)
                    // INV-021/INV-022: text/errorBar produce no geometry for morph tracking; elements are emitted.
                    case m: Mark.Text[A, ?, ?] =>
                        (accElems ++ lowerText(m, rows, xs, ys, markColor, spec.theme), accGeom)
                    case m: Mark.ErrorBar[A, ?, ?] =>
                        (accElems ++ lowerErrorBar(m, rows, xs, ys, markColor, spec.theme), accGeom)
                end match
        // Write the new state only when this is a genuinely new emission.
        // On repeat pulls, the ref is left untouched so the next real emission still sees the correct fromGeom.
        if !isRepeatPull then
            stateRef.set(TransState(rows, fromGeom = t.currentGeom, currentGeom = newGeom))
        shapes.foldLeft(Svg.g): (g, el) =>
            el match
                case r: Svg.Rect   => g(r)
                case p: Svg.Path   => g(p)
                case c: Svg.Circle => g(c)
                case l: Svg.Line   => g(l)
                case inner: Svg.G  => g(inner)
                case other         => g(other.asInstanceOf[Svg.SvgElement & Svg.SvgChild])
    end marksRegionWithTransitions

    // ---- reactive helpers ----

    /** Returns true when the y-scale's domain is fully determined by the `ScaleOverride` (not the data).
      *
      * A fixed domain means the y-axis ticks are independent of the live rows and can be drawn once in the
      * static frame. An inferred domain means the ticks must live inside the reactive region so they update
      * when the data changes.
      */
    private def isYDomainFixed(yOverride: Maybe[ScaleOverride]): Boolean =
        yOverride.flatMap(_.kind) match
            case Present(ScaleKind.Linear(_, _)) => true
            case Present(ScaleKind.Log)          => true
            case _                               => false
    end isYDomainFixed

    /** Build the domain-independent portion of the static frame for a live chart.
      *
      * Includes: background rect and axis lines. If the y-domain is fixed (by a `ScaleOverride`),
      * also includes the full y-axis (ticks, labels, gridlines); otherwise the y-axis is omitted here and
      * included inside the reactive region where it can update with the data. The legend is data-dependent and
      * lives inside the reactive region, not here.
      *
      * The x-axis ticks and labels are NOT included in the static frame: they depend on the live x-scale
      * (which tracks the current category set) and are therefore placed inside the reactive region by
      * `buildReactiveRegion`. Only the axis border lines (bottom and left) are static.
      *
      * No `url(#id)` references are emitted (N4-guard satisfied).
      */
    private[kyo] def buildStaticFrameLive[A](
        layout: Layout,
        xs: Scale,
        ysL: Scale,
        ysR: Maybe[Scale],
        spec: ChartSpec[A],
        initialRows: Chunk[A]
    )(using Frame): Chunk[Svg.SvgElement] =
        val leftChrome  = axisChromeColorFor(spec.theme, spec.marks, Axis.Left)
        val rightChrome = axisChromeColorFor(spec.theme, spec.marks, Axis.Right)
        val gridColor   = gridlineColor(spec.theme)
        val background  = buildBackground(layout, spec.theme)
        val axisLines   = buildAxisLines(layout, ysR, spec.theme, leftChrome, rightChrome)
        // The legend is NOT built here: it is data-dependent and lives inside the reactive region, where it
        // reflects each emission's live category set. Building it from a one-shot sample would freeze it to the
        // first emission's categories.
        val yAxisElems: Chunk[Svg.SvgElement] =
            if isYDomainFixed(spec.yScaleOverride) then
                val leftAxis = buildYAxis(layout, ysL, spec.yAxisCfg, isRight = false, spec.theme, leftChrome, gridColor)
                val rightAxis = ysR match
                    case Present(ysR_) =>
                        buildYAxis(
                            layout,
                            ysR_,
                            spec.yAxisRightCfg.getOrElse(AxisConfig.default),
                            isRight = true,
                            spec.theme,
                            rightChrome,
                            gridColor
                        )
                    case Absent => Chunk.empty
                leftAxis ++ rightAxis
            else Chunk.empty
        background ++ axisLines ++ yAxisElems
    end buildStaticFrameLive

    /** Build the reactive region `Svg.G` for one emission of the live signal.
      *
      * The x-axis ticks are always included here so they use the same live `xs` scale that drives the marks:
      * for a band (categorical) x-axis the tick labels must match the actual category keys, which come from
      * the data, not from an empty-rows initial resolution. Building them from `Chunk.empty` would produce a
      * Linear [0,1] fallback and emit numeric labels (0, 0.25, ...) instead of the band category labels.
      *
      * When the y-domain is inferred from the data, the y-axis ticks are also included here so they update
      * with the data range. When the y-domain is fixed, only the marks and x-axis are included (the static
      * y-axis already lives in the outer frame).
      *
      * The returned `Svg.G` is the child that the engine replaces on each signal emission.
      */
    private def buildReactiveRegion[A](
        rows: Chunk[A],
        spec: ChartSpec[A],
        layout: Layout,
        xs: Scale,
        ysL: Scale,
        ysR: Maybe[Scale],
        stateRef: Maybe[AtomicRef.Unsafe[TransState[A]]],
        gradPrefix: String,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Svg.G =
        // Interactive hidden-series filter (INV-026): the marks drop rows whose color label is hidden; the
        // legend keeps every category (built from the full emission rows) so a hidden series can be toggled on.
        val visibleRows = visibleRowsFor(rows, spec)
        val marksG = stateRef match
            case Present(ref) => marksRegionWithTransitions(visibleRows, spec, layout, xs, ysL, ysR, ref)
            case Absent       => marksRegion(visibleRows, spec.marks, layout, xs, ysL, ysR, Present(spec), internalHoverRef)
        // Live legend (INV-029): built per emission from the full rows so it reflects the current category set.
        val legendElems = buildLegend(layout, spec, rows, gradPrefix)
        val xAxisElems  = buildXAxis(layout, xs, spec.xAxisCfg, spec.theme)
        if isYDomainFixed(spec.yScaleOverride) then
            // Fixed y-domain: y-axis is static; only x-axis ticks, legend, and marks are reactive.
            val xAxisG = (xAxisElems ++ legendElems).foldLeft(Svg.g): (g, el) =>
                el match
                    case l: Svg.Line => g(l)
                    case t: Svg.Text => g(t)
                    case other       => g(other.asInstanceOf[Svg.SvgElement & Svg.SvgChild])
            Svg.g(xAxisG)(marksG)
        else
            // Inferred domain: include y-axis ticks, x-axis ticks, and the legend inside the reactive region.
            val leftChrome    = axisChromeColorFor(spec.theme, spec.marks, Axis.Left)
            val rightChrome   = axisChromeColorFor(spec.theme, spec.marks, Axis.Right)
            val gridColor     = gridlineColor(spec.theme)
            val leftAxisElems = buildYAxis(layout, ysL, spec.yAxisCfg, isRight = false, spec.theme, leftChrome, gridColor)
            val rightAxisElems = ysR match
                case Present(ysR_) =>
                    buildYAxis(
                        layout,
                        ysR_,
                        spec.yAxisRightCfg.getOrElse(AxisConfig.default),
                        isRight = true,
                        spec.theme,
                        rightChrome,
                        gridColor
                    )
                case Absent => Chunk.empty
            val allElems: Chunk[Svg.SvgElement] = leftAxisElems ++ rightAxisElems ++ xAxisElems ++ legendElems
            val axisG = allElems.foldLeft(Svg.g): (g, el) =>
                el match
                    case l: Svg.Line => g(l)
                    case t: Svg.Text => g(t)
                    case other       => g(other.asInstanceOf[Svg.SvgElement & Svg.SvgChild])
            Svg.g(axisG)(marksG)
        end if
    end buildReactiveRegion

    /** Drop rows whose color-channel label is in the interactive legend's hidden set (INV-026).
      *
      * When the legend is not interactive (or no `hiddenSeries` ref is attached), all rows are returned. The
      * hidden labels are read synchronously from the user's ref; the label derivation mirrors the legend's
      * (`accessor(row).toString`), so toggling a swatch hides exactly the rows that swatch represents.
      */
    private def visibleRowsFor[A](rows: Chunk[A], spec: ChartSpec[A]): Chunk[A] =
        spec.legendCfg.hiddenSeries match
            case Absent       => rows
            case Present(ref) =>
                // Unsafe: a synchronous read of the user's SignalRef. Sound because this runs inside the pure
                // reactive projection (buildReactiveRegion) / synchronous static lowering; the ref is read, not
                // written, and the filter is a pure function of its current value.
                import AllowUnsafe.embrace.danger
                val hidden = ref.unsafe.get()
                if hidden.isEmpty then rows
                else
                    legendChannel(spec.marks) match
                        case Present(ch) => rows.filter(r => !hidden.contains(ch.accessor(r).toString))
                        case Absent      => rows
                end if
    end visibleRowsFor

    /** Lower a `ChartSpec[A]` with a `DataSource.Live` signal to an `Svg.Root`.
      *
      * The static frame (background, axis lines, and the y-axis when the domain is fixed) is drawn once. The
      * marks region, the legend, the x-axis, and (when the domain is inferred) the y-axis ticks are wrapped in
      * `signal.render` so they re-render on each emission. The legend reflects each emission's live category set.
      *
      * Partition logic:
      *   - Fixed domain (`yScale(_.linear(lo,hi))` or `yScale(_.log)`): the y-scale is computed once from the
      *     override, so the y-axis is static. Only the marks `Svg.G` is reactive.
      *   - Inferred domain (default): the y-scale is recomputed from each new batch of rows. Both the y-axis
      *     ticks and the marks live inside the reactive `Svg.G` so the tick labels update with the data.
      *
      * The x-scale is always computed from the first emission via `initialRows`. For a categorically-typed x
      * axis (band scale) the category set is fixed to the initial categories; this is the Phase-05 constraint
      * (dynamic category insertion is a Phase-06+ concern).
      *
      * Phase 06: when `spec.animateCfg.enabled` is true, a chart-private `AtomicRef.Unsafe[TransState[A]]`
      * is created once and closed over by the reactive render function. The `TransState` carries three
      * slots: `lastRows` (the row chunk of the last committed render), `fromGeom` (animation origin), and
      * `currentGeom` (animation target). Each call to the render projection compares the incoming rows to
      * `lastRows`. A new emission writes the ref once; repeat pulls of the same emission reuse the stored
      * from/to and produce identical SVG, making the projection idempotent. Bar marks emit SMIL `animate`
      * children; line/area marks snap (no animate) in Phase 06 with the bounded stepped-morph tween
      * deferred to Phase 08.
      *
      * N4-guard: no `url(#id)` references are emitted; `Frame.internal` is safe.
      *
      * Unsafe boundary: `stateRef` uses `AtomicRef.Unsafe` so it can be read and written from within the
      * pure render function. The ref is private to this chart instance and writes occur only on genuine row
      * changes, so the bypass is sound.
      */
    private[kyo] def lowerLive[A](spec: ChartSpec[A], signal: Signal[Chunk[A]], gradPrefix: String)(using Frame): Svg.Root =
        val layout = buildLayout(spec)
        // Use a fixed initial row set for the layout/x-scale/ysR-presence check.
        // For Phase 05 the x-scale is computed from the signal's current value via initConst or the first
        // emission. We resolve from Chunk.empty to get a stable layout; the reactive region re-resolves the
        // y-scale per emission.
        val initialRows: Chunk[A] = Chunk.empty
        val hasRight = spec.marks.exists:
            case m: Mark.Bar[A, ?, ?]      => m.axis == Axis.Right
            case m: Mark.Line[A, ?, ?]     => m.axis == Axis.Right
            case m: Mark.Area[A, ?, ?]     => m.axis == Axis.Right
            case m: Mark.Point[A, ?, ?]    => m.axis == Axis.Right
            case _: Mark.Rule[A]           => false
            case _: Mark.Text[A, ?, ?]     => false
            case _: Mark.ErrorBar[A, ?, ?] => false
        val computeRight = hasRight || spec.yAxisRightCfg.isDefined
        // Resolve initial scales for the static frame using resolveAllScales (INV-004).
        val ResolvedScales(xs, ysLInitial, ysRFixed) =
            resolveAllScales(
                initialRows,
                spec.marks,
                layout,
                spec.xScaleOverride,
                spec.yScaleOverride,
                computeRight,
                spec.xAxisCfg,
                spec.yAxisCfg
            )
        // For fixed domain, compute ysL once from the override; for inferred domain, ysL is recomputed per
        // emission inside the reactive region.
        val ysLFixed: Maybe[Scale] =
            if isYDomainFixed(spec.yScaleOverride) then Present(ysLInitial) else Absent
        // Static frame: drawn once, never changes.
        val ysLForFrame = ysLFixed.getOrElse(ysLInitial)
        val staticFrame = buildStaticFrameLive(layout, xs, ysLForFrame, ysRFixed, spec, initialRows)
        val vb          = Svg.ViewBox(0.0, 0.0, layout.svgW, layout.svgH)
        val baseSvg     = Svg.svg.width(layout.svgW).height(layout.svgH).viewBox(vb)
        val withFrame = staticFrame.foldLeft(baseSvg): (acc, el) =>
            el match
                case r: Svg.Rect => acc(r)
                case l: Svg.Line => acc(l)
                case t: Svg.Text => acc(t)
                case g: Svg.G    => acc(g)
                case other       => acc(other.asInstanceOf[Svg.SvgElement & Svg.SvgChild])
        // Phase 06: create the chart-private transition-state ref when animation is enabled.
        // Unsafe: AtomicRef.Unsafe bypasses kyo effects tracking; sound because the ref is private to this
        // chart and writes occur only on genuine row changes (idempotent within any single emission).
        val stateRefMaybe: Maybe[AtomicRef.Unsafe[TransState[A]]] =
            if spec.animateCfg.enabled then
                import AllowUnsafe.embrace.danger
                Present(AtomicRef.Unsafe.init(TransState.empty[A]))
            else Absent
        // Phase 07: create an internal hover ref when a tooltip is configured so shape handlers can drive it.
        // Unsafe: SignalRef.Unsafe.init bypasses kyo effects; sound because the ref is private to this chart.
        val internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = spec.tooltip match
            case Present(_) =>
                import AllowUnsafe.embrace.danger
                given CanEqual[Maybe[A], Maybe[A]] = CanEqual.derived
                Present(Signal.SignalRef.Unsafe.init[Maybe[A]](Absent).safe)
            case Absent => Absent
        // Reactive region: re-renders on each signal emission.
        val reactiveMarks: UI.Ast.Reactive[Svg.G] = signal.render: rows =>
            // Re-resolve scales from the current rows so categorical axes expand when categories are added.
            val ResolvedScales(xsLive, ysLLiveResolved, ysRLive) =
                resolveAllScales(
                    rows,
                    spec.marks,
                    layout,
                    spec.xScaleOverride,
                    spec.yScaleOverride,
                    computeRight,
                    spec.xAxisCfg,
                    spec.yAxisCfg
                )
            val ysLLive = ysLFixed.getOrElse(ysLLiveResolved)
            buildReactiveRegion(rows, spec, layout, xsLive, ysLLive, ysRLive, stateRefMaybe, gradPrefix, internalHoverRef)
        val withMarks = withFrame(reactiveMarks)
        // Phase 07: append the tooltip overlay as the last child so it renders on top.
        (spec.tooltip, internalHoverRef) match
            case (Present(fn), Present(ref)) =>
                withMarks(buildTooltipOverlay(ref, fn, layout))
            case _ =>
                withMarks
        end match
    end lowerLive

    // ---- main entry point ----

    /** Lower a `ChartSpec[A]` to an `Svg.Root`.
      *
      * Dispatches to `lowerStatic` for `DataSource.Static` and `lowerLive` for `DataSource.Live`.
      *
      * A document-unique id prefix is allocated once per `lower` call (`nextChartInstancePrefix`) so a
      * sequential-color gradient def gets an id that is unique per chart INSTANCE, even when two charts on one
      * page have identical structural hashes. Within one chart the gradient def id and its `url(#id)` reference
      * always match (the def carries the prefix-derived `defId`, the swatch fill references the same id), and
      * two charts in one document never collide.
      */
    private[kyo] def lower[A](spec: ChartSpec[A]): Svg.Root =
        given Frame    = Frame.internal
        val gradPrefix = ChartFoundations.nextChartInstancePrefix()
        spec.data match
            case DataSource.Static(rows) => lowerStatic(rows, spec, gradPrefix)
            case DataSource.Live(signal) => lowerLive(spec, signal, gradPrefix)
        end match
    end lower

    /** Build a tooltip overlay `Reactive[Svg.G]` driven by the chart's internal hover ref.
      *
      * When the hover ref holds `Present(row)`, the overlay renders a translucent rect background and
      * a text label formatted by `spec.tooltip`. When `Absent`, it renders an empty `Svg.g`. The
      * overlay is placed in the upper-left of the plot area and drawn last so it appears on top.
      *
      * The `internalHoverRef` is created once per chart instance (in `lowerStatic`/`lowerLive`) and
      * is separate from `spec.onHover` so the tooltip can work without a user-visible hover ref.
      *
      * N4-guard: no `url(#id)` references are emitted (plain rect + text).
      */
    private def buildTooltipOverlay[A](
        internalHoverRef: Signal.SignalRef[Maybe[A]],
        tooltipFn: A => String,
        layout: Layout
    )(using Frame): UI.Ast.Reactive[Svg.G] =
        internalHoverRef.render:
            case Present(row) =>
                val label = tooltipFn(row)
                val tipX  = layout.plotX + 8.0
                val tipY  = layout.plotY + 8.0
                Svg.g(
                    Svg.rect
                        .x(tipX - 4.0)
                        .y(tipY - 14.0)
                        .width((label.length * 7.0 + 8.0) max 40.0)
                        .height(20.0)
                        .fill(Svg.Paint.Color(Style.Color.white))
                        .fillOpacity(0.85),
                    Svg.text
                        .x(tipX)
                        .y(tipY)
                        .dominantBaseline(Svg.DominantBaseline.Auto)
                        .apply(label)
                )
            case Absent =>
                Svg.g
    end buildTooltipOverlay

    private def lowerStatic[A](rows: Chunk[A], spec: ChartSpec[A], gradPrefix: String)(using Frame): Svg.Root =
        val layout = buildLayout(spec)
        val hasRight = spec.marks.exists:
            case m: Mark.Bar[A, ?, ?]      => m.axis == Axis.Right
            case m: Mark.Line[A, ?, ?]     => m.axis == Axis.Right
            case m: Mark.Area[A, ?, ?]     => m.axis == Axis.Right
            case m: Mark.Point[A, ?, ?]    => m.axis == Axis.Right
            case _: Mark.Rule[A]           => false
            case _: Mark.Text[A, ?, ?]     => false
            case _: Mark.ErrorBar[A, ?, ?] => false
        val computeRight = hasRight || spec.yAxisRightCfg.isDefined
        val ResolvedScales(xs, ysL, ysR) =
            resolveAllScales(rows, spec.marks, layout, spec.xScaleOverride, spec.yScaleOverride, computeRight, spec.xAxisCfg, spec.yAxisCfg)
        val vb = Svg.ViewBox(0.0, 0.0, layout.svgW, layout.svgH)
        // Interactive hidden-series filter (INV-026): the marks drop hidden rows; the legend keeps every
        // category (built from the full rows) so the user can still toggle a hidden series back on.
        val visibleRows = visibleRowsFor(rows, spec)
        val frame       = buildFrame(layout, xs, ysL, ysR, spec, rows, gradPrefix)
        // Phase 07: create an internal hover ref when a tooltip is configured so shape handlers can drive it.
        // Unsafe: SignalRef.Unsafe.init bypasses kyo effects; sound because the ref is private to this chart.
        val internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = spec.tooltip match
            case Present(_) =>
                import AllowUnsafe.embrace.danger
                given CanEqual[Maybe[A], Maybe[A]] = CanEqual.derived
                Present(Signal.SignalRef.Unsafe.init[Maybe[A]](Absent).safe)
            case Absent => Absent
        val marksG  = marksRegion(visibleRows, spec.marks, layout, xs, ysL, ysR, Present(spec), internalHoverRef)
        val baseSvg = Svg.svg.width(layout.svgW).height(layout.svgH).viewBox(vb)
        val withFrame = frame.foldLeft(baseSvg): (acc, el) =>
            el match
                case r: Svg.Rect => acc(r)
                case l: Svg.Line => acc(l)
                case t: Svg.Text => acc(t)
                case g: Svg.G    => acc(g)
                case other       => acc(other.asInstanceOf[Svg.SvgElement & Svg.SvgChild])
        val withMarks = withFrame(marksG)
        // Phase 07: append the tooltip overlay as the last child so it renders on top.
        (spec.tooltip, internalHoverRef) match
            case (Present(fn), Present(ref)) =>
                withMarks(buildTooltipOverlay(ref, fn, layout))
            case _ =>
                withMarks
        end match
    end lowerStatic

end ChartLower
