# Implementation Plan: kyo-bench Cross-Platform Benchmark Runner

## Context

`kyo-bench` is currently JVM-only via JMH. As Kyo expands to JS and Native, we need a cross-platform benchmark runner. This module uses Kyo's own primitives (Clock, Fiber, Loop) to provide async-native benchmarking with a callback-based API that any library can use. Research across 9 frameworks (JMH, Criterion.rs, Divan, BenchmarkDotNet, etc.) informed the design — full notes in `benchmark-frameworks-research.md`, `benchmark-safety-research.md`, and `kyo-bench-runner-analysis.md`.

## Strategy

- **Rename** existing `kyo-bench` (JMH) to `kyo-bench-jmh` in build.sbt
- **Create** new `kyo-bench` as a cross-platform module (JVM/JS/Native) depending only on `kyo-core`
- Package: `kyo.bench`
- Implement in phases: core API first, then measurement engine, stats, reporting, safety

---

## Phase 1: Module Scaffold + Build Config

### 1.1 Rename existing JMH module

**File: `build.sbt`**

Change at line 695:
```scala
// Before
lazy val `kyo-bench` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-bench"))
        .enablePlugins(JmhPlugin)
        // ...

// After
lazy val `kyo-bench-jmh` =
    crossProject(JVMPlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Pure)
        .in(file("kyo-bench-jmh"))
        .enablePlugins(JmhPlugin)
        // ... rest unchanged
```

Update kyoJVM aggregate (line ~121):
```scala
// Before
`kyo-bench`.jvm,
// After
`kyo-bench-jmh`.jvm,
```

Rename directory: `mv kyo-bench kyo-bench-jmh`

### 1.2 Add new `kyo-bench` cross-platform module

**File: `build.sbt`** — add after the renamed JMH module:

```scala
lazy val `kyo-bench` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-bench"))
        .dependsOn(`kyo-core`)
        .disablePlugins(MimaPlugin)
        .settings(`kyo-settings`)
        .jvmSettings(
            libraryDependencies += "tools.profiler" % "async-profiler" % "4.0" % Optional,
            libraryDependencies += "org.openjdk.jmh" % "jmh-core" % "1.37" % Optional  // for fromClass reflection
        )
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`)
```

Note: async-profiler JAR bundles native libs for all supported OS/arch. Marked `Optional` so downstream users don't pull it unless they enable profiling.

Add to all three aggregates:
- kyoJVM (line ~121): add `` `kyo-bench`.jvm, ``
- kyoJS (line ~145): add `` `kyo-bench`.js, ``
- kyoNative (line ~170): add `` `kyo-bench`.native, ``

### 1.3 Directory structure

```
kyo-bench/
├── shared/src/main/scala/kyo/bench/
│   ├── Bench.scala              -- user-facing API (only public file)
│   └── internal/
│       ├── BenchDef.scala       -- internal representation
│       ├── Blackhole.scala      -- DCE prevention (delegates to Platform)
│       ├── Fork.scala           -- forked execution orchestration (shared)
│       ├── Profiler.scala       -- profiling orchestration (shared logic)
│       ├── Scalability.scala    -- auto-discover concurrency ceiling
│       ├── Runner.scala         -- measurement engine
│       ├── Stats.scala          -- MovingStdDev, bootstrap, outliers
│       └── Report.scala         -- console + JSON output
├── shared/src/test/scala/kyo/bench/
│   ├── StatsTest.scala
│   ├── RunnerTest.scala
│   └── BenchTest.scala          -- end-to-end integration
├── jvm/src/main/scala/kyo/bench/internal/
│   └── Platform.scala           -- blackhole, threads, async-profiler
├── js/src/main/scala/kyo/bench/internal/
│   └── Platform.scala           -- blackhole, Node.js inspector profiling
└── native/src/main/scala/kyo/bench/internal/
    └── Platform.scala           -- blackhole, threads, async-profiler via native
```

---

## Phase 2: Core API

### 2.1 `Bench.scala` — the single user-facing file

```scala
package kyo.bench

import kyo.*
import kyo.bench.internal.*

opaque type Bench = BenchDef

object Bench:

    // ── Sync benchmarks ──────────────────────────────────────

    def apply(name: String)(f: => Any): Bench =
        BenchDef.Single(
            name    = name,
            isAsync = false,
            setup   = Maybe.empty,
            init    = Maybe.empty,
            cleanup = Maybe.empty,
            syncFn  = Maybe((_: Any) => f),
            asyncFn = Maybe.empty,
            params  = Maybe.empty
        )

    def apply[I](name: String, setup: () => I)(f: I => Any): Bench =
        BenchDef.Single(
            name    = name,
            isAsync = false,
            setup   = Maybe(setup.asInstanceOf[() => Any]),
            init    = Maybe.empty,
            cleanup = Maybe.empty,
            syncFn  = Maybe(f.asInstanceOf[Any => Any]),
            asyncFn = Maybe.empty,
            params  = Maybe.empty
        )

    def apply[R](name: String, init: () => R, cleanup: R => Unit)(f: R => Any): Bench =
        BenchDef.Single(
            name    = name,
            isAsync = false,
            setup   = Maybe.empty,
            init    = Maybe(init.asInstanceOf[() => Any]),
            cleanup = Maybe(cleanup.asInstanceOf[Any => Unit]),
            syncFn  = Maybe(f.asInstanceOf[Any => Any]),
            asyncFn = Maybe.empty,
            params  = Maybe.empty
        )

    // ── Async benchmarks (callback-based) ────────────────────
    FEEDBACK: I think these can be named apply as well? param types seem different
    def async(name: String)(f: (Any => Unit) => Unit): Bench =
        BenchDef.Single(
            name    = name,
            isAsync = true,
            setup   = Maybe.empty,
            init    = Maybe.empty,
            cleanup = Maybe.empty,
            syncFn  = Maybe.empty,
            asyncFn = Maybe((_: Any) => f),
            params  = Maybe.empty
        )
    FEEDBACK don't use () =>, use by name params instead
    def async[I](name: String, setup: () => I)(f: I => (Any => Unit) => Unit): Bench =
        BenchDef.Single(
            name    = name,
            isAsync = true,
            setup   = Maybe(setup.asInstanceOf[() => Any]),
            init    = Maybe.empty,
            cleanup = Maybe.empty,
            syncFn  = Maybe.empty,
            asyncFn = Maybe(f.asInstanceOf[Any => (Any => Unit) => Unit]),
            params  = Maybe.empty
        )

    def async[R](name: String, init: () => R, cleanup: R => Unit)(
        f: R => (Any => Unit) => Unit
    ): Bench =
        BenchDef.Single(
            name    = name,
            isAsync = true,
            setup   = Maybe.empty,
            init    = Maybe(init.asInstanceOf[() => Any]),
            cleanup = Maybe(cleanup.asInstanceOf[Any => Unit]),
            syncFn  = Maybe.empty,
            asyncFn = Maybe(f.asInstanceOf[Any => (Any => Unit) => Unit]),
            params  = Maybe.empty
        )

    // ── Parameterized ────────────────────────────────────────

    def apply[P](name: String, params: Seq[P])(f: P => Any): Bench =
        BenchDef.Suite(
            name,
            params.map { p =>
                BenchDef.Single(
                    name    = s"$name[$p]",
                    isAsync = false,
                    setup   = Maybe.empty,
                    init    = Maybe.empty,
                    cleanup = Maybe.empty,
                    syncFn  = Maybe((_: Any) => f(p)),
                    asyncFn = Maybe.empty,
                    params  = Maybe(Seq(name -> p.asInstanceOf[Any]))
                )
            }
        )

    def async[P](name: String, params: Seq[P])(f: P => (Any => Unit) => Unit): Bench =
        BenchDef.Suite(
            name,
            params.map { p =>
                BenchDef.Single(
                    name    = s"$name[$p]",
                    isAsync = true,
                    setup   = Maybe.empty,
                    init    = Maybe.empty,
                    cleanup = Maybe.empty,
                    syncFn  = Maybe.empty,
                    asyncFn = Maybe((_: Any) => f(p)),
                    params  = Maybe(Seq(name -> p.asInstanceOf[Any]))
                )
            }
        )

    // ── Suite ────────────────────────────────────────────────

    def suite(name: String)(benchmarks: Bench*): Bench =
        BenchDef.Suite(name, benchmarks.map(b => b: BenchDef))

    // ── Execution ────────────────────────────────────────────

    def run(benchmarks: Bench*)(using Frame): Unit < Async =
        run(BenchConfig())(benchmarks*)

    def run(config: BenchConfig)(benchmarks: Bench*)(using Frame): Unit < Async =
        Runner.run(config, benchmarks.map(b => b: BenchDef))

end Bench
```

### 2.2 `BenchDef.scala` — internal representation

```scala
package kyo.bench.internal

import kyo.*

sealed trait BenchDef

object BenchDef:
    case class Single(
        name: String,
        isAsync: Boolean,
        setup: Maybe[() => Any],
        init: Maybe[() => Any],
        cleanup: Maybe[Any => Unit],
        syncFn: Maybe[Any => Any],
        asyncFn: Maybe[Any => (Any => Unit) => Unit],
        params: Maybe[Seq[(String, Any)]]
    ) extends BenchDef

    case class Suite(
        name: String,
        benchmarks: Seq[BenchDef]
    ) extends BenchDef

    // Flatten nested structure into a list of (path, single) pairs for execution
    def flatten(defs: Seq[BenchDef]): Seq[(Maybe[String], Single)] =
        defs.flatMap:
            case s: Single       => Seq((Maybe.empty, s))
            case Suite(name, bs) =>
                bs.flatMap:
                    case s: Single       => Seq((Maybe(name), s))
                    case Suite(n2, bs2)  => flatten(Seq(Suite(s"$name/$n2", bs2)))
end BenchDef
```

### 2.3 `BenchConfig` — in `Bench.scala` or separate file

```scala
package kyo.bench

import kyo.*

