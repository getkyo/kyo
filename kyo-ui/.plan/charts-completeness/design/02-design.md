# 02 — Implementation design: kyo-ui chart feature-completeness (14 gaps)

HOW to close the 14 gaps so the existing chart layer is consistent. The public surface is fixed by
`02-public-api.md` and is IMMUTABLE input here; this document implements it. Working assumption (the
RECOMMENDED defaults pending formal confirmation in resolve-open):

- GAP-RIGHTY-SCALE: dedicated `ChartSpec.yScaleRightOverride: Maybe[ScaleOverride]` field +
  `yScaleRight(f)` extension method (candidate B).
- GAP-LABELALLBANDS: REMOVE the dead `AxisConfig.labelAllBands` field.
- GAP-AXISCONFIG-SIDE: REMOVE `AxisConfig.side` + `.left/.right/.top/.bottom` setters + the `Side`
  enum, and delete the FALSE scaladoc line (API:2489).

Path roots (fresh reads, current worktree):
- LOWER = `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala` (3990 lines)
- API   = `kyo-ui/shared/src/main/scala/kyo/UI.scala` (2879 lines)
- Tests = `kyo-ui/shared/src/test/scala/kyo/Chart*.scala`, `.../internal/ScaleTest.scala`

Every fix MIRRORS an existing correct implementation. The campaign invariant is byte-identical
output on the no-colorScale / Absent path (INV-004 golden must not move): see §0.1 for the proof
that routing a Present colorScale through `resolvePalette` while leaving the Absent branch on the
existing by-index fallback preserves that.

---

## §0 Shared design decisions that the per-gap fixes depend on

### §0.1 The colorScale-vs-Absent byte-identity proof (binds GAP-COLOR-* + INV-004)

`themePalette(theme)` (LOWER:149-150) is `theme.palette.getOrElse(DefaultPalette)`, consumed
by-index as `palette(i % palette.size)`. `resolvePalette(spec, cats)`'s **Absent** branch
(LOWER:1309-1314) is `spec.theme.palette match { Present(p) => i % p.size ; Absent => DefaultPalette
i % size }`. These are the SAME mapping for the Absent-colorScale case. Therefore the fix shape for
every color gap is:

```
spec.legendCfg.colorScale match
    case Present(_) => resolvePalette(s, cats)   // honor the scale (Categorical OR Sequential)
    case Absent     => <the existing by-index themePalette/DefaultPalette path, unchanged>
```

This keeps Leaf 15/16/17/18 (`ChartLowerTest.scala:1634/1670/1692/1728`, all
no-colorScale + custom `theme.palette`) byte-identical, because those tests never set
`legendCfg.colorScale` so they take the Absent arm verbatim. A cleaner equivalent is to call
`resolvePalette` unconditionally (its Absent branch already IS the by-index fallback); each gap below
states which form it uses. Both forms preserve INV-004 by construction; the per-gap reproduce test
asserts the Present path and the Leaf 15-18 guards assert the Absent path stays unchanged.

### §0.2 Shared helper `applyMarkChannels` (binds GAP-TRANS-BAR-CHANNELS; refactors GAP coverage)

To prevent the static and transition bar paths from diverging again (the root cause of
GAP-TRANS-BAR-CHANNELS: `lowerBarSimple` applies opacity/tooltip/label at 1815/1819/1823 but
`lowerBarSimpleWithTransitions` 3272-3284 does not), extract ONE helper that applies the three
rect-bound channels, and call it from both paths.

Signature (place near `lowerBarSimple`, private to the object):

```scala
/** Apply the opacity, tooltip, and label channels to a bar rect for row `row`.
  *
  * Returns the channel-decorated rect plus any emitted label `Svg.text` (Chunk.empty when no
  * `label` channel). Shared by the static (`lowerBarSimple`) and transition
  * (`lowerBarSimpleWithTransitions`) paths so the two cannot drift: both honor `opacity`/`tooltip`/
  * `label` identically. `barX`/`barW`/`barY` are the already-projected rect geometry; `fill` is the
  * resolved fill color (passed in so the caller keeps control of palette resolution).
  */
private def applyBarChannels[A, X, Y](
    rect: Svg.Rect,
    mark: Mark.Bar[A, X, Y],
    row: A,
    barX: Double,
    barW: Double,
    barY: Double,
    fill: Style.Color
): (Svg.SvgElement, Chunk[Svg.SvgElement])
```

Body is exactly the current `lowerBarSimple` 1815-1836 block (opacity -> tooltip -> label),
returning `(withTooltip, labelElems)`. `lowerBarSimple` then becomes
`val (rectEl, labelEls) = applyBarChannels(baseRect, mark, row, barX, barW, barY, defaultFill)`,
appending `(row, rectEl)` to bars and `labelEls` to labels (no behavior change; pure extraction).
`lowerBarSimpleWithTransitions` calls the same helper on its rect (before/after the SMIL animates are
attached, see GAP-TRANS-BAR-CHANNELS §G12).

