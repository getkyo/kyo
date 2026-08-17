# The three-way table, prepared before the replication lands

The original sweep (`bench-results/exp3/RESULT.md`) ran **one leg per configuration**, so every number
below is a single-leg difference with no threshold behind it. The replication in flight runs five legs
of the same two configurations, which is the first time these rows get a real threshold.

The comparison to make when it lands is three-way, and **the raw data is the arbiter**: two of the
three agreeing settles nothing. Fill the middle column from `BenchCompare` over the stored run ids,
never by hand.

| row | recorded claim (1 leg) | tool verdict (5 legs) | classification |
|---|---|---|---|
| `emittingClausesPayRegionRebuild` | -9.78%, not bold, "within error" | | |
| `handleLoopAnswersInPlace` | **-9.19%**, bold | | |
| `suspensionBaseline` | **-8.08%**, bold | | |
| `handleLoopFusesContinuation` | **-5.53%**, bold | | |
| `continuationBodiesFuse` | **-4.87%**, bold | | |
| `fusionPastBudgetPaysRescuesOnly` | -1.17%, within error | | |
| eight rows | between -1% and +2%, within error | | |
| `trailingMapsStayLinear` | **+23.80%**, bold, **+239,976 B/op** | | |

## What is already known to be contested, before the run reports

Three of these have been challenged by later work, so the replication is not starting from a blank
slate and should not be read as if it were.

- **`emittingClausesPayRegionRebuild`.** The -9.78% was later found to sit on a leg that never reached
  steady state; at `-wi 25` its error fell from ±12.33 to ±2.97 and it settled as a **-9.5% win**. The
  ledger records that my intermediate correction ("never a win, it is flat") was itself wrong, and
  that the truth was the leg had been *unmeasurable*. Whatever the replication says here supersedes
  both.
- **`trailingMapsStayLinear`'s timing.** The +23.80% is **not established**. A replicated bracket put
  that row's resolution at ±22.64%, so the figure was outside its own leg's resolution rather than
  measured. Its **allocation** regression is established and unaffected: +239,976 B/op, exact,
  reproduced twice.
- **The count of wins.** `VALIDATION.md` records "5 wins, 1 loss" as the recorded claim and "4-5 wins
  on settled legs" as what survived, with the fifth never having been a win.

## The other thing to read first

This run doubles as the first test of defect 25's fix. Before reading any verdict, confirm the store
recorded the configuration:

- variant legs must carry `-XX:CompileCommandFile=.../forced-inline.cmd` in `jvmArgs`
- control legs must carry nothing

If they do not, the replication has inherited the exact defect that made the original unrecoverable,
and the numbers are worth no more than the ones they were meant to replace.
