# Event Dispatch Redesign

## Problem Statement

The tui2 event dispatch has an architectural asymmetry: keyboard events are cleanly delegated to widgets via `Widget.handleKey()`, but mouse events are entirely hardcoded in `EventDispatch`. This means:

- Clicking a text input focuses it but doesn't place the cursor
- Dragging in a text input does nothing (no text selection)
- Clicking a range slider does nothing (only arrow keys work)
- Clicking a select option does nothing (only arrow keys work)
- Adding any mouse interaction to any widget requires editing EventDispatch
- Scroll events bypass widgets entirely

The old flat pipeline (`TuiFocus`) has the same gaps — neither pipeline implements cursor-from-click or drag-select.

## Current Architecture

### Event Types (4 variants)

| Type | Fields | Source |
|------|--------|--------|
| `Key` | key, ctrl, alt, shift | Terminal keyboard input |
| `Mouse` | kind, x, y, shift, alt, ctrl | Terminal mouse input (SGR encoding) |
| `Paste` | text | Terminal bracketed paste |
| `ClipboardPaste` | items: Chunk[ClipboardItem] | OS clipboard via Ctrl+V |

### Mouse Kinds (12 variants)

| Kind | Currently Handled | By Whom |
|------|-------------------|---------|
| LeftPress | Focus + onClick | EventDispatch (hardcoded) |
| LeftRelease | Clear activeTarget | EventDispatch (hardcoded) |
| LeftDrag | **Unhandled** | — |
| Move | Set hoverTarget | EventDispatch (hardcoded) |
| ScrollUp | Adjust container scroll | EventDispatch (hardcoded) |
| ScrollDown | Adjust container scroll | EventDispatch (hardcoded) |
| RightPress | **Unhandled** | — |
| RightRelease | **Unhandled** | — |
| MiddlePress | **Unhandled** | — |
| MiddleRelease | **Unhandled** | — |
| RightDrag | **Unhandled** | — |
| MiddleDrag | **Unhandled** | — |

### Widget Handler Methods (current)

| Method | Signature | Used By |
|--------|-----------|---------|
| `handleKey` | `(elem, Key, ctx) => Boolean` | TextInput, Checkbox, Range, Select, Picker, FileInput, Container, Anchor |
| `handlePaste` | `(elem, String, ctx) => Boolean` | TextInput |
| `handleMouse` | `(elem, MouseKind, mx, my, ctx) => Boolean` | **Added but not yet wired into dispatch** |

### User-Facing Event Handlers (on Attrs)

| Handler | Signature | Fired When |
|---------|-----------|------------|
| onClick | `Unit < Async` | LeftPress on element |
| onClickSelf | `Unit < Async` | LeftPress on exact element |
| onKeyDown | `KeyEvent => Unit < Async` | Before widget key handling |
| onKeyUp | `KeyEvent => Unit < Async` | After widget key handling |
| onFocus | `Unit < Async` | Focus gained |
| onBlur | `Unit < Async` | Focus lost |
| onInput | `String => Unit < Async` | Text modified (TextInput) |
| onChange | `T => Unit < Async` | Value changed (per widget type) |
| onSubmit | `Unit < Async` | Enter in Form (Form only) |

### Current Dispatch Flow

**Keyboard** (well-structured):
```
Key event
  → Tab? → focus.next/prev → onBlur/onFocus
  → Ctrl+C? → quit
  → Ctrl+V? → clipboard → widget.handlePaste
  → Otherwise:
      → fire onKeyDown
      → widget.handleKey (returns consumed?)
      → if !consumed + Enter + Form → onSubmit
      → fire onKeyUp
```

**Mouse** (ad-hoc):
```
Mouse event
  → LeftPress? → hitTest → set activeTarget → bubbleClick → focus
  → Move? → hitTest → set hoverTarget
  → LeftRelease? → clear activeTarget
  → ScrollUp/Down? → findScrollableAt → adjust scrollY
  → Everything else? → ignored
```

## Design Principles

1. **Symmetry**: Mouse events should flow through widgets the same way keyboard events do
2. **Framework then widget**: EventDispatch handles framework concerns (focus, hover, active), then delegates to widgets for element-specific behavior
3. **Consumed flag**: Widgets return `Boolean` — if not consumed, framework applies fallback behavior
4. **Correct target**: Press goes to hit element, drag/release go to activeTarget (the press origin)
5. **Content position**: Widgets use `ctx.getContentPosition(elem)` to convert screen coords to local coords (cache already added)

## Proposed Dispatch Flow

### Mouse Events (redesigned)

