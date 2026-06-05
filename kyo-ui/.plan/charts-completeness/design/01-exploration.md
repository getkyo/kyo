# 01 — Exploration: kyo-ui chart feature-completeness (14 gaps)

Scope: close the 14 feature-completeness gaps so the EXISTING chart functionality is complete and
CONSISTENT. No new mark types / scale kinds / channels (steering §3). All loci are in `shared/`.
Re-checked every gap-file claim against source; line numbers below are FRESH reads from the current
worktree (ChartLower.scala has drifted ~0-2 lines vs the gap file, which was analyzed at 3538-max;
the file is now 3990 lines, but every structural claim holds — see §6 re-check notes).

Path roots:
- LOWER = `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala` (3990 lines)
- SCALE = `kyo-ui/shared/src/main/scala/kyo/internal/Scale.scala` (544 lines)
- API   = `kyo-ui/shared/src/main/scala/kyo/UI.scala` (2879 lines)
- Tests = `kyo-ui/shared/src/test/scala/kyo/Chart*.scala` + `.../internal/ScaleTest.scala`

---

## 1. Module map (files / functions each gap touches, with line numbers)

### 1.1 LOWER — the per-mark lowerings and frame/scale builders

| Function | Lines | Role | Gaps touching it |
|----------|-------|------|------------------|
| `buildLayout` | 272-299 (`hasLegend` 278-285) | reserves legend margin; returns false for Text/ErrorBar (283-284) | GAP-LEGEND-MARGIN |
| `resolveAllScales` | 660-751 (right scale 744-748) | builds xs/ysL/ysR; right is hardcoded `Linear, nice=true` | GAP-RIGHTY-SCALE |
| `buildFrame` | 766-798 | dispatches buildYAxis (left 782, right 783-794), buildXAxis (795), buildLegend (796) | GAP-RIGHTY-SCALE/GRID/ROTATION/FONT, GAP-AXISCONFIG-SIDE |
| `buildYAxis` | 862-954 (grid gate 884; anchor 874; tick label 909-916; title 927-952) | left + right y-axis chrome; NO rotation/anchor/font | GAP-RIGHTY-GRID, GAP-YAXIS-ROTATION, GAP-THEME-FONT, GAP-AXISCONFIG-SIDE, GAP-LABELALLBANDS |
| `buildXAxis` | 957-1034 (`withFont` 975-977; anchor 969-972,1010; rotation 1014-1018; title 1024-1031) | x-axis chrome — the CORRECT pattern to mirror | prior-art for GAP-6/7 |
| `buildLegend` / legend items | 1036+; `resolvePalette` swatch 1069 | legend swatch colors honor colorScale | prior-art for GAP-1/2/3/4 |
| `themePalette` | 149-150 | by-index palette fallback (the WRONG source under a colorScale) | GAP-1/2/3 |
| `resolvePalette` | 1302-1315 | THE canonical colorScale resolver (Categorical 1304, Sequential 1306, Absent->theme/Default 1309-1314) | prior-art for GAP-1/2/3/4 |
| `collectColorCategoriesWithRaw` | (called 1858, 2152, 2292, 2360, 2789) | collects (label, raw) color categories in plottable order | GAP-1/2/3/4 |
| `resolveHighlight` | 1571-1582 | Maybe[Highlight] from interactionCfg + onHover/onSelect ref | GAP-HIGHLIGHT |
| `withHighlight` | 1630-1645 | wraps row-tagged shapes in a ref-driven Reactive | prior-art for GAP-HIGHLIGHT |
| `applyHighlightStyle` | 1592-1617 | maps Style props to SVG attrs | GAP-HIGHLIGHT (reused as-is) |
| `marksRegion` (static) | 1653-1721 | per-mark dispatch; call sites: line 1687, area 1691, point 1695-1706, rule 1708, text 1709, errorBar 1710 | GAP-1/2/3/4/13 (call-site threading) |
| `lowerBarSimple` | 1768-1842 (opacity 1815, tooltip 1819, label 1823, highlight 1841) | static simple bar — channel + highlight pattern | prior-art for GAP-12, GAP-13 |
| `lowerBarGrouped` | 1844-1929 (palette 1864-1878; Sequential-only routing 1872; case _ by-index 1873-1878; highlight 1928) | grouped bar drops Categorical colorScale | GAP-COLOR-GROUPEDBAR |
| `lowerBarStacked` | 1943+ (`resolvePalette` 1971) | stacked bar — colorScale honored | prior-art for GAP-1 |
| `resolvePaletteFromCfg` | 2118+ | Absent-colorScale categorical fallback | (referenced) |
| `lowerLine` | 2132-2162 (split-by-cat 2157-2161; `resolvePalette` 2154-2155; band-center 2198) | THE canonical color-split pattern | prior-art for GAP-1/2/3/4; GAP-13 |
| `lowerLineSeries` | 2216-2267 | one path per series | GAP-13 (series granularity) |
| `lowerText` | 2277-2342 (basePalette themePalette 2294; by-index 2295-2299; band-center 2320; opacity 2334) | text drops colorScale; NO `spec` param (sig 2277-2283) | GAP-COLOR-TEXT, GAP-HIGHLIGHT |
| `lowerErrorBar` | 2351-2423 (basePalette 2362; by-index 2363-2367; px=`xs.apply(x)` 2384 NO +bw/2) | errorBar drops colorScale; NOT band-centered; NO `spec` param (sig 2351-2357) | GAP-COLOR-ERRORBAR, GAP-ERRORBAR-BANDCENTER, GAP-HIGHLIGHT |
| `lowerArea` | 2432-2504 (non-stack branch 2449-2499; single path `defaultColor` 2483; band-center 2461) | non-stacked area never reads `mark.color` | GAP-COLOR-AREA-SIMPLE, GAP-HIGHLIGHT |
| `buildBandRibbon` | 2512+ | y0/y1 band form (defaultColor; NOT a listed gap) | — |
| `lowerAreaStacked` | 2583-2729 (`resolvePalette` 2613; band-center 2682) | stacked area — colorScale honored | prior-art for GAP-4 |
| `lowerPoint` | 2747-2920 (sig incl `highlight` 2757; `resolvePalette` 2789; band-center 2825; `withHighlight` 2919) | THE complete reference mark (colorScale + highlight + channels) | prior-art for GAP-1/2/3/13 |
| `lowerRuleChildren` | 2979-3039 (band-center 2993, 3009) | rule band-centering pattern | prior-art for GAP-14 |
| `lowerBarSimpleWithTransitions` | 3235-3288 (rect 3274/3279 — only x/y/w/h/fill) | animated bar drops opacity/label/tooltip | GAP-TRANS-BAR-CHANNELS |
| `lowerLineWithTransitions` | 3310-3381 (`resolvePalette` 3338; split 3351) | animated line honors colorScale — prior-art | prior-art for GAP-12 (mirroring) |
| `lowerAreaWithTransitions` | 3397-3446 (defaultColor 3412/3417) | animated area uses defaultColor only | GAP-COLOR-AREA-SIMPLE (transitions twin) |
| `marksRegionWithTransitions` | 3466-3552 | animated dispatch; call sites: bar 3503, line 3508/3515, area 3512/3516, point 3519-3529, rule 3533, text 3536, errorBar 3538 | GAP-1/2/3/4/12/13 (transitions call-site threading) |

