# Tier-split variant 3, and the end of DIS-1

Base against variant 3, bracketed C V C V C on three rows chosen to test the specific hypothesis.
Variant 3 finds the stack index once and hands it to whichever tier runs; variants 1 and 2 looked it
up twice on a fast-path miss, which was the suspected cause of their 22-35% handler regressions.

## Result

    ⚪ trailingMapsStayLinear     318.28 -> 309.77   -2.7%   +24 B/op
    ⚪ continuationBodiesFuse      28.55 ->  28.35   -0.7%    -0 B/op
    🔴 handleLoopAnswersInPlace    90.92 -> 101.09  +11.2%    +0 B/op

**The hypothesis was half right.** Removing the double lookup halves the handler regression, from
+21.7% and +22.6% in variants 1 and 2 to +11.2%. So the second stack scan was a real cost and not all
of it.

**And the candidate fails anyway.** `continuationBodiesFuse` is flat at -0.7%: variant 3 does not
deliver the win that forcing the whole method inline delivers (-6.8%, replicated). It avoids the
allocation cliff, +24 B/op rather than +240,000, so it is strictly better than the crude fix on that
axis, and it still costs 11% on a handler row while buying nothing on the target.

## The conclusion for DIS-1

All three constructible forms of the tier split fail:

| variant | continuationBodiesFuse | handler rows | allocation |
|---|---|---|---|
| 1, tiered at both arms | -3.2% | +22 to +35% | clean |
| 2, tiered at SuspendWith only | -1.9% | +23 to +33% | clean |
| 3, index found once | -0.7% (flat) | +11.2% | clean |
| crude: force the whole method inline | **-6.8%** (replicated) | improves them | **+240,000 B/op** |

The win comes from inlining the whole 607-byte body, and a small inlined fast path does not
approximate it. The thing that helps the target row is exactly the thing that breaks
`trailingMapsStayLinear`'s escape analysis, and shrinking what gets inlined shrinks the win with it.

DIS-1 as specified is **refuted**, by measurement, in three forms. What remains open is whether some
other shape recovers the win without enlarging the compilation unit; nothing measured here suggests
one, and proposing another without a mechanism would be guessing.

## A tool defect this run exposed

The blocker fired on `26.5, 27.3, 27.2, 27.3, 27.2`, a settled series. `startBias` compared `score`
against the row's own iterations, and after deltas began carrying replicate means in `score`, that
compared a five-leg mean to one leg's iterations and read the gap between legs as a warmup ramp. It
now measures the row's own iterations against themselves. Fixed, with this series as a fixture.
