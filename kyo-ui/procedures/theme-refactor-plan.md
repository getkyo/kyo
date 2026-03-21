# Theme Refactor Plan — Updated

## Current State

- 230 tests failing because Default theme now adds borders to inputs/selects and padding to table cells
- The input border/padding/width changes are CORRECT — they match web browser defaults
- Tests fail because they were written assuming borderless inputs

## Root Cause

Most Visual tests use `Screen(ui, cols, rows)` which defaults to `Theme.Default`. With borders now on inputs, every `assertFrame` for an input is wrong (expected borderless, got bordered).

## Solution: Test Infrastructure Change

1. **`Screen` and `assertRender` should default to `Theme.Plain`** for behavior tests
2. **Only theme-specific tests use `Theme.Default`** explicitly
3. This way, adding theme styles doesn't break 200+ behavior tests

### Why Plain for behavior tests?

Behavior tests verify: cursor position, typing, focus, click handlers, placeholder lifecycle, etc. These don't depend on whether the input has a border. Using Plain makes tests immune to theme changes.

Theme appearance tests verify: "input has border in Default theme", "button has padding", etc. These explicitly use Default and assert the full bordered frame.

## Changes Needed

### 1. Screen constructor defaults to Plain
```scala
class Screen(ui: UI, val cols: Int, val rows: Int, theme: Theme = Theme.Plain)
```

### 2. assertRender defaults to Plain
```scala
def assertRender(ui: UI, cols: Int, rows: Int, theme: Theme = Theme.Plain)(expected: String)
```

### 3. RenderToString.render defaults to Plain
Already has `theme: Theme = Theme.Default` — change to `Theme.Plain`.

Wait — actually RenderToString is used by RenderTest which HAS theme-specific tests. Let me think...

Better approach: **Screen defaults to Plain, RenderToString keeps Theme parameter explicit.** Tests that want Default pass it explicitly.

### 4. Theme-specific tests
Keep the existing `VisualTextInputTest` 1.19 section that tests "input has border in Default theme" — these explicitly construct Screen/assertRender with `Theme.Default`.

### 5. Fix the 6 pending failing tests
- Input border (3 tests) — already written, need correct expected frames with Default theme
- Button submits form (1 test) — need to fix the click row now that input has border
- Table padding (2 tests) — need correct expected frames

## Execution

1. Change `Screen` default theme to `Theme.Plain`
2. Change `RenderToString.render` default theme to `Theme.Plain`
3. Run tests — most should pass again (Plain = no borders)
4. Fix the 6 theme-specific tests to pass with explicit `Theme.Default`
5. Fix button-submits-form test (click position changed due to borders)
6. Verify full suite passes

## Pending Demo Issues

1. ✅ Input has border — Done (in Default theme)
2. ✅ Input has padding — Done (padding(0.px, 1.px))
3. ✅ Input fills parent width — Done (width(100.pct))
4. ⬜ Button click submits form — Implementation done, test needs row adjustment
5. ⬜ Table column spacing — Done (th/td padding in Default theme)
6. Theme is fallback, user style overrides — Already correct (`theme ++ userStyle`)
