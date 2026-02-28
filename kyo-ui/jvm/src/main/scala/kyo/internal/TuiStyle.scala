package kyo.internal

import kyo.Maybe
import kyo.Maybe.*
import kyo.Style
import kyo.Style.*
import kyo.Style.Prop.*

/** Resolves Style props into TuiLayout flat arrays. No intermediate objects. */
private[kyo] object TuiStyle:

    /** Initialize all style arrays to defaults. Called for every node (including plain Text). */
    def setDefaults(layout: TuiLayout, idx: Int): Unit =
        layout.lFlags(idx) = 0; layout.pFlags(idx) = 0
        layout.padT(idx) = 0; layout.padR(idx) = 0; layout.padB(idx) = 0; layout.padL(idx) = 0
        layout.marT(idx) = 0; layout.marR(idx) = 0; layout.marB(idx) = 0; layout.marL(idx) = 0
        layout.gap(idx) = 0; layout.sizeW(idx) = -1; layout.sizeH(idx) = -1
        layout.minW(idx) = -1; layout.maxW(idx) = -1; layout.minH(idx) = -1; layout.maxH(idx) = -1
        layout.transX(idx) = 0; layout.transY(idx) = 0
        layout.fg(idx) = -1; layout.bg(idx) = -1
        layout.bdrClrT(idx) = -1; layout.bdrClrR(idx) = -1; layout.bdrClrB(idx) = -1; layout.bdrClrL(idx) = -1
        layout.opac(idx) = 1.0f; layout.shadow(idx) = -1
        layout.lineH(idx) = 0; layout.letSp(idx) = 0; layout.fontSz(idx) = 1
        layout.text(idx) = Absent; layout.focusStyle(idx) = Absent
        layout.activeStyle(idx) = Absent; layout.element(idx) = Absent
        layout.nodeType(idx) = 0
    end setDefaults

    /** Resolve a Style into TuiLayout arrays at the given index. */
    def resolve(style: Style, layout: TuiLayout, idx: Int, parentW: Int, parentH: Int): Unit =
        setDefaults(layout, idx)
        style.props.foreach { prop =>
            prop match
                case BgColor(color)   => layout.bg(idx) = TuiColor.resolve(color)
                case TextColor(color) => layout.fg(idx) = TuiColor.resolve(color)
                case Padding(t, r, b, l) =>
                    layout.padT(idx) = resolveSize(t, parentW)
                    layout.padR(idx) = resolveSize(r, parentW)
                    layout.padB(idx) = resolveSize(b, parentW)
                    layout.padL(idx) = resolveSize(l, parentW)
                case Margin(t, r, b, l) =>
                    layout.marT(idx) = resolveSize(t, parentW)
                    layout.marR(idx) = resolveSize(r, parentW)
                    layout.marB(idx) = resolveSize(b, parentW)
                    layout.marL(idx) = resolveSize(l, parentW)
                case Gap(value)       => layout.gap(idx) = resolveSize(value, parentW)
                case Width(value)     => layout.sizeW(idx) = resolveSizeOr(value, parentW, -1)
                case Height(value)    => layout.sizeH(idx) = resolveSizeOr(value, parentH, -1)
                case MinWidth(value)  => layout.minW(idx) = resolveSizeOr(value, parentW, -1)
                case MaxWidth(value)  => layout.maxW(idx) = resolveSizeOr(value, parentW, -1)
                case MinHeight(value) => layout.minH(idx) = resolveSizeOr(value, parentH, -1)
                case MaxHeight(value) => layout.maxH(idx) = resolveSizeOr(value, parentH, -1)

                case Align(value) =>
                    val bits = value match
                        case Alignment.Start    => TuiLayout.AlignStart
                        case Alignment.Center   => TuiLayout.AlignCenter
                        case Alignment.End      => TuiLayout.AlignEnd
                        case Alignment.Stretch  => TuiLayout.AlignStretch
                        case Alignment.Baseline => TuiLayout.AlignStart // no baseline in TUI
                    layout.lFlags(idx) = (layout.lFlags(idx) & ~(TuiLayout.AlignMask << TuiLayout.AlignShift)) |
                        (bits << TuiLayout.AlignShift)

                case Justify(value) =>
                    val bits = value match
                        case Justification.Start        => TuiLayout.JustStart
                        case Justification.Center       => TuiLayout.JustCenter
                        case Justification.End          => TuiLayout.JustEnd
                        case Justification.SpaceBetween => TuiLayout.JustBetween
                        case Justification.SpaceAround  => TuiLayout.JustAround
                        case Justification.SpaceEvenly  => TuiLayout.JustEvenly
                    layout.lFlags(idx) = (layout.lFlags(idx) & ~(TuiLayout.JustMask << TuiLayout.JustShift)) |
                        (bits << TuiLayout.JustShift)

                case OverflowProp(value) =>
                    val bits = value match
                        case Overflow.Visible => 0
                        case Overflow.Hidden  => 1
                        case Overflow.Scroll  => 2
                        case Overflow.Auto    => 2 // auto = scroll in TUI
                    layout.lFlags(idx) = (layout.lFlags(idx) & ~(TuiLayout.OverflowMask << TuiLayout.OverflowShift)) |
                        (bits << TuiLayout.OverflowShift)

                case FontSizeProp(value) => layout.fontSz(idx) = math.max(1, resolveSize(value, parentW))
                case FontWeightProp(value) =>
                    value match
                        case FontWeight.Bold | FontWeight.W600 | FontWeight.W700 | FontWeight.W800 | FontWeight.W900 =>
                            layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.BoldBit)
                        case FontWeight.W100 | FontWeight.W200 | FontWeight.W300 =>
                            layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.DimBit)
                        case _ => () // Normal, W400, W500 — no attribute
                case FontStyleProp(value) =>
                    if value == FontStyle.Italic then
                        layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.ItalicBit)
                case FontFamilyProp(_) => () // no-op in TUI
                case TextAlignProp(value) =>
                    val bits = value match
                        case TextAlign.Left    => TuiLayout.TextAlignLeft
                        case TextAlign.Center  => TuiLayout.TextAlignCenter
                        case TextAlign.Right   => TuiLayout.TextAlignRight
                        case TextAlign.Justify => TuiLayout.TextAlignJustify
                    layout.pFlags(idx) = (layout.pFlags(idx) & ~(TuiLayout.TextAlignMask << TuiLayout.TextAlignShift)) |
                        (bits << TuiLayout.TextAlignShift)

                case TextDecorationProp(value) =>
                    value match
                        case TextDecoration.Underline =>
                            layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.UnderlineBit)
                        case TextDecoration.Strikethrough =>
                            layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.StrikethroughBit)
                        case TextDecoration.None => ()

                case LineHeightProp(value)    => layout.lineH(idx) = math.max(0, value.toInt)
                case LetterSpacingProp(value) => layout.letSp(idx) = resolveSize(value, parentW)
                case TextTransformProp(value) =>
                    val bits = value match
                        case TextTransform.None       => 0
                        case TextTransform.Uppercase  => 1
                        case TextTransform.Lowercase  => 2
                        case TextTransform.Capitalize => 3
                    layout.pFlags(idx) = (layout.pFlags(idx) & ~(TuiLayout.TextTransMask << TuiLayout.TextTransShift)) |
                        (bits << TuiLayout.TextTransShift)

                case TextOverflowProp(value) =>
                    if value == TextOverflow.Ellipsis then
                        layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.TextOverflowBit)

                case BorderStyleProp(value) =>
                    val bits = value match
                        case BorderStyle.None   => TuiLayout.BorderNone
                        case BorderStyle.Solid  => TuiLayout.BorderThin
                        case BorderStyle.Dashed => TuiLayout.BorderDashed
                        case BorderStyle.Dotted => TuiLayout.BorderDotted
                    layout.pFlags(idx) = (layout.pFlags(idx) & ~(TuiLayout.BorderStyleMask << TuiLayout.BorderStyleShift)) |
                        (bits << TuiLayout.BorderStyleShift)

                case BorderWidthProp(t, r, b, l) =>
                    // Width > 0 → border present; width = 0 → absent
                    var flags = layout.lFlags(idx)
                    if resolveBorderWidth(t, parentW) > 0 then flags = flags | (1 << TuiLayout.BorderTBit)
                    if resolveBorderWidth(r, parentW) > 0 then flags = flags | (1 << TuiLayout.BorderRBit)
                    if resolveBorderWidth(b, parentW) > 0 then flags = flags | (1 << TuiLayout.BorderBBit)
                    if resolveBorderWidth(l, parentW) > 0 then flags = flags | (1 << TuiLayout.BorderLBit)
                    layout.lFlags(idx) = flags

                case BorderColorProp(t, r, b, l) =>
                    layout.bdrClrT(idx) = TuiColor.resolve(t)
                    layout.bdrClrR(idx) = TuiColor.resolve(r)
                    layout.bdrClrB(idx) = TuiColor.resolve(b)
                    layout.bdrClrL(idx) = TuiColor.resolve(l)

                case BorderTopProp(width, color) =>
                    if resolveBorderWidth(width, parentW) > 0 then
                        layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.BorderTBit)
                    layout.bdrClrT(idx) = TuiColor.resolve(color)
                case BorderRightProp(width, color) =>
                    if resolveBorderWidth(width, parentW) > 0 then
                        layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.BorderRBit)
                    layout.bdrClrR(idx) = TuiColor.resolve(color)
                case BorderBottomProp(width, color) =>
                    if resolveBorderWidth(width, parentW) > 0 then
                        layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.BorderBBit)
                    layout.bdrClrB(idx) = TuiColor.resolve(color)
                case BorderLeftProp(width, color) =>
                    if resolveBorderWidth(width, parentW) > 0 then
                        layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.BorderLBit)
                    layout.bdrClrL(idx) = TuiColor.resolve(color)

                case BorderRadiusProp(tl, tr, br, bl) =>
                    var pf = layout.pFlags(idx)
                    if resolveBorderWidth(tl, parentW) > 0 then pf = pf | (1 << TuiLayout.RoundedTLBit)
                    if resolveBorderWidth(tr, parentW) > 0 then pf = pf | (1 << TuiLayout.RoundedTRBit)
                    if resolveBorderWidth(br, parentW) > 0 then pf = pf | (1 << TuiLayout.RoundedBRBit)
                    if resolveBorderWidth(bl, parentW) > 0 then pf = pf | (1 << TuiLayout.RoundedBLBit)
                    layout.pFlags(idx) = pf

                case OpacityProp(value) => layout.opac(idx) = value.toFloat
                case ShadowProp(x, y, blur, spread, color) =>
                    layout.shadow(idx) = TuiColor.resolve(color)
                    // x/y/blur/spread used by painter; shadow color stored here

                case TranslateProp(x, y) =>
                    layout.transX(idx) = resolveSizeOr(x, parentW, 0)
                    layout.transY(idx) = resolveSizeOr(y, parentH, 0)

                case FocusProp(s)  => layout.focusStyle(idx) = Present(s.asInstanceOf[AnyRef])
                case ActiveProp(s) => layout.activeStyle(idx) = Present(s.asInstanceOf[AnyRef])
                case HoverProp(_)  => () // stored on element, handled by painter via element ref

                case CursorProp(_)   => () // no-op in TUI
                case RotateProp(_)   => () // removed from TUI
                case ScaleProp(_, _) => () // removed from TUI
        }
    end resolve

    /** Resolve a Size to cell count. Auto → 0. */
    private def resolveSize(size: Size, parentDim: Int): Int =
        size match
            case Size.Px(v)  => math.max(0, v.toInt)
            case Size.Em(v)  => math.max(0, v.toInt)
            case Size.Pct(v) => math.max(0, (v / 100.0 * parentDim).toInt)
            case Size.Auto   => 0

    /** Resolve a Size to cell count. Auto → fallback. */
    private def resolveSizeOr(size: Size, parentDim: Int, fallback: Int): Int =
        size match
            case Size.Px(v)  => math.max(0, v.toInt)
            case Size.Em(v)  => math.max(0, v.toInt)
            case Size.Pct(v) => math.max(0, (v / 100.0 * parentDim).toInt)
            case Size.Auto   => fallback

    /** Resolve border width: clamped to binary (0 or 1). */
    private def resolveBorderWidth(size: Size, parentDim: Int): Int =
        size match
            case Size.Auto => 0
            case _         => if resolveSize(size, parentDim) > 0 then 1 else 0

end TuiStyle
