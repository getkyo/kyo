# kyo-compat

<!-- doctest:setup
```scala
import java.util.concurrent.TimeoutException
import kyo.compat.*
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

// Domain stubs used throughout examples
case class User(name: String, id: String):
    def placeholder(id: String): User = User("placeholder", id)
object User:
    def placeholder(id: String): User = User("placeholder", id)
case class Profile(user: User, followers: Int)
case class Response(body: String)
class NetworkError(msg: String) extends Exception(msg)

val id                                        = "user-42"
def fetchUser(id: String): CIO[User]          = CIO.defer(User("alice", id))
def fetchUserFromCache(id: String): CIO[User] = CIO.defer(User("alice-cached", id))
def countFollowers(id: String): CIO[Int]      = CIO.defer(42)
def slowFetch(key: String): CIO[String]       = CIO.defer(s"slow-$key")
def fastFetch(key: String): CIO[String]       = CIO.defer(s"fast-$key")
def fetch(url: String): CIO[String]           = CIO.defer(s"fetched-$url")
def query(q: String): CIO[String]             = CIO.defer(s"result-$q")
case class Request(id: String)
def process(req: Request): CIO[Response]     = CIO.defer(Response("ok"))
val req: Request                             = Request("req-1")
def wrapWithContext(e: Throwable): Throwable = new RuntimeException("wrapped", e)
type QueryResult = String
```
-->

kyo-compat lets you write a library once against the `kyo.compat.*` surface and ship it to all 5 backends. Consumers pick the runtime at deploy time (ZIO, scala.concurrent.Future, Ox, Twitter Future, or Kyo); each pulls only its own runtime's jar.

- **Overhead-free.** Every method is `inline def` and lowers at the call site to the backend's primitive. No typeclass dispatch, no adapter layer.
- **Runtime-free.** Each backend artifact depends only on its target runtime. No dependencies on other Kyo modules.
- **Uniform surface.** A consistent API for fibers, promises, channels, atomics, latches, meters, fiber-locals, time, and concurrency.
- **Preserves backend features.** ZIO `Trace`, Kyo `Frame`, fiber locals, scoped resources, and runtime stack traces flow through `CIO` unchanged.

The cross-backend computation type, `CIO[+A]`, is an opaque alias whose definition is per-backend:

| Backend        | `CIO[+A]` resolves to                            | Provided by                |
|----------------|--------------------------------------------------|----------------------------|
| Kyo            | `A < (Abort[Throwable] & Async)`                 | `kyo-compat-kyo`           |
| ZIO            | `zio.ZIO[Any, Throwable, A]`                     | `kyo-compat-zio`           |
| Future         | `LocalCtx => scala.concurrent.Future[A]`         | `kyo-compat-future`        |
| Ox             | `(Int, ox.Ox) => A`                                | `kyo-compat-ox`            |
| Twitter Future | `() => com.twitter.util.Future[A]`               | `kyo-compat-twitter-future`|

## The error channel

`CIO` has one portable failure lane: `Throwable`. That is the compatibility boundary. Backends with a first-class error channel still use it underneath, but the error type is fixed by `CIO`: the Kyo binding is `A < (Abort[Throwable] & Async)`, and the ZIO binding is `ZIO[Any, Throwable, A]`. Future, Ox, and Twitter Future also surface failures as throwables, so the shared API can offer `CIO.fail`, `.recover`, `.fold`, `.mapError`, and `.liftToTry` uniformly across all five bindings.

This means `CIO[A]` is not the portable form of a backend-specific `A < Abort[E]` or `ZIO[R, E, A]` with an arbitrary typed error `E`. If a library needs typed domain errors in its public portable API, model them as values in the success type or translate them to `Throwable` at the `CIO` boundary. When backend-specific typed recovery, partial error elimination, or defect inspection matters, use `.lower` to work with the native carrier and wrap the result with `CIO.lift` afterward. The [error handling](#error-handling) section below lists the portable operations.

Kyo and ZIO alias a backend effect type directly: `A < S` and `ZIO` are already lazy, referentially-transparent descriptions, so `CIO` inherits that. The other three carriers are functions because their runtimes are not lazy. `scala.concurrent.Future` and Twitter's `Future` are eager (constructing one starts the computation), and Ox is direct-style with no effect type. Wrapping the runtime in a cold function (`() => Future[A]`, `LocalCtx => Future[A]`, `(Int, Ox) => A`) keeps a `CIO` value a description: nothing runs until `unsafeRun` applies the function, and each application is a fresh run. So a `CIO[A]` is referentially transparent on every backend.

`CIO` exposes `.map` and `.flatMap` directly, alongside `CIO.zip`, `CIO.foreach`, `.recover`, `.fold`, and the rest. The same source compiles against every backend. A single `import kyo.compat.*` is sufficient on every backend, including Kyo.

## Example

Write a library once against `CIO`. Here a greeting is assembled from two values fetched concurrently:

```scala doctest:scope=env:greeter
import kyo.compat.*

object Greeter:
    private val fetchName: CIO[String]    = CIO.defer("kyo-compat")
    private val fetchVersion: CIO[String] = CIO.defer("1.0")

    val greeting: CIO[String] =
        CIO.zip(fetchName, fetchVersion).map {
            case (name, version) => s"hello from $name $version"
        }
end Greeter
```

`Greeter` compiles unchanged against every `kyo-compat-X` artifact. The consumer picks a backend at deploy time with one dependency. For ZIO:

```scala doctest:expect=skipped
// build.sbt
libraryDependencies += "io.getkyo" %% "kyo-compat-zio" % "<latest version>"
```

`unsafeRun` then materializes the `CIO` into a `scala.concurrent.Future`:

```scala doctest:scope=env:greeter
import scala.concurrent.Await

val greeting: scala.concurrent.Future[String] = Greeter.greeting.unsafeRun
// hello from kyo-compat 1.0
```

Linking `kyo-compat-kyo` instead runs the same `Greeter` on Kyo, `kyo-compat-future` on `scala.concurrent.Future`, and so on. `unsafeRun` returns `scala.concurrent.Future[A]` on every binding, with the backend's default global runtime (ZIO's `Runtime`, etc.) bound inside it. The Ox binding's `unsafeRun` additionally needs a `given ExecutionContext` to bridge the Ox computation onto a `Future`; the Kyo binding's needs only a `Frame`, which the compiler synthesizes at the call site; the other three take no user-supplied implicit.

For error recovery, use `.recover` anywhere in the chain:

