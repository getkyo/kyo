# Benchmark Framework Research

Comprehensive analysis of 9 benchmark frameworks across languages, covering API design, setup/teardown ergonomics, statistical methods, safety features, and unique innovations.

---

## 1. JMH (Java Microbenchmark Harness)

**Language:** Java / JVM
**Repository:** https://github.com/openjdk/jmh

### User API for Defining Benchmarks

Annotation-driven. Methods annotated with `@Benchmark` in `@State`-annotated classes:

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class MyBenchmark {

    @Benchmark
    @Fork(value = 2)
    @Warmup(iterations = 5, time = 1)
    @Measurement(iterations = 10, time = 1)
    public String concatenation() {
        String result = "";
        for (int i = 0; i < 1000; i++) {
            result += i;
        }
        return result;
    }
}
```

**Benchmark modes** (`@BenchmarkMode`):
- `Mode.Throughput` (default) -- ops/time
- `Mode.AverageTime` -- time/op
- `Mode.SampleTime` -- percentile distribution (p50, p90, p99, min, max)
- `Mode.SingleShotTime` -- single invocation (cold start)
- `Mode.All` -- all modes at once

### Setup/Teardown Approach and Granularity

Three-level lifecycle with `@Setup` / `@TearDown`:

| Level | When Executed | Use Case |
|-------|--------------|----------|
| `Level.Trial` | Once per fork (before/after all iterations) | Heavy resource allocation (DB connections, file loading) |
| `Level.Iteration` | Before/after each measurement iteration | Reset mutable state between iterations |
| `Level.Invocation` | Before/after every single benchmark call | **Use with extreme caution** -- timing overhead can dominate measurement for fast benchmarks |

```java
@State(Scope.Thread)
public class MyState {
    List<Integer> data;

    @Setup(Level.Iteration)
    public void setup() {
        data = generateRandomList(10000);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // cleanup resources
    }
}
```

**State scopes** (`@State`):
- `Scope.Thread` -- each benchmark thread gets its own instance (no contention)
- `Scope.Benchmark` -- single instance shared across all threads (tests contention)
- `Scope.Group` -- shared within a `@Group` of threads

### Async Benchmarks

JMH does not have native async/await support. Async code must be benchmarked by blocking on the future within the benchmark method. The fork-per-trial isolation model means each benchmark runs in a separate JVM process anyway.

### Statistical Approach

- **Warmup:** Configurable warmup iterations (`@Warmup(iterations=N, time=T)`); warmup forks also supported
- **Fork isolation:** Each trial runs in a **separate JVM fork** -- this is JMH's signature feature; eliminates profile pollution, JIT compilation artifacts, and class-loading effects
- **Iteration model:** Each measurement iteration runs the benchmark method many times; JMH automatically calibrates invocation count
- **Statistics:** Reports mean, error, and confidence intervals; supports percentile histograms in `SampleTime` mode
- **No built-in outlier detection** -- relies on fork isolation to produce clean data

### Safety Features

- **Blackhole:** `Blackhole.consume(value)` prevents dead code elimination (DCE) for computed results that would otherwise be unused. Alternatively, return the value from the `@Benchmark` method -- JMH auto-consumes it.
- **Constant folding prevention:** Store benchmark inputs in `@State` fields (not as local constants) so the JIT cannot fold them at compile time
- **@CompilerControl:** Fine-grained JIT control:
  - `@CompilerControl(Mode.DONT_INLINE)` -- prevent inlining
  - `@CompilerControl(Mode.INLINE)` -- force inlining
  - `@CompilerControl(Mode.EXCLUDE)` -- prevent JIT compilation entirely
- **Fork isolation:** Prevents cross-benchmark JIT pollution

### Parameterized Benchmarks

```java
@State(Scope.Thread)
public class ParamBenchmark {
    @Param({"10", "100", "1000"})
    int size;

    @Benchmark
    public List<Integer> createList() {
        return IntStream.range(0, size).boxed().collect(Collectors.toList());
    }
}
```

Command-line override: `-p size=10,50,100`

### Output Formats

- Text (console, default)
- CSV (`-rf csv`)
- JSON (`-rf json`)
- SCSV (semicolon-separated)

### Unique/Innovative Features

- **Fork isolation** is unique among all frameworks -- each trial runs in a fresh JVM, eliminating JIT profile pollution
- **Asymmetric benchmarks** via `@Group` / `@GroupThreads`: different threads run different methods, modeling producer/consumer patterns:
  ```java
  @Group("rw") @GroupThreads(3) @Benchmark
  public void reader(SharedState s) { s.read(); }

  @Group("rw") @GroupThreads(1) @Benchmark
  public void writer(SharedState s) { s.write(); }
  ```
- **SampleTime mode** provides full latency histograms with percentiles
- **SingleShotTime** for cold-start / startup benchmarks
- **Annotation processor** generates harness code at compile time -- no reflection at runtime

---

## 2. Criterion.rs (Rust)

**Language:** Rust
**Repository:** https://github.com/bheisler/criterion.rs
**Docs:** https://bheisler.github.io/criterion.rs/book/

### User API for Defining Benchmarks

Function-based with macros for registration:

```rust
use criterion::{black_box, criterion_group, criterion_main, Criterion};

fn fibonacci(n: u64) -> u64 {
    match n {
        0 => 1, 1 => 1,
        n => fibonacci(n-1) + fibonacci(n-2),
    }
}

fn criterion_benchmark(c: &mut Criterion) {
    c.bench_function("fib 20", |b| b.iter(|| fibonacci(black_box(20))));
}

