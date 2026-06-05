# Phase P11 prep: right y-axis scale overrides + right-axis grid

Gaps: GAP-RIGHTY-SCALE (major) + GAP-RIGHTY-GRID (minor).
Files: `kyo-ui/shared/src/main/scala/kyo/UI.scala` + `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala`.
Tests: `kyo-ui/shared/src/test/scala/kyo/ChartAxisTest.scala` + `kyo-ui/shared/src/test/scala/kyo/internal/ScaleTest.scala`.

---

## 1. LOCKED public API (02-public-api §2.1, candidate B — confirmed YES by user)

### 1a. `yScale` mirror location (the precedent to replicate 1:1)

The extension block that owns all chart builders lives at **UI.scala:2363**:

```scala
extension [A](spec: ChartSpec[A])
```

`yScale` is defined at **UI.scala:2394-2396**:

```scala
/** Overrides the y-axis scale using a builder lambda. */
def yScale(f: ScaleOverride => ScaleOverride): ChartSpec[A] =
    spec.copy(yScaleOverride = Present(f(ScaleOverride.default)))
```

`yScaleRight` must be placed immediately after `yScale` (line 2396), in the same `extension [A](spec: ChartSpec[A])` block.

### 1b. Locked `yScaleRight` signature + scaladoc (from 02-public-api §2.1)

```scala
/** Overrides the right y-axis scale using a builder lambda.
  *
  * Applies only to marks bound to the right axis (`axis = Axis.Right`). The left axis is
  * unaffected; configure it with `.yScale(...)`. Use this when the two axes carry
  * unrelated domains that need independent scale kinds, ranges, or fitting knobs (the
  * canonical dual-axis case, e.g. an absolute count on the left and a log-scaled ratio on
  * the right).
  *
  * The builder receives a fresh `ScaleOverride.default` and returns the configured
  * override: `.yScaleRight(_.log)`, `.yScaleRight(_.linear(0, 1).withClamp(true))`. An
  * unset right override (the default) leaves the right scale inferred from the right-bound
  * marks' data extent, exactly as today.
  *
  * Note: a right axis only exists when the chart has right-bound marks or `.yAxisRight(f)`
  * was called; on a single-axis chart this override is a no-op.
  */
def yScaleRight(f: ScaleOverride => ScaleOverride): ChartSpec[A] =
    spec.copy(yScaleRightOverride = Present(f(ScaleOverride.default)))
```

Exact mirror of `yScale`: same return type (`ChartSpec[A]`), same param type (`ScaleOverride => ScaleOverride`), same body pattern. The scaladoc must be 8-35 lines (this is 16 lines) and have an explicit return type.

### 1c. `ChartSpec` case class field to add

Current `ChartSpec[A]` is at **UI.scala:2258-2279**. Current fields around the insertion point:

```scala
final case class ChartSpec[A](
    data: DataSource[A],
    marks: Chunk[Mark[A]],
    chartSize: (Int, Int),
    xAxisCfg: AxisConfig,
    yAxisCfg: AxisConfig,
    yAxisRightCfg: Maybe[AxisConfig],
    legendCfg: LegendConfig,
    theme: Theme,
    xScaleOverride: Maybe[ScaleOverride],
    yScaleOverride: Maybe[ScaleOverride],          // line 2268 — insert after this
    // NEW field goes here:
    yScaleRightOverride: Maybe[ScaleOverride],     // Absent => infer from right-bound marks
    animateCfg: AnimateConfig,
    key: Maybe[A => String],
    ...
)
```

Field type: `Maybe[ScaleOverride]`. Default: none (ChartSpec has no `= default` on positional fields; the factory passes `Absent` explicitly). Insert immediately after `yScaleOverride` (line 2268) to mirror the `yScaleOverride`/`yAxisRightCfg` pairing that already exists.

No comment is required on the field itself beyond the position — the scaladoc on `yScaleRight` explains semantics.

### 1d. `UI.chart` factory — must pass `Absent` for the new field

