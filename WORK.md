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

## OPEN, in the order it should be picked up

1. **C4 is measured and needs a ruling.** The fix costs **+26.2% on `evalFixedOverhead`**
   (0.005853 → 0.007364, about 1.5 ns per top-level `eval`), every other row flat, A/A null clean.
   That is the row that measures `Eval.apply`'s fixed overhead, so it is the row that should move.
   The trade is 1.5 ns per eval against a permanent per-thread budget leak on exceptions. A cheaper
   `catch`-based shape exists but is not equivalent and is a hypothesis, not a recommendation; the
   honest way to settle it is a three-sha chain. **Default: keep the `save`/`finally` version.** See
   `bench-results/c4/RESULT.md`.
2. **The remaining candidates are all gated, each has a recorded default, and two probes are done.**
   See `RULINGS-NEEDED.md`. C1, IN-1, IN-2, DIS-1 and DIS-2 are closed; C3, DIS-3, DIS-4 and IN-3 need
   a ruling. Defaults: **DIS-3 proceed** (the only open lead on the campaign's central regression,
   since deleting `dispatch$1` by collapsing the two suspension arms is the "other shape" DIS-1's
   refutation left open), **IN-3, C3, DIS-4 hold**.
   - **IN-3 probed**: the poll's declared surface is 145 B against a claimed 712 B, so its byte-count
     premise is post-inlining expansion that javap cannot show and no instrument here can settle.
     Its falsifier is dead independently (every safepoint method is inlined at 23-24 sites, so there
     is no refusal to flip). Only the CPU-profile motivation survives, ~8.4% of samples.
   - **C3 probed, no run needed**: the second 16 KB/op is `ProtoKernelBench$$anon$95` minted at the
     benchmark's own `ask`, 49.1% of the row. Not refuted, because its claim is about preemption
     design rather than about this site, but the conversation now has a first question: by what
     mechanism does moving the park trigger stop `ask` minting that node?
   - **DIS-4 probed, as layout arithmetic**: `Suspend` declares 0 fields, `SuspendWith`, `Bind` and
     `Chain` declare 2. Under a 12-byte header with 8-byte alignment those occupy 16 and 24 bytes, so
     an added 4-byte kind field lands in existing padding and is **free** on all four, the opposite of
     the candidate's stated risk. This does not kill it; the expensive half, the drive rewrite, is
     what the gate is really about.
3. **The sweep is replicated. DONE.** Five legs of both configurations, A/A null clean, and it
   corrects the original: **the sweep's "five wins" is three.** `suspensionBaseline` -9.7%,
   `continuationBodiesFuse` -7.1% and `handleLoopFusesContinuation` -6.9% survive with a real
   threshold and all three got *larger*. `handleLoopAnswersInPlace` was bolded as a win on one leg and
   does not survive. The **three** largest deltas on the board are all flat: +43.2%, -12.5% and
   -10.7%. That is exactly what a threshold is for and what a single leg cannot see. (An earlier
   version of this entry said "two", omitting the largest of the three.)

   `trailingMapsStayLinear` allocates **+239,977 B/op** against the original's **+239,976**: two
   independent measurements agreeing to one byte in 240,000. Its timing is still not established,
   +43.2% against a ±59.94% resolution, and the original +23.80% was not established either.

   It also passed the first test of defect 25's fix: variant legs recorded the `CompileCommandFile`,
   controls recorded nothing, and the report printed the difference. See
   `bench-results/sweep-replicated/RESULT.md`.

4. **DIS-3 is designed and blocked on one sign-off.** Proceeding under its own recorded default got
   as far as the design and the cast, which I said I would surface before measuring. The request is
   narrower than the plan implied: the collapsed arm keeps the `@unchecked` typed pattern the drive
   **already uses at both arms today**, on one arm instead of two, so this introduces no new category
   of concession. `Suspend.chain` already carries a reference-identity cast on its `Identity` fast
   path as precedent. See `bench-results/dis3/DESIGN.md` for the exact spelling, the acceptance
   conditions (`dispatch$1` must be **gone**, not smaller; 128 tests green; must not share a bracket
   with DIS-1) and the falsifier.