criterion_group!(benches, criterion_benchmark);
criterion_main!(benches);
```

### Setup/Teardown Approach and Granularity

Six distinct iteration methods on `Bencher`, each with different setup/drop semantics:

| Method | Setup | Drop | Memory | Use Case |
|--------|-------|------|--------|----------|
| `iter` | None | Timed | O(1) | Simple pure functions |
| `iter_custom` | Manual | Manual | Manual | Multi-threaded, custom timing |
| `iter_with_large_drop` | None | **Deferred** (collected then dropped after timing) | O(iters * sizeof(O)) | Expensive destructors |
| `iter_batched` | Per-batch setup fn | Timed | O(batch) | Sorting algorithms (consumes input) |
| `iter_batched_ref` | Per-batch setup fn | Deferred | O(batch) | Mutating benchmarks (takes &mut) |
| `to_async(runtime).iter(...)` | Per-batch | Timed | Varies | Async functions |

**`iter_batched` example** (setup per invocation, drop excluded):
```rust
b.iter_batched(
    || data.clone(),                    // setup: called before each invocation
    |mut data| data.sort(),             // routine: what's timed
    BatchSize::SmallInput               // batching strategy
)
```

**`BatchSize` controls:**
- `SmallInput` -- many per batch (low overhead)
- `LargeInput` -- fewer per batch (less memory)
- `NumBatches(n)` -- exact batch count
- `PerIteration` -- one setup per invocation (most precise, highest overhead)

### Async Benchmarks

Native support via `to_async()`:
```rust
b.to_async(FuturesExecutor).iter(|| async_function());
// or
b.to_async(tokio::runtime::Runtime::new().unwrap())
 .iter(|| do_something());
```

Supported runtimes (via feature flags):
- `async_tokio` -- `tokio::runtime::Runtime`
- `async_std` -- `AsyncStdExecutor`
- `async_smol` -- `SmolExecutor`
- `async_futures` -- `FuturesExecutor`
- Custom via `AsyncExecutor` trait

**Caveat:** "Async functions naturally result in more measurement overhead than synchronous functions."

### Statistical Approach

The most statistically rigorous of the Rust frameworks:

- **Warmup:** Exponential probing (1, 2, 4, 8... iterations) until `warm_up_time` (default 3s) is exceeded
- **Sampling:** Linear sampling -- `iterations = [d, 2d, 3d, ..., Nd]` where d is derived from warmup
- **Linear regression:** Slope of iterations-vs-time line gives time-per-iteration estimate
- **Bootstrap resampling:** 100,000 bootstrap resamples (configurable via `nresamples`) to compute confidence intervals
- **Outlier classification:** Modified Tukey's method:
  - Mild outliers: outside 25th percentile +/- 1.5 * IQR
  - Severe outliers: outside 25th percentile +/- 3.0 * IQR
  - **Outliers are NOT dropped** -- they remain in analysis
- **Change detection:** T-test comparing current vs. previous run's bootstrapped distributions
- **Noise threshold:** Default 1% -- changes within this range are reported as "no change"
- **Confidence level:** Default 95%
- **Significance level:** Default 0.05

### Safety Features

- **`black_box(value)`** -- prevents compiler from constant-folding or eliminating the value
- **`iter_batched` / `iter_batched_ref`** -- structurally prevents timing setup/teardown
- **Automatic regression detection** -- compares against saved baseline

### Parameterized Benchmarks

```rust
fn bench_group(c: &mut Criterion) {
    let mut group = c.benchmark_group("my-group");
    for size in [1024, 2048, 4096, 8192].iter() {
        group.throughput(Throughput::Bytes(*size as u64));
        group.bench_with_input(
            BenchmarkId::new("sort", size),
            size,
            |b, &size| b.iter(|| sort_vec(size)),
        );
    }
    group.finish();
}
```

### Configuration

```rust
criterion_group!{
    name = benches;
    config = Criterion::default()
        .significance_level(0.1)
        .sample_size(500)
        .warm_up_time(Duration::from_secs(5))
        .measurement_time(Duration::from_secs(10))
        .noise_threshold(0.05);
    targets = bench
}
```

**Sampling modes:**
- `SamplingMode::Auto` (default) -- heuristic selection
- `SamplingMode::Linear` -- for fast benchmarks
- `SamplingMode::Flat` -- for long-running benchmarks (fixed iteration count per sample)

### Output Formats

- Console text with color (default)
- HTML reports with interactive plots (via gnuplot or plotters)
- Machine-readable JSON (criterion.rs directory structure)
- Automatic comparison against previous runs

### Unique/Innovative Features

- **Automatic regression detection** with saved baselines
- **HTML reports** with violin plots, PDF overlays, and iteration-time scatter plots
- **`iter_with_large_drop`** -- unique approach to decouple destructor timing
- **Throughput reporting** -- automatically computes bytes/sec or elements/sec
- **Asymptotic complexity** tracking across input sizes via plot configuration

---

## 3. Google Benchmark (C++)

**Language:** C++
**Repository:** https://github.com/google/benchmark
**Docs:** https://google.github.io/benchmark/user_guide.html

### User API for Defining Benchmarks

Macro-based registration with a `State` iteration object:

```cpp
static void BM_memcpy(benchmark::State& state) {
    char* src = new char[state.range(0)];
    char* dst = new char[state.range(0)];
    memset(src, 'x', state.range(0));
    for (auto _ : state)
        memcpy(dst, src, state.range(0));
    state.SetBytesProcessed(int64_t(state.iterations()) * int64_t(state.range(0)));
    delete[] src;
    delete[] dst;
}
BENCHMARK(BM_memcpy)->Arg(8)->Arg(64)->Arg(512)->Arg(4<<10);
```

### Setup/Teardown Approach and Granularity

**In-function setup** (code before/after the `for (auto _ : state)` loop):
- Code before the loop = per-benchmark setup
- Code after the loop = per-benchmark teardown

**Pause/Resume timing** (per-invocation setup):
```cpp
for (auto _ : state) {
    state.PauseTiming();
    auto data = ConstructRandomSet(state.range(0));  // not timed
    state.ResumeTiming();
    for (int j = 0; j < state.range(1); ++j)
        data.insert(RandomNumber());                  // timed
}
```
**Warning:** `PauseTiming`/`ResumeTiming` have significant overhead (system calls) -- not suitable for very fast inner loops.

**Fixture-based setup:**
```cpp
class MyFixture : public benchmark::Fixture {
public:
    void SetUp(::benchmark::State& state) { /* per-benchmark */ }
    void TearDown(::benchmark::State& state) { /* per-benchmark */ }
};

