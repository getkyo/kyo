# Phase P5 prep — Non-stacked area color split + colorScale (`buildSimpleAreaPath`)

**Gap closed:** GAP-COLOR-AREA-SIMPLE  
**File under change:** `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala` (currently 4064 lines)  
**Test file:** `kyo-ui/shared/src/test/scala/kyo/ChartLowerTest.scala`  
**Verification:** `sbt 'kyo-ui/testOnly kyo.ChartLowerTest'`

---

## 1. Current line ranges (fresh read, 4064-line file)

### The `lowerArea` function

```
lowerArea definition:   LOWER:2506-2578
  signature (including defaults):  2506-2515
  mark.y Present arm:              2518
    stack.group Present arm:       2519-2522   (delegates to lowerAreaStacked)
    stack.group Absent arm (NON-STACKED branch):  2523-2573
      point-collection loop:       2525-2541
      empty-guard + path build:    2542-2572
        top edge (CurvePath):      2545-2546
        baseline return + close:   2548-2550
        opacity resolution:        2552-2556
        basePath (.fill(defaultColor) .fillOpacity(opacity)):  2557
        tooltip arm:               2558-2563
        interaction arm:           2564-2572
  mark.y Absent arm (band ribbon): 2574-2576  (delegates to buildBandRibbon)
end lowerArea:          2578
```

The current `lowerArea` signature already carries `spec: Maybe[ChartSpec[A]] = Absent` (line 2513) and `internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent` (line 2514). No signature change is needed to thread `spec` -- it is already in scope inside `lowerArea`.

Key line for `defaultColor` application: **line 2557**
```scala
val basePath = Svg.path.d(pd2).fill(Svg.Paint.Color(defaultColor)).fillOpacity(opacity)
```
This is the single site that must become `fill` from the per-series palette when `mark.color` is Present.

### `lowerAreaWithTransitions`

```
definition:   LOWER:3471-3520
  stacked fast-path (no interaction):  3484-3487
  non-stacked path:                    3489-3518
    rawPaths = lowerArea(...).collect { case p: Svg.Path => p }:  3491-3492
    pathKey fold (keyed area-$markIdx-$seriesIdx):               3496-3517
end lowerAreaWithTransitions: 3520
```

The animated twin calls `lowerArea` at line 3491 and iterates `rawPaths.zipWithIndex` with key `area-$markIdx-$seriesIdx`. When P5 makes `lowerArea` return N paths for a colored area, the fold already handles N items transparently. The comment on line 3495 ("Simple area always produces at most one path; seriesIdx is always 0 here") becomes outdated and must be removed. No other change to `lowerAreaWithTransitions`.

### The `lowerLine` category-split pattern to mirror

```
lowerLine definition:     LOWER:2206-2236
  mark.color Absent arm:    2217-2220  (single series, defaultColor)
  mark.color Present arm:   2221-2235
    collectColorCategoriesWithRaw:  2226
    colorKeys:                      2227
    palette via spec match:         2228-2230
    colorKeys.zipWithIndex.map:     2231-2235
      seriesRows filter:            2232
      strokeColor:                  2233
      lowerLineSeries call:         2235
end lowerLine:            2236
```

The P5 category-split in `lowerArea` mirrors this pattern exactly:
- `collectColorCategoriesWithRaw(rows, colorEnc)` -> categories
- `spec match { case Present(s) => resolvePalette(s, cats); case Absent => DefaultPalette }` for palette
- `keys.zipWithIndex.flatMap { (key, idx) => ... buildSimpleAreaPath(seriesRows, ..., fill, ...) }`

The `flatMap` (not `map`) is needed because `buildSimpleAreaPath` returns `Maybe[Svg.SvgElement]` (Absent when no finite points); `flatMap` + `Maybe.toChunk` or equivalent drops empty results.

### `defaultColor` parameter source in the non-stacked call sites

`lowerArea` receives `defaultColor: Style.Color = DefaultPalette(0)` as a value param (line 2512). It is passed by call sites in `marksRegion` (line ~1741 area branch: `lowerArea(rows, m, layout, xs, ys, markColor)`) and `lowerAreaWithTransitions` (line 3491: `lowerArea(rows, mark, layout, xs, ys, defaultColor)`). When `mark.color` is Absent, the single `buildSimpleAreaPath` call uses this `defaultColor` directly; when Present, `defaultColor` is ignored and per-series palette colors are used instead.

---

## 2. `buildSimpleAreaPath` helper

### Placement

Place immediately before `lowerArea` (i.e., insert starting at line 2506, pushing `lowerArea` down). It is `private` to the object, consistent with all other chart-lowering helpers.

