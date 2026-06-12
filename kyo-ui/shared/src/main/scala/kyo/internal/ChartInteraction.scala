package kyo.internal

import kyo.*
import kyo.Chart.*
import kyo.Chart.Encoding
import kyo.UI.*
import kyo.UI.Ast.*

/** Tooltip, hover, and brush interaction wiring for chart marks.
  *
  * Provides `Highlight` (the built-in hover/select visual feedback carrier), `buildInteractionAttrs`
  * (attaches hover and click handlers to per-row SVG shapes), `resolveHighlight` (derives the active
  * `Highlight` from the chart spec's interaction configuration), `applyHighlightStyle` (maps a `Style`
  * onto SVG attributes for the active mark), `withHighlight` (wraps row-tagged shapes in a reactive
  * highlight region), and `buildTooltipOverlay` (builds the floating tooltip `Reactive[Svg.G]` driven
  * by the chart's internal hover ref).
  */
private[kyo] object ChartInteraction:

    import ChartLayout.Layout

    /** Built-in highlight: the active mark's visual feedback driven by the user's hover/select ref.
      *
      * `ref` is the SAME `SignalRef[Maybe[A]]` the caller passed to `onHover`/`onSelect`; there is no internal
      * interaction cell. `style` is the optional caller-supplied `Style`; when `Absent` the default highlight
      * (a dark stroke outline) is applied to the active mark. The active mark is the one whose source row equals
      * the current value the ref holds; every other mark renders unchanged.
      */
    final private[kyo] case class Highlight[A](ref: Signal.SignalRef[Maybe[A]], style: Maybe[Style])

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
    private[kyo] def buildInteractionAttrs[A](
        row: A,
        spec: Chart[A],
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
                    val base: Any < Async = (): Any < Async
                    val withHover: Any < Async =
                        spec.onHover.fold(base)(ref => base.andThen(ref.set(Present(row))))
                    val combined: Any < Async =
                        internalHoverRef.fold(withHover)(ref => withHover.andThen(ref.set(Present(row))))
                    Present(combined)
                else Absent
            // Unhover action: clear user ref + internal tooltip ref, combined sequentially.
            val unhoverAction: Maybe[Any < Async] =
                if hasHover || hasTooltip then
                    val base: Any < Async = (): Any < Async
                    val withHover: Any < Async =
                        spec.onHover.fold(base)(ref => base.andThen(ref.set(Absent)))
                    val combined: Any < Async =
                        internalHoverRef.fold(withHover)(ref => withHover.andThen(ref.set(Absent)))
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

    /** Resolve the built-in highlight for a chart, if configured.
      *
      * `selectHighlight` (driven by `onSelect`) wins over `hoverHighlight` (driven by `onHover`) when both are
      * enabled, mirroring the click-over-hover precedence of the interaction handlers. Returns `Absent` when no
      * highlight is enabled OR the matching ref is not configured (the documented no-op).
      */
    private[kyo] def resolveHighlight[A](spec: Maybe[Chart[A]]): Maybe[Highlight[A]] =
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
    private[kyo] def applyHighlightStyle(el: Svg.SvgElement, style: Maybe[Style]): Svg.SvgElement =
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

    /** Wrap one mark's row-tagged shapes in the built-in highlight.
      *
      * When `highlight` is `Absent`, the shapes are returned unchanged (no-op; non-interactive and plain
      * interactive charts emit the shapes unchanged). When `Present`, all the mark's shapes are wrapped in a
      * single `Reactive` region driven by the user's `ref`: on each emission, the shape whose source row equals
      * the ref's current value gets `applyHighlightStyle`, and every other shape renders unchanged. The region is
      * driven directly by the user ref (`ref.render`), so no internal mutable interaction cell is created.
      *
      * The `Reactive` node is wrapped in an `Svg.G` so the result is an `Svg.SvgElement` that flows through the
      * marks-region fold (mirroring the reactive-rule path).
      */
    private[kyo] def withHighlight[A](
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

    /** Build a tooltip overlay `Reactive[Svg.G]` driven by the chart's internal hover ref.
      *
      * When the hover ref holds `Present(row)`, the overlay renders a translucent rect background and
      * a text label formatted by `spec.tooltip`. When `Absent`, it renders an empty `Svg.g`. The
      * overlay is placed in the upper-left of the plot area and drawn last so it appears on top.
      *
      * The `internalHoverRef` is created once per chart instance (in `lowerStatic`/`lowerLive`) and
      * is separate from `spec.onHover` so the tooltip can work without a user-visible hover ref.
      *
      * No `url(#id)` references are emitted (plain rect + text).
      */
    private[kyo] def buildTooltipOverlay[A](
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

end ChartInteraction
