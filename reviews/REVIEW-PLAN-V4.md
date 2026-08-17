# Held-out review: IMPROVEMENT-PLAN.md (v4)

Read-only. Nothing executed: no scala-cli, no sbt, no benchmark, no decode. Every number below was
re-derived by me from the artifacts with python and grep.

**Note on overlap.** After finishing my analysis I found `reviews/RESULT-DRIVEN-DESIGN.md`, committed
at `f46737873e` while this review ran, written deliberately before reading it. It reaches my two
headline conclusions independently and in nearly the same words ("the report after v4 is the same
shape with fewer wrong numbers"; item 4 is over-hedged). I state that agreement briefly in Q1/Q2 and
spend the rest of this review on what survives that document.

---

## Verdict

v4 is an accurate defect list and a poor delivery plan for the thing it was commissioned to deliver.
Its numbers now hold up: I attacked six of them and broke none. Its *ordering* front-loads the work
that does not serve the founding requirement and schedules the two items that do (3 and 2) last, and
its acceptance gates appear in no position in the order at all.

The most consequential technical finding I have that neither v4 nor RESULT-DRIVEN-DESIGN.md holds:
**the CPU partition that item 4 spends its entire rework on is sampled at roughly 93 samples per leg,
and is not stable to the precision it is printed at.** On the only two Full legs in the store, whose
scores agree to 0.3%, the kernel share reads **7.5%** and **15.2%**.

---

## Q1. Does v4 deliver the founding requirement?

**Partially, in two of twelve items, scheduled last.** Not "the same shape with fewer wrong numbers"
in full, which would be unfair to items 2 and 3, but much nearer that than to the ask.

What an operator receives today, from the actual artifacts:

| surface | today |
|---|---|
| `bench run` (up to 4 JMH invocations) | **one line** (`Cli.scala:178`) |
| `bench show` | **five lines** (`Cli.scala:393-397`) |
| `bench compare` / `bracket` / `chain` | `Report.render`, 19 conditional sections (`Store.scala:220-458`) |

`Report.render` is already substantially result-driven, which v4 correctly conceded and v3 did not:
`jitChanges` fires only on unanimous verdict flips, `deoptShift` only on reasons that moved past a
threshold, `polymorphic` only on sites the JIT actually profiled, `allocSites` only on sites whose
sample count moved by a quarter of the larger side, `allocNote` only on rows whose timing did not
resolve, `ladder` only on unexplained rows, `investigation` only on regressed/winning/allocating rows.

What changes after v4 executes in full:

- **New surface:** item 3 gives `run` and `show` a real render. Real gain, and it is the thesis's own
  first half.
- **New selection:** item 2, scoped to one line (the unexplained-row line carrying its own evidence).
- **Everything else** corrects or annotates output that already prints: A/A null in `compare` (5),
  the threshold's binding term (6), the partition shares (4), efficacy on configuration pairs (1),
  correct `thrpt` classification (12), ingest fidelity (11), conservation (9), drift (10).

So: **no item in v4 changes which evidence the report reaches for as a function of the result**,
except item 2's one line. The section set goes from 19 to about 20. That is the finding that matters
most, and RESULT-DRIVEN-DESIGN.md now states it too.

Two additional gaps in item 3 itself:

1. **Item 3 names `run`, `show` and `bracket`. It does not name `compare` or `chain`.** `compare` is
   the path that re-reads stored legs, i.e. the path where you did not watch the measurement happen
   and the trust fields (`coverage`, `treeHash`, `warmup`) matter most. Every stored artifact in
   `bench-results/` was read back this way.
2. **Item 8 is the one sanctioned new subcommand, and a subcommand is what the founding requirement
   calls a failure.** `bench adjudicate` is a command the operator must remember to run at exactly the
   moment they are least likely to: after an isolation leg lands. `Investigate.adjudicate`
   (`Investigate.scala:259`) needs `(falsifier, row, baseline, isolation, target)`; `BenchCompare`
   holds the store and both runs, and a configuration comparison is already detectable
   (`Store.scala:169`, same sha, differing `jvmArgs`). The adjudication belongs in the report that
   renders when the isolation leg is compared, not behind a verb.

---

## Q2. Has v4 overcorrected?

### Item 4: yes, and it also under-corrects in a way nobody has caught

**Confirmed first.** I re-derived item 4's raw figures from `qa-artifacts/qa-cpu.txt` (28 frames):

    kernel  (kyo.kernel.proto.*)  16.05%
    bench   (kyo.kernel.bench.*)  48.37%
    other                         35.57%   (boxToInteger alone is 29.07%)
    noiseShare as printed today   83.95%

`Bench.cpuPartition` (`Bench.scala:565-574`) works as written: the profiler's frames carry full
package names (`kyo.kernel.bench.ProtoKernelBench.loop$9`), so both `startsWith` predicates match.

**Where I think v4 is wrong to refuse the inference.** "State the partition, not the inference"
produces three numbers and no help. Two statements are both defensible and useful:

- *The largest single frame in this profile is `scala.runtime.BoxesRunTime.boxToInteger` at 29%*, which
  is neither kernel nor benchmark logic. That is a fact, it is the biggest lever in the table, and no
  version of the note says it.
- *The kernel share is a lower bound, not a ceiling.* Kernel code inlined into `loop$9` is credited to
  `loop$9`. Naming the direction of the bias is defensible without settling v4's open question, and it
  is the sentence that stops a reader treating 16% as a ceiling. RESULT-DRIVEN-DESIGN.md's form ("it
  moves the 48% only by changing how often those closures run") is the same move and is better than
  three bare numbers.

**The thing neither document has: the partition has no denominator.**

`profile()` runs `-f 1 -wi 10 -i 1` (`Bench.scala:436`), so `Run.cpu` is **one second of itimer
sampling per row**. Measured on the two stored Full legs, which measure the same row and agree to
0.3% (6.148 vs 6.167 us/op):

    control   total 930,000,000 ns = 93 samples   kernel  7.5%  bench 46.2%  other 46.2%
    variant   total 920,000,000 ns = 92 samples   kernel 15.2%  bench 45.7%  other 39.1%

The kernel share **doubles between two legs of identical-to-0.3% code**. One sample is 1.1 points.
`qa-cpu.txt` reaches 461 samples only because it used JMH's default `-i 5`.

This matters three ways:

1. Item 4's proposed output prints whole-percent shares from a sample of ~93, with no count. The
   codebase's own rule is `Model.scala:118`: *"A verdict is only meaningful with its denominator, so
   the denominator is carried."* `InlineSites` carries it, `ParseCoverage` carries it, `CallMorphism`
   carries it. Item 4's output would be the one place that does not.
2. It rules out the obvious "more useful" upgrade. I costed a per-frame *share shift* between the two
   legs (the result-driven form of the same data, mirroring `allocSites`); at n=93 the largest entry
   is −12.8 points on `loop$9` between two legs that did not move. That table would be noise. Do not
   build it at this sample count.
3. `Run.cpu` costs a **whole extra JMH invocation per leg** (open defect 42) and buys ~93 samples.
   Raising `-i` on that invocation is a one-token change on a run already being paid for, and it is
   worth more to the founding requirement than most of v4's items. By contrast the *allocation*
   profile from the same ladder carries **3,790 samples** on the top class
   (`qa-artifacts/qa-alloc-flat-real.txt`), which is dense enough to support exactly the per-method
   attribution v4 leaves behind step 0a.

**Item 4's stated fix does not address its own problem 2.** The item lists three problems and its fix
("report three shares and let the reader infer") addresses 1 and 3. Problem 2 is the `n < 25.0` gate
being unreachable in the other direction; nothing in the fix says what the new gate is.
RESULT-DRIVEN-DESIGN.md's answer (print it always as a fact, reserve the warning form for when the
kernel share actually bounds the deltas) is right and should be folded into the item.

