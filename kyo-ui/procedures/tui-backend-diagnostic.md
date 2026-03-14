# TUI Backend Design Exploration

## What exists

### Pipeline (complete, 502 tests passing)
- `Pipeline.renderFrame(ui, state, viewport)` → `Array[Byte] < (Async & Scope)` — full render cycle producing ANSI escape bytes
- `Pipeline.dispatchEvent(event, state)` → `Unit < Async` — routes input events to handler closures
- `InputEvent` enum: `Key`, `Mouse`, `Paste` — the pipeline's input contract
- `CellGrid` — 2D cell array, the pipeline's visual output before diffing
- `Differ.diff(prev, curr)` → `Array[Byte]` — produces ANSI escape sequences for terminal update
- `ScreenState` — persists across frames: focusedId, hoveredId, activeId, widgetState cache, prevLayout, prevGrid

### Backend interface (defined, not implemented for TUI)
- `UIBackend.render(ui, theme)` → `UISession < (Async & Scope)` — abstract
- `UISession(fiber, rendered)` — wraps a render loop fiber with `await`, `stop`, `awaitRender`, `onRender`
- `DomBackend` (JS) — complete implementation for browser DOM
- `JavaFxBackend` (JVM) — commented out, was HTML-based WebView, not TUI

### Headless rendering (exists)
- `RenderToString.render(ui, cols, rows, theme)` → `String < (Async & Scope)` — renders to plain text string
- `Screen` (test harness) — renders and dispatches events for interaction testing

### Demo
- `TuiDemo` — uses `RenderToString` to print a static frame, no interactivity

## What's missing

### 1. Terminal I/O layer
No code exists for:
- **Raw terminal mode** — disable line buffering, echo, canonical mode (stty raw / termios)
- **ANSI output** — write `Array[Byte]` from Differ to stdout
- **Alternate screen buffer** — `\e[?1049h` on start, `\e[?1049l` on exit (full screen mode)
- **Terminal size detection** — read cols/rows from terminal (stty size, TIOCGWINSZ ioctl, or ANSI `\e[18t` query)
- **Cursor visibility** — hide cursor during rendering (`\e[?25l`), show on exit (`\e[?25h`)

### 2. Input parsing
No code exists for:
- **Raw byte → InputEvent** — parse terminal escape sequences into the pipeline's `InputEvent` enum
- **Keyboard sequences** — arrow keys (`\e[A`), function keys (`\e[15~`), ctrl combos, alt combos
- **Mouse sequences** — SGR mouse mode (`\e[<0;x;yM`), button press/release/move
- **Bracketed paste** — `\e[200~`...`\e[201~` → `InputEvent.Paste(text)`
- **Resize signal** — SIGWINCH → re-query terminal size

### 3. Render loop
No code exists for:
- **Main loop** — render → wait for input/signal change → dispatch → re-render
- **Signal change detection** — detect when any `SignalRef` changes and trigger re-render
- **Debouncing** — coalesce rapid signal changes into a single re-render
- **Resize handling** — re-render with new viewport on terminal resize

### 4. Lifecycle management
No code exists for:
- **Graceful shutdown** — restore terminal state on exit (normal, exception, SIGINT)
- **Scope management** — materialized signal fibers need cleanup when session ends
- **Error recovery** — if render fails, restore terminal state before propagating

## Architecture constraints (from the pipeline)

### The pipeline's contract
- `renderFrame` takes a `Rect` viewport — the backend provides terminal dimensions
- `renderFrame` returns `Array[Byte]` — ANSI escapes ready for stdout
- `dispatchEvent` takes `InputEvent` — the backend parses raw bytes into these
- `ScreenState` persists across frames — the backend owns and passes it
- All pipeline methods follow the AllowUnsafe discipline (Rule 3 for computation-returning)

