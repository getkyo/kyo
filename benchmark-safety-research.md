# Benchmark Safety, Correctness, and Pitfalls Research

Comprehensive research on safety features, correctness guarantees, and common pitfalls across benchmarking frameworks and platforms.

---

## 1. Dead Code Elimination (DCE) Prevention Across Platforms

### JVM: JMH Blackhole

JMH provides two approaches to prevent dead code elimination:

**Traditional (Pure Java) Blackhole:**
- Reads aliases from volatile fields, forcing the compiler to assume values are unstable
- Blackhole methods are prohibited from being inlined
- The `consume()` call provides a side effect preventing JIT elimination
- Overhead is substantial: a simple `x + y` benchmark produces ~25 extra instructions beyond the 2 for computation, with 3+ store operations and ~3.2 ns/op total
- Source: [Shipilev - Compiler Blackholes](https://shipilev.net/jvm/anatomy-quarks/27-compiler-blackholes/)

**Compiler Blackholes (OpenJDK 17+):**
- Instruct the compiler to carry all arguments through optimization phases, then drop them at code generation
- Zero runtime overhead -- no stores, no method calls
- Reduces instructions by ~70% (8 vs 27 instructions) and execution time from ~3.2 ns/op to <1 ns/op
- Still experimental: opt-in via `-Djmh.blackhole.mode=COMPILER`
- Risk: hardware could theoretically skip non-observable instructions (not observed in practice)

### Rust: std::hint::black_box

- Currently implemented as empty inline assembly for LLVM
- Compiler treats it as having side-effects, so it must compute its input
- **Critical limitation**: `black_box` is "best-effort" only and the extent varies by platform and backend
- **Major pitfall**: Applying `black_box` only to outputs does NOT prevent pre-computation of results
  - Example: GF(2^8) multiplication with output-only `black_box` reduced to `mov byte ptr [rsp], 19` (a constant)
  - Must use `black_box` on BOTH inputs AND outputs: `black_box(black_box(a) + black_box(b))`
- Source: [Guillaume Endignoux - Why my Rust benchmarks were wrong](https://gendignoux.com/blog/2022/01/31/rust-benchmarks.html)

### C++: Google Benchmark DoNotOptimize

- For GCC-compatible compilers: `asm volatile("" : : "g"(value) : "memory")`
- Empty inline assembly with memory constraints forces value availability
- `"memory"` clobber prevents caching/reordering memory operations
- Complemented by `ClobberMemory()`: `asm volatile("" : : : "memory")`
- **Important**: `DoNotOptimize(<expr>)` does NOT prevent optimizations on `<expr>` itself -- `<expr>` may be removed if the result is already known
- Source: [Google Benchmark Assembly Tests](https://google.github.io/benchmark/AssemblyTests.html)

### JavaScript (V8 / TurboFan)

V8's TurboFan uses SSA "sea of nodes" representation for aggressive dead code elimination, constant folding, and loop unrolling. JS benchmarking has unique challenges:

**bench-node approach (Node.js):**
- Default: `%NeverOptimizeFunction(DoNotOptimize)` prevents JIT optimization entirely
- Alternative: `%OptimizeFunctionOnNextCall()` forces optimization to simulate real-world post-warmup
- **DCE detection**: Measures baseline (empty function), compares benchmark timing; warns if benchmark runs less than 10x slower than baseline (configurable threshold)
- Source: [bench-node on GitHub](https://github.com/RafaelGSS/bench-node)

**Browser constraints:**
- `performance.now()` clamped to 100us in non-isolated contexts (Chrome 91+)
- Cross-origin isolated contexts: relaxed to 5us
- Firefox: `privacy.reduceTimerPrecision` defaults to 1ms; `privacy.resistFingerprinting` reduces to 100ms
- Safari: 1ms precision (same as Date.now())
- Source: [MDN - High precision timing](https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/High_precision_timing)

### LLVM (Scala Native / Rust / C++)

LLVM provides two relevant intrinsics:

- **`llvm.blackbox`** (RFC): Accepts and returns a value, opaque to all optimizations. Prevents constant propagation, dead code elimination, etc. Dropped at code generation.
- **`llvm.sideeffect`**: No-op that appears to have obscure side effects, preventing loop elimination
- Source: [LLVM RFC - llvm.blackbox intrinsic](https://lists.llvm.org/pipermail/llvm-dev/2015-November/091968.html)

Scala Native compiles through LLVM and inherits these capabilities but does not currently expose a dedicated benchmark barrier API. The same inline assembly tricks used by Google Benchmark and Rust's `black_box` work at the LLVM level.

### Scala.js

Scala.js applies type-aware inter-method global dead code elimination during optimization. For benchmarking, results must be consumed to prevent elimination. Fields that are never read are now DCE'd (semantics-preserving: side effects in assignments still execute). There is no built-in `black_box` equivalent -- benchmark results must be explicitly used or passed through external-facing APIs.

---

## 2. Thread/Resource Leak Detection in Benchmarks

### Go: goleak (Uber)

- Snapshot comparison: captures active goroutines before and after test execution
- Two modes: per-test (`defer goleak.VerifyNone(t)`) and package-level (`TestMain`)
- Filters system goroutines and allows custom allowlists
- Limitation: cannot distinguish leaked goroutines from unfinished parallel tests (`t.Parallel()`)
- Source: [goleak on GitHub](https://github.com/uber-go/goleak)

### JVM: randomizedtesting ThreadLeakControl (Elasticsearch/Lucene)

The most sophisticated JVM thread leak detector, used by Elasticsearch and Lucene:

- **Before/after snapshot**: Captures threads via `getThreads()` before execution, compares after
- **Multi-stage filtering**: Built-in filters (daemon threads, AWT, profiler), user-defined `ThreadFilter`, state validation
- **Lingering period**: Configurable wait time for threads to naturally terminate before declaring leak
- **Leak handling**: Stack trace capture only on confirmed leaks (expensive op), configurable actions (WARN, INTERRUPT)
- **Zombie detection**: Multiple interrupt attempts with decreasing wait intervals
- Operates at both test-level and suite-level scopes
- Source: [randomizedtesting ThreadLeakControl.java](https://github.com/randomizedtesting/randomizedtesting/blob/master/randomized-runner/src/main/java/com/carrotsearch/randomizedtesting/ThreadLeakControl.java)

### Kafka: JUnit 5 Extension

- Custom JUnit 5 extension (`DetectThreadLeak`) verifies no lingering threads after each test
- Thread group enumeration before/after with diff analysis
- Source: [Kafka JIRA - KAFKA-16072](https://www.mail-archive.com/jira@kafka.apache.org/msg167902.html)

### General JVM Pattern

The core algorithm for any JVM leak detector:
1. `Thread.getAllStackTraces()` or `ThreadGroup.enumerate()` to snapshot active threads
2. Filter known system threads (GC, Finalizer, JMX, etc.)
3. Run test
4. Re-snapshot and diff
5. Wait with timeout for transient threads to finish
6. Report any remaining delta as leaks

---

## 3. Automated Warmup Detection

### JMH Approach (Static Configuration)

JMH uses **fixed iteration counts** for warmup, not statistical detection:
- User configures `@Warmup(iterations = N, time = T)`
- No automatic steady-state detection
- This is a known weakness -- research shows developers frequently misconfigure warmup

### Barrett et al. Changepoint Analysis (Research)

The most rigorous automated approach, using the PELT (Pruned Exact Linear Time) algorithm:

**Methodology:**
- Analyze 2000+ in-process iterations across 30 process runs per VM-benchmark pair
- Detect shifts where statistical properties (mean AND variance) change between segments
- Segments with means within 0.001 seconds are considered equivalent
- Final 500 iterations compared to determine if steady state was reached

**Classification of warmup behavior:**
- **Flat**: All segments equivalent (ideal)
- **Warmup**: Classic pattern -- steady state faster than initial segments
- **Slowdown**: Steady state present but earlier segments were faster (bad)
- **No steady state**: Last 500 iterations never stabilize (bad)

**Shocking findings:**
- Only **37%** of VM-benchmark pairs achieved flat or warmup patterns
- ~50% of cases showed inconsistent behavior -- same benchmark on same VM on same machine exhibited multiple performance characteristics across runs
- 10.9% of process executions failed to reach steady state entirely (follow-up study of 586 benchmarks)
- VMs typically reach steady state either within 10 iterations or after hundreds -- nothing in between

- Source: [Barrett et al. - Virtual Machine Warmup Blows Hot and Cold](https://blog.acolyer.org/2017/11/07/virtual-machine-warmup-blows-hot-and-cold/)
- Source: [Tratt - More Evidence for Problems in VM Warmup](https://tratt.net/laurie/blog/2022/more_evidence_for_problems_in_vm_warmup.html)

### Criterion.rs Approach (Linear Regression)

Criterion.rs uses a simpler but effective approach:

**Warmup phase:**
- Execute routine 1x, 2x, 4x, ... (doubling) until cumulative time exceeds `warm_up_time`
- Records iteration count and elapsed time for scaling factor

**Measurement phase:**
- Iteration counts scale progressively: `iterations = [d, 2d, 3d, ... Nd]`
- Factor `d` derived from warmup estimates ensures samples meet target measurement duration
- **Linear regression** fitted to samples (iterations vs time) to estimate per-iteration time

**Statistical analysis:**
- Bootstrap resampling: generates many bootstrap samples, fits regression line to each
- Produces distributions for slope, mean, median, standard deviation
- Outlier detection via modified Tukey's Method (IQR-based, with 1.5x and 3x fences)
- Outliers are reported but NOT removed from analysis

- Source: [Criterion.rs - Analysis Process](https://bheisler.github.io/criterion.rs/book/analysis.html)

---

## 4. Environment Interference Detection

### Hyperfine

- **Statistical outlier detection**: Built-in detection of anomalous runs from other processes/caching
- **First-run detection**: Identifies whether the first run is an outlier (cold cache)
- **Proposed heuristics** (not yet implemented):
  - Bimodal/multimodal distribution detection for background process interference
  - Linear model fitting to runtimes; check if slope is close to zero (thermal throttling)
- Warmup support: `-w/--warmup` flag for N preparatory executions
- Source: [hyperfine issue #481 - Advanced interference detection](https://github.com/sharkdp/hyperfine/issues/481)

### BenchmarkDotNet

**Validators (pre-flight checks):**
- `JitOptimizationsValidator`: Checks if referenced assemblies are non-optimized (debug builds)
- `BaselineValidator`: Ensures only one benchmark per class has `Baseline = true`
- `ExecutionValidator`: Pre-runs each benchmark once to verify it can execute
- `ReturnValueValidator`: Checks non-void benchmarks return equal values

**Environmental controls:**
- **Power plan management**: Forces Windows to High Performance plan during benchmarks, restores afterward
- **VM detection**: Warns when running on HyperV, VirtualBox, or VMware
- **CPU info detection**: Parses CPU frequency, model for environment reporting

- Source: [BenchmarkDotNet - Validators](https://benchmarkdotnet.org/articles/configs/validators.html)
- Source: [BenchmarkDotNet - Power Plans](https://benchmarkdotnet.org/articles/configs/powerplans.html)

### General Techniques

**Thermal throttling detection:**
- Fit linear model to runtime series; non-zero slope indicates thermal drift
- Detectable via CPU frequency monitoring (`/proc/cpuinfo`, Intel RAPL)

**CPU frequency scaling:**
- Pin CPU frequency or use `performance` governor on Linux
- BenchmarkDotNet forces High Performance power plan on Windows

---

## 5. GC and Allocation Measurement

### JMH -prof gc

**Two components:**

1. **Allocation measurement** (`gc.alloc`):
   - Uses `ThreadMXBean.getThreadAllocatedBytes()` for per-thread allocation tracking
   - Snapshots thread allocation data before/after benchmark iterations
   - Reports bytes allocated per operation
   - Caveat: threads that are created and die between snapshots may have allocations missed
   - Current thread excluded (assumed to run JMH infrastructure only)

2. **GC churn measurement**:
   - Monitors GC events to track collection frequency and pause times
   - Reports Gen0/Gen1/Gen2 collection rates

**Other JMH profilers:**
- `-prof perfasm`: Assembly-level profiling via Linux perf
- `-prof perfnorm`: Hardware counter normalization (cache misses, branch misses)
- `-prof stack`: Simple sampling profiler for hot method detection
- Source: [JMH profiler documentation](https://gist.github.com/markrmiller/a04f5c734fad879f688123bc312c21af)

### BenchmarkDotNet MemoryDiagnoser

- Uses `GC.GetAllocatedBytesForCurrentThread()` on .NET Core
- Falls back to `AppDomain.MonitoringTotalAllocatedMemorySize` on .NET Framework
- **Accuracy: 99.5%** (2-byte variance due to CLR allocation quantum rounding)
- Reports:
  - `Allocated`: Managed memory per invocation (excludes stackalloc/native heap)
  - `Gen X`: GC frequency per 1,000 operations
- Separate diagnostic run to avoid affecting timing measurements
- Cross-platform: .NET Framework, .NET Core, Mono (Mono lacks allocation API -- Allocated column unavailable)
- Source: [Adam Sitnik - The new MemoryDiagnoser](https://adamsitnik.com/the-new-Memory-Diagnoser/)

### Cross-Platform Allocation Measurement

| Platform | API | Accuracy | Notes |
|----------|-----|----------|-------|
| JVM | ThreadMXBean.getThreadAllocatedBytes | Good | Misses short-lived threads |
| .NET Core | GC.GetAllocatedBytesForCurrentThread | 99.5% | Per-thread, managed only |
| .NET Framework | AppDomain.MonitoringTotalAllocatedMemorySize | Lower | Process-wide |
| Mono | N/A | N/A | No API available |
| Native (Rust/C) | Custom allocator wrapping | Exact | Requires replacing global allocator |
| JS/Node.js | process.memoryUsage() | Coarse | Heap snapshots, no per-operation |

### Recommended Metrics (6 Key Memory Metrics for JVM)

1. Object graph traversal (EHCache sizeof / JAMM)
2. Used memory after forced GC (requires settling -- multiple GC cycles)
3. Used heap memory (JVM Attach API histograms)
4. Maximum used memory via GC notification (peak detection)
5. Process VmRSS and VmHWM (Linux OS-level)
6. Allocation rate per operation (normalize absolute figures)

Source: [6 Memory Metrics for Java Benchmarks](https://cruftex.net/2017/03/28/The-6-Memory-Metrics-You-Should-Track-in-Your-Java-Benchmarks.html)

---

## 6. Async Benchmarking Pitfalls

### Coordinated Omission (Gil Tene)

The most insidious async benchmarking bug. Occurs when the measuring system inadvertently coordinates with the system under test, avoiding measurement of outliers.

**The problem:**
- Most tools use closed-loop model (new request only after previous completes)
- Real systems are open-loop (requests arrive independently of processing speed)
- When a request takes longer, subsequent requests queue but their queuing time is not measured

**Distortion magnitude:**
- System with true 10ms P99 may show 665ms P99 when correctly measured -- **66x distortion**
- Without correction, tail latencies are systematically underreported

**Correction (wrk2 approach):**
- Measure latency from intended firing time, not actual transmission time
- `Corrected Latency = (now - intended_firing_time) + measured_service_time`
- Use HdrHistogram for accurate recording across full range
- Source: [ScyllaDB - On Coordinated Omission](https://www.scylladb.com/2021/04/22/on-coordinated-omission/)

### BenchmarkDotNet Async Limitations

- Single-threaded execution: async/await tends to be slower when CPU-bound
- Multi-threading not well-supported for async benchmarks
- Measures artificial scenarios that do not reflect real concurrent workloads
- Source: [Silvenga - Benchmarking .NET Async Throughput](https://silvenga.com/posts/benchmarking-async-throughput/)

### Thread Pool Saturation

- Thread scheduling and synchronization overhead: 15-30% of CPU time
- Properly sized pools reduce context switching by 45-65%, increase throughput by 30-80%
- Benchmarks must match production concurrency patterns

### Latency vs Throughput

- Flat response time with rising throughput = healthy scaling
- Rising latency with flat/declining throughput = saturation/bottleneck
- At saturation: throughput peaks while latency rises faster
- At overload: throughput declines AND latency spikes

### Async Overhead Reality (matklad)

- Pure async/await overhead: ~4x vs hand-coded state machines
- One syscall reduces this to ~10% overhead
- IO-related context switches: identical time between threads and stackless coroutines
- Pinned thread-to-thread channel sends matched async performance
- Only unpinned threads showed order-of-magnitude slower switching
- Source: [matklad - Async Benchmarks Index](https://matklad.github.io/2021/03/22/async-benchmarks-index.html)

---

## 7. Cross-Platform Timing Precision

### JVM: System.nanoTime()

| Platform | Latency | Granularity | Notes |
|----------|---------|-------------|-------|
| Linux | ~25-26 ns | ~26-29 ns | Uses clock_gettime(CLOCK_MONOTONIC) via vDSO |
| Windows | ~14 ns (single) | **~371 us** | Extremely coarse granularity |
| macOS | varies | ~1 us | Monotonicity via CAS under load |

- **Minimum measurable duration: ~30 ns** (anything shorter is indistinguishable from timer overhead)
- **Scalability bottleneck**: Calling faster than 32us across all threads on some machines
- 26 consecutive calls on Windows may return identical values
- Source: [Shipilev - Nanotrusting the Nanotime](https://shipilev.net/blog/2014/nanotrusting-nanotime/)

### JavaScript: performance.now()

| Context | Chrome | Firefox | Safari |
|---------|--------|---------|--------|
| Cross-origin isolated | 5 us | 5 us* | 1 ms |
| Non-isolated | 100 us | 1 ms | 1 ms |
| resistFingerprinting | N/A | 100 ms | N/A |

*Firefox with `privacy.reduceTimerPrecision` defaults to 1ms.

**Node.js:**
- `performance.now()`: microsecond precision (uses hrtime internally)
- `process.hrtime.bigint()`: nanosecond precision, immune to clock drift
- Preferred for benchmarking: `process.hrtime.bigint()`

Source: [MDN - performance.now()](https://developer.mozilla.org/en-US/docs/Web/API/Performance/now)

### Native: clock_gettime

| Platform | API | Actual Precision | Notes |
|----------|-----|-----------------|-------|
| Linux | clock_gettime(CLOCK_MONOTONIC) | ~1 ns | vDSO avoids syscall overhead |
| macOS Intel | mach_absolute_time | 1 ns tick | True nanosecond |
| macOS Apple Silicon | mach_absolute_time | **41.67 ns tick** | 125/3 ratio, NOT 1:1 |
| macOS (clock_gettime) | clock_gettime(CLOCK_MONOTONIC) | ~1 us | Last 3 digits always zero |

**Critical macOS finding**: `clock_gettime` on macOS reports nanosecond capability but often delivers only microsecond precision (last 3 digits zero). Apple's recommended API is `clock_gettime_nsec_np(CLOCK_UPTIME_RAW)`.

Use `clock_getres()` to programmatically determine actual resolution on any system.

### Summary: Minimum Measurable Durations

| Platform | Practical Minimum | Timer Overhead |
|----------|-------------------|---------------|
| JVM (Linux) | ~30 ns | ~25 ns per call |
| JVM (Windows) | ~371 us | ~14 ns per call but coarse granularity |
| JVM (macOS) | ~1 us | varies |
| Native (Linux) | ~1-5 ns | <5 ns via vDSO |
| Native (macOS ARM) | ~42 ns | 41.67 ns tick |
| JS (Node.js) | ~1 us | process.hrtime |
| JS (Chrome isolated) | ~5 us | clamped |
| JS (Chrome non-isolated) | ~100 us | clamped |
| JS (Firefox) | ~1 ms | privacy default |
| JS (Safari) | ~1 ms | always |

---

## 8. Statistical Methods

### Why Bootstrapping (Criterion.rs) Instead of Normal Distribution

- Benchmark data is frequently NOT normally distributed
- Common patterns: skewed right (GC pauses), bimodal (JIT transitions), heavy-tailed (IO)
- Bootstrapping is "asymptotically more accurate than standard intervals obtained using sample variance and assumptions of normality"
- Nonparametric bootstrap "reasonably reconstructs sampling distributions for both source distributions" (unimodal and bimodal)
- If bimodal: parametric bootstrap generates inaccurate distribution because of erroneous model assumption
- Source: [Johnston - Bootstrap is superior for non-normal data](https://nph.onlinelibrary.wiley.com/doi/10.1111/nph.17159)

### Criterion.rs Statistical Pipeline

1. **Warmup**: Exponential doubling until target time exceeded
2. **Measurement**: Progressive iteration counts `[d, 2d, 3d, ... Nd]`
3. **Linear regression**: Fit line to (iterations, elapsed_time) pairs
4. **Bootstrapping**: Generate many bootstrap samples, fit regression to each, build distributions for slope/mean/median/MAD
5. **Outlier classification**: Modified Tukey's Method (IQR-based, 1.5x mild / 3x severe)
6. **Comparison**: T-test on bootstrapped samples from current vs previous run
7. **Noise threshold**: Configurable (e.g., +/-1%) to filter trivial changes

### Bimodal Detection

**Hartigan's Dip Test:**
- Measures distance between CDF and nearest unimodal CDF
- The dip statistic = maximum distance at any point
- Consistent for bimodality detection; accuracy increases with sample size

**Bimodality Coefficient (BC):**
- Simpler but less reliable alone
- Best combined with Hartigan's dip statistic

### Changepoint Detection (Barrett et al.)

- PELT algorithm: O(n) complexity for detecting statistical shifts
- Detects changes in both mean AND variance
- Applied to benchmark time series to identify warmup/steady-state transitions
- Segments with means within 0.001s threshold considered equivalent
- Can classify warmup behavior: flat, warmup, slowdown, no-steady-state

### Outlier Detection (BenchmarkDotNet / Akinshin)

**Carling's Modification of Tukey's Fences:**
- Dynamic k parameter: `k = (17.63n - 23.64) / (7.74n - 3.71)`
- Converges to ~2.28 for large n (vs fixed 1.5 or 3.0)
- With k=1.5: nearly 100% of large samples detect outliers (too sensitive)
- With k=3.0: too many false negatives
- Carling's: ~8% of samples detect outliers, regardless of sample size (balanced)
- Source: [Akinshin - Carling's Modification](https://aakinshin.net/posts/carling-outlier-detector/)

---

## 9. Five Most Common JMH Anti-Patterns (Academic Study)

From "What's Wrong with My Benchmark Results?" (Costa et al., IEEE TSE 2019), studying 123 open source Java projects:

1. **RETU** - Not using returned computation: Dead code elimination removes the measured work
2. **LOOP** - Accumulation inside loops: JVM applies loop optimizations not present in production
3. **FINAL** - Final primitives as input: Constant folding replaces variables with literals
4. **INVO** - Fixture methods per invocation: JMH overhead inflates measured times
5. **FORK** - Zero fork configuration: Profile-guided optimization differs without process isolation

**28% of studied projects** contained at least one bad practice. The researchers developed SpotJMHBugs, a static analysis tool for Maven/Gradle/Ant to detect these patterns.

Source: [Costa et al. - What's Wrong with My Benchmark Results?](https://ieeexplore.ieee.org/document/8747433/)

---

## 10. Microbenchmark vs Production Reality (Abseil / Google)

Key pitfalls from Google's internal experience:

1. **Data residency**: Benchmarks keep working sets in cache; production has large footprints
2. **Instruction cache footprint**: Benchmark code is cache-resident; production has large icache footprint (glibc memcmp at ~6KB caused real production regression despite benchmark improvements)
3. **Arithmetic vs load tradeoffs**: Lookup arrays stay cached in benchmarks but get evicted in production
4. **Code alignment sensitivity**: Small layout changes produce 20% performance swings
5. **Unrepresentative data**: String lengths, request types, code paths differ from production
6. **Benchmarking wrong code**: RNG overhead can exceed measured work
7. **Steady-state vs dynamic**: Production variability absent in simplified benchmarks

**Recommendation**: Hierarchical validation -- microbenchmark -> single-task loadtest -> cluster loadtest -> production profiling.

Source: [Abseil - Beware microbenchmarks bearing gifts](https://abseil.io/fast/39)

---

## Key Takeaways for Cross-Platform Benchmark Framework Design

1. **DCE prevention must be platform-specific**: JVM needs Blackhole/compiler intrinsics, LLVM needs inline assembly barriers, JS needs V8 intrinsics or DCE detection heuristics
2. **Timer precision varies wildly**: From ~1ns (Linux native) to 1ms (Safari), with macOS Apple Silicon at 41.67ns ticks
3. **Warmup is an unsolved problem**: Only 37% of JVM benchmarks reliably reach steady state; fixed iteration counts are insufficient
4. **Statistical analysis should not assume normality**: Bootstrap resampling is strictly better for benchmark data
5. **Thread leak detection requires snapshot diff**: Before/after enumeration with filtering and lingering timeouts
6. **Async benchmarks need open-loop models**: Closed-loop testing hides tail latency by 10-66x (coordinated omission)
7. **Environment detection is table stakes**: Power plan, VM detection, frequency scaling, thermal trend analysis
8. **Allocation measurement is platform-dependent**: ThreadMXBean (JVM), GC.GetAllocatedBytesForCurrentThread (.NET), custom allocators (native), coarse heap snapshots (JS)
