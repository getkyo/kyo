# 04 — Invariants ledger: kyo-ui chart feature-completeness (14 gaps)

The testable INVARIANTS this completeness campaign establishes. Each leaf is a property that, once
the gap is fixed, MUST hold AND be asserted by a concrete-value test. This is the
reproduce-before-fix target: the test fails today (for the right reason), passes after the fix, and
stays as a permanent regression guard.

The dominant family is CONSISTENCY: "the same knob behaves the same across all marks / axes". A
colorScale that colors a line must color a grouped-bar, a text glyph, an errorBar, and a non-stacked
area; a right axis must honor the same scale/grid/chrome knobs the left axis honors; an animated bar
must emit the same channels as a static bar.

## Conventions used by every leaf

- **STATUS** is `NEW` (a property no test asserts today; the gap leaves it broken) or `CO-PIN` (an
  already-correct behavior re-pinned so the fix cannot weaken it; these MUST stay byte-identical /
  green through the campaign).
- **Assertion-readback helpers** (existing, in the Chart\*Test bases): `frameRectsIn(root)` /
  `frameTextsIn(root)` (legend swatches/labels live in the frame `<g>`, marks live in the marks
  `<g>`); `colorOf(maybePaint): Style.Color`; `assertClose(a, b, label)` for pixel comparisons; raw
  `HtmlRenderer.render(root, Seq.empty)` -> `html` substring checks (the Leaf 15-18 + highlight
  pattern). Mark fill/stroke is read either as a typed `Svg` attribute (`elem.svgAttrs.fill` ->
  `colorOf`) or as an `html.contains("fill=\"#rrggbb\"")` substring, matching the surrounding tests
  in each file.
- **Self-audit trace:** every PUBLIC-surface leaf cites the `02-public-api §6` verdict row it rests
  on (all five principles PASS, exit 0). Internal-only leaves cite the §1.1 table row.

## §6 self-audit trace (public-API invariants)

`02-public-api §6` returned 5 PASS / 0 WEAK / 0 FAIL (exit 0). The public-surface leaves below trace
to it as follows:

- Principle 1 Complete -> every gap has a leaf here (L1..L23 cover all 14 GAPs).
- Principle 2 Correct -> L19 (right/left scale independence, disjoint fields), L22/L23 (dead-knob
  removal converts a silent lie into a compile error), L13 (false scaladoc removed).
- Principle 3 Clean -> L22/L23 net-shrink the surface (no widening); L19's only additions
  (`yScaleRight` / `yScaleRightOverride`) nest under existing owners.
- Principle 4 Safe / 5 FP-typed -> all colorScale leaves match the existing sealed `ColorScale`
  union exhaustively; `yScaleRight` is a pure total `ChartSpec[A] => ChartSpec[A]`; the COLOR-ABSENT
  byte-identity leaf (L8) proves no fix weakens the `Maybe`-encoded Absent fallback.

---

## A. COLOR CONSISTENCY — categorical colorScale honored per mark (legend ↔ mark agreement)

Property family: with a Present **categorical** `colorScale[K]`, EACH color-split mark's rendered
fill (or stroke, for errorBar) for a category EQUALS the colorScale color mapped to that category,
AND EQUALS that category's legend swatch fill. This is the reproduce-before-fix family: the
"already-correct" marks (line/point/stacked-bar) are co-pinned so the shared `resolvePalette` route
cannot regress them while the broken marks are fixed.

### L1 — grouped-bar categorical colorScale `NEW`
- **Property:** in a grouped/dodged bar split by `color = _.region` with
  `.legend(_.colorScale[Region](NA->blue, EU->green, APAC->red))`, each dodged rect for region R
  fills with the colorScale color for R (not `themePalette`-by-index).
- **Constrains:** `Mark.Bar` grouped path, categorical `ColorScale`.
- **Assertion shape:** read the marks-`<g>` rects; the rect group for NA has `fill == Style.Color.blue`,
  EU `== green`, APAC `== red` (via `colorOf(rect.svgAttrs.fill)`), AND the legend swatch for each
  region (`colorOf(swatch.svgAttrs.fill)`) equals the same color (legend↔mark agreement, mirrors
  ChartAxisTest:261-263).
