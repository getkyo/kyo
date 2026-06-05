# Phase P12 prep — Remove dead AxisConfig knobs

## Status: READ-ONLY. No source modifications. No commit.

---

## 0. Purpose and scope

P12 is a REMOVAL-only phase. It closes two dead-knob gaps by deleting public surface that has zero
reads and (for `side`) carries a FALSE scaladoc claim. Both removals land together because they
touch the same `AxisConfig` case class and the same `AxisConfig.default` literal; separating them
would require two partial edits of the same declaration.

Gaps closed: GAP-AXISCONFIG-SIDE (Q-3 REMOVE, approved YES) + GAP-LABELALLBANDS (Q-2 REMOVE,
approved YES).

Leaves delivered: L24 (Side/setters/enum removal + false scaladoc gone) + L23 (labelAllBands
removal + 7-label render assertion retained).

No rendered output changes. The compile error on `.xAxis(_.top)` after removal is the desired end
state.

---

## 1. Complete call-site inventory

### 1A. API definitions to DELETE (all in UI.scala, current state confirmed by read 2026-06-05)

All line numbers are confirmed against the current worktree
(`kyo-ui/shared/src/main/scala/kyo/UI.scala`, 2879 lines).

| Line(s) | Element | Action |
|---------|---------|--------|
| 469-471 | `enum Side derives CanEqual: case Left, Right, Top, Bottom` | DELETE all 3 lines (scaladoc at 469, keyword at 470, cases at 471) |
| 2487 | Type-doc example `_.left.grid.ticks(5).format(...)` | REWRITE to `_.grid.ticks(5).format(...)` |
| 2489 | FALSE scaladoc sentence `` `side` selects where the axis line and labels are drawn. `` | DELETE this sentence only; keep the rest of the scaladoc (2490-2493) |
| 2495 | `side: Maybe[Side],` | DELETE this field (first positional field of `AxisConfig`) |
| 2504 | `labelAllBands: Boolean = true               // D18` | DELETE this field |
| 2506 | `def left: AxisConfig  = copy(side = Present(Side.Left))` | DELETE |
| 2507 | `def right: AxisConfig = copy(side = Present(Side.Right))` | DELETE |
| 2508 | `def bottom: AxisConfig = copy(side = Present(Side.Bottom))` | DELETE |
| 2509 | `def top: AxisConfig   = copy(side = Present(Side.Top))` | DELETE |
| 2522 | `val default: AxisConfig = AxisConfig(Absent, Absent, false, 5, Absent)` | REWRITE (see section 2) |

**Confirmed: `labelAllBands` has ZERO reads in LOWER/SCALE/tests. `side` has ZERO reads in
LOWER/SCALE. The only non-definition references are the four setters (deleted) and
ChartSpecTest:92-94 (rewritten below).**

**Confirmed: `Side` enum is referenced ONLY by the four setters and the `side` field; no `Side.`
reference exists in ChartLower.scala, internal/ packages, or any test body outside ChartSpecTest.**

**Confirmed: `labelAllBands` scaladoc: the type doc at 2483-2493 does NOT mention `labelAllBands`
by name. No scaladoc sentence requires editing for this field beyond the deletion of the field
itself.**

### 1B. Demo call sites (path:line, before -> after)

Demo files live under `kyo-ui/shared/src/test/scala/demo/`. All are pure chain-start rewrites:
drop the leading `.left`/`.right`/`.bottom`/`.top` from the lambda; keep all subsequent setters.
`.xAxis(_.bottom)` / `.xAxis(_.bottom.format(...))` drops the `.bottom` prefix (positional bottom
is default); `.yAxis(_.left)` becomes `.yAxis(identity)` -> actually just remove the call OR keep
as `.yAxis(_.grid)` if grid was also intended. See note on `.yAxis(_.left)` solo calls below.

**Note on solo `.yAxis(_.left)` / `.yAxisRight(_.right)` calls:** when `.left` or `.right` is the
ONLY setter (no chained `.grid`/`.ticks`/etc.), the entire `.yAxis(_.left)` call is a no-op
wrapper that sets `side` (dead) and nothing else. After removal the lambda `_.left` no longer
compiles. The call can be DROPPED (the axis still renders from `xAxisCfg`/`yAxisCfg` default) OR
kept as `.yAxis(identity)` which is a valid no-op. **Dropping is cleaner and matches intent: the
axis renders positionally with its defaults.** Use the same pattern as `.xAxis(_.bottom)` -> drop.

