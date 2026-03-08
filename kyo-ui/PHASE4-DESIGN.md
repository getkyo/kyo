# Phase 4: TuiPainter, TuiTerminal, TuiFocus, TuiBackend

## Overview

Complete the TUI backend by implementing the four remaining files. Each builds on the previous — TuiPainter is pure and testable, TuiTerminal is I/O, TuiFocus is stateful logic, and TuiBackend wires everything into a running `UIBackend`.

## Existing Components

| File | Role | Status |
|------|------|--------|
| TuiLayout | Flat arrays + measure + arrange (flexbox) | Done |
| TuiFlatten | UI AST → flat arrays, resolves signals inline | Done |
| TuiStyle | Style.Prop → layout/paint arrays | Done |
| TuiRenderer | Double-buffered cell grid, diff-based ANSI flush | Done |
| TuiAnsiBuffer | Pre-allocated byte buffer for ANSI output | Done |
| TuiColor | Packed RGB, hex parsing, quantization | Done |
| TuiInput | Escape sequence parser (keys, mouse, paste) | Done |
| TuiSignalCollector | Collects signals during flatten for awaitAny | Done |

## Pipeline

```
UI AST
  → TuiFlatten (resolve signals, build flat arrays)
  → TuiLayout.measure (bottom-up intrinsic sizing)
  → TuiLayout.arrange (top-down position assignment)
  → TuiPainter.inheritStyles (propagate fg/bg/attrs to children)  ← NEW
  → TuiFocus.scan + applyFocusStyle                                ← NEW
  → TuiPainter.paint (render into cell grid)                       ← NEW
  → TuiRenderer.flush (diff-emit ANSI)
  → TuiTerminal (raw mode, /dev/tty I/O)                           ← NEW
  → TuiBackend (orchestrates input + render fibers)                 ← NEW
```

---

## File 1: `internal/TuiPainter.scala`

Reads TuiLayout flat arrays, paints into TuiRenderer. Pure computation, no I/O.

### Public API

```scala
object TuiPainter:
    def inheritStyles(layout: TuiLayout): Unit
    def paint(layout: TuiLayout, renderer: TuiRenderer): Unit
```

### `inheritStyles(layout)` — Forward pass, O(n)

Parents always have lower indices than children (TuiFlatten allocates depth-first). A single forward pass from index 1 to count-1 propagates inherited properties.

**Color inheritance** (fg, bg):
- Text nodes (nodeType == NodeText): always copy parent's fg, bg, and full pFlags. Text nodes are always leaves with no styles of their own.
- Element nodes: copy parent's fg/bg only when the child's value is Absent (-1). Don't touch pFlags — we can't distinguish "not set" from "explicitly set to default" for packed bit fields.

**Opacity blending**:
- If `opac(idx) < 1.0` and parent has a bg color, blend:
  `fg(idx) = TuiColor.blend(fg(idx), parentBg, opac(idx))`
  `bg(idx) = TuiColor.blend(bg(idx), parentBg, opac(idx))`
- Subtree opacity (a div at 50% making everything inside half-transparent) is a full compositor problem — out of scope. Apply per-node only.

### `paint(layout, renderer)` — Recursive tree walk

Entry point: `paintNode(layout, renderer, 0)`. The caller (`doRender`) is responsible for calling `renderer.clear()` before paint.

**`paintNode(layout, renderer, idx)`** for each node:

1. **Skip** if hidden.

2. **Background**: `renderer.fillBg(x, y, w, h, bg)` if bg != Absent.

3. **Borders**: If any border bit is set and borderStyle != None:
   - Use per-side border colors (bdrClrT, bdrClrR, bdrClrB, bdrClrL independently).
   - Fall back to fg color, then default gray.
   - Use `TuiLayout.borderChars(style, roundedTL, roundedTR, roundedBR, roundedBL)` for box-drawing characters.
   - Draw top/bottom horizontal lines, left/right vertical lines, four corners.

4. **Text**: If `layout.text(idx)` is defined:
   - Compute content area: `(x + borderL + padL, y + borderT + padT, w - borderL - borderR - padL - padR, h - borderT - borderB - padT - padB)`
   - Apply text transform (uppercase/lowercase/capitalize) from pFlags.
   - Get lines:
     - If wrapText flag: `TuiLayout.wrapText(text, contentW)`
     - Else if textOverflow=ellipsis: `TuiLayout.clipText(text, contentW, contentH, true)`
     - Else: `text.split("\n", -1)`, clipped to contentH lines
   - For each line, compute start X from textAlign:
     - Left: `contentX`
     - Center: `contentX + (contentW - lineLen) / 2`
     - Right: `contentX + contentW - lineLen`
   - Render characters, clipped to content area width.

