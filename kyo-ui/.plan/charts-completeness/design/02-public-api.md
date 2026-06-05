# Public API specification ; kyo-ui chart completeness (14 gaps)

Feature dir: kyo-ui/.plan/charts-completeness
Reference module: this same module's EXISTING correct chart surface (`UI.scala` builders,
`ChartLower.scala` left-scale path) is the structural precedent ; this is a consistency
campaign, so the "reference" is the codebase's own canonical patterns, not a sibling module.
Hardline principles: complete, correct, clean, safe, FP-typed.

Source roots (fresh reads, current worktree):
- API   = `kyo-ui/shared/src/main/scala/kyo/UI.scala` (2879 lines)
- LOWER  = `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala` (3990 lines)
- SCALE  = `kyo-ui/shared/src/main/scala/kyo/internal/Scale.scala` (544 lines)

---

## §0 Scope of this document (why it is small)

This campaign closes 14 feature-completeness gaps in an EXISTING chart layer. It adds NO new
mark types, scale kinds, or channels (steering §3). Most of the 14 fixes change INTERNAL
lowering only: they make a private `lowerXxx` read configuration that the public surface
already exposes. Those produce **no public delta** and are listed in §1.1 so the design step
does not accidentally widen the surface.

Only a small set of fixes genuinely touch the user-facing surface. This document locks exactly
those. Three of them are **value-underdetermined PUBLIC forks** that this skill does NOT decide
unilaterally; each is specified with both candidate surfaces, a recommendation, and a
RESOLVE-OPEN escalation marker (`>>> ESCALATE`). flow-resolve-open consumes these.

The §6 self-audit runs the five hardline principles against the resulting locked surface.

---

## §1 Top-level package surface

Budget: this campaign may add **0** new top-level types in package `kyo`. Actual: **0**.

The campaign touches only members nested under the already-exported `UI` object
(`UI.mark.*` factories, `UI.Ast.*` config types, the `ChartSpec` extension methods). No new
package-root type is introduced by any of the 14 fixes. Every candidate addition below nests
under an existing companion:

| Candidate addition | Kind | Nests under | Introduced by | Status |
|--------------------|------|-------------|---------------|--------|
| `ChartSpec.yScaleRightOverride` field | case-class field | `UI.Ast.ChartSpec` | GAP-RIGHTY-SCALE (candidate B only) | fork ; see §2.1 |
| `yScaleRight(f)` extension method | extension method | `extension [A](spec: ChartSpec[A])` | GAP-RIGHTY-SCALE (candidate B only) | fork ; see §2.1 |

No other public addition. GAP-LABELALLBANDS and GAP-AXISCONFIG-SIDE only **remove or wire
existing** members, never add. The colorScale / highlight / band-center / font / rotation /
grid fixes touch zero public declarations (§1.1).

### §1.1 Internal-only fixes (NO public delta) ; explicit list

These 11 of the 14 gaps are closed entirely below the public surface. The public knobs they
honor (`.legend(_.colorScale(...))`, the `color = ...` mark channel, `.theme(_.font(...))`,
`.yAxis(_.rotateTicks(...))`, `.interaction(_.highlightSelect)`, `.yAxisRight(_.grid)`) ALL
ALREADY EXIST. The fix is to make the private lowering read them. The design step must not add
any public member for these.

