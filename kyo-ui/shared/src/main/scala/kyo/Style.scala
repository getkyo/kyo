package kyo

import scala.annotation.tailrec
import scala.language.implicitConversions

final case class Style private[kyo] (props: Span[Style.Prop]) derives CanEqual:

    import Style.*
    import Style.Prop.*

    private def appendProp(p: Prop): Style =
        val cls = p.getClass
        Style(props.filter(x => !(x.getClass eq cls)) :+ p)

    private inline def clampSize(s: Size): Size = s match
        case Size.Px(v)  => if v < 0 then Size.Px(0) else s
        case Size.Pct(v) => if v < 0 then Size.Pct(0) else s
        case Size.Em(v)  => if v < 0 then Size.Em(0) else s
        case Size.Auto   => s

    private inline def clampSizeMin1(s: Size): Size = s match
        case Size.Px(v) => if v < 1 then Size.Px(1) else s
        case Size.Em(v) => if v < 0.1 then Size.Em(0.1) else s
        case _          => s

    // Composition

    def ++(other: Style): Style =
        if other.isEmpty then this
        else if isEmpty then other
        else
            @tailrec def loop(result: Span[Prop], i: Int): Span[Prop] =
                if i >= other.props.size then result
                else
                    val p   = other.props(i)
                    val cls = p.getClass
                    loop(result.filter(x => !(x.getClass eq cls)) :+ p, i + 1)
            Style(loop(props, 0))

    // Pseudo-states

    def hover(s: Style): Style                  = appendProp(HoverProp(s))
    def hover(f: Style.type => Style): Style    = hover(f(Style))
    def focus(s: Style): Style                  = appendProp(FocusProp(s))
    def focus(f: Style.type => Style): Style    = focus(f(Style))
    def active(s: Style): Style                 = appendProp(ActiveProp(s))
    def active(f: Style.type => Style): Style   = active(f(Style))
    def disabled(s: Style): Style               = appendProp(DisabledProp(s))
    def disabled(f: Style.type => Style): Style = disabled(f(Style))

    def isEmpty: Boolean  = props.isEmpty
    def nonEmpty: Boolean = props.nonEmpty

    def find[A <: Prop](using tag: ConcreteTag[A]): Maybe[A] =
        @tailrec def loop(i: Int): Maybe[A] =
            if i >= props.size then Absent
            else
                props(i) match
                    case tag(a) => Present(a)
                    case _      => loop(i + 1)
        loop(0)
    end find

    def filter(f: Prop => Boolean): Style = Style(props.filter(f))

    def without[A <: Prop](using tag: ConcreteTag[A]): Style =
        Style(props.filter(p => !tag.accepts(p)))

    // Background

    def bg(c: Color): Style               = appendProp(Prop.BgColor(c))
    def bg(hex: String): Style            = bg(Color.hex(hex))
    def bg(f: Color.type => Color): Style = bg(f(Color))

    // Text color

    def color(c: Color): Style               = appendProp(Prop.TextColor(c))
    def color(hex: String): Style            = color(Color.hex(hex))
    def color(f: Color.type => Color): Style = color(f(Color))

    // Padding — px, pct, or em (no auto)

    def padding(all: Size.Px | Size.Pct | Size.Em): Style =
        val c = clampSize(all); appendProp(Prop.Padding(c, c, c, c))
    def padding(vertical: Size.Px | Size.Pct | Size.Em, horizontal: Size.Px | Size.Pct | Size.Em): Style =
        val v = clampSize(vertical); val h = clampSize(horizontal); appendProp(Prop.Padding(v, h, v, h))
    def padding(
        top: Size.Px | Size.Pct | Size.Em,
        right: Size.Px | Size.Pct | Size.Em,
        bottom: Size.Px | Size.Pct | Size.Em,
        left: Size.Px | Size.Pct | Size.Em
    ): Style =
        appendProp(Prop.Padding(clampSize(top), clampSize(right), clampSize(bottom), clampSize(left)))

    // Margin — any size including auto

    def margin(all: Size): Style                        = appendProp(Prop.Margin(all, all, all, all))
    def margin(vertical: Size, horizontal: Size): Style = appendProp(Prop.Margin(vertical, horizontal, vertical, horizontal))
    def margin(top: Size, right: Size, bottom: Size, left: Size): Style = appendProp(Prop.Margin(top, right, bottom, left))

    // Gap — px or em

    def gap(v: Size.Px | Size.Em): Style = appendProp(Prop.Gap(clampSize(v)))

    // Layout direction

    def row: Style    = appendProp(Prop.FlexDirectionProp(FlexDirection.row))
    def column: Style = appendProp(Prop.FlexDirectionProp(FlexDirection.column))

    def flexWrap(v: FlexWrap): Style                  = appendProp(Prop.FlexWrapProp(v))
    def flexWrap(f: FlexWrap.type => FlexWrap): Style = flexWrap(f(FlexWrap))

    // Alignment

    def align(v: Alignment): Style                             = appendProp(Prop.Align(v))
    def align(f: Alignment.type => Alignment): Style           = align(f(Alignment))
    def justify(v: Justification): Style                       = appendProp(Prop.Justify(v))
    def justify(f: Justification.type => Justification): Style = justify(f(Justification))

    // Overflow

    def overflow(v: Overflow): Style                  = appendProp(Prop.OverflowProp(v))
    def overflow(f: Overflow.type => Overflow): Style = overflow(f(Overflow))

    // Sizing — any size including auto

    def width(v: Size): Style     = appendProp(Prop.Width(clampSize(v)))
    def height(v: Size): Style    = appendProp(Prop.Height(clampSize(v)))
    def minWidth(v: Size): Style  = appendProp(Prop.MinWidth(clampSize(v)))
    def maxWidth(v: Size): Style  = appendProp(Prop.MaxWidth(clampSize(v)))
    def minHeight(v: Size): Style = appendProp(Prop.MinHeight(clampSize(v)))
    def maxHeight(v: Size): Style = appendProp(Prop.MaxHeight(clampSize(v)))

    // Typography

    def fontSize(v: Size.Px | Size.Em): Style = appendProp(Prop.FontSizeProp(clampSizeMin1(v)))

    def fontWeight(v: FontWeight): Style                    = appendProp(Prop.FontWeightProp(v))
    def fontWeight(f: FontWeight.type => FontWeight): Style = fontWeight(f(FontWeight))
    def bold: Style                                         = fontWeight(FontWeight.bold)

    def fontStyle(v: FontStyle): Style                   = appendProp(Prop.FontStyleProp(v))
    def fontStyle(f: FontStyle.type => FontStyle): Style = fontStyle(f(FontStyle))
    def italic: Style                                    = fontStyle(FontStyle.italic)

    def fontFamily(v: String): Style = appendProp(Prop.FontFamilyProp(v))

    def textAlign(v: TextAlign): Style                   = appendProp(Prop.TextAlignProp(v))
    def textAlign(f: TextAlign.type => TextAlign): Style = textAlign(f(TextAlign))

    def textDecoration(v: TextDecoration): Style                        = appendProp(Prop.TextDecorationProp(v))
    def textDecoration(f: TextDecoration.type => TextDecoration): Style = textDecoration(f(TextDecoration))
    def underline: Style                                                = textDecoration(TextDecoration.underline)
    def strikethrough: Style                                            = textDecoration(TextDecoration.strikethrough)

    def lineHeight(v: Double): Style = appendProp(Prop.LineHeightProp(math.max(0.1, v)))

    def letterSpacing(v: Size.Px | Size.Em): Style = appendProp(Prop.LetterSpacingProp(v))

    def textTransform(v: TextTransform): Style                       = appendProp(Prop.TextTransformProp(v))
    def textTransform(f: TextTransform.type => TextTransform): Style = textTransform(f(TextTransform))

    def textOverflow(v: TextOverflow): Style                      = appendProp(Prop.TextOverflowProp(v))
    def textOverflow(f: TextOverflow.type => TextOverflow): Style = textOverflow(f(TextOverflow))

    def textWrap(v: TextWrap): Style                  = appendProp(Prop.TextWrapProp(v))
    def textWrap(f: TextWrap.type => TextWrap): Style = textWrap(f(TextWrap))

    // Borders — width is px only

    def border(width: Size.Px, style: BorderStyle, c: Color): Style =
        val w = clampSize(width)
        appendProp(Prop.BorderWidthProp(w, w, w, w))
            .appendProp(Prop.BorderStyleProp(style))
            .appendProp(Prop.BorderColorProp(c, c, c, c))
    end border

    def border(width: Size.Px, style: BorderStyle, hex: String): Style            = border(width, style, Color.hex(hex))
    def border(width: Size.Px, style: BorderStyle, f: Color.type => Color): Style = border(width, style, f(Color))
    def border(width: Size.Px, c: Color): Style                                   = border(width, BorderStyle.solid, c)
    def border(width: Size.Px, hex: String): Style                                = border(width, BorderStyle.solid, Color.hex(hex))
    def border(width: Size.Px, f: Color.type => Color): Style                     = border(width, BorderStyle.solid, f(Color))

    def borderColor(c: Color): Style               = appendProp(Prop.BorderColorProp(c, c, c, c))
    def borderColor(hex: String): Style            = borderColor(Color.hex(hex))
    def borderColor(f: Color.type => Color): Style = borderColor(f(Color))
    def borderColor(top: Color, right: Color, bottom: Color, left: Color): Style =
        appendProp(Prop.BorderColorProp(top, right, bottom, left))

    def borderWidth(v: Size.Px): Style =
        val c = clampSize(v)
        appendProp(Prop.BorderWidthProp(c, c, c, c))
    def borderWidth(top: Size.Px, right: Size.Px, bottom: Size.Px, left: Size.Px): Style =
        appendProp(Prop.BorderWidthProp(clampSize(top), clampSize(right), clampSize(bottom), clampSize(left)))

    def borderStyle(v: BorderStyle): Style                     = appendProp(Prop.BorderStyleProp(v))
    def borderStyle(f: BorderStyle.type => BorderStyle): Style = borderStyle(f(BorderStyle))

    def borderTop(width: Size.Px, c: Color): Style               = appendProp(Prop.BorderTopProp(clampSize(width), c))
    def borderTop(width: Size.Px, hex: String): Style            = borderTop(width, Color.hex(hex))
    def borderTop(width: Size.Px, f: Color.type => Color): Style = borderTop(width, f(Color))

    def borderRight(width: Size.Px, c: Color): Style               = appendProp(Prop.BorderRightProp(clampSize(width), c))
    def borderRight(width: Size.Px, hex: String): Style            = borderRight(width, Color.hex(hex))
    def borderRight(width: Size.Px, f: Color.type => Color): Style = borderRight(width, f(Color))

    def borderBottom(width: Size.Px, c: Color): Style               = appendProp(Prop.BorderBottomProp(clampSize(width), c))
    def borderBottom(width: Size.Px, hex: String): Style            = borderBottom(width, Color.hex(hex))
    def borderBottom(width: Size.Px, f: Color.type => Color): Style = borderBottom(width, f(Color))

    def borderLeft(width: Size.Px, c: Color): Style               = appendProp(Prop.BorderLeftProp(clampSize(width), c))
    def borderLeft(width: Size.Px, hex: String): Style            = borderLeft(width, Color.hex(hex))
    def borderLeft(width: Size.Px, f: Color.type => Color): Style = borderLeft(width, f(Color))

    // Border radius — px or pct

    def rounded(v: Size.Px | Size.Pct): Style =
        val c = clampSize(v)
        appendProp(Prop.BorderRadiusProp(c, c, c, c))
    def rounded(
        topLeft: Size.Px | Size.Pct,
        topRight: Size.Px | Size.Pct,
        bottomRight: Size.Px | Size.Pct,
        bottomLeft: Size.Px | Size.Pct
    ): Style =
        appendProp(Prop.BorderRadiusProp(clampSize(topLeft), clampSize(topRight), clampSize(bottomRight), clampSize(bottomLeft)))

    // Effects

    def shadow(
        x: Size.Px = Size.zero,
        y: Size.Px = Size.zero,
        blur: Size.Px = Size.zero,
        spread: Size.Px = Size.zero,
        c: Color = Color.rgba(0, 0, 0, 0.25)
    ): Style = appendProp(Prop.ShadowProp(x, y, clampSize(blur), spread, c))

    def shadow(x: Size.Px, y: Size.Px, blur: Size.Px, spread: Size.Px, f: Color.type => Color): Style =
        shadow(x, y, blur, spread, f(Color))

    def opacity(v: Double): Style = appendProp(Prop.OpacityProp(math.max(0.0, math.min(1.0, v))))

    // Cursor

    def cursor(v: Cursor): Style                = appendProp(Prop.CursorProp(v))
    def cursor(f: Cursor.type => Cursor): Style = cursor(f(Cursor))

    // Transform — px or pct

    def translate(x: Size.Px | Size.Pct, y: Size.Px | Size.Pct): Style = appendProp(Prop.TranslateProp(x, y))

    // Position

    def position(v: Position): Style                  = appendProp(Prop.PositionProp(v))
    def position(f: Position.type => Position): Style = position(f(Position))

    // Flex grow/shrink

    def flexGrow(v: Double): Style   = appendProp(Prop.FlexGrowProp(math.max(0.0, v)))
    def flexShrink(v: Double): Style = appendProp(Prop.FlexShrinkProp(math.max(0.0, v)))

    // Visibility

    def displayNone: Style = appendProp(Prop.HiddenProp)

    // Filters

    def brightness(v: Double): Style = appendProp(Prop.BrightnessProp(math.max(0.0, v)))
    def contrast(v: Double): Style   = appendProp(Prop.ContrastProp(math.max(0.0, v)))
    def grayscale(v: Double): Style  = appendProp(Prop.GrayscaleProp(math.max(0.0, math.min(1.0, v))))
    def sepia(v: Double): Style      = appendProp(Prop.SepiaProp(math.max(0.0, math.min(1.0, v))))
    def invert(v: Double): Style     = appendProp(Prop.InvertProp(math.max(0.0, math.min(1.0, v))))
    def saturate(v: Double): Style   = appendProp(Prop.SaturateProp(math.max(0.0, v)))
    def hueRotate(v: Double): Style  = appendProp(Prop.HueRotateProp(v))
    def blur(v: Size.Px): Style      = appendProp(Prop.BlurProp(clampSize(v)))

    // Background gradient

    def bgGradient(
        direction: GradientDirection.type => GradientDirection,
        stop1: (Color, Size.Pct),
        stop2: (Color, Size.Pct),
        stops: (Color, Size.Pct)*
    ): Style = bgGradient(direction(GradientDirection), stop1, stop2, stops*)

    def bgGradient(direction: GradientDirection, stop1: (Color, Size.Pct), stop2: (Color, Size.Pct), stops: (Color, Size.Pct)*): Style =
        val allStops  = stop1 +: stop2 +: stops
        val colors    = new Array[Color](allStops.length)
        val positions = new Array[Double](allStops.length)
        @tailrec def loop(i: Int): Unit =
            if i < allStops.length then
                colors(i) = allStops(i)._1
                positions(i) = math.max(0.0, math.min(100.0, allStops(i)._2.value))
                loop(i + 1)
        loop(0)
        appendProp(Prop.BgGradientProp(direction, Span.from(colors), Span.from(positions)))
    end bgGradient

