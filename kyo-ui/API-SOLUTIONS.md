# API Issues — Proposed Solutions

Concrete solutions for each issue in API-ISSUES.md. Organized by implementation order: safety fixes first, then missing API, then structural/ergonomic.

---

## Phase A: Type Safety Fixes

### A1. Fix `Style.++` to Deduplicate (Issue 1.1)

**Change:** One-line fix in Style.scala:25.

```scala
// Before:
def ++(other: Style): Style = Style(props ++ other.props)

// After:
def ++(other: Style): Style =
    if other.isEmpty then this
    else if isEmpty then other
    else
        var result = props
        other.props.foreach { p =>
            val cls = p.getClass
            result = result.filter(x => !(x.getClass eq cls)) :+ p
        }
        Style(result)
```

**Semantics:** Right-hand side wins on conflict, matching CSS cascade order. Non-conflicting props from both sides are preserved. This is equivalent to `other.props.foldLeft(this)((s, p) => s.appendProp(p))` but avoids creating intermediate Style instances.

**Pseudo-state merging:** `style1.hover(_.bg(_.red)) ++ style2.hover(_.bg(_.blue))` → only `HoverProp(blue)` remains (full replacement). This matches chaining: `style.hover(a).hover(b)` → only b. To merge inner hover styles, users compose them explicitly: `Style.hover(hoverBase ++ hoverOverride)`.

**Tests to add:** `"++ dedup same prop type"`, `"++ dedup pseudo-states"`, `"++ preserves non-conflicting props"`.

---

### A2. Restrict Color Enum Constructors (Issue 1.2)

**Approach A (preferred if it compiles):** Add `private[kyo]` to each enum case constructor in Style.scala:423-427.

```scala
enum Color derives CanEqual:
    case Hex private[kyo] (value: String)
    case Rgb private[kyo] (r: Int, g: Int, b: Int)
    case Rgba private[kyo] (r: Int, g: Int, b: Int, a: Double)
```

**Approach B (fallback):** Convert from enum to sealed abstract class with private constructors.

```scala
sealed abstract class Color derives CanEqual
object Color:
    final case class Hex private[kyo] (value: String) extends Color
    final case class Rgb private[kyo] (r: Int, g: Int, b: Int) extends Color
    final case class Rgba private[kyo] (r: Int, g: Int, b: Int, a: Double) extends Color
    // ... factories and constants unchanged ...
```

**Note:** The kyo codebase uses `private[kyo]` on regular case classes (`PanicException`, `Stopwatch`, `Deadline`) but has NO existing example of `private[scope]` on enum case constructors specifically. The syntax for Approach A needs compile-time verification. Approach B is guaranteed to work.

