# Experiment 2: the regression is `Eval$::dispatch$1` failing to inline

The `continuationBodiesFuse` regression, open and undiagnosed across sessions, has a measured cause
with a passing efficacy gate.

## The isolation

Force the one suspect method to inline, change nothing else:

    -XX:CompileCommandFile   ->   inline kyo/kernel/proto/Eval$.dispatch$1

**Efficacy gate**, checked before reading the score, since a flag that does nothing would refute
every hypothesis and pass:

    dispatch$1 (607B)   default: 0 inlined / 2 refused  'hot method too big'
                        forced:  2 inlined / 0 refused

The command took, on exactly the target, and on nothing else that matters.

## The result

| current design | us/op | vs its own default | vs old design |
|---|---|---|---|
| default | 27.455 ± 0.336 | | +4.55% |
| `dispatch$1` forced inline | 25.566 ± 0.190 | **-6.88%** | **-2.65%** |

Forcing one 607-byte method to inline removes the entire regression and turns it into a win over the
old design. Nothing else changed: same sources, same JVM, same heap and collector, same machine, no
agents running.

## What this establishes

**The mechanism is named and measured.** `ask.map{...}` used to mint a `Suspend`, whose delivery is
expanded into the drive loop, and now mints a `SuspendWith`, whose delivery is a separate 607-byte
`dispatch$1` that HotSpot refuses as `hot method too big`. On this row, and only this row, the
continuation body is itself too large to repay that frame through downstream inlining.

This confirms candidate **DIS-1** by direct isolation: split dispatch into a small inlined degenerate
tier and an out-of-line general tier, so the hot path's dispatch fits the budget. It also says the
current design is *better* than the old once that frame is gone, which is the outcome the design was
supposed to deliver.

## The budget experiments were inconclusive, and the gate is why

Before this, two budget probes looked informative and were not:

    FreqInlineSize=600   dispatch$1  0 inlined / 2 refused  'hot method too big'
    FreqInlineSize=700   dispatch$1  0 inlined / 2 refused  'already compiled into a big method'

At 700 the callee's size no longer blocks it, but the caller is by then too big to absorb 607 more
bytes, so it still never inlines. Every gap measured at those budgets (-5.74% and -4.49%) therefore
says nothing about `dispatch$1`. Read without the gate, "the gap is similar at 600 and 700" would
have become "the frame is not the mechanism", which is the opposite of the truth.

That is the whole argument for the efficacy gate, demonstrated on a live question rather than
asserted in a design document.

## Limits

- One row. The other fourteen are untouched by this result and the fix must be measured across all of
  them, since a change to dispatch is on every row's path.
- `CompileCommandFile` is a diagnostic, not a fix. The fix is to make the method small enough to
  inline on its own, which is DIS-1's actual content and is not yet measured.
- One configuration per cell, no replication, so the harness's own threshold machinery was not
  applied. The effect (-6.88%, error ±0.2-0.34) is far outside noise, but the size is approximate.
- The parse failure that cost two runs is recorded in the tool-defect list: `-XX:CompileCommand` on a
  Scala name cannot survive the shell and sbt quoting chain, and a malformed command produces a run
  that looks entirely normal. Use `CompileCommandFile`, and check that it parsed.