Rationale for not over-generalizing: text/line/area/point already each apply their own channel logic
with mark-shape-specific anchoring (label position above bar vs at point), so a single cross-mark
channel helper would be a premature abstraction (guide "three similar lines beat a premature
abstraction"). The bar static/transition pair is the one place two paths render the SAME rect, so the
shared helper is exactly scoped to that pair.

### §0.3 Shared helper `applyTickLabelChrome` (binds GAP-YAXIS-ROTATION + GAP-THEME-FONT)

`buildXAxis` (957-1034) is the only place that maps `tickAnchor`, applies `tickRotation`, and applies
`withFont` (theme.fontFamily/fontSize). `buildYAxis` (862-954) does none. To stop the two axes from
diverging, extract the anchor+font+rotation application into ONE helper used by both x and y tick
labels, axis titles, and legend text.

Two sub-pieces, both currently inline in `buildXAxis`:

1. `svgAnchor` mapping (969-972): `TextAnchor.{Start,Middle,End} -> Svg.TextAnchor.{Start,Middle,End}`.
   Hoist to a tiny private `toSvgAnchor(a: TextAnchor): Svg.TextAnchor`.
2. `withFont` (975-977): currently a local closure capturing `theme`. Hoist to a private method.

Combined helper signature (place above `buildXAxis`, private to the object):

```scala
/** Apply theme font, configured anchor, and configured rotation to a tick label.
  *
  * Builds the `Svg.text` for `labelStr` at `(x, y)`, fills it with `chrome`, sets the dominant
  * baseline, applies `theme.fontFamily`/`theme.fontSize` when set, applies `cfg.tickAnchor`, and
  * rotates about `(x, y)` when `cfg.tickRotation != 0.0`. This is the single tick-label chrome path
  * shared by `buildXAxis` and `buildYAxis` (left and right) so the two axes render labels identically
  * (mirrors the original `buildXAxis` 1005-1018 block).
  */
private def tickLabel(
    x: Double,
    y: Double,
    labelStr: String,
    chrome: Style.Color,
    baseline: Svg.DominantBaseline,
    cfg: AxisConfig,
    theme: Theme
): Svg.SvgElement
```

Body (lifted verbatim from buildXAxis 1005-1018, generalizing the hardcoded hanging baseline to the
`baseline` param and the hardcoded `svgAnchor` to `toSvgAnchor(cfg.tickAnchor)`):

```scala
val base: Svg.Text =
    withFont(theme, Svg.text.x(x).y(y)
        .textAnchor(toSvgAnchor(cfg.tickAnchor))
        .dominantBaseline(baseline)
        .fill(Svg.Paint.Color(chrome))).apply(labelStr)
if cfg.tickRotation != 0.0 then base.transform(Svg.Transform.Rotate(cfg.tickRotation, Present(x), Present(y)))
else base
```

`withFont(theme, t)` is the hoisted 975-977 closure: `theme.fontFamily.fold(t)(t.fontFamily) |>
theme.fontSize.fold(_)(px => _.fontSize(Svg.SvgLength.Px(px)))`.

A separate one-liner `withFont(theme, t)` is also applied to: axis TITLES (buildXAxis 1024-1031,
buildYAxis 927-952 both edges) and legend text (buildLegend item/sequential/size labels). Titles and
legend text use the existing anchors and are not rotated, so they call `withFont` directly, not the
full `tickLabel` helper.

Rationale: `buildXAxis` keeps the X gridline emission and tick-mark geometry inline (those are
X-specific: vertical gridlines, bottom tick marks); only the LABEL chrome is shared, because that is
the part `buildYAxis` is missing and the part that drifted.

### §0.4 Highlight granularity decision (binds GAP-HIGHLIGHT-COVERAGE)

`withHighlight(tagged, highlight)` (1630-1645) wraps a `Chunk[(A, Svg.SvgElement)]` (row-tagged
shapes) in a ref-driven reactive. The tag is the source row. For per-row marks the row is obvious;
for series-level marks (line/area emit ONE path per series) the documented interaction granularity is
series-level using the series-representative row (lowerLineSeries already attaches interaction attrs
to the first row of the series, 2258-2265; the series-granularity design is documented at the
lowerLine head 2128-2131). Binding decision:

- **text, errorBar**: per-row. Each glyph / each error-bar group is tagged with its own row, mirroring
  lowerBarSimple (every rect tagged with `row`).
- **line, area**: series-level. The single per-series path is tagged with the series-representative
  row (the first row of the series chunk, the SAME row lowerLineSeries/lowerArea already use for
  interaction attrs at 2262-2265 / 2495-2497). This matches the documented series-granularity
  interaction model and avoids inventing per-vertex highlighting (a new feature, out of scope).

This is justified by consistency: highlight then matches the existing interaction-attr granularity on
the very same shapes, so the highlighted element is exactly the element that fires onHover/onSelect.

### §0.5 Area overlap policy decision (binds GAP-COLOR-AREA-SIMPLE)

When the non-stacked area splits into N per-series closed paths, they overlap. The bound policy:
**emit one closed path per category in data (category-collection) order, each at the EXISTING
non-stacked `fillOpacity = 0.7` (LOWER:2478), layered in that order (later categories paint over
earlier).** Rationale:

- It reuses the single-path opacity already in the code (2478), so a one-series chart is byte-identical
  (one path, 0.7 opacity, defaultColor when no colorScale).
- 0.7 fill-opacity means overlaps remain visible (lower layers show through), which is the correct
  semantics for unstacked overlapping areas; pure z-order with opaque fills would hide lower series and
  silently lose data, which is worse for a "completeness/consistency" fix.
- It mirrors lowerLine's per-series emission order (2157-2161): categories collected via
  `collectColorCategoriesWithRaw`, iterated by `zipWithIndex`. The area paths emit in the same order so
  area and line agree when both are colored by the same channel.

This is an internal lowering decision (no public knob); it is bound here, not escalated.

---

## §1 Per-gap design

Each gap: exact code change (function, signature delta, logic), the prior-art mirror with file:line,
and the reproduce-before-fix test (target class + concrete-value assertion). Inter-gap dependencies
are listed in §2.

### GAP-COLOR-GROUPEDBAR (INTERNAL, no signature change)

- **Locus:** `lowerBarGrouped` palette block, LOWER:1869-1878. Today only
  `Present(_: Sequential)` routes through `resolvePalette` (1872); `case _` (Categorical AND Absent)
  falls to the by-index `basePalette` path (1873-1878).
- **Change:** replace the inner match so a Present colorScale of EITHER variant routes through
  `resolvePalette(s, colorCats)`:

  ```scala
  val palette: Chunk[Style.Color] = spec match
      case Present(s) =>
          s.legendCfg.colorScale match
              case Present(_) => resolvePalette(s, colorCats)        // Categorical or Sequential
              case Absent     => colorKeys.zipWithIndex.map((_, i) => basePalette.toSeq.apply(i % basePalette.size))
      case Absent =>
          colorKeys.zipWithIndex.map((_, i) => basePalette.toSeq.apply(i % basePalette.size))
  ```

  `colorCats` is already collected (1858), `spec` already threaded (sig 1851). No signature change.
- **Mirror:** the Sequential arm already at 1872, and `lowerBarStacked` 1971
  (`Present(s) => resolvePalette(s, groupCats)`).
- **Byte-identity:** the Absent and `spec=Absent` arms are the existing by-index code verbatim
  (§0.1), so Leaf 15/16 (`ChartLowerTest.scala:1634/1670`, no colorScale) stay green.
- **Reproduce test (`ChartLowerTest`):** grouped/dodged bar over 2+ regions sharing an x-band +
  `.legend(_.colorScale[Region](Region.NA -> blue, Region.EU -> green, ...))`; assert each dodged
  rect's `fill` equals the colorScale color for its region (mirror `ChartAxisTest.scala:216-260` fill
  assertions). Fails today (bars get themePalette-by-index, not the scale color).