case class BenchConfig(
    warmupMaxDuration: Duration    = Duration.fromUnits(10, Duration.Units.Seconds),
    convergenceThreshold: Double   = 0.05,
    measurementDuration: Duration  = Duration.fromUnits(5, Duration.Units.Seconds),
    minSamples: Int                = 50,
    concurrency: Int               = java.lang.Runtime.getRuntime.availableProcessors() * 2,
    validateResults: Boolean       = true,
    detectLeaks: Boolean           = true,
    // Profiling (opt-in, zero-cost when disabled)
    profile: Boolean               = false,
    profileEvents: Seq[String]     = Seq("cpu"),  // cpu, wall, alloc, lock, cache-misses, etc.
    profileOutput: String          = "./bench-profiles",
    profileDuration: Duration      = Duration.fromUnits(5, Duration.Units.Seconds),
    profileAllocations: Boolean    = false, // allocation bytes/op tracking (separate from CPU profiling)
    // Scalability curve (opt-in, auto-discovers concurrency ceiling)
    scalability: Boolean           = false,
    scalabilityEfficiencyFloor: Double = 0.5  // stop probing when efficiency drops below this
)
```

---

## Phase 3: Platform-Specific Code

### 3.1 `Blackhole.scala` — shared, delegates to Platform

```scala
package kyo.bench.internal

private[bench] object Blackhole:
    inline def consume(a: Any): Unit = Platform.consumeBlackhole(a)
end Blackhole
```

### 3.2 `Platform.scala` — JVM

```scala
package kyo.bench.internal

import scala.jdk.CollectionConverters.*
import scala.util.Try

private[bench] object Platform:

    // ── Blackhole ────────────────────────────────────────────
    @volatile private var sink: Any = null
    def consumeBlackhole(a: Any): Unit = sink = a

    // ── Thread leak detection ────────────────────────────────
    def snapshotThreads(): Set[String] =
        Thread.getAllStackTraces().keySet().asScala
            .filterNot(isSystemThread)
            .map(_.getName)
            .toSet

    private def isSystemThread(t: Thread): Boolean =
        val name = t.getName
        t.isDaemon || name == "Reference Handler" || name == "Finalizer" ||
        name == "Signal Dispatcher" || name == "Common-Cleaner" ||
        name.startsWith("JMX") || name.startsWith("Monitor Ctrl") ||
        name.startsWith("Notification") || name.startsWith("process reaper")

    // ── Profiling via async-profiler ─────────────────────────
    // Uses reflection to avoid hard dependency — async-profiler
    // is Optional in build.sbt. No-ops gracefully if not on classpath.

    private lazy val asyncProfiler: Any =
        Try {
            val clazz = Class.forName("one.profiler.AsyncProfiler")
            clazz.getMethod("getInstance").invoke(null)
        }.getOrElse(null)

    private lazy val executeMethod =
        if asyncProfiler != null then
            asyncProfiler.getClass.getMethod("execute", classOf[String])
        else null

    def profilerAvailable: Boolean = asyncProfiler != null

    def startProfile(outputFile: String, events: Seq[String]): Unit =
        if asyncProfiler != null then
            val eventStr = events.mkString(",")
            executeMethod.invoke(asyncProfiler, s"start,jfr,event=$eventStr,file=$outputFile")

    def stopProfile(outputFile: String): Unit =
        if asyncProfiler != null then
            executeMethod.invoke(asyncProfiler, s"stop,file=$outputFile")

    // ── Allocation tracking via ThreadMXBean ─────────────────
    // Built-in, no external dependency needed.

    private lazy val threadMXBean: Any =
        Try {
            val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            val sunBean = bean.asInstanceOf[com.sun.management.ThreadMXBean]
            sunBean.setThreadAllocatedMemoryEnabled(true)
            sunBean
        }.getOrElse(null)

    def getAllocatedBytes(): Long =
        if threadMXBean != null then
            threadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
                .getThreadAllocatedBytes(Thread.currentThread().getId())
        else -1L

end Platform
```

### 3.3 `Platform.scala` — JS

```scala
package kyo.bench.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

private[bench] object Platform:

    // ── Blackhole ────────────────────────────────────────────
    private var sink: Any = null
    def consumeBlackhole(a: Any): Unit = sink = a

    // ── Thread leak detection ────────────────────────────────
    def snapshotThreads(): Set[String] = Set.empty

    // ── Profiling via Node.js Inspector API ──────────────────
    // Node.js built-in: no npm dependency needed.
    // Uses js.Dynamic to call the inspector module.

    private var session: js.Dynamic = null

    def profilerAvailable: Boolean =
        js.typeOf(js.Dynamic.global.process) != "undefined" // Node.js only

    def startProfile(outputFile: String, events: Seq[String]): Unit =
        try
            val inspector = js.Dynamic.global.require("node:inspector")
            session = js.Dynamic.newInstance(inspector.Session)()
            session.connect()
            session.post("Profiler.enable")
            session.post("Profiler.start")
        catch case _: Exception => () // graceful no-op in browsers

    def stopProfile(outputFile: String): Unit =
        if session != null then
            session.post("Profiler.stop", null, { (err: js.Dynamic, params: js.Dynamic) =>
                if err == null then
                    val fs = js.Dynamic.global.require("node:fs")
                    val json = js.JSON.stringify(params.profile)
                    val file = outputFile.replace(".jfr", ".cpuprofile")
                    fs.writeFileSync(file, json)
                session.disconnect()
                session = null
            }: js.Function2[js.Dynamic, js.Dynamic, Unit])

    // ── Allocation tracking ──────────────────────────────────
    // Coarse heap snapshot via process.memoryUsage().heapUsed

    def getAllocatedBytes(): Long =
        try
            js.Dynamic.global.process.memoryUsage().heapUsed.asInstanceOf[Double].toLong
        catch case _: Exception => -1L

end Platform
```

### 3.4 `Platform.scala` — Native

async-profiler can profile native processes via perf_events (#751).
On Native, we shell out to `asprof` CLI rather than loading the native lib.

```scala
package kyo.bench.internal

import scala.scalanative.annotation.nooptimize
import scala.jdk.CollectionConverters.*
import scala.util.Try

private[bench] object Platform:

    // ── Blackhole ────────────────────────────────────────────
    @volatile private var sink: Any = null
    @nooptimize
    def consumeBlackhole(a: Any): Unit = sink = a

    // ── Thread leak detection ────────────────────────────────
    def snapshotThreads(): Set[String] =
        Thread.getAllStackTraces().keySet().asScala
            .filterNot(_.isDaemon)
            .map(_.getName)
            .toSet

    // ── Profiling via async-profiler CLI ─────────────────────
    // async-profiler can profile native processes via perf_events.
    // We shell out to `asprof` which must be on PATH.

    private lazy val asprofPath: String =
        Try {
            val p = new ProcessBuilder("which", "asprof").start()
            val path = new String(p.getInputStream.readAllBytes()).trim
            p.waitFor()
            if p.exitValue() == 0 then path else null
        }.getOrElse(null)

    private var profilerProcess: Process = null

    def profilerAvailable: Boolean = asprofPath != null

    def startProfile(outputFile: String, events: Seq[String]): Unit =
        if asprofPath != null then
            val pid = ProcessHandle.current().pid().toString
            val eventStr = events.mkString(",")
            // Start async-profiler attached to our own PID
            profilerProcess = new ProcessBuilder(
                asprofPath, "start",
                "-e", eventStr,
                "-f", outputFile,
                "-o", "jfr",
                pid
            ).inheritIO().start()

    def stopProfile(outputFile: String): Unit =
        if asprofPath != null then
            val pid = ProcessHandle.current().pid().toString
            new ProcessBuilder(
                asprofPath, "stop",
                "-f", outputFile,
                pid
            ).inheritIO().start().waitFor()
            profilerProcess = null

    // ── Allocation tracking ──────────────────────────────────
    // No per-thread API on Native. Return -1 to signal unavailable.

    def getAllocatedBytes(): Long = -1L

end Platform
```

---

## Phase 3.5: Profiling Orchestration — `Profiler.scala`

Shared logic that delegates to platform-specific `Platform.startProfile`/`stopProfile`.

```scala
package kyo.bench.internal

import kyo.*

private[bench] object Profiler:

    // Called after warmup + measurement, as a dedicated third pass.
    // The benchmark is re-run under the profiler for profileDuration.
    def profileIfEnabled(
        config: BenchConfig,
        single: BenchDef.Single,
        resource: Maybe[Any]
    )(using Frame): Unit < Async =
        if !config.profile then Kyo.unit
        else if !Platform.profilerAvailable then
            Console.println("  Profiling requested but no profiler available on this platform.".yellow)
        else
            val dir = config.profileOutput
            Sync.defer(java.io.File(dir).mkdirs())
            val safeName = single.name.replaceAll("[^a-zA-Z0-9_-]", "_")
            val outputFile = s"$dir/$safeName.jfr"
            Console.println(s"  Profiling ${single.name} → $outputFile".dim).flatMap { _ =>
                // Start profiler
                Sync.defer(Platform.startProfile(outputFile, config.profileEvents))
                // Re-run the benchmark for profileDuration
                Clock.stopwatch.flatMap { sw =>
                    Loop.whileTrue {
                        Runner.executeSingleOp(single, resource).flatMap { _ =>
                            sw.elapsed.map { elapsed =>
                                if elapsed >= config.profileDuration then Loop.done(())
                                else Loop.continue
                            }
                        }
                    }
                }.flatMap { _ =>
                    // Stop profiler
                    Sync.defer(Platform.stopProfile(outputFile))
                }
            }
    end profileIfEnabled

    // Allocation tracking: measure bytes allocated during measurement.
    // Returns bytes/op or -1 if unavailable.
    def measureAllocations(
        config: BenchConfig,
        single: BenchDef.Single,
        resource: Maybe[Any],
        opCount: Int
    )(using Frame): Long < Sync =
        if !config.profileAllocations then Sync.defer(-1L)
        else
            val before = Platform.getAllocatedBytes()
            if before == -1L then Sync.defer(-1L)
            else
                Loop.repeat(opCount) {
                    Runner.executeSingleOp(single, resource)
                }.map { _ =>
                    val after = Platform.getAllocatedBytes()
                    if after == -1L then -1L
                    else (after - before) / opCount
                }
