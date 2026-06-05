# Consolidated post-commit audit — color + chrome cluster (P4/P5/P6/P8)

Audited the COMMITTED state of each phase via `git show <sha>` (not the dirty tree; ChartLower.scala
is being edited concurrently by P9). Scope: P4 `4076e3271`, P5 `435c08449`, P6 `b6c4c0c96`,
P8 `2fe7e6d3d`, all on `ChartLower.scala` + the matching test file.

## 5-line summary

- Counts: PASS 20, WARN 1, FAIL 2.
- Most important finding: **P5 left the animated/transitions non-stacked area color-blind.**
  `lowerAreaWithTransitions` (and the non-animated transitions-region area fallback) call `lowerArea`
  with `spec = Absent`, so a `area(color=_.cat)` + `.legend(_.colorScale(...))` under a transitioning
  chart renders `DefaultPalette` colors, NOT the colorScale — directly diverging from the already-fixed
  animated **line** twin (`lowerLineWithTransitions` "FIX B", which resolves per-series colors with the
  real `spec`). The static area path P5 added is correct; the animated twin silently drops the fix.
- Second FAIL: **L10 (Sequential-arm) reproduce-before-fix test is missing across the whole cluster.**
  The plan lists L10 as a NEW leaf for P4, P5, and P6 (grouped-bar / area / text / errorBar), and none
  of the three commits added it. The Sequential code path works, but the planned regression guard does
  not exist.
- Byte-identity (Absent / default-theme / default-anchor) and the INV-004 golden are clean across all
  four phases; no existing test was weakened or deleted; no AI tells / TODO / pending / ignore.

---

## P4 — GAP-COLOR-GROUPEDBAR (`4076e3271`)

**1. CORRECTNESS — PASS.** Source diff (ChartLower.scala:1938-1948 in the commit) replaces
`case Present(_: Sequential) => resolvePalette(s, colorCats)` / `case _ => <by-index>` with
`case Present(_) => resolvePalette(s, colorCats)` / `case Absent => <by-index>`. This routes BOTH
Categorical and Sequential Present colorScales through `resolvePalette`, exactly as DESIGN
§GAP-COLOR-GROUPEDBAR specifies, mirroring the `lowerBarStacked` 1971 arm. `colorCats` is the
already-collected category chunk (no recompute).

**2. BYTE-IDENTITY — PASS.** The `Absent` inner arm and the outer `case Absent` are the existing
by-index `basePalette` code verbatim (`colorKeys.zipWithIndex.map((_, i) => basePalette.toSeq.apply(i %
basePalette.size))`). `basePalette` is `themePalette(s.theme)` for Present spec, else `DefaultPalette`
— unchanged. INV-004 golden file (ChartInvariantsTest) is untouched by this commit.

**3. DRY — PASS.** Reuses `resolvePalette`; no palette logic duplicated.

**4. REWARD-HACKING — PASS.** Test (ChartLowerTest, +61 lines, pure addition) asserts concrete hex
fills (`#e63946`/`#2a9d8f`/`#e9c46a`) per sub-bar, an explicit anti-fallback guard
(`!= Style.Color.blue && != Style.Color.orange`), AND legend-swatch↔mark agreement. No `.nonEmpty`-only
assert, no deletions.

**5. KYO CONVENTIONS — PASS.** No signature change (no new public surface); Maybe/Chunk used; no
`protected`/`@uncheckedVariance`.

**WARN (cluster, surfaced here): L10 Sequential-arm test missing.** Plan 05-plan.md:126 + table:354
list L10 ("Sequential arm two distinct concrete colors") as a NEW reproduce-before-fix leaf for
GAP-COLOR-GROUPEDBAR. The commit adds only the Categorical test (L1). The `Present(_) => resolvePalette`
change is precisely what enables the Sequential arm, yet no test pins it. See cluster FAIL-2 below.

---

## P5 — GAP-COLOR-AREA-SIMPLE (`435c08449`)

