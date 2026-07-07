# kyo-core

`kyo-core` is the runtime layer that turns Kyo's algebraic effects into actual programs that do things: suspend side effects, fork fibers, race and gather concurrent work, manage resources, schedule recurring tasks, and emit logs and metrics. It is the layer between `kyo-prelude` (pure effects and data) and the rest of the ecosystem, providing the I/O substrate that production code targets.

Two effects anchor the model and split responsibility. `Sync` marks pure suspension of side effects: code that runs to completion without parking. `Async` adds the fiber scheduler on top: parking, races, structured cancellation, bounded-concurrency collection ops. Most application code reads as a chain of effectful values (`Console.printLine(...)`, `Async.foreach(items)(process)`) terminating at a `KyoApp` `run` block that discharges the effects at the application boundary. `Fiber[A, S]` is the low-level primitive those combinators sit on top of; application code rarely names it directly, because `Async`, `Scope`, `Channel`, `Hub`, and friends do the fiber work for you.

```scala
import kyo.*

case class Report(url: String, status: Int)

object Crawler extends KyoApp:
    run {
        val urls: Chunk[String] = Chunk("https://a.example", "https://b.example")
        Async.foreach(urls, concurrency = 8) { url =>
            fetch(url).map { status =>
                Log.info(s"$url -> $status").andThen(Report(url, status))
            }
        }
    }

    def fetch(url: String): Int < Async = ???
end Crawler
```

## From values to programs: `Sync`, `Async`, and `KyoApp`

Production code touches I/O at every layer. Kyo splits that into two distinct effects so the type system can tell you which calls might park a fiber and which run straight through.

### Suspending side effects with `Sync`

When you call a side-effecting Java API or system call, you suspend it in `Sync`. A computation in `Sync` runs to completion, without parking and without scheduling. Use it whenever you need to defer execution until the effect is handled.

```scala
import kyo.*

val nowMillis: Long < Sync =
    Sync.defer(java.lang.System.currentTimeMillis())

val withCleanup: String < Sync =
    Sync.ensure(Sync.defer(println("done"))) {
        Sync.defer("computed value")
    }
```

`Sync.acquireReleaseWith` is the lightweight bracket for `Sync`-only resources (no `Scope` effect involved):

```scala
import kyo.*

val read: String < Sync =
    Sync.acquireReleaseWith(new java.io.BufferedReader(new java.io.FileReader("data.txt")))(reader =>
        reader.close()
    ) { reader =>
        reader.readLine()
    }
```

For resources whose lifetime spans more than a single `acquire`/`use` block, use `Scope` instead (covered below).

### Adding fibers with `Async`

When a computation might park (sleep, wait on a fiber, await I/O), reach for `Async`. It extends `Sync` with the fiber-aware scheduler. The same `defer`, `sleep`, `delay`, and `timeout` operators all live on `Async`, plus the structured-concurrency combinators in the next section.

```scala
val slow: Int < Async =
    Async.delay(500.millis)(Sync.defer(42))

val withDeadline: Int < (Async & Abort[Timeout]) =
    Async.timeout(2.seconds)(slow)

val infinite: Nothing < Async =
    Async.never
```

`Async.timeout` requires a finite duration; an infinite duration is short-circuited and the underlying computation is returned unwrapped. `Async.timeoutWithError` lets you supply a custom error on expiry instead of `Timeout`.

`Async.memoize` lazily evaluates a computation once and shares the result with all subsequent callers:

```scala
import kyo.*

val expensive: Int < Async = Sync.defer { Thread.sleep(1000); 42 }

val cached: Int < (Async & Sync) =
    Async.memoize(expensive).flatMap(memo => memo)
```

> **Caution:** `Async.memoize` permanently blocks all callers if the initial computation hangs. Wrap with `Async.timeout` when the underlying computation might not complete.

`Async.fromFuture(f)` lifts a `scala.concurrent.Future` into an `Async` computation, bridging existing Future-based code into the Kyo effect model.

`Async.mask` runs a computation with interrupt masking, so the masked portion completes even if an interrupt arrives. The interrupt is delivered after the mask returns, so the surrounding fiber is still cancellable.

### Running an application

`Async` has no `run` method. You discharge it at the application boundary with `KyoApp`:

```scala
object Hello extends KyoApp:
    run {
        for
            name <- Console.readLine
            _    <- Console.printLine(s"hello $name")
        yield ()
    }
end Hello
```

The `run` block accepts `A < (Async & Scope & Abort[Any])`. Multiple `run` blocks execute sequentially. `args: Chunk[String]` exposes the command-line arguments.

> **Note:** `KyoApp.runAndBlock(timeout)(v)` exists for embedding Kyo inside a blocking integration (`main` calls a Future-returning library, you need a `Result`). It defeats the purpose of async execution, so reserve it for that bridging case.

For full integration outside an application entry point, `KyoApp.Unsafe.runAndBlock(timeout)(v): Result[Throwable, A]` runs a computation and produces a plain `Result`.

## Structured concurrency

`Async` provides three families of structured-concurrency operators: races (pick one), gathers (collect many), and bounded-concurrency collection ops (process a collection, capped). When you need to escape into raw fibers, `Fiber.init` is there.

> **Sequential vs parallel:** the `Async.*` collection operations below run inputs concurrently. For sequential execution, reach for the `Kyo.*` companion (`Kyo.collectAll`, `Kyo.foreach`, `Kyo.fill`, `Kyo.zip`, `Kyo.when`, `Kyo.unless`), defined in [kyo-prelude](../kyo-prelude/README.md). Mental model: `Kyo.*` for sequential, `Async.*` for parallel.

