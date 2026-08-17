# Held-out review: IMPROVEMENT-PLAN.md (v3)

Read-only review. Nothing executed: no scala-cli, no sbt, no benchmark, no decode. Every number below
was re-derived by me from the artifacts with python and grep, not taken from either prior report.

**Line-number note.** Citations into `Bench.scala` and `Store.scala` are from the tree at `64e0e57e4d`,
immediately before commits `bbaf9d0d66` (item 7) and `50a8b73dfd` (item 4). Those commits shift
everything after `Bench.scala:23` by about +8 and after `Bench.scala:538` by about +17; after
`Store.scala:229` by +6 and after `:404` by +10. Where I discuss code those commits changed, I quote
the new text from the commits themselves.

---

## The three inherited figures, adjudicated

### 1. The OSR attribution: the review's 8.9% is right, the correction to 2.2% is wrong

Both are arithmetically correct over different populations. Reproduced on
`bench-results/exp1/logc-new-default.xml`:

    ALL TIERS : 141 of 6329 = 2.2%   (946 tasks)
    C2 ONLY   :  99 of 1117 = 8.9%   (109 tasks)

The 5,212-verdict gap is C1 tier-3 verdicts, which the harness deliberately discards.
`LogCompilation.inlining` filters `p.tasks.filter(_.level >= 4)` at `LogCompilation.scala:272`, and the
rationale is written out twelve lines above at `:254-266`:

> "The tier filter is not a refinement, it is the difference between a signal and noise. In a captured
> run, of 1969 refusals 1929 come from C1 tier 3 and 40 from C2 ... Reporting C1 verdicts as a method's
> inlining behaviour would point every optimization at code the measured score never executes."

So 1,117 is not "some C2/`kyo.`-prefixed subset it did not state"; it is the tier filter that defines
`Run.jit`. Publishing 2.2% describes a population the harness never stores, never renders, and has a
written reason for excluding. ORACLES.md section 2 needs correcting: the denominator was right, and its
only fault was not naming the filter.

There is a better number than either, and it is the one item 2 should quote. `Run.jit` is C2 **and**
`kyo.`-callee (`LogCompilation.scala:272`). Replicating the parser's per-task symbol-table resolution
exactly, on the same log:

    C2 verdicts with kyo. callee (the population Run.jit holds): 545
      under the row's own jmhStub task                         :  69  (12.7%)
      under ProtoKernelBench's own methods                     : 337  (61.8%)
      row-attributable                                         : 406  (74.5%)

### 2. The KnownNoise figure: not a correction, a third classification, and I think it is wrong

No arithmetic dispute. I get 29.07%, `boxToInteger` as the sole match, `loop$9` 17.14%, `run$39`
13.23%, `ask` 11.06%, `kyo.kernel.proto.*` 16.04%. Every figure in ORACLES.md section 3 reproduces.

The dispute is the predicate. "The true non-kernel share is 83.97%" asserts that
`ProtoKernelBench.loop$9`, `run$39`, `ask` and `anon$95.<init>`, together 48.37% of the profile, are
frames "no kernel change can move". `WORK.md` contradicts that on its own page:

> C3 probed: "the second 16 KB/op is `ProtoKernelBench$$anon$95` minted at the benchmark's own `ask`,
> 49.1% of the row. Not refuted, because its claim is about preemption design..."

C3 is a kernel candidate whose entire subject is a `ProtoKernelBench$$anon$95` allocation, and
`Bench.KernelPackage = "kyo.kernel.proto."` now classifies that frame as immovable. The benchmark's
closures are the workload; how often they run and whether they allocate is what the kernel's dispatch
decides. Under the new predicate the ceiling on any kernel change is 16.04% of this profile, and this
repository stores a -17.4% swing from one JVM inlining flag (`bench-results/dis2/RESULT.md:13`,
a different row, so not a strict contradiction, but the shape of evidence that should stop an 84%
immovable partition).

Both 70% and 83.97% are unsupported as "the truth". See finding 5.

### 3. The 94% `InlineSites.bytes` population: confirmed exactly, 887/944 and 904/958.