5. **Clip rect for overflow:hidden children**:
   - If overflow mode == hidden (from lFlags), save current clip, set clip to content area.
   - Recurse into children.
   - Restore previous clip.
   - Otherwise just recurse into children without changing clip.

6. **Children**: Walk `firstChild → nextSibling` chain, call `paintNode` recursively.

### Text Transform Logic

```
transform == 1 (Uppercase): text.toUpperCase
transform == 2 (Lowercase): text.toLowerCase
transform == 3 (Capitalize): capitalize first letter of each word
```

### Helper: `contentArea(layout, idx) → (cx, cy, cw, ch)`

Computes the inner content rectangle accounting for borders and padding:
```
bT = if hasBorderT(lFlags) then 1 else 0  (similarly for R, B, L)
cx = x + bL + padL
cy = y + bT + padT
cw = max(0, w - bL - bR - padL - padR)
ch = max(0, h - bT - bB - padT - padB)
```

### Tests

- **inheritStyles**: Build a layout with parent fg=red, child fg=Absent → child inherits red. Text node inherits parent's bold. Element with explicit fg keeps it.
- **paintText**: Text with textAlign=Center in a 20-wide box → chars start at correct offset.
- **paintBorders**: Node with rounded border → correct corner chars at correct positions.
- **overflow:hidden**: Child text extending beyond parent bounds → clipped by clip rect.
- **textTransform**: Uppercase/lowercase/capitalize applied correctly.

---

## File 2: `internal/TuiTerminal.scala`

Manages terminal raw mode, `/dev/tty` I/O, and screen state lifecycle.

### Public API

```scala
class TuiTerminal:
    def rows: Int
    def cols: Int
    def outputStream: java.io.OutputStream

    def enter(): Unit
    def exit(): Unit
    def querySize(): (Int, Int)
    def pollResize(): Boolean
    def read(buf: Array[Byte]): Int
    def flush(): Unit
```

### `enter()` — Sequence (order matters)

1. `stty -g < /dev/tty` → save to `savedStty`
2. `stty raw -echo -icanon -isig < /dev/tty` → raw mode
3. Open `new FileInputStream("/dev/tty")`, `new FileOutputStream("/dev/tty")`
4. `querySize()` → store rows, cols
5. Write escape sequences to ttyOut:
   - `\e[?1049h` — alternate screen buffer
   - `\e[?25l` — hide cursor
   - `\e[?1003h` — enable all-motion mouse tracking
   - `\e[?1006h` — SGR mouse encoding format
   - `\e[?2004h` — enable bracketed paste mode
6. Flush ttyOut
7. `Runtime.addShutdownHook(new Thread(() => exit()))` — safety net
8. Set `entered = true`

### `exit()` — Reverse sequence (idempotent)

1. Guard: `if !entered then return`. Set `entered = false`.
2. Write escape sequences:
   - `\e[?2004l` — disable bracketed paste
   - `\e[?1006l` — disable SGR mouse
   - `\e[?1003l` — disable mouse tracking
   - `\e[?25h` — show cursor
   - `\e[?1049l` — exit alternate screen
   - `\e[0m` — reset all SGR attributes
3. Flush ttyOut
4. Close ttyIn, ttyOut
5. Restore stty: `stty $savedStty < /dev/tty`

### `read(buf)` — Non-blocking

```scala
val avail = ttyIn.available()
if avail > 0 then ttyIn.read(buf, 0, math.min(avail, buf.length))
else 0
```

The caller (input fiber) controls the polling cadence with `Async.sleep`.

### `querySize()` — Parse stty output

```scala
val p = ProcessBuilder("stty", "size").redirectInput(File("/dev/tty")).start()
val output = new String(p.getInputStream.readAllBytes()).trim  // "24 80"
val parts = output.split(" ")
(parts(0).toInt, parts(1).toInt)
```

### `pollResize()` — Called once per render cycle

Re-runs `querySize()`. If rows or cols changed, update stored values and return true.

### Why `-isig`

Disabling signal processing means Ctrl-C reaches us as byte 0x03 instead of SIGINT. The input loop captures it as `Key("c", ctrl=true)` and triggers clean shutdown. The shutdown hook handles unexpected termination (SIGTERM, etc.).

### stty Helpers — ProcessBuilder pattern