### GAP-COLOR-TEXT (INTERNAL, private signature delta)

- **Locus:** `lowerText` (sig 2277-2283) + palette block 2294-2299 + fill resolution 2322-2327.
- **Signature delta:** add `spec: Maybe[ChartSpec[A]] = Absent` (the thread-through, same shape every
  other lowerer takes). Place it per the using-clause ordering (value params before `(using Frame)`).
  Keep `theme` for the Absent fallback color resolution. (Threading the whole `spec` rather than just
  `legendCfg.colorScale` matches lowerLine/lowerPoint and lets the highlight fix (GAP-HIGHLIGHT) reuse
  the same param.)
- **Change:** resolve the per-category palette via `resolvePalette` when a colorScale is Present:

  ```scala
  val palette: Chunk[Style.Color] =
      if colorCatsWithRaw.isEmpty then Chunk.empty
      else spec match
          case Present(s) => resolvePalette(s, colorCatsWithRaw)
          case Absent     => colorCatsWithRaw.zipWithIndex.map((_, i) => basePaletteText.toSeq.apply(i % basePaletteText.size))
  ```

  Fill resolution (2322-2327) is unchanged (it already indexes `palette(idx)`).
- **Call sites:** `marksRegion` 1709 -> `lowerText(m, rows, xs, ys, markColor, theme, spec)`;
  `marksRegionWithTransitions` 3536 -> `lowerText(m, rows, xs, ys, markColor, spec.theme, Present(spec))`.
- **Mirror:** lowerLine 2154-2156 / lowerPoint 2789 color resolution.
- **Byte-identity:** the Absent arm = current code (§0.1), so Leaf 17 (`ChartLowerTest.scala:1692`,
  no colorScale) stays green.
- **Reproduce test (`ChartLowerTest`):** text mark + `.legend(_.colorScale[Region](...))`; assert each
  glyph's `fill` equals the colorScale color. Preserve Leaf 17 for the Absent path.

### GAP-COLOR-ERRORBAR (INTERNAL, private signature delta)

- **Locus:** `lowerErrorBar` (sig 2351-2357) + palette block 2362-2367 + color resolution 2388-2394.
- **Signature delta:** add `spec: Maybe[ChartSpec[A]] = Absent` (same as GAP-COLOR-TEXT).
- **Change:** same palette resolution rewrite as GAP-COLOR-TEXT, using `colorCatsWithRaw` (already
  collected 2359-2361):

  ```scala
  val palette: Chunk[Style.Color] =
      if colorCatsWithRaw.isEmpty then Chunk.empty
      else spec match
          case Present(s) => resolvePalette(s, colorCatsWithRaw)
          case Absent     => colorCatsWithRaw.zipWithIndex.map((_, i) => basePaletteErr.toSeq.apply(i % basePaletteErr.size))
  ```

  The per-row `color` resolution (2388-2394) indexes `palette(colorIdx)` and is unchanged; all three
  parts (vLine, caps, marker) already share the one resolved `stroke` (2395), so a row's whole error
  bar takes ONE colorScale color.
- **Call sites:** `marksRegion` 1710 -> add `spec`; `marksRegionWithTransitions` 3538 ->
  `Present(spec)`.
- **Mirror:** lowerPoint / lowerLine.
- **Byte-identity:** Absent arm = current code, so Leaf 18 (`ChartLowerTest.scala:1728`) stays green.
- **Reproduce test (`ChartLowerTest`):** errorBar + `.legend(_.colorScale[Region](...))`; assert all 3
  parts of a row share ONE `stroke` equal to the colorScale color. Preserve Leaf 18 for Absent.

### GAP-COLOR-AREA-SIMPLE (INTERNAL, output-semantics change; overlap policy §0.5)

- **Locus:** `lowerArea` non-stacked branch (2449-2499; single path, defaultColor 2483) and its
  animated twin `lowerAreaWithTransitions` (3397-3446; defaultColor 3412/3417, both via delegating to
  `lowerArea`).
- **Change (static, `lowerArea` 2449-2499):** when `mark.color` is Present, split rows by category and
  emit ONE closed area path per series, each colored via `resolvePalette`, at fillOpacity 0.7 (§0.5),
  in category order. Restructure the `case Absent =>` (no stack) branch to dispatch on `mark.color`:

  ```scala
  case Absent =>
      mark.color match
          case Absent =>
              <existing single-path body 2451-2499 verbatim, defaultColor, 0.7>
          case Present(colorEnc) =>
              val cats   = collectColorCategoriesWithRaw(rows, colorEnc.asInstanceOf[Encoding[A, Any]])
              val keys   = cats.map(_._1)
              val palette = spec match
                  case Present(s) => resolvePalette(s, cats)
                  case Absent     => DefaultPalette
              keys.zipWithIndex.flatMap: (key, idx) =>
                  val seriesRows = rows.filter(r => colorEnc.accessor(r).toString == key)
                  val fill       = palette.toSeq.apply(idx % palette.size)
                  <build one closed path from seriesRows exactly like 2451-2499, using `fill`,
                   the same band-centered px (2461), the same baseline-return + close (2476),
                   the same 0.7 opacity (2478), the same per-series representative-row interaction
                   (2492-2497) and tooltip (2484-2489)>
  ```

  Factor the single-series path construction (collect points 2451-2467 -> build closed path 2471-2476
  -> opacity 2477-2483 -> tooltip 2484-2489 -> interaction 2490-2498) into a private
  `buildSimpleAreaPath(seriesRows, mark, layout, xs, ys, fill, spec, internalHoverRef)` returning
  `Maybe[Svg.SvgElement]` (Absent when no finite points). The Absent-color arm calls it once with
  `defaultColor`; the Present-color arm calls it per series. This guarantees the single-series output
  is byte-identical (same function, same inputs) and the multi-series output reuses it exactly.