### 1.2 SCALE
- `Scale.fit` dispatch: `Scale.scala:64-72` (Linear 65, Log 66, Band 67, Time 68, Ordinal 69, Point 70,
  Symlog 71). `nice` reaches Linear+Time; `clamp` reaches Linear/Log/Symlog. Right-Y reuses this via
  `Scale.fit(Kind.Linear, ...)` at LOWER:747. No SCALE change is required for any gap (the gaps are in
  the LOWER threading, not in fit itself).

### 1.3 API (public surface)
| Type / member | Lines | Gap relevance |
|---------------|-------|---------------|
| `ChartSpec` case class | 2258-2279 (`yScaleOverride` 2268; NO `yScaleRight*` field) | GAP-RIGHTY-SCALE (needs a right override source decision) |
| `ChartSpec.xScale`/`yScale` builders | 2391/2395 (NO `yScaleRight`) | GAP-RIGHTY-SCALE |
| `ChartSpec.yAxisRight` builder | 2374-2376 | GAP-RIGHTY-SCALE/GRID |
| `AxisConfig` case class | 2494-2519 (scaladoc 2483-2493; `side` field 2495; `labelAllBands` 2504; `tickRotation` 2500; `tickAnchor` 2501; `reversed` 2502; `padding` 2503) | GAP-AXISCONFIG-SIDE, GAP-LABELALLBANDS, GAP-YAXIS-ROTATION |
| `AxisConfig` setters | `left/right/bottom/top` 2506-2509; `grid` 2511; `reverse` 2514; `pad` 2515; `rotateTicks` 2516; `anchor` 2517 | GAP-AXISCONFIG-SIDE, GAP-RIGHTY-GRID |
| `AxisConfig.default` | 2522 (`AxisConfig(Absent, Absent, false, 5, Absent)`) | (constructor arity if a field is removed) |
| `Side` enum | 470-471 (`Left, Right, Top, Bottom`) | GAP-AXISCONFIG-SIDE (remove vs wire) |
| `LegendConfig` + `colorScale*` | 2540-2613 (`colorScale[K]` 2581; `colorScale(String=>Color)` 2599; `colorScaleSequential` 2608/2612) | the knob mis-honored by GAP-1/2/3/4 |
| `Theme` + `font`/`fontSize` | 2639-2664 (`fontFamily` 2645; `fontSize` 2646; `font` 2661; `fontSize` setter 2664) | GAP-THEME-FONT |
| `ScaleOverride` | 2711-2732 (`kind`/`nice`/`clamp`/`pad`; setters 2717-2727) | GAP-RIGHTY-SCALE |
| `InteractionConfig` | 2337+ (`hoverHighlight`/`selectHighlight` + styles, read by resolveHighlight 1575-1579) | GAP-HIGHLIGHT |
| `Axis` enum (`Left, Right`) | 442 | mark axis binding (marksRegion 1671-1677) |

