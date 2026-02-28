# TUI Backend — Design

A terminal UI backend for kyo-ui that renders the `UI` AST to a character-cell grid using ANSI escape codes. Full alternate-screen TUI (like vim/htop) with complete Style support. No external libraries — only kyo + Java stdlib. JVM-only.

---

## Architecture

### TuiRenderer — double-buffered framebuffer

`TuiRenderer` is the single class that owns the cell grid, diffs frames, and emits ANSI. It holds two flat framebuffers (current and previous) and exposes an API for the painter and main loop.

```scala
class TuiRenderer(var width: Int, var height: Int):
    // Internal: current + previous buffers (each has chars + style + wideSyms arrays)

    // Painter API
    def set(x: Int, y: Int, ch: Char, style: Long): Unit
    def setWide(x: Int, y: Int, sym: String, style: Long): Unit
    def fillBg(x: Int, y: Int, w: Int, h: Int, color: Int): Unit  // packed 24-bit RGB

    // Main loop API
    def flush(out: FileOutputStream): Unit  // diff → ANSI → write to tty → swap
    def resize(w: Int, h: Int): Unit
```

Each buffer is a pair of flat parallel arrays:
```
Buffer:
  chars[N]    — Char per cell (covers ASCII + BMP, 2 bytes each)
  style[N]    — Long array, bit-packed: fg color + bg color + attrs (see layout below)
  wideSyms    — HashMap[Int, String] (sparse, only for multi-codepoint graphemes)

  N = width × height, index = y × width + x
```

**Double-buffer swap**: the painter writes into `current`. `flush()` walks both buffers cell-by-cell, emits ANSI only where `current` differs from `previous`, then swaps the references (`tmp = previous; previous = current; current = tmp`). The painter always does a full repaint, so the old `previous` (now `current`) is naturally overwritten — no clearing step needed.

Both buffers are allocated once on startup. `resize()` reallocates only when dimensions change.

**Bit layout of `style[N]` (Long, 64 bits per cell):**
```
  63    39 38    14 13   8 7      0
  ┌──────┬──────┬──────┬──────────┐
  │  fg  │  bg  │ attrs│ reserved │
  │25 bit│25 bit│ 6 bit│  8 bit   │
  └──────┴──────┴──────┴──────────┘

  Color (25 bits each):
    bit 24 = present (1=has color, 0=terminal default)
    bits 23–0 = packed RGB (r<<16 | g<<8 | b)

  Attrs (6 bits): bold|dim|italic|underline|strikethrough|skip
  Reserved (8 bits): future use (underline style, etc.)
```

This packs fg, bg, and text attributes into a single array — during flush, each cell is one `Long` compare + read instead of three separate array lookups. Extraction uses inline shift/mask helpers (3-4 CPU cycles, pure ALU, no branches, no allocations).

The framebuffer is struct-of-arrays, not array-of-structs. This gives better cache locality during the diff scan and avoids per-cell object allocation.

**Memory layout** (200×50 = 10,000 cells, ×2 buffers):
- `chars` ×2: 40KB (primitive `Array[Char]`, 2 bytes each — no object per cell)
- `style` ×2: 160KB (primitive `Array[Long]`, 8 bytes each)
- `wideSyms` ×2: near-zero (only emoji / CJK grapheme clusters allocate String entries)
- **Total: ~200KB** (4 arrays + 2 HashMaps), allocated once for session lifetime

99%+ of terminal cells are single BMP characters (ASCII, box-drawing, block elements). The `chars` array handles these with zero allocation. The `wideSyms` HashMap is only consulted when `chars[i] == '\u0000'` (sentinel for "look up in wideSyms"), which is rare.

**Wide character handling** (from Ratatui):
- Single BMP chars go into `chars[i]` directly
- Multi-codepoint grapheme clusters (ZWJ emoji, flag sequences) go into `wideSyms(i)`
- Wide characters (CJK, some emoji) set the symbol in cell (x, y) and mark cell (x+1, y) with the `skip` bit
- During flush, cells with `skip` set are not emitted
- Overwriting cell (x, y) that was the first cell of a wide char also clears cell (x+1, y)

