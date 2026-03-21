# Placeholder & Color Fix Plan

## Issues

1. **Placeholder visible when focused** — clicking an empty input shows the cursor IN the placeholder text. Correct behavior: placeholder disappears when input is focused, reappears when unfocused AND empty.
2. **Text color grey** — Default theme uses `fg = RGB.Transparent` (terminal default), which renders as grey on some terminals. Need explicit white fg.
3. **Placeholder should be dimmed** — placeholder text should be visually distinct (grey/dimmed) compared to labels and typed text.
4. **Color testing gap** — `gridToString` discards colors. Need a way to assert cell fg/bg in tests.

## Execution Order

### Step 1: Add color assertion support to Screen

Add `cellAt(col, row): Cell` to Screen so tests can check fg/bg colors.

### Step 2: Write failing tests for ALL issues

- Placeholder disappears when focused (empty input)
- Placeholder reappears when unfocused and empty
- Placeholder stays gone when unfocused with value
- Placeholder has dimmed fg color (grey) compared to normal text
- Normal text has explicit white fg (not Transparent)
- Label text has explicit white fg

### Step 3: Fix theme fg to explicit white

Change Default/Minimal/Plain theme `fg` from `RGB.Transparent` to `RGB(255, 255, 255)`.

### Step 4: Fix placeholder behavior in Lower

- When input is focused AND value is empty: show empty text (no placeholder), cursor at 0
- When input is unfocused AND value is empty: show placeholder with dimmed style
- The dimmed style must be SCOPED to the placeholder text node only (not inherited by siblings)

### Step 5: Verify all tests pass