- **Test file:** ChartLowerTest.
- **Closes:** GAP-COLOR-GROUPEDBAR. **Trace:** §1.1 table row GAP-COLOR-GROUPEDBAR.

### L2 — text categorical colorScale `NEW`
- **Property:** a `text(color = _.region)` mark with the same categorical colorScale fills each
  glyph with its region's colorScale color.
- **Constrains:** `Mark.Text` fill, categorical `ColorScale`.
- **Assertion shape:** each glyph `<text>` in the marks `<g>` has `fill == colorScale(region)` via
  `colorOf`; legend swatch for that region equals the same color (legend↔mark agreement).
- **Test file:** ChartLowerTest.
- **Closes:** GAP-COLOR-TEXT. **Trace:** §1.1 row GAP-COLOR-TEXT.

### L3 — errorBar categorical colorScale (one color per row, all 3 parts) `NEW`
- **Property:** an `errorBar(color = _.region)` mark with the categorical colorScale strokes all
  three sub-shapes of a row (vertical line, caps, marker) with ONE color equal to the colorScale
  color for that row's region.
- **Constrains:** `Mark.ErrorBar` stroke, categorical `ColorScale`.
- **Assertion shape:** the vLine, both caps, and the marker for the NA row all carry
  `stroke == colorScale(NA)`; the EU row's three parts all carry `stroke == colorScale(EU)`; legend
  swatch agreement as above.
- **Test file:** ChartLowerTest.
- **Closes:** GAP-COLOR-ERRORBAR. **Trace:** §1.1 row GAP-COLOR-ERRORBAR.

### L4 — non-stacked area categorical colorScale `NEW`
- **Property:** a non-stacked `area(color = _.series)` with the categorical colorScale fills each
  per-series path with its series' colorScale color.
- **Constrains:** `Mark.Area` non-stacked fill, categorical `ColorScale`. (Geometry split is L9.)
- **Assertion shape:** the N per-series area `<path>` elements each carry `fill == colorScale(series)`
  via `colorOf`; legend swatch agreement.
- **Test file:** ChartLowerTest.
- **Closes:** GAP-COLOR-AREA-SIMPLE (color half; geometry half is L9). **Trace:** §1.1 row
  GAP-COLOR-AREA-SIMPLE + §2.4.

### L5 — line categorical colorScale (CO-PIN, already correct) `CO-PIN`
- **Property:** a `line(color = _.series)` with the categorical colorScale colors each series path
  with its colorScale color — already true (lowerLine routes through `resolvePalette`); re-pinned so
  the shared-route refactor for L1..L4 does not regress it.
- **Constrains:** `Mark.Line` stroke, categorical `ColorScale`.
- **Assertion shape:** each series `<path>` stroke `== colorScale(series)`; legend swatch agreement.
- **Test file:** ChartLowerTest.
- **Closes:** consistency guard for GAP-COLOR-* (regression co-pin). **Trace:** §1.1 (line is the
  prior-art mirror cited for every color gap).

### L6 — point categorical colorScale (CO-PIN, already correct) `CO-PIN`
- **Property:** a `point(color = _.series)` colors each point's fill from the categorical colorScale
  — already true (lowerPoint); re-pinned as above.
- **Constrains:** `Mark.Point` fill, categorical `ColorScale`.
- **Assertion shape:** each point `<circle>`/marker fill `== colorScale(series)`; legend agreement.
- **Test file:** ChartLowerTest.
- **Closes:** consistency guard (regression co-pin). **Trace:** §1.1 (point is the §0.4 mirror).

### L7 — stacked-bar categorical colorScale (CO-PIN, already correct) `CO-PIN`
- **Property:** a stacked `bar` split by `color` colors each stack segment from the categorical
  colorScale — already true (lowerBarStacked LOWER:1971); re-pinned as above.