---

## Findings, most severe first

### F1. Item 2's statistic is right, its interpretation is not, and the correct number changes the item

> "The other 91% are shared methods and belong to no row."

False on the log it cites. `bench-results/exp1/logc-new-default.xml` contains exactly one benchmark
(`ProtoKernelBench_continuationBodiesFuse_jmhTest` is the only stub class in 4 MB). C2 verdicts by
enclosing task:

| enclosing task | verdicts |
|---|---|
| `ProtoKernelBench.…run$56` | 307 |
| `ProtoKernelBench.…run$57` through `run$67` (11 lambdas) | 231 |
| `ProtoKernelBench.…run$67` | 37 |
| `ProtoKernelBench.continuationBodiesFuse` | 31 |
| `ProtoKernelBench.loop$14` | 17 |
| **the benchmark's own code** | **602 (54%)** |
| `jmhStub` | 99 |
| `Eval$.loop`, `dispatch$1`, `Arrow$SuspendWith.apply`, anon | 142 |
| JDK and JMH infrastructure | 274 |

Those 602 are the measured row's body and its lambdas. On the `Run.jit` population the row-attributable
share is **74.5%, not 8.9%**. Both prior documents mislabel them; ORACLES.md repeats it as
"kyo.-rooted (shared) 1988".

Worse, the measurement cannot answer item 2's question. Item 2 is about a whole-class leg; this is a
single-row leg, where attribution is trivial. Per 0b no whole-class Full log exists, so the whole-class
ratio is unmeasured and the number standing in for it comes from the case where the question does not
arise.

### F2. Step 0a's fix subsumes item 2, and the plan does not notice

0a's remedy is "give the path a per-fork discriminator and parse the set". Once each fork writes its own
log, each file is one benchmark, so the row key is the filename and every verdict in the file belongs to
that row's fork. The `Task.method` join item 2 proposes then discards about 90% of what the file already
attributes. The ordering (0a gates 2) is right; the content of item 2 has to be re-decided after 0a, not
merely unblocked. As written the plan ships the weaker of its own two mechanisms.

### F3. The thesis is broader than its evidence

> "14 of the 22 `Run` fields reach no output."

The enumeration that follows names 13 things, of which 5 are `Run` fields (`alloc`, `cpu`, `coverage`,
`treeHash`, `warmup`); the rest are nested fields of other records or not in `Run` at all
(`Task.compileId` is `LogCompilation.Task`). Two of the 5 do reach output: `alloc` at `Cli.scala:397`,
`cpu` through `noiseShare` at `Store.scala:399`. Greps for `.treeHash`, `.warmup`, `.coverage` across the
eleven production sources return write sites only, so **3 fields reach no output**, against a claimed 14.

"Renders almost none of it" is also unfair to `Report.render`. An operator running
`bracket`/`compare`/`chain` receives fifteen conditional sections (`Store.scala:220-448`): provenance
header, per-row table with both scores, errors, per-row resolution, B/op delta and mechanism, warmup-ramp
callouts, resolution footer with df and alpha, inlining flips, deopt shifts, profiled polymorphic sites,
allocation-by-method movement, allocation-on-flat-rows, partition note, verdict, unexplained ladder,
steady-state blocker, nine-row JIT cost table, both-ways warning, noise note, proposed experiments.

Right-sized thesis: **the per-leg evidence has no renderer** (`bench run` prints one line,
`Cli.scala:178`; `bench show` prints five, `:393-397`) **and the comparison renderer withholds exactly
the fields that say whether it can be trusted** (`coverage`, `warmup`, `treeHash`) plus per-row
attribution. That is items 3 and 2, and it ranks them.

### F4. The cost claim in the thesis is wrong

> "Two of them cost a full extra JMH invocation each: `Run.alloc` surfaces as the integer `alloc sites N`"

The alloc invocation is one recording dumped twice (`Bench.scala:420-423`; `:469-473` parses the text
into `alloc` and the collapsed CSVs into `allocByMethod`), and `allocByMethod` is rendered, at
`Store.scala:369-385`. That invocation is not a wasted run producing one integer. Only the `itimer`
invocation has the thin yield described, and even it feeds `noiseShare`.