### First success vs first finish

Both `Async.race` and `Async.raceFirst` run a collection of computations concurrently and interrupt the rest when one finishes. The difference is when "finishes" counts.

```scala
val fastest: String < Async =
    Async.race(
        slowSource("a"),
        slowSource("b"),
        slowSource("c")
    )

def slowSource(label: String): String < Async = ???
```

`Async.race` completes only on a successful computation. If all fail, it waits for the last failure. If some never complete, it waits indefinitely for a success.

`Async.raceFirst` completes as soon as any computation completes, success or failure. If one fails while another never completes, `raceFirst` returns the failure and interrupts the rest.

The "when to reach for which" rule: use `race` when you want a successful answer from a redundant set of sources (replicated reads, load-balanced queries). Use `raceFirst` when you want the first observable outcome (a request bounded by a timeout fiber, a competition where any termination is decisive).

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)

// Bound an order lookup by a deadline, surfacing whichever finishes first
val withDeadline: Order < (Async & Abort[Timeout]) =
    Async.raceFirst(
        loadOrder(orderId),
        Async.sleep(2.seconds).andThen(Abort.fail(Timeout()))
    )

val orderId: Long                      = ???
def loadOrder(id: Long): Order < Async = ???
```

> **Caution:** Both `race` and `raceFirst` are unbounded: every input runs concurrently with no admission control. With large input sequences, layer in `Meter.initSemaphore` or call `Async.gather(max)(...)` instead.

### Gathering bounded successful results

When several upstreams race to satisfy a request and you want the first few that succeed, `Async.gather` runs every input concurrently and collects up to `max` successful results.

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)

val orders: Chunk[Order] < Async =
    Async.gather(max = 3)(
        loadOrder(1L),
        loadOrder(2L),
        loadOrder(3L),
        loadOrder(4L),
        loadOrder(5L)
    )

def loadOrder(id: Long): Order < Async = ???
```

Failures are silently dropped; the result is the first `max` successes (or fewer, if not that many succeeded). When you want every result including failures, run `gather` on inputs lifted into `Result`.

### Bounded-concurrency collection ops

For mapping over a sequence with a concurrency cap, use `Async.foreach`, `Async.foreachIndexed`, `Async.collect`, `Async.filter`, `Async.collectAll`, `Async.fill`, and their `Discard` siblings.

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)
case class Txn(id: String)
class ChargeError extends Exception
def charge(o: Order): Txn < (Async & Abort[ChargeError]) = ???
def loadPending: Chunk[Order]                            = ???

val orders: Chunk[Order] = loadPending

val charges: Chunk[Txn] < (Async & Abort[ChargeError]) =
    Async.foreach(orders, concurrency = 16) { order =>
        charge(order)
    }
```

The default concurrency is `Async.defaultConcurrency`, which is `2 * Runtime.getRuntime.availableProcessors()`. Override globally with `-Dkyo.async.concurrency.default=N` or per call with the `concurrency` parameter.

`Async.foreachDiscard` and `Async.collectAllDiscard` drop the results when you don't need them: a useful saving for large fan-out cases that produce `Unit`.

`Async.filter(seq, c)(p)` and `Async.collect(seq, c)(f)` run their predicate or `Maybe`-returning function concurrently and keep only the elements that pass.

`Async.fill(n, c)(v)` and `Async.fillIndexed(n, c)(f)` repeat a computation `n` times in parallel: useful for load testing or backfilling.

### Parallel n-way join

When several independent values are needed before downstream code can proceed, `Async.zip` runs them in parallel and returns a typed tuple. Arities 2 through 10.

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)

val assembled: (Order, Order, Order) < Async =
    Async.zip(
        loadOrder(1L),
        loadOrder(2L),
        loadOrder(3L)
    )

def loadOrder(id: Long): Order < Async = ???
```

### Explicit fibers (advanced)

Most application code never names a `Fiber` directly. When you need to (library code, custom scheduling primitives), `Fiber.init` forks a scoped fiber tied to the enclosing `Scope`:

```scala
val task: Fiber[Int, Any] < (Sync & Scope) =
    Fiber.init(compute)

def compute: Int < Async = ???
```

The fiber is interrupted automatically when its scope closes. `Fiber.initUnscoped` skips scope management: the resulting fiber outlives the launching computation unless you interrupt it explicitly. A leaked unscoped fiber is a resource leak in practice.

```scala
import kyo.*
def compute: Int < Async = ???

// Scoped: tied to the enclosing Scope.run
val safe: Fiber[Int, Any] < (Sync & Scope) = Fiber.init(compute)

// Unscoped: no automatic cleanup
val raw: Fiber[Int, Any] < Sync = Fiber.initUnscoped(compute)
```

Fibers expose `get`, `getResult`, `use`, `useResult`, `map`, `flatMap`, `mapResult`, `mask`, `interrupt`, `onComplete`, `onInterrupt`, `block`, and `safe`. `Fiber.Promise[E, A]` is the manually-completable variant: build one with `Fiber.Promise.init[E, A]`, call `succeed`, `fail`, `complete`, or `become` from another fiber. `Fiber.fromFuture(f)` converts a `scala.concurrent.Future` into a `Fiber`, bridging Future-returning APIs into fiber-managed code.

> **Note:** `Fiber` is a low-level primitive; the public-facing recommendation is to write application code against `Async`'s structured combinators and reach for `Fiber.init` only when none of them fit.

## Resource lifetimes with `Scope`

