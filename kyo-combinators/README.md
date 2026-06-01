# kyo-combinators

Every Kyo computation has the shape `A < S`: a value of type `A` pending one or more effects `S`. `kyo-combinators` is a layer of fluent extension methods on top of that one shape. Instead of writing `Abort.run(eff).map(...)`, `Fiber.init(eff)`, or `Async.sleep(d).andThen(eff)`, you write `eff.result`, `eff.fork`, or `eff.delay(d)`. The library adds no new core type and no new effect: it adds a postfix vocabulary for the effects already in `kyo-prelude` and `kyo-core` (`Abort`, `Async`, `Choice`, `Emit`, `Env`, `Scope`, `Sync`, `Stream`), plus a `Kyo.*` companion for constructing computations.

The combinators cluster by which effect row they target. The receiver of each extension carries a type-pattern that constrains where it applies: `.fork` is defined on `A < (Abort[E] & Async & S)`, `.maybe` is defined on `A < (Abort[Absent] & S)`, `.handleChoice` is defined on `A < (S & Choice)`. The same call-site idiom (postfix method on the effect value) handles construction, handling, recovery, retry, repetition, lifecycle, parallel composition, and stream conversion.

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
object OrderRepo:
    def lookup(id: Long): Option[Order] = None
val orderId: Long = 42L

val resilient: Order < Async =
    Kyo.fromOption(OrderRepo.lookup(orderId))
        .absentToFailure(OrderNotFound(orderId))
        .retry(Schedule.exponential(100.millis, 2.0).take(3))
        .recover(_ => Order(0L))
```

The rest of this README walks the combinators by cluster, starting with construction (lifting plain values into the effect row) and finishing with dependency injection (handling `Env[E]`).

## Construction

The `Kyo.` companion is the entry door for lifting plain values, callbacks, futures, optionality types, sequences, and resources into the right effect row. You reach for these BEFORE any combinator applies: every combinator is an extension method on `A < S`, so you need an `A < S` first.

### Lifting plain values and side-effects

`Kyo.defer` suspends a thunk under `Sync`. `Kyo.fail` lifts an error value into `Abort[E]`. `Kyo.attempt` catches any `Throwable` thrown by the body into `Abort[Throwable]`.

```scala
import kyo.*

case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val orderId: Long = 42L

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
import kyo.*

case class Order(id: Long, items: Seq[String])
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val orderId: Long = 42L

trait LegacyClient:
    def fetchOrder(id: Long, cb: Either[Throwable, Order] => Unit): Unit
val legacyClient: LegacyClient = ???

val pause: Unit < Async = Kyo.sleep(500.millis)

val sentinel: Nothing < Async = Kyo.never

val fromCallback: Order < (Abort[OrderNotFound] & Async) =
    Kyo.async { register =>
        legacyClient.fetchOrder(
            orderId,
            {
                case Right(order) => register(order)
                case Left(err)    => register(Kyo.fail(OrderNotFound(orderId)))
            }
        )
    }
```

> **Caution:** `Kyo.async` schedules the register callback through `Sync.Unsafe.evalOrThrow` internally. The public API is safe (any panic propagates through the resulting promise), but the implementation depends on an unsafe primitive. Treat the `register` body as side-effecting code that may run outside the calling fiber.

### Lifting standard-library types

Each `fromX` constructor lifts a standard-library result-or-optionality type into the corresponding `Abort`-or-`Choice`-typed effect.

```scala
import kyo.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise

case class Order(id: Long, items: Seq[Item])
case class Item(sku: String, qty: Int)
case class OrderNotFound(id: Long)      extends Exception(s"Order $id not found")
case class ValidationError(msg: String) extends Exception(msg)
val orderId: Long = 42L
val order: Order  = Order(orderId, Seq(Item("sku1", 1)))

object OrderRepo:
    def lookup(id: Long): Option[Order] = None

object orderCache:
    def get(id: Long): Maybe[Order] = Absent

object Order:
    def validate(raw: String): Either[ValidationError, Order] = Left(ValidationError("bad"))

trait Repo:
    def lookupResult(id: Long): Result[OrderNotFound, Order]
    def lookupOrThrow(id: Long): Order
    def lookupAsync(id: Long): Future[Order]
end Repo
val repo: Repo                   = ???
val rawJson: String              = "{}"
val orderPromise: Promise[Order] = Promise[Order]()

val fromOpt: Order < Abort[Absent] =
    Kyo.fromOption(OrderRepo.lookup(orderId))

val fromMb: Order < Abort[Absent] =
    Kyo.fromMaybe(orderCache.get(orderId))

val fromEi: Order < Abort[ValidationError] =
    Kyo.fromEither(Order.validate(rawJson))

val fromRes: Order < Abort[OrderNotFound] =
    Kyo.fromResult(repo.lookupResult(orderId))

val fromTr: Order < Abort[Throwable] =
    Kyo.fromTry(scala.util.Try(repo.lookupOrThrow(orderId)))

val fromFut: Order < Async =
    Kyo.fromFuture(repo.lookupAsync(orderId))

val fromPr: Order < Async =
    Kyo.fromPromiseScala(orderPromise)

val fromSq: Item < Choice =
    Kyo.fromSeq(order.items)
```

`fromOption` and `fromMaybe` both route absence to `Abort[Absent]`; the choice between them is the input type. `fromEither` and `fromResult` preserve the error type; `fromTry` collapses to `Abort[Throwable]` because `Try` only carries `Throwable`.

`fromSeq` expands each element of the sequence into a non-deterministic branch of `Choice`. The downstream computation will be evaluated for every element (see [Non-determinism](#non-determinism-choice)).

### Resource lifecycle (constructing the resource)

Lifecycle constructors register cleanup with `Scope`. The acquired value is in scope until the surrounding `Scope.run` exits, at which point the registered finalizer runs.

```scala
import java.sql.Connection
import kyo.*

