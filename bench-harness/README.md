# bench-harness

The measurement protocol from the kernel skill, as a program rather than as bash typed fresh
each time. Isolated on purpose: scala-cli with published kyo artifacts, no dependency on the
repo's sbt build, which does not compile as a whole while the kernel migration is in flight.

```sh
scala-cli run . --main-class BenchTest   # self-check, needs no benchmark run
scala-cli compile .
```

## What it enforces

Each guard exists because its absence corrupted or wasted a real measurement:

- **Throwaway worktree, never the tree you commit from.**
- **`git restore --source=<sha> --worktree`, never `git checkout <sha> -- <paths>`.** Checkout
  stages what it restores, so an interrupted bracket leaves the comparison design in the index
  and the next commit silently sweeps it in. That reverted an entire redesign once, under a
  commit message that claimed to touch only a documentation file.
- **Design markers verified before and after each leg**, so every number carries proof of which
  design produced it, and a run whose markers moved mid-flight fails instead of reporting.
- **Results parsed from JMH json**, not grepped from console text.
- **Row count checked against the benchmark class**, counting only bare `@Benchmark` lines,
  because matching the substring also matches `@BenchmarkMode` and inflates the expectation. A
  short read is retried, then fails; it is never reported as clean.
- **Deltas classified against the drift band**, with scores dominated by their own error marked
  below resolution and sorted away from the real movements, so a 5ns row reading +20% cannot
  masquerade as the headline regression.
