# Evidence

Everything below was measured on the tip `2e070d7d21`, on a working tree clean against it, on
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
| the three proto suites in one JVM | **209 tests, 0 failed** |
| the edit sequence against the baseline | 10 edits reproduce all 4 files, by digest |
| flags | 99 rows, every one with a verdict |

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

## The change against the proto baseline: it regresses, and the change is not ready

This is the comparison that says whether the change costs anything, and it does. Both legs are
`ProtoBench` on the same benchmark, the control being the four kernel files restored to
`31a7b4bde9`. **Both legs were checked to compute the same twenty answers**, so the comparison is
of two evaluators doing the same work rather than of two different programs.

Screened at `-f 1` over all twenty rows, then confirmed at `-f 3` on the nine outside the noise.
Both legs at `-f 3`, the variant re-run at the tip after the lazy-allocation fix so that nothing here
is dated to a commit the tip supersedes.

| row | baseline us/op | tip us/op | delta |
|---|---|---|---|
| `suspensionFusesContinuation` | 53.682 ± 0.332 | 48.823 ± 0.751 | **-9.1%** |
| `fusionAllocatesNothing` | 0.080 ± 0.001 | 0.085 ± 0.001 | +6.3% |
| `evalFixedOverhead` | 0.005 ± 0.001 | 0.008 ± 0.001 | +60%, on a row at the harness's floor |
| `emittingClausesPayRegionRebuild` | 123.253 ± 1.762 | 144.270 ± 1.144 | +17.1% |
| `statefulAnswersPaySuccessor` | 181.685 ± 3.118 | 375.965 ± 11.730 | **+106.9%** |
| `handleLoopFusesContinuation` | 182.415 ± 6.860 | 392.590 ± 3.286 | **+115.2%** |
| `continuationBodiesFuse` | 16.073 ± 0.252 | 35.318 ± 0.299 | **+119.7%** |
| `handleLoopAnswersInPlace` | 173.801 ± 9.543 | 391.118 ± 6.286 | **+125.0%** |
| `suspensionBaseline` | 134.829 ± 1.968 | 337.562 ± 29.245 | **+150.4%** |

The other eleven rows are inside the combined error at `-f 1`.

### What the diagnosis established

**It is one commit.** Restoring only `Eval.scala` and `Stack.scala` to `a10624dfa4`, the first of the
fifteen, reproduces the whole thing: `suspensionBaseline` 347.3, `handleLoopAnswersInPlace` 392.9.
Every commit after the region stack is neutral on these rows. The budget fixes, the guard rewrite,
the crossing and the boundary cost nothing.

**It is not allocation.** The tip allocates *less*: 480346 B/op against the baseline's 720145 on both
`suspensionBaseline` and `handleLoopAnswersInPlace`, 24 bytes less per kernel operation, while
running 2.6x slower. `gc.alloc.rate.norm` is exact and nearly noise-free, so this rules allocation
out and forces the search into path length or code shape.

**It is not the size of `loop`.** `loop$1` grew from about 1500 bytes to 1874 when it absorbed
`region`. Moving the foreign-crossing rebuild back out into a private method took it to 1747 and
changed nothing measurable: 342.2 against 346.4 on `suspensionBaseline`, 393.8 against 391.4 on
`handleLoopAnswersInPlace`. The probe was reverted, since a change with no measured benefit is not a
change.

**It is per answered operation, and it has a sharp boundary.** Every regressed row suspends an
operation that a region answers. Every row at parity or faster either installs no region, or
installs one that never answers. And the boundary inside that set is sharp:
`suspensionFusesContinuation` suspends ten thousand times under the same handler and is **8.7%
faster**, while `suspensionBaseline` does the same work with the continuation standing beside the
node instead of fused into it and is **157% slower**. The two differ only in `askWith` against
`ask.map`, which is exactly the difference between the absorb path returning the node untouched and
the absorb path copying it.

**The mechanism is not identified.** That is the honest state. The next experiment is the one that
distinguishes the two absorb paths rather than the stack reads, since the fused row exercises the
stack reads just as often and does not pay. `PrintInlining` on the two rows, looking at what the
absorb merge point does to the receiver profile of `susp.tag`, `susp.input` and `susp.cont`, is
where I would go next.

### The hypotheses tested, and what each measured

Recorded so the next attempt starts from the fifth, not the first.

| # | hypothesis | verdict | evidence |
|---|---|---|---|
| 1 | allocation | **refuted** | the tip allocates 480,346 B/op against the baseline's 720,145 on both `suspensionBaseline` and `handleLoopAnswersInPlace`, while running 2.6x slower. `gc.alloc.rate.norm` is exact |
| 2 | `loop`'s bytecode size, from absorbing `region` | **refuted** | extracting the foreign-crossing rebuild took `loop$1` from 1874 to 1747 bytes and moved nothing: 342.2 against 346.4, 393.8 against 391.4. Probe reverted |
| 3 | the per-eval `Safepoint.save`/`restore` | **refuted** | removing only that pair leaves 341.6 against 346.4 and allocation byte-identical. Probe reverted |
| 4 | the two legs execute different steps | **refuted** | with a debugger installed, both legs report identical traces at depth 3 and depth 600: 1804 allocations, `SuspendArrow` x1202, `Defer` x601, `Handle` x1, 601 unfused applies. The semantics are the same |
| 5 | the absorb's middle branch | **confirmed, partially** | removing it takes `handleLoopAnswersInPlace` from 391.4 to 237.3 us, recovering about half that row's regression, at the cost of 67% more allocation. It moves no other row |

A caveat that matters for reading row 4: an installed debugger makes `Safepoint.enterPark` allow every
strict application, so a traced run never defers and does not follow the benchmark's path. The trace
proves the two legs agree step for step; it cannot speak for what the budget does in the benchmark.

### What the shape costs, which is a separate finding

Same handler, same depth, same region; only the call shape differs:

| shape | allocations per 600 operations | per operation |
|---|---|---|
| `ask.map(f)` | 1804: `SuspendArrow` x1202, `Defer` x601, `Handle` x1 | **3** |
| `askWith(f)` | 602: `SuspendArrow` x601, `Handle` x1 | **1** |

That 3:1 is the 7.1x between `suspensionBaseline` and `suspensionFusesContinuation` inside the proto,
and it holds on both legs, so it is not the regression. It is why every regressed row is a `.map`
row and the one parity row is a `suspendWith` row, which had looked like a clue about the change and
is really a fact about the shape.

### The one cost that is diagnosed and fixed

`evalFixedOverhead` installs no region at all, and it regressed 180%. The `Stack` constructor built
four arrays of eight, so every eval paid for them whether or not it ever installed anything: 224
B/op against the baseline's nothing.

The arrays now start shared and empty and the first `push` grows into real ones (`2e070d7d21`).
Measured after: `evalFixedOverhead` 32 B/op and 0.008 us, `fusionAllocatesNothing` 0.085 us against
the baseline's 0.080, inside error. `suspensionBaseline` is 346.3, unchanged, which is the control:
the fix is per eval and the other regression is per operation.

The 32 B/op that remain are the `Stack` object itself, one per eval. The reference kernel pools its
stacks for exactly this reason, and that is the next step on this cost.

### What this means for the review

The change fixes five correctness defects and makes a red module green. It also makes answered
operations under a region between 2x and 2.6x slower, and that is an open defect, not a trade I get
to make. It is localized to one commit, three explanations are ruled out, and the mechanism is not
yet named.