### What the backend must provide
1. A `Rect` viewport that updates on resize
2. Raw `InputEvent` values parsed from terminal input
3. A trigger for re-render when signals change
4. Stdout writing of the ANSI bytes from `renderFrame`
5. Terminal setup/teardown (raw mode, alternate screen, mouse mode)

### What the backend must NOT do
- No widget-type awareness — the pipeline handles all widget logic
- No layout computation — the pipeline handles positioning
- No style resolution — the pipeline handles inheritance
- No event routing — `Dispatch` handles hit-testing and handler composition

## Platform considerations

### JVM-specific terminal I/O
- `java.lang.ProcessBuilder` for `stty` commands (raw mode, size query)
- `System.in` for byte-level input reading
- `System.out` for ANSI byte output
- `sun.misc.Signal` for SIGWINCH (JVM-specific, may need fallback)
- Alternative: JNA/JNI for direct termios access (more reliable than stty subprocess)

### Cross-platform (shared)
- `InputEvent` enum already in shared — parsing logic could be shared if input bytes are platform-abstracted
- `Pipeline`, `ScreenState`, `Differ` — all shared, backend just calls them
- The render loop logic (wait for input OR signal change, dispatch, re-render) is platform-independent once I/O is abstracted

### Native considerations
- Scala Native has direct access to C termios API — no stty/JNA needed
- SIGWINCH handling via `signal()` C function
- stdin/stdout via C file descriptors

## Design constraints (from the implementation plan)

The plan prescribes:
- **Simple**: single file, single entry point per component, no god objects
- **Modular**: backend depends only on Pipeline's public API (`renderFrame`, `dispatchEvent`), no widget-type awareness
- **Safe**: all cross-frame state is `SignalRef`, terminal state restoration guaranteed via `Scope.ensure`
- **AllowUnsafe discipline**: terminal I/O methods doing immediate side effects are Rule 2 (take `AllowUnsafe`). The render loop and session lifecycle are Rule 3 (return computations, no `AllowUnsafe`). `Sync.Unsafe.defer` at boundaries only.
- **Pipeline contract is fixed**: `renderFrame` returns `Array[Byte] < (Async & Scope)`, `dispatchEvent` returns `Unit < Async`. The backend calls these and nothing else from the pipeline.
- **Backend runs in `Async & Scope`**: materialized signal fibers attach to the session scope. Session end → scope close → fibers cancelled.

## Design questions

### 1. Re-render trigger

How does the backend know when to re-render after a handler fires?

Dispatch writes to `SignalRef.Unsafe` refs (focusedId, hoveredId, widget state). `SignalRef` has `.next` which returns a `Promise` that completes when the value changes. The backend could:

- **Race signals with input**: collect all active signals, race their `.next` promises with the input stream. Whichever fires first triggers the next cycle. This is what the plan describes ("Changes to any ref trigger re-render automatically").
- **Always re-render after dispatch**: every input event triggers a dispatch then a re-render. No signal watching needed for input-driven changes. Signal watching only needed for external changes (user code updating a `SignalRef` from outside the UI).

The second approach is simpler. Dispatch always changes something (focus, hover, widget state) or does nothing. Re-rendering after dispatch is cheap if nothing changed (Differ produces empty output). For external signal changes, the backend subscribes to the user's `UI` tree signals via `Signal.streamChanges` or similar.

### 2. Input/render sequencing

The pipeline assumes sequential render→dispatch→render. `ScreenState` is mutable (frame-local vars, widget state cache). Concurrent access would corrupt it.

The render loop must be a single sequential fiber:
```
loop:
    wait for input event OR signal change
    if input: dispatch(event)
    re-render
```

No parallelism within the loop. Input reading can be a separate fiber that feeds events into a channel. The render loop reads from the channel.

### 3. Viewport as fresh read

Terminal size can change at any time (resize). The viewport `Rect` must be read fresh each render cycle. The backend should query terminal dimensions before each `renderFrame` call, not cache them.

### 4. Terminal I/O abstraction