### 1.4 Tests (reproduce-before-fix loci; all extend the module `Test` base class)
- `ChartLowerTest extends Test` (`ChartLowerTest.scala:16`); `ChartAxisTest extends Test` (`:20`);
  `ScaleTest extends Test` (`internal/ScaleTest.scala:6`).
- Grouped-bar color: existing Test 8 (`ChartLowerTest.scala:369-409`, no colorScale) and Test 8b
  (`:444-487`, Sequential colorScale on grouped bar). NO Categorical-colorScale grouped-bar test.
- Stacked-bar colorScale legend: `ChartAxisTest.scala:216-260` (`colorScale[Region]` assigns swatch
  fills) — pattern for the render-fill assertions the new tests should make.
- errorBar themePalette (regression guard to PRESERVE on the GAP-3 fix): `ChartLowerTest.scala:~1728`
  ("errorBar mark uses theme.palette colors, not DefaultPalette, under a custom theme", custom palette
  `#cc00cc`/`#00cccc`, `errorBar(... color = _.region)`). The text twin is leaf 17 just above (~1700).
- errorBar geometry: `ChartLowerTest.scala:1175-1179` (vertical line + 2 caps + center circle).
- text factory shape: `ChartSpecTest.scala:367-368` (AST-shape only, no rendered fill under colorScale).
- x-tick rotation: `ChartAxisTest.scala:~972` (`rotateTicks` on X only).
- ScaleTest: unit `Scale.fit` clamp/nice/pad (`internal/ScaleTest.scala`), no right-axis-through-spec
  driving.

---

## 2. Prior art — the CORRECT patterns already in the codebase to MIRROR

This is a consistency campaign: each fix must match an EXISTING correct implementation, not invent.

### 2.1 colorScale resolution (GAP-1/2/3/4)
The canonical resolver is `resolvePalette(spec, categories)` (LOWER:1302-1315): Categorical maps each
raw value through the user fn (1304-1305), Sequential interpolates (1306-1308), Absent falls back to
`theme.palette` then `DefaultPalette` by index (1309-1314). It is used by:
- the legend swatches: `buildLegend` -> `resolvePalette(spec, categories)` (1069);
- stacked bar: `lowerBarStacked` 1971 (`Present(s) => resolvePalette(s, groupCats)`);
- stacked area: `lowerAreaStacked` 2613;
- line (the cleanest split pattern): `lowerLine` 2147-2161 — collect cats via
  `collectColorCategoriesWithRaw` (2152), `palette = resolvePalette(s, cats)` (2154-2155), then
  `colorKeys.zipWithIndex.map` filtering rows per category (2157-2161). The static line path passes
  `spec` (`Present(s)`, 1687) and falls back to `DefaultPalette` when spec is Absent (2156);
