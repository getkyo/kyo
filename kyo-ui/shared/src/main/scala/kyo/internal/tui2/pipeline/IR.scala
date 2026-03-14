package kyo.internal.tui2.pipeline

import kyo.*
import scala.annotation.tailrec

// ---- Tags ----

enum ElemTag derives CanEqual:
    case Div, Span, Popup, Table

// ---- Geometry ----

case class Rect(x: Int, y: Int, w: Int, h: Int) derives CanEqual:
    def intersect(other: Rect): Rect =
        val ix = math.max(x, other.x)
        val iy = math.max(y, other.y)
        val ir = math.min(x + w, other.x + other.w)
        val ib = math.min(y + h, other.y + other.h)
        Rect(ix, iy, math.max(0, ir - ix), math.max(0, ib - iy))
    end intersect
end Rect

// ---- Image ----

case class ImageData(bytes: Span[Byte], width: Int, height: Int, alt: String)

// ---- Widget Identity ----

case class WidgetKey(frame: Frame, dynamicPath: Chunk[String]) derives CanEqual:
    def child(segment: String): WidgetKey =
        WidgetKey(frame, dynamicPath.append(segment))

// ---- Handlers ----

case class Handlers(
    widgetKey: Maybe[WidgetKey],
    id: Maybe[String],
    forId: Maybe[String],
    tabIndex: Maybe[Int],
    disabled: Boolean,
    onClick: Unit < Async,
    onClickSelf: Unit < Async,
    onKeyDown: UI.KeyEvent => Unit < Async,
    onKeyUp: UI.KeyEvent => Unit < Async,
    onInput: String => Unit < Async,
    onChange: Any => Unit < Async,
    onSubmit: Unit < Async,
    onFocus: Unit < Async,
    onBlur: Unit < Async,
    onScroll: Int => Unit < Async,
    colspan: Int,
    rowspan: Int,
    imageData: Maybe[ImageData]
)

object Handlers:
    private val noop: Unit < Async                   = ()
    private val noopKey: UI.KeyEvent => Unit < Async = _ => noop
    private val noopStr: String => Unit < Async      = _ => noop
    private val noopAny: Any => Unit < Async         = _ => noop
    private val noopInt: Int => Unit < Async         = _ => noop

    val empty: Handlers = Handlers(
        widgetKey = Absent,
        id = Absent,
        forId = Absent,
        tabIndex = Absent,
        disabled = false,
        onClick = noop,
        onClickSelf = noop,
        onKeyDown = noopKey,
        onKeyUp = noopKey,
        onInput = noopStr,
        onChange = noopAny,
        onSubmit = noop,
        onFocus = noop,
        onBlur = noop,
        onScroll = noopInt,
        colspan = 1,
        rowspan = 1,
        imageData = Absent
    )
end Handlers

// ---- IR: Resolved (after Lower) ----

enum Resolved:
    case Node(tag: ElemTag, style: Style, handlers: Handlers, children: Chunk[Resolved])
    case Text(value: String)
    case Cursor(charOffset: Int)
end Resolved

// ---- IR: Styled (after Styler) ----

enum Styled:
    case Node(tag: ElemTag, style: FlatStyle, handlers: Handlers, children: Chunk[Styled])
    case Text(value: String, style: FlatStyle)
    case Cursor(charOffset: Int)
end Styled

// ---- IR: Laid (after Layout) ----

enum Laid:
    case Node(
        tag: ElemTag,
        style: FlatStyle,
        handlers: Handlers,
        bounds: Rect,
        content: Rect,
        clip: Rect,
        children: Chunk[Laid]
    )
    case Text(value: String, style: FlatStyle, bounds: Rect, clip: Rect)
    case Cursor(pos: Rect)
end Laid

case class LayoutResult(base: Laid, popups: Chunk[Laid])

// ---- Cell + CellGrid (paint output) ----

case class Cell(
    char: Char,
    fg: PackedColor,
    bg: PackedColor,
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    strikethrough: Boolean,
    dimmed: Boolean
) derives CanEqual

object Cell:
    val Empty: Cell = Cell('\u0000', PackedColor(0, 0, 0), PackedColor(0, 0, 0), false, false, false, false, false)

