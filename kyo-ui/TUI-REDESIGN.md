# TUI Backend Redesign

## Safety constraints

- No `null` — use `Maybe[A]` (allocationless)
- No `while` — use `@tailrec def loop`
- No `return` — use guard nesting or method decomposition
- No `throw` — use `Result` at boundaries
- No `asInstanceOf` / `isInstanceOf` — use pattern matching
- No `Text` — use `String` directly
- No bare `Long` for packed data — use opaque types

## Pipeline

```
Each frame:
  1. beginFrame — reset collectors, clear screen, scan focus
  2. render    — walk UI tree (resolve style → paint box → widget content)
  3. endFrame  — paint overlays, diff-flush, position cursor
  4. await     — wait for signal change or input event
```

## Types

### CellStyle

Opaque packed cell attributes. Encapsulates the bit layout so no raw bit manipulation leaks.

```scala
opaque type CellStyle = Long
object CellStyle:
    val Empty: CellStyle = 0L

    def apply(fg: Int, bg: Int, bold: Boolean, italic: Boolean,
              underline: Boolean, strikethrough: Boolean, dim: Boolean): CellStyle =
        // pack: fg(25 bits) | bg(25 bits) | attrs(6 bits)
        ...

    extension (s: CellStyle)
        def fg: Int = ...
        def bg: Int = ...
        def bold: Boolean = ...
        def italic: Boolean = ...
        def underline: Boolean = ...
        def strikethrough: Boolean = ...
        def dim: Boolean = ...
        def withFg(v: Int): CellStyle = ...
        def withBg(v: Int): CellStyle = ...
```

### Screen

Pre-allocated cell grid. Owns double-buffered arrays and ANSI output. One instance, reused across frames.

```scala
class Screen(var width: Int, var height: Int):

    // Double-buffered cell data (typed via CellStyle, not raw Long)
    private var curChars: Array[Char] = ...
    private var curStyle: Array[CellStyle] = ...
    private var prevChars: Array[Char] = ...
    private var prevStyle: Array[CellStyle] = ...

    // Clip stack (overflow:hidden containers)
    private var clipX0, clipY0, clipX1, clipY1: Int = ...
    private val clipStack: Array[Int] = new Array[Int](32) // 8 levels × 4
    private var clipDepth: Int = 0

    def set(x: Int, y: Int, ch: Char, style: CellStyle): Unit =
        if x >= clipX0 && x < clipX1 && y >= clipY0 && y < clipY1 then
            val idx = y * width + x
            curChars(idx) = ch
            curStyle(idx) = style

    def fillBg(x: Int, y: Int, w: Int, h: Int, color: Int): Unit =
        @tailrec def row(ry: Int): Unit =
            if ry < y + h then
                @tailrec def col(rx: Int): Unit =
                    if rx < x + w then
                        set(rx, ry, curChars(ry * width + rx), curStyle(ry * width + rx).withBg(color))
                        col(rx + 1)
                col(x)
                row(ry + 1)
        row(y)

    def pushClip(x0: Int, y0: Int, x1: Int, y1: Int): Unit = ...
    def popClip(): Unit = ...

    def clear(): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < curChars.length then
                curChars(i) = ' '
                curStyle(i) = CellStyle.Empty
                loop(i + 1)
        loop(0)

    def flush(out: OutputStream, colorTier: Int): Unit =
        @tailrec def scan(i: Int): Unit =
            if i < width * height then
                if curChars(i) != prevChars(i) || curStyle(i) != prevStyle(i) then
                    // emit ANSI: move cursor, set style, write char
                    ...
                scan(i + 1)
        scan(0)
        System.arraycopy(curChars, 0, prevChars, 0, curChars.length)
        System.arraycopy(curStyle, 0, prevStyle, 0, curStyle.length)

    def resize(w: Int, h: Int): Unit = ...
```

### Canvas

Stateless drawing helpers over Screen. No text layout — widgets draw chars directly.

```scala
class Canvas(val screen: Screen):

    def drawString(x: Int, y: Int, maxW: Int, s: String, offset: Int, style: CellStyle): Int =
        @tailrec def loop(i: Int, col: Int): Int =
            if i >= s.length || col >= x + maxW then col - x
            else
                screen.set(col, y, s.charAt(i), style)
                loop(i + 1, col + 1)
        loop(offset, x)

    def drawChar(x: Int, y: Int, ch: Char, style: CellStyle): Unit =
        screen.set(x, y, ch, style)

    def hline(x: Int, y: Int, w: Int, ch: Char, style: CellStyle): Unit =
        @tailrec def loop(col: Int): Unit =
            if col < x + w then
                screen.set(col, y, ch, style)
                loop(col + 1)
        loop(x)

    def border(x: Int, y: Int, w: Int, h: Int, rs: ResolvedStyle): Unit =
        // Reads border fields from rs: borderStyle, border{T,R,B,L},
        // round{TL,TR,BR,BL}, borderColor{T,R,B,L}
        // Picks Unicode box chars based on borderStyle, draws via screen.set
        ...

    def applyFilters(x: Int, y: Int, w: Int, h: Int, bits: Int, vals: Array[Double]): Unit =
        // Post-processing (brightness, contrast, blur, etc.)
        ...
```

### ResolvedStyle

Pre-allocated mutable struct on RenderCtx. Replaces the 30+ local vars approach — adding a new style prop means adding one field + one match case.