- point: `lowerPoint` 2789;
- animated line: `lowerLineWithTransitions` 3338 (mirrors lowerLine for the SMIL path).

The four gap marks deviate: grouped-bar routes ONLY `Present(_: Sequential)` through resolvePalette
(1872) and sends Categorical+Absent down the by-index `themePalette` path (1873-1878); text (2294-2299)
and errorBar (2362-2367) build a by-index `themePalette` palette and never see `spec`/`colorScale` (no
`spec` parameter in their signatures); non-stacked area fills a single path with `defaultColor` (2483)
and never reads `mark.color`. The FIX is to make each mirror lowerLine/lowerBarStacked: when a
Categorical (or any) colorScale is set on `spec.legendCfg.colorScale`, resolve via `resolvePalette`;
keep the by-index/themePalette path ONLY for the Absent case (preserving the
`ChartLowerTest.scala:~1728` themePalette regression guard).

### 2.2 Band-centering on a Band x (GAP-14)
Every point-like mark adds half the bandwidth so it centers under the (centered) tick label;
`xs.bandwidth` is 0 on continuous scales so it is a no-op there. Pattern occurrences:
`px = xs.apply(x) + xs.bandwidth / 2.0` at line (2198), area (2461), point (2825), text (2320); rule
adds it only for `Domain.Category` (2993, 3009). errorBar deviates: `px = xs.apply(x)` (2384), no
`+ bandwidth/2`. The FIX is exactly `xs.apply(x) + xs.bandwidth / 2.0` at 2384, matching the others.

### 2.3 Built-in highlight (INV-024) (GAP-13)
`resolveHighlight(spec)` (1571-1582) returns `Present(Highlight(ref, style))` only when
`selectHighlight`/`hoverHighlight` is set AND the matching `onSelect`/`onHover` ref exists, else Absent.
`withHighlight(tagged, highlight)` (1630-1645) returns shapes unchanged on Absent (1635) and otherwise
wraps the row-tagged shapes in a single ref-driven `Reactive` (1637-1644) that calls
`applyHighlightStyle` on the active row's shape. Applied today only at `lowerBarSimple` 1841,
`lowerBarGrouped` 1928, `lowerPoint` 2919 (point already threads `highlight` as a parameter, sig 2757,
populated at the call site 1705). The FIX threads `highlight` (from `resolveHighlight(Present(s))`)
into line/area/text/errorBar and wraps their row-tagged shapes via `withHighlight`. Per-row marks
(text 2337, errorBar) map cleanly to row tags; line/area are series-level (one path per series) so the
"row" is the series-representative row — an open question (§5).

### 2.4 Axis chrome (GAP-6 rotation/anchor, GAP-7 font)
`buildXAxis` (957-1034) is the complete reference: it maps `cfg.tickAnchor` to an SVG anchor
(969-972), applies it at 1010, defines `withFont` (975-977) applying `theme.fontFamily`/`fontSize`,
wraps the tick label base in `withFont` (1005-1013), and rotates about the anchor point when
`cfg.tickRotation != 0.0` (1014-1018). `buildYAxis` (862-954) does NONE of these: anchor is hardcoded
by side (874), tick labels (909-916) have no font and no rotation. The FIX factors the
anchor/rotation/font application out of buildXAxis (or duplicates the three small blocks) into
buildYAxis tick labels, and applies `withFont` to the axis titles in both builders (currently neither
buildXAxis title at 1024-1031 nor buildYAxis title at 927-952 fonts the title) and to legend text.

