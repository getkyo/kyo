# kyo-scheduler-finagle

A bridge that drops Kyo's adaptive scheduler into Finagle's execution model. You don't construct anything from this module. You add it to the classpath and set one JVM flag, and Finagle's `FuturePool`s and compute-intensive tasks start running on Kyo's scheduler while lightweight `Future` compositions continue to run on Finagle's default `LocalScheduler`.

The integration is wired through Finagle's `FinagleSchedulerService` service-loader hook. With the flag `-Dcom.twitter.finagle.exp.scheduler=kyo` set at JVM startup, Finagle picks up `KyoFinagleSchedulerService` from `META-INF/services`, asks it to build a scheduler, and from then on every `FuturePool` apply, every `fork`, and the server-side `ForkingSchedulerFilter` route through Kyo's load-aware worker pool. Backpressure (admission rejection under sustained overload) and Twitter `Local` propagation are handled by the bridge so existing Finagle code keeps its semantics.

JVM-only, Scala 2.13-only. Finagle is not published for Scala 3 or non-JVM platforms; the build excludes Scala 3 sources for this module.

## Enabling it

Add the dependency and pass one flag at JVM startup. Nothing in your source code changes.

```scala
// build.sbt
libraryDependencies += "io.getkyo" %% "kyo-scheduler-finagle" % "<version>"
```

```bash
java -Dcom.twitter.finagle.exp.scheduler=kyo -cp ... your.MainClass
```

Without the flag the module is inert: the class sits on the classpath but Finagle never asks for it, so no rerouting happens.

> **Note:** activation is a JVM flag, not an API call. There is no `KyoFinagleSchedulerService.install()` or equivalent. Finagle reads the flag during `com.twitter.concurrent.Scheduler` initialization, then calls into the SPI.

### How the flag finds the class

The module ships a service-loader file at `META-INF/services/com.twitter.finagle.exp.FinagleSchedulerService` containing one line:

```
kyo.scheduler.KyoFinagleSchedulerService
```

Finagle enumerates `FinagleSchedulerService` implementations through `java.util.ServiceLoader`, calls `paramsFormat` to discover what each one wants (`KyoFinagleSchedulerService` returns `"<kyo>"`), then calls `create(params)` with the tokens parsed from the flag value. The match is exact: `create(List("kyo"))` returns `Some(scheduler)`; anything else returns `None`, and the service loader silently skips this implementation.

> **Caution:** the discriminator match is exact. `-Dcom.twitter.finagle.exp.scheduler=kyo,foo` parses to `List("kyo", "foo")` and `create` returns `None`. There is no error and no log line; Finagle just falls back to its default scheduler. If the bridge does not appear to engage, check the flag value has no trailing tokens.

## What gets rerouted

The bridge is selective. Some Finagle execution paths move onto Kyo's scheduler; others stay on Finagle's `LocalScheduler` by design.

### `FuturePool` work moves to Kyo

Every `FuturePool` apply goes through Kyo's worker pool, including the named pools Finagle exposes:

```scala
import com.twitter.util.FuturePool

val a = FuturePool.unboundedPool { expensiveComputation() }
val b = FuturePool.interruptibleUnboundedPool { interruptibleWork() }
```

User-constructed `FuturePool(executor)` instances are also rerouted:

```scala
import com.twitter.util.FuturePool
import java.util.concurrent.Executors

val myPool = FuturePool(Executors.newFixedThreadPool(8))
val c      = myPool { workThatExpectsMyPool() }
```

> **Caution:** once the bridge is active, the `Executor` passed to `FuturePool(executor)` is ignored. Every `FuturePool` (including pools constructed with a custom executor) runs on Kyo's worker pool. If your code relies on a specific executor for thread affinity, isolation, or per-pool sizing, that contract no longer holds with the flag set.

### `fork` and `tryFork` move to Kyo

Code that uses the active scheduler's `fork` directly (typically through Finagle's `ForkingScheduler` API) submits work to Kyo and gets full `Local`-context propagation and interrupt forwarding:

```scala
import com.twitter.concurrent.Scheduler
import com.twitter.concurrent.ForkingScheduler
import com.twitter.util.Future

val sched = Scheduler.asInstanceOf[ForkingScheduler]
val f: Future[Int] = sched.fork { Future.value(computeOnKyo()) }
```

At fork time the bridge calls `Local.save()` and snapshots the caller's Twitter `Local` context. When the task runs on a Kyo worker, the bridge restores the captured `Local`s before calling the body and reinstates the worker's prior `Local` state in a `finally` block. Existing code that reads `Local` values inside `fork` keeps the semantics it had on Finagle's default scheduler.