```scala
class ResolvedStyle:
    // Inherited (initialized from RenderCtx at start of each element)
    var fg: Int = TuiColor.Absent
    var bg: Int = TuiColor.Absent
    var bold, italic, underline, strikethrough, dim: Boolean = false

    // Box
    var padT, padR, padB, padL: Int = 0
    var borderT, borderR, borderB, borderL: Boolean = false
    var borderStyle: Int = 0
    var borderColorT, borderColorR, borderColorB, borderColorL: Int = TuiColor.Absent
    var roundTL, roundTR, roundBR, roundBL: Boolean = false

    // Layout
    var direction, align, justify, gap, overflow: Int = 0
    var flexGrow: Double = 0.0
    var flexShrink: Double = 1.0

    // Text
    var textAlign, textTransform: Int = 0
    var textWrap: Int = 1
    var textOverflow: Boolean = false

    // Visibility
    var hidden, overlay: Boolean = false

    // Shadow
    var shadowColor: Int = TuiColor.Absent
    var shadowX, shadowY, shadowSpread: Int = 0

    // Filters
    var filterBits: Int = 0
    val filterVals: Array[Double] = new Array[Double](8) // pre-allocated

    // Pseudo-state styles
    var hoverStyle: Maybe[Style] = Absent
    var focusStyle: Maybe[Style] = Absent
    var activeStyle: Maybe[Style] = Absent
    var disabledStyle: Maybe[Style] = Absent

    def inherit(ctx: RenderCtx): Unit =
        fg = ctx.fg; bg = ctx.bg
        bold = ctx.bold; italic = ctx.italic
        underline = ctx.underline; strikethrough = ctx.strikethrough; dim = ctx.dim
        // Reset non-inherited fields to defaults
        padT = 0; padR = 0; padB = 0; padL = 0
        borderT = false; borderR = false; borderB = false; borderL = false
        borderStyle = 0
        borderColorT = TuiColor.Absent; borderColorR = TuiColor.Absent
        borderColorB = TuiColor.Absent; borderColorL = TuiColor.Absent
        roundTL = false; roundTR = false; roundBR = false; roundBL = false
        direction = 0; align = 0; justify = 0; gap = 0; overflow = 0
        flexGrow = 0.0; flexShrink = 1.0
        textAlign = 0; textTransform = 0; textWrap = 1; textOverflow = false
        hidden = false; overlay = false
        shadowColor = TuiColor.Absent; shadowX = 0; shadowY = 0; shadowSpread = 0
        filterBits = 0
        hoverStyle = Absent; focusStyle = Absent
        activeStyle = Absent; disabledStyle = Absent

    def applyProps(style: Style): Unit =
        style.props.foreach { prop =>
            prop match
                case Style.Prop.BgColor(c)     => bg = TuiColor.resolve(c)
                case Style.Prop.TextColor(c)   => fg = TuiColor.resolve(c)
                case Style.Prop.Padding(t, r, b, l) =>
                    padT = toCell(t); padR = toCell(r); padB = toCell(b); padL = toCell(l)
                case Style.Prop.BorderWidthProp(t, r, b, l) =>
                    borderT = toCell(t) > 0; borderR = toCell(r) > 0
                    borderB = toCell(b) > 0; borderL = toCell(l) > 0
                case Style.Prop.BorderStyleProp(v)  => borderStyle = toBorderStyle(v)
                case Style.Prop.BorderColorProp(t, r, b, l) =>
                    borderColorT = TuiColor.resolve(t); borderColorR = TuiColor.resolve(r)
                    borderColorB = TuiColor.resolve(b); borderColorL = TuiColor.resolve(l)
                case Style.Prop.FlexDirectionProp(d) => direction = toDirection(d)
                case Style.Prop.Align(v)            => align = toAlign(v)
                case Style.Prop.Justify(v)          => justify = toJustify(v)
                case Style.Prop.Gap(v)              => gap = toCell(v)
                case Style.Prop.OverflowProp(v)     => overflow = toOverflow(v)
                case Style.Prop.HiddenProp          => hidden = true
                case Style.Prop.PositionProp(v) =>
                    if v == Position.overlay then overlay = true
                case Style.Prop.HoverProp(s)        => hoverStyle = Present(s)
                case Style.Prop.FocusProp(s)        => focusStyle = Present(s)
                case Style.Prop.ActiveProp(s)       => activeStyle = Present(s)
                case Style.Prop.DisabledProp(s)     => disabledStyle = Present(s)
                case Style.Prop.Bold(_)             => bold = true
                case Style.Prop.Italic(_)           => italic = true
                case Style.Prop.Underline(_)        => underline = true
                case Style.Prop.Strikethrough(_)    => strikethrough = true
                case Style.Prop.Dim(_)              => dim = true
                case Style.Prop.TextAlignProp(v)    => textAlign = toTextAlign(v)
                case Style.Prop.TextTransformProp(v) => textTransform = toTextTransform(v)
                case Style.Prop.TextWrapProp(v)     => textWrap = toTextWrap(v)
                case Style.Prop.TextOverflowProp(v) => textOverflow = v == TextOverflow.ellipsis
                case Style.Prop.FlexGrow(v)         => flexGrow = v
                case Style.Prop.FlexShrink(v)       => flexShrink = v
                case Style.Prop.ShadowProp(c, x, y, s) =>
                    shadowColor = TuiColor.resolve(c)
                    shadowX = x; shadowY = y; shadowSpread = s
                case Style.Prop.RoundedProp(tl, tr, br, bl) =>
                    roundTL = tl; roundTR = tr; roundBR = br; roundBL = bl
                case _ => () // filter props, etc.
        }

    def cellStyle: CellStyle =
        CellStyle(fg, bg, bold, italic, underline, strikethrough, dim)

    def borderThickness: (Int, Int, Int, Int) =
        (if borderT then 1 else 0, if borderR then 1 else 0,
         if borderB then 1 else 0, if borderL then 1 else 0)
```

### RenderCtx

Mutable context passed through the tree walk. Holds pre-allocated ResolvedStyle + layout scratch.