BENCHMARK_F(MyFixture, TestName)(benchmark::State& st) {
    for (auto _ : st) { /* benchmark code */ }
}
```

**Global Setup/Teardown callbacks:**
```cpp
BENCHMARK(BM_func)->Setup(DoSetup)->Teardown(DoTeardown);
```
Called once per benchmark family (before first / after last run of a parameterized set).

### Async Benchmarks

No native async support. Manual timing mode can be used:
```cpp
static void BM_ManualTiming(benchmark::State& state) {
    for (auto _ : state) {
        auto start = std::chrono::high_resolution_clock::now();
        // async work, GPU kernel, etc.
        auto end = std::chrono::high_resolution_clock::now();
        state.SetIterationTime(
            std::chrono::duration_cast<std::chrono::duration<double>>(end - start).count());
    }
}
BENCHMARK(BM_ManualTiming)->UseManualTime();
```

### Statistical Approach

- **Automatic iteration calibration:** Framework determines iteration count dynamically to reach stable measurements
- **Repetitions:** `--benchmark_repetitions=N` runs entire benchmark N times; reports mean, median, stddev
- **Custom statistics:**
  ```cpp
  BENCHMARK(BM_func)->Repetitions(3)
      ->ComputeStatistics("max", [](const std::vector<double>& v) {
          return *std::max_element(v.begin(), v.end());
      });
  ```
- **Warmup:** `--benchmark_min_warmup_time` for pre-measurement warmup
- **CPU vs wall time:** Can measure CPU time or real time (`UseRealTime()`)

### Safety Features

- **`DoNotOptimize(expr)`** -- forces compiler to materialize expression result in memory/register; acts as read/write barrier
- **`ClobberMemory()`** -- forces pending memory writes to complete (compiler barrier)
- **`state.SkipWithError(msg)`** -- gracefully skip benchmarks on error conditions
- **Custom `MemoryManager`** -- pluggable memory tracking

### Parameterized Benchmarks

Rich parameterization API:
```cpp
// Sparse geometric range
BENCHMARK(BM_func)->Range(8, 8<<10);  // 8, 64, 512, 4096, 8192
BENCHMARK(BM_func)->RangeMultiplier(2)->Range(8, 8<<10);

// Dense linear range
BENCHMARK(BM_func)->DenseRange(0, 1024, 128);

// Multi-dimensional
BENCHMARK(BM_func)->Ranges({{1<<10, 8<<10}, {128, 512}});

// Cartesian product
BENCHMARK(BM_func)->ArgsProduct({
    benchmark::CreateRange(8, 128, 2),
    benchmark::CreateDenseRange(1, 4, 1)
});

// Named captures (type-erased args)
BENCHMARK_CAPTURE(BM_func, variant_name, 42, std::string("abc"));

// Custom argument generator
BENCHMARK(BM_func)->Apply(CustomArguments);
```

### Output Formats

- Console (colored tabular, default)
- JSON (`--benchmark_format=json`)
- CSV (`--benchmark_format=csv`)
- File output: `--benchmark_out=file --benchmark_out_format=json`

### Unique/Innovative Features

- **Asymptotic complexity analysis:**
  ```cpp
  BENCHMARK(BM_func)->Range(1<<10, 1<<18)->Complexity(benchmark::oN);
  // or auto-detect:
  BENCHMARK(BM_func)->Range(1<<10, 1<<18)->Complexity();
  ```
  Fits O(1), O(n), O(n log n), O(n^2), etc. and reports RMS error
- **Custom thread runners** (OpenMP, custom thread pools)
- **Multithreaded benchmarks:**
  ```cpp
  BENCHMARK(BM_func)->Threads(2);
  BENCHMARK(BM_func)->ThreadRange(1, 8);  // powers of 2
  ```
- **Custom counters** with rate/avg/inverse display modes
- **Manual timing** for GPU and external-process benchmarks
- **ASLR mitigation:** `MaybeReenterWithoutASLR()` for consistent memory layout
- **Dry run mode:** `--benchmark_dry_run` for single-iteration validation

---

## 4. BenchmarkDotNet (.NET)

**Language:** C# / .NET
**Repository:** https://github.com/dotnet/BenchmarkDotNet
**Docs:** https://benchmarkdotnet.org/

### User API for Defining Benchmarks

Attribute-driven on classes:

```csharp
[MemoryDiagnoser]
[SimpleJob(RuntimeMoniker.Net80)]
public class MyBenchmarks
{
    [Params(100, 200)]
    public int N { get; set; }

    private int[] data;

    [GlobalSetup]
    public void Setup() => data = Enumerable.Range(0, N).ToArray();

    [Benchmark(Baseline = true)]
    public int LinqSum() => data.Sum();

    [Benchmark]
    public int ForLoopSum()
    {
        int sum = 0;
        for (int i = 0; i < data.Length; i++) sum += data[i];
        return sum;
    }
}
```

### Setup/Teardown Approach and Granularity

| Attribute | Granularity | Notes |
|-----------|-------------|-------|
| `[GlobalSetup]` | Once per benchmark method (before all iterations) | Safe for resource allocation |
| `[GlobalCleanup]` | Once per benchmark method (after all iterations) | Resource disposal |
| `[IterationSetup]` | Before each iteration (not between operations within an iteration) | "Not recommended for microbenchmarks" -- can spoil results |
| `[IterationCleanup]` | After each iteration | Can spoil MemoryDiagnoser if allocating |

**Key distinction:** An "iteration" contains many "operations." `[IterationSetup]` runs before the iteration, not before each operation. There is **no per-operation setup** equivalent (unlike JMH's `Level.Invocation`).

### Async Benchmarks

Native support for `async Task` and `async ValueTask` benchmark methods:
```csharp
[Benchmark]
public async Task<int> AsyncMethod() => await SomeAsyncWork();
```

Validators have been updated to properly `await` async benchmarks, compare `Task<T>` values (not `Task` objects), and work correctly with `InProcessNoEmitToolchain`.

### Statistical Approach

Multi-stage execution pipeline:

1. **Pilot stage** -- iteratively doubles operation count to find stable measurement window
2. **Overhead evaluation** -- runs empty method with same signature, measures harness overhead
3. **Warmup stage** -- JIT stabilization iterations
4. **Actual workload** -- collects measurements with outlier filtering; continues until heuristics are satisfied
5. **Result calculation** -- `ActualWorkload - MedianOverhead`

Additional features:
- **Process isolation** -- each runtime/config generates a separate project built in Release mode and runs in its own process
- **Loop unrolling** -- manual unrolling for measurement accuracy
- **Multiple runtime comparison** -- test same code on .NET 6, 7, 8 simultaneously

### Safety Features

**Validators** (pluggable correctness checks):
- `BaselineValidator` (mandatory) -- ensures at most 1 baseline per class
- `JitOptimizationsValidator` -- warns if referenced assemblies lack optimizations
- `ExecutionValidator` -- dry-runs each benchmark once to verify it can execute
- `ReturnValueValidator` -- **verifies all benchmark variants return identical values** (correctness check!)

**Additional safety:**
- Must build in Release mode (warns otherwise)
- Dead code elimination: return values from benchmarks are consumed by the harness
- Process isolation prevents state leakage between benchmarks

### Parameterized Benchmarks

Three parameter attributes plus two argument attributes:

```csharp
// Inline values
[Params(100, 200, 300)]
public int Size { get; set; }