- **Constrains:** `Mark.Bar` stacked path, categorical `ColorScale`.
- **Assertion shape:** each stack segment rect fill `== colorScale(category)`; legend agreement.
- **Test file:** ChartLowerTest.
- **Closes:** consistency guard (regression co-pin). **Trace:** §1.1 (stacked-bar 1971 is the
  GAP-COLOR-GROUPEDBAR mirror).

---

## B. COLOR — sequential colorScale + ABSENT byte-identity

### L8 — COLOR-ABSENT byte-identity guard (no colorScale ⇒ colors unchanged) `CO-PIN`
- **Property:** with NO `colorScale` set, EVERY mark's colors are byte-identical to today. The
  INV-004 golden SVG and the existing Leaf 15/16/17/18 `theme.palette` assertions
  (ChartLowerTest:1634/1670/1692/1728) must stay byte-identical / green. This guards the fix from
  weakening the Absent fallback (the `Maybe`-encoded inferred path).
- **Constrains:** every color-split mark under Absent `ColorScale`; the `themePalette` /
  `DefaultPalette` by-index route.
- **Assertion shape:** (a) INV-004 golden `html == ChartInvariantsTest.expectedGolden` unchanged
  (ChartInvariantsTest:114); (b) the four existing custom-`theme.palette` tests still assert
  `fill/stroke == #cc00cc / #00cccc` and NOT `#3b82f6 / #f97316`; (c) default-theme grouped bar
  still `#3b82f6 / #f97316` (Leaf 16). These are EXISTING tests kept green, plus the golden as the
  byte-identity anchor.
- **Test file:** ChartInvariantsTest (golden) + ChartLowerTest (Leaf 15/16/17/18 retained).
- **Closes:** byte-identity contract for GAP-COLOR-* (steering §5, R-8). **Trace:** §1.1 (every
  color row "Public delta: none"); §6 Principle 4/5 (Absent fallback not weakened).

### L9 — non-stacked area SPLIT geometry (N distinct per-series closed paths) `NEW`
- **Property:** a non-stacked `area(color = _.series)` over N categories renders N distinct CLOSED
  area `<path>` elements (one per category), each at `fill-opacity="0.7"`, NOT one merged path.
- **Constrains:** `Mark.Area` non-stacked geometry + overlap policy (per-series, data order, 0.7).
- **Assertion shape:** count area `<path>` elements in the marks `<g>` `== N` (e.g. 2 for 2 series);
  each carries `fill-opacity="0.7"` and is a closed path (ends with `Z`/baseline-return); a
  single-series area still renders exactly ONE path at 0.7 (byte-identity edge, R-5).
- **Test file:** ChartLowerTest.
- **Closes:** GAP-COLOR-AREA-SIMPLE (geometry half). **Trace:** §2.4 output-semantics note; R-5.

### L10 — sequential colorScale honored per fixed mark `NEW`
- **Property:** with a Present **Sequential** colorScale (`Sequential(low, high, domain)`), each
  newly-fixed color-split mark (grouped-bar, text, errorBar, non-stacked area) colors a datum by its
  interpolated sequential color (same `resolvePalette` Sequential branch line/point already use),
  emitting a concrete color, never `url(#...)` on the mark (matching the existing Leaf 15 INV-028
  rule: sequential MARK fills are concrete colors, ChartLowerTest:1434).
- **Constrains:** `Mark.Bar` grouped / `Mark.Text` / `Mark.ErrorBar` / `Mark.Area` under Sequential
  `ColorScale`.
- **Assertion shape:** for two rows at distinct sequential-domain values, the two marks carry two
  DIFFERENT concrete `fill`/`stroke` hex colors (both `#......`, neither `url(#`), and the lower-value
  datum's color equals the `low`-end interpolation, the higher-value datum's the `high`-end (assert
  the two concrete colors and their ordering, mirroring the existing sequential resolve).
- **Test file:** ChartLowerTest.
- **Closes:** GAP-COLOR-GROUPEDBAR/-TEXT/-ERRORBAR/-AREA-SIMPLE (Sequential arm). **Trace:** §3
  `LegendConfig.ColorScale` (sealed Categorical|Sequential, fix matches exhaustively); §1.1 color rows.

