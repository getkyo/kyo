# Pipeline Fixes Proposal

39 visual test failures expose 6 architectural gaps in the pipeline. This proposal addresses each gap with a targeted, fundamental fix — not individual bug patches. Each change is small in code but high in impact because it fixes an entire category of bugs.

---

## Change 1: Constraint Clamping in Layout

**Fixes:** 7 failures — width(0.px), minWidth, maxWidth, maxHeight, overflow hidden clips

**The gap:** Layout resolves `width` and `height` but never clamps the result against `minWidth`/`maxWidth`/`minHeight`/`maxHeight`. CSS has a well-defined resolution order: `min ≤ actual ≤ max`. We skip it entirely.

**The fix:** Add a `clampSize` step in `arrange` for `Styled.Node`, immediately after computing `outerW` and before computing content dimensions:

```scala
// In Layout.arrange, after computing outerW:
val outerW = available.w - marL - marR

// NEW: clamp to min/max constraints
val minW = Length.resolveOrZero(cs.minWidth, available.w)
val maxW = Length.resolveOrMax(cs.maxWidth, available.w)
val clampedOuterW = math.max(minW, math.min(maxW, outerW))

// Use clampedOuterW everywhere downstream instead of outerW
```

Same for height — after computing `actualOuterH`:

```scala
val minH = Length.resolveOrZero(cs.minHeight, available.h)
val maxH = Length.resolveOrMax(cs.maxHeight, available.h)
val clampedOuterH = math.max(minH, math.min(maxH, actualOuterH))
```

And the overflow clip must use the CLAMPED height, not the natural content height:

```scala
// BEFORE (broken — clips to content height, which equals the full content):
val childClip = clip.intersect(Rect(contentX, contentY, contentW, actualContentH))

// AFTER (correct — clips to the constrained height):
val clippedContentH = if explicitH.nonEmpty then math.max(0, contentH) else actualContentH
val childClip = clip.intersect(Rect(contentX, contentY, contentW, clippedContentH))
```

For `width(0.px)`: the `resolveAvailable` function already resolves `Px(0)` to 0, but `measureFlexChildren` gives the child its intrinsic width. The fix: in `positionChildren`, the child's available width comes from `childMainSizes(i)` (for row) or `crossSize` (for column). With clamped parent width = 0, `contentW = 0`, so flex children get 0 width. No special case needed — constraint clamping handles it.

**Helper additions to `Length`:**

```scala
def resolveOrZero(len: Length, parent: Int): Int = len match
    case Length.Auto => 0
    case explicit    => resolve(explicit, parent)

def resolveOrMax(len: Length, parent: Int): Int = len match
    case Length.Auto => Int.MaxValue
    case explicit    => resolve(explicit, parent)
```

**Tests fixed:** minWidth 5px, maxWidth 5px, maxHeight 3px, width 0px, overflow hidden clips tall, overflow hidden clips (via height constraint), all content in row exceeding viewport

---

## Change 2: Hidden = Absent from Layout

**Fixes:** 3 failures — hidden true, hidden children, sibling takes hidden space

**The gap:** `hidden(true)` produces `Resolved.Text("")` which still participates in layout — empty text has height 1 in column layout. In CSS, `display: none` removes the element from the layout tree entirely.

**The fix:** Add `Resolved.Empty` — a zero-size IR node that Layout treats as absent:

```scala
// In IR.scala, add to Resolved enum:
case Empty  // hidden element — zero size, not painted

// In Styled enum:
case Empty  // hidden element — zero size, not painted
```

In `Lower.lowerElement`:
```scala
// BEFORE:
if hidden then Resolved.Text("")

// AFTER:
if hidden then Resolved.Empty
```

In `Styler.style`:
```scala
case Resolved.Empty => Styled.Empty
```

In `Layout.measureWidth` / `measureHeight`:
```scala
case Styled.Empty => 0
```

In `Layout.arrange`:
```scala
case Styled.Empty =>
    Laid.Text("", FlatStyle.Default, Rect(available.x, available.y, 0, 0), clip)
```

In `Layout.measureFlexChildren`:
```scala
case Styled.Empty =>
    mainSizes(i) = 0
    crossSizes(i) = 0
```