```
Mouse event
  → LeftPress:
      1. hitTest → find target
      2. set activeTarget = target
      3. bubbleClick (onClick/onClickSelf handlers)
      4. if Focusable → changeFocus → onBlur/onFocus
      5. widget.handleMouse(target, LeftPress, mx, my)
         → TextInput: place cursor at click position
         → Range: set value from click position
         → Select: handled by onClick on options (no change needed)
         → Default: false (not consumed)

  → LeftDrag:
      1. if activeTarget exists:
         widget.handleMouse(activeTarget, LeftDrag, mx, my)
         → TextInput: extend selection
         → Range: adjust value by drag position
         → Default: false

  → LeftRelease:
      1. if activeTarget exists:
         widget.handleMouse(activeTarget, LeftRelease, mx, my)
      2. clear activeTarget

  → Move:
      1. hitTest → set hoverTarget
      (no widget dispatch — hover is framework-only)

  → ScrollUp/ScrollDown:
      1. hitTest → find target
      2. widget.handleMouse(target, ScrollUp/Down, mx, my)
         → Textarea: scroll text content, return true
         → Default: false (not consumed)
      3. if not consumed → findScrollableAt → adjust container scrollY
         (existing behavior as fallback)

  → RightPress, MiddlePress, etc.:
      1. hitTest → find target
      2. widget.handleMouse(target, kind, mx, my)
         → Default: false (not consumed)
         → Future: context menus, middle-click paste, etc.
```

### Keyboard Events (no structural changes)

The keyboard path is already well-structured. Only minor clarifications:

- `acceptsTextInput` flag controls Space→Char(' ') normalization (unchanged)
- Form.onSubmit fires only when Enter is NOT consumed by widget (unchanged)

### Paste Events (no structural changes)

- `Paste(text)` → `widget.handlePaste(focused, text)` (unchanged)
- `ClipboardPaste(items)` → extract text → `widget.handlePaste(focused, text)` (unchanged)

## Changes — All Complete

### 1. Widget.scala — Done
`handleMouse` method added with correct signature.

### 2. RenderCtx.scala — Done
Content position cache added. Fixed `beginFrame()` to NOT clear `activeTarget` (it persists across frames for drag/release, like `scrollYMap`).

### 3. Render.scala — Done
`ctx.recordContentPosition(elem, cx, cy, cw, ch)` called before `widget.render()`.

### 4. EventDispatch.scala — Done
Rewrote mouse dispatch to delegate to `widget.handleMouse()` for all mouse event kinds. Framework handles focus/hover/activeTarget, widgets handle element-specific behavior.

### 5. WidgetRegistry.scala — Done
Wired `handleMouse` for:
- `TextInputWidget` → `TextInputW.handleMouse`
- `TextareaWidget` → `TextInputW.handleMouse`
- `RangeWidget` → `RangeW.handleMouse`
- `EditablePickerWidget` → `PickerW.handleEditableMouse`

### 6. TextInputW.scala — Done
`handleMouse` implemented: LeftPress → cursor placement, LeftDrag → extend selection.

### 7. RangeW.scala — Done
`handleMouse` implemented: LeftPress/LeftDrag → set value from click position, snapped to step.

### 8. PickerW.scala — Done
`handleEditableMouse` implemented: LeftPress → cursor placement for editable pickers.

### 9. TerminalEmulator.scala — No changes needed
Already has `click(x, y)`, `drag(x, y)`, `scrollUp/Down(x, y)` methods.

## What This Does NOT Change

- **No new user-facing event handlers** (onMouseDown, onDrag, onScroll, etc.). These can be added later by extending `Attrs` — the widget plumbing will already be in place.
- **No event bubbling for mouse** beyond onClick. The hit-tested element's widget gets the event; if not consumed, framework fallback applies. Full DOM-style bubbling is not needed for a TUI.
- **No preventDefault/stopPropagation API**. The `Boolean` consumed flag is sufficient.
- **No hover callbacks**. hoverTarget is tracked for CSS-like `:hover` styling but no user handler.

## Tests — All Passing (7 new mouse tests, 266 total)

Added to `TextInputDiagTest` using the emulator:

1. **Click to focus second input** — Click directly on second input, verify correct focus
2. **Click places cursor in text input** — Click at column N, verify cursor position
3. **Drag to select in text input** — Press at A, drag to B, type to replace selection
4. **Range input click sets value** — Click at ~50% of range, verify value ≈ 50
5. **Scroll down on scrollable container** — ScrollDown on container, verify content shifts
6. **Click then type inserts at click position** — Click mid-text, verify insertion at click point
7. **Multiple inputs click directly without tab** — Click between inputs, verify correct focus each time

## Bug Found During Testing

**`beginFrame()` was clearing `activeTarget`** — This broke drag/release across frames because the render that happens between press and drag would reset `activeTarget = Absent`. Fixed by not clearing `activeTarget` in `beginFrame()`, consistent with how `scrollYMap` already persists.

## File Impact Summary

| File | Change | Status |
|------|--------|--------|
| `Widget.scala` | handleMouse added | Done |
| `RenderCtx.scala` | Content position cache + activeTarget fix | Done |
| `Render.scala` | recordContentPosition call | Done |
| `EventDispatch.scala` | Rewrote mouse dispatch | Done |
| `WidgetRegistry.scala` | Wire handleMouse for 4 widgets | Done |
| `TextInputW.scala` | handleMouse impl | Done |
| `RangeW.scala` | handleMouse impl | Done |
| `PickerW.scala` | handleEditableMouse impl | Done |
| `TextInputDiagTest.scala` | 7 mouse interaction tests | Done |