end Profiler
```

**Pipeline order**: Warmup → Measure → Profile (dedicated pass). Profiling never affects measurement timing.

---

## Phase 3.6: Scalability Curve — `Scalability.scala`

Auto-discovers the concurrency ceiling by doubling worker count until efficiency collapses. Same idea as warmup convergence — the runner finds the shape, not the user.

```scala
package kyo.bench.internal

import kyo.*

private[bench] object Scalability:

    case class ScalabilityPoint(
        concurrency: Int,
        throughput: Double,   // ops/s
        scaling: Double,      // thrpt_N / thrpt_1
        efficiency: Double    // scaling / N
    )

    case class ScalabilityCurve(
        name: String,
        points: Seq[ScalabilityPoint],
        peak: ScalabilityPoint   // highest throughput point
    )

    // Discovers the scalability curve for a single benchmark.
    // Algorithm:
    //   1. Measure at concurrency=1 (baseline)
    //   2. Double: 2, 4, 8, 16, ...
    //   3. At each level compute efficiency = (thrpt_N / thrpt_1) / N
    //   4. Stop when:
    //      - efficiency < floor for 2 consecutive levels, OR
    //      - throughput decreases from previous level for 2 consecutive levels, OR
    //      - concurrency exceeds available processors * 4
    //   5. Return the full curve with peak highlighted
    def discover(
        config: BenchConfig,
        single: BenchDef.Single,
        resource: Maybe[Any]
    )(using Frame): ScalabilityCurve < Async =
        val maxConcurrency = java.lang.Runtime.getRuntime.availableProcessors() * 4
        val floor          = config.scalabilityEfficiencyFloor

        // Measure throughput at a given concurrency level.
        // For sync: spawns N OS threads each running bench in tight loop.
        // For async: launches N fibers (reuses measureAsync path).
        def measureAt(n: Int): Double < Async =
            val perLevelConfig = config.copy(
                concurrency = n,
                // Shorter measurement per level — we're sweeping, not final measurement
                measurementDuration = Duration.fromUnits(2, Duration.Units.Seconds),
                minSamples = 20
            )
            val samples =
                if single.isAsync then Runner.measureAsync(perLevelConfig, single, resource)
                else Runner.measureSyncConcurrent(perLevelConfig, single, resource, n)
            samples.map { arr =>
                if arr.isEmpty then 0d
                else
                    val meanNanos = arr.sum / arr.length
                    if meanNanos == 0d then 0d
                    else (1_000_000_000d / meanNanos) * n  // total ops/s across all workers
            }

        // Sweep loop
        Loop(
            1,                              // current concurrency
            Seq.empty[ScalabilityPoint],     // accumulated points
            0                               // consecutive "below floor" count
        ) { (concurrency, points, belowCount) =>
            measureAt(concurrency).map { throughput =>
                val baseline   = points.headOption.map(_.throughput).getOrElse(throughput)
                val scaling    = if baseline == 0d then 0d else throughput / baseline
                val efficiency = if concurrency == 1 then 1.0 else scaling / concurrency
                val point      = ScalabilityPoint(concurrency, throughput, scaling, efficiency)
                val newPoints  = points :+ point

                val prevThrpt   = points.lastOption.map(_.throughput).getOrElse(0d)
                val declining   = throughput < prevThrpt * 0.95  // 5% tolerance
                val belowFloor  = efficiency < floor
                val newBelow    = if belowFloor || declining then belowCount + 1 else 0
                val nextConc    = concurrency * 2

                if newBelow >= 2 || nextConc > maxConcurrency then
                    val peak = newPoints.maxBy(_.throughput)
                    Loop.done(ScalabilityCurve(single.name, newPoints, peak))
                else
                    Loop.continue(nextConc, newPoints, newBelow)
            }
        }
    end discover

end Scalability
```

This requires a new `measureSyncConcurrent` method in `Runner` that spawns N OS threads (not fibers) each running the sync bench. For async benchmarks, we already have `measureAsync` which takes `config.concurrency`.

**Runner addition — `measureSyncConcurrent`:**

```scala
// In Runner.scala — measure sync benchmark with N concurrent OS threads
private[bench] def measureSyncConcurrent(
    config: BenchConfig,
    single: BenchDef.Single,
    resource: Maybe[Any],
    threads: Int
)(using Frame): Array[Double] < Async =
    // Launch N fibers, each pinned to execute the sync bench in a loop.
    // Using Fiber rather than raw Thread because Kyo manages the thread pool,
    // but the sync bench itself runs without yielding (tight loop per worker).
    val perWorkerConfig = config.copy(concurrency = 1)
    Kyo.foreach(Seq.fill(threads)(())) { _ =>
        measureSync(perWorkerConfig, single, resource)
    }.map { arrays =>
        // Merge all per-worker sample arrays
        val total = arrays.map(_.length).sum
        val merged = new Array[Double](total)
        var offset = 0
        arrays.foreach { arr =>
            java.lang.System.arraycopy(arr, 0, merged, offset, arr.length)
            offset += arr.length
        }
        merged
    }
```

**Report output for scalability curve:**

```
benchmark: channel-ping-pong (scalability)
concurrency    thrpt (ops/s)    scaling     efficiency
1              1,234,567        1.00x       100%
2              2,401,234        1.95x        97%
4              4,512,345        3.66x        91%
8              7,234,567        5.86x        73%
16             8,901,234        7.21x        45%  ← peak
32             8,123,456        6.58x        21%
```

The peak row is marked. Efficiency going red/yellow when it drops below thresholds.

---

## Phase 4: Statistics — `Stats.scala`

```scala
package kyo.bench.internal

import java.util.Arrays

private[bench] object Stats:

    // ── MovingStdDev ─────────────────────────────────────────
    // Ported from kyo-scheduler/jvm-native/.../MovingStdDev.scala
    // Pure math, no platform dependencies.

    FEEDBACK Can we use Kyo's histogram?
    final class MovingStdDev(window: Int):
        private val values = new Array[Long](window)
        private var idx    = 0L

        def observe(v: Long): Unit =
            values((idx % window).toInt) = v
            idx += 1

        def count: Long = idx

        def avg(): Double =
            val n   = Math.min(idx, window.toLong)
            if n == 0 then return 0d
            var sum = 0d FEEDBACK there should be zero vars or while in the code. Use @tailrec def loop
            var i   = 0
            while i < n do
                sum += values(i % window)
                i += 1
            sum / n

        def dev(): Double =
            val n = Math.min(idx, window.toLong)
            if n <= 1 then return 0d
            var sum   = 0L
            var sumSq = 0L
            var i     = 0
            while i < n do
                val v = values(i % window)
                sum += v
                sumSq += v * v
                i += 1
            val mean     = sum.toDouble / n
            val variance = (sumSq.toDouble / n) - (mean * mean)
            if n > 1 then Math.sqrt(variance * n / (n - 1)) else 0.0
        end dev

        def coefficientOfVariation(): Double =
            val a = avg()
            if a == 0d then Double.MaxValue
            else dev() / a
    end MovingStdDev

    // ── Percentile ───────────────────────────────────────────

    def percentile(sorted: Array[Double], p: Double): Double =
        if sorted.isEmpty then return 0d
        val index = p * (sorted.length - 1)
        val lower = index.toInt
        val upper = Math.min(lower + 1, sorted.length - 1)
        val frac  = index - lower
        sorted(lower) * (1.0 - frac) + sorted(upper) * frac
    end percentile

    // ── Bootstrap Resampling ─────────────────────────────────
    // Non-parametric percentile bootstrap. Does NOT assume normality.
    // Uses java.util.Random directly (no Kyo effects — this is pure math).

    case class BootstrapResult(
        mean: Double,
        median: Double,
        stdDev: Double,
        ciLow: Double,
        ciHigh: Double,
        percentiles: Map[String, Double]
    )

    def bootstrap(
        samples: Array[Double],
        resamples: Int,
        confidenceLevel: Double
    ): BootstrapResult =
        val rng       = new java.util.Random()
        val n         = samples.length
        val means     = new Array[Double](resamples)
        val resample  = new Array[Double](n)

        var i = 0
        while i < resamples do
            // Generate one bootstrap resample
            var j = 0
            while j < n do
                resample(j) = samples(rng.nextInt(n))
                j += 1
            // Compute mean of this resample
            var sum = 0d
            j = 0
            while j < n do
                sum += resample(j)
                j += 1
            means(i) = sum / n
            i += 1
        end while

        Arrays.sort(means)

        val alpha = (1.0 - confidenceLevel) / 2.0

        // Compute percentiles of the original samples
        val sortedSamples = samples.clone()
        Arrays.sort(sortedSamples)

        BootstrapResult(
            mean    = mean(samples),
            median  = percentile(sortedSamples, 0.50),
            stdDev  = stddev(samples),
            ciLow   = percentile(means, alpha),
            ciHigh  = percentile(means, 1.0 - alpha),
            percentiles = Map(
                "p50"  -> percentile(sortedSamples, 0.50),
                "p90"  -> percentile(sortedSamples, 0.90),
                "p95"  -> percentile(sortedSamples, 0.95),
                "p99"  -> percentile(sortedSamples, 0.99),
                "p999" -> percentile(sortedSamples, 0.999)
            )
        )
    end bootstrap

    // ── Outlier Detection (Modified Tukey) ───────────────────

    case class OutlierReport(
        mild: Int,
        severe: Int,
        total: Int
    )

    def detectOutliers(sorted: Array[Double]): OutlierReport =
        if sorted.length < 4 then return OutlierReport(0, 0, sorted.length)
        val q1  = percentile(sorted, 0.25)
        val q3  = percentile(sorted, 0.75)
        val iqr = q3 - q1
        val mildLow    = q1 - 1.5 * iqr
        val mildHigh   = q3 + 1.5 * iqr
        val severeLow  = q1 - 3.0 * iqr
        val severeHigh = q3 + 3.0 * iqr
        var mild   = 0
        var severe = 0
        var i      = 0
        while i < sorted.length do
            val v = sorted(i)
            if v < severeLow || v > severeHigh then severe += 1
            else if v < mildLow || v > mildHigh then mild += 1
            i += 1
        OutlierReport(mild, severe, sorted.length)
    end detectOutliers

    // ── Helpers ──────────────────────────────────────────────

    private def mean(arr: Array[Double]): Double =
        var sum = 0d
        var i   = 0
        while i < arr.length do
            sum += arr(i)
            i += 1
        sum / arr.length

    private def stddev(arr: Array[Double]): Double =
        val m = mean(arr)
        var sumSq = 0d
        var i = 0
        while i < arr.length do
            val d = arr(i) - m
            sumSq += d * d
            i += 1
        Math.sqrt(sumSq / (arr.length - 1))

