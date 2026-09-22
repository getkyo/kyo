# kyo-core

`kyo-core` is the runtime layer that turns Kyo's algebraic effects into actual programs that do things: suspend side effects, fork fibers, race and gather concurrent work, manage resources, schedule recurring tasks, and emit logs and metrics. It is the layer between `kyo-prelude` (pure effects and data) and the rest of the ecosystem, providing the I/O substrate that production code targets.

Two effects anchor the model and split responsibility. `Sync` marks pure suspension of side effects: code that runs to completion without parking. `Async` adds the fiber scheduler on top: parking, races, structured cancellation, bounded-concurrency collection ops. Most application code reads as a chain of effectful values (`Console.printLine(...)`, `Cache.get(key)`, `Async.foreach(items)(process)`) terminating at a `KyoApp` `run` block that discharges the effects at the application boundary. `Fiber[A, S]` is the low-level primitive those combinators sit on top of; application code rarely names it directly, because `Async`, `Scope`, `Channel`, `Hub`, and friends do the fiber work for you.

```scala
import kyo.*

object Crawler extends KyoApp:
    run {
        val urls: Chunk[String] = Chunk("https://a.example", "https://b.example")
        Async.foreachDiscard(urls, concurrency = 8) { url =>
            fetch(url).map { status =>
                Log.warn(s"$url -> $status")
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

`Sync.ensure` and its sibling `Sync.acquireReleaseWith` are the lightweight brackets; [Resource safety](#resource-safety) covers when to use them and when to use `Scope` instead.

### Adding fibers with `Async`

When a computation might park (sleep, wait on a fiber, await I/O), reach for `Async`. It extends `Sync` with the fiber-aware scheduler: alongside `defer`, `Async` adds `sleep`, `delay`, and `timeout`, plus the structured-concurrency combinators in the next section.

```scala
val slow: Int < Async =
    Async.delay(500.millis)(Sync.defer(42))

val withDeadline: Int < (Async & Abort[Timeout]) =
    Async.timeout(2.seconds)(slow)

val infinite: Nothing < Async =
    Async.never
```

An infinite duration disables `Async.timeout`: the underlying computation is returned unwrapped. `Async.timeoutWithError` lets you supply a custom error on expiry instead of `Timeout`.

`Async.memoize` lazily evaluates a computation and shares its first successful result with all subsequent callers; a failure clears the slot, so the next caller runs it again:

```scala
import kyo.*

val expensive: Int < Async =
    Async.sleep(1.second).andThen(42)

// The second call reuses the first one's result instead of sleeping again.
val sharedTwice: (Int, Int) < Async =
    Async.memoize(expensive).map { memo =>
        memo.map(first => memo.map(second => (first, second)))
    }
```

> **Caution:** `Async.memoize` leaves all callers waiting for completion if the initial computation hangs. Wrap with `Async.timeout` when the underlying computation might not complete.

`Async.fromFuture(f)` lifts a `scala.concurrent.Future` into an `Async` computation, bridging existing Future-based code into the Kyo effect model.

`Async.uninterruptible` runs a computation that interrupts cannot reach, so it runs to its end even if the caller is interrupted. The caller itself stays cancellable: an interrupt stops it waiting at once and runs its own finalizers, while the protected computation finishes on its own and its result goes nowhere. Anything the protected computation opens, it must release itself.

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

The `run` block accepts `A < (Async & Scope & Abort[Any])`. Multiple `run` blocks execute sequentially. A block's result, when it is not `Unit`, is printed to stdout, so a block that exists for its effects should end in `Unit`. `args: Chunk[String]` exposes the command-line arguments.

> **Note:** `KyoApp.runAndBlock(timeout)(v)` blocks the calling thread until `v` finishes, leaving `Sync` and `Abort[E | Timeout]` in the row. It exists for embedding Kyo inside a blocking integration. It defeats the purpose of async execution, so reserve it for that bridging case. Like `Fiber.block`, it throws on JavaScript and Wasm, where there is only one thread to block.

For full integration outside an application entry point, `KyoApp.Unsafe.runAndBlock(timeout)(v): Result[Throwable, A]` runs a computation and produces a plain `Result`.

## Structured concurrency

`Async` provides three families of structured-concurrency operators: races (pick one), gathers (collect many), and bounded-concurrency collection ops (process a collection, capped). When you need to escape into raw fibers, `Fiber.init` is there.

> **Sequential vs parallel:** the `Async.*` collection operations below run inputs concurrently. For sequential execution, reach for the `Kyo.*` companion (`Kyo.collectAll`, `Kyo.foreach`, `Kyo.fill`, `Kyo.zip`, `Kyo.when`, `Kyo.unless`), defined in [kyo-kernel](../kyo-kernel/README.md). `Kyo.*` is sequential, `Async.*` is parallel.

From here on, the examples share a small order-processing domain:

```scala doctest:setup
case class Item(sku: String, qty: Int, price: BigDecimal)
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
```

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

Use `race` when you want a successful answer from a redundant set of sources (replicated reads, load-balanced queries). Use `raceFirst` when you want the first observable outcome (a request bounded by a timeout fiber, a competition where any termination is decisive).

```scala
import kyo.*