- **Change (animated, `lowerAreaWithTransitions` 3417):** `lowerArea(...)` now returns N paths for a
  colored area; the existing `rawPaths.zipWithIndex` fold (3422-3443) already keys each path
  `area-$markIdx-$seriesIdx`, so it transparently animates each per-series path. The only requirement
  is that `lowerArea`'s per-series emission order is STABLE across emissions (it is: category order
  from `collectColorCategoriesWithRaw`, which is encounter/enum-ordinal order). No further change to
  the transitions twin beyond it inheriting the multi-path `lowerArea`.
- **Mirror:** lowerLine split 2157-2161; lowerAreaStacked resolvePalette 2613; the per-series path
  construction is the existing single-path body.
- **Byte-identity:** `mark.color = Absent` takes the unchanged single-path arm (via
  `buildSimpleAreaPath` with `defaultColor`), so existing non-stacked area output is unchanged.
- **Reproduce test (`ChartLowerTest`):** non-stacked `area(color = _.series)` over 2 series; assert N
  distinct closed area paths each with the `resolvePalette` fill (fails today: one merged blob with
  defaultColor). A second assertion: each path carries `fill-opacity="0.7"` (overlap policy §0.5).

### GAP-RIGHTY-SCALE (PUBLIC: `yScaleRightOverride` field + `yScaleRight` builder)

- **Public surface (per 02-public-api §2.1 candidate B, IMMUTABLE):**
  1. Add `yScaleRightOverride: Maybe[ScaleOverride]` to `ChartSpec` (UI.scala:2258-2279), immediately
     after `yScaleOverride` (2268), mirroring the `yScaleOverride`/`yAxisRightCfg` pairing.
  2. Update the `ChartSpec[A]` factory (UI.scala:938-953) to pass `yScaleRightOverride = Absent`
     (the only positional/named constructor site; verified the spec is built ONLY here).
  3. Add the `yScaleRight` extension method next to `yScale` (UI.scala:2395) with the exact scaladoc
     and body locked in 02-public-api §2.1:
     `def yScaleRight(f: ScaleOverride => ScaleOverride): ChartSpec[A] = spec.copy(yScaleRightOverride = Present(f(ScaleOverride.default)))`.
- **Internal change (`resolveAllScales`):** replace the hardcoded right block (744-748):

  ```scala
  val ysR: Maybe[Scale] =
      if computeRight then
          val rExt = yRightExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
          Present(Scale.fit(Scale.Kind.Linear, rExt, layout.plotBaseline, layout.plotY, nice = true))
      else Absent
  ```

  with the SAME resolution the LEFT path uses (708-741), against the right extent and the right
  override + right axis config. Thread two new params into `resolveAllScales`:
  `yRightOverride: Maybe[ScaleOverride] = Absent` and `yAxisRightCfg: AxisConfig = AxisConfig.default`.
  The right block then computes, mirroring 708-741:
  - `rPad = effectivePad(yRightOverride, yAxisRightCfg)` (reuse the local `effectivePad` 671);
  - `rNice = yRightOverride.map(_.nice).getOrElse(true)`;
  - `rReverse = yAxisRightCfg.reversed`;
  - `rKindOpt` via the same 7-arm `ScaleKind -> Scale.Kind` match (714-722), `getOrElse(Linear)`;
  - the same Linear-domain / Log-no-zero / default-extent arms (730-739) but using
    `yRightExtent` / a right `yRightExtentNoZero` for the Log arm (add the no-zero right extent helper
    mirroring `yLeftExtentNoZero`, or compute it inline);
  - `rReverse` range swap on `(layout.plotBaseline, layout.plotY)` (mirror 729);
  - `rClamp = yRightOverride.map(_.clamp).getOrElse(false)`;
  - `Present(Scale.fit(rKind, rExtFinal, rLo, rHi, nice = useRNice, clamp = rClamp))`.

  To avoid duplicating the ~30-line left arm, factor the left Y resolution (708-741) into a private
  `resolveYScale(ext, extNoZero, override, axisCfg, rangeLo, rangeHi): Scale` and call it for BOTH
  left and right. This is the prior-art-faithful move (it makes the right path literally the left path)
  and prevents future left/right drift, the same divergence class this campaign exists to fix.
- **Call-site updates:** the 4 `resolveAllScales(...)` calls (LOWER:3759, 3805, 3875, 3957) pass
  `spec.yScaleRightOverride` and `spec.yAxisRightCfg.getOrElse(AxisConfig.default)`.
- **Byte-identity:** when `yScaleRightOverride = Absent` and `yAxisRightCfg = default`, the factored
  `resolveYScale` must reproduce the OLD hardcoded `Scale.fit(Linear, rExt, baseline, plotY,
  nice=true)`: default override => kind Linear (getOrElse), nice true (getOrElse), no reverse, no
  clamp, pad 0 (default AxisConfig.padding) => identical `Scale.fit`. Verified against 708-741: the
  default-extent arm (739) with nice=true and no pad is exactly the old call. So a single-axis or
  unconfigured dual-axis chart is byte-identical (INV-004 holds).
- **Mirror:** the left Y resolution 708-741 (the whole point is to reuse it).
- **Reproduce test (`ChartAxisTest`/`ChartLowerTest`):** dual-axis chart with an `axis = Axis.Right`
  mark + `.yScaleRight(_.log)` (and a second case `.yScaleRight(_.linear(0,1).withClamp(true))` and
  `.yAxisRight(_.reverse)`); assert the right series' projected pixel reflects the override (e.g. a
  known data value lands at the log-scaled pixel, not the linear one; readback via the rendered
  element coordinate). Today all no-op (linear nice).

### GAP-RIGHTY-GRID (PUBLIC behavior via existing `.yAxisRight(_.grid)`; depends on GAP-RIGHTY-SCALE)

