package kyo.internal

import kyo.*
import kyo.Chart.*
import kyo.Chart.Encoding
import kyo.UI.*
import kyo.UI.Ast.*

/** Legend swatch layout and color-scale resolution for chart marks.
  *
  * Provides the full legend-building pipeline: `buildLegend` (top-level dispatcher), `buildSequentialLegend`
  * (continuous gradient swatch), `buildSizeLegend` (representative size-bubble items), `buildLegendItems`
  * (categorical swatch+label rows), palette resolution via `resolvePalette` and `resolvePaletteFromCfg`,
  * and the color-category collection helpers (`collectColorCategoriesWithRaw`, `collectColorCategories`)
  * that enumerate enum values in declaration order.
  */
private[kyo] object ChartLegend:

    import ChartLayout.Layout
    import ChartLayout.LegendReservedH

    /** Swatch size (pixels) for legend color boxes. */
    private val SwatchSize = 12.0

    /** Vertical gap between legend rows. */
    private val LegendRowH = 18.0

    /** Horizontal gap between swatch and label text in the legend. */
    private val SwatchLabelGap = 6.0

    /** Build the legend `Svg.g` elements for any `color` encoding present in the marks.
      *
      * Categories are collected in enum-ordinal order when the color values are enums (real `Plottable`
      * ordering, not encounter order). The palette comes from `legendCfg.colorScale`
      * when set; otherwise from `theme.palette` or the `DefaultPalette`.
      *
      * When no mark has a `color` encoding but a Bar or Area mark carries a `stack` grouping, the legend is
      * derived from the STACK groups instead: one swatch+label per stack category, in the same colors the
      * stacked segments use. This is the same derivation as for a `color` encoding, applied to the grouping
      * accessor that colors the stacked segments.
      */
    private[kyo] def buildLegend[A](
        layout: Layout,
        spec: Chart[A],
        rows: Chunk[A],
        gradPrefix: String
    )(using Frame): Chunk[Svg.SvgElement] =
        if spec.legendCfg.isHidden then Chunk.empty
        else
            // Prefer a color encoding; fall back to the stack grouping that colors the stacked segments.
            val colorItems: Chunk[Svg.SvgElement] = legendEncoding(spec.marks) match
                case Absent            => Chunk.empty
                case Present(colorEnc) =>
                    // categories: Chunk[(label, rawValue)] sorted by enum ordinal when applicable
                    val categories = collectColorCategoriesWithRaw(rows, colorEnc)
                    if categories.isEmpty then Chunk.empty
                    else
                        spec.legendCfg.colorScale match
                            case Present(LegendConfig.ColorScale.Sequential(lo, hi, domOv)) =>
                                // Sequential legend: a continuous gradient swatch under a doc-unique def id, plus
                                // the numeric value extent (min/mid/max) so the gradient is quantitatively readable.
                                buildSequentialLegend(layout, spec, lo, hi, categories, domOv, gradPrefix)
                            case Absent | Present(_: LegendConfig.ColorScale.Categorical) =>
                                val palette = resolvePalette(spec, categories)
                                buildLegendItems(
                                    layout,
                                    categories,
                                    palette,
                                    spec.legendCfg,
                                    spec.marks,
                                    ChartAxes.axisChromeColor(spec.theme),
                                    spec.theme
                                )
                    end if

            // Size legend: emitted when any Point mark has a size encoding.
            val sizeItems: Chunk[Svg.SvgElement] = spec.marks.flatMap:
                case m: Mark.Point[A, ?, ?] =>
                    m.size match
                        case Present(fn) =>
                            buildSizeLegend(layout, rows, m, ChartAxes.axisChromeColor(spec.theme), colorItems.size, spec.theme)
                        case Absent => Chunk.empty
                case (_: Mark.Bar[?, ?, ?] | _: Mark.Line[?, ?, ?] | _: Mark.Area[?, ?, ?] | _: Mark.Rule[?] |
                    _: Mark.Text[?, ?, ?] | _: Mark.ErrorBar[?, ?, ?]) =>
                    Chunk.empty

            colorItems ++ sizeItems
        end if
    end buildLegend

    /** Build a continuous gradient swatch for a sequential color scale.
      *
      * Emits an `Svg.defs` carrying one `linearGradient` (low at offset 0, high at offset 1) under a
      * document-unique def id, plus a swatch `Svg.rect` filled with that gradient via `url(#id)`. The id comes
      * from `gradPrefix` (allocated once per chart instance), so two charts on one page never alias the same
      * gradient even when their specs are structurally identical: the def id and the swatch's `url(#id)` always
      * match within one chart, and differ across charts.
      */
    private def buildSequentialLegend[A](
        layout: Layout,
        spec: Chart[A],
        lo: Style.Color,
        hi: Style.Color,
        categories: Chunk[(String, Any)],
        domainOv: Maybe[(Double, Double)],
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
            case LegendPosition.Top    => (layout.plotX, 20.0 + (LegendReservedH - SwatchSize) / 2.0)
            case LegendPosition.Bottom => (layout.plotX, layout.plotBaseline + (LegendReservedH - SwatchSize) / 2.0)
            case LegendPosition.Left   => (60.0 / 2.0 - SwatchSize, layout.plotY)
            case LegendPosition.Right  => (layout.plotX + layout.plotW + 8.0, layout.plotY)
        val swatchW = SwatchSize * 4.0
        val swatch: Svg.SvgElement =
            Svg.rect
                .x(legendX)
                .y(legendY)
                .width(swatchW)
                .height(SwatchSize)
                .fill(gradient.paint)
        // Numeric value extent at the two ends, so the gradient reads quantitatively. The labels sit INLINE to
        // the left (min) and right (max) of the swatch, vertically centred on it, so they stay inside the thin
        // reserved band (placing them below the swatch would spill into the plot area). They use the axis-tick
        // chrome color and the same NumberFormat the tick/size legends use.
        val (domLo, domHi) = domainExtentOf(categories, domainOv)
        val labelColor     = ChartAxes.axisChromeColor(spec.theme)
        val labelY         = legendY + SwatchSize / 2.0
        def label(value: Double, lx: Double, anchor: Svg.TextAnchor): Svg.SvgElement =
            withFont(
                spec.theme,
                Svg.text
                    .x(lx)
                    .y(labelY)
                    .textAnchor(anchor)
                    .dominantBaseline(Svg.DominantBaseline.Middle)
                    .fill(Svg.Paint.Color(labelColor))
            ).apply(NumberFormat.double(value))
        val minLabel = label(domLo, legendX - 6.0, Svg.TextAnchor.End)
        val maxLabel = label(domHi, legendX + swatchW + 6.0, Svg.TextAnchor.Start)
        Chunk(Svg.defs(gradient), swatch, minLabel, maxLabel)
    end buildSequentialLegend

    /** Build representative size-bubble legend items for a point mark with a size encoding.
      *
      * Emits two representative circles (min and max magnitude) with their magnitude labels,
      * placed in the legend region to the right of any color-legend items.
      */
    private def buildSizeLegend[A, X, Y](
        layout: Layout,
        rows: Chunk[A],
        mark: Mark.Point[A, X, Y],
        labelColor: Style.Color,
        colorItemCount: Int,
        theme: Theme
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
                    // Representative legend glyphs are sized to FIT the top legend strip (a data point's
                    // DefaultRMax of 20px would overflow the strip and dip into the plot). Cap the max legend
                    // radius at half the reserved strip height so both sample bubbles sit entirely in the strip,
                    // above the plot data area, never over the plotted points.
                    val legendRMax = (LegendReservedH / 2.0) - 1.0
                    val legendRMin = SizeScale.DefaultRMin
                    val scale      = SizeScale(magMin, magMax, legendRMin, legendRMax)
                    val rMin       = scale.radius(magMin)
                    val rMax       = scale.radius(magMax)
                    // Place size bubbles in the TOP legend strip, after any color swatches. legendY is the strip
                    // centre; with rMax <= strip/2 the bubbles stay within [plotY - LegendReservedH, plotY).
                    val startX  = layout.plotX + colorItemCount.toDouble * 80.0
                    val legendY = 20.0 + LegendReservedH / 2.0
                    val minCirc = Svg.circle
                        .cx(startX + rMin)
                        .cy(legendY)
                        .r(rMin)
                        .fill(Svg.Paint.Color(ChartAxes.DefaultPalette(0)))
                        .fillOpacity(0.5)
                    val minLabel = withFont(
                        theme,
                        Svg.text
                            .x(startX + rMin * 2.0 + 4.0)
                            .y(legendY)
                            .dominantBaseline(Svg.DominantBaseline.Middle)
                            .fill(Svg.Paint.Color(labelColor))
                    ).apply(NumberFormat.double(magMin))
                    val maxCirc = Svg.circle
                        .cx(startX + rMin * 2.0 + 50.0 + rMax)
                        .cy(legendY)
                        .r(rMax)
                        .fill(Svg.Paint.Color(ChartAxes.DefaultPalette(0)))
                        .fillOpacity(0.5)
                    val maxLabel = withFont(
                        theme,
                        Svg.text
                            .x(startX + rMin * 2.0 + 50.0 + rMax * 2.0 + 4.0)
                            .y(legendY)
                            .dominantBaseline(Svg.DominantBaseline.Middle)
                            .fill(Svg.Paint.Color(labelColor))
                    ).apply(NumberFormat.double(magMax))
                    Chunk(minCirc, minLabel, maxCirc, maxLabel)
                end if
    end buildSizeLegend

    /** The encoding that drives the legend: the first `color` encoding, or, when none is present, the first
      * Bar/Area `stack` grouping (which colors the stacked segments).
      */
    private[kyo] def legendEncoding[A](marks: Chunk[Mark[A]]): Maybe[Encoding[A, ?]] =
        findColorEncoding(marks).orElse(findStackGroup(marks))

    /** Find the first `color` encoding across all marks. */
    private def findColorEncoding[A](marks: Chunk[Mark[A]]): Maybe[Encoding[A, ?]] =
        @scala.annotation.tailrec
        def loop(i: Int): Maybe[Encoding[A, ?]] =
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
    end findColorEncoding

    /** Find the first Bar/Area `stack` grouping accessor, wrapped as a string-keyed `Encoding`.
      *
      * The wrapped encoding mirrors how `lowerBarStacked` colors stacked segments: the grouping accessor
      * keyed as a string category. Deriving the legend from this encoding produces one swatch per stack
      * category in the same colors the segments use.
      */
    private def findStackGroup[A](marks: Chunk[Mark[A]]): Maybe[Encoding[A, ?]] =
        @scala.annotation.tailrec
        def loop(i: Int): Maybe[Encoding[A, ?]] =
            if i >= marks.size then Absent
            else
                val groupMaybe = marks(i) match
                    case m: Mark.Bar[A, ?, ?]  => m.stack.group
                    case m: Mark.Area[A, ?, ?] => m.stack.group
                    case _                     => Absent
                groupMaybe match
                    case Present(g) =>
                        Present(Encoding[A, Any](g, Plottable.any, summon[ConcreteTag[Any]]))
                    case Absent => loop(i + 1)
                end match
        loop(0)
    end findStackGroup

    /** Collect distinct color category labels in enum-ordinal order when possible, encounter order otherwise.
      *
      * For enum values (detected via `scala.reflect.Enum`), the first encountered value's ordinal is used as
      * a sort key so that the legend lists cases in enum declaration order rather than encounter order. This
      * ensures ordering via the real color accessor's `Plottable`, not the string stand-in.
      *
      * Returns `Chunk[(label, rawValue)]` where `rawValue` is the first encountered raw `color` encoding value
      * for that label. The raw value is passed to `colorScale` so typed enum functions can be applied
      * directly (no label-to-K roundtrip).
      */
    private[kyo] def collectColorCategoriesWithRaw[A](rows: Chunk[A], colorEnc: Encoding[A, ?]): Chunk[(String, Any)] =
        // distinctKeyed dedups by CatKey in O(n) (Set-backed), returning first-seen (CatKey, row) in
        // encounter order; (label, rawValue, ordinal) is derived from each representative row.
        val distinct = ChartFoundations.distinctKeyed(
            rows,
            r => ChartFoundations.categoryKey(colorEnc.tag, colorEnc.accessor(r))
        )
        val triples = distinct.zipWithIndex.map: (pair, encounterIdx) =>
            val raw   = colorEnc.accessor(pair._2)
            val label = raw.toString
            val ordinal = raw match
                case e: scala.reflect.Enum => e.ordinal
                case _                     => encounterIdx // stable encounter-order index
            (label, raw, ordinal)
        // Sort by ordinal so enum cases appear in declaration order rather than encounter order.
        triples.sortBy(_._3).foldLeft(Chunk.empty[(String, Any)])((acc, t) => acc.append((t._1, t._2)))
    end collectColorCategoriesWithRaw

    /** Collect category labels (without raw values) for use in stacked-bar lowering. */
    private[kyo] def collectColorCategories[A](rows: Chunk[A], colorEnc: Encoding[A, ?]): Chunk[String] =
        collectColorCategoriesWithRaw(rows, colorEnc).map(_._1)

    /** Resolve the palette: explicit `colorScale` first (applied to raw values), then `theme.palette`,
      * then `DefaultPalette`.
      *
      * A `Categorical` scale applies its total function to each category's RAW `color` encoding value (e.g. the
      * actual enum case), not the label string, so typed pairs/functions work without a label-to-K roundtrip.
      * A `Sequential` scale derives the numeric domain extent from the categories (or its `domain` override)
      * and interpolates each raw value's color between `low` and `high`.
      */
    private[kyo] def resolvePalette[A](spec: Chart[A], categories: Chunk[(String, Any)]): Chunk[Style.Color] =
        spec.legendCfg.colorScale match
            case Present(LegendConfig.ColorScale.Categorical(fn)) =>
                categories.map { case (_, raw) => fn(raw) }
            case Present(LegendConfig.ColorScale.Sequential(lo, hi, domOv)) =>
                val (domLo, domHi) = domainExtentOf(categories, domOv)
                categories.map { case (_, raw) => sequentialColor(raw, lo, hi, domLo, domHi) }
            case Absent =>
                spec.theme.palette match
                    case Present(p) => categories.zipWithIndex.map: (_, i) =>
                            p(i % p.size)
                    case Absent => categories.zipWithIndex.map: (_, i) =>
                            ChartAxes.DefaultPalette(i % ChartAxes.DefaultPalette.size)
    end resolvePalette

    /** Convenience: resolve a palette from DefaultPalette for the stacked-bar Absent-spec fallback. */
    private[kyo] def resolvePaletteFromCfg(categories: Chunk[String]): Chunk[Style.Color] =
        categories.zipWithIndex.map: (_, i) =>
            ChartAxes.DefaultPalette(i % ChartAxes.DefaultPalette.size)

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

    /** Linear per-component RGB interpolation between two colors at parameter `t` in `[0, 1]`.
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

    /** True when the legend-driving mark (the one that carries the `color` encoding) is a line or area mark.
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
    private[kyo] def buildLegendItems[A](
        layout: Layout,
        categories: Chunk[(String, Any)],
        palette: Chunk[Style.Color],
        cfg: LegendConfig,
        marks: Chunk[Mark[A]],
        labelColor: Style.Color,
        theme: Theme
    )(using Frame): Chunk[Svg.SvgElement] =
        // Origin and flow direction per legend position. `vertical` stacks items down a column (Left/Right);
        // otherwise items flow horizontally across the reserved band (Top/Bottom).
        val (legendX, legendY, vertical) = cfg.position.getOrElse(LegendPosition.Top) match
            case LegendPosition.Top    => (layout.plotX, 20.0 + (LegendReservedH - SwatchSize) / 2.0, false)
            case LegendPosition.Bottom => (layout.plotX, layout.plotBaseline + (LegendReservedH - SwatchSize) / 2.0, false)
            case LegendPosition.Left   => (60.0 / 2.0 - SwatchSize, layout.plotY, true)
            case LegendPosition.Right  => (layout.plotX + layout.plotW + 8.0, layout.plotY, true)

        val useLineSwatch = legendUsesLineSwatch(marks)
        val itemGap       = 16.0

        // Click action toggling one series index in the user's hiddenSeries ref.
        def toggleAction(catIndex: Int): Maybe[Any < Async] =
            if cfg.isInteractive then
                cfg.hiddenSeries.map(ref =>
                    ref.updateAndGet(s => if s.contains(catIndex) then s - catIndex else s + catIndex)
                )
            else Absent

        @scala.annotation.tailrec
        def loop(i: Int, curX: Double, curY: Double, acc: Chunk[Svg.SvgElement]): Chunk[Svg.SvgElement] =
            if i >= categories.size then acc
            else
                val (cat, _) = categories(i)
                val color    = if i < palette.size then palette(i) else ChartAxes.DefaultPalette(i % ChartAxes.DefaultPalette.size)
                val clickAttrs = toggleAction(i) match
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
                    withFont(
                        theme,
                        Svg.text
                            .x(curX + SwatchSize + SwatchLabelGap)
                            .y(curY + SwatchSize / 2.0)
                            .dominantBaseline(Svg.DominantBaseline.Middle)
                            .fill(Svg.Paint.Color(labelColor))
                            .withAttrs(clickAttrs)
                    ).apply(cat)
                val approxLabelW = cat.length.toDouble * 7.0
                val (nextX, nextY) =
                    if vertical then (curX, curY + LegendRowH)
                    else (curX + SwatchSize + SwatchLabelGap + approxLabelW + itemGap, curY)
                loop(i + 1, nextX, nextY, acc.append(swatch).append(label))
        loop(0, legendX, legendY, Chunk.empty)
    end buildLegendItems

    /** Apply theme font family and font size to an Svg.Text element when the theme sets them. */
    private def withFont(theme: Theme, t: Svg.Text): Svg.Text =
        val t1 = theme.fontFamily.fold(t)(f => t.fontFamily(f))
        theme.fontSize.fold(t1)(px => t1.fontSize(Svg.SvgLength.Px(px)))

end ChartLegend
