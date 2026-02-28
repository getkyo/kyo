package kyo.internal

import kyo.*
import kyo.Style.*
import kyo.Style.Prop.*

private[kyo] object FxCssStyleRenderer:

    private def fmt(v: Double): String =
        if v == v.toLong then v.toLong.toString else v.toString

    def size(s: Size): String = s match
        case Size.Px(v)  => if v == 0 then "0" else fmt(v)
        case Size.Pct(v) => s"${fmt(v)}%"
        case Size.Em(v)  => s"${fmt(v)}em"
        case Size.Auto   => "Infinity"

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
        case BgColor(c)                                       => s"-fx-background-color: ${c.css};"
        case TextColor(c)                                     => s"-fx-text-fill: ${c.css};"
        case Padding(t, r, b, l)                              => s"-fx-padding: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case Margin(_, _, _, _)                               => ""
        case Gap(v)                                           => s"-fx-spacing: ${size(v)};"
        case Align(v)                                         => s"-fx-alignment: ${v.fxCss};"
        case Justify(v)                                       => s"-fx-alignment: ${v.fxCss};"
        case OverflowProp(_)                                  => ""
        case Width(v)                                         => s"-fx-pref-width: ${size(v)};"
        case Height(v)                                        => s"-fx-pref-height: ${size(v)};"
        case MinWidth(v)                                      => s"-fx-min-width: ${size(v)};"
        case MaxWidth(v)                                      => s"-fx-max-width: ${size(v)};"
        case MinHeight(v)                                     => s"-fx-min-height: ${size(v)};"
        case MaxHeight(v)                                     => s"-fx-max-height: ${size(v)};"
        case FontSizeProp(v)                                  => s"-fx-font-size: ${size(v)};"
        case FontWeightProp(v)                                => s"-fx-font-weight: ${v.css};"
        case FontStyleProp(v)                                 => s"-fx-font-style: ${v.css};"
        case FontFamilyProp(v)                                => s"-fx-font-family: '$v';"
        case TextAlignProp(v)                                 => s"-fx-text-alignment: ${v.css};"
        case TextDecorationProp(TextDecoration.Underline)     => "-fx-underline: true;"
        case TextDecorationProp(TextDecoration.Strikethrough) => "-fx-strikethrough: true;"
        case TextDecorationProp(TextDecoration.None)          => "-fx-underline: false; -fx-strikethrough: false;"
        case LineHeightProp(_)                                => ""
        case LetterSpacingProp(_)                             => ""
        case TextTransformProp(_)                             => ""
        case TextOverflowProp(TextOverflow.Ellipsis)          => "-fx-text-overrun: ellipsis;"
        case TextOverflowProp(TextOverflow.Clip)              => "-fx-text-overrun: clip;"
        case BorderColorProp(t, r, b, l)                      => s"-fx-border-color: ${t.css} ${r.css} ${b.css} ${l.css};"
        case BorderWidthProp(t, r, b, l)                      => s"-fx-border-width: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case BorderStyleProp(v)                               => s"-fx-border-style: ${v.css};"
        case BorderTopProp(w, c) =>
            s"-fx-border-color: ${c.css} transparent transparent transparent; -fx-border-width: ${size(w)} 0 0 0;"
        case BorderRightProp(w, c) =>
            s"-fx-border-color: transparent ${c.css} transparent transparent; -fx-border-width: 0 ${size(w)} 0 0;"
        case BorderBottomProp(w, c) =>
            s"-fx-border-color: transparent transparent ${c.css} transparent; -fx-border-width: 0 0 ${size(w)} 0;"
        case BorderLeftProp(w, c) =>
            s"-fx-border-color: transparent transparent transparent ${c.css}; -fx-border-width: 0 0 0 ${size(w)};"
        case BorderRadiusProp(tl, tr, br, bl) =>
            s"-fx-background-radius: ${size(tl)} ${size(tr)} ${size(br)} ${size(bl)}; -fx-border-radius: ${size(tl)} ${size(tr)} ${size(br)} ${size(bl)};"
        case ShadowProp(_, y, blur, _, c) =>
            s"-fx-effect: dropshadow(gaussian, ${c.css}, ${size(blur)}, 0, 0, ${size(y)});"
        case OpacityProp(v)                              => s"-fx-opacity: ${fmt(v)};"
        case CursorProp(v)                               => s"-fx-cursor: ${v.css};"
        case RotateProp(deg)                             => s"-fx-rotate: ${fmt(deg)};"
        case ScaleProp(x, y)                             => s"-fx-scale-x: ${fmt(x)}; -fx-scale-y: ${fmt(y)};"
        case TranslateProp(x, y)                         => s"-fx-translate-x: ${size(x)}; -fx-translate-y: ${size(y)};"
        case _: HoverProp | _: FocusProp | _: ActiveProp => ""

end FxCssStyleRenderer
