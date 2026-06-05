# Phase P10 prep: Reserve legend margin for color-bearing text/errorBar marks

**Gap closed:** GAP-LEGEND-MARGIN-TEXT-ERRORBAR
**Source:** `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala` (LOWER)
**Test file:** `kyo-ui/shared/src/test/scala/kyo/ChartLowerTest.scala`
**Depends on:** P6 (text/errorBar now honor colorScale; legend has meaningful content to reserve space for)

---

## 1. Confirmed defect: exactly how text/errorBar are excluded today

### 1a. Legend-category collection: `findColorChannel` (LOWER:1274-1288)

```scala
private def findColorChannel[A](marks: Chunk[Mark[A]]): Maybe[Encoding[A, ?]] =
    @scala.annotation.tailrec
    def loop(i: Int): Maybe[Encoding[A, ?]] =
        if i >= marks.size then Absent
        else
            marks(i) match
                case m: Mark.Bar[A, ?, ?]      => m.color.orElse(loop(i + 1))
                case m: Mark.Line[A, ?, ?]     => m.color.orElse(loop(i + 1))
                case m: Mark.Area[A, ?, ?]     => m.color.orElse(loop(i + 1))
                case m: Mark.Point[A, ?, ?]    => m.color.orElse(loop(i + 1))
                case m: Mark.Text[A, ?, ?]     => m.color.orElse(loop(i + 1))
                case m: Mark.ErrorBar[A, ?, ?] => m.color.orElse(loop(i + 1))
                case _: Mark.Rule[A]           => loop(i + 1)
    loop(0)
```

`findColorChannel` ALREADY includes `Mark.Text` and `Mark.ErrorBar` at lines 1284-1285. If a
`text(color = ...)` or `errorBar(color = ...)` mark is present, this function returns `Present(colorEnc)`
and `buildLegend` DOES render legend swatches for those marks (via `legendChannel` -> `findColorChannel`
at LOWER:1270-1271, then `buildLegendItems` at 1123). The legend RENDERS correctly after P6. The defect
is NOT here.

### 1b. Margin reservation: `buildLayout` `hasLegend` predicate (LOWER:278-285)

```scala
val hasLegend = !spec.legendCfg.isHidden && spec.marks.exists:
    case m: Mark.Bar[?, ?, ?]      => m.color.isDefined || m.stack.group.isDefined
    case m: Mark.Line[?, ?, ?]     => m.color.isDefined
    case m: Mark.Area[?, ?, ?]     => m.color.isDefined || m.stack.group.isDefined
    case m: Mark.Point[?, ?, ?]    => m.color.isDefined
    case _: Mark.Rule[?]           => false
    case _: Mark.Text[?, ?, ?]     => false      // line 284: hardcoded false, no color check
    case _: Mark.ErrorBar[?, ?, ?] => false      // line 285: hardcoded false, no color check
```

`Mark.Text` (line 284) and `Mark.ErrorBar` (line 285) use wildcard patterns (`_:`) so they always
return `false` regardless of whether a color channel is set. This is the SOLE defect: `hasLegend`
stays `false` for a text/errorBar-only color chart, so `topPad` is 0 (LOWER:296), `plotY` is
`MarginTop = 20.0` (not shifted down), and the rendered legend swatches overlap the plot area.

**Exact exclusion mechanism:** Both cases are wildcard matches (`_: Mark.Text`, `_: Mark.ErrorBar`)
that do not bind the mark value. They cannot call `m.color.isDefined` because there is no `m` binding.
Bar/Line/Area/Point all use value-binding patterns (`m: Mark.Bar`, etc.) and check `m.color.isDefined`.

### 1c. Constants at play

- `LegendReservedH = 20.0` (LOWER:167): pixels reserved for the legend strip.
- `MarginTop = 20.0` (LOWER:26): top margin before any reservation.
- When `hasLegend == true` and position is `Top` (the default): `topPad = LegendReservedH = 20.0`,
  so `plotY = MarginTop + topPad = 20.0 + 20.0 = 40.0`.