---

## C. RIGHT-AXIS — scale overrides + grid (GAP-RIGHTY-SCALE / GAP-RIGHTY-GRID)

### L11 — right axis honors `yScaleRight` overrides `NEW`
- **Property:** a right-bound mark (`axis = Axis.Right`) under `.yScaleRight(_.log)` /
  `.yScaleRight(_.linear(a,b).withClamp(true))` / `.yScaleRight(_.withPad(p))` /
  `.yAxisRight(_.reverse)` projects via THAT scale, not the old hardcoded `Linear + nice`.
- **Constrains:** the right y-scale resolution (`resolveAllScales` right block), `ScaleOverride` on
  the right axis.
- **Assertion shape:** preferred — read back the resolved right scale's domain/kind (e.g. the
  factored `resolveYScale` output kind `== Log` under `.yScaleRight(_.log)`, or the clamped/padded
  domain bounds) via the ScaleTest-level readback; complementary — a known right-bound datum's
  rendered y-pixel (the right line `<path>`/point coordinate) equals the LOG-scaled pixel, NOT the
  linear pixel (compute both expected pixels, assert it matches the log one, mirrors the dual-scale
  pixel math in ChartAxisTest:271-288). One sub-case per override (`log`, `withClamp(true)`,
  `withPad`, `reverse`).
- **Test file:** ScaleTest (domain/kind readback) + ChartAxisTest (rendered right-pixel).
- **Closes:** GAP-RIGHTY-SCALE. **Trace:** `02-public-api §2.1` candidate B (locked) + §6 Principle 1.

### L12 — right-axis default byte-identity (no override ⇒ old Linear+nice) `CO-PIN`
- **Property:** with NO `.yScaleRight(...)` and default `yAxisRightCfg`, the right scale is
  byte-identical to the old hardcoded `Scale.fit(Linear, rExt, plotBaseline, plotY, nice=true)`. The
  factored `resolveYScale` reproduces the old call exactly (default override ⇒ Linear via getOrElse,
  nice true, no reverse, no clamp, pad 0).