**Item 4's "Largest contributors" list is computed by the wrong function.** The proposed line reads
"...36% boxing and infrastructure. Largest contributors: ...", and the only frame lister is
`Bench.noiseFrames` (`Bench.scala:577-585`), which ranks **non-kernel** frames. On this profile it
returns `boxToInteger`, `loop$9`, `run$39`: two of the three belong to the 48% bucket, listed under a
sentence about the 36% bucket. Either partition the frame list by bucket or move the list.

**Item 4 is not free-standing.** v4 puts it in "start now". Its render-side acceptance needs a
decodable Full pair, and per 0b neither stored Full run decodes. The function is unit-testable today
(`LogCompilationTest.scala:245` already exercises `noiseShare` against a real capture); the *note* is
not renderable until 0b lands.

### Other hedges

- **Item 1's "delete the `Parsed`-based pair" is an overcorrection in the other direction.**
  `LogCompilation.diffVerdicts` (`:344-346`) calls `inlining(before)`/`inlining(after)`; its real
  dependency is `Chunk[InlineSites]`, which **`Run` already carries**. The choice is not "wire the Run
  one or delete the Parsed one" - it is *re-type the Parsed one over `Chunk[InlineSites]`*, at which
  point it runs on stored runs. Deleting it discards `VerdictChange`, a persisted `Model` type whose
  scaladoc (`Model.scala:154-160`) states it is exactly what proving a flag took requires, and the
  "names *what* moved" property item 1's own rationale cites. `Investigate.efficacy` returns
  `Maybe[String]` (did it take / did it spill); `diffVerdicts` returns the named list. The report wants
  both.