- When `hasLegend == false`: `topPad = 0.0`, `plotY = MarginTop = 20.0`.

**Today for a text-only color chart:** `plotY = 20.0` (un-shifted). Legend swatches render at
`MarginTop + (LegendReservedH - SwatchSize) / 2.0` (LOWER:1165) = 20 + 4 = 24 px. Plot starts at
20 px. The legend swatch y (24) sits inside the plot area (starts at 20): overlap.

**After fix:** `plotY = 40.0`. Legend swatch y = 24 px. Plot starts at 40 px. No overlap.

---

## 2. Exact edit to fix the defect

Single change in `buildLayout`, LOWER:284-285. Replace the two wildcard cases with value-binding
cases that check `m.color.isDefined`:

```scala
// BEFORE (lines 284-285):
    case _: Mark.Text[?, ?, ?]     => false
    case _: Mark.ErrorBar[?, ?, ?] => false

// AFTER:
    case m: Mark.Text[?, ?, ?]     => m.color.isDefined
    case m: Mark.ErrorBar[?, ?, ?] => m.color.isDefined
```

This mirrors the existing `m: Mark.Bar`, `m: Mark.Line`, `m: Mark.Area`, `m: Mark.Point` cases
immediately above. The `Mark.Rule` case stays `false` (Rule has no color channel).

No change to `findColorChannel`, `buildLegend`, `legendChannel`, or any mark lowering path. The only
touched function is `buildLayout`.

### Byte-identity guarantees

- A `text`/`errorBar` mark with NO color channel: `m.color.isDefined == false`, so `hasLegend`
  is unchanged for that mark (still contributes `false`). If no other mark triggers `hasLegend`,
  `topPad` stays 0, `plotY` stays `MarginTop = 20.0`. Byte-identical.
- A chart with no text/errorBar marks: the new cases are never reached. Byte-identical.
- A chart with bar/line/area/point color marks (the existing working case): those cases still fire
  first (the `exists` short-circuits on the first true). Byte-identical.

### Interaction with P6

P6 (already committed) threads `spec: Maybe[ChartSpec[A]]` into `lowerText` and `lowerErrorBar` so
those marks resolve the `colorScale` and RENDER the correct colors. P10 makes `buildLayout` reserve
the legend strip for those marks. Together:

- P6 ensures the MARK renders colorScale colors and the legend swatches are drawn by `buildLegend`
  (because `findColorChannel` already found the color channel).
- P10 ensures the LAYOUT reserves `LegendReservedH` so the swatch strip sits above `plotY` without
  overlapping the data.

P6 and P10 are disjoint code loci: P6 touches `lowerText`/`lowerErrorBar` (mark lowering), P10
touches `buildLayout` (layout sizing). No shared mutable state; no ordering constraint within this
edit.

### Double-count guard

If a chart has BOTH a bar with color AND a text with color, `hasLegend` fires true on the first mark
that satisfies the condition (`exists` short-circuits). The `topPad` is a scalar boolean outcome, not
a sum: reserving the strip once is identical to reserving it twice. `findColorChannel` also returns
the first color channel (LOWER:1274-1287 loop returns on first hit), so there is exactly one legend
regardless. No double-counting risk.

---

## 3. Reproduce-before-fix tests (Leaf L22)

**Leaf L22 (NEW):** Test must be written FIRST and confirmed to fail before the fix lands.

### L22a: text-only color mark reserves the top strip

```
Test name: "legend margin reserved for color-bearing text mark, plot shifted by LegendReservedH (GAP-LEGEND-MARGIN-TEXT-ERRORBAR)"
Test file: ChartLowerTest.scala (append after the L3 block near line 2449)
```

Setup:
- Data: `Chunk(Sale("Jan", Usd(1000), Region.NA), Sale("Feb", Usd(2000), Region.EU))` (reuse existing
  `Sale`/`Region` types already in ChartLowerTest).
- Chart: `UI.chart(rows)(text(x = _.month, y = _.revenue, label = _.month, color = _.region))` with
  `.legend(_.colorScale[Region](Region.NA -> naColor, Region.EU -> euColor))`.
