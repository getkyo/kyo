# Where the tool left me guessing

Recorded while running Experiment 1, which is the point of QA-ing the harness by using it. Two
things were under test: the candidate, and the tool. The tool is the one that matters, and every
moment I reached past it for a shell script or a python one-liner is a defect in it.

The experiment succeeded. The tool contributed almost nothing to that success, which is the finding.

## What I had to do by hand

**1. Run the experiment at all.** The harness has no flag-probe path. I wrote a 25-line shell script
to restore two designs, issue four JMH invocations with `-jvmArgsAppend`, and collect the json.
`runLeg` cannot take extra JVM arguments, so even with the harness running I could not have asked it
for this. *This is Phase 6 Tier A, unimplemented: the entire point of that phase is that a falsifier
which is a JVM flag can be derived from the hypothesis and run automatically.*

**2. The efficacy gate.** The protocol demands proving a flag took effect before reading its result.
The tool has no function for it. I wrote 40 lines of python to parse two compilation logs, group C2
inline verdicts by method, and diff them. `LogCompilation` already produces exactly this structure;
what is missing is a `diff(a, b)` over two `Parsed` values. Without that, the most important guard in
the protocol is a manual step, which means in practice it is an optional one.

**3. Finding the 379-byte method.** The mechanism was `run$56` at 379 bytes refused for
`hot method too big`. The tool holds every part of that (per-site verdicts, byte sizes, reasons) and
would not have surfaced it, because nothing ranks refusals by whether they sit near a budget
threshold. `Bytecode.Change.crossedBudget` does exactly this reasoning for a size *change* between
legs; the same question about an absolute size against the budget is not asked anywhere.

**4. Comparing two configurations of one design.** Every comparison the harness models is
design-against-design. Configuration-against-configuration, the same sources under two JVM settings,
has no representation at all, though it is the shape of every Tier A falsifier.

**5. Replication.** I ran one configuration per cell. The harness's own statistic needs C V C V C and
its runner still produces one leg per invocation, so the thresholds and the minimum detectable effect
I built tonight could not be applied to the one measurement that was actually taken. The numbers are
reported as approximate for exactly this reason.

## What the tool did contribute

The discipline, which is not nothing. Heap and collector pinned because the plan says to. The
efficacy gate run *before* the numbers were read, because the protocol says to, and it is the step
that turned a suggestive result into a named mechanism. The reading rules written down in advance,
so the interpretation was fixed before the data existed. And the honest limits section, which exists
because the plan requires every result to state what it does not establish.

That is the harness working as a procedure while contributing nothing as a program.

## Ranked fixes

1. **`LogCompilation.diff`** over two `Parsed` values, returning per-method verdict changes. Turns the
   efficacy gate from 40 lines of throwaway python into a call. Cheapest and highest value.
2. **`runLeg` accepting extra JVM arguments**, and a configuration-pair comparison alongside the
   design-pair one. Without these Tier A cannot exist.
3. **Rank refusals by proximity to a budget.** A method refused `hot method too big` at 379 bytes
   against a 325 default is a finding; the same refusal at 4000 bytes is furniture.
4. **Leg orchestration for C V C V C**, so the statistic that is implemented and tested can actually
   be applied to a measurement.

Nothing here is a surprise: items 1, 2 and 4 are Phases 6 and 3 of the plan, unimplemented. The value
of running the experiment first is knowing which order to build them in, and that item 3 was not in
the plan at all.

## Added after experiment 2

**6. A malformed JVM flag produces a completely normal-looking run.** `-XX:CompileCommand=inline,...`
on a Scala method name cannot survive the shell and sbt quoting chain: two runs were spent before the
log showed `CompileCommand: An error occurred during parsing`, and both had produced plausible
output. `CompileCommandFile` avoids the escaping entirely. The harness must, when it issues a compile
command, assert that the JVM parsed it, and must prefer the file form. Without that, Phase 6 Tier A
would silently test nothing while reporting refutations.