| Gap | Public knob already exists | Internal change (private signature / body) | Public delta |
|-----|----------------------------|---------------------------------------------|--------------|
| GAP-COLOR-GROUPEDBAR | `.legend(_.colorScale[K](...))`, `color =` | `lowerBarGrouped` `case _` arm (LOWER:1873-1878) routes Categorical through `resolvePalette(spec, cats)` (mirror Sequential arm 1872 / `lowerBarStacked` 1971). `spec` already threaded (sig 1851). | none |
| GAP-COLOR-TEXT | same | `lowerText` gains a `spec` / `colorScale` source param (sig 2277-2283); resolves via `resolvePalette` when Present. Call sites `marksRegion` 1709, `marksRegionWithTransitions` 3536. | none (private sig) |
| GAP-COLOR-ERRORBAR | same | `lowerErrorBar` gains the same param (sig 2351-2357); same resolution. Call sites 1710, 3538. | none (private sig) |
| GAP-COLOR-AREA-SIMPLE | `area(color = ...)` already exists | `lowerArea` non-stacked branch (2449-2499) + animated twin (3397-3446) split rows by `mark.color` and emit one path per series via `resolvePalette`. | none to the SURFACE; see §2.4 (output-semantics change handed to flow-design) |
| GAP-RIGHTY-GRID | `.yAxisRight(_.grid)` already exists | `buildYAxis` grid gate (LOWER:884) `showGrid && !isRight` allows the right axis to draw horizontal gridlines, double-draw-guarded. | none |
| GAP-YAXIS-ROTATION | `.yAxis(_.rotateTicks(d))`, `.yAxis(_.anchor(a))` already exist | `buildYAxis` tick-label block (909-916) applies `cfg.tickRotation` / `cfg.tickAnchor` (mirror `buildXAxis` 969-972, 1014-1018). | none |
| GAP-THEME-FONT | `.theme(_.font(f).fontSize(px))` already exists | apply `withFont` (buildXAxis 975-977) to `buildYAxis` tick labels, axis titles in both builders, legend text. | none |
| GAP-LEGEND-MARGIN-TEXT-ERRORBAR | (no knob; layout reservation) | `buildLayout.hasLegend` (278-285) includes Text/ErrorBar color channels so the legend strip is reserved. Couples to GAP-COLOR-TEXT/ERRORBAR keep-legend decision. | none |
| GAP-TRANS-BAR-CHANNELS | `opacity` / `label` / `tooltip` channels already exist on bar | `lowerBarSimpleWithTransitions` rect (3272-3284) applies opacity/tooltip/label (mirror `lowerBarSimple` 1815/1819/1823). | none |
| GAP-HIGHLIGHT-COVERAGE | `.onSelect`/`.onHover` + `.interaction(_.highlightSelect)` already exist | thread `highlight` (from `resolveHighlight`) into `lowerLine`/`lowerArea`/`lowerText`/`lowerErrorBar` + `withHighlight` (mirror `lowerPoint` sig 2757). | none (private sig) |
| GAP-ERRORBAR-BANDCENTER | (no knob; geometry correctness) | `lowerErrorBar` px (LOWER:2384) `xs.apply(x)` -> `xs.apply(x) + xs.bandwidth / 2.0` (mirror line 2198 / text 2320). | none |

That is 11 internal-only. The remaining **3 are public**: GAP-RIGHTY-SCALE (§2.1),
GAP-LABELALLBANDS (§2.2), GAP-AXISCONFIG-SIDE (§2.3). All three are value-underdetermined forks
flagged `>>> ESCALATE`.

---

## §2 Per-entry-point specification (the public deltas)

### §2.1 GAP-RIGHTY-SCALE ; right y-axis honors scale overrides `>>> ESCALATE (value-underdetermined PUBLIC fork)`

**Defect.** The right y-axis scale is hardcoded `Scale.fit(Scale.Kind.Linear, rExt,
plotBaseline, plotY, nice = true)` (LOWER:744-748). It reads NONE of the override knobs the
left axis honors. The left scale (LOWER:708-741) is the reference: it reads `yOverride`
(= `spec.yScaleOverride`) for `kind`/`nice`/`clamp`/`pad` and `yAxisCfg.reversed`/padding. So
today `.yScale(_.log)`, `.yScale(_.withClamp(true))`, `.yScale(_.withPad(0.1))`, and
`.yAxisRight(_.reverse)` are silently dropped for right-bound marks (`axis = Axis.Right`).

A chart can already bind a mark to the right axis (`Axis` enum, API:441-442; mark `axis`
channel, read at `marksRegion` 1671-1677) and already configure the right axis chrome
(`.yAxisRight(f)`, API:2374-2376). What is missing is a SCALE override source that the right
resolution reads. There are two candidate public surfaces; this is the fork.

The question: **does a right-bound mark's scale read the EXISTING `yScaleOverride` (so
`.yScale(...)` applies to whichever axis the chart's marks use), or does the right axis get its
OWN override source?**

---

#### Candidate A ; reuse `yScaleOverride` for both axes (NO new public member)

- **Public delta:** none. No new field, no new method.
- **Behavior:** the right-scale block (LOWER:744-748) reads the SAME `spec.yScaleOverride` and
  `spec.yAxisRightCfg` (reversed/padding) that the left block reads, applying `kind`/`nice`/
  `clamp`/`pad` identically. `.yScale(_.log)` then makes BOTH left and right y-axes log.
- **Where it is read:** `resolveAllScales` right block, mirroring the left arm at 708-741
  against `rExt` (the right extent `yRightExtent`) and the right range
  (`plotBaseline`..`plotY`).
- **Scaladoc:** the existing `yScale` doc (API:2394-2395, "Overrides the y-axis scale") would be
  amended to: "Overrides BOTH y-axis scales (left and right) using a builder lambda. A chart
  with right-bound marks shares this override across both axes."
