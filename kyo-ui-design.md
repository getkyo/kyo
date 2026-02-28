# kyo-ui Design

## 1. Overview

kyo-ui is a UI module for the Kyo effect system. It provides a pure data model for describing user interfaces and backends that render them to different targets.

**Core principles:**

- **UI as pure data** — creating an element produces an inert value, not a live DOM node or terminal widget. A backend interprets the tree. This follows Kyo's "describe, don't execute" philosophy.
- **SignalRef for reactivity** — Kyo's existing `SignalRef` / `Signal` is the reactive primitive. No wrappers, no new types. Signal allocation via `Signal.initRef` with `for/yield`.
- **Builder DSL** — zero-arg tag constructors, chained attribute methods, children via `.apply`. Type safety via method availability per element type.
- **No external rendering libraries** — both the web backend (Scala.js DOM) and terminal backend (ANSI escape sequences) are implemented from scratch in pure Scala for maximum performance and control.
- **All platforms** — the core data model and terminal backend target JVM, JS (Node), and Scala Native. The web backend targets JS only.

```scala
def counter: UI < Async =
  for count <- Signal.initRef(0)
  yield
    div(
      p(count.map(_.toString)),
      button("+").onClick(count.update(_ + 1)),
      button("-").onClick(count.update(_ - 1))
    )
```

---

## 2. Design Decisions

### 2.1 SignalRef directly, not Var or custom primitives

`SignalRef` from kyo-core is persistent, fine-grained, concurrent-safe, and provides exactly the API UI needs: `map`, `set`, `update`, `get`, `streamChanges`. Signal allocation via `Signal.initRef` is honestly effectful (`SignalRef[A] < Async`), composed with `for/yield`.

Alternatives evaluated and rejected:

- **`Var[V]`** — no concurrency (`Var` can't be used with `Async`), scoped to a computation (UI is persistent — handlers fire long after construction), no fine-grained reactivity (one blob, can't subscribe to parts), disambiguation problem (two `Int` states are both `Var[Int]`).
- **Custom `UISignal` with `Frame` identity** — parallel type hierarchy, hidden `Render` effect, new `Action` type, all to avoid `for/yield`.
- **Eager allocation via `AllowUnsafe`** — hidden side effect contradicts pure data principle.

### 2.2 No type parameter on UI

Handlers are always `Unit < Async` — a fixed type. `UI` has no type parameter.

We initially designed `UI[-S]` where `S` tracked effects required by action handlers. This cascaded through everything: `Div[-S]`, `Child[-S]`, variance issues (`Signal[A]` is invariant, case classes with contravariant params), backend needed to thread arbitrary effect contexts to event listeners.

The insight: handlers only need signal operations and async work. `Env` dependencies are resolved at construction time and captured in closures — the handler gets a concrete value, not a deferred effect. This eliminates the type parameter, all variance concerns, and simplifies the backend to `render(ui: UI): Unit < (Async & Scope)`.

### 2.3 Zero-arg tag constructors + builder methods

Tag constructors are zero-arg values (`val div: Div`). If `div(...)` took children directly, `div.cls("x")` wouldn't work. Children are added via `.apply`, attributes via chained methods. Both styles work:

```scala
div.cls("container")(h1("Title"), p("Body"))  // attrs first
button("Submit").cls("primary").onClick(handler) // children first
```

