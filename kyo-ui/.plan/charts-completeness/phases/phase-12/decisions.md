# Phase P12 decisions

## D-01: Side enum deleted (GAP-AXISCONFIG-SIDE, Q-3 YES)

The `Side` enum (3 lines at UI.scala:469-471) and the four `AxisConfig` setter methods
(`left`, `right`, `bottom`, `top` -- lines 2527-2530) were deleted. The `side: Maybe[Side]`
field (first positional of `AxisConfig`) was also deleted. Confirmed zero non-setter references
to `Side` in ChartLower.scala, internal packages, or any test body except ChartSpecTest:92-94,
which was itself rewritten.

Post-removal, `.xAxis(_.top)` / `.yAxis(_.left)` etc. are now type errors, not silent no-ops.
This is the desired end state: the compile error converts the silent defect into a build failure.

No "fails-to-compile" unit test was written (cannot test that in the same source set). The
conversion from silent no-op to compile error is documented here as the regression guard.

## D-02: labelAllBands field deleted (GAP-LABELALLBANDS, Q-2 YES)

The `labelAllBands: Boolean = true  // D18` field (trailing defaulted field) was deleted from
`AxisConfig`. It was never read in ChartLower.scala or any scale/internal code. Because it was
a trailing field with a default value, removing it does NOT change `AxisConfig.default` arity
for the non-default positional slots; only `default` literal itself was updated (dropped the
leading `Absent` for the deleted `side` field, but `labelAllBands` had a default value so it
did not appear in the `default` literal at all).

The L23 regression guard test "a 7-category band x-axis produces 7 tick labels" retains its
`assert(labels.size == 7, ...)` assertion unchanged. Only the test name string was corrected
(dropped the "(labelAllBands default)" parenthetical). Band labeling is unaffected because the
axis renders all labels by default through the grid-tick path.

## D-03: AxisConfig.default arity updated

Before: `AxisConfig(Absent, Absent, false, 5, Absent)`
        positional: side=Absent, axisLabel=Absent, showGrid=false, tickCount=5, tickFormat=Absent

After:  `AxisConfig(Absent, false, 5, Absent)`
        positional: axisLabel=Absent, showGrid=false, tickCount=5, tickFormat=Absent

The `ChartSpec` factory at UI.scala:938-939 uses `AxisConfig.default` (not positional
constructor), so it is unaffected. No other site constructs `AxisConfig` positionally.

## D-04: False scaladoc corrected

The scaladoc at UI.scala was updated:
- The type-doc example was changed from `_.left.grid.ticks(5).format(...)` to
  `_.grid.ticks(5).format(...)`.
- The false sentence "`side` selects where the axis line and labels are drawn." was deleted.
- The sentence "Builder methods ... write `_.left.grid.ticks(5).format(...)`." was updated
  to "Builder methods ... write `_.grid.ticks(5).format(...)`."
- The `axisLabel` description lines were preserved; the `side` description line was removed.

## D-05: Call-site counts actually rewritten

| Bucket | Count | Notes |
|--------|-------|-------|
| A. API definitions (UI.scala) | 10 edits | Side enum 3 lines, scaladoc 2 edits, side field, labelAllBands field, 4 setters, default literal |
| B. Demo call sites | 31 | ChartFeatureGallery(12), ChartGalleryShot(11), ChartReactiveScales(6), LiveDashboard(8 changed, see below), LinkedSelection(3) |
| C. Test call sites | 39 | ChartSpecTest(1 block rewrite), ChartAxisTest(25 sites), ChartLowerTest(9 sites), plus 1 extra P11 site at ChartAxisTest:1524 not in prep |
| D. README doctest blocks | 6 | All 6 prep-listed lines rewritten |
| **Total call sites (B+C+D)** | **76** | +1 vs prep's 75 due to P11 addition at ChartAxisTest:1524 |

LiveDashboard count: prep listed 8 AxisConfig sites but only 7 distinct lines appear
(lines 307/308, 319/320, 336/337, 351/352 = 8 lines; confirms 8 rewrites, matching prep).

The extra site (ChartAxisTest:1524) was introduced by P11: `.yAxis(_.left).yAxisRight(_.right)`
at the end of the L12a test. Both were solo setters (no chained setter), so the entire
`.yAxis(_.left).yAxisRight(_.right)` suffix was dropped.

## D-06: Method-based placement co-pin confirmation

The following existing tests continue to verify that axis placement is determined by which
method is called (.xAxis/.yAxis/.yAxisRight), not by the deleted `side` field:

- ChartAxisTest "yAxis(_.grid.ticks(3)) produces 3 gridline Lines..." -- left y-axis ticks at
  TextAnchor.End (left of PlotX), gridlines spanning full plot width.
- ChartAxisTest dual-axis test (line ~283) -- left tick labels are left of PlotX; right tick
  labels are right of PlotX+PlotWTwoAx. Neither reads `side`.
- ChartAxisTest "yAxis(_.log) produces log-spaced ticks" (line ~187) -- left y-axis ticks at
  TextAnchor.End; no `side` field involved.

These tests do not access `spec.yAxisCfg.side` and are driven entirely by the method called.
They remain unchanged through P12 and stay green, confirming placement still works correctly.

## D-07: LegendConfig disambiguation confirmed

All `.top/.bottom/.left/.right` references inside `.legend(_....)` lambdas were confirmed to
be `LegendConfig` setters and were NOT touched. Specifically:
- ChartFeatureGallery:105, 116 -- `.legend(_.top)` -- LegendConfig.top
- ChartGalleryShot:83, 104 -- `.legend(_.top)` -- LegendConfig.top
- LiveDashboard:322, 337 -- `.legend(_.top.colorScale {...})` -- LegendConfig.top
- ChartAxisTest:569, 614 -- `.legend(_.top.colorScale {...})` -- LegendConfig.top
- ChartReactiveTest:256 -- `.legend(_.top.colorScale {...})` -- LegendConfig.top
- ChartSpecTest:484 -- `.margins(_.left(120.0))` -- Margins setter

The `LegendPosition` enum (UI.scala: `enum LegendPosition: case Top, Bottom, Left, Right`)
was not touched.

## D-08: Full test run results (impl-test-jvm-001.log)

`sbt 'kyo-ui/test'` completed: 90 suites, 0 aborted.

- Total tests run: 1802
- Succeeded: 1784
- Failed: 18
- Canceled: 0, ignored: 0, pending: 0

ALL 18 failures are pre-existing browser-infrastructure flakiness (Chrome DevTools), NOT
chart-related and NOT caused by P12. They are in 7 test classes:
HtmlRendererTest, ReactiveTest, RealtimeScenarioItTest, UISessionItTest, DomStyleSheetTest,
FocusableTest, AttrsTest. Error types: kyo.Timeout (1-minute), kyo.Interrupted (cascade after
a sibling timed out), BrowserConnectionLostException, BrowserElementNotFoundException. Telltale
durations of 40s-1min mark the Chrome WebSocket disconnect; the millisecond failures are
interrupt cascades from the timed-out sibling in the same suite.

Confirmation these are unrelated to P12:
- None reference AxisConfig/yAxis/xAxis/labelAllBands/Side (grep -L confirmed for
  HtmlRendererTest.scala and ReactiveTest.scala).
- They are all browser-driven (Browser.assertText, Browser.click, DevTools WebSocket).
- The session began with a pre-existing kyo-core AsyncTest.scala modification addressing
  interrupt/race flakiness, confirming the environment has known async/browser-test flakiness.

ALL chart test suites passed clean (no FAILED): ChartSpecTest, ChartTypedValuesTest,
ChartScalesTest, ChartReactiveTest, ChartInteractionTest, ChartAxisTest, ChartLowerTest.

L23/L24 regression guards confirmed green:
- "a 7-category band x-axis produces 7 tick labels" (2ms) PASS
- "yAxis(_.grid.ticks(5)) sets showGrid true and tickCount 5" (1ms) PASS

Method-based placement co-pins confirmed green:
- "yAxis(_.grid.ticks(3)) produces 3 gridline Lines and 3 tick Text labels..." (71ms) PASS
- "xAxis(_.grid) emits vertical gridlines at each x tick..." (1ms) PASS
- "yAxis(_.reverse) swaps the y range..." (2ms) PASS

Compilation: `sbt kyo-ui/compile` clean (demos + tests + main all compile). The only warnings
are 3 pre-existing pattern-match-exhaustivity warnings in ChartLower.scala (2202, 3190, 3216),
unrelated to P12.

Post-removal sweep (rg over all kyo-ui scala): zero missed AxisConfig sites. All remaining
.left/.right/.top/.bottom hits are LegendConfig (`_.top.colorScale`), Margins (`m.left`/`m.right`/
`m.top`/`m.bottom`, `leftAxisMargin`), or comments (ChartAxisTest:1319 "Side-default anchor"
comment, deliberately untouched per prep). Zero `Side` enum refs and zero `labelAllBands` refs
remain (the only `Side` token is the untouched internal-logic comment).