```scala doctest:scope=env:greeter
val resilient: CIO[String] =
    Greeter.greeting.recover {
        case _ => CIO.value("hello (offline)")
    }
```


## The CIO surface

Signatures throughout this document omit `using` parameters that the compiler synthesizes at the call site. On Kyo this is `(using Frame)`; on ZIO, `(using Trace)`; on Ox, the `Ox` capability. None need to be plumbed through library code. The Future binding's public surface takes no `using`; it routes composition through `ExecutionContext.parasitic`, and `CIO.blocking` dispatches to the standard `scala.concurrent.ExecutionContext.global`.

### Constructors

`CIO.defer(thunk)` suspends a side-effecting expression. The thunk re-executes on every materialization:

```scala
val time: CIO[Long] = CIO.defer(java.lang.System.currentTimeMillis())
```

An exception escaping the thunk is caught and surfaced through the failure channel; recovery via `.recover` works as expected. `CIO.fail(e)` builds a failed `CIO`. `CIO.get` lifts a `Try[A]`. `CIO.fromScalaFuture(f)` lifts a `scala.concurrent.Future[A]`; the parameter is `inline`, so a method-call expression at the call site is re-evaluated on each materialization. On the JVM, `CIO.fromCompletionStage(cs)` is the same lift for `java.util.concurrent.CompletionStage`. `CIO.never` is a `CIO` that never completes.

### `lift`, `deferLift`, `defer`, and `value`

The rule: never `lift` side-effecting code. `lift` is identity on an already-built, pure carrier. `deferLift` suspends code that produces a backend effect. `defer` suspends code that produces a plain value. `CIO.value(a)` wraps an already-evaluated value.

`CIO.lift(effect)` wraps an already-constructed, pure backend effect value as a `CIO[A]` (a `zio.ZIO`, a Kyo `A < S`, or a finished `Future`). `CIO.deferLift { ... }` suspends side-effecting code that produces the backend effect, re-running it on every materialization; on Future the block receives an ambient `LocalCtx` and yields a `Future[A]`, on Ox an ambient `Ox` capability. `CIO.defer { thunk }` suspends code that produces a plain, non-effect value, re-running it on every materialization. `CIO.value(a)` wraps an already-evaluated value in a successful `CIO` without suspending anything. `c.lower` extracts the backend carrier back out.

```scala doctest:expect=skipped
val onZio: CIO[Int] = CIO.lift(zio.ZIO.succeed(42))
val onFut: CIO[Int] =
    CIO.deferLift { scala.concurrent.Future(42)(using scala.concurrent.ExecutionContext.parasitic) }
```

Every `C*` primitive has the same pattern; see [Primitives](#primitives).

### Error handling

Failures are not tracked in the type parameter; every backend channels them through the portable `Throwable` lane introduced above. Recovery handlers receive a `Throwable`:

```scala
val resilient: CIO[User] =
    fetchUser(id)
        .recover {
            case _: NetworkError => CIO.defer(User.placeholder(id))
            case e               => CIO.fail(e)
        }
        .orElse(fetchUserFromCache(id))
        .mapError(wrapWithContext)
```

When a domain error is part of your portable API, keep it in the success value instead of the `CIO` failure lane:

```scala
enum LoadError:
    case NotFound(id: String)
    case Disabled(id: String)

// Domain errors stay in the success value as data.
def loadUser(id: String): CIO[Result[LoadError, User]] =
    fetchUser(id)
        .map(Result.succeed(_))
        .recover {
            case _: NetworkError =>
                // The CIO failure lane recovers to a typed error value.
                CIO.value(Result.fail(LoadError.NotFound(id)))
        }
```

Use this shape when callers should handle the domain error as data. Reserve `CIO.fail` for runtime failures, interrupted work, defects collapsed by a backend, or errors you intentionally expose as `Throwable`.

The full set:

| Method                          | Returns                       | Notes                                                  |
|---------------------------------|-------------------------------|--------------------------------------------------------|
| `c.recover(h)`                  | `CIO[A]`                      | Run `h` on failure; pattern-match inside to narrow     |
| `c.orElse(that)`                | `CIO[A]`                      | Fall back to `that` on any failure                     |
| `c.mapError(f)`                 | `CIO[A]`                      | Rewrite the error value                                |
| `c.fold(onSuccess, onFail)`     | `CIO[B]`                      | Collapse both branches, each branch returns a `CIO`    |
| `c.liftToTry`                   | `CIO[Try[A]]`                 | Reify failure as `Try`; always succeeds                |
| `c.unit`                        | `CIO[Unit]`                   | Discard the success value; failure propagates          |

Kyo and ZIO additionally have a defect / panic channel that the surface does not expose (Kyo's `Abort.panic`, ZIO's `ZIO.die`). To write or inspect a defect on those backends, lower into the native runtime via `c.lower`.

### Sequencing

`c.map(f)` transforms the success value with a pure function. `c.flatMap(f)` chains another `CIO` whose construction depends on the success value:

```scala
val program: CIO[String] =
    fetchUser(id).flatMap { user =>
        countFollowers(user.id).map(n => s"${user.name} has $n followers")
    }
```

`for`-comprehensions work the same way across every backend:

```scala
def loadProfile(id: String): CIO[Profile] =
    for
        user      <- fetchUser(id)
        followers <- countFollowers(user.id)
    yield Profile(user, followers)
```

Failures short-circuit through both: a failed `c` skips `f` and propagates. To run a `CIO` and obtain a `scala.concurrent.Future[A]`, call `c.unsafeRun`; ZIO / Kyo / Future / Twitter Future bind their default global runtime internally, and only the Ox binding requires the caller to supply a `given ExecutionContext`.

### Resources

`CIO.acquireReleaseWith(acquire)(release)(use)` pairs an acquisition with a release that runs on success and failure of `use`. `CIO.ensure(cleanup)(c)` is the unparameterised sugar.

```scala
val readFile: CIO[String] =
    CIO.acquireReleaseWith(
        CIO.defer(new java.io.FileReader("data.txt"))
    )(r => CIO.defer(r.close())) { reader =>
        CIO.defer {
            val buf = new Array[Char](1024)
            val n   = reader.read(buf)
            new String(buf, 0, math.max(n, 0))
        }
    }
```

`release` returns `CIO[Unit]`. When `use` succeeds, a failing `release` propagates as `acquireReleaseWith`'s failure on every backend except Kyo (which logs the release error via `kyo.logs` and lets `use`'s value win). When `use` fails, `use`'s throwable always wins; the release failure is handled differently by each backend. Ox attaches it as a suppressed exception on `use`'s throwable; ZIO reifies it as a defect on the cause channel (via `.orDie`); Future and Twitter Future silently drop it; Kyo logs via `kyo.logs`. See [Backends](#backends) for the per-binding contract.

### Async callbacks

`CIO.async(register)` bridges a one-shot completion callback into `CIO`. `register` receives a `Try[A] => Unit`:

```scala
def fromCallback[A](api: (Try[A] => Unit) => Unit): CIO[A] =
    CIO.async { cb =>
        api {
            case Success(a) => cb(Success(a))
            case Failure(t) => cb(Failure(t))
        }
    }
```

## Time and concurrency

### Time

`CIO.sleep(d)` suspends the calling computation for `d`. `CIO.delay(d)(c)` runs `c` after a delay of `d` (sleep, then run). `CIO.now` returns the wall-clock instant; `CIO.nowMonotonic` returns a monotonic timestamp expressed as a `Duration` since some backend-defined origin (use it for *intervals*, not wall-clock time).

```scala
val late: CIO[QueryResult]       = CIO.delay(500.millis)(query("data"))
val maybeUser: CIO[Option[User]] = CIO.timeout(5.seconds)(fetchUser(id))
val mustComplete: CIO[User] =
    CIO.timeoutWithError(5.seconds)(new TimeoutException("fetch deadline"))(fetchUser(id))
```

`CIO.sleep`, `CIO.delay`, `CIO.timeout`, and `CIO.timeoutWithError` all accept `scala.concurrent.duration.FiniteDuration`. `CIO.now` returns `java.time.Instant`. `CIO.nowMonotonic` returns `FiniteDuration` (a monotonic timestamp since some backend-defined origin). Duration literals (`500L.millis`, `1.second`, etc.) come from `import scala.concurrent.duration.*`.

When a deadline expires, the returned `CIO` resolves to `None` (or fails with the supplied error). The CIO surface does not expose cancellation, so on the Future binding the inner computation keeps running orphaned even after the timeout fires. Bindings whose underlying runtime cancels naturally (Kyo, ZIO, Ox) cancel internally; Twitter Future uses `raiseWithin`, which propagates an interrupt to the inner `Future`.

### Concurrency primitives

`CIO.zip(a, b)` (and arities up to 7) runs computations in parallel and returns a tuple. A sibling failure surfaces as the zip's failure; whether the other legs are cancelled or run to completion is a binding-specific detail (Kyo / ZIO / Ox cancel via their native runtimes; Future and Twitter Future do not, and Twitter's `zip` uses `Future.join` which fails fast without raising).