**ChartFeatureGallery.scala** (`kyo-ui/shared/src/test/scala/demo/ChartFeatureGallery.scala`)

| Line | Before | After |
|------|--------|-------|
| 71 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 72 | `.xAxis(_.bottom)` | DROP (positional default) |
| 84 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 85 | `.xAxis(_.bottom)` | DROP |
| 96 | `.yAxis(_.left.ticks(4))` | `.yAxis(_.ticks(4))` |
| 97 | `.xAxis(_.bottom)` | DROP |
| 107 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 108 | `.xAxis(_.bottom)` | DROP |
| 119 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 120 | `.xAxis(_.bottom)` | DROP |
| 132 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 133 | `.xAxis(_.bottom)` | DROP |

**ChartGalleryShot.scala** (`kyo-ui/shared/src/test/scala/demo/ChartGalleryShot.scala`)

| Line | Before | After |
|------|--------|-------|
| 81 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 82 | `.xAxis(_.bottom.label("Month"))` | `.xAxis(_.label("Month"))` |
| 93 | `.yAxis(_.left.grid)` | `.yAxis(_.grid)` |
| 94 | `.xAxis(_.bottom)` | DROP |
| 104 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 105 | `.xAxis(_.bottom)` | DROP |
| 117 | `.yAxis(_.left.label("Revenue"))` | `.yAxis(_.label("Revenue"))` |
| 118 | `.yAxisRight(_.right.label("Growth %"))` | `.yAxisRight(_.label("Growth %"))` |
| 119 | `.xAxis(_.bottom)` | DROP |
| 129 | `.yAxis(_.left.grid)` | `.yAxis(_.grid)` |
| 130 | `.xAxis(_.bottom)` | DROP |
| 140 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 141 | `.xAxis(_.bottom)` | DROP |

**ChartReactiveScales.scala** (`kyo-ui/shared/src/test/scala/demo/ChartReactiveScales.scala`)

| Line | Before | After |
|------|--------|-------|
| 93 | `.yAxis(_.left.grid.ticks(5))` | `.yAxis(_.grid.ticks(5))` |
| 94 | `.xAxis(_.bottom)` | DROP |
| 106 | `.yAxis(_.left.grid.ticks(5))` | `.yAxis(_.grid.ticks(5))` |
| 107 | `.xAxis(_.bottom)` | DROP |
| 175 | `.yAxis(_.left.grid.ticks(5))` | `.yAxis(_.grid.ticks(5))` |
| 176 | `.xAxis(_.bottom)` | DROP |

**LiveDashboard.scala** (`kyo-ui/shared/src/test/scala/demo/LiveDashboard.scala`)

Note: lines 323 and 339 are `.legend(_.top.colorScale {...})` which is `LegendConfig.top`
(position), NOT `AxisConfig.top`. DO NOT TOUCH those lines.

| Line | Before | After |
|------|--------|-------|
| 307 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 308 | `.xAxis(_.bottom)` | DROP |
| 319 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 320 | `.xAxis(_.bottom.format(timeAxisLabel))` | `.xAxis(_.format(timeAxisLabel))` |
| 336 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 337 | `.xAxis(_.bottom)` | DROP |
| 351 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 352 | `.xAxis(_.bottom.format(timeAxisLabel))` | `.xAxis(_.format(timeAxisLabel))` |

**LinkedSelection.scala** (`kyo-ui/shared/src/test/scala/demo/LinkedSelection.scala`)

| Line | Before | After |
|------|--------|-------|
| 58 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |
| 84 | `.xAxis(_.bottom)` | DROP |
| 85 | `.yAxis(_.left.grid.ticks(4))` | `.yAxis(_.grid.ticks(4))` |

**Demo bucket total: 31 call sites** (19 `.left`/`.right` to drop prefix + 12 `.xAxis(_.bottom)`
to drop entirely).

### 1C. Test call sites (kyo/ package)

All are in `kyo-ui/shared/src/test/scala/kyo/`. These are NOT test-weakening rewrites -- the
assertions are all about rendered output (gridlines, tick pixels, colors, bar rects), not about
`side`. The chain-start removal is mechanical; every assertion stays intact.