- **Cost:** couples left and right scale kinds. A dual-axis chart cannot have a linear left and
  a log right axis ; the classic dual-axis use case (two unrelated domains, e.g. revenue vs.
  conversion-rate) cannot give them independent kinds. This contradicts the reason a right axis
  exists.

#### Candidate B ; dedicated `yScaleRight` override source (RECOMMENDED) `>>> ESCALATE`

- **Public delta (two additions, both nested under existing owners):**

  1. A new field on `ChartSpec` (API:2258-2279), placed immediately after `yScaleOverride`
     (2268) to mirror the `yScaleOverride` / `yAxisRightCfg` pairing already in the case class:

     ```scala
     final case class ChartSpec[A](
         // ... existing fields ...
         yScaleOverride: Maybe[ScaleOverride],
         yScaleRightOverride: Maybe[ScaleOverride], // NEW ; right-axis scale override; Absent => inferred
         // ... existing fields ...
     )
     ```

     Default value: there is no `ChartSpec.default`; the spec is built by `UI.chart(...)`
     factories that supply every field positionally, so the factory call sites (the
     `UI.chart` constructors in `UI.scala`) must pass `Absent` for the new field. This is an
     internal constructor-site update, not a public default.

  2. A new extension method next to `yScale` (API:2395), with an explicit return type and
     8-35-line scaladoc per guide constraint §3/§4:

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

- **Where it is read:** `resolveAllScales` right block (LOWER:744-748) reads
  `spec.yScaleRightOverride` (`kind`/`nice`/`clamp`/`pad`) and `spec.yAxisRightCfg`
  (reversed/padding), mirroring the LEFT resolution arm (708-741) against the right extent and
  range. Internal threading reuses the existing `Maybe[ScaleOverride]` plumbing; no `Side`
  enum involvement.
- **Effect row / error row:** none. `ChartSpec` builders are pure total functions
  (`ChartSpec[A] => ChartSpec[A]`), no `< Async`, no `Abort`. Mirrors every existing builder
  (`xScale`/`yScale`, API:2391-2396).
- **Example:**

  ```scala
  import kyo.*
  // revenue on the left (linear), conversion ratio on the right (log), independent kinds:
  UI.chart(rows)(
    UI.mark.bar(_.month, _.revenue),
    UI.mark.line(_.month, _.ratio).axis(Axis.Right)
  ).yScale(_.linear(0, 1_000_000))
   .yScaleRight(_.log)
  ```

**RECOMMENDATION: Candidate B (dedicated `yScaleRight` + `yScaleRightOverride`).**

Rationale, grounded in existing precedent and convention:

1. **Existing precedent already separates the right axis at every other layer.** The right axis
   has its own chrome config (`yAxisRightCfg: Maybe[AxisConfig]`, API:2264; `.yAxisRight(f)`,
   2374-2376) and its own extent computation (`yRightExtent`, LOWER:746). A right-bound mark
   declares `axis = Axis.Right` explicitly (API:441-442). The ONLY thing that is shared today
   is the scale override, and that is precisely the unfinished part (the right block is
   hardcoded, not deliberately reusing the left override). The honest completion mirrors the
   `yScaleOverride`/`yAxisRightCfg` pairing: a right scale override to match the right axis
   config that already exists.

2. **d3 / grammar-of-graphics convention treats dual axes as independent scales.** A dual-axis
   chart exists specifically to plot two domains with DIFFERENT units/ranges/kinds on a shared
   x. In d3 each `yScale` is a separate scale object; in grammar-of-graphics a secondary axis is
   a distinct positional scale. Candidate A (shared kind) cannot express log-right + linear-left,
   which is the most common real dual-axis pattern, so it would ship a right axis that still
   cannot do the thing right axes are for.

3. **Surface cost is one field + one method**, both nesting under existing owners (no new
   top-level type, §1 budget 0 preserved), both pure, both mirroring `yScale` exactly. The
   ergonomic delta is `.yScaleRight(...)` reading symmetrically with `.yScale(...)` and
   `.yAxisRight(...)` reading symmetrically with `.yAxis(...)`.

This is a value-underdetermined PUBLIC fork (it adds user-facing surface vs. not). Per steering
§6 this skill does NOT pick unilaterally: the recommendation is presented as the DEFAULT for
flow-resolve-open to confirm or override. `>>> ESCALATE: yes/no ; add dedicated yScaleRight
(recommended), or reuse yScaleOverride for both axes?`

---

### §2.2 GAP-LABELALLBANDS ; dead `AxisConfig.labelAllBands` knob `>>> ESCALATE (value-underdetermined PUBLIC fork)`

