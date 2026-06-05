# 00 — Binding development guides (kyo-ui chart feature-completeness, 14 gaps)

Sources extracted: root `CONTRIBUTING.md`, root `CLAUDE.md`, `kyo-ui/README.md` (Charts section).
`kyo-ui/CONTRIBUTING.md` **does not exist** -> recorded as a **GAP** (see §4). No module guide was
invented. Every claim below is cited to file:section/line.

---

## Binding constraints

Each is an enforceable rule the campaign MUST obey.

1. **Cross-platform `shared/` placement; JVM + JS + Native green is the final gate.** All source
   changes land in `kyo-ui/shared/src/main`, all tests in `kyo-ui/shared/src/test`; never move a test
   into `jvm/`/`js/`/`native/` to dodge a platform. Only genuine platform-specific behavior justifies a
   split. (`CONTRIBUTING.md` Testing §829-840 "Platform-Conditional Tests"; root `CLAUDE.md` Working
   Mindset "All platforms, shared tests"; exploration §4 confirms every locus is `shared/`.)

2. **Use Kyo value types, not stdlib.** `Maybe` not `Option`, `Result` not `Either`/`Try`, `Chunk` not
   `List`/`Seq`/`Vector`, `Span` over array wrappers. `Option` only as conversion input.
   (`CONTRIBUTING.md` Types §233-249; root `CLAUDE.md` Pre-Submission Checklist.)

3. **Public APIs carry explicit return types.** Every public method/value touching the chart surface
   (e.g. a new `yScaleRight` builder, a changed `AxisConfig` ctor) must declare its return type.
   (`CONTRIBUTING.md` Pre-Submission "Public APIs have explicit return types" — referenced in root
   `CLAUDE.md` Pre-Submission Checklist.)

4. **Public types get scaladoc, 8-35 lines.** Any public type touched/added (e.g. `ChartSpec` field
   additions, `AxisConfig` changes) needs the type-level scaladoc shape: definitional opener, "why",
   capability bullets, `WARNING:`/`IMPORTANT:`/`Note:` callouts, `@tparam`. **If a `side`/`labelAllBands`
   knob is removed, its now-false scaladoc must be removed/corrected, not left dangling** (GAP-AXISCONFIG-SIDE
   scaladoc at API:2489 is currently FALSE per exploration §3). (`CONTRIBUTING.md` Documentation §434-455.)

5. **No `protected`, no `@uncheckedVariance`; use `private[kyo]` for cross-package visibility.**
   Threading `spec`/`colorScale`/`highlight` into private lowering helpers stays internal — use
   `private[kyo]` (or tighter), never `protected`. (root `CLAUDE.md` Pre-Submission Checklist;
   `CONTRIBUTING.md` File Organization §604 Visibility Tiers.)

6. **`using`-clause ordering is fixed.** Inline methods: `Tag` before `Frame`. Non-inline methods:
   `Frame` before type-level evidence. `AllowUnsafe` always last. (`CONTRIBUTING.md` Method Signatures
   §342-357.)