**IMPORTANT disambiguation:**
- Lines mentioning `.legend(_.top/.bottom/.left/.right)` use `LegendConfig` setters: NOT affected.
- Lines mentioning `.legend(_.top.colorScale {...})` use `LegendConfig.top`: NOT affected.
- `StyleTest.scala:393/395` use `Style.TextAlign.left/right`: NOT affected.
- ChartLowerTest:1433/1449/1466 use `.legend(_.right/.bottom/.left)`: NOT affected.
- ChartAxisTest:572/617 and ChartReactiveTest:256 use `.legend(_.top.colorScale {...})`: NOT affected.

**ChartSpecTest.scala** (`kyo-ui/shared/src/test/scala/kyo/ChartSpecTest.scala`)

Special case: this test actively reads `spec.yAxisCfg.side` and uses `Side.Left`. The entire test
block at 90-97 must be REWRITTEN (not just the chain-start), per DESIGN §GAP-AXISCONFIG-SIDE.

| Line | Before | After |
|------|--------|-------|
| 90 | `"yAxis(_.left.grid.ticks(5)) sets side Left, grid true, tickCount 5" in {` | `"yAxis(_.grid.ticks(5)) sets showGrid true and tickCount 5" in {` |
| 91 | `val spec = UI.chart(sales)(bar(x = _.month, y = _.revenue)).yAxis(_.left.grid.ticks(5))` | `val spec = UI.chart(sales)(bar(x = _.month, y = _.revenue)).yAxis(_.grid.ticks(5))` |
| 92-94 | `spec.yAxisCfg.side match` / `case Present(Side.Left) => succeed` / `case other => fail(...)` | DELETE these 3 lines. `side` field and `Side` enum are gone. Comment: `// side field removed (dead/false knob per design §GAP-AXISCONFIG-SIDE)` |
| 95-96 | `assert(spec.yAxisCfg.showGrid == true)` / `assert(spec.yAxisCfg.tickCount == 5)` | KEEP UNCHANGED -- these are the surviving real-behavior assertions |

**ChartAxisTest.scala** (`kyo-ui/shared/src/test/scala/kyo/ChartAxisTest.scala`)

| Line | Before | After |
|------|--------|-------|
| 86 (test name string) | `"yAxis(_.left.grid.ticks(3)) produces 3 gridline Lines..."` | `"yAxis(_.grid.ticks(3)) produces 3 gridline Lines..."` |
| 93 | `.yAxis(_.left.grid.ticks(3))` | `.yAxis(_.grid.ticks(3))` |
| 194 | `.yAxis(_.left)` | DROP the `.yAxis(...)` call entirely (solo no-op after removal) |
| 281 | `.yAxis(_.left)` | DROP |
| 282 | `.yAxisRight(_.right)` | DROP |
| 325 | `.yAxis(_.left.label("Revenue"))` | `.yAxis(_.label("Revenue"))` |
| 326 | `.yAxisRight(_.right.label("Growth %"))` | `.yAxisRight(_.label("Growth %"))` |
| 327 | `.xAxis(_.bottom)` | DROP |
| 392 | `.yAxis(_.left.label("Revenue"))` | `.yAxis(_.label("Revenue"))` |
| 393 | `.yAxisRight(_.right.label("Growth %"))` | `.yAxisRight(_.label("Growth %"))` |
| 394 | `.xAxis(_.bottom)` | DROP |
| 661 | `.yAxis(_.left.ticks(3).format(v => s"$$${v.toInt}"))` | `.yAxis(_.ticks(3).format(v => s"$$${v.toInt}"))` |
| 902 | `.yAxis(_.left.grid)` | `.yAxis(_.grid)` |
| 924 | `.yAxis(_.left.grid.ticks(3))` | `.yAxis(_.grid.ticks(3))` |
| 1001 | `.xAxis(_.bottom.grid)` | `.xAxis(_.grid)` |
| 1017 | `.yAxis(_.left)` | DROP |
| 1018 | `.yAxis(_.left.reverse)` | `.yAxis(_.reverse)` |
| 1068 (test name string) | `"a 7-category band x-axis produces 7 tick labels (labelAllBands default)"` | `"a 7-category band x-axis produces 7 tick labels"` |
| 1150 | `.yAxis(_.left.label("Revenue"))` | `.yAxis(_.label("Revenue"))` |
| 1151 | `.xAxis(_.bottom)` | DROP |
| 1206 | `.yAxis(_.left.grid)` | `.yAxis(_.grid)` |
| 1207 | `.xAxis(_.bottom)` | DROP |
| 1244 | `.yAxis(_.left.label("Revenue"))` | `.yAxis(_.label("Revenue"))` |
| 1245 | `.yAxisRight(_.right.label("Growth %"))` | `.yAxisRight(_.label("Growth %"))` |
| 1246 | `.xAxis(_.bottom)` | DROP |

