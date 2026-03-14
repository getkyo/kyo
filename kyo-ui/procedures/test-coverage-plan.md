# Test Coverage Analysis & Plan

## Current state: 282 tests across 12 suites

## Missing test files

### DispatchTest — 0 tests (NO FILE)
The entire Dispatch module is untested. This is the most critical gap.

**Needed tests:**
- `hitTest` — deepest node returned; outside bounds → Absent; popup checked before base; nested nodes; clip respected
- `hitTest` — zero-size rect; point on boundary (edge cases for `contains`)
- `findByKey` — found; not found → Absent; nested deeply; key on Text/Cursor node → Absent
- `findByUserId` — found; not found; nested
- `dispatch Key` — Tab cycles focus forward; Shift+Tab cycles backward; wraps around; fires onBlur/onFocus
- `dispatch Key` — onKeyDown fires on focused node; disabled node → no effect; Space fires onClick
- `dispatch LeftPress` — fires onClick + onClickSelf; updates focusedId; updates activeId; disabled → no effect
- `dispatch LeftPress` — forId redirect: click label → focuses target
- `dispatch LeftRelease` — clears activeId
- `dispatch Move` — updates hoveredId; hover nothing → Absent
- `dispatch ScrollUp/Down` — fires onScroll on hit target
- `dispatch Paste` — fires onInput on focused node; disabled → no effect
- `setFocus` — fires onBlur on old, onFocus on new; same node → no-op
- `cycleFocus` — empty focusableIds → no-op; single element → stays

### RenderToStringTest — 0 tests (NO FILE)
Covered partially by PipelineTest but no dedicated tests.

**Needed tests:**
- `gridToString` — empty grid → all spaces; single cell; multi-line; null char → space

## Existing files — missing coverage

### IR.scala / IRTest.scala (33 tests)

**Missing:**
- `Rect.contains` — point inside; on boundary; outside; zero-size rect; negative coordinates
- `Rect.intersect` — overlapping; non-overlapping (zero-area result); one inside other; identical; zero-size input
- `RGB.lerp` — f=0 → first color; f=1 → second color; f=0.5 → midpoint; out of range f
- `RGB.invert` — white→black; black→white; mid-gray
- `RGB.Transparent` — r/g/b extraction on Transparent (potential bug: -1 as packed int, r/g/b may return unexpected values)
- `RGB.clamp` — negative → 0; >255 → 255; in range → unchanged
- `RGB.fromStyle` with `Hex("#")` — empty after # (edge case for parseHex)
- `Length.resolve` — Px; Pct of 0; Pct of 100; Em; Auto; Pct of negative parent
- `Length.resolveOrAuto` — Auto → Absent; Px → Present
- `Length.toPx` — Px passthrough; Em → Px; Pct → zero; Auto → zero
- `WidgetKey.child` — chained calls; empty segment
- `CellGrid.empty` — 0x0 grid; 1x1; large grid

### Styler.scala / StylerTest.scala (14 tests)

**Missing:**
- `toPx` in Styler — `Em(2)` → `Px(2)`; `Pct(50)` → `Px(0)`; `Auto` → `Px(0)`
- Pseudo-state style merging — hover style applied when hovered; focus style; active; disabled
- Multiple pseudo-states active simultaneously
- Gradient direction preserved as `Maybe[GradientDirection]` — `Present` and `Absent`
- Border individual props (`BorderTopProp`, etc.) override `BorderWidthProp`
- Shadow prop — all fields set correctly
- Translate prop
- Filters — brightness, contrast, etc. pass through
- `lineHeight(0)` → clamped to 1

### Layout.scala / LayoutTest.scala (22 tests)

**Missing:**
- Negative margin — node shifts left/up
- `resolveAvailable` for Text/Cursor nodes — passthrough
- `measureWidth` with nested children — column vs row
- `measureHeight` with percentage height — returns 0
- Zero-dimension viewport — 0 width, 0 height
- Multiple popups — all extracted, order preserved
- Table with empty rows
- Table colspan > column count — edge case
- Flex wrap (not tested at all) — children wrap to next line
- Justify spaceBetween with 1 child — no crash
- Justify spaceAround, spaceEvenly — correct spacing
- Scroll with overflow visible — no clipping
- Deep nesting — 10+ levels
- Text with empty string
- Cursor at position 0; at end of text

### Painter.scala / PainterTest.scala (15 tests)

**Missing:**
- Dashed border style
- Dotted border style
- No border (borderStyle.none) — no border chars drawn
- Shadow with blur — shade characters (░▒▓)
- Shadow without blur — solid rect
- Background with Transparent — no cells changed
- Text with lineHeight > 1 — blank rows between lines
- Text multiline (newlines in text)
- Multiple gradient stops — interpolation between 3+ colors
- Gradient toLeft, toTop, diagonal directions
- Filter: contrast, grayscale, sepia, invert, saturate, hueRotate
- Filter on Transparent color — skipped (potential bug: applyFilterChain checks `== RGB.Transparent`)
- Cursor on empty cell — block cursor (█)
- Cursor at grid boundary — no crash
- Image with alt text fallback
- Empty children — no crash in paintBox
- Popup painted to separate grid

