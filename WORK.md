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

## DIS-1 is refuted, by measurement, in three forms

Variant 3 halves the handler regression (+11.2% against +21.7%) and avoids the allocation cliff
(+24 B/op against +240,000), and delivers **no win**: `continuationBodiesFuse` is flat at -0.7%
against the -6.8% that forcing the whole 607-byte body inline delivers.

The win comes from inlining the whole body, and a small inlined fast path does not approximate it.
What helps the target row is exactly what breaks `trailingMapsStayLinear`'s escape analysis, and
shrinking what gets inlined shrinks the win with it. See `bench-results/tier3/RESULT.md`.

Open: whether some other shape recovers the win without enlarging the compilation unit. Nothing
measured suggests one, and proposing another without a mechanism would be guessing.

## The central question, answered with replication

First bracket with a real threshold: A/A null clean, `continuationBodiesFuse` **-6.8%** when
`dispatch$1` is forced inline. The headline survives.

The correction is on the other row: `trailingMapsStayLinear`'s **timing** regression is *not*
established. It resolves to ±22.64%, so the +23.8% and +26.6% I reported from single legs were
outside their legs' resolution rather than measured. Its **allocation** regression is established and
unaffected: +240,000 B/op, exact, matching two independent earlier measurements.

Exposed one defect, fixed: mechanisms are suppressed on flat rows, so a 240,000 B/op change showed
only as a column entry. Allocation does not need the timing to resolve. See
`bench-results/bracket1/RESULT.md`.

## Re-measurement done, and it overturned a correction

`-wi 25` settles `emittingClausesPayRegionRebuild`: its error falls from ±12.33 to ±2.97 and the
blocker clears, exit 0. The row is a **-9.5% win** after all. So the original hand number was roughly
right, my intermediate correction ("never a win, it is flat") was wrong, and the truth is the leg was
*unmeasurable*. The tool had said so correctly by refusing the whole comparison; I read the refusal
as a verdict. See `bench-results/exp6/RESULT.md`.

The settling problem moved rather than vanished: `handleLoopFusesContinuation` now reports ±14.55%
and is flat at -8.8%. One re-measurement does not buy a clean sweep, and the tool is saying so.

## The CLI surface has now been QA'd, and it was the softest part of the tool

Every earlier defect was found by using the tool on a kernel question. Nobody had ever run its
commands *wrong*. Six probes with a typo, a missing file and a mismatched argument count found three
defects, one of them the campaign's own failure shape: **a store that does not exist read as a store
that is empty**, `no runs stored`, exit 0. A mistyped `--store` was indistinguishable from a store
whose runs were gone.

All three fixed, pinned by six new checks; **193 checks green** across five suites. See defects 14 to
16 in `tool-defects.md`. One observation about kyo itself came out of it: `KyoAppRunner.onResult`
prints a failed result and then rethrows it, so every kyo app renders its `Abort` failures twice.

## Phase 5 is done: allocation is attributed to the method that allocated it

The flat table says `Nested` is 50.7% of this row's allocation and can never say by whom. The
collapsed view can, and now does, validated end to end on a real JMH run rather than on a fixture:

    kyo.kernel.proto.Nested                    minted at kyo.kernel.proto.Nested$.apply   (3790 samples, ~1.99 GB)
    kyo.kernel.bench.ProtoKernelBench$$anon$95 minted at ProtoKernelBench$.ask            (3676 samples, ~1.93 GB)

Both views come from **one** recording, so the conservation check measures the parse: exact, 7,481
samples across 1,951 collapsed lines, nothing lost. Two recordings of the same planted program differ
by 0.25%, and the gate correctly refuses that pair, which is the whole reason the single-recording
requirement is in the plan.

Acceptance is a planted program whose only significant allocator is named before the parser runs, not
conservation, which holds equally for a correct attribution and for one assigning every sample to an
arbitrary frame.

Two parser defects found on the way, both the character-class family that has bitten this file four
times: an array type truncated at the bracket (`java.lang.Object[]` and `java.lang.Object` became one
row, and on the kernel rows the `Object[]` is the stack), and JVM-internal C++ frames truncated at the
`::`, one of them reported as a method called `void`.

**220 checks green.**

## Phase 6 Tier A is done: every delta now arrives with its next experiment

