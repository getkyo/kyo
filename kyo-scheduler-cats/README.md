# kyo-scheduler-cats

A small bridge that swaps the Cats Effect default `IORuntime` for one backed by `kyo-scheduler`. Cats Effect normally executes `IO` programs on its built-in work-stealing thread pool. This module hands both the compute and the blocking execution contexts to `kyo.scheduler.Scheduler`, while keeping Cats Effect's own scheduled-task `Scheduler` (used by `IO.sleep` and timer-driven combinators) on a dedicated two-thread `ScheduledExecutorService`.

There is no new API to learn. The reader's first call is either `import KyoSchedulerIORuntime.global` to bring an implicit runtime into scope for `unsafeRun*`, or `extends KyoSchedulerIOApp` to make an `IOApp` use the kyo-scheduler runtime automatically. The change is observable only as a thread-name shift (threads named `kyo-...`) and a different scheduling profile underneath unchanged `IO` code. JVM-only; published for Scala 3 LTS and 2.13.

```scala
import cats.effect.IO
import kyo.KyoSchedulerIORuntime.global

val name: String =
    IO.cede.map(_ => Thread.currentThread().getName).unsafeRunSync()
// name starts with "kyo-"
```

## Using kyo-scheduler with Cats Effect

The module exposes one object and one trait. They solve the same problem at two layers: ad-hoc `unsafeRun*` call sites and full `IOApp`-shaped applications. Both share a single process-wide kyo-scheduler executor; mixing them in the same process is safe and does not start a second runtime.

### KyoSchedulerIORuntime.global

When you already have code that calls `unsafeRunSync`, `unsafeRunAsync`, or otherwise picks up an implicit `IORuntime`, import `KyoSchedulerIORuntime.global` to redirect that execution onto kyo-scheduler.

```scala
import cats.effect.IO
import kyo.KyoSchedulerIORuntime.global

val program: IO[String] =
    IO.cede.map(_ => Thread.currentThread().getName)

val threadName: String = program.unsafeRunSync()
// threadName: "kyo-..."
```

> **Caution:** `global` is a `lazy val` that shadows `cats.effect.unsafe.IORuntime.global` only when explicitly imported. Without the import, `unsafeRun*` resolves the stock Cats Effect runtime and your code still runs on `io-compute-...` threads.

> **Note:** constructing this runtime reads `IORuntime.global.config`, which forces the stock global runtime to initialize once (and allocate its own pools) as a side effect. The kyo-scheduler runtime then ignores those pools and uses its own executor.

### KyoSchedulerIOApp

For applications written against `IOApp`, replace `extends IOApp` with `extends KyoSchedulerIOApp` and nothing else changes. The trait overrides `runtime` to `KyoSchedulerIORuntime.global`; `run` is defined exactly as with stock `IOApp`.

```scala
import cats.effect.ExitCode
import cats.effect.IO
import kyo.KyoSchedulerIOApp

object Main extends KyoSchedulerIOApp:
    def run(args: List[String]): IO[ExitCode] =
        IO.println(Thread.currentThread().getName).as(ExitCode.Success)
```

> **Note:** `KyoSchedulerIOApp` overrides only `runtime`. It does not override `runtimeConfig` or `computeWorkerThreadCount`, so any configuration normally exposed on `IOApp` is inherited from `IORuntime.global.config` rather than from user overrides on the app subclass.

### Choosing between them

Both entry points produce the same runtime; the choice is shape-only. Use `KyoSchedulerIOApp` when your program has a single `main` defined as an `IOApp`. Use `import KyoSchedulerIORuntime.global` when execution is scattered across libraries, tests, or scripts that summon an `IORuntime` implicitly at multiple call sites. Mixing both in one process is fine: they share the same `lazy val`, so the executor is allocated once.

## What changes underneath

Three things differ from the stock Cats Effect runtime:

1. Compute and blocking are the same executor. The `IORuntime` is built with `kyo.scheduler.Scheduler.get.asExecutionContext` passed in both the compute and the blocking slot. Cats Effect's normal split (a cached blocking pool distinct from the compute pool) is collapsed, so `IO.blocking` runs on the same scheduler as `IO.cede`.

   ```scala
import cats.effect.IO
import kyo.KyoSchedulerIORuntime.global

val blockingThread: String =
    IO.blocking(Thread.currentThread().getName).unsafeRunSync()
// blockingThread: "kyo-..."
   ```

2. Timers run on a fixed 2-thread `ScheduledExecutorService`. `IO.sleep` and other timer-driven combinators are dispatched by a dedicated `cats.effect.unsafe.Scheduler` whose thread pool size is hard-coded to 2 regardless of CPU count. High-frequency `IO.sleep` workloads share these two threads.

3. The runtime shutdown hook is a no-op. `IORuntime(...)` is constructed with `() => ()` as the shutdown action, so calling `IORuntime#shutdown()` does not stop the kyo-scheduler executor. The scheduler lives for the JVM's lifetime.