Rejected: varargs modifiers (can't constrain per element type), named parameters (`children = Seq(...)` is verbose).

### 2.4 Union type for children, no implicit conversions

`type Child = UI | String | Signal[String] | Signal[UI]` — elements normalize internally. No `Conversion` instances needed, no imports, the union type is explicit.

### 2.5 Collection naming from Kyo.scala

`foreach` and `foreachIndexed` on `Signal[List[A]]`, matching `Kyo.foreach` and `Kyo.foreachIndexed`.

### 2.6 Pure Scala rendering, no external libraries

Both backends are implemented from scratch:

- **Web**: uses `org.scalajs.dom` (Scala.js standard type facades over browser APIs, zero overhead). Direct DOM manipulation, fine-grained signal→DOM binding.
- **Terminal**: pure Scala ANSI renderer. Cell buffer, diff algorithm, escape sequence output, layout engine, input parsing — all shared across platforms.

No Lanterna, no tui-scala, no ratatui. Existing TUI libraries are JVM-only or require native dependencies. A pure Scala implementation runs on all three platforms (JVM, JS/Node, Native) with ~30 lines of platform-specific I/O shim each (raw mode, terminal size, stdin/stdout access).

This also gives us full control over performance — no abstraction overhead, no unnecessary features, optimal for Kyo's performance-focused approach.

### 2.7 Components are functions

No base class, no registration, no lifecycle interface. Stateless components return `UI`. Stateful ones return `UI < Async`. Dependencies via `< Env[...]`. Lifecycle via `< Scope`. Composition is function calls + nesting in `for/yield`.

---

## 3. Data Model

### UI sealed trait

```scala
sealed trait UI

case class Text(value: String) extends UI
case class ReactiveText(signal: Signal[String]) extends UI
case class ReactiveNode(signal: Signal[UI]) extends UI
case class Foreach[A](signal: Signal[List[A]], render: A => UI) extends UI
case class ForeachIndexed[A](signal: Signal[List[A]], render: (Int, A) => UI) extends UI
case class Fragment(children: List[UI]) extends UI
```

### Child type

```scala
type Child = UI | String | Signal[String] | Signal[UI]
```

Elements normalize internally: `String` → `Text`, `Signal[String]` → `ReactiveText`, `Signal[UI]` → `ReactiveNode`.

### Element types

Each HTML element is its own abstract type with typed builder methods. Internal representation is private case classes in the same package. Handlers take `Unit < Async`.

```scala
sealed abstract class Div extends UI:
  def cls(v: String | Signal[String]): Div
  def id(v: String): Div
  def style(v: String | Signal[String]): Div
  def hidden(v: Boolean | Signal[Boolean]): Div
  def onClick(action: Unit < Async): Div
  def apply(children: Child*): Div

sealed abstract class Button extends UI:
  def cls(v: String | Signal[String]): Button
  def disabled(v: Boolean | Signal[Boolean]): Button
  def onClick(action: Unit < Async): Button
  def apply(children: Child*): Button

sealed abstract class Input extends UI:
  def cls(v: String | Signal[String]): Input
  def value(v: String | SignalRef[String]): Input
  def placeholder(v: String): Input
  def typ(v: String): Input
  def checked(v: Boolean | Signal[Boolean]): Input
  def disabled(v: Boolean | Signal[Boolean]): Input
  def onInput(f: String => Unit < Async): Input
  // no apply — void element

sealed abstract class Anchor extends UI:
  def href(v: String | Signal[String]): Anchor
  def target(v: String): Anchor
  def onClick(action: Unit < Async): Anchor
  def apply(children: Child*): Anchor

sealed abstract class Img extends UI:
  def cls(v: String | Signal[String]): Img
  def onClick(action: Unit < Async): Img
  // no apply — void element

sealed abstract class Form extends UI:
  def cls(v: String | Signal[String]): Form
  def onSubmit(action: Unit < Async): Form
  def apply(children: Child*): Form

// Same pattern for: P, Span, Ul, Li, H1-H6, Table, Tr, Td, Th,
// Nav, Header, Footer, Section, Main, Label, Select, Option, Textarea
```

### Tag constructors

```scala
val div: Div
val p: P
val span: Span
val button: Button
val input: Input
val ul: Ul
val li: Li
val form: Form
val nav: Nav
val header: Header
val footer: Footer
val section: Section
val main: Main
val h1: H1
// ... h2-h6, table, tr, td, th, label, select, textarea

def img(src: String, alt: String): Img  // required attrs
```

### Collection extensions

```scala
extension [A](signal: Signal[List[A]])
  def foreach(render: A => UI): Foreach[A]
  def foreachIndexed(render: (Int, A) => UI): ForeachIndexed[A]
```

### Backend trait

```scala
trait Backend:
  def render(ui: UI): Unit < (Async & Scope)
```

---

## 4. Construction Effects

Construction effects (`< S`) are what happens when building the UI tree. They are standard Kyo effects, handled at the call site:

```scala
// Static — no effects
val footer: UI = p("Built with Kyo")

// Stateful — signal allocation
val counter: UI < Async =
  for count <- Signal.initRef(0)
  yield div(p(count.map(_.toString)), button("+").onClick(count.update(_ + 1)))

// With dependencies — resolved at construction, captured in closures
val search: UI < (Async & Env[ApiClient]) =
  for
    client  <- Env.get[ApiClient]
    query   <- Signal.initRef("")
    results <- Signal.initRef(List.empty[String])
  yield
    div(
      input.value(query).onInput(query.set(_)),
      button("Go").onClick {
        for
          q   <- query.get
          res <- client.search(q)
          _   <- results.set(res)
        yield ()
      },
      ul(results.foreach(r => li(r)))
    )

// With lifecycle — cleanup on unmount
val ticker: UI < (Async & Scope) =
  for
    price <- Signal.initRef(0.0)
    _     <- Scope.ensure(Log.info("Stopped"))
    _     <- Async.run { priceStream.foreach(p => price.set(p)) }
  yield span(price.map(p => f"$$$p%.2f"))
```

The rendering site handles all construction effects:

```scala
object Main extends KyoApp:
  def run =
    Env.run(ApiClient.live) {
      Scope.run {
        for ui <- app
        yield Backend.render(ui)
      }
    }
```

---

## 5. Kyo Effects in UI

All effects are resolved during construction. No new effects invented — kyo-ui reuses existing Kyo primitives.

| Effect | Role | Replaces |
|--------|------|----------|
| `Async` | Signal ops, background tasks, HTTP in handlers | React `useState`, `useEffect` + fetch |
| `Scope` | Component cleanup on unmount | `useEffect` cleanup, `onDestroy` |
| `Env` | Dependency injection — resolved at construction, captured in closures | React Context, CompositionLocal |
| `Abort` | Error handling during fallible initialization | React error boundaries |
| `Stream` | Live data consumed into `SignalRef` during construction | RxJS observables |
| `Var` | **Not used** — no concurrency, scoped to computation | — |
| `Emit` | **Not used** — runs to completion, UI is persistent | — |

---

## 6. Examples

### Todo App

```scala
def todoApp: UI < Async =
  for
    items <- Signal.initRef(List.empty[String])
    text  <- Signal.initRef("")
  yield
    div.cls("todo-app")(
      h1("Todos"),
      div.cls("input-row")(
        input.value(text).onInput(text.set(_)).placeholder("What needs to be done?"),
        button("Add").onClick {
          for
            t <- text.get
            _ <- if t.nonEmpty then items.update(_ :+ t) else ()
            _ <- text.set("")
          yield ()
        }
      ),
      ul.cls("todo-list")(
        items.foreachIndexed((idx, todo) =>
          li.cls("todo-item")(
            span(todo),
            button("x").cls("delete").onClick(items.update(_.patch(idx, Nil)))
          )
        )
      ),
      p(items.map(i => s"${i.length} items left")).cls("footer")
    )
```

### Login Form

```scala
def loginForm: UI < (Async & Env[ApiClient]) =
  for
    client   <- Env.get[ApiClient]
    email    <- Signal.initRef("")
    password <- Signal.initRef("")
    error    <- Signal.initRef("")
    loading  <- Signal.initRef(false)
  yield
    form.cls("login").onSubmit {
      for
        e      <- email.get
        p      <- password.get
        _      <- loading.set(true)
        _      <- error.set("")
        result <- Abort.run[Throwable](client.login(e, p))
        _ <- result match
          case Result.Success(_) => loading.set(false)
          case Result.Failure(e) =>
            for _ <- error.set(e.toString); _ <- loading.set(false) yield ()
      yield ()
    }(
      div(label("Email"), input.typ("email").value(email).onInput(email.set(_))),
      div(label("Password"), input.typ("password").value(password).onInput(password.set(_))),
      p(error).cls("error").hidden(error.map(_.isEmpty)),
      button(loading.map(if _ then "Logging in..." else "Log in")).cls("submit").disabled(loading)
    )
```

### Dashboard with Live Data

```scala
def livePrice(ticker: String): UI < (Async & Scope) =
  for
    price <- Signal.initRef(0.0)
    _     <- Scope.ensure(Log.info(s"Stopped tracking $ticker"))
    _     <- Async.run { priceStream(ticker).foreach(p => price.set(p)) }
  yield span(price.map(p => f"$$$p%.2f")).cls("price")

def dashboard: UI < (Async & Scope & Env[ApiClient]) =
  for
    client <- Env.get[ApiClient]
    stats  <- Signal.initRef(Maybe.empty[Stats])
    btc    <- livePrice("BTC")
    eth    <- livePrice("ETH")
  yield
    div.cls("dashboard")(
      h1("Dashboard"),
      div.cls("prices")(div(span("BTC: "), btc), div(span("ETH: "), eth)),
      button("Refresh Stats").onClick {
        for s <- client.fetchStats; _ <- stats.set(Present(s)) yield ()
      },
      stats.map {
        case Absent    => p("Click refresh to load stats")
        case Present(s) => div.cls("stats")(
          p(s"Users: ${s.users}"),
          p(f"Revenue: $$${s.revenue}%.2f"),
          p(s"Orders: ${s.orders}")
        )
      }
    )

object Main extends KyoApp:
  def run =
    Env.run(ApiClient.live) {
      Scope.run {
        for ui <- dashboard
        yield DomBackend.render(ui)
      }
    }
```

---

## 7. Phase 1 — Core Data Model

Pure Scala, shared across all platforms. No rendering, no platform dependencies.

| Item | Description |
|------|-------------|
| `UI` sealed trait | `Text`, `ReactiveText`, `ReactiveNode`, `Foreach`, `ForeachIndexed`, `Fragment` |
| `Child` type | `UI \| String \| Signal[String] \| Signal[UI]` |
| Element types | Abstract types with builder methods: `Div`, `P`, `Button`, `Input`, `Anchor`, `Img`, `Form`, `Span`, `Ul`, `Li`, `H1`-`H6`, `Table`, `Tr`, `Td`, `Th`, `Nav`, `Header`, `Footer`, `Section`, `Main`, `Label`, `Select`, `Textarea` |
| Builder methods | `.cls`, `.id`, `.hidden`, `.style`, `.onClick` (universal); `.value`, `.onInput`, `.placeholder`, `.typ`, `.checked`, `.disabled` (Input); `.href`, `.target` (Anchor); `.onSubmit` (Form); etc. |
| Tag constructors | Zero-arg values plus `def img(src, alt)` for required attrs |
| Collection extensions | `.foreach` / `.foreachIndexed` on `Signal[List[A]]` |
| `Backend` trait | `render(ui: UI): Unit < (Async & Scope)` |
| Internal repr | Private case classes extending abstract element types |

Testable by constructing UI trees and inspecting their structure. Effect tracking verified at compile time.

---

## 8. Phase 2 — Web Backend

Scala.js, rendering `UI` to browser DOM. Single external dependency: `org.scalajs.dom` (type facades only, zero overhead).

### Signal → DOM binding

Each signal subscription targets one DOM operation — fine-grained, no VDOM, no tree diffing:

```scala
private def subscribe[A](signal: Signal[A])(f: A => Unit): Unit < (Async & Scope) =
  signal.get.map(f) *>
  Async.run { signal.streamChanges.foreach(a => f(a)) }.unit
```

Static attributes set once. Reactive attributes subscribe. Subscription fibers are tied to `Scope` — cancelled on unmount.

### Event handler wiring

Handlers are `Unit < Async`. The backend spawns a fiber per event:

```scala
el.addEventListener("click", _ => Async.run(action))
```

No effect context threading needed — handlers are a fixed type, dependencies were captured in closures at construction.

### ReactiveNode lifecycle

Each `ReactiveNode` renders content in a nested `Scope`. When the signal emits a new `UI`:

1. Close old Scope → cancels all signal subscriptions in old subtree
2. Remove old DOM nodes
3. Open new Scope, render new subtree within it

No leaks — `Scope` guarantees cleanup.

### List reconciliation

Index-based for `Foreach` and `ForeachIndexed`. Each item gets its own `Scope`. On list change: remove excess items, re-render changed positions, add new items. Key-based diffing can be added later as `foreachBy(keyFn)(render)`.

### rAF batching

`Signal.streamChanges` already delivers only the latest value. DOM writes further batched via `requestAnimationFrame` for coalescing multiple updates in one frame.

### Deliverables

| Item | Description |
|------|-------------|
| `DomBackend` | Implements `Backend`, walks UI tree → DOM nodes |
| Signal→DOM | `streamChanges` → `textContent` / `setAttribute` |
| Event wiring | `addEventListener` → `Async.run(action)` |
| ReactiveNode | Nested `Scope` per reactive subtree |
| List reconciliation | Index-based, per-item `Scope` |
| rAF batching | `requestAnimationFrame` scheduler |
| Scope integration | Fiber cancellation on unmount |

---

## 9. Performance

### Web: fine-grained signals vs VDOM diffing

React re-runs the entire component function on every state change, creates a new VDOM tree, and diffs it against the previous one — O(n) in tree size. Every keystroke in an input re-renders the entire component subtree. React mitigates this with `useMemo`, `useCallback`, `React.memo` — manual opt-outs that leak performance concerns into the API.

kyo-ui builds the UI tree once. Signal updates target only the DOM nodes that depend on the changed signal. A keystroke updates the input's value binding — one DOM write. The list and footer don't re-render. No diffing, no re-allocation of VDOM nodes, no closures recreated.

| | React | kyo-ui |
|---|---|---|
| State change | Re-run component function | Signal notifies subscribers |
| Scope of work | Entire component subtree | Only bound DOM nodes |
| Diffing | O(n) VDOM tree diff | None — direct DOM write |
| Allocations per update | New VDOM nodes, new closures | None — subscription exists |

### Terminal: viewport-based rendering

For terminal UIs with large content (e.g., a chat interface with thousands of messages, or a tool like Claude Code with long contexts), the critical optimization is making all work proportional to **viewport size**, not **content size**.

The conversation data lives in signals (`Signal[List[Message]]`). The scroll position is a signal. The layout engine only processes messages that fall within the current viewport:

```
┌──────────────────────────┐
│ ... thousands of lines   │ ← not laid out, just raw data
│ ... above the viewport   │
├──────────────────────────┤
│ visible line 1           │ ← only these ~50 lines get
│ visible line 2           │   layout + cell buffer + diff
│ ...                      │
│ visible line 50          │
├──────────────────────────┤
│ ... lines below viewport │ ← not laid out
└──────────────────────────┘
```

**Streaming tokens** — the hot path for AI-like applications. When a response streams token by token:

- The current message's signal updates: `message.update(_ + token)`
- Only the visible portion of that message needs re-layout (5-10 lines of word-wrapping)
- Diff catches the changed cells, emits a handful of ANSI writes
- Cost is O(viewport), not O(total content)

**Terminal resize** changes viewport dimensions — re-layout the visible window at the new width. Infrequent event, small amount of work.

**Key techniques:**

- Store messages as raw data, not pre-laid-out cells
- Compute layout only for the visible window
- Estimate heights for scroll position (`chars / terminal_width`) rather than exact layout of everything above
- Cache laid-out blocks — once a message is complete (not streaming), its layout is stable until resize

This is virtualized scrolling — the same technique that makes large lists performant in web UIs. The `Foreach`/scroll component needs a virtualization-aware variant, rendering only items whose estimated position overlaps the viewport.

### Comparison with React/Ink (terminal)

React/Ink renders to terminal by re-running the entire component tree on every state change — similar to React on the web. For small UIs this is fine (terminal UIs are tiny), but for large scrollable content it has the same problem: layout computation over the entire content on every update.

kyo-ui's terminal backend does full re-layout too, but only of the **visible viewport**. The cell buffer is always terminal-sized (e.g., 200×50 = 10,000 cells). The diff is cell-by-cell — if one character changes, one cursor move + one character write. Ink diffs line by line and rewrites entire changed lines.

For small static TUIs the difference is negligible. For streaming content with large history (chat, logs, AI tools), viewport-based rendering is the difference between constant-time and linear-time updates.

---

## 10. Future Phases

### Phase 3 — Terminal Backend

Pure Scala ANSI renderer, shared across JVM/JS/Native. No external libraries.

Core: character cell buffer (`Cell` = char + fg + bg + modifiers), double-buffered rendering, cell-by-cell diff algorithm, ANSI escape sequence output with cursor/style optimization. Constraint-based layout engine mapping UI elements to terminal rectangles. Focus management for interactive elements (Tab navigation, Enter to activate, keypress input). Input parsing for escape sequences (arrow keys, mouse events).

### Phase 4 — Platform Shims

~30 lines per platform for terminal I/O:

- **JVM**: raw mode via `stty`, terminal size via `stty size`, `System.out`/`System.in`
- **JS (Node)**: `process.stdin.setRawMode(true)`, `process.stdout.columns/rows`
- **Native**: `termios` C FFI for raw mode, `ioctl(TIOCGWINSZ)` for size

Everything else — cell buffer, diff, ANSI output, layout, input parsing — is shared pure Scala.

### Phase 5 — Polish

Virtualized scrolling (viewport-based rendering for large content), borders/padding (box drawing characters), mouse support (click → element lookup → handler), key-based list diffing (`foreachBy`).
