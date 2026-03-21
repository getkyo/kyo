# Input Scroll + Dropdown Sizing Plan

## Issue 1: Text Input Horizontal Scroll

### Current behavior (broken)
- Text wraps and expands the input box
- No horizontal scrolling
- Cursor can be off-screen

### Correct behavior (web `<input>`)
- Single-line inputs: fixed height (1 content row), text never wraps
- Long text is clipped at the visible edge
- Unfocused: shows start of text (scrollOffset = 0)
- Focused at end: shows end of text (scrollOffset = textLength - visibleWidth)
- Cursor always within visible window
- Typing/arrow keys scroll to keep cursor visible
- Textarea: wraps normally, no horizontal scroll

### Architecture

**Previous frame's width pattern:**
1. Lower reads `state.prevLayout` to find the input's `Laid.Node.content.w` from the previous frame
2. Lower stores a `scrollOffset: SignalRef.Unsafe[Int]` in widget state (like cursorPos)
3. Lower adjusts `scrollOffset` to keep cursor within `[scrollOffset, scrollOffset + visibleWidth)`
4. Lower emits `visibleText = displayText.substring(scrollOffset, min(scrollOffset + visibleWidth, textLength))`
5. `handlers.cursorPosition` uses `cursor - scrollOffset` (relative to visible window)
6. Style includes `textWrap(noWrap)` + `overflow(hidden)` for safety clipping

**Finding previous width:**
- Lower has access to `state.prevLayout: Maybe[LayoutResult]`
- Search `prevLayout` for the `Laid.Node` with matching `WidgetKey`
- Read its `content.w` → that's the visible width
- First frame: `prevLayout` is `Absent` → visibleWidth = Int.MaxValue (show all, let Painter clip)
- `Dispatch.findByKey` already exists for searching the layout tree by key

**Scroll offset update rules:**
```
if cursor >= scrollOffset + visibleWidth:
    scrollOffset = cursor - visibleWidth + 1
if cursor < scrollOffset:
    scrollOffset = cursor
scrollOffset = max(0, scrollOffset)
```

### Files to change
- `Lower.scala` — lowerTextInput: add scroll logic, emit visible window
- No changes to Layout, Painter, Style, IR

## Issue 2: Dropdown Popup Width

### Current behavior (broken)
- Popup expands to viewport width (30+ cols)
- Should match the select's own width

### Root cause
`layoutPopups` uses `clip.w` (viewport width) for popup `available.w`:
```scala
val popupRect = Rect(outerX, popupY, clip.w, clip.h - popupY)
```

### Fix
Use the select's outer width instead:
```scala
val popupRect = Rect(outerX, popupY, outerW, clip.h - popupY)
```

One-line change in `Layout.scala` `layoutPopups`.

### Files to change
- `Layout.scala` — layoutPopups: use `outerW` instead of `clip.w`

## Issue 3: Table Row Concatenation

### Current behavior (reported)
- Second table entry concatenates to the right of the first, not below

### Investigation needed
- The demo uses `entriesRef.foreachKeyed(...)` to render table rows
- Each row is `UI.tr(UI.td(...), ...)` inside `UI.table`
- The table layout should stack rows vertically
- Possible issue: foreachKeyed items append to the same row instead of creating new rows

### Files to check
- `Lower.scala` — walkForeach: verify items produce separate Resolved nodes
- `Layout.scala` — layoutTable: verify rows stack vertically

## Execution Order

1. ✅ Tests already written (8 failing)
2. Fix dropdown popup width (1 line)
3. Implement input horizontal scroll
4. Investigate table row issue
5. Verify all tests pass