**Defect.** `AxisConfig.labelAllBands: Boolean = true` (API:2504, tagged `// D18`) has **zero
reads** in the entire module (verified: `grep labelAllBands` returns only the field declaration
at UI.scala:2504; no read in LOWER or SCALE). It has **no setter** on `AxisConfig` (the setters
list 2506-2517 has none for it). The field is constructed only via the `AxisConfig` case-class
default and `AxisConfig.default` (2522). It is a dead public field: a user can `.copy(...)` it
but nothing observes it. A `true` default that is never read is a latent correctness lie ; the
name promises "label all bands (vs. thinning)" and delivers nothing.

Two clean+correct options:

#### Option WIRE
- **What it means:** make `buildXAxis` (957-1034) and `buildYAxis` (862-954) tick-label loops
  read `cfg.labelAllBands`: when `false` on a Band/Ordinal x-scale, render a tick label on
  every Nth band (thinning) instead of every band. Add a setter so the knob is reachable:
  `def labelEveryBand: AxisConfig = copy(labelAllBands = true)` and
  `def labelThinned: AxisConfig = copy(labelAllBands = false)` (or a single
  `def labelAllBands(on: Boolean)`).
- **Cost:** introduces a thinning policy that does not exist anywhere today (what is "N"? a new
  hint field). That edges toward a NEW feature (steering §3), not completing an existing knob,
  because there is no documented thinning behavior to mirror. WIRE here means designing a
  thinning algorithm, not finishing one.

#### Option REMOVE (RECOMMENDED) `>>> ESCALATE`
- **What it means:** delete the field from `AxisConfig` (API:2504), adjust `AxisConfig.default`
  (2522) arity (it currently passes 5 positional args and relies on the trailing defaults; the
  field has a default so removal does not break the `default` val literal, but any positional
  constructor that names it must drop it). Remove the `// D18` reference. No scaladoc line
  mentions `labelAllBands` by name (the type scaladoc 2483-2493 does not name it), so no
  scaladoc edit beyond not introducing one. No setter to delete (none exists). No source
  constructs it explicitly (only the case-class default).
- **Cost:** the smallest honest surface. Removes a field nothing reads. No render behavior
  changes (it was never read).

**RECOMMENDATION: REMOVE.** Rationale: a dead public knob that lies about its effect is a
correctness defect (guide constraint §4: a removed/false knob's surface must be corrected, not
left dangling). There is no setter, no read, and no documented thinning behavior to "finish";
WIRE would invent a NEW feature (thinning + an N hint), which steering §3 places out of scope.
REMOVE is the smaller, honest surface and changes zero rendered output. This is value-
underdetermined (someone might genuinely want band-label thinning), so per steering §6 it
escalates rather than being picked here.
`>>> ESCALATE: yes/no ; REMOVE labelAllBands (recommended), or WIRE band-label thinning (new behavior)?`

---

### §2.3 GAP-AXISCONFIG-SIDE ; dead `AxisConfig.side` + FALSE scaladoc `>>> ESCALATE (value-underdetermined PUBLIC fork)`

**Defect.** `AxisConfig.side: Maybe[Side]` (API:2495) and its four setters `left`/`right`/
`bottom`/`top` (API:2506-2509) have **zero reads** in the module (verified: no `.side` read and
no `Side.` reference anywhere in LOWER or SCALE; only the field, the setters, and the `Side`
enum declaration at API:469-471 exist). Axis placement is fully POSITIONAL today: the x-axis is
drawn at the bottom by `buildXAxis`, the left/right y-axes are dispatched by `buildFrame`
(766-798) from the `isRight` flag derived from `yAxisRightCfg` presence, never from `cfg.side`.

Worse, the `AxisConfig` type scaladoc at **API:2489 is FALSE**: it states `side` "selects where
the axis line and labels are drawn." Nothing reads `side`, so `.xAxis(_.top)` does not move the
x-axis; the documented behavior does not happen. This is the most acute case: a knob that
LIES in its own scaladoc.

Two clean+correct options:

#### Option WIRE
- **What it means:** make `buildFrame` axis dispatch (766-798) honor `cfg.side`: `.xAxis(_.top)`
  draws the x-axis at the top of the plot, `.yAxis(_.right)` flips the left y to the right, etc.
  This requires positional layout logic keyed on `Side` for all four placements, including
  margin re-reservation (a top x-axis needs top margin, not bottom). Substantial new placement
  machinery, because placement is otherwise entirely positional + `yAxisRightCfg`-driven.
