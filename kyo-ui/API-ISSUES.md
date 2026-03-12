# UI + Style API Issues & Improvements

Adversarial review of the full API surface against the design goal: **a safe, fluent API that rules out invalid states at the type level**.

Each issue is assessed through one lens: can the user represent an invalid state? If the type system allows a value that has no valid meaning, that's a gap.

---

## Category 1: Type Safety Gaps — Invalid States Representable

### 1.1 `Style.++` Does NOT Deduplicate — Breaks `find` and Pseudo-States

**Severity: High** — silent data corruption in a common operation.

`appendProp` filters out same-class props before appending (last wins). But `++` just concatenates:

```scala
// Chaining dedup — correct, only blue remains:
Style.bg(_.red).bg(_.blue)

// ++ NO dedup — BOTH BgColor props in the list:
Style.bg(_.red) ++ Style.bg(_.blue)
```

**Consequences:**
- `Style.find[A]` returns the **first** match (Style.scala:41-46), so `find` on `++`-composed styles returns the **wrong** (oldest) value
- Pseudo-states are broken: `style1.hover(_.bg(_.red)) ++ style2.hover(_.bg(_.blue))` → `find[HoverProp]` extracts only the red hover, blue is silently lost. DomStyleSheet (DomStyleSheet.scala:50-53) uses `find` to extract pseudo-states for CSS rule generation
- Unbounded growth: repeatedly `++`-composing accumulates duplicate props forever, which matters in reactive UIs where styles recompose on signal changes
- TuiStyle iterates all props and last assignment wins (correct by accident)
- CssStyleRenderer emits both declarations (last wins in browser, correct by accident)

**Root cause:** `++` on Style.scala:25 bypasses `appendProp`'s dedup:
```scala
def ++(other: Style): Style = Style(props ++ other.props)  // no dedup
```

### 1.2 `Color` Enum Constructors Bypass Validation

**Severity: High** — any string/int passes through to CSS rendering unchecked.

The validated factories (`Color.hex`, `Color.rgb`, `Color.rgba`) clamp values and validate format. But the enum case constructors are public:

```scala
Color.Hex("garbage")        // compiles — no '#', no validation
Color.Hex("")               // compiles — empty string
Color.Rgb(999, -1, 500)     // compiles — bypasses clamp255
Color.Rgba(0, 0, 0, 99.0)  // compiles — bypasses clamp01
```

CssStyleRenderer (CssStyleRenderer.scala:13-16) emits these unchecked:
```scala
case Color.Hex(value) => value     // outputs "garbage" as CSS
case Color.Rgb(r, g, b) => s"rgb($r, $g, $b)"  // outputs "rgb(999, -1, 500)"
```

`Color.transparent` (Style.scala:455) is a specific instance of this: `Hex("transparent")` bypasses the `Color.hex()` factory which would reject it (no '#' prefix). It happens to work in CSS but violates the `Hex` type's implied invariant.

The same applies to `Color.Rgb(r,g,b)` and `Color.Rgba(r,g,b,a)` — direct construction bypasses `clamp255`/`clamp01`.

### 1.3 `clampSize` Only Clamps `Px` — `Pct`/`Em` Unchecked

**Severity: Medium** — negative values pass through for properties that reject them.

```scala
private inline def clampSize(s: Size): Size = s match
    case Size.Px(v) => if v < 0 then Size.Px(0) else s
    case _          => s  // Pct and Em pass through unchecked
```

`padding((-10).pct)` produces CSS `padding: -10%` — invalid per CSS spec (padding must be non-negative). `margin((-10).pct)` is valid (negative margins are legal). But `clampSize` is used for both padding and gap, which should both reject negatives for ALL size types.

Affected methods that should reject negative Pct/Em but don't: `padding`, `gap`, `fontSize` (via `clampSizeMin1` which also only checks Px), `borderWidth`, `rounded`, `shadow` blur/spread, `blur`, `width`/`height` (debatable — CSS `width: -10%` is invalid).

### 1.4 `Keyboard.Char` Takes `String` Instead of `Char`

**Severity: Medium** — permits invalid multi-character "Char" keys.

```scala
case Char(c: String)  // accepts ANY string, including "", "abc", emoji sequences
```

`fromString` (UI.scala:57) only creates `Char` for `s.length == 1`, but the enum case constructor is public — `Keyboard.Char("multiple")` or `Keyboard.Char("")` compile and represent invalid states. The `charValue` method would return `Present("multiple")`.

### 1.5 `RangeInput.min/max/step` Are `String` Instead of Numeric

**Severity: Medium** — no compile-time validation of numeric constraints.

```scala
min: Maybe[String] = Absent,   // rangeInput.min("hello") compiles
max: Maybe[String] = Absent,
step: Maybe[String] = Absent,
```

These values are always numeric in HTML (`<input type="range" min="0" max="100" step="5">`). Using `String` permits any value. Should be `Double` (or a typed numeric type).

### 1.6 `NumberInput`/`RangeInput` `onChange` Returns `String` Not `Double`

**Severity: Medium** — forces runtime parsing on every use site.

