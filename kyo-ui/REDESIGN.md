# kyo-ui Redesign

## Current Architecture

### Three backends, one shared UI AST

```
shared/    UI.scala (478 lines, 34 element case classes)
           Style.scala (539 lines, 41 Prop types)
           UIBackend.scala (5 lines — abstract render method)
           UISession.scala (9 lines — session lifecycle)
           CssStyleRenderer.scala (150 lines — Style → CSS)
           FxCssStyleRenderer.scala (155 lines — Style → JavaFX CSS)

js/        DomBackend.scala (541 lines — DOM rendering)
           DomStyleSheet.scala (CSS resets, pseudo-states)

jvm/       JavaFxBackend.scala (1,468 lines — native JavaFX)
           TuiBackend.scala (243 lines — orchestrates TUI pipeline)
           TuiSimulator.scala (168 lines — headless testing)
           internal/  TuiFlatten, TuiLayout, TuiPainter, TuiFocus,
                      TuiRenderer, TuiTerminal, TuiInput, TuiStyle,
                      TuiColor, TuiAnsiBuffer, TuiSignalCollector
```

### TUI render pipeline

```
UI AST → TuiFlatten → TuiLayout(measure+arrange) → TuiPainter(inherit+paint) → TuiRenderer(diff+flush) → TuiTerminal
                                                     TuiFocus(scan+dispatch)
```

Each stage:

1. **TuiFlatten**: Walks the UI AST, resolves signals inline, writes into flat arrays. Converts `Element` case classes to nodeType integers. Creates synthetic text children for Input/Textarea.

2. **TuiLayout.measure**: Bottom-up pass (reverse index order). Computes intrinsic width/height for each node from text content or child accumulation. Applies explicit size, min/max clamping.

3. **TuiLayout.arrange**: Top-down pass (forward index order). Root fills terminal. Positions children within parent content area using flex-like layout: direction (row/column), gap, justify, align. Handles padding, margins, borders.

4. **TuiPainter.inheritStyles**: Forward pass O(n). Propagates fg/bg colors and opacity from parent to child.

5. **TuiFocus.scan**: Linear scan collecting indices of focusable elements. Preserves focus across re-renders by element identity (`eq`).

6. **TuiFocus.applyFocusStyle**: Overlays focus visual (blue border or custom focus style) onto focused element's layout arrays.

7. **TuiPainter.paint**: Recursive tree walk. For each visible node: fill bg, draw borders, render text, recurse into children. Supports overflow clipping via renderer clip rect.

8. **TuiRenderer.flush**: Diff current vs previous cell buffer. Emit ANSI only for changed cells (cursor move + SGR + character). Swap buffers.

9. **TuiTerminal**: Raw `/dev/tty` I/O. Alternate screen, raw mode (`stty`), mouse tracking (`?1003h`), SGR mouse encoding (`?1006h`), bracketed paste.

### Flat array storage (TuiLayout)

45 parallel arrays, arena-reset semantics (`count = 0`, slots overwritten before read):

- **Tree structure**: parent, firstChild, nextSibling, lastChild, nodeType
- **Geometry**: x, y, w, h, intrW, intrH
- **Layout style**: lFlags (packed bits: direction, align, justify, overflow, hidden, border sides, disabled), padT/R/B/L, marT/R/B/L, gap, sizeW/H, minW/maxW/minH/maxH, transX/Y
- **Paint style**: pFlags (packed bits: bold, dim, italic, underline, strikethrough, borderStyle, rounded corners, textAlign, textDeco, textTrans, textOverflow, wrapText), fg, bg, bdrClrT/R/B/L, opac, lineH, letSp, fontSz, shadow
- **Content refs**: text (Maybe[String]), focusStyle, activeStyle, element (Maybe[AnyRef] → original AST node)

Zero-allocation hot path: no objects created during measure/arrange/paint. All state lives in pre-allocated arrays.

---

## Problems

### 1. Element behavior is scattered across 8 dispatch points

Adding a new element type requires touching all of these:

| # | File:Line | What |
|---|-----------|------|
| 1 | TuiFlatten:205 | `elementNodeType`: 34-way match, AST class → nodeType int |
| 2 | TuiFlatten:112 | `resolveElement`: `isInlineNode` check → sets direction bit |
| 3 | TuiFlatten:120 | `resolveInputText`: Input/Textarea special child creation |
| 4 | TuiFlatten:175 | `resolveDisabledIfInteractive`: Button/Input/Textarea/Select |
| 5 | TuiLayout:331 | `isInlineNode`: hardcodes Span, Nav, Li, Tr |
| 6 | TuiLayout:335 | `isFocusable`: hardcodes Button, Input, Textarea, Select, Anchor |
| 7 | TuiFocus:218 | `builtInHandle`: keyboard dispatch per element type |
| 8 | TuiFocus:195 | `builtInHandleMouse`: mouse dispatch per element type |

### 2. Event dispatch is type-driven instead of handler-driven

`onClick` lives on `CommonAttrs`, available to ALL elements. But only Button and Anchor fire it — because of hardcoded pattern matches. A `div.onClick(...)` silently does nothing in TUI.

### 3. 34 element case classes that are all structurally identical

Every element case class has the same shape: `(common: CommonAttrs, children: Span[UI])` plus optional specialized fields. 23 types have only `CommonAttrs` + `children` (21 containers + 2 void elements Hr/Br), byte-for-byte identical — same fields, same builder methods, same boilerplate. The remaining 11 "distinct" types (Button, Input, Textarea, Select, Option, Anchor, Form, Label, Td, Th, Img) differ only by a few fields (`disabled`, `value`, `placeholder`, `href`, `target`, etc.) that belong on CommonAttrs.

### 4. Hit testing only sees focusable elements

`TuiFocus.hitTest` scans a separate `indices` array of focusable element indices. Non-focusable elements with onClick handlers are invisible to mouse interaction. There's no hover detection.

### 5. No overlapping/z-order support

Layout is purely sequential flex. No `position: absolute`, no `z-index`, no modal/overlay/dialog support. If siblings overlap (via negative margins or translate), the result is accidental — last-painted-wins with no intentional stacking.

### 6. No text cursor