Note: ChartAxisTest:1068 test body asserts `labels.size == 7` -- the 7-label assertion is
UNCHANGED (L23 regression guard). Only the name string is corrected.

Note: ChartAxisTest:1325 comment mentions "Side-default anchor (End for left, Start for right)" --
this is a comment explaining how `buildYAxis` works internally. The comment remains valid (the
internal rendering logic is unchanged; P8 handles the side-default anchor). Do NOT touch it.

**ChartLowerTest.scala** (`kyo-ui/shared/src/test/scala/kyo/ChartLowerTest.scala`)

| Line | Before | After |
|------|--------|-------|
| 618 | `.yAxis(_.left)` | DROP |
| 619 | `.yAxisRight(_.right)` | DROP |
| 676 | `.yAxis(_.left.ticks(3))` | `.yAxis(_.ticks(3))` |
| 677 | `.xAxis(_.bottom.label("Month"))` | `.xAxis(_.label("Month"))` |
| 1706 | `.yAxis(_.left.grid)` | `.yAxis(_.grid)` |
| 1715 | `.yAxis(_.left)` | DROP |
| 1729 | `.yAxis(_.left.grid)` | `.yAxis(_.grid)` |
| 1742 | `.yAxis(_.left.grid)` | `.yAxis(_.grid)` |
| 1743 | `.yAxis(_.left.grid)` | `.yAxis(_.grid)` |

**Test bucket total: 38 call sites** across ChartSpecTest (1 rewrite + 1 line-block delete),
ChartAxisTest (24 sites), ChartLowerTest (9 sites).

### 1D. README doctest blocks

File: `kyo-ui/README.md`. All six lines confirmed against current worktree. The `.legend(_.top)`
at lines 1089/1170 is `LegendConfig.top` -- DO NOT TOUCH. The `.margins(_.left(80))` in examples
is the `Margins` setter -- DO NOT TOUCH.

| Line | Before | After |
|------|--------|-------|
| 1088 | `.yAxis(_.left.grid.ticks(5).format(v => f"$$$v%,.0f"))` | `.yAxis(_.grid.ticks(5).format(v => f"$$$v%,.0f"))` |
| 1168 | `.xAxis(_.bottom.label("Month"))` | `.xAxis(_.label("Month"))` |
| 1169 | `.yAxis(_.left.grid.ticks(5).format(v => f"$$$v%,.0f"))` | `.yAxis(_.grid.ticks(5).format(v => f"$$$v%,.0f"))` |
| 1186 | `.yAxis(_.left.label("Revenue"))` | `.yAxis(_.label("Revenue"))` |
| 1187 | `.yAxisRight(_.right.label("Upper bound"))` | `.yAxisRight(_.label("Upper bound"))` |
| 1291 | `.xAxis(_.bottom.rotateTicks(45).pad(8))` | `.xAxis(_.rotateTicks(45).pad(8))` |

**README bucket total: 6 call sites.**

### Summary counts

| Bucket | Count |
|--------|-------|
| A. API definitions to delete/rewrite (UI.scala) | 10 edits |
| B. Demo call sites | 31 |
| C. Test call sites | 38 |
| D. README doctest blocks | 6 |
| **Total call sites (B+C+D)** | **75** |

**Count reconciliation vs the ~84 estimate:** The design estimate of ~84 included a broad count
across the full file; the precise grep-confirmed count is 75 distinct lines that contain a
chain-start removal. Some earlier-phase edits (P8 in particular introduced some new tests with
`.yAxis(_.left...)` chains as part of its phase; re-read current state of all test files after P8
commits before editing). The 75 count is the current state.

**IMPORTANT pre-edit action:** Before touching any file, run:
```
git log --oneline -5
```
and re-read `ChartAxisTest.scala` and `ChartLowerTest.scala` to confirm lines have not shifted
from P8/P11 commits. The line numbers above are from the current worktree (pre-P8 through P11).
After P8 and P11 commit (they also edit ChartAxisTest.scala and ChartLower.scala), line numbers
will shift. Always grep-anchor before editing.

