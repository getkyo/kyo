# 05 — Phased implementation plan: kyo-ui chart feature-completeness (14 gaps)

This is the /flow plan-for-review. It closes ALL 14 feature-completeness gaps. Implementation does
NOT start until the user approves. The machine-readable sibling is `05-plan.yaml` (one entry per
phase).

## Reading rules (what this plan is and is not)

- **No scope cuts.** Every one of the 14 gaps appears as a concrete phase deliverable. None deferred,
  dropped, or "revisit later". The coverage map at the end lists GAP-ID -> phase for all 14.
- **Ordering is technical-dependency only.** Phases are ordered by what must compile/exist before
  what, NEVER by priority, importance, or severity. Each phase states its dependency rationale
  ("Phase X must follow Phase Y because ..."). Severity is recorded as a neutral reviewer attribute
  (`severity_attr`, taken verbatim from `feature-completeness-gaps.md`), never used to order or to
  justify a cut.
- **Reproduce-before-fix per phase.** Each phase names the failing leaf test(s) (L1..L24 from
  `04-invariants.md`) written FIRST, which must fail for the right reason (the actual symptom), then
  the fix, then kept as permanent regression guards. Every leaf asserts a concrete rendered value or a
  concrete public-API shape.
- **Byte-identity is never weakened.** The Absent/themePalette fallback co-pin (L8) stays
  byte-identical across every phase; INV-004 golden is unchanged. The right-axis default (L12), the x
  tick-label chrome (L17), and the already-correct line/point/stacked-bar color paths (L5/L6/L7) are
  re-pinned the same way.
- **Cross-platform.** All sources land in `kyo-ui/shared/src/main`, all tests in
  `kyo-ui/shared/src/test`. Per-phase verification is `targeted` (JVM only). The FINAL phase (P13)
  runs `cross-platform-full` (JVM + JS + Native clean-build green). Beware the stale `.sjsir`/`.tasty`
  false green (00-guides Trap 6): force a clean recompile per platform; a no-"Compiling" log is RED.
- **Public-decision defaults baked in.** The 3 user decisions in `03b-user-escalations.md` are
  implemented as the RECOMMENDED defaults: Q-1 = `yScaleRight` candidate B; Q-2 = REMOVE
  `labelAllBands`; Q-3 = REMOVE `side`. Each affected phase (P11, P12, P13) carries an explicit
  "if the user picks the alternative" note so the plan is reviewable against either answer.

## Dependency overview

Two enabling refactors (shared helpers) land first; the gaps that reuse a helper follow it; the
right-axis grid follows the right-axis scale; the legend margin follows the text/errorBar color fix;
the dead-knob removals land as one AxisConfig-cleanup unit; the README + cross-platform gate is last.

```
P1  applyBarChannels (extract)         ─────────────► P7  animated-bar channels
P2  tickLabel/withFont/toSvgAnchor ────────────────► P8  y-axis rotation/anchor/font
P3  errorBar band-center
P4  grouped-bar colorScale
P5  area split + colorScale ───────────────────────► P9  highlight (area paths)
P6  text/errorBar colorScale ──┬────────────────────► P9  highlight (spec param)
                               └────────────────────► P10 legend margin (keep-legend decision)
P7  animated-bar channels      (needs P1)
P8  y-axis chrome              (needs P2)
P9  highlight coverage         (needs P5 + P6)
P10 legend margin              (needs P6)
P11 right scale + right grid   (grid follows scale within the phase)   [PUBLIC: Q-1]
P12 remove dead AxisConfig knobs (+ ~84-site rewrite, doctest later)   [PUBLIC: Q-2, Q-3] ─► P13
P13 README + doctest + cross-platform-full clean gate  (needs ALL)
```

Longest dependency chain (4 links): **P2 -> P8** for chrome and **P1 -> P7** for channels are 2-link;
the longest is **P6 -> P9 -> (nothing) ** combined with the final gate:
**P5/P6 -> P9 -> P13** and **P6 -> P10 -> P13** and **P11 -> P13** and **P12 -> P13** are all 3-link
chains terminating at P13. The single longest is **P5 -> P9 -> P13** = 3 phases / 3 links (area split
feeds highlight feeds the final gate). No phase sits more than 3 links deep.

---

## Phase P1 — Extract shared bar-channel helper (`applyBarChannels`)

