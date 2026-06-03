package kyo

import scala.language.implicitConversions

// ---- Sentinel for optional channel parameters ----

/** A unique sentinel object shared by every optional accessor parameter.
  *
  * The sentinel is compared by reference identity inside each factory. A parameter
  * that was not supplied by the caller is equal to `Unset.accessor` (same object);
  * a supplied parameter is any other function value. The factory body calls
  * `Unset.supplied` to distinguish the two cases and builds `Present` or `Absent`
  * accordingly.
  *
  * This is the one `asInstanceOf` idiom in the channel layer; it is safe because
  * the sentinel is never invoked, only compared by reference.
  */
private[kyo] object Unset:
    val accessor: Any => Nothing           = _ => throw new NoSuchElementException("Unset sentinel invoked")
    inline def of[A, C]: A => C            = accessor.asInstanceOf[A => C]
    def supplied[A, C](f: A => C): Boolean = !(f.asInstanceOf[AnyRef] eq accessor)
end Unset

// ---- Grouping carrier ----

/** Carries the grouping accessor and normalization flag for a stacked mark.
  *
  * A `Grouping[A]` is produced exclusively by `by(...)`, so there is no way to
  * express a stack without a grouping accessor. The two `by` forms are:
  *
  * {{{
  * stack = by(_.region)                        // stack by region
  * stack = by(_.region, normalize = true)      // 100% stacked
  * }}}
  *
  * The `normalize` flag is a named parameter, not a chained method, which
  * preserves row-type inference (the appendix-validated design constraint).
  *
  * `Grouping.none[A]` is the library-internal default for the `stack` parameter;
  * callers never construct it directly.
  */
final case class Grouping[A](group: Maybe[A => Any], normalize: Boolean)

object Grouping:
    private[kyo] def none[A]: Grouping[A] = Grouping(Absent, false)

/** Builds a `Grouping[A]` that groups by `group` and optionally normalizes to 100%.
  *
  * This is the only public constructor for `Grouping`. Pass the result to a mark's
  * `stack` parameter:
  *
  * {{{
  * bar(x = _.month, y = _.revenue, stack = by(_.region))
  * bar(x = _.month, y = _.revenue, stack = by(_.region, normalize = true))
  * }}}
  *
  * `normalize = true` produces a 100%-stacked bar; `false` (the default) stacks
  * absolute values.
  */
def by[A](group: A => Any, normalize: Boolean = false): Grouping[A] =
    Grouping(Present(group), normalize)

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
  * `basis` uses a B-spline; `catmullRom` uses a Catmull-Rom spline. The two
  * cases explicitly named in the public-API contract are `stepAfter` and
  * `monotone`; the rest are additive.
  */
enum Curve derives CanEqual:
    case linear, monotone, stepBefore, stepAfter, basis, catmullRom

/** Point glyph shape.
  *
  * Selects the shape rendered at each point in a `point` mark. `circle` is the
  * default and renders as an `Svg.circle`; the others render as `Svg.path`
  * glyphs of equal visual weight. All five cases are additive beyond the two the
  * contract names.
  */
enum Symbol derives CanEqual:
    case circle, square, triangle, diamond, cross

/** Text alignment for `text` marks and rotated axis tick labels. */
enum TextAnchor derives CanEqual:
    case Start, Middle, End

// ---- Named-parameter factories ----

/** Creates a bar/column mark.
  *
  * `x` and `y` are required positional channels; `color` groups the bars and
  * derives a legend; `stack` stacks or normalizes the bars (carrying its own
  * grouping via `by(...)`); `axis` selects the y-axis (default `Axis.Left`).
  *
  * Channels are named parameters, never chained setters. Supplying `color` is
  * what turns a plain bar chart into a grouped one. The omitted optionals are
  * `Absent` inside the resulting `Mark.Bar`; the supplied ones are `Present`.
  *
  * `bar` has no `size` parameter. `rule` has no `color` parameter. These
  * omissions are intentional capability gates: the compiler enforces them.
  */
def bar[A, X: Plottable, Y: Plottable](
    x: A => X,
    y: A => Y,
    color: A => Any = Unset.of[A, Any],
    stack: Grouping[A] = Grouping.none[A],
    axis: Axis = Axis.Left
)(using Frame): Mark[A] =
    val xCh = Channel[A, X](x, summon[Plottable[X]])
    val yCh = Channel[A, Y](y, summon[Plottable[Y]])
    val colorMaybe: Maybe[Channel[A, ?]] =
        if Unset.supplied(color) then Present(Channel[A, Any](color, Plottable.string.asInstanceOf[Plottable[Any]]))
        else Absent
    Mark.Bar(xCh, yCh, colorMaybe, stack, Absent, Absent, Absent, axis)