- **Constrains:** right y-scale resolution default path.
- **Assertion shape:** the existing dual-axis test (ChartAxisTest:268, "two axes yield distinct
  y-scales") stays green — same value 10 maps to the same right pixel `≈ 230` it does today; ADD an
  explicit assertion that `resolveYScale` with default args returns a scale equal to
  `Scale.fit(Linear, rExt, baseline, plotY, nice=true)` (ScaleTest).
- **Test file:** ChartAxisTest (existing dual-axis test retained) + ScaleTest (factored-helper
  equality).
- **Closes:** GAP-RIGHTY-SCALE byte-identity. **Trace:** DESIGN §GAP-RIGHTY-SCALE byte-identity; R-8.

### L13 — right-axis grid emitted; left-wins tie-break `NEW`
- **Property:** `.yAxisRight(_.grid)` with LEFT grid OFF emits right horizontal gridlines (one per
  right-scale tick, full plot width). When BOTH `.yAxis(_.grid)` and `.yAxisRight(_.grid)` are set,
  only the LEFT set is emitted (left-wins, no doubled lines).
- **Constrains:** `buildYAxis` grid gate (`drawGrid` param), right-axis chrome.
- **Assertion shape:** case 1 — with right-grid-only, count horizontal gridline `<line>` elements
  `== rightTickCount` and assert their y-positions equal the right scale's tick pixels. Case 2 —
  with both, the horizontal gridline count `== leftTickCount` only (no duplicates); the
  matching-existing pattern is the gridline-count assertion at ChartAxisTest:1009 / :931.
- **Test file:** ChartAxisTest.
- **Closes:** GAP-RIGHTY-GRID. **Trace:** §1.1 row GAP-RIGHTY-GRID; R-10 (left-wins).

---

## D. Y-AXIS CHROME — rotation, anchor, theme font (GAP-YAXIS-ROTATION / GAP-THEME-FONT)

### L14 — y tick-label rotation `NEW`
- **Property:** `.yAxis(_.rotateTicks(-45))` gives every Y tick label a `Rotate(-45.0, …)`
  transform (and `.yAxisRight(_.rotateTicks(a))` rotates the right Y ticks), mirroring the existing
  x-axis rotation (ChartAxisTest:972).
- **Constrains:** `buildYAxis` tick-label chrome (shared `tickLabel` helper), both Y axes.
- **Assertion shape:** each Y tick `<text>` carries a `Svg.Transform.Rotate(-45.0, _, _)` transform
  (read `t.svgAttrs.transform`, assert it `contains` the Rotate with value -45.0), mirroring the x
  assertion at ChartAxisTest:972-983.
- **Test file:** ChartAxisTest.
- **Closes:** GAP-YAXIS-ROTATION (rotation). **Trace:** §1.1 row GAP-YAXIS-ROTATION.

### L15 — y tick-label anchor `NEW`
- **Property:** `.yAxis(_.anchor(TextAnchor.Start))` sets the Y tick-label `text-anchor` to `Start`;
  when the anchor is NOT explicitly set, the Y tick label keeps its side-default anchor (End for
  left, Start for right) — byte-identical to today (R-9).
- **Constrains:** `buildYAxis` tick anchor; the resolved-anchor param of the shared helper.
- **Assertion shape:** explicit case — Y tick `<text>` `svgAttrs.textAnchor.contains(Start)` (mirror
  ChartAxisTest:994). Default case (co-pin) — with no `.anchor(...)`, the left Y tick label is still
  `End`-anchored (the existing left-tick End-anchor assumption at ChartAxisTest:1155 stays green).
- **Test file:** ChartAxisTest.
- **Closes:** GAP-YAXIS-ROTATION (anchor). **Trace:** §1.1 row GAP-YAXIS-ROTATION; R-9 (default
  preserved).

### L16 — theme font on y ticks, axis titles, legend text `NEW`
- **Property:** `.theme(_.font("monospace").fontSize(14))` makes Y tick labels, axis titles (x and
  y), and legend text each carry `font-family="monospace"` and `font-size="14px"`. With a default
  theme (both Absent) those elements are byte-identical to today (no font attr added).
- **Constrains:** `withFont` applied at `buildYAxis` ticks, both axis titles, legend text.
- **Assertion shape:** select a Y tick `<text>`, an axis-title `<text>`, and a legend-label `<text>`;
  each has `font-family="monospace"` and `font-size="14px"` (substring or `svgAttrs` readback). A
  default-theme co-pin asserts NO `font-family` on those elements (byte-identity).
- **Test file:** ChartAxisTest.
- **Closes:** GAP-THEME-FONT. **Trace:** §1.1 row GAP-THEME-FONT; §3 `Theme`.

### L17 — x tick-label chrome unchanged through shared helper (CO-PIN) `CO-PIN`
- **Property:** routing x AND y tick labels through the one shared `tickLabel` helper leaves x
  tick-label output byte-identical: the existing x `rotateTicks`/`anchor`/x-font tests
  (ChartAxisTest:972/987 + the x-tick font test) stay green.
- **Constrains:** `buildXAxis` tick-label chrome (refactor into shared helper).
- **Assertion shape:** the existing x-axis rotation, anchor, and font tests pass byte-identical
  (Rotate transform present, `text-anchor=end`, x-tick `font-family` when set).
- **Test file:** ChartAxisTest (existing x tests retained).
- **Closes:** refactor regression guard for GAP-YAXIS-ROTATION/GAP-THEME-FONT. **Trace:** DESIGN §0.3
  (shared `tickLabel`, x and y share one path).

---

## E. TRANSITIONS, HIGHLIGHT, BAND-CENTER, LEGEND-MARGIN

### L18 — animated bar emits opacity/label/tooltip channels `NEW`
- **Property:** an animated simple bar with `opacity = _ => 0.5` + `label = _.name` +
  `tooltip = _.name` emits `fill-opacity="0.5"` on the transition rect, a sibling label `<text>`,
  and a `<title>` child — matching the static bar (`lowerBarSimple`). A no-channel animated bar is
  byte-identical to today.
- **Constrains:** `lowerBarSimpleWithTransitions` rect (via shared `applyBarChannels`).
- **Assertion shape:** the animated `<rect>` carries `fill-opacity="0.5"`, has a `<title>` child, and
  a label `<text>` exists alongside it; assert the SAME three values the static path emits for the
  same channels (cross-check static-vs-transition parity). No-channel co-pin: a plain animated bar
  has no `fill-opacity`/`<title>`/label text.
- **Test file:** ChartTransitionTest.
- **Closes:** GAP-TRANS-BAR-CHANNELS. **Trace:** §1.1 row GAP-TRANS-BAR-CHANNELS; DESIGN §0.2.

### L19 — right/left scale independence (disjoint fields) `NEW`
- **Property:** `.yScale(_.log).yScaleRight(_.linear(0,1))` leaves the LEFT scale log and the RIGHT
  scale linear; setting one override does not mutate the other (disjoint `yScaleOverride` /
  `yScaleRightOverride` fields, structural `copy`).
- **Constrains:** `ChartSpec.yScaleOverride` vs `yScaleRightOverride` (the new field), both axes'
  resolution.
- **Assertion shape:** resolve both scales; the left scale kind `== Log` and the right scale kind
  `== Linear` simultaneously (readback via the factored `resolveYScale` / scale objects); a
  left-bound datum projects log, a right-bound datum projects linear.
- **Test file:** ScaleTest (kind readback) + ChartAxisTest (per-axis pixel).
- **Closes:** GAP-RIGHTY-SCALE (independence anti-case). **Trace:** `02-public-api §5` Anti-case 5 +
  §6 Principle 2 (disjoint fields).

### L20 — highlight coverage on line/area (series-level) and text/errorBar (per-row) `NEW`
- **Property:** with `.onSelect(ref).interaction(_.highlightSelect)` and the ref driven to a known
  datum, the selected datum's LINE and AREA shapes (series-level) and TEXT and ERRORBAR shapes
  (per-row) get the highlight styling (default dark `stroke="#000000"`, `stroke-width="2px"`); a
  non-selected element does not. BAR and POINT highlight is unchanged (co-pin).
- **Constrains:** highlight threading into `lowerLine`/`lowerArea`/`lowerText`/`lowerErrorBar`;
  granularity per R-4 (series for line/area, per-row for text/errorBar).
- **Assertion shape:** before selection, `!html.contains("stroke=\"#000000\"")` on those marks;
  after driving the ref to the known row, the active line/area/text/errorBar element carries
  `stroke="#000000"` + `stroke-width="2px"` and exactly the active element does (count occurrences ==
  expected, mirror ChartInteractionTest:420/429/433). Co-pin: bar/point highlight tests
  (ChartInteractionTest:408+) stay green and unchanged.
- **Test file:** ChartInteractionTest.
- **Closes:** GAP-HIGHLIGHT-COVERAGE. **Trace:** §1.1 row GAP-HIGHLIGHT-COVERAGE; R-4 (granularity).

### L21 — errorBar centered on Band x (apply + bandwidth/2) `NEW`
- **Property:** on a Band (String/enum) x, an errorBar's x-position equals
  `xs.apply(x) + xs.bandwidth/2` (centered), matching point/line/text. On continuous x
  (`bandwidth == 0`) the position is unchanged (byte-identity).
- **Constrains:** `lowerErrorBar` px geometry.
- **Assertion shape:** the errorBar vertical line's `x1` (and `x2`) equals band-left + bandwidth/2,
  co-aligned with a bar/point/text at the same category (assert the concrete pixel == the
  band-center value, NOT the band-left `xs.apply(x)`); a continuous-x errorBar's x is unchanged.
- **Test file:** ChartLowerTest.
- **Closes:** GAP-ERRORBAR-BANDCENTER. **Trace:** §1.1 row GAP-ERRORBAR-BANDCENTER.

### L22 — legend margin reserved for color-bearing text/errorBar `NEW`
- **Property:** a chart whose ONLY color-bearing mark is `text(color = _.region)` (or
  `errorBar(color = …)`) reserves the top legend strip (`hasLegend == true`), so the plotted glyphs
  do not overlap the legend; the plot's top y is shifted down by the reserved strip. A text/errorBar
  mark with NO color channel keeps `hasLegend == false` (byte-identical, no reservation).
- **Constrains:** `buildLayout.hasLegend` predicate for `Mark.Text` / `Mark.ErrorBar`.
- **Assertion shape:** with a color-bearing text-only chart, a rendered top reference (top tick /
  baseline / first glyph y) reflects the reserved `LegendReservedH` (assert the concrete shifted y ==
  reserved-strip y, vs the un-reserved y today); no-color co-pin asserts the un-shifted y.
- **Test file:** ChartLowerTest.
- **Closes:** GAP-LEGEND-MARGIN-TEXT-ERRORBAR. **Trace:** §1.1 row GAP-LEGEND-MARGIN; R-6 (coupling).

---

## F. DEAD-KNOB REMOVAL — API-shape guards (compile-level, not render)

### L23 — `AxisConfig.labelAllBands` removed from the public surface `NEW`
- **Property:** `AxisConfig.labelAllBands` no longer exists on the public surface (the dead field is
  gone). The band-label render behavior is unchanged: a 7-category band x-axis still produces 7 tick
  labels.
- **Constrains:** `AxisConfig` public shape; no render change.
- **Assertion shape:** API-shape guard (compile-level): no source/test references `labelAllBands`;
  the existing 7-label assertion (ChartAxisTest:1068, with the now-meaningless name corrected) stays
  green as the render regression guard. (This is a compile/API-shape guard, NOT a render test for the
  removed field; it was never read.)
- **Test file:** ChartAxisTest (retained 7-label assertion; name corrected).
- **Closes:** GAP-LABELALLBANDS (REMOVE). **Trace:** `02-public-api §2.2` (REMOVE locked) + §6
  Principle 3 (net shrink); Q-2.

### L24 — `AxisConfig.side` + `.left/.right/.top/.bottom` + `Side` enum removed; false scaladoc gone `NEW`
- **Property:** `AxisConfig.side`, the four setters `.left/.right/.top/.bottom`, and the `Side` enum
  no longer exist on the public surface; the FALSE scaladoc sentence (UI.scala:2489 "selects where
  the axis line and labels are drawn") is removed. `.xAxis(_.top)` no longer COMPILES (a silent
  runtime no-op becomes a compile error). No rendered output changes (nothing read `side`).
- **Constrains:** `AxisConfig` public shape, `Side` enum, axis-placement scaladoc.
- **Assertion shape:** API-shape / compile-level guard: no source/test references `.side`, `Side.`,
  or the four removed setters; the rewritten `ChartSpecTest` (was ChartSpecTest:90-97 reading
  `spec.yAxisCfg.side` / using `.left`) now asserts the surviving real behavior
  (`.yAxis(_.grid.ticks(5))` ⇒ `showGrid == true`, `tickCount == 5`) with the `.side` assertion
  dropped. (Compile-level + corrected positive assertion, NOT a render test for the removed knob.)
- **Test file:** ChartSpecTest (rewritten positive assertion) + module-wide grep guard.
- **Closes:** GAP-AXISCONFIG-SIDE (REMOVE). **Trace:** `02-public-api §2.3` (REMOVE locked) + §5
  Anti-case 3 + §6 Principle 2/3; Q-3 (REMOVE), R-1 (blast radius), R-2 (README sweep).

---

## Coverage check (all 14 GAPs ↦ leaves)

| GAP | Leaves | Family |
|-----|--------|--------|
| GAP-COLOR-GROUPEDBAR | L1, L10 (seq), L8 (absent) | COLOR |
| GAP-COLOR-TEXT | L2, L10, L8 | COLOR |
| GAP-COLOR-ERRORBAR | L3, L10, L8 | COLOR |
| GAP-COLOR-AREA-SIMPLE | L4 (color), L9 (split), L10, L8 | COLOR + AREA-SPLIT |
| GAP-RIGHTY-SCALE | L11, L12 (default copin), L19 (independence) | RIGHT-AXIS-SCALE |
| GAP-RIGHTY-GRID | L13 | RIGHT-AXIS-GRID |
| GAP-YAXIS-ROTATION | L14 (rotate), L15 (anchor), L17 (x copin) | Y-AXIS-CHROME |
| GAP-THEME-FONT | L16, L17 (x copin) | Y-AXIS-CHROME |
| GAP-LEGEND-MARGIN-TEXT-ERRORBAR | L22 | LEGEND-MARGIN |
| GAP-TRANS-BAR-CHANNELS | L18 | TRANS-BAR-CHANNELS |
| GAP-HIGHLIGHT-COVERAGE | L20 | HIGHLIGHT-COVERAGE |
| GAP-ERRORBAR-BANDCENTER | L21 | ERRORBAR-BANDCENTER |
| GAP-LABELALLBANDS | L23 | DEAD-KNOBS-REMOVED |
| GAP-AXISCONFIG-SIDE | L24 | DEAD-KNOBS-REMOVED |

Consistency co-pins (already-correct marks/behaviors re-pinned): L5 (line), L6 (point), L7
(stacked-bar), L8 (Absent byte-identity + INV-004 golden), L12 (right-axis default), L17 (x
tick-label chrome).

## Status tally

- **Total leaves:** 24.
- **NEW assertions:** 18 (L1, L2, L3, L4, L9, L10, L11, L13, L14, L15, L16, L18, L19, L20, L21, L22,
  L23, L24). **CO-PINS of existing behavior:** 6 (L5, L6, L7, L8, L12, L17).
- **Color-consistency leaves (Family A categorical, legend↔mark per mark):** 7 (L1, L2, L3, L4, L5,
  L6, L7) — 4 NEW (grouped-bar, text, errorBar, non-stacked area) + 3 co-pins (line, point,
  stacked-bar). With Sequential (L10) and the Absent byte-identity guard (L8), the color family
  spans L1..L10.
- **Test-file homes:** ChartLowerTest (L1-L4, L5-L7, L8b, L9, L10, L21, L22), ChartInvariantsTest
  (L8a golden), ChartAxisTest (L11b, L12a, L13, L14, L15, L16, L17, L19b), ScaleTest (L11a, L12b,
  L19a), ChartTransitionTest (L18), ChartInteractionTest (L20), ChartSpecTest (L24).

## Concrete-assertability flags (for design rework)

All 24 leaves are concretely assertable on rendered SVG output or public API shape. One leaf carries
a measurement caveat the plan must pin down precisely (NOT a blocker, flagged for the plan step to
make exact):

- **L22 (LEGEND-MARGIN):** the assertion reads a rendered top-reference y against the reserved
  `LegendReservedH` constant. The plan must pin the EXACT readback element (top tick y vs plot
  baseline vs first-glyph y) and the EXACT reserved-strip pixel so the "shifted down by the reserved
  strip" property is a concrete `==`, not a relative "is larger" check. The internal constant
  (`LegendReservedH` / `topPad`) is named in DESIGN §GAP-LEGEND-MARGIN; the plan must surface its
  value for the equality.
- **L11 (RIGHT-AXIS-SCALE):** the strongest assertion is a domain/kind READBACK on the factored
  `resolveYScale`/scale object (ScaleTest), which requires that helper to expose its resolved
  domain/kind to the test (it is `private[kyo]`-reachable per the test package). The rendered-pixel
  sub-assertion (ChartAxisTest) is always available as the fallback; the plan should confirm the
  readback path so the override (log/clamp/pad/reverse) is asserted at the scale level, not only via
  a pixel that could coincidentally match.

No leaf is un-assertable; the two flags are precision-of-readback notes for the plan, not design
reworks.
