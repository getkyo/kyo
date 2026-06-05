# Phase P3 prep — GAP-ERRORBAR-BANDCENTER

## 1. Confirmed source locus

File: `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala` (4011 lines total).

`lowerErrorBar` is defined at line 2425 (`private def lowerErrorBar[A, X, Y](`); the body's
inner loop pattern-match case `(Present(x), Present(y), Present(lo), Present(hi)) =>` reaches
**line 2458**:

```scala
val px     = xs.apply(x)      // line 2458 — MISSING + xs.bandwidth / 2.0
```

`px` is then used verbatim at every sub-shape site (no further transformation):
- vLine `x1(px) ... x2(px)` at lines 2472-2473.
- capLow `x1(px - halfCap) ... x2(px + halfCap)` at lines 2478-2479.
- capHigh `x1(px - halfCap) ... x2(px + halfCap)` at lines 2484-2485.
- marker `cx(px)` at line 2490.

All four sub-shapes consume the single `px` value, so fixing line 2458 centers all of them.
There is no risk of a double-add: `px` is computed once and referenced everywhere; the cap
formula adds/subtracts `halfCap` from `px`, not from `bandwidth`. The fix is exactly:

```scala
val px = xs.apply(x) + xs.bandwidth / 2.0
```

Mirror pattern (confirmed by grep): line/area/text/point all use `xs.apply(x) + xs.bandwidth / 2.0`:
- line: line 2272 (lowerLine inner loop).
- text: line 2394 (lowerText inner loop).
- area: line 2535 (lowerArea vertex loop).
- point: line 2846 (lowerPoint inner loop, grep result).
- rule: lines 3014 / 3030 (rule centering block).

Only `lowerErrorBar` at 2458 is missing the half-bandwidth offset.

---

## 2. Reproduce-before-fix test design (leaf L21)

### Test file

`kyo-ui/shared/src/test/scala/kyo/ChartLowerTest.scala`

(L21 lives in the errorBar Phase-4 block, after Test 35 at line 1234. The existing tests confirm
`linesIn` and `circlesIn` helpers are already in scope, along with `PlotX = 60.0`,
`PlotW = 560.0`, `PlotH = 420.0`, `Baseline = 440.0`, `assertClose`, and `numOf`.)

### Fixture geometry (Band x, n=2 categories)

Use 2 categories `"a"` and `"b"` so both category positions are testable. The Band scale math
with `padding = 0.1` (the Scale.Band default):

```
PlotX  = 60.0
PlotW  = 560.0
n      = 2
slot   = 560.0 / 2          = 280.0
bandW  = 560.0 * 0.9 / 2    = 252.0    (= bandwidth)
pad    = (slot - bandW) / 2  = 14.0

apply("a") = PlotX + 0*slot + pad  =  74.0   (band LEFT edge)
apply("b") = PlotX + 1*slot + pad  = 354.0   (band LEFT edge)

center("a") = apply("a") + bandW/2  =  74.0 + 126.0 = 200.0
center("b") = apply("b") + bandW/2  = 354.0 + 126.0 = 480.0
```

Today `lowerErrorBar` emits `px = apply("a") = 74.0` (left edge); after the fix: `px = 200.0`.
This matches the existing line/text/point tests for the same 2-category geometry (ChartLowerTest
line 135: `px_a = 200.0`, confirmed by comment "band centre").

### Test case A — Band x errorBar (fails today, passes after fix)

```scala
// L21-A: errorBar on a Band x must be centered on the band, not at the left edge.
"errorBar on a Band x is centered (x1 == band-left + bandwidth/2), not at the left edge (L21)" in {
    // Band: n=2 ["a","b"], slot=280, bandW=252, pad=14.
    // apply("a") = 74.0 (left edge); center = 74.0 + 126.0 = 200.0.
    case class EbRow(cat: String, mean: Double, lo: Double, hi: Double)
    val rows = Chunk(EbRow("a", 5.0, 3.0, 7.0), EbRow("b", 5.0, 3.0, 7.0))
    val spec = UI.chart(rows)(errorBar(x = _.cat, y = _.mean, low = _.lo, high = _.hi, capWidth = 10.0))
        .yScale(_.linear(0.0, 10.0))
    val root = summon[Conversion[ChartSpec[EbRow], Svg.Root]](spec)
    val ls   = linesIn(root)
    // 2 rows * 3 lines each (vLine + capLow + capHigh) = 6 lines total.
    assert(ls.size == 6, s"Expected 6 lines (2 rows * 3 each) but got ${ls.size}")
    // Extract the 3 lines for row "a" (emitted first: vLine, capLow, capHigh).
    val vLine  = ls(0) // vertical line for "a"
    val capLow = ls(1) // low cap for "a"
    val halfCap = 5.0  // capWidth=10 / 2
    // Band scale: n=2, slot=280, bandW=252, pad=14. center("a") = 200.0.
    val slot   = 280.0
    val bandW  = 252.0
    val pad    = (slot - bandW) / 2.0          // 14.0
    val center = PlotX + 0 * slot + pad + bandW / 2.0  // 200.0
    // svgAttrs.x1/x2/cx are Maybe[Double] (not Maybe[Coord]); extract directly.
    def dbl(m: Maybe[Double], lbl: String): Double = m match
        case Present(v) => v
        case Absent     => fail(s"$lbl absent")
    // Vertical line x1 and x2 must both be at the band center.
    assertClose(dbl(vLine.svgAttrs.x1, "vLine x1"), center, "vLine x1 for 'a'")
    assertClose(dbl(vLine.svgAttrs.x2, "vLine x2"), center, "vLine x2 for 'a'")
    // Cap lines must be centered (x1 = center - halfCap, x2 = center + halfCap).
    assertClose(dbl(capLow.svgAttrs.x1, "capLow x1"), center - halfCap, "capLow x1 for 'a'")
    assertClose(dbl(capLow.svgAttrs.x2, "capLow x2"), center + halfCap, "capLow x2 for 'a'")
    // Marker circle must be at center.
    val cs = circlesIn(root)
    assert(cs.nonEmpty, "Expected center marker circles")
    assertClose(dbl(cs(0).svgAttrs.cx, "marker cx"), center, "marker cx for 'a'")
}
```