- **Gaps closed:** none (enabling refactor; unblocks P7). **severity_attr:** n/a.
- **Dependency:** depends on nothing. Must precede P7, which calls `applyBarChannels` from
  `lowerBarSimpleWithTransitions`; the helper must exist and be proven byte-identical on the static
  path first.
- **Surface-touching:** no.
- **Change:** factor the `lowerBarSimple` opacity/tooltip/label block (LOWER:1815-1836) into a private
  `applyBarChannels(rect, mark, row, barX, barW, barY, fill): (Svg.SvgElement, Chunk[Svg.SvgElement])`
  (signature locked in DESIGN §0.2). `lowerBarSimple` becomes a single call into it. Pure extraction,
  no behavior change.
- **Files:** `ChartLower.scala`.
- **Reproduce-before-fix:** no NEW leaf. The EXISTING `lowerBarSimple` opacity/tooltip/label
  assertions in `ChartLowerTest` are the refactor guard (they fail if the extraction drifts).
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ChartLowerTest'`.
- **Estimate:** ~40 LOC, ~35 min.

## Phase P2 — Extract shared tick-label chrome (`tickLabel` + `withFont` + `toSvgAnchor`)

- **Gaps closed:** none (enabling refactor; unblocks P8). **severity_attr:** n/a.
- **Dependency:** depends on nothing. Must precede P8, which routes BOTH x and y tick labels through
  this shared helper. This phase hoists `withFont` (975-977), `toSvgAnchor` (969-972), and the
  `tickLabel` block (1005-1018) out of `buildXAxis` and re-routes `buildXAxis` through them, proving x
  output byte-identical BEFORE y is added. `tickLabel` takes a RESOLVED anchor param (R-9) so
  `buildYAxis` can later pass its side-default anchor without guessing.
- **Surface-touching:** no.
- **Change:** per DESIGN §0.3. `buildXAxis` tick labels now call `tickLabel(...)`; x gridline geometry
  and tick-mark geometry stay inline (x-specific).
- **Files:** `ChartLower.scala`.
- **Reproduce-before-fix:** **L17 (CO-PIN)** — the existing x-axis rotateTicks/anchor/font tests
  (ChartAxisTest:972/987 + x-tick font test) stay byte-identical through the new shared helper.
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ChartAxisTest'`.
- **Estimate:** ~60 LOC, ~45 min.

## Phase P3 — errorBar band-centering on Band x

