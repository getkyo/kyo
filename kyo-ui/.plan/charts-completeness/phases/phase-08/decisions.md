# Phase P8 decisions — GAP-YAXIS-ROTATION + GAP-THEME-FONT

**Date:** 2026-06-05
**Gaps closed:** GAP-YAXIS-ROTATION, GAP-THEME-FONT
**Leaves delivered:** L14 (NEW, passing), L15 (NEW + co-pin, passing), L16 (NEW + co-pin, passing), L17 (CO-PIN, passing)

---

## Implementation decisions

### D1 — effAnchor fallback strategy (byte-identity trap resolution)

The `AxisConfig.default` has `tickAnchor = TextAnchor.Middle`. The Y axis side-default is `End` (left) or `Start` (right). A naive `toSvgAnchor(cfg.tickAnchor)` call would change every existing left Y tick from `End` to `Middle`, breaking 10+ existing tests.

**Decision:** compute `effAnchor` by comparing `cfg.tickAnchor` against the sentinel default `TextAnchor.Middle`. If they differ, the user explicitly set the anchor and we use `toSvgAnchor(cfg.tickAnchor)`. If equal (the default), fall back to the `anchor` val (side-derived `End`/`Start`). This preserves exact byte identity on the default path (L15 co-pin, L17 co-pin, INV-004 golden).

```scala
val effAnchor: Svg.TextAnchor =
    if cfg.tickAnchor != TextAnchor.Middle then toSvgAnchor(cfg.tickAnchor)
    else anchor // side-default: End for left, Start for right
```

### D2 — Local val name shadow eliminated

The old inline block used `val tickLabel: Svg.SvgElement = Svg.text...` which shadowed the private `tickLabel(...)` method added by P2. After replacing the inline block with a call to the method, the local val is renamed to `tickLabelElem`, eliminating the shadow. The element references in the `grid match` block were updated accordingly.

### D3 — Y axis-title withFont wrapping

Both arms of the Y-axis title block (right: `Rotate(90.0)`, left: `Rotate(-90.0)`) are wrapped with `withFont(theme, Svg.text...)`. The `.transform(...)` call is placed before `.apply(lbl)` so `withFont` receives the full `Svg.Text` before the string child is applied. This is a no-op under the default theme (both `fontFamily` and `fontSize` are `Absent`).

### D4 — X axis-title withFont wrapping

`buildXAxis` title block was also missing `withFont`. Wrapped `Svg.text...` in `withFont(theme, ...)` before `.apply(lbl)`. The `tickLabel(...)` call at line 1062 is unchanged: only the title (not the tick labels) required P8 editing in `buildXAxis`.

### D5 — buildLegendItems theme parameter

Added `theme: Theme` as the last value parameter to `buildLegendItems`. The single call site in `buildLegend` passes `spec.theme`. The legend label `Svg.text` construction is wrapped with `withFont(theme, ...)`. A no-op under the default theme.

### D6 — buildSizeLegend theme parameter

Added `theme: Theme` as the last value parameter to `buildSizeLegend`. The call site in `buildLegend` passes `spec.theme`. Both size-legend label `Svg.text` constructions (minLabel and maxLabel) are wrapped with `withFont(theme, ...)`. A no-op under the default theme.

### D7 — buildSequentialLegend local label def

The local `def label(...)` inside `buildSequentialLegend` wraps its `Svg.text` with `withFont(spec.theme, ...)`. `spec` is already available in scope as a parameter to `buildSequentialLegend`. A no-op under the default theme.

---

## Trap resolutions

- **Trap 1 (effAnchor byte-identity):** Resolved via D1. The `TextAnchor.Middle` sentinel comparison is the correct guard. The L15 co-pin test (`left Y tick label keeps text-anchor=end when no anchor is set`) confirms the side-default is preserved.
- **Trap 2 (local val shadow):** Resolved via D2. `val tickLabel` renamed to `val tickLabelElem`; all downstream references updated.
- **Trap 3 (buildXAxis tick label unchanged):** The `tickLabel(...)` call at `buildXAxis` line 1062 was not modified. Only the X-axis title block was touched (D4). The L17 co-pin confirms no regression.
- **Trap 4 (buildLegendItems call sites):** Verified via grep: exactly one call site exists at line 1130 inside `buildLegend`. Updated to pass `spec.theme`.
- **Trap 5 (withFont no-op):** The L16 co-pin test (`default theme adds no font-family or font-size`) and the INV-004 golden in ChartInvariantsTest confirm byte identity on the default-theme path.
- **Trap 6 (GAP-RIGHTY-GRID not touched):** The `!isRight` grid gate at `buildYAxis` was not modified. P11 owns it.
- **Trap 7 (right Y axis):** The same `buildYAxis` serves both axes. The `effAnchor` logic uses the side-derived `anchor` val (`Start` for right), so the right Y axis also benefits from P8. The L14 right-axis sub-test confirms rotation works for right ticks.

---

## Test results

**Before fix (reproduce phase):**
- L14: `Expected a Rotate transform on Y tick label but got Chunk.Indexed()` (no rotation on either left or right Y ticks)
- L15: `Y tick with explicit anchor(Start) must have text-anchor=start but got End` (cfg.tickAnchor not read)
- L16: `Y tick label must carry font-family=monospace but got Absent` (no font applied to Y ticks)

**After fix:**
- ChartAxisTest: 41 tests, 41 passed (0 failed)
- ChartLowerTest: 97 tests, 97 passed (0 failed)
- ChartInvariantsTest: 8 tests, 8 passed (0 failed)
- Total: 146 tests, 146 passed, 0 failed

**Co-pins verified GREEN:**
- L15 co-pin: `left Y tick label keeps text-anchor=end when no anchor is set` — PASS
- L16 co-pin: `default theme adds no font-family or font-size to Y tick, title, or legend` — PASS
- L17: `x tick rotateTicks/anchor/font stay byte-identical after P8` — PASS
- INV-004 golden (ChartInvariantsTest) — PASS

---

## Six touch-points summary

1. **buildYAxis tick-label (lines 909-920):** Replaced inline `val tickLabel: Svg.SvgElement = Svg.text...` with `effAnchor` computation + `val tickLabelElem = tickLabel(...)`. References in `grid match` updated to `tickLabelElem`.
2. **buildYAxis axis-title right arm (lines 932-943):** Wrapped `Svg.text...transform(Rotate(90.0))` with `withFont(theme, ...)`.
3. **buildYAxis axis-title left arm (lines 944-955):** Wrapped `Svg.text...transform(Rotate(-90.0))` with `withFont(theme, ...)`.
4. **buildXAxis axis-title (lines 1082-1089):** Wrapped `Svg.text...` with `withFont(theme, ...)`. Tick-label call at 1062 unchanged.
5. **buildLegendItems (line 1500 + label construction):** Added `theme: Theme` param; wrapped label `Svg.text` with `withFont(theme, ...)`.
6. **buildSizeLegend (line 1215 + minLabel/maxLabel):** Added `theme: Theme` param; wrapped both text labels with `withFont(theme, ...)`.
   **buildSequentialLegend (local `def label`):** Wrapped `Svg.text` with `withFont(spec.theme, ...)`.
