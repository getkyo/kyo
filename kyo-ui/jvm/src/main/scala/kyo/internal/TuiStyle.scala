package kyo.internal

import kyo.Maybe
import kyo.Maybe.*
import kyo.Span
import kyo.Style
import kyo.Style.*
import kyo.Style.Prop.*

/** Resolves Style props into TuiLayout flat arrays. No intermediate objects. */
private[kyo] object TuiStyle:

    /** Initialize all style arrays to defaults. Called for every node (including plain Text). */
    private[internal] def setDefaults(layout: TuiLayout, idx: Int): Unit =
        layout.lFlags(idx) = 0; layout.pFlags(idx) = 0
        layout.padT(idx) = 0; layout.padR(idx) = 0; layout.padB(idx) = 0; layout.padL(idx) = 0
        layout.marT(idx) = 0; layout.marR(idx) = 0; layout.marB(idx) = 0; layout.marL(idx) = 0
        layout.gap(idx) = 0; layout.sizeW(idx) = -1; layout.sizeH(idx) = -1
        layout.minW(idx) = -1; layout.maxW(idx) = -1; layout.minH(idx) = -1; layout.maxH(idx) = -1
        layout.transX(idx) = 0; layout.transY(idx) = 0
        layout.fg(idx) = -1; layout.bg(idx) = -1
        layout.bdrClrT(idx) = -1; layout.bdrClrR(idx) = -1; layout.bdrClrB(idx) = -1; layout.bdrClrL(idx) = -1
        layout.opac(idx) = 1.0; layout.fontSz(idx) = 1
        layout.shadowClr(idx) = -1; layout.shadowX(idx) = 0; layout.shadowY(idx) = 0
        layout.shadowBlur(idx) = 0; layout.shadowSpread(idx) = 0
        layout.flexGrow(idx) = 0.0; layout.flexShrink(idx) = 1.0
        layout.filterBits(idx) = 0
        layout.colspan(idx) = 1; layout.rowspan(idx) = 1
        layout.text(idx) = Absent; layout.focusStyle(idx) = Absent
        layout.activeStyle(idx) = Absent; layout.hoverStyle(idx) = Absent
        layout.disabledStyle(idx) = Absent; layout.element(idx) = Absent
        layout.nodeType(idx) = 0
    end setDefaults

    /** Resolve a Style into TuiLayout arrays at the given index. */
    def resolve(style: Style, layout: TuiLayout, idx: Int, parentW: Int, parentH: Int): Unit =
        setDefaults(layout, idx)
        // Enable text wrapping by default -- terminals have fixed width
        layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.WrapTextBit)
        applyProps(style.props, layout, idx, parentW, parentH)
    end resolve

    /** Resolve theme style first, then user style on top (user overrides theme). */
    def resolveWithTheme(themeStyle: Style, userStyle: Style, layout: TuiLayout, idx: Int, parentW: Int, parentH: Int): Unit =
        setDefaults(layout, idx)
        layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.WrapTextBit)
        if themeStyle.nonEmpty then applyProps(themeStyle.props, layout, idx, parentW, parentH)
        applyProps(userStyle.props, layout, idx, parentW, parentH)
    end resolveWithTheme

    private def applyProps(props: Span[Style.Prop], layout: TuiLayout, idx: Int, parentW: Int, parentH: Int): Unit =
        props.foreach { prop =>
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

                case FlexDirectionProp(value) =>
                    value match
                        case FlexDirection.row    => layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.DirBit)
                        case FlexDirection.column => layout.lFlags(idx) = layout.lFlags(idx) & ~(1 << TuiLayout.DirBit)

                case Align(value) =>
                    val bits = value match
                        case Alignment.start    => TuiLayout.AlignStart
                        case Alignment.center   => TuiLayout.AlignCenter
                        case Alignment.end      => TuiLayout.AlignEnd
                        case Alignment.stretch  => TuiLayout.AlignStretch
                        case Alignment.baseline => TuiLayout.AlignStart // no baseline in TUI
                    layout.lFlags(idx) = (layout.lFlags(idx) & ~(TuiLayout.AlignMask << TuiLayout.AlignShift)) |
                        (bits << TuiLayout.AlignShift)

                case Justify(value) =>
                    val bits = value match
                        case Justification.start        => TuiLayout.JustStart
                        case Justification.center       => TuiLayout.JustCenter
                        case Justification.end          => TuiLayout.JustEnd
                        case Justification.spaceBetween => TuiLayout.JustBetween
                        case Justification.spaceAround  => TuiLayout.JustAround
                        case Justification.spaceEvenly  => TuiLayout.JustEvenly
                    layout.lFlags(idx) = (layout.lFlags(idx) & ~(TuiLayout.JustMask << TuiLayout.JustShift)) |
                        (bits << TuiLayout.JustShift)

                case OverflowProp(value) =>
                    val bits = value match
                        case Overflow.visible => 0
                        case Overflow.hidden  => 1
                        case Overflow.scroll  => 2
                        case Overflow.auto    => 2 // auto = scroll in TUI
                    layout.lFlags(idx) = (layout.lFlags(idx) & ~(TuiLayout.OverflowMask << TuiLayout.OverflowShift)) |
                        (bits << TuiLayout.OverflowShift)

                case FontSizeProp(value) => layout.fontSz(idx) = math.max(1, resolveSize(value, parentW))
                case FontWeightProp(value) =>
                    value match
                        case FontWeight.bold | FontWeight.w600 | FontWeight.w700 | FontWeight.w800 | FontWeight.w900 =>
                            layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.BoldBit)
                        case FontWeight.w100 | FontWeight.w200 | FontWeight.w300 =>
                            layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.DimBit)
                        case _ => () // Normal, W400, W500 -- no attribute
                case FontStyleProp(value) =>
                    if value == FontStyle.italic then
                        layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.ItalicBit)
                case FontFamilyProp(_) => () // no-op in TUI
                case TextAlignProp(value) =>
                    val bits = value match
                        case TextAlign.left    => TuiLayout.TextAlignLeft
                        case TextAlign.center  => TuiLayout.TextAlignCenter
                        case TextAlign.right   => TuiLayout.TextAlignRight
                        case TextAlign.justify => TuiLayout.TextAlignJustify
                    layout.pFlags(idx) = (layout.pFlags(idx) & ~(TuiLayout.TextAlignMask << TuiLayout.TextAlignShift)) |
                        (bits << TuiLayout.TextAlignShift)

                case TextDecorationProp(value) =>
                    value match
                        case TextDecoration.underline =>
                            layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.UnderlineBit)
                        case TextDecoration.strikethrough =>
                            layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.StrikethroughBit)
                        case TextDecoration.none => ()

                case LineHeightProp(_)    => () // removed — not meaningful in TUI
                case LetterSpacingProp(_) => () // removed — not meaningful in TUI
                case TextTransformProp(value) =>
                    val bits = value match
                        case TextTransform.none       => 0
                        case TextTransform.uppercase  => 1
                        case TextTransform.lowercase  => 2
                        case TextTransform.capitalize => 3
                    layout.pFlags(idx) = (layout.pFlags(idx) & ~(TuiLayout.TextTransMask << TuiLayout.TextTransShift)) |
                        (bits << TuiLayout.TextTransShift)

                case TextOverflowProp(value) =>
                    if value == TextOverflow.ellipsis then
                        layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.TextOverflowBit)

                case TextWrapProp(value) =>
                    value match
                        case TextWrap.wrap =>
                            layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.WrapTextBit)
                            layout.pFlags(idx) = layout.pFlags(idx) & ~(1 << TuiLayout.TextOverflowBit)
                        case TextWrap.noWrap =>
                            layout.pFlags(idx) = layout.pFlags(idx) & ~(1 << TuiLayout.WrapTextBit)
                            layout.pFlags(idx) = layout.pFlags(idx) & ~(1 << TuiLayout.TextOverflowBit)
                        case TextWrap.ellipsis =>
                            layout.pFlags(idx) = layout.pFlags(idx) & ~(1 << TuiLayout.WrapTextBit)
                            layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.TextOverflowBit)

                case BorderStyleProp(value) =>
                    val bits = value match
                        case BorderStyle.none   => TuiLayout.BorderNone
                        case BorderStyle.solid  => TuiLayout.BorderThin
                        case BorderStyle.dashed => TuiLayout.BorderDashed
                        case BorderStyle.dotted => TuiLayout.BorderDotted
                    layout.pFlags(idx) = (layout.pFlags(idx) & ~(TuiLayout.BorderStyleMask << TuiLayout.BorderStyleShift)) |
                        (bits << TuiLayout.BorderStyleShift)

                case BorderWidthProp(t, r, b, l) =>
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

                case OpacityProp(value) => layout.opac(idx) = value
                case ShadowProp(sx, sy, sblur, sspread, color) =>
                    layout.shadowClr(idx) = TuiColor.resolve(color)
                    layout.shadowX(idx) = resolveSize(sx, parentW)
                    layout.shadowY(idx) = resolveSize(sy, parentH)
                    layout.shadowBlur(idx) = resolveSize(sblur, parentW)
                    layout.shadowSpread(idx) = resolveSize(sspread, parentW)

                case TranslateProp(x, y) =>
                    layout.transX(idx) = resolveSizeOr(x, parentW, 0)
                    layout.transY(idx) = resolveSizeOr(y, parentH, 0)

                case FocusProp(s)    => layout.focusStyle(idx) = Present(s)
                case ActiveProp(s)   => layout.activeStyle(idx) = Present(s)
                case HoverProp(s)    => layout.hoverStyle(idx) = Present(s)
                case DisabledProp(s) => layout.disabledStyle(idx) = Present(s)

                case CursorProp(_) => () // no-op in TUI
                case HiddenProp =>
                    layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.HiddenBit)
                case PositionProp(value) =>
                    value match
                        case Position.overlay =>
                            layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.PositionBit)
                        case Position.flow =>
                            layout.lFlags(idx) = layout.lFlags(idx) & ~(1 << TuiLayout.PositionBit)

                case FlexGrowProp(value)   => layout.flexGrow(idx) = value
                case FlexShrinkProp(value) => layout.flexShrink(idx) = value

                case BrightnessProp(value) =>
                    layout.filterBits(idx) = layout.filterBits(idx) | (1 << 0)
                    layout.filterVals(idx * 8 + 0) = value
                case ContrastProp(value) =>
                    layout.filterBits(idx) = layout.filterBits(idx) | (1 << 1)
                    layout.filterVals(idx * 8 + 1) = value
                case GrayscaleProp(value) =>
                    layout.filterBits(idx) = layout.filterBits(idx) | (1 << 2)
                    layout.filterVals(idx * 8 + 2) = value
                case SepiaProp(value) =>
                    layout.filterBits(idx) = layout.filterBits(idx) | (1 << 3)
                    layout.filterVals(idx * 8 + 3) = value
                case InvertProp(value) =>
                    layout.filterBits(idx) = layout.filterBits(idx) | (1 << 4)
                    layout.filterVals(idx * 8 + 4) = value
                case SaturateProp(value) =>
                    layout.filterBits(idx) = layout.filterBits(idx) | (1 << 5)
                    layout.filterVals(idx * 8 + 5) = value
                case HueRotateProp(value) =>
                    layout.filterBits(idx) = layout.filterBits(idx) | (1 << 6)
                    layout.filterVals(idx * 8 + 6) = value
                case BlurProp(value) =>
                    layout.filterBits(idx) = layout.filterBits(idx) | (1 << 7)
                    layout.filterVals(idx * 8 + 7) = resolveSize(value, parentW).toDouble

                case FlexWrapProp(_) => () // handled by tui2.ResolvedStyle

                case BgGradientProp(direction, colors, positions) =>
                    // Gradient: resolve first and last color as bg blend
                    if colors.size >= 2 then
                        val c0 = TuiColor.resolve(colors(0))
                        val c1 = TuiColor.resolve(colors(colors.size - 1))
                        // Simple blend of endpoints as approximation for TUI
                        layout.bg(idx) = TuiColor.blend(c0, c1, 0.5)
                    else if colors.size == 1 then
                        layout.bg(idx) = TuiColor.resolve(colors(0))
        }
    end applyProps

    /** Overlay a Style on top of existing layout arrays -- does NOT call setDefaults. Used by applyStates to apply state overlays without
      * resetting existing values.
      */
    private def overlay(style: Style, layout: TuiLayout, idx: Int): Unit =
        val parentW = layout.w(idx)
        val parentH = layout.h(idx)
        style.props.foreach { prop =>
            prop match
                case BgColor(color)   => layout.bg(idx) = TuiColor.resolve(color)
                case TextColor(color) => layout.fg(idx) = TuiColor.resolve(color)
                case BorderWidthProp(t, r, b, l) =>
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
                case BorderStyleProp(value) =>
                    val bits = value match
                        case BorderStyle.none   => TuiLayout.BorderNone
                        case BorderStyle.solid  => TuiLayout.BorderThin
                        case BorderStyle.dashed => TuiLayout.BorderDashed
                        case BorderStyle.dotted => TuiLayout.BorderDotted
                    layout.pFlags(idx) = (layout.pFlags(idx) & ~(TuiLayout.BorderStyleMask << TuiLayout.BorderStyleShift)) |
                        (bits << TuiLayout.BorderStyleShift)
                case BorderRadiusProp(tl, tr, br, bl) =>
                    var pf = layout.pFlags(idx)
                    if resolveBorderWidth(tl, parentW) > 0 then pf = pf | (1 << TuiLayout.RoundedTLBit)
                    if resolveBorderWidth(tr, parentW) > 0 then pf = pf | (1 << TuiLayout.RoundedTRBit)
                    if resolveBorderWidth(br, parentW) > 0 then pf = pf | (1 << TuiLayout.RoundedBRBit)
                    if resolveBorderWidth(bl, parentW) > 0 then pf = pf | (1 << TuiLayout.RoundedBLBit)
                    layout.pFlags(idx) = pf
                case OpacityProp(value) => layout.opac(idx) = value
                case _                  => () // Only visual overlay props -- skip layout-affecting props
        }
    end overlay

    // ---- Style Inheritance ----

    /** Forward pass O(n): propagate inherited properties from parent to child.
      *
      * Parents always have lower indices than children (TuiFlatten allocates depth-first). Text nodes inherit everything (fg, bg, pFlags).
      * Element nodes inherit fg/bg only when Absent.
      */
    def inherit(layout: TuiLayout): Unit =
        val count = layout.count
        @scala.annotation.tailrec
        def loop(idx: Int): Unit =
            if idx < count then
                val pi = layout.parent(idx)
                if pi >= 0 then
                    if layout.nodeType(idx) == TuiLayout.NodeText then
                        if layout.fg(idx) == TuiColor.Absent then
                            layout.fg(idx) = layout.fg(pi)
                        if layout.bg(idx) == TuiColor.Absent then
                            layout.bg(idx) = layout.bg(pi)
                        layout.pFlags(idx) = layout.pFlags(pi)
                    else
                        if layout.fg(idx) == TuiColor.Absent then
                            layout.fg(idx) = layout.fg(pi)
                        if layout.bg(idx) == TuiColor.Absent then
                            layout.bg(idx) = layout.bg(pi)
                    end if
                    val opac = layout.opac(idx)
                    if opac < 1.0 then
                        val parentBg = layout.bg(pi)
                        if parentBg != TuiColor.Absent then
                            if layout.fg(idx) != TuiColor.Absent then
                                layout.fg(idx) = TuiColor.blend(layout.fg(idx), parentBg, opac)
                            if layout.bg(idx) != TuiColor.Absent then
                                layout.bg(idx) = TuiColor.blend(layout.bg(idx), parentBg, opac)
                        end if
                    end if
                end if
                loop(idx + 1)
        loop(1)
    end inherit

    // ---- State-dependent Overlays ----

    /** Apply state-dependent style overlays: focus, hover, active, disabled.
      *
      * Reads focusStyle/hoverStyle/activeStyle/disabledStyle from layout arrays and calls overlay for each active state. Disabled always
      * applies if the element has the disabled flag. Focus/hover/active apply based on tracked indices from TuiFocus.
      */
    def applyStates(layout: TuiLayout, focusedIdx: Int, hoverIdx: Int, activeIdx: Int): Unit =
        val count = layout.count
        // Apply disabled style to all disabled elements
        @scala.annotation.tailrec
        def applyDisabled(idx: Int): Unit =
            if idx < count then
                if TuiLayout.isDisabled(layout.lFlags(idx)) then
                    val ds = layout.disabledStyle(idx)
                    if ds.isDefined then
                        overlay(ds.get, layout, idx)
                    else
                        // Default disabled: dim the element
                        layout.pFlags(idx) = layout.pFlags(idx) | (1 << TuiLayout.DimBit)
                    end if
                end if
                applyDisabled(idx + 1)
        applyDisabled(0)

        // Apply hover style
        if hoverIdx >= 0 && hoverIdx < count then
            val hs = layout.hoverStyle(hoverIdx)
            if hs.isDefined then
                overlay(hs.get, layout, hoverIdx)
        end if

        // Apply active style
        if activeIdx >= 0 && activeIdx < count then
            val as = layout.activeStyle(activeIdx)
            if as.isDefined then
                overlay(as.get, layout, activeIdx)
        end if

        // Apply focus style (highest priority — applied last)
        if focusedIdx >= 0 && focusedIdx < count then
            val fs = layout.focusStyle(focusedIdx)
            if fs.isDefined then
                overlay(fs.get, layout, focusedIdx)
            else
                // Default highlight: thin blue border on all sides
                val borderBits = (1 << TuiLayout.BorderTBit) | (1 << TuiLayout.BorderRBit) |
                    (1 << TuiLayout.BorderBBit) | (1 << TuiLayout.BorderLBit)
                layout.lFlags(focusedIdx) = layout.lFlags(focusedIdx) | borderBits
                val blue = TuiColor.pack(122, 162, 247) // #7aa2f7
                layout.bdrClrT(focusedIdx) = blue
                layout.bdrClrR(focusedIdx) = blue
                layout.bdrClrB(focusedIdx) = blue
                layout.bdrClrL(focusedIdx) = blue
                if TuiLayout.borderStyle(layout.pFlags(focusedIdx)) == TuiLayout.BorderNone then
                    layout.pFlags(focusedIdx) = (layout.pFlags(focusedIdx) & ~(TuiLayout.BorderStyleMask << TuiLayout.BorderStyleShift)) |
                        (TuiLayout.BorderThin << TuiLayout.BorderStyleShift)
            end if
        end if
    end applyStates

    /** Scale factor: 1 terminal cell = 8 CSS pixels. */
    private inline val PxPerCell = 8.0

    /** Resolve a Size to cell count. Px values scaled by PxPerCell. Auto -> 0. */
    private def resolveSize(size: Size, parentDim: Int): Int =
        size match
            case Size.Px(v)  => math.max(0, math.round(v / PxPerCell).toInt)
            case Size.Em(v)  => math.max(0, v.toInt)
            case Size.Pct(v) => math.max(0, (v / 100.0 * parentDim).toInt)
            case Size.Auto   => 0

    /** Resolve a Size to cell count. Px values scaled by PxPerCell. Auto -> fallback. */
    private def resolveSizeOr(size: Size, parentDim: Int, fallback: Int): Int =
        size match
            case Size.Px(v)  => math.max(0, math.round(v / PxPerCell).toInt)
            case Size.Em(v)  => math.max(0, v.toInt)
            case Size.Pct(v) => math.max(0, (v / 100.0 * parentDim).toInt)
            case Size.Auto   => fallback

    /** Resolve border width: binary (any non-zero Px -> 1 cell). */
    private def resolveBorderWidth(size: Size, parentDim: Int): Int =
        size match
            case Size.Auto   => 0
            case Size.Px(v)  => if v > 0 then 1 else 0
            case Size.Em(v)  => if v > 0 then 1 else 0
            case Size.Pct(v) => if (v / 100.0 * parentDim).toInt > 0 then 1 else 0

end TuiStyle