end bar

/** Creates a line mark.
  *
  * `x` and `y` are required; `color` splits into one line per series; `curve`
  * selects the interpolation strategy (default `Curve.linear`); `defined`
  * overrides gap detection (a row where `defined` returns `false` breaks the
  * line); `axis` selects the y-axis. A `y` accessor that returns `Maybe[Y]`
  * type-checks because `Plottable[Maybe[Y]]` is derived from `Plottable[Y]`.
  */
def line[A, X: Plottable, Y: Plottable](
    x: A => X,
    y: A => Y,
    color: A => Any = Unset.of[A, Any],
    curve: Curve = Curve.linear,
    defined: A => Boolean = Unset.of[A, Boolean],
    axis: Axis = Axis.Left
)(using Frame): Mark[A] =
    val xCh = Channel[A, X](x, summon[Plottable[X]])
    val yCh = ChannelMaybe.fromTotal[A, Y](y, summon[Plottable[Y]])
    val colorMaybe: Maybe[Channel[A, ?]] =
        if Unset.supplied(color) then Present(Channel[A, Any](color, Plottable.string.asInstanceOf[Plottable[Any]]))
        else Absent
    val definedMaybe: Maybe[A => Boolean] =
        if Unset.supplied(defined) then Present(defined) else Absent
    Mark.Line(xCh, yCh, colorMaybe, curve, definedMaybe, Absent, Absent, Absent, axis)
end line

/** Creates an area mark.
  *
  * Two forms are accepted: (1) a single `y` accessor fills the area between the
  * y value and the baseline; (2) `y0` and `y1` fill the band between two values.
  * At lowering time exactly one of {`y`} or {`y0`,`y1`} must be present; a
  * misconfiguration renders an empty frame (never a crash, per contract section
  * 10). `color`, `stack`, `curve`, and `axis` follow the same rules as `bar` and
  * `line`.
  */
def area[A, X: Plottable, Y: Plottable](
    x: A => X,
    y: A => Y = Unset.of[A, Y],
    y0: A => Y = Unset.of[A, Y],
    y1: A => Y = Unset.of[A, Y],
    color: A => Any = Unset.of[A, Any],
    stack: Grouping[A] = Grouping.none[A],
    curve: Curve = Curve.linear,
    axis: Axis = Axis.Left
)(using Frame): Mark[A] =
    val xCh                           = Channel[A, X](x, summon[Plottable[X]])
    val plY                           = summon[Plottable[Y]]
    val yMaybe                        = if Unset.supplied(y) then Present(ChannelMaybe.fromTotal[A, Y](y, plY)) else Absent
    val y0Maybe: Maybe[Channel[A, Y]] = if Unset.supplied(y0) then Present(Channel[A, Y](y0, plY)) else Absent
    val y1Maybe: Maybe[Channel[A, Y]] = if Unset.supplied(y1) then Present(Channel[A, Y](y1, plY)) else Absent
    val colorMaybe: Maybe[Channel[A, ?]] =
        if Unset.supplied(color) then Present(Channel[A, Any](color, Plottable.string.asInstanceOf[Plottable[Any]]))
        else Absent
    Mark.Area(xCh, yMaybe, y0Maybe, y1Maybe, colorMaybe, stack, curve, Absent, Absent, Absent, axis)
end area

/** Creates a point (scatter/bubble) mark.
  *
  * `x` and `y` are required; `color`, `size`, and `symbol` are optional
  * non-positional channels. `bar` has no `size` parameter; `point` does. This
  * asymmetry is the capability gate enforced by the named-parameter design.
  * A `y` accessor returning `Maybe[Y]` renders a gap (no dot) at `Absent` rows.
  */
def point[A, X: Plottable, Y: Plottable](
    x: A => X,
    y: A => Y,
    color: A => Any = Unset.of[A, Any],
    size: A => Double = Unset.of[A, Double],
    symbol: A => Symbol = Unset.of[A, Symbol],
    axis: Axis = Axis.Left
)(using Frame): Mark[A] =
    val xCh = Channel[A, X](x, summon[Plottable[X]])
    val yCh = ChannelMaybe.fromTotal[A, Y](y, summon[Plottable[Y]])
    val colorMaybe: Maybe[Channel[A, ?]] =
        if Unset.supplied(color) then Present(Channel[A, Any](color, Plottable.string.asInstanceOf[Plottable[Any]]))
        else Absent
    val sizeMaybe: Maybe[A => Double]   = if Unset.supplied(size) then Present(size) else Absent
    val symbolMaybe: Maybe[A => Symbol] = if Unset.supplied(symbol) then Present(symbol) else Absent
    Mark.Point(xCh, yCh, colorMaybe, sizeMaybe, Absent, symbolMaybe, Absent, Absent, Absent, axis)