`Scope` pairs an acquisition with its release. The release runs deterministically on success, failure, or interruption, exactly once. Resources stack: nested acquisitions release in LIFO order when the enclosing `Scope.run` exits.

### `acquireRelease` and `acquire`

```scala
import java.io.FileWriter
import kyo.*

val withFile: Unit < (Async & Sync) =
    Scope.run {
        Scope.acquireRelease(new FileWriter("log.txt"))(_.close()).map { writer =>
            Sync.defer(writer.write("entry\n"))
        }
    }
```

`Scope.acquire` is the convenience for `java.lang.AutoCloseable`:

```scala
import kyo.*

val read: String < (Async & Sync) =
    Scope.run {
        Scope.acquire(new java.io.BufferedReader(new java.io.FileReader("data.txt"))).map { reader =>
            Sync.defer(reader.readLine())
        }
    }
```

### `Scope.ensure`

Register a finalizer without acquiring a corresponding resource:

```scala
val withCleanup: Int < (Async & Sync & Scope) =
    Scope.ensure(Log.info("computation completed")).andThen {
        compute
    }

def compute: Int < Sync = ???
```

The overload `Scope.ensure(f: Maybe[Error[Any]] => Any < ...)` exposes the outcome (`Absent` on success, `Present` on failure) so the finalizer can branch on it.

### `Scope.run` and parallel cleanup

`Scope.run(v)` discharges the `Scope` effect, running finalizers sequentially. `Scope.run(closeParallelism)(v)` runs up to `closeParallelism` finalizers in parallel, useful when many resources have independent slow shutdowns (database pools, network connections).

```scala
// Up to 8 finalizers run concurrently on shutdown
val app: Result[Throwable, Unit] < Async =
    Scope.run(closeParallelism = 8) {
        openAllPools.andThen(serve)
    }

def openAllPools: Unit < (Scope & Sync)    = ???
def serve: Result[Throwable, Unit] < Async = ???
```

> **Note:** Scope finalizers run exactly once. Failures are logged via `Log.error`, not raised, so a finalizer failure does not mask the primary computation's result.

The lower-level `Scope.Finalizer` and `Scope.Finalizer.Awaitable` types are surfaced for library code that wants to drive finalizer lifecycles directly.

## Talking between fibers

Fibers exchange data through bounded buffers (`Channel`), unbounded queues (`Queue`), broadcasts (`Hub`), and request multiplexers (`Exchange`). Pick by traffic pattern: one-to-one, one-to-many, many-to-many, request/response.

### Bounded MPMC buffers

When two fibers need a hand-off with backpressure, reach for `Channel`. It exposes synchronous (`offer`/`poll`) and parking (`put`/`take`) operations on a bounded MPMC buffer.

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)

val example: Unit < (Async & Sync & Scope & Abort[Closed]) =
    Channel.init[Order](capacity = 64).map { channel =>
        channel.put(Order(1L, 100L, Chunk.empty, BigDecimal(0))).andThen {
            channel.take.map { received =>
                Log.info(s"received order ${received.id}")
            }
        }
    }
```

`offer` and `poll` are non-blocking: `offer` returns `false` if the channel is full, `poll` returns `Absent` if empty.

`put` and `take` park the fiber until space is available or an element arrives. `putBatch(values)` puts a sequence atomically (items from one `putBatch` are kept contiguous in the channel). `takeExactly(n)` blocks until at least `n` items can be taken.

`drain` and `drainUpTo(max)` return all currently-buffered elements.

`stream(maxChunkSize)` exposes the channel as `Stream[A, Abort[Closed] & Async]`. Use `streamUntilClosed` if you want a clean termination instead of a `Closed` failure on close.

The `access` parameter at `Channel.init` selects an internal representation tuned to the producer/consumer pattern. `Access` is an enum:
- `Access.MultiProducerMultiConsumer` (default): any pattern.
- `Access.MultiProducerSingleConsumer`: many producers, one consumer.
- `Access.SingleProducerMultiConsumer`: one producer, many consumers.
- `Access.SingleProducerSingleConsumer`: most restrictive, often fastest.

> **Note:** On the JVM, `Channel` capacity is rounded up to the next power of two for performance. Capacity 10 becomes 16.

> **Caution:** A `Channel` has no upper bound on the number of fibers suspended on it. In an HTTP-per-request pattern the queue of waiters can grow unbounded even when the channel's element capacity is bounded. Combine with `Admission` or `Meter` at the boundary if request rate is unbounded.

#### Closing a channel: `close` vs `closeAwaitEmpty`

`close` immediately fails pending consumers with `Closed` and returns any buffered elements:

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)
val channel: Channel[Order] = ???

val remaining: Maybe[Seq[Order]] < Sync = channel.close
```

`closeAwaitEmpty` closes the channel to new producers and waits until all buffered elements have been consumed:

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)
val channel: Channel[Order] = ???

val drained: Boolean < Async = channel.closeAwaitEmpty
```

The "when to reach for which" rule: `close` when consumers should learn the source is gone now (shutdown on error); `closeAwaitEmpty` when consumers should finish the work already enqueued (graceful shutdown).

### Lock-free queues with overflow policies

`Channel` parks fibers on a full buffer; sometimes you want overflow handling instead. `Queue` is the lower-level lock-free queue underneath `Channel`, and `Queue.Unbounded` exposes the policies `Channel` doesn't:

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)

val q: Queue[Order] < (Sync & Scope) =
    Queue.init[Order](capacity = 1024)

val drop: Queue.Unbounded[Order] < (Sync & Scope) =
    Queue.Unbounded.initDropping[Order](capacity = 1024)

val slide: Queue.Unbounded[Order] < (Sync & Scope) =
    Queue.Unbounded.initSliding[Order](capacity = 1024)
```