7. **`inline` only on suspend/create paths, never on handling paths.** Effect handlers
   (`Abort.run`, `Var.run`-style) are regular methods. The chart lowering helpers are pure projections,
   not effect handlers; do not sprinkle `inline` to "optimize" — use it only to avoid genuine function
   dispatch on suspension paths. (`CONTRIBUTING.md` Inline Guidelines §718; root `CLAUDE.md` Common
   Gotchas #2.)

8. **Tests extend the module `Test` base class, not ScalaTest directly.** `kyo-ui` defines
   `kyo-ui/shared/src/test/scala/kyo/Test.scala`; `ChartLowerTest`/`ChartAxisTest`/`ScaleTest` already
   extend it (exploration §1.4). New tests do the same. (`CONTRIBUTING.md` Testing §774-800.)

9. **Every test asserts a specific concrete value.** Assert the rendered fill string, the
   `x1`/transform value, the gridline element presence, the reserved `plotY`/topPad — never
   `assert(true)`, never type-only/non-empty checks. (`CONTRIBUTING.md`-aligned root `CLAUDE.md` "Write
   Meaningful Tests"; exploration §3 names the exact concrete assertion per gap.)

10. **Reproduce before you fix; keep the test as a regression guard.** For each of the 14 gaps, write a
    test that FAILS for the right reason FIRST (the actual symptom: wrong fill, left-edge px, missing
    transform, overlapping legend), then fix, then keep it. None of the 14 has a test today (steering §5,
    exploration §1.4). (root `CLAUDE.md` "Reproduce Before You Fix"; steering §5.)

11. **Fix the code, not the test; never weaken a test.** Diagnose root cause before changing either
    side; only change a test when the test itself is wrong, and document why. Preserve the existing
    themePalette/Absent-path guards (e.g. `ChartLowerTest.scala:~1728`) while fixing the colorScale path.
    (root `CLAUDE.md` "Fix the Code, Not the Test"; exploration §2.1.)

12. **No reflection / source-parsing in tests.** Assert on rendered SVG values from the caller's
    perspective (the `Svg.*` element tree), not on internal structure via reflection. (root `CLAUDE.md`
    "Write Meaningful Tests" "Test behavior, not implementation"; exploration's assertions are all
    render-level.)

13. **Naming: action verbs, no symbolic operators.** Any new public builder uses a plain descriptive
    name (`yScaleRight`, not an operator). No symbolic operators in core-style modules. (`CONTRIBUTING.md`
    Naming §191-205.)

14. **No AI-generation tells in any output** (code comments, scaladoc, README, commit messages): no
    em-dashes/en-dashes, no marketing adjectives, no filler openers. Use commas/colons/parentheses or
    separate sentences. (root `CLAUDE.md` Working Mindset "No AI-generation tells".)

15. **Leave no issue behind; no scope cuts; order by technical dependency only.** All 14 gaps get a
    concrete fix, none deferred/dropped/"revisit later"; any red signal found in passing is owned;
    phases ordered by what-must-compile-before-what, never by severity/priority. New features stay out of
    scope (flag, do not silently expand). (root `CLAUDE.md` "Complete and correct, no scope cuts" +
    "Leave No Issue Behind"; steering §§1-3.)

16. **Safe by default.** Prefer the safe API tier; reach for `AllowUnsafe`/unsafe only at a justified
    bridging boundary with a `// Unsafe:` comment. (root `CLAUDE.md` "Safe by default"; `CONTRIBUTING.md`
    Unsafe Boundary §874.)

---

## Conventions + recipes (chart layer)

- **Marks lower to pure SVG-string projections.** Each `lowerXxx` builds `Svg.*` element values; no
  `java.*`/DOM/platform calls except `java.lang.Double.isFinite` (cross-platform-backed). Mirror an
  EXISTING correct lowering, do not invent. (exploration §4; §2.)

- **`resolvePalette` is THE canonical colorScale resolver.** `resolvePalette(spec, categories)`
  (LOWER:1302-1315) maps Categorical raw values through the user fn, interpolates Sequential, and falls
  back to `theme.palette` then `DefaultPalette` by index only for the **Absent** case. The gap-mark fix
  for GAP-1/2/3/4 is: route any present colorScale through `resolvePalette` (mirror `lowerLine`
  2147-2161 / `lowerBarStacked` 1971 / `lowerPoint` 2789), keeping the by-index path ONLY for Absent.
  (exploration §2.1; README "Color scales and themes" §1224-1235: mark fills are concrete colors, never
  `url(#...)` gradient refs.)

- **`themePalette` is the by-index fallback, NOT a colorScale source.** `themePalette` (LOWER:149-150)
  is correct only under an Absent colorScale; using it under a present scale is the bug in
  grouped-bar/text/errorBar. Preserve themePalette behavior for the Absent path (its regression guard
  `ChartLowerTest.scala:~1728` must stay green). (exploration §1.1, §2.1.)

- **Band-centering recipe.** Point-like marks use `px = xs.apply(x) + xs.bandwidth / 2.0` (no-op on
  continuous scales). errorBar deviates (`xs.apply(x)` at LOWER:2384); the fix is exactly the centered
  form, matching line 2198 / area 2461 / point 2825 / text 2320. (exploration §2.2.)

- **Axis chrome recipe.** `buildXAxis` (957-1034) is the complete reference for `tickAnchor` mapping,
  `tickRotation`, and `withFont` (theme.fontFamily/fontSize). `buildYAxis` lacks all three; mirror them.
  (exploration §2.4.)

- **`sbt kyo-ui/test` workflow.** Run the JVM aggregate as `sbt 'kyo-ui/test'` (project id is `kyo-ui`,
  NOT `kyo-uiJVM` — that id does not exist and errored a prior run, charts-improvements
  phase-03/verify.md:15). JS/Native ids are `kyo-uiJS` / `kyo-uiNative` (final-gate-js2.log;
  build.sbt:283,322). Single class: `sbt 'kyo-ui/testOnly kyo.ChartLowerTest'`.
  (`CONTRIBUTING.md` Build & Test; build.sbt:236-322.)

- **README doctest requirement.** Every fenced ` ```scala ` block in `kyo-ui/README.md` compiles against
  the module classpath via `sbt kyo-ui/doctest` (some blocks tagged `doctest:expect=skipped` /
  `doctest:platform=js`). If the public chart surface changes, update the README Charts examples and
  keep doctest green. (`CONTRIBUTING.md` Module READMEs §646-648; README §15, §1061, §1224.)

- **Build auto-formats; re-read edited files.** Building runs scalafmt; line structure (and docstring
  wrapping) may shift after a build, so re-read any file you edited before further editing.
  (`CONTRIBUTING.md`/root `CLAUDE.md` Build & Test "Building automatically formats the code".)

---

## Traps

1. **`kyo.System` shadows `java.lang.System`.** Use fully-qualified `java.lang.System` if needed.
   (root `CLAUDE.md` Common Gotchas #1.) Low relevance here (charts use no `System`), but binding.

2. **Effect handlers are not inline.** Do not add `inline` to handling-style methods; only
   suspend/create paths. (root `CLAUDE.md` Common Gotchas #2; constraint §7.)

3. **`Frame` required on every effectful method** (not on pure data accessors like `size`/`capacity`).
   Most chart lowering helpers are pure projections; an effectful one needs `(using Frame)`. (root
   `CLAUDE.md` Common Gotchas #3.)

4. **Overloads delegate to the canonical method; never duplicate logic.** If a fix adds an
   `AxisConfig`/`ChartSpec` builder overload, it must delegate, not re-implement. (root `CLAUDE.md`
   Common Gotchas #4.)

5. **macOS case-insensitive object-name clash.** On the dev filesystem, two top-level objects/classes
   differing only in case (e.g. `UI$mark` vs `UI$Mark` class files) can collide on disk. Watch for it
   when touching `UI.mark.*` factories / nested mark types; avoid introducing a name that differs only in
   case from an existing sibling. (named module gotcha per task brief; `UI.mark` surface confirmed in
   README §1098, exploration API map.)

6. **Stale-`.class` / zero-recompilation false green (and the cross-platform `.sjsir`/`.tasty` twin).**
   A test run that shows NO `Compiling` lines ran against stale artifacts and does NOT validate your
   source change — it is not a real pass. A prior campaign hit exactly this: a green JVM testOnly log ran
   with zero recompilation on stale `.class` files and could not certify the source
   (charts-improvements phase-04/verify.md:10-26, validation.json:43). The JS/Native equivalent is stale
   `.sjsir`/`.tasty`. **Force a clean compile of the dependency chain on each platform before trusting a
   green** (the final gate is JVM + JS + Native), and treat a no-`Compiling` log as RED.

---

## Update obligation (end of campaign)

1. **`kyo-ui/CONTRIBUTING.md` GAP.** No module contributor guide exists. This campaign relies on root
   `CONTRIBUTING.md` + root `CLAUDE.md` + this file. At end of campaign, flag to the user whether a
   `kyo-ui/CONTRIBUTING.md` (or a chart-conventions note) should be created capturing the
   resolvePalette/themePalette/band-center/axis-chrome recipes; do NOT invent one mid-campaign.
   (verified absent: `ls kyo-ui/CONTRIBUTING.md` -> not found.)

2. **README Charts section + doctest.** If the public chart surface changes (most likely
   GAP-RIGHTY-SCALE adding `yScaleRight`; GAP-LABELALLBANDS / GAP-AXISCONFIG-SIDE removing or wiring a
   knob), update the relevant `kyo-ui/README.md` Charts examples (§§1159-1300: "Two axes", "Scales,
   axes, and legends", "Color scales and themes", "Axis chrome") and re-run `sbt kyo-ui/doctest` so every
   fenced block still compiles. (README §1176-1300; `CONTRIBUTING.md` §646-648.)

3. **Scaladoc correction for any removed/changed public knob.** If `AxisConfig.side` /
   `labelAllBands` are removed, delete their (currently false) scaladoc and any `Side`-enum references
   that become dead. (constraint §4; exploration §3 GAP-AXISCONFIG-SIDE.)

---

## STALE findings

No guide claim contradicts current source. Notes:

- **Project-id string staleness (resolved here, not a guide error):** prior campaign artifacts use
  `kyo-uiJVM`, which is not a valid sbt project id; the correct JVM id is `kyo-ui`
  (charts-improvements phase-03/verify.md:15,57). Recorded in §"Conventions" so the plan does not
  inherit the wrong id.
- **`kyo-ui/CONTRIBUTING.md` referenced by root guides as the per-module guide does not exist** —
  treated as a GAP (§4.1), not a STALE contradiction, since root `CONTRIBUTING.md` is module-agnostic.
- The exploration already re-verified all 14 gap loci against fresh reads (exploration §6); no guide
  recipe (resolvePalette, band-center, axis-chrome) is contradicted by those reads.
