# Phase P4 prep — GAP-COLOR-GROUPEDBAR

## 3-line summary

The bug is in `lowerBarGrouped` at LOWER:1943-1952: the `palette` val matches `Present(_: Sequential)`
to route through `resolvePalette`, but `case _` covers Categorical AND Absent together, so a
Categorical colorScale is silently ignored. The fix: match `Present(_)` (any variant) through
`resolvePalette`; leave `case Absent` on the existing by-index path verbatim. No signature change is
needed: `spec` (line 1925) and `colorCats` (line 1932) are already in scope.

---

## 1. Confirmed palette-resolution lines in `lowerBarGrouped`

File: `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala`

### Signature (lines 1918-1928, confirmed)

```scala
private def lowerBarGrouped[A, X, Y](
    rows: Chunk[A],
    mark: Mark.Bar[A, X, Y],
    colorEnc: Encoding[A, Any],
    layout: Layout,
    xs: Scale,
    ys: Scale,
    spec: Maybe[ChartSpec[A]] = Absent,          // already threaded -- line 1925
    internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent,
    highlight: Maybe[Highlight[A]] = Absent
)(using Frame): Chunk[Svg.SvgElement] =
```

### `colorCats` collection (line 1932, confirmed)

```scala
val colorCats: Chunk[(String, Any)] = collectColorCategoriesWithRaw(rows, colorEnc)
```

`colorCats` is the `Chunk[(String, Any)]` that `resolvePalette` expects as its second argument. It
is collected unconditionally before the `basePalette` / `palette` vals.

### `basePalette` (lines 1938-1940, confirmed)

```scala
val basePalette: Chunk[Style.Color] = spec match
    case Present(s) => themePalette(s.theme)
    case Absent     => DefaultPalette
```

This is the by-index fallback source that must remain unchanged for the Absent arm.

### `palette` -- THE BUG (lines 1943-1952, confirmed)

```scala
val palette: Chunk[Style.Color] = spec match
    case Present(s) =>
        s.legendCfg.colorScale match
            case Present(_: LegendConfig.ColorScale.Sequential) => resolvePalette(s, colorCats)
            case _ =>                                               // BUG: Categorical falls here
                colorKeys.zipWithIndex.map: (_, i) =>
                    basePalette.toSeq.apply(i % basePalette.size)
    case Absent =>
        colorKeys.zipWithIndex.map: (_, i) =>
            basePalette.toSeq.apply(i % basePalette.size)
```

`case _` matches both `Present(_: Categorical)` and `Absent` (of the inner colorScale), so a
Categorical colorScale is dropped and bars get themePalette-by-index instead.

### Scope confirmation -- no signature change needed

All three ingredients required by the fix are already in scope within `lowerBarGrouped`:

- `spec: Maybe[ChartSpec[A]]` -- outer param, line 1925
- `colorCats: Chunk[(String, Any)]` -- line 1932, above the `palette` val
- `resolvePalette(s: ChartSpec[A], categories: Chunk[(String, Any)])` -- existing private method,
  LOWER:1355; already called at line 1946 for the Sequential arm

No signature change is required. This is confirmed by the plan (P4 dependency note: "no signature
change (`spec` already threaded at 1851, `colorCats` already collected at 1858)"; those numbers
refer to a prior read; confirmed current lines are 1925/1932).

### Mirror pattern: `lowerBarStacked` (lines 2044-2046, confirmed)

```scala
val groupPalette: Chunk[Style.Color] = spec match
    case Present(s) => resolvePalette(s, groupCats)
    case Absent     => resolvePaletteFromCfg(groupKeys)
```

`lowerBarStacked` routes the ENTIRE Present arm through `resolvePalette` (which internally branches
on `colorScale` being Categorical/Sequential/Absent). This is the target shape for the fix.

### `resolvePalette` Absent branch (lines 1355-1368, confirmed)