```scala
class RenderCtx(
    val canvas: Canvas,
    val focus: FocusRing,
    val theme: ResolvedTheme,
    val signals: SignalCollector,
    val overlays: OverlayCollector
):
    // Inherited style (mutable — caller saves/restores)
    var fg: Int = TuiColor.Absent
    var bg: Int = TuiColor.Absent
    var bold, italic, underline, strikethrough, dim: Boolean = false
    var opacity: Double = 1.0

    // Interaction targets — Maybe, not null
    var hoverTarget: Maybe[UI.Element] = Absent
    var activeTarget: Maybe[UI.Element] = Absent

    // Pre-allocated per-element style resolution (one instance, reused)
    val rs: ResolvedStyle = new ResolvedStyle

    // Layout scratch arrays (owned here, not global)
    var layoutIntBuf: Array[Int] = new Array[Int](512)
    var layoutDblBuf: Array[Double] = new Array[Double](128)

    def beginFrame(): Unit =
        signals.reset()
        overlays.reset()
        fg = TuiColor.Absent; bg = TuiColor.Absent
        bold = false; italic = false; underline = false
        strikethrough = false; dim = false; opacity = 1.0
        hoverTarget = Absent; activeTarget = Absent

    def saveInherited(): (Int, Int, Boolean, Boolean, Boolean, Boolean, Boolean) =
        (fg, bg, bold, italic, underline, strikethrough, dim)

    def restoreInherited(saved: (Int, Int, Boolean, Boolean, Boolean, Boolean, Boolean)): Unit =
        fg = saved._1; bg = saved._2; bold = saved._3; italic = saved._4
        underline = saved._5; strikethrough = saved._6; dim = saved._7
```

### FlexLayout

Pure layout algorithm. Scratch arrays on RenderCtx, not global state.

```scala
object FlexLayout:

    private def ensureCapacity(ctx: RenderCtx, n: Int): Unit =
        if ctx.layoutIntBuf.length < n * 4 then
            ctx.layoutIntBuf = new Array[Int](n * 4)
        if ctx.layoutDblBuf.length < n * 2 then
            ctx.layoutDblBuf = new Array[Double](n * 2)

    def measureW(ui: UI, availW: Int, availH: Int, ctx: RenderCtx): Int = ...
    def measureH(ui: UI, availW: Int, availH: Int, ctx: RenderCtx): Int = ...

    // CPS layout — calls emit for each child position. Zero alloc.
    inline def arrange(
        children: Span[UI],
        cx: Int, cy: Int, cw: Int, ch: Int,
        rs: ResolvedStyle, ctx: RenderCtx
    )(inline emit: (Int, Int, Int, Int, Int) => Unit): Unit =
        // Reads direction, align, justify, gap from rs
        // Uses ctx.layoutIntBuf/layoutDblBuf as scratch
        ensureCapacity(ctx, children.size)
        ...
```

### FocusRing

Focus management. Parallel arrays instead of IdentityHashMap — no Integer boxing.

```scala
class FocusRing:
    private val maxFocusables = 64

    // Current frame
    private val elems = new Array[UI.Element](maxFocusables)
    private val cursorPosArr = new Array[Int](maxFocusables)
    private val scrollXArr = new Array[Int](maxFocusables)
    private val scrollYArr = new Array[Int](maxFocusables)
    private var count: Int = 0
    private var focusIdx: Int = -1

    // Previous frame (for state migration during scan)
    private val prevElems = new Array[UI.Element](maxFocusables)
    private val prevCursorPos = new Array[Int](maxFocusables)
    private val prevScrollX = new Array[Int](maxFocusables)
    private val prevScrollY = new Array[Int](maxFocusables)
    private var prevCount: Int = 0

    // Cursor screen position (set during render, read after)
    var cursorScreenX: Int = -1
    var cursorScreenY: Int = -1

    def scan(ui: UI): Unit =
        // 1. Copy current → prev
        System.arraycopy(elems, 0, prevElems, 0, count)
        System.arraycopy(cursorPosArr, 0, prevCursorPos, 0, count)
        System.arraycopy(scrollXArr, 0, prevScrollX, 0, count)
        System.arraycopy(scrollYArr, 0, prevScrollY, 0, count)
        val prevFocused = focused
        prevCount = count
        count = 0
        // 2. Walk tree, collect focusables
        scanTree(ui)
        // 3. Migrate state from prev
        migrateState()
        // 4. Restore focus index
        focusIdx = prevFocused.fold(-1)(findIndex)

    private def scanTree(ui: UI): Unit =
        ui match
            case elem: UI.Focusable =>
                if count < maxFocusables then
                    elems(count) = elem
                    cursorPosArr(count) = 0
                    scrollXArr(count) = 0
                    scrollYArr(count) = 0
                    count += 1
                elem.children.foreach(scanTree)
            case elem: UI.Element =>
                elem.children.foreach(scanTree)
            case UI.Fragment(children) =>
                children.foreach(scanTree)
            case UI.Reactive(signal) =>
                scanTree(signal.currentUnsafe)
            case _ => ()

    private def migrateState(): Unit =
        @tailrec def outer(i: Int): Unit =
            if i < count then
                @tailrec def inner(j: Int): Unit =
                    if j < prevCount then
                        if prevElems(j) eq elems(i) then
                            cursorPosArr(i) = prevCursorPos(j)
                            scrollXArr(i) = prevScrollX(j)
                            scrollYArr(i) = prevScrollY(j)
                        else inner(j + 1)
                inner(0)
                outer(i + 1)
        outer(0)

    private def findIndex(elem: UI.Element): Int =
        @tailrec def loop(i: Int): Int =
            if i >= count then -1
            else if elems(i) eq elem then i
            else loop(i + 1)
        loop(0)

    def focused: Maybe[UI.Element] =
        Maybe.when(focusIdx >= 0 && focusIdx < count)(elems(focusIdx))

    def isFocused(elem: UI.Element): Boolean =
        focused match
            case Present(f) => f eq elem
            case _          => false

    def next(): Unit =
        if count > 0 then focusIdx = (focusIdx + 1) % count

    def prev(): Unit =
        if count > 0 then focusIdx = if focusIdx <= 0 then count - 1 else focusIdx - 1

    def focusOn(elem: UI.Element): Unit =
        val idx = findIndex(elem)
        if idx >= 0 then focusIdx = idx

    // Per-element state accessors
    def cursorPos(elem: UI.Element): Int =
        val i = findIndex(elem); if i >= 0 then cursorPosArr(i) else 0
    def setCursorPos(elem: UI.Element, pos: Int): Unit =
        val i = findIndex(elem); if i >= 0 then cursorPosArr(i) = pos
    def scrollX(elem: UI.Element): Int =
        val i = findIndex(elem); if i >= 0 then scrollXArr(i) else 0
    def setScrollX(elem: UI.Element, v: Int): Unit =
        val i = findIndex(elem); if i >= 0 then scrollXArr(i) = v
    def scrollY(elem: UI.Element): Int =
        val i = findIndex(elem); if i >= 0 then scrollYArr(i) else 0
    def setScrollY(elem: UI.Element, v: Int): Unit =
        val i = findIndex(elem); if i >= 0 then scrollYArr(i) = v
```

