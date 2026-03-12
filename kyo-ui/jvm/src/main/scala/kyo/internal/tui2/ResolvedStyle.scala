package kyo.internal.tui2

import kyo.Maybe
import kyo.Maybe.*
import kyo.Span
import kyo.Style
import kyo.Style.*
import kyo.Style.Prop.*

/** Pre-allocated mutable struct for resolved style properties.
  *
  * One instance lives on RenderCtx. Inherited fields are initialized from RenderCtx at the start of each element; non-inherited fields are
  * reset to defaults. `applyProps` matches each Prop case and writes the corresponding field directly — no intermediate objects.
  */
final private[kyo] class ResolvedStyle:

    // ---- Inherited (initialized from RenderCtx) ----
    var fg: Int                                              = ColorUtils.Absent
    var bg: Int                                              = ColorUtils.Absent
    var bold, italic, underline, strikethrough, dim: Boolean = false

    // ---- Box ----
    var padT, padR, padB, padL: Int                                 = 0
    var marT, marR, marB, marL: Int                                 = 0
    var borderT, borderR, borderB, borderL: Boolean                 = false
    var borderStyle: Int                                            = 0
    var borderColorT, borderColorR, borderColorB, borderColorL: Int = ColorUtils.Absent
    var roundTL, roundTR, roundBR, roundBL: Boolean                 = false

    // ---- Layout ----
    var direction, align, justify, gap, overflow: Int = 0
    var flexGrow: Double                              = 0.0
    var flexShrink: Double                            = 1.0
    var flexWrap: Int                                 = 0 // 0=nowrap, 1=wrap
    var sizeW, sizeH: Int                             = -1
    var minW, maxW, minH, maxH: Int                   = -1
    var transX, transY: Int                           = 0

    // ---- Text ----
    var textAlign, textTransform: Int = 0
    var textWrap: Boolean             = true
    var textOverflow: Boolean         = false

    // ---- Visibility ----
    var hidden, overlay: Boolean = false
    var opacity: Double          = 1.0

    // ---- Shadow ----
    var shadowColor: Int                    = ColorUtils.Absent
    var shadowX, shadowY, shadowSpread: Int = 0

    // ---- Filters ----
    var filterBits: Int           = 0
    val filterVals: Array[Double] = new Array[Double](8)

    // ---- Gradient ----
    var gradientDir: Int                 = -1 // -1=none, maps from GradientDirection.ordinal
    var gradientStops: Int               = 0
    val gradientColors: Array[Int]       = new Array[Int](8)
    val gradientPositions: Array[Double] = new Array[Double](8)

    // ---- Pseudo-state styles ----
    var hoverStyle: Maybe[Style]    = Absent
    var focusStyle: Maybe[Style]    = Absent
    var activeStyle: Maybe[Style]   = Absent
    var disabledStyle: Maybe[Style] = Absent

    /** Initialize inherited fields from RenderCtx and reset non-inherited to defaults. */
    def inherit(ctx: RenderCtx): Unit =
        val inh = ctx.inherited
        fg = inh.fg; bg = inh.bg
        bold = inh.bold; italic = inh.italic
        underline = inh.underline; strikethrough = inh.strikethrough; dim = inh.dim
        textAlign = inh.textAlign; textTransform = inh.textTransform
        textWrap = inh.textWrap; textOverflow = inh.textOverflow

        // Reset non-inherited
        padT = 0; padR = 0; padB = 0; padL = 0
        marT = 0; marR = 0; marB = 0; marL = 0
        borderT = false; borderR = false; borderB = false; borderL = false
        borderStyle = 0
        borderColorT = ColorUtils.Absent; borderColorR = ColorUtils.Absent
        borderColorB = ColorUtils.Absent; borderColorL = ColorUtils.Absent
        roundTL = false; roundTR = false; roundBR = false; roundBL = false
        direction = 0; align = ResolvedStyle.AlignStretch; justify = 0; gap = 0; overflow = 0
        flexGrow = 0.0; flexShrink = 1.0; flexWrap = 0
        sizeW = -1; sizeH = -1
        minW = -1; maxW = -1; minH = -1; maxH = -1
        transX = 0; transY = 0
        hidden = false; overlay = false; opacity = 1.0
        shadowColor = ColorUtils.Absent; shadowX = 0; shadowY = 0; shadowSpread = 0
        filterBits = 0
        gradientDir = -1; gradientStops = 0
        hoverStyle = Absent; focusStyle = Absent
        activeStyle = Absent; disabledStyle = Absent
    end inherit

    /** Apply all props from a Style onto this resolved style. */
    def applyProps(style: Style): Unit =
        import ResolvedStyle.{resolveSize, resolveSizeOr, resolveBorderWidth}
        style.props.foreach { prop =>
            prop match
                case BgColor(color)   => bg = ColorUtils.resolve(color)
                case TextColor(color) => fg = ColorUtils.resolve(color)
                case Padding(t, r, b, l) =>
                    padT = resolveSize(t); padR = resolveSize(r)
                    padB = resolveSize(b); padL = resolveSize(l)
                case Margin(t, r, b, l) =>
                    marT = resolveSize(t); marR = resolveSize(r)
                    marB = resolveSize(b); marL = resolveSize(l)
                case Gap(value)       => gap = resolveSize(value)
                case Width(value)     => sizeW = resolveSizeOr(value, -1)
                case Height(value)    => sizeH = resolveSizeOr(value, -1)
                case MinWidth(value)  => minW = resolveSizeOr(value, -1)
                case MaxWidth(value)  => maxW = resolveSizeOr(value, -1)
                case MinHeight(value) => minH = resolveSizeOr(value, -1)
                case MaxHeight(value) => maxH = resolveSizeOr(value, -1)

                case FlexDirectionProp(value) =>
                    direction = value match
                        case FlexDirection.row    => ResolvedStyle.DirRow
                        case FlexDirection.column => ResolvedStyle.DirColumn

                case FlexWrapProp(value) =>
                    flexWrap = value match
                        case FlexWrap.wrap   => 1
                        case FlexWrap.noWrap => 0

                case Align(value) =>
                    align = value match
                        case Alignment.start    => ResolvedStyle.AlignStart
                        case Alignment.center   => ResolvedStyle.AlignCenter
                        case Alignment.end      => ResolvedStyle.AlignEnd
                        case Alignment.stretch  => ResolvedStyle.AlignStretch
                        case Alignment.baseline => ResolvedStyle.AlignStart

                case Justify(value) =>
                    justify = value match
                        case Justification.start        => ResolvedStyle.JustStart
                        case Justification.center       => ResolvedStyle.JustCenter
                        case Justification.end          => ResolvedStyle.JustEnd
                        case Justification.spaceBetween => ResolvedStyle.JustBetween
                        case Justification.spaceAround  => ResolvedStyle.JustAround
                        case Justification.spaceEvenly  => ResolvedStyle.JustEvenly

                case OverflowProp(value) =>
                    overflow = value match
                        case Overflow.visible => 0
                        case Overflow.hidden  => 1
                        case Overflow.scroll  => 2
                        case Overflow.auto    => 2

                case FontSizeProp(_) => () // no-op in TUI (always 1 cell per char)
                case FontWeightProp(value) =>
                    value match
                        case FontWeight.bold | FontWeight.w600 | FontWeight.w700 | FontWeight.w800 | FontWeight.w900 =>
                            bold = true
                        case FontWeight.w100 | FontWeight.w200 | FontWeight.w300 =>
                            dim = true
                        case _ => ()
                case FontStyleProp(value) =>
                    if value == FontStyle.italic then italic = true
                case FontFamilyProp(_) => ()
                case TextAlignProp(value) =>
                    textAlign = value match
                        case TextAlign.left    => ResolvedStyle.TextAlignLeft
                        case TextAlign.center  => ResolvedStyle.TextAlignCenter
                        case TextAlign.right   => ResolvedStyle.TextAlignRight
                        case TextAlign.justify => ResolvedStyle.TextAlignJustify

                case TextDecorationProp(value) =>
                    value match
                        case TextDecoration.underline     => underline = true
                        case TextDecoration.strikethrough => strikethrough = true
                        case TextDecoration.none          => ()

                case LineHeightProp(_)    => ()
                case LetterSpacingProp(_) => ()
                case TextTransformProp(value) =>
                    textTransform = value match
                        case TextTransform.none       => 0
                        case TextTransform.uppercase  => 1
                        case TextTransform.lowercase  => 2
                        case TextTransform.capitalize => 3

                case TextOverflowProp(value) =>
                    textOverflow = value == TextOverflow.ellipsis

                case TextWrapProp(value) =>
                    value match
                        case TextWrap.wrap =>
                            textWrap = true; textOverflow = false
                        case TextWrap.noWrap =>
                            textWrap = false; textOverflow = false
                        case TextWrap.ellipsis =>
                            textWrap = false; textOverflow = true

                case BorderStyleProp(value) =>
                    borderStyle = value match
                        case BorderStyle.none   => ResolvedStyle.BorderNone
                        case BorderStyle.solid  => ResolvedStyle.BorderSolid
                        case BorderStyle.dashed => ResolvedStyle.BorderDashed
                        case BorderStyle.dotted => ResolvedStyle.BorderDotted

                case BorderWidthProp(t, r, b, l) =>
                    borderT = resolveBorderWidth(t)
                    borderR = resolveBorderWidth(r)
                    borderB = resolveBorderWidth(b)
                    borderL = resolveBorderWidth(l)

                case BorderColorProp(t, r, b, l) =>
                    borderColorT = ColorUtils.resolve(t)
                    borderColorR = ColorUtils.resolve(r)
                    borderColorB = ColorUtils.resolve(b)
                    borderColorL = ColorUtils.resolve(l)

                case BorderTopProp(width, color) =>
                    borderT = resolveBorderWidth(width)
                    borderColorT = ColorUtils.resolve(color)
                case BorderRightProp(width, color) =>
                    borderR = resolveBorderWidth(width)
                    borderColorR = ColorUtils.resolve(color)
                case BorderBottomProp(width, color) =>
                    borderB = resolveBorderWidth(width)
                    borderColorB = ColorUtils.resolve(color)
                case BorderLeftProp(width, color) =>
                    borderL = resolveBorderWidth(width)
                    borderColorL = ColorUtils.resolve(color)

                case BorderRadiusProp(tl, tr, br, bl) =>
                    roundTL = resolveBorderWidth(tl)
                    roundTR = resolveBorderWidth(tr)
                    roundBR = resolveBorderWidth(br)
                    roundBL = resolveBorderWidth(bl)

                case OpacityProp(value) => opacity = value

                case ShadowProp(sx, sy, sblur, sspread, color) =>
                    shadowColor = ColorUtils.resolve(color)
                    shadowX = resolveSize(sx)
                    shadowY = resolveSize(sy)
                    shadowSpread = resolveSize(sspread)

                case TranslateProp(x, y) =>
                    transX = resolveSize(x)
                    transY = resolveSize(y)

                case PositionProp(value) =>
                    value match
                        case Position.overlay => overlay = true
                        case Position.flow    => overlay = false

                case HiddenProp => hidden = true

                case FlexGrowProp(value)   => flexGrow = value
                case FlexShrinkProp(value) => flexShrink = value

                case BrightnessProp(value) =>
                    filterBits = filterBits | (1 << 0); filterVals(0) = value
                case ContrastProp(value) =>
                    filterBits = filterBits | (1 << 1); filterVals(1) = value
                case GrayscaleProp(value) =>
                    filterBits = filterBits | (1 << 2); filterVals(2) = value
                case SepiaProp(value) =>
                    filterBits = filterBits | (1 << 3); filterVals(3) = value
                case InvertProp(value) =>
                    filterBits = filterBits | (1 << 4); filterVals(4) = value
                case SaturateProp(value) =>
                    filterBits = filterBits | (1 << 5); filterVals(5) = value
                case HueRotateProp(value) =>
                    filterBits = filterBits | (1 << 6); filterVals(6) = value
                case BlurProp(value) =>
                    filterBits = filterBits | (1 << 7); filterVals(7) = resolveSize(value).toDouble

                case BgGradientProp(dir, colors, positions) =>
                    if colors.size >= 2 then
                        gradientDir = dir.ordinal
                        gradientStops = math.min(colors.size, 8)
                        var gi = 0
                        while gi < gradientStops do
                            gradientColors(gi) = ColorUtils.resolve(colors(gi))
                            gradientPositions(gi) =
                                if gi < positions.size then positions(gi)
                                else gi.toDouble / (gradientStops - 1)
                            gi += 1
                        end while
                    else if colors.size == 1 then
                        bg = ColorUtils.resolve(colors(0))

                case HoverProp(s)    => hoverStyle = Present(s)
                case FocusProp(s)    => focusStyle = Present(s)
                case ActiveProp(s)   => activeStyle = Present(s)
                case DisabledProp(s) => disabledStyle = Present(s)

                case CursorProp(_) => ()
        }
    end applyProps

    /** Build a CellStyle from the current resolved state. Zero-alloc (CellStyle is opaque Long). */
    def cellStyle: CellStyle =
        CellStyle(fg, bg, bold, italic, underline, strikethrough, dim)