case class Order(id: Long)
val orderId: Long = 42L
val url: String   = "jdbc:postgresql://localhost/db"

trait Database:
    def begin: Connection < Sync
val db: Database = ???

class RepoHandle(conn: Connection):
    def load(id: Long): Order < Sync = Kyo.defer(Order(id))

object OrderRepo:
    def using(conn: Connection): RepoHandle = new RepoHandle(conn)

val open: Connection < (Scope & Sync) =
    Kyo.fromAutoCloseable(java.sql.DriverManager.getConnection(url))

val txn: Connection < (Scope & Sync) =
    Kyo.acquireRelease(db.begin)(conn => Kyo.defer(conn.commit()))

val installShutdown: Unit < (Scope & Sync) =
    Kyo.addFinalizer(Kyo.logInfo("server shutting down"))

val scoped: Order < (Async & Sync) =
    Kyo.scoped {
        txn.map(conn => OrderRepo.using(conn).load(orderId))
    }
```

`fromAutoCloseable` is the shortcut for the common case (Java AutoCloseable). `acquireRelease` is the general form: you control both the acquisition effect and the cleanup effect. `addFinalizer` registers cleanup with no associated resource. `scoped` discharges the `Scope` effect, running registered finalizers when the inner effect completes.

`ensuring` (described under [Resource lifecycle](#resource-lifecycle)) is the symmetric "attach cleanup to a computation" combinator.

### Env constructors (reading dependencies)

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val orderId: Long = 42L

trait Database
val db: Database = new Database {}

class OrderRepo(val database: Database):
    def load(id: Long): Order < Abort[OrderNotFound] = ???

val readRepo: OrderRepo < Env[OrderRepo] =
    Kyo.service[OrderRepo]

val loadOrder: Order < (Env[OrderRepo] & Abort[OrderNotFound]) =
    Kyo.serviceWith[OrderRepo].apply(repo => repo.load(orderId))

val withRepo =
    Kyo.provideFor(OrderRepo(db))(loadOrder)
```

> **Note:** `Kyo.serviceWith[D]` returns a polymorphic function. The call-site idiom is `Kyo.serviceWith[D].apply(fn)`, not `Kyo.serviceWith[D](fn)`. The polymorphic shape lets the compiler infer the return effect row from `fn` independently.

### Parallel fan-out

`Kyo.foreachPar`, `foreachParDiscard`, `collectAllPar`, `collectAllParDiscard` run a sequence of effects in parallel with a bounded concurrency.

```scala
import kyo.*

case class Item(sku: String, qty: Int)
case class Order(id: Long, items: Seq[Item])
case class Stock(sku: String, available: Int)
case class Profile(name: String)
val order: Order = Order(1L, Seq(Item("sku1", 1)))

object inventory:
    def check(sku: String): Stock < (Abort[Throwable] & Async) = ???
    def record(sku: String): Unit < (Abort[Throwable] & Async) = ???

object profiles:
    def lookup(sku: String): Profile < Async = ???
    def touch(sku: String): Unit < Async     = ???

val stockChecks: Chunk[Stock] < (Abort[Throwable] & Async) =
    Kyo.foreachPar(order.items, Async.defaultConcurrency)(item => inventory.check(item.sku))

val stockChecksDiscard: Unit < (Abort[Throwable] & Async) =
    Kyo.foreachParDiscard(order.items, Async.defaultConcurrency)(item => inventory.record(item.sku))

val collected: Seq[Profile] < Async =
    Kyo.collectAllPar(order.items.map(i => profiles.lookup(i.sku)), Async.defaultConcurrency)

val collectedDiscard: Unit < Async =
    Kyo.collectAllParDiscard(order.items.map(i => profiles.touch(i.sku)), Async.defaultConcurrency)
```

> **Note:** All four take a `concurrency: Int = Async.defaultConcurrency` parameter as the default. The limit applies even when the iterable is small. Forgetting the second argument silently caps fan-out at `Async.defaultConcurrency`, which is intentional but easy to miss when copying a snippet without its imports and defaults.

### Emit and Poll

```scala
import kyo.*

case class Heartbeat(ts: Long)
case class Order(id: Long)

val ping: Unit < Emit[Heartbeat] =
    Kyo.emit(Heartbeat(java.lang.System.currentTimeMillis()))

val one: Maybe[Order] < Poll[Order] =
    Kyo.poll[Order]
```

