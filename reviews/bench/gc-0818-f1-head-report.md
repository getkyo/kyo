One control leg against one variant, so the bound below is the legs' own error and not a threshold estimated from replicates. Pass every leg of a bracket to get its real verdict.

Control `35d4cbdba0` (kernel-gc) against variant `35d4cbdba0` (proto-gc).
JMH -f 1, no threshold was estimated, so this is diagnostic and not a claim, 4 selected rows of 15, so this says nothing about the rest, timing only, so no movement here is attributed, drift 4.0% assumed, not measured.
Markers control  | variant 

| | row | mode | cnt | control | variant | delta | resolves | B/op delta | mechanism |
|---|---|---|---|---|---|---|---|---|---|
| 🟢 | `evalFixedOverhead` | avgt | 5 | 0.007378 ± 0.000097 | 0.004009 ± 0.000033 | -45.7% | ±4.0% | -0 | **none found** |
| 🔴 | `nestedPayloadsUnwrapInMaps` | avgt | 5 | 6.00 ± 0.2405 | 8.41 ± 0.0957 | +40.2% | ±4.0% | +24000 | allocation +24000 B/op |
| 🔴 | `suspensionBaseline` | avgt | 5 | 88.97 ± 0.4577 | 161.5 ± 2.22 | +81.5% | ±4.0% | +1 | **none found** |
| 🔴 | `handleLoopAnswersInPlace` | avgt | 5 | 88.76 ± 1.90 | 181.4 ± 14.48 | +104.3% | ±8.0% | +1 | **none found** |
Every flat row below is flat to within +-7.98%, the floor set by the legs' own reported error. These legs were not replicated, so this bounds the result without estimating the spread; a replicated bracket would give a real threshold and this does not.
⚠️  Same sha and no recorded JVM arguments on either leg, so nothing here says what was varied. Either this is an A/A, or it was measured before the harness recorded configuration and the difference is unrecoverable.
🔴 Regressed, so the work is unfinished until each is diagnosed or ruled on:
  - nestedPayloadsUnwrapInMaps +40.2%
  - suspensionBaseline +81.5%
  - handleLoopAnswersInPlace +104.3%
⚠️  Moved with nothing in the evidence behind it, so the cause is not known yet:
  - evalFixedOverhead: check allocation sites and the inlining log before proposing a mechanism
  - suspensionBaseline: check allocation sites and the inlining log before proposing a mechanism
  - handleLoopAnswersInPlace: check allocation sites and the inlining log before proposing a mechanism
⚠️  This change both wins and loses. Those are two diagnoses, not one tradeoff: the loss usually turns out removable, and accepting it early ships a defect the same afternoon's work would have deleted.

Next experiments, each one run of a configuration this harness already issues:
  1. nestedPayloadsUnwrapInMaps rides scalar replacement
      re-run control with -XX:-EliminateAllocations
      if disabling escape analysis on the control reproduces the variant's +24000 B/op, the variant destroyed a scalar replacement rather than adding an allocation
  A falsifier whose flag did not take refutes nothing; the run reports that as inconclusive rather than as evidence against the hypothesis.
