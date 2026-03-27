# kyo-bench-runner: Cross-Platform Benchmark Runner Analysis

## Current State

`kyo-bench` is JVM-only via `sbt-jmh` (0.4.8). 56 benchmark files, arena pattern comparing Kyo/ZIO/Cats Effect. JMH features used: `@Benchmark`, `@State`, `@BenchmarkMode(Throughput)`, `@Fork(1)`, `@Param`, `@Setup(Level.Trial)`, `Blackhole`.

## Why kyo-bench-runner

1. **Cross-platform**: JMH is JVM-only. No way to benchmark on JS/Native
2. **Async-native**: JMH blocks on async code. A Kyo runner measures true async throughput with interleaved fibers
3. **Generic**: Callback-based API — any library (ZIO, Cats, Futures, Vert.x) plugs in
4. **Unified results**: Same benchmark, same output format, across JVM/JS/Native

## Research Summary

Studied 9 frameworks: JMH, Criterion.rs, Google Benchmark, BenchmarkDotNet, Divan, Criterion (Haskell), Benchmark.js, pytest-benchmark, hyperfine. Full notes in `benchmark-frameworks-research.md` and `benchmark-safety-research.md`.

### Best ideas to adopt

| Feature | Source | Why |
|---------|--------|-----|
| Callback-based async (`deferred.resolve()`) | Benchmark.js | Library-agnostic, universal |
| `iter_batched` / `with_inputs` (setup outside timing) | Criterion.rs / Divan | Type-safe setup/teardown, structurally correct |
| Bootstrap resampling | Criterion.rs / Criterion Haskell | Doesn't assume normality — benchmark data rarely is |
| Modified Tukey outlier detection | Criterion.rs | Reports outliers without dropping them |
| ReturnValueValidator | BenchmarkDotNet | Validates correctness alongside performance |
| Thread leak detection (snapshot diff) | goleak / randomizedtesting | Detects leaked threads/fibers from benchmark code |
| Automatic warmup convergence | MovingStdDev (Kyo) + Barrett et al. research | Adapts to platform — JVM needs more warmup, JS/Native less |
| AllocProfiler | Divan | Built-in allocation tracking |
| Baseline comparison / regression detection | Criterion.rs / pytest-benchmark | Save and compare across runs |
| Deferred drop (result collection outside timing) | Divan | Prevents destructor/GC from affecting timing |

### What NOT to adopt

| Feature | Source | Why not |
|---------|--------|---------|
| Annotation-based API | JMH / BenchmarkDotNet | Too much ceremony. Function-based is simpler |
| JVM fork isolation | JMH | Complex, JVM-only. Fiber isolation + warmup detection is sufficient for most cases |
| O(n) complexity fitting | Google Benchmark | Nice-to-have but overengineering for v1 |
| HTML reports with charts | Criterion.rs | Console + JSON is enough. Charts can come from external tools |
| BCA bootstrap | Criterion Haskell | Regular percentile bootstrap is simpler and sufficient |

## Design

### Core API — Simple, Divan-inspired

The API should feel like writing a test. Minimal ceremony:

```scala
package bench

import scala.concurrent.duration.*

object Bench:

    // Sync benchmark
    def apply(name: String)(f: => Any): Bench

    // Async benchmark — callback-based, library-agnostic
    def async(name: String)(f: (Any => Unit) => Unit): Bench

    // Suite of benchmarks to compare
    def suite(name: String)(benchmarks: Bench*): Suite

    // Parameterized
    def apply[P](name: String, params: Seq[P])(f: P => Any): Bench
    def async[P](name: String, params: Seq[P])(f: P => (Any => Unit) => Unit): Bench
```

Example usage:

```scala
// Simple
Bench("list-map")(List.range(0, 1000).map(_ + 1))

// Parameterized
Bench("list-map", Seq(100, 1000, 10000)) { size =>
    List.range(0, size).map(_ + 1)
}

// Async — any library
Bench.async("cats-fork") { done =>
    catsBench().unsafeRunAsync(r => done(r.toTry.get))
}

Bench.async("zio-fork") { done =>
    zio.Unsafe.unsafe { u =>
        given zio.Unsafe = u
        zioRuntime.unsafe.fork(zioBench()).unsafe.addObserver(exit => done(exit.getOrThrow()))
    }
}

// Suite for comparison
Bench.suite("scheduling")(
    Bench.async("kyo") { done => /* ... */ },
    Bench.async("cats") { done => /* ... */ },
    Bench.async("zio") { done => /* ... */ }
)
```

