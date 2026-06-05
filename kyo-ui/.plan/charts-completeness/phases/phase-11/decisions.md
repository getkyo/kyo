# Phase P11 decisions: GAP-RIGHTY-SCALE + GAP-RIGHTY-GRID

## Implementation summary

Two gaps closed: GAP-RIGHTY-SCALE (hardcoded right y-axis scale) and GAP-RIGHTY-GRID (!isRight grid gate).

---

## D1: Test design for L12a CO-PIN

**Issue discovered during test writing:** The original prep note's L12a comment said "right pixel ~230 for growthPct=10", quoting the formula `440 + (10/20)*(20-440) = 230` (domain [0,20]). But the actual yRightExtent for a Line mark with values [10.0, 20.0] gives `Extent.Continuous(10.0, 20.0)` (Line marks do NOT apply ensureZero). With `nice=true`, the domain stays [10,20] (niceTicks(10,20,5)=step5, snapped to [10,20] already aligned). So `Scale.Linear(10,20,440,20).apply(10) = 440` (domain min maps to rangeLo), not 230.

**Decision:** The L12a CO-PIN asserts:
- Tick labeled "10" (domain min) at y=440 (rangeLo=plotBaseline) -- confirming byte-identity
- Tick labeled "20" (domain max) at y=20 (rangeHi=plotY)

This is the actual byte-identical behavior (the old hardcoded Scale.fit(Linear, [10,20], 440, 20, nice=true) == resolveYScale with Absent override).

The original Test 6 (line 270) uses the formula `440 + (10/20)*(20-440) = 230` as a conceptual "expected" to verify the two axes differ, not as an assertion about the actual rendered tick. That test continues to pass unchanged.

---

## D2: resolveYScale extraction as a nested def

**Issue:** The `effectivePad` and `padExtent` helpers are local defs inside `resolveAllScales`. Extracting `resolveYScale` as a top-level private method would require moving or duplicating those helpers.

**Decision:** `resolveYScale` is a nested `def` inside `resolveAllScales`, closing over `effectivePad` and `padExtent`. This avoids duplication and keeps the helpers co-located with their single use site. The signature matches the design spec exactly: `(ext, extNoZero, ov, axisCfg, rangeLo, rangeHi): Scale`.

---

## D3: buildYAxis default param for drawGrid

**Design spec says:** add `drawGrid: Boolean` param. Adding it as `drawGrid: Boolean = false` preserves binary compatibility for any call site not yet updated (default = false = no gridlines). All 3 callers (buildFrame, buildStaticFrameLive, buildReactiveRegion) now pass explicit `leftDrawGrid` and `rightDrawGrid` values. The default exists only as a safety net; no call site relies on it.

---

## D4: L13b grid count assertion

The test asserts `gridLines.size == 5` for `yAxis(_.grid)` with data `revenue=[1000,2000]`. Computation: `yLeftExtent` for Bar marks applies `ensureZero`, giving `Extent.Continuous(0, 2000)`. `niceTicks(0, 2000, 5)` gives step=500, ticks=[0,500,1000,1500,2000] -> 5 ticks -> 5 gridlines. Left wins, so right grid is suppressed -> total = 5. Test passes.

---

## D5: yRightExtentNoZero added

New private helper mirroring `yLeftExtentNoZero` for Axis.Right marks. Required by the Log arm of `resolveYScale`. The helper filters positive values from Bar, Line, Point, Text, and ErrorBar marks on Axis.Right.

---

## Public surface diff

**UI.scala:**
- `ChartSpec[A]` case class: new field `yScaleRightOverride: Maybe[ScaleOverride]` after `yScaleOverride`
- `Builder.apply` factory: `yScaleRightOverride = Absent` added after `yScaleOverride = Absent`
- Extension method `yScaleRight` added after `yScale` with 16-line scaladoc (within 8-35 line bound)

**ChartLower.scala (internal):**
- New `yRightExtentNoZero` private helper (~55 lines)
- `resolveAllScales` gains 2 new params with defaults: `yRightOverride: Maybe[ScaleOverride] = Absent` and `yAxisRightCfg: AxisConfig = AxisConfig.default`
- Nested `resolveYScale` def extracted from the left Y arm and shared for both axes
- All 4 `resolveAllScales` call sites updated to pass `spec.yScaleRightOverride` and `spec.yAxisRightCfg.getOrElse(AxisConfig.default)`
- `buildYAxis` gains `drawGrid: Boolean = false` param; grid gate changes from `if cfg.showGrid && !isRight` to `if drawGrid`
- All 3 `buildYAxis` callers compute `leftDrawGrid`/`rightDrawGrid` and pass them

---

## Failing-then-passing tests

| Test name | Before | After | Why |
|-----------|--------|-------|-----|
| L11a: yScaleRight(_.log) resolves right scale as Log kind | compile error (yScaleRight not found) | PASS | yScaleRight + resolveYScale routing |
| L11b: yScaleRight(_.log) projects right datum at log pixel | compile error (yScaleRight not found) | PASS | resolveYScale Log arm used for right |
| L12a CO-PIN: default right scale tick pixels | PASS (but with corrected assertion) | PASS | byte-identity confirmed |
| L12b: default right scale resolves as Linear (CO-PIN) | compile error | PASS | resolveYScale default arm = Linear |
| L13a: yAxisRight(_.grid) emits right gridlines | FAIL (gridLines.isEmpty) | PASS | drawGrid gate fixes !isRight block |
| L13b: both axes grid = only left count (left-wins) | PASS (already 5 left gridlines) | PASS | rightDrawGrid=false when leftDrawGrid=true |
| L19b: yScale(_.log)+yScaleRight(_.linear) independence | compile error | PASS | disjoint fields, independent resolution |