Both `NumberInput` and `RangeInput` have `onChange: Maybe[String => Unit < Async]`. The user always receives a string they must parse to a number. A type-safe API would provide `Double => Unit < Async` since the value is always numeric for these input types.

`NumberInput` inherits this from `TextInput` (which is correct for text inputs). The issue is that `NumberInput` extends `TextInput` when it should arguably have its own `NumericInput` trait.

### 1.7 `img(src, alt)` Factory Circumventable via `copy`

**Severity: Low** — requires intentional action.

```scala
def img(src: String, alt: String): Img  // factory enforces both args — good
```

But since `Img` is a case class exposed via type alias, `copy` is available:
```scala
UI.img("pic.jpg", "A picture").copy(alt = Absent)  // removes alt — bad
```

The `src()` and `alt()` setter methods on `Img` are actually safe — they only override one field, keeping the other. The risk is exclusively via `copy`. Low severity since it requires intentional circumvention.

### 1.8 `Label.forId` / `Element.id` Are Stringly-Typed

**Severity: Low** — no compile-time link between label and target element.

```scala
val myInput = UI.input.id("inp1")
val myLabel = UI.label.forId("inp2")  // typo — compiles fine, broken at runtime
```

Inherent to string-based ID systems. Worth noting as a future improvement target but difficult to solve without fundamentally changing the element model.

---

## Category 2: Missing API Surface