`Queue.Unbounded.initDropping(capacity)` rejects new offers when full (returns `false`). `Queue.Unbounded.initSliding(capacity)` evicts the oldest element to make room. `Queue.Unbounded.init` has no upper bound.

> **Caution:** `Queue.Unbounded.init` can exhaust memory if producers outpace consumers indefinitely. Prefer `initDropping` or `initSliding` unless an external mechanism enforces a bound.

Like `Channel`, `Queue` has the same `close` (drop any in-flight items, return remaining elements) vs `closeAwaitEmpty` (close to new offers and wait until all buffered elements have been consumed) distinction. Use `close` for immediate shutdown and `closeAwaitEmpty` for graceful draining.

### Broadcast fan-out

When one producer needs to feed many independent listeners (log auditors, metrics, replicas), use `Hub`. Every listener gets every value, with per-listener buffers and listener-driven backpressure.

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)

val example: Unit < (Async & Sync & Scope & Abort[Closed]) =
    Hub.init[Order](capacity = 64).map { hub =>
        hub.listen.map { audit =>
            hub.listen(bufferSize = 16).map { metrics =>
                hub.put(Order(1L, 100L, Chunk.empty, BigDecimal(0))).andThen {
                    audit.take.map { o => Log.info(s"audit saw ${o.id}") }
                }
            }
        }
    }
```

Each `listen` registers a fresh subscriber with its own buffer. The hub's main buffer fills only when a listener is full, applying backpressure to the producer. `listen(filter)` keeps only matching values; `listen(bufferSize, filter)` combines both. `Hub.initUnscoped` creates a hub without tying it to an enclosing `Scope`, useful when the hub's lifetime must be managed manually or outlive the launching computation.

> **Caution:** Because backpressure is applied hub-wide, a leaked or stalled listener (one that is never drained or closed) can stall the entire Hub and block all producers, not just its own consumer. A dedicated fiber distributes messages from the Hub's buffer to each listener's individual buffer; when any listener's buffer becomes full and the Hub's buffer is also full, publishers are blocked. Always close listeners that are no longer needed, and scope them so that shutdown is automatic.

### ID-multiplexed request/response (advanced)

When you're building a protocol client where a single connection multiplexes many in-flight requests (HTTP/2, WebSocket, JSON-RPC), `Exchange` is the primitive. You supply encoder/decoder/transport callbacks; `Exchange` runs a single reader fiber that drains incoming frames, routes responses by ID back to their pending callers, and surfaces unsolicited messages as events.

```scala
val client: Exchange[Request, Response, Nothing, java.io.IOException] < (Sync & Scope) =
    Exchange.init[Request, Response, Frame, Nothing, java.io.IOException](
        encode = (id, req) => Sync.defer(toFrame(id, req)),
        send = frame => transport.write(frame),
        receive = transport.frames,
        decode = frame => Sync.defer(classify(frame))
    )

trait Request; trait Response; trait Frame
def toFrame(id: Int, req: Request): Frame                        = ???
def classify(f: Frame): Exchange.Message[Int, Response, Nothing] = ???
trait Transport:
    def write(f: Frame): Unit < (Async & Abort[java.io.IOException])
    def frames: Stream[Frame, Async & Abort[java.io.IOException]]
val transport: Transport = ???
```

`Exchange` is intentionally low-level. Most application code reaches for a higher-level HTTP/2 or WebSocket client built on top of it.

> **Caution:** `Exchange`'s `decode` callback runs on the single reader fiber and must be `Sync` only. Making it `Async` would stall every in-flight request behind a single decode's parking.

## Coordinating work

The synchronization primitives below are for fiber-to-fiber waiting, mutual exclusion, rate limiting, load shedding, and reactive state.

### `Latch`: asymmetric countdown

When N background tasks must complete before a coordinator proceeds, use `Latch`. `Latch.init(n)` creates a latch that releases all waiters when `release` has been called `n` times. Similar to `CountDownLatch`.

```scala
val example: Unit < (Async & Sync) =
    Latch.init(3).map { latch =>
        Async.foreachDiscard(1 to 3) { i =>
            doWork(i).andThen(latch.release)
        }.andThen {
            latch.await.andThen(Log.info("all three done"))
        }
    }

def doWork(i: Int): Unit < Sync = ???
```

> **Note:** A `Latch` initialized with a count `<= 0` is a no-op: all `await` calls complete immediately.

### `Gate`: symmetric multi-party barrier

When N parties must all reach a rendezvous point before any proceeds, use `Gate`. `Gate.init(parties)` blocks every party until `parties` parties have arrived, then releases them all. Similar to `CyclicBarrier`. Gates reuse across phases; once released, all parties can pass again on the next cycle.

```scala
import kyo.*

val example: Unit < (Async & Sync & Scope & Abort[Closed]) =
    Gate.init(parties = 4).map { gate =>
        Async.foreachDiscard(1 to 4) { worker =>
            prepare(worker).andThen {
                gate.pass.andThen(execute(worker))
            }
        }
    }