```scala
private def resolvePalette[A](spec: ChartSpec[A], categories: Chunk[(String, Any)]): Chunk[Style.Color] =
    spec.legendCfg.colorScale match
        case Present(LegendConfig.ColorScale.Categorical(fn)) =>
            categories.map { case (_, raw) => fn(raw) }
        case Present(LegendConfig.ColorScale.Sequential(lo, hi, domOv)) =>
            val (domLo, domHi) = domainExtentOf(categories, domOv)
            categories.map { case (_, raw) => sequentialColor(raw, lo, hi, domLo, domHi) }
        case Absent =>
            spec.theme.palette match
                case Present(p) => categories.zipWithIndex.map: (_, i) =>
                        p.toSeq.apply(i % p.size)
                case Absent => categories.zipWithIndex.map: (_, i) =>
                        DefaultPalette.toSeq.apply(i % DefaultPalette.size)
```

The `Absent` branch of `resolvePalette` is byte-identical to the existing `case _` by-index
fallback in `lowerBarGrouped` (both delegate to `theme.palette` with `DefaultPalette` fallback,
same modular index). This is the §0.1 proof: calling `resolvePalette` unconditionally when
`spec = Present(s)` and `colorScale = Absent` yields the same colors as the current by-index arm.

---

## 2. Reproduce-before-fix test design (leaf L1)

### Test home

File: `kyo-ui/shared/src/test/scala/kyo/ChartLowerTest.scala`, class `ChartLowerTest`.

### Test 8 co-pin (leaf L8, line 371 region, confirmed)

The existing "color channel splits a bar into N grouped sub-bands" test (line 371-397) exercises
a grouped bar with NO colorScale. It asserts sub-band widths and x-positions only, not fills. It
must stay green: the Absent path is byte-identical after the fix.

The co-pin Leaf 15 (line 1634, "grouped bar uses theme.palette colors") and Leaf 16 (line 1669,
"grouped bar with default theme uses DefaultPalette blue and orange") are the byte-identity guards
for the Absent/no-colorScale path. Both must stay green without modification.

### New test (leaf L1): grouped bar with categorical colorScale

The test must be placed after the existing "Test 8b" sequential test (currently line 444-486, which
already passes because Sequential already routed through `resolvePalette`).

**Setup:**

- Use the existing `Sale`/`Region` domain types already defined at the top of `ChartLowerTest`.
  `Region` is an enum with `NA`, `EU`, `APAC`.
- Three rows sharing `x = "Jan"` and distinct regions -- this guarantees `dodge = true` (multiple
  distinct colors within one x-band), so the grouped-bar path is exercised.
- Apply `.legend(_.colorScale[Region](Region.NA -> blue, Region.EU -> green, Region.APAC -> red))`
  using `UI.LegendConfig` builder.
- Use distinct, recognizable hex colors that differ from the DefaultPalette entries (`#3b82f6` blue
  and `#f97316` orange) so the assertion is unambiguous.

**Assertion shape (mirrors `ChartAxisTest.scala:216-260` fill assertions):**

1. Read rects from the marks `<g>` via `rectsIn(root)`.
2. Sort rects by x-position to recover NA/EU/APAC order (same as the existing Test 8).
3. For each rect, read fill via `r.svgAttrs.fill` -> `Svg.Paint.Color(c)`.
4. Assert each fill equals the colorScale color for that region (NOT the DefaultPalette color).
5. Assert each region's legend swatch fill equals the same colorScale color (legend<->mark
   agreement). Legend swatches are `rectsIn` results in the frame `<g>` (the legend area);
   isolate them from mark rects by checking that they reside in the legend sub-group.
6. Assert the fills are NOT `Style.Color.blue` / `Style.Color.orange` (the DefaultPalette colors
   that appear today -- this makes the test fail for the RIGHT reason before the fix).

**Why this fails today (right reason):**

The `case _` arm in `lowerBarGrouped`'s `palette` val currently matches
`Present(_: Categorical)` and routes it to the by-index `basePalette` path. With a default theme
and no `theme.palette`, `basePalette = DefaultPalette`, so NA gets `#3b82f6` (blue) and EU gets
`#f97316` (orange) -- the wrong colors. The assertion `fill == colorScaleColor` fails, and the
explicit `!= Style.Color.blue` guard makes the failure message clear.

