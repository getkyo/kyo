# Work ledger

Single source of truth for what is done, what is open, and what is currently being worked on. Read
this first on every wake-up; update it after every step, before reporting anything.

The other documents are subordinate to this one: `bench-harness-plan.md` is the design,
`review-findings.md` and `tool-defects.md` are per-stream ledgers, `bench-results/*/RESULT.md` are
measurements, `PROCEDURE.md` is the operating rules. If any of them disagrees with this
file about state, this file is wrong and gets fixed.

## The rule that governs all of it

Every measurement, comparison and verdict goes through the harness. Python and grep are oracles only:
they expose facts to check output the tool already produced. Computing a delta, threshold or verdict
by hand is the failure this project exists to prevent, and it happened for four experiments before
being caught.

## In flight

`exp6/base-wi25.json`: the base leg re-measured at `-wi 25` instead of 10, because three comparisons
are blocked on `emittingClausesPayRegionRebuild`'s control leg never settling. If 25 warmup
iterations settle it, the blocked comparisons can be re-issued; if not, that row needs a different
remedy and the tool should say so rather than the operator guessing.

Next commands, so they need no re-deriving:

    # ingest and compare against the forced-inline leg
    BenchIngest --json bench-results/exp6/base-wi25.json --label e6-base-wi25 --sha d85ee6821f \
                --session validation --declared-rows 15 --store bench-results/store
    BenchCompare --control <e6-base-wi25 id> --variant <sweep-forced id> --store bench-results/store

Expected: the blocker clears for that row, or it does not and the row is unmeasurable at this warmup.
Either is a result.

## Now

**Validating the tool against the manual work.** Procedure: three-way comparison of raw data, tool
verdict, and my recorded claim, per experiment. The raw data is the arbiter; two of three agreeing
settles nothing.

| exp | ingested | tool verdict | 3-way table | disagreements discharged |
|---|---|---|---|---|
| exp3 sweep | yes | yes | yes | yes, 3 of 3 |
| exp1 budget probes | yes | yes | yes | yes |
| exp2 forced inline | yes | yes | yes | yes |
| exp4 escape analysis | yes | yes | yes | yes |
| exp5 tier splits | yes | yes | yes | yes |

**Stream complete.** See `VALIDATION.md`. Every numeric claim survived; several confidences did not.
Three of five comparisons are now blocked on a control leg that never settled, and the sweep's fifth
"win" was never a win.

### Findings from this stream, all discharged

1. **`Bench.compare` had no floor at the legs' own error.** Classified -9.8% a win on a row whose
   control leg reports ±15.2%. *Tool wrong.* **Fixed**: single-pair comparison floors at the legs'
   own error and states that a floor is not a replicate-estimated threshold.
2. **No steady-state detection from the iteration series.** *Tool could not express it.* **Fixed**:
   `unsettledStart` judges by how far the first iteration drags the mean the verdict uses. Two
   earlier criteria were tried against real data and failed: a fixed percentage cannot work, and an
   outlier test cannot either, since two real rows trip it at 2.43x and 2.53x with only one a genuine
   ramp. Both rows are pinned as fixtures.
3. **`TOOL-VERDICT.md` claimed "the tool is right each time".** *False.* **Corrected**, error left
   visible.

### And the change that came out of it

An unsettled leg is now a **blocker**, not a warning: banner rendered first, data still printed in
full, process exits non-zero. A warning line in a thirty-line report is something this operator
demonstrably skips. On the real sweep it fires once, on the true positive, and exits 1.

## Done

- Plan through three review rounds; v4 is current.
- Phase 1 instruments, Phase 3 A/A null and bracket orchestration, Phase 4 bytecode, Phase 8
  known-answer fixture, the verdict statistic, efficacy gate, budget-proximity ranking, JVM args per
  leg, drift off the session path, the ingest path.
- All 12 implementation-review findings closed.
- **165 checks green** across five suites.
- Experiment data force-added to git; `.gitignore` had a global `*.json` hiding all of it.

## Open, beyond the validation stream

- **Phases 5, 6, 7**: allocation attribution via `output=collapsed`; the investigator's rule table;
  steady-state recalibration and the CLI QA that has never run.
- **The DIS-1 tier split is unresolved.** Variants 1 and 2 measured and both regress the handler rows
  22-35%; variant 3 (index found once, so a fast-path miss does not pay for a second stack scan) was
  edited and compiled with 126 tests green, but **its measurement was interrupted and never ran**.
  `bench-results/exp5/tiered3-1.json` is 0 bytes.
- **The sweep was never replicated**: one leg per configuration.
- **Ten tool defects** in `tool-defects.md`, of which two are now fixed (efficacy gate, budget
  ranking) and one is being fixed (ingest). Seven open.

## The kernel result, for a reader arriving cold

`continuationBodiesFuse` was slower under the current design than the old one and nobody knew why.
The cause is `kyo.kernel.proto.Eval$::dispatch$1`, 607 bytes, refused by HotSpot as `hot method too
big` and present only in the current design: `ask.map{...}` used to mint a `Suspend`, expanded into
the drive loop, and now mints a `SuspendWith` whose delivery is that separate method.

Forcing it inline takes the row from +4.5% to -6.9%, with the efficacy gate proving the flag took
(0 inlined / 2 refused becomes 2 inlined / 0 refused). Across all fifteen rows that crude fix wins on
four and costs `trailingMapsStayLinear` 23.8% and 239,976 B/op, which a further isolation showed is
exactly the scalar replacement the enlarged compilation unit destroys: disabling escape analysis on
the unmodified design reproduces the same allocation to within 24 bytes of 2.5 million.

Two tier-split variants aimed at capturing the win without that cost both regress the handler rows by
22 to 35%. A third variant, which finds the stack index once so a fast-path miss does not pay for a
second scan, is written and compiles with 126 tests green but **has never been measured**.

## Standing constraints

No kernel source landed. Candidates measured in the detached throwaway worktree only. No `inline`
without approval. No PR interaction. Commits under the owner's identity, no attribution.
