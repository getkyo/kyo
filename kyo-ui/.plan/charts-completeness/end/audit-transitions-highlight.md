# Audit — TRANSITION + HIGHLIGHT cluster (P7 GAP-TRANS-BAR-CHANNELS, P9 GAP-HIGHLIGHT-COVERAGE)

Audited at HEAD `2755d3abe` (P9). Source read via `git show HEAD:kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala`
(4280 lines) and the two committed test files. READ-ONLY; no edits to source/test.

## 5-line summary
- PASS: 6 / WARN: 2 / FAIL: 1
- Most important NEW finding (beyond task #244): `lowerAreaWithTransitions` drops `spec` at a
  **second, distinct** call site — the STACKED fallthrough at **ChartLower.scala:3659** (not just the
  non-stacked rawPaths call at :3664 that #244 already covers). The stacked drop is strictly worse: it
  routes through `lowerAreaStacked(spec = Absent)` → `resolvePaletteFromCfg` (line 2233-2235), which uses
  `DefaultPalette` ONLY and ignores BOTH `colorScale` AND a custom `theme.palette`. An animated stacked
  area silently renders DefaultPalette even when a categorical colorScale or custom theme palette is set,
  while the static `marksRegion` area path (`:1781`) honors both. Same FAIL-1 defect class as #244, separate
  locus; a fix that only patches :3664 leaves :3659 broken.
- Everything else in the cluster is clean: P7 reuse + byte-identity + cast soundness verified; P9 highlight
  coverage is genuinely complete and consistent for the in-scope marks; the §0.4 exclusions are real.
- Secondary hygiene FAIL: 4 em-dashes on committed `.scala` test-comment lines (P9).

---

## Param-forwarding table (the FAIL-1 class)

Baseline = static `marksRegion` (`:1731`–`:1815`). Each transition call-into-lowering is compared to its
static twin. "highlight" column: does the call cause the highlight stroke to be applied for that mark?
(`spec→hl` = highlight is resolved internally from the forwarded `spec`, as `lowerBar` does at `:1839`.)

### Static-fallback calls in `marksRegionWithTransitions` (`:3713`–`:3842`)

| Mark      | Call site | spec fwd? | color honored? | highlight fwd? | vs static twin |
|-----------|-----------|-----------|----------------|----------------|----------------|
| Bar       | `:3761` `lowerBar(...,Present(spec))` | YES | YES (via spec) | YES (spec→hl inside lowerBar) | CONSISTENT (static `:1760`) |
| Line      | `:3763` `lowerLine(...,Present(spec),Absent,resolveHighlight(Present(spec)))` | YES | YES | YES | CONSISTENT (static `:1766`) |
| Area      | `:3777` `lowerArea(...,Present(spec),Absent,resolveHighlight(Present(spec)))` | YES | YES | YES | CONSISTENT (static `:1781`) |
| Point     | `:3791` `lowerPoint(...,Present(spec),Absent,theme,resolveHighlight(Present(spec)))` | YES | YES | YES | CONSISTENT (static `:1785`) |
| Rule      | `:3807` `lowerRule(...)` | n/a | n/a | n/a (rule excluded, see §0.4) | CONSISTENT (`lowerRuleChildren :1798`) |
| Text      | `:3810` `lowerText(...,Present(spec),resolveHighlight(Present(spec)))` | YES | YES | YES | CONSISTENT (static `:1801`) |
| ErrorBar  | `:3815` `lowerErrorBar(...,Present(spec),resolveHighlight(Present(spec)))` | YES | YES | YES | CONSISTENT (static `:1804`) |

All 6 static fallbacks forward spec + color + highlight consistently with the static path. **No FAIL-1 here.**
(Uniform note: `marksRegionWithTransitions` has NO `internalHoverRef` param at all, so none of these forward
the hover ref — but that omission is uniform across every mark and is a pre-existing transition-path design
choice, not a per-mark inconsistency; out of the FAIL-1 class.)

### Animated twins (color is resolved inside the twin; highlight is by-design NOT applied during animation)

| Twin | spec used for color? | colorScale honored? | highlight | Verdict |
|------|----------------------|---------------------|-----------|---------|
| `lowerBarSimpleWithTransitions` `:3476` | n/a (only reached for `m.color.isEmpty`, guard `:3747`); colored bars go to static fallback `:3761` | YES (colored bars never reach the twin) | not applied (by design `:552`) | PASS |
| `lowerLineWithTransitions` `:3557` | YES — resolves `resolvePalette(spec,cats)` at `:3585`, then `lowerLineSeries(...,strokeColor)` | **YES — VERIFIED honors colorScale** | not applied (by design) | PASS |
| `lowerAreaWithTransitions` `:3644` | **NO — calls `lowerArea(...,defaultColor)` WITHOUT spec at BOTH `:3659` (stacked) and `:3664` (non-stacked)** | **NO — drops colorScale (and stacked also drops theme.palette)** | not applied (by design) | **FAIL** |

VERIFIED claim from audit context: `lowerLineWithTransitions` DOES honor colorScale (the animated colored
line resolves via `resolvePalette(spec, cats)` at `:3585`; the `resolved.isEmpty` guard at `:3596` only falls
to DefaultPalette when the palette is genuinely empty). There is even a regression test for it
(`ChartTransitionTest:420` "an animated color-split line honors an explicit categorical colorScale (FIX B)").
The asymmetry — line twin honors colorScale, area twin does not — is exactly the #244 inconsistency, now shown
to also exist at the stacked-area locus.

---

## P7 — GAP-TRANS-BAR-CHANNELS — **PASS**

- **Reuse (no inline copy):** `lowerBarSimpleWithTransitions` calls the shared `applyBarChannels` (`:1924`)
  at `:3515`; it does NOT re-implement opacity/tooltip/label inline. The static `lowerBarSimple` calls the
  same helper at `:1908`. Single shared implementation — matches design §0.2. PASS.
- **SMIL animates byte-identical:** the animated arm at `:3527`–`:3530` attaches
  `smilAnimate("height", fromH, barH, durStr)` then `smilAnimate("y", fromY, barY, durStr)` exactly as the
  pre-P7 code; `from`/`to` geometry math unchanged. L18 test asserts `from="0" to="210"` (height) and
  `from="440" to="230"` (y) (`ChartTransitionTest:531`–`539`). PASS.
- **`asInstanceOf[Svg.Rect]` cast sound:** `applyBarChannels` first element is `withTooltip`, which is one of
  `rect` | `rect.fillOpacity(..)` | `withOpacity(Svg.title(..))` — all return `Svg.Rect` at runtime
  (`.fillOpacity` and `Rect.apply(ShapeChild*)` both return Rect). Cast at `:3527` is sound; documented in
  the `:3522`–`:3526` comment. PASS.
- **Child order `[<title>, <animate>, <animate>]`:** `applyBarChannels` adds `<title>` first (when tooltip
  Present); the cast-rect then appends the two `<animate>` children → exact order claimed. PASS.
- **No-channel path byte-identical:** all-Absent channels → `applyBarChannels` returns `(rect, Chunk.empty)`;
  `channelRect` is the plain rect; animates attach as before. Co-pinned by `ChartTransitionTest:545` (no
  `fill-opacity`, no `<title>`). PASS.
- **L18 asserts concrete values:** `fill-opacity="0.5"`, `>Jan</title>`, label `>Jan<`, both `<animate>`
  attributeNames, and concrete from/to pixels. Dual-pinned with a no-channel byte-identity co-pin. PASS.
- **Minor deviation (benign):** design §0.2 listed `applyBarChannels` without `(using Frame)`; the committed
  helper carries `(using Frame)` (`:1932`). Frame is ordered last, correct convention. No behavioral impact.

WARN (P7, low): the L18 label assertion `html.contains(">Jan<")` (`ChartTransitionTest:519`) is broad —
`>Jan<` could also match an x-axis tick label. It is guard-then-concrete (separate `fill-opacity` and
`</title>` asserts exist), so acceptable, but a label-`<text>`-specific match would be stronger.

## P9 — GAP-HIGHLIGHT-COVERAGE — **PASS (coverage complete + consistent)**

- **`withHighlight` reused (no inline stroke copy):** line `:2268`/`:2289`, area `:2699`/`:2713`/`:2734`/`:2743`,
  text `:2474`, errorBar `:2581` all call `withHighlight` (`:1708`), which delegates the stroke override to
  `applyHighlightStyle` (`:1670`). No lowerer reimplements the `stroke=#000000 / stroke-width=2px` logic. PASS.
- **errorBar single-stroke grouping:** highlight-Present branch wraps the 4 sub-shapes
  (`vLine, capLow, capHigh, marker`) in `Svg.g(vLine)(capLow)(capHigh)(marker)` tagged `(row, rowG)` at
  `:2577`–`:2578`, then `withHighlight` re-styles the GROUP once. A highlighted row therefore carries EXACTLY
  ONE select stroke. PASS. L20 errorBar test (`ChartInteractionTest:773`) asserts `strokeOccurrences == 1`.
- **Absent / no-highlight byte-identity:** errorBar uses a true dual-loop — the `Absent` arm
  (`:2510`–`:2543`) emits the 4 sub-shapes FLAT exactly as the pre-P9 path (`loopFlat`), separate from the
  grouped `Present` arm. `withHighlight(_, Absent)` is `tagged.map(_._2)` (`:1713`), a no-op, for line/area/text.
  PASS. L20 tests assert `!htmlBefore.contains("stroke=\"#000000\"")` for the pre-select state.
- **L20 tests concrete:** all 4 (line `:650`, area `:681`, text `:712`, errorBar `:745`) assert
  before(`!contains stroke=#000000`) → after(`contains stroke=#000000 && stroke-width=2px`) → exactly-one
  occurrence via `"stroke=\"#000000\"".r.findAllMatchIn(...).size == 1`. Matches L20 invariant shape. PASS.
- **Granularity matches §0.4:** text/errorBar tagged per-row (`:2470` / `:2578`); line/area tagged with the
  series-representative first row (`:2264`/`:2286`, `:2695`/`:2710`/`:2730`/`:2739`). Consistent with the
  documented interaction-attr granularity. PASS.

### §0.4 exclusions — re-read and CONFIRMED genuine (not convenient)
- **Animation excluded:** grounded at design `:552` ("highlight not applied during animation, consistent with
  today"). The three animated twins (`lowerBarSimpleWithTransitions`, `lowerLineWithTransitions`,
  `lowerAreaWithTransitions`) take no `highlight` param and call no `withHighlight` — uniform, intentional.
- **Stacked bar excluded:** `lowerBarStacked` (`:2058`) takes no `highlight` param. Real reason: a stacked
  segment is a partial view of a group, not a single source row (lowerBar scaladoc `:1826`–`:1827`); §0.4
  assigns granularity only to text/errorBar (per-row) and line/area (series). Genuine, not convenient.
- **Rule excluded:** `lowerRule`/`lowerRuleChildren` are threshold lines with no data-row identity; no
  highlight param. Genuine.

WARN (P9, low): the L20 line/area tests use single-series (no `color` channel) charts, so they pin the
single-path rep-row tagging and the exactly-one-occurrence count, but do NOT exercise the multi-series
(colored) case where N series paths each carry their own rep-row tag and only the active one highlights.
The L20 invariant only required single-active coverage, so this is within contract, but a colored
multi-series highlight case would harden the "exactly the active series" claim. Coverage NOTE, not a defect.

---

## Reward-hacking / Kyo-convention sweep
- No weakened / `.nonEmpty`-sole asserts; every L18/L20 assertion is concrete (exact attr values + occurrence
  counts). No `assert(true)`. No test-name-only fixes. No `pending`/`.ignore`/TODO/FIXME on added lines.
- Kyo types: new params are `Maybe[Highlight[A]] = Absent` (not Option); Chunk used throughout. Frame ordered
  last on all touched signatures. No `protected`, no `@uncheckedVariance`. No new public surface — every
  touched lowerer stays `private` / `private[kyo]`.
- **Hygiene FAIL (added lines):** 4 em-dashes (`—`) on committed `.scala` test-comment lines added by P9:
  `ChartInteractionTest.scala:649, 680, 712, 744` ("Test 16/17/18/19 (L20): … — the active …"). CLAUDE.md
  forbids em-dashes everywhere including code comments. These are in committed test source (NOT in the
  `.plan/` exclusion). Main `ChartLower.scala` has zero em-dashes on added lines. Low severity, but it is a
  real convention violation on shipped lines.

---

## Findings ledger

| # | Sev | Phase | Locus | Finding |
|---|-----|-------|-------|---------|
| 1 | FAIL | P9-adjacent | `ChartLower.scala:3659` | NEW (not #244): animated STACKED area drops `spec` → `lowerAreaStacked(spec=Absent)` → `resolvePaletteFromCfg` (DefaultPalette only), dropping BOTH colorScale AND custom theme.palette. Static twin `:1781` honors both. Fix must add `Present(spec)` here in addition to the #244 non-stacked `:3664` fix. |
| 2 | FAIL | P9 | `ChartInteractionTest.scala:649,680,712,744` | 4 em-dashes on committed test-comment added lines (AI-tell convention violation). |
| 3 | WARN | P9 | `ChartInteractionTest.scala:650,681` | L20 line/area highlight tests cover single-series only; no colored multi-series highlight case. Within L20 contract; hardening gap. |
| 4 | WARN | P7 | `ChartTransitionTest.scala:519` | L18 label assertion `>Jan<` is broad (could match a tick label); guard-then-concrete so acceptable. |
| — | PASS | P7 | — | applyBarChannels reuse, SMIL byte-identity, cast soundness, child order, no-channel byte-identity all verified. |
| — | PASS | P9 | — | withHighlight reuse, errorBar single-group stroke, Absent byte-identity, granularity, exclusions all verified. |

## Verdict on highlight coverage
**Complete and consistent for the in-scope (non-animated) marks.** Line/area/text/errorBar all thread
highlight, reuse `withHighlight`/`applyHighlightStyle`, tag at the correct granularity (§0.4), and are pinned
by concrete before/after + exactly-one-occurrence L20 tests. The excluded marks (stacked bar, rule, animation)
are excluded for design-grounded, genuine reasons. The ONE substantive defect in the cluster is the
parameter-forwarding FAIL in `lowerAreaWithTransitions` — and it is broader than task #244: it occurs at TWO
call sites (`:3659` stacked, `:3664` non-stacked), the stacked one additionally dropping theme.palette via
`resolvePaletteFromCfg`. Ensure the #244 fix patches BOTH `lowerArea(...)` calls in that function, not just the
non-stacked path.