### Differ.scala / DifferTest.scala (6 tests)

**Missing:**
- Unicode character (multi-byte) — correctly encoded
- Multiple attribute changes — reset emitted once, all re-enabled
- Background color change — SGR 48;2 emitted
- Dimmed attribute — SGR 2
- Italic + underline + strikethrough combined
- Cursor position tracking — no move emitted for consecutive changed cells
- Transparent fg/bg — skipped (not emitted)
- Grid size mismatch (prev smaller than curr) — no crash
- Empty grid (0x0) — empty output

### Compositor.scala / CompositorTest.scala (3 tests)

**Missing:**
- Size mismatch between base and popup — potential IndexOutOfBounds
- All empty cells — no override
- Both grids have cells at same position — popup wins (already tested, but verify fg/bg preserved)

### Lower.scala / LowerTest.scala (16 tests)

**Missing:**
- Reactive with Signal — signal value is materialized and walked
- Foreach — items mapped to children with correct keys
- Foreach with key function — dynamic path includes key
- Form onSubmit — threaded to children via parentOnSubmit
- TextInput onKeyDown composition — widget → user → parent chain
- TextInput cursor movement — ArrowLeft/Right/Home/End
- TextInput character insertion and deletion
- TextInput Enter in form — fires onSubmit
- Textarea Enter — inserts newline (NOT onSubmit)
- Select expanded — popup node present
- Select option click — selects and collapses
- RangeInput arrow keys — value adjusted by step
- RangeInput min/max — clamped
- Disabled element — handlers.disabled = true, not in focusableIds
- Hidden element with Signal[Boolean] — materialized and evaluated
- Style with Signal[Style] — materialized and applied
- onClick composition — leaf onClick = child.andThen(parent)
- onClickSelf NOT composed — only fires on declaring node
- onScroll override semantics — innermost scrollable wins
- Multiple focusable inputs — all in focusableIds
- Theme styles — H1 gets bold, Button gets border (Default theme)

### PipelineTest.scala (6 tests)

**Missing:**
- Second render produces different (smaller) ANSI output — diff optimization
- Widget state persists across renders — cursor position maintained
- `dispatchEvent` after render — handler fires, state changes
- Full cycle: render → dispatch click → re-render → verify visual change

## Potential bugs to investigate

1. **RGB.Transparent extraction** — `RGB.Transparent = -1`. Calling `.r`, `.g`, `.b` on -1: `(-1 >> 16) & 0xff = 255`, `(-1 >> 8) & 0xff = 255`, `(-1) & 0xff = 255`. So `Transparent.r == 255`, `Transparent.g == 255`, `Transparent.b == 255`. Is this intentional? It means Transparent is indistinguishable from white when extracting components.

2. **Differ writeDecimal** — only handles 0-999. Terminal dimensions > 999 rows/cols would produce wrong output. Unlikely but unbounded.

3. **Layout resolveAvailable** — for a node with `Pct(50)` width inside a parent with `Auto` width, `Length.resolve(Pct(50), parentAvail.w)` where `parentAvail.w` is the viewport width. Is this correct, or should percentage resolve against the parent's content width (after padding/border)?

4. **Dispatch cycleFocus** — `keys.indexOf(k)` returns -1 if not found. Then `current.map(k => keys.indexOf(k)).getOrElse(-1)` returns -1. Next index calculation: `if currentIdx < 0 ... then 0` → focuses first element. But `indexOf` is on `Chunk` which inherits from `Seq` — does `Chunk.indexOf` return -1 or throw on not found?

5. **Painter cursor rendering** — compares `existing.bg == black` and `existing.fg == black`. If the cell has `RGB.Transparent` bg/fg, these comparisons fail (Transparent ≠ black). The cursor might not invert correctly on transparent backgrounds.

6. **Layout percentage resolve with 0 parent** — `Length.resolve(Pct(50), 0) = (50 * 0 / 100) = 0`. Fine. But `Length.resolve(Pct(50), -1)` would give negative. Can parent be negative?

7. **Lower handler closures capture `currentValue`** — In `lowerTextInput`, `currentValue` is read once and captured in the closure. But the closure fires later when the user types. By then, `currentValue` is stale — it should read from the ref each time the closure fires, not capture once.

## Confirmed bugs (tests fail)

1. **Dispatch: disabled node fires onClick on LeftPress** — `DispatchTest."disabled node does not fire onClick"` fails. The `disabled` flag on handlers is checked but the click still fires. Root cause likely in how the test constructs the node (copy changes handlers but the disabled check in Dispatch may be looking at the wrong thing) or Dispatch's LeftPress branch has a bug in the disabled guard.

## Priority

1. **DispatchTest** — zero coverage on event routing, the most complex runtime behavior
2. **Lower handler composition tests** — verify bubbling chains actually work
3. **RGB.Transparent bug investigation** — potential visual bug
4. **Lower stale closure bug** — potential correctness bug in text input
5. **Painter edge cases** — shadow blur, multiple filters, transparent colors
6. **Layout edge cases** — flex wrap, negative margins, deep nesting