`Kyo.emit` produces a single value into the `Emit[A]` effect; multiple `emit` calls in sequence produce a stream of values (see [Emit, streaming, and conversion](#emit-streaming-and-conversion)). `Kyo.poll` reads one value from a `Poll[A]` channel.

### Console logging

```scala
import kyo.*

case class Order(id: Long)
val order: Order  = Order(42L)
val ex: Throwable = new RuntimeException("payment failed")

val logInfo2: Unit < Sync  = Kyo.logInfo("processing order")
val logWarn2: Unit < Sync  = Kyo.logWarn("payment retry", new RuntimeException("network"))
val logDebug2: Unit < Sync = Kyo.logDebug(s"order=$order")
val logError2: Unit < Sync = Kyo.logError("payment failed", ex)
val logTrace2: Unit < Sync = Kyo.logTrace("entering checkout")
```

Each variant has a `(String)` and `(String, Throwable)` overload. All run under `Sync`.

## Sequencing and combining

These extensions live on `A < S` for ANY effect row. They compose two effects sequentially, gate one on another, or weave a side effect through a result.

### Sequential zip: `*>`, `<*`, `<*>`

```scala
import kyo.*

case class Order(id: Long)
case class Profile(name: String)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val orderId: Long                                = 42L
val load: Order < (Abort[OrderNotFound] & Async) = ???
val profileFor: Long => Profile < Async          = _ => ???

val ignoreFirst: Order < (Abort[OrderNotFound] & Async) =
    Kyo.logInfo("loading") *> load

val ignoreSecond: Order < (Abort[OrderNotFound] & Async) =
    load <* Kyo.logInfo("loaded")

val both: (Order, Profile) < (Abort[OrderNotFound] & Async) =
    load <*> profileFor(orderId)
```

`*>` keeps the second result; `<*` keeps the first; `<*>` keeps both as a tuple. `<*>` uses a `Zippable` typeclass to flatten nested tuples: `a <*> b <*> c` produces `(A, B, C)`, not `((A, B), C)`.

> **Unlike** the parallel siblings `&>`, `<&`, `<&>` under [Concurrency](#concurrency-and-forking), the sequential operators evaluate the second effect AFTER the first. The only call-site signal between sequential and parallel is the ampersand: `a *> b` is sequential, `a &> b` runs both fibers concurrently. Pick deliberately.

### `tap`: side-effect on the success value

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val logged: Order < (Abort[OrderNotFound] & Async & Sync) =
    load.tap(order => Kyo.logInfo(s"loaded $order"))
```

`tap` runs `fn(a)` for its effect and discards the result, returning the original `a`. The function may itself be effectful; the tap's effect row is added to the carrier's.

### `when` / `unless`: conditional execution

```scala
import kyo.*

case class Order(id: Long, total: BigDecimal)
case class Receipt(orderId: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val orderId: Long                                = 42L
val order: Order                                 = Order(orderId, BigDecimal(100))
val load: Order < (Abort[OrderNotFound] & Async) = ???

def sendReceipt(o: Order): Receipt < (Abort[Throwable] & Async) = ???
object orderCache:
    def contains(id: Long): Boolean < Sync = Kyo.defer(false)

val maybeNotify: Maybe[Receipt] < (Abort[Throwable] & Async) =
    sendReceipt(order).when(order.total > BigDecimal(0))

val maybeRetry: Maybe[Order] < (Abort[OrderNotFound] & Async) =
    load.unless(orderCache.contains(orderId))
```

> **Note:** `when` and `unless` return `Maybe[A]`, not `Unit`. When the condition is true, `when` wraps the result in `Present`; when false, it returns `Absent`. Users coming from Cats `whenA` or ZIO `whenZIO` may expect `Unit` and lose the result. If you only care about the side-effect, follow with `.unit` (or chain into a `tap`).

### `debugValue` / `debugTrace`: inline observability

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val withPrint: Order < (Abort[OrderNotFound] & Async) =
    load.debugValue

val withTrace: Order < (Abort[OrderNotFound] & Async) =
    load.debugTrace
```

`debugValue` prints the result to the console once it's computed. `debugTrace` prints the result plus an execution trace. Both pass the value through unchanged; insert into a chain for inspection without restructuring code.

## Repetition

Repetition primitives are orthogonal to error handling: they re-run an effect by count, by schedule, by predicate, or forever. None of them treat failure as a reason to re-run; for that, use [`retry`](#error-handling) instead.

### `repeat`: by count or schedule

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val ten: Order < (Abort[OrderNotFound] & Async) =
    load.repeat(10)

val backedOff: Order < (Abort[OrderNotFound] & Async) =
    load.repeat(Schedule.exponential(100.millis, 2.0).take(5))

val polled: Order < (Abort[OrderNotFound] & Async) =
    load.repeatAtInterval(i => (i * 100).millis, limit = 5)
```

> **Note:** Two overloads of `repeat` exist with different effect rows. `repeat(Int)` is pure (just a loop counter); `repeat(Schedule)` adds `Async` because the schedule may insert delays. If you switch from a count to a schedule, expect the effect row to grow.

### `repeatWhile` / `repeatUntil`: predicate-driven

```scala
import kyo.*

enum Status:
    case Pending, Completed
case class Order(id: Long, status: Status)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val pollPending: Order < (Abort[OrderNotFound] & Async) =
    load.repeatWhile(_.status == Status.Pending)

val pollWithDelay: Order < (Abort[OrderNotFound] & Async) =
    load.repeatWhile { (order, iter) =>
        (order.status == Status.Pending, (iter * 50).millis)
    }

val waitForCompletion: Order < (Abort[OrderNotFound] & Async) =
    load.repeatUntil(_.status == Status.Completed)

val waitWithDelay: Order < (Abort[OrderNotFound] & Async) =
    load.repeatUntil { (order, iter) =>
        (order.status == Status.Completed, (iter * 50).millis)
    }
```

The simpler overload takes `A => Boolean`. The richer overload takes `(A, Int) => (Boolean, Duration)`: the second `Int` is the iteration index and the returned `Duration` is the sleep interval before the next iteration. The richer overload adds `Async` to the effect row because of the sleep.

### `forever`: infinite repetition

```scala
val heartbeat: Nothing < Async =
    Kyo.sleep(1.second).forever
```

The return type is `Nothing` because the loop never produces a final value.

### `delay`: postpone before running

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val later: Order < (Abort[OrderNotFound] & Async) =
    load.delay(500.millis)
```

`delay` is the one-shot sibling to `repeatAtInterval`'s per-iteration backoff: it sleeps once before evaluating the effect.

> **Note:** `delay` adds `Async` to the effect row because it sleeps before evaluating, just like `repeat(Schedule)` above.

## Error handling

Everything below applies to `A < (Abort[E] & S)`. The combinators are organised by what they DO to the error: handle it (to a `Result` or `Maybe`), recover from it, fold over it, transform its type, route it to a different effect, retry on it, or convert it to a panic.

### `result`: handle to a `Result`

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val handled: Result[OrderNotFound, Order] < Async =
    load.result
```

`result` discharges the `Abort[E]` effect entirely, exposing the success-or-failure as a value. The remaining effect row is `S` (in this case `Async`).

Two siblings exist for partial handling:

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val partial: Result.Partial[OrderNotFound, Order] < (Abort[Nothing] & Async) =
    load.resultPartial

val partialThrowing: Result.Partial[OrderNotFound, Order] < Async =
    load.resultPartialOrThrow
```

`resultPartial` returns a `Result.Partial` (no `Panic` branch) but leaves panics tracked as `Abort[Nothing]`. `resultPartialOrThrow` returns a `Result.Partial` AND throws on panic synchronously, discharging the `Abort[Nothing]` row.

### `recover` and `recoverSome`: replace failures

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val orderId: Long                                = 42L
val load: Order < (Abort[OrderNotFound] & Async) = ???

val safe: Order < Async =
    load.recover(_ => Order(0L))

val partialRecovery: Order < (Abort[OrderNotFound] & Async) =
    load.recoverSome {
        case OrderNotFound(0L) => Order(0L)
    }
```

`recover` handles ALL failures of type `E`; the result no longer tracks `Abort[E]`. `recoverSome` takes a `PartialFunction`: unmatched failures stay in the `Abort[E]` row. Use `recover` when you have a total handler, `recoverSome` when you handle only some failure shapes.

### `foldAbort` / `foldAbortOrThrow`: fold over outcome

```scala
import kyo.*

case class Order(id: Long, total: BigDecimal)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val rendered: String < Async =
    load.foldAbort(
        onSuccess = order => s"order ${order.id}: ${order.total}",
        onFail = err => s"failed: $err"
    )

val renderedWithPanic: String < Async =
    load.foldAbort(
        onSuccess = order => s"order ${order.id}",
        onFail = err => s"failed: $err",
        onPanic = thr => s"panic: ${thr.getMessage}"
    )

val renderedThrowing: String < Async =
    load.foldAbortOrThrow(
        onSuccess = order => s"order ${order.id}",
        onFail = err => s"failed: $err"
    )
```

Three panic strategies: leave `Abort[Nothing]` in the row (two-arm `foldAbort`); handle it explicitly with a third arm; throw on panic (`foldAbortOrThrow`). Pick by what the caller needs to see.

### `mapAbort` and `swapAbort`: transform the error type

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
sealed trait ServiceError
object ServiceError:
    case class NotFound(id: Long) extends ServiceError
val load: Order < (Abort[OrderNotFound] & Async) = ???

val mapped: Order < (Abort[ServiceError] & Async) =
    load.mapAbort(notFound => ServiceError.NotFound(notFound.id))

val swapped: OrderNotFound < (Abort[Order] & Async) =
    load.swapAbort
```

`mapAbort` is the error-side `map`: transform `E` to `E1` without affecting success. `swapAbort` exchanges success and error sides; the success becomes the new `Abort` failure type. `swapAbort` is rare in production code, common for testing error paths.

### `orPanic` / `orThrow` / `unpanic`: collapse to panic or back

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val panickingTracked: Order < (Abort[Nothing] & Async) =
    load.orPanic

val panickingUntracked: Order < Async =
    load.orThrow

val caughtAgain: Order < (Async & Abort[Throwable]) =
    panickingUntracked.unpanic
```

> **Caution:** `orThrow` throws synchronously and DOES NOT track the panic in the effect row. Inside `Sync` or `Async`, that panic becomes invisible to the type system: a code path you didn't expect to fail will fail at runtime with no compile-time hint. `Sync` and `Async` track panics as `Abort[Nothing]`; preserve that tracking by using `orPanic` instead. The scaladoc for `orThrow` makes this point explicit: it's intended for pure (non-`Sync`/non-`Async`) effects only.

`unpanic` is the inverse: catch any `Throwable` thrown at runtime (e.g. from `orThrow` or from a panicking sub-effect) and lift it back into `Abort[Throwable]`.

### `abortToChoiceDrop`, `abortToAbsent`, `abortToThrowable`: route to other effects

```scala
import kyo.*

case class Order(id: Long)
// A non-Throwable error type is required for abortToThrowable
case class OrderNotFound(id: Long)
val load: Order < (Abort[OrderNotFound] & Async) = ???

val asChoice: Order < (Async & Choice) =
    load.abortToChoiceDrop

val asAbsent: Order < (Async & Abort[Absent]) =
    load.abortToAbsent

val asThrown: Order < (Async & Abort[Throwable]) =
    load.abortToThrowable
```

The first drops failures as empty `Choice` branches. The second collapses any failure to `Absent` (you lose the specific error value). The third lifts `E` into `Abort[Throwable]`, wrapping non-`Throwable` failures in `PanicException(error)`.

> **Note:** `abortToThrowable` requires `NotGiven[E <:< Throwable]` and will NOT compile on `Abort[Throwable]`. The constraint exists because the implementation would otherwise produce an ambiguous wrap. If `E` already extends `Throwable`, you don't need a conversion; you already have `Abort[Throwable]`.

> **Note:** Code that catches `Throwable` downstream of `abortToThrowable` / `orThrow` / `orPanic` will receive a `PanicException(originalError)` for non-`Throwable` original errors, NOT the original error value. Unwrap with a pattern match if you need to recover the original.

### `retry`: re-run on failure

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val retriedN: Order < (Abort[OrderNotFound] & Async) =
    load.retry(3)

val retriedSched: Order < (Abort[OrderNotFound] & Async) =
    load.retry(Schedule.exponential(100.millis, 2.0).take(5))

val retriedForever: Order < Async =
    load.retryForever
```

`retry(Int)` retries up to `n` times on failure; after the budget, the last failure is re-raised. `retry(Schedule)` re-runs with delays driven by the schedule. `retryForever` returns a computation with no `Abort[E]` row because the only way to exit is success.

`recover` and `retry` together solve different problems. `recover` is "replace the error with a value." `retry` is "do it again." Combine them: `load.retry(3).recover(_ => Order.empty(orderId))` retries three times and falls back if all retries fail.

### `forAbort[E1]`: handle one branch of a union

When the error type is a union (`Abort[A | B | C]`), `forAbort[A]` enters a narrowing DSL that lets you handle ONLY one branch and leave the others in the effect row.

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long)         extends Exception(s"Order $id not found")
case class InventoryEmpty()                extends Exception("Inventory empty")
case class PaymentDeclined(reason: String) extends Exception(reason)
sealed trait ServiceError
object ServiceError:
    case class Payment(reason: String) extends ServiceError
val orderId: Long = 42L

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

`ForAbortOps` exposes a parallel surface to the top-level combinators: `result`, `resultPartial`, `recover`, `recoverSome`, `fold`, `mapAbort`, `swap`, `orPanic`, `toChoiceDrop`, `toAbsent`, `toThrowable`, `retry(Int)`, `retry(Schedule)`, `retryForever`. The only difference is the type narrowing: each method applies to the SELECTED branch `E1` and leaves the other branches `ER` in the residual effect row.

> **Note:** `ForAbortOps.recover` and `ForAbortOps.recoverSome` return polymorphic functions (just like `Kyo.serviceWith[D]`). The call site is `effect.forAbort[E1].recover.apply(fn)`, but in practice Scala 3 picks up the apply automatically: `effect.forAbort[E1].recover(fn)` works.

### `PanicException`: the panic wrapper

`PanicException[A]` (extends `KyoException`) is the wrapper used when lifting a non-`Throwable` error into a `Throwable`-typed `Abort`. It carries the original `error: A` as a field. You see it when:

- `abortToThrowable` lifts a non-`Throwable` `E`.
- `orThrow` / `orPanic` lift a non-`Throwable` `Failure` to a panic.
- `ForAbortOps.toThrowable` / `ForAbortOps.orPanic` do the same on one branch.

Pattern-matching on the original error is straightforward:

```scala
import kyo.*

case class Order(id: Long)
// Non-Throwable error types are required for abortToThrowable
case class OrderNotFound(id: Long)
case class InventoryEmpty()
case class PaymentDeclined(reason: String)
val orderId: Long = 42L
type OrderError = OrderNotFound | InventoryEmpty | PaymentDeclined
val orderEffect: Order < (Abort[OrderError] & Async) = ???

val recovered: Order < Async =
    orderEffect.abortToThrowable.recover {
        case PanicException(OrderNotFound(id)) => Order(id)
        case _                                 => Order(orderId)
    }
```

## Optionality (Absent)

`Absent` is the canonical "no value" failure, and `Abort[Absent]` is the absence-as-error effect. The combinators below are specific to `Abort[Absent]`: every error-handling combinator described above also works on `Abort[Absent]`, but these are the convenient shapes when you DON'T need to carry an error message.

### `maybe`: handle to `Maybe[A]`

```scala
import kyo.*

case class Order(id: Long)
val orderId: Long = 42L
object orderCache:
    def get(id: Long): Option[Order] = None

val lookup: Order < (Abort[Absent] & Async) =
    Kyo.fromOption(orderCache.get(orderId))

val asMaybe: Maybe[Order] < Async =
    lookup.maybe
```

`maybe` is the `Absent`-specific analogue of `result`: it discharges the `Abort[Absent]` row and returns a `Maybe[A]`. Use it when "missing" is a normal control-flow outcome and you don't want to type-erase to a `Result.Failure(Absent)`.

`maybe` only applies when `E` is exactly `Absent`. If you have `Abort[MyError]`, use `result` (returns `Result[MyError, A]`) instead. The two read as alternates but their constraints are different.

### Routing absence to other effects

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val orderId: Long                           = 42L
val lookup: Order < (Abort[Absent] & Async) = Kyo.fromOption(None)

val asChoice: Order < (Async & Choice) =
    lookup.absentToChoice

val asNoSuchEl: Order < (Async & Abort[NoSuchElementException]) =
    lookup.absentToThrowable

val asDomain: Order < (Async & Abort[OrderNotFound]) =
    lookup.absentToFailure(OrderNotFound(orderId))
```

`absentToChoice` drops the branch in a `Choice` context. `absentToThrowable` substitutes a `NoSuchElementException`, useful when bridging to APIs that expect Java-style "not found." `absentToFailure(err)` lifts `Absent` into a domain error: pass the error value, get back `Abort[E]` instead.

## Concurrency and forking

Forking launches a computation into its own fiber. The Kyo runtime schedules the fiber on its work-stealing pool; the parent continues without blocking.

### `fork`, `forkUnscoped`, `forkUsing`

```scala
import kyo.*

case class Order(id: Long)

// fork and forkUsing work on effects that have Abort[E] & Async in their row.
val loadSync: Order < (Abort[Nothing] & Async & Sync) = Kyo.defer(Order(1L))

val forked =
    loadSync.fork

val forkedUnsafe =
    loadSync.forkUnscoped

val usedFiber =
    loadSync.forkUsing { fiber =>
        fiber.join.map(o => s"got ${o.id}")
    }
```

> **Caution:** `fork` requires `Scope` in the effect row and registers the fiber to be interrupted when the scope closes. `forkUnscoped` does NOT register interruption: if the parent finishes before the child, the child keeps running as an orphan fiber. The shapes are identical (both return `Fiber[A, ...]`) but the lifetime guarantees are opposite. Reach for `fork` by default; use `forkUnscoped` only when you explicitly want the fiber to outlive the current scope.

`forkUsing` is the scoped form: it forks, hands you the fiber inside a function, and guarantees interruption when the function returns. Use it when you only need the fiber long enough to wait on it or race it with another effect.

### Parallel zip: `&>`, `<&`, `<&>`

```scala
import kyo.*

case class Order(id: Long)
case class Profile(name: String)

// Parallel zip composes with extra effects beyond Abort[E] & Async.
val orderEff   = Sync.defer(Order(1L))
val profileEff = Sync.defer(Profile("alice"))

val ignoreFirstPar = orderEff &> profileEff

val ignoreSecondPar = orderEff <& profileEff

val bothPar = orderEff <&> profileEff
```

Both effects launch on separate fibers via `Fiber.initUnscoped`. The combinator awaits both, then assembles the requested shape. The error type widens to `E | E1` because either fiber can fail. Compare with the sequential `*>`, `<*`, `<*>` under [Sequencing and combining](#sequencing-and-combining): the only call-site difference is the ampersand.

### `Fiber#join` and `Fiber#await`

Once you have a `Fiber[A, ...] < S` (typically from `.fork`), two combinators let you wait on it:

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val joined: Order < (Sync & Async & Scope & Abort[OrderNotFound]) =
    load.fork.join

val awaited: Result[OrderNotFound, Order] < (Sync & Async & Scope) =
    load.fork.await
```

`join` propagates the fiber's failure into the calling fiber (the error row stays `Abort[E]`). `await` exposes the outcome as a `Result[E, A]` value: no `Abort` is propagated, the caller inspects the result.

`join` is the right default when you want the parent fiber to fail if the child fails. `await` is the right default when you want to inspect the outcome (e.g. log it, retry it, race it against another fiber).

## Non-determinism (Choice)

`Choice` is the non-determinism effect: a single `Choice` computation can produce multiple results, each from a different branch. `Kyo.fromSeq` is the most common way to enter `Choice`; the combinators below handle, filter, or convert the result.

### `handleChoice`: handle to `Seq[A]`

```scala
import kyo.*

case class Item(sku: String, qty: Int)
case class Order(id: Long, items: Seq[Item])
val order: Order = Order(1L, Seq(Item("sku1", 1), Item("sku2", 2)))
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
import kyo.*

case class Item(sku: String, qty: Int)
case class Order(id: Long, items: Seq[Item])
val order: Order = Order(1L, Seq(Item("sku1", 1), Item("sku2", 2)))
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
import kyo.*

case class Item(sku: String, qty: Int)
case class Order(id: Long, items: Seq[Item])
case class NoMatchingItems(orderId: Long) extends Exception("no matching items")
val orderId: Long = 42L
val order: Order  = Order(orderId, Seq(Item("sku1", 1)))
object inventory:
    def check(item: Item): Item < Async = Kyo.defer(item)

val branches: Item < (Choice & Async) =
    Kyo.fromSeq(order.items).map(item => inventory.check(item))

val nonEmptyAbsent: Item < (Choice & Async & Abort[Absent]) =
    branches.choiceDropToAbsent

val nonEmptyEx: Item < (Choice & Async & Abort[NoSuchElementException]) =
    branches.choiceDropToThrowable

val nonEmptyDomain: Item < (Choice & Async & Abort[NoMatchingItems]) =
    branches.choiceDropToFailure(NoMatchingItems(orderId))
```

> **Note:** These all trigger only when `handleChoice` would return an EMPTY `Seq`. They do NOT trigger when individual branches are dropped (e.g. by `filterChoice`); they trigger when the entire `Choice` reduces to no surviving branch. Easy to misread the name as "convert every per-branch drop to an error."

## Emit, streaming, and conversion

`Emit[A]` produces values one at a time, similar to `yield` in Python or `IEnumerable` in C#. The combinators below either HANDLE the emit effect (collect, foreach, pipe to a channel) or CONVERT it into a `Stream[A, S]` for downstream stream operators.

### `handleEmit`, `handleEmitDiscarding`, `foreachEmit`

```scala
import kyo.*

enum ShipmentEvent:
    case Shipped(sku: String)
case class Item(sku: String, qty: Int)
case class Order(id: Long, items: Seq[Item])
val order: Order = Order(1L, Seq(Item("sku1", 1), Item("sku2", 2)))

val emitting: Unit < Emit[ShipmentEvent] =
    order.items.foldLeft[Unit < Emit[ShipmentEvent]](Kyo.unit) { (acc, item) =>
        acc *> Kyo.emit(ShipmentEvent.Shipped(item.sku))
    }

val collected: (Chunk[ShipmentEvent], Unit) < Any =
    emitting.handleEmit

val collectedOnly: Chunk[ShipmentEvent] < Any =
    emitting.handleEmitDiscarding

val sideEffect: Unit < Sync =
    emitting.foreachEmit(ev => Kyo.logInfo(s"event: $ev"))
```

`handleEmit` returns `(Chunk[A], B)` where `B` is the original effect's result. `handleEmitDiscarding` keeps only the chunk. `foreachEmit` runs a function per emitted value and ignores the values themselves.

### `emitToChannel`: pipe to a `Channel[A]`

```scala
import kyo.*

enum ShipmentEvent:
    case Shipped(sku: String)
val emitting: Unit < Emit[ShipmentEvent] =
    Kyo.emit(ShipmentEvent.Shipped("sku1"))

val piped: Unit < (Sync & Scope & Async & Abort[Closed]) =
    Channel.init[ShipmentEvent](16).map(channel => emitting.emitToChannel(channel))
```

The channel must be initialised separately. If the channel is closed before the emit completes, the computation fails with `Abort[Closed]`.

### `emitChunked`: re-emit as fixed-size chunks

```scala
import kyo.*

enum ShipmentEvent:
    case Shipped(sku: String)
val emitting: Unit < Emit[ShipmentEvent] =
    Kyo.emit(ShipmentEvent.Shipped("sku1"))

val chunkedEmits: Unit < Emit[Chunk[ShipmentEvent]] =
    emitting.emitChunked(32)
```

`emitChunked` accumulates emitted values in a buffer until the buffer reaches `chunkSize`, then emits a `Chunk[A]`. The LAST partial chunk is flushed at the end. The output effect changes from `Emit[A]` to `Emit[Chunk[A]]`.

> **Note:** Chunks emitted by `emitChunked` are AT MOST `chunkSize`; the final chunk may be smaller. Don't assume strict sizes.

### Convert to `Stream`

Six conversion variants exist, distinguished by three axes: chunked vs single-value emit, discarding vs retaining the original result, with-result tuple form.

```scala
import kyo.*

enum ShipmentEvent:
    case Shipped(sku: String)
val emitting: Unit < Emit[ShipmentEvent] =
    Kyo.emit(ShipmentEvent.Shipped("sku1"))
val nonUnitEmitting: Long < Emit[ShipmentEvent] =
    Kyo.emit(ShipmentEvent.Shipped("sku1")).map(_ => 42L)
val chunkedEmits: Unit < Emit[Chunk[ShipmentEvent]] =
    emitting.emitChunked(32)

// Emit[Chunk[A]] -> Stream[A, S], when B = Unit: use emitToStream
val s1: Stream[ShipmentEvent, Any] =
    emitting.emitChunked(32).emitToStream
// Emit[A] -> Stream[A, S], discarding non-Unit result B
val s2: Stream[ShipmentEvent, Any] =
    nonUnitEmitting.emitChunkedToStreamDiscarding(32) // discards B

// Emit[Chunk[A]] -> Stream[A, S], retaining result as a separate Async effect
val s3: (Stream[ShipmentEvent, Async], Unit < Async) < Async =
    chunkedEmits.emitToStreamAndResult

// Emit[A] -> Stream[A, S], chunked by chunkSize
val s4: Stream[ShipmentEvent, Any] =
    emitting.emitChunkedToStream(32)

// Emit[Chunk[A]] -> Stream[A, S], discarding non-Unit result B (pre-chunked variant)
val s5: Stream[ShipmentEvent, Any] =
    nonUnitEmitting.emitChunked(32).emitToStreamDiscarding

val s6: (Stream[ShipmentEvent, Async], Unit < Async) < Async =
    emitting.emitChunkedToStreamAndResult(32)
```

> **Note:** `emitToStreamDiscarding` requires `NotGiven[B =:= Unit]`. If the original computation's result is `Unit`, the discarding form would silently throw away nothing and is forbidden by the constraint. For `Unit < Emit[Chunk[A]] & S`, use `emitToStream` instead (it's defined on a different extension shape with no `NotGiven` requirement).

### `unwrapStream`: flatten an effectful stream

```scala
import kyo.*

case class Order(id: Long)
val streamInEffect: Stream[Order, Async] < Async = ???

val flattened: Stream[Order, Async] =
    streamInEffect.unwrapStream
```

`unwrapStream` fuses the outer effect context (`S2`) into the stream's effect row (`S`), producing a single `Stream[V, S & S2]`. Use it when an upstream operation produces a stream as part of its effect (e.g. `repo.streamOrders: Stream[Order, Async] < (Env[Repo] & Async)`) and you want a flat stream to chain stream operators against.

## Resource lifecycle

The Construction cluster has `Kyo.acquireRelease`, `addFinalizer`, `scoped`, and `fromAutoCloseable`: those CREATE resources and register cleanup at acquisition time. The combinators below are the symmetric side: attaching cleanup to an in-flight computation.

### `ensuring` and `ensuringError`

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val load: Order < (Abort[OrderNotFound] & Async) = ???

val withCleanup: Order < (Async & Scope & Sync & Abort[OrderNotFound]) =
    load.ensuring(Kyo.logInfo("load complete"))

val withErrorAwareCleanup: Order < (Async & Scope & Sync & Abort[OrderNotFound]) =
    load.ensuringError {
        case Present(err) => Kyo.logError(s"load failed: $err")
        case Absent       => Kyo.logInfo("load succeeded")
    }
```

`ensuring(finalizer)` registers `finalizer` to run when the surrounding `Scope` closes. `ensuringError` takes a function that receives `Maybe[Error[Any]]`: `Present(err)` when the effect failed, `Absent` when it succeeded.

> **Note:** `ensuring` registers the finalizer BEFORE `effect` runs (via `Scope.ensure(finalizer).andThen(effect)`). The finalizer will fire even if the effect never starts (e.g. if a prior step in the scope already failed). This is the safe behaviour for "always clean up," but differs from a try-finally where the cleanup runs only if the try-body started.

When you reach for `ensuring`: you have an existing effect and want to add cleanup AFTER it. When you reach for `Kyo.acquireRelease`: you're building the resource from scratch and want cleanup paired with construction. The latter is the better default; reach for `ensuring` when you can't restructure the surrounding code.

## Putting it together

The clusters above are orthogonal. A single chain can cross construction, sequencing, concurrency, error handling, and resource lifecycle without any intermediate types.

```scala
import kyo.*

opaque type OrderId = Long
opaque type Money   = BigDecimal
case class Order(id: OrderId, total: Money)
case class Item(sku: String, qty: Int, price: Money)
case class Profile(name: String, tier: String)
case class OrderNotFound(id: OrderId) extends Exception(s"Order $id not found")

object OrderRepo:
    def lookup(id: Long): Option[Order] = None

trait Database:
    def begin: Any < Sync

val db: Database                        = ???
val orderId: Long                       = 42L
val profileFor: Long => Profile < Async = _ => ???

// Construction: lift a fallible operation into Abort[OrderNotFound]
val load: Order < (Abort[OrderNotFound] & Async) =
    Kyo.fromOption(OrderRepo.lookup(orderId))
        .absentToFailure(OrderNotFound(orderId))

// Sequencing: log then load
val priced: Order < (Abort[OrderNotFound] & Async) =
    Kyo.logInfo("loading order") *> load

// Concurrency: fork a profile fetch and join after loading the order
val profileEff: Profile < Async = profileFor(orderId)
val withProfile: (Order, Profile) < (Abort[OrderNotFound] & Async & Scope & Sync) =
    for
        fiber   <- profileEff.fork
        order   <- load
        profile <- fiber.join
    yield (order, profile)

// Error handling: retry on failure, recover with a fallback if all retries fail
val resilient: Order < Async =
    load.retry(3).recover(_ => Order(0L, BigDecimal(0)))

// Resource lifecycle: acquire a transaction, ensure commit or rollback
val txnComp: Any < (Abort[OrderNotFound] & Async & Scope & Sync) =
    Kyo.acquireRelease(db.begin)(_ => Kyo.unit).map(_ => load)
```

## Dependency injection (Env)

`Env[E]` is the read-only context effect; you read dependencies with `Kyo.service[E]` / `Kyo.serviceWith[E]` (under [Construction](#env-constructors-reading-dependencies)) and supply them with the combinators below.

### `provideValue`: supply a single dependency

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val orderId: Long = 42L

trait Database
val db: Database = new Database {}
class OrderRepo(val database: Database):
    def load(id: Long): Order < Abort[OrderNotFound] = ???

val loadOrder: Order < (Env[OrderRepo] & Abort[OrderNotFound]) =
    Kyo.serviceWith[OrderRepo].apply(repo => repo.load(orderId))

val withRepo: Order < (Abort[OrderNotFound]) =
    loadOrder.provideValue(OrderRepo(db))
```

Use when you have a concrete instance of one dependency. The `Env[E]` is removed from the effect row for that single type; if the effect requires more than one dependency, you'll need to chain `provideValue` calls or use `provideLayer`/`provide`.

### `provideLayer`: supply via a `Layer`

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val orderId: Long = 42L

class OrderRepo:
    def load(id: Long): Order < Abort[OrderNotFound] = ???
object OrderRepo:
    val layer: Layer[OrderRepo, Any] = Layer(new OrderRepo)

val loadOrder: Order < (Env[OrderRepo] & Abort[OrderNotFound]) =
    Kyo.serviceWith[OrderRepo].apply(repo => repo.load(orderId))

val configured: Order < (Memo & Abort[OrderNotFound]) =
    loadOrder.provideLayer(OrderRepo.layer)
```

A `Layer[E, S]` is a deferred construction of `E` that may itself depend on other effects. `provideLayer` runs the layer to produce the dependency, then supplies it. The resulting effect row adds `Memo` because layers are memoised.

### `provide`: supply all required `Env` with multiple layers

```scala
import kyo.*

case class Order(id: Long)
case class OrderNotFound(id: Long) extends Exception(s"Order $id not found")
val orderId: Long = 42L

class OrderRepo:
    def load(id: Long): Order < Abort[OrderNotFound] = ???
object OrderRepo:
    val layer: Layer[OrderRepo, Any] = Layer(new OrderRepo)

val loadOrder: Order < (Env[OrderRepo] & Abort[OrderNotFound]) =
    Kyo.serviceWith[OrderRepo].apply(repo => repo.load(orderId))

val fullyConfigured: Order < (Abort[OrderNotFound] & Memo) =
    loadOrder.provide(OrderRepo.layer)
```

`provide` is a `transparent inline` macro that takes a variable number of layers and supplies ALL of the effect's `Env[*]` requirements. The return type is `A < Nothing`: no effects remain, the computation is ready to run.

> **Caution:** Because `provide` is `transparent inline`, under-provisioning produces a macro-flavoured compile error rather than a typed residue. If you forget a layer, the error message will reference `Env.runLayer` macro expansion rather than naming the missing dependency clearly. Read the error carefully or temporarily switch to chained `provideLayer` calls to localise which dependency is missing.

`Kyo.provideFor` (in [Construction](#construction)) is the companion form for single-dependency wiring: it accepts a dependency value and an effect, complementing the extension methods `.provideValue`, `.provideLayer`, and `.provide` shown above.