**7. The efficacy gate earned its place twice, in opposite directions.** On the budget probes it
showed the flag never achieved the intended state (`dispatch$1` refused at 600 and at 700, for two
different reasons), which made those runs inconclusive rather than evidence against the hypothesis.
On the forced-inline run it showed the change took exactly, which is what licensed reading the score.
A harness without it would have concluded the opposite of the truth from the same four numbers.

## Added after the first real bracket

**8. The harness reports its resolution only after spending the session.** The A/A at five legs and
one fork resolves to ±5.77%, which means it cannot see the +4.55% regression this campaign was built
around. That was discovered by running the session and reading the footer. A tool whose purpose is to
prevent wasted conclusions should say, before the first leg, what effect size the requested
configuration can detect, and refuse or warn when the target effect is below it. Everything needed is
already there: the legs' own errors are in the JMH json of any prior run on the same rows.

**9. Synthetic validation agrees with the code's blind spots by construction.** The replicate
statistic had 31 unit tests, a simulation-backed review, and three rounds of plan review, and none of
them found a missing `max` between the threshold and the legs' own error. Five legs of identical
sources on a real machine found it in one run. Every fixture in this suite was generated by the same
understanding that wrote the code under it; the A/A is the only check whose input the author does not
control. It should run on every session, not on request.


## Added by the first QA of the CLI surface

Every defect above was found by using the tool on kernel questions. Nobody had ever run its
commands *wrong* on purpose, which is the other half of what a tool has to survive. Four of the six
subcommands were exercised with a typo, a missing file, and a mismatched argument count.

**14, a store that does not exist reads as a store that is empty.** `bench list --store <typo>`
printed `no runs stored` and exited 0. So a mistyped path, a store on a different branch, and a store
whose runs really were deleted are three different situations the tool reported identically, and the
one that is an operator error is the one it is silent about. This is the campaign's own failure shape
arriving through the front door: a clean-looking result standing in for an unasked question.
**Fixed**: reading a store requires it to exist. `bench list`, `show`, `compare` and `plan` fail with
the path and what would have created it.

**15, an unknown run id was answered with a file path and a stack trace.**
`bench show --id nope` produced `Failure(kyo.FileNotFoundException: ... /runs/nope.json ...)` and then
the same text again under `Exception in thread "main"`. Two renders of one problem, both naming a
path the operator never typed, neither naming the ids that do exist. **Fixed**: the failure names the
id, lists what the store holds, and prints once. The doubling is upstream: `KyoAppRunner.onResult`
prints the result and then rethrows any `Throwable` error, so every kyo app that fails through
`Abort` renders its failure twice. The harness now catches its own failures before they reach that
path; the observation about kyo stands separately.

**16, a leftover argument was dropped without a word.** `bench ingest` with two json files and three
shas used the first two and discarded the third, so a mis-typed invocation ingested under shas the
operator did not intend. **Fixed**: the count must be one per file or one for all.

## Added by using the investigator on the campaign's own data

**17, adding a field to `Run` made every stored run undecodable.** All 34, across six stores, with the
campaign's whole measurement history behind them. The tool reported it clearly and refused, which is
correct, but the store is the durable record and a schema addition must leave the old records
readable. **Fixed**: the field is defaulted, and a test decodes a run recorded before it existed.

**18, the escape-analysis falsifier was adjudicated on wall clock.** It is a claim about bytes per
operation, and the row it fires on is one whose *timing does not resolve at all*, so judging it on
timing answers a question nobody asked. Found by running the new rule table against the real sweep.
**Fixed**: each hypothesis names the quantity that answers it.

**19, a one-byte allocation band refused the campaign's own result.** `gc.alloc.rate.norm` is nearly
exact, and "nearly" had been assumed rather than measured: the escape-analysis isolation landed 23.8 B
from its target on 2.56 MB, a reproduction to six significant figures, and was reported inconclusive.
**Fixed**, from data: across four A/A sets of three legs on identical sources, every kilobyte-scale
row reproduced to the byte and the one megabyte-scale row spread 47.8 B on 2.32 MB. The band is 5e-5
relative with a one-byte floor, twice the worst observed spread, and 1,875x smaller than the effect it
has to resolve.

## Added by exercising the guards that had never fired

