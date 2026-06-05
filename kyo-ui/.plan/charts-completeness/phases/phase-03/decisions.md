# Phase P3 decisions — GAP-ERRORBAR-BANDCENTER

## D-P3-01: Failing test written before the fix (REPRODUCE-BEFORE-FIX)

Two tests were added to `kyo-ui/shared/src/test/scala/kyo/ChartLowerTest.scala` after Test 35:

- **L21-A** "errorBar on a Band x is centered (x1 == band-left + bandwidth/2), not at the left edge (L21)":
  Band x with n=2 categories ["a","b"], slot=280, bandW=252, pad=14. Expected `vLine.x1` for "a" = 200.0 (center). Before fix the test failed: `126.0 was not less than 1.0E-6 vLine x1 for 'a': expected 200.0 but got 74.0`.

- **L21-B** "errorBar on a continuous x is unaffected by the band-centering fix (bandwidth==0 co-pin) (L21)":
  Linear x scale [0,10], two rows at x=2.0 (px=172.0) and x=8.0 (px=508.0). Passes before and after the fix because `bandwidth=0` makes the offset a no-op.

## D-P3-02: One-line fix at ChartLower.scala line 2458

In `lowerErrorBar` inner loop, changed:

```scala
val px     = xs.apply(x)
```

to:

```scala
val px = xs.apply(x) + xs.bandwidth / 2.0
```

This is the same pattern used by `lowerLine` (line 2272), `lowerText` (line 2394), `lowerArea` (line 2535), `lowerPoint` (line 2846), and `lowerRule`. Only `lowerErrorBar` was missing the half-bandwidth offset.

`px` is computed once and referenced at all four sub-shapes (vLine, capLow, capHigh, marker), so centering it at the single assignment point centers all of them. No double-add risk.

## D-P3-03: Test result before and after

Before fix (L21-A run):
- Test "errorBar on a Band x is centered..." FAILED: expected 200.0 but got 74.0.
- Test "errorBar on a continuous x is unaffected..." PASSED.

After fix (impl-testOnly-jvm-001.log):
- Total: 88 tests, 88 succeeded, 0 failed.
- Both L21-A and L21-B passed.
- All pre-existing 86 errorBar, line, bar, text, point, area, legend, and theme tests remained green.