// Dynamic source
[ParamsSource(nameof(Sizes))]
public int Size { get; set; }
public IEnumerable<int> Sizes => new[] { 100, 200, 300 };

// All enum/bool values automatically
[ParamsAllValues]
public SortAlgorithm Algorithm { get; set; }

// Per-method arguments
[Benchmark]
[Arguments(100, 10)]
[Arguments(200, 20)]
public void Run(int a, int b) { }

// Dynamic argument source
[Benchmark]
[ArgumentsSource(nameof(Data))]
public void Run(int a, int b) { }
```

All parameters/arguments combine **multiplicatively** for full coverage.

### Output Formats

Built-in exporters:
- **Markdown** (default, GitHub-friendly tables)
- **HTML**
- **CSV** (with `ISummaryStyle` configuration)
- **JSON** (Brief, Full, BriefCompressed, FullCompressed, Custom)
- **XML** (same variants as JSON)
- **AsciiDoc**
- **RPlot** (R script generation for visualization)
- **Plain text**

### Unique/Innovative Features

- **ReturnValueValidator** -- validates correctness alongside performance (unique among all frameworks)
- **MemoryDiagnoser** -- GC collection counts + allocated bytes per operation (99.5% accurate)
- **ThreadingDiagnoser** -- lock contention + thread pool statistics
- **InliningDiagnoser** -- JIT inlining event monitoring
- **Multi-runtime comparison** -- single benchmark class, multiple .NET versions
- **Process isolation** like JMH fork, but generates actual separate projects
- **Baseline ratio** -- `[Benchmark(Baseline = true)]` shows relative performance of other methods
- **Disassembly diagnoser** -- shows generated assembly code

---

## 5. Divan (Rust)

**Language:** Rust
**Repository:** https://github.com/nvzqz/divan
**Docs:** https://docs.rs/divan/latest/divan/

### User API for Defining Benchmarks

Attribute macro on functions -- the simplest API of any Rust benchmark framework:

```rust
fn main() {
    divan::main();
}

#[divan::bench]
fn simple() -> Vec<i32> {
    (0..100).collect()
}

#[divan::bench(args = [1, 2, 4, 8, 16, 32])]
fn fibonacci(n: u64) -> u64 {
    if n <= 1 { 1 } else { fibonacci(n - 2) + fibonacci(n - 1) }
}
```

**Attribute options:**
- `args = [...]` -- parameterize over values
- `consts = [...]` -- parameterize over const generics
- `types = [T1, T2, ...]` -- parameterize over types
- `sample_count = N` -- number of samples
- `sample_size = N` -- iterations per sample
- `max_time = N` -- duration limit (seconds)
- `threads = [0, 1, 2, 4]` -- thread counts (0 = available parallelism)

### Setup/Teardown Approach and Granularity

Builder-pattern on `Bencher`:

```rust
#[divan::bench]
fn sort_vec(bencher: divan::Bencher) {
    bencher
        .with_inputs(|| vec![5, 3, 1, 4, 2])   // per-invocation setup (NOT timed)
        .bench_values(|mut v| v.sort())          // timed (consumes input)
}
```

**Bencher methods:**
| Method | Input | Threading | Use Case |
|--------|-------|-----------|----------|
| `bench(fn)` | None | Parallel (`Fn + Sync`) | Simple functions |
| `bench_local(fn)` | None | Single thread (`FnMut`) | Non-Send types |
| `bench_values(fn)` | Owned `I` | Parallel | Consuming input |
| `bench_local_values(fn)` | Owned `I` | Single thread | Non-Send consuming |
| `bench_refs(fn)` | `&mut I` | Parallel | Mutating input |
| `bench_local_refs(fn)` | `&mut I` | Single thread | Non-Send mutating |

**Key design:** `Bencher` is consumed by-value -- Rust's type system prevents reuse/misuse.

**Deferred drop:** Return values are buffered in pre-allocated `MaybeUninit` slots, so destructors run outside the timing window.

### Async Benchmarks

Not yet supported (as of 0.1.21). The framework is synchronous-only.

### Statistical Approach

- **Automatic sample size scaling:** Uses formula `v(t) ~ 2^(100 * tau_precision / t)` -- fast operations get exponentially more iterations to overcome timer precision. Inspired by "Robust Benchmarking in Noisy Environments" paper.
- **Reports:** fastest, slowest, median, mean per benchmark
- **Timer options:** `Instant` (default) or CPU timestamp counter via `DIVAN_TIMER=tsc` environment variable (x86/AArch64)
- **No bootstrap resampling or confidence intervals** -- simpler statistical model than Criterion.rs

### Safety Features

- **`black_box(value)`** -- standard compiler barrier
- **`black_box_drop(value)`** -- barrier + drop in one call
- **Deferred drop** -- return values stored in `MaybeUninit` slots, dropped outside timing
- **`AllocProfiler`** -- global allocator wrapper tracking alloc/dealloc counts and bytes:
  ```rust
  #[global_allocator]
  static ALLOC: divan::AllocProfiler = divan::AllocProfiler::system();
  ```
- **Bencher consumed by-value** -- type system prevents API misuse

### Parameterized Benchmarks

Three dimensions of parameterization:

```rust
// By value
#[divan::bench(args = [100, 1000, 10000])]
fn bench_size(n: usize) { /* ... */ }