**1. CORRECTNESS (static path) — PASS.** The non-stacked `case Absent =>` (no-stack) branch now
dispatches on `mark.color`: Absent-color calls `buildSimpleAreaPath(rows, ..., defaultColor, ...)` once;
Present-color collects categories via `collectColorCategoriesWithRaw`, resolves the palette
(`Present(s) => resolvePalette(s, cats)` / `Absent => DefaultPalette`), and emits one closed path per
series via `buildSimpleAreaPath`. The extracted `buildSimpleAreaPath` is the original single-path body
moved verbatim (band-center px, CurvePath top edge, baseline-return + close, 0.7 opacity, tooltip,
interaction). Matches DESIGN §0.5 + §GAP-COLOR-AREA-SIMPLE.

**1b. CORRECTNESS (animated twin) — FAIL.** This is the cluster's most important finding.
`lowerAreaWithTransitions` (ChartLower.scala:3560-3609 at HEAD) holds the real `spec: ChartSpec[A]`
(param, line 3567) but calls `lowerArea(rows, mark, layout, xs, ys, defaultColor)` at lines 3575 and
3580 WITHOUT forwarding it, so `spec` defaults to `Absent`. The P5 Present-color arm under `spec =
Absent` resolves the palette to `DefaultPalette` (the area branch's literal `case Absent => DefaultPalette`),
NOT `resolvePalette` with the user's colorScale. `lowerAreaWithTransitions` then re-emits those
`rawPath`s unchanged (only attaching SMIL animates), so an explicit categorical/sequential colorScale on
an animated non-stacked area is DROPPED — the paths render `DefaultPalette` blue/orange/... .
  - The non-animated transitions-region fallback has the same defect: ChartLower.scala:3677
    `lowerArea(rows, m, layout, xs, ys, markColor)` also passes no spec.
  - This DIRECTLY contradicts the sibling line fix: `lowerLineWithTransitions` (3473-3544) deliberately
    resolves per-series colors itself with the real `spec` ("FIX B (transitions path, mirrors lowerLine):
    resolve per-series colors via resolvePalette ... so an explicit categorical/sequential colorScale is
    honored and the animated line agrees with the legend"). The area twin was NOT given the equivalent
    treatment.
  - Root: DESIGN §GAP-COLOR-AREA-SIMPLE (02-design.md:310-315) reasoned only about geometry stability
    ("transparently animates each per-series path ... No further change to the transitions twin") and
    overlooked that the delegated `lowerArea` call has `spec = Absent`, so the colors silently fall back.
    Plan P5 (05-plan.md:141) inherited the same blind spot ("inherits the multi-path lowerArea").
  - Fix direction (one line each, but flagging, not fixing, per READ-ONLY): forward `Present(spec)` into
    the `lowerArea` calls at 3575/3580/3677 (the static stacked call at 3575 is harmless to thread too,
    or thread only the non-stacked emission path).

**2. BYTE-IDENTITY — PASS (for the bound boundary).** The byte-identity boundary is `mark.color =
Absent` → single `buildSimpleAreaPath(..., defaultColor, ...)` path, which is the original body verbatim
(same fn, same inputs). L8 co-pin test asserts exactly ONE closed path at fill-opacity 0.7 for the
no-color area. INV-004 golden untouched. (The animated-twin defect above is a missed FIX, not a
byte-identity regression of the Absent path — the Absent-color area still emits the single default path
on both static and animated routes.)

**3. DRY — PASS.** `buildSimpleAreaPath` is a clean extraction; the Present-color arm reuses it
per-series and reuses `collectColorCategoriesWithRaw` / `resolvePalette`. No copy-paste.

**4. REWARD-HACKING — PASS (within the static scope tested).** L4+L9 test asserts: exactly 2 paths,
each closed (last command == Close), each fill-opacity 0.7, concrete colorScale fills (red/purple),
legend line-swatch agreement; L8 co-pin asserts the single-path Absent case. No weakened/`.nonEmpty`-only
asserts, no deletions. The reward-hacking concern is the ABSENCE of an animated-area test (which would
have caught FAIL-1b) and the absence of the L10 Sequential test (FAIL-2) — coverage gaps, not weakened
assertions.

**5. KYO CONVENTIONS — PASS.** `buildSimpleAreaPath` is `private def`, returns `Maybe[Svg.SvgElement]`
(explicit), `(using Frame)` last; Maybe/Chunk used; the `.asInstanceOf[Encoding[A, Any]]` cast is the
pre-existing pattern used by every color lowerer. No new public surface.

---

## P6 — GAP-COLOR-TEXT + GAP-COLOR-ERRORBAR (`b6c4c0c96`)

**1. CORRECTNESS — PASS.** `lowerText` (sig +`spec: Maybe[ChartSpec[A]] = Absent`) and `lowerErrorBar`
(same) replace the by-index palette block with `Present(s) => resolvePalette(s, colorCatsWithRaw)` /
`Absent => <by-index>`. All four call sites threaded: `marksRegion` 1762/1763 pass `spec`;
`marksRegionWithTransitions` 3668/3670 pass `Present(spec)`. The errorBar's three sub-shapes already
share the one resolved `stroke`, so a row takes ONE colorScale color (verified in the test). Matches
DESIGN §GAP-COLOR-TEXT / §GAP-COLOR-ERRORBAR.

**2. BYTE-IDENTITY — PASS.** Both Absent arms are the existing
`colorCatsWithRaw.zipWithIndex.map((_, i) => basePaletteX.toSeq.apply(i % basePaletteX.size))` verbatim;
the `if colorCatsWithRaw.isEmpty then Chunk.empty` guard is preserved. Leaf 17/18 (ChartLowerTest
no-colorScale tests) untouched and pass; INV-004 golden untouched.

**3. DRY — PASS.** Reuses `resolvePalette` / `collectColorCategoriesWithRaw`; no duplicated palette
logic; the text and errorBar rewrites are the same shape.

**4. REWARD-HACKING — PASS.** Tests (+154 lines, pure additions): text glyph fills assert concrete
`#e63946`/`#2a9d8f`; errorBar groups all three sub-shapes per row share one concrete stroke, center
marker fill asserted, anti-fallback guards, legend-swatch agreement. No weakened/`.nonEmpty`-only
asserts, no deletions.

**5. KYO CONVENTIONS — PASS.** `spec` added as last value param before `(using Frame)` (correct
ordering); default `= Absent`; both remain `private def`; Maybe/Chunk used. No new public surface.

**WARN (cluster): L10 Sequential test missing for text + errorBar** (see cluster FAIL-2).

---

## P8 — GAP-YAXIS-ROTATION + GAP-THEME-FONT (`2fe7e6d3d`)

**1. CORRECTNESS — PASS.** `buildYAxis` tick labels now route through P2's `tickLabel` helper:
`tickLabel(labelX, py, labelStr, chrome, Svg.DominantBaseline.Middle, effAnchor, cfg, theme)`. The
helper applies `cfg.tickRotation` (Rotate about (x,y) when `!= 0.0`), the resolved anchor, and
`withFont`. `withFont(theme, ...)` is applied at the other five sites named in the design: both Y-axis
titles (931-958), the X-axis title (1080-1090), legend item labels (`buildLegendItems` gains `theme`,
1512/1561-1571), sequential-legend labels (1197-1208), size-legend labels (1221/1260-1284). Wired via
the P2 helpers (`tickLabel`/`withFont`/`toSvgAnchor`), as designed.

**2. BYTE-IDENTITY — PASS.** `effAnchor` (ChartLower.scala:914-916 at HEAD):
`if cfg.tickAnchor != TextAnchor.Middle then toSvgAnchor(cfg.tickAnchor) else anchor` — when the user
has NOT set an anchor (`tickAnchor == Middle` default), it falls back to the side-derived `anchor`
(`if isRight then Start else End`, line 874), NOT Middle. Rotation is gated on `cfg.tickRotation != 0.0`
inside `tickLabel`, so the default (0.0) adds no transform. `withFont` is a no-op when both
`theme.fontFamily` and `theme.fontSize` are Absent. The default Y-axis output is therefore byte-identical
(End/Start anchor, no transform, no font). L15/L16 co-pin tests assert exactly this; INV-004 golden
untouched; `buildXAxis` tick-label call is unchanged (only its title gained `withFont`).

**3. DRY — PASS.** No inline tick-label copy: `buildYAxis` deletes its inline `Svg.text` block and calls
the shared `tickLabel`. `toSvgAnchor` reused. `withFont` reused at every site. The old inline
constructions are replaced, not duplicated.

**4. REWARD-HACKING — PASS.** Tests (ChartAxisTest +182 lines, pure additions): L14 asserts concrete
Rotate degrees (-45.0 left, 30.0 right) per tick; L15 asserts text-anchor=Start when set AND End
co-pin when unset; L16 asserts font-family=monospace + font-size=14 on a Y tick, an axis title, and a
legend label, plus a default-theme no-font co-pin; L17 re-pins x rotation/anchor/font byte-identity.
The `assert(ticks.nonEmpty, ...)` lines are preconditions FOLLOWED by concrete per-element asserts
(acceptable). No weakened asserts, no deletions.

**5. KYO CONVENTIONS — PASS.** Added no public surface (confirmed: all touched methods —
`buildYAxis`/`buildXAxis`/`buildLegendItems`/`buildSizeLegend`/`buildSequentialLegend` — are
`private`/`private def`; `buildLegendItems`/`buildSizeLegend` gained a `theme: Theme` value param, not a
public surface). `tickLabel`/`withFont`/`toSvgAnchor` are `private def` with `(using Frame)` last where
applicable. No `protected`/`@uncheckedVariance`.

---

## Cluster-level cross-phase consistency verdict — FAIL (one real divergence)

The four phases make STATIC color resolution genuinely uniform: every color-split mark (grouped-bar P4,
non-stacked area P5, text + errorBar P6) now follows the SAME shape — Present colorScale →
`resolvePalette(spec, cats)`; Absent → by-index `themePalette`/`DefaultPalette`. `resolvePalette`
(verified at 1355-1368) is the single source of that fallback, so the Absent path is uniform and
byte-identical. P8 makes the theme font reach Y ticks, both axis titles, the X-axis title, and all
legend text uniformly via one `withFont`, and routes both axes through one `tickLabel` helper. INV-004
golden is untouched by all four commits; no existing test (Leaf 15/16/17/18, golden) was edited.

The consistency BREAK is the **animated/transitions non-stacked area** (P5 FAIL-1b): the area transitions
twin diverges from the line transitions twin. The line twin resolves per-series colorScale colors with
the real `spec` ("FIX B"); the area twin delegates to `lowerArea` with `spec = Absent` and so falls back
to `DefaultPalette`, dropping the colorScale for animated color-split areas. This is precisely the
divergent-variant the cross-phase check exists to catch: bar/text/errorBar/area agree on STATIC charts,
but area alone regresses to default colors the moment the chart animates, while line does not.

### Findings ledger

- **FAIL-1 (P5):** animated + non-animated transitions-region non-stacked area drops the colorScale
  (calls `lowerArea` with `spec = Absent` at ChartLower.scala:3575, 3580, 3677). Diverges from
  `lowerLineWithTransitions` FIX B. Fix: forward `Present(spec)` into those `lowerArea` calls and add a
  reproduce test (animated `area(color=_.cat)` + colorScale → per-series colorScale fills, mirroring the
  existing animated-line color test). Owner action required before "leave no issue behind" closes.
- **FAIL-2 (cluster):** L10 (Sequential colorScale arm) reproduce-before-fix test is MISSING for all
  four color marks. Plan 05-plan.md lists L10 as a NEW leaf for P4 (:126), P5 (:146), P6 (:165) and in
  the leaf table (:354-357); invariants L10 (04-invariants.md:151-165) requires "two DIFFERENT concrete
  hex colors, neither `url(#`, low-end vs high-end ordering" per mark. No such test exists in any of the
  three commits (grep: zero `Sequential`/`L10` test additions). The Sequential code path is exercised by
  `resolvePalette`'s Sequential branch but has no regression guard, violating the reproduce-before-fix
  contract for L10.
- **WARN-1 (P8):** none of the P8 font assertions check the X-axis title font (the design lists the
  X-axis title among the six `withFont` sites and the diff adds it at 1080-1090, but L16 only asserts a
  Y tick, a rotated Y-title, and a legend label). Minor: the X-title `withFont` is byte-covered by the
  default-theme co-pin and the shared `withFont` helper, so this is a coverage nicety, not a defect.

Everything else in the cluster is genuinely clean: static color uniformity holds, byte-identity holds on
every Absent/default path, the golden is intact, no test was weakened or deleted, and there are no AI
tells / TODO / pending / ignore on any added line.
