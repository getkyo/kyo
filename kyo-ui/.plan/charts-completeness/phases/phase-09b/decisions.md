# Phase P9b — decisions

## FIX 1: animated non-stacked area drops colorScale (FAIL-1)

**Root cause.** `lowerAreaWithTransitions` has `spec: ChartSpec[A]` in scope but called `lowerArea(rows, mark, layout, xs, ys, defaultColor)` at two sites (stacked arm and non-stacked rawPaths arm), passing no `spec` argument, so the optional parameter defaulted to `Absent`. The non-stacked color-channel arm in `lowerArea` then resolved the palette via `case Absent => DefaultPalette`, ignoring any explicit colorScale on the spec. Result: an animated non-stacked area with `color=_.cat` and `.legend(_.colorScale(...))` rendered DefaultPalette colors, while the static path (P5) and the animated line twin (`lowerLineWithTransitions` FIX B) honored the colorScale.

**Fix.** Forward `Present(spec)` into both `lowerArea` calls inside `lowerAreaWithTransitions`:
- Stacked arm (line 3659): `lowerArea(rows, mark, layout, xs, ys, defaultColor, Present(spec))`
- Non-stacked rawPaths arm (line 3667): `lowerArea(rows, mark, layout, xs, ys, defaultColor, Present(spec)).collect:`

This is the direct mirror of `lowerLineWithTransitions` FIX B, which already resolves per-series colors via `resolvePalette` using the real `spec`.

**Highlight/interaction params.** `lowerArea` also accepts `internalHoverRef` and `highlight` with defaults. These are intentionally left at their defaults (`Absent`) inside the transitions path. Per design §0.4, highlight-during-animation is excluded from scope; the animated paths handle animation geometry only. The non-animated fallback at line 3777-3790 already threads `Present(spec)`, `Absent` (hoverRef), and `resolveHighlight(Present(spec))` correctly.

**Byte-identity.** A non-stacked area WITHOUT a color channel (`mark.color = Absent`) enters the `Absent =>` arm in `lowerArea` which calls `buildSimpleAreaPath(..., defaultColor, spec, ...)` once. With `spec = Present(...)` and no colorScale set, `buildSimpleAreaPath` is identical: it only uses `spec` for interaction attrs and passes it to `buildInteractionAttrs`, which is a no-op when `animateCfg.enabled` is false (animated charts have interaction disabled per design §0.4). The L8 co-pin test confirms the single-path byte-identical behavior.

**Test added (reproduce-before-fix, stays as regression guard):**
- `ChartLowerTest`: "animated non-stacked area with categorical colorScale honors the scale colors (FIX 1, P9b)"
  - Confirmed FAIL before fix: `"[#3b82f6]" did not equal "[rgb(6, 182, 212)]"` (DefaultPalette blue instead of colorScale cyan)
  - Confirmed PASS after fix.
  - Before fills: `#3b82f6` (DefaultPalette blue), `#f97316` (DefaultPalette orange)
  - After fills: `rgb(6, 182, 212)` (cyan), `rgb(245, 158, 11)` (amber)
  - Anti-fallback guards assert DefaultPalette colors are absent.

**Call-site diff summary.** Two call sites changed in `lowerAreaWithTransitions`:
- Stacked: `lowerArea(rows, mark, layout, xs, ys, defaultColor)` -> `lowerArea(rows, mark, layout, xs, ys, defaultColor, Present(spec))`
- Non-stacked: `lowerArea(rows, mark, layout, xs, ys, defaultColor).collect:` -> `lowerArea(rows, mark, layout, xs, ys, defaultColor, Present(spec)).collect:`

---

## FIX 2: missing L10 Sequential regression guards (FAIL-2)

**Root cause.** Plan `05-plan.md:354-357` and `04-invariants.md:151` (L10) require a Sequential-colorScale regression guard test for P4 (grouped bar), P5 (non-stacked area), P6 (text and errorBar). None of these tests existed after P4/P5/P6 were committed. The Sequential code path in `resolvePalette` was already functional; the gap was tests that lock the behavior.

**Tests added (regression guards; pass on first run).**

All four tests use `Sequential(low=Style.Color.black, high=Style.Color.white)` with two data rows at domain endpoints `0.0` and `100.0`. Since the auto-derived domain equals `[0.0, 100.0]` when data values are exactly `{0.0, 100.0}`, the interpolation at t=0 gives `lerpColor(black, white, 0.0) = rgb(0,0,0)` and at t=1 gives `rgb(255,255,255)`. These exact endpoint colors are asserted, giving concrete hex-equivalent values with no floating-point ambiguity.

Sequential legend renders a single continuous gradient swatch (not per-category swatches), so legend swatch assertions per-category are not applicable to Sequential tests. The gradient swatch is already covered by INV-028 Leaf 16. The L10 assertions are on the mark fills only.

**Tests:**
1. `"grouped bar with Sequential colorScale honors the scale colors (L10, P9b)"` - ChartLowerTest
   - Asserts: low-value bar fill == `Style.Color.rgb(0,0,0)`, high-value bar fill == `Style.Color.rgb(255,255,255)`, fills differ, no DefaultPalette entry.
2. `"non-stacked area with Sequential colorScale honors the scale colors (L10, P9b)"` - ChartLowerTest
   - Asserts: two area paths, low-value path fill == `rgb(0,0,0)`, high-value path fill == `rgb(255,255,255)`, fills differ, no DefaultPalette entry.
3. `"text mark with Sequential colorScale honors the scale colors (L10, P9b)"` - ChartLowerTest
   - Asserts: text glyph fills match domain-endpoint sequential colors, fills differ, no DefaultPalette entry.
4. `"errorBar with Sequential colorScale honors the scale colors (L10, P9b)"` - ChartLowerTest
   - Asserts: all 6 lines (3 per row) and 2 circle fills match domain-endpoint sequential colors, fills differ, no DefaultPalette entry.

**Final run.** `sbt 'kyo-ui/testOnly kyo.ChartLowerTest kyo.ChartInvariantsTest'` -> 113 tests, 0 failed.
INV-004 golden (ChartInvariantsTest) unchanged. All prior co-pins (L1/L2/L3/L4/L8/L9/L15/L16/L17/L18/L22) green.
