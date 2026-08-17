# bench-harness improvement plan (v5)

Supersedes v4. Four held-out reviews have run. v4's numbers survived attack; **its ordering did not**.

> **v4 was an accurate defect list and a poor delivery plan.** After executing v4 in full, no item
> changes *which evidence the report reaches for as a function of the result*, except one line in
> item 2. The section count goes 19 to about 20. Under v4's order the operator gets nothing new until
> the last two items.

v5 keeps v4's diagnoses and **inverts the order**: the founding requirement first, the sparsest
evidence last.

## Two findings that reorder everything, both verified here

**1. The CPU partition has no denominator, so item 4 was about to print noise as fact.** `profile()`
runs `-i 1` (`Bench.scala:436`), so `Run.cpu` is one second of itimer. On the two stored Full legs,
same row, scores agreeing to 0.3%:

    qa-control  93 samples   kernel  7.5%   bench 46.2%
    qa-variant  92 samples   kernel 15.2%   bench 45.7%

**The kernel share doubled between two legs that did not move.** One sample is 1.1 points. `qa-cpu.txt`
reaches 461 samples only because it used JMH's default `-i 5`. So **item 4 is demoted to last**, and it
may not print a share without its count, per this codebase's own rule (`Model.scala:118`: "a verdict is
only meaningful with its denominator, so the denominator is carried"). The cheap fix is raising `-i` on
an invocation already being paid for, which is worth more than most of v4.

**2. The verdict line contradicts the allocation note, in the highest-consequence line of the output.**
From the real `sweep-replicated.log`:

    | ⚪ | trailingMapsStayLinear | ... | +43.2% | +239977 |
    ℹ️  Allocation moved ... Allocation is exact and per-operation, so this is a real change ...
    🟢 No row regressed beyond the drift band, across the whole class.

`allocMoved` is computed at `Store.scala:364` and `verdictLine` (`:421-434`) never reads it. **One
branch, free-standing, and it is the line an operator trusts most.**

## The ordering

**Step 1, unblock every Full render.** 0b: the two Full runs are undecodable, and `coverage`
(`Model.scala:307`) is a new field with **no default**, so defect 17's original class is live now. Add
tests for **both** schema classes. `StoreSchemaTest` does not exist; the check is inline at
`BenchTest.scala:498-511` and a stale comment at `Model.scala:323` names the phantom. 0c: `Ingest`
cannot produce a jit-bearing run at all.

**Step 2, the founding requirement. This is the step that pays.**
- **The verdict-line fix** above.
- **`Report.renderRun`**, and **extended to `compare` and `chain`**, which v4 omitted. That is the path
  that re-reads stored legs, where `coverage`, `warmup` and `treeHash` matter most because nobody
  watched the measurement.
- **"Absence is a result and it names its remedy."** Per comparison, enumerate the checks that did not
  run and why: no A/A null (fewer than three control legs), no efficacy (legs share `jvmArgs`), no
  mechanism (Timing evidence), no attribution (whole-class leg). Never "check the inlining log";
  instead the command that would produce it. This converts every "the operator must remember" case into
  "the output already told them", and it depends on nothing.

  Three of the four are computable at the render site; **the A/A case is not.** `Comparison`
  (`Model.scala:371`) carries one control `Run`, not the leg count. That same model change is needed by
  items 6 and 10, so **all three land together**.

**Step 3, correctness.** Item 12: `mode`/`unit` unchecked in **both** classifiers (`Bench.scala:615`,
`Stats.classify` `Stats.scala:171`), so a `thrpt` row is classified backwards including on the
replicated path, **and `Investigate.Quantity.Time` reads `r.score` raw, so adjudication is backwards
too**. Refuse mismatched units; invert for `thrpt`.

**Step 4, the threshold advice, which is currently backwards for half the rows.** The threshold is
`max(t·se, ownError·mean)`; more legs shrink only `t·se`. On the **7 of 15 floor-bound rows more legs
cannot move the threshold at all**, yet `Cli.scala:295-296` prints "More forks will not help ... More
legs, or a quieter machine", while `Plan.scala:15-17` records that tripling forks cut own error
six-fold. **Item 6** (carry the binding term on `Resolution`) plus fixing that sentence, plus **wiring
`Plan.forecast` into the report**: it takes `Chunk[Run]`, exactly what a `Comparison` holds, and today
is reachable only through a command that must be remembered.

