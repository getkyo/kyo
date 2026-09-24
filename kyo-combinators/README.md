# kyo-combinators

Every Kyo computation has the shape `A < S`: a value of type `A` pending one or more effects `S`. `kyo-combinators` is a layer of fluent extension methods on top of that one shape. Instead of writing `Abort.run(eff)`, `Fiber.init(eff)`, or `Async.sleep(d).andThen(eff)`, you write `eff.result`, `eff.fork`, or `eff.delay(d)`. The library adds no new core type and no new effect: it adds a postfix vocabulary for the effects already in `kyo-prelude` and `kyo-core` (`Abort`, `Async`, `Choice`, `Emit`, `Env`, `Scope`, `Sync`) and for converting emitted values into a `Stream`, plus a `Kyo.*` companion for constructing computations, including a `Poll` constructor.

The combinators cluster by which effect row they target. The receiver of each extension carries a type-pattern that constrains where it applies: `.fork` is defined on `A < (Abort[E] & Async & S)`, `.maybe` is defined on `A < (Abort[Absent] & S)`, `.handleChoice` is defined on `A < (S & Choice)`. The same call-site idiom (postfix method on the effect value) handles construction, handling, recovery, retry, repetition, lifecycle, parallel composition, and stream conversion.

The examples share a small order-processing domain:

```scala doctest:setup
enum Status:
    case Pending, Completed

case class Item(sku: String, qty: Int)
case class Order(id: Long, total: BigDecimal = BigDecimal(0), items: Seq[Item] = Seq.empty, status: Status = Status.Pending)
case class Profile(name: String)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")

trait OrderRepo:
    def lookup(id: Long): Option[Order] < Sync
    def fetch(id: Long): Order < Abort[OrderNotFound]

enum ShipmentEvent:
    case Shipped(sku: String)

val orderId: Long                                = 42L
val order: Order                                 = Order(orderId, BigDecimal(100), Seq(Item("sku1", 1), Item("sku2", 2)))
val repo: OrderRepo                              = ???
val load: Order < (Abort[OrderNotFound] & Async) = ??? // loads the order for orderId; most examples start here
val profileFor: Long => Profile < Async          = _ => ???

val shipments: Unit < Emit[ShipmentEvent] =
    Kyo.foreachDiscard(order.items)(item => Kyo.emit(ShipmentEvent.Shipped(item.sku)))
```

With it, a lookup that may find nothing becomes a typed failure, is retried with backoff, and falls back to a default:

```scala
val resilient: Order < Async =
    repo.lookup(orderId).map(found => Kyo.fromOption(found))
        .absentToFailure(OrderNotFound(orderId))
        .retry(Schedule.exponential(100.millis, 2.0).take(3))
        .recover(_ => Order(0L))
```

The rest of this README walks the combinators by cluster, starting with construction (lifting plain values into the effect row) through dependency injection (handling `Env[E]`), and ends with one chain that crosses several of them.

## Construction

The `Kyo` companion is the entry point for lifting plain values, callbacks, futures, optionality types, sequences, and resources into the right effect row. Every postfix combinator is an extension method on `A < S`, so you need an `A < S` first.

### Lifting plain values and side-effects

`Kyo.defer` suspends a thunk under `Sync`. `Kyo.fail` lifts an error value into `Abort[E]`. `Kyo.attempt` catches any `Throwable` thrown by the body into `Abort[Throwable]`.

```scala
val timestamp: Long < Sync =
    Kyo.defer(java.lang.System.currentTimeMillis())

val rejected: Nothing < Abort[OrderNotFound] =
    Kyo.fail(OrderNotFound(orderId))

val parsed: Int < Abort[Throwable] =
    Kyo.attempt("not a number".toInt)
```

### Async primitives

`Kyo.sleep(duration)` pauses under `Async`. `Kyo.never` is an `Async` computation that never completes (useful as a sentinel in races and timeouts). `Kyo.async` bridges a callback API into `Async`: the body receives a "register" function that the callback eventually calls with the result.

```scala
trait LegacyClient:
    def fetchOrder(id: Long, cb: Either[Throwable, Order] => Unit): Unit
val legacyClient: LegacyClient = ???

val pause: Unit < Async = Kyo.sleep(500.millis)

val sentinel: Nothing < Async = Kyo.never

val fromCallback: Order < (Abort[Throwable] & Async) =
    Kyo.async { register =>
        legacyClient.fetchOrder(
            orderId,
            {
                case Right(found) => register(found)
                case Left(err)    => register(Kyo.fail(err))
            }
        )
    }
```

> **Note:** `register` may be called from any thread, typically the callback API's own. Each call runs the computation passed to it on a fiber of its own, and the first one to finish completes the result; a later call still runs its computation, but its result is ignored. Interrupting the fiber waiting on `Kyo.async` also interrupts the registered computations, so a caller that gives up does not leave them running.

### Lifting standard-library types

Each `fromX` constructor lifts a standard-library result, optionality, sequence, or future type into the corresponding effect: `Abort` for results and optional values, `Choice` for sequences, `Async` for futures.

