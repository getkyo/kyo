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
        // Collect individual border sides to combine them
        var borderTopColor: String    = "transparent"
        var borderRightColor: String  = "transparent"
        var borderBottomColor: String = "transparent"
        var borderLeftColor: String   = "transparent"
        var borderTopWidth: String    = "0"
        var borderRightWidth: String  = "0"
        var borderBottomWidth: String = "0"
        var borderLeftWidth: String   = "0"
        var hasIndividualBorderSides  = false
        style.props.foreach {
            case BorderTopProp(w, c)    => hasIndividualBorderSides = true; borderTopColor = c.css; borderTopWidth = size(w)
            case BorderRightProp(w, c)  => hasIndividualBorderSides = true; borderRightColor = c.css; borderRightWidth = size(w)
            case BorderBottomProp(w, c) => hasIndividualBorderSides = true; borderBottomColor = c.css; borderBottomWidth = size(w)
            case BorderLeftProp(w, c)   => hasIndividualBorderSides = true; borderLeftColor = c.css; borderLeftWidth = size(w)
            case _                      => ()
        }
        style.props.foreach { prop =>
            prop match
                case _: BorderTopProp | _: BorderRightProp | _: BorderBottomProp | _: BorderLeftProp =>
                    () // handled above
                case _ =>
                    val css = renderProp(prop)
                    if css.nonEmpty then
                        if sb.nonEmpty then sb.append(' ')
                        sb.append(css)
        }
        if hasIndividualBorderSides then
            if sb.nonEmpty then sb.append(' ')
            sb.append(s"-fx-border-color: $borderTopColor $borderRightColor $borderBottomColor $borderLeftColor;")
            sb.append(s" -fx-border-width: $borderTopWidth $borderRightWidth $borderBottomWidth $borderLeftWidth;")
        end if
        sb.toString
    end render

    private def alignment(v: Alignment): String = v match
        case Alignment.start    => "top-left"
        case Alignment.center   => "center"
        case Alignment.end      => "bottom-right"
        case Alignment.stretch  => "center"
        case Alignment.baseline => "baseline-left"

    private def justification(v: Justification): String = v match
        case Justification.start  => "top-left"
        case Justification.center => "center"
        case Justification.end    => "bottom-right"
        case _                    => ""

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
        case BgColor(c)                                       => s"-fx-background-color: ${c.css};"
        case TextColor(c)                                     => s"-fx-text-fill: ${c.css};"
        case Padding(t, r, b, l)                              => s"-fx-padding: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case Margin(_, _, _, _)                               => ""
        case Gap(v)                                           => s"-fx-spacing: ${size(v)};"
        case FlexDirectionProp(_)                             => ""
        case Align(_)                                         => "" // handled by backend via Java API
        case Justify(_)                                       => "" // handled by backend via Java API
        case OverflowProp(_)                                  => ""
        case Width(v)                                         => s"-fx-pref-width: ${size(v)};"
        case Height(v)                                        => s"-fx-pref-height: ${size(v)};"
        case MinWidth(v)                                      => s"-fx-min-width: ${size(v)};"
        case MaxWidth(v)                                      => s"-fx-max-width: ${size(v)};"
        case MinHeight(v)                                     => s"-fx-min-height: ${size(v)};"
        case MaxHeight(v)                                     => s"-fx-max-height: ${size(v)};"
        case FontSizeProp(v)                                  => s"-fx-font-size: ${size(v)};"
        case FontWeightProp(v)                                => s"-fx-font-weight: ${fontWeight(v)};"
        case FontStyleProp(v)                                 => s"-fx-font-style: ${fontStyle(v)};"
        case FontFamilyProp(v)                                => s"-fx-font-family: '$v';"
        case TextAlignProp(v)                                 => s"-fx-text-alignment: ${textAlign(v)};"
        case TextDecorationProp(TextDecoration.underline)     => "-fx-underline: true;"
        case TextDecorationProp(TextDecoration.strikethrough) => "-fx-strikethrough: true;"
        case TextDecorationProp(TextDecoration.none)          => "-fx-underline: false; -fx-strikethrough: false;"
        case LineHeightProp(_)                                => ""
        case LetterSpacingProp(_)                             => ""
        case TextTransformProp(_)                             => ""
        case TextOverflowProp(TextOverflow.ellipsis)          => "-fx-text-overrun: ellipsis;"
        case TextOverflowProp(TextOverflow.clip)              => "-fx-text-overrun: clip;"
        case WrapTextProp(v)                                  => s"-fx-wrap-text: $v;"
        case BorderColorProp(t, r, b, l)                      => s"-fx-border-color: ${t.css} ${r.css} ${b.css} ${l.css};"
        case BorderWidthProp(t, r, b, l)                      => s"-fx-border-width: ${size(t)} ${size(r)} ${size(b)} ${size(l)};"
        case BorderStyleProp(v)                               => s"-fx-border-style: ${borderStyle(v)};"
        case _: BorderTopProp                                 => "" // combined in render()
        case _: BorderRightProp                               => "" // combined in render()
        case _: BorderBottomProp                              => "" // combined in render()
        case _: BorderLeftProp                                => "" // combined in render()
        case BorderRadiusProp(tl, tr, br, bl) =>
            s"-fx-background-insets: 0; -fx-background-radius: ${size(tl)} ${size(tr)} ${size(br)} ${size(bl)}; -fx-border-radius: ${size(tl)} ${size(tr)} ${size(br)} ${size(bl)};"
        case ShadowProp(_, y, blur, _, c) =>
            s"-fx-effect: dropshadow(gaussian, ${c.css}, ${size(blur)}, 0, 0, ${size(y)});"
        case OpacityProp(v)                              => s"-fx-opacity: ${fmt(v)};"
        case CursorProp(v)                               => s"-fx-cursor: ${cursor(v)};"
        case TranslateProp(x, y)                         => s"-fx-translate-x: ${size(x)}; -fx-translate-y: ${size(y)};"
        case _: HoverProp | _: FocusProp | _: ActiveProp => ""

end FxCssStyleRenderer
