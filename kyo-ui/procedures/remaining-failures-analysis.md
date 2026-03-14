# Architectural Analysis of Remaining 10 Failures

## The Common Thread

Every remaining failure traces to the same structural pattern: **the pipeline's layers don't communicate constraints downward through the IR types**. Style properties, layout constraints, and element semantics are set in early stages but lost or ignored by later stages because the IR doesn't carry them across the boundary in a form the next stage can use.

---

## Failure 1: Text Ellipsis (`"abcde     "` — no `…`)

**Test**: `UI.div.style(Style.width(5.px).textWrap(Style.TextWrap.ellipsis))("abcdefghij")`

**What happens**: The text is truncated to "abcde" (flex shrink now works), but no ellipsis `…` appears.

**Root cause**: The style `textWrap(TextWrap.ellipsis)` is set on the **div**, not on the text node. In Styler, the div's `FlatStyle` gets `textWrap = ellipsis`. But when the child `Resolved.Text("abcdefghij")` is styled, `inheritText(parent)` inherits `textWrap` from the parent — so `Styled.Text.style.textWrap = ellipsis`. ✓

Then in Layout, the text measures as 10, flex shrinks it to 5. In Painter, `paintText` reads `cs.textWrap`. But line 179-181: `if cs.textOverflow == Style.TextOverflow.ellipsis then noWrap`. The test sets `textWrap(ellipsis)`, not `textOverflow(ellipsis)`. These are **two different style properties**.

**Architectural issue**: The style API has `textWrap` (with values `wrap`, `noWrap`, `ellipsis`) AND `textOverflow` (with values `clip`, `ellipsis`). The Painter checks `textOverflow` for ellipsis behavior. The test sets `textWrap` to `ellipsis`. The Painter's `effectiveWrap` check on line 180 uses `cs.textOverflow`, but the ellipsis trigger in the Painter (line 188-190) ALSO checks `cs.textOverflow`. The `textWrap.ellipsis` value is never checked by Painter at all.

**The deeper problem**: Two overlapping style properties (`textWrap` and `textOverflow`) both have an `ellipsis` value but with different semantics. `textWrap` controls line wrapping. `textOverflow` controls what happens when text exceeds its container. The Painter only honors `textOverflow.ellipsis`, not `textWrap.ellipsis`. This is a **style API design ambiguity** — the existence of two paths to "ellipsis" confuses both the implementer and the user.

---

## Failures 2-3: Text Align Center/Right (`"hi"` at position 0)

**Test**: `UI.div.style(Style.width(10.px).textAlign(Style.TextAlign.center))("hi")`

**What happens**: "hi" renders at position 0, not centered.

**Root cause**: Painter `paintText` (line 196-200) computes `startX` based on `cs.textAlign` and `t.bounds`. It reads `t.bounds.x` and adds an offset. For center: `startX = t.bounds.x + (t.bounds.w - lineWidth) / 2`.

The text "hi" has `lineWidth = 2`. If `t.bounds.w = 10`, then `startX = 0 + (10-2)/2 = 4`. That should work!

But after the flex shrink fix, the text node's `t.bounds.w` might not be 10. Let me trace: the div has `width(10.px)`. `resolveAvailable` gives the div `available.w = 10`. Inside the div, the text is the only child. `measureFlexChildren` measures `mainSizes(0) = measureWidth(text) = 2` (just "hi"). `freeSpace = 10 - 2 = 8`. With `flexGrow = 0.0` (default for Text from `inheritText`), no grow happens. So `childMain = 2`.

Then `positionChildren` assigns `childW = 2`. `arrange` for text: `w = min(measureWidth(text)=2, available.w=2) = 2`. So `t.bounds = Rect(0, 0, 2, 1)`.

Painter: `maxChars = 2`, `lineWidth = 2`, `startX = 0 + (2-2)/2 = 0`. The text is "left-aligned" within its 2-wide bounds. But the test expects it centered within the 10-wide div.

**Architectural issue**: Text alignment is supposed to be relative to the **parent container**, not the text's own bounds. In CSS, `text-align: center` centers text within its block container. But in this pipeline, the text node gets tight bounds from flex layout (width = intrinsic width), and alignment is computed relative to those tight bounds. Since bounds.w == text width, there's no room to center.

The fix requires either: (a) flex layout should give the text the full parent width instead of intrinsic width, or (b) Painter should reference the parent's bounds for alignment, not the text's own bounds. Option (a) is the CSS-correct behavior — in CSS, inline text fills the block container's width, and `text-align` positions it within that width. The current flex behavior treats text nodes like flex items with intrinsic sizing, which is wrong for inline text in a non-flex context.

