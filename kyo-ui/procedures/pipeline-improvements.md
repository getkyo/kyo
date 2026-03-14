# Pipeline Improvement Proposal

## Context

After three iterations of interaction testing (118 tests, 94 passing, 24 failing), clear patterns emerged in the remaining bugs and in the fixes that were needed. This document proposes targeted improvements that address structural weaknesses exposed by testing, without redesigning the pipeline's fundamentals.

---

## Problem 1: Handler Closures Capture Stale Values

### What's happening

Every widget-specific lowering method (`lowerTextInput`, `lowerBooleanInput`, `lowerSelect`, `lowerRangeInput`) reads reactive state into local vals, then closes over those vals in handler lambdas. The handlers execute later during Dispatch, but they see state from lowering time:

```scala
// Lower time — frame N
val currentValue = readStringOrRef(ti.value, key, ctx.state, "")  // "AB"
val disabled = readBooleanOrSignal(ti.disabled, key, "disabled", ctx.state)  // false

val widgetOnKeyDown: UI.KeyEvent => Unit < Async = ke =>
    if !readOnly && !disabled then          // uses captured `disabled` from frame N
        ke.key match
            case UI.Keyboard.Backspace =>
                val pos = cursorPos.get()   // reads ref at dispatch time ✓
                val newVal = currentValue.substring(0, pos - 1)  // uses captured `currentValue` from frame N ✗
                                            + currentValue.substring(pos)
```

The `cursorPos.get()` correctly reads the ref at dispatch time. But `currentValue.substring(...)` uses the value captured at frame N. If the user typed characters between renders, the handler sees stale text.

This caused:
- Checkbox toggle not working (eager `checkedRef.set()` at lower time, fixed with `Sync.Unsafe.defer`)
- Select toggle eagerly flipping `expanded` ref at lower time
- Text editing using stale `currentValue` for string manipulation

### Where it affects

| Method | Captured val | Should be ref read |
|--------|-------------|-------------------|
| `lowerTextInput` | `currentValue: String` | Should re-read from cached ref at handler time |
| `lowerTextInput` | `disabled: Boolean` | Should re-read from signal/cache |
| `lowerBooleanInput` | `disabled: Boolean` | Should re-read from signal/cache |
| `lowerSelect` | `isExpanded: Boolean` | Already a ref, but `toggleClick` reads eagerly |
| `lowerSelect` | `disabled: Boolean` | Should re-read |
| `lowerRangeInput` | `currentValue: Double` | Should re-read from ref |
| `lowerRangeInput` | `disabled: Boolean` | Should re-read |

### Proposed fix: Defer all handler bodies

The pattern that already works (checkbox fix) should be applied systematically to ALL widget handlers:

```scala
// Before — captures resolved values
val widgetOnKeyDown: UI.KeyEvent => Unit < Async = ke =>
    if !readOnly && !disabled then  // stale disabled
        ke.key match
            case UI.Keyboard.Char(c) =>
                val pos = cursorPos.get()
                val newVal = currentValue.substring(0, pos) + c + currentValue.substring(pos) // stale
                writeStringRef(ti.value, key, ctx.state, newVal)

// After — reads fresh state inside Sync.Unsafe.defer
val widgetOnKeyDown: UI.KeyEvent => Unit < Async = ke =>
    Sync.Unsafe.defer {
        val currentValue = readStringOrRef(ti.value, key, ctx.state, "")
        val disabled = readBooleanOrSignal(ti.disabled, key, "disabled", ctx.state)
        if !readOnly && !disabled then
            ke.key match
                case UI.Keyboard.Char(c) =>
                    val pos = cursorPos.get()
                    val newVal = currentValue.substring(0, pos) + c + currentValue.substring(pos) // fresh
                    writeStringRef(ti.value, key, ctx.state, newVal)
                // ...
        else noop
    }
```

The handler closure still captures `key`, `ctx.state`, `ti.value` (stable references), but reads their *current values* at dispatch time inside the deferred block. The overhead is one `Sync.Unsafe.defer` per handler invocation — negligible.

