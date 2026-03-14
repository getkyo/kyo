package kyo.internal.tui2.pipeline

import kyo.*
import scala.annotation.tailrec

/** Pure function: Resolved → Styled via style inheritance. No state reads, no side effects.
  */
object Styler:

    def style(node: Resolved, parent: FlatStyle): Styled =
        node match
            case Resolved.Node(tag, userStyle, handlers, children) =>
                val flat           = resolve(userStyle, parent)
                val styledChildren = children.map(child => style(child, flat))
                Styled.Node(tag, flat, handlers, styledChildren)
            case Resolved.Text(value) =>
                Styled.Text(value, inheritText(parent))
            case Resolved.Cursor(offset) =>
                Styled.Cursor(offset)

    /** Resolve a node's style by combining parent inheritance with user-specified props.
      *
      * Follows CSS-like inheritance: visual properties (color, bold, text-*) inherit from parent, while box model (padding, margin, border)
      * and layout (flex, width, overflow) reset to defaults. User props override inherited values where specified.
      */
    private def resolve(userStyle: Style, parent: FlatStyle): FlatStyle =
        // ---- Inherited from parent ----
        var fg            = parent.fg
        var bg            = parent.bg
        var bold          = parent.bold
        var italic        = parent.italic
        var underline     = parent.underline
        var strikethrough = parent.strikethrough
        var opacity       = parent.opacity
        var cursorStyle   = parent.cursor
        var textAlign     = parent.textAlign
        var textTransform = parent.textTransform
        var textWrap      = parent.textWrap
        var textOverflow  = parent.textOverflow
        var lineHeight    = parent.lineHeight
        var letterSpacing = parent.letterSpacing

        // ---- Non-inherited: start at defaults ----
        var shadowX: Length.Px                                = Length.zero
        var shadowY: Length.Px                                = Length.zero
        var shadowBlur: Length.Px                             = Length.zero
        var shadowSpread: Length.Px                           = Length.zero
        var shadowColor                                       = RGB.Transparent
        var gradientDirection: Maybe[Style.GradientDirection] = Absent
        var gradientStops: Chunk[(RGB, Double)]               = Chunk.empty
        var brightness                                        = 1.0
        var contrast                                          = 1.0
        var grayscale                                         = 0.0
        var sepia                                             = 0.0
        var invert                                            = 0.0
        var saturate                                          = 1.0
        var hueRotate                                         = 0.0
        var blur: Length.Px                                   = Length.zero
        var translateX: Length                                = Length.zero
        var translateY: Length                                = Length.zero
        var padTop: Length                                    = Length.zero
        var padRight: Length                                  = Length.zero
        var padBottom: Length                                 = Length.zero
        var padLeft: Length                                   = Length.zero
        var marTop: Length                                    = Length.zero
        var marRight: Length                                  = Length.zero
        var marBottom: Length                                 = Length.zero
        var marLeft: Length                                   = Length.zero
        var borderTop: Length.Px                              = Length.zero
        var borderRight: Length.Px                            = Length.zero
        var borderBottom: Length.Px                           = Length.zero
        var borderLeft: Length.Px                             = Length.zero
        var borderStyle                                       = Style.BorderStyle.none
        var borderColorTop                                    = RGB.Transparent
        var borderColorRight                                  = RGB.Transparent
        var borderColorBottom                                 = RGB.Transparent
        var borderColorLeft                                   = RGB.Transparent
        var roundTL                                           = false
        var roundTR                                           = false
        var roundBR                                           = false
        var roundBL                                           = false
        var direction                                         = Style.FlexDirection.row
        var justify                                           = Style.Justification.start
        var align                                             = Style.Alignment.start
        var gap: Length                                       = Length.zero
        var flexGrow                                          = 0.0
        var flexShrink                                        = 1.0
        var flexWrap                                          = Style.FlexWrap.wrap
        var width: Length                                     = Length.Auto
        var height: Length                                    = Length.Auto
        var minWidth: Length                                  = Length.zero
        var maxWidth: Length                                  = Length.Auto
        var minHeight: Length                                 = Length.zero
        var maxHeight: Length                                 = Length.Auto
        var overflow                                          = Style.Overflow.visible
        var scrollTop                                         = 0
        var scrollLeft                                        = 0
        var position                                          = Style.Position.flow

        // ---- Apply user style props ----
        val props = userStyle.props
        @tailrec def loop(i: Int): Unit =
            if i < props.size then
                props(i) match
                    case Style.Prop.BgColor(c)   => bg = RGB.fromStyle(c, parent.bg)
                    case Style.Prop.TextColor(c) => fg = RGB.fromStyle(c, parent.bg)
                    case Style.Prop.Padding(t, r, b, l) =>
                        padTop = t
                        padRight = r
                        padBottom = b
                        padLeft = l
                    case Style.Prop.Margin(t, r, b, l) =>
                        marTop = t
                        marRight = r
                        marBottom = b
                        marLeft = l
                    case Style.Prop.Gap(v)               => gap = v
                    case Style.Prop.FlexDirectionProp(d) => direction = d
                    case Style.Prop.FlexWrapProp(w)      => flexWrap = w
                    case Style.Prop.Align(v)             => align = v
                    case Style.Prop.Justify(v)           => justify = v
                    case Style.Prop.OverflowProp(v)      => overflow = v
                    case Style.Prop.Width(v)             => width = v
                    case Style.Prop.Height(v)            => height = v
                    case Style.Prop.MinWidth(v)          => minWidth = v
                    case Style.Prop.MaxWidth(v)          => maxWidth = v
                    case Style.Prop.MinHeight(v)         => minHeight = v
                    case Style.Prop.MaxHeight(v)         => maxHeight = v
                    case Style.Prop.FontWeightProp(w) =>
                        bold = w match
                            case Style.FontWeight.bold | Style.FontWeight.w700 |
                                Style.FontWeight.w800 | Style.FontWeight.w900 => true
                            case _ => false
                    case Style.Prop.FontStyleProp(s) => italic = s == Style.FontStyle.italic
                    case Style.Prop.TextDecorationProp(d) =>
                        underline = d == Style.TextDecoration.underline
                        strikethrough = d == Style.TextDecoration.strikethrough
                    case Style.Prop.TextAlignProp(v)     => textAlign = v
                    case Style.Prop.TextTransformProp(v) => textTransform = v
                    case Style.Prop.TextOverflowProp(v)  => textOverflow = v
                    case Style.Prop.TextWrapProp(v)      => textWrap = v
                    case Style.Prop.LineHeightProp(v)    => lineHeight = math.max(1, v.toInt)
                    case Style.Prop.LetterSpacingProp(v) => letterSpacing = v
                    case Style.Prop.OpacityProp(v)       => opacity = v
                    case Style.Prop.CursorProp(v)        => cursorStyle = v
                    case Style.Prop.PositionProp(v)      => position = v
                    case Style.Prop.TranslateProp(x, y) =>
                        translateX = x
                        translateY = y
                    case Style.Prop.BorderWidthProp(t, r, b, l) =>
                        borderTop = Length.toPx(t)
                        borderRight = Length.toPx(r)
                        borderBottom = Length.toPx(b)
                        borderLeft = Length.toPx(l)
                    case Style.Prop.BorderColorProp(t, r, b, l) =>
                        borderColorTop = RGB.fromStyle(t, parent.bg)
                        borderColorRight = RGB.fromStyle(r, parent.bg)
                        borderColorBottom = RGB.fromStyle(b, parent.bg)
                        borderColorLeft = RGB.fromStyle(l, parent.bg)
                    case Style.Prop.BorderStyleProp(v) => borderStyle = v
                    case Style.Prop.BorderTopProp(w, c) =>
                        borderTop = Length.toPx(w)
                        borderColorTop = RGB.fromStyle(c, parent.bg)
                    case Style.Prop.BorderRightProp(w, c) =>
                        borderRight = Length.toPx(w)
                        borderColorRight = RGB.fromStyle(c, parent.bg)
                    case Style.Prop.BorderBottomProp(w, c) =>
                        borderBottom = Length.toPx(w)
                        borderColorBottom = RGB.fromStyle(c, parent.bg)
                    case Style.Prop.BorderLeftProp(w, c) =>
                        borderLeft = Length.toPx(w)
                        borderColorLeft = RGB.fromStyle(c, parent.bg)
                    case Style.Prop.BorderRadiusProp(tl, tr, br, bl) =>
                        roundTL = Length.toPx(tl).value > 0
                        roundTR = Length.toPx(tr).value > 0
                        roundBR = Length.toPx(br).value > 0
                        roundBL = Length.toPx(bl).value > 0
                    case Style.Prop.ShadowProp(x, y, b, s, c) =>
                        shadowX = Length.toPx(x)
                        shadowY = Length.toPx(y)
                        shadowBlur = Length.toPx(b)
                        shadowSpread = Length.toPx(s)
                        shadowColor = RGB.fromStyle(c, parent.bg)
                    case Style.Prop.BgGradientProp(dir, colors, positions) =>
                        gradientDirection = Maybe(dir)
                        gradientStops = Chunk.from(Array.tabulate(colors.size) { j =>
                            (RGB.fromStyle(colors(j), parent.bg), positions(j))
                        })
                    case Style.Prop.FlexGrowProp(v)   => flexGrow = v
                    case Style.Prop.FlexShrinkProp(v) => flexShrink = v
                    case Style.Prop.BrightnessProp(v) => brightness = v
                    case Style.Prop.ContrastProp(v)   => contrast = v
                    case Style.Prop.GrayscaleProp(v)  => grayscale = v
                    case Style.Prop.SepiaProp(v)      => sepia = v
                    case Style.Prop.InvertProp(v)     => invert = v
                    case Style.Prop.SaturateProp(v)   => saturate = v
                    case Style.Prop.HueRotateProp(v)  => hueRotate = v
                    case Style.Prop.BlurProp(v)       => blur = Length.toPx(v)
                    // Pseudo-states already merged by Lower — skip
                    case _: Style.Prop.HoverProp    => ()
                    case _: Style.Prop.FocusProp    => ()
                    case _: Style.Prop.ActiveProp   => ()
                    case _: Style.Prop.DisabledProp => ()
                    // TUI-irrelevant — skip
                    case _: Style.Prop.FontSizeProp   => ()
                    case _: Style.Prop.FontFamilyProp => ()
                    case Style.Prop.HiddenProp        => ()
                end match
                loop(i + 1)
        loop(0)

        FlatStyle(
            fg,
            bg,
            bold,
            italic,
            underline,
            strikethrough,
            opacity,
            cursorStyle,
            shadowX,
            shadowY,
            shadowBlur,
            shadowSpread,
            shadowColor,
            gradientDirection,
            gradientStops,
            brightness,
            contrast,
            grayscale,
            sepia,
            invert,
            saturate,
            hueRotate,
            blur,
            translateX,
            translateY,
            textAlign,
            textTransform,
            textWrap,
            textOverflow,
            lineHeight,
            letterSpacing,
            padTop,
            padRight,
            padBottom,
            padLeft,
            marTop,
            marRight,
            marBottom,
            marLeft,
            borderTop,
            borderRight,
            borderBottom,
            borderLeft,
            borderStyle,
            borderColorTop,
            borderColorRight,
            borderColorBottom,
            borderColorLeft,
            roundTL,
            roundTR,
            roundBR,
            roundBL,
            direction,
            justify,
            align,
            gap,
            flexGrow,
            flexShrink,
            flexWrap,
            width,
            height,
            minWidth,
            maxWidth,
            minHeight,
            maxHeight,
            overflow,
            scrollTop,
            scrollLeft,
            position
        )
    end resolve

    /** Only inherited properties from parent. No layout, no box model. */
    private def inheritText(parent: FlatStyle): FlatStyle =
        FlatStyle(
            fg = parent.fg,
            bg = RGB.Transparent,
            bold = parent.bold,
            italic = parent.italic,
            underline = parent.underline,
            strikethrough = parent.strikethrough,
            opacity = parent.opacity,
            cursor = Style.Cursor.default_,
            shadowX = Length.zero,
            shadowY = Length.zero,
            shadowBlur = Length.zero,
            shadowSpread = Length.zero,
            shadowColor = RGB.Transparent,
            gradientDirection = Absent,
            gradientStops = Chunk.empty,
            brightness = 1.0,
            contrast = 1.0,
            grayscale = 0.0,
            sepia = 0.0,
            invert = 0.0,
            saturate = 1.0,
            hueRotate = 0.0,
            blur = Length.zero,
            translateX = Length.zero,
            translateY = Length.zero,
            textAlign = parent.textAlign,
            textTransform = parent.textTransform,
            textWrap = parent.textWrap,
            textOverflow = parent.textOverflow,
            lineHeight = parent.lineHeight,
            letterSpacing = parent.letterSpacing,
            padTop = Length.zero,
            padRight = Length.zero,
            padBottom = Length.zero,
            padLeft = Length.zero,
            marTop = Length.zero,
            marRight = Length.zero,
            marBottom = Length.zero,
            marLeft = Length.zero,
            borderTop = Length.zero,
            borderRight = Length.zero,
            borderBottom = Length.zero,
            borderLeft = Length.zero,
            borderStyle = Style.BorderStyle.none,
            borderColorTop = RGB.Transparent,
            borderColorRight = RGB.Transparent,
            borderColorBottom = RGB.Transparent,
            borderColorLeft = RGB.Transparent,
            roundTL = false,
            roundTR = false,
            roundBR = false,
            roundBL = false,
            direction = Style.FlexDirection.row,
            justify = Style.Justification.start,
            align = Style.Alignment.start,
            gap = Length.zero,
            flexGrow = 0.0,
            flexShrink = 1.0,
            flexWrap = Style.FlexWrap.wrap,
            width = Length.Auto,
            height = Length.Auto,
            minWidth = Length.zero,
            maxWidth = Length.Auto,
            minHeight = Length.zero,
            maxHeight = Length.Auto,
            overflow = Style.Overflow.visible,
            scrollTop = 0,
            scrollLeft = 0,
            position = Style.Position.flow
        )

end Styler
