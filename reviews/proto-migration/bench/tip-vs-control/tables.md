## Time, ProtoBench, C V C V C at -f 1

Control band is the spread between the three control legs of the same code; a delta inside it is noise. Rows marked confirmed were re-measured at -f 3 (six control forks against three tip forks), and the confirmed numbers are shown.

| row | control us/op | tip us/op | delta | control band | status |
|---|---:|---:|---:|---:|:---:|
| `continuationBodiesFuse` | 18.358 | 9.832 | -46.4% | 7.1% | 🟢 improvement (confirmed at -f 3) |
| `deepRecursionNoRescue` | 1.531 | 1.512 | -1.3% | 1.0% | ✅ flat |
| `deepRecursionOneRescue` | 2.779 | 2.768 | -0.4% | 0.7% | ✅ flat |
| `deepRecursionPaysRescuesOnly` | 49.088 | 48.625 | -0.9% | 4.2% | ✅ flat |
| `deferBindPerStep` | 12.955 | 12.837 | -0.9% | 5.1% | ✅ flat |
| `deferBindUnderIdleHandler` | 17.234 | 16.954 | -1.6% | 8.4% | ✅ flat |
| `deferBindUnderTrailingMap` | 29.297 | 29.210 | -0.3% | 2.1% | ✅ flat |
| `dynamicChainOfBindsStaysLinear` | 4.050 | 4.046 | -0.1% | 1.0% | ✅ flat |
| `dynamicChainOfMapsStaysLinear` | 4.071 | 4.039 | -0.8% | 1.4% | ✅ flat |
| `effectfulIterationViaArrow` | 78.845 | 77.121 | -2.2% | 3.8% | ✅ flat |
| `effectfulIterationViaLoop` | 227.069 | 220.732 | -2.8% | 5.3% | ✅ flat |
| `emittingClausesPayRegionRebuild` | 78.655 | 75.536 | -4.0% | 6.3% | ✅ flat |
| `evalFixedOverhead` | 0.002 | 0.002 | -1.1% | 2.3% | ✅ flat |
| `foreignCrossingsAnsweredInPlace` | 668.890 | 632.651 | -5.4% | 5.0% | 🟢 improvement |
| `foreignCrossingsPayRotation` | 1089.642 | 1104.318 | +1.3% | 3.0% | ✅ flat (confirmed at -f 3) |
| `fusionAfterSuspension` | 129.870 | 130.295 | +0.3% | 4.6% | ✅ flat (confirmed at -f 3) |
| `fusionAfterSuspensionRunOnly` | 0.529 | 0.527 | -0.4% | 2.9% | ✅ flat |
| `fusionAllocatesNothing` | 0.081 | 0.078 | -3.5% | 9.3% | ✅ flat |
| `fusionPastBudgetPaysRescuesOnly` | 46.237 | 45.619 | -1.3% | 3.7% | ✅ flat |
| `handleLoopAnswersInPlace` | 43.310 | 42.847 | -1.1% | 1.4% | ✅ flat |
| `handleLoopFusesContinuation` | 42.981 | 42.796 | -0.4% | 0.4% | ✅ flat |
| `idleHandlerAddsNothing` | 45.478 | 45.330 | -0.3% | 0.9% | ✅ flat |
| `inlineLimitCostsTimeNotAllocation` | 281.957 | 280.085 | -0.7% | 1.4% | ✅ flat |
| `inlineLimitKeepsZeroAllocation` | 1.121 | 1.122 | +0.1% | 0.8% | ✅ flat |
| `nestedPayloadsUnwrapInMaps` | 6.006 | 6.001 | -0.1% | 0.6% | ✅ flat |
| `partialSuspensionBaseline` | 115.200 | 107.542 | -6.6% | 21.6% | ✅ flat |
| `pureIterationViaArrow` | 93.595 | 91.071 | -2.7% | 42.9% | ✅ flat |
| `pureIterationViaLoop` | 15.862 | 15.891 | +0.2% | 0.6% | ✅ flat |
| `pureIterationViaMethod` | 65.180 | 64.360 | -1.3% | 8.1% | ✅ flat (confirmed at -f 3) |
| `sharedHandlerPaysDispatch` | 166.151 | 165.677 | -0.3% | 3.7% | ✅ flat |
| `statefulAnswersPaySuccessor` | 40.772 | 40.971 | +0.5% | 1.2% | ✅ flat |
| `suspensionBaseline` | 76.366 | 76.563 | +0.3% | 4.1% | ✅ flat |
| `suspensionFusesContinuation` | 40.881 | 35.829 | -12.4% | 45.9% | ✅ flat |
| `trailingMapsStayLinear` | 298.644 | 293.021 | -1.9% | 5.3% | ✅ flat (confirmed at -f 3) |
| `uncachedValuesPayBoxingOnly` | 49.705 | 49.589 | -0.2% | 3.9% | ✅ flat |
| `userTypesSkipKernelWrapping` | 50.152 | 50.265 | +0.2% | 1.5% | ✅ flat |
| `contextReadsUnderBindings` | 41.524 | 39.133 | -5.8% | 1.7% | 🟢 improvement (new row, -f 3 only) |
| `contextRegionsPayEntryExit` | 31.297 | 73.161 | +133.8% | 1.7% | 🔴 regression (new row, -f 3 only) |

