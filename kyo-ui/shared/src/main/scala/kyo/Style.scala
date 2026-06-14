package kyo

import scala.annotation.tailrec

/** An immutable, ordered list of style properties to attach to a [[kyo.UI]] element.
  *
  * A `Style` is a pure value: it holds a `Chunk[Style.Prop]` and nothing else. Every setter (`bg`, `padding`, `width`, ...) returns a *new*
  * `Style` with the property appended, leaving the receiver untouched, so a `Style` is safe to share and reuse across elements. Attach one to
  * an element with its `.style(...)` setter.
  *
  * The defining rule is **last-write-wins per property kind**. The "kind" is the runtime case of the [[kyo.Style.Prop]] enum, not the
  * individual setting call: writing `width` twice keeps only the last write, and `padding(10.px)` followed by `padding(20.px, 30.px)` keeps
  * only the second because both produce a `Prop.Padding`. Distinct kinds (`BgColor` vs `TextColor`) coexist. Composition with `++` merges
  * another style under the same rule, so it is associative on kinds rather than on individual calls.
  *
  *   - **Pseudo-states**: `hover`, `focus`, `active`, and `disabled` each nest a *child* `Style` that applies only in that interaction state.
  *   - **Value clamping**: sizing values are clamped to non-negative (and to a sensible minimum where a zero would be invalid, e.g. font
  *     size and border width), opacity/filter ratios are clamped to their valid ranges, and color components are clamped on construction.
  *   - **Introspection**: the encoded props are pattern-matchable, so `find`, `filter`, and `without` can query or strip them.
  *
  * IMPORTANT: merge and dedup key on the property *kind*, so a later same-kind write silently replaces the earlier one rather than combining
  * with it.
  *
  * @see
  *   [[kyo.Style.hover]], [[kyo.Style.focus]], [[kyo.Style.active]], [[kyo.Style.disabled]] and `++` for composition
  * @see
  *   [[kyo.Style.find]], [[kyo.Style.filter]], [[kyo.Style.without]] for introspection and removal
  * @see
  *   [[kyo.Style.Color]] and [[kyo.Length]] for the value types setters accept
  * @see
  *   [[kyo.Style.Prop]] for the encoded representation each setter stores
  * @see
  *   [[kyo.UI]] for the element tree a `Style` attaches to
  */