Terminal cursor is hidden on enter (`\e[?25l`). Input fields have no visible cursor, no cursor position tracking. Text editing is append-only (can't arrow left/right to edit middle of text). No blinking cursor.

### 7. Mouse move events are ignored

Terminal sends MouseMove events (all-motion tracking `?1003h` is already enabled, `TuiInput` parses Move events correctly), but `TuiFocus.dispatch` drops them. No hover effects despite the infrastructure being in place.

### 8. JavaFxBackend is a 1,468-line monolith

Should be replaced with WebView rendering (reusing DomBackend + CssStyleRenderer). Single decision: drop native JavaFX scene graph, embed a web view.

### 9. Three style renderers with silent degradation

CssStyleRenderer, FxCssStyleRenderer, TuiStyle each independently interpret the same Style props. Missing a prop in one renderer = silent visual degradation. No shared validation or exhaustiveness checking.

### 10. TuiRenderer's drawing API is too low-level

The renderer provides only cell-level primitives: `set(x, y, char, style)`, `fillBg(...)`, `setClip(...)`. The painter must manually convert high-level concepts (borders, text blocks) into individual character placements. `paintBorders` (55 lines) manually places corner chars, horizontal lines, vertical lines. `paintText` (60 lines) handles wrapping, clipping, alignment, transforms, then sets characters one by one. The painter is 85% drawing code and 15% tree walking — these are different concerns jammed into one object.

### 11. TuiLayout is a 736-line god object

TuiLayout contains six different responsibilities:
- Data structure (arrays, alloc, reset, grow) — 140 lines
- Tree operations (linkChild) — 15 lines
- Layout algorithm (measure + arrange) — 250 lines
- Text utilities (wrapText, clipText, wrapLineCount) — 120 lines
- Constants (node types, flag bits, accessors) — 130 lines
- Box-drawing characters (borderChars) — 50 lines

### 12. Redundant AST variants

`ReactiveText(signal)` is just `Reactive(signal.map(Text(_)))`. `ForeachIndexed` and `ForeachKeyed` have the same structure — they differ only by an optional key function. Six non-Element AST variants can collapse to four.

### 13. UI AST leaks backend-specific concepts

CommonAttrs contains five fields that are DOM/CSS escape hatches: `styles` (inline CSS strings), `classes`/`dynamicClassName` (CSS class names), `handlers` (DOM event names as strings), `attrs` (HTML attributes as strings). These are meaningless in TUI and break the abstraction that UI is backend-agnostic. Several cross-platform attributes (`href`, `src`, `alt`, `colspan`) are expressed through the stringly-typed `attrs` map instead of as typed fields.

---

## Design Principles

### UI and Style are backend-agnostic

UI and Style contain NOTHING specific to any backend — no HTML element names, no CSS strings, no DOM event names. They are a self-contained UI language that each backend implements fully.

- **UI** = structure + semantics + interaction. What elements exist, what they mean, how they respond to users.
- **Style** = visual presentation + layout. How elements look and where they go.

If a concept is missing, add it as a typed field to UI or Style. Never add escape hatches.

### No escape hatches

The current `styles`, `classes`, `dynamicClassName`, `handlers`, and `attrs` fields are DOM-specific escape hatches. They're removed. The reasoning:

- `styles` (inline CSS strings) → use Style. If Style is missing a prop, add it.
- `classes` / `dynamicClassName` (CSS class names) → use Style for all visual concerns.
- `handlers` (DOM event names as strings) → type the handlers that matter cross-platform.
- `attrs` (HTML attributes as strings) → type the attributes that matter cross-platform.

### Every visual concept maps to every backend

Style describes visual *intent*. Each backend renders it as best it can. A prop isn't "DOM-only" or "TUI-only" — it's a visual concept that maps differently:

- **CursorProp** — DOM: sets CSS `cursor`. TUI: expresses "interactive" intent, already manifested through focus highlighting and hover effects.
- **Filters** (brightness, contrast, grayscale, blur, etc.) — DOM: CSS `filter`. TUI: color math on the cell buffer (post-processing pass after painting). All filters are per-cell color transformations; blur averages neighboring cells' colors.
- **Background gradients** — DOM: CSS `linear-gradient`. TUI: interpolate colors across character cells.
- **Box shadow** — DOM: CSS `box-shadow`. TUI: approximate with dim characters or color blending on adjacent cells.
- **Opacity** — DOM: CSS `opacity`. TUI: blend colors with parent/background.

The only truly non-cross-platform concepts are **pixel-level background images** and **sub-character transforms** (rotate, scale). Everything that's color math or layout works everywhere.

### Zero-allocation render path

The TUI render pipeline (flatten → layout → style → paint → flush) runs every frame. No objects are allocated in this path. All state lives in pre-allocated flat arrays with arena-reset semantics (`count = 0`, slots overwritten before read).

#### Optimization techniques

1. **Flat parallel arrays, not objects.** Element state is 45+ parallel arrays indexed by integer, not per-node objects. Adding a new property means adding a new array, not a new field on a class.
2. **`Maybe` not `Option`.** `Maybe` is a value type — no boxing, no allocation. Used for all optional fields in CommonAttrs and layout arrays.
3. **`Span` not `Seq`/`List`.** `Span` is an opaque type over `Array` — zero-cost wrapper. Its methods (`foreach`, `foldLeft`, `forall`, `exists`, etc.) are all `inline def` with `@tailrec` loops, so the closure is inlined at compile time. **No iterator, no closure, no allocation.** `Style.props` is `Span[Style.Prop]`, so style resolution via `props.foreach { ... }` compiles to a while loop. Element `children` is `Span[UI]`, so tree traversal via `children.foreach { ... }` compiles to a while loop.
4. **Packed bit flags, not booleans.** `lFlags` and `pFlags` pack 20+ boolean properties into two `Int` values per node. `Tag.flags` packs element behavior (focusable, inline, etc.) into a single `Int`.
5. **`inline val` constants.** Flag bit positions and masks are `inline val` — zero-cost at runtime, no boxing.
6. **Primitives for colors.** Colors are packed `Int` (RGB in 24 bits). No color objects in the hot path. HSL conversion is in-place math for filter application.
7. **DrawSurface takes primitives.** `fillRect(x, y, w, h, color: Int)`, `drawBorder(...)`, `drawText(...)` — all parameters are `Int`, `Boolean`, `String`. No wrapper objects.
8. **In-place mutation.** Style resolution, inheritance, and state overlays all write directly to layout arrays. Filter application transforms cell colors in-place.
9. **Pre-allocated buffers.** Cell buffer, ANSI output buffer, and layout arrays are allocated once and grown by doubling. Reset per frame by zeroing the count.
10. **CPS (continuation-passing) for multi-value results.** When a hot-path method would return a tuple or pair, use `inline def` with an `inline` callback instead. The callback receives primitive arguments directly — no tuple object allocated. The compiler inlines the callback body at the call site. Pattern from kyo-http's `RouteUtil.encodeRequest`. Example — text wrapping:

    ```scala
    // Instead of returning (start, end) pairs:
    //   def wrapLines(text: String, maxWidth: Int): Array[(Int, Int)]   // allocates tuples + array
    // Use CPS:
    inline def forEachLine(text: String, maxWidth: Int)(inline f: (Int, Int) => Unit): Int
    //   f receives start/end as bare Ints — no tuple, no closure, no allocation.
    //   Returns line count.
    ```

11. **Separate vals, not tuple destructuring.** `val (x, y, w, h) = (...)` allocates a Tuple4. Use four separate `val` declarations instead.

#### Per-frame allocation analysis

**Unavoidable allocations:**

| What | Where | Why unavoidable |
|------|-------|-----------------|
| Signal resolution | TuiFlatten, per reactive node | kyo core owns Signal.get — returns current value |
| `Chunk.foreach` internal buffer | TuiFlatten, Foreach rendering | Chunk.foreach uses `acquireBuffer` internally. Signal API returns Chunk, not Span. |
| `InputEvent` case classes | TuiInput, per key/mouse event | One allocation per user input event (not per frame — per event). Low frequency. |
| `new String(bytes, "UTF-8")` | TuiInput, per key event | Must produce String from terminal bytes. Low frequency. |
| String concat in text editing | TuiFocus, `cur + key` / `cur.substring` | Must produce new String for SignalRef update. Only on keypress. |

**Zero-cost (previously flagged as allocating):**

| What | Why zero-cost |
|------|---------------|
| `style.props.foreach { ... }` | `Span.foreach` is `inline def` → compiles to while loop. No iterator, no closure object. |
| `children.foreach { ... }` | Same — `Span.foreach` is inline. |
| `Present(elem)` wrapping | `Maybe` is an opaque type. `Present(x)` is just `x` at runtime. |
| `Tag.isInline(tag)` etc. | `inline def` with bit math. No boxing. |

**Avoidable — text wrapping (biggest win):**

The current text wrapping in TuiLayout is the worst allocation offender per frame:

| What | Current | New design (TuiText) |
|------|---------|---------------------|
| `text.split("\n", -1)` | Allocates `Array[String]` | CPS: `forEachLine(text, maxWidth)(inline f: (Int, Int) => Unit)` scans for `\n` and word breaks, calls `f(start, end)` per line. No split, no array, no tuples. |
| `line.substring(pos, brk)` | Allocates new String per wrapped line | CPS callback receives `(start, end)` as bare Ints. DrawSurface writes chars directly from source string[start..end] to cell buffer. |
| `new ArrayList[String]` | Per text node | Eliminated — CPS streams lines to callback, no intermediate storage. |
| `toUpperCase`/`toLowerCase`/`capitalize` | Text transforms allocate new String (+ StringBuilder for capitalize) | Transform char-by-char during cell writes: `applyTransform(ch: Char, transform: Int): Char` inline in the cell write loop. No String, no buffer. |

The key insight: CPS with `inline` callbacks (technique #10) eliminates all intermediate storage. `forEachLine` scans the source string once, calling `f(start, end)` for each wrapped line. The callback is inlined at the call site — the compiler produces a single while loop with the drawing code pasted in. Two consumers: `lineCount(text, maxWidth): Int` (layout measure) and `forEachLine` (renderer draw).

**Avoidable — other:**

| What | Current | Fix |
|------|---------|-----|
| Wide char `HashMap<Int, String>` | Boxing Int keys, HashMap overhead | Flat `Array[String]` indexed by cell position |
| `Seq.tabulate(sigs.size)(...)` | TuiBackend signal race setup | Manual while loop or Span.tabulate |
| `remainder ++ bytes` | Chunk concat for input buffering | Ring buffer or index tracking |

### Conditional styling uses Scala, not selectors

CSS uses media queries and selectors for conditional styling. This framework uses signals and Scala logic — which is strictly more powerful:

```scala
// Responsive layout — instead of CSS media queries
val isWide = terminalWidth.map(_ > 100)
div.style(isWide.map(w => if w then Style.row else Style.column))(sidebar, content)

// Computed styles — instead of CSS variables
val theme = signal.map(if dark then Style.bg("#1a1a1a").color("#eee") else Style.bg("#fff").color("#333"))
div.style(theme)(content)
```

No cascade, no specificity, no selector matching. Styles are applied directly.

---

## Design Decisions

### Drop JavaFX native rendering, use WebView

Replace the 1,468-line JavaFxBackend with a thin WebView wrapper that reuses DomBackend + CssStyleRenderer. This eliminates FxCssStyleRenderer entirely and reduces the style rendering problem from 3 backends to 2 (DOM CSS + TUI flat arrays).

### Keep semantic element types in UI AST

`Box` is a layout concept, not a UI concept. The UI AST should remain semantic — `div`, `nav`, `section` carry meaning (accessibility for DOM, visual conventions for TUI). The "box" is what the backend's layout engine produces *from* those semantic elements, not something the developer declares.

### Collapse ALL element types into Element(tag, ...)

All 34 element case classes have the same fundamental shape: CommonAttrs + children. The "specialized" fields on Button, Input, Textarea, etc. are cross-platform interaction concepts that belong on CommonAttrs.

One element type replaces all 34:

```scala
final case class Element(
    tag: Tag,
    common: CommonAttrs = CommonAttrs(),
    override val children: Span[UI] = Span.empty[UI]
) extends UI:
    def apply(cs: UI*): Element = copy(children = children ++ Span.from(cs))
    // All builder methods (onClick, style, value, disabled, href, etc.) defined once
```

The `Tag` enum carries behavior flags. Backend-specific mappings (Tag → HTML name, Tag → TUI node type) live in each backend, not on Tag:

```scala
enum Tag(val flags: Int = 0):
    // Block containers (column default)
    case Div, P, Section, Main, Header, Footer, Form, Pre, Code,
         Ul, Ol, Table
    // Inline containers (row default)
    case Span extends Tag(Inline)
    case Nav  extends Tag(Inline)
    case Li   extends Tag(Inline)
    case Tr   extends Tag(Inline)
    // Headings (block, bold)
    case H1, H2, H3, H4, H5, H6
    // Table cells
    case Td, Th
    // Interactive
    case Button   extends Tag(Focusable | Inline | HasDisabled | Activatable | Clickable)
    case Input    extends Tag(Focusable | Inline | HasDisabled | TextInput)
    case Textarea extends Tag(Focusable | HasDisabled | TextInput)
    case Select   extends Tag(Focusable | HasDisabled)
    case Anchor   extends Tag(Focusable | Inline | Activatable | Clickable)
    case Option, Label
    // Media
    case Img extends Tag(Inline)
    // Void
    case Hr, Br

object Tag:
    inline val Focusable   = 1 << 0
    inline val Inline      = 1 << 1
    inline val HasDisabled = 1 << 2
    inline val TextInput   = 1 << 3
    inline val Activatable = 1 << 4
    inline val Clickable   = 1 << 5

    inline def isInline(t: Tag): Boolean      = (t.flags & Inline) != 0
    inline def isFocusable(t: Tag): Boolean   = (t.flags & Focusable) != 0
    inline def hasDisabled(t: Tag): Boolean   = (t.flags & HasDisabled) != 0
    inline def isTextInput(t: Tag): Boolean   = (t.flags & TextInput) != 0
    inline def isActivatable(t: Tag): Boolean = (t.flags & Activatable) != 0
    inline def isClickable(t: Tag): Boolean   = (t.flags & Clickable) != 0
```

The tag ordinal IS the nodeType. `Tag.ordinal` maps directly to TuiLayout's node type integers. The DOM backend maps `Tag → String` internally (most are just `tag.toString.toLowerCase`, with `Anchor → "a"` as the exception).

**CommonAttrs — typed, cross-platform, no escape hatches:**

```scala
final private[kyo] case class CommonAttrs(
    // Identity & visibility
    identifier: Maybe[String] = Absent,
    hidden: Maybe[Boolean | Signal[Boolean]] = Absent,
    tabIndex: Maybe[Int] = Absent,
    // Visual
    uiStyle: Style | Signal[Style] = Style.empty,
    // Event handlers
    onClick: Maybe[Unit < Async] = Absent,
    onKeyDown: Maybe[KeyEvent => Unit < Async] = Absent,
    onKeyUp: Maybe[KeyEvent => Unit < Async] = Absent,
    onFocus: Maybe[Unit < Async] = Absent,
    onBlur: Maybe[Unit < Async] = Absent,
    // Form interaction
    value: Maybe[String | SignalRef[String]] = Absent,
    placeholder: Maybe[String] = Absent,
    disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
    checked: Maybe[Boolean | Signal[Boolean]] = Absent,
    selected: Maybe[Boolean | Signal[Boolean]] = Absent,
    onInput: Maybe[String => Unit < Async] = Absent,
    onChange: Maybe[String => Unit < Async] = Absent,
    onSubmit: Maybe[Unit < Async] = Absent,
    // Semantic attributes
    href: Maybe[String | Signal[String]] = Absent,
    target: Maybe[String] = Absent,
    src: Maybe[String] = Absent,
    alt: Maybe[String] = Absent,
    colspan: Maybe[Int] = Absent,
    rowspan: Maybe[Int] = Absent,
    forId: Maybe[String] = Absent,
    inputType: Maybe[String] = Absent,
)
```

Every field is cross-platform:

| Field | DOM | TUI |
|-------|-----|-----|
| `href` | `<a href="...">` | Opens URL in system browser via `xdg-open`/`open` |
| `target` | `<a target="_blank">` | Ignored (TUI always opens in system browser) |
| `src` | `<img src="...">` | Terminal image protocol (iTerm2/Kitty) or alt text |
| `alt` | `<img alt="...">` | Displayed as text when image can't render |
| `colspan`/`rowspan` | Table cell spanning | Table layout spanning |
| `forId` | `<label for="...">` | Focus association (clicking label focuses input) |
| `inputType` | `<input type="password">` | Password masking (`***` instead of text) |
| `disabled` | Grays out, blocks interaction | Dims element, blocks interaction |
| `value` | Two-way binding | Text display + editing |
| `tabIndex` | Tab order | Focus scan inclusion/exclusion |

**What this eliminates:**

| Dispatch point | Before | After |
|---|---|---|
| TuiFlatten `elementNodeType` (34-way match) | Match on class type → int | `tag.ordinal` |
| TuiFlatten `resolveDisabledIfInteractive` (4-way match) | Match Button/Input/Textarea/Select → `.disabled` | `common.disabled` |
| TuiFlatten `resolveInputText` (2-way match) | Match Input/Textarea → `.value`, `.placeholder` | `if tag.isTextInput` → `common.value`, `common.placeholder` |
| TuiFocus `builtInHandleMouse` (2-way match) | Match Button/Anchor → `common.onClick` | `if tag.isClickable` → `common.onClick` |
| TuiFocus `builtInHandle` (5-way match) | Match type → dispatch | `if tag.isActivatable` / `if tag.isTextInput` |
| TuiFocus `handleTextInput` | Takes `inp.value` / `ta.value` | Takes `common.value` |
| DomBackend `tagName` (34-way match) | Match on class type → string | Backend-internal Tag → String mapping |
| DomBackend `applySpecific` (12-way match) | Match type → apply specialized fields | **Eliminated** — all fields on CommonAttrs, handled uniformly in `applyCommon` |

ALL eight original dispatch points are eliminated.

**DSL surface is unchanged:**

```scala
button("Click").onClick(action).disabled(isDisabled)
input.value(text).placeholder("Type here...")
a("Link").href("https://example.com")
div.style(Style.row)(span("A"), span("B"))
img("photo.jpg", "A photo")
td.colspan(2)("Merged cell")
```

The factory methods become:

```scala
val div: Element    = Element(Tag.Div)
val button: Element = Element(Tag.Button)
val input: Element  = Element(Tag.Input)
def img(src: String, alt: String): Element = Element(Tag.Img).src(src).alt(alt)
```

### Collapse redundant AST variants

`ReactiveText` is `Reactive(signal.map(Text(_)))`. `ForeachIndexed` and `ForeachKeyed` differ only by an optional key function. The full UI AST becomes 5 cases:

```scala
sealed trait UI
object UI:
    case class Element(tag: Tag, common: CommonAttrs, children: Span[UI]) extends UI
    case class Text(value: String) extends UI
    case class Reactive(signal: Signal[UI]) extends UI
    case class Foreach[A](signal: Signal[Chunk[A]], key: Maybe[A => String], render: (Int, A) => UI) extends UI
    case class Fragment(children: Span[UI]) extends UI
```

Implicit conversions adapt:

```scala
given stringToUI: Conversion[String, UI]                                    = Text(_)
given signalStringToUI(using Frame): Conversion[Signal[String], UI] = sig => Reactive(sig.map(s => Text(s)))
given signalUIToUI: Conversion[Signal[UI], UI]                              = Reactive(_)
```

Collection extensions:

```scala
extension [A](signal: Signal[Chunk[A]])
    def foreach(render: A => UI): Foreach[A]               = Foreach(signal, Absent, (_, a) => render(a))
    def foreachIndexed(render: (Int, A) => UI): Foreach[A]  = Foreach(signal, Absent, render)
    def foreachKeyed(key: A => String)(render: A => UI): Foreach[A] =
        Foreach(signal, Present(key), (_, a) => render(a))
    def foreachKeyedIndexed(key: A => String)(render: (Int, A) => UI): Foreach[A] =
        Foreach(signal, Present(key), render)
```

### Keep flat arrays for zero-allocation hot paths

The 45 parallel arrays are the right storage model. No allocations in measure/arrange/paint. The problem isn't the arrays — it's the scattered dispatch that fills them. With Element(tag), the dispatch is eliminated.

### Extract DrawSurface — richer drawing API

The renderer's API is too low-level (`set(x, y, char, style)`). The painter shouldn't know what border characters look like or how text wrapping works. Extract a higher-level drawing interface:

```scala
/** Higher-level drawing primitives. Implemented by TuiRenderer. */
trait DrawSurface:
    def fillRect(x: Int, y: Int, w: Int, h: Int, color: Int): Unit
    def drawBorder(
        x: Int, y: Int, w: Int, h: Int,
        style: Int,
        colorT: Int, colorR: Int, colorB: Int, colorL: Int,
        roundTL: Boolean, roundTR: Boolean, roundBR: Boolean, roundBL: Boolean
    ): Unit
    def drawText(
        x: Int, y: Int, w: Int, h: Int,
        text: String, style: Long,
        align: Int, wrap: Boolean, overflow: Boolean, transform: Int
    ): Unit
    def applyFilter(x: Int, y: Int, w: Int, h: Int, filters: Array[Long]): Unit
    def pushClip(x: Int, y: Int, w: Int, h: Int): Unit
    def popClip(): Unit
```

TuiRenderer implements DrawSurface. The ~180 lines of border character selection, hline/vline drawing, text wrapping/clipping/alignment move from TuiPainter into TuiRenderer (which already owns the cell buffer and knows how to place characters). Filter application is a post-processing pass on the cell buffer — iterate the bounding box and apply each filter in sequence, transforming cell colors. Each filter is packed into a `Long` (filter type + amount), stored as an `Array[Long]` per element to support chaining (e.g., `Style.brightness(1.2).grayscale(0.5)`).

The painter becomes just the tree walk — ~50 lines:

```scala
private def paintNode(layout: TuiLayout, surface: DrawSurface, idx: Int): Unit =
    if idx < 0 || idx >= layout.count then return
    val lf = layout.lFlags(idx)
    if isHidden(lf) then return
    val nx = layout.x(idx); val ny = layout.y(idx)
    val nw = layout.w(idx); val nh = layout.h(idx)
    if nw <= 0 || nh <= 0 then return

    // Background (solid or gradient)
    if layout.bg(idx) != TuiColor.Absent then
        surface.fillRect(nx, ny, nw, nh, layout.bg(idx))

    // Borders
    val pf = layout.pFlags(idx)
    if hasBorder(lf) && borderStyle(pf) != BorderNone then
        surface.drawBorder(nx, ny, nw, nh, borderStyle(pf),
            layout.bdrClrT(idx), layout.bdrClrR(idx), layout.bdrClrB(idx), layout.bdrClrL(idx),
            isRoundedTL(pf), isRoundedTR(pf), isRoundedBR(pf), isRoundedBL(pf))

    // Text
    if layout.text(idx).isDefined then
        surface.drawText(contentX, contentY, contentW, contentH,
            layout.text(idx).get, mkTextStyle(layout, idx),
            textAlign(pf), shouldWrapText(pf), hasTextOverflow(pf), textTrans(pf))

    // Children (with optional clip)
    val clip = overflow(lf) == 1
    if clip then surface.pushClip(contentX, contentY, contentW, contentH)
    paintChildren(layout, surface, idx)
    if clip then surface.popClip()

    // Filters (post-processing on painted cells — multiple filters per element)
    if hasFilters(layout, idx) then
        surface.applyFilter(nx, ny, nw, nh, layout.filters(idx))
```

This separation makes overlays straightforward — two passes, no change to drawing code:

```scala
def paint(layout: TuiLayout, surface: DrawSurface): Unit =
    paintNode(layout, surface, 0, skipOverlays = true)   // pass 1: flow
    paintOverlays(layout, surface)                         // pass 2: overlays on top
```

### Split TuiLayout into focused modules

| New module | Lines (est.) | Responsibility |
|------------|-------------|----------------|
| `TuiLayout.scala` | ~170 | Arrays, alloc, reset, grow, linkChild, constants, flag accessors |
| `TuiFlexLayout.scala` | ~250 | measure + arrange — pure layout algorithm, reads/writes arrays |
| `TuiText.scala` | ~120 | forEachLine (CPS), lineCount, clipText — shared text utilities |

`TuiFlexLayout` is the layout algorithm. It operates on flat arrays of constraints and produces flat arrays of positions. It doesn't know about TUI, terminals, or ANSI. It's a generic flex layout engine that could be tested independently and eventually shared with other backends.

`TuiText` handles all text measurement and wrapping. Used by both layout (line counting for intrinsic sizing) and drawing (actual wrapping via TuiRenderer). Currently this logic exists in TuiLayout but is also needed by TuiPainter — extracting it eliminates that coupling. **Zero-allocation design via CPS (technique #10):** two `inline def` methods — `lineCount(text, maxWidth): Int` for layout measure, and `forEachLine(text, maxWidth)(inline f: (Int, Int) => Unit): Int` for renderer draw. No substrings, no tuples, no intermediate arrays. The renderer's callback writes chars directly from the source string to the cell buffer.

Box-drawing characters (`borderChars`) move into TuiRenderer's DrawSurface implementation, where they're actually used.

### Consolidate all style concerns into TuiStyle

"What does this element look like right now?" is currently answered by three different modules at three different pipeline stages:

1. **TuiStyle.resolve** — base style props → layout arrays (during flatten)
2. **TuiPainter.inheritStyles** — propagate fg/bg/pFlags parent → child (after layout)
3. **TuiFocus.applyFocusStyle** — overlay focus visual onto layout arrays (after inherit)

The redesign adds three more state overlays (hover, active, disabled), each doing the same thing as focus — call `TuiStyle.overlay(style, layout, idx)`. Without consolidation, this pattern would repeat four times across two files.

**Consolidation:** TuiStyle becomes the single owner of all style concerns. The pipeline becomes:

```
TuiFlatten(+TuiStyle.resolve) → TuiFlexLayout(measure+arrange) → TuiFocus.scan → TuiStyle(inherit+applyStates) → TuiPainter(paint) → TuiRenderer(diff+flush) → TuiTerminal
                                                                                    TuiFocus(dispatch)
```

```scala
object TuiStyle:
    /** Base style resolution — called per node during flatten. */
    def resolve(style: Style, layout: TuiLayout, idx: Int, parentW: Int, parentH: Int): Unit

    /** Parent→child inheritance — forward pass after layout. */
    def inherit(layout: TuiLayout): Unit

    /** State-dependent overlays — apply focus/hover/active/disabled styles. */
    def applyStates(layout: TuiLayout, focusedIdx: Int, hoverIdx: Int, activeIdx: Int): Unit
```

- `inherit` is the current `TuiPainter.inheritStyles` moved here (propagates fg/bg/pFlags/opacity).
- `applyStates` replaces `TuiFocus.applyFocusStyle` and handles all four state overlays uniformly. It reads `focusStyle`/`hoverStyle`/`activeStyle`/`disabledStyle` from layout arrays and calls `overlay` for each active state.
- TuiFocus no longer touches layout arrays — it only tracks interaction state (focusedIdx, hoverIdx, activeIdx, cursorPos) and dispatches events.
- TuiPainter no longer modifies style — it just reads final resolved values and paints.

### Net effect of restructuring

| Component | Before | After |
|-----------|--------|-------|
| UI.scala | 478 lines (34 case classes, ~8-12 lines each) | ~150 lines (1 Element class, Tag enum, CommonAttrs) |
| DomBackend.scala | 541 lines (34-way tagName + 12-way applySpecific) | ~350 lines (uniform applyCommon, no type dispatch) |
| TuiFlatten.scala | 242 lines (34-way elementNodeType + 4-way disabled + 2-way inputText) | ~100 lines (5-case match, flag-driven) |
| TuiStyle | 265 lines (resolve + overlay) | ~350 lines (resolve + inherit + applyStates + overlay — consolidated style owner) |
| TuiPainter | 301 lines (tree walk + inheritStyles + borders + text rendering) | ~50 lines (tree walk only, no style logic) |
| TuiFocus | 267 lines (scan + applyFocusStyle + dispatch) | ~200 lines (scan + dispatch, no style application) |
| TuiRenderer | 321 lines (cell buffer + diff) | ~600 lines (cell buffer + diff + DrawSurface + filters) |
| TuiLayout | 736 lines (data + algorithm + text + constants) | ~170 lines (data + constants) |
| TuiFlexLayout | — | ~250 lines (measure + arrange) |
| TuiText | — | ~120 lines (wrap + clip) |

~810 fewer lines total. The main win is conceptual: 40 AST types → 5, 8 dispatch points → 0, zero escape hatches. Style concerns consolidated into TuiStyle (single owner of resolve, inherit, and state overlays).

---

## Style — Complete Visual API

Style is the sole mechanism for visual presentation. No inline CSS, no CSS classes, no stringly-typed style escape hatches. If a visual concept exists, it's a typed Style prop.

### What Style already covers

- **Colors**: fg, bg, border colors (24-bit)
- **Typography**: fontSize, fontFamily, bold, italic, underline, strikethrough, dim
- **Spacing**: padding (per-side), margin (per-side), gap
- **Sizing**: width, height, minWidth, maxWidth, minHeight, maxHeight
- **Layout**: flex direction (row/column), align, justify
- **Borders**: width, style (solid/dashed/dotted/double), color, radius (per-corner)
- **Text**: align, decoration, transform (uppercase/lowercase/capitalize), overflow (ellipsis), wrap
- **Visual effects**: opacity, box shadow, translate (X/Y)
- **Cursor**: pointer/text/default/etc. (DOM: CSS cursor. TUI: intent expressed through focus/hover effects)
- **Overflow**: hidden/visible/scroll

### Bugs in current Style (MatchError at runtime)

TuiStyle.resolve doesn't handle these two existing props:

| Prop | Style API | What happens in TUI |
|------|-----------|---------------------|
| `FlexDirectionProp` | `Style.row`, `Style.column` | **MatchError** — direction comes only from element type, not style |
| `WrapTextProp` | `Style.wrapText(false)` | **MatchError** — wrap is hardcoded on, can't be turned off |

Both work correctly in CssStyleRenderer (DOM). Fix: add match cases in TuiStyle.resolve.

### New Style props

#### Layout

| Prop | Why needed | API |
|------|-----------|-----|
| `PositionProp` | Overlays, modals, tooltips | `Style.position(Position.overlay)` |
| `FlexGrowProp` | Fill remaining space | `Style.flexGrow(1.0)`, `Style.grow` |
| `FlexShrinkProp` | Prevent shrinking | `Style.flexShrink(0.0)`, `Style.noShrink` |

Without FlexGrow, you can't express "this element fills the remaining width" — the most basic layout pattern (header, sidebar+content, footer).

#### Filters (cross-platform color math)

All filters are color transformations on rendered output. In TUI, this is a post-processing pass on the cell buffer after painting — iterate the element's bounding box and transform each cell's fg/bg colors.

| Prop | What it does | TUI implementation |
|------|-------------|-------------------|
| `BrightnessProp(v: Float)` | Scale RGB values | `r * v, g * v, b * v` (clamp 0-255) |
| `ContrastProp(v: Float)` | Scale relative to mid-gray | `128 + (r - 128) * v` per channel |
| `GrayscaleProp(v: Float)` | Convert to luminance | `0.299*r + 0.587*g + 0.114*b`, blend by amount |
| `SepiaProp(v: Float)` | Warm-tone color matrix | Standard sepia matrix transformation |
| `InvertProp(v: Float)` | Invert colors | `255 - r, 255 - g, 255 - b`, blend by amount |
| `SaturateProp(v: Float)` | Adjust saturation | Convert to HSL, scale S, convert back |
| `HueRotateProp(v: Float)` | Rotate hue | Convert to HSL, rotate H, convert back |
| `BlurProp(v: Float)` | Soften colors | Average neighboring cells' fg/bg colors |

```scala
Style.brightness(1.2)           // 20% brighter
Style.grayscale(1.0)            // fully desaturated
Style.blur(1.0)                 // blur colors across neighboring cells
Style.filter(Filter.sepia(0.5)) // 50% sepia tone
```

Blur in TUI: reads neighboring cells and averages their colors. Text characters stay sharp (the blur is on color, not geometry). For stronger blur, can also replace characters with block elements (`░▒▓`).

#### Background gradients

```scala
Style.bgGradient(direction: GradientDirection, stops: (String, Float)*)
Style.bgGradient(GradientDirection.toRight, "#ff0000" -> 0f, "#0000ff" -> 1f)
```

DOM: maps to CSS `linear-gradient`. TUI: interpolate between stop colors across the element's character cells in the given direction. Each cell gets the color at its position along the gradient axis.

#### State-dependent styles

`HoverProp`, `FocusProp`, and `ActiveProp` already exist in Style.scala and are resolved by TuiStyle (overlay applied to layout arrays). Only `DisabledProp` is new.

```scala
Style.hover(Style.bg("#eee"))                          // already exists
Style.focus(Style.border(1, BorderStyle.solid, "#00f")) // already exists
Style.active(Style.bg("#ddd"))                          // already exists
Style.disabled(Style.opacity(0.5))                      // NEW — needs DisabledProp added
```

- DOM: maps to CSS pseudo-classes (`:hover`, `:focus`, `:active`, `:disabled`)
- TUI: all four applied by `TuiStyle.applyStates` — overlays focus/hover/active/disabled styles onto layout arrays based on interaction state from TuiFocus.

These compose naturally:

```scala
button("Click").style(
    Style.bg("#4a9eff").color("#fff")
        .hover(Style.bg("#3a8eef"))
        .active(Style.bg("#2a7edf"))
        .disabled(Style.bg("#999").color("#666"))
)
```

### Not adding (deferred or unnecessary)

- `ZIndexProp` — tree order sufficient for v1 overlay stacking
- `DisplayProp` — `hidden` on CommonAttrs covers display:none
- `AnimationProp` / `TransitionProp` — significant complexity, deferred to v2
- `GridProp` — flexbox covers most layouts, grid deferred to v2
- Background images — pixel data, not cross-platform
- Rotate/scale transforms — sub-character geometry, not cross-platform

---

## TUI Pipeline Mapping

Every feature must trace a complete path from UI/Style definition through TUI rendering. This section maps each feature through the pipeline stages: **TuiFlatten** (AST → flat arrays, calls TuiStyle.resolve) → **TuiFlexLayout** (measure + arrange) → **TuiFocus.scan** (focusable indices) → **TuiStyle** (inherit + state overlays) → **TuiPainter** (tree walk → DrawSurface) → **TuiRenderer** (cell buffer + diff) → **TuiTerminal** (output).

### CommonAttrs → TUI

#### Works end-to-end (no changes needed)

| Field | Flatten (+Style.resolve) | Layout | Style (inherit+states) | Painter | Focus | Renderer |
|-------|--------------------------|--------|------------------------|---------|-------|----------|
| `hidden` | Sets `HiddenBit` in lFlags (bool or signal) | Skips hidden nodes | Skips hidden nodes | Skips hidden nodes | Skips hidden nodes | — |
| `uiStyle` | `TuiStyle.resolve` unpacks all props into layout arrays | Uses arrays for measure/arrange | `inherit` propagates fg/bg/pFlags; `applyStates` overlays state styles | Reads final arrays for paint | — | Cell-level output |
| `onKeyDown` | — | — | — | — | `fireHandler(common.onKeyDown, event)` on focused element | — |
| `onKeyUp` | — | — | — | — | `fireHandler(common.onKeyUp, event)` on focused element | — |
| `disabled` | Sets `DisabledBit` in lFlags (bool or signal) | — | `applyStates` overlays DisabledProp style | — | Skips in focus scan | — |
| `value` | Creates synthetic text child from String or SignalRef | Text child measured | Inherits fg/bg from parent | Text child painted | `handleTextInput` mutates SignalRef directly | Text rendered |
| `placeholder` | Fallback text when value is empty | Placeholder text measured | Inherits fg/bg from parent | Placeholder text painted | — | Text rendered |

#### Partially working — redesign fixes

| Field | Current | After redesign |
|-------|---------|---------------|
| `onClick` | Only fires for Button/Anchor (type-driven dispatch) | Fires for ANY element with onClick (handler-driven). Event bubbling via parent chain. |
| `onFocus` / `onBlur` | Silently ignored | Fire when focus moves to/from element in TuiFocus. Focus manager tracks previous focused index and fires onBlur on old, onFocus on new. |
| `onInput` | Silently ignored — TUI uses Signal mutation, never calls callback | Fire callback after Signal mutation in `handleTextInput`. Both mechanisms work: SignalRef is mutated AND onInput callback is called. |

#### New in redesign — needs TUI implementation

| Field | TUI path |
|-------|----------|
| `tabIndex` | TuiFocus.scan: positive tabIndex → include in focus order at that position. Negative → exclude. Zero → default order. |
| `checked` | TuiFlatten: render `[x]` or `[ ]` prefix for Input with `inputType = "checkbox"`. TuiFocus: toggle on Enter/Space. |
| `selected` | TuiFlatten: visual indicator on selected Option within Select. |
| `onChange` | TuiFocus: fire after Select value changes. |
| `onSubmit` | TuiFocus: fire on Enter in a Form's descendant input (walk parent chain to find Form, fire onSubmit). |
| `href` | TuiFocus: on activation (Enter/click on Anchor), run `open`/`xdg-open` with href value. |
| `target` | Ignored — TUI always opens in system browser. |
| `src` | TuiFlatten: try terminal image protocol (iTerm2 inline images / Kitty). Fall back to alt text. |
| `alt` | TuiFlatten: displayed as text when src can't render (or always, since image protocol support varies). |
| `colspan` / `rowspan` | TuiFlexLayout: table cell spanning in measure + arrange. Merged cell occupies multiple columns/rows. |
| `forId` | TuiFocus: clicking a Label with forId moves focus to the element with matching identifier. |
| `inputType` | TuiFlatten: `"password"` → mask value with `•` characters. `"checkbox"` → render `[x]`/`[ ]`. Others → plain text. |

#### Intentionally ignored in TUI

| Field | Why |
|-------|-----|
| `identifier` | Used internally for forId/focus association, not rendered. No terminal equivalent. |

### Style props → TUI

#### Works end-to-end (32 props)

| Category | Props | TuiStyle.resolve → | TuiFlexLayout → | TuiStyle (inherit+states) → | Painter → Renderer |
|----------|-------|-------------------|----------------|----------------------------|-------------------|
| Colors | BgColor, TextColor | `layout.bg`, `layout.fg` | — | `inherit` propagates fg/bg to children; `applyStates` can override for focus/hover/active/disabled | Cell fg/bg bits |
| Spacing | Padding, Margin, Gap | `padT/R/B/L`, `marT/R/B/L`, `gap` | measure insets, arrange spacing | — | — |
| Sizing | Width, Height, Min/MaxWidth, Min/MaxHeight | `sizeW/H`, `minW/maxW`, `minH/maxH` | measure override, arrange clamp | — | — |
| Typography | FontWeightProp, FontStyleProp, TextDecorationProp | `pFlags` bold/dim/italic/underline/strikethrough bits | — | `inherit` propagates pFlags for text nodes | ANSI SGR attrs |
| Text | TextAlignProp, TextTransformProp, TextOverflowProp | `pFlags` textAlign/textTrans/textOverflow bits | — | — | drawText align/transform/ellipsis |
| Borders | BorderWidthProp, BorderStyleProp, BorderColorProp, BorderTop/Right/Bottom/LeftProp, BorderRadiusProp | `lFlags` border bits + `bdrClrT/R/B/L` + `pFlags` borderStyle/rounded bits | measure/arrange border insets | `applyStates` can add/change borders for focus/hover/active/disabled | drawBorder with box-drawing chars |
| Layout | Align, Justify, OverflowProp | `lFlags` align/justify/overflow bits | arrange cross-axis offset, main-axis justify, overflow clip | — | pushClip for overflow:hidden |
| Effects | OpacityProp, TranslateProp | `layout.opac`, `layout.transX/Y` | arrange applies translate offset | `inherit` blends opacity with parent bg | — |
| Font | FontSizeProp | `layout.fontSz` | — | — | Scales text proportionally |
| State | FocusProp | `layout.focusStyle(idx)` (stores style for later) | — | `applyStates` overlays onto focused element | — |

#### Bugs — crash at runtime (2 props)

| Prop | Bug | Fix |
|------|-----|-----|
| `FlexDirectionProp` | Missing case in TuiStyle.resolve → **MatchError**. Direction currently hardcoded by element type in TuiFlatten (inline → row, block → column). | Add case: write direction bit to `lFlags`. TuiFlatten still sets default from Tag.isInline, but style can override. |
| `WrapTextProp` | Missing case in TuiStyle.resolve → **MatchError**. Wrap hardcoded ON in setDefaults. | Add case: clear/set WrapTextBit in `pFlags`. |

#### Dead code — resolved but never consumed (2 props)

| Prop | Resolved to | Never read by |
|------|------------|---------------|
| `LineHeightProp` | `layout.lineH(idx)` | TuiPainter never reads lineH — text always uses single-line height. |
| `LetterSpacingProp` | `layout.letSp(idx)` | TuiPainter never reads letSp — chars always placed at unit width. |

**Fix:** Either implement in TuiPainter (lineH → extra blank lines between text lines; letSp → extra spaces between characters) or document as "not yet supported" and remove the dead arrays.

#### Partial / unimplemented (3 props)

| Prop | Current state | Fix |
|------|--------------|-----|
| `ShadowProp` | Only shadow color stored in `layout.shadow(idx)`. x/y/blur/spread params **discarded**. Never rendered. | TuiRenderer.drawShadow: paint dim-colored cells adjacent to element bounds (offset by x/y, spread by spread). Blur → gradient dim characters (`░▒▓`). |
| `HoverProp` | Style stored on element ref via TuiStyle. No hover state machine — mouse move events dropped. | TuiFocus tracks hoverIdx (hitTest on mouse move). TuiStyle.applyStates overlays HoverProp style on hovered element. |
| `ActiveProp` | Style stored in `layout.activeStyle(idx)`. Never applied. | TuiFocus tracks activeIdx (set on mouse down, clear on mouse up). TuiStyle.applyStates overlays ActiveProp style on active element. |

#### Intentionally ignored (2 props)

| Prop | Why |
|------|-----|
| `FontFamilyProp` | Terminals use their configured font — no per-element font selection. |
| `CursorProp` | Terminal emulator controls mouse cursor shape. TUI expresses "interactive" intent through focus highlighting and hover effects instead. |

#### New props — TUI implementation plan

| Prop | TuiStyle.resolve | Layout array | Consumed by |
|------|-----------------|-------------|-------------|
| `PositionProp` | Set position bit in lFlags (0=flow, 1=overlay) | `lFlags` position bit | TuiFlexLayout: skip overlay elements in flow arrange. TuiPainter: two-pass paint (flow then overlays). |
| `FlexGrowProp` | Write to `layout.flexGrow(idx)` | New `flexGrow` array | TuiFlexLayout.arrange: distribute remaining space proportionally among children with grow > 0. |
| `FlexShrinkProp` | Write to `layout.flexShrink(idx)` | New `flexShrink` array | TuiFlexLayout.arrange: shrink children proportionally when total exceeds container. |
| `DisabledProp` | Store style on element (like FocusProp) | `layout.disabledStyle` | TuiStyle.applyStates: overlay DisabledProp style on elements with disabled=true. |
| Filter props (8) | Pack filter type + amount into Array[Long] | New `filters` array | TuiRenderer.applyFilter: post-processing pass on cell buffer. Iterate bounding box, transform each cell's fg/bg. |
| `BgGradientProp` | Store direction + color stops | New gradient arrays or packed into existing bg | TuiRenderer: interpolate stop colors across element's character cells in given direction. |

---

## Event Dispatch Redesign

### Handler-driven, not type-driven

Instead of "is this a Button? then fire onClick" — check "does this element have onClick? then fire it." With all interaction fields on CommonAttrs and behavior flags on Tag, dispatch is uniform:

```scala
def dispatchToElement(event: InputEvent, layout: TuiLayout, idx: Int)(using Frame, AllowUnsafe): Unit < Sync =
    val elem = layout.element(idx)
    if elem.isEmpty then ()
    else
        val element = elem.get.asInstanceOf[Element]
        val common  = element.common
        val tag     = element.tag
        event match
            // Mouse click: any element with onClick
            case InputEvent.Mouse(LeftPress, _, _, _, _, _) =>
                fireAction(common.onClick)
            // Keyboard activation: only activatable elements
            case InputEvent.Key("Enter" | " ", false, false, false) if Tag.isActivatable(tag) =>
                fireAction(common.onClick)
            // Text input: only text-input elements
            case key: InputEvent.Key if Tag.isTextInput(tag) =>
                handleTextInput(common.value, key, allowNewline = tag == Tag.Textarea)
            case _ => ()
        for
            _ <- fireHandler(common.onKeyDown, event)
            _ <- fireHandler(common.onKeyUp, event)
        yield ()
```

No type matching on the element. `Tag` flags drive behavior. `CommonAttrs` fields provide the handlers.

### Hit testing: all elements, reverse paint order

Replace the current approach (scan only focusable indices) with walking all elements in reverse array order. Depth-first allocation means higher indices = deeper in tree = painted later = visually on top:

```scala
def hitTest(mx: Int, my: Int, layout: TuiLayout): Int =
    @tailrec def loop(i: Int): Int =
        if i < 0 then -1
        else if TuiLayout.isHidden(layout.lFlags(i)) then loop(i - 1)
        else if mx >= layout.x(i) && mx < layout.x(i) + layout.w(i) &&
                my >= layout.y(i) && my < layout.y(i) + layout.h(i) then
            i  // topmost element at this point
        else loop(i - 1)
    loop(layout.count - 1)
```

### Event bubbling

From the hit element, walk up the parent chain to find the nearest handler. This is DOM event bubbling:

```scala
def findClickHandler(layout: TuiLayout, idx: Int): Int =
    @tailrec def loop(i: Int): Int =
        if i < 0 then -1
        else
            val elem = layout.element(i)
            if elem.isDefined && elem.get.asInstanceOf[Element].common.onClick.isDefined then i
            else loop(layout.parent(i))
    loop(idx)
```

Mouse click at (x, y):
1. `hitTest(x, y)` → deepest visible element at that point
2. `findClickHandler(result)` → walk parent chain to find onClick
3. If focusable element found along the way → move focus there
4. Fire onClick

This makes `div.onClick(...)` work correctly — the click on any child of the div bubbles up and fires the handler.

---

## Overlapping / Z-order

### Current state

No support. Layout is purely sequential flex. The TuiPainter does a depth-first tree walk; TuiRenderer is a flat cell grid (last-write-wins). If siblings overlap via negative margins or translate, results are accidental.

### Minimal viable approach

For modals, tooltips, dropdowns:

1. **Position flag** in lFlags: 0 = flow (default), 1 = overlay (positioned absolutely relative to terminal).

2. **No z-index array needed initially.** Tree order within overlays determines stacking. Overlays that appear later in the UI tree paint on top. This matches DOM behavior where later siblings are on top unless z-index overrides.

3. **Two-pass paint**:
   - Pass 1: paint all flow elements normally (position=0)
   - Pass 2: paint overlay elements in tree order (position=1)

4. **Hit testing**: check overlay elements first (reverse order), then flow elements. Since overlays paint last, they're visually on top and should receive events first.

5. **Focus trapping**: when an overlay is active, Tab/Shift-Tab only cycles within descendants of the topmost overlay. The parent chain in the flat arrays gives `isDescendant` cheaply.

### DSL

```scala
// Simple overlay — rendered on top of everything, centered
div.style(Style.position(Position.overlay))(
    div.style(Style.bg("#333").padding(16).rounded(8))(
        h2("Confirm"),
        p("Are you sure?"),
        nav(
            button("Cancel").onClick(showModal.set(false)),
            button("OK").onClick(doConfirm)
        )
    )
)
```

No z-index for v1. Tree order is sufficient for modal stacking (inner modal after outer = on top). Z-index can be added later if needed.

---

## Mouse Interaction

### What works today

- SGR mouse parsing is complete: press, release, drag, scroll, modifiers
- All-motion tracking enabled (`?1003h`) — terminal sends Move events
- Left-click hit tests focusable elements and dispatches onClick for Button/Anchor

### What's missing and how to add it

#### Hover effects

Mouse move events are already parsed but dropped by dispatch. To add hover:

1. Store `hoverIdx: Int` on TuiFocus (layout index under mouse, -1 if none)
2. On `InputEvent.Mouse(Move, x, y, ...)`: run `hitTest(x, y)`, update `hoverIdx`
3. `TuiStyle.applyStates` overlays hover style from `Style.hover(...)` onto the hovered element's layout arrays
4. If `hoverIdx` changed, trigger a re-render

Cost: one `hitTest` per mouse move event + one style overlay per frame for the hovered element.

#### Scroll

Scroll events are parsed but ignored. To add scrollable containers:

1. Add `scrollX`, `scrollY` arrays to TuiLayout
2. During arrange: offset children by `-scrollX/-scrollY` within scrollable containers (overflow = scroll/auto)
3. Paint already clips children for overflow:hidden via `renderer.setClip`
4. On `ScrollUp`/`ScrollDown`: find the container under the mouse, adjust its scroll offset
5. Render a scroll indicator (optional): `▲ █ ▼` on the right edge of scrollable containers

#### Drag

Drag events are parsed (`LeftDrag`, `MiddleDrag`, `RightDrag`) but never handled. Drag support would need:

1. Track drag start position and source element
2. On drag move: update position, fire drag event on source
3. On release: run hit test at release position, fire drop event on target

Not essential for v1. Drag is a rare TUI interaction pattern.

### Mouse cursor shape

Style's `CursorProp` expresses intent ("this element is interactive/text-editable/etc."). DOM maps to CSS `cursor`. TUI expresses this intent through focus highlighting and hover visual feedback — the terminal emulator controls the actual mouse pointer shape.

---

## Keyboard Interaction

### What works today

- Tab/Shift-Tab cycles focus linearly through focusable elements
- Enter/Space activates focused button
- Character-by-character typing into Input/Textarea (append only)
- Backspace deletes last character
- Enter in Textarea inserts newline
- Ctrl+C quits the application
- Focus preserved across re-renders by element identity (`eq`)

### What's missing and how to add it

#### Text cursor and in-place editing

Currently: no cursor position, all edits are append-only. This is the biggest usability gap.

**Implementation: use the native terminal cursor.** The terminal already has a cursor that blinks for free. Currently hidden. After each `flush()`:

```
if focused element is a text input:
    position terminal cursor at insertion point
    set cursor shape (\e[5 q = blinking bar)
    show cursor (\e[?25h)
else:
    hide cursor (\e[?25l)
```

The terminal emulator handles blink animation. Zero cost in the render loop.

**Cursor state in TuiFocus:**

```scala
private var cursorPos: Int = -1  // -1 = end of text
```

**Keyboard handling for text inputs:**

| Key | Action |
|-----|--------|
| ArrowLeft | `cursorPos -= 1` (clamp to 0) |
| ArrowRight | `cursorPos += 1` (clamp to text length) |
| Home | `cursorPos = 0` |
| End | `cursorPos = text.length` |
| Backspace | Delete char at `cursorPos - 1`, `cursorPos -= 1` |
| Delete | Delete char at `cursorPos` |
| Character | Insert at `cursorPos`, `cursorPos += 1` |

**Rendering the cursor position**: After `renderer.flush()`, emit:

```scala
val focusIdx = focus.focusedIndex
if focusIdx >= 0 && Tag.isTextInput(layout.tag(focusIdx)) then
    val textStartX = layout.x(focusIdx) + borderL + padL
    val textStartY = layout.y(focusIdx) + borderT + padT
    val cursorX    = textStartX + focus.cursorPos
    terminal.showCursor(cursorX, textStartY)
else
    terminal.hideCursor()
```

#### Arrow key spatial navigation

Not essential for v1. Tab cycling covers most TUI use cases.

#### Focus groups / focus traps

Needed for modals. When an overlay is visible, Tab should only cycle within the overlay's descendants.

#### Keyboard shortcuts

The `onKeyDown` handler on `CommonAttrs` already supports this. A root-level `div.onKeyDown(...)` can intercept any key.

#### Paste

Bracketed paste (`\e[?2004h`) is enabled. `TuiInput` parses `Paste(text)` events correctly. But `TuiFocus.dispatch` doesn't handle them. Fix: when focused on a text input and `Paste(text)` is received, insert the full text at cursor position.

---

## Text Selection

### Not implemented

No selection model exists. Would require:

1. Selection anchor + cursor positions per text input
2. Shift+Arrow to extend selection
3. Shift+Click to select range
4. Rendering: highlight selected text with inverted/custom bg color
5. Ctrl+C (when not Ctrl+C-to-quit) copies selection
6. Copy to clipboard via OSC 52 escape sequence (`\e]52;c;base64-text\e\\`)

### Interaction with Ctrl+C

Currently Ctrl+C quits the application. To support copy, either:
- Use a different quit key (Ctrl+Q)
- Ctrl+C copies when there's a selection, quits when there isn't
- Only quit on double Ctrl+C

This is a v2+ feature.

---

## File Inventory

### Shared

| File | Lines | Change |
|------|-------|--------|
| `UI.scala` | 478 → ~150 | Complete rewrite: `Element(tag, common, children)` replaces 34 case classes. `Tag` enum with behavior flags (no backend-specific data). `Reactive` replaces `ReactiveText`+`ReactiveNode`. `Foreach` replaces `ForeachIndexed`+`ForeachKeyed`. CommonAttrs: remove escape hatches (`styles`, `classes`, `dynamicClassName`, `handlers`, `attrs`), absorb typed interaction fields. |
| `Style.scala` | 539 | Add `PositionProp`, `FlexGrowProp`, `FlexShrinkProp`, `DisabledProp`. Add filter props (brightness, contrast, grayscale, sepia, invert, saturate, hueRotate, blur). Add `BgGradientProp`. (HoverProp, FocusProp, ActiveProp already exist.) |
| `UIDsl.scala` | 23 | Update exports for new Tag/Element/Style types (Position, GradientDirection, etc.) |
| `UIBackend.scala` | 5 | No change |
| `UISession.scala` | 9 | No change |
| `CssStyleRenderer.scala` | 150 | Add cases for new Style props (position, flex grow/shrink, filters, gradients, state modifiers → CSS pseudo-classes). |
| `FxCssStyleRenderer.scala` | 155 | **Delete** — replaced by WebView |

### JVM — TUI internals

| File | Lines | Change |
|------|-------|--------|
| `TuiFlatten.scala` | 242 → ~100 | 5-case match on UI. `tag.ordinal` replaces 34-way `elementNodeType`. `common.disabled` replaces 4-way match. `common.value`/`common.placeholder` replace 2-way match. Flag checks via `Tag.isTextInput`, `Tag.isInline`. |
| `TuiLayout.scala` | 736 → ~170 | Remove `isInlineNode`/`isFocusable` (now on Tag). Add `flexGrow`/`flexShrink` arrays. Add `scrollX`/`scrollY` arrays. Add filter arrays. Add `hoverStyle`/`disabledStyle` arrays (alongside existing `focusStyle`/`activeStyle`). Data + constants only. |
| `TuiFlexLayout.scala` | NEW ~250 | measure + arrange extracted from TuiLayout. |
| `TuiText.scala` | NEW ~120 | forEachLine (CPS), lineCount, clipText extracted from TuiLayout. Zero-allocation text wrapping via technique #10. |
| `TuiFocus.scala` | 267 → ~200 | Major refactor: handler-driven dispatch via `Tag` flags + `CommonAttrs` fields. Full hit testing (all elements, reverse order). Event bubbling via parent chain. Cursor position tracking. Hover/active index tracking. Fire onFocus/onBlur callbacks on focus change. Fire onInput callback after Signal mutation. Fire onSubmit by walking parent chain to find Form. Anchor activation opens href via system browser. No longer applies styles — interaction state (focusedIdx, hoverIdx, activeIdx) is read by TuiStyle.applyStates. |
| `TuiPainter.scala` | 301 → ~50 | Tree walk only. Borders/text/filter drawing via DrawSurface. Two-pass paint for overlays. No longer handles style inheritance (moved to TuiStyle.inherit). |
| `TuiStyle.scala` | 265 → ~350 | Consolidated style owner. Three methods: `resolve` (base style → arrays, called during flatten), `inherit` (parent→child fg/bg/pFlags/opacity propagation, was TuiPainter.inheritStyles), `applyStates` (focus/hover/active/disabled overlays, was TuiFocus.applyFocusStyle). Fix FlexDirectionProp and WrapTextProp MatchError bugs. Add position, flexGrow, flexShrink, disabled, filter, gradient prop resolution. Wire lineH/letSp consumption or remove dead arrays. Fix ShadowProp to store full params. |
| `TuiRenderer.scala` | 321 → ~600 | Implements DrawSurface. Absorbs border/text rendering from TuiPainter. Adds filter post-processing (color math on cell buffer). Adds gradient fill. |
| `TuiTerminal.scala` | 188 | Add `showCursor(x, y, shape)`, `hideCursor()`. |
| `TuiInput.scala` | 335 | No change (already complete) |
| `TuiColor.scala` | 138 | Add color math utilities for filters (brightness, contrast, HSL conversion, etc.) |
| `TuiAnsiBuffer.scala` | — | No change |
| `TuiSignalCollector.scala` | — | No change |

### JVM — Backends

| File | Lines | Change |
|------|-------|--------|
| `TuiBackend.scala` | 243 | Update pipeline call order: `flatten → flexLayout → focus.scan → style.inherit → style.applyStates → paint → flush`. Add cursor show/hide after flush. |
| `TuiSimulator.scala` | 168 | Update for new hit testing / dispatch API. |
| `JavaFxBackend.scala` | 1,468 | **Replace** with WebView wrapper (~100 lines). |

### JS

| File | Lines | Change |
|------|-------|--------|
| `DomBackend.scala` | 541 → ~350 | Backend-internal Tag → HTML name mapping. `applySpecific` eliminated — all fields handled uniformly in `applyCommon`. No escape hatch fields to process. |
| `DomStyleSheet.scala` | — | No change |

---

## Implementation Phases

### Approach: clean room rewrite

The old implementation is kept as read-only reference. New code is written from scratch, not patched.

1. **Move old sources** to `kyo-ui/old/` (non-source folder, not compiled). Keep them for reference only.
2. **Write new files from scratch** in their normal source locations, using this design doc as the spec and old sources as implementation reference.
3. **Port code cleanly** — take the opportunity to ensure ported code follows all optimization techniques (section above), is safe, and has no dead code or workarounds from the old design.
4. **Never copy-paste old code blindly** — understand it, then rewrite it in the context of the new design.

### Workflow

- **Read this entire file** before starting any phase. It is the single source of truth.
- **One phase at a time.** Complete all work items, run all verify checks, then return for review.
- **Never commit** — user handles all git commits.
- Each phase must compile and pass existing tests before moving on.
- On completion, produce a reply with:
  - **Outline**: what was done, files touched, key decisions
  - **Divergences**: anything that differs from this design doc and why

### Source locations

```
kyo-ui/shared/src/main/scala/kyo/            — UI.scala, Style.scala, UIBackend.scala, UISession.scala, UIDsl.scala
kyo-ui/shared/src/main/scala/kyo/internal/   — CssStyleRenderer.scala, FxCssStyleRenderer.scala
kyo-ui/shared/src/main/scala/demo/           — ~34 demo UIs (DemoUI.scala, FormUI.scala, etc.) — use UI DSL extensively
kyo-ui/shared/src/test/scala/kyo/            — UITest.scala, StyleTest.scala
kyo-ui/jvm/src/main/scala/kyo/internal/      — TUI pipeline (TuiFlatten, TuiLayout, TuiPainter, TuiStyle, etc.)
kyo-ui/jvm/src/main/scala/kyo/               — TuiBackend.scala, TuiSimulator.scala, JavaFxBackend.scala
kyo-ui/jvm/src/main/scala/demo/              — TuiDemoApp.scala, TuiDumpFrame.scala, JavaFx demo apps
kyo-ui/jvm/src/test/scala/kyo/               — TuiLayoutTest, TuiStyleTest, TuiFocusTest, TuiSimulatorTest, etc.
kyo-ui/js/src/main/scala/kyo/                — DomBackend.scala
kyo-ui/js/src/main/scala/kyo/internal/       — DomStyleSheet.scala
kyo-ui/old/                                  — old sources (read-only reference, not compiled)
```

Demo files and tests use the UI DSL heavily. When UI.scala changes (Phase 2), all demos and tests will need updating. Move old demos/tests to `kyo-ui/old/` along with the old sources.

### Code quality rules

All new code must follow these rules. Verify on every phase before returning.

1. **Simplicity first.** Prefer the simplest correct implementation. No premature abstraction, no over-engineering.
2. **No unsafe constructs without justification.** The following require a `// unsafe: <reason>` comment:
   - `throw` — prefer returning errors via kyo effects
   - `return` — prefer structured control flow
   - `while` — prefer `@tailrec` recursion or `Span.foreach` (exception: `while` in `inline def` for performance is justified by technique #10)
   - `.asInstanceOf` — prefer pattern matching or type-safe alternatives
   - `null` — prefer `Maybe`/`Absent`
3. **No dead code.** Don't leave commented-out code, unused imports, unused methods, or TODO placeholders.
4. **No unnecessary allocations.** Follow all 11 optimization techniques in the zero-allocation section.
5. **Use kyo primitives over stdlib equivalents.** Before using a stdlib type, check if kyo has a zero-cost alternative:

   **kyo-data (zero-cost opaque types):**
   - `Maybe` not `Option` — `Present(x)` is just `x` at runtime, no boxing
   - `Span` not `Array`/`Seq`/`List` for immutable sequences — opaque over Array, `inline def` iteration (foreach, foldLeft, etc. compile to while loops)
   - `Result` not `Try`/`Either` — distinguishes expected failures from panics
   - `Duration` not `java.time.Duration` / `scala.concurrent.duration.Duration` — opaque over Long (nanos), extensions like `100.millis`
   - `Instant` not `java.time.Instant` — opaque wrapper with duration arithmetic
   - `Dict` not `Map` — opaque, uses Span for ≤8 entries (linear scan), HashMap for larger. Avoids tuple allocations for entries.

   **kyo-core (reactive/async):**
   - `Signal` / `Signal.Ref` for reactive state (already used by UI)
   - `Channel` for event communication between fibers
   - `Hub` for broadcast/pub-sub patterns
   - `Scope` for resource lifecycle (acquire/release)
   - `Clock` for timing, deadlines, repeated tasks
   - `Atomic` for thread-safe mutable state without effects

   **Use stdlib only when:**
   - `Chunk` — where kyo APIs require it (Signal returns Chunk). Note: Chunk boxes primitives, Span does not.
   - `String` — in the render hot path (Text has lazy ops but allocates Op nodes). Use `String` for cell buffer content.
   - Mutable `Array[T]` — for pre-allocated buffers that are mutated in-place (layout arrays, cell buffer). Span wraps immutable arrays.

**Every phase verify checklist must include these checks (in addition to phase-specific items):**

- [ ] No `throw` without `// unsafe: <reason>` comment
- [ ] No `return` without `// unsafe: <reason>` comment
- [ ] No `while` without `// unsafe: <reason>` comment (exception: inside `inline def`)
- [ ] No `.asInstanceOf` without `// unsafe: <reason>` comment
- [ ] No `null` without `// unsafe: <reason>` comment
- [ ] No dead code (commented-out code, unused imports/methods)
- [ ] No unnecessary allocations in render-path code
- [ ] Allocation review: no `Tuple2`/`Tuple3` in stored data (use parallel arrays/spans), no boxed primitives in `Span` (use `Span[Float]`/`Span[Int]` not `Span[(A, B)]`), no `Option` where `Maybe` works, no `Array`/`Seq`/`List` where `Span` works
- [ ] Compiles
- [ ] REDESIGN.md is consistent with implementation — update any signatures, type definitions, or descriptions in this file that were changed during implementation
- [ ] Test coverage: all new/changed public API has tests. Every new prop, builder method, enum, extraction method, and factory method must be covered. Tests must pass before returning the phase.

### Phase 1: Style.scala — new props

Add new Style props to the shared Style type. No backend changes yet.

**Scope:** Style.scala only.

**Work:**
- Add `PositionProp(position: Position)` + `Position` enum (flow, overlay)
- Add `FlexGrowProp(value: Float)` + convenience `Style.grow`
- Add `FlexShrinkProp(value: Float)` + convenience `Style.noShrink`
- Add `DisabledProp(style: Style)`
- Add 8 filter props (BrightnessProp, ContrastProp, GrayscaleProp, SepiaProp, InvertProp, SaturateProp, HueRotateProp, BlurProp)
- Add `BgGradientProp(direction: GradientDirection, colors: Span[String], positions: Span[Float])` — two parallel spans avoid Tuple2 boxing

**Verify before returning:**
- [ ] All new props have `Style.xxx(...)` builder methods
- [ ] HoverProp, FocusProp, ActiveProp already exist — confirm no duplicates
- [ ] Compiles (shared module)
- [ ] No backend changes — existing tests still pass
- [ ] Test coverage for all new props, enums, builder methods, convenience methods, extraction methods, and companion factory methods

### Phase 2: UI.scala — AST collapse

Replace 34 element case classes with `Element(tag, common, children)`.

**Scope:** UI.scala only (shared).

**Work:**
- Create `Tag` enum with behavior flags (Focusable, Inline, HasDisabled, TextInput, Activatable, Clickable)
- Create `CommonAttrs` case class with all typed fields (see design doc)
- Create `Element` case class with `tag: Tag`, `common: CommonAttrs`, `children: Span[UI]`
- Define all builder methods on Element once (onClick, style, value, disabled, href, etc.)
- Collapse `ReactiveText` → `Reactive(signal.map(Text(_)))`, `ReactiveNode` → `Reactive`
- Collapse `ForeachIndexed` + `ForeachKeyed` → `Foreach`
- Define factory vals/defs: `val div`, `val button`, `def img(src, alt)`, etc.
- Define implicit conversions (String → Text, Signal[String] → Reactive, etc.)
- Define collection extensions (foreach, foreachIndexed, foreachKeyed, foreachKeyedIndexed)
- Remove the 34 old case classes
- Remove escape hatch fields: `styles`, `classes`, `dynamicClassName`, `handlers`, `attrs`

**Verify before returning:**
- [ ] All 34 element types are represented in `Tag` enum
- [ ] Tag flags match design: Button = Focusable|Inline|HasDisabled|Activatable|Clickable, etc.
- [ ] `inline val` for flag bits, `inline def` for flag checks
- [ ] CommonAttrs uses `Maybe` not `Option` for optional fields
- [ ] DSL surface unchanged: `button("Click").onClick(...)`, `div.style(Style.row)(...)`, etc.
- [ ] No escape hatch fields remain
- [ ] `UIDsl` object updated if needed
- [ ] Compiles (shared module — backends will break, that's expected)

### Phase 3: Backend updates for new AST

Update all backends to work with the new 5-case UI AST.

**Scope:** TuiFlatten, TuiStyle, TuiFocus, TuiPainter, DomBackend, CssStyleRenderer, TuiBackend.

**Work:**
- TuiFlatten: 5-case match on UI. `tag.ordinal` for nodeType. `common.disabled` replaces 4-way match. `if Tag.isTextInput` for value/placeholder. `children.foreach` for tree walk.
- TuiStyle.resolve: handle new props (FlexDirectionProp, WrapTextProp fix MatchError bugs). Existing prop resolution unchanged.
- TuiFocus: flag-driven dispatch (`Tag.isClickable`, `Tag.isActivatable`, `Tag.isTextInput`). Access `common.value`, `common.onClick` directly.
- TuiPainter: no element type matching — reads from layout arrays only.
- DomBackend: backend-internal Tag → HTML name mapping. Eliminate `tagName` and `applySpecific`. All fields handled uniformly via `applyCommon`.
- CssStyleRenderer: add cases for new Style props from Phase 1.
- TuiBackend: update any references to old AST types.

**Verify before returning:**
- [ ] All 8 original dispatch points eliminated (see design doc table)
- [ ] TuiFlatten: no class-type matching, only `Tag` flag checks
- [ ] TuiFocus: flag-driven — uses `Tag.isClickable`/`isActivatable`/`isTextInput` instead of class-type matching. Same behavior (Button/Anchor only for onClick).
- [ ] DomBackend: no `applySpecific`, no `tagName` match
- [ ] FlexDirectionProp and WrapTextProp no longer throw MatchError in TuiStyle
- [ ] Compiles (all modules)
- [ ] Demo app renders same output as before (TuiDumpFrame)

### Phase 4: Handler-driven dispatch + event bubbling

Make `div.onClick(...)` work. Full hit testing for mouse events.

**Scope:** TuiFocus.

**Work:**
- Hit test ALL elements in reverse array order (deeper/later = on top), not just focusable indices
- Event bubbling: walk parent chain to find nearest handler
- Mouse click: hitTest → findClickHandler → fire onClick
- Fire onFocus/onBlur callbacks on focus transitions
- Fire onInput callback after Signal mutation in handleTextInput
- Fire onSubmit by walking parent chain to find Form tag
- Left-click on any element with onClick → fire it (not just Button/Anchor)

**Verify before returning:**
- [ ] hitTest scans all nodes in reverse order (layout.count-1 downto 0)
- [ ] findClickHandler walks parent chain via `layout.parent(i)`
- [ ] Non-focusable elements with onClick respond to mouse clicks
- [ ] Focus change fires onBlur on old element, onFocus on new element
- [ ] onInput fires after SignalRef mutation
- [ ] onSubmit walks parent chain looking for Tag.Form
- [ ] Compiles
- [ ] Existing focus/click behavior preserved for Button/Anchor

### Phase 5: Text cursor

Add cursor position tracking and native terminal cursor display.

**Scope:** TuiFocus, TuiTerminal, TuiBackend.

**Work:**
- Add `cursorPos: Int` to TuiFocus (-1 = end of text)
- Arrow keys move cursor: Left/Right, Home/End
- Insert character at cursorPos, delete at cursorPos (Backspace/Delete)
- TuiTerminal: add `showCursor(x, y, shape)` and `hideCursor()` methods
- TuiBackend: after flush, position terminal cursor at insertion point if focused on text input, else hide cursor

**Verify before returning:**
- [ ] cursorPos clamps to [0, text.length]
- [ ] Backspace deletes char at cursorPos-1, Delete deletes char at cursorPos
- [ ] Character insert at cursorPos, not just append
- [ ] Cursor shape: blinking bar (`\e[5 q`) for text input
- [ ] Cursor shown/hidden after each flush
- [ ] Compiles
- [ ] Text editing still works (append behavior preserved, now with in-place editing)

### Phase 6: CommonAttrs TUI features

Implement promoted CommonAttrs fields that need new TUI behavior.

**Scope:** TuiFlatten, TuiFocus, TuiLayout (colspan/rowspan layout changes).

**Work:**
- inputType: `"password"` → mask value with `•` characters in TuiFlatten. `"checkbox"` → render `[x]`/`[ ]` prefix.
- checked: toggle on Enter/Space in TuiFocus for checkbox inputs
- href: on Anchor activation, run `open`/`xdg-open` with href value
- forId: clicking a Label with forId moves focus to element with matching identifier
- tabIndex: positive → include at that position, negative → exclude, zero → default
- colspan/rowspan: table cell spanning in TuiLayout measure + arrange
- selected: visual indicator on selected Option within Select in TuiFlatten
- onChange: fire after Select value changes in TuiFocus
- src: try terminal image protocol (iTerm2/Kitty) in TuiFlatten, fall back to alt text
- alt: displayed as text when src can't render

**Verify before returning:**
- [ ] Password input shows `•••` not plaintext
- [ ] Checkbox renders `[x]`/`[ ]` and toggles on activation
- [ ] Anchor activation opens system browser
- [ ] Label click focuses associated input
- [ ] tabIndex ordering: positive values sorted, then default-order elements, negative excluded
- [ ] colspan/rowspan: merged table cells span correctly in layout
- [ ] selected: visual indicator on selected Option
- [ ] onChange: fires on Select value change
- [ ] src/alt: image falls back to alt text when protocol unsupported
- [ ] Compiles
- [ ] Existing form behavior preserved

### Phase 7: Consolidate style pipeline

Single style owner. TuiFocus no longer touches layout arrays.

**Scope:** TuiStyle, TuiPainter, TuiFocus, TuiBackend.

**Work:**
- TuiLayout: add `hoverStyle` and `disabledStyle` arrays (alongside existing `focusStyle`/`activeStyle`)
- Move `inheritStyles` from TuiPainter → `TuiStyle.inherit`
- Move `applyFocusStyle` from TuiFocus → `TuiStyle.applyStates`
- `TuiStyle.applyStates(layout, focusedIdx, hoverIdx, activeIdx)` handles all four state overlays
- TuiFocus: track hoverIdx (hitTest on mouse move), activeIdx (set on mouse down, clear on release)
- TuiFocus no longer writes to layout arrays
- TuiPainter: remove inheritStyles call
- TuiBackend: update pipeline order to `flatten → layout → focus.scan → style.inherit → style.applyStates → paint → flush`
- Add DisabledProp to TuiStyle.resolve
- DOM: map hover/active/disabled to CSS pseudo-classes in CssStyleRenderer

**Verify before returning:**
- [ ] TuiStyle has exactly 3 public methods: resolve, inherit, applyStates
- [ ] TuiFocus has zero writes to layout arrays
- [ ] TuiPainter has zero style modification logic
- [ ] Pipeline order in TuiBackend matches design: flatten → layout → focus.scan → style.inherit → style.applyStates → paint → flush
- [ ] Focus visual (blue border) still works via applyStates
- [ ] Hover: mouse move updates hoverIdx, triggers re-render, hover style visible
- [ ] Active: mouse down sets activeIdx, mouse up clears it
- [ ] Disabled: elements with disabled=true get DisabledProp overlay
- [ ] Compiles

### Phase 8: DrawSurface + TuiLayout split

Higher-level drawing API. Split TuiLayout into 3 modules.

**Scope:** TuiLayout, TuiFlexLayout (new), TuiText (new), TuiRenderer, TuiPainter.

**Work:**
- Extract `DrawSurface` trait with `fillRect`, `drawBorder`, `drawText`, `applyFilter`, `pushClip`/`popClip`
- TuiRenderer implements DrawSurface
- Move border character selection, hline/vline drawing, text wrapping/clipping into TuiRenderer
- TuiText: `inline def forEachLine(text, maxWidth)(inline f: (Int, Int) => Unit): Int` (CPS, technique #10)
- TuiText: `inline def lineCount(text, maxWidth): Int`
- TuiText: `clipText` — clip + ellipsis via CPS or char-by-char in DrawSurface
- TuiFlexLayout: measure + arrange extracted from TuiLayout — pure arithmetic on arrays
- TuiLayout: data only — arrays, alloc, reset, grow, linkChild, constants, flag accessors
- TuiPainter: ~50-line tree walk calling DrawSurface methods
- drawText: text transforms char-by-char via `applyTransform(ch: Char, transform: Int): Char` — no String allocation
- Wide chars: flat `Array[String]` replaces `HashMap<Int, String>`

**Verify before returning:**
- [ ] TuiLayout has no measure/arrange logic — data and constants only
- [ ] TuiFlexLayout reads/writes TuiLayout arrays — no other dependencies
- [ ] TuiText.forEachLine is `inline def` with `inline` callback (technique #10)
- [ ] TuiText.lineCount returns Int with no allocations
- [ ] No `text.split("\n", -1)` anywhere — replaced by char scanning
- [ ] No `line.substring(pos, brk)` anywhere — CPS callback gets (start, end) Ints
- [ ] No `toUpperCase`/`toLowerCase` String allocation — char-by-char transform
- [ ] Wide chars use flat Array[String], no HashMap, no Int boxing
- [ ] TuiPainter is ≤60 lines, tree walk only
- [ ] DrawSurface methods take only primitives (Int, Boolean, String, Long)
- [ ] Compiles
- [ ] Visual output unchanged (TuiDumpFrame comparison)

### Phase 9: New Style prop resolution + filters

Wire new Style props into TuiStyle and TuiRenderer.

**Scope:** TuiStyle, TuiLayout (new arrays), TuiFlexLayout, TuiRenderer, TuiColor.

**Work:**
- TuiLayout: add `flexGrow`, `flexShrink`, `filters` arrays. Add `position` bit to lFlags.
- TuiStyle.resolve: handle PositionProp, FlexGrowProp, FlexShrinkProp, filter props, BgGradientProp, ShadowProp (store full params)
- TuiFlexLayout.arrange: distribute remaining space proportionally via flexGrow, shrink via flexShrink
- TuiRenderer: implement `applyFilter` — post-processing pass on cell buffer (brightness, contrast, grayscale, sepia, invert, saturate, hueRotate, blur)
- TuiRenderer: implement gradient fill in `fillRect` variant
- TuiColor: add HSL conversion, brightness/contrast/grayscale math
- Wire lineH/letSp consumption in TuiRenderer.drawText (or remove dead arrays)

**Verify before returning:**
- [ ] FlexGrow distributes remaining space proportionally among children
- [ ] FlexShrink shrinks children proportionally when total exceeds container
- [ ] All 8 filter types transform cell colors correctly
- [ ] Filters apply as post-processing — after paint, before flush
- [ ] Gradient interpolates between color stops across character cells
- [ ] ShadowProp stores x/y/blur/spread/color (not just color)
- [ ] lineH and letSp either work or arrays are removed
- [ ] Colors packed as Int (RGB in 24 bits), HSL is in-place math
- [ ] Compiles

### Phase 10: Overlays

Position-based layering with two-pass paint.

**Scope:** TuiPainter, TuiFocus, TuiFlexLayout.

**Work:**
- TuiFlexLayout: skip overlay elements (position=overlay) during flow arrange. Overlay elements get full terminal bounds.
- TuiPainter: two-pass paint — pass 1: flow (position=flow), pass 2: overlays in tree order
- TuiFocus: hit test overlays first (reverse order), then flow elements
- Focus trapping: when overlay is active, Tab/Shift-Tab only cycles within overlay's descendants. Use parent chain for `isDescendant` check.

**Verify before returning:**
- [ ] Overlay elements paint on top of flow elements
- [ ] Overlay elements positioned relative to terminal (full bounds)
- [ ] Hit testing checks overlays first
- [ ] Tab cycles only within topmost overlay when one is active
- [ ] Tree order within overlays determines stacking (later = on top)
- [ ] Compiles
- [ ] Flow layout unchanged when no overlays present

### Phase 11: JavaFX WebView

Replace native JavaFX rendering with WebView wrapper.

**Scope:** JavaFxBackend (replace), FxCssStyleRenderer (delete).

**Work:**
- Replace JavaFxBackend (1,468 lines) with WebView wrapper (~100 lines) that reuses DomBackend + CssStyleRenderer
- Delete FxCssStyleRenderer.scala

**Verify before returning:**
- [ ] JavaFxBackend replaced with WebView wrapper
- [ ] FxCssStyleRenderer.scala deleted
- [ ] WebView renders UI using DomBackend + CssStyleRenderer
- [ ] Compiles

### Phase 12: Scrollable containers

Scroll offset arrays, scroll event handling, scroll indicators.

**Scope:** TuiLayout, TuiFlexLayout, TuiFocus, TuiRenderer.

**Work:**
- TuiLayout: add `scrollX`, `scrollY` arrays
- TuiFlexLayout.arrange: offset children by `-scrollX/-scrollY` within scrollable containers (overflow=scroll/auto)
- TuiFocus: on ScrollUp/ScrollDown, find container under mouse, adjust scroll offset
- TuiRenderer: render scroll indicator (`▲ █ ▼`) on right edge of scrollable containers
- Overflow clipping already works via DrawSurface.pushClip/popClip

**Verify before returning:**
- [ ] Scroll events adjust scrollY on the containing element
- [ ] Children offset by scroll position during arrange
- [ ] Overflow clipping hides out-of-bounds content
- [ ] Scroll indicator visible when content overflows
- [ ] Compiles

### Phase 13: Paste support

Handle bracketed paste events in text inputs.

**Scope:** TuiFocus.

**Work:**
- Dispatch `Paste(text)` to focused text input
- Insert full pasted text at cursor position
- Update cursorPos to end of pasted text

**Verify before returning:**
- [ ] Paste inserts text at cursorPos, not just at end
- [ ] cursorPos advances by pasted text length
- [ ] Works in both Input and Textarea
- [ ] Compiles

### Phase 14: Allocation cleanup

Eliminate remaining avoidable allocations in the pipeline.

**Scope:** TuiBackend, TuiRenderer.

**Work:**
- TuiBackend: replace `Seq.tabulate(sigs.size)(...)` with manual while loop or Span.tabulate
- TuiBackend: replace `remainder ++ bytes` Chunk concat with ring buffer or index tracking
- Verify all optimization techniques (1-11) are applied throughout the codebase

**Verify before returning:**
- [ ] No `Seq.tabulate` in hot path
- [ ] No Chunk concat in input buffering
- [ ] No tuple destructuring in hot path (technique #11)
- [ ] No `HashMap<Int, ...>` with boxed keys (technique confirmed in Phase 8)
- [ ] Scan for any remaining `split`, `substring`, `toUpperCase` in render path
- [ ] Compiles
