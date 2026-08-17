# Experiment 3: the fix is not free, and the one-row result did not generalize

Full 15-row sweep of the current design, `dispatch$1` forced to inline against default, one leg each,
`-f 1 -wi 10 -i 5`, heap and collector pinned, idle machine.

This is the run that stops a 24% regression from shipping as a fix.

## Every row

| row | default | forced | delta | B/op |
|---|---|---|---|---|
| emittingClausesPayRegionRebuild | 81.120 | 73.189 | -9.78% | same |
| handleLoopAnswersInPlace | 85.915 | 78.023 | **-9.19%** | same |
| suspensionBaseline | 85.809 | 78.875 | **-8.08%** | same |
| handleLoopFusesContinuation | 83.139 | 78.540 | **-5.53%** | same |
| continuationBodiesFuse | 26.829 | 25.521 | **-4.87%** | same |
| fusionPastBudgetPaysRescuesOnly | 33.649 | 33.255 | -1.17% | same |
| eight rows between -1% and +2% | | | within error | same |
| **trailingMapsStayLinear** | **293.694** | **363.580** | **+23.80%** | **+239,976 B/op** |

Bold rows are outside their combined error. Ten of fifteen are within it.

## What it establishes

**The win is broader than one row.** Four rows beyond `continuationBodiesFuse` improve by 5 to 9%,
all of them suspension and handler rows, which is consistent: they share the delivery path the frame
sits on. That strengthens DIS-1 well beyond the row that motivated it.

**The loss is real and it is allocation.** Fourteen rows have byte-identical `gc.alloc.rate.norm`,
to the tenth of a byte. `trailingMapsStayLinear` alone gains 239,976 B/op, 10.34% more than it
allocated before. Allocation here is exact and per-operation, not sampled, so unlike the timing this
is not a question of noise: inlining `dispatch$1` causes that row to allocate more.

The likely reading, and it is a reading rather than a measurement: enlarging the compilation unit
defeats an escape analysis that was scalar-replacing something on this row. The kernel's own history
records exactly this shape ("escape analysis dies, eager rows went 0.0 to 81.2 and 164.7 B/op"). It
is not established here and the isolation that would establish it is `-XX:-EliminateAllocations` or
`-XX:+PrintEliminateAllocations`, which has not been run.

## What this means for the candidate

DIS-1 in its crude form, make the dispatch inline, is **not a free win**. It buys 5 to 9% on four
rows and costs 24% and 10% more allocation on one. Per the skill's own rule, a change that both wins
and loses is two diagnoses and not one tradeoff, and the loss usually turns out removable.

So the candidate's real content matters more than ever: DIS-1 proposes splitting dispatch into a
small inlined degenerate tier and an out-of-line general tier. That is *not* what was measured here.
Forcing the whole 607-byte method to inline is the crude version, and the crude version is what
regressed `trailingMapsStayLinear`. A tier split might deliver the gains without enlarging the unit
enough to break escape analysis, which is precisely the thing to measure next.

## Limits

- One leg per configuration. No replication, so the harness's threshold machinery was not applied.
  The four wins and the one loss are outside their combined errors; the ten middle rows are not
  distinguishable from noise and should not be read as small effects.
- `trailingMapsStayLinear` carries a large relative error (±23.81 and ±28.42, roughly 8%), so its
  +23.80% is outside combined error but not by a wide margin. **Its allocation delta is not subject
  to that caveat** and is the load-bearing evidence.
- The allocation mechanism is inferred, not measured. Saying "escape analysis" without running
  `-XX:+PrintEliminateAllocations` would be exactly the unmeasured mechanism claim this harness
  exists to prevent.