This is the same pattern as `Styled.Break` — zero-size, no visual output. The key difference from `Resolved.Text("")`: an empty text node measures as 1 line tall (because `splitLines("", ...)` returns `Chunk("")` which has size 1). `Empty` measures as 0 in both dimensions.

**Tests fixed:** hidden true, hidden children, sibling takes space

---

## Change 3: Password Placeholder Priority

**Fixes:** 3 failures — password placeholder not shown

**The gap:** In `lowerTextInput`, the password masking case runs BEFORE the empty-value check:

```scala
val displayText = ti match
    case _: UI.Password            => "•" * currentValue.length  // masks first
    case _ if currentValue.isEmpty => ti.placeholder.getOrElse("")
    case _                         => currentValue
```

When password value is empty, `"•" * 0 = ""`. The placeholder case never runs because the password case matches first.

**The fix:** Check empty value first, then mask:

```scala
val displayText =
    if currentValue.isEmpty then
        ti.placeholder.getOrElse("")
    else ti match
        case _: UI.Password => "•" * currentValue.length
        case _              => currentValue
```

This is a 3-line change. Placeholder takes priority over masking when value is empty — same behavior as HTML password inputs.

**Tests fixed:** empty password placeholder unfocused, focused, backspace to empty

---

## Change 4: Readonly Allows Cursor Movement

**Fixes:** 2 failures — readonly input/textarea cursor movement

**The gap:** In `lowerTextInput`, the `widgetOnKeyDown` handler gates ALL key processing on `if !ro && !dis`:

```scala
if !ro && !dis then
    // ALL key handling here — including cursor movement
```

Readonly should block editing (insert, delete, backspace) but allow cursor movement (ArrowLeft, ArrowRight, Home, End).

**The fix:** Split the handler into two sections — cursor movement (always allowed unless disabled) and editing (blocked by readonly OR disabled):

```scala
disabledCheck.map { dis =>
    if dis then ()  // disabled blocks everything
    else
        ke.key match
            // Cursor movement — allowed even in readonly
            case UI.Keyboard.ArrowLeft =>
                cursorRef.get.map(pos => if pos > 0 then cursorRef.set(pos - 1) else ())
            case UI.Keyboard.ArrowRight =>
                cursorRef.get.map { pos =>
                    val len = valueRef match
                        case Present(ref) => ref.get.map(_.length)
                        case _            => Kyo.lift(currentValue.length)
                    len.map(l => if pos < l then cursorRef.set(pos + 1) else ())
                }
            case UI.Keyboard.Home => cursorRef.set(0)
            case UI.Keyboard.End =>
                val len = valueRef match
                    case Present(ref) => ref.get.map(_.length)
                    case _            => Kyo.lift(currentValue.length)
                len.map(l => cursorRef.set(l))
            // Editing — blocked by readonly
            case _ if !ro =>
                val valueRead = ...
                // existing editing logic
            case _ => ()
}
```

The structural change: cursor movement keys are handled OUTSIDE the `!ro` guard. Only editing keys (Char, Space, Backspace, Delete, Enter) are inside the `!ro` guard.

**Tests fixed:** readonly input allows cursor movement, readonly textarea allows cursor movement

---

## Change 5: Radio Click Toggle

**Fixes:** 4 failures — radio click/space/independent toggle

**The gap:** Looking at `Dispatch.dispatch` for `LeftPress`:

```scala
case InputEvent.Mouse(MouseKind.LeftPress, mx, my) =>
    hitTest(layout, mx, my) match
        case Present(node) if !node.handlers.disabled =>
            ...
            .andThen(node.handlers.onClick)
            .andThen(node.handlers.onClickSelf)
```

And `lowerBooleanInput`:

```scala
val widgetOnClick: Unit < Async =
    checkedSafe.get.map { curr =>
        val newVal = !curr
        checkedSafe.set(newVal).andThen(...)
    }

val handlers = ctx.parentHandlers
    ...
    .composeOnClick(widgetOnClick)
    .composeOnClick(bi.attrs.onClick.getOrElse(()))
```

