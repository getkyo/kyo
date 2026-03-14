# Pure Lowering Pipeline: Implementation Plan

New backend from scratch. UI and Style APIs stay unchanged.

> **Important:** The old tui2 backend code (RenderCtx, EventDispatch, WidgetRegistry, Terminal.scala, InputParser.scala, InputEvent, SignalCollector.scala, ColorUtils.scala, ResolvedTheme.scala, and all old backend/renderer files) has been **deleted**. Do not search for or reference any of these files — they no longer exist in the codebase. The motivation section below describes the old architecture's problems for context only.

---

## Motivation

The previous tui2 renderer (now deleted) accumulated architectural problems across multiple sessions. Every bug fix followed the same pattern: state added to `RenderCtx` (a god object visible to all widgets), special cases added to `EventDispatch` (a central switch statement), fixes applied to whichever file was open rather than where responsibility belongs.

**The pattern:** Select dropdown required 9 mutable fields on RenderCtx, code in 6 files, 11 separate code locations. A developer touching any of them must understand all 11 to avoid breaking the dropdown.

**Root causes:**
1. **No widget encapsulation** — a widget's behavior is split across Widget.render, Widget.handleKey/handleMouse/handleClick, WidgetRegistry anonymous classes, EventDispatch special cases, RenderCtx state storage, and backend-specific render pipeline hooks
2. **RenderCtx is a god object** — carries rendering infrastructure, inherited styles, interaction targets, widget-specific state, layout scratch buffers, scroll state, position caches, and signal cache. Every widget can read/write everything.
3. **Centralized imperative dispatch** — EventDispatch handles keyboard routing, mouse hit-testing, focus management, clipboard paste, form submission, dropdown click handling, scroll fallback, and Label.forId redirection in one growing switch statement
4. **Implicit ordering dependencies** — SelectWidget.handleKey must call PickerW before super; EventDispatch.LeftPress must check dropdown before hit-testing; Backend.doRender must call renderDropdown() after overlays but before flush. None enforced by types.

**This redesign eliminates all four** by separating the pipeline into pure functions (Lower → Style → Layout → Paint → Composite → Diff), encoding widget behavior as closures during Lower so dispatch has no widget-type awareness, and using `WidgetKey`-keyed `SignalRef.Unsafe` for all persistent state instead of god-object fields.

---

## Design Goals

This redesign targets three properties — **simple**, **modular**, and **safe** — measured by concrete criteria, not aspirational hand-waving.

### Simple

Every pipeline step is a pure function: input → output. No ambient context, no god objects, no implicit ordering between steps. A developer reading any one file can understand it without reading the others. The complexity ceiling is set by the hardest single step (Lower, ~800 lines), not by cross-cutting interactions between steps.

**Concrete criteria:**
- Each pipeline step fits in a single file with a single public entry point
- No step reads or writes shared mutable state (except Lower reading reactive refs, and Dispatch writing them)
- No implicit ordering dependencies — steps compose as `diff ∘ composite ∘ paint ∘ layout ∘ style ∘ lower`
- Widget behavior is fully encoded during Lower; downstream steps are widget-type-agnostic
- **No imperative constructs:** No `while`, no `var` for loop control, no `return`. Use `@tailrec def loop`. No `asInstanceOf` — pattern match. No `;`-joined statements.
- **Typed values over primitives:** Use `Length` for sizes, `PackedColor` for colors, enum types for categorical values. No `Int` ordinals, no sentinel values — use `Maybe[T]`.
- **Full quality standards in `PIPELINE-QUALITY-PLAN.md`** — all phases must follow these before submitting for review.

### Modular

Each pipeline step has a well-defined input type, output type, and no hidden dependencies. Steps can be tested independently by constructing their input types directly. Adding a new widget type means adding a case to Lower — no changes to Style, Layout, Paint, Dispatch, or Pipeline.

**Concrete criteria:**
- 10 focused files with single responsibilities (see File Structure)
- Any step can be tested by constructing its input IR directly — no need to run preceding steps
- Adding a widget = adding a match case in Lower. Zero downstream changes.
- No widget-type awareness outside Lower
- Each phase compiles independently (only depends on types from Phase 1)

### Safe

All cross-frame state is `SignalRef.Unsafe` — changes are automatically detected and trigger re-render. The only mutable state is the `WidgetStateCache` (mark-and-sweep per frame — entries for widgets absent from the UI tree are evicted) and frame-local scratch (`prevLayout`, `prevGrid`, `focusableIds` — overwritten each frame). All kyo primitives used are well-tested foundation types with clear safety contracts.

**Concrete criteria:**
- All persistent reactive state is `SignalRef.Unsafe` — no raw `var` for cross-frame state
- `WidgetStateCache` uses mark-and-sweep — entries for widgets absent from the tree are evicted after each frame, preventing unbounded growth
- Frame-local state is overwritten (not accumulated) each frame — no stale leftovers
- IR types are immutable enums/case classes — no mutation after construction
- `CellGrid.cells` is mutable but freshly allocated per frame and never shared between frames
- Handler closures capture `SignalRef.Unsafe` refs, not raw values — always read fresh state

### Review workflow

Each phase is designed for isolated review. The implementer completes a phase, verifies it compiles and all tests pass, then presents it for review. The reviewer only needs to understand:
1. The phase's input/output types (from Phase 1)
2. The phase's single file
3. The phase's test file

No cross-phase review is needed until Phase 9 (Pipeline) and Phase 10 (Integration), which are small orchestration layers.

---

## Design Philosophy (Technical Details)

### Pure pipeline, minimal mutability

The core pipeline is **six pure functions** chained together:

```
Lower     UI → Resolved        (reads SignalRefs, produces immutable tree)
Style     Resolved → Styled    (pure inheritance, no state)
Layout    Styled → Laid         (pure measurement + positioning, no state)
Paint     Laid → CellGrid      (pure cell emission, no state)
Composite CellGrid² → CellGrid (pure merge, no state)
Diff      CellGrid² → Bytes    (pure comparison, no state)
```

Each step takes immutable input, produces immutable output. No step reads or writes shared mutable state. The entire rendering path is a composition: `diff ∘ composite ∘ paint ∘ layout ∘ style ∘ lower`.

### State model: SignalRef everywhere possible

All persistent state across frames is `SignalRef.Unsafe`. Changes to any ref trigger re-render automatically via `SignalCollector`.

| State | Type | Why SignalRef |
|-------|------|---------------|
| Focused element ID | `SignalRef.Unsafe[Maybe[WidgetKey]]` | Dispatch writes, Lower reads for pseudo-state styles. Change triggers re-render to show/hide focus ring. |
| Hovered element ID | `SignalRef.Unsafe[Maybe[WidgetKey]]` | Same pattern — hover styles need re-render. |
| Active element ID | `SignalRef.Unsafe[Maybe[WidgetKey]]` | Same — active/pressed styles. |
| Text cursor position | `SignalRef.Unsafe[Int]` | Cursor movement triggers re-render to update cursor display. |
| Scroll offset | `SignalRef.Unsafe[Int]` | Scroll triggers re-render to shift content. |
| Checkbox checked | `SignalRef.Unsafe[Boolean]` | Toggle triggers re-render. |
| Select expanded | `SignalRef.Unsafe[Boolean]` | Open/close triggers re-render. |
| Select selected index | `SignalRef.Unsafe[Int]` | Selection change triggers re-render. |
| Range slider value | `SignalRef.Unsafe[Double]` | Value change triggers re-render. |

### Where SignalRef doesn't apply

Two pieces of state are **not** `SignalRef` because they're frame-local outputs, not cross-frame reactive state:

| State | Type | Why not SignalRef |
|-------|------|-------------------|
| `focusableIds` | `Chunk[WidgetKey]` | Rebuilt from scratch every frame by Lower. Not reactive — it's a product of the current tree shape, not an input to rendering. Set once per frame, read by Dispatch for Tab cycling. |
| `prevLayout` / `prevGrid` | `Maybe[LayoutResult]` / `Maybe[CellGrid]` | Previous frame's output, used only by Diff and Dispatch. Overwritten every frame. Not reactive — they don't trigger re-render, they're consumed after render completes. |

### Where mutable state exists

| State | Type | Why mutable |
|-------|------|-------------|
| `WidgetStateCache` | `HashMap[String, Any]` | Stores `SignalRef.Unsafe` values keyed by element identity. The *refs themselves* are reactive (SignalRef), but the *cache* mapping identity → ref is a plain HashMap because: (1) it's written during Lower and swept after, (2) reads are by key, (3) making the cache itself a Signal would mean every widget state creation triggers a re-render, which is circular. Uses mark-and-sweep: `getOrCreate` marks accessed keys, sweep after the frame removes entries for widgets no longer in the tree. This prevents unbounded growth from Foreach items that disappear. Hidden widgets survive because Lower runs widget expansion (calling `getOrCreate`) even for hidden elements, discarding the result afterward. |

### What exists (retained from old codebase)
- `UI.scala` — user-facing AST
- `Style.scala` — user-facing style DSL
- `Theme.scala` — theme definitions
- `UIDsl.scala` — DSL helpers
- `UIBackend.scala` — backend trait
- `UISession.scala` — session type

> **Note:** All old tui2 internal code has been deleted (RenderCtx, EventDispatch, WidgetRegistry, Terminal.scala, InputParser.scala, InputEvent, SignalCollector.scala, ColorUtils.scala, ResolvedTheme.scala). These must be rebuilt as part of this plan or replaced by new pipeline equivalents. Do not search for them.

---

## Design Decisions

### Why there is no separate Resolve step

Earlier versions had `Lowered` (with signals) → `Resolved` (concrete). This required two IRs and two tree walks. Since `lower` already walks the UI tree for widget expansion, it evaluates signals in the same pass:
- `SignalRef`: direct read via `.unsafe.get()` — no effect overhead
- Derived `Signal`: `Sync.Unsafe.evalOrThrow(signal.current)` — rare since most user code uses `SignalRef` directly

One tree walk, one IR, one responsibility: "make the UI concrete."

### Focus, hover, active, and disabled via refs and lower

All interaction state lives in `ScreenState` as `SignalRef.Unsafe` refs:
- `focusedId: Maybe[WidgetKey]` — which element has focus
- `hoveredId: Maybe[WidgetKey]` — which element the mouse is over
- `activeId: Maybe[WidgetKey]` — which element is being pressed

**Dispatch writes** these refs in response to events (click, Tab, mouse move, mouse down/up). **Lower reads** them to bake pseudo-state styles: for each element, if its WidgetKey matches `focusedId`/`hoveredId`/`activeId`, the corresponding `:focus`/`:hover`/`:active` style variant is merged into the base style.

**Disabled** is per-element (attribute on the UI node, possibly `Signal[Boolean]`). Lower evaluates it, merges `:disabled` style, sets `handlers.disabled = true`, and skips the element from `focusableIds`. Dispatch checks `handlers.disabled` before firing any handler.

This means the style step is pure inheritance — no interaction logic. Dispatch is simple — it updates refs and fires handlers, with no style knowledge.

### Event bubbling via handler composition in Lower

Rather than walking the tree at dispatch time, bubbling is pre-composed during lowering. Lower threads parent handlers down the tree and composes them with each child's handlers via `.andThen`. A leaf node's `onClick` handler is its own handler chained with its parent's, grandparent's, etc. Dispatch just finds the target and calls its handler — no path walking needed.

| Event | Composition | Rationale |
|-------|-------------|-----------|
| `onClick` | **Compose** — `child.andThen(parent)` | Standard bubbling: inner fires first, then outer |
| `onClickSelf` | **Not composed** — stays on declaring node | Target-only by definition |
| `onKeyDown` / `onKeyUp` | **Compose** — `child.andThen(parent)` | Escape-to-close, form shortcuts |
| `onSubmit` | **Woven into onKeyDown** — Form's onSubmit becomes an Enter-key handler composed into non-textarea descendants' onKeyDown | No separate form-finding at dispatch time |
| `onScroll` | **Override** — child replaces parent, not composes | Only innermost scrollable should scroll |
| `onFocus` / `onBlur` | **Not composed** — element-specific | Not bubbling events |
| `onInput` / `onChange` | **Not composed** — widget-specific | Fire on the widget itself |

This means `hitTest` returns a single node (not a path), and dispatch is a simple match: find target → call handler. All bubbling complexity is in Lower, which already walks the tree.

**Handler types use noop defaults, not Maybe.** Since every node ends up with a handler (its own or inherited from parent), the Maybe distinction is meaningless. All event handler fields are `Unit < Async` or `Event => Unit < Async` with noop defaults. This simplifies composition (just `.andThen`, no pattern matching) and dispatch (just call handler, no `.foreach`).

**onSubmit and Textarea:** Form's `onSubmit` is woven into descendants' `onKeyDown` as an Enter-key handler during lowering. Textarea is the exception — Lower does NOT weave `onSubmit` into Textarea's `onKeyDown` because Enter inserts a newline, not a submission. The `parentOnSubmit` is threaded as a separate parameter through `walk` to enable this selective weaving.

**Disabled nodes:** `disabled` suppresses handlers on the disabled node only. Composition into children happens regardless — a non-disabled child of a disabled parent can still fire its own handlers and the parent's handlers (which are part of its pre-composed chain). This is acceptable for TUI: `disabled` applies only to interactive elements, not container divs.

### Label.forId

Lower preserves `forId: Maybe[String]` on Handlers when lowering a `Label` element. Dispatch checks it on click: if the hit target has `forId`, dispatch finds the element with matching user-facing `id` attribute via `findByUserId` and sets focus to its `widgetKey`. No special Label logic anywhere else.

### Widget state as SignalRef.Unsafe

All widget internal state is stored as `SignalRef.Unsafe` values in the `WidgetStateCache`:
- **TextInput/Textarea**: cursor position, selection range, scroll offset
- **Select**: expanded (Boolean), selected index, highlight index
- **Checkbox/Radio**: checked state (Boolean for checkbox, group-keyed for radio)
- **RangeInput**: slider value (Double)
- **Scroll containers**: scroll offset (Int)

The pattern is uniform: lower creates refs (via `getOrCreate`), reads them for display, and generates handler closures that update them. Dispatch fires handlers without knowing what they do. Ref changes trigger re-render.

### Two-way value binding

`TextInput.value(signalRef: SignalRef[String])` binds the input both ways:
- **Read**: lower calls `signalRef.unsafe.get()` to get the current text for display
- **Write**: lower generates an `onKeyDown` handler that updates `signalRef` on edits

The generated handler also updates widget-local state (cursor position, selection) in the `WidgetStateCache`. Text editing logic is encapsulated in the handler closure — dispatch fires it generically.

### Widget identity: Frame + dynamic path

Widget state persists across frames in the `WidgetStateCache`, keyed by `WidgetKey`. The key must be stable across frames despite UI elements being recreated each frame.

**Frame (source position):** `Frame` is an opaque type capturing `file:line:column` at compile time via Scala macros. Each element constructor call gets a unique Frame. Since source code doesn't change at runtime, Frame-based keys are inherently stable. This is the same approach Jetpack Compose uses (compiler-generated source-position hash), adapted to Scala's `inline`/macro system.

**Required keys for Foreach:** In a loop, all iterations share the same Frame (same source line). Every major UI framework (React, Compose, SwiftUI, Flutter) requires explicit keys for dynamic lists — there is no fully automatic solution. We follow SwiftUI's approach: Foreach requires a key function, period.

**Explicit `id` is separate.** `elem.id("my-id")` sets a user-facing semantic identifier for `Label.forId` and event targeting. It is NOT used as the `WidgetKey` for state lookup. Widget identity and semantic identity are independent concerns.

### Foreach keying

`Foreach` requires a key function: `key: A => String`. Mandatory — no keyless/index-based mode.

During lowering, each Foreach item appends `key(item)` to the dynamic path:
```scala
val childDynamicPath = dynamicPath.append(keyFn(item))
val childKey = WidgetKey(childFrame, childDynamicPath)
```

User API:
```scala
items.foreachKeyed(_.id) { item => ... }
items.foreachKeyed(_.toString) { ... }   // escape hatch if no natural key
```

### Why Text and Cursor are enum variants, not Tags

Text carries a `value: String`. Cursor carries nothing (just a position after layout). Neither has Style, Handlers, or children. Separate enum variants let each IR carry exactly the data relevant at that stage — no wasted fields.

### ElemTag.Table and table layout

Table layout is fundamentally different from flex: column widths are globally constrained across all rows. Colspan and rowspan require cross-row/cross-column occupancy tracking. This cannot be reduced to flex. Lower preserves table structure as `ElemTag.Table` with row/cell children. Layout dispatches on `ElemTag.Table` and runs a dedicated algorithm. After layout, cells are `Laid.Node` — paint treats them identically.

### Overlay vs Popup positioning

- **`position: overlay`** (FlatStyle.position = 1): escapes flex flow but stays in the parent's clip context. Analogous to CSS `position: absolute`.
- **`ElemTag.Popup`**: extracted to a separate cell grid. Not clipped by any parent. Used for dropdowns/tooltips that must escape scroll containers.

### Scroll containers

Elements with `overflow: scroll` have a scroll offset persisted as `SignalRef.Unsafe[Int]` in the `WidgetStateCache`. During lower, the offset is read and stored in the element's Style (→ `scrollTop`/`scrollLeft` on `FlatStyle`). Layout uses these values to translate child positions. The `onScroll` handler updates the SignalRef.

### Paint: shadows, gradients, and filters

All implementable in 24-bit color terminals:
- **Shadows**: background rectangle offset by (shadowX, shadowY), expanded by shadowSpread. Blur approximated with shade characters (░▒▓).
- **Gradients**: per-cell background color interpolation along the gradient axis.
- **Filters**: applied after painting children as color math on cells within node bounds. Brightness multiplies RGB, contrast scales from midpoint, grayscale converts to luminance, etc.

### Image support

`Img` elements are lowered to a `Resolved.Node` with image data on Handlers. Paint writes the image data to `CellGrid.rawSequences` as positioned raw terminal bytes (kitty/sixel protocol). The cell grid underneath is filled with alt text for terminals without image support.

### Size encoding in FlatStyle

Style supports `Px`, `Pct`, `Em`, and `Auto`. FlatStyle uses `Int` fields:
- `>= 0`: absolute cells (from `Style.Px`)
- `< 0 && > Int.MinValue/2`: percentage, encoded as `-(pct * 100).toInt` (50% = -5000)
- `Int.MinValue`: auto (content-determined or fill-parent)
- `Em`: converted to Px by style step (1em = 1 cell in monospace TUI)

### Color encoding

All color fields use packed 24-bit RGB: `(r << 16) | (g << 8) | b`. Transparent: `-1`. RGBA resolved during style step by blending against parent background, producing opaque RGB.

### Style inheritance rules