`CIO.race(a, b)` returns the first leg to complete successfully. Whether the losing leg is cancelled is a binding-specific detail (Kyo / ZIO / Ox cancel via their native runtimes; Twitter Future raises a `CancellationException` on the loser via `raise`; only the Future binding lets the loser run to completion).

The full sequencing family:

| Method                                                    | Returns          | Notes                                                        |
|-----------------------------------------------------------|------------------|--------------------------------------------------------------|
| `CIO.foreach(coll, concurrency = Int.MaxValue)(f)`        | `CIO[CChunk[B]]` | Parallel map; `concurrency` caps in-flight items (unbounded by default) |
| `CIO.foreachIndexed(coll, concurrency = Int.MaxValue)(f)` | `CIO[CChunk[B]]` | Passes the element index to `f`; same concurrency semantics  |
| `CIO.foreachDiscard(coll, concurrency = Int.MaxValue)(f)` | `CIO[Unit]`      | Runs `f` for side effects; same concurrency semantics        |
| `CIO.filter(coll, concurrency = Int.MaxValue)(p)`         | `CIO[CChunk[A]]` | Concurrent predicate filtering; same concurrency semantics   |
| `CIO.collectAll(coll, concurrency = Int.MaxValue)`        | `CIO[CChunk[A]]` | Sequence over `Iterable[CIO[A]]`; same concurrency semantics |
| `CIO.collectAllDiscard(coll, concurrency = Int.MaxValue)` | `CIO[Unit]`      | Sequence and discard results; same concurrency semantics     |

All six methods accept an optional `concurrency: Int` second argument (default `Int.MaxValue`, meaning unbounded). When `concurrency == Int.MaxValue` the binding's native unbounded parallel primitive is used directly, with no semaphore and no batching. When `concurrency` is finite, the binding's native bounded variant is used; bindings without one (Future, Twitter Future) gate dispatch through a semaphore. The `coll(f)` call shape still works unchanged; `concurrency` is the second positional argument in the value list, not a separate currying group.

`CChunk[A]` is a per-backend opaque alias for the native bulk collection type (Kyo: `kyo.Chunk[A]`, ZIO: `zio.Chunk[A]`, others: `Vector[A]`). Surface helpers `.toSeq`, `.toIndexedSeq`, `.apply(i)`, `.size`, `.iterator`, `.isEmpty` are available on every backend.

