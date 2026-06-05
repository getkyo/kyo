# Steering — kyo-ui chart feature-completeness campaign

## Task
Make the EXISTING kyo-ui chart-layer functionality complete and consistent by closing the 14
feature-completeness gaps documented in
`kyo-ui/.plan/charts-improvements/end/feature-completeness-gaps.md` (the de-facto exploration input,
already code-grounded with file:line, reachability, severity, and fix direction for each gap, plus
mark x feature and scale x feature matrices and a "checked & CONSISTENT" baseline).

This `/flow` invocation is PLAN-ONLY: run Stage 1 (explore -> guides -> public-api -> design ->
resolve-open -> invariants -> plan -> validate -> api-design T1), present the plan for REVIEW, and STOP.
Do NOT enter Stage 2 / implement anything until the user approves.

## Binding constraints (enforced by every stage + the reward-hacking gate)
1. NO SCOPE CUTS. All 14 gaps (GAP-COLOR-GROUPEDBAR, -TEXT, -ERRORBAR, -AREA-SIMPLE; GAP-RIGHTY-SCALE;
   GAP-YAXIS-ROTATION; GAP-THEME-FONT; GAP-RIGHTY-GRID; GAP-LABELALLBANDS; GAP-AXISCONFIG-SIDE;
   GAP-LEGEND-MARGIN-TEXT-ERRORBAR; GAP-TRANS-BAR-CHANNELS; GAP-HIGHLIGHT-COVERAGE; GAP-ERRORBAR-BANDCENTER)
   MUST appear in the plan with a concrete fix. None deferred, none dropped, none "revisit later".
2. DO NOT INFER PRIORITIES. Phases are ordered by TECHNICAL DEPENDENCY only (what must compile/exist
   before what). No priority/importance/severity-ranking language in the plan ordering. Severity is
   recorded as an attribute for the reviewer, never used to order or to justify cutting a phase.
3. NEW FEATURES ARE OUT OF SCOPE. The goal is completing/uniformizing EXISTING knobs, not adding mark
   types/scale kinds/channels. The gap file's "out of scope" section (e.g. morphSteps, structural
   line/area morph) stays excluded. If a "fix" would require a genuinely new capability, flag it for the
   user rather than silently expanding into a feature.
4. CROSS-PLATFORM by default (kyo): every change lands in `kyo-ui/shared/src/main`, every test in
   `kyo-ui/shared/src/test`; the campaign's final gate is JVM + JS + Native green. No jvm/js/native-only
   placement without a per-source rationale matching the exception bar.
5. REPRODUCE-BEFORE-FIX: every gap fix is planned with a failing test FIRST (a categorical-colorScale
   render assertion per mismatching mark; a right-axis override readback; a y-tick rotation assertion;
   etc.) that fails for the right reason, then the fix, kept as a regression guard. The gap file notes
   NONE of the 14 has a test today.
6. PUBLIC-API DECISIONS ARE FIRST-CLASS. Some fixes touch the public surface and must be locked by
   flow-public-api before design:
   - GAP-RIGHTY-SCALE: does the right axis need its own override source (e.g. a `yScaleRight`), or does
     it reuse `yScaleOverride` for right-bound marks? This shapes the public API.
   - GAP-LABELALLBANDS (D18) and GAP-AXISCONFIG-SIDE (`.left/.right/.top`): DEAD public knobs (never
     read; `side` scaladoc is FALSE). The honest fix is WIRE the behavior OR REMOVE the misleading
     API. This is value-underdetermined and visibly shifts the public surface -> escalate ONE yes/no
     each (recommendation + write-in), do NOT pick silently.
   - Threading `spec`/`legendCfg.colorScale` into lowerText/lowerErrorBar is INTERNAL (private lowering
     signatures), not public — bind without escalation.
7. Diagnostic discipline: every gap claim in the plan must trace to the gap file's file:line or to a
   fresh read; do not inherit framing without the citation. The gap file was supervisor-validated
   (spot-checked GAP-4/5/6/7/9/10/14 against source; GAP-1/2/3/12/13 cross-checked) so it is high-trust,
   but design/plan still cite source.