- **Gaps closed:** GAP-ERRORBAR-BANDCENTER. **severity_attr:** minor.
- **Dependency:** depends on nothing. Placed before P6 only to keep the two `lowerErrorBar` edits
  (this geometry fix and P6's colorScale spec param) sequential and conflict-free; this is an edit
  ordering, not a logical dependency.
- **Surface-touching:** no.
- **Change:** `lowerErrorBar` px (LOWER:2384) `xs.apply(x)` -> `xs.apply(x) + xs.bandwidth / 2.0`
  (mirror line 2198 / area 2461 / point 2825 / text 2320). No-op on continuous x.
- **Files:** `ChartLower.scala`.
- **Reproduce-before-fix:** **L21 (NEW)** — errorBar on a Band x; assert vertical line `x1` ==
  band-left + bandwidth/2, co-aligned with a bar/point at the same category; continuous-x errorBar
  unchanged. Fails today (left edge).
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ChartLowerTest'`.
- **Estimate:** ~30 LOC, ~30 min.

## Phase P4 — Grouped-bar colorScale resolution (categorical + sequential)

- **Gaps closed:** GAP-COLOR-GROUPEDBAR. **severity_attr:** major.
- **Dependency:** no signature change (`spec` already threaded at 1851, `colorCats` already collected
  at 1858). Depends only on the §0.1 colorScale-vs-Absent pattern, which needs no prior phase. Opens
  the COLOR family with the no-signature case before the signature-delta color phases (P5, P6).
- **Surface-touching:** no (public knobs `.legend(_.colorScale)` + `color =` already exist).
- **Change:** per DESIGN §GAP-COLOR-GROUPEDBAR — the `lowerBarGrouped` palette block (1869-1878)
  routes a Present colorScale of EITHER variant through `resolvePalette(s, colorCats)`; the Absent arm
  is the existing by-index `basePalette` code verbatim.
- **Files:** `ChartLower.scala`.
- **Reproduce-before-fix:** **L1 (NEW)** grouped/dodged rect fill == colorScale(region) + legend
  agreement (fails today: themePalette by index); **L10 (NEW)** Sequential arm two distinct concrete
  hex fills, never `url(#...)`; **L8 (CO-PIN)** no-colorScale grouped bar byte-identical (Leaf 16
  #3b82f6/#f97316; INV-004 golden unchanged).
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ChartLowerTest'`.
- **Estimate:** ~70 LOC, ~50 min.

## Phase P5 — Non-stacked area color split + colorScale (`buildSimpleAreaPath` extraction)

- **Gaps closed:** GAP-COLOR-AREA-SIMPLE. **severity_attr:** major.
- **Dependency:** depends only on the §0.1 pattern + the §0.5 overlap policy. Introduces the private
  `buildSimpleAreaPath` helper that factors the single-series path body so the Absent-color arm calls
  it once and the Present-color arm calls it per series (single-series byte-identity). Must precede P9,
  which tags the per-series area paths this phase produces.
- **Surface-touching:** no (output-semantics change only; `area(color =)` already exists — DESIGN
  §2.4). N overlapping per-series paths at fillOpacity 0.7 in data order (R-5).
- **Change:** per DESIGN §GAP-COLOR-AREA-SIMPLE. The animated twin `lowerAreaWithTransitions` inherits
  the multi-path `lowerArea` (stable category-order emission).
- **Files:** `ChartLower.scala`.
- **Reproduce-before-fix:** **L9 (NEW)** N distinct closed paths each `fill-opacity="0.7"`, not one
  blob; single-series still exactly ONE path at 0.7 (byte-identity edge); **L4 (NEW)** each per-series
  path fill == colorScale(series) + legend agreement; **L10 (NEW)** Sequential arm; **L8 (CO-PIN)**
  `mark.color = Absent` area output unchanged.
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ChartLowerTest'`.
- **Estimate:** ~130 LOC, ~90 min.

## Phase P6 — text + errorBar colorScale resolution (`spec` param thread-through)

- **Gaps closed:** GAP-COLOR-TEXT, GAP-COLOR-ERRORBAR. **severity_attr:** major.
- **Dependency:** depends only on the §0.1 pattern. Adds the private `spec: Maybe[ChartSpec[A]] =
  Absent` param to `lowerText` (2277-2283) and `lowerErrorBar` (2351-2357), updating call sites
  marksRegion 1709/1710 and marksRegionWithTransitions 3536/3538. Must precede P9 (reuses this `spec`
  param) and P10 (the legend margin is reserved precisely BECAUSE text/errorBar now render a
  meaningful legend — one shared keep-legend decision, R-6). Runs after P3 so the two `lowerErrorBar`
  edits stay sequential.
- **Surface-touching:** no (private signatures only).
- **Change:** per DESIGN §GAP-COLOR-TEXT / §GAP-COLOR-ERRORBAR. A row's whole error bar takes ONE
  colorScale color (all three sub-shapes share the resolved stroke).
- **Files:** `ChartLower.scala`.
- **Reproduce-before-fix:** **L2 (NEW)** text glyph fill == colorScale(region) + legend agreement;
  **L3 (NEW)** errorBar all 3 sub-shapes share ONE stroke == colorScale(region); **L10 (NEW)**
  Sequential arm both marks; **L8 (CO-PIN)** Leaf 17/18 (1692/1728) Absent-path byte-identical.
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ChartLowerTest'`.
- **Estimate:** ~90 LOC, ~70 min.

## Phase P7 — Animated simple-bar opacity/label/tooltip channels

- **Gaps closed:** GAP-TRANS-BAR-CHANNELS. **severity_attr:** minor.
- **Dependency:** must follow P1 — it calls the `applyBarChannels` helper P1 extracted, applying it to
  the `lowerBarSimpleWithTransitions` rect (3272-3284) before the SMIL animates attach, so the static
  and transition paths cannot drift. Depends on P1 only.
- **Surface-touching:** no (channels `opacity`/`label`/`tooltip` already exist on bar).
- **Change:** per DESIGN §GAP-TRANS-BAR-CHANNELS + §0.2.
- **Files:** `ChartLower.scala`.
- **Reproduce-before-fix:** **L18 (NEW)** animated simple bar with `opacity=_=>0.5` + `label=_.name` +
  `tooltip=_.name` emits `fill-opacity="0.5"`, a `<title>` child, and a label `<text>`, matching the
  static path (parity cross-check); no-channel animated bar byte-identical. Fails today (all three
  dropped).
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ChartTransitionTest'`.
- **Estimate:** ~60 LOC, ~50 min.

## Phase P8 — y-axis tick rotation + anchor + theme font (ticks/titles/legend)

- **Gaps closed:** GAP-YAXIS-ROTATION, GAP-THEME-FONT. **severity_attr:** major (both).
- **Dependency:** must follow P2 — routes `buildYAxis` tick labels (909-916, both axes) through the
  shared `tickLabel` helper P2 extracted. GAP-THEME-FONT lands here too because font rides the same
  `tickLabel` path; `withFont` is additionally applied to axis TITLES (buildXAxis 1024-1031,
  buildYAxis 927-952) and legend text (buildLegend). The two gaps share the single `tickLabel`/
  `withFont` path and cannot be split without re-deriving the helper twice.
- **Surface-touching:** no (`.yAxis(_.rotateTicks/anchor)`, `.theme(_.font/.fontSize)` already exist).
- **Change:** per DESIGN §GAP-YAXIS-ROTATION + §GAP-THEME-FONT. y-axis passes the side-default anchor
  unless the user explicitly set `.anchor`/`.rotateTicks` (R-9), preserving no-config byte-identity.
- **Files:** `ChartLower.scala`.
- **Reproduce-before-fix:** **L14 (NEW)** y tick `Rotate(-45.0,...)` transform (both axes); **L15
  (NEW)** `.anchor(Start)` sets y tick `text-anchor=Start`, default keeps side-default End (R-9
  co-pin); **L16 (NEW)** `.theme(_.font("monospace").fontSize(14))` puts `font-family="monospace"` +
  `font-size="14px"` on a y tick, an axis title, and a legend label, default theme adds none; **L17
  (CO-PIN)** x chrome byte-identical.
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ChartAxisTest'`.
- **Estimate:** ~110 LOC, ~80 min.

## Phase P9 — Highlight coverage on line/area (series-level) + text/errorBar (per-row)

- **Gaps closed:** GAP-HIGHLIGHT-COVERAGE. **severity_attr:** minor.
- **Dependency:** must follow P6 (reuses the `spec` param P6 adds to `lowerText`/`lowerErrorBar`; adds
  a parallel `highlight: Maybe[Highlight[A]] = Absent` param to `lowerLine`/`lowerArea`/`lowerText`/
  `lowerErrorBar`) and P5 (tags the per-series area paths P5 produces). Granularity per R-4:
  series-level for line/area, per-row for text/errorBar. `withHighlight(_, Absent)` is a no-op so
  non-interactive charts are byte-identical.
- **Surface-touching:** no (`.onSelect`/`.onHover` + `.interaction(_.highlightSelect)` already exist).
- **Change:** per DESIGN §GAP-HIGHLIGHT-COVERAGE + §0.4.
- **Files:** `ChartLower.scala`.
- **Reproduce-before-fix:** **L20 (NEW)** drive the ref to a known datum; the active line/area
  (series-level) and text/errorBar (per-row) element gets `stroke="#000000"` + `stroke-width="2px"`
  and exactly the active element does (count == expected); a non-active element does not; bar/point
  highlight unchanged (co-pin). Fails today (no highlight on those four marks).
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ChartInteractionTest'`.
- **Estimate:** ~110 LOC, ~80 min.

## Phase P10 — Reserve legend margin for color-bearing text/errorBar marks

- **Gaps closed:** GAP-LEGEND-MARGIN-TEXT-ERRORBAR. **severity_attr:** minor.
- **Dependency:** must follow P6 — the `hasLegend` predicate (buildLayout 278-285) returns
  `m.color.isDefined` for `Mark.Text`/`Mark.ErrorBar` precisely BECAUSE P6 made those marks honor
  colorScale and render a meaningful legend (R-6, one shared keep-legend decision). Without P6,
  reserving the strip would reserve space for a meaningless legend.
- **Surface-touching:** no (layout reservation only).
- **Change:** per DESIGN §GAP-LEGEND-MARGIN-TEXT-ERRORBAR. A no-color text/errorBar keeps
  `hasLegend == false` (byte-identical).
- **Files:** `ChartLower.scala`.
- **Reproduce-before-fix:** **L22 (NEW)** a chart whose ONLY color-bearing mark is `text(color =
  _.region)` reserves the top strip; assert a concrete top reference (top tick / baseline / first-glyph
  y) == reserved-strip y. The plan PINS the exact readback element and the `LegendReservedH`/`topPad`
  pixel for an `==` assertion (per 04-invariants concrete-assertability flag for L22); no-color text
  keeps the un-shifted y (co-pin). Fails today (overlap, topPad 0).
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ChartLowerTest'`.
- **Estimate:** ~40 LOC, ~45 min.

## Phase P11 — Right y-axis scale overrides + right-axis grid `[PUBLIC: Q-1]`

- **Gaps closed:** GAP-RIGHTY-SCALE, GAP-RIGHTY-GRID. **severity_attr:** GAP-RIGHTY-SCALE major;
  GAP-RIGHTY-GRID minor.
- **Dependency:** GAP-RIGHTY-GRID runs AFTER the scale fix WITHIN this phase because the right grid
  needs the resolved right scale's ticks to exist (the gridline y-positions ARE the right scale tick
  pixels); both also touch the same right-axis / `isRight` branch in `buildYAxis`. The phase itself
  depends on no earlier color/chrome phase (the right-scale path is disjoint); it is isolated here so
  the one public-surface phase is self-contained for the flow-api-design T2 audit.
- **Surface-touching:** YES.
- **Change (default Q-1 = candidate B):**
  - Add `ChartSpec.yScaleRightOverride: Maybe[ScaleOverride]` (UI.scala, after `yScaleOverride`) and
    the `yScaleRight(f)` extension method (next to `yScale`), per 02-public-api §2.1 / DESIGN
    §GAP-RIGHTY-SCALE. Update the single `UI.chart` factory to pass `Absent`.
  - Factor the left Y resolution (708-741) into a private
    `resolveYScale(ext, extNoZero, override, axisCfg, rangeLo, rangeHi): Scale` used for BOTH axes;
    add the new `yRightExtentNoZero` helper for the log-right arm (R-3). The 4 `resolveAllScales`
    call sites (3759/3805/3875/3957) pass the new override + right axis cfg.
  - Right grid: `buildYAxis` gains a `drawGrid: Boolean` param; left-wins double-draw tie-break
    (R-10): `rightDrawGrid = spec.yAxisRightCfg.exists(_.showGrid) && !leftDrawGrid`.
- **Files:** `UI.scala`, `ChartLower.scala`.
- **Reproduce-before-fix:**
  - ScaleTest: **L11a (NEW)** `resolveYScale` readback kind==Log under `.yScaleRight(_.log)`,
    clamped/padded domain, reversed range; **L12b (CO-PIN)** default args == old
    `Scale.fit(Linear, rExt, baseline, plotY, nice=true)`; **L19a (NEW)** `.yScale(_.log)
    .yScaleRight(_.linear(0,1))` -> left Log AND right Linear simultaneously.
  - ChartAxisTest: **L11b (NEW)** right-bound datum's rendered y-pixel matches the log pixel, not
    linear; **L13 (NEW)** right gridlines emitted with left grid OFF (count==rightTickCount at right
    tick y), and with BOTH set count==leftTickCount only (left-wins); **L12a (CO-PIN)** existing
    dual-axis test (value 10 -> ~230) stays green; **L19b (NEW)** per-axis pixel independence.
- **Verification:** targeted — `sbt 'kyo-ui/testOnly kyo.ScaleTest kyo.ChartAxisTest'`.
- **Estimate:** ~200 LOC, ~150 min.
- **If the user picks the alternative in 03b (Q-1 = candidate A, reuse `yScaleOverride` for both
  axes):** this phase REMOVES the `yScaleRight` builder + `yScaleRightOverride` field; `resolveYScale`
  reads `spec.yScaleOverride` for BOTH axes; the `yScale` scaladoc is amended to "both axes"; **L19
  (independence) is DROPPED** and **L11** is re-expressed against the shared override; a 02-public-api
  §6 re-audit is required (per 03b end note).

## Phase P12 — Remove dead AxisConfig knobs (`side` + setters + `Side` enum + `labelAllBands`) `[PUBLIC: Q-2, Q-3]`

- **Gaps closed:** GAP-AXISCONFIG-SIDE, GAP-LABELALLBANDS. **severity_attr:** both minor.
- **Dependency:** both are dead-knob REMOVALs on the SAME `AxisConfig` case class, so they land as one
  AxisConfig-cleanup unit (the side removal changes `AxisConfig.default` arity; the labelAllBands
  removal edits the same declaration). Independent of every rendering phase (nothing reads either
  knob). Must precede P13 because P13's cross-platform clean build + the README doctest gate must run
  AFTER the call-site rewrite this phase performs, so the green is real.
- **Surface-touching:** YES.
- **Change (default Q-2 = REMOVE, Q-3 = REMOVE):** per 02-public-api §2.2/§2.3 + DESIGN
  §GAP-LABELALLBANDS / §GAP-AXISCONFIG-SIDE.
  - Delete `AxisConfig.labelAllBands` (2504, `// D18`); no setter, no read, no scaladoc names it.
  - Delete `AxisConfig.side` (2495) + the four setters `.left/.right/.bottom/.top` (2506-2509) +
    the `Side` enum (469-471); fix the FALSE scaladoc sentence (2489) and the type-doc example (2487);
    adjust `AxisConfig.default` arity (drop the leading `Absent`).
  - **Full call-site rewrite** carried HERE: ~42 demos + ~36 chart tests rewrite the setter
    chain-starts (`.yAxis(_.left.grid)` -> `.yAxis(_.grid)`; `.xAxis(_.bottom)` dropped;
    `.yAxisRight(_.right...)` -> `.yAxisRight(...)`), plus the two named test-site edits
    `ChartSpecTest.scala:90` and `ChartAxisTest.scala:1068`. Do NOT touch `LegendConfig.legend(_.top)`
    or `Margins.margins(_.left(...))` (distinct setters, R-2 disambiguation).
- **Files:** `UI.scala` (+ the test/demo rewrites listed in the tests block).
- **Reproduce-before-fix (compile/API-shape guards):**
  - **L24 (NEW)** rewrite `ChartSpecTest:90-97` to assert the surviving real behavior
    `.yAxis(_.grid.ticks(5))` => `showGrid==true`, `tickCount==5`, dropping the `.side` assertion (it
    verified a knob that did nothing — a legitimate change, not a weakening). Module-wide grep guard:
    no source/test references `.side`, `Side.`, or the four removed setters; `.xAxis(_.top)` no longer
    compiles (silent no-op -> compile error).
  - **L23 (NEW)** `labelAllBands` field removed; `ChartAxisTest:1068` NAME corrected to drop the
    "(labelAllBands default)" parenthetical; the 7-label render assertion is UNCHANGED and stays green.
- **Verification:** targeted — `sbt 'kyo-ui/test'` (the whole JVM aggregate, since the rewrite touches
  many test files).
- **Estimate:** ~180 LOC, ~140 min.
- **If the user picks the alternatives in 03b:**
  - **Q-2 = WIRE:** instead of removal, ADD a band-label thinning policy + setter + an `N` hint field
    and wire `buildXAxis`/`buildYAxis` tick loops (a new feature); **L23** changes from a removal guard
    to a thinning-behavior assertion; a 02-public-api §6 re-audit is required.
  - **Q-3 = KEEP-AS-MARKER:** keep the four setters + `Side` enum, edit ONLY the false scaladoc
    sentence (2489); the ~84-site rewrite is DROPPED; `ChartSpecTest:90` keeps the `.left` call but the
    `.side` assertion still drops; **L24** becomes a scaladoc-correctness guard, not a removal guard.
  - **Q-3 = WIRE:** build positional placement (a new feature); a §6 re-audit + large DESIGN revision.

## Phase P13 — README Charts-section + doctest, then cross-platform-full clean-build gate

- **Gaps closed:** none directly (final integration gate; closes the README/doctest + cross-platform
  update obligation). **severity_attr:** n/a.
- **Dependency:** FINAL phase. Must follow EVERY prior phase because (a) it updates the README Charts
  examples for the public deltas from P11 (`yScaleRight`) and P12 (removed setters at README lines
  1088/1168/1169/1186/1187/1291, R-2) and re-runs `sbt kyo-ui/doctest`; and (b) it runs the
  cross-platform-full gate, which can only certify the whole feature once all source changes are in.
- **Surface-touching:** yes (README documents the surface; no new code surface).
- **Change:** rewrite the removed-setter doctest blocks to chains without the removed setter (e.g.
  `.yAxis(_.grid.ticks(4))`, `.xAxis(_.grid.ticks(5))`); add/adjust a `yScaleRight` example if the
  Charts section demonstrates dual-axis scales. Do NOT touch `.legend(_.top)` (1089/1170) or
  `.margins(_.left(...))` (R-2). Flag the `kyo-ui/CONTRIBUTING.md` GAP to the user (do NOT create it
  mid-campaign; 00-guides Update Obligation 1).
- **Files:** `README.md`.
- **Reproduce-before-fix:** the doctest gate is the guard — every fenced ` ```scala ` block compiles
  via `sbt kyo-ui/doctest`.
- **Verification:** **cross-platform-full** —
  `sbt 'clean' 'kyo-ui/doctest' 'kyo-ui/test' 'kyo-uiJS/test' 'kyo-uiNative/test'`. Force a clean
  recompile per platform; a no-"Compiling" log is RED (00-guides Trap 6, the stale `.sjsir`/`.tasty`
  twin). Project ids are `kyo-ui` / `kyo-uiJS` / `kyo-uiNative` (NOT `kyo-uiJVM`).
- **Estimate:** ~60 LOC, ~120 min.
- **If the user picks alternatives in 03b:** if Q-1 = candidate A, drop any `yScaleRight` README
  example. If Q-3 = KEEP-AS-MARKER, the removed-setter README rewrites are NOT needed (setters
  retained); only verify doctest stays green.

---

## Coverage map (all 14 gaps -> phase; NO scope cuts)

| GAP | Severity (neutral attr) | Phase | Reproduce leaf(s) |
|-----|-------------------------|-------|-------------------|
| GAP-COLOR-GROUPEDBAR | major | P4 | L1, L10 (+ L8 co-pin) |
| GAP-COLOR-AREA-SIMPLE | major | P5 | L4, L9, L10 (+ L8) |
| GAP-COLOR-TEXT | major | P6 | L2, L10 (+ L8) |
| GAP-COLOR-ERRORBAR | major | P6 | L3, L10 (+ L8) |
| GAP-RIGHTY-SCALE | major | P11 | L11, L19 (+ L12 co-pin) |
| GAP-YAXIS-ROTATION | major | P8 | L14, L15 (+ L17 co-pin) |
| GAP-THEME-FONT | major | P8 | L16 (+ L17 co-pin) |
| GAP-RIGHTY-GRID | minor | P11 | L13 |
| GAP-LABELALLBANDS | minor | P12 | L23 |
| GAP-AXISCONFIG-SIDE | minor | P12 | L24 |
| GAP-LEGEND-MARGIN-TEXT-ERRORBAR | minor | P10 | L22 |
| GAP-TRANS-BAR-CHANNELS | minor | P7 | L18 |
| GAP-HIGHLIGHT-COVERAGE | minor | P9 | L20 |
| GAP-ERRORBAR-BANDCENTER | minor | P3 | L21 |

14/14 gaps mapped to a concrete phase deliverable. P1, P2 (enabling shared-helper refactors) and P13
(final gate) close no gap directly but every gap is covered above. Severity is shown ONLY as a neutral
reviewer attribute; it played no role in phase ordering (which is technical-dependency only).

## Estimate roll-up

- **Total estimated LOC:** ~1180 (P1 40, P2 60, P3 30, P4 70, P5 130, P6 90, P7 60, P8 110, P9 110,
  P10 40, P11 200, P12 180, P13 60).
- **Total estimated wall-clock:** ~995 min (~16.6 h).
- **Longest dependency chain:** 3 phases / 3 links, e.g. **P5 -> P9 -> P13** (area split feeds
  highlight feeds the final cross-platform gate); equivalently **P6 -> P9 -> P13**, **P6 -> P10 ->
  P13**, **P2 -> P8 -> P13**, **P1 -> P7 -> P13**, **P11 -> P13**, **P12 -> P13**. No phase sits more
  than 3 links deep.

## Unplaceable gaps (priority-inference flag)

None. All 14 gaps were placed using technical-dependency ordering alone; no gap required inferring a
priority to position it. Where two gaps were dependency-equal (e.g. P3 errorBar-band-center vs P4
grouped-bar-color, or the two dead-knob removals in P12), they were grouped by SHARED CODE LOCUS (same
`lowerErrorBar` signature edit sequence; same `AxisConfig` case class), not by importance.