```scala
import scala.concurrent.Future
import scala.concurrent.Promise

case class ValidationError(msg: String) extends Exception(msg)

object orderCache:
    def get(id: Long): Maybe[Order] = Absent

trait LegacyRepo:
    def lookupNow(id: Long): Option[Order]
    def validate(raw: String): Either[ValidationError, Order]
    def lookupResult(id: Long): Result[OrderNotFound, Order]
    def lookupOrThrow(id: Long): Order
    def lookupAsync(id: Long): Future[Order]
val legacy: LegacyRepo           = ???
val rawJson: String              = "{}"
val orderPromise: Promise[Order] = Promise[Order]()

val fromOpt: Order < Abort[Absent] =
    Kyo.fromOption(legacy.lookupNow(orderId))

val fromMb: Order < Abort[Absent] =
    Kyo.fromMaybe(orderCache.get(orderId))

val fromEi: Order < Abort[ValidationError] =
    Kyo.fromEither(legacy.validate(rawJson))

val fromRes: Order < Abort[OrderNotFound] =
    Kyo.fromResult(legacy.lookupResult(orderId))

val fromTr: Order < Abort[Throwable] =
    Kyo.fromTry(scala.util.Try(legacy.lookupOrThrow(orderId)))

val fromFut: Order < Async =
    Kyo.fromFuture(legacy.lookupAsync(orderId))

val fromPr: Order < Async =
    Kyo.fromPromiseScala(orderPromise)

val fromSq: Item < Choice =
    Kyo.fromSeq(order.items)
```

`fromOption` and `fromMaybe` both route absence to `Abort[Absent]`; the choice between them is the input type. `fromEither` and `fromResult` preserve the error type; `fromTry` collapses to `Abort[Throwable]` because `Try` only carries `Throwable`.

`fromOption`, `fromMaybe`, `fromEither`, and `fromResult` take an already-computed value. To retry or repeat the lookup that produces it, lift the lookup itself and map into the constructor, as the opening example does with `repo.lookup(orderId).map(found => Kyo.fromOption(found))`; retrying `Kyo.fromOption(value)` only re-checks the same value.

