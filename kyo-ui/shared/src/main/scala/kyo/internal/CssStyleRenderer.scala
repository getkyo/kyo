package kyo.internal

import kyo.*
import kyo.Style.*
import kyo.Style.Color.*
import kyo.Style.Prop.*

private[kyo] object CssStyleRenderer:

    private def fmt(v: Double): String = NumberFormat.double(v)

    def color(c: Color): String = c match
        case Color.Hex(value)       => value
        case Color.Rgb(r, g, b)     => s"rgb($r, $g, $b)"
        case Color.Rgba(r, g, b, a) => s"rgba($r, $g, $b, ${fmt(a)})"
        case Color.Transparent      => "transparent"
        case Color.Var(name)        => s"var(--$name)"

    def size(s: Length): String = s match
        case Length.Px(v)   => if v == 0 then "0" else s"${fmt(v)}px"
        case Length.Pct(v)  => s"${fmt(v)}%"
        case Length.Em(v)   => s"${fmt(v)}em"
        case Length.Vh(v)   => s"${fmt(v)}vh"
        case Length.Calc(e) => s"calc($e)"
        case Length.Auto    => "auto"

    def render(style: Style): String =
        val sb      = new StringBuilder
        val filters = new StringBuilder
        style.props.foreach { prop =>
            // renderFilterFragment is the single source of truth for which props are CSS filters: a
            // Present fragment goes into the `filter:` declaration, Absent means it is a normal property.
            renderFilterFragment(prop) match
                case Present(fragment) =>
                    if filters.isEmpty then filters.append("filter:")
                    filters.append(' ')
                    filters.append(fragment)
                case Absent =>
                    val css = renderProp(prop)
                    if css.nonEmpty then
                        if sb.nonEmpty then sb.append(' ')
                        sb.append(css)
        }
        if filters.nonEmpty then
            filters.append(';')
            if sb.nonEmpty then sb.append(' ')
            sb.append(filters)
        end if
        sb.toString
    end render

    /** Present(fragment) for a CSS filter prop, Absent for any non-filter prop. */
    private def renderFilterFragment(prop: Prop): Maybe[String] = prop match
        case BrightnessProp(v) => Present(s"brightness(${fmt(v)})")
        case ContrastProp(v)   => Present(s"contrast(${fmt(v)})")
        case GrayscaleProp(v)  => Present(s"grayscale(${fmt(v)})")
        case SepiaProp(v)      => Present(s"sepia(${fmt(v)})")
        case InvertProp(v)     => Present(s"invert(${fmt(v)})")
        case SaturateProp(v)   => Present(s"saturate(${fmt(v)})")
        case HueRotateProp(v)  => Present(s"hue-rotate(${fmt(v)}deg)")
        case BlurProp(v)       => Present(s"blur(${size(v)})")
        case _                 => Absent

    private def alignment(v: Alignment): String = v match
        case Alignment.start    => "flex-start"
        case Alignment.center   => "center"
        case Alignment.end      => "flex-end"
        case Alignment.stretch  => "stretch"
        case Alignment.baseline => "baseline"

    private def justification(v: Justification): String = v match
        case Justification.start        => "flex-start"
        case Justification.center       => "center"
        case Justification.end          => "flex-end"
        case Justification.spaceBetween => "space-between"
        case Justification.spaceAround  => "space-around"
        case Justification.spaceEvenly  => "space-evenly"

    private def overflow(v: Overflow): String = v match
        case Overflow.visible => "visible"
        case Overflow.hidden  => "hidden"
        case Overflow.scroll  => "scroll"
        case Overflow.auto    => "auto"

    private def scrollbarWidth(v: ScrollbarWidth): String = v match
        case ScrollbarWidth.auto => "auto"
        case ScrollbarWidth.thin => "thin"
        case ScrollbarWidth.none => "none"

    private def scrollbarGutter(v: ScrollbarGutter): String = v match
        case ScrollbarGutter.auto            => "auto"
        case ScrollbarGutter.stable          => "stable"
        case ScrollbarGutter.stableBothEdges => "stable both-edges"

    private def backgroundClip(v: BackgroundClip): String = v match
        case BackgroundClip.borderBox  => "border-box"
        case BackgroundClip.paddingBox => "padding-box"
        case BackgroundClip.contentBox => "content-box"

    private[kyo] def fontWeightCss(v: FontWeight): String = v match
        case FontWeight.normal => "normal"
        case FontWeight.bold   => "bold"
        case FontWeight.w100   => "100"
        case FontWeight.w200   => "200"
        case FontWeight.w300   => "300"
        case FontWeight.w400   => "400"
        case FontWeight.w500   => "500"
        case FontWeight.w600   => "600"
        case FontWeight.w700   => "700"
        case FontWeight.w800   => "800"
        case FontWeight.w900   => "900"

    private[kyo] def fontStyleCss(v: FontStyle): String = v match
        case FontStyle.normal => "normal"
        case FontStyle.italic => "italic"

    private[kyo] def fontVariantLigaturesCss(v: FontVariantLigatures): String = v match
        case FontVariantLigatures.normal => "normal"
        case FontVariantLigatures.none   => "none"

    private def textAlign(v: TextAlign): String = v match
        case TextAlign.left    => "left"
        case TextAlign.center  => "center"
        case TextAlign.right   => "right"
        case TextAlign.justify => "justify"

    private def textDecoration(v: TextDecoration): String = v match
        case TextDecoration.none          => "none"
        case TextDecoration.underline     => "underline"
        case TextDecoration.strikethrough => "line-through"

    private def textTransform(v: TextTransform): String = v match
        case TextTransform.none       => "none"
        case TextTransform.uppercase  => "uppercase"
        case TextTransform.lowercase  => "lowercase"
        case TextTransform.capitalize => "capitalize"

    private def textOverflow(v: TextOverflow): String = v match
        case TextOverflow.clip     => "clip"
        case TextOverflow.ellipsis => "ellipsis"

    private def borderStyle(v: BorderStyle): String = v match
        case BorderStyle.none   => "none"
        case BorderStyle.solid  => "solid"
        case BorderStyle.dashed => "dashed"
        case BorderStyle.dotted => "dotted"

    private def cursor(v: Cursor): String = v match
        case Cursor.defaultCursor => "default"
        case Cursor.pointer       => "pointer"
        case Cursor.text          => "text"
        case Cursor.move          => "move"
        case Cursor.notAllowed    => "not-allowed"
        case Cursor.crosshair     => "crosshair"
        case Cursor.help          => "help"
        case Cursor.wait_         => "wait"
        case Cursor.grab          => "grab"
        case Cursor.grabbing      => "grabbing"

    private def easing(v: Easing): String = v match
        case Easing.ease      => "ease"
        case Easing.linear    => "linear"
        case Easing.easeIn    => "ease-in"
        case Easing.easeOut   => "ease-out"
        case Easing.easeInOut => "ease-in-out"

    private def transitionProperty(v: TransitionProperty): String = v match
        case TransitionProperty.all             => "all"
        case TransitionProperty.backgroundColor => "background-color"
        case TransitionProperty.color           => "color"
        case TransitionProperty.borderColor     => "border-color"
        case TransitionProperty.opacity         => "opacity"
        case TransitionProperty.transform       => "transform"
        case TransitionProperty.Custom(name)    => name

    private def renderProp(prop: Prop): String = prop match
        case BgColor(c)          => s"background-color: ${color(c)};"
        case TextColor(c)        => s"color: ${color(c)};"
        case Padding(t, r, b, l) => s"padding: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case Margin(t, r, b, l)  => s"margin: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case Gap(v)              => s"gap: ${size(v)};"
        case FlexDirectionProp(d) => d match
                case FlexDirection.row           => "flex-direction: row;"
                case FlexDirection.column        => "flex-direction: column;"
                case FlexDirection.rowReverse    => "flex-direction: row-reverse;"
                case FlexDirection.columnReverse => "flex-direction: column-reverse;"
        case Align(v)                    => s"align-items: ${alignment(v)};"
        case Justify(v)                  => s"justify-content: ${justification(v)};"
        case OverflowProp(v)             => s"overflow: ${overflow(v)};"
        case OverflowXProp(v)            => s"overflow-x: ${overflow(v)};"
        case OverflowYProp(v)            => s"overflow-y: ${overflow(v)};"
        case ScrollbarWidthProp(v)       => s"scrollbar-width: ${scrollbarWidth(v)};"
        case ScrollbarColorProp(th, tr)  => s"scrollbar-color: ${color(th)} ${color(tr)};"
        case ScrollbarGutterProp(v)      => s"scrollbar-gutter: ${scrollbarGutter(v)};"
        case BackgroundClipProp(v)       => s"background-clip: ${backgroundClip(v)};"
        case Width(v)                    => s"width: ${size(v)};"
        case Height(v)                   => s"height: ${size(v)};"
        case MinWidth(v)                 => s"min-width: ${size(v)};"
        case MaxWidth(v)                 => s"max-width: ${size(v)};"
        case MinHeight(v)                => s"min-height: ${size(v)};"
        case MaxHeight(v)                => s"max-height: ${size(v)};"
        case FontSizeProp(v)             => s"font-size: ${size(v)};"
        case FontWeightProp(v)           => s"font-weight: ${fontWeightCss(v)};"
        case FontStyleProp(v)            => s"font-style: ${fontStyleCss(v)};"
        case FontVariantLigaturesProp(v) => s"font-variant-ligatures: ${fontVariantLigaturesCss(v)};"
        case FontFamilyProp(v) =>
            val cssValue = v match
                case FontFamily.SansSerif    => "sans-serif"
                case FontFamily.Serif        => "serif"
                case FontFamily.Monospace    => "monospace"
                case FontFamily.Cursive      => "cursive"
                case FontFamily.Fantasy      => "fantasy"
                case FontFamily.SystemUi     => "system-ui"
                case FontFamily.Custom(name) => name
            s"font-family: $cssValue;"
        case TextAlignProp(v)      => s"text-align: ${textAlign(v)};"
        case TextDecorationProp(v) => s"text-decoration: ${textDecoration(v)};"
        case LineHeightProp(v)     => s"line-height: ${fmt(v)};"
        case LetterSpacingProp(v)  => s"letter-spacing: ${size(v)};"
        case TextTransformProp(v)  => s"text-transform: ${textTransform(v)};"
        case TextOverflowProp(v)   => s"text-overflow: ${textOverflow(v)};"
        case TextWrapProp(v) => v match
                case TextWrap.wrap     => "overflow-wrap: break-word;"
                case TextWrap.noWrap   => "overflow-wrap: normal;"
                case TextWrap.ellipsis => "overflow-wrap: normal; text-overflow: ellipsis;"
                case TextWrap.balance  => "text-wrap: balance;"
                case TextWrap.pretty   => "text-wrap: pretty;"
        case BorderColorProp(t, r, b, l)      => s"border-color: ${color(t)} ${color(r)} ${color(b)} ${color(l)};"
        case BorderWidthProp(t, r, b, l)      => s"border-width: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case BorderStyleProp(v)               => s"border-style: ${borderStyle(v)};"
        case BorderRadiusProp(tl, tr, br, bl) => s"border-radius: ${size(tl)} ${size(tr)} ${size(br)} ${size(bl)};"
        case ShadowProp(x, y, blur, spread, c) =>
            s"box-shadow: ${size(x)} ${size(y)} ${size(blur)} ${size(spread)} ${color(c)};"
        case OpacityProp(v)      => s"opacity: ${fmt(v)};"
        case CursorProp(v)       => s"cursor: ${cursor(v)};"
        case TranslateProp(x, y) => s"transform: translate(${size(x)}, ${size(y)});"
        case PositionProp(v) => v match
                case Position.flow     => "position: static;"
                case Position.overlay  => "position: fixed; top: 0; left: 0; width: 100%; height: 100%;"
                case Position.relative => "position: relative;"
                case Position.dropdown => "position: absolute; top: 100%; right: 0; z-index: 50;"
                case Position.sticky   => "position: sticky;"
                case Position.absolute => "position: absolute;"
                case Position.fixed    => "position: fixed;"
        case Top(v)                 => s"top: ${size(v)};"
        case Right(v)               => s"right: ${size(v)};"
        case Bottom(v)              => s"bottom: ${size(v)};"
        case Left(v)                => s"left: ${size(v)};"
        case ZIndexProp(v)          => s"z-index: $v;"
        case AlignSelf(v)           => s"align-self: ${alignment(v)};"
        case ScrollMarginTopProp(v) => s"scroll-margin-top: ${size(v)};"
        case DisplayProp(v) => v match
                case Display.block       => "display: block;"
                case Display.inline      => "display: inline;"
                case Display.inlineBlock => "display: inline-block;"
                case Display.flex        => "display: flex;"
                case Display.inlineFlex  => "display: inline-flex;"
                case Display.listItem    => "display: list-item;"
                case Display.table       => "display: table;"
                case Display.tableRow    => "display: table-row;"
                case Display.tableCell   => "display: table-cell;"
        case ListStyleProp(v) => v match
                case ListStyle.disc    => "list-style-type: disc;"
                case ListStyle.decimal => "list-style-type: decimal;"
                case ListStyle.none    => "list-style-type: none;"
        case BorderCollapseProp(v) => v match
                case BorderCollapse.collapse => "border-collapse: collapse;"
                case BorderCollapse.separate => "border-collapse: separate;"
        case HiddenProp        => "display: none;"
        case FlexGrowProp(v)   => s"flex-grow: ${fmt(v)};"
        case FlexShrinkProp(v) => s"flex-shrink: ${fmt(v)};"
        case FlexBasisProp(v)  => s"flex-basis: ${size(v)};"
        case _: BrightnessProp | _: ContrastProp | _: GrayscaleProp | _: SepiaProp |
            _: InvertProp | _: SaturateProp | _: HueRotateProp | _: BlurProp => ""
        case BgGradientProp(dir, space, colors, positions) =>
            val cssDir = dir match
                case GradientDirection.toRight       => "to right"
                case GradientDirection.toLeft        => "to left"
                case GradientDirection.toTop         => "to top"
                case GradientDirection.toBottom      => "to bottom"
                case GradientDirection.toTopRight    => "to top right"
                case GradientDirection.toTopLeft     => "to top left"
                case GradientDirection.toBottomRight => "to bottom right"
                case GradientDirection.toBottomLeft  => "to bottom left"
            // The default sRGB space emits the bare direction; oklch/oklab append `in <space>` after the
            // direction so the browser interpolates perceptually (no muddy grey midtone, far less 8-bit
            // banding). The CSS spec order is `linear-gradient([<direction>] [in <color-space>]?, <stops>)`,
            // so the color-interpolation-method comes AFTER the direction; the reversed form is rejected.
            val prelude = space match
                case GradientColorSpace.srgb  => cssDir
                case GradientColorSpace.oklch => s"$cssDir in oklch"
                case GradientColorSpace.oklab => s"$cssDir in oklab"
            val sb = new StringBuilder(s"background: linear-gradient($prelude")
            (0 until colors.size).foreach { i =>
                sb.append(s", ${color(colors(i))} ${fmt(positions(i))}%")
            }
            sb.append(");")
            sb.toString
        case FlexWrapProp(v) => v match
                case FlexWrap.wrap   => "flex-wrap: wrap;"
                case FlexWrap.noWrap => "flex-wrap: nowrap;"
        case TransitionProp(prop, durationMs, ease) =>
            s"transition: ${transitionProperty(prop)} ${durationMs}ms ${easing(ease)};"
        case AnimationProp(name, durationMs, ease) =>
            s"animation: $name ${durationMs}ms ${easing(ease)} both;"
        case AnimationDelayProp(ms) =>
            s"animation-delay: ${ms}ms;"
        case StrokeDashoffsetProp(v) =>
            s"stroke-dashoffset: ${fmt(v)};"
        case _: HoverProp | _: FocusProp | _: ActiveProp | _: DisabledProp => ""

end CssStyleRenderer