### F5. Item 4 as shipped: the inversion is right, the partition is not, and the note now always fires

Keep the inversion. A closed set is the correct instinct and the new sentence honestly names what it
measures ("outside `kyo.kernel.proto`"). Three problems remain in `50a8b73dfd`:

1. **The inference did not change and is now louder.** The sentence still ends "so kernel-attributable
   movement is a fraction of each delta above". That does not follow from a frame-name partition, and it
   is now asserted at 84% instead of 29%.
2. **The note fires on every Full leg.** The gate is `n < 25.0`; a benchmark spends most of its samples
   in its own closures and boxing, so the threshold is now unreachable in the other direction. A note
   that always prints carries no information, which is the plan's own complaint about outputs.
3. **The benchmark's own code needs its own bucket.** ORACLES.md computed the three-way split
   (`ProtoKernelBench.*` 48.37%, `kyo.kernel.proto.*` 16.04%, JDK/native 6.53%, boxing 29.07%) and the
   commit collapsed it to two. The three-way version is strictly more informative and does not require
   deciding the contested question: "16% of sampled time is kernel-owned; 48% is the benchmark's own
   closures, which the kernel decides how often to run; 36% is boxing and infrastructure." That is
   checkable, it carries its own evidence, and it does not classify C3's own subject as immovable.

The second half of the commit, naming the largest contributors, is right and is the part that satisfies
the plan's rule.

### F6. Gate B is unsatisfiable on the data that triggers it

`Delta.unexplained` (`Model.scala:365`) requires `mechanism` empty; `mechanism` (`Bench.scala:578-584`,
`:748-754`) is built from exactly an `allocDelta` over 1.0 and the head of `jitShift`. So a row is
unexplained only when `allocDelta` is absent or near zero and `jitShift` is empty. On a Timing leg
`variant.jit` is empty by construction (`Bench.scala:486`, `:506`), and 47 of the 49 stored runs are
Timing (I decoded all 49). The gate asks the report to print a method name from an empty collection, for
precisely the comparisons that produce unexplained rows today. It needs an `Evidence.Full` precondition,
a different datum for the Timing case, and a fixture that does not exist until 0b/0c.

### F7. Gate A is not implementable as specified, and its allowlist is the edit-around

- No test framework (`project.scala` is four `//> using` lines; every test is a `main`). That half is
  fine: `QaGuards` and `QaParsers` are the home.
- "Reachable from a `Cli` entrypoint" needs a call graph. Reflection gives declared members, never call
  sites. The only bytecode reader here is `Bytecode.parse`, which consumes `javap -c -p` instruction
  listings (`Bytecode.scala:74-93`); Scala 3 lambda bodies are reached through `invokedynamic`, whose
  implementation method sits in the `BootstrapMethods` attribute that `-c` does not print. A sound check
  needs `javap -v` plus new parsing, or a source-symbol walk from a declared root set. Either is fine;
  neither is scoped.
- "Public selector" has no mechanical definition. About 144 `def`s in production, 31 `private` markers of
  which 24 are regex vals in `LogCompilation.scala`. A gate over public defs flags every helper; a gate
  over a curated list is the same memory the plan says is the problem.
- The allowlist is the failure mode. The plan claims the gate "fails loudly rather than being edited
  around" one sentence after granting "an explicit allowlist for deliberate exceptions". Make it
  wire-or-delete with no allowlist, which is what item 10 already says for `commonMode`.

### F8. Two of the six "prerequisite-free" items are not

- **Item 5 breaks the two commands it wires.** `nullComparison` returns `Maybe.empty` below three
  controls (`Bench.scala:769`), and `Report.nullBlockers` turns `empty` into a blocker with an exit code
  (`Store.scala:141-145`, `Cli.scala:223-225`). Wired into `compare`, every single-pair re-read fails,
  the case `Cli.scala:373` documents. Wired into `chain`, whose default is 2 legs per sha
  (`Bench.scala:702`), every default chain fails. Item 5 needs a conditional or a default change.
