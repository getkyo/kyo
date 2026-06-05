# 03b — User escalations (value-underdetermined PUBLIC forks)

These are the 3 `value-underdetermined` PUBLIC forks from `design/03-candidates.json`. Each visibly
shifts the user-facing surface, so per steering §6 this skill does NOT pick unilaterally. Each is
rendered as a SINGLE yes/no on the recommended answer with a write-in slot (per the /flow render
rule, NOT a labeled-option menu): answer **yes** to take the recommendation, or write in an
alternative.

The design (`02-design.md`) already implements the RECOMMENDED answer for all three as its working
assumption, so a **yes** to each leaves the design unchanged. A **no** / write-in requires a design
revision (and, for Q-1, a `02-public-api §6` re-audit; see the note at the end of this file).

Source of truth:
- API   = `kyo-ui/.plan/charts-completeness/design/02-public-api.md`
- DESIGN = `kyo-ui/.plan/charts-completeness/design/02-design.md`
- UI    = `kyo-ui/shared/src/main/scala/kyo/UI.scala`
- LOWER = `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala`

---

## Q-1 (Q-RIGHTY-SCALE-SOURCE) — right-axis scale override source

**Problem.** The right y-axis scale is hardcoded `Scale.fit(Linear, rExt, baseline, plotY,
nice=true)` (LOWER:744-748) and reads NONE of the override knobs the left axis honors, so
`.yScale(_.log)`, `.withClamp`, `.withPad`, `.yAxisRight(_.reverse)` are silently dropped for
right-bound marks. The fork: does a right-bound mark's scale reuse the EXISTING `yScaleOverride`
(candidate A, no new surface), or does the right axis get its OWN override source (candidate B)?

**Recommendation.** Add a dedicated `yScaleRight(f: ScaleOverride => ScaleOverride)` extension
method + `ChartSpec.yScaleRightOverride: Maybe[ScaleOverride]` field (candidate B).

**Rationale.** Every other layer already separates the right axis: it has its own chrome config
(`yAxisRightCfg` / `.yAxisRight(f)`) and its own extent (`yRightExtent`); a right-bound mark
declares `axis = Axis.Right` explicitly. The ONLY thing shared today is the scale override, and
that is precisely the unfinished part. d3 / grammar-of-graphics convention treats dual axes as
independent scales; the canonical dual-axis case (e.g. linear-left revenue + log-right ratio) is
inexpressible under candidate A because it couples left and right scale KINDS. B mirrors the
existing `yScaleOverride` / `yAxisRightCfg` pairing.

**Full cost / consequence.**
- Recommended (B): ONE new public builder (`yScaleRight`) + ONE new `ChartSpec` field
  (`yScaleRightOverride`), both nesting under existing owners (top-level type budget 0 preserved),
  both pure `ChartSpec[A] => ChartSpec[A]`, both mirroring `yScale` 1:1. NO existing call-site
  churn: the only constructor site (the `UI.chart` factory, UI.scala:938-953) passes `Absent` for
  the new field. Internally the right block factors into the shared `resolveYScale` so the right
  path becomes the left path.
- Alternative (A): NO new public member, but it COUPLES left/right scale kinds: a dual-axis chart
  cannot have a linear left and a log right axis, contradicting the reason a right axis exists. It
  also changes the `yScale` scaladoc to claim it drives BOTH axes.
- This fork ADDS user-facing surface (B) vs not (A); it is irreversible once phases ship (removing
  or renaming a public field + method is a public-surface restructure).

>>> ESCALATE (yes/no): Add the dedicated `yScaleRight` builder + `ChartSpec.yScaleRightOverride`
field (candidate B)?  [ ] yes
Write-in (if not yes): __________________________________________________

**Binding source.** API §2.1 (lines 81-211, Candidate B RECOMMENDED, `>>> ESCALATE`); DESIGN
§GAP-RIGHTY-SCALE (324-376) + §4 Rejected alternatives (684-686).

---

## Q-2 (Q-LABELALLBANDS-WIRE-OR-REMOVE) — dead `AxisConfig.labelAllBands` knob

**Problem.** `AxisConfig.labelAllBands: Boolean = true` (UI.scala:2504, tagged `// D18`) has ZERO
reads in the whole module and NO setter: it is a dead public field whose name promises "label all
bands (vs. thinning)" and delivers nothing. The fork: WIRE it (build a band-label thinning policy +
setter) or REMOVE the field?

**Recommendation.** REMOVE the field.

**Rationale.** A dead public knob that lies about its effect is a correctness defect. There is no
setter, no read, and no documented thinning behavior to "finish"; WIRE would invent a NEW feature
(thinning + an `N` hint), which steering §3 places out of scope. REMOVE is the smaller, honest
surface and changes ZERO rendered output (the field was never read).

**Full cost / consequence.**
- Recommended (REMOVE): TRIVIAL. `labelAllBands` has NO public setter and ZERO reads, so removing
  the field touches only `AxisConfig` (and its default arity). The `AxisConfig.default` literal
  (UI.scala:2522) does NOT pass `labelAllBands` positionally (it relies on the trailing default), so
  this removal alone does not change `default`'s arity. No call-site churn. The one test reference
  (`ChartAxisTest.scala:1068`) is a test-NAME string only ("...(labelAllBands default)"), not a
  field read; the name is corrected without weakening the 7-label assertion.
- Alternative (WIRE): a NEW feature. It requires designing a band-label thinning algorithm (what is
  "N"? a new hint field), adding setters, and wiring `buildXAxis`/`buildYAxis` tick-label loops to
  thin on a Band/Ordinal scale. That is genuinely new behavior, not completing an existing knob.
- This fork removes a `.copy`-able public field (REMOVE) vs introduces a new public thinning knob
  (WIRE); it is irreversible once phases ship.