final case class Style private[kyo] (props: Chunk[Style.Prop]) derives CanEqual:

    import Style.*
    import Style.Prop.*

    private def withoutKind(p: Prop): Style =
        val cls = p.getClass
        Style(props.filter(x => !(x.getClass eq cls)))

    private def appendProp(p: Prop): Style =
        withoutKind(p) match
            case s => Style(s.props :+ p)

    private def clampSize(s: Length): Length = s match
        case Length.Px(v)   => if v < 0 then Length.Px(0) else s
        case Length.Pct(v)  => if v < 0 then Length.Pct(0) else s
        case Length.Em(v)   => if v < 0 then Length.Em(0) else s
        case Length.Vh(v)   => if v < 0 then Length.Vh(0) else s
        case Length.Calc(_) => s
        case Length.Auto    => s

    private def clampSizeMin1(s: Length): Length = s match
        case Length.Px(v) => if v < 1 then Length.Px(1) else s
        case Length.Em(v) => if v < 0.1 then Length.Em(0.1) else s
        case _            => s

    // Composition

    /** Merges `other` into this style under last-write-wins per [[kyo.Style.Prop]] kind: each prop in `other` replaces a same-kind prop here
      * and is appended, so the merge is associative on kinds rather than on individual setting calls.
      */
    def ++(other: Style): Style =
        if other.isEmpty then this
        else if isEmpty then other
        else
            @tailrec def loop(result: Style, i: Int): Style =
                if i >= other.props.size then result
                else
                    val p = other.props(i)
                    loop(
                        result.withoutKind(p) match
                            case s => Style(s.props :+ p)
                        ,
                        i + 1
                    )
            loop(this, 0)

    // Pseudo-states

    /** Nests a child `Style` that applies while the element is hovered.
      *
      * The `f` overload is a builder shorthand: `hover(_.bg(Color.indigo))` instead of `hover(Style.bg(Color.indigo))`.
      */
    def hover(s: Style): Style               = appendProp(HoverProp(s))
    def hover(f: Style.type => Style): Style = hover(f(Style))

    /** Nests a child `Style` that applies while the element is focused. The `f` overload is the builder shorthand. */
    def focus(s: Style): Style               = appendProp(FocusProp(s))
    def focus(f: Style.type => Style): Style = focus(f(Style))

    /** Nests a child `Style` that applies while the element is active (pressed). The `f` overload is the builder shorthand. */
    def active(s: Style): Style               = appendProp(ActiveProp(s))
    def active(f: Style.type => Style): Style = active(f(Style))

    /** Nests a child `Style` that applies while the element is disabled. The `f` overload is the builder shorthand. */
    def disabled(s: Style): Style               = appendProp(DisabledProp(s))
    def disabled(f: Style.type => Style): Style = disabled(f(Style))

    def isEmpty: Boolean  = props.isEmpty
    def nonEmpty: Boolean = props.nonEmpty

    /** Finds the first stored [[kyo.Style.Prop]] of the given case type, or `Absent` if none is present.
      *
      * @tparam A
      *   the concrete `Prop` case to look for (e.g. `Prop.Width`)
      */
    def find[A <: Prop](using tag: ConcreteTag[A]): Maybe[A] =
        @tailrec def loop(i: Int): Maybe[A] =
            if i >= props.size then Absent
            else
                props(i) match
                    case tag(a) => Present(a)
                    case _      => loop(i + 1)
        loop(0)
    end find

    /** Returns a `Style` keeping only the encoded [[kyo.Style.Prop]] cases for which `f` holds. */
    def filter(f: Prop => Boolean): Style = Style(props.filter(f))

    /** Returns a `Style` with every stored [[kyo.Style.Prop]] of the given case type removed.
      *
      * @tparam A
      *   the concrete `Prop` case to strip (e.g. `Prop.BgColor`)
      */
    def without[A <: Prop](using tag: ConcreteTag[A]): Style =
        Style(props.filter(p => !tag.accepts(p)))

    // Background

    def bg(c: Color): Style               = appendProp(Prop.BgColor(c))
    def bg(f: Color.type => Color): Style = bg(f(Color))

    /** Maps to the CSS `background-clip` property: `paddingBox` clips the background to the padding box so
      * a transparent border insets the painted fill (the standard way to float a scrollbar thumb inside its
      * track), `contentBox` clips to the content box, `borderBox` is the default.
      */
    def backgroundClip(v: BackgroundClip): Style                        = appendProp(Prop.BackgroundClipProp(v))
    def backgroundClip(f: BackgroundClip.type => BackgroundClip): Style = backgroundClip(f(BackgroundClip))

    // Text color

    def color(c: Color): Style               = appendProp(Prop.TextColor(c))
    def color(f: Color.type => Color): Style = color(f(Color))

    // Padding: px, pct, or em (no auto)

    /** Sets padding. The 1-arg form applies to all four sides, the 2-arg form to vertical/horizontal, the 4-arg form to top/right/bottom/left.
      * Values are clamped to non-negative.
      */
    def padding(all: Length.Px | Length.Pct | Length.Em): Style =
        val c = clampSize(all); appendProp(Prop.Padding(c, c, c, c))
    def padding(vertical: Length.Px | Length.Pct | Length.Em, horizontal: Length.Px | Length.Pct | Length.Em): Style =
        val v = clampSize(vertical); val h = clampSize(horizontal); appendProp(Prop.Padding(v, h, v, h))
    def padding(
        top: Length.Px | Length.Pct | Length.Em,
        right: Length.Px | Length.Pct | Length.Em,
        bottom: Length.Px | Length.Pct | Length.Em,
        left: Length.Px | Length.Pct | Length.Em
    ): Style =
        appendProp(Prop.Padding(clampSize(top), clampSize(right), clampSize(bottom), clampSize(left)))

    // Margin: any size including auto

    /** Sets margin with the same 1/2/4-arg side expansion as `padding`. Margins accept `auto` and are not clamped. */
    def margin(all: Length): Style                          = appendProp(Prop.Margin(all, all, all, all))
    def margin(vertical: Length, horizontal: Length): Style = appendProp(Prop.Margin(vertical, horizontal, vertical, horizontal))
    def margin(top: Length, right: Length, bottom: Length, left: Length): Style = appendProp(Prop.Margin(top, right, bottom, left))

    // Gap: px or em

    /** Sets the gap between flex children, clamped to non-negative. */
    def gap(v: Length.Px | Length.Em): Style = appendProp(Prop.Gap(clampSize(v)))

    // Layout direction

    def row: Style           = appendProp(Prop.FlexDirectionProp(FlexDirection.row))
    def column: Style        = appendProp(Prop.FlexDirectionProp(FlexDirection.column))
    def rowReverse: Style    = appendProp(Prop.FlexDirectionProp(FlexDirection.rowReverse))
    def columnReverse: Style = appendProp(Prop.FlexDirectionProp(FlexDirection.columnReverse))

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

    /** Maps to the CSS `overflow-x` property: clips/scrolls the HORIZONTAL axis only, leaving the
      * vertical axis at its default. Use over [[overflow]] when only one axis should scroll, so the
      * other axis never reserves space for a scrollbar nor draws a stray scrollbar track.
      */
    def overflowX(v: Overflow): Style                  = appendProp(Prop.OverflowXProp(v))
    def overflowX(f: Overflow.type => Overflow): Style = overflowX(f(Overflow))

    /** Maps to the CSS `overflow-y` property: clips/scrolls the VERTICAL axis only, leaving the
      * horizontal axis at its default. Use over [[overflow]] when only one axis should scroll, so the
      * other axis never reserves space for a scrollbar nor draws a stray scrollbar track.
      */
    def overflowY(v: Overflow): Style                  = appendProp(Prop.OverflowYProp(v))
    def overflowY(f: Overflow.type => Overflow): Style = overflowY(f(Overflow))

    /** Maps to the standard CSS `scrollbar-width` property: `thin` renders a slimmer scrollbar than the
      * platform default, `none` hides it while keeping the element scrollable, `auto` is the default.
      * Pairs with [[scrollbarColor]] to theme an overflow container's scrollbar (current Chrome, Firefox,
      * and Safari honor both; other engines fall back to the native scrollbar).
      */
    def scrollbarWidth(v: ScrollbarWidth): Style                        = appendProp(Prop.ScrollbarWidthProp(v))
    def scrollbarWidth(f: ScrollbarWidth.type => ScrollbarWidth): Style = scrollbarWidth(f(ScrollbarWidth))

    /** Maps to the standard CSS `scrollbar-color` property: the first color paints the scrollbar thumb,
      * the second paints the track. Use a subtle thumb and a transparent or near-page track so the
      * scrollbar reads as part of the surface rather than an OS chrome slab.
      */
    def scrollbarColor(thumb: Color, track: Color): Style = appendProp(Prop.ScrollbarColorProp(thumb, track))

    /** Maps to the standard CSS `scrollbar-gutter` property: `stable` reserves space for the scrollbar
      * even while the content is short enough not to scroll, so a container whose content grows past the
      * viewport (or shrinks back) does not shift its layout sideways as the classic scrollbar appears and
      * disappears. Apply to the page scroll root (`html`) to stop SPA route swaps from nudging the layout.
      */
    def scrollbarGutter(v: ScrollbarGutter): Style                         = appendProp(Prop.ScrollbarGutterProp(v))
    def scrollbarGutter(f: ScrollbarGutter.type => ScrollbarGutter): Style = scrollbarGutter(f(ScrollbarGutter))

    // Sizing: any size including auto

    /** Sizing setters. Each accepts any [[kyo.Length]] (including `auto`) and clamps explicit lengths to non-negative. */
    def width(v: Length): Style     = appendProp(Prop.Width(clampSize(v)))
    def height(v: Length): Style    = appendProp(Prop.Height(clampSize(v)))
    def minWidth(v: Length): Style  = appendProp(Prop.MinWidth(clampSize(v)))
    def maxWidth(v: Length): Style  = appendProp(Prop.MaxWidth(clampSize(v)))
    def minHeight(v: Length): Style = appendProp(Prop.MinHeight(clampSize(v)))
    def maxHeight(v: Length): Style = appendProp(Prop.MaxHeight(clampSize(v)))

    // Typography

    def fontSize(v: Length.Px | Length.Em): Style = appendProp(Prop.FontSizeProp(clampSizeMin1(v)))

    def fontWeight(v: FontWeight): Style                    = appendProp(Prop.FontWeightProp(v))
    def fontWeight(f: FontWeight.type => FontWeight): Style = fontWeight(f(FontWeight))
    def bold: Style                                         = fontWeight(FontWeight.bold)

    def fontStyle(v: FontStyle): Style                   = appendProp(Prop.FontStyleProp(v))
    def fontStyle(f: FontStyle.type => FontStyle): Style = fontStyle(f(FontStyle))
    def italic: Style                                    = fontStyle(FontStyle.italic)

    def fontFamily(v: FontFamily): Style                    = appendProp(Prop.FontFamilyProp(v))
    def fontFamily(f: FontFamily.type => FontFamily): Style = fontFamily(f(FontFamily))

    def textAlign(v: TextAlign): Style                   = appendProp(Prop.TextAlignProp(v))
    def textAlign(f: TextAlign.type => TextAlign): Style = textAlign(f(TextAlign))

    def textDecoration(v: TextDecoration): Style                        = appendProp(Prop.TextDecorationProp(v))
    def textDecoration(f: TextDecoration.type => TextDecoration): Style = textDecoration(f(TextDecoration))
    def underline: Style                                                = textDecoration(TextDecoration.underline)
    def strikethrough: Style                                            = textDecoration(TextDecoration.strikethrough)

    def lineHeight(v: Double): Style = appendProp(Prop.LineHeightProp(math.max(0.1, v)))

    def letterSpacing(v: Length.Px | Length.Em): Style = appendProp(Prop.LetterSpacingProp(v))

    def textTransform(v: TextTransform): Style                       = appendProp(Prop.TextTransformProp(v))
    def textTransform(f: TextTransform.type => TextTransform): Style = textTransform(f(TextTransform))

    def textOverflow(v: TextOverflow): Style                      = appendProp(Prop.TextOverflowProp(v))
    def textOverflow(f: TextOverflow.type => TextOverflow): Style = textOverflow(f(TextOverflow))

    def textWrap(v: TextWrap): Style                  = appendProp(Prop.TextWrapProp(v))
    def textWrap(f: TextWrap.type => TextWrap): Style = textWrap(f(TextWrap))

    // Borders: width is px only

    def border(width: Length.Px, style: BorderStyle, c: Color): Style =
        val w = clampSize(width)
        appendProp(Prop.BorderWidthProp(w, w, w, w))
            .appendProp(Prop.BorderStyleProp(style))
            .appendProp(Prop.BorderColorProp(c, c, c, c))
    end border

    def border(width: Length.Px, style: BorderStyle, f: Color.type => Color): Style = border(width, style, f(Color))
    def border(width: Length.Px, c: Color): Style                                   = border(width, BorderStyle.solid, c)
    def border(width: Length.Px, f: Color.type => Color): Style                     = border(width, BorderStyle.solid, f(Color))

    def borderColor(c: Color): Style               = appendProp(Prop.BorderColorProp(c, c, c, c))
    def borderColor(f: Color.type => Color): Style = borderColor(f(Color))
    def borderColor(top: Color, right: Color, bottom: Color, left: Color): Style =
        appendProp(Prop.BorderColorProp(top, right, bottom, left))

    def borderWidth(v: Length.Px): Style =
        val c = clampSize(v)
        appendProp(Prop.BorderWidthProp(c, c, c, c))
    def borderWidth(top: Length.Px, right: Length.Px, bottom: Length.Px, left: Length.Px): Style =
        appendProp(Prop.BorderWidthProp(clampSize(top), clampSize(right), clampSize(bottom), clampSize(left)))

    def borderStyle(v: BorderStyle): Style                     = appendProp(Prop.BorderStyleProp(v))
    def borderStyle(f: BorderStyle.type => BorderStyle): Style = borderStyle(f(BorderStyle))

    def borderTop(width: Length.Px, c: Color): Style =
        val w = clampSize(width)
        val newWidths = find[Prop.BorderWidthProp]
            .map(p => Prop.BorderWidthProp(w, p.right, p.bottom, p.left))
            .getOrElse(Prop.BorderWidthProp(w, Length.zero, Length.zero, Length.zero))
        val newColors = find[Prop.BorderColorProp]
            .map(p => Prop.BorderColorProp(c, p.right, p.bottom, p.left))
            .getOrElse(Prop.BorderColorProp(c, Color.Transparent, Color.Transparent, Color.Transparent))
        withoutKind(newWidths)
            .appendProp(newWidths)
            .withoutKind(newColors)
            .appendProp(newColors)
    end borderTop
    def borderTop(width: Length.Px, f: Color.type => Color): Style = borderTop(width, f(Color))

    def borderRight(width: Length.Px, c: Color): Style =
        val w = clampSize(width)
        val newWidths = find[Prop.BorderWidthProp]
            .map(p => Prop.BorderWidthProp(p.top, w, p.bottom, p.left))
            .getOrElse(Prop.BorderWidthProp(Length.zero, w, Length.zero, Length.zero))
        val newColors = find[Prop.BorderColorProp]
            .map(p => Prop.BorderColorProp(p.top, c, p.bottom, p.left))
            .getOrElse(Prop.BorderColorProp(Color.Transparent, c, Color.Transparent, Color.Transparent))
        withoutKind(newWidths)
            .appendProp(newWidths)
            .withoutKind(newColors)
            .appendProp(newColors)
    end borderRight
    def borderRight(width: Length.Px, f: Color.type => Color): Style = borderRight(width, f(Color))

    def borderBottom(width: Length.Px, c: Color): Style =
        val w = clampSize(width)
        val newWidths = find[Prop.BorderWidthProp]
            .map(p => Prop.BorderWidthProp(p.top, p.right, w, p.left))
            .getOrElse(Prop.BorderWidthProp(Length.zero, Length.zero, w, Length.zero))
        val newColors = find[Prop.BorderColorProp]
            .map(p => Prop.BorderColorProp(p.top, p.right, c, p.left))
            .getOrElse(Prop.BorderColorProp(Color.Transparent, Color.Transparent, c, Color.Transparent))
        withoutKind(newWidths)
            .appendProp(newWidths)
            .withoutKind(newColors)
            .appendProp(newColors)
    end borderBottom
    def borderBottom(width: Length.Px, f: Color.type => Color): Style = borderBottom(width, f(Color))

    def borderLeft(width: Length.Px, c: Color): Style =
        val w = clampSize(width)
        val newWidths = find[Prop.BorderWidthProp]
            .map(p => Prop.BorderWidthProp(p.top, p.right, p.bottom, w))
            .getOrElse(Prop.BorderWidthProp(Length.zero, Length.zero, Length.zero, w))
        val newColors = find[Prop.BorderColorProp]
            .map(p => Prop.BorderColorProp(p.top, p.right, p.bottom, c))
            .getOrElse(Prop.BorderColorProp(Color.Transparent, Color.Transparent, Color.Transparent, c))
        withoutKind(newWidths)
            .appendProp(newWidths)
            .withoutKind(newColors)
            .appendProp(newColors)
    end borderLeft
    def borderLeft(width: Length.Px, f: Color.type => Color): Style = borderLeft(width, f(Color))

    // Border radius: px or pct

    def rounded(v: Length.Px | Length.Pct): Style =
        val c = clampSize(v)
        appendProp(Prop.BorderRadiusProp(c, c, c, c))
    def rounded(
        topLeft: Length.Px | Length.Pct,
        topRight: Length.Px | Length.Pct,
        bottomRight: Length.Px | Length.Pct,
        bottomLeft: Length.Px | Length.Pct
    ): Style =
        appendProp(Prop.BorderRadiusProp(clampSize(topLeft), clampSize(topRight), clampSize(bottomRight), clampSize(bottomLeft)))

    // Effects

    def shadow(
        x: Length.Px = Length.zero,
        y: Length.Px = Length.zero,
        blur: Length.Px = Length.zero,
        spread: Length.Px = Length.zero,
        c: Color = Color.rgba(0, 0, 0, 0.25)
    ): Style = appendProp(Prop.ShadowProp(x, y, clampSize(blur), spread, c))

    def shadow(x: Length.Px, y: Length.Px, blur: Length.Px, spread: Length.Px, f: Color.type => Color): Style =
        shadow(x, y, blur, spread, f(Color))

    def opacity(v: Double): Style = appendProp(Prop.OpacityProp(math.max(0.0, math.min(1.0, v))))

    // Cursor

    def cursor(v: Cursor): Style                = appendProp(Prop.CursorProp(v))
    def cursor(f: Cursor.type => Cursor): Style = cursor(f(Cursor))

    // Transform: px or pct

    def translate(x: Length.Px | Length.Pct, y: Length.Px | Length.Pct): Style = appendProp(Prop.TranslateProp(x, y))

    // Position

    def position(v: Position): Style                  = appendProp(Prop.PositionProp(v))
    def position(f: Position.type => Position): Style = position(f(Position))

    /** Sets the CSS `top` offset, the distance from the top edge of the containing block (or the
      * sticky/fixed reference). Pairs with `position(_.sticky)` or `position(_.relative)` to place a
      * pinned element, e.g. `top(60.px)` to stick a rail directly under a 60px header.
      */
    def top(v: Length): Style = appendProp(Prop.Top(v))

    /** Sets the CSS `right` / `bottom` / `left` offsets of a positioned element (the counterparts of
      * [[top]]); e.g. `position(_.absolute).top(8.px).right(8.px)` floats a control in the top-right
      * corner of a `position(_.relative)` container.
      */
    def right(v: Length): Style  = appendProp(Prop.Right(v))
    def bottom(v: Length): Style = appendProp(Prop.Bottom(v))
    def left(v: Length): Style   = appendProp(Prop.Left(v))

    /** Sets the CSS `z-index` stacking order of a positioned element. Higher values layer above lower
      * ones, e.g. `zIndex(100)` to keep a sticky header above scrolling page content.
      */
    def zIndex(v: Int): Style = appendProp(Prop.ZIndexProp(v))

    /** Sets the CSS `align-self`, overriding the parent flex container's `align-items` for this one
      * child. `align-self: flex-start` keeps a flex item at the cross-axis start instead of stretching
      * to the row height, e.g. a sticky rail in a flex row that must not grow to the article's height.
      */
    def alignSelf(v: Alignment): Style                   = appendProp(Prop.AlignSelf(v))
    def alignSelf(f: Alignment.type => Alignment): Style = alignSelf(f(Alignment))

    /** Sets the CSS `scroll-margin-top`, the extra space kept above an element when it is scrolled into
      * view (by a `#anchor` jump or `scrollIntoView`). Use it so a heading targeted by an in-page link
      * stops just below a sticky header rather than under it, e.g. `scrollMarginTop(72.px)` for a 60px
      * header plus a 12px gap.
      */
    def scrollMarginTop(v: Length): Style = appendProp(Prop.ScrollMarginTopProp(v))

    // Display

    /** Sets the CSS `display` mode, opting an element out of the default flex layout into normal
      * document flow (`block`/`inline`/`inline-block`) or list-item rendering. Use this for flowing
      * prose where inline runs must wrap within a line rather than stack as flex children.
      */
    def display(v: Display): Style                 = appendProp(Prop.DisplayProp(v))
    def display(f: Display.type => Display): Style = display(f(Display))
    def block: Style                               = display(Display.block)
    def inline: Style                              = display(Display.inline)
    def inlineBlock: Style                         = display(Display.inlineBlock)
    def listItem: Style                            = display(Display.listItem)
    def table: Style                               = display(Display.table)
    def tableRow: Style                            = display(Display.tableRow)
    def tableCell: Style                           = display(Display.tableCell)

    // List marker

    /** Sets the CSS `list-style-type` marker for a list (`disc`/`decimal`/`none`). The base reset
      * suppresses markers with `list-style: none`, so a flowing prose list restores them by setting
      * this back to `disc` (unordered) or `decimal` (ordered) on its `ul`/`ol`.
      */
    def listStyle(v: ListStyle): Style                   = appendProp(Prop.ListStyleProp(v))
    def listStyle(f: ListStyle.type => ListStyle): Style = listStyle(f(ListStyle))

    // Tables

    /** Sets the CSS `border-collapse` of a table. `collapse` merges adjacent cell borders into single
      * shared dividers so rows and columns read as one crisp grid; `separate` is the UA default.
      */
    def borderCollapse(v: BorderCollapse): Style                        = appendProp(Prop.BorderCollapseProp(v))
    def borderCollapse(f: BorderCollapse.type => BorderCollapse): Style = borderCollapse(f(BorderCollapse))

    // Flex grow/shrink

    def flexGrow(v: Double): Style   = appendProp(Prop.FlexGrowProp(math.max(0.0, v)))
    def flexShrink(v: Double): Style = appendProp(Prop.FlexShrinkProp(math.max(0.0, v)))
    def flexBasis(v: Length): Style  = appendProp(Prop.FlexBasisProp(clampSize(v)))

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
    def blur(v: Length.Px): Style    = appendProp(Prop.BlurProp(clampSize(v)))

    // Background gradient

    /** A linear-gradient background. Each stop is a `(Color, percentage-along-the-axis)` pair; at least two stops are required. This
      * overload takes the direction as a `GradientDirection.type => GradientDirection` selector for inline use (`bgGradient(_.toRight, ...)`).
      * Interpolates in sRGB (the CSS default); use the [[GradientColorSpace]] overload to interpolate in OKLCH/OKLAB.
      */
    def bgGradient(
        direction: GradientDirection.type => GradientDirection,
        stop1: (Color, Length.Pct),
        stop2: (Color, Length.Pct),
        stops: (Color, Length.Pct)*
    ): Style = bgGradient(direction(GradientDirection), GradientColorSpace.srgb, stop1, stop2, stops*)

    /** A linear-gradient background with an explicit [[kyo.Style.GradientDirection]]. Each stop is a `(Color, percentage)` pair; at least
      * two stops are required. Interpolates in sRGB (the CSS default).
      */
    def bgGradient(
        direction: GradientDirection,
        stop1: (Color, Length.Pct),
        stop2: (Color, Length.Pct),
        stops: (Color, Length.Pct)*
    ): Style = bgGradient(direction, GradientColorSpace.srgb, stop1, stop2, stops*)

    /** A linear-gradient background interpolated in the given [[kyo.Style.GradientColorSpace]]. The selector overload takes the direction
      * inline (`bgGradient(_.toBottom, GradientColorSpace.oklch, ...)`). Interpolating in `oklch`/`oklab` keeps the path between two colors
      * perceptually even, so a transition from a tinted color to a near-black does not sag through a muddy grey midtone and bands far less at
      * 8-bit sRGB output than the default `srgb` interpolation.
      */
    def bgGradient(
        direction: GradientDirection.type => GradientDirection,
        colorSpace: GradientColorSpace,
        stop1: (Color, Length.Pct),
        stop2: (Color, Length.Pct),
        stops: (Color, Length.Pct)*
    ): Style = bgGradient(direction(GradientDirection), colorSpace, stop1, stop2, stops*)

    /** A linear-gradient background with an explicit [[kyo.Style.GradientDirection]] and an explicit [[kyo.Style.GradientColorSpace]] to
      * interpolate in. Each stop is a `(Color, percentage)` pair; at least two stops are required. This is the canonical overload the others
      * delegate to.
      */
    def bgGradient(
        direction: GradientDirection,
        colorSpace: GradientColorSpace,
        stop1: (Color, Length.Pct),
        stop2: (Color, Length.Pct),
        stops: (Color, Length.Pct)*
    ): Style =
        val allStops  = stop1 +: stop2 +: stops
        val colors    = new Array[Color](allStops.length)
        val positions = new Array[Double](allStops.length)
        @tailrec def loop(i: Int): Unit =
            if i < allStops.length then
                colors(i) = allStops(i)._1
                positions(i) = math.max(0.0, math.min(100.0, allStops(i)._2.value))
                loop(i + 1)
        loop(0)
        appendProp(Prop.BgGradientProp(direction, colorSpace, Chunk.from(colors), Chunk.from(positions)))
    end bgGradient

    // Motion

    /** A CSS `transition` on a single property: `transition: <property> <durationMs>ms <easing>;`. The
      * duration is given in milliseconds and clamped to non-negative. Pass `TransitionProperty.all` (or
      * the no-property overload) to transition every animatable property at once. Use it so a hover or
      * active state's color/background change fades in smoothly rather than snapping.
      */
    def transition(property: TransitionProperty, durationMs: Int, easing: Easing): Style =
        appendProp(Prop.TransitionProp(property, math.max(0, durationMs), easing))
    def transition(property: TransitionProperty.type => TransitionProperty, durationMs: Int, easing: Easing.type => Easing): Style =
        transition(property(TransitionProperty), durationMs, easing(Easing))

    /** A CSS `transition: all <durationMs>ms <easing>;` shorthand transitioning every animatable
      * property. Equivalent to `transition(TransitionProperty.all, durationMs, easing)`.
      */
    def transition(durationMs: Int, easing: Easing): Style =
        transition(TransitionProperty.all, durationMs, easing)

    /** A CSS `animation: <name> <durationMs>ms <easing> both;` referencing a `@keyframes` block of the
      * given `name` (registered on the [[kyo.Stylesheet]] with [[kyo.Stylesheet.keyframes]]). The
      * `both` fill mode holds the keyframes' first frame before the animation starts and its last frame
      * after it ends, so an entrance animation does not flash the un-animated state. Duration is in
      * milliseconds, clamped to non-negative.
      */
    def animation(name: String, durationMs: Int, easing: Easing): Style =
        appendProp(Prop.AnimationProp(name, math.max(0, durationMs), easing))
    def animation(name: String, durationMs: Int, easing: Easing.type => Easing): Style =
        animation(name, durationMs, easing(Easing))

    /** Sets `animation-delay` in milliseconds: the offset before the element's [[animation]] starts. Pairs
      * with [[animation]] to stagger siblings into a cascade by giving each an increasing delay. A negative
      * value starts the animation as if it had already been running for that long.
      */
    def animationDelay(ms: Int): Style = appendProp(Prop.AnimationDelayProp(ms))

    /** Sets `stroke-dashoffset` (unitless) on an SVG element. Paired with an SVG path's `pathLength`
      * normalization, a keyframe that tweens this from 1 (the dash shifted fully off, line hidden) to 0
      * (line drawn) animates a stroke-draw without knowing the path's real length. Used by the landing
      * gap chart's scroll-revealed line.
      */
    def strokeDashoffset(v: Double): Style = appendProp(Prop.StrokeDashoffsetProp(v))