Apply this to:
- `lowerTextInput`: `widgetOnKeyDown` (including `insertChar`)
- `lowerBooleanInput`: `widgetOnClick`  (already done)
- `lowerSelect`: `toggleClick`
- `lowerRangeInput`: `widgetOnKeyDown`

### Design principle compliance

The plan says: "Handler closures capture `SignalRef.Unsafe` refs, not raw values — always read fresh state." This proposal enforces that rule mechanically rather than relying on developers to remember it.

---

## Problem 2: Text Nodes Don't Participate in Flex Shrink

### What's happening

`measureFlexChildren` handles three IR variants differently:

```scala
case nd: Styled.Node =>
    mainSizes(i) = ...
    grow(i) = nd.style.flexGrow      // ← nodes participate in flex
    shrink(i) = nd.style.flexShrink   // ← nodes can shrink

case t: Styled.Text =>
    mainSizes(i) = measureWidth(t)    // ← intrinsic width, may exceed parent
    // grow and shrink arrays stay at 0.0 — text can't shrink!

case _: Styled.Cursor =>
    mainSizes(i) = 1
```

When a div with `width(5.px)` contains text "abcdefghij", the div's content width is 5. Inside `layoutFlex`, the text child measures as 10 (intrinsic width). `freeSpace = 5 - 10 = -5`. But `shrink(0) = 0.0`, so `distributeShrink` does nothing. The text gets `childMain = 10`, overflowing the parent.

Then `positionChildren` passes `Rect(x, y, 10, h)` to `arrange`, and `arrange` for `Styled.Text` does `math.min(measureWidth(node), available.w)` = `min(10, 10)` = 10. The text gets a 10-wide bounds inside a 5-wide parent.

### The root issue

Text has no `FlatStyle` with flex properties in the `Styled.Text` variant. The `measureFlexChildren` function can't read `flexShrink` from a Text node because `Styled.Text` only carries `(value: String, style: FlatStyle)` — the style IS there, but the code doesn't read grow/shrink from it.

Actually, `Styled.Text.style` is a `FlatStyle` which has `flexShrink` (inherited from parent via Styler). But `measureFlexChildren` doesn't access it for Text nodes.

### Proposed fix

Read `flexShrink` from text nodes' style in `measureFlexChildren`:

```scala
case t: Styled.Text =>
    mainSizes(i) = if isColumn then measureHeight(t, cw) else measureWidth(t)
    crossSizes(i) = if isColumn then measureWidth(t) else measureHeight(t, cw)
    grow(i) = t.style.flexGrow
    shrink(i) = t.style.flexShrink  // allow text to shrink when parent overflows
```

With default `flexShrink = 1.0` from Styler inheritance, text will shrink proportionally to fit the parent. Then `positionChildren` passes a smaller `available.w` to `arrange`, and `arrange` caps the text width correctly.

### Why this is the right layer

The alternative — having Painter truncate text that extends past its parent's clip rect — is a band-aid. Layout should produce correct bounds. Painter should paint within bounds. The contract is: Layout assigns sizes that fit, Painter fills them. Breaking this means every downstream step must re-validate sizes.

---

## Problem 3: Focusable Registration is Scattered

### What's happening

Five separate code paths decide whether an element is focusable:

```
lowerPassthrough:   if Focusable trait && tabIndex absent/>=0 → add
lowerTextInput:     if !disabled && tabIndex absent/>=0 → add
lowerBooleanInput:  if !disabled && tabIndex absent/>=0 → add
lowerSelect:        if !disabled && tabIndex absent/>=0 → add
lowerRangeInput:    if !disabled && tabIndex absent/>=0 → add
```

The rules are almost identical but with subtle differences:
- `lowerPassthrough` checks `Focusable` trait; widget methods don't (they know they're focusable)
- `lowerPassthrough` doesn't check `disabled`; widget methods do (the disabled check is already done earlier)
- Actually `lowerPassthrough` does check disabled (via `isDisabled`) for the `isFocusable` guard

When a new focusable element is added, or when the focusable rules change, all five paths must be updated.

### Proposed fix

Extract a single method:

