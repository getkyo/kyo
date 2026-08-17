# bench-harness

The measurement protocol from the kernel skill, as a program rather than as bash typed fresh
each time. Isolated on purpose: scala-cli with published kyo artifacts, no dependency on the
repo's sbt build, which does not compile as a whole while the kernel migration is in flight.

```sh
scala-cli run . --main-class BenchTest                       # guards self-check, no benchmark needed
scala-cli run . --main-class BenchRun -- --worktree ../../../../../bench-sweep \
    --label control --sha 36b41336fb                         # measure one leg, store it
scala-cli run . --main-class BenchCompare -- --control <id> --variant <id>
scala-cli run . --main-class BenchBracket -- --worktree ../../../../../bench-sweep \
    --control <sha> --variant <sha>                          # C V C V C, with a real threshold
scala-cli run . --main-class BenchChain -- --worktree ../../../../../bench-sweep \
    --sha <a> --sha <b> --sha <c>                            # isolate one change per step
scala-cli run . --main-class BenchPlan -- --from <id> --target 5
scala-cli run . --main-class BenchIngest -- --json <f> --label <l> --sha <sha>
scala-cli run . --main-class BenchList
scala-cli run . --main-class BenchShow -- --id <id>
```

## Why it collects more than timing

Wall clock says whether something moved, never why. Every mechanism established in this
project came from `gc.alloc.rate.norm`, allocation sites, the CPU profile, or the inlining
log, and every mechanism claimed *without* them turned out wrong on the next measurement. So
`run` walks the whole ladder by default: the measurement carries the gc profiler, then an
allocation-site profile, a CPU profile, and a short `PrintInlining` pass. `--evidence timing`
skips them for iteration, and any report built from such a run is stamped as unattributed.

## What it refuses to do

Each guard exists because its absence corrupted or wasted a real measurement:

- **Run in a worktree that is on a branch.** A bracket worktree is created detached, so a
  detached HEAD is the property required. Comparing git dirs was tried first and catches only
  the repository's *primary* worktree, while the tree that must not be scribbled on is whichever
  one work happens in, and that one is always on a branch. Brackets overwrite sources; doing
  that where commits come from destroyed uncommitted work and, once, a whole redesign.
- **Flip designs with `git checkout <sha> -- <paths>`.** Checkout stages what it restores, so
  an interrupted bracket leaves the comparison design in the index and the next commit sweeps
  it in. Only `git restore --worktree` is used.
- **Measure a red tree.** The suite must pass before a leg spends any runs, because a faster
  variant with failing tests is not a result.
- **Trust a short read.** Row count is checked against bare `@Benchmark` lines in the class,
  retried, then failed. Matching the substring would also match `@BenchmarkMode`.
- **Report a suite-wide verdict from a subset run.** "No row regressed" is unreachable unless
  both legs measured the whole class; a subset says so in its own conclusion instead.
- **Let an artifact headline.** Scores dominated by their own error render as below resolution
  and sort past the real movements, so a 5ns row reading +20% cannot pose as the worst
  regression.
- **Narrate a movement with no evidence behind it.** A row that moved while allocation and
  inlining did not is marked unexplained, which is the tool's way of enforcing the rule that a
  mechanism must be measured rather than reasoned.
- **Attribute a two-sha difference to one change inside it.** A pair does not declare a
  partition, so the report says no source-level mechanism follows from it, however plausible one
  is. `chain` declares the partition and each of its steps is attributable.
- **Read a session that cannot check itself.** A dirty A/A null, or too few control legs to run
  one, is a blocker with an exit code rather than a line of text: every row an A/A classifies is
  a false positive by construction, so it is the strongest available statement that nothing
  below is readable.
- **Return a verdict from a falsifier whose flag never took.** `CompileCommand=inline` is a
  hint, and HotSpot still refuses on `MaxInlineLevel`, node budget, or a method it cannot
  compile. Such a run is inconclusive, never a refutation.

## What it proposes next

A comparison that stops at "this row moved" is where "it is slower, so replace it" comes from.
Every delta arrives with the experiments that could contradict the obvious reading of it, each
one JVM flag and one run: force the method the variant stopped inlining, raise only the budget
it is over, force the *control*'s refused method to test whether a win was the inlining rather
than the design, disable escape analysis when allocation moved.

## Storage

Every leg is stored with its markers, configuration and full ladder, so `compare` is a pure
function over records and never re-runs anything. Two run ids always produce the same verdict,
and what the JIT decided about a method stays answerable long after the run.
