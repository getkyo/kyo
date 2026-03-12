# kyo-ui API Additions — Implementation Plan

This plan adds new event types, interaction handlers, transitions, and pointer control to the shared kyo-ui API surface (`UI.scala`, `Style.scala`, `UIDsl.scala`). The goal is a **safe, fluent API that rules out invalid states at the type level**.

Backend updates (Phases 6–8, 10) are minimal — they wire the new Attrs/Props through existing dispatch patterns and add no new backend features. They serve primarily as **validation that the API is backend-implementable**.

## Scope

Shared API: `UI.scala`, `Style.scala`, `UIDsl.scala`, `CssStyleRenderer.scala`
Backend wiring: `TuiFocus`, `TuiFlatten`, `TuiLayout`, `TuiStyle`, `DomBackend`
Tests: `UITest`, `StyleTest`, `TuiSimulatorTest`

## Known Divergence from REDESIGN.md

REDESIGN.md line 699 defers `TransitionProp` to v2. This plan adds it because it was approved during the usability review. The implementation is minimal (one Prop case + one Easing enum) and does not introduce animation loops or per-property transitions — it's a declarative hint that backends can handle or ignore.

## Type Safety Analysis

Every new type is designed to rule out invalid states statically:

| Type / API | Invalid states eliminated |
|-----------|--------------------------|
| `PointerButton` enum (`Left`, `Right`, `Middle`) | No raw ints. Exhaustive match enforced by compiler. |
| `PointerEvent` sealed enum (`Down`, `Up`, `Move`) | `Move` has no `button` field — can't accidentally read a button from a movement event. `Down`/`Up` require `PointerButton` — can't forget which button. |
| `Modifiers` case class (all-false defaults) | Can't construct partial modifier state. No `Option` fields — every modifier is always defined. |
| `ScrollEvent` case class (4 required `Double` fields) | All fields mandatory. Can't create a scroll event missing the position or delta. |
| `Easing` enum (4 cases) | No string-based easing. Exhaustive match enforced. |
| `TransitionProp(Duration, Easing)` | Both fields required. Duration clamped to non-negative at construction. Easing is a closed enum. |
| `onPointer(f: PointerEvent => ...)` | Single handler for all pointer events. Pattern matching on sealed enum gives compile-time exhaustiveness warnings. Eliminates the state where only some events are handled (vs 3 separate handlers). |
| `onScroll(f: ScrollEvent => ...)` | Handler receives a complete event — can't access delta without position or vice versa. |
| `onClickOutside(action)` | No configuration needed — semantics are unambiguous (any click outside bounds). |
| `pointerPassthrough(Boolean)` | Simple flag — no ambiguous "partial passthrough" states. |

**Not representable at the type level** (enforced by backend behavior):
- onClick/onClickSelf precedence — both are `Maybe[Unit < Async]` on Attrs. The no-double-fire guarantee is a behavioral contract enforced identically by every backend.
- pointerPassthrough skipping during hit testing — backends must check the flag.
- onClickOutside skipping hidden elements — backends must check hidden state.

**No divergence from existing patterns**:
- New Attrs fields all use `Maybe[...]` with `= Absent` defaults — same as existing `onClick`, `onKeyDown`, etc.
- New Style methods follow the same `appendProp` + companion mirror + lambda overload pattern as every other Style method.
- New event types use `derives CanEqual` like all existing UI types.

---

## Phase 1: New Types in UI Companion

Add `PointerButton`, `Modifiers`, `PointerEvent`, and `ScrollEvent` to the `UI` companion object.

### 1.1 Add Types

**File: `kyo-ui/shared/src/main/scala/kyo/UI.scala`**

Add after the `KeyEvent` case class (line 75), before `Element`:

```scala
// ---- Pointer types ----

enum PointerButton derives CanEqual:
    case Left, Right, Middle

case class Modifiers(
    ctrl: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
    meta: Boolean = false
) derives CanEqual

enum PointerEvent derives CanEqual:
    case Down(x: Double, y: Double, button: PointerButton, modifiers: Modifiers)
    case Up(x: Double, y: Double, button: PointerButton, modifiers: Modifiers)
    case Move(x: Double, y: Double, modifiers: Modifiers)

// ---- Scroll types ----

case class ScrollEvent(
    scrollX: Double,
    scrollY: Double,
    deltaX: Double,
    deltaY: Double
) derives CanEqual
```

