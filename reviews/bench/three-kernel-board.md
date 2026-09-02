# Three kernels on the ProtoBench rows: main (CPS), the Arrow kernel, the proto

Same bodies per row across the three kernels (`KernelBench` mirror rows for main, `ProtoKernelBench` for the Arrow
kernel, `ProtoBench` for the proto). Time is avgt us/op, allocation is gc.alloc.rate.norm B/op. Depth rows run
10,000 operations per op, Narrow rows 1,000.

Sources:
- **main (CPS)**: `kernel-comparison-board.md` old-kernel column, 2026-08-11, 3 forks, 5+5 x 1s, gc profiler, same machine.
- **Arrow**: `ProtoKernelBench` today (2026-09-01), 3 forks, gc profiler, same session as the proto run; cells marked † are
  the 2026-08-18 `-f 1` screen (`reviews/bench/screen-0818-f1-head-kernel.json`, time only) where today's run had not
  reached the row yet, and allocation marked † is the 2026-08-11 board's kernel2 column.
- **proto**: `ProtoBench` today, commit b32f3b1318, 3 forks, gc profiler; the fifteen rows ported from
  `KernelBench` on 2026-09-01 (userTypes, inlineLimit, fusionAfterSuspension, partial, sharedHandler,
  foreignCrossings, dynamicChain, pureIteration, effectfulIteration) are a single fork with gc, no Arrow
  column yet, and main and the 2026-08-11 kernel2 column where that board had the row.

Status: 🟢 proto within 5% of that kernel or better, 🟡 5% to 25% behind, 🔴 more than 25% behind, ⚪ no data for that kernel.

## Time (us/op)