end Stats
```

---

## Phase 5: Measurement Engine — `Runner.scala`

```scala
package kyo.bench.internal

import kyo.*

private[bench] object Runner:

    def run(config: BenchConfig, defs: Seq[BenchDef])(using Frame): Unit < Async =
        val flat = BenchDef.flatten(defs)
        // Pre-flight: validate suite results
        val validated =
            if config.validateResults then validateSuiteResults(defs)
            else Kyo.unit
        validated.flatMap { _ =>
            // Run each benchmark sequentially (no interference between benchmarks)
            Kyo.foreach(flat) { case (suiteName, single) =>
                runSingle(config, suiteName, single)
            }.flatMap { results =>
                Report.print(results)
            }
        }
    end run

    // ── Pre-flight: result validation ────────────────────────

    private def validateSuiteResults(defs: Seq[BenchDef])(using Frame): Unit < Async =
        Kyo.foreach(defs) {
            case BenchDef.Suite(name, benchmarks) =>
                val singles = benchmarks.collect { case s: BenchDef.Single => s }
                Kyo.foreach(singles) { s =>
                    runOnce(s)
                }.map { results =>
                    val distinct = results.distinct
                    if distinct.size > 1 then
                        Console.println(
                            s"WARNING: Suite '$name' benchmarks return different results: $distinct".yellow
                        )
                    else Kyo.unit
                }
            case _ => Kyo.unit
        }.unit
    end validateSuiteResults

    // Run a benchmark once for result validation
    private def runOnce(single: BenchDef.Single)(using Frame): Any < Sync =
        val input = single.init.map(_()).orElse(single.setup.map(_())).getOrElse(())
        if single.isAsync then
            Promise.init[Nothing, Any].flatMap { promise =>
                val fn = single.asyncFn.getOrElse(throw new IllegalStateException("missing asyncFn"))
                Sync.defer(fn(input)(a => promise.completeDiscard(Result.succeed(a))))
                promise.get
            }
        else
            val fn = single.syncFn.getOrElse(throw new IllegalStateException("missing syncFn"))
            Sync.defer(fn(input))
    end runOnce

    // ── Single benchmark execution pipeline ──────────────────

    private def runSingle(
        config: BenchConfig,
        suiteName: Maybe[String],
        single: BenchDef.Single
    )(using Frame): BenchResult < Async =
        // 1. Thread snapshot (before)
        val threadsBefore =
            if config.detectLeaks then Platform.snapshotThreads()
            else Set.empty[String]

        // 2. Shared init
        val resource = single.init.map(_())

        // 3. Warmup
        warmup(config, single, resource).flatMap { _ =>

            // 4. Measurement (no profiler attached — clean timing)
            val measurement =
                if single.isAsync then measureAsync(config, single, resource)
                else measureSync(config, single, resource)

            measurement.flatMap { samples =>

                // 5. Profile (dedicated pass — re-runs benchmark under profiler)
                Profiler.profileIfEnabled(config, single, resource).flatMap { _ =>

                // 6. Allocation tracking (short dedicated pass)
                Profiler.measureAllocations(config, single, resource, 1000).flatMap { bytesPerOp =>

                // 7. Scalability curve (auto-discovers concurrency ceiling)
                val scalabilityCurve =
                    if config.scalability then
                        Scalability.discover(config, single, resource).map(c => Maybe(c))
                    else Kyo.lift(Maybe.empty[Scalability.ScalabilityCurve])

                scalabilityCurve.flatMap { curve =>

                // 8. Shared cleanup
                single.cleanup.foreach(cleanup => resource.foreach(r => cleanup(r)))

                // 8. Stats
                val sorted = samples.clone()
                java.util.Arrays.sort(sorted)
                val stats    = Stats.bootstrap(sorted, 10_000, 0.95)
                val outliers = Stats.detectOutliers(sorted)

                // 9. Thread leak check
                val warnings = Chunk.empty[String]
                val leakWarnings =
                    if config.detectLeaks then
                        Async.sleep(Duration.fromUnits(200, Duration.Units.Millis)).flatMap { _ =>
                            Sync.defer {
                                val threadsAfter = Platform.snapshotThreads()
                                val leaked = threadsAfter.diff(threadsBefore)
                                if leaked.nonEmpty then
                                    Chunk(s"Leaked ${leaked.size} thread(s): ${leaked.mkString(", ")}")
                                else Chunk.empty[String]
                            }
                        }
                    else Kyo.lift(Chunk.empty[String])

                leakWarnings.map { lw =>
                    val outlierWarnings =
                        if outliers.mild > 0 || outliers.severe > 0 then
                            Chunk(s"${outliers.mild} mild, ${outliers.severe} severe outlier(s)")
                        else Chunk.empty[String]

                    val allWarnings = lw.concat(outlierWarnings)

                    // Throughput = ops/sec
                    val opsPerSec = stats.mean match
                        case 0d => 0d
                        case ns => 1_000_000_000d / ns

                    val errorPct =
                        if stats.mean == 0d then 0d
                        else ((stats.ciHigh - stats.ciLow) / 2.0 / stats.mean) * 100.0

                    BenchResult(
                        name        = single.name,
                        suite       = suiteName,
                        params      = single.params
                            .map(_.map((k, v) => k -> v.toString).toMap)
                            .getOrElse(Map.empty),
                        mode        = "thrpt",
                        score       = opsPerSec,
                        scoreError  = errorPct,
                        unit        = "ops/s",
                        bytesPerOp  = bytesPerOp,
                        scalability = curve,
                        percentiles = stats.percentiles.map((k, ns) => k -> 1_000_000_000d / ns),
                        outliers    = outliers,
                        warnings    = allWarnings
                    )
                }
            }}}  // close profile + alloc + scalability flatMaps
        }
    end runSingle

    // ── Warmup (convergence-based) ───────────────────────────

    private def warmup(
        config: BenchConfig,
        single: BenchDef.Single,
        resource: Maybe[Any]
    )(using Frame): Unit < Async =
        Clock.stopwatch.flatMap { sw =>
            val msd = new Stats.MovingStdDev(50)
            Loop.whileTrue {
                // Run a small batch and time it
                val batchSize = 10
                Clock.nowMonotonic.flatMap { batchStart =>
                    Loop.repeat(batchSize) {
                        executeSingleOp(single, resource)
                    }.flatMap { _ =>
                        Clock.nowMonotonic.flatMap { batchEnd =>
                            val batchNanos = (batchEnd - batchStart).toNanos
                            msd.observe(batchNanos / batchSize)
                            sw.elapsed.map { elapsed =>
                                if elapsed >= config.warmupMaxDuration then
                                    Loop.done(())  // safety cap
                                else if msd.count > 50 && msd.coefficientOfVariation() < config.convergenceThreshold then
                                    Loop.done(())  // converged
                                else
                                    Loop.continue
                            }
                        }
                    }
                }
            }
        }
    end warmup

    // ── Sync measurement ─────────────────────────────────────

    private def measureSync(
        config: BenchConfig,
        single: BenchDef.Single,
        resource: Maybe[Any]
    )(using Frame): Array[Double] < Sync =
        Clock.stopwatch.flatMap { sw =>
            val samples = new java.util.ArrayList[Double](config.minSamples * 2)
            val buffer  = new Array[Any](64) // deferred drop buffer
            var bufIdx  = 0

            Loop.whileTrue {
                val input = resource
                    .orElse(single.setup.map(_()))
                    .getOrElse(())

                Clock.nowMonotonic.flatMap { opStart =>
                    val fn     = single.syncFn.getOrElse(throw new IllegalStateException("missing syncFn"))
                    val result = fn(input)

                    Clock.nowMonotonic.flatMap { opEnd =>
                        buffer(bufIdx % 64) = result
                        bufIdx += 1
                        Blackhole.consume(result)

                        val nanos = (opEnd - opStart).toNanos.toDouble
                        samples.add(nanos)

                        sw.elapsed.map { elapsed =>
                            if elapsed >= config.measurementDuration && samples.size() >= config.minSamples then
                                Loop.done(())
                            else
                                Loop.continue
                        }
                    }
                }
            }.map { _ =>
                // Clear deferred drop buffer
                java.util.Arrays.fill(buffer, null)
                val arr = new Array[Double](samples.size())
                var i = 0
                while i < samples.size() do
                    arr(i) = samples.get(i)
                    i += 1
                arr
            }
        }
    end measureSync

    // ── Async measurement ────────────────────────────────────

    private def measureAsync(
        config: BenchConfig,
        single: BenchDef.Single,
        resource: Maybe[Any]
    )(using Frame): Array[Double] < Async =
        Clock.stopwatch.flatMap { sw =>
            AtomicRef.init(Chunk.empty[Double]).flatMap { samplesRef =>
                // Launch concurrency fibers, each running the bench in a loop
                Kyo.foreach(Seq.fill(config.concurrency)(())) { _ =>
                    Loop.whileTrue {
                        val input = resource
                            .orElse(single.setup.map(_()))
                            .getOrElse(())

                        Clock.nowMonotonic.flatMap { opStart =>
                            Promise.init[Nothing, Any].flatMap { promise =>
                                val fn = single.asyncFn.getOrElse(
                                    throw new IllegalStateException("missing asyncFn")
                                )
                                Sync.defer(fn(input)(a => promise.completeDiscard(Result.succeed(a))))
                                promise.get.flatMap { result =>
                                    Clock.nowMonotonic.flatMap { opEnd =>
                                        Blackhole.consume(result)
                                        val nanos = (opEnd - opStart).toNanos.toDouble
                                        samplesRef.update(_.append(nanos)).flatMap { _ =>
                                            sw.elapsed.map { elapsed =>
                                                if elapsed >= config.measurementDuration then
                                                    Loop.done(())
                                                else
                                                    Loop.continue
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.flatMap { _ =>
                    samplesRef.get.map { chunk =>
                        val arr = new Array[Double](chunk.length)
                        var i = 0
                        chunk.foreach { v =>
                            arr(i) = v
                            i += 1
                        }
                        arr
                    }
                }
            }
        }
    end measureAsync

    // ── Helpers ──────────────────────────────────────────────

    private def executeSingleOp(
        single: BenchDef.Single,
        resource: Maybe[Any]
    )(using Frame): Unit < Async =
        val input = resource
            .orElse(single.setup.map(_()))
            .getOrElse(())
        if single.isAsync then
            Promise.init[Nothing, Any].flatMap { promise =>
                val fn = single.asyncFn.getOrElse(throw new IllegalStateException("missing asyncFn"))
                Sync.defer(fn(input)(a => promise.completeDiscard(Result.succeed(a))))
                promise.get.map(a => Blackhole.consume(a))
            }
        else
            Sync.defer {
                val fn = single.syncFn.getOrElse(throw new IllegalStateException("missing syncFn"))
                Blackhole.consume(fn(input))
            }
    end executeSingleOp

end Runner
```

### `BenchResult` — result data model

```scala
package kyo.bench.internal

import kyo.*

case class BenchResult(
    name: String,
    suite: Maybe[String],
    params: Map[String, String],
    mode: String,
    score: Double,
    scoreError: Double,
    unit: String,
    bytesPerOp: Long,              // allocation bytes/op, -1 if unavailable
    scalability: Maybe[Scalability.ScalabilityCurve],
    percentiles: Map[String, Double],
    outliers: Stats.OutlierReport,
    warnings: Chunk[String]
)
```

---

## Phase 6: Reporting — `Report.scala`

```scala
package kyo.bench.internal

import kyo.*

private[bench] object Report:

    def print(results: Chunk[BenchResult])(using Frame): Unit < Sync =
        // Group by suite
        val grouped = results.toSeq.groupBy(_.suite)
        val lines   = new StringBuilder

        // Header
        lines.append(formatHeader())
        lines.append("\n")

        // Top-level (no suite)
        grouped.get(Maybe.empty).foreach { rs =>
            rs.foreach(r => lines.append(formatRow("", r, isLast = false)))
        }

        // Suites
        val suiteNames = grouped.keys.filter(_.isDefined).toSeq.sortBy(_.get)
        suiteNames.zipWithIndex.foreach { case (suiteName, suiteIdx) =>
            val isLastSuite = suiteIdx == suiteNames.size - 1
            val prefix      = if isLastSuite then "└─ " else "├─ "
            lines.append(s"$prefix${suiteName.get}\n".bold)

            val rs = grouped(suiteName)
            rs.zipWithIndex.foreach { case (r, i) =>
                val isLast    = i == rs.size - 1
                val childPre  = if isLastSuite then "   " else "│  "
                val connector = if isLast then "└─ " else "├─ "
                lines.append(formatRow(s"$childPre$connector", r, isLast))
            }
        }

        // Warnings
        val allWarnings = results.toSeq.flatMap(r =>
            r.warnings.toSeq.map(w => s"${r.name}: $w")
        )
        if allWarnings.nonEmpty then
            lines.append("\n")
            allWarnings.foreach(w => lines.append(s"  WARNING: $w\n".yellow))

        Console.println(lines.toString)
    end print

    private def formatHeader(hasAlloc: Boolean): String =
        val base = f"${"benchmark"}%-30s ${"mode"}%-6s ${"score"}%>14s ${"error"}%>8s ${"unit"}%-8s ${"p50"}%>10s ${"p99"}%>10s"
        if hasAlloc then (base + f" ${"alloc"}%>10s").bold
        else base.bold

    private def formatRow(prefix: String, r: BenchResult, isLast: Boolean, hasAlloc: Boolean): String =
        val name  = s"$prefix${r.name}"
        val score = formatNumber(r.score)
        val error = f"±${r.scoreError}%.1f%%"
        val p50   = r.percentiles.get("p50").map(formatDuration).getOrElse("-")
        val p99   = r.percentiles.get("p99").map(formatDuration).getOrElse("-")
        val base  = f"$name%-30s ${r.mode}%-6s $score%>14s $error%>8s ${r.unit}%-8s $p50%>10s $p99%>10s"
        if hasAlloc then
            val alloc = if r.bytesPerOp >= 0 then formatBytes(r.bytesPerOp) else "-"
            base + f" $alloc%>10s\n"
        else base + "\n"

    private def formatNumber(d: Double): String =
        if d >= 1_000_000 then f"${d / 1_000_000}%.3f M"
        else if d >= 1_000 then f"${d / 1_000}%.3f K"
        else f"$d%.1f"

    private def formatDuration(opsPerSec: Double): String =
        if opsPerSec <= 0 then "-"
        else
            val ns = 1_000_000_000d / opsPerSec
            if ns >= 1_000_000 then f"${ns / 1_000_000}%.1f ms"
            else if ns >= 1_000 then f"${ns / 1_000}%.1f us"
            else f"$ns%.0f ns"

end Report
```

---

## Phase 7: Tests

### 7.1 `StatsTest.scala` — pure math, no async

```scala
package kyo.bench

class StatsTest extends kyo.Test:

    // ── MovingStdDev ─────────────────────────────────────────

    "MovingStdDev" - {
        "constant values have zero deviation" in {
            val msd = new Stats.MovingStdDev(10)
            (0 until 20).foreach(_ => msd.observe(100L))
            assert(msd.dev() == 0d)
            assert(msd.avg() == 100d)
        }

        "coefficient of variation converges for stable input" in {
            val msd = new Stats.MovingStdDev(50)
            // Slightly noisy but stable
            val rng = new java.util.Random(42)
            (0 until 100).foreach(_ => msd.observe(1000L + rng.nextInt(10)))
            assert(msd.coefficientOfVariation() < 0.01)
        }

        "coefficient of variation is high for unstable input" in {
            val msd = new Stats.MovingStdDev(50)
            val rng = new java.util.Random(42)
            (0 until 100).foreach(_ => msd.observe(rng.nextLong(10000L)))
            assert(msd.coefficientOfVariation() > 0.3)
        }

        "window size limits retained values" in {
            val msd = new Stats.MovingStdDev(5)
            (0 until 5).foreach(_ => msd.observe(100L))
            (0 until 5).foreach(_ => msd.observe(200L))
            // Window should contain only 200s now
            assert(msd.avg() == 200d)
        }

        "count tracks total observations" in {
            val msd = new Stats.MovingStdDev(10)
            (0 until 25).foreach(_ => msd.observe(1L))
            assert(msd.count == 25L)
        }

        "empty has zero avg and dev" in {
            val msd = new Stats.MovingStdDev(10)
            assert(msd.avg() == 0d)
            assert(msd.dev() == 0d)
        }

        "single observation has zero dev" in {
            val msd = new Stats.MovingStdDev(10)
            msd.observe(42L)
            assert(msd.avg() == 42d)
            assert(msd.dev() == 0d)
        }
    }

    // ── Percentile ───────────────────────────────────────────

    "percentile" - {
        "p0 returns min" in {
            val sorted = Array(1.0, 2.0, 3.0, 4.0, 5.0)
            assert(Stats.percentile(sorted, 0.0) == 1.0)
        }

        "p100 returns max" in {
            val sorted = Array(1.0, 2.0, 3.0, 4.0, 5.0)
            assert(Stats.percentile(sorted, 1.0) == 5.0)
        }

        "p50 returns median for odd-length" in {
            val sorted = Array(1.0, 2.0, 3.0, 4.0, 5.0)
            assert(Stats.percentile(sorted, 0.5) == 3.0)
        }

        "p50 interpolates for even-length" in {
            val sorted = Array(1.0, 2.0, 3.0, 4.0)
            assert(Stats.percentile(sorted, 0.5) == 2.5)
        }

        "empty array returns 0" in {
            assert(Stats.percentile(Array.empty[Double], 0.5) == 0d)
        }

        "single element returns that element for any percentile" in {
            val sorted = Array(42.0)
            assert(Stats.percentile(sorted, 0.0) == 42.0)
            assert(Stats.percentile(sorted, 0.5) == 42.0)
            assert(Stats.percentile(sorted, 1.0) == 42.0)
        }
    }

    // ── Bootstrap ────────────────────────────────────────────

    "bootstrap" - {
        "confidence interval contains true mean for normal-ish data" in {
            val rng = new java.util.Random(42)
            val samples = Array.fill(200)(100.0 + rng.nextGaussian() * 5.0)
            val result = Stats.bootstrap(samples, 10_000, 0.95)
            assert(result.ciLow < 100.0)
            assert(result.ciHigh > 100.0)
        }

        "CI narrows with more samples" in {
            val rng = new java.util.Random(42)
            val small = Array.fill(20)(100.0 + rng.nextGaussian() * 5.0)
            val large = Array.fill(2000)(100.0 + rng.nextGaussian() * 5.0)
            val ciSmall = Stats.bootstrap(small, 10_000, 0.95)
            val ciLarge = Stats.bootstrap(large, 10_000, 0.95)
            val widthSmall = ciSmall.ciHigh - ciSmall.ciLow
            val widthLarge = ciLarge.ciHigh - ciLarge.ciLow
            assert(widthLarge < widthSmall)
        }

        "percentiles are ordered" in {
            val rng = new java.util.Random(42)
            val samples = Array.fill(500)(rng.nextDouble() * 100.0)
            val result = Stats.bootstrap(samples, 10_000, 0.95)
            assert(result.percentiles("p50") <= result.percentiles("p90"))
            assert(result.percentiles("p90") <= result.percentiles("p95"))
            assert(result.percentiles("p95") <= result.percentiles("p99"))
        }

        "mean is close to actual mean" in {
            val rng = new java.util.Random(42)
            val samples = Array.fill(1000)(50.0 + rng.nextGaussian() * 2.0)
            val result = Stats.bootstrap(samples, 10_000, 0.95)
            assert(Math.abs(result.mean - 50.0) < 1.0)
        }

        "handles skewed data (GC-like)" in {
            val rng = new java.util.Random(42)
            // Most ops fast, occasional GC spikes
            val samples = Array.fill(1000) {
                if rng.nextDouble() < 0.05 then 10000.0  // GC pause
                else 100.0 + rng.nextGaussian() * 10.0   // normal op
            }
            val result = Stats.bootstrap(samples, 10_000, 0.95)
            // Median should be much lower than mean (skewed)
            assert(result.median < result.mean)
        }
    }

    // ── Outlier Detection ────────────────────────────────────

    "outlier detection" - {
        "no outliers for uniform data" in {
            val sorted = Array.tabulate(100)(i => i.toDouble)
            val report = Stats.detectOutliers(sorted)
            assert(report.mild == 0)
            assert(report.severe == 0)
        }

        "detects severe outliers" in {
            val sorted = (Array.fill(98)(100.0) :+ 0.0 :+ 10000.0).sorted
            val report = Stats.detectOutliers(sorted)
            assert(report.severe >= 1)
        }

        "detects mild outliers" in {
            val base = Array.fill(96)(100.0) ++ Array(50.0, 55.0, 150.0, 155.0)
            val sorted = base.sorted
            val report = Stats.detectOutliers(sorted)
            assert(report.mild >= 1 || report.severe >= 1)
        }

        "empty or tiny arrays report no outliers" in {
            assert(Stats.detectOutliers(Array.empty[Double]).mild == 0)
            assert(Stats.detectOutliers(Array(1.0)).mild == 0)
            assert(Stats.detectOutliers(Array(1.0, 2.0, 3.0)).mild == 0)
        }
    }
```

### 7.2 `RunnerTest.scala` — integration with short durations

```scala
package kyo.bench

class RunnerTest extends kyo.Test:

    val fastConfig = BenchConfig(
        warmupMaxDuration   = Duration.fromUnits(500, Duration.Units.Millis),
        convergenceThreshold = 0.5,  // very lenient for tests
        measurementDuration = Duration.fromUnits(500, Duration.Units.Millis),
        minSamples          = 10,
        concurrency         = 2,
        validateResults     = true,
        detectLeaks         = false  // disable in most tests to avoid flakiness
    )

    // ── Sync benchmarks ──────────────────────────────────────

    "sync benchmark produces results" in run {
        var count = 0
        val bench = Bench("counter") { count += 1; count }
        Bench.run(fastConfig)(bench).map { _ =>
            assert(count > 0)
        }
    }

    "sync benchmark with per-invocation setup" in run {
        var setupCount = 0
        val bench = Bench("sort",
            setup = () => { setupCount += 1; List(3, 1, 2) }
        ) { data => data.sorted }
        Bench.run(fastConfig)(bench).map { _ =>
            assert(setupCount > 0) // setup was called
        }
    }

    "sync benchmark with shared init/cleanup" in run {
        var inited  = false
        var cleaned = false
        val bench = Bench("resource",
            init    = () => { inited = true; "resource" },
            cleanup = (_: String) => { cleaned = true }
        ) { resource =>
            assert(resource == "resource")
            resource.length
        }
        Bench.run(fastConfig)(bench).map { _ =>
            assert(inited)
            assert(cleaned)
        }
    }

    "shared init called once, not per-invocation" in run {
        var initCount = 0
        val bench = Bench("init-once",
            init    = () => { initCount += 1; 42 },
            cleanup = (_: Int) => ()
        ) { n => n + 1 }
        Bench.run(fastConfig)(bench).map { _ =>
            assert(initCount == 1)
        }
    }

    // ── Async benchmarks ─────────────────────────────────────

    "async benchmark with callback" in run {
        var count = 0
        val bench = Bench.async("async-counter") { done =>
            count += 1
            done(count)
        }
        Bench.run(fastConfig)(bench).map { _ =>
            assert(count > 0)
        }
    }

    "async benchmark with per-invocation setup" in run {
        var setupCount = 0
        val bench = Bench.async("async-setup",
            setup = () => { setupCount += 1; 42 }
        ) { input => done =>
            done(input + 1)
        }
        Bench.run(fastConfig)(bench).map { _ =>
            assert(setupCount > 0)
        }
    }

    // ── Parameterized benchmarks ─────────────────────────────

    "parameterized benchmark runs all variants" in run {
        val seen = new java.util.concurrent.ConcurrentHashMap[Int, Boolean]()
        val bench = Bench("param", Seq(10, 100, 1000)) { size =>
            seen.put(size, true)
            List.range(0, size).sum
        }
        Bench.run(fastConfig)(bench).map { _ =>
            assert(seen.containsKey(10))
            assert(seen.containsKey(100))
            assert(seen.containsKey(1000))
        }
    }

    // ── Suite ────────────────────────────────────────────────

    "suite groups benchmarks" in run {
        var aRan = false
        var bRan = false
        val suite = Bench.suite("group")(
            Bench("a") { aRan = true; 1 },
            Bench("b") { bRan = true; 1 }
        )
        Bench.run(fastConfig)(suite).map { _ =>
            assert(aRan)
            assert(bRan)
        }
    }

    // ── Result validation ────────────────────────────────────

    "suite result validation warns on mismatch" in run {
        val config = fastConfig.copy(validateResults = true)
        val suite = Bench.suite("mismatch")(
            Bench("a") { 1 },
            Bench("b") { 2 }  // different result!
        )
        // Capture console output
        Console.withOut {
            Bench.run(config)(suite)
        }.map { (output, _) =>
            assert(output.exists(_.contains("WARNING")))
        }
    }

    "suite result validation passes for matching results" in run {
        val config = fastConfig.copy(validateResults = true)
        val suite = Bench.suite("match")(
            Bench("a") { 42 },
            Bench("b") { 42 }
        )
        Console.withOut {
            Bench.run(config)(suite)
        }.map { (output, _) =>
            assert(!output.exists(_.contains("WARNING")))
        }
    }

    // ── Thread leak detection ────────────────────────────────

    "thread leak detection warns on leaked thread" in run {
        val config = fastConfig.copy(detectLeaks = true, measurementDuration = Duration.fromUnits(200, Duration.Units.Millis))
        val bench = Bench("leaker") {
            // Intentionally leak a thread
            val t = new Thread(() => Thread.sleep(10000), "leaked-bench-thread")
            t.setDaemon(false)
            t.start()
            1
        }
        Console.withOut {
            Bench.run(config)(bench)
        }.map { (output, _) =>
            assert(output.exists(_.contains("leaked")))
        }
    } taggedAs(jvmOnly)

    // ── Blackhole ────────────────────────────────────────────

    "blackhole prevents DCE — benchmark runs at least expected ops" in run {
        var count = 0
        val bench = Bench("dce-test") {
            count += 1
            // Heavy computation that optimizer might eliminate without blackhole
            (0 until 100).foldLeft(0)(_ + _)
        }
        Bench.run(fastConfig)(bench).map { _ =>
            assert(count > 5) // Should have run many iterations
        }
    }

    // ── Deferred drop ────────────────────────────────────────

    "deferred drop - benchmark produces correct scores even with allocating workloads" in run {
        val bench = Bench("alloc") {
            // Allocate objects that would trigger GC if not deferred
            Array.fill(1000)(new Object())
        }
        Bench.run(fastConfig)(bench).map { _ =>
            succeed // Just verifying it completes without error
        }
    }
```

### 7.3 `BenchTest.scala` — end-to-end example + API surface tests

```scala
package kyo.bench

class BenchTest extends kyo.Test:

    // ── API construction ─────────────────────────────────────

    "Bench.apply creates sync benchmark" in {
        val b: Bench = Bench("test")(1 + 1)
        succeed
    }

    "Bench.async creates async benchmark" in {
        val b: Bench = Bench.async("test")(done => done(42))
        succeed
    }

    "Bench.suite creates suite" in {
        val b: Bench = Bench.suite("s")(
            Bench("a")(1),
            Bench("b")(2)
        )
        succeed
    }

    "parameterized creates multiple benchmarks" in {
        val b: Bench = Bench("p", Seq(1, 2, 3))(n => n * n)
        succeed
    }

    "all setup variants compile" in {
        // No setup
        val _: Bench = Bench("a")(1)
        // Per-invocation setup
        val _: Bench = Bench("b", setup = () => List(1, 2, 3))(_.sorted)
        // Shared init/cleanup
        val _: Bench = Bench("c", init = () => "res", cleanup = (_: String) => ())(_.length)
        // Async no setup
        val _: Bench = Bench.async("d")(done => done(1))
        // Async per-invocation
        val _: Bench = Bench.async("e", setup = () => 42)(n => done => done(n + 1))
        // Async shared
        val _: Bench = Bench.async("f", init = () => "res", cleanup = (_: String) => ())(
            r => done => done(r.length)
        )
        succeed
    }

    // ── End-to-end smoke test ────────────────────────────────

    "end-to-end: sync suite runs and prints results" in run {
        val suite = Bench.suite("e2e")(
            Bench("fast") { 1 + 1 },
            Bench("medium") { (0 until 100).sum }
        )
        val config = BenchConfig(
            warmupMaxDuration   = Duration.fromUnits(200, Duration.Units.Millis),
            convergenceThreshold = 0.5,
            measurementDuration = Duration.fromUnits(300, Duration.Units.Millis),
            minSamples          = 5,
            validateResults     = true,
            detectLeaks         = false
        )
        Bench.run(config)(suite).map(_ => succeed)
    }

    "end-to-end: async suite runs and prints results" in run {
        val suite = Bench.suite("async-e2e")(
            Bench.async("immediate") { done => done(42) },
            Bench.async("delayed") { done =>
                // Simulate async work
                val t = new Thread(() => { Thread.sleep(1); done(99) })
                t.setDaemon(true)
                t.start()
            }
        )
        val config = BenchConfig(
            warmupMaxDuration   = Duration.fromUnits(200, Duration.Units.Millis),
            convergenceThreshold = 0.5,
            measurementDuration = Duration.fromUnits(500, Duration.Units.Millis),
            minSamples          = 5,
            concurrency         = 2,
            validateResults     = false,
            detectLeaks         = false
        )
        Bench.run(config)(suite).map(_ => succeed)
    }

    // ── Profiling ─────────────────────────────────────────

    "profiling produces output file when enabled" in run {
        val tmpDir = java.io.File.createTempFile("bench-prof", "").getAbsolutePath + "-dir"
        val config = BenchConfig(
            warmupMaxDuration    = Duration.fromUnits(100, Duration.Units.Millis),
            convergenceThreshold = 0.5,
            measurementDuration  = Duration.fromUnits(200, Duration.Units.Millis),
            minSamples           = 5,
            profile              = true,
            profileDuration      = Duration.fromUnits(200, Duration.Units.Millis),
            profileOutput        = tmpDir,
            detectLeaks          = false
        )
        val bench = Bench("profiled") { (0 until 1000).sum }
        Bench.run(config)(bench).map { _ =>
            if Platform.profilerAvailable then
                val dir = java.io.File(tmpDir)
                assert(dir.exists())
                assert(dir.listFiles().exists(_.getName.contains("profiled")))
            else succeed // graceful skip
        }
    } taggedAs(jvmOnly)

    "profiling is no-op when disabled (default)" in run {
        val tmpDir = java.io.File.createTempFile("bench-noprof", "").getAbsolutePath + "-dir"
        val config = BenchConfig(
            warmupMaxDuration    = Duration.fromUnits(100, Duration.Units.Millis),
            convergenceThreshold = 0.5,
            measurementDuration  = Duration.fromUnits(200, Duration.Units.Millis),
            minSamples           = 5,
            profile              = false,  // default
            profileOutput        = tmpDir,
            detectLeaks          = false
        )
        val bench = Bench("not-profiled") { 1 + 1 }
        Bench.run(config)(bench).map { _ =>
            val dir = java.io.File(tmpDir)
            assert(!dir.exists() || dir.listFiles().isEmpty)
        }
    }

    "allocation tracking reports bytes/op" in run {
        val config = BenchConfig(
            warmupMaxDuration    = Duration.fromUnits(100, Duration.Units.Millis),
            convergenceThreshold = 0.5,
            measurementDuration  = Duration.fromUnits(200, Duration.Units.Millis),
            minSamples           = 5,
            profileAllocations   = true,
            detectLeaks          = false
        )
        val bench = Bench("allocator") { Array.fill(100)(new Object()) }
        // Just verify it runs without error — allocation numbers are platform-dependent
        Bench.run(config)(bench).map(_ => succeed)
    } taggedAs(jvmOnly)

    // ── Scalability ──────────────────────────────────────

    "scalability discovers curve for contention-free benchmark" in run {
        val config = BenchConfig(
            warmupMaxDuration    = Duration.fromUnits(200, Duration.Units.Millis),
            convergenceThreshold = 0.5,
            measurementDuration  = Duration.fromUnits(200, Duration.Units.Millis),
            minSamples           = 5,
            scalability          = true,
            detectLeaks          = false
        )
        // Pure computation — should scale well
        val bench = Bench("scalable") { (0 until 100).sum }
        Bench.run(config)(bench).map(_ => succeed)
    }

    "scalability detects contention wall for contended benchmark" in run {
        val lock = new Object()
        val config = BenchConfig(
            warmupMaxDuration    = Duration.fromUnits(200, Duration.Units.Millis),
            convergenceThreshold = 0.5,
            measurementDuration  = Duration.fromUnits(200, Duration.Units.Millis),
            minSamples           = 5,
            scalability          = true,
            detectLeaks          = false
        )
        // Heavy contention — should plateau quickly
        val bench = Bench("contended") {
            lock.synchronized { (0 until 100).sum }
        }
        Bench.run(config)(bench).map(_ => succeed)
    } taggedAs(jvmOnly)

    "scalability is no-op when disabled (default)" in run {
        val config = BenchConfig(
            warmupMaxDuration    = Duration.fromUnits(100, Duration.Units.Millis),
            convergenceThreshold = 0.5,
            measurementDuration  = Duration.fromUnits(200, Duration.Units.Millis),
            minSamples           = 5,
            scalability          = false,
            detectLeaks          = false
        )
        val bench = Bench("no-scale") { 1 + 1 }
        Bench.run(config)(bench).map(_ => succeed)
    }

    "async scalability varies fiber concurrency" in run {
        val config = BenchConfig(
            warmupMaxDuration    = Duration.fromUnits(200, Duration.Units.Millis),
            convergenceThreshold = 0.5,
            measurementDuration  = Duration.fromUnits(200, Duration.Units.Millis),
            minSamples           = 5,
            concurrency          = 2,
            scalability          = true,
            detectLeaks          = false
        )
        val bench = Bench.async("async-scalable") { done => done(42) }
        Bench.run(config)(bench).map(_ => succeed)
    }

    // ── End-to-end ───────────────────────────────────────

    "end-to-end: mixed sync and async" in run {
        val config = BenchConfig(
            warmupMaxDuration   = Duration.fromUnits(200, Duration.Units.Millis),
            convergenceThreshold = 0.5,
            measurementDuration = Duration.fromUnits(300, Duration.Units.Millis),
            minSamples          = 5,
            validateResults     = false,
            detectLeaks         = false
        )
        Bench.run(config)(
            Bench("sync-op") { List.range(0, 100).map(_ + 1) },
            Bench.async("async-op") { done => done(42) }
        ).map(_ => succeed)
    }
```

---

## Phase 8: JMH Migration Bridge — `Platform.fromClass` (JVM only)

**File: `kyo-bench/jvm/src/main/scala/kyo/bench/internal/Platform.scala`** (additions)

Reflects over a JMH benchmark class and produces `Seq[Bench]` automatically. Handles both patterns in the codebase:

### JMH features to map

| JMH Feature | kyo-bench equivalent |
|---|---|
| `@Benchmark` methods | Each becomes a `Bench` (sync — they return plain values) |
| `@Param` fields | Cartesian product → parameterized bench |
| `@Setup(Level.Trial)` methods | `init` (shared setup, called once) |
| `@Setup(Level.Iteration)` | Per-invocation `setup` |
| `@TearDown` methods | `cleanup` |
| `@State(Scope.Benchmark)` | Single shared instance |
| `Blackhole` parameter | Stripped — our runner handles DCE |
| Warmup state params (`KyoForkWarmup` etc.) | Stripped — our runner handles warmup |
| `expectedResult` (ArenaBench) | Suite result validation |

### API

```scala
// shared — Bench.scala
object Bench:
    // ... existing API ...

    // JVM: reflects over JMH class, creates benchmarks from @Benchmark methods
    // JS/Native: returns Seq.empty
    def fromClass(cls: Class[?]): Seq[Bench] = Platform.fromClass(cls)

    // JVM: scans classpath for bench classes in package
    // JS/Native: returns Seq.empty
    def fromPackage(pkg: String): Seq[Bench] = Platform.fromPackage(pkg)
```

### JVM implementation

```scala
// In Platform.scala (JVM) — additions

def fromClass(cls: Class[?]): Seq[Bench] =
    import org.openjdk.jmh.annotations.*

    // 1. Discover @Param fields → parameter space (cartesian product)
    val paramFields = cls.getDeclaredFields.filter(_.isAnnotationPresent(classOf[Param]))
    val paramSpace: Seq[Map[String, String]] =
        if paramFields.isEmpty then Seq(Map.empty)
        else
            paramFields.foldLeft(Seq(Map.empty[String, String])) { (acc, field) =>
                val values = field.getAnnotation(classOf[Param]).value()
                for combo <- acc; v <- values yield combo + (field.getName -> v)
            }

    // 2. For each param combination, create instance and run setup
    paramSpace.flatMap { params =>
        val instance = cls.getDeclaredConstructor().newInstance()

        // Set @Param fields
        params.foreach { (name, value) =>
            val field = cls.getDeclaredField(name)
            field.setAccessible(true)
            field.getType match
                case t if t == classOf[Int]     => field.setInt(instance, value.toInt)
                case t if t == classOf[Long]    => field.setLong(instance, value.toLong)
                case t if t == classOf[Double]  => field.setDouble(instance, value.toDouble)
                case t if t == classOf[String]  => field.set(instance, value)
                case _                          => field.set(instance, value)
        }

        // Run @Setup(Level.Trial) methods
        val setupMethods = cls.getMethods.filter { m =>
            m.isAnnotationPresent(classOf[Setup]) &&
            m.getAnnotation(classOf[Setup]).value() == Level.Trial
        }
        val teardownMethods = cls.getMethods.filter(_.isAnnotationPresent(classOf[TearDown]))

        setupMethods.foreach(_.invoke(instance))

        // 3. Discover @Benchmark methods
        val benchMethods = cls.getMethods.filter(_.isAnnotationPresent(classOf[Benchmark]))

        // 4. Create Bench for each @Benchmark method
        val paramSuffix =
            if params.isEmpty then ""
            else params.map((k, v) => s"$k=$v").mkString("[", ",", "]")

        val benches = benchMethods.map { method =>
            val methodParams = method.getParameterTypes
            // Filter out Blackhole and warmup state params
            val needsBlackhole = methodParams.exists(_ == classOf[org.openjdk.jmh.infra.Blackhole])
            val benchName = s"${cls.getSimpleName}.${method.getName}$paramSuffix"

            Bench(
                benchName,
                init    = () => { setupMethods.foreach(_.invoke(instance)); instance },
                cleanup = (_: Any) => teardownMethods.foreach(_.invoke(instance))
            ) { _ =>
                if needsBlackhole then
                    // Create a dummy Blackhole — result consumed by our Blackhole anyway
                    method.invoke(instance, createBlackhole())
                else if methodParams.exists(isWarmupState) then
                    // Skip warmup state params — pass null, the warmup logic is ours
                    val args = methodParams.map {
                        case p if isWarmupState(p) => null
                        case p if p == classOf[org.openjdk.jmh.infra.Blackhole] => createBlackhole()
                        case _ => null
                    }
                    method.invoke(instance, args*)
                else
                    method.invoke(instance)
            }
        }.toSeq

        // Group as suite if multiple benchmarks from same class
        if benches.size > 1 then
            Seq(Bench.suite(s"${cls.getSimpleName}$paramSuffix")(benches*))
        else benches
    }
end fromClass

def fromPackage(pkg: String): Seq[Bench] =
    // Reuse the existing Registry.findClasses pattern
    import java.nio.file.{Files, Path}
    import scala.jdk.CollectionConverters.*
    val classLoader = getClass.getClassLoader
    val path = pkg.replace('.', '/')
    val resources = classLoader.getResources(path)
    val classes = scala.collection.mutable.ArrayBuffer[Class[?]]()
    while resources.hasMoreElements do
        val url = resources.nextElement()
        Files.list(Path.of(url.toURI))
            .collect(java.util.stream.Collectors.toList())
            .asScala
            .filter(_.toString.endsWith(".class"))
            .foreach { p =>
                val className = pkg + "." + p.getFileName.toString.stripSuffix(".class")
                try
                    val cls = Class.forName(className)
                    if cls.getMethods.exists(_.isAnnotationPresent(
                        classOf[org.openjdk.jmh.annotations.Benchmark])) then
                        classes += cls
                catch case _: Exception => ()
            }
    classes.toSeq.sortBy(_.getSimpleName).flatMap(fromClass)
end fromPackage

private def isWarmupState(cls: Class[?]): Boolean =
    cls.getName.contains("Warmup")

private def createBlackhole(): org.openjdk.jmh.infra.Blackhole =
    // JMH Blackhole has a constructor that takes compiler hints
    try org.openjdk.jmh.infra.Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.")
    catch case _: Exception => null
```

### JS/Native stubs

```scala
// js/Platform.scala and native/Platform.scala — additions
def fromClass(cls: Class[?]): Seq[kyo.bench.internal.BenchDef] = Seq.empty
def fromPackage(pkg: String): Seq[kyo.bench.internal.BenchDef] = Seq.empty
```

### Usage — migrate all existing benchmarks in one line

```scala
// Run ALL existing arena benchmarks through the new runner
Bench.run()(Bench.fromPackage("kyo.bench.arena")*)

// Or a specific class
Bench.run()(Bench.fromClass(classOf[SchedulingBench])*)

// Mix old and new
Bench.run()(
    Bench.fromClass(classOf[ChunkBench]) ++
    Seq(
        Bench("new-bench") { myNewCode() }
    )
*)
```

### Tests

```scala
"fromClass discovers @Benchmark methods" in run {
    val benches = Bench.fromClass(classOf[DeepBindBench])
    assert(benches.nonEmpty)
} taggedAs(jvmOnly)

"fromClass handles @Param expansion" in run {
    val benches = Bench.fromClass(classOf[ChunkBench])
    // ChunkBench has @Param(Array("1024", "1048576")) so should expand
    assert(benches.size >= 2)
} taggedAs(jvmOnly)

"fromClass strips Blackhole parameter" in run {
    val benches = Bench.fromClass(classOf[ChunkBench])
    // Should run without error even though original takes Blackhole
    val config = BenchConfig(
        warmupMaxDuration = Duration.fromUnits(100, Duration.Units.Millis),
        convergenceThreshold = 0.5,
        measurementDuration = Duration.fromUnits(100, Duration.Units.Millis),
        minSamples = 3,
        detectLeaks = false
    )
    Bench.run(config)(benches*).map(_ => succeed)
} taggedAs(jvmOnly)

"fromClass runs @Setup before and @TearDown after" in run {
    // ChunkBench has @Setup(Level.Trial) that initializes data
    // If setup doesn't run, the benchmark will NPE
    val benches = Bench.fromClass(classOf[ChunkBench])
    assert(benches.nonEmpty)
    succeed
} taggedAs(jvmOnly)

"fromPackage discovers all arena benchmarks" in run {
    val benches = Bench.fromPackage("kyo.bench.arena")
    assert(benches.size >= 10) // there are 45+ arena bench files
} taggedAs(jvmOnly)

"fromClass returns empty on JS/Native" in {
    // This test runs on all platforms
    if kyo.internal.Platform.isJVM then succeed
    else assert(Bench.fromClass(classOf[Object]).isEmpty)
}
```

---

## Implementation Order

| Step | Files | What to do |
|------|-------|------------|
| **1** | `build.sbt`, `kyo-bench/` dir | Rename JMH module to `kyo-bench-jmh`. Create new `kyo-bench` cross-platform module with empty `shared/jvm/js/native` dirs. Verify `sbt kyo-bench/compile` succeeds (empty module). |
| **2** | `Bench.scala`, `BenchDef.scala` | Core API types. All `Bench.apply/async/suite` methods + `BenchConfig` (incl. profiling fields). No runner yet — just the data definitions. |
| **3** | `Platform.scala` (jvm/js/native), `Blackhole.scala` | Platform blackhole, thread snapshot, profiling (async-profiler/Inspector/asprof), allocation tracking. |
| **4** | `Stats.scala` | `MovingStdDev`, `percentile`, `bootstrap`, `detectOutliers`. Pure math, no Kyo effects. |
| **5** | `StatsTest.scala` | All stats tests from Phase 7.1. Run: `sbt kyo-benchJVM/testOnly *StatsTest`. |
| **6** | `Runner.scala`, `BenchResult`, `Profiler.scala`, `Scalability.scala` | Full measurement engine: warmup → measure → profile → allocations → scalability → stats → report. |
| **7** | `Report.scala` | Console table with tree formatting, ANSI colors, optional alloc column. |
| **8** | Wire `Bench.run` → `Runner.run` → `Report.print` | End-to-end pipeline. |
| **9** | `RunnerTest.scala`, `BenchTest.scala` | All runner + integration tests. Run on all platforms. |
| **10** | `Platform.fromClass`, `Platform.fromPackage` (JVM) | JMH migration bridge: reflect over `@Benchmark`/`@Param`/`@Setup`/`@TearDown`, strip `Blackhole` and warmup params. JS/Native stubs return empty. |
| **11** | Migration tests | `fromClass(ChunkBench)`, `fromClass(DeepBindBench)`, `fromPackage("kyo.bench.arena")` — verify all existing JMH benchmarks run through the new runner. |

## Key Kyo APIs Used

| API | Import | Purpose |
|-----|--------|---------|
| `Clock.stopwatch` → `.elapsed` | `kyo.Clock` | Monotonic timing, returns `Duration < Sync` |
| `Clock.nowMonotonic` | `kyo.Clock` | Raw timestamps for per-op timing, returns `Duration < Sync` |
| `Duration.toNanos`, `>=`, `+`, `-` | `kyo.Duration` | Duration arithmetic (opaque Long in nanos) |
| `Loop.whileTrue { ... }` | `kyo.kernel.Loop` | Stack-safe iteration, returns `Unit < S` |
| `Loop.repeat(n) { ... }` | `kyo.kernel.Loop` | Fixed repetition count |
| `Promise.init[E, A]` | `kyo.Fiber` | Create unresolved promise, returns `Promise[E, A] < Sync` |
| `.complete(Result.succeed(a))` | `kyo.Fiber` | Complete promise with value |
| `.get` | `kyo.Fiber` | Await promise result |
| `Fiber.initUnscoped(v)` | `kyo.Fiber` | Launch unscoped fiber for async measurement |
| `AtomicRef.init(v)` | `kyo.Atomic` | Thread-safe mutable ref for sample collection |
| `Kyo.foreach(seq)(f)` | `kyo.Kyo` | Sequential effectful iteration |
| `Sync.defer { ... }` | `kyo.Sync` | Suspend side effects |
| `Console.println(text)` | `kyo.Console` | Print report output |
| `Chunk.empty[A]`, `.append`, `.concat` | `kyo.Chunk` | Collect results and warnings |
| `Maybe.empty`, `Maybe(v)` | `kyo.Maybe` | Optional fields in BenchDef |
| `.bold`, `.yellow`, `.green` | `kyo.Ansi` | Console colors (extension methods on String) |
| `Frame` (auto-derived) | `kyo.Frame` | Implicit source location, auto-provided by compiler |

## Verification

1. `sbt kyo-bench/compile` — compiles on all 3 platforms
2. `sbt kyo-benchJVM/testOnly *StatsTest` — stats tests pass
3. `sbt kyo-benchJVM/testOnly *RunnerTest` — runner tests pass
4. `sbt kyo-benchJVM/testOnly *BenchTest` — integration tests pass
5. `sbt kyo-benchJS/test` — all tests pass on JS
6. `sbt kyo-benchNative/test` — all tests pass on Native
7. `sbt kyo-bench-jmh/compile` — existing JMH benchmarks still compile
