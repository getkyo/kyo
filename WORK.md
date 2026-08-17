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

## Re-measurement done, and it overturned a correction

`-wi 25` settles `emittingClausesPayRegionRebuild`: its error falls from ±12.33 to ±2.97 and the
blocker clears, exit 0. The row is a **-9.5% win** after all. So the original hand number was roughly
right, my intermediate correction ("never a win, it is flat") was wrong, and the truth is the leg was
*unmeasurable*. The tool had said so correctly by refusing the whole comparison; I read the refusal
as a verdict. See `bench-results/exp6/RESULT.md`.

The settling problem moved rather than vanished: `handleLoopFusesContinuation` now reports ±14.55%
and is flat at -8.8%. One re-measurement does not buy a clean sweep, and the tool is saying so.

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

## Design for the three open tool defects

They share a shape: the tool knows after the fact what it could have said beforehand, or does not
assert that an instruction it issued was obeyed.

**8, resolution reported only after spending the session.** A five-leg session at one fork resolves
to ±5.77%, so it cannot see the +4.5% regression this campaign is about, and that was discovered by
running it and reading the footer. Everything needed to say so first is already stored: any prior run
on the same rows carries each leg's `scoreError`. A `bench plan` subcommand should take the intended
configuration and a target effect size, and answer whether the configuration can resolve it, refusing
or warning when it cannot. Calibration measured earlier says forks will not help, since the variance
is between legs rather than within them, so the answer will usually be "more legs" and it should say
that rather than leaving the operator to guess.

**6, a malformed JVM flag produces a normal-looking run.** Two runs were spent before a log line
revealed `CompileCommand: An error occurred during parsing`. The harness must assert that a compile
command it issued was parsed, and prefer the file form, which does not have to survive shell and sbt
quoting. Without this, Phase 6's Tier A would silently test nothing while reporting refutations.

**9, synthetic validation agrees with the code's blind spots.** Not fully fixable: every fixture is
written by the same understanding that wrote the code. The mitigation is the A/A null, whose input is
not authored, and it has already earned that keep by catching a missing floor that 31 unit tests and
a simulation-backed review missed. It should run on every session rather than on request.

## Standing constraints

No kernel source landed. Candidates measured in the detached throwaway worktree only. No `inline`
without approval. No PR interaction. Commits under the owner's identity, no attribution.