### OverlayCollector

Deferred overlay rendering. Pre-allocated array, reused.

```scala
class OverlayCollector:
    private var elems = new Array[UI.Element](4)
    private var count = 0

    def add(elem: UI.Element): Unit =
        if count >= elems.length then
            val next = new Array[UI.Element](elems.length * 2)
            System.arraycopy(elems, 0, next, 0, count)
            elems = next
        elems(count) = elem
        count += 1

    def paintAll(w: Int, h: Int, ctx: RenderCtx): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < count then
                Render.renderElement(elems(i), 0, 0, w, h, ctx)
                loop(i + 1)
        loop(0)

    def reset(): Unit = count = 0
```

### SignalCollector

Collects Signal references during tree walk. Returns Span view — no copy.

```scala
class SignalCollector(initialCap: Int):
    private var signals = new Array[Signal[Any]](initialCap)
    private var count = 0

    def add(sig: Signal[?]): Unit =
        if count >= signals.length then
            val next = new Array[Signal[Any]](signals.length * 2)
            System.arraycopy(signals, 0, next, 0, count)
            signals = next
        signals(count) = sig.asInstanceOf[Signal[Any]]
        count += 1

    // Zero-alloc view over internal array
    def span: Span[Signal[Any]] = Span.fromUnsafe(signals).take(count)

    def isEmpty: Boolean = count == 0
    def reset(): Unit = count = 0
```

Note: `span` returns a view via `Span.fromUnsafe` (no copy) + `take` (which does copy a slice).
To make `take` zero-alloc, use indexed access in the await loop instead:

```scala
    def size: Int = count
    def apply(i: Int): Signal[Any] = signals(i)
```

## Rendering

### Entry Point

```scala
object Render:

    def render(ui: UI, x: Int, y: Int, w: Int, h: Int, ctx: RenderCtx): Unit =
        if w > 0 && h > 0 then
            ui match
                case elem: UI.Element =>
                    renderElement(elem, x, y, w, h, ctx)

                case UI.Text(value) =>
                    ctx.canvas.drawString(x, y, w, value, 0, ctx.cellStyle)

                case UI.Reactive(signal) =>
                    ctx.signals.add(signal)
                    render(signal.currentUnsafe, x, y, w, h, ctx)

                case fe: UI.Foreach[?] =>
                    ctx.signals.add(fe.signal)
                    renderForeach(fe, x, y, w, h, ctx)

                case UI.Fragment(children) =>
                    Container.renderColumn(children, x, y, w, h, ctx)
```

Note: `ctx.cellStyle` is a convenience that builds `CellStyle(ctx.fg, ctx.bg, ctx.bold, ...)` — zero alloc since CellStyle is an opaque Long.

### Element Rendering

Resolves style via pre-allocated `ResolvedStyle`, paints common box, dispatches to widget. No `return`, no 30 local vars.

```scala
    def renderElement(
        elem: UI.Element, x: Int, y: Int, w: Int, h: Int, ctx: RenderCtx
    ): Unit =
        val rs = ctx.rs
        rs.inherit(ctx)

        // Apply theme defaults → user style → pseudo-state overlays
        resolveAllStyles(elem, rs, ctx)

        if rs.hidden then ()
        else if rs.overlay then ctx.overlays.add(elem)
        else paintElement(elem, x, y, w, h, rs, ctx)

    private def resolveAllStyles(elem: UI.Element, rs: ResolvedStyle, ctx: RenderCtx): Unit =
        // Theme defaults
        rs.applyProps(ctx.theme.styleFor(elem))
        // User style
        val style = resolveStyleValue(elem.attrs.uiStyle, ctx)
        rs.applyProps(style)
        // Pseudo-state overlays
        val isDisabled = resolveDisabled(elem)
        if isDisabled then
            rs.disabledStyle.foreach(rs.applyProps)
        else
            val isFocused = ctx.focus.isFocused(elem)
            val isHovered = ctx.hoverTarget.exists(_ eq elem)
            val isActive = ctx.activeTarget.exists(_ eq elem)
            if isHovered then rs.hoverStyle.foreach(rs.applyProps)
            if isFocused then rs.focusStyle.foreach(rs.applyProps)
            if isActive then rs.activeStyle.foreach(rs.applyProps)

    private def paintElement(
        elem: UI.Element, x: Int, y: Int, w: Int, h: Int,
        rs: ResolvedStyle, ctx: RenderCtx
    ): Unit =
        // Shadow
        if rs.shadowColor != TuiColor.Absent then
            ctx.canvas.screen.fillBg(
                x + rs.shadowX - rs.shadowSpread, y + rs.shadowY - rs.shadowSpread,
                w + rs.shadowSpread * 2, h + rs.shadowSpread * 2, rs.shadowColor)

        // Background
        if rs.bg != TuiColor.Absent then
            ctx.canvas.screen.fillBg(x, y, w, h, rs.bg)

        // Border
        val (bt, br, bb, bl) = rs.borderThickness
        if (bt | br | bb | bl) != 0 && rs.borderStyle != 0 then
            ctx.canvas.border(x, y, w, h, rs)

        // Content rect
        val cx = x + bl + rs.padL
        val cy = y + bt + rs.padT
        val cw = w - bl - br - rs.padL - rs.padR
        val ch = h - bt - bb - rs.padT - rs.padB

        if cw > 0 && ch > 0 then
            // Save + update inherited style
            val saved = ctx.saveInherited()
            ctx.fg = rs.fg; ctx.bg = rs.bg
            ctx.bold = rs.bold; ctx.italic = rs.italic
            ctx.underline = rs.underline
            ctx.strikethrough = rs.strikethrough; ctx.dim = rs.dim

            // Dispatch to widget
            WidgetDispatch.render(elem, cx, cy, cw, ch, rs, ctx)

            // Restore inherited style
            ctx.restoreInherited(saved)

        // Post-process filters
        if rs.filterBits != 0 then
            ctx.canvas.applyFilters(x, y, w, h, rs.filterBits, rs.filterVals)
```