The forked task also installs an interrupt handler so `Future.raise` on the returned promise propagates into the running computation. Without this, Finagle interrupts on forked work would be silently dropped.

`tryFork` is the admission-control variant. It checks Kyo's scheduler for overload before scheduling; when overloaded it returns `Future.None` instead of submitting the task:

```scala
import com.twitter.concurrent.Scheduler
import com.twitter.concurrent.ForkingScheduler
import com.twitter.util.Future

val sched              = Scheduler.asInstanceOf[ForkingScheduler]
val maybe: Future[Option[Int]] =
    sched.tryFork { Future.value(workWhenCapacityAllows()) }
```

`tryFork` returns `Future[Option[T]]` (not a thrown rejection) by design: callers branch on the `Option` to implement backpressure.

### Server-side `ForkingSchedulerFilter` uses `tryFork` for backpressure

Finagle's server stack includes `ForkingSchedulerFilter` by default. Once the bridge is active, that filter calls into `tryFork` on every accepted request. When Kyo's scheduler signals overload, the filter sees `Future.None` and rejects the request, applying backpressure at the server edge. You do not need to enable, configure, or wire anything for this; it follows from the flag.

### `blocking` flushes both queues

`Awaitable.CanAwait` calls go through the scheduler's `blocking` hook. The bridge flushes both Finagle's local queue and Kyo's worker queue before evaluating the body:

```scala
import com.twitter.util.{Await, Future}

val result: Int = Await.result(Future.value(42))
```

The flush matters when you `Await` from inside a worker that still has queued continuations: draining them first prevents deadlocks.

## What it does not change

The bridge is not a full replacement. A few surfaces stay on Finagle's `LocalScheduler`, and a few configuration knobs are no-ops.

### `Future` composition stays on Finagle

`Future` combinators (`map`, `flatMap`, `transform`, `respond`, etc.) keep running on Finagle's default `LocalScheduler`. The bridge holds onto the original Finagle scheduler in a field and forwards `submit` calls to it.

```scala
import com.twitter.util.Future

// These stages run on Finagle's LocalScheduler, not Kyo:
val composed = Future.value(1).map(_ + 1).flatMap(x => Future.value(x * 2))
```

This is intentional. `Future` composition is fine-grained and runs best on the lightweight local scheduler; moving it to Kyo would add overhead without gain. If you want a stage to run on Kyo, place it inside a `FuturePool` apply or a `fork`.

> **Note:** "Finagle now runs on Kyo" is a common misread of this integration. Only `FuturePool` work, `fork`/`tryFork`, and the server-side admission filter route through Kyo. Continuation-heavy `Future` graphs still run on `LocalScheduler`.

### `withMaxSyncConcurrency` is a no-op

The `ForkingScheduler.withMaxSyncConcurrency(concurrency, maxWaiters)` method returns the same instance unchanged. Kyo's scheduler manages concurrency adaptively from system load, so per-instance throttling parameters do not apply.

```scala
import com.twitter.concurrent.Scheduler
import com.twitter.concurrent.ForkingScheduler

val sched   = Scheduler.asInstanceOf[ForkingScheduler]
val sameRef = sched.withMaxSyncConcurrency(concurrency = 32, maxWaiters = 64)
assert(sameRef eq sched)
```

If you were relying on Finagle's static-concurrency knob to bound parallelism, that bound goes away when the bridge is active; rely on Kyo's adaptive admission instead.

### `create` parameters beyond `"kyo"` are reserved

`create(params: List[String])` accepts only `List("kyo")`. The `params` argument is documented as "currently unused but maintained for compatibility" and `paramsFormat` returns `"<kyo>"` (no tokens beyond the discriminator). Future versions may accept additional tokens; today, only the bare flag value `kyo` engages the bridge.

## Verifying the bridge is active

A short check at startup confirms Finagle resolved to `KyoFinagleSchedulerService`:

```scala
import com.twitter.concurrent.Scheduler

println(Scheduler.getClass.getName)
// kyo.scheduler.KyoFinagleSchedulerService$$anon$1 (when the flag is set)
// com.twitter.concurrent.LocalScheduler        (when the flag is missing)
```

If you see `LocalScheduler` with the flag set, the most common cause is a malformed flag value (extra tokens, typo in the discriminator); see the `Caution` under "How the flag finds the class".
