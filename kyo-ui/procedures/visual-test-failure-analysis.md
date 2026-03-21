# Visual Test Failure Analysis

39 failures across 9 test files. Each failure exposes a real implementation bug. Grouped by root cause.

---

## 1. `width(0.px)` / zero-width container not enforced (3 failures)

**Tests:**
- `VisualTextInputTest`: "zero-width container — no crash" — expected blank, got "hello"
- `VisualLayoutTest`: "width 0px — no content visible" — expected blank, got "hello"
- `VisualLayoutTest`: "all content in one row exceeding viewport — clipped" — expected "ABCDEFGH", got "ABCDKLMN"

**Root cause:** `Style.width(0.px)` does not constrain child width to zero. The child renders at its intrinsic width, ignoring the parent's explicit width constraint. `Length.resolve(Length.Px(0), parent)` may return `parent` instead of `0` for some code paths.

**Location:** `Layout.scala` — `resolveAvailable`, `measureFlexChildren`, or `arrange` width handling.

---

## 2. `minWidth` / `maxWidth` not enforced (2 failures)

**Tests:**
- `VisualLayoutTest`: "minWidth 5px in 3 col parent" — expected "ABCDE", got "ABC" (minWidth ignored)
- `VisualLayoutTest`: "maxWidth 5px in 20 col parent" — expected "ABCDE", got "ABCDEFGHIJ" (maxWidth ignored)

**Root cause:** `minWidth` and `maxWidth` constraints are not applied during layout. The child gets its parent's width or intrinsic width without clamping.

**Location:** `Layout.scala` — missing `math.max(minW, math.min(maxW, computed))` in width resolution.

---

## 3. `maxHeight` not enforced / overflow not clipped (2 failures)

**Tests:**
- `VisualLayoutTest`: "maxHeight 3px with 5 rows of content" — content rows D, E visible beyond maxHeight
- `VisualLayoutTest`: "overflow hidden clips tall content" — expected 2 rows visible, got 3 (content "C" visible beyond bounds)

**Root cause:** `maxHeight` is computed but the overflow clip rect doesn't use it. Content beyond `maxHeight` is still painted. Similarly, `overflow(hidden)` doesn't clip children to the container's height.

**Location:** `Layout.scala` — `computeContentHeight` or clip rect computation. `Painter.scala` — clip check may use wrong bounds.

---

## 4. Hidden elements still take space (3 failures)

**Tests:**
- `VisualLayoutTest`: "hidden true — not rendered" — expected "VISIBLE" on row 0, got it on row 1 (hidden div takes row 0)
- `VisualLayoutTest`: "hidden div with children — children also hidden" — "C" on row 1 instead of row 0
- `VisualLayoutTest`: "sibling after hidden element takes hidden space" — "B" on row 1, "C" on row 2 instead of row 0 and row 1

**Root cause:** `hidden(true)` converts the element to `Resolved.Text("")` in Lower, but the empty text still occupies a row in column layout (height = 1 for text wrap). The hidden element should produce ZERO height.

**Location:** `Lower.scala` line 222 — `Resolved.Text("")` should perhaps be a zero-height marker, or `Layout.measureHeight` for empty text should return 0.

---

## 5. Password placeholder not shown (3 failures)

**Tests:**
- `VisualPasswordTest`: "empty password with placeholder unfocused — shows placeholder not masked" — blank instead of "Enter password"
- `VisualPasswordTest`: "empty with placeholder focused — placeholder visible, cursor at 0" — blank instead of "Enter password"
- `VisualPasswordTest`: "backspace to empty — placeholder reappears" — blank instead of "hint"

**Root cause:** In `Lower.lowerTextInput`, the password masking happens BEFORE the placeholder check. When value is empty, `displayText` becomes the masked empty string (`"•" * 0 = ""`), which is empty. Then the placeholder check sees `currentValue.isEmpty` is true but `displayText` is already set to the masked result.

