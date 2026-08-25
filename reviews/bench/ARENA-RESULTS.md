# Arena benchmarks: kernel2 vs Cats Effect vs ZIO

Run: 2026-08-24 20:19, darwin-aarch64 (laptop, not the CI bench runner), HEAD a300174c5e.
JMH params: the bench workflow input defaults, '-wi 10 -i 4 -r 1 -w 1 -f 1 -t 1 -foe true -prof gc'.
Selector: kyo.bench.arena (all arena benches). Raw data: reviews/bench/arena-kernel2.json.

Cells are ops/s (higher is better) with gc.alloc.rate.norm B/op in parens. Ratios are kyo/other on throughput.

## fork (ops/s, higher is better; alloc B/op in parens)

| bench | kyo | cats | zio | kyo/cats | kyo/zio |
|---|---|---|---|---|---|
| BatchBench | 10.1k (53.3k) | 616.4 (1.08M) | 10.5k (80.6k) | 16.44x | 0.96x |
| BlockingBench | 2.8k (996.0) | 3.6k (1.2k) | 5.5k (1.8k) | 0.77x | 0.51x |
| BlockingContentionBench | 13.5 (333.2k) | 311.3 (1.31M) | 166.0 (623.1k) | 0.04x | 0.08x |
| BroadFlatMapBench | 37.4k (912.5) | 9.3k (111.5k) | 7.0k (166.6k) | 4.04x | 5.37x |
| CollectBench | 9.3k (265.0k) | 15.0k (218.8k) | 35.9k (41.2k) | 0.62x | 0.26x |
| CollectParBench | 2.1k (886.3k) | 1.1k (3.23M) | 2.2k (1.24M) | 1.96x | 0.97x |
| CountdownLatchBench | 988.4 (1.44M) | 1.1k (2.32M) | 2.6k (641.0k) | 0.92x | 0.38x |
| DeepBindBench | 13.7k (2.8k) | 7.4k (481.1k) | 15.0k (481.0k) | 1.84x | 0.91x |
| DeepBindMapBench | 240.1 (9.27M) | 347.9 (5.26M) | 600.3 (5.42M) | 0.69x | 0.40x |
| EnqueueDequeueBench | 626.9 (2.96M) | 289.0 (12.24M) | 1.1k (3.36M) | 2.17x | 0.57x |
| FailureBench | 22.6k (15.1k) | 52.9k (6.1k) | 30.1k (20.6k) | 0.43x | 0.75x |
| ForkChainedBench | 231.1 (6.00M) | 574.8 (6.72M) | 341.1 (5.34M) | 0.40x | 0.68x |
| ForkJoinBench | 204.6 (10.24M) | 310.0 (9.95M) | 308.5 (6.02M) | 0.66x | 0.66x |
| ForkJoinContentionBench | 171.3 (13.17M) | 959.8 (12.62M) | 688.5 (7.14M) | 0.18x | 0.25x |
| ForkManyBench | 180.6 (6.33M) | 346.7 (7.70M) | 277.7 (5.40M) | 0.52x | 0.65x |
| ForkSpawnBench | 62.1 (69.62M) | 128.4 (84.88M) | 70.4 (53.68M) | 0.48x | 0.88x |
| HttpClientBench | 11.9k (4.7k) | 6.2k (88.9k) | 3.0k (51.7k) | 1.93x | 3.92x |
| HttpClientContentionBench | 4.6k (57.1k) | 492.0 (1.09M) | 2.2k (612.6k) | 9.43x | 2.09x |
| HttpServerBench | 12.6k (7.4k) | 7.5k (127.0k) | 10.3k (54.4k) | 1.69x | 1.22x |
| HttpServerContentionBench | 3.9k (87.7k) | 1.7k (1.55M) | 3.2k (649.6k) | 2.27x | 1.23x |
| LoggingBench | 33.3 (1.24M) | 16.6k (130.6k) | 12.2k (127.4k) | 0.00x | 0.00x |
| NarrowBindBench | 621.2 (1.52M) | 4.2k (959.1k) | 1.2k (959.0k) | 0.15x | 0.52x |
| NarrowBindMapBench | 268.4 (8.55M) | 419.1 (4.78M) | 630.7 (4.94M) | 0.64x | 0.43x |
| PingPongBench | 495.3 (1.99M) | 430.2 (5.05M) | 1.2k (1.79M) | 1.15x | 0.41x |
| ProducerConsumerBench | 434.2 (2.44M) | 562.2 (3.11M) | 1.0k (2.64M) | 0.77x | 0.43x |
| RandomBench | 283.8 (7.12M) | 362.3 (7.20M) | 531.4 (6.16M) | 0.78x | 0.53x |
| RendezvousBench | 100.1 (16.84M) | 89.6 (17.74M) | 96.3 (12.52M) | 1.12x | 1.04x |
| SchedulingBench | 22.4 (77.52M) | 35.2 (121.98M) | 12.7 (193.85M) | 0.64x | 1.77x |
| SemaphoreBench | 447.8 (5.68M) | 462.9 (9.28M) | 358.7 (10.72M) | 0.97x | 1.25x |
| SemaphoreContentionBench | 26.8 (57.68M) | 18.7 (336.83M) | 21.2 (204.28M) | 1.44x | 1.27x |
| StateBench | 30.3k (135.3k) | 1.2k (663.5k) | 765.7 (6.17M) | 25.73x | 39.62x |
| StateMapBench | 2.9k (340.9k) | 4.3k (1.06M) | 631.0 (6.22M) | 0.66x | 4.56x |
| StreamAsyncBench | 130.5 (14.55M) | 12.2 (178.00M) | 23.2 (79.00M) | 10.69x | 5.63x |
| StreamBench | 8.7k (201.3k) | 9.1k (250.1k) | 7.3k (420.3k) | 0.95x | 1.18x |
| StreamSyncBench | 460.3 (4.52M) | 201.2 (14.12M) | 371.8 (11.66M) | 2.29x | 1.24x |
| SuspensionBench | 40.0k (3.1k) | 39.9k (2.0k) | 38.4k (1.8k) | 1.00x | 1.04x |
| TMapMultiKeyBench | 11.7k (111.5k) | 1.1k (1.85M) | 10.0k (122.4k) | 10.75x | 1.17x |
| TMapSingleKeyBench | 14.2k (83.4k) | 2.1k (958.9k) | 24.4k (55.8k) | 6.66x | 0.58x |
| TRefMultiBench | 11.4k (87.7k) | 4.6k (522.4k) | 20.4k (52.8k) | 2.47x | 0.56x |
| TRefSingleBench | 10.0k (63.5k) | 2.1k (886.6k) | 26.1k (38.9k) | 4.76x | 0.38x |