| row | main (CPS) | Arrow | proto | proto/main | proto/Arrow | vs main | vs Arrow |
|---|---|---|---|---|---|---|---|
| evalFixedOverhead | 0.009 ± 0.001 | 0.014 ± 0.001 | 0.002 ± 0.001 | 0.22 | 0.14 | 🟢 | 🟢 |
| fusionAllocatesNothing | 0.830 ± 0.010 | 0.085 ± 0.001 | 0.079 ± 0.001 | 0.10 | 0.93 | 🟢 | 🟢 |
| fusionPastBudgetPaysRescuesOnly | 48.4 ± 0.7 | 43.6 ± 0.9 | 45.1 ± 0.2 | 0.93 | 1.03 | 🟢 | 🟢 |
| uncachedValuesPayBoxingOnly | 74.7 ± 1.3 | 46.6 ± 0.8 | 49.3 ± 0.3 | 0.66 | 1.06 | 🟢 | 🟡 |
| deepRecursionPaysRescuesOnly | 55.1 ± 0.6 | 49.2 ± 0.3 | 49.8 ± 0.3 | 0.90 | 1.01 | 🟢 | 🟢 |
| deepRecursionNoRescue | - | 1.530 ± 0.025 | 1.568 ± 0.038 | - | 1.02 | ⚪ | 🟢 |
| deepRecursionOneRescue | - | 2.871 ± 0.022 | 2.830 ± 0.009 | - | 0.99 | ⚪ | 🟢 |
| deferBindPerStep | - | 65.5 ± 0.5 | 12.9 ± 0.1 | - | 0.20 | ⚪ | 🟢 |
| deferBindUnderIdleHandler | - | 67.6 ± 1.1 | 17.2 ± 0.4 | - | 0.25 | ⚪ | 🟢 |
| deferBindUnderTrailingMap | - | 44.3 ± 0.2 | 29.7 ± 0.6 | - | 0.67 | ⚪ | 🟢 |
| nestedPayloadsUnwrapInMaps | - | 6.068 ± 0.058 | 5.976 ± 0.032 | - | 0.98 | ⚪ | 🟢 |
| suspensionBaseline | 127.7 ± 3.6 | 83.6 ± 0.6 | 76.6 ± 0.8 | 0.60 | 0.92 | 🟢 | 🟢 |
| suspensionFusesContinuation | 70.2 ± 7.0 | 43.8 ± 0.5 | 33.3 ± 0.2 | 0.47 | 0.76 | 🟢 | 🟢 |
| idleHandlerAddsNothing | 48.8 ± 0.8 | 43.6 ± 0.3 | 45.2 ± 0.3 | 0.93 | 1.04 | 🟢 | 🟢 |
| continuationBodiesFuse | 24.8 ± 0.4 | 8.845 ± 0.210 | 10.4 ± 0.5 | 0.42 | 1.18 | 🟢 | 🟡 |
| trailingMapsStayLinear | 472,770.9 ± 10,199.1 | 644.5 ± 12.7 | 289.8 ± 3.8 | 0.00 | 0.45 | 🟢 | 🟢 |
| handleLoopAnswersInPlace | 134.8 ± 1.2 | 79.5 ± 0.6 | 42.7 ± 0.3 | 0.32 | 0.54 | 🟢 | 🟢 |
| handleLoopFusesContinuation | - | 80.0 ± 1.6 | 42.8 ± 0.2 | - | 0.54 | ⚪ | 🟢 |
| statefulAnswersPaySuccessor | 153.7 ± 2.5 | 87.0 ± 0.5 | 40.7 ± 0.3 | 0.26 | 0.47 | 🟢 | 🟢 |
| emittingClausesPayRegionRebuild | - | 144.3 ± 1.1 | 77.1 ± 2.2 | - | 0.53 | ⚪ | 🟢 |
| dynamicChainOfBindsStaysLinear | - | - | 3.495 ± 0.130 | - | - | ⚪ | ⚪ |
| dynamicChainOfMapsStaysLinear | - | - | 4.107 ± 0.021 | - | - | ⚪ | ⚪ |
| effectfulIterationViaArrow | - | - | 76.3 ± 3.3 | - | - | ⚪ | ⚪ |
| effectfulIterationViaLoop | - | - | 224.7 ± 7.0 | - | - | ⚪ | ⚪ |
| foreignCrossingsPayRotation | 325.0 ± 2.5 | 377.2 ± 3.9† | 1,430.4 ± 90.2 | 4.40 | 3.79 | 🔴 | 🔴 |
| fusionAfterSuspension | 88.0 ± 5.4 | 271.5 ± 4.3† | 127.9 ± 2.2 | 1.45 | 0.47 | 🔴 | 🟢 |
| fusionAfterSuspensionRunOnly | 0.283 ± 0.001 | 0.829 ± 0.016† | 0.526 ± 0.012 | 1.86 | 0.63 | 🔴 | 🟢 |
| inlineLimitCostsTimeNotAllocation | 347.0 ± 7.7 | 248.3 ± 2.8† | 283.8 ± 4.7 | 0.82 | 1.14 | 🟢 | 🟡 |
| inlineLimitKeepsZeroAllocation | 2.167 ± 0.008 | 1.385 ± 0.010† | 1.132 ± 0.032 | 0.52 | 0.82 | 🟢 | 🟢 |
| partialSuspensionBaseline | - | 82.3 ± 1.0† | 108.9 ± 1.6 | - | 1.32 | ⚪ | 🔴 |
| pureIterationViaArrow | - | - | 73.6 ± 47.6 | - | - | ⚪ | ⚪ |
| pureIterationViaLoop | - | - | 16.0 ± 0.5 | - | - | ⚪ | ⚪ |
| pureIterationViaMethod | - | - | 64.0 ± 1.2 | - | - | ⚪ | ⚪ |
| sharedHandlerPaysDispatch | 140.8 ± 6.0 | 169.7 ± 2.2† | 166.1 ± 6.0 | 1.18 | 0.98 | 🟡 | 🟢 |
| userTypesSkipKernelWrapping | 43.9 ± 1.1 | 34.4 ± 1.0† | 50.5 ± 0.9 | 1.15 | 1.47 | 🟡 | 🔴 |

## Allocation (B/op)