Factory at **UI.scala:936-954** (`Builder[A].apply`). Current body:

```scala
def apply(marks: Mark[A]*)(using Frame): ChartSpec[A] =
    ChartSpec[A](
        data = data,
        marks = Chunk.from(marks),
        chartSize = (640, 480),
        xAxisCfg = AxisConfig.default,
        yAxisCfg = AxisConfig.default,
        yAxisRightCfg = Absent,
        legendCfg = LegendConfig.default,
        theme = Theme.default,
        xScaleOverride = Absent,
        yScaleOverride = Absent,
        animateCfg = AnimateConfig.default,
        key = Absent,
        onHover = Absent,
        onSelect = Absent,
        tooltip = Absent
    )
```

Add `yScaleRightOverride = Absent` immediately after `yScaleOverride = Absent` (line 948). This is the ONLY constructor site for `ChartSpec` in the codebase (verified: the `Builder` is the single factory; no other positional constructor exists).

---

## 2. ChartLower source facts

### 2a. Hardcoded right-scale block to replace

Located at **ChartLower.scala:743-748** (inside `resolveAllScales`, after the left-Y block at 708-741):

```scala
// Y right scale (optional)
val ysR: Maybe[Scale] =
    if computeRight then
        val rExt = yRightExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
        Present(Scale.fit(Scale.Kind.Linear, rExt, layout.plotBaseline, layout.plotY, nice = true))
    else Absent
```

This block ignores every override knob. It is the sole locus of GAP-RIGHTY-SCALE.

### 2b. Left Y resolution (708-741) — the shared `resolveYScale` source

The left arm that P11 must factor and reuse:

```scala
// Y left scale
val yExt     = yLeftExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
val yPad     = effectivePad(yOverride, yAxisCfg)
val yNice    = yOverride.map(_.nice).getOrElse(true)
val yReverse = yAxisCfg.reversed

val yKindOpt: Maybe[Scale.Kind] = yOverride.flatMap(_.kind) match
    case Present(ScaleKind.Band)         => Present(Scale.Kind.Band)
    case Present(ScaleKind.Log)          => Present(Scale.Kind.Log)
    case Present(ScaleKind.Linear(_, _)) => Present(Scale.Kind.Linear)
    case Present(ScaleKind.Time)         => Present(Scale.Kind.Time)
    case Present(ScaleKind.Ordinal)      => Present(Scale.Kind.Ordinal)
    case Present(ScaleKind.Point)        => Present(Scale.Kind.Point)
    case Present(ScaleKind.Symlog)       => Present(Scale.Kind.Symlog)
    case _                               => Absent
val yKind    = yKindOpt.getOrElse(Scale.Kind.Linear)
val baseline = layout.plotBaseline
val top = layout.plotY + layout.topHeadroom
val (yRLoBase, yRHiBase) = if yReverse then (top, baseline) else (baseline, top)
val (yExtFinal, yRLo, yRHi, useNice) = yOverride.flatMap(_.kind) match
    case Present(ScaleKind.Linear(domLo, domHi)) =>
        (padExtent(Extent.Continuous(domLo, domHi), yPad), yRLoBase, yRHiBase, false)
    case Present(ScaleKind.Log) =>
        val rawExt = yLeftExtentNoZero(rows, marks).getOrElse(Extent.Continuous(1.0, 10.0))
        (padExtent(rawExt, yPad), yRLoBase, yRHiBase, false)
    case _ => (padExtent(yExt, yPad), yRLoBase, yRHiBase, yNice)
val yClamp = yOverride.map(_.clamp).getOrElse(false)
val ysL    = Scale.fit(yKind, yExtFinal, yRLo, yRHi, nice = useNice, clamp = yClamp)
```

The design name for the factored helper is **`resolveYScale`**. This does not yet exist in the source; P11 creates it by extracting the left arm above into a private method and calling it for both axes.

### 2c. `resolveYScale` — target private signature (design §GAP-RIGHTY-SCALE)