### WidgetDispatch

Single match point for both render and event handling. Adding a widget = adding cases here + one widget file.

```scala
object WidgetDispatch:

    def render(
        elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int,
        rs: ResolvedStyle, ctx: RenderCtx
    ): Unit =
        elem match
            case _: UI.Div | _: UI.Section | _: UI.Main | _: UI.Header |
                 _: UI.Footer | _: UI.Nav | _: UI.Form | _: UI.Li |
                 _: UI.Span | _: UI.P | _: UI.Pre | _: UI.Code |
                 _: UI.H1 | _: UI.H2 | _: UI.H3 | _: UI.H4 | _: UI.H5 | _: UI.H6 |
                 _: UI.Ul | _: UI.Ol | _: UI.Label | _: UI.Anchor | _: UI.Tr =>
                Container.render(elem, cx, cy, cw, ch, rs, ctx)

            case t: UI.Table =>
                TableW.render(t, cx, cy, cw, ch, rs, ctx)

            case ti: UI.TextInput =>
                TextInputW.render(ti, elem, cx, cy, cw, ch, rs, ctx)

            case bi: UI.BooleanInput =>
                CheckboxW.render(bi, cx, cy, cw, ch, rs, ctx)

            case pi: UI.PickerInput =>
                PickerW.render(pi, elem, cx, cy, cw, ch, rs, ctx)

            case ri: UI.RangeInput =>
                RangeW.render(ri, cx, cy, cw, ch, rs, ctx)

            case _: UI.Hr =>
                HrW.render(cx, cy, cw, rs, ctx.canvas)

            case _: UI.Br => ()

            case img: UI.Img =>
                ImgW.render(img, cx, cy, cw, ch, rs, ctx.canvas)

            case _ =>
                Container.render(elem, cx, cy, cw, ch, rs, ctx)

    def handleKey(
        elem: UI.Element, event: UI.KeyEvent, ctx: RenderCtx
    ): Boolean =
        elem match
            case ti: UI.TextInput    => TextInputW.handleKey(ti, elem, event, ctx)
            case bi: UI.BooleanInput => CheckboxW.handleKey(bi, event, ctx)
            case ri: UI.RangeInput   => RangeW.handleKey(ri, event, ctx)
            case pi: UI.PickerInput  => PickerW.handleKey(pi, elem, event, ctx)
            case _ =>
                (event.key == UI.Keyboard.Enter || event.key == UI.Keyboard.Space) &&
                    elem.attrs.onClick.fold(false) { a => ctx.runHandler(a); true }

    def handlePaste(
        elem: UI.Element, paste: String, ctx: RenderCtx
    ): Boolean =
        elem match
            case ti: UI.TextInput => TextInputW.handlePaste(ti, elem, paste, ctx)
            case _                => false
```

## Widgets

### Container

Handles all elements with children. Runs flex layout via CPS.

```scala
object Container:

    def render(
        elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int,
        rs: ResolvedStyle, ctx: RenderCtx
    ): Unit =
        val children = elem.children
        if children.nonEmpty then
            val clipped = rs.overflow == 1 || rs.overflow == 2
            if clipped then ctx.canvas.screen.pushClip(cx, cy, cx + cw, cy + ch)

            FlexLayout.arrange(children, cx, cy, cw, ch, rs, ctx) { (i, rx, ry, rw, rh) =>
                Render.render(children(i), rx, ry, rw, rh, ctx)
            }

            if clipped then ctx.canvas.screen.popClip()
            if rs.overflow == 2 then ScrollW.paintIndicator(elem, cx, cy, cw, ch, ctx)

    def renderColumn(children: Span[UI], x: Int, y: Int, w: Int, h: Int, ctx: RenderCtx): Unit =
        @tailrec def loop(i: Int, curY: Int): Unit =
            if i < children.size && curY < y + h then
                val childH = FlexLayout.measureH(children(i), w, h - (curY - y), ctx)
                Render.render(children(i), x, curY, w, childH, ctx)
                loop(i + 1, curY + childH)
        loop(0, y)
```

### TextInputW

Handles Input, Password, Email, Tel, Url, Search, Number, Textarea.