- **The anti-goals are not what is over-tight.** "No new statistics" blocks nothing I would recommend
  (everything below reuses existing tested functions). "No new subcommands" is the right instinct and
  v4 breaks it itself, at item 8, for the one thing that should have been pushed rather than
  commanded. I would make it absolute and re-scope item 8 into the report.

---

## Q3. Completeness: what is missing that the founding requirement needs

### 1. The verdict line prints a green all-clear over a measured, exact regression

From a real stored run (`bench-results/sweep-replicated.log`), current tool, unedited:

    | ⚪ | `trailingMapsStayLinear` | avgt | 5 | 315.3 ± 24.14 | 451.6 ± 24.14 | +43.2% | +239977 | - |
    ℹ️  Allocation moved on rows whose timing did not resolve...
      - trailingMapsStayLinear +239977 B/op, timing +43.2% (Flat)
    🟢 No row regressed beyond the drift band, across the whole class.

`allocMoved` is computed at `Store.scala:364-365` and `verdictLine` at `:421-434` does not read it:
`reds` is `verdict == Regressed` only. So the headline the operator takes away contradicts the note
fifteen lines above it, on a quarter-megabyte-per-operation change the report itself calls "exact and
per-operation ... a real change regardless of what the timing could or could not show".
RESULT-DRIVEN-DESIGN.md marks this row of its own table "rendered". The *note* is rendered; the
*verdict* is wrong. One branch, free-standing, and it is the single highest-consequence line in the
whole output.

### 2. `Plan.forecast` never reaches a report, and `BenchPlan`'s standing advice is backwards

`Plan.forecast(priors: Chunk[Run], legs, familyAlpha)` (`Plan.scala:54`) is the tool's existing answer
to "this row did not resolve, now what". It takes exactly what a `Comparison` holds. It is reachable
only through `bench plan`, a command the operator must remember to run. That is the founding
requirement's named failure mode, and it is not in v4 and not in the selection table.

Worse, the advice it prints is inverted for half the rows. I reproduced item 6's statistic
independently over `bench-results/sweep-replicated/runs` (alpha 0.05/15, df 3, t=8.5752), confirming
v4 to the digit: **7 of 15 rows are floor-bound**, `handleLoopAnswersInPlace` at **−10.72%** with a
**14.43%** threshold set entirely by own error against an **8.20%** spread term. The threshold is
`max(t·se, ownError·mean)` (`Stats.scala:160`). More legs shrink only `t·se`. So on those 7 rows
**more legs cannot move the threshold at all**, and `Cli.scala:295-296` tells the operator:

    More forks will not help: calibration showed tripling them moved the resolution by 0.05 points,
    because the variance is between legs rather than within them. More legs, or a quieter machine.