- **Cost:** large; introduces a placement model the module does not have. Edges into a NEW
  feature (free axis placement), steering §3.

#### Option REMOVE (RECOMMENDED) `>>> ESCALATE`
- **What it means, exactly:**
  - Delete the `side: Maybe[Side]` field from `AxisConfig` (API:2495).
  - Delete the four setters `left` (2506), `right` (2507), `bottom` (2508), `top` (2509).
  - **Fix the FALSE scaladoc at API:2489**: remove the sentence "`side` selects where the axis
    line and labels are drawn." from the `AxisConfig` type doc (constraint §4: false scaladoc
    of a removed knob must be removed).
  - Remove the `Side` enum (API:469-471) IF it becomes dead after this (verify no other reader;
    current grep shows `Side` is referenced only by these now-removed setters and the field, so
    it becomes dead and should be removed with them).
  - Adjust `AxisConfig.default` (2522): it passes `side = Absent` as the first positional arg
    (`AxisConfig(Absent, Absent, false, 5, Absent)`); removing the field drops that leading
    `Absent`, becoming `AxisConfig(Absent, false, 5, Absent)` (axisLabel/showGrid/tickCount/
    tickFormat). Every positional `AxisConfig(...)` constructor in source updates accordingly
    (the design step must grep for them; `AxisConfig.default` and the case-class defaults are
    the known sites).
  - Update the type scaladoc 2483-2493 example `_.left.grid.ticks(5)` (2487) which uses the
    `.left` setter ; rewrite to a chain that does not use a removed setter (e.g.
    `_.grid.ticks(5).format(...)`).
- **Cost:** removes 1 field + 4 setters + 1 enum + corrects 1 false scaladoc line + 1 example.
  Zero rendered output change (nothing read `side`).

**RECOMMENDATION: REMOVE.** Rationale: this is the clearest correctness defect of the three ;
the scaladoc actively lies (API:2489), and the knob has zero reads. Placement is determined
positionally and by `yAxisRightCfg` everywhere, so `side` has no role in the actual model. WIRE
would build a free-placement feature that does not exist (steering §3 out of scope). REMOVE
yields a smaller honest surface and deletes the lie. Value-underdetermined (free axis placement
is a plausible future want), so per steering §6 it escalates.
`>>> ESCALATE: yes/no ; REMOVE side + left/right/top/bottom + Side enum + fix false scaladoc (recommended), or WIRE positional placement (new feature)?`

---

### §2.4 GAP-COLOR-AREA-SIMPLE ; output-semantics note (NO public-surface change)

This is listed in §1.1 as internal-only, but it carries a RENDERED-OUTPUT change that the
design step must own, so it is recorded here without being a public-surface delta.

- **Public surface:** unchanged. `area(color = _.series)` already exists; `mark.color` is
  already a field on `Mark.Area`. No new method, field, or scaladoc.
- **Output-semantics change to hand to flow-design:** today a non-stacked multi-series area is
  ONE merged path filled with `defaultColor` (LOWER:2483); the fix splits it into N closed area
  paths, one per `color` category, each filled via `resolvePalette` (mirror `lowerLine`
  split 2157-2161 + `lowerAreaStacked` resolve 2613). The same chart that rendered one blob will
  render N overlapping translucent areas. This is a visible behavioral change for existing
  charts, even though the API call is identical.
- **Overlap policy (design concern, not a public-surface change):** N overlapping non-stacked
  areas need an overlap rule. The natural bind (exploration §5.4) is per-series paths in data
  order at the EXISTING non-stacked fillOpacity (0.7, LOWER:2478), matching the single-path
  opacity. This is an internal lowering decision for flow-design, NOT a public knob; it is
  recorded here so the design step resolves it deliberately rather than incidentally.

No `>>> ESCALATE`: there is no public fork. The escalation-worthy item (overlap policy) is an
internal pattern-bind, handed to flow-design per exploration §5.4.

---

## §3 Type vocabulary (touched public types)

This campaign references existing public ADTs; the only candidate NEW declaration is the
`yScaleRightOverride` field (fork §2.1 candidate B). No new sealed union, enum, or opaque type.

### Existing types the public deltas reference

#### ScaleOverride
- **Nested location:** `kyo.UI.Ast.ScaleOverride` (API:2711-2732).
- **Definition:** `final case class ScaleOverride(kind: Maybe[ScaleKind], nice: Boolean = true,
  clamp: Boolean = false, pad: Double = 0.0)` with builders `band`/`log`/`linear`/`time`/
  `ordinal`/`point`/`symlog`/`withNice`/`noNice`/`withClamp`/`withPad`.
