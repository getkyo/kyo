# Phase P6 prep — text + errorBar colorScale resolution (`spec` param thread-through)

**Gaps closed:** GAP-COLOR-TEXT, GAP-COLOR-ERRORBAR  
**Leaves:** L2 (NEW), L3 (NEW), L8 co-pin (Leaf 17 + Leaf 18 Absent path, existing tests retained)  
**Source file:** `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala` (4064 lines after P1-P5)  
**Test file:** `kyo-ui/shared/src/test/scala/kyo/ChartLowerTest.scala`

---

## 1. Current state — confirmed signatures, palette blocks, call sites

### `lowerText` (LOWER:2351-2416)

Current signature (lines 2351-2358):
```scala
private def lowerText[A, X, Y](
    mark: Mark.Text[A, X, Y],
    rows: Chunk[A],
    xs: Scale,
    ys: Scale,
    defaultColor: Style.Color,
    theme: Theme
)(using Frame): Chunk[Svg.SvgElement] =
```

Current palette block (lines 2368-2373) — the broken code:
```scala
val basePaletteText: Chunk[Style.Color] = themePalette(theme)
val palette: Chunk[Style.Color] =
    if colorCatsWithRaw.isEmpty then Chunk.empty
    else
        colorCatsWithRaw.zipWithIndex.map: (_, i) =>
            basePaletteText.toSeq.apply(i % basePaletteText.size)
```

This resolves palette purely by index from `themePalette(theme)` (= `theme.palette.getOrElse(DefaultPalette)`), ignoring any `legendCfg.colorScale`. When a categorical colorScale is configured, it is silently discarded.

### `lowerErrorBar` (LOWER:2425-2497)

Current signature (lines 2425-2432):
```scala
private def lowerErrorBar[A, X, Y](
    mark: Mark.ErrorBar[A, X, Y],
    rows: Chunk[A],
    xs: Scale,
    ys: Scale,
    defaultColor: Style.Color,
    theme: Theme
)(using Frame): Chunk[Svg.SvgElement] =
```

Current palette block (lines 2436-2441) — the broken code:
```scala
val basePaletteErr: Chunk[Style.Color] = themePalette(theme)
val palette: Chunk[Style.Color] =
    if colorCatsWithRaw.isEmpty then Chunk.empty
    else
        colorCatsWithRaw.zipWithIndex.map: (_, i) =>
            basePaletteErr.toSeq.apply(i % basePaletteErr.size)
```

Same defect: `legendCfg.colorScale` is never consulted; colors come purely from `themePalette(theme)` by index. Note that P3 already changed the `px` computation at line 2458 (`xs.apply(x) + xs.bandwidth / 2.0`), so the P6 edit to `lowerErrorBar` is in the palette block only, not in the geometry.

### The four call sites (current, post-P1-P5 line numbers confirmed by grep)

**Call site CS1 — `marksRegion`, line 1762** (`case m: Mark.Text`):
```scala
case m: Mark.Text[A, ?, ?]     => lowerText(m, rows, xs, ys, markColor, theme).asInstanceOf[Chunk[UI]]
```
`marksRegion` takes `spec: Maybe[ChartSpec[A]] = Absent` (line 1713). At the call site, `theme` is already derived as `val theme = spec.map(_.theme).getOrElse(Theme.default)` (line 1720). After the fix, the call site passes `spec` as the last positional argument.

**Call site CS2 — `marksRegion`, line 1763** (`case m: Mark.ErrorBar`):
```scala
case m: Mark.ErrorBar[A, ?, ?] => lowerErrorBar(m, rows, xs, ys, markColor, theme).asInstanceOf[Chunk[UI]]
```
Same context as CS1: `spec: Maybe[ChartSpec[A]]` is in scope.

**Call site CS3 — `marksRegionWithTransitions`, line 3610** (`case m: Mark.Text`):
```scala
case m: Mark.Text[A, ?, ?] =>
    (accElems ++ lowerText(m, rows, xs, ys, markColor, spec.theme), accGeom)
```
`marksRegionWithTransitions` takes `spec: ChartSpec[A]` (not Maybe, a concrete ChartSpec — line 3542). After the fix, the call site passes `Present(spec)`.