5. **`BenchPlan` did not reproduce the thresholds it forecasts. FIXED, tested, and one of my own
   claims refuted in the process.** Validating it against the
   replicated sweep needed two other defects fixed first (27 and 28), and then it could be checked:
   three defects. It lumped all legs into one spread instead of pooling *within* each arm; it omitted
   the floor `Stats.threshold` applies at the legs' own error; and it normalised against every leg's
   mean where the threshold divides by the **control** mean, which cost 8.8 points on
   `trailingMapsStayLinear` alone. The third was found by `PlanTest`, not by me.

   It now reproduces **all 15** thresholds that bracket produced, most to a tenth of a point,
   `trailingMapsStayLinear` included at ±59.9% against ±59.9%. `PlanTest` pins the relationship rather
   than constants, so it fails if either estimator drifts.

   **And it refuted a claim I had already written down.** I recorded that a one-arm forecast is
   "systematically optimistic". It is not: it differs from the two-arm forecast on 8 rows of 15 and
   under-predicts on only 4, erring both ways. Corrected in `tool-defects.md` and pinned by a test
   that asserts a difference rather than a direction.

6. **Every fix from this stream is now pinned by a test, which was itself a gap.** Defects 25 to 29
   were fixed and verified by running commands and reading output, leaving nothing to catch a
   regression, which is the exact shape this project corrects in its own results. `PlanTest` (new,
   reads the real bracket) and four checks on the resolution column close it. **324 checks green
   across eight suites.** Defect 27's CLI wiring, two lines choosing `compareReplicated` when given
   more than one leg per side, is exercised by running it and not by a test; the statistic it selects
   is covered by `PlanTest` on real stored legs.

7. **`IMPROVEMENT-PLAN.md` written, held-out opus review IN FLIGHT.** Thesis: the tool refuses well
   and volunteers poorly, and the remaining value is converting what is already in the store into
   statements the report makes out loud. Five such syntheses (A1-A5) are things I derived by hand this
   campaign and the tool had all the data for. The reviewer is briefed to attack the thesis, to check
   the cited numbers rather than accept them, and specifically to challenge A2 (false-alarm risk),
   A3 (whether per-row attribution is derivable at all, since only one row was ever profiled) and
   B2 (`-f 3` by default trades machine time against a variance that is between-leg, not within-leg).

**The C4 fix is NOT landed, and is now on a branch so it cannot be lost.** It exists only in the
throwaway worktree, per the standing rule, so the safepoint depth leak is still live in the proto
kernel and landing it is a ruling rather than an action I take. It is also the **only** kernel change
from this work.

It was reachable from **nothing but a detached HEAD**, which any `checkout` in that worktree would
have orphaned and a prune would then have deleted: a reproduced, tested bug fix one command away from
gone. Now `parked/c4-safepoint-root-guard`, per the skill's own rule that rejected and parked
experiments become branches rather than floating commits. The same branch also rescues
`9685c9b445`, DIS-1's tier-split variant 3, which was floating for the same reason. A sweep of every
`measure-only` commit confirms all three are now on a ref.