end point

/** Creates a reference line mark.
  *
  * A `rule` draws a horizontal (`y`) or vertical (`x`) line spanning the plot at
  * a constant value or at a `Signal`-tracked value. At least one of `x` or `y`
  * must be supplied. `rule` has no `color` or `size` parameter; these are
  * intentional omissions gating capability at the call site.
  *
  * Constant form: `rule(y = Usd(1000))`. Signal form: `rule(y = threshold)`
  * where `threshold: Signal[Usd]`.
  */
def rule[A, C: Plottable](
    x: RuleValue[C] = RuleValue.unset[C],
    y: RuleValue[C] = RuleValue.unset[C],
    axis: Axis = Axis.Left
)(using Frame): Mark[A] =
    // Pattern matching on RuleValue.Unset across type parameters requires an erased check.
    // isInstanceOf[RuleValue.Unset.type] detects the singleton; the value arm carries the concrete case.
    val xMaybe: Maybe[RuleValue[?]] = if x.isInstanceOf[RuleValue.Unset.type] then Absent else Present(x)
    val yMaybe: Maybe[RuleValue[?]] = if y.isInstanceOf[RuleValue.Unset.type] then Absent else Present(y)
    Mark.Rule(xMaybe, yMaybe, axis)
end rule

// ---- Chart entry points ----

/** Entry point for building a chart.
  *
  * `Chart(data)` opens a chart over a `Chunk[A]` or `Signal[Chunk[A]]`, fixing
  * the row type `A`. The second application `(marks*)` supplies the marks and
  * returns a `ChartSpec[A]`. Configuration methods (`xAxis`, `yAxis`, `size`,
  * etc.) are defined on `ChartSpec[A]` and return a copy with one field changed.
  *
  * The two-stage `apply` is what makes row-type inference work: `A` is bound by
  * the first application before the mark channel lambdas are read, so
  * `bar(x = _.month, y = _.revenue)` does not need annotations.
  *
  * A `ChartSpec[A]` converts to `Svg.Root` automatically wherever an `Svg.Root`
  * is expected (including `UI.div` children), via the `given Conversion`.
  */
object Chart:

    /** Opens a chart over a static `Chunk[A]`. */
    def apply[A](data: Chunk[A])(using Frame): Builder[A] =
        new Builder[A](DataSource.Static(data))

    /** Opens a chart over a live `Signal[Chunk[A]]`; the marks region redraws on each emission. */
    def apply[A](data: Signal[Chunk[A]])(using CanEqual[Chunk[A], Chunk[A]], Frame): Builder[A] =
        new Builder[A](DataSource.Live(data))

    /** Holds the data source and accepts the mark list.
      *
      * The second application `(marks*)` is where the `ChartSpec[A]` is built.
      * Keeping this as a two-step `apply` ensures that `A` is bound to the data
      * element type before the mark channel lambdas are read, which is the
      * inference boundary the named-parameter design relies on.
      */
    final class Builder[A] private[kyo] (data: DataSource[A]):
        def apply(marks: Mark[A]*)(using Frame): ChartSpec[A] =
            ChartSpec[A](
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
                animateCfg = AnimateConfig.default,
                key = Absent,
                onHover = Absent,
                onSelect = Absent,
                tooltip = Absent
            )
    end Builder

end Chart

// ---- ChartSpec and config extension methods ----

/** The fully-configured, not-yet-lowered chart intermediate representation.
  *
  * Built by `Chart(data)(marks*)` and then refined by the configuration methods.
  * Every config method returns a copy with one field changed; the whole chain is
  * pure and allocation-light (case class copy).
  *
  * `ChartSpec[A]` converts to `Svg.Root` automatically via the `given Conversion`
  * below; that conversion is the only path to a rendered chart. Until it is
  * invoked the spec is just a value you can inspect and modify.
  *
  * Fields: `data` is either `DataSource.Static` (a `Chunk`) or `DataSource.Live`
  * (a `Signal`). `marks` is the ordered list of mark layers. The `*Cfg` fields
  * hold the configuration built by the builder lambdas. `key` / `onHover` /
  * `onSelect` / `tooltip` drive reactivity and interaction (Phases 05-07).
  */
