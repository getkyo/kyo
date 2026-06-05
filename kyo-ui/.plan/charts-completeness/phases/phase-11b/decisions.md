# Phase 11b decisions

## Scope

Test-hardening only. Closes 3 WARN findings from the P11 audit
(`kyo-ui/.plan/charts-completeness/end/audit-p11-rightaxis.md` Area 5).
No source changes. All edits in `kyo-ui/shared/src/test/scala/kyo/ChartAxisTest.scala`.

---

## W1 -- L13a right-grid count + y-position (04-invariants L13)

**Before:** sole assert was `gridLines.nonEmpty`. If any gridlines were emitted, the test
passed regardless of count or positions.

**After:** asserts `gridLines.size == 4` and checks all four y-positions via `assertClose`.

**Count derivation:** right axis domain is `growthPct` in `[5.0, 20.0]`, default
`tickCount=5`, `nice=true`.

1. `niceTicks(5.0, 20.0, 5)`: `rawStep=3.75`, `magnitude=1.0`, `residual=3.75` (<=5.0 ->
   `niceUnit=5.0`), `step=5.0`. Loop from 5.0: `[5, 10, 15, 20]` = 4 values.
2. `fitLinear` snaps: `snappedLo=5.0`, `snappedHi=20.0` (already aligned). `niceStep=5.0`.
3. `Linear.ticks(5)`: `req=niceTicks(5,20,5)=[5,10,15,20]`, `reqStep=5.0`, `span=15.0`,
   `reqLandsOnMax: round(15/5)*5=15 == 15` -> true -> `emit(5.0)`.
   `count=round(15/5)=3` -> `Chunk(0..3)` -> 4 ticks: [5.0, 10.0, 15.0, 20.0].

**4 ticks -> 4 gridlines.** Not 5 (the tick labeled "10" in L12a sits at a different domain
`[10,20]` which gives 3 ticks there; here the domain is `[5,20]`).

**Y-positions:** `Linear(5.0, 20.0, rangeLo=440, rangeHi=20)`:
- `apply(5.0)  = 440.0`
- `apply(10.0) = 440 + (10-5)/(20-5) * (20-440) = 440 - 140 = 300.0`
- `apply(15.0) = 440 + (10/15) * (-420) = 440 - 280 = 160.0`
- `apply(20.0) = 20.0`

Asserted via `assertClose` with `assertClose` tolerance (1e-3).

---

## W2 -- L12a co-pin: `case None => succeed` -> `case None => fail(...)` (both ticks)

**Before:** if niceTicks did not emit the "10" or "20" label, the corresponding
`case None => succeed` branch made the test pass silently. The pixel assertion at y=440 /
y=20 could be skipped entirely.

**After:** both `case None` branches replaced with `case None => fail(...)` including the
label text and the full `rightTickLabels.map(_.children).toList` for diagnosis.

**Why the ticks are always present:** `niceTicks(10.0, 20.0, 5)` produces step=5 ->
`[10, 15, 20]` (3 ticks). "10" is the first value (loop starts at `min=10`) and "20" is
the last (loop stops when `t > 20 + 5*1e-9`). Both endpoints are always in the output.

---

## W3 -- L19b independence: `case None => succeed` -> `case None => fail(...)`

**Before:** the discriminating pixel assert for tick "0.5" at y=230 was behind
`case None => succeed`. If the tick label text did not match, the independence property
was not verified.

**After:** `case None` replaced with `case None => fail(...)` including the full
`rightTicks.map(_.children).toList` for diagnosis.

**Why "0.5" is always present:** right scale is `yScaleRight(_.linear(0.0, 1.0))`.
`ov.flatMap(_.kind) = Present(ScaleKind.Linear(0.0, 1.0))` -> `useNice=false`, domain
fixed `[0.0, 1.0]`. `Scale.fit(Linear, Continuous(0,1), 440, 20, nice=false)` -> no
`niceStep`, falls back in `ticks(5)` to `niceTicks(0.0, 1.0, 5)`.

`niceTicks(0.0, 1.0, 5)`: `rawStep=0.25`, `magnitude=0.1`, `residual=2.5` (<=5.0 ->
`niceUnit=5.0`), `step=0.5`. Loop: `[0.0, 0.5, 1.0]` (3 ticks). "0.5" is always present.

`apply(0.5) = 440 + 0.5 * (20-440) = 440 - 210 = 230.0`.

---

## Test run result

`sbt 'kyo-ui/testOnly kyo.ChartAxisTest kyo.ChartInvariantsTest'`

```
Tests: succeeded 58, failed 0, canceled 0, ignored 0, pending 0
All tests passed.
```

Log: `phases/phase-11b/runs/impl-testOnly-jvm-001.log`