`CIO.blocking { thunk }` runs a blocking thunk on a blocking-safe pool. `CIO.cede` yields the current fiber. Per-backend specifics (which of these is a no-op, which dispatches to a pool) are in [Backends](#backends).

## Primitives

Every handle wraps a backend-native primitive: an opaque alias where the backend has a suitable native type, or a small `final class` where the binding implements its own (the Future and Twitter `CChannel`/`CLatch`, the Future `CMeter`/`CLocal`). Each exposes a companion `lift` and an extension `lower` for native interop. Operations that take values are read directly off the handle (`atomic.get`, `latch.release`, `meter.run(c)`).

### Fibers

`CFiber[A]` is a running computation forked off the current execution path. `CFiber.init` does not register with any parent scope; the fiber's lifetime is the caller's responsibility.

Use `CFiber` when you want to start work concurrently, then join the result later via `fiber.get`. If you only need the results in aggregate, prefer `CIO.zip` or `CIO.foreach`, which handle lifetime automatically.

`fiber.onComplete(cb)` fires when the fiber completes naturally (success or failure). User-callback failures surface through each runtime's reporter, not through the surrounding fiber.

```scala
val concurrent: CIO[String] =
    CFiber.init(slowFetch("a")).flatMap { fiber =>
        fastFetch("b").flatMap(b => fiber.get.map(a => s"$a / $b"))
    }
```

Available operations: `CFiber.init(c): CIO[CFiber[A]]`, `fiber.get: CIO[A]`, `fiber.onComplete(cb): CIO[Unit]`.

### Promises

`CPromise[A]` is a single-shot completable cell. The producer holds a reference and calls `p.succeed(a)` or `p.fail(e)` when done; the consumer blocks on `p.get` until the result arrives. `CIO.async` packages both registration and delivery in a single expression; `CPromise` separates them so producer and consumer can live in different fibers with no shared closure.

Use `CPromise` when the producing callback may fire multiple times and you want first-wins semantics (subsequent completions are dropped), or when producer and consumer don't share a closure.

Each backend maps `CPromise` to its native single-assignment cell: `kyo.Promise`, `zio.Promise`, `scala.concurrent.Promise`, `java.util.concurrent.CompletableFuture`, or `com.twitter.util.Promise`. The `lift`/`lower` bridge is available on every backend.

Available operations: `CPromise.init[A]`, `p.succeed(a): CIO[Boolean]`, `p.fail(e): CIO[Boolean]`, `p.get: CIO[A]`, `p.poll: CIO[Option[Try[A]]]`, `p.done: CIO[Boolean]`.

### Channels

`CChannel[A]` is a bounded FIFO queue. Producers call `ch.put(a)`, which suspends when the channel is full; consumers call `ch.take`, which suspends when empty. The bounded capacity is the backpressure contract: fast producers slow down rather than OOM-ing slow consumers.

Use a channel when two concurrent fibers need to hand off a stream of values, e.g. a producer/consumer pipeline or a work queue. `CIO.zip` combines exactly two concurrent results; channels scale to any number of producers and consumers and continue across many rounds of data.

Kyo, ZIO, and Ox map `CChannel` to a bounded queue: `kyo.Channel`, `zio.Queue`, or `java.util.concurrent.LinkedBlockingQueue` (Ox). The Future and Twitter Future bindings implement `CChannel` as a plain `final class`: Future holds three `ConcurrentLinkedQueue`s (items, takers, putters) plus an `AtomicInteger` size counter, so `put`/`take` suspension reuses the binding's `Promise`-based wait machinery; Twitter combines `com.twitter.concurrent.AsyncSemaphore` (capacity bound) with `com.twitter.concurrent.AsyncQueue` (FIFO buffer). Neither blocks a thread. The surface intentionally omits `close`, `closed`, `size`, and `offer`, because their semantics differ enough across backends that a portable abstraction would swallow real differences.

```scala
val pipeline: CIO[Int] =
    CChannel.init[Int](capacity = 64).flatMap { ch =>
        val producer = CIO.foreachDiscard(1 to 100)(ch.put)
        val consumer = CIO.foreach(1 to 100)(_ => ch.take).map(_.toSeq.sum)
        CIO.zip(producer, consumer).map(_._2)
    }
```

Available operations: `CChannel.init[A](capacity)`, `ch.put(a): CIO[Unit]`, `ch.take: CIO[A]`, `ch.poll: CIO[Option[A]]`.

### Atomics

`CAtomicRef[A]`, `CAtomicInt`, `CAtomicLong`, and `CAtomicBoolean` are atomic mutable cells for lock-free coordination between concurrent fibers. The operations match the `java.util.concurrent.atomic` contract: reads, writes, compare-and-swap, and numeric increments all execute without holding a lock.

Use atomics for shared counters, flags, or lightweight references that multiple fibers need to update without a full mutex. For richer consistency requirements (transactions across multiple cells, STM) reach for backend-specific facilities via `lower`.

Each backend maps to its native atomic type: `kyo.AtomicRef/Int/Long/Boolean`, `zio.Ref` per type, or `java.util.concurrent.atomic.*` (Future, Ox, Twitter Future).

```scala
val total: CIO[Int] =
    CAtomicInt.init(0).flatMap { counter =>
        CIO.foreachDiscard(1 to 100)(_ => counter.incrementAndGet)
            .flatMap(_ => counter.get)
    }
```

Available operations:
- `CAtomicRef[A]`: `init`, `get`, `set`, `getAndSet`, `compareAndSet`, `getAndUpdate`, `updateAndGet`.
- `CAtomicInt` / `CAtomicLong`: `init`, `get`, `set`, `getAndSet`, `compareAndSet`, plus `incrementAndGet`, `getAndIncrement`, `decrementAndGet`, `getAndDecrement`, `addAndGet`, `getAndAdd`.
- `CAtomicBoolean`: `init`, `get`, `set`, `getAndSet`, `compareAndSet`.

### Local values

`CLocal[A]` is a value scoped to the current fiber/computation, used for per-request context (request IDs, tracing spans, deadlines) that should propagate through async boundaries without being threaded through every function signature. It is the portable equivalent of Java's `ThreadLocal`, but async-safe: each fiber gets its own copy, and when you fork, the child inherits the parent's snapshot.

Use `local.let(v)(body)` to install a value for the duration of `body`, then automatically revert. Use `local.get` to read the current fiber's value. The `update(f)` variant composes both: it reads the current value, applies `f`, and installs the result for the body.

`CLocal.init(default)` returns `CIO[CLocal[A]]`. Construction is deferred to effect-evaluation time so each call allocates a fresh local. Each backend maps to a fiber-local mechanism: Kyo `Local`, ZIO `FiberRef`, Ox `ox.ForkLocal` (backed by JDK `ScopedValue`), Twitter Future's `com.twitter.util.Local`, or, on the Future binding, an immutable `LocalCtx` map threaded through the `LocalCtx => Future[A]` carrier.

```scala
val program: CIO[Response] =
    CLocal.init("anonymous").flatMap { requestId =>
        requestId.let(req.id)(process(req))
    }
```

Per-backend propagation notes:

- **Kyo**, **ZIO**: native fiber-local mechanism (`Local`, `FiberRef`).
- **Twitter Future**: `com.twitter.util.Local`; the Twitter scheduler propagates snapshots across async boundaries automatically. `CLocal` is a `(Local[A], A)` pair (the `Local` plus its configured default), and `lift`/`lower` operate on that pair.
- **scala.concurrent.Future**: an immutable `LocalCtx` (a `Map[Any, Any]`) is the carrier of every `CIO`: `CIO[+A] = LocalCtx => Future[A]`. `let(v)(body)` constructs an updated `LocalCtx` keyed by the local's identity and runs `body` with it; `get` reads from the ctx. Because the ctx is threaded through the carrier rather than stored in a thread-local, propagation is independent of the `ExecutionContext` used for `Future` execution.
- **Ox**: an `ox.ForkLocal[A]` backed by JDK `ScopedValue`. `let(v)(body)` opens a `scopedWhere` scope; all forks inside that scope inherit the value. After the scope exits, the value reverts to the default.

Available operations: `CLocal.init(default): CIO[CLocal[A]]`, `local.get: CIO[A]`, `local.let(v)(c): CIO[A]`, `local.update(f)(c): CIO[A]`.

### Latches, meters

These are coordination primitives for multi-fiber rendezvous. Skip on first read; reach for them when several concurrent computations need to synchronise on a shared rendezvous point.

`CLatch` is a count-down latch: a one-shot counter that starts at `n`, decrements on each `release`, and unblocks every `await` once the counter reaches zero. It is the right primitive for "wait until N concurrent jobs have all signalled completion." Kyo and ZIO map to a native countdown (`kyo.Latch`, `zio.concurrent.CountdownLatch`); Ox uses `java.util.concurrent.CountDownLatch`. The Future and Twitter Future bindings implement `CLatch` as a plain `final class` over a `Promise` (resolved when the counter hits zero), so `await` composes with the binding's future type without blocking a thread. `CLatch.init(n)` normalizes `n <= 0` to "already released"; `await` returns immediately and does not throw.

```scala
def waitForAll(jobs: Seq[CIO[Unit]]): CIO[Unit] =
    CLatch.init(jobs.size).flatMap { latch =>
        CIO.foreachDiscard(jobs) { job =>
            CFiber.init(job.fold(_ => latch.release, _ => latch.release))
        }.flatMap(_ => latch.await)
    }
```

Available operations: `CLatch.init(n)`, `latch.release: CIO[Unit]`, `latch.await: CIO[Unit]`.

`CMeter` is a counting semaphore: a pool of `n` permits used to cap the number of concurrent operations. `meter.run(c)` acquires one permit, runs `c`, and releases on completion (success or failure). Use it for bounded parallelism: `CIO.foreach(urls)(u => meter.run(fetch(u)))` caps in-flight fetches at `n` regardless of how many URLs are passed. Kyo and ZIO map to `kyo.Meter` and `zio.Semaphore`; Ox uses `java.util.concurrent.Semaphore`; Twitter Future uses `com.twitter.concurrent.AsyncSemaphore`. The Future binding implements `CMeter` as a plain `final class` (an `AtomicInteger` permit count plus a `Promise` waiter queue), so `run` never blocks a thread.

```scala
def fetchBounded(urls: Seq[String]): CIO[CChunk[String]] =
    CMeter.init(permits = 8).flatMap(meter => CIO.foreach(urls)(u => meter.run(fetch(u))))
```

Available operations: `CMeter.init(permits)`, `meter.run(c): CIO[A]`, `meter.tryRun(c): CIO[Option[A]]`, `meter.availablePermits: CIO[Int]`.

## Streams

`CStream[+A]` is a portable stream type alongside `CIO`. A library targeting the streams surface compiles unchanged against every binding; each backend wraps a native stream type where one exists, or supplies a hand-rolled implementation where the ecosystem lacks one.

| Backend        | `CStream[+A]` resolves to                            |
|----------------|------------------------------------------------------|
| Kyo            | `kyo.Stream[A, Abort[Throwable] & Async]`            |
| ZIO            | `zio.stream.ZStream[Any, Throwable, A]`              |
| Ox             | `ox.Ox ?=> ox.flow.Flow[A]`                          |
| Twitter Future | `com.twitter.concurrent.AsyncStream[A]`              |
| Future         | `LocalCtx => scala.concurrent.Future[Repr[A]]`       |

Platform footprints match the existing CIO surface: Kyo and ZIO ship JVM / JS / Native; Ox and Twitter Future are JVM-only; Future ships JVM / JS / Native.

The kyo-named API tracks `kyo.Stream`: constructors `empty`, `init(seq)`, `init(c: CIO[Seq[A]])`, `range`, `unfold`; transforms `concat`, `mapPure` / `map`, `flatMap`, `tap`, `take`, `drop`, `takeWhilePure`, `filterPure` / `filter`, `collectPure`; and terminals `run: CIO[CChunk[A]]`, `foldPure`, `foreach`, `discard`. The pure/effectful split (`mapPure` vs. `map`, `filterPure` vs. `filter`, `foldPure`, `collectPure`, `takeWhilePure`) tracks the kyo convention; effectful variants take `A => CIO[B]`.

On the four bindings that wrap a native stream type (Kyo, ZIO, Ox, Twitter Future), every method is an `inline def` that compiles to a single native call, with at most a trivial type adapter (`Option ⇆ Maybe`, `n.toLong` for ZIO long-arity takes/drops, `Function.unlift` for partial-function collects). The Twitter binding's `unfold` is the only exception on those four: `AsyncStream` ships no native unfold, so the wrap is a small recursive helper built on `AsyncStream.mk(head, => tail)`. The Future binding is the only fully hand-rolled implementation: `scala.concurrent.Future` has no canonical async stream, so the binding supplies a cons-stream where `Repr[A]` is a binding-private ADT (`Empty | Cons(head, tail: LocalCtx => Future[Repr[A]])`) matching the `CIO` carrier shape. Transformations build cons cells with lazy tails; terminal walks use a nested `@tailrec def loop` so 100000-element sync-completed streams don't blow the stack.

```scala
import kyo.compat.*

def doubled: CStream[Int] = CStream.init(Seq(1, 2, 3)).mapPure(_ * 2)
def sum: CIO[Int]         = doubled.foldPure(0)(_ + _)
```

This compiles and runs against every binding × supported platform.

Constructors and terminals not in the surface compose from what is:

- Failure stream: `CStream.init(CIO.fail(e))` (`init(c: CIO[Seq[A]])` propagates `c`'s failure).
- Count: `s.foldPure(0L)((c, _) => c + 1L)`.

### Known divergences (kyo binding)

Three tests in `CStreamTest` are marked `pending` because they expose limitations
of `kyo.Stream` that the other five bindings (zio, ce, ox, twitter-future, future)
don't have. They are kept in the suite as the cross-binding contract; removing the
`pending` markers once `kyo.Stream` is fixed will turn them green on every binding.

- **Chunked-eager effectful map.** `kyo.Stream.map(f: A => B < S)` applies `f` eagerly
  across each upstream chunk before emitting downstream. So
  `init(largeSeq).map(effect).take(n)` runs `effect` once per upstream chunk element
  on the kyo binding, not once per consumed element. Tests: `take(n) on an effectful
  upstream invokes the upstream effect exactly n times` and `takeWhilePure stops
  invoking upstream effects after the first false`.
- **Deep flatMap stack safety.** `kyo.Stream` stack-overflows on a 10000-deep
  flatMap chain (`Stream.handleLoopLoop` recursion). The shallower 1000-deep test
  passes everywhere. Test: `deep flatMap chains do not stack-overflow (10000
  levels)`.

## Backends

The Kyo and ZIO backends are predominantly thin redirects to their host runtime: each operation lowers to one or two calls into `Sync.defer`/`Abort.fail`/`Async.race` (Kyo) or `ZIO.attempt`/`Promise.await`/`Fiber.Runtime` (ZIO). Fiber-locals, scoped resources, and tracing all work as they would in hand-written code.

### Future

The carrier is `LocalCtx => Future[A]`, a function from an immutable `LocalCtx` (the fiber-local context threaded through every combinator) to a fresh `Future`. The plain function arrow keeps the body lazy, so each materialization re-runs it. `CLocal.let`/`get`/`update` thread the ctx through the carrier; no `ExecutionContext` smuggling or `ThreadLocal` capture is involved.

- The Future binding has no cancellation. `scala.concurrent.Future` exposes none, and the CIO surface does not either; `CIO.timeout` returns `None` on expiry but the inner computation keeps running orphaned, and `CIO.race` returns the winner while the loser runs to completion.
- `release` in `CIO.acquireReleaseWith` runs on success and failure of `use`; failures propagate as `acquireReleaseWith`'s failed Future.
- `CIO.never` blocks the calling fiber for the lifetime of the process.
- `CIO.blocking { thunk }` runs the thunk inside `scala.concurrent.blocking` on `scala.concurrent.ExecutionContext.global`. `CIO.cede` schedules a zero-delay task through `CompatScheduler`, forcing a scheduling round-trip.
- `fiber.onComplete(cb)` fires when the underlying `Future` completes naturally (success or failure).
- Call `c.unsafeRun` to materialize a `CIO` into a `scala.concurrent.Future[A]`.

### Ox

The carrier is `(Int, Ox) => A`: a function over Ox's structured-concurrency capability, plus an `Int` carrying the current `flatMap` nesting depth. The computation is applied at the boundary inside an `ox.supervised:` block. As with Future, the plain function arrow preserves cold-laziness.

- The eventual boundary call where the consumer runs the computation must live inside an `ox.supervised:` block.
- `fromScalaFuture(f)` blocks the calling thread via `Await.result(f, Duration.Inf)`; Ox is direct-style, and blocking is the native idiom.
- `CFiber.init` uses `ox.forkCancellable`; the fork's lifetime is bounded by the surrounding `ox.supervised:` block.
- `release` failures in `CIO.acquireReleaseWith` propagate synchronously through a `try`/`catch` shell: on `use`-success + `release`-failure, the release throwable propagates; on `use`-failure + `release`-failure, the release throwable is attached to `use`'s via `addSuppressed`.
- `fiber.onComplete(cb)` fires with `Failure(t)` when the fiber's body fails; the observer runs on a daemon thread independent of the surrounding `ox.supervised` scope.
- `CIO.cede` forks an unsupervised task and immediately joins it (`ox.forkUnsupervised(()).join()`), giving the surrounding scope an opportunity to schedule another fork. `CIO.blocking { thunk }` wraps the thunk in `scala.concurrent.blocking { ... }` so the ForkJoinPool managing the calling thread can spawn a replacement worker.
- **Stack safety.** `flatMap` threads the carrier's depth `Int`; once it reaches a fixed limit the remaining chain re-runs inside a fresh `ox.forkUnsupervised`, a new virtual-thread stack. Deep `flatMap` chains (1000+ levels) do not stack-overflow; `cede` additionally introduces an async boundary that resets the physical stack.
- Call `c.unsafeRun` (with an `ExecutionContext` in scope) to drive the `CIO` and obtain a `scala.concurrent.Future[A]`; await it with `Await.result(c.unsafeRun, deadline)` when synchronous code is needed.

### Twitter Future

The carrier is `() => com.twitter.util.Future[A]`, a cold thunk. JVM-only.

- `CLocal[A]` uses `com.twitter.util.Local[A]`, which propagates across async boundaries via the Twitter scheduler.
- `release` failures in `CIO.acquireReleaseWith` propagate as `acquireReleaseWith`'s `Throw(t)`.
- `CIO.race(a, b)` is hand-rolled with a `Promise[A]` and `respond` callbacks; the losing leg is interrupted via `raise(CancellationException)`, but whether the inner computation reacts is up to whoever wired the source's `setInterruptHandler`. `CIO.timeout` uses `Future.raiseWithin`, which propagates the same `raise` signal on expiry.
- `acquireReleaseWith`, `timeout`, and `race` are hand-rolled; Twitter Future has no native acquire-release primitive.
- `CIO.cede` schedules a "run now" task via `twitterTimer.doLater(Duration.Zero)`, a real async boundary that lets the current fiber suspend (`Future.sleep(Zero)` would short-circuit to an already-completed future and not yield). `CIO.blocking { thunk }` runs on `FuturePool.unboundedPool`.
- Call `c.unsafeRun` to obtain a `scala.concurrent.Future[A]` bridged from the underlying `com.twitter.util.Future[A]`; await with `Await.result(c.unsafeRun, deadline)` for synchronous results.

### Kyo

The carrier is `A < (Abort[Throwable] & Async)`. `CIO.acquireReleaseWith` wraps `Scope.acquireRelease` inside a `Scope.run { ... }` so the Scope capability is eliminated at the `acquireReleaseWith` boundary and never appears in the carrier. The kyo runtime guarantees release on every exit path. A failing release is reported by the kyo scope-finalizer logger (`kyo.logs`); `use`'s value still wins. This deviates from the unified `acquireReleaseWith` contract on the other five backends, which surface release failures synchronously through `acquireReleaseWith`'s failure channel; on kyo, subscribe to `kyo.logs` to observe scope-finalizer errors. `fiber.onComplete(cb)` fires when the fiber completes. `CIO.cede` is `Sync.defer(())` (Kyo's scheduler is preemptive); `CIO.blocking { thunk }` is `Sync.defer(thunk)` (Kyo's scheduler auto-detects blocking).

### ZIO

The carrier is `ZIO[Any, Throwable, A]`. `CIO.defer` lowers to `ZIO.attempt`; escaped exceptions land in the typed `Throwable` error channel. `CIO.acquireReleaseWith` reifies a release Throwable as a defect via `.orDie` so it propagates through ZIO's cause channel. `fiber.onComplete(cb)` fires with `Failure(cause.squash)` for non-success exits; ZIO's `onComplete` matches `Exit.Failure` for every non-success outcome. `CIO.cede` is `ZIO.yieldNow`; `CIO.blocking { thunk }` is `ZIO.attemptBlocking`.

### Native carrier types

| Handle                                                            | Kyo                                          | ZIO                                  | Future                                            | Ox                                              | Twitter Future                          |
|-------------------------------------------------------------------|----------------------------------------------|--------------------------------------|---------------------------------------------------|-------------------------------------------------|-----------------------------------------|
| `CFiber[A]`                                                       | `kyo.Fiber[A, Abort[Throwable]]`             | `zio.Fiber.Runtime[Throwable, A]`    | `scala.concurrent.Future[A]`                      | `ox.CancellableFork[A]`                         | `com.twitter.util.Future[A]`            |
| `CPromise[A]`                                                     | `kyo.Promise[A, Abort[Throwable]]`           | `zio.Promise[Throwable, A]`          | `scala.concurrent.Promise[A]`                     | `java.util.concurrent.CompletableFuture[A]`     | `com.twitter.util.Promise[A]`           |
| `CChannel[A]`                                                     | `kyo.Channel[A]`                             | `zio.Queue[A]`                       | `final class CChannel` (3 CLQs + AtomicInteger)    | `java.util.concurrent.LinkedBlockingQueue[A]`   | `final class CChannel` (`AsyncSemaphore` + `AsyncQueue`) |
| `CAtomicRef[A]` / `CAtomicInt` / `CAtomicLong` / `CAtomicBoolean` | `kyo.AtomicRef[A]` / `AtomicInt` / `AtomicLong` / `AtomicBoolean` | `zio.Ref[T]` per type | `java.util.concurrent.atomic.*` per type          | same as Future                                  | same as Future                          |
| `CLatch`                                                          | `kyo.Latch`                                  | `zio.concurrent.CountdownLatch`      | `final class CLatch` (Promise-queue)              | `java.util.concurrent.CountDownLatch`           | `final class CLatch` (`Promise[Unit]` + `AtomicInteger`) |
| `CMeter`                                                          | `kyo.Meter`                                  | `zio.Semaphore`                      | `final class CMeter` (permits + waiter queue)     | `java.util.concurrent.Semaphore`                | `com.twitter.concurrent.AsyncSemaphore`                  |
| `CLocal[A]`                                                       | `kyo.Local[A]`                               | `zio.FiberRef[A]`                    | identity-keyed lookup in the threaded `LocalCtx` map | `ox.ForkLocal[A]` (JDK `ScopedValue`) | `(com.twitter.util.Local[A], A)`        |

### `fromScalaFuture` / `fromCompletionStage`

`CIO.fromScalaFuture(f)` ships on every backend; `CIO.fromCompletionStage(cs)` is JVM-only. Both observe their source's eventual completion. The CIO surface does not expose cancellation, so the source future / completion stage is not cancelled by the consumer.

## Beyond the surface

`CIO` covers operations every backend can express uniformly. The cases below either differ in expressive power across backends or are backend-specific extensions; reach them via `c.lower` (which yields the underlying carrier) and re-wrap with `CIO.lift(...)` if a `CIO` is needed back.

- **Partial error recovery.** `recover`, `fold`, `mapError`, `orElse` on `CIO` are total over `Throwable`. Backend-specific facilities (Kyo's `Abort.recover[A]` returning `Abort[B | C]` with a branch removed at the type level, ZIO's `catchSome`/`refineToOrDie`, Ox's `try`/`catch`) are reached via `lower`.
- **Defect channels.** Only Kyo (`Abort.panic`, `Result.Panic`) and ZIO (`ZIO.die`, `Cause.Die`) separate defects from typed failures. The other backends collapse defects into the typed channel. To write or inspect a defect, use the native API per backend.
- **Resource models.** `CIO.acquireReleaseWith` covers the lexical acquire-release case. Backend-specific resource types (Kyo's `Scope`, ZIO's `Scope`, Ox's `useCloseableInScope`) have no cross-backend counterpart.

## How to publish a kyo-compat library

### Getting started

Requires Scala 3. Add `kyo-compat-future` as your local dev dependency. It has no third-party transitive deps, so the compile and IDE loop is fast:

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-compat-future" % "<latest version>"
```

```scala
import kyo.compat.*
```

Write your library against `CIO`, `CFiber`, `CPromise`, and the rest of the `kyo.compat.*` surface. At dev time you compile against a single backend; the same source will compile against all five.

### Cross-publishing with the sbt plugin

The bundled `kyo-compat-plugin` sbt plugin extends `sbt-projectmatrix`'s `ProjectMatrix` builder with a terminal `.compatLibrary(extras*)(platforms*)(scalaVersions)` method. Each call adds one row per `(backend × supported-platform × scala-version)` to the matrix, generating the per-backend cells your library needs to ship to every runtime from a single source tree.

#### Setup

```scala doctest:expect=skipped
// project/plugins.sbt
addSbtPlugin("io.getkyo"          % "kyo-compat-plugin"             % "<latest version>")
addSbtPlugin("com.eed3si9n"       % "sbt-projectmatrix"             % "0.10.1")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.3.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % "1.20.2")
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.10")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
```

```scala doctest:expect=skipped
// build.sbt
import sbt.VirtualAxis

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "<latest version>"

lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        organization := "com.example",
        version      := "1.0.0"
    )
    .compatLibrary(KyoLib, ZioLib, OxLib)(
        VirtualAxis.jvm,
        VirtualAxis.js,
        VirtualAxis.native
    )(Seq("3.3.4"))
```

`compatKyoVersion` overrides the version used in the per-row `libraryDependencies += "io.getkyo" %%% s"kyo-compat-<id>" % compatKyoVersion.value` line that the plugin injects. It defaults to the plugin's own `Implementation-Version`, so a clean install with no override pulls the matching backend artifacts. Pin it explicitly only when targeting a snapshot that doesn't match the plugin build.

That single declaration is enough; sbt auto-discovers every per-(backend, platform) cell because `ProjectMatrix` is itself a `CompositeProject`. From the snippet above sbt sees these cells:

```
myLibFuture          myLibKyo          myLibZio          myLibOx
                     myLibKyoJS        myLibZioJS
                     myLibKyoNative    myLibZioNative
```

The JVM and Scala suffixes are suppressed because `compatLibrary` pins the matrix's `defaultAxes` to `(VirtualAxis.jvm, VirtualAxis.scalaVersionAxis(sv, sv))` for single-Scala matrices. Pass multiple Scala versions in the third tuple to cross-build; each row gets its own `scalaVersionAxis` so target dirs and project ids stay distinct.

Each cell's `baseDirectory` is the per-(backend, platform) source root (`my-lib/<backend>/<platform>/`), not the project root. Tests and IO inside a cell resolve relative paths against that root: the usual reason a port from a flat `crossProject` setup needs file-path adjustments.

#### Selecting backends

`Future` is the deps-free dev anchor and is always generated, regardless of the `extras*` list. The varargs are the *additional* backends to cross-publish to:

| Call | Generated backends |
|------|--------------------|
| `.compatLibrary()(VirtualAxis.jvm)(Seq("3.3.4"))` | Future only |
| `.compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))` | Future + Kyo |
| `.compatLibrary(KyoLib, ZioLib, OxLib)(...)` | Future + Kyo + ZIO + Ox |
| `.compatLibrary(KyoLib, ZioLib, OxLib, TwitterFutureLib)(...)` | All five |

Each backend's `supportedPlatforms` is intersected with the user-requested `platforms` list:

| Backend          | JVM | JS  | Native |
|------------------|-----|-----|--------|
| `Future`         | ✅ | ❌ | ❌ |
| `Kyo`            | ✅ | ✅ | ✅ |
| `Zio`            | ✅ | ✅ | ✅ |
| `Ox`             | ✅ | ❌ | ❌ |
| `TwitterFuture`  | ✅ | ❌ | ❌ |

Cells the backend cannot support are silently skipped. An empty intersection for any explicitly-requested backend (e.g. `.compatLibrary(OxLib)(VirtualAxis.js)(Seq("3.3.4"))`) errors at build-load with a clear message: widen the requested `platforms` list or drop the backend from the `extras*` list.

The four optional backends are exported from `CompatPlugin.autoImport` as `KyoLib`, `ZioLib`, `OxLib`, `TwitterFutureLib`. Each is a `CompatBackendAxis` extending `VirtualAxis.WeakAxis`. The `Lib` suffix avoids collisions with `scala.concurrent.Future` and the `kyo` package object that consumers commonly have in scope. `FutureLib` exists too for explicit reference (used by `bindLocally(FutureLib, ...)`).

#### Source layout and per-platform settings

Library code lives at `my-lib/shared/src/main/scala/...` and tests at `my-lib/shared/src/test/scala/...`. Three additional source roots are picked up automatically:

| Path | When to use it |
|------|----------------|
| `my-lib/shared/src/{main,test}/scala` | Cross-backend, cross-platform code (the default) |
| `my-lib/<backend>/src/{main,test}/scala` | Backend-specific code shared across all of that backend's platforms (e.g. Kyo helpers used by both `myLibKyo` and `myLibKyoJS`) |
| `my-lib/<backend>/<platform>/src/{main,test}/scala` | One specific (backend, platform) cell |

`<backend>` is `future` / `kyo` / `zio` / `ox` / `twitter-future`; `<platform>` is `jvm` / `js` / `native`. Resources mirror the same layout under `src/{main,test}/resources`. Each cell pulls its own `kyo-compat-<backend>` artifact. Running `sbt myLibZio/Test/test` and `sbt myLibFuture/Test/test` exercises the same source against two runtimes.

For per-platform settings, `compatLibrary(...)` returns a matrix on which `.jvmSettings(...)`, `.jsSettings(...)`, and `.nativeSettings(...)` apply settings only to the rows of the corresponding platform. Useful for `nativeLinkStubs := true` on Native or JS-only test framework wiring. These mirror sbt-crossproject's same-named methods.

#### Per-backend access

`compatLibrary(...)` returns a `ProjectMatrix` extended with named accessors `.future` / `.kyo` / `.zio` / `.ox` / `.twitterFuture`, each yielding a view with `.jvm` / `.js` / `.native` for explicit cross-module wiring (`someProject.dependsOn(myLib.zio.jvm)`). Accessing a backend that was not opted in via the `extras*` list throws `NoSuchBackendException` at build-load. Use `.get(backend): Option[CompatBackendProjects]` for a safe lookup.

`.bindLocally(backend, local)` (and `.bindAllLocally(map)`) swap the auto-injected `libraryDependencies += "io.getkyo" %%% s"kyo-compat-<id>"` for a project-level `dependsOn(local)`. Used by contributors testing local snapshots and by in-repo modules wiring against unpublished compat backends:

```scala doctest:expect=skipped
lazy val myLib = (projectMatrix in file("my-lib"))
    .compatLibrary(KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))
    .bindLocally(KyoLib, myInTreeKyoCompatKyo: ProjectReference)
