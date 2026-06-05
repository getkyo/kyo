# Phase P13 prep: README Charts-section refresh + doctest + cross-platform CLEAN-BUILD gate

Status: READ-ONLY. No source modifications. No commit.

---

## 0. Context and scope

P13 is the FINAL phase. It closes no rendering gap directly; instead it satisfies two
end-of-campaign obligations from steering §54:

1. Refresh the README Charts section to reflect the locked public-surface delta:
   - P12 REMOVES `AxisConfig.side` + four setters (`.left`/`.right`/`.top`/`.bottom`) + the `Side`
     enum. Six README doctest blocks use removed setters and will FAIL TO COMPILE after P12.
   - P11 ADDS `yScaleRight`. The "Two axes" example may be enhanced with a `yScaleRight` usage.
2. Run `sbt kyo-ui/doctest` to verify every fenced `scala` block in `kyo-ui/README.md` compiles
   and runs green.
3. Run the FINAL cross-platform CLEAN-BUILD gate: JVM + JS + Native, from a CLEAN build.

P10/P11/P12 are not yet committed at this writing (the most recent commit is P8:
`2fe7e6d3d [ui] charts: wire Y-tick rotation/anchor + theme font everywhere (P8)`).
All mechanics below are grounded in the CURRENT worktree state (README, build.sbt, plugin source).
Any detail that depends on P11/P12 output is flagged with "RE-CONFIRM AT IMPL TIME."

---

## 1. README location and doctest mechanics

### 1A. File location

`kyo-ui/README.md` (absolute: `kyo-ui/README.md` at repo root).

The KyoDoctestPlugin default lookup (from `KyoDoctestPlugin.scala` projectSettings, line 194-201):
checks `baseDirectory / "README.md"`, then `baseDirectory / ".." / "README.md"`. The JVM project
(`kyo-ui`, with `.withoutSuffixFor(JVMPlatform)`) has `baseDirectory = kyo-ui/jvm/`. That
directory has no README.md, so the plugin falls through to `kyo-ui/README.md`. Confirmed: the
file exists and is 1624 lines long.

### 1B. Doctest command

```
sbt 'kyo-ui/doctest'
```

This is the JVM project. The JVM project ID is `kyo-ui` (NOT `kyo-uiJVM` -- the project uses
`.withoutSuffixFor(JVMPlatform)` which makes the JVM artifact the unsuffixed name). Confirmed
from build.sbt line 1186-1188 and the `crossProject(...).withoutSuffixFor(JVMPlatform)` pattern.

### 1C. How blocks are selected