final case class ChartSpec[A](
    data: DataSource[A],
    marks: Chunk[Mark[A]],
    chartSize: (Int, Int),
    xAxisCfg: AxisConfig,
    yAxisCfg: AxisConfig,
    yAxisRightCfg: Maybe[AxisConfig],
    legendCfg: LegendConfig,
    theme: Theme,
    xScaleOverride: Maybe[ScaleOverride],
    yScaleOverride: Maybe[ScaleOverride],
    animateCfg: AnimateConfig,
    key: Maybe[A => String],
    onHover: Maybe[Signal.SignalRef[Maybe[A]]],
    onSelect: Maybe[Signal.SignalRef[Maybe[A]]],
    tooltip: Maybe[A => String]
)

extension [A](spec: ChartSpec[A])

    /** Configures the x-axis using a builder lambda. */
    def xAxis(f: AxisConfig => AxisConfig): ChartSpec[A] =
        spec.copy(xAxisCfg = f(spec.xAxisCfg))

    /** Configures the left y-axis using a builder lambda. */
    def yAxis(f: AxisConfig => AxisConfig): ChartSpec[A] =
        spec.copy(yAxisCfg = f(spec.yAxisCfg))

    /** Configures the right y-axis using a builder lambda. Creates it if absent. */
    def yAxisRight(f: AxisConfig => AxisConfig): ChartSpec[A] =
        val base = spec.yAxisRightCfg.getOrElse(AxisConfig.default)
        spec.copy(yAxisRightCfg = Present(f(base)))

    /** Configures the legend using a builder lambda. */
    def legend(f: LegendConfig => LegendConfig): ChartSpec[A] =
        spec.copy(legendCfg = f(spec.legendCfg))

    /** Configures the theme using a builder lambda. */
    def theme(f: Theme => Theme): ChartSpec[A] =
        spec.copy(theme = f(spec.theme))

    /** Sets the chart width and height in user units. */
    def size(w: Int, h: Int): ChartSpec[A] =
        spec.copy(chartSize = (w, h))

    /** Overrides the x-axis scale using a builder lambda. */
    def xScale(f: ScaleOverride => ScaleOverride): ChartSpec[A] =
        spec.copy(xScaleOverride = Present(f(ScaleOverride.default)))

    /** Overrides the y-axis scale using a builder lambda. */
    def yScale(f: ScaleOverride => ScaleOverride): ChartSpec[A] =
        spec.copy(yScaleOverride = Present(f(ScaleOverride.default)))

    /** Configures animation using a builder lambda. */
    def animate(f: AnimateConfig => AnimateConfig): ChartSpec[A] =
        spec.copy(animateCfg = f(spec.animateCfg))

    /** Sets the key extractor used for keyed transitions. */
    def key(f: A => String): ChartSpec[A] =
        spec.copy(key = Present(f))

    /** Publishes the hovered datum to `ref` on pointer enter/leave. */
    def onHover(ref: Signal.SignalRef[Maybe[A]]): ChartSpec[A] =
        spec.copy(onHover = Present(ref))

    /** Publishes the selected datum to `ref` on click. */
    def onSelect(ref: Signal.SignalRef[Maybe[A]]): ChartSpec[A] =
        spec.copy(onSelect = Present(ref))

    /** Attaches a default tooltip using `f` to format the hovered datum. */
    def tooltip(f: A => String): ChartSpec[A] =
        spec.copy(tooltip = Present(f))

    /** Lowers this chart spec to an `Svg.Root`.
      *
      * Equivalent to `summon[Conversion[ChartSpec[A], Svg.Root]](spec)`. Provided as an explicit
      * method for callers that do not have `scala.language.implicitConversions` in scope.
      */
    def toSvg: Svg.Root = internal.ChartLower.lower(spec)

end extension

/** Converts a `ChartSpec[A]` to an `Svg.Root` wherever one is expected.
  *
  * Delegates to `ChartLower.lower`. The lowering uses `Frame.internal` for SVG node construction,
  * so the conversion itself requires no frame synthesis (carry-over N4 from Phase 02 verify).
  */
given [A]: Conversion[ChartSpec[A], Svg.Root] = spec => internal.ChartLower.lower(spec)

// ---- Mark hierarchy ----

/** A single visual layer over the chart's row type `A`.
  *
  * Sealed: the seven cases (`Bar`, `Line`, `Area`, `Point`, `Rule`, `Text`, `ErrorBar`)
  * are the complete mark vocabulary. All lowering in `internal/ChartLower.scala` is an
  * exhaustive match on this sealed trait; adding a new case is a compile error until
  * lowering is extended. Marks are pure immutable values; they carry no rendering logic.
  *
  * Marks are produced by the top-level factories `bar`/`line`/`area`/`point`/`rule`
  * and consumed by `Chart(data)(marks*)`. Users never name the concrete cases
  * directly; the factories are the public API.
  */