## Allocation, gc.alloc.rate.norm

| row | control B/op | tip B/op | delta B/op | status |
|---|---:|---:|---:|:---:|
| `continuationBodiesFuse` | 48,120.2 | 48,120.1 | -0.1 | ✅ identical |
| `deepRecursionNoRescue` | 0.0 | 0.0 | -0.0 | ✅ identical |
| `deepRecursionOneRescue` | 32.0 | 32.0 | -0.0 | ✅ identical |
| `deepRecursionPaysRescuesOnly` | 608.4 | 608.4 | -0.0 | ✅ identical |
| `deferBindPerStep` | 64,080.1 | 64,080.1 | -0.0 | ✅ identical |
| `deferBindUnderIdleHandler` | 64,136.2 | 64,136.2 | -0.0 | ✅ identical |
| `deferBindUnderTrailingMap` | 112,144.3 | 112,144.3 | -0.0 | ✅ identical |
| `dynamicChainOfBindsStaysLinear` | 13,984.0 | 13,984.0 | -0.0 | ✅ identical |
| `dynamicChainOfMapsStaysLinear` | 13,984.0 | 13,984.0 | -0.0 | ✅ identical |
| `effectfulIterationViaArrow` | 480,136.7 | 480,136.7 | -0.0 | ✅ identical |
| `effectfulIterationViaLoop` | 1,120,226.0 | 1,120,226.0 | -0.1 | ✅ identical |
| `emittingClausesPayRegionRebuild` | 176,280.7 | 176,280.7 | -0.0 | ✅ identical |
| `evalFixedOverhead` | 0.0 | 0.0 | -0.0 | ✅ identical |
| `foreignCrossingsAnsweredInPlace` | 1,520,286.0 | 1,520,285.7 | -0.3 | ✅ identical |
| `foreignCrossingsPayRotation` | 2,240,361.8 | 2,480,385.5 | +240,023.7 | 🔴 more |
| `fusionAfterSuspension` | 536,609.2 | 536,609.2 | +0.0 | ✅ identical |
| `fusionAfterSuspensionRunOnly` | 1,240.0 | 1,240.0 | -0.0 | ✅ identical |
| `fusionAllocatesNothing` | 0.0 | 0.0 | -0.0 | ✅ identical |
| `fusionPastBudgetPaysRescuesOnly` | 488.4 | 488.4 | -0.0 | ✅ identical |
| `handleLoopAnswersInPlace` | 480,136.4 | 480,136.4 | -0.0 | ✅ identical |
| `handleLoopFusesContinuation` | 480,152.4 | 480,152.4 | -0.0 | ✅ identical |
| `idleHandlerAddsNothing` | 528.4 | 528.4 | -0.0 | ✅ identical |
| `inlineLimitCostsTimeNotAllocation` | 738,402.5 | 738,402.5 | -0.0 | ✅ identical |
| `inlineLimitKeepsZeroAllocation` | 0.0 | 0.0 | +0.0 | ✅ identical |
| `nestedPayloadsUnwrapInMaps` | 32,064.1 | 32,064.1 | -0.0 | ✅ identical |
| `partialSuspensionBaseline` | 480,121.0 | 480,121.0 | -0.1 | ✅ identical |
| `pureIterationViaArrow` | 158,624.8 | 158,624.8 | -0.0 | ✅ identical |
| `pureIterationViaLoop` | 160,048.1 | 160,048.1 | +0.0 | ✅ identical |
| `pureIterationViaMethod` | 158,456.6 | 158,456.6 | -0.0 | ✅ identical |
| `sharedHandlerPaysDispatch` | 240,457.5 | 240,457.5 | -0.0 | ✅ identical |
| `statefulAnswersPaySuccessor` | 480,160.4 | 480,160.4 | +0.0 | ✅ identical |
| `suspensionBaseline` | 480,120.7 | 480,120.7 | +0.0 | ✅ identical |
| `suspensionFusesContinuation` | 240,096.4 | 240,096.3 | -0.0 | ✅ identical |
| `trailingMapsStayLinear` | 1,600,642.8 | 1,600,642.6 | -0.1 | ✅ identical |
| `uncachedValuesPayBoxingOnly` | 155,200.4 | 155,200.4 | -0.0 | ✅ identical |
| `userTypesSkipKernelWrapping` | 176,672.5 | 176,672.5 | +0.0 | ✅ identical |
| `contextReadsUnderBindings` | 48,336.3 | 48,312.3 | -24.0 | 🟢 less (new row) |
| `contextRegionsPayEntryExit` | 120,136.2 | 112,128.5 | -8,007.7 | 🟢 less (new row) |
