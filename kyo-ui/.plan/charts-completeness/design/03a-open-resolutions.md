# 03a — Open resolutions (already-decided candidates)

PASS 2 (apply). One defensible answer per `already-decided` candidate in
`design/03-candidates.json`. Each cites the binding source in `02-design.md` / `02-public-api.md`.
These are NOT user escalations: they are internal decisions already locked by the design and
public-API specs. No open item is left dangling here; the only items routed to the user are the 3
`value-underdetermined` PUBLIC forks in `03b-user-escalations.md`.

Source of truth for every citation below:
- DESIGN = `kyo-ui/.plan/charts-completeness/design/02-design.md`
- API    = `kyo-ui/.plan/charts-completeness/design/02-public-api.md`
- LOWER  = `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala`
- UI     = `kyo-ui/shared/src/main/scala/kyo/UI.scala`

---

## R-1 (Q-SIDE-REMOVAL-OTHER-MODULES) — Side-removal blast radius

**Question.** Does removing the `Side` enum / `AxisConfig.side` touch any module or call site
beyond `UI.scala` + the two named tests?

**Resolution.** NO. The removal blast radius is exactly the sites the design already enumerates;
no other `kyo-ui` file and no other module is touched. A repo-wide grep over
`kyo-ui/shared/src/{main,test}` finds `Side` / `.side` references ONLY at:
- `UI.scala:469-471` (the `Side` enum declaration),
- `UI.scala:2495` (the `AxisConfig.side` field),
- `UI.scala:2506-2509` (the four setters `left`/`right`/`bottom`/`top`),
- `ChartSpecTest.scala:92-94` (the one test that reads `spec.yAxisCfg.side` / uses `.left`).

There is ZERO `.side` read and ZERO `Side.` reference anywhere in LOWER or SCALE: placement is
fully positional + `yAxisRightCfg`-driven today. So removal touches only the case class, the four
setters, the enum, and the one test, which is precisely the site list in
DESIGN §GAP-AXISCONFIG-SIDE.

**Binding source.** DESIGN §GAP-AXISCONFIG-SIDE (Removals, lines 603-631, which already lists
exactly these sites); API §2.3 (lines 261-314, "no `.side` read and no `Side.` reference anywhere
in LOWER or SCALE"). Conditioned on the §2.3 escalation resolving to REMOVE (see
`03b-user-escalations.md` Q-2).

---

## R-2 (Q-README-DOCTEST-REMOVED-SETTERS) — README/doctest rewrite obligation

**Question.** Which README/doctest examples use the to-be-removed `.left/.right/.top/.bottom`
`AxisConfig` setters and must be rewritten (and re-run through `sbt kyo-ui/doctest`)?

**Resolution.** The campaign OWNS a README/doctest sweep for the removed setters. The fenced,
doctest-validated Scala blocks in `kyo-ui/README.md` that use the removed `AxisConfig` setters and
will stop compiling after REMOVE are at lines:
- `1088` (`.yAxis(_.left...)`),
- `1168` (`.xAxis(_.bottom...)`),
- `1169` (`.yAxis(_.left...)`),
- `1186` (`.yAxis(_.left...)`),
- `1187` (`.yAxisRight(_.right...)`),
- `1291` (`.xAxis(_.bottom...)`).

Each must be rewritten to a chain without the removed setter (e.g. `.yAxis(_.grid.ticks(4))`,
`.xAxis(_.grid.ticks(5))`), and `sbt kyo-ui/doctest` re-run as the regression gate.

**Important disambiguation (do NOT touch these):** `.legend(_.top)` at README `1089`/`1170` is the
`LegendConfig` setter (`UI.scala:2547`), NOT the `AxisConfig` setter, and `.margins(_.left(80))`
is the `Margins` setter (`UI.scala:2319`); both are UNAFFECTED by the `Side` removal.

**Binding source.** DESIGN §GAP-AXISCONFIG-SIDE "README/doctest obligation" (lines 629-631);
steering folded-guides "End-of-campaign update obligation" (steering line 54). Conditioned on
Q-AXISCONFIG-SIDE resolving to REMOVE (see `03b` Q-2).

---

## R-3 (Q-RIGHT-EXTENT-NOZERO-HELPER) — missing `yRightExtentNoZero` for log-right

**Question.** Does honoring `.yScaleRight(_.log)` require a right-axis no-zero extent path (a
`yRightExtentNoZero` helper) analogous to the left `yLeftExtentNoZero`, and does it exist today?

