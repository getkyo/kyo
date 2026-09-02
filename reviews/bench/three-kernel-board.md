# Three kernels on the ProtoBench rows: main (CPS), the Arrow kernel, the proto

Same bodies per row across the three kernels (`KernelBench` mirror rows for main, `ProtoKernelBench` for the Arrow
kernel, `ProtoBench` for the proto). Time is avgt us/op, allocation is gc.alloc.rate.norm B/op. Depth rows run
10,000 operations per op, Narrow rows 1,000.

Sources:
- **main (CPS)**: `kernel-comparison-board.md` old-kernel column, 2026-08-11, 3 forks, 5+5 x 1s, gc profiler, same machine.
- **Arrow**: `ProtoKernelBench` today (2026-09-01), 3 forks, gc profiler, same session as the proto run; cells marked † are
  the 2026-08-18 `-f 1` screen (`reviews/bench/screen-0818-f1-head-kernel.json`, time only) where today's run had not
  reached the row yet, and allocation marked † is the 2026-08-11 board's kernel2 column.
- **proto**: `ProtoBench` today, commit 32ee518804, 3 forks, gc profiler.

Status: 🟢 proto within 5% of that kernel or better, 🟡 5% to 25% behind, 🔴 more than 25% behind, ⚪ no data for that kernel.

## Time (us/op)

| row | main (CPS) | Arrow | proto | proto/main | proto/Arrow | vs main | vs Arrow |
|---|---|---|---|---|---|---|---|
| evalFixedOverhead | 0.009 ± 0.001 | 0.014 ± 0.001 | 0.002 ± 0.001 | 0.22 | 0.14 | 🟢 | 🟢 |
| fusionAllocatesNothing | 0.830 ± 0.010 | 0.085 ± 0.001 | 0.081 ± 0.003 | 0.10 | 0.95 | 🟢 | 🟢 |
| fusionPastBudgetPaysRescuesOnly | 48.4 ± 0.7 | 43.6 ± 0.9 | 46.1 ± 1.3 | 0.95 | 1.06 | 🟢 | 🟡 |
| uncachedValuesPayBoxingOnly | 74.7 ± 1.3 | 46.6 ± 0.8 | 51.1 ± 0.7 | 0.68 | 1.10 | 🟢 | 🟡 |
| deepRecursionPaysRescuesOnly | 55.1 ± 0.6 | 49.2 ± 0.3 | 50.5 ± 0.6 | 0.92 | 1.03 | 🟢 | 🟢 |
| deepRecursionNoRescue | - | 1.530 ± 0.025 | 1.552 ± 0.022 | - | 1.01 | ⚪ | 🟢 |
| deepRecursionOneRescue | - | 2.871 ± 0.022 | 2.871 ± 0.023 | - | 1.00 | ⚪ | 🟢 |
| deferBindPerStep | - | 65.5 ± 0.5 | 13.3 ± 0.3 | - | 0.20 | ⚪ | 🟢 |
| deferBindUnderIdleHandler | - | 67.6 ± 1.1 | 19.1 ± 1.0 | - | 0.28 | ⚪ | 🟢 |
| deferBindUnderTrailingMap | - | 44.3 ± 0.2 | 39.7 ± 1.1 | - | 0.90 | ⚪ | 🟢 |
| nestedPayloadsUnwrapInMaps | - | 6.068 ± 0.058 | 6.078 ± 0.028 | - | 1.00 | ⚪ | 🟢 |
| suspensionBaseline | 127.7 ± 3.6 | 83.6 ± 0.6 | 80.5 ± 2.7 | 0.63 | 0.96 | 🟢 | 🟢 |
| suspensionFusesContinuation | 70.2 ± 7.0 | 43.8 ± 0.5 | 34.6 ± 0.6 | 0.49 | 0.79 | 🟢 | 🟢 |
| idleHandlerAddsNothing | 48.8 ± 0.8 | 43.6 ± 0.3 | 46.3 ± 1.5 | 0.95 | 1.06 | 🟢 | 🟡 |
| continuationBodiesFuse | 24.8 ± 0.4 | 8.845 ± 0.210 | 9.970 ± 0.654 | 0.40 | 1.13 | 🟢 | 🟡 |
| trailingMapsStayLinear | 472,770.9 ± 10,199.1 | 644.5 ± 12.7 | 298.8 ± 11.1 | 0.00 | 0.46 | 🟢 | 🟢 |
| handleLoopAnswersInPlace | 134.8 ± 1.2 | 79.5 ± 0.6 | 43.4 ± 0.3 | 0.32 | 0.55 | 🟢 | 🟢 |
| handleLoopFusesContinuation | - | 80.0 ± 1.6 | 43.2 ± 0.3 | - | 0.54 | ⚪ | 🟢 |
| statefulAnswersPaySuccessor | 153.7 ± 2.5 | 87.0 ± 0.5 | 41.4 ± 0.5 | 0.27 | 0.48 | 🟢 | 🟢 |
| emittingClausesPayRegionRebuild | - | 144.3 ± 1.1 | 79.0 ± 2.4 | - | 0.55 | ⚪ | 🟢 |

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