**20, a leg could adopt the previous leg's numbers.** The results json is a fixed path per label and
was never deleted before a run. JMH exits 0 when its selector matches nothing, which is the exact
failure the retry beside it exists for, so such a run would leave the previous attempt's file in place
and this leg would parse it, pass the row-count check, and be stored as a measurement of sources it
never ran against. **Fixed**: the file is deleted before every attempt, and its absence afterwards
fails the leg with the reason.

**21, the red-tree gate had never refused anything.** Verified for the first time by planting a
failing test in the throwaway worktree: `sbt --client` does exit 1 on a test failure, `exec` does
abort, and the leg does not run. The gate works. What did not was its message: `Failure(1)` followed
by several hundred lines of *passing* test names, with the one line naming the failure far below the
fold. **Fixed**: a failed command reports its error lines and its tail, and says how much it left out.

**22, a QA check that passed whenever nothing was flagged.** `QaEndToEnd`'s steady-state check read
`stillCompiling(ctl).isEmpty || out.contains("NOT STEADY STATE")`, so on every run ever made it
asserted nothing. **Fixed** to the biconditional it meant.

**23, the steady-state compile-time limit cannot be calibrated from any data this campaign has.** It
was 1.0% of the measured window, 50 ms, against an observed maximum of 9 ms across 52 stored rows, so
it has never fired and could not have. The plan called for tightening it to the ~4 ms of the run that
motivated it; that would be wrong, because 8 of those 52 rows sit at or above 4 ms and every one is
ordinary. Nor is there a positive case to fit: of the 25 rows carrying both an iteration series and a
compile-time figure, none is unsettled by the series criterion. **Bounded rather than fixed**: set to
0.5%, under three times the worst share observed, and documented as not-yet-validated. The signal
actually catching unsettled legs is `Row.unsettledStart`.

## Defect 9's mitigation, wired in

**24, a dirty A/A null printed a line and exited 0.** The null runs on every bracket, so the "runs on
request" half of defect 9 was already closed; what was not is that its failure was reported as a
warning. Every row an A/A null classifies is a false positive by construction, comparing control legs
against each other, so a dirty null is the strongest statement available that the session below is
unreadable, and it was the one statement the tool made in passing. **Fixed**: it is a blocker, in the
same banner and behind the same exit code as an unsettled leg, naming the rows it falsely classified.

A session with too few control legs is blocked too, and for a stronger reason: it is not that the
check failed, it is that the session cannot check itself. That also used to be a printed line.

## Found by reading a real verdict

**26, two fixed decimals made a nanosecond row unreadable.** The C4 bracket regressed
`evalFixedOverhead` by 26.2%, cleanly separated and not marginal, and the report rendered it as
`0.01 ± 0.00` against `0.01 ± 0.00`. The numbers behind the verdict were invisible and the error
column read as though there were no error at all; the real values were 0.005853 ± 0.000317 against
0.007364 ± 0.000111, which any reader could have judged on sight.

The skill requires that a reader judge a result without asking what was measured or how confident it
is, and requires the error column specifically because a delta smaller than the combined error is not
a result. Rounding both to zero defeats both rules at once. **Fixed**: precision scales with
magnitude, and the same stored runs re-read correctly with no re-run, since a comparison is a pure
function over records.

## Found by trying to replicate the sweep

**25, a stored run did not record the JVM arguments that produced it.** A configuration comparison
varies nothing but those arguments, so a stored pair with the same `sha` and the same `treeHash` is
otherwise indistinguishable. The campaign's **headline result** is stored exactly that way:
`continuationBodiesFuse` at -6.8% under a forced inline, with the forcing recorded nowhere. Those runs
can be loaded, re-compared and re-reported forever, and no reader can say what was measured. The
compile-command file that produced them is no longer on disk either, so the configuration is
unrecoverable rather than merely unstated.

This is the same family as defect 11: the harness gained the ability to *express* configuration
comparisons (`Arm(sha, jvmArgs)`) without gaining the ability to *record* one.