| row | main (CPS) | Arrow | proto | proto/main | proto/Arrow | vs main | vs Arrow |
|---|---|---|---|---|---|---|---|
| evalFixedOverhead | 0 | 0 | 0 | - | - | ⚪ | ⚪ |
| fusionAllocatesNothing | 0 | 0 | 0 | - | - | ⚪ | ⚪ |
| fusionPastBudgetPaysRescuesOnly | 1,128 | 664 | 488 | 0.43 | 0.74 | 🟢 | 🟢 |
| uncachedValuesPayBoxingOnly | 141,777 | 155,376 | 155,200 | 1.09 | 1.00 | 🟡 | 🟢 |
| deepRecursionPaysRescuesOnly | 2,128 | 912 | 608 | 0.29 | 0.67 | 🟢 | 🟢 |
| deepRecursionNoRescue | - | 0 | 0 | - | - | ⚪ | ⚪ |
| deepRecursionOneRescue | - | 48 | 32 | - | 0.67 | ⚪ | 🟢 |
| deferBindPerStep | - | 80,096 | 64,080 | - | 0.80 | ⚪ | 🟢 |
| deferBindUnderIdleHandler | - | 80,152 | 64,136 | - | 0.80 | ⚪ | 🟢 |
| deferBindUnderTrailingMap | - | 144,176 | 112,144 | - | 0.78 | ⚪ | 🟢 |
| nestedPayloadsUnwrapInMaps | - | 32,080 | 32,064 | - | 1.00 | ⚪ | 🟢 |
| suspensionBaseline | 560,080 | 642,009 | 480,121 | 0.86 | 0.75 | 🟢 | 🟢 |
| suspensionFusesContinuation | 240,051 | 241,968 | 240,096 | 1.00 | 0.99 | 🟢 | 🟢 |
| idleHandlerAddsNothing | 1,224 | 704 | 528 | 0.43 | 0.75 | 🟢 | 🟢 |
| continuationBodiesFuse | 56,072 | 64,304 | 48,120 | 0.86 | 0.75 | 🟢 | 🟢 |
| trailingMapsStayLinear | 1,601,202,471 | 2,320,956 | 1,600,642 | 0.00 | 0.69 | 🟢 | 🟢 |
| handleLoopAnswersInPlace | 960,134 | 642,009 | 480,136 | 0.50 | 0.75 | 🟢 | 🟢 |
| handleLoopFusesContinuation | - | 642,025 | 480,152 | - | 0.75 | ⚪ | 🟢 |
| statefulAnswersPaySuccessor | 1,040,139 | 643,273 | 480,160 | 0.46 | 0.75 | 🟢 | 🟢 |
| emittingClausesPayRegionRebuild | - | 176,305 | 176,281 | - | 1.00 | ⚪ | 🟢 |
| dynamicChainOfBindsStaysLinear | - | - | 13,984 | - | - | ⚪ | ⚪ |
| dynamicChainOfMapsStaysLinear | - | - | 13,984 | - | - | ⚪ | ⚪ |
| effectfulIterationViaArrow | - | - | 480,137 | - | - | ⚪ | ⚪ |
| effectfulIterationViaLoop | - | - | 1,120,226 | - | - | ⚪ | ⚪ |
| foreignCrossingsPayRotation | 1,680,202 | 1,840,331† | 2,280,554 | 1.36 | 1.24 | 🔴 | 🟡 |
| fusionAfterSuspension | 408,437 | 1,073,154† | 536,609 | 1.31 | 0.50 | 🔴 | 🟢 |
| fusionAfterSuspensionRunOnly | 0 | 1,288† | 1,240 | - | 0.96 | ⚪ | 🟢 |
| inlineLimitCostsTimeNotAllocation | 724,418 | 743,346† | 738,402 | 1.02 | 0.99 | 🟢 | 🟢 |
| inlineLimitKeepsZeroAllocation | 0 | 0† | 0 | - | - | ⚪ | ⚪ |
| partialSuspensionBaseline | 0 | 640,145† | 480,121 | - | 0.75 | ⚪ | 🟢 |
| pureIterationViaArrow | - | - | 158,625 | - | - | ⚪ | ⚪ |
| pureIterationViaLoop | - | - | 160,048 | - | - | ⚪ | ⚪ |
| pureIterationViaMethod | - | - | 158,456 | - | - | ⚪ | ⚪ |
| sharedHandlerPaysDispatch | 240,405 | 240,465† | 240,457 | 1.00 | 1.00 | 🟢 | 🟢 |
| userTypesSkipKernelWrapping | 177,040 | 177,224† | 176,672 | 1.00 | 1.00 | 🟢 | 🟢 |