end Style

object Style:

    // ---- Style factory methods ----

    val empty: Style = Style(Span.empty[Prop])

    def bg(c: Color): Style                               = empty.bg(c)
    def bg(hex: String): Style                            = empty.bg(hex)
    def bg(f: Color.type => Color): Style                 = empty.bg(f)
    def color(c: Color): Style                            = empty.color(c)
    def color(hex: String): Style                         = empty.color(hex)
    def color(f: Color.type => Color): Style              = empty.color(f)
    def padding(all: Size.Px | Size.Pct | Size.Em): Style = empty.padding(all)
    def padding(vertical: Size.Px | Size.Pct | Size.Em, horizontal: Size.Px | Size.Pct | Size.Em): Style =
        empty.padding(vertical, horizontal)
    def padding(
        top: Size.Px | Size.Pct | Size.Em,
        right: Size.Px | Size.Pct | Size.Em,
        bottom: Size.Px | Size.Pct | Size.Em,
        left: Size.Px | Size.Pct | Size.Em
    ): Style = empty.padding(top, right, bottom, left)
    def margin(all: Size): Style                                                  = empty.margin(all)
    def margin(vertical: Size, horizontal: Size): Style                           = empty.margin(vertical, horizontal)
    def margin(top: Size, right: Size, bottom: Size, left: Size): Style           = empty.margin(top, right, bottom, left)
    def gap(v: Size.Px | Size.Em): Style                                          = empty.gap(v)
    def row: Style                                                                = empty.row
    def column: Style                                                             = empty.column
    def flexWrap(v: FlexWrap): Style                                              = empty.flexWrap(v)
    def flexWrap(f: FlexWrap.type => FlexWrap): Style                             = empty.flexWrap(f)
    def align(v: Alignment): Style                                                = empty.align(v)
    def align(f: Alignment.type => Alignment): Style                              = empty.align(f)
    def justify(v: Justification): Style                                          = empty.justify(v)
    def justify(f: Justification.type => Justification): Style                    = empty.justify(f)
    def overflow(v: Overflow): Style                                              = empty.overflow(v)
    def overflow(f: Overflow.type => Overflow): Style                             = empty.overflow(f)
    def width(v: Size): Style                                                     = empty.width(v)
    def height(v: Size): Style                                                    = empty.height(v)
    def minWidth(v: Size): Style                                                  = empty.minWidth(v)
    def maxWidth(v: Size): Style                                                  = empty.maxWidth(v)
    def minHeight(v: Size): Style                                                 = empty.minHeight(v)
    def maxHeight(v: Size): Style                                                 = empty.maxHeight(v)
    def fontSize(v: Size.Px | Size.Em): Style                                     = empty.fontSize(v)
    def fontWeight(v: FontWeight): Style                                          = empty.fontWeight(v)
    def fontWeight(f: FontWeight.type => FontWeight): Style                       = empty.fontWeight(f)
    def bold: Style                                                               = empty.bold
    def italic: Style                                                             = empty.italic
    def fontStyle(v: FontStyle): Style                                            = empty.fontStyle(v)
    def fontStyle(f: FontStyle.type => FontStyle): Style                          = empty.fontStyle(f)
    def fontFamily(v: String): Style                                              = empty.fontFamily(v)
    def textAlign(v: TextAlign): Style                                            = empty.textAlign(v)
    def textAlign(f: TextAlign.type => TextAlign): Style                          = empty.textAlign(f)
    def textDecoration(v: TextDecoration): Style                                  = empty.textDecoration(v)
    def textDecoration(f: TextDecoration.type => TextDecoration): Style           = empty.textDecoration(f)
    def underline: Style                                                          = empty.underline
    def strikethrough: Style                                                      = empty.strikethrough
    def lineHeight(v: Double): Style                                              = empty.lineHeight(v)
    def letterSpacing(v: Size.Px | Size.Em): Style                                = empty.letterSpacing(v)
    def textTransform(v: TextTransform): Style                                    = empty.textTransform(v)
    def textTransform(f: TextTransform.type => TextTransform): Style              = empty.textTransform(f)
    def textOverflow(v: TextOverflow): Style                                      = empty.textOverflow(v)
    def textOverflow(f: TextOverflow.type => TextOverflow): Style                 = empty.textOverflow(f)
    def textWrap(v: TextWrap): Style                                              = empty.textWrap(v)
    def textWrap(f: TextWrap.type => TextWrap): Style                             = empty.textWrap(f)
    def border(width: Size.Px, style: BorderStyle, c: Color): Style               = empty.border(width, style, c)
    def border(width: Size.Px, style: BorderStyle, hex: String): Style            = empty.border(width, style, hex)
    def border(width: Size.Px, style: BorderStyle, f: Color.type => Color): Style = empty.border(width, style, f)
    def border(width: Size.Px, c: Color): Style                                   = empty.border(width, c)
    def border(width: Size.Px, hex: String): Style                                = empty.border(width, hex)
    def border(width: Size.Px, f: Color.type => Color): Style                     = empty.border(width, f)
    def borderColor(c: Color): Style                                              = empty.borderColor(c)
    def borderColor(hex: String): Style                                           = empty.borderColor(hex)
    def borderColor(f: Color.type => Color): Style                                = empty.borderColor(f)
    def borderColor(top: Color, right: Color, bottom: Color, left: Color): Style  = empty.borderColor(top, right, bottom, left)
    def borderWidth(v: Size.Px): Style                                            = empty.borderWidth(v)
    def borderWidth(top: Size.Px, right: Size.Px, bottom: Size.Px, left: Size.Px): Style =
        empty.borderWidth(top, right, bottom, left)
    def borderStyle(v: BorderStyle): Style                          = empty.borderStyle(v)
    def borderStyle(f: BorderStyle.type => BorderStyle): Style      = empty.borderStyle(f)
    def borderTop(width: Size.Px, c: Color): Style                  = empty.borderTop(width, c)
    def borderTop(width: Size.Px, hex: String): Style               = empty.borderTop(width, hex)
    def borderTop(width: Size.Px, f: Color.type => Color): Style    = empty.borderTop(width, f)
    def borderRight(width: Size.Px, c: Color): Style                = empty.borderRight(width, c)
    def borderRight(width: Size.Px, hex: String): Style             = empty.borderRight(width, hex)
    def borderRight(width: Size.Px, f: Color.type => Color): Style  = empty.borderRight(width, f)
    def borderBottom(width: Size.Px, c: Color): Style               = empty.borderBottom(width, c)
    def borderBottom(width: Size.Px, hex: String): Style            = empty.borderBottom(width, hex)
    def borderBottom(width: Size.Px, f: Color.type => Color): Style = empty.borderBottom(width, f)
    def borderLeft(width: Size.Px, c: Color): Style                 = empty.borderLeft(width, c)
    def borderLeft(width: Size.Px, hex: String): Style              = empty.borderLeft(width, hex)
    def borderLeft(width: Size.Px, f: Color.type => Color): Style   = empty.borderLeft(width, f)
    def rounded(v: Size.Px | Size.Pct): Style                       = empty.rounded(v)
    def rounded(
        topLeft: Size.Px | Size.Pct,
        topRight: Size.Px | Size.Pct,
        bottomRight: Size.Px | Size.Pct,
        bottomLeft: Size.Px | Size.Pct
    ): Style = empty.rounded(topLeft, topRight, bottomRight, bottomLeft)
    def shadow(
        x: Size.Px = Size.zero,
        y: Size.Px = Size.zero,
        blur: Size.Px = Size.zero,
        spread: Size.Px = Size.zero,
        c: Color = Color.rgba(0, 0, 0, 0.25)
    ): Style = empty.shadow(x, y, blur, spread, c)
    def shadow(x: Size.Px, y: Size.Px, blur: Size.Px, spread: Size.Px, f: Color.type => Color): Style =
        empty.shadow(x, y, blur, spread, f)
    def opacity(v: Double): Style                                      = empty.opacity(v)
    def cursor(v: Cursor): Style                                       = empty.cursor(v)
    def cursor(f: Cursor.type => Cursor): Style                        = empty.cursor(f)
    def translate(x: Size.Px | Size.Pct, y: Size.Px | Size.Pct): Style = empty.translate(x, y)
    def position(v: Position): Style                                   = empty.position(v)
    def position(f: Position.type => Position): Style                  = empty.position(f)
    def flexGrow(v: Double): Style                                     = empty.flexGrow(v)
    def flexShrink(v: Double): Style                                   = empty.flexShrink(v)
    def displayNone: Style                                             = empty.displayNone
    def brightness(v: Double): Style                                   = empty.brightness(v)
    def contrast(v: Double): Style                                     = empty.contrast(v)
    def grayscale(v: Double): Style                                    = empty.grayscale(v)
    def sepia(v: Double): Style                                        = empty.sepia(v)
    def invert(v: Double): Style                                       = empty.invert(v)
    def saturate(v: Double): Style                                     = empty.saturate(v)
    def hueRotate(v: Double): Style                                    = empty.hueRotate(v)
    def blur(v: Size.Px): Style                                        = empty.blur(v)
    def bgGradient(direction: GradientDirection, stop1: (Color, Size.Pct), stop2: (Color, Size.Pct), stops: (Color, Size.Pct)*): Style =
        empty.bgGradient(direction, stop1, stop2, stops*)
    def bgGradient(
        direction: GradientDirection.type => GradientDirection,
        stop1: (Color, Size.Pct),
        stop2: (Color, Size.Pct),
        stops: (Color, Size.Pct)*
    ): Style = empty.bgGradient(direction, stop1, stop2, stops*)
    def hover(s: Style): Style                  = empty.hover(s)
    def hover(f: Style.type => Style): Style    = empty.hover(f)
    def focus(s: Style): Style                  = empty.focus(s)
    def focus(f: Style.type => Style): Style    = empty.focus(f)
    def active(s: Style): Style                 = empty.active(s)
    def active(f: Style.type => Style): Style   = empty.active(f)
    def disabled(s: Style): Style               = empty.disabled(s)
    def disabled(f: Style.type => Style): Style = empty.disabled(f)

    // ---- Color ----

    sealed abstract class Color derives CanEqual

    object Color:
        final case class Hex private[kyo] (value: String)                      extends Color
        final case class Rgb private[kyo] (r: Int, g: Int, b: Int)             extends Color
        final case class Rgba private[kyo] (r: Int, g: Int, b: Int, a: Double) extends Color
        case object Transparent                                                extends Color

        private inline def clamp255(v: Int): Int      = math.max(0, math.min(255, v))
        private inline def clamp01(v: Double): Double = math.max(0.0, math.min(1.0, v))

        def hex(value: String): Color =
            val v = if value.nonEmpty && value.charAt(0) != '#' then "#" + value else value
            if v.isEmpty || v.charAt(0) != '#' then Hex("#000000")
            else
                @tailrec def isValidHex(i: Int): Boolean =
                    if i >= v.length then true
                    else
                        val c = v.charAt(i)
                        if (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') then
                            isValidHex(i + 1)
                        else false
                val valid = isValidHex(1)
                val len   = v.length
                if valid && (len == 4 || len == 5 || len == 7 || len == 9) then Hex(v)
                else Hex("#000000")
            end if
        end hex
        def rgb(r: Int, g: Int, b: Int): Color             = Rgb(clamp255(r), clamp255(g), clamp255(b))
        def rgba(r: Int, g: Int, b: Int, a: Double): Color = Rgba(clamp255(r), clamp255(g), clamp255(b), clamp01(a))

        val white: Color       = Hex("#ffffff")
        val black: Color       = Hex("#000000")
        val transparent: Color = Transparent
        val red: Color         = Hex("#ef4444")
        val orange: Color      = Hex("#f97316")
        val yellow: Color      = Hex("#eab308")
        val green: Color       = Hex("#22c55e")
        val blue: Color        = Hex("#3b82f6")
        val indigo: Color      = Hex("#6366f1")
        val purple: Color      = Hex("#a855f7")
        val pink: Color        = Hex("#ec4899")
        val gray: Color        = Hex("#6b7280")
        val slate: Color       = Hex("#64748b")
    end Color

    // ---- Size ----

    sealed abstract class Size derives CanEqual

    object Size:
        final case class Px(value: Double) extends Size:
            def unary_- : Px = Px(-value)
        final case class Pct(value: Double) extends Size:
            def unary_- : Pct = Pct(-value)
        final case class Em(value: Double) extends Size:
            def unary_- : Em = Em(-value)
        case object Auto extends Size

        val auto: Auto.type = Auto
        val zero: Px        = Px(0)

        implicit def intToPx(v: Int): Px       = Px(v.toDouble)
        implicit def doubleToPx(v: Double): Px = Px(v)

        extension (v: Int)
            def px: Px   = Px(v.toDouble)
            def pct: Pct = Pct(v.toDouble)
            def em: Em   = Em(v.toDouble)
        end extension
        extension (v: Double)
            def px: Px   = Px(v)
            def pct: Pct = Pct(v)
            def em: Em   = Em(v)
        end extension
    end Size

    // ---- Enums ----

    enum FlexDirection derives CanEqual:
        case row, column

    enum FlexWrap derives CanEqual:
        case wrap, noWrap

    enum Alignment derives CanEqual:
        case start, center, end, stretch, baseline

    enum Justification derives CanEqual:
        case start, center, end, spaceBetween, spaceAround, spaceEvenly

    enum Overflow derives CanEqual:
        case visible, hidden, scroll, auto

    enum FontWeight derives CanEqual:
        case normal, bold, w100, w200, w300, w400, w500, w600, w700, w800, w900

    enum FontStyle derives CanEqual:
        case normal, italic

    enum TextAlign derives CanEqual:
        case left, center, right, justify

    enum TextDecoration derives CanEqual:
        case none, underline, strikethrough

    enum TextTransform derives CanEqual:
        case none, uppercase, lowercase, capitalize

    enum TextOverflow derives CanEqual:
        case clip, ellipsis

    enum TextWrap derives CanEqual:
        case wrap, noWrap, ellipsis

    enum BorderStyle derives CanEqual:
        case none, solid, dashed, dotted

    enum Cursor derives CanEqual:
        case default_, pointer, text, move, notAllowed, crosshair, help, wait_, grab, grabbing

    enum Position derives CanEqual:
        case flow, overlay

    enum GradientDirection derives CanEqual:
        case toRight, toLeft, toTop, toBottom, toTopRight, toTopLeft, toBottomRight, toBottomLeft

    // ---- Prop ADT ----

    enum Prop derives CanEqual:
        // Background
        case BgColor(color: Color)
        case TextColor(color: Color)
        // Layout
        case Padding(top: Size, right: Size, bottom: Size, left: Size)
        case Margin(top: Size, right: Size, bottom: Size, left: Size)
        case Gap(value: Size)
        case FlexDirectionProp(value: FlexDirection)
        case FlexWrapProp(value: FlexWrap)
        case Align(value: Alignment)
        case Justify(value: Justification)
        case OverflowProp(value: Overflow)
        // Sizing
        case Width(value: Size)
        case Height(value: Size)
        case MinWidth(value: Size)
        case MaxWidth(value: Size)
        case MinHeight(value: Size)
        case MaxHeight(value: Size)
        // Typography
        case FontSizeProp(value: Size)
        case FontWeightProp(value: FontWeight)
        case FontStyleProp(value: FontStyle)
        case FontFamilyProp(value: String)
        case TextAlignProp(value: TextAlign)
        case TextDecorationProp(value: TextDecoration)
        case LineHeightProp(value: Double)
        case LetterSpacingProp(value: Size)
        case TextTransformProp(value: TextTransform)
        case TextOverflowProp(value: TextOverflow)
        case TextWrapProp(value: TextWrap)
        // Borders
        case BorderColorProp(top: Color, right: Color, bottom: Color, left: Color)
        case BorderWidthProp(top: Size, right: Size, bottom: Size, left: Size)
        case BorderStyleProp(value: BorderStyle)
        case BorderTopProp(width: Size, color: Color)
        case BorderRightProp(width: Size, color: Color)
        case BorderBottomProp(width: Size, color: Color)
        case BorderLeftProp(width: Size, color: Color)
        case BorderRadiusProp(topLeft: Size, topRight: Size, bottomRight: Size, bottomLeft: Size)
        // Effects
        case ShadowProp(x: Size, y: Size, blur: Size, spread: Size, color: Color)
        case OpacityProp(value: Double)
        // Cursor
        case CursorProp(value: Cursor)
        // Transform
        case TranslateProp(x: Size, y: Size)
        // Position
        case PositionProp(value: Position)
        // Visibility
        case HiddenProp
        // Flex grow/shrink
        case FlexGrowProp(value: Double)
        case FlexShrinkProp(value: Double)
        // Filters
        case BrightnessProp(value: Double)
        case ContrastProp(value: Double)
        case GrayscaleProp(value: Double)
        case SepiaProp(value: Double)
        case InvertProp(value: Double)
        case SaturateProp(value: Double)
        case HueRotateProp(value: Double)
        case BlurProp(value: Size)
        // Background gradient
        case BgGradientProp(direction: GradientDirection, colors: Span[Color], positions: Span[Double])
        // Pseudo-states
        case HoverProp(style: Style)
        case FocusProp(style: Style)
        case ActiveProp(style: Style)
        case DisabledProp(style: Style)
    end Prop

end Style