**Call site CS4 — `marksRegionWithTransitions`, line 3612** (`case m: Mark.ErrorBar`):
```scala
case m: Mark.ErrorBar[A, ?, ?] =>
    (accElems ++ lowerErrorBar(m, rows, xs, ys, markColor, spec.theme), accGeom)
```
Same context as CS3: `spec: ChartSpec[A]` is in scope. After the fix, passes `Present(spec)`.

---

## 2. Signature delta and call-site changes

### Signature change (both functions)

Add `spec: Maybe[ChartSpec[A]] = Absent` as the last value parameter, before `(using Frame)`:

```scala
private def lowerText[A, X, Y](
    mark: Mark.Text[A, X, Y],
    rows: Chunk[A],
    xs: Scale,
    ys: Scale,
    defaultColor: Style.Color,
    theme: Theme,
    spec: Maybe[ChartSpec[A]] = Absent        // NEW
)(using Frame): Chunk[Svg.SvgElement] =
```

```scala
private def lowerErrorBar[A, X, Y](
    mark: Mark.ErrorBar[A, X, Y],
    rows: Chunk[A],
    xs: Scale,
    ys: Scale,
    defaultColor: Style.Color,
    theme: Theme,
    spec: Maybe[ChartSpec[A]] = Absent        // NEW
)(using Frame): Chunk[Svg.SvgElement] =
```

Rationale for keeping `theme` alongside `spec`: the Absent arm still needs `theme` for the by-index fallback (`themePalette(theme)` = `theme.palette.getOrElse(DefaultPalette)`). Threading `spec` alone would require `spec.map(_.theme).getOrElse(Theme.default)` at the palette fallback, which is an unnecessary rewrite of existing working code. Keeping `theme` as a passed param makes the Absent arm verbatim-unchanged (preserving byte-identity for Leaf 17/18). The `theme` param remains unchanged; `spec` is added.

This mirrors `lowerLine` (2213: `spec: Maybe[ChartSpec[A]] = Absent`) and `lowerPoint` (2828: `spec: Maybe[ChartSpec[A]] = Absent`).

### Palette block change (both functions)

Replace the by-index-only palette block with a `spec`-branched block:

**For `lowerText`**, replace lines 2368-2373 with:
```scala
val basePaletteText: Chunk[Style.Color] = themePalette(theme)
val palette: Chunk[Style.Color] =
    if colorCatsWithRaw.isEmpty then Chunk.empty
    else spec match
        case Present(s) => resolvePalette(s, colorCatsWithRaw)
        case Absent     => colorCatsWithRaw.zipWithIndex.map((_, i) => basePaletteText.toSeq.apply(i % basePaletteText.size))
```

**For `lowerErrorBar`**, replace lines 2436-2441 with:
```scala
val basePaletteErr: Chunk[Style.Color] = themePalette(theme)
val palette: Chunk[Style.Color] =
    if colorCatsWithRaw.isEmpty then Chunk.empty
    else spec match
        case Present(s) => resolvePalette(s, colorCatsWithRaw)
        case Absent     => colorCatsWithRaw.zipWithIndex.map((_, i) => basePaletteErr.toSeq.apply(i % basePaletteErr.size))
```

The `resolvePalette(s, colorCatsWithRaw)` call matches the exact signature `private def resolvePalette[A](spec: ChartSpec[A], categories: Chunk[(String, Any)]): Chunk[Style.Color]` at LOWER:1355 — no change to that function.

### Call-site changes

| Site | Location | Before | After |
|------|----------|--------|-------|
| CS1 | LOWER:1762 | `lowerText(m, rows, xs, ys, markColor, theme)` | `lowerText(m, rows, xs, ys, markColor, theme, spec)` |
| CS2 | LOWER:1763 | `lowerErrorBar(m, rows, xs, ys, markColor, theme)` | `lowerErrorBar(m, rows, xs, ys, markColor, theme, spec)` |
| CS3 | LOWER:3610 | `lowerText(m, rows, xs, ys, markColor, spec.theme)` | `lowerText(m, rows, xs, ys, markColor, spec.theme, Present(spec))` |
| CS4 | LOWER:3612 | `lowerErrorBar(m, rows, xs, ys, markColor, spec.theme)` | `lowerErrorBar(m, rows, xs, ys, markColor, spec.theme, Present(spec))` |