- No bar/line/area/point marks. The ONLY color mark is text.

Exact assertion (pin the concrete `plotY` value):
```
Expected plotY = MarginTop + LegendReservedH = 20.0 + 20.0 = 40.0
Today (before fix): plotY = MarginTop = 20.0 (overlap)
```

Readback element: The text glyphs rendered by `lowerText` have `y = ys.apply(revenue)` where `ys` is
the y scale. With `.yScale(_.linear(0.0, 4000.0))` and the default chart size (640x480), the scale
range is `[plotBaseline, plotY]`. After the fix, `plotY = 40.0`, so the topmost glyph's `y`
attribute will reflect the shifted scale range. The cleaner, pinnable readback is the legend SWATCH y:
legend swatch y (from `buildLegendItems` LOWER:1498) is
`MarginTop + (LegendReservedH - SwatchSize) / 2.0 = 20 + 4 = 24.0`, which is BELOW `plotY=40.0`
(good, inside the reserved strip) -- that is the property: `swatchY < plotY`.

Concrete assertion:
```scala
val swatches = legendSwatchRects(root)
assert(swatches.size == 2, "L22a: expected 2 swatches for 2 regions")
val swatchY = coordNum(swatches(0).svgAttrs.y).getOrElse(fail("L22a: swatch y absent"))
// After fix: swatchY = 24.0, plotY = 40.0, so swatchY < plotY (legend above plot).
// Before fix: plotY = 20.0, swatchY = 24.0, so swatchY > plotY (legend INSIDE plot = overlap).
assert(swatchY < 40.0, s"L22a: swatch y ($swatchY) must be above plotY=40 in the reserved strip")
// Confirm the text glyphs exist in the marks group (not an axis label).
val marks = marksGroup(root)
val texts = marks.children.collect { case t: Svg.Text => t }
assert(texts.size >= 2, s"L22a: expected text glyphs in marks group")
```

Why it fails today: `plotY = 20.0` (no topPad), swatch is at y=24.0 which is > 20.0, so the
assertion `swatchY < 40.0` passes -- wait, that would pass. The stronger assertion is that the
swatch falls WITHIN the reserved strip, i.e. `swatchY < plotY`. Since today `plotY = 20.0` and
`swatchY = 24.0`, the swatch is BELOW `plotY` (in the plot area). The failing form:

```scala
// The swatch must sit ABOVE plotY (i.e. in the reserved strip, not inside the plot).
// After fix: plotY = 40.0, swatchY = 24.0 => 24.0 < 40.0 (pass).
// Before fix: plotY = 20.0, swatchY = 24.0 => 24.0 < 20.0 (FAIL: swatch overlaps plot).
assert(swatchY < plotY_expected, ...)
```

Since we cannot read `plotY` directly from the rendered SVG (it is an internal Layout field, not
emitted as an SVG attribute), the proxy is: read the y-coordinate of a y-axis tick that is at the
top (max y-domain value). With the y scale range = `[plotBaseline, plotY]`, the top tick is at
`plotY`. For the text chart with `.yScale(_.linear(0.0, 4000.0))`, the top tick is at domain 4000,
which maps to `plotY`. So:

```scala
// Readback via axis tick at max domain value:
// Lines in root whose y1 == y2 (horizontal grid lines) and y == plotY are the top axis gridlines.
// But simpler: check the swatch y against the EXPECTED plotY constant.
val expectedPlotY = 40.0  // MarginTop(20) + LegendReservedH(20)
assert(swatchY < expectedPlotY,
    s"L22a: legend swatch y ($swatchY) must be in the reserved strip (< plotY=$expectedPlotY); " +
    s"today hasLegend==false for text mark so plotY==20 and the swatch overlaps the plot")
```

This fails today because `plotY = 20.0` and `swatchY = 24.0 >= 20.0` (not `< 20.0`), but the
assertion checks `< 40.0` -- that would PASS today. Correct formulation that fails today:

```scala
// Fail-before-fix: assert that the plot y-start IS the reserved plotY.
// Read the minimum y of data glyphs (they must all be >= plotY for a no-overlap chart).
// With linear(0,4000) and plotH=(480-40-20-20)=400 (after fix) or 400(before fix=460-20-40=400?).
// Actually: before fix plotH = 480 - 20 - 0 - 40 = 420; after fix = 480 - 20 - 20 - 40 = 400.
// The text glyph at domain y=2000 maps to plotBaseline - (2000/4000)*plotH = baseline - plotH/2.
// Before: baseline=20+420=440, glyph y = 440 - 210 = 230.
// After:  baseline=40+400=440, glyph y = 440 - 200 = 240.
// So the glyph y shifts between fix states: assert the expected post-fix value.
```

The cleanest failing assertion is: assert that the swatch y is STRICTLY LESS than any text glyph y
in the marks group (i.e., the legend sits above the data), which fails today because the swatch is at
24 and glyphs start at ~230 (both before and after) -- actually this would pass. The correct
failing assertion is to directly assert `expectedPlotY == 40.0` by checking that the y-axis tick at
the maximum domain value equals 40.0.

**Simplest reliable readback (mirrors the existing point-headroom test at ChartLowerTest:2143-2156):**

```scala
val swatches = legendSwatchRects(root)
assert(swatches.size == 2, "L22a: expected 2 legend swatches (one per region)")
val swatchY = coordNum(swatches.toSeq.minBy(s => coordNum(s.svgAttrs.y).getOrElse(Double.MaxValue)).svgAttrs.y)
    .getOrElse(fail("L22a: swatch y absent"))
// Legend swatch is at MarginTop + (LegendReservedH - SwatchSize) / 2 = 20 + 4 = 24.0
// plotY after fix = 40.0; plotY before fix = 20.0.
// After fix: swatch(24) < plotY(40): pass.
// Before fix: swatch(24) >= plotY(20): FAIL.
assert(swatchY < 40.0,
    s"L22a: swatch y ($swatchY) must lie in the reserved strip (y < plotY=40); " +
    "today hasLegend=false for text-only color mark so plotY=20 and the swatch overlaps the plot")
```

Wait -- `swatchY(24) < 40.0` is TRUE regardless of the fix. The correct check is that
`swatchY < plotY` where `plotY` is inferred from axis geometry. The design's own note (04-invariants
L22 concrete-assertability flag) says the plan must pin the EXACT readback element. The plot's top y
can be read from the top x-axis tick mark: y-axis ticks are `Svg.Line` elements with `y1 = y2 = plotY`
(the start of the data region). Looking at the existing test at ChartLowerTest:106, `barY=20.0` for
a no-legend bar. For a top-legend bar the bar's top (`barY`) would be 40.0.

**Final pinned assertion for L22a:**

The text glyph at `y=revenue_max(4000)` with `yScale(linear(0,4000))` maps to `plotY`. After the
fix `plotY = 40.0`; before the fix `plotY = 20.0`. The highest-revenue glyph is at domain=2000 or
2000 -- actually to get a glyph AT plotY, use domain max=4000.

With `.yScale(_.linear(0.0, 4000.0))`:
- Scale: `Linear(domain=[0,4000], range=[plotBaseline, plotY])`, `apply(4000) = plotY`.
- Before fix: `plotY = 20.0`, `apply(4000) = 20.0`.
- After fix: `plotY = 40.0`, `apply(4000) = 40.0`.

Add a row with `revenue = 4000` (or use `Usd(4000)`) and assert its glyph y equals `40.0`:

```scala
val rows = Chunk(
    Sale("Jan", Usd(4000), Region.NA),  // at top of scale
    Sale("Feb", Usd(2000), Region.EU)
)
val spec = UI.chart(rows)(text(x = _.month, y = _.revenue, label = _.month, color = _.region))
    .yScale(_.linear(0.0, 4000.0))
    .legend(_.colorScale[Region](Region.NA -> naColor, Region.EU -> euColor))
val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
val marks = marksGroup(root)
val texts = marks.children.collect { case t: Svg.Text => t }.toSeq
    .sortBy(t => numOf(t.svgAttrs.x))
// glyph for Jan (revenue=4000, NA) is at the top of the y scale => y == plotY
val topGlyphY = numOf(texts(0).svgAttrs.y)
// After fix: plotY = MarginTop + LegendReservedH = 20.0 + 20.0 = 40.0
// Before fix: plotY = MarginTop = 20.0
assertClose(topGlyphY, 40.0, "L22a: top glyph y must equal reserved plotY=40 (hasLegend=true for text color)")
```

This FAILS before the fix (glyph y = 20.0, but assertion expects 40.0) and PASSES after.

### L22b: errorBar-only color mark reserves the top strip

Same structure as L22a but with `errorBar(color = _.region)`:

```scala
val rows = Chunk(
    EbSale("a", 10.0, 8.0, 10.0, Region.NA),  // hi=10.0 == top of scale
    EbSale("b", 5.0,  3.0,  7.0, Region.EU)
)
val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.region))
    .yScale(_.linear(0.0, 10.0))
    .legend(_.colorScale[Region](Region.NA -> naColor, Region.EU -> euColor))
val root = summon[Conversion[ChartSpec[EbSale], Svg.Root]](spec)
// The vLine for row NA has y1 = ys.apply(hi=10.0) = plotY.
val marks = marksGroup(root)
val lines = marks.children.collect { case l: Svg.Line => l }.toSeq
// vLines have x1 == x2. The NA vLine y1 should be plotY.
val vLines = lines.filter(l => l.svgAttrs.x1 == l.svgAttrs.x2)
val naVLine = vLines.minByOption(l => l.svgAttrs.y1.getOrElse(Double.MaxValue))
    .getOrElse(fail("L22b: no vLine found"))
val topY = naVLine.svgAttrs.y1.getOrElse(fail("L22b: y1 absent"))
assertClose(topY, 40.0, "L22b: top vLine y1 must equal reserved plotY=40 (hasLegend=true for errorBar color)")
```

### L22c (CO-PIN): no-color text/errorBar keeps `hasLegend == false`, plotY unchanged

```scala
// text mark WITHOUT color channel: plotY stays at MarginTop = 20.0
val spec = UI.chart(rows)(text(x = _.month, y = _.revenue, label = _.month))  // no color =
    .yScale(_.linear(0.0, 4000.0))
val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
val marks = marksGroup(root)
val texts = marks.children.collect { case t: Svg.Text => t }.toSeq.sortBy(t => numOf(t.svgAttrs.x))
val topGlyphY = numOf(texts(0).svgAttrs.y)
assertClose(topGlyphY, 20.0, "L22c: no-color text mark must keep plotY=20 (hasLegend=false, topPad=0)")
// Also: no legend swatches rendered
assert(legendSwatchRects(root).isEmpty, "L22c: no legend swatches for no-color text mark")
```

### L22d (CO-PIN): bar-only color chart legend + margin unchanged (byte-identity co-pin)

Reuse the existing bar test pattern (no code change needed -- verify the existing bar legend tests
remain green). The new `Mark.Text`/`Mark.ErrorBar` cases in `hasLegend` are not reached when the
only color mark is a bar.

---

## 4. Verification command

```
sbt 'kyo-ui/testOnly kyo.ChartLowerTest kyo.ChartInvariantsTest'
```

- `ChartLowerTest`: runs L22a/L22b/L22c/L22d plus all existing bar/line/area/point legend tests
  (co-pins for byte-identity).
- `ChartInvariantsTest`: runs the INV-004 golden guard (L8a) to confirm no existing chart output
  changed.

---

## 5. Traps and coordination notes

### Trap 1: do NOT double-count categories

`findColorChannel` returns the FIRST color channel found (loop exits on first Present). `buildLayout`
`hasLegend` uses `exists` (exits on first true match). Neither accumulates. A chart with both a bar
and a text color mark:
- `findColorChannel` returns the bar's color (first in the marks list). One legend, one set of
  swatches.