---

## 2. Exact AxisConfig case-class edit

### Current state (UI.scala:2483-2522, confirmed)

```scala
/** Configures axis appearance for one axis.
  *
  * Builder methods return a copy with one field changed, so chains compose
  * without mutation. Used as the argument to `.xAxis(f)` / `.yAxis(f)` /
  * `.yAxisRight(f)`: write `_.left.grid.ticks(5).format(...)`.
  *
  * `side` selects where the axis line and labels are drawn. `axisLabel` is an
  * optional axis label string. `showGrid` enables gridlines across the plot.
  * `tickCount` is the desired number of ticks (a hint, not a hard limit).
  * `tickFormat` overrides the default tick label formatter.
  */
final case class AxisConfig(
    side: Maybe[Side],           // <-- DELETE THIS FIELD (first positional)
    axisLabel: Maybe[String],
    showGrid: Boolean,
    tickCount: Int,
    tickFormat: Maybe[Double => String],
    tickRotation: Double = 0.0,                 // D17
    tickAnchor: TextAnchor = TextAnchor.Middle, // D17
    reversed: Boolean = false,                  // D20
    padding: Double = 0.0,                      // D21
    labelAllBands: Boolean = true               // D18  <-- DELETE THIS FIELD
):
    def left: AxisConfig  = copy(side = Present(Side.Left))   // DELETE
    def right: AxisConfig = copy(side = Present(Side.Right))  // DELETE
    def bottom: AxisConfig = copy(side = Present(Side.Bottom)) // DELETE
    def top: AxisConfig   = copy(side = Present(Side.Top))    // DELETE
    def label(s: String): AxisConfig             = copy(axisLabel = Present(s))
    def grid: AxisConfig                         = copy(showGrid = true)
    def ticks(n: Int): AxisConfig                = copy(tickCount = n)
    def format(f: Double => String): AxisConfig  = copy(tickFormat = Present(f))
    def reverse: AxisConfig                      = copy(reversed = true)
    def pad(fraction: Double): AxisConfig        = copy(padding = fraction)
    def rotateTicks(degrees: Double): AxisConfig = copy(tickRotation = degrees)
    def anchor(a: TextAnchor): AxisConfig        = copy(tickAnchor = a)

end AxisConfig

object AxisConfig:
    val default: AxisConfig = AxisConfig(Absent, Absent, false, 5, Absent)
    //                                   ^^^^^  <- this is `side: Absent` -- DROP IT after removal
```

### Target state (after P12)

```scala
/** Configures axis appearance for one axis.
  *
  * Builder methods return a copy with one field changed, so chains compose
  * without mutation. Used as the argument to `.xAxis(f)` / `.yAxis(f)` /
  * `.yAxisRight(f)`: write `_.grid.ticks(5).format(...)`.
  *
  * `axisLabel` is an optional axis label string. `showGrid` enables gridlines
  * across the plot. `tickCount` is the desired number of ticks (a hint, not a
  * hard limit). `tickFormat` overrides the default tick label formatter.
  */
final case class AxisConfig(
    axisLabel: Maybe[String],
    showGrid: Boolean,
    tickCount: Int,
    tickFormat: Maybe[Double => String],
    tickRotation: Double = 0.0,                 // D17
    tickAnchor: TextAnchor = TextAnchor.Middle, // D17
    reversed: Boolean = false,                  // D20
    padding: Double = 0.0,                      // D21
):
    def label(s: String): AxisConfig             = copy(axisLabel = Present(s))
    def grid: AxisConfig                         = copy(showGrid = true)
    def ticks(n: Int): AxisConfig                = copy(tickCount = n)
    def format(f: Double => String): AxisConfig  = copy(tickFormat = Present(f))
    def reverse: AxisConfig                      = copy(reversed = true)
    def pad(fraction: Double): AxisConfig        = copy(padding = fraction)
    def rotateTicks(degrees: Double): AxisConfig = copy(tickRotation = degrees)
    def anchor(a: TextAnchor): AxisConfig        = copy(tickAnchor = a)

end AxisConfig

object AxisConfig:
    val default: AxisConfig = AxisConfig(Absent, false, 5, Absent)
```

