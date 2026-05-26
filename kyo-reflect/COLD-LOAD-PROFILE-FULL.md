# Cold-load profile — FULL kyo-bench classpath

Date: 2026-05-26T07:57 UTC+0
Classpath: 121 jars + kyo-bench classes directory (122 roots total)
- Total TASTy files: 5,949
- Total class files: 34,580
- Total bytes (uncompressed): 250.49 MB
- Total jar sizes (on-disk): 100.69 MB
JVM: OpenJDK 25.0.3 (Eclipse Temurin, build 25.0.3+9-LTS, mixed mode, sharing)
Profiler: async-profiler 4.3 (built Jan 13 2026), CPU sampling at 10ms intervals, allocation profiling at TLAB boundary
Harness: `kyo-reflect-bench/jvm/src/main/scala/kyo/bench/ColdLoadFullBench.scala` — `kyo.bench.ColdLoadFullBench`

## What "cold-load" means here

Each `Reflect.Classpath.open(roots)` call enumerates all 122 roots (121 jars + 1 classes
directory), opens each JAR with `JarFile`, iterates its central directory to find `.tasty`
and `.class` entries, then reads and decodes every TASTy file. Files are NOT cold on disk
(macOS requires interactive auth for `sudo purge` so disk-cache clearing was not done). Times
reflect warm-OS-cache, cold-JVM-heap iterations after 5 JVM warmup rounds.

The profiler was attached to the **forked JVM** (not the sbt process) via
`kyo-reflect-bench`.jvm / run / javaOptions. This was required to see kyo-reflect application
allocations — profiling the sbt parent process only showed sbt infrastructure.

## Cold-load timing (warm OS cache)

### W11-full: cold-load (no snapshot)

5 warmup + 10 measurement iterations, uninstrumented baseline:

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

**Median: 55 ms, p95: 68 ms** (N=10 after 5 warmup)

### W11b-full: cold-load + snapshot cache

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

**Median: 57 ms, p95: 76 ms** (N=10 after 5 warmup)

The snapshot path is NOT faster than the cold-load path at this scale. Both paths spend
most of their time in JAR enumeration and decompression, which happens regardless of whether
a snapshot cache hit occurs.

## Comparison to prior partial profile

| Profile | Scope | TASTy files | Class files | Data size | Median | p95 |
|---|---|---|---|---|---|---|
| Partial (COLD-LOAD-PROFILE.md) | kyo-bench classes dir only | 69 | 511 | 2.51 MB (uncompressed) | ~20 ms | ~27 ms |
| Full (this profile) | 121 jars + classes dir | 5,949 | 34,580 | 250.49 MB (uncompressed) | ~55 ms | ~68 ms |
| Ratio | | 86x more TASTy | 68x more class | 100x more data | **2.75x** | **2.5x** |

The ratio is dramatically sub-linear. A 100x increase in file count and data size produces only
a 2.75x increase in wall-clock latency. This confirms that the bottleneck at partial scale
(TASTy type decode, HashMap construction) is not the bottleneck at full scale. At full scale,
the bottleneck is JAR central-directory enumeration, which is flat-cost-per-jar (not
per-file), and the work is highly parallelized by the scheduler.

**Snapshot path comparison:**
- Partial profile: snapshot ~4 ms vs cold ~20 ms — **5x speedup**
- Full profile: snapshot ~57 ms vs cold ~55 ms — **no measurable speedup**

The snapshot path provided a 5x win at the small scale because it skipped TASTy decode, which
was the bottleneck there. At full scale, TASTy decode is NOT the bottleneck — JAR I/O and
decompression dominate — and the snapshot path still needs to enumerate all jars to compute
the digest. So it pays the same JAR overhead as the non-snapshot path.

## CPU breakdown (forked JVM, 10ms sampling, 5+10 iterations)

Total samples: 1,539. The forked JVM has no C2 compiler interference since it runs
post-warmup with most hot paths already JIT-compiled before measurement starts.