8. **The held-out review landed and it is severe. See `REVIEW-FINDINGS-PLAN.md`.** My plan's thesis
   ("refuses well, volunteers poorly") is wrong: classified by shape, the dominant failure across 29
   defects is **a confident statement that was wrong**, 7 cases, not silence, 3-4 cases. Four of my
   five section-A items add a new derived claim, landing in exactly that category, with no new
   cross-validation proposed for any of them.

   Two findings I verified myself, both real: **`compare` and `chain` never run the A/A null** (only
   call sites are `Cli.scala:210/215`, inside `BenchBracket`), so the strongest refusal is unreachable
   from stored records; and **`KnownNoise` misses the benchmark's own frames**, so `noiseShare` prints
   **29% where the truth is 70%**, understated by 41 points in the direction that flatters the kernel,
   with a fixture that cannot catch it. A live instance of open defect 9.

   The finding I would not have reached: **7 of 15 rows are floor-bound**, and
   `handleLoopAnswersInPlace` at -10.7% has a ±14.4% threshold set *entirely* by the own-error floor
   against a spread term of ±8.2%. Halve the legs' own error and **the row resolves as a win** - the
   very row whose demotion made the sweep's five wins into three. Meanwhile `Plan.scala` tells the
   operator unconditionally that forks do not help, on the strength of one A/A on one row.

   Cut A2, A3-as-written, B2, C2. Rework A1, A4, A5. Order accepted as the reviewer gave it.

**Everything else remaining is a ruling**, each with a recorded default: the four gated candidates in
`RULINGS-NEEDED.md`, the C4 trade, and DIS-3's cast above.

**On DIS-3 specifically**: its recorded default is proceed, but the exception opened for kernel work
was scoped to C4, and the skill requires sign-off for casts. I surfaced the cast and did not start the
redesign on my own authority. That is a deliberate stop against the default, and it is the one place
in this ledger where I have not taken my own recorded default.

Everything else below is finished work, kept for its reasoning.

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

**Superseded by the replication, which is the fourth reading of this row and the first with a
threshold: -12.5%, and flat.** So "a -9.5% win after all" does not stand either. Across four attempts
this row has produced -9.78%, "unmeasurable", -9.5% and -12.5%, and the only claim consistent with all
four is that **it has never been measurable**, not that it moves and not that it does not. Its control
leg is flagged for a warmup ramp in the replication too, the same failure it has shown every time.

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

## DIS-2 answered, and the answer is that it was never tested

Second in the plan's run order because it needs no source change. The tool says
`continuationBodiesFuse` is **-17.4%** under `-XX:FreqInlineSize=600`, more than twice the -6.8% that
forcing `dispatch$1` delivered, which invites the conclusion that the budget is the whole story.

The efficacy gate says the opposite. `dispatch$1` is **607 B and refused for `hot method too big` in
both logs**: 600 is below 607, so the flag never reached the method the hypothesis is about, and 700
was refused for a different reason in experiment 2. The 37 verdicts that did move are almost entirely
the *benchmark's own* closures, `run$57` through `run$67` at 119 B going from 1 inlined site to 3,
plus two `anon$` constructors and `ask`. Every kernel method checked is unchanged.

So a JVM flag made the benchmark's harness code inline better and the row got faster because of that.
**DIS-2 is untested, not confirmed**, and the number its mechanism is worth remains the replicated
-6.8%. See `bench-results/dis2/RESULT.md`.

One defect out of it: the compilation log is XML, so a constructor arrived as `&lt;init&gt;` while
every other tool in the ladder prints `<init>`, and those names cross-referenced against none of them,
silently. **264 checks green.**

## C1 refuted, for the cost of reading a profile already on disk

Third in the run order and the plan's own "single cheapest decisive candidate". Also owner-gated: it
needs an explicit `Nested(...)` spelling the skill forbids without sign-off. It needed neither the
edit nor the sign-off.

Its hypothesis is that the `Nested` box exists only because the park arm stores the incoming union.
The collapsed allocation view, which the harness could not produce until this week, says **all 3,790
`Nested` samples on the row, 100%, come from one stack**: `loop$9` → `ProtoKernelBench$.boxed` →
`Nested.nest` → `Nested$.apply`. That is the *benchmark's own* boxing at the lift boundary. Zero
samples reach any park arm, so the eight edits C1 proposes would remove nothing on this row.