sealed trait Mark[A]

object Mark:

    /** A bar or column mark.
      *
      * Carries required positional channels `x` and `y`, and optional grouping
      * channels `color` and `stack`. `axis` selects the y-axis. The additive
      * fields `opacity`, `label`, and `tooltip` default `Absent`; behavior lands
      * in Phase 3/4.
      */
    final case class Bar[A, X, Y](
        x: Channel[A, X],
        y: Channel[A, Y],
        color: Maybe[Channel[A, ?]],
        stack: Grouping[A],
        opacity: Maybe[A => Double] = Absent, // D4
        label: Maybe[A => String] = Absent,   // D5
        tooltip: Maybe[A => String] = Absent, // D6
        axis: Axis
    ) extends Mark[A]

    /** A line mark with optional gap support.
      *
      * `y` is a `ChannelMaybe` to support `Maybe[Y]` accessors (gaps). `color`
      * splits into one line per series. `defined` overrides gap detection. The
      * additive fields `opacity`, `label`, and `tooltip` default `Absent`; behavior
      * lands in Phase 3/4.
      */
    final case class Line[A, X, Y](
        x: Channel[A, X],
        y: ChannelMaybe[A, Y],
        color: Maybe[Channel[A, ?]],
        curve: Curve,
        defined: Maybe[A => Boolean],
        opacity: Maybe[A => Double] = Absent, // D4
        label: Maybe[A => String] = Absent,   // D5
        tooltip: Maybe[A => String] = Absent, // D6
        axis: Axis
    ) extends Mark[A]

    /** An area mark.
      *
      * Either `y` (fill to baseline) or the `y0`/`y1` band form must be supplied.
      * Exactly one of the two forms is valid; a lowering-time check selects it. The
      * additive fields `opacity`, `label`, and `tooltip` default `Absent`; behavior
      * lands in Phase 3/4.
      */
    final case class Area[A, X, Y](
        x: Channel[A, X],
        y: Maybe[ChannelMaybe[A, Y]],
        y0: Maybe[Channel[A, Y]],
        y1: Maybe[Channel[A, Y]],
        color: Maybe[Channel[A, ?]],
        stack: Grouping[A],
        curve: Curve,
        opacity: Maybe[A => Double] = Absent, // D4
        label: Maybe[A => String] = Absent,   // D5
        tooltip: Maybe[A => String] = Absent, // D6
        axis: Axis
    ) extends Mark[A]

    /** A point (scatter/bubble) mark.
      *
      * `y` is a `ChannelMaybe` so `Maybe[Y]` accessors render gaps as absent dots.
      * `size` controls the dot radius (D7: meaning becomes sqrt-area magnitude in
      * Phase 3); `sizePx` is the raw-pixel-radius escape hatch; `symbol` selects
      * the glyph. The additive fields `opacity`, `label`, and `tooltip` default
      * `Absent`; behavior lands in Phase 3/4.
      */
    final case class Point[A, X, Y](
        x: Channel[A, X],
        y: ChannelMaybe[A, Y],
        color: Maybe[Channel[A, ?]],
        size: Maybe[A => Double],
        sizePx: Maybe[A => Double] = Absent, // D7 escape hatch: raw pixel radius
        symbol: Maybe[A => Symbol],
        opacity: Maybe[A => Double] = Absent, // D4
        label: Maybe[A => String] = Absent,   // D5
        tooltip: Maybe[A => String] = Absent, // D6
        axis: Axis
    ) extends Mark[A]

    /** A reference line mark.
      *
      * `x` or `y` carries a `RuleValue`: a constant (`Const`) or a reactive
      * signal (`Reactive`). At least one of `x`/`y` should be `Present`. `rule`
      * has no `color` or `size` channel.
      */
    final case class Rule[A](
        x: Maybe[RuleValue[?]],
        y: Maybe[RuleValue[?]],
        axis: Axis
    ) extends Mark[A]

    /** A text annotation mark.
      *
      * Renders one `Svg.text` per row at `(x, y)` with the string produced by
      * `label`. `y` is a `ChannelMaybe` so gap rows produce no text. `color`
      * optionally groups by category; `anchor` controls horizontal alignment;
      * `opacity` controls per-datum transparency. Full lowering lands in Phase 4.
      */
    final case class Text[A, X, Y](
        x: Channel[A, X],
        y: ChannelMaybe[A, Y],
        label: A => String,
        color: Maybe[Channel[A, ?]],
        anchor: TextAnchor,
        opacity: Maybe[A => Double],
        axis: Axis
    ) extends Mark[A]

    /** An error bar mark.
      *
      * Renders a vertical line from `low` to `high` at `x`, with horizontal
      * caps of `capWidth` pixels, and a center marker at `y`. All three y-channels
      * fold into the y-extent. `color` optionally groups by category. Full
      * lowering lands in Phase 4.
      */
    final case class ErrorBar[A, X, Y](
        x: Channel[A, X],
        y: Channel[A, Y],
        low: Channel[A, Y],
        high: Channel[A, Y],
        color: Maybe[Channel[A, ?]],
        capWidth: Double,
        axis: Axis
    ) extends Mark[A]

