package kyo

import scala.language.implicitConversions

/** An authoring spec for a chart that lowers to an `Svg.Root`.
  *
  * A `Chart` is not a [[kyo.UI]] node: it is a pure authoring value that describes a data visualization and lowers to an `Svg.Root` via
  * `.lower`. Because the lowered result is `HtmlContent`, a lowered chart drops into any HTML container exactly where an SVG root is
  * expected.
  *
  * Build a chart with `Chart(data)(marks*)`: the first application fixes the row type `A`, the second supplies the marks and returns a
  * [[kyo.Chart]]. Marks are produced by the factories `Chart.bar`, `Chart.line`, `Chart.area`, `Chart.point`, `Chart.text`,
  * `Chart.errorBar`, and `Chart.rule`. The resulting chart is refined with the configuration methods (`.xAxis`, `.legend`, `.theme`, ...)
  * and lowered with `.lower`.
  *
  * @see
  *   [[kyo.Chart.apply]] for the chart entry point
  */
final case class Chart[A] private[kyo] (
    data: Chart.DataSource[A],
    marks: Chunk[Chart.Mark[A]],
    chartSize: (Int, Int),
    xAxisCfg: Chart.AxisConfig,
    yAxisCfg: Chart.AxisConfig,
    yAxisRightCfg: Maybe[Chart.AxisConfig],
    legendCfg: Chart.LegendConfig,
    theme: Chart.Theme,
    xScaleOverride: Maybe[Chart.ScaleOverride],
    yScaleOverride: Maybe[Chart.ScaleOverride],
    yScaleRightOverride: Maybe[Chart.ScaleOverride],
    animateCfg: Chart.AnimateConfig,
    key: Maybe[A => String],
    onHover: Maybe[Signal.SignalRef[Maybe[A]]],
    onSelect: Maybe[Signal.SignalRef[Maybe[A]]],
    tooltip: Maybe[A => String],
    interactionCfg: Chart.InteractionConfig = Chart.InteractionConfig.default,
    a11y: Chart.A11y = Chart.A11y.default,
    isResponsive: Boolean = false,
    aspectRatio: Maybe[Double] = Absent,
    marginsCfg: Chart.Margins = Chart.Margins.default
):

    import Chart.*

    /** Configures the x-axis using a builder lambda. */
    def xAxis(f: AxisConfig => AxisConfig): Chart[A] =
        copy(xAxisCfg = f(xAxisCfg))

    /** Configures the left y-axis using a builder lambda. */
    def yAxis(f: AxisConfig => AxisConfig): Chart[A] =
        copy(yAxisCfg = f(yAxisCfg))

    /** Configures the right y-axis using a builder lambda. Creates it if absent. */
    def yAxisRight(f: AxisConfig => AxisConfig): Chart[A] =
        val base = yAxisRightCfg.getOrElse(AxisConfig.default)
        copy(yAxisRightCfg = Present(f(base)))

    /** Configures the legend using a builder lambda. */
    def legend(f: LegendConfig => LegendConfig): Chart[A] =
        copy(legendCfg = f(legendCfg))

    /** Configures the theme using a builder lambda. */
    def theme(f: Theme => Theme): Chart[A] =
        copy(theme = f(theme))

    /** Sets the chart width and height in user units. Disables responsive sizing (last-set wins). */
    def size(w: Int, h: Int): Chart[A] =
        copy(chartSize = (w, h), isResponsive = false)

    /** Overrides the x-axis scale using a builder lambda. */
    def xScale(f: ScaleOverride => ScaleOverride): Chart[A] =
        copy(xScaleOverride = Present(f(ScaleOverride.default)))

    /** Overrides the y-axis scale using a builder lambda. */
    def yScale(f: ScaleOverride => ScaleOverride): Chart[A] =
        copy(yScaleOverride = Present(f(ScaleOverride.default)))

    /** Overrides the right y-axis scale using a builder lambda.
      *
      * Applies only to marks bound to the right axis (`axis = Axis.Right`). The left axis is
      * unaffected; configure it with `.yScale(...)`. Use this when the two axes carry
      * unrelated domains that need independent scale kinds, ranges, or fitting knobs (the
      * canonical dual-axis case, e.g. an absolute count on the left and a log-scaled ratio on
      * the right).
      *
      * The builder receives a fresh `ScaleOverride.default` and returns the configured
      * override: `.yScaleRight(_.log)`, `.yScaleRight(_.linear(0, 1).withClamp(true))`. An
      * unset right override (the default) leaves the right scale inferred from the right-bound
      * marks' data extent.
      *
      * A right axis only exists when the chart has right-bound marks or `.yAxisRight(f)`
      * was called; on a single-axis chart this override is a no-op.
      */
    def yScaleRight(f: ScaleOverride => ScaleOverride): Chart[A] =
        copy(yScaleRightOverride = Present(f(ScaleOverride.default)))

    /** Configures animation using a builder lambda. */
    def animate(f: AnimateConfig => AnimateConfig): Chart[A] =
        copy(animateCfg = f(animateCfg))

    /** Sets the key extractor used for keyed transitions. */
    def key(f: A => String): Chart[A] =
        copy(key = Present(f))

    /** Publishes the hovered datum to `ref` on pointer enter/leave. */
    def onHover(ref: Signal.SignalRef[Maybe[A]]): Chart[A] =
        copy(onHover = Present(ref))

    /** Publishes the selected datum to `ref` on click. */
    def onSelect(ref: Signal.SignalRef[Maybe[A]]): Chart[A] =
        copy(onSelect = Present(ref))

    /** Attaches a default tooltip using `f` to format the hovered datum. */
    def tooltip(f: A => String): Chart[A] =
        copy(tooltip = Present(f))

    /** Configures built-in visual highlight behavior using a builder lambda.
      *
      * Example: `.interaction(_.highlightSelect)` enables the default opacity boost on the selected mark.
      * If no `onHover`/`onSelect` ref is configured, all highlight settings are no-ops.
      */
    def interaction(f: InteractionConfig => InteractionConfig): Chart[A] =
        copy(interactionCfg = f(interactionCfg))

    /** Sets the accessible title (a `<title>` child); also implies `role="img"` on the root. */
    def title(s: String): Chart[A] =
        copy(a11y = a11y.copy(title = Present(s)))

    /** Sets the accessible long description (a `<desc>` child). */
    def desc(s: String): Chart[A] =
        copy(a11y = a11y.copy(desc = Present(s)))

    /** Sets the `aria-label` on the chart's root `<svg>`. */
    def ariaLabel(s: String): Chart[A] =
        copy(a11y = a11y.copy(ariaLabel = Present(s)))

    /** Makes the chart responsive: the root uses `width="100%"` and a `viewBox`, no fixed height. */
    def responsive: Chart[A] =
        copy(isResponsive = true)

    /** Makes the chart responsive with the given aspect ratio (width / height). */
    def responsive(aspectRatio: Double): Chart[A] =
        copy(isResponsive = true, aspectRatio = Present(aspectRatio))

    /** Sets all four plot margins (top, right, bottom, left) in pixels. */
    def margins(top: Double, right: Double, bottom: Double, left: Double): Chart[A] =
        copy(marginsCfg = Margins(top, right, bottom, left))

    /** Configures plot margins using a builder lambda: `.margins(_.left(80))`. */
    def margins(f: Margins => Margins): Chart[A] =
        copy(marginsCfg = f(marginsCfg))

    /** Lowers this chart to an `Svg.Root`.
      *
      * This is the explicit path from a `Chart[A]` to a rendered SVG root; embedding a chart in
      * a UI tree requires calling it. It suspends in `Sync` because lowering allocates the chart's
      * reactive state (animation and hover refs) and samples live data sources.
      */
    def lower(using Frame): Svg.Root < Sync =
        // Unsafe: `Sync.Unsafe.defer` brackets the synchronous lowering. `ChartLower.lower` allocates the
        // chart's reactive state and samples any live data source once, all completing synchronously inside
        // the defer, so nothing escapes the bracket; the safe boundary is the `Svg.Root < Sync` return type.
        Sync.Unsafe.defer(kyo.internal.ChartLower.lower(this))

    /** Lowers this chart to an `Svg.Root` together with its resolved [[Scales]].
      *
      * The returned `Scales` exposes the data-to-pixel projection for both axes and the
      * inner plot rectangle, so callers can build overlays, brush outlines, or annotations at
      * exact chart pixel coordinates without re-deriving the scale math. For live charts the
      * scales are computed from the current signal value at call time; they do not update until
      * `lowerWithScales` is called again.
      */
    def lowerWithScales(using Frame): (Svg.Root, Scales) < Sync =
        // Unsafe: as in `lower`, `Sync.Unsafe.defer` brackets the synchronous lowering and scale resolution;
        // the work runs to completion inside the defer and the `(Svg.Root, Scales) < Sync` return is the
        // safe boundary.
        Sync.Unsafe.defer(kyo.internal.ChartLower.lowerWithScales(this))
end Chart

