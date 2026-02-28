package kyo

import scala.annotation.targetName

final case class Style private[kyo] (props: Span[Style.Prop]):

    import Style.*
    import Style.Prop.*

    // Composition

    def ++(other: Style): Style = Style(props ++ other.props)

    // Background

    def bg(c: Color): Style    = append(BgColor(c))
    def bg(hex: String): Style = bg(Color.hex(hex))

    // Text color

    def color(c: Color): Style    = append(TextColor(c))
    def color(hex: String): Style = color(Color.hex(hex))

    // Padding

    def padding(all: Int): Style                         = padding(Size.px(all))
    def padding(all: Double): Style                      = padding(Size.px(all))
    def padding(all: Size): Style                        = append(Padding(all, all, all, all))
    def padding(vertical: Int, horizontal: Int): Style   = padding(Size.px(vertical), Size.px(horizontal))
    def padding(vertical: Size, horizontal: Size): Style = append(Padding(vertical, horizontal, vertical, horizontal))
    def padding(top: Int, right: Int, bottom: Int, left: Int): Style =
        padding(Size.px(top), Size.px(right), Size.px(bottom), Size.px(left))
    def padding(top: Size, right: Size, bottom: Size, left: Size): Style =
        append(Padding(top, right, bottom, left))

    // Margin

    def margin(all: Int): Style                         = margin(Size.px(all))
    def margin(all: Double): Style                      = margin(Size.px(all))
    def margin(all: Size): Style                        = append(Margin(all, all, all, all))
    def margin(vertical: Int, horizontal: Int): Style   = margin(Size.px(vertical), Size.px(horizontal))
    def margin(vertical: Size, horizontal: Size): Style = append(Margin(vertical, horizontal, vertical, horizontal))
    def margin(top: Int, right: Int, bottom: Int, left: Int): Style =
        margin(Size.px(top), Size.px(right), Size.px(bottom), Size.px(left))
    def margin(top: Size, right: Size, bottom: Size, left: Size): Style =
        append(Margin(top, right, bottom, left))

    // Gap

    def gap(v: Int): Style    = gap(Size.px(v))
    def gap(v: Double): Style = gap(Size.px(v))
    def gap(v: Size): Style   = append(Gap(v))

    // Alignment

    def align(v: Alignment): Style       = append(Align(v))
    def justify(v: Justification): Style = append(Justify(v))

    // Overflow

    def overflow(v: Overflow): Style = append(OverflowProp(v))

    // Sizing

    def width(v: Int): Style    = width(Size.px(v))
    def width(v: Double): Style = width(Size.px(v))
    def width(v: Size): Style   = append(Width(v))

    def height(v: Int): Style    = height(Size.px(v))
    def height(v: Double): Style = height(Size.px(v))
    def height(v: Size): Style   = append(Height(v))

    def minWidth(v: Int): Style    = minWidth(Size.px(v))
    def minWidth(v: Double): Style = minWidth(Size.px(v))
    def minWidth(v: Size): Style   = append(MinWidth(v))

    def maxWidth(v: Int): Style    = maxWidth(Size.px(v))
    def maxWidth(v: Double): Style = maxWidth(Size.px(v))
    def maxWidth(v: Size): Style   = append(MaxWidth(v))

    def minHeight(v: Int): Style    = minHeight(Size.px(v))
    def minHeight(v: Double): Style = minHeight(Size.px(v))
    def minHeight(v: Size): Style   = append(MinHeight(v))

    def maxHeight(v: Int): Style    = maxHeight(Size.px(v))
    def maxHeight(v: Double): Style = maxHeight(Size.px(v))
    def maxHeight(v: Size): Style   = append(MaxHeight(v))

    // Typography

    def fontSize(v: Int): Style    = fontSize(Size.px(v))
    def fontSize(v: Double): Style = fontSize(Size.px(v))
    def fontSize(v: Size): Style   = append(FontSizeProp(v))

    def fontWeight(v: FontWeight): Style = append(FontWeightProp(v))
    def bold: Style                      = fontWeight(FontWeight.Bold)

    def fontStyle(v: FontStyle): Style = append(FontStyleProp(v))
    def italic: Style                  = fontStyle(FontStyle.Italic)

    def fontFamily(v: String): Style = append(FontFamilyProp(v))

    def textAlign(v: TextAlign): Style = append(TextAlignProp(v))

    def textDecoration(v: TextDecoration): Style = append(TextDecorationProp(v))
    def underline: Style                         = textDecoration(TextDecoration.Underline)
    def strikethrough: Style                     = textDecoration(TextDecoration.Strikethrough)

    def lineHeight(v: Double): Style = append(LineHeightProp(v))

    def letterSpacing(v: Int): Style    = letterSpacing(Size.px(v))
    def letterSpacing(v: Double): Style = letterSpacing(Size.px(v))
    def letterSpacing(v: Size): Style   = append(LetterSpacingProp(v))

    def textTransform(v: TextTransform): Style = append(TextTransformProp(v))

    def textOverflow(v: TextOverflow): Style = append(TextOverflowProp(v))

    // Borders

    def border(width: Int, style: BorderStyle, c: Color): Style =
        append(BorderWidthProp(Size.px(width), Size.px(width), Size.px(width), Size.px(width)))
            .append(BorderStyleProp(style))
            .append(BorderColorProp(c, c, c, c))

    def border(width: Int, style: BorderStyle, hex: String): Style =
        border(width, style, Color.hex(hex))

    def border(width: Int, c: Color): Style =
        border(width, BorderStyle.Solid, c)

    def border(width: Int, hex: String): Style =
        border(width, BorderStyle.Solid, Color.hex(hex))

    def borderColor(c: Color): Style    = append(BorderColorProp(c, c, c, c))
    def borderColor(hex: String): Style = borderColor(Color.hex(hex))
    def borderColor(top: Color, right: Color, bottom: Color, left: Color): Style =
        append(BorderColorProp(top, right, bottom, left))

    def borderWidth(v: Int): Style  = borderWidth(Size.px(v))
    def borderWidth(v: Size): Style = append(BorderWidthProp(v, v, v, v))
    def borderWidth(top: Int, right: Int, bottom: Int, left: Int): Style =
        append(BorderWidthProp(Size.px(top), Size.px(right), Size.px(bottom), Size.px(left)))

    def borderStyle(v: BorderStyle): Style = append(BorderStyleProp(v))

    def borderTop(width: Int, c: Color): Style =
        append(BorderTopProp(Size.px(width), c))
    def borderTop(width: Int, hex: String): Style = borderTop(width, Color.hex(hex))

    def borderRight(width: Int, c: Color): Style =
        append(BorderRightProp(Size.px(width), c))
    def borderRight(width: Int, hex: String): Style = borderRight(width, Color.hex(hex))

    def borderBottom(width: Int, c: Color): Style =
        append(BorderBottomProp(Size.px(width), c))
    def borderBottom(width: Int, hex: String): Style = borderBottom(width, Color.hex(hex))

    def borderLeft(width: Int, c: Color): Style =
        append(BorderLeftProp(Size.px(width), c))
    def borderLeft(width: Int, hex: String): Style = borderLeft(width, Color.hex(hex))

    // Border radius

    def rounded(v: Int): Style    = rounded(Size.px(v))
    def rounded(v: Double): Style = rounded(Size.px(v))
    def rounded(v: Size): Style   = append(BorderRadiusProp(v, v, v, v))
    def rounded(topLeft: Int, topRight: Int, bottomRight: Int, bottomLeft: Int): Style =
        append(BorderRadiusProp(Size.px(topLeft), Size.px(topRight), Size.px(bottomRight), Size.px(bottomLeft)))
    def rounded(topLeft: Size, topRight: Size, bottomRight: Size, bottomLeft: Size): Style =
        append(BorderRadiusProp(topLeft, topRight, bottomRight, bottomLeft))

    // Effects

    def shadow(
        x: Int = 0,
        y: Int = 0,
        blur: Int = 0,
        spread: Int = 0,
        c: Color = Color.rgba(0, 0, 0, 0.25)
    ): Style = append(ShadowProp(Size.px(x), Size.px(y), Size.px(blur), Size.px(spread), c))

    def opacity(v: Double): Style = append(OpacityProp(v))

    // Cursor

    def cursor(v: Cursor): Style = append(CursorProp(v))

    // Transform

    def rotate(degrees: Double): Style = append(RotateProp(degrees))

    def scale(v: Double): Style            = append(ScaleProp(v, v))
    def scale(x: Double, y: Double): Style = append(ScaleProp(x, y))

    def translate(x: Int, y: Int): Style   = translate(Size.px(x), Size.px(y))
    def translate(x: Size, y: Size): Style = append(TranslateProp(x, y))

    // Pseudo-states

    def hover(s: Style): Style  = append(HoverProp(s))
    def focus(s: Style): Style  = append(FocusProp(s))
    def active(s: Style): Style = append(ActiveProp(s))

    def isEmpty: Boolean  = props.isEmpty
    def nonEmpty: Boolean = props.nonEmpty

    // Pseudo-state extraction (for backend use)

    private[kyo] def baseProps: Style = Style(props.filter {
        case _: HoverProp | _: FocusProp | _: ActiveProp => false
        case _                                           => true
    })

    private[kyo] def hoverStyle: Maybe[Style] =
        props.find(_.isInstanceOf[HoverProp]).map(_.asInstanceOf[HoverProp].style)

    private[kyo] def focusStyle: Maybe[Style] =
        props.find(_.isInstanceOf[FocusProp]).map(_.asInstanceOf[FocusProp].style)

    private[kyo] def activeStyle: Maybe[Style] =
        props.find(_.isInstanceOf[ActiveProp]).map(_.asInstanceOf[ActiveProp].style)

    // Internal

    private def append(p: Prop): Style = Style(props :+ p)