**Why this fails today:** `px = xs.apply("a") = 74.0`. The assertions expect `200.0`; they will
fail with actual values 74.0 (vLine x1/x2), 69.0/79.0 (cap x1/x2), 74.0 (marker cx).

### Test case B — continuous x errorBar co-pin (must stay unchanged)

For a numeric x errorBar, `xs` is a Linear (or similar continuous) scale with `bandwidth = 0.0`.
Adding `0.0` is a no-op, so this test must stay green both before and after the fix.

```scala
// L21-B: continuous-x errorBar x position is unchanged (bandwidth == 0, no-op).
"errorBar on a continuous x is unaffected by the band-centering fix (bandwidth==0 co-pin) (L21)" in {
    // 2 rows at x=2.0 and x=8.0. Linear x scale niceTicks(2,8,5) => 0..10 range.
    case class EbRow(x: Double, mean: Double, lo: Double, hi: Double)
    val rows = Chunk(EbRow(2.0, 5.0, 3.0, 7.0), EbRow(8.0, 5.0, 3.0, 7.0))
    val spec = UI.chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
        .xScale(_.linear(0.0, 10.0))
        .yScale(_.linear(0.0, 10.0))
    val root = summon[Conversion[ChartSpec[EbRow], Svg.Root]](spec)
    val ls   = linesIn(root)
    assert(ls.size == 6, s"Expected 6 lines but got ${ls.size}")
    // x=2.0: linear scale [0,10] -> [60,620]. pixel = 60 + (2/10)*560 = 60 + 112 = 172.0
    // x=8.0: pixel = 60 + (8/10)*560 = 60 + 448 = 508.0
    val px2 = 60.0 + (2.0 / 10.0) * 560.0  // 172.0
    val px8 = 60.0 + (8.0 / 10.0) * 560.0  // 508.0
    // svgAttrs.x1/x2 are Maybe[Double]; extract directly (not via numOf which unwraps Maybe[Coord]).
    def dbl(m: Maybe[Double], lbl: String): Double = m match
        case Present(v) => v
        case Absent     => fail(s"$lbl absent")
    // Vertical line for first row (x=2.0): both x1 and x2 must be 172.0.
    assertClose(dbl(ls(0).svgAttrs.x1, "vLine x1"), px2, "vLine x1 continuous x=2")
    assertClose(dbl(ls(0).svgAttrs.x2, "vLine x2"), px2, "vLine x2 continuous x=2")
    // Vertical line for second row (x=8.0): x1/x2 must be 508.0.
    assertClose(dbl(ls(3).svgAttrs.x1, "vLine x1 x8"), px8, "vLine x1 continuous x=8")
    assertClose(dbl(ls(3).svgAttrs.x2, "vLine x2 x8"), px8, "vLine x2 continuous x=8")
}
```

This co-pin test must pass BEFORE the fix (it verifies today's behavior is correct for
continuous x) and must continue to pass after the fix (the `+ 0.0` is a no-op).

### Concrete expected pixel summary

| Category | `xs.apply(x)` today | Expected center (after fix) | Delta |
|----------|--------------------|-----------------------------|-------|
| "a" (i=0) | 74.0 | 200.0 | +126.0 |
| "b" (i=1) | 354.0 | 480.0 | +126.0 |

The delta equals `bandW / 2 = 252.0 / 2 = 126.0` in both cases. The center pixel `200.0` for
`"a"` is the same value used by the existing two-point line test (ChartLowerTest line 135,
`px_a = PlotX + 0 * slot + pad + bandW / 2 // 200.0 (band centre)`), confirming
cross-mark co-alignment after the fix.

### SvgAttrs attribute types for x1 / x2 / cx