end Style

/** Companion of [[kyo.Style]]: the `empty` identity, factory setters, the [[kyo.Style.Color]] model, the value enums, and the
  * [[kyo.Style.Prop]] representation.
  *
  * Every instance setter has a matching factory of the same name here that builds from `empty`, so `Style.bg(c)` is exactly
  * `Style.empty.bg(c)`. Start a chain from either entry point; both yield equal values. `empty` is the identity for `++`.
  *
  * @see
  *   [[kyo.Style]] for the value-semantics and last-write-wins rules
  * @see
  *   [[kyo.Style.Color]] for color construction and [[kyo.Style.Prop]] for the encoded prop cases
  */
object Style:

    // ---- Style factory methods ----

    /** The identity `Style` with no properties; the unit for `++`. */
    val empty: Style = Style(Chunk.empty[Prop])

    def bg(c: Color): Style                                             = empty.bg(c)
    def bg(f: Color.type => Color): Style                               = empty.bg(f)
    def backgroundClip(v: BackgroundClip): Style                        = empty.backgroundClip(v)
    def backgroundClip(f: BackgroundClip.type => BackgroundClip): Style = empty.backgroundClip(f)
    def color(c: Color): Style                                          = empty.color(c)
    def color(f: Color.type => Color): Style                            = empty.color(f)
    def padding(all: Length.Px | Length.Pct | Length.Em): Style         = empty.padding(all)
    def padding(vertical: Length.Px | Length.Pct | Length.Em, horizontal: Length.Px | Length.Pct | Length.Em): Style =
        empty.padding(vertical, horizontal)
    def padding(
        top: Length.Px | Length.Pct | Length.Em,
        right: Length.Px | Length.Pct | Length.Em,
        bottom: Length.Px | Length.Pct | Length.Em,
        left: Length.Px | Length.Pct | Length.Em
    ): Style = empty.padding(top, right, bottom, left)
    def margin(all: Length): Style                                                  = empty.margin(all)
    def margin(vertical: Length, horizontal: Length): Style                         = empty.margin(vertical, horizontal)
    def margin(top: Length, right: Length, bottom: Length, left: Length): Style     = empty.margin(top, right, bottom, left)
    def gap(v: Length.Px | Length.Em): Style                                        = empty.gap(v)
    def row: Style                                                                  = empty.row
    def column: Style                                                               = empty.column
    def rowReverse: Style                                                           = empty.rowReverse
    def columnReverse: Style                                                        = empty.columnReverse
    def flexWrap(v: FlexWrap): Style                                                = empty.flexWrap(v)
    def flexWrap(f: FlexWrap.type => FlexWrap): Style                               = empty.flexWrap(f)
    def align(v: Alignment): Style                                                  = empty.align(v)
    def align(f: Alignment.type => Alignment): Style                                = empty.align(f)
    def justify(v: Justification): Style                                            = empty.justify(v)
    def justify(f: Justification.type => Justification): Style                      = empty.justify(f)
    def overflow(v: Overflow): Style                                                = empty.overflow(v)
    def overflow(f: Overflow.type => Overflow): Style                               = empty.overflow(f)
    def overflowX(v: Overflow): Style                                               = empty.overflowX(v)
    def overflowX(f: Overflow.type => Overflow): Style                              = empty.overflowX(f)
    def overflowY(v: Overflow): Style                                               = empty.overflowY(v)
    def overflowY(f: Overflow.type => Overflow): Style                              = empty.overflowY(f)
    def scrollbarWidth(v: ScrollbarWidth): Style                                    = empty.scrollbarWidth(v)
    def scrollbarWidth(f: ScrollbarWidth.type => ScrollbarWidth): Style             = empty.scrollbarWidth(f)
    def scrollbarColor(thumb: Color, track: Color): Style                           = empty.scrollbarColor(thumb, track)
    def scrollbarGutter(v: ScrollbarGutter): Style                                  = empty.scrollbarGutter(v)
    def scrollbarGutter(f: ScrollbarGutter.type => ScrollbarGutter): Style          = empty.scrollbarGutter(f)
    def width(v: Length): Style                                                     = empty.width(v)
    def height(v: Length): Style                                                    = empty.height(v)
    def minWidth(v: Length): Style                                                  = empty.minWidth(v)
    def maxWidth(v: Length): Style                                                  = empty.maxWidth(v)
    def minHeight(v: Length): Style                                                 = empty.minHeight(v)
    def maxHeight(v: Length): Style                                                 = empty.maxHeight(v)
    def fontSize(v: Length.Px | Length.Em): Style                                   = empty.fontSize(v)
    def fontWeight(v: FontWeight): Style                                            = empty.fontWeight(v)
    def fontWeight(f: FontWeight.type => FontWeight): Style                         = empty.fontWeight(f)
    def bold: Style                                                                 = empty.bold
    def italic: Style                                                               = empty.italic
    def fontStyle(v: FontStyle): Style                                              = empty.fontStyle(v)
    def fontStyle(f: FontStyle.type => FontStyle): Style                            = empty.fontStyle(f)
    def fontFamily(v: FontFamily): Style                                            = empty.fontFamily(v)
    def fontFamily(f: FontFamily.type => FontFamily): Style                         = empty.fontFamily(f)
    def textAlign(v: TextAlign): Style                                              = empty.textAlign(v)
    def textAlign(f: TextAlign.type => TextAlign): Style                            = empty.textAlign(f)
    def textDecoration(v: TextDecoration): Style                                    = empty.textDecoration(v)
    def textDecoration(f: TextDecoration.type => TextDecoration): Style             = empty.textDecoration(f)
    def underline: Style                                                            = empty.underline
    def strikethrough: Style                                                        = empty.strikethrough
    def lineHeight(v: Double): Style                                                = empty.lineHeight(v)
    def letterSpacing(v: Length.Px | Length.Em): Style                              = empty.letterSpacing(v)
    def textTransform(v: TextTransform): Style                                      = empty.textTransform(v)
    def textTransform(f: TextTransform.type => TextTransform): Style                = empty.textTransform(f)
    def textOverflow(v: TextOverflow): Style                                        = empty.textOverflow(v)
    def textOverflow(f: TextOverflow.type => TextOverflow): Style                   = empty.textOverflow(f)
    def textWrap(v: TextWrap): Style                                                = empty.textWrap(v)
    def textWrap(f: TextWrap.type => TextWrap): Style                               = empty.textWrap(f)
    def border(width: Length.Px, style: BorderStyle, c: Color): Style               = empty.border(width, style, c)
    def border(width: Length.Px, style: BorderStyle, f: Color.type => Color): Style = empty.border(width, style, f)
    def border(width: Length.Px, c: Color): Style                                   = empty.border(width, c)
    def border(width: Length.Px, f: Color.type => Color): Style                     = empty.border(width, f)
    def borderColor(c: Color): Style                                                = empty.borderColor(c)
    def borderColor(f: Color.type => Color): Style                                  = empty.borderColor(f)
    def borderColor(top: Color, right: Color, bottom: Color, left: Color): Style    = empty.borderColor(top, right, bottom, left)
    def borderWidth(v: Length.Px): Style                                            = empty.borderWidth(v)
    def borderWidth(top: Length.Px, right: Length.Px, bottom: Length.Px, left: Length.Px): Style =
        empty.borderWidth(top, right, bottom, left)
    def borderStyle(v: BorderStyle): Style                            = empty.borderStyle(v)
    def borderStyle(f: BorderStyle.type => BorderStyle): Style        = empty.borderStyle(f)
    def borderTop(width: Length.Px, c: Color): Style                  = empty.borderTop(width, c)
    def borderTop(width: Length.Px, f: Color.type => Color): Style    = empty.borderTop(width, f)
    def borderRight(width: Length.Px, c: Color): Style                = empty.borderRight(width, c)
    def borderRight(width: Length.Px, f: Color.type => Color): Style  = empty.borderRight(width, f)
    def borderBottom(width: Length.Px, c: Color): Style               = empty.borderBottom(width, c)
    def borderBottom(width: Length.Px, f: Color.type => Color): Style = empty.borderBottom(width, f)
    def borderLeft(width: Length.Px, c: Color): Style                 = empty.borderLeft(width, c)
    def borderLeft(width: Length.Px, f: Color.type => Color): Style   = empty.borderLeft(width, f)
    def rounded(v: Length.Px | Length.Pct): Style                     = empty.rounded(v)
    def rounded(
        topLeft: Length.Px | Length.Pct,
        topRight: Length.Px | Length.Pct,
        bottomRight: Length.Px | Length.Pct,
        bottomLeft: Length.Px | Length.Pct
    ): Style = empty.rounded(topLeft, topRight, bottomRight, bottomLeft)
    def shadow(
        x: Length.Px = Length.zero,
        y: Length.Px = Length.zero,
        blur: Length.Px = Length.zero,
        spread: Length.Px = Length.zero,
        c: Color = Color.rgba(0, 0, 0, 0.25)
    ): Style = empty.shadow(x, y, blur, spread, c)
    def shadow(x: Length.Px, y: Length.Px, blur: Length.Px, spread: Length.Px, f: Color.type => Color): Style =
        empty.shadow(x, y, blur, spread, f)
    def opacity(v: Double): Style                                              = empty.opacity(v)
    def cursor(v: Cursor): Style                                               = empty.cursor(v)
    def cursor(f: Cursor.type => Cursor): Style                                = empty.cursor(f)
    def translate(x: Length.Px | Length.Pct, y: Length.Px | Length.Pct): Style = empty.translate(x, y)
    def position(v: Position): Style                                           = empty.position(v)
    def position(f: Position.type => Position): Style                          = empty.position(f)
    def top(v: Length): Style                                                  = empty.top(v)
    def right(v: Length): Style                                                = empty.right(v)
    def bottom(v: Length): Style                                               = empty.bottom(v)
    def left(v: Length): Style                                                 = empty.left(v)
    def zIndex(v: Int): Style                                                  = empty.zIndex(v)
    def alignSelf(v: Alignment): Style                                         = empty.alignSelf(v)
    def alignSelf(f: Alignment.type => Alignment): Style                       = empty.alignSelf(f)
    def scrollMarginTop(v: Length): Style                                      = empty.scrollMarginTop(v)
    def display(v: Display): Style                                             = empty.display(v)
    def display(f: Display.type => Display): Style                             = empty.display(f)
    def block: Style                                                           = empty.block
    def inline: Style                                                          = empty.inline
    def inlineBlock: Style                                                     = empty.inlineBlock
    def listItem: Style                                                        = empty.listItem
    def table: Style                                                           = empty.table
    def tableRow: Style                                                        = empty.tableRow
    def tableCell: Style                                                       = empty.tableCell
    def listStyle(v: ListStyle): Style                                         = empty.listStyle(v)
    def listStyle(f: ListStyle.type => ListStyle): Style                       = empty.listStyle(f)
    def borderCollapse(v: BorderCollapse): Style                               = empty.borderCollapse(v)
    def borderCollapse(f: BorderCollapse.type => BorderCollapse): Style        = empty.borderCollapse(f)
    def flexGrow(v: Double): Style                                             = empty.flexGrow(v)
    def flexShrink(v: Double): Style                                           = empty.flexShrink(v)
    def flexBasis(v: Length): Style                                            = empty.flexBasis(v)
    def displayNone: Style                                                     = empty.displayNone
    def brightness(v: Double): Style                                           = empty.brightness(v)
    def contrast(v: Double): Style                                             = empty.contrast(v)
    def grayscale(v: Double): Style                                            = empty.grayscale(v)
    def sepia(v: Double): Style                                                = empty.sepia(v)
    def invert(v: Double): Style                                               = empty.invert(v)
    def saturate(v: Double): Style                                             = empty.saturate(v)
    def hueRotate(v: Double): Style                                            = empty.hueRotate(v)
    def blur(v: Length.Px): Style                                              = empty.blur(v)
    def bgGradient(
        direction: GradientDirection,
        stop1: (Color, Length.Pct),
        stop2: (Color, Length.Pct),
        stops: (Color, Length.Pct)*
    ): Style =
        empty.bgGradient(direction, stop1, stop2, stops*)
    def bgGradient(
        direction: GradientDirection.type => GradientDirection,
        stop1: (Color, Length.Pct),
        stop2: (Color, Length.Pct),
        stops: (Color, Length.Pct)*
    ): Style = empty.bgGradient(direction, stop1, stop2, stops*)
    def bgGradient(
        direction: GradientDirection,
        colorSpace: GradientColorSpace,
        stop1: (Color, Length.Pct),
        stop2: (Color, Length.Pct),
        stops: (Color, Length.Pct)*
    ): Style = empty.bgGradient(direction, colorSpace, stop1, stop2, stops*)
    def bgGradient(
        direction: GradientDirection.type => GradientDirection,
        colorSpace: GradientColorSpace,
        stop1: (Color, Length.Pct),
        stop2: (Color, Length.Pct),
        stops: (Color, Length.Pct)*
    ): Style = empty.bgGradient(direction, colorSpace, stop1, stop2, stops*)
    def transition(property: TransitionProperty, durationMs: Int, easing: Easing): Style =
        empty.transition(property, durationMs, easing)
    def transition(property: TransitionProperty.type => TransitionProperty, durationMs: Int, easing: Easing.type => Easing): Style =
        empty.transition(property, durationMs, easing)
    def transition(durationMs: Int, easing: Easing): Style              = empty.transition(durationMs, easing)
    def animation(name: String, durationMs: Int, easing: Easing): Style = empty.animation(name, durationMs, easing)
    def animation(name: String, durationMs: Int, easing: Easing.type => Easing): Style =
        empty.animation(name, durationMs, easing)
    def animationDelay(ms: Int): Style          = empty.animationDelay(ms)
    def strokeDashoffset(v: Double): Style      = empty.strokeDashoffset(v)
    def hover(s: Style): Style                  = empty.hover(s)
    def hover(f: Style.type => Style): Style    = empty.hover(f)
    def focus(s: Style): Style                  = empty.focus(s)
    def focus(f: Style.type => Style): Style    = empty.focus(f)
    def active(s: Style): Style                 = empty.active(s)
    def active(f: Style.type => Style): Style   = empty.active(f)
    def disabled(s: Style): Style               = empty.disabled(s)
    def disabled(f: Style.type => Style): Style = empty.disabled(f)

    // ---- Color ----

    /** A color value used by background, text, border, shadow, and gradient setters.
      *
      * `Color` is a sealed ADT with private constructors, so colors are built through the [[kyo.Style.Color]] companion: the `hex`, `rgb`,
      * and `rgba` factories, or the named constants (`Color.white`, `Color.blue`, ...). Construction validates and clamps: `rgb`/`rgba`
      * clamp components to `[0, 255]` and alpha to `[0, 1]`, while `hex` returns a `Maybe` because an invalid string yields `Absent` rather
      * than throwing.
      *
      * @see
      *   [[kyo.Style.Color.hex]], [[kyo.Style.Color.rgb]], [[kyo.Style.Color.rgba]] for construction
      */
    sealed abstract class Color derives CanEqual

    object Color:
        final case class Hex private[kyo] (value: String)                      extends Color
        final case class Rgb private[kyo] (r: Int, g: Int, b: Int)             extends Color
        final case class Rgba private[kyo] (r: Int, g: Int, b: Int, a: Double) extends Color
        case object Transparent                                                extends Color
        final case class Var private[kyo] (name: String)                       extends Color

        private def clamp255(v: Int): Int      = math.max(0, math.min(255, v))
        private def clamp01(v: Double): Double = math.max(0.0, math.min(1.0, v))

        /** Parses a hex color. Accepts 3, 4, 6, or 8 hex digits with or without a leading `#`; returns `Absent` for any other length or any
          * non-hex character. Never throws.
          */
        def hex(value: String): Maybe[Color] =
            val v = if value.nonEmpty && value.charAt(0) != '#' then "#" + value else value
            if v.isEmpty || v.charAt(0) != '#' then Absent
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
                if valid && (len == 4 || len == 5 || len == 7 || len == 9) then Present(Hex(v))
                else Absent
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

        /** A CSS custom-property reference: `Color.variable("accent")` renders as `var(--accent)` in
          * declarations. Use it with [[kyo.Stylesheet.vars]] to define the property and [[kyo.Style.color]]/
          * [[kyo.Style.bg]] to reference it.
          */
        def variable(name: String): Color = Var(name)
    end Color

    // ---- Enums ----

    enum FlexDirection derives CanEqual:
        case row, column, rowReverse, columnReverse

    /** Maps to the CSS `flex-wrap` property. */
    enum FlexWrap derives CanEqual:
        case wrap, noWrap

    /** Maps to the CSS `align-items` property (cross-axis alignment). */
    enum Alignment derives CanEqual:
        case start, center, end, stretch, baseline

    /** Maps to the CSS `justify-content` property (main-axis distribution). */
    enum Justification derives CanEqual:
        case start, center, end, spaceBetween, spaceAround, spaceEvenly

    /** Maps to the CSS `overflow` property. */
    enum Overflow derives CanEqual:
        case visible, hidden, scroll, auto

    /** Maps to the standard CSS `scrollbar-width` property: `auto` is the platform default, `thin`
      * requests a slimmer scrollbar, `none` hides it while the element stays scrollable.
      */
    enum ScrollbarWidth derives CanEqual:
        case auto, thin, none

    /** Maps to the standard CSS `scrollbar-gutter` property: `auto` is the default (the gutter exists only
      * while scrolling), `stable` always reserves the gutter, `stableBothEdges` reserves it on both edges.
      */
    enum ScrollbarGutter derives CanEqual:
        case auto, stable, stableBothEdges

    /** Maps to the CSS `background-clip` property. */
    enum BackgroundClip derives CanEqual:
        case borderBox, paddingBox, contentBox

    /** Maps to the CSS `font-weight` property. */
    enum FontWeight derives CanEqual:
        case normal, bold, w100, w200, w300, w400, w500, w600, w700, w800, w900

    enum FontStyle derives CanEqual:
        case normal, italic

    /** Maps to the CSS `text-align` property. */
    enum TextAlign derives CanEqual:
        case left, center, right, justify

    /** Maps to the CSS `text-decoration` property. */
    enum TextDecoration derives CanEqual:
        case none, underline, strikethrough

    /** Maps to the CSS `text-transform` property. */
    enum TextTransform derives CanEqual:
        case none, uppercase, lowercase, capitalize

    /** Maps to the CSS `text-overflow` property. */
    enum TextOverflow derives CanEqual:
        case clip, ellipsis

    /** Maps to the CSS `text-wrap` / `white-space` behavior.
      *
      *   - `wrap`: allow long words to break so an overflowing token wraps within the box (`overflow-wrap: break-word`).
      *   - `noWrap`: keep words intact, never breaking inside one (`overflow-wrap: normal`).
      *   - `ellipsis`: keep words intact and mark clipped overflow with an ellipsis.
      *   - `balance`: balance the lines of a short block (a heading) so each line is close to the same width (`text-wrap: balance`),
      *     avoiding a last line stranding a single short word.
      *   - `pretty`: optimize the last few lines of a longer block (body prose) to avoid orphans and bad breaks (`text-wrap: pretty`).
      */
    enum TextWrap derives CanEqual:
        case wrap, noWrap, ellipsis, balance, pretty

    /** Maps to the CSS `font-family` property; `Custom` carries an arbitrary family name. */
    enum FontFamily derives CanEqual:
        case SansSerif, Serif, Monospace, Cursive, Fantasy, SystemUi
        case Custom(name: String)
    end FontFamily

    /** Maps to the CSS `border-style` property. */
    enum BorderStyle derives CanEqual:
        case none, solid, dashed, dotted

    /** Maps to the CSS `cursor` property (`wait_` renders as `wait`, `defaultCursor` as `default`). */
    enum Cursor derives CanEqual:
        case defaultCursor, pointer, text, move, notAllowed, crosshair, help, wait_, grab, grabbing

    /** Maps to the CSS `position` property.
      *
      *   - `flow`: normal layout (`position: static`).
      *   - `overlay`: a full-viewport fixed overlay (`position: fixed` pinned to all four edges).
      *   - `relative`: a positioned containing block (`position: relative`) for absolutely-positioned
      *     descendants, without shifting the element itself.
      *   - `dropdown`: an absolutely-positioned panel anchored under the right edge of its nearest
      *     positioned ancestor (`position: absolute; top: 100%; right: 0`), layered above sibling
      *     content. Used for menus and result panels that drop below a trigger.
      *   - `sticky`: a sticky-positioned element (`position: sticky`) that scrolls with the document
      *     until it reaches its sticky offset, then pins in place. `sticky` emits ONLY the
      *     `position: sticky` declaration; the offset and stacking are set separately with
      *     [[kyo.Style.top]] and [[kyo.Style.zIndex]], so a sticky element can pin at the viewport top
      *     (`top(0.px).zIndex(100)`, e.g. a site header) or just below another sticky element
      *     (`top(60.px)`, e.g. a rail under a 60px header) without a baked-in offset.
      *   - `absolute`: a positioned element (`position: absolute`) taken out of flow and placed against
      *     its nearest positioned ancestor; the offsets and size are set separately.
      *   - `fixed`: a viewport-pinned element (`position: fixed`) taken out of flow, with the offsets and
      *     size set separately (unlike `overlay`, which bakes in all-four-edges full-viewport sizing). For
      *     a partial pinned element: a corner floating button (`fixed` + `left`/`bottom`) or an edge
      *     drawer (`fixed` + `top`/`left`/`bottom` + `width`).
      */
    enum Position derives CanEqual:
        case flow, overlay, relative, dropdown, sticky, absolute, fixed

    /** Maps to the CSS `display` property for opting out of the default flex layout.
      *
      *   - `block`: a block-level box (`display: block`) that stacks vertically and fills its line.
      *   - `inline`: an inline box (`display: inline`) that flows within a line of text and wraps with
      *     it, so inline runs (code, links, emphasis) sit within a sentence instead of stacking.
      *   - `inlineBlock`: an inline-level box that still honors width/height and padding
      *     (`display: inline-block`), used for inline pills that need box metrics yet flow in a line.
      *   - `listItem`: a list item (`display: list-item`) so the element renders its list marker.
      *   - `table`: a table box (`display: table`) that runs the CSS table-layout algorithm, so an
      *     explicit `width` is distributed across columns instead of shrinking each column to content.
      *   - `tableRow`: a table row (`display: table-row`), the row box inside a `table`.
      *   - `tableCell`: a table cell (`display: table-cell`) that participates in the shared column
      *     widths and border grid of its `table`.
      *   - `flex` / `inlineFlex`: a flex container (`display: flex` / `display: inline-flex`). The
      *     `Style.row`/`Style.column` direction helpers only set `flex-direction` and rely on an element
      *     already being a flex box; use `flex` to force it where a more specific rule (e.g. a prose
      *     `a { display: inline }`) would otherwise win the cascade.
      */
    enum Display derives CanEqual:
        case block, inline, inlineBlock, flex, inlineFlex, listItem, table, tableRow, tableCell

    /** Maps to the CSS `list-style-type` property: the marker a `list-item` renders. `disc` is the
      * filled bullet for unordered lists, `decimal` the number for ordered lists, `none` suppresses
      * the marker (the base reset default).
      */
    enum ListStyle derives CanEqual:
        case disc, decimal, none

    /** Maps to the CSS `border-collapse` property of a table: `collapse` merges adjacent cell borders
      * into single shared lines (so row and column dividers render as one crisp rule), `separate` is
      * the UA default where each cell keeps its own border separated by `border-spacing`.
      */
    enum BorderCollapse derives CanEqual:
        case collapse, separate

    /** Direction of a background gradient (the `to ...` keyword of a CSS `linear-gradient`). */
    enum GradientDirection derives CanEqual:
        case toRight, toLeft, toTop, toBottom, toTopRight, toTopLeft, toBottomRight, toBottomLeft

    /** The color space a `linear-gradient` interpolates its stops in (the `in <space>` keyword of a CSS gradient).
      *
      *   - `srgb`: the CSS default. Interpolation is linear in the sRGB channels, which can sag through a muddy desaturated midtone
      *     between a saturated color and a dark/near-black, and bands visibly at 8-bit output across a long, low-contrast span.
      *   - `oklch`: interpolates in the perceptually-uniform OKLCH space (lightness, chroma, hue). The path between two colors stays even
      *     and the hue does not pass through grey, so the same two stops read smooth instead of muddy and band far less.
      *   - `oklab`: the Cartesian OKLAB sibling of `oklch`; also perceptually uniform, interpolating the a/b axes rather than chroma/hue.
      */
    enum GradientColorSpace derives CanEqual:
        case srgb, oklch, oklab

    /** A CSS timing function for a `transition` or `animation`.
      *
      *   - `ease`: the UA default, a gentle accelerate-then-decelerate curve.
      *   - `linear`: constant velocity from start to end.
      *   - `easeIn`: starts slow, accelerates into the end.
      *   - `easeOut`: starts fast, decelerates into the end (the natural feel for an element settling
      *     into place, e.g. a panel sliding in).
      *   - `easeInOut`: slow at both ends, faster in the middle.
      */
    enum Easing derives CanEqual:
        case ease, linear, easeIn, easeOut, easeInOut

    /** The CSS property a `transition` animates. `all` transitions every animatable property at once
      * (the common shorthand); the named cases target a single property so an element can fade its
      * background and color without animating layout. `Custom` carries an arbitrary CSS property name
      * for the rare case the named set does not cover.
      */
    enum TransitionProperty derives CanEqual:
        case all, backgroundColor, color, borderColor, opacity, transform
        case Custom(name: String)
    end TransitionProperty

    // ---- Prop ADT ----

    /** The encoded representation of a single style property that a [[kyo.Style]] stores.
      *
      * Each setter on `Style` produces exactly one `Prop` case, and a `Style` is just the ordered `Chunk` of these. The enum case is the
      * "kind" key that drives last-write-wins dedup and `++` merging, so two writes of the same case (e.g. two `Width` props) collapse to the
      * last one. Pseudo-states are themselves props (`HoverProp`, `FocusProp`, ...) carrying a nested `Style`.
      *
      * `Prop` is pattern-matchable and is the target of the introspection API: it is the RHS of `find`, `filter`, and `without`. While
      * exposed for inspection and testing, most callers build styles through the fluent setters rather than these cases directly.
      *
      * @see
      *   [[kyo.Style.find]], [[kyo.Style.filter]], [[kyo.Style.without]] for querying and removing props
      */
    enum Prop derives CanEqual:
        // Background
        case BgColor(color: Color)
        case TextColor(color: Color)
        // Layout
        case Padding(top: Length, right: Length, bottom: Length, left: Length)
        case Margin(top: Length, right: Length, bottom: Length, left: Length)
        case Gap(value: Length)
        case FlexDirectionProp(value: FlexDirection)
        case FlexWrapProp(value: FlexWrap)
        case Align(value: Alignment)
        case Justify(value: Justification)
        case OverflowProp(value: Overflow)
        case OverflowXProp(value: Overflow)
        case OverflowYProp(value: Overflow)
        case ScrollbarWidthProp(value: ScrollbarWidth)
        case ScrollbarColorProp(thumb: Color, track: Color)
        case ScrollbarGutterProp(value: ScrollbarGutter)
        case BackgroundClipProp(value: BackgroundClip)
        // Sizing
        case Width(value: Length)
        case Height(value: Length)
        case MinWidth(value: Length)
        case MaxWidth(value: Length)
        case MinHeight(value: Length)
        case MaxHeight(value: Length)
        // Typography
        case FontSizeProp(value: Length)
        case FontWeightProp(value: FontWeight)
        case FontStyleProp(value: FontStyle)
        case FontFamilyProp(value: FontFamily)
        case TextAlignProp(value: TextAlign)
        case TextDecorationProp(value: TextDecoration)
        case LineHeightProp(value: Double)
        case LetterSpacingProp(value: Length)
        case TextTransformProp(value: TextTransform)
        case TextOverflowProp(value: TextOverflow)
        case TextWrapProp(value: TextWrap)
        // Borders
        case BorderColorProp(top: Color, right: Color, bottom: Color, left: Color)
        case BorderWidthProp(top: Length, right: Length, bottom: Length, left: Length)
        case BorderStyleProp(value: BorderStyle)
        case BorderRadiusProp(topLeft: Length, topRight: Length, bottomRight: Length, bottomLeft: Length)
        // Effects
        case ShadowProp(x: Length, y: Length, blur: Length, spread: Length, color: Color)
        case OpacityProp(value: Double)
        // Cursor
        case CursorProp(value: Cursor)
        // Transform
        case TranslateProp(x: Length, y: Length)
        // Position
        case PositionProp(value: Position)
        case Top(value: Length)
        case Right(value: Length)
        case Bottom(value: Length)
        case Left(value: Length)
        case ZIndexProp(value: Int)
        case AlignSelf(value: Alignment)
        case ScrollMarginTopProp(value: Length)
        // Display
        case DisplayProp(value: Display)
        // List marker
        case ListStyleProp(value: ListStyle)
        // Tables
        case BorderCollapseProp(value: BorderCollapse)
        // Visibility
        case HiddenProp
        // Flex grow/shrink
        case FlexGrowProp(value: Double)
        case FlexShrinkProp(value: Double)
        case FlexBasisProp(value: Length)
        // Filters
        case BrightnessProp(value: Double)
        case ContrastProp(value: Double)
        case GrayscaleProp(value: Double)
        case SepiaProp(value: Double)
        case InvertProp(value: Double)
        case SaturateProp(value: Double)
        case HueRotateProp(value: Double)
        case BlurProp(value: Length)
        // Background gradient
        case BgGradientProp(direction: GradientDirection, colorSpace: GradientColorSpace, colors: Chunk[Color], positions: Chunk[Double])
        // Motion
        case TransitionProp(property: TransitionProperty, durationMs: Int, easing: Easing)
        case AnimationProp(name: String, durationMs: Int, easing: Easing)
        case AnimationDelayProp(ms: Int)
        case StrokeDashoffsetProp(value: Double)
        // Pseudo-states
        case HoverProp(style: Style)
        case FocusProp(style: Style)
        case ActiveProp(style: Style)
        case DisabledProp(style: Style)
    end Prop

end Style
