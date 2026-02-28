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
        case Cursor.wait_      => "wait"
        case Cursor.grab       => "grab"
        case Cursor.grabbing   => "grabbing"

    private def renderProp(prop: Prop): String = prop match
        case BgColor(c)          => s"background-color: ${c.css};"
        case TextColor(c)        => s"color: ${c.css};"
        case Padding(t, r, b, l) => s"padding: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case Margin(t, r, b, l)  => s"margin: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case Gap(v)              => s"gap: ${size(v)};"
        case FlexDirectionProp(d) => d match
                case FlexDirection.row    => "flex-direction: row;"
                case FlexDirection.column => "flex-direction: column;"
        case Align(v)                         => s"align-items: ${alignment(v)};"
        case Justify(v)                       => s"justify-content: ${justification(v)};"
        case OverflowProp(v)                  => s"overflow: ${overflow(v)};"
        case Width(v)                         => s"width: ${size(v)};"
        case Height(v)                        => s"height: ${size(v)};"
        case MinWidth(v)                      => s"min-width: ${size(v)};"
        case MaxWidth(v)                      => s"max-width: ${size(v)};"
        case MinHeight(v)                     => s"min-height: ${size(v)};"
        case MaxHeight(v)                     => s"max-height: ${size(v)};"
        case FontSizeProp(v)                  => s"font-size: ${size(v)};"
        case FontWeightProp(v)                => s"font-weight: ${fontWeight(v)};"
        case FontStyleProp(v)                 => s"font-style: ${fontStyle(v)};"
        case FontFamilyProp(v)                => s"font-family: $v;"
        case TextAlignProp(v)                 => s"text-align: ${textAlign(v)};"
        case TextDecorationProp(v)            => s"text-decoration: ${textDecoration(v)};"
        case LineHeightProp(v)                => s"line-height: ${fmt(v)};"
        case LetterSpacingProp(v)             => s"letter-spacing: ${size(v)};"
        case TextTransformProp(v)             => s"text-transform: ${textTransform(v)};"
        case TextOverflowProp(v)              => s"text-overflow: ${textOverflow(v)};"
        case WrapTextProp(v)                  => if v then "overflow-wrap: break-word;" else "overflow-wrap: normal;"
        case BorderColorProp(t, r, b, l)      => s"border-color: ${t.css} ${r.css} ${b.css} ${l.css};"
        case BorderWidthProp(t, r, b, l)      => s"border-width: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case BorderStyleProp(v)               => s"border-style: ${borderStyle(v)};"
        case BorderTopProp(w, c)              => s"border-top: ${size(w)} solid ${c.css};"
        case BorderRightProp(w, c)            => s"border-right: ${size(w)} solid ${c.css};"
        case BorderBottomProp(w, c)           => s"border-bottom: ${size(w)} solid ${c.css};"
        case BorderLeftProp(w, c)             => s"border-left: ${size(w)} solid ${c.css};"
        case BorderRadiusProp(tl, tr, br, bl) => s"border-radius: ${size(tl)} ${size(tr)} ${size(br)} ${size(bl)};"
        case ShadowProp(x, y, blur, spread, c) =>
            s"box-shadow: ${size(x)} ${size(y)} ${size(blur)} ${size(spread)} ${c.css};"
        case OpacityProp(v)                              => s"opacity: ${fmt(v)};"
        case CursorProp(v)                               => s"cursor: ${cursor(v)};"
        case TranslateProp(x, y)                         => s"transform: translate(${size(x)}, ${size(y)});"
        case _: HoverProp | _: FocusProp | _: ActiveProp => ""

end CssStyleRenderer