That generalization came from a row where the spread term dominated. The same calibration recorded
(`Plan.scala:15-17`) that tripling forks **cut each leg's own error six-fold** - which is precisely
the term that binds those 7 rows. `WORK.md:220` already notes "halve the legs' own error and the row
resolves as a win". So the tool holds the mechanism, states the inference, and states it the wrong way
round for roughly half its rows. Not a numbered defect (the ledger runs to 52) and not a plan item.

Item 6 gives which term bound the threshold. The remedy follows from that term, differs by term, and
is computable from `Plan.forecast` with no new statistics.

### 3. Eight stranded result-driven selectors have no item, only an unscheduled gate

v4's own corrected count is twelve. Its items claim four: `Investigate.efficacy`/`adjudicate` (1, 8),
`allocConservation` (9), `commonMode`/`residual` (10), plus `flatButUnbounded` (0g). Verified by grep,
these have **no item in v4** and stand behind Gate A alone:

| selector | status (verified) | ledger |
|---|---|---|
| `Bench.actionableJit` (`:40`) | **one reference repo-wide, its own definition** | defect 34 |
| `Bench.jitUnstable` (`:852`) | sole caller `BenchTest.scala:91`; its scaladoc claims it *is* "reported separately" | - |
| `LogCompilation.budgetCandidates` (`:371`) | test-only | - |
| `LogCompilation.unprofiledSites` (`:375`) | no caller | - |
| `LogCompilation.diffVerdicts` / `efficacy` (`:344`, `:361`) | test-only | defect 35 |
| `Bytecode.verifyAgainst` (`:149`) | no caller | - |
| `Bytecode.diff` / `Change.crossedBudget` (`:121`, `:104`) | test-only | - |