```scala
object TextInputW:

    def render(
        ti: UI.TextInput, elem: UI.Element,
        cx: Int, cy: Int, cw: Int, ch: Int,
        rs: ResolvedStyle, ctx: RenderCtx
    ): Unit =
        val rawText = resolveValue(ti.value, ctx.signals)
        val isFocused = ctx.focus.isFocused(elem)
        val cursor = if isFocused then ctx.focus.cursorPos(elem) else -1

        elem match
            case _: UI.Textarea =>
                renderTextarea(rawText, cursor, cx, cy, cw, ch, rs, elem, ctx)
            case _: UI.Password =>
                renderSingleLine(rawText, cursor, cx, cy, cw, rs, elem, ctx, mask = true)
            case _ =>
                renderSingleLine(rawText, cursor, cx, cy, cw, rs, elem, ctx, mask = false)

    private def renderSingleLine(
        text: String, cursor: Int, cx: Int, cy: Int, cw: Int,
        rs: ResolvedStyle, elem: UI.Element, ctx: RenderCtx, mask: Boolean
    ): Unit =
        val scrollX = ctx.focus.scrollX(elem)
        val newScroll = adjustHorizontalScroll(text, cursor, scrollX, cw)
        ctx.focus.setScrollX(elem, newScroll)
        val style = rs.cellStyle
        val visEnd = math.min(text.length, newScroll + cw)
        @tailrec def drawChars(i: Int): Unit =
            if i < visEnd then
                val ch = if mask then '*'
                         else TextMetrics.applyTransform(text.charAt(i), rs.textTransform)
                ctx.canvas.screen.set(cx + i - newScroll, cy, ch, style)
                drawChars(i + 1)
        drawChars(newScroll)
        if cursor >= 0 then
            val screenX = cx + cursor - newScroll
            if screenX >= cx && screenX < cx + cw then
                ctx.focus.cursorScreenX = screenX
                ctx.focus.cursorScreenY = cy

    private def renderTextarea(
        text: String, cursor: Int,
        cx: Int, cy: Int, cw: Int, ch: Int,
        rs: ResolvedStyle, elem: UI.Element, ctx: RenderCtx
    ): Unit =
        val scrollY = ctx.focus.scrollY(elem)
        // Adjust scroll to keep cursor visible
        // Draw wrapped text lines starting from scrollY offset
        // Set cursor screen position
        ...

    def handleKey(
        ti: UI.TextInput, elem: UI.Element,
        event: UI.KeyEvent, ctx: RenderCtx
    ): Boolean =
        val text = resolveValue(ti.value, ctx.signals)
        val pos = ctx.focus.cursorPos(elem)
        event.key match
            case UI.Keyboard.Backspace if pos > 0 =>
                setValue(ti.value, text.substring(0, pos - 1) + text.substring(pos))
                ctx.focus.setCursorPos(elem, pos - 1)
                fireOnInput(ti, ctx); true
            case UI.Keyboard.Delete if pos < text.length =>
                setValue(ti.value, text.substring(0, pos) + text.substring(pos + 1))
                fireOnInput(ti, ctx); true
            case UI.Keyboard.Char(c) =>
                setValue(ti.value, text.substring(0, pos) + c + text.substring(pos))
                ctx.focus.setCursorPos(elem, pos + 1)
                fireOnInput(ti, ctx); true
            case UI.Keyboard.ArrowLeft if pos > 0 =>
                ctx.focus.setCursorPos(elem, pos - 1); true
            case UI.Keyboard.ArrowRight if pos < text.length =>
                ctx.focus.setCursorPos(elem, pos + 1); true
            case UI.Keyboard.Home =>
                ctx.focus.setCursorPos(elem, lineStart(text, pos, elem)); true
            case UI.Keyboard.End =>
                ctx.focus.setCursorPos(elem, lineEnd(text, pos, elem)); true
            case UI.Keyboard.Enter =>
                elem match
                    case _: UI.Textarea =>
                        setValue(ti.value, text.substring(0, pos) + "\n" + text.substring(pos))
                        ctx.focus.setCursorPos(elem, pos + 1); true
                    case _ => false
            case _ => false

    def handlePaste(
        ti: UI.TextInput, elem: UI.Element,
        paste: String, ctx: RenderCtx
    ): Boolean =
        val text = resolveValue(ti.value, ctx.signals)
        val pos = ctx.focus.cursorPos(elem)
        setValue(ti.value, text.substring(0, pos) + paste + text.substring(pos))
        ctx.focus.setCursorPos(elem, pos + paste.length)
        fireOnInput(ti, ctx); true
```

Note: `String.substring` allocations in `handleKey`/`handlePaste` are acceptable — they happen per-keystroke (event-driven), not per-frame.

### CheckboxW

```scala
object CheckboxW:

    private val checkboxChecked = "[x]"
    private val checkboxUnchecked = "[ ]"
    private val radioChecked = "(*)"
    private val radioUnchecked = "( )"

    def render(
        bi: UI.BooleanInput, cx: Int, cy: Int, cw: Int, ch: Int,
        rs: ResolvedStyle, ctx: RenderCtx
    ): Unit =
        val checked = resolveChecked(bi.checked, ctx.signals)
        val mark = bi match
            case _: UI.Checkbox => if checked then checkboxChecked else checkboxUnchecked
            case _: UI.Radio    => if checked then radioChecked else radioUnchecked
        ctx.canvas.drawString(cx, cy, cw, mark, 0, rs.cellStyle)

    def handleKey(bi: UI.BooleanInput, event: UI.KeyEvent, ctx: RenderCtx): Boolean =
        event.key match
            case UI.Keyboard.Space | UI.Keyboard.Enter =>
                val v = !resolveChecked(bi.checked, ctx.signals)
                setChecked(bi.checked, v)
                bi.onChange.foreach(f => ctx.runHandler(f(v)))
                true
            case _ => false
```

### RangeW

```scala
object RangeW:

    def render(
        ri: UI.RangeInput, cx: Int, cy: Int, cw: Int, ch: Int,
        rs: ResolvedStyle, ctx: RenderCtx
    ): Unit =
        val value = resolveDouble(ri.value, ctx.signals)
        val min = ri.min.getOrElse(0.0)
        val max = ri.max.getOrElse(100.0)
        val pct = if max > min then (value - min) / (max - min) else 0.0
        val filled = math.min(cw, (pct * cw).toInt)
        ctx.canvas.screen.fillBg(cx, cy, filled, 1, rs.fg)
        ctx.canvas.screen.fillBg(cx + filled, cy, cw - filled, 1, rs.bg)

    def handleKey(ri: UI.RangeInput, event: UI.KeyEvent, ctx: RenderCtx): Boolean =
        val value = resolveDouble(ri.value, ctx.signals)
        val step = ri.step.getOrElse(1.0)
        val min = ri.min.getOrElse(0.0)
        val max = ri.max.getOrElse(100.0)
        event.key match
            case UI.Keyboard.ArrowRight | UI.Keyboard.ArrowUp =>
                val v = math.min(max, value + step)
                setDouble(ri.value, v)
                ri.onChange.foreach(f => ctx.runHandler(f(v))); true
            case UI.Keyboard.ArrowLeft | UI.Keyboard.ArrowDown =>
                val v = math.max(min, value - step)
                setDouble(ri.value, v)
                ri.onChange.foreach(f => ctx.runHandler(f(v))); true
            case _ => false
```

