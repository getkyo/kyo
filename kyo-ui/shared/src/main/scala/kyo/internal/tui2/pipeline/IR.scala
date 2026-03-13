package kyo.internal.tui2.pipeline

import kyo.*
import scala.annotation.tailrec

// ---- Tags ----

enum ElemTag derives CanEqual:
    case Div, Span, Popup, Table

// ---- Geometry ----

case class Rect(x: Int, y: Int, w: Int, h: Int) derives CanEqual

// ---- Image ----

case class ImageData(bytes: IArray[Byte], width: Int, height: Int, alt: String)

// ---- Widget Identity ----

case class WidgetKey(frame: Frame, dynamicPath: Chunk[String]) derives CanEqual

object WidgetKey:
    def child(parent: WidgetKey, segment: String): WidgetKey =
        WidgetKey(parent.frame, parent.dynamicPath.append(segment))
end WidgetKey

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
    case Node(tag: ElemTag, computed: ComputedStyle, handlers: Handlers, children: Chunk[Styled])
    case Text(value: String, computed: ComputedStyle)
    case Cursor(charOffset: Int)
end Styled

// ---- IR: Laid (after Layout) ----

enum Laid:
    case Node(
        tag: ElemTag,
        computed: ComputedStyle,
        handlers: Handlers,
        bounds: Rect,
        content: Rect,
        clip: Rect,
        children: Chunk[Laid]
    )
    case Text(value: String, computed: ComputedStyle, bounds: Rect, clip: Rect)
    case Cursor(pos: Rect)
end Laid

case class LayoutResult(base: Laid, popups: Chunk[Laid])

// ---- Cell + CellGrid (paint output) ----

case class Cell(
    char: Char,
    fg: Int,
    bg: Int,
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    strikethrough: Boolean,
    dimmed: Boolean
) derives CanEqual

object Cell:
    val Empty: Cell = Cell('\u0000', 0, 0, false, false, false, false, false)

case class CellGrid(
    width: Int,
    height: Int,
    cells: Array[Cell],
    rawSequences: Chunk[(Rect, Array[Byte])]
)

object CellGrid:
    def empty(w: Int, h: Int): CellGrid =
        CellGrid(w, h, Array.fill(w * h)(Cell.Empty), Chunk.empty)

// ---- ComputedStyle ----

case class ComputedStyle(
    // Visual (inheritable)
    fg: Int,
    bg: Int,
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    strikethrough: Boolean,
    opacity: Double,
    cursor: Int,
    // Shadow (non-inheritable)
    shadowX: Int,
    shadowY: Int,
    shadowBlur: Int,
    shadowSpread: Int,
    shadowColor: Int,
    // Gradient (non-inheritable)
    gradientDirection: Int,
    gradientStops: Chunk[(Int, Double)],
    // Filters (non-inheritable)
    brightness: Double,
    contrast: Double,
    grayscale: Double,
    sepia: Double,
    invert: Double,
    saturate: Double,
    hueRotate: Double,
    blur: Int,
    // Transform (non-inheritable)
    translateX: Int,
    translateY: Int,
    // Text (inheritable)
    textAlign: Int,
    textTransform: Int,
    textWrap: Int,
    textOverflow: Int,
    lineHeight: Int,
    letterSpacing: Int,
    // Box model (non-inheritable, size-encoded)
    padTop: Int,
    padRight: Int,
    padBottom: Int,
    padLeft: Int,
    marTop: Int,
    marRight: Int,
    marBottom: Int,
    marLeft: Int,
    borderTop: Int,
    borderRight: Int,
    borderBottom: Int,
    borderLeft: Int,
    borderStyle: Int,
    borderColorTop: Int,
    borderColorRight: Int,
    borderColorBottom: Int,
    borderColorLeft: Int,
    roundTL: Boolean,
    roundTR: Boolean,
    roundBR: Boolean,
    roundBL: Boolean,
    // Layout (non-inheritable, size-encoded)
    direction: Int,
    justify: Int,
    align: Int,
    gap: Int,
    flexGrow: Double,
    flexShrink: Double,
    flexWrap: Int,
    width: Int,
    height: Int,
    minWidth: Int,
    maxWidth: Int,
    minHeight: Int,
    maxHeight: Int,
    overflow: Int,
    scrollTop: Int,
    scrollLeft: Int,
    position: Int
)

object ComputedStyle:
    val Default: ComputedStyle = ComputedStyle(
        fg = ColorEnc.pack(255, 255, 255),
        bg = ColorEnc.Transparent,
        bold = false,
        italic = false,
        underline = false,
        strikethrough = false,
        opacity = 1.0,
        cursor = 0,
        shadowX = 0,
        shadowY = 0,
        shadowBlur = 0,
        shadowSpread = 0,
        shadowColor = ColorEnc.Transparent,
        gradientDirection = 0,
        gradientStops = Chunk.empty,
        brightness = 1.0,
        contrast = 1.0,
        grayscale = 0.0,
        sepia = 0.0,
        invert = 0.0,
        saturate = 1.0,
        hueRotate = 0.0,
        blur = 0,
        translateX = 0,
        translateY = 0,
        textAlign = 0,
        textTransform = 0,
        textWrap = 0,
        textOverflow = 0,
        lineHeight = 1,
        letterSpacing = 0,
        padTop = 0,
        padRight = 0,
        padBottom = 0,
        padLeft = 0,
        marTop = 0,
        marRight = 0,
        marBottom = 0,
        marLeft = 0,
        borderTop = 0,
        borderRight = 0,
        borderBottom = 0,
        borderLeft = 0,
        borderStyle = 0,
        borderColorTop = ColorEnc.Transparent,
        borderColorRight = ColorEnc.Transparent,
        borderColorBottom = ColorEnc.Transparent,
        borderColorLeft = ColorEnc.Transparent,
        roundTL = false,
        roundTR = false,
        roundBR = false,
        roundBL = false,
        direction = 0,
        justify = 0,
        align = 0,
        gap = 0,
        flexGrow = 0.0,
        flexShrink = 1.0,
        flexWrap = 0,
        width = SizeEnc.Auto,
        height = SizeEnc.Auto,
        minWidth = 0,
        maxWidth = SizeEnc.Auto,
        minHeight = 0,
        maxHeight = SizeEnc.Auto,
        overflow = 0,
        scrollTop = 0,
        scrollLeft = 0,
        position = 0
    )
    def fromTheme(theme: ResolvedTheme): ComputedStyle =
        Default.copy(fg = theme.fg, bg = theme.bg)