case class CellGrid(
    width: Int,
    height: Int,
    cells: Array[Cell],
    rawSequences: Chunk[(Rect, Array[Byte])]
)

object CellGrid:
    def empty(w: Int, h: Int): CellGrid =
        CellGrid(w, h, Array.fill(w * h)(Cell.Empty), Chunk.empty)

// ---- FlatStyle ----

case class FlatStyle(
    // Visual (inheritable)
    fg: PackedColor,
    bg: PackedColor,
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    strikethrough: Boolean,
    opacity: Double,
    cursor: Style.Cursor,
    // Shadow (non-inheritable)
    shadowX: Length.Px,
    shadowY: Length.Px,
    shadowBlur: Length.Px,
    shadowSpread: Length.Px,
    shadowColor: PackedColor,
    // Gradient (non-inheritable)
    gradientDirection: Maybe[Style.GradientDirection],
    gradientStops: Chunk[(PackedColor, Double)],
    // Filters (non-inheritable)
    brightness: Double,
    contrast: Double,
    grayscale: Double,
    sepia: Double,
    invert: Double,
    saturate: Double,
    hueRotate: Double,
    blur: Length.Px,
    // Transform (non-inheritable)
    translateX: Length,
    translateY: Length,
    // Text (inheritable)
    textAlign: Style.TextAlign,
    textTransform: Style.TextTransform,
    textWrap: Style.TextWrap,
    textOverflow: Style.TextOverflow,
    lineHeight: Int,
    letterSpacing: Length,
    // Box model (non-inheritable)
    padTop: Length,
    padRight: Length,
    padBottom: Length,
    padLeft: Length,
    marTop: Length,
    marRight: Length,
    marBottom: Length,
    marLeft: Length,
    borderTop: Length.Px,
    borderRight: Length.Px,
    borderBottom: Length.Px,
    borderLeft: Length.Px,
    borderStyle: Style.BorderStyle,
    borderColorTop: PackedColor,
    borderColorRight: PackedColor,
    borderColorBottom: PackedColor,
    borderColorLeft: PackedColor,
    roundTL: Boolean,
    roundTR: Boolean,
    roundBR: Boolean,
    roundBL: Boolean,
    // Layout (non-inheritable)
    direction: Style.FlexDirection,
    justify: Style.Justification,
    align: Style.Alignment,
    gap: Length,
    flexGrow: Double,
    flexShrink: Double,
    flexWrap: Style.FlexWrap,
    width: Length,
    height: Length,
    minWidth: Length,
    maxWidth: Length,
    minHeight: Length,
    maxHeight: Length,
    overflow: Style.Overflow,
    scrollTop: Int,
    scrollLeft: Int,
    position: Style.Position
)

