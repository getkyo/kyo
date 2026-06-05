# Phase P8 prep — y-axis tick rotation + anchor + theme font (GAP-YAXIS-ROTATION, GAP-THEME-FONT)

**Gaps closed:** GAP-YAXIS-ROTATION, GAP-THEME-FONT  
**Leaves:** L14 (NEW), L15 (NEW), L16 (NEW), L17 (CO-PIN)  
**Source file:** `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala` (4118 lines, post-P1-P7)  
**Test file:** `kyo-ui/shared/src/test/scala/kyo/ChartAxisTest.scala`  
**Dependency:** must follow P2 (which placed `toSvgAnchor` at line 962, `withFont` at line 974, `tickLabel` at lines 995-1018, and rewired `buildXAxis` to call them)

---

## 0. Gaps P8 owns (confirmed from 05-plan.yaml coverage map)

From the `coverage:` block in `05-plan.yaml`:

```
GAP-YAXIS-ROTATION: P8
GAP-THEME-FONT: P8
GAP-RIGHTY-GRID: P11   # NOT P8
```

P8 owns exactly two gaps: **GAP-YAXIS-ROTATION** and **GAP-THEME-FONT**.  
**GAP-RIGHTY-GRID** belongs to P11 (bundled with GAP-RIGHTY-SCALE because it requires the right scale's ticks to exist first). The `!isRight` grid gate at `buildYAxis` line 884 is a P11 concern, not a P8 one.

---

## 1. Live state of `buildYAxis` — confirmed via source read

`buildYAxis` occupies **lines 862-954** of ChartLower.scala (4118 lines). Signature (lines 862-870):

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

### 1a. Anchor derivation (line 874) — confirms GAP-YAXIS-ROTATION anchor half

The side-default anchor is hardcoded at **line 874**:

```scala
val anchor = if isRight then Svg.TextAnchor.Start else Svg.TextAnchor.End
```

This is a pre-resolved `Svg.TextAnchor` value. It is used directly in the tick label at line 913 (see below). There is NO read of `cfg.tickAnchor` anywhere in `buildYAxis`; a user calling `.yAxis(_.anchor(TextAnchor.Start))` silently has no effect on the Y axis today.

### 1b. Tick-label construction — the inline block that P8 replaces (lines 909-916)

```scala
val tickLabel: Svg.SvgElement =
    Svg.text
        .x(labelX)
        .y(py)
        .textAnchor(anchor)
        .dominantBaseline(Svg.DominantBaseline.Middle)
        .fill(Svg.Paint.Color(chrome))
        .apply(labelStr)
```

This is the EXACT inline that P8 replaces with a call to the shared `tickLabel` helper. Observations:

- **No `withFont` call** — GAP-THEME-FONT confirmed: theme font is never applied to Y tick labels today.
- **No rotation** — GAP-YAXIS-ROTATION confirmed: `cfg.tickRotation` is never read; no `Svg.Transform.Rotate` is added.
- **Anchor is the hardcoded side-default** (`anchor` val at line 874): `cfg.tickAnchor` is not consulted. A `.yAxis(_.anchor(...))` call has zero effect today.
- The shadow local variable `tickLabel` (a `Svg.SvgElement`) has the same name as the private method `tickLabel` (added by P2 at lines 995-1018). After P8's edit replaces the inline block with a call to the method, the local `val tickLabel` name will be gone; the call-site uses a different name (e.g. `val tickLabelElem = tickLabel(...)`), matching the pattern `buildXAxis` uses at line 1061 (`val tickLabelElem`).

### 1c. Grid gate (line 884) — P8 does NOT touch this

```scala
val grid: Maybe[Svg.SvgElement] =
    if cfg.showGrid && !isRight then
        Present(...)
    else Absent
```

The `!isRight` guard is the GAP-RIGHTY-GRID locus (design §GAP-RIGHTY-GRID). It belongs to P11. P8 reads but does not modify this gate.

### 1d. Axis-title block (lines 927-952) — P8 adds `withFont` here

Both the right-title arm (lines 930-940) and left-title arm (lines 941-950) currently build `Svg.text` without `withFont`:

```scala
// right title (lines 931-940):
Svg.text
    .x(cx)
    .y(midY)
    .textAnchor(Svg.TextAnchor.Middle)
    .dominantBaseline(Svg.DominantBaseline.Auto)
    .fill(Svg.Paint.Color(chrome))
    .transform(Svg.Transform.Rotate(90.0, Present(cx), Present(midY)))
    .apply(lbl)

// left title (lines 942-950):
Svg.text
    .x(cx)
    .y(midY)
    .textAnchor(Svg.TextAnchor.Middle)
    .dominantBaseline(Svg.DominantBaseline.Auto)
    .fill(Svg.Paint.Color(chrome))
    .transform(Svg.Transform.Rotate(-90.0, Present(cx), Present(midY)))
    .apply(lbl)
```

GAP-THEME-FONT requires wrapping each of these in `withFont(theme, ...)`. Both are axis-title texts (not tick labels), so they call `withFont` directly, NOT through the full `tickLabel` helper (design §0.3: "Titles and legend text use the existing anchors and are not rotated, so they call `withFont` directly, not the full `tickLabel` helper.").

---

## 2. P2 helpers (committed) — exact signatures to reuse

All three helpers were placed by P2 at lines 962-1018. They are committed and must be called from P8 without modification.

### `toSvgAnchor` (line 962):
```scala
private def toSvgAnchor(a: TextAnchor): Svg.TextAnchor =
    a match
        case TextAnchor.Start  => Svg.TextAnchor.Start
        case TextAnchor.Middle => Svg.TextAnchor.Middle
        case TextAnchor.End    => Svg.TextAnchor.End
```

### `withFont` (line 974):
```scala
private def withFont(theme: Theme, t: Svg.Text): Svg.Text =
    val t1 = theme.fontFamily.fold(t)(f => t.fontFamily(f))
    theme.fontSize.fold(t1)(px => t1.fontSize(Svg.SvgLength.Px(px)))
```
A no-op when both `theme.fontFamily` and `theme.fontSize` are `Absent` (default theme): byte-identical on the default path.

### `tickLabel` (lines 995-1018):
```scala
private def tickLabel(
    x: Double,
    y: Double,
    labelStr: String,
    chrome: Style.Color,
    baseline: Svg.DominantBaseline,
    anchor: Svg.TextAnchor,           // CALLER-RESOLVED (see note below)
    cfg: AxisConfig,
    theme: Theme
)(using Frame): Svg.SvgElement =
    val base: Svg.Text =
        withFont(
            theme,
            Svg.text
                .x(x)
                .y(y)
                .textAnchor(anchor)
                .dominantBaseline(baseline)
                .fill(Svg.Paint.Color(chrome))
        ).apply(labelStr)
    if cfg.tickRotation != 0.0 then
        base.transform(Svg.Transform.Rotate(cfg.tickRotation, Present(x), Present(y)))
    else base
end tickLabel
```

**Key design note:** `anchor` is a pre-resolved `Svg.TextAnchor` (NOT `cfg.tickAnchor: TextAnchor`). `buildXAxis` passes `toSvgAnchor(cfg.tickAnchor)`. `buildYAxis` (P8) passes a pre-resolved anchor that is either the side-default OR `toSvgAnchor(cfg.tickAnchor)` when the user explicitly set it. This is the "one deviation from pure mirror" documented in design §GAP-YAXIS-ROTATION.

---

## 3. `AxisConfig` rotation and anchor knobs — confirmed from source

From `UI.scala` lines 2494-2522:

```scala
final case class AxisConfig(
    side: Maybe[Side],
    axisLabel: Maybe[String],
    showGrid: Boolean,
    tickCount: Int,
    tickFormat: Maybe[Double => String],
    tickRotation: Double = 0.0,                 // D17
    tickAnchor: TextAnchor = TextAnchor.Middle,  // D17
    reversed: Boolean = false,                   // D20
    padding: Double = 0.0,                       // D21
    labelAllBands: Boolean = true               // D18
):
    ...
    def rotateTicks(degrees: Double): AxisConfig = copy(tickRotation = degrees)
    def anchor(a: TextAnchor): AxisConfig        = copy(tickAnchor = a)
```

`AxisConfig` **already has** `tickRotation` and `tickAnchor` fields with their builder methods `rotateTicks` and `anchor`. The default for `tickAnchor` is `TextAnchor.Middle`. P2 wired `buildXAxis` to use them (line 1068: `toSvgAnchor(cfg.tickAnchor)`). **P8 wires an existing-but-dead knob on the Y axis** — not adding a new knob, just routing `buildYAxis` through the same helper so `tickRotation` and `tickAnchor` actually take effect on Y ticks.

**Important implication for byte-identity:** `AxisConfig.default` has `tickAnchor = TextAnchor.Middle` and `tickRotation = 0.0`. But `buildYAxis` line 874 uses a side-derived default (`End` for left, `Start` for right), NOT `Middle`. If P8 naively passed `toSvgAnchor(cfg.tickAnchor)` to `tickLabel`, a default-config Y axis would switch from `End`/`Start` to `Middle` — breaking byte-identity (L15 co-pin, L17).

The fix (per design §GAP-YAXIS-ROTATION): compute an effective anchor that uses the side-default when `cfg.tickAnchor` has its default value (`TextAnchor.Middle`), and uses `cfg.tickAnchor` only when the user explicitly set it:

```scala
// P8 anchor resolution — placed just below the existing line 874:
val effAnchor: Svg.TextAnchor =
    if cfg.tickAnchor != TextAnchor.Middle then toSvgAnchor(cfg.tickAnchor)
    else anchor   // side-default: End (left) or Start (right)
```

Then pass `effAnchor` to `tickLabel` (instead of `anchor`).

---

## 4. P2 state of `buildXAxis` — the rotation/font pattern to mirror

`buildXAxis` (lines 1021-1087) calls `tickLabel` at lines 1061-1071:

```scala
val tickLabelElem: Svg.SvgElement =
    tickLabel(
        px,
        labelY,
        xLabelStr,
        chrome,
        Svg.DominantBaseline.Hanging,
        toSvgAnchor(cfg.tickAnchor),
        cfg,
        theme
    )
```

P8 mirrors this in `buildYAxis` loop with the following changes:
- Position: `labelX`, `py` (instead of `px`, `labelY`)
- Baseline: `Svg.DominantBaseline.Middle` (instead of `Hanging`)
- Anchor: `effAnchor` (side-default-or-user, instead of `toSvgAnchor(cfg.tickAnchor)` unconditionally)
- `cfg` and `theme`: same params (already available in `buildYAxis` signature)

The axis-title block in `buildXAxis` (lines 1075-1085) does NOT use `withFont` today (it builds `Svg.text` directly). Per design §GAP-THEME-FONT, P8 also needs to wrap x-axis titles — but `buildXAxis` title is at lines 1077-1083. However, the plan note says P8 applies `withFont` to titles in "buildXAxis 1024-1031, buildYAxis 927-952" — read these lines to confirm. Lines 1075-1085 of the committed P2 form:

```scala
cfg.axisLabel match
    case Present(lbl) =>
        val labelElem: Svg.SvgElement =
            Svg.text
                .x(layout.plotX + layout.plotW / 2.0)
                .y(layout.svgH - 4.0)
                .textAnchor(Svg.TextAnchor.Middle)
                .fill(Svg.Paint.Color(chrome))
                .apply(lbl)
        base.append(labelElem)
    case Absent => base
```

This X-axis title does NOT call `withFont`. P8 wraps it with `withFont(theme, Svg.text...).apply(lbl)`.

---

## 5. `buildLegend` — legend text and `withFont`

Legend text is built in `buildLegendItems` (lines 1487-1554) and `buildSequentialLegend` (lines 1147-1195). The label `Svg.text` at line 1541:

```scala
val label: Svg.SvgElement =
    Svg.text
        .x(curX + SwatchSize + SwatchLabelGap)
        .y(curY + SwatchSize / 2.0)
        .dominantBaseline(Svg.DominantBaseline.Middle)
        .fill(Svg.Paint.Color(labelColor))
        .withAttrs(clickAttrs)
        .apply(cat)
```

Sequential legend labels at lines 1184-1193 (`label` local function). Neither applies `withFont`. P8 wraps these with `withFont(spec.theme, Svg.text...)`. The `theme` is available as `spec.theme` inside `buildLegend` (via `spec: ChartSpec[A]` param).

**Note on `buildSizeLegend` (lines 1202-1490):** size-legend also builds text labels; per the design, "item/sequential/size labels" all get `withFont`. Check whether P8 scope includes size-legend text.  
Design §GAP-THEME-FONT says: "legend text: buildLegend item labels, the sequential-scale labels, and the size-legend labels". Yes, size-legend labels are included.

---

## 6. Exact edits for P8

### Edit 1 — Replace inline Y tick label with `tickLabel` helper call (lines 909-916)

**Old (lines 909-916):**
```scala
val tickLabel: Svg.SvgElement =
    Svg.text
        .x(labelX)
        .y(py)
        .textAnchor(anchor)
        .dominantBaseline(Svg.DominantBaseline.Middle)
        .fill(Svg.Paint.Color(chrome))
        .apply(labelStr)
```

**New:**
```scala
val effAnchor: Svg.TextAnchor =
    if cfg.tickAnchor != TextAnchor.Middle then toSvgAnchor(cfg.tickAnchor)
    else anchor   // side-default: End for left, Start for right
val tickLabelElem: Svg.SvgElement =
    tickLabel(labelX, py, labelStr, chrome, Svg.DominantBaseline.Middle, effAnchor, cfg, theme)
```

Update references from `tickLabel` to `tickLabelElem` in lines 917-919:

**Old:**
```scala
val elements = grid match
    case Present(g) => Chunk[Svg.SvgElement](g, tickMark, tickLabel)
    case Absent     => Chunk[Svg.SvgElement](tickMark, tickLabel)
```

**New:**
```scala
val elements = grid match
    case Present(g) => Chunk[Svg.SvgElement](g, tickMark, tickLabelElem)
    case Absent     => Chunk[Svg.SvgElement](tickMark, tickLabelElem)
```

### Edit 2 — Wrap Y axis-title texts in `withFont` (lines 930-950)

**Left title (old, lines 942-950):**
```scala
Svg.text
    .x(cx)
    .y(midY)
    .textAnchor(Svg.TextAnchor.Middle)
    .dominantBaseline(Svg.DominantBaseline.Auto)
    .fill(Svg.Paint.Color(chrome))
    .transform(Svg.Transform.Rotate(-90.0, Present(cx), Present(midY)))
    .apply(lbl)
```

**Left title (new):**
```scala
withFont(
    theme,
    Svg.text
        .x(cx)
        .y(midY)
        .textAnchor(Svg.TextAnchor.Middle)
        .dominantBaseline(Svg.DominantBaseline.Auto)
        .fill(Svg.Paint.Color(chrome))
        .transform(Svg.Transform.Rotate(-90.0, Present(cx), Present(midY)))
).apply(lbl)
```

Same wrapping for the right title (lines 931-940) with `Rotate(90.0, ...)`.

### Edit 3 — Wrap X axis-title text in `withFont` (lines 1077-1083 in `buildXAxis`)

**Old:**
```scala
val labelElem: Svg.SvgElement =
    Svg.text
        .x(layout.plotX + layout.plotW / 2.0)
        .y(layout.svgH - 4.0)
        .textAnchor(Svg.TextAnchor.Middle)
        .fill(Svg.Paint.Color(chrome))
        .apply(lbl)
```

**New:**
```scala
val labelElem: Svg.SvgElement =
    withFont(
        theme,
        Svg.text
            .x(layout.plotX + layout.plotW / 2.0)
            .y(layout.svgH - 4.0)
            .textAnchor(Svg.TextAnchor.Middle)
            .fill(Svg.Paint.Color(chrome))
    ).apply(lbl)
```

### Edit 4 — Wrap legend item labels in `withFont` (line 1541 in `buildLegendItems`)

`buildLegendItems` has `labelColor: Style.Color` but not `theme`. It is called from `buildLegend` which has `spec: ChartSpec[A]` (so `spec.theme` is available). Add `theme: Theme` param to `buildLegendItems`, pass `spec.theme` at the call site (line 1123), and wrap the label `Svg.text` with `withFont(theme, ...)`.

**Old call site (line 1123):**
```scala
buildLegendItems(layout, categories, palette, spec.legendCfg, spec.marks, axisChromeColor(spec.theme))
```

**New call site:**
```scala
buildLegendItems(layout, categories, palette, spec.legendCfg, spec.marks, axisChromeColor(spec.theme), spec.theme)
```

**Updated `buildLegendItems` signature:** add `theme: Theme` as last value param.  
**Updated label construction** (line ~1541):
```scala
val label: Svg.SvgElement =
    withFont(
        theme,
        Svg.text
            .x(curX + SwatchSize + SwatchLabelGap)
            .y(curY + SwatchSize / 2.0)
            .dominantBaseline(Svg.DominantBaseline.Middle)
            .fill(Svg.Paint.Color(labelColor))
            .withAttrs(clickAttrs)
    ).apply(cat)
```

### Edit 5 — Wrap sequential legend labels in `withFont` (lines 1184-1193 in `buildSequentialLegend`)

`buildSequentialLegend` already has `spec: ChartSpec[A]` (so `spec.theme` available). The local `label` function (line 1184):

**Old:**
```scala
def label(value: Double, lx: Double, anchor: Svg.TextAnchor): Svg.SvgElement =
    Svg.text
        .x(lx)
        .y(labelY)
        .textAnchor(anchor)
        .dominantBaseline(Svg.DominantBaseline.Middle)
        .fill(Svg.Paint.Color(labelColor))
        .apply(NumberFormat.double(value))
```

**New:**
```scala
def label(value: Double, lx: Double, anchor: Svg.TextAnchor): Svg.SvgElement =
    withFont(
        spec.theme,
        Svg.text
            .x(lx)
            .y(labelY)
            .textAnchor(anchor)
            .dominantBaseline(Svg.DominantBaseline.Middle)
            .fill(Svg.Paint.Color(labelColor))
    ).apply(NumberFormat.double(value))
```

### Edit 6 — `buildSizeLegend` labels (size-legend text)

`buildSizeLegend` (lines 1202-1490) also builds text labels for the size legend. Locate the `Svg.text` constructions inside `buildSizeLegend` and wrap each with `withFont(spec.theme, ...)`. The `spec: ChartSpec[A]` param is already present in its signature.

---

## 7. Reproduce-before-fix tests (L14, L15, L16) + co-pin (L17)

Add to `ChartAxisTest.scala` after the existing Phase 6 tests (after line 1259). All four tests target `ChartAxisTest`.

**Helper needed:** a Y-tick-label extractor. Y tick labels use `DominantBaseline.Middle` (not `Hanging`). The existing `frameTextsIn` returns all frame texts; filter by `dominantBaseline.contains(Svg.DominantBaseline.Middle)` AND position to the left of `PlotX` (left axis) or right of `PlotX + PlotW` (right axis). Alternatively: `textAnchor.contains(Svg.TextAnchor.End)` identifies left Y ticks (this is already used in several existing tests at lines 107, 199, 333, etc.).

```scala
// ---- Phase 8 helpers ----

/** Left y-axis tick labels: frame texts with TextAnchor.End (left of plotX). */
private def leftYTickLabelsIn(root: Svg.Root): Chunk[Svg.Text] =
    frameTextsIn(root).filter(_.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))

/** Right y-axis tick labels: frame texts with TextAnchor.Start to the right of plot area. */
private def rightYTickLabelsIn(root: Svg.Root): Chunk[Svg.Text] =
    frameTextsIn(root).filter: t =>
        t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Start) &&
            t.svgAttrs.x.exists(_ > PlotX + PlotWTwoAx)
```

Note: `leftYTickLabelsIn` must exclude legend labels that also use `TextAnchor.End`. Check: sequential legend `minLabel` uses `TextAnchor.End` (line 1192); categorically-positioned legend item text uses `dominantBaseline.Middle` but NOT `textAnchor.End` (it is unset). For tests that only use left Y axis with no sequential legend, the filter on `TextAnchor.End` is sufficient and matches the existing test pattern at lines 107/199.

---

### L14 — y tick-label rotation (NEW, reproduce-first)

```scala
// ---- Leaf L14 (GAP-YAXIS-ROTATION): yAxis rotateTicks gives every Y tick a Rotate transform ----

"yAxis(_.rotateTicks(-45)) gives every Y tick label a Rotate(-45) transform (L14, GAP-YAXIS-ROTATION)" in {
    // Before fix: buildYAxis inline Svg.text has no rotation; cfg.tickRotation is not read.
    // After fix: tickLabel helper applies Svg.Transform.Rotate(tickRotation, px, py).
    val rows  = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
    val spec  = UI.chart(rows)(bar(x = _.month, y = _.revenue)).yAxis(_.rotateTicks(-45.0))
    val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
    val ticks = leftYTickLabelsIn(root)
    assert(ticks.nonEmpty, "Expected left Y tick labels")
    ticks.foldLeft(succeed): (_, t) =>
        val rot = t.svgAttrs.transform.toSeq.collectFirst { case r: Svg.Transform.Rotate => r }
        rot match
            case Some(r) => assertClose(r.deg, -45.0, "Y tick label rotate degrees")
            case None    => fail(s"Expected a Rotate transform on Y tick label but got ${t.svgAttrs.transform}")
}

"yAxisRight(_.rotateTicks(30)) gives every right Y tick label a Rotate(30) transform (L14 right, GAP-YAXIS-ROTATION)" in {
    // Both left and right Y axes go through buildYAxis; the fix applies to both.
    val rows = Chunk(Row2Ax("Jan", Usd(1000), 5.0), Row2Ax("Feb", Usd(2000), 10.0))
    val spec = UI.chart(rows)(
        bar(x = _.month, y = _.revenue),
        line(x = _.month, y = _.growthPct, axis = Axis.Right)
    ).yAxisRight(_.rotateTicks(30.0))
    val root  = summon[Conversion[ChartSpec[Row2Ax], Svg.Root]](spec)
    val ticks = rightYTickLabelsIn(root)
    assert(ticks.nonEmpty, "Expected right Y tick labels")
    ticks.foldLeft(succeed): (_, t) =>
        val rot = t.svgAttrs.transform.toSeq.collectFirst { case r: Svg.Transform.Rotate => r }
        rot match
            case Some(r) => assertClose(r.deg, 30.0, "Right Y tick label rotate degrees")
            case None    => fail(s"Expected a Rotate transform on right Y tick label but got ${t.svgAttrs.transform}")
}
```

**Failure mode before fix:** `rot` is `None` for every tick; test fails at `case None => fail(...)`.

---

### L15 — y tick-label anchor (NEW, reproduce-first) + co-pin

```scala
// ---- Leaf L15 (GAP-YAXIS-ROTATION): anchor sets Y tick text-anchor; side-default preserved when unset ----

"yAxis(_.anchor(TextAnchor.Start)) sets text-anchor=start on left Y tick labels (L15, GAP-YAXIS-ROTATION)" in {
    // Before fix: cfg.tickAnchor is never read; text-anchor is always the side-default (End for left).
    // After fix: effAnchor = toSvgAnchor(cfg.tickAnchor) when cfg.tickAnchor != TextAnchor.Middle.
    val rows = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
    val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue)).yAxis(_.anchor(TextAnchor.Start))
    val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
    // After the fix the left Y ticks carry Start anchor (no longer filtered by TextAnchor.End).
    // Use dominantBaseline.Middle to isolate Y ticks (not Hanging=X, not absent=rotated-title).
    val ticks = frameTextsIn(root).filter(_.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle))
    assert(ticks.nonEmpty, "Expected Y tick labels")
    ticks.foldLeft(succeed): (_, t) =>
        assert(
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Start),
            s"Y tick with explicit anchor(Start) must have text-anchor=start but got ${t.svgAttrs.textAnchor}"
        )
}

"left Y tick label keeps text-anchor=end when no anchor is set (L15 co-pin, R-9 byte-identity)" in {
    // Side-default anchor (End for left, Start for right) must be preserved when cfg.tickAnchor is
    // the default TextAnchor.Middle. This is the byte-identity guard for the no-anchor case.
    val rows = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
    val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
    val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
    val ticks = leftYTickLabelsIn(root)
    assert(ticks.nonEmpty, "Expected left Y tick labels")
    ticks.foldLeft(succeed): (_, t) =>
        assert(
            t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End),
            s"Default left Y tick must retain text-anchor=end (side-default) but got ${t.svgAttrs.textAnchor}"
        )
}
```

**Failure mode before fix (explicit anchor):** Left Y ticks still carry `TextAnchor.End` (side-default) even with `.anchor(TextAnchor.Start)`. The `dominantBaseline.Middle` filter returns those ticks; the `textAnchor.contains(Start)` assertion fails.

---

### L16 — theme font on Y ticks, axis titles, legend text (NEW, reproduce-first) + co-pin

```scala
// ---- Leaf L16 (GAP-THEME-FONT): theme font applied to Y ticks, axis titles, legend labels ----

"theme font appears on Y tick, axis title, and legend label (L16, GAP-THEME-FONT)" in {
    // Before fix: withFont is only called in buildXAxis tick labels (through tickLabel helper).
    // Y ticks, axis titles, and legend labels have no font attrs even when theme sets them.
    // After fix: withFont called at all four sites; each text carries font-family + font-size.
    val rows = Chunk(
        Sale("Jan", Usd(1000), Region.NA),
        Sale("Feb", Usd(2000), Region.EU)
    )
    val spec = UI.chart(rows)(
        bar(x = _.month, y = _.revenue, color = _.region)
    )
        .yAxis(_.label("Revenue"))   // axis title in buildYAxis
        .theme(_.font("monospace").fontSize(14))
        .legend(_.colorScale[Region](Region.NA -> Style.Color.blue, Region.EU -> Style.Color.green))
    val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
    val html = HtmlRenderer.render(root, Seq.empty)

    // A Y tick label: DominantBaseline.Middle + TextAnchor.End (left Y, default config)
    val yTick = frameTextsIn(root)
        .find(t => t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle) &&
                   t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))
        .getOrElse(fail("Expected a left Y tick label with DominantBaseline.Middle + TextAnchor.End"))
    assert(
        html.contains("font-family=\"monospace\""),
        s"Theme font-family must appear somewhere in rendered SVG; got:\n$html"
    )
    // Verify the Y tick specifically carries the font attributes.
    assert(
        yTick.svgAttrs.fontFamily.contains("monospace"),
        s"Y tick label must carry font-family=monospace but got ${yTick.svgAttrs.fontFamily}"
    )
    assert(
        yTick.svgAttrs.fontSize.exists(_.toString.contains("14")),
        s"Y tick label must carry font-size=14px but got ${yTick.svgAttrs.fontSize}"
    )

    // An axis title: the rotated y-title is also a frame text with a Rotate transform.
    val yTitle = frameTextsIn(root)
        .find(t => t.svgAttrs.transform.toSeq.exists { case r: Svg.Transform.Rotate => true; case _ => false })
        .getOrElse(fail("Expected a rotated axis-title text"))
    assert(
        yTitle.svgAttrs.fontFamily.contains("monospace"),
        s"Y axis title must carry font-family=monospace but got ${yTitle.svgAttrs.fontFamily}"
    )

    // A legend label: DominantBaseline.Middle, NOT a tick (no textAnchor=End for the legend label
    // in a categorical legend with default Top position — legend labels don't have textAnchor.End).
    // Use html substring check (consistent with how test 8d at line 600 checks legend text).
    assert(
        html.contains("font-family=\"monospace\""),
        s"Legend label must carry font-family=monospace; full html:\n$html"
    )
}

"default theme adds no font-family or font-size to Y tick, title, or legend (L16 co-pin, byte-identity)" in {
    // withFont is a no-op when theme.fontFamily and theme.fontSize are both Absent (the default).
    // No font attr must appear on any frame text when no theme font is set.
    val rows = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))
    val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
    val root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
    val html = HtmlRenderer.render(root, Seq.empty)
    assert(
        !html.contains("font-family"),
        s"Default theme must NOT add font-family to any element; got font-family in:\n$html"
    )
    assert(
        !html.contains("font-size"),
        s"Default theme must NOT add font-size to any element; got font-size in:\n$html"
    )
}
```

**Failure mode before fix (L16 NEW):** `yTick.svgAttrs.fontFamily` is `Absent`; the `contains("monospace")` assertion fails.  
**Failure mode before fix (co-pin):** Passes today (no font attrs in default theme) — must stay green.

Note on `HtmlRenderer.render`: this is an effectful call returning `String < Async`. In a synchronous test context it uses `run` or the pattern already established in ChartAxisTest (which uses `summon[Conversion[...]]` synchronously). Check whether ChartAxisTest's test suite uses the `in { ... }` or `in run { ... }` pattern. Existing tests at lines 440-453 use synchronous `in { ... }` with `summon[Conversion...]`. The `HtmlRenderer.render` is async — use `in run { ... }` if needed, or restructure the test to use `svgAttrs` directly (preferred: avoids async). Revise L16 to use `svgAttrs` exclusively rather than `html.contains(...)`:

```scala
// Preferred L16 implementation using svgAttrs only (no HtmlRenderer, no async):
val yTick = frameTextsIn(root)
    .find(t => t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle) &&
               t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End))
    .getOrElse(fail("Expected a left Y tick label"))
assert(yTick.svgAttrs.fontFamily.contains("monospace"), ...)
assert(yTick.svgAttrs.fontSize.exists(_.toString.contains("14")), ...)

val yTitle = frameTextsIn(root)
    .find(t => t.svgAttrs.transform.toSeq.exists { case _: Svg.Transform.Rotate => true; case _ => false })
    .getOrElse(fail("Expected rotated y-title"))
assert(yTitle.svgAttrs.fontFamily.contains("monospace"), ...)

// Legend label: find a frame text that is NOT a tick (no DominantBaseline.Middle+End) and NOT a title (no Rotate).
val legendLabel = frameTextsIn(root)
    .find(t =>
        t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Middle) &&
        !t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End) &&
        !t.svgAttrs.textAnchor.contains(Svg.TextAnchor.Start) &&
        t.svgAttrs.transform.isEmpty
    )
    .getOrElse(fail("Expected a legend label text"))
assert(legendLabel.svgAttrs.fontFamily.contains("monospace"), ...)
```

---

### L17 — x tick-label chrome unchanged (CO-PIN)

```scala
// ---- Leaf L17 (CO-PIN): x tick rotation/anchor/font stay byte-identical through shared helper ----

"x tick rotateTicks/anchor/font stay byte-identical after P8 (L17 co-pin)" in {
    // P8 touches buildXAxis only to add withFont to the title block; the tickLabel helper call at
    // line 1062 is unchanged (P2 already wired it). This test re-asserts the P2 baseline.
    val rows = Chunk(Sale("Jan", Usd(1000)), Sale("Feb", Usd(2000)))

    // Rotation (mirrors Phase 6 Leaf 1 at line 972):
    val rotSpec = UI.chart(rows)(bar(x = _.month, y = _.revenue)).xAxis(_.rotateTicks(-45.0))
    val rotRoot = summon[Conversion[ChartSpec[Sale], Svg.Root]](rotSpec)
    val xTicks  = xTickLabelsIn(rotRoot)
    assert(xTicks.nonEmpty, "Expected x tick labels")
    xTicks.foldLeft(succeed): (_, t) =>
        val rot = t.svgAttrs.transform.toSeq.collectFirst { case r: Svg.Transform.Rotate => r }
        rot match
            case Some(r) => assertClose(r.deg, -45.0, "x tick rotate (L17 co-pin)")
            case None    => fail(s"Expected Rotate on x tick but got ${t.svgAttrs.transform}")

    // Anchor (mirrors Phase 6 Leaf 2 at line 987):
    val ancSpec = UI.chart(rows)(bar(x = _.month, y = _.revenue)).xAxis(_.anchor(TextAnchor.End))
    val ancRoot = summon[Conversion[ChartSpec[Sale], Svg.Root]](ancSpec)
    xTickLabelsIn(ancRoot).foldLeft(succeed): (_, t) =>
        assert(t.svgAttrs.textAnchor.contains(Svg.TextAnchor.End), "x tick anchor (L17 co-pin)")

    // Font (mirrors the x-tick font from P2 baseline):
    val fntSpec = UI.chart(rows)(bar(x = _.month, y = _.revenue)).theme(_.font("monospace").fontSize(14))
    val fntRoot = summon[Conversion[ChartSpec[Sale], Svg.Root]](fntSpec)
    xTickLabelsIn(fntRoot).foldLeft(succeed): (_, t) =>
        assert(t.svgAttrs.fontFamily.contains("monospace"), "x tick font-family (L17 co-pin)")
        assert(t.svgAttrs.fontSize.exists(_.toString.contains("14")), "x tick font-size (L17 co-pin)")
}
```

**Failure mode:** none expected — this is a CO-PIN. If P8 accidentally breaks buildXAxis, this test catches it.

---

## 8. Verification command

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"
sbt 'kyo-ui/testOnly kyo.ChartAxisTest kyo.ChartLowerTest kyo.ChartInvariantsTest'
```

- `ChartAxisTest`: exercises L14 (rotate, both axes), L15 (anchor + co-pin), L16 (font + co-pin), L17 (x co-pin) — and ALL pre-existing axis tests (Tests 1-8d at lines 86-946) as regression guards.
- `ChartLowerTest`: guards color and bar-channel paths; confirms `buildLegendItems` signature delta (new `theme` param) compiles and the existing legend tests stay green.
- `ChartInvariantsTest`: guards INV-004 golden (byte-identity for default theme, confirming `withFont` no-op path).

---

## 9. Traps

**Trap 1 — Byte-identity of default Y-axis anchor.** `AxisConfig.default` has `tickAnchor = TextAnchor.Middle`, but the Y-axis side default is `End` (left) or `Start` (right). Naively passing `toSvgAnchor(cfg.tickAnchor)` = `Svg.TextAnchor.Middle` to `tickLabel` would change every existing left Y tick from `End` to `Middle`, breaking 10+ existing tests. The `effAnchor` computation (compare `cfg.tickAnchor` against `TextAnchor.Middle` default, fall back to `anchor` val) is mandatory. Do not skip it.

**Trap 2 — Do NOT touch `buildXAxis` tick-label construction.** P2 already rewired `buildXAxis` to call `tickLabel`. P8 only adds `withFont` to the X-axis title (lines 1077-1083) and leaves the `tickLabel(...)` call at line 1062 completely unchanged. Any edit to the tick-label call site in `buildXAxis` risks breaking L17 and all existing x-axis tests.

**Trap 3 — Local name shadow: `val tickLabel` vs private method `tickLabel`.** Inside `buildYAxis`'s `loop`, the local `val tickLabel: Svg.SvgElement = Svg.text...` (line 909) shadows the private method `tickLabel(...)` (P2, lines 995-1018). After P8 renames the local val to `val tickLabelElem`, the method is unambiguously callable as `tickLabel(...)`. Confirm no other local `val tickLabel` remains inside `buildYAxis` after the edit.

**Trap 4 — `buildLegendItems` signature delta and all call sites.** Adding `theme: Theme` to `buildLegendItems` requires updating every call site. Currently there is one call site at line 1123 (inside `buildLegend`). Confirm with a grep that no other call site exists before editing.

**Trap 5 — `withFont` is a no-op for default theme.** All existing chart tests (which use the default theme with no `font`/`fontSize` set) must produce byte-identical output after P8. The `withFont` implementation (`theme.fontFamily.fold(t)(...)`) returns `t` unchanged when `fontFamily` and `fontSize` are both `Absent`. The co-pin tests in L16 and the INV-004 golden in `ChartInvariantsTest` verify this. Run `ChartInvariantsTest` explicitly.

**Trap 6 — GAP-RIGHTY-GRID is P11, not P8.** The `!isRight` gate at line 884 must NOT be touched in P8. Do not modify the grid-emission logic. P8 only changes the tick-label construction (lines 909-919) and the axis-title construction (lines 927-952).

**Trap 7 — Right Y axis goes through the same `buildYAxis`.** The `effAnchor` logic uses `anchor` (the side-default computed at line 874: `Start` for right). So `.yAxisRight(_.anchor(TextAnchor.End))` also correctly resolves to `End`. The L14 right-axis rotation sub-test confirms both axes are covered by the single fix.

---

## 3-line summary

P8 owns exactly two gaps: **GAP-YAXIS-ROTATION** and **GAP-THEME-FONT** (GAP-RIGHTY-GRID is P11). `buildYAxis` occupies **lines 862-954**; the tick-label inline block is at **lines 909-916** (a local `val tickLabel: Svg.SvgElement = Svg.text...` with no `withFont`, no rotation, and no read of `cfg.tickAnchor`). `AxisConfig` **already has** `tickRotation: Double = 0.0` and `tickAnchor: TextAnchor = TextAnchor.Middle` with `rotateTicks`/`anchor` builders (they are **existing-but-dead knobs on Y** that P2 wired only for X); P8 wires them on Y by replacing the inline block with a call to the shared `tickLabel` helper (P2, line 995), computing `effAnchor` to preserve the side-default (`End`/`Start`) when `cfg.tickAnchor` is the unset default `TextAnchor.Middle`, and additionally wrapping axis-title texts (both Y and X) and legend item/sequential/size labels in `withFont(theme, ...)` — all of which are no-ops under the default theme, guaranteeing byte-identical output when no font is configured.