```

Bindings must be set BEFORE first access to the matrix's `componentProjects` / `projectRefs`. Both methods return the same `ProjectMatrix` (chainable).

#### Cross-backend dependencies

When one compat library depends on another, chain `.dependsOn(other)` on the matrix returned by `compatLibrary(...)`. The wiring is backend-aware out of the box because `CompatBackendAxis` extends `VirtualAxis.WeakAxis`: each row in the dependent matrix resolves to the matching-backend row in the dependee.

```scala doctest:expect=skipped
lazy val myFetcher = (projectMatrix in file("my-fetcher"))
    .compatLibrary(KyoLib, ZioLib)(VirtualAxis.jvm, VirtualAxis.js)(Seq("3.3.4"))

lazy val myHttp = (projectMatrix in file("my-http"))
    .compatLibrary(KyoLib, ZioLib)(VirtualAxis.jvm, VirtualAxis.js)(Seq("3.3.4"))
    .dependsOn(myFetcher) // myHttpFuture depends on myFetcherFuture, etc.
```

`dependsOn` is sbt-projectmatrix's own API and returns a `ProjectMatrix`, so the matrix continues to auto-discover.

#### Aggregator project for CI

For a "test or publish all backends" CI target, `.aggregate(id)` returns a plain `Project` that fans every per-(backend, platform) row of the matrix into one task target:

```scala doctest:expect=skipped
lazy val myLibAll = myLib.aggregate("my-lib-all")

// sbt myLibAll/test          -> runs every (backend, platform) cell
// sbt myLibAll/publishLocal  -> publishes every backend
```

The aggregator sets `publish / skip := true`, so it never lands in maven local itself.
