package kyo.internal

import kyo.*
import kyo.Chart.*
// Explicit named import so the bare `Encoding` resolves to the chart data-encoding carrier
// (kyo.Chart.Encoding). This is distinct from kyo-core's concurrency type `kyo.Channel`.
import kyo.Chart.Encoding
import kyo.UI.*
import kyo.UI.Ast.*

/** Lowers a `Chart[A]` to an `Svg.Root` for static or live (reactive) data.
  *
  * Multiple `private[kyo]` entry points cover the static path (`lowerStatic`), the live path
  * (`lowerLive`), and the transition-aware animated path (`ChartTransitions.marksRegionWithTransitions`).
  * Layout is computed from `size` and margin constants; scales are resolved from the marks' encodings via
  * `Plottable` and `Scale.fit`; each mark is lowered to its corresponding SVG primitive. The static frame
  * (axes, gridlines, tick marks, tick labels, legend) is built by `ChartAxes.buildFrame`.
  */
private[kyo] object ChartLower:

    import ChartLayout.Layout
    import ChartScales.ResolvedScales

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
      * No `url(#id)` references are emitted.
      */
    private[kyo] def buildStaticFrameLive[A](
        layout: Layout,
        xs: Scale,
        ysL: Scale,
        ysR: Maybe[Scale],
        spec: Chart[A],
        initialRows: Chunk[A]
    )(using Frame): Chunk[Svg.SvgElement] =
        val leftChrome    = ChartAxes.axisChromeColorFor(spec.theme, spec.marks, Axis.Left)
        val rightChrome   = ChartAxes.axisChromeColorFor(spec.theme, spec.marks, Axis.Right)
        val gridColor     = ChartAxes.gridlineColor(spec.theme)
        val leftDrawGrid  = spec.yAxisCfg.showGrid
        val rightDrawGrid = spec.yAxisRightCfg.exists(_.showGrid) && !leftDrawGrid
        val background    = ChartAxes.buildBackground(layout, spec.theme)
        val axisLines     = ChartAxes.buildAxisLines(layout, ysR, spec.theme, leftChrome, rightChrome)
        // The legend is NOT built here: it is data-dependent and lives inside the reactive region, where it
        // reflects each emission's live category set. Building it from a one-shot sample would freeze it to the
        // first emission's categories.
        val yAxisElems: Chunk[Svg.SvgElement] =
            if isYDomainFixed(spec.yScaleOverride) then
                val leftAxis =
                    ChartAxes.buildYAxis(layout, ysL, spec.yAxisCfg, isRight = false, spec.theme, leftChrome, gridColor, leftDrawGrid)
                val rightAxis = ysR match
                    case Present(ysR_) =>
                        ChartAxes.buildYAxis(
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
        spec: Chart[A],
        layout: Layout,
        xs: Scale,
        ysL: Scale,
        ysR: Maybe[Scale],
        stateRef: Maybe[AtomicRef.Unsafe[ChartTransitions.TransState[A]]],
        gradPrefix: String,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame, AllowUnsafe): Svg.G =
        // Interactive hidden-series filter: the marks drop rows whose color label is hidden; the
        // legend keeps every category (built from the full emission rows) so a hidden series can be toggled on.
        val visibleRows = visibleRowsFor(rows, spec)
        val marksG = stateRef match
            case Present(ref) => ChartTransitions.marksRegionWithTransitions(visibleRows, spec, layout, xs, ysL, ysR, ref, internalHoverRef)
            case Absent       => ChartMarks.marksRegion(visibleRows, spec.marks, layout, xs, ysL, ysR, Present(spec), internalHoverRef)
        // Live legend: built per emission from the full rows so it reflects the current category set.
        val legendElems = ChartLegend.buildLegend(layout, spec, rows, gradPrefix)
        val xAxisElems  = ChartAxes.buildXAxis(layout, xs, spec.xAxisCfg, spec.theme)
        if isYDomainFixed(spec.yScaleOverride) then
            // Fixed y-domain: y-axis is static; only x-axis ticks, legend, and marks are reactive.
            val xAxisG = (xAxisElems ++ legendElems).foldLeft(Svg.g): (g, el) =>
                el match
                    case l: Svg.Line => g(l)
                    case t: Svg.Text => g(t)
                    // The axis/legend builders emit `Svg.SvgElement`s; `SvgElement` is a member of the `SvgChild`
                    // union, so the residual arm is accepted by `g` directly.
                    case other => g(other)
            Svg.g(xAxisG)(marksG)
        else
            // Inferred domain: include y-axis ticks, x-axis ticks, and the legend inside the reactive region.
            val leftChrome    = ChartAxes.axisChromeColorFor(spec.theme, spec.marks, Axis.Left)
            val rightChrome   = ChartAxes.axisChromeColorFor(spec.theme, spec.marks, Axis.Right)
            val gridColor     = ChartAxes.gridlineColor(spec.theme)
            val leftDrawGrid  = spec.yAxisCfg.showGrid
            val rightDrawGrid = spec.yAxisRightCfg.exists(_.showGrid) && !leftDrawGrid
            val leftAxisElems =
                ChartAxes.buildYAxis(layout, ysL, spec.yAxisCfg, isRight = false, spec.theme, leftChrome, gridColor, leftDrawGrid)
            val rightAxisElems = ysR match
                case Present(ysR_) =>
                    ChartAxes.buildYAxis(
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
            val allElems: Chunk[Svg.SvgElement] = leftAxisElems ++ rightAxisElems ++ xAxisElems ++ legendElems
            val axisG = allElems.foldLeft(Svg.g): (g, el) =>
                el match
                    case l: Svg.Line => g(l)
                    case t: Svg.Text => g(t)
                    // The axis/legend builders emit `Svg.SvgElement`s; `SvgElement` is a member of the `SvgChild`
                    // union, so the residual arm is accepted by `g` directly.
                    case other => g(other)
            Svg.g(axisG)(marksG)
        end if
    end buildReactiveRegion

    /** Drop rows whose color category index is in the interactive legend's hidden set.
      *
      * When the legend is not interactive (or no `hiddenSeries` ref is attached), all rows are returned. The
      * hidden indices are read synchronously from the user's ref; the index derivation mirrors the legend's
      * category list (collectColorCategoriesWithRaw order), so toggling index i hides exactly the rows
      * belonging to the i-th color category. Two categories with colliding `toString` values get distinct
      * indices and can be toggled independently.
      */
    private def visibleRowsFor[A](rows: Chunk[A], spec: Chart[A])(using AllowUnsafe): Chunk[A] =
        spec.legendCfg.hiddenSeries match
            case Absent       => rows
            case Present(ref) =>
                // Unsafe: a synchronous read of the user's SignalRef. Sound because this runs inside the pure
                // reactive projection (buildReactiveRegion) / synchronous static lowering; the ref is read, not
                // written, and the filter is a pure function of its current value.
                val hidden = ref.unsafe.get()
                if hidden.isEmpty then rows
                else
                    ChartLegend.legendEncoding(spec.marks) match
                        case Present(ch) =>
                            val colorEnc: Encoding[A, ?] = ch
                            // Build a CatKey -> index map from the same ordered color-category list the legend uses.
                            val catsList = ChartLegend.collectColorCategoriesWithRaw(rows, colorEnc)
                            val idxByKey: Map[ChartFoundations.CatKey, Int] =
                                catsList.zipWithIndex.foldLeft(Map.empty[ChartFoundations.CatKey, Int]): (m, pair) =>
                                    val ((_, raw), idx) = pair
                                    m.updated(ChartFoundations.categoryKey(colorEnc.tag, raw), idx)
                            rows.filter: r =>
                                val key = ChartFoundations.categoryKey(colorEnc.tag, colorEnc.accessor(r))
                                !hidden.contains(idxByKey.getOrElse(key, -1))
                        case Absent => rows
                end if
    end visibleRowsFor

    /** Lower a `Chart[A]` with a `DataSource.Live` signal to an `Svg.Root`.
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
      * axis (band scale) the category set is fixed to the initial categories; dynamic category insertion
      * requires re-resolving the x-scale reactively, which is not yet implemented.
      *
      * When `spec.animateCfg.enabled` is true, a chart-private `AtomicRef.Unsafe[TransState[A]]` is
      * created once and closed over by the reactive render function. The `TransState` carries three slots:
      * `lastRows` (the row chunk of the last committed render), `fromGeom` (animation origin), and
      * `currentGeom` (animation target). Each call to the render projection compares the incoming rows to
      * `lastRows`. A new emission writes the ref once; repeat pulls of the same emission reuse the stored
      * from/to and produce identical SVG, making the projection idempotent. Bar marks emit SMIL `animate`
      * children. Line and area marks emit a declarative SMIL `d` morph when the command-type signature
      * of the previous and new paths matches (same ordered sequence of MoveTo/LineTo/Close types); they
      * snap with no animate child when the signature differs (structural change such as a gap introduction
      * or a category add/remove).
      *
      * No `url(#id)` references are emitted; `Frame.internal` is safe.
      *
      * Unsafe boundary: `stateRef` uses `AtomicRef.Unsafe` so it can be read and written from within the
      * pure render function. The ref is private to this chart instance and writes occur only on genuine row
      * changes, so the bypass is sound.
      */
    private[kyo] def lowerLive[A](spec: Chart[A], signal: Signal[Chunk[A]], gradPrefix: String)(using Frame, AllowUnsafe): Svg.Root =
        val layout = ChartLayout.buildLayout(spec)
        // Use a fixed initial row set for the layout/x-scale/ysR-presence check.
        // The x-scale is computed from the signal's current value via initConst or the first emission.
        // We resolve from Chunk.empty to get a stable layout; the reactive region re-resolves the y-scale
        // per emission.
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
        // Resolve initial scales for the static frame using resolveAllScales.
        val ResolvedScales(xs, ysLInitial, ysRFixed) =
            ChartScales.resolveAllScales(
                initialRows,
                spec.marks,
                layout,
                spec.xScaleOverride,
                spec.yScaleOverride,
                computeRight,
                spec.xAxisCfg,
                spec.yAxisCfg,
                spec.yScaleRightOverride,
                spec.yAxisRightCfg.getOrElse(AxisConfig.default)
            )
        // For fixed domain, compute ysL once from the override; for inferred domain, ysL is recomputed per
        // emission inside the reactive region.
        val ysLFixed: Maybe[Scale] =
            if isYDomainFixed(spec.yScaleOverride) then Present(ysLInitial) else Absent
        // Static frame: drawn once, never changes.
        val ysLForFrame = ysLFixed.getOrElse(ysLInitial)
        val staticFrame = buildStaticFrameLive(layout, xs, ysLForFrame, ysRFixed, spec, initialRows)
        val vb          = Svg.ViewBox(0.0, 0.0, layout.svgW, layout.svgH)
        val baseSvg     = buildBaseSvg(spec, layout, vb)
        val withFrame = staticFrame.foldLeft(baseSvg): (acc, el) =>
            el match
                case r: Svg.Rect => acc(r)
                case l: Svg.Line => acc(l)
                case t: Svg.Text => acc(t)
                case g: Svg.G    => acc(g)
                // The static frame holds `Svg.SvgElement`s; `SvgElement` is a member of the `SvgChild` union, so
                // the residual arm is accepted by `acc` directly.
                case other => acc(other)
        // Create the chart-private transition-state ref when animation is enabled.
        // Unsafe: AtomicRef.Unsafe bypasses kyo effects tracking; sound because the ref is private to this
        // chart and writes occur only on genuine row changes (idempotent within any single emission).
        val stateRefMaybe: Maybe[AtomicRef.Unsafe[ChartTransitions.TransState[A]]] =
            if spec.animateCfg.enabled then
                Present(AtomicRef.Unsafe.init(ChartTransitions.TransState.empty[A]))
            else Absent
        // Create an internal hover ref when a tooltip is configured so shape handlers can drive it.
        // Unsafe: SignalRef.Unsafe.init bypasses kyo effects; sound because the ref is private to this chart.
        val internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = spec.tooltip match
            case Present(_) =>
                given CanEqual[Maybe[A], Maybe[A]] = CanEqual.derived
                Present(Signal.SignalRef.Unsafe.init[Maybe[A]](Absent).safe)
            case Absent => Absent
        // Reactive region: re-renders on each signal emission.
        val reactiveMarks: UI.Ast.Reactive[Svg.G] = signal.render: rows =>
            // Re-resolve scales from the current rows so categorical axes expand when categories are added.
            val ResolvedScales(xsLive, ysLLiveResolved, ysRLive) =
                ChartScales.resolveAllScales(
                    rows,
                    spec.marks,
                    layout,
                    spec.xScaleOverride,
                    spec.yScaleOverride,
                    computeRight,
                    spec.xAxisCfg,
                    spec.yAxisCfg,
                    spec.yScaleRightOverride,
                    spec.yAxisRightCfg.getOrElse(AxisConfig.default)
                )
            val ysLLive = ysLFixed.getOrElse(ysLLiveResolved)
            buildReactiveRegion(rows, spec, layout, xsLive, ysLLive, ysRLive, stateRefMaybe, gradPrefix, internalHoverRef)
        val withMarks = withFrame(reactiveMarks)
        // Append the tooltip overlay as the last child so it renders on top.
        (spec.tooltip, internalHoverRef) match
            case (Present(fn), Present(ref)) =>
                withMarks(ChartInteraction.buildTooltipOverlay(ref, fn, layout))
            case _ =>
                withMarks
        end match
    end lowerLive

    // ---- main entry point ----

    /** Lower a `Chart[A]` to an `Svg.Root`.
      *
      * Dispatches to `lowerStatic` for `DataSource.Static` and `lowerLive` for `DataSource.Live`.
      *
      * A fresh document-unique id prefix is allocated per lowering (`chartInstancePrefix`) so a
      * sequential-color gradient def gets an id distinct from any other lowered chart on the page, even
      * another lowering of the same spec. Within one chart the gradient def id and its `url(#id)` reference
      * always match (the def carries the prefix-derived `defId`, the swatch fill references the same id).
      */
    private[kyo] def lower[A](spec: Chart[A])(using AllowUnsafe): Svg.Root =
        given Frame    = Frame.internal
        val gradPrefix = ChartFoundations.chartInstancePrefix()
        spec.data match
            case DataSource.Static(rows) => lowerStatic(rows, spec, gradPrefix)
            case DataSource.Live(signal) => lowerLive(spec, signal, gradPrefix)
        end match
    end lower

    /** Lower a `Chart[A]` to an `Svg.Root` together with its resolved scales.
      *
      * The returned `ChartScales` projects the same resolved `xs`/`ysL`/`ysR` the lowering used, plus the inner
      * plot rectangle, so callers can compute exact chart pixel coordinates. For a live chart the scales are
      * resolved from the signal's current value at call time.
      */
    private[kyo] def lowerWithScales[A](spec: Chart[A])(using AllowUnsafe): (Svg.Root, Chart.Scales) =
        given Frame = Frame.internal
        val rows: Chunk[A] = spec.data match
            case DataSource.Static(rs)   => rs
            case DataSource.Live(signal) =>
                // For a live chart, sample the current emission so the scales match what is on screen now.
                // Unsafe: evalOrThrow runs the pure synchronous current-value read; the escape hatch is a
                // point-in-time projection, documented to reflect the signal value at call time.
                // Cannot throw: signal.current returns A < Sync (no Abort in scope), so Abort.run yields
                // Result.Ok and getOrThrow never executes the throw branch.
                Sync.Unsafe.evalOrThrow(signal.current)
        val layout = ChartLayout.buildLayout(spec)
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
            ChartScales.resolveAllScales(
                rows,
                spec.marks,
                layout,
                spec.xScaleOverride,
                spec.yScaleOverride,
                computeRight,
                spec.xAxisCfg,
                spec.yAxisCfg,
                spec.yScaleRightOverride,
                spec.yAxisRightCfg.getOrElse(AxisConfig.default)
            )
        val plot   = Chart.Scales.Rect(layout.plotX, layout.plotY, layout.plotW, layout.plotH)
        val scales = Chart.Scales.from(xs, ysL, ysR, plot)
        val svg    = lower(spec)
        (svg, scales)
    end lowerWithScales

    /** Build the root `<svg>` with responsive sizing and a11y attributes applied.
      *
      * Responsive: when `spec.isResponsive`, the root uses `width="100%"` and a `viewBox` (no fixed
      * pixel `height`) so the chart scales to its container; an explicit `aspectRatio` adds a `preserveAspectRatio`.
      * A11y: `spec.a11y.title`/`desc` become `<title>`/`<desc>` children; a title implies the generic
      * `role="img"` builder, and `ariaLabel` sets the generic `aria-label`. All attrs use the generic UI builders;
      * no chart-specific hardwiring in the renderer.
      */
    private def buildBaseSvg[A](spec: Chart[A], layout: Layout, vb: Svg.ViewBox)(using Frame): Svg.Root =
        val sized =
            if spec.isResponsive then
                val base = Svg.svg.width(Svg.SvgLength.Pct(100.0)).viewBox(vb)
                spec.aspectRatio match
                    case Present(_) =>
                        base.preserveAspectRatio(Svg.PreserveAspectRatio(Svg.Align.XMidYMid, Svg.MeetOrSlice.Meet))
                    case Absent => base
                end match
            else Svg.svg.width(layout.svgW).height(layout.svgH).viewBox(vb)
        val withTitle = spec.a11y.title.fold(sized)(t => sized(Svg.title(t)))
        val withDesc  = spec.a11y.desc.fold(withTitle)(d => withTitle(Svg.desc(d)))
        val withRole  = if spec.a11y.title.isDefined then withDesc.role("img") else withDesc
        spec.a11y.ariaLabel.fold(withRole)(lbl => withRole.aria("label", lbl))
    end buildBaseSvg

    private def lowerStatic[A](rows: Chunk[A], spec: Chart[A], gradPrefix: String)(using Frame, AllowUnsafe): Svg.Root =
        val layout = ChartLayout.buildLayout(spec)
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
            ChartScales.resolveAllScales(
                rows,
                spec.marks,
                layout,
                spec.xScaleOverride,
                spec.yScaleOverride,
                computeRight,
                spec.xAxisCfg,
                spec.yAxisCfg,
                spec.yScaleRightOverride,
                spec.yAxisRightCfg.getOrElse(AxisConfig.default)
            )
        val vb = Svg.ViewBox(0.0, 0.0, layout.svgW, layout.svgH)
        // Interactive hidden-series filter: the marks drop hidden rows; the legend keeps every
        // category (built from the full rows) so the user can still toggle a hidden series back on.
        val visibleRows = visibleRowsFor(rows, spec)
        val frame       = ChartAxes.buildFrame(layout, xs, ysL, ysR, spec, rows, gradPrefix)
        // Create an internal hover ref when a tooltip is configured so shape handlers can drive it.
        // Unsafe: SignalRef.Unsafe.init bypasses kyo effects; sound because the ref is private to this chart.
        val internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = spec.tooltip match
            case Present(_) =>
                given CanEqual[Maybe[A], Maybe[A]] = CanEqual.derived
                Present(Signal.SignalRef.Unsafe.init[Maybe[A]](Absent).safe)
            case Absent => Absent
        val marksG  = ChartMarks.marksRegion(visibleRows, spec.marks, layout, xs, ysL, ysR, Present(spec), internalHoverRef)
        val baseSvg = buildBaseSvg(spec, layout, vb)
        val withFrame = frame.foldLeft(baseSvg): (acc, el) =>
            el match
                case r: Svg.Rect => acc(r)
                case l: Svg.Line => acc(l)
                case t: Svg.Text => acc(t)
                case g: Svg.G    => acc(g)
                // The static frame holds `Svg.SvgElement`s; `SvgElement` is a member of the `SvgChild` union, so
                // the residual arm is accepted by `acc` directly.
                case other => acc(other)
        val withMarks = withFrame(marksG)
        // Append the tooltip overlay as the last child so it renders on top.
        (spec.tooltip, internalHoverRef) match
            case (Present(fn), Present(ref)) =>
                withMarks(ChartInteraction.buildTooltipOverlay(ref, fn, layout))
            case _ =>
                withMarks
        end match
    end lowerStatic

end ChartLower