**Resolution.** YES, it is required, and NO, it does not exist today: it must be added as an
INTERNAL helper mirroring the existing left one. Verified: `yLeftExtentNoZero` exists
(`ChartLower.scala:465-522`) and feeds the LEFT Log arm; only `yRightExtent` (`525-555`) exists for
the right axis, with NO no-zero variant. Because a `.yScaleRight(_.log)` over data that crosses or
touches zero needs the same zero-exclusion the left log path uses, the Log-right arm of the
factored `resolveYScale` must consume a right no-zero extent. Add `yRightExtentNoZero` mirroring
`yLeftExtentNoZero` (or compute it inline) so the log-right path behaves exactly like the log-left
path. This is a design-bound private helper addition, not a user escalation.

**Binding source.** DESIGN §GAP-RIGHTY-SCALE (lines 354-357: "use `yRightExtent` / a right
`yRightExtentNoZero` for the Log arm (add the no-zero right extent helper mirroring
`yLeftExtentNoZero`, or compute it inline)"). Verified anchors: `yLeftExtentNoZero` at
`ChartLower.scala:465-522`, `yRightExtent` at `525-555`, no right no-zero variant present.

---

## R-4 (Q-HIGHLIGHT-LINE-AREA-GRANULARITY) — highlight granularity

**Question.** On a multi-segment line/area, does highlight apply to the whole series path or
per-segment/per-vertex/per-row?

**Resolution.** SERIES-LEVEL for line and area; PER-ROW for text and errorBar.
- **line, area:** the single per-series path is tagged with the series-representative row (the
  first row of the series chunk, the SAME row `lowerLineSeries`/`lowerArea` already use for
  interaction attrs at LOWER:2262-2265 / 2495-2497), then wrapped via `withHighlight`. This matches
  the documented series-granularity interaction model (LOWER:2128-2131) and avoids inventing
  per-vertex highlighting.
- **text, errorBar:** per-row. Each glyph / each error-bar group is tagged with its own row,
  mirroring `lowerBarSimple` (every rect tagged with `row`).

Per-vertex / per-segment highlighting is explicitly REJECTED as a new feature (steering §3,
out of scope) and as inconsistent with the documented series-granularity model. Justified by
consistency: highlight then matches the existing interaction-attr granularity on the same shapes,
so the highlighted element is exactly the element that fires `onHover`/`onSelect`.

**Binding source.** DESIGN §0.4 Highlight granularity decision (lines 150-167) + §GAP-HIGHLIGHT-COVERAGE
(521-564) + §4 Rejected alternatives "Per-vertex / per-row highlight for line/area" (687-689);
API §6 internal binds (lines 571-572).

---

## R-5 (Q-AREA-OVERLAP-OPACITY) — area overlap policy

**Question.** When non-stacked area splits into N per-series paths, what overlap policy applies
(opaque z-order vs layered fill-opacity, and what value)?

**Resolution.** Emit ONE closed path per category in data-collection (category-collection) order,
each at the EXISTING non-stacked `fillOpacity = 0.7` (`ChartLower.scala:2478`), layered in that
order (later categories paint over earlier). Rationale:
- It reuses the single-path opacity already in code (2478), so a one-series chart is
  byte-identical (one path, 0.7 opacity, `defaultColor` when no colorScale).
- 0.7 fill-opacity keeps overlaps visible (lower layers show through), the correct semantics for
  unstacked overlapping areas; opaque z-order would hide lower series and silently lose data, which
  is worse for a completeness/consistency fix and is REJECTED.
- It mirrors `lowerLine`'s per-series emission order (LOWER:2157-2161): categories collected via
  `collectColorCategoriesWithRaw`, iterated by `zipWithIndex`, so area and line agree when both are
  colored by the same channel.

Internal lowering decision, no public knob.

**Binding source.** DESIGN §0.5 Area overlap policy (lines 169-185) + §GAP-COLOR-AREA-SIMPLE
(275-322) + §4 Rejected alternatives "Opaque z-order" (690-692); API §2.4 (lines 318-338).

---

## R-6 (Q-LEGEND-MARGIN-COUPLING) — legend-margin coupling

**Question.** Should the legend margin (`hasLegend`/topPad reservation) be reserved for
color-bearing text/errorBar marks, coupled to the keep-vs-drop colorScale-legend decision?

**Resolution.** YES. Include the Text/ErrorBar color channel in `hasLegend` (`buildLayout`
278-285) so the top strip is reserved, as ONE decision shared with GAP-COLOR-TEXT/ERRORBAR:

```scala
case m: Mark.Text[?, ?, ?]     => m.color.isDefined
case m: Mark.ErrorBar[?, ?, ?] => m.color.isDefined
```

Because those marks now honor `colorScale` and render a meaningful legend, the margin MUST be
reserved (otherwise the top legend strip overlaps the plot, since no `topPad` is reserved today). A
text/errorBar mark with NO color channel keeps `m.color.isDefined == false`, so `hasLegend` is
unchanged and the no-color case stays byte-identical (topPad still 0). This lands together with
GAP-COLOR-TEXT/ERRORBAR (one shared keep-legend decision). Rule stays `false` (it has no color
channel). Internal decision, no escalation.

**Binding source.** DESIGN §GAP-LEGEND-MARGIN-TEXT-ERRORBAR (lines 468-494) + §2 dependency 5
(648-649); API §1.1 table row GAP-LEGEND-MARGIN + §6 internal binds (lines 573-574).

---

## R-7 (Q-SHARED-HELPER-NAMES) — shared-helper names

**Question.** What are the shared-helper names/shapes (`applyBarChannels`, `tickLabel` + `withFont`
+ `toSvgAnchor`, `buildSimpleAreaPath`, `resolveYScale`) and their signatures?

**Resolution.** The design fixes all helper names, signatures, bodies, and placement; all are
private/internal (no user-facing surface):
- `applyBarChannels(rect, mark, row, barX, barW, barY, fill): (Svg.SvgElement, Chunk[Svg.SvgElement])`
  — shared by static `lowerBarSimple` and `lowerBarSimpleWithTransitions` so the two cannot drift
  (DESIGN §0.2, lines 48-88).
- `tickLabel(...)` + hoisted `withFont(theme, t)` + `toSvgAnchor(a: TextAnchor): Svg.TextAnchor`
  — the single tick-label chrome path shared by `buildXAxis` and `buildYAxis`; `tickLabel` takes a
  resolved `anchor` param rather than reading `cfg.tickAnchor` itself (see R-9) (DESIGN §0.3,
  lines 90-148).
- `buildSimpleAreaPath(seriesRows, mark, layout, xs, ys, fill, spec, internalHoverRef):
  Maybe[Svg.SvgElement]` — factors the single-series area-path construction so the Absent-color arm
  calls it once and the Present-color arm calls it per series, guaranteeing single-series
  byte-identity (DESIGN §GAP-COLOR-AREA-SIMPLE, lines 305-309).
- `resolveYScale(ext, extNoZero, override, axisCfg, rangeLo, rangeHi): Scale` — factors the left Y
  resolution (LOWER:708-741) so the right path literally becomes the left path, used for BOTH axes
  (DESIGN §GAP-RIGHTY-SCALE, lines 357-362).

A single cross-mark `applyMarkChannels` for ALL marks is explicitly REJECTED as a premature
abstraction (text/line/area/point anchor channels differently); the bar static/transition pair is
the one place two paths render the SAME rect, so the shared helper is scoped exactly there.

**Binding source.** DESIGN §0.2 (48-88), §0.3 (90-148), §GAP-COLOR-AREA-SIMPLE `buildSimpleAreaPath`
(305-309), §GAP-RIGHTY-SCALE `resolveYScale` (357-362), §4 Rejected "single cross-mark
applyMarkChannels" (696-699).

---

## R-8 (Q-ABSENT-FALLBACK-BYTE-IDENTITY) — Absent-fallback byte identity (INV-004)

**Question.** What is the exact byte-identity contract for the no-colorScale / Absent path, and how
is INV-004 preserved?

**Resolution.** The no-colorScale / Absent path stays BYTE-IDENTICAL by construction on every fix:
- **Color gaps:** the Absent-colorScale arm uses the existing by-index `themePalette` /
  `DefaultPalette` mapping verbatim, which is provably identical to `resolvePalette`'s Absent branch
  (`themePalette(theme) = theme.palette.getOrElse(DefaultPalette)`, consumed `palette(i % size)`;
  `resolvePalette` Absent branch LOWER:1309-1314 is the SAME mapping).
- **Highlight:** `withHighlight(tagged, Absent)` returns `tagged.map(_._2)` unchanged (LOWER:1635).
- **Font:** `withFont` with the default theme (both `fontFamily` and `fontSize` Absent) is a no-op.
- **Right scale:** the factored `resolveYScale` reproduces the old hardcoded right
  `Scale.fit(Linear, rExt, baseline, plotY, nice = true)` for the default override (default override
  => kind Linear via getOrElse, nice true via getOrElse, no reverse, no clamp, pad 0).
- **Bar channels:** `applyBarChannels` with no channels returns the rect unchanged + empty label chunk.
- **Band-center:** `xs.apply(x) + xs.bandwidth/2.0` is a no-op on continuous x (`bandwidth == 0`).

Leaf 15/16/17/18 (`ChartLowerTest.scala:1634/1670/1692/1728`, all no-colorScale + custom
`theme.palette`) plus the golden tests guard the unchanged Absent path; each gap's reproduce test
asserts only the NEW Present-path behavior. This is the INV-004 contract. Internal correctness
contract, no escalation.

**Binding source.** DESIGN §0.1 byte-identity proof (lines 27-46) + §3 byte-identity ledger
(667-679); steering folded-guides (line 50, "INV-004 golden unchanged").

---

## R-9 (Q-YAXIS-DEFAULT-ANCHOR-PRESERVATION) — y-axis default anchor preservation

**Question.** When routing y-axis tick labels through the shared `tickLabel` helper, how is the
existing side-default anchor (End for left, Start for right) preserved versus the `AxisConfig`
default `tickAnchor = Middle`?

**Resolution.** The §0.3 `tickLabel` helper takes a RESOLVED `anchor: Svg.TextAnchor` param rather
than reading `cfg.tickAnchor` itself. `buildYAxis` passes the side-derived default anchor (End for
left, Start for right; the existing hardcoded 874 value) UNLESS the user explicitly set
`.anchor(...)` / `.rotateTicks(...)`, in which case it passes `toSvgAnchor(cfg.tickAnchor)`;
`buildXAxis` always passes `toSvgAnchor(cfg.tickAnchor)`. This keeps no-config y-axis output
byte-identical (anchor still End/Start) while honoring an explicit `.anchor(...)`. This is the one
deliberate deviation from a pure `buildXAxis` mirror, because `buildXAxis` has no side-default
anchor to preserve and `buildYAxis` does (874). Internal, no escalation.

**Binding source.** DESIGN §GAP-YAXIS-ROTATION "Note on the hardcoded side anchor" (lines 422-441)
+ §0.3 (90-148).

---

## R-10 (Q-RIGHTY-GRID-DOUBLE-DRAW-TIEBREAK) — right-grid double-draw tie-break

**Question.** When both left and right axes request `.grid`, which axis's horizontal gridlines are
drawn (double-draw guard direction)?

**Resolution.** LEFT-WINS. The right axis draws grid only when its own cfg requests it AND the left
did not:

```scala
val leftDrawGrid  = spec.yAxisCfg.showGrid
val rightDrawGrid = spec.yAxisRightCfg.exists(_.showGrid) && !leftDrawGrid
```

`buildYAxis` gains a `drawGrid: Boolean` param and its gate (LOWER:884) becomes `if drawGrid`. The
left axis is always present and its ticks own the horizontal references when both request grid; the
right is optional. When both set `.grid`, only the left set is emitted (no overlapping duplicate
lines at potentially different y positions). This is a deterministic, documented tie-break, not a
silent drop. Internal lowering decision, no escalation.

**Binding source.** DESIGN §GAP-RIGHTY-GRID (lines 378-403; guard direction + rationale 396-398).

---

## Coverage check

All `already-decided` candidates in `03-candidates.json` are resolved above:

| # | Candidate id | Resolution |
|---|--------------|------------|
| R-1 | Q-SIDE-REMOVAL-OTHER-MODULES | UI.scala + 2 tests only; no other module |
| R-2 | Q-README-DOCTEST-REMOVED-SETTERS | rewrite README lines 1088/1168/1169/1186/1187/1291 + re-doctest |
| R-3 | Q-RIGHT-EXTENT-NOZERO-HELPER | add `yRightExtentNoZero` mirroring `yLeftExtentNoZero` |
| R-4 | Q-HIGHLIGHT-LINE-AREA-GRANULARITY | series-level line/area, per-row text/errorBar |
| R-5 | Q-AREA-OVERLAP-OPACITY | per-series paths, data order, 0.7 fillOpacity |
| R-6 | Q-LEGEND-MARGIN-COUPLING | reserve margin iff text/errorBar carry color |
| R-7 | Q-SHARED-HELPER-NAMES | applyBarChannels / tickLabel+withFont+toSvgAnchor / buildSimpleAreaPath / resolveYScale |
| R-8 | Q-ABSENT-FALLBACK-BYTE-IDENTITY | Absent arm byte-identical; INV-004 by construction |
| R-9 | Q-YAXIS-DEFAULT-ANCHOR-PRESERVATION | tickLabel takes resolved anchor; side-default preserved |
| R-10 | Q-RIGHTY-GRID-DOUBLE-DRAW-TIEBREAK | left-wins guard |

No open item remains among the already-decided set. The only items routed to the user are the 3
PUBLIC forks in `03b-user-escalations.md`.