object FlatStyle:
    val Default: FlatStyle = FlatStyle(
        fg = PackedColor(255, 255, 255),
        bg = PackedColor.Transparent,
        bold = false,
        italic = false,
        underline = false,
        strikethrough = false,
        opacity = 1.0,
        cursor = Style.Cursor.default_,
        shadowX = Length.zero,
        shadowY = Length.zero,
        shadowBlur = Length.zero,
        shadowSpread = Length.zero,
        shadowColor = PackedColor.Transparent,
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
        textAlign = Style.TextAlign.left,
        textTransform = Style.TextTransform.none,
        textWrap = Style.TextWrap.wrap,
        textOverflow = Style.TextOverflow.clip,
        lineHeight = 1,
        letterSpacing = Length.zero,
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
        borderColorTop = PackedColor.Transparent,
        borderColorRight = PackedColor.Transparent,
        borderColorBottom = PackedColor.Transparent,
        borderColorLeft = PackedColor.Transparent,
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
    def fromTheme(theme: ResolvedTheme): FlatStyle =
        Default.copy(fg = theme.fg, bg = theme.bg)
end FlatStyle

// ---- ResolvedTheme ----

case class ResolvedTheme(
    variant: Theme,
    fg: PackedColor,
    bg: PackedColor,
    borderColor: Style.Color,
    highlightBg: Style.Color,
    highlightFg: Style.Color
)

object ResolvedTheme:
    def resolve(theme: Theme): ResolvedTheme = theme match
        case Theme.Default =>
            ResolvedTheme(
                variant = Theme.Default,
                fg = PackedColor(255, 255, 255),
                bg = PackedColor.Transparent,
                borderColor = Style.Color.rgb(128, 128, 128),
                highlightBg = Style.Color.rgb(0, 0, 255),
                highlightFg = Style.Color.rgb(255, 255, 255)
            )
        case Theme.Minimal =>
            ResolvedTheme(
                variant = Theme.Minimal,
                fg = PackedColor(255, 255, 255),
                bg = PackedColor.Transparent,
                borderColor = Style.Color.rgb(128, 128, 128),
                highlightBg = Style.Color.rgb(0, 0, 255),
                highlightFg = Style.Color.rgb(255, 255, 255)
            )
        case Theme.Plain =>
            ResolvedTheme(
                variant = Theme.Plain,
                fg = PackedColor(255, 255, 255),
                bg = PackedColor.Transparent,
                borderColor = Style.Color.rgb(128, 128, 128),
                highlightBg = Style.Color.rgb(0, 0, 255),
                highlightFg = Style.Color.rgb(255, 255, 255)
            )
end ResolvedTheme

// ---- Packed Color ----

/** RGB color packed into an Int: bits [23:16] = red, [15:8] = green, [7:0] = blue. Opaque type — prevents confusion with sizes, indices, or
  * other Int values.
  */
opaque type PackedColor = Int

given CanEqual[PackedColor, PackedColor] = CanEqual.derived

object PackedColor:
    val Transparent: PackedColor = -1

    def apply(r: Int, g: Int, b: Int): PackedColor = (r << 16) | (g << 8) | b

    extension (c: PackedColor)
        def r: Int   = (c >> 16) & 0xff
        def g: Int   = (c >> 8) & 0xff
        def b: Int   = c & 0xff
        def raw: Int = c
    end extension

    def fromStyle(c: Style.Color, parentBg: PackedColor): PackedColor =
        c match
            case Style.Color.Transparent  => Transparent
            case Style.Color.Rgb(r, g, b) => PackedColor(r, g, b)
            case Style.Color.Rgba(r, g, b, a) =>
                if a >= 1.0 then PackedColor(r, g, b)
                else if a <= 0.0 then
                    if parentBg == Transparent then PackedColor(0, 0, 0)
                    else parentBg
                else
                    val bgR = if parentBg == Transparent then 0 else parentBg.r
                    val bgG = if parentBg == Transparent then 0 else parentBg.g
                    val bgB = if parentBg == Transparent then 0 else parentBg.b
                    PackedColor(
                        clamp((r * a + bgR * (1 - a)).toInt),
                        clamp((g * a + bgG * (1 - a)).toInt),
                        clamp((b * a + bgB * (1 - a)).toInt)
                    )
            case Style.Color.Hex(hex) =>
                parseHex(hex)

    private def clamp(v: Int): Int =
        if v < 0 then 0 else if v > 255 then 255 else v

    private def parseHex(hex: String): PackedColor =
        val s = if hex.charAt(0) == '#' then hex.substring(1) else hex
        s.length match
            case 3 =>
                val r = Integer.parseInt(s.substring(0, 1), 16)
                val g = Integer.parseInt(s.substring(1, 2), 16)
                val b = Integer.parseInt(s.substring(2, 3), 16)
                PackedColor(r * 17, g * 17, b * 17) // expand 4-bit to 8-bit
            case 4 =>
                val r = Integer.parseInt(s.substring(0, 1), 16)
                val g = Integer.parseInt(s.substring(1, 2), 16)
                val b = Integer.parseInt(s.substring(2, 3), 16)
                PackedColor(r * 17, g * 17, b * 17) // 4th char is alpha — ignored
            case 6 =>
                val r = Integer.parseInt(s.substring(0, 2), 16)
                val g = Integer.parseInt(s.substring(2, 4), 16)
                val b = Integer.parseInt(s.substring(4, 6), 16)
                PackedColor(r, g, b)
            case 8 =>
                val r = Integer.parseInt(s.substring(0, 2), 16)
                val g = Integer.parseInt(s.substring(2, 4), 16)
                val b = Integer.parseInt(s.substring(4, 6), 16)
                PackedColor(r, g, b) // last 2 chars are alpha — ignored
            case _ =>
                PackedColor(0, 0, 0)
        end match
    end parseHex
end PackedColor