```scala
private def resolveYScale(
    ext: Extent,
    extNoZero: Extent,
    override: Maybe[ScaleOverride],
    axisCfg: AxisConfig,
    rangeLo: Double,
    rangeHi: Double
): Scale
```

The right call passes:
- `ext` = `yRightExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))`
- `extNoZero` = the right no-zero extent (computed inline or via a new `yRightExtentNoZero` helper mirroring `yLeftExtentNoZero` at line 465)
- `override` = `spec.yScaleRightOverride` (threaded from call sites)
- `axisCfg` = `spec.yAxisRightCfg.getOrElse(AxisConfig.default)` (threaded from call sites)
- `rangeLo` / `rangeHi` = `layout.plotBaseline` / `layout.plotY` (note: no `topHeadroom` offset for the right axis, unlike the left which uses `layout.plotY + layout.topHeadroom`; the right extent is driven by right-bound marks, not points, so the point-headroom offset is a left-side concern — verify against the existing behavior; the old hardcoded right `Scale.fit` used `layout.plotBaseline, layout.plotY` directly without topHeadroom)

Byte-identity check (from design): when `override = Absent` and `axisCfg = AxisConfig.default` (padding=0, reversed=false), `resolveYScale` falls through to the `case _ =>` arm with `nice = true`, `clamp = false`, `pad = 0`, `kind = Linear`, producing `Scale.fit(Scale.Kind.Linear, rExt, plotBaseline, plotY, nice=true)` — byte-identical to the old hardcoded line 747.

### 2d. `resolveAllScales` signature delta

Current signature at **ChartLower.scala:659-668**:

```scala
private def resolveAllScales[A](
    rows: Chunk[A],
    marks: Chunk[Mark[A]],
    layout: Layout,
    xOverride: Maybe[ScaleOverride],
    yOverride: Maybe[ScaleOverride],
    computeRight: Boolean,
    xAxisCfg: AxisConfig = AxisConfig.default,
    yAxisCfg: AxisConfig = AxisConfig.default
): ResolvedScales =
```

P11 adds two new params for the right override and right axis config (placed after `yAxisCfg`):

```scala
    yRightOverride: Maybe[ScaleOverride] = Absent,
    yAxisRightCfg: AxisConfig = AxisConfig.default
```

These have defaults of `Absent` / `AxisConfig.default` so the 4 call sites that do NOT pass them yet are still valid. The 4 call sites that need updating to pass the new values are:

- `lowerStatic`: **line 4013** (single-line form, passing positional args inline)
- `lowerLive` initial: **line 3897-3906** (named form)
- `lowerLive` reactive: **line 3943-3952** (named form)
- `lowerWithScales`: **line 4013** (same structure as lowerStatic)

All 4 sites must pass `spec.yScaleRightOverride` and `spec.yAxisRightCfg.getOrElse(AxisConfig.default)`.

Note: the exact current line numbers for the lowerLive and lowerWithScales call sites are **3897**, **3943**, and **4013** based on the current source read; treat these as best-effort since prior phases (P1-P10) may have shifted lines in ChartLower.scala. The P11 implementer must re-read the current file.

### 2e. `!isRight` grid gate — the GAP-RIGHTY-GRID locus

Located at **ChartLower.scala:884** (inside `buildYAxis` loop):

```scala
val grid: Maybe[Svg.SvgElement] =
    if cfg.showGrid && !isRight then
        Present(
            Svg.line
                .x1(layout.plotX).y1(py)
                .x2(layout.plotX + layout.plotW).y2(py)
                .stroke(Svg.Paint.Color(gridColor))
                .strokeOpacity(0.3)
        )
    else Absent
```

The `!isRight` gate is the sole reason right gridlines are never emitted even when `.yAxisRight(_.grid)` is set.

### 2f. `buildYAxis` signature and the `drawGrid` param

Current signature at **ChartLower.scala:862-869**:

```scala
private def buildYAxis(
    layout: Layout,
    ys: Scale,
    cfg: AxisConfig,
    isRight: Boolean,
    theme: Theme,
    chrome: Style.Color,
    gridColor: Style.Color
)(using Frame): Chunk[Svg.SvgElement] =
```

P11 adds a `drawGrid: Boolean` param (placed after `gridColor`). The gate at line 884 changes from `if cfg.showGrid && !isRight` to `if drawGrid`.

The `drawGrid` value is computed in the callers (`buildFrame` and `buildReactiveRegion`) using the left-wins tie-break:

```scala
val leftDrawGrid  = spec.yAxisCfg.showGrid
val rightDrawGrid = spec.yAxisRightCfg.exists(_.showGrid) && !leftDrawGrid
```

`buildYAxis` is called at three places:

1. **`buildFrame`** (line 782): left call passes `drawGrid = leftDrawGrid`; right call inside the `Present(ysR_)` match at line 785-793 passes `drawGrid = rightDrawGrid`.
2. **`buildReactiveRegion`** (lines 3802 and 3805): same pattern.
3. **`buildStaticFrameLive`** (contains left + right `buildYAxis` calls): same pattern.

The `buildStaticFrameLive` function is also a caller. Search for it:

```
grep -n "buildStaticFrameLive\|buildYAxis" ChartLower.scala
```

Confirm the call sites and apply the same `drawGrid` threading to each.

---

## 3. Exact edits

### 3a. UI.scala edits (public surface)

**Edit 1 — `ChartSpec` field.** After `yScaleOverride: Maybe[ScaleOverride],` (line 2268) add:
```scala
yScaleRightOverride: Maybe[ScaleOverride],
```

**Edit 2 — `Builder.apply` factory.** After `yScaleOverride = Absent,` (line 948) add:
```scala
yScaleRightOverride = Absent,
```

**Edit 3 — `yScaleRight` extension method.** After the `yScale` method body (line 2396) add the full scaladoc + method from §1b above.

### 3b. ChartLower.scala edits (GAP-RIGHTY-SCALE)

**Edit 4 — Extract `resolveYScale` private helper.** Add a new private method after `resolveAllScales` (after line 751) or co-locate it near `resolveAllScales`. The body is the left-arm logic (708-741) parameterized. Callers pass the appropriate extent, extNoZero, override, axisCfg, and range bounds.

**Edit 5 — Replace left arm.** Replace lines 708-741 with a call to `resolveYScale(yExt, yLeftExtentNoZero(rows, marks).getOrElse(...), yOverride, yAxisCfg, baseline, top)`.

Wait: the current left arm uses `layout.plotY + layout.topHeadroom` as the hi range. For the right arm, the old hardcoded call used `layout.plotY` (no headroom offset). Preserve this distinction by passing `rangeLo = layout.plotBaseline` and `rangeHi = layout.plotY` for the right call (matching the old hardcoded line 747 exactly). For the left call, pass `rangeLo = layout.plotBaseline` and `rangeHi = layout.plotY + layout.topHeadroom`. The `resolveYScale` helper then uses the `rangeLo`/`rangeHi` directly as `yRLoBase`/`yRHiBase` before the `yReverse` swap.

**Edit 6 — Replace right block (lines 743-748).** After the left call, replace the hardcoded block:
```scala
// Y right scale (optional)
val ysR: Maybe[Scale] =
    if computeRight then
        val rExt = yRightExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
        Present(resolveYScale(
            rExt,
            /* extNoZero — compute inline or via yRightExtentNoZero */ ...,
            yRightOverride,
            yAxisRightCfg,
            layout.plotBaseline,
            layout.plotY          // no topHeadroom: right axis does not use point headroom
        ))
    else Absent
```

The `extNoZero` for the right arm: add a private `yRightExtentNoZero` helper mirroring `yLeftExtentNoZero` (line 465), or compute the no-zero extent inline. The design calls for adding the helper (mirror pattern). Its body filters right-bound marks the same way `yLeftExtentNoZero` filters left-bound marks.

