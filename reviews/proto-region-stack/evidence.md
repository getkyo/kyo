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

Full class, both legs back to back on the same machine, `-f 1 -wi 5 -i 5`, JDK 25, macOS arm64.
Control `31a7b4bde9`, variant `ffc1819ecc`. **Drift band on this machine is 3 to 4%**, per the
skill's brackets section, so a delta inside it is not a result and is marked flat below; a delta
smaller than the combined error is not a result either, whatever its size. Twenty benchmarks declared and twenty measured, so the result set is complete
(the twenty-first `@Benchmark` match is the class-level `@BenchmarkMode` annotation).

Deviation, recorded rather than routed around: the harness bracket refuses a leg whose suite is red,
and the control's is red **by construction**, since `ArrowEffectTest` aborts there with the
StackOverflowError this change fixes, and that reproduces back through `eabef556e0`. No green control
exists for this change or can exist. Narrowing the gate's task to the suites green on both legs was
available and rejected as weakening a gate for convenience, so these legs were run directly and the
bracket's A/A null and warmup guards did not run over them.

| benchmark | control | variant | delta | |
|---|---|---|---|---|
| deepRecursionPaysRescuesOnly | 49.121 ± 1.440 | 51.760 ± 0.576 | +5.4% | flagged, see below |
| handleLoopAnswersInPlace | 79.334 ± 1.317 | 84.763 ± 10.461 | +6.8% | error exceeds delta |
| emittingClausesPayRegionRebuild | 146.026 ± 7.169 | 151.231 ± 22.592 | +3.6% | error exceeds delta |
| suspensionBaseline | 84.949 ± 2.616 | 87.668 ± 13.772 | +3.2% | error exceeds delta |
| deepRecursionOneRescue | 2.857 ± 0.019 | 2.886 ± 0.124 | +1.0% | flat |
| continuationBodiesFuse | 8.782 ± 0.186 | 8.837 ± 0.429 | +0.6% | flat |
| fusionPastBudgetPaysRescuesOnly | 43.626 ± 0.428 | 43.864 ± 1.994 | +0.5% | flat |
| deepRecursionNoRescue | 1.522 ± 0.010 | 1.528 ± 0.014 | +0.4% | flat |
| statefulAnswersPaySuccessor | 87.701 ± 3.073 | 88.021 ± 3.998 | +0.4% | flat |
| suspensionFusesContinuation | 44.178 ± 1.085 | 44.324 ± 0.787 | +0.3% | flat |
| nestedPayloadsUnwrapInMaps | 6.018 ± 0.159 | 6.025 ± 0.073 | +0.1% | flat |
| deferBindPerStep | 65.320 ± 0.526 | 65.398 ± 1.084 | +0.1% | flat |
| deferBindUnderIdleHandler | 68.592 ± 0.895 | 68.502 ± 3.227 | -0.1% | flat |
| deferBindUnderTrailingMap | 44.748 ± 1.800 | 44.492 ± 0.793 | -0.6% | flat |
| handleLoopFusesContinuation | 80.568 ± 3.681 | 80.070 ± 5.251 | -0.6% | flat |
| idleHandlerAddsNothing | 43.940 ± 0.573 | 43.578 ± 1.190 | -0.8% | flat |
| fusionAllocatesNothing | 0.085 ± 0.001 | 0.084 ± 0.001 | -1.2% | flat |
| uncachedValuesPayBoxingOnly | 47.475 ± 3.018 | 46.881 ± 2.194 | -1.3% | flat |
| trailingMapsStayLinear | 686.974 ± 45.638 | 651.898 ± 36.762 | -5.1% | error exceeds delta |
| evalFixedOverhead | 0.014 ± 0.001 | 0.013 ± 0.001 | -7.1% | below measurement resolution |

`deepRecursionPaysRescuesOnly` was the only delta outside the errors on both sides, so it was
confirmed at `-f 3`, 15 iterations per leg, back to back:

| | control | variant | delta |
|---|---|---|---|
| deepRecursionPaysRescuesOnly | 49.346 ± 0.558 | 48.985 ± 0.416 | -0.7% |

It does not reproduce. The `-f 1` figure was a single-fork artifact, which is why a `-f 1` number is
never a result. **No confirmed regression on any row.**

This closes the `measurement pending` group in `flags.md`: moving the open regions from the Java
stack to four heap arrays costs nothing measurable on any row the change reaches, including the
region-heavy ones (`emittingClausesPayRegionRebuild`, `handleLoopAnswersInPlace`,
`statefulAnswersPaySuccessor`).

## Trailing maps are linear, and what the row's cost actually is

Asked whether the proto is quadratic in trailing maps. It is not. Sweeping the benchmark's own shape
over a 16x depth range, per-step cost is flat where quadratic growth would multiply it by 16:

| depth | trailing, us | us/step | plain, us | us/step |
|---|---|---|---|---|
| 2500 | 208.3 | 0.0833 | 54.2 | 0.0217 |
| 5000 | 386.1 | 0.0772 | 100.9 | 0.0202 |
| 10000 | 638.2 | 0.0638 | 202.6 | 0.0203 |
| 20000 | 1396.9 | 0.0698 | 398.4 | 0.0199 |
| 40000 | 2848.9 | 0.0712 | 943.6 | 0.0236 |

Each doubling of depth roughly doubles total time (1.85x, 1.65x, 2.19x, 2.04x) where quadratic would
quadruple it. The row's 687 us is because it runs at `Depth = 10000` while nearly every other row
uses `NarrowDepth = 1000`, so it does ten times the work. What is real is a constant factor: a
trailing map costs about 3.4x per step against the same recursion without one, 0.070 against 0.020
us. Linear, not free.

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