The plugin validates every fenced ```` ```scala ```` block in the README. Blocks annotated
```` ```scala doctest:platform=js ```` are skipped on JVM (the plugin's platform filter).
A block annotated ```` ```scala doctest:expect=skipped ```` is compiled but its output is not
asserted. A plain ```` ```scala ```` block is compiled AND its expression values are evaluated.

The global predef (`doctestPredef := Seq("import kyo.*")`) injects `import kyo.*` at the top of
every block's wrapper, so top-level `import kyo.*` inside a block is redundant but harmless.

The Charts section uses one running domain defined in a ```` ```scala doctest:expect=skipped ````
block (line 1061) that declares `Region`, `Usd`, `Sale`, and `sales`. Every subsequent
```` ```scala ```` block in the Charts section relies on these declarations being in scope. The
doctest plugin handles this by compiling blocks as a file-level sequence (later blocks see earlier
bindings).

### 1D. Total scala blocks in the Charts section (lines 1051-end)

README total: 91 fenced ```` ```scala ```` blocks (grep-confirmed). Platform-js blocks (annotated
`doctest:platform=js`) are outside the Charts section and are not run by `kyo-ui/doctest`.

The Charts section (starting line 1051) contributes the following ```` ```scala ```` blocks
(by opening-line):

1082, 1102, 1110, 1121, 1128, 1137, 1152, 1163, 1180, 1195, 1201, 1211, 1217, 1228, 1237,
1246, 1257, 1267, 1277, 1286, 1304, 1315, 1333

That is 23 scala blocks in the Charts section (plus the 1061 `expect=skipped` domain block).
Of those 23, exactly 6 reference removed AxisConfig setters (see section 2 below) and must be
rewritten by P13 (or by P12, which may optionally do the README edits -- P13 re-runs doctest
as the gate regardless).

---

## 2. README doctest blocks affected by the P12 REMOVAL (side/setters/labelAllBands)

P12 removes `.left`, `.right`, `.bottom`, `.top` from `AxisConfig`. Six README lines reference
these setters inside doctest blocks and will STOP COMPILING after P12 lands. These are the R-2
lines from `03a-open-resolutions.md`.

The following grep was run against the CURRENT worktree (pre-P12):

```
rg -n "\.left|\.right|\.top|\.bottom|yScale" kyo-ui/README.md
```

Results (full output):

```
1088:        .yAxis(_.left.grid.ticks(5).format(v => f"$$$v%,.0f"))
1089:        .legend(_.top)
1167:        .yScale(_.linear(0.0, 80000.0))
1168:        .xAxis(_.bottom.label("Month"))
1169:        .yAxis(_.left.grid.ticks(5).format(v => f"$$$v%,.0f"))
1170:        .legend(_.top)
1186:        .yAxis(_.left.label("Revenue"))
1187:        .yAxisRight(_.right.label("Upper bound"))
1215:        ... (prose, not in a block)
1221:        ... (yScale in prose)
1284:        ... (margins.left in prose)
1290:        .yScale(_.linear(0.0, 80000.0).withNice(true)...)
1291:        .xAxis(_.bottom.rotateTicks(45).pad(8))
```

### 2A. Lines that ARE AxisConfig setters (must be rewritten -- 6 total)

| Line | Block opens at | Current text | After P12 | Notes |
|------|---------------|--------------|-----------|-------|
| 1088 | 1082 | `.yAxis(_.left.grid.ticks(5).format(v => f"$$$v%,.0f"))` | `.yAxis(_.grid.ticks(5).format(v => f"$$$v%,.0f"))` | AxisConfig `.left` removed |
| 1168 | 1163 | `.xAxis(_.bottom.label("Month"))` | `.xAxis(_.label("Month"))` | AxisConfig `.bottom` removed |
| 1169 | 1163 | `.yAxis(_.left.grid.ticks(5).format(v => f"$$$v%,.0f"))` | `.yAxis(_.grid.ticks(5).format(v => f"$$$v%,.0f"))` | AxisConfig `.left` removed |
| 1186 | 1180 | `.yAxis(_.left.label("Revenue"))` | `.yAxis(_.label("Revenue"))` | AxisConfig `.left` removed |
| 1187 | 1180 | `.yAxisRight(_.right.label("Upper bound"))` | `.yAxisRight(_.label("Upper bound"))` | AxisConfig `.right` removed |
| 1291 | 1286 | `.xAxis(_.bottom.rotateTicks(45).pad(8))` | `.xAxis(_.rotateTicks(45).pad(8))` | AxisConfig `.bottom` removed |

Total: 6 blocks require a line-level chain-start edit. The edit is mechanical: drop the
`.left`/`.right`/`.bottom` prefix from the lambda chain. The remaining setters (`.grid`,
`.ticks`, `.format`, `.label`, `.rotateTicks`, `.pad`) all survive and are unchanged.

### 2B. Lines that are NOT AxisConfig setters (DO NOT TOUCH)

- Line 1089: `.legend(_.top)` -- this is `LegendConfig.top`, NOT `AxisConfig.top`. DO NOT TOUCH.
- Line 1170: `.legend(_.top)` -- same. DO NOT TOUCH.
- Line 1284: `.margins(_.left(80))` -- this is `Margins.left`, NOT `AxisConfig`. DO NOT TOUCH.
- Lines 1167, 1290: `.yScale(...)` -- `ChartSpec.yScale`, completely unrelated to AxisConfig side.
  DO NOT TOUCH.

### 2C. `labelAllBands` in the README

No README line references `labelAllBands`. Confirmed by grep (zero hits). No README change
needed for this removal.

---

## 3. P11 `yScaleRight` -- README consideration

### 3A. Current "Two axes" example (block at line 1180)

Current state (lines 1180-1189):

```scala
val twoAxis: Svg.Root =
    UI.chart(sales)(
        UI.mark.bar(x = _.month, y = _.revenue),
        UI.mark.line(x = _.month, y = _.hi, axis = Axis.Right)
    )
        .yAxis(_.left.label("Revenue"))       // line 1186 -- MUST drop .left (P12 edit)
        .yAxisRight(_.right.label("Upper bound"))  // line 1187 -- MUST drop .right (P12 edit)
        .toSvg
```

After the P12 setter removal (from section 2A), this block becomes:

```scala
val twoAxis: Svg.Root =
    UI.chart(sales)(
        UI.mark.bar(x = _.month, y = _.revenue),
        UI.mark.line(x = _.month, y = _.hi, axis = Axis.Right)
    )
        .yAxis(_.label("Revenue"))
        .yAxisRight(_.label("Upper bound"))
        .toSvg
```

### 3B. Adding `yScaleRight` to the dual-axis example (P11 public addition)

P11 adds `.yScaleRight(f: ScaleOverride => ScaleOverride): ChartSpec[A]`. The plan (05-plan.md
P13 section) says: "add/adjust a `yScaleRight` example if the Charts section demonstrates
dual-axis scales."

The "Two axes" section (line 1176) is exactly where a `yScaleRight` usage should appear, since it
teaches dual-axis behavior. After the P12 setter cleanup, the block can be extended with a
`yScaleRight` call to illustrate independent scale control. Suggested extended form:

```scala
val twoAxis: Svg.Root =
    UI.chart(sales)(
        UI.mark.bar(x = _.month, y = _.revenue),
        UI.mark.line(x = _.month, y = _.hi, axis = Axis.Right)
    )
        .yAxis(_.label("Revenue"))
        .yAxisRight(_.label("Upper bound"))
        .yScaleRight(_.linear(40000.0, 70000.0))
        .toSvg
```

The `.yScaleRight(_.linear(40000.0, 70000.0))` pins the right axis to a fixed domain (the `hi`
column ranges from 46000 to 64000 in the running `sales` dataset). This makes the doctest
deterministic without asserting on exact SVG output.

RE-CONFIRM AT IMPL TIME: after P11 commits, read the exact `yScaleRight` signature and
the running `sales` data to pick a domain that compiles and produces non-degenerate rendering.
The domain `[46000.0, 64000.0]` is safe (spans the min/max of `hi` in the two-row dataset).
`_.linear(40000.0, 70000.0)` is a padded version and is safe.

Alternatively, do NOT add `yScaleRight` to the "Two axes" block and instead add a separate
short example:

```scala
val dualScale: Svg.Root =
    UI.chart(sales)(
        UI.mark.bar(x = _.month, y = _.revenue),
        UI.mark.line(x = _.month, y = _.hi, axis = Axis.Right)
    )
        .yScale(_.linear(0.0, 100000.0))
        .yScaleRight(_.linear(40000.0, 70000.0))
        .toSvg
```

Either approach is acceptable. The plan requires "add/adjust a `yScaleRight` example." The
supervisor chooses which form at impl time.

---

## 4. Doctest mechanics: imports and scope

Every block is prefixed with `import kyo.*` (the global `doctestPredef`). The Charts section
declares its running domain in the `doctest:expect=skipped` block at line 1061:

```scala
enum Region derives CanEqual, Plottable: ...
opaque type Usd <: Double = Double; object Usd: ...
case class Sale(month: String, revenue: Usd, region: Region, lo: Usd, hi: Usd)
val sales: Chunk[Sale] = Chunk(...)
```

These bindings are visible to all subsequent blocks in the file. The Charts blocks use:
- `UI.chart`, `UI.mark.bar`, `UI.mark.line`, `UI.mark.rule`, etc. (from `import kyo.*`)
- `Svg.Root`, `Chunk`, `Maybe` (from `import kyo.*`)
- `sales`, `Sale`, `Region`, `Usd` (from the `expect=skipped` domain block)
- `Axis` (from `import kyo.*`, specifically `kyo.UI.Axis`)
- `ScaleOverride`, `Curve`, `Signal`, `Instant` (from `import kyo.*`)

No additional imports are needed in the Charts blocks. Any block that references `UI.mark` or
`Axis.Right` or `Axis` relies on `import kyo.*` which pulls in the `UI` object and its nested
types.

RE-CONFIRM AT IMPL TIME: after P11 adds `yScaleRight`, verify that `ScaleOverride` is accessible
via `import kyo.*` (it already is, since the existing `yScale` docs show `_.linear(...)` working
in README blocks without an explicit import). Confirmed: `ScaleOverride` is a nested type within
`UI.Ast` and is in scope via `import kyo.*`.

---

## 5. FINAL cross-platform CLEAN-BUILD gate

### 5A. Project IDs (confirmed)

From `build.sbt` line 1185-1232 (the `kyo-ui` crossProject definition):

```scala
crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .withoutSuffixFor(JVMPlatform)   // kyo-ui = JVM (no suffix)
```

This produces:
- `kyo-ui` = JVM project (withoutSuffix)
- `kyo-uiJS` = JS project
- `kyo-uiNative` = Native project

`kyo-uiJVM` does NOT exist. Confirmed from the steering folded-guides and from build.sbt structure.

### 5B. The stale-artifact false-green trap

Steering §53 (folded-guides): "a stale `.class`/`.sjsir`/`.tasty` can show a false green with zero
recompilation; the final cross-platform gate must clean-build." Steering §50 warns: "the prior
campaign hit exactly this with `UI$mark$.sjsir`." A prior campaign saw a passing `kyo-uiJS/test`
run that showed no "Compiling" log lines -- this means sbt picked up stale `.sjsir` from a prior
compilation of the OLD code, not the new code. The fix is a `clean` before the cross-platform
run.

A run where `sbt kyo-uiJS/test` produces NO "[info] Compiling" log lines for kyo-ui source files
is RED and should be treated as a false green. Abort, run `sbt kyo-uiJS/clean`, and retry.

### 5C. CLEAN-BUILD gate: exact ordered checklist

The supervisor runs these commands in this order, in a single sbt session to minimize re-compilation:

```
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

sbt 'clean' 'kyo-ui/doctest' 'kyo-ui/test' 'kyo-uiJS/test' 'kyo-uiNative/test'
```

Rationale for ordering:
1. `clean` -- removes ALL stale `.class`/`.sjsir`/`.tasty` across the whole build (global clean).
   This ensures no false green from prior partial compilation. The global `clean` is the correct
   scope; a module-scoped `kyo-ui/clean` would not clean JS/Native artifacts. Use global `clean`.
2. `kyo-ui/doctest` -- README block validation. Runs on JVM, using the JVM project's compiled
   classes. Must come before the full test suite to surface README compilation failures early.
3. `kyo-ui/test` -- full JVM test suite including all unit and integration tests.
4. `kyo-uiJS/test` -- full JS test suite. Requires Scala.js link. Expect long build times.
5. `kyo-uiNative/test` -- full Native test suite. Requires Scala Native link. Expect long times.

Alternative split into separate sbt invocations (if the single-session form is impractical):

```
sbt clean
sbt 'kyo-ui/doctest'
sbt 'kyo-ui/test'
sbt 'kyo-uiJS/test'
sbt 'kyo-uiNative/test'
```

All five must be green before P13 is considered verified.

### 5D. Expected build times

- `kyo-ui/doctest`: moderate (compiles 91 blocks sequentially in a forked JVM; roughly 3-7 min).
- `kyo-ui/test`: the JVM test suite forks one JVM per test class (per-suite forking in build.sbt)
  and drives Chrome for browser-integration tests. Expect 10-30 min depending on machine.
- `kyo-uiJS/test`: Scala.js link + Node.js test run. Expect 15-40 min.
- `kyo-uiNative/test`: Scala Native link + native test run. Expect 20-60 min.

Total gate time from a cold clean: expect 60-120 min. Do NOT abort early on "slow progress";
long Scala Native link times are normal.

---

## 6. P13 acceptance checklist

The supervisor runs each check in order. ALL must pass before P13 is committed.

### Checklist

- [ ] **README setter-removal edits applied.** All 6 lines (1088, 1168, 1169, 1186, 1187, 1291)
  rewritten as documented in section 2A. `.legend(_.top)` at 1089/1170 is UNTOUCHED. `.margins`
  is UNTOUCHED.

- [ ] **README `yScaleRight` example present.** The "Two axes" section (line 1176) or an adjacent
  block demonstrates `.yScaleRight(...)`. RE-CONFIRM AT IMPL TIME that P11 has committed and
  `yScaleRight` compiles before adding this example.

- [ ] **`sbt kyo-ui/doctest` green.** All 91 fenced scala blocks compile. Zero block failures.
  The doctest run shows "Compiling" log lines (not a cache-hit no-op run). If zero "Compiling"
  lines appear, the cache is stale -- run `sbt kyo-ui/doctestClean` then re-run.

- [ ] **`sbt kyo-ui/test` green.** Full JVM test suite green, including all regression guards
  added in P1-P12 (L1-L24, all co-pins). Any failure is root-fixed here, not skipped.

- [ ] **`sbt kyo-uiJS/test` green from CLEAN.** The clean in step 5C was performed before this
  run. The output contains "[info] Compiling" lines for kyo-ui Scala.js sources. Zero test
  failures.

- [ ] **`sbt kyo-uiNative/test` green from CLEAN.** Same clean guarantee. Zero test failures.

- [ ] **No `pending`/TODO/FIXME introduced by the campaign.** Run:
  ```
  rg -n "pending|TODO|FIXME" \
    kyo-ui/shared/src/main/scala/kyo/ \
    kyo-ui/shared/src/test/scala/kyo/ \
    kyo-ui/README.md
  ```
  Zero results expected. Any hit from the campaign's NEW code is a blocker. Pre-existing hits in
  files untouched by the campaign are out of scope, but confirm by cross-checking with `git diff
  --name-only` to see which files the campaign modified.

- [ ] **kyo-ui/CONTRIBUTING.md decision (supervisor action, not auto-create).** Steering §54:
  "create `kyo-ui/CONTRIBUTING.md` only if the campaign establishes module-wide invariants."
  This campaign does establish module-wide conventions (color-resolution pattern, axis-config
  placement model, cross-platform gate). The supervisor must decide whether to create it. The
  P13 implementer does NOT create it automatically. Supervisor decision point: if YES, note the
  conventions to document (mirror pattern for colorScale, band-centering formula, `resolveYScale`
  for both axes, `applyBarChannels` for static+animated bar parity, `withHighlight` call pattern).

- [ ] **Git status clean.** `git status` shows no unintended untracked files. The only changes
  committed are: `kyo-ui/README.md` and the plan file `phases/phase-13/prep.md`.

---

## 7. Traps and guardrails

### T-1. False green from stale artifacts (most critical trap)

If `sbt kyo-uiJS/test` or `sbt kyo-uiNative/test` shows NO "[info] Compiling" lines for kyo-ui
source files, the run is a stale-cache false green. Abort, run `sbt clean`, and re-run. This is
the exact failure mode documented in the prior campaign (the `UI$mark$.sjsir` incident, steering
folded-guides §49).

### T-2. Doctest failures = compile errors from P12 removal surfacing

If any doctest block fails with a "value left is not a member of AxisConfig" (or similar "not a
member" error for `.left`/`.right`/`.bottom`/`.top`), P12's REMOVAL has not been applied to that
README block. Fix the block per section 2A. Do NOT revert P12; the compile error is correct
behavior after the removal. Fix the doctest block.

### T-3. LegendConfig vs AxisConfig disambiguation (must not touch LegendConfig)

`LegendConfig` has its own `.top`/`.bottom`/`.left`/`.right` setters (UI.scala ~line 2547). These
are COMPLETELY SEPARATE from the removed `AxisConfig` setters. Lines 1089 and 1170 use
`LegendConfig.top`. Lines inside `ChartLowerTest` at 1433/1449/1466 use `.legend(_.right/
.bottom/.left)`. None of these are affected by P12 or P13. DO NOT TOUCH them.

### T-4. README edits may already be done by P12

The P12 prep (section 1D of that prep doc) documents the same six README line edits and says
"P12 MUST leave the README compiling." If the P12 implementer applies the README edits during P12,
P13 still runs `sbt kyo-ui/doctest` as its gate, but finds the edits already in place. In that
case P13's README work is limited to the `yScaleRight` example addition and verifying the doctest
is green. RE-CONFIRM AT IMPL TIME: check README lines 1088/1168/1169/1186/1187/1291 before
editing; if already updated by P12, do not re-apply.

### T-5. Line number drift after P11/P12 commits

P11 adds new fields to `ChartSpec` (in UI.scala) and new tests in `ChartAxisTest.scala`. P12
edits UI.scala (deletes the `Side` enum + AxisConfig fields/setters) and many test files. The
README line numbers may shift slightly if any prior phase inserted or deleted lines in the README
(P12 REMOVES lines from the README if it drops the solo `.xAxis(_.bottom)` or similar calls in
any doc example). Always re-read the README after P12 commits to confirm which lines contain the
six affected chains. Use context-string matching (the builder chain content), not raw line number,
as the anchor.

### T-6. `kyo-ui/doctest` is JVM-only; JS/Native blocks with `doctest:platform=js` are separate

The `doctest:platform=js` annotated blocks in the README (lines 1363, 1383, 1437, 1452, 1468,
1484, 1557) are NOT run by `kyo-ui/doctest`. They are outside the Charts section and test UI
interactivity features. P13 has no obligation to run them; the `kyo-ui/test` suite (which
includes JS tests) covers those paths.

### T-7. No `pending` or TODO/FIXME in newly introduced code

The campaign introduced approximately 24 reproduce-before-fix leaves (L1-L24) plus co-pin guards.
If any prior phase left a `pending` test or a FIXME comment as a placeholder, P13's grep
(checklist item) will surface it. Resolve each before committing.

---

## 8. 3-line summary

1. `kyo-ui/README.md` (1624 lines) is the only file changed in P13. Exactly **6 doctest blocks**
   need the AxisConfig-setter chain-start rewrite (lines 1088, 1168, 1169, 1186, 1187, 1291 --
   drop `.left`/`.right`/`.bottom` prefix, keep all chained setters). The "Two axes" block (line
   1180) should gain a `.yScaleRight(...)` call after P11 commits. RE-CONFIRM BOTH at impl time.

2. Cross-platform project IDs confirmed: `kyo-ui` (JVM, no suffix), `kyo-uiJS`, `kyo-uiNative`
   (`kyo-uiJVM` does NOT exist). Run `sbt kyo-ui/doctest` for README blocks, then all three
   test suites.

3. The FINAL clean-build command is:
   `sbt 'clean' 'kyo-ui/doctest' 'kyo-ui/test' 'kyo-uiJS/test' 'kyo-uiNative/test'`
   -- the global `clean` is mandatory (not module-scoped) to avoid stale `.sjsir`/`.tasty` false
   green; any run showing zero "Compiling" log lines for kyo-ui sources must be discarded and
   re-run cold.
