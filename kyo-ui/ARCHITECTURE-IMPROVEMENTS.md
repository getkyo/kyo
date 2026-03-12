# TUI2 Architecture Improvements — Design Document (v2)

## Goal

Make the renderer **more modular, simpler, and safer** through structural changes that
eliminate entire classes of bugs. The **CSS spec is the reference** — deviations are
documented and justified.

**Current state**: 21 bugs across 5 root causes, 8 structural fragility points.
**Target**: Fix 16+ bugs through 5 architectural improvements + targeted widget fixes.

---

## CSS Conformance Audit

Before designing fixes, here is how the current TUI defaults compare to the CSS spec.
Every deviation must be either fixed or explicitly justified.

### Current Defaults vs CSS Spec

| Property | CSS Default | TUI Default | Status |
|----------|------------|-------------|--------|
| `flex-direction` | `row` | `DirColumn` (0) | **DEVIATION** — justified |
| `align-items` | `normal` (= `stretch`) | `AlignStart` (0) | **BUG** — must fix |
| `justify-content` | `normal` (= `flex-start`) | `JustStart` (0) | Correct |
| `flex-grow` | `0` | `0.0` | Correct |
| `flex-shrink` | `1` | `1.0` | Correct |
| `flex-wrap` | `nowrap` | `0` (nowrap) | Correct |
| `overflow` | `visible` | `0` (visible) | Correct |
| `text-align` | `start` (= `left` in LTR) | `0` (left) | Correct |
| `text-transform` | `none` | `0` (none) | Correct |
| `white-space` | `normal` (wrap) | `true` (wrap) | Correct |
| `text-overflow` | `clip` | `false` (no ellipsis) | Correct |
| `min-width` | `auto` (content min for flex) | `-1` (none) | **DEVIATION** — documented |

### Inheritance — CSS vs TUI

| Property | CSS Inherited? | TUI Inherited? | Status |
|----------|---------------|---------------|--------|
| `color` (fg) | Yes | Yes | Correct |
| `font-weight` (bold) | Yes | Yes | Correct |
| `font-style` (italic) | Yes | Yes | Correct |
| `text-align` | Yes | **No** (hardcoded 0) | **BUG** — Phase 2 |
| `text-transform` | Yes | **No** (hardcoded 0) | **BUG** — Phase 2 |
| `white-space` (textWrap) | Yes | **No** (hardcoded true) | **BUG** — Phase 2 |
| `text-overflow` | **No** | No | Correct (but needs forwarding — see Phase 2) |
| `text-decoration` | No (propagates) | Yes (inherits) | **DEVIATION** — TUI simplification |
| `background-color` | No | Yes | **DEVIATION** — justified below |
| `opacity` | No (but cascades visually) | Not applied | Dead code |

### Justified Deviations

1. **`flex-direction: column` default** — Terminal UIs naturally stack vertically (top-to-bottom).
   Row layout is available via `Style.row`. Changing this would break all existing layouts
   for no ergonomic benefit.

2. **`background-color` inheritance** — CSS backgrounds are transparent by default; the parent's
   bg shows through. In TUI, each cell needs a definite color. Inheriting bg achieves the same
   visual result as CSS transparency — child text renders on the parent's bg.

