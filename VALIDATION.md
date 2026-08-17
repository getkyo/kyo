# Validating the tool against the manual work

Every conclusion this campaign reached was computed by hand in python, outside the harness built to
prevent exactly that. This is the three-way check: raw data, tool verdict, and the claim recorded at
the time. The raw data is the arbiter; two of three agreeing settles nothing.

## Outcome

**Every numeric claim survived. Several of the confidences did not.**

| claim | recorded | tool verdict | outcome |
|---|---|---|---|
| the regression | +4.55% | 🔴 +4.5%, "none found" | confirmed, but marginal: the floor is 4.0% |
| forcing dispatch$1 inline | -6.88% | 🟢 -6.9%, "none found" | confirmed |
| the sweep: 5 wins, 1 loss | 5 / 1 | blocked, then 4-5 wins on settled legs | see the correction chain below |
| allocation on the regressed row | +239,976 B/op | named, on that row only | confirmed |
| escape analysis off, timing | declined to claim | ⚪ flat, floor ±15.18% | my refusal was right |
| tier split v1 and v2 | regress handlers 22-35% | 🔴 confirmed, comparison **blocked** | confirmed but unreadable |

## What the tool found that I did not

**An unsettled leg I never saw.** `uncachedValuesPayBoxingOnly` in the tier-split variant reads
`36.9, 34.9, 34.7, 34.4, 34.0`: a clean monotone ramp. My python looked only at score and error and
had no way to see it. The tool now blocks on it.

**That `emittingClausesPayRegionRebuild` was never measurable.** Its control leg reads
`86.4, 80.8, 81.4, 78.5, 78.6` and reports ±15.2% of its own score, so it supports no verdict in
either direction. Every comparison built on that leg was blocked, which was three of the five
experiments.

**Re-measured at `-wi 25` the leg settles**, its error falls to ±2.97, the blocker clears, and the
row is a **-9.5% win**. So the correction chain ran: hand arithmetic said a win, the tool with the
error floor said flat, and a properly warmed leg says a win again. The original number was roughly
right; my intermediate correction was wrong; and the honest state in between was neither, it was
"unmeasurable, re-measure".

The distinction matters because the tool never said "flat". It **refused the comparison**. I read the
refusal as a verdict, which is the same error as computing one by hand: taking silence for an answer.

## What I found that the tool did not

**The floor.** `Bench.compare` had no bound at the legs' own error, so it classified that same -9.8%
as a win. My cruder screen withheld the verdict, by a wrong route: I required the legs' combined
error, which is far too strict in general and happened to be right here. Fixed; the tool now agrees.

## What neither of us had

**Steady-state detection from the iteration series.** Nothing was reading `rawData` at all. Getting
the criterion right took three attempts against real numbers, and the first two were wrong: a fixed
percentage cannot work, and an outlier test cannot either, since two real rows trip it at 2.43x and
2.53x with only one a genuine ramp. What separates them is how far the first iteration drags the mean
the verdict uses: 1.64% against 0.59%.

## The honest state of the campaign's conclusions

The mechanism result stands: `Eval$::dispatch$1` at 607 bytes fails to inline, forcing it inline
takes `continuationBodiesFuse` from +4.5% to -6.9%, the efficacy gate proved the flag took, and the
allocation cost on `trailingMapsStayLinear` is exactly the scalar replacement it destroys, measured
two independent ways to within 24 bytes of 2.5 million.

What is weaker than reported: the sweep's fifth "win" was never a win; three comparisons rest on a
control leg that never settled and are blocked until re-measured; and every one of these is a
single-leg comparison whose floor is the legs' own error rather than a threshold estimated from
replicates. None of that changes the direction of any result. It changes what may be claimed from it.

## Cost of the drift

Four experiments' verdicts had to be re-issued. No re-measurement was needed, because the raw json
survived, but it survived by luck: `.gitignore` carried a global `*.json` and none of it was
committed until the drift was caught.