>>> ESCALATE (yes/no): REMOVE the dead `labelAllBands` field?  [ ] yes
Write-in (if not yes): __________________________________________________

**Binding source.** API §2.2 (lines 215-257, REMOVE RECOMMENDED, `>>> ESCALATE`); DESIGN
§GAP-LABELALLBANDS (580-601).

---

## Q-3 (Q-AXISCONFIG-SIDE-WIRE-OR-REMOVE) — dead `AxisConfig.side` + FALSE scaladoc

**Problem.** `AxisConfig.side: Maybe[Side]` (UI.scala:2495), its four setters `left`/`right`/
`bottom`/`top` (2506-2509), and the `Side` enum (469-471) have ZERO reads in the module; placement
is fully positional + `yAxisRightCfg`-driven. Worse, the `AxisConfig` scaladoc at UI.scala:2489 is
FALSE: it claims `side` "selects where the axis line and labels are drawn", but nothing reads it, so
`.xAxis(_.top)` does NOT move the axis. The fork: WIRE positional placement, or REMOVE the field +
setters + enum and fix the false scaladoc?

**Recommendation.** REMOVE the `side` field + the `.left`/`.right`/`.top`/`.bottom` setters + the
`Side` enum, and delete the FALSE scaladoc sentence at UI.scala:2489.

**Rationale.** This is the clearest correctness defect of the three: the scaladoc actively lies and
the knob has zero reads. Placement is determined positionally and by `yAxisRightCfg` everywhere, and
axis SELECTION is already method-based (`xAxis`/`yAxis`/`yAxisRight` already determine placement), so
`side` has no role in the actual model. WIRE would build a free-placement feature that does not
exist (steering §3 out of scope) AND would conflict with the existing method-based axis selection.

**Full cost / consequence.**
- Recommended (REMOVE): LARGE mechanical rewrite. The `.left`/`.right`/`.top`/`.bottom` setters are
  used ~84 times as CHAIN-STARTS across the repo (≈42 in demos, ≈36 in chart tests, 6 in README
  doctests). Every such chain must be rewritten, e.g.:
  - `.yAxis(_.left.grid.ticks(4))` -> `.yAxis(_.grid.ticks(4))`
  - `.xAxis(_.bottom)` -> dropped (positional bottom is the default)
  - `.yAxisRight(_.right...)` -> `.yAxisRight(...)` with `.right` removed
  Plus: delete the `Side` enum, fix the false scaladoc sentence (2489), rewrite the type-doc example
  at 2487 (`_.left.grid.ticks(5)`), and adjust `AxisConfig.default` arity (drop the leading
  positional `Absent`: `AxisConfig(Absent, Absent, false, 5, Absent)` ->
  `AxisConfig(Absent, false, 5, Absent)`). Rendered output is UNCHANGED (nothing read `side`); the
  churn is purely call-site/scaladoc. README doctest blocks (lines 1088/1168/1169/1186/1187/1291)
  must be rewritten and `sbt kyo-ui/doctest` re-run (see `03a` R-2). `.xAxis(_.top)` etc. then stop
  COMPILING, converting a silent no-op into a compile error.
- **Honest middle alternative the reviewer may prefer (KEEP-AS-MARKER):** keep the `.left`/`.right`/
  `.top`/`.bottom` setters as conventional orientation MARKERS but fix the scaladoc to STOP claiming
  they move the axis. This is minimal churn (no ~84-site rewrite; only the false scaladoc sentence
  changes), but it RETAINS a cosmetic no-op API.
- **WIRE is NOT recommended:** making `side` actually relocate the axis conflicts with the existing
  method-based axis selection (`xAxis`/`yAxis`/`yAxisRight` already determine placement) and builds
  a free-placement model the module does not have (steering §3 out of scope).
- This fork is irreversible once phases ship (removes a public field, four public setters, and a
  public enum).

>>> ESCALATE (yes/no): REMOVE the `side` field + `.left`/`.right`/`.top`/`.bottom` setters + the
`Side` enum + fix the false scaladoc (accepting the ~84-site chain-start rewrite)?  [ ] yes
Write-in (if not yes; e.g. "keep setters as orientation markers, only fix the scaladoc"):
__________________________________________________

**Binding source.** API §2.3 (lines 261-314, REMOVE RECOMMENDED, `>>> ESCALATE`); DESIGN
§GAP-AXISCONFIG-SIDE (603-631); `03a` R-1 (blast radius) + R-2 (README sweep).

---

## Public-surface note (02-public-api re-audit)

All 3 forks are ALREADY enumerated in `02-public-api.md` (§2.1 / §2.2 / §2.3) and the design already
implements the recommended answer for each, so a **yes** to all three requires NO patch to
`02-public-api.md` and NO patch to `02-design.md`.

A **no** / write-in changes the locked public surface and would require a `02-public-api §6`
re-audit, plus a `02-design.md` revision:
- Q-1 write-in to candidate A: removes the `yScaleRight` builder + `yScaleRightOverride` field from
  §2.1 and §1 (re-run the §6 self-audit; revise DESIGN §GAP-RIGHTY-SCALE to the shared-override
  resolution).
- Q-2 write-in to WIRE: adds a new thinning knob + behavior to §2.2 (new public member => §6
  re-audit; revise DESIGN §GAP-LABELALLBANDS).
- Q-3 write-in to KEEP-AS-MARKER: retains the setters + `Side` enum but corrects the scaladoc;
  smaller §6 delta (no removals), revise DESIGN §GAP-AXISCONFIG-SIDE to scaladoc-only and drop the
  ~84-site rewrite. Q-3 write-in to WIRE: adds a placement model (§6 re-audit; large DESIGN revision).
