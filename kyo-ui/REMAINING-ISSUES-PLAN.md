# Remaining Issues: Implementation Plan

Addresses all 9 remaining issues (B6, B7, B8/N2, B20, B22, N1, N3, N4) through
4 architectural changes. Each phase eliminates a class of bugs, not just one instance.

---

## Phase 1: Widget Trait

**Fixes**: N2, B8, B22, B20 (and prevents future measurement/rendering divergence)

**Root cause**: Leaf widgets draw content via direct Canvas calls but the layout
engine can't see it — it measures children (which these widgets don't have).
WidgetDispatch has 3 separate match cascades (intrinsicWidth, intrinsicHeight,
render) that must stay in sync. Missing a case produces silent sizing bugs.

### 1.1 Design

Create a sealed `Widget` trait that bundles measurement and rendering into one unit.
Each widget implements all methods — the compiler enforces completeness.

```scala
// kyo-ui/jvm/src/main/scala/kyo/internal/tui2/Widget.scala
private[kyo] trait Widget:
    /** Content width without insets. Return -1 for children-based measurement. */
    def measureWidth(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int

    /** Content height without insets. Return -1 for children-based measurement. */
    def measureHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int

    /** Extra width added to children measurement (e.g. list markers). */
    def extraWidth(elem: UI.Element): Int = 0

    /** Whether this widget controls its own chrome (border/bg) or expects theme decoration. */
    def selfRendered: Boolean = false

    /** Render content into the content rect (cx, cy, cw, ch). */
    def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
        using Frame, AllowUnsafe
    ): Unit

    /** Handle key event. Return true if consumed. */
    def handleKey(elem: UI.Element, event: InputEvent.Key, ctx: RenderCtx)(using Frame, AllowUnsafe): Boolean = false

    /** Handle paste event. Return true if consumed. */
    def handlePaste(elem: UI.Element, paste: String, ctx: RenderCtx)(using Frame, AllowUnsafe): Boolean = false
end Widget
```

### 1.2 Widget Implementations

Convert each existing widget object to implement the trait. The implementation
is mechanical — move the match case body into the corresponding method:

| Widget Object | measureWidth | measureHeight | extraWidth | selfRendered | render | handleKey | handlePaste |
|--------------|-------------|---------------|------------|-------------|--------|-----------|-------------|
| `ContainerWidget` | -1 | -1 | 0 | false | Container.render | — | — |
| `TextInputWidget` | -1 | **1** | 0 | false | TextInputW.render | TextInputW.handleKey | TextInputW.handlePaste |
| `TextareaWidget` | -1 | **3** | 0 | false | TextInputW.render | TextInputW.handleKey | TextInputW.handlePaste |
| `CheckboxWidget` | 3 | 1 | 0 | **true** | CheckboxW.render | CheckboxW.handleKey | — |
| `RangeWidget` | -1 | 1 | 0 | **true** | RangeW.render | RangeW.handleKey | — |
| `SelectWidget` | maxOptText+2 | -1 | 0 | false | PickerW.render | PickerW.handleKey | — |
| `EditablePickerWidget` | -1 | **1** | 0 | false | PickerW.renderEditable | PickerW.handleEditableKey | — |
| `FileInputWidget` | -1 | **1** | 0 | false | FileInputW.render | FileInputW.handleKey | — |
| `HrWidget` | -1 | 1 | 0 | **true** | HrW.render | — | — |
| `LiWidget` | -1 | -1 | 2 | false | Li render logic | — | — |
| `TableWidget` | -1 | -1 | 0 | false | TableW.render | — | — |
| `ImgWidget` | -1 | -1 | 0 | false | ImgW.render | — | — |
| `AnchorWidget` | -1 | -1 | 0 | false | Container.render | anchor key logic | — |
| `ButtonWidget` | -1 | -1 | 0 | false | Container.render | button key logic | — |

Key fixes in this table:
- **TextInput/EditablePicker/FileInput**: `measureHeight = 1` (was missing → ch=0 bug)
- **Textarea**: `measureHeight = 3` (sensible default for multi-line)
- **Checkbox/Range/Hr**: `selfRendered = true` (prevents theme border overlap)

### 1.3 Widget Registry

Replace WidgetDispatch's match cascades with a single lookup:

```scala
private[kyo] object WidgetRegistry:
    /** Returns the Widget for a given element, or null for container fallback. */
    def lookup(elem: UI.Element): Widget =
        elem match
            case _: UI.TextInput => elem match
                case _: UI.Textarea => TextareaWidget
                case _              => TextInputWidget
            case _: UI.BooleanInput => CheckboxWidget
            case _: UI.RangeInput   => RangeWidget
            case _: UI.PickerInput  => elem match
                case _: UI.Select => SelectWidget
                case _            => EditablePickerWidget
            case _: UI.FileInput => FileInputWidget
            case _: UI.Hr        => HrWidget
            case _: UI.Li        => LiWidget
            case _: UI.Table     => TableWidget
            case _: UI.Img       => ImgWidget
            case _: UI.Anchor    => AnchorWidget
            case _: UI.Button    => ButtonWidget
            case _               => null // container fallback
```

One match, one place. Adding a new widget = adding a Widget impl + one case here.

### 1.4 Integration Points

**FlexLayout.measureW** (line ~42): Replace `WidgetDispatch.intrinsicWidth` /
`WidgetDispatch.extraWidth` calls with:

```scala
val widget = WidgetRegistry.lookup(elem)
val intrinsic = if widget != null then widget.measureWidth(elem, availW - insetW - marginW, ctx) else -1
val extra = if widget != null then widget.extraWidth(elem) else 0
```

**FlexLayout.measureH** (line ~94): Same pattern with `widget.measureHeight`.

**paintElement** (Render.scala line ~189): Replace `WidgetDispatch.render` with:

```scala
val widget = WidgetRegistry.lookup(elem)
if widget != null then widget.render(elem, cx, cy, cw, ch, rs, ctx)
else Container.render(elem, cx, cy, cw, ch, rs, ctx)
```

**EventDispatch / handleKey / handlePaste / handleClipboardPaste**: Route through
widget registry instead of WidgetDispatch match. Move the `handleClipboardPaste`
text extraction logic (the `findText` helper that extracts `text/plain` from
clipboard items) into EventDispatch itself, then call `widget.handlePaste` with
the extracted string. This keeps clipboard item parsing centralized rather than
per-widget.

**Delete** WidgetDispatch.scala entirely once migration is complete.

### 1.5 selfRendered Integration

In `paintElement` (Render.scala), skip theme border/background for self-rendered widgets:

```scala
val widget = WidgetRegistry.lookup(elem)
val isSelfRendered = widget != null && widget.selfRendered

// Step 5: Paint border — skip for self-rendered
if !isSelfRendered && (bt | br | bb | bl) != 0 && rs.borderStyle != 0 then
    ctx.canvas.border(tx, ty, adjW, adjH, rs)

// Step 6: Content rect — no inset for self-rendered
val (cx, cy, cw, ch) =
    if isSelfRendered then (tx, ty, adjW, adjH)
    else (tx + bl + rs.padL, ty + bt + rs.padT, adjW - bl - br - rs.padL - rs.padR, adjH - bt - bb - rs.padT - rs.padB)
```

And in `ResolvedTheme`, remove styles that conflict with self-rendered widgets:

```scala
// Remove from default theme match:
// case _: UI.PickerInput | _: UI.RangeInput | _: UI.FileInput => inputStyle
// Replace with:
case _: UI.PickerInput | _: UI.FileInput => inputStyle
// RangeInput no longer gets inputStyle (it's self-rendered)
```

Similarly, `hrStyle` changes from `borderBottom(1.px, "#666")` to just
`color("#666")` — HrW draws the line itself.

### 1.6 Verification

- All 10 TerminalEmulatorTest tests pass
- Run QA: text inputs show content row inside border (N2/B8 fixed)
- HR renders as single line (N1 fixed)
- Range gets full content area without theme border (B22 layout fixed)

---

## Phase 2: RangeW Track Rendering + Select Text Fix

**Fixes**: B22 (range visual), B20 (select text at full width)

These are widget-specific rendering bugs that Phase 1's layout fixes expose
but don't fully resolve.

### 2.1 RangeW: Draw Character Track

