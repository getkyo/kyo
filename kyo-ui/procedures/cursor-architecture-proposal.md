# Cursor Architecture Proposal

## The Problem

`Resolved.Cursor(charOffset)` is an IR node that represents the text cursor position. Lower emits it as a child of the text input's expanded div:

```scala
Chunk(
    Resolved.Text("hel"),    // text before cursor
    Resolved.Cursor(3),      // cursor at position 3
    Resolved.Text("lo")      // text after cursor
)
```

The parent div has `Style.row` (or defaults to row for Span tags). In flex row layout, each child is an independent flex item with its own position. The cursor's `available.x` is set by flex to the end of `Text("hel")` = 3. Then `Layout.arrange` for `Styled.Cursor(charOffset)` computes `Laid.Cursor(Rect(available.x + charOffset, ...))` = `Rect(3 + 3, ...)` = column 6. The cursor appears at column 6, but it should be at column 3.

The `charOffset` is double-counted: flex already positioned the cursor after the preceding text, and `arrange` adds it again.

## Why This Architecture Is Wrong

`Resolved.Cursor` was designed for a different layout model. It assumes the cursor is positioned relative to the parent node's content origin — `charOffset` is an absolute offset from the parent's left edge. But in flex layout, children are positioned sequentially. The cursor child gets `available.x = end_of_previous_child`, not `available.x = parent_content_x`.

This means:
- The cursor can only work correctly if it's the FIRST child (charOffset not double-counted) or if flex is disabled
- Arrow keys that change cursor position produce visually wrong results
- The cursor position depends on how Layout distributes space to siblings — implementation detail leaking into visual correctness

## The Proper Fix: Cursor as Node Metadata, Not IR Child

The cursor is not content. It's a visual marker that the Painter renders by inverting colors at a specific cell. It shouldn't participate in layout at all. It should be metadata on the input node, consumed directly by the Painter.

### Change 1: Remove `Resolved.Cursor`, `Styled.Cursor`, `Laid.Cursor` from the IR enums

These variants exist solely for text input cursor rendering. No other widget uses them. Removing them simplifies the IR and eliminates the layout interaction.

### Change 2: Add `cursorPosition: Maybe[Int]` to `Handlers`

The cursor position is interaction state — it's tied to the focused input. It belongs on `Handlers`, not as an IR tree node.

```scala
// In Handlers:
val cursorPosition: Maybe[Int]  // Absent = no cursor, Present(n) = cursor at column n
```

### Change 3: Lower emits a single Text child for inputs

Instead of splitting text around the cursor:
```scala
// Before:
Chunk(Text("hel"), Cursor(3), Text("lo"))

// After:
Chunk(Text("hello"))
// + handlers.cursorPosition = Maybe(3)
```

The input always has one Text child with the full display text. The cursor position is on the handlers.

### Change 4: Painter reads cursorPosition from handlers

After painting a node's children, Painter checks `node.handlers.cursorPosition`. If present, it inverts the cell at `(content.x + cursorPosition, content.y)` within the node's content rect.

```scala
private def paintBox(n: Laid.Node, canvas: Canvas): Unit =
    // ... existing painting ...
    // Paint cursor if present
    n.handlers.cursorPosition match
        case Present(pos) =>
            val cx = n.content.x + pos
            val cy = n.content.y
            if inBounds(cx, cy, canvas) && inClip(cx, cy, n.clip) then
                invertCell(canvas, cx, cy)
        case _ => ()
```

### Change 5: Layout no longer handles Cursor variants

`measureWidth`, `measureHeight`, `measureFlexChildren`, `positionChildren`, `arrange`, `computeContentHeight` — all lose their `Cursor` cases. The IR is simpler.

### Change 6: Styler no longer handles Cursor variants

`Styled.Cursor` case in `Styler.style` is removed.

## Impact

- **Eliminates the double-counting bug** — cursor position is an absolute offset within the node, not a flex child
- **Eliminates the "cursor on unfocused inputs" category** — already fixed by WidgetState, but reinforced here: no cursor in the IR tree at all for unfocused inputs, just `handlers.cursorPosition = Absent`
- **Simplifies IR** — one fewer variant in Resolved, Styled, Laid
- **Simplifies Layout** — no cursor special cases in measurement, positioning, content height
- **Simplifies Painter** — cursor rendering is in `paintBox`, not a separate `paintCursor`
- **Correct by construction** — the cursor position is relative to the node's content area, computed once, never reinterpreted by layout

## What This Does NOT Change

- The cursor position ref (`SignalRef.Unsafe[Int]`) in WidgetStateCache — stays the same
- Handler closures for cursor movement (ArrowLeft, ArrowRight, Home, End) — stay the same
- The Differ — doesn't know about cursors, just compares cells
- The WidgetState improvement — stays, cursor is only set when `ws.focused`

## Tests Fixed

All 6 cursor position failures would be fixed:
- "input with hello focused - cursor at position 5" ✅ — cursorPosition = 5, Painter inverts cell at content.x + 5
- "type AB - cursor at position 2" ✅ — cursorPosition = 2, cell at content.x + 2
- "type AB, ArrowLeft - cursor at position 1" ✅ — cursorPosition = 1
- "type AB, Home, ArrowRight - cursor at position 1" ✅ — cursorPosition = 1
- "password abc focused - cursor after third dot" ✅ — cursorPosition = 3 (after 3 dots)
- "type A verify cursor at 1, type B verify at 2, backspace verify at 1" ✅ — sequential position updates

## Implementation Order

1. Add `cursorPosition: Maybe[Int]` to `Handlers` (builder: `withCursorPosition`)
2. Update Lower: set `handlers.withCursorPosition(Maybe(cursor))` when focused, emit single `Text(displayText)` child
3. Update Painter: add cursor rendering in `paintBox` after children
4. Remove `Resolved.Cursor`, `Styled.Cursor`, `Laid.Cursor` from IR enums
5. Remove cursor cases from Styler, Layout, Painter (`paintCursor`)
6. Update all tests that reference `Cursor` IR variants
