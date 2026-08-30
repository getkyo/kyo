# Evidence

Everything below was measured on the tip `6a40f9de4b`, on a working tree clean against it, on
2026-08-30. `package-check.sh` re-derives the tip, the surface, the tree's cleanliness, the flag
count, the edit sequence and the benchmark coverage, so these are checkable rather than asserted.

Machine note, because it bears on the benchmark errors and not on the suites: the host was running
the user's IDE and its Bloop server throughout, at a load average between 5 and 8. Ratios of 3x and
up are far outside anything that explains, and the rows inside 15% are reported as inside the noise
rather than as differences.

## Correctness

| | result |
|---|---|
| clean batch build (`kyo-kernelJVM/clean`, `compile`, `test`) | green, exit 0 |
| `kyo-kernelJVM/test` | **35 suites, 0 aborted, 1405 tests, 0 failed** |
| the three proto suites in one JVM | **208 tests, 0 failed** |
| `EvalTest` | 57 of 57 |
| the edit sequence against the baseline | 10 edits reproduce all 4 files, by digest |
| flags | 98 rows, every one with a verdict |

At the baseline `31a7b4bde9` the same module aborts a suite with a `StackOverflowError` and cannot
be run green, which is the defect this change exists to fix. The three `EvalTest` cases about the
eval's boundary were red there too, and are green here.

## The four pins, and what each fails against

A pin that passes without its fix pins nothing, so each is stated with the thing it was run against.

| pin | fails against |
|---|---|
| `ArrowEffectTest` "handles nested per recursion step in bounded stack" | the baseline, with `StackOverflowError` after 17 of 88 |
| `EvalTest` "regions that fail and recover in sequence cost no stack" | the mutually recursive guard, at 10000 cycles |
| `EvalTest` "a context update outlives an operation answered after it" | the baseline, which resumes with the install-time context |
| `EvalTest` "a region recovering across a foreign crossing leaves the budget where it found it" | the tip before the crossing fix: the sampled budget falls by exactly one per crossing, 33278 down to 33179 over 100 |
| `EvalTest` "a recover that fails itself is the failure the enclosing region sees" | a mutation of the shipped unwind (`recovered(ex)` for `recovered(ex2)`); the baseline gave this behaviour free from nested tries, so the baseline is not the control |

## Proto against the kernel, on identical work

This is the comparison that did not exist before today, and the reason it did not is recorded in
`escapes.md`: `kyo.kernel.bench.ProtoKernelBench` measures `kyo.kernel`, not the proto, and nothing
under `src/jmh` referenced `kyo.proto` at all. `ProtoBench` now runs the same twenty workloads
against the proto, body for body.

Both classes, 40 rows, one JMH session, `-f 1`, back to back. All 40 rows returned.

| row | kernel us/op | proto us/op | proto / kernel |
|---|---|---|---|
| `deferBindPerStep` | 65.807 ± 1.155 | 18.570 ± 1.110 | **3.54x faster** |
| `deferBindUnderIdleHandler` | 67.264 ± 2.039 | 23.287 ± 1.265 | **2.89x faster** |
| `trailingMapsStayLinear` | 677.094 ± 72.226 | 352.619 ± 15.152 | **1.92x faster** |
| `emittingClausesPayRegionRebuild` | 146.141 ± 5.019 | 144.478 ± 3.028 | parity |
| `evalFixedOverhead` | 0.014 ± 0.001 | 0.014 ± 0.001 | parity |
| `deepRecursionNoRescue` | 1.523 ± 0.061 | 1.533 ± 0.059 | parity |
| `deepRecursionOneRescue` | 2.875 ± 0.066 | 2.877 ± 0.088 | parity |
| `deepRecursionPaysRescuesOnly` | 48.784 ± 0.664 | 50.014 ± 1.872 | parity |
| `nestedPayloadsUnwrapInMaps` | 6.027 ± 0.083 | 6.272 ± 0.184 | parity |
| `fusionAllocatesNothing` | 0.085 ± 0.001 | 0.092 ± 0.004 | parity |
| `suspensionFusesContinuation` | 44.848 ± 2.429 | 48.815 ± 0.466 | parity |
| `fusionPastBudgetPaysRescuesOnly` | 43.651 ± 1.082 | 48.282 ± 0.912 | 1.11x slower |
| `uncachedValuesPayBoxingOnly` | 46.278 ± 2.322 | 52.166 ± 0.761 | 1.13x slower |
| `idleHandlerAddsNothing` | 43.623 ± 0.999 | 50.516 ± 14.489 | 1.16x slower |
| `deferBindUnderTrailingMap` | 44.617 ± 0.787 | 56.609 ± 1.247 | 1.27x slower |
| `continuationBodiesFuse` | 8.876 ± 0.197 | 35.219 ± 0.156 | 3.97x slower |
| `suspensionBaseline` | 85.228 ± 1.378 | 346.515 ± 5.259 | 4.07x slower |
| `statefulAnswersPaySuccessor` | 87.676 ± 2.080 | 363.465 ± 14.074 | 4.15x slower |
| `handleLoopAnswersInPlace` | 83.378 ± 1.043 | 392.386 ± 2.735 | 4.71x slower |
| `handleLoopFusesContinuation` | 80.176 ± 1.269 | 392.305 ± 20.466 | 4.89x slower |

Three faster, eight at parity, nine slower, of twenty.

### The shape of it, which is not a spread

The slow rows are not scattered. **Every row where an operation suspends and a handler answers it is
between 4x and 4.9x slower**, and every row that does not suspend is at parity or faster. The
exception proves where the cost lives: `suspensionFusesContinuation` suspends 10000 times and is at
**parity**, and it is the one row whose continuation is fused into the suspension node by
`suspendWith` rather than standing beside it as a register.

Within the proto, that pair is `suspensionBaseline` 346.5 against `suspensionFusesContinuation`
48.8, a factor of 7.1. Within the kernel it is 85.2 against 44.8, a factor of 1.9. So the proto pays
roughly 30ns per answered operation whose continuation is not already fused, where the kernel pays
about 4ns.

That is a specific, named place rather than a general slowness, and it points at the register
absorption in the `Suspend` arm and the answer path beside it. **It is not diagnosed.** Naming a
suspect from a wall-clock table is the first rung of the evidence ladder, and the mechanism needs
`gc.alloc.rate.norm` and an inlining log before anything is claimed about it.

### What this is not

These twenty rows are the proto against the kernel, which is a question about the proto as a whole.
They are **not** a statement about this change: for that, the same benchmark has to run on the proto
before and after, which is the section below.

## The change against the proto baseline

*(pending: the control leg is `ProtoBench` on the four kernel files at `31a7b4bde9`, running now)*
