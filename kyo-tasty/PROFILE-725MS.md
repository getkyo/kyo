# kyo-tasty Cold-Load Profile: Post-.toMap Removal Baseline

Baseline measured after commit `98750f904` (drop .toMap HAMT conversions).
Bench: `kyo.bench.ColdLoadFullBench`, full kyo-bench classpath.
Roots: 122 jars / directories. Entries processed: 5,981 (5,949 TASTy + 32 module-info.class).
Uncompressed payload: 57 MB TASTy bytes read per cold-load.
Runtime: JVM, Apple M3 Pro, 12 vCPUs.

**Measured medians (5 warmup + 5 measure):**
- W11-full cold-load (no snapshot): **~786-840 ms** (run-to-run OS page-cache variance)
- W11b-full cold-load + snapshot:   **~361-370 ms**


## 1. Phase Timing (instrumentation: `-Dkyo.reflect.timing=true`)

Representative clean runs (stable OS page-cache, no GC spike):

| Run | list (ms) | decode (ms) | merge (ms) | finalize (ms) | total (ms) |
|-----|-----------|-------------|------------|---------------|------------|
| A   | 396       | 599         | 599        | 207           | 807        |
| B   | 391       | 445         | 445        | 316           | 762        |
| C   | 445       | 490         | 490        | 275           | 765        |

**Notes on columns:**
- `list` is the wall-clock timestamp when the producer (Phase A, walkRoot) finished. Decode starts immediately so this represents the time until all JAR entries are enqueued.
- `decode` and `merge` are both recorded when those stages finish. Because the merger runs concurrently, `merge` time approximately equals `decode` time (the merger drains as fast as decoders produce).
- `finalize` is wall-clock from start to end of `finalizeMerge` (phases: placeholderResolve + assignSymbolFields + transitionToReady).
- All values are wall-clock from `t_start`, so `decode` already includes `list` time.

**Decode breakdown (cumulative nanoseconds across 12 decoder threads):**

| Sub-phase          | Cumulative (ms) | Critical-path est. (~div 12) |
|--------------------|-----------------|-------------------------------|
| TastyHeader        | ~0              | ~0                            |
| NameUnpickler      | 2-5             | ~0.4                          |
| SectionIndex       | 1-2             | ~0.2                          |
| AttributeUnpickler | 1-3             | ~0.2                          |
| AstUnpickler Pass1 | 2-4             | ~0.3                          |
| PositionsUnpickler | 2-3             | ~0.2                          |
| CommentsUnpickler  | 1-2             | ~0.1                          |

Decode unpickler CPU is negligible vs I/O. The entire decode stage wall-clock (445-599 ms above) is almost entirely jar read I/O latency.

**Jar I/O breakdown:**
- Jars opened: 5,912
- Jar construct (open + central-directory parse): 9-13 ms
- Jar read (mmap entry reads): 246-273 ms cumulative across 12 threads

**Summary:** ~550 ms goes to Phase A+B (I/O dominated), ~275 ms to finalize.


## 2. Jaeger Per-Span Timing

One representative trace (traceID `1763aea0`, total 808.8 ms, closest to measured median):

| Span Name                   | Count | Sum (ms) | p50 (ms) | Max (ms) | First-off (ms) | Last-end (ms) |
|-----------------------------|-------|----------|----------|----------|----------------|---------------|
| walkRoot                    | 122   | 10159.7  | 19.29    | 463.72   | 2.1            | 479.3         |
| decoder                     | 12    | 6211.3   | 476.05   | 602.58   | 23.4           | 628.6         |
| merger                      | 1     | 626.7    | 626.69   | 626.69   | 2.1            | 628.8         |
| finalize.assignSymbolFields | 1     | 148.0    | 147.96   | 147.96   | 631.5          | 779.5         |
| finalize.transitionToReady  | 1     | 10.3     | 10.28    | 10.28    | 779.5          | 789.8         |
| finalize.placeholderResolve | 1     | 2.6      | 2.57     | 2.57     | 628.9          | 631.5         |
| coldLoad                    | 1     | 808.8    | 808.78   | 808.78   | 0.0            | 808.8         |