end ComputedStyle

// ---- ResolvedTheme ----

case class ResolvedTheme(
    variant: Theme,
    fg: Int,
    bg: Int,
    borderColor: Style.Color,
    highlightBg: Style.Color,
    highlightFg: Style.Color
)

object ResolvedTheme:
    def resolve(theme: Theme): ResolvedTheme = theme match
        case Theme.Default =>
            ResolvedTheme(
                variant = Theme.Default,
                fg = ColorEnc.pack(255, 255, 255),
                bg = ColorEnc.Transparent,
                borderColor = Style.Color.rgb(128, 128, 128),
                highlightBg = Style.Color.rgb(0, 0, 255),
                highlightFg = Style.Color.rgb(255, 255, 255)
            )
        case Theme.Minimal =>
            ResolvedTheme(
                variant = Theme.Minimal,
                fg = ColorEnc.pack(255, 255, 255),
                bg = ColorEnc.Transparent,
                borderColor = Style.Color.rgb(128, 128, 128),
                highlightBg = Style.Color.rgb(0, 0, 255),
                highlightFg = Style.Color.rgb(255, 255, 255)
            )
        case Theme.Plain =>
            ResolvedTheme(
                variant = Theme.Plain,
                fg = ColorEnc.pack(255, 255, 255),
                bg = ColorEnc.Transparent,
                borderColor = Style.Color.rgb(128, 128, 128),
                highlightBg = Style.Color.rgb(0, 0, 255),
                highlightFg = Style.Color.rgb(255, 255, 255)
            )
end ResolvedTheme

// ---- Size Encoding ----

object SizeEnc:
    val Auto: Int = Int.MinValue

    def px(v: Int): Int = v

    def pct(v: Double): Int = -(v * 100).toInt

    def isPct(v: Int): Boolean = v < 0 && v > Int.MinValue / 2

    def isAuto(v: Int): Boolean = v == Int.MinValue

    def resolve(v: Int, parentPx: Int): Int =
        if v >= 0 then v
        else if isAuto(v) then parentPx
        else ((-v) * parentPx) / 10000
end SizeEnc

// ---- Color Encoding ----

object ColorEnc:
    val Transparent: Int = -1

    def pack(r: Int, g: Int, b: Int): Int = (r << 16) | (g << 8) | b

    def r(c: Int): Int = (c >> 16) & 0xff
    def g(c: Int): Int = (c >> 8) & 0xff
    def b(c: Int): Int = c & 0xff

    def fromStyle(c: Style.Color, parentBg: Int): Int =
        c match
            case Style.Color.Transparent  => Transparent
            case Style.Color.Rgb(r, g, b) => pack(r, g, b)
            case Style.Color.Rgba(r, g, b, a) =>
                if a >= 1.0 then pack(r, g, b)
                else if a <= 0.0 then
                    if parentBg == Transparent then pack(0, 0, 0)
                    else parentBg
                else
                    val bgR = if parentBg == Transparent then 0 else ColorEnc.r(parentBg)
                    val bgG = if parentBg == Transparent then 0 else ColorEnc.g(parentBg)
                    val bgB = if parentBg == Transparent then 0 else ColorEnc.b(parentBg)
                    pack(
                        clamp((r * a + bgR * (1 - a)).toInt),
                        clamp((g * a + bgG * (1 - a)).toInt),
                        clamp((b * a + bgB * (1 - a)).toInt)
                    )
            case Style.Color.Hex(hex) =>
                parseHex(hex)

    private def clamp(v: Int): Int =
        if v < 0 then 0 else if v > 255 then 255 else v

    private def parseHex(hex: String): Int =
        val s = if hex.charAt(0) == '#' then hex.substring(1) else hex
        s.length match
            case 3 =>
                val r = Integer.parseInt(s.substring(0, 1), 16)
                val g = Integer.parseInt(s.substring(1, 2), 16)
                val b = Integer.parseInt(s.substring(2, 3), 16)
                pack(r * 17, g * 17, b * 17)
            case 4 =>
                val r = Integer.parseInt(s.substring(0, 1), 16)
                val g = Integer.parseInt(s.substring(1, 2), 16)
                val b = Integer.parseInt(s.substring(2, 3), 16)
                // 4th char is alpha — ignore for packed RGB
                pack(r * 17, g * 17, b * 17)
            case 6 =>
                val r = Integer.parseInt(s.substring(0, 2), 16)
                val g = Integer.parseInt(s.substring(2, 4), 16)
                val b = Integer.parseInt(s.substring(4, 6), 16)
                pack(r, g, b)
            case 8 =>
                val r = Integer.parseInt(s.substring(0, 2), 16)
                val g = Integer.parseInt(s.substring(2, 4), 16)
                val b = Integer.parseInt(s.substring(4, 6), 16)
                // last 2 chars are alpha — ignore for packed RGB
                pack(r, g, b)
            case _ =>
                pack(0, 0, 0)
        end match
    end parseHex
end ColorEnc