end ResolvedStyle

private[kyo] object ResolvedStyle:

    // ---- Direction constants ----
    inline val DirColumn = 0
    inline val DirRow    = 1

    // ---- Alignment constants ----
    inline val AlignStart   = 0
    inline val AlignCenter  = 1
    inline val AlignEnd     = 2
    inline val AlignStretch = 3

    // ---- Justification constants ----
    inline val JustStart   = 0
    inline val JustCenter  = 1
    inline val JustEnd     = 2
    inline val JustBetween = 3
    inline val JustAround  = 4
    inline val JustEvenly  = 5

    // ---- Text align constants ----
    inline val TextAlignLeft    = 0
    inline val TextAlignCenter  = 1
    inline val TextAlignRight   = 2
    inline val TextAlignJustify = 3

    // ---- Border style constants ----
    inline val BorderNone   = 0
    inline val BorderSolid  = 1
    inline val BorderDashed = 2
    inline val BorderDotted = 3

    /** Scale factor: 1 terminal cell = 8 CSS pixels. */
    private inline val PxPerCell = 8.0

    /** Resolve a Size to cell count. Auto -> 0. */
    private[tui2] def resolveSize(size: Style.Size): Int =
        size match
            case Style.Size.Px(v)  => math.max(0, math.round(v / PxPerCell).toInt)
            case Style.Size.Em(v)  => math.max(0, v.toInt)
            case Style.Size.Pct(_) => 0 // Pct needs parent dimension — resolved in layout
            case Style.Size.Auto   => 0

    /** Resolve a Size to cell count with a fallback for Auto. Pct is encoded as -(v+1) for later resolution against parent. */
    private[tui2] def resolveSizeOr(size: Style.Size, fallback: Int): Int =
        size match
            case Style.Size.Px(v)  => math.max(0, math.round(v / PxPerCell).toInt)
            case Style.Size.Em(v)  => math.max(0, v.toInt)
            case Style.Size.Pct(v) => -(v.toInt + 1)
            case Style.Size.Auto   => fallback

    /** Resolve border width: any non-zero -> true. */
    private def resolveBorderWidth(size: Style.Size): Boolean =
        size match
            case Style.Size.Auto   => false
            case Style.Size.Px(v)  => v > 0
            case Style.Size.Em(v)  => v > 0
            case Style.Size.Pct(v) => v > 0

end ResolvedStyle