### AxisConfig.default arity: BEFORE -> AFTER

```
BEFORE: AxisConfig(Absent, Absent, false, 5, Absent)
        positional: side=Absent, axisLabel=Absent, showGrid=false, tickCount=5, tickFormat=Absent

AFTER:  AxisConfig(Absent, false, 5, Absent)
        positional: axisLabel=Absent, showGrid=false, tickCount=5, tickFormat=Absent
```

The ONLY site that constructs `AxisConfig` positionally is the `default` literal in `object
AxisConfig`. The `ChartSpec` factory at UI.scala:942-944 uses `AxisConfig.default`, not the
positional constructor. No other `.copy(...)` or positional `AxisConfig(...)` exists (verified:
`rg "AxisConfig(" kyo-ui` returns only the definition site and the `default` literal).

### Side enum deletion

```
DELETE lines 469-471 of UI.scala:
    /** The side on which an axis is drawn. Used inside `UI.Ast.AxisConfig`. */
    enum Side derives CanEqual:
        case Left, Right, Top, Bottom
```

`Side` has ZERO non-definition references after the four setters are deleted. No `derives`
clause, no `match`, no `import Side._`, no LOWER/SCALE reference confirmed.

### Scaladoc edits

Line 2487 (type-doc example): change `_.left.grid.ticks(5).format(...)` to
`_.grid.ticks(5).format(...)`.

Line 2489 (FALSE sentence): delete the sentence `` `side` selects where the axis line and labels
are drawn. `` The remaining sentences (2490-2493) are unchanged and accurate.

---

## 3. Reproduce-before-fix (compile guards, not render guards)

P12 is a REMOVAL phase. There is no failing render test to write first. The "reproduce" step is:

**Step 1 (pre-removal check -- optional, documents the problem):** confirm `.xAxis(_.top)` and
`.yAxis(_.left)` currently COMPILE with no error (the silent no-op is the existing defect).

**Step 2 (post-removal check -- the desired end state):** after the deletions, the entire module
MUST compile green. Any call site that was not rewritten WILL produce a compile error. This
compile-error is the desired anti-regression (`.xAxis(_.top)` etc. must FAIL to compile).

**Step 3 (regression guards -- must stay green):**

L23 guard: `ChartAxisTest` test "a 7-category band x-axis produces 7 tick labels" -- 7-label
assertion unchanged and stays green. Confirms band labeling is unaffected by the `labelAllBands`
removal (it was never read; the axis renders all labels by default through the grid-tick path).

L24 guard: `ChartSpecTest` test "yAxis(_.grid.ticks(5)) sets showGrid true and tickCount 5" --
`showGrid == true` and `tickCount == 5` assertions unchanged and stay green.

**Existing tests that co-pin axis PLACEMENT (positional, method-based, unaffected by P12):**

The following tests verify that left/right/x axis placement is determined by which method is
called (`.xAxis` / `.yAxis` / `.yAxisRight`), NOT by the `side` field. They are unaffected by the
removal and serve as implicit regression guards that placement still works:

- ChartAxisTest: "yAxis(_.grid.ticks(3)) produces 3 gridline Lines..." -- left y-axis ticks at
  `TextAnchor.End` (left of PlotX), gridlines spanning PlotX to PlotX+PlotW.
- ChartAxisTest: tests around line 281-310 (dual-axis) -- left tick labels are left of PlotX;
  right tick labels are right of PlotX+PlotW.
- ChartAxisTest: "yScale(_.log) produces log-spaced ticks" (line 187) -- left y-axis ticks at
  TextAnchor.End; no `side` field involved.

These tests do not read `spec.yAxisCfg.side` and are entirely driven by which method the user
calls (`xAxis`/`yAxis`/`yAxisRight`). They remain unchanged through P12.

---

## 4. Verification command

```
sbt 'kyo-ui/test'
```

This runs the full JVM aggregate (targeted per-phase gate). The rewrite touches demo/ files (which
compile as part of the test source set) and kyo/ test files. Running the full aggregate is
required per the plan's P12 specification: "sbt 'kyo-ui/test' (the whole JVM aggregate, since
the rewrite touches many test files)."

README doctests are P13's gate (`sbt kyo-ui/doctest`), but P12 MUST leave the README compiling
(all six doctest blocks rewritten in 1D above). If the README rewrite is done during P12, run
`sbt kyo-ui/doctest` to validate -- it will also be run again in P13.

