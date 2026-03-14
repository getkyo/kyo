# Phase 3: Lower — Implementation Proposal

## What Lower does

Transforms `UI → Resolved`. The only pipeline step that reads reactive state (`SignalRef.Unsafe`). Produces an immutable `Resolved` tree where all widgets are expanded into primitive elements, all handlers are pre-composed for bubbling, and all reactive values are evaluated.

## Input types

- `UI` — sealed ADT with ~40 case classes (see `UI.scala`)
  - Non-element: `Text`, `Reactive`, `Foreach`, `Fragment`
  - Container elements: `Div`, `Span`, `P`, `Section`, `H1`–`H6`, `Ul`, `Ol`, `Table`, `Tr`, `Form`, `Label`, `Nav`, `Pre`, `Code`, etc.
  - Widget elements: `Input`, `Password`, `Textarea`, `Checkbox`, `Radio`, `Select`, `RangeInput`, `DateInput`, `TimeInput`, `ColorInput`, `FileInput`, `HiddenInput`, `Button`, `Anchor`, `Img`
  - Void elements: `Hr`, `Br`
- `ScreenState` — reactive refs for `focusedId`, `hoveredId`, `activeId` + `WidgetStateCache`
- `UI.Attrs` — identity, hidden, tabIndex, style, event handlers
- `Style` / `Signal[Style]` — may be reactive

## Output type

```scala
case class LowerResult(tree: Resolved, focusableIds: Chunk[WidgetKey])
```

## Architecture

### Core: `walk` function

Recursive pattern match over `UI`. Each branch produces `Resolved`. The function threads parent handlers for pre-composed bubbling.

**Parameters that change per call:**
- `ui: UI` — current node
- `dynamicPath: Chunk[String]` — for keyed identity in `Foreach`

**Parameters threaded for handler composition (invariant per subtree):**
- `parentOnClick`, `parentOnKeyDown`, `parentOnKeyUp`, `parentOnScroll`, `parentOnSubmit`

Per the quality plan, these invariant parameters should be grouped to reduce parameter explosion. I'll use a `WalkContext` case class:

```scala
private case class WalkContext(
    state: ScreenState,
    focusables: ChunkBuilder[WidgetKey],  // shared mutable accumulator (not copied)
    parentOnClick: Unit < Async,
    parentOnKeyDown: UI.KeyEvent => Unit < Async,
    parentOnKeyUp: UI.KeyEvent => Unit < Async,
    parentOnScroll: Int => Unit < Async,
    parentOnSubmit: Unit < Async
)
```

`walk(ui, dynamicPath, ctx)` — only 3 parameters. When recursing into children, `ctx.copy(parentOnClick = composed, ...)` updates the handler chain. `state` and `focusables` are shared across all calls (not copied per-node).

### Sub-phases

#### 3a: Passthrough elements (~150 lines)

All `Element` subtypes that map to `Resolved.Node`:
1. Generate `WidgetKey` from `Frame` + `dynamicPath`
2. Read `attrs.uiStyle` (may be `Signal[Style]` — evaluate via `Sync.Unsafe.evalOrThrow`)
3. Merge theme style for the tag (`themeStyle`)
4. Merge pseudo-state styles (`:focus`, `:hover`, `:active`, `:disabled`) if this element's key matches `state.focusedId`/etc.
5. Read `attrs.hidden` (may be `Signal[Boolean]`) — if hidden, return empty `Resolved.Text("")`
6. Build `Handlers` from `Attrs`:
   - `onClick`: compose with `ctx.parentOnClick`
   - `onKeyDown`: compose with `ctx.parentOnKeyDown`
   - `onKeyUp`: compose with `ctx.parentOnKeyUp`
   - `onClickSelf`, `onFocus`, `onBlur`: NOT composed — element-specific
   - `onScroll`: override semantics (innermost wins)