def prepare(w: Int): Unit < Sync = ???
def execute(w: Int): Unit < Sync = ???
```

`Gate.Dynamic.init(parties)` is the variant where parties can join and leave between cycles.

The "when to reach for which" rule: `Latch` is asymmetric (some release, others wait). `Gate` is symmetric (all parties pass together).

### `Meter`: mutex, semaphore, rate limiter

When you need to cap concurrency, enforce mutual exclusion, or limit a rate, reach for `Meter`. It exposes three factories:

```scala
val mutex: Meter < (Sync & Scope) =
    Meter.initMutex

val semaphore: Meter < (Sync & Scope) =
    Meter.initSemaphore(concurrency = 8)

val rateLimiter: Meter < (Sync & Scope) =
    Meter.initRateLimiter(rate = 100, period = 1.second)
```

Use `meter.run(v)` to execute a computation under the meter, blocking until a permit is available. `meter.tryRun(v)` returns `Maybe[A]` and skips the work if no permit is available.

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)
case class Txn(id: String)
case class ChargeError() extends Exception
val orders: Chunk[Order]                                 = Chunk.empty
def charge(o: Order): Txn < (Async & Abort[ChargeError]) = ???

val charged: Chunk[Txn] < (Async & Scope & Abort[ChargeError | Closed]) =
    Meter.initRateLimiter(50, 1.second).map { limiter =>
        Async.foreach(orders) { order =>
            limiter.run(charge(order))
        }
    }
```

Meters are reentrant by default: nested calls from the same fiber pass through. Pass `reentrant = false` to enforce strict mutual exclusion even within a single fiber. Each meter exposes `availablePermits`, `pendingWaiters`, `close`, and `closed`.

`Meter.pipeline(m1, m2)` composes two (or more, up to four-arity) meters: a request must acquire each in order. The composite "limit to 10 concurrent operations but no more than 100/second" pattern is the canonical use:

```scala
val composite: Meter < (Sync & Scope) =
    Meter.pipeline(
        Meter.initSemaphore(10),
        Meter.initRateLimiter(100, 1.second)
    )
```

`Meter.Noop` is a no-op meter that always grants permits immediately, useful in tests when you want to disable metering without changing the call site. Each factory also has an unscoped variant (`initMutexUnscoped`, `initSemaphoreUnscoped`, `initRateLimiterUnscoped`) for manual lifecycle management, and a `useX` variant (`useMutex`, `useSemaphore`, `useRateLimiter`) that acquires an unscoped meter, runs a function, and discards the meter when the function returns.

### `Admission`: load shedding at the boundary

When the scheduler reports congestion and you'd rather shed load than queue it, wrap the entry point in `Admission`. It rejects probabilistically, or deterministically per key.

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)
case class ChargeError() extends Exception
val order: Order                                           = Order(1L, 100L, Chunk.empty, BigDecimal(0))
def charge(o: Order): Order < (Async & Abort[ChargeError]) = ???

val handle: Order < (Async & Abort[ChargeError | Rejected]) =
    Admission.run(s"customer-${order.customerId}") {
        charge(order)
    }.map(_ => order)
```

`Admission.run(v)` rejects probabilistically when the scheduler reports congestion. `Admission.run(key)(v)` uses the key's hash for deterministic per-key rejection: identical keys see the same outcome, so related requests share their fate.

A rejection raises `Abort[Rejected]`, which the caller can translate into an HTTP 503 or a back-off. `Admission.reject` returns the rejection decision without running anything: useful when you want to drop entirely instead of failing.

### `Signal`: reactive value with change streams

When downstream code must react to value changes (UI state, config reload, feature flags), use `Signal`. It exposes a mutable cell (`Signal.Ref[A]`) whose changes propagate to subscribers.

```scala
import kyo.*

val example: Unit < (Async & Sync) =
    Signal.initRef(0).map { count =>
        Async.foreachDiscard(1 to 100) { _ =>
            count.updateAndGet(_ + 1).unit
        }.andThen {
            count.current.map(c => Log.info(s"final: $c"))
        }
    }
```

`signal.current` reads the current value (`Sync`). `signal.next` parks until the value changes (`Async`). `signal.streamCurrent` emits the current value and every subsequent change. `signal.streamChanges` emits only subsequent changes.

> **Caution:** `Signal.streamChanges` may skip intermediate values under load. The stream guarantees latest-value semantics, not every-change-observed semantics. For capture-every-change cases use a `Channel` instead.

`Signal.initConst(value)` produces a `Signal` that never changes: useful as a placeholder or a sentinel.

## Shared mutable state

Across fibers, state lives in atomics, contended-write counters, or a CLOCK-evicting cache.

### Atomic primitives

`AtomicInt`, `AtomicLong`, `AtomicBoolean`, and `AtomicRef[A]` are effect-typed wrappers around `java.util.concurrent.atomic`. Every operation returns `... < Sync`.

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)

val counter: Long < Sync =
    AtomicLong.init(0).map { ref =>
        ref.incrementAndGet.andThen(ref.incrementAndGet).andThen(ref.get)
    }

val refExample: Order < Sync =
    AtomicRef.init(Order(1L, 100L, Chunk.empty, BigDecimal(0))).map { ref =>
        ref.updateAndGet(o => o.copy(total = o.total + BigDecimal(10)))
    }
```

All four expose `get`, `set`, `lazySet`, `getAndSet`, `compareAndSet(curr, next)`, `getAndUpdate(f)`, and `updateAndGet(f)`. The integer types add `incrementAndGet`, `decrementAndGet`, `getAndIncrement`, `getAndDecrement`, `getAndAdd`, `addAndGet`, etc.

### Contention-optimized counters