**Design notes:**
- `PointerEvent.Move` has no `PointerButton` — correct because no button is inherently pressed during movement. Backends that track drag state can synthesize button info via `Down`/`Up` tracking.
- Coordinates (`x`/`y`) are `Double` (subpixel-accurate), element-local (relative to target element's top-left). Backends translate from their native coordinate system.
- `Modifiers` is a standalone case class (not duplicating `KeyEvent`'s inline booleans) because it's shared across `PointerEvent` cases. `KeyEvent` keeps its existing inline booleans for backward compatibility. Note: TUI's `InputEvent.Mouse` only has `shift`/`alt`/`ctrl` (no `meta`). TUI backend sets `meta = false` always. DOM backend populates all four from the browser event.
- `ScrollEvent` fields: `scrollX/scrollY` = absolute scroll position; `deltaX/deltaY` = change since last event.

### 1.2 Verify

- No `PointerButton`, `Modifiers`, `PointerEvent`, or `ScrollEvent` exist anywhere in the codebase yet.
- These are pure data types with no effect dependencies — they sit alongside `KeyEvent` and `Keyboard`.
- All use `derives CanEqual` consistent with existing types.

---

## Phase 2: Extend Attrs with New Handler Fields

**File: `kyo-ui/shared/src/main/scala/kyo/UI.scala`**

### 2.1 Add Fields to Attrs

Update `internal.Attrs` (currently lines 158–169):

```scala
final case class Attrs(
    identifier: Maybe[String] = Absent,
    hidden: Maybe[Boolean | Signal[Boolean]] = Absent,
    tabIndex: Maybe[Int] = Absent,
    uiStyle: Style | Signal[Style] = Style.empty,
    pointerPassthrough: Maybe[Boolean] = Absent,                    // NEW
    onClick: Maybe[Unit < Async] = Absent,
    onClickSelf: Maybe[Unit < Async] = Absent,
    onClickOutside: Maybe[Unit < Async] = Absent,                   // NEW
    onPointer: Maybe[PointerEvent => Unit < Async] = Absent,        // NEW
    onScroll: Maybe[ScrollEvent => Unit < Async] = Absent,          // NEW
    onKeyDown: Maybe[KeyEvent => Unit < Async] = Absent,
    onKeyUp: Maybe[KeyEvent => Unit < Async] = Absent,
    onFocus: Maybe[Unit < Async] = Absent,
    onBlur: Maybe[Unit < Async] = Absent
)
```

### 2.2 Add Methods to Element (pointerPassthrough)

Add to the `Element` trait (after `style` methods, around line 103):

```scala
def pointerPassthrough(v: Boolean = true): Self = withAttrs(attrs.copy(pointerPassthrough = Present(v)))
```

**Note:** `pointerPassthrough` goes on `Element`, not `Interactive`, because it's a property of any element (e.g., a decorative overlay `div` that should not block pointer events).

### 2.3 Add Methods to Interactive

Add to the `Interactive` trait (after existing handlers, around line 114):

```scala
def onClickOutside(action: Unit < Async): Self      = withAttrs(attrs.copy(onClickOutside = Present(action)))
def onPointer(f: PointerEvent => Unit < Async): Self = withAttrs(attrs.copy(onPointer = Present(f)))
def onScroll(f: ScrollEvent => Unit < Async): Self   = withAttrs(attrs.copy(onScroll = Present(f)))
```

### 2.4 Verify

- `pointerPassthrough` is on `Element` (available to all elements including non-interactive ones like `Hr`, `Img`).
- `onClickOutside`, `onPointer`, `onScroll` are on `Interactive` (consistent with `onClick`, `onKeyDown`, etc.).
- All new `Attrs` fields have `= Absent` defaults — no breaking changes to existing `Attrs()` construction.
- No duplication: `onPointer` is a single handler (not three separate `onPointerDown`/`onPointerUp`/`onPointerMove`), matching the approved design.

---

## Phase 3: Transition and Easing on Style

**File: `kyo-ui/shared/src/main/scala/kyo/Style.scala`**

### 3.1 Add Easing Enum

Add after the `GradientDirection` enum (lines 543–544), among the other enums inside `object Style`:

```scala
enum Easing derives CanEqual:
    case linear, easeIn, easeOut, easeInOut
```

### 3.2 Add TransitionProp

Add to the `Prop` enum (after `DisabledProp`, before `end Prop`):

```scala
// Transition
case TransitionProp(duration: Duration, easing: Easing)
```

**Note:** `Duration` is `kyo.Duration` (opaque over `Long` nanos). Already available in the `kyo` package — no import needed since `Style.scala` is in `package kyo`.

### 3.3 Add Transition Methods on Style Instance

Add after `bgGradient` methods (around line 279), before `end Style`:

```scala
// Transition

def transition(duration: Duration, easing: Easing): Style =
    val clamped = if duration.toNanos < 0 then Duration.Zero else duration
    appendProp(Prop.TransitionProp(clamped, easing))
def transition(duration: Duration): Style =
    transition(duration, Easing.easeInOut)
def transition(duration: Duration, f: Easing.type => Easing): Style =
    transition(duration, f(Easing))
```

**Note on clamping:** `Duration` is opaque over `Long` — negative values are representable (e.g., via arithmetic). Clamping to `Duration.Zero` follows the existing pattern (see `opacity` clamping to 0.0–1.0, `lineHeight` to 0.1+, `flexGrow` to 0.0+).

### 3.4 Add Transition Methods on Style Companion

Add after the `disabled` companion factory methods (around line 419):

```scala
def transition(duration: Duration, easing: Easing): Style          = empty.transition(duration, easing)
def transition(duration: Duration): Style                          = empty.transition(duration)
def transition(duration: Duration, f: Easing.type => Easing): Style = empty.transition(duration, f)
```

### 3.5 Verify

- `Duration` is `kyo.Duration`, available without import since `Style.scala` is in `package kyo`. kyo-ui depends on kyo-data which defines `Duration` (confirmed by use of `Maybe`, `Span` from kyo-data throughout kyo-ui).
- `Easing` follows the same pattern as other Style enums (`FontWeight`, `TextAlign`, etc.): lowercase cases, `derives CanEqual`.
- `TransitionProp` stores duration + easing. Semantics: applies to all animatable style properties on the element. This is intentionally simple — one transition per element.
- Lambda overload `f: Easing.type => Easing` enables `_.easeIn` syntax.
- Default easing is `easeInOut` (most common UI transition curve).
- Duration clamped to non-negative (follows existing pattern for `opacity`, `flexGrow`, etc.).

---

## Phase 4: Update CssStyleRenderer

**File: `kyo-ui/shared/src/main/scala/kyo/internal/CssStyleRenderer.scala`**

### 4.1 Add TransitionProp Rendering

Add a new case in the `renderProp` match, before the pseudo-state catch-all at line 205 (`case _: HoverProp | ...`):

```scala
case TransitionProp(duration, easing) =>
    val ms = duration.toMillis
    val easingStr = easing match
        case Easing.linear    => "linear"
        case Easing.easeIn    => "ease-in"
        case Easing.easeOut   => "ease-out"
        case Easing.easeInOut => "ease-in-out"
    s"transition: all ${ms}ms $easingStr;"
```

### 4.2 Add Easing Import

Add to the imports at the top:

```scala
import kyo.Style.Easing
```

### 4.3 Verify

- The `renderProp` match must remain exhaustive. `TransitionProp` is a new `Prop` case — adding it prevents a match warning.
- CSS output: `transition: all 200ms ease-in-out;` — standard CSS transition shorthand.
- `duration.toMillis` returns `Long` — no allocation.

---

## Phase 5: Update UIDsl Exports

**File: `kyo-ui/shared/src/main/scala/kyo/UIDsl.scala`**

### 5.1 Add New Exports

Add after the existing exports:

```scala
export Style.Easing
export UI.Modifiers
export UI.PointerButton
export UI.PointerEvent
export UI.ScrollEvent
```

### 5.2 Verify

- All new public types are exported so users can write `import kyo.UIDsl.*` and use them without qualification.
- Alphabetical order within UI/Style groups.

---

## Phase 6: Update TUI Backend — Attrs Handling

The TUI backend reads `Attrs` fields in `TuiFocus` and `TuiFlatten`. New fields need to be wired through.

### 6.1 TuiFocus — onPointer Dispatch

**File: `kyo-ui/jvm/src/main/scala/kyo/internal/TuiFocus.scala`**

In the mouse event dispatch section (around the `handleMousePress` method), after calling `fireClickHandler`:

- On `LeftPress`/`LeftRelease`/`RightPress`/`RightRelease`/`MiddlePress`/`MiddleRelease`: construct a `PointerEvent.Down` or `PointerEvent.Up` with:
  - `x`/`y` = mouse position relative to the hit element's top-left (subtract `layout.x(hitIdx)`, `layout.y(hitIdx)`)
  - `button` = map from `MouseKind` to `PointerButton`
  - `modifiers` = `Modifiers(ctrl, alt, shift, meta)` from the `InputEvent.Mouse` fields
  - Fire `fireHandler(elem.attrs.onPointer, pointerEvent)`
- On `Move`: construct `PointerEvent.Move`, fire similarly.

**Note on `pointerPassthrough`**: During hit testing (`hitTestAll`), skip elements with `PointerPassthroughBit` set in `lFlags` (set during flatten in Phase 6.5). This avoids reading the element ref during hit testing — the bit check is O(1) on the existing lFlags array.

### 6.2 TuiFocus — onScroll Dispatch

In the scroll handling section (where `ScrollUp`/`ScrollDown` are processed):

After adjusting internal scroll state, construct a `ScrollEvent`:
- `scrollX/scrollY` = current scroll position of the scrollable container
- `deltaX` = 0 (terminal scrolling is vertical only)
- `deltaY` = +1 or -1 (or the scroll delta from the event)
- Fire `fireHandler(elem.attrs.onScroll, scrollEvent)` on the scrollable container

### 6.3 TuiFocus — onClickSelf Precedence

Update `fireClickHandler` to implement the approved precedence:

```
When both onClick and onClickSelf are set on the same element:
- Direct click (hitIdx == handler element) → fire onClickSelf only
- Child click (hitIdx != handler element, found via parent walk) → fire onClick only
When only one is set, fire it for any matching click.
```

The current `fireClickHandler` walks the parent chain from `hitIdx` to find the nearest `onClick`. Modify to:

1. At `hitIdx`: check for `onClickSelf` first. If present and this is the direct hit, fire it and stop.
2. If no `onClickSelf` at `hitIdx` (or not a direct click scenario), walk parent chain for `onClick` as before.
3. When walking the parent chain, at each node: if `hitIdx == current node` and `onClickSelf` is present, fire `onClickSelf` (not `onClick`). If `hitIdx != current node` and `onClick` is present, fire `onClick`.

### 6.4 TuiFocus — onClickOutside Dispatch

After handling a mouse click (any `LeftPress`):

1. Walk all elements in the layout.
2. For each element with `onClickOutside` present: if the click position is NOT within that element's bounds, fire its `onClickOutside` action.
3. Skip hidden elements.

This is O(n) per click but clicks are infrequent. Alternative: maintain a list of elements with `onClickOutside` during flatten (similar to how overlay elements are tracked).

### 6.5 TuiFlatten — pointerPassthrough Flag

**File: `kyo-ui/jvm/src/main/scala/kyo/internal/TuiFlatten.scala`**

During element flattening, if `attrs.pointerPassthrough` is `Present(true)`, set a flag bit in `lFlags` so that `hitTestAll` can skip the element without needing to read the element ref.

**TuiLayout**: Add a new bit constant `PointerPassthroughBit = 18` to `lFlags` (next available bit after `NoWrapBit = 17` at line 201). Add the corresponding `inline def isPointerPassthrough(lf: Int): Boolean = (lf & (1 << 18)) != 0` accessor.

### 6.6 Verify

- `onPointer` handler receives element-local coordinates (not terminal-global).
- `onPointer(Down)` and `onClick` both fire on the same LeftPress — correct and intentional. `onPointer` is low-level (raw events with coordinates/button/modifiers); `onClick` is high-level (semantic action). Same as DOM where `pointerdown` fires before `click`.
- `pointerPassthrough` prevents hit testing — element is "transparent" to pointer events.
- `onClickSelf` + `onClick` never double-fire on the same click.
- `onClickOutside` fires for ALL elements that have it registered and are outside the click point (not just the first one found).
- `onScroll` fires after internal scroll state is updated.
- No existing behavior is changed — all modifications are additive (new fields, new dispatch paths).

---

## Phase 7: Update DOM Backend — Attrs Handling

**File: `kyo-ui/js/src/main/scala/kyo/DomBackend.scala`**

### 7.1 applyAttrs — New Handlers

In `applyAttrs` (around lines 132-171), add handling for new Attrs fields:

**onPointer**: Add three event listeners:
```scala
// pointerdown → PointerEvent.Down
// pointerup → PointerEvent.Up
// pointermove → PointerEvent.Move
```
Each listener extracts element-local coordinates via `getBoundingClientRect()` offset, maps `event.button` to `PointerButton`, constructs `Modifiers`, and calls `runHandler(f(pointerEvent))`.

**onScroll**: Add `scroll` event listener:
```scala
// scroll → ScrollEvent(el.scrollLeft, el.scrollTop, deltaX, deltaY)
```
`deltaX`/`deltaY` computed as difference from previous scroll position (track via closure or data attribute).

**onClickOutside**: Add `document.addEventListener("pointerdown", ...)` that checks if the event target is outside the element:
```scala
if !el.contains(event.target) then runHandler(action)
```
Must clean up the document listener on element disconnect.

**onClickSelf**: Already exists in Attrs but NOT currently wired in DOM `applyAttrs` (only `onClick` is wired at line 143). Must be wired now:
```scala
element.addEventListener("click", e => {
    if e.target == element then runHandler(action)
    // When both onClick and onClickSelf: onClick handler should check e.target != element
})
```

**pointerPassthrough**: Set CSS `pointer-events: none` on the element:
```scala
if attrs.pointerPassthrough.contains(true) then
    element.style.pointerEvents = "none"
```

### 7.2 onClick + onClickSelf Precedence in DOM

For the approved precedence semantics:
- `onClickSelf`: listen with handler that checks `e.target == element`
- `onClick`: listen with handler that checks `e.target != element` (only if `onClickSelf` is also present) or fires unconditionally (if `onClickSelf` is absent)

Implementation: single `click` listener that dispatches:
```scala
element.addEventListener("click", e => {
    val isDirect = e.target == element
    if isDirect && attrs.onClickSelf.isDefined then
        runHandler(attrs.onClickSelf.get)
    else if !isDirect && attrs.onClick.isDefined then
        runHandler(attrs.onClick.get)
    else if isDirect && attrs.onClick.isDefined && attrs.onClickSelf.isEmpty then
        runHandler(attrs.onClick.get)
})
```

### 7.3 Verify

- DOM `pointerdown`/`pointerup`/`pointermove` events map naturally to `PointerEvent` enum.
- `getBoundingClientRect()` provides element-local coordinates.
- `event.button`: 0=Left, 1=Middle, 2=Right — map to `PointerButton`.
- `pointer-events: none` is the standard CSS property for pointer passthrough.
- Document-level listener for `onClickOutside` must be cleaned up on element removal.
- `onClickSelf` + `onClick` precedence: single listener, conditional dispatch, no double-fire.

---

## Phase 8: Update TuiStyle — TransitionProp

**File: `kyo-ui/jvm/src/main/scala/kyo/internal/TuiStyle.scala`**

### 8.1 Handle TransitionProp in resolve

Add a no-op case for `TransitionProp` in the `applyProps` match (after the last case `BgGradientProp` around line 268). The `applyProps` match has NO catch-all — every `Prop` case must be handled explicitly:

```scala
case _: TransitionProp => () // TUI doesn't support animated transitions
```

Also check the `overlay` method (lines 275-311) — it already has a `case _ => ()` catch-all at line 310, so `TransitionProp` is handled there automatically.

### 8.2 Verify

- `applyProps` has no catch-all — the new case is mandatory to prevent `MatchError`.
- `overlay` already has `case _ => ()` — no change needed there.
- TUI correctly ignores transitions — terminal rendering is immediate, no animation loop.

---

## Phase 9: Update Tests

### 9.1 UITest — New Types and Methods

**File: `kyo-ui/shared/src/test/scala/kyo/UITest.scala`**

Add test sections:

**"PointerButton"**: Verify enum values exist (`Left`, `Right`, `Middle`).

**"Modifiers"**: Verify default construction (`Modifiers()` has all false), explicit construction, equality.

**"PointerEvent"**: Verify `Down`, `Up`, `Move` construction. `Move` has no button field. Coordinates are preserved.

**"ScrollEvent"**: Verify field access, equality.

**"Element.pointerPassthrough"**: Verify `div.pointerPassthrough()` sets the attr. Verify default is `Absent`.

**"Interactive.onClickOutside"**: Verify `div.onClickOutside(action)` sets the attr.

**"Interactive.onPointer"**: Verify `div.onPointer(f)` sets the attr.

**"Interactive.onScroll"**: Verify `div.onScroll(f)` sets the attr.

**"onClickSelf"**: Already tested (verify existing tests still pass).

### 9.2 StyleTest — Transition

**File: `kyo-ui/shared/src/test/scala/kyo/StyleTest.scala`**

Add test section:

**"transition"**:
- `Style.transition(200.millis)` produces `TransitionProp(200ms, easeInOut)`
- `Style.transition(300.millis, Easing.linear)` produces correct prop
- Lambda overload: `Style.transition(200.millis, _.easeIn)` works
- CSS rendering: verify `transition: all 200ms ease-in-out;`
- Composition: `Style.bg("#fff").transition(200.millis)` has both props

**"Easing"**:
- Verify all enum values exist: `linear`, `easeIn`, `easeOut`, `easeInOut`

### 9.3 TuiSimulatorTest — Integration

**File: `kyo-ui/jvm/src/test/scala/kyo/TuiSimulatorTest.scala`**

Add test sections:

**"onClickSelf precedence"**:
- Element with both `onClick` and `onClickSelf`: direct click fires `onClickSelf` only
- Element with both: click on child fires `onClick` only
- Element with only `onClick`: fires for both direct and child clicks
- Element with only `onClickSelf`: fires only for direct clicks

**"pointerPassthrough"**:
- Element with `pointerPassthrough` is not hit by click (click passes to element behind)

**"onClickOutside"**:
- Click outside element fires `onClickOutside`
- Click inside element does NOT fire `onClickOutside`

**"onPointer"** (if TUI mouse events are wired):
- Mouse press constructs `PointerEvent.Down` with correct coordinates
- Mouse release constructs `PointerEvent.Up`

**"onScroll handler"**:
- Scroll on scrollable container fires `onScroll` callback with correct delta

### 9.4 Verify

- All new types have unit tests for construction and equality.
- All new Attrs fields have tests verifying they're set correctly.
- All new Style props have tests for construction, composition, and CSS rendering.
- Integration tests cover the interaction semantics (precedence, passthrough, outside detection).
- Existing tests (240 total) remain unchanged and passing.

---

## Phase 10: Update DomStyleSheet — TransitionProp

**File: `kyo-ui/js/src/main/scala/kyo/internal/DomStyleSheet.scala`**

### 10.1 No Changes Needed

`DomStyleSheet.apply` uses `CssStyleRenderer.render(style)` for inline styles and pseudo-state CSS rules. Since `CssStyleRenderer` already handles `TransitionProp` (Phase 4), `DomStyleSheet` picks it up automatically.

### 10.2 Verify

- `TransitionProp` is rendered as inline CSS by `CssStyleRenderer`.
- Pseudo-state transitions work: `Style.bg("#fff").hover(_.bg("#eee")).transition(200.millis)` — the transition CSS is in the base rule, hover CSS is in the `:hover` rule. Standard CSS transition mechanics apply.

---

## File Change Summary

| File | Changes |
|------|---------|
| `UI.scala` | Add `PointerButton`, `Modifiers`, `PointerEvent`, `ScrollEvent` types. Add `pointerPassthrough`, `onClickOutside`, `onPointer`, `onScroll` to `Attrs`. Add `pointerPassthrough` to `Element`. Add `onClickOutside`, `onPointer`, `onScroll` to `Interactive`. |
| `Style.scala` | Add `Easing` enum. Add `TransitionProp` to `Prop`. Add `transition()` methods to Style instance + companion. |
| `UIDsl.scala` | Add exports for `Easing`, `Modifiers`, `PointerButton`, `PointerEvent`, `ScrollEvent`. |
| `CssStyleRenderer.scala` | Add `TransitionProp` case in `renderProp`. Add `Easing` import. |
| `TuiStyle.scala` | Add no-op case for `TransitionProp`. |
| `TuiFocus.scala` | Wire `onPointer`, `onScroll`, `onClickOutside` dispatch. Implement `onClickSelf`/`onClick` precedence. Respect `pointerPassthrough` in hit testing. |
| `TuiFlatten.scala` | Map `pointerPassthrough` to `lFlags` bit. |
| `TuiLayout.scala` | Add `PointerPassthroughBit` constant. |
| `DomBackend.scala` | Wire `onPointer` (3 DOM listeners), `onScroll` (1 listener), `onClickOutside` (document listener), `onClickSelf`/`onClick` precedence, `pointerPassthrough` (CSS `pointer-events: none`). |
| `UITest.scala` | Add tests for new types and methods (~25 new tests). |
| `StyleTest.scala` | Add tests for `transition`, `Easing` (~8 new tests). |
| `TuiSimulatorTest.scala` | Add integration tests for precedence, passthrough, outside, pointer, scroll (~12 new tests). |

## Execution Order

```
Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5  (shared API — must compile)
    ↓
Phase 6 (TUI backend)  ←→  Phase 7 (DOM backend)  (can be parallel)
    ↓                            ↓
Phase 8 (TuiStyle no-op)   Phase 10 (DomStyleSheet verify)
    ↓
Phase 9 (tests — all backends must compile first)
```

Phases 1–5 are the core API surface and must be done sequentially. Phases 6–8 (TUI) and Phase 7+10 (DOM) can be done in parallel. Phase 9 (tests) comes last after all backends compile.

## Cross-cutting Concerns

### onClick/onClickSelf Precedence (enforced in Phase 6 + Phase 7)

| Scenario | Direct click on element | Click on child element |
|----------|------------------------|----------------------|
| Only `onClick` | fires | fires |
| Only `onClickSelf` | fires | does NOT fire |
| Both `onClick` + `onClickSelf` | `onClickSelf` fires, `onClick` does NOT | `onClick` fires, `onClickSelf` does NOT |

This is enforced identically in both TUI and DOM backends.

### pointerPassthrough (enforced in Phase 6 + Phase 7)

- TUI: skip element in `hitTestAll` when `PointerPassthroughBit` is set in `lFlags`
- DOM: set CSS `pointer-events: none`
- Both: the element is visible but does not intercept pointer events

### Coordinate System for PointerEvent

- Coordinates are element-local: (0, 0) is the element's top-left corner
- TUI: subtract `layout.x(hitIdx)` and `layout.y(hitIdx)` from terminal mouse position
- DOM: use `event.clientX - element.getBoundingClientRect().left` (and similar for Y)

### Transition Semantics

- `transition(duration, easing)` applies to ALL animatable style properties
- One transition per element (last wins via `appendProp` dedup)
- TUI: no-op (terminal rendering is immediate)
- DOM: standard CSS `transition: all <ms>ms <easing>;`
- Not per-property — deliberately simple for v1
