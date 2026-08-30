# Evidence

Change: `a10624dfa4` in the isolated worktree. Control: `31a7b4bde9`.

## Green

**Clean batch build**: `kyo-kernelJVM/clean` then `compile`, zero errors. Run because the change
could summon the lift in a kernel file and incremental green is not clean green.

**Proto suites**, each in its own JVM (see the blocked section for why not together):

| suite | before | after |
|---|---|---|
| `ArrowEffectTest` | aborted after 17 of 88, `StackOverflowError` | **88 of 88** |
| `PendingTest` | 63 of 63 | 63 of 63 |
| `EvalTest` | 51 of 54 | 51 of 54 |

`EvalTest`'s three failures are the pre-existing eval-boundary item (`Eval.apply` returns an
unanswered suspension instead of rejecting it), untouched by this change and recorded as its own
open question.

**Demo**: every one of the 28 scenarios returns its recorded value, the bracket guarantees included:
83, -1, -1, 4221, -9, 991. This was the check most at risk, because `recover` now reads the state the
region has reached rather than the one it was installed with. The values holding is consistent with
the encoded bracket keeping its finalizers in a handler-owned registry rather than in loop state.

**Depth**: `ArrowEffectTest` "handles nested per recursion step in bounded stack" nests 100000
regions and now passes at the default fork stack, where before it needed 1 GB. The standalone
measurement (1426 nested regions at 1 MB) is superseded by that pass and has not been re-run as a
separate probe.

## Benchmarks

Full class, both legs back to back in one session on the same machine, `-f 1 -wi 5 -i 5`, JDK 25,
macOS arm64. Control `31a7b4bde9`, variant `7a7cd22ad8`, **the shipped tip**. Twenty declared, twenty
measured (the twenty-first `@Benchmark` match is the class-level `@BenchmarkMode`). **Drift band on
this machine is 3 to 4%**, so a delta inside it is not a result, and neither is one smaller than the
combined error.

An earlier pair measured `ffc1819ecc`, which was not what shipped: the tip rewrote the guard every
row enters. Those numbers are discarded rather than carried forward, and the control was re-run here
rather than reused across sessions.

| benchmark | control | variant | delta | |
|---|---|---|---|---|
| trailingMapsStayLinear | 670.531 ± 45.381 | 703.402 ± 38.423 | +4.9% | error exceeds delta |
| deferBindUnderIdleHandler | 66.308 ± 0.460 | 67.045 ± 0.673 | +1.1% | flat |
| suspensionFusesContinuation | 44.547 ± 0.442 | 44.782 ± 1.305 | +0.5% | flat |
| deepRecursionOneRescue | 2.878 ± 0.029 | 2.891 ± 0.032 | +0.5% | flat |
| fusionPastBudgetPaysRescuesOnly | 43.962 ± 0.371 | 44.002 ± 0.396 | +0.1% | flat |
| emittingClausesPayRegionRebuild | 145.222 ± 2.639 | 145.407 ± 2.113 | +0.1% | flat |
| handleLoopFusesContinuation | 80.351 ± 0.695 | 80.376 ± 1.477 | 0.0% | flat |
| evalFixedOverhead | 0.014 ± 0.001 | 0.014 ± 0.001 | 0.0% | below measurement resolution |
| deferBindPerStep | 65.783 ± 0.924 | 65.682 ± 0.312 | -0.2% | flat |
| statefulAnswersPaySuccessor | 88.218 ± 1.195 | 88.036 ± 1.353 | -0.2% | flat |
| suspensionBaseline | 85.686 ± 5.619 | 85.324 ± 1.631 | -0.4% | flat |
| nestedPayloadsUnwrapInMaps | 6.094 ± 0.095 | 6.069 ± 0.128 | -0.4% | flat |
| deepRecursionNoRescue | 1.545 ± 0.102 | 1.534 ± 0.013 | -0.7% | flat |
| idleHandlerAddsNothing | 44.338 ± 1.913 | 43.984 ± 0.358 | -0.8% | flat |
| uncachedValuesPayBoxingOnly | 47.227 ± 4.520 | 47.659 ± 0.436 | +0.9% | flat |
| fusionAllocatesNothing | 0.086 ± 0.001 | 0.085 ± 0.001 | -1.2% | flat |
| continuationBodiesFuse | 8.939 ± 0.409 | 8.808 ± 0.151 | -1.5% | flat |
| deferBindUnderTrailingMap | 45.785 ± 3.346 | 44.795 ± 0.649 | -2.2% | flat |
| handleLoopAnswersInPlace | 83.631 ± 1.414 | 80.427 ± 1.519 | -3.8% | faster, at the band's edge |
| deepRecursionPaysRescuesOnly | 52.117 ± 0.436 | 49.556 ± 3.734 | -4.9% | error exceeds delta |

**No row regressed beyond the drift band, and none regressed beyond its own error.** The two nominally
faster rows are at or inside the band and are not claimed as wins. Moving the open regions from the
Java stack to four heap arrays, and the guard from per-region tries to one loop, costs nothing
measurable on any row the change reaches, the region-heavy ones included.

## The recovery path scales

The guard's first shape recursed, which cost a frame per recovered region, and under the fix for
that the path still stalled because a throw leaks a safepoint `enter`. Both are fixed; this is the
measurement that shows it, since a single depth cannot show a scaling defect.

| recoveries | 200 | 400 | 800 | 1600 | 3200 | 6400 | 12800 |
|---|---|---|---|---|---|---|---|
| us per recovery | 5.68 | 3.05 | 1.51 | 1.14 | 1.09 | 0.96 | 0.89 |

Flat, with the early figures reflecting JIT warmup rather than growth, and `Stack` pushes equal to
pops at every size. Without the reset the same probe livelocks past 800; the baseline livelocks past
400. Pinned by `EvalTest` "regions that fail and recover in sequence cost no stack" at 10000 cycles.

## The hang this surfaced, now fixed separately

Running the three suites in one JVM used to hang at `ArrowEffectTest:969`. That was pre-existing:
the baseline hangs at the identical test when given `-Xss1g` so that it reaches it, where normally
the StackOverflowError ends the suite at test 17 and nothing ever gets there. It is fixed by
`ffc1819ecc`, which gives an eval its own safepoint budget, and it has its own derivation under
`reviews/proto-eval-budget/`.

With all three commits in, `kyo-kernelJVM/test` is 35 suites, 0 aborted, **1399 passing**, 3 failing,
and the three proto suites run together for the first time. The 3 failures are the
pre-existing eval-boundary item in `EvalTest`, which is untouched here and still holds two open
questions of its own.
