# bench-harness

The measurement protocol from the kernel skill, as a program rather than as bash typed fresh
each time. Isolated on purpose: its own sbt build on published kyo artifacts (`1.0.0-RC6`), no
dependency on the repo's build, so it compiles and runs while the kernel tree is red or mid-edit,
and its sbt server (keyed on this directory) is never the one the kernel is built through. Tests
are kyo-test suites (`kyo.test.Test`), run by `sbt test`.

```sh
cd kyo-kernel/.claude/skills/kernel/bench-harness
sbt test                                                     # every guard, statistic and parser self-check, no benchmark needed
sbt "testOnly BenchTest"                                     # one suite
sbt "runMain BenchRun --worktree ../../../../../bench-sweep \
    --label control --sha 36b41336fb"                        # measure one leg, store it
sbt "runMain BenchCompare --control <id> --variant <id>"
sbt "runMain BenchBracket --worktree ../../../../../bench-sweep \
    --control <sha> --variant <sha>"                         # C V C V C, with a real threshold
sbt "runMain BenchChain --worktree ../../../../../bench-sweep \
    --sha <a> --sha <b> --sha <c>"                           # isolate one change per step
sbt "runMain BenchPlan --from <id> --target 5"
sbt "runMain BenchIngest --json <f> --label <l> --sha <sha>"
sbt "runMain BenchJit --run <id> --log <compilation.log>"     # attach inlining/metrics from a compilation log (a json carries none)
sbt "runMain BenchList"
sbt "runMain BenchShow --id <id>"
sbt "runMain BenchExpansion --fixture BareValue --phase inlining"   # print an expansion fixture after a phase, against Roots.classes
```

`sbt --client "..."` keeps a server warm between commands (2 to 5 s per command instead of a
cold start); the QA mains (`QaGuards`, `QaParsers`, `QaEndToEnd`) run the same way and fail
with an exit code. Nothing here reads the kernel build's classes except `BytecodeTest`, which
points at an ordinary sbt output directory through `Roots.classes` (`-Dbench.classes=...` to
move it).

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
- **Store a measurement without the configuration that produced it.** A configuration
  comparison varies nothing but JVM arguments, so a stored pair with the same sha and tree hash
  is otherwise indistinguishable. The campaign's headline result was stored exactly that way and
  is unrecoverable because of it. Runs record their arguments, and a same-sha comparison prints
  which ones differ.
- **Round a verdict's own numbers away.** A cleanly separated 26.2% regression on a nanosecond
  row once rendered as `0.01 ± 0.00` against `0.01 ± 0.00`. Precision scales with magnitude, so
  the figures behind a verdict stay legible and the error column never reads as zero when it is
  not.

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
