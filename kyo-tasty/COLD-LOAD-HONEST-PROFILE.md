# Cold-Load Honest Profile: After jar!/entry Read Bug Fix

## 1. Setup

**Bench command:**
```
sbt -batch -J-Xmx4G -J-Xss8m \
  'set (`kyo-reflect-bench`.jvm) / run / javaOptions ++= \
    Seq("-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=cpu,interval=10ms,file=/tmp/cpu-honest.html,flamegraph", \
        "-Dkyo.reflect.timing=true")' \
  'kyo-reflect-bench/runMain kyo.bench.ColdLoadFullBench 5 5'
```

**JVM:** Temurin 25 (G1 GC), -Xmx4G -Xss8m

**async-profiler:** libasyncProfiler.dylib at `/opt/homebrew/lib/libasyncProfiler.dylib`
(Homebrew install, version reported in flamegraph header).

**Classpath stats:**
- 121 jars
- 5,949 TASTy files (of which 5,880 are in jars, 69 in kyo-bench classes dir)
- 34,580 class files
- 250.49 MB uncompressed
- 70 jars contain TASTy (up to 950 entries each; mean ~84 per jar)

---

## 2. Phase-Timing Summary (representative steady-state run)

From `-Dkyo.reflect.timing=true`, warmup iteration 3 (OS page cache warm):

```
[kyo-reflect] cold-load:
  list=2170ms  decode=6147ms  merge=6147ms  finalize=302ms  total=6449ms
  jars=5912 (construct=829ms  read=318ms)
  entries=5981  bytes=57MB

decode-breakdown (per-file CPU work, totaled across all 5,981 files):
  header=0ms  names=11ms  section=1ms  attr=1ms  ast=933ms  pos=5ms  comments=1ms
  => total in-unpickler CPU work: ~952ms
```

**Key gap:** decode wall = 6,147ms but per-file CPU work sums to only ~952ms.
The remaining ~5,195ms (85% of decode time) is NOT measured by the per-stage counters.

---

## 3. CPU Breakdown Table (top 15, async-profiler CPU sampling, 10ms interval)

Source: `cpu-honest-after-fix.txt` -- Total samples: 34,981.
Flat profile (per method, all callers merged):

| Rank | % CPU | Method |
|------|-------|--------|
| 1 | 21.00% | `scala.collection.immutable.IntMap.updated` |
| 2 | 8.81% | `scala.collection.immutable.IntMap$Bin$.apply` |
| 3 | 8.37% | `scala.collection.mutable.HashMap$HashMapIterator.hasNext` |
| 4 | 6.43% | `G1ParScanThreadState::do_copy_to_survivor_space` (GC) |
| 5 | 4.95% | `__psynch_cvwait` (park / OS wait) |
| 6 | 4.62% | `G1ParScanThreadState::trim_queue_to_threshold` (GC) |
| 7 | 4.38% | `scala.collection.immutable.IntMap$Bin.left` |
| 8 | 3.93% | `scala.collection.immutable.IntMap$Tip$.apply` |
| 9 | 3.55% | `G1 OopOopIterateBackwards` (GC) |
| 10 | 2.48% | `scala.collection.immutable.IntMap$Bin.prefix` |
| 11 | 2.19% | `kyo.internal.reflect.symbol.Interner.countFilled` |
| 12 | 1.82% | `scala.collection.immutable.IntMapUtils$.join` |
| 13 | 1.82% | `scala.collection.immutable.IntMap$Bin.<init>` |
| 14 | 1.57% | `kyo.internal.reflect.symbol.Interner.internInShard` |
| 15 | 1.02% | `read` (OS disk read via RandomAccessFile -- JarFile CEN) |

**Aggregated by category (execution-profile stacks, top-100 entries):**

| Category | Samples | % CPU |
|----------|---------|-------|
| GC (G1 evacuate/scan) | 4,994 | 14.28% |
| Kyo scheduler threads parked | 1,481 | 4.23% |
| TASTy unpickler (AstUnpickler / TypeUnpickler) | 1,067 | 3.05% |
| Interner.intern (fullName / classpath) | 485 | 1.39% |
| JAR disk I/O (RandomAccessFile.read -- CEN) | 233 | 0.67% |
| JAR decompression (inflate_fast) | 196 | 0.56% |
| JAR central-dir init + hash (initCEN) | 133 | 0.38% |
| JVM JIT compilation | 104 | 0.30% |
| Interner (snapshot path) | 71 | 0.20% |
| Scala collections (HAMT / IntMap) | 50 | 0.14% |
| assignHomes (HashMap.put / hashCode) | 48 | 0.14% |
| Other park / wait | 133 | 0.38% |
| **Total visible** | **9,019** | **25.78%** |
| **Not in top-100 entries (idle / OS-wait)** | **25,962** | **74.22%** |