## Binding constraints folded from design/00-guides.md (root CONTRIBUTING.md + CLAUDE.md; kyo-ui has NO module guide -> GAP for end-of-campaign)
- All fixes in `kyo-ui/shared/src/main`, tests in `kyo-ui/shared/src/test`; final gate JVM + JS + Native green (the same shared tests run on all 3). No platform-only placement without a per-source rationale.
- Reproduce-before-fix: a concrete-value-asserting test per gap (assert exact rendered fill/stroke == the colorScale color, exact tick rotation attr, exact right-axis domain readback, exact errorBar px = apply+bandwidth/2), failing first for the right reason; NEVER weaken the existing `themePalette`/`Absent` fallback guards (the no-colorScale path must stay byte-identical, e.g. INV-004 golden unchanged).
- Mirror the canonical pattern, do not invent: color resolution = `resolvePalette(spec, cats)` when a colorScale is Present, `themePalette`/DefaultPalette by index only when Absent (this is exactly how lowerLine/lowerBarStacked/lowerPoint/legend already do it); band-centering = `xs.apply(x) + xs.bandwidth/2.0`; highlight = `withHighlight(shapes, resolveHighlight(...))`.
- Kyo types (Maybe/Result/Chunk), public APIs have explicit return types + scaladoc (8-35 lines), no `protected` (use `private[kyo]`), using-clause ordering, `inline` only on suspend not handling paths. Building auto-formats -> re-read edited files.
- Build/test trap: a stale `.class`/`.sjsir`/`.tasty` can show a false green with zero recompilation; the final cross-platform gate must clean-build (the prior campaign hit exactly this with `UI$mark$.sjsir`). Project id is `kyo-ui`/`kyo-uiJS`/`kyo-uiNative` (not `kyo-uiJVM`).
- End-of-campaign update obligation: refresh the README Charts section + `sbt kyo-ui/doctest` if the public chart surface changes; create `kyo-ui/CONTRIBUTING.md` only if the campaign establishes module-wide invariants (per /flow Stage 3 guide step).

## APPROVED 2026-06-05 -> Stage 2 execute to completion
User approved the plan and said "execute to completion, strictly follow /flow, do not leave ANY issues
behind." The 3 escalations are answered YES to all recommendations (no design change; design already
implements them):
- Q-1: ADD `yScaleRight` builder + `ChartSpec.yScaleRightOverride` (candidate B). [P11]
- Q-2: REMOVE dead `labelAllBands`. [P12]
- Q-3: REMOVE `side` + `.left/.right/.top/.bottom` setters + `Side` enum + fix the false scaladoc,
  ACCEPTING the ~84-site chain-start rewrite (42 demos + 36 tests + 6 README doctests) + doctest re-run.
  "Leave no issues behind" = the clean REMOVE, not the cosmetic KEEP-AS-MARKER middle option. [P12]

## Stage 2 discipline (mirrored per /flow)
- NEVER STOP: once a phase verifies green, COMMIT it and immediately launch the next. No mid-campaign
  status reports; between phases the only user-facing line is "Phase NN committed: <sha>". Substantive
  prose only for a genuine block or ALL-PHASES-COMPLETE.
- COMMIT AT EVERY GREEN CHECKPOINT: commit each validated phase (and each validated mid-phase fix) before
  the next; never let uncommitted work span more than one logical change. Check git status before each
  new agent.
- Dispatch-then-work: after launching a phase impl (SLOT-A), immediately do the next independent unit
  (prep N+1, audit N-1 in SLOT-B, baseline N+1) -- never dispatch-then-idle. Cap 2 concurrent.
- LEAVE NO ISSUE BEHIND (user hardline): every WARN/NOTE from every phase audit is addressed before
  ALL-PHASES-COMPLETE; every reproduce-before-fix test stays green; no `pending`/TODO/FIXME introduced;
  the final cross-platform CLEAN-BUILD gate (JVM+JS+Native) must be green (beware stale .sjsir/.tasty
  false green) and any failure is root-fixed, never retry-passed or excused.
- Agents NEVER git add/commit; the supervisor commits. Agents write all .scala/.md; supervisor writes
  only control/ files.
- Per-phase verify = targeted (JVM only); the final phase P13 runs cross-platform-full clean.