**Variance across all 12 traces (ms):**

| Trace total | merger  | f.placeholderResolve | f.assignSymbolFields | f.transitionToReady |
|-------------|---------|----------------------|----------------------|---------------------|
| 723.8       | 501.4   | 3.6                  | 187.6                | 10.7                |
| 731.1       | 411.5   | 6.3                  | 281.1                | 10.6                |
| 775.1       | 585.1   | 2.7                  | 154.5                | 10.6                |
| 808.8       | 626.7   | 2.6                  | 148.0                | 10.3                |
| 911.5       | 642.2   | 7.3                  | 227.7                | 10.7                |
| 1045.0      | 670.4   | 4.0                  | 162.3                | **185.2**           |
| 1161.9      | 791.9   | 3.0                  | 157.1                | **188.6**           |
| 1326.2      | 876.6   | 3.4                  | 208.3                | **212.6**           |
| 1421.2      | 875.1   | 5.0                  | 256.2                | **261.4**           |
| 1467.3      | 842.8   | 14.1                 | 310.1                | **263.1**           |
| 2026.6      | 1685.0  | 8.4                  | 252.5                | 11.2                |
| 2573.4      | 1991.4  | 9.7                  | 229.5                | **258.7**           |

**Medians (12 traces):** total=1045 ms, merger=656 ms, assignSymbolFields=228 ms, transitionToReady=~100 ms (bimodal: 10 ms or 185-263 ms).

**Key observation:** `finalize.transitionToReady` is bimodal. In 6 of 12 traces it takes 10 ms (trivial: just constructs `State.Ready` and sets the AtomicRef). In the other 6 traces it takes 185-263 ms. This strongly indicates a GC pause attributed to that span window, not real work inside `transitionToReady`. The actual `State.Ready` constructor + atomic set is sub-millisecond.

`finalize.placeholderResolve` is consistently cheap (2-14 ms) because the `.toMap` that was previously in `finalizeMerge` has been removed; only the slot-replace loop over placeholders remains.

`finalize.assignSymbolFields` is the real deterministic finalize cost: **148-310 ms** range, median ~228 ms.


## 3. Allocation Top 10 (async-profiler, alloc mode)

Total samples: 45,246. Grand total implied: ~23 GB allocations across full bench run (all warmup + measure iterations combined).

| # | Size (MB) | % of total | Alloc type | Root call site |
|---|-----------|------------|------------|----------------|
| 1 | 647.5     | 2.70%      | byte[]     | `Tasty.Symbol$.computeFullName` via `mkString(".")` |
| 2 | 604.7     | 2.52%      | byte[]     | `JarMappedReader.readEntry` (jar byte copy) |
| 3 | 417.5     | 1.74%      | Object[]   | `ArrayBuffer.<init>` in `computeFullName` (parts buffer) |
| 4 | 396.0     | 1.65%      | Object[]   | `ArrayBuffer.<init>` via `mkString` intermediate builder |
| 5 | 365.5     | 1.53%      | byte[]     | `Tasty.Name.apply` (UTF-8 encode in `computeFullName` result) |
| 6 | 344.5     | 1.44%      | byte[]     | `StringBuilder.result` in `mkString` (String copy) |
| 7 | 333.0     | 1.39%      | byte[]     | `JarMappedReader.readEntry` (second callsite, same pattern) |
| 8 | 319.6     | 1.33%      | byte[]     | `SnapshotWriter.serialize` (ByteArrayOutputStream expand) |
| 9 | 291.0     | 1.21%      | Tasty$Symbol | `SnapshotReader.readSymbolsMapped` (Symbol allocation) |
| 10| 288.5     | 1.20%      | byte[]     | `SnapshotFormat.decodeString` (String decode in snapshot read) |

**Top 10 combined: 3.91 GB / 16.7% of total.**

