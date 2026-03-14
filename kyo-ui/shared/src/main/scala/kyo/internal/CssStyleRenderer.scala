package kyo.internal

import kyo.*
import kyo.Style.*
import kyo.Style.Color.*
import kyo.Style.Prop.*

private[kyo] object CssStyleRenderer:

    private def fmt(v: Double): String =
        if v == v.toLong then v.toLong.toString else v.toString

    def color(c: Color): String = c match
        case Color.Hex(value)       => value
        case Color.Rgb(r, g, b)     => s"rgb($r, $g, $b)"
        case Color.Rgba(r, g, b, a) => s"rgba($r, $g, $b, $a)"
        case Color.Transparent      => "transparent"

    def size(s: Length): String = s match
        case Length.Px(v)  => if v == 0 then "0" else s"${fmt(v)}px"
        case Length.Pct(v) => s"${fmt(v)}%"
        case Length.Em(v)  => s"${fmt(v)}em"
        case Length.Auto   => "auto"

    def render(style: Style): String =
        val sb      = new StringBuilder
        val filters = new StringBuilder
        style.props.foreach { prop =>
            prop match
                case _: BrightnessProp | _: ContrastProp | _: GrayscaleProp | _: SepiaProp |
                    _: InvertProp | _: SaturateProp | _: HueRotateProp | _: BlurProp =>
                    if filters.isEmpty then filters.append("filter:")
                    filters.append(' ')
                    filters.append(renderFilterFragment(prop))
                case _ =>
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

    private def renderFilterFragment(prop: Prop): String = prop match
        case BrightnessProp(v) => s"brightness($v)"
        case ContrastProp(v)   => s"contrast($v)"
        case GrayscaleProp(v)  => s"grayscale($v)"
        case SepiaProp(v)      => s"sepia($v)"
        case InvertProp(v)     => s"invert($v)"
        case SaturateProp(v)   => s"saturate($v)"
        case HueRotateProp(v)  => s"hue-rotate(${v}deg)"
        case BlurProp(v)       => s"blur(${size(v)})"
        case _                 => ""

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

    private def fontWeight(v: FontWeight): String = v match
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

    private def fontStyle(v: FontStyle): String = v match
        case FontStyle.normal => "normal"
        case FontStyle.italic => "italic"

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
        case Cursor.default_   => "default"
        case Cursor.pointer    => "pointer"
        case Cursor.text       => "text"
        case Cursor.move       => "move"
        case Cursor.notAllowed => "not-allowed"
        case Cursor.crosshair  => "crosshair"
        case Cursor.help       => "help"
        case Cursor.await      => "wait"
        case Cursor.grab       => "grab"
        case Cursor.grabbing   => "grabbing"

    private def renderProp(prop: Prop): String = prop match
        case BgColor(c)          => s"background-color: ${color(c)};"
        case TextColor(c)        => s"color: ${color(c)};"
        case Padding(t, r, b, l) => s"padding: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case Margin(t, r, b, l)  => s"margin: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case Gap(v)              => s"gap: ${size(v)};"
        case FlexDirectionProp(d) => d match
                case FlexDirection.row    => "flex-direction: row;"
                case FlexDirection.column => "flex-direction: column;"
        case Align(v)          => s"align-items: ${alignment(v)};"
        case Justify(v)        => s"justify-content: ${justification(v)};"
        case OverflowProp(v)   => s"overflow: ${overflow(v)};"
        case Width(v)          => s"width: ${size(v)};"
        case Height(v)         => s"height: ${size(v)};"
        case MinWidth(v)       => s"min-width: ${size(v)};"
        case MaxWidth(v)       => s"max-width: ${size(v)};"
        case MinHeight(v)      => s"min-height: ${size(v)};"
        case MaxHeight(v)      => s"max-height: ${size(v)};"
        case FontSizeProp(v)   => s"font-size: ${size(v)};"
        case FontWeightProp(v) => s"font-weight: ${fontWeight(v)};"
        case FontStyleProp(v)  => s"font-style: ${fontStyle(v)};"
        case FontFamilyProp(v) =>
            val families = v.split(",").map { f =>
                val t = f.trim
                if t.startsWith("\"") || t.startsWith("'") then t
                else s"\"$t\""
            }
            s"font-family: ${families.mkString(", ")};"
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
        case BorderColorProp(t, r, b, l)      => s"border-color: ${color(t)} ${color(r)} ${color(b)} ${color(l)};"
        case BorderWidthProp(t, r, b, l)      => s"border-width: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case BorderStyleProp(v)               => s"border-style: ${borderStyle(v)};"
        case BorderTopProp(w, c)              => s"border-top: ${size(w)} solid ${color(c)};"
        case BorderRightProp(w, c)            => s"border-right: ${size(w)} solid ${color(c)};"
        case BorderBottomProp(w, c)           => s"border-bottom: ${size(w)} solid ${color(c)};"
        case BorderLeftProp(w, c)             => s"border-left: ${size(w)} solid ${color(c)};"
        case BorderRadiusProp(tl, tr, br, bl) => s"border-radius: ${size(tl)} ${size(tr)} ${size(br)} ${size(bl)};"
        case ShadowProp(x, y, blur, spread, c) =>
            s"box-shadow: ${size(x)} ${size(y)} ${size(blur)} ${size(spread)} ${color(c)};"
        case OpacityProp(v)      => s"opacity: ${fmt(v)};"
        case CursorProp(v)       => s"cursor: ${cursor(v)};"
        case TranslateProp(x, y) => s"transform: translate(${size(x)}, ${size(y)});"
        case PositionProp(v) => v match
                case Position.flow    => "position: static;"
                case Position.overlay => "position: fixed; top: 0; left: 0; width: 100%; height: 100%;"
        case HiddenProp        => "display: none;"
        case FlexGrowProp(v)   => s"flex-grow: $v;"
        case FlexShrinkProp(v) => s"flex-shrink: $v;"
        case _: BrightnessProp | _: ContrastProp | _: GrayscaleProp | _: SepiaProp |
            _: InvertProp | _: SaturateProp | _: HueRotateProp | _: BlurProp => ""
        case BgGradientProp(dir, colors, positions) =>
            val cssDir = dir match
                case GradientDirection.toRight       => "to right"
                case GradientDirection.toLeft        => "to left"
                case GradientDirection.toTop         => "to top"
                case GradientDirection.toBottom      => "to bottom"
                case GradientDirection.toTopRight    => "to top right"
                case GradientDirection.toTopLeft     => "to top left"
                case GradientDirection.toBottomRight => "to bottom right"
                case GradientDirection.toBottomLeft  => "to bottom left"
            val sb = new StringBuilder(s"background: linear-gradient($cssDir")
            var i  = 0
            // unsafe: while for array iteration in non-hot path
            while i < colors.size do
                sb.append(s", ${color(colors(i))} ${fmt(positions(i))}%")
                i += 1
            end while
            sb.append(");")
            sb.toString
        case FlexWrapProp(v) => v match
                case FlexWrap.wrap   => "flex-wrap: wrap;"
                case FlexWrap.noWrap => "flex-wrap: nowrap;"
        case _: HoverProp | _: FocusProp | _: ActiveProp | _: DisabledProp => ""

end CssStyleRenderer