### Signature (per DESIGN §0.5 and §GAP-COLOR-AREA-SIMPLE)

```scala
/** Build one closed area path for a single series from `seriesRows`.
  *
  * Collects finite (px, py) points from `seriesRows` using the same band-centre projection as
  * `lowerArea` (xs.apply(x) + xs.bandwidth / 2.0). Returns Absent when no finite points exist
  * (e.g. all rows have undefined y), so the caller can flatMap over the Maybe and silently omit
  * empty series -- consistent with `lowerArea`'s own empty-guard at 2542.
  *
  * The path is always CLOSED: top edge via CurvePath.append, then lineTo the last x at baseline,
  * lineTo the first x at baseline, then close (INV-019). fillOpacity defaults to 0.7 (§0.5).
  * The `mark.opacity` channel overrides fillOpacity when Present; the channel is sampled from the
  * first row in `seriesRows` (the series-representative row, same as the interaction anchor).
  *
  * `fill` is the resolved fill color (caller handles palette resolution so this helper is
  * color-agnostic). `spec` and `internalHoverRef` are forwarded to `buildInteractionAttrs`
  * unchanged, attaching click/hover handlers to the path (INV-023).
  */
private def buildSimpleAreaPath[A, X, Y](
    seriesRows: Chunk[A],
    mark: Mark.Area[A, X, Y],
    layout: Layout,
    xs: Scale,
    ys: Scale,
    fill: Style.Color,
    spec: Maybe[ChartSpec[A]],
    internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]]
)(using Frame): Maybe[Svg.SvgElement]
```

The body is the current non-stacked arm lines 2525-2572 verbatim, with `defaultColor` replaced by `fill`. Return type is `Maybe[Svg.SvgElement]`: return `Absent` where the current code returns `Chunk.empty` (points.isEmpty guard at 2542), and `Present(withInteraction)` for the success case.

### How callers use it

**Absent-color arm** (byte-identical single-path, unchanged):
```scala
case Absent =>
    buildSimpleAreaPath(rows, mark, layout, xs, ys, defaultColor, spec, internalHoverRef)
        .toChunk   // Maybe -> Chunk (0 or 1 elements)
```

**Present-color arm** (the new split):
```scala
case Present(colorEnc) =>
    val cats    = collectColorCategoriesWithRaw(rows, colorEnc.asInstanceOf[Encoding[A, Any]])
    val keys    = cats.map(_._1)
    val palette = spec match
        case Present(s) => resolvePalette(s, cats)
        case Absent     => DefaultPalette
    keys.zipWithIndex.flatMap: (key, idx) =>
        val seriesRows = rows.filter(r => colorEnc.accessor(r).toString == key)
        val fillColor  = palette.toSeq.apply(idx % palette.size)
        buildSimpleAreaPath(seriesRows, mark, layout, xs, ys, fillColor, spec, internalHoverRef)
            .toChunk
```

`Maybe.toChunk` (or the equivalent `fold(Chunk.empty)(Chunk(_))`) converts `Maybe[Svg.SvgElement]` to a 0-or-1 `Chunk`. This mirrors how lowerLine returns empty per-series paths when the series has no finite points.

---

## 3. Reproduce-before-fix tests (L4 + L9 + L8 co-pin)

Both tests belong in `ChartLowerTest.scala`. The test class already imports `UI.*`, `UI.Ast.*`, `UI.mark.*`, defines `Region`/`Sale`/`Usd`, and has `marksGroup`, `pathsIn`, `frameRectsIn`, `frameTextsIn`. Add `colorOf` either by copy-paste from `ChartAxisTest` (lines 80-82) or by defining it inline in the test block.

### L9 + L4 (reproduce-first failing test -- written BEFORE the fix)