// By const generic
#[divan::bench(consts = [16, 32, 64, 128])]
fn bench_buffer<const N: usize>() { let _buf = [0u8; N]; }

// By type
#[divan::bench(types = [Vec<u8>, String, VecDeque<u8>])]
fn bench_collection<T: FromIterator<u8>>() -> T {
    (0..100).collect()
}
```

### Output Format

Hierarchical tree matching module structure:
```
benchmark_name        fastest  | slowest  | median   | mean     | samples | iters
|- subgroup
|  |- variant_1       0.5 ns   | 1.2 ns   | 0.8 ns   | 0.9 ns   | 100     | 204800
|  +- variant_2       2.3 us   | 2.8 us   | 2.4 us   | 2.5 us   | 100     | 6400
```

When `AllocProfiler` is active, additional rows show alloc/dealloc counts and bytes.

### Unique/Innovative Features

- **Linker-based registration** -- `#[divan::bench]` uses distributed slices (like `#[test]`), no manual registration
- **Type-parameterized benchmarks** -- unique ability to compare across types generically
- **Const-generic parameterization** -- leverage Rust's const generics
- **`AllocProfiler`** -- built-in allocation tracking (no external tool needed)
- **Thread contention testing** via `threads = [0, 1, 2, 4]`
- **Module-level grouping** via `#[divan::bench_group]`
- **Counter-driven throughput:** `BytesCount`, `CharsCount`, `ItemsCount` with SI/binary formatting
- **Simplest API** of any framework -- closest to `#[test]` ergonomics

---

## 6. Criterion (Haskell)

**Language:** Haskell
**Repository:** https://github.com/haskell/criterion
**Docs:** https://hackage.haskell.org/package/criterion

### User API for Defining Benchmarks

Pure-functional composition of benchmark specifications:

```haskell
import Criterion.Main

main :: IO ()
main = defaultMain [
    bgroup "fib" [
        bench "1"  $ whnf fib 1,
        bench "5"  $ whnf fib 5,
        bench "10" $ nf   fib 10,
        bench "20" $ nf   fib 20
    ]
  ]
```

**Evaluation strategies** -- a unique concept:
- `nf :: NFData b => (a -> b) -> a -> Benchmarkable` -- evaluate to **normal form** (fully evaluate all nested structures)
- `whnf :: (a -> b) -> a -> Benchmarkable` -- evaluate to **weak head normal form** (just the outermost constructor)
- `nfIO :: NFData a => IO a -> Benchmarkable` -- IO action, result to normal form
- `whnfIO :: IO a -> Benchmarkable` -- IO action, result to WHNF
- `nfAppIO :: NFData b => (a -> IO b) -> a -> Benchmarkable` -- apply argument to IO function, NF
- `whnfAppIO :: (a -> IO b) -> a -> Benchmarkable` -- apply argument to IO function, WHNF

The distinction between `nf` and `whnf` is critical in Haskell due to lazy evaluation -- choosing wrong can make benchmarks meaningless.

### Setup/Teardown Approach and Granularity

The richest environment model of any framework, with six levels:

| Function | Scope | Cleanup | Use Case |
|----------|-------|---------|----------|
| `env` | Shared across all benchmarks | No | Lazy, memory-efficient data generation |
| `envWithCleanup` | Shared | Yes (exception-safe) | Resources requiring disposal |
| `perBatchEnv` | Fresh per batch of runs | No | IO operations that mutate state (e.g., writing to Chan) |
| `perBatchEnvWithCleanup` | Fresh per batch | Yes (exception-safe) | Mutable resources per batch |
| `perRunEnv` | Fresh per individual run | No | Mutable operations like sorting |
| `perRunEnvWithCleanup` | Fresh per run | Yes (exception-safe) | Mutable resources per run |

```haskell
-- Shared environment (lazy, created once)
env setupDatabase $ \~db ->
    bgroup "queries" [
        bench "simple" $ whnfIO (query db "SELECT 1"),
        bench "complex" $ whnfIO (query db complexQuery)
    ]

-- Per-run environment (fresh each time)
bench "sort" $ perRunEnv
    (return $ reverse [1..10000])
    (\xs -> return $ sort xs)

-- Per-batch with cleanup
bench "file-ops" $ perBatchEnvWithCleanup
    (\batchSize -> openTempFile "/tmp" "bench")  -- setup
    (\batchSize (path, h) -> hClose h >> removeFile path)  -- cleanup
    (\(_, h) -> hPutStr h "data")  -- benchmark
```

**Key design:** `env` uses lazy pattern matching (`\~(small, big) -> ...`) so environments are only created when the associated benchmarks actually run.

**NFData constraint:** All environment functions require `NFData` instances to ensure data is fully evaluated before timing begins.

### Async Benchmarks

Haskell's IO monad naturally handles effects. `nfIO` and `whnfIO` benchmark IO actions directly. Async operations using `async` library can be benchmarked by waiting on the result in the IO action.

### Statistical Approach

The **pioneer of statistically rigorous benchmarking** -- Criterion Haskell introduced the approach later adopted by Criterion.rs:

- **Bootstrap resampling** -- generates large number of bootstrap samples from measured data
- **Bias-corrected accelerated (BCA) bootstrap** -- adjusts for bias and skewness
- **Confidence intervals** -- computed from bootstrap distribution
- **Outlier detection** with `OutlierEffect` and `OutlierVariance` metrics -- quantifies how much outliers affect the estimate
- **Configurable resamples** and confidence level via command-line or Config
- **Measures down to picosecond resolution** (per documentation)

### Safety Features