object Chart:

    // ---- Entry point ----

    /** Opens a chart over a static `Seq[A]`.
      *
      * `Chart(data)(marks*)` fixes the row type `A` from `data`, then supplies the marks and
      * returns a `Chart[A]`. Splitting data and marks into two parameter groups is what makes
      * row-type inference work: `A` is bound from `data` before the mark parameter lambdas are
      * read, so `Chart.bar(x = _.month, y = _.revenue)` needs no annotations. A `Chart[A]` lowers
      * to `Svg.Root` via `.lower` wherever one is expected (including `UI.div` children).
      */
    def apply[A](data: Seq[A])(marks: Mark[A]*)(using Frame): Chart[A] =
        build(DataSource.Static(Chunk.from(data)), marks)

    /** Opens a chart over a live `Signal[Seq[A]]`; the marks region redraws on each emission. */
    def apply[A](data: Signal[Seq[A]])(marks: Mark[A]*)(using CanEqual[A, A], Frame): Chart[A] =
        build(DataSource.Live(data.map(Chunk.from)), marks)

    private def build[A](data: DataSource[A], marks: Seq[Mark[A]]): Chart[A] =
        new Chart[A](
            data = data,
            marks = Chunk.from(marks),
            chartSize = (640, 480),
            xAxisCfg = AxisConfig.default,
            yAxisCfg = AxisConfig.default,
            yAxisRightCfg = Absent,
            legendCfg = LegendConfig.default,
            theme = Theme.default,
            xScaleOverride = Absent,
            yScaleOverride = Absent,
            yScaleRightOverride = Absent,
            animateCfg = AnimateConfig.default,
            key = Absent,
            onHover = Absent,
            onSelect = Absent,
            tooltip = Absent
        )

    // ---- Mark factories ----

    /** Creates a bar/column mark.
      *
      * `x` and `y` are required positional parameters; `color` groups the bars and
      * derives a legend; `stack` stacks or normalizes the bars (carrying its own
      * grouping via `Chart.by(...)`); `axis` selects the y-axis (default `Axis.Left`).
      *
      * `bar` has no `size` parameter: a bar's magnitude is its `y`, so a size encoding would be
      * meaningless. The missing parameter makes `Chart.bar(size = ...)` a compile error.
      */
    def bar[A, X: Plottable, Y: Plottable, C](
        x: A => X,
        y: A => Y,
        color: A => C = Unset.of[A, C],
        stack: Stack[A] = Stack.none[A],
        opacity: A => Double = Unset.of[A, Double],
        label: A => String = Unset.of[A, String],
        tooltip: A => String = Unset.of[A, String],
        axis: Axis = Axis.Left
    )(using ColorKey[C], Frame): Mark[A] =
        val xCh                               = Encoding[A, X](x, summon[Plottable[X]], positionalTag[X])
        val yCh                               = Encoding[A, Y](y, summon[Plottable[Y]], positionalTag[Y])
        val colorMaybe: Maybe[Encoding[A, ?]] = Maybe.when(Unset.supplied(color))(colorEncoding[A, C](color))
        val opacityMaybe: Maybe[A => Double]  = Maybe.when(Unset.supplied(opacity))(opacity)
        val labelMaybe: Maybe[A => String]    = Maybe.when(Unset.supplied(label))(label)
        val tooltipMaybe: Maybe[A => String]  = Maybe.when(Unset.supplied(tooltip))(tooltip)
        Mark.Bar(xCh, yCh, colorMaybe, stack, opacityMaybe, labelMaybe, tooltipMaybe, axis)
    end bar

    /** Creates a line mark.
      *
      * `x` and `y` are required; `color` splits into one line per series; `curve`
      * selects the interpolation strategy (default `Curve.linear`); `defined`
      * overrides gap detection (a row where `defined` returns `false` breaks the
      * line); `axis` selects the y-axis. A `y` accessor that returns `Maybe[Y]`
      * type-checks because `Plottable[Maybe[Y]]` is derived from `Plottable[Y]`.
      */
    def line[A, X: Plottable, Y: Plottable, C](
        x: A => X,
        y: A => Y,
        color: A => C = Unset.of[A, C],
        curve: Curve = Curve.linear,
        defined: A => Boolean = Unset.of[A, Boolean],
        opacity: A => Double = Unset.of[A, Double],
        label: A => String = Unset.of[A, String],
        tooltip: A => String = Unset.of[A, String],
        axis: Axis = Axis.Left
    )(using ColorKey[C], Frame): Mark[A] =
        val xCh                               = Encoding[A, X](x, summon[Plottable[X]], positionalTag[X])
        val yCh                               = EncodingMaybe.fromTotal[A, Y](y, summon[Plottable[Y]], positionalTag[Y])
        val colorMaybe: Maybe[Encoding[A, ?]] = Maybe.when(Unset.supplied(color))(colorEncoding[A, C](color))
        val definedMaybe: Maybe[A => Boolean] = Maybe.when(Unset.supplied(defined))(defined)
        val opacityMaybe: Maybe[A => Double]  = Maybe.when(Unset.supplied(opacity))(opacity)
        val labelMaybe: Maybe[A => String]    = Maybe.when(Unset.supplied(label))(label)
        val tooltipMaybe: Maybe[A => String]  = Maybe.when(Unset.supplied(tooltip))(tooltip)
        Mark.Line(xCh, yCh, colorMaybe, curve, definedMaybe, opacityMaybe, labelMaybe, tooltipMaybe, axis)
    end line

    /** Creates an area mark.
      *
      * Two forms are accepted: (1) a single `y` accessor fills the area between the
      * y value and the baseline; (2) `y0` and `y1` fill the band between two values.
      * At lowering time exactly one of {`y`} or {`y0`,`y1`} must be present; a
      * misconfiguration renders an empty frame rather than crashing. `color`, `stack`,
      * `curve`, and `axis` follow the same rules as `bar` and `line`.
      */
    def area[A, X: Plottable, Y: Plottable, C](
        x: A => X,
        y: A => Y = Unset.of[A, Y],
        y0: A => Y = Unset.of[A, Y],
        y1: A => Y = Unset.of[A, Y],
        color: A => C = Unset.of[A, C],
        stack: Stack[A] = Stack.none[A],
        curve: Curve = Curve.linear,
        opacity: A => Double = Unset.of[A, Double],
        label: A => String = Unset.of[A, String],
        tooltip: A => String = Unset.of[A, String],
        axis: Axis = Axis.Left
    )(using ColorKey[C], Frame): Mark[A] =
        val xCh                               = Encoding[A, X](x, summon[Plottable[X]], positionalTag[X])
        val plY                               = summon[Plottable[Y]]
        val tagY                              = positionalTag[Y]
        val yMaybe                            = Maybe.when(Unset.supplied(y))(EncodingMaybe.fromTotal[A, Y](y, plY, tagY))
        val y0Maybe: Maybe[Encoding[A, Y]]    = Maybe.when(Unset.supplied(y0))(Encoding[A, Y](y0, plY, tagY))
        val y1Maybe: Maybe[Encoding[A, Y]]    = Maybe.when(Unset.supplied(y1))(Encoding[A, Y](y1, plY, tagY))
        val colorMaybe: Maybe[Encoding[A, ?]] = Maybe.when(Unset.supplied(color))(colorEncoding[A, C](color))
        val opacityMaybe: Maybe[A => Double]  = Maybe.when(Unset.supplied(opacity))(opacity)
        val labelMaybe: Maybe[A => String]    = Maybe.when(Unset.supplied(label))(label)
        val tooltipMaybe: Maybe[A => String]  = Maybe.when(Unset.supplied(tooltip))(tooltip)
        Mark.Area(xCh, yMaybe, y0Maybe, y1Maybe, colorMaybe, stack, curve, opacityMaybe, labelMaybe, tooltipMaybe, axis)
    end area

    /** Creates a point (scatter/bubble) mark.
      *
      * `x` and `y` are required; `color`, `size`, and `symbol` are optional
      * non-positional parameters. Unlike `bar`, `point` carries a `size` encoding,
      * which scales the dot radius by magnitude. A `y` accessor returning `Maybe[Y]`
      * renders a gap (no dot) at `Absent` rows.
      */
    def point[A, X: Plottable, Y: Plottable, C](
        x: A => X,
        y: A => Y,
        color: A => C = Unset.of[A, C],
        size: A => Double = Unset.of[A, Double],   // sqrt-area scaled magnitude
        sizePx: A => Double = Unset.of[A, Double], // raw pixel radius
        symbol: A => Symbol = Unset.of[A, Symbol],
        opacity: A => Double = Unset.of[A, Double],
        label: A => String = Unset.of[A, String],
        tooltip: A => String = Unset.of[A, String],
        axis: Axis = Axis.Left
    )(using ColorKey[C], Frame): Mark[A] =
        val xCh                               = Encoding[A, X](x, summon[Plottable[X]], positionalTag[X])
        val yCh                               = EncodingMaybe.fromTotal[A, Y](y, summon[Plottable[Y]], positionalTag[Y])
        val colorMaybe: Maybe[Encoding[A, ?]] = Maybe.when(Unset.supplied(color))(colorEncoding[A, C](color))
        val sizeSup                           = Unset.supplied(size)
        val sizePxSup                         = Unset.supplied(sizePx)
        // When both size and sizePx are supplied, size wins and sizePx is dropped.
        val sizeMaybe: Maybe[A => Double]    = if sizeSup then Present(size) else Absent
        val sizePxMaybe: Maybe[A => Double]  = if sizePxSup && !sizeSup then Present(sizePx) else Absent
        val symbolMaybe: Maybe[A => Symbol]  = Maybe.when(Unset.supplied(symbol))(symbol)
        val opacityMaybe: Maybe[A => Double] = Maybe.when(Unset.supplied(opacity))(opacity)
        val labelMaybe: Maybe[A => String]   = Maybe.when(Unset.supplied(label))(label)
        val tooltipMaybe: Maybe[A => String] = Maybe.when(Unset.supplied(tooltip))(tooltip)
        Mark.Point(xCh, yCh, colorMaybe, sizeMaybe, sizePxMaybe, symbolMaybe, opacityMaybe, labelMaybe, tooltipMaybe, axis)
    end point

    /** Creates a reference line mark.
      *
      * A `rule` draws a horizontal (`y`) or vertical (`x`) line spanning the plot at
      * a constant value or at a `Signal`-tracked value. At least one of `x` or `y`
      * must be supplied. `rule` has no `color` or `size` parameter: a reference line
      * reads no row, so per-datum encodings do not apply.
      *
      * Constant form: `rule(y = Usd(1000))`. Signal form: `rule(y = threshold)`
      * where `threshold: Signal[Usd]`.
      */
    def rule[A, C: Plottable](
        x: RuleValue[C] = RuleValue.unset[C],
        y: RuleValue[C] = RuleValue.unset[C],
        axis: Axis = Axis.Left
    )(using Frame): Mark[A] =
        // RuleValue.Unset has element type Nothing, so a typed pattern match against RuleValue[C] cannot
        // reach it; a reference-identity check against the shared singleton detects the unset arm.
        val xMaybe: Maybe[RuleValue[?]] = if x eq RuleValue.Unset then Absent else Present(x)
        val yMaybe: Maybe[RuleValue[?]] = if y eq RuleValue.Unset then Absent else Present(y)
        Mark.Rule(xMaybe, yMaybe, axis)
    end rule

    /** Creates a text annotation mark.
      *
      * Renders one `Svg.text` per row at `(x, y)` with the string from `label`. `y` is treated
      * as a `EncodingMaybe` internally so gap rows (where the accessor would yield no value) skip
      * the text element. `anchor` controls horizontal alignment. `color` optionally groups rows
      * by category so each group can carry a distinct fill color.
      */
    def text[A, X: Plottable, Y: Plottable, C](
        x: A => X,
        y: A => Y,
        label: A => String,
        color: A => C = Unset.of[A, C],
        anchor: TextAnchor = TextAnchor.Middle,
        opacity: A => Double = Unset.of[A, Double],
        axis: Axis = Axis.Left
    )(using ColorKey[C], Frame): Mark[A] =
        val xCh                               = Encoding[A, X](x, summon[Plottable[X]], positionalTag[X])
        val yCh                               = EncodingMaybe.fromTotal[A, Y](y, summon[Plottable[Y]], positionalTag[Y])
        val colorMaybe: Maybe[Encoding[A, ?]] = Maybe.when(Unset.supplied(color))(colorEncoding[A, C](color))
        val opacityMaybe: Maybe[A => Double]  = Maybe.when(Unset.supplied(opacity))(opacity)
        Mark.Text(xCh, yCh, label, colorMaybe, anchor, opacityMaybe, axis)
    end text

    /** Creates an error bar mark.
      *
      * Renders a vertical line from `low` to `high` at `x`, two horizontal caps of `capWidth` pixels,
      * and a center marker at `y`. All three y parameters (`y`, `low`, `high`) contribute to the y-axis
      * extent. `color` optionally groups by category. `capWidth` defaults to 6 pixels.
      *
      * The rendered elements are plain `Svg.line` and `Svg.circle` with no `url(#id)` or `<marker>`
      * references.
      */
    def errorBar[A, X: Plottable, Y: Plottable, C](
        x: A => X,
        y: A => Y,
        low: A => Y,
        high: A => Y,
        color: A => C = Unset.of[A, C],
        capWidth: Double = 6.0,
        axis: Axis = Axis.Left
    )(using ColorKey[C], Frame): Mark[A] =
        val plY                               = summon[Plottable[Y]]
        val tagY                              = positionalTag[Y]
        val colorMaybe: Maybe[Encoding[A, ?]] = Maybe.when(Unset.supplied(color))(colorEncoding[A, C](color))
        Mark.ErrorBar(
            Encoding(x, summon[Plottable[X]], positionalTag[X]),
            Encoding(y, plY, tagY),
            Encoding(low, plY, tagY),
            Encoding(high, plY, tagY),
            colorMaybe,
            capWidth,
            axis
        )
    end errorBar

    /** Builds a `Stack[A]` that groups rows by `group` and optionally normalizes to 100%.
      *
      * This is the only public constructor for `Stack`. Pass the result to a mark's
      * `stack` parameter:
      *
      * ```scala
      * Chart.bar(x = _.month, y = _.revenue, stack = Chart.by(_.region))
      * Chart.bar(x = _.month, y = _.revenue, stack = Chart.by(_.region, normalize = true))
      * ```
      *
      * `G` must satisfy `ColorKey[G]`: a stable cross-platform identity key (`ConcreteTag[G]`)
      * and `CanEqual[G, G]`. Under `-language:strictEquality`, an enum `G` must `derives CanEqual`.
      *
      * `normalize = true` produces a 100%-stacked bar; `false` (the default) stacks
      * absolute values.
      */
    def by[A, G](group: A => G, normalize: Boolean = false)(using ColorKey[G]): Stack[A] =
        // Unsafe: the group accessor is stored erased to A => Any in the Grouping carrier and keyed
        // downstream ONLY by CatKey (ConcreteTag identity + label), never reconstructed back to G;
        // widening A => G to A => Any is sound because no consumer reads the stored value at a
        // G-typed position. Mirrors the colorEncoding bridge.
        Grouping(Present(group.asInstanceOf[A => Any]), normalize)

    // ---- Public config types ----

    /** Configures axis appearance for one axis.
      *
      * Builder methods return a copy with one field changed, so chains compose
      * without mutation. Used as the argument to `.xAxis(f)` / `.yAxis(f)` /
      * `.yAxisRight(f)`: write `_.grid.ticks(5).format(...)`.
      *
      * `axisLabel` is an optional axis label string. `showGrid` enables gridlines
      * across the plot. `tickCount` is the desired number of ticks (a hint, not a
      * hard limit). `tickFormat` overrides the default tick label formatter.
      */
    final case class AxisConfig(
        axisLabel: Maybe[String],
        showGrid: Boolean,
        tickCount: Int,
        tickFormat: Maybe[Double => String],
        tickRotation: Double = 0.0,
        tickAnchor: TextAnchor = TextAnchor.Middle,
        reversed: Boolean = false,
        padding: Double = 0.0
    ):
        def label(s: String): AxisConfig             = copy(axisLabel = Present(s))
        def grid: AxisConfig                         = copy(showGrid = true)
        def ticks(n: Int): AxisConfig                = copy(tickCount = n)
        def format(f: Double => String): AxisConfig  = copy(tickFormat = Present(f))
        def reverse: AxisConfig                      = copy(reversed = true)
        def pad(fraction: Double): AxisConfig        = copy(padding = fraction)
        def rotateTicks(degrees: Double): AxisConfig = copy(tickRotation = degrees)
        def anchor(a: TextAnchor): AxisConfig        = copy(tickAnchor = a)

    end AxisConfig

    object AxisConfig:
        val default: AxisConfig = AxisConfig(Absent, false, 5, Absent)

    /** Configures legend appearance, position, color scale, and interactivity.
      *
      * Builder methods return a copy with one field changed. Used as the argument to
      * `.legend(f)`: write `_.top` or `_.hidden`. The `colorScale` methods attach an
      * explicit color mapping for the mark's `color` parameter.
      *
      * `position` selects where the legend box is placed relative to the plot area.
      * `isHidden` hides the legend entirely. `colorScale` is an optional
      * [[kyo.Chart.LegendConfig.ColorScale]]: either a `Categorical` mapping (built from
      * value-equality pairs or a label function) or a `Sequential` low-to-high gradient over a
      * numeric domain; if `Absent` the default palette is used.
      *
      * `isInteractive` and `hiddenSeries` enable click-to-toggle series visibility: when a
      * `hiddenSeries` ref is attached via `interactive`, clicking a legend swatch toggles that
      * series index in the ref, and the marks lowering filters out the hidden series.
      */
    final case class LegendConfig(
        position: Maybe[LegendPosition],
        isHidden: Boolean,
        colorScale: Maybe[LegendConfig.ColorScale],
        isInteractive: Boolean = false,
        hiddenSeries: Maybe[Signal.SignalRef[Set[Int]]] = Absent
    ):
        def top: LegendConfig    = copy(position = Present(LegendPosition.Top))
        def bottom: LegendConfig = copy(position = Present(LegendPosition.Bottom))
        def left: LegendConfig   = copy(position = Present(LegendPosition.Left))
        def right: LegendConfig  = copy(position = Present(LegendPosition.Right))
        def hidden: LegendConfig = copy(isHidden = true)

        /** Enables click-to-toggle series visibility, driven by the supplied `ref`.
          *
          * Clicking a legend swatch toggles that series' ordinal index in `ref`: an index not in the set is
          * added (the series is hidden), an index already present is removed (the series is shown again). The
          * marks lowering reads `ref` and drops rows whose color category index is in the hidden set, applying
          * the filter before color-splitting so the visible categories keep their stable palette order.
          *
          * Index-based keying means two color categories with colliding `toString` values are treated as
          * distinct entries and can be toggled independently.
          *
          * The hidden indices are held in a plain `scala.Predef.Set[Int]`: genuine set membership (unordered,
          * unique, toggled with `contains`/`+`/`-`), for which kyo has no first-class primitive and a
          * `Chunk` would be the wrong shape.
          */
        def interactive(ref: Signal.SignalRef[Set[Int]]): LegendConfig =
            copy(isInteractive = true, hiddenSeries = Present(ref))

        /** Attaches a color scale built from value-equality pairs over a typed key `K`.
          *
          * Each pair maps a key value to a color; categories are matched by `==` (value equality), so two
          * enum cases that share a `toString` stay distinct. A category with no matching pair falls back to
          * `Style.Color.blue` (the first default-palette color).
          *
          * The Scala call name is `colorScale[K]`. The JVM bytecode symbol is `colorScaleTyped` (set via
          * `@targetName`) to avoid an erasure conflict with the `String => Style.Color` overload.
          *
          * Example:
          *
          * ```scala
          * .legend(_.colorScale[Region](
          *   Region.NA -> Style.Color.blue,
          *   Region.EU -> Style.Color.green
          * ))
          * ```
          */
        @scala.annotation.targetName("colorScaleTyped")
        def colorScale[K](pairs: (K, Style.Color)*)(using CanEqual[K, K]): LegendConfig =
            val chunk = Chunk.from(pairs)
            copy(colorScale =
                Present(LegendConfig.ColorScale.Categorical(v =>
                    // Categories arrive type-erased as Any, so the typed keys are matched by `equals` rather than
                    // ==. An unmatched category falls back to Style.Color.blue (the first default-palette color).
                    Maybe.fromOption(chunk.collectFirst { case (k, c) if k.equals(v) => c }).getOrElse(Style.Color.blue)
                ))
            )
        end colorScale

        /** Attaches a total color-scale function keyed on the category label string.
          *
          * `f` must be exhaustive over the category labels that the `color` parameter produces. For a `String`-keyed
          * `color` parameter the labels are the raw string values. For an enum `color` parameter the labels are the
          * enum case names (e.g. `"NA"`, `"EU"`, `"APAC"`).
          */
        def colorScale(f: String => Style.Color): LegendConfig =
            copy(colorScale = Present(LegendConfig.ColorScale.Categorical(v => f(v.toString))))

        /** Attaches a sequential low-to-high color gradient over the numeric `color` parameter domain.
          *
          * Each row's numeric color value is normalized into `[0, 1]` over the data extent and interpolated
          * between `low` (domain minimum) and `high` (domain maximum). The legend shows a continuous gradient
          * swatch; the mark fills are concrete interpolated colors, never `url(#...)` references.
          */
        def colorScaleSequential(low: Style.Color, high: Style.Color): LegendConfig =
            copy(colorScale = Present(LegendConfig.ColorScale.Sequential(low, high, Absent)))

        /** Attaches an explicit sequential color scale, allowing a fixed domain override. */
        def colorScaleSequential(scale: LegendConfig.ColorScale.Sequential): LegendConfig =
            copy(colorScale = Present(scale))

    end LegendConfig

    object LegendConfig:
        val default: LegendConfig = LegendConfig(Absent, false, Absent)

        /** A legend color scale: how raw `color` parameter values map to swatch and mark colors.
          *
          * `Categorical` carries a total function from a raw value to a color (built from value-equality pairs
          * or a label function); each distinct category gets its own swatch. `Sequential` carries a low-to-high
          * gradient over a numeric domain: values are normalized into `[0, 1]` over the data extent (or the
          * `domain` override when present) and interpolated between `low` and `high`.
          */
        enum ColorScale:
            private[kyo] case Categorical(fn: Any => Style.Color)
            case Sequential(low: Style.Color, high: Style.Color, domain: Maybe[(Double, Double)])
        end ColorScale
    end LegendConfig

    /** Configures chart theme.
      *
      * Selects the overall color scheme (light or dark) and optionally overrides the
      * palette. Builder methods return a copy. Used as the argument to `.theme(f)`:
      * write `_.light` or `_.dark`.
      */
    final case class Theme(
        isDark: Boolean,
        palette: Maybe[Chunk[Style.Color]],
        background: Maybe[Style.Color] = Absent,
        axisColor: Maybe[Style.Color] = Absent,
        gridColor: Maybe[Style.Color] = Absent,
        fontFamily: Maybe[String] = Absent,
        fontSize: Maybe[Double] = Absent
    ) derives CanEqual:
        def light: Theme = copy(isDark = false)
        def dark: Theme  = copy(isDark = true)

        /** Overrides the plot background fill color. */
        def background(c: Style.Color): Theme = copy(background = Present(c))

        /** Overrides the axis line, tick mark, and tick label color. */
        def axisColor(c: Style.Color): Theme = copy(axisColor = Present(c))

        /** Overrides the gridline color. */
        def gridColor(c: Style.Color): Theme = copy(gridColor = Present(c))

        /** Sets the font family for axis and tick labels. */
        def font(family: String): Theme = copy(fontFamily = Present(family))

        /** Sets the font size (in pixels) for axis and tick labels. */
        def fontSize(px: Double): Theme = copy(fontSize = Present(px))

        /** Sets the categorical palette from a named [[Palette]]. */
        def palette(p: Palette): Theme = copy(palette = Present(Palette.colors(p)))

        /** Sets the categorical palette from an explicit color list. */
        def palette(colors: Seq[Style.Color]): Theme = copy(palette = Present(Chunk.from(colors)))

    end Theme

    object Theme:
        val default: Theme = Theme(false, Absent)

    /** Configures animation for live charts.
      *
      * `ease(d)` enables one-shot SMIL transitions with duration `d`; `none` disables all transitions. The easing
      * function is fixed to ease-in-out-cubic. Lowering animates same-structure path morphs (equal command count)
      * with a declarative SMIL `animate` on `d` but snaps structural changes (different command count, i.e. a
      * category added or removed), since SMIL cannot interpolate paths of differing command count.
      * Used as the argument to `.animate(f)`: write `_.ease(300.millis)` or `_.none`.
      */
    final case class AnimateConfig(
        enabled: Boolean,
        duration: Duration
    ):
        def ease(d: Duration): AnimateConfig = copy(enabled = true, duration = d)
        def none: AnimateConfig              = copy(enabled = false)

    end AnimateConfig

    object AnimateConfig:
        val default: AnimateConfig =
            AnimateConfig(true, Duration.fromJava(java.time.Duration.ofMillis(300)))

    /** Overrides the automatically-inferred scale for an axis.
      *
      * Builder methods return a copy with the override set. Used as the argument to `.xScale(f)` or `.yScale(f)`:
      * write `_.band`, `_.linear(0, 5000)`, or `_.log`. An unset override (the default) leaves the scale inferred
      * from the accessor's `Plottable` kind and the data extent.
      *
      * `nice`, `clamp`, and `pad` refine the fitted domain independently of `kind`. `nice=true` (default) snaps
      * domain bounds to round values; `clamp=false` (default) allows extrapolation beyond the domain. `pad` widens
      * the domain symmetrically by the given fraction before fitting.
      */
    final case class ScaleOverride(
        kind: Maybe[ScaleKind],
        nice: Boolean = true,
        clamp: Boolean = false,
        pad: Double = 0.0
    ):
        def band: ScaleOverride                           = copy(kind = Present(ScaleKind.Band))
        def log: ScaleOverride                            = copy(kind = Present(ScaleKind.Log))
        def linear(lo: Double, hi: Double): ScaleOverride = copy(kind = Present(ScaleKind.Linear(lo, hi)))
        def time: ScaleOverride                           = copy(kind = Present(ScaleKind.Time))
        def point: ScaleOverride                          = copy(kind = Present(ScaleKind.Point))
        def symlog: ScaleOverride                         = copy(kind = Present(ScaleKind.Symlog))
        def withNice(on: Boolean): ScaleOverride          = copy(nice = on)
        def noNice: ScaleOverride                         = copy(nice = false)
        def withClamp(on: Boolean): ScaleOverride         = copy(clamp = on)
        def withPad(fraction: Double): ScaleOverride      = copy(pad = fraction)

    end ScaleOverride

    object ScaleOverride:
        val default: ScaleOverride = ScaleOverride(Absent)

    /** Plot margins in pixels around the inner plot rectangle.
      *
      * `top`, `right`, `bottom`, and `left` reserve space for axis chrome and labels.
      * The defaults (`20/20/40/60`) give the larger bottom and left room for the x-axis
      * tick labels and the y-axis numbers. Set them with `.margins(t, r, b, l)` or
      * `.margins(_.left(80))`.
      */
    final case class Margins(
        top: Double,
        right: Double,
        bottom: Double,
        left: Double
    ) derives CanEqual:
        def top(v: Double): Margins    = copy(top = v)
        def right(v: Double): Margins  = copy(right = v)
        def bottom(v: Double): Margins = copy(bottom = v)
        def left(v: Double): Margins   = copy(left = v)
    end Margins

    object Margins:
        /** The default margins (top 20, right 20, bottom 40, left 60). */
        val default: Margins = Margins(20.0, 20.0, 40.0, 60.0)
    end Margins

    /** Accessibility metadata emitted into the chart's root `<svg>`.
      *
      * `title` becomes a `<title>` child and implies `role="img"` on the root so assistive
      * technology announces the chart as a single image with that accessible name. `desc`
      * becomes a `<desc>` child for a longer description. `ariaLabel` sets the `aria-label`
      * attribute directly. All three default to `Absent`, in which case no a11y markup is
      * emitted. Set them with `.title(s)`, `.desc(s)`, and `.ariaLabel(s)`.
      */
    final case class A11y(
        title: Maybe[String],
        desc: Maybe[String],
        ariaLabel: Maybe[String]
    ) derives CanEqual

    object A11y:
        /** The default: no accessibility metadata. */
        val default: A11y = A11y(Absent, Absent, Absent)
    end A11y

    /** Controls built-in visual highlight behavior when a hover or select ref is set.
      *
      * All fields default `false`/`Absent`. Set `hoverHighlight` or `selectHighlight` to opt into the
      * built-in opacity boost on the active mark. `hoverStyle` and `selectStyle` let you supply a custom
      * `Style` instead of the default boost. If the relevant ref (`onHover`/`onSelect`) is not configured
      * on the chart, all highlight settings are no-ops.
      *
      * Build via the chaining methods: `_.highlightHover`, `_.highlightSelect`, `_.hoverStyle(s)`,
      * `_.selectStyle(s)`.
      */
    final case class InteractionConfig(
        hoverHighlight: Boolean = false,
        selectHighlight: Boolean = false,
        hoverStyle: Maybe[Style] = Absent,
        selectStyle: Maybe[Style] = Absent
    ):
        /** Enable the built-in hover highlight (opacity boost on the hovered mark). */
        def highlightHover: InteractionConfig = copy(hoverHighlight = true)

        /** Enable the built-in select highlight (opacity boost on the selected mark). */
        def highlightSelect: InteractionConfig = copy(selectHighlight = true)

        /** Apply a custom style on hover (also enables hover highlight). */
        def hoverStyle(s: Style): InteractionConfig = copy(hoverHighlight = true, hoverStyle = Present(s))

        /** Apply a custom style on select (also enables select highlight). */
        def selectStyle(s: Style): InteractionConfig = copy(selectHighlight = true, selectStyle = Present(s))
    end InteractionConfig

    object InteractionConfig:
        /** The default config: all highlight features disabled. */
        val default: InteractionConfig = InteractionConfig()
    end InteractionConfig

    /** Named color palettes for categorical charts.
      *
      * Pass to `_.theme(_.palette(Palette.Okabe))` to select a categorical color set.
      * `Default` is the built-in palette (not optimized for color-vision deficiency). `Okabe`
      * is the Okabe-Ito 8-color set, the recommended accessible choice for categorical data.
      * `Viridis` is an 8-category perceptually-derived set. `Tableau10` is the Tableau 10
      * categorical palette. Resolve a palette to its colors with `Palette.colors(p)`.
      */
    enum Palette derives CanEqual:
        case Default
        case Okabe
        case Viridis
        case Tableau10
    end Palette

    object Palette:
        /** Resolve a named palette to its `Chunk[Style.Color]`. */
        def colors(p: Palette): Chunk[Style.Color] = p match
            case Palette.Default => kyo.internal.ChartLower.DefaultPalette
            case Palette.Okabe =>
                Chunk(
                    Style.Color.rgb(0, 0, 0),
                    Style.Color.rgb(230, 159, 0),
                    Style.Color.rgb(86, 180, 233),
                    Style.Color.rgb(0, 158, 115),
                    Style.Color.rgb(240, 228, 66),
                    Style.Color.rgb(0, 114, 178),
                    Style.Color.rgb(213, 94, 0),
                    Style.Color.rgb(204, 121, 167)
                )
            case Palette.Viridis =>
                Chunk(
                    Style.Color.rgb(68, 1, 84),
                    Style.Color.rgb(72, 40, 120),
                    Style.Color.rgb(62, 74, 137),
                    Style.Color.rgb(49, 104, 142),
                    Style.Color.rgb(38, 130, 142),
                    Style.Color.rgb(31, 158, 137),
                    Style.Color.rgb(53, 183, 121),
                    Style.Color.rgb(253, 231, 37)
                )
            case Palette.Tableau10 =>
                Chunk(
                    Style.Color.rgb(31, 119, 180),
                    Style.Color.rgb(255, 127, 14),
                    Style.Color.rgb(44, 160, 44),
                    Style.Color.rgb(214, 39, 40),
                    Style.Color.rgb(148, 103, 189),
                    Style.Color.rgb(140, 86, 75),
                    Style.Color.rgb(227, 119, 194),
                    Style.Color.rgb(127, 127, 127),
                    Style.Color.rgb(188, 189, 34),
                    Style.Color.rgb(23, 190, 207)
                )
    end Palette

    // ---- Enums ----

    /** Selects which y-axis a mark binds to.
      *
      * Marks that carry no explicit `axis` parameter default to `Axis.Left`. A mark
      * with `axis = Axis.Right` binds to an independent right y-scale, which is
      * resolved separately from the left y-scale and rendered on the right margin.
      *
      * Use `Axis.Right` together with `.yAxisRight(...)` to configure the right axis.
      */
    enum Axis derives CanEqual:
        case Left, Right

    /** Interpolation strategy between line or area mark vertices.
      *
      * `linear` draws straight segments; `monotone` uses a Hermite spline that
      * preserves monotonicity; `stepBefore` and `stepAfter` produce staircase lines;
      * `basis` uses a B-spline; `catmullRom` uses a Catmull-Rom spline.
      */
    enum Curve derives CanEqual:
        case linear, monotone, stepBefore, stepAfter, basis, catmullRom

    /** Point glyph shape.
      *
      * Selects the shape rendered at each point in a `point` mark. `circle` is the
      * default and renders as an `Svg.circle`; the others render as `Svg.path`
      * glyphs of equal visual weight.
      */
    enum Symbol derives CanEqual:
        case circle, square, triangle, diamond, cross

    /** Text alignment for `text` marks and rotated axis tick labels. */
    enum TextAnchor derives CanEqual:
        case Start, Middle, End

    /** Position of a legend relative to the plot area. */
    enum LegendPosition derives CanEqual:
        case Top, Bottom, Left, Right

    /** The scale kind selected by a `Chart.ScaleOverride`. */
    enum ScaleKind derives CanEqual:
        case Band
        case Log
        case Linear(lo: Double, hi: Double)
        case Time
        case Point
        case Symlog
    end ScaleKind

    /** The constant-or-signal carrier for `Chart.rule(x = ...)` / `rule(y = ...)`.
      *
      * A rule position is either a constant value (`Const`) or a `Signal`-tracked
      * value (`Reactive`). Neither form carries a row accessor; a rule reads no row.
      * The lowering phase resolves `Const` once and `Reactive` through
      * `signal.render`.
      */
    enum RuleValue[C]:
        case Const(value: C, plottable: Plottable[C])
        case Reactive(signal: Signal[C], plottable: Plottable[C])
        case Unset extends RuleValue[Nothing] // total absence sentinel for an unsupplied x or y
    end RuleValue

    /** Implicit conversions from plain values and signals to `RuleValue`.
      *
      * These allow the inline `rule(y = 1000.0)` and `rule(y = threshold)` forms
      * to compile without an explicit `RuleValue.Const(...)` wrapper. Defining them as
      * `implicit def` keeps the `scala.language.implicitConversions` feature import local
      * to this file, so callers apply the conversions without importing the feature.
      */
    object RuleValue:
        // Unsafe: Unset carries no C-typed value (its element type is Nothing), so widening the shared
        // singleton from RuleValue[Nothing] to RuleValue[C] can never expose a value of the wrong type.
        private[kyo] def unset[C]: RuleValue[C] = RuleValue.Unset.asInstanceOf[RuleValue[C]]
        implicit def constConversion[C: Plottable](c: C): RuleValue[C] =
            RuleValue.Const(c, summon[Plottable[C]])
        implicit def signalConversion[C: Plottable](s: Signal[C])(using CanEqual[C, C]): RuleValue[C] =
            RuleValue.Reactive(s, summon[Plottable[C]])
    end RuleValue

    // ---- Mark hierarchy ----

    /** A single visual layer over the chart's row type `A`.
      *
      * Sealed: the seven cases (`Bar`, `Line`, `Area`, `Point`, `Rule`, `Text`, `ErrorBar`)
      * are the complete mark vocabulary. All lowering in `internal/ChartLower.scala` is an
      * exhaustive match on this sealed trait; adding a new case is a compile error until
      * lowering is extended. Marks are pure immutable values; they carry no rendering logic.
      *
      * Marks are produced by the `Chart.*` factories `bar`/`line`/`area`/`point`/`rule`/`text`
      * and consumed by `Chart(data)(marks*)`. Users never name the concrete cases
      * directly; the factories are the public API.
      */
    sealed abstract class Mark[A]

    object Mark:

        /** A bar or column mark.
          *
          * Carries required positional parameters `x` and `y`, and optional grouping
          * parameters `color` and `stack`. `axis` selects the y-axis. `opacity`, `label`,
          * and `tooltip` are optional per-datum accessors, `Absent` when the factory
          * caller omits them.
          */
        final private[kyo] case class Bar[A, X, Y](
            x: Encoding[A, X],
            y: Encoding[A, Y],
            color: Maybe[Encoding[A, ?]],
            stack: Grouping[A],
            opacity: Maybe[A => Double] = Absent,
            label: Maybe[A => String] = Absent,
            tooltip: Maybe[A => String] = Absent,
            axis: Axis
        ) extends Mark[A]

        /** A line mark with optional gap support.
          *
          * `y` is a `EncodingMaybe` to support `Maybe[Y]` accessors (gaps). `color`
          * splits into one line per series. `defined` overrides gap detection.
          * `opacity`, `label`, and `tooltip` are optional per-datum accessors,
          * `Absent` when the factory caller omits them.
          */
        final private[kyo] case class Line[A, X, Y](
            x: Encoding[A, X],
            y: EncodingMaybe[A, Y],
            color: Maybe[Encoding[A, ?]],
            curve: Curve,
            defined: Maybe[A => Boolean],
            opacity: Maybe[A => Double] = Absent,
            label: Maybe[A => String] = Absent,
            tooltip: Maybe[A => String] = Absent,
            axis: Axis
        ) extends Mark[A]

        /** An area mark.
          *
          * Either `y` (fill to baseline) or the `y0`/`y1` band form must be supplied.
          * Exactly one of the two forms is valid; a lowering-time check selects it.
          * `opacity`, `label`, and `tooltip` are optional per-datum accessors,
          * `Absent` when the factory caller omits them.
          */
        final private[kyo] case class Area[A, X, Y](
            x: Encoding[A, X],
            y: Maybe[EncodingMaybe[A, Y]],
            y0: Maybe[Encoding[A, Y]],
            y1: Maybe[Encoding[A, Y]],
            color: Maybe[Encoding[A, ?]],
            stack: Grouping[A],
            curve: Curve,
            opacity: Maybe[A => Double] = Absent,
            label: Maybe[A => String] = Absent,
            tooltip: Maybe[A => String] = Absent,
            axis: Axis
        ) extends Mark[A]

        /** A point (scatter/bubble) mark.
          *
          * `y` is a `EncodingMaybe` so `Maybe[Y]` accessors render gaps as absent dots.
          * `size` controls the dot radius as a sqrt-area magnitude; `sizePx` is the
          * raw-pixel-radius escape hatch; `symbol` selects the glyph. `opacity`, `label`,
          * and `tooltip` are optional per-datum accessors, `Absent` when the factory
          * caller omits them.
          */
        final private[kyo] case class Point[A, X, Y](
            x: Encoding[A, X],
            y: EncodingMaybe[A, Y],
            color: Maybe[Encoding[A, ?]],
            size: Maybe[A => Double],
            sizePx: Maybe[A => Double] = Absent, // raw pixel radius
            symbol: Maybe[A => Symbol],
            opacity: Maybe[A => Double] = Absent,
            label: Maybe[A => String] = Absent,
            tooltip: Maybe[A => String] = Absent,
            axis: Axis
        ) extends Mark[A]

        /** A reference line mark.
          *
          * `x` or `y` carries a `RuleValue`: a constant (`Const`) or a reactive
          * signal (`Reactive`). At least one of `x`/`y` should be `Present`. `rule`
          * has no `color` or `size` parameter.
          */
        final private[kyo] case class Rule[A](
            x: Maybe[RuleValue[?]],
            y: Maybe[RuleValue[?]],
            axis: Axis
        ) extends Mark[A]

        /** A text annotation mark.
          *
          * Renders one `Svg.text` per row at `(x, y)` with the string produced by
          * `label`. `y` is a `EncodingMaybe` so gap rows produce no text. `color`
          * optionally groups by category; `anchor` controls horizontal alignment;
          * `opacity` controls per-datum transparency.
          */
        final private[kyo] case class Text[A, X, Y](
            x: Encoding[A, X],
            y: EncodingMaybe[A, Y],
            label: A => String,
            color: Maybe[Encoding[A, ?]],
            anchor: TextAnchor,
            opacity: Maybe[A => Double],
            axis: Axis
        ) extends Mark[A]

        /** An error bar mark.
          *
          * Renders a vertical line from `low` to `high` at `x`, with horizontal
          * caps of `capWidth` pixels, and a center marker at `y`. All three y parameters (`y`, `low`, `high`)
          * fold into the y-extent. `color` optionally groups by category.
          */
        final private[kyo] case class ErrorBar[A, X, Y](
            x: Encoding[A, X],
            y: Encoding[A, Y],
            low: Encoding[A, Y],
            high: Encoding[A, Y],
            color: Maybe[Encoding[A, ?]],
            capWidth: Double,
            axis: Axis
        ) extends Mark[A]

    end Mark

    // ---- Scales ----

    /** Read-only projection of the resolved scale state after lowering a `Chart`.
      *
      * Obtain one via `chart.lowerWithScales`. The accessors expose the data-to-pixel projection
      * for both axes and the inner plot rectangle, while the internal `Scale`/`Layout` types stay
      * private. Use it as an escape hatch to build overlays, brush outlines, or custom annotations
      * at exact chart pixel coordinates. `x` and `y` are always present; `yRight` is `Present` only
      * for dual-axis charts; `plot` is the inner plot rectangle in pixel coordinates.
      */
    sealed abstract class Scales:
        def x: Scales.Axis
        def y: Scales.Axis
        def yRight: Maybe[Scales.Axis]
        def plot: Scales.Rect
    end Scales

    object Scales:

        /** The inner plot rectangle in pixel coordinates. */
        final case class Rect(x: Double, y: Double, width: Double, height: Double) derives CanEqual

        /** A single axis projection: maps domain values to pixels and back. */
        sealed abstract class Axis:
            /** Map a continuous domain value to its pixel coordinate on this axis. */
            def toPixel(value: Double): Double

            /** Map a category key to its band pixel coordinate, or `Absent` for non-categorical axes / unknown keys. */
            def toPixelCategory(key: String): Maybe[Double]

            /** Invert a pixel coordinate back to a resolved domain value. */
            def invert(pixel: Double): Scales.Resolved

            /** The scale family this axis was fitted with. */
            def kind: ScaleKind
        end Axis

        /** The inverse of an axis projection: a continuous value or a category key. */
        enum Resolved derives CanEqual:
            case Continuous(value: Double)
            case Category(key: String)
        end Resolved

        final private case class AxisImpl(scale: kyo.internal.Scale, kind: ScaleKind) extends Axis:
            def toPixel(value: Double): Double =
                scale.apply(kyo.internal.Domain.Continuous(value))
            def toPixelCategory(key: String): Maybe[Double] =
                // Only categorical scales project a category key, and only when the key is actually present.
                scale match
                    case b: kyo.internal.Scale.Band =>
                        if b.keys.contains(key) then Present(scale.apply(kyo.internal.Domain.Category(key))) else Absent
                    case _ => Absent
                end match
            end toPixelCategory
            def invert(pixel: Double): Scales.Resolved =
                scale.invert(pixel) match
                    case kyo.internal.Domain.Continuous(v) => Resolved.Continuous(v)
                    case kyo.internal.Domain.Category(k)   => Resolved.Category(k)
                    case kyo.internal.Domain.Temporal(ms)  => Resolved.Continuous(ms.toDouble)
        end AxisImpl

        final private case class Impl(x: Axis, y: Axis, yRight: Maybe[Axis], plot: Rect) extends Scales

        private def kindOf(scale: kyo.internal.Scale): ScaleKind = scale match
            case _: kyo.internal.Scale.Band   => ScaleKind.Band
            case _: kyo.internal.Scale.Log    => ScaleKind.Log
            case s: kyo.internal.Scale.Linear => ScaleKind.Linear(s.domainMin, s.domainMax)
            case _: kyo.internal.Scale.Time   => ScaleKind.Time
            case _: kyo.internal.Scale.Symlog => ScaleKind.Symlog

        /** Internal factory used by the lowering to project resolved scales into the public surface. */
        private[kyo] def from(
            xs: kyo.internal.Scale,
            ysL: kyo.internal.Scale,
            ysR: Maybe[kyo.internal.Scale],
            plot: Rect
        ): Scales =
            Impl(
                AxisImpl(xs, kindOf(xs)),
                AxisImpl(ysL, kindOf(ysL)),
                ysR.map(s => AxisImpl(s, kindOf(s))),
                plot
            )
    end Scales

    // ---- Plottable typeclass ----

    /** A typeclass that maps a value type to the scale used to plot it.
      *
      * Built-in instances cover `Int`, `Long`, `Double`, `String`, and `Instant`. Enum types derive instances
      * automatically via `derives Plottable`. Opaque numeric quantities backed by `Double` use `Plottable.numeric`.
      * Any type without an instance produces a compile error when used as a chart parameter, preventing accidental
      * plotting of unscalable types.
      *
      * Custom instances are built through the public factories `Plottable.continuous`, `Plottable.categorical`,
      * and `Plottable.temporal`. Each factory takes only standard function types, so custom instances do not
      * depend on any internal scale representation.
      *
      * `label` returns the tick or legend text for a value and is the only public method. The scale family and
      * domain projection are implementation details managed by the chart engine.
      */
    abstract class Plottable[A]:
        private[kyo] def kind: kyo.internal.Scale.Kind
        private[kyo] def toDomain(a: A): Maybe[kyo.internal.Domain]
        def label(a: A): String
    end Plottable

    /** Companion object with built-in `Plottable` instances and derivation utilities.
      *
      * All built-in instances are `val`s (never `inline`), so each is a single shared object; there is no per-call
      * duplication. Enum instances are produced by `derived` from the type's `Mirror.SumOf`.
      */
    object Plottable:

        import kyo.internal.Domain
        import kyo.internal.NumberFormat
        import kyo.internal.Scale

        given int: Plottable[Int] = continuous(_.toDouble, _.toString)

        given long: Plottable[Long] = continuous(_.toDouble, _.toString)

        given double: Plottable[Double] = continuous(identity, NumberFormat.double)

        given string: Plottable[String] = categorical(identity)

        // Internal fallback: plots any value categorically by its toString label. Used by the
        // color/group encoding bridge which stores the accessor erased to Any; the label string
        // is the display text and the CatKey identity is carried separately by ConcreteTag.
        private[kyo] val any: Plottable[Any] = categorical(_.toString)

        given instant: Plottable[Instant] = temporal(i => i.toJava.toEpochMilli, i => i.toJava.toString)

        /** `Plottable[Maybe[A]]` projects only `Present` values; `Absent` returns `Absent` so the extent-folding
          * layer skips it entirely.
          *
          * The `kind` is identical to the inner `Plottable[A]` kind: a `Maybe[Int]` parameter is still a Linear
          * encoding; a `Maybe[String]` parameter is still a Band encoding. An all-`Absent` column produces an empty
          * extent (no domain contributions at all).
          */
        given maybe[A](using inner: Plottable[A]): Plottable[Maybe[A]] =
            new Plottable[Maybe[A]]:
                private[kyo] def kind: Scale.Kind = inner.kind
                private[kyo] def toDomain(a: Maybe[A]): Maybe[Domain] = a match
                    case Present(v) => inner.toDomain(v)
                    case Absent     => Absent
                def label(a: Maybe[A]): String = a match
                    case Present(v) => inner.label(v)
                    case Absent     => ""

        /** Derives a linear `Plottable` for an opaque numeric quantity with an upper `<: Double` bound.
          *
          * The underlying `double` instance is reused directly; the cast is sound because the `<: Double` bound
          * guarantees that `A` is a `Double` at runtime (the opaque alias is erased).
          */
        def numeric[A <: Double]: Plottable[A] =
            // Unsafe: sound only because A <: Double guarantees the erased runtime type is Double.
            double.asInstanceOf[Plottable[A]]

        /** Derives a band `Plottable` for an enum from its `Mirror.SumOf`.
          *
          * The `inline given` surface reifies the literal label tuple (which must be done inline) and builds the
          * instance from the labels.
          */
        inline given derived[A](using m: scala.deriving.Mirror.SumOf[A]): Plottable[A] =
            val labelTuple = scala.compiletime.constValueTuple[m.MirroredElemLabels]
            deriveEnum[A](tupleToChunk(labelTuple))

        private def deriveEnum[A](labels: Chunk[String]): Plottable[A] =
            new Plottable[A]:
                private[kyo] def kind: Scale.Kind = Scale.Kind.Band
                private[kyo] def toDomain(a: A): Maybe[Domain] =
                    // Unsafe: sound because derivation is gated on Mirror.SumOf[A], guaranteeing A is a
                    // scala.reflect.Enum whose ordinal is in range of the mirrored element labels.
                    val idx = a.asInstanceOf[scala.reflect.Enum].ordinal
                    Present(Domain.Category(if idx >= 0 && idx < labels.size then labels(idx) else idx.toString))
                end toDomain
                def label(a: A): String =
                    // Unsafe: sound because derivation is gated on Mirror.SumOf[A], guaranteeing A is a
                    // scala.reflect.Enum whose ordinal is in range of the mirrored element labels.
                    val idx = a.asInstanceOf[scala.reflect.Enum].ordinal
                    if idx >= 0 && idx < labels.size then labels(idx) else idx.toString
                end label
            end new
        end deriveEnum

        private def tupleToChunk(t: Tuple): Chunk[String] =
            @scala.annotation.tailrec
            def loop(remaining: Tuple, acc: Chunk[String]): Chunk[String] =
                remaining match
                    case _: EmptyTuple => acc
                    case h *: tail     =>
                        // Unsafe: `t` is a `MirroredElemLabels` tuple whose elements are always singleton String
                        // literal types, so each head `h` is a String at runtime.
                        loop(tail, acc.append(h.asInstanceOf[String]))
            loop(t, Chunk.empty)
        end tupleToChunk

        /** Builds a `Plottable` instance for a type that maps to a continuous numeric scale.
          *
          * The resulting instance selects a linear scale. `value` extracts the numeric coordinate from an `A`
          * value; `label` produces the tick or legend text. Both are plain function types, so custom instances
          * do not depend on any internal representation.
          *
          * Typical use: define a `given` in the companion object of a custom numeric type, then pass values of
          * that type directly to the `x` or `y` parameter of any chart factory.
          *
          * Example:
          * {{{
          * opaque type Celsius <: Double = Double
          * object Celsius:
          *     given Plottable[Celsius] = Chart.Plottable.continuous(c => c, c => f"$c%.1f C")
          * }}}
          */
        def continuous[A](value: A => Double, label: A => String): Plottable[A] =
            val lbl = label
            new Plottable[A]:
                private[kyo] def kind: Scale.Kind              = Scale.Kind.Linear
                private[kyo] def toDomain(a: A): Maybe[Domain] = Present(Domain.Continuous(value(a)))
                def label(a: A): String                        = lbl(a)
            end new
        end continuous

        /** Builds a `Plottable` instance for a type that maps to a categorical band scale.
          *
          * The resulting instance selects a band (ordinal) scale. `label` produces the category key and the
          * tick text for each value. It is a plain function type, so custom instances do not depend on any
          * internal representation.
          *
          * Typical use: define a `given` for a custom identifier or code type and pass values of that type to
          * the `x` parameter of a bar or point chart.
          *
          * Example:
          * {{{
          * case class Sku(code: String)
          * given Plottable[Sku] = Chart.Plottable.categorical(_.code)
          * }}}
          */
        def categorical[A](label: A => String): Plottable[A] =
            val lbl = label
            new Plottable[A]:
                private[kyo] def kind: Scale.Kind              = Scale.Kind.Band
                private[kyo] def toDomain(a: A): Maybe[Domain] = Present(Domain.Category(lbl(a)))
                def label(a: A): String                        = lbl(a)
            end new
        end categorical

        /** Builds a `Plottable` instance for a type that maps to a time scale.
          *
          * The resulting instance selects a time scale. `epochMillis` extracts the Unix epoch millisecond
          * timestamp from an `A` value; `label` produces the tick or legend text. Both are plain function
          * types, so custom instances do not depend on any internal representation.
          *
          * Typical use: define a `given` for a custom timestamp type and pass values of that type to the `x`
          * parameter of a line or area chart.
          *
          * Example:
          * {{{
          * case class Stamp(millis: Long, iso: String)
          * given Plottable[Stamp] = Chart.Plottable.temporal(_.millis, _.iso)
          * }}}
          */
        def temporal[A](epochMillis: A => Long, label: A => String): Plottable[A] =
            val lbl = label
            new Plottable[A]:
                private[kyo] def kind: Scale.Kind              = Scale.Kind.Time
                private[kyo] def toDomain(a: A): Maybe[Domain] = Present(Domain.Temporal(epochMillis(a)))
                def label(a: A): String                        = lbl(a)
            end new
        end temporal

    end Plottable

    // ---- Internal ----

    /** Bundles a row accessor with the `Plottable` evidence for its value type.
      *
      * Captures the scale evidence and the `ConcreteTag` of the encoding value type
      * at the factory call site so the lowering phase does not need to re-derive
      * either. The `tag` is a stable, platform-independent type identity captured
      * from the static type `C`, used to key category values so the keying matches
      * across the JVM, Scala.js, and Native (a boxed runtime class would diverge).
      * `A` is the row type; `C` is the encoding value type. Both positional and
      * non-positional parameters use this carrier.
      */
    final private[kyo] case class Encoding[A, C](accessor: A => C, plottable: Plottable[C], tag: ConcreteTag[C])

    /** An encoding whose accessor may return `Maybe[C]`, supporting gap semantics.
      *
      * A `EncodingMaybe[A, C]` is built from an `A => C` total accessor (via
      * `EncodingMaybe.fromTotal`) or an `A => Maybe[C]` gap accessor (via
      * `EncodingMaybe.fromMaybe`). The lowering phase calls `accessor` and treats
      * `Absent` as a gap (breaks a line, drops a bar or point).
      *
      * `plottable` is the evidence for `C`, not for `Maybe[C]`, so the scale
      * resolution in lowering uses the inner type directly. `tag` is the stable
      * `ConcreteTag` of the inner type `C`, captured from the static type at
      * construction for cross-platform category keying.
      */
    final private[kyo] case class EncodingMaybe[A, C](accessor: A => Maybe[C], plottable: Plottable[C], tag: ConcreteTag[C])

    object EncodingMaybe:
        def fromTotal[A, C](f: A => C, pl: Plottable[C], tag: ConcreteTag[C]): EncodingMaybe[A, C] =
            EncodingMaybe(a => Present(f(a)), pl, tag)
        def fromMaybe[A, C](f: A => Maybe[C], pl: Plottable[C], tag: ConcreteTag[C]): EncodingMaybe[A, C] =
            EncodingMaybe(f, pl, tag)
    end EncodingMaybe

    /** Carries the grouping accessor and normalization flag for a stacked mark.
      *
      * A `Grouping[A]` is produced exclusively by `Chart.by(...)`, so there is no way to
      * express a stack without a grouping accessor. The two `by` forms are:
      *
      * ```scala
      * stack = Chart.by(_.region)                        // stack by region
      * stack = Chart.by(_.region, normalize = true)      // 100% stacked
      * ```
      *
      * The `normalize` flag is a named parameter, not a chained method, which
      * preserves row-type inference at the `by(...)` call site.
      *
      * `Grouping.none[A]` is the library-internal default for the `stack` parameter;
      * callers never construct it directly.
      */
    final private[kyo] case class Grouping[A](group: Maybe[A => Any], normalize: Boolean)

    object Grouping:
        private[kyo] def none[A]: Grouping[A] = Grouping(Absent, false)

    /** The data backing a chart: static (a `Chunk`) or live (a `Signal`).
      *
      * `Static` data lowers the whole tree once. `Live` data lowers the static frame
      * once and wraps the marks region in `signal.render` so marks redraw on each
      * emission while the frame, axes, and legend stay put.
      */
    private[kyo] enum DataSource[A]:
        case Static(rows: Chunk[A])
        case Live(rows: Signal[Chunk[A]])

    /** A unique sentinel object shared by every optional accessor parameter.
      *
      * The sentinel is compared by reference identity inside each factory. A parameter
      * that was not supplied by the caller is equal to `Unset.accessor` (same object);
      * a supplied parameter is any other function value. The factory body calls
      * `Unset.supplied` to distinguish the two cases and builds `Present` or `Absent`
      * accordingly. The `of` cast is sound because the sentinel is never invoked, only compared
      * by reference identity.
      */
    private[kyo] object Unset:
        val accessor: Any => Nothing           = _ => throw new NoSuchElementException("Unset sentinel invoked")
        inline def of[A, C]: A => C            = accessor.asInstanceOf[A => C]
        def supplied[A, C](f: A => C): Boolean = !(f.asInstanceOf[AnyRef] eq accessor)
    end Unset

    /** Tag for a positional (x/y/low/high) parameter value.
      *
      * Positional parameter values feed numeric or ordinal scales and are never
      * category-keyed, so their `ConcreteTag` is not used for identity. A positional
      * `Y` may also be a `Maybe[..]` gap type, which `ConcreteTag` cannot derive.
      * This returns the widest tag so the field is populated without constraining the
      * value type. Category keying (`CatKey`) only ever reads the color/group parameter
      * tag, which is always derived from a concrete type.
      */
    private[kyo] def positionalTag[C]: ConcreteTag[C] =
        // Unsafe: returns the widest tag for a positional value; the tag is never used for identity (positional
        // values feed numeric/ordinal scales only), so widening `ConcreteTag[Any]` to `ConcreteTag[C]` is sound.
        summon[ConcreteTag[Any]].asInstanceOf[ConcreteTag[C]]

    private def colorEncoding[A, C](color: A => C)(using c: ColorKey[C]): Encoding[A, Any] =
        // Unsafe: the color value is stored erased to Any and keyed downstream ONLY by CatKey
        // (ConcreteTag identity + label string), never reconstructed back to C. Capturing the real
        // ConcreteTag[C] from `c.identity` (not ConcreteTag[Any]) preserves full cross-platform
        // category discrimination; widening the accessor's element type to Any is sound because no
        // consumer reads the stored value at a C-typed position.
        Encoding[A, Any](color.asInstanceOf[A => Any], Plottable.any, c.identity.asInstanceOf[ConcreteTag[Any]])

    /** Evidence that a value type `C` can drive a chart's `color` (or stack `group`) encoding.
      *
      * A color/group value is keyed categorically by identity and compared for equality, never
      * scaled, so the evidence a chart needs from `C` is exactly: a stable cross-platform identity
      * key (so two enum cases that share a `toString` label stay distinct on JVM, JS, and Native)
      * and `CanEqual[C, C]` (so a typed legend `colorScale` can match keys by `==`). `ColorKey`
      * bundles both. Instances exist for every concrete non-generic type the chart can color by:
      * `Int`, `Long`, `Double`, `String`, `Instant`, and any enum that `derives CanEqual`. Under
      * the build's `-language:strictEquality`, an enum color/group type MUST `derives CanEqual`
      * (the built-in `Int`/`Long`/`Double`/`String`/`Instant` already satisfy it); omitting the
      * derive yields a compile error at the factory call site, by design. `C` infers from the
      * `color` accessor's return type, so call sites never write a type argument. When `color` is
      * omitted entirely, the `ColorKey[Nothing]` instance from `ColorKey.nothing` resolves
      * directly, covering the no-color case without any encoding being produced at runtime.
      */
    sealed abstract class ColorKey[C]:
        private[kyo] def equality: CanEqual[C, C]
        private[kyo] def identity: ConcreteTag[C]

    /** Companion for the `ColorKey` evidence.
      *
      * Two givens cover the full call-site surface. `derived` summons `ColorKey[C]` whenever both
      * `CanEqual[C, C]` and `ConcreteTag[C]` resolve for the concrete type `C` inferred from the
      * accessor. `nothing` covers the omitted-`color` case: when no `color` argument is supplied
      * the default `Unset.of[A, C]` leaves `C` unconstrained, and `ColorKey[Nothing]` is the most
      * specific match so the compiler resolves immediately without exploring other `CanEqual`
      * instances in scope. Generic `C` (e.g. `Maybe[Region]`) does not summon because `ConcreteTag`
      * rejects generics; this is the documented narrowing.
      */
    object ColorKey:
        given nothing: ColorKey[Nothing] =
            new ColorKey[Nothing]:
                private[kyo] def equality: CanEqual[Nothing, Nothing] = CanEqual.derived
                private[kyo] def identity: ConcreteTag[Nothing]       = summon[ConcreteTag[Nothing]]
        given derived[C](using ceq: CanEqual[C, C], tag: ConcreteTag[C]): ColorKey[C] =
            new ColorKey[C]:
                private[kyo] def equality: CanEqual[C, C] = ceq
                private[kyo] def identity: ConcreteTag[C] = tag
    end ColorKey

    /** A grouping directive for a stacked mark's `stack` parameter.
      *
      * Produced exclusively by `Chart.by(...)`; there is no other public constructor, so a stack
      * always carries a grouping accessor. Holds the type-erased group key accessor and the
      * normalize flag internally. Opaque so the erased internal carrier never appears on the
      * readable surface; the only public operations are constructing one with `Chart.by` and
      * passing it to a mark's `stack` parameter. `Stack.none` is the library-internal default for
      * an unstacked mark.
      */
    opaque type Stack[A] = Grouping[A]

    object Stack:
        private[kyo] def none[A]: Stack[A] = Grouping.none[A]
    end Stack

end Chart