A comparison used to say a row moved and stop. It now offers the falsifiers that could contradict the
obvious reading of it, each one flag and one run: force the refused method, raise only the budget it
is over, force the *control*'s refused method to test whether the win was the inlining rather than the
design, disable escape analysis when allocation moved. The megamorphism and GC falsifiers are
deliberately absent, the first unstatable from a log that profiles a receiver at 12 sites in 5,093,
the second impossible while every leg pins heap and collector.

The gate that makes an answer worth having: **a falsifier whose flag did not take refutes nothing.**
`CompileCommand=inline` is a hint and HotSpot still refuses on `MaxInlineLevel`, node budget, or a
method it cannot compile, so a pipeline without this gate refutes every hypothesis and passes its own
acceptance. A confirm additionally requires that *only* the instructed method's verdict moved, since
forcing a callee spends the caller's remaining budget.

Acceptance runs in both directions, which is what the earlier design got wrong: a planted true
hypothesis must be **confirmed** and a planted false one **refuted**, so an always-refutes
implementation fails half of it. Three further answers are distinguished from both: the flag never
fired, the row half-moved, and the experiment never had the power to separate the two.

**255 checks green** across seven suites.

Run against the campaign's own sweep, the rule table proposes **exactly the experiment I ran by hand**
in experiment 4, and adjudicates it from stored data: `trailingMapsStayLinear` rides scalar
replacement, CONFIRMED, 2,321,410 to 2,561,410 B/op against a target of 2,561,387. That round trip
found three more defects, two of them in the investigator itself and one much worse: adding a field to
`Run` had made **every stored run undecodable**, all 34 of them. See defects 17 to 19.

## The guards that had never fired, exercised

Three of the harness's own guards had never once been triggered, which is the same shape as the CLI
that had never been run wrong. Exercising them found four more defects (20 to 23):

- **A leg could have adopted the previous leg's numbers.** The results json is a fixed path per label
  and was never deleted; JMH exits 0 when its selector matches nothing, so such a run would parse the
  previous attempt's file, pass the row-count check, and be stored as a measurement of sources it
  never ran against. Deleted before every attempt now, and its absence fails the leg.
- **The red-tree gate does work**, verified by planting a failing test in the throwaway worktree. Its
  message did not: `Failure(1)` under several hundred lines of *passing* test names.
- **A QA check asserted nothing** on every run ever made (`isEmpty || contains`).
- **The compile-time steady-state limit cannot be calibrated from any data this campaign has**, and
  saying so is the honest result. It was 50 ms against an observed maximum of 9 ms across 52 rows.
  The plan's proposed 4 ms would fire on 8 of those 52, all ordinary. Bounded at 0.5% and labelled
  not-yet-validated; `Row.unsettledStart` is the signal actually doing this work.

**260 checks green** across seven suites.

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

## Tool defect 8 closed: the session says what it can resolve, first

`BenchPlan` forecasts each row's detectable effect before a session is spent. Three runs were spent
reporting a 25% timing regression on a row that resolves to ±22.6% and cannot support a verdict of
that size, and each was believed at the time.

Its first version was wrong in the way this campaign keeps being wrong: it estimated between-leg
spread from a single leg's *within-leg* error, which the fork calibration had already shown are
different quantities. It called `continuationBodiesFuse` unresolvable at ±14.6% when a real bracket
resolved a -6.8% win on it, and would have talked me out of the campaign's best measurement.

Corrected to use between-leg spread when legs exist, and to label the single-leg case an
approximation. Validated against the bracket that refuted it: forecast ±1.5% for
`continuationBodiesFuse` (bracket resolved -6.8%) and ±15.0% for `trailingMapsStayLinear` (bracket
reported ±22.6%, called +9.5% flat). Same conclusions, both rows.

## Open, beyond the validation stream

- **Phase 6 Tier B**: a bracket that accepts a *chain* of shas, so a source-level mechanism can be
  isolated. With two shas the harness must refuse any source-level claim and say the partition was
  never declared. Tier A (the falsifiers that are JVM flags) is done.
- **Phase 7 remainder**: dead code (`MinCpuSamples`, `NoiseShare`, stranded doc comments, README
  drift) and deleting `bench-harness-qa.md`. The guards it named are all exercised now.
- **DIS-1 is closed: refuted in all three constructible forms.** See above. The remaining candidates
  in `optimization-plan.md` are untouched.
- **The sweep was never replicated**: one leg per configuration.
- **Twenty-three tool defects** in `tool-defects.md`: twenty-one fixed, one bounded, one open, one an observation.

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
