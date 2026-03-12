# Remaining Issues: Architectural Analysis

Post-architecture-improvements QA found 5 pre-existing bugs and 4 new issues.
This document analyzes them not as isolated bugs but as symptoms of **4 architectural
root causes** in the tui2 rendering pipeline.

---

## Root Cause 1: No Widget Trait — Leaf Widgets Are Invisible to the Layout Engine

**Symptoms**: N2 (input content height=0), B8 (tiny input box), B22 (range no track), B20 (select text invisible at full width)

### The pattern

The rendering pipeline has two completely separate paths that must agree about
the same elements:

1. **Measurement** (FlexLayout) — decides how much space an element occupies
2. **Rendering** (WidgetDispatch → XxxW.render) — draws content into that space

For "container" elements (div, span, section, etc.) these naturally agree because
FlexLayout measures children and the render path draws children — it's the same
data. But **leaf widgets** (TextInput, RangeInput, Select, Checkbox, etc.)
draw content that doesn't come from children. They draw glyphs, text values,
tracks, and indicators directly via Canvas calls.

The result: **FlexLayout sees an empty element** (no children) and assigns it
`math.max(1, insetH)` — which is just border + padding. The widget's actual
content (a line of text, a track bar, a glyph) is invisible to measurement.

### Why WidgetDispatch.intrinsicHeight is insufficient

Phase 1 added intrinsic sizing for BooleanInput, Hr, RangeInput, and Select.
But it's a piecemeal patch on a structural problem:

- **Every new widget** must remember to add cases in 3 places:
  `intrinsicWidth`, `intrinsicHeight`, `extraWidth`
- **Forgetting a case** produces silent bugs (element renders with wrong size)
- TextInput, PickerInput (non-select), FileInput, Textarea were all missed
- The compiler gives no warning — the catch-all `case _ => -1` swallows everything

### What's needed architecturally

A **Widget trait** with `measureWidth`/`measureHeight`/`render` as a single unit.
Each widget owns its own measurement, so measurement and rendering can't diverge.
The match in WidgetDispatch becomes:

```
widget.measureW(availW, ctx) // widget knows its own content size
widget.measureH(availW, ctx)
widget.render(cx, cy, cw, ch, rs, ctx)
```

This eliminates the 3 separate match cascades and makes forgetting a widget a
compile error (abstract methods force implementation).

---

## Root Cause 2: Dual Rendering Authority — Theme vs Widget Overlap

**Symptoms**: N1 (HR double-height), B22 (range rendering), B20 (select at full width)

### The pattern

The rendering pipeline applies styles in this order:

1. Theme provides default `Style` properties (border, padding, colors)
2. `paintElement` renders border/background from resolved style
3. `WidgetDispatch.render` dispatches to widget-specific rendering

For most elements this works — theme provides decoration, widget provides content.
But for some elements, **both paths try to render the same thing**:

- **HR**: Theme applies `borderBottom(1.px, "#666")` → draws `─` line.
  Then `HrW.render` draws another `─` line. Result: 2 visible lines.
- **RangeInput**: Theme applies input border → draws bordered box.
  Then `RangeW.render` tries to fill inside with `fillBg` only (no characters).
  Result: empty bordered box — the theme's border is the only visible output.
- **Select**: Theme applies input border with padding.
  Then `PickerW.render` draws text + `▼` inside the content rect.
  At full width, the content rect coordinates interact with border insets,
  and text disappears (but `▼` at `cx + cw - 1` still renders).

### Why this happens

There's no contract defining **who is responsible for what** per element type:

- Is border a "theme concern" or a "widget concern"?
- Is HR a "bordered element" or a "self-drawing widget"?
- Does a range input own its entire visual, or just the content area?

The theme applies the same `inputStyle` to TextInput, Select, RangeInput,
PickerInput, FileInput — but each of these has radically different visual needs.
RangeInput doesn't need a border at all (it IS the visual). HR doesn't need
borderBottom because HrW draws the line. But the theme can't know this.

### What's needed architecturally

Widgets should declare their **rendering mode**:

- `Decorated`: theme provides border/padding, widget renders in content rect
  (TextInput, Select, Button)
- `SelfRendered`: widget controls the entire visual, theme provides colors only
  (HR, RangeInput, BooleanInput)

This prevents the theme from adding borders/padding to widgets that draw their
own chrome, and prevents widgets from redrawing what the theme already painted.

---

## Root Cause 3: Input Model Conflates "Special Key" and "Printable Character"

**Symptoms**: B7 (typeText drops spaces)

### The pattern

The Keyboard enum separates `Space` from `Char(c)`:

```scala
enum Keyboard:
    case Enter, Tab, Escape, Backspace, Delete, Space
    case Char(c: scala.Char)
```