```scala
private def registerFocusable(
    elem: UI.Element,
    key: WidgetKey,
    disabled: Boolean,
    ctx: Ctx
): Unit =
    if disabled then ()
    else
        val isFocusable = elem match
            case _: UI.Focusable  => true  // Button, Anchor, RangeInput, FileInput
            case _: UI.TextInput  => true  // Input, Password, Textarea, etc.
            case _: UI.BooleanInput => true  // Checkbox, Radio
            case _: UI.PickerInput => true  // Select, DateInput, etc.
            case _ =>
                elem.attrs.tabIndex.exists(_ >= 0)  // explicit tabIndex
        if isFocusable then
            elem.attrs.tabIndex match
                case Present(idx) if idx < 0 => () // tabIndex=-1 explicitly removes from tab order
                case _                       => ctx.focusables.addOne(key)
```

Call it from every lowering path:

```scala
// In lowerPassthrough, lowerTextInput, lowerBooleanInput, lowerSelect, lowerRangeInput:
registerFocusable(elem, key, disabled, ctx)
```

### Benefits

- Single source of truth for "what is focusable"
- New widget types only need to extend the right trait
- `tabIndex = -1` explicitly removes from tab order (HTML convention, currently not handled)
- Testable in isolation

---

## Problem 4: Materialization and Widget Expansion are Separate Traversals

### What's happening

Phase 1 (materialize) walks the UI tree to cache `SignalRef.Unsafe` values. Phase 2 (walk) walks the same tree to produce `Resolved` IR. Both traversals must agree on which values need caching:

```scala
// Phase 1 — materializeElement
val valueEffect: Unit < (Async & Scope) = elem match
    case ti: UI.TextInput   => materializeMaybeStringSignal(ti.value, ...)
    case pi: UI.PickerInput => materializeMaybeStringSignal(pi.value, ...)
    case _                  => ()

// Phase 2 — lowerTextInput (called from walk)
val currentValue = readStringOrRef(ti.value, key, ctx.state, "")
// ↑ reads from cache that Phase 1 should have populated
```

When `RangeInput.value` was added as a `SignalRef[Double]`, Phase 2 (`lowerRangeInput`) tried to read it with `asInstanceOf[SignalRef.Unsafe[Double]].get()`, but Phase 1 never materialized it. Result: `ClassCastException`.

### The structural problem

The two phases have an implicit contract: "everything Phase 2 reads from cache, Phase 1 must have written." Nothing enforces this. The two traversals match on different type hierarchies (`materializeElement` matches trait types; `lowerElement` matches concrete types) and each can be extended independently.

### Proposed fix: Co-locate materialization with consumption

Instead of a separate Phase 1 traversal, each widget lowering method materializes what it needs:

```scala
// Current: two separate traversals
// Phase 1: materialize(ui, state, path) walks tree, calls materializeElement
// Phase 2: walk(ui, path, ctx) walks tree, calls lowerTextInput

// Proposed: single traversal, lower returns < (Async & Scope)
private def lowerTextInput(ti: UI.TextInput, key: WidgetKey, ...)(using Frame): Resolved < (Async & Scope) =
    materializeMaybeStringSignal(ti.value, key, "value", ctx.state).andThen {
        Sync.Unsafe.defer {
            val currentValue = readStringOrRef(ti.value, key, ctx.state, "")
            // ... rest of lowering (synchronous under AllowUnsafe)
        }
    }
```

### Tradeoff

The current design separates "async materialization" (Phase 1, `< (Async & Scope)`) from "synchronous walk" (Phase 2, `AllowUnsafe`). This is clean in theory — Phase 2 is fast because all async work is done. But in practice, Phase 1 and Phase 2 must stay perfectly synchronized, and bugs from desynchronization are silent until runtime.

Unifying them means the walk returns `< (Async & Scope)` instead of being purely synchronous. The overhead is small — `Sync.Unsafe.defer` wraps the synchronous parts and the async materialization effects are already needed.

The benefit: **it becomes impossible to forget materialization**, because the materialization call and the read call are in the same method, visible in the same screen of code.

### Alternative: Materialization registry

If unification is too disruptive, add a compile-time-like check: each `readXxxOrRef` method should call a `requireMaterialized(key, suffix, state)` that asserts the cache entry exists and throws a clear error instead of silently returning a default or crashing with `ClassCastException`:

```scala
private def readStringOrRef(value: Maybe[String | SignalRef[String]], key: WidgetKey, state: ScreenState, default: String)(using AllowUnsafe): String =
    if value.isEmpty then default
    else value.get match
        case s: String => s
        case _: SignalRef[?] =>
            state.widgetState.get[SignalRef.Unsafe[String]](key.child("value")) match
                case Present(ref) => ref.get()
                case _ => throw IllegalStateException(
                    s"SignalRef for ${key.child("value")} not materialized. " +
                    s"Add materializeMaybeStringSignal call to materializeElement."
                )
```

This turns a silent bug into a loud failure with a fix instruction.

---

## Problem 5: `br` is Modeled as Text, but Line Breaks are a Layout Concern

### What's happening

Lower converts `UI.Br` to `Resolved.Text("\n")`:

```scala
case _: UI.Br => Resolved.Text("\n")
```

This `\n` passes through Styler as `Styled.Text("\n", parentStyle)`, then Layout measures it as a 1-character-wide text node. Painter writes `\n` as a character to the cell grid — it doesn't trigger a line advance.

The result: `span("top"), br, span("bot")` renders as `"topbo"` — the `\n` character is invisible in the cell grid (terminals don't interpret `\n` in a cell as a line break), and the text following it continues on the same line.

### The root issue

A cell grid represents a 2D grid of characters. There's no concept of "advance to next line" — each cell is addressed by (x, y). A `\n` character placed in a cell is simply a non-printing character that takes up one column of space.

Line breaks need to be a **layout** concern: "stop the current inline flow and advance Y to the next line." This is how `br` works in HTML — it's a block-level interruption in inline flow.

### Proposed fix: New IR variant `Resolved.Break`

```scala
enum Resolved:
    case Node(tag: ElemTag, style: Style, handlers: Handlers, children: Chunk[Resolved])
    case Text(value: String)
    case Cursor(charOffset: Int)
    case Break  // ← new: signals a line advance in inline layout
```

Lower produces:
```scala
case _: UI.Br => Resolved.Break
```

Styler passes it through (no style to apply):
```scala
enum Styled:
    // ...
    case Break
```

Layout handles it in `layoutFlex` / `positionChildren`: when encountering a `Break` in row (inline) flow, reset X to the content start and advance Y by one line:

```scala
case Styled.Break =>
    // In row direction: advance to next line
    // In column direction: no-op (already on separate line)
    if !isColumn then
        mainPos = 0  // reset X
        crossPos += 1  // advance Y
    // Break produces no Laid node — it's consumed by layout
```

Painter never sees `Break` — it's consumed during layout positioning.

### Benefits

- `br` works correctly as a line break
- No special character handling in Painter
- The break is structural (layout) not textual (content)
- `hr` can similarly produce `Resolved.Break` + a `Resolved.Node` with a border-bottom

---

## Problem 6: Select `toggleClick` Has Eager Side Effect

### What's happening

```scala
val isExpanded = expanded.get()  // read at lower time

val toggleClick: Unit < Async =
    if !disabled then expanded.set(!isExpanded)  // uses captured isExpanded
    else noop
```

`expanded.set(!isExpanded)` executes the `set` immediately at lower time because `expanded.set()` is a `SignalRef.Unsafe` operation that runs eagerly. The `if !disabled` guard is also captured from lower time.

This means the select toggles open/closed at lower time, not at click time. The toggle effect happens during rendering, not during user interaction.

### Proposed fix

Same pattern as the checkbox fix — wrap in `Sync.Unsafe.defer`:

```scala
val toggleClick: Unit < Async =
    Sync.Unsafe.defer {
        val disabled = readBooleanOrSignal(sel.disabled, key, "disabled", ctx.state)
        if !disabled then
            expanded.set(!expanded.get())  // read fresh expanded state
        else noop
    }
```

---

## Problem 7: `mergePseudoStates` Uses `var` and `isInstanceOf`

### What's happening

```scala
private def mergePseudoStates(...): Style =
    var result = base
    if focused.contains(key) then
        result = result ++ extractPseudo(base, _.isInstanceOf[Style.Prop.FocusProp])
    if hovered.contains(key) then
        result = result ++ extractPseudo(base, _.isInstanceOf[Style.Prop.HoverProp])
    // ...
    result
```

This uses `var` for accumulation and `isInstanceOf` for type dispatch — both explicitly banned by the quality plan.

### Proposed fix

Fold over a list of (condition, predicate) pairs:

```scala
private def mergePseudoStates(
    base: Style,
    key: WidgetKey,
    focused: Maybe[WidgetKey],
    hovered: Maybe[WidgetKey],
    active: Maybe[WidgetKey],
    disabled: Boolean
): Style =
    val pseudoStates = Chunk(
        (focused.contains(key), (p: Style.Prop) => p match { case _: Style.Prop.FocusProp => true; case _ => false }),
        (hovered.contains(key), (p: Style.Prop) => p match { case _: Style.Prop.HoverProp => true; case _ => false }),
        (active.contains(key),  (p: Style.Prop) => p match { case _: Style.Prop.ActiveProp => true; case _ => false }),
        (disabled,              (p: Style.Prop) => p match { case _: Style.Prop.DisabledProp => true; case _ => false })
    )
    @tailrec def loop(i: Int, acc: Style): Style =
        if i >= pseudoStates.size then acc
        else
            val (active, pred) = pseudoStates(i)
            val next = if active then acc ++ extractPseudo(base, pred) else acc
            loop(i + 1, next)
    loop(0, base)
```

This eliminates `var` and replaces `isInstanceOf` with pattern match.

---

## Problem 8: `RangeInput` Has Same `asInstanceOf` Bug as TextInput

### What's happening

`lowerRangeInput` has the same bug that was fixed for TextInput:

```scala
val currentValue = ri.value match
    case Present(v: Double)         => v
    case Present(ref: SignalRef[?]) => ref.asInstanceOf[SignalRef.Unsafe[Double]].get()  // ← ClassCastException
    case _                          => minVal

// ... inside handler:
ri.value match
    case Present(ref: SignalRef[?]) => ref.asInstanceOf[SignalRef.Unsafe[Double]].set(newVal)  // ← same bug
```

`SignalRef` (safe) can't be cast to `SignalRef.Unsafe`. This was fixed for TextInput via materialization + cache lookup, but RangeInput wasn't updated.

### Proposed fix

Apply the same pattern:

1. Add `RangeInput` to `materializeElement`:
```scala
val valueEffect: Unit < (Async & Scope) = elem match
    case ti: UI.TextInput   => materializeMaybeStringSignal(ti.value, ...)
    case pi: UI.PickerInput => materializeMaybeStringSignal(pi.value, ...)
    case ri: UI.RangeInput  => materializeMaybeDoubleSignal(ri.value, ...) // new
    case _                  => ()
```

2. Add `materializeMaybeDoubleSignal` (analogous to `materializeMaybeStringSignal`)
3. Add `readDoubleOrRef` / `writeDoubleRef` helpers that look up the cached ref by key instead of casting

---

## Problem 9: Handlers Case Class Has Too Many Responsibilities

### What's happening

`Handlers` has 18 fields mixing three concerns:

```scala
case class Handlers(
    // Identity
    widgetKey: Maybe[WidgetKey],
    id: Maybe[String],
    forId: Maybe[String],

    // Interaction state
    tabIndex: Maybe[Int],
    disabled: Boolean,

    // Event closures (10 fields)
    onClick: Unit < Async,
    onClickSelf: Unit < Async,
    onKeyDown: UI.KeyEvent => Unit < Async,
    onKeyUp: UI.KeyEvent => Unit < Async,
    onInput: String => Unit < Async,
    onChange: Any => Unit < Async,
    onSubmit: Unit < Async,
    onFocus: Unit < Async,
    onBlur: Unit < Async,
    onScroll: Int => Unit < Async,

    // Table metadata
    colspan: Int,
    rowspan: Int,
    imageData: Maybe[ImageData]
)
```

Every `Resolved.Node`, `Styled.Node`, and `Laid.Node` carries all 18 fields, even when most are noops. A div with no event handlers still allocates a `Handlers` with 10 noop closures. The case class copy during handler composition creates many intermediate objects.

### Proposed improvement: Separate identity from events

```scala
// Identity + metadata — always present on nodes
case class NodeMeta(
    widgetKey: Maybe[WidgetKey],
    id: Maybe[String],
    forId: Maybe[String],
    tabIndex: Maybe[Int],
    disabled: Boolean,
    colspan: Int,
    rowspan: Int,
    imageData: Maybe[ImageData]
)

// Event handlers — only present when node has handlers
case class EventHandlers(
    onClick: Unit < Async,
    onClickSelf: Unit < Async,
    onKeyDown: UI.KeyEvent => Unit < Async,
    onKeyUp: UI.KeyEvent => Unit < Async,
    onInput: String => Unit < Async,
    onChange: Any => Unit < Async,
    onSubmit: Unit < Async,
    onFocus: Unit < Async,
    onBlur: Unit < Async,
    onScroll: Int => Unit < Async
)
```

Nodes carry `meta: NodeMeta` and `handlers: Maybe[EventHandlers]`. Dispatch checks `handlers` presence before firing. Composition only creates `EventHandlers` when the element or a parent has actual handlers.

### Benefits

- Clearer separation of concerns
- Nodes without events don't allocate closure objects
- Handler composition doesn't need to copy identity fields
- `onChange: Any => Unit < Async` could become `onChange: EventHandlers.ChangeValue => Unit < Async` with a sealed trait for type safety

### Tradeoff

More types to manage. Dispatch needs an extra `match` check. May not be worth it if the allocation overhead is negligible (most nodes DO have inherited handlers via composition).

---

## Problem 10: `cycleFocus` Returns Computations That Don't Execute

### What's happening

The Tab-based focus tests fail: `onFocus` handlers don't fire even though `cycleFocus` composes the right computations. Click-based focus works with the exact same handlers.

The difference:

```scala
// Click path (works) — in dispatch():
state.activeId.set(node.handlers.widgetKey)
focusEffect                           // ← returned from setFocus
    .andThen(node.handlers.onClick)   // ← onClick is Sync.defer'd
    .andThen(node.handlers.onClickSelf)

// Tab path (broken) — in dispatch():
cycleFocus(state, layout, reverse = shift)  // ← returns blurEffect.andThen(focusEffect)
```

In the click path, the computation is chained with `.andThen(node.handlers.onClick)`. The kyo runtime evaluates this chain, which forces evaluation of `focusEffect` (the `Sync.defer` inside `onFocus`).

In the Tab path, `cycleFocus` returns `blurEffect.andThen(focusEffect)`. Both `blurEffect` and `focusEffect` are `Unit < Async` computations. When `blurEffect` is `noop` (no previous focus), the chain is `noop.andThen(focusEffect)`.

The issue may be that `noop` is defined as `val noop: Unit < Async = ()`. In kyo, `()` is a pure `Unit` value, widened to `Unit < Async`. When `.andThen(focusEffect)` is called on a pure value, the kyo runtime should evaluate `focusEffect`. But something in the evaluation chain might be short-circuiting.

### Hypothesis

When `Pipeline.dispatchEvent` returns the `Unit < Async` from `cycleFocus`, and `Screen.dispatch` does `.andThen(render)`, the chained computation is:

```
noop.andThen(Sync.defer { focused = "btn1" }).andThen(render)
```

If `noop` is the pure `Unit` value `()`, then `noop.andThen(x)` should return `x`. And `x.andThen(render)` should evaluate `x` then `render`. The `Sync.defer` should execute when the kyo runtime handles the `Sync` effect.

But if there's a kyo optimization where `.andThen` on a pure value returns the next computation WITHOUT wrapping it in an effect boundary, the `Sync` inside might not get properly handled.

### Proposed investigation

1. Change `noop` from `val noop: Unit < Async = ()` to `val noop: Unit < Async = Sync.defer(())` — this ensures `noop` is a real computation, not a pure value.
2. If that fixes Tab focus, the issue is a kyo runtime optimization that skips `Sync` effects when chaining with pure values.
3. If it doesn't fix it, add a minimal reproduction test that isolates the `noop.andThen(Sync.defer { ... })` chain.

### Alternative: Don't return computations from cycleFocus

Instead of returning `blurEffect.andThen(focusEffect)` for the caller to evaluate, `cycleFocus` could evaluate the effects inline:

```scala
private def cycleFocus(...)(using AllowUnsafe, Frame): Unit < Async =
    // ... compute newKey, blurEffect, focusEffect ...
    state.focusedId.set(Maybe(newKey))
    // Evaluate effects directly instead of returning composed chain
    blurEffect.andThen(focusEffect)
```

This is actually what the code already does — it returns the chain. The caller (`dispatch`) returns it. The test's `run` evaluates it. The issue is somewhere in this evaluation chain.

---

## Problem 11: `hr` Theme Style Creates Full Border Box

### What's happening

```scala
// In themeStyle:
case _: UI.Hr => Style.border(1.px, theme.borderColor).width(100.pct)
```

This creates a bordered box that takes the full width. Since `hr` is a `Block` element, it gets laid out as a div-like box with borders on all four sides. The expected behavior is a single horizontal line.

### Proposed fix

Use border-bottom only:

```scala
case _: UI.Hr => Style.borderBottom(1.px, theme.borderColor).width(100.pct).height(1.px)
```

Or use a dedicated text representation:

```scala
// In lowerElement:
case _: UI.Hr =>
    val width = 100 // or resolved from parent
    Resolved.Text("─" * width)
```

The border-bottom approach is simpler and consistent with how CSS handles `hr`. The `height(1.px)` ensures the element takes exactly one row.

---

## Problem 12: Placeholder Text Not Implemented

### What's happening

`lowerTextInput` displays `currentValue` but never checks `ti.placeholder`:

```scala
val displayText = ti match
    case _: UI.Password => "•" * currentValue.length
    case _              => currentValue
```

When `currentValue` is empty and `placeholder` is set, the display should show the placeholder text in a dimmed style.

### Proposed fix

```scala
val displayText = ti match
    case _: UI.Password => "•" * currentValue.length
    case _ if currentValue.isEmpty =>
        ti.placeholder.getOrElse("")
    case _ => currentValue
```

For styling, the placeholder text should have a dimmed foreground. This could be achieved by conditionally merging a placeholder style:

```scala
val placeholderActive = currentValue.isEmpty && ti.placeholder.nonEmpty
val effectiveStyle = if placeholderActive then
    style ++ Style.color(Style.Color.gray)
else style
```

---

## Priority and Dependency Order

| # | Problem | Impact | Effort | Dependencies |
|---|---------|--------|--------|--------------|
| 1 | Stale handler captures | High — affects all widgets | Medium | None |
| 2 | Text flex shrink | High — text truncation broken | Low | None |
| 3 | Focusable registration | Medium — maintainability | Low | None |
| 5 | `br` as Break IR | Medium — line breaks broken | Medium | Changes to IR, Styler, Layout |
| 6 | Select toggle eager | Medium — select behavior wrong | Low | Subsumed by #1 |
| 10 | cycleFocus investigation | High — keyboard nav broken | Low-Medium | None |
| 4 | Materialize co-location | Medium — prevents future bugs | High | Changes walk return type |
| 8 | RangeInput asInstanceOf | Medium — runtime crash | Low | None |
| 7 | mergePseudoStates cleanup | Low — style only | Low | None |
| 12 | Placeholder text | Low — missing feature | Low | None |
| 11 | hr border style | Low — visual bug | Low | None |
| 9 | Handlers split | Low — structural improvement | Medium | Changes to IR, Lower, Dispatch |

Recommended order: **10 → 2 → 1 → 3 → 5 → 8 → 6 → 12 → 11 → 7 → 4 → 9**

Fix the mysterious Tab focus bug first (10) since it blocks all keyboard navigation testing. Then text truncation (2) since it's high impact and low effort. Then the systematic handler deference (1) since it prevents an entire class of bugs. The rest follows naturally.