Terminal I/O operations:
- Enter/exit raw mode
- Enter/exit alternate screen buffer
- Enable/disable mouse tracking
- Show/hide cursor
- Read input bytes
- Write ANSI output bytes
- Query terminal size

These are platform-specific. The render loop logic is not. A trait abstracts the I/O.

**Every `TerminalIO` method returns a computation (Rule 3).** Terminal I/O is side-effectful — it must not take `AllowUnsafe`. Each platform implementation wraps system calls in `Sync.Unsafe.defer` internally.

```scala
trait TerminalIO:
    def enterRawMode(using Frame): Unit < IO
    def exitRawMode(using Frame): Unit < IO
    def enterAlternateScreen(using Frame): Unit < IO
    def exitAlternateScreen(using Frame): Unit < IO
    def enableMouseTracking(using Frame): Unit < IO
    def disableMouseTracking(using Frame): Unit < IO
    def showCursor(using Frame): Unit < IO
    def hideCursor(using Frame): Unit < IO
    def size(using Frame): (Int, Int) < IO
    def write(bytes: Array[Byte])(using Frame): Unit < IO
    def readByte(using Frame): Int < IO
    def flush(using Frame): Unit < IO
```

The render loop composes these as computations — no `Sync.Unsafe.defer` in loop body:

```scala
// The loop is pure computation composition
terminal.size.map { (cols, rows) =>
    Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows))
}.map { ansi =>
    terminal.write(ansi).andThen(terminal.flush)
}
```

The render loop lives in shared code, terminal I/O implementation in platform-specific code. This makes the loop testable with a mock `TerminalIO`.

### 5. Input parsing

Terminal escape sequences are multi-byte and context-dependent:
- `\e[A` = Arrow Up
- `\e[1;5A` = Ctrl+Arrow Up
- `\e` alone = Escape key (needs timeout to distinguish from sequence prefix)
- `\e[<0;10;5M` = SGR mouse click at (10, 5)
- `\e[200~`...`\e[201~` = bracketed paste

The parser needs:
- A state machine that buffers partial sequences
- A timeout (typically 50-100ms) to distinguish bare Escape from sequence prefix
- Conversion to the pipeline's `InputEvent` enum

This is a pure function (bytes → events) with a small amount of state (buffer, timeout). It could be a shared-code component with no platform dependencies.

### 6. Lifecycle

All lifecycle steps are computations (Rule 3). Terminal setup/teardown compose via `.andThen`. Cleanup is registered via `Scope.ensure` — guaranteed to run on scope exit.

```scala
// Rule 3: returns computation, no AllowUnsafe
def render(ui: UI, theme: Theme)(using Frame): UISession < (Async & Scope) =
    for
        state <- Sync.Unsafe.defer { new ScreenState(ResolvedTheme.resolve(theme)) }
        _     <- terminal.enterRawMode
        _     <- Scope.ensure(terminal.exitRawMode)
        _     <- terminal.enterAlternateScreen
        _     <- Scope.ensure(terminal.exitAlternateScreen)
        _     <- terminal.enableMouseTracking
        _     <- Scope.ensure(terminal.disableMouseTracking)
        _     <- terminal.hideCursor
        _     <- Scope.ensure(terminal.showCursor)
        // ... initial render, start loop fiber ...
    yield session
```

`Scope.ensure` stacks in reverse — exitRawMode runs last. Guarantees terminal restoration on normal exit, exception, or fiber cancellation. For SIGINT, a JVM shutdown hook may be needed as a fallback since SIGINT doesn't go through kyo's scope mechanism.

### 7. Where code lives

Following the plan's file structure:
- `shared/` — render loop logic, input parser, `TerminalIO` trait
- `jvm/` — `TerminalIO` implementation (stty/termios), shutdown hook
- `js/` — not applicable (DOM backend exists)
- `native/` — `TerminalIO` implementation (C termios)

This keeps the architecture testable: the render loop can be tested with a mock `TerminalIO` in shared tests.