**Edit 7 — `resolveAllScales` new params.** Add `yRightOverride: Maybe[ScaleOverride] = Absent` and `yAxisRightCfg: AxisConfig = AxisConfig.default` to the signature (after `yAxisCfg`).

**Edit 8 — Update all 4 call sites** of `resolveAllScales` to pass `spec.yScaleRightOverride` and `spec.yAxisRightCfg.getOrElse(AxisConfig.default)`. The call sites are in `lowerStatic`, `lowerLive` (twice), and `lowerWithScales`. The lowerWithScales call is at the function defined around line 4000 (`lowerWithScales` is the ChartScales helper); re-read the file to confirm.

### 3c. ChartLower.scala edits (GAP-RIGHTY-GRID)

**Edit 9 — `buildYAxis` signature.** Add `drawGrid: Boolean` after `gridColor: Style.Color`.

**Edit 10 — Gate change.** Replace `if cfg.showGrid && !isRight` (line 884) with `if drawGrid`.

**Edit 11 — All `buildYAxis` call sites.** There are call sites in `buildFrame`, `buildReactiveRegion`, and `buildStaticFrameLive`. In each: compute `leftDrawGrid` and `rightDrawGrid` as described in §2f, then pass the appropriate boolean. Re-read the file after prior phases have landed to get exact current line numbers.

---

## 4. Reproduce-before-fix tests

### Test file: `ScaleTest.scala` (kyo-ui/shared/src/test/scala/kyo/internal/ScaleTest.scala)

**L11a (NEW) — `resolveYScale` readback under override.**
Write a test that constructs a `ChartSpec` (or calls `resolveAllScales` directly if `private[kyo]` access allows via the `kyo.internal` package) with `.yScaleRight(_.log)` on a dual-axis chart and reads the resolved right scale's kind.

If `resolveAllScales` is not accessible from ScaleTest (it is `private`, not `private[kyo]`), use the rendered-pixel path in ChartAxisTest instead. The preferred readback is the `Scale` object's kind. Check whether `ChartLower` is `private[kyo]` or accessible from the test package:

```scala
// ChartLower object declaration:
private[kyo] def buildFrame / resolveAllScales
```

`resolveAllScales` is currently `private` (not `private[kyo]`), so it is NOT accessible from tests directly. The L11a ScaleTest leaf must therefore rely on the `ChartScales` / `lowerWithScales` API (which IS accessible via `ChartSpec.toChartScales` or similar) or on the rendered SVG pixel.

Fallback path (always available): use `ChartAxisTest` with a known right-bound datum and compute the expected log-scaled vs linear pixel, asserting the rendered coordinate matches the log pixel.

**L12b (CO-PIN) — default resolveYScale byte-identity.**
Assert that a dual-axis chart with NO `yScaleRight` override produces a right scale equal to `Scale.fit(Scale.Kind.Linear, rExt, plotBaseline, plotY, nice=true)` — the same as today. The existing `ChartAxisTest` dual-axis test (currently at line 268) IS this guard; re-pin it to ensure the known right-pixel value `230.0` is still produced.

**L19a (NEW) — independence: `.yScale(_.log).yScaleRight(_.linear(0,1))`.**
Two-axis chart with left log + right linear. Both resolved correctly and independently. Assert left kind = Log AND right kind = Linear simultaneously (via rendered pixels or scale readback).

### Test file: `ChartAxisTest.scala` (kyo-ui/shared/src/test/scala/kyo/ChartAxisTest.scala)

**L11b (NEW) — right-bound datum pixel matches log scale.**
Dual-axis chart, right-bound line mark with `.yScaleRight(_.log)`. Known right datum value `v`. Compute expected log pixel: `plotBaseline + log(v/domLo)/log(domHi/domLo) * (plotY - plotBaseline)` (or the Scale.Log formula). Assert rendered y-coordinate of the right line path node `== expected log pixel` (not the linear pixel). Fails today: hardcoded linear produces a different pixel.