All stty calls use:
```scala
ProcessBuilder(args*).redirectInput(File("/dev/tty")).start()
```
This ensures stty operates on the actual terminal even when stdin is piped (e.g., sbt forked process).

### Tests

TuiTerminal is I/O-heavy. Unit tests are limited:
- `querySize` parsing (mock the stty output)
- Enter/exit sequence ordering (verify escape codes written in correct order via a ByteArrayOutputStream)

---

## File 3: `internal/TuiFocus.scala`

Stateful focus manager. Scans layout for focusable elements, cycles focus on Tab/Shift-Tab, dispatches input events to the focused element.

### Public API

```scala
class TuiFocus:
    def scan(layout: TuiLayout): Unit
    def focusedIndex: Int                // layout array index, or -1
    def next(): Unit
    def prev(): Unit
    def applyFocusStyle(layout: TuiLayout): Unit
    def dispatch(event: InputEvent, layout: TuiLayout)(using Frame, AllowUnsafe): Unit < Sync
```

### Internal State

```scala
private var indices: Array[Int] = new Array[Int](64)  // layout indices of focusable nodes
private var count: Int = 0
private var current: Int = -1    // position in indices array, -1 = no focus
private var prevFocusedElement: Maybe[AnyRef] = Absent  // to preserve focus across re-scans
```

### `scan(layout)` — Rebuild focusable list

Forward walk through layout (0 to count-1). Collect indices where:
- `TuiLayout.isFocusable(nodeType(i))` — Button, Input, Textarea, Select, Anchor
- NOT `TuiLayout.isHidden(lFlags(i))`
- NOT `TuiLayout.isDisabled(lFlags(i))`

**Focus preservation**: After scan, if `prevFocusedElement` is Present, search the new list for a node whose `layout.element(idx)` matches (reference equality). If found, set current to that position. Otherwise, clamp current to valid range or reset to 0.

### `next()` / `prev()` — Tab cycling

```
next(): current = if count == 0 then -1 else (current + 1) % count
prev(): current = if count == 0 then -1 else (current + count - 1) % count
```

### `applyFocusStyle(layout)` — Visual highlight

For the focused element at `indices(current)`:

1. Check `layout.focusStyle(idx)` — if Present, cast to `Style` and resolve it over the existing paint arrays via `TuiStyle.resolve`. This overlays the focus style on top (last-write-wins for each prop).
2. If Absent, apply a default highlight:
   - Set all border bits in lFlags (make border visible)
   - Set border color to bright blue (#7aa2f7)
   - Set border style to thin (if currently None)

### `dispatch(event, layout)` — Route to focused element

Returns `Unit < Sync`. Most paths return pure `()`. Only allocates a suspended computation when writing a SignalRef or launching a handler fiber.

**Global handlers** (regardless of focused element):
- `Key("Tab", shift=false)` → `next()`, return `()`
- `Key("Tab", shift=true)` → `prev()`, return `()`

**If no element focused**: return `()`.

**CommonAttrs event handlers** — All elements have these via `CommonAttrs`:
- `onKeyDown: Maybe[KeyEvent => Unit < Async]` — invoked before built-in handling
- `onKeyUp: Maybe[KeyEvent => Unit < Async]` — invoked after built-in handling
- `onClick: Maybe[Unit < Async]` — invoked on Enter/Space for buttons, Enter for anchors

All effects are composed via for-comprehension. `evalOrThrow` is only used for reading current signal values. Handler and SignalRef writes are composed as `< Sync`:

```scala
def dispatch(event: InputEvent, layout: TuiLayout)(using Frame, AllowUnsafe): Unit < Sync =
    event match
        case InputEvent.Key("Tab", _, _, shift) =>
            if shift then prev() else next()
            ()  // pure, no Sync effect
        case _ =>
            if current < 0 || count == 0 then ()
            else
                val idx    = indices(current)
                val elem   = layout.element(idx).get.asInstanceOf[Element]
                val common = elem.common
                for
                    _ <- fireHandler(common.onKeyDown, event)
                    _ <- builtInHandle(elem, event)
                    _ <- fireHandler(common.onKeyUp, event)
                yield ()

// Launch handler as fire-and-forget fiber. Fiber.initUnscoped returns Fiber < Sync.
// Only allocates KeyEvent when handler exists.
private def fireHandler(h: Maybe[KeyEvent => Unit < Async], event: InputEvent)(using Frame): Unit < Sync =
    if h.isDefined then
        val keyEvent = toKeyEvent(event)
        Fiber.initUnscoped(h.get(keyEvent)).unit
    else ()

private def fireAction(action: Maybe[Unit < Async])(using Frame): Unit < Sync =
    if action.isDefined then Fiber.initUnscoped(action.get).unit
    else ()
```

**Built-in handling by element type** — returns `Unit < Sync`. Reads use `evalOrThrow`, writes compose `ref.set`:

**Button**:
- `Key("Enter")` or `Key(" ")` → `fireAction(button.common.onClick)`.
- Other keys → `()`.

**Input**:
- `Key(char)` where char.length == 1 and printable → read with `evalOrThrow`, write with `set`:
  ```scala
  input.value match
      case ref: SignalRef[?] =>
          val r   = ref.asInstanceOf[SignalRef[String]]
          val cur = Sync.Unsafe.evalOrThrow(r.get)  // read: evalOrThrow
          r.set(cur + char)                          // write: composed (Unit < Sync)
      case _ => ()
  ```
- `Key("Backspace")` → same pattern with `r.set(cur.dropRight(1))`.
- `Key("Enter")` → `()`.
- Other keys → `()`.

**Textarea**: Same as Input but Enter returns `r.set(cur + "\n")`.

**Select**: ArrowUp/ArrowDown to cycle options (future).

**Anchor**: Enter → `fireAction(anchor.common.onClick)`.

### Tests

- **scan**: Layout with Button, Input, hidden Button, disabled Input → focusable list contains only visible/enabled ones.
- **next/prev**: Cycle through 3 elements, wrap around correctly.
- **applyFocusStyle**: Focused element gets border highlight.
- **dispatch Tab**: Advances focus index.
- **dispatch Enter on Button**: onClick handler invoked.
- **dispatch char on Input**: SignalRef value updated.

---

## File 4: `TuiBackend.scala`

The integration backend. Implements `UIBackend.render`. Lives at `kyo/TuiBackend.scala` (not in `internal/` — it's the public entry point).

### Public API

```scala
object TuiBackend extends UIBackend:
    def render(ui: UI)(using Frame): UISession < (Async & Scope)
```

### `render` Flow

```scala
def render(ui: UI)(using Frame): UISession < (Async & Scope) =
    for
        rendered  <- Signal.initRef[UI](UI.empty)
        terminal  =  new TuiTerminal
        _         =  terminal.enter()
        _         <- Scope.ensure(terminal.exit())
        layout    =  new TuiLayout(512)
        signals   =  new TuiSignalCollector(256)
        renderer  =  new TuiRenderer(terminal.cols, terminal.rows)
        focus     =  new TuiFocus
        _         =  doRender(ui, terminal, layout, signals, renderer, focus)
        _         <- rendered.set(ui)
        fiber     <- Fiber.init(
                        loop(ui, terminal, layout, signals, renderer, focus, rendered)
                     )
    yield UISession(fiber, rendered)
```

### Render Loop — Two concurrent fibers via `Async.race`

```scala
private def loop(...)(using Frame): Unit < Async =
    Async.race(
        inputLoop(terminal, focus, layout),
        renderLoop(ui, terminal, layout, signals, renderer, focus, rendered)
    ).unit
```

When the input loop exits (Ctrl-C), the render loop is interrupted. The fiber completes, triggering `Scope.ensure(terminal.exit())`.

**Input fiber** — Uses `Loop` state threading instead of mutable `var`:
```scala
private def inputLoop(terminal: TuiTerminal, focus: TuiFocus, layout: TuiLayout)(using Frame): Unit < Async =
    import AllowUnsafe.embrace.danger
    val readBuf = new Array[Byte](256)

    // Thread remainder through Loop state — no mutable var
    Loop(Chunk.empty[Byte]) { remainder =>
        val n = terminal.read(readBuf)
        if n > 0 then
            val all = remainder ++ Chunk.from(readBuf, 0, n)
            val (events, leftover) = TuiInput.parse(all)
            // Dispatch events, composing Sync effects from SignalRef writes / handler launches.
            // Most dispatch calls return pure () — no effect allocation.
            var i = 0; var quit = false
            var pending: Unit < Sync = ()
            while i < events.size && !quit do
                events(i) match
                    case InputEvent.Key("c", true, _, _) => quit = true
                    case e =>
                        pending = pending.andThen(focus.dispatch(e, layout))
                i += 1
            if quit then Loop.done(())
            else pending.andThen(Loop.continue(leftover))
        else
            Async.sleep(16.millis).andThen(Loop.continue(remainder))
    }
```

**Render fiber** — `signal.next` is the only genuinely async suspension:
```scala
private def renderLoop(...)(using Frame): Unit < Async =
    Loop.forever {
        val sigs = signals.toSpan
        for
            _ <- if sigs.isEmpty then Async.sleep(100.millis)
                 else
                    // Build race effects — allocates N closures but only once per render cycle
                    // (acceptable: render cycles are ≤60fps, not per-event)
                    Async.race(sigs.toSeq.map(s => s.next.unit))
            _ <- Async.sleep(4.millis)  // debounce
            _ =
                if terminal.pollResize() then
                    renderer.resize(terminal.cols, terminal.rows)
                    renderer.invalidate()
                doRender(ui, terminal, layout, signals, renderer, focus)
            _ <- rendered.set(ui)
        yield Loop.continue
    }
```

### `doRender` — Synchronous pipeline

`AllowUnsafe` scoped to this method only — needed by `TuiFlatten.flatten` to read signal values. Everything else is pure computation on flat arrays.

```scala
private def doRender(...)(using Frame): Unit =
    import AllowUnsafe.embrace.danger
    TuiFlatten.flatten(ui, layout, signals, terminal.cols, terminal.rows)
    TuiLayout.measure(layout)
    TuiLayout.arrange(layout, terminal.cols, terminal.rows)
    TuiPainter.inheritStyles(layout)
    focus.scan(layout)
    focus.applyFocusStyle(layout)
    renderer.clear()
    TuiPainter.paint(layout, renderer)
    renderer.flush(terminal.outputStream)
    terminal.flush()
```

### Ctrl-C Handling

Input fiber detects `Key("c", ctrl=true)` and exits via `Loop.done`. `Async.race` interrupts the render fiber. Scope cleanup restores terminal.

### Terminal Resize

`pollResize()` re-runs `querySize()`. If changed: `renderer.resize` + `renderer.invalidate` (full redraw).

### Tests

- `doRender` completes without error on a known UI tree (mock OutputStream)
- Lifecycle: enter → render → exit sequence
- Signal change triggers re-render (via SignalRef.set)

---

## Implementation Order

1. **TuiPainter** — Pure, largest, most testable.
2. **TuiTerminal** — Small, mostly I/O. Extracts code proven in demos.
3. **TuiFocus** — Stateful logic, depends on TuiPainter (for applyFocusStyle).
4. **TuiBackend** — Integration, depends on all three above.

## Open Questions

1. **Signal racing API**: Verify `Async.race` handles N-element `Seq` (not just 2-arg).
2. **Focus across re-renders**: Element reference equality breaks if the UI tree is rebuilt per signal change. May need key-based matching.

## Effect Boundary Summary

| Operation | Effect type | Strategy |
|-----------|------------|----------|
| `SignalRef.get` (read current) | `A < Sync` | `Sync.Unsafe.evalOrThrow` — pure read |
| `SignalRef.set` (write) | `Unit < Sync` | Compose — triggers change notifications |
| `signal.next` (wait) | `A < Async` | Compose — genuinely suspends |
| `onKeyDown/onKeyUp/onClick` | `Unit < Async` | `Fiber.initUnscoped` composed as `< Sync` (fire-and-forget) |
| `rendered.set` | `Unit < Sync` | Compose |
| `Scope.ensure` | `Unit < Scope` | Compose |
| `TuiFlatten.flatten` | needs `AllowUnsafe` | Scoped `import AllowUnsafe.embrace.danger` |
| Everything else (layout, paint, flush, parse, focus) | Pure | Direct call |

## Allocation Notes

**Per-render cycle** (≤60fps — acceptable):
- `sigs.toSeq.map(s => s.next.unit)` — N closures for signal racing
- `ChunkBuilder` inside `TuiInput.parse` for events

**Per-input event** (minimized):
- `focus.dispatch` returns pure `Unit` for most events (Tab, unhandled keys) — no effect allocation
- Only allocates when a handler exists (`Fiber.initUnscoped`) or a SignalRef is written (`r.set`)
- `KeyEvent` object created lazily — only when `onKeyDown`/`onKeyUp` is Present on the focused element

**Avoided**:
- ~~`buf.take(n)` (intermediate array copy)~~ — use `Chunk.from(readBuf, 0, n)`
- ~~`var remainder`~~ — threaded through `Loop` state
- ~~`events.exists { ... }` + `Kyo.foreach(...)`~~ — single `while` loop, no closures
- ~~`evalOrThrow` for fire-and-forget handlers~~ — `Fiber.initUnscoped` composed as `< Sync`