When many fibers update a counter and reads are rare, `LongAdder` and `DoubleAdder` outperform atomics. `LongAdder` has fast writes (lock-free striped counter) but slower reads (must sum all stripes); `AtomicLong` is opposite: cheap reads, contended writes serialize.

```scala
import kyo.*

val counted: Long < (Async & Sync) =
    LongAdder.init.map { adder =>
        Async.foreachDiscard(1 to 1_000_000)(_ => adder.increment)
            .andThen(adder.get)
    }
```

The "when to reach for which" rule: pick `LongAdder` when many fibers increment and the value is read infrequently (request counters, hit counters). Pick `AtomicLong` when reads dominate or you need `cas` semantics. Choosing by name alone hides the trade-off: both look like counters, but `LongAdder` trades faster contended writes for slower reads (it must sum across stripes), while `AtomicLong` is the inverse.

### Bounded caches and memoization

When repeated work on the same key should reuse a previous result, reach for `Cache`. `Cache.init(maxSize, expireAfterAccess, expireAfterWrite)` creates a bounded cache with CLOCK eviction.

```scala
import kyo.*
case class User(id: Long, name: String)

val lookups: Maybe[User] < Sync =
    Cache.init[Long, User](maxSize = 10_000, expireAfterAccess = 5.minutes).map { cache =>
        cache.add(42L, User(42L, "alice")).andThen(cache.get(42L))
    }
```

`getOrElse(key, value)` returns the cached value or evaluates the default and inserts it. `remove(key)` marks the entry for eviction.

> **Caution:** Actual table capacity is rounded up to the next power of two above `maxSize * 5/4`. Maximum `maxSize` is 1,048,576 entries.

`Cache.memo(maxSize, expireAfterAccess, expireAfterWrite)(f)` builds a self-contained memoized function. Concurrent callers for the same key deduplicate: one computes, the rest wait on the in-flight result.

```scala
import kyo.*
case class User(id: Long, name: String)
class NotFound extends Exception
def fetchUser(id: Long): User < (Sync & Abort[NotFound]) = ???

val fetcher: (Long => User < (Async & Abort[NotFound])) < Sync =
    Cache.memo[Long](maxSize = 10_000) { id =>
        fetchUser(id)
    }
```

`memo2`, `memo3`, `memo4` handle two-, three-, and four-argument functions.

## Time, deadlines, retries

The wall-clock and the monotonic clock answer different questions. Retries layer on top.

### `Clock.now` vs `Clock.nowMonotonic`

When you need a timestamp for humans or other systems (log lines, database TTLs), use `Clock.now`. When you need to measure elapsed time without surprises, use `Clock.nowMonotonic`. The wall-clock can jump (NTP adjustment, leap seconds, DST), go backwards, and stand still during a system suspend; the monotonic clock only increases, and the duration between two readings reflects real elapsed time.

```scala
val stamped: (Instant, Duration) < Sync =
    Clock.now.map { wall =>
        Clock.nowMonotonic.map(mono => (wall, mono))
    }
```

### Measuring elapsed time and tracking deadlines

```scala
val measured: (Result[Throwable, Int], Duration) < (Async & Sync) =
    Clock.stopwatch.map { sw =>
        Abort.run[Throwable](work).map { result =>
            sw.elapsed.map(d => (result, d))
        }
    }

def work: Int < (Async & Abort[Throwable]) = ???

val withDeadline: Boolean < Sync =
    Clock.deadline(2.seconds).map { dl =>
        dl.timeLeft.map(_.toMillis > 0)
    }
```

`Stopwatch.elapsed` returns the monotonic duration since the stopwatch was created. `Deadline.timeLeft` returns the time remaining; `Deadline.isOverdue` is the boolean version.

### Scheduling recurring work

```scala
val pollEverySec: Unit < (Async & Sync) =
    Clock.repeatWithDelay(1.second)(checkHealth)

def checkHealth: Unit < (Async & Sync) = ???

val tickOnSchedule: Unit < (Async & Sync) =
    Clock.repeatAtInterval(1.second)(emitMetric)

def emitMetric: Unit < (Async & Sync) = ???
```

The "when to reach for which" rule: `repeatWithDelay(d)` runs the task, waits `d`, runs it again. A slow task pushes the next start out. `repeatAtInterval(d)` runs at fixed wall-clock intervals; if the task takes longer than the interval, the next invocation starts immediately (subsequent invocations may stack up). Pick deliberately.

### Deterministic time for tests

Both `Clock.withTimeShift` and `Clock.withTimeControl` produce deterministic time within a scope, useful for testing.

```scala
val fastForward: Result[Throwable, Unit] < (Async & Sync) =
    Clock.withTimeShift(factor = 1000.0) {
        Abort.run[Throwable] {
            Async.sleep(1.hour).andThen(Log.info("done"))
        }
    }
```

`withTimeShift(factor)` runs the body with the clock advancing `factor` times faster. `withTimeControl(f)` gives `f` direct control over the clock: `f` receives a `TimeControl` it can advance manually.

> **Caution:** `Clock.TimeControl` is not thread-safe. All operations must be performed sequentially within a single fiber.

### `Retry`

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)
case class NotFound() extends Exception

val resilient: Order < (Async & Abort[NotFound]) =
    Retry[NotFound] {
        loadOrder(42L)
    }

val customSchedule: Order < (Async & Abort[NotFound]) =
    Retry[NotFound](Schedule.fixed(500.millis).take(5)) {
        loadOrder(42L)
    }