end Mark

// ---- Channel carriers ----

/** Bundles a row accessor with the `Plottable` evidence for its value type.
  *
  * Captures the scale evidence at the factory call site so the lowering phase
  * does not need to re-derive it. `A` is the row type; `C` is the channel value
  * type. Both positional and non-positional channels use this carrier.
  */
final case class Channel[A, C](accessor: A => C, plottable: Plottable[C])

/** A channel whose accessor may return `Maybe[C]`, supporting gap semantics.
  *
  * A `ChannelMaybe[A, C]` is built from an `A => C` total accessor (via
  * `ChannelMaybe.fromTotal`) or an `A => Maybe[C]` gap accessor (via
  * `ChannelMaybe.fromMaybe`). The lowering phase calls `accessor` and treats
  * `Absent` as a gap (breaks a line, drops a bar or point).
  *
  * `plottable` is the evidence for `C`, not for `Maybe[C]`, so the scale
  * resolution in lowering uses the inner type directly.
  */
final case class ChannelMaybe[A, C](accessor: A => Maybe[C], plottable: Plottable[C])

object ChannelMaybe:
    def fromTotal[A, C](f: A => C, pl: Plottable[C]): ChannelMaybe[A, C] =
        ChannelMaybe(a => Present(f(a)), pl)
    def fromMaybe[A, C](f: A => Maybe[C], pl: Plottable[C]): ChannelMaybe[A, C] =
        ChannelMaybe(f, pl)
end ChannelMaybe

/** The constant-or-signal carrier for `rule(x = ...)` / `rule(y = ...)`.
  *
  * A rule position is either a constant value (`Const`) or a `Signal`-tracked
  * value (`Reactive`). Neither form carries a row accessor; a rule reads no row.
  * The lowering phase resolves `Const` once and `Reactive` through
  * `signal.render`.
  */
enum RuleValue[C]:
    case Const(value: C, plottable: Plottable[C])
    case Reactive(signal: Signal[C], plottable: Plottable[C])
    case Unset extends RuleValue[Nothing] // D33: total absence sentinel replacing null
end RuleValue

/** Implicit conversions from plain values and signals to `RuleValue`.
  *
  * These allow the inline `rule(y = Usd(1000))` and `rule(y = threshold)` forms
  * to compile without an explicit `RuleValue.Const(...)` wrapper.
  */
object RuleValue:
    // Unsafe: widening Nothing to C is safe because Unset is a total sentinel that is never invoked;
    // the cast is the canonical idiom for a covariant singleton sentinel in a non-covariant enum.
    private[kyo] def unset[C]: RuleValue[C] = RuleValue.Unset.asInstanceOf[RuleValue[C]]
    given constConversion[C: Plottable]: Conversion[C, RuleValue[C]] =
        c => RuleValue.Const(c, summon[Plottable[C]])
    given signalConversion[C: Plottable](using CanEqual[C, C]): Conversion[Signal[C], RuleValue[C]] =
        s => RuleValue.Reactive(s, summon[Plottable[C]])
end RuleValue

// ---- DataSource carrier ----

/** The data backing a chart: static (a `Chunk`) or live (a `Signal`).
  *
  * `Static` data lowers the whole tree once. `Live` data lowers the static frame
  * once and wraps the marks region in `signal.render` so marks redraw on each
  * emission while the frame, axes, and legend stay put.
  */
private[kyo] enum DataSource[A]:
    case Static(rows: Chunk[A])
    case Live(rows: Signal[Chunk[A]])

// ---- Config types ----