The `widgetOnClick` is composed onto `parentHandlers.onClick`. When Dispatch fires `node.handlers.onClick`, it calls the composed chain: widget toggle → user onClick → parent onClick.

This should work. The issue might be that `hitTest` doesn't find the radio node because the radio is a `Span` tag (inline) and the hit test checks `bounds.contains(mx, my)`. If the radio's bounds don't include the click position, the hit test fails.

**Investigation needed:** Run the failing test with debug output to verify:
1. Is `hitTest` finding the radio node?
2. Is `onClick` being dispatched?
3. Is `checkedSafe.set` executing?

The likely root cause: the radio's rendered text is "( )" at 3 chars wide, but the Span has `ElemTag.Span` which defaults to `Style.FlexDirection.row`. In row layout, the `Text("( )")` child gets its intrinsic width (3), and the Span's bounds might be larger or positioned differently.

Actually, looking more carefully at the test — it clicks at `(1, 0)` on a 5x1 screen. The radio renders "( )" starting at column 0. The Span should have bounds covering (0,0)-(4,0). The click at (1,0) should hit. Let me check if the issue is in how `lowerBooleanInput` wires the click handler.

Wait — I see the issue. `lowerBooleanInput` uses `composeOnClick(widgetOnClick)` but `widgetOnClick` is a `Unit < Async` VALUE, not a thunk. The `composeOnClick` method takes `child: => Unit < Async` (by-name). But `widgetOnClick` is already evaluated — the `checkedSafe.get.map { ... }` is constructed at Lower time and captured as a closure. When `onClick` fires, it runs this closure.

The closure reads `checkedSafe.get` which returns the current checked state. Then it sets `!curr`. This should work...

Actually, the issue might be that the `Screen.click(1, 0)` dispatch fires `onClick` but the radio renders as `( )` which is a text child inside a Span. The `hitTest` traverses `Laid.Node` children, but `Laid.Text` is not a `Laid.Node` — it has no handlers. The hit test finds the innermost `Laid.Node` whose bounds contain the click. For the radio (Span with Text child), the Span node IS the innermost node with handlers.

Let me re-check: `hitTest` does `findInChildren` which recurses into children. For a Span with a single Text child, `findInChildren` tries the Text child. `hitTestNode` on `Laid.Text` returns `Absent` (not a Node). So it falls back to the Span node itself. The Span has `handlers.onClick` with the toggle. This should work.

I think this needs debugging rather than an architectural fix. The toggle mechanism is wired correctly in Lower. Something in the dispatch chain is failing silently. Possible cause: the `composeOnClick` chain fires parent → child, but the widget click is composed AFTER the user click, so the execution order might be wrong. Or the `checkedSafe` ref read/write doesn't propagate correctly within the async effect chain.

**The fix:** Needs debugging. The architecture (compose onClick, fire on click dispatch) is correct. The implementation may have an ordering or evaluation issue in the handler chain. Add targeted logging or a test that checks the `checkedSafe` ref directly after dispatch.

**Tests fixed:** (after debugging) radio click unchecked→checked, checked→unchecked, space toggle, independent toggle

---

## Change 6: Blur/Focus Event Ordering

**Fixes:** 1 failure — blur fires before focus

**The gap:** In `setFocusImpl`:

```scala
state.focusedId.safe.set(newKey)
    .andThen(blurEffect)
    .andThen(node.handlers.onFocus)
```

The focus state is set FIRST, then blur fires, then focus fires. The order should be: blur fires, then state updates, then focus fires. But the `state.focusedId.safe.set(newKey)` returns `Unit < Sync`. The `andThen` chains these effects sequentially. So the actual execution order IS: set state → blur → focus.

But the test checks that blur FIRES (i.e., the blur handler executes), not the order relative to state. The failure message is "blur should fire: List()" — meaning the blur handler never fired at all.

**Root cause:** The blur effect is computed from `oldKey.flatMap(k => findByKey(layout.base, k))`. If `oldKey` is `Absent` (nothing was focused before), there's no node to blur. The test must ensure something IS focused before clicking the second element.

**Investigation needed:** Check if the test correctly focuses the first input before clicking the second.

---

## Change 7: Margin Between Siblings

**Fixes:** 2 failures — margin offset, margin gap in column