Two of these are the answers to entries 3 and 2 of the tool's own founding ledger
(`tool-defects.md:19-27`: "Finding the 379-byte method... nothing ranks refusals by whether they sit
near a budget"; "The efficacy gate... 40 lines of throwaway python"). `nearBudget` is the one wired
selector, and it is wired only through `Investigate.falsifiers`, which requires `regressed.nonEmpty`
(`Investigate.scala:121`). **On a flat or winning report, a method sitting 379B against a 325B budget
is invisible.** The selection is gated on a regression existing, not on the datum being informative.

**And Gates A and B appear nowhere in v4's ordering.** The acceptance section defines them; the
Ordering section lists items 4, 9, 10, 12, 11, then step 0, then 6, 5, then 1, 2, 3, 8. Neither gate is
scheduled. A gate that is never run is the allowlist by another route.

---

## Q4. Will the described changes work against the real code?

### F-1. Item 1's mechanism does not survive contact (blocking)

> "needs `jvmArgs` on ingest (F11) since that is the only route by which it can learn which method a
> `CompileCommandFile` instructed."

`jvmArgs` carries a **path**, not a method. From the stored run rendered in
`bench-results/sweep-replicated.log`:

    Same sha, so this is a configuration comparison and the difference is exactly:
      -XX:CompileCommandFile=/Users/.../bench-results/forced-inline.cmd

and `forced-inline.cmd` contains, in a file the `Run` does not carry:

    inline kyo/kernel/proto/Eval$.dispatch$1

The `-XX:CompileCommand=inline,pkg.Class::method` inline form would carry it, but `Bench.scala:386-389`
**instructs the operator to use `CompileCommandFile` instead** ("which does not have to survive shell
and sbt quoting"), after two runs were lost to the other form. So on the harness's own mandated path
the method is never recoverable from `jvmArgs`.

This also breaks the "legs differ in `jvmArgs`" row of RESULT-DRIVEN-DESIGN.md's selection table.
Fix: `runLeg` should read any `CompileCommandFile` argument and store its **contents** beside
`jvmArgs`. Small, and item 1's acceptance depends on it.

### F-2. Item 12 is under-scoped in the plan I was given

`Bench.compare:615` is cited; `Stats.classify` (`Stats.scala:171`) has the identical sign defect and is
the classifier the replicated path uses, i.e. every `bracket` and every multi-leg `compare`. Fixing one
leaves the other backwards. *(Already corrected at `c6cfe5e12b`, after v4 was written. Two addenda that
are still not covered: `Investigate.Quantity.Time` (`Investigate.scala:218`) reads `r.score` raw and is
equally mode-blind, so an adjudication of a `thrpt` row is backwards too; and `Bench.compare:614`'s
`c.score <= 0.0` guard assumes a time-like quantity.)*

### F-3. 0a needs a stale-artifact guard, and one of them is a live defect today

Once `-XX:LogFile` gains a `%p` discriminator, the harness globs for a **set** of files. Nothing
deletes them between legs. `measureWith` already learned this exact lesson for the results json
(`Bench.scala:399-404`, `json.removeExisting` with a five-line comment on why), and 0a reintroduces the
condition it guards against.

The same hole exists **today** for allocation: `allocDir = worktree / s"alloc-$label"`
(`Bench.scala:432`), `collapsedFiles` reads every `collapsed-*.csv` under it (`:441-451`), and nothing
clears the directory. A repeated label in one worktree (`bench run --label control` twice, or a second
bracket, whose labels are always `control-1`, `variant-1`, ...) concatenates a previous leg's collapsed
dumps into this leg's `byMethod`. Found by reading; **not reproduced** - I ran nothing. If it holds it
belongs in the ledger, and `allocConservation` (item 9) is the check that would catch it, which is a
second argument for item 9.

### F-4. "Once each fork writes its own log, the row key is the filename" is imprecise

The filename gives the *partition*; naming the row still requires the stub task inside the file.
Verified on `bench-results/exp1/logc-new-default.xml`: the only task naming a row is

    kyo.kernel.bench.jmh_generated.ProtoKernelBench_continuationBodiesFuse_jmhTest
      continuationBodiesFuse_avgt_jmhStub (...)

So `Task.method` (`LogCompilation.scala:170`) is still needed after 0a - once per file rather than per
verdict. Item 2's re-decision should say that, not that the join is discarded.

### F-5. Item 11's `stillCompiling` denominator has three fabricated terms, not one

`run.forks * MeasureIterations * IterationSeconds * 1000.0` (`Bench.scala:594`). `MeasureIterations`
and `IterationSeconds` are the harness's own constants; the JMH json carries `measurementIterations`
and `measurementTime` per entry (verified in both `ab-head.json` and `exp6/base-wi25.json`). They
happen to be 5 and "1 s" in both, so this is latent, but an ingested json from any other configuration
mis-scales the steady-state guard by the ratio. The same jsons also carry `jdkVersion`, `vmName` and
`vmVersion`, while `Ingest` writes `Session(..., "unknown", 0.0)` (`Cli.scala:238`) and comparing legs
across JDKs is unchecked.

---

## Q5. Ordering

v4's order front-loads free-standing defect fixes and puts the founding requirement last. I would
invert it. Proposed:

1. **0b first, not fourth.** It is the gate on *every* Full-leg render, including item 4's own
   acceptance, and it holds a live undefaulted-field defect (`coverage`, `Model.scala:307`) that will
   break the store again the next time a field is added. Nothing else can be validated end to end
   against real Full data until it lands.
2. **Item 3, then the verdict-line fix (Q3.1), then the "absence names its remedy" enumeration**
   (RESULT-DRIVEN-DESIGN.md's last section). These are the founding requirement. Item 3 depends on
   nothing - `coverage`, `warmup`, `treeHash` are present on all 47 Timing runs in the store as well as
   the 2 Full ones - and it is the only item that changes what `bench run` prints after four JMH
   invocations. Extend its scope to `compare` and `chain`.
3. **Item 12** (correctness; a backwards verdict is worse than a missing one), **item 6** (it is the
   input to Q3.2's remedy), then **Q3.2** (wire `Plan.forecast`, fix the inverted advice).
4. **0a + F13/F14 + item 9**, then **items 1 and 2**, then **8 as a report section rather than a
   command**.
5. **Items 10, 11, 4, 0d-0g** last. They are real and small. Item 4 in particular should not lead the
   plan: its subject is the sparsest evidence the harness collects.
6. **Schedule Gate A explicitly**, with the eight stranded selectors of Q3.3 enumerated as its work
   items rather than left to the gate to discover.

The reordering rests on one argument: after step (2) the operator gets useful output on the very next
run, and every later item improves an output they are already reading. Under v4's order they get
nothing new until items 3 and 2, at the end.

---

## Confirmed: claims I attacked and could not break

- **Item 2's attribution numbers, exactly.** Independently re-derived from
  `bench-results/exp1/logc-new-default.xml` by splitting on `<task ` boundaries and defaulting a
  missing `level` to 4: **1,117 C2 verdicts**, row-own `jmhStub` **99 (8.9%)**, benchmark's own code
  **625 (56.0%)**, row-attributable **724 (64.8%)**, kernel 157, JDK/JMH 236. Also confirmed 946 tasks,
  837 carrying `level` (672 at 3, 154 at 1, 11 at 2), 109 with none. The 8.9%-vs-2.2% retraction in
  ORACLES §2 is correct.
- **Item 6, to the digit.** Re-implementing `Stats.threshold` over
  `bench-results/sweep-replicated/runs`: alpha 0.00333, df 3, t 8.5752, **7 of 15 floor-bound**,
  `handleLoopAnswersInPlace` −10.72% / ±14.43% / spread 8.20%. Also
  `emittingClausesPayRegionRebuild` −12.46% against ±12.63%, which misses resolution by 0.17 points.
- **0b's census.** 49 stored runs, 47 Timing, 2 Full. Both Full runs' `jit_metrics` carry
  `[c2Tasks, deopts, lastCompileAt, msInWindow, msTotal, recompiled, tasks]`: missing `osrTasks`,
  `plantedTraps`, `madeNotEntrant`, and carrying an obsolete `deopts`. Both lack `coverage` (which has
  no default) and carry `jit` in the old flat `JitEntry` shape. `jvmArgs` missing from 44 runs and
  `allocByMethod` from 39, both defaulted and therefore harmless.
- **F3's correction.** `coverage`, `treeHash` and `warmup` have exactly one production reference each,
  all of them writes (`Bench.scala:515` for coverage; none for the other two). Three fields, not 14.
- **Item 4's raw figures**, above, and that `cpuPartition`'s `kyo.kernel.bench.` prefix genuinely
  matches the profiler's frame names.
- **0e's caveat.** The QA store's two runs carry `driftPercent = 3.950238833244396`, so `Store.session`
  really can recover a real value.
- **0f.** `QaEndToEnd.check` prints and returns `Unit` (`:17-18`); `check("drift measured, not
  assumed", session.driftPercent > 0.0)` is false by construction and exits 0.
- **Item 5's blocker.** `nullComparison` returns empty below three controls (`Bench.scala:817`) and
  `nullBlockers` turns empty into a blocker (`Store.scala:141-145`) that `Cli.scala:223` exits on.
  Wired as-is, every single-pair `compare` and every default `chain` (2 legs/sha) fails.
- **Defect 43, live.** The stored `sweep-replicated.log` prints "check ... the inlining log" on a run
  whose header says "timing only" three lines above.
- **The `mode` column** is already displayed and `Row.unit` is read nowhere, as item 12 says.

## What I could not verify

- I executed nothing. No sbt, no scala-cli, no benchmark, no decode; the two Full runs' failure to load
  is inferred from their stored keys against `Model.scala`, not watched.
- **0a end to end.** `%p` expansion in `-XX:LogFile` and JMH's per-fork application of
  `-jvmArgsAppend` are reasoned about, not run. I did confirm that `exp1`'s log names exactly one
  benchmark row and was a single-row invocation, so it cannot distinguish truncation from having had
  one row - task 20 remains the only thing that settles it.
- **The `allocDir` accumulation defect (F-3)** is read from the source, not reproduced.
- **Whether itimer credits inlined callees to the inlining frame.** Still open. My sampling finding
  makes it less load-bearing than v4 assumes: at n=93 the sampling error swamps the attribution
  question either way.
- I did not re-run the harness's own test mains, nor check `Bytecode.parse` against `javap` output.