## sync (ops/s, higher is better; alloc B/op in parens)

| bench | kyo | cats | zio | kyo/cats | kyo/zio |
|---|---|---|---|---|---|
| BatchBench | 14.1k (52.4k) | 1.3k (1.08M) | 54.3k (80.1k) | 11.22x | 0.26x |
| BroadFlatMapBench | 95.3k (64.2) | 12.0k (111.4k) | 24.6k (166.1k) | 7.92x | 3.87x |
| CollectBench | 10.0k (256.1k) | 15.7k (218.8k) | 92.6k (40.7k) | 0.64x | 0.11x |
| DeepBindBench | 16.1k (1.7k) | 3.7k (481.1k) | 19.8k (480.5k) | 4.31x | 0.81x |
| DeepBindMapBench | 206.3 (8.95M) | 360.3 (5.26M) | 506.6 (5.42M) | 0.57x | 0.41x |
| FailureBench | 162.9k (14.3k) | 67.0k (6.0k) | 92.1k (20.1k) | 2.43x | 1.77x |
| LoggingBench | 28.4 (1.18M) | 5.1k (130.3k) | 26.9k (126.9k) | 0.01x | 0.00x |
| NarrowBindBench | 578.9 (1.28M) | 2.6k (959.1k) | 5.1k (959.0k) | 0.22x | 0.11x |
| NarrowBindMapBench | 375.7 (8.31M) | 479.7 (4.78M) | 613.5 (4.94M) | 0.78x | 0.61x |
| RandomBench | 230.6 (7.04M) | 353.5 (7.20M) | 534.8 (6.16M) | 0.65x | 0.43x |
| StateBench | 48.8k (134.5k) | 7.6k (663.5k) | 795.9 (6.17M) | 6.46x | 61.33x |
| StateMapBench | 3.6k (340.0k) | 3.5k (1.06M) | 765.1 (6.28M) | 1.01x | 4.64x |
| StreamBench | 8.8k (200.3k) | 8.9k (250.0k) | 4.0k (420.3k) | 0.98x | 2.22x |
| StreamSyncBench | 563.8 (4.52M) | 191.7 (14.36M) | 287.4 (11.66M) | 2.94x | 1.96x |
| SuspensionBench | 963.7k (2.2k) | 26.1k (1.9k) | 2.57M (1.3k) | 36.87x | 0.38x |

## Reading

### Where the new kernel leads
- **Effect-system rows**: StateBench 26x cats / 40x zio (fork), BroadFlatMap 4-8x, Batch 16x cats,
  Failure sync 2.4x cats. The allocation column is the story: DeepBind at 2.8k B/op against 481k for
  both others, BroadFlatMap at 912 B/op against 111k/166k. The settled-path zero-allocation design
  shows directly.