**Location:** `Lower.scala` `lowerTextInput` — the masking logic:
```scala
val displayText = ti match
    case _: UI.Password            => "•" * currentValue.length
    case _ if currentValue.isEmpty => ti.placeholder.getOrElse("")
    case _                         => currentValue
```
The password case returns `""` when value is empty. It should check `currentValue.isEmpty` first and return placeholder.

---

## 6. Radio click/space doesn't toggle (4 failures)

**Tests:**
- `VisualRadioTest`: "click unchecked becomes checked" — stays "( )"
- `VisualRadioTest`: "click checked becomes unchecked" — stays "(•)"
- `VisualRadioTest`: "space on focused radio toggles" — stays "( )"
- `VisualRadioTest`: "toggle is independent per radio" — middle radio stays "( )" after click

**Root cause:** Radio toggle doesn't work via click dispatch. The click handler on checkbox/radio uses `widgetOnClick` which toggles the internal checked ref. But the radio's click handler or the click dispatch may not be reaching the widget. Alternatively, the radio doesn't have `onClickSelf` wired correctly — Dispatch fires `onClick` which bubbles, but the radio's toggle is on `onClick` not `onClickSelf`.

**Location:** `Lower.scala` `lowerBooleanInput` — the `widgetOnClick` composition, or `Dispatch.scala` — click event routing for radio elements.

---

## 7. Readonly input cursor movement doesn't work (2 failures)

**Tests:**
- `VisualTextInputTest`: "allows cursor movement" — cursor at 2 instead of 1 after ArrowLeft
- `VisualTextareaTest`: "readonly textarea allows cursor movement" — cursor at 2 instead of 1

**Root cause:** The `widgetOnKeyDown` handler in `lowerTextInput` checks `if !ro && !dis` before processing ALL key events, including cursor movement. Readonly blocks cursor movement because the entire key handler is gated on `!readOnly`. Cursor movement (ArrowLeft, ArrowRight, Home, End) should be allowed even in readonly mode.

**Location:** `Lower.scala` `lowerTextInput` — the `if !ro && !dis` guard should only apply to editing operations (insert, delete, backspace), not cursor movement.

---

## 8. Button rendering issues (3 failures)

**Tests:**
- `VisualButtonTest`: "empty button — border only, no crash" — 2 rows instead of 3 (missing middle row)
- `VisualButtonTest`: "button Submit Application in 12-col — text truncated" — text wraps to second line instead of truncating
- `VisualButtonTest`: "button in narrow container — truncated" — right border missing, text overflows

**Root cause:** Button with no children has zero content height → border collapses to 2 rows (top+bottom with no middle). Long button text wraps instead of being truncated by the border. The button's content area doesn't enforce its width constraint on text children.

**Location:** `Layout.scala` — content height for empty nodes should be at least 1 when border is present. Text overflow within bordered containers not clipped.

---

## 9. Button beside input in row — different heights (1 failure)

**Tests:**
- `VisualButtonTest`: "button beside input in row — no overlap" — button (3 rows with border) and input (1 row) are not on the same row; button starts at row 0 but input text is at row 0 too, so "Go" appears at row 1 inside the border

**Root cause:** In row layout, a bordered button is 3 rows tall (border top + content + border bottom) while a plain input is 1 row. The test expected them on the same visual row, but the button's text "Go" is at row 1 (inside the border), not row 0.

**Verdict:** This is likely a test expectation issue rather than an implementation bug — the rendering is correct, the test's assertion logic needs adjustment to account for the button's border height.

---

## 10. Flex grow ratio imprecise (1 failure)

**Tests:**
- `VisualLayoutTest`: "flexGrow 2 beside flexGrow 1 — 2:1 ratio" — expected "A     B  " (A=6, B=3), got "A    B   " (A=5, B=4)