### 2.5 Right scale parity (GAP-5, GAP-8)
The left scale path in `resolveAllScales` (708-741) is the reference: `effectivePad` resolving
ScaleOverride-over-AxisConfig (671-672), `nice` from override (711), `reversed` range swap (729),
`kind` from override (714-723), `clamp` (740), `padExtent` (730-739), then `Scale.fit`. The right
scale (744-748) reads none of these — it hardcodes `Scale.fit(Kind.Linear, rExt, baseline, plotY,
nice = true)`. The FIX mirrors the left resolution for the right extent, reading the chosen right
override source (§5 open question) plus `yAxisRightCfg` reversed/padding. GAP-8 (grid) sits directly
on this: `buildYAxis` 884 gates gridlines `showGrid && !isRight`; the left axis already draws
full-width horizontal gridlines (885-891), so the fix is to allow the right axis to request the SAME
horizontal lines when `cfg.showGrid`, guarded against double-draw when both axes set grid.

### 2.6 Animated bar channels (GAP-12)
`lowerBarSimple` (1768-1842) is the static reference applying opacity (1815), tooltip (1819), label
(1823). `lowerBarSimpleWithTransitions` (3235-3288) emits the rect at 3274 (no anim) / 3279 (anim)
with only x/y/width/height/fill plus SMIL animates; it never applies those three channels. The FIX
applies the same opacity/tooltip/label handling inside the transitions rect (mirror lowerBarSimple, or
extract a shared channel-application helper).

---

## 3. Per-gap fix surface (all 14)

For each: concrete locus, INTERNAL vs PUBLIC, mirror target, reproduce-before-fix test locus +
assertion. Dependencies noted.

### GAP-COLOR-GROUPEDBAR (major) — INTERNAL
- Locus: `lowerBarGrouped` palette block, LOWER:1869-1878. Change the `case _` arm (1873-1878) so when
  `s.legendCfg.colorScale` is a Categorical, resolve via `resolvePalette(s, colorCats)` (mirror the
  Sequential arm at 1872 and lowerBarStacked 1971); keep the by-index themePalette only for Absent.
  No signature change (`spec` already threaded, sig 1851).
- Test: `ChartLowerTest.scala` — grouped bar (multiple regions share an x-band so `dodge`) +
  `.legend(_.colorScale[Region](...))`; assert each dodged rect's `fill` equals the colorScale color
  for its region (mirror `ChartAxisTest.scala:216-260` fill assertions). Fails today (bars get
  themePalette-by-index).

### GAP-COLOR-TEXT (major) — INTERNAL
- Locus: `lowerText`, sig 2277-2283 (add a `spec`/`colorScale` source) + palette block 2294-2299 +
  fill resolution 2322-2327. Thread `Maybe[ChartSpec[A]]` (or `Maybe[LegendConfig.ColorScale]`) and
  resolve via `resolvePalette` when set; keep themePalette for Absent. Update call sites: marksRegion
  1709, marksRegionWithTransitions 3536.
- Mirror: lowerPoint/lowerLine color resolution.
- Test: `ChartLowerTest.scala` (near leaf 17, ~1700) — text mark + colorScale; assert each glyph's
  `fill` equals the colorScale color. PRESERVE the existing themePalette test (leaf 17) for the Absent
  path.

### GAP-COLOR-ERRORBAR (major) — INTERNAL
- Locus: `lowerErrorBar`, sig 2351-2357 + palette 2362-2367 + color resolution 2388-2394. Same
  treatment as GAP-COLOR-TEXT. Call sites: marksRegion 1710, marksRegionWithTransitions 3538.
- Mirror: lowerPoint/lowerLine.
- Test: `ChartLowerTest.scala` — errorBar + colorScale; assert all 3 parts of a row share ONE stroke
  equal to the colorScale color. PRESERVE the themePalette guard at ~1728 for Absent.

### GAP-COLOR-AREA-SIMPLE (major) — INTERNAL (+ overlap-policy decision, §5)
- Locus: `lowerArea` non-stacked branch 2449-2499 (single path, defaultColor 2483) and its animated
  twin `lowerAreaWithTransitions` 3397-3446 (defaultColor 3412/3417). When `mark.color` is Present and
  no stack, split rows by category (mirror lowerLine 2157-2161) and emit one closed area path per
  series colored via `resolvePalette`; keep the single-path defaultColor behavior only when
  `mark.color` is Absent. `mark.color` exists on `Mark.Area`.