**Step 5, Gate A and the eight stranded selectors.** v4 defined its gates and **scheduled them
nowhere**. Gate A is a source-symbol reachability walk from the eight `KyoCaseApp` roots, **no
allowlist**, and its work items are the eight: `actionableJit`, `jitUnstable`, `budgetCandidates`,
`unprofiledSites`, `diffVerdicts`/`efficacy`, `verifyAgainst`, `Bytecode.diff`/`crossedBudget`. Two of
them answer entries 2 and 3 of the founding defect ledger. **`nearBudget` is the one wired selector and
it is reachable only through `falsifiers`, which requires `regressed.nonEmpty`, so on a flat or winning
report a method sitting 379B against a 325B budget is invisible.**

**Step 6, multi-row correctness.** 0a (`%p`, then parse the set) with its two output consequences: the
JIT table's mixed scopes, and the allocation break. **Plus a stale-file guard**: `allocDir` is never
cleared and `collapsedFiles` reads every `collapsed-*.csv` under it, so a repeated bracket label
concatenates a previous leg's dumps into `byMethod`; `measureWith` already learned this for the json
(`Bench.scala:399-404`). **Item 9** (`allocConservation`) is the check that catches both.

"The row key is the filename" is imprecise: only the `..._jmhTest ..._jmhStub` task names a row, so
`Task.method` is still needed after 0a, once per file rather than per verdict.

**Step 7, items 1 and 2.** Item 1 has a **blocking** problem: `jvmArgs` carries a **path**, not a
method. The stored run holds `-XX:CompileCommandFile=.../forced-inline.cmd` and the instructed method
lives inside that file, and `Bench.scala:386-389` *instructs* operators to use the file form. **Store
the file's contents**, or the instructed method is unrecoverable on the harness's own mandated path.
Also: do **not** delete `diffVerdicts`. Its real dependency is `Chunk[InlineSites]`, which `Run`
already carries, so re-type it. `Investigate.efficacy` says *whether* the flag took; `diffVerdicts`
names *what moved*, which is what item 1's own rationale asks for.

Item 2's content is re-decided after 0a. Attribution, verified: 1,117 C2 verdicts, 99 (8.9%) the row's
stub, 625 (56.0%) the benchmark's own code, **724 (64.8%) row-attributable**, on a single-row leg. The
whole-class ratio is unmeasured by anyone.

**Step 8, adjudication as a report section, not a command.** Item 8 was a new subcommand, which the
founding requirement calls a failure by construction: `bench adjudicate` must be remembered at exactly
the moment it will not be. `BenchCompare` holds the store and both runs and already detects a
configuration comparison (`Store.scala:169`). **Adjudication belongs in the report the isolation leg
renders.**

**Step 9, last.** Item 4 with its denominator, items 10, 11, 0d-0g.

- **Item 4** must carry the sample count, and should say the two things every version has omitted:
  the largest single frame is `boxToInteger` at 29%, neither kernel nor benchmark logic and the biggest
  lever in the table; and **the kernel share is a lower bound, not a ceiling**, because inlined kernel
  code is credited to its inlining frame. Its "largest contributors" list must not come from
  `noiseFrames`, which ranks non-kernel frames and would list two 48%-bucket frames under a sentence
  about the 36% bucket. Its render-side acceptance needs 0b, so **it was never free-standing**.
- **Item 11**: `stillCompiling`'s denominator has **three** fabricated terms, not one.
  `MeasureIterations` and `IterationSeconds` are harness constants while the json carries
  `measurementIterations`/`measurementTime`. `forks` has three readers; `warmup` has zero.

## Anti-goals

- **No new subcommands at all**, including item 8. A command is another thing to remember.
- **No new statistics.**
- **No claim without its denominator.** New, and it is what item 4 violated.
- **v2's item 7 stays cut**, with its three caveats: the 94% was measured over JDK-inclusive records so
  the current C2 + `kyo.`-prefixed population is unmeasured; `bytes = 0` means "unloaded, unknown"; and
  `InlineSites` keys without a signature, so overloads collapse.

## Why this order

After **step 2** the operator gets useful output on the very next run, and every later item improves an
output they are already reading. Under v4's order they got nothing new until the end.