```scala
// ---- L9 + L4 (GAP-COLOR-AREA-SIMPLE, reproduce-before-fix): non-stacked area with color channel ----
// L9: N distinct closed paths, each fill-opacity="0.7" (not one merged blob).
// L4: each per-series path fill == colorScale(series); legend swatch agreement.
// Fails today: lowerArea emits ONE path filled with defaultColor regardless of mark.color.
"non-stacked area with color=_.region emits one closed path per series at fill-opacity 0.7, each colored by colorScale (L4 + L9)" in run {
    val blue  = Style.Color.blue   // #3b82f6 -> colorScale for NA
    val green = Style.Color.green  // #22c55e -> colorScale for EU
    // Two rows: one per region (distinct x values so paths have 1 point each -- still valid closed paths)
    val rows = Chunk(
        Sale("Jan", Usd(1000), Region.NA),
        Sale("Feb", Usd(2000), Region.EU)
    )
    val spec = UI.chart(rows)(area(x = _.month, y = _.revenue, color = _.region))
        .yScale(_.linear(0.0, 4000.0))
        .legend(_.colorScale[Region](Region.NA -> blue, Region.EU -> green))
    val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
    for html <- HtmlRenderer.render(root, Seq.empty)
    yield
        // L9: exactly 2 distinct path elements in the marks group (one per Region)
        val marks = marksGroup(root)
        val areaPaths = marks.children.collect { case p: Svg.Path => p }
        assert(areaPaths.size == 2, s"L9: expected 2 per-series area paths but got ${areaPaths.size}:\n$html")

        // L9: each path is closed (the PathData ends with a Close command)
        areaPaths.zipWithIndex.foreach: (p, i) =>
            p.svgAttrs.d match
                case Absent => fail(s"L9: area path $i has no d attribute")
                case Present(pd) =>
                    val cmds = Svg.PathData.commands(pd)
                    assert(
                        cmds.lastOption.exists { case Svg.PathCommand.Close => true; case _ => false },
                        s"L9: area path $i must be closed (last command == Close) but got $cmds"
                    )

        // L9: each path carries fill-opacity == 0.7
        areaPaths.zipWithIndex.foreach: (p, i) =>
            assert(
                p.svgAttrs.fillOpacity.exists(fo => math.abs(fo - 0.7) < 1e-9),
                s"L9: area path $i must have fill-opacity=0.7 but got ${p.svgAttrs.fillOpacity}"
            )

        // L4: path fills match the colorScale colors (NA=blue, EU=green)
        // Encounter/ordinal order for Region: NA(0) first, EU(1) second.
        def colorOf(fill: Maybe[Svg.Paint]): Style.Color = fill match
            case Present(Svg.Paint.Color(c)) => c
            case other => fail(s"Expected Paint.Color but got $other")
        assert(
            colorOf(areaPaths(0).svgAttrs.fill) == blue,
            s"L4: first area path (NA) must be blue but got ${areaPaths(0).svgAttrs.fill}:\n$html"
        )
        assert(
            colorOf(areaPaths(1).svgAttrs.fill) == green,
            s"L4: second area path (EU) must be green but got ${areaPaths(1).svgAttrs.fill}:\n$html"
        )

        // L4: legend swatches must agree with the mark fills
        // frameRectsIn: first rect is the background, then swatch rects.
        // ChartAxisTest helper pattern (drop background rect):
        val swatches = root.children.flatMap { case r: Svg.Rect => Chunk(r); case _ => Chunk.empty }.drop(1)
        assert(swatches.size >= 2, s"L4: expected at least 2 legend swatches but got ${swatches.size}")
        assert(
            colorOf(swatches(0).svgAttrs.fill) == blue,
            s"L4: NA legend swatch must be blue but got ${swatches(0).svgAttrs.fill}"
        )
        assert(
            colorOf(swatches(1).svgAttrs.fill) == green,
            s"L4: EU legend swatch must be green but got ${swatches(1).svgAttrs.fill}"
        )
    end for
}
```

**Why it fails today:** `lowerArea`'s non-stacked arm collects ALL rows into a single point set and emits `Chunk(withInteraction)` -- exactly one `Svg.Path` filled with `defaultColor` (line 2557). The `mark.color` field is never read in this branch. So `areaPaths.size == 1` (not 2) and the fill is `DefaultPalette(0)` (blue #3b82f6), not the colorScale color for EU.

### L8 co-pin (no-color area unchanged -- written at the same time, must stay green)

```scala
// L8 co-pin: non-stacked area WITHOUT a color channel emits exactly 1 closed path,
// byte-identical to today. The buildSimpleAreaPath refactor must not change the Absent-color arm.
"non-stacked area with no color channel still emits exactly one closed path (L8 co-pin, byte-identical)" in {
    case class Row(x: String, y: Int)
    val rows = Chunk(Row("a", 100), Row("b", 200))
    val spec = UI.chart(rows)(area(x = _.x, y = _.y))
    val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
    val paths = root.children.flatMap {
        case g: Svg.G => g.children.collect { case p: Svg.Path => p }
        case _        => Chunk.empty
    }
    assert(paths.size == 1, s"L8: mark.color=Absent area must emit exactly 1 path but got ${paths.size}")
    assert(
        paths(0).svgAttrs.fillOpacity.exists(fo => math.abs(fo - 0.7) < 1e-9),
        s"L8: the single area path must have fill-opacity=0.7 but got ${paths(0).svgAttrs.fillOpacity}"
    )
    paths(0).svgAttrs.d match
        case Absent => fail("L8: area path must have a d attribute")
        case Present(pd) =>
            val cmds = Svg.PathData.commands(pd)
            assert(
                cmds.lastOption.exists { case Svg.PathCommand.Close => true; case _ => false },
                s"L8: the single path must be closed; last command was ${cmds.lastOption}"
            )
}
```

