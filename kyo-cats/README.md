# kyo-cats

Kyo's interop module for Cats Effect. Two directions, two methods: `Cats.get` lifts a `cats.effect.IO[A]` into a Kyo computation so it can be sequenced and composed alongside `Abort` and `Async`, and `Cats.run` interprets a Kyo computation back into a `cats.effect.IO[A]` so a Cats-Effect-based program can drive it. Cancellation propagates both ways: interrupting the Kyo side cancels the underlying Cats `IO`, and cancelling the Cats fiber interrupts the Kyo fiber.

The interop uses Cats Effect's `IORuntime.global` to schedule lifted `IO` actions, and Kyo's own scheduler for the Kyo side, so the two runtimes coexist rather than one wrapping the other. `Cats.run` only accepts computations whose pending row is `Abort[Throwable] & Async`. Any other effects must be handled first. The module is published for JVM and Scala.js.

A quick taste: lift a Cats `IO` value and sequence it with Kyo:

```scala
import cats.effect.IO as CatsIO

val fetchName: CatsIO[String] = CatsIO.pure("Alice")

// Lift into Kyo; compose with native Kyo work in one for-comprehension.
val greeting: String < (Abort[Nothing] & Async) =
    for
        name  <- Cats.get(fetchName)
        upper <- Sync.defer(name.toUpperCase)
    yield s"Hello, $upper"
```

The rest of this document walks the two bridges and the error and cancellation semantics that govern them.

## Bridging Cats Effect and Kyo

The module is a single `object kyo.Cats` with two methods. One direction per method.

### `Cats.get`: lifting `cats.effect.IO` into Kyo

When you have an existing Cats Effect value (a client call, a database query, a third-party library that returns `IO`) and you want to sequence it inside a Kyo computation, wrap it with `Cats.get`. The result is a Kyo value whose pending row contains `Async`:

```scala
import cats.effect.IO as CatsIO

val fetched: Int < (Abort[Nothing] & Async) =
    Cats.get(CatsIO.pure(10))

val doubled: Int < (Abort[Nothing] & Async) =
    fetched.map(_ * 2)
```

The signature is `Cats.get[A](io: => CatsIO[A]): A < (Abort[Nothing] & Async)`. The `IO` argument is by-name, so construction is deferred until the surrounding Kyo computation runs. The `Abort[Nothing]` half of the row is deliberate; failures from the inner `IO` do NOT surface there. See the error mapping section below.

> **Note:** `Cats.get` schedules the lifted `IO` on `cats.effect.unsafe.implicits.global`, the global `IORuntime`. If your Cats-Effect program installs a custom `IORuntime`, lifted `IO`s still run on the global one, not on yours.

Inside a Kyo for-comprehension, `Cats.get` results compose with native Kyo concurrency primitives:

```scala
import cats.effect.IO as CatsIO

val a: Int < (Abort[Nothing] & Async) = Cats.get(CatsIO.pure(1))
val b: Int < (Abort[Nothing] & Async) = Fiber.initUnscoped(2).map(_.get)

val zipped: (Int, Int) < (Abort[Nothing] & Async) = Async.zip(a, b)
```

Nesting works in both directions: a `Cats.get(...)` may appear inside a `Cats.run(...)` block and vice versa. Each crossing schedules through both runtimes, so deep nesting costs real work; it is not free.

### `Cats.run`: interpreting Kyo as `cats.effect.IO`

When you need to hand a Kyo computation to a Cats-Effect-based driver (a Cats Effect `IOApp`, an `unsafeRunSync()` call site, a library that consumes `IO`), use `Cats.run`. The signature is `Cats.run[A](v: => A < (Abort[Throwable] & Async)): CatsIO[A]`.

```scala
import cats.effect.IO as CatsIO

val kyoWork: Int < (Abort[Throwable] & Async) =
    Async.delay(10.millis)(42)

val asIO: CatsIO[Int] = Cats.run(kyoWork)
```

The pending row must be exactly `Abort[Throwable] & Async`. The compiler enforces it; this is not a runtime check. If your computation carries other effects (`Env`, `Var`, `Scope`, `Choice`, a narrower `Abort` row), handle or widen them first:

```scala
import cats.effect.IO as CatsIO

case class Config(timeoutMs: Int)

val withEnv: Int < (Env[Config] & Abort[Throwable] & Async) = ???

// Handle Env before crossing; then Cats.run accepts the result.
val readyForCats: Int < (Abort[Throwable] & Async) =
    Env.run(Config(1000))(withEnv)

val asIO: CatsIO[Int] = Cats.run(readyForCats)
```

Inside `Cats.run`, the Kyo computation is started via `Fiber.initUnscoped`. The Kyo fiber is not attached to the caller's `Scope`; its lifetime is controlled by the returned `CatsIO`, including cancellation. Treat `Cats.run` as a runtime boundary, not as a scoped resource.

## Error and cancellation semantics

The two bridges do not unify Kyo's `Abort` channel with Cats' `IO` error channel. Each direction has specific, asymmetric behavior that the reader must understand to write correct interop code.

### Cats failure becomes Kyo `Panic`

