# kyo-scheduler

`kyo-scheduler` is a standalone work-stealing task scheduler for the JVM with adaptive concurrency control. You plug it in wherever you would use a `java.util.concurrent.ExecutorService` or `scala.concurrent.ExecutionContext`, and it handles the things a normal pool will not: it watches its own queuing delays and rejects new work when the system is overloaded, it samples per-thread CPU time to detect threads stuck in blocking syscalls and grows the pool to compensate, and it shrinks the pool back down when scheduling delays recover. Tasks run for a bounded time slice before being re-queued so no single task starves the others. The scheduler can be used on its own (the rest of Kyo is not required) and a single JVM-wide instance is available as `Scheduler.get`.

```scala
import kyo.scheduler.Scheduler
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

implicit val ec: ExecutionContext = Scheduler.get.asExecutionContext
val f: Future[Int]                = Future(42)
```

> **Important**: Most users should rely on Kyo's primitives built on top of the scheduler without ever using its APIs directly. Using the scheduler directly is an advanced feature.

## Getting started

The shortest useful program is "submit a `Task` to the shared scheduler". Everything else in this README extends that shape. The running example throughout is a service that handles inbound requests:

```scala
case class Request(userId: String, payload: Array[Byte])

def handle(req: Request): Unit =
    // CPU work plus a bit of I/O
    val sum = req.payload.foldLeft(0)(_ + _)
    println(s"user=${req.userId} sum=$sum")
end handle
```

### Submitting a task

The shared scheduler is `Scheduler.get`. Wrap any block of code in `Task(...)` and hand it to `schedule`:

```scala
import kyo.scheduler.Scheduler
import kyo.scheduler.Task

case class Request(userId: String, payload: Array[Byte])

def handle(req: Request): Unit =
    val sum = req.payload.foldLeft(0)(_ + _)
    println(s"user=${req.userId} sum=$sum")

val req = Request("u-42", Array[Byte](1, 2, 3, 4))
Scheduler.get.schedule(Task(handle(req)))
```