The #1, #3, #4, #5, #6 sites all trace back to a single call: `Tasty.Symbol$.computeFullName` called from `ClasspathOrchestrator.$anonfun$9`. That lambda (lines 563-565 of `ClasspathOrchestrator.scala`) calls `sym.fullName` which triggers `computeFullName` for every symbol in every decoded file. `computeFullName` allocates:
- a fresh `mutable.ArrayBuffer[String]` per call (site #3)
- `String` segments via `.asString` on each `Name` up the owner chain (site #4 via mkString internals)
- `StringBuilder.result` String (site #6)
- UTF-8 encode for `Name(full)` at the end (site #5)
- `mkString(".")` `byte[]` intermediates (site #1)

Sites #2 and #7 are jar byte reads (unavoidable for non-mmap paths, or mmap-mapped copies).


## 4. Where Is Wall-Clock Going Now?

The dominant wall-clock cost is split between two regions. Phase A+B (I/O pipeline) accounts for roughly 550 ms: the producer (walkRoot) walks 5,912 jars and the 12 decoder fibers spend ~445-599 ms wall-clock waiting on mmap-served byte reads and decoding 5,949 TASTy files. The decode unpickler CPU (per the breakdown counters) is under 5 ms cumulative critical path; essentially all Phase A+B wall-clock is I/O latency. The finalize stage accounts for roughly 275 ms of the remaining time: `assignSymbolFields` (~228 ms median) makes three full sweeps over `allSyms` (the complete symbol set) to set `_parents`, `_typeParams`, `_declarations`, `_declaredType`, `_scaladoc`, and `_position` on every symbol that was not covered by the per-file loops. The `transitionToReady` span occasionally shows 185-263 ms but this is a GC pause during or immediately after the large allocation phase of `assignSymbolFields`, not real work inside the span.


## 5. Next-Optimization Candidates

**Rank 1: Eliminate per-symbol `computeFullName` allocation in `decodeTastyBytes` (impact: ~200-300 ms alloc pressure, likely 50-100 ms wall-clock reduction)**

Sites #1, #3, #4, #5, #6 all come from `ClasspathOrchestrator.$anonfun$9` calling `sym.fullName` per symbol. `computeFullName` allocates a new `ArrayBuffer`, builds String segments, `mkString`s them, then calls `Name(full)` which UTF-8-encodes the result and interns it. Fix sketch: store the full-name `Name` directly on the symbol during `AstUnpickler.readPass1` (at construction time, when the owner chain is already being assembled), eliminating the post-hoc chain walk entirely. Alternatively, cache the full-name lazily on the symbol with a `@volatile var` to avoid repeated recomputation on multi-iteration benchmarks.

**Rank 2: Eliminate three `allSyms` sweeps in `assignSymbolFields` (impact: ~150-230 ms wall-clock, deterministic)**

`assignSymbolFields` performs six full iterations over `allSyms` (plus per-FileResult inner loops). Three of those are "set to default if not set" passes: for `_parents`, `_typeParams`, `_declarations`, `_declaredType`, `_scaladoc`, `_position`. Fix sketch: merge all six default-set sweeps into a single pass, or better, track which symbols were written during the per-file loops using a `BitSet` keyed by symbol index, then sweep only unwritten symbols. The per-file loops already write the majority; the sweep only needs to cover the remainder. A single combined sweep should cut this span from ~228 ms to ~40-60 ms.

**Rank 3: Reduce jar byte-copy allocations in `JarMappedReader.readEntry` (impact: ~940 MB alloc pressure, moderate wall-clock)**

Sites #2 and #7 allocate fresh `byte[]` on every `readEntry` call for all 5,949 TASTy files. The mmap path already maps the JAR data into virtual memory, but `readEntry` still copies bytes into a new array for the decoder. Fix sketch: introduce a `ByteView` that wraps a `MappedByteBuffer` slice directly (offset + length), deferring or eliminating the copy. The `ByteView` abstraction already exists in `kyo.internal.tasty.binary.ByteView`; extend it to support a `MappedByteBuffer`-backed variant. Decoders (`NameUnpickler`, `AstUnpickler`, etc.) would then read directly from mapped memory, eliminating ~940 MB of per-load byte[] allocation and the associated GC pressure.