- **Derivations:** none (plain case class).
- **Used by:** existing `xScale`/`yScale` (API:2391-2396); the proposed `yScaleRight` (§2.1
  candidate B) reuses it unchanged ; same builder vocabulary, no new method on it. `kind` is
  `Maybe[ScaleKind]` (Absent = inferred), the correct total encoding of "optional override".

#### ScaleKind
- **Nested location:** `kyo.UI.ScaleKind` (enum, derives `CanEqual`, API:477+).
- **Used by:** `ScaleOverride.kind`. No change ; the right axis honoring `.log`/`.linear`/etc.
  is a lowering change, not a type change.

#### AxisConfig
- **Nested location:** `kyo.UI.Ast.AxisConfig` (API:2494-2519).
- **Definition today:** `side: Maybe[Side]`, `axisLabel: Maybe[String]`, `showGrid: Boolean`,
  `tickCount: Int`, `tickFormat: Maybe[Double => String]`, `tickRotation`, `tickAnchor`,
  `reversed`, `padding`, `labelAllBands`.
- **Change under recommended forks:** REMOVE `side` (§2.3) and `labelAllBands` (§2.2),
  shrinking the case class. No field is added. `reversed`/`padding`/`tickRotation`/`tickAnchor`
  stay (they ARE read after the GAP-YAXIS-ROTATION / right-scale fixes).
- **Used by:** `.xAxis`/`.yAxis`/`.yAxisRight` builders (API:2366-2376).

#### Side
- **Nested location:** `kyo.UI.Side` (enum, derives `CanEqual`, API:469-471).
- **Status under recommended fork:** REMOVED with `AxisConfig.side` (§2.3) since it becomes
  dead. (If GAP-AXISCONFIG-SIDE resolves to WIRE, it is RETAINED and read by `buildFrame`.)

#### Axis
- **Nested location:** `kyo.UI.Axis` (enum `Left, Right`, derives `CanEqual`, API:441-442).
- **Used by:** the mark `axis` channel (read at `marksRegion` 1671-1677). Unchanged; it already
  binds marks to the right axis. GAP-RIGHTY-SCALE makes that binding's SCALE honor overrides;
  the `Axis` type itself does not change.

#### LegendConfig.ColorScale
- **Nested location:** `kyo.UI.Ast.LegendConfig.ColorScale` (sealed enum:
  `Categorical(fn: Any => Style.Color)` and
  `Sequential(low, high, domain: Maybe[(Double, Double)])`, API:2627-2630).
- **Used by:** the colorScale gaps (GROUPEDBAR/TEXT/ERRORBAR/AREA). The fix makes more marks
  READ this existing sealed union; the type is unchanged. This is the FP-typed polymorphism the
  campaign leans on ; categorical-vs-sequential is already a sealed union, and the gap marks
  fail only by not pattern-matching it.

#### Theme
- **Nested location:** `kyo.UI.Ast.Theme` (API:2639-2672), `fontFamily: Maybe[String]`,
  `fontSize: Maybe[Double]`, builders `font`/`fontSize`.
- **Used by:** GAP-THEME-FONT makes `buildYAxis` / titles / legend read `fontFamily`/`fontSize`.
  Type unchanged.

No protocol polymorphism in this campaign lacks a sealed union: `ColorScale` (Categorical |
Sequential) and `Maybe[ScaleOverride]` / `Maybe[Side]` (the Absent = inferred/positional
encoding) already model every optionality and variance at the type level. No `null`, no
`Either`, no untyped AST.

---

## §4 Worked examples (the public deltas in use)

### Example 1: Minimal ; right axis honors `.yScaleRight` (recommended fork B)

```scala
import kyo.*
UI.chart(rows)(
  UI.mark.bar(_.month, _.count),
  UI.mark.line(_.month, _.rate).axis(Axis.Right)
).yScaleRight(_.log) // right series projected on a log scale; left stays linear
```

### Example 2: Common case ; independent left/right kinds and ranges

```scala
import kyo.*
UI.chart(rows)(
  UI.mark.bar(_.month, _.revenue),
  UI.mark.line(_.month, _.conversion).axis(Axis.Right)
).yScale(_.linear(0, 1_000_000))      // left: absolute revenue
 .yScaleRight(_.linear(0, 1).withClamp(true)) // right: clamped ratio in [0,1]
 .yAxisRight(_.grid)                   // GAP-RIGHTY-GRID: right gridlines now drawn (existing knob)
```

### Example 3: colorScale honored on a grouped bar (internal fix, existing public knob)