**The deeper problem**: The pipeline models ALL children as flex items, even inline text. CSS has distinct layout modes: block layout (text fills width, text-align works), flex layout (items sized by flex algorithm), and inline layout (items flow left-to-right). The pipeline conflates these into one flex algorithm, which means text-align has nowhere to operate.

---

## Failure 4: Line Break (`"topbo"` — no line break)

**Test**: `UI.div(UI.span("top"), UI.br, UI.span("bot"))`

**What happens**: Lower converts `UI.Br` to `Resolved.Text("\n")`. The three children (span→text "top", br→text "\n", span→text "bot") are laid out as flex items in a row. The "\n" text is a 1-char-wide item between "top" and "bot". All three render on the same line.

**Architectural issue**: The pipeline has no concept of line breaking within inline flow. Every child is a flex item. The `\n` character is just another text node — it has no structural meaning. In CSS, `<br>` forces a line break in inline flow by interrupting the line box. The pipeline would need either:

1. A new IR variant (`Resolved.Break`) that Layout interprets as "advance to next line"
2. Or detection of "\n" in text during Layout's `splitLines` — but `splitLines` operates on a single text node, not across siblings

This is a **missing layout concept**: the pipeline has row and column flex, but no inline flow with line breaks.

---

## Failure 5: Horizontal Rule (`"ab┌─────┐b..."`)

**Test**: `UI.div(UI.span("above"), UI.hr, UI.span("below"))`

**What happens**: The `hr` element renders as a bordered box that overlaps surrounding content. The output shows partial text mixed with border characters.

**Root cause**: Even after the fix to use `borderBottom` instead of full `border`, the `hr` still has `width(100.pct)` and `height(1.px)`. In a row-direction div (default), the `hr` is a flex item alongside "above" and "below". It takes up 100% of the parent width as a horizontal flex item, pushing siblings into the overflow.

**Architectural issue**: `hr` is semantically a block-level element that should interrupt flow and span the full width. But in a row-direction parent, it becomes a horizontal flex item. The fix needs `hr` to either: force the parent into column direction, or be treated as a block-level interruption (like `br` but with visual content).

This is the same problem as `br` — **no block-level interruption in inline flow**.

---

## Failure 6: Form Renders All Widgets (truncated layout)

**Test**: Form with label+input in a row, checkbox+span in another row, and a button.

**Output**: `"Name:█Joh[ ]Agre┌───────┐"` — all content crammed into one line, truncated.

**Root cause**: The form element is lowered as a `Div` (line 300 in Lower: `case _: UI.Form => ...` goes to `lowerPassthrough`). Its children (row divs and button) are flex items in the default row direction. Since no explicit `column` direction is set on the form, everything lays out horizontally.

**Architectural issue**: In CSS, `<form>` is a block-level element — its children stack vertically by default. The pipeline treats all elements as flex-row unless explicitly styled as column. There's no concept of "block-level" vs "inline-level" elements. The theme could add `Style.column` for `Form`, but currently doesn't.

**The deeper problem**: The pipeline's uniform "everything is flex-row" default doesn't match HTML's block/inline distinction. Block elements (`div`, `p`, `form`, `h1-h6`, `ul`, `ol`) should default to column direction. Only inline elements (`span`, `a`, `button`, `label`) should default to row.

---

## Failure 7: Click Input/Type in Complex Test

**Test**: `UI.div(UI.label("Name:"), UI.input.value(ref.safe))` — click at (6, 0) to focus input, type "Joe".

**Output**: `ref.get() == ""` — typing didn't work.

**Root cause**: The click at (6, 0) targets position 6 on row 0. The label "Name:" takes 5 characters (positions 0-4). The input starts at position 5. But the click at x=6 might hit the label, not the input, depending on exact layout bounds. If the label's laid bounds extend to position 6, the click focuses the label (which is interactive but not a text input), and subsequent typing goes nowhere.

**Architectural issue**: Hit-testing uses laid bounds, which depend on flex sizing. In a row layout, the label and input split the available width. The label's intrinsic width is 5, but flex may give it more. The input may start later than expected. The test assumes x=6 is inside the input, but that depends on the exact flex distribution.

This is a **test fragility issue** rather than an architectural one — the test makes coordinate assumptions without knowing the exact layout.

---

## Failure 8: UL List Items (`"Item Item Item..."` — all on one line)

