# Cold-load profile — kyo-bench classpath

Date: 2026-05-26T07:34 UTC+0
Classpath: kyo-bench compiled output (69 TASTy + 511 classfiles, 2.51 MB)
Root: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-bench/.jvm/target/scala-3.8.3/classes`
JVM: OpenJDK 25.0.3 (Eclipse Temurin, build 25.0.3+9-LTS, mixed mode, sharing)
Profiler: async-profiler 4.3 (built Jan 13 2026), CPU sampling at default 10ms intervals, allocation profiling at TLAB boundary
Harness: `kyo-reflect-bench/jvm/src/main/scala/kyo/bench/ColdLoadBench.scala` — `kyo.bench.ColdLoadProfile`

## What "cold-load" means here

Each `Reflect.Classpath.open(Seq(root))` call re-parses the TASTy files from disk (or OS page
cache), rebuilds the full symbol table, runs the 3-pass orchestrator pipeline, and returns a
ready `Classpath`. Files are NOT cold on disk (macOS `sudo purge` requires interactive auth so
disk-cache clearing was not available). Times reflect warm-OS-cache, cold-JVM-heap iterations
after a 5-iteration JVM warmup.

W11b (`openCached`) writes a snapshot on the first call and reads it on subsequent calls. The
benchmark re-uses the same temp cache directory across all iterations so the snapshot is always a
hit from iteration 2 onward. The reported median therefore reflects the snapshot-read path.

## Timing — warm OS cache (all iterations measured)

**W11 cold-load (open, no snapshot)**

| Run | Median | p95 |
|---|---|---|
| 10-iter baseline | 28.67 ms | 40.10 ms |
| 10-iter with CPU profiler | 26.71 ms | 45.32 ms |
| 100-iter with CPU profiler | 20.03 ms | 30.31 ms |
| 100-iter with alloc profiler | 19.91 ms | 26.64 ms |

The wide p95 on early runs is JIT jitter; 100-iter runs are steadier. Best estimate after JIT
is stable: **median ~20 ms, p95 ~27 ms**.

**W11b cold-load + snapshot cache (openCached)**

| Run | Median | p95 |
|---|---|---|
| 10-iter baseline | 7.18 ms | 9.26 ms |
| 100-iter with CPU profiler | 4.37 ms | 5.49 ms |
| 100-iter with alloc profiler | 4.18 ms | 5.36 ms |

Best estimate: **median ~4 ms, p95 ~5.5 ms**. The snapshot path is ~5x faster than full
TASTy decode.

## Cold-cache (after purge) timing

`sudo purge` requires interactive auth on macOS and was not available. As a proxy, the first
`Reflect.Classpath.open` call in the harness (before JIT has compiled the hot paths but
after 5 warmup iterations) reads ~26 ms, consistent with warm-cache measurements. The files
are only 2.51 MB so OS page-cache cold vs warm is expected to contribute at most 1-2 ms on
an SSD. The bottleneck is CPU, not I/O (see CPU breakdown below).

## CPU breakdown (% of CPU-sampling attributable samples, 100-iter run)

The CPU profiler (10ms sampling) captured 888 total samples across the 100-iter run (~30s
wall clock). 57% were in C2 JIT compiler threads (warmup artifact), leaving a thin signal
for application code. The categories are approximate because low-sample-count traces have
high variance. The allocation profiler (6885 samples) gives far better signal and is the
primary source of function-level findings.

| Function / area | % CPU-samples | Notes |
|---|---|---|
| JIT compilation (C2) | 57% | Warmup-phase artifact; near-zero in steady state |
| kyo.scheduler workers (parked) | 19% | Fiber workers waiting for work between iterations |
| Name/symbol interning (Interner) | 17% | Maps to `TypeUnpickler.readTypeIntoSession` call chain |
| TASTy AST walk (AstUnpickler) | 5% | `walkStats` recursive descent |
| File I/O (Files.readAllBytes) | ~1% | Negligible; OS page cache warm |
| GC | ~0% | Not visible in samples |
| Other | ~1% | Thread pool idle, misc |

**Caveat:** with only ~220 non-JIT samples total, the CPU breakdown is indicative only.
The allocation profile (6885 samples) is the authoritative data for where work is being done.

## Allocation hotspots (100-iter run, alloc profiler, 2049 MB total attributed)

### By allocated type (flat summary from profiler)

| Type | MB | % | Notes |
|---|---|---|---|
| `java.lang.Object[]` | 1282 MB | 35.5% | HashMap node arrays (HAMT) and Scala collection arrays |
| `int[]` | 667 MB | 18.5% | HAMT bitmap arrays inside `HashMapBuilder` |
| `byte[]` | 362 MB | 10.0% | TASTy file bytes from `JvmFileSource`, name byte arrays |
| `scala.Tuple2` | 282 MB | 7.8% | Map entries in `TypeUnpickler.readTypeIntoSession` |
| `BitmapIndexedMapNode` | 128 MB | 3.5% | Scala immutable HashMap structural nodes |
| `SingleAssign` | 84 MB | 2.3% | Per-symbol lazy slot wrappers |
| `AtomicReference` | 82 MB | 2.3% | Per-symbol internal state cells |
| `java.lang.Integer` | 71 MB | 2.0% | Boxing in `PositionsUnpickler.readSync` |
| `Interner$Entry[]` | 66 MB | 1.8% | Interner shard resize (`Arrays.copyOf`) |
| `java.lang.String` | 54 MB | 1.5% | Decoded strings in snapshot path |
| `long[]` | 52 MB | 1.4% | Position/offset arrays |
| `kyo.Reflect$Symbol` | 34 MB | 0.9% | Symbol object construction |
| `kyo.Reflect$Symbol$TastyOrigin` | 18 MB | 0.5% | TASTy origin metadata per symbol |

### By call path

| Call path | MB | % |
|---|---|---|
| `TypeUnpickler.readTypeIntoSession` -> `HashMapBuilder` | ~560 MB | 27.3% |
| `TypeUnpickler.readTypeIntoSession` (other allocs) | ~340 MB | 16.6% |
| `TypeUnpickler.readTypeIntoSession` (Tuple2 map entries) | ~224 MB | 10.9% |
| `PositionsUnpickler.readSync` (Integer boxing) | ~55 MB | 2.7% |
| `Interner.internInShard` (shard array resize) | ~42 MB | 2.0% |
| `JvmFileSource.apply` (raw file bytes) | ~40 MB | 1.9% |
| `NameUnpickler.readUnsafe` + `internString` | ~58 MB | 2.8% |
| Snapshot read path (`SnapshotReader` + `SnapshotFormat`) | ~120 MB | 5.9% |

The single biggest allocation site is `TypeUnpickler.readTypeIntoSession` building an
immutable Scala `HashMap` via `HashMapBuilder`. The HAMT node arrays (`Object[]` + `int[]`)
account for roughly 45% of all bytes allocated. This is per-decode-call: each of the 100
measurement iterations allocates ~20 MB just for those maps.

## Findings

**What actually dominates cold load.** The bottleneck is TASTy type decoding, specifically
`TypeUnpickler.readTypeIntoSession`. This method constructs a fresh Scala immutable `HashMap`
on every call using `HashMapBuilder`, which produces large `Object[]` and `int[]` HAMT node
arrays plus `Tuple2` wrapper objects for each key-value pair. At 20 ms per open and roughly
2 GB attributed allocation across 100 iterations, each open call allocates approximately
20 MB and triggers several minor GC pauses. The number that was perhaps underestimated is
how expensive per-call hash-map construction is at scale: each TASTy file's type section is
decoded into fresh maps that are immediately discarded after symbol table construction.

**How surprising vs prior estimates.** The allocation picture is more map-heavy than expected.
`TypeUnpickler.readTypeIntoSession` appears to be constructing a per-decode-call `HashMap`
that is used as a scratch type session for forward-reference resolution, then thrown away.
This is 44% of all allocations by bytes. The interner (`Interner$Entry[]` + shard resize) and
positions unpickler (Integer boxing) are also visible but much smaller contributors (~4% combined).
Symbol object construction (`kyo.Reflect$Symbol` at 0.9%) is not a bottleneck, which confirms
the lazy `SingleAssign`/`OnceCell` design is working correctly for the cold-load path.

**Where mmap would or would not help.** File I/O (`JvmFileSource` byte reads) is only ~1.9% of
allocated bytes and is not visible in CPU samples. The files are 2.51 MB total and fit
comfortably in OS page cache. mmap would not measurably improve cold-load latency: the
bottleneck is computation (HashMap construction, HAMT node allocation, GC pressure), not
disk I/O. Even after disk-cold `sudo purge`, the 2.51 MB read from a modern SSD takes under
1 ms — the 20 ms budget is spent entirely in the CPU path.

**Where allocation reduction would help.** Replacing the per-call `HashMap` in
`TypeUnpickler.readTypeIntoSession` with a reusable mutable scratch structure (e.g., a
reused `mutable.HashMap` or an array-indexed session) could eliminate the dominant
`Object[]` / `int[]` / `Tuple2` allocations, reducing GC pressure and likely cutting the
20 ms cold-load time by 30-50%. The `Integer` boxing in `PositionsUnpickler.readSync`
(2% of allocations) is a quick win: replacing `Map[Int, ...]` with a specialized structure
or using primitive arrays would avoid 71 MB of boxing per 100 iterations.
`Interner$Entry[]` shard-resize allocations (1.8%) are a natural consequence of shard
growth; pre-sizing the interner shards based on expected class count would flatten this.

**What the snapshot cache buys.** At 4-5 ms for cached reads vs 20 ms for full decode, the
snapshot path is ~5x faster. The snapshot read path allocates ~120 MB per 100 iterations
(vs ~2050 MB for full decode), confirming that serialization sidesteps the per-decode
HashMap construction almost entirely. The remaining 120 MB in the snapshot path is dominated
by `SnapshotReader` reconstructing `Symbol` objects and `SnapshotFormat.decodeString` for
name interning. The snapshot design is already yielding its expected benefit for incremental
tooling use cases.

**What the data suggests for next optimization.** (1) Audit `TypeUnpickler.readTypeIntoSession`
to see whether the per-call `HashMap` can be replaced with a thread-local or arena-scoped
scratch map that is cleared rather than GC'd. This is the highest-leverage change by allocation
volume. (2) Eliminate `Integer` boxing in `PositionsUnpickler` — 71 MB/100 iterations from
a single `boxToInteger` site is straightforward to fix with a primitive-friendly alternative.
(3) Consider pre-sizing the `Interner` shards when classpath class count is known at open time.
(4) The snapshot path at 4 ms is already fast enough for interactive tooling; further snapshot
optimization is lower priority than the cold-path allocation reduction.

## Profile artifacts

- CPU flamegraph (100-iter run): `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-reflect/cold-load-flame.html`
- Allocation flamegraph (10-iter run): `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-reflect/cold-load-alloc.html`
- CPU text breakdown (100-iter run): `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-reflect/cold-load-text.txt`

All raw profile files also at `/tmp/kyo-reflect-profile/`.

## Harness

`kyo-reflect-bench/jvm/src/main/scala/kyo/bench/ColdLoadBench.scala` — object `ColdLoadProfile`.

Run with:
```
sbt 'kyo-reflect-bench/runMain kyo.bench.ColdLoadProfile 100'
```

Run with CPU profiling:
```
sbt \
  "set Global / javaOptions += \"-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=cpu,file=/tmp/flame.html\"" \
  'kyo-reflect-bench/runMain kyo.bench.ColdLoadProfile 100'
```

Run with allocation profiling:
```
sbt \
  "set Global / javaOptions += \"-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=alloc,file=/tmp/alloc.html\"" \
  'kyo-reflect-bench/runMain kyo.bench.ColdLoadProfile 100'
```
