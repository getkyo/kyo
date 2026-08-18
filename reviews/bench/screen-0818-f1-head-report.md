[90mCompiling project (Scala 3.8.4, JVM (25))[0m
[90mCompiled project (Scala 3.8.4, JVM (25))[0m
[90mCompiling project (Scala 3.8.4, JVM (25))[0m
[90mCompiled project (Scala 3.8.4, JVM (25))[0m
One control leg against one variant, so the bound below is the legs' own error and not a threshold estimated from replicates. Pass every leg of a bracket to get its real verdict.


==============================================================================
⛔ NOT A VALID MEASUREMENT: 1 leg(s) did not reach steady state.
The rows below are printed in full, and none of their verdicts can be trusted: a score that
mixes warm and cold code is not a measurement of the code. Re-run with more warmup.
  - handleLoopAnswersInPlace (variant) never settled: iterations 189.1, 174.6, 173.0, 171.0, 179.4
==============================================================================
Control `35d4cbdba0` (kernel) against variant `35d4cbdba0` (proto).
JMH -f 1, no threshold was estimated, so this is diagnostic and not a claim, all 15 rows, timing only, so no movement here is attributed, drift 4.0% assumed, not measured.
Markers control  | variant 

| | row | mode | cnt | control | variant | delta | resolves | B/op delta | mechanism |
|---|---|---|---|---|---|---|---|---|---|
| 🟢 | `evalFixedOverhead` | avgt | 5 | 0.007449 ± 0.000275 | 0.003990 ± 0.000048 | -46.4% | ±4.0% | - | **none found** |
| 🔴 | `fusionAllocatesNothing` | avgt | 5 | 0.5563 ± 0.007527 | 0.5852 ± 0.002547 | +5.2% | ±4.0% | - | **none found** |
| 🔴 | `deepRecursionPaysRescuesOnly` | avgt | 5 | 50.37 ± 0.2507 | 57.55 ± 1.35 | +14.2% | ±4.0% | - | **none found** |
| 🔴 | `continuationBodiesFuse` | avgt | 5 | 28.45 ± 0.3467 | 32.94 ± 0.4575 | +15.8% | ±4.0% | - | **none found** |
| 🔴 | `idleHandlerAddsNothing` | avgt | 5 | 34.38 ± 0.3162 | 44.90 ± 0.9287 | +30.6% | ±4.0% | - | **none found** |
| 🔴 | `fusionPastBudgetPaysRescuesOnly` | avgt | 5 | 33.76 ± 0.2414 | 44.65 ± 0.8614 | +32.3% | ±4.0% | - | **none found** |
| 🔴 | `uncachedValuesPayBoxingOnly` | avgt | 5 | 34.33 ± 0.4072 | 47.29 ± 1.04 | +37.7% | ±4.0% | - | **none found** |
| 🔴 | `nestedPayloadsUnwrapInMaps` | avgt | 5 | 5.92 ± 0.1087 | 8.39 ± 0.0426 | +41.7% | ±4.0% | - | **none found** |
| 🔴 | `emittingClausesPayRegionRebuild` | avgt | 5 | 79.82 ± 1.17 | 113.7 ± 10.72 | +42.4% | ±9.4% | - | **none found** |
| 🔴 | `statefulAnswersPaySuccessor` | avgt | 5 | 116.8 ± 1.22 | 166.6 ± 7.14 | +42.6% | ±4.3% | - | **none found** |
| 🔴 | `suspensionFusesContinuation` | avgt | 5 | 40.47 ± 1.23 | 58.03 ± 2.84 | +43.4% | ±4.9% | - | **none found** |
| 🔴 | `trailingMapsStayLinear` | avgt | 5 | 294.3 ± 5.94 | 490.3 ± 19.24 | +66.6% | ±4.0% | - | **none found** |
| 🔴 | `suspensionBaseline` | avgt | 5 | 88.65 ± 1.78 | 159.8 ± 2.79 | +80.2% | ±4.0% | - | **none found** |
| 🔴 | `handleLoopAnswersInPlace` | avgt | 5 | 88.77 ± 2.23 | 177.4 ± 27.86 | +99.9% | ±15.7% | - | **none found** |
⚠️  A leg's first measured iteration sits far from the rest, so it was still warming up and its score includes that ramp:
  - handleLoopAnswersInPlace (variant) first iteration is 8% from the median of the rest
Every flat row below is flat to within +-15.70%, the floor set by the legs' own reported error. These legs were not replicated, so this bounds the result without estimating the spread; a replicated bracket would give a real threshold and this does not.
⚠️  Same sha and no recorded JVM arguments on either leg, so nothing here says what was varied. Either this is an A/A, or it was measured before the harness recorded configuration and the difference is unrecoverable.
🔴 Regressed, so the work is unfinished until each is diagnosed or ruled on:
  - fusionAllocatesNothing +5.2%
  - deepRecursionPaysRescuesOnly +14.2%
  - continuationBodiesFuse +15.8%
  - idleHandlerAddsNothing +30.6%
  - fusionPastBudgetPaysRescuesOnly +32.3%
  - uncachedValuesPayBoxingOnly +37.7%
  - nestedPayloadsUnwrapInMaps +41.7%
  - emittingClausesPayRegionRebuild +42.4%
  - statefulAnswersPaySuccessor +42.6%
  - suspensionFusesContinuation +43.4%
  - trailingMapsStayLinear +66.6%
  - suspensionBaseline +80.2%
  - handleLoopAnswersInPlace +99.9%
⚠️  Moved with nothing in the evidence behind it, so the cause is not known yet:
  - evalFixedOverhead: check allocation sites and the inlining log before proposing a mechanism
  - fusionAllocatesNothing: check allocation sites and the inlining log before proposing a mechanism
  - deepRecursionPaysRescuesOnly: check allocation sites and the inlining log before proposing a mechanism
  - continuationBodiesFuse: check allocation sites and the inlining log before proposing a mechanism
  - idleHandlerAddsNothing: check allocation sites and the inlining log before proposing a mechanism
  - fusionPastBudgetPaysRescuesOnly: check allocation sites and the inlining log before proposing a mechanism
  - uncachedValuesPayBoxingOnly: check allocation sites and the inlining log before proposing a mechanism
  - nestedPayloadsUnwrapInMaps: check allocation sites and the inlining log before proposing a mechanism
  - emittingClausesPayRegionRebuild: check allocation sites and the inlining log before proposing a mechanism
  - statefulAnswersPaySuccessor: check allocation sites and the inlining log before proposing a mechanism
  - suspensionFusesContinuation: check allocation sites and the inlining log before proposing a mechanism
  - trailingMapsStayLinear: check allocation sites and the inlining log before proposing a mechanism
  - suspensionBaseline: check allocation sites and the inlining log before proposing a mechanism
  - handleLoopAnswersInPlace: check allocation sites and the inlining log before proposing a mechanism
⚠️  This change both wins and loses. Those are two diagnoses, not one tradeoff: the loss usually turns out removable, and accepting it early ships a defect the same afternoon's work would have deleted.
⛔ 1 leg(s) did not reach steady state; the verdicts above are not readable