end Style

object Style:

    val empty: Style = Style(Span.empty[Prop])

    // Factory methods to start a chain

    def bg(c: Color): Style                                                      = empty.bg(c)
    def bg(hex: String): Style                                                   = empty.bg(hex)
    def color(c: Color): Style                                                   = empty.color(c)
    def color(hex: String): Style                                                = empty.color(hex)
    def padding(all: Int): Style                                                 = empty.padding(all)
    def padding(all: Double): Style                                              = empty.padding(all)
    def padding(all: Size): Style                                                = empty.padding(all)
    def padding(vertical: Int, horizontal: Int): Style                           = empty.padding(vertical, horizontal)
    def padding(vertical: Size, horizontal: Size): Style                         = empty.padding(vertical, horizontal)
    def padding(top: Int, right: Int, bottom: Int, left: Int): Style             = empty.padding(top, right, bottom, left)
    def padding(top: Size, right: Size, bottom: Size, left: Size): Style         = empty.padding(top, right, bottom, left)
    def margin(all: Int): Style                                                  = empty.margin(all)
    def margin(all: Double): Style                                               = empty.margin(all)
    def margin(all: Size): Style                                                 = empty.margin(all)
    def margin(vertical: Int, horizontal: Int): Style                            = empty.margin(vertical, horizontal)
    def margin(vertical: Size, horizontal: Size): Style                          = empty.margin(vertical, horizontal)
    def margin(top: Int, right: Int, bottom: Int, left: Int): Style              = empty.margin(top, right, bottom, left)
    def margin(top: Size, right: Size, bottom: Size, left: Size): Style          = empty.margin(top, right, bottom, left)
    def gap(v: Int): Style                                                       = empty.gap(v)
    def gap(v: Double): Style                                                    = empty.gap(v)
    def gap(v: Size): Style                                                      = empty.gap(v)
    def align(v: Alignment): Style                                               = empty.align(v)
    def justify(v: Justification): Style                                         = empty.justify(v)
    def overflow(v: Overflow): Style                                             = empty.overflow(v)
    def width(v: Int): Style                                                     = empty.width(v)
    def width(v: Double): Style                                                  = empty.width(v)
    def width(v: Size): Style                                                    = empty.width(v)
    def height(v: Int): Style                                                    = empty.height(v)
    def height(v: Double): Style                                                 = empty.height(v)
    def height(v: Size): Style                                                   = empty.height(v)
    def minWidth(v: Int): Style                                                  = empty.minWidth(v)
    def minWidth(v: Double): Style                                               = empty.minWidth(v)
    def minWidth(v: Size): Style                                                 = empty.minWidth(v)
    def maxWidth(v: Int): Style                                                  = empty.maxWidth(v)
    def maxWidth(v: Double): Style                                               = empty.maxWidth(v)
    def maxWidth(v: Size): Style                                                 = empty.maxWidth(v)
    def minHeight(v: Int): Style                                                 = empty.minHeight(v)
    def minHeight(v: Double): Style                                              = empty.minHeight(v)
    def minHeight(v: Size): Style                                                = empty.minHeight(v)
    def maxHeight(v: Int): Style                                                 = empty.maxHeight(v)
    def maxHeight(v: Double): Style                                              = empty.maxHeight(v)
    def maxHeight(v: Size): Style                                                = empty.maxHeight(v)
    def fontSize(v: Int): Style                                                  = empty.fontSize(v)
    def fontSize(v: Double): Style                                               = empty.fontSize(v)
    def fontSize(v: Size): Style                                                 = empty.fontSize(v)
    def fontWeight(v: FontWeight): Style                                         = empty.fontWeight(v)
    def bold: Style                                                              = empty.bold
    def italic: Style                                                            = empty.italic
    def fontStyle(v: FontStyle): Style                                           = empty.fontStyle(v)
    def fontFamily(v: String): Style                                             = empty.fontFamily(v)
    def textAlign(v: TextAlign): Style                                           = empty.textAlign(v)
    def textDecoration(v: TextDecoration): Style                                 = empty.textDecoration(v)
    def underline: Style                                                         = empty.underline
    def strikethrough: Style                                                     = empty.strikethrough
    def lineHeight(v: Double): Style                                             = empty.lineHeight(v)
    def letterSpacing(v: Int): Style                                             = empty.letterSpacing(v)
    def letterSpacing(v: Double): Style                                          = empty.letterSpacing(v)
    def letterSpacing(v: Size): Style                                            = empty.letterSpacing(v)
    def textTransform(v: TextTransform): Style                                   = empty.textTransform(v)
    def textOverflow(v: TextOverflow): Style                                     = empty.textOverflow(v)
    def border(width: Int, style: BorderStyle, c: Color): Style                  = empty.border(width, style, c)
    def border(width: Int, style: BorderStyle, hex: String): Style               = empty.border(width, style, hex)
    def border(width: Int, c: Color): Style                                      = empty.border(width, c)
    def border(width: Int, hex: String): Style                                   = empty.border(width, hex)
    def borderColor(c: Color): Style                                             = empty.borderColor(c)
    def borderColor(hex: String): Style                                          = empty.borderColor(hex)
    def borderColor(top: Color, right: Color, bottom: Color, left: Color): Style = empty.borderColor(top, right, bottom, left)
    def borderWidth(v: Int): Style                                               = empty.borderWidth(v)
    def borderWidth(v: Size): Style                                              = empty.borderWidth(v)
    def borderWidth(top: Int, right: Int, bottom: Int, left: Int): Style         = empty.borderWidth(top, right, bottom, left)
    def borderStyle(v: BorderStyle): Style                                       = empty.borderStyle(v)
    def borderTop(width: Int, c: Color): Style                                   = empty.borderTop(width, c)
    def borderTop(width: Int, hex: String): Style                                = empty.borderTop(width, hex)
    def borderRight(width: Int, c: Color): Style                                 = empty.borderRight(width, c)
    def borderRight(width: Int, hex: String): Style                              = empty.borderRight(width, hex)
    def borderBottom(width: Int, c: Color): Style                                = empty.borderBottom(width, c)
    def borderBottom(width: Int, hex: String): Style                             = empty.borderBottom(width, hex)
    def borderLeft(width: Int, c: Color): Style                                  = empty.borderLeft(width, c)
    def borderLeft(width: Int, hex: String): Style                               = empty.borderLeft(width, hex)
    def rounded(v: Int): Style                                                   = empty.rounded(v)
    def rounded(v: Double): Style                                                = empty.rounded(v)
    def rounded(v: Size): Style                                                  = empty.rounded(v)
    def rounded(topLeft: Int, topRight: Int, bottomRight: Int, bottomLeft: Int): Style =
        empty.rounded(topLeft, topRight, bottomRight, bottomLeft)
    def rounded(topLeft: Size, topRight: Size, bottomRight: Size, bottomLeft: Size): Style =
        empty.rounded(topLeft, topRight, bottomRight, bottomLeft)
    def shadow(
        x: Int = 0,
        y: Int = 0,
        blur: Int = 0,
        spread: Int = 0,
        c: Color = Color.rgba(0, 0, 0, 0.25)
    ): Style = empty.shadow(x, y, blur, spread, c)
    def opacity(v: Double): Style          = empty.opacity(v)
    def cursor(v: Cursor): Style           = empty.cursor(v)
    def rotate(degrees: Double): Style     = empty.rotate(degrees)
    def scale(v: Double): Style            = empty.scale(v)
    def scale(x: Double, y: Double): Style = empty.scale(x, y)
    def translate(x: Int, y: Int): Style   = empty.translate(x, y)
    def translate(x: Size, y: Size): Style = empty.translate(x, y)
    def hover(s: Style): Style             = empty.hover(s)
    def focus(s: Style): Style             = empty.focus(s)
    def active(s: Style): Style            = empty.active(s)

    // Color

    sealed abstract class Color:
        private[kyo] def css: String

    object Color:
        final case class Hex private[Color] (value: String) extends Color:
            private[kyo] def css: String = value

        final case class Rgb private[Color] (r: Int, g: Int, b: Int) extends Color:
            private[kyo] def css: String = s"rgb($r, $g, $b)"

        final case class Rgba private[Color] (r: Int, g: Int, b: Int, a: Double) extends Color:
            private[kyo] def css: String = s"rgba($r, $g, $b, $a)"

        def hex(value: String): Color                      = Hex(value)
        def rgb(r: Int, g: Int, b: Int): Color             = Rgb(r, g, b)
        def rgba(r: Int, g: Int, b: Int, a: Double): Color = Rgba(r, g, b, a)

        val white: Color       = Hex("#ffffff")
        val black: Color       = Hex("#000000")
        val transparent: Color = Hex("transparent")
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

    // Size

    sealed abstract class Size derives CanEqual

    object Size:
        final case class Px(value: Double)  extends Size
        final case class Pct(value: Double) extends Size
        final case class Em(value: Double)  extends Size
        case object Auto                    extends Size

        def px(v: Int): Size     = Px(v.toDouble)
        def px(v: Double): Size  = Px(v)
        def pct(v: Int): Size    = Pct(v.toDouble)
        def pct(v: Double): Size = Pct(v)
        def em(v: Int): Size     = Em(v.toDouble)
        def em(v: Double): Size  = Em(v)
        val auto: Size           = Auto
        val zero: Size           = Px(0)
    end Size

    // Size extension methods

    extension (v: Int)
        def px: Size  = Size.Px(v.toDouble)
        def pct: Size = Size.Pct(v.toDouble)
        def em: Size  = Size.Em(v.toDouble)
    end extension

    extension (v: Double)
        @targetName("doublePx")
        def px: Size = Size.Px(v)
        @targetName("doublePct")
        def pct: Size = Size.Pct(v)
        @targetName("doubleEm")
        def em: Size = Size.Em(v)
    end extension

    // Enums

    enum Alignment derives CanEqual:
        case Start, Center, End, Stretch, Baseline

        private[kyo] def css: String = this match
            case Start    => "flex-start"
            case Center   => "center"
            case End      => "flex-end"
            case Stretch  => "stretch"
            case Baseline => "baseline"

        private[kyo] def fxCss: String = this match
            case Start    => "top-left"
            case Center   => "center"
            case End      => "bottom-right"
            case Stretch  => "center"
            case Baseline => "baseline-left"
    end Alignment

    enum Justification derives CanEqual:
        case Start, Center, End, SpaceBetween, SpaceAround, SpaceEvenly

        private[kyo] def css: String = this match
            case Start        => "flex-start"
            case Center       => "center"
            case End          => "flex-end"
            case SpaceBetween => "space-between"
            case SpaceAround  => "space-around"
            case SpaceEvenly  => "space-evenly"

        private[kyo] def fxCss: String = this match
            case Start        => "top-left"
            case Center       => "center"
            case End          => "bottom-right"
            case SpaceBetween => "center"
            case SpaceAround  => "center"
            case SpaceEvenly  => "center"
    end Justification

    enum Overflow derives CanEqual:
        case Visible, Hidden, Scroll, Auto

        private[kyo] def css: String = this match
            case Visible => "visible"
            case Hidden  => "hidden"
            case Scroll  => "scroll"
            case Auto    => "auto"
    end Overflow

    enum FontWeight derives CanEqual:
        case Normal, Bold, W100, W200, W300, W400, W500, W600, W700, W800, W900

        private[kyo] def css: String = this match
            case Normal => "normal"
            case Bold   => "bold"
            case W100   => "100"
            case W200   => "200"
            case W300   => "300"
            case W400   => "400"
            case W500   => "500"
            case W600   => "600"
            case W700   => "700"
            case W800   => "800"
            case W900   => "900"
    end FontWeight

    enum FontStyle derives CanEqual:
        case Normal, Italic

        private[kyo] def css: String = this match
            case Normal => "normal"
            case Italic => "italic"
    end FontStyle

    enum TextAlign derives CanEqual:
        case Left, Center, Right, Justify

        private[kyo] def css: String = this match
            case Left    => "left"
            case Center  => "center"
            case Right   => "right"
            case Justify => "justify"
    end TextAlign

    enum TextDecoration derives CanEqual:
        case None, Underline, Strikethrough

        private[kyo] def css: String = this match
            case None          => "none"
            case Underline     => "underline"
            case Strikethrough => "line-through"
    end TextDecoration

    enum TextTransform derives CanEqual:
        case None, Uppercase, Lowercase, Capitalize

        private[kyo] def css: String = this match
            case None       => "none"
            case Uppercase  => "uppercase"
            case Lowercase  => "lowercase"
            case Capitalize => "capitalize"
    end TextTransform

    enum TextOverflow derives CanEqual:
        case Clip, Ellipsis

        private[kyo] def css: String = this match
            case Clip     => "clip"
            case Ellipsis => "ellipsis"
    end TextOverflow

    enum BorderStyle derives CanEqual:
        case None, Solid, Dashed, Dotted

        private[kyo] def css: String = this match
            case None   => "none"
            case Solid  => "solid"
            case Dashed => "dashed"
            case Dotted => "dotted"
    end BorderStyle

    enum Cursor derives CanEqual:
        case Default, Pointer, Text, Move, NotAllowed, Crosshair, Help, Wait, Grab, Grabbing

        private[kyo] def css: String = this match
            case Default    => "default"
            case Pointer    => "pointer"  // JavaFX: "hand"
            case Text       => "text"
            case Move       => "move"
            case NotAllowed => "not-allowed"
            case Crosshair  => "crosshair"
            case Help       => "help"     // JavaFX: "default"
            case Wait       => "wait"
            case Grab       => "grab"     // JavaFX: "open_hand"
            case Grabbing   => "grabbing" // JavaFX: "closed_hand"
    end Cursor

    // Prop ADT

    sealed abstract class Prop

    object Prop:
        // Background
        final case class BgColor(color: Color) extends Prop
        // Text color
        final case class TextColor(color: Color) extends Prop
        // Layout
        final case class Padding(top: Size, right: Size, bottom: Size, left: Size) extends Prop
        final case class Margin(top: Size, right: Size, bottom: Size, left: Size)  extends Prop
        final case class Gap(value: Size)                                          extends Prop
        final case class Align(value: Alignment)                                   extends Prop
        final case class Justify(value: Justification)                             extends Prop
        final case class OverflowProp(value: Overflow)                             extends Prop
        // Sizing
        final case class Width(value: Size)     extends Prop
        final case class Height(value: Size)    extends Prop
        final case class MinWidth(value: Size)  extends Prop
        final case class MaxWidth(value: Size)  extends Prop
        final case class MinHeight(value: Size) extends Prop
        final case class MaxHeight(value: Size) extends Prop
        // Typography
        final case class FontSizeProp(value: Size)                 extends Prop
        final case class FontWeightProp(value: FontWeight)         extends Prop
        final case class FontStyleProp(value: FontStyle)           extends Prop
        final case class FontFamilyProp(value: String)             extends Prop
        final case class TextAlignProp(value: TextAlign)           extends Prop
        final case class TextDecorationProp(value: TextDecoration) extends Prop
        final case class LineHeightProp(value: Double)             extends Prop
        final case class LetterSpacingProp(value: Size)            extends Prop
        final case class TextTransformProp(value: TextTransform)   extends Prop
        final case class TextOverflowProp(value: TextOverflow)     extends Prop
        // Borders
        final case class BorderColorProp(top: Color, right: Color, bottom: Color, left: Color)                extends Prop
        final case class BorderWidthProp(top: Size, right: Size, bottom: Size, left: Size)                    extends Prop
        final case class BorderStyleProp(value: BorderStyle)                                                  extends Prop
        final case class BorderTopProp(width: Size, color: Color)                                             extends Prop
        final case class BorderRightProp(width: Size, color: Color)                                           extends Prop
        final case class BorderBottomProp(width: Size, color: Color)                                          extends Prop
        final case class BorderLeftProp(width: Size, color: Color)                                            extends Prop
        final case class BorderRadiusProp(topLeft: Size, topRight: Size, bottomRight: Size, bottomLeft: Size) extends Prop
        // Effects
        final case class ShadowProp(x: Size, y: Size, blur: Size, spread: Size, color: Color) extends Prop
        final case class OpacityProp(value: Double)                                           extends Prop
        // Cursor
        final case class CursorProp(value: Cursor) extends Prop
        // Transform
        final case class RotateProp(degrees: Double)     extends Prop
        final case class ScaleProp(x: Double, y: Double) extends Prop
        final case class TranslateProp(x: Size, y: Size) extends Prop
        // Pseudo-states
        final case class HoverProp(style: Style)  extends Prop
        final case class FocusProp(style: Style)  extends Prop
        final case class ActiveProp(style: Style) extends Prop
    end Prop

end Style
