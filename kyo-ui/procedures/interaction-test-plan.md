# Interaction Test Plan

## Rendering bugs found

### Overflow: children wider than parent not constrained
Two children with `width(20.px)` each in a 15px viewport render as `AAAA    BBBB   ` — both get their full 20px, overflowing the viewport. `flexShrink` (default 1.0) should constrain them proportionally to fit.

**Root cause hypothesis:** In `Layout.measureFlexChildren`, children with explicit `Px` width get their full width as `mainSize`. When `totalMain > mainSize` (overflow), `distributeShrink` should reduce them. But `childShrink` array is only populated for `Styled.Node` — `Styled.Text` and `Styled.Cursor` get `0.0`. For nodes, `nd.style.flexShrink` is read. Default `FlatStyle.flexShrink = 1.0`, so it should work. The issue may be in `arrange` — since `available.w` is authoritative, a child with `width=20.px` gets `outerW = available.w - margins = 20 - 0 = 20` regardless of the flex-computed smaller size.

Wait — `arrange` uses `available.w` as authoritative. The flex algorithm computes `childMain` (post-shrink), and `positionChildren` passes `Rect(childX, childY, childW, childH)` where `childW = childMain`. So `available.w` for the child should be the shrunk size. The issue might be that `resolveAvailable` overrides `available.w` with the child's explicit `width` — `resolveAvailable(node, flexRect)` returns `Rect(x, y, resolve(20.px, flexRect.w), ...)` = 20, not the flex-assigned smaller value.

**Fix:** `resolveAvailable` should NOT be called for flex children — flex has already computed their sizes. Currently `resolveAvailable` is only called at the root and for overlays, not in `positionChildren`. Let me verify...

Actually, looking at `Layout.positionChildren` (line 468): `val laid = arrange(children(i), Rect(childX, childY, childW, childH), clip, popups)` — no `resolveAvailable` call. The child gets `available.w = childW` (flex-computed). Then in `arrange`: `outerW = available.w - marL - marR`. So if `childW` was properly shrunk, `outerW` would be correct.

The bug must be in `distributeShrink`. Let me check: if `totalMain = 40` and `mainSize = 15` (viewport), `freeSpace = 15 - 40 = -25`. `totalShrinkFactor = 2.0` (both have 1.0). Each child shrinks by `25 * 1.0 / 2.0 = 12`. So sizes become `20-12=8` and `20-12=8`. Total = 16 + no gaps = 16... still > 15. The gap is 0 (default) so total should be 16. But viewport is 15. Off by 1 because we have 2 children and the integer division rounds down.

Actually, the `mainSize` for flex in a row is `cw` (content width). If the parent has auto width in a 15px viewport, `outerW = 15`, `contentW = 15 - 0 - 0 = 15`. So `mainSize = 15`. But `resolveAvailable` for the root with auto width resolves to `parentAvail.w = 15`. Then `arrange` gives `outerW = 15`. Then `contentW = 15`. Then flex gets `cw = 15`, `mainSize = 15`. Each child measures 20, total 40. `freeSpace = -25`. Shrink: each gets `20 - 12 = 8`. Total = 16. `16 > 15`. One pixel overflow because `25/2 = 12` (integer division rounds down).

This is a minor off-by-one. But the bigger issue might be that the children in the test are NOT getting shrunk at all — `AAAA    BBBB   ` shows AAAA at ~8 chars apart, which is closer to 8+8=16 minus some gap. Actually the output is `AAAA    BBBB   ` which is 15 chars — AAAA(4) + spaces(4) + BBBB(4) + spaces(3) = 15. So the children ARE being constrained to ~7-8 px each, and the text fits. The extra spaces are because the text "AAAA" is only 4 chars in a 7-8 px box.

So maybe the basic layout IS working but the demo broke because of deeper nesting with more complex style combinations. Need to test the actual demo scenario.

### Other issues to investigate
- `br` doesn't create visual line break (test already fails)
- `h1` default theme style (bold + padding) interaction with layout

## Test structure

### Shared test harness

```scala
class InteractionHarness(cols: Int, rows: Int):
    val state = new ScreenState(ResolvedTheme.resolve(Theme.Default))

    def render(ui: UI): String < (Async & Scope) = ...
    def dispatch(event: InputEvent): Unit < Async = ...
    def click(x: Int, y: Int): Unit < Async = ...
    def key(k: UI.Keyboard): Unit < Async = ...
    def typeText(text: String): Unit < Async = ...
    def tab(): Unit < Async = ...
    def frame: String = ... // current grid as string
```

### Test files

#### InteractionTextTest
- Simple text renders at correct position
- Text truncated to container width
- Text wrapping at container boundary
- Text overflow ellipsis
- Text align center, right
- Text transform uppercase, lowercase
- Letter spacing
- Line height > 1
- Unicode characters
- Empty string

#### InteractionInputTest
- Input renders value
- Type a character → value updated, cursor moves
- Backspace → character deleted
- Delete → forward delete
- Arrow keys → cursor moves
- Home/End → cursor to start/end
- Password masking
- Readonly input ignores keystrokes
- Disabled input ignores keystrokes

#### InteractionLayoutTest
- Row: children side by side
- Column: children stacked
- Explicit width/height respected
- Percentage width (50% of parent)
- Auto width fills parent
- Padding shifts content
- Margin shifts element
- Border draws characters
- Rounded border corners
- Nested borders
- Flex grow fills remaining space
- Flex shrink constrains overflow (THE BUG)
- Gap between children
- Justify center, end, spaceBetween
- Align center, stretch
- Overflow hidden clips
- Scroll offset shifts content
- Two bordered boxes side by side (demo scenario)
- Deep nesting (border > padding > border > content)

#### InteractionCheckboxRadioTest
- Checkbox renders [ ] unchecked
- Checkbox renders [x] checked
- Click toggles checkbox
- Radio renders ( ) unchecked
- Radio renders (•) checked
- Click toggles radio

#### InteractionSelectTest
- Collapsed shows selected value + ▼
- Click opens dropdown (popup appears)
- Click option selects and closes
- Arrow keys navigate options

#### InteractionFocusTest
- Tab cycles through focusable elements
- Shift+Tab cycles backward
- Click sets focus
- Focus fires onFocus, blur fires onBlur
- Disabled elements skipped in tab order

#### InteractionFormTest
- Enter in input fires form onSubmit
- Enter in textarea inserts newline
- Form with multiple inputs

## Priority

1. **InteractionLayoutTest** — expose and fix the overflow/shrink bug
2. **InteractionInputTest** — verify the full typing experience
3. **InteractionCheckboxRadioTest** — simple, high confidence
4. **InteractionFocusTest** — tab cycling is complex
5. **InteractionTextTest** — text rendering edge cases
6. **InteractionSelectTest** — dropdown interaction
7. **InteractionFormTest** — form submission