CS1/CS2: `spec` is the `Maybe[ChartSpec[A]]` already in scope in `marksRegion` (line 1713).  
CS3/CS4: `Present(spec)` wraps the concrete `ChartSpec[A]` from `marksRegionWithTransitions`'s parameter (line 3542).

---

## 3. Reproduce-before-fix tests

All tests go in `ChartLowerTest`. Write each failing test first; confirm it fails for the correct reason (fill/stroke is DefaultPalette color, not the colorScale color), then apply the fix.

### L2 — text categorical colorScale (NEW)

Failure mode today: `lowerText` ignores `spec.legendCfg.colorScale`; the palette is resolved via `themePalette(theme)` by index. With no custom `theme.palette`, this yields `DefaultPalette` colors (`#3b82f6` / `#f97316`), not the colorScale colors.

Test shape (mirrors L1 grouped-bar test at ChartLowerTest:488-547):

```scala
// ---- Leaf L2 (GAP-COLOR-TEXT): text with categorical colorScale uses the scale colors ----
"text mark with categorical colorScale uses the scale colors, not DefaultPalette (GAP-COLOR-TEXT)" in {
    val naColor = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))
    val euColor = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))
    val rows = Chunk(
        Sale("Jan", Usd(1000), Region.NA),
        Sale("Feb", Usd(2000), Region.EU)
    )
    val spec = UI.chart(rows)(text(x = _.month, y = _.revenue, label = _.month, color = _.region))
        .yScale(_.linear(0.0, 4000.0))
        .legend(_.colorScale[Region](
            Region.NA -> naColor,
            Region.EU -> euColor
        ))
    val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
    // Collect text elements from the marks group (deepTextsIn collects Svg.Text from all top-level G children).
    val texts = deepTextsIn(root)
    // Two label glyphs, one per row. Sort by x to recover NA/EU encounter order.
    val marksGTexts = texts.filter(t => t.svgAttrs.fill.isDefined)
    assert(marksGTexts.size >= 2, s"Expected at least 2 text glyphs with fill, got ${marksGTexts.size}: $texts")
    val byX = marksGTexts.toSeq.sortBy(t => numOf(t.svgAttrs.x))
    // colorCats encounter order: NA (idx 0), EU (idx 1).
    val fill0 = fillColorOf(byX(0).svgAttrs.fill)
    val fill1 = fillColorOf(byX(1).svgAttrs.fill)
    assert(fill0 == naColor, s"Text glyph 0 (NA) must be naColor $naColor but got $fill0")
    assert(fill1 == euColor, s"Text glyph 1 (EU) must be euColor $euColor but got $fill1")
    // Explicit guard: not DefaultPalette fallback colors.
    assert(fill0 != Style.Color.blue && fill0 != Style.Color.orange,
        s"Text fill 0 must not be DefaultPalette colors; got $fill0")
    assert(fill1 != Style.Color.blue && fill1 != Style.Color.orange,
        s"Text fill 1 must not be DefaultPalette colors; got $fill1")
    // Legend swatch agreement.
    val swatches = legendSwatchRects(root)
    assert(swatches.size == 2, s"Expected 2 legend swatches but got ${swatches.size}")
    val swatchesByY = swatches.toSeq.sortBy(s => coordNum(s.svgAttrs.y).getOrElse(0.0))
    assert(fillColorOf(swatchesByY(0).svgAttrs.fill) == naColor,
        s"Swatch 0 must be naColor $naColor")
    assert(fillColorOf(swatchesByY(1).svgAttrs.fill) == euColor,
        s"Swatch 1 must be euColor $euColor")
}
```

Note: `deepTextsIn` (ChartLowerTest:758) collects `Svg.Text` from depth-2 of the SVG tree. Text mark glyphs and axis tick texts both appear in the tree; the `.fill.isDefined` filter is used above to narrow to mark glyphs (tick texts may also have fill, so verify the count is reasonable). An alternative is to use `marksGroup(root).children.collect { case t: Svg.Text => t }` which restricts to the marks `<g>` only and avoids axis ticks. Either form is acceptable; the marks-group form is more precise.

### L3 — errorBar categorical colorScale (NEW)