**Fixed**: `Run.jvmArgs`, defaulted so the 34 stored runs still decode, and a same-sha comparison now
prints exactly which arguments differ. A same-sha pair with nothing recorded says so, and names the
two readings, rather than presenting itself as a clean comparison.

## Found by validating one part of the tool against another

**27, a bracket's replicated verdict could not be re-read from its own store.** `bench compare` took
exactly one control id and one variant id, so once a bracket's legs were stored the only statistic
recoverable from them was the **single-pair** one, which is the weaker of the two. On the replicated
sweep the pair says *five* wins and the replicate statistic says *three*. So the harness's stated
principle, that a comparison is a pure function over records and two run ids always produce the same
verdict, held for the weaker claim and silently not for the stronger one, and the stronger one existed
only in the terminal scrollback of the run that produced it. **Fixed**: `--control` and `--variant`
repeat, and more than one leg on either side selects `compareReplicated`. Re-rendering the sweep from
its store now reproduces the bracket's own three wins exactly.

**28, the report showed only the worst resolution, never each row's own.** The footer says "flat to
within its own resolution, at worst ±X", which cannot answer the question a flat row actually raises:
was it close to resolving or nowhere near? On the replicated sweep `emittingClausesPayRegionRebuild`
came back flat at **-12.5% against its own ±12.6%**, missing by a tenth of a point, and nothing in the
table said so. **Fixed**: a `resolves` column per row.

**29, `BenchPlan` did not reproduce the thresholds it forecasts.** It forecasts from control legs
only, while the threshold it is predicting pools the spread of *both* arms, so it cannot see the
variance the variant legs contribute. Measured against the replicated sweep, forecast against actual:

| row | forecast | actual | |
|---|---|---|---|
| `handleLoopAnswersInPlace` | ±10.0% | ±14.4% | under by 1.4x |
| `continuationBodiesFuse` | ±1.9% | ±3.6% | under by 1.9x |
| `suspensionBaseline` | ±3.2% | ±3.9% | under |
| `handleLoopFusesContinuation` | ±6.8% | ±5.8% | over |
| `emittingClausesPayRegionRebuild` | ±14.6% | ±12.6% | over |

**Fixed, and the diagnosis had three parts, the third found by the test rather than by me.** It lumped every prior leg into one spread, which sees only
one arm's variance when given one arm and folds the real between-arm difference into the "spread" when
given both. It now splits by arm and pools *within* each, exactly as `Stats.pooledSd` does. It
omitted the floor `Stats.threshold` applies at the legs' own reported error, so it predicted
resolutions the real comparison would never award. And it normalised against the mean of *every* leg
where the threshold divides by the **control** mean, which understates the forecast whenever the arms
genuinely differ: on `trailingMapsStayLinear`, whose variant runs a third slower than its control,
that alone put the forecast 8.8 points below the threshold it was predicting.

With all three corrections, forecasting the replicated sweep from its own legs reproduces **all 15**
of the thresholds that bracket produced, most of them to a tenth of a point, including
`trailingMapsStayLinear` at ±59.9% against ±59.9%. `PlanTest` pins that as a relationship rather than
a table of constants, so it fails if either estimator drifts.

**A claim of mine that the test refuted.** I recorded that a one-arm forecast is "systematically
optimistic". Measured, it is not: against this bracket it differs from the two-arm forecast on 8 rows
of 15 and under-predicts on only **4**, erring in both directions. So the property worth labelling is
not a direction but a difference, and the label reads "one arm only, optimistic" for a reason that is
weaker than I first wrote. The honest statement is that seeing one arm's spread instead of two is a
materially different estimate, and the test asserts that rather than a bias.

## Status, reconciled