7. If `tabIndex >= 0` and not disabled → add to `ctx.focusables`
8. Recurse children with updated context (this node's composed handlers as parent)
9. Special cases:
   - `Form`: thread `onSubmit` to children via `ctx.parentOnSubmit`
   - `Table` → `ElemTag.Table`, `Tr`/`Td`/`Th` preserve colspan/rowspan on `Handlers`
   - `Label`: preserve `forId` on `Handlers`
   - `Br` → `Resolved.Text("\n")`
   - `Hr` → `Resolved.Node(Div)` with border-bottom theme style
   - `Ul`/`Ol` → children get list markers

#### 3b: Reactive constructs (~50 lines)

- `Text(value)` → `Resolved.Text(value)`
- `Fragment(children)` → recurse children, flatten
- `Reactive(signal)` → evaluate `Sync.Unsafe.evalOrThrow(signal.get)`, recurse result
- `Foreach(signal, key, render)` → evaluate signal, map items with keyed dynamic paths

#### 3c: TextInput widget expansion (~200 lines)

`Input`, `Password`, `Email`, `Tel`, `UrlInput`, `Search`, `NumberInput`, `Textarea`

1. Get/create widget state from `WidgetStateCache`:
   - `cursorPos: SignalRef.Unsafe[Int]`
   - `scrollX: SignalRef.Unsafe[Int]`
   - (Textarea adds `scrollY`)
2. Read current value (from `SignalRef[String]` or literal)
3. Build display text (Password masks with `•`)
4. Generate `onKeyDown` closure that captures state refs and implements:
   - Character insertion, backspace, delete
   - Cursor movement (arrows, home, end)
   - Form `onSubmit` weaving on Enter (except Textarea)
5. Compose widget `onKeyDown` on top of user handler + parent chain
6. Produce: `Resolved.Node(Div, style, handlers, Chunk(Text(before), Cursor(pos), Text(after)))`

#### 3d: Checkbox / Radio (~60 lines)

- `Checkbox`: state = `SignalRef.Unsafe[Boolean]`. Display `[x]` or `[ ]`. onClick toggles.
- `Radio`: state keyed by group name. onClick sets group value.

#### 3e: Select dropdown (~80 lines)

State: `expanded`, `selectedIndex`, `highlightIndex` (all `SignalRef.Unsafe`)
- Collapsed: display selected option + `▼`
- Expanded: same + `Popup` node with option list
- `onClickSelf` toggles expanded (NOT composed — target-only)
- Option onClick selects and collapses

#### 3f: RangeInput (~40 lines)

State: `SignalRef.Unsafe[Double]`. Track visualization. Arrow keys adjust by step within min/max.

#### 3g: Other inputs (~40 lines)

- `DateInput`, `TimeInput`, `ColorInput`: text display
- `FileInput`: text display
- `HiddenInput`: filtered out
- `Img`: `Resolved.Node` with `ImageData` on Handlers

#### 3h: Theme application (~60 lines)

Match on `UI` subtype + `Theme` variant → return `Style` to merge.

## Handler composition — belongs to `Handlers` type?

Per the "logic belongs to its type" principle, `compose` and `composeKeyed` could be methods on `Handlers`. But they operate on raw `Unit < Async` values, not on `Handlers` instances. They stay as private helpers in Lower since they're specific to the bubbling composition algorithm.

## Quality checklist (from PIPELINE-QUALITY-PLAN.md)

- [ ] Re-read entire quality plan before starting
- [ ] All iteration via `@tailrec` — no `while`
- [ ] `WalkContext` groups invariant parameters — no 9-parameter `walk`
- [ ] IR is fully immutable — `Resolved` tree is immutable
- [ ] All Signal reads via explicit `Sync.Unsafe.evalOrThrow` — never implicit
- [ ] `WidgetKey.child("cursor")` — instance method
- [ ] No `.ordinal`, no sentinel values, no `return`
- [ ] No `asInstanceOf` — pattern match on `UI` subtypes
- [ ] Comments explain domain constraints (bubbling, pseudo-state merging), not code
- [ ] Tests construct `UI` trees directly, verify `Resolved` output structure
- [ ] Handler composition tests verify full bubbling chains

## Estimated size

~700-800 lines of implementation + ~200 lines of tests

## Dependencies

- Phase 1 (IR types): `Resolved`, `Handlers`, `WidgetKey`, `ElemTag`
- Phase 2 (ScreenState): `ScreenState`, `WidgetStateCache`
- `UI.scala`: all element types
- `Style.scala`: style types, `Length.*` extensions
- `kyo-core`: `Signal`, `SignalRef`, `Sync.Unsafe`, `AllowUnsafe`

## Open questions

1. ~~**Widget identity**~~ ✅ Resolved — `Frame` added to `Element` trait and all case classes via `(using val frame: Frame)`. Factory methods changed from `val` to `def` to pipe Frame. `WidgetKey(elem.frame, dynamicPath)` in Lower.

2. **`Foreach` key function and `asInstanceOf`** — The plan's pseudocode uses `fe.key.asInstanceOf[Any => String]`. Per quality rules, no `asInstanceOf`. Need to handle the existential type `Foreach[?]` cleanly.

3. **`Signal.current` API** — The plan uses `signal.current` but the actual Signal API may differ. Need to verify how to read current value from `Signal` under `AllowUnsafe`.

4. **Handler composition on `Unit < Async`** — `.andThen` may not exist on `Unit < Async`. Need to verify kyo's effect composition API.

## Risks

- Largest phase — ~800 lines. May need sub-phase review checkpoints.
- Widget expansion closures are complex (capture `SignalRef.Unsafe` refs). Need careful testing.
- `Boolean | Signal[Boolean]` union types on attrs require explicit pattern matching — no `asInstanceOf`.
- `String | SignalRef[String]` union types similarly.
- Identity mechanism (open question 1) is foundational — wrong choice affects all widget state persistence.