| Function / area | % CPU-samples | Notes |
|---|---|---|
| `__psynch_mutexwait` | 29.4% | mutex waits: kyo scheduler + JarFile.entries() synchronized access |
| `__psynch_cvwait` | 8.3% | condition variable waits (kyo fiber coordination) |
| `__open` | 6.4% | file open syscalls — opening 121 jars per iteration |
| `__psynch_mutexdrop` | 3.4% | mutex release |
| `pthread_jit_write_protect_np` | 2.0% | JIT code protection (Apple Silicon) |
| `kyo.scheduler.IOTask.partialLoop$1` | 0.52% | fiber task execution loop |
| `scala.collection.immutable.BitmapIndexedMapNode.getKey` | 0.52% | HashMap lookups (phase C cross-file resolution) |
| `kyo.internal.reflect.symbol.Interner.internInShard` | 0.39% | symbol name interning |
| `kyo.Tag$package$Tag$internal$.checkTypes` | 0.39% | Kyo tag type checks |
| `kyo.kernel.internal.Safepoint.enter` | 0.26% | fiber safepoint overhead |
| `kyo.internal.reflect.tasty.PositionsUnpickler$.readSync` | 0.19% | TASTy positions decode |
| `inflate_fast` | 0.19% | ZIP decompression of TASTy/class data in jars |
| `java.util.zip.ZipFile$Source.checkAndAddEntry` | 0.19% | JAR CEN parsing |
| `scala.runtime.BoxesRunTime.boxToInteger` | 0.32% | Integer boxing (PositionsUnpickler, as in partial profile) |

The dominant CPU time is in mutex waits, which are split between the Kyo fiber scheduler
coordinating Phase B parallel body decode, and the `JarFile` constructor's internal
synchronized access to its ZipFile source. File open syscalls (`__open` at 6.4%) confirm
that each iteration re-opens all 121 JARs.

## Allocation hotspots (top 15, forked JVM, 5+10 iterations)

Total samples: 5,229 (TLAB-crossing allocations).

| Type | % of alloc samples | Notes |
|---|---|---|
| `byte[]` | 29.3% | JAR CEN (`ZipFile$Source.initCEN`), zip inflate buffers, string concat in path building |
| `java.lang.Object[]` | 15.0% | HAMT node arrays in `BitmapIndexedMapNode`, collection builders |
| `java.util.jar.JarFile$JarFileEntry` | 12.3% | Per-entry objects during jar listing — one per TASTy/class entry |
| `int[]` | 8.6% | HAMT bitmap arrays, zip CEN integer buffers |
| `java.lang.String` | 4.1% | Path string building in `JvmFileSource.listJarEntries` (jar path + "!/" + entry name) |
| `scala.Tuple2` | 2.95% | Map entries (phase C cross-file resolution) |
| `long[]` | 2.2% | Position arrays (PositionsUnpickler), zip offsets |
| `kyo.Abort$$anon$2` | 1.36% | Kyo effect continuation closures (per fiber dispatch) |
| `kyo.Abort$$anon$1` | 1.27% | Kyo effect continuation closures |
| `kyo.Fiber$package$Fiber$internal$$anon$59$$anon$61` | 1.30% | Kyo fiber closures |
| `scala.collection.immutable.BitmapIndexedMapNode` | 1.07% | HAMT nodes (phase C cross-file map merging) |
| `kyo.internal.reflect.symbol.Interner$Entry[]` | 0.94% | Interner shard array resize |
| `kyo.Closed` | 0.78% | Scope finalizer state objects |
| `java.lang.Integer` | 0.73% | Boxing in PositionsUnpickler (same site as partial profile) |
| `kyo.internal.reflect.query.ClasspathOrchestrator$$anon$16` | 0.73% | Per-file orchestrator continuations |

### Top call paths (by allocation bytes)

The single dominant allocation path (three variants at 2.6%, 2.5%, 2.4% each) is:

```
java.util.jar.JarFile$JarFileEntry
  <- java.util.jar.JarFile.entryFor
  <- java.util.zip.ZipFile.getZipEntry
  <- java.util.zip.ZipFile$ZipEntryIterator.next
  <- kyo.internal.reflect.query.JvmFileSource$.listJarEntries
  <- ClasspathOrchestrator (Phase A header sweep)
```

This is `JarFile$JarFileEntry` allocation per enumerated entry — one object per `.tasty` or
`.class` entry in each jar, across 121 jars per iteration. This path appears 3 times in the
top-10 stacks (variants from Phase A's two `list(.tasty)` and `list(.class)` calls plus the
`DigestComputer` path in the snapshot variant).

The second dominant path (1.97% + 1.74%):