**Root cause:** Integer division in flex grow distribution. 9 free pixels / 3 total grow = 3 per unit. A gets 2*3=6, B gets 1*3=3. But the actual result gives A=5, B=4, suggesting the distribution is `(freeSpace * grow / totalGrow).toInt` which truncates differently.

**Location:** `Layout.scala` `distributeGrow` — integer rounding in grow distribution.

---

## 11. Flex shrink distribution wrong (1 failure)

**Tests:**
- `VisualLayoutTest`: "two 10-col children in 15-col parent — shrink proportionally" — expected each shrunk to ~7-8, got different split

**Root cause:** Similar integer rounding issue in shrink distribution. The expected frame assumed a specific split but the actual integer math produces a different (also valid) result.

**Location:** `Layout.scala` `distributeShrink`.

---

## 12. Justify spaceBetween/spaceAround off by one (2 failures)

**Tests:**
- `VisualLayoutTest`: "justify spaceBetween" — "B" one column too early
- `VisualLayoutTest`: "justify spaceAround" — "B" one column too late

**Root cause:** Integer division for spacing between items. With 2 items "A" and "B" in 10 cols, spaceBetween should put A at 0 and B at 9, but the gap calculation uses `remaining / (n-1)` which truncates.

**Location:** `Layout.scala` — `extraGap` and `startPos` calculations for justify modes.

---

## 13. Align stretch doesn't expand children (1 failure)