def loadOrder(id: Long): Order < (Async & Abort[NotFound]) = ???
```

`Retry[E](v)` uses `Retry.defaultSchedule`: exponential backoff starting at 100ms, factor 2, max 5 seconds, 0.2 jitter, capped at 3 attempts. `Retry[E](schedule)(v)` accepts any `Schedule`.

`Schedule` is defined in `kyo-prelude`; common combinators are `exponentialBackoff`, `fixed`, `take`, `jitter`, and `and`/`or` for combining policies.

### `Timeout`

`Async.timeout(d)(v)` adds `Abort[Timeout]` to the effect row. The `Timeout` error carries the duration that expired:

```scala
val withTimeout: Result[Timeout, Int] < (Async & Sync) =
    Abort.run[Timeout] {
        Async.timeout(2.seconds) {
            Async.sleep(5.seconds).andThen(42)
        }
    }
```

`Async.timeoutWithError(d, error)(v)` lets you raise a domain-specific error on expiry instead.

> **File system, processes, and environment:** `Path`, `Command`, `Process`, `System`, and the
> `FileException` hierarchy live in [`kyo-system`](../kyo-system/README.md); add it to your
> dependencies to use them.

## Ambient services

`Console`, `Random`, and `Log` are thread-local-style context services. Their default implementations target the platform's stdout/stderr, secure RNG, and console logger respectively. Tests can swap them out per scope without threading them as arguments.

### `Console`

```scala
val name: String < (Sync & Abort[java.io.IOException]) =
    for
        _ <- Console.print("name? ")
        n <- Console.readLine
        _ <- Console.printLine(s"hello $n")
    yield n
```

`Console.print`, `Console.printLine`, `Console.printErr`, `Console.printLineErr` and `Console.readLine` cover the basics. `Console.checkErrors` returns `true` if either stdout or stderr has signalled a write failure.

> **Note:** Console print methods return no `Abort` because the underlying Java `PrintStream` never throws. A write failure is silently captured; check it explicitly with `Console.checkErrors`.

`Console.flush` flushes both stdout and stderr. `Console.withIn(lines)(v)` runs `v` with a stub that replays the provided lines as `readLine` input, useful in tests. `Console.withOut(v)` captures all print output from `v` into a buffer and returns it alongside the result.

`Console.let(c)(v)` runs `v` with `c` as the ambient console: useful for testing (capture output to a buffer) and for redirection.

### `Random`

```scala
import kyo.*
case class User(id: Long, name: String)
val allUsers: Chunk[User] = Chunk.empty

val pickUser: User < Sync =
    Random.nextValue(allUsers)

val token: String < Sync =
    Random.nextStringAlphanumeric(length = 32)
```

`Random` exposes `nextInt`, `nextInt(bound)`, `nextLong`, `nextDouble`, `nextBoolean`, `nextGaussian`, `nextValue(seq)`, `nextValues(length, seq)`, `nextStringAlphanumeric(length)`, and `shuffle(seq)`.

For deterministic tests: `Random.withSeed(seed)(v)` runs `v` with a seeded RNG; `Random.let(r)(v)` substitutes a custom `Random` instance for the scope.

### `Log`

`Log` is the ambient logger. `Log.live` is the default backend: a `ConsoleLogger` named `kyo.logs` at `warn` level. It writes `warn` and `error` to stderr (with stack traces to stderr) and `trace`, `debug`, and `info` to stdout. Each line is prefixed with a timestamp from the system clock. Log calls are async by default on JVM and Native: each call enqueues to a bounded background channel (capacity 4096) and returns without blocking; a daemon fiber drains the channel in FIFO order. `Log.flush: Unit < Async` suspends until the daemon has delivered every enqueued event. To force synchronous logging, set `-Dkyo.Log.asyncLogging=false`.

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)
val orders: Chunk[Order] = Chunk.empty

val example: Unit < Sync =
    for
        _ <- Log.debug("starting")
        _ <- Log.info(s"orders: ${orders.length}")
        _ <- Log.error("failed", new RuntimeException("nope"))
    yield ()
```

Each level (`trace`, `debug`, `info`, `warn`, `error`) has a `(msg)` form and a `(msg, throwable)` form. `Log.Level` is the enum of severities.

## Cross-cutting errors

Three error types appear in `Abort` rows across the module:

- `Closed`: raised by `Channel`, `Queue`, `Promise`, and `Hub` when the underlying resource is closed (or by operations attempted after close). Carries the resource name and the frame where it was created.
- `Interrupted`: marker for fiber interruption. Carries the frame where the interrupt was issued.
- `Timeout`: produced by `Async.timeout(d)(v)` and `Fiber.block(duration)` on expiry. Carries the duration.

`KyoApp.FailureException` is the wrapper thrown when an uncaught `Abort` value surfaces from a `runAndBlock` boundary, allowing callers to distinguish Kyo-originated failures from unexpected exceptions.

Handle them per-effect with `Abort.run[Closed]`, `Abort.recover[Timeout]`, and so on:

```scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)
val channel: Channel[Order] = ???

val handled: Maybe[Order] < (Async & Sync) =
    Abort.run[Closed] {
        channel.take
    }.map {
        case Result.Success(o)         => Maybe(o)
        case Result.Failure(_: Closed) => Absent
        case panic: Result.Panic       => Maybe.empty
    }
```

## Metrics and stream bridges

### `Stat`: metrics registry

When you want to publish counters, histograms, gauges, and traces to a metrics backend, create a `Stat` registry scope. Metrics inside that scope are named with the scope's prefix.