A Cats `IO` failure surfaces on the Kyo side as `Result.Panic`, never as `Result.Failure`. This is why `Cats.get` returns `A < (Abort[Nothing] & Async)` rather than `A < (Abort[Throwable] & Async)`: there is no typed `Abort.fail` arriving from the inner `IO`.

```scala
import cats.effect.IO as CatsIO

val failingIo: CatsIO[Int] =
    CatsIO.raiseError(new RuntimeException("boom"))

val lifted: Int < (Abort[Nothing] & Async) = Cats.get(failingIo)

val asResult: Result[Nothing, Int] < Async =
    Abort.run(lifted)
// when run, asResult yields Result.Panic(RuntimeException("boom"))
// it does NOT yield Result.Failure(RuntimeException("boom"))
```

> **Unlike** the common reader expectation that `IO.raiseError(e)` becomes `Abort.fail(e)`, kyo-cats treats every `IO` failure as an unexpected condition and reifies it as `Panic`. To match on it, pattern-match `Result.Panic(ex)`. `Result.Failure(ex)` will never fire for a value that came from `Cats.get`.

If you want a typed `Abort` channel from a Cats `IO`, convert on the Cats side first (e.g. via `attempt` or `IO.fromEither`) and lift only success values, raising a typed Kyo error explicitly.

### Kyo failure becomes Cats `IO.raiseError`

In the reverse direction, `Cats.run` converts the Kyo `Result` to an `Either` by `r.map(_.eval).toEither`. A Kyo `Panic` is reified as a `Left(throwable)` and surfaces on the Cats side as `IO.raiseError`. A successful `Result` evaluates eagerly inside the Cats async callback, so any deferred work the Kyo computation still represents runs at the boundary.

### Combined errors are ordering-sensitive

When Kyo `Abort.fail` and a Cats `IO.raiseError` both participate in one chain, the one sequenced first wins; there is no merge.

```scala
import cats.effect.IO as CatsIO

object catsFailure extends RuntimeException
object kyoFailure  extends RuntimeException

// Kyo failure first: the Cats failure never runs.
val kyoFirst: Result[Throwable, Unit] < Async =
    val a = Abort.fail(kyoFailure)
    val b = Cats.get(CatsIO.raiseError(catsFailure))
    Abort.run[Throwable](a.map(_ => b))
end kyoFirst
// kyoFirst yields Result.Failure(kyoFailure)

// Cats failure first: it surfaces as Panic and the Kyo Abort.fail never runs.
val catsFirst: Result[Throwable, Unit] < Async =
    val a = Cats.get(CatsIO.raiseError(catsFailure))
    val b = Abort.fail(kyoFailure)
    Abort.run[Throwable](a.map(_ => b))
end catsFirst
// catsFirst yields Result.Panic(catsFailure)
```

> **Caution:** there is no unified error semantics across the bridge. The position of the failure in the chain decides whether it lands as `Failure` (always from a typed Kyo `Abort.fail`) or `Panic` (always from a lifted Cats `IO` error). Code that expects a single canonical error shape will be surprised.

### Cancellation is bidirectional

Cancellation propagates across the bridge in both directions, driven by the runtime that owns the outer fiber:

- **Kyo side interrupted:** `Cats.get` registers `p.onInterrupt(_ => cancel())`, where `cancel` is the canceller returned by Cats' `unsafeToFutureCancelable`. Interrupting the Kyo fiber that wraps a lifted `IO` cancels the `IO`.
- **Cats side cancelled:** `Cats.run` returns an `IO.async` whose finalizer calls `fiber.unsafe.interrupt()`. Cancelling the Cats fiber (e.g. via `f.cancel`) interrupts the Kyo fiber.

This holds for compositions on either side. A Cats program that races a `Cats.run(kyoWork)` against another `IO` will interrupt the Kyo fiber when the race resolves; a Kyo program whose outer fiber is interrupted will cancel any in-flight `Cats.get` it carries.

```scala
import cats.effect.IO as CatsIO

// Cats-side cancellation reaching into Kyo.
def kyoLoop: Unit < Sync =
    def loop(): Unit < Sync = Sync.defer(loop())
    loop()

val cancellable: CatsIO[Unit] = Cats.run(kyoLoop)
// `cancellable.start.flatMap(f => f.cancel >> f.join)` yields a cancelled outcome;
// the Kyo loop stops because the surrounding fiber was interrupted.
```

Cancellation is the contract by which the two runtimes stay tidy under load. Use it instead of relying on `Scope`: the Kyo fiber created by `Cats.run` is unscoped, so resources tied to the caller's `Scope` are not held open across the Cats boundary.

## Runtime placement

Two scheduling decisions are baked into the module and matter for performance and observability:

- Lifted `IO`s run on `cats.effect.unsafe.implicits.global`. Custom `IORuntime` instances installed elsewhere in your program are not consulted by `Cats.get`.
- Kyo computations crossed via `Cats.run` execute on Kyo's scheduler through `Fiber.initUnscoped`. The Cats async callback is invoked when the Kyo fiber completes; the success value evaluates inside that callback.

The two runtimes coexist; neither wraps the other. Profiling a mixed program will show both schedulers active, each serving its own half of the computation.