**L12a (CO-PIN) — existing dual-axis test stays green.**
The test at line 268 ("two axes: bar(revenue) + line(growthPct, Right) yield distinct y-scales; right labels on right margin") must stay green. It currently asserts `rightScaleY10 ≈ 230.0`. No change to this test; it is a co-pin.

**L13 (NEW) — right gridlines with left-wins guard.**

Case 1: `.yAxisRight(_.grid)` + left grid OFF. Assert horizontal gridline `<line>` elements exist in the rendered SVG at the right scale's tick y-positions. Count `== right axis tick count` (e.g. 5). Fails today: `!isRight` gate prevents any right gridlines.

Case 2: `.yAxis(_.grid).yAxisRight(_.grid)` (both set). Assert gridline count `== left tick count` only (no doubled lines). The right set is suppressed by the `rightDrawGrid = ... && !leftDrawGrid` guard.

Use `frameRectsIn` / SVG substring search to identify gridline `<line>` elements with `stroke-opacity="0.3"` (the gridline opacity constant at lines 890-891).

**L19b (NEW) — per-axis pixel independence.**
Same independence scenario as L19a, but asserted via rendered pixels: a left-bound datum projects onto the log scale, a right-bound datum projects onto the linear scale. Both pixels are asserted in one test. Complementary to L19a's kind readback.

### Co-pins (must remain green, no modification)

- **L8 (INV-004 golden):** `ChartInvariantsTest` golden SVG unchanged — the right scale with no override is byte-identical to today.
- **L12a:** existing dual-axis test (ChartAxisTest ~line 268) stays green, right pixel `≈ 230.0`.
- **Left axis:** existing left-scale tests (xScale / yScale) are unaffected; the left arm calls `resolveYScale` with the same inputs as before.

---

## 5. Verification command

```
sbt 'kyo-ui/testOnly kyo.ChartAxisTest kyo.internal.ScaleTest kyo.ChartInvariantsTest'
```

The invariants test (INV-004 golden) is included because P11 changes `resolveAllScales` and `buildYAxis`, both of which participate in the static frame path that produced the golden. If ChartLowerTest has existing dual-axis or right-axis sub-tests, include it too:

```
sbt 'kyo-ui/testOnly kyo.ChartAxisTest kyo.internal.ScaleTest kyo.ChartInvariantsTest kyo.ChartLowerTest'
```

P11's targeted verify is JVM only; cross-platform runs in P13.

---

## 6. Traps and gotchas

**PUBLIC surface rules (non-negotiable).**
- `yScaleRight` MUST have an explicit return type (`ChartSpec[A]` — NOT inferred).
- Scaladoc MUST be 8-35 lines (the locked text is 16 lines).
- Do NOT add `inline` to `yScaleRight` — it is a handling/builder method, not a suspension path.
- Do NOT add `using Frame` — it is a pure total function.

**`ChartSpec` field ordering.**
The field `yScaleRightOverride` must be inserted between `yScaleOverride` and `animateCfg` to mirror the `yScaleOverride/yAxisRightCfg` pairing. The factory must pass it positionally as `yScaleRightOverride = Absent` — using named params avoids arity bugs if any other phase touched the case class ordering.

**Byte-identity of the default right path.**
The old hardcoded block called `Scale.fit(Scale.Kind.Linear, rExt, layout.plotBaseline, layout.plotY, nice = true)`. The `resolveYScale` helper with `Absent` override and `AxisConfig.default` (padding = 0, reversed = false) must produce EXACTLY this call. Verify: `effectivePad(Absent, AxisConfig.default) = 0.0`; `nice = Absent.map(_.nice).getOrElse(true) = true`; `kind = Linear (getOrElse)`; `reverse = false` => `(yRLoBase, yRHiBase) = (plotBaseline, plotY)`; no pad, no clamp, `case _ =>` arm => `Scale.fit(Linear, rExt, plotBaseline, plotY, nice=true)`. The only difference from the left is that the right does NOT add `topHeadroom` to the hi bound — pass `layout.plotY` (not `layout.plotY + layout.topHeadroom`) for the right's `rangeHi`.