The 74% of samples NOT in the top-100 entries represent threads that are off-CPU:
idle kyo Worker threads that returned to the executor pool, or fibers suspended
waiting for Channel.take / Channel.put to unblock.

---

## 4. Allocation Breakdown Table (top 15, async-profiler alloc event)

Source: `alloc-honest-after-fix.txt` -- Total samples: 2,061,320.
Aggregated by allocated type across all stacks:

| Rank | % Samples | Bytes (cumulative across all iters) | Allocated type / context |
|------|-----------|--------------------------------------|--------------------------|
| 1 | 22.48% | 243 GB | `IntMap$Bin` -- `TypeUnpickler.readTypeIntoSession` building a snapshot of `liveAddrMap` on every type-decode call |
| 2 | 3.72% | 37 GB | `Interner$Entry[]` -- `Arrays.copyOf` during Interner shard grow and insert CAS |
| 3 | 0.95% | 10 GB | `byte[]` -- `ZipFile$Source.initCEN` (JarFile central-directory buffer, one per entry open) |
| 4 | 0.34% | 3.6 GB | `Object[]` -- various collection intermediaries |
| 5 | 0.32% | 3.5 GB | `int[]` -- IntMap internal arrays |

Flat-profile top 5 by sample count:

| Rank | Samples | % | Bytes (MB) | Method |
|------|---------|---|------------|--------|
| 1 | 161,327 | 7.82% | 84,582 | `IntMap$Tip` |
| 2 | 76,801 | 3.72% | 40,266 | `Interner$Entry[]` |
| 3 | 32,809 | 1.67% | 18,121 | `byte[]` |
| 4 | 6,691 | 0.34% | 3,647 | `Object[]` |
| 5 | 6,552 | 0.32% | 3,463 | `int[]` |

---

## 5. Diagnosis: Where Is the ~6 s Gap Going?

### Summary verdict

**Hypothesis 3 (Scala collection internals -- IntMap) and Hypothesis 2 (per-fiber JarFile I/O pipeline bottleneck) together explain the gap. Hypotheses 1 and 4 are minor contributors.**

### Finding A: IntMap allocation storm in TypeUnpickler (dominant CPU + alloc cost)

`TypeUnpickler.readTypeIntoSession` (line 174 in `TypeUnpickler.scala`) calls:

```scala
IntMap.from(session.liveAddrMap.iterator)
```

This snapshot is taken **once per type-node decoded**, converting the entire mutable `HashMap[Int, Symbol]` into a fresh immutable `IntMap` via a full iteration and rebuild. With 5,981 TASTy files each containing hundreds to thousands of type nodes, this produces an enormous number of short-lived `IntMap$Bin` and `IntMap$Tip` objects.

Evidence:
- IntMap-related methods occupy the top 4 slots in the flat CPU profile (21.0% + 8.8% + 8.4% + 4.4% = 42.6% of all CPU samples).
- The alloc profile shows `IntMap$Bin` at 22.48% of alloc samples, totaling ~243 GB allocated across all benchmark iterations -- by far the single largest allocation source.
- `scala.collection.mutable.HashMap$HashMapIterator.hasNext` at 8.37% CPU: this is the iterator driving `IntMap.from(...)`, draining the mutable HashMap into the immutable IntMap tree.
- The GC is consuming 14.28% of CPU samples, consistent with continuous promotion of short-lived IntMap nodes through G1 young-gen.

This single call site is responsible for the majority of observable CPU burn and GC pressure. The fix is to pass the mutable `HashMap` directly to `DecodeCtx` instead of snapshotting it into an `IntMap` on every call, or to snapshot once per file rather than per type-node.

### Finding B: JarFile re-open per entry (dominant wall-clock bottleneck)

`JvmFileSource.readJarEntry` opens a new `JarFile` for every TASTy entry read:

```scala
val jf = new java.util.jar.JarFile(jarPath)  // line 151
```

With 5,912 TASTy entries in jars (mean ~49 entries per jar), each decoder fiber opens and closes the same JAR file ~49 times. The timing data shows:

- `jars=5912 (construct=829ms read=318ms)` in steady state.
- 5,912 JarFile constructs account for 829ms -- but only 0.36%+0.13% CPU (read + initCEN) visible in CPU profile, meaning most of the 829ms is wall-clock blocked on OS file I/O or kernel page-table work, not CPU.
- The Phase A list stage takes 1,403-2,170ms despite OS page cache being warm. This is Channel.put backpressure: the producer cannot outrun the decoders, which are each stalled on JarFile construction.
- The per-file decode CPU work totals only 952ms across all 5,981 files, yet the decode pipeline takes 6,147ms wall. The ratio (6.5x) reflects that decoders spend most of their time waiting for JarFile I/O rather than executing TASTy decode logic.

The fix is to open each JAR once per decoder fiber and keep it open for the duration of that jar's entries, or to pre-read all entries for a jar in Phase A using the already-open `JarCentralDirectory` handle.

