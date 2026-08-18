Replicated over 3 control and 3 variant leg(s): the threshold below is estimated from the spread between them.

A/A null: clean, no control row classified against another control leg (14 rows).


==============================================================================
⛔ NOT A VALID MEASUREMENT: 1 leg(s) did not reach steady state.
The rows below are printed in full, and none of their verdicts can be trusted: a score that
mixes warm and cold code is not a measurement of the code. Re-run with more warmup.
  - emittingClausesPayRegionRebuild (variant leg 1 of 3) never settled: first iteration 368.4 then 149.5, 129.9, 83.5, 102.6 (184% ramp); the other 2 variant leg(s) of this row settled, so one JVM warmed late: re-run with more warmup, or look at what compiles late in it (-prof comp, LogCompilation)
==============================================================================
Control `9ad929fccc` (kyotrait-f1) against variant `9ad929fccc` (arrowtrait-f1).
JMH -f 1, all 14 rows, timing only, so no movement here is attributed, drift 4.0% assumed, not measured.
Markers control  | variant 

| | row | mode | cnt | control | variant | delta | resolves | B/op delta | mechanism |
|---|---|---|---|---|---|---|---|---|---|
| ⚪ | `statefulAnswersPaySuccessor` | avgt | 5 | 289.1 ± 85.69 | 205.8 ± 85.69 | -28.8% | ±148.5% | -0 | - |
| ⚪ | `suspensionFusesContinuation` | avgt | 5 | 51.15 ± 8.46 | 42.66 ± 8.46 | -16.6% | ±141.8% | -0 | - |
| ⚪ | `handleLoopAnswersInPlace` | avgt | 5 | 279.0 ± 75.01 | 239.1 ± 75.01 | -14.3% | ±185.2% | +7 | - |
| ⚪ | `nestedPayloadsUnwrapInMaps` | avgt | 5 | 8.37 ± 0.0536 | 8.38 ± 0.0536 | +0.2% | ±3.2% | +0 | - |
| ⚪ | `fusionAllocatesNothing` | avgt | 5 | 0.5884 ± 0.008067 | 0.5933 ± 0.008067 | +0.8% | ±11.6% | +0 | - |
| ⚪ | `trailingMapsStayLinear` | avgt | 5 | 578.8 ± 67.71 | 584.4 ± 67.71 | +1.0% | ±58.6% | +1 | - |
| ⚪ | `continuationBodiesFuse` | avgt | 5 | 33.73 ± 2.45 | 36.14 ± 2.45 | +7.2% | ±59.5% | +0 | - |
| ⚪ | `evalFixedOverhead` | avgt | 5 | 0.004027 ± 0.000220 | 0.004373 ± 0.000220 | +8.6% | ±50.8% | +0 | - |
| ⚪ | `deepRecursionPaysRescuesOnly` | avgt | 5 | 57.72 ± 8.81 | 65.69 ± 8.81 | +13.8% | ±118.9% | +0 | - |
| ⚪ | `suspensionBaseline` | avgt | 5 | 191.1 ± 14.42 | 218.5 ± 14.42 | +14.4% | ±48.8% | -4 | - |
| ⚪ | `fusionPastBudgetPaysRescuesOnly` | avgt | 5 | 41.68 ± 1.42 | 47.82 ± 1.42 | +14.7% | ±28.4% | +0 | - |
| ⚪ | `idleHandlerAddsNothing` | avgt | 5 | 41.81 ± 4.49 | 48.32 ± 4.49 | +15.6% | ±56.3% | +0 | - |
| ⚪ | `uncachedValuesPayBoxingOnly` | avgt | 5 | 44.44 ± 6.66 | 52.15 ± 6.66 | +17.4% | ±102.2% | +0 | - |
| ⚪ | `emittingClausesPayRegionRebuild` | avgt | 5 | 74.85 ± 35.96 | 108.1 ± 35.96 | +44.5% | ±266.7% | -24006 | - |
⚠️  A leg's first measured iteration sits far from the rest, so it was still warming up and its score includes that ramp:
  - suspensionFusesContinuation (control) first iteration is 35% from the median of the rest
  - handleLoopAnswersInPlace (variant) first iteration is 14% from the median of the rest
  - trailingMapsStayLinear (variant) first iteration is 21% from the median of the rest
  - continuationBodiesFuse (variant) first iteration is 18% from the median of the rest
  - idleHandlerAddsNothing (variant) first iteration is 26% from the median of the rest
  - emittingClausesPayRegionRebuild (variant) first iteration is 184% from the median of the rest
Every flat row below is flat to within its own resolution, at worst +-266.69% (alpha 0.00357 after correcting for 14 rows, df 4).
ℹ️  Allocation moved on rows whose timing did not resolve. Allocation is exact and per-operation, so this is a real change regardless of what the timing could or could not show:
  - handleLoopAnswersInPlace +7 B/op, timing -14.3% (Flat)
  - suspensionBaseline -4 B/op, timing +14.4% (Flat)
  - emittingClausesPayRegionRebuild -24006 B/op, timing +44.5% (Flat)
⚠️  Same sha and no recorded JVM arguments on either leg, so nothing here says what was varied. Either this is an A/A, or it was measured before the harness recorded configuration and the difference is unrecoverable.
🟢 No row regressed beyond the drift band, across the whole class.

Next experiments, each one run of a configuration this harness already issues:
  1. emittingClausesPayRegionRebuild rides scalar replacement
      re-run control with -XX:-EliminateAllocations
      if disabling escape analysis on the control reproduces the variant's -24006 B/op, the variant destroyed a scalar replacement rather than adding an allocation
  A falsifier whose flag did not take refutes nothing; the run reports that as inconclusive rather than as evidence against the hypothesis.
⛔ 1 reason(s) make these verdicts unreadable; see the banner above