- **Locus:** `buildYAxis` grid gate (884): `if cfg.showGrid && !isRight`.
- **Change:** allow the right axis to draw the same full-width horizontal gridlines when
  `cfg.showGrid`, with a double-draw guard. Pass a `drawGrid: Boolean` into `buildYAxis` (computed in
  `buildFrame`) instead of the inline `!isRight`:

  ```
  // in buildFrame (782-794): the LEFT axis draws grid when its cfg requests it;
  // the RIGHT axis draws grid only when its cfg requests it AND the left did not (guard).
  val leftDrawGrid  = spec.yAxisCfg.showGrid
  val rightDrawGrid = spec.yAxisRightCfg.exists(_.showGrid) && !leftDrawGrid
  ```

  `buildYAxis` gains `drawGrid: Boolean` and the gate (884) becomes `if drawGrid`. The right gridline
  geometry is the SAME full-width horizontal line as the left (885-891), so when the right draws it,
  it spans `plotX..plotX+plotW` identically. The guard ensures that when BOTH axes set `.grid`, only
  the left set is emitted (no overlapping duplicate lines at potentially different y positions).
- **Rationale for guard direction:** left-wins because the left axis is always present; the right is
  optional. When both request grid, one set of horizontal references suffices and the left scale's
  ticks own them. (This is a deterministic, documented tie-break, not a silent drop.)
- **Mirror:** the existing left gridline emission 885-891 (geometry reused unchanged).
- **Reproduce test (`ChartAxisTest`):** dual-axis chart, right-bound mark, `.yAxisRight(_.grid)` and
  LEFT grid OFF; assert horizontal gridline elements exist for the right axis (at the right scale's
  tick y-positions). A second case: BOTH `.yAxis(_.grid)` and `.yAxisRight(_.grid)` set; assert the
  gridline count equals the LEFT tick count only (guard: no doubled lines).

### GAP-YAXIS-ROTATION (PUBLIC behavior via existing `.yAxis(_.rotateTicks/anchor)`; uses §0.3 helper)

- **Locus:** `buildYAxis` tick-label block (909-916), which hardcodes `anchor` by side (874) and
  applies no rotation and no font.
- **Change:** replace the inline `tickLabel` construction (909-916) with the shared `tickLabel`
  helper (§0.3):

  ```scala
  val tickLabel: Svg.SvgElement =
      tickLabel(labelX, py, labelStr, chrome, Svg.DominantBaseline.Middle, cfg, theme)
  ```

  This applies `cfg.tickAnchor` (replacing the hardcoded 874 `anchor`), `cfg.tickRotation` (about the
  label anchor point), and `withFont` (GAP-THEME-FONT) in one call. Applies to BOTH left and right y
  (both go through `buildYAxis`). The `labelX`/`py`/`chrome` and the Middle baseline are the existing
  buildYAxis values (873/881/868/914).

  Note on the hardcoded side anchor (874): the OLD default was End (left) / Start (right). The new
  default `cfg.tickAnchor` is `TextAnchor.Middle` (AxisConfig default 2501). To preserve byte-identity
  when the user does NOT set an anchor, the helper call for the y-axis must default the anchor to the
  side-derived value when `cfg.tickAnchor` is the unset default. DESIGN BIND: keep the existing
  side-anchor as the y-axis default and only override when the user explicitly set `rotateTicks`/
  `anchor`. Concretely: `buildYAxis` computes `effAnchor = if cfg.tickRotation != 0.0 ||
  cfg.tickAnchor != TextAnchor.Middle then cfg.tickAnchor else (if isRight then End-equiv... )` —
  simpler and exact: pass the side-derived anchor as the BASE and let the helper apply `cfg.tickAnchor`
  only when it differs from the default. To avoid guessing, the helper takes an explicit
  `anchor: Svg.TextAnchor` param (already-resolved) rather than reading `cfg.tickAnchor` itself; the
  y-axis resolves `anchor = toSvgAnchor(cfg.tickAnchor)` when the user set it, else the side default
  (874). This keeps no-config y-axis output byte-identical (anchor still End/Start) while honoring an
  explicit `.anchor(...)`.

  This is the ONE place the design deviates from a pure "mirror buildXAxis" because buildXAxis has no
  side-default anchor to preserve; the y-axis does (874). The helper §0.3 is adjusted to take a
  resolved `anchor: Svg.TextAnchor` param so both axes drive it correctly:
  `tickLabel(x, y, labelStr, chrome, anchor, baseline, rotation, theme)` where buildXAxis passes
  `toSvgAnchor(cfg.tickAnchor)` + `cfg.tickRotation` and buildYAxis passes the side-default-or-user
  anchor + `cfg.tickRotation`.
- **Mirror:** buildXAxis anchor (1010) + rotation (1014-1018).
- **Reproduce test (`ChartAxisTest`, next to the x rotateTicks test ~972):** `.yAxis(_.rotateTicks(-45))`;
  assert the y tick-label element carries a `Rotate(-45.0, ...)` transform. A second assertion:
  `.yAxis(_.anchor(TextAnchor.Start))` sets the y tick label text-anchor to Start. Today: no transform,
  fixed side anchor.

### GAP-THEME-FONT (PUBLIC behavior via existing `.theme(_.font/.fontSize)`; uses §0.3 helper)

- **Locus:** `withFont` is local to buildXAxis (975-977). Font is applied to x tick labels only.
- **Change:** hoist `withFont(theme, t)` to a private method (§0.3) and apply it to:
  - y tick labels: via the shared `tickLabel` helper (GAP-YAXIS-ROTATION already routes through it, so
    font comes for free);
  - x tick labels: buildXAxis now calls the shared `tickLabel` helper too (replacing its inline
    1005-1018) so x and y share ONE path;
  - axis TITLES: buildYAxis title (927-952, both left and right edges) and buildXAxis title
    (1024-1031) wrap their `Svg.text` in `withFont(theme, _)`;
  - legend text: buildLegend item labels, the sequential-scale labels, and the size-legend labels wrap
    their `Svg.text` in `withFont(theme, _)`. (buildLegend already has `theme` via `spec.theme`.)
- **Byte-identity:** `withFont` is a no-op when `theme.fontFamily` and `theme.fontSize` are both Absent
  (the default theme), so charts without a custom font are byte-identical. The existing x-tick font
  test stays green (same helper, same output).