TuiInput.parse converts byte `0x20` to `Keyboard.Space` (not `Char(' ')`).
This is intentional — Space doubles as an activation key for buttons/anchors
(like Enter). But TextInputW.handleKey only matches `Char(c)`, not `Space`.

The Keyboard enum already has a `charValue` helper that maps `Space → " "`,
proving the design intended Space to be usable as a character. But nothing
enforces that text-input widgets use `charValue` instead of matching only `Char`.

### Why this is structural, not just a missing case

The real issue is that `Keyboard.Space` has **dual semantics**:

- In interactive contexts (button, anchor, checkbox): action trigger
- In text contexts (input, textarea): printable character

But the dispatch model doesn't know which context applies. WidgetDispatch
routes all key events through a single `handleKey` per widget. Each widget
must independently decide "is Space a character or an action?" This is error-prone
and has already caused exactly the bug we see.

### What's needed architecturally

Two options:

**Option A — Context-aware input**: The dispatch layer translates `Space` into
`Char(' ')` before sending to text-input widgets, so widgets don't need to
handle the ambiguity. WidgetDispatch already knows the element type.

**Option B — Use charValue consistently**: TextInputW should use
`event.key.charValue` to extract printable characters instead of matching
`Char(c)` directly. This handles Space (and any future printable keys)
automatically.

Option A is cleaner because it centralizes the decision. Option B is simpler
to implement but pushes responsibility to each widget.

---

## Root Cause 4: Placeholder Rendering Guarded by Focus State

**Symptoms**: B6 (placeholder text not rendering)

### The pattern

TextInputW.render has the placeholder condition:

```scala
if text.isEmpty && placeholder.nonEmpty && cursor < 0
```

Where `cursor < 0` means "not focused". But FocusRing auto-focuses the first
focusable element on every frame. So the first input always has `cursor >= 0`,
and its placeholder never renders.

Even for non-first inputs, the condition means: **placeholder is only visible
when the input is unfocused AND empty**. This is correct CSS behavior. But
because inputs start with `cursor = -1` only if they're not the first focusable,
and FocusRing.scan() runs before render, the first input gets auto-focused
before it has a chance to show its placeholder.

### Why this is structural

The issue isn't the condition itself — it's that **FocusRing.scan() and
rendering are too tightly coupled**. The focus state is set before the first
render, so the first input never renders in an "unfocused" state.

In CSS/HTML, an auto-focused input still shows its placeholder until the user
types. The placeholder disappears on input, not on focus. The TUI renderer
conflates "has cursor" with "is focused" with "hide placeholder".

### What's needed

Separate the concept of "has keyboard focus" (for border styling) from "has
active cursor" (for placeholder visibility). Or: show placeholder even when
focused, as long as text is empty — with dim styling to distinguish it from
real input. This matches modern browser behavior.

---

## Summary: Recurring Architectural Themes

| Theme | Root Cause | Bugs |
|-------|-----------|------|
| **Measurement/rendering divergence** | No Widget trait; leaf widgets invisible to layout | N2, B8, B22, B20 |
| **Dual rendering authority** | Theme and widget both draw; no contract for who does what | N1, B22, B20 |
| **Input semantics ambiguity** | Space is both action key and printable char; no centralized resolution | B7 |
| **State/rendering coupling** | Focus auto-set before first render; placeholder logic depends on focus state | B6 |
| **Missing overflow implementation** | Scroll state stored but never applied to child positioning | N3 |
| **displayNone gap** | Hidden elements removed from render but not from layout measurement | N4 |

### Priority Order for Fixes

1. **N2 + B8** (input content height) — Highest impact, most tests affected.
   Quick fix: add intrinsicHeight=1 for TextInput, PickerInput, FileInput, Textarea.
   Proper fix: Widget trait.

2. **B7** (space handling) — High impact, every text test with spaces.
   Quick fix: add `case Keyboard.Space` in TextInputW.handleKey.
   Proper fix: use `charValue` or context-aware dispatch.

3. **N1** (HR double-height) — Medium impact, visible in 2 tests.
   Quick fix: remove borderBottom from hrStyle in theme.
   Proper fix: widget rendering mode contract.

4. **B6** (placeholder) — Medium impact, 5 tests.
   Quick fix: change condition to show placeholder when focused + empty.

5. **B20** (select text at full width) — Medium impact, 4 tests.
   Needs investigation of PickerW text positioning vs content rect calculation.

6. **B22** (range track) — Medium impact, 5 tests.
   RangeW.render needs character content (not just fillBg).
   Theme should not apply inputStyle to RangeInput.

7. **N3** (overflow scroll) — Low impact, 1 test.
   Requires applying scrollY offset to child positioning in FlexLayout.

8. **N4** (displayNone gap) — Low impact, 1 test.
   FlexLayout should skip hidden elements during measurement.