**Unicode width measurement**:
- Use `java.text.BreakIterator` or equivalent for grapheme cluster segmentation
- Characters with East Asian Width property W or F occupy 2 cells
- Emoji width: stick to Unicode 9 emoji for cross-terminal consistency (Textual's recommendation); avoid multi-codepoint sequences (ZWJ, flag sequences)

**AnsiBuffer — zero-allocation ANSI output**:

`TuiRenderer` uses a pre-allocated byte buffer for ANSI output, avoiding all String allocation and encoding overhead in the flush path:

```scala
private[kyo] final class AnsiBuffer(initialCapacity: Int = 65536):
    private var buf = new Array[Byte](initialCapacity)
    private var pos = 0

    def reset(): Unit = pos = 0

    inline def put(b: Byte): Unit = { if pos == buf.length then grow(); buf(pos) = b; pos += 1 }
    inline def putAscii(s: String): Unit = { var i = 0; while i < s.length do put(s.charAt(i).toByte); i += 1 }
    def putInt(n: Int): Unit = if n < 10 then put((n + '0').toByte) else { putInt(n / 10); put((n % 10 + '0').toByte) }
    def putChar(ch: Char): Unit =  // inline UTF-8 encoding
        if ch < 0x80 then put(ch.toByte)
        else if ch < 0x800 then { put((0xc0 | (ch >> 6)).toByte); put((0x80 | (ch & 0x3f)).toByte) }
        else { put((0xe0 | (ch >> 12)).toByte); put((0x80 | ((ch >> 6) & 0x3f)).toByte); put((0x80 | (ch & 0x3f)).toByte) }

    def writeTo(out: FileOutputStream): Unit = out.write(buf, 0, pos)  // single write, zero copy

    // ANSI helpers — no String allocation
    def csi(): Unit = { put(0x1b); put('[') }
    def sgr(code: Int): Unit = { csi(); putInt(code); put('m') }
    def moveTo(row: Int, col: Int): Unit = { csi(); putInt(row); put(';'); putInt(col); put('H') }
    def fgRgb(r: Int, g: Int, b: Int): Unit = { csi(); putAscii("38;2;"); putInt(r); put(';'); putInt(g); put(';'); putInt(b); put('m') }
    def bgRgb(r: Int, g: Int, b: Int): Unit = { csi(); putAscii("48;2;"); putInt(r); put(';'); putInt(g); put(';'); putInt(b); put('m') }
```

- `buf` allocated once, grows only if a frame exceeds capacity (rare, amortized)
- `reset()` sets `pos = 0` — no clearing, previous bytes overwritten
- `writeTo` writes directly from `buf` to tty — no copy, no intermediate `byte[]`
- No `String` objects for ANSI sequences — everything is byte-level
- `putInt` does digit extraction via recursion on the stack (no `Integer.toString`)
- `putAscii` for fixed SGR fragments like `"38;2;"` — JIT inlines as constant byte sequences

**Box-drawing character merging** (from Ratatui's `merge_symbol()`):
- When writing a border character to a cell that already contains one, merge them using a lookup table
- Each box-drawing character encodes which directions it connects (up/down/left/right)
- Merge = union of directions → lookup result character
- Example: `─` + `│` → `┼`, `┌` + `─` from right → `┬`

### Pipeline

```
Two fibers:
  Input fiber:  blocking read on tty → bytes into Channel (dedicated thread, never starves)
  Blink fiber:  toggles cursorBlink SignalRef every ~530ms (for text cursor visibility)
  Main fiber:   render loop (see below)

Reactive loop (main fiber in TuiBackend):
  1. Drain input:   take all available bytes from input Channel (non-blocking drain)
  2. Parse + dispatch: bytes → InputEvents (key + mouse) → update focus/scroll/form state/element handlers
  3. Flatten:        walk UI AST, resolve signals inline, resolve styles → TuiLayout flat arrays
  4. Measure:        bottom-up intrinsic sizing (flat array arithmetic)
  5. Arrange:        top-down position assignment (flat array arithmetic)
  6. Paint:          walk TuiLayout arrays, write cells into TuiRenderer
  7. Flush:          TuiRenderer.flush() → diff current vs previous → ANSI → swap
  8. Wait:           race(inputChannel.take, signals.awaitAny) — zero CPU while idle
  9. Loop:           go to 1
```

Steps 3-6 (flatten through paint) are the pure core — all operate on pre-allocated flat arrays with zero object allocation in steady state. The painter does a full repaint every frame, but `flush()` diffs current vs previous buffer and only emits changed cells. At 200×50 = 10,000 cells, the full pipeline (flatten + measure + arrange + paint + flush) runs in <0.5ms.

### TuiLayout — flat array table

All layout, style, and tree structure data lives in pre-allocated parallel arrays inside `TuiLayout`. No LayoutBox, LayoutStyle, or PaintStyle objects. Each UI node gets an integer index; tree structure is encoded via index references.

```scala
private[kyo] final class TuiLayout(initialCapacity: Int = 256):

    var count: Int = 0  // nodes used this frame

    // ---- tree structure ----
    val parent      = new Array[Int](cap)   // parent index, -1 = root
    val firstChild  = new Array[Int](cap)   // -1 = leaf
    val nextSibling = new Array[Int](cap)   // -1 = last
    val lastChild   = new Array[Int](cap)   // for O(1) child append
    val nodeType    = new Array[Byte](cap)  // element type tag

    // ---- geometry (written by measure + arrange) ----
    val x    = new Array[Int](cap)
    val y    = new Array[Int](cap)
    val w    = new Array[Int](cap)
    val h    = new Array[Int](cap)
    val intrW = new Array[Int](cap)  // intrinsic width from measure
    val intrH = new Array[Int](cap)  // intrinsic height from measure

    // ---- layout style (flat, no LayoutStyle objects) ----
    val lFlags = new Array[Int](cap)  // packed: direction|align|justify|overflow|hidden|borders
    val padT  = new Array[Int](cap);  val padR = new Array[Int](cap)
    val padB  = new Array[Int](cap);  val padL = new Array[Int](cap)
    val marT  = new Array[Int](cap);  val marR = new Array[Int](cap)
    val marB  = new Array[Int](cap);  val marL = new Array[Int](cap)
    val gap   = new Array[Int](cap)
    val sizeW = new Array[Int](cap);  val sizeH = new Array[Int](cap)  // -1 = auto
    val minW  = new Array[Int](cap);  val maxW  = new Array[Int](cap)  // -1 = none
    val minH  = new Array[Int](cap);  val maxH  = new Array[Int](cap)
    val transX = new Array[Int](cap); val transY = new Array[Int](cap)

    // ---- paint style (flat, no PaintStyle objects) ----
    val pFlags = new Array[Int](cap)   // packed: bold|dim|italic|underline|strikethrough|borderStyle|...
    val fg     = new Array[Int](cap)   // -1 = absent
    val bg     = new Array[Int](cap)   // -1 = absent
    val bdrClrT = new Array[Int](cap)  // -1 = absent (per-side border colors)
    val bdrClrR = new Array[Int](cap)
    val bdrClrB = new Array[Int](cap)
    val bdrClrL = new Array[Int](cap)
    val opac   = new Array[Float](cap) // 1.0 = opaque
    val lineH  = new Array[Int](cap)
    val letSp  = new Array[Int](cap)
    val fontSz = new Array[Int](cap)
    val shadow = new Array[Int](cap)   // -1 = none

    // ---- content (refs into UI AST — no copy) ----
    val text        = new Array[String](cap) // null = no text
    val focusStyle  = new Array[Style](cap)  // null = none (ref to UI AST Style)
    val activeStyle = new Array[Style](cap)  // null = none
    val element     = new Array[Any](cap)    // null = text/fragment; original UI AST Element ref for handler dispatch

    def reset(): Unit = count = 0  // O(1) — slots overwritten before read

    def alloc(): Int =
        if count == parent.length then grow()
        val idx = count; count += 1; idx

    private def grow(): Unit = // double all arrays (amortized O(1), rare)
```

`reset()` is O(1) — just sets count to 0. Every slot is written before it's read during flatten, so no clearing is needed. `grow()` only triggers if the UI tree exceeds capacity (doubles all arrays, amortized O(1)).

**Layout style `lFlags` bit layout (Int, 32 bits):**
```
  bit 0     = direction (0=column, 1=row)
  bits 1–2  = alignItems (0=start, 1=center, 2=end, 3=stretch)
  bits 3–5  = justifyContent (0=start, 1=center, 2=end, 3=between, 4=around, 5=evenly)
  bits 6–7  = overflow (0=visible, 1=hidden, 2=scroll)
  bit 8     = hidden
  bit 9     = borderTop present
  bit 10    = borderRight present
  bit 11    = borderBottom present
  bit 12    = borderLeft present
  bit 13    = disabled
  bits 14–31 = reserved
```

**Paint style `pFlags` bit layout (Int, 32 bits):**
```
  bit 0     = bold
  bit 1     = dim
  bit 2     = italic
  bit 3     = underline
  bit 4     = strikethrough
  bits 5–8  = borderStyle (0=none, 1=solid, 2=dashed, 3=dotted + TUI-internal: 4=rounded, 5=heavy, 6=double, 7=block, 8=outerHalf, 9=innerHalf)
  bit 9     = roundedTL (top-left corner rounded)
  bit 10    = roundedTR
  bit 11    = roundedBR
  bit 12    = roundedBL
  bits 13–14 = textAlign (0=left, 1=center, 2=right, 3=justify)
  bits 15–16 = textDecoration (0=none, 1=underline, 2=strikethrough)  // matches Style.TextDecoration enum
  bits 17–18 = textTransform (0=none, 1=uppercase, 2=lowercase, 3=capitalize)
  bit 19    = textOverflow (0=clip, 1=ellipsis)
  bit 20    = wrapText
  bits 21–31 = reserved
```

All colors use raw `Int` with `-1` sentinel — no `Maybe[Int]` boxing. Size dimensions use `-1` for auto/none. `text[i]` points directly into the UI AST's `Text.value` — no copy. `focusStyle[i]` / `activeStyle[i]` point to the original `Style` from `FocusProp`/`ActiveProp` — resolved lazily only when the element has focus.

### Flatten — walk UI AST, resolve signals, write flat arrays

The flatten phase replaces both snapshot and layout-tree-building. It walks the UI AST, resolves signals inline (no intermediate snapshot tree), resolves styles inline, and writes everything into the `TuiLayout` flat arrays.

```scala
private[kyo] object TuiFlatten:
    def flatten(ui: UI, layout: TuiLayout, signals: SignalCollector, parentW: Int, parentH: Int): Unit =
        layout.reset()
        signals.reset()
        flattenNode(ui, layout, signals, -1, parentW, parentH)

    private def flattenNode(ui: UI, layout: TuiLayout, signals: SignalCollector,
                            parentIdx: Int, parentW: Int, parentH: Int): Unit =
        ui match
            case Text(value) =>
                val idx = layout.alloc()
                linkChild(layout, parentIdx, idx)
                TuiStyle.setDefaults(layout, idx)  // must initialize all arrays even for plain text
                layout.text(idx) = value

            case elem: Element =>
                val idx = layout.alloc()
                linkChild(layout, parentIdx, idx)
                resolveCommonAttrs(elem.common, layout, signals, idx)
                TuiStyle.resolve(elem.common.uiStyle, layout, idx, parentW, parentH)
                elem.children.foreach(child => flattenNode(child, layout, signals, idx, parentW, parentH))

            case ReactiveText(signal) =>
                signals.add(signal)
                val idx = layout.alloc()
                linkChild(layout, parentIdx, idx)
                TuiStyle.setDefaults(layout, idx)
                layout.text(idx) = signal.current

            case ReactiveNode(signal) =>
                signals.add(signal)
                flattenNode(signal.current, layout, signals, parentIdx, parentW, parentH)

            case ForeachIndexed(signal, render) =>
                signals.add(signal)
                signal.current.foreachIndexed((i, item) =>
                    flattenNode(render(i, item), layout, signals, parentIdx, parentW, parentH))

            case ForeachKeyed(signal, key, render) =>
                signals.add(signal)
                signal.current.foreachIndexed((i, item) =>
                    flattenNode(render(i, item), layout, signals, parentIdx, parentW, parentH))

            case Fragment(children) =>
                children.foreach(child => flattenNode(child, layout, signals, parentIdx, parentW, parentH))
```

Child linking is O(1) via `lastChild` tracking:
```scala
    private def linkChild(layout: TuiLayout, parentIdx: Int, childIdx: Int): Unit =
        layout.parent(childIdx) = parentIdx
        layout.firstChild(childIdx) = -1
        layout.nextSibling(childIdx) = -1
        layout.lastChild(childIdx) = -1
        if parentIdx >= 0 then
            if layout.firstChild(parentIdx) == -1 then
                layout.firstChild(parentIdx) = childIdx
            else
                layout.nextSibling(layout.lastChild(parentIdx)) = childIdx
            layout.lastChild(parentIdx) = childIdx
```

Union type fields (`Maybe[Boolean | Signal[Boolean]]`, etc.) are resolved via `isInstanceOf` checks during `resolveCommonAttrs` — same logic as before, just writing into flat arrays instead of building snapshot nodes.

**SignalCollector** — pre-allocated, reused across frames:
```scala
private[kyo] final class SignalCollector(initialCapacity: Int = 64):
    private var signals = new Array[Signal[?]](initialCapacity)
    private var count = 0
    def reset(): Unit = count = 0
    def add(s: Signal[?]): Unit = { if count == signals.length then grow(); signals(count) = s; count += 1 }
    def toChunk: Chunk[Signal[?]] = Chunk.from(signals.view.slice(0, count))
```

### Style resolution — writes directly into TuiLayout arrays

`TuiStyle.resolve` folds over `Style.props` and writes values directly into the `TuiLayout` arrays at the given node index. No intermediate style objects.

```scala
private[kyo] object TuiStyle:
    /** Initialize all style arrays to defaults. Called for every node (including plain Text). */
    def setDefaults(layout: TuiLayout, idx: Int): Unit =
        layout.lFlags(idx) = 0; layout.pFlags(idx) = 0
        layout.padT(idx) = 0; layout.padR(idx) = 0; layout.padB(idx) = 0; layout.padL(idx) = 0
        layout.marT(idx) = 0; layout.marR(idx) = 0; layout.marB(idx) = 0; layout.marL(idx) = 0
        layout.gap(idx) = 0; layout.sizeW(idx) = -1; layout.sizeH(idx) = -1
        layout.minW(idx) = -1; layout.maxW(idx) = -1; layout.minH(idx) = -1; layout.maxH(idx) = -1
        layout.transX(idx) = 0; layout.transY(idx) = 0
        layout.fg(idx) = -1; layout.bg(idx) = -1
        layout.bdrClrT(idx) = -1; layout.bdrClrR(idx) = -1; layout.bdrClrB(idx) = -1; layout.bdrClrL(idx) = -1
        layout.opac(idx) = 1.0f; layout.shadow(idx) = -1
        layout.lineH(idx) = 0; layout.letSp(idx) = 0; layout.fontSz(idx) = 1
        layout.focusStyle(idx) = null; layout.activeStyle(idx) = null
        layout.text(idx) = null; layout.element(idx) = null
        layout.nodeType(idx) = 0

    def resolve(style: Style, layout: TuiLayout, idx: Int, parentW: Int, parentH: Int): Unit =
        setDefaults(layout, idx)
        // fold over props — writes directly into arrays
        style.props.foreach:
            case BgColor(color)      => layout.bg(idx) = TuiColor.resolve(color)
            case TextColor(color)    => layout.fg(idx) = TuiColor.resolve(color)
            case Padding(t, r, b, l) => layout.padT(idx) = resolveSize(t, parentW); ...
            case Width(s)            => layout.sizeW(idx) = resolveSize(s, parentW)
            case FocusProp(s)        => layout.focusStyle(idx) = s
            case ActiveProp(s)       => layout.activeStyle(idx) = s
            // ... all 41 Style.Prop variants
```

### Layout engine — measure and arrange

Operates entirely on `TuiLayout` flat arrays. No objects allocated.

**Measure** (bottom-up) — reverse index traversal ensures children are measured before parents:
- `text[i] != null` → intrinsic size = text width × line count
- Container → sum children along main axis, max along cross axis, plus padding + border insets
- Apply explicit `sizeW`/`sizeH`, clamp with `minW`/`maxW`/`minH`/`maxH`

**Arrange** (top-down) — forward index traversal, parent assigns positions to children:
- Default direction: `column` for block elements (Div, P, Section, etc.), `row` for inline (Span, Nav, Li)
- Gap inserts space between children on the main axis
- Padding shrinks the content area, margin offsets position, border adds 1 cell per side
- `Margin(Auto)` on horizontal axis → center element (distribute remaining space equally)
- Justify distributes free space on the main axis:
  - `start` — children packed at start
  - `end` — children packed at end
  - `center` — free space split equally before and after
  - `spaceBetween` — free space divided evenly between children
  - `spaceAround` — equal space around each child (half-space at edges)
  - `spaceEvenly` — equal space between and around all children
- Align positions children on the cross axis: `start` / `center` / `end` / `stretch`
- `Translate` applied as visual offset after positioning (does not affect siblings)

The painter then walks the `TuiLayout` arrays and writes cells directly into the `TuiRenderer`.

### Compositing

All color blending is done in software before writing to the buffer (confirmed by Textual's approach):
- `opacity: 0.5` → `result = source × alpha + destination × (1 - alpha)` for both fg and bg
- `Rgba` alpha → blend bg with parent bg
- Shadow → gradient falloff via alpha blending
- Overlapping elements → painter's algorithm (later children overwrite earlier ones)
- All blended to final RGB values before `TuiRenderer.set()` — the terminal sees only opaque truecolor

### Color resolution

`Color.Hex` stores CSS strings (`"#ffffff"`, `"#fff"`, `"transparent"`). The color resolver parses these to packed 24-bit RGB:
- `Color.Rgb(r, g, b)` → `(r << 16) | (g << 8) | b`
- `Color.Rgba(r, g, b, a)` → pack RGB, alpha handled separately via `OpacityProp`
- `Color.Hex("#fff")` → expand 3-char to 6-char, parse as integer
- `Color.Hex("transparent")` → -1 (absent — same as terminal default; no distinction needed in TUI)

Blending: `blend(src, dst, alpha) = (sr×α + dr×(1-α), sg×α + dg×(1-α), sb×α + db×(1-α))`

---

## Design Philosophy — Functional Shell, Mutable Core

The TUI backend uses pre-allocated mutable buffers for performance, but contains that mutation behind clean boundaries. The outer layer (TuiBackend, fiber wiring, event dispatch) is idiomatic kyo FP. The inner layer (layout arrays, framebuffer, ANSI buffer) uses controlled mutation with arena-reset semantics and single-ownership discipline.

### Layer separation

| Layer | Nature | Components |
|-------|--------|------------|
| **Pure functions** | No mutation, no IO, deterministic | TuiInput.parse, TuiColor.resolve/blend, measure, arrange |
| **Contained mutation** | Writes into pre-allocated buffers, deterministic, no IO | TuiFlatten, TuiStyle, TuiPainter, TuiRenderer.flush |
| **Effectful shell** | IO, signals, channels, fibers — idiomatic kyo | TuiBackend.mainLoop, TuiTerminal, inputLoop |

The effectful shell is thin — just TuiBackend and TuiTerminal. Everything else is either pure or deterministically mutating pre-owned buffers.

### Arena-reset semantics

Mutation in this design is not shared mutable state. It's arena-style allocation — pre-allocated buffers that are logically fresh each frame via `reset()`:

- `TuiLayout` — `reset()` sets `count = 0`, every slot written before read, no aliasing across frames
- `TuiRenderer` — double buffer swapped atomically after flush, painter writes one while flush reads the other
- `AnsiBuffer` — linear write, single `writeTo`, `reset()` sets `pos = 0`
- `SignalCollector` — append-only during flatten, read-only during await, reset before next frame

This gives the logical purity of fresh allocation with the performance of reuse. No component reads stale data from a previous frame.

### Single-ownership discipline

Instead of immutability preventing aliasing bugs, single ownership achieves the same guarantee structurally:

- Only `TuiFlatten` writes into `TuiLayout` arrays
- Only `TuiPainter` writes into `TuiRenderer`
- Only `renderer.flush` reads from `TuiRenderer` and writes to `AnsiBuffer`
- Only `inputLoop` writes to the input Channel; only `mainLoop` reads from it

No two components write to the same buffer concurrently. No buffer is read while being written. Enforced by the sequential main loop, not the type system, but a clear invariant.

### The main loop reads as a pipeline

Despite internal mutation, the loop is a unidirectional pipeline — each step transforms state forward, no step reaches back:

```
Loop.forever:
  bytes    <- inputCh.drain
  events    = TuiInput.parse(bytes, remainder)
  _         = dispatch(events, focusState, scrollState)
  _         = TuiFlatten.flatten(ui, layout, signals, w, h)
  _         = TuiLayout.measure(layout)
  _         = TuiLayout.arrange(layout, w, h)
  _         = TuiPainter.paint(layout, renderer)
  _         = renderer.flush(ttyOut)
  _        <- race(inputCh.take, Signal.awaitAny(signals))
```

### Testing stays pure

Mutable components accept their buffers from outside — tests create fresh buffers, run a function, inspect results. No mocking, no IO:

```scala
val layout = new TuiLayout(64)
val signals = new SignalCollector(16)
TuiFlatten.flatten(someUI, layout, signals, 80, 24)
assert(layout.count == 5)
assert(layout.text(0) == "hello")
```

### Rules

- No global mutable state — every buffer is owned by the `TuiBackend` instance
- No mutation leaking across the API boundary — `UISession` is a `Fiber` + `Signal`, fully kyo-idiomatic
- No callbacks mutating shared state — event handlers return `Unit < Async`, dispatched through kyo's effect system
- No lazy initialization with side effects — all buffers allocated eagerly in `render()`

---

## Terminal Management

### Raw mode and alternate screen

`TuiTerminal` manages the terminal lifecycle. It uses `stty` via `ProcessBuilder` for raw mode and ANSI escape sequences for screen management.

```
Enter:
  1. Save current stty settings via `stty -g < /dev/tty`
  2. Enter raw mode via `stty raw -echo < /dev/tty`
  3. Open /dev/tty for reading (FileInputStream) and writing (FileOutputStream)
  4. Emit: \u001b[?1049h (alternate screen) + \u001b[?25l (hide cursor)
  5. Emit: \u001b[?1003h (any-event mouse tracking) + \u001b[?1006h (SGR extended mouse format) + \u001b[?2004h (bracketed paste)

Exit (via Scope.ensure — calls idempotent cleanup()):
  1. Emit: \u001b[?2004l (disable bracketed paste) + \u001b[?1003l + \u001b[?1006l (disable mouse tracking)
  2. Emit: \u001b[?25h (show cursor) + \u001b[?1049l (exit alternate screen)
  3. Close streams
  4. Restore saved stty settings
```

### Terminal size as reactive signal

Terminal size is stored in a `SignalRef[(Int, Int)]` — width and height. It's updated by SIGWINCH and treated as just another signal in the reactive loop.

```
Initialization:
  1. Query initial size via `stty size < /dev/tty` → (rows, cols) → (width, height)
  2. Create SignalRef[(Int, Int)] with initial size
  3. Create SignalRef[Long] resizeEpoch = 0 (monotonic counter)
  4. Register SIGWINCH handler via kyo's OsSignal.handle("WINCH", callback)
  5. Callback: resizeEpoch.unsafe.set(resizeEpoch.unsafe.get + 1)
     (increment always changes value → fires promise → wakes awaitAny)

Integration:
  - resizeEpoch is included in Signal.awaitAny alongside UI signals
  - SIGWINCH fires → epoch incremented → awaitAny wakes
  - Main loop step 4 queries `stty size` (safe — between frames, no concurrent tty write), updates sizeRef
  - TuiRenderer.resize(newW, newH) detects dimension change, reallocates both buffers
  - Full repaint occurs naturally — no special resize handling beyond step 4
```

The SIGWINCH handler must not call `stty size` — that spawns a process that reads `/dev/tty`, which could interleave with the main fiber's ANSI output. Instead, the handler increments a monotonic counter. The main loop queries size at a safe point between flush and the next render, when no tty writes are in progress.

Note: `SignalRef.set` only fires notifications when the new value differs from the current value (`prev != value` check in `getAndSet`). A simple "poke" like `sizeRef.set(sizeRef.get)` would NOT wake awaitAny. The monotonic counter guarantees a different value each time.

`OsSignal.handle` is kyo's own abstraction over `sun.misc.Signal` — uses reflection, falls back to no-op on unsupported platforms. The `unsafe.set` from the signal handler is safe because `SignalRef.Unsafe` uses lock-free atomic operations. It requires `AllowUnsafe.embrace.danger` in scope, which is appropriate for this low-level integration point.

The `TuiRenderer.resize()` method checks if dimensions changed; if so, it reallocates all arrays in both buffers. The first flush after resize diffs against a blank previous buffer, producing a full redraw.

### Cleanup

`Scope.ensure` runs in LIFO order — last registered runs first. Registration order matters:

```scala
// In TuiBackend.render:
_         <- Scope.ensure(cleanup(term))              // registered 1st → runs LAST (idempotent)
_         <- Scope.ensure(ttyIn.close())             // registered 2nd → runs 3rd (unblock input fiber read)
_         <- Fiber.init(inputLoop(...))              // input fiber (auto-interrupted by Scope)
_         <- Fiber.init(blinkLoop(...))              // blink fiber (auto-interrupted by Scope)
fiber     <- Fiber.init(mainLoop(...))               // main fiber (auto-interrupted by Scope)
```

**Shutdown sequence** (LIFO):
1. `Fiber.init` fibers are interrupted by Scope (main → blink → input, reverse order)
2. `ttyIn.close()` unblocks the input fiber's blocking `read()` (throws IOException, fiber exits)
3. `cleanup(term)` restores terminal (idempotent via AtomicBoolean — safe if already called by signal handler)

The input fiber is blocked in `Async.blocking(ttyIn.read(...))` — `Fiber.interrupt` sets a flag but cannot unblock an OS-level `read()` syscall. Closing the stream from cleanup forces the read to throw, cleanly terminating the fiber. This must happen after fiber interruption (so the fiber's interrupt flag is set) but before terminal restore (so no fiber writes ANSI after we exit alt screen).

JVM shutdown hooks provide defense-in-depth against unclean exits (kill -9 cannot be caught, but normal JVM shutdown triggers hooks).

### ANSI output

**Synchronized Output (Mode 2026)** — each flush is wrapped in:
```
\u001b[?2026h  ← begin batch (terminal holds rendering)
... ANSI output from flush() ...
\u001b[?2026l  ← end batch (terminal applies atomically)
```
Supported by: kitty, WezTerm, Windows Terminal, foot, iTerm2, Alacritty, tmux. Used by: Textual, crossterm (Ratatui), neovim, btop.

**Flicker-free rules** (from Textual):
1. **Overwrite, never clear** — never `\u001b[2J`. Always overwrite every cell.
2. **Synchronized output** as above.
3. **Single write** — `flush()` builds ANSI into a pre-allocated `AnsiBuffer` (reset each frame), then writes directly to the tty `FileOutputStream` in one `write(buf, 0, len)` call — zero copy, zero allocation.

**SGR batching** in flush:
- Track current SGR state (fg, bg, bold, italic, etc.) across cells
- Only emit SGR codes when attributes change from the previous emitted cell
- Use specific disable codes (`\u001b[22m` for unbold) rather than full reset (`\u001b[0m`)
- Cursor positioning: `\u001b[row;colH` to jump over unchanged regions; omit when the next changed cell is adjacent

---

## Signal Resolution (inline during flatten)

There is no separate snapshot phase. Signal resolution happens inline during `TuiFlatten` as the UI AST is walked:

- `ReactiveText(signal)` → `signal.current` → write value into `text[idx]`
- `ReactiveNode(signal)` → `signal.current` → recurse into resolved subtree
- `ForeachIndexed(signal, render)` / `ForeachKeyed(signal, key, render)` → `signal.current` → flatten each item
- Element union-type fields resolved via `isInstanceOf` (erasure requires runtime checks):
  - `hidden: Maybe[Boolean | Signal[Boolean]]` → resolve, write into `lFlags[idx]`
  - `disabled: Maybe[Boolean | Signal[Boolean]]` → resolve, write into node metadata
  - `value: Maybe[String | SignalRef[String]]` → resolve to plain String
  - `checked: Maybe[Boolean | Signal[Boolean]]` → resolve
  - `href: Maybe[String | Signal[String]]` → resolve
  - `classes`, `dynamicClassName`, `attrs` — resolve any Signal values within

Every signal encountered is added to the `SignalCollector`. At the end of each frame, `Signal.awaitAny(signals.toChunk :+ term.size)` blocks until any signal changes, then the loop re-runs.

---

## Reactivity — The Main Loop

Two fibers: an input fiber for blocking tty reads, and a main fiber for the render loop. The input fiber pushes raw byte chunks into a `Channel[Chunk[Byte]]`, ensuring no keystrokes are dropped (unlike Signal, which has latest-value semantics and skips intermediate changes). The main fiber drains the channel, processes input, re-renders, then races between the channel and signal changes to wake up.

```scala
class TuiBackend extends UIBackend:
    def render(ui: UI)(using Frame): UISession < (Async & Scope) =
        for
            rendered  <- Signal.initRef[UI](UI.empty)
            term      <- TuiTerminal.enter             // raw mode, alt screen, SIGWINCH
            _         <- Scope.ensure(cleanup(term))              // registered 1st → runs LAST (idempotent)
            _         <- Scope.ensure(term.ttyIn.close())       // registered 2nd → runs 3rd (unblocks input read)
            (w, h)    <- term.size.get
            inputCh   <- Channel.init[Chunk[Byte]](256) // bounded; mouse events generate high volume
            renderer   = new TuiRenderer(w, h)           // allocated once
            layout     = new TuiLayout(256)               // allocated once
            signals    = new SignalCollector(64)           // allocated once
            focusRef  <- Signal.initRef[Maybe[Any]](Absent)  // focused element by AST identity
            scrolls   <- Signal.initRef[Map[Any, Int]](Map.empty)  // keyed by AST element identity
            cursorBlink <- Signal.initRef[Boolean](true)    // cursor visibility toggle
            _         <- Fiber.init(inputLoop(term.ttyIn, inputCh))  // input fiber (interrupted 3rd, runs last)
            _         <- Fiber.init(blinkLoop(cursorBlink))          // blink fiber (interrupted 2nd)
            fiber     <- Fiber.init(mainLoop(ui, term, inputCh, renderer, layout, signals,
                            focusRef, scrolls, cursorBlink, rendered))  // main fiber (interrupted 1st)
        yield UISession(fiber, rendered)
```

**Input fiber** — dedicated thread, blocking read:
```
inputLoop(ttyIn, channel):
  Loop.forever:
    bytes <- Async.blocking(read from ttyIn)  // blocks on OS read(), zero CPU
    channel.put(bytes)                         // backpressure if channel full
```

**Main loop**:
```
Loop.forever:
  1. Drain inputCh: take all available byte chunks (non-blocking poll until empty)
  2. Parse bytes → InputEvents (KeyEvent + MouseEvent, accumulates remainder for partial sequences)
  3. Dispatch events:
     - If Select overlay open → route to overlay (arrow/enter/escape/click/scroll wheel)
     - Tab/Shift-Tab → update focusRef
     - Mouse click → hit test → focus + onClick bubbling
     - Mouse wheel → hit test → scroll nearest scrollable ancestor (does not move focus)
     - Mouse move → hover tracking (does not move focus)
     - Printable keys → insert into focused Input/Textarea SignalRef
     - Enter on Input inside Form → fire Form.onSubmit (parent walk)
     - Enter/Space on Button → fire onClick
     - Other keys → focused element's onKeyDown/onKeyUp handlers
  4. Check terminal size → renderer.resize() if changed
  5. Flatten: walk UI AST → resolve signals + styles → TuiLayout flat arrays
  6. Measure: bottom-up intrinsic sizing (flat array arithmetic)
  7. Arrange: top-down position assignment (flat array arithmetic)
  8. Paint: walk TuiLayout arrays, write cells into TuiRenderer
  9. Flush: renderer.flush(term.out) → diff → ANSI → write → swap
  10. rendered.set(ui) — notify UISession observers
  11. Async.race(inputCh.take, signalAwaitAny(signals.toChunk :+ term.size :+ cursorBlink))
      — parks fiber, zero CPU while idle
      — wakes on: keystroke, signal change, terminal resize, or cursor blink toggle
```

**Why Channel for input, Signal for the rest**:
- `Signal.next()` returns the current promise — if multiple changes fire before the loop calls `next()` again, intermediate values are dropped. This is correct for UI state (you only care about the latest value) and terminal size (you only care about the current dimensions).
- Keystrokes must not be dropped — every key press matters. `Channel` queues all values and delivers them in order. The bounded capacity (256) provides backpressure if the render loop falls behind.

### Concurrency model

**Three fibers, one tty**:

| Fiber | Thread | Reads | Writes | Blocks on |
|-------|--------|-------|--------|-----------|
| Input | Dedicated (via `Async.blocking`) | ttyIn (FileInputStream) | inputCh Channel | OS `read()` syscall |
| Blink | Kyo scheduler | — | cursorBlink SignalRef | `Async.sleep(530.millis)` |
| Main | Kyo scheduler | inputCh, all SignalRefs | ttyOut (FileOutputStream) | `Async.race(inputCh.take, Signal.awaitAny(...))` |

Reading (input fiber) and writing (main fiber) use separate FileInputStream/FileOutputStream — no conflict. The main fiber is the only writer to the tty output stream, so no synchronization needed for ANSI output.

**`Signal.awaitAny` cost**: internally calls `Async.race(signals.map(s => Async.mask(s.next)))`, which creates N short-lived fibers per wait. With ~50 signals this is 50 fibers per frame. Each fiber is lightweight (just a masked promise await), but this is the main per-frame allocation. Acceptable for TUI frame rates (<60 FPS), but worth noting.

**Input fiber termination**: the input fiber is blocked in `Async.blocking(ttyIn.read(...))` — an OS-level syscall that `Fiber.interrupt` cannot unblock. Cleanup closes the tty FileInputStream, which forces the blocked `read()` to throw IOException, cleanly terminating the fiber. See Cleanup section under Terminal Management.

**Event handler execution**: handlers like `onClick` return `Unit < Async`. They execute inline in the main loop's dispatch step — the main fiber runs the handler before proceeding to flatten. If a handler launches async work (e.g., HTTP request), it should fork a fiber; otherwise it blocks rendering. This matches browser semantics where event handlers are synchronous.

**Form state and re-render**: when the main loop sets a SignalRef (e.g., typing into an Input), the signal's promise fires immediately. The subsequent `Signal.awaitAny` in step 11 would wake instantly for a value we just set. This is harmless — flush diffs to zero changes — but wastes one empty render cycle. Avoided because dispatch (step 2) happens before flatten (step 5): the signal change propagates naturally into the same frame's render.

---

## Input Handling

### Escape sequence parser

`TuiInput.parse(bytes: Chunk[Byte]): (Chunk[InputEvent], Chunk[Byte])` — pure function from raw bytes to input events plus unconsumed remainder (for partial UTF-8 or escape sequences split across reads). Returns `KeyEvent`, `MouseEvent`, and `PasteEvent` in a common `InputEvent` ADT.

**Keyboard sequences**:

| Byte sequence | KeyEvent |
|---|---|
| `\u001b[A/B/C/D` | ArrowUp / ArrowDown / ArrowRight / ArrowLeft |
| `\u001b[H` / `\u001b[1~` | Home |
| `\u001b[F` / `\u001b[4~` | End |
| `\u001b[5~` / `\u001b[6~` | PageUp / PageDown |
| `\u001b[3~` | Delete |
| `\u001b[Z` | Shift+Tab |
| `\u001b[1;2A` | Shift+ArrowUp (modifier in param 2) |
| `\u001b[1;5A` | Ctrl+ArrowUp |
| `0x7f` (127) | Backspace |
| `0x0d` (13) | Enter |
| `0x09` (9) | Tab |
| `0x1b` alone | Escape |
| `0x01`–`0x1a` | Ctrl+A through Ctrl+Z |
| `\u001b` + printable | Alt+key |
| `\u001b[200~`...`\u001b[201~` | PasteEvent(text) — bracketed paste content |
| Printable bytes | Character key (UTF-8 decoded) |

**Mouse sequences** (SGR extended format, enabled via `\u001b[?1003h` + `\u001b[?1006h`):

| Byte sequence | MouseEvent |
|---|---|
| `\u001b[<0;col;rowM` | Left press at (col, row) |
| `\u001b[<0;col;rowm` | Left release at (col, row) |
| `\u001b[<1;col;rowM/m` | Middle press/release |
| `\u001b[<2;col;rowM/m` | Right press/release |
| `\u001b[<32;col;rowM` | Left drag (motion with button held) |
| `\u001b[<35;col;rowM` | Mouse move (no button, requires mode 1003) |
| `\u001b[<64;col;rowM` | Scroll up |
| `\u001b[<65;col;rowM` | Scroll down |

Button field modifiers: `+4` = shift held, `+8` = alt held, `+16` = ctrl held. Coordinates are 1-based (subtract 1 for 0-based layout coordinates).

### Focus management

`FocusState` tracks the focused element by identity — a `SignalRef[Maybe[Any]]` holding a reference to the focused AST Element (from `element[idx]`). Focus targets are extracted from the layout tree after each render (depth-first traversal, collecting Button/Input/Textarea/Select/Anchor into a flat list). The previously focused element is located by identity in the new list to determine its current index — this ensures focus survives tree structure changes (elements inserted/removed). If the previously focused element is no longer in the tree, focus moves to the nearest neighbor or resets to the first focusable element.

- Tab / Shift+Tab cycles the focus index (wrapping)
- Focused element gets its `FocusProp` style applied (or a default: heavy border `┏━┓┃┗━┛`)
- Focused element receives keyboard events via its `onKeyDown`/`onKeyUp` handlers
- Enter/Space on focused Button triggers `onClick`
- Printable keys on focused Input/Textarea update the `SignalRef` value

Focus index is itself a signal, so changing focus triggers a re-render via `Signal.awaitAny`.

### Mouse support

**Terminal mouse tracking** is enabled on enter and disabled on exit:
```
Enter:  \u001b[?1003h (any-event tracking) + \u001b[?1006h (SGR extended coordinates) + \u001b[?2004h (bracketed paste)
Exit:   \u001b[?2004l + \u001b[?1003l + \u001b[?1006l
```

SGR extended format supports coordinates >223 (unlike legacy X10/UTF-8 encodings) and distinguishes press (`M`) from release (`m`).

**Hit testing** — find which UI element is at `(x, y)`:
```
hitTest(x, y, layout):
  result = -1
  for i <- 0 until layout.count do
    if x >= layout.x(i) && x < layout.x(i) + layout.w(i)
       && y >= layout.y(i) && y < layout.y(i) + layout.h(i)
       && !isHidden(i) then
      result = i  // later (deeper) nodes overwrite — deepest match wins
  result
```

Linear scan of flat arrays. At ~100 nodes this is ~100 Int comparisons — negligible. The `element[idx]` array holds a reference to the original UI AST `Element`, giving direct access to its handlers.

**Event dispatch with bubbling** — walk up `parent` chain until a handler is found:
```
dispatchClick(x, y, layout):
  idx = hitTest(x, y, layout)
  while idx != -1 do
    layout.element(idx) match
      case elem: Element if elem.common.onClick.nonEmpty =>
        run(elem.common.onClick.get)
        return
      case _ =>
    idx = layout.parent(idx)
```

Same pattern for all handlers. This matches HTML event bubbling — clicking a Span inside a Button triggers the Button's `onClick`.

**Mouse event → handler mapping**:

| Mouse event | Action |
|---|---|
| Left click | `hitTest` → bubble up for `onClick` handler. Also moves focus to the clicked element (or nearest focusable ancestor). |
| Right click | Could map to context menu; currently no UI AST support. Ignored. |
| Scroll wheel up/down | `hitTest` → walk up `parent` chain for nearest `overflow: scroll` container → adjust its scroll offset. Does not move focus. If a Select overlay is open, scrolls the overlay option list instead. |
| Mouse drag | Track drag start/end positions. Useful for text selection in Input/Textarea (deferred). |

**Element-specific click behavior**:
- **Button**: triggers `onClick`
- **Input/Textarea**: moves focus + positions cursor at click column (offset within text content)
- **Select**: moves focus, click opens/closes option overlay
- **Anchor**: triggers navigation (OSC 8 hyperlink)
- **Any element with onClick**: triggers the handler via bubbling

**Hover tracking**:
Track `lastHoverIdx` (the deepest element under the mouse). On mouse move:
```
newIdx = hitTest(x, y, layout)
if newIdx != lastHoverIdx then
  // Apply HoverProp style to new element, remove from old
  lastHoverIdx = newIdx
  // triggers re-render (hover style change)
```

This enables `HoverProp(style)` support — apply the hover style to whatever element the mouse is over. Note: hover enter/leave does NOT fire onFocus/onBlur — those are keyboard focus events only. The UI AST has no onMouseEnter/onMouseLeave handlers.

### Scroll state

Scroll offsets for `overflow: scroll` elements are maintained as external state — a `SignalRef[Map[Any, Int]]` mapping element identity (the UI AST Element reference from `element[idx]`) to vertical scroll offset. Keying by AST element identity rather than layout index ensures scroll position is preserved across frames even when the tree structure changes (elements inserted/removed above a scrollable container would change layout indices but the AST reference remains stable).

**Data flow**:
1. During **flatten**, elements with `overflow: scroll` in lFlags are noted
2. During **arrange**, scrollable containers lay out children at their natural positions (as if unlimited height), then store the total content height in `intrH[idx]`
3. During **paint**, the scroll offset is applied:

```
paintScrollable(idx, scrollOffset):
  visibleTop    = layout.y(idx) + borderTop + padTop
  visibleBottom = layout.y(idx) + layout.h(idx) - borderBottom - padBottom
  visibleHeight = visibleBottom - visibleTop

  // Walk children, apply offset, clip
  child = layout.firstChild(idx)
  while child != -1:
    childY = layout.y(child) - scrollOffset   // shift up by scroll amount
    if childY + layout.h(child) > visibleTop && childY < visibleBottom then
      // Child is at least partially visible — paint with clip rect
      paintWithClip(child, visibleTop, visibleBottom)
    // else: child entirely outside visible area — skip entirely
    child = layout.nextSibling(child)
```

**Clipping**: `TuiRenderer.set()` checks against a clip rect stack. When entering a scrollable container, push `(clipTop, clipBottom)`. Any `set()` call where `y < clipTop || y >= clipBottom` is silently dropped. Pop on exit. The clip rect is just two Ints passed through the paint recursion — no allocation.

**Scroll indicators** — painted on the right edge of the scrollable container:
```
contentHeight = layout.intrH(idx)   // total content height (from arrange)
viewHeight    = visibleHeight        // visible area height
scrollOffset  = current offset

if contentHeight > viewHeight then
  // Up/down arrows
  if scrollOffset > 0 then
    renderer.set(rightEdgeX, visibleTop, '▲', indicatorStyle)
  if scrollOffset + viewHeight < contentHeight then
    renderer.set(rightEdgeX, visibleBottom - 1, '▼', indicatorStyle)

  // Scroll thumb — proportional position on the track
  trackStart = visibleTop + (if scrollOffset > 0 then 1 else 0)
  trackEnd   = visibleBottom - (if scrollOffset + viewHeight < contentHeight then 1 else 0)
  trackHeight = trackEnd - trackStart
  thumbHeight = max(1, trackHeight * viewHeight / contentHeight)
  thumbPos    = trackStart + (trackHeight - thumbHeight) * scrollOffset / (contentHeight - viewHeight)

  for y <- trackStart until trackEnd do
    if y >= thumbPos && y < thumbPos + thumbHeight then
      renderer.set(rightEdgeX, y, '█', indicatorStyle)
    else
      renderer.set(rightEdgeX, y, '░', indicatorStyle)
```

**Input handling** for scroll:
- **Keyboard** (when a scrollable element has focus or contains the focused element):
  - ArrowUp/ArrowDown → scroll by 1 row
  - PageUp/PageDown → scroll by `viewHeight - 1` rows
  - Home/End → scroll to top/bottom
- **Mouse scroll wheel**: hit test finds the nearest scrollable ancestor at `(x, y)`, then adjusts its scroll offset by ±3 rows per wheel event
- Scroll offset is clamped to `[0, max(0, contentHeight - viewHeight)]`
- Updating the scroll SignalRef triggers a re-render via the race in the main loop

**Focus-follows-scroll**: when Tab moves focus to an element inside a scrollable container, auto-scroll to make the focused element visible. Compute `focusedY - containerY` and adjust scroll offset if the element is above or below the visible window.

**Nested scrolling**: if a scrollable container is inside another scrollable container, each has its own scroll offset and clip rect. The clip rect stack naturally handles this — inner clips are intersected with outer clips.

### Pseudo-state styles

- **`FocusProp(style)`**: applied when the element has focus. If no FocusProp, a default is used (heavy border `┏━┓┃┗━┛` for buttons/inputs, reverse video for anchors).
- **`ActiveProp(style)`**: applied for a single render frame when the element is activated (Enter/Space pressed). The loop sets an active flag, re-renders with the active style, then clears the flag after ~100ms and re-renders again.
- **`HoverProp(style)`**: supported via SGR mouse tracking. `lastHoverIdx` tracks the element under the cursor; hover style is applied/removed on mouse move events.
- **`Alignment.baseline`**: no font baseline in a terminal. Treated as `start`.

### Form handling

Forms are the primary interactive surface in TUI. Each form element renders as a recognizable terminal widget and responds to keyboard + mouse input. All form state lives in the UI AST's `SignalRef` values — the TUI backend reads and writes them, no separate form state needed.

#### Input (type=text, default)

**Rendering**: single-line bordered box showing the current value (or placeholder in dim style when empty).
```
┌──────────────────┐
│Enter your name___│   ← placeholder, dim
└──────────────────┘

┌──────────────────┐
│John▏_____________│   ← value with cursor (▏ = thin bar)
└──────────────────┘
```

**Cursor state**: `cursorPos: Int` tracked per focused Input in the main loop state (not in the layout — it's transient UI state, reset when focus moves).

**Keyboard**:
- Printable chars → insert at `cursorPos` into `SignalRef[String]`, advance cursor
- Backspace → delete char before cursor, move cursor left
- Delete → delete char after cursor
- Left/Right → move cursor ±1
- Home/End → cursor to start/end
- Ctrl+A → select all (set selection range)

**Mouse click** → move cursor to click position:
```
contentX = layout.x(idx) + borderLeft + padLeft
cursorPos = clamp(mouseX - contentX, 0, value.length)
```
Monospace grid = 1 char per cell, so `mouseX - contentX` is the exact character index. No font metrics needed.

**Horizontal scroll for long values**: when `value.length > contentWidth`, display a window starting at `scrollX`. Cursor movement adjusts `scrollX` to keep cursor visible. Scroll indicators `◀▶` at edges when content overflows.

**`onInput` callback**: fires after each edit with the new String value.

#### Input (type=password)

Same as text Input, but renders `●` (U+25CF) per character instead of actual text. Cursor positioning still works — `cursorPos` maps to the same cell offset.

#### Input (type=checkbox)

**Rendering**: `☐` (U+2610) unchecked, `☑` (U+2611) checked. Label text beside it.
```
☑ Remember me
☐ Subscribe to newsletter
```

**Interaction**: Space/Enter toggles `checked` SignalRef. Mouse click toggles.

#### Textarea

**Rendering**: multi-line bordered box with text content.
```
┌────────────────────────┐
│This is line one        │
│And this is line two    │
│▏                       │   ← cursor on empty line 3
│                        │
│                        │
└────────────────────────┘
```

**Cursor state**: `(cursorLine: Int, cursorCol: Int)` — 2D cursor position.

**Keyboard**:
- Printable chars → insert at cursor, advance col
- Enter → insert newline, move to next line col 0
- Backspace at col 0 → join with previous line
- Up/Down → move cursor between lines (clamp col to line length)
- All Input keys (Home, End, Delete, etc.) apply within current line

**Mouse click** → 2D cursor positioning:
```
contentX = layout.x(idx) + borderLeft + padLeft
contentY = layout.y(idx) + borderTop + padTop
cursorLine = clamp(mouseY - contentY + scrollOffset, 0, lines.length - 1)
cursorCol  = clamp(mouseX - contentX, 0, lines(cursorLine).length)
```

**Scrolling**: when content exceeds box height, uses the scroll mechanism (scroll state section). Arrow keys at top/bottom edges auto-scroll. Mouse wheel scrolls. Scroll indicator on right edge.

**Word wrap**: text wraps within available `contentWidth` when `wrapText` is enabled. Wrapped lines are visual only — the underlying String preserves original line breaks.

#### Button

**Rendering**: children (label text) inside a bordered box.
```
┌──────┐       ┏━━━━━━┓
│ Save │       ┃ Save ┃   ← focused (heavy border)
└──────┘       ┗━━━━━━┛
```

**Interaction**:
- Enter/Space → triggers `onClick`, applies `ActiveProp` style for ~100ms (visual press feedback)
- Mouse click → triggers `onClick` via hit test + bubbling

**Disabled**: rendered in dim style (`\u001b[2m`), skipped in focus cycle, ignores input.

#### Select (dropdown)

**Closed rendering**: shows selected value with dropdown indicator.
```
┌─────────────┐
│ English   ▾ │
└─────────────┘
```

**Opening**: Enter/Space (or mouse click) opens an overlay painted on top of all other content.

**Overlay rendering**: bordered box listing all `Option` children, positioned below (or above if not enough room below).
```
┌─────────────┐
│ English   ▾ │
├─────────────┤
│ English     │   ← highlighted (reverse video)
│ Español     │
│ Français    │
│ Deutsch     │
│ 日本語      │
└─────────────┘
```

**Overlay positioning**:
```
// Try below the select box
dropdownY = selectY + selectH
dropdownH = min(optionCount, maxVisible)  // cap at ~10

// Flip above if not enough room
if dropdownY + dropdownH > terminalHeight then
  dropdownY = selectY - dropdownH

// Width = max of select width and longest option
dropdownW = max(selectW, longestOptionText + 2)
```

**Overlay painting**: overlays are collected during the main paint pass and painted in a second pass (after all regular elements). This ensures they render on top without z-buffer complexity. Since we do full repaint every frame, closing the overlay just means the next frame doesn't paint it — the double-buffer diff handles cleanup automatically.

**Keyboard** (while open):
- Arrow Up/Down → move highlight (wrapping)
- Enter → select highlighted option, close, fire `onChange`
- Escape → close without selecting
- Type characters → jump to first option matching prefix (type-ahead)

**Mouse** (while open):
- Click on option → select and close
- Click outside overlay → close without selecting
- Scroll wheel → scroll options if more than `maxVisible`

**State**: `openSelectIdx: Int` (-1 = none open) + `highlightedOption: Int` in the main loop. When a Select overlay is open, input dispatch routes to the overlay instead of normal focus.

**Option.selected**: resolves to a Boolean indicating pre-selection. Used to set the initial highlighted option when the overlay opens. Also determines which option text to display in the closed Select box when `Select.value` is not explicitly set.

**Option.value**: the String value passed to `Select.onChange` when this option is selected. If absent, the option's text content is used.

#### Form

**Rendering**: invisible container (like Div) — no visual border unless styled. Groups its children for Tab navigation and submit behavior.

**Submit**: Enter on any Input inside a Form triggers `onSubmit`:
```
// Walk up parent chain from focused element
idx = focusedIdx
while idx != -1 do
  layout.element(idx) match
    case form: Form if form.onSubmit.nonEmpty =>
      run(form.onSubmit.get)
      return
    case _ =>
  idx = layout.parent(idx)
```

**Tab order**: focus cycles through form fields in document order (depth-first traversal order = layout index order). This is already handled by the focus system — Form doesn't need special Tab logic.

#### Label

**Rendering**: text container, no special visual. In the UI AST, Label's first child is typically text, and it's associated with a nearby Input/Select by document proximity.

#### Anchor

**Rendering**: text with underline style (or user-specified style). OSC 8 terminal hyperlink:
```
\u001b]8;;https://example.com\u001b\\link text\u001b]8;;\u001b\\
```

**Interaction**: Enter (or mouse click) opens the URL. The terminal itself may handle link activation, or we can use `ProcessBuilder("open", url)` / `xdg-open` as fallback.

#### Disabled state

All interactive elements (Input, Textarea, Select, Button) support `disabled`:
- Skipped in focus cycle (Tab jumps past them)
- Rendered in dim style (`\u001b[2m` SGR)
- Ignore keyboard and mouse input
- Resolved during flatten from `disabled: Maybe[Boolean | Signal[Boolean]]` → packed into `lFlags` bit 13

#### Visual cursor

The text cursor is rendered by the painter when the focused element is an Input or Textarea:
- **Block cursor**: invert fg/bg at cursor position
- **Bar cursor**: render `▏` (U+258F) at cursor position in fg color
- **Blinking**: toggle visibility every ~530ms via a `SignalRef[Boolean]` updated by a timer fiber. Included in `Signal.awaitAny` so blink transitions trigger re-render. Alternatively, use terminal's built-in cursor blink by showing the real terminal cursor (`\u001b[?25h` + `\u001b[row;colH`) positioned at the input cursor — avoids the timer fiber entirely.

#### Text selection

Mouse drag sets `selectionStart` and `selectionEnd` (character offsets). Selected range is rendered with inverted/highlighted colors. Works for both Input and Textarea. Ctrl+C copies selection to clipboard (OSC 52: `\u001b]52;c;[base64]\u0007`).

### Semantic HTML elements in TUI

Elements that have no special interactivity but need TUI-specific rendering behavior:

#### Headings (H1–H6)

Rendered with decreasing emphasis. In a monospace terminal, "size" is expressed via bold + decoration:
- **H1**: bold, full-width underline (`═══`), extra blank line after
- **H2**: bold, full-width underline (`───`), extra blank line after
- **H3**: bold, extra blank line after
- **H4**: bold
- **H5**: normal weight
- **H6**: dim (`\u001b[2m`)

These are default styles — user-specified Style props override them.

#### Hr (horizontal rule)

Renders as a full-width horizontal line using `─` (U+2500). Takes the element's `width` style (or parent content width if auto). Height is always 1 row. Respects `TextColor` for the line color.

#### Br (line break)

Inserts a line break in the text flow. During measure/arrange, Br increments the current Y position by 1 row and resets X to the content start. It's a zero-width, 1-height element.

#### Ul / Ol / Li (lists)

- **Ul > Li**: each Li is prefixed with `• ` (U+2022 bullet, 2 cells indent)
- **Ol > Li**: each Li is prefixed with its 1-based index: `1. `, `2. `, etc. (dynamic width: 3+ cells indent)
- Nested lists increase indent by 2 cells per level
- Li is laid out as a row: `[prefix][content]`

The prefix is added during paint, not during flatten — the layout allocates space for it by adding left padding to Li based on nesting depth and list type.

#### Table / Tr / Td / Th

Table layout uses a separate algorithm from flexbox:

1. **Measure columns**: scan all rows, compute max intrinsic width for each column position
2. **Colspan/rowspan**: Td/Th with `colspan > 1` spans multiple columns; `rowspan > 1` spans multiple rows. Space is distributed evenly across spanned columns/rows.
3. **Arrange**: each cell gets `(x, y, w, h)` based on column offsets and row heights
4. **Paint**: cell content is clipped to cell bounds; borders drawn using box-drawing characters with proper junction merging (e.g., `┼` where borders meet)

```
Table rendering:
┌──────┬───────┬──────┐
│ Name │ Email │ Role │    ← Th (bold text)
├──────┼───────┼──────┤
│ John │ j@... │ Admin│    ← Td
│ Jane │ ja@.. │ User │
└──────┴───────┴──────┘
```

Th content is rendered bold by default. Border junctions (`┬┴├┤┼`) are resolved via the box-drawing merge table.

#### Pre / Code

- **Pre**: preserves whitespace and line breaks as-is (no word wrap regardless of `wrapText` setting). Rendered in the same monospace font as everything else (already monospace in TUI).
- **Code**: same as Pre when block-level. When inline (inside a Span or P), renders with a different bg color (e.g., slightly darker) to distinguish code from surrounding text.

#### Label

Renders as plain text container. `forId` associates the label with an Input/Select element — clicking the label moves focus to the associated element (lookup by `identifier` in the layout tree via hit test → find element where `CommonAttrs.identifier == forId` → set focusRef).

#### Anchor.target

Ignored in TUI — no concept of separate windows/tabs. All links open in the same context.

### Web-only attributes (ignored in TUI)

These CommonAttrs fields are specific to DOM/browser backends and have no effect in TUI:
- `style: Maybe[String | Signal[String]]` — CSS string, web-only. TUI uses `uiStyle: Style` exclusively.
- `attrs: Map[String, String | Signal[String]]` — arbitrary HTML attributes, no terminal equivalent.
- `handlers: Map[String, Unit < Async]` — generic DOM event handlers (e.g., `"dblclick"`, `"contextmenu"`). TUI supports only the typed handlers (onClick, onKeyDown, etc.).
- `classes` / `dynamicClassName` — CSS class names, web-only. TUI resolves styles inline.

### Overflow.auto

`Overflow.auto` behaves the same as `Overflow.scroll` in TUI — if content exceeds the box, scrollable; otherwise no scroll indicators shown. The difference from `scroll` is that scroll indicators are hidden when content fits (no empty track/thumb).

### FontWeight numeric variants

`FontWeight` has numeric variants (w100–w900). TUI maps:
- `w100`–`w300` → dim (`\u001b[2m`)
- `w400`–`w500` → normal
- `w600`–`w900`, `bold` → bold (`\u001b[1m`)

### BorderStyle.none

`BorderStyle.none` means no border is drawn (equivalent to border width 0 on all sides). Distinct from omitting the border style — `none` explicitly removes borders even if `BorderWidthProp` is set.

---

## Style Property Coverage

### Full support — direct ANSI/Unicode mapping

| Style Property | Terminal Technique |
|---|---|
| **BgColor** | `\u001b[48;2;r;g;bm` (truecolor) |
| **TextColor** | `\u001b[38;2;r;g;bm` (truecolor) |
| **Bold** (FontWeight.bold) | `\u001b[1m` (SGR 1) |
| **Italic** (FontStyle.italic) | `\u001b[3m` (SGR 3) |
| **Underline** | `\u001b[4:1m` straight, `4:2` double, `4:3` curly, `4:4` dotted, `4:5` dashed |
| **Colored underline** | `\u001b[58;2;r;g;bm` |
| **Strikethrough** | `\u001b[9m` (SGR 9) |
| **Padding** | Cell-count spacing (shrinks content area) |
| **Margin** | Cell-count spacing (offsets position) |
| **Gap** | Cell-count spacing between children |
| **FlexDirection** (row/column) | Layout engine main axis |
| **Align** | Layout engine cross-axis positioning |
| **Justify** | Layout engine main-axis distribution |
| **Width / Height** | Cell dimensions |
| **MinWidth / MaxWidth / MinHeight / MaxHeight** | Clamping in layout |
| **TextAlign** (left/center/right/justify) | Pad text within content area |
| **TextOverflow** (ellipsis) | Truncate + `…` character |
| **WrapText** | Line-break within available width |
| **TextTransform** (uppercase/lowercase/capitalize) | String transformation before rendering |
| **Opacity** | Software color blending before buffer write |
| **Hidden** | Skip element entirely in layout |
| **Overflow** (hidden/scroll/visible) | Clip content to box bounds / virtual viewport |
| **Hyperlinks** (Anchor href) | OSC 8: `\u001b]8;;URL\u001b\\text\u001b]8;;\u001b\\` |
| **LineHeight** | Extra blank rows between text lines |

### Full support — Unicode box-drawing characters

Complete border character map (from Lipgloss survey):

| Style | Top | Bot | Left | Right | TL | TR | BL | BR | ML | MR | M | MT | MB |
|-------|-----|-----|------|-------|----|----|----|----|----|----|----|----|----|
| **Solid** | `─` | `─` | `│` | `│` | `┌` | `┐` | `└` | `┘` | `├` | `┤` | `┼` | `┬` | `┴` |
| **Rounded** | `─` | `─` | `│` | `│` | `╭` | `╮` | `╰` | `╯` | `├` | `┤` | `┼` | `┬` | `┴` |
| **Dashed** | `┄` | `┄` | `┆` | `┆` | `┌` | `┐` | `└` | `┘` | `├` | `┤` | `┼` | `┬` | `┴` |
| **Dotted** | `┈` | `┈` | `┊` | `┊` | `┌` | `┐` | `└` | `┘` | `├` | `┤` | `┼` | `┬` | `┴` |
| **Heavy** | `━` | `━` | `┃` | `┃` | `┏` | `┓` | `┗` | `┛` | `┣` | `┫` | `╋` | `┳` | `┻` |
| **Double** | `═` | `═` | `║` | `║` | `╔` | `╗` | `╚` | `╝` | `╠` | `╣` | `╬` | `╦` | `╩` |
| **Block** | `█` | `█` | `█` | `█` | `█` | `█` | `█` | `█` | `█` | `█` | `█` | `█` | `█` |
| **Outer half-block** | `▀` | `▄` | `▌` | `▐` | `▛` | `▜` | `▙` | `▟` | — | — | — | — | — |
| **Inner half-block** | `▄` | `▀` | `▐` | `▌` | `▗` | `▖` | `▝` | `▘` | — | — | — | — | — |

Middle characters (ML, MR, M, MT, MB) are used when adjacent borders merge at T-junctions and crossings.

`BorderRadius > 0` selects rounded corners (`╭╮╰╯`) per-corner, applied on top of whatever border style is active.

**Border colors**: `BorderColorProp(top, right, bottom, left)` supports per-side colors. In TuiLayout, stored as 4 arrays: `bdrClrT`, `bdrClrR`, `bdrClrB`, `bdrClrL` (packed 24-bit RGB, -1 = absent). Applied as fg color to the respective border characters during paint. `BorderTopProp`/`BorderRightProp`/`BorderBottomProp`/`BorderLeftProp` also set both width and color for individual sides.

**Border widths**: always 1 cell in a terminal. Width > 0 → present, width = 0 → absent. Enables selective borders (e.g., bottom-only).

**Border radius**: `BorderRadiusProp(topLeft, topRight, bottomRight, bottomLeft)` supports per-corner values. In TUI, each corner is binary: radius > 0 → rounded (`╭╮╰╯`), radius = 0 → square (`┌┐└┘`). Stored as 4 bits in `pFlags` (bits 9-12: roundedTL, roundedTR, roundedBR, roundedBL).

### Approximated — creative terminal techniques

| Style Property | Terminal Technique |
|---|---|
| **FontSize** | Sub-cell pixel rendering using font8x8 bitmap + Unicode block elements. Half-blocks (`▀▄█`) for 2x, quadrants (`▘▝▖▗`) for 2x2, braille (`⠁...⣿`) for 2x4 resolution. |
| **FontWeight** (100-900) | SGR 1 for bold (>=600), SGR 2 for dim (<=300), normal otherwise |
| **Shadow** | Blended shadow color in cells adjacent to the element, offset by x/y, gradient via alpha falloff |
| **Translate** | Offset (x, y) in layout by cell count; visual only, does not affect siblings |
| **LetterSpacing** | Space characters inserted between letters (integer cells, coarser than CSS) |
| **FontFamily** | No-op (terminal uses its configured monospace font) |
| **BorderRadius** | Per-corner binary: radius > 0 uses rounded corner (`╭╮╰╯`), radius = 0 uses square (`┌┐└┘`). Supports mixed (e.g., only top corners rounded). |
| **Cursor** | No-op (no mouse cursor to change) |
| **Images** (Img element) | Auto-detected protocol: Kitty Graphics → iTerm2 IIP → Sixel → sextant/quadrant/half-block character fallback. See Image Rendering section. |

### Removed from Style API

Removed entirely (not just unsupported on TUI) to ensure a consistent API across all backends:

| Removed Property | Reason |
|---|---|
| **`rotate(degrees)`** | No useful terminal mapping. Arbitrary angles destroy interactivity. |
| **`scale(x, y)`** | No useful terminal mapping. Box resizing is already covered by width/height. |

---

## Image Rendering

### Layout integration

`Img` elements participate in flexbox layout like any other element. The layout engine assigns them a cell-sized bounding box `(w, h)`.

**Intrinsic sizing** during measure:
1. Query cell pixel dimensions via `\u001b[16t` → response `\u001b[6;cellH;cellWt` (cached once at startup)
2. Load image via `javax.imageio.ImageIO.read()` (Java stdlib) → get pixel dimensions
3. Compute intrinsic cell size: `intrW = ceil(imgPixelW / cellPixelW)`, `intrH = ceil(imgPixelH / cellPixelH)`
4. If explicit `Width`/`Height` style props are set, those override
5. Aspect ratio preserved when only one dimension is specified

During **paint**, the painter sees an `Img` node and delegates to `TuiImage` which selects the best available protocol and emits the image at the assigned cell position and size.

### Protocol detection

Detected once during `TuiTerminal.enter`, before the first render:

```
1. Send Kitty graphics query:  \u001b_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA\u001b\\
2. Send DA1 query:             \u001b[c
3. Read responses (timeout ~100ms, drain all)
4. Check $TERM_PROGRAM env var

Detection priority:
  - Kitty graphics OK in response     → ImageProtocol.Kitty
  - TERM_PROGRAM ∈ {iTerm2, WezTerm}  → ImageProtocol.ITerm2
  - DA1 response contains ";4;"       → ImageProtocol.Sixel
  - Otherwise                         → ImageProtocol.CharacterBlock
```

Result stored in `TuiTerminal.imageProtocol` — a simple enum, checked by `TuiImage` during paint.

### Native protocols (Kitty, iTerm2, Sixel)

All three protocols accept an image payload and a target size, and the terminal renders the image into the specified cell region. Surrounding components are unaffected — the image is just a rectangular block in the layout.

**Kitty Graphics Protocol**:
```
\u001b_Gf=100,t=d,a=T,c=<cols>,r=<rows>,q=2;[base64 PNG data]\u001b\\
```
- `c=cols,r=rows` specifies cell dimensions — terminal scales to fit
- `f=100` = PNG format (terminal decodes)
- Supports virtual placements, animation, z-layering

**iTerm2 Inline Image Protocol (IIP)**:
```
\u001b]1337;File=inline=1;width=<cols>;height=<rows>;preserveAspectRatio=1:[base64 data]\u0007
```
- `width`/`height` in cells — terminal scales to fit
- Supported by: iTerm2, WezTerm, Warp, VSCode terminal, Tabby

**Sixel**:
```
\u001bPq[sixel data]\u001b\\
```
- Must pre-scale image to exact pixel dimensions (`cellPixelW × cols` by `cellPixelH × rows`) before encoding
- Scale via `BufferedImage.getScaledInstance()` (Java stdlib)
- Sixel encoding: quantize colors (up to 256), encode pixel rows as sixel data (6 vertical pixels per row)

### Character block fallback

When no image protocol is available, render images using Unicode block characters with truecolor fg+bg. Three tiers, detected by font/terminal capability:

**Pixel density per cell**:

| Method | Grid | Pixels/cell | Characters |
|--------|------|-------------|------------|
| **Sextants** | 2×3 | 6 | U+1FB00–1FB3B + `▌▐█ ` (64 patterns). Best quality. Requires Unicode 13+ font. |
| **Quadrants** | 2×2 | 4 | `▘▝▖▗▀▐▌▄▛▜▙▟▞▚█ ` (16 patterns). Wide support. |
| **Half-blocks** | 1×2 | 2 | `▀▄█ ` (4 patterns). Universal fallback. |

**Rendering algorithm** (same for all three tiers):
```
For each cell (cx, cy) in the image's layout box:
  Sample the NxM pixel region from the scaled image
    (N=2,M=3 for sextants; N=2,M=2 for quadrants; N=1,M=2 for half-blocks)

  bestError = MaxInt; bestSym = ' '; bestFg = 0; bestBg = 0

  For each candidate pattern (64 for sextants, 16 for quadrants, 4 for half-blocks):
    Partition sampled pixels into "fg set" (pattern bit = 1) and "bg set" (bit = 0)
    fgColor = average RGB of fg set pixels
    bgColor = average RGB of bg set pixels
    error   = sum of squared RGB distance from each pixel to its assigned color
    if error < bestError then
      bestError = error; bestSym = patternChar; bestFg = fgColor; bestBg = bgColor

  renderer.set(x + cx, y + cy, bestSym, packStyle(fg = bestFg, bg = bestBg))
```

**Image scaling**: scale source image to `(cellW × N, cellH × M)` pixels using area-averaging downscale (better than nearest-neighbor for downscaling). Java's `BufferedImage` + `Graphics2D` with `RenderingHints.VALUE_INTERPOLATION_BILINEAR`.

**Sextant detection**: render U+1FB00 to a test cell and check if the terminal reports width 1 (supported) vs width 2 or replacement char (unsupported). Alternatively, check `$TERM` / `$TERM_PROGRAM` against a known-good list.

**Performance**: for a 40×20 cell image, sextant rendering evaluates 40×20×64 = 51,200 pattern comparisons. Each is ~6 multiply-adds. Total: <1ms. Pre-computed lookup tables for pattern → pixel membership make the inner loop branch-free.

### Clipping images in scrollable containers

When an image is partially scrolled out of view:
- **Kitty/iTerm2**: both support crop/offset parameters — emit only the visible portion
- **Sixel**: re-encode only the visible pixel rows
- **Character blocks**: clipping works naturally since each cell is independent — the clip rect in `TuiRenderer.set()` handles it

---

## Size Resolution

Every `Size` value is resolved to an integer cell count during style resolution.

| Size | Rule |
|------|------|
| `Px(v)` | `max(0, v.toInt)` |
| `Em(v)` | `max(0, v.toInt)` (1 em = 1 cell) |
| `Pct(v)` | `max(0, (v / 100.0 * parentDim).toInt)` |
| `Auto` | Context-dependent (see below) |

**Auto by context**:
- Width/Height → intrinsic size (fit content)
- Horizontal Margin → center element (distribute remaining space)
- Vertical Margin, Padding → 0
- Min/Max constraints → no constraint

**Percentage base**:
- Width, MinWidth, MaxWidth, Translate.x → parent content width
- Height, MinHeight, MaxHeight, Translate.y → parent content height
- Padding (all sides), Margin (all sides), Gap → parent content width (CSS convention)
- FontSize → parent's resolved font size (default = 1 cell)
- BorderWidth, BorderRadius → clamped to binary (0 or 1)

---

## Memory Budget

All data structures are pre-allocated once and reused across frames. Summary for a 200×50 terminal (~10,000 cells, ~100 UI nodes):

| Structure | Size | Lifetime |
|-----------|------|----------|
| `TuiRenderer` buffers ×2 | ~200KB | Session (allocated once, swapped each frame) |
| `TuiRenderer.wideSyms` ×2 | ~0 | Session (sparse, <100 entries) |
| `AnsiBuffer` | ~64KB | Session (reset via `pos = 0` each frame) |
| `TuiLayout` flat arrays (×45 arrays, 256 capacity) | ~45KB | Session (reset via `count = 0` each frame) |
| `SignalCollector` | ~0.5KB | Session (reset each frame) |
| **Total static** | **~310KB** | **Allocated once, never GC'd** |

| Per-frame allocations | Count | Notes |
|-----------------------|-------|-------|
| `Chunk[Signal[?]]` for awaitAny | 1 | Wrapper around SignalCollector array slice |
| Short-lived fibers from `Signal.awaitAny` | ~N | N = number of signals (~50). Each is a masked `signal.next` race participant. Lightweight (promise await + fiber wrapper). |
| InputEvent objects (KeyEvent + MouseEvent) | ~1-5 | From input parsing |
| **Total per frame** | **~50-60 objects** | **Dominated by awaitAny race fibers; all short-lived** |

Key design decisions:
- **Flat arrays instead of objects**: eliminates ~300 per-frame allocations (LayoutBox + LayoutStyle + PaintStyle + Array[LayoutBox] backing)
- **Inline signal resolution**: eliminates snapshot tree allocation — signals resolved during flatten, values written directly into arrays
- **AnsiBuffer**: pre-allocated byte buffer with inline UTF-8 encoding and ANSI helpers — zero String allocation in flush path, `writeTo` writes directly to tty with no copy
- **`reset()` is O(1)**: TuiLayout and SignalCollector just set `count = 0` — every slot is overwritten before read
- **Double-buffer swap**: two pointer assignments, no allocation
- **Bit-packed flags**: enums + booleans in one Int per node, no per-field padding
- **Raw Int sentinels**: `-1` for absent colors/sizes, avoids `Maybe[Int]` boxing

---

## File Structure

All under `kyo-ui/jvm/src/main/scala/kyo/`:

```
kyo/TuiBackend.scala                    — UIBackend impl: main loop wiring
kyo/internal/
  TuiLayout.scala                       — flat array table + measure + arrange
  TuiFlatten.scala                      — walk UI AST, resolve signals + styles → TuiLayout arrays
  TuiRenderer.scala                     — double-buffered cell grid, diff, ANSI emission, swap
  TuiStyle.scala                        — Style.Prop → writes into TuiLayout arrays
  TuiPainter.scala                      — TuiLayout arrays → writes into TuiRenderer
  TuiInput.scala                        — raw bytes → KeyEvents
  TuiFocus.scala                        — focus state, focusable extraction from layout
  TuiColor.scala                        — Color → packed RGB, blending
  TuiImage.scala                        — image rendering: protocol detection, native (Kitty/iTerm2/Sixel), character block fallback
  TuiTerminal.scala                     — raw mode, alt screen, SIGWINCH, mouse, tty I/O
```

Tests under `kyo-ui/jvm/src/test/scala/kyo/`:
```
TuiColorTest.scala                      — color packing, hex parsing, blending
TuiRendererTest.scala                   — set/flush/resize/diff/ANSI output
TuiStyleTest.scala                      — Style.Prop → flat array values
TuiLayoutTest.scala                     — measure + arrange on known UI trees
TuiFlattenTest.scala                    — UI AST → TuiLayout flat arrays (signal resolution, style resolution)
TuiPainterTest.scala                    — TuiLayout → TuiRenderer cell contents
TuiInputTest.scala                      — byte sequences → KeyEvents + MouseEvents
TuiFocusTest.scala                      — focus cycling, focusable extraction, event dispatch
TuiImageTest.scala                      — character block rendering, protocol selection
```

## Terminal Capability Detection

### Color Tier

Detect once at startup, store as enum:

```
Detection order (first match wins):
NoColor    — $NO_COLOR is set (any value) → disable all color output (checked first, overrides everything)
TrueColor  — $COLORTERM ∈ {truecolor, 24bit}, or $TERM_PROGRAM ∈ {iTerm.app, WezTerm, mintty}
Color256   — terminfo `colors >= 256`, or $TERM contains "256color"
Color16    — everything else (basic ANSI)
```

TuiRenderer.flush checks the tier when emitting SGR:
- **TrueColor**: `\u001b[38;2;r;g;bm` as-is
- **Color256**: quantize to nearest in 6×6×6 cube (indices 16-231) or 24-step grayscale (232-255) via Euclidean RGB distance → `\u001b[38;5;{idx}m`
- **Color16**: map to nearest ANSI color (0-15) → `\u001b[{30+idx}m` / `\u001b[{90+idx}m` for bright
- **NoColor**: emit no SGR color codes; bold/underline still allowed

Quantization lives in TuiColor:
```scala
def to256(packed: Int): Int   // nearest 6×6×6 cube or grayscale index
def to16(packed: Int): Int    // nearest ANSI 0-15
```

### Dark/Light Theme Detection

Detect at startup to pick sensible default fg/bg colors:

1. **OSC 11 query**: Write `\u001b]11;?\u001b\\` to tty, read response `\u001b]11;rgb:RRRR/GGGG/BBBB\u001b\\` with 200ms timeout
2. Parse RGB (16-bit per channel), compute relative luminance: `L = 0.299×R + 0.587×G + 0.114×B` (normalized 0-1)
3. `L > 0.5` → light background; else dark
4. **Fallback**: Parse `$COLORFGBG` (format `fg;bg`, e.g. `15;0`) — bg < 8 = dark
5. **Default**: dark (most terminals)

Store as `isDarkBackground: Boolean` in TuiTerminal. Used by TuiPainter to select default text color (white on dark, black on light) and adjust border visibility.

## Clipboard Integration

### OSC 52 Copy/Paste

```
Copy:   \u001b]52;c;{base64-encoded-text}\u001b\\
Paste:  \u001b]52;c;?\u001b\\  → terminal responds with \u001b]52;c;{base64}\u001b\\
```

- Paste response read from stdin with 200ms timeout (same as OSC 11)
- Many terminals support copy but block paste read for security — fall back gracefully

### Bracketed Paste Mode

Enable at startup: `\u001b[?2004h` (disable on exit: `\u001b[?2004l`)

Pasted text arrives wrapped: `\u001b[200~`...`\u001b[201~`

TuiInput detects this wrapper and emits a single `PasteEvent(text: String)` instead of individual key events. This lets Input/Textarea handle bulk paste correctly (insert all at once, single undo step).

### Platform Fallback

On macOS when OSC 52 paste fails: shell out to `pbpaste` via ProcessBuilder. For copy, `pbcopy` via ProcessBuilder with text on stdin. Only used as fallback — OSC 52 preferred for terminal-native operation.

### Tmux Wrapping

Inside tmux (`$TMUX` set), OSC 52 must be wrapped in DCS passthrough:
```
\u001bPtmux;\u001b\u001b]52;c;{base64}\u001b\u001b\\\u001b\\
```
Requires `set -g allow-passthrough on` in tmux 3.3+.

## Multiplexer Compatibility

### Detection

```
Tmux:        $TMUX is set
GNU Screen:  $TERM starts with "screen" and $TMUX is not set
Neither:     direct terminal access
```

### Tmux

- **DCS passthrough**: Escape sequences that need to reach the outer terminal must be wrapped: `\u001bPtmux;\u001b{seq}\u001b\\` (inner ESC doubled)
- **Feature detection**: `tmux display -p '#{client_termfeatures}'` returns comma-separated list (RGB, clipboard, mouse, etc.)
- **What breaks without passthrough**: OSC 52 clipboard read, image protocols (Kitty/iTerm2/Sixel), OSC 11 bg query
- **allow-passthrough**: Off by default since tmux 3.3a; users must enable it for advanced features
- Basic ANSI (colors, cursor, mouse SGR) works without passthrough

### GNU Screen

- No DCS passthrough support — advanced escape sequences (OSC 52, image protocols, OSC 11) will not work
- Basic ANSI rendering works fine
- Detect and skip unsupported features gracefully (no errors, just reduced functionality)

### TuiTerminal Capability Record

TuiTerminal stores detected capabilities at startup:

```scala
case class Capabilities(
    colorTier: ColorTier,          // TrueColor | Color256 | Color16 | NoColor
    isDarkBg: Boolean,             // dark/light background
    hasOsc52Copy: Boolean,         // clipboard copy
    hasOsc52Paste: Boolean,        // clipboard paste (often blocked)
    hasBracketedPaste: Boolean,    // pasted text detection
    multiplexer: Multiplexer,     // None | Tmux | Screen
    imageProtocol: ImageProto,    // None | Kitty | ITerm2 | Sixel
    cellPixelW: Int,               // cell width in pixels (from \u001b[16t query, for image scaling)
    cellPixelH: Int,               // cell height in pixels
)
// Note: SGR mouse (mode 1003 + 1006) is always enabled — no capability flag needed
```

## Signal Handling & Ctrl Key Bindings

### Raw Mode Key Delivery

In raw mode, the kernel does not interpret control keys as signals:

| Key | Byte | Normal mode | Raw mode |
|-----|------|-------------|----------|
| Ctrl+C | 0x03 | SIGINT | byte 0x03 delivered to read() |
| Ctrl+Z | 0x1a | SIGTSTP | byte 0x1a delivered to read() |
| Ctrl+\\ | 0x1c | SIGQUIT | byte 0x1c delivered to read() |

### Default Bindings

- **Ctrl+C**: Quit (clean exit). Overridable by the application (e.g. copy-to-clipboard)
- **Ctrl+Z**: Suspend — exit alt screen, restore stty, send `SIGTSTP` to self. On resume: re-enter raw mode + alt screen + full repaint
- **Ctrl+\\**: Always quit (non-overridable emergency exit)
- **Double Ctrl+C within 500ms**: Always quit even if Ctrl+C is rebound — guaranteed escape hatch

### Ctrl+Z Suspend/Resume Sequence

```
1. Exit alternate screen: \u001b[?1049l
2. Show cursor: \u001b[?25h
3. Disable mouse + bracketed paste: \u001b[?1003l\u001b[?1006l\u001b[?2004l
4. Restore original stty settings
5. Send SIGTSTP to self: Runtime.exec("kill -TSTP " + ProcessHandle.current().pid())
   — process suspends here, shell gets control —
6. On resume (SIGCONT): re-enter raw mode + alt screen + mouse + bracketed paste + hide cursor
7. Query terminal size (may have changed)
8. Full repaint (clear prev buffer to force complete redraw)
```

### External Signal Handling

Even in raw mode, signals sent externally (e.g. `kill -TERM <pid>`) still arrive:

- **SIGTERM/SIGHUP**: Register via `sun.misc.Signal.handle`. Run cleanup (exit alt screen, restore stty)
- **Shutdown hook**: `Runtime.addShutdownHook` as last-resort cleanup
- **Idempotent cleanup**: Guard with `AtomicBoolean` — safe to call from signal handler, shutdown hook, and normal exit
- **UncaughtExceptionHandler**: Set on the main thread to ensure cleanup runs on unexpected crashes

```scala
private val cleaned = new java.util.concurrent.atomic.AtomicBoolean(false)
private def cleanup(term: TuiTerminal): Unit =
    if cleaned.compareAndSet(false, true) then
        term.disableBracketedPaste()
        term.disableMouse()
        term.showCursor()
        term.exitAltScreen()
        term.restoreStty()
// Called from: Scope.ensure (normal exit), sun.misc.Signal handlers (SIGTERM/SIGHUP),
// shutdown hook (last resort), uncaught exception handler. AtomicBoolean ensures
// only the first caller runs cleanup — all others no-op.
```

## Implementation Order

Dependencies:
```
TuiColor       → (none)
TuiInput       → (none)
TuiLayout      → (none) (flat array table + measure + arrange — pure geometry)
TuiRenderer    → TuiColor
TuiStyle       → TuiColor, TuiLayout (writes into TuiLayout arrays)
TuiFlatten     → TuiLayout, TuiStyle (walks UI AST, resolves signals + styles)
TuiPainter     → TuiRenderer, TuiLayout, TuiColor, TuiImage
TuiImage       → TuiRenderer, TuiColor, TuiTerminal (for protocol detection)
TuiFocus       → TuiLayout
TuiTerminal    → kyo OsSignal, Signal APIs
TuiBackend     → all of the above
```

**Phase 1 — Leaf utilities** (no dependencies, can be built in parallel):
1. **TuiColor** — packed RGB, hex parsing, blending. Foundation for everything.
2. **TuiInput** — byte → InputEvent (key + mouse) parser. Independent of the rendering chain.

**Phase 2 — Core infrastructure** (need TuiColor, independent of each other):
3. **TuiLayout** — flat array table + measure + arrange. The central data structure. Pure geometry, no UI AST knowledge.
4. **TuiRenderer** — double-buffered cell grid, diff, ANSI emission.

**Phase 3 — Style, flatten, and painting** (connect UI AST to layout to renderer):
5. **TuiStyle** — Style.Prop → writes into TuiLayout arrays. Depends on TuiColor + TuiLayout.
6. **TuiFlatten** — walk UI AST, resolve signals + styles → TuiLayout arrays. Depends on TuiLayout + TuiStyle.
7. **TuiPainter** — TuiLayout arrays → writes into TuiRenderer. Depends on TuiRenderer + TuiLayout.

**Phase 4 — Interactivity** (independent of each other, can be built in parallel):
8. **TuiFocus** — focusable extraction, event dispatch. Depends on TuiLayout.
9. **TuiTerminal** — raw mode, SIGWINCH, tty I/O. Depends on kyo OsSignal + Signal APIs.

**Phase 5 — Integration**:
10. **TuiBackend** — the main loop wiring everything together.

**Deferred**:
- **TuiPixelFont** (font8x8 bitmap + sub-cell rendering) — independent, only needed by painter for `FontSize` approximation. Build after end-to-end pipeline works.
- **TuiImage** — image rendering (protocol detection + native protocols + character block fallback). Independent of core pipeline, only needed when `Img` elements are present. Build after end-to-end pipeline works.

## References

- [Ratatui Buffer/Cell](https://docs.rs/ratatui/latest/ratatui/buffer/struct.Cell.html) — Cell struct design, skip flag, double-buffer diffing
- [Ratatui Under the Hood](https://ratatui.rs/concepts/rendering/under-the-hood/) — Rendering pipeline
- [FTXUI Architecture](https://deepwiki.com/ArthurSonzogni/FTXUI) — 3-phase layout, Canvas pixel modes
- [Lipgloss Borders](https://github.com/charmbracelet/lipgloss/blob/master/borders.go) — Complete border character definitions
- [Textual Rendering Algorithms](https://textual.textualize.io/blog/2024/12/12/algorithms-for-high-performance-terminal-apps/) — Compositor, flicker-free techniques
- [Textual 7 Lessons](https://www.textualize.io/blog/7-things-ive-learned-building-a-modern-tui-framework/) — Overwrite-never-clear, synchronized output
- [Synchronized Output Spec](https://gist.github.com/christianparpart/d8a62cc1ab659194337d73e399004036) — Mode 2026 protocol
- [tui-big-text](https://github.com/joshka/tui-big-text) — Sub-cell pixel font rendering
- [font8x8](https://github.com/dhepper/font8x8) — 8x8 bitmap font data
- [Kitty Graphics Protocol](https://sw.kovidgoyal.net/kitty/graphics-protocol/) — Inline image rendering
- [iTerm2 Inline Images](https://iterm2.com/documentation-images.html) — iTerm2 IIP specification
- [Are We Sixel Yet?](https://www.arewesixelyet.com/) — Terminal sixel support tracker
- [Chafa](https://hpjansson.org/chafa/) — State-of-the-art terminal image rendering (symbol selection, color fitting)
- [Unicode Sextants (HN)](https://news.ycombinator.com/item?id=24956014) — 2×3 block mosaics for image rendering
- [Unicode Block Mosaics](https://unicode.org/charts/beta/nameslist/n_1FB00.html) — U+1FB00 sextant character definitions
- [TerminalImageViewer](https://github.com/stefanhaustein/TerminalImageViewer) — Quadrant-based image rendering
- [ANSI Escape Codes](https://en.wikipedia.org/wiki/ANSI_escape_code) — Full SGR reference
- [NO_COLOR](https://no-color.org/) — Convention for disabling color output
- [OSC 52 Clipboard](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h3-Operating-System-Commands) — Xterm control sequences spec
- [Bracketed Paste Mode](https://cirw.in/blog/bracketed-paste) — How bracketed paste works
- [Tmux Passthrough](https://github.com/tmux/tmux/wiki/FAQ#what-is-the-passthrough-escape-sequence-and-how-do-i-use-it) — DCS passthrough documentation