- **Mirror:** buildXAxis `withFont` 975-977.
- **Reproduce test (`ChartAxisTest`):** `.theme(_.font("monospace").fontSize(14))`; assert a y tick
  label, an axis title, and a legend label each carry `font-family="monospace"` and
  `font-size="14px"`. Today: only x ticks fonted.

### GAP-LEGEND-MARGIN-TEXT-ERRORBAR (INTERNAL; depends on GAP-COLOR-TEXT/ERRORBAR decision)

- **Locus:** `buildLayout` `hasLegend` predicate (278-285): returns `false` for `Mark.Text` (284) and
  `Mark.ErrorBar` (285) even when they carry a color channel, but `buildLegend` (via
  `findColorChannel`) DOES render a legend for them, so the top strip overlaps the plot (no `topPad`
  reserved).
- **Change:** include the Text/ErrorBar color channel in `hasLegend`, consistent with GAP-COLOR-TEXT/
  ERRORBAR now honoring the colorScale and rendering a meaningful legend:

  ```scala
  case m: Mark.Text[?, ?, ?]     => m.color.isDefined
  case m: Mark.ErrorBar[?, ?, ?] => m.color.isDefined
  ```

  (Rule stays `false` since rule has no color channel.) This reserves the top strip whenever a
  color-bearing text/errorBar mark drives a legend, exactly as Bar/Line/Area/Point already do
  (279-282).
- **Coupling:** this is ONE decision shared with GAP-COLOR-TEXT/ERRORBAR (02-public-api §6 internal
  bind): since those marks now honor colorScale and render a legend, the margin must be reserved. If
  GAP-COLOR-TEXT/ERRORBAR were dropped (they are not), this would not change. The two land together.
- **Byte-identity:** a text/errorBar mark with NO color channel keeps `m.color.isDefined == false`, so
  `hasLegend` is unchanged for the no-color case (topPad still 0). Charts without color-bearing
  text/errorBar are byte-identical.
- **Reproduce test (`ChartLowerTest`):** chart whose ONLY color-bearing mark is `text(color = _.region)`;
  assert the plot is shifted down by the reserved legend strip (the data region's top y is at the
  reserved `topPad`, i.e. the legend does not overlap the plotted glyphs). Concretely assert a
  rendered tick/baseline y reflects the reserved `LegendReservedH`. Today: not reserved (overlap).

### GAP-TRANS-BAR-CHANNELS (INTERNAL; uses §0.2 helper)

- **Locus:** `lowerBarSimpleWithTransitions` rect emission (3272-3284): only x/y/w/h/fill + SMIL
  animates; no opacity/tooltip/label.
