# Phase P5 decisions — GAP-COLOR-AREA-SIMPLE

## D-1: Test colors chosen to avoid false-positive under the single-path bug

**Context:** The failing test (L4+L9) needed colors that produce an unambiguous failure. `DefaultPalette(0)` is `Style.Color.blue` (#3b82f6). Using `blue` for the NA colorScale color would make the single-path bug look like a success for the NA color assertion (the single defaultColor path IS blue). The prep's test used `blue` for NA, which would confuse the assertion.

**Decision:** Used `Style.Color.red` (#ef4444) for NA and `Style.Color.purple` (#a855f7) for EU. Both differ from `DefaultPalette(0)=blue` and from each other, so the size assertion (`areaPaths.size == 2`) is the primary gate (clear failure: got 1, expected 2), and the color assertions also fail unambiguously once the count passes.

## D-2: `buildSimpleAreaPath` body restructured as `mark.y match` (not `return`)

**Context:** The prep signature uses `val yEnc = mark.y.getOrElse(return Absent)` which is a non-local return from a potential lambda context. Scala 3 disallows non-local returns in general.

**Decision:** Structured `buildSimpleAreaPath` as `mark.y match { case Absent => Absent; case Present(yEnc) => ... }`. This is cleaner and avoids any non-local return issue. The `Absent` arm of the match is dead code in practice (the helper is called only from the `Present(yEnc)` arm of `lowerArea`), but it makes the function total and safe. No behavior change.

## D-3: `lowerArea` stacked arm: bound `yEnc` from the outer match

**Context:** The prep's stacked arm had `val yEnc = mark.y.getOrElse(return Chunk.empty)` (same non-local-return trap). The outer `mark.y match` already pattern-matches `Present(yEnc)`, so `yEnc` is in scope in the arm bodies.

**Decision:** Changed outer match to `case Present(yEnc) =>` (binding `yEnc`) and passed it directly to `lowerAreaStacked`. No redundant inner match or `getOrElse`.

## D-4: Legend swatch assertion uses `Svg.Line` with `strokeWidth == px(2.0)`, not `legendSwatchRects`

**Context:** `legendUsesLineSwatch` returns `true` for `Mark.Area` with a color channel, so the categorical legend emits `Svg.Line` swatches (not `Svg.Rect`). The `legendSwatchRects` helper in ChartLowerTest filters for 12x12 Svg.Rect elements and finds 0 for area marks.

**Decision:** The L4 legend swatch assertion collects `root.children.collect { case l: Svg.Line if strokeWidth == px(2.0) }`. This identifies the legend line swatches reliably (axis lines don't carry `strokeWidth(2.0)`; only legend swatches do). Then checks `l.svgAttrs.stroke` for the colorScale color.

## D-5: Category order: encounter/ordinal order preserved

**Context:** `collectColorCategoriesWithRaw` sorts enum values by ordinal (`Region.NA.ordinal == 0`, `Region.EU.ordinal == 1`). The test rows are `[NA, EU]` so encounter order matches ordinal order.

**Decision:** No explicit sort or re-order in the Present-color arm of `lowerArea`. The order emerges from `collectColorCategoriesWithRaw` (which already sorts by ordinal). This mirrors `lowerLine` exactly.

## D-6: Stale comment in `lowerAreaWithTransitions` updated

**Context:** Line 3546 said "Simple area always produces at most one path; seriesIdx is always 0 here." After the fix, a colored non-stacked area emits N paths.

**Decision:** Updated to: "A non-stacked area with a color channel produces one path per category; seriesIdx tracks each." No code change, comment only.

## Verification summary

- Failing leaf before fix: "non-stacked area with color=_.region emits one closed path per series at fill-opacity 0.7, each colored by colorScale (L4 + L9)" -- FAILED with `areaPaths.size == 1` (expected 2).
- After fix: leaf PASSES (2 paths, each with correct fill, each closed at fill-opacity 0.7, legend swatches match).
- L8 co-pin ("non-stacked area with no color channel still emits exactly one closed path"): PASSES before and after.
- INV-004 golden (ChartInvariantsTest): PASSES unchanged.
- Full suite: 204 tests across ChartLowerTest, ChartInvariantsTest, ChartAxisTest, ChartTransitionTest, ChartInteractionTest, ChartSpecTest -- 204/204 passed, 0 failed.