Current RangeW uses only `fillBg` (invisible when colors don't contrast).
Replace with character-based track:

```scala
def render(ri, cx, cy, cw, ch, rs, ctx): Unit =
    val value  = ValueResolver.resolveDouble(ri.value, ctx.signals)
    val min    = ri.min.getOrElse(0.0)
    val max    = ri.max.getOrElse(100.0)
    val pct    = if max > min then (value - min) / (max - min) else 0.0
    val filled = math.min(cw, math.max(0, (pct * cw).toInt))
    val style  = rs.cellStyle
    // Filled portion: solid block
    var i = 0
    while i < filled do
        ctx.canvas.screen.set(cx + i, cy, '█', style)
        i += 1
    // Unfilled portion: light shade
    while i < cw do
        ctx.canvas.screen.set(cx + i, cy, '░', style)
        i += 1
```

With `selfRendered = true` from Phase 1, this renders without a theme border
interfering.

### 2.2 Select Text: Fix PickerW Content Rect Usage

The select text disappears at full width. The analysis identified this as an
interaction between theme border/padding insets and PickerW's text positioning.
Phase 1 gives correct content rect dimensions, which may resolve this.

**After Phase 1, verify with a targeted test**: render a standalone select at
30 cols. If text is still missing, the root cause is in the clip region —
`Container.render` pushes a clip for overflow:hidden/scroll, and if the
select's parent or the select itself triggers clipping, `drawString`'s
`screen.set` calls get silently dropped.

**Concrete investigation path if Phase 1 doesn't fix it:**

1. In `PickerW.render`, add a temporary debug: print `cx, cy, cw, ch` and
   the clip region bounds (`screen.clipX0, clipX1`) to stderr
2. Check if `cx` falls outside `clipX0..clipX1` — if so, the clip is wrong
3. Trace back to find which `pushClip` call is setting the wrong bounds
4. Fix the clip bounds or the coordinate computation that feeds into drawString

PickerW.render does:
```scala
ctx.canvas.drawString(cx, cy, math.max(0, cw - 2), displayText, 0, style)
```

The `cw - 2` reserves space for ` ▼`. With `cw` from a correct content rect
(after border/padding subtraction), `cx` should be inside the clip. If it's
not, the issue is in how paintElement computes the content rect for selects
at exactly container width.

### 2.3 Verification

- Range inputs show `████░░░░` track at all percentages (0%, 50%, 100%)
- Select option text visible at all widths

---

## Phase 3: Input Model — Centralized Character Resolution

**Fixes**: B7 (space dropped in text input)

**Root cause**: `Keyboard.Space` has dual semantics (action key for buttons,
printable char for text inputs) but each widget independently decides which
role it plays. TextInputW forgot to handle Space.

### 3.1 Centralized charValue Resolution

Instead of fixing TextInputW alone (which leaves the same bug latent in
EditablePicker, and any future text widget), resolve at the dispatch level.

In `WidgetRegistry`, add a method that translates key events for text widgets:

The simplest approach: in the Widget trait's default `handleKey`, use `charValue`
for character insertion. But widgets have different key handling, so instead
normalize at the call site.

In EventDispatch (or wherever handleKey is called), before dispatching to the
widget:

```scala
// Normalize Space to Char(' ') for text-input widgets
val normalizedEvent =
    if event.key == UI.Keyboard.Space then
        val widget = WidgetRegistry.lookup(elem)
        if widget != null && widget.acceptsTextInput then
            InputEvent.Key(UI.Keyboard.Char(' '), event.modifiers)
        else event
    else event
```

Add `acceptsTextInput: Boolean = false` to the Widget trait. Set to `true`
for TextInputWidget, TextareaWidget, EditablePickerWidget.

This way:
- Buttons/anchors/checkboxes still see `Keyboard.Space` (action semantics)
- Text widgets see `Keyboard.Char(' ')` (character semantics)
- No widget needs to handle the ambiguity
- Future text widgets automatically get correct behavior

### 3.2 Where to Place the Normalization

**Option A — EventDispatch.dispatch**: Before calling `widget.handleKey`.
This is the narrowest scope — only affects key dispatch, not other code.

**Option B — WidgetRegistry wrapper**: `WidgetRegistry.dispatchKey(elem, event, ctx)`
that normalizes then delegates. Keeps normalization co-located with widget lookup.

Prefer Option A — it's where all key routing already happens.

### 3.3 Verification

- typeText("hello world") produces "hello world" in text inputs (not "helloworld")
- Space still activates buttons, toggles checkboxes, triggers anchors
- EditablePicker (date/time/color) also handles space correctly

---

## Phase 4: Placeholder + Overflow Scroll + displayNone

**Fixes**: B6 (placeholder), N3 (overflow scroll), N4 (displayNone gap)

Three independent fixes that each touch a different part of the pipeline.

### 4.1 Placeholder: Show When Focused + Empty

Current condition in TextInputW.renderSingleLine (line ~50):

```scala
if text.isEmpty && placeholder.nonEmpty && cursor < 0
```

Change to:

```scala
if text.isEmpty && placeholder.nonEmpty
```

Always show placeholder when text is empty, regardless of focus state.
Draw with dim style to distinguish from real input. This matches modern
browser behavior (placeholder visible until user types, even when focused).

Apply the same change in `renderTextarea` (line ~102).

### 4.2 Overflow Scroll: Apply scrollY to Child Positioning

Current state: `FocusRing` stores `scrollY` per element, `Container.render`
pushes a clip region for overflow:scroll, and `ScrollW.paintIndicator` draws
arrows — but **children are never repositioned** by the scroll offset.

The fix has two parts:

**Part A — Pass scroll offset to FlexLayout.arrange:**

In `Container.render`, after computing clip:

```scala
val scrollY = if rs.overflow == 2 then ctx.focus.scrollY(elem) else 0

FlexLayout.arrange(children, cx, cy - scrollY, cw, ch, rs, ctx) { (i, rx, ry, rw, rh) =>
    R.render(children(i), rx, ry, rw, rh, ctx)
}
```

The `cy - scrollY` shifts all children upward by the scroll amount. Combined
with the clip region `(cx, cy, cx+cw, cy+ch)`, children above the viewport
are clipped away. Children below the viewport are also clipped.

**Part B — Compute max scroll from content height:**

ScrollW.paintIndicator needs to know the total content height to draw arrows
correctly and to clamp scrollY. FlexLayout already computes total measured
height during arrange — expose this.

Add a return value from `FlexLayout.arrange` that returns total content height,
or compute it in Container.render by summing measured heights.

Then in ScrollW:

```scala
val totalContentH = ... // from FlexLayout measurement
val maxScroll = math.max(0, totalContentH - ch)
val clampedScrollY = math.min(scrollY, maxScroll)
// Draw ▲ if clampedScrollY > 0
// Draw ▼ if clampedScrollY < maxScroll
```

**Part C — Key-based scrolling:**

Currently only mouse wheel updates scrollY. Add ArrowUp/ArrowDown handling
for scrollable containers (non-input elements with overflow:scroll):

In EventDispatch, after widget handleKey returns false for a scrollable element:

```scala
if !handled && rs.overflow == 2 then
    event.key match
        case UI.Keyboard.ArrowDown =>
            ctx.focus.setScrollY(elem, ctx.focus.scrollY(elem) + 1)
            true
        case UI.Keyboard.ArrowUp =>
            val sy = ctx.focus.scrollY(elem)
            if sy > 0 then ctx.focus.setScrollY(elem, sy - 1)
            true
        case _ => false
```

### 4.3 displayNone: Skip in Measurement

Current behavior: `display:none` elements are skipped in render (via
`rs.hidden` check in `renderElement`) but are still measured by FlexLayout
because measurement happens before render resolves styles.

The fix: in FlexLayout.measureW and measureH, resolve the element's hidden
state and return 0 for hidden elements:

```scala
case elem: UI.Element =>
    val rs = ctx.measureRs
    rs.inherit(ctx)
    rs.applyProps(ctx.theme.styleFor(elem))
    resolveStyleInto(elem, rs, ctx)
    if rs.hidden || ValueResolver.resolveBoolean(elem.attrs.hidden, ctx.signals) then 0
    else
        // ... existing measurement logic
```

This already happens partially (resolveStyleInto applies user styles which may
set hidden). The missing piece is checking `elem.attrs.hidden` during measurement
too, and returning 0 width/height so hidden elements don't consume layout space.

Also in `FlexLayout.arrange`, skip hidden children when counting for gap
distribution and justify spacing.

### 4.4 Verification

- Placeholder "Enter name" visible in empty focused input (B6)
- overflow:scroll container shows 3 lines, scrolls to reveal others (N3)
- Scroll indicators ▲/▼ visible at edges
- displayNone element leaves no gap (N4)

---

## Execution Order

| Phase | Changes | Files Modified | Bugs Fixed |
|-------|---------|---------------|------------|
| 1 | Widget trait + registry + selfRendered | New: Widget.scala, WidgetRegistry.scala. Modified: FlexLayout.scala, Render.scala, ResolvedTheme.scala. Deleted: WidgetDispatch.scala | N2, B8, N1 |
| 2 | RangeW track chars + PickerW text fix | RangeW.scala, PickerW.scala | B22, B20 |
| 3 | Centralized Space→Char normalization | EventDispatch.scala, Widget.scala | B7 |
| 4 | Placeholder + scroll + displayNone | TextInputW.scala, Container.scala, FlexLayout.scala, ScrollW.scala | B6, N3, N4 |

Each phase is independently testable. Run `TerminalEmulatorTest` (10 tests)
after each phase for regression. Run full QA after Phase 4.

---

## What This Plan Does NOT Cover

- **B3 (bold font rendering)**: JediTerm font limitation, not a code bug
- **B21 (select text truncation)**: Likely fixed by Phase 1 proper content rect;
  verify during QA and add a targeted fix if needed
- **Cursor visibility in text output**: The QA text frames don't show cursor
  position — this is a limitation of the text-mode frame dump, not a rendering bug.
  Cursor is rendered via terminal escape sequences in the real TUI.
