# Phase 8 Prep — Re-profile and verification

## Baseline reminder (from COLD-LOAD-PROFILE-FULL.md)

| Metric | Value |
|---|---|
| Cold-load median | 55 ms |
| Cold-load p95 | 68 ms |
| Snapshot median | 57 ms |
| Snapshot p95 | 76 ms |
| Total TASTy files | 5,949 |
| Total class files | 34,580 |
| Total jars | 121 |
| Total bytes (uncompressed) | 250.49 MB |

Raw cold-load measurement series (5 warmup + 10 measurement):

```
warmup 1: 448 ms   (JVM cold, JIT nothing compiled)
warmup 2: 102 ms   (JIT beginning)
warmup 3:  78 ms
warmup 4:  69 ms
warmup 5:  64 ms   (JIT largely settled)
iter  1:   62 ms
iter  2:   58 ms
iter  3:   55 ms
iter  4:   56 ms
iter  5:   49 ms
iter  6:   48 ms
iter  7:   50 ms
iter  8:   50 ms
iter  9:   53 ms
iter 10:   68 ms
```

Raw snapshot series (5 warmup + 10 measurement):

```
warmup 1: 63 ms
warmup 2: 63 ms
warmup 3: 58 ms
warmup 4: 60 ms
warmup 5: 56 ms
iter  1:  57 ms
iter  2:  53 ms
iter  3:  55 ms
iter  4:  57 ms
iter  5:  76 ms
iter  6:  66 ms
iter  7:  60 ms
iter  8:  57 ms
iter  9:  56 ms
iter 10:  51 ms
```

Dominant baseline CPU hotspots:

| Function / area | % CPU-samples |
|---|---|
| `__psynch_mutexwait` | 29.4% |
| `__psynch_cvwait` | 8.3% |
| `__open` | 6.4% |
| `__psynch_mutexdrop` | 3.4% |
| `pthread_jit_write_protect_np` | 2.0% |
| `scala.runtime.BoxesRunTime.boxToInteger` | 0.32% |

Dominant baseline allocations:

| Type | % of alloc samples |
|---|---|
| `byte[]` (JAR CEN + inflate + path concat) | 29.3% |
| `java.lang.Object[]` (HAMT + builders) | 15.0% |
| `java.util.jar.JarFile$JarFileEntry` | 12.3% |
| `int[]` (HAMT bitmap + CEN integers) | 8.6% |
| `java.lang.String` (path building) | 4.1% |
| `scala.Tuple2` (phase C map entries) | 2.95% |
| `long[]` (positions + zip offsets) | 2.2% |
| `kyo.Abort$$anon$2` | 1.36% |
| `kyo.Fiber$...$$anon$61` | 1.30% |
| `kyo.Abort$$anon$1` | 1.27% |
| `scala.collection.immutable.BitmapIndexedMapNode` | 1.07% |
| `kyo.internal.reflect.symbol.Interner$Entry[]` | 0.94% |
| `kyo.Closed` | 0.78% |
| `java.lang.Integer` | 0.73% |
| `kyo.internal.reflect.query.ClasspathOrchestrator$$anon$16` | 0.73% |

Key causes of baseline snapshot = cold-load (no speedup):

- `DigestComputer.compute` called `source.list(root, ".tasty")` per root, reopening every JAR
  a third time per iteration (two opens already in Phase A, one more in DigestComputer).
- Digest was based on entry enumeration (O(entries) CEN reads), not jar `stat()`, so the
  snapshot check paid the same JAR overhead as a full cold-load.

## Acceptance criteria (from plan)

- Cold-load median <= 25 ms (from 55 ms baseline) — approximately 2.2x improvement
- Snapshot median <= 5 ms (from 57 ms baseline) — approximately 11x improvement
- All existing 245+ kyo-reflect tests green on JVM, JS, and Native

## Run commands

Uninstrumented baseline (same harness and args as baseline measurement):

```
sbt -J-Xmx4G -J-Xss8m 'kyo-reflect-bench/runMain kyo.bench.ColdLoadFullBench 5 10'
```

CPU profile (forked JVM, output to new file to avoid overwriting baseline):

```
sbt -J-Xmx4G -J-Xss8m \
  'set (`kyo-reflect-bench`.jvm) / run / javaOptions ++= Seq("-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=cpu,file=/tmp/full-cpu-after.html,flamegraph")' \
  'kyo-reflect-bench/runMain kyo.bench.ColdLoadFullBench 5 10'
```

Allocation profile (forked JVM, text format for table extraction):

```
sbt -J-Xmx4G -J-Xss8m \
  'set (`kyo-reflect-bench`.jvm) / run / javaOptions ++= Seq("-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=alloc,file=/tmp/full-alloc-after.txt,output=text")' \
  'kyo-reflect-bench/runMain kyo.bench.ColdLoadFullBench 5 10'
```