`Task.apply(=> Unit)` is the standard way to build a one-shot task. An overload takes a `Runnable` for Java interop, and a second overload takes an initial `runtime` priority value (lower runs sooner; see [Cooperative tasks and time slicing](#cooperative-tasks-and-time-slicing)).

```scala
import kyo.scheduler.Task

val fromBlock: Task = Task(println("a"))
val fromRunnable: Task = Task(new Runnable:
    def run() = println("b"))
val highPriority: Task = Task(println("c"), runtime = 0)
```

> **Note:** `Scheduler.get` is a JVM-wide singleton. Constructing a second `new Scheduler()` doubles every background thread (clock, timer, monitor, regulators) and the two instances will fight over CPU samples. Most code should use `Scheduler.get`. See [Lifecycle and ops](#lifecycle-and-ops) for the rare cases where a second instance makes sense.

### Bridging to standard executors

Many libraries want a `java.util.concurrent.Executor`, a `java.util.concurrent.ExecutorService`, or a `scala.concurrent.ExecutionContext`. The scheduler exposes adapters for each:

```scala
import kyo.scheduler.Scheduler
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

case class Request(userId: String, payload: Array[Byte])

val s = Scheduler.get

val exec                          = s.asExecutor        // java.util.concurrent.Executor
val execSv                        = s.asExecutorService // java.util.concurrent.ExecutorService
implicit val ec: ExecutionContext = s.asExecutionContext

val req = Request("u-7", Array.emptyByteArray)
val f: Future[Int] = Future {
    req.payload.length
}
```

Each adapter wraps every submitted `Runnable` in a `Task` and submits it through the same work-stealing path as `Scheduler#schedule`, so all the scheduler's properties (time-slice preemption, adaptive concurrency, blocking detection) apply to `Future`-based code.

> **Caution:** `asExecutorService` returns an `ExecutorService` whose `shutdown()`, `shutdownNow()`, `isShutdown()`, `isTerminated()`, and `awaitTermination()` are all no-ops. The only real shutdown path is `Scheduler#shutdown()` (and the scheduler cannot be restarted after that).

## Cooperative tasks and time slicing

The default `Task(=> Unit)` builder runs the body to completion and reports `Task.Done`. For longer-running work, a custom `Task` can yield back to the scheduler so other tasks can take a turn. This is the cooperative-preemption model: a worker calls `run(startMillis, clock, deadline)`, and the task returns either `Task.Done` (finished) or `Task.Preempted` (re-queue me and run other tasks first).

`Task.Result` is a `Boolean` type alias. `Preempted = true`, `Done = false`. Returning the wrong constant silently makes a completed task re-queue forever or drops a preempted task on the floor, so always use the named constants.

### A custom `Task`

The worker calls `run` with three arguments: the millisecond time it started this slice, an `InternalClock` for low-overhead time reads, and a deadline (also in millis) past which the worker would like the task to yield. A typical iterative task checks the deadline (or calls `shouldPreempt()`) on every iteration:

```scala
import kyo.scheduler.InternalClock
import kyo.scheduler.Scheduler
import kyo.scheduler.Task

case class Request(userId: String, payload: Array[Byte])

class HashRequest(req: Request) extends Task:
    private var i   = 0
    private var sum = 0
    private val n   = req.payload.length

    def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        while i < n do
            sum = sum * 31 + req.payload(i)
            i += 1
            if clock.currentMillis() >= deadline then return Task.Preempted
        end while
        println(s"user=${req.userId} hash=$sum")
        Task.Done
    end run
end HashRequest

Scheduler.get.schedule(new HashRequest(Request("u-1", Array.fill(1024)(1.toByte))))
```

> **Note:** The implicit `Task` ordering compares by accumulated runtime, lower-runtime-first. A freshly created task starts at runtime 1, so it has near-top priority. Override the second argument of `Task.apply(=> Unit, runtime: Int)` (or call `addRuntime` on a custom subclass before submission) if you need a different initial priority.

### Yield hooks

A custom `Task` has three hooks the worker uses for preemption:

- `shouldPreempt(): Boolean` is `true` when the coordinator has flagged this task to yield. Cheaper than reading `clock.currentMillis()` if you call it on a hot inner loop.
- `doPreempt(): Unit` is called by the worker to set the preemption flag. You normally do not call this from inside `run`.
- `addRuntime(v: Int): Unit` adds to the task's accumulated runtime, shifting its priority lower. The worker updates this between time slices.

```scala
import kyo.scheduler.InternalClock
import kyo.scheduler.Task

class CountUp(target: Int) extends Task:
    private var n = 0
    def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        while n < target do
            n += 1
            if shouldPreempt() then return Task.Preempted
        Task.Done
    end run
end CountUp
```

### Opting in to interruption

When a task wraps code you wrote and you know it is safe to break out of with `Thread.interrupt()`, you can let the blocking monitor (see [Adaptive concurrency and blocking compensation](#adaptive-concurrency-and-blocking-compensation)) deliver an interrupt to a worker stuck in a blocking syscall. Plain tasks default to `false` because arbitrary user code is not safe to interrupt mid-flight, so opting in is explicit per task by overriding `needsInterrupt()`:

```scala
import kyo.scheduler.InternalClock
import kyo.scheduler.Task

class Interruptible(body: => Unit) extends Task:
    override def needsInterrupt(): Boolean = true
    def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        body
        Task.Done
end Interruptible
```

The Kyo runtime overrides this on its own `IOTask` to wire interruption to fiber cancellation, so generic user-supplied tasks must opt in explicitly.

## Admission control

When more work arrives than the scheduler can run on time, queuing delay rises and every task in flight gets slower. This means the scheduler refuses work BEFORE the queue grows to OOM, INSTEAD OF accepting everything and falling over. The admission regulator probes the queue with timing tasks at a fixed interval, watches the jitter of those probes, and lowers the admission percentage (initially 100) when delays climb. Calling `Scheduler#reject*` asks "given the current percentage, should I drop this task?" and is the standard way to apply backpressure at the API boundary.

There are three rejection variants. They differ only in how they pick the sampling input that the admission percentage is compared against:

- `reject(): Boolean` draws a fresh random integer per call.
- `reject(key: String): Boolean` uses XXH32 of `key`.
- `reject(key: Int): Boolean` uses `key` directly.

### Random rejection

Use `reject()` for one-off tasks where consistent decisions across calls do not matter, and where you want a fresh decision on every retry:

```scala
import kyo.scheduler.Scheduler
import kyo.scheduler.Task

def handleAnonymous(): Unit = println("served")

if Scheduler.get.reject() then
    println("503 overloaded")
else
    Scheduler.get.schedule(Task(handleAnonymous()))
end if
```

### Keyed rejection (sticky within a rotation window)

For requests that have an identifier, `reject(key)` returns the same answer for the same key throughout a rotation window (default 60 minutes), then a new window starts and the same key may flip to admitted. This is what you want for per-user fairness: under pressure the system stably sheds the same subset of users instead of randomly punishing everyone, and the window rotation prevents any individual user from being locked out indefinitely.

```scala
import kyo.scheduler.Scheduler
import kyo.scheduler.Task

case class Request(userId: String, payload: Array[Byte])

def respond503(req: Request): Unit = println(s"503 for user=${req.userId}")
def handle(req: Request): Unit     = println(s"handled user=${req.userId}")

val req = Request("u-42", Array.emptyByteArray)

if Scheduler.get.reject(req.userId) then
    respond503(req)
else
    Scheduler.get.schedule(Task(handle(req)))
end if
```

> **Caution:** `reject(key)` is sticky within the rotation window. A naive retry loop with the same key will not get a different answer until the window rolls. If your retry budget is shorter than the window (it usually is), the retry will keep getting rejected. Use `reject()` (random) if you want a fresh decision per call, or accept the rejection and let the caller back off.

### Random vs keyed: when to use which

Both forms read the same admission percentage. Use `reject()` for traffic without a natural key, or when you specifically want each call to be an independent draw. Use `reject(key)` when related work should share a fate (a user's retries, a session's RPC calls, a batch job's sub-tasks) and when fairness across keys matters more than per-call randomness. The integer variant exists for cases where you have already computed a numeric key and want to skip the hash.

> **Caution:** The integer-keyed hash is `(key * 2147483647 * windowId).abs % 100`. If your key set is small or correlated with a multiple of 100, distribution can be uneven. Prefer the `String` variant (XXH32) for arbitrary inputs.

### Reading the current admission percentage

For diagnostics or custom load-shedding logic, the `Admission` regulator exposes the live percentage:

```scala
import kyo.scheduler.Scheduler
import kyo.scheduler.Task
import kyo.scheduler.regulator.Admission

// Construct your own admission instance for diagnostics, or read it via Status (see Monitoring).
val pct: Int = Admission.defaultConfig.collectWindow // configuration, not the live percent
// The Scheduler's live admission percentage is exposed via status().admission.admissionPercent
val live: Int = Scheduler.get.status().admission.admissionPercent
println(s"current admission = $live%")
```

The live value is also reported in `top.AdmissionStatus#admissionPercent`, which is what the monitoring section uses.

## Adaptive concurrency and blocking compensation

Unlike a fixed-size pool, which underutilizes the CPU when many workers are blocked and overloads the OS when none are, the scheduler decides how many worker threads to run based on live measurements, independent of how many tasks you submit. Two background mechanisms drive that decision:

1. The **concurrency regulator** sleeps for 1 ms on a dedicated OS thread, measures the actual wake-up delay, and feeds the jitter of those measurements through the shared regulator framework. High jitter (the OS is having trouble running threads on time) shrinks the pool; low jitter combined with high load grows it. The probe technique is similar to jHiccup.
2. The **blocking monitor** samples user-mode CPU time across all workers. A worker that has accumulated wall-clock time but no CPU time is stuck in a blocking syscall; the monitor counts those and adjusts the effective worker count up to compensate, so a pool of `coreWorkers` running threads is preserved even when several are blocked.

Both mechanisms run without any cooperation from task code, with one exception: the blocking monitor will only call `Thread.interrupt()` on tasks that opt in via `needsInterrupt()` (see [Cooperative tasks and time slicing](#cooperative-tasks-and-time-slicing)).

### Triggering an immediate scan

If you know a fiber was just cancelled and want the blocking monitor to react without waiting for its next scheduled scan, call `notifyInterrupt()`. The scheduler itself wires this for `IOTask` cancellation; you only need it if you are building external interrupt machinery on top.

```scala
import kyo.scheduler.Scheduler

Scheduler.get.notifyInterrupt()
```

> **Caution:** The concurrency regulator can grow the pool up to `maxWorkers` (default `coreWorkers * 100`). With many blocked threads this can produce hundreds of OS threads. Cap `maxWorkers` in `Scheduler.Config` if you have a hard thread budget.

> **Note:** On non-HotSpot JVMs where `com.sun.management.ThreadMXBean` is unavailable, CPU-time queries return `-1` and blocking detection silently does nothing. The scheduler still works; it just loses the blocking compensation behavior. The work-stealing pool and admission control are unaffected.

## Configuration and tuning

`Scheduler.Config` is a flat `case class` with eleven fields. The standard way to get a configured `Config` is `Scheduler.Config.default`, which reads each field from a `-Dkyo.scheduler.*` system property (with a built-in default) at first access.

> **Caution:** Every `kyo.scheduler.*` property is read exactly once into a `val` when the scheduler class loads. Setting the property afterward (including `System.setProperty` mid-run) has no effect until the process restarts, and a malformed value throws at class-load time, crashing the process before it serves any traffic.

### Scheduler-wide config

```scala
import kyo.scheduler.Scheduler

val cfg: Scheduler.Config = Scheduler.Config.default
println(cfg.coreWorkers)
println(cfg.maxWorkers)
println(cfg.timeSliceMs)
println(cfg.virtualizeWorkers)
```

The fields are:

| Field | System property | Meaning |
|---|---|---|
| `cores` | (read from `Runtime.availableProcessors`) | CPU count seen at startup |
| `coreWorkers` | `kyo.scheduler.coreWorkers` | Initial worker count |
| `minWorkers` | `kyo.scheduler.minWorkers` | Lower bound for the concurrency regulator |
| `maxWorkers` | `kyo.scheduler.maxWorkers` | Upper bound for the concurrency regulator |
| `scheduleStride` | `kyo.scheduler.scheduleStride` | Workers examined when placing a new task |
| `stealStride` | `kyo.scheduler.stealStride` | Workers examined when stealing |
| `virtualizeWorkers` | `kyo.scheduler.virtualizeWorkers` | Back workers with Loom virtual threads |
| `timeSliceMs` | `kyo.scheduler.timeSliceMs` | Max ms a task runs before yielding |
| `cycleIntervalNs` | `kyo.scheduler.cycleIntervalNs` | Worker-health check interval |
| `enableTopJMX` | `kyo.scheduler.enableTopJMX` | Register the `kyo.scheduler:type=Top` JMX bean |
| `enableTopConsoleMs` | `kyo.scheduler.enableTopConsoleMs` | If `> 0`, print `Printer`-formatted status at this interval |

> **Caution:** System-property defaults flow through `Config.default` only. If you construct `Config(...)` by hand, you bypass every system-property override.

The two regulators have their own `regulator.Config` for tuning: `collectWindow`, `collectInterval`, `regulateInterval`, `jitterUpperThreshold`, `jitterLowerThreshold`, `loadAvgTarget`, and `stepExp`. Defaults are `Admission.defaultConfig` and `Concurrency.defaultConfig`, again populated from `-Dkyo.scheduler.admission*` and `-Dkyo.scheduler.concurrency*` system properties.

```scala
import kyo.scheduler.regulator.Admission
import kyo.scheduler.regulator.Concurrency
import kyo.scheduler.regulator.Config

val admissionCfg: Config   = Admission.defaultConfig
val concurrencyCfg: Config = Concurrency.defaultConfig
println(admissionCfg.collectWindow)
println(concurrencyCfg.jitterUpperThreshold)
```

### Constructing a non-singleton scheduler

In tests or in isolated subsystems where the singleton is the wrong shape, you can build a fresh `Scheduler` with explicit executors and a custom `Config`:

```scala
import java.util.concurrent.Executors
import kyo.scheduler.Scheduler
import kyo.scheduler.util.Threads

val workerExec = Executors.newCachedThreadPool(Threads("my-worker"))
val clockExec  = Executors.newSingleThreadExecutor(Threads("my-clock"))
val timerExec  = Executors.newScheduledThreadPool(2, Threads("my-timer"))

val cfg = Scheduler.Config.default.copy(coreWorkers = 4, maxWorkers = 16)
val s   = new Scheduler(workerExec, clockExec, timerExec, cfg)
```

Re-read the warning at the top of [Getting started](#getting-started) before doing this in production code: two `Scheduler` instances in the same JVM double the regulator overhead and fight over CPU samples.

## Loom and virtual threads

When you want blocking calls inside tasks to scale further than the worker count would normally allow, set `virtualizeWorkers = true` (or `-Dkyo.scheduler.virtualizeWorkers=true`) so that the worker pool is wired as a virtual-thread scheduler. Worker threads become carrier threads for virtual threads, so that a blocking call inside a task unmounts the virtual thread instead of pinning the carrier. This is implemented in `util.LoomSupport.tryVirtualize`, which is also callable directly if you want the same effect on a custom executor:

```scala
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kyo.scheduler.util.LoomSupport
import kyo.scheduler.util.Threads

val carrier: Executor = Executors.newFixedThreadPool(4, Threads("carrier"))
val virtualized       = LoomSupport.tryVirtualize(enabled = true, exec = carrier)
virtualized.execute(() => println(s"on ${Thread.currentThread()}"))
```

> **Caution:** `virtualizeWorkers = true` silently falls back to a regular pool (with one `WARNING:` log line through `java.util.logging`) if the JVM is missing `--add-opens=java.base/java.lang=ALL-UNNAMED`. There is no programmatic signal of the fallback; check the log if it matters. Add the flag to your JVM args when you intend to use Loom.

## Monitoring (kyo-scheduler top)

When you need to see what the scheduler is doing under load (which workers are busy, how often the admission percentage is dropping, whether the concurrency regulator is growing the pool), reach for `status()`. It returns a `top.Status` snapshot: counts of current vs allocated workers, the load average, total flushes, active and total OS threads, a per-worker `Seq[WorkerStatus]`, and the two regulator statuses. Every status type defines `infix def -` so two snapshots subtract to a delta over the interval, so that comparing two timestamps gives a rate without bookkeeping. `loadAvg()` returns the load number on its own for cheap polling.

```scala
import kyo.scheduler.Scheduler

val s   = Scheduler.get
val now = s.status()
println(s"workers ${now.currentWorkers}, load ${now.loadAvg}")
println(s"admission ${now.admission.admissionPercent}%")
Thread.sleep(1000)
val later = s.status()
val delta = later - now
println(s"flushes/sec ${delta.flushes}")
```

### Rendering as a table

For an ops dashboard or a `tail -f` over the log file, you want the snapshot in a form a human can scan at a glance instead of a `case class` toString. `top.Printer.apply(status)` formats a `Status` snapshot as the "Kyo Scheduler Top" ASCII table:

```scala
import kyo.scheduler.Scheduler
import kyo.scheduler.top.Printer

val s = Scheduler.get
println(Printer(s.status()))
```

### Exposing the status via JMX

Set `enableTopJMX = true` in `Scheduler.Config` (or `-Dkyo.scheduler.enableTopJMX=true`) and the scheduler registers the `kyo.scheduler:type=Top` MBean (`top.TopMBean`) backed by `top.Reporter`. `top.Client` connects to a running JVM over RMI/JMX and streams `Status` deltas:

```scala
import kyo.scheduler.top.Client
import kyo.scheduler.top.Printer
import scala.concurrent.duration.*

Client.run(host = "localhost", port = 1099, interval = 1.second) { delta =>
    println(Printer(delta))
}
```

The `Client.run(args: List[String])` overload parses `host`, `port`, and `interval` from command-line arguments and falls back to `localhost:1099` at 1-second intervals when arguments are missing. `top.Console` is the runnable CLI built on top of it; running `kyo.scheduler.top.Console <host> <port> <intervalSeconds>` clears the screen and reprints the table on every tick.

### Periodic console dump in-process

Set `enableTopConsoleMs > 0` (`-Dkyo.scheduler.enableTopConsoleMs=<ms>`) on `Scheduler.Config` to have the scheduler itself print the `Printer`-formatted table at that interval, no external client needed. This is the cheapest path for local debugging.

## Lifecycle and ops

Two questions the scheduler answers at runtime: "can I drain pending work right now?" and "how do I stop it for good?" Those are `flush()` and `shutdown()`. The scheduler itself comes up implicitly on first reference to `Scheduler.get`, so that there is no explicit `start()` and so most code only ever touches the shutdown side.

### `flush()`

When you are inside a task and about to do something expensive (or block on external I/O) and want the rest of your worker's queue to get a chance to run first, call `flush()`. It drains and re-submits all pending tasks on the **current worker's** queue. It does nothing from a non-worker thread because it has no global queue to drain; the alternative would be to walk every worker's deque from the outside, which would invalidate the work-stealing guarantees the scheduler relies on.

```scala
import kyo.scheduler.Scheduler
import kyo.scheduler.Task

Scheduler.get.schedule(Task {
    // Inside a worker. Drain the current worker's queue before doing something expensive.
    Scheduler.get.flush()
    println("flushed and continuing")
})
```

> **Caution:** `flush()` is not a global "wait until idle" primitive. From the main thread it is a no-op. There is no built-in way to block until every worker is idle.

### `shutdown()`

When the process is about to exit (a shutdown hook, an integration-test teardown) and you want the scheduler's background threads to stop so the JVM can quit cleanly, call `shutdown()`. It cancels the worker cycle task, stops the blocking monitor, stops both regulators, and unregisters the JMX bean. It cannot be reversed because there is no `start()`. Once you shut down the singleton, the only way to get scheduling back is `new Scheduler(...)`, which (per the warning in [Getting started](#getting-started)) is generally a mistake.

```scala
import kyo.scheduler.Scheduler

// Graceful exit, e.g. in a shutdown hook
sys.addShutdownHook {
    Scheduler.get.shutdown()
}
```

### `flush()` vs `shutdown()`

`flush()` is per-worker, runs only on a worker, and is meant to drain the local queue mid-task. `shutdown()` is global, runs from anywhere, and is irreversible. They are not alternatives to each other; you use both, at different points in a process's life.

### Cross-classloader singletons

When you deploy into an application server (Tomcat, WildFly, plugin systems) that loads your code through several classloaders, the usual `object Foo` pattern gives you one `Foo` per loader, which is not what most code expects. `util.Singleton[A <: AnyRef]` is the helper that keeps a single instance JVM-wide. It stores the instance in `System.getProperties` keyed by the singleton's fully-qualified class name and synchronizes initialization on `ClassLoader.getSystemClassLoader`, so that every loader sharing that root sees the same value.

```scala
import kyo.scheduler.util.Singleton

class MyService

object MyService extends Singleton[MyService]:
    override protected def init() = new MyService

val one = MyService.get
val two = MyService.get
assert(one eq two)
```

`Scheduler` itself does not extend `Singleton` (its `get` is a plain `val`), but Kyo's wider machinery uses this helper when classloader-spanning identity matters.

> **Caution:** Stale instances may persist if the same JVM reloads code at the same fully-qualified class name. This is not safe for hot-reload tooling that re-uses classloaders aggressively.

## Cross-platform notes

### Scala.js

The JS build replaces the work-stealing pool with `MacrotaskExecutor`. `schedule(Task)` enqueues the task as a macrotask and re-enqueues it while `run` returns `Task.Preempted`. There is one thread, so there is no work stealing, no admission regulator, and no concurrency regulator. The API shape is preserved so cross-platform code compiles:

```scala
import kyo.scheduler.Scheduler
import kyo.scheduler.Task
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

val s = Scheduler.get

s.schedule(Task(println("ran")))

implicit val ec: ExecutionContext = s.asExecutionContext
val f                             = Future(42)

// These are always false / no-op on JS
val maybe: Boolean = s.reject()         // false
val ignored        = s.reject("user-1") // false
s.flush() // no-op
s.notifyInterrupt() // no-op
```

> **Caution:** `reject()`, `reject(String)`, and `reject(Int)` always return `false` on Scala.js. Cross-platform code that relies on admission decisions needs an explicit JS fallback (e.g. an in-process rate limiter).

### Scala Native

Native compiles the JVM source directly via the `jvm-native/` source tree, so the full work-stealing, admission, and concurrency-regulation surface is the same as on the JVM. The one platform-specific piece is `util.Sleep`: on POSIX it calls `nanosleep` directly to avoid the file-descriptor pressure of Scala Native's `Thread.sleep` (which would use a pipe-poll-close pattern), and on Windows it falls back to `Thread.sleep`. The concurrency regulator's jitter probe relies on this for accurate measurements.

## Extending the scheduler

`regulator.Regulator` is a public abstract base class for the built-in `Admission` and `Concurrency` regulators. It implements the closed-loop control logic (moving-jitter window, step escalation, periodic probe scheduling) and exposes two hooks a subclass fills in: `probe()` to collect a performance measurement and `update(diff: Int)` to apply a regulation adjustment. One packaging constraint applies: `Regulator` requires an `InternalTimer` to schedule its probes, and `InternalTimer` is `private[kyo]`, so a custom subclass must live in the `kyo.scheduler` package (or be driven by a timer you supply via a separate mechanism).

### Thread factories

When you build your own executor and want its threads to behave like the scheduler's own (daemon, prefixed so they show up grouped in a stack trace, numbered sequentially), use `util.Threads.apply(name)`. It returns a `ThreadFactory` that creates daemon threads named `name-1`, `name-2`, and so on. A second overload accepts a custom `Runnable => Thread` constructor for cases where you need a non-standard `Thread` subclass:

```scala
import java.util.concurrent.Executors
import kyo.scheduler.util.Threads

val tf   = Threads("my-pool")
val exec = Executors.newCachedThreadPool(tf)
```

### Self-check harness

`util.SelfCheck` ramps up synthetic clients against the scheduler until rejections cross a threshold, then reports whether the discovered capacity matches expectations for the host's CPU count. The companion object extends `App` so it runs with defaults:

```scala
import kyo.scheduler.util.SelfCheck

new SelfCheck(rejectionThreshold = 0.2, taskDurationMs = 1000).run()
```

Run `kyo.scheduler.util.SelfCheck` as a main class to use the defaults end to end.

## Putting it together

A small RPC-style request handler that uses every major surface introduced above. It pulls the shared scheduler, applies per-user keyed rejection, runs the work as a cooperatively-preempting `Task`, exposes the scheduler over JMX, prints a delta-formatted status snapshot every second, and shuts down cleanly on exit.

```scala
import kyo.scheduler.InternalClock
import kyo.scheduler.Scheduler
import kyo.scheduler.Task
import kyo.scheduler.top.Printer
import scala.concurrent.duration.*

case class Request(userId: String, payload: Array[Byte])

// 1. Build a scheduler with JMX and console reporting enabled.
val cfg = Scheduler.Config.default.copy(
    enableTopJMX = true,
    enableTopConsoleMs = 0 // we will print ourselves
)
val s = new Scheduler(config = cfg)

// 2. Cooperatively-preempting hash task.
class HashRequest(req: Request) extends Task:
    override def needsInterrupt(): Boolean = true
    private var i                          = 0
    private var h                          = 0
    def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        val n = req.payload.length
        while i < n do
            h = h * 31 + req.payload(i)
            i += 1
            if shouldPreempt() then return Task.Preempted
        end while
        println(s"hash(user=${req.userId}) = $h")
        Task.Done
    end run
end HashRequest

// 3. API boundary: per-user keyed rejection, then submit.
def submit(req: Request): Boolean =
    if s.reject(req.userId) then
        println(s"503 for user=${req.userId}")
        false
    else
        s.schedule(new HashRequest(req))
        true

// 4. Periodic monitoring printout.
val monitor = new Thread(
    () =>
        var last = s.status()
        while !Thread.currentThread().isInterrupted do
            Thread.sleep(1000)
            val now = s.status()
            println(Printer(now - last))
            last =
                now
        end while
    ,
    "monitor"
)
monitor.setDaemon(true)
monitor.start()

// 5. Drive some traffic.
for id <- 1 to 5_000 do
    val req = Request(userId = s"u-${id % 50}", payload = Array.fill(2048)(id.toByte))
    val _   = submit(req)

// 6. Graceful shutdown.
sys.addShutdownHook {
    monitor.interrupt()
    s.shutdown()
}
```

The same code compiles on Scala Native unchanged. On Scala.js, `s.reject(req.userId)` is always `false`, `Printer` and JMX are not available, and the work runs through `MacrotaskExecutor` instead of the work-stealing pool. The `Task` and `schedule` calls remain identical.