/** Configures axis appearance for one axis.
  *
  * Builder methods return a copy with one field changed, so chains compose
  * without mutation. Used as the argument to `.xAxis(f)` / `.yAxis(f)` /
  * `.yAxisRight(f)`: write `_.left.grid.ticks(5).format(...)`.
  *
  * `side` selects where the axis line and labels are drawn. `axisLabel` is an
  * optional axis label string. `showGrid` enables gridlines across the plot.
  * `tickCount` is the desired number of ticks (a hint, not a hard limit).
  * `tickFormat` overrides the default tick label formatter.
  */
final case class AxisConfig(
    side: Maybe[Side],
    axisLabel: Maybe[String],
    showGrid: Boolean,
    tickCount: Int,
    tickFormat: Maybe[Double => String],
    tickRotation: Double = 0.0,                 // D17 (rendered Phase 6)
    tickAnchor: TextAnchor = TextAnchor.Middle, // D17 (rendered Phase 6)
    reversed: Boolean = false,                  // D20
    padding: Double = 0.0,                      // D21
    labelAllBands: Boolean = true               // D18
):
    def left: AxisConfig                        = copy(side = Present(Side.Left))
    def right: AxisConfig                       = copy(side = Present(Side.Right))
    def bottom: AxisConfig                      = copy(side = Present(Side.Bottom))
    def top: AxisConfig                         = copy(side = Present(Side.Top))
    def label(s: String): AxisConfig            = copy(axisLabel = Present(s))
    def grid: AxisConfig                        = copy(showGrid = true)
    def ticks(n: Int): AxisConfig               = copy(tickCount = n)
    def format(f: Double => String): AxisConfig = copy(tickFormat = Present(f))
    def reverse: AxisConfig                     = copy(reversed = true)
    def pad(fraction: Double): AxisConfig       = copy(padding = fraction)

end AxisConfig

object AxisConfig:
    val default: AxisConfig = AxisConfig(Absent, Absent, false, 5, Absent)

/** The side on which an axis is drawn. Used inside `AxisConfig`. */
enum Side derives CanEqual:
    case Left, Right, Top, Bottom

/** Configures legend appearance and position.
  *
  * Builder methods return a copy with one field changed. Used as the argument to
  * `.legend(f)`: write `_.top` or `_.hidden`. The `colorScale` methods attach an
  * explicit color mapping for the mark's color channel.
  *
  * `position` selects where the legend box is placed relative to the plot.
  * `isHidden` hides the legend entirely. `colorScaleFn` is an optional function
  * from the raw color-channel value (as `Any`) to `Style.Color`; if `Absent` the
  * default palette is used. Using `Any` rather than `String` allows typed enum
  * functions to be stored directly, avoiding a label-to-enum roundtrip.
  */
final case class LegendConfig(
    position: Maybe[LegendPosition],
    isHidden: Boolean,
    colorScaleFn: Maybe[Any => Style.Color]
):
    def top: LegendConfig    = copy(position = Present(LegendPosition.Top))
    def bottom: LegendConfig = copy(position = Present(LegendPosition.Bottom))
    def left: LegendConfig   = copy(position = Present(LegendPosition.Left))
    def right: LegendConfig  = copy(position = Present(LegendPosition.Right))
    def hidden: LegendConfig = copy(isHidden = true)

    /** Attaches a total color-scale function keyed on the category label string.
      *
      * `f` must be exhaustive over the category labels that the color channel produces. For a `String`-keyed
      * color channel the labels are the raw string values. For an enum color channel the labels are the enum
      * case names (e.g. `"NA"`, `"EU"`, `"APAC"`).
      */
    def colorScale(f: String => Style.Color): LegendConfig =
        copy(colorScaleFn = Present(v => f(v.toString)))

    /** Attaches a total typed color-scale function. The compiler checks exhaustiveness when `f` is a match
      * expression over a sealed enum type `K`, so a missing case is flagged at compile time.
      *
      * The Scala call name is `colorScale[K]`. The JVM bytecode symbol is `colorScaleTyped` (set via
      * `@targetName`) to avoid an erasure conflict with the `String => Style.Color` overload.
      *
      * Example:
      * {{{
      * .legend(_.colorScale[Region] {
      *   case Region.NA   => Style.Color.blue
      *   case Region.EU   => Style.Color.green
      *   case Region.APAC => Style.Color.orange
      * })
      * }}}
      *
      * At runtime `f` is applied directly to the raw color-channel value (which is a `K`), so no
      * label-to-K roundtrip is needed. Exhaustiveness is checked by the compiler at the call site.
      */
    @scala.annotation.targetName("colorScaleTyped")
    def colorScale[K](f: K => Style.Color): LegendConfig =
        // Unsafe: the cast Any => K is safe because the color channel accessor returns K values
        // and the legend builder passes those raw values to this function. The Plottable.string
        // stand-in means K = String in the channel, but for enum channels the raw value is the
        // actual enum case. The cast is safe as long as the caller's K matches the channel's
        // accessor return type, which the named-parameter API enforces.
        copy(colorScaleFn = Present(v => f(v.asInstanceOf[K])))

    /** Attaches a `Map[K, Style.Color]` color scale with a `fallback` color for unmapped categories.
      *
      * Useful when you want to map only some categories to specific colors and leave the rest to the
      * fallback. Does not provide compile-time exhaustiveness checking; use the typed `colorScale[K]`
      * overload for that.
      */
    def colorScaleMap[K](map: Map[K, Style.Color], fallback: Style.Color = Style.Color.gray): LegendConfig =
        copy(colorScaleFn = Present(v => map.getOrElse(v.asInstanceOf[K], fallback)))