```scala
import kyo.*
enum Region derives CanEqual: case NA, EU, APAC
UI.chart(rows)(
  UI.mark.bar(_.quarter, _.sales, color = _.region) // grouped/dodged by region
).legend(_.colorScale[Region](
  Region.NA  -> Style.Color.blue,
  Region.EU  -> Style.Color.green,
  Region.APAC-> Style.Color.red
)) // each dodged rect now fills with its region's colorScale color (was themePalette-by-index)
```

### Example 4: dead-knob removal ; the chains that no longer compile (recommended forks)

```scala
import kyo.*
// AFTER GAP-AXISCONFIG-SIDE = REMOVE, this no longer compiles (the setter is gone):
//   UI.chart(rows)(UI.mark.bar(_.x, _.y)).xAxis(_.top)   // .top removed
// The honest single-axis-bottom placement is positional and needs no knob:
UI.chart(rows)(UI.mark.bar(_.x, _.y)).xAxis(_.grid.ticks(5))
```

### Example 5: y-tick rotation + theme font (existing knobs, now read)

```scala
import kyo.*
UI.chart(rows)(UI.mark.line(_.t, _.v))
  .yAxis(_.rotateTicks(-45).anchor(TextAnchor.End)) // GAP-YAXIS-ROTATION: y ticks now rotate
  .theme(_.font("monospace").fontSize(14))          // GAP-THEME-FONT: y ticks/titles/legend now fonted
```

### Example 6: highlight coverage on line/text (existing knobs, now read)

```scala
import kyo.*
val sel: Signal.SignalRef[Maybe[Row]] = ???
UI.chart(rows)(
  UI.mark.line(_.t, _.v, color = _.series),
  UI.mark.text(_.t, _.v, _.label)
).onSelect(sel)
 .interaction(_.highlightSelect) // line + text now honor the highlight (was point/bar only)
```

(All six call only the EXISTING public surface plus the single proposed `yScaleRight`; no
example needs a new top-level type.)

---

## §5 Anti-cases (what the surface REJECTS / makes impossible)

### Anti-case 1: scale override is total, never null

Bad:
```scala
.yScaleRight(_ => null) // not expressible
```
What happens: `yScaleRight` takes `ScaleOverride => ScaleOverride`; the builder receives
`ScaleOverride.default` and every builder method returns a `ScaleOverride`. There is no path to
`null`; `kind` is `Maybe[ScaleKind]` (Absent = inferred). A user cannot produce a half-built or
null override.
Why this matters: the right-scale resolution (LOWER:744-748) reads a total `Maybe[ScaleOverride]`,
matching the left arm; no nullable scale state, no `NullPointerException` at projection time
(Principle 4 ; safe, Principle 5 ; `Maybe` over null).

### Anti-case 2: colorScale polymorphism is closed (sealed)

Bad:
```scala
// attempt to attach a colorScale that is neither categorical nor sequential
.legend(_ => LegendConfig(...).copy(colorScale = Present(/* third kind */)))
```
What happens: compile error. `ColorScale` is a sealed enum with exactly `Categorical` and
`Sequential` (API:2627-2630); there is no third variant to construct. The lowering's match on it
(after the GAP-1/2/3/4 fixes) is exhaustive.
Why this matters: making grouped-bar/text/errorBar read the colorScale cannot introduce an
unhandled case ; the sealed union guarantees the match is total (Principle 2 ; correct,
Principle 5 ; sealed union over open polymorphism).

### Anti-case 3: dead knob cannot silently lie (post-REMOVE)

Bad (today, before fix):
```scala
.xAxis(_.top) // compiles, does NOTHING; scaladoc claims it moves the axis (API:2489 FALSE)
```
What happens after recommended REMOVE: it no longer compiles (`top` setter deleted). The user
gets a compile error instead of a silent no-op.
Why this matters: a no-op knob with a true-sounding scaladoc is a correctness lie (constraint
§4). Removing it converts a silent runtime no-op into a compile-time rejection ; the type system
now tells the truth about what the surface can do (Principle 2 ; correct, Principle 4 ; no
silent no-op).

### Anti-case 4: builders stay pure / total (no effect row, no Abort)

Bad:
```scala
val s: ChartSpec[Row] < Abort[Throwable] = chart.yScaleRight(_.log) // wrong type
```
What happens: compile error. `yScaleRight: ChartSpec[A] => ChartSpec[A]` is a pure total
function with no effect row, like every existing builder (`xScale`/`yScale`, API:2391-2396).
Why this matters: chart construction is value-composition, not effectful action (root CLAUDE.md
"Think in values, not actions"). No builder can throw or suspend; errors do not hide in a
builder (Principle 4 ; safe, Principle 5 ; FP value composition).