`fromSeq` expands each element of the sequence into a non-deterministic branch of `Choice`. The downstream computation will be evaluated for every element (see [Non-determinism](#non-determinism-choice)).

### Resource lifecycle (constructing the resource)

Lifecycle constructors register cleanup with `Scope`. The acquired value is in scope until the surrounding `Scope.run` exits, at which point the registered finalizer runs.

```scala
import java.sql.Connection

val url: String = "jdbc:postgresql://localhost/db"

trait Database:
    def begin: Connection < Sync
val db: Database = ???

def loadWith(conn: Connection, id: Long): Order < Sync = Kyo.defer(Order(id))

val open: Connection < (Scope & Sync) =
    Kyo.fromAutoCloseable(java.sql.DriverManager.getConnection(url))

// The release rolls back anything the body did not commit, then closes.
val txn: Connection < (Scope & Sync) =
    Kyo.acquireRelease(db.begin)(conn => Kyo.defer { conn.rollback(); conn.close() })

val installShutdown: Unit < (Scope & Sync) =
    Kyo.addFinalizer(Kyo.logInfo("server shutting down"))

val scoped: Order < (Async & Sync) =
    Kyo.scoped {
        txn.map(conn => loadWith(conn, orderId).map(loaded => Kyo.defer(conn.commit()).andThen(loaded)))
    }
```

`fromAutoCloseable` is the shortcut for the common case (Java AutoCloseable). `acquireRelease` is the general form: you control both the acquisition effect and the cleanup effect. `addFinalizer` registers cleanup with no associated resource. `scoped` discharges the `Scope` effect, running registered finalizers when the inner effect completes.

`ensuring` (described under [Resource lifecycle](#resource-lifecycle)) is the symmetric "attach cleanup to a computation" combinator.

### Env constructors (reading dependencies)

When a computation needs a service the caller supplies, read it from `Env`. `Kyo.service[D]` returns the service itself; `Kyo.serviceWith[D](f)` reads it and continues with `f` in one step.

```scala
val readRepo: OrderRepo < Env[OrderRepo] =
    Kyo.service[OrderRepo]

val loadOrder: Order < (Env[OrderRepo] & Abort[OrderNotFound]) =
    Kyo.serviceWith[OrderRepo](_.fetch(orderId))
```

Supplying the service is covered under [Dependency injection](#dependency-injection-env).

### Parallel fan-out

`Kyo.foreachPar`, `foreachParDiscard`, `collectAllPar`, `collectAllParDiscard` run a sequence of effects in parallel with a bounded concurrency.

```scala
case class Stock(sku: String, available: Int)

object inventory:
    def check(sku: String): Stock < (Abort[Throwable] & Async) = ???
    def record(sku: String): Unit < (Abort[Throwable] & Async) = ???

val stockChecks: Chunk[Stock] < (Abort[Throwable] & Async) =
    Kyo.foreachPar(order.items)(item => inventory.check(item.sku))

val recorded: Unit < (Abort[Throwable] & Async) =
    Kyo.foreachParDiscard(order.items, concurrency = 4)(item => inventory.record(item.sku))

val profiles: Seq[Profile] < Async =
    Kyo.collectAllPar(Seq(profileFor(1L), profileFor(2L)))

val warmed: Unit < Async =
    Kyo.collectAllParDiscard(Seq(profileFor(1L), profileFor(2L)).map(_.unit), concurrency = 2)
```

`concurrency` defaults to `Async.defaultConcurrency`; pass a value to raise or lower the cap.

### Emit and Poll

`Emit` and `Poll` are the push and pull halves of streaming. `Kyo.emit` and `Kyo.poll` are their single-value constructors, for building a producer or consumer by hand.

```scala
case class Heartbeat(ts: Long)

val ping: Unit < Emit[Heartbeat] =
    Kyo.emit(Heartbeat(java.lang.System.currentTimeMillis()))

val one: Maybe[Order] < Poll[Order] =
    Kyo.poll[Order]
```

`Kyo.emit` produces a single value into the `Emit[A]` effect; multiple `emit` calls in sequence produce a sequence of values (see [Emit, streaming, and conversion](#emit-streaming-and-conversion)). `Kyo.poll` requests one value through the `Poll[A]` effect and returns `Absent` once the producer is done.

### Logging

`Kyo.log*` are shortcuts for kyo-core's `Log` at each level; they write to whatever logger is in scope. The default logger writes to the console at `warn` level, so `info` and below print nothing until the level is lowered with `-Dkyo.Log.defaultLevel` (or `KYO_LOG_DEFAULTLEVEL`), or a console logger at `debug` is installed with `Log.withConsoleLogger`.

```scala
val ex: Throwable = new RuntimeException("payment failed")

val logInfo2: Unit < Sync  = Kyo.logInfo("processing order")
val logWarn2: Unit < Sync  = Kyo.logWarn("payment retry", new RuntimeException("network"))
val logDebug2: Unit < Sync = Kyo.logDebug(s"order=$order")
val logError2: Unit < Sync = Kyo.logError("payment failed", ex)
val logTrace2: Unit < Sync = Kyo.logTrace("entering checkout")
```

Each variant has a `(String)` and `(String, Throwable)` overload. All run under `Sync`.

## Sequencing and combining

These extensions live on `A < S` for any effect row. They compose two effects sequentially, gate one on another, or weave a side effect through a result.

### Sequential zip: `*>`, `<*`, `<*>`

```scala
val ignoreFirst: Order < (Abort[OrderNotFound] & Async) =
    Kyo.logInfo("loading") *> load

val ignoreSecond: Order < (Abort[OrderNotFound] & Async) =
    load <* Kyo.logInfo("loaded")

val both: (Order, Profile) < (Abort[OrderNotFound] & Async) =
    load <*> profileFor(orderId)
```

`*>` keeps the second result; `<*` keeps the first; `<*>` keeps both as a tuple. `<*>` uses a `Zippable` typeclass to flatten nested tuples: `a <*> b <*> c` produces `(A, B, C)`, not `((A, B), C)`. A `Unit` side is dropped, so `a <*> Kyo.logInfo("x")` produces `A`, and a side whose value is already a tuple is spliced into the result rather than nested.

> **Note:** the parallel siblings `&>`, `<&`, `<&>` under [Concurrency](#concurrency-and-forking) look almost the same. The sequential operators evaluate the second effect after the first; `a &> b` runs both on separate fibers. The ampersand is the only call-site signal, so pick deliberately.

### `tap`: side-effect on the success value

```scala
val logged: Order < (Abort[OrderNotFound] & Async) =
    load.tap(loaded => Kyo.logInfo(s"loaded $loaded"))
```

`tap` runs `fn(a)` for its effect and discards the result, returning the original `a`. The function may itself be effectful; the tap's effect row is added to the carrier's.

### `when` / `unless`: conditional execution

```scala
case class Receipt(orderId: Long)

def sendReceipt(o: Order): Receipt < (Abort[Throwable] & Async) = ???
object orderCache:
    def contains(id: Long): Boolean < Sync = Kyo.defer(false)

val maybeNotify: Maybe[Receipt] < (Abort[Throwable] & Async) =
    sendReceipt(order).when(order.total > BigDecimal(0))

val loadIfUncached: Maybe[Order] < (Abort[OrderNotFound] & Async) =
    load.unless(orderCache.contains(orderId))
```

> **Note:** `when` and `unless` return `Maybe[A]`, not `Unit`. When the effect runs, the result is wrapped in `Present`; when it is skipped, the result is `Absent`. Users coming from Cats `whenA` may expect `Unit`. If you only care about the side-effect, follow with `.unit`.

## Repetition

Repetition primitives are orthogonal to error handling: they re-run an effect by count, by schedule, by predicate, or forever. None of them treat failure as a reason to re-run; for that, use [`retry`](#error-handling) instead.

### `repeat`: by count or schedule

```scala
val elevenRuns: Order < (Abort[OrderNotFound] & Async) =
    load.repeat(10)

val backedOff: Order < (Abort[OrderNotFound] & Async) =
    load.repeat(Schedule.exponential(100.millis, 2.0).take(5))

val polled: Order < (Abort[OrderNotFound] & Async) =
    load.repeatAtInterval(i => (i * 100).millis, limit = 5)
```

All three return the last run's result, and each runs the effect once more than its count:

- `repeat(n)` runs the effect once and then repeats it `n` times, so `repeat(10)` runs it eleven times.
- `repeat(schedule)` runs the effect once per schedule step, waiting the step's delay first, and once more when the schedule ends.
- `repeatAtInterval(backoff, limit)` sleeps `backoff(i)` before each of the first `limit` runs, then runs once more.

> **Note:** `repeat(Int)` adds no effect to the row; `repeat(Schedule)` adds `Async` because the schedule inserts delays. If you switch from a count to a schedule, expect the effect row to grow.

### `repeatWhile` / `repeatUntil`: predicate-driven

```scala
val pollPending: Order < (Abort[OrderNotFound] & Async) =
    load.repeatWhile(_.status == Status.Pending)

val pollWithDelay: Order < (Abort[OrderNotFound] & Async) =
    load.repeatWhile { (loaded, iter) =>
        (loaded.status == Status.Pending, (iter * 50).millis)
    }

val waitForCompletion: Order < (Abort[OrderNotFound] & Async) =
    load.repeatUntil(_.status == Status.Completed)

val waitWithDelay: Order < (Abort[OrderNotFound] & Async) =
    load.repeatUntil { (loaded, iter) =>
        (loaded.status == Status.Completed, (iter * 50).millis)
    }
```

The simpler overload takes a predicate `A => Boolean < S1`, which may itself be effectful. The richer overload takes `(A, Int) => (Boolean, Duration) < S1`: the `Int` is the iteration index and the returned `Duration` is the sleep before the next iteration. `repeatUntil` adds `Async` to the row in both forms; `repeatWhile` adds it only in the richer one, because of the sleep.

### `forever`: infinite repetition

```scala
val heartbeat: Nothing < Async =
    Kyo.sleep(1.second).forever
```

The return type is `Nothing` because the loop never produces a final value.

### `delay`: postpone before running

```scala
val later: Order < (Abort[OrderNotFound] & Async) =
    load.delay(500.millis)
```

`delay` sleeps once before evaluating the effect, and adds `Async` to the effect row because of the sleep.

## Error handling

Everything below applies to `A < (Abort[E] & S)`. The combinators are organized by what they do to the error: handle it (to a `Result` or `Maybe`), recover from it, fold over it, transform its type, route it to a different effect, retry on it, or convert it to a panic.

### `result`: handle to a `Result`

```scala
val handled: Result[OrderNotFound, Order] < Async =
    load.result
```

`result` discharges the `Abort[E]` effect entirely, exposing the success-or-failure as a value. The remaining effect row is `S` (in this case `Async`).

Two siblings exist for partial handling:

```scala
val partial: Result.Partial[OrderNotFound, Order] < (Abort[Nothing] & Async) =
    load.resultPartial

val partialThrowing: Result.Partial[OrderNotFound, Order] < Async =
    load.resultPartialOrThrow
```

`resultPartial` returns a `Result.Partial` (no `Panic` branch) but leaves panics tracked as `Abort[Nothing]`. `resultPartialOrThrow` returns a `Result.Partial` and throws on panic, discharging the `Abort[Nothing]` row.

### `recover` and `recoverSome`: replace failures

```scala
val safe: Order < Async =
    load.recover(_ => Order(0L))

val partialRecovery: Order < (Abort[OrderNotFound] & Async) =
    load.recoverSome {
        case OrderNotFound(0L) => Order(0L)
    }
```

`recover` handles all failures of type `E`; the result no longer tracks `Abort[E]`. `recoverSome` takes a `PartialFunction`: unmatched failures stay in the `Abort[E]` row. Use `recover` when you have a total handler, `recoverSome` when you handle only some failure shapes.

### `foldAbort` / `foldAbortOrThrow`: fold over outcome

```scala
val rendered: String < Async =
    load.foldAbort(
        onSuccess = loaded => s"order ${loaded.id}: ${loaded.total}",
        onFail = err => s"failed: $err"
    )

val renderedWithPanic: String < Async =
    load.foldAbort(
        onSuccess = loaded => s"order ${loaded.id}",
        onFail = err => s"failed: $err",
        onPanic = thr => s"panic: ${thr.getMessage}"
    )

val renderedThrowing: String < Async =
    load.foldAbortOrThrow(
        onSuccess = loaded => s"order ${loaded.id}",
        onFail = err => s"failed: $err"
    )
```

Three panic strategies: leave `Abort[Nothing]` in the row (two-arm `foldAbort`); handle it explicitly with a third arm; throw on panic (`foldAbortOrThrow`). Pick by what the caller needs to see.

### `mapAbort` and `swapAbort`: transform the error type

```scala
sealed trait ServiceError
object ServiceError:
    case class NotFound(id: Long) extends ServiceError

val mapped: Order < (Abort[ServiceError] & Async) =
    load.mapAbort(notFound => ServiceError.NotFound(notFound.id))

val swapped: OrderNotFound < (Abort[Order] & Async) =
    load.swapAbort
```

`mapAbort` is the error-side `map`: transform `E` to `E1` without affecting success. `swapAbort` exchanges success and error sides; the success becomes the new `Abort` failure type, which is useful when the failure is the value a test or a probe wants to inspect.

### `orPanic` / `orThrow` / `unpanic`: collapse to panic or back

```scala
val panickingTracked: Order < (Abort[Nothing] & Async) =
    load.orPanic

val panickingUntracked: Order < Async =
    load.orThrow

val caughtAgain: Order < (Async & Abort[Throwable]) =
    panickingUntracked.unpanic
```

> **Caution:** `orThrow` throws and does not track the panic in the effect row. Inside `Sync` or `Async`, that panic becomes invisible to the type system: a code path you didn't expect to fail will fail at runtime with no compile-time hint. `Sync` and `Async` track panics as `Abort[Nothing]`; preserve that tracking by using `orPanic` instead. `orThrow` is intended for pure (non-`Sync`, non-`Async`) effects.

`unpanic` is the inverse: catch any `Throwable` thrown at runtime (e.g. from `orThrow` or from a panicking sub-effect) and lift it back into `Abort[Throwable]`.

### `abortToChoiceDrop`, `abortToAbsent`, `abortToThrowable`: route to other effects

```scala
// abortToThrowable requires an error type that is not a Throwable
case class Missing(id: Long)
val find: Order < (Abort[Missing] & Async) = ???

val asChoice: Order < (Async & Choice) =
    find.abortToChoiceDrop

val asAbsent: Order < (Async & Abort[Absent]) =
    find.abortToAbsent

val asThrown: Order < (Async & Abort[Throwable]) =
    find.abortToThrowable
```

The first drops failures as empty `Choice` branches. The second collapses any failure to `Absent` (you lose the specific error value). The third lifts `E` into `Abort[Throwable]`, wrapping non-`Throwable` failures in `PanicException(error)`.

> **Note:** `abortToThrowable` requires `NotGiven[E <:< Throwable]` and does not compile on an error type that already extends `Throwable`. If `E` is already a `Throwable`, you don't need a conversion; you already have `Abort[Throwable]`.

> **Note:** Code that catches `Throwable` downstream of `abortToThrowable` / `orThrow` / `orPanic` will receive a `PanicException(originalError)` for non-`Throwable` original errors, not the original error value. Unwrap with a pattern match if you need to recover the original.

### `retry`: re-run on failure

```scala
val retriedN: Order < (Abort[OrderNotFound] & Async) =
    load.retry(3)

val retriedSched: Order < (Abort[OrderNotFound] & Async) =
    load.retry(Schedule.exponential(100.millis, 2.0).take(5))

val retriedForever: Order < Async =
    load.retryForever
```

`retry(n)` runs the effect and, on a failure or a panic, runs it again up to `n` more times; after the budget, the last failure is re-raised. `retry(Schedule)` re-runs with delays driven by the schedule, and retries failures only: a panic ends it at once. `retryForever` retries failures and panics, and returns a computation with no `Abort[E]` row because the only way to exit is success.

`recover` and `retry` together solve different problems. `recover` is "replace the error with a value." `retry` is "do it again." Combine them: `load.retry(3).recover(_ => Order(0L))` retries three times and falls back if all attempts fail.

### `forAbort[E1]`: handle one branch of a union

When the error type is a union (`Abort[E1 | E2 | E3]`), `forAbort[E1]` enters a narrowing DSL that lets you handle only that branch and leave the others in the effect row.

```scala
case class InventoryEmpty()                extends Exception("Inventory empty")
case class PaymentDeclined(reason: String) extends Exception(reason)
sealed trait ServiceError
object ServiceError:
    case class Payment(reason: String) extends ServiceError

type OrderError = OrderNotFound | InventoryEmpty | PaymentDeclined

val orderEffect: Order < (Abort[OrderError] & Async) = ???

// Recover from one branch, leave the others
val afterNotFound: Order < (Abort[InventoryEmpty | PaymentDeclined] & Async) =
    orderEffect.forAbort[OrderNotFound].recover(_ => Order(0L))

// Retry only on one branch
val retryInventory: Order < (Abort[OrderError] & Async) =
    orderEffect.forAbort[InventoryEmpty].retry(3)

// Map one branch to a different error
val mappedPayment: Order < (Abort[OrderNotFound | InventoryEmpty | ServiceError] & Async) =
    orderEffect.forAbort[PaymentDeclined].mapAbort(d => ServiceError.Payment(d.reason))

// Convert one branch to Absent
val asAbsent: Order < (Abort[InventoryEmpty | PaymentDeclined | Absent] & Async) =
    orderEffect.forAbort[OrderNotFound].toAbsent

// Convert one branch to a Choice drop
val asChoice: Order < (Abort[InventoryEmpty | PaymentDeclined] & Async & Choice) =
    orderEffect.forAbort[OrderNotFound].toChoiceDrop
```

`ForAbortOps` exposes a parallel surface to the top-level combinators: `result`, `resultPartial`, `recover`, `recoverSome`, `fold`, `mapAbort`, `swap`, `orPanic`, `toChoiceDrop`, `toAbsent`, `toThrowable`, `retry(Int)`, `retry(Schedule)`, `retryForever`. Each method applies to the selected branch `E1` and leaves the other branches in the row. The one behavioral difference from the top-level forms is that `retry(n)` and `retryForever` here retry `E1` failures only, not panics. Unlike the other `forAbort` methods, `recoverSome` keeps the whole union in the row, since an unmatched `E1` stays possible.

### `PanicException`: the panic wrapper

`PanicException[A]` (extends `KyoException`) is the wrapper used when lifting a non-`Throwable` error into a `Throwable`-typed `Abort`. It carries the original `error: A` as a field. You see it when:

- `abortToThrowable` lifts a non-`Throwable` `E`.
- `orThrow` / `orPanic` lift a non-`Throwable` `Failure` to a panic.
- `ForAbortOps.toThrowable` / `ForAbortOps.orPanic` do the same on one branch.

Match on the original error:

```scala
// Non-Throwable error types, as abortToThrowable requires
case class Missing(id: Long)
case class OutOfStock()
case class Declined(reason: String)

val attempt: Order < (Abort[Missing | OutOfStock | Declined] & Async) = ???

val recovered: Order < Async =
    attempt.abortToThrowable.recover {
        case PanicException(Missing(id)) => Order(id)
        case _                           => Order(orderId)
    }
```

## Optionality (Absent)

`Absent` is the canonical "no value" failure, and `Abort[Absent]` is the absence-as-error effect. The combinators below are specific to `Abort[Absent]`: every error-handling combinator described above also works on `Abort[Absent]`, but these are the convenient shapes when you don't need to carry an error message.

### `maybe`: handle to `Maybe[A]`

```scala
val lookup: Order < (Abort[Absent] & Sync) =
    repo.lookup(orderId).map(found => Kyo.fromOption(found))

val asMaybe: Maybe[Order] < Sync =
    lookup.maybe
```

`maybe` is the `Absent`-specific analogue of `result`: it discharges the `Abort[Absent]` row and returns a `Maybe[A]`. Use it when "missing" is a normal control-flow outcome and you don't want to inspect a `Result` whose only failure is `Absent`.

`maybe` only applies when `E` is exactly `Absent`. If you have `Abort[MyError]`, use `result` (returns `Result[MyError, A]`) instead.

### Routing absence to other effects

```scala
val lookup: Order < (Abort[Absent] & Sync) =
    repo.lookup(orderId).map(found => Kyo.fromOption(found))

val asChoice: Order < (Sync & Choice) =
    lookup.absentToChoice

val asNoSuchEl: Order < (Sync & Abort[NoSuchElementException]) =
    lookup.absentToThrowable

val asDomain: Order < (Sync & Abort[OrderNotFound]) =
    lookup.absentToFailure(OrderNotFound(orderId))
```

`absentToChoice` drops the branch in a `Choice` context. `absentToThrowable` substitutes a `NoSuchElementException`, useful when bridging to APIs that expect Java-style "not found." `absentToFailure(err)` lifts `Absent` into a domain error: pass the error value, get back `Abort[E]` instead.

## Concurrency and forking

Forking launches a computation into its own fiber. The Kyo runtime schedules the fiber on its work-stealing pool; the parent continues without blocking.

### `fork`, `forkUnscoped`, `forkUsing`

```scala
// All three apply to effects with Abort[E] & Async in their row.
val forked =
    load.fork

val forkedUnscoped =
    load.forkUnscoped

val usedFiber =
    load.forkUsing { fiber =>
        fiber.join.map(loaded => s"got ${loaded.id}")
    }
```

> **Caution:** `fork` requires `Scope` in the effect row and registers the fiber to be interrupted when the scope closes. `forkUnscoped` registers nothing: if the parent finishes before the child, the child keeps running as an orphan fiber. The shapes are identical (both return `Fiber[A, ...]`) but the lifetime guarantees are opposite. Reach for `fork` by default; use `forkUnscoped` only when you explicitly want the fiber to outlive the current scope.

`forkUsing` is the scoped form: it forks, hands you the fiber inside a function, and guarantees interruption when the function returns. Use it when you only need the fiber long enough to wait on it or race it with another effect.

### Parallel zip: `&>`, `<&`, `<&>`

```scala
val orderEff: Order < Sync     = Kyo.defer(order)
val profileEff: Profile < Sync = Kyo.defer(Profile("alice"))

val ignoreFirstPar =
    orderEff &> profileEff

val ignoreSecondPar =
    orderEff <& profileEff

val bothPar =
    orderEff <&> profileEff
```

Each side runs on a fiber of its own, so the two proceed concurrently. Effects other than `Abort` and `Async` that a side carries (here `Sync`) cross to its fiber through an `Isolate`, which the compiler must be able to find for them. `<&>` returns both results as a tuple, flattened by `Zippable` like `<*>`; `&>` and `<&` return one side's result.

### `Fiber#join` and `Fiber#await`

Once you have a `Fiber[A, ...] < S` (typically from `.fork`), two combinators let you wait on it:

```scala
val joined: Order < (Sync & Async & Scope & Abort[OrderNotFound]) =
    load.fork.join

val awaited: Result[OrderNotFound, Order] < (Sync & Async & Scope) =
    load.fork.await
```

`join` propagates the fiber's failure into the calling fiber (the error row stays `Abort[E]`). `await` exposes the outcome as a `Result[E, A]` value: no `Abort` is propagated, the caller inspects the result.

`join` is the right default when you want the parent fiber to fail if the child fails. `await` is the right default when you want to inspect the outcome (e.g. log it or branch on it).

## Non-determinism (Choice)

`Choice` is the non-determinism effect: a single `Choice` computation can produce multiple results, each from a different branch. `Kyo.fromSeq` is the most common way to enter `Choice`; the combinators below handle, filter, or convert the result.

### `handleChoice`: handle to `Seq[A]`

```scala
object inventory:
    def check(item: Item): Item < Async = Kyo.defer(item)

val branches: Item < (Choice & Async) =
    Kyo.fromSeq(order.items).map(item => inventory.check(item))

val all: Seq[Item] < Async =
    branches.handleChoice
```

`handleChoice` runs all branches and collects their results into a `Seq`. The `Choice` effect is discharged.

### `filterChoice`: drop branches by predicate

```scala
object inventory:
    def check(item: Item): Item < Async        = Kyo.defer(item)
    def available(sku: String): Boolean < Sync = Kyo.defer(true)

val branches: Item < (Choice & Async) =
    Kyo.fromSeq(order.items).map(item => inventory.check(item))

val inStock: Item < (Choice & Async) =
    branches.filterChoice(item => inventory.available(item.sku))
```

`filterChoice` runs the predicate per branch; branches where the predicate is false are dropped. Equivalent to `flatMap`-ing through `Choice.dropIf` but more readable at the call site.

### `choiceDropToAbsent`, `choiceDropToThrowable`, `choiceDropToFailure`

```scala
case class NoMatchingItems(orderId: Long) extends Exception("no matching items")

val branches: Item < (Choice & Async) =
    Kyo.fromSeq(order.items)

val nonEmptyAbsent: Item < (Choice & Async & Abort[Absent]) =
    branches.choiceDropToAbsent

val nonEmptyEx: Item < (Choice & Async & Abort[NoSuchElementException]) =
    branches.choiceDropToThrowable

val nonEmptyDomain: Item < (Choice & Async & Abort[NoMatchingItems]) =
    branches.choiceDropToFailure(NoMatchingItems(orderId))
```

> **Note:** These trigger only when `handleChoice` would return an empty `Seq`, that is, when the entire `Choice` reduces to no surviving branch. Individual branches dropped along the way (e.g. by `filterChoice`) do not trigger them, even though the names read as if every per-branch drop became an error.

## Emit, streaming, and conversion

`Emit[A]` produces values one at a time, similar to `yield` in Python or `IEnumerable` in C#. The combinators below either handle the emit effect (collect, foreach, pipe to a channel) or convert it into a `Stream[A, S]` for downstream stream operators. The examples use the `shipments` producer from the shared domain, which emits one `ShipmentEvent` per order item.

### `handleEmit`, `handleEmitDiscarding`, `foreachEmit`

```scala
val collected: (Chunk[ShipmentEvent], Unit) < Any =
    shipments.handleEmit

val collectedOnly: Chunk[ShipmentEvent] < Any =
    shipments.handleEmitDiscarding

val sideEffect: Unit < Sync =
    shipments.foreachEmit(ev => Kyo.logInfo(s"event: $ev"))
```

`handleEmit` returns `(Chunk[A], B)` where `B` is the original effect's result. `handleEmitDiscarding` keeps only the chunk. `foreachEmit` passes each emitted value to a function as it is emitted, and returns the original effect's result.

### `emitToChannel`: pipe to a `Channel[A]`

```scala
val piped: Unit < (Sync & Scope & Async & Abort[Closed]) =
    Channel.init[ShipmentEvent](16).map(channel => shipments.emitToChannel(channel))
```

The channel must be initialized separately. Each emission is a `put`, so a full channel suspends the producer until a consumer takes from it: a producer that emits more than the channel's capacity needs a consumer running concurrently. If the channel is closed before the emit completes, the computation fails with `Abort[Closed]`.

### `emitChunked`: re-emit as chunks

```scala
val chunkedEmits: Unit < Emit[Chunk[ShipmentEvent]] =
    shipments.emitChunked(32)
```

`emitChunked` accumulates emitted values in a buffer until the buffer reaches `chunkSize`, then emits a `Chunk[A]`, and flushes the last partial chunk at the end. The output effect changes from `Emit[A]` to `Emit[Chunk[A]]`. Chunks are at most `chunkSize`; the final one may be smaller.

### Convert to `Stream`

Six conversions cover two input shapes and three ways of treating the original effect's result:

| Input | Result is `Unit` | Non-`Unit` result, discarded | Result kept as `B < Async` |
|---|---|---|---|
| `Emit[Chunk[A]]` | `emitToStream` | `emitToStreamDiscarding` | `emitToStreamAndResult` |
| `Emit[A]` | `emitChunkedToStream(n)` | `emitChunkedToStreamDiscarding(n)` | `emitChunkedToStreamAndResult(n)` |

```scala
val counted: Long < Emit[ShipmentEvent] =
    shipments.map(_ => 42L)
val chunkedEmits: Unit < Emit[Chunk[ShipmentEvent]] =
    shipments.emitChunked(32)

val s1: Stream[ShipmentEvent, Any] =
    chunkedEmits.emitToStream

val s2: Stream[ShipmentEvent, Any] =
    counted.emitChunked(32).emitToStreamDiscarding

val s3: (Stream[ShipmentEvent, Async], Unit < Async) < Async =
    chunkedEmits.emitToStreamAndResult

val s4: Stream[ShipmentEvent, Any] =
    shipments.emitChunkedToStream(32)

val s5: Stream[ShipmentEvent, Any] =
    counted.emitChunkedToStreamDiscarding(32)

val s6: (Stream[ShipmentEvent, Async], Unit < Async) < Async =
    shipments.emitChunkedToStreamAndResult(32)
```

> **Note:** the `Discarding` forms require `NotGiven[B =:= Unit]`: for a `Unit` result there is nothing to discard, so use the first column instead.

> **Note:** in the `AndResult` forms, the `B < Async` completes only once the stream has been run. Consume the stream before waiting on the result, or the wait never ends.

### `unwrapStream`: flatten an effectful stream

```scala
val streamInEffect: Stream[Order, Async] < Async = ???

val flattened: Stream[Order, Async] =
    streamInEffect.unwrapStream
```

`unwrapStream` fuses the outer effect context (`S2`) into the stream's effect row (`S`), producing a single `Stream[V, S & S2]`. Use it when an upstream operation produces a stream as part of its effect (for example, reading a service from `Env` and asking it for a stream) and you want a flat stream to chain stream operators against.

## Resource lifecycle

The Construction cluster has `Kyo.acquireRelease`, `addFinalizer`, `scoped`, and `fromAutoCloseable`: those create resources and register cleanup at acquisition time. The combinators below are the symmetric side: attaching cleanup to an in-flight computation.

### `ensuring` and `ensuringError`

```scala
val withCleanup: Order < (Async & Scope & Sync & Abort[OrderNotFound]) =
    load.ensuring(Kyo.logInfo("load complete"))

val withErrorAwareCleanup: Order < (Async & Scope & Sync & Abort[OrderNotFound]) =
    load.ensuringError {
        case Present(err) => Kyo.logError(s"load failed: $err")
        case Absent       => Kyo.logInfo("load succeeded")
    }
```

`ensuring(finalizer)` registers `finalizer` to run when the surrounding `Scope` closes. `ensuringError` takes a function that receives `Maybe[Error[Any]]`: `Present(err)` when the scope's body failed or was interrupted, `Absent` when it completed.

> **Note:** The finalizer is registered before the effect's first step, so it runs even if the effect is interrupted before it starts. It runs when the scope closes, not when the effect ends: in a scope that goes on to do more work, the finalizer waits for all of it. Wrap the effect in its own `Kyo.scoped` when cleanup must follow the effect directly.

`Kyo.acquireRelease` is the better default, since it pairs cleanup with the construction of the resource. Reach for `ensuring` when you have an existing effect to attach cleanup to and can't restructure the code that builds it.

## Dependency injection (Env)

`Env[E]` is the read-only context effect; you read dependencies with `Kyo.service[E]` / `Kyo.serviceWith[E]` (under [Construction](#env-constructors-reading-dependencies)) and supply them with the combinators below.

### `provideValue` and `Kyo.provideFor`: supply a single dependency

```scala
val loadOrder: Order < (Env[OrderRepo] & Abort[OrderNotFound]) =
    Kyo.serviceWith[OrderRepo](_.fetch(orderId))

val withRepo: Order < Abort[OrderNotFound] =
    loadOrder.provideValue(repo)

val withRepoPrefix =
    Kyo.provideFor(repo)(loadOrder)
```

Use these when you have a concrete instance of one dependency. The `Env[E]` is removed from the effect row for that single type; if the effect requires more than one dependency, chain the calls or use `provideLayer` / `provide`. `Kyo.provideFor(value)(effect)` is the prefix form of `provideValue`.

### `provideLayer`: supply via a `Layer`

```scala
val repoLayer: Layer[OrderRepo, Any] = Layer(repo)

val loadOrder: Order < (Env[OrderRepo] & Abort[OrderNotFound]) =
    Kyo.serviceWith[OrderRepo](_.fetch(orderId))

val configured: Order < (Memo & Abort[OrderNotFound]) =
    loadOrder.provideLayer(repoLayer)
```

A `Layer[E, S]` is a deferred construction of `E` that may itself depend on other effects. `provideLayer` runs the layer to produce the dependency, then supplies it. The resulting effect row adds `Memo` because layers are memoized.

### `provide`: supply all required `Env` with multiple layers

```scala
val repoLayer: Layer[OrderRepo, Any] = Layer(repo)

val loadOrder: Order < (Env[OrderRepo] & Abort[OrderNotFound]) =
    Kyo.serviceWith[OrderRepo](_.fetch(orderId))

val fullyConfigured: Order < (Abort[OrderNotFound] & Memo) =
    loadOrder.provide(repoLayer)
```

`provide` is a `transparent inline` macro that takes a variable number of layers and supplies all of the effect's `Env[*]` requirements. Its result type is computed at the call site: the `Env` requirements are gone, and what remains is the effect's other effects plus `Memo` from the layers, as in the example above. If a layer is missing, the call fails to compile with `Missing Input: T`, naming the absent type, and naming the layer that needs it when the need comes from another layer.

## Putting it together

The clusters above are orthogonal. One computation can cross construction, sequencing, concurrency, error handling, and resource lifecycle without any intermediate types: here a checkout opens a transaction, fetches the customer's profile on its own fiber, looks the order up with retries and a fallback, and commits.

```scala
trait Transaction:
    def commit: Unit < Sync
    def close: Unit < Sync // rolls back anything not committed

trait Database:
    def begin: Transaction < Sync
val db: Database = ???

// Construction: a lookup that may find nothing becomes a typed failure
val found: Order < (Abort[OrderNotFound] & Sync) =
    repo.lookup(orderId).map(result => Kyo.fromOption(result))
        .absentToFailure(OrderNotFound(orderId))

val checkout: (Order, Profile) < (Async & Scope & Sync) =
    // Resource lifecycle: the transaction closes with the scope, rolling back unless committed
    Kyo.acquireRelease(db.begin)(_.close).map { txn =>
        for
            // Concurrency: the profile loads on its own fiber meanwhile
            profileFiber <- profileFor(orderId).fork
            // Sequencing and error handling: log, look up with retries, fall back to a default
            order <- (Kyo.logInfo("loading order") *> found).retry(3).recover(_ => Order(0L))
            _     <- txn.commit
            profile <- profileFiber.join
        yield (order, profile)
    }
```