end LegendConfig

object LegendConfig:
    val default: LegendConfig = LegendConfig(Absent, false, Absent)

/** Position of a legend relative to the plot area. */
enum LegendPosition derives CanEqual:
    case Top, Bottom, Left, Right

/** Configures chart theme.
  *
  * Selects the overall color scheme (light or dark) and optionally overrides the
  * palette. Builder methods return a copy. Used as the argument to `.theme(f)`:
  * write `_.light` or `_.dark`.
  */
final case class Theme(
    isDark: Boolean,
    palette: Maybe[Chunk[Style.Color]]
):
    def light: Theme = copy(isDark = false)
    def dark: Theme  = copy(isDark = true)

end Theme

object Theme:
    val default: Theme = Theme(false, Absent)

/** Configures animation for live charts.
  *
  * `ease(d)` enables one-shot SMIL transitions with duration `d`. `none` disables all transitions. The easing
  * function is fixed to ease-in-out-cubic (the demo's pattern); named easing variants are additive extensions.
  * `morphSteps` is reserved for a future effectful chart-mount API that will drive a bounded stepped
  * path-morph interpolation for line/area marks whose command structure changes between updates. The
  * current pure `Svg.Root` lowering animates same-structure path morphs with declarative SMIL (an
  * `animate` on the `d` attribute) and snaps structural changes; `morphSteps` is not consulted by it.
  * Used as the argument to `.animate(f)`: write `_.ease(300.millis)` or `_.none`.
  */
final case class AnimateConfig(
    enabled: Boolean,
    duration: Duration,
    morphSteps: Int
):
    def ease(d: Duration): AnimateConfig = copy(enabled = true, duration = d)
    def none: AnimateConfig              = copy(enabled = false)

end AnimateConfig

object AnimateConfig:
    val default: AnimateConfig =
        AnimateConfig(true, Duration.fromJava(java.time.Duration.ofMillis(300)), morphSteps = 24)

/** Overrides the automatically-inferred scale for an axis.
  *
  * Builder methods return a copy with the override set. Used as the argument to `.xScale(f)` or `.yScale(f)`:
  * write `_.band`, `_.linear(0, 5000)`, or `_.log`. An unset override (the default) leaves the scale inferred
  * from the accessor's `Plottable` kind and the data extent.
  *
  * `nice`, `clamp`, and `pad` are additive knobs that refine the fitted domain. `nice=true` (default) snaps
  * domain bounds to round values; `clamp=false` (default) allows extrapolation beyond the domain. `pad` widens
  * the domain symmetrically by the given fraction before fitting (INV-007, Q-003).
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
    def ordinal: ScaleOverride                        = copy(kind = Present(ScaleKind.Ordinal))
    def point: ScaleOverride                          = copy(kind = Present(ScaleKind.Point))
    def symlog: ScaleOverride                         = copy(kind = Present(ScaleKind.Symlog))
    def withNice(on: Boolean): ScaleOverride          = copy(nice = on)
    def noNice: ScaleOverride                         = copy(nice = false)
    def withClamp(on: Boolean): ScaleOverride         = copy(clamp = on)
    def withPad(fraction: Double): ScaleOverride      = copy(pad = fraction)

end ScaleOverride

object ScaleOverride:
    val default: ScaleOverride = ScaleOverride(Absent)

/** The scale kind selected by a `ScaleOverride`. */
enum ScaleKind derives CanEqual:
    case Band
    case Log
    case Linear(lo: Double, hi: Double)
    case Time    // D11
    case Ordinal // D11
    case Point   // D11
    case Symlog  // D11
end ScaleKind
