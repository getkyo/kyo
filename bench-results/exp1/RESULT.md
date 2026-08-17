# Experiment 1: the continuationBodiesFuse regression is an inlining-budget effect

First measurement of the night, and the first time this regression has been explained with evidence
rather than argued from source. Run on an otherwise idle machine, no agents, heap and collector
pinned (`-Xms4g -Xmx4g -XX:+UseG1GC`), `-f 2 -wi 10 -i 5`, 10 measured iterations per cell.

## The four numbers

| | default | `-XX:FreqInlineSize=600` |
|---|---|---|
| old design `36b41336fb` | 26.261 ± 0.575 us/op | 24.058 ± 0.473 |
| current design `d85ee6821f` | 27.455 ± 0.336 | 22.678 ± 0.251 |

    default:  current is +4.55% against old      (reproduces the known +4.3%)
    freq600:  current is -5.74% against old      (the sign flips)
    raising the budget helps old by -8.39%, current by -17.40%

## What it says

**The regression reproduces**, independently and at the expected size. That alone was worth the run:
the +4.3% was a stored number from an earlier session, and it is real.

**Raising the inlining budget does not merely close the gap, it reverses it.** At 600 the current
design is faster than the old one. So the current design is not slower; it is slower *only while its
delivery path cannot inline*.

**The budget helps the current design twice as much**, which is the shape you expect when one design
has a large body sitting just above the threshold and the other does not.

## The mechanism, from the efficacy gate

The protocol requires proving the flag took effect before reading any of the above, because a flag
that silently does nothing refutes every hypothesis and passes. Comparing C2 inline verdicts between
the two configurations of the current design: 57 methods changed, and size-related C2 refusals fell
from 31 to 20. The flag took.

The decisive line:

    ProtoKernelBench::run$56  (379 bytes)
      default:  0 inlined / 10 refused   'hot method too big'
      freq600:  4 inlined /  7 refused

`run$56` is the fused continuation body, ten chained maps. At **379 bytes** it sits above HotSpot's
default `FreqInlineSize` of 325 and below 600. So under the default budget it can never inline at a
hot site, and under 600 it can.

This is exactly what a static analysis predicted before any measurement: this row is the only one of
the fifteen whose continuation body is itself too big to inline, so it alone cannot repay the cost of
the delivery frame with downstream inlining. Six other rows moved to the same delivery path and got
faster.

## What this does NOT establish

- **`FreqInlineSize=600` is a diagnostic, not a fix.** It is global, it changes inlining everywhere,
  and nobody should ship it. It was used to answer one question and it answered it.
- **One row, one configuration per cell, no replication.** The effects here (4.55%, 17.40%) are large
  against their errors (~1-2%), but this session did not run the C V C V C shape, so the harness's own
  threshold machinery was not applied. Treat the sizes as approximate and the direction as solid.
- **It does not choose between the fix candidates.** DIS-1 (split dispatch into an inlined degenerate
  tier and an out-of-line general tier) and DIS-2 (Transform-first delivery, taking the entry under
  the budget) both aim to make the default budget sufficient. This experiment says the budget is the
  binding constraint; it does not say which change relieves it. That is Experiment 2.
- **The other fourteen rows are untouched by this result.**

## What changes because of it

The candidate ranking should shift toward anything that reduces the size of what must inline on this
path, and away from the frame-cost framing. The frame is real, but at a sufficient budget the current
design wins outright, which is hard to reconcile with the frame being the dominant cost.