These are not type safety gaps (they don't allow invalid states) but represent common HTML capabilities not yet exposed. Included because adding them later with the RIGHT types matters for safety.

### 2.1 `NumberInput` Missing `min`, `max`, `step`

**Severity: High** — `<input type="number">` needs these in virtually all real use cases.

`RangeInput` has all three, but `NumberInput` has none. When adding these, they should be `Double` not `String` (see 1.5).

### 2.2 `Select` Missing `value` Field

**Severity: High** — no way to programmatically control which option is selected.

`Select` extends `Focusable + HasDisabled` but NOT `PickerInput`. It has `onChange` but no `value`. `DateInput`, `TimeInput`, `ColorInput` all correctly have `value` via `PickerInput`.

### 2.3 `Opt` (Option) Missing `value` Attribute

**Severity: High** — no way to set the form-submission value of an `<option>`.

```scala
final case class Opt(
    attrs: Attrs = Attrs(),
    children: kyo.Span[UI] = kyo.Span.empty,
    selected: Maybe[Boolean | Signal[Boolean]] = Absent
) // NO value field — display text becomes data payload
```

Without `value`, the option's text content IS the submitted value. This couples display to data — e.g., can't have `<option value="US">United States</option>`.

### 2.4 `FileInput` Missing `accept`

**Severity: Medium** — can't restrict file types (`accept="image/*,.pdf"`).

### 2.5 No `readonly` on TextInput Elements

**Severity: Medium** — can't make inputs display-only but selectable/copyable.

`disabled` prevents all interaction. `readonly` allows selection/copying but not editing. Distinct and common HTML attribute.

### 2.6 No `rows`/`cols` on `Textarea`

**Severity: Low** — workaround via `style(_.width(...).height(...))`.

### 2.7 No Accessibility Attributes (`aria-*`)

**Severity: Low (for v1)** — `aria-label`, `aria-hidden`, `role` are critical for accessibility. Large surface area that may warrant a dedicated subsystem.

---

## Category 3: Structural / Consistency Issues

### 3.1 Block/Inline Trait Determines Default Flex Direction — DomStyleSheet Is Inconsistent

**Severity: Medium**

In kyo-ui's layout model, Block/Inline controls default flex direction:
- **Block** → `flex-direction: column` (vertical stacking)
- **Inline** → `flex-direction: row` (horizontal layout)

The TUI backend derives this from the trait (TuiFlatten.scala:116-117):
```scala
if elem.isInstanceOf[UI.Inline] then
    layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.DirBit)
```

But the DomStyleSheet (DomStyleSheet.scala:22-23) **hardcodes element names** instead of deriving from traits:
```css
div, section, main, header, footer, form, article, aside, p, ul, ol { flex-direction: column; }
nav, li, span { flex-direction: row; }
```

**Inconsistencies:**
- `code`, `pre`, `h1-h6`, `table`, `tr`, `td`, `th`, `label` are absent from the DomStyleSheet entirely — they get no flex default in the browser but DO get one in TUI
- The two backends could diverge in layout behavior for these elements
- Adding a new element requires updating BOTH the trait hierarchy AND the hardcoded CSS — error-prone

**Note:** The Block/Inline assignments are intentional layout design choices (not HTML spec compliance), so e.g. `Li` as Inline (row) is a deliberate choice for the common icon+text list item pattern. The issue is the DomStyleSheet not deriving from traits, not the trait assignments themselves.

### 3.2 `Style.hidden` vs `Element.hidden()` — Dual Mechanism

**Severity: Medium** — two independent hide mechanisms with different capabilities.

- `element.hidden(v: Boolean | Signal[Boolean])` → sets `attrs.hidden` → HTML `hidden` attribute
- `element.style(_.hidden)` → adds `HiddenProp` → CSS `display: none`

Both hide the element but via different mechanisms. Only `Element.hidden` supports `Signal[Boolean]` for reactive toggling. Combining both on one element creates ambiguous intent — `element.hidden(false).style(_.hidden)` means "not hidden by HTML but hidden by CSS."

### 3.3 `Attrs` Carries Handler Fields for All Elements

**Severity: Low** — structural waste, not a safety issue.

`Attrs` has `onClick`, `onClickSelf`, `onKeyDown`, `onKeyUp`, `onFocus`, `onBlur`, `tabIndex` fields present on every element (including `Hr`, `Br`, `Img`). These fields are unreachable through the public API on non-Interactive elements, so no safety gap exists — just wasted memory per instance.

### 3.4 `Img` Not Interactive

**Severity: Low** — design choice, not a safety gap.

Clickable images are a common UI pattern, but the standard HTML approach is wrapping in `<a>` or `<button>`. Making `Img` Interactive would add `onClick`/`onKeyDown`/`tabIndex`/etc — all of which ARE valid HTML attributes on `<img>`. Worth considering but not strictly needed since `a.onClick(action)(img("src", "alt"))` works.

---

## Category 4: Boilerplate / DRY Violations

### 4.1 21 Element Case Classes Are Near-Identical

**Severity: Medium** — maintainability concern.

16 elements share exact same structure: `attrs + children + withAttrs + apply(UI*)`. 4 more differ only in typed children constraints. ~200 lines of copy-paste. This also means adding a new method to all container elements (e.g., `pointerPassthrough` from the implementation plan) requires editing 20+ case classes.

### 4.2 7 TextInput Elements Are Identical

**Severity: Medium**

Input, Password, Email, Tel, UrlInput, Search, NumberInput — same fields, same methods, same traits. ~120 lines of pure duplication.

### 4.3 Style Companion Mirrors Every Instance Method

**Severity: Low** — ~130 lines of delegation. Intentional for ergonomics (`Style.bg(_.red)` instead of `Style.empty.bg(_.red)`).

---

## Category 5: Ergonomic Issues

### 5.1 No Way to Clear/Reset a Style Property

**Severity: Medium** — once set, a prop can only be overridden, not removed.

```scala
val base = Style.bg(_.red).padding(10.px)
// How to create a variant WITHOUT padding?
// base.filter(!_.isInstanceOf[Padding]) works but exposes Prop internals
```

`Style.filter` exists but takes `Prop => Boolean`, requiring knowledge of internal prop types.

### 5.2 `Cursor.default_` and `Cursor.wait_` Naming

**Severity: Low** — trailing underscores needed for Scala keyword conflicts. Alternatives: `Cursor.auto`/`Cursor.loading`.

---

## Summary: Priority by Safety Impact

Issues that allow invalid states to be represented statically:

| # | Issue | Invalid State Example | Severity |
|---|-------|-----------------------|----------|
| 1.1 | `++` doesn't dedup | Duplicate props, find returns wrong value, pseudo-states silently lost | **High** |
| 1.2 | Color constructors bypass validation | `Color.Hex("garbage")`, `Color.Rgb(999,-1,500)` | **High** |
| 1.3 | clampSize only checks Px | `padding((-10).pct)` = invalid CSS | **Medium** |
| 1.4 | Keyboard.Char takes String | `Keyboard.Char("")`, `Keyboard.Char("abc")` | **Medium** |
| 1.5 | min/max/step are String | `rangeInput.min("hello")` | **Medium** |
| 1.6 | Numeric inputs return String | `numberInput.onChange(s => /* must parse */)` | **Medium** |
| 1.7 | img factory copy bypass | `img.copy(alt = Absent)` | **Low** |
| 1.8 | forId stringly-typed | `label.forId("typo")` | **Low** |

Missing API (type decisions matter when adding):

| # | Issue | Severity |
|---|-------|----------|
| 2.1 | NumberInput missing min/max/step | **High** |
| 2.2 | Select missing value | **High** |
| 2.3 | Opt missing value | **High** |
| 2.4 | FileInput missing accept | **Medium** |
| 2.5 | No readonly on TextInput | **Medium** |
| 2.6 | No rows/cols on Textarea | **Low** |
| 2.7 | No aria-* attributes | **Low** |

Structural / Ergonomic:

| # | Issue | Severity |
|---|-------|----------|
| 3.1 | DomStyleSheet inconsistent with Block/Inline traits | **Medium** |
| 3.2 | Style.hidden vs Element.hidden dual mechanism | **Medium** |
| 4.1 | 21 boilerplate element classes | **Medium** |
| 4.2 | 7 identical TextInput classes | **Medium** |
| 5.1 | No way to reset/remove style property | **Medium** |
| 3.3 | Attrs waste on non-Interactive elements | **Low** |
| 3.4 | Img not Interactive | **Low** |
| 4.3 | Style companion mirrors all methods | **Low** |
| 5.2 | Cursor naming | **Low** |