This test currently passes. After the fix it must STILL pass (the `buildSimpleAreaPath` extraction with `defaultColor` is byte-identical to the current inline body).

---

## 4. Verification command

```
sbt 'kyo-ui/testOnly kyo.ChartLowerTest'
```

Run order: write L9+L4 test first (confirm it FAILS for the right reason -- `areaPaths.size == 2` assertion fires, getting 1), then implement the fix (`buildSimpleAreaPath` helper + `mark.color` dispatch in `lowerArea`), then re-run and confirm green. L8 co-pin must stay green throughout.

---

## 5. Traps

### Trap 1: Absent single-path output must be byte-identical after the refactor

The `buildSimpleAreaPath` helper body is extracted verbatim from lines 2525-2572. The Absent-color arm becomes `buildSimpleAreaPath(rows, mark, layout, xs, ys, defaultColor, spec, internalHoverRef).toChunk` -- same function, same inputs. Any deviation (different opacity default, different close command, omitted interaction attrs) is a regression. Confirm by running the existing area test (Test 3, "area lowers to a closed Svg.Path", line 154) and the L8 co-pin.

### Trap 2: the path must stay CLOSED (baseline return)

The non-stacked area path closes by returning to the baseline: `lineTo(lastX, baseline).lineTo(firstX, baseline).close` (lines 2548-2550). The `buildSimpleAreaPath` body must reproduce this exactly. A fill without a close creates an open shape that SVG renderers may or may not fill -- the Close command is load-bearing.

### Trap 3: category order = encounter/ordinal order from `collectColorCategoriesWithRaw`

`collectColorCategoriesWithRaw` (LOWER:1323-1341) builds categories in the order first encountered in `rows`. For enum types deriving `Plottable` (like `Region`), the Plottable instance sorts by ordinal; for String-keyed types, it is first-encounter order. The area paths must be emitted in this same order, and the legend swatches come from the same `resolvePalette(s, cats)` call with the same `cats` chunk. This guarantees path[0].fill == swatch[0].fill == colorScale(category[0]). Do NOT sort or re-order the categories independently in the Present-color arm.

### Trap 4: `lowerAreaWithTransitions` stale comment at line 3495

Line 3495 says "Simple area always produces at most one path; seriesIdx is always 0 here." After P5, a colored area produces N paths, so seriesIdx ranges 0..N-1. Remove or update this comment. The keying logic itself (`area-$markIdx-$seriesIdx`) is already correct for N > 1 -- no code change needed, just the comment.

### Trap 5: the stacked arm is untouched

The `stack.group` Present arm (lines 2519-2522) delegates to `lowerAreaStacked` and must not be modified. The dispatch order is: `mark.y` match -> `stack.group` match -> `mark.color` match. Only the innermost `mark.color` dispatch is new; the outer matches are unchanged.

### Trap 6: `spec` is already in scope -- no signature change needed

`lowerArea` already accepts `spec: Maybe[ChartSpec[A]] = Absent` (line 2513). The call sites in `marksRegion` and `lowerAreaWithTransitions` already pass `spec` or `Present(spec)`. No signature delta, no call-site updates for this parameter.

---

## 3-line summary

**Current non-stacked area branch:** `lowerArea` lines 2523-2573 (within function 2506-2578), collects all rows into one point set, emits a single `Svg.Path` filled with `defaultColor` at line 2557 -- `mark.color` is never read.

**`buildSimpleAreaPath` home:** private helper placed immediately before `lowerArea` (shift lowerArea down by the helper length); signature `(seriesRows, mark, layout, xs, ys, fill, spec, internalHoverRef)(using Frame): Maybe[Svg.SvgElement]`; body is the current lines 2525-2572 verbatim with `defaultColor` replaced by `fill`.

**`spec` threading:** already present in `lowerArea`'s signature (line 2513) and passed by all call sites -- no signature change required; P5 only adds a `mark.color` dispatch inside the existing `stack.group Absent` arm, calling `buildSimpleAreaPath` once (Absent) or once per series (Present), mirroring `lowerLine` lines 2216-2235.
