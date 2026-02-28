package kyo.internal

import kyo.*
import kyo.Style.*
import kyo.Style.Prop.*

private[kyo] object CssStyleRenderer:

    private def fmt(v: Double): String =
        if v == v.toLong then v.toLong.toString else v.toString

    def size(s: Size): String = s match
        case Size.Px(v)  => if v == 0 then "0" else s"${fmt(v)}px"
        case Size.Pct(v) => s"${fmt(v)}%"
        case Size.Em(v)  => s"${fmt(v)}em"
        case Size.Auto   => "auto"

    def render(style: Style): String =
        val sb = new StringBuilder
        style.props.foreach { prop =>
            val css = renderProp(prop)
            if css.nonEmpty then
                if sb.nonEmpty then sb.append(' ')
                sb.append(css)
        }
        sb.toString
    end render

    private def renderProp(prop: Prop): String = prop match
        case BgColor(c)                       => s"background-color: ${c.css};"
        case TextColor(c)                     => s"color: ${c.css};"
        case Padding(t, r, b, l)              => s"padding: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case Margin(t, r, b, l)               => s"margin: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case Gap(v)                           => s"gap: ${size(v)};"
        case Align(v)                         => s"align-items: ${v.css};"
        case Justify(v)                       => s"justify-content: ${v.css};"
        case OverflowProp(v)                  => s"overflow: ${v.css};"
        case Width(v)                         => s"width: ${size(v)};"
        case Height(v)                        => s"height: ${size(v)};"
        case MinWidth(v)                      => s"min-width: ${size(v)};"
        case MaxWidth(v)                      => s"max-width: ${size(v)};"
        case MinHeight(v)                     => s"min-height: ${size(v)};"
        case MaxHeight(v)                     => s"max-height: ${size(v)};"
        case FontSizeProp(v)                  => s"font-size: ${size(v)};"
        case FontWeightProp(v)                => s"font-weight: ${v.css};"
        case FontStyleProp(v)                 => s"font-style: ${v.css};"
        case FontFamilyProp(v)                => s"font-family: $v;"
        case TextAlignProp(v)                 => s"text-align: ${v.css};"
        case TextDecorationProp(v)            => s"text-decoration: ${v.css};"
        case LineHeightProp(v)                => s"line-height: ${fmt(v)};"
        case LetterSpacingProp(v)             => s"letter-spacing: ${size(v)};"
        case TextTransformProp(v)             => s"text-transform: ${v.css};"
        case TextOverflowProp(v)              => s"text-overflow: ${v.css};"
        case BorderColorProp(t, r, b, l)      => s"border-color: ${t.css} ${r.css} ${b.css} ${l.css};"
        case BorderWidthProp(t, r, b, l)      => s"border-width: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case BorderStyleProp(v)               => s"border-style: ${v.css};"
        case BorderTopProp(w, c)              => s"border-top: ${size(w)} solid ${c.css};"
        case BorderRightProp(w, c)            => s"border-right: ${size(w)} solid ${c.css};"
        case BorderBottomProp(w, c)           => s"border-bottom: ${size(w)} solid ${c.css};"
        case BorderLeftProp(w, c)             => s"border-left: ${size(w)} solid ${c.css};"
        case BorderRadiusProp(tl, tr, br, bl) => s"border-radius: ${size(tl)} ${size(tr)} ${size(br)} ${size(bl)};"
        case ShadowProp(x, y, blur, spread, c) =>
            s"box-shadow: ${size(x)} ${size(y)} ${size(blur)} ${size(spread)} ${c.css};"
        case OpacityProp(v)                              => s"opacity: ${fmt(v)};"
        case CursorProp(v)                               => s"cursor: ${v.css};"
        case RotateProp(deg)                             => s"transform: rotate(${fmt(deg)}deg);"
        case ScaleProp(x, y)                             => s"transform: scale(${fmt(x)}, ${fmt(y)});"
        case TranslateProp(x, y)                         => s"transform: translate(${size(x)}, ${size(y)});"
        case _: HoverProp | _: FocusProp | _: ActiveProp => ""

end CssStyleRenderer