- **NFData enforcement** -- type system ensures values are fully evaluated (prevents benchmarking lazy thunks)
- **Evaluation strategy selection** (`nf` vs `whnf`) makes the programmer explicitly choose evaluation depth
- **`nfAppIO` / `whnfAppIO`** -- reconstruct the IO value on each iteration, preventing the optimizer from caching results
- **Exception-safe cleanup** in all `*WithCleanup` variants

### Parameterized Benchmarks

Via `bgroup` and list comprehension:
```haskell
defaultMain [
    bgroup "sizes" [
        bench (show n) $ nf myFunction n
        | n <- [100, 1000, 10000]
    ]
  ]
```

No dedicated parameterization API -- uses Haskell's natural list/comprehension expressiveness.

### Output Formats

- **Interactive HTML reports** with charts (default when `--output report.html`)
- **JSON** (`--json report.json`)
- **CSV** (`--csv report.csv`)
- **Console text** (default)

### Unique/Innovative Features

- **Evaluation depth control** (`nf` vs `whnf`) -- unique to Haskell, addresses lazy evaluation
- **Six-level environment model** -- finest-grained setup control of any framework
- **NFData type-safety** -- compiler enforces that benchmarked values can be fully evaluated
- **Pioneered bootstrap statistical analysis** for benchmarks -- Criterion.rs and others followed this lead
- **BCA bootstrap** -- more sophisticated than simple percentile bootstrap
- **Picosecond resolution** claims

---

## 7. Benchmark.js (JavaScript)

**Language:** JavaScript
**Repository:** https://github.com/bestiejs/benchmark.js
**Docs:** https://benchmarkjs.com/

### User API for Defining Benchmarks

Object-oriented with event system:

```javascript
var suite = new Benchmark.Suite;

suite
  .add('RegExp#test', function() {
    /o/.test('Hello World!');
  })
  .add('String#indexOf', function() {
    'Hello World!'.indexOf('o') > -1;
  })
  .on('cycle', function(event) {
    console.log(String(event.target));
  })
  .on('complete', function() {
    console.log('Fastest is ' + this.filter('fastest').map('name'));
  })
  .run({ 'async': true });
```

### Setup/Teardown Approach and Granularity

Setup and teardown are **compiled into the test** and execute immediately before/after the test loop:

```javascript
var bench = new Benchmark({
  'setup': function() {
    var c = this.count;
    var element = document.getElementById('container');
  },
  'fn': function() {
    element.removeChild(element.lastChild);
  },
  'teardown': function() {
    // restore DOM state
  }
});
```

**Important:** Setup/teardown are compiled into the same function scope as the benchmark, so variables declared in setup are accessible in the benchmark function. This is a string-compilation approach, not just callback invocation.

There is no per-invocation vs per-iteration distinction -- setup runs before each timed cycle.

### Async Benchmarks

Via `defer: true` and `deferred.resolve()`:

```javascript
var bench = new Benchmark('async test', {
  'defer': true,
  'fn': function(deferred) {
    setTimeout(function() {
      // async work done
      deferred.resolve();
    }, 100);
  }
});
```

The `async: true` option on `suite.run()` allows the UI to update between cycles (does not make benchmarks themselves async -- that is `defer`).

### Statistical Approach

- **Adaptive iteration:** Starts with `initCount` operations, adjusts to fit within `minTime` (reduces margin of error to 1%)
- **Sample collection:** Collects at least `minSamples` (default 5) timed cycles
- **Statistics computed:**
  - `stats.mean` -- arithmetic mean (seconds)
  - `stats.deviation` -- standard deviation
  - `stats.variance` -- sample variance
  - `stats.moe` -- margin of error
  - `stats.rme` -- relative margin of error (%)
  - `stats.sem` -- standard error of the mean
  - `stats.sample` -- array of all sampled periods
- **`hz` property** -- operations per second (the primary metric)
- **No confidence intervals or bootstrap resampling**

### Safety Features

- No DCE prevention (JavaScript JITs are less aggressive about dead code)
- No `black_box` equivalent
- Return values are not consumed by the harness
- **Manual discipline required** to prevent JIT optimizations

### Parameterized Benchmarks

No built-in parameterization. Manual loop:
```javascript
[10, 100, 1000].forEach(function(n) {
  suite.add('size=' + n, function() { doWork(n); });
});
```

### Output Format

- Console text (via `String(event.target)` in cycle event)
- No built-in file export -- relies on event handlers for custom output
- `stats` object available for programmatic access

### Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `delay` | 0.005 | Seconds between cycles |
| `initCount` | 1 | Initial execution count |
| `maxTime` | 5 | Maximum runtime (seconds) |
| `minSamples` | 5 | Minimum sample count |
| `minTime` | 0 | Minimum time to reduce uncertainty |

### Unique/Innovative Features

- **Event-driven architecture** -- `start`, `cycle`, `abort`, `error`, `reset`, `complete` events on both individual benchmarks and suites
- **Compiled setup/teardown** -- setup code shares scope with benchmark function
- **Browser-compatible** -- works in browsers and Node.js
- **Suite filtering** -- `suite.filter('fastest')`, `suite.filter('slowest')`
- **Deferred async model** -- explicit resolve pattern

---

## 8. pytest-benchmark (Python)

**Language:** Python
**Repository:** https://github.com/ionelmc/pytest-benchmark
**Docs:** https://pytest-benchmark.readthedocs.io/

### User API for Defining Benchmarks

pytest fixture injection:

```python
def test_my_function(benchmark):
    result = benchmark(my_function, arg1, arg2, kwarg1="value")
    assert result == expected  # can also assert on results!

# Or as decorator (not recommended for microbenchmarks):
@benchmark
def test_my_function():
    return my_function()
```

**Pedantic mode** for fine-grained control:
```python
def test_precise(benchmark):
    benchmark.pedantic(
        my_function,
        args=(1, 2, 3),
        kwargs={'foo': 'bar'},
        setup=setup_fn,        # called before first iteration of each round
        teardown=teardown_fn,
        iterations=10,         # exact iterations per round
        rounds=100,            # exact number of rounds
        warmup_rounds=5        # warmup rounds before measurement
    )
```

