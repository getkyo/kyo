# Work ledger

Single source of truth for what is done, what is open, and what is currently being worked on. Read
this first on every wake-up; update it after every step, before reporting anything.

The other documents are subordinate to this one: `bench-harness-plan.md` is the design,
`review-findings.md` and `tool-defects.md` are per-stream ledgers, `bench-results/*/RESULT.md` are
measurements, `overnight-procedure.md` is the operating rules. If any of them disagrees with this
file about state, this file is wrong and gets fixed.

## The rule that governs all of it

Every measurement, comparison and verdict goes through the harness. Python and grep are oracles only:
they expose facts to check output the tool already produced. Computing a delta, threshold or verdict
by hand is the failure this project exists to prevent, and it happened for four experiments before
being caught.

## Now

**Validating the tool against the manual work.** Procedure: three-way comparison of raw data, tool
verdict, and my recorded claim, per experiment. The raw data is the arbiter; two of three agreeing
settles nothing.

| exp | ingested | tool verdict | 3-way table | disagreements discharged |
|---|---|---|---|---|
| exp3 sweep | yes | yes | **yes** | **yes**, 3 of 3 |
| exp1 budget probes | no | no | no | no |
| exp4 escape analysis | no | no | no | no |
| exp5 tier splits | no | no | no | no |

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

## Standing constraints

No kernel source landed. Candidates measured in the detached throwaway worktree only. No `inline`
without approval. No PR interaction. Commits under the owner's identity, no attribution.
