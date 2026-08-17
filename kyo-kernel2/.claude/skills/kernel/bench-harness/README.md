# bench-harness

The measurement protocol from the kernel skill, as a program rather than as bash typed fresh
each time. Isolated on purpose: scala-cli with published kyo artifacts, no dependency on the
repo's sbt build, which does not compile as a whole while the kernel migration is in flight.

```sh
scala-cli run . --main-class BenchTest                       # guards self-check, no benchmark needed
scala-cli run . --main-class BenchRun -- --worktree ../../../../../bench-sweep \
    --label control --sha 36b41336fb                         # measure one leg, store it
scala-cli run . --main-class BenchCompare -- --control <id> --variant <id>
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

- **Run in the primary worktree.** It compares the worktree's git dir against the common git
  dir and refuses if they match. Brackets overwrite sources; doing that where commits come
  from destroyed uncommitted work and, once, a whole redesign.
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

## Storage

Every leg is stored with its markers, configuration and full ladder, so `compare` is a pure
function over records and never re-runs anything. Two run ids always produce the same verdict,
and what the JIT decided about a method stays answerable long after the run.