Limits stated: one row only (C1 also names the boxing rows, unprofiled), and the inlining confound
applies as always, though a four-deep stack landing in the benchmark's own `boxed` is not a placement
a park arm could be mistaken for. See `bench-results/c1/RESULT.md`.

It forced one tool fix: the first attribution named `Nested$.apply`, a type's own factory, which is
where every instance of it is allocated and decides nothing. The report now names the first frame
outside the allocated type's own code as well. **266 checks green.**

## Three more candidates read from evidence already on disk

No run spent. See `bench-results/premises/RESULT.md`.

- **IN-1's stated field is dead.** `Arrow$Identity$::apply` is 92 B and *inlined at all 25 sites*, so
  there is no refusal for a fast/slow split to flip and the candidate's own falsifier is satisfied
  before the edit is written. Not a refutation of the candidate, a refutation of the field it chose:
  the effect worth looking for is whether freeing 86 B in 25 callers lets something else in.
- **IN-3's headline number is not checkable against this instrument.** It claims one
  `AtomicReferenceArray.get` force-inlines 434 B; the log reports that callee at 12 B. Both can be
  true, since 434 is the transitive VarHandle expansion the log does not show. Its second field,
  `javap` of the hot unit, is the right one. Also owner-gated.
- **IN-2's premise holds and its targets are ranked.** `Stack::truncate` is 52 B against the 35 B cold
  budget refused at 2/4 sites, `Stack::grow` 55 B refused at 4/4. One correction: the candidate names
  `Eval.dump`, which is not in the ranking at all, and omits `Stack::grow`, which is the worse of the
  two by ratio.

And the finding that belongs to no candidate: the method ranked **first**, above every kernel method,
is `ProtoKernelBench::run$56` at 379 B refused 10/10. It is the benchmark's own closure, the same
family the FreqInlineSize flag moved for a 17.4% score change that had nothing to do with the kernel.
Two independent readings now say a material share of these rows is the benchmark's own generated code.

## C4 is FIXED, and the harness caught me contaminating its own measurement

Owner opened an explicit exception to the tooling focus to fix this kernel bug and watch the tooling
being used on it.

**Reproduced, then fixed, 128 tests green.** Red first for the right reason: 50 throws escaping a root
`eval` lost exactly 50 of 512 depth, one per throw, permanently, on a slot that is per thread. After
the fix the same test reports `lost 0`.

The fix is one `try`/`finally` at `Eval.apply`, matching what `Eval.partial` already carries. The
reasoning that makes it sufficient, which took two wrong turns to reach:

- `Safepoint.exit` is unprotected in all eight delivery arms **by design**, and does not need
  protecting. Every normal path balances its pairs, *including a drained budget*: the arm that parks
  never completed an `enter`, and every frame that did runs its `exit` as the parked value propagates
  up. So only an exception can skip an `exit`.
- All eight catch sites in `Eval.scala` rethrow, so no exception is absorbed mid-drive and every one
  reaches a boundary. There are exactly two boundaries and only one was guarded. **That asymmetry was
  the entire bug.**
- `save` not `peek`, so a nested eval gets its own budget rather than inheriting a drained one.

Two wrong turns, both recorded in `bench-results/c4/RESULT.md` because both were plausible: `peek`
instead of `save`, and a reset on every trampoline iteration (an unconditional write on the kernel's
hottest loop, correcting a drift that cannot happen).

**The tooling finding, which is the point of the exercise.** The first bracket failed with
`⛔ leg control-2 sources changed mid-run`. That is the harness's own guard, firing correctly, because
I was editing kernel sources in the throwaway worktree while a measurement was in flight. It refused
to produce a number from a tree that changed underneath it, which is exactly the corruption it exists
to prevent, and it caught the operator rather than a hypothetical one. Re-run in flight against the
committed fix (`9685c9b445` against `2fc76b9cc6`), store `bench-results/c4-store2`.