### PickerW

```scala
object PickerW:

    def render(
        pi: UI.PickerInput, elem: UI.Element,
        cx: Int, cy: Int, cw: Int, ch: Int,
        rs: ResolvedStyle, ctx: RenderCtx
    ): Unit =
        val value = resolveValue(pi.value, ctx.signals)
        val style = rs.cellStyle
        elem match
            case sel: UI.Select =>
                val displayText = findSelectedOptionText(sel, value)
                ctx.canvas.drawString(cx, cy, cw - 2, displayText, 0, style)
                ctx.canvas.drawChar(cx + cw - 1, cy, '▼', style)
            case _ =>
                ctx.canvas.drawString(cx, cy, cw, value, 0, style)

    def handleKey(
        pi: UI.PickerInput, elem: UI.Element,
        event: UI.KeyEvent, ctx: RenderCtx
    ): Boolean =
        elem match
            case sel: UI.Select =>
                event.key match
                    case UI.Keyboard.ArrowUp   => selectPrev(sel, ctx); true
                    case UI.Keyboard.ArrowDown => selectNext(sel, ctx); true
                    case _ => false
            case _ => false
```

### TableW

```scala
object TableW:

    def render(
        table: UI.Table, cx: Int, cy: Int, cw: Int, ch: Int,
        rs: ResolvedStyle, ctx: RenderCtx
    ): Unit =
        if table.children.nonEmpty then
            // 1. Count columns, resolve colspans
            // 2. Compute column widths (proportional, respecting colspan)
            // 3. For each row, for each cell, render into computed rect
            ...
```

### HrW, ImgW, ScrollW

```scala
object HrW:
    def render(cx: Int, cy: Int, cw: Int, rs: ResolvedStyle, canvas: Canvas): Unit =
        canvas.hline(cx, cy, cw, '─', rs.cellStyle)

object ImgW:
    def render(img: UI.Img, cx: Int, cy: Int, cw: Int, ch: Int,
               rs: ResolvedStyle, canvas: Canvas): Unit =
        img.alt.foreach { alt =>
            val style = CellStyle(rs.fg, rs.bg, false, true, false, false, false)
            canvas.drawString(cx, cy, cw, alt, 0, style)
        }

object ScrollW:
    def paintIndicator(
        elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, ctx: RenderCtx
    ): Unit =
        // Compute total content height from children measurements
        // Draw ▲/▼ arrows + thumb on right edge
        ...
```

## Event Dispatch

```scala
object EventDispatch:

    def dispatch(ui: UI, event: InputEvent, w: Int, h: Int, ctx: RenderCtx): Unit =
        event match
            case InputEvent.Key(ke) =>
                if ke.key == UI.Keyboard.Tab then
                    if ke.shift then ctx.focus.prev() else ctx.focus.next()
                else
                    ctx.focus.focused.foreach { elem =>
                        // Fire onKeyDown
                        elem.attrs.onKeyDown.foreach(f => runHandler(f(toKeyEvent(ke)), ctx))
                        // Built-in widget handling
                        WidgetDispatch.handleKey(elem, ke, ctx)
                        // Fire onKeyUp
                        elem.attrs.onKeyUp.foreach(f => runHandler(f(toKeyEvent(ke)), ctx))
                    }

            case InputEvent.Mouse(me) =>
                me match
                    case LeftPress(mx, my) =>
                        hitTest(ui, 0, 0, w, h, mx, my, ctx).foreach { hit =>
                            ctx.activeTarget = Present(hit)
                            bubbleClick(hit, ui, ctx)
                            hit match
                                case _: UI.Focusable => ctx.focus.focusOn(hit)
                                case _ => ()
                        }
                    case Move(mx, my) =>
                        ctx.hoverTarget = hitTest(ui, 0, 0, w, h, mx, my, ctx)
                    case Release(_, _) =>
                        ctx.activeTarget = Absent
                    case ScrollUp(mx, my) =>
                        findScrollable(ui, 0, 0, w, h, mx, my, ctx)
                            .foreach(target => adjustScroll(target, me, ctx))
                    case ScrollDown(mx, my) =>
                        findScrollable(ui, 0, 0, w, h, mx, my, ctx)
                            .foreach(target => adjustScroll(target, me, ctx))

            case InputEvent.Paste(text) =>
                ctx.focus.focused.foreach { elem =>
                    WidgetDispatch.handlePaste(elem, text, ctx)
                }

    // Hit test: walk tree with same layout as render
    private def hitTest(
        ui: UI, x: Int, y: Int, w: Int, h: Int,
        mx: Int, my: Int, ctx: RenderCtx
    ): Maybe[UI.Element] =
        // Same structure as Render.render but returns deepest element containing (mx, my)
        // For containers: check children in reverse order (topmost first)
        // Recomputes layout inline (FlexLayout.arrange)
        ...
```

## Main Loop

