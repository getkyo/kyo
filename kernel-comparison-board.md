# Old kernel vs new kernel: mirrored KernelBench boards

First true like-for-like comparison: the same 19 benchmark bodies compiled against each kernel
(kyo-kernel via the mirror in `kyo-kernel/bench`, kyo-kernel2 via its canonical suite), both
boards run in the same session on the same idle machine and JVM (Temurin 25.0.3, aarch64),
3 forks, 5 warmup + 5 measurement iterations, gc profiler, identical (default) JVM flags,
JMH default -foe false. Zero benchmark failures on either side.

The only body divergences: the old kernel's handleLoop handlers receive the continuation
explicitly (answer-in-place is `Loop.continue(cont(1))`), and `partialSuspensionBaseline` is
kernel2-only (the old kernel has no kernel-level partial evaluation). The previously recorded
"old kernel" board column (R5 night report) was measured against the earlier bench generation
with different bodies and is superseded by this run.

## Time, microseconds per op (lower is better; ratio is new/old)

| row | old time (us/op) | new time (us/op) | new/old |
|---|---|---|---|
| trailingMapsStayLinear | 472,770.9 ± 10,199.1 | 672.4 ± 9.5 | 0.0014 |
| evalFixedOverhead | 0.009 ± 0.001 | 0.002 ± 0.001 | 0.22 |
| suspensionFusesContinuation | 70.22 ± 7.00 | 29.88 ± 0.85 | 0.43 |
| uncachedValuesPayBoxingOnly | 74.71 ± 1.28 | 34.72 ± 0.24 | 0.46 |
| handleLoopAnswersInPlace | 134.85 ± 1.24 | 82.04 ± 0.36 | 0.61 |
| inlineLimitKeepsZeroAllocation | 2.167 ± 0.008 | 1.385 ± 0.010 | 0.64 |
| suspensionBaseline | 127.69 ± 3.57 | 82.85 ± 0.85 | 0.65 |
| idleHandlerAddsNothing | 48.84 ± 0.84 | 33.42 ± 0.36 | 0.68 |
| fusionAllocatesNothing | 0.830 ± 0.010 | 0.578 ± 0.011 | 0.70 |
| fusionPastBudgetPaysRescuesOnly | 48.44 ± 0.73 | 33.69 ± 0.39 | 0.70 |
| inlineLimitCostsTimeNotAllocation | 347.03 ± 7.74 | 248.35 ± 2.78 | 0.72 |
| statefulAnswersPaySuccessor | 153.68 ± 2.53 | 111.11 ± 0.60 | 0.72 |
| userTypesSkipKernelWrapping | 43.86 ± 1.11 | 34.41 ± 0.99 | 0.78 |
| deepRecursionPaysRescuesOnly | 55.08 ± 0.64 | 52.44 ± 1.41 | 0.95 |
| continuationBodiesFuse | 24.78 ± 0.37 | 27.13 ± 0.38 | 1.09 |
| foreignCrossingsPayRotation | 325.04 ± 2.54 | 377.17 ± 3.92 | 1.16 |
| sharedHandlerPaysDispatch | 140.77 ± 6.02 | 169.70 ± 2.17 | 1.21 |
| fusionAfterSuspensionRunOnly | 0.283 ± 0.001 | 0.829 ± 0.016 | 2.93 |
| fusionAfterSuspension | 87.98 ± 5.42 | 271.49 ± 4.31 | 3.09 |
| partialSuspensionBaseline | kernel2-only | 82.32 ± 1.02 | - |

## Allocation, bytes per op (lower is better; ratio is new/old)

| row | old alloc (B/op) | new alloc (B/op) | new/old |
|---|---|---|---|
| trailingMapsStayLinear | 1,601,202,471 | 2,961,173 | 0.0018 |
| deepRecursionPaysRescuesOnly | 2,128 | 912 | 0.43 |
| handleLoopAnswersInPlace | 960,134 | 640,145 | 0.67 |
| idleHandlerAddsNothing | 1,224 | 1,096 | 0.90 |
| fusionPastBudgetPaysRescuesOnly | 1,128 | 1,032 | 0.91 |
| sharedHandlerPaysDispatch | 240,405 | 240,465 | 1.00 |
| suspensionFusesContinuation | 240,051 | 240,104 | 1.00 |
| userTypesSkipKernelWrapping | 177,040 | 177,224 | 1.00 |
| evalFixedOverhead | ~0 | ~0 | zero both |
| fusionAllocatesNothing | 0.006 | 0.004 | zero both |
| inlineLimitKeepsZeroAllocation | 0.015 | 0.010 | zero both |
| inlineLimitCostsTimeNotAllocation | 724,418 | 743,346 | 1.03 |
| statefulAnswersPaySuccessor | 1,040,139 | 1,118,177 | 1.08 |
| foreignCrossingsPayRotation | 1,680,202 | 1,840,331 | 1.10 |
| uncachedValuesPayBoxingOnly | 141,777 | 155,752 | 1.10 |
| continuationBodiesFuse | 56,072 | 64,144 | 1.14 |
| suspensionBaseline | 560,080 | 640,145 | 1.14 |
| fusionAfterSuspension | 408,437 | 1,073,154 | 2.63 |
| fusionAfterSuspensionRunOnly | 0.002 | 1,288 | zero old, 1,288 new |
| partialSuspensionBaseline | kernel2-only | 640,145 | - |

## Reading

- The old kernel is quadratic on the trailing-maps shape (issue 531): 473ms and 1.6GB per op
  at 10,000 levels, against the new kernel's linear 672us and 2.96MB. This dominates any
  aggregate view of the board.
- The new kernel wins time on 14 of 19 shared rows, typically 0.43x to 0.78x, including every
  baseline row (suspension, handler answering, boxing, fusion, budget rescues).
- The new kernel's weak family is maps attached to an unanswered suspension: building and
  answering per level (fusionAfterSuspension, 3.09x time and 2.63x allocation) and running a
  stored answered chain (fusionAfterSuspensionRunOnly, 2.93x time and 1,288 B/op where the old
  kernel runs allocation-free). The allocation source was previously attributed by JFR to the
  Flat windows minted per stored step plus the flatten array, with pre-linked nodes as the named
  fix direction.
- Smaller new-kernel losses: sharedHandlerPaysDispatch 1.21x time at byte-parity allocation,
  foreignCrossingsPayRotation 1.16x time and 1.10x allocation, continuationBodiesFuse 1.09x time
  and 1.14x allocation, statefulAnswersPaySuccessor allocation 1.08x (one 32-byte StateNode per
  answer, the price of capturable state; time is 0.72x in the new kernel's favor here).
- Per-suspension storage is 64 bytes in the new kernel against 56 in the old
  (suspensionBaseline, 10,000 suspensions per op), 1.14x.
