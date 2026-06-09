package kyo.internal

import kyo.*
import kyo.Chart.*
import kyo.Chart.Encoding
import kyo.UI.*
import kyo.UI.Ast.*

/** Per-mark SVG geometry for each of the seven Mark cases.
  *
  * Provides `marksRegion` (the top-level dispatch that builds the marks `Svg.G` for static data) and the
  * seven per-mark lowerers: `lowerBar`, `lowerLine`, `lowerText`, `lowerErrorBar`, `lowerArea`,
  * `lowerPoint`, and `lowerRule`. Helper functions (`lowerBarSimple`, `lowerBarGrouped`,
  * `lowerBarStacked`, `lowerLineSeries`, `buildSimpleAreaPath`, `lowerAreaStacked`, etc.) handle each
  * dispatch branch. All mark lowerers call into `ChartInteraction`, `ChartLegend`, and `ChartAxes` for
  * cross-cutting concerns; the unsafe-cast comment block below applies to those call sites.
  */
private[kyo] object ChartMarks:

    import ChartInteraction.Highlight
    import ChartLayout.Layout

    // Unsafe: the group/color encoding helpers in ChartLegend carry an `Any` element type because nothing
    // ever reads the value back through the type parameter: the value is keyed by its label string only.
    // `Plottable.any` (which plots any value by `toString`) paired with `summon[ConcreteTag[Any]]` is the
    // correct evidence at `Any`. The tag serves only as a structural `CatKey` identity component, never to
    // reconstruct or constrain an element type. Every `asInstanceOf` in this file's reach is at a justified
    // opaque-type/internal-erasure boundary, documented with its own inline comment.

    /** Stroke width (pixels) of the separating outline drawn around each `point` circle so that overlapping
      * or adjacent bubbles read as distinct discs rather than merging into one blob.
      */
    private val PointStrokeWidth = 1.5

    /** Default point radius when no `size` encoding is supplied. */
    private val DefaultRadius = 4.0

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
        spec: Maybe[Chart[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Svg.G =
        // Each mark with no explicit color encoding uses a DISTINCT palette color by its index so that a
        // multi-mark chart (e.g. a bar plus a line) shows e.g. blue bars and an orange line. A single-mark
        // chart uses palette(0). Grouped/stacked color encoding marks keep mapping color categories to the
        // palette (handled inside the per-mark lowerers).
        val theme = spec.map(_.theme).getOrElse(Theme.default)
        // Each `lower*` returns a `Chunk[<Svg subtype>]` (e.g. `Chunk[Svg.Rect]`); `Svg` subtypes are `UI` and
        // `Chunk` is covariant, so ascribing the per-mark result to `Chunk[UI]` upcasts each arm by covariance.
        val allShapes: Chunk[UI] = marks.zipWithIndex.flatMap: (mark, markIdx) =>
            val markColor = ChartAxes.markDefaultColor(theme, markIdx)
            val ys        = if ChartAxes.markAxisOf(mark) == Axis.Right then ysR.getOrElse(ysL) else ysL
            val region: Chunk[UI] = mark match
                case m: Mark.Bar[A, ?, ?] =>
                    spec match
                        case Present(s) =>
                            lowerBar(rows, m, layout, xs, ys, markColor, Present(s), internalHoverRef)
                        case Absent => lowerBar(rows, m, layout, xs, ys, markColor)
                case m: Mark.Line[A, ?, ?] =>
                    spec match
                        case Present(s) =>
                            lowerLine(
                                rows,
                                m,
                                xs,
                                ys,
                                markColor,
                                Present(s),
                                internalHoverRef,
                                ChartInteraction.resolveHighlight(Present(s))
                            )
                        case Absent => lowerLine(rows, m, xs, ys, markColor)
                case m: Mark.Area[A, ?, ?] =>
                    lowerArea(rows, m, layout, xs, ys, markColor, spec, internalHoverRef, ChartInteraction.resolveHighlight(spec))
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
                                ChartInteraction.resolveHighlight(Present(s))
                            )
                        case Absent => lowerPoint(rows, m, layout, xs, ys, markColor, theme = theme)
                case m: Mark.Rule[A] => lowerRuleChildren(m, layout, xs, ys)
                case m: Mark.Text[A, ?, ?] =>
                    lowerText(m, rows, xs, ys, markColor, theme, spec, ChartInteraction.resolveHighlight(spec))
                case m: Mark.ErrorBar[A, ?, ?] =>
                    lowerErrorBar(m, rows, xs, ys, markColor, theme, spec, ChartInteraction.resolveHighlight(spec))
            region
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
      * When the mark has no `color` encoding each row produces one rect spanning the full band. When a `color`
      * encoding is present the band is subdivided into sub-bands (grouped) or the rects stack vertically
      * (stacked, when `mark.stack.group` is `Present`).
      *
      * When `spec` is supplied, hover and click handlers are attached to each rect so `spec.onHover` and
      * `spec.onSelect` refs track the hovered/selected row. Stacked bars do not carry per-row interaction
      * because each stacked segment represents a partial view of a group rather than a single row.
      */
    private[kyo] def lowerBar[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        spec: Maybe[Chart[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val highlight = ChartInteraction.resolveHighlight(spec)
        mark.stack.group match
            case Present(_) =>
                lowerBarStacked(rows, mark, layout, xs, ys, spec)
            case Absent =>
                mark.color match
                    case Absent =>
                        lowerBarSimple(rows, mark, xs, ys, defaultColor, spec, internalHoverRef, highlight)
                    case Present(colorEnc) =>
                        lowerBarGrouped(
                            rows,
                            mark,
                            colorEnc,
                            xs,
                            ys,
                            spec,
                            internalHoverRef,
                            highlight
                        )
        end match
    end lowerBar

    private def lowerBarSimple[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        xs: Scale,
        ys: Scale,
        defaultFill: Style.Color,
        spec: Maybe[Chart[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        highlight: Maybe[Highlight[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        // Use ys.apply(0) as the zero-line baseline. For positive-only data, ensureZero makes 0
        // the domain minimum so ys.apply(0) == plotBaseline (byte-identical). For mixed
        // positive/negative data the zero line is above the plot bottom; using min/abs below
        // ensures non-negative rect heights.
        val baseline = ys.apply(Domain.Continuous(0.0))
        // A bar with no explicit color encoding uses the per-mark default fill (palette color by mark index),
        // not the browser default (black), so it is visible and distinct from other marks in a combo chart.
        // The bar rect is tagged with its source row so the built-in highlight can re-style the
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
                // drop non-finite domain values; filterFinite returns Absent for NaN/Inf
                val yDomain = ChartFoundations.filterFinite(mark.y.plottable.toDomain(mark.y.accessor(row)))
                val (nextBars, nextLabels) = yDomain match
                    case Absent => (bars, labels)
                    case Present(yd) =>
                        val xDomain = mark.x.plottable.toDomain(mark.x.accessor(row))
                        xDomain match
                            case Absent => (bars, labels)
                            case Present(xd) =>
                                val barX = xs.apply(xd)
                                val barW = xs.bandwidth
                                val barY = ys.apply(yd)
                                // min/abs ensure a non-negative rect height for negative data values:
                                // a negative datum maps barY above the baseline in SVG coordinates.
                                val rectY = math.min(barY, baseline)
                                val rectH = math.abs(baseline - barY)
                                val iAttrs = spec.map(s => ChartInteraction.buildInteractionAttrs(row, s, internalHoverRef)).getOrElse(
                                    UI.Ast.Attrs()
                                )
                                val baseRect = Svg.rect
                                    .x(barX)
                                    .y(rectY)
                                    .width(barW)
                                    .height(rectH)
                                    .fill(Svg.Paint.Color(defaultFill))
                                    .withAttrs(iAttrs)
                                val (rectEl, labelElems) = applyBarEncodings(baseRect, mark, row, barX, barW, rectY, defaultFill)
                                (bars.append((row, rectEl)), labels ++ labelElems)
                        end match
                loop(i + 1, nextBars, nextLabels)
        val (bars, labels) = loop(0, Chunk.empty, Chunk.empty)
        ChartInteraction.withHighlight(bars, highlight) ++ labels
    end lowerBarSimple

    /** Apply the opacity, tooltip, and label encodings to a bar rect for row `row`.
      *
      * Returns the decorated rect plus any emitted label `Svg.text` (Chunk.empty when no
      * `label` encoding). Shared by the static (`lowerBarSimple`) and transition
      * (`lowerBarSimpleWithTransitions`) paths so the two cannot drift: both honor `opacity`/`tooltip`/
      * `label` identically. `barX`/`barW`/`barY` are the already-projected rect geometry; `fill` is the
      * resolved fill color (passed in so the caller keeps control of palette resolution).
      */
    private[kyo] def applyBarEncodings[A, X, Y](
        rect: Svg.Rect,
        mark: Mark.Bar[A, X, Y],
        row: A,
        barX: Double,
        barW: Double,
        barY: Double,
        fill: Style.Color
    )(using Frame): (Svg.Rect, Chunk[Svg.SvgElement]) =
        // Opacity encoding.
        val withOpacity = mark.opacity match
            case Present(fn) => rect.fillOpacity(math.max(0.0, math.min(1.0, fn(row))))
            case Absent      => rect
        // Tooltip encoding.
        val withTooltip = mark.tooltip match
            case Present(fn) => withOpacity(Svg.title(fn(row)))
            case Absent      => withOpacity
        // Label encoding: emit Svg.text above the bar.
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
                        .fill(Svg.Paint.Color(fill))
                        .apply(fn(row))
                )
            case Absent => Chunk.empty
        (withTooltip, labelElems)
    end applyBarEncodings

    private def lowerBarGrouped[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Bar[A, X, Y],
        colorEnc: Encoding[A, ?],
        xs: Scale,
        ys: Scale,
        spec: Maybe[Chart[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        highlight: Maybe[Highlight[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        // Collect distinct color categories (label + raw value) in enum-ordinal order.
        // The raw values feed the same Sequential-aware palette resolution lowerPoint/lowerArea use, so a
        // numeric `color` encoding under a Sequential `colorScale` paints each bar with its gradient color.
        val colorCats: Chunk[(String, Any)] = ChartLegend.collectColorCategoriesWithRaw(rows, colorEnc)
        val colorKeys: Chunk[String]        = colorCats.map(_._1)
        val numColors                       = colorKeys.size
        // Precompute colorCatKey -> index once (O(colors)); keys by CatKey (tag + raw value) so distinct
        // color values with the same toString remain separate.
        val colorIdxByKey: Map[ChartFoundations.CatKey, Int] =
            colorCats.zipWithIndex.foldLeft(Map.empty[ChartFoundations.CatKey, Int]): (m, catWithIdx) =>
                val (cat, idx) = catWithIdx
                val catKey     = ChartFoundations.categoryKey(colorEnc.tag, cat._2)
                m.updated(catKey, idx)
        val basePalette: Chunk[Style.Color] = spec match
            case Present(s) => ChartAxes.themePalette(s.theme)
            case Absent     => ChartAxes.DefaultPalette
        // A Present colorScale (Categorical or Sequential) resolves through resolvePalette; an Absent one
        // assigns palette colors by category index.
        val palette: Chunk[Style.Color] = spec match
            case Present(s) =>
                s.legendCfg.colorScale match
                    case Present(_) => ChartLegend.resolvePalette(s, colorCats)
                    case Absent =>
                        colorKeys.zipWithIndex.map: (_, i) =>
                            basePalette(i % basePalette.size)
            case Absent =>
                colorKeys.zipWithIndex.map: (_, i) =>
                    basePalette(i % basePalette.size)
        // Use ys.apply(0) as the zero-line baseline (same reason as lowerBarSimple).
        val baseline = ys.apply(Domain.Continuous(0.0))

        // Degenerate-grouping guard: a `color` encoding only DODGES (subdivides a band into sub-slots) when
        // MULTIPLE distinct colors actually share the SAME x-band. When `color` is 1:1 with `x` (e.g.
        // bar(x=_.label, color=_.label)), every band holds exactly one color; dodging each band's single bar
        // into its global color sub-slot would march thin bars left-to-right, misaligned with the centered
        // x-axis tick labels. So compute the max distinct CatKeys present within any single x-band; when
        // that max is <= 1, render SIMPLE full-band bars (slot-centered, full bandwidth) instead of dodging.
        // Keys by CatKey so two distinct values with the same toString are counted as two distinct colors.
        val bandColorSets: Map[String, Set[ChartFoundations.CatKey]] =
            rows.foldLeft(Map.empty[String, Set[ChartFoundations.CatKey]]): (m, row) =>
                val xKeyMaybe = mark.x.plottable.toDomain(mark.x.accessor(row)).map(ChartScales.domainKey)
                xKeyMaybe match
                    case Present(xKey) =>
                        val colorCatKey = ChartFoundations.categoryKey(colorEnc.tag, colorEnc.accessor(row))
                        m.updated(xKey, m.getOrElse(xKey, Set.empty[ChartFoundations.CatKey]) + colorCatKey)
                    case Absent => m
                end match
        val maxColorsPerBand: Int =
            bandColorSets.foldLeft(0)((mx, kv) => math.max(mx, kv._2.size))
        val dodge = maxColorsPerBand > 1

        // For sparse grouped bars: per x-band, collect the global color indices present (ascending order).
        // When the band has all numColors categories, present = Chunk(0, 1, ..., numColors-1), so
        // groupOffset == 0 and localIdx == colorIdx: barX reduces to the simple dense formula.
        val presentByBand: Map[String, Chunk[Int]] =
            if dodge then
                bandColorSets.map: (xKey, catKeySet) =>
                    val indices = colorIdxByKey.collect:
                        case (catKey, idx) if catKeySet.contains(catKey) => idx
                    xKey -> Chunk.from(indices).sorted
            else Map.empty[String, Chunk[Int]]

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
                                val bandX       = xs.apply(xd)
                                val bandW       = xs.bandwidth
                                val colorCatKey = ChartFoundations.categoryKey(colorEnc.tag, colorEnc.accessor(row))
                                val colorIdx    = colorIdxByKey.getOrElse(colorCatKey, -1)
                                // Dodge only when a real grouped bar (some band holds >1 color); otherwise the
                                // bar spans the full band, slot-centered under its x tick label.
                                val subW = if dodge then bandW / numColors.toDouble else bandW
                                val barX =
                                    if dodge then
                                        val xKey    = ChartScales.domainKey(xd)
                                        val present = presentByBand.getOrElse(xKey, Chunk.empty[Int])
                                        val k       = present.size
                                        val localIdx =
                                            // indexOf on Chunk is O(k); k <= numColors (small in practice).
                                            val idx = present.toSeq.indexOf(colorIdx)
                                            if idx < 0 then 0 else idx
                                        end localIdx
                                        // Center the k present bars within the full bandW.
                                        // When k == numColors: groupOffset == 0, localIdx == colorIdx (dense case).
                                        val groupOffset = (bandW - k.toDouble * subW) / 2.0
                                        bandX + groupOffset + localIdx.toDouble * subW
                                    else bandX
                                val barY = ys.apply(yd)
                                // min/abs ensure a non-negative rect height for negative data values.
                                val rectY     = math.min(barY, baseline)
                                val rectH     = math.abs(baseline - barY)
                                val fillColor = if colorIdx >= 0 && colorIdx < palette.size then palette(colorIdx) else basePalette(0)
                                val iAttrs = spec.map(s => ChartInteraction.buildInteractionAttrs(row, s, internalHoverRef)).getOrElse(
                                    UI.Ast.Attrs()
                                )
                                val r =
                                    Svg.rect.x(barX).y(rectY).width(subW).height(rectH).fill(Svg.Paint.Color(fillColor)).withAttrs(iAttrs)
                                acc.append((row, r))
                        end match
                loop(i + 1, nextAcc)
        ChartInteraction.withHighlight(loop(0, Chunk.empty), highlight)
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
        spec: Maybe[Chart[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val groupFn  = mark.stack.group.getOrElse((_: A) => "")
        val baseline = layout.plotBaseline
        val plotTop  = layout.plotY

        // 1. Collect all distinct x keys in encounter order (O(rows), Set-backed via distinctKeyed).
        val presentXKeys: Chunk[String] = rows.foldLeft(Chunk.empty[String]): (acc, row) =>
            mark.x.plottable.toDomain(mark.x.accessor(row)) match
                case Present(d) => acc.append(ChartScales.domainKey(d))
                case Absent     => acc
        val xKeys: Chunk[String] =
            ChartFoundations.distinctKeyed(presentXKeys, k => ChartFoundations.categoryKey(k)).map(_._2)

        // 2. Collect all distinct group keys (with their raw values) in enum-ordinal order.
        // The raw values feed the same palette resolution the legend uses, so each stacked segment is
        // painted in exactly the color its legend swatch shows (honoring an explicit `colorScale`).
        val groupEnc: Encoding[A, Any] =
            Encoding(groupFn, Plottable.any, summon[ConcreteTag[Any]])
        val groupCats: Chunk[(String, Any)] = ChartLegend.collectColorCategoriesWithRaw(rows, groupEnc)
        val groupKeys: Chunk[String]        = groupCats.map(_._1)
        val groupPalette: Chunk[Style.Color] = spec match
            case Present(s) => ChartLegend.resolvePalette(s, groupCats)
            case Absent     => ChartLegend.resolvePaletteFromCfg(groupKeys)

        // 3. Build, in one pass: xKey -> groupCatKey -> yValue (dataMap), xKey -> first-seen x Domain
        // (xDomainByKey, for band positioning), and (xKey, groupCatKey) -> first-seen row (rowBySlot, for
        // per-datum encodings). Keys by CatKey (value-equality via categoryKey) instead of toString so two
        // distinct group values with colliding toString remain separate.
        final case class StackMaps(
            data: Map[String, Map[ChartFoundations.CatKey, Double]],
            xDomainByKey: Map[String, Domain],
            rowBySlot: Map[(String, ChartFoundations.CatKey), A]
        )
        @scala.annotation.tailrec
        def buildMap(i: Int, m: StackMaps): StackMaps =
            if i >= rows.size then m
            else
                val row        = rows(i)
                val xDomainOpt = mark.x.plottable.toDomain(mark.x.accessor(row))
                val xKeyOpt = xDomainOpt match
                    case Present(d) => Present(ChartScales.domainKey(d))
                    case Absent     => Absent
                val yValOpt = mark.y.plottable.toDomain(mark.y.accessor(row)) match
                    case Present(Domain.Continuous(v)) => Present(v)
                    case _                             => Absent
                // Key by CatKey (tag + raw value) so distinct group values with the same toString stay
                // separate. groupEnc.tag is ConcreteTag[Any]; groupFn(row) is the raw group value.
                val groupCatKey = ChartFoundations.categoryKey(groupEnc.tag, groupFn(row))
                val withDomain = (xKeyOpt, xDomainOpt) match
                    case (Present(xk), Present(d)) if !m.xDomainByKey.contains(xk) =>
                        m.copy(xDomainByKey = m.xDomainByKey.updated(xk, d))
                    case _ => m
                // rowBySlot records the first row per (xKey, groupCatKey) regardless of y. The slot's row is
                // only consulted when its summed y is non-zero, but it is keyed first-seen by x+group alone.
                val withSlot = xKeyOpt.fold(withDomain) { xk =>
                    val slotKey = (xk, groupCatKey)
                    if withDomain.rowBySlot.contains(slotKey) then withDomain
                    else withDomain.copy(rowBySlot = withDomain.rowBySlot.updated(slotKey, row))
                }
                val next = (xKeyOpt, yValOpt) match
                    case (Present(xk), Present(yv)) =>
                        val inner = withSlot.data.getOrElse(xk, Map.empty)
                        withSlot.copy(data = withSlot.data.updated(xk, inner.updated(groupCatKey, yv)))
                    case _ => withSlot
                buildMap(i + 1, next)
        val stackMaps    = buildMap(0, StackMaps(Map.empty, Map.empty, Map.empty))
        val dataMap      = stackMaps.data
        val xDomainByKey = stackMaps.xDomainByKey
        val rowBySlot    = stackMaps.rowBySlot

        // 4. For each x key, emit stacked rects.
        // groupCatKeys: one CatKey per category in encounter/ordinal order, derived from the raw values
        // in groupCats (mirrors how collectColorCategoriesWithRaw keyed them in step 2 above).
        val groupCatKeys: Chunk[ChartFoundations.CatKey] =
            groupCats.map { case (_, raw) => ChartFoundations.categoryKey(groupEnc.tag, raw) }

        @scala.annotation.tailrec
        def loopX(xi: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if xi >= xKeys.size then acc
            else
                val xKey = xKeys(xi)
                // O(1) lookup of the first-seen x Domain for this key (precomputed in buildMap).
                val bandX = Maybe.fromOption(xDomainByKey.get(xKey)) match
                    case Present(d) => xs.apply(d)
                    case Absent     => xs.apply(Domain.Category(xKey))
                val bandW    = xs.bandwidth
                val groupMap = dataMap.getOrElse(xKey, Map.empty)
                val totalY   = groupCatKeys.foldLeft(0.0)((s, gck) => s + groupMap.getOrElse(gck, 0.0))

                // posAcc and negAcc are threaded as loopGroup parameters, reset to 0.0 for each
                // new x-key iteration. posAcc accumulates positive contributions upward
                // from the baseline; negAcc accumulates negative contributions downward.
                @scala.annotation.tailrec
                def loopGroup(gi: Int, posAcc: Double, negAcc: Double, acc2: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
                    if gi >= groupCatKeys.size then acc2
                    else
                        val gck  = groupCatKeys(gi)
                        val rawY = groupMap.getOrElse(gck, 0.0)
                        val effectiveY =
                            if mark.stack.normalize then
                                if totalY > 0.0 then rawY / totalY else 0.0
                            else rawY
                        // Signed stack: positive values stack upward; negative values stack downward.
                        // (segLo, segHi) bound the segment; nextPosAcc/nextNegAcc carry forward.
                        val (segLo, segHi, nextPosAcc, nextNegAcc) =
                            if mark.stack.normalize then
                                // Normalize mode stacks only positive values, all upward from the baseline.
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
                        val rectY = math.min(topPx, botPx)
                        val rectH = math.abs(topPx - botPx)
                        val fillColor = if gi < groupPalette.size then groupPalette(gi)
                        else ChartAxes.DefaultPalette(gi % ChartAxes.DefaultPalette.size)
                        // Skip emission when the group contributes nothing at this x slot.
                        val nextAcc2 =
                            if rawY == 0.0 then acc2
                            else
                                // Apply opacity encoding if present.
                                val baseRect = Svg.rect
                                    .x(bandX)
                                    .y(rectY)
                                    .width(bandW)
                                    .height(rectH)
                                    .fill(Svg.Paint.Color(fillColor))
                                // Look up the row for this x+group combination to apply per-datum encodings.
                                // (There is at most one row per x+group; rowBySlot holds the first match.)
                                val rowForSlot: Maybe[A] = Maybe.fromOption(rowBySlot.get((xKey, gck)))
                                val withEncodings: Chunk[Svg.SvgElement] = rowForSlot match
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
                                acc2 ++ withEncodings
                        loopGroup(gi + 1, nextPosAcc, nextNegAcc, nextAcc2)
                val rectsForX = loopGroup(0, 0.0, 0.0, Chunk.empty)
                loopX(xi + 1, acc ++ rectsForX)
        loopX(0, Chunk.empty)
    end lowerBarStacked

    /** Lower a `Mark.Line` to a `Chunk` of `Svg.Path`s.
      *
      * Each `Absent` y value (gap) closes the current sub-path and starts a new `MoveTo` segment. The result may
      * contain multiple `MoveTo` commands in a single `PathData` (one for each contiguous run of defined points).
      * When a `color` encoding is present each distinct color series produces its own path.
      *
      * When `spec` is supplied, hover and click handlers are attached to each path. A line path represents
      * all rows in a series; the hover handler publishes `Present(firstRow)` (the series representative row,
      * i.e. the first element of the series chunk). Per-row interaction is better suited to `point` marks
      * layered over the line.
      */
    private[kyo] def lowerLine[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Line[A, X, Y],
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        spec: Maybe[Chart[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        highlight: Maybe[Highlight[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        mark.color match
            case Absent =>
                // No color encoding: single series whose stroke is the per-mark default color (palette by mark index).
                // A line path has no per-row identity, so it is tagged with its first row as the series
                // representative for the highlight wrap.
                val path   = lowerLineSeries(rows, mark, xs, ys, defaultColor, spec, internalHoverRef)
                val repRow = rows.headMaybe
                val tagged: Chunk[(A, Svg.SvgElement)] = repRow match
                    case Present(r) => Chunk((r, path))
                    case Absent     => Chunk.empty
                ChartInteraction.withHighlight(tagged, highlight)
            case Present(colorEnc) =>
                // resolvePalette (the same path the legend and stacked bars use) honors an explicit
                // categorical/sequential colorScale so the line agrees with the legend, and falls back to
                // theme.palette then DefaultPalette when no colorScale is set.
                val cats: Chunk[(String, Any)] = ChartLegend.collectColorCategoriesWithRaw(rows, colorEnc)
                val palette: Chunk[Style.Color] = spec match
                    case Present(s) => ChartLegend.resolvePalette(s, cats)
                    case Absent     => ChartAxes.DefaultPalette
                // Split rows by CatKey (tag + raw value), NOT by label toString: distinct color values
                // with a colliding toString must stay in separate series. `cats` was deduped by CatKey,
                // so cats[idx] aligns with the catKey filter at the same index.
                val catKeys: Chunk[ChartFoundations.CatKey] =
                    cats.map { case (_, raw) => ChartFoundations.categoryKey(colorEnc.tag, raw) }
                // Pre-build a single-pass groupBy so each catKey lookup is O(1) rather than O(N).
                val rowsByKey = ChartFoundations.groupByKey(rows, r => ChartFoundations.categoryKey(colorEnc.tag, colorEnc.accessor(r)))
                val tagged: Chunk[(A, Svg.SvgElement)] = catKeys.zipWithIndex.flatMap: (catKey, idx) =>
                    val seriesRows  = rowsByKey.getOrElse(catKey, Chunk.empty)
                    val strokeColor = palette(idx % palette.size)
                    val path        = lowerLineSeries(seriesRows, mark, xs, ys, strokeColor, spec, internalHoverRef)
                    // The series-representative row is the first row of the series chunk.
                    seriesRows.headMaybe match
                        case Present(r) => Chunk((r, path))
                        case Absent     => Chunk.empty
                ChartInteraction.withHighlight(tagged, highlight)
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
                                    // Centre on the band (xs.apply is the band LEFT edge; bandwidth is 0 for
                                    // continuous scales) so line vertices align with the centred tick labels.
                                    val px = xs.apply(x) + xs.bandwidth / 2.0
                                    val py = ys.apply(y)
                                    // Skip non-finite pixel coordinates: they produce invisible or corrupt SVG paths.
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

    private[kyo] def lowerLineSeries[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Line[A, X, Y],
        xs: Scale,
        ys: Scale,
        strokeColor: Style.Color = Style.Color.blue,
        spec: Maybe[Chart[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Svg.Path =
        // Collect contiguous defined segments. Each segment is then threaded through
        // CurvePath.append for the chosen interpolation.
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
        // Opacity encoding: for a line, apply as strokeOpacity.
        val withOpacity = mark.opacity match
            case Present(fn) =>
                // Use the first row to evaluate line-level opacity (series-level encoding, not per-datum).
                rows.headMaybe match
                    case Present(r) => basePath.strokeOpacity(math.max(0.0, math.min(1.0, fn(r))))
                    case Absent     => basePath
            case Absent => basePath
        // Tooltip encoding: attach a title child on the path element for browser tooltip.
        val withTooltip = mark.tooltip match
            case Present(fn) =>
                rows.headMaybe match
                    case Present(r) => withOpacity(Svg.title(fn(r)))
                    case Absent     => withOpacity
            case Absent => withOpacity
        // Attach interaction attrs to the line path. The representative row for a line series
        // is the first row of the series chunk (path-level, not per-datum).
        spec match
            case Absent => withTooltip
            case Present(s) =>
                rows.headMaybe match
                    case Absent     => withTooltip
                    case Present(r) => withTooltip.withAttrs(ChartInteraction.buildInteractionAttrs(r, s, internalHoverRef))
        end match
    end lowerLineSeries

    /** Lower a `Mark.Text` to one `Svg.text` per row at `(x, y)`.
      *
      * Gap rows (where the `y` EncodingMaybe returns `Absent`) produce no text element. The
      * `anchor` is mapped from `kyo.TextAnchor` to `Svg.TextAnchor`; both enums have the same
      * case names but live in different packages and must not be cast. When `mark.color` is
      * `Present`, each row receives its category palette color; otherwise `defaultColor` is used.
      * `opacity` sets `fillOpacity` clamped to [0,1].
      */
    private[kyo] def lowerText[A, X, Y](
        mark: Mark.Text[A, X, Y],
        rows: Chunk[A],
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        theme: Theme,
        spec: Maybe[Chart[A]] = Absent,
        highlight: Maybe[Highlight[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        // TextAnchor mapping: two distinct enums, explicit match required.
        val svgAnchor = mark.anchor match
            case TextAnchor.Start  => Svg.TextAnchor.Start
            case TextAnchor.Middle => Svg.TextAnchor.Middle
            case TextAnchor.End    => Svg.TextAnchor.End
        // Resolve per-category palette when mark.color is Present.
        val colorCatsWithRaw: Chunk[(String, Any)] = mark.color match
            case Present(ch) => ChartLegend.collectColorCategoriesWithRaw(rows, ch)
            case Absent      => Chunk.empty
        val basePaletteText: Chunk[Style.Color] = ChartAxes.themePalette(theme)
        val palette: Chunk[Style.Color] =
            if colorCatsWithRaw.isEmpty then Chunk.empty
            else
                spec match
                    case Present(s) => ChartLegend.resolvePalette(s, colorCatsWithRaw)
                    case Absent     => colorCatsWithRaw.zipWithIndex.map((_, i) => basePaletteText(i % basePaletteText.size))
        // Precompute CatKey (tag + raw value) -> index once, so distinct color values with a colliding
        // toString resolve to distinct palette indices (not collapsed onto one label bucket). Matching
        // `mark.color` here keeps the encoding's tag a fresh existential so `categoryKey` infers its element
        // type with no widening cast.
        val catIdxText: Map[ChartFoundations.CatKey, Int] =
            mark.color match
                case Present(colorEnc) =>
                    colorCatsWithRaw.zipWithIndex.foldLeft(Map.empty[ChartFoundations.CatKey, Int]): (m, ci) =>
                        val key = ChartFoundations.categoryKey(colorEnc.tag, ci._1._2)
                        if m.contains(key) then m else m.updated(key, ci._2)
                case Absent => Map.empty
        // Accumulate row-tagged glyphs so withHighlight can re-style the active row.
        @scala.annotation.tailrec
        def loop(i: Int, acc: Chunk[(A, Svg.SvgElement)]): Chunk[(A, Svg.SvgElement)] =
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
                                // For a band/categorical x, xs.apply(x) is the band's LEFT edge (where bars start).
                                // Centre the label on the band (the same x the bar is centred on) by adding half
                                // the band width. For continuous scales bandwidth is 0, so this is a no-op there.
                                val px = xs.apply(x) + xs.bandwidth / 2.0
                                val py = ys.apply(y)
                                val fillColor: Style.Color = mark.color match
                                    case Absent => defaultColor
                                    case Present(ch) =>
                                        val catKey = ChartFoundations.categoryKey(ch.tag, ch.accessor(row))
                                        val idx    = catIdxText.getOrElse(catKey, -1)
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
                                // Tag the glyph with its source row for highlight.
                                acc.append((row, withOpacity(mark.label(row))))
                            case _ => acc
                        end match
                loop(i + 1, nextAcc)
        ChartInteraction.withHighlight(loop(0, Chunk.empty), highlight)
    end lowerText

    /** Lower a `Mark.ErrorBar` to SVG elements per row.
      *
      * Each row yields: one vertical `Svg.line` from `low` to `high` at `x`, two horizontal cap
      * `Svg.line`s of `capWidth` pixels centered at `x`, and one `Svg.circle` center marker at `y`.
      * All elements use plain `Svg.line` and `Svg.circle` with NO `url(#id)` or `<marker>` references.
      * When `mark.color` is `Present`, each row's stroke uses its category palette color.
      */
    private[kyo] def lowerErrorBar[A, X, Y](
        mark: Mark.ErrorBar[A, X, Y],
        rows: Chunk[A],
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        theme: Theme,
        spec: Maybe[Chart[A]] = Absent,
        highlight: Maybe[Highlight[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val colorCatsWithRaw: Chunk[(String, Any)] = mark.color match
            case Present(ch) => ChartLegend.collectColorCategoriesWithRaw(rows, ch)
            case Absent      => Chunk.empty
        val basePaletteErr: Chunk[Style.Color] = ChartAxes.themePalette(theme)
        val palette: Chunk[Style.Color] =
            if colorCatsWithRaw.isEmpty then Chunk.empty
            else
                spec match
                    case Present(s) => ChartLegend.resolvePalette(s, colorCatsWithRaw)
                    case Absent     => colorCatsWithRaw.zipWithIndex.map((_, i) => basePaletteErr(i % basePaletteErr.size))
        // Precompute CatKey (tag + raw value) -> index once, so distinct color values with a colliding
        // toString resolve to distinct palette indices (not collapsed onto one label bucket).
        val catIdxErr: Map[ChartFoundations.CatKey, Int] =
            mark.color match
                case Present(colorEnc) =>
                    colorCatsWithRaw.zipWithIndex.foldLeft(Map.empty[ChartFoundations.CatKey, Int]): (m, ci) =>
                        val key = ChartFoundations.categoryKey(colorEnc.tag, ci._1._2)
                        if m.contains(key) then m else m.updated(key, ci._2)
                case Absent => Map.empty
        val halfCap = mark.capWidth / 2.0
        highlight match
            case Absent =>
                // No highlight: emit the 4 sub-shapes per row flat.
                @scala.annotation.tailrec
                def loopFlat(i: Int, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
                    if i >= rows.size then acc
                    else
                        val row   = rows(i)
                        val xd    = mark.x.plottable.toDomain(mark.x.accessor(row))
                        val yd    = mark.y.plottable.toDomain(mark.y.accessor(row))
                        val lowD  = mark.low.plottable.toDomain(mark.low.accessor(row))
                        val highD = mark.high.plottable.toDomain(mark.high.accessor(row))
                        val nextAcc = (xd, yd, lowD, highD) match
                            case (Present(x), Present(y), Present(lo), Present(hi)) =>
                                val px     = xs.apply(x) + xs.bandwidth / 2.0
                                val py     = ys.apply(y)
                                val pyLow  = ys.apply(lo)
                                val pyHigh = ys.apply(hi)
                                val colorIdx = mark.color match
                                    case Absent => -1
                                    case Present(ch) =>
                                        val catKey = ChartFoundations.categoryKey(ch.tag, ch.accessor(row))
                                        catIdxErr.getOrElse(catKey, -1)
                                val color: Style.Color =
                                    if colorIdx >= 0 && colorIdx < palette.size then palette(colorIdx) else defaultColor
                                val stroke = Svg.Paint.Color(color)
                                val vLine  = Svg.line.x1(px).y1(pyLow).x2(px).y2(pyHigh).stroke(stroke).strokeWidth(1.5)
                                val capLow = Svg.line.x1(px - halfCap).y1(pyLow).x2(px + halfCap).y2(pyLow).stroke(stroke).strokeWidth(1.5)
                                val capHigh =
                                    Svg.line.x1(px - halfCap).y1(pyHigh).x2(px + halfCap).y2(pyHigh).stroke(stroke).strokeWidth(1.5)
                                val marker = Svg.circle.cx(px).cy(py).r(3.0).fill(stroke)
                                acc.append(vLine).append(capLow).append(capHigh).append(marker)
                            case _ => acc
                        loopFlat(i + 1, nextAcc)
                loopFlat(0, Chunk.empty)
            case Present(_) =>
                // Highlight present: group each row's 4 sub-shapes into an Svg.g tagged with the row.
                // Grouping ensures applyHighlightStyle fires ONCE on the group (not 4 times on sub-shapes),
                // so exactly 1 stroke="#000000" appears per highlighted row. SVG stroke inheritance propagates
                // the highlight stroke to all child lines and the circle.
                @scala.annotation.tailrec
                def loopGrouped(i: Int, acc: Chunk[(A, Svg.SvgElement)]): Chunk[(A, Svg.SvgElement)] =
                    if i >= rows.size then acc
                    else
                        val row   = rows(i)
                        val xd    = mark.x.plottable.toDomain(mark.x.accessor(row))
                        val yd    = mark.y.plottable.toDomain(mark.y.accessor(row))
                        val lowD  = mark.low.plottable.toDomain(mark.low.accessor(row))
                        val highD = mark.high.plottable.toDomain(mark.high.accessor(row))
                        val nextAcc = (xd, yd, lowD, highD) match
                            case (Present(x), Present(y), Present(lo), Present(hi)) =>
                                val px     = xs.apply(x) + xs.bandwidth / 2.0
                                val py     = ys.apply(y)
                                val pyLow  = ys.apply(lo)
                                val pyHigh = ys.apply(hi)
                                val colorIdx = mark.color match
                                    case Absent => -1
                                    case Present(ch) =>
                                        val catKey = ChartFoundations.categoryKey(ch.tag, ch.accessor(row))
                                        catIdxErr.getOrElse(catKey, -1)
                                val color: Style.Color =
                                    if colorIdx >= 0 && colorIdx < palette.size then palette(colorIdx) else defaultColor
                                val stroke = Svg.Paint.Color(color)
                                val vLine  = Svg.line.x1(px).y1(pyLow).x2(px).y2(pyHigh).stroke(stroke).strokeWidth(1.5)
                                val capLow = Svg.line.x1(px - halfCap).y1(pyLow).x2(px + halfCap).y2(pyLow).stroke(stroke).strokeWidth(1.5)
                                val capHigh =
                                    Svg.line.x1(px - halfCap).y1(pyHigh).x2(px + halfCap).y2(pyHigh).stroke(stroke).strokeWidth(1.5)
                                val marker = Svg.circle.cx(px).cy(py).r(3.0).fill(stroke)
                                val rowG   = Svg.g(vLine)(capLow)(capHigh)(marker)
                                acc.append((row, rowG))
                            case _ => acc
                        loopGrouped(i + 1, nextAcc)
                ChartInteraction.withHighlight(loopGrouped(0, Chunk.empty), highlight)
        end match
    end lowerErrorBar

    /** Build one closed area path for a single series from `seriesRows`.
      *
      * Collects finite (px, py) points from `seriesRows` using the same band-centre projection as
      * `lowerArea` (xs.apply(x) + xs.bandwidth / 2.0). Returns Absent when no finite points exist
      * (e.g. all rows have undefined y), so the caller can flatMap over the Maybe and silently omit
      * empty series, consistent with `lowerArea`'s own empty-guard.
      *
      * The path is always CLOSED: top edge via CurvePath.append, then lineTo the last x at baseline,
      * lineTo the first x at baseline, then close. fillOpacity defaults to 0.7.
      * The `mark.opacity` encoding overrides fillOpacity when Present; the encoding is sampled from the
      * first row in `seriesRows` (the series-representative row, same as the interaction anchor).
      *
      * `fill` is the resolved fill color (the caller handles palette resolution so this helper is
      * color-agnostic). `spec` and `internalHoverRef` are forwarded to `buildInteractionAttrs`
      * unchanged, attaching click/hover handlers to the path.
      */
    private[kyo] def buildSimpleAreaPath[A, X, Y](
        seriesRows: Chunk[A],
        mark: Mark.Area[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        fill: Style.Color,
        spec: Maybe[Chart[A]],
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]]
    )(using Frame): Maybe[Svg.SvgElement] =
        mark.y match
            case Absent => Absent
            case Present(yEnc) =>
                val baseline = layout.plotBaseline
                // Collect (px, py) pairs, skipping non-finite values (NaN/Infinity produce corrupt SVG paths).
                val points: Chunk[(Double, Double)] = seriesRows.flatMap: row =>
                    yEnc.accessor(row) match
                        case Absent => Chunk.empty
                        case Present(yv) =>
                            val xd = mark.x.plottable.toDomain(mark.x.accessor(row))
                            val yd = yEnc.plottable.toDomain(yv)
                            (xd, yd) match
                                case (Present(x), Present(y)) =>
                                    // Centre on the band (xs.apply is the band LEFT edge; bandwidth is 0
                                    // for continuous scales) so the area aligns with the centred tick labels.
                                    val px = xs.apply(x) + xs.bandwidth / 2.0
                                    val py = ys.apply(y)
                                    if java.lang.Double.isFinite(px) && java.lang.Double.isFinite(py) then
                                        Chunk((px, py))
                                    else Chunk.empty
                                case _ => Chunk.empty
                            end match
                if points.isEmpty then Absent
                else
                    // Top edge forward via CurvePath.
                    val startPd = Svg.PathData.from(points(0)._1, points(0)._2)
                    val topPd   = CurvePath.append(startPd, points.drop(1), mark.curve)
                    // Baseline back: from last x at baseline to first x at baseline, then close.
                    val lastX  = points(points.size - 1)._1
                    val firstX = points(0)._1
                    val pd2    = topPd.lineTo(lastX, baseline).lineTo(firstX, baseline).close
                    // Apply opacity encoding if present; default fillOpacity=0.7.
                    val baseOpacity = 0.7
                    val opacity = mark.opacity match
                        case Present(fn) =>
                            seriesRows.headMaybe.map(r => math.max(0.0, math.min(1.0, fn(r)))).getOrElse(baseOpacity)
                        case Absent => baseOpacity
                    val basePath = Svg.path.d(pd2).fill(Svg.Paint.Color(fill)).fillOpacity(opacity)
                    val withTooltip = mark.tooltip match
                        case Present(fn) =>
                            seriesRows.headMaybe match
                                case Present(r) => basePath(Svg.title(fn(r)))
                                case Absent     => basePath
                        case Absent => basePath
                    // Attach interaction attrs to the area path.
                    // The representative row is the first defined row.
                    val withInteraction = spec match
                        case Absent => withTooltip
                        case Present(s) =>
                            seriesRows.headMaybe match
                                case Absent     => withTooltip
                                case Present(r) => withTooltip.withAttrs(ChartInteraction.buildInteractionAttrs(r, s, internalHoverRef))
                    Present(withInteraction)
                end if
        end match
    end buildSimpleAreaPath

    /** Lower a `Mark.Area` to closed `Svg.Path`(s).
      *
      * Three dispatch paths:
      *   1. `mark.stack.group` is `Present` and `mark.y` is `Present`: stacked area (groups sit atop each other).
      *   2. `mark.y` is `Present` and no stack: per-color-series area(s) fill between y values and the plot
      *      baseline; with a `color` encoding, one path per category (mirroring `lowerLine`); without, one path.
      *   3. `mark.y` is `Absent`: the `y0`/`y1` band form (closed ribbon between two edges).
      */
    private[kyo] def lowerArea[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Area[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color = ChartAxes.DefaultPalette(0),
        spec: Maybe[Chart[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        highlight: Maybe[Highlight[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        mark.y match
            case Present(yEnc) =>
                mark.stack.group match
                    case Present(_) =>
                        // Stacked area has no natural per-row identity; tag with the first row of rows
                        // as the series-representative for highlight (series-level granularity).
                        val stacked = lowerAreaStacked(rows, mark, yEnc, layout, xs, ys, spec, internalHoverRef)
                        val repRow  = rows.headMaybe
                        val tagged: Chunk[(A, Svg.SvgElement)] = repRow match
                            case Absent     => stacked.map(el => (rows(0), el)) // unreachable when stacked is non-empty
                            case Present(r) => stacked.map(el => (r, el))
                        ChartInteraction.withHighlight(tagged, highlight)
                    case Absent =>
                        // Non-stacked: dispatch on mark.color (mirrors lowerLine).
                        // Each series path is tagged with its series-representative row for highlight.
                        mark.color match
                            case Absent =>
                                // No color encoding: single series using the per-mark default color.
                                val pathMaybe = buildSimpleAreaPath(rows, mark, layout, xs, ys, defaultColor, spec, internalHoverRef)
                                val tagged: Chunk[(A, Svg.SvgElement)] = pathMaybe match
                                    case Absent => Chunk.empty
                                    case Present(path) =>
                                        rows.headMaybe match
                                            case Present(r) => Chunk((r, path))
                                            case Absent     => Chunk.empty
                                ChartInteraction.withHighlight(tagged, highlight)
                            case Present(colorEnc) =>
                                // Color encoding: split rows by category, one path per series.
                                // resolvePalette falls back to theme.palette / DefaultPalette when no colorScale is set,
                                // so a non-stacked area without a colorScale uses the theme palette.
                                val colorEncAny: Encoding[A, ?] = colorEnc
                                val cats: Chunk[(String, Any)] =
                                    ChartLegend.collectColorCategoriesWithRaw(rows, colorEncAny)
                                val palette: Chunk[Style.Color] = spec match
                                    case Present(s) => ChartLegend.resolvePalette(s, cats)
                                    case Absent     => ChartAxes.DefaultPalette
                                // Split rows by CatKey (tag + raw value), NOT by label toString: distinct color
                                // values with a colliding toString must stay in separate series. `cats` was deduped
                                // by CatKey, so cats[idx] aligns with the catKey filter at the same index.
                                val catKeys: Chunk[ChartFoundations.CatKey] =
                                    cats.map { case (_, raw) => ChartFoundations.categoryKey(colorEncAny.tag, raw) }
                                // Pre-build a single-pass groupBy so each catKey lookup is O(1) rather than O(N).
                                val rowsByKey = ChartFoundations.groupByKey(
                                    rows,
                                    r => ChartFoundations.categoryKey(colorEncAny.tag, colorEncAny.accessor(r))
                                )
                                val tagged: Chunk[(A, Svg.SvgElement)] = catKeys.zipWithIndex.flatMap: (catKey, idx) =>
                                    val seriesRows = rowsByKey.getOrElse(catKey, Chunk.empty)
                                    val fillColor  = palette(idx % palette.size)
                                    buildSimpleAreaPath(seriesRows, mark, layout, xs, ys, fillColor, spec, internalHoverRef) match
                                        case Absent => Chunk.empty
                                        case Present(path) =>
                                            seriesRows.headMaybe match
                                                case Present(r) => Chunk((r, path))
                                                case Absent     => Chunk.empty
                                    end match
                                ChartInteraction.withHighlight(tagged, highlight)
            case Absent =>
                // y0/y1 band form: render a closed ribbon between the two edges.
                // Tag the ribbon with the first row as the series-representative for highlight.
                val ribbon = buildBandRibbon(rows, mark, xs, ys, defaultColor)
                val repRow = rows.headMaybe
                val tagged: Chunk[(A, Svg.SvgElement)] = repRow match
                    case Absent     => Chunk.empty
                    case Present(r) => ribbon.map(el => (r, el))
                ChartInteraction.withHighlight(tagged, highlight)
        end match
    end lowerArea

    /** Build the closed ribbon path for the area band form (`y0` and `y1` supplied, `y` absent).
      *
      * The ribbon runs forward along the y1 edge (curve-interpolated), then backward along the
      * y0 edge, then closes. Invalid combinations (only `y0` or only `y1`, or neither) emit
      * `Chunk.empty` so that sibling marks still render.
      */
    private def buildBandRibbon[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Area[A, X, Y],
        xs: Scale,
        ys: Scale,
        fillColor: Style.Color
    )(using Frame): Chunk[Svg.SvgElement] =
        // Both y0 and y1 must be Present for a valid band.
        (mark.y0, mark.y1) match
            case (Present(ch0), Present(ch1)) =>
                // Collect (xPx, y0Px, y1Px) triples for each row, skipping non-finite values (NaN/Infinity produce corrupt SVG paths).
                val pts: Chunk[(Double, Double, Double)] = rows.flatMap: row =>
                    val xd  = mark.x.plottable.toDomain(mark.x.accessor(row))
                    val y0d = ch0.plottable.toDomain(ch0.accessor(row))
                    val y1d = ch1.plottable.toDomain(ch1.accessor(row))
                    (xd, y0d, y1d) match
                        case (Present(xdom), Present(y0dom), Present(y1dom)) =>
                            // Centre ribbon vertices on the band (xs.apply gives the band LEFT edge;
                            // bandwidth is 0 for continuous scales). Mirrors line, point, and simple-area
                            // which all add xs.bandwidth / 2.0 so that all marks align at the band center.
                            val xPx  = xs.apply(xdom) + xs.bandwidth / 2.0
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
                    // Backward along y0 edge, traversed in reverse order, curved per mark.curve too:
                    // both band edges must reflect the curve, not just the forward y1 edge.
                    // The connecting edge from the last y1 vertex down to the last y0 vertex is a single
                    // lineTo; the remaining reversed y0 vertices feed CurvePath.append so the y0 edge curves.
                    val y0ptsRev    = Chunk.from(pts.reverse.map(t => (t._1, t._2)))
                    val connectedPd = forwardPd.lineTo(y0ptsRev(0)._1, y0ptsRev(0)._2)
                    val ribbonPd    = CurvePath.append(connectedPd, y0ptsRev.drop(1), mark.curve).close
                    val baseOpacity = 0.7
                    val opacity = mark.opacity match
                        case Present(fn) =>
                            rows.headMaybe.map(r => math.max(0.0, math.min(1.0, fn(r)))).getOrElse(baseOpacity)
                        case Absent => baseOpacity
                    val basePath = Svg.path.d(ribbonPd).fill(Svg.Paint.Color(fillColor)).fillOpacity(opacity)
                    val withTooltip = mark.tooltip match
                        case Present(fn) =>
                            rows.headMaybe match
                                case Present(r) => basePath(Svg.title(fn(r)))
                                case Absent     => basePath
                        case Absent => basePath
                    Chunk(withTooltip)
                end if
            case _ =>
                // Only one edge supplied or neither: invalid combo, skip deterministically.
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
        yEnc: EncodingMaybe[A, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        spec: Maybe[Chart[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val groupFn  = mark.stack.group.getOrElse((_: A) => "")
        val baseline = layout.plotBaseline
        val plotTop  = layout.plotY

        // Collect all distinct x keys in encounter order (O(rows), Set-backed via distinctKeyed).
        val presentXKeys: Chunk[String] = rows.foldLeft(Chunk.empty[String]): (acc, row) =>
            mark.x.plottable.toDomain(mark.x.accessor(row)) match
                case Present(d) => acc.append(ChartScales.domainKey(d))
                case Absent     => acc
        val xKeys: Chunk[String] =
            ChartFoundations.distinctKeyed(presentXKeys, k => ChartFoundations.categoryKey(k)).map(_._2)

        // Collect all distinct group keys in encounter order
        val groupEnc: Encoding[A, Any] =
            Encoding(groupFn, Plottable.any, summon[ConcreteTag[Any]])
        val groupKeys: Chunk[String] = ChartLegend.collectColorCategories(rows, groupEnc)
        // Per-group palette, resolved the SAME way the stacked-bar path does so each band gets a
        // distinct fill color (honoring a custom theme.palette; DefaultPalette under the default theme).
        val groupCats: Chunk[(String, Any)] = ChartLegend.collectColorCategoriesWithRaw(rows, groupEnc)
        val groupPalette: Chunk[Style.Color] = spec match
            case Present(s) => ChartLegend.resolvePalette(s, groupCats)
            case Absent     => ChartLegend.resolvePaletteFromCfg(groupKeys)

        // groupCatKeys: one CatKey per category in encounter/ordinal order, derived from the raw values
        // in groupCats. Keys by CatKey (tag + raw value) so distinct group values with colliding toString
        // stay separate.
        val groupCatKeys: Chunk[ChartFoundations.CatKey] =
            groupCats.map { case (_, raw) => ChartFoundations.categoryKey(groupEnc.tag, raw) }

        // Precompute, in single passes: xKey -> first-seen x Domain (for band positioning) and
        // groupCatKey -> first-seen row (for per-group interaction attrs). These replace the per-(group, x)
        // and per-group linear `rows.find` scans inside loopGroups.
        val xDomainByKey: Map[String, Domain] = rows.foldLeft(Map.empty[String, Domain]): (m, row) =>
            mark.x.plottable.toDomain(mark.x.accessor(row)) match
                case Present(d) =>
                    val k = ChartScales.domainKey(d)
                    if m.contains(k) then m else m.updated(k, d)
                case Absent => m
        val rowByGroup: Map[ChartFoundations.CatKey, A] = rows.foldLeft(Map.empty[ChartFoundations.CatKey, A]): (m, row) =>
            val gck = ChartFoundations.categoryKey(groupEnc.tag, groupFn(row))
            if m.contains(gck) then m else m.updated(gck, row)

        // Build xKey -> groupCatKey -> yValue map.
        // Keys by CatKey instead of toString so distinct group values with the same toString stay separate.
        @scala.annotation.tailrec
        def buildMap(i: Int, m: Map[String, Map[ChartFoundations.CatKey, Double]]): Map[String, Map[ChartFoundations.CatKey, Double]] =
            if i >= rows.size then m
            else
                val row = rows(i)
                val xKeyOpt = mark.x.plottable.toDomain(mark.x.accessor(row)) match
                    case Present(d) => Present(ChartScales.domainKey(d))
                    case Absent     => Absent
                val yValOpt = yEnc.accessor(row) match
                    case Present(yv) => yEnc.plottable.toDomain(yv) match
                            case Present(Domain.Continuous(v)) => Present(v)
                            case _                             => Absent
                    case Absent => Absent
                val next = (xKeyOpt, yValOpt) match
                    case (Present(xk), Present(yv)) =>
                        val gck   = ChartFoundations.categoryKey(groupEnc.tag, groupFn(row))
                        val inner = m.getOrElse(xk, Map.empty)
                        m.updated(xk, inner.updated(gck, yv))
                    case _ => m
                buildMap(i + 1, next)
        val dataMap = buildMap(0, Map.empty)

        // Compute per-x totals for normalization
        val xTotals: Map[String, Double] = xKeys.foldLeft(Map.empty[String, Double]): (acc, xk) =>
            val groupMap = dataMap.getOrElse(xk, Map.empty)
            acc.updated(xk, groupCatKeys.foldLeft(0.0)((s, gck) => s + groupMap.getOrElse(gck, 0.0)))

        // For each group, compute the pixel x values and the y0/y1 pixel pairs at each x slot.
        // `accumulatedFractions` tracks, for each xKey, how much of the stack has been consumed so far.
        @scala.annotation.tailrec
        def loopGroups(
            gi: Int,
            accByX: Map[String, Double],
            acc: Chunk[Svg.SvgElement]
        ): Chunk[Svg.SvgElement] =
            if gi >= groupCatKeys.size then acc
            else
                val gck = groupCatKeys(gi)

                // Build (px, py0, py1) for each x slot in order
                val bands: Chunk[(Double, Double, Double)] = xKeys.flatMap: xk =>
                    val groupMap = dataMap.getOrElse(xk, Map.empty)
                    val rawY     = groupMap.getOrElse(gck, 0.0)
                    val accY     = accByX.getOrElse(xk, 0.0)
                    val total    = xTotals.getOrElse(xk, 0.0)
                    // Centre area vertices on the band (xs.apply gives the band LEFT edge; bandwidth is 0 for
                    // continuous scales). Tick labels sit at band centres, so left-edge vertices leave the last
                    // band slot empty (a wedge of unfilled plot to the right of the final vertex).
                    val px =
                        (Maybe.fromOption(xDomainByKey.get(xk)) match
                            case Present(d) => xs.apply(d)
                            case Absent     => xs.apply(Domain.Category(xk))
                        ) + xs.bandwidth / 2.0
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

                // Skip groups that contribute nothing at every x (no zero-height paths)
                val hasContribution = xKeys.exists: xk =>
                    dataMap.getOrElse(xk, Map.empty).getOrElse(gck, 0.0) > 0.0

                val newAcc =
                    if !hasContribution then acc
                    else
                        // Top edge forward (y1 values), then bottom edge backward (y0 values), then close
                        val topEdge: Svg.PathData = bands.tail.foldLeft(
                            Svg.PathData.from(bands(0)._1, bands(0)._3)
                        ): (pd, b) =>
                            pd.lineTo(b._1, b._3)
                        val fullPath = bands.reverse.foldLeft(topEdge): (pd, b) =>
                            pd.lineTo(b._1, b._2)
                        // Fill each band in its group's palette color (mirrors the non-stacked area
                        // convention: a color fill at 0.7 opacity), so stacked bands are not colorless.
                        val groupColor = if gi < groupPalette.size then groupPalette(gi)
                        else ChartAxes.DefaultPalette(gi % ChartAxes.DefaultPalette.size)
                        val basePath = Svg.path.d(fullPath.close).fill(Svg.Paint.Color(groupColor)).fillOpacity(0.7)
                        // Attach interaction attrs to each group path.
                        // The representative row is the first row in this group (precomputed in rowByGroup).
                        val withInteraction = spec match
                            case Absent => basePath
                            case Present(s) =>
                                Maybe.fromOption(rowByGroup.get(gck)) match
                                    case Absent     => basePath
                                    case Present(r) => basePath.withAttrs(ChartInteraction.buildInteractionAttrs(r, s, internalHoverRef))
                        acc.append(withInteraction)

                // Update accumulated fractions for the next group, but ONLY when the group
                // was rendered (hasContribution). A skipped group must not add to accByX because
                // that would corrupt later groups' baselines: they would appear to stack on top
                // of a value that is not actually visible in the chart.
                val newAccByX =
                    if !hasContribution then accByX
                    else
                        xKeys.foldLeft(accByX): (m, xk) =>
                            val groupMap = dataMap.getOrElse(xk, Map.empty)
                            m.updated(xk, m.getOrElse(xk, 0.0) + groupMap.getOrElse(gck, 0.0))

                loopGroups(gi + 1, newAccByX, newAcc)
        loopGroups(0, Map.empty, Chunk.empty)
    end lowerAreaStacked

    /** Lower a `Mark.Point` to glyphs (circle, square, triangle, diamond, or cross).
      *
      * Color encoding: when `mark.color` is `Present`, rows are split by
      * `ChartFoundations.categoryKey` and each category gets a distinct palette color
      * via the same `resolvePalette` the legend uses. Without a `color` encoding, all
      * points use `defaultColor`.
      *
      * Size encoding: `sizePx` overrides with raw pixel radius; `size` uses a
      * sqrt-area scale built from the magnitude extent over all rows. Absent: `DefaultRadius`.
      *
      * Symbol encoding: dispatches on `Symbol` to circle (`Svg.circle`) or a
      * path glyph helper (square, triangle, diamond, cross).
      *
      * Encodings: `opacity` sets `fillOpacity`; `label` emits an `Svg.text`
      * above the glyph; `tooltip` attaches a `<title>` child.
      */
    private[kyo] def lowerPoint[A, X, Y](
        rows: Chunk[A],
        mark: Mark.Point[A, X, Y],
        layout: Layout,
        xs: Scale,
        ys: Scale,
        defaultColor: Style.Color,
        spec: Maybe[Chart[A]] = Absent,
        internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
        theme: Theme = Theme.default,
        highlight: Maybe[Highlight[A]] = Absent
    )(using Frame): Chunk[Svg.SvgElement] =
        val separator = ChartAxes.pointSeparatorColor(theme)

        // Build the sqrt-area size scale once from the full row set.
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

        // Build the per-row color lookup CatKey -> Style.Color when a `color` encoding is present. Matching
        // `mark.color` here keeps the encoding's tag a fresh existential so `categoryKey` keys raw values under
        // the same stable, cross-platform type identity the per-row lookup uses below, with no widening cast.
        val colorByKey: Map[ChartFoundations.CatKey, Style.Color] =
            mark.color match
                case Absent => Map.empty
                case Present(colorEnc) =>
                    val cats = ChartLegend.collectColorCategoriesWithRaw(rows, colorEnc)
                    val palette = spec match
                        case Present(s) => ChartLegend.resolvePalette(s, cats)
                        case Absent     => cats.zipWithIndex.map((_, i) => ChartAxes.DefaultPalette(i % ChartAxes.DefaultPalette.size))
                    cats.zipWithIndex.foldLeft(Map.empty[ChartFoundations.CatKey, Style.Color]): (m, pair) =>
                        val ((label, raw), idx) = pair
                        val key                 = ChartFoundations.categoryKey(colorEnc.tag, raw)
                        val color =
                            if idx < palette.size then palette(idx) else ChartAxes.DefaultPalette(idx % ChartAxes.DefaultPalette.size)
                        m.updated(key, color)

        // Glyph elements are tagged with their source row so the built-in highlight can re-style the
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
                                // Centre on the band (xs.apply is the band LEFT edge; bandwidth is 0 for
                                // continuous scales) so glyphs align with the centred tick labels.
                                val cx = xs.apply(x) + xs.bandwidth / 2.0
                                val cy = ys.apply(y)
                                // Skip non-finite pixel coordinates: they produce invisible or corrupt SVG elements.
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
                                        case Present(colorEnc) =>
                                            val key = ChartFoundations.categoryKey(colorEnc.tag, colorEnc.accessor(row))
                                            colorByKey.getOrElse(key, defaultColor)

                                    val iAttrs = spec.map(s => ChartInteraction.buildInteractionAttrs(row, s, internalHoverRef)).getOrElse(
                                        UI.Ast.Attrs()
                                    )

                                    // Resolve symbol and build the glyph shape.
                                    val sym = mark.symbol.map(_(row)).getOrElse(Symbol.circle)

                                    // Opacity encoding.
                                    val opacity = mark.opacity match
                                        case Present(fn) => Present(math.max(0.0, math.min(1.0, fn(row))))
                                        case Absent      => Absent

                                    def pathGlyph(pd: Svg.PathData): Chunk[Svg.SvgElement] =
                                        val base = Svg.path.d(pd)
                                            .fill(Svg.Paint.Color(fillColor))
                                            .stroke(Svg.Paint.Color(separator))
                                            .strokeWidth(PointStrokeWidth)
                                            .withAttrs(iAttrs)
                                        val withOp = opacity.fold(base)(op => base.fillOpacity(op))
                                        val withTip = mark.tooltip match
                                            case Present(fn) => withOp(Svg.title(fn(row)))
                                            case Absent      => withOp
                                        Chunk(withTip)
                                    end pathGlyph

                                    // Build glyph elements (circle or path-based).
                                    val glyphElems: Chunk[Svg.SvgElement] = sym match
                                        case Symbol.circle =>
                                            val base = Svg.circle
                                                .cx(cx).cy(cy).r(r)
                                                .fill(Svg.Paint.Color(fillColor))
                                                .stroke(Svg.Paint.Color(separator))
                                                .strokeWidth(PointStrokeWidth)
                                                .withAttrs(iAttrs)
                                            val withOp = opacity.fold(base)(op => base.fillOpacity(op))
                                            val withTip = mark.tooltip match
                                                case Present(fn) => withOp(Svg.title(fn(row)))
                                                case Absent      => withOp
                                            Chunk(withTip)
                                        case Symbol.cross =>
                                            // Cross: two perpendicular Svg.Line elements.
                                            val h = Svg.line.x1(cx - r).y1(cy).x2(cx + r).y2(cy)
                                                .stroke(Svg.Paint.Color(fillColor)).strokeWidth(PointStrokeWidth + 0.5)
                                                .withAttrs(iAttrs)
                                            val v = Svg.line.x1(cx).y1(cy - r).x2(cx).y2(cy + r)
                                                .stroke(Svg.Paint.Color(fillColor)).strokeWidth(PointStrokeWidth + 0.5)
                                            Chunk(h, v)
                                        case Symbol.square   => pathGlyph(squarePath(cx, cy, r))
                                        case Symbol.triangle => pathGlyph(trianglePath(cx, cy, r))
                                        case Symbol.diamond  => pathGlyph(diamondPath(cx, cy, r))

                                    // Label encoding: emit Svg.text above the glyph.
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
        ChartInteraction.withHighlight(glyphs, highlight) ++ labels
    end lowerPoint

    // ---- Symbol glyph path helpers ----

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
    private[kyo] def lowerRule[A](
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
      * No `url(#id)` references are emitted (plain lines only).
      */
    // Project a `RuleValue.Const`'s value into its domain. Taking the case directly opens the existential
    // once into a single `C`, so `plottable.toDomain(value)` type-checks (both are `C`) with no cast.
    private def constDomain[C](c: RuleValue.Const[C]): Maybe[Domain] =
        c.plottable.toDomain(c.value)

    // Build a `Reactive[Svg.Line]` from a `RuleValue.Reactive`. Taking the case directly binds `signal` and
    // `plottable` to one `C`, so `signal.render` yields `v: C` and `plottable.toDomain(v)` type-checks with
    // no cast. `lineOf` builds the line from a present domain; `Absent` emits a zero-length invisible line
    // (not emitting nothing avoids a type mismatch; the renderer skips zero-length lines).
    private def reactiveLine[C](r: RuleValue.Reactive[C], lineOf: Domain => Svg.Line)(using Frame): UI.Ast.Reactive[Svg.Line] =
        r.signal.render: v =>
            r.plottable.toDomain(v) match
                case Present(d) => lineOf(d)
                case Absent     => Svg.line.x1(0.0).y1(0.0).x2(0.0).y2(0.0)

    private[kyo] def lowerRuleChildren[A](
        mark: Mark.Rule[A],
        layout: Layout,
        xs: Scale,
        ys: Scale
    )(using Frame): Chunk[UI] =
        // For a category domain, place the vertical rule at the band center so the line bisects the bar/data
        // point rather than aligning to the band left wall.
        def xLine(d: Domain): Svg.Line =
            val leftEdge = xs.apply(d)
            val px = d match
                case _: Domain.Category => leftEdge + xs.bandwidth / 2.0
                case _                  => leftEdge
            Svg.line.x1(px).y1(layout.plotY).x2(px).y2(layout.plotBaseline)
        end xLine
        def yLine(d: Domain): Svg.Line =
            val py = ys.apply(d)
            Svg.line.x1(layout.plotX).y1(py).x2(layout.plotX + layout.plotW).y2(py)
        val xChildren: Chunk[UI] = mark.x match
            case Present(c: RuleValue.Const[?]) =>
                constDomain(c) match
                    case Present(d) => Chunk(xLine(d))
                    case Absent     => Chunk.empty
            case Present(r: RuleValue.Reactive[?]) =>
                Chunk(Svg.g(reactiveLine(r, xLine)))
            case Absent => Chunk.empty
        val yChildren: Chunk[UI] = mark.y match
            case Present(c: RuleValue.Const[?]) =>
                constDomain(c) match
                    case Present(d) => Chunk(yLine(d))
                    case Absent     => Chunk.empty
            case Present(r: RuleValue.Reactive[?]) =>
                Chunk(Svg.g(reactiveLine(r, yLine)))
            case Absent => Chunk.empty
        xChildren ++ yChildren
    end lowerRuleChildren

end ChartMarks