Failure mode today: `lowerErrorBar` ignores `spec.legendCfg.colorScale`; stroke comes from `themePalette(theme)` by index. All three sub-shapes (vLine, capLow, capHigh) use the same `stroke` derived from the broken palette, so fixing the palette makes all three agree on the colorScale color simultaneously.

Test shape:

```scala
// ---- Leaf L3 (GAP-COLOR-ERRORBAR): errorBar with categorical colorScale uses the scale colors ----
"errorBar with categorical colorScale uses the scale colors, one stroke per row (GAP-COLOR-ERRORBAR)" in {
    val naColor = Style.Color.hex("#e63946").getOrElse(fail("bad hex naColor"))
    val euColor = Style.Color.hex("#2a9d8f").getOrElse(fail("bad hex euColor"))
    case class EbSale(x: String, mean: Double, lo: Double, hi: Double, region: Region)
    val rows = Chunk(
        EbSale("a", 6.0, 4.0, 8.0, Region.NA),
        EbSale("b", 3.0, 1.0, 5.0, Region.EU)
    )
    val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi, color = _.region))
        .yScale(_.linear(0.0, 10.0))
        .legend(_.colorScale[Region](
            Region.NA -> naColor,
            Region.EU -> euColor
        ))
    val root  = summon[Conversion[ChartSpec[EbSale], Svg.Root]](spec)
    // Each row emits 3 Svg.Line + 1 Svg.Circle; collect all lines and circles.
    val lines   = linesIn(root)   // 3 lines per row -> 6 total
    val circles = circlesIn(root) // 1 circle per row -> 2 total
    assert(lines.size == 6, s"Expected 6 lines (3 per row x 2 rows) but got ${lines.size}")
    assert(circles.size == 2, s"Expected 2 circles (1 per row x 2 rows) but got ${circles.size}")

    // Strokes: all 3 lines for row 0 (NA) must be naColor; all 3 for row 1 (EU) must be euColor.
    // Sort lines by x1 then y1 to group by row.
    def strokeOf(l: Svg.Line): Style.Color = l.svgAttrs.stroke match
        case Present(Svg.Paint.Color(c)) => c
        case other                       => fail(s"Expected stroke Color but got $other")
    // The two rows are at distinct x positions (band scale); partition by x1.
    val (naLines, euLines) = lines.toSeq.partition(l => numOf(l.svgAttrs.x1) < numOf(l.svgAttrs.x2) + 0.0)
    // Simpler: group by x1 pixel value (rows at distinct bands).
    val byX1 = lines.toSeq.groupBy(l => math.round(numOf(l.svgAttrs.x1) * 10).toInt)
    assert(byX1.size == 2, s"Expected 2 distinct x1 groups (one per row), got ${byX1.size}: ${byX1.keys}")
    val lineGroups = byX1.values.toSeq.sortBy(_.map(l => numOf(l.svgAttrs.x1)).min)
    val naGroup    = lineGroups(0)  // x1 smaller (category "a" appears first)
    val euGroup    = lineGroups(1)
    naGroup.foreach(l => assert(strokeOf(l) == naColor, s"NA line stroke must be naColor $naColor but got ${strokeOf(l)}"))
    euGroup.foreach(l => assert(strokeOf(l) == euColor, s"EU line stroke must be euColor $euColor but got ${strokeOf(l)}"))
    // Circles: fill equals the stroke color (lowerErrorBar uses `fill(stroke)` for the marker).
    val circlesByX = circles.toSeq.sortBy(c => numOf(c.svgAttrs.cx))
    val naCircleFill = circlesByX(0).svgAttrs.fill match
        case Present(Svg.Paint.Color(c)) => c
        case other                       => fail(s"Expected circle fill Color but got $other")
    val euCircleFill = circlesByX(1).svgAttrs.fill match
        case Present(Svg.Paint.Color(c)) => c
        case other                       => fail(s"Expected circle fill Color but got $other")
    assert(naCircleFill == naColor, s"NA marker fill must be naColor $naColor but got $naCircleFill")
    assert(euCircleFill == euColor, s"EU marker fill must be euColor $euColor but got $euCircleFill")
    // Legend swatch agreement.
    val swatches    = legendSwatchRects(root)
    assert(swatches.size == 2, s"Expected 2 legend swatches but got ${swatches.size}")
    val swatchesByY = swatches.toSeq.sortBy(s => coordNum(s.svgAttrs.y).getOrElse(0.0))
    assert(fillColorOf(swatchesByY(0).svgAttrs.fill) == naColor, s"Swatch 0 must be naColor")
    assert(fillColorOf(swatchesByY(1).svgAttrs.fill) == euColor, s"Swatch 1 must be euColor")
}
```