// Bound an order lookup by a deadline, surfacing whichever finishes first
val withDeadline: Order < (Async & Abort[Timeout]) =
    Async.raceFirst(
        loadOrder(orderId),
        Async.sleep(2.seconds).andThen(Abort.fail(Timeout()))
    )

val orderId: Long                      = ???
def loadOrder(id: Long): Order < Async = ???
```

> **Caution:** Both `race` and `raceFirst` are unbounded: every input runs concurrently with no admission control. With large input sequences, layer in `Meter.initSemaphore`, or use `Async.foreach` with a `concurrency` cap.

### Gathering bounded successful results

When several upstreams race to satisfy a request and you want the first few that succeed, `Async.gather` runs every input concurrently and collects up to `max` successful results.

```scala
import kyo.*

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

Failures are skipped while other inputs can still succeed; the result is up to `max` successes, returned in input order. If no input succeeds, `gather` fails with the last error. When you want every result including failures, run `gather` on inputs lifted into `Result`.

### Bounded-concurrency collection ops

For mapping over a sequence with a concurrency cap, use `Async.foreach`, `Async.foreachIndexed`, `Async.collect`, `Async.filter`, `Async.collectAll`, and `Async.fill`, plus `Async.foreachDiscard` and `Async.collectAllDiscard` when the results are not needed.

```scala
import kyo.*
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

The default concurrency is `Async.defaultConcurrency`, which is `2 * Runtime.getRuntime.availableProcessors()`. Override globally with the `-Dkyo.async.concurrency.default=N` system property or the `KYO_ASYNC_CONCURRENCY_DEFAULT` environment variable (checked in that order, system property first), or override per call with the `concurrency` parameter. On Scala.js, Wasm, and Scala Native, the environment variable is the only one of the two global channels that takes effect.

`Async.foreachDiscard` and `Async.collectAllDiscard` drop the results when you don't need them: a useful saving for large fan-out cases that produce `Unit`.

`Async.filter(seq, c)(p)` and `Async.collect(seq, c)(f)` run their predicate or `Maybe`-returning function concurrently and keep only the elements that pass.

`Async.fill(n, c)(v)` and `Async.fillIndexed(n, c)(f)` repeat a computation `n` times in parallel: useful for load testing or backfilling.

### Parallel n-way join

When several independent values are needed before downstream code can proceed, `Async.zip` runs them in parallel and returns a typed tuple. Arities 2 through 10.

```scala
import kyo.*

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

Fibers expose `get`, `getResult`, `use`, `useResult`, `map`, `flatMap`, `mapResult`, `uninterruptible`, `interrupt`, `interruptAwait`, `onComplete`, `onInterrupt`, and `block`. `Promise[A, S]` (exported at the top level) is the manually-completable variant: build one with `Promise.init[A, Abort[E]]` and call `succeed`, `fail`, `complete`, or `become` from another fiber. `Fiber.fromFuture(f)` converts a `scala.concurrent.Future` into a `Fiber`, bridging Future-returning APIs into fiber-managed code.

## Resource safety

A release that has been registered runs exactly once, whether the computation succeeds, fails, or is interrupted. Everything in this section follows from that sentence and from its one condition: the guarantee starts at registration. The tools differ in where the release is registered, how long it stays owed, and what it may do when it runs.

### Choosing the tool

| You have | Reach for | The release runs |
| --- | --- | --- |
| A resource used within one expression, with a release that does not park | `Sync.acquireReleaseWith`, `Sync.ensure` | inline, when the expression ends |
| A resource that outlives the expression that opened it, or a release that parks | `Scope.acquireRelease`, `Scope.acquire`, `Scope.ensure` | when the enclosing `Scope.run` closes |

The `Sync` brackets need no `Scope` in the effect row, and their release is a `Sync` computation evaluated to completion in place:

```scala
import kyo.*

val firstLine: String < Sync =
    Sync.acquireReleaseWith(new java.io.BufferedReader(new java.io.FileReader("data.txt")))(_.close()) { reader =>
        reader.readLine()
    }
```

A `Scope` release may be `Async`: it can flush over the network, wait on a fiber, or drain a queue. It is owed until the scope closes, so the value can be handed around, stored, and used by later steps.

### `acquireRelease`, `acquire`, and `ensure`

```scala
import java.io.FileWriter
import kyo.*

val withFile: Unit < Async =
    Scope.run {
        Scope.acquireRelease(new FileWriter("log.txt"))(_.close()).map { writer =>
            Sync.defer(writer.write("entry\n"))
        }
    }
```

`Scope.acquire` is the convenience for `java.lang.AutoCloseable`:

```scala
import kyo.*

val read: String < Async =
    Scope.run {
        Scope.acquire(new java.io.BufferedReader(new java.io.FileReader("data.txt"))).map { reader =>
            Sync.defer(reader.readLine())
        }
    }
```

`Scope.ensure` registers a release with no resource attached. Its overload hands the release the outcome, `Absent` when the scope's body completed and `Present(error)` when it failed or was interrupted, so a release can commit or roll back:

```scala
val withCleanup: Int < (Async & Scope) =
    Scope.ensure { (outcome: Maybe[Result.Error[Any]]) =>
        outcome match
            case Absent        => Log.info("committed")
            case Present(fail) => Log.warn(s"rolled back: $fail")
    }.andThen(compute)

def compute: Int < Sync = ???
```

### Closing a scope

`Scope.run` discharges `Scope` and closes it when its body ends. Its result is delivered after every release has run, and a failure from the body is raised again after them, so the caller never observes a half-closed scope. Releases run in reverse registration order. A failing release is logged with `Log.error` and the rest still run, so a release can never mask the body's own result or error.

A `Scope.run` nested inside another is its child: it closes at its own end, releasing its resources before the enclosing scope's own. A run opened inside a forked branch or an unscoped fiber is a root instead, since that fiber can outlive the scope it was forked from. `Scope.run(closeParallelism)` runs up to that many releases at once, for scopes holding many independent slow shutdowns such as connection pools. Releases still start in reverse order, but one may finish after a release registered before it.

```scala
val app: Unit < Async =
    Scope.run(closeParallelism = 8) {
        openAllPools.andThen(serve)
    }

def openAllPools: Unit < (Scope & Sync) = ???
def serve: Unit < Async                 = ???
```

### Fibers and scopes