The other half of the finding stands from before: **the harness has no path for a candidate whose
deliverable is a failing test.** The diagnosis and the fix came from reading and from sbt; the tool
contributed the guard above and will contribute the cost measurement, and nothing else.

### How C4 was diagnosed, before the reproduction existed

Superseded by the section above, which carries the outcome. Kept for the reasoning, and read as
history rather than as state.

The only candidate whose deliverable is a failing test, in the list to see whether the harness can
handle one. It cannot: every shape it models ends in a Run, a Comparison and a Verdict over benchmark
rows, and a failing test produces none of those. The *symptom* is measurable though, since a leaked
depth forces parks and parks allocate, so `gc.alloc.rate.norm` could carry the confirmation even
though nothing in the harness carries the diagnosis.

The candidate says `Safepoint.exit` is unguarded at the eight delivery arms. It is, and that is by
design, confirmed by the owner: a `try`/`finally` at each would put an exception handler on the
hottest path in the kernel. The design puts one guard at the boundary instead, and `Eval.partial` has
it: `save` / `arm` / `try` / `finally restore`.

The hole is that the **root** entry does not:

    def apply[A](v: A < Any): A =
        Nested.unnest[A](loop(v, armed = false, neverStop))

Every top-level `.eval` goes through that. A throw escaping it unwinds past every skipped
`Safepoint.exit` with nothing restoring the depth, and the slot is per thread, so the loss is paid by
later unrelated computations on that thread. No wrong answers, just a thread that parks more and
allocates more forever after, which is how this stays hidden.

**Diagnosed by reading, so it is a hypothesis until the reproduction fails for the right reason.**
See `bench-results/c4/RESULT.md`.

## IN-2 refuted, and more strongly than its own analysis claimed

No run spent; both readings came from evidence already stored. See `bench-results/in2/RESULT.md`.

IN-2 predicted it would change no score, and that prediction is right. Its reasoning is not, on three
counts. **`Eval.dump` has nothing to fix**: 92 B, inlined at both sites, so its falsifier is satisfied
before any edit. **"Both already inline hot" is wrong for the other target**: `Stack::truncate` is
refused at 2 of 4 sites for size. **The method with the strongest refusal is not in the candidate at
all**: `Stack::grow`, 55 B, refused 4 of 4.

Then the CPU profile settles it in one line: **no `Stack` or `dump` frame is sampled at all**, in 28
frames. A refusal costs only when the call happens, so a method the profile never reaches cannot be
worth an inlining verdict however far over budget it sits. The JIT says the same from its side, having
refused the 7-byte `Stack::apply` at 4 of 5 sites for *low call site frequency* rather than for size.

Limits stated: one row profiled, the log carries no invocation counts for these callees, and a
28-frame profile is directional only.

**Third independent reading of the same underlying finding**: 29.1% of this row is `boxToInteger` and
the next three frames, 41.4% more, are the benchmark's own generated methods. 70% of the profile is
code no kernel change touches.

## Phase 7 is done, and the dead code was hiding a real one

`MinCpuSamples` was declared and never read. `NoiseShare` was a case class nothing ever constructed.
`bench-harness-qa.md` was already gone.

The third item was not dead code but a **duplicate**: `bracketPlan` builds the C V C V C ordering and
`bracket` built the same ordering again inline, so the test pinning the ordering covered a function
the runner did not call, and the ordering that actually ran was pinned by nothing. `bracketPlan` is
now generic in what a leg measures and both call it.

The README drift was real too: it described the throwaway-worktree guard as comparing git dirs, which
is what the code did before it was changed to require a detached HEAD, and the reason for that change
(a git-dir comparison catches only the *primary* worktree, while the tree that must not be scribbled
on is whichever one work happens in) had not reached the document. Corrected, along with the four
commands the README never listed and the four newer refusals.

## Phase 6 Tier B is done: a pair is now told what it cannot say