- Mirror: lowerLine split + lowerAreaStacked resolvePalette (2613).
- Test: `ChartLowerTest.scala` — non-stacked `area(color=_.series)` over 2 series; assert N distinct
  area paths each with the resolvePalette fill (fails today: one merged blob).
- Dependency: overlap policy (z-order vs opacity) is an open question (§5).

### GAP-RIGHTY-SCALE (major) — PUBLIC (override-source decision, §5)
- Locus: `resolveAllScales` right block 744-748; mirror the left resolution 708-741. Reads chosen
  right override source + `yAxisRightCfg` reversed/padding. May add a `yScaleRightOverride` field to
  `ChartSpec` (2258-2279) + a `yScaleRight` builder (next to 2395) + `Side`-free plumbing through
  `buildFrame`/marksRegion.
- Test: `ChartLowerTest.scala`/`ChartAxisTest.scala` — dual-axis chart with `axis = Axis.Right` mark +
  right override `.log`/`.withClamp`/`.withPad`/`.yAxisRight(_.reverse)`; assert the right series'
  projected pixels reflect the override (readback). Today all no-op.
- Dependency: GAP-RIGHTY-GRID sits ON this (both touch the right axis / buildYAxis isRight path).

### GAP-YAXIS-ROTATION (major) — PUBLIC behavior, no new API
- Locus: `buildYAxis` tick-label block 909-916. Apply `cfg.tickAnchor` (replace the hardcoded 874
  anchor) and `cfg.tickRotation` (rotate about the label anchor), mirroring buildXAxis 969-972,
  1005-1018. Affects both left and right y.
- Mirror: buildXAxis anchor + rotation.
- Test: `ChartAxisTest.scala` (next to the x rotateTicks test ~972) — `.yAxis(_.rotateTicks(-45))`;
  assert the y tick-label element carries a `Rotate` transform. Today: no transform.

### GAP-THEME-FONT (major) — PUBLIC behavior, no new API
- Locus: `withFont` is local to buildXAxis (975-977). Apply equivalent font to: buildYAxis tick labels
  (909-916), axis titles in buildYAxis (927-952) AND buildXAxis (1024-1031), and legend text
  (buildLegend items / sequential / size). Optionally mark `label` text (text mark 2328-2337).
- Mirror: buildXAxis `withFont`.
- Test: `ChartAxisTest.scala` — `.theme(_.font("monospace").fontSize(14))`; assert y tick label, an
  axis title, and a legend label carry the fontFamily/fontSize. Today: only x ticks.

### GAP-RIGHTY-GRID (minor) — PUBLIC behavior (`.yAxisRight(_.grid)`)
- Locus: `buildYAxis` grid gate 884 (`cfg.showGrid && !isRight`). Allow the right axis to draw the
  same horizontal gridlines; guard against double-drawing when both axes set `grid`.
- Test: `ChartAxisTest.scala` — `.yAxisRight(_.grid)` on a dual-axis chart; assert horizontal gridline
  elements exist for the right axis. Today: none.
- Dependency: sits on GAP-RIGHTY-SCALE (same right-axis path / isRight branch).

### GAP-LABELALLBANDS (minor) — PUBLIC dead-knob decision (§5, escalate)
- Locus: `AxisConfig.labelAllBands` field (API:2504), no setter, ZERO reads in LOWER/SCALE. Either
  WIRE (thin Band tick labels to every Nth band when false — would touch buildXAxis/buildYAxis tick
  loops) or REMOVE the field (touches the `AxisConfig` ctor + `AxisConfig.default` arity 2522).
- Test (if wired): assert label count thins when false. (If removed: a compile-level removal; no
  render test.)
- Note: value-underdetermined + public-surface -> escalate ONE yes/no (steering §6).

### GAP-AXISCONFIG-SIDE (minor) — PUBLIC dead-knob decision (§5, escalate)
- Locus: `AxisConfig.side` field (API:2495), setters `left/right/bottom/top` (2506-2509), `Side` enum
  (470-471); scaladoc 2489 claims "selects where the axis line and labels are drawn" (FALSE). ZERO
  reads in LOWER. Either HONOR `side` in `buildFrame` axis dispatch (766-797; today positional: x via
  buildXAxis bottom, y via the `isRight` flag from `yAxisRightCfg` presence) or REMOVE the field +
  setters + fix the scaladoc.