**Test**: `UI.ul(UI.li("Item 1"), UI.li("Item 2"), UI.li("Item 3"))`

**Output**: Items render side by side, not stacked.

**Root cause**: Same as the form issue. `UI.Ul` is lowered as a `Div` (via `lowerPassthrough`) with default row direction. `UI.Li` children are flex items in a row. In CSS, `<ul>` is a block element and `<li>` children stack vertically.

**Architectural issue**: Same as Failure 6 — no block-level default for structural elements.

---

## Failure 9: Select Escape (`onChange fires with "banana"`)

**Test**: Open select dropdown, press Escape — expects no selection change.

**Output**: `selected == "banana"` — onChange fired despite Escape.

**Root cause**: There's no Escape key handling in Lower's `lowerSelect`. When Escape is pressed, it falls through to the composed `onKeyDown` handler. The select's popup is visible (expanded = true). The key event propagates through the handler chain. If there's no explicit Escape case, the event may trigger default behavior or propagate to a parent handler.

Looking at the actual flow: Escape is dispatched as a `Key` event. Dispatch finds the focused element (the select). The select's `onKeyDown` handler is `composeKeyed(sel.attrs.onKeyDown, ctx.parentOnKeyDown)` — neither has Escape handling. So `noop` is returned.

But the test says `selected == "banana"`. This means `onChange` was called with "banana". The select's option click handlers fire `onChange`. But Escape isn't a click — it's a key event. So how does onChange get called?

Wait — looking at the test more carefully: the test opens the dropdown with `click(1, 0)`, then presses Escape. The click on the select fires `toggleClick` (onClickSelf), which sets `expanded = true`. But after my fix, `toggleClick` uses `Sync.Unsafe.defer { expanded.set(!expanded.get()) }`. At click time, `expanded.get()` is `false`, so it sets `true`. Then the re-render shows options. Then Escape fires — but the key routing sends it to the focused select node. The select's onKeyDown is noop (no widget-specific key handling in lowerSelect). So Escape does nothing to the select.

But the re-render after `s.click(1, 0)` re-lowers the select with `isExpanded = true`. This builds popup nodes. The option nodes have their own click handlers. But Escape is a key event, not a click...

Actually, let me re-read the test. After `click(1, 0)` to open, the test dispatches `key(Escape)`. This triggers `cycleFocus` for `Escape`? No — Escape is not Tab. It goes to the `case _:` branch in dispatch, which sends it to `findFocused → node.handlers.onKeyDown(ke)`. The select's onKeyDown is `composeKeyed(sel.attrs.onKeyDown, ctx.parentOnKeyDown)`. If `sel.attrs.onKeyDown` is absent, it's `noopKey`. So the key handler is effectively `noop`.

But the test says `selected == "banana"`. Where does "banana" come from? The only place onChange is fired is in `buildOptionNodes`. But those are click handlers, not key handlers.

Actually, the issue might be timing. When `click(1, 0)` fires, it dispatches the event AND re-renders. The re-render re-lowers the select. At this point, the toggle has already flipped `expanded` to true. The re-lower builds popup options. But the popup options are ALSO part of the laid tree now. The second event `key(Escape)` re-renders again. During this re-render, `isExpanded = expanded.get() = true` still. The popup is still visible.

Wait — maybe the initial `click(1, 0)` dispatches LeftPress to the select. The select's `onClickSelf` fires `toggleClick` which flips expanded to true. But then `.andThen(render)` re-renders. The new layout includes the popup. But the popup's first option might overlap the select's collapsed position. So when the user intended to click the select trigger, the popup option at the same position gets clicked in the re-render? No — the click happens before the re-render.

Actually, the answer might be simpler. After `click(1, 0)`, the select is expanded. Then `key(Escape)` dispatches. But `Escape` doesn't close the dropdown (no Escape handling). Then the re-render shows the dropdown. The test checks `selected` — but `selected` was set to `"banana"` by a previous action. Wait, `selected` is initialized to `""`. Let me re-read the test:

```scala
var selected = ""
val s = screen(
    UI.select.value("apple").onChange(v => selected = v)(
        UI.option.value("apple")("Apple"),
        UI.option.value("banana")("Banana")
    ), 15, 5)
for
    _ <- s.render
    _ <- s.click(1, 0) // open
    _ <- s.key(UI.Keyboard.Escape)
yield assert(selected == "", ...)
```

`selected` starts at "". After `click(1, 0)`, the select's `onClickSelf` fires (toggle). But `onClick` also fires. The select's `onClick` is `composeUnit(sel.attrs.onClick, ctx.parentOnClick)` — both noop. So `selected` stays "".