Full test suite per platform (sequential — never run JVM/JS/Native in parallel):

```
sbt 'kyo-reflectJVM/test' 2>&1 | tail -20
sbt 'kyo-reflectJS/test' 2>&1 | tail -20
sbt 'kyo-reflectNative/test' 2>&1 | tail -20
```

Note: the bench harness is JVM-only. It requires the kyo-bench compiled classes directory
(`kyo-bench/.jvm/target/scala-3.8.3/classes`) and the classpath file at `/tmp/kyo-bench-cp.txt`.
Regenerate the classpath file before running if the build has changed:

```
sbt 'show kyo-bench/fullClasspath' 2>&1 | grep -oE '/[^ ]*\.jar' | sort -u > /tmp/kyo-bench-cp.txt
```

## What the produced report contains

`COLD-LOAD-PROFILE-AFTER.md` mirrors COLD-LOAD-PROFILE-FULL.md's structure:

1. Cold-load timing table: 5 warmup + 10 measurement, all raw iteration values, median + p95
2. Snapshot timing table: same structure
3. CPU breakdown table (top hotspots, % CPU samples from async-profiler)
4. Allocation hotspots table (top 15 types, % of TLAB-crossing samples)
5. Top call paths (by allocation bytes, same format as baseline "Top call paths" section)
6. Before/after comparison table (baseline vs after, for both median and p95)
7. Per-phase contribution analysis (how much each phase contributed to the improvement, derived
   from the measured data and cross-referenced against the expected deltas below)
8. Findings narrative: remaining hotspots, any new hotspots introduced, Channel mutex overhead
   (if visible), per-phase assessment

## Per-phase expected impact (hypothesis — Phase 8 measures actual)

| Phase | Optimization | Expected delta on cold-load | Expected delta on snapshot |
|---|---|---|---|
| 1 | Single-pass JAR enum via direct CEN reader: eliminates `JarFile$JarFileEntry` (12.3% alloc), halves `byte[]` CEN alloc (~2%), eliminates per-entry path string concat (2.20% alloc), reduces `__open` syscalls from 3 opens/jar to 1 | 10-15 ms reduction | small reduction (Phase A savings apply to both paths) |
| 2 | Digest by jar metadata (`stat()` mtime + size instead of CEN entry enumeration): eliminates the third JAR open per iteration in DigestComputer | minimal cold-load impact (Phase A already improved by Phase 1) | ~50 ms reduction (the dominant snapshot win: snapshot check drops from O(entries) to O(jars) `stat` calls, making snapshot path near-zero for an unchanged classpath) |
| 3 | Streaming pipeline via Channels: overlaps enumeration with decode and merge, eliminating two sequential phase boundaries that leave workers parked | 5-10 ms reduction (reduces idle time that shows up as `__psynch_mutexwait` / `__psynch_cvwait`) | same proportional benefit |
| 4 | Defer `toMap` in AstUnpickler: `mutable.HashMap` in `Pass1Result` and `FileResult`, eliminating `BitmapIndexedMapNode` (1.07%) and much of `Tuple2` (2.95%) allocation | 1-3 ms (GC pressure reduction, fewer young-gen collections) | same |
| 5 | `IntMap` in PositionsUnpickler: eliminates `Integer` boxing (0.73% alloc, 0.32% CPU in `boxToInteger`) | <1 ms direct; GC pressure reduction | same |
| 6 | Interner pre-sizing: `initialShardCapacity = (entryCount / 128) * 2` eliminates `Interner$Entry[]` resize allocation (0.94%) | <1 ms direct; reduced shard resize count | same |

Total expected cold-load delta: approximately 17-30 ms reduction (55 ms to 25-38 ms). The
acceptance target is 25 ms (lower bound of expected range).

Total expected snapshot delta: approximately 50-55 ms reduction (57 ms to 2-7 ms). The
acceptance target is 5 ms (lower bound of expected range). Phase 2 is the dominant driver: it
converts snapshot validation from O(entries CEN reads) to O(jars stat calls), collapsing the
snapshot path cost from ~57 ms to the cost of 121 `stat` calls plus `SnapshotReader.readMapped`.

## Risk: targets may not be met

**Cold-load misses 25 ms but lands in 30-40 ms range:**

