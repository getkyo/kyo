# Phase 09c Decisions

## Per-item change log

### Item 1: Em-dashes removed (ChartInteractionTest.scala)

File: `kyo-ui/shared/src/test/scala/kyo/ChartInteractionTest.scala`

Four comment lines replaced `—` with `:` rewrite:
- Line 649 before: `// Test 16 (L20): line with highlightSelect — the active series path carries stroke="#000000" (INV-024)`
- Line 649 after:  `// Test 16 (L20): line with highlightSelect: the active series path carries stroke="#000000" (INV-024)`
- Lines 680, 712, 744: same pattern (`—` -> `:`)

Re-grep result: `rg -n "—|–" ChartInteractionTest.scala` returns zero lines. Confirmed.

### Item 2: Self-comparing assertion fixed (ChartAxisTest.scala)

File: `kyo-ui/shared/src/test/scala/kyo/ChartAxisTest.scala`, lines 251-255

Before (always passes, proves nothing):
```scala
assertClose(
    swatches(0).svgAttrs.x.map { case Coord.Num(v) => v; case _ => -1.0 }.getOrElse(-1.0),
    swatches(0).svgAttrs.x.map { case Coord.Num(v) => v; case _ => -1.0 }.getOrElse(-1.0),
    "swatch x sanity"
) // just ensure present
```

After (asserts the horizontal legend flows left-to-right):
```scala
val swatch0x = swatches(0).svgAttrs.x.map { ... }
val swatch1x = swatches(1).svgAttrs.x.map { ... }
assert(swatch1x > swatch0x, "legend items must flow left-to-right")
```

Rationale: the buildLegendItems loop advances `curX` by `SwatchSize + SwatchLabelGap + approxLabelW + itemGap` for each item. For a horizontal (Top) legend, swatch(1).x must be strictly greater than swatch(0).x. This assertion proves the layout loop is moving right, not that the same coordinate is non-negative.

### Item 3: Stale scaladoc corrected (ChartLower.scala lines 10-19)

File: `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala`

Three corrections:
- "for static data" -> "for static or live (reactive) data" (the object handles both via lowerStatic and lowerLive)
- "single internal function" -> "Multiple private[kyo] entry points" (lowerStatic, lowerLive, marksRegionWithTransitions, buildFrame, etc. are all separate entry points)
- "Phase 04 adds: ..." changelog line removed entirely (dev-era internal note, must not ship)

The corrected scaladoc is 7 lines of actual description, accurate for the object as-shipped.

### Item 4: Weak .nonEmpty assertions strengthened

#### ChartLowerTest.scala, Test 16 (area y0/y1 band)

Before: `assert(ps.nonEmpty, "...")`
After: `assert(ps.size == 1, s"... but got ${ps.size}")`

The band form (2 data points with y0/y1) always produces exactly 1 closed ribbon path. `size == 1` is concrete and would catch regressions that emit 0 or 2+ paths.

#### ChartLowerTest.scala, Test 17 (area only-y1, bar sibling)

Before: only `assert(rs.nonEmpty, "bar sibling must still render")`
After: added `assert(pathsIn(root).isEmpty, "area with only y1 (invalid combo) must emit no path elements")`

The test now asserts BOTH sides of the invariant: bar renders, area does not. The original test only checked the bar side; the area-empty guarantee was untested.

#### ChartLowerTest.scala, Test 18 (single y wins)

Before: `assert(ps.nonEmpty, "...")`
After:
```scala
assert(ps.size == 1, s"area with single y must render exactly 1 path but got ${ps.size}")
assert(!ps.toSeq.exists(hasCubicCmd), "area single-y linear form must not contain CubicTo commands...")
```

Note: the single-y area form DOES emit a Close command (it closes to the baseline), so `!hasCloseCmd` would be incorrect. The distinguishing structural property is the absence of CubicTo commands (the linear single-y path is MoveTo+LineTo+LineTo+Close; a band+curve form would emit CubicTo on both edges). The `size == 1` is the primary concrete assertion; the no-cubic check confirms the form is the single-y, not the band form.