- **Change:** apply the shared `applyBarChannels` helper (§0.2) to the rect. The animated rect is built
  at 3279 (`rectBase`) and decorated with SMIL animates at 3280-3283; apply the channels to the rect
  BEFORE attaching the SMIL animates (so opacity/title compose with the animation children), and emit
  the label elems alongside:

  ```scala
  val (decorated, labelEls) = applyBarChannels(rectBaseOrAnim, mark, row, barX, barW, barY, defaultFill)
  ```

  Restructure so both the `!animOk` rect (3274) and the animated rect (3279-3283) pass through
  `applyBarChannels`, then the loop accumulates `decorated` into `acc` and `labelEls` into a separate
  labels chunk appended at the end (mirroring lowerBarSimple's `(bars, labels)` split 1840-1841). The
  SMIL `Svg.title` (tooltip) and the `<animate>` children coexist as element children; order them
  tooltip-then-animates to match the static path's title placement.
- **Mirror:** lowerBarSimple 1815/1819/1823 (now the shared helper §0.2).
- **Byte-identity:** a bar with no opacity/label/tooltip channel: `applyBarChannels` returns the rect
  unchanged and an empty label chunk, so animated output is byte-identical for the no-channel case.
- **Reproduce test (`ChartTransitionTest` or `ChartLowerTest`):** live/animated simple bar with
  `opacity = _ => 0.5` + `label = _.name` + `tooltip = _.name`; assert the animated rect carries
  `fill-opacity="0.5"`, a `<title>` child, and a label `<text>`. Today all three dropped.

### GAP-HIGHLIGHT-COVERAGE (INTERNAL; granularity §0.4)

- **Locus:** thread `highlight` into `lowerLine` / `lowerArea` / `lowerText` / `lowerErrorBar` and
  wrap their row-tagged shapes via `withHighlight`. Today only lowerBarSimple (1841), lowerBarGrouped
  (1928), lowerPoint (2919) apply it. `resolveHighlight(Present(s))` (1571-1582) supplies the
  `Maybe[Highlight[A]]`.
- **Signature deltas:** add `highlight: Maybe[Highlight[A]] = Absent` to `lowerLine`, `lowerArea`,
  `lowerText`, `lowerErrorBar` (lowerText/lowerErrorBar already gaining `spec` from GAP-COLOR-*; add
  `highlight` alongside, mirroring lowerPoint sig 2757). Place after the existing optional params,
  before `(using Frame)`.
- **Change per mark (granularity §0.4):**
  - **text** (per-row): tag each emitted glyph with its `row` and wrap the chunk via `withHighlight`.
    The current `lowerText` loop builds a flat `Chunk[Svg.SvgElement]`; change it to accumulate
    `Chunk[(A, Svg.SvgElement)]` (tag each `withOpacity(mark.label(row))` with `row`), then
    `withHighlight(tagged, highlight)` at the end (mirror lowerBarSimple 1840-1841).
  - **errorBar** (per-row): each row emits 4 elements (vLine, caps, marker). Group them per row: build a
    per-row `Svg.G` (or tag each of the 4 with the same `row`). To keep highlight applying to the whole
    error bar, wrap the row's 4 elements in an `Svg.g` tagged `(row, g)` and `withHighlight` the chunk
    of per-row groups. (applyHighlightStyle restyles the group's stroke; the group's children inherit
    via the highlight stroke override.) This mirrors the row-shape model of lowerBarSimple while
    respecting that an error bar is 4 shapes.
  - **line** (series-level): `lowerLine` emits one path per series. Tag each series path with its
    representative row (first row of the series chunk, already used for interaction at lowerLineSeries
    2262-2265) and `withHighlight` the chunk of series paths. The no-color single-series case (2146)
    tags the one path with `rows.headOption`.
  - **area** (series-level): same as line. After GAP-COLOR-AREA-SIMPLE the non-stacked area emits one
    path per series; tag each with its series-representative row and `withHighlight`. The single-series
    / band-form / stacked cases tag with the representative row each already computes.
- **Call sites:** `marksRegion` line 1687 / area 1691 / text 1709 / errorBar 1710 pass
  `resolveHighlight(spec)` (note: pass `resolveHighlight(spec)` which returns Absent when spec Absent,
  matching lowerPoint's `resolveHighlight(Present(s))` at 1705). In `marksRegionWithTransitions`,
  line/area go through the transition lowerers (highlight not applied during animation, consistent with
  today: the transition path is for animated enter/update, and lowerPoint in the transition path 3517
  DOES pass highlight; for parity, pass `resolveHighlight(Present(spec))` to the static lowerLine/
  lowerArea calls at 3515/3516 and to lowerText/lowerErrorBar at 3536/3538).
- **Mirror:** lowerPoint (sig 2757, call 1705, withHighlight 2919); withHighlight 1630-1645.
- **Byte-identity:** `withHighlight(tagged, Absent)` returns `tagged.map(_._2)` unchanged (1635), so a
  chart with no highlight config (the default) is byte-identical. resolveHighlight returns Absent
  unless `selectHighlight`/`hoverHighlight` is set AND the matching ref exists, so non-interactive and
  plain-interactive charts are unchanged.
- **Reproduce test (`ChartInteractionTest`):** `.onSelect(ref).interaction(_.highlightSelect)` on a
  line / text / errorBar chart; drive the ref to a known row and assert the active element gets the
  highlight stroke (e.g. `stroke="#000000"` default, or the configured `selectStyle` color), while a
  non-active element does not. Today: no highlight on those marks.

### GAP-ERRORBAR-BANDCENTER (INTERNAL)

- **Locus:** `lowerErrorBar` px (2384): `val px = xs.apply(x)`.
- **Change:** `val px = xs.apply(x) + xs.bandwidth / 2.0`. `bandwidth` is 0 on continuous scales (no-op
  there); on a Band x it centers the whole error bar under the (centered) tick label. Caps at
  2404-2405 use `px ± halfCap`, so centering `px` centers the caps too; the marker (2415-2416) uses
  `px` and centers as well.
- **Mirror:** line 2198, area 2461, point 2825, text 2320 (all `xs.apply(x) + xs.bandwidth / 2.0`).
- **Byte-identity:** on continuous x (`bandwidth == 0`) the value is unchanged; only Band-x error bars
  shift, which is the correctness fix.
- **Reproduce test (`ChartLowerTest`):** errorBar on a Band (String/enum) x; assert the vertical line's
  `x1` equals band-left + bandwidth/2 (co-aligned with a bar/point at the same category). Today: left
  edge (`xs.apply(x)`), misaligned.

### GAP-LABELALLBANDS (PUBLIC: REMOVE, per 02-public-api §2.2)

- **Removals (UI.scala):**
  - Delete the `labelAllBands: Boolean = true` field (2504) from `AxisConfig`.
  - Delete the `// D18` trailing comment with it.
  - No setter exists (verified: setters 2506-2517 have none), so none to delete.
  - `AxisConfig.default` (2522) `AxisConfig(Absent, Absent, false, 5, Absent)` does NOT pass
    `labelAllBands` positionally (it relies on the trailing default), so removing the field leaves the
    `default` literal valid as-is. No arity change to `default` from THIS removal (the Side removal
    below DOES change the first positional arg).
  - No scaladoc line names `labelAllBands` (the type doc 2483-2493 does not), so no scaladoc edit
    beyond not introducing one.
  - Zero reads in LOWER/SCALE (verified `grep labelAllBands` returns only the field decl), so no body
    change.
- **Test-site:** `ChartAxisTest.scala:1068` mentions `labelAllBands` ONLY in the test-name string
  ("...(labelAllBands default)"); it does NOT read the field (it asserts 7 tick labels render). Update
  the test NAME to drop the now-meaningless parenthetical (e.g. "a 7-category band x-axis produces 7
  tick labels"); the assertion is unchanged and still passes. This is not a weakened test (the
  behavior asserted is identical), just a stale name corrected with the removed knob.
- **Reproduce/verify:** compile-level (the field is gone). No render test (it was never read). The
  retained `ChartAxisTest:1068` assertion (7 labels) is the regression guard that band labeling is
  unaffected by the removal.

### GAP-AXISCONFIG-SIDE (PUBLIC: REMOVE, per 02-public-api §2.3)

- **Removals (UI.scala):**
  - Delete the `side: Maybe[Side]` field (2495) from `AxisConfig` (the FIRST positional field).
  - Delete the four setters `left` (2506), `right` (2507), `bottom` (2508), `top` (2509).
  - Delete the `Side` enum (469-471) — it becomes dead (verified: `Side` is referenced only by these
    four setters and the field; no LOWER/SCALE read).
  - Fix the FALSE scaladoc at 2489: remove the sentence "`side` selects where the axis line and labels
    are drawn." from the `AxisConfig` type doc (2483-2493).
  - Update the scaladoc example at 2487 (`_.left.grid.ticks(5)`) which uses the removed `.left` setter:
    rewrite to a chain with no removed setter, e.g. `_.grid.ticks(5).format(...)`.
  - Adjust `AxisConfig.default` (2522): it passes `side = Absent` as the FIRST positional arg
    (`AxisConfig(Absent, Absent, false, 5, Absent)`); dropping the field drops that leading `Absent`,
    becoming `AxisConfig(Absent, false, 5, Absent)` (axisLabel/showGrid/tickCount/tickFormat). The
    `ChartSpec` factory (UI.scala:942-944) constructs axis configs via `AxisConfig.default`, not
    positionally, so no other constructor site changes.
- **Test-site:** `ChartSpecTest.scala:90-97` reads `spec.yAxisCfg.side` and uses the `.left` setter
  (`.yAxis(_.left.grid.ticks(5))`). Both are removed. This test verified a knob that does nothing (a
  latent-lie test). Under REMOVE, rewrite it to assert the surviving, real behavior:
  `.yAxis(_.grid.ticks(5))` sets `showGrid == true` and `tickCount == 5` (drop the `.side` assertion
  and the `.left` call). Document in the test comment that `side` was removed as a dead/false knob
  (per 02-public-api §2.3). This is a legitimate test change (the asserted behavior never existed), not
  a weakening: the real `grid`/`ticks` assertions are kept.
- **Reproduce/verify:** compile-level. Anti-case 3 (02-public-api §5): `.xAxis(_.top)` no longer
  compiles (the setter is gone), converting a silent no-op into a compile error. No render change
  (nothing read `side`).
- **README/doctest obligation:** if any README Charts example uses `.left`/`.right`/`.top`/`.bottom`,
  rewrite it and re-run `sbt kyo-ui/doctest` (per 00-guides §"Update obligation"). The plan owns the
  README sweep for the removed setters.

---

## §2 Inter-gap dependencies + ordering (technical dependency only, not priority)

Shared helpers land BEFORE the gaps that consume them; right-axis grid AFTER right-axis scale; legend
margin AFTER the text/errorBar color decision.

1. **Shared helpers first** (§0.2 `applyBarChannels`, §0.3 `tickLabel`+`withFont`+`toSvgAnchor`,
   §0.5 `buildSimpleAreaPath`, the §GAP-RIGHTY-SCALE `resolveYScale` extraction): these are pure
   refactors with byte-identical output, validated by the existing suite staying green. They unblock
   the gaps that use them.
2. **GAP-ERRORBAR-BANDCENTER**: standalone one-line geometry fix, no dependency.
3. **GAP-COLOR-GROUPEDBAR**: no signature change, depends only on the §0.1 pattern.
4. **GAP-COLOR-TEXT, GAP-COLOR-ERRORBAR**: private signature delta (`spec` param) + the §0.1 pattern;
   their call sites (marksRegion 1709/1710, marksRegionWithTransitions 3536/3538) update together.
5. **GAP-LEGEND-MARGIN-TEXT-ERRORBAR**: AFTER GAP-COLOR-TEXT/ERRORBAR (one shared keep-legend
   decision; the margin is reserved because those marks now render a meaningful legend).
6. **GAP-COLOR-AREA-SIMPLE**: uses §0.5 `buildSimpleAreaPath`; the transitions twin inherits the
   multi-path `lowerArea` (so static lands first, then the transition path is verified to animate each
   per-series path).
7. **GAP-RIGHTY-SCALE**: public field + builder + the `resolveYScale` extraction (helper #1).
8. **GAP-RIGHTY-GRID**: AFTER GAP-RIGHTY-SCALE (both touch the right-axis path / the `isRight` branch;
   grid needs the right scale's ticks to exist).
9. **GAP-YAXIS-ROTATION + GAP-THEME-FONT**: AFTER §0.3 helper; they share the `tickLabel` path and
   land together (rotation routes through the same helper that carries the font).
10. **GAP-HIGHLIGHT-COVERAGE**: AFTER GAP-COLOR-TEXT/ERRORBAR (reuses the `spec` param those add) and
    AFTER GAP-COLOR-AREA-SIMPLE (the per-series area paths it tags). Granularity per §0.4.
11. **GAP-LABELALLBANDS, GAP-AXISCONFIG-SIDE**: dead-knob removals; independent, but the
    `AxisConfig.default` arity edit for SIDE and the field deletions touch the same case class, so they
    land as one AxisConfig-cleanup unit. The two test-site edits (ChartSpecTest:90, ChartAxisTest:1068)
    land with them.

---

## §3 Cross-platform + byte-identity ledger

- Every locus is in `kyo-ui/shared/src/main/scala/kyo/` (LOWER, API); every test in
  `kyo-ui/shared/src/test/scala/kyo/`. No jvm/js/native-only placement. The lowering stays a pure
  SVG-string projection (only `java.lang.Double.isFinite`, cross-platform). Final gate: JVM + JS +
  Native green via clean compile (00-guides Trap 6: a no-`Compiling` log is RED).
- **INV-004 / byte-identity preserved by construction** on every fix: the Absent-colorScale arm is the
  existing by-index code (§0.1); `withHighlight(_, Absent)` is a no-op (1635); `withFont` with the
  default theme is a no-op; the `resolveYScale` extraction reproduces the old hardcoded right
  `Scale.fit` for the default override; `applyBarChannels` with no channels returns the rect unchanged;
  band-center is a no-op on continuous x. Each gap's reproduce test asserts the NEW Present-path
  behavior; the existing Leaf 15/16/17/18 + golden tests guard the unchanged Absent path.

---

## §4 Rejected alternatives

- **Reuse `yScaleOverride` for both axes (GAP-RIGHTY-SCALE candidate A).** Rejected by 02-public-api
  §2.1 (RECOMMENDED B): it couples left+right scale kinds, so a linear-left/log-right dual axis (the
  canonical case) is inexpressible. Design follows the locked B.
- **Per-vertex / per-row highlight for line/area.** Rejected (§0.4): line/area lower to one path per
  series; per-vertex highlighting is a new feature (steering §3) and inconsistent with the documented
  series-granularity interaction (2128-2131). Bound to series-level.
- **Opaque z-order for overlapping non-stacked areas.** Rejected (§0.5): opaque fills hide lower series
  (silent data loss). Bound to 0.7 fill-opacity layering in category order, matching the existing
  single-path opacity (2478).
- **WIRE the dead `side`/`labelAllBands` knobs.** Rejected by 02-public-api §2.2/§2.3 (RECOMMENDED
  REMOVE): WIRE invents new placement/thinning features (steering §3 out of scope) and leaves a knob
  whose scaladoc currently lies. REMOVE is the smaller honest surface; design follows REMOVE.
- **A single cross-mark `applyMarkChannels` for ALL marks.** Rejected (§0.2): text/line/area/point each
  anchor their channels differently (label above bar vs at point); a universal helper would be a
  premature abstraction. Scoped `applyBarChannels` to the one static/transition rect pair that actually
  duplicates.