### Setup/Teardown — Inspired by Criterion.rs `iter_batched` and Divan `with_inputs`

The key insight from the research: **setup/teardown is really about producing fresh input for each invocation without timing the production**. The best frameworks (Criterion.rs, Divan, Criterion Haskell) model this as "input generation" rather than "lifecycle hooks".

Three patterns cover all cases:

```scala
object Bench:

    // 1. No setup — pure computation, nothing to prepare
    def apply(name: String)(f: => Any): Bench

    // 2. Per-invocation setup — setup returns input, only `f` is timed
    //    Inspired by Divan's `with_inputs` / Criterion.rs `iter_batched`
    //    The `setup` runs before each invocation but is NOT included in timing
    def apply[I](name: String, setup: () => I)(f: I => Any): Bench

    // 3. Shared setup — expensive resource created once, shared across invocations
    //    Inspired by Criterion Haskell's `env` / JMH's @Setup(Level.Trial)
    //    `init` runs once before all iterations, `cleanup` runs once after
    def apply[R](name: String, init: () => R, cleanup: R => Unit)(f: R => Any): Bench

    // All three patterns also for async:
    def async(name: String)(f: (Any => Unit) => Unit): Bench
    def async[I](name: String, setup: () => I)(f: I => (Any => Unit) => Unit): Bench
    def async[R](name: String, init: () => R, cleanup: R => Unit)(f: R => (Any => Unit) => Unit): Bench
```

Examples:

```scala
// Sort benchmark — needs fresh input each time (sort is destructive)
Bench("list-sort", setup = () => scala.util.Random.shuffle(List.range(0, 10000))) { data =>
    data.sorted
}

// Database benchmark — shared connection, per-invocation query
Bench("db-query",
    init    = () => openConnection(),
    cleanup = conn => conn.close()
) { conn =>
    conn.query("SELECT 1")
}

// Channel benchmark — async with shared setup
Bench.async("channel-ping-pong",
    init    = () => Channel.init[Int](1024),
    cleanup = _ => ()
) { channel => done =>
    channel.send(1).flatMap(_ => channel.receive).map(done)
}
```

**Why this is better than JMH's 3-level model:**
- JMH's `Level.Invocation` is documented as "use with extreme caution" because the timing overhead of setup/teardown calls pollutes measurement. By structurally separating setup from the timed function, we avoid this entirely.
- JMH's `Level.Iteration` is a middle ground that doesn't map cleanly to real needs. You either need fresh input per call (pattern 2) or a shared resource (pattern 3).
- No `@State` scope complexity (Thread/Benchmark/Group). The setup function is just a function.

**Why not more levels?** Criterion Haskell has 6, but 3 of those are "with cleanup" variants. Our pattern 3 already has cleanup built in. The remaining distinction (per-batch vs per-run in Haskell) only matters for micro-optimizing setup overhead — the runner can batch internally.

### Safety Features

#### 1. Dead Code Elimination Prevention (platform-specific)

```scala
// Platform-specific Blackhole
object Blackhole:
    def consume(a: Any): Unit // prevent DCE
```