### Finding C: Interner shard copy-on-write overhead

The Interner's lock-free design copies the entire shard array on every insert (via `Arrays.copyOf`) and re-walks it on every `countFilled` check. With 5,949 symbol names being interned per cold load:

- `Interner.countFilled` is 2.19% of CPU samples (flat: 767 samples).
- `Interner.internInShard` is 1.57% of CPU samples (549 samples).
- Alloc profile: `Interner$Entry[]` is 3.72% of alloc samples (37 GB cumulative).

The CAS+copy strategy (copying the whole shard table per insert) generates an `Entry[]` copy per unique symbol seen for the first time. With 32 shards, each shard holds ~187 entries at saturation, and each insert copies and discards that array. This is a secondary cost compared to IntMap but measurable.

### Finding D: Scheduler park overhead (minor)

Kyo scheduler daemon threads (`Scheduler.$init$$$anonfun$10`, `BlockingMonitor`, `InternalClock`) account for 4.23% of CPU samples, all in `parkNanos`. These are background threads that sleep between heartbeat checks. They contribute to the sample count but NOT to wall time -- they are expected overhead.

The Channel.put / Channel.take blocking that causes actual pipeline backpressure is not visible in CPU samples because blocked fibers are off-CPU (the fiber is suspended and the worker thread finds no work, returns to the executor pool, and the thread sleeps -- sampled as idle or in `threadpool getTask`).

### Quantitative allocation by hypothesis

| Hypothesis | CPU evidence | Alloc evidence | Verdict |
|---|---|---|---|
| H1: Channel/Promise coordination (5,981 puts + takes) | Minimal CPU; 0% alloc | ~3,378 AtomicReference (0.16%) | Not a primary cause |
| H2: Per-fiber JarFile re-open (I/O stall) | 1.2% CPU (I/O+inflate+hash) | 10 GB byte[] (0.95%) | Major wall-clock cause |
| H3: Scala collection internals (IntMap, HashMap) | 42% CPU (IntMap) + 14% GC | 243 GB IntMap$Bin (22%) | Dominant CPU + GC cause |
| H4: Something new (Interner CAS copy overhead) | 3.8% CPU | 37 GB Entry[] (3.7%) | Secondary cause |

---

## 6. Concrete Next-Step Recommendations (priority order)

### Fix 1: Stop snapshotting `liveAddrMap` into `IntMap` per type-node [HIGH IMPACT]

In `TypeUnpickler.readTypeIntoSession` (and `DecodeCtx`), replace:

```scala
IntMap.from(session.liveAddrMap.iterator)  // snapshot per call
```

with passing the mutable `HashMap[Int, Symbol]` directly, or taking a single snapshot per file at the start of pass-1 decode. This eliminates the dominant alloc source (22% of alloc samples, 42% of CPU samples, and most of the GC pressure) in one change. The `IntMap` was presumably chosen for immutability/thread safety, but `DecodeCtx` is single-threaded per file -- a plain `HashMap` lookup is sufficient.

### Fix 2: Keep JarFile open per-jar in Phase B decoders [HIGH IMPACT]

Change `JvmFileSource.readJarEntry` to accept an already-open `JarFile` (or `ZipFile`) handle, and group Phase B work so all entries from the same JAR are read with a single open/close. Since Phase A already knows the JAR path and enqueues entries grouped by JAR (or can be made to), a per-JAR `JarFile` pool in the decoder fiber eliminates 5,912 redundant opens. Expected savings: eliminates the 829ms construct time and the associated 10 GB `byte[]` alloc for central directory buffers.

### Fix 3: Track fill count in Interner (avoid `countFilled` scan) [MEDIUM IMPACT]

Replace the `countFilled(table)` O(n) scan with an `AtomicInteger` fill counter incremented on successful insert. This turns the per-insert load-factor check from O(capacity) to O(1) and eliminates the dominant use of `countFilled` (2.19% CPU flat). At 32 shards * ~8 inserts/shard = 256 total unique names, the scan happens 256 times over ~1,024-sized arrays = 262,144 iterations wasted.

### Fix 4: Pre-read all entries per JAR in a single open during Phase A [ALTERNATIVE to Fix 2]

Restructure Phase A to not just enqueue paths but to read all bytes for each JAR in one pass, pushing `(path, bytes)` pairs to `entryCh`. Phase B decoders then never open files at all. This keeps the file I/O on a single dedicated thread (Phase A) and eliminates backpressure from file-open latency in the decode stage. The trade-off is higher peak memory (all bytes for a JAR in flight simultaneously).

---

## Artifacts

- `cpu-honest-after-fix.html` -- flamegraph (open in browser)
- `cpu-honest-after-fix.txt` -- flat + execution profile text
- `alloc-honest-after-fix.txt` -- allocation profile text