- Test (if wired): `.xAxis(_.top)` moves the x-axis; assert label Y. (If removed: compile-level.)
- Note: value-underdetermined + public-surface -> escalate ONE yes/no.

### GAP-LEGEND-MARGIN-TEXT-ERRORBAR (minor) — INTERNAL, depends on GAP-2/3 decision
- Locus: `buildLayout` `hasLegend` predicate (278-285): returns false for Text (283) and ErrorBar
  (284) even with a color channel, but `findColorChannel` (in buildLegend) DOES render a legend for
  them, so the legend strip overlaps the plot (no `topPad` reserved, 296). Fix: include Text/ErrorBar
  color channels in `hasLegend` so the strip is reserved — this MUST agree with the GAP-2/3 color fix
  (if text/errorBar legends are kept, reserve margin).
- Test: `ChartLowerTest.scala` — chart whose only color-bearing mark is `text(color=..)`; assert
  `plotY`/topPad reserves the legend strip (legend does not overlap data). Today: not reserved.
- Dependency: resolution coupled to GAP-COLOR-TEXT / GAP-COLOR-ERRORBAR (keep-vs-drop the
  text/errorBar legend consistently).

### GAP-TRANS-BAR-CHANNELS (minor) — INTERNAL
- Locus: `lowerBarSimpleWithTransitions` rect emission 3272-3284 (only x/y/w/h/fill at 3274/3279).
  Apply opacity/tooltip/label mirroring static `lowerBarSimple` (1815/1819/1823).
- Test: `ChartTransitionTest.scala` (or `ChartLowerTest.scala`) — live/animated simple bar with
  opacity+label+tooltip; assert the animated rect carries fillOpacity + title + label. Today dropped.

### GAP-HIGHLIGHT-COVERAGE (minor) — INTERNAL (+ line/area granularity decision, §5)
- Locus: thread `highlight` (from `resolveHighlight(Present(s))`) into `lowerLine` (static call 1687),
  `lowerArea` (1691), `lowerText` (1709), `lowerErrorBar` (1710) and wrap row-tagged shapes via
  `withHighlight` (mirror lowerPoint sig 2757 + call 1705 + 2919). text/errorBar are per-row (clean);
  line/area are series-level (representative-row tag — open question §5).
- Test: `ChartInteractionTest.scala` — `.onSelect(ref).interaction(_.highlightSelect)` on a
  line/text/errorBar chart; drive the ref and assert the active element gets the highlight style.
  Today: no highlight on those marks.

### GAP-ERRORBAR-BANDCENTER (minor) — INTERNAL
- Locus: `lowerErrorBar` px 2384 (`xs.apply(x)`); change to `xs.apply(x) + xs.bandwidth / 2.0`
  (mirror line 2198 / text 2320). Note caps at 2404-2405 use `px ± halfCap`, so centering `px` centers
  the whole error bar.
- Test: `ChartLowerTest.scala` — errorBar on a Band (String/enum) x; assert the vertical line's `x1`
  equals band-left + bandwidth/2 (co-aligned with a bar/point at the same category). Today: left edge.

---

## 4. Cross-platform note

Every locus above is in `kyo-ui/shared/src/main/scala/kyo/` (LOWER, SCALE, API) and every test target
is in `kyo-ui/shared/src/test/scala/kyo/`. The lowering is a pure SVG-string projection (builds
`Svg.*` element values; no `java.*`/DOM/platform APIs — the only `java.lang` use is
`java.lang.Double.isFinite` at e.g. 2463/2201, available on all three platforms). No gap requires a
jvm/js/native-only file. Steering §4 satisfied: all fixes + tests land in `shared/`; the final gate is
JVM + JS + Native green.

---

## 5. Open questions (feed flow-resolve-open)