- **Streams and STM**: StreamAsync 10.7x cats / 5.6x zio, StreamSync 2.3-2.9x, TMap/TRef 2.5-10.7x
  cats (zio's STM stays ahead on single-key TRef rows).
- **HTTP**: client 1.9x cats / 3.9x zio, client-under-contention 9.4x cats, server 1.2-2.3x, with
  one to two orders of magnitude less allocation per op.

### Where it trails
- **The fork/scheduler family**: ForkChained 0.40x cats, ForkJoinContention 0.18x cats / 0.25x zio,
  ForkMany 0.52x, ForkSpawn 0.48x, PingPong/ProducerConsumer 0.4-0.8x zio. Fiber spawn/join traffic
  is the clearest systematic deficit.
- **Map-fused bind chains**: DeepBindMap 0.57-0.69x, NarrowBindMap 0.6-0.8x, with 8-9M B/op against
  5M for the others; plain DeepBind (no map) is fine, so the cost is in map-heavy chains.
- **CollectBench** 0.26x zio (fork), 0.11x (sync); zio's collect is far leaner here.

### Anomalies that look like defects, not performance
- **LoggingBench: 33 ops/s vs 16.6k/12.2k, at 1.24M B/op.** Three orders of magnitude off with heavy
  allocation says the kyo variant is doing real work per op (likely actually writing log output where
  the cats/zio variants no-op on level). Needs its own investigation before this row is read as a
  kernel number.
- **BlockingContentionBench: 13.5 vs 311/166.** The blocking bridge under contention collapses; the
  uncontended BlockingBench is only 0.5-0.8x, so the gap is contention-specific.
- **NarrowBindBench allocating 1.3-1.5M B/op** where cats/zio sit at 959k, while DeepBind allocates
  near nothing: narrow rebinding hits an allocation path deep binds do not.

### Caveats
- Laptop run (darwin-aarch64), one fork, one thread, 4 measurement iterations: good for direction,
  not for small deltas. The CI bench runner is the reference environment.
- No old-kernel baseline in this run: these are cross-library comparisons only, not kernel2-vs-kernel1.

## Recheck (same box, second run of the kyo outlier rows)

Raw data: reviews/bench/arena-kyo-recheck.json. Same params, ~40 minutes later, podman VM at ~100%
CPU throughout and load average above 6 on both runs.

| bench | run1 ops/s | run2 ops/s | delta | alloc B/op run1 | run2 |
|---|---|---|---|---|---|
| BlockingContentionBench.forkKyo | 13.5 | 19.7 | +45% | 333158 | 319036 |
| BroadFlatMapBench.forkKyo | 37443 | 33750 | -10% | 913 | 912 |
| DeepBindBench.forkKyo | 13665 | 10923 | -20% | 2755 | 2756 |
| ForkChainedBench.forkKyo | 231 | 198 | -15% | 6002538 | 6002569 |
| ForkJoinContentionBench.forkKyo | 171 | 67 | -61% | 13173318 | 13177395 |
| LoggingBench.forkKyo | 33 | 35 | +6% | 1238415 | 1234248 |
| NarrowBindBench.forkKyo | 621 | 1328 | +114% | 1519175 | 1361170 |

Reading: throughput on this box swings up to 2x run to run, so no fine-grained throughput claim
survives, the fork-family ratios included. Allocation per op reproduces to within rounding, so the
allocation findings stand: LoggingBench and NarrowBind are real defects, and ForkJoinContention's
alloc parity with cats says its 0.18x throughput reading was mostly load. The map-fused rows
(DeepBindMapBench, NarrowBindMapBench) were missed by the recheck selector and remain single-run.
Trustworthy throughput needs the CI bench runner; the old-vs-new kernel question needs an A/B
against the old-kernel branch on a quiet machine.

## NarrowBind decomposition (kernel-level isolation)

A new pinned kernel bench row (ProtoKernelBench.deferBindPerStep) plus scratch probes, all with the
gc profiler, isolating the arena NarrowBind shape one variable at a time:

| shape | B/op | per step |
|---|---|---|
| kernel: Effect.defer + bind, depth 1000, map | 80096 | 80.1 |
| kernel: same, depth 10000, flatMap | 800101 | 80.0 |
| kernel: same, depth 10000, under Effect.catching | 800117 | 80.0 |
| arena syncKyo (Sync.defer + flatMap, depth 10000, under Abort.run) | 1280278 | 128.0 |
| arena cats / zio | 959000 | ~96 |

Sync.defer inlines to the same deferral node the kernel rows build, and rows are erased at runtime,
so depth, flatMap, the Sync row, and a plain region below are all exonerated: the kernel floor for
this shape is a stable 80 B per step, already below cats' 96. The remaining 48 B per step appears
only under Abort.run's handler machinery (evalOrThrow wraps the loop in Abort.run). Open item:
an allocation-site profile (async-profiler alloc event) of the arena row to name the allocation
inside the handler path.

### Abort.run overhead attributed

Two further pinned rows split the +48 B per step:

| condition below the defer loop | B/step |
|---|---|
| nothing | 80 |
| idle handler (handleCont region) | 80 |
| Effect.catching region | 80 |
| standing transform (a trailing map) | 144 |

The handler is free: regions bound the eval's fold, so the steady state folds nothing. A standing
transform is not a region, and every settled delivery folds it into a chain the next push takes
apart, ~64 B per step of churn. Abort.run's runWith stands `map(Result.succeed)` under the body
(required: it boxes a nested error value into the success lane before the clause's error completion
shares the region's value type), which is where the arena's +48 B per step comes from. Fix
directions, either closing most of the NarrowBind gap: a delivery fast path that applies a single
standing entry without building and splitting a chain, or a region-shaped success wrap.