After the re-render with expanded=true, the popup options are visible. When `key(Escape)` dispatches, it goes to `findFocused → select node → onKeyDown`. But the select has no key handling. So `noop`. But `selected == "banana"` somehow.

Actually — maybe the issue is that `click(1, 0)` on the expanded select (after the first click opens it) hits an OPTION in the popup, not the select itself. Because `dispatch` does `.andThen(render)`, the re-render happens AFTER the toggle. But the dispatch uses `prevLayout` from BEFORE the toggle. So the first click uses the collapsed layout — hits the select. Then re-renders to show popup. Then `key(Escape)` uses the expanded layout.

But `selected == "banana"` means onChange was called. The only place that happens is in option click handlers. But Escape is a key event... unless Escape somehow triggers a click? No, Dispatch's key handler doesn't fire onClick for Escape.

**Actually**, I bet the issue is that the select re-renders with expanded=true, and during the re-lower, `buildOptionNodes` creates option nodes. Each option node has an `onClick` handler. The option click handler uses `Sync.Unsafe.defer` ... wait, no. The option click handlers are:

```scala
val optClick: Unit < Async =
    writeStringRef(sel.value, key, ctx.state, value)
        .andThen(expanded.set(false))
        .andThen(...)
```

This is NOT wrapped in `Sync.Unsafe.defer`! `writeStringRef` does `ref.set(newVal)` which is eager! The entire `optClick` is evaluated EAGERLY at lower time when building the option nodes!

**That's the bug!** Each option's click handler has an eager `writeStringRef` call. When the select re-lowers with `isExpanded = true`, building the option nodes evaluates `writeStringRef(sel.value, key, ctx.state, "banana")` eagerly — setting the value to "banana" during lowering, not during a click.

**Architectural issue**: Same pattern as the checkbox bug — handler closures with eager side effects. The `buildOptionNodes` creates click handlers that should be deferred but aren't.

---

## Failure 10: Click on Different Buttons (`focused == "left"`)

**Test**: Two buttons in a row, click left at (2,1), then click right at (12,1). Expected: right focused.

**Output**: `focused == "left"` — second click didn't change focus.

**Root cause**: The right button is at x=12, but the two buttons' actual positions depend on flex layout. Each button has border (from theme), so its bounds are larger. If the two buttons don't reach x=12 within a 20-col viewport, the click misses.

Looking at the test: `UI.div.style(Style.row)( UI.button.onFocus{...}("L"), UI.button.onFocus{...}("R") )`, cols=20, rows=3. Each button has theme border + padding. The label "L" is 1 char. With border (1px each side) and padding (0px top/bottom, 1px left/right), each button is `1 + 1 + 1 + 1 + 1 = 5` wide (border-left + pad-left + content + pad-right + border-right). Two buttons = 10. They start at x=0. First button: 0-4. Second button: 5-9. Click at (12,1) misses both.

**Architectural issue**: This is a test issue — the click coordinate doesn't match the actual layout. But the underlying problem is that **button sizes depend on theme styles**, which the test doesn't account for. The test assumes buttons are small enough to fit, but theme-injected borders make them wider.

---

## Summary: Three Architectural Root Causes

### 1. No block/inline layout distinction

The pipeline models everything as flex items in a row. Elements that should stack vertically (`form`, `ul`, `ol`, `h1-h6`, `p`) don't, because there's no block-level default. Line breaks (`br`) and horizontal rules (`hr`) can't interrupt inline flow because inline flow doesn't exist as a concept. This causes failures 4, 5, 6, and 8.

**Fix**: Either add `Style.column` to the theme style for block-level elements (quick, pragmatic), or introduce a proper inline/block layout mode in Layout (correct, larger effort).

### 2. Text bounds are flex-tight, not container-wide

The flex algorithm sizes text to its intrinsic width. Text alignment operates within the text's own bounds, not the parent's bounds. This causes failures 2 and 3.

**Fix**: When the parent has a non-row direction or the text is the only child, give it the full parent width instead of intrinsic width. Or introduce a "fill" behavior for text in non-flex contexts.

### 3. Handler closures with eager side effects (recurring)

Despite fixing this pattern in checkboxes, text inputs, and select toggles, the same bug exists in `buildOptionNodes` — option click handlers execute `writeStringRef` eagerly at lower time. This causes failure 9.

**Fix**: Apply `Sync.Unsafe.defer` to option click handlers, same pattern as all other widget handlers.