Confirmed by reading `Svg.scala` `SvgAttrs` case class:
- `x1: Maybe[Double] = Absent` (line 352)
- `x2: Maybe[Double] = Absent` (line 354)
- `cx: Maybe[Double] = Absent` (line 347)

These are plain `Maybe[Double]`, NOT `Maybe[Coord]`. The `numOf` helper (which unwraps
`Maybe[Coord.Num]`) does NOT apply. Use direct extraction instead:

```scala
def doubleOf(m: Maybe[Double], label: String): Double = m match
    case Present(v) => v
    case Absent     => fail(s"Expected a value for $label but got Absent")
```

Or inline: `ls(0).svgAttrs.x1.getOrElse(fail("x1 absent"))`.

The test assertions in Section 2 above that call `numOf(vLine.svgAttrs.x1)` must use
`doubleOf` or an equivalent `Maybe[Double]` extractor instead. The `numOf` helper is correct
for `Svg.Rect.svgAttrs.x` (which is `Maybe[Coord]`) but NOT for `Svg.Line.svgAttrs.x1`.

---

## 3. Svg.Line attribute access verification

Check that `Svg.Line.svgAttrs.x1` is `Maybe[Coord]` (same as `Svg.Rect.svgAttrs.x`):

```
grep -n "case class Line\|x1\|x2\|SvgLine" kyo-ui/shared/src/main/scala/kyo/Svg.scala | head -20
```

The existing Test 31 and Test 34 both do `ls.size` checks but do not assert `x1`/`x2` pixel
values. The impl agent must verify the exact field name on `Svg.LineSvgAttrs` before writing
the assertion. If `x1` is `Maybe[Coord.Num]` (matching `Svg.Rect.x`), `numOf` applies directly.
If it is a plain `Double`, use the value directly (no `numOf` wrapper needed).

---

## 4. One-line fix

In `lowerErrorBar` at line 2458, change:

```scala
val px     = xs.apply(x)
```

to:

```scala
val px = xs.apply(x) + xs.bandwidth / 2.0
```

No other line changes in `lowerErrorBar`. The four sub-shapes (vLine, capLow, capHigh, marker)
already all reference `px` — centering `px` centers them all. The caps use `px +/- halfCap`,
which correctly remains symmetric around the centered `px`.

**No double-add risk:** `xs.bandwidth / 2.0` is added exactly once to `px`. The capLow/capHigh
offset by `halfCap` is applied to the already-centered `px`, not to `bandwidth` again.

---

## 5. Verification command

```
sbt 'kyo-ui/testOnly kyo.ChartLowerTest'
```

This is the targeted JVM-only gate specified in the plan for P3. It covers both the new L21
failing test (must go from RED to GREEN) and the continuous-x co-pin (must stay GREEN before
and after the fix). No other test files need to run for this phase.

---

## 6. Traps and edge cases

1. **`px` is computed once, used four times.** Centering it at the single assignment point (line
   2458) is sufficient. Do not add `+ xs.bandwidth / 2.0` at each of the four call sites; that
   would be correct but redundant and could diverge in the future.

2. **No-op on continuous x.** `Scale.Linear.bandwidth = 0.0`, `Scale.Log.bandwidth = 0.0`, etc.
   The fix is safe to apply unconditionally; it does not require a `bandwidth > 0` guard.

3. **Cap symmetry is preserved.** After `px = apply(x) + bw/2`, the cap formula
   `px - halfCap` / `px + halfCap` remains symmetric around the centered position. The caps
   will now visually straddle the band center rather than the band left edge.

4. **P6 will also edit `lowerErrorBar`** (to add a `spec` param for colorScale). P3 is
   deliberately placed before P6 so the two edits are sequential and non-conflicting. Do not
   preemptively add the P6 `spec` param here.

5. **`linesIn` in the test file** collects `Svg.Line` elements ONE level deep inside top-level
   `Svg.G` children. It does NOT recurse into nested `Svg.G > Svg.G` structures. Axis gridlines
   and tick lines are rendered inside nested groups (confirmed by ChartLower axis builders
   wrapping elements in multiple `Svg.G` levels), so they are NOT picked up by `linesIn`.
   The existing Test 34 (line 1228-1231) confirms: 1 row -> exactly 3 lines (no spurious axis
   extras), so counting 6 for 2 rows is reliable. No change to `linesIn` needed.

---

## Summary (3 lines)

The missing centering is at **line 2458** of `ChartLower.scala` (`val px = xs.apply(x)` inside
`lowerErrorBar`'s inner loop); the fix is `+ xs.bandwidth / 2.0`, which is a no-op on continuous
x. The Band-x test fixture uses 2 categories with n=2 Band geometry (slot=280, bandW=252,
pad=14), so the expected centered x for category `"a"` is **200.0** (today the mark renders at
74.0, the left edge). Both the vertical line x1/x2 and the marker cx must equal 200.0 after the
fix; the continuous-x co-pin (bandwidth=0) must stay unchanged.