**Do NOT run the cross-platform gate here.** That is P13's responsibility.

---

## 5. Traps and guardrails

**T-1. DO NOT weaken any test assertion.** Every rewrite in 1C is a chain-start edit only. The
assertions (gridline counts, tick pixel values, fill colors, rect coordinates) are UNCHANGED.
`ChartAxisTest:1068` -- the 7-label assertion `assert(labels.size == 7, ...)` stays intact;
only the test name string changes.

**T-2. `.top` in `LegendConfig` is NOT the same as `AxisConfig.top`.** The `LegendConfig`
setters (`.top`, `.bottom`, `.left`, `.right`) at UI.scala:~2547 are entirely unrelated and
MUST NOT be touched. Before editing any `.top/.bottom/.left/.right` removal, confirm the
surrounding context is an `xAxis(_....)` / `yAxis(_....)` / `yAxisRight(_....)` lambda.
Specifically: ChartAxisTest:572, 617 and ChartReactiveTest:256 use `.legend(_.top.colorScale
{...})` -- LegendConfig, DO NOT TOUCH. ChartLowerTest:1433/1449/1466 use `.legend(_.right/
.bottom/.left)` -- LegendConfig, DO NOT TOUCH. LiveDashboard:323 and 339 use `.legend(
_.top.colorScale {...})` -- LegendConfig, DO NOT TOUCH.

**T-3. `.margins(_.left(...))` in README and tests is the `Margins` setter, not `AxisConfig`.**
Do not touch README 1089, 1170, ChartSpecTest:486, or any `.margins(...)` call.

**T-4. Re-read files after P8/P11 commit before editing.** P8 edits `ChartAxisTest.scala`
(adds L14/L15/L16/L17 tests) and `ChartLower.scala`. P11 edits `ChartAxisTest.scala` (adds
L11/L12/L13/L19 tests) and `UI.scala`. Line numbers in ChartAxisTest.scala WILL have shifted
from what is documented here. Always anchor by test-name or context string, not raw line number,
when making edits.

**T-5. Confirm AxisConfig.default arity before editing.** Before editing the `default` literal,
read the current line to confirm it still matches `AxisConfig(Absent, Absent, false, 5, Absent)`.
If P11 has added fields to `ChartSpec` (it does -- `yScaleRightOverride`) or `AxisConfig.default`
has been touched, adjust accordingly. P11 does NOT change `AxisConfig` itself (it adds a new
`ChartSpec` field), so `default` should be unchanged.

**T-6. ChartSpecTest:90-97 is a COMPLETE block rewrite, not a line-by-line edit.** The test
name, the chain-start, the `side match` block (3 lines) -- all change together. The two
`assert(...)` lines at the end are KEPT. See 1C section above for the exact before/after.

**T-7. Solo `.yAxis(_.left)` / `.yAxisRight(_.right)` calls:** when `.left` or `.right` is the
ONLY content of the lambda (no chained setter), the entire `.yAxis(_.left)` call becomes
`.yAxis(identity)` which is a no-op. Drop the whole call instead (the axis still renders
correctly from `xAxisCfg`/`yAxisCfg` defaults). ChartAxisTest:194, 281, 282, 1017 and
ChartLowerTest:618, 619, 1715 fall into this category.

**T-8. No new `pending` / TODO / FIXME may be introduced.** If a site cannot be cleanly rewritten,
that is a blocker to be resolved before committing.

---

## 3-line summary

**Confirmed total call-site count (B+C+D = 31+38+6 = 75)**, not 84; the design estimate was a
broad approximation. Exact per-file grep is the authoritative count.

**AxisConfig.default arity before -> after:** `AxisConfig(Absent, Absent, false, 5, Absent)` ->
`AxisConfig(Absent, false, 5, Absent)` (drops the leading `Absent` for the deleted `side` field;
`labelAllBands` removal does NOT change arity because it was a trailing defaulted field).

**Side enum non-setter references:** ZERO. `Side` is referenced exclusively by the `side` field
and the four setters in UI.scala; no LOWER/SCALE/test-body reference exists outside
ChartSpecTest:92-94 (which is itself rewritten in 1C). Deletion of the enum is safe immediately
after deleting the four setters and the field.