```scala
object TuiBackend extends UIBackend:

    def render(ui: UI, theme: Theme)(using Frame): UISession < (Async & Scope) =
        val terminal = TuiTerminal.init()
        val screen = new Screen(terminal.cols, terminal.rows)
        val canvas = new Canvas(screen)
        val focus = new FocusRing()
        val signals = new SignalCollector(256)
        val overlays = new OverlayCollector()
        val resolvedTheme = ResolvedTheme.from(theme)
        val ctx = new RenderCtx(canvas, focus, resolvedTheme, signals, overlays)
        val rendered = Signal.initRef[UI](UI.empty)

        for
            inputFiber <- Fiber.init(inputLoop(terminal, ui, ctx))
            renderFiber <- Fiber.init(renderLoop(terminal, ui, screen, ctx, rendered))
        yield UISession(renderFiber, rendered)

    private def renderLoop(
        terminal: TuiTerminal, ui: UI,
        screen: Screen, ctx: RenderCtx, rendered: SignalRef[UI]
    )(using Frame): Nothing < Async =
        Loop.forever {
            val w = terminal.cols; val h = terminal.rows
            screen.resize(w, h)

            // Begin frame
            ctx.beginFrame()
            ctx.focus.scan(ui)
            screen.clear()

            // Render
            Render.render(ui, 0, 0, w, h, ctx)
            ctx.overlays.paintAll(w, h, ctx)

            // End frame
            screen.flush(terminal.output, detectColorTier())
            if ctx.focus.cursorScreenX >= 0 then
                terminal.showCursor(ctx.focus.cursorScreenX, ctx.focus.cursorScreenY)
            else terminal.hideCursor()
            ctx.focus.cursorScreenX = -1; ctx.focus.cursorScreenY = -1
            rendered.set(ui)

            // Await next change (zero-alloc signal iteration)
            if ctx.signals.isEmpty then Async.sleep(100.millis)
            else
                @tailrec def buildRace(i: Int, acc: Unit < Async): Unit < Async =
                    if i >= ctx.signals.size then acc
                    else buildRace(i + 1, Async.race(acc, ctx.signals(i).next.unit))
                buildRace(1, ctx.signals(0).next.unit)
            Async.sleep(16.millis)
        }

    private def inputLoop(
        terminal: TuiTerminal, ui: UI, ctx: RenderCtx
    )(using Frame): Nothing < Async =
        // Read terminal bytes, parse into InputEvent, call EventDispatch.dispatch
        ...
```

Note: `Duration` in kyo is `opaque type Duration = Long` — `100.millis` and `16.millis` are zero-alloc.

## File Structure

```
kyo-ui/jvm/src/main/scala/kyo/
  TuiBackend.scala                       ~120 lines
  internal/tui/
    CellStyle.scala                      ~50 lines     opaque packed cell style
    Screen.scala                         ~300 lines    cell storage, diff flush, clip stack
    Canvas.scala                         ~80 lines     drawString, hline, border, filters
    ResolvedStyle.scala                  ~120 lines    pre-allocated style struct + applyProps
    RenderCtx.scala                      ~50 lines     inherited style + shared state + scratch
    FlexLayout.scala                     ~300 lines    measure + arrange (scratch from ctx)
    FocusRing.scala                      ~180 lines    parallel arrays, state migration
    EventDispatch.scala                  ~180 lines    event routing, hit testing, bubbling
    TextMetrics.scala                    ~160 lines    text width, height, wrapping, transform
    ColorUtils.scala                     ~250 lines    RGB packing, quantization, filters
    AnsiBuffer.scala                     ~80 lines     ANSI escape sequence builder
    Terminal.scala                       ~180 lines    TTY control (stty/termios)
    InputParser.scala                    ~330 lines    ANSI input byte parsing
    SignalCollector.scala                ~35 lines     signal collection for await
    OverlayCollector.scala               ~30 lines     deferred overlay rendering
    ResolvedTheme.scala                  ~60 lines     theme pre-resolution
    widget/
      Render.scala                       ~120 lines    entry point, renderElement
      WidgetDispatch.scala               ~80 lines     single match for render + handleKey
      Container.scala                    ~60 lines     flex children layout
      TableW.scala                       ~120 lines    colspan/rowspan layout
      TextInputW.scala                   ~220 lines    input/password/textarea + key handling
      CheckboxW.scala                    ~30 lines     checkbox/radio + key handling
      PickerW.scala                      ~80 lines     select/date/time/color + key handling
      RangeW.scala                       ~40 lines     range slider + key handling
      ScrollW.scala                      ~50 lines     scroll indicators
      HrW.scala                          ~5 lines      horizontal rule
      ImgW.scala                         ~10 lines     alt text fallback

Total: ~2,800 lines (vs current 4,700)
```

## Allocation per frame: zero

| What | How |
|------|-----|
| Cell data | Screen arrays, pre-allocated |
| Drawing | Canvas methods write to Screen, no intermediates |
| Inherited style | RenderCtx mutable fields, save/restore tuple on stack |
| Style resolution | ResolvedStyle pre-allocated on RenderCtx, reset per element |
| Layout scratch | Arrays on RenderCtx (not global state) |
| Focus state | Parallel arrays (no IdentityHashMap, no Integer boxing) |
| Overlays | OverlayCollector reused array |
| Signals | SignalCollector reused array, indexed access |
| Filter values | Pre-allocated 8-element array on ResolvedStyle |
| CellStyle | Opaque Long, no object |
| Duration | Opaque Long, `100.millis` is zero-alloc |
| Checkbox marks | String constants, not created per frame |

### Event-driven allocations (per keystroke, not per frame)

| What | Why acceptable |
|------|---------------|
| `String.substring` + concat in TextInputW.handleKey | Unavoidable for immutable String editing; happens once per keystroke |
| `String.substring` + concat in handlePaste | Same; once per paste event |

## Adding a new element type

1. Add case class to UI.scala (shared)
2. Add one file in widget/ with render + handleKey
3. Add case in WidgetDispatch.render
4. Add case in WidgetDispatch.handleKey (if interactive)

Both dispatch matches are in the same file — impossible to update one without seeing the other.

## Unsafe constructs audit

| Construct | Status |
|-----------|--------|
| `null` | Eliminated — `Maybe[UI.Element]` everywhere |
| `while` | Eliminated — `@tailrec def loop` everywhere |
| `return` | Eliminated — guard nesting + method decomposition |
| `throw` | Not used |
| `asInstanceOf` | Not used — pattern matching only |
| `isInstanceOf` | Not used — pattern matching only |
| `Text` (kyo-data) | Not used — `String` directly |
| Raw `Long` packing | Eliminated — `opaque type CellStyle` |
| `IdentityHashMap` | Eliminated — parallel arrays in FocusRing |
| Global mutable state | Eliminated — scratch arrays on RenderCtx |