```
byte[]
  <- java.util.zip.ZipFile$Source.initCEN
  <- java.util.zip.ZipFile$Source.<init>
  <- java.util.jar.JarFile.<init>
  <- kyo.internal.reflect.query.JvmFileSource$.listJarEntries
```

This is the JAR central-directory (CEN) `byte[]` allocated when opening each JarFile. Each
`JarFile` constructor reads the entire CEN into a `byte[]` to build its in-memory entry
index. For 121 jars, this happens 121 times per iteration.

The fifth dominant path (2.20%):

```
byte[]
  <- StringConcatHelper.newArrayWithSuffix
  <- kyo.internal.reflect.query.JvmFileSource$$anon$1$$anon$3.input
  <- kyo.kernel.internal.KyoContinue.<init>
  <- ClasspathOrchestrator.flatMapLoop$7
```

Path strings are being built as `jarPath + "!/" + entryName` for each entry — 5,880 TASTy +
29,929 class = 35,809 entries each allocating a concatenated string.

## Where things differ from the partial profile

**TypeUnpickler.readTypeIntoSession is no longer the bottleneck.** In the partial profile it
accounted for 44% of allocations (HAMT `Object[]` + `int[]` + `Tuple2`). In the full profile
those same types appear but at much lower absolute percentages: `BitmapIndexedMapNode` is
1.07%, `Tuple2` is 2.95% (much of which is probably cross-file resolution, not type decode).
`TypeUnpickler` does not appear in the top call paths at all.

**JAR I/O is now the dominant allocation source.** `JarFile$JarFileEntry` at 12.3% and JAR
CEN `byte[]` at combined ~4% of total samples are new top entries. These did not appear in
the partial profile because the partial profile profiled a directory root (no jars). The
partial profile's `JvmFileSource` allocation (1.9%) was `Files.readAllBytes` for flat `.tasty`
files — now replaced by `JarFile$JarFileEntry` allocation during jar entry enumeration.

**New: path string building per-entry.** Each of the 35,809 entries allocates a
`jarPath + "!/" + entryName` string (2.20% of alloc samples). This was invisible at the small
scale but is measurable here because it runs 35,809 times per iteration vs 69 times at small
scale.

**Integer boxing in PositionsUnpickler persists.** `java.lang.Integer` is at 0.73% (forked
JVM) — same site as the partial profile's 2.0%. Proportionally smaller because other
allocations dwarf it at scale.

**Phase C cross-file resolution is now more prominent.** `BitmapIndexedMapNode` and `Tuple2`
at ~4% combined reflect the larger cross-file type resolution graph (5,949 files vs 69).
Still not the dominant cost.

**The snapshot digest computation adds JAR overhead without benefit.** `DigestComputer` at
call stack position appears in the top allocation stacks for the snapshot path, performing the
same JAR enumeration as the non-snapshot path. The snapshot is read after the digest, but
by then the JAR overhead is already paid.

## Findings

**JAR I/O is the dominant bottleneck at full classpath scale.** Every cold-load iteration
opens 121 JarFiles, reads each JAR's central-directory (CEN) into memory, iterates 35,809
entries to find `.tasty` and `.class` files, and builds a `jarPath + "!/" + entryName` string
for each entry. This costs ~40-50 ms even with warm OS page cache and JIT-compiled hotpaths.
The TASTy decode work (TypeUnpickler, AstUnpickler, PositionsUnpickler) that was the
bottleneck at small scale is barely visible in the profile: `PositionsUnpickler.readSync` at
0.19% CPU and `TypeUnpickler` not appearing in the top call paths.

**The snapshot cache provides no speedup at full classpath scale.** The snapshot path costs
the same 50-57 ms as the no-snapshot path. This is because `openCachedImpl` calls
`SnapshotDigest.compute(roots, source)` before reading the snapshot, and `DigestComputer`
enumerates all jar entries to compute the digest — exactly the same per-jar work as Phase A
of the full open. The 5x snapshot speedup seen in the partial profile was entirely due to
skipping TASTy decode; at full scale TASTy decode is not the bottleneck, so skipping it
provides no measurable benefit.