- `hasLegend` fires on the bar case (first mark). `topPad` is set once.
No double-counting is possible from this change.

### Trap 2: keep no-text/errorBar legend+margin byte-identical (INV golden)

The two new cases in `buildLayout` are `m: Mark.Text[?, ?, ?] => m.color.isDefined` and
`m: Mark.ErrorBar[?, ?, ?] => m.color.isDefined`. When `m.color` is `Absent`,
`m.color.isDefined == false`, which is the same as the wildcard `false` today. The INV-004 golden
(ChartInvariantsTest) uses a bar-only chart; the new cases are never evaluated. Byte-identical by
construction.

### Trap 3: coordinate with P6 (legend swatch colors from `resolvePalette`)

P6 wired `resolvePalette` into `lowerText`/`lowerErrorBar` so the MARK colors match the legend
swatches. P10 is completely downstream: it only changes `buildLayout`. The `buildLegend` path
already calls `findColorChannel` -> `resolvePalette` -> `buildLegendItems` for text/errorBar (since
`findColorChannel` already included them at LOWER:1284-1285). P10 just ensures the layout gives
those swatches room by reserving the top strip. No change to `buildLegend`, `resolvePalette`, or
any mark lowering. P6 and P10 are safe to read/test independently.

### Trap 4: `hasSizeLegend` is a separate path; do not conflate

`hasSizeLegend` (LOWER:289-291) reserves the top strip for a Point size-legend even without a color
legend. The `hasLegend` path (278-285) is independent. This edit touches only `hasLegend`. The
`hasSizeLegend` path and the `reserveTop` OR logic (LOWER:295) are untouched.

### Trap 5: `legendSwatchRects` filter (12x12 rect heuristic)

The test helper `legendSwatchRects` (ChartLowerTest:1395-1397) collects `Svg.Rect` with width=12
and height=12. The `buildLegendItems` function (LOWER:1487+) emits exactly those dimensions for
categorical swatches. This heuristic is stable across all existing tests and will work for text/errorBar
swatch rects too (same `buildLegendItems` path, same dimensions).

### Trap 6: `numOf` vs `coordNum` in assertions

The existing test uses `numOf` (fails with `fail(...)` on non-Num) for mark geometry and `coordNum`
(returns `Maybe[Double]`) for legend swatch positions. For the `topGlyphY` assertion on the text
glyph, use `numOf(t.svgAttrs.y)` (the text `y` attribute is set as a `Coord.Num` by `lowerText`).
For `errorBar` vLine `y1` coordinates, `lowerErrorBar` sets them as `Double`, not `Coord`, so read
via `l.svgAttrs.y1.getOrElse(fail(...))` directly.

### Concurrency note (P6 agent)

P6 edits `lowerText` (function body ~LOWER:2277-2340) and `lowerErrorBar` (function body
~LOWER:2351-2430). P10 edits ONLY the `buildLayout` `hasLegend` predicate (LOWER:278-285). These
are non-overlapping regions with no shared helper between them. P10 also adds tests to
`ChartLowerTest.scala` after line 2448 (end of file); P6 adds tests before that point (L2 is at
2295, L3 at 2348). Ensure P10 appends after any P6 additions to avoid merge conflicts.

---

## 3-line summary

`findColorChannel` (LOWER:1274-1288) already includes `Mark.Text` (line 1284) and `Mark.ErrorBar`
(line 1285) so `buildLegend` DOES render legend swatches for those marks; the defect is solely in
`buildLayout.hasLegend` (LOWER:278-285) where lines 284-285 use wildcard patterns (`_: Mark.Text`,
`_: Mark.ErrorBar`) that hardcode `false` regardless of a color channel. The fix replaces both
wildcards with value-binding patterns (`m: Mark.Text/ErrorBar => m.color.isDefined`), matching the
existing Bar/Line/Area/Point treatment; a text/errorBar mark with no color channel continues to
contribute `false` (byte-identical), and a chart with only bar/line color marks is never affected
(the `exists` short-circuits before reaching the new cases).