**Effect (both approaches):**
- `Color.Hex("garbage")` no longer compiles outside `kyo` package
- `Color.hex("garbage")` still compiles (returns `Hex("#000000")` via validated factory)
- Pattern matching in CssStyleRenderer and TuiColor still works (they're in `kyo.internal` ⊂ `kyo`)
- Named constants like `Color.white`, `Color.transparent` still work (defined within `Color` companion ⊂ `kyo`)
- No code in the codebase calls `.copy()` on Color values — verified by search
- All existing user code uses factories — no breaking change

---

### A3. Fix `clampSize` to Handle All Size Types (Issue 1.3)

**Change:** Extend `clampSize` and `clampSizeMin1` in Style.scala:15-21.

```scala
// Before:
private inline def clampSize(s: Size): Size = s match
    case Size.Px(v) => if v < 0 then Size.Px(0) else s
    case _          => s

private inline def clampSizeMin1(s: Size): Size = s match
    case Size.Px(v) => if v < 1 then Size.Px(1) else s
    case _          => s

// After:
private inline def clampSize(s: Size): Size = s match
    case Size.Px(v)  => if v < 0 then Size.Px(0) else s
    case Size.Pct(v) => if v < 0 then Size.Pct(0) else s
    case Size.Em(v)  => if v < 0 then Size.Em(0) else s
    case Size.Auto   => s

private inline def clampSizeMin1(s: Size): Size = s match
    case Size.Px(v) => if v < 1 then Size.Px(1) else s
    case Size.Em(v) => if v < 0.1 then Size.Em(0.1) else s
    case _          => s
```

**Methods affected (all already call clampSize):** `padding`, `gap`, `width`, `height`, `minWidth`, `maxWidth`, `minHeight`, `maxHeight`, `borderWidth`, `rounded`, `blur` (filter), `fontSize` (via clampSizeMin1).

**Note:** `shadow` does NOT call `clampSize` — its x/y offsets correctly allow negative values, and blur/spread are passed through unchecked (existing behavior preserved).

**Methods correctly NOT calling clampSize:** `margin` (negative margins are valid CSS), `translate` (negative offsets are valid), `letterSpacing` (negative letter-spacing is valid CSS).

**Tests to add:** `"padding rejects negative pct"`, `"padding rejects negative em"`, `"fontSize min 0.1 em"`.

---

### A4. Change `Keyboard.Char` to Use `scala.Char` (Issue 1.4)

**Change:** In UI.scala, change the Char case parameter type:

```scala
// Before:
case Char(c: String)

// After:
case Char(c: scala.Char)
```

**Update `fromString`** (UI.scala:57):
```scala
// Before:
case s if s.length == 1 => Char(s)

// After:
case s if s.length == 1 => Char(s.charAt(0))
```

**Update `charValue`** (UI.scala:23):
```scala
// Before:
case Char(c) => Present(c)
case Space   => Present(" ")

// After:
case Char(c) => Present(c.toString)
case Space   => Present(" ")
```

**Backend updates — TuiInput.scala** (4 construction sites):
```scala
// Before (line 94):
events.addOne(Key(K.Char(asciiStrings(next)), alt = true))
// After:
events.addOne(Key(K.Char(next.toChar), alt = true))

// Before (line 108):
events.addOne(Key(K.Char(asciiStrings(b + 'a' - 1)), ctrl = true))
// After:
events.addOne(Key(K.Char((b + 'a' - 1).toChar), ctrl = true))

// Before (line 114):
val k = if b == 0x20 then K.Space else K.Char(asciiStrings(b))
// After:
val k = if b == 0x20 then K.Space else K.Char(b.toChar)

// Before (line 336 — UTF-8 multi-byte):
events.addOne(Key(K.Char(new String(arr, "UTF-8"))))
// After:
val s = new String(arr, "UTF-8")
if s.length == 1 then events.addOne(Key(K.Char(s.charAt(0))))
else events.addOne(Key(K.Unknown(s)))
```

**Note on multi-codepoint characters:** Emoji like 😀 are surrogate pairs (2 Java chars). The current code already only creates `Char` for `s.length == 1` in `fromString`, so emoji already become `Unknown`. The UTF-8 multi-byte path (line 336) is the only place that could produce multi-char strings — the fix routes those to `Unknown`.

**Note on `asciiStrings` array:** The pre-allocated String array becomes unnecessary for Keyboard.Char but may still be used elsewhere. If it's only used for Char construction, it can be removed.

---

### A5. Change `RangeInput.min/max/step` to `Double` (Issue 1.5)

**Change:** In UI.scala, RangeInput case class:

```scala
// Before:
min: Maybe[String] = Absent,
max: Maybe[String] = Absent,
step: Maybe[String] = Absent,

// After:
min: Maybe[Double] = Absent,
max: Maybe[Double] = Absent,
step: Maybe[Double] = Absent,
```

**Setter methods:**
```scala
// Before:
def min(v: String): RangeInput  = copy(min = Present(v))
def max(v: String): RangeInput  = copy(max = Present(v))
def step(v: String): RangeInput = copy(step = Present(v))

// After:
def min(v: Double): RangeInput  = copy(min = Present(v))
def max(v: Double): RangeInput  = copy(max = Present(v))
def step(v: Double): RangeInput = copy(step = Present(if v <= 0 then 1.0 else v))
```

`step` is clamped: values ≤ 0 become 1.0 (the browser default). HTML spec requires step > 0. `min` and `max` are unclamped since any numeric range is valid.

**Backend update — DomBackend.scala:236-238:** Convert Double to String via `v.toString` in `setAttribute` calls.

---

## Phase B: Missing API Surface

### B1. Add `min`, `max`, `step` to NumberInput (Issue 2.1)

**Change:** In UI.scala, NumberInput case class:

```scala
// Before:
final case class NumberInput(
    attrs: Attrs = Attrs(),
    value: Maybe[String | SignalRef[String]] = Absent,
    placeholder: Maybe[String] = Absent,
    disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
    onInput: Maybe[String => Unit < Async] = Absent,
    onChange: Maybe[String => Unit < Async] = Absent
) extends Inline with Interactive with TextInput with Void:

// After:
final case class NumberInput(
    attrs: Attrs = Attrs(),
    value: Maybe[String | SignalRef[String]] = Absent,
    placeholder: Maybe[String] = Absent,
    min: Maybe[Double] = Absent,
    max: Maybe[Double] = Absent,
    step: Maybe[Double] = Absent,
    disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
    onInput: Maybe[String => Unit < Async] = Absent,
    onChange: Maybe[String => Unit < Async] = Absent
) extends Inline with Interactive with TextInput with Void:
    // ...existing methods...
    def min(v: Double): NumberInput  = copy(min = Present(v))
    def max(v: Double): NumberInput  = copy(max = Present(v))
    def step(v: Double): NumberInput = copy(step = Present(if v <= 0 then 1.0 else v))
```

**NumberInput keeps extending TextInput** — it still has placeholder, onInput (intermediate string values while typing), and String value/onChange. This matches HTML behavior where `<input type="number">` fires input events with string values during typing. The numeric typing issue (1.6) is noted below as a future improvement.

**Backend update — DomBackend.scala:** Add min/max/step handling in the NumberInput section (same pattern as RangeInput).

---

### B2. Add `value` to Select (Issue 2.2)

**Change:** In UI.scala, Select case class:

```scala
// Before:
final case class Select(
    attrs: Attrs = Attrs(),
    children: kyo.Span[UI] = kyo.Span.empty,
    disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
    onChange: Maybe[String => Unit < Async] = Absent
) extends Block with Interactive with Focusable with HasDisabled:

// After:
final case class Select(
    attrs: Attrs = Attrs(),
    children: kyo.Span[UI] = kyo.Span.empty,
    value: Maybe[String | SignalRef[String]] = Absent,
    disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
    onChange: Maybe[String => Unit < Async] = Absent
) extends Block with Interactive with PickerInput:
    // ...existing methods...
    def value(v: String | SignalRef[String]): Select = copy(value = Present(v))
```

**Trait change:** `Focusable with HasDisabled` → `PickerInput` (which already extends `Focusable with HasDisabled` and requires `value` + `onChange`).

**Backend update — DomBackend.scala:293-303:** Wire value binding using the existing `applyValueBinding` helper (same pattern as other input types).

---

### B3. Add `value` to Opt (Issue 2.3)

**Change:** In UI.scala, Opt case class:

```scala
// Before:
final case class Opt(
    attrs: Attrs = Attrs(),
    children: kyo.Span[UI] = kyo.Span.empty,
    selected: Maybe[Boolean | Signal[Boolean]] = Absent
) extends Block:

// After:
final case class Opt(
    attrs: Attrs = Attrs(),
    children: kyo.Span[UI] = kyo.Span.empty,
    value: Maybe[String] = Absent,
    selected: Maybe[Boolean | Signal[Boolean]] = Absent
) extends Block:
    // ...existing methods...
    def value(v: String): Opt = copy(value = Present(v))
```

**Value is `String`, not `String | SignalRef[String]`** — option values are always static in HTML. No reactive binding needed.

**Backend update — DomBackend.scala:339:**
```scala
case opt: UI.Opt =>
    for
        _ <- opt.value.fold(noop)(v => el.setAttribute("value", v); noop)
        _ <- opt.selected.fold(noop)(v => applyBoolProp(el, elem, "selected", v, rendered))
    yield ()
```

---

### B4. Add `accept` to FileInput (Issue 2.4)

**Change:** In UI.scala, FileInput case class:

```scala
// Before:
final case class FileInput(
    attrs: Attrs = Attrs(),
    disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
    onChange: Maybe[String => Unit < Async] = Absent
) extends Inline with Interactive with Focusable with HasDisabled with Void:

// After:
final case class FileInput(
    attrs: Attrs = Attrs(),
    accept: Maybe[String] = Absent,
    disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
    onChange: Maybe[String => Unit < Async] = Absent
) extends Inline with Interactive with Focusable with HasDisabled with Void:
    // ...existing methods...
    def accept(v: String): FileInput = copy(accept = Present(v))
```

**Accept value is `String`** — MIME types and file extensions (`"image/*"`, `".pdf,.doc"`) are inherently stringly-typed. No type-safe alternative without an enum that would be immediately incomplete.

**Backend update — DomBackend.scala:250-261:** Add `fi.accept.fold(noop)(v => el.setAttribute("accept", v); noop)`.

---

### B5. Add `readonly` to TextInput Elements (Issue 2.5)

**Change:** In UI.scala, TextInput trait and all implementations:

```scala
// TextInput trait:
sealed trait TextInput extends Focusable with HasDisabled:
    def value: Maybe[String | SignalRef[String]]
    def placeholder: Maybe[String]
    def readOnly: Maybe[Boolean]
    def onInput: Maybe[String => Unit < Async]
    def onChange: Maybe[String => Unit < Async]

// Each TextInput implementation (Input, Password, Email, Tel, UrlInput, Search, NumberInput, Textarea):
readOnly: Maybe[Boolean] = Absent,
// ...
def readOnly(v: Boolean): Self = copy(readOnly = Present(v))
```

**Backend update:** `el.setAttribute("readonly", "")` when `readOnly = Present(true)`.

---

## Phase C: Style API Improvements

### C1. Add `Style.without[A]` for Typed Property Removal (Issue 5.1)

**Change:** In Style.scala, add instance and companion methods:

```scala
// Instance method on Style class:
def without[A <: Prop](using tag: ConcreteTag[A]): Style =
    Style(props.filter(p => !tag.accepts(p)))

// Companion method:
// Not needed — without is always called on an existing style instance
```

**Usage:**
```scala
val base = Style.bg(_.red).padding(10.px).bold
val noPadding = base.without[Prop.Padding]
// Result: Style with bg(red) and bold, padding removed
```

**Verified:** ConcreteTag is generated via macro for all Prop enum cases. `tag.accepts(p)` performs the runtime type check.

---

### C2. Sync DomStyleSheet Base CSS with Block/Inline Traits (Issue 3.1)

**Change:** In DomStyleSheet.scala:22-23, add ONLY elements safe for flex layout.

```scala
// Before:
div, section, main, header, footer, form, article, aside, p, ul, ol { display: flex; flex-direction: column; }
nav, li, span { display: flex; flex-direction: row; align-items: center; }

// After:
div, section, main, header, footer, form, article, aside, p, ul, ol, pre, code, h1, h2, h3, h4, h5, h6, label { display: flex; flex-direction: column; }
nav, li, span, button, a { display: flex; flex-direction: row; align-items: center; }
```

**MUST EXCLUDE from flex layout** (native CSS rendering required):
- `table`, `tr`, `td`, `th` — need native `display: table/table-row/table-cell` for correct table layout. The existing `table { border-collapse: collapse; width: 100%; }` rule depends on this.
- `select`, `option` — need native dropdown rendering. `display: flex` breaks dropdown behavior.
- `textarea`, `hr`, `br` — Void elements (no children to flex). Meaningless and could interfere with native rendering.
- All `input` elements — Void, native form controls.
- `img` — replaced element, no flex layout needed.

**Safe to add:**
- Column (Block, non-Void, non-native): `pre`, `code`, `h1`-`h6`, `label`
- Row (Inline, non-Void, non-native): `button`, `a`

---

## Phase D: Deferred Improvements (Design Discussion Needed)

### D1. Numeric Input Typing (Issue 1.6)

**Problem:** NumberInput/RangeInput onChange returns String instead of Double.

**Proposed direction:** New `NumericInput` capability trait:

```scala
sealed trait NumericInput extends Focusable with HasDisabled:
    def numericValue: Maybe[Double | SignalRef[Double]]
    def min: Maybe[Double]
    def max: Maybe[Double]
    def step: Maybe[Double]
    def onChangeNumeric: Maybe[Double => Unit < Async]
```

**Deferred because:**
- NumberInput currently extends TextInput which provides `placeholder`, `onInput` (String), and `value` (String). A NumericInput trait needs to decide whether to keep the String-based `onInput` for intermediate typing states
- `SignalRef[Double]` two-way binding requires backend conversion logic (DOM value is always String)
- RangeInput doesn't extend TextInput (has its own fields) — trait unification needed
- Breaking change to NumberInput's trait hierarchy

**Recommendation:** Implement B1 (add min/max/step with Double) now. Defer the full NumericInput trait redesign.

### D2. Style.hidden vs Element.hidden (Issue 3.2)

**Problem:** Two mechanisms for hiding elements.

**Decision:** Keep both — they serve distinct purposes:
- `element.hidden(signal)` → reactive visibility toggling
- `style(_.hidden)` → static/pseudo-state visibility (`hover(_.hidden)`)

**No code change needed.** The dual mechanism is correct when each is used for its intended purpose. Document the distinction in API docs when they exist.

### D3. Element Boilerplate Reduction (Issues 4.1, 4.2)

**Problem:** ~320 lines of near-identical case class definitions.

**Not recommended for v1.** The boilerplate is mechanical but:
- Explicit definitions make the codebase easy to navigate
- Each element type is independently modifiable (different trait mixes)
- Macro-based generation would make the code harder to understand
- The codebase is stable (34 elements unlikely to change frequently)

### D4. Cursor Naming (Issue 5.2)

**Proposed rename:**
- `Cursor.default_` → `Cursor.arrow` (describes the cursor shape)
- `Cursor.wait_` → `Cursor.waiting` (valid English, no keyword conflict)

**Deferred** as a breaking change. Can be done alongside any other breaking change batch.

### D5. img Factory Copy Bypass (Issue 1.7) / Label.forId (Issue 1.8)

**No practical fix.** `copy` is inherent to case classes. Making `Img` not a case class would lose pattern matching. The factory guarantee is "strong enough" — circumvention requires explicit intent.

Label.forId stringly-typed: no practical compile-time solution without a fundamentally different element model (phantom type IDs, builder pattern, etc.). Accept as inherent limitation.

---

## Implementation Order

All changes are additive and backward-compatible except where noted.

| Phase | Issue | Change | Breaking? |
|-------|-------|--------|-----------|
| A1 | 1.1 | `++` dedup | No — correct existing behavior |
| A2 | 1.2 | Color `private[kyo]` | No* — users use factories |
| A3 | 1.3 | clampSize Pct/Em | No — clamps invalid values |
| A4 | 1.4 | Keyboard.Char → scala.Char | **Yes** — pattern match on Char(c) changes |
| A5 | 1.5 | min/max/step Double | **Yes** — String→Double type change |
| B1 | 2.1 | NumberInput min/max/step | No — new fields with defaults |
| B2 | 2.2 | Select value + PickerInput | No — new field with default |
| B3 | 2.3 | Opt value | No — new field with default |
| B4 | 2.4 | FileInput accept | No — new field with default |
| B5 | 2.5 | TextInput readonly | No — new field with default |
| C1 | 5.1 | Style.without[A] | No — new method |
| C2 | 3.1 | DomStyleSheet sync | No — CSS defaults only |

*A2 is technically breaking if anyone constructs `Color.Hex(...)` directly instead of using `Color.hex(...)`. Unlikely in practice since the factory methods are the documented API.