### Anti-case 5: right override does not corrupt left scale (independence)

Bad assumption:
```scala
.yScale(_.log).yScaleRight(_.linear(0, 1)) // does the right override change the left?
```
What happens: no. Under recommended fork B the left scale reads `yScaleOverride` and the right
reads `yScaleRightOverride` from disjoint fields (§2.1). Setting one cannot mutate the other;
`copy` is structural. The left stays log, the right stays linear.
Why this matters: the dual-axis contract is that the two domains are independent (d3 / GoG
convention, §2.1 rationale 2). Candidate A would VIOLATE this (shared kind); the recommendation
is chosen specifically to preserve it at the type level via separate fields (Principle 2 ;
correct).

---

## §6 Principle self-audit

| Principle | Verdict | Evidence | Remediation |
|-----------|---------|----------|-------------|
| 1 Complete | PASS | All 14 gaps accounted for: 11 internal-only with the exact public knob they honor (§1.1 table), 3 public deltas fully specified (§2.1/2.2/2.3) + 1 output-semantics note (§2.4). No gap dropped, none deferred (steering §1). Every public knob the internal fixes read already exists and is cited (legend.colorScale, color channel, theme.font, yAxis.rotateTicks, yAxisRight.grid, interaction.highlightSelect). | n/a |
| 2 Correct | PASS | Optionality via `Maybe[ScaleOverride]`/`Maybe[Side]` (Absent = inferred/positional), not null (§3). colorScale polymorphism is the existing sealed `ColorScale` union; the fix makes marks match it exhaustively (Anti-case 2). Right/left scale independence encoded as disjoint fields under fork B (Anti-case 5). False scaladoc at API:2489 is explicitly corrected by REMOVE (§2.3). No session-level state threaded through signatures (builders are `ChartSpec=>ChartSpec`). | n/a |
| 3 Clean | PASS | §1 budget = 0 new top-level types; actual 0. The only candidate additions (`yScaleRightOverride` field, `yScaleRight` method) nest under existing owners (`ChartSpec` / the `extension`), §1 table. No alias overloads; `yScaleRight` mirrors `yScale` 1:1. Recommended dead-knob forks REMOVE surface (net shrink), never widen. | n/a |
| 4 Safe | PASS | No `null` in any §4 example or §2 signature (Anti-case 1). No `throw`, no effect row on builders ; pure total functions (Anti-case 4). Errors cannot hide in construction. Dead-knob removal converts silent runtime no-ops into compile errors (Anti-case 3). No resource lifecycle introduced (charts are pure values lowered to `Svg.Root`). | n/a |
| 5 FP-typed | PASS | Sealed-union reliance: `ColorScale` (Categorical \| Sequential) is the polymorphism the campaign reads (§3, Anti-case 2). `Maybe[T]` over nullable for every optional knob (`ScaleOverride.kind`, `yScaleRightOverride`, `Side`). Builders are pure value-composition `ChartSpec[A] => ChartSpec[A]` (Anti-case 4). Typed key on `colorScale[K](using CanEqual[K,K])` already present (API:2581). No new opaque type or `+E` row is INTRODUCED, but none is APPLICABLE: this campaign adds no effectful entry point and no new engine boundary; it completes pure builders + private lowering. Marking PASS (not WEAK) because the applicable FP idioms (sealed union, Maybe-over-null, pure composition, type-class-keyed colorScale) are all present; the inapplicable ones (`+E`, opaque) are correctly absent rather than missing. | n/a |

Verdict summary: 5 PASS, 0 WEAK, 0 FAIL.
Exit code: 0.

Open PUBLIC forks for flow-resolve-open (do NOT auto-resolve; recommendations are defaults):
1. `>>> ESCALATE` GAP-RIGHTY-SCALE override source ; **recommend dedicated `yScaleRight` (candidate B)**.
2. `>>> ESCALATE` GAP-LABELALLBANDS ; **recommend REMOVE**.
3. `>>> ESCALATE` GAP-AXISCONFIG-SIDE ; **recommend REMOVE** (+ delete `Side` enum + fix false scaladoc API:2489).

Internal pattern-binds handed to flow-design (no escalation): GAP-COLOR-AREA-SIMPLE overlap
policy (per-series paths at existing 0.7 fillOpacity, data order); GAP-HIGHLIGHT line/area
granularity (series-level for line/area, per-row for text/errorBar); GAP-LEGEND-MARGIN coupling
(reserve margin iff text/errorBar keep their colorScale legend, one decision shared with
GAP-COLOR-TEXT/ERRORBAR).