A bracket measures the difference between two trees. Attributing that difference to one change inside
it requires the partition to have been declared, and a pair does not declare one. Every two-sha
comparison now says so in the report: the diff may contain any number of changes, so a sentence of the
form "this moved because of change X" is not supported by anything in it, however plausible X is.

A configuration comparison, the same sha under two sets of JVM args, has nothing to partition and gets
no note. A step of a declared chain gets the opposite note, that its delta *is* isolated.

`bench chain` runs three or more shas, legs interleaved across shas rather than grouped so machine
drift does not land entirely in one step, and renders each adjacent pair as its own replicated
comparison. It refuses a pair, and refuses a repeated sha, before the worktree is touched at all:
verified against a nonexistent worktree path, which it never looked at.

This is the skill's own worked example made mechanical. A node-layout change and a currency hoist
shipped together, the bundle was faster, the win was credited first to one and then to the other, and
both stories were wrong as told. **284 checks green.**

## Defect 9's mitigation is wired in: a dirty A/A null now stops the session

The null already ran on every bracket, so "runs on request" was not the gap. The gap was that its
failure was a printed line with a cross on it, and the process exited 0. Every row an A/A null
classifies is a false positive by construction, so a dirty null is the strongest available statement
that the session is unreadable, and it was the one statement the tool made in passing.

It is now a blocker, in the same banner and behind the same exit code as an unsettled leg, naming the
rows it falsely classified. A session with too few control legs to run a null at all is blocked too,
for a stronger reason: it cannot check itself. **274 checks green.**

## Validation against the manual work: COMPLETE

Three-way comparison of raw data, tool verdict, and recorded claim, per experiment. The raw data was
the arbiter; two of three agreeing settled nothing.

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
- Phase 5 (allocation attribution), Phase 6 Tier A (the investigator), the CLI QA, and the
  never-exercised guards. **324 checks green across eight suites** is the current figure; the counts
  quoted inside the sections above are the counts as of those steps and are left as written.
- Experiment data force-added to git; `.gitignore` had a global `*.json` hiding all of it.

## How defect 8 was closed, and the wrong first version of the fix

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
- **Twenty-nine tool defects** in `tool-defects.md`: twenty-five fixed, one bounded, one an observation, one superseded.

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

## The one tool defect still open

**9, synthetic validation agrees with the code's blind spots.** Not fully fixable: every fixture is
written by the same understanding that wrote the code. The mitigation is the A/A null, whose input is
not authored, and it has earned that keep twice: catching a missing floor that 31 unit tests and a
simulation-backed review missed, and catching a forecast that would have talked me out of the
campaign's best measurement. **It still runs on request rather than on every session, and that is the
open part.**

### Closed, kept for the reasoning (defects 6 and 8)

**8, resolution reported only after spending the session.** *Fixed by `BenchPlan`.* A five-leg session at one fork resolves
to ±5.77%, so it cannot see the +4.5% regression this campaign is about, and that was discovered by
running it and reading the footer. Everything needed to say so first is already stored: any prior run
on the same rows carries each leg's `scoreError`. A `bench plan` subcommand should take the intended
configuration and a target effect size, and answer whether the configuration can resolve it, refusing
or warning when it cannot. Calibration measured earlier says forks will not help, since the variance
is between legs rather than within them, so the answer will usually be "more legs" and it should say
that rather than leaving the operator to guess.

**6, a malformed JVM flag produces a normal-looking run.** *Fixed: a rejected compile command fails
the leg.* Two runs were spent before a log line
revealed `CompileCommand: An error occurred during parsing`. The harness must assert that a compile
command it issued was parsed, and prefer the file form, which does not have to survive shell and sbt
quoting. Without this, Phase 6's Tier A would silently test nothing while reporting refutations.

## Standing constraints

No kernel source landed. Candidates measured in the detached throwaway worktree only. No `inline`
without approval. No PR interaction. Commits under the owner's identity, no attribution.
