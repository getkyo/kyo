Replicated over 3 control and 3 variant leg(s): the threshold below is estimated from the spread between them.

A/A null: clean, no control row classified against another control leg (15 rows).


==============================================================================
⛔ NOT A VALID MEASUREMENT: 1 leg(s) did not reach steady state.
The rows below are printed in full, and none of their verdicts can be trusted: a score that
mixes warm and cold code is not a measurement of the code. Re-run with more warmup.
  - uncachedValuesPayBoxingOnly (variant leg 1 of 3) never settled: iterations 55.2, 48.5, 48.9, 49.6, 48.5
==============================================================================
Control `35d4cbdba0` (kernel-f1) against variant `35d4cbdba0` (proto-f1).
JMH -f 1, all 15 rows, timing only, so no movement here is attributed, drift 4.0% assumed, not measured.
Markers control  | variant 

| | row | mode | cnt | control | variant | delta | resolves | B/op delta | mechanism |
|---|---|---|---|---|---|---|---|---|---|
| 🟢 | `evalFixedOverhead` | avgt | 5 | 0.007443 ± 0.000020 | 0.004036 ± 0.000020 | -45.8% | ±2.8% | - | **none found** |
| 🔴 | `fusionAllocatesNothing` | avgt | 5 | 0.5597 ± 0.003960 | 0.5867 ± 0.003960 | +4.8% | ±4.0% | - | **none found** |
| ⚪ | `deepRecursionPaysRescuesOnly` | avgt | 5 | 51.74 ± 1.39 | 57.81 ± 1.39 | +11.7% | ±37.2% | - | - |
| 🔴 | `continuationBodiesFuse` | avgt | 5 | 28.53 ± 0.2437 | 33.53 ± 0.2437 | +17.5% | ±6.2% | - | **none found** |
| 🔴 | `idleHandlerAddsNothing` | avgt | 5 | 34.51 ± 0.3822 | 44.40 ± 0.3822 | +28.7% | ±5.7% | - | **none found** |
| 🔴 | `fusionPastBudgetPaysRescuesOnly` | avgt | 5 | 34.39 ± 0.4160 | 44.65 ± 0.4160 | +29.8% | ±6.2% | - | **none found** |
| 🔴 | `uncachedValuesPayBoxingOnly` | avgt | 5 | 34.22 ± 1.26 | 48.16 ± 1.26 | +40.7% | ±21.9% | - | **none found** |
| 🔴 | `nestedPayloadsUnwrapInMaps` | avgt | 5 | 5.94 ± 0.0422 | 8.38 ± 0.0422 | +41.1% | ±3.6% | - | **none found** |
| 🔴 | `suspensionFusesContinuation` | avgt | 5 | 40.57 ± 0.0520 | 57.49 ± 0.0520 | +41.7% | ±1.7% | - | **none found** |
| 🔴 | `emittingClausesPayRegionRebuild` | avgt | 5 | 80.24 ± 2.58 | 118.3 ± 2.58 | +47.5% | ±16.4% | - | **none found** |
| 🔴 | `statefulAnswersPaySuccessor` | avgt | 5 | 116.1 ± 2.35 | 172.7 ± 2.35 | +48.8% | ±10.3% | - | **none found** |
| 🔴 | `trailingMapsStayLinear` | avgt | 5 | 295.5 ± 27.76 | 518.6 ± 27.76 | +75.5% | ±53.7% | - | **none found** |
| 🔴 | `suspensionBaseline` | avgt | 5 | 88.45 ± 2.63 | 162.9 ± 2.63 | +84.2% | ±15.2% | - | **none found** |
| 🔴 | `handleLoopAnswersInPlace` | avgt | 5 | 88.65 ± 5.28 | 184.6 ± 5.28 | +108.3% | ±30.4% | - | **none found** |
⚠️  A leg's first measured iteration sits far from the rest, so it was still warming up and its score includes that ramp:
  - uncachedValuesPayBoxingOnly (variant) first iteration is 13% from the median of the rest
Every flat row below is flat to within its own resolution, at worst +-53.68% (alpha 0.00333 after correcting for 14 rows, df 4).
⚠️  Same sha and no recorded JVM arguments on either leg, so nothing here says what was varied. Either this is an A/A, or it was measured before the harness recorded configuration and the difference is unrecoverable.
🔴 Regressed, so the work is unfinished until each is diagnosed or ruled on:
  - fusionAllocatesNothing +4.8%
  - continuationBodiesFuse +17.5%
  - idleHandlerAddsNothing +28.7%
  - fusionPastBudgetPaysRescuesOnly +29.8%
  - uncachedValuesPayBoxingOnly +40.7%
  - nestedPayloadsUnwrapInMaps +41.1%
  - suspensionFusesContinuation +41.7%
  - emittingClausesPayRegionRebuild +47.5%
  - statefulAnswersPaySuccessor +48.8%
  - trailingMapsStayLinear +75.5%
  - suspensionBaseline +84.2%
  - handleLoopAnswersInPlace +108.3%
⚠️  Moved with nothing in the evidence behind it, so the cause is not known yet:
  - evalFixedOverhead: check allocation sites and the inlining log before proposing a mechanism
  - fusionAllocatesNothing: check allocation sites and the inlining log before proposing a mechanism
  - continuationBodiesFuse: check allocation sites and the inlining log before proposing a mechanism
  - idleHandlerAddsNothing: check allocation sites and the inlining log before proposing a mechanism
  - fusionPastBudgetPaysRescuesOnly: check allocation sites and the inlining log before proposing a mechanism
  - uncachedValuesPayBoxingOnly: check allocation sites and the inlining log before proposing a mechanism
  - nestedPayloadsUnwrapInMaps: check allocation sites and the inlining log before proposing a mechanism
  - suspensionFusesContinuation: check allocation sites and the inlining log before proposing a mechanism
  - emittingClausesPayRegionRebuild: check allocation sites and the inlining log before proposing a mechanism
  - statefulAnswersPaySuccessor: check allocation sites and the inlining log before proposing a mechanism
  - trailingMapsStayLinear: check allocation sites and the inlining log before proposing a mechanism
  - suspensionBaseline: check allocation sites and the inlining log before proposing a mechanism
  - handleLoopAnswersInPlace: check allocation sites and the inlining log before proposing a mechanism
⚠️  This change both wins and loses. Those are two diagnoses, not one tradeoff: the loss usually turns out removable, and accepting it early ships a defect the same afternoon's work would have deleted.
⛔ 1 reason(s) make these verdicts unreadable; see the banner above