- **JVM**: Volatile write (like JMH's traditional Blackhole). On JDK 17+, use compiler blackholes if available.
- **JS**: Assign to a module-scoped `var` — V8's TurboFan won't eliminate writes to potentially-observable state. Also: detect DCE by comparing against empty-function baseline (bench-node approach).
- **Native (LLVM)**: Inline assembly barrier equivalent to Rust's `black_box` — `asm volatile("" : : "g"(value) : "memory")` via Scala Native's `CFuncPtr` or `@extern`.

The runner auto-wraps benchmark return values through `Blackhole.consume`. Users don't need to think about this.

#### 2. Result Validation (from BenchmarkDotNet)

When benchmarks in a suite have the same type, validate they return the same result. This catches bugs where one implementation silently computes wrong results but appears faster:

```scala
// Runner does this automatically for suites
val results = suite.benchmarks.map(b => b.runOnce())
if results.distinct.size > 1 then
    warn(s"Suite '${suite.name}': benchmarks return different results: $results")
```

This is a pre-flight check before measurement begins.

#### 3. Thread/Fiber Leak Detection (from goleak / randomizedtesting)

Snapshot threads before and after each benchmark. Flag leaks:

```scala
// Before benchmark
val threadsBefore = Thread.getAllStackTraces().keySet()

// Run benchmark

// After benchmark — wait briefly for transient threads
Thread.sleep(100)
val threadsAfter = Thread.getAllStackTraces().keySet()
val leaked = threadsAfter.diff(threadsBefore)
    .filter(!isSystemThread(_)) // filter GC, Finalizer, JMX, etc.

if leaked.nonEmpty then
    warn(s"Benchmark '${bench.name}' leaked ${leaked.size} threads: ${leaked.map(_.getName)}")
```

On JS: detect leaked timers/intervals. On Native: thread snapshot similar to JVM.

The existing `BenchTest` in kyo-bench already does something similar (`detectRuntimeLeak`), so this pattern is proven.

#### 4. Deferred Drop / GC Isolation (from Divan)

Collect benchmark results in a pre-allocated buffer during measurement. Only drop/GC them after timing is done. This prevents destructor/finalizer overhead from affecting measurements:

```scala
// During measurement: store results, don't let GC see them
val results = new Array[Any](batchSize)
var i = 0
while i < batchSize do
    results(i) = bench.run()
    i += 1
// After timing: clear the array (GC can now collect)
java.util.Arrays.fill(results, null)
```

#### 5. Warmup Convergence Detection

Instead of fixed iteration counts (JMH's known weakness — research shows only 37% of JVM benchmarks reliably reach steady state with fixed warmup), detect convergence statistically:

```scala
// Port MovingStdDev to shared (it's pure math, no platform deps)
val window = new MovingStdDev(50)
var converged = false
while !converged do
    val elapsed = timeOneIteration()
    window.observe(elapsed.toNanos)
    if window.idx > window.size then
        val cv = window.dev() / window.avg()
        converged = cv < 0.05 // 5% coefficient of variation
```

With a maximum warmup time as a safety cap. This adapts naturally: JVM needs more iterations (JIT), JS/Native converge faster.

#### 6. Outlier Detection (Modified Tukey, from Criterion.rs)

Report outliers but don't remove them from the data:

```scala
val q1 = percentile(samples, 0.25)
val q3 = percentile(samples, 0.75)
val iqr = q3 - q1
val mildFence = 1.5 * iqr
val severeFence = 3.0 * iqr

val mild = samples.count(s => s < q1 - mildFence || s > q3 + mildFence)
val severe = samples.count(s => s < q1 - severeFence || s > q3 + severeFence)

if mild > 0 then
    warn(s"${mild} mild outliers, ${severe} severe. Consider increasing measurement time.")
```

### Statistical Approach

**Bootstrap resampling** (following Criterion.rs/Haskell) — benchmark data is frequently not normal (skewed by GC pauses, bimodal from JIT transitions):

1. Collect N samples (each sample = mean of a batch of iterations)
2. Generate 10,000 bootstrap resamples
3. Compute confidence interval from bootstrap distribution
4. Report: mean, median, stddev, p50, p90, p95, p99
5. Outlier classification via Modified Tukey

For comparison across runs: t-test on bootstrapped distributions with configurable noise threshold (default 1%).

### Async Throughput Measurement

```
Traditional (JMH):
  Thread: [op1-start...op1-end][op2-start...op2-end][op3-start...]
  One op at a time, blocking between

Async (kyo-bench-runner):
  Fiber pool: [op1-start...][op2-start...][op3-start...]
              [op1-end][op4-start...][op2-end][op5-start...]
  Configurable concurrency, true interleaving
```

The callback-based `(A => Unit) => Unit` is internally lifted to a Kyo fiber:

```scala
private def liftCallback[A](f: (A => Unit) => Unit): A < Async =
    Fiber.initPromise[A].map { promise =>
        f(a => promise.complete(Result.succeed(a)))
        promise.get
    }
```

### Runner Configuration

```scala
case class BenchConfig(
    // Warmup
    maxWarmupDuration: Duration = 10.seconds,  // safety cap
    convergenceThreshold: Double = 0.05,       // CV threshold for warmup detection

    // Measurement
    measurementDuration: Duration = 5.seconds,
    minSamples: Int = 50,

    // Async
    concurrency: Int = Runtime.getRuntime.availableProcessors() * 2,

    // Statistics
    bootstrapResamples: Int = 10_000,
    confidenceLevel: Double = 0.95,
    noiseThreshold: Double = 0.01,  // 1% — changes within this are "no change"

    // Safety
    validateResults: Boolean = true,
    detectThreadLeaks: Boolean = true,
    maxWarmupTime: Duration = 30.seconds  // abort if warmup never converges
)
```

### Result Reporting

```scala
case class BenchResult(
    name: String,
    platform: String,         // "jvm", "js", "native"
    mode: String,             // "thrpt" or "avgt"
    score: Double,
    scoreError: Double,       // confidence interval half-width
    unit: String,             // "ops/s" or "ns/op"
    params: Map[String, String],
    percentiles: Map[String, Double],  // p50, p90, p95, p99
    outliers: OutlierReport,
    warnings: Seq[String]     // thread leaks, result mismatches, etc.
)
```

Console output — clean table like Divan:

```
benchmark              mode   score        error    unit     p50      p99
├─ scheduling
│  ├─ kyo              thrpt  1,234,567    ±2.3%   ops/s    812 ns   1.2 μs
│  ├─ cats             thrpt    456,789    ±3.1%   ops/s    2.1 μs   4.8 μs
│  └─ zio              thrpt    567,890    ±2.8%   ops/s    1.7 μs   3.9 μs
├─ list-sort
│  ├─ size=100         thrpt  5,678,901    ±1.5%   ops/s    ...
│  └─ size=10000       thrpt     12,345    ±2.1%   ops/s    ...
```

Plus JSON output for CI and cross-platform aggregation.

### Baseline Comparison (from Criterion.rs / pytest-benchmark)

Save results to a JSON file. On next run, compare:

```
benchmark           old         new         change
├─ scheduling/kyo   1,200,000   1,234,567   +2.9% (within noise)
├─ scheduling/cats    460,000     456,789   -0.7% (within noise)
├─ scheduling/zio    550,000     567,890   +3.3% (IMPROVED, p=0.02)
```

## Module Structure

```
kyo-bench/
  shared/src/main/scala/kyo/bench/
    Bench.scala          -- Benchmark definition (apply, async, suite)
    Runner.scala         -- Execution engine (warmup, measurement, reporting)
    Stats.scala          -- MovingStdDev, bootstrap, percentiles, outlier detection
    Blackhole.scala      -- DCE prevention (expect, platform-specific)
    Report.scala         -- Console table + JSON output
    Config.scala         -- BenchConfig
  jvm/src/main/scala/kyo/bench/
    Platform.scala       -- Volatile blackhole, ThreadMXBean allocations, thread leak detection
  js/src/main/scala/kyo/bench/
    Platform.scala       -- Module-var blackhole, timer leak detection
  native/src/main/scala/kyo/bench/
    Platform.scala       -- Inline asm blackhole, thread snapshot
```

Package: `kyo.bench` (keeps it in the Kyo namespace since it depends on kyo-core).

Dependencies: only `kyo-core`.

## Cross-Platform Timing Precision

| Platform | Practical Minimum | Notes |
|----------|-------------------|-------|
| JVM (Linux) | ~30 ns | `System.nanoTime()` via vDSO |
| JVM (macOS) | ~1 μs | Varies by chip |
| JVM (Windows) | ~371 μs | Extremely coarse granularity |
| Native (Linux) | ~1-5 ns | `clock_gettime` via vDSO |
| Native (macOS ARM) | ~42 ns | 41.67 ns tick (`mach_absolute_time`) |
| JS (Node.js) | ~1 μs | `process.hrtime.bigint()` |
| JS (Browser) | 5 μs - 1 ms | Clamped for fingerprint protection |

The runner should auto-scale iteration batch size based on platform timer resolution (like Divan's formula and pytest-benchmark's calibration).

## Open Questions

1. **Kyo-specific convenience API**: Should `Bench.kyo` live in kyo-bench or a separate `kyo-bench-kyo` module? Probably just in kyo-bench since it already depends on kyo-core.

2. **Fork isolation on JVM**: Skip for v1. Warmup convergence + fiber isolation covers most cases. Can add `ProcessBuilder`-based fork later.

3. **Allocation profiling**: On JVM, use `ThreadMXBean.getThreadAllocatedBytes`. On other platforms, skip or use coarse heap snapshots. Worth having as an opt-in "diagnoser" a la BenchmarkDotNet, not default.

4. **Baseline storage location**: `./bench-results/` directory? Configurable? Criterion.rs uses `./target/criterion/`.

5. **Discovery**: How are benchmarks discovered and run? Options:
   - Explicit `main` method listing suites (simplest, no magic)
   - sbt plugin that discovers benchmark objects
   - Start with explicit `main`, add discovery later