#### ChartInteractionTest.scala, Test 10 (stacked area with onSelect)

Before: `assert(interactivePaths.nonEmpty, ...) yield succeed`
After: `assert(interactivePaths.size == 4, ...)`

The test data uses `color = _.revenue.toInt.toString` and `stack = by(_.revenue.toInt.toString)`. The 4 rows have revenues 1000, 500, 2000, 800: all distinct. This produces 4 stack groups, each with its own path and onClick handler. The original comment "2 groups are formed" was misleading; the actual behavior is 4 groups (one per unique revenue value). Asserting `size == 4` precisely pins the one-path-per-group contract.

### Item 5: Test run

Run: `sbt 'kyo-ui/testOnly kyo.ChartLowerTest kyo.ChartAxisTest kyo.ChartInteractionTest kyo.ChartTransitionTest kyo.ChartInvariantsTest'`
Result: 189 tests, 189 passed, 0 failed.
Log: `phases/phase-09c/runs/impl-testOnly-jvm-001.log`

### Item 6: Animated stacked area test guard (P9b addendum)

#### resolvePaletteFromCfg vs resolvePalette investigation

`resolvePaletteFromCfg` (ChartLower.scala:2233) uses `DefaultPalette` by index only. It ignores both `legendCfg.colorScale` and `theme.palette`.

`resolvePalette` (ChartLower.scala:1377) honors in priority order:
1. Categorical colorScale (LegendConfig.ColorScale.Categorical): maps each category raw value via user function
2. Sequential colorScale (LegendConfig.ColorScale.Sequential): interpolates by domain position
3. theme.palette (spec.theme.palette, Present): indexes into user-supplied palette
4. DefaultPalette (final fallback when no colorScale and no theme.palette)

#### lowerAreaStacked with Present(spec)

`lowerAreaStacked` (ChartLower.scala:2853-2855):
```scala
val groupPalette: Chunk[Style.Color] = spec match
    case Present(s) => resolvePalette(s, groupCats)
    case Absent     => resolvePaletteFromCfg(groupKeys)
```

When `spec = Present(s)`, `resolvePalette` is used and honors both colorScale and theme.palette.
When `spec = Absent`, `resolvePaletteFromCfg` is used and falls back to DefaultPalette only.

The P9b fix at `lowerAreaWithTransitions` line 3659 forwards `Present(spec)` into `lowerArea`, which then forwards it to `lowerAreaStacked`. This means the animated stacked area now correctly routes through `resolvePalette`.

#### Tests added

Two tests added in ChartLowerTest after the existing FIX 1 (P9b) non-stacked test:

**Test A: `"animated stacked area honors custom theme.palette colors, matching the static twin (FIX 1b, P9c)"`**
- Uses `.animate(_.ease(300.millis))` + `.theme(_.palette(Chunk(purple, teal)))` on a 2-group stacked area
- Asserts fill contains `#cc00cc` (purple) and `#00cccc` (teal) -- mirrors leaf 19 (static stacked area custom palette test)
- Asserts DefaultPalette `#3b82f6` and `#f97316` do NOT appear
- This proves the `spec=Absent` path (resolvePaletteFromCfg) is not taken

**Test B: `"animated stacked area honors a categorical colorScale, not DefaultPalette (FIX 1b, P9c)"`**
- Uses `.animate(_.ease(300.millis))` + `.legend(_.colorScale[Region](...))` with crimson/indigo mapping
- Asserts fill contains `rgb(220, 38, 38)` (crimson for NA) and `rgb(99, 102, 241)` (indigo for EU)
- Asserts DefaultPalette colors do NOT appear
- This proves `resolvePalette` reaches the Categorical colorScale branch

#### Design note: colorScale for stacked area

`resolvePaletteFromCfg` ignores colorScale because it takes only `categories: Chunk[String]` (key strings) and has no access to `spec`. `resolvePalette` takes the full `ChartSpec[A]` and reads `spec.legendCfg.colorScale`. With `Present(spec)` forwarded, the animated stacked area now correctly honors both colorScale and custom theme.palette, matching the static twin.

Both new tests pass. Total test suite: 189/189 pass.
