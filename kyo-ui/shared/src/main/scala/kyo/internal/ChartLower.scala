package kyo.internal

import kyo.*

/** Lowers a `ChartSpec[A]` to an `Svg.Root` for static data.
  *
  * This is the single internal function that produces a real SVG tree from the immutable chart intermediate
  * representation. Layout is computed from `size` and margin constants; scales are resolved from the marks'
  * channels via `Plottable` and `Scale.fit`; each mark is lowered to its corresponding SVG primitive.
  *
  * Phase 03 covers static data only (`DataSource.Static`). Live data (`DataSource.Live`) is handled in Phase 05.
  */
private[kyo] object ChartLower:

    /** Standard margins used when computing the plot rectangle from the chart size. */
    private val MarginLeft   = 60.0
    private val MarginRight  = 20.0
    private val MarginTop    = 20.0
    private val MarginBottom = 40.0

    /** Default point radius when no `size` channel is supplied. */
    private val DefaultRadius = 4.0

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
      * The margins are fixed constants; a future phase will widen `marginRight` when a right axis is present.
      */
    private def buildLayout(spec: ChartSpec[?]): Layout =
        val (w, h) = spec.chartSize
        Layout(
            svgW = w.toDouble,
            svgH = h.toDouble,
            plotX = MarginLeft,
            plotY = MarginTop,
            plotW = w.toDouble - MarginLeft - MarginRight,
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

    /** Collect y-extent across all marks on `Axis.Left` for the given rows. */
    private def yLeftExtent[A](rows: Chunk[A], marks: Chunk[Mark[A]]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= marks.size then acc
            else
                val markExtent: Maybe[Extent] = marks(i) match
                    case m: Mark.Bar[A, ?, ?] if m.axis == Axis.Left =>
                        val base = foldExtent(rows, r => m.y.plottable.toDomain(m.y.accessor(r.asInstanceOf[A])))
                        ensureZero(base)
                    case m: Mark.Line[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(rows, r => m.y.accessor(r.asInstanceOf[A]).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.Area[A, ?, ?] if m.axis == Axis.Left =>
                        val base = m.y match
                            case Present(ch) => foldExtent(rows, r => ch.accessor(r.asInstanceOf[A]).flatMap(v => ch.plottable.toDomain(v)))
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

    // ---- scale resolution ----

    private def resolveXScale[A](rows: Chunk[A], marks: Chunk[Mark[A]], layout: Layout, xOverride: Maybe[ScaleOverride]): Scale =
        val ext = xExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
        val kindOpt: Maybe[Scale.Kind] = xOverride.flatMap(_.kind) match
            case Present(ScaleKind.Band)         => Present(Scale.Kind.Band)
            case Present(ScaleKind.Log)          => Present(Scale.Kind.Log)
            case Present(ScaleKind.Linear(_, _)) => Present(Scale.Kind.Linear)
            case Absent                          => Absent
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
            case Absent                          => Absent
        val kind = kindOpt.getOrElse(Scale.Kind.Linear)
        // y-axis: rangeLo=baseline (large pixel), rangeHi=top (small pixel) -- inverts SVG so big values are up
        val baseline = layout.plotBaseline
        val top      = layout.plotY
        val (extFinal, rLo, rHi) = yOverride.flatMap(_.kind) match
            case Present(ScaleKind.Linear(domLo, domHi)) => (Extent.Continuous(domLo, domHi), baseline, top)
            case _                                       => (ext, baseline, top)
        Scale.fit(kind, extFinal, rLo, rHi, nice = true)
    end resolveYScale

    private def inferKind[A](ext: Extent, marks: Chunk[Mark[A]], isX: Boolean): Scale.Kind =
        ext match
            case Extent.Categories(_)    => Scale.Kind.Band
            case Extent.Continuous(_, _) => Scale.Kind.Linear

    // ---- marks region ----

    /** Build the SVG `<g>` containing all lowered marks for static data. */
    private[kyo] def marksRegion[A](
        rows: Chunk[A],
        marks: Chunk[Mark[A]],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Svg.G =
        val shapes: Chunk[Svg.SvgElement] = marks.flatMap: mark =>
            mark match
                case m: Mark.Bar[A, ?, ?]   => lowerBar(rows, m, layout, xs, ys)
                case m: Mark.Line[A, ?, ?]  => lowerLine(rows, m, layout, xs, ys)
                case m: Mark.Area[A, ?, ?]  => lowerArea(rows, m, layout, xs, ys)
                case m: Mark.Point[A, ?, ?] => lowerPoint(rows, m, layout, xs, ys)
                case m: Mark.Rule[A]        => lowerRule(m, layout, xs, ys)
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
      * channel is present the band is subdivided into sub-bands, one per distinct color category, and each row
      * contributes to exactly one sub-band determined by its color value.
      */
    private def lowerBar[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Chunk[Svg.SvgElement] =
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
        // Collect distinct color keys in encounter order.
        // The color channel stores Plottable.string as a stand-in (N3); use .toString on the raw value
        // to get a stable key that works for any type (enum, String, etc.).
        val colorKeys: Chunk[String] = rows.foldLeft(Chunk.empty[String]): (acc, row) =>
            val key = colorCh.accessor(row).toString
            if acc.toSeq.contains(key) then acc else acc.append(key)
        val numColors = colorKeys.size
        val baseline  = layout.plotBaseline

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
                                val bandX    = xs.apply(xd)
                                val bandW    = xs.bandwidth
                                val subW     = bandW / numColors.toDouble
                                val colorKey = colorCh.accessor(row).toString
                                val colorIdx = colorKeys.toSeq.indexOf(colorKey)
                                val barX     = bandX + colorIdx.toDouble * subW
                                val barY     = ys.apply(yd)
                                val barH     = baseline - barY
                                val r        = Svg.rect.x(barX).y(barY).width(subW).height(barH)
                                acc.append(r)
                        end match
                loop(i + 1, nextAcc)
        loop(0, Chunk.empty)
    end lowerBarGrouped

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

    /** Lower a `Mark.Area` to a closed `Svg.Path`.
      *
      * The path traces the top edge forward (y values), then the baseline backward, then closes. An area mark with
      * only `y` supplied fills between the y values and the plot baseline. The `y0`/`y1` band form is not fully
      * implemented until Phase 04; this phase handles the common single-`y` form.
      */
    private def lowerArea[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Area[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Chunk[Svg.SvgElement] =
        val baseline = layout.plotBaseline
        // Only the single-y form is supported in Phase 03.
        mark.y match
            case Absent =>
                // y0/y1 band form: emit empty (not implemented until Phase 04)
                Chunk.empty
            case Present(yCh) =>
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
        end match
    end lowerArea

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
      * Only `Const` rule values are handled in Phase 03; `Reactive` values are resolved in Phase 07.
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
      * Handles only `DataSource.Static` in Phase 03. For `DataSource.Live` the reactive path (Phase 05) is needed;
      * this phase emits an empty root for live data as a safe fallback.
      *
      * Uses `Frame.internal` for SVG node construction; all frames in the lowered tree point to this module,
      * which is appropriate since the nodes are generated by the lowering machinery, not by the user.
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
        val ys     = resolveYScale(rows, spec.marks, layout, spec.yScaleOverride)
        val vb     = Svg.ViewBox(0.0, 0.0, layout.svgW, layout.svgH)
        val marks  = marksRegion(rows, spec.marks, layout, xs, ys)
        Svg.svg.width(layout.svgW).height(layout.svgH).viewBox(vb)(marks)
    end lowerStatic

end ChartLower