### Setup/Teardown Approach and Granularity

**Normal mode:** No explicit setup/teardown -- the benchmark fixture wraps a callable.

**Pedantic mode:**
- `setup` -- called before the first iteration of every round (per-round setup)
- `teardown` -- called after each round
- `warmup_rounds` -- pre-measurement rounds

**GC control:** `--benchmark-disable-gc` disables garbage collection during benchmark runs.

There is **no per-invocation setup** -- only per-round in pedantic mode.

### Async Benchmarks

No native async support. `async def` functions cannot be directly benchmarked. Users must wrap with `asyncio.run()` or similar.

### Statistical Approach

- **Calibration:** Automatically determines iteration count per round. Runs function repeatedly, doubling until total time exceeds `10 * TIMER_RESOLUTION`. The calibration precision (number of significant digits) is configurable.
- **Rounds:** Multiple rounds of (N iterations each). Default minimum 5 rounds (`--benchmark-min-rounds`).
- **Warmup:** Optional (`--benchmark-warmup`), runs up to 100,000 warmup iterations by default
- **Statistics computed:**
  - min, max, mean, median
  - stddev, IQR
  - outlier counts (round-level)
  - Operations per second (OPS)
- **No bootstrap resampling or confidence intervals**

### Safety Features

- **GC disable** (`--benchmark-disable-gc`) -- prevents GC pauses from affecting measurements
- **Result assertion** -- benchmark returns the function's return value, enabling `assert` on correctness alongside performance
- **Comparison against saved baselines** -- detect regressions
- **Pedantic mode** prevents automatic calibration from masking issues

### Parameterized Benchmarks

Via pytest's `@pytest.mark.parametrize`:
```python
@pytest.mark.parametrize("size", [10, 100, 1000])
def test_sort(benchmark, size):
    data = list(range(size, 0, -1))
    benchmark(sorted, data)
```

### Output Formats

- **Console table** (default, with color)
- **JSON** (`--benchmark-json=output.json` -- includes all raw timings)
- **Histogram** (`--benchmark-histogram` -- SVG via pygal)
- **Saved runs** (`--benchmark-save=NAME`)
- **Comparison** (`--benchmark-compare=0001` -- diff against saved run)

### Command-Line Options

- `--benchmark-sort=NAME` -- sort by name, min, max, mean, etc.
- `--benchmark-group-by=GROUP` -- group by name, param, func, etc. (comma-separated for multiple)
- `--benchmark-warmup=on/off`
- `--benchmark-warmup-iterations=N`
- `--benchmark-min-rounds=N`
- `--benchmark-max-time=SECONDS`
- `--benchmark-calibration-precision=N`
- `--benchmark-disable-gc`
- `--benchmark-enable` / `--benchmark-disable`
- `--benchmark-only` (skip non-benchmark tests)

### Unique/Innovative Features

- **pytest integration** -- benchmarks are regular tests; can run assertions alongside timing
- **Pedantic mode** -- opt-in precise control, bypassing calibration
- **Save/compare workflow** -- save runs and diff against baselines from CLI
- **Histogram generation** -- SVG histograms with interactive tooltips
- **`benchmark.extra_info`** -- attach arbitrary metadata to JSON output
- **Grouping** -- flexible grouping by name, param, function
- **Non-invasive** -- benchmarks are just pytest tests, no separate harness

---

## 9. Hyperfine

**Language:** Rust (CLI tool, language-agnostic)
**Repository:** https://github.com/sharkdp/hyperfine

### User API for Defining Benchmarks

Shell command-based:

```bash
# Simple benchmark
hyperfine 'sleep 0.3'

# Compare implementations
hyperfine 'hexdump file' 'xxd file'

# With warmup and cache clearing
hyperfine --warmup 3 \
  --prepare 'sync; echo 3 | sudo tee /proc/sys/vm/drop_caches' \
  'grep -R TODO *'
```

### Setup/Teardown Approach and Granularity

| Flag | When | Scope |
|------|------|-------|
| `--setup CMD` | Once before entire benchmark session | Global |
| `--prepare CMD` | Before **each** timed run | Per-run (like per-invocation) |
| `--cleanup CMD` | Once after entire benchmark session | Global |

```bash
# Setup creates file, prepare clears cache, cleanup removes file
hyperfine \
  --setup 'dd if=/dev/urandom of=testfile bs=1M count=100' \
  --prepare 'sync; echo 3 | sudo tee /proc/sys/vm/drop_caches' \
  --cleanup 'rm testfile' \
  'cat testfile > /dev/null'
```

### Async Benchmarks

Not applicable -- benchmarks entire process execution. Inherently supports any concurrency model the program uses internally.

### Statistical Approach

- **Automatic run count:** Minimum 10 runs AND at least 3 seconds total wall time
- **Configurable:** `--min-runs N`, `--max-runs N`, `--runs N` (exact)
- **Warmup runs:** `--warmup N` executes N untimed runs first
- **Shell startup calibration:** Automatically measures and subtracts shell startup time
- **Statistics reported:**
  - Mean +/- standard deviation
  - Min, max
  - Relative comparison (X.XX times faster/slower)
- **Outlier detection:** Warns when statistical outliers detected (from background processes, caching effects)
- **No bootstrap or confidence intervals**

### Safety Features

- **Shell startup correction** -- calibrates and removes shell overhead
- **Outlier warnings** -- prints warnings when interference detected
- **`--shell=none` / `-N`** -- skip shell for very fast commands (<5ms) to reduce noise
- **`--ignore-failure`** -- continues even if commands fail (explicit opt-in)

### Parameterized Benchmarks

```bash
# Numeric range
hyperfine --parameter-scan threads 1 12 'make -j {threads}'

# With step size
hyperfine --parameter-scan delay 0.3 0.7 -D 0.2 'sleep {delay}'

# Named parameter list
hyperfine -L compiler gcc,clang '{compiler} -O2 main.cpp'

# Multiple parameters (cartesian product)
hyperfine -L lang rust,go -L opt 0,1,2 '{lang}c -O{opt} main'
```

