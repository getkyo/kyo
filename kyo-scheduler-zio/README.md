# kyo-scheduler-zio

`kyo-scheduler-zio` swaps ZIO's default executor for Kyo's adaptive scheduler so ZIO fibers run on the same work-stealing pool Kyo uses. Two entry points cover the integration: extend `KyoSchedulerZIOAppDefault` to launch a `ZIOAppDefault` whose runtime is preconfigured, or use `KyoSchedulerZIORuntime.default` directly to obtain a ready-to-use `Runtime[Any]` for embedding ZIO into an existing application. Both routes wire the Kyo scheduler in as ZIO's `Executor` and `BlockingExecutor`, so every effect (regular or blocking) executes on Kyo's pool.

Once installed the integration is invisible from the call site: existing ZIO code (`ZIO.succeed`, `.fork`, `.flatMap`, layers, services) runs unchanged, just on threads whose names start with `kyo`. The module is small by design; the central type the reader will actually name is `KyoSchedulerZIORuntime` (or, more often, the `KyoSchedulerZIOAppDefault` trait), not anything from the underlying scheduler. Sources live under `shared/`, so the module compiles for the JVM and Scala Native. There is no Scala.js target.

```scala
import kyo.*
import zio.Console

object MyApp extends KyoSchedulerZIOAppDefault:
    def run = Console.printLine("hello")
```

## Setup

The integration ships two forms, one for each way people typically reach for ZIO. New applications extend a drop-in `ZIOAppDefault` replacement; code embedding ZIO inside something larger (tests, mixed-runtime apps, libraries) consumes a process-global `Runtime[Any]` directly.

### `KyoSchedulerZIOAppDefault` (new applications)

When you would otherwise write `extends ZIOAppDefault`, write `extends KyoSchedulerZIOAppDefault` instead. Nothing else in the program changes: `run` returns the same `ZIO[Any, E, A]`, layers compose the same way, and `Console`, `Clock`, and the rest of the ZIO standard environment are available unmodified.

```scala
import kyo.*
import zio.Console
import zio.ZIO

object MyApp extends KyoSchedulerZIOAppDefault:
    def run =
        for
            _ <- Console.printLine("starting")
            n <- ZIO.succeed(40 + 2)
            _ <- Console.printLine(s"answer: $n")
        yield ()
end MyApp
```

> **Note:** `KyoSchedulerZIOAppDefault` overrides `runtime` on `ZIOAppDefault`. If a downstream subclass overrides `runtime` again, it must compose with this one (calling back into `KyoSchedulerZIORuntime.default`) or the Kyo wiring is lost.

### `KyoSchedulerZIORuntime.default` (embedding ZIO)

When ZIO is one component of a larger application (or you need a ZIO runtime from a non-ZIO call site, including a test), use `KyoSchedulerZIORuntime.default` directly. It is a `lazy val Runtime[Any]` shared process-wide.

```scala
import kyo.*
import zio.Unsafe
import zio.ZIO

val n: Int =
    Unsafe.unsafe { implicit u =>
        KyoSchedulerZIORuntime.default.unsafe.run(ZIO.succeed(40 + 2)).getOrThrow()
    }
assert(n == 42)
```

> **Caution:** `default` builds the runtime on first access inside `Unsafe.unsafe { ... }` and calls `getOrThrowFiberFailure()`. If the underlying layer fails to build, the first access throws. The instance is process-global; every caller in the JVM shares it.

When you would reach for both, prefer `KyoSchedulerZIOAppDefault` for whole-program entry points and `KyoSchedulerZIORuntime.default` for libraries, tests, and embedding scenarios. The trait is just a one-line override that delegates to the same runtime, so the two forms produce identical execution behavior.

## Verifying the integration

Threads owned by the Kyo scheduler have names that start with `kyo`. A one-line check inside any effect confirms that ZIO is actually running on the Kyo pool rather than on its default executor.

```scala
import kyo.*
import zio.Unsafe
import zio.ZIO

val installed: Boolean =
    Unsafe.unsafe { implicit u =>
        val io     = ZIO.succeed(Thread.currentThread().getName()).fork.flatMap(_.join)
        val thread = KyoSchedulerZIORuntime.default.unsafe.run(io).getOrThrow()
        thread.contains("kyo")
    }
assert(installed)
```

The same check inside a `KyoSchedulerZIOAppDefault` application looks like:

```scala
import kyo.*
import zio.ZIO

object Verify extends KyoSchedulerZIOAppDefault:
    def run =
        ZIO.succeed(Thread.currentThread().getName()).tap { thread =>
            ZIO.fail("Not using Kyo Scheduler").unless(thread.contains("kyo")) *>
                ZIO.logInfo(thread)
        }
end Verify
```

## What the bridge covers (and what it does not)

The wiring is intentionally narrow: ZIO's `Executor` and `BlockingExecutor` are both replaced with one Kyo-backed executor, and that is the entire scope of the integration. Knowing what is NOT bridged avoids surprises in tooling and capacity planning.

### Regular and blocking work share one executor

`KyoSchedulerZIORuntime.layer` installs the same `Executor` instance for both regular and blocking work via `Runtime.setExecutor(exec) ++ Runtime.setBlockingExecutor(exec)`. ZIO's usual split (a bounded compute pool plus a dedicated blocking pool) is collapsed into the single Kyo scheduler, which manages blocking work adaptively from inside the same pool.

> **Note:** ZIO's default `BlockingExecutor` is a separate cached thread pool sized to absorb long-running blocking calls. The Kyo scheduler instead detects and isolates blocking tasks dynamically inside its work-stealing pool. For most applications this is a wash or a win; if you rely on the existence of a separate blocking pool for diagnostics or thread-dump filtering, that distinction is gone.

### Metrics are not bridged

ZIO exposes per-executor metrics through `Executor.metrics(implicit unsafe: Unsafe): Option[ExecutionMetrics]`. The Kyo-backed executor returns `None`. Any tooling that reads ZIO's `ExecutionMetrics` from this executor will see no data; instead, observe the Kyo scheduler directly through its own metrics surface.

### `submit` always reports success

The executor's `submit` schedules the runnable on the Kyo scheduler and returns `true` unconditionally. The Kyo scheduler does not refuse work, so ZIO code paths that branch on `Executor.submit` returning `false` (rejection handling) will never trigger here.

### The Kyo scheduler is shared process-wide

The executor pulls `kyo.scheduler.Scheduler.get`, the process-wide singleton, so ZIO shares the pool with any Kyo code running in the same JVM. Tuning the Kyo scheduler (parallelism, thread factory) affects ZIO throughput, and conversely a heavy ZIO workload influences any Kyo computations on the same node.

> **Note:** `KyoSchedulerZIORuntime.layer` itself is `private[kyo]`. There is no public `ZLayer` for composing the Kyo executor into a custom `Runtime` you assemble yourself; the two supported entry points are `KyoSchedulerZIORuntime.default` and `KyoSchedulerZIOAppDefault`. The executor is built into ZIO's global scope (`layer.build(Scope.global)`), so it lives for the whole process and is never finalized.