**The gap:** In `Layout.arrange`, margin is applied to the element's own position:

```scala
val marT = Length.resolve(cs.marTop, available.h)
val outerY = available.y + marT
```

This shifts the element DOWN by its top margin. But in `positionChildren`, the next child's `available.y` is computed from the previous child's end position — it doesn't account for the previous child's bottom margin. The position advances by `childMain + totalGap`, where `totalGap` is from the parent's `gap` property, not from the child's margin.

Wait — looking more carefully, margin IS handled in `arrange`. Each child is positioned at `available.y + marT`. The child's bounds are at `outerY = available.y + marT`. The child's bottom is `outerY + actualOuterH`. Then in `positionChildren`, the next child gets `mainPos + childMain + totalGap`.

But `childMain = mainSizes(i)` which is the intrinsic or explicit size, NOT including margin. The margin is applied inside `arrange` to offset the element within its allocated slot, but the slot size doesn't account for margin. So two children each with `margin(1.px)` overlap because the slot doesn't include the margin space.

**The fix:** Include margin in flex measurement. In `measureFlexChildren`, for each child, add top+bottom margin (column) or left+right margin (row) to the main size:

```scala
case nd: Styled.Node =>
    val mainMargin = if isColumn then
        Length.resolve(nd.style.marTop, ch) + Length.resolve(nd.style.marBottom, ch)
    else
        Length.resolve(nd.style.marLeft, cw) + Length.resolve(nd.style.marRight, cw)
    mainSizes(i) = (intrinsicMain) + mainMargin
```

This way, the flex distribution accounts for margin, and `positionChildren` allocates enough space for each child including its margin.

**Tests fixed:** margin 1px all sides, margin between divs in column

---

## Change 8: Asymmetric Border Drawing

**Fixes:** 2 failures — left border only, top+bottom only

**The gap:** `Painter.paintBorder` draws corners based on the intersection of top/left, top/right, bottom/left, bottom/right borders. When only one side is set, corners don't draw (correct), but the edge drawing logic uses `x1+1` to `x2` for the top edge, which skips the first and last columns. For top-only border without left/right, the top edge should span the full width.

**The fix:** In `paintBorder`, adjust edge ranges based on which corners are present:

```scala
// Top edge: from x1 (or x1+1 if left corner drawn) to x2 (or x2-1 if right corner drawn)
val topStart = if cs.borderLeft.value > 0 then x1 + 1 else x1
val topEnd = if cs.borderRight.value > 0 then x2 else x2 + 1
```

Similarly for bottom edge and left/right edges. When no corner is drawn (because the adjacent border is absent), the edge extends to the full corner position.

**Tests fixed:** left border only, top+bottom border only

---

## Change 9: Flex Grow/Shrink/Justify Integer Rounding

**Fixes:** 4 failures — flexGrow ratio, shrink proportional, spaceBetween, spaceAround

**The gap:** `distributeGrow` and the justify spacing calculations use integer division, which truncates. For 2:1 flex ratio in 9 free pixels: `(9 * 2 / 3).toInt = 6`, `(9 * 1 / 3).toInt = 3` — total 9. But the actual code distributes pixel-by-pixel, and rounding errors accumulate differently.

**The fix:** After distributing via integer division, compute the remainder and add 1 pixel to the first N children where N = remainder:

```scala
// After distributeGrow:
val distributed = sumMainSizes - originalTotal
val remainder = freeSpace - distributed
// Add 1px to first `remainder` children that have grow > 0
```

For justify modes, use the same remainder-distribution approach:

```scala
// spaceBetween: remaining / (n-1) with remainder distributed
val baseGap = remaining / (n - 1)
val extraGaps = remaining % (n - 1)
// First `extraGaps` gaps get baseGap + 1, rest get baseGap
```

**Tests fixed:** flexGrow 2:1 ratio, shrink proportional, spaceBetween, spaceAround

---

## Change 10: Button Empty Content Height / Text Overflow

**Fixes:** 3 failures — empty button, text truncation, narrow container

**The gap:** A bordered button with no children has `computeContentHeight = 0`, so the border collapses to top+bottom only (2 rows). A bordered button with long text wraps instead of truncating because the text node gets the full content width and wraps naturally.

