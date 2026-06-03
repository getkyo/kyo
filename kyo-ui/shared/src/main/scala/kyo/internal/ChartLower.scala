package kyo.internal

import kyo.*
import kyo.UI.render

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
        val legendPad = if hasLegend then LegendReservedH else 0.0
        Layout(
            svgW = w.toDouble,
            svgH = h.toDouble,
            plotX = MarginLeft,
            plotY = MarginTop + legendPad,
            plotW = w.toDouble - MarginLeft - marginRight,
            plotH = h.toDouble - MarginTop - legendPad - MarginBottom
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
                    case _: Mark.Text[A, ?, ?]     => Absent // DEV: Text/ErrorBar lowering lands in Phase 4 (placeholder)
                    case _: Mark.ErrorBar[A, ?, ?] => Absent // DEV: Text/ErrorBar lowering lands in Phase 4 (placeholder)
                    case _                         => Absent
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
                    case _: Mark.Text[A, ?, ?]     => Absent // DEV: Text/ErrorBar lowering lands in Phase 4 (placeholder)
                    case _: Mark.ErrorBar[A, ?, ?] => Absent // DEV: Text/ErrorBar lowering lands in Phase 4 (placeholder)
                    case _                         => Absent
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
                    case _: Mark.Text[A, ?, ?]     => Absent // DEV: Text/ErrorBar lowering lands in Phase 4 (placeholder)
                    case _: Mark.ErrorBar[A, ?, ?] => Absent // DEV: Text/ErrorBar lowering lands in Phase 4 (placeholder)
                    case _                         => Absent
                val merged = mergeExtents(acc, markExtent)
                loop(i + 1, merged)
        loop(0, Absent)
    end yRightExtent

    /** Compute the stacked y-extent for a Bar mark with a stack grouping.
      *
      * For each distinct x value, sums the y contributions of all stack groups and returns the maximum sum as the
      * extent upper bound (lower bound is zero after `ensureZero`).
      */
    private def stackedYExtent[A, X, Y](rows: Chunk[A], mark: Mark.Bar[A, X, Y]): Maybe[Extent] =
        val groupFn = mark.stack.group.getOrElse((_: A) => "")
        // Build: xKey -> totalY
        @scala.annotation.tailrec
        def loop(i: Int, totals: Map[String, Double]): Map[String, Double] =
            if i >= rows.size then totals
            else
                val row = rows(i)
                val xKey = mark.x.plottable.toDomain(mark.x.accessor(row)) match
                    case Present(d) => domainKey(d)
                    case Absent     => ""
                val yVal = mark.y.plottable.toDomain(mark.y.accessor(row)) match
                    case Present(Domain.Continuous(v)) => v
                    case _                             => 0.0
                val newTotal = totals.getOrElse(xKey, 0.0) + yVal
                loop(i + 1, totals.updated(xKey, newTotal))
        val totals = loop(0, Map.empty)
        if totals.isEmpty then Absent
        else
            val maxTotal = totals.values.fold(0.0)(math.max)
            Present(Extent.Continuous(0.0, maxTotal))
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
        rows: Chunk[A]
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
        val legendElems = buildLegend(layout, spec, rows)
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
        rows: Chunk[A]
    )(using Frame): Chunk[Svg.SvgElement] =
        if spec.legendCfg.isHidden then Chunk.empty
        else
            // Prefer a color channel; fall back to the stack grouping that colors the stacked segments.
            legendChannel(spec.marks) match
                case Absent           => Chunk.empty
                case Present(colorCh) =>
                    // categories: Chunk[(label, rawValue)] sorted by enum ordinal when applicable
                    val categories = collectColorCategoriesWithRaw(rows, colorCh)
                    if categories.isEmpty then Chunk.empty
                    else
                        val palette = resolvePalette(spec, categories)
                        buildLegendItems(layout, categories, palette, spec.legendCfg, axisChromeColor(spec.theme))
                    end if
            end match
    end buildLegend

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
        // Dedup by CatKey (INV-002): replaces the O(n^2) toSeq.exists(_._1 == label) with O(1) HashSet lookup.
        // Collect (label, rawValue, ordinal) triples; sort by ordinal for enum declaration order.
        val seenKeys = scala.collection.mutable.HashSet.empty[ChartFoundations.CatKey]
        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[(String, Any, Int)]): Chunk[(String, Any, Int)] =
            if i >= rows.size then acc
            else
                val raw = colorCh.accessor(rows(i))
                val key = ChartFoundations.categoryKey(raw)
                if seenKeys.add(key) then
                    val label = raw.toString
                    val ordinal = raw match
                        case e: scala.reflect.Enum => e.ordinal
                        case _                     => acc.size // stable encounter-order index
                    loop(i + 1, acc.append((label, raw, ordinal)))
                else loop(i + 1, acc)
                end if
        val triples = loop(0, Chunk.empty)
        // Sort by ordinal so enum cases appear in declaration order.
        // toSeq here is acceptable: it is a terminal operation on a small collection, not a hot membership scan.
        triples.toSeq.sortBy(_._3).foldLeft(Chunk.empty[(String, Any)])((acc, t) => acc.append((t._1, t._2)))
    end collectColorCategoriesWithRaw

    /** Collect category labels (without raw values) for use in stacked-bar lowering. */
    private def collectColorCategories[A](rows: Chunk[A], colorCh: Channel[A, ?]): Chunk[String] =
        collectColorCategoriesWithRaw(rows, colorCh).map(_._1)

    /** Resolve the palette: explicit `colorScaleFn` first (applied to raw values), then `theme.palette`,
      * then `DefaultPalette`.
      */
    private def resolvePalette[A](spec: ChartSpec[A], categories: Chunk[(String, Any)]): Chunk[Style.Color] =
        spec.legendCfg.colorScaleFn match
            case Present(fn) =>
                // N3: apply the function to the RAW color-channel value (e.g. the actual enum case),
                // not the label string, so typed enum functions work without a label-to-K roundtrip.
                categories.map { case (_, raw) => fn(raw) }
            case Absent =>
                spec.theme.palette match
                    case Present(p) => categories.zipWithIndex.map: (_, i) =>
                            p.toSeq.apply(i % p.size)
                    case Absent => categories.zipWithIndex.map: (_, i) =>
                            DefaultPalette.toSeq.apply(i % DefaultPalette.size)
    end resolvePalette

    /** Build the legend swatch+label elements positioned in the reserved space above the plot area.
      *
      * `categories` is `Chunk[(label, rawValue)]` in enum-ordinal order. `palette` is the resolved
      * color per category. Each entry produces one swatch `Svg.rect` and one `Svg.text` label.
      *
      * The legend is placed in the band between the SVG top and the plot top (the `LegendReservedH`
      * strip reserved by `buildLayout`). This ensures legend swatches never overlap the data marks.
      *
      * `labelColor` is the theme chrome color (the same `axisChromeColor` the axis tick labels use):
      * light on dark, dark on light, so the labels stay readable against the panel background. The swatch
      * fills keep the category/colorScale colors.
      */
    private def buildLegendItems(
        layout: Layout,
        categories: Chunk[(String, Any)],
        palette: Chunk[Style.Color],
        cfg: LegendConfig,
        labelColor: Style.Color
    )(using Frame): Chunk[Svg.SvgElement] =
        // Place the legend inside the reserved band above the plot (y in [MarginTop, plotY - 2]).
        // The band height equals LegendReservedH; center vertically within it.
        val legendX = layout.plotX
        val legendY = MarginTop + (LegendReservedH - SwatchSize) / 2.0

        // Lay items horizontally, each entry occupying (SwatchSize + SwatchLabelGap + labelWidth + itemGap).
        // Approximate label pixel width at roughly 7px per character.
        val itemGap = 16.0

        @scala.annotation.tailrec
        def loop(i: Int, curX: Double, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= categories.size then acc
            else
                val (cat, _) = categories(i)
                val color    = if i < palette.size then palette(i) else DefaultPalette.toSeq.apply(i % DefaultPalette.size)
                val swatch: Svg.SvgElement =
                    Svg.rect
                        .x(curX)
                        .y(legendY)
                        .width(SwatchSize)
                        .height(SwatchSize)
                        .fill(Svg.Paint.Color(color))
                val label: Svg.SvgElement =
                    Svg.text
                        .x(curX + SwatchSize + SwatchLabelGap)
                        .y(legendY + SwatchSize / 2.0)
                        .dominantBaseline(Svg.DominantBaseline.Middle)
                        .fill(Svg.Paint.Color(labelColor))
                        .apply(cat)
                val approxLabelW = cat.length.toDouble * 7.0
                val nextX        = curX + SwatchSize + SwatchLabelGap + approxLabelW + itemGap
                loop(i + 1, nextX, acc.append(swatch).append(label))
        loop(0, legendX, Chunk.empty)
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
                case m: Mark.Area[A, ?, ?] => lowerArea(rows, m, layout, xs, ys, markColor).asInstanceOf[Chunk[UI]]
                case m: Mark.Point[A, ?, ?] =>
                    spec match
                        case Present(s) =>
                            lowerPoint(rows, m, layout, xs, ys, markColor, Present(s), internalHoverRef, theme).asInstanceOf[Chunk[UI]]
                        case Absent => lowerPoint(rows, m, layout, xs, ys, markColor, theme = theme).asInstanceOf[Chunk[UI]]
                case m: Mark.Rule[A]           => lowerRuleChildren(m, layout, xs, ys)
                case _: Mark.Text[A, ?, ?]     => Chunk.empty[UI] // DEV: placeholder; behavior lands in Phase 4 (lowerText)
                case _: Mark.ErrorBar[A, ?, ?] => Chunk.empty[UI] // DEV: placeholder; behavior lands in Phase 4 (lowerErrorBar)
            end match
        allShapes.foldLeft(Svg.g): (g, el) =>
            el match
                case r: Svg.Rect   => g(r)
                case p: Svg.Path   => g(p)
                case c: Svg.Circle => g(c)
                case l: Svg.Line   => g(l)
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
        mark.stack.group match
            case Present(_) =>
                lowerBarStacked(rows, mark, layout, xs, ys, specMaybe)
            case Absent =>
                mark.color match
                    case Absent =>
                        lowerBarSimple(rows, mark, layout, xs, ys, defaultColor, specMaybe, internalHoverRef)
                    case Present(colorCh) =>
                        lowerBarGrouped(rows, mark, colorCh.asInstanceOf[Channel[A, Any]], layout, xs, ys, specMaybe, internalHoverRef)
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
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val baseline = layout.plotBaseline
        // A bar with no explicit color channel uses the per-mark default fill (palette color by mark index),
        // not the browser default (black), so it is visible and distinct from other marks in a combo chart.
        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= rows.size then acc
            else
                val row = rows(i)
                // catalog #2: drop non-finite domain values; filterFinite returns Absent for NaN/Inf
                val yDomain = ChartFoundations.filterFinite(mark.y.plottable.toDomain(mark.y.accessor(row)))
                val nextAcc = yDomain match
                    case Absent => acc
                    case Present(yd) =>
                        val xDomain = mark.x.plottable.toDomain(mark.x.accessor(row))
                        xDomain match
                            case Absent => acc
                            case Present(xd) =>
                                val barX   = xs.apply(xd)
                                val barW   = xs.bandwidth
                                val barY   = ys.apply(yd)
                                val barH   = baseline - barY
                                val iAttrs = spec.map(s => buildInteractionAttrs(row, s, internalHoverRef)).getOrElse(UI.Ast.Attrs())
                                val r = Svg.rect
                                    .x(barX)
                                    .y(barY)
                                    .width(barW)
                                    .height(barH)
                                    .fill(Svg.Paint.Color(defaultFill))
                                    .withAttrs(iAttrs)
                                acc.append(r)
                        end match
                loop(i + 1, nextAcc)
        loop(0, Chunk.empty)
    end lowerBarSimple

    private def lowerBarGrouped[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        colorCh: Channel[A, Any],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        spec: Maybe[ChartSpec[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        // Collect distinct color keys in enum-ordinal order (N3 carry-over)
        val colorKeys: Chunk[String] = collectColorCategories(rows, colorCh)
        val numColors                = colorKeys.size
        val palette                  = resolvePaletteFromCfg(colorKeys)
        val baseline                 = layout.plotBaseline

        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
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
                                acc.append(r)
                        end match
                loop(i + 1, nextAcc)
        loop(0, Chunk.empty)
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

                @scala.annotation.tailrec
                def loopGroup(gi: Int, accY: Double, acc2: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
                    if gi >= groupKeys.size then acc2
                    else
                        val gk   = groupKeys(gi)
                        val rawY = groupMap.getOrElse(gk, 0.0)
                        val effectiveY =
                            if mark.stack.normalize then
                                if totalY > 0.0 then rawY / totalY else 0.0
                            else rawY
                        val newAccY = accY + effectiveY
                        val rectTop =
                            if mark.stack.normalize then
                                plotTop + (1.0 - newAccY) * layout.plotH
                            else
                                ys.apply(Domain.Continuous(newAccY))
                        val rectBot =
                            if mark.stack.normalize then
                                plotTop + (1.0 - accY) * layout.plotH
                            else if accY == 0.0 then baseline
                            else ys.apply(Domain.Continuous(accY))
                        val rectH     = rectBot - rectTop
                        val fillColor = if gi < groupPalette.size then groupPalette(gi) else DefaultPalette(gi % DefaultPalette.size)
                        // Skip emission when the group contributes nothing at this x slot (FIX 3a)
                        val nextAcc2 =
                            if rawY == 0.0 then acc2
                            else
                                val r = Svg.rect
                                    .x(bandX)
                                    .y(rectTop)
                                    .width(bandW)
                                    .height(rectH)
                                    .fill(Svg.Paint.Color(fillColor))
                                acc2.append(r)
                        loopGroup(gi + 1, newAccY, nextAcc2)
                val rectsForX = loopGroup(0, 0.0, Chunk.empty)
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
                Chunk(lowerLineSeries(rows, mark, layout, xs, ys, defaultColor))
            case Present(colorCh) =>
                val colorKeys: Chunk[String] = rows.foldLeft(Chunk.empty[String]): (acc, row) =>
                    val key = colorCh.accessor(row.asInstanceOf[A]).toString
                    if acc.toSeq.contains(key) then acc else acc.append(key)
                colorKeys.zipWithIndex.map: (key, idx) =>
                    val seriesRows  = rows.filter(r => colorCh.accessor(r).toString == key)
                    val strokeColor = DefaultPalette.toSeq.apply(idx % DefaultPalette.size)
                    lowerLineSeries(seriesRows, mark, layout, xs, ys, strokeColor)
    end lowerLine

    private def lowerLineSeries[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Line[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        strokeColor: Style.Color = Style.Color.blue
    )(using Frame): Svg.Path =
        @scala.annotation.tailrec
        def loop(i: Int, pd: Svg.PathData, started: Boolean): Svg.PathData =
            if i >= rows.size then pd
            else
                val row = rows(i)
                val isDefined = mark.defined match
                    case Present(fn) => fn(row)
                    case Absent      => true
                if !isDefined then
                    loop(i + 1, pd, false)
                else
                    mark.y.accessor(row) match
                        case Absent =>
                            loop(i + 1, pd, false)
                        case Present(yv) =>
                            val xd = mark.x.plottable.toDomain(mark.x.accessor(row))
                            val yd = mark.y.plottable.toDomain(yv)
                            (xd, yd) match
                                case (Present(x), Present(y)) =>
                                    val px = xs.apply(x)
                                    val py = ys.apply(y)
                                    if !started then
                                        // First point of a new segment: MoveTo (from or moveTo if resuming after gap)
                                        val newPd =
                                            if Svg.PathData.commands(pd).isEmpty then Svg.PathData.from(px, py)
                                            else pd.moveTo(px, py)
                                        loop(i + 1, newPd, true)
                                    else
                                        loop(i + 1, pd.lineTo(px, py), true)
                                    end if
                                case _ =>
                                    loop(i + 1, pd, false)
                            end match
                end if
        val pathData = loop(0, Svg.PathData.empty, false)
        // A line path must be stroked, not filled. Without explicit fill=none the browser fills the
        // closed polygon formed by the path endpoints, producing a black bowtie/filled-polygon artefact.
        Svg.path
            .d(pathData)
            .fill(Svg.Paint.None)
            .stroke(Svg.Paint.Color(strokeColor))
            .strokeWidth(2.0)
    end lowerLineSeries

    /** Lower a `Mark.Area` to closed `Svg.Path`(s).
      *
      * Three dispatch paths:
      *   1. `mark.stack.group` is `Present` and `mark.y` is `Present`: stacked area (groups sit atop each other).
      *   2. `mark.y` is `Present` and no stack: single area fills between y values and the plot baseline.
      *   3. `mark.y` is `Absent`: the `y0`/`y1` band form (not yet implemented; emits empty).
      */
    private def lowerArea[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Area[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color = DefaultPalette(0)
    )(using Frame): Chunk[Svg.SvgElement] =
        val baseline = layout.plotBaseline
        mark.y match
            case Present(yCh) =>
                mark.stack.group match
                    case Present(_) =>
                        lowerAreaStacked(rows, mark, yCh, layout, xs, ys)
                    case Absent =>
                        // Collect (px, py) pairs, skipping gaps
                        val points: Chunk[(Double, Double)] = rows.flatMap: row =>
                            yCh.accessor(row) match
                                case Absent => Chunk.empty
                                case Present(yv) =>
                                    val xd = mark.x.plottable.toDomain(mark.x.accessor(row))
                                    val yd = yCh.plottable.toDomain(yv)
                                    (xd, yd) match
                                        case (Present(x), Present(y)) => Chunk((xs.apply(x), ys.apply(y)))
                                        case _                        => Chunk.empty
                        if points.isEmpty then Chunk.empty
                        else
                            // Top edge forward
                            val topPd: Svg.PathData = points.tail.foldLeft(Svg.PathData.from(points(0)._1, points(0)._2)): (pd, pt) =>
                                pd.lineTo(pt._1, pt._2)
                            // Baseline back: from last x at baseline to first x at baseline, then close
                            val lastX  = points(points.size - 1)._1
                            val firstX = points(0)._1
                            val pd2    = topPd.lineTo(lastX, baseline).lineTo(firstX, baseline).close
                            // Fill with the per-mark default color (translucent so layered areas remain legible)
                            // rather than the browser default (black).
                            Chunk(Svg.path.d(pd2).fill(Svg.Paint.Color(defaultColor)).fillOpacity(0.7))
                        end if
            case Absent =>
                // y0/y1 band form: not yet implemented; emits empty
                Chunk.empty
        end match
    end lowerArea

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
        ys: Scale
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
                        acc.append(Svg.path.d(fullPath.close))

                // Update accumulated fractions for the next group
                val newAccByX = xKeys.foldLeft(accByX): (m, xk) =>
                    val groupMap = dataMap.getOrElse(xk, Map.empty)
                    m.updated(xk, m.getOrElse(xk, 0.0) + groupMap.getOrElse(gk, 0.0))

                loopGroups(gi + 1, newAccByX, newAcc)
        loopGroups(0, Map.empty, Chunk.empty)
    end lowerAreaStacked

    /** Lower a `Mark.Point` to `Svg.Circle`s.
      *
      * Each row with a defined y produces one circle. The radius comes from the `size` channel if present, or the
      * `DefaultRadius` constant otherwise.
      *
      * When `spec` is supplied, hover and click handlers are attached to each circle so `spec.onHover` and
      * `spec.onSelect` refs receive the typed row on pointer events.
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
        theme: Theme = Theme.default
    )(using Frame): Chunk[Svg.SvgElement] =
        val separator = pointSeparatorColor(theme)
        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= rows.size then acc
            else
                val row = rows(i)
                val nextAcc = mark.y.accessor(row) match
                    case Absent => acc
                    case Present(yv) =>
                        val xd = mark.x.plottable.toDomain(mark.x.accessor(row))
                        val yd = mark.y.plottable.toDomain(yv)
                        (xd, yd) match
                            case (Present(x), Present(y)) =>
                                val cx = xs.apply(x)
                                val cy = ys.apply(y)
                                val r = mark.size match
                                    case Present(fn) => fn(row)
                                    case Absent      => DefaultRadius
                                val iAttrs = spec.map(s => buildInteractionAttrs(row, s, internalHoverRef)).getOrElse(UI.Ast.Attrs())
                                // Fill points with the per-mark default color rather than the browser default (black),
                                // and add a thin contrasting outline (theme background color) so overlapping or
                                // adjacent bubbles read as distinct discs instead of merging into one blob.
                                val c = Svg.circle
                                    .cx(cx).cy(cy).r(r)
                                    .fill(Svg.Paint.Color(defaultColor))
                                    .stroke(Svg.Paint.Color(separator))
                                    .strokeWidth(PointStrokeWidth)
                                    .withAttrs(iAttrs)
                                acc.append(c)
                            case _ => acc
                        end match
                loop(i + 1, nextAcc)
        loop(0, Chunk.empty)
    end lowerPoint

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
                        (accElems ++ lowerPoint(rows, m, layout, xs, ys, markColor, theme = spec.theme), accGeom)
                    case m: Mark.Rule[A]           => (accElems ++ lowerRule(m, layout, xs, ys), accGeom)
                    case _: Mark.Text[A, ?, ?]     => (accElems, accGeom) // DEV: placeholder; behavior lands in Phase 4
                    case _: Mark.ErrorBar[A, ?, ?] => (accElems, accGeom) // DEV: placeholder; behavior lands in Phase 4
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
      * Includes: background rect, axis lines, legend chrome. If the y-domain is fixed (by a `ScaleOverride`),
      * also includes the full y-axis (ticks, labels, gridlines); otherwise the y-axis is omitted here and
      * included inside the reactive region where it can update with the data.
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
        initialRows: Chunk[A],
        legendRows: Chunk[A]
    )(using Frame): Chunk[Svg.SvgElement] =
        val leftChrome  = axisChromeColorFor(spec.theme, spec.marks, Axis.Left)
        val rightChrome = axisChromeColorFor(spec.theme, spec.marks, Axis.Right)
        val gridColor   = gridlineColor(spec.theme)
        val background  = buildBackground(layout, spec.theme)
        val axisLines   = buildAxisLines(layout, ysR, spec.theme, leftChrome, rightChrome)
        // Derive the legend from a sample of the live signal's current value, not from the empty `initialRows`
        // used for the (data-independent) frame chrome. An empty chunk yields zero categories, which would
        // suppress the legend entirely.
        val legendElems = buildLegend(layout, spec, legendRows)
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
        background ++ axisLines ++ yAxisElems ++ legendElems
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
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Svg.G =
        val marksG = stateRef match
            case Present(ref) => marksRegionWithTransitions(rows, spec, layout, xs, ysL, ysR, ref)
            case Absent       => marksRegion(rows, spec.marks, layout, xs, ysL, ysR, Present(spec), internalHoverRef)
        val xAxisElems = buildXAxis(layout, xs, spec.xAxisCfg, spec.theme)
        if isYDomainFixed(spec.yScaleOverride) then
            // Fixed y-domain: y-axis is static; only x-axis ticks and marks are reactive.
            val xAxisG = xAxisElems.foldLeft(Svg.g): (g, el) =>
                el match
                    case l: Svg.Line => g(l)
                    case t: Svg.Text => g(t)
                    case other       => g(other.asInstanceOf[Svg.SvgElement & Svg.SvgChild])
            Svg.g(xAxisG)(marksG)
        else
            // Inferred domain: include y-axis ticks AND x-axis ticks inside the reactive region.
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
            val allElems: Chunk[Svg.SvgElement] = leftAxisElems ++ rightAxisElems ++ xAxisElems
            val axisG = allElems.foldLeft(Svg.g): (g, el) =>
                el match
                    case l: Svg.Line => g(l)
                    case t: Svg.Text => g(t)
                    case other       => g(other.asInstanceOf[Svg.SvgElement & Svg.SvgChild])
            Svg.g(axisG)(marksG)
        end if
    end buildReactiveRegion

    /** Lower a `ChartSpec[A]` with a `DataSource.Live` signal to an `Svg.Root`.
      *
      * The static frame (background, axis lines, x-axis, legend, and the y-axis when the domain is fixed) is
      * drawn once. The marks region (and the y-axis ticks when the domain is inferred) is wrapped in
      * `signal.render` so it re-renders on each emission.
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
    private[kyo] def lowerLive[A](spec: ChartSpec[A], signal: Signal[Chunk[A]], legendRows: Chunk[A])(using Frame): Svg.Root =
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
        val staticFrame = buildStaticFrameLive(layout, xs, ysLForFrame, ysRFixed, spec, initialRows, legendRows)
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
            buildReactiveRegion(rows, spec, layout, xsLive, ysLLive, ysRLive, stateRefMaybe, internalHoverRef)
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
      * N4-guard: no `url(#id)` references are emitted by the frame or marks (plain rects/lines/text only),
      * so `Frame.internal` is safe here. When gradient/clip/marker refs are added in a later phase, the
      * construction `Frame` must be threaded through `ChartSpec` to avoid cross-chart id collisions.
      */
    private[kyo] def lower[A](spec: ChartSpec[A]): Svg.Root =
        given Frame = Frame.internal
        spec.data match
            case DataSource.Static(rows) => lowerStatic(rows, spec)
            case DataSource.Live(signal) =>
                // Sample the signal's current value so the (static) legend can derive its categories from real
                // rows. The legend lives in the static frame, so without a sample it would be built from an
                // empty chunk and never emit any swatches/labels. The category set (e.g. 2xx/4xx/5xx, p50/p99)
                // is fixed for a live chart, so one sample at lowering time is sufficient; the reactive marks
                // region still redraws per emission. Unsafe: a synchronous read of the signal's current value
                // is sound here because lowering is itself a pure, synchronous projection.
                import AllowUnsafe.embrace.danger
                val legendRows = Sync.Unsafe.evalOrThrow(signal.current)
                lowerLive(spec, signal, legendRows)
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

    private def lowerStatic[A](rows: Chunk[A], spec: ChartSpec[A])(using Frame): Svg.Root =
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
        val vb    = Svg.ViewBox(0.0, 0.0, layout.svgW, layout.svgH)
        val frame = buildFrame(layout, xs, ysL, ysR, spec, rows)
        // Phase 07: create an internal hover ref when a tooltip is configured so shape handlers can drive it.
        // Unsafe: SignalRef.Unsafe.init bypasses kyo effects; sound because the ref is private to this chart.
        val internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = spec.tooltip match
            case Present(_) =>
                import AllowUnsafe.embrace.danger
                given CanEqual[Maybe[A], Maybe[A]] = CanEqual.derived
                Present(Signal.SignalRef.Unsafe.init[Maybe[A]](Absent).safe)
            case Absent => Absent
        val marksG  = marksRegion(rows, spec.marks, layout, xs, ysL, ysR, Present(spec), internalHoverRef)
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
