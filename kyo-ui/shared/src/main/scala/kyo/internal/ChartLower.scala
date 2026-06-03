package kyo.internal

import kyo.*

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

    /** Swatch size (pixels) for legend color boxes. */
    private val SwatchSize = 12.0

    /** Vertical gap between legend rows. */
    private val LegendRowH = 18.0

    /** Horizontal gap between swatch and label text in the legend. */
    private val SwatchLabelGap = 6.0

    /** Tick mark half-length (pixels extending past the axis line). */
    private val TickLen = 5.0

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
      * the plot area.
      */
    private def buildLayout(spec: ChartSpec[?]): Layout =
        val (w, h)      = spec.chartSize
        val marginRight = if spec.yAxisRightCfg.isDefined then MarginRightAxis else MarginRightDefault
        Layout(
            svgW = w.toDouble,
            svgH = h.toDouble,
            plotX = MarginLeft,
            plotY = MarginTop,
            plotW = w.toDouble - MarginLeft - marginRight,
            plotH = h.toDouble - MarginTop - MarginBottom
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
                                acc match
                                    case Absent                             => Present(Extent.Continuous(v, v))
                                    case Present(Extent.Continuous(lo, hi)) => Present(Extent.Continuous(math.min(lo, v), math.max(hi, v)))
                                    case Present(Extent.Categories(_))      => Present(Extent.Continuous(v, v))
                            case Domain.Category(key) =>
                                acc match
                                    case Absent => Present(Extent.Categories(Chunk(key)))
                                    case Present(Extent.Categories(keys)) =>
                                        if keys.toSeq.contains(key) then Present(Extent.Categories(keys))
                                        else Present(Extent.Categories(keys.append(key)))
                                    case Present(Extent.Continuous(_, _)) => Present(Extent.Categories(Chunk(key)))
                            case Domain.Temporal(ms) =>
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
                    case m: Mark.Bar[A, ?, ?]   => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r.asInstanceOf[A])))
                    case m: Mark.Line[A, ?, ?]  => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r.asInstanceOf[A])))
                    case m: Mark.Area[A, ?, ?]  => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r.asInstanceOf[A])))
                    case m: Mark.Point[A, ?, ?] => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r.asInstanceOf[A])))
                    case _: Mark.Rule[A]        => Absent
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
                        foldExtent(rows, r => m.y.plottable.toDomain(m.y.accessor(r.asInstanceOf[A])))
                    case m: Mark.Line[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(rows, r => m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.Point[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(rows, r => m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)))
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
                    case _ => Absent
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
                    val merged = k2.foldLeft(k1)((acc, k) => if acc.toSeq.contains(k) then acc else acc.append(k))
                    Present(Extent.Categories(merged))
                case (Extent.Continuous(lo, hi), Extent.Categories(_)) => Present(Extent.Continuous(lo, hi))
                case (Extent.Categories(_), Extent.Continuous(lo, hi)) => Present(Extent.Continuous(lo, hi))
        case _ => Absent

    // ---- scale resolution ----

    private def resolveXScale[A](rows: Chunk[A], marks: Chunk[Mark[A]], layout: Layout, xOverride: Maybe[ScaleOverride]): Scale =
        val ext = xExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
        val kindOpt: Maybe[Scale.Kind] = xOverride.flatMap(_.kind) match
            case Present(ScaleKind.Band)         => Present(Scale.Kind.Band)
            case Present(ScaleKind.Log)          => Present(Scale.Kind.Log)
            case Present(ScaleKind.Linear(_, _)) => Present(Scale.Kind.Linear)
            case _                               => Absent
        val kind = kindOpt.getOrElse(inferKind(ext, marks, isX = true))
        val (extFinal, lo, hi) = xOverride.flatMap(_.kind) match
            case Present(ScaleKind.Linear(domLo, domHi)) => (Extent.Continuous(domLo, domHi), layout.plotX, layout.plotX + layout.plotW)
            case _                                       => (ext, layout.plotX, layout.plotX + layout.plotW)
        Scale.fit(kind, extFinal, lo, hi)
    end resolveXScale

    private def resolveYScale[A](rows: Chunk[A], marks: Chunk[Mark[A]], layout: Layout, yOverride: Maybe[ScaleOverride]): Scale =
        val ext = yLeftExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
        val kindOpt: Maybe[Scale.Kind] = yOverride.flatMap(_.kind) match
            case Present(ScaleKind.Band)         => Present(Scale.Kind.Band)
            case Present(ScaleKind.Log)          => Present(Scale.Kind.Log)
            case Present(ScaleKind.Linear(_, _)) => Present(Scale.Kind.Linear)
            case _                               => Absent
        val kind = kindOpt.getOrElse(Scale.Kind.Linear)
        // y-axis: rangeLo=baseline (large pixel), rangeHi=top (small pixel) -- inverts SVG so big values are up
        val baseline = layout.plotBaseline
        val top      = layout.plotY
        val (extFinal, rLo, rHi, useNice) = yOverride.flatMap(_.kind) match
            // Explicit linear domain: preserve exact lo/hi, skip niceTicks so the bounds are exact
            case Present(ScaleKind.Linear(domLo, domHi)) => (Extent.Continuous(domLo, domHi), baseline, top, false)
            // Log scale: use the raw extent without ensureZero adjustment (log domain must be > 0)
            case Present(ScaleKind.Log) =>
                val rawExt = yLeftExtentNoZero(rows, marks).getOrElse(Extent.Continuous(1.0, 10.0))
                (rawExt, baseline, top, false)
            case _ => (ext, baseline, top, true)
        Scale.fit(kind, extFinal, rLo, rHi, nice = useNice)
    end resolveYScale

    private def resolveYRightScale[A](rows: Chunk[A], marks: Chunk[Mark[A]], layout: Layout): Scale =
        val ext = yRightExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
        Scale.fit(Scale.Kind.Linear, ext, layout.plotBaseline, layout.plotY, nice = true)
    end resolveYRightScale

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
        val background = buildBackground(layout, spec.theme)
        val axisLines  = buildAxisLines(layout, ysR)
        val leftAxis   = buildYAxis(layout, ysL, spec.yAxisCfg, isRight = false)
        val rightAxis = ysR match
            case Present(ysR_) => buildYAxis(layout, ysR_, spec.yAxisRightCfg.getOrElse(AxisConfig.default), isRight = true)
            case Absent        => Chunk.empty
        val xAxisElems  = buildXAxis(layout, xs, spec.xAxisCfg)
        val legendElems = buildLegend(layout, spec, rows)
        background ++ axisLines ++ leftAxis ++ rightAxis ++ xAxisElems ++ legendElems
    end buildFrame

    /** Background rectangle filled with the theme color. */
    private def buildBackground(layout: Layout, theme: Theme)(using Frame): Chunk[Svg.SvgElement] =
        val color = if theme.isDark then DarkBg else LightBg
        Chunk(
            Svg.rect
                .x(layout.plotX)
                .y(layout.plotY)
                .width(layout.plotW)
                .height(layout.plotH)
                .fill(Svg.Paint.Color(color))
        )
    end buildBackground

    /** Axis lines bordering the plot area (left + bottom). */
    private def buildAxisLines(layout: Layout, ysR: Maybe[Scale])(using Frame): Chunk[Svg.SvgElement] =
        val leftLine = Svg.line
            .x1(layout.plotX).y1(layout.plotY)
            .x2(layout.plotX).y2(layout.plotBaseline)
            .stroke(Svg.Paint.Color(Style.Color.gray))
        val bottomLine = Svg.line
            .x1(layout.plotX).y1(layout.plotBaseline)
            .x2(layout.plotX + layout.plotW).y2(layout.plotBaseline)
            .stroke(Svg.Paint.Color(Style.Color.gray))
        val rightLine: Maybe[Svg.SvgElement] = ysR match
            case Present(_) =>
                val rx = layout.plotX + layout.plotW
                Present(
                    Svg.line
                        .x1(rx).y1(layout.plotY)
                        .x2(rx).y2(layout.plotBaseline)
                        .stroke(Svg.Paint.Color(Style.Color.gray))
                )
            case Absent => Absent
        val base = Chunk[Svg.SvgElement](leftLine, bottomLine)
        rightLine match
            case Present(l) => base.append(l)
            case Absent     => base
    end buildAxisLines

    /** Build the left or right y-axis: gridlines (if enabled), tick marks, and tick labels. */
    private def buildYAxis(
        layout: Layout,
        ys: Scale,
        cfg: AxisConfig,
        isRight: Boolean
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
                // gridline
                val grid: Maybe[Svg.SvgElement] =
                    if cfg.showGrid && !isRight then
                        Present(
                            Svg.line
                                .x1(layout.plotX).y1(py)
                                .x2(layout.plotX + layout.plotW).y2(py)
                                .stroke(Svg.Paint.Color(Style.Color.gray))
                                .strokeOpacity(0.3)
                        )
                    else Absent
                // tick mark
                val tickMark: Svg.SvgElement =
                    if isRight then
                        Svg.line
                            .x1(axisX).y1(py)
                            .x2(axisX + TickLen).y2(py)
                            .stroke(Svg.Paint.Color(Style.Color.gray))
                    else
                        Svg.line
                            .x1(axisX - TickLen).y1(py)
                            .x2(axisX).y2(py)
                            .stroke(Svg.Paint.Color(Style.Color.gray))
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
                        .apply(labelStr)
                val elements = grid match
                    case Present(g) => Chunk[Svg.SvgElement](g, tickMark, tickLabel)
                    case Absent     => Chunk[Svg.SvgElement](tickMark, tickLabel)
                loop(i + 1, acc ++ elements)
        val base = loop(0, Chunk.empty)
        // axis label
        cfg.axisLabel match
            case Present(lbl) =>
                val labelElem: Svg.SvgElement =
                    if isRight then
                        Svg.text
                            .x(axisX + MarginRightAxis - 8.0)
                            .y(layout.plotY + layout.plotH / 2.0)
                            .textAnchor(Svg.TextAnchor.Middle)
                            .apply(lbl)
                    else
                        Svg.text
                            .x(layout.plotX - MarginLeft + 12.0)
                            .y(layout.plotY + layout.plotH / 2.0)
                            .textAnchor(Svg.TextAnchor.Middle)
                            .apply(lbl)
                base.append(labelElem)
            case Absent => base
        end match
    end buildYAxis

    /** Build the x-axis: tick marks and tick labels along the bottom. */
    private def buildXAxis(
        layout: Layout,
        xs: Scale,
        cfg: AxisConfig
    )(using Frame): Chunk[Svg.SvgElement] =
        val ticks = xs.ticks(cfg.tickCount)
        val axisY = layout.plotBaseline

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
                        .stroke(Svg.Paint.Color(Style.Color.gray))
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
      */
    private def buildLegend[A](
        layout: Layout,
        spec: ChartSpec[A],
        rows: Chunk[A]
    )(using Frame): Chunk[Svg.SvgElement] =
        if spec.legendCfg.isHidden then Chunk.empty
        else
            // Find the first mark with a color channel
            val colorChOpt: Maybe[Channel[A, ?]] = findColorChannel(spec.marks)
            colorChOpt match
                case Absent           => Chunk.empty
                case Present(colorCh) =>
                    // categories: Chunk[(label, rawValue)] sorted by enum ordinal when applicable
                    val categories = collectColorCategoriesWithRaw(rows, colorCh)
                    if categories.isEmpty then Chunk.empty
                    else
                        val palette = resolvePalette(spec, categories)
                        buildLegendItems(layout, categories, palette, spec.legendCfg)
                    end if
            end match
    end buildLegend

    /** Find the first color channel across all marks. */
    private def findColorChannel[A](marks: Chunk[Mark[A]]): Maybe[Channel[A, ?]] =
        @scala.annotation.tailrec
        def loop(i: Int): Maybe[Channel[A, ?]] =
            if i >= marks.size then Absent
            else
                marks(i) match
                    case m: Mark.Bar[A, ?, ?]   => m.color.orElse(loop(i + 1))
                    case m: Mark.Line[A, ?, ?]  => m.color.orElse(loop(i + 1))
                    case m: Mark.Area[A, ?, ?]  => m.color.orElse(loop(i + 1))
                    case m: Mark.Point[A, ?, ?] => m.color.orElse(loop(i + 1))
                    case _                      => loop(i + 1)
        loop(0)
    end findColorChannel

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
        // Collect (label, rawValue, ordinal) triples.
        @scala.annotation.tailrec
        def loop(i: Int, seen: Chunk[(String, Any, Int)]): Chunk[(String, Any, Int)] =
            if i >= rows.size then seen
            else
                val raw   = colorCh.accessor(rows(i))
                val label = raw.toString
                if seen.toSeq.exists(_._1 == label) then loop(i + 1, seen)
                else
                    val ordinal = raw match
                        case e: scala.reflect.Enum => e.ordinal
                        case _                     => seen.size // stable encounter-order index
                    loop(i + 1, seen.append((label, raw, ordinal)))
                end if
        val triples = loop(0, Chunk.empty)
        // Sort by ordinal so enum cases appear in declaration order
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

    /** Build the legend swatch+label elements positioned at the top of the plot.
      *
      * `categories` is `Chunk[(label, rawValue)]` in enum-ordinal order. `palette` is the resolved
      * color per category. Each entry produces one swatch `Svg.rect` and one `Svg.text` label.
      */
    private def buildLegendItems(
        layout: Layout,
        categories: Chunk[(String, Any)],
        palette: Chunk[Style.Color],
        cfg: LegendConfig
    )(using Frame): Chunk[Svg.SvgElement] =
        // Position legend above the plot area on the right side by default
        val legendX = layout.plotX + layout.plotW - 120.0
        val legendY = layout.plotY - MarginTop + 2.0

        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= categories.size then acc
            else
                val (cat, _) = categories(i)
                val color    = if i < palette.size then palette(i) else DefaultPalette.toSeq.apply(i % DefaultPalette.size)
                val rowY     = legendY + i.toDouble * LegendRowH
                val swatch: Svg.SvgElement =
                    Svg.rect
                        .x(legendX)
                        .y(rowY)
                        .width(SwatchSize)
                        .height(SwatchSize)
                        .fill(Svg.Paint.Color(color))
                val label: Svg.SvgElement =
                    Svg.text
                        .x(legendX + SwatchSize + SwatchLabelGap)
                        .y(rowY + SwatchSize / 2.0)
                        .dominantBaseline(Svg.DominantBaseline.Middle)
                        .apply(cat)
                loop(i + 1, acc.append(swatch).append(label))
        loop(0, Chunk.empty)
    end buildLegendItems

    // ---- marks region ----

    /** Build the SVG `<g>` containing all lowered marks for static data. */
    private[kyo] def marksRegion[A](
        rows: Chunk[A],
        marks: Chunk[Mark[A]],
        layout: Layout,
        xs: Scale,
        ysL: Scale,
        ysR: Maybe[Scale] = Absent
    )(using Frame): Svg.G =
        val shapes: Chunk[Svg.SvgElement] = marks.flatMap: mark =>
            val ys = mark match
                case m: Mark.Bar[A, ?, ?]   => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                case m: Mark.Line[A, ?, ?]  => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                case m: Mark.Area[A, ?, ?]  => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                case m: Mark.Point[A, ?, ?] => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
                case m: Mark.Rule[A]        => if m.axis == Axis.Right then ysR.getOrElse(ysL) else ysL
            mark match
                case m: Mark.Bar[A, ?, ?]   => lowerBar(rows, m, layout, xs, ys)
                case m: Mark.Line[A, ?, ?]  => lowerLine(rows, m, layout, xs, ys)
                case m: Mark.Area[A, ?, ?]  => lowerArea(rows, m, layout, xs, ys)
                case m: Mark.Point[A, ?, ?] => lowerPoint(rows, m, layout, xs, ys)
                case m: Mark.Rule[A]        => lowerRule(m, layout, xs, ys)
            end match
        shapes.foldLeft(Svg.g): (g, el) =>
            el match
                case r: Svg.Rect   => g(r)
                case p: Svg.Path   => g(p)
                case c: Svg.Circle => g(c)
                case l: Svg.Line   => g(l)
                case other         => g(other.asInstanceOf[Svg.SvgElement & Svg.SvgChild])
    end marksRegion

    // ---- per-mark lowerers ----

    /** Lower a `Mark.Bar` to a `Chunk` of `Svg.Rect`s.
      *
      * When the mark has no `color` channel each row produces one rect spanning the full band. When a `color`
      * channel is present the band is subdivided into sub-bands (grouped) or the rects stack vertically
      * (stacked, when `mark.stack.group` is `Present`).
      */
    private def lowerBar[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Chunk[Svg.SvgElement] =
        mark.stack.group match
            case Present(_) =>
                lowerBarStacked(rows, mark, layout, xs, ys)
            case Absent =>
                mark.color match
                    case Absent =>
                        lowerBarSimple(rows, mark, layout, xs, ys)
                    case Present(colorCh) =>
                        lowerBarGrouped(rows, mark, colorCh.asInstanceOf[Channel[A, Any]], layout, xs, ys)
    end lowerBar

    private def lowerBarSimple[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Chunk[Svg.SvgElement] =
        val baseline = layout.plotBaseline
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
                                val barX = xs.apply(xd)
                                val barW = xs.bandwidth
                                val barY = ys.apply(yd)
                                val barH = baseline - barY
                                val r = Svg.rect
                                    .x(barX)
                                    .y(barY)
                                    .width(barW)
                                    .height(barH)
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
        ys: Scale
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
                                val r         = Svg.rect.x(barX).y(barY).width(subW).height(barH).fill(Svg.Paint.Color(fillColor))
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
        ys: Scale
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

        // 2. Collect all distinct group keys in enum-ordinal order
        // Use a temporary Channel to reuse collectColorCategories
        val groupCh: Channel[A, Any] = Channel(groupFn, Plottable.string.asInstanceOf[Plottable[Any]])
        val groupKeys: Chunk[String] = collectColorCategories(rows, groupCh)

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
                        val colorIdx  = gi % DefaultPalette.size
                        val fillColor = DefaultPalette(colorIdx)
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
      */
    private def lowerLine[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Line[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Chunk[Svg.SvgElement] =
        mark.color match
            case Absent =>
                Chunk(lowerLineSeries(rows, mark, layout, xs, ys))
            case Present(colorCh) =>
                val colorKeys: Chunk[String] = rows.foldLeft(Chunk.empty[String]): (acc, row) =>
                    val key = colorCh.accessor(row.asInstanceOf[A]).toString
                    if acc.toSeq.contains(key) then acc else acc.append(key)
                colorKeys.map: key =>
                    val seriesRows = rows.filter(r => colorCh.accessor(r).toString == key)
                    lowerLineSeries(seriesRows, mark, layout, xs, ys)
    end lowerLine

    private def lowerLineSeries[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Line[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale
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
        Svg.path.d(pathData)
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
        ys: Scale
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
                            Chunk(Svg.path.d(pd2))
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
      */
    private def lowerPoint[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Point[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Chunk[Svg.SvgElement] =
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
                                val c = Svg.circle.cx(cx).cy(cy).r(r)
                                acc.append(c)
                            case _ => acc
                        end match
                loop(i + 1, nextAcc)
        loop(0, Chunk.empty)
    end lowerPoint

    /** Lower a `Mark.Rule` to an `Svg.Line` spanning the full plot width (horizontal rule) or height (vertical rule).
      *
      * Only `Const` rule values are handled in Phases 03/04; `Reactive` values are resolved in Phase 07.
      */
    private def lowerRule[A](
        mark: Mark.Rule[A],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Chunk[Svg.SvgElement] =
        val xLine: Maybe[Svg.SvgElement] = mark.x match
            case Present(RuleValue.Const(v, pl)) =>
                pl.asInstanceOf[Plottable[Any]].toDomain(v) match
                    case Present(d) =>
                        val px = xs.apply(d)
                        Present(Svg.line.x1(px).y1(layout.plotY).x2(px).y2(layout.plotBaseline))
                    case Absent => Absent
            case _ => Absent
        val yLine: Maybe[Svg.SvgElement] = mark.y match
            case Present(RuleValue.Const(v, pl)) =>
                pl.asInstanceOf[Plottable[Any]].toDomain(v) match
                    case Present(d) =>
                        val py = ys.apply(d)
                        Present(Svg.line.x1(layout.plotX).y1(py).x2(layout.plotX + layout.plotW).y2(py))
                    case Absent => Absent
            case _ => Absent
        Chunk.from(xLine.toOption.toSeq ++ yLine.toOption.toSeq)
    end lowerRule

    // ---- main entry point ----

    /** Lower a `ChartSpec[A]` to an `Svg.Root`.
      *
      * Handles only `DataSource.Static` through Phase 04. For `DataSource.Live` the reactive path (Phase 05)
      * is needed; Phases 03/04 emit an empty root for live data as a safe fallback.
      *
      * N4-guard: no `url(#id)` references are emitted by the frame or marks (plain rects/lines/text only),
      * so `Frame.internal` is safe here. When gradient/clip/marker refs are added in a later phase, the
      * construction `Frame` must be threaded through `ChartSpec` to avoid cross-chart id collisions.
      */
    def lower[A](spec: ChartSpec[A]): Svg.Root =
        given Frame = Frame.internal
        spec.data match
            case DataSource.Static(rows) => lowerStatic(rows, spec)
            case DataSource.Live(_)      => Svg.svg
    end lower

    private def lowerStatic[A](rows: Chunk[A], spec: ChartSpec[A])(using Frame): Svg.Root =
        val layout = buildLayout(spec)
        val xs     = resolveXScale(rows, spec.marks, layout, spec.xScaleOverride)
        val ysL    = resolveYScale(rows, spec.marks, layout, spec.yScaleOverride)
        val hasRight = spec.marks.toSeq.exists:
            case m: Mark.Bar[A, ?, ?]   => m.axis == Axis.Right
            case m: Mark.Line[A, ?, ?]  => m.axis == Axis.Right
            case m: Mark.Area[A, ?, ?]  => m.axis == Axis.Right
            case m: Mark.Point[A, ?, ?] => m.axis == Axis.Right
            case _                      => false
        val ysR: Maybe[Scale] =
            if hasRight || spec.yAxisRightCfg.isDefined
            then Present(resolveYRightScale(rows, spec.marks, layout))
            else Absent
        val vb      = Svg.ViewBox(0.0, 0.0, layout.svgW, layout.svgH)
        val frame   = buildFrame(layout, xs, ysL, ysR, spec, rows)
        val marksG  = marksRegion(rows, spec.marks, layout, xs, ysL, ysR)
        val baseSvg = Svg.svg.width(layout.svgW).height(layout.svgH).viewBox(vb)
        val withFrame = frame.foldLeft(baseSvg): (acc, el) =>
            el match
                case r: Svg.Rect => acc(r)
                case l: Svg.Line => acc(l)
                case t: Svg.Text => acc(t)
                case g: Svg.G    => acc(g)
                case other       => acc(other.asInstanceOf[Svg.SvgElement & Svg.SvgChild])
        withFrame(marksG)
    end lowerStatic

end ChartLower