3. **`text-decoration` inheritance** — CSS propagates decorations (parent's underline paints
   across child text, but child can't remove it). TUI simplifies this to true inheritance.
   The visual result is equivalent for common cases.

4. **`min-width: auto` not implemented** — CSS flex items have a content-based minimum that
   prevents shrinking below content. TUI items can shrink to 0. This is a future improvement,
   not needed for the current bug fixes.

---

## Phase 1: Widget Intrinsic Sizing

**Fixes**: Fragility #2 (measurement/rendering divergence), #6 (theme vs widget), #8 (implicit contract)
**Bugs addressed**: B8, B9, B23, B25 (completes with Phase 3)

### Problem

`FlexLayout.measureW/H` only considers children. Widgets that draw non-child content
(checkbox glyph, list marker, select arrow, range bar, hr line) have no way to declare
their intrinsic size. A checkbox with no children measures as 0+padding.

### Design

Add two methods to WidgetDispatch — one for intrinsic width (replaces children measurement)
and one for extra width (added alongside children measurement):

```scala
/** Intrinsic width that REPLACES children measurement.
  * Returns -1 if children measurement should be used instead.
  */
def intrinsicWidth(elem: UI.Element, availW: Int, ctx: RenderCtx)(
    using Frame, AllowUnsafe
): Int =
    elem match
        case _: UI.BooleanInput => 3   // "[x]" or "(*)"
        case _: UI.Hr           => 1   // min 1, will be stretched by Phase 3
        case _: UI.RangeInput   => 1   // min 1, will be stretched by Phase 3
        case sel: UI.Select     =>
            // Scan option children to find max display text width
            var maxW = 0
            var i = 0
            while i < sel.children.size do
                sel.children(i) match
                    case opt: UI.Opt =>
                        val text = extractOptText(opt)
                        maxW = math.max(maxW, text.length)
                    case _ => ()
                i += 1
            maxW + 2  // text + " ▼"
        case _ => -1  // -1 = no override, use children measurement

/** Extra width ADDED to children measurement (e.g., list markers).
  * Returns 0 if no extra width.
  * Note: Li always returns marker width — ctx.listKind is not available
  * during FlexLayout measurement (only set during rendering). This means
  * Li outside a list gets slightly extra measurement padding, which is harmless.
  */
def extraWidth(elem: UI.Element): Int =
    elem match
        case _: UI.Li => 2  // "• " or "N. " (use 2 as conservative default)
        case _        => 0

def intrinsicHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(
    using Frame, AllowUnsafe
): Int =
    elem match
        case _: UI.BooleanInput | _: UI.Hr | _: UI.RangeInput => 1
        case _ => -1  // -1 = no override

// Helper: extract display text from an Opt element
private def extractOptText(opt: UI.Opt): String =
    var text = ""
    var i = 0
    while i < opt.children.size do
        opt.children(i) match
            case UI.Text(v) => text = text + v
            case _          => ()
        i += 1
    text
```

Integrate into FlexLayout.measureW:

```scala
// FlexLayout.measureW, inside the elem: UI.Element case
val rawW =
    if rs.sizeW >= 0 then rs.sizeW
    else
        val intrinsic = WidgetDispatch.intrinsicWidth(elem, availW - insetW - marginW, ctx)
        if intrinsic >= 0 then
            // Widget replaces children measurement (checkbox, hr, range, select)
            intrinsic + insetW
        else
            // Widget may add extra alongside children (Li marker)
            val extra = WidgetDispatch.extraWidth(elem)
            val childrenW = if children.nonEmpty then
                measureChildrenW(children, availW - extra - insetW - marginW, availH, isRow, rs.gap, ctx)
            else 0
            extra + childrenW + insetW
```

**Key design decisions**:

1. **Two methods, not one**: `intrinsicWidth` replaces children measurement (for widgets whose
   children are data, not layout — Select, Checkbox). `extraWidth` adds alongside children
   (for Li markers drawn next to child content). This avoids ambiguity.

2. **Li marker always measured**: `extraWidth` returns 2 for all Li elements, not just those
   inside Ul/Ol. During measurement, `ctx.listKind` is 0 (only set during rendering in
   `Render.paintElement`). Always returning marker width is harmless — Li outside a list
   simply gets 2 extra chars of space that go unused.

3. **`ctx.measureRs` safety**: The intrinsic width methods use element properties (children text)
   directly, not `ctx.measureRs` which is overwritten during the measurement loop.

### Changes

| File | Change |
|------|--------|
| `WidgetDispatch.scala` | Add `intrinsicWidth`, `extraWidth`, `intrinsicHeight`, `extractOptText` |
| `FlexLayout.scala` | Call intrinsic sizing in `measureW`/`measureH` |

---

## Phase 2: Inherited Style

**Fixes**: Fragility #1 (split inheritance model), #7 (RenderCtx god object — partial)
**Bugs fixed**: B28 (textTransform), B29 (textAlign), B30 (textWrap/ellipsis)

### Problem

Two unrelated mechanisms for style inheritance:
1. **RenderCtx mutable fields** (fg, bg, bold, italic, underline, strikethrough, dim) — manually
   saved/restored in 14 lines at `Render.scala:139-171`
2. **Hardcoded parameters** to `drawText` at `Render.scala:23`: `align=0, transform=0, wrap=true, ellipsis=false`

Text style properties (textAlign, textTransform, textWrap) are CSS-inherited, properly resolved
in ResolvedStyle, but never forwarded to child Text nodes.

### CSS Reference

Per CSS spec, these properties are **inherited**:
- `color` — yes
- `font-weight` — yes
- `font-style` — yes
- `text-align` — yes
- `text-transform` — yes
- `white-space` (textWrap) — yes

And this one is **not inherited** but needs forwarding for TUI text rendering:
- `text-overflow` — no (CSS applies it at the block container level, but TUI renders
  text per-node via `drawText`, so the parent's value must reach the Text node)

### Design

**New file**: `InheritedStyle.scala` — bundles all inheritable properties.

```scala
final private[kyo] class InheritedStyle:
    var fg: Int = ColorUtils.Absent
    var bg: Int = ColorUtils.Absent
    var bold, italic, underline, strikethrough, dim: Boolean = false
    var textAlign: Int = 0
    var textTransform: Int = 0
    var textWrap: Boolean = true       // CSS: white-space: normal = wrap
    var textOverflow: Boolean = false   // Not CSS-inherited, but forwarded for TUI drawText

    def cellStyle: CellStyle =
        CellStyle(fg, bg, bold, italic, underline, strikethrough, dim)

    /** Copy all fields from another InheritedStyle. Zero-allocation. */
    def copyFrom(other: InheritedStyle): Unit =
        fg = other.fg; bg = other.bg
        bold = other.bold; italic = other.italic; underline = other.underline
        strikethrough = other.strikethrough; dim = other.dim
        textAlign = other.textAlign; textTransform = other.textTransform
        textWrap = other.textWrap; textOverflow = other.textOverflow

    /** Apply resolved style fields onto this inherited style. */
    def applyFrom(rs: ResolvedStyle): Unit =
        fg = rs.fg; bg = rs.bg
        bold = rs.bold; italic = rs.italic; underline = rs.underline
        strikethrough = rs.strikethrough; dim = rs.dim
        textAlign = rs.textAlign; textTransform = rs.textTransform
        textWrap = rs.textWrap; textOverflow = rs.textOverflow

    def reset(): Unit =
        fg = ColorUtils.Absent; bg = ColorUtils.Absent
        bold = false; italic = false; underline = false
        strikethrough = false; dim = false
        textAlign = 0; textTransform = 0
        textWrap = true; textOverflow = false
```

**Update RenderCtx**: Replace 8 loose mutable fields with one `InheritedStyle` + pre-allocated
save stack for zero-allocation save/restore:

```scala
// RenderCtx — replace loose fields
val inherited: InheritedStyle = new InheritedStyle

// Pre-allocated stack for save/restore (max tree depth 32)
private val inheritedStack: Array[InheritedStyle] = Array.fill(32)(new InheritedStyle)
private var inheritedDepth: Int = 0

inline def saveInherited(): Unit =
    inheritedStack(inheritedDepth).copyFrom(inherited)
    inheritedDepth += 1

inline def restoreInherited(): Unit =
    inheritedDepth -= 1
    inherited.copyFrom(inheritedStack(inheritedDepth))

def cellStyle: CellStyle = inherited.cellStyle

// Remove: var fg, bg, bold, italic, underline, strikethrough, dim, opacity
// Update beginFrame to call inherited.reset() and inheritedDepth = 0
```

**Update ResolvedStyle.inherit**:

```scala
def inherit(ctx: RenderCtx): Unit =
    val inh = ctx.inherited
    fg = inh.fg; bg = inh.bg
    bold = inh.bold; italic = inh.italic; underline = inh.underline
    strikethrough = inh.strikethrough; dim = inh.dim
    // ... reset non-inherited as before
```

**Update Render.paintElement** — save/restore becomes 2 lines + try/finally for safety:

```scala
// Before (14 lines, no exception safety):
val sFg = ctx.fg; val sBg = ctx.bg; ...  // 7 lines
ctx.fg = rs.fg; ctx.bg = rs.bg; ...      // 7 lines
WidgetDispatch.render(...)
ctx.fg = sFg; ctx.bg = sBg; ...          // 7 lines

// After (safe, modular):
ctx.saveInherited()
ctx.inherited.applyFrom(rs)
try
    WidgetDispatch.render(elem, cx, cy, cw, ch, rs, ctx)
finally
    ctx.restoreInherited()
```

**Critical fix — Render.render for Text nodes**:

```scala
case UI.Text(value) =>
    val inh = ctx.inherited
    ctx.canvas.drawText(value, x, y, w, h, inh.cellStyle,
        inh.textAlign, inh.textTransform, inh.textWrap, inh.textOverflow)
```

### Zero-Allocation Analysis

- `InheritedStyle` on RenderCtx: 1 object, reused across frames
- Save stack: 32 pre-allocated objects, never reallocated
- `saveInherited()`/`restoreInherited()`: just field copies, no allocation
- Per-frame cost: N `copyFrom` calls where N = tree depth (typically 10-15)
- Each `copyFrom`: 11 field assignments = ~44 bytes of memory writes

### Exception Safety

The try/finally in paintElement ensures `restoreInherited()` is always called, even if
a widget throws. The current code (local variables) is implicitly safe because local vars
are on the JVM stack, but the save/restore pattern is not — without try/finally, an
exception would leave the inherited state corrupted for the rest of the frame.

### Changes

| File | Change |
|------|--------|
| `InheritedStyle.scala` | New file (~45 lines) |
| `RenderCtx.scala` | Replace 8 fields with `inherited` + save stack |
| `ResolvedStyle.scala` | Update `inherit()` to read from `ctx.inherited` |
| `Render.scala` | Use save/restore + fix Text node rendering |
| Widget files | Replace `ctx.fg`/`ctx.bg`/etc. with `ctx.inherited.fg`/etc. |

---

## Phase 3: Cross-Axis Stretch

**Fixes**: Fragility #3 (no cross-axis stretch)
**Bugs fixed**: B8 (inputs tiny), B9 (checkbox invisible), B23 (li truncated), B25 (hr invisible)

### CSS Reference

Per CSS spec:
- `align-items` default is `normal`, which behaves as `stretch` in flex containers
- `stretch` expands a flex item's cross-axis size to fill the container
- **Exception**: items with an explicit cross-axis size (e.g., `height: 50px` in a row layout)
  are NOT stretched — they behave as `flex-start`
- Items with NO children and NO explicit size still stretch (they get cross-axis = container)

### Problem

`FlexLayout.arrange` line 239:
```scala
crossSizes(i) = math.max(0, math.min(ctx.layoutIntBuf(n + i), crossAvail))
```

Children always get their measured cross-axis size, never stretching. The default `align`
is `AlignStart` (0), but CSS default is `stretch`.

### Design

**Step 1**: Fix default `align` in `ResolvedStyle.inherit()`:

```scala
// ResolvedStyle.inherit() — change line 82
align = ResolvedStyle.AlignStretch  // was: 0 (AlignStart). CSS default is stretch.
```

**Step 2**: Track explicit cross-axis size during measure loop. Use a boolean array
(not high-bit flag — that approach is fragile for edge cases with negative measurements).

**Important**: The `crossExplicit` array must be declared BEFORE the measure loop so it
can be populated INSIDE the loop (where `ctx.measureRs` holds each child's style):

```scala
// In FlexLayout.arrange, BEFORE measureLoop:
val crossExplicit = new Array[Boolean](n)  // allocated alongside mainSizes/crossSizes

// Inside measureLoop, after reading child's style into mrs:
child match
    case elem: UI.Element =>
        val mrs = ctx.measureRs
        // ... (mrs populated for flex-grow/shrink — same loop)
        val crossSizeField = if isRow then mrs.sizeH else mrs.sizeW
        crossExplicit(i) = crossSizeField >= 0 || crossSizeField < -1  // px/em or pct
    case _ =>
        crossExplicit(i) = false  // non-element nodes stretch

// In snapshot (after measureLoop), apply stretch:
@tailrec def snapshot(i: Int): Unit =
    if i < n then
        mainSizes(i) = math.max(0, ctx.layoutIntBuf(i))
        val measuredCross = math.max(0, ctx.layoutIntBuf(n + i))
        crossSizes(i) =
            if align == ResolvedStyle.AlignStretch && !crossExplicit(i) then
                crossAvail  // stretch to container
            else
                math.min(measuredCross, crossAvail)
        snapshot(i + 1)
```

**Step 3**: Apply the same stretch logic in `arrangeWrapped`:

```scala
// In arrangeWrapped, when computing crossSz per item:
val crossSz =
    if align == ResolvedStyle.AlignStretch && !crossExplicit(j) then
        lineCrossMax  // stretch to line's max cross size
    else
        math.max(0, math.min(crossSizes(j), lineCrossMax))
```

**Step 4**: Update `computeAlignOffset` to handle stretch correctly:

```scala
private def computeAlignOffset(align: Int, crossAvail: Int, crossSz: Int): Int =
    align match
        case ResolvedStyle.AlignCenter  => math.max(0, (crossAvail - crossSz) / 2)
        case ResolvedStyle.AlignEnd     => math.max(0, crossAvail - crossSz)
        case ResolvedStyle.AlignStretch => 0  // offset=0, size already set to crossAvail
        case _                          => 0  // AlignStart
```

This is already correct — stretch items have `crossSz = crossAvail`, so offset 0 is right.

### Why `crossExplicit` array, not high-bit flag

The original plan used `Int.MinValue` as a flag bit in the cross-size slot. This is unsafe:
- If `measureH`/`measureW` returns 0 and we OR with `Int.MinValue`, `& Int.MaxValue` gives 0 (correct)
- But if measurement returns a negative value (bug), bit operations produce wrong results
- A separate boolean array is clear, safe, and only costs N bytes

The `crossExplicit` array is allocated in the same scope as `mainSizes`/`crossSizes`, which
are already allocated per-arrange call. The incremental cost is minimal.

### Impact

- Column layout: inputs, checkboxes, hr, range all stretch to container width
- Row layout: children stretch to container height
- Items WITH explicit cross-size are NOT stretched (CSS-correct)
- `align(start)` / `align(center)` / `align(end)` override stretch (CSS-correct)

### Changes

| File | Change |
|------|--------|
| `ResolvedStyle.scala` | Default `align` → `AlignStretch` (line 82) |
| `FlexLayout.scala` | Add `crossExplicit` array, stretch logic in arrange + arrangeWrapped |

---

## Phase 4: Size Authority

**Fixes**: Fragility #4 (size authority ambiguity)
**Bugs fixed**: B27 (explicit width/height ignored), B26 (flexWrap needs width constraint)

### CSS Reference

Per CSS spec:
- `width`/`height` set the **content box** size (in standard box model)
- The total rendered size = content + padding + border (+ margin outside)
- Flex containers respect explicit sizes on items (as initial `flex-basis` on main axis)
- At root level, an element's explicit size should constrain rendering

### Problem

`paintElement` uses caller-provided `(w, h)`, never checks `rs.sizeW`/`rs.sizeH`.
Root rendering passes viewport dimensions. So `div(style = _.width(10.em))` measures
as 10 but renders at viewport width.

### Design

Apply size constraint **inside `paintElement`**, after margins are stripped but before
border/padding computation. This is the correct place because `paintElement` already
handles the box model decomposition (margin → border → padding → content).

```scala
private def paintElement(elem, x, y, w, h, rs, ctx) =
    val tx   = x + rs.transX + rs.marL
    val ty   = y + rs.transY + rs.marT
    val adjW0 = w - rs.marL - rs.marR
    val adjH0 = h - rs.marT - rs.marB

    // --- NEW: Constrain border-box to explicit size ---
    // rs.sizeW/H is content size. Border-box = content + padding + border.
    val adjW = constrainToBorderBox(adjW0, rs.sizeW,
        rs.padL, rs.padR, rs.borderL, rs.borderR)
    val adjH = constrainToBorderBox(adjH0, rs.sizeH,
        rs.padT, rs.padB, rs.borderT, rs.borderB)

    if adjW <= 0 || adjH <= 0 then return
    // ... rest of paintElement unchanged
```

Where:

```scala
private inline def constrainToBorderBox(
    avail: Int,
    contentSize: Int,  // rs.sizeW or rs.sizeH
    padStart: Int, padEnd: Int,
    borderStart: Boolean, borderEnd: Boolean,
    flexGrow: Double
): Int =
    if flexGrow > 0.0 then
        avail  // flex-grow can expand beyond explicit size — don't constrain
    else if contentSize >= 0 then
        // Explicit size: content + padding + border
        val borderBox = contentSize + padStart + padEnd +
            (if borderStart then 1 else 0) + (if borderEnd then 1 else 0)
        math.min(avail, borderBox)
    else if contentSize < -1 then
        // Percentage: resolve against available (which already excludes margins).
        // For flex items, FlexLayout already resolved percentages during arrange.
        // This branch only applies to the root element (no FlexLayout parent).
        val pct = -(contentSize + 1)
        math.min(avail, (avail * pct) / 100)
    else
        avail  // auto: use full available
```

Updated call site passes `rs.flexGrow`:

```scala
    val adjW = constrainToBorderBox(adjW0, rs.sizeW,
        rs.padL, rs.padR, rs.borderL, rs.borderR, rs.flexGrow)
    val adjH = constrainToBorderBox(adjH0, rs.sizeH,
        rs.padT, rs.padB, rs.borderT, rs.borderB, rs.flexGrow)
```

### Why `flexGrow` check is needed

In CSS, `width` acts as `flex-basis`. With `flex-grow: 0` (default), the item stays at its
basis. With `flex-grow > 0`, the item CAN grow beyond its basis. FlexLayout allocates the
grown size and passes it as `w` to `paintElement`. Without the `flexGrow` check,
`constrainToBorderBox` would shrink the item back to its basis, undoing the flex-grow.

For the root element (the primary target of Phase 4), `flexGrow` is 0.0 (default), so the
constraint always applies. This is correct — root elements should respect explicit sizes.

### Why inside `paintElement`, not `renderElement`

The `renderElement` method doesn't have access to padding/border values yet (they're in `rs`
which is resolved, but the box model decomposition happens in `paintElement`). Placing the
constraint in `paintElement` after margin subtraction but before content rect computation
ensures the box model math is correct: margin is outside the constraint, padding/border
are inside.

### Edge Cases

- **Percentage for root**: `(avail * pct) / 100` — `avail` is at most viewport size (~200),
  `pct` is at most 100. No integer overflow risk. For non-root elements, FlexLayout already
  resolved percentages, so this branch rarely executes.
- **Content size = 0**: `constrainToBorderBox(avail, 0, pad, pad, true, true, 0.0)` →
  `min(avail, 0 + pad + pad + 2)`. Correct — element renders as just padding + border.
- **flex-grow > 0**: Constraint skipped entirely — FlexLayout's allocation is trusted.
- **flex-shrink**: Shrunk items have `w < explicit-border-box`, so `min(w, borderBox) = w`.
  No change. Correct.

### Changes

| File | Change |
|------|--------|
| `Render.scala` | Add `constrainToBorderBox` + apply in `paintElement` |

---

## Phase 5: Pseudo-State Defaults

**Fixes**: Root Cause 3 from bug analysis (no default pseudo-state styles in theme)
**Bugs fixed**: B4 (disabled identical to enabled), B5 (no focus indicator), B6 (placeholder)

### CSS Reference

Per CSS spec and browser behavior:
- **`:focus`** — Browsers apply a default focus ring (`outline: auto`, typically blue).
  Modern browsers use `:focus-visible` (keyboard navigation shows ring, mouse may not).
- **`:disabled`** — Browsers apply a greyed-out appearance. Disabled elements cannot receive
  focus or be activated.
- **`:hover`** — No default browser style. User must define hover styles.

### Design

Add default pseudo-state styles to `ResolvedTheme` for the Default theme:

```scala
// ResolvedTheme.scala — Default theme additions
private val defaultFocusStyle = Style
    .borderColor(Color.hex("#4488ff"))
    .border(1.px, BorderStyle.solid)  // ensure border is visible

private val defaultDisabledStyle = Style
    .color(Color.hex("#666666"))  // greyed-out text, matching browser behavior
```

No default hover style (consistent with CSS — browsers don't provide one).

Apply as fallbacks in `Render.resolveAllStyles`:

```scala
if isDisabled then
    val ds = rs.disabledStyle.getOrElse(ctx.theme.defaultDisabledStyle)
    rs.applyProps(ds)
else
    val isFocused = ctx.focus.isFocused(elem)
    val isHovered = ctx.hoverTarget.exists(_ eq elem)
    val isActive  = ctx.activeTarget.exists(_ eq elem)
    if isHovered then rs.hoverStyle.foreach(rs.applyProps)
    if isFocused then
        val fs = rs.focusStyle.getOrElse(ctx.theme.defaultFocusStyle)
        rs.applyProps(fs)
    if isActive then rs.activeStyle.foreach(rs.applyProps)
```

**Note**: Default focus/disabled styles only apply to the **Default theme**. Minimal and
Plain themes return `Style.Empty` for defaults (no visual feedback — user must provide it).

**B6 — Placeholder**: TextInputW already has placeholder rendering code (line 50). Verify
after Phase 2 lands whether it works. If not, the fix is in TextInputW: render placeholder
text with dimmed style when the input is empty, regardless of focus state (CSS behavior —
placeholder is always visible when empty, dimmed when focused).

### Changes

| File | Change |
|------|--------|
| `ResolvedTheme.scala` | Add `defaultFocusStyle`, `defaultDisabledStyle` + accessor methods |
| `Render.scala` | Use fallback styles for focus/disabled |
| `TextInputW.scala` | Verify/fix placeholder rendering |

---

## Phase 6: Widget-Specific Fixes

**Bugs**: B24 (ordered list numbering), B7 (typeText spaces), B20/B22 (verify after 1-5)

### B24: Ordered list numbers all "1."

**Root cause** (verified by code trace):

`Render.paintElement` lines 152-165 unconditionally save/restore `listKind` and `listIndex`
for EVERY element. The list index increment happens in WidgetDispatch (Li case, line 33),
but `paintElement`'s restore immediately reverts it.

Trace:
```
Ol → paintElement saves (listKind=0, listIndex=0), sets (2, 0)
  → Container.render → FlexLayout.arrange
    → Li[0] → paintElement saves (listKind=2, listIndex=0)
      → WidgetDispatch: listIndex++ → 1, renders "1."
      → paintElement restores (listKind=2, listIndex=0)  ← BUG: reverts increment
    → Li[1] → paintElement saves (listKind=2, listIndex=0)
      → WidgetDispatch: listIndex++ → 1, renders "1."  ← Always 1!
```

**Fix**: Only save/restore list context when entering a new list (Ul/Ol), not for every element.
Integrates with Phase 2's try/finally for exception safety:

```scala
// Render.paintElement — combined with Phase 2's inherited style save/restore
val isList = elem.isInstanceOf[UI.Ul] || elem.isInstanceOf[UI.Ol]
val prevListKind  = ctx.listKind
val prevListIndex = ctx.listIndex
if isList then
    elem match
        case _: UI.Ul => ctx.listKind = 1; ctx.listIndex = 0
        case _: UI.Ol => ctx.listKind = 2; ctx.listIndex = 0
        case _        => ()

ctx.saveInherited()       // Phase 2
ctx.inherited.applyFrom(rs)
try
    WidgetDispatch.render(elem, cx, cy, cw, ch, rs, ctx)
finally
    ctx.restoreInherited()  // Phase 2
    if isList then
        ctx.listKind = prevListKind
        ctx.listIndex = prevListIndex
```

This preserves the list context across Li siblings while correctly scoping it within Ul/Ol.
Nested lists (Ol inside Li inside Ol) work because the inner Ol saves/restores.
The try/finally ensures both inherited style AND list context are restored even on exception.

### B7: typeText() drops spaces

Test harness issue. The `TerminalEmulator.typeText()` method needs to handle `' '` as
`Keyboard.Space` → generate the correct input event. Fix in test code only.

### B20 / B22

Likely resolved or simplified after Phases 1+3 (intrinsic sizing + cross-axis stretch).
Verify after those phases land before writing targeted fixes.

### Changes

| File | Change |
|------|--------|
| `Render.scala` | Conditional save/restore for list context |
| `TerminalEmulatorQA.scala` or `TerminalEmulator.scala` | Fix typeText space handling |

---

## Implementation Order

```
Phase 1: Widget Intrinsic Sizing   (~60 lines changed)
    │
    └── Phase 3: Cross-Axis Stretch  (~50 lines changed, depends on Phase 1 for full effect)

Phase 2: Inherited Style            (~100 lines changed + ~45 new file)

Phase 4: Size Authority              (~25 lines changed)

Phase 5: Pseudo-State Defaults       (~30 lines changed)

Phase 6: Widget-Specific             (~20 lines changed)
```

Phases 1+3 and Phase 2 are independent — can be done in either order.
Phase 4 is independent of all others.
Phase 5 is independent but benefits from Phase 2 (inherited style infrastructure).
Phase 6 should be done last (some bugs may resolve from structural changes).

**Total**: ~330 lines across 8 files + 1 new file.

---

## Bug Resolution Matrix

| Bug | Description | Fixed By | CSS Behavior Matched |
|-----|------------|----------|---------------------|
| B4  | Disabled looks identical | Phase 5 | Yes — browsers grey out disabled |
| B5  | No focus indicator | Phase 5 | Yes — browsers show focus ring |
| B6  | Placeholder not rendering | Phase 5 | Yes — placeholder visible when empty |
| B8  | Inputs tiny in containers | Phase 1 + 3 | Yes — stretch fills cross-axis |
| B9  | Checkbox invisible | Phase 1 + 3 | Yes — intrinsic 3-char width + stretch |
| B20 | Select text not visible | Phase 1 + 3 | Verify — intrinsic width includes text |
| B22 | Range bar not visible | Phase 1 + 3 | Verify — stretch fills cross-axis |
| B23 | Li text truncated | Phase 1 + 3 | Yes — marker width in measurement |
| B24 | Ordered list all "1." | Phase 6 | Yes — sequential numbering |
| B25 | Hr invisible | Phase 1 + 3 | Yes — intrinsic 1 + stretch |
| B26 | flexWrap not working | Phase 4 | Yes — explicit width creates constraint |
| B27 | Explicit size ignored | Phase 4 | Yes — width/height respected |
| B28 | textTransform no effect | Phase 2 | Yes — text-transform is inherited |
| B29 | textAlign no effect | Phase 2 | Yes — text-align is inherited |
| B30 | textWrap ellipsis no effect | Phase 2 | Yes — white-space is inherited |
| B3  | Bold not distinct | Cosmetic | N/A — font rendering limitation |
| B7  | typeText drops spaces | Phase 6 | N/A — test harness bug |

**16 of 17 active bugs resolved** through structural improvements.

---

## Design Principles

1. **Zero-allocation on hot paths**: InheritedStyle uses pre-allocated stack (32 objects ≈ 1.6KB).
   `crossExplicit` array allocated per-arrange alongside existing `mainSizes`/`crossSizes`.
   No per-cell, per-frame, or per-keystroke allocation added.

2. **Exception safety**: try/finally wraps save/restore in paintElement. Current code lacks this.

3. **CSS as reference**: Every default, inheritance rule, and stretch behavior matches CSS spec.
   Deviations (column default, bg inheritance, text-decoration) are documented and justified.

4. **No shared module changes**: All improvements are in JVM renderer internals. UI.scala and
   Style.scala are untouched. The API contract is preserved.

5. **Incremental and reversible**: Each phase can be landed and tested independently.