**`drawGrid` threading — do not miss `buildStaticFrameLive`.**
There are at least 3 functions that call `buildYAxis`: `buildFrame`, `buildReactiveRegion`, and `buildStaticFrameLive` (the live chart's static portion). All three must compute `leftDrawGrid` / `rightDrawGrid` and thread `drawGrid`. Missing one leaves a call site with a compile error or (if a default is provided) silently wrong behavior.

**P8 concurrency note.**
P8 edits `buildYAxis` (lines ~862-954) to add the shared `tickLabel` chrome helper. P11 also edits `buildYAxis` (adding the `drawGrid` param and changing the grid gate at ~line 884). These are non-overlapping changes within the same function but P8 modifies the tick-label block (909-916) while P11 modifies the grid block (883-892) and the signature. If P8 lands before P11, the P11 implementer must re-read `buildYAxis` to see P8's changes and apply the `drawGrid` change on top. The changes are compatible (different lines within the function), but re-reading is required.

**`yRightExtentNoZero` — add or inline.**
The `resolveYScale` helper's Log arm needs a no-zero right extent (mirroring `yLeftExtentNoZero` for the left). Add `private def yRightExtentNoZero[A](rows, marks)` mirroring `yLeftExtentNoZero` (line 465) but filtering for `m.axis == Axis.Right` marks, OR compute the no-zero right extent inline inside the right block. The design calls for the new helper (mirror pattern). Do not reuse `yLeftExtentNoZero` for the right arm (it filters left-bound marks).

**`resolveAllScales` call site at `lowerWithScales`.**
`lowerWithScales` is a test/inspection entry point that exposes `ChartScales` (used by `ChartScalesTest`). It also calls `resolveAllScales` (line ~4013). This call MUST be updated to pass `spec.yScaleRightOverride` and `spec.yAxisRightCfg.getOrElse(AxisConfig.default)`. If missed, the `ChartScalesTest` would still compile (the new params have defaults) but the right scale in the scales object would be wrong for a spec with a right override.

**No regression on left axis.**
The left arm becomes a call to `resolveYScale(...)`. Verify by running the existing suite that left-axis pixel values are unchanged. The INV-004 golden is the strongest guard; it covers the static frame including the left y-axis ticks.

---

## 3-line summary

1. `yScaleRight` lives at **UI.scala:2394** (same `extension [A](spec: ChartSpec[A])` block as `yScale`); its locked signature is `def yScaleRight(f: ScaleOverride => ScaleOverride): ChartSpec[A] = spec.copy(yScaleRightOverride = Present(f(ScaleOverride.default)))` with a 16-line scaladoc; `ChartSpec.yScaleRightOverride: Maybe[ScaleOverride]` inserts after `yScaleOverride` (line 2268); the factory at line 937 adds `yScaleRightOverride = Absent`.

2. The shared scale resolver is named **`resolveYScale`** (does not yet exist; P11 extracts it from the left arm at ChartLower.scala:708-741); the hardcoded right block at lines 743-748 (`Scale.fit(Scale.Kind.Linear, rExt, layout.plotBaseline, layout.plotY, nice=true)`) is replaced with a call to `resolveYScale` passing `spec.yScaleRightOverride` and `spec.yAxisRightCfg.getOrElse(AxisConfig.default)`, and the 4 `resolveAllScales` call sites gain two new params.

3. The `!isRight` grid gate is at **ChartLower.scala:884** (`if cfg.showGrid && !isRight`); P11 replaces it with `if drawGrid` where `drawGrid: Boolean` is a new param computed in each `buildYAxis` caller via `rightDrawGrid = spec.yAxisRightCfg.exists(_.showGrid) && !leftDrawGrid` (left-wins tie-break), threading through `buildFrame`, `buildReactiveRegion`, and `buildStaticFrameLive`.
