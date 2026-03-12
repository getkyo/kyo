# TUI2 Web Parity Fixes — Implementation Plan (Revised)

14 fixes from TUI-WEB-PARITY-AUDIT.md. Two items removed after verification:
- ~~Fix 4 (Br)~~: Already works — Br matches `UI.Element` in FlexLayout, measures as width=0, height=1, acts as vertical spacer in column layout.
- ~~Fix 10 (Word wrapping)~~: Already implemented — `TextMetrics.findWordBreak` scans backward for spaces before breaking.

---

## Fix 1: Flex Shrink

**Problem**: Stored in `layoutDblBuf` but never applied. When children overflow container, they clamp to 0.

**Files**: `FlexLayout.scala`

**Change**: After the grow distribution in `arrange`, add a shrink loop for `remainSpace < 0`.

Per CSS spec, flex-shrink is weighted by `shrink * basis` (the item's natural size), unlike flex-grow which uses raw ratios. This prevents small items from shrinking to zero while large items barely change.

```scala
// After grow distribution (existing)
if remainSpace > 0 && totalGrow > 0 then ...

// NEW: shrink distribution (weighted by basis)
if remainSpace < 0 then
    // Accumulate weighted shrink: shrink_i * mainSize_i
    var totalWeightedShrink = 0.0
    @tailrec def accumShrink(i: Int): Unit =
        if i < n then
            totalWeightedShrink += ctx.layoutDblBuf(n + i) * ctx.layoutIntBuf(i)
            accumShrink(i + 1)
    accumShrink(0)
    if totalWeightedShrink > 0 then
        @tailrec def shrinkLoop(i: Int): Unit =
            if i < n then
                val shrink = ctx.layoutDblBuf(n + i)
                val basis = ctx.layoutIntBuf(i)
                if shrink > 0 && basis > 0 then
                    val reduction = ((-remainSpace) * shrink * basis / totalWeightedShrink).toInt
                    ctx.layoutIntBuf(i) = math.max(0, basis - reduction)
                shrinkLoop(i + 1)
        shrinkLoop(0)
```

Note: `totalShrink` does NOT need to be accumulated in the measure loop — we compute `totalWeightedShrink` inline since it depends on the measured sizes.

---

## Fix 2: Margins

**Problem**: Parsed in `ResolvedStyle` (`marT/marR/marB/marL`) but never applied.

**Files**: `FlexLayout.scala`, `Render.scala`

**Change**: Margins in CSS flexbox do NOT collapse (unlike block layout). They add space around the element.

In `Render.paintElement`, apply margins by offsetting position and reducing available size:

```scala
// Offset by margins before any other positioning
val tx = x + rs.transX + rs.marL
val ty = y + rs.transY + rs.marT
val adjW = w - rs.marL - rs.marR
val adjH = h - rs.marT - rs.marB
// Then use adjW/adjH instead of w/h for shadow, bg, border, content
```

In `FlexLayout.measureW`/`measureH`, add margins to intrinsic size so the parent container allocates enough space:

```scala
// In measureW for UI.Element, after computing natural width:
val result = ... // existing logic
result + mrs.marL + mrs.marR

// In measureH for UI.Element:
val result = ... // existing logic
result + mrs.marT + mrs.marB
```

---

## Fix 3: Min/Max Constraints

**Problem**: `minW`, `maxW`, `minH`, `maxH` stored but never read.

**Files**: `FlexLayout.scala`

**Change**: Per CSS spec, min/max are applied after flex grow/shrink distribution. Clamp measured sizes in `arrange` after grow/shrink:

```scala
// After grow/shrink distribution, clamp to min/max
@tailrec def clampLoop(i: Int): Unit =
    if i < n then
        children(i) match
            case elem: UI.Element =>
                val mrs = ctx.measureRs
                mrs.inherit(ctx)
                mrs.applyProps(ctx.theme.styleFor(elem))
                resolveStyleInto(elem, mrs, ctx)
                val mainMin = if isRow then mrs.minW else mrs.minH
                val mainMax = if isRow then mrs.maxW else mrs.maxH
                var s = ctx.layoutIntBuf(i)
                if mainMin >= 0 then s = math.max(s, mainMin)
                if mainMax >= 0 then s = math.min(s, mainMax)
                ctx.layoutIntBuf(i) = s
            case _ => ()
        clampLoop(i + 1)
clampLoop(0)
```

Also apply min/max in `measureW`/`measureH` so intrinsic size reports respect constraints.

---

## Fix 5: Heading Sizes (H1–H6)

**Problem**: All headings just get `Style.bold`. No visual hierarchy.

**Files**: `ResolvedTheme.scala`

**Change**: Terminal can't change font size. Web UA stylesheet: H1=2em, H2=1.5em, H3=1.17em, H4=1em, H5=0.83em, H6=0.67em (all bold, with margins proportional to size). Approximate via vertical padding for visual weight. All headings remain bold — H5/H6 should NOT be dim (web makes them smaller, not dimmer):

```scala
val h1Style = Style.bold.padding(1.em, 0.em, 1.em, 0.em)  // most prominent
val h2Style = Style.bold.padding(1.em, 0.em, 0.em, 0.em)  // moderate
val h3Style = Style.bold                                    // standard
val h4Style = Style.bold                                    // same as H3 (1em = 1 cell)
val h5Style = Style.bold                                    // can't go smaller than 1 cell
val h6Style = Style.bold                                    // can't go smaller than 1 cell

case _: UI.H1 => h1Style
case _: UI.H2 => h2Style
case _: UI.H3 => h3Style
case _: UI.H4 => h4Style
case _: UI.H5 => h5Style
case _: UI.H6 => h6Style
```

---

## Fix 6: List Markers (Ul/Ol/Li)

**Problem**: No bullets for Ul, no numbers for Ol.

**Files**: `RenderCtx.scala`, `Render.scala`, `WidgetDispatch.scala`, `ResolvedTheme.scala`

**Change**: Track list context on `RenderCtx` and render markers when painting Li.

In `RenderCtx`, add list tracking:
```scala
var listKind: Int = 0   // 0=none, 1=ul, 2=ol
var listIndex: Int = 0  // 1-based item counter for ol
```

In `Render.renderElement`, save/set/restore list context when entering Ul/Ol:
```scala
elem match
    case _: UI.Ul =>
        val prevKind = ctx.listKind; val prevIdx = ctx.listIndex
        ctx.listKind = 1; ctx.listIndex = 0
        paintElement(elem, ...)
        ctx.listKind = prevKind; ctx.listIndex = prevIdx
    case _: UI.Ol =>
        val prevKind = ctx.listKind; val prevIdx = ctx.listIndex
        ctx.listKind = 2; ctx.listIndex = 0
        paintElement(elem, ...)
        ctx.listKind = prevKind; ctx.listIndex = prevIdx
    case _ => paintElement(elem, ...)
```

In `WidgetDispatch.render`, separate Li from container rendering:
```scala
case li: UI.Li =>
    val markerW = 3  // "• " or "1. " = 2-3 chars
    if ctx.listKind == 1 then
        ctx.canvas.drawString(cx, cy, markerW, "• ", 0, rs.cellStyle)
    else if ctx.listKind == 2 then
        ctx.listIndex += 1
        val marker = s"${ctx.listIndex}. "
        ctx.canvas.drawString(cx, cy, marker.length, marker, 0, rs.cellStyle)
    Container.render(elem, cx + markerW, cy, cw - markerW, ch, rs, ctx)
```

Theme: add left padding to Ul/Ol for indentation:
```scala
case _: UI.Ul | _: UI.Ol => Style.padding(0.em, 0.em, 0.em, 1.em)
```

---

## Fix 7: Th Bold + Center

**Problem**: Th renders identically to Td.

**Files**: `ResolvedTheme.scala`

**Change**: Web UA stylesheet makes Th both bold AND centered (text-align: center). Add theme style:

```scala
case _: UI.Th => Style.bold.textAlign(TextAlign.center)
```

---

## Fix 8: Anchor Underline + Blue

**Problem**: Links are invisible without user styling.

**Files**: `ResolvedTheme.scala`

**Change**: Web UA stylesheet: `color: -webkit-link` (blue), `text-decoration: underline`. Add theme style:

```scala
case _: UI.Anchor => Style.underline.color("#5599ff")
```

---

## Fix 9: Radio Group Exclusion

**Problem**: Multiple radios can be checked simultaneously. No group concept.

**Files**: `UI.scala` (shared model), `CheckboxW.scala`, `FocusRing.scala`

**Change**: Per HTML spec, radios with the same `name` are mutually exclusive (document-wide, not form-scoped). Only the newly-checked radio fires `onChange`.

1. Add `name` field to Radio in `UI.scala`:
```scala
final case class Radio(
    attrs: Attrs = Attrs(),
    checked: Maybe[Boolean | Signal[Boolean]] = Absent,
    disabled: Maybe[Boolean | Signal[Boolean]] = Absent,
    onChange: Maybe[Boolean => Unit < Async] = Absent,
    name: Maybe[String] = Absent
) extends ...
```

2. Add `forEachFocusable` to `FocusRing`:
```scala
def forEachFocusable(f: UI.Element => Unit): Unit =
    @tailrec def loop(i: Int): Unit =
        if i < count then
            f(elems(i))
            loop(i + 1)
    loop(0)
```

3. In `CheckboxW.handleKey`, when toggling a Radio with a name:
```scala
case radio: UI.Radio =>
    val v = !ValueResolver.resolveBoolean(radio.checked, ctx.signals)
    if v && radio.name.nonEmpty then
        // Uncheck other radios in same group
        ctx.focus.forEachFocusable { other =>
            other match
                case otherRadio: UI.Radio
                    if (otherRadio ne radio) && otherRadio.name == radio.name =>
                    ValueResolver.setBoolean(otherRadio.checked, false)
                    // Do NOT fire onChange on unchecked radios (per HTML spec)
                case _ => ()
        }
    ValueResolver.setBoolean(radio.checked, v)
    radio.onChange.foreach(f => ValueResolver.runHandler(f(v)))
```

---

## Fix 11: Percentage Sizing

**Problem**: `Pct(v)` resolves to 0 in `ResolvedStyle`. No parent dimension propagation.

**Files**: `ResolvedStyle.scala`, `FlexLayout.scala`

**Change**: Use negative encoding to distinguish percentage from pixel values. This avoids adding new fields or changing the type of `sizeW`/`sizeH`.

In `ResolvedStyle.resolveSizeOr`:
```scala
case Style.Size.Pct(v) => -(v.toInt + 1)  // negative = percentage, +1 to distinguish Pct(0)
```

In `FlexLayout.measureW`/`measureH`, when `rs.sizeW < -1` (was `>= 0` check), skip it — percentages can only be resolved in `arrange` where parent dimensions are known.

In `FlexLayout.arrange`, after measuring each child, resolve percentage sizes against container:
```scala
val mainSz = ctx.layoutIntBuf(i)
// Check if child has a percentage size
child match
    case elem: UI.Element =>
        val mrs = ... // resolved style
        val pctMain = if isRow then mrs.sizeW else mrs.sizeH
        if pctMain < -1 then  // percentage encoded
            val pct = -(pctMain + 1)
            ctx.layoutIntBuf(i) = (mainAvail * pct) / 100
    case _ => ()
```

---

## Fix 12: Flex Wrap

**Problem**: Not supported. Single-line layout only.

**Files**: `Style.scala` (model), `ResolvedStyle.scala`, `FlexLayout.scala`

**Change**: Multi-line layout in `arrange`. This is the largest fix.

1. Add `FlexWrap` to Style:
```scala
enum FlexWrap derives CanEqual: case wrap, noWrap
case FlexWrapProp(value: FlexWrap) extends Prop
```
Add DSL method: `def flexWrap(v: FlexWrap): Style`

2. Add `var flexWrap: Int = 0` to `ResolvedStyle` (0=nowrap, 1=wrap). Reset in `inherit`.

3. In `arrange`, when `flexWrap == 1`:
   - First pass: measure children, accumulate main-axis sizes, insert line breaks when accumulated size exceeds `mainAvail`
   - Track line starts in scratch buffer (e.g. `ctx.layoutIntBuf` at offset `2*n`)
   - For each line: compute grow/shrink independently within that line
   - Emit positions, advancing cross-axis position between lines

When `flexWrap == 0` (default), behavior is unchanged (existing single-line code).

---

## Fix 13: Date/Time/Color Pickers

**Problem**: ArrowUp/Down does nothing — no Opt children to cycle through.

**Files**: `WidgetDispatch.scala`, `PickerW.scala`

**Change**: Route DateInput/TimeInput/ColorInput to a text-editing mode instead of picker mode. Reuse cursor/scroll state from FocusRing (same as TextInput).

In `WidgetDispatch.render`:
```scala
case pi: UI.PickerInput =>
    pi match
        case _: UI.Select => PickerW.render(pi, elem, cx, cy, cw, ch, rs, ctx)
        case _            => PickerW.renderEditable(pi, elem, cx, cy, cw, ch, rs, ctx)
```

`PickerW.renderEditable`: renders the value string with a cursor. Accepts character input, ArrowLeft/Right for cursor movement, Backspace/Delete.

In `WidgetDispatch.handleKey`:
```scala
case pi: UI.PickerInput =>
    pi match
        case _: UI.Select => PickerW.handleKey(pi, elem, event, ctx)
        case _            => PickerW.handleEditableKey(pi, elem, event, ctx)
```

---

## Fix 14: Text Selection

**Problem**: No Shift+Arrow, Ctrl+A, word navigation.

**Files**: `FocusRing.scala`, `TextInputW.scala`

**Change**:

1. Add selection state to `FocusRing` (parallel arrays like cursor/scroll):
```scala
private val selStartArr = new Array[Int](maxFocusables)  // -1 = no selection
private val selEndArr   = new Array[Int](maxFocusables)
```
Initialize to -1. Migrate in `migrateState`.

2. In `TextInputW.handleKey`:
   - `Shift+ArrowLeft/Right`: extend selection from cursor
   - `Shift+Home/End`: select to line boundary
   - `Ctrl+A`: select all
   - `Ctrl+ArrowLeft/Right`: word navigation (scan to next space boundary)
   - Any cursor movement without Shift: collapse selection
   - Backspace/Delete with active selection: delete selected range
   - Character input with active selection: replace selected range

3. In `TextInputW.render`: draw selected characters with inverted fg/bg (swap colors).

---

## Fix 15: Gradients

**Problem**: `BgGradientProp` blends to single color instead of per-cell interpolation.

**Files**: `ResolvedStyle.scala`, `Render.scala`

**Change**: The `BgGradientProp(direction, colors, positions)` already stores all needed data. We need to extract it into `ResolvedStyle` fields and render per-cell.

1. Add gradient fields to `ResolvedStyle`:
```scala
var gradientDir: Int = -1      // -1=none, maps from GradientDirection ordinal
var gradientStops: Int = 0
val gradientColors = new Array[Int](8)     // resolved RGB, max 8 stops
val gradientPositions = new Array[Double](8)
```
Reset `gradientDir = -1; gradientStops = 0` in `inherit`.

2. In `applyProps` for `BgGradientProp`: populate gradient arrays instead of blending:
```scala
case BgGradientProp(dir, colors, positions) =>
    if colors.size >= 2 then
        gradientDir = dir.ordinal
        gradientStops = math.min(colors.size, 8)
        var i = 0
        while i < gradientStops do
            gradientColors(i) = ColorUtils.resolve(colors(i))
            gradientPositions(i) = if i < positions.size then positions(i) else i.toDouble / (gradientStops - 1)
            i += 1
    else if colors.size == 1 then
        bg = ColorUtils.resolve(colors(0))
```

3. In `Render.paintElement`, render gradient when present:
```scala
if rs.gradientDir >= 0 then
    paintGradient(tx, ty, w, h, rs, ctx)
else if rs.bg != ColorUtils.Absent then
    ctx.canvas.screen.fillBg(tx, ty, w, h, rs.bg)
```

4. `paintGradient`: for each cell, compute normalized position (0.0–1.0) based on direction, find two surrounding stops, linearly interpolate RGB channels, set cell bg:
```scala
private def paintGradient(x: Int, y: Int, w: Int, h: Int, rs: ResolvedStyle, ctx: RenderCtx): Unit =
    var py = 0
    while py < h do
        var px = 0
        while px < w do
            val t = rs.gradientDir match
                case 0 => px.toDouble / math.max(1, w - 1)       // toRight
                case 1 => py.toDouble / math.max(1, h - 1)       // toBottom
                case 2 => (1.0 - px.toDouble / math.max(1, w - 1))  // toLeft
                case 3 => (1.0 - py.toDouble / math.max(1, h - 1))  // toTop
                case _ => ... // diagonal: average of x and y components
            val color = interpolateGradient(t, rs)
            ctx.canvas.screen.setBg(x + px, y + py, color)
            px += 1
        py += 1
```

---

## Fix 16: FileInput Filename Display

**Problem**: Always shows `[Choose File]` even after selection.

**Files**: `FileInputW.scala`, `RenderCtx.scala`

**Change**: Store selected paths on `RenderCtx`:

```scala
// RenderCtx:
val fileInputPaths = new java.util.IdentityHashMap[UI.FileInput, String]()
```

In `FileInputW.handleKey`, after successful selection:
```scala
result.foreach { path =>
    ctx.fileInputPaths.put(fi, path)
    fi.onChange.foreach(f => ValueResolver.runHandler(f(path)))
}
```

In `FileInputW.render`:
```scala
val selected = ctx.fileInputPaths.get(fi)
val label =
    if selected != null && selected.nonEmpty then
        val name = selected.substring(selected.lastIndexOf('/') + 1)
        s"[$name]"
    else "[Choose File]"
discard(ctx.canvas.drawString(cx, cy, cw, label, 0, style))
```

---

## Dependency Graph

```
Independent (no cross-dependencies):
  Fix 5  (Heading sizes)      — ResolvedTheme only
  Fix 7  (Th bold+center)     — ResolvedTheme only
  Fix 8  (Anchor underline)   — ResolvedTheme only
  Fix 16 (FileInput display)  — FileInputW + RenderCtx only

Layout cluster (coordinate changes in FlexLayout):
  Fix 1  (Flex shrink)        — FlexLayout.arrange
  Fix 2  (Margins)            — FlexLayout + Render
  Fix 3  (Min/max)            — FlexLayout measure + arrange
  Fix 11 (Percentage)         — ResolvedStyle + FlexLayout
  Fix 12 (Flex wrap)          — FlexLayout major refactor (depends on Fix 1)

Rendering:
  Fix 15 (Gradients)          — ResolvedStyle + Render

Widget cluster:
  Fix 6  (List markers)       — Render + WidgetDispatch + RenderCtx
  Fix 9  (Radio groups)       — UI.scala model + CheckboxW + FocusRing
  Fix 13 (Pickers)            — PickerW + WidgetDispatch
  Fix 14 (Text selection)     — FocusRing + TextInputW
```

## Implementation Order

Suggested order to minimize conflicts:

1. **Theme-only fixes** (Fix 5, 7, 8) — trivial, no cross-dependencies
2. **Fix 16** (FileInput) — simple, isolated
3. **Fix 1** (Flex shrink) — foundational layout fix
4. **Fix 2** (Margins) — layout + render coordination
5. **Fix 3** (Min/max) — layout, builds on measure infrastructure
6. **Fix 11** (Percentage) — layout, encoding change in ResolvedStyle
7. **Fix 6** (List markers) — widget + context tracking
8. **Fix 9** (Radio groups) — shared model change + widget
9. **Fix 15** (Gradients) — rendering only
10. **Fix 13** (Pickers) — widget
11. **Fix 14** (Text selection) — widget + FocusRing
12. **Fix 12** (Flex wrap) — largest change, do last