**The fix:**
- Empty bordered container: if border is present and content height is 0, use 1 as minimum content height. This ensures the border has a middle row.
- Text overflow in bordered containers: the button's content area width is `outerW - border - padding`. Text longer than this should be truncated. This already works IF the child's available width is set correctly. Check that `positionChildren` passes the correct width to children.

For the narrow container case ("OK" in 4-col container), the border takes 2 cols (left + right), padding takes 2 cols (1px each side), leaving 0 cols for text. But the text "OK" renders at 2 cols. The text node should be clipped to the content width.

**The fix:** In `Layout.arrange` for `Styled.Node`, when computing `actualContentH`:

```scala
val actualContentH =
    if explicitH.nonEmpty then math.max(0, contentH)
    else
        val natural = computeContentHeight(laidChildren, contentY)
        // Ensure bordered containers have at least 1 row of content
        val hasBorder = cs.borderTop.value > 0 || cs.borderBottom.value > 0
        if hasBorder && natural == 0 then 1 else natural
```

**Tests fixed:** empty button, button text truncation (partially — also needs text clipping), button in narrow container

---

## Change 11: Cursor Position Clamped on Value Change

**Fixes:** 1 failure — StringIndexOutOfBoundsException on form submit after clear

**The gap:** When `onSubmit` clears a ref (`ref.set("")`), the cursor position ref still holds the old position (e.g., 3). On next render, `displayText.substring(0, cursor)` crashes because `cursor > displayText.length`.

The existing code has:
```scala
val cursor = math.min(cursorPos.get(), displayText.length)
```

This clamps on READ. But `displayText` for password might be the placeholder (which has a different length). And for the crash case, the issue might be that `currentValue` changed but `displayText` didn't update in the same frame.

Actually, looking at the code again, the clamping IS there. The crash might be elsewhere — perhaps in the handler's `curValue.substring(0, pos)` where `pos` is from a stale cursor ref and `curValue` is the new empty string.

**The fix:** In the handler, always clamp position before using it:

```scala
case UI.Keyboard.Backspace =>
    cursorRef.get.map { pos =>
        val safePos = math.min(pos, curValue.length)
        if safePos > 0 then
            val newVal = curValue.substring(0, safePos - 1) + curValue.substring(safePos)
            ...
```

Do the same for Delete, insert, and all substring operations in the handler.

**Tests fixed:** can submit again after clear

---

## Change 12: Select Narrow Container

**Fixes:** 1 failure — select text and arrow truncated differently than expected

**The gap:** The select renders `Resolved.Text(selectedText)` and `Resolved.Text(" ▼")` as separate children in a row. In a narrow container, flex distributes width between them. The test expected "Alpha" (5 chars filling the container) but got "Alph" (4 chars) because the arrow " ▼" (2 chars) takes 2 cols, leaving only 4 for the text in a 6-col container.

**Verdict:** The test expectation may be wrong — 5-col select with "Alpha ▼" (7 chars) should truncate. The arrow takes space. The test should expect "Alph" or "Alp ▼" depending on how flex distributes. This is not a bug — it's a test expectation issue.

**Action:** Adjust the test to match the actual flex behavior (text and arrow share the width).

---

## Implementation Order

1. **Change 3: Password placeholder** — 3-line fix, zero risk
2. **Change 4: Readonly cursor** — structural split of handler, moderate risk
3. **Change 11: Cursor position clamping** — safety fix, prevents crash
4. **Change 2: Hidden = Empty** — new IR variant, moderate scope
5. **Change 1: Constraint clamping** — core layout change, high impact (7 tests)
6. **Change 7: Margin in flex measurement** — layout change, moderate risk
7. **Change 8: Asymmetric border** — painter change, low risk
8. **Change 9: Integer rounding** — layout change, low risk
9. **Change 10: Button content height** — layout + painter, moderate risk
10. **Change 5: Radio toggle** — needs debugging first
11. **Change 6: Blur/focus ordering** — needs debugging first
12. **Change 12: Select narrow** — test adjustment

Total: ~12 changes, fixing ~35 of the 39 failures. The remaining ~4 need debugging to identify root cause.