**Inherited** (child gets parent's value when not explicitly set):
- Visual: `fg`, `bg`, `bold`, `italic`, `underline`, `strikethrough`, `opacity`, `cursor`
- Text: `textAlign`, `textTransform`, `textWrap`, `textOverflow`, `lineHeight`, `letterSpacing`

**Non-inherited** (default to initial values):
- Box model: all pad/mar/border/round fields
- Layout: direction, justify, align, gap, flex*, width, height, min/max, overflow, position
- Effects: all shadow/gradient/filter/transform/scroll fields

### onChange type erasure

`Handlers.onChange` is typed `Any => Unit < Async` (noop default). Different widget types produce different value types. Lower generates type-correct closures. Dispatch fires generically. Alternative typed callbacks (`onTextChange`, `onCheckedChange`) would scatter widget knowledge into Handlers.

### Signal.asRef

For stable derived signals read frequently, `Signal.asRef` materializes them into a `SignalRef` via a piping fiber. Returns `< (Async & Scope)` — the fiber's lifetime is the caller's scope. Backend runs in `Async & Scope`, so materialized refs attach to the render session's scope naturally.

### What lower does for each UI node type

| UI node type | Lower action |
|---|---|
| `Div`, `Span` | Pass through: theme defaults, pseudo-state styles, recurse |
| `H1`..`H6`, `P`, `Nav`, `Section`, `Main`, `Header`, `Footer`, `Pre`, `Code` | → `ElemTag.Div` with tag-specific theme style |
| `Button`, `Label`, `Li`, `Anchor` | → `ElemTag.Span`/`ElemTag.Div` with theme style. Label preserves `forId`. |
| `Hr` | → `ElemTag.Div` with border-bottom |
| `Br` | → `Resolved.Text("\n")` |
| `Table` | → `ElemTag.Table`. Tr → `ElemTag.Div` (row), Td/Th → `ElemTag.Div` (cell). Colspan/rowspan on Handlers. |
| `Ul`, `Ol` | → `ElemTag.Div`. Li children get list marker prepended. |
| `Form` | → `ElemTag.Div`. Preserves `onSubmit` on Handlers. |
| `Select` | Expand to Div + Popup. State: expanded, selectedIndex, highlightIndex. |
| `Input` variants | Expand to Div + Text + Cursor. State: cursorPos, selection, scrollX. |
| `Textarea` | Like TextInput but multi-line. State adds scrollY. |
| `Checkbox` | Expand to Span with `[x]`/`[ ]`. State: checked. |
| `Radio` | Like Checkbox with group exclusion via `name`. |
| `RangeInput` | Expand to Div with track. State: value. |
| `Img` | → `ElemTag.Div` with ImageData on Handlers. |
| `Reactive(signal)` | Evaluate signal → recurse |
| `Foreach(signal, key, f)` | Evaluate signal → map with f → recurse. Key function determines WidgetKey path. |
| `Fragment(children)` | Flatten: splice children into parent |
| Hidden elements | Filter out |

---

## File Structure

All pipeline code in `shared/` (cross-platform). Only terminal I/O in `jvm/`.

### New files

```
shared/src/main/scala/kyo/internal/tui2/pipeline/
├── IR.scala              # All IR types: Resolved, Styled, Laid, CellGrid, FlatStyle, Handlers
├── ScreenState.scala      # ScreenState + WidgetStateCache
├── Lower.scala           # UI → Resolved
├── Styler.scala          # Resolved → Styled
├── Layout.scala          # Styled → LayoutResult
├── Painter.scala         # LayoutResult → (CellGrid, CellGrid)
├── Compositor.scala      # (base, popup) → CellGrid
├── Differ.scala          # (prev, curr) → Array[Byte]
├── Dispatch.scala        # Event routing: hitTest, findByKey, findByUserId, dispatch
├── Pipeline.scala        # renderFrame + dispatchEvent orchestrator

jvm/src/main/scala/kyo/
├── Tui2Backend.scala     # Backend: terminal I/O + main loop using Pipeline
├── TerminalEmulator.scala # Headless testing harness using Pipeline

shared/src/test/scala/kyo/internal/tui2/pipeline/
├── IRTest.scala
├── LowerTest.scala
├── StylerTest.scala
├── LayoutTest.scala
├── PainterTest.scala
├── CompositorTest.scala
├── DifferTest.scala
├── DispatchTest.scala
├── PipelineTest.scala

jvm/src/test/scala/kyo/
├── PipelineIntegrationTest.scala
```

---

## Implementation Phases

### Phase 0: Signal.asRef (kyo-core prerequisite) ✅

**Goal:** Add `Signal.asRef` method to kyo-core. Materializes any `Signal[A]` into a `SignalRef[A]` via a piping fiber.

**File:** `kyo-core/shared/src/main/scala/kyo/Signal.scala`

```scala
def asRef(using Frame): SignalRef[A] < (Async & Scope) =
    for
        curr <- self.current
        ref  <- Signal.initRef(curr)
        _ <- Fiber.init {
            Loop(curr) { last =>
                self.currentWith { curr =>
                    if last != curr then
                        ref.set(curr).andThen(Loop.continue(curr))
                    else
                        self.nextWith { value =>
                            ref.set(value).andThen(Loop.continue(value))
                        }
                }
            }
        }
    yield ref
```

> **Divergence from original plan:** Uses `streamChanges`-style pattern (poll `current`, then block on `nextWith`) instead of bare `Loop.forever { nextWith }`. This eliminates a race where `set` happens between `Fiber.init` and the first `nextWith` call — the fiber would miss the change and block forever on a fresh promise.

Key properties:
- `Signal` stays resource-free. `asRef` creates a piping fiber — the resource. Returns `< (Async & Scope)`.
- No new unsafe pattern on Signal. `asRef` returns a `SignalRef`, then existing `signalRef.unsafe` / `.unsafe.get()` work.
- Backend lifecycle is natural. `Tui2Backend.render` runs in `Async & Scope`. All materialized refs attach to the render session's scope. Session ends → scope closes → fibers canceled.

**Tests:**
- `asRef` returns ref with current value
- Source signal update propagates to ref
- Scope closure cancels piping fiber
- Works with derived signals (`map`)

**Dependencies:** None.

**Compile isolation:** Self-contained change to `kyo-core`. No kyo-ui dependency.

**Review checkpoint:** ✅ Complete. All 4 tests pass.

---

### Phase 1: IR Types ✅

**Goal:** Define all intermediate representation types as immutable data.

**File:** `shared/src/main/scala/kyo/internal/tui2/pipeline/IR.scala`

Everything in this file is immutable. No `var`, no mutable collections, no side effects.

**Contents:**

```scala
enum ElemTag:
    case Div, Span, Popup, Table

case class Rect(x: Int, y: Int, w: Int, h: Int)

case class ImageData(bytes: IArray[Byte], width: Int, height: Int, alt: String)

/** Stable widget identity for cross-frame state preservation.
  * Composed of Frame (source position, compile-time) + dynamic Foreach path (runtime).
  * See "Widget identity: Frame + dynamic path" in Design Decisions above.
  * Uses UI.KeyEvent directly — no separate KeyEvent type needed.
  */
case class WidgetKey(frame: Frame, dynamicPath: Chunk[String]) derives CanEqual

object WidgetKey:
    def child(parent: WidgetKey, segment: String): WidgetKey =
        WidgetKey(parent.frame, parent.dynamicPath.append(segment))
```

**Handlers** — carried through every IR. Event dispatch operates on the final `Laid` tree, so handlers must survive all transformations. Widget-specific behavior is encoded as closures generated by Lower — dispatch fires them generically.

Event handler fields use noop defaults instead of `Maybe`. With pre-composed bubbling, every node ends up with a handler (its own or inherited from ancestors), so the `Absent` vs `Present` distinction is meaningless. This simplifies composition (just `.andThen`) and dispatch (just call handler).

```scala
case class Handlers(
    widgetKey: Maybe[WidgetKey],            // internal identity (Frame + dynamic path)
    id: Maybe[String],                      // user-facing id (for Label.forId etc.)
    forId: Maybe[String],                   // Label.forId target (user-level string, resolved at dispatch)
    tabIndex: Maybe[Int],
    disabled: Boolean,
    onClick: Unit < Async,                       // pre-composed bubbling chain (inner→outer)
    onClickSelf: Unit < Async,                  // target-only, not composed into children
    onKeyDown: UI.UI.KeyEvent => Unit < Async,     // pre-composed bubbling chain (inner→outer)
    onKeyUp: UI.UI.KeyEvent => Unit < Async,       // pre-composed bubbling chain (inner→outer)
    onInput: String => Unit < Async,       // widget-specific, not composed
    onChange: Any => Unit < Async,          // type-erased, Lower ensures correctness
    onSubmit: Unit < Async,                // form-specific (woven into descendants' onKeyDown by Lower)
    onFocus: Unit < Async,                 // element-specific, not composed
    onBlur: Unit < Async,                  // element-specific, not composed
    onScroll: Int => Unit < Async,         // override semantics (innermost scrollable wins)
    colspan: Int,                           // table cell hint (default 1)
    rowspan: Int,                           // table cell hint (default 1)
    imageData: Maybe[ImageData]             // raw image for paint
)
object Handlers:
    private val noop: Unit < Async = ()
    val empty: Handlers = Handlers(
        widgetKey = Absent, id = Absent, forId = Absent, tabIndex = Absent,
        disabled = false,
        onClick = noop, onClickSelf = noop,
        onKeyDown = _ => noop, onKeyUp = _ => noop,
        onInput = _ => noop, onChange = _ => noop, onSubmit = noop,
        onFocus = noop, onBlur = noop, onScroll = _ => noop,
        colspan = 1, rowspan = 1, imageData = Absent
    )
```

**IR enums** — three stages, each adding information:

```scala
// After lower: concrete tree, all signals evaluated, widgets expanded
enum Resolved:
    case Node(tag: ElemTag, style: Style, handlers: Handlers, children: Chunk[Resolved])
    case Text(value: String)
    case Cursor(charOffset: Int)

// After style: each node has computed visual properties
enum Styled:
    case Node(tag: ElemTag, computed: FlatStyle, handlers: Handlers, children: Chunk[Styled])
    case Text(value: String, computed: FlatStyle)
    case Cursor(charOffset: Int)

// After layout: each node has position and bounds
enum Laid:
    case Node(tag: ElemTag, computed: FlatStyle, handlers: Handlers,
              bounds: Rect, content: Rect, clip: Rect, children: Chunk[Laid])
    case Text(value: String, computed: FlatStyle, bounds: Rect, clip: Rect)
    case Cursor(pos: Rect)

case class LayoutResult(base: Laid, popups: Chunk[Laid])
```

**Cell and CellGrid** — paint output:

```scala
case class Cell(char: Char, fg: Int, bg: Int,
                bold: Boolean, italic: Boolean, underline: Boolean,
                strikethrough: Boolean, dimmed: Boolean)
object Cell:
    val Empty: Cell = Cell('\u0000', 0, 0, false, false, false, false, false)

case class CellGrid(
    width: Int, height: Int,
    cells: Array[Cell],                         // mutable for paint performance
    rawSequences: Chunk[(Rect, Array[Byte])]    // positioned image data
)
object CellGrid:
    def empty(w: Int, h: Int): CellGrid =
        CellGrid(w, h, Array.fill(w * h)(Cell.Empty), Chunk.empty)
```

Note: `CellGrid.cells` is `Array[Cell]` (mutable) for paint performance — Painter writes cells in a single pass without allocating intermediate structures. The `CellGrid` itself is treated as an output value: created, filled by Painter, then consumed immutably by Compositor and Differ.

**FlatStyle** — fully resolved visual and layout properties:

```scala
// Color encoding: packed 24-bit RGB = (r << 16) | (g << 8) | b. Transparent = -1.
// Size encoding: >= 0 = px, < 0 && > MinValue/2 = -(pct*100), MinValue = auto.
// Em converted to px by Styler (1em = 1 cell). Layout resolves pct/auto.
case class FlatStyle(
    // Visual (inheritable)
    fg: Int, bg: Int,
    bold: Boolean, italic: Boolean, underline: Boolean, strikethrough: Boolean,
    opacity: Double, cursor: Int,
    // Shadow (non-inheritable)
    shadowX: Length.Px, shadowY: Length.Px, shadowBlur: Length.Px, shadowSpread: Length.Px,
    shadowColor: PackedColor,
    // Gradient (non-inheritable)
    gradientDirection: Maybe[Style.GradientDirection], gradientStops: Chunk[(PackedColor, Double)],
    // Filters (non-inheritable)
    brightness: Double, contrast: Double, grayscale: Double, sepia: Double,
    invert: Double, saturate: Double, hueRotate: Double, blur: Length.Px,
    // Transform (non-inheritable)
    translateX: Length, translateY: Length,
    // Text (inheritable)
    textAlign: Style.TextAlign, textTransform: Style.TextTransform,
    textWrap: Style.TextWrap, textOverflow: Style.TextOverflow,
    lineHeight: Int, letterSpacing: Length,
    // Box model (non-inheritable)
    padTop: Length, padRight: Length, padBottom: Length, padLeft: Length,
    marTop: Length, marRight: Length, marBottom: Length, marLeft: Length,
    borderTop: Length.Px, borderRight: Length.Px, borderBottom: Length.Px, borderLeft: Length.Px,
    borderStyle: Style.BorderStyle,
    borderColorTop: PackedColor, borderColorRight: PackedColor,
    borderColorBottom: PackedColor, borderColorLeft: PackedColor,
    roundTL: Boolean, roundTR: Boolean, roundBR: Boolean, roundBL: Boolean,
    // Layout (non-inheritable)
    direction: Style.FlexDirection, justify: Style.Justification,
    align: Style.Alignment, gap: Length,
    flexGrow: Double, flexShrink: Double, flexWrap: Style.FlexWrap,
    width: Length, height: Length,
    minWidth: Length, maxWidth: Length, minHeight: Length, maxHeight: Length,
    overflow: Style.Overflow, scrollTop: Int, scrollLeft: Int, position: Style.Position
)
```

**Type helpers:**

- `Length` — standalone ADT in `kyo-ui/shared/src/main/scala/kyo/Length.scala` with `Px | Pct | Em | Auto`. Methods: `resolve(length, parentPx)`, `resolveOrAuto(length, parentPx)`, `toPx(length)`. Extensions: `N.px`, `N.pct`, `N.em`.
- `PackedColor` — opaque type over `Int` in IR.scala. `PackedColor(r, g, b)`, `.r`/`.g`/`.b`/`.raw` extensions, `PackedColor.Transparent`, `PackedColor.fromStyle(color, parentBg)`.
- `Rect` — `intersect(other)` instance method for clip computation.

**Tests (IRTest.scala):**
- Construct each IR variant, verify field access
- `Handlers.empty` defaults: `colspan=1, rowspan=1, imageData=Absent, disabled=false`, all event handlers are noops
- `CellGrid.empty` produces correct dimensions with `Cell.Empty` cells
- `Length` round-trips: `pct(50.0)` → `resolve(_, 100)` = 50
- `PackedColor` round-trips: `pack(r, g, b)` → `r(c), g(c), b(c)` correct
- `FlatStyle.Default` produces sensible defaults
- `WidgetKey` construction and `WidgetKey.child` produce correct frame/path values
- All three IR enum variants (`Resolved`, `Styled`, `Laid`) constructible with correct fields

**Dependencies:** None (only kyo-data types: `Chunk`, `Maybe`, `Frame`).

**Compile isolation:** Defines types only — no logic beyond constructors and encoding helpers. Compiles with just kyo-data on classpath.

**Estimated size:** ~350 lines

**Review checkpoint:** ✅ Complete. All 34 tests pass. Refactored: `Length` → `Length` types, `PackedColor` → `PackedColor` opaque type, all enum fields typed (not Int ordinals), `gradientDirection: Maybe[Style.GradientDirection]`.

---

### Phase 2: ScreenState + WidgetStateCache + ResolvedTheme

**Goal:** Define persistent cross-frame state and resolved theme. All reactive state as `SignalRef.Unsafe`.

**File:** `pipeline/ScreenState.scala`

```scala
/** Resolved theme values for TUI rendering. Pre-resolves colors from the Theme enum
  * so Lower and Pipeline don't need to pattern-match on Theme variants repeatedly.
  *
  * - `variant` controls which elements get themed (Default=rich, Minimal=moderate, Plain=none)
  * - Color fields provide the resolved values for how to style them
  */
case class ResolvedTheme(
    variant: Theme,
    fg: Int,                    // root fg color (packed RGB) — inherited by entire tree
    bg: Int,                    // root bg color (packed RGB or Transparent)
    borderColor: Style.Color,   // borders: buttons, inputs, hr, popups
    highlightBg: Style.Color,   // select dropdown highlight background
    highlightFg: Style.Color    // select dropdown highlight foreground
)

object ResolvedTheme:
    def resolve(theme: Theme): ResolvedTheme = theme match
        case Theme.Default =>
            ResolvedTheme(
                variant = Theme.Default,
                fg = PackedColor.pack(255, 255, 255),
                bg = PackedColor.Transparent,
                borderColor = Style.Color.rgb(128, 128, 128),
                highlightBg = Style.Color.rgb(0, 0, 255),
                highlightFg = Style.Color.rgb(255, 255, 255)
            )
        case Theme.Minimal =>
            ResolvedTheme(
                variant = Theme.Minimal,
                fg = PackedColor.pack(255, 255, 255),
                bg = PackedColor.Transparent,
                borderColor = Style.Color.rgb(128, 128, 128),
                highlightBg = Style.Color.rgb(0, 0, 255),
                highlightFg = Style.Color.rgb(255, 255, 255)
            )
        case Theme.Plain =>
            ResolvedTheme(
                variant = Theme.Plain,
                fg = PackedColor.pack(255, 255, 255),
                bg = PackedColor.Transparent,
                borderColor = Style.Color.rgb(128, 128, 128),
                highlightBg = Style.Color.rgb(0, 0, 255),
                highlightFg = Style.Color.rgb(255, 255, 255)
            )

/** Produce root FlatStyle from resolved theme. Sets inherited fg/bg for the tree. */
object FlatStyle:
    def fromTheme(theme: ResolvedTheme): FlatStyle =
        Default.copy(fg = theme.fg, bg = theme.bg)
```

```scala
/** All persistent state across frames. Owned by the backend session.
  *
  * Reactive state (SignalRef.Unsafe): changes trigger re-render.
  * Frame-local state (var): overwritten each frame, not reactive.
  */
class ScreenState(val theme: ResolvedTheme)(using AllowUnsafe):
    // ---- Reactive: changes trigger re-render ----
    val focusedId: SignalRef.Unsafe[Maybe[WidgetKey]]  = SignalRef.Unsafe.init(Absent)
    val hoveredId: SignalRef.Unsafe[Maybe[WidgetKey]]   = SignalRef.Unsafe.init(Absent)
    val activeId: SignalRef.Unsafe[Maybe[WidgetKey]]    = SignalRef.Unsafe.init(Absent)

    // ---- Widget state cache (contains SignalRefs, but cache itself is not reactive) ----
    val widgetState: WidgetStateCache = new WidgetStateCache

    // ---- Frame-local: rebuilt or overwritten each frame ----
    var focusableIds: Chunk[WidgetKey]    = Chunk.empty    // set by Lower each frame
    var prevLayout: Maybe[LayoutResult]   = Absent         // consumed by Dispatch + Diff
    var prevGrid: Maybe[CellGrid]         = Absent         // consumed by Diff
```

```scala
/** Per-widget state cache. Maps WidgetKey → SignalRef.Unsafe values.
  *
  * Mark-and-sweep lifecycle:
  *   - beginFrame(): clears the accessed set
  *   - getOrCreate(): marks the key as accessed, creates if absent
  *   - sweep(): removes entries not accessed this frame
  *
  * The refs inside are reactive (changes trigger re-render). The cache
  * itself is a plain HashMap because making it a Signal would mean every
  * widget state creation triggers a re-render, which is circular.
  *
  * Hidden widgets survive: Lower runs widget expansion even for hidden
  * elements (the result is discarded), so getOrCreate marks their keys.
  * Only widgets truly absent from the UI tree are evicted.
  *
  * Dict (kyo-data immutable map) is not suitable here since the cache
  * requires mutable mark-and-sweep semantics.
  */
class WidgetStateCache:
    private val map = new java.util.HashMap[WidgetKey, Any]
    private val accessed = new java.util.HashSet[WidgetKey]

    def beginFrame(): Unit = accessed.clear()

    def getOrCreate[S](key: WidgetKey, init: => S)(using AllowUnsafe): S =
        accessed.add(key)
        val existing = map.get(key)
        if existing != null then existing.asInstanceOf[S]
        else
            val v = init
            map.put(key, v)
            v

    def get[S](key: WidgetKey): Maybe[S] =
        val v = map.get(key)
        if v != null then Maybe(v.asInstanceOf[S]) else Absent

    def sweep(): Unit =
        map.keySet().removeIf(k => !accessed.contains(k))
```

**Tests:**
- `getOrCreate` creates on first call, returns same instance on second
- `getOrCreate` with different keys returns different instances
- `get` returns `Absent` for missing key, `Present` for existing
- `sweep` removes entries not accessed since `beginFrame`
- `sweep` preserves entries accessed since `beginFrame`
- `beginFrame` + `getOrCreate` + `sweep` cycle: accessed keys survive, others evicted
- Multiple frames: entry created in frame 1, not accessed in frame 2, evicted after frame 2 sweep
- `ScreenState` refs initialize to `Absent`
- `focusableIds` starts empty
- `prevLayout` and `prevGrid` start as `Absent`
- `ResolvedTheme.resolve` produces correct values per variant
- `FlatStyle.fromTheme` sets fg/bg from theme

**Dependencies:** Phase 0 (SignalRef.Unsafe), Phase 1 (IR types for `WidgetKey`, `LayoutResult`, `CellGrid`, `FlatStyle`, `PackedColor`).

**Compile isolation:** Only depends on types from Phase 1 and `SignalRef.Unsafe` from kyo-core. No pipeline logic.

**Estimated size:** ~130 lines

**Review checkpoint:** ✅ Complete. All 15 tests pass (split across ScreenStateTest + WidgetStateCacheTest). `WidgetStateCache` in its own file.

---

### Phase 3: Lower

**Goal:** Transform `UI → LowerResult(Resolved, focusableIds)`.

**File:** `pipeline/Lower.scala`

This is the largest step. It bridges the gap between the user-facing `UI` AST and the pure pipeline. It is the **only** step that reads `SignalRef.Unsafe` values and the **only** step aware of widget types.

**Purity model:** Lower is a *function* from `(UI, ScreenState) → LowerResult`. It reads reactive refs (focusedId, widgetState) but never writes them. The handler closures it generates *will* write refs when executed by Dispatch — but Lower itself is read-only. It produces an immutable `Resolved` tree.

```scala
object Lower:
    case class LowerResult(tree: Resolved, focusableIds: Chunk[WidgetKey])

    private val noop: Unit < Async = ()
    private val noopKey: UI.KeyEvent => Unit < Async = _ => noop
    private val noopInt: Int => Unit < Async = _ => noop

    /** Compose two Unit < Async handlers: child fires first, then parent. */
    private def compose(child: Unit < Async, parent: Unit < Async): Unit < Async =
        child.andThen(parent)

    /** Compose two keyed handlers: child fires first, then parent (same event). */
    private def composeKeyed(
        child: UI.KeyEvent => Unit < Async,
        parent: UI.KeyEvent => Unit < Async
    ): UI.KeyEvent => Unit < Async =
        e => child(e).andThen(parent(e))

    /** Walk the UI tree, producing a concrete primitive tree.
      *
      * Reads (via AllowUnsafe):
      *   - SignalRef values (user-bound values, reactive styles, conditional rendering)
      *   - ScreenState.focusedId/hoveredId/activeId (for pseudo-state style merging)
      *   - WidgetStateCache (for widget internal state: cursor pos, checked, expanded, etc.)
      *
      * Never writes any state. Handler closures capture refs for deferred writes.
      *
      * Parent handler parameters enable pre-composed bubbling. Each node composes its
      * own handlers with the parent chain, and threads the result to its children.
      * The leaf's handler IS the full bubbling chain — dispatch just calls it.
      */
    def lower(ui: UI, state: ScreenState)(using AllowUnsafe): LowerResult =
        val focusables = ChunkBuilder.init[WidgetKey]
        val tree = walk(ui, state, Chunk.empty, focusables,
                        noop, noopKeyed, noopKeyed, noopInt, noop)
        LowerResult(tree, focusables.result())

    private def walk(
        ui: UI, state: ScreenState, dynamicPath: Chunk[String],
        focusables: ChunkBuilder[WidgetKey],
        parentOnClick: Unit < Async,
        parentOnKeyDown: UI.KeyEvent => Unit < Async,
        parentOnKeyUp: UI.KeyEvent => Unit < Async,
        parentOnScroll: Int => Unit < Async,
        parentOnSubmit: Unit < Async                // threaded separately for selective weaving
    )(using AllowUnsafe): Resolved = ...
```

**Implementation sub-phases:**

#### 3a: Passthrough elements
Elements that map directly to `Resolved.Node` with theme defaults:
- `Div`, `Span` → `ElemTag.Div` / `ElemTag.Span`
- `H1`–`H6`, `P`, `Nav`, `Section`, `Main`, `Header`, `Footer`, `Pre`, `Code` → `ElemTag.Div` + theme style
- `Button`, `Label`, `Li`, `Anchor` → theme style. `Label` preserves `forId`.
- `Hr` → `ElemTag.Div` + border-bottom
- `Br` → `Resolved.Text("\n")`
- `Form` → `ElemTag.Div`, preserves `onSubmit`
- `Ul`, `Ol` → `ElemTag.Div`, Li children get markers
- `Table` → `ElemTag.Table`, Tr/Td/Th mapped with colspan/rowspan on Handlers

For each element:
1. Generate ID (user-provided or `path + "/" + tag + index`)
2. Read `uiStyle` (may be `Signal[Style]` → read via `Sync.Unsafe.evalOrThrow`)
3. Merge theme defaults for the tag
4. Read `state.focusedId/hoveredId/activeId`, merge `:focus/:hover/:active` style variant if ID matches
5. Read `disabled` attribute (may be `Signal[Boolean]`), merge `:disabled` style
6. Extract event handlers from `Attrs` → build base `Handlers` (noop defaults for unset handlers)
7. **Compose bubbling handlers** with parent chain:
   - `onClick`: `compose(base.onClick, parentOnClick)` — bubbles inner→outer
   - `onKeyDown`: `composeKeyed(base.onKeyDown, parentOnKeyDown)` — bubbles inner→outer
   - `onKeyUp`: `composeKeyed(base.onKeyUp, parentOnKeyUp)` — bubbles inner→outer
   - `onSubmit` (Form only): woven into onKeyDown chain as Enter-key handler, threaded to descendants via `parentOnSubmit`
   - `onScroll`: override semantics — if scroll container, use own handler; else inherit parent
   - `onClickSelf`, `onFocus`, `onBlur`, `onInput`, `onChange`: NOT composed — element-specific
8. If `tabIndex >= 0` and not disabled, add ID to `focusables`
9. Recurse children with this node's composed handlers as their parents
10. Filter hidden elements (`hidden` attribute, `displayNone` style)

#### 3b: Reactive constructs
- `Reactive(signal)` → evaluate signal (`Sync.Unsafe.evalOrThrow(signal.current)`), recurse result
- `Foreach(signal, key, f)` → evaluate signal, map items, recurse each. Key function is required — each item appends `key(item)` to the dynamic path.
- `Fragment(children)` → recurse children, flatten into parent's child list
- `Text(value)` → `Resolved.Text(value)`

#### 3c: Widget expansion — TextInput family
Elements: `Input`, `Password`, `Email`, `Tel`, `UrlInput`, `Search`, `NumberInput`, `Textarea`

Widget state (all `SignalRef.Unsafe`, from WidgetStateCache):
```scala
val cursorPos = state.widgetState.getOrCreate(key + "/cursor", SignalRef.Unsafe.init(0))
val scrollX   = state.widgetState.getOrCreate(key + "/scrollX", SignalRef.Unsafe.init(0))
// Textarea adds:
val scrollY   = state.widgetState.getOrCreate(key + "/scrollY", SignalRef.Unsafe.init(0))
```

Read current value:
- `SignalRef[String]`: `.unsafe.get()`
- `String` literal: use directly
- Absent: empty string

Expand to:
```
Resolved.Node(ElemTag.Div, inputStyle, inputHandlers, Chunk(
    Resolved.Text(displayText),
    Resolved.Cursor(cursorOffset),
    Resolved.Text(textAfterCursor)
))
```

`Password`: mask with `•`. Generated `onKeyDown` closure captures `cursorPos`, `scrollX`, value ref and implements: insert, delete, cursor movement, selection. Generated `onInput`/`onChange` fire user callbacks. Widget `onKeyDown` is composed on top of the already-composed chain (user handler + parent chain) via `composeKeyed`. Form `onSubmit` is woven into the chain as an Enter-key handler. Textarea does NOT get `onSubmit` weaving — Enter inserts a newline there.

#### 3d: Widget expansion — Checkbox / Radio
- `Checkbox`: state = `SignalRef.Unsafe[Boolean]` (checked). Expand to `Node(ElemTag.Span, ..., Chunk(Text("[x]" or "[ ]")))`. onClick toggles ref, fires `onChange`. Widget onClick is composed on top of the already-composed parent chain.
- `Radio`: state keyed by group `name` = `SignalRef.Unsafe[Maybe[String]]` (selected value). onClick sets group value.

#### 3e: Widget expansion — Select (dropdown)
State: `expanded: SignalRef.Unsafe[Boolean]`, `selectedIndex: SignalRef.Unsafe[Int]`, `highlightIndex: SignalRef.Unsafe[Int]`

Collapsed: `Node(ElemTag.Div, ..., Chunk(Text(selectedOption.text), Text("▼")))`
Expanded: same + `Node(ElemTag.Popup, ..., Chunk(optionNodes...))`

Toggle uses `onClickSelf` (target-only — clicking an option does NOT re-toggle the dropdown). Widget onKeyDown (ArrowUp/Down, Enter, Escape) is composed on top of the already-composed chain. Option onClick is composed with the Select's parent onClick chain (NOT the toggle).

#### 3f: Widget expansion — RangeInput
State: `value: SignalRef.Unsafe[Double]`. Expand to `Node(ElemTag.Div, trackStyle, handlers, Chunk(Text(trackVisualization)))`. onKeyDown adjusts value by step within min/max.

#### 3g: Widget expansion — Other inputs
- `DateInput`, `TimeInput`, `ColorInput`: text display + specialized onKeyDown
- `FileInput`: text display + onClick triggers file dialog
- `HiddenInput`: filtered out entirely
- `Img`: expand to `ElemTag.Div` Node with `ImageData` on Handlers, alt text as fallback `Text` child

#### 3h: Theme application
```scala
private def themeStyle(elem: UI.Element, theme: ResolvedTheme): Style =
    theme.variant match
        case Theme.Plain => Style.empty
        case Theme.Minimal =>
            elem match
                case _: UI.H1  => Style.bold.padding(1.px, 0.px)
                case _: UI.H2  => Style.bold
                case _: UI.Hr  => Style.borderBottom(1.px, theme.borderColor).width(100.pct)
                case _         => Style.empty
        case Theme.Default =>
            elem match
                case _: UI.H1      => Style.bold.padding(1.px, 0.px)
                case _: UI.H2      => Style.bold
                case _: UI.Button   => Style.border(1.px, theme.borderColor).padding(0.px, 1.px)
                case _: UI.Hr       => Style.borderBottom(1.px, theme.borderColor).width(100.pct)
                case _              => Style.empty
```

**Tests (LowerTest.scala):**
- Passthrough: `div(span("hello"))` → correct `Resolved` tree
- Fragment flattening: `div(fragment(span("a"), span("b")))` → children flattened
- Hidden filtering: `div.hidden(true)(span("x"))` → filtered out
- Hidden widget state survives sweep: `input.hidden(true).value(ref)` → filtered out, but cache entry preserved across frames
- Reactive: signal with `div("hello")` → evaluated
- Foreach: signal of `Chunk("a","b")` → two children
- Foreach keying: cache key includes item key
- TextInput: `input.value("hello")` → Node with Text + Cursor
- Password: `password.value("abc")` → Text("•••")
- Checkbox: `checkbox.checked(true)` → Text("[x]")
- Select collapsed/expanded: correct tree shape
- Focus collection: inputs with `tabIndex(0)` → in `focusableIds`
- Pseudo-state: set `focusedId`, verify focus style merged
- Disabled: `disabled(true)` → `handlers.disabled = true`, not in focusableIds
- **onClick composition**: `div.onClick(A)(div.onClick(B)(span.onClick(C)("x")))` → leaf's onClick = C.andThen(B.andThen(A))
- **onClickSelf not composed**: `div.onClickSelf(A)(span.onClick(B)("x"))` → leaf's onClick = B (A not in chain)
- **onKeyDown composition**: nested `div.onKeyDown(escape)(input.onKeyDown(log)("x"))` → input's onKeyDown = widget.andThen(log.andThen(escape))
- **TextInput handler composition**: `input.onKeyDown(userHandler).value(ref)` → widget onKeyDown composes with userHandler + parent chain
- **Select onClickSelf**: select toggle uses onClickSelf — option click doesn't fire toggle
- **Form onSubmit weaving**: `form.onSubmit(submit)(input("x"))` → input's onKeyDown includes Enter→submit
- **Textarea skips onSubmit**: `form.onSubmit(submit)(textarea("x"))` → textarea's onKeyDown does NOT include Enter→submit
- **onScroll override**: nested scroll containers → innermost handler wins, not composed

**Dependencies:** Phase 1 (IR types), Phase 2 (ScreenState). Also reads `UI.scala` types and `Style.scala` types.

**Compile isolation:** Depends on Phase 1 (IR types) and Phase 2 (ScreenState) only. Produces `Resolved` — does not import Styler, Layout, or any downstream step.

**Estimated size:** ~800 lines

**Review checkpoint:** Phase complete when Lower.scala compiles with all 14+ tests passing. This is the largest and most complex phase — review the widget expansion patterns carefully. Each sub-phase (3a–3h) can be reviewed incrementally if preferred. Present for review before starting Phase 4.

---

### Phase 4: Styler

**Goal:** Transform `Resolved → Styled` via pure style inheritance. No state, no side effects.

**File:** `pipeline/Styler.scala`

```scala
object Styler:
    /** Pure function: resolve style inheritance down the tree.
      * No state reads, no side effects.
      */
    def style(node: Resolved, parent: FlatStyle): Styled =
        node match
            case Resolved.Node(tag, userStyle, handlers, children) =>
                val computed = resolve(userStyle, parent)
                val styledChildren = children.map(child => style(child, computed))
                Styled.Node(tag, computed, handlers, styledChildren)
            case Resolved.Text(value) =>
                Styled.Text(value, inheritText(parent))
            case Resolved.Cursor(offset) =>
                Styled.Cursor(offset)
```

**`resolve`:** Start from parent's inherited properties + defaults for non-inherited. Iterate `userStyle.props`, override matching fields:

```scala
private def resolve(userStyle: Style, parent: FlatStyle): FlatStyle =
    // Inherited from parent
    var fg = parent.fg; var bg = parent.bg
    var bold = parent.bold; var italic = parent.italic
    // ... all inheritable properties

    // Non-inherited start at defaults
    var padTop: Length = Length.zero
    var padRight: Length = Length.zero
    // ...
    var width: Length = Length.Auto
    var height: Length = Length.Auto

    // Apply user style props
    val props = userStyle.props
    @tailrec def loop(i: Int): Unit =
        if i < props.size then
            props(i) match
                case Prop.BgColor(c)           => bg = PackedColor.fromStyle(c, parent.bg)
                case Prop.TextColor(c)         => fg = PackedColor.fromStyle(c, parent.bg)
                case Prop.Padding(t, r, b, l)  =>
                    padTop = t
                    padRight = r
                    padBottom = b
                    padLeft = l
                case Prop.Width(v)             => width = v
                case Prop.BorderWidthProp(t, r, b, l) =>
                    borderTop = Length.toPx(t)
                    // ... (Length.toPx for Length.Px fields)
                case Prop.FlexDirectionProp(d) => direction = d  // enum, not .ordinal
                // ... all 56 Prop variants
            loop(i + 1)
    loop(0)
    FlatStyle(fg, bg, bold, italic, ...)
```

**`inheritText`:** Only visual/text properties from parent. No layout, no box model.

**Inheritance rules:**
- **Inherited:** `fg, bg, bold, italic, underline, strikethrough, opacity, cursor, textAlign, textTransform, textWrap, textOverflow, lineHeight, letterSpacing`
- **Non-inherited:** everything else (box model, layout, effects, transform, scroll)

**Tests (StylerTest.scala):**
- Child inherits parent's color when not set
- Child overrides parent's color when set
- Width/padding don't inherit
- Size encoding: `width(50.pct)` → correct Length value
- Color encoding: `color(rgb(255,0,0))` → `fg = 0xFF0000`
- Transparent: `bg(transparent)` → `bg = -1`
- RGBA blending against parent bg
- Text node inherits visual properties
- Cursor passes through unchanged
- fontSize/fontFamily: no-op, no crash
- Gradient stops populated correctly
- Deep inheritance: 5 levels, properties propagate
- All 56 Prop variants handled without error

**Dependencies:** Phase 1 (IR types only — reads `Resolved`, produces `Styled`, uses `FlatStyle`).

**Compile isolation:** Pure function, no ScreenState or SignalRef dependency. Only imports IR types from Phase 1.

**Estimated size:** ~250 lines

**Review checkpoint:** ✅ Complete. All 14 tests pass. Refactored: assigns `Length` directly (no `sizeToInt`), `toPx` helper for `Length.Px` fields, `PackedColor.fromStyle` for colors, FontWeight uses explicit match (no `.ordinal`), one statement per line.

---

### Phase 5: Layout

**Goal:** Transform `Styled → LayoutResult`. Pure measurement + positioning — no state, no side effects.

**File:** `pipeline/Layout.scala`

```scala
object Layout:
    /** Pure function: measure and position every node.
      * No state reads, no side effects, no mutation (except ChunkBuilder).
      */
    def layout(styled: Styled, viewport: Rect): LayoutResult =
        val popups = ChunkBuilder.init[Laid](4)
        val base = arrange(styled, viewport, viewport, popups)
        LayoutResult(base, popups.result())

    private def arrange(
        node: Styled, available: Rect, clip: Rect, popups: ChunkBuilder[Laid]
    ): Laid = ...
```

**Layout algorithm for `Styled.Node`:**

1. **Resolve sizes:** Convert size-encoded fields to absolute cells using `available` as parent reference for percentages.

2. **Content box:**
   ```
   outerW = resolvedWidth (or available.w if auto)
   contentX = available.x + margin.left + border.left + padding.left
   contentW = outerW - horizontalChrome
   ```

3. **Special tags:**
   - `ElemTag.Popup`: extract to `popups`, position below parent. Not in parent's flow.
   - `ElemTag.Table`: delegate to `layoutTable()`.
   - Overlay (`position=1`): skip during flex, position at `content + translate`.

4. **Flex layout:**
   a. **Axis:** `direction == column` → main=Y, cross=X; `direction == row` → main=X, cross=Y.
   b. **Measure:** Recursively compute intrinsic size per child. Text: `width = len * (1 + letterSpacing), height = lineCount * lineHeight`.
   c. **Distribute:** Sum main-axis sizes + gaps. Shrink via `flexShrink` or grow via `flexGrow`. Apply `justify` (start/center/end/spaceBetween/spaceAround/spaceEvenly). Apply `align` (start/center/end/stretch).
   d. **Wrap:** If `flexWrap == wrap`, start new line on overflow.
   e. **Position:** Assign `(x, y, w, h)` per child.
   f. **Recurse:** `arrange()` each child with its assigned rect.

5. **Clip:** If `overflow == hidden|scroll`: `childClip = intersect(clip, contentRect)`.

6. **Scroll:** `scrollTop/scrollLeft != 0` → translate children.

**Table layout:**
1. Collect rows and cells
2. Column count = max cells per row (accounting for colspan)
3. Column width = max intrinsic width per column
4. Colspan: merged cell gets sum of spanned widths
5. Rowspan: mark occupied cells in subsequent rows
6. Distribute remaining width proportionally
7. Position cells at grid coordinates

**Cursor layout:** Track last Text node's bounds. `Cursor(charOffset)` → `Laid.Cursor(Rect(textX + charOffset * (1 + letterSpacing), textY, 1, 1))`.

**Tests (LayoutTest.scala):**
- Simple div: bounds = viewport, text at content origin
- Padding: content shifted, size reduced
- Margin: bounds shifted
- Border: between margin and padding
- Column/row: children stacked/side-by-side with gap
- FlexGrow: fills remaining space
- FlexShrink: proportional shrink
- All justify variants
- All align variants
- FlexWrap: wraps to next line
- Overflow hidden: clip constrained
- Scroll offset: children shifted
- Popup extraction: in popups, not base
- Overlay: at content + translate
- Table: column widths from widest content
- Table colspan/rowspan
- Percentage width: 50% of 100 → 50
- Auto width: content-determined
- Cursor: at correct character offset

**Dependencies:** Phase 1 (IR types only — reads `Styled`, produces `Laid` and `LayoutResult`).

**Compile isolation:** Pure function, no ScreenState or SignalRef dependency. Only imports IR types from Phase 1.

**Estimated size:** ~600 lines

**Review checkpoint:** ✅ Complete. All 22 tests pass. Layout uses `Length` types (not `Length`), `available` rect is authoritative, `resolveAvailable` bridges explicit sizes, nested defs for categorization/overlay loops, pattern match instead of `asInstanceOf`.

---

### Phase 6: Painter

**Goal:** Transform `LayoutResult → (base CellGrid, popup CellGrid)`. Pure cell emission — writes to fresh `CellGrid` arrays, no shared state.

**File:** `pipeline/Painter.scala`

```scala
object Painter:
    /** Pure function: emit cells into fresh grids.
      * The only mutation is writing to the CellGrid.cells arrays,
      * which are freshly allocated and not shared.
      */
    def paint(layout: LayoutResult, viewport: Rect): (CellGrid, CellGrid) =
        val base = CellGrid.empty(viewport.w, viewport.h)
        paintNode(layout.base, base)
        val popup = CellGrid.empty(viewport.w, viewport.h)
        layout.popups.foreach(p => paintNode(p, popup))
        (base, popup)
```

**Paint operations:**

**`Laid.Node`:**
1. **Shadow:** Rectangle at `(bounds + shadowOffset)` expanded by `shadowSpread`. Blur via shade chars (░▒▓).
2. **Background:** Solid fill or per-cell gradient interpolation along `gradientDirection`.
3. **Border:** Unicode box-drawing characters (┌┐└┘─│ solid, ┄┆ dashed, ╭╮╰╯ rounded). Per-side border color.
4. **Children:** Recurse, clipping to `clip` rect.
5. **Filters:** After children, apply color math on cells within bounds: brightness, contrast, grayscale, sepia, invert, saturate, hueRotate.
6. **Image:** If `handlers.imageData` present, append to `grid.rawSequences`. Fill cells with alt text fallback.

**`Laid.Text`:**
1. `textTransform` (uppercase/lowercase/capitalize)
2. `textWrap`: split into lines
3. `textAlign` per line (left/center/right/justify)
4. Write characters, skip outside `clip`, add `letterSpacing` gaps
5. `textOverflow == ellipsis`: truncate with "..."

**`Laid.Cursor`:**
1. Read cell at `pos` from grid (what was painted there)
2. Write inverted cell (swap fg/bg). If empty: use block cursor (█).

**Tests (PainterTest.scala):**
- Text: correct cells at correct positions
- Background: all cells in bounds have bg color
- Border: correct characters at edges
- Rounded border: ╭ at top-left
- Gradient: interpolated colors
- Clipping: nothing written outside clip
- Cursor: fg/bg swapped
- TextAlign center: centered
- TextOverflow ellipsis: "hello..."
- LetterSpacing: gaps between chars
- LineHeight: blank rows
- TextTransform: uppercase
- Filters: brightness changes color
- Shadow: offset rectangle

**Dependencies:** Phase 1 (IR types only — reads `Laid`/`LayoutResult`, produces `CellGrid`).

**Compile isolation:** Pure function, no ScreenState or SignalRef dependency. Only imports IR types from Phase 1.

**Estimated size:** ~500 lines

**Review checkpoint:** Phase complete when Painter.scala compiles with all 14+ tests passing. Review focuses on clipping correctness and visual output. Present for review before starting Phase 7.

---

### Phase 7: Compositor + Differ

**Goal:** Merge layers, compare with previous frame, emit ANSI bytes. Both pure functions.

#### 7a: Compositor (`pipeline/Compositor.scala`)

```scala
object Compositor:
    def composite(base: CellGrid, popup: CellGrid): CellGrid =
        val result = CellGrid.empty(base.width, base.height)
        @tailrec def loop(i: Int): Unit =
            if i < base.cells.length then
                result.cells(i) =
                    if popup.cells(i) != Cell.Empty then popup.cells(i)
                    else base.cells(i)
                loop(i + 1)
        loop(0)
        result.copy(rawSequences = base.rawSequences.concat(popup.rawSequences))
```

~20 lines.

#### 7b: Differ (`pipeline/Differ.scala`)

```scala
object Differ:
    private case class TermState(
        fg: Maybe[PackedColor], bg: Maybe[PackedColor],
        bold: Boolean, italic: Boolean, underline: Boolean,
        strikethrough: Boolean, dimmed: Boolean,
        cursorPos: Maybe[(Int, Int)]
    )

    def diff(prev: CellGrid, curr: CellGrid): Array[Byte] =
        val buf = new java.io.ByteArrayOutputStream(curr.width * curr.height * 4)

        @tailrec def eachCol(col: Int, row: Int, term: TermState): TermState = ...
        @tailrec def eachRow(row: Int, term: TermState): TermState =
            if row >= curr.height then term
            else eachRow(row + 1, eachCol(0, row, term))

        discard(eachRow(0, Initial))

        @tailrec def eachRaw(i: Int): Unit = ...
        eachRaw(0)
        buf.toByteArray
```

Named ANSI primitives: `enableBold`, `enableDim`, `enableItalic`, `enableUnderline`, `enableStrikethrough`, `resetAllAttributes`, `moveCursorTo`, `writeFgColor`, `writeBgColor`, `writeChar`, `writeDecimal`. SGR tracking threaded as immutable `TermState` — no mutable vars.

~190 lines.

**Tests:**
- CompositorTest: empty popup → base; popup overrides base; rawSequences concatenated
- DifferTest: identical grids → empty; single change → cursor+SGR+char; same color not re-emitted; full change → all cells

**Dependencies:** Phase 1 (IR types only — reads/produces `CellGrid`).

**Compile isolation:** Pure functions, no ScreenState or SignalRef dependency. Only imports IR types from Phase 1.

**Review checkpoint:** ✅ Complete. 9 tests pass (3 Compositor + 6 Differ). Refactored: `@tailrec` loops, `TermState` with `Maybe[PackedColor]` for colors and `Maybe[(Int, Int)]` for cursor, named ANSI primitives (`enableBold`, `resetAllAttributes`, `moveCursorTo`, etc.).

---

### Phase 8: Dispatch

**Goal:** Route input events to handlers. The only step that **writes** `SignalRef.Unsafe` values (focusedId, hoveredId, activeId) and fires handler closures (which write widget state refs).

**File:** `pipeline/Dispatch.scala`

Dispatch is dramatically simplified because all bubbling is pre-composed during Lower. There is no tree walking for event propagation — dispatch just finds the target node and calls its handler. The handler already contains the full bubbling chain (inner→outer).

```scala
object Dispatch:
    /** Pure query: find deepest node at (mx, my). Returns single node, not path.
      * Pre-composed handlers already contain the full bubbling chain.
      * Checks popups first (topmost layer), then base layout.
      */
    def hitTest(layout: LayoutResult, mx: Int, my: Int): Maybe[Laid.Node] =
        // Check popups in reverse order (topmost first)
        @tailrec def loop(i: Int): Maybe[Laid.Node] =
            if i < 0 then hitTestNode(layout.base, mx, my)
            else
                val hit = hitTestNode(layout.popups(i), mx, my)
                if hit.nonEmpty then hit
                else loop(i - 1)
        loop(layout.popups.size - 1)

    private def hitTestNode(node: Laid, mx: Int, my: Int): Maybe[Laid.Node] =
        node match
            case n: Laid.Node =>
                if !contains(n.bounds, mx, my) || outsideClip(n.clip, mx, my) then Absent
                else
                    // Check children in reverse order (topmost first)
                    findInChildren(n.children, n.children.size - 1, mx, my).orElse(Maybe(n))
            case _ => Absent

    /** Pure query: find node by WidgetKey. For focus/blur dispatch. */
    def findByKey(node: Laid, key: WidgetKey): Maybe[Laid.Node] = ...

    /** Pure query: find node by user-facing id. For Label.forId redirect. */
    def findByUserId(node: Laid, id: String): Maybe[Laid.Node] = ...

    /** Side-effecting: route event, update refs, fire handlers.
      * No tree walking for bubbling — handlers are pre-composed by Lower.
      */
    def dispatch(event: InputEvent, layout: LayoutResult, state: ScreenState)(using AllowUnsafe): Unit =
        event match
            // ---- Keyboard ----
            case InputEvent.Key(ke, ctrl, alt, shift) =>
                ke match
                    case UI.Keyboard.Tab =>
                        cycleFocus(state, layout, reverse = shift)
                    case _ =>
                        findFocused(layout, state).foreach { node =>
                            if !node.handlers.disabled then
                                // Pre-composed: fires widget → user → parent → ... chain
                                val keyEvent = UI.KeyEvent(ke, ctrl, shift, alt, meta = false)
                                node.handlers.onKeyDown(keyEvent)
                        }

            // ---- Mouse click ----
            case InputEvent.Mouse(MouseKind.LeftPress, mx, my) =>
                hitTest(layout, mx, my).foreach { node =>
                    if !node.handlers.disabled then
                        // Label.forId redirect
                        node.handlers.forId match
                            case Present(targetId) =>
                                findByUserId(layout.base, targetId).foreach { targetNode =>
                                    setFocus(targetNode, layout, state)
                                }
                            case Absent =>
                                // Update focus
                                if node.handlers.tabIndex.nonEmpty then
                                    setFocus(node, layout, state)
                                // Pre-composed onClick: fires target → parent → ... chain
                                node.handlers.onClick
                                // onClickSelf: target only (not composed into children)
                                node.handlers.onClickSelf
                        state.activeId.unsafe.set(node.handlers.widgetKey)
                }

            // ---- Mouse release ----
            case InputEvent.Mouse(MouseKind.LeftRelease, _, _) =>
                state.activeId.unsafe.set(Absent)

            // ---- Mouse move ----
            case InputEvent.Mouse(MouseKind.Move, mx, my) =>
                val target = hitTest(layout, mx, my)
                state.hoveredId.unsafe.set(target.flatMap(_.handlers.widgetKey))

            // ---- Scroll ----
            case InputEvent.Mouse(MouseKind.ScrollUp | MouseKind.ScrollDown, mx, my) =>
                hitTest(layout, mx, my).foreach { node =>
                    // Pre-composed with override semantics: innermost scrollable wins
                    val delta = if event.isScrollUp then -3 else 3
                    node.handlers.onScroll(delta)
                }

            // ---- Paste ----
            case InputEvent.Paste(text) =>
                findFocused(layout, state).foreach { node =>
                    node.handlers.onInput(text)
                }

    private def findFocused(layout: LayoutResult, state: ScreenState)(using AllowUnsafe): Maybe[Laid.Node] =
        state.focusedId.unsafe.get().flatMap(key => findByKey(layout.base, key))

    private def cycleFocus(state: ScreenState, layout: LayoutResult, reverse: Boolean)(using AllowUnsafe): Unit =
        val ids = state.focusableIds
        if ids.nonEmpty then
            val current = state.focusedId.unsafe.get()
        val currentIdx = current.map(k => ids.indexOf(k)).getOrElse(-1)
        val nextIdx = if reverse then
            if currentIdx <= 0 then ids.size - 1 else currentIdx - 1
        else
            if currentIdx < 0 || currentIdx >= ids.size - 1 then 0 else currentIdx + 1
        val newKey = ids(nextIdx)
        // Fire onBlur on old focused element
        current.flatMap(k => findByKey(layout.base, k)).foreach { old =>
            old.handlers.onBlur
        }
        // Fire onFocus on new focused element
        findByKey(layout.base, newKey).foreach { node =>
            node.handlers.onFocus
        }
        state.focusedId.unsafe.set(Maybe(newKey))

    private def setFocus(node: Laid.Node, layout: LayoutResult, state: ScreenState)(using AllowUnsafe): Unit =
        val oldKey = state.focusedId.unsafe.get()
        val newKey = node.handlers.widgetKey
        if oldKey != newKey then
            oldKey.flatMap(k => findByKey(layout.base, k)).foreach(_.handlers.onBlur)
            node.handlers.onFocus
            state.focusedId.unsafe.set(newKey)
```

**Tests (DispatchTest.scala):**
- hitTest: nested nodes → deepest node; outside → Absent; popup checked first
- findByKey: by WidgetKey → node; not found → Absent
- findByUserId: by user id → node; not found → Absent
- Tab cycling: wraps around, shift-tab backwards, fires onBlur/onFocus
- Click: updates focusedId, fires pre-composed onClick
- onClick composition verified: click leaf → fires leaf + parent + grandparent handlers (all in one call, pre-composed)
- onClickSelf: fires only on the declaring node (not on child clicks)
- onClick + onClickSelf on same node: both fire when node is direct target
- Disabled: no events fired on disabled node
- onKeyDown composition verified: key on focused input → fires widget + user + parent handlers
- Form onSubmit via onKeyDown: Enter in input inside form → onSubmit fires (woven into onKeyDown chain)
- Textarea inside form: Enter → inserts newline, does NOT fire onSubmit
- Scroll: fires innermost scrollable's handler (override semantics, not composed)
- MouseMove → hoveredId updated
- Paste → onInput on focused element
- forId: click label → focuses target element
- Focus change: onBlur fires on old, onFocus fires on new

**Dependencies:** Phase 1 (IR types), Phase 2 (ScreenState). Does NOT depend on Lower, Styler, Layout, Painter, Compositor, or Differ.

**Compile isolation:** Reads `Laid`/`LayoutResult` (output of Layout) and writes `ScreenState` refs. Does not import any upstream pipeline step.

**Estimated size:** ~200 lines (significantly smaller than 350 — no tree walking for bubbling, no form-finding, no scroll container fallback)

**Review checkpoint:** Phase complete when Dispatch.scala compiles with all 17+ tests passing. Review focuses on event routing correctness and forId redirect. Present for review before starting Phase 9.

---

### Phase 9: Pipeline Orchestrator

**Goal:** Wire all steps together. One function for rendering, one for events.

**File:** `pipeline/Pipeline.scala`

```scala
object Pipeline:

    /** Render a single frame.
      *
      * Pure pipeline: lower → style → layout → paint → composite → diff.
      * The only mutations are:
      *   - state.widgetState (mark-and-sweep: beginFrame before Lower, sweep after)
      *   - state.focusableIds (frame-local, rebuilt each frame)
      *   - state.prevLayout / state.prevGrid (frame-local, overwritten each frame)
      *   - CellGrid.cells arrays (freshly allocated, not shared)
      */
    def renderFrame(ui: UI, state: ScreenState, viewport: Rect)(using AllowUnsafe): Array[Byte] =
        state.widgetState.beginFrame()
        val LowerResult(resolved, focusableIds) = Lower.lower(ui, state)
        state.focusableIds = focusableIds
        val rootStyle    = FlatStyle.fromTheme(state.theme)
        val styled       = Styler.style(resolved, rootStyle)
        val layoutResult = Layout.layout(styled, viewport)
        val (base, pop)  = Painter.paint(layoutResult, viewport)
        val composited   = Compositor.composite(base, pop)
        val prev         = state.prevGrid.getOrElse(CellGrid.empty(viewport.w, viewport.h))
        val ansi         = Differ.diff(prev, composited)
        state.prevLayout = Maybe(layoutResult)
        state.prevGrid   = Maybe(composited)
        state.widgetState.sweep()
        ansi

    /** Dispatch an input event against the most recent layout.
      * Fires handler closures that write SignalRef.Unsafe values,
      * triggering re-render.
      */
    def dispatchEvent(event: InputEvent, state: ScreenState)(using AllowUnsafe): Unit =
        state.prevLayout.foreach { layout =>
            Dispatch.dispatch(event, layout, state)
        }
```

**Tests (PipelineTest.scala):**
- End-to-end: `div("hello")` → ANSI bytes contain "hello"
- Reactive: change signal → re-render → different bytes
- Event cycle: click → handler writes ref → next render reflects change
- `dispatchEvent` with no `prevLayout` → no-op (no crash)
- `renderFrame` updates `prevLayout` and `prevGrid`

**Dependencies:** All previous phases (3–8). First step that imports all pipeline modules.

**Compile isolation:** Thin orchestration — only wires steps together. Compiles once all prior phases compile.

**Estimated size:** ~40 lines

**Review checkpoint:** Phase complete when Pipeline.scala compiles with all 5 tests passing. Small file — review focuses on correct wiring. Present for review before starting Phase 10.

---

### Phase 10: Backend Integration

**Goal:** Wire Pipeline into terminal I/O and headless testing.

#### 10a: Tui2Backend (`jvm/src/main/scala/kyo/Tui2Backend.scala`)

```scala
object Tui2Backend extends UIBackend:
    def render(ui: UI, theme: Theme = Theme.Default)(using Frame): UISession < (Async & Scope) =
        val resolved = ResolvedTheme.resolve(theme)
        for
            renderTrigger <- Signal.initRef[Int](0)
            terminal <- Sync.defer { val t = new Terminal; t.enter(); t }
            _ <- Scope.ensure(terminal.exit())
            state    = new ScreenState(resolved)
            signals  = new SignalCollector(256)
            _     <- Sync.defer(doRender(ui, terminal, state))
            fiber <- Fiber.init(mainLoop(ui, terminal, state, signals, renderTrigger))
        yield new UISession(fiber, ...)

    private def doRender(ui: UI, terminal: Terminal, state: ScreenState, cursorOn: Boolean = true)(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        val viewport = Rect(0, 0, terminal.cols, terminal.rows)
        val ansi = Pipeline.renderFrame(ui, state, viewport)
        terminal.outputStream.write(ansi)
        if cursorOn then positionCursor(state, terminal)
        else terminal.hideCursor()
        terminal.flush()
```

Input loop calls `Pipeline.dispatchEvent`. Render loop calls `doRender` on signal change.

#### 10b: TerminalEmulator (`jvm/src/main/scala/kyo/TerminalEmulator.scala`)

Headless testing harness:

```scala
class TerminalEmulator(ui: UI, cols: Int, rows: Int, state: ScreenState):
    def doRender(): Unit =
        import AllowUnsafe.embrace.danger
        Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows))

    def frame: String =
        state.prevGrid.map { grid =>
            (0 until grid.height).map { y =>
                (0 until grid.width).map { x =>
                    val c = grid.cells(y * grid.width + x)
                    if c.char == '\u0000' then ' ' else c.char
                }.mkString
            }.mkString("\n")
        }.getOrElse("")

    def key(k: UI.Keyboard): Unit = dispatchAndRender(InputEvent.Key(k, false, false, false))
    def click(x: Int, y: Int): Unit = dispatchAndRender(InputEvent.Mouse(MouseKind.LeftPress, x, y))
    // ... etc

    private def dispatchAndRender(event: InputEvent): Unit =
        import AllowUnsafe.embrace.danger
        Pipeline.dispatchEvent(event, state)
        doRender()
```

Direct CellGrid → String for text frame extraction.

#### 10c: renderToString

```scala
def renderToString(ui: UI, cols: Int, rows: Int, theme: Theme = Theme.Default)(using Frame): String =
    import AllowUnsafe.embrace.danger
    val state = new ScreenState(ResolvedTheme.resolve(theme))
    Pipeline.renderFrame(ui, state, Rect(0, 0, cols, rows))
    // Read directly from CellGrid — no ANSI parsing
    state.prevGrid.map(gridToString).getOrElse("")
```

**Tests (PipelineIntegrationTest.scala):**
- Render: `div("hello")` → renderToString contains "hello"
- Type text: render input, type, verify value
- Focus cycling: Tab through inputs
- Dropdown: click select, verify popup appears
- Scroll: scroll, verify content shifts
- Reactive: update signal, re-render, verify

**Dependencies:** All previous phases. Terminal I/O must be implemented as part of this phase.

**Compile isolation:** JVM-only. Depends on Pipeline (Phase 9) + Terminal I/O. All pipeline logic is in shared/.

**Estimated size:** ~250 lines total across backend files

**Review checkpoint:** Phase complete when Tui2Backend, TerminalEmulator, and renderToString compile with all 6+ integration tests passing. Final phase — review focuses on terminal I/O correctness and headless testing harness. Present for final review.

---

## Dependency Graph

```
Phase 0 (Signal.asRef) ─────────────────────────────┐
                                                      │
Phase 1 (IR Types) ───────┬──────────────────────────┤
                           │                           │
Phase 2 (ScreenState) ─────┤ depends on Phase 0        │
                           │                           │
                    ┌──────┼──────────────┐            │
                    │      │              │            │
Phase 3 (Lower) ────┤  Phase 4 (Styler) ──┤            │
                    │      │              │            │
                    │  Phase 5 (Layout) ──┤  can       │
                    │      │              │  parallel  │
                    │  Phase 6 (Painter) ─┤            │
                    │      │              │            │
                    │  Phase 7 (Comp+Diff)┤            │
                    │      │              │            │
Phase 8 (Dispatch) ─┤      │              │            │
                    │      │              │            │
                    └──────┼──────────────┘            │
                           │                           │
Phase 9 (Pipeline) ────────┤ depends on ALL above      │
                           │                           │
Phase 10 (Integration) ────┘ depends on Phase 9        │
```

Phases 3–8 all depend only on Phase 1 (IR types) and optionally Phase 2 (ScreenState). They can proceed in parallel.

---

## Size Estimates

| Phase | File(s) | Est. Lines |
|-------|---------|------------|
| 0 | Signal.asRef | 15 |
| 1 | IR.scala | 350 |
| 2 | ScreenState.scala | 80 |
| 3 | Lower.scala | 800 |
| 4 | Styler.scala | 250 |
| 5 | Layout.scala | 600 |
| 6 | Painter.scala | 500 |
| 7 | Compositor.scala + Differ.scala | 230 |
| 8 | Dispatch.scala | 200 |
| 9 | Pipeline.scala | 40 |
| 10 | Tui2Backend + TerminalEmulator | 250 |
| **Total** | **10 files** | **~3315** |

Architecture properties:
- 10 focused files with single responsibilities
- Each pipeline step independently testable with constructed inputs
- No god object — ScreenState holds only reactive refs + frame-local cache
- No widget-type awareness outside Lower
- No implicit ordering dependencies between steps
- Purity: steps 1–6 are pure functions. Step 7 (Dispatch) is the only one that writes SignalRefs.
- Event bubbling pre-composed during Lower — Dispatch is a flat event→handler mapping, no tree walking
- Handler fields use noop defaults (not Maybe) — simpler composition and dispatch

---

## Recommended Execution Order

```
Batch 1:  Phase 0 (Signal.asRef) + Phase 1 (IR types) + Phase 2 (ScreenState)
Batch 2:  Phase 4 (Styler) + Phase 7 (Compositor + Differ)
Batch 3:  Phase 5 (Layout)
Batch 4:  Phase 6 (Painter)
Batch 5:  Phase 3 (Lower)
Batch 6:  Phase 8 (Dispatch) + Phase 9 (Pipeline)
Batch 7:  Phase 10 (Integration)
```

Lower (Phase 3) is scheduled after Phases 4–7 because understanding how downstream steps consume the IR informs how Lower should produce it. But it can be moved earlier if preferred — no hard dependency on implementation knowledge, only on Phase 1 types.

Each batch ends with a review checkpoint. No batch starts until the previous batch passes review.

---

## Detailed Algorithms

### Lower: walk algorithm

The core is a recursive pattern match over the `UI` sealed type. Every branch produces `Resolved` (immutable). Signal reads happen under `AllowUnsafe`.

```scala
private def walk(
    ui: UI, state: ScreenState, dynamicPath: Chunk[String],
    focusables: ChunkBuilder[WidgetKey],
    parentOnClick: Unit < Async,
    parentOnKeyDown: UI.KeyEvent => Unit < Async,
    parentOnKeyUp: UI.KeyEvent => Unit < Async,
    parentOnScroll: Int => Unit < Async,
    parentOnSubmit: Unit < Async
)(using AllowUnsafe): Resolved =
    ui match
        // ---- Leaf nodes ----
        case UI.Text(value) =>
            Resolved.Text(value)

        // ---- Reactive ----
        case UI.Reactive(signal) =>
            val current = Sync.Unsafe.evalOrThrow(signal.current)
            walk(current, state, dynamicPath, focusables,
                 parentOnClick, parentOnKeyDown, parentOnKeyUp, parentOnScroll, parentOnSubmit)

        // ---- Foreach (key function required) ----
        case fe: UI.Foreach[?] =>
            val items = Sync.Unsafe.evalOrThrow(fe.signal.current)
            val keyFn = fe.key.asInstanceOf[Any => String]
            val children = Chunk.from(items.toIndexed.map { item =>
                val childPath = dynamicPath.append(keyFn(item))
                val childUI = fe.render(items.indexOf(item), item.asInstanceOf[fe.type])
                walk(childUI, state, childPath, focusables,
                     parentOnClick, parentOnKeyDown, parentOnKeyUp, parentOnScroll, parentOnSubmit)
            })
            if children.size == 1 then children.head
            else Resolved.Node(ElemTag.Div, Style.empty, Handlers.empty, children)

        // ---- Fragment ----
        case UI.Fragment(span) =>
            val children = lowerChildren(span, state, dynamicPath, focusables,
                parentOnClick, parentOnKeyDown, parentOnKeyUp, parentOnScroll, parentOnSubmit)
            if children.size == 1 then children.head
            else Resolved.Node(ElemTag.Div, Style.empty, Handlers.empty, children)

        // ---- Elements ----
        case elem: UI.Element =>
            lowerElement(elem, state, dynamicPath, focusables,
                         parentOnClick, parentOnKeyDown, parentOnKeyUp, parentOnScroll, parentOnSubmit)
```

```scala
private def lowerElement(
    elem: UI.Element, state: ScreenState, dynamicPath: Chunk[String],
    focusables: ChunkBuilder[WidgetKey],
    parentOnClick: Unit < Async,
    parentOnKeyDown: UI.KeyEvent => Unit < Async,
    parentOnKeyUp: UI.KeyEvent => Unit < Async,
    parentOnScroll: Int => Unit < Async,
    parentOnSubmit: Unit < Async
)(using AllowUnsafe): Resolved =

    // 1. Resolve identity — Frame from element's Attrs + dynamic Foreach path
    val key = WidgetKey(elem.attrs.frame, dynamicPath)

    // 2. Check hidden
    val hidden = elem.attrs.hidden match
        case Maybe.Present(b: Boolean)        => b
        case Maybe.Present(sig: Signal[?])    =>
            Sync.Unsafe.evalOrThrow(sig.asInstanceOf[Signal[Boolean]].current)
        case _ => false

    // 3. Resolve style (may be reactive)
    val userStyle = elem.attrs.uiStyle match
        case s: Style          => s
        case sig: Signal[?]    =>
            Sync.Unsafe.evalOrThrow(sig.asInstanceOf[Signal[Style]].current)

    // 4. Get theme defaults for this element type
    val themeDefaults = themeStyle(elem, state.theme)

    // 5. Merge pseudo-state styles
    val baseStyle = themeDefaults ++ userStyle
    val focusedKey = state.focusedId.unsafe.get()
    val hoveredKey = state.hoveredId.unsafe.get()
    val activeKey  = state.activeId.unsafe.get()

    val withPseudo = mergePseudoStates(baseStyle, key, focusedKey, hoveredKey, activeKey,
        isDisabled(elem))

    // 6. Check disabled
    val disabled = isDisabled(elem)
    val finalStyle = if disabled then
        withPseudo ++ extractPseudoStyle(baseStyle, classOf[Prop.DisabledProp])
    else withPseudo

    // 7. Check displayNone — early exit via if/else, not return
    if finalStyle.find[Prop.HiddenProp].nonEmpty then Resolved.Text("")
    else

    // 8. Build base handlers from Attrs (noop defaults for unset handlers)
    val baseHandlers = buildHandlers(elem, key, disabled)

    // 9. Compose bubbling handlers with parent chain
    //    onClick: compose inner→outer
    val composedOnClick = compose(baseHandlers.onClick, parentOnClick)
    //    onKeyUp: compose inner→outer
    val composedOnKeyUp = composeKeyed(baseHandlers.onKeyUp, parentOnKeyUp)
    //    onKeyDown: compose inner→outer, then weave in Form's onSubmit as Enter handler
    val formOnSubmit: Unit < Async = elem match
        case _: UI.Form => baseHandlers.onSubmit
        case _          => noop
    val childOnSubmit = if formOnSubmit != noop then formOnSubmit else parentOnSubmit
    val submitAsKeyDown: UI.KeyEvent => Unit < Async = (ke: UI.KeyEvent) =>
        if ke.key == UI.Keyboard.Enter then childOnSubmit else noop
    val composedOnKeyDown = composeKeyed(
        baseHandlers.onKeyDown,
        composeKeyed(submitAsKeyDown, parentOnKeyDown)
    )
    //    onScroll: override semantics (innermost scrollable wins)
    val isScrollContainer = finalStyle.has(Prop.Overflow(Overflow.Scroll))
    val scrollRef = if isScrollContainer then
        Maybe(state.widgetState.getOrCreate(
            WidgetKey.child(key, "scroll"), SignalRef.Unsafe.init(0)))
    else Absent
    val composedOnScroll: Int => Unit < Async = scrollRef match
        case Maybe.Present(ref) => (delta: Int) =>
            import AllowUnsafe.embrace.danger
            ref.unsafe.set(math.max(0, ref.unsafe.get() + delta))
        case _ => parentOnScroll

    // 10. Assemble final handlers for THIS node
    val handlers = baseHandlers.copy(
        onClick = composedOnClick,
        onKeyDown = composedOnKeyDown,
        onKeyUp = composedOnKeyUp,
        onScroll = composedOnScroll
        // onClickSelf: unchanged — stays on this node, not composed
        // onFocus/onBlur: unchanged — element-specific, not composed
        // onInput/onChange: unchanged — widget-specific, set by widget expansion
    )

    // 11. Collect focusable (not if hidden — hidden elements aren't focusable)
    if !hidden && handlers.tabIndex.nonEmpty && !disabled then
        focusables.addOne(key)

    // 12. Dispatch to widget expansion or passthrough
    //     Widget expansion runs even for hidden elements so that
    //     getOrCreate marks cache keys — sweep won't evict their state.
    //     The result is discarded if hidden.
    //
    //     Widget expansion receives handlers with already-composed parent chain.
    //     It composes its own widget handlers on top (e.g., TextInput's onKeyDown
    //     composes: widgetKeyHandler.andThen(composedOnKeyDown)).
    val resolved = elem match
        case _: UI.Input | _: UI.Password | _: UI.Email | _: UI.Tel |
             _: UI.UrlInput | _: UI.Search | _: UI.NumberInput =>
            lowerTextInput(elem.asInstanceOf[UI.TextInput], finalStyle, handlers, key, state, childOnSubmit)
        case _: UI.Textarea =>
            // Textarea does NOT get onSubmit weaving — Enter inserts newline
            lowerTextarea(elem.asInstanceOf[UI.Textarea], finalStyle, handlers, key, state)
        case _: UI.Checkbox =>
            lowerCheckbox(elem.asInstanceOf[UI.Checkbox], finalStyle, handlers, key, state)
        case _: UI.Radio =>
            lowerRadio(elem.asInstanceOf[UI.Radio], finalStyle, handlers, key, state)
        case _: UI.Select =>
            lowerSelect(elem.asInstanceOf[UI.Select], finalStyle, handlers, key, state, dynamicPath, focusables,
                composedOnClick, composedOnKeyDown, composedOnKeyUp, composedOnScroll, childOnSubmit)
        case _: UI.RangeInput =>
            lowerRange(elem.asInstanceOf[UI.RangeInput], finalStyle, handlers, key, state)
        case _: UI.Img =>
            lowerImg(elem.asInstanceOf[UI.Img], finalStyle, handlers)
        case _: UI.HiddenInput =>
            Resolved.Text("")  // not rendered
        case _: UI.Hr =>
            Resolved.Node(ElemTag.Div, finalStyle, handlers, Chunk.empty)
        case _: UI.Br =>
            Resolved.Text("\n")
        case _: UI.Table =>
            lowerTable(elem, finalStyle, handlers, state, dynamicPath, focusables,
                composedOnClick, composedOnKeyDown, composedOnKeyUp, composedOnScroll, childOnSubmit)
        case _ =>
            // Passthrough: Div, Span, H1-H6, P, Nav, Section, Form, Button, etc.
            val tag = if isInlineElement(elem) then ElemTag.Span else ElemTag.Div
            val children = lowerChildren(elem.children, state, dynamicPath, focusables,
                composedOnClick, composedOnKeyDown, composedOnKeyUp, composedOnScroll, childOnSubmit)
            Resolved.Node(tag, finalStyle, handlers, children)
    // Hidden check after expansion: widget expansion must run so getOrCreate
    // marks cache keys for sweep. The resolved tree is discarded if hidden.
    if hidden then Resolved.Text("") else resolved

private def mergePseudoStates(
    style: Style, key: WidgetKey,
    focusedKey: Maybe[WidgetKey], hoveredKey: Maybe[WidgetKey], activeKey: Maybe[WidgetKey],
    disabled: Boolean
): Style =
    val withFocus = if focusedKey.contains(key) then
        style ++ extractPseudoStyle(style, classOf[Prop.FocusProp]) else style
    val withHover = if hoveredKey.contains(key) then
        withFocus ++ extractPseudoStyle(style, classOf[Prop.HoverProp]) else withFocus
    if activeKey.contains(key) then
        withHover ++ extractPseudoStyle(style, classOf[Prop.ActiveProp]) else withHover

private def extractPseudoStyle(style: Style, propClass: Class[?]): Style =
    // Find the pseudo-state prop, extract its inner Style
    style.props.collectFirst {
        case p: Prop.HoverProp if propClass == classOf[Prop.HoverProp]       => p.style
        case p: Prop.FocusProp if propClass == classOf[Prop.FocusProp]       => p.style
        case p: Prop.ActiveProp if propClass == classOf[Prop.ActiveProp]     => p.style
        case p: Prop.DisabledProp if propClass == classOf[Prop.DisabledProp] => p.style
    }.getOrElse(Style.empty)

/** Build base handlers from Attrs. Converts Maybe (user API) to noop defaults.
  * These are base handlers BEFORE composition with parent chain.
  * Composition happens in lowerElement after this call.
  */
private def buildHandlers(elem: UI.Element, key: WidgetKey, disabled: Boolean): Handlers =
    val a = elem.attrs
    Handlers(
        widgetKey = Maybe(key),
        id = a.id,
        forId = elem match { case l: UI.Label => l.forId; case _ => Absent },
        tabIndex = a.tabIndex,
        disabled = disabled,
        onClick = a.onClick.getOrElse(noop),
        onClickSelf = a.onClickSelf.getOrElse(noop),
        onKeyDown = a.onKeyDown.getOrElse(noopKeyed),
        onKeyUp = a.onKeyUp.getOrElse(noopKeyed),
        onInput = _ => noop,    // set by widget expansion
        onChange = _ => noop,    // set by widget expansion
        onSubmit = elem match { case f: UI.Form => f.onSubmit.getOrElse(noop); case _ => noop },
        onFocus = a.onFocus.getOrElse(noop),
        onBlur = a.onBlur.getOrElse(noop),
        onScroll = noopInt,     // set by scroll container handling in lowerElement
        colspan = 1, rowspan = 1, imageData = Absent
    )

private def lowerChildren(
    children: kyo.Span[UI], state: ScreenState, dynamicPath: Chunk[String],
    focusables: ChunkBuilder[WidgetKey],
    parentOnClick: Unit < Async,
    parentOnKeyDown: UI.KeyEvent => Unit < Async,
    parentOnKeyUp: UI.KeyEvent => Unit < Async,
    parentOnScroll: Int => Unit < Async,
    parentOnSubmit: Unit < Async
)(using AllowUnsafe): Chunk[Resolved] =
    import scala.annotation.tailrec
    @tailrec def loop(i: Int, acc: Chunk[Resolved]): Chunk[Resolved] =
        if i >= children.size then acc
        else
            val resolved = walk(children(i), state, dynamicPath, focusables,
                parentOnClick, parentOnKeyDown, parentOnKeyUp, parentOnScroll, parentOnSubmit)
            resolved match
                case Resolved.Text("") => loop(i + 1, acc) // skip filtered elements
                case other             => loop(i + 1, acc.append(other))
    loop(0, Chunk.empty)
```

### Lower: TextInput widget expansion

```scala
/** TextInput receives baseHandlers with already-composed parent chain.
  * It composes its widget-specific onKeyDown ON TOP of the existing chain:
  * widgetKeyHandler → user onKeyDown → parent onKeyDown → ... (inner→outer)
  *
  * parentOnSubmit is woven into the onKeyDown chain as an Enter-key handler:
  * Enter in a TextInput submits the enclosing form (if any).
  * Textarea does NOT get this weaving — Enter inserts a newline there.
  */
private def lowerTextInput(
    elem: UI.TextInput, style: Style, baseHandlers: Handlers, key: WidgetKey,
    state: ScreenState, parentOnSubmit: Unit < Async
)(using AllowUnsafe): Resolved =
    // Read current value
    val currentValue: String = elem.value match
        case Maybe.Present(ref: SignalRef[?]) => ref.asInstanceOf[SignalRef[String]].unsafe.get()
        case Maybe.Present(s: String)         => s
        case _                                => ""

    // Widget state from cache (keyed by WidgetKey)
    val cursorPos = state.widgetState.getOrCreate(
        WidgetKey.child(key, "cursor"), SignalRef.Unsafe.init(math.min(currentValue.length, 0)))
    val scrollX = state.widgetState.getOrCreate(
        WidgetKey.child(key, "scrollX"), SignalRef.Unsafe.init(0))

    val cursor = math.max(0, math.min(cursorPos.unsafe.get(), currentValue.length))

    // Display text (mask for password)
    val displayValue = elem match
        case _: UI.Password => "•" * currentValue.length
        case _              => currentValue

    val beforeCursor = displayValue.substring(0, math.min(cursor, displayValue.length))
    val afterCursor  = displayValue.substring(math.min(cursor, displayValue.length))

    // Generate widget onKeyDown handler (cursor movement, character insertion)
    val valueRef = elem.value match
        case Maybe.Present(ref: SignalRef[?]) => Maybe(ref.asInstanceOf[SignalRef[String]])
        case _                                => Absent
    val userOnInput  = elem.onInput
    val userOnChange = elem.onChange

    val widgetOnKeyDown: UI.KeyEvent => Unit < Async = { (ke: UI.KeyEvent) =>
        import AllowUnsafe.embrace.danger
        val currentVal = valueRef.map(_.unsafe.get()).getOrElse(currentValue)
        val pos = math.min(cursorPos.unsafe.get(), currentVal.length)

        ke.key match
            case UI.Keyboard.Backspace if pos > 0 =>
                val newVal = currentVal.substring(0, pos - 1) + currentVal.substring(pos)
                valueRef.foreach(_.unsafe.set(newVal))
                cursorPos.unsafe.set(pos - 1)
                userOnInput.foreach(f => Sync.Unsafe.evalOrThrow(f(newVal)))
            case UI.Keyboard.Delete if pos < currentVal.length =>
                val newVal = currentVal.substring(0, pos) + currentVal.substring(pos + 1)
                valueRef.foreach(_.unsafe.set(newVal))
                userOnInput.foreach(f => Sync.Unsafe.evalOrThrow(f(newVal)))
            case UI.Keyboard.ArrowLeft if pos > 0 =>
                cursorPos.unsafe.set(pos - 1)
            case UI.Keyboard.ArrowRight if pos < currentVal.length =>
                cursorPos.unsafe.set(pos + 1)
            case UI.Keyboard.Home =>
                cursorPos.unsafe.set(0)
            case UI.Keyboard.End =>
                cursorPos.unsafe.set(currentVal.length)
            case UI.Keyboard.Char(c) if !ke.ctrl && !ke.alt =>
                val newVal = currentVal.substring(0, pos) + c + currentVal.substring(pos)
                valueRef.foreach(_.unsafe.set(newVal))
                cursorPos.unsafe.set(pos + 1)
                userOnInput.foreach(f => Sync.Unsafe.evalOrThrow(f(newVal)))
            case _ => ()
    }

    // Weave parentOnSubmit as Enter-key handler into onKeyDown chain
    val submitOnKeyDown: UI.KeyEvent => Unit < Async = (ke: UI.KeyEvent) =>
        if ke.key == UI.Keyboard.Enter then parentOnSubmit else noop

    // Compose: widget → submit → already-composed chain (user + parents)
    // baseHandlers.onKeyDown already contains: user's onKeyDown composed with parent chain
    val composedOnKeyDown = composeKeyed(widgetOnKeyDown,
        composeKeyed(submitOnKeyDown, baseHandlers.onKeyDown))

    val handlers = baseHandlers.copy(
        onKeyDown = composedOnKeyDown,
        onInput = userOnInput.map(f => (s: String) => f(s)).getOrElse(_ => noop),
        onChange = userOnChange.map(f => (v: Any) => f(v.asInstanceOf[String])).getOrElse(_ => noop)
    )

    Resolved.Node(ElemTag.Div, style, handlers, Chunk(
        Resolved.Text(beforeCursor),
        Resolved.Cursor(cursor),
        Resolved.Text(afterCursor)
    ))
```

### Lower: Select widget expansion

```scala
/** Select receives baseHandlers with already-composed parent chain.
  * Toggle uses onClickSelf — NOT composed into children, so clicking
  * an option does NOT re-toggle the dropdown.
  * Option onClick is composed with the Select's parent onClick chain
  * (NOT the toggle), so clicks bubble past the Select correctly.
  */
private def lowerSelect(
    elem: UI.Select, style: Style, baseHandlers: Handlers, key: WidgetKey,
    state: ScreenState, dynamicPath: Chunk[String], focusables: ChunkBuilder[WidgetKey],
    parentOnClick: Unit < Async,
    parentOnKeyDown: UI.KeyEvent => Unit < Async,
    parentOnKeyUp: UI.KeyEvent => Unit < Async,
    parentOnScroll: Int => Unit < Async,
    parentOnSubmit: Unit < Async
)(using AllowUnsafe): Resolved =
    // Collect options from children
    val options = collectOptions(elem.children)

    // Widget state (keyed by WidgetKey)
    val expanded = state.widgetState.getOrCreate(
        WidgetKey.child(key, "expanded"), SignalRef.Unsafe.init(false))
    val selectedIdx = state.widgetState.getOrCreate(
        WidgetKey.child(key, "selected"), SignalRef.Unsafe.init(0))
    val highlightIdx = state.widgetState.getOrCreate(
        WidgetKey.child(key, "highlight"), SignalRef.Unsafe.init(0))

    val isExpanded = expanded.unsafe.get()
    val selIdx = math.max(0, math.min(selectedIdx.unsafe.get(), options.length - 1))
    val hiIdx = highlightIdx.unsafe.get()

    val displayText = if options.nonEmpty then options(selIdx) else ""

    // Widget toggle uses onClickSelf — NOT composed into children
    val toggleClick: Unit < Async = {
        import AllowUnsafe.embrace.danger
        val wasExpanded = expanded.unsafe.get()
        expanded.unsafe.set(!wasExpanded)
        if !wasExpanded then highlightIdx.unsafe.set(selectedIdx.unsafe.get())
    }

    // Widget onKeyDown: navigate when expanded
    val widgetOnKeyDown: UI.KeyEvent => Unit < Async = { (ke: UI.KeyEvent) =>
        import AllowUnsafe.embrace.danger
        if expanded.unsafe.get() then
            ke.key match
                case UI.Keyboard.ArrowDown =>
                    val hi = highlightIdx.unsafe.get()
                    if hi < options.length - 1 then highlightIdx.unsafe.set(hi + 1)
                case UI.Keyboard.ArrowUp =>
                    val hi = highlightIdx.unsafe.get()
                    if hi > 0 then highlightIdx.unsafe.set(hi - 1)
                case UI.Keyboard.Enter =>
                    val hi = highlightIdx.unsafe.get()
                    selectedIdx.unsafe.set(hi)
                    expanded.unsafe.set(false)
                    elem.onChange.foreach(f =>
                        Sync.Unsafe.evalOrThrow(f(options(hi))))
                case UI.Keyboard.Escape =>
                    expanded.unsafe.set(false)
                case _ => ()
    }

    val handlers = baseHandlers.copy(
        onClickSelf = toggleClick,  // target-only: won't fire on option clicks
        // onClick from baseHandlers already has user onClick + parent chain — unchanged
        onKeyDown = composeKeyed(widgetOnKeyDown, baseHandlers.onKeyDown)
    )

    // Build display node
    val displayChildren = Chunk(
        Resolved.Text(displayText),
        Resolved.Text(if isExpanded then " ▲" else " ▼")
    )

    if !isExpanded then
        Resolved.Node(ElemTag.Div, style, handlers, displayChildren)
    else
        // Build popup with option list
        // Option onClick is composed with the Select's PARENT onClick chain
        // (NOT the Select's toggle — toggle is onClickSelf, not in parentOnClick)
        val optionNodes = Chunk.from((0 until options.size).map { i =>
            val optStyle = if i == hiIdx then
                Style.bg(state.theme.highlightBg).color(state.theme.highlightFg)
            else Style.empty
            val pickHandler: Unit < Async = {
                import AllowUnsafe.embrace.danger
                selectedIdx.unsafe.set(i)
                expanded.unsafe.set(false)
                elem.onChange.foreach(f => Sync.Unsafe.evalOrThrow(f(options(i))))
            }
            // Compose option's onClick with Select's parent chain (bubbling)
            val optionOnClick = compose(pickHandler, parentOnClick)
            Resolved.Node(ElemTag.Div, optStyle, Handlers.empty.copy(
                onClick = optionOnClick
            ), Chunk(Resolved.Text(options(i))))
        })
        val popup = Resolved.Node(ElemTag.Popup, Style.border(1.px, state.theme.borderColor), Handlers.empty, optionNodes)
        Resolved.Node(ElemTag.Div, style, handlers, displayChildren.append(popup))

private def collectOptions(children: kyo.Span[UI]): Chunk[String] =
    import scala.annotation.tailrec
    @tailrec def loop(i: Int, acc: Chunk[String]): Chunk[String] =
        if i >= children.size then acc
        else children(i) match
            case UI.Text(v) => loop(i + 1, acc.append(v))
            case elem: UI.Element => loop(i + 1, extractText(elem.children, acc))
            case _ => loop(i + 1, acc)
    loop(0, Chunk.empty)
```

### Styler: complete resolve algorithm

```scala
object Styler:
    def style(node: Resolved, parent: FlatStyle): Styled =
        node match
            case Resolved.Node(tag, userStyle, handlers, children) =>
                val computed = resolve(userStyle, parent)
                val styledChildren = children.map(child => style(child, computed))
                Styled.Node(tag, computed, handlers, styledChildren)
            case Resolved.Text(value) =>
                Styled.Text(value, inheritText(parent))
            case Resolved.Cursor(offset) =>
                Styled.Cursor(offset)

    private def resolve(userStyle: Style, parent: FlatStyle): FlatStyle =
        // ---- Start with inherited values from parent ----
        var fg = parent.fg
        var bg = parent.bg
        var bold = parent.bold
        var italic = parent.italic
        var underline = parent.underline
        var strikethrough = parent.strikethrough
        var opacity = parent.opacity
        var cursorStyle = parent.cursor
        var textAlign = parent.textAlign
        var textTransform = parent.textTransform
        var textWrap = parent.textWrap
        var textOverflow = parent.textOverflow
        var lineHeight = parent.lineHeight
        var letterSpacing = parent.letterSpacing

        // ---- Non-inherited: start at defaults ----
        var shadowX = 0; var shadowY = 0; var shadowBlur = 0
        var shadowSpread = 0; var shadowColor = PackedColor.Transparent
        var gradientDirection = 0
        var gradientStops: Chunk[(Int, Double)] = Chunk.empty
        var brightness = 1.0; var contrast = 1.0; var grayscale = 0.0
        var sepia = 0.0; var invert = 0.0; var saturate = 1.0
        var hueRotate = 0.0; var blur = 0
        var translateX = 0; var translateY = 0
        var padTop = 0; var padRight = 0; var padBottom = 0; var padLeft = 0
        var marTop = 0; var marRight = 0; var marBottom = 0; var marLeft = 0
        var borderTop = 0; var borderRight = 0; var borderBottom = 0; var borderLeft = 0
        var borderStyle = 0
        var borderColorTop = PackedColor.Transparent
        var borderColorRight = PackedColor.Transparent
        var borderColorBottom = PackedColor.Transparent
        var borderColorLeft = PackedColor.Transparent
        var roundTL = false; var roundTR = false; var roundBR = false; var roundBL = false
        var direction = 0; var justify = 0; var align = 0; var gap = 0
        var flexGrow = 0.0; var flexShrink = 1.0; var flexWrap = 0
        var width = Length.Auto; var height = Length.Auto
        var minWidth = 0; var maxWidth = Length.Auto
        var minHeight = 0; var maxHeight = Length.Auto
        var overflow = 0; var scrollTop = 0; var scrollLeft = 0; var position = 0

        // ---- Apply user style props ----
        val props = userStyle.props
        var i = 0
        while i < props.length do
            props(i) match
                case Prop.BgColor(c)             => bg = PackedColor.fromStyle(c, parent.bg)
                case Prop.TextColor(c)           => fg = PackedColor.fromStyle(c, parent.bg)
                case Prop.Padding(t, r, b, l) =>
                    padTop = t
                    padRight = r
                    padBottom = b
                    padLeft = l
                case Prop.Margin(t, r, b, l) =>
                    marTop = t
                    marRight = r
                    marBottom = b
                    marLeft = l
                case Prop.Gap(v)                 => gap = v
                case Prop.Width(v)               => width = v
                case Prop.Height(v)              => height = v
                case Prop.MinWidth(v)            => minWidth = v
                case Prop.MaxWidth(v)            => maxWidth = v
                case Prop.MinHeight(v)           => minHeight = v
                case Prop.MaxHeight(v)           => maxHeight = v
                case Prop.FlexDirectionProp(d)   => direction = d
                case Prop.FlexWrapProp(w)        => flexWrap = w
                case Prop.FlexGrowProp(v)        => flexGrow = v
                case Prop.FlexShrinkProp(v)      => flexShrink = v
                case Prop.Align(v)               => align = v
                case Prop.Justify(v)             => justify = v
                case Prop.OverflowProp(v)        => overflow = v
                case Prop.FontWeightProp(w)      =>
                    bold = w match
                        case FontWeight.bold | FontWeight.w700 |
                            FontWeight.w800 | FontWeight.w900 => true
                        case _ => false
                case Prop.FontStyleProp(s)       => italic = s == FontStyle.italic
                case Prop.TextDecorationProp(d)  =>
                    underline = d == TextDecoration.underline
                    strikethrough = d == TextDecoration.strikethrough
                case Prop.TextAlignProp(v)       => textAlign = v
                case Prop.TextTransformProp(v)   => textTransform = v
                case Prop.TextOverflowProp(v)    => textOverflow = v
                case Prop.TextWrapProp(v)        => textWrap = v
                case Prop.LineHeightProp(v)      => lineHeight = math.max(1, v.toInt)
                case Prop.LetterSpacingProp(v)   => letterSpacing = v
                case Prop.OpacityProp(v)         => opacity = v
                case Prop.CursorProp(v)          => cursorStyle = v
                case Prop.PositionProp(v)        => position = v
                case Prop.TranslateProp(x, y)    =>
                    translateX = x
                    translateY = y
                case Prop.BorderWidthProp(t, r, b, l) =>
                    borderTop = Length.toPx(t)
                    borderRight = Length.toPx(r)
                    borderBottom = Length.toPx(b)
                    borderLeft = Length.toPx(l)
                case Prop.BorderColorProp(t, r, b, l) =>
                    borderColorTop = PackedColor.fromStyle(t, parent.bg)
                    borderColorRight = PackedColor.fromStyle(r, parent.bg)
                    borderColorBottom = PackedColor.fromStyle(b, parent.bg)
                    borderColorLeft = PackedColor.fromStyle(l, parent.bg)
                case Prop.BorderStyleProp(v)     => borderStyle = v
                case Prop.BorderTopProp(w, c)    =>
                    borderTop = Length.toPx(w)
                    borderColorTop = PackedColor.fromStyle(c, parent.bg)
                case Prop.BorderRightProp(w, c)  =>
                    borderRight = Length.toPx(w)
                    borderColorRight = PackedColor.fromStyle(c, parent.bg)
                case Prop.BorderBottomProp(w, c) =>
                    borderBottom = Length.toPx(w)
                    borderColorBottom = PackedColor.fromStyle(c, parent.bg)
                case Prop.BorderLeftProp(w, c)   =>
                    borderLeft = Length.toPx(w)
                    borderColorLeft = PackedColor.fromStyle(c, parent.bg)
                case Prop.BorderRadiusProp(tl, tr, br, bl) =>
                    roundTL = Length.toPx(tl).value > 0
                    roundTR = Length.toPx(tr).value > 0
                    roundBR = Length.toPx(br).value > 0
                    roundBL = Length.toPx(bl).value > 0
                case Prop.ShadowProp(x, y, b, s, c) =>
                    shadowX = Length.toPx(x)
                    shadowY = Length.toPx(y)
                    shadowBlur = Length.toPx(b)
                    shadowSpread = Length.toPx(s)
                    shadowColor = PackedColor.fromStyle(c, parent.bg)
                case Prop.BgGradientProp(dir, colors, positions) =>
                    gradientDirection = Maybe(dir)
                    gradientStops = Chunk.from(Array.tabulate(colors.length) { j =>
                        (PackedColor.fromStyle(colors(j), parent.bg), positions(j))
                    })
                case Prop.BrightnessProp(v)      => brightness = v
                case Prop.ContrastProp(v)        => contrast = v
                case Prop.GrayscaleProp(v)       => grayscale = v
                case Prop.SepiaProp(v)           => sepia = v
                case Prop.InvertProp(v)          => invert = v
                case Prop.SaturateProp(v)        => saturate = v
                case Prop.HueRotateProp(v)       => hueRotate = v
                case Prop.BlurProp(v)            => blur = Length.toPx(v)
                // Pseudo-states already merged by Lower — skip
                case _: Prop.HoverProp           => ()
                case _: Prop.FocusProp           => ()
                case _: Prop.ActiveProp          => ()
                case _: Prop.DisabledProp        => ()
                // TUI-irrelevant — skip
                case _: Prop.FontSizeProp        => ()
                case _: Prop.FontFamilyProp      => ()
                case Prop.HiddenProp             => ()
            loop(i + 1)

        FlatStyle(fg, bg, bold, italic, underline, strikethrough, opacity, cursorStyle,
            shadowX, shadowY, shadowBlur, shadowSpread, shadowColor,
            gradientDirection, gradientStops,
            brightness, contrast, grayscale, sepia, invert, saturate, hueRotate, blur,
            translateX, translateY,
            textAlign, textTransform, textWrap, textOverflow, lineHeight, letterSpacing,
            padTop, padRight, padBottom, padLeft,
            marTop, marRight, marBottom, marLeft,
            borderTop, borderRight, borderBottom, borderLeft, borderStyle,
            borderColorTop, borderColorRight, borderColorBottom, borderColorLeft,
            roundTL, roundTR, roundBR, roundBL,
            direction, justify, align, gap,
            flexGrow, flexShrink, flexWrap,
            width, height, minWidth, maxWidth, minHeight, maxHeight,
            overflow, scrollTop, scrollLeft, position)

    private def inheritText(parent: FlatStyle): FlatStyle =
        // Only inherited properties, everything else at defaults
        FlatStyle(
            fg = parent.fg, bg = PackedColor.Transparent,
            bold = parent.bold, italic = parent.italic,
            underline = parent.underline, strikethrough = parent.strikethrough,
            opacity = parent.opacity, cursor = Style.Cursor.default_,
            shadowX = Length.zero, shadowY = Length.zero,
            shadowBlur = Length.zero, shadowSpread = Length.zero,
            shadowColor = PackedColor.Transparent,
            gradientDirection = Absent, gradientStops = Chunk.empty,
            brightness = 1.0, contrast = 1.0, grayscale = 0.0, sepia = 0.0,
            invert = 0.0, saturate = 1.0, hueRotate = 0.0, blur = Length.zero,
            translateX = Length.zero, translateY = Length.zero,
            textAlign = parent.textAlign, textTransform = parent.textTransform,
            textWrap = parent.textWrap, textOverflow = parent.textOverflow,
            lineHeight = parent.lineHeight, letterSpacing = parent.letterSpacing,
            padTop = Length.zero, padRight = Length.zero,
            padBottom = Length.zero, padLeft = Length.zero,
            marTop = Length.zero, marRight = Length.zero,
            marBottom = Length.zero, marLeft = Length.zero,
            borderTop = Length.zero, borderRight = Length.zero,
            borderBottom = Length.zero, borderLeft = Length.zero,
            borderStyle = Style.BorderStyle.none,
            borderColorTop = PackedColor.Transparent, borderColorRight = PackedColor.Transparent,
            borderColorBottom = PackedColor.Transparent, borderColorLeft = PackedColor.Transparent,
            roundTL = false, roundTR = false, roundBR = false, roundBL = false,
            direction = Style.FlexDirection.row, justify = Style.Justification.start,
            align = Style.Alignment.start, gap = Length.zero,
            flexGrow = 0.0, flexShrink = 1.0, flexWrap = Style.FlexWrap.wrap,
            width = Length.Auto, height = Length.Auto,
            minWidth = Length.zero, maxWidth = Length.Auto,
            minHeight = Length.zero, maxHeight = Length.Auto,
            overflow = Style.Overflow.visible, scrollTop = 0, scrollLeft = 0,
            position = Style.Position.flow)
```

### Layout: flex algorithm

```scala
object Layout:
    import scala.annotation.tailrec

    def layout(styled: Styled, viewport: Rect): LayoutResult =
        val popups = ChunkBuilder.init[Laid](4)
        val base = arrange(styled, viewport, viewport, popups)
        LayoutResult(base, popups.result())

    // ---- Intrinsic size measurement ----

    /** Measure intrinsic width of a node (without layout context). */
    private def measureWidth(node: Styled): Int = node match
        case Styled.Node(_, cs, _, children) =>
            val w = Length.resolve(cs.width, Int.MaxValue)
            if !Length.isAuto(cs.width) && w < Int.MaxValue then
                w
            else
                val chrome = cs.padLeft + cs.padRight + cs.borderLeft + cs.borderRight +
                             cs.marLeft + cs.marRight
                val childrenWidth =
                    if cs.direction == Style.FlexDirection.column then // column
                        @tailrec def maxWidth(i: Int, max: Int): Int =
                            if i >= children.size then max
                            else maxWidth(i + 1, math.max(max, measureWidth(children(i))))
                        maxWidth(0, 0)
                    else // row
                        @tailrec def sumWidth(i: Int, sum: Int): Int =
                            if i >= children.size then sum
                            else sumWidth(i + 1, sum + measureWidth(children(i)) + (if i > 0 then cs.gap else 0))
                        sumWidth(0, 0)
                chrome + childrenWidth
        case Styled.Text(value, cs) =>
            val lines = splitLines(value, Int.MaxValue, cs.textWrap)
            @tailrec def maxLine(i: Int, max: Int): Int =
                if i >= lines.size then max
                else maxLine(i + 1, math.max(max, lines(i).length * (1 + cs.letterSpacing)))
            maxLine(0, 0)
        case Styled.Cursor(_) => 1

    /** Measure intrinsic height of a node given a constrained width. */
    private def measureHeight(node: Styled, availW: Int): Int = node match
        case Styled.Node(_, cs, _, children) =>
            val h = Length.resolve(cs.height, Int.MaxValue)
            if !Length.isAuto(cs.height) && h < Int.MaxValue then h
            else
                val chrome = cs.padTop + cs.padBottom + cs.borderTop + cs.borderBottom +
                             cs.marTop + cs.marBottom
                val contentW = availW - chrome
                val childrenHeight =
                    if cs.direction == Style.FlexDirection.column then // column: sum heights + gaps
                        @tailrec def sumHeight(i: Int, sum: Int): Int =
                            if i >= children.size then sum
                            else sumHeight(i + 1, sum + measureHeight(children(i), contentW) + (if i > 0 then cs.gap else 0))
                        sumHeight(0, 0)
                    else // row: max height
                        @tailrec def maxHeight(i: Int, max: Int): Int =
                            if i >= children.size then max
                            else maxHeight(i + 1, math.max(max, measureHeight(children(i), contentW)))
                        maxHeight(0, 0)
                chrome + childrenHeight
        case Styled.Text(value, cs) =>
            val lines = splitLines(value, availW / math.max(1, 1 + cs.letterSpacing), cs.textWrap)
            lines.size * cs.lineHeight
        case Styled.Cursor(_) => 1

    // ---- Main layout: arrange ----

    private def arrange(
        node: Styled, available: Rect, clip: Rect, popups: ChunkBuilder[Laid]
    ): Laid = node match
        case Styled.Text(value, cs) =>
            val w = math.min(measureWidth(node), available.w)
            val h = measureHeight(node, available.w)
            Laid.Text(value, cs, Rect(available.x, available.y, w, h), clip)

        case Styled.Cursor(charOffset) =>
            // Positioned by parent after preceding Text
            Laid.Cursor(Rect(available.x + charOffset, available.y, 1, 1))

        case Styled.Node(tag, cs, handlers, children) =>
            // 1. Resolve sizes
            val resolvedW = resolveDim(cs.width, available.w)
            val resolvedH = resolveDim(cs.height, available.h)

            // 2. Compute box model rects
            val outerX = available.x + cs.marLeft
            val outerY = available.y + cs.marTop
            val outerW = if resolvedW == Length.Auto then
                available.w - cs.marLeft - cs.marRight
            else resolvedW
            val outerH = resolvedH  // may still be Auto

            val contentX = outerX + cs.borderLeft + cs.padLeft
            val contentY = outerY + cs.borderTop + cs.padTop
            val contentW = outerW - cs.borderLeft - cs.borderRight - cs.padLeft - cs.padRight

            // 3. Separate children by category
            @tailrec def categorize(
                i: Int, flow: Chunk[Styled], overlays: Chunk[Styled]
            ): (Chunk[Styled], Chunk[Styled]) =
                if i >= children.size then (flow, overlays)
                else children(i) match
                    case n: Styled.Node if n.tag == ElemTag.Popup =>
                        val popupRect = Rect(outerX, outerY + math.max(outerH, 0) + 1,
                            available.w, available.h)
                        popups.add(arrange(n, popupRect, Rect(0, 0, available.w, available.h), popups))
                        categorize(i + 1, flow, overlays)
                    case n: Styled.Node if n.style.position == Style.Position.overlay =>
                        categorize(i + 1, flow, overlays.append(n))
                    case other =>
                        categorize(i + 1, flow.append(other), overlays)

            val (flow, overlays) = categorize(0, Chunk.empty, Chunk.empty)

            // 4. Flex layout for flow children
            val laidChildren = if tag == ElemTag.Table then
                layoutTable(flow, contentX, contentY, contentW, clip, popups)
            else
                layoutFlex(flow, cs, contentX, contentY, contentW,
                    if outerH == Length.Auto then Int.MaxValue
                    else outerH - cs.borderTop - cs.borderBottom - cs.padTop - cs.padBottom,
                    clip, cs.overflow, cs.scrollTop, cs.scrollLeft, popups)

            // 5. Compute actual height if auto
            val actualContentH =
                if outerH != Length.Auto then
                    outerH - cs.borderTop - cs.borderBottom - cs.padTop - cs.padBottom
                else
                    @tailrec def maxBottom(j: Int, maxY: Int): Int =
                        if j >= laidChildren.size then maxY - contentY
                        else
                            val bottom = laidChildren(j) match
                                case n: Laid.Node   => n.bounds.y + n.bounds.h
                                case t: Laid.Text   => t.bounds.y + t.bounds.h
                                case c: Laid.Cursor  => c.pos.y + c.pos.h
                            maxBottom(j + 1, math.max(maxY, bottom))
                    maxBottom(0, contentY)

            val actualOuterH = if outerH == Length.Auto then
                actualContentH + cs.borderTop + cs.borderBottom + cs.padTop + cs.padBottom
            else outerH

            // 6. Layout overlay children
            val allChildren =
                if overlays.isEmpty then laidChildren
                else
                    val overlayLaid = overlays.map { styled =>
                        val ov = styled.asInstanceOf[Styled.Node]
                        val ovX = contentX + Length.resolve(ov.style.translateX, contentW)
                        val ovY = contentY + Length.resolve(ov.style.translateY, actualContentH)
                        arrange(ov, Rect(ovX, ovY, contentW, actualContentH), clip, popups)
                    }
                    laidChildren.concat(overlayLaid)

            // 7. Compute clip for children
            val childClip = if cs.overflow == Style.Overflow.hidden || cs.overflow == Style.Overflow.scroll then
                intersect(clip, Rect(contentX, contentY, contentW, actualContentH))
            else clip

            val bounds = Rect(outerX, outerY, outerW, actualOuterH)
            val content = Rect(contentX, contentY, contentW, actualContentH)
            Laid.Node(tag, cs, handlers, bounds, content, childClip, allChildren)

    // ---- Flex distribute algorithm ----

    private def layoutFlex(
        children: Chunk[Styled], cs: FlatStyle,
        cx: Int, cy: Int, cw: Int, ch: Int,
        clip: Rect, overflow: Int, scrollTop: Int, scrollLeft: Int,
        popups: ChunkBuilder[Laid]
    ): Chunk[Laid] =
        if children.isEmpty then Chunk.empty
        else

        val isColumn = cs.direction == Style.FlexDirection.column
        val mainSize = if isColumn then ch else cw
        val crossSize = if isColumn then cw else ch
        val n = children.size

        // Measurement arrays — mutable parallel arrays are the right tool
        // for flex distribution (grow/shrink modifies sizes in-place)
        val childMainSizes = new Array[Int](n)
        val childCrossSizes = new Array[Int](n)
        val childGrow = new Array[Double](n)
        val childShrink = new Array[Double](n)

        // Measure each child
        @tailrec def measure(i: Int, totalMain: Int): Int =
            if i >= n then totalMain
            else
                children(i) match
                    case nd: Styled.Node =>
                        childMainSizes(i) = if isColumn then measureHeight(nd, cw) else measureWidth(nd)
                        childCrossSizes(i) = if isColumn then measureWidth(nd) else measureHeight(nd, cw)
                        childGrow(i) = nd.style.flexGrow
                        childShrink(i) = nd.style.flexShrink
                    case t: Styled.Text =>
                        childMainSizes(i) = if isColumn then measureHeight(t, cw) else measureWidth(t)
                        childCrossSizes(i) = if isColumn then measureWidth(t) else measureHeight(t, cw)
                    case _: Styled.Cursor =>
                        childMainSizes(i) = 1
                        childCrossSizes(i) = 1
                measure(i + 1, totalMain + childMainSizes(i) + (if i > 0 then cs.gap else 0))
        val totalMain = measure(0, 0)

        // Distribute: grow or shrink (mutates childMainSizes in-place)
        val freeSpace = mainSize - totalMain
        if freeSpace > 0 then
            val totalGrow = childGrow.sum
            if totalGrow > 0 then
                @tailrec def grow(j: Int): Unit =
                    if j < n then
                        if childGrow(j) > 0 then
                            childMainSizes(j) += (freeSpace * childGrow(j) / totalGrow).toInt
                        grow(j + 1)
                grow(0)
        else if freeSpace < 0 then
            val totalShrink = childShrink.sum
            if totalShrink > 0 then
                val deficit = -freeSpace
                @tailrec def shrink(j: Int): Unit =
                    if j < n then
                        if childShrink(j) > 0 then
                            childMainSizes(j) = math.max(0, childMainSizes(j) - (deficit * childShrink(j) / totalShrink).toInt)
                        shrink(j + 1)
                shrink(0)

        // Justify: compute starting position and spacing
        @tailrec def sumActual(j: Int, s: Int): Int =
            if j >= n then s
            else sumActual(j + 1, s + childMainSizes(j) + (if j > 0 then cs.gap else 0))
        val remaining = mainSize - sumActual(0, 0)

        val startPos = cs.justify match
            case 0 => 0                              // start
            case 1 => remaining / 2                  // center
            case 2 => remaining                      // end
            case 3 => 0                              // spaceBetween
            case 4 => if n > 0 then remaining / (n * 2) else 0 // spaceAround
            case 5 => if n > 0 then remaining / (n + 1) else 0 // spaceEvenly
            case _ => 0

        val extraGap = cs.justify match
            case 3 => if n > 1 then remaining / (n - 1) else 0
            case 4 => if n > 0 then remaining / n else 0
            case 5 => if n > 0 then remaining / (n + 1) else 0
            case _ => 0

        // Position each child
        @tailrec def position(i: Int, mainPos: Int, acc: Chunk[Laid]): Chunk[Laid] =
            if i >= n then acc
            else
                val childMain = childMainSizes(i)
                val childCross = childCrossSizes(i)

                val crossPos = cs.align match
                    case 0 => 0                              // start
                    case 1 => (crossSize - childCross) / 2   // center
                    case 2 => crossSize - childCross          // end
                    case 3 => 0                              // stretch
                    case _ => 0

                val childCrossActual = if cs.align == Style.Alignment.stretch then crossSize else childCross

                val (childX, childY, childW, childH) = if isColumn then
                    (cx + crossPos - scrollLeft, cy + mainPos - scrollTop,
                     childCrossActual, childMain)
                else
                    (cx + mainPos - scrollLeft, cy + crossPos - scrollTop,
                     childMain, childCrossActual)

                val laid = arrange(children(i),
                    Rect(childX, childY, childW, childH), clip, popups)

                position(i + 1, mainPos + childMain + cs.gap + extraGap, acc.append(laid))
        position(0, startPos, Chunk.empty)

    // ---- Table layout ----

    private def layoutTable(
        rows: Chunk[Styled], cx: Int, cy: Int, cw: Int,
        clip: Rect, popups: ChunkBuilder[Laid]
    ): Chunk[Laid] =
        // 1. Count columns
        @tailrec def countCols(i: Int, maxCols: Int): Int =
            if i >= rows.size then maxCols
            else rows(i) match
                case Styled.Node(_, _, _, cells) =>
                    @tailrec def countRow(j: Int, colCount: Int): Int =
                        if j >= cells.size then colCount
                        else
                            val span = cells(j) match
                                case n: Styled.Node => n.handlers.colspan
                                case _              => 1
                            countRow(j + 1, colCount + span)
                    countCols(i + 1, math.max(maxCols, countRow(0, 0)))
                case _ => countCols(i + 1, maxCols)
        val numCols = countCols(0, 0)

        if numCols == 0 then Chunk.empty
        else

        // 2. Measure intrinsic column widths (mutable array — accumulating max per column)
        val colWidths = new Array[Int](numCols)
        @tailrec def measureCols(i: Int): Unit =
            if i < rows.size then
                rows(i) match
                    case Styled.Node(_, _, _, cells) =>
                        @tailrec def measureRow(j: Int, col: Int): Unit =
                            if j < cells.size then
                                val colspan = cells(j) match
                                    case n: Styled.Node => n.handlers.colspan
                                    case _              => 1
                                if colspan == 1 then
                                    colWidths(col) = math.max(colWidths(col), measureWidth(cells(j)))
                                measureRow(j + 1, col + colspan)
                        measureRow(0, 0)
                    case _ =>
                measureCols(i + 1)
        measureCols(0)

        // 3. Distribute remaining width (mutates colWidths)
        val usedWidth = colWidths.sum
        if usedWidth < cw then
            val extra = (cw - usedWidth) / numCols
            @tailrec def distribute(j: Int): Unit =
                if j < numCols then { colWidths(j) += extra; distribute(j + 1) }
            distribute(0)

        // 4. Position cells
        @tailrec def layoutRows(i: Int, rowY: Int, acc: Chunk[Laid]): Chunk[Laid] =
            if i >= rows.size then acc
            else rows(i) match
                case row: Styled.Node =>
                    @tailrec def layoutCells(
                        j: Int, col: Int, cellX: Int, rowHeight: Int, cells: Chunk[Laid]
                    ): (Chunk[Laid], Int) =
                        if j >= row.children.size then (cells, rowHeight)
                        else
                            val cell = row.children(j)
                            val colspan = cell match
                                case n: Styled.Node => n.handlers.colspan
                                case _              => 1
                            @tailrec def spanWidth(k: Int, w: Int): Int =
                                if k >= colspan || col + k >= numCols then w
                                else spanWidth(k + 1, w + colWidths(col + k))
                            val cellW = spanWidth(0, 0)
                            val cellH = measureHeight(cell, cellW)
                            val laid = arrange(cell, Rect(cellX, rowY, cellW, cellH), clip, popups)
                            layoutCells(j + 1, col + colspan, cellX + cellW,
                                math.max(rowHeight, cellH), cells.append(laid))
                    val (cellLaid, rowHeight) = layoutCells(0, 0, cx, 0, Chunk.empty)
                    val rowNode = Laid.Node(ElemTag.Div, row.style, row.handlers,
                        Rect(cx, rowY, cw, rowHeight),
                        Rect(cx, rowY, cw, rowHeight),
                        clip, cellLaid)
                    layoutRows(i + 1, rowY + rowHeight, acc.append(rowNode))
                case other =>
                    layoutRows(i + 1, rowY + 1,
                        acc.append(arrange(other, Rect(cx, rowY, cw, 1), clip, popups)))
        layoutRows(0, cy, Chunk.empty)

    // ---- Helpers ----

    private def resolveDim(encoded: Int, parentPx: Int): Int =
        if Length.isAuto(encoded) then Length.Auto
        else Length.resolve(encoded, parentPx)

    private def intersect(a: Rect, b: Rect): Rect =
        val x = math.max(a.x, b.x)
        val y = math.max(a.y, b.y)
        val r = math.min(a.x + a.w, b.x + b.w)
        val bot = math.min(a.y + a.h, b.y + b.h)
        Rect(x, y, math.max(0, r - x), math.max(0, bot - y))

    private def splitLines(text: String, maxWidth: Int, textWrap: Int): Chunk[String] =
        if textWrap == Style.TextWrap.noWrap then // noWrap
            Chunk(text)
        else
            val parts = text.split('\n')
            @tailrec def loop(i: Int, acc: Chunk[String]): Chunk[String] =
                if i >= parts.length then acc
                else
                    val line = parts(i)
                    if line.length <= maxWidth || maxWidth <= 0 then
                        loop(i + 1, acc.append(line))
                    else
                        @tailrec def wrap(pos: Int, inner: Chunk[String]): Chunk[String] =
                            if pos >= line.length then inner
                            else wrap(math.min(pos + maxWidth, line.length),
                                inner.append(line.substring(pos, math.min(pos + maxWidth, line.length))))
                        loop(i + 1, wrap(0, acc))
            loop(0, Chunk.empty)
```

### Painter: complete paint algorithm

```scala
object Painter:
    import scala.annotation.tailrec

    val white = PackedColor(255, 255, 255)
    val black = PackedColor(0, 0, 0)

    def paint(layout: LayoutResult, viewport: Rect): (CellGrid, CellGrid) =
        val base = CellGrid.empty(viewport.w, viewport.h)
        paintNode(layout.base, base)
        val popup = CellGrid.empty(viewport.w, viewport.h)
        @tailrec def paintPopups(i: Int): Unit =
            if i < layout.popups.size then
                paintNode(layout.popups(i), popup)
                paintPopups(i + 1)
        paintPopups(0)
        (base, popup)

    private def paintNode(node: Laid, grid: CellGrid): Unit = node match
        case n: Laid.Node   => paintBoxNode(n, grid)
        case t: Laid.Text   => paintText(t, grid)
        case c: Laid.Cursor => paintCursor(c, grid)

    private def paintBoxNode(n: Laid.Node, grid: CellGrid): Unit =
        val cs = n.style
        val b = n.bounds
        val clip = n.clip

        // 1. Shadow
        if cs.shadowColor != PackedColor.Transparent &&
           (cs.shadowX.value != 0 || cs.shadowY.value != 0 || cs.shadowSpread.value != 0) then
            paintShadow(grid, b, cs, clip)

        // 2. Background
        cs.gradientDirection match
            case Present(dir) if cs.gradientStops.size >= 2 =>
                paintGradient(grid, b, dir, cs.gradientStops, clip)
            case _ =>
                if cs.bg != PackedColor.Transparent then
                    fillBg(grid, b.x, b.y, b.w, b.h, cs.bg, clip)

        // 3. Border
        if cs.borderTop.value > 0 || cs.borderRight.value > 0 ||
           cs.borderBottom.value > 0 || cs.borderLeft.value > 0 then
            paintBorder(grid, b, cs, clip)

        // 4. Image
        n.handlers.imageData.foreach { img =>
            paintImage(grid, n.content, img, clip)
        }

        // 5. Children
        @tailrec def paintChildren(i: Int): Unit =
            if i < n.children.size then
                paintNode(n.children(i), grid)
                paintChildren(i + 1)
        paintChildren(0)

        // 6. Filters
        if cs.brightness != 1.0 || cs.contrast != 1.0 || cs.grayscale > 0.0 ||
           cs.sepia > 0.0 || cs.invert > 0.0 || cs.saturate != 1.0 || cs.hueRotate != 0.0 then
            applyFilters(grid, b, cs, clip)

    // ---- Background fill ----

    private def fillBg(grid: CellGrid, x: Int, y: Int, w: Int, h: Int, bg: PackedColor, clip: Rect): Unit =
        @tailrec def eachRow(row: Int): Unit =
            if row < y + h then
                @tailrec def eachCol(col: Int): Unit =
                    if col < x + w then
                        if inClip(col, row, clip) && inBounds(col, row, grid) then
                            val idx = row * grid.width + col
                            grid.cells(idx) = grid.cells(idx).copy(bg = bg)
                        eachCol(col + 1)
                eachCol(x)
                eachRow(row + 1)
        eachRow(y)

    // ---- Border drawing ----

    private val BoxSolid  = Array('┌', '─', '┐', '│', '│', '└', '─', '┘')
    private val BoxRound  = Array('╭', '─', '╮', '│', '│', '╰', '─', '╯')
    private val BoxDashed = Array('┌', '┄', '┐', '┆', '┆', '└', '┄', '┘')
    private val BoxDotted = Array('┌', '·', '┐', '·', '·', '└', '·', '┘')

    private def paintBorder(grid: CellGrid, b: Rect, cs: FlatStyle, clip: Rect): Unit =
        val chars = cs.borderStyle match
            case Style.BorderStyle.solid  => BoxSolid
            case Style.BorderStyle.dashed => BoxDashed
            case Style.BorderStyle.dotted => BoxDotted
            case Style.BorderStyle.none   => BoxSolid

        val x1 = b.x
        val y1 = b.y
        val x2 = b.x + b.w - 1
        val y2 = b.y + b.h - 1

        // Corners
        if cs.borderTop.value > 0 && cs.borderLeft.value > 0 then
            val ch = if cs.roundTL then BoxRound(0) else chars(0)
            setCell(grid, x1, y1, ch, cs.borderColorTop, cs.bg, clip)
        // ... (same pattern for TR, BL, BR corners)

        // Edges — @tailrec loops, not while
        if cs.borderTop.value > 0 then
            @tailrec def topEdge(col: Int): Unit =
                if col < x2 then
                    setCell(grid, col, y1, chars(1), cs.borderColorTop, cs.bg, clip)
                    topEdge(col + 1)
            topEdge(x1 + 1)
        // ... (same pattern for bottom, left, right edges)

    // ---- Text painting ----

    private def paintText(t: Laid.Text, grid: CellGrid): Unit =
        val cs = t.style
        val spacing = Length.resolve(cs.letterSpacing, 0)

        // Text transform — match on enum
        val text = cs.textTransform match
            case Style.TextTransform.uppercase  => t.value.toUpperCase
            case Style.TextTransform.lowercase  => t.value.toLowerCase
            case Style.TextTransform.capitalize => t.value.capitalize
            case Style.TextTransform.none       => t.value

        val maxCharsPerLine = t.bounds.w / math.max(1, 1 + spacing)
        val lines = splitLines(text, maxCharsPerLine, cs.textWrap)

        @tailrec def eachLine(lineIdx: Int, lineY: Int): Unit =
            if lineIdx < lines.size then
                val line = lines(lineIdx)

                // Text overflow — match on enum
                val displayLine = cs.textOverflow match
                    case Style.TextOverflow.ellipsis if line.length > maxCharsPerLine && maxCharsPerLine > 3 =>
                        line.substring(0, maxCharsPerLine - 1) + "…"
                    case _ if line.length > maxCharsPerLine && maxCharsPerLine > 0 =>
                        line.substring(0, maxCharsPerLine)
                    case _ => line

                // Text align — match on enum
                val lineWidth = displayLine.length * (1 + spacing)
                val startX = cs.textAlign match
                    case Style.TextAlign.left    => t.bounds.x
                    case Style.TextAlign.center  => t.bounds.x + (t.bounds.w - lineWidth) / 2
                    case Style.TextAlign.right   => t.bounds.x + t.bounds.w - lineWidth
                    case Style.TextAlign.justify => t.bounds.x

                @tailrec def eachChar(charIdx: Int, cellX: Int): Unit =
                    if charIdx < displayLine.length then
                        setCell(grid, cellX, lineY, displayLine.charAt(charIdx),
                            cs.fg, cs.bg, t.clip,
                            cs.bold, cs.italic, cs.underline, cs.strikethrough)
                        eachChar(charIdx + 1, cellX + 1 + spacing)
                eachChar(0, startX)

                eachLine(lineIdx + 1, lineY + cs.lineHeight)
        eachLine(0, t.bounds.y)

    // ---- Cursor painting ----

    private def paintCursor(c: Laid.Cursor, grid: CellGrid): Unit =
        val x = c.pos.x
        val y = c.pos.y
        if inBounds(x, y, grid) then
            val idx = y * grid.width + x
            val existing = grid.cells(idx)
            val newFg = if existing.bg == black then white else existing.bg
            val newBg = if existing.fg == black then white else existing.fg
            val ch = if existing.char == '\u0000' then '█' else existing.char
            grid.cells(idx) = Cell(ch, newFg, newBg,
                existing.bold, existing.italic, existing.underline,
                existing.strikethrough, existing.dimmed)

    // ---- Gradient painting ----

    private def paintGradient(
        grid: CellGrid, b: Rect, direction: Style.GradientDirection,
        stops: Chunk[(PackedColor, Double)], clip: Rect
    ): Unit =
        @tailrec def eachRow(y: Int): Unit =
            if y < b.y + b.h then
                @tailrec def eachCol(x: Int): Unit =
                    if x < b.x + b.w then
                        if inClip(x, y, clip) && inBounds(x, y, grid) then
                            val t = gradientPosition(direction, x - b.x, y - b.y, b.w, b.h)
                            val color = interpolateGradient(stops, t)
                            grid.cells(y * grid.width + x) = grid.cells(y * grid.width + x).copy(bg = color)
                        eachCol(x + 1)
                eachCol(b.x)
                eachRow(y + 1)
        eachRow(b.y)

    /** Compute position (0.0 to 1.0) along gradient axis */
    private def gradientPosition(dir: Style.GradientDirection, dx: Int, dy: Int, w: Int, h: Int): Double =
        val fx = dx.toDouble / math.max(1, w - 1)
        val fy = dy.toDouble / math.max(1, h - 1)
        dir match
            case Style.GradientDirection.toRight       => fx
            case Style.GradientDirection.toLeft         => 1.0 - fx
            case Style.GradientDirection.toBottom       => fy
            case Style.GradientDirection.toTop          => 1.0 - fy
            case Style.GradientDirection.toTopRight     => (fx + (1.0 - fy)) / 2.0
            case Style.GradientDirection.toTopLeft      => ((1.0 - fx) + (1.0 - fy)) / 2.0
            case Style.GradientDirection.toBottomRight  => (fx + fy) / 2.0
            case Style.GradientDirection.toBottomLeft   => ((1.0 - fx) + fy) / 2.0

    private def interpolateGradient(stops: Chunk[(PackedColor, Double)], t: Double): PackedColor =
        if stops.size <= 1 then
            if stops.isEmpty then black else stops(0)._1
        else
            // ... find surrounding stops, lerp between them
            val (c1, c2, f) = findStopPair(stops, t)
            c1.lerp(c2, f) // lerp is a PackedColor method

    // ---- Filter application ----

    private def applyFilters(grid: CellGrid, b: Rect, cs: FlatStyle, clip: Rect): Unit =
        @tailrec def eachRow(y: Int): Unit =
            if y < b.y + b.h then
                @tailrec def eachCol(x: Int): Unit =
                    if x < b.x + b.w then
                        if inClip(x, y, clip) && inBounds(x, y, grid) then
                            val idx = y * grid.width + x
                            val cell = grid.cells(idx)
                            grid.cells(idx) = cell.copy(
                                fg = applyFilterChain(cell.fg, cs),
                                bg = applyFilterChain(cell.bg, cs)
                            )
                        eachCol(x + 1)
                eachCol(b.x)
                eachRow(y + 1)
        eachRow(b.y)

    private def applyFilterChain(color: PackedColor, cs: FlatStyle): PackedColor =
        if color == PackedColor.Transparent then color
        else
            // Apply brightness, contrast, grayscale, sepia, invert, saturate, hueRotate
            // Each filter uses PackedColor methods for extraction/construction
            // Local vars acceptable here — building up a single PackedColor result
            var r = color.r
            var g = color.g
            var b = color.b
            // ... (filter math same as before, using PackedColor(...) to construct result)
            PackedColor(PackedColor.clamp(r), PackedColor.clamp(g), PackedColor.clamp(b))

    // ---- Helpers ----

    private def setCell(grid: CellGrid, x: Int, y: Int, ch: Char,
                        fg: PackedColor, bg: PackedColor, clip: Rect,
                        bold: Boolean = false, italic: Boolean = false,
                        underline: Boolean = false, strikethrough: Boolean = false): Unit =
        if inClip(x, y, clip) && inBounds(x, y, grid) then
            grid.cells(y * grid.width + x) = Cell(ch, fg, bg, bold, italic, underline, strikethrough, false)

    private inline def inClip(x: Int, y: Int, clip: Rect): Boolean =
        x >= clip.x && x < clip.x + clip.w && y >= clip.y && y < clip.y + clip.h

    private inline def inBounds(x: Int, y: Int, grid: CellGrid): Boolean =
        x >= 0 && x < grid.width && y >= 0 && y < grid.height

    // splitLines reuses Layout.splitLines or is extracted to a shared utility
```

**Key differences from old pseudocode:**
- `n.style` not `n.computed`
- All enums matched by variant (`Style.BorderStyle.solid`), not Int ordinals
- All colors typed as `PackedColor`, not `Int`
- `PackedColor.lerp`, `PackedColor.clamp` — color operations on the type
- `gradientDirection: Maybe[Style.GradientDirection]` — pattern match, not `> 0`
- Border widths accessed as `.value` (they're `Length.Px`)
- All `while` → `@tailrec def eachRow`/`eachCol`/`eachLine`/`eachChar`
- No `return` — early exits via `if`/`else`
- No `;`-joined statements
- No magic `0xFFFFFF` — named `white`/`black` constants

### Differ: complete ANSI emission

> **Note**: Differ has been refactored to use `@tailrec` with immutable `TermState` threaded
> through `eachCol`/`eachRow` loops. SGR tracking uses `Maybe[PackedColor]` for colors and
> `Maybe[(Int, Int)]` for cursor position — no sentinel values. ANSI protocol details are behind
> named methods (`enableBold`, `resetAllAttributes`, `moveCursorTo`, `writeFgColor`, etc.).
> See `Differ.scala` for the current implementation.

```scala
object Differ:
    private case class TermState(
        fg: Maybe[PackedColor], bg: Maybe[PackedColor],
        bold: Boolean, italic: Boolean, underline: Boolean,
        strikethrough: Boolean, dimmed: Boolean,
        cursorPos: Maybe[(Int, Int)]
    )

    def diff(prev: CellGrid, curr: CellGrid): Array[Byte] =
        val buf = new java.io.ByteArrayOutputStream(curr.width * curr.height * 4)

        @tailrec def eachCol(col: Int, row: Int, term: TermState): TermState =
            if col >= curr.width then term
            else
                val idx = row * curr.width + col
                val currCell = curr.cells(idx)
                val prevCell = if idx < prev.cells.length then prev.cells(idx) else Cell.Empty
                if currCell != prevCell then
                    if !term.cursorPos.contains((row, col)) then
                        moveCursorTo(buf, row, col)
                    val afterAttrs = updateTextAttributes(buf, term, currCell)
                    val afterFg    = updateFgColor(buf, afterAttrs, currCell.fg)
                    val afterBg    = updateBgColor(buf, afterFg, currCell.bg)
                    writeChar(buf, currCell.char)
                    eachCol(col + 1, row, afterBg.copy(cursorPos = Maybe((row, col + 1))))
                else
                    eachCol(col + 1, row, term)

        @tailrec def eachRow(row: Int, term: TermState): TermState =
            if row >= curr.height then term
            else eachRow(row + 1, eachCol(0, row, term))

        discard(eachRow(0, Initial))

        @tailrec def eachRaw(i: Int): Unit =
            if i < curr.rawSequences.size then
                val (rect, bytes) = curr.rawSequences(i)
                moveCursorTo(buf, rect.y, rect.x)
                buf.write(bytes)
                eachRaw(i + 1)
        eachRaw(0)
        buf.toByteArray

    // Named ANSI primitives: moveCursorTo, resetAllAttributes, enableBold,
    // enableDim, enableItalic, enableUnderline, enableStrikethrough,
    // writeFgColor, writeBgColor, writeChar, writeDecimal
    // See Differ.scala for full implementation
```

### Dispatch: complete event routing (pre-composed handlers)

Dispatch is a flat event→handler mapping. All bubbling has been pre-composed by Lower during the tree walk. hitTest returns a single node (not a path). Handler calls are direct — no tree walking, no form searching, no scroll container fallback.

```scala
object Dispatch:
    import scala.annotation.tailrec

    /** Find deepest node at (mx, my). Returns single node.
      * Pre-composed handlers already contain the full bubbling chain.
      */
    def hitTestNode(node: Laid, mx: Int, my: Int): Maybe[Laid.Node] =
        node match
            case n: Laid.Node =>
                if !contains(n.bounds, mx, my) || !contains(n.clip, mx, my) then Absent
                else
                    @tailrec def checkChildren(i: Int): Maybe[Laid.Node] =
                        if i < 0 then Maybe(n)
                        else
                            hitTestNode(n.children(i), mx, my) match
                                case found @ Maybe.Present(_) => found
                                case _                        => checkChildren(i - 1)
                    checkChildren(n.children.size - 1)
            case _ => Absent

    /** hitTest across popups (topmost first) then base. */
    def hitTest(layout: LayoutResult, mx: Int, my: Int): Maybe[Laid.Node] =
        @tailrec def checkPopups(i: Int): Maybe[Laid.Node] =
            if i < 0 then hitTestNode(layout.base, mx, my)
            else
                hitTestNode(layout.popups(i), mx, my) match
                    case found @ Maybe.Present(_) => found
                    case _                        => checkPopups(i - 1)
        checkPopups(layout.popups.size - 1)

    /** Find node by WidgetKey. For focus/blur dispatch. */
    def findByKey(node: Laid, key: WidgetKey): Maybe[Laid.Node] =
        node match
            case n: Laid.Node =>
                if n.handlers.widgetKey.contains(key) then Maybe(n)
                else
                    @tailrec def searchChildren(i: Int): Maybe[Laid.Node] =
                        if i >= n.children.size then Absent
                        else
                            findByKey(n.children(i), key) match
                                case found @ Maybe.Present(_) => found
                                case _                        => searchChildren(i + 1)
                    searchChildren(0)
            case _ => Absent

    /** Find node by user-facing id (for Label.forId). */
    def findByUserId(node: Laid, id: String): Maybe[Laid.Node] =
        node match
            case n: Laid.Node =>
                if n.handlers.id.contains(id) then Maybe(n)
                else
                    @tailrec def searchChildren(i: Int): Maybe[Laid.Node] =
                        if i >= n.children.size then Absent
                        else
                            findByUserId(n.children(i), id) match
                                case found @ Maybe.Present(_) => found
                                case _                        => searchChildren(i + 1)
                    searchChildren(0)
            case _ => Absent

    /** Route event: find target, call pre-composed handler. No tree walking for bubbling. */
    def dispatch(
        event: InputEvent, layout: LayoutResult, state: ScreenState
    )(using AllowUnsafe): Unit =
        event match
            case InputEvent.Key(key, ctrl, shift, alt) =>
                if key == UI.Keyboard.Tab then
                    cycleFocus(state, layout, reverse = shift)
                else
                    findFocused(layout, state).foreach { target =>
                        if !target.handlers.disabled then
                            val ke = UI.KeyEvent(key, ctrl, shift, alt, false)
                            // Pre-composed: fires widget → user → parent → ... chain
                            // Includes form onSubmit on Enter (woven by Lower for TextInputs)
                            Sync.Unsafe.evalOrThrow(target.handlers.onKeyDown(ke))

                            // Space: fire onClick on focused element
                            if key == UI.Keyboard.Space then
                                Sync.Unsafe.evalOrThrow(target.handlers.onClick)
                    }

            case InputEvent.Mouse(kind, x, y, _, _, _) =>
                kind match
                    case MouseKind.LeftPress =>
                        hitTest(layout, x, y).foreach { target =>
                            if !target.handlers.disabled then
                                // forId redirect
                                target.handlers.forId match
                                    case Maybe.Present(targetId) =>
                                        findByUserId(layout.base, targetId).foreach { targetNode =>
                                            setFocus(state, targetNode, layout)
                                        }
                                    case _ =>
                                        if target.handlers.tabIndex.nonEmpty then
                                            setFocus(state, target, layout)

                                // Pre-composed onClick: fires target → parent → ... chain
                                Sync.Unsafe.evalOrThrow(target.handlers.onClick)
                                // onClickSelf: target only (not composed into children)
                                Sync.Unsafe.evalOrThrow(target.handlers.onClickSelf)

                                // Set active
                                state.activeId.unsafe.set(target.handlers.widgetKey)
                        }

                    case MouseKind.LeftRelease =>
                        state.activeId.unsafe.set(Absent)

                    case MouseKind.Move =>
                        val target = hitTest(layout, x, y)
                        state.hoveredId.unsafe.set(target.flatMap(_.handlers.widgetKey))

                    case MouseKind.ScrollUp | MouseKind.ScrollDown =>
                        hitTest(layout, x, y).foreach { target =>
                            val delta = if kind == MouseKind.ScrollUp then -3 else 3
                            // Pre-composed with override semantics (innermost scrollable wins)
                            Sync.Unsafe.evalOrThrow(target.handlers.onScroll(delta))
                        }

                    case _ => ()

            case InputEvent.Paste(text) =>
                findFocused(layout, state).foreach { target =>
                    if !target.handlers.disabled then
                        Sync.Unsafe.evalOrThrow(target.handlers.onInput(text))
                }

            case _ => ()

    private def findFocused(layout: LayoutResult, state: ScreenState)(using AllowUnsafe): Maybe[Laid.Node] =
        state.focusedId.unsafe.get().flatMap(key => findByKey(layout.base, key))

    private def setFocus(
        state: ScreenState, node: Laid.Node, layout: LayoutResult
    )(using AllowUnsafe): Unit =
        val oldKey = state.focusedId.unsafe.get()
        val newKey = node.handlers.widgetKey
        if oldKey != newKey then
            // Fire onBlur on old
            oldKey.flatMap(k => findByKey(layout.base, k)).foreach { old =>
                Sync.Unsafe.evalOrThrow(old.handlers.onBlur)
            }
            state.focusedId.unsafe.set(newKey)
            // Fire onFocus on new
            Sync.Unsafe.evalOrThrow(node.handlers.onFocus)

    private def cycleFocus(state: ScreenState, layout: LayoutResult, reverse: Boolean)(using AllowUnsafe): Unit =
        val keys = state.focusableIds
        if keys.nonEmpty then
            val current = state.focusedId.unsafe.get()
        val idx = current.map(k => keys.indexOf(k)).getOrElse(-1)
        val nextIdx =
            if reverse then (if idx <= 0 then keys.size - 1 else idx - 1)
            else (if idx < 0 || idx >= keys.size - 1 then 0 else idx + 1)
        val newKey = keys(nextIdx)
        // Fire onBlur on old
        current.flatMap(k => findByKey(layout.base, k)).foreach { old =>
            Sync.Unsafe.evalOrThrow(old.handlers.onBlur)
        }
        // Fire onFocus on new
        findByKey(layout.base, newKey).foreach { node =>
            Sync.Unsafe.evalOrThrow(node.handlers.onFocus)
        }
        state.focusedId.unsafe.set(Maybe(newKey))

    private inline def contains(r: Rect, x: Int, y: Int): Boolean =
        x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h
```

---

## Kyo Primitives Reference

This section documents the kyo-data and kyo-core primitives used throughout the pipeline, their APIs, characteristics, and where each is used. Every implementation phase should use these primitives correctly — no raw Java collections unless explicitly justified.

### Chunk[A]

**Package:** `kyo` (kyo-data)

**What:** Immutable sequence with structural sharing. Sealed class extending `Seq[A]`. The primary collection type for all IR children, focusable ID lists, gradient stops, and any sequence that passes between pipeline steps.

**Key API:**
- `Chunk.empty[A]` — empty chunk (singleton)
- `Chunk(a, b, c)` / `Chunk.from(iterable)` — construction
- `.append(a)` / `.concat(other)` — O(1) via `Append` nodes (structural sharing, no copy)
- `.size` — O(1) (cached)
- `.apply(i)` — O(depth) where depth = number of Append nodes; O(1) after `.toIndexed`
- `.map(f)` / `.filter(f)` / `.flatMap(f)` / `.foreach(f)` — standard collection ops
- `.toIndexed` — flattens to `Chunk.Indexed` (backed by `Array`) for O(1) random access
- `.head` / `.last` / `.tail` / `.take(n)` / `.drop(n)` — subsequence ops
- `.indexOf(a)` / `.contains(a)` — search
- `.mkString(sep)` — string rendering
- `.nonEmpty` / `.isEmpty` — emptiness check

**Characteristics:**
- Immutable — no mutation after creation
- Structural sharing via `Append` nodes — O(1) append without copying
- For indexed access in hot loops, call `.toIndexed` first
- NOT a Scala collections type — use `.size` not `.length` (except on raw `Array`)

**Used in:** All IR `children` fields, `focusableIds`, `gradientStops`, `rawSequences`, `LayoutResult.popups`, hitTest paths, findByKey paths.

### ChunkBuilder[A]

**Package:** `kyo` (kyo-data)

**What:** Mutable builder that produces `Chunk.Indexed[A]`. Uses ThreadLocal `ArrayDeque` buffer pool for efficient reuse.

**Key API:**
- `ChunkBuilder.init[A]` / `ChunkBuilder.init[A](initialCapacity)` — create builder
- `.add(a)` / `.addOne(a)` — append single element
- `.result()` — finalize to `Chunk.Indexed[A]` (O(n) copy, builder is consumed)

**Characteristics:**
- Mutable — for accumulation during a single pass
- Uses buffer pool — avoids repeated allocation in hot paths
- After `.result()`, builder should not be reused

**Used in:** Lower (collecting `focusableIds`), Layout (collecting `popups`).

### Maybe[A]

**Package:** `kyo` (kyo-data)

**What:** Opaque type over `AnyRef | Null`. Zero runtime overhead — no wrapper object. Replaces `Option` throughout.

**Key API:**
- `Maybe(a)` / `Maybe.Present(a)` — wrap a value (use `Maybe(a)` when `a` might be null)
- `Absent` — empty value (equivalent to `None`)
- `.isEmpty` / `.nonEmpty` / `.isDefined` — check presence
- `.get` — unwrap (throws on Absent)
- `.getOrElse(default)` — unwrap with fallback
- `.map(f)` / `.flatMap(f)` / `.filter(p)` / `.foreach(f)` — standard monadic ops
- `.contains(a)` — equality check against wrapped value
- Pattern match: `case Maybe.Present(v) =>` / `case _ =>` (or `case Absent =>` for `Maybe[Nothing]`)

**Characteristics:**
- Zero allocation — `Present(a)` is just `a` cast to opaque type
- `Absent` is `null` under the hood — no sentinel object
- Use `Absent` (not `Maybe.Absent` or `None`) for the empty case
- Pattern matching uses `Maybe.Present(v)`, not `Some(v)`

**Used in:** All `Maybe` fields on `Handlers` (widgetKey, id, forId, tabIndex, onClick, etc.), `ScreenState` reactive refs (`Maybe[WidgetKey]`), `WidgetStateCache.get` return type, `prevLayout`/`prevGrid`.

### Frame

**Package:** `kyo` (kyo-data)

**What:** Opaque type capturing source position (`file:line:column`) at compile time via Scala macros. Generated automatically by `inline` methods.

**Key API:**
- `Frame` is typically received as `(using Frame)` — implicit parameter
- `.position` — access the `Position` value
- `.position.show` — string representation like `"Lower.scala:42:5"`

**Characteristics:**
- Compile-time only — no runtime cost beyond a string
- Unique per call site — two calls on different lines produce different Frames
- Used as the stable component of `WidgetKey` (source position is constant across re-renders)
- Not manually constructed — always provided by the compiler

**Used in:** `WidgetKey.apply(frame, dynamicPath)` — combines Frame with Foreach path for widget identity.

### SignalRef[A] / SignalRef.Unsafe[A]

**Package:** `kyo` (kyo-core)

**What:** Mutable reactive reference. The "safe" version requires effects; the `.Unsafe` version allows direct reads/writes under `AllowUnsafe`.

**Key API (SignalRef):**
- `Signal.initRef[A](initial)` — create a new SignalRef (returns `< IO`)
- `.get` — read current value (returns `< IO`)
- `.set(v)` — write new value (returns `< IO`)
- `.getAndUpdate(f)` — atomic read-modify-write (returns `< IO`)
- `.update(f)` — atomic modify (returns `< IO`)
- `.next` — wait for next change (returns `< Async`)

**Key API (SignalRef.Unsafe):**
- `SignalRef.Unsafe.init[A](initial)` — create directly (no effect)
- `.unsafe.get()` — direct read (requires `AllowUnsafe`)
- `.unsafe.set(v)` — direct write (requires `AllowUnsafe`)

**Characteristics:**
- Built on two `AtomicRef`s: one for current value, one for next-change promise
- CAS-based updates — thread-safe
- Change detection via `CanEqual` — only notifies if new value != old value
- `.next` returns a `Promise` that completes when the value changes — this is how `SignalCollector` detects changes for re-render

**Used in:** `ScreenState.focusedId/hoveredId/activeId`, all widget state in `WidgetStateCache` (cursor position, scroll offset, checkbox checked, select expanded/selected/highlight, range value), user-bound `value` refs on inputs. Lower reads via `.unsafe.get()`, Dispatch writes via `.unsafe.set()`.

### Span[A]

**Package:** `kyo` (kyo-data)

**What:** Opaque wrapper over `Array[A]`. Immutable view — no mutation. O(1) indexed access, no boxing for primitives.

**Key API:**
- `Span(a, b, c)` / `Span.from(array)` — construction
- `.apply(i)` — O(1) indexed access
- `.size` — O(1) length
- `.foreach(f)` — iteration

**Characteristics:**
- NOT part of Scala collections — no `.map`, `.filter` etc.
- Zero overhead over raw arrays for indexed access
- Used in existing `UI` AST for element children (`children: Span[UI]`)
- We do NOT use Span in new pipeline IR types — use `Chunk` instead for structural sharing
- Exception: `ImageData.bytes: IArray[Byte]` stays as `IArray` (raw byte data, never appended to)

**Used in:** Reading `UI.Element.children` during Lower (existing API). Not used in new IR types.

### Dict[K, V]

**Package:** `kyo` (kyo-data)

**What:** Dual-representation immutable map. Uses `Span` for ≤8 entries, `HashMap` for >8.

**Key API:**
- `Dict.empty[K, V]` / `Dict(k1 -> v1, k2 -> v2)` — construction
- `.get(k)` — returns `Maybe[V]`
- `.add(k, v)` — returns new Dict with entry added
- `.size` — entry count

**Characteristics:**
- Immutable — `.add` returns a new Dict
- Optimized for small maps (≤8 entries use linear scan)
- NOT suitable for `WidgetStateCache` — that needs mutable mark-and-sweep semantics

**Used in:** Not used in the new pipeline. Documented here for completeness — if a future need arises for small immutable maps in pipeline state, Dict is available.

### Sync.Unsafe.evalOrThrow

**Package:** `kyo` (kyo-core)

**What:** Evaluates a kyo effect computation synchronously, throwing on failure. Used to bridge from effectful to unsafe code.

**Key API:**
- `Sync.Unsafe.evalOrThrow(computation)` — evaluate `A < Sync` to `A` (requires `AllowUnsafe`)

**Characteristics:**
- Used in Lower to evaluate reactive signals: `Sync.Unsafe.evalOrThrow(signal.current)`
- Used in Dispatch to fire handler closures: `Sync.Unsafe.evalOrThrow(handler(event))`
- Throws if the computation fails — appropriate because render/dispatch are already in AllowUnsafe context

**Used in:** Lower (signal evaluation), Dispatch (handler firing).

---

## Design Goals Checklist

Use this checklist at each phase review to verify the implementation meets design goals.

### Simplicity
- [ ] Single file per pipeline step with single public entry point
- [ ] No ambient context or god objects — all inputs are explicit parameters
- [ ] No implicit ordering dependencies between steps
- [ ] Widget behavior fully encoded in Lower — downstream steps are widget-type-agnostic
- [ ] `@tailrec` used for tree traversals (except justified exceptions in Painter/Differ)
- [ ] Each function's purpose is clear from its signature without reading the body
- [ ] Event handler fields use noop defaults (not Maybe) — no pattern matching for handler dispatch
- [ ] Bubbling is pre-composed in Lower — Dispatch has no tree walking for event propagation

### Modularity
- [ ] Each phase compiles independently (only depends on IR types from Phase 1)
- [ ] Each phase is testable by constructing input types directly — no preceding steps needed
- [ ] Adding a new widget type requires changes only in Lower
- [ ] No circular dependencies between pipeline modules
- [ ] Test file mirrors source file 1:1 (e.g., `Styler.scala` ↔ `StylerTest.scala`)

### Safety
- [ ] All cross-frame reactive state is `SignalRef.Unsafe` — no raw `var` for reactive state
- [ ] `WidgetStateCache` uses mark-and-sweep — `beginFrame` before Lower, `sweep` after, evicting entries for absent widgets
- [ ] IR types are immutable case classes/enums — no mutation after construction
- [ ] `CellGrid.cells` is freshly allocated per frame — never shared between frames
- [ ] Handler closures capture `SignalRef.Unsafe` refs, not snapshot values
- [ ] `AllowUnsafe` is used explicitly (not implicitly imported in broad scope)

### Kyo Primitives Usage
- [ ] `Chunk` used for all sequence types in IR and pipeline (not `List`, `Vector`, or `Array` for sequences)
- [ ] `ChunkBuilder` used for mutable accumulation during single-pass traversals
- [ ] `Maybe` used instead of `Option` throughout (not `Some`/`None`)
- [ ] `Absent` used for empty Maybe (not `Maybe.empty` or `None`)
- [ ] `SignalRef.Unsafe` for all reactive state (not raw `AtomicReference` or `var`)
- [ ] `Frame` used for widget identity (not manual string construction)
- [ ] `.size` used on Chunk (not `.length`)
- [ ] Pattern matching uses `Maybe.Present(v)` (not `Some(v)`)
- [ ] `Span` only used for reading existing `UI.Element.children` — not in new types

### Test Coverage
- [ ] Each phase has a dedicated test file with tests listed in the implementation plan
- [ ] Tests construct inputs directly — no dependency on other pipeline steps executing first
- [ ] Edge cases covered: empty inputs, single-element inputs, boundary values
- [ ] Tests verify both positive cases and negative cases (e.g., disabled elements don't fire handlers)