**Sequential arm (leaf L10, subset):**

A second sub-case (or a separate test) exercises a Sequential colorScale on the same grouped bar
setup. This is partially already covered by the existing "Test 8b" (line 444-486) which tests
Sequential and already passes. That test is the L10 co-pin for the Sequential arm; no new
Sequential test is required for P4 beyond confirming Test 8b stays green after the fix.

---

## 3. Exact fix to apply

Replace lines 1941-1952 (`palette` val) with:

```scala
// Route any Present colorScale (Categorical OR Sequential) through resolvePalette.
// Absent keeps the existing by-index basePalette path, byte-identical (§0.1 proof).
val palette: Chunk[Style.Color] = spec match
    case Present(s) =>
        s.legendCfg.colorScale match
            case Present(_) => resolvePalette(s, colorCats)
            case Absent =>
                colorKeys.zipWithIndex.map: (_, i) =>
                    basePalette.toSeq.apply(i % basePalette.size)
    case Absent =>
        colorKeys.zipWithIndex.map: (_, i) =>
            basePalette.toSeq.apply(i % basePalette.size)
```

Key differences from the current code:

- `Present(_: LegendConfig.ColorScale.Sequential)` -> `Present(_)` (matches both Categorical and
  Sequential)
- The `case _` arm is renamed to `case Absent` to make the exhaustivity explicit

The `basePalette` val (lines 1938-1940) is UNCHANGED. The `colorKeys`/`colorIdxByKey`/`numColors`
vals (lines 1933-1937), the `dodge` calculation (lines 1960-1971), and all per-row positioning
geometry are UNCHANGED. Only the color SOURCE changes.

---

## 4. Traps

### Do not change `numColors`/`dodge`/sub-slot positioning

The dodge geometry (`numColors`, `subW`, `barX` offset) was fixed in a prior campaign and is
correct. The fix only changes which `palette` is used at the color-lookup step (line 1996:
`palette(colorIdx)`). The `colorIdx` indexing into `palette` remains by position in `colorCats`
order, which is exactly the order `resolvePalette` uses -- so Absent output is byte-identical and
the fix is self-consistent.

### `resolvePalette` called unconditionally (alternative form)

The design §0.1 notes that calling `resolvePalette(s, colorCats)` unconditionally (dropping the
inner `colorScale` match entirely) is a valid equivalent because `resolvePalette`'s `Absent` branch
is the same mapping. However, the explicit `case Absent` arm in the outer `spec` match is cleaner
and mirrors the `lowerBarStacked` pattern; use that form. Do NOT call `resolvePalette` from the
`spec = Absent` arm (the function requires a `ChartSpec[A]`, not a `Maybe`).

### Legend swatch readback

When asserting legend-to-mark color agreement, note that `rectsIn(root)` returns all rects in the
SVG including legend swatches. The existing `ChartAxisTest.scala:216-260` pattern distinguishes mark
rects from swatch rects by position or by using `frameRectsIn` vs direct `rectsIn` scoped to the
marks group. Follow that pattern; do not assert swatch colors via a size filter that could break if
the layout changes.

### Leaf 16 (`#3b82f6`/`#f97316`) must stay green

The Leaf 16 test (line 1669) uses `.yScale(_.linear(0.0, 4000.0))` with NO colorScale and asserts
the exact DefaultPalette hex colors appear in the HTML output. After the fix, the Absent arm is
unchanged, so these colors must still appear. If they do not, the Absent arm was incorrectly
modified -- this is the primary regression signal.

---

## 5. Verification command

```
sbt 'kyo-ui/testOnly kyo.ChartLowerTest'
```

This is targeted JVM-only (per-phase verify rule from steering.md). Run it twice: once BEFORE the
fix to confirm the new L1 test fails for the right reason (wrong fill colors), once AFTER to confirm
all tests pass including Leaf 15/16 co-pins.