The most likely cause is that per-jar overhead has an irreducible floor at the `ZipFile` level
even after Phase 1's direct CEN reader. Specifically: `ZipFile$Source.initCEN` reads the full
CEN `byte[]` into memory on every `ZipFile` open (confirmed in profile at 1.97% + 1.74%
allocation samples). Phase 1 replaces `JarFile.entries()` iteration but does not eliminate the
`ZipFile` open itself — the CEN still needs to be read to locate entries. Further reduction
would require caching open `ZipFile` handles across cold-load iterations (across the bench's
measurement loop) or using `java.nio.file.FileSystems.newFileSystem` with a keep-open flag, both
of which are out of scope for this plan. If the target is missed, document the residual floor
in `COLD-LOAD-PROFILE-AFTER.md` and flag for a follow-up plan.

**Snapshot misses 5 ms but lands in 10-20 ms range:**

Most likely cause: `SnapshotReader.readMapped` has non-trivial overhead on a large snapshot
file. At full classpath scale (5,949 TASTy files), the `.krfl` snapshot file can be tens to
hundreds of MB. `readMapped` uses `FileChannel.map` (mmap) which is cheap for the OS but
may still require page faults on first access if the OS has evicted the file. Re-profile the
snapshot path in isolation to measure `readMapped` vs digest-computation costs and identify
which dominates the residual. Also check whether the snapshot write in warmup iterations
leaves the file large enough to cause read overhead.

**If targets are missed:** write measurements and root-cause analysis in `COLD-LOAD-PROFILE-AFTER.md`
and flag explicitly for supervisor decision: accept the partial win, or open a follow-up plan
for the remaining gap.

## Cross-platform note

Phase 8 reruns the full test suite on JVM, JS, and Native. Sequential — never in parallel
(per STEERING rule on sequential cross-platform test runs). Resource contention between
platforms (Kyo scheduler thread counts, potential port conflicts in network tests) makes
parallel runs unreliable.

The bench harness (`ColdLoadFullBench`) runs JVM only. JS and Native do not have access to
the real kyo-bench JAR classpath (it is a JVM artifact), so JS/Native performance is not
benchmarked. JS/Native coverage is limited to correctness (test suite).

The sbt plugin (Phase 7) also runs JVM only. Its scripted tests cover JVM snapshot
loadability. JS/Native snapshot loadability is covered by the existing `SnapshotRoundTripTest`,
which runs on all three platforms.

## Concerns

**Is 25 ms cold-load realistic given the architectural floor of per-jar overhead?**

The 121-jar classpath requires 121 `ZipFile` opens per cold-load iteration regardless of
Phase 1 (the CEN must be read to list entries). Each open allocates a CEN `byte[]` and pays
one `__open` syscall. The `__open` samples are 6.4% of CPU time at baseline. Phase 1 reduces
from 3 opens/jar to 1 open/jar, which should cut the `__open` contribution by approximately
3x, from ~3.5 ms to ~1.2 ms. The CEN `byte[]` allocation should drop by the same factor.
This alone does not reach 25 ms; Phases 3-6 must contribute the remaining 5-10 ms. The
target is achievable only if Phase 3 (pipelining) successfully converts the `__psynch_mutexwait`
and `__psynch_cvwait` idle time (29.4% + 8.3% = 37.7% of CPU samples) into useful work.
If the pipelining overhead (Channel lock/unlock, extra fibers) exceeds the idle-time savings,
Phase 3 may not contribute as expected. Phase 8 re-profiling is the definitive check.

**Is 5 ms snapshot realistic?**

The 5 ms target requires that Phase 2 effectively eliminates JAR enumeration from the snapshot
path. After Phase 2, the snapshot check costs: (a) 121 `stat()` calls (one per jar), (b) one
`SnapshotReader.readMapped` call. At full classpath scale, the snapshot file itself may be
large, and `readMapped` cost scales with snapshot size. If the snapshot file is >50 MB, even
sequential reads of that file may take 5-10 ms on warm OS cache. Pre-profiling the snapshot
read path (before Phase 8) via a targeted micro-benchmark of `SnapshotReader.readMapped` on a
realistic snapshot file would de-risk this target before the full Phase 8 run. If it turns out
`readMapped` is the floor, a further optimization (snapshot file compression, or binary search
index to skip unused sections) would be needed in a follow-up plan.

**Channel overhead in Phase 3 may show up as new mutex samples:**

The pipelining in Phase 3 introduces `entryCh` and `resultCh` channel operations, each of
which acquires a brief internal lock. At high throughput (5,949 entries across decoder fibers),
this could show up as new `__psynch_mutexwait` samples that were not present in the baseline.
Phase 8 profiling should check whether channel lock samples appear in the CPU breakdown and
whether they are offset by a reduction in scheduler park/unpark samples (`__psynch_cvwait`).
The net effect should be positive, but if channel contention at `decodeConcurrency = 8` exceeds
the idle-time savings, reducing channel capacity or using a lock-free queue may be warranted.
