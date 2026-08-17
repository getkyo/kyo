# The sweep, replicated: three wins survive, two do not, and one byte confirms the loss

The original sweep ran **one leg per configuration** and reported five wins and one loss. This runs
five legs of the same two configurations, so these rows get a threshold for the first time. A/A null
clean across all 15 rows.

Both arms at `d85ee6821f`; the only difference, now recorded in the runs themselves rather than lost:

    -XX:CompileCommandFile=.../forced-inline.cmd
    inline kyo/kernel/proto/Eval$.dispatch$1

## The three-way table

Raw data is the arbiter. The tool-verdict column is `BenchCompare` over the stored run ids.

| row | recorded claim (1 leg) | tool verdict (5 legs) | classification |
|---|---|---|---|
| `emittingClausesPayRegionRebuild` | -9.78%, "within error" | **-12.5%, ⚪ flat** | tool-right; both readings were weak |
| `handleLoopAnswersInPlace` | **-9.19%**, bold win | **-10.7%, ⚪ flat** | **claim overstated** |
| `suspensionBaseline` | **-8.08%**, bold win | **-9.7%, 🟢 faster** | confirmed, and larger |
| `handleLoopFusesContinuation` | **-5.53%**, bold win | **-6.9%, 🟢 faster** | confirmed, and larger |
| `continuationBodiesFuse` | **-4.87%**, bold win | **-7.1%, 🟢 faster** | confirmed, and larger |
| `fusionPastBudgetPaysRescuesOnly` | -1.17%, within error | -0.3%, ⚪ flat | agrees |
| eight rows | -1% to +2%, within error | all ⚪ flat | agrees |
| `trailingMapsStayLinear` | **+23.80%**, +239,976 B/op | **+43.2%, ⚪ flat**, **+239,977 B/op** | **timing never established; allocation confirmed** |

## What changed, and it is not what a bigger delta would suggest

**The three largest deltas in the whole run do not resolve.** `trailingMapsStayLinear` at +43.2% is
the largest of all and is also flat; an earlier version of this document said "two" and named only the
next two, which understated the very fact it was making. `emittingClausesPayRegionRebuild` at
-12.5% and `handleLoopAnswersInPlace` at -10.7% are the biggest movements on the board and both are
flat, while `continuationBodiesFuse` at -7.1% is a win. That is the entire point of a threshold: those
two rows have a between-leg spread that swallows their delta, and the single-leg sweep could not see
it because a single leg has no spread to measure.

So **the sweep's "five wins" is three.** `handleLoopAnswersInPlace` was bolded as a win on one leg and
does not survive replication. `emittingClausesPayRegionRebuild` was never bolded, was later re-measured
at `-wi 25` and read as a -9.5% win, and now reads flat again at a larger delta; the honest summary of
that row across three attempts is that it has never been measurable, not that it moves or does not.

**The three that survive all got bigger**, by 1.4 to 2.2 points. Nothing about the direction of the
result changed; what changed is which rows may be claimed.

## The loss, confirmed to one byte

`trailingMapsStayLinear` allocates **+239,977 B/op** here against **+239,976 B/op** in the original
sweep. Two independent five-leg-versus-one-leg measurements, months of tooling apart, agreeing to
**one byte in 240,000**. Allocation is exact and per-operation and this is what that means in practice.

Its **timing is still not established**: +43.2% against a flat-row resolution of ±59.94% at worst. The
original +23.80% was never established either, and the two figures differing by twenty points while
both fail to resolve is the clearest possible statement that this row's timing is not measurable at
one fork.

The harness proposed the right next experiment unprompted: re-run the control with
`-XX:-EliminateAllocations` to test whether the variant destroyed a scalar replacement rather than
adding an allocation. That experiment has already been run and **confirmed**, twice.

## What this run also proves about the harness

It is the first test of defect 25's fix, and it passes. The variant legs record the
`CompileCommandFile` and the controls record nothing, and the report prints the difference under
"Same sha, so this is a configuration comparison and the difference is exactly". The pair this
replaces is unrecoverable precisely because none of that was kept.

## Caveats the harness attached, carried rather than dropped

- `-f 1` is diagnostic, not a claim.
- Timing only, so no movement here is attributed to a mechanism; the three wins are marked
  **none found** and that is correct.
- Flat rows resolve only to ±59.94% at worst, which is wide, and is why so much of the class is flat.
- `emittingClausesPayRegionRebuild`'s control leg has a first iteration 6% off the median of the rest,
  flagged as a warmup ramp. Not a blocker here, but it is the same row that has failed to settle every
  time it has been measured.