| # | defect | state |
|---|---|---|
| 1 | no `LogCompilation.diff`, so the efficacy gate was 40 lines of throwaway python | **fixed** |
| 2 | `runLeg` could not take JVM args, so no configuration probe was expressible | **fixed** |
| 3 | refusals not ranked by proximity to a budget | **fixed** |
| 4 | no C V C V C orchestration | **fixed** |
| 5 | (superseded by 4) | - |
| 6 | a malformed `CompileCommand` produces a normal-looking run | **fixed**: the leg refuses, with the JVM's own diagnosis |
| 7 | the efficacy gate earned its place in both directions | observation, not a defect |
| 8 | resolution reported only after spending the session | **fixed**: `BenchPlan`, forecasting from between-leg spread |
| 9 | synthetic validation agrees with the code's blind spots | open, mitigated by the A/A |
| 10 | the report showed leg one beside a mean-based delta | **fixed** |
| 11 | **no ingest path**: the tool could only compare runs it produced, which is what drove four experiments' verdicts into hand-written python | **fixed** |
| 12 | no floor at the legs' own error in single-pair comparison | **fixed** |
| 13 | no steady-state detection from the iteration series | **fixed**, and now a blocker |
| 14 | a store that does not exist reads as a store that is empty | **fixed**: reading one requires it to exist |
| 15 | an unknown run id answered with a file path, printed twice | **fixed**: names the id, lists what exists, prints once |
| 16 | a leftover `--sha` dropped silently | **fixed**: one per file or one for all |
| 17 | a field added to `Run` made every stored run undecodable | **fixed**: defaulted, with a decode test |
| 18 | the escape-analysis falsifier judged on wall clock | **fixed**: a hypothesis names its quantity |
| 19 | a one-byte allocation band refused a six-figure reproduction | **fixed**: band measured from the A/A legs |
| 20 | a leg could adopt the previous leg's json | **fixed**: deleted before every attempt, absence fails the leg |
| 21 | the red-tree gate had never refused anything | **verified** it refuses; its message **fixed** to be readable |
| 22 | a QA check that passed whenever nothing was flagged | **fixed**: the biconditional it meant |
| 23 | the compile-time steady-state limit is uncalibratable from any data here | **bounded**, and labelled as such |
| 24 | a dirty A/A null printed a line and exited 0 | **fixed**: a blocker, with the exit code |
| 25 | a stored run did not record the JVM args that produced it | **fixed**: recorded and reported; the headline pair remains unrecoverable |
| 26 | two fixed decimals made a nanosecond row unreadable | **fixed**: precision scales with magnitude |
| 27 | a bracket's replicated verdict could not be re-read from its store | **fixed**: `--control`/`--variant` repeat |
| 28 | the report showed only the worst resolution, not each row's | **fixed**: a `resolves` column |
| 29 | `BenchPlan` forecast from one arm, unpooled, and without the threshold's floor | **fixed**: reproduces real thresholds |
| 30 | a multi-row leg's compilation log describes only its **last** row: fixed `-XX:LogFile` path, one JVM forked per benchmark | **open**, verified by a two-JVM probe |
| 31 | no decodable jit data exists: the two jit-bearing runs fail to load on a missing `osrTasks`, and their entries are the old `JitEntry` shape | **open**, verified by running `bench show` |
| 32 | `StoreSchemaTest` covers an *added* field but not a **changed field shape**, which is how 31 got in | **open** |
| 33 | `Ingest.run` hardcodes `evidence = Timing` and `jit = Chunk.empty`, so the ingest path cannot build a jit-bearing fixture at all | **open** |
| 34 | `Bench.actionableJit` has a 6-line docstring documenting a vocabulary "verified against a capture", and no caller and no test | **open** |
| 35 | `diffVerdicts` and `Bench.jitShift` are two implementations of one question, over different types, in different modules; the tested one is unwired | **open** |
| 36 | `--drift-row` is inert: `openSession` never references the parameter | **open** |
| 37 | `driftPercent` is hardcoded `0.0`, so "drift measured this session" is unreachable and every report prints "4.0% assumed" | **open** |
| 38 | `QaEndToEnd` asserts `driftPercent > 0.0` and therefore fails by construction | **open** |
| 39 | `Delta.flatButUnbounded` can never be true: both producers always attach a `Resolution` to a `Flat` verdict | **open** |
| 40 | `Stats.commonMode` is computed on every replicated comparison, bound to `common`, and never read | **open** |
| 41 | the `-f N is diagnostic` note is keyed on fork count, so it fires on every replicated bracket that has a real threshold | **open** |
| 42 | `Run.alloc` and `Run.cpu` cost one extra JMH invocation each and surface as one integer and one percentage printed only above 25% | **open** |
| 43 | the unexplained-row line tells the operator to "check the inlining log" on a **Timing** run, whose `jit` is empty by construction: it names evidence the run provably does not contain | **open**, reproduced live |
| 44 | `mode` and `unit` are captured and never checked, so `Bench.compare:567` calls a negative percentage `Faster` unconditionally: a **`thrpt` row is classified backwards**, and two legs in different time units are off by the unit ratio | **open**, exposed via `bench ingest` |
| 45 | `Run.coverage` (`Model.scala:307`) has **no schema default**, so defect 17's *original* class is live in the tree right now | **open** |
| 46 | `StoreSchemaTest` **does not exist**; the check is inline at `BenchTest.scala:498-511` covering `allocByMethod` only, and a stale comment at `Model.scala:323` names the phantom | **open**, and the plan inherited the name unchecked |
| 47 | `Ingest` fabricates `forks = 1` while the json carries the truth (**three readers**, including `stillCompiling`'s measured-window denominator) and **discards `jvmArgs` entirely**, so an ingested configuration pair calls the difference unrecoverable while holding the file that states it | **open** |
| 48 | multi-row allocation break: `parseAlloc` reads one flat table per benchmark so `Run.alloc` carries duplicate `cls` rows, and `apportion` takes a last-wins numerator against a denominator summed across all benchmarks | **open**, `allocConservation` is the check that fires on it |
| 49 | the JIT table mixes scopes: `msInWindow`/`msTotal` summed across all rows from `-prof comp`, task census from the single surviving fork's log, printed as one table headed "a property of the design" | **open**, a consequence of 30 |
| 50 | "N flat rows carry no resolution" is **reachable** via `bracket --legs 2` and **mislabelled**: it fires on rows that are not flat. I first recorded it as unreachable dead code, which would have deleted a real defect | **open**, my misdiagnosis corrected |
| 51 | `QaEndToEnd.check` only **prints** (`:17-18`), unlike `BenchTest.check` which throws (`:41-43`), so a QA main's checks cannot fail the run: "fails by construction" means "prints FAIL and exits 0" | **open** |
| 52 | item 4 as shipped: the note now fires on **every** Full leg (the `n < 25.0` gate became unreachable in the other direction), and its two-way partition classifies `ProtoKernelBench$$anon$95` as immovable although that allocation is candidate C3's entire subject | **open**, rework in flight |

Fifty-two entries: **25 fixed**, 24 open (9, 30-52), 1 bounded (23), 1 an observation (7),
1 superseded (5).

**44 through 52 came from a held-out review of the plan, not from using the tool.** That is now the
second time a review round has produced more defects than a campaign of operating the harness did,
and the shape is consistent: operating it surfaces what it says wrongly, reading it surfaces what it
never says and what it silently mis-scales. 50 is the sharpest of them, because I had already
recorded that warning as unreachable dead code, and deleting it on my own diagnosis would have
removed a live, mislabelled defect.
Counted from the table rather than tallied by hand, because this line had drifted from it once
already. Defect 9's mitigation is now wired into every
bracket rather than available on request; what remains irreducible about 9 is that fixtures are
written by the understanding that wrote the code, which is why the A/A, whose input is not authored,
is the check that keeps earning its place.

Defect 9 is not fully fixable: every fixture is written by the same understanding that wrote the code
under it. The mitigation is the A/A null, whose input is not authored, and it has earned that keep
twice: catching a missing floor that 31 unit tests and a simulation-backed review missed, and catching
a forecast that would have talked me out of the campaign's best measurement.

**30 through 42 were all found in one pass, by two held-out agents and by re-deriving my own numbers,
after I had declared the tool validated.** None was found by using the tool. That is the finding: 1-29
came from operating the harness and watching it misbehave, which surfaces what the harness *says*
wrongly; it cannot surface what the harness never says at all. Ten of these thirteen are silence, not
error. `bench show` printing `944` for 944 jit entries is not a wrong statement, so no amount of
careful operation would ever have flagged it, and none did across the whole campaign.