PUBLIC-SURFACE + value-underdetermined (escalate ONE yes/no each per steering §6):
1. **GAP-RIGHTY-SCALE override source.** Add a dedicated `yScaleRight`/`yScaleRightOverride` (new
   public builder + ChartSpec field), OR reuse the existing `yScaleOverride` for right-bound marks?
   New field is the honest dual-axis model but widens the public surface; reuse is smaller but couples
   left+right scale kinds. (Recommendation candidate: dedicated, since left and right axes have
   independent domains; flag for user.)
2. **GAP-LABELALLBANDS** — WIRE (thin Band labels to every Nth band when false) vs REMOVE the dead
   field. Scaladoc gives no behavior today; no setter exists. (Recommendation candidate: REMOVE, since
   there is no setter and no documented behavior; flag.)
3. **GAP-AXISCONFIG-SIDE** — WIRE `side` into buildFrame axis dispatch vs REMOVE the field + setters
   and correct the false scaladoc (2489). Placement is fully positional today. (Recommendation
   candidate: REMOVE, since axis placement is otherwise determined positionally and by `yAxisRightCfg`;
   flag.)

INTERNAL / bind to the existing pattern (no escalation, but a deliberate choice within the pattern):
4. **GAP-COLOR-AREA-SIMPLE overlap policy** — when splitting non-stacked area into per-series paths,
   z-order (later series on top, opaque) vs reduced opacity for visibility. The existing non-stacked
   area uses fillOpacity 0.7 (2478); the natural bind is per-series paths at the same 0.7 opacity in
   data order (matches the single-path opacity behavior).
5. **GAP-HIGHLIGHT line/area granularity** — series-level (highlight the whole series path on the
   representative-row ref match) vs row-level. line/area lower to one path per series with a
   representative row, so series-level matches the existing series-granularity interaction design
   (documented at 2128-2131); bind to series-level for line/area, per-row for text/errorBar.
6. **GAP-LEGEND-MARGIN coupling** — keep-vs-drop the text/errorBar legend must be ONE decision shared
   with GAP-2/3 (if the marks honor colorScale and render a meaningful legend, reserve the margin).

Out of scope (steering §3; do NOT expand into these): morphSteps, structural line/area path-morph,
per-row interaction granularity as a NEW feature, right-axis Log/Time as a NEW capability beyond
honoring existing knobs, animating opacity/label transitions themselves.

---

## 6. Re-check of gap-file claims (high-trust input, confirmed against source)

All 14 gaps confirmed against fresh reads. Notable refinements (claims that held but with detail):
- **Signatures**: the gap file says "thread `spec`/`legendCfg.colorScale` into lowerText/lowerErrorBar".
  Confirmed lowerText (sig 2277-2283) and lowerErrorBar (sig 2351-2357) take `defaultColor` + `theme`
  but NO `spec`/`colorScale` — so the fix adds a parameter (INTERNAL signature delta). lowerPoint
  ALREADY takes `highlight` (sig 2757) and `theme`, which is the exact template.
- **Line drift**: ChartLower.scala is now 3990 lines (gap file analyzed at ~3538 max). All cited line
  numbers land within 0-2 lines of fresh reads; no claim moved to a different function. Examples
  verified at fresh lines: grouped-bar 1869-1878, text 2294-2299, errorBar 2362-2367 + px 2384, area
  2449-2499, right scale 744-748, buildYAxis grid 884 + anchor 874, buildXAxis withFont 975-977 +
  rotation 1014-1018, hasLegend 278-285, withHighlight 1630-1645, lowerBarSimpleWithTransitions
  3272-3284.
- **GAP-RIGHTY-SCALE public surface**: confirmed `ChartSpec` has only `yScaleOverride` (2268), only
  `xScale`/`yScale` builders (2391/2395) — there is genuinely NO right-axis scale-override source
  today, so the override-source question (§5.1) is real and public.
- **NO gap-file claim failed re-check.** One stylistic note: the gap file cites "Test 8c
  (ChartAxisTest.scala:557)" for stacked-bar colorScale; the stacked-bar colorScale legend test is at
  ChartAxisTest.scala:507-560 region and the canonical fill-assertion pattern is at
  ChartAxisTest.scala:216-260 — both confirm the claim (stacked path honors colorScale; grouped does
  not), so the substance holds even though the exact line cited is the test's setup tail rather than
  its header.