`Fiber.init` ties the fiber to the enclosing scope as one of its releases. When the scope reaches it, the release interrupts the fiber, waits for it to stop, and then releases what the fiber registered, before moving on to anything registered ahead of the fiber. The combinators in [Structured concurrency](#structured-concurrency) work differently: they join or interrupt their own branches before returning, and a resource acquired inside a branch registers directly on the scope the combinator was called in.

Two cases need care:

- **Interrupting a fiber does not wait for it.** `fiber.interrupt` returns once the interrupt is requested. When the caller must observe the fiber stopped, use `fiber.interruptAwait`: it returns once the fiber has stopped and its brackets and nested `Scope.run`s have released. What a `Fiber.init` fiber registered directly on its scope is released when the enclosing scope reaches it.
- **A fiber that outlives its scope cannot register on it.** A registration on a closed scope logs a warning, runs the release at once, detached, and panics the registering computation with `Closed`: the resource was released instead of leaked, but its user is told it no longer has one. This happens to `Fiber.initUnscoped` fibers that capture a scope, and it is the reason to prefer `Fiber.init`.

### Where the guarantee starts and stops

`acquireRelease` registers the release in the same step that delivers the acquired value, so no interrupt can land between the two. The edges of that guarantee are specific:

- **Only the returned value is covered.** If the acquire opens a socket and then a session, and is interrupted between them, the socket is the acquire's to clean up. Split it into two `acquireRelease` calls, one per resource.
- **An acquire that joins a fiber or a promise is not covered.** An interrupt that lands after the join and before the acquiring fiber resumes drops the value with nothing registered. Register the release inside the fiber that produces the value, so the value never travels unowned.
- **`*Unscoped` constructors hand over a resource with nothing registered.** Between receiving it and registering a release, the caller is unprotected. Prefer the scoped constructor wherever one exists.
- **Work that must finish once started goes in `Async.uninterruptible`.** It runs to its end even if the caller is interrupted, but the interrupted caller stops waiting at once, so the protected work must release anything it opens itself.

Under a handler that runs the rest of the computation more than once, such as `Choice.run`, a scope opened around the choice point is shared by every branch: each branch's registrations are kept, and all of them release once, after the last branch. A scope opened inside a branch closes at the end of that branch. [kyo-kernel's README](../kyo-kernel/README.md#a-released-scope-entered-again) covers the underlying bracket semantics, including why a computation resumed after its scope has closed is refused rather than run against released resources.

## Talking between fibers

Fibers exchange data through bounded buffers (`Channel`), unbounded queues (`Queue`), broadcasts (`Hub`), and request multiplexers (`Exchange`). Pick by traffic pattern: one-to-one, one-to-many, many-to-many, request/response.

### Bounded MPMC buffers

When two fibers need a hand-off with backpressure, reach for `Channel`. It exposes synchronous (`offer`/`poll`) and parking (`put`/`take`) operations on a bounded MPMC buffer.

```scala
import kyo.*

val example: Unit < (Async & Scope & Abort[Closed]) =
    Channel.init[Order](capacity = 64).map { channel =>
        channel.put(Order(1L, 100L, Chunk.empty, BigDecimal(0))).andThen {
            channel.take.map { received =>
                Log.info(s"received order ${received.id}")
            }
        }
    }
```

`offer` and `poll` are non-blocking: `offer` returns `false` if the channel is full, `poll` returns `Absent` if empty.

`put` and `take` park the fiber until space is available or an element arrives. `putBatch(values)` puts a sequence atomically (items from one `putBatch` are kept contiguous in the channel). `takeExactly(n)` parks until it has taken exactly `n` items.

`drain` returns every currently-buffered element without parking, and `drainUpTo(max)` returns at most `max` of them.

`stream(maxChunkSize)` exposes the channel as `Stream[A, Abort[Closed] & Async]`. Use `streamUntilClosed` if you want a clean termination instead of a `Closed` failure on close.

The `access` parameter at `Channel.init` selects an internal representation tuned to the producer/consumer pattern. `Access` is an enum:
- `Access.MultiProducerMultiConsumer` (default): any pattern.
- `Access.MultiProducerSingleConsumer`: many producers, one consumer.
- `Access.SingleProducerMultiConsumer`: one producer, many consumers.
- `Access.SingleProducerSingleConsumer`: most restrictive, often fastest.

> **Note:** from 2 up, `Channel` capacity is rounded up to the next power of two on every platform, so capacity 10 becomes 16. Capacity 1 stays 1, and a capacity of 0 or less makes a rendezvous channel, where each `put` waits for a matching `take`.

> **Caution:** A `Channel` has no upper bound on the number of fibers suspended on it. In an HTTP-per-request pattern the queue of waiters can grow unbounded even when the channel's element capacity is bounded. Combine with `Admission` or `Meter` at the boundary if request rate is unbounded.

#### Closing a channel: `close` vs `closeAwaitEmpty`

`close` immediately fails pending consumers with `Closed` and returns any buffered elements:

```scala
import kyo.*
val channel: Channel[Order] = ???

val remaining: Maybe[Seq[Order]] < Async = channel.close
```

`closeAwaitEmpty` closes the channel to new producers and waits until all buffered elements have been consumed:

```scala
import kyo.*
val channel: Channel[Order] = ???

val drained: Boolean < Async = channel.closeAwaitEmpty
```

Use `close` when consumers should learn the source is gone now (shutdown on error), and `closeAwaitEmpty` when they should finish the work already enqueued (graceful shutdown).

### Lock-free queues with overflow policies

`Channel` parks fibers on a full buffer; sometimes you want overflow handling instead. `Queue` is the lower-level lock-free queue underneath `Channel`, and `Queue.Unbounded` exposes the policies `Channel` doesn't:

```scala
import kyo.*

val q: Queue[Order] < (Sync & Scope) =
    Queue.init[Order](capacity = 1024)

val drop: Queue.Unbounded[Order] < (Sync & Scope) =
    Queue.Unbounded.initDropping[Order](capacity = 1024)

val slide: Queue.Unbounded[Order] < (Sync & Scope) =
    Queue.Unbounded.initSliding[Order](capacity = 1024)
```

`Queue.Unbounded.initDropping(capacity)` discards a new element when full; `offer` still returns `true`, so the drop is silent. `Queue.Unbounded.initSliding(capacity)` evicts the oldest element to make room. `Queue.Unbounded.init` has no upper bound.

> **Caution:** `Queue.Unbounded.init` can exhaust memory if producers outpace consumers indefinitely. Prefer `initDropping` or `initSliding` unless an external mechanism enforces a bound.

Like `Channel`, `Queue` has the same `close` (close at once and return the buffered elements) vs `closeAwaitEmpty` (close to new offers and wait until all buffered elements have been consumed) distinction. Use `close` for immediate shutdown and `closeAwaitEmpty` for graceful draining.

### Broadcast fan-out

When one producer needs to feed many independent listeners (log auditors, metrics, replicas), use `Hub`. Every listener gets every value, with per-listener buffers and listener-driven backpressure.

```scala
import kyo.*

val example: Unit < (Async & Scope & Abort[Closed]) =
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
// The workers run on their own fibers; the coordinator waits on the latch, not on them.
val example: Unit < (Async & Scope) =
    Latch.init(3).map { latch =>
        Kyo.foreachDiscard(1 to 3) { i =>
            Fiber.init(doWork(i).andThen(latch.release))
        }.andThen {
            latch.await.andThen(Log.warn("all three done"))
        }
    }

def doWork(i: Int): Unit < Sync = ???
```

> **Note:** A `Latch` initialized with a count `<= 0` is a no-op: all `await` calls complete immediately.

### `Gate`: symmetric multi-party barrier

When N parties must all reach a rendezvous point before any proceeds, use `Gate`. `Gate.init(parties)` blocks every party until `parties` parties have arrived, then releases them all. Similar to `CyclicBarrier`. Gates reuse across phases; once released, all parties can pass again on the next cycle.

```scala
import kyo.*

val example: Unit < (Async & Scope & Abort[Closed]) =
    Gate.init(parties = 4).map { gate =>
        // every party must be running at once to reach the gate together
        Async.foreachDiscard(1 to 4, concurrency = 4) { worker =>
            prepare(worker).andThen {
                gate.pass.andThen(execute(worker))
            }
        }
    }

def prepare(w: Int): Unit < Sync = ???
def execute(w: Int): Unit < Sync = ???
```

`Gate.Dynamic.init(parties)` is the variant where parties can join and leave at any time, and `subgroup` derives a gate for a subset of the parties.

`Latch` is asymmetric: some parties release, others wait. `Gate` is symmetric: all parties pass together.

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

Use `meter.run(v)` to execute a computation under the meter, parking until a permit is available. `meter.tryRun(v)` returns `Maybe[A]` and skips the work if no permit is available.

```scala
import kyo.*
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

`Meter.pipeline(m1, m2, ...)` composes any number of meters: a request must acquire each in order. The typical use is "at most 10 concurrent operations and no more than 100 per second":

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

When downstream code must react to value changes (UI state, config reload, feature flags), use `Signal`. It exposes a mutable cell (`SignalRef[A]`) whose changes propagate to subscribers.

```scala
import kyo.*

val example: Unit < Async =
    Signal.initRef(0).map { (count: SignalRef[Int]) =>
        Async.foreachDiscard(1 to 100) { _ =>
            count.updateAndGet(_ + 1).unit
        }.andThen {
            count.current.map(c => Log.info(s"final: $c"))
        }
    }
```

`signal.current` reads the current value (`Sync`). `signal.next` parks until the value changes (`Async`). `signal.streamChanges` emits the current value, then each new value that differs from the last one emitted. `signal.streamCurrent` samples the current value repeatedly without waiting for a change, so it is a polling stream to bound with `take` or a schedule.

> **Caution:** `Signal.streamChanges` may skip intermediate values under load. The stream guarantees latest-value semantics, not every-change-observed semantics. For capture-every-change cases use a `Channel` instead.

`Signal.initConst(value)` produces a `Signal` that never changes: useful as a placeholder or a sentinel.

Signals also compose as values. `signal.map(f)` derives a signal, `a.combineLatest(b)` pairs two and updates when either changes, and `a.zip(b)` updates only once both have changed. `signal.observe(f)` runs `f` for the current value and again on every change, each time inside a fresh `Scope` that is closed before the next value's `f` runs, so whatever `f` forks for one value is interrupted when the value changes. `observe` runs until interrupted, so fork it.

## Shared mutable state

Across fibers, state lives in atomics, contended-write counters, or a bounded cache.

### Atomic primitives

`AtomicInt`, `AtomicLong`, `AtomicBoolean`, and `AtomicRef[A]` are effect-typed wrappers around `java.util.concurrent.atomic`. Every operation returns `... < Sync`.

```scala
import kyo.*

val counter: Long < Sync =
    AtomicLong.init(0).map { ref =>
        ref.incrementAndGet.andThen(ref.incrementAndGet).andThen(ref.get)
    }

val refExample: Order < Sync =
    AtomicRef.init(Order(1L, 100L, Chunk.empty, BigDecimal(0))).map { ref =>
        ref.updateAndGet(o => o.copy(total = o.total + BigDecimal(10)))
    }
```

All four expose `get`, `set`, `lazySet`, `getAndSet`, and `compareAndSet(curr, next)`; `AtomicInt`, `AtomicLong`, and `AtomicRef` add `getAndUpdate(f)` and `updateAndGet(f)`. The integer types add `incrementAndGet`, `decrementAndGet`, `getAndIncrement`, `getAndDecrement`, `getAndAdd`, `addAndGet`, etc.

### Contention-optimized counters

When many fibers update a counter and reads are rare, `LongAdder` and `DoubleAdder` outperform atomics. `LongAdder` has fast writes (lock-free striped counter) but slower reads (must sum all stripes); `AtomicLong` is opposite: cheap reads, contended writes serialize.

```scala
import kyo.*

val counted: Long < Async =
    LongAdder.init.map { adder =>
        Async.foreachDiscard(1 to 1_000_000)(_ => adder.increment)
            .andThen(adder.get)
    }
```

Pick `LongAdder` when many fibers increment and the value is read infrequently (request counters, hit counters). Pick `AtomicLong` when reads dominate or you need compare-and-set.

### Bounded caches and memoization

When repeated work on the same key should reuse a previous result, reach for `Cache`. `Cache.init(maxSize, expireAfterAccess, expireAfterWrite)` creates a bounded cache. When it is full, eviction uses the CLOCK algorithm: an approximation of least-recently-used that gives each recently read entry one more pass before it is evicted. `Cache.initWithFinalizer` is the scoped variant for values that must be closed: it runs the finalizer once for every value removed by any path (eviction, expiry, `remove`, or the scope closing the cache), on a background fiber so eviction never waits for it.

```scala
import kyo.*
case class User(id: Long, name: String)

val lookups: Maybe[User] < Sync =
    Cache.init[Long, User](maxSize = 10_000, expireAfterAccess = 5.minutes).map { cache =>
        cache.add(42L, User(42L, "alice")).andThen(cache.get(42L))
    }
```

`getOrElse(key, value)` returns the cached value or evaluates the default and inserts it. `add(key, value)` inserts only when the key is absent and returns the value the cache holds, so it never overwrites. `remove(key)` removes the entry at once.

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

When you need a timestamp for humans or other systems (log lines, database TTLs), use `Clock.now`. When you need to measure elapsed time without surprises, use `Clock.nowMonotonic`. The wall-clock can jump (NTP adjustment, leap seconds) and go backwards; the monotonic clock only increases, so the difference between two readings is a usable duration. Across a system suspend the monotonic clock may stop while the wall clock jumps forward, so neither measures time spent suspended.

```scala
val stamped: (Instant, Duration) < Sync =
    Clock.now.map { wall =>
        Clock.nowMonotonic.map(mono => (wall, mono))
    }
```

### Measuring elapsed time and tracking deadlines

```scala
val measured: (Result[Throwable, Int], Duration) < Async =
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

`Clock.repeatWithDelay` and `Clock.repeatAtInterval` start the loop on a background fiber and return that fiber at once. The fiber is unscoped: nothing stops it until the caller interrupts it, so acquire it with its interrupt as the release:

```scala
val pollEverySec: Fiber[Unit, Any] < (Sync & Scope) =
    Scope.acquireRelease(Clock.repeatWithDelay(1.second)(checkHealth))(_.interrupt)

def checkHealth: Unit < Async = ???

val tickOnSchedule: Fiber[Unit, Any] < (Sync & Scope) =
    Scope.acquireRelease(Clock.repeatAtInterval(1.second)(emitMetric))(_.interrupt)

def emitMetric: Unit < Async = ???
```

`repeatWithDelay(d)` runs the task, waits `d`, and runs it again, so a slow task pushes the next start out. `repeatAtInterval(d)` schedules runs by the interval `d`, with an optional initial delay in its other overloads. Runs of either never overlap.

### Deterministic time for tests

`Clock.withTimeControl` gives deterministic time within a scope, which is what tests should use. `Clock.withTimeShift` scales the live clock instead: faster or slower, but still real time.

```scala
// An hour of sleeping takes 3.6 real seconds.
val fastForward: Unit < Async =
    Clock.withTimeShift(factor = 1000.0) {
        Async.sleep(1.hour).andThen(Log.warn("done"))
    }
```

`withTimeShift(factor)` runs the body with the clock advancing `factor` times faster. `withTimeControl(f)` gives `f` direct control over the clock: `f` receives a `TimeControl` it can advance manually.

> **Caution:** `Clock.TimeControl` is not thread-safe. All operations must be performed sequentially within a single fiber.

### `Retry`

```scala
import kyo.*
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

`Retry[E](v)` uses `Retry.defaultSchedule`: exponential backoff starting at 100ms, factor 2, max 5 seconds, 0.2 jitter, and at most 3 retries, so 4 attempts in all. `Retry[E](schedule)(v)` accepts any `Schedule`. Only failures of type `E` are retried; a panic ends the retry at once.

`Schedule` is defined in `kyo-data`. Policies are built from `fixed`, `linear`, `exponential`, `exponentialBackoff`, or `fibonacci`, bounded with `take` or `maxDuration`, randomized with `jitter`, sequenced with `andThen`, and combined with `max` (the longer delay of two, stopping when either stops) or `min` (the shorter delay, continuing while either does).

### `Timeout`

`Async.timeout(d)(v)` adds `Abort[Timeout]` to the effect row, and the `Timeout` error's message names the duration that expired:

```scala
val withTimeout: Result[Timeout, Int] < Async =
    Abort.run[Timeout] {
        Async.timeout(2.seconds) {
            Async.sleep(5.seconds).andThen(42)
        }
    }
```

`Async.timeoutWithError(d, error)(v)` lets you raise a domain-specific error on expiry instead.

## Files, processes, and the OS

> **File system, processes, and environment:** `Path`, `Command`, `Process`, `System`, and the
> `FileSystemException` hierarchy live in [`kyo-system`](../kyo-system/README.md); add it to your
> dependencies to use them.

## Ambient services

`Console`, `Random`, `SecureRandom`, `UUIDGenerator`, and `Log` are dynamically scoped context services. Their defaults target the platform console, a non-cryptographic `java.util.Random`, the platform's cryptographic random source, secure UUID entropy drawn from the ambient `SecureRandom` (so `SecureRandom.let` changes it too), and the console logger respectively. Tests can swap them out per scope without threading them as arguments.

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

`flush` is a method on a `Console` value (reach it with `Console.use`), and the live console's `flush` flushes stdout. `Console.withIn(lines)(v)` runs `v` with a stub that replays the provided lines as `readLine` input, useful in tests. `Console.withOut(v)` captures all print output from `v` into a buffer and returns it alongside the result.

`Console.let(c)(v)` runs `v` with `c` as the ambient console: useful for testing (capture output to a buffer) and for redirection.

### UUID generation

`UUID` is the pure value type from `kyo-data`. It provides canonical parsing and formatting, network-order bytes, and the deterministic name-based constructors `UUID.v5` and `UUID.v8Sha256`. Effectful generation is provided by the secure `UUIDGenerator` capability:

```scala
val randomId: UUID < Sync =
    UUID.v4

val randomIdText: String < Sync =
    UUID.v4String

val timeOrderedId: UUID < Sync =
    UUID.v7

val generator: UUIDGenerator =
    UUIDGenerator.live

val scopedId: UUID < Sync =
    UUID.let(generator)(UUID.v4)
```

`UUIDGenerator.live`, the default generator, uses cryptographic platform entropy. Its version 7 implementation combines secure entropy with the Kyo clock and preserves strict monotonic ordering per generator instance when the clock repeats or moves backward. Entropy and clock failures remain `Sync` panics; the live generator never falls back to `Random`, timestamps alone, or process counters.

`UUID.v4`, `UUID.v4String`, and `UUID.v7` delegate to the currently scoped generator. `UUID.v4String` generates a version 4 value and renders its canonical lowercase text. The equivalent capability-first entry points are `UUIDGenerator.v4`, `UUIDGenerator.v7`, and `UUIDGenerator.let`.

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

`Random` provides non-cryptographic random values, sampling, shuffling, and UUID-formatted strings. `Random.uuid` is non-cryptographic; use `UUID.v4` or `UUID.v7` for secure UUID generation. It exposes `nextInt`, `nextInt(bound)`, `nextLong`, `nextDouble`, `nextFloat`, `nextBoolean`, `nextGaussian`, `nextValue(seq)`, `nextValues(length, seq)`, `nextStringAlphanumeric(length)`, `nextString(length, chars)`, `nextBytes(length)`, `shuffle(seq)`, and `uuid`.

For deterministic tests: `Random.withSeed(seed)(v)` runs `v` with a seeded RNG; `Random.let(r)(v)` substitutes a custom `Random` instance for the scope.

### `Log`

`Log` is the ambient logger. `Log.live` is the default backend: a `ConsoleLogger` named `kyo.logs` at `warn` level, configurable with `-Dkyo.Log.defaultLevel` or `KYO_LOG_DEFAULTLEVEL`. At the default level, `trace`, `debug`, and `info` calls print nothing. It writes `warn` and `error` to stderr (with stack traces to stderr) and `trace`, `debug`, and `info` to stdout. Each line is prefixed with a timestamp from the ambient `Clock`, so time control applies to it. Log calls are async by default on JVM and Native: each call enqueues to a bounded background channel (capacity 4096) and returns at once; a daemon fiber drains the channel in FIFO order. When the channel is full, the default overflow policy writes that event inline on the caller instead of dropping it. `Log.flush: Unit < Async` suspends until the daemon has delivered every enqueued event. To force synchronous logging, set `-Dkyo.Log.asyncLogging=false`.

```scala
import kyo.*
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

Three error types recur across the module. Two appear in `Abort` rows:

- `Closed`: raised by `Channel`, `Queue`, `Hub`, `Meter`, `Gate`, and `Exchange` when the underlying resource is closed or an operation is attempted after close, and panicked by `Scope` for a registration on a closed scope. Its message names the resource and the frame where it was created.
- `Timeout`: produced by `Async.timeout(d)(v)` and `Fiber.block(duration)` on expiry. Its message names the duration.

The third arrives as a panic rather than a typed failure:

- `Interrupted`: an interrupt ends the interrupted computation with `Result.Panic(Interrupted(frame))`, where the frame is where the interrupt was issued.

`KyoApp.FailureException` wraps a non-`Throwable` `Abort` error that escapes an application's `run` block or `KyoApp.Unsafe.runAndBlock`, so it can travel as a `Throwable`; `KyoApp.Unsafe.runAndBlock` returns it inside its `Result`.

Handle them per-effect with `Abort.run[Closed]`, `Abort.recover[Timeout]`, and so on:

```scala
import kyo.*
val channel: Channel[Order] = ???

val handled: Maybe[Order] < Async =
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

`Counter` exposes `inc`, `add(v)`, `get`. `Histogram` exposes `observe(v)` and `summary`, a non-destructive read of the whole distribution. `Gauge` and `CounterGauge` are read-only views: the registry calls the provided thunk on each scrape.

The `init*` methods register, and registration is first-writer-wins. That makes them the wrong call for reading a metric somebody else publishes: `initGauge` supplies the thunk, so a consumer that calls it before the producer wins the path and the producer's real value becomes unreachable for the life of the process. To read without registering, ask:

```scala
val orders = Stat.initScope("kyo", "orders")

val hostCpu: Maybe[Histogram] < Sync = Stat.initScope("machine", "cpu").findHistogram("total.rate")
val missing: Maybe[Counter] < Sync   = orders.findCounter("processsed") // Absent; the typo registers nothing
```

`findCounter`, `findGauge` and `findHistogram` answer `Absent` when nothing is registered at that name and never create anything, so a misspelled path fails visibly instead of becoming a brand-new zeroed instrument that an exporter publishes and a dashboard renders as a permanent flat line.

`stats.traceSpan(name, attributes)(v)`, on a `Stat` scope, wraps a computation in a trace span exported through the registered exporters.

`Stat.traceListen(exporter)(v)` registers an exporter for the duration of `v`. Exporters implement `kyo.stats.internal.TraceExporter` from kyo-stats-registry, which `import kyo.*` does not bring in; kyo-stats-otlp provides one for OpenTelemetry.

### `StreamCoreExtensions`

`StreamCoreExtensions` adds the stream operators that need fibers, imported with `kyo.*`:

- **Concurrent mapping:** `mapPar` and `mapChunkPar` keep input order, and `mapParUnordered` and `mapChunkParUnordered` emit results as they complete.
- **Merging:** `Stream.collectAll` runs many streams at once, and `merge`, `mergeHalting`, `mergeHaltingLeft` and `mergeHaltingRight` combine two, differing in which side's end stops the result.
- **Fan-out:** `broadcast2` through `broadcast5` and `broadcastN` split one stream into a fixed number of copies, and `broadcastDynamic` returns a `StreamHub` that later subscribers join.
- **Batching:** `groupedWithin(maxSize, maxTime)` emits a batch when it is full or when the time runs out, whichever comes first.

Operators that buffer between fibers default to a buffer of 1024 elements.

`Stream.fromInputStream` turns a `java.io.InputStream` into scoped byte chunks and closes it when the enclosing `Scope` ends:

```scala
import kyo.*

def source: java.io.InputStream = ???

val bytes: Stream[Byte, Sync & Scope] =
    Stream.fromInputStream(source, bufferSize = 8.kib)
```

`bufferSize` is a `ByteSize`, so a read buffer reads as `8.kib` rather than as a bare number. It is clamped to the range an array can address: `ByteSize.Zero` reads one byte at a time rather than spinning on a buffer that holds nothing, and anything above `Int.MaxValue` bytes reads through the largest buffer there is.

### `StreamCompression` (JVM only)

`StreamCompression` is a JVM-only object (in `kyo-core/jvm`) that adds gzip and deflate operators directly to `Stream[Byte, Ctx]` via an extension. The operators are available after `import kyo.StreamCompression.*`.

- `stream.deflate(...)` compresses bytes with deflate, zlib-framed by default (`noWrap = true` for raw deflate), and returns `Stream[Byte, Scope & Sync & Ctx]`.
- `stream.inflate(...)` decompresses deflate data, zlib-framed unless `noWrap = true`, and returns `Stream[Byte, Sync & Scope & Ctx & Abort[StreamCompressionException]]`.
- `stream.gzip(...)` compresses bytes with the gzip framing (header + CRC-32 trailer) and returns `Stream[Byte, Scope & Sync & Ctx]`.
- `stream.gunzip(...)` decompresses a gzip stream, validates the trailer, and returns `Stream[Byte, Sync & Scope & Ctx & Abort[StreamCompressionException]]`.

Compression behavior is tuned through three enums nested in `StreamCompression`: `CompressionLevel` (`Default`, then `NoCompression`, `BestSpeed`, `Level2` through `Level8`, and `BestCompression`), `CompressionStrategy` (`Default`, `Filtered`, `HuffmanOnly`), and `FlushMode` (`Default`, `NoFlush`, `SyncFlush`, `FullFlush`). The last two also carry `BestSpeed` and `BestCompression` aliases for the settings that suit each goal. Decompression failures surface as `StreamCompressionException`.

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

The example below combines several effects from this module into one program: `KyoApp` discharges the effect row at the application boundary, `Meter.initRateLimiter` enforces a system-wide rate limit, `Async.foreachDiscard` fans out work with bounded concurrency, and `Log.warn` reports each charge. Everything composes into a single value that `KyoApp` then runs.

```scala
import kyo.*

case class Txn(id: String)
class ChargeError extends Exception

object Checkout extends KyoApp:
    run {
        val orders: Chunk[Order] = loadPending

        // Bounded-concurrency fan-out: rate-limit charges to 50/sec
        Meter.initRateLimiter(rate = 50, period = 1.second).map { limiter =>
            // Process each order: charge, persist receipt, log
            Async.foreachDiscard(orders, concurrency = 16) { order =>
                limiter.run(charge(order)).map { txn =>
                    persistReceipt(order.id, render(order, txn)).andThen {
                        Log.warn(s"order ${order.id} -> ${txn.id}")
                    }
                }
            }
        }
    }

    def loadPending: Chunk[Order]                              = ???
    def charge(o: Order): Txn < (Async & Abort[ChargeError])   = ???
    def render(o: Order, t: Txn): String                       = ???
    def persistReceipt(id: Long, content: String): Unit < Sync = ???
end Checkout
```

The `run` block's type is `Unit < (Async & Scope & Abort[Any])`, which `KyoApp` discharges; ending in `Unit` keeps `KyoApp` from printing a result.

## Low-level extension points

Most kyo-core primitives have an `Unsafe` tier (`Sync.Unsafe`, `Fiber.Unsafe`, `Channel.Unsafe`, `Queue.Unsafe`, `Cache.Unsafe`, `Exchange.Unsafe`, `Console.Unsafe`, `Latch.Unsafe`, ...). The `Unsafe` API skips the effect-tracking layer and works against raw values, gated by an `AllowUnsafe` evidence import. Application code should use the safe surface; the `Unsafe` API is for library integrations, performance-critical inner loops, and bridging into non-Kyo code.

Modules that provide their own application entry point, such as kyo-case-app, build on `kyo.internal.KyoAppRunner`, the trait `KyoApp` itself uses to register and run its `run` blocks.