**The scaling from partial to full is dramatically sub-linear.** 100x more data (2.51 MB to
250 MB) produces only 2.75x more latency (20 ms to 55 ms). Two mechanisms explain this: (1)
parallelism — Phase A and B run concurrently across available cores, so the per-file decode
work scales well; (2) the JAR overhead is per-jar, not per-file — 121 JARs (one open+close
per jar) vs reading 69 individual `.tasty` files. The partial profile's 20 ms baseline was
mostly decode; the full profile's 55 ms is mostly JAR bookkeeping. A workload with 10x more
jars would cost approximately 10x the JAR overhead.

**mmap of JAR bytes would not help.** JAR entries are individually deflate-compressed;
mmap'ing the raw jar file bytes does not skip decompression. The `ZipFile$Source.initCEN`
already reads the central directory; the remaining `inflate_fast` CPU samples (0.19%) are
for actual deflate of individual entries. The dominant cost is not decompression but the
per-entry object allocation (`JarFile$JarFileEntry`, path strings) and the JAR opening
overhead (CEN `byte[]` allocation + `__open` syscalls). mmap would not reduce object
allocation or syscall count.

**Optimization priority changes from the partial profile.** At small scale the priority was:
(1) replace per-decode HashMap in TypeUnpickler, (2) fix Integer boxing in PositionsUnpickler,
(3) pre-size Interner shards. At full classpath scale the priorities shift: (1) cache open
JarFile handles across iterations or use a ZipFileSystem that keeps the CEN in memory between
calls — the 121 open+close cycles per iteration dominate; (2) avoid per-entry path string
allocation in `JvmFileSource.listJarEntries` — 35,809 string concatenations can be replaced
by storing entries as raw `(jarPath, entryName)` pairs or using an interned path scheme; (3)
avoid `JarFile$JarFileEntry` allocation by iterating the ZipFile source's raw CEN bytes
directly instead of calling `entries().asIterator()`. The HashMap optimization in
TypeUnpickler (from the partial profile) still applies but is lower priority at full scale.

**The snapshot design needs a different trigger mechanism at full scale.** Because
`SnapshotDigest.compute` enumerates all jars to compute the digest, the snapshot path pays
the same JAR overhead as the cold path. A better design for full-classpath use would cache
the digest itself (e.g., based on jar last-modified times via `stat()` rather than by
re-enumerating entries) so the snapshot check costs O(jars) in `stat` calls rather than
O(entries) in CEN reads. This would let the snapshot path skip both TASTy decode AND jar
entry enumeration on a cache hit, reducing latency to near-zero for an unchanged classpath.

## Profile artifacts

- CPU flamegraph (forked JVM, 5+10 iters): `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-reflect/full-cold-load-cpu-flame.html`
- Allocation flamegraph (sbt process, 5+10 iters): `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-reflect/full-cold-load-alloc.html`
- Allocation text (forked JVM, 5+10 iters): `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-reflect/full-cold-load-alloc-text.txt`
- CPU text (forked JVM, 5+10 iters): `/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-reflect/full-cold-load-cpu-text.txt`

All raw profile files also at `/tmp/kyo-reflect-full-profile/`.

## Harness

`kyo-reflect-bench/jvm/src/main/scala/kyo/bench/ColdLoadFullBench.scala` — object `ColdLoadFullBench`.

Classpath file: `/tmp/kyo-bench-cp.txt` (produced by `sbt 'show kyo-bench/fullClasspath' 2>&1 | grep -oE '/[^ ]*\.jar' | sort -u > /tmp/kyo-bench-cp.txt`).

Run uninstrumented baseline:
```
sbt -J-Xmx4G -J-Xss8m 'kyo-reflect-bench/runMain kyo.bench.ColdLoadFullBench 5 10'
```

Run with CPU profiling on forked JVM:
```
sbt -J-Xmx4G -J-Xss8m \
  'set (`kyo-reflect-bench`.jvm) / run / javaOptions ++= Seq("-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=cpu,file=/tmp/full-cpu.html,flamegraph")' \
  'kyo-reflect-bench/runMain kyo.bench.ColdLoadFullBench 5 10'
```

Run with allocation profiling on forked JVM:
```
sbt -J-Xmx4G -J-Xss8m \
  'set (`kyo-reflect-bench`.jvm) / run / javaOptions ++= Seq("-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=alloc,file=/tmp/full-alloc.txt,output=text")' \
  'kyo-reflect-bench/runMain kyo.bench.ColdLoadFullBench 5 10'
```