**Tests:**
- `VisualLayoutTest`: "align stretch" — bordered children are 1 row tall instead of 3 (the parent's cross axis)

**Root cause:** `align(stretch)` should expand children to fill the cross axis. In row layout with 3-row parent, children should be 3 rows tall. But bordered children collapse to their intrinsic height (2 rows for border only), not the cross size.

**Location:** `Layout.scala` `positionChildren` — the `childCrossActual` for `stretch` alignment may not propagate to the child's `arrange` call.

---

## 14. Margin doesn't create gap between siblings (2 failures)

**Tests:**
- `VisualLayoutTest`: "margin 1px all sides — element offset from siblings" — "B" at wrong position, "C" overlapping
- `VisualLayoutTest`: "margin 1px between two divs in column" — no gap between A and B

**Root cause:** Margin on a child in column layout doesn't create spacing between siblings. The `marTop` on the second child should push it down by 1 row, but it appears immediately after the first child.

**Location:** `Layout.scala` — margin handling in `arrange` or `positionChildren`. Top margin may not be added to the child's position offset.

---

## 15. Asymmetric border rendering wrong (2 failures)

**Tests:**
- `VisualLayoutTest`: "left border only" — no left border character on row 0, wrong on other rows
- `VisualLayoutTest`: "top plus bottom border only" — edges don't span full width

**Root cause:** When only some border sides are set (e.g. `borderLeft(1.px)` without top/right/bottom), the border drawing code uses corner characters that depend on ALL borders being present. With only left border, there are no corners, just `│` on each row. The current code draws corners conditionally but the edge logic may skip cells.

**Location:** `Painter.scala` `paintBorder` — corner and edge drawing with asymmetric border widths.

---

## 16. Form-related failures (3 failures)

**Tests:**
- `VisualFormTest`: "form with label + input + button — all visible in correct order" — button border wider than expected (theme padding)
- `VisualFormTest`: "can submit again after clear" — StringIndexOutOfBoundsException when cursor > text length after clear
- `VisualFormTest`: "space on focused button fires button onClick, NOT form onSubmit" — button Space doesn't fire onClick

**Root causes:**
- Button rendering: test expected `┌──┐│Go│└──┘` but actual has padding from theme: `┌────┐│ Go │└────┘`. Test expectation doesn't account for theme's `padding(0.px, 1.px)`.
- StringIndexOutOfBounds: after clearing a ref in onSubmit, the cursor position is still at the old length. Next render tries `displayText.substring(0, cursor)` where cursor > displayText.length.
- Button Space: Dispatch fires `onKeyDown` then `onClick` on Space for focused element, but the button's `onClick` handler may not be composed correctly in the handler chain.

**Locations:**
- Test expectation issue (button padding)
- `Lower.scala` — cursor position should be clamped: `math.min(cursorPos.get(), displayText.length)`
- `Dispatch.scala` — Space key handling for buttons

---

## 17. Focus event ordering (1 failure)

**Tests:**
- `VisualFocusTest`: "order: blur on old THEN focus on new" — blur handler didn't fire

**Root cause:** The onBlur handler on the first input doesn't fire when focus moves to the second input via click. The Dispatch click handler may not fire blur on the previously focused element before focusing the new one.

**Location:** `Dispatch.scala` `setFocusImpl` — should fire `oldNode.handlers.onBlur` before `newNode.handlers.onFocus`.

---

## 18. Textarea multi-line cursor positioning (1 failure)

**Tests:**
- `VisualTextareaTest`: "type A, Enter, type B — cursor on row 1 at column 1" — cursorRow is 0 instead of 1

**Root cause:** The cursor position is tracked as a single integer offset into the text string. For multi-line text "A\nB", cursor at position 3 (after "A\nB") is `content.x + 3 = 3`. But `cursorRow` reads `content.y` which is the row of the container, not the row within the multi-line text. The cursor position model doesn't account for line breaks.

**Location:** `Handlers.cursorPosition` is a single column offset. For multi-line textarea, it needs to be (row, col) or the Screen's `cursorPos` needs to account for line wrapping.

---

## 19. Signal settlement — stale derived value (1 failure)

**Tests:**
- `VisualReactiveTest`: "no stale values visible in the frame" — "Len: 1" instead of "Len: 2"

**Root cause:** Two `ref.render(...)` bindings on the same ref. After typing "XY" (ref = "XY"), `Echo: XY` is correct but `Len: 2` shows `Len: 1`. The second derived signal's piping fiber hasn't settled within the 5ms sleep.

**Location:** `Signal.asRef` piping fiber + `Screen.dispatch` settlement delay. The second `asRef` fiber may need more time, or the settlement approach (fixed sleep) is insufficient for multiple derived signals.

---

## 20. Select narrow container truncation (1 failure)

**Tests:**
- `VisualSelectTest`: "select in narrow container — text and arrow truncated" — expected "Alpha" (5 chars filling container), got "Alph" (4 chars)

**Root cause:** The select renders "Alpha ▼" (7 chars) in a container. The test expected the arrow to be truncated and "Alpha" to fill the 5-col container, but the text is truncated at 4 chars. The select's `Style.row` layout splits width between the text and the arrow "▼", giving the text child less than the full width.

**Location:** `Lower.scala` `lowerSelect` — the display text and arrow are separate `Resolved.Text` children in a row. Each gets a portion of the width via flex layout.

---

## Summary by severity

**P0 — Crashes:**
- StringIndexOutOfBoundsException on form submit after clear (#16)

**P1 — Broken features:**
- Radio click/space toggle doesn't work (#6)
- Readonly cursor movement blocked (#7)
- Password placeholder not shown (#5)
- Hidden elements take space (#4)
- Overflow hidden doesn't clip (#3)

**P2 — Layout constraints ignored:**
- width(0.px) not enforced (#1)
- minWidth/maxWidth not enforced (#2)
- maxHeight not enforced (#3)
- Margin doesn't space siblings (#14)
- Align stretch doesn't expand (#13)

**P3 — Imprecise layout:**
- Flex grow ratio rounding (#10)
- Flex shrink rounding (#11)
- Justify space modes off-by-one (#12)
- Asymmetric border rendering (#15)

**P4 — Test expectation issues:**
- Button padding from theme (#16 partial)
- Button beside input height difference (#9)

**P5 — Architectural limitations:**
- Signal settlement insufficient for multiple derived signals (#19)
- Textarea cursor doesn't model multi-line (#18)