### Output Formats

- **Console** (colored, default)
- **JSON** (`--export-json results.json`)
- **CSV** (`--export-csv results.csv`)
- **Markdown** (`--export-markdown results.md`)
- **AsciiDoc** (`--export-asciidoc results.adoc`)

### Companion Scripts

Python scripts in `scripts/` directory:
- Histogram generation
- Whisker plots
- Statistical visualization

### Unique/Innovative Features

- **Language-agnostic** -- benchmarks any executable, any language
- **Shell startup calibration** -- unique correction for shell overhead
- **Cache-clearing integration** -- `--prepare` for IO benchmarks
- **Outlier detection warnings** -- alerts about noisy measurements
- **Process-level benchmarking** -- includes startup time, unlike library-based tools
- **Cross-platform** -- Linux, macOS, Windows, FreeBSD
- **Direct execution mode** (`-N`) -- bypass shell for sub-millisecond commands
- **Integration** with Chronologer and Bencher for continuous benchmarking

---

## Cross-Framework Comparison Matrix

| Feature | JMH | Criterion.rs | Google Bench | BDN | Divan | Criterion.hs | Benchmark.js | pytest-bench | hyperfine |
|---------|-----|-------------|-------------|-----|-------|-------------|-------------|-------------|-----------|
| **Setup granularity** | 3 levels (Trial/Iteration/Invocation) | 4 iter methods (batch/ref/large_drop/custom) | Fixture + Pause/Resume + Setup/Teardown | 4 attrs (Global/Iteration Setup/Cleanup) | with_inputs builder | 6 levels (env/perBatch/perRun + cleanup) | Compiled setup/teardown | Pedantic mode setup | prepare/setup/cleanup |
| **Process isolation** | Fork per trial | No | No | Separate project/process | No | No | No | No | Inherent (process) |
| **Async support** | No | Yes (to_async) | Manual timing | Yes (native) | No | IO monad | defer: true | No | N/A |
| **Bootstrap resampling** | No | Yes (100K) | No | No | No | Yes (BCA) | No | No | No |
| **Outlier detection** | No | Modified Tukey | No | Built-in filtering | No | OutlierVariance | No | Count reported | Warnings |
| **DCE prevention** | Blackhole + return | black_box | DoNotOptimize + ClobberMemory | Return consumption | black_box + deferred drop | NFData enforcement | None | None | N/A |
| **Parameterization** | @Param | BenchmarkGroup/BenchmarkId | Range/Args/Capture | Params/Arguments/Source | args/consts/types | bgroup + comprehension | Manual | pytest parametrize | parameter-scan/list |
| **Memory profiling** | No (external) | No | Custom MemoryManager | MemoryDiagnoser built-in | AllocProfiler built-in | No | No | No | No |
| **Correctness validation** | No | No | No | ReturnValueValidator | No | No | No | assert on result | No |
| **Regression detection** | No | Automatic (baseline) | No | No | No | No | No | Save/compare | No |
| **Complexity analysis** | No | No | O(n) fitting | No | No | No | No | No | No |
| **Type-parameterized** | No | No | Templates | No | types = [...] | No | No | No | No |
| **Thread contention** | @Group/@GroupThreads | iter_custom | Threads/ThreadRange | No | threads = [...] | No | No | No | No |

---

## Key Design Insights

### Setup/Teardown Spectrum

From simplest to most granular:
1. **Benchmark.js** -- single setup/teardown compiled into test scope
2. **hyperfine** -- global setup + per-run prepare + global cleanup
3. **pytest-benchmark** -- pedantic mode with per-round setup
4. **Google Benchmark** -- fixture + pause/resume + global setup/teardown
5. **BenchmarkDotNet** -- 4 lifecycle attributes (global/iteration x setup/cleanup)
6. **JMH** -- 3 levels x 3 scopes = 9 combinations
7. **Divan** -- builder-pattern with input generation (type-safe)
8. **Criterion.rs** -- 6 iter methods with different setup/drop semantics (type-safe)
9. **Criterion Haskell** -- 6 env functions with cleanup variants (type-safe, lazy-aware)

### Statistical Rigor Spectrum

From simplest to most rigorous:
1. **Benchmark.js** -- mean, stddev, margin of error
2. **hyperfine** -- mean, stddev, outlier warnings
3. **pytest-benchmark** -- min/max/mean/median/stddev/IQR, outlier counts
4. **Divan** -- fastest/slowest/median/mean, auto-scaled sample sizes
5. **Google Benchmark** -- repetitions with custom statistics, complexity fitting
6. **JMH** -- multiple modes (throughput/avg/sample/single-shot), fork isolation
7. **BenchmarkDotNet** -- multi-stage pipeline with overhead subtraction, process isolation
8. **Criterion.rs** -- bootstrap (100K resamples), linear regression, Tukey outliers, t-test change detection
9. **Criterion Haskell** -- BCA bootstrap, outlier effect quantification (the pioneer)

### Safety Innovation Highlights

- **JMH:** Fork isolation is the gold standard for JVM benchmarks -- nothing else prevents JIT profile pollution
- **Criterion.rs:** `iter_batched` structurally separates setup from timing at the type level
- **Divan:** `Bencher` consumed by-value prevents API misuse via Rust's ownership
- **BenchmarkDotNet:** `ReturnValueValidator` ensures benchmark variants compute the same result -- the only framework checking correctness alongside performance
- **Criterion Haskell:** `NFData` constraint enforces full evaluation -- the compiler prevents you from accidentally benchmarking unevaluated thunks
- **Google Benchmark:** `DoNotOptimize` + `ClobberMemory` is the most explicit compiler barrier API

### API Ergonomics Highlights

- **Divan** has the simplest API: `#[divan::bench]` mirrors `#[test]`, with `args`/`types`/`consts` for parameterization
- **pytest-benchmark** is the least invasive: benchmarks are regular tests with a fixture
- **JMH** is the most annotation-heavy but also the most configurable
- **Criterion Haskell** is the most composable (pure functional benchmark specification)
- **hyperfine** is the most accessible (shell commands, no code changes needed)