```scala
val stats: Stat = Stat.initScope("kyo", "orders")

val counter: Counter     = stats.initCounter("processed", "orders successfully charged")
val histogram: Histogram = stats.initHistogram("charge-latency-ms", "charge endpoint latency")
val gauge: Gauge         = stats.initGauge("queue-depth", "pending orders")(currentDepth.toDouble)
val cgauge: CounterGauge = stats.initCounterGauge("active-fibers", "live worker fibers")(activeCount)

def currentDepth: Int = ???
def activeCount: Long = ???
```

`Counter` exposes `inc`, `add(v)`, `get`. `Histogram` exposes `observe(v)`. `Gauge` and `CounterGauge` are read-only views: the registry calls the provided thunk on each scrape.

`Stat.traceSpan(name, attributes)(v)` wraps a computation in a trace span exported via the registered `TraceExporter`. `Stat.traceListen(exporter)(v)` registers an exporter for the duration of the scope.

### `StreamCoreExtensions`

`StreamCoreExtensions` adds async `Channel`-driven stream operators. `Stream.emitChunks`, `Stream.fromChannel`, async `mapPar`, and `Stream`-level `StreamHub` for fan-out. The default `defaultAsyncStreamBufferSize` is 1024.

The companion methods are imported with `kyo.*` and become available on the `Stream` companion and on existing `Stream` values.

### `StreamCompression` (JVM only)

`StreamCompression` is a JVM-only object (in `kyo-core/jvm`) that adds gzip and deflate operators directly to `Stream[Byte, Ctx]` via an extension. All four operators are available after importing `kyo.*`.

- `stream.deflate(...)` compresses bytes using raw deflate and returns `Stream[Byte, Scope & Sync & Ctx]`.
- `stream.inflate(...)` decompresses raw deflate data and returns `Stream[Byte, Sync & Scope & Ctx & Abort[StreamCompressionException]]`.
- `stream.gzip(...)` compresses bytes with the gzip framing (header + CRC-32 trailer) and returns `Stream[Byte, Scope & Sync & Ctx]`.
- `stream.gunzip(...)` decompresses a gzip stream, validates the trailer, and returns `Stream[Byte, Sync & Scope & Ctx & Abort[StreamCompressionException]]`.

Compression behaviour is tuned through three enums nested in `StreamCompression`: `CompressionLevel` (from `NoCompression` through `BestSpeed` and `BestCompression` to `Default`), `CompressionStrategy` (`Default`, `Filtered`, `HuffmanOnly`), and `FlushMode` (`NoFlush`, `SyncFlush`, `FullFlush`, `Default`). Decompression failures surface as `StreamCompressionException`.

All operators default to a 32 KB buffer (`1 << 15`) and `Default` settings, so the common case requires no arguments:

```scala
import kyo.*
import kyo.StreamCompression.*

val compressed: Stream[Byte, Scope & Sync] =
    Stream.init(Chunk[Byte](1, 2, 3)).gzip()

val decompressed: Stream[Byte, Sync & Scope & Abort[StreamCompression.StreamCompressionException]] =
    compressed.gunzip()
```

## Putting it together

The example below combines several effects from this module into one cohesive program: `KyoApp` discharges the effect row at the application boundary, `Meter.initRateLimiter` enforces a system-wide rate limit, `Async.foreach` fans out work with bounded concurrency, `Console.printLine` emits the rendered output, and `Log.info` emits structured log lines. Everything composes into a single value that `KyoApp` then runs.

```scala
import kyo.*

case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)
case class Txn(id: String)
class ChargeError extends Exception

object Checkout extends KyoApp:
    run {
        val orders: Chunk[Order] = loadPending

        // Bounded-concurrency fan-out: rate-limit charges to 50/sec
        Meter.initRateLimiter(rate = 50, period = 1.second).map { limiter =>
            // Process each order: charge, print receipt, log
            Async.foreach(orders, concurrency = 16) { order =>
                limiter.run(charge(order)).map { txn =>
                    Console.printLine(render(order, txn)).andThen {
                        Log.info(s"order ${order.id} -> ${txn.id}")
                    }
                }
            }
        }
    }

    def loadPending: Chunk[Order]                            = ???
    def charge(o: Order): Txn < (Async & Abort[ChargeError]) = ???
    def render(o: Order, t: Txn): String                     = ???
end Checkout
```

The resulting type of the `run` block is `Chunk[Unit] < (Async & Scope & Abort[ChargeError])`, which `KyoApp` discharges.

## Low-level extension points

Every public type in kyo-core has a companion `Unsafe` object (`Sync.Unsafe`, `Async`-by-way-of `Fiber.Unsafe`, `Channel.Unsafe`, `Queue.Unsafe`, `Cache.Unsafe`, `Exchange.Unsafe`, `Console.Unsafe`, ...). The `Unsafe` API skips the effect-tracking layer and works against raw values, gated by an `AllowUnsafe` evidence import. Application code should use the safe surface; the `Unsafe` API is for library integrations, performance-critical inner loops, and bridging into non-Kyo code.

The `KyoApp` lifecycle is extensible via `KyoApp.Base[S]`, `KyoAppRunner`, `KyoAppInterrupts`, and `KyoAppRunnerWithInterrupts`. Override these to customise initialization, interrupt handling, or the effect set the `run` block accepts.

`Async.Join` is the arrow effect that backs `Async.race`, `Async.gather`, and `Async.zip` internally. Library code implementing custom structured-concurrency primitives can use it directly as an escape hatch when none of the built-in combinators fit.

Deprecated type aliases preserved for migration: `Resource` (use `Scope`) and `IO` (use `Sync`). Both will be removed in 1.0.
