# Phase P10 decisions

**Gap closed:** GAP-LEGEND-MARGIN-TEXT-ERRORBAR
**Leaf:** L22 (a, b, c)

## Decisions made

### D1: Exact fix applied

Replaced the two wildcard pattern arms in `buildLayout.hasLegend` (LOWER:284-285) with value-binding
arms that check `m.color.isDefined`, mirroring the existing Bar/Line/Area/Point treatment:

```
// BEFORE:
    case _: Mark.Text[?, ?, ?]     => false
    case _: Mark.ErrorBar[?, ?, ?] => false

// AFTER:
    case m: Mark.Text[?, ?, ?]     => m.color.isDefined
    case m: Mark.ErrorBar[?, ?, ?] => m.color.isDefined
```

No other function was touched. `findColorChannel`, `buildLegend`, `resolvePalette`, `lowerText`,
`lowerErrorBar`, `hasSizeLegend` are all unchanged.

### D2: L22b readback element

Prep suggested using `vLine y1` for the errorBar test. Source inspection (LOWER:2535) confirmed that
`lowerErrorBar` sets `y1=pyLow` and `y2=pyHigh`. For `hi=domain_max`, `pyHigh = ys.apply(hi) = plotY`.
So the correct readback is `min of y2 across vLines`, not y1. The L22b test uses `y2` accordingly.

### D3: Test structure for L22c (co-pin)

L22c verifies that a text mark WITHOUT a color channel still contributes `false` to `hasLegend`
(because `m.color.isDefined == false`), keeping `plotY = 20.0` byte-identical to today. This
confirms the fix does not change behavior for the no-color-channel case.

### D4: No L22d separate test added

Prep described L22d as "reuse existing bar tests - no code needed." The ChartInvariantsTest golden
guard (INV-004) and all existing bar/line/area/point legend tests are included in the verification
run and confirmed green. No separate L22d test was written.

## Verification results

Command: `sbt 'kyo-ui/testOnly kyo.ChartLowerTest kyo.ChartInvariantsTest'`
Result: 108 tests, 0 failed, 0 canceled.

- L22a (text color mark reserves plotY=40): PASS (failed before fix: got 20.0)
- L22b (errorBar color mark reserves plotY=40): PASS (failed before fix: got 20.0)
- L22c (no-color text stays plotY=20): PASS (both before and after fix)
- INV-004 golden (bar-only chart, ChartInvariantsTest): PASS (unchanged)
- All existing bar/line/area/point legend/color tests: PASS

## Before/after plotY

| Scenario | Before fix (plotY) | After fix (plotY) |
|---|---|---|
| text-only color mark + legend | 20.0 | 40.0 |
| errorBar-only color mark + legend | 20.0 | 40.0 |
| text mark without color + no legend | 20.0 | 20.0 (unchanged) |
| bar color mark + legend (co-pin) | 40.0 | 40.0 (unchanged) |