- **Item 6 needs a model change.** Both terms are computed inside `Stats.threshold` (`Stats.scala:160`)
  and discarded there; `Resolution` (`Model.scala:345`) carries only `percent, absolute, df, alpha`.
  "Already computed" is not "available at the render site".

Items 4, 7, 9, 10, 11 are genuinely free-standing. Item 7 as landed is correct: keying on
`_.resolution.exists(_.df > 0)` with `exists` is right, and it matches the branch that already existed
at `Store.scala:269`.

### F9. 0g misdiagnoses a reachable defect as an unreachable one

`flatButUnbounded` is indeed unreachable (`Bench.scala:587` always attaches a `Resolution`;
`Stats.scala:168-171` returns `Flat` only with a Present one). But the warning is not:
`Store.scala:263` counts `d.flatButUnbounded || d.verdict == Verdict.BelowResolution`, reachable via
`bracket --legs 2` (`bracketPlan`, `Bench.scala:624-630`, gives df 0 and an empty threshold at
`Stats.scala:173`). The live defect is the text: "N flat rows carry no resolution" about rows that are
not flat. Deleting the branch as dead removes a reachable, mislabeled warning.

### F10. 0b's diagnosis is incomplete, and it names a test that does not exist

The two Full runs' `jit_metrics` keys are `[c2Tasks, deopts, lastCompileAt, msInWindow, msTotal,
recompiled, tasks]`: missing `osrTasks`, `plantedTraps`, `madeNotEntrant`, carrying an obsolete
`deopts`. They also lack `coverage`, `jvmArgs` and `allocByMethod`. Of those three, `jvmArgs`
(`Model.scala:316`) and `allocByMethod` (`:326`) have defaults and **`coverage` (`:307`) does not.** So
defect 17's original class, a new field with no default, is live in the tree right now, and 0b asks only
for a changed-shape test. Both are needed; the omitted one has already bitten twice.

`StoreSchemaTest` does not exist. The check is inline at `BenchTest.scala:498-511`, covers
`allocByMethod` only, and the phantom name comes from a stale doc comment at `Model.scala:323` that the
plan inherited unchecked.

### F11. Item 11 names the harmless half of the ingest defect

`warmup` is fabricated (`Ingest.scala:56`), verified: `exp6/base-wi25.json` records
`warmupIterations: 25` and the store holds 10. It has zero readers. In the same constructor:

- `forks = 1` (`Ingest.scala:51`) is equally fabricated and the json carries the truth (`ab-head.json`
  has `forks: 3`). `forks` has three readers: the header (`Store.scala:237`), the diagnostic note you
  just rewrote (`:229`), and `stillCompiling`'s measured-window denominator (`Bench.scala:546`), where a
  wrong value mis-scales the steady-state guard.
- `jvmArgs` is discarded entirely, and every JMH json carries it (`ab-head.json` has `--add-opens...`;
  `exp6/base-wi25.json` has `-Xms4g -Xmx4g -XX:+UseG1GC`). It is the whole configuration branch of
  `partitionNote` (`Store.scala:174-183`), so an ingested configuration pair prints "Same sha and no
  recorded JVM arguments on either leg ... the difference is unrecoverable" about a difference sitting in
  the file it just read. It is also the only route by which item 1 could learn which method a
  `CompileCommandFile` instructed, which its acceptance requires.

### F12. Missed live defect: `mode` and `unit` are captured and never checked

Both are parsed (`Bench.scala:225`, `:230`); `mode` is printed (`Store.scala:293`), `unit` nowhere;
neither is used in a verdict. `Bench.compare` calls a negative percentage `Faster` unconditionally
(`Bench.scala:567`), so a `thrpt` row is classified backwards and two legs in different time units
produce a percentage off by the unit ratio. The harness's own benchmark is
`@BenchmarkMode(Array(Mode.AverageTime))`; the exposure is `bench ingest`, which accepts arbitrary JMH
json (`Cli.scala:236-269`) with no check on either field.

### F13. Missed: the multi-row break also hits the allocation views, and item 3 would render it

`parseAlloc` runs over the whole sbt stdout, which for a whole-class leg holds one flat table per
benchmark, so `Run.alloc` carries duplicate `cls` rows; `apportion`'s `flat.map(a => a.cls -> a).toMap`
(`Bench.scala:304`) is last-wins while `totals` sums samples across all benchmarks. Per-method byte
estimates then take a numerator from one arbitrary row and a denominator from all of them.
`allocConservation`, item 9's subject, is the check that fires on this. 0a needs a sibling before item 3
renders allocation attribution.

### F14. Missed: the JIT table already mixes scopes

`msInWindow` and `msTotal` are summed across all rows from `-prof comp` (`Bench.scala:516-518`);
`tasks`, `c2Tasks`, `osrTasks`, `recompiled`, `madeNotEntrant`, `lastCompileAt` come from the single
surviving fork's log. `Store.scala:321-341` prints them in one table headed "JIT cost, which is a
property of the design". A multi-row Full bracket renders a 15-row compile-time sum beside a 1-row task
census today. This is 0a's consequence in the output, and the plan never states it.

### F15. Small: item 1's wiring target is ambiguous, and the selector count is padded

Two `efficacy`s exist. `LogCompilation.efficacy` (`:361`, wrapping `diffVerdicts` `:344`) takes two
`Parsed`, a type that is never persisted. `Investigate.efficacy` (`:178`) takes two `Run`s and is the
wirable one, dead only because `adjudicate` (`:259`) is dead. "Two implementations, one wired" is true,
but the remedy differs per copy: the `Run`-based pair survives; the `Parsed`-based pair is duplicate
logic over a transient type and should be deleted or moved behind an ingest that stores `Parsed`.

Of the fifteen "functions that decide what is worth showing given the results", `Bytecode.of` (`:68`) is
a javap capture, `Bytecode.diff` (`:121`) is a differ, and `measureDrift` (`Bench.scala:171`) is a
measurement. Twelve, not fifteen.

---

## The five questions, directly

**1. Right-sized?** No, too broad. It indicts everything and ranks nothing, and its supporting count is
off by more than 4x (F3). The narrower statement in F3 is actionable and is items 3 and 2.

**2. Real gates?** 0a, 0b, 0c are real. 0a's inference is sound for JMH specifically, not just for
HotSpot file semantics: `compilationLog` passes a fixed `-XX:LogFile` (`Bench.scala:445`, `:455`) at
`-f 1` through `-jvmArgsAppend`, which JMH applies to every fork; JMH runs one forked JVM per matched
benchmark, sequentially; the probe shows HotSpot truncates on open; nothing in the path adds a
discriminator, and HotSpot's own `%p` expansion makes the fix a one-token change. F14 shows its
consequence is already in the output, F13 gives it a sibling. 0g is a misdiagnosis (F9). 0d, 0e, 0f are
correct, with the caveat that `QaEndToEnd.check` only prints (`:17-18`), unlike `BenchTest.check` which
throws (`BenchTest.scala:41-43`), so "fails by construction" today means "prints FAIL and exits 0".

**3. Cutting v2's item 7: correct, and they are the same quantity.** HotSpot's `<method ... bytes='N'>`
is the method's bytecode length, read at `LogCompilation.scala:190` and folded into `InlineSites.bytes`
at `:278`; `Bytecode.Method.size` (`:23`) is the last instruction's offset plus one; `verifyAgainst`
(`:144-157`) exists to assert exactly that identity, and a module that treats javap as the side needing
verification is not an argument for adding a javap capture. Three caveats belong in the item:
1. The 94% is measured over the two fixtures' old per-site `JitEntry` records, which include
   `java.lang.invoke.MemberName::allFlagsSet` and other JDK frames. Current `InlineSites` is C2-only and
   `kyo.`-prefixed (`LogCompilation.scala:270-272`), so the population under the current path is
   unmeasured.
2. `bytes = 0` means "unloaded form, size unknown" (`:188-190`). A folded budget-crossing computation
   must treat 0 as unknown, as `verifyAgainst` does (`Bytecode.scala:155-156`), or every method missing a
   size on one side reports "grew past MaxInlineSize (35)".
3. `InlineSites` keys on `class::method` with no signature (`:134-137`) and takes the max across sites
   (`:278`), so overloads collapse. javap's per-signature size is strictly finer, and `verifyAgainst`
   will report false disagreements on any overloaded name.

**4. Acceptance implementable?** Gate B is unsatisfiable as written (F6); Gate A is under-specified and
self-defeating (F7). What would work: Gate A as a source-symbol reachability walk from a declared root
set (the eight `KyoCaseApp` objects) with no allowlist, so an unreachable selector is wired or deleted;
Gate B conditioned on `Evidence.Full`, with a Timing-path variant asserting on the row's iteration
series, and its fixture produced by 0c rather than assumed to exist.

**5. Ordering and gaps.** 4, 7, 9, 10, 11 are free-standing; 5 and 6 are not (F8). Item 2's content needs
re-deciding after 0a (F2). The lowest-value item is 11 as scoped, a fabricated field with zero readers.
Ranked above it: `mode`/`unit` unchecked (F12), the rest of the ingest fabrication (F11), the multi-row
allocation break that item 3 would render (F13), the JIT table's mixed scopes (F14), `Run.coverage` with
no schema default (F10).

---

## Confirmed: claims I attacked and could not break

- 8.9%, 99 of 1,117: reproduced twice, including a parser-faithful replication resolving callee names
  through the per-task symbol table.
- 94% bytes population: 887/944 and 904/958.
- Item 6, every figure. Re-implementing `Stats.threshold` (Bonferroni alpha 0.05/15, bisected t at df 3)
  over `bench-results/sweep-replicated/runs`: 7 of 15 floor-bound, `handleLoopAnswersInPlace` at -10.72%
  with a 14.43% threshold set entirely by own error against an 8.20% spread term. To the digit.
- Item 4's raw figures: 29.07%, `boxToInteger` only, 17.14/13.23/11.06, `kyo.kernel.proto.*` 16.04%.
- 0b's census: 49 runs, 47 Timing, 2 Full; no `osrTasks` in either `jit_metrics`.
- 0d: `openSession(worktree, driftRow)` (`Bench.scala:335-348`) never mentions `driftRow`.
- 0e: hardcoded 0.0 at `Bench.scala:348` and `Cli.scala:238`; `band` falls back to `DriftBand` 4.0
  (`:529-531`). Caveat: `Store.session` can recover an older session carrying a real value (the QA
  fixtures hold 3.95), so "every report" holds for anything measured now, not for every stored pair.
- Items 9 and 10: `allocConservation` (`Bench.scala:324`) has no production caller; `common` is bound at
  `:729` and appears nowhere in the remaining 28 lines of `compareReplicated`.
- The 83%-false-positive characterization of v2's acceptance grep. I could not re-run it (v2's text is
  gone), but all six hits are at the cited lines and I agree with five of six classifications;
  `Store.scala:315` is genuinely borderline. The conclusion that the grep passes `bench show` printing
  `944` is correct.

## What I could not verify

- I executed nothing. That the two QA runs fail to load is inferred from their stored keys against
  undefaulted fields in `Model.scala`; the shapes do not admit a decode, but I did not watch one fail.
  I also did not run your 287 checks.
- The two-JVM `-XX:LogFile` probe is yours. I reasoned about its applicability to JMH's fork model rather
  than re-running it.
- Whether a whole-class Full leg's log covers one row or several is still unobserved by anyone.
  Everything either of us says about it, mine included, is inference from the fixed path. Task 20 is the
  right gate and is correctly marked as needing a quiet machine.
- Whether async-profiler's `itimer` flat output credits inlined callees to the inlining frame. This
  affects how strongly F5's argument runs, and I did not settle it. The argument I rely on is independent
  of it: the kernel decides how often the benchmark's closures execute and whether they allocate, which
  your own C3 probe treats as a live kernel question.