Implementation note on L3 line grouping: the x1 coordinate of the vertical line for a row equals `xs.apply(x) + xs.bandwidth/2.0` (after P3's band-centering fix). The two rows ("a" and "b") are in distinct bands so their x1 values differ. Grouping by `math.round(x1 * 10)` clusters the 3 lines (vLine, capLow, capHigh) for each row since all three share the same `px` value. This avoids needing to know the exact pixel.

### L8 co-pin — Absent path byte-identical (Leaf 17 and Leaf 18, existing tests retained)

The existing Leaf 17 test ("text mark uses theme.palette colors, not DefaultPalette, under a custom theme", ChartLowerTest ~line 1814) and Leaf 18 test ("errorBar mark uses theme.palette colors, not DefaultPalette, under a custom theme", ~line 1850) are the Absent-path co-pins. They exercise `lowerText` / `lowerErrorBar` with a custom `theme.palette` but NO `legend(_.colorScale(...))`. After the fix:

- `marksRegion` calls `lowerText(m, rows, xs, ys, markColor, theme, spec)` where `spec` is the `Maybe[ChartSpec[Sale]]`. If the test uses `UI.chart(rows)(...)` without calling `.legend(_.colorScale(...))`, then `spec.flatMap(_.legendCfg.colorScale)` is Absent, so `resolvePalette` in the Absent arm falls through to `spec.theme.palette` which picks up the custom `theme.palette`. The Absent arm of the new block is verbatim-equivalent to the old code.
- These tests do NOT call `.legend(_.colorScale(...))`, so they will hit the Absent arm and keep their existing custom palette colors (`#cc00cc` / `#00cccc`), NOT DefaultPalette (`#3b82f6` / `#f97316`). The assertions must stay green byte-for-byte.

Wait — there is a subtlety. The Leaf 17/18 tests call `lowerText`/`lowerErrorBar` through `marksRegion`, which receives `spec: Maybe[ChartSpec[Sale]] = Absent` ... unless the chart has a spec. Let me check: the test builds `UI.chart(rows)(...).yScale(_.linear(...)).theme(_.palette(...))` and calls `summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)`. The `marksRegion` call inside the conversion does receive the full `ChartSpec`, so `spec` at CS1/CS2 is `Present(s)`. When `Present(s)` and `s.legendCfg.colorScale == Absent`, the new palette block hits `Present(s) => resolvePalette(s, colorCatsWithRaw)`. `resolvePalette` with `Absent` colorScale (lines 1362-1367) falls to `spec.theme.palette match { Present(p) => ... | Absent => DefaultPalette... }`, which reads the custom `theme.palette`. So Leaf 17/18's custom palette still produces `#cc00cc` / `#00cccc`. Byte-identical. Good.

Additionally, INV-004 golden (ChartInvariantsTest) uses a chart without `colorScale`, so `marksRegion` receives `Present(spec)` with `Absent` colorScale; `resolvePalette` falls to the Absent branch; the output is unchanged. The golden stays green.

---

## 4. What is NOT changed

- The `colorCatsWithRaw` collection (lines 2365-2367 in lowerText, 2433-2435 in lowerErrorBar): unchanged.
- The `catIdxText` / `catIdxErr` maps and the `fillColor` / `color` index lookup in the loop: unchanged (they still index into `palette(idx)`; only the palette source changes).
- The `lowerErrorBar` geometry (`px = xs.apply(x) + xs.bandwidth / 2.0` at line 2458, already fixed by P3): unchanged.
- The `theme` parameter position in both signatures: unchanged. It remains the 6th positional param; `spec` is appended as the 7th.
- All other `lowerText` / `lowerErrorBar` callers: there are exactly four call sites (CS1-CS4); all four are updated. The default `spec: Maybe[ChartSpec[A]] = Absent` ensures no other callers (if any were added later) break.

---

## 5. Traps

**Trap 1 — Absent byte-identity must be preserved.** When `spec = Absent` OR `spec = Present(s)` with `s.legendCfg.colorScale = Absent`, the palette output must be byte-identical to the old code. The Absent arm `colorCatsWithRaw.zipWithIndex.map((_, i) => basePalette.toSeq.apply(i % basePalette.size))` is the old code verbatim, and `resolvePalette`'s Absent branch reproduces the same mapping (LOWER:1362-1367 = same `theme.palette` or `DefaultPalette` by index). Confirm Leaf 17 and Leaf 18 stay green after the fix; if they red, the Absent arm logic has drifted.

**Trap 2 — `spec` is `Present` at both `marksRegion` call sites.** The internal conversion always calls `marksRegion` with a fully-formed `ChartSpec`, so `spec` will be `Present(s)` at CS1 and CS2. The default `= Absent` is a safety net for future direct callers, not the normal runtime path. Do not rely on the default being exercised by any current test.

**Trap 3 — `marksRegionWithTransitions` takes `spec: ChartSpec[A]` (not Maybe).** CS3 and CS4 must wrap it as `Present(spec)`. Passing `spec` directly will not type-check. This is different from `marksRegion`'s `spec: Maybe[ChartSpec[A]]`.

**Trap 4 — existing errorBar L21 band-centering tests must stay green.** P3 changed the `px` line in `lowerErrorBar` (2458). P6 does not touch that line. Confirm `lowerErrorIn(root)` lines still have the correct centered x1 values after P6.

**Trap 5 — `deepTextsIn` collects axis tick texts too.** For L2, use `marksGroup(root).children.collect { case t: Svg.Text => t }` instead of `deepTextsIn` to restrict to the marks `<g>`. Axis texts live in sibling `<g>` groups (one per axis), not in the marks group. The marks group is the LAST top-level `Svg.G` child in the root (per `marksGroup` definition at ChartLowerTest:69). Using the marks-group form avoids counting axis tick labels as text-mark glyphs.

**Trap 6 — `linesIn(root)` collects ALL lines including axis gridlines and tick marks.** For L3, axis gridlines and tick-mark lines may appear in the output. Filter to lines without `strokeOpacity` (gridlines use `strokeOpacity`; tick marks use a different stroke pattern) or use `marksGroup(root).children.collect { case l: Svg.Line => l }` to restrict to the marks group.

---

## 6. Verification command

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"
sbt 'kyo-ui/testOnly kyo.ChartLowerTest kyo.ChartAxisTest kyo.ChartInvariantsTest'
```

- `ChartLowerTest`: exercises L2 (NEW), L3 (NEW), L8 co-pin (Leaf 17/18), and L21 co-pin (errorBar band-centering from P3).
- `ChartAxisTest`: existing tests that use text/errorBar color via `marksRegion` (axis-level integration); also covers the INV-004-adjacent assertions.
- `ChartInvariantsTest`: golden (INV-004) must remain byte-identical.

---

## 3-line summary

Confirmed call sites: `marksRegion` line 1762 (`lowerText`) and 1763 (`lowerErrorBar`), `marksRegionWithTransitions` line 3610 (`lowerText`) and 3612 (`lowerErrorBar`) — all four pass `theme` but not `spec`, so neither function can reach `legendCfg.colorScale`.

Signature delta: add `spec: Maybe[ChartSpec[A]] = Absent` as the 7th positional param to both `lowerText` (after `theme`, before `(using Frame)`) and `lowerErrorBar` (same position); replace the by-index palette block in each with a `spec match { Present(s) => resolvePalette(s, cats) | Absent => <old code verbatim> }`; CS1/CS2 pass the in-scope `spec: Maybe[ChartSpec[A]]`; CS3/CS4 pass `Present(spec)`.

Tests: add L2 (`text + categorical colorScale -> fill == colorScale(region) + legend swatch agreement`) and L3 (`errorBar + categorical colorScale -> all 3 sub-shapes share ONE stroke == colorScale(region) + swatch agreement`) as failing-first guards; Leaf 17 and Leaf 18 (Absent path) must stay green byte-for-byte as the co-pin.
