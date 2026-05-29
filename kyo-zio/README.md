# kyo-zio

<!-- doctest:setup
```scala
import kyo.*
import zio.{ZIO, Cause, Exit, ZLayer}
import zio.stream.ZStream

case class UserId(value: Long)
case class User(id: UserId, name: String)
case class Balance(userId: UserId, cents: Long)
final case class FetchError(reason: String) extends RuntimeException(reason)

trait DbPool:
    def query(sql: String): String < Async
case class DbPoolImpl(name: String) extends DbPool:
    def query(sql: String): String < Async = s"[$name] $sql"
```
-->

Bidirectional interop between Kyo and ZIO. The module lets you call ZIO code from a Kyo computation, or run a Kyo computation as ZIO, without committing the whole program to either runtime. Three pairs cover the surface: `ZIOs.get` / `ZIOs.run` for `ZIO[Any, E, A]` values, `ZStreams.get` / `ZStreams.run` for `zio.stream.ZStream`, and `ZLayers.get` / `ZLayers.run` for `ZLayer`. The convention is consistent: `get` lifts a ZIO-side value into a Kyo computation (typed `A < (Abort[E] & Async)`), `run` interprets a Kyo-side value into ZIO.

Interop preserves failure semantics and interruption across the boundary. `ZIO.fail` becomes `Abort[E]`, `ZIO.die` becomes `Result.Panic`, ZIO interruption translates to a Kyo panic carrying `Fiber.Interrupted`, and `Cause` shapes (`Then`, `Both`, `Stackless`, `Empty`) collapse to a single `Result.Error`. In the reverse direction, Kyo's `Result.Failure` and `Result.Panic` map back to `Cause.fail` and `Cause.die`. Interruption is bidirectional: cancelling on either side propagates to the running effect on the other. ZIO environments (`ZIO[R, E, A]` with `R != Any`) are not supported and must be eliminated before lifting.

kyo-zio is cross-compiled for JVM, JS, and Native; all sources live in `shared/`.

```scala
val fetched: User < (Abort[FetchError] & Async) =
    ZIOs.get(ZIO.succeed(User(UserId(42), "Ada")))
```

## Lifting and running effects

This is the first cluster you reach for. `ZIOs.get` brings a ZIO value into a Kyo computation; `ZIOs.run` drives a Kyo computation as a ZIO. The two directions are the building blocks for everything below; the streams and layers clusters wrap the same pattern around different carrier types.

### Lifting a ZIO into Kyo

When you have a pre-existing ZIO library call and the surrounding orchestration is Kyo, lift the ZIO with `ZIOs.get`. The result is a Kyo computation whose effect row includes `Abort[E]` (mirroring the ZIO error channel) and `Async` (because the underlying ZIO is forked on `Runtime.default`).

```scala
def fetchUser(id: UserId): ZIO[Any, FetchError, User] =
    ZIO.succeed(User(id, s"user-${id.value}"))

val user: User < (Abort[FetchError] & Async) =
    ZIOs.get(fetchUser(UserId(7)))
```

A ZIO that succeeds yields a Kyo value of the same type. A ZIO that fails yields an `Abort[E]`:

```scala
val failing: Int < (Abort[String] & Async) =
    ZIOs.get(ZIO.fail("downstream error"))

val handled: Result[String, Int] < Async = Abort.run(failing)
```

`ZIOs.get` is `=> ZIO[Any, E, A]` (by-name), so the ZIO is constructed on every lift, not captured at definition. You can mix Kyo and ZIO calls freely inside the same `for`-comprehension; each `ZIOs.get` is a fresh fork on the default runtime:

```scala
def fetchUser(id: UserId): User < (Abort[FetchError] & Async) =
    ZIOs.get(ZIO.succeed(User(id, s"user-${id.value}")))

val pipeline: Long < (Abort[FetchError] & Async) =
    for
        u <- fetchUser(UserId(1))
        b <- ZIOs.get(ZIO.succeed(Balance(u.id, 1000L)))
        _ <- Log.info(s"user=${u.name} cents=${b.cents}")
    yield b.cents
```

### Running a Kyo as ZIO

When the entry point is a ZIO application and the inner work is written in Kyo, `ZIOs.run` interprets a Kyo computation reduced to `Abort[E] & Async` into `ZIO[Any, E, A]`. The Kyo computation runs on its own scheduler; `ZIOs.run` connects completion and interruption back through `ZIO.asyncInterrupt`.

```scala
val kyoWork: Int < (Abort[Throwable] & Async) =
    Async.sleep(5.millis).andThen(42)

val asZio: ZIO[Any, Throwable, Int] = ZIOs.run(kyoWork)
```

Discharge other Kyo effects (`Sync`, `Env`, `Memo`, `Scope`, ...) before calling `ZIOs.run`; it accepts only `Abort[E] & Async`. The compiler enforces this: a leftover `Env[Foo]` in the row produces a type error at the call site, not at runtime.

### ZIO environments are rejected at compile time

`ZIOs.get` has a second overload that catches `ZIO[R, E, A]` with a non-`Any` environment and produces a `compiletime.error`. You must `.provide` (or otherwise eliminate) the environment ZIO-side before lifting.

```scala
// Will not compile: "ZIO environments are not supported yet."
// val bad: Int < (Env[Int] & Async) = ZIOs.get(ZIO.service[Int])

val provided: ZIO[Any, Nothing, Int] = ZIO.service[Int].provide(ZLayer.succeed(42))
val good: Int < (Abort[Nothing] & Async) = ZIOs.get(provided)
```

> **Note:** The rejection is a hard `compiletime.error`, not a runtime check or a TODO. The scaladoc for the overload still calls it a "placeholder," but the compile-time message is what you actually see.

### Failure ordering at the boundary

When a Kyo `Abort.fail` runs before a `ZIOs.get`, the Kyo failure wins; the ZIO failure does not override. The reverse holds too. Composition order matters, exactly as it does within a single runtime.

```scala
object KyoFailure extends RuntimeException
object ZioFailure extends RuntimeException

val kyoFirst =
    Abort.fail(KyoFailure).map(_ => ZIOs.get(ZIO.fail(ZioFailure)))

val result: Result[RuntimeException, Any] < Async = Abort.run(kyoFirst)
// result is Result.Failure(KyoFailure)
```

> **Note:** Interruption from the ZIO side surfaces as `Result.Panic(Fiber.Interrupted(...))`, not as Kyo's built-in interruption channel. A consumer checking `result.isInterrupted` should check `result.isPanic` (and inspect the panic) instead.

### Cancellation semantics on `ZIOs.run`

`ZIOs.run` uses `Fiber.initUnscoped` internally, so the lifted Kyo computation is not cancelled by an enclosing `Scope.run` on the ZIO side. It is cancelled only by ZIO-side interruption flowing through `ZIO.asyncInterrupt`. Cancellation in the other direction (Kyo cancelling a `ZIOs.get`) calls `f.unsafe.interrupt` on the ZIO fiber.

## Streams

`ZStreams.get` and `ZStreams.run` apply the same `get`/`run` shape to `zio.stream.ZStream` and `kyo.Stream`. Use them when the carrier is a stream of chunks instead of a single value.

### Lifting a `ZStream` into Kyo

```scala
val zioStream: ZStream[Any, Nothing, UserId] =
    ZStream.fromIterable(List(UserId(1), UserId(2), UserId(3)))

val kyoStream: Stream[UserId, Abort[Nothing] & Async] =
    ZStreams.get(zioStream)

val collected: Chunk[UserId] < (Abort[Nothing] & Async) =
    kyoStream.run
```

The lifted stream behaves like any other `kyo.Stream`: you can `.take`, `.map`, fold, race, or run it concurrently. Each lift consumes the source ZIO stream once per terminal operation.

### Scoping and finalizers

`ZStreams.get` opens a fresh `zio.Scope` per call and registers its closure as a finalizer on the surrounding Kyo `Scope`. The lifted stream must be consumed inside a `Scope.run` (or an outer effect that already provides `Scope`); on completion or interruption, the ZIO scope closes and the upstream's finalizers run.

```scala
val drained: Chunk[Int] < (Abort[Throwable] & Async) =
    Scope.run:
        ZStreams.get(ZStream.fromIterable(List(1, 2, 3, 4, 5))).run
```

> **Caution:** A `ZStreams.get` consumed without `Scope.run` (or another `Scope`-supplying effect) on the path will not get its upstream finalizers invoked. Always run inside a scope.

### Running a Kyo `Stream` as `ZStream`

```scala
import scala.reflect.ClassTag

val kyoSrc: Stream[Int, Any] = Stream.init(List(1, 2, 3, 4, 5))

val asZioStream: ZStream[Any, Nothing, Int] = ZStreams.run(kyoSrc)

val collected: ZIO[Any, Nothing, zio.Chunk[Int]] = asZioStream.runCollect
```

> **Caution:** `ZStreams.run` requires `ClassTag[A]`; each chunk is copied into a `zio.Chunk.fromArray`. Element types without a `ClassTag` (e.g. abstract or generic without the right context bound) will not compile. Add a `: ClassTag` context bound to the type parameter at the call site.

`ZStreams.run` wraps each pull in `ZIO.uninterruptibleMask` to keep emission atomic against interruption; the ZIO consumer can still cancel between pulls.

### Round-tripping

`get` and `run` compose cleanly. A round trip leaves elements intact:

```scala
val original: ZStream[Any, Nothing, Int]      = ZStream.fromIterable(List(1, 2, 3, 4, 5))
val viaKyo: Stream[Int, Abort[Nothing] & Async] = ZStreams.get(original)
val backToZio: ZStream[Any, Nothing, Int]     = ZStreams.run(viaKyo)
```

## Layers

`ZLayers.get` and `ZLayers.run` apply the same shape to dependency provision. A `ZLayer` becomes a `kyo.Layer`; a Kyo layer becomes a `ZLayer`.

### Lifting a `ZLayer` into Kyo

```scala
val dbPoolZLayer: ZLayer[Any, Nothing, DbPool] =
    ZLayer.succeed(DbPoolImpl("primary"))

val dbPoolLayer: Layer[DbPool, Abort[Nothing] & Async & Scope] =
    ZLayers.get(dbPoolZLayer)

val program: String < (Abort[Nothing] & Async) =
    Env.runLayer(dbPoolLayer):
        Env.use[DbPool](_.query("select 1"))
    .handle(Memo.run, Scope.run)
```

> **Note:** `ZLayers.get` always returns a layer whose effect row includes `Scope`, even when the source `ZLayer` is not scoped. The bridge allocates a `zio.Scope` to host any finalizers, so the resulting Kyo layer carries `Scope` unconditionally. Discharge it with `Scope.run` on the call site.

A scoped source layer behaves as expected: its acquire runs on layer build, its release runs when the surrounding Kyo `Scope` closes, and the close path receives the appropriate `Exit` (success, failure, or panic) based on how the consuming effect terminated.

```scala
val acquired = java.util.concurrent.atomic.AtomicInteger(0)
val scoped: ZLayer[Any, Nothing, Int] =
    ZLayer.scoped:
        zio.Scope.addFinalizer(ZIO.succeed(())) *>
            ZIO.succeed { acquired.incrementAndGet(); 42 }

val kLayer: Layer[Int, Abort[Nothing] & Async & Scope] = ZLayers.get(scoped)
```

### Running a Kyo `Layer` as `ZLayer`

```scala
val poolLayer: Layer[DbPool, Sync] =
    Layer(Sync.defer(DbPoolImpl("from-kyo")))

val zlayer: ZLayer[Any, Nothing, DbPool] = ZLayers.run(poolLayer)

val zApp: ZIO[Any, Nothing, String] =
    ZIO.serviceWithZIO[DbPool](p => ZIOs.run(p.query("select 2"))).provide(zlayer)
```

> **Note:** `ZLayers.run` calls `Memo.run` internally on the Kyo layer, which matches ZIO's once-per-environment semantics for `ZLayer`. The `Memo` effect is discharged inside `run`, not propagated outward; consumers of the resulting `ZLayer` see a normal ZIO surface.

### When to use which

When the entry point is a Kyo program that wants to consume an existing ZIO-defined resource (a JDBC pool, an `STM`-backed cache, anything published as a `ZLayer`), use `ZLayers.get` and feed the result into `Env.runLayer`. When the entry point is a ZIO program and a resource is most natural to write as a `kyo.Layer` (for example, because the construction uses Kyo effects), use `ZLayers.run` to expose it to `.provide`.

## Failure and interruption translation

Four extension methods make the boundary's translation table explicit. They are the rule book the `get`/`run` pairs use internally; you see them most when debugging unexpected `Result.Panic` values or building custom adapters.

### `Exit.toResult` and `Result.toExit`

```scala
import kyo.ZIOs.*

val r1: Result[String, Int] = Exit.succeed(42).toResult        // Result.Success(42)
val r2: Result[String, Int] = Exit.fail("boom").toResult       // Result.Failure("boom")

val e1: Exit[String, Int] = Result.succeed(42).toExit          // Exit.Success(42)
val e2: Exit[String, Int] = Result.fail("boom").toExit         // Exit.Failure(...)
val e3: Exit[String, Int] = Result.Panic(new Exception).toExit // Exit.Die(...)
```

### `Cause.toError` and `Result.Error.toCause`

`Cause` has more shapes than `Result.Error`. The translation collapses what doesn't fit:

| `Cause` shape                  | `Result.Error` produced                                         |
|--------------------------------|-----------------------------------------------------------------|
| `Cause.Fail(e, _)`             | `Result.Failure(e)`                                             |
| `Cause.Die(e, _)`              | `Result.Panic(e)`                                               |
| `Cause.Interrupt(fiberId, _)`  | `Result.Panic(Fiber.Interrupted(frame, fiberId.threadName))`    |
| `Cause.Then(left, right)`      | `loop(left).orElse(loop(right))` (leftmost wins)                |
| `Cause.Both(left, right)`      | `loop(left).orElse(loop(right))` (leftmost wins)                |
| `Cause.Stackless(inner, _)`    | `loop(inner)` (unwrapped)                                       |
| `Cause.Empty`                  | `Result.Panic(new Exception("Unexpected zio.Cause.Empty at ..."))` |

```scala
import kyo.ZIOs.*

val both: Cause[String] = Cause.Both(Cause.fail("left"), Cause.fail("right"))
val collapsed: Result.Error[String] = both.toError
// collapsed is Result.Failure("left"); the right branch is dropped.
```

> **Caution:** `Then` and `Both` are lossy at the boundary. ZIO's parallel-failure and sequential-failure composition both collapse to the leftmost branch; the right branch is discarded. If you need both, project the `Cause` ZIO-side before lifting.

The reverse direction is total:

```scala
import kyo.ZIOs.*

val asCause1: Cause[String] = Result.Failure("boom").toCause  // Cause.fail("boom")
val asCause2: Cause[String] = Result.Panic(new Exception("x")).toCause // Cause.die(...)
```

> **Note:** `Cause.Empty` does not map to a Kyo "no error" value; it maps to a synthetic panic with the message `"Unexpected zio.Cause.Empty at <frame>"`. If you see this panic, the upstream ZIO produced an empty cause and you are looking at the kyo-zio fallback, not a ZIO bug.

### Interruption as panic

`Cause.Interrupt` translates to `Result.Panic(Fiber.Interrupted(...))`, not to Kyo's interruption channel. This is the most surprising entry in the table, so it is worth restating: a ZIO-side interruption that crosses into Kyo looks like a panic to Kyo callers.

```scala
val interrupted: Int < (Abort[Nothing] & Async) =
    ZIOs.get(ZIO.never.fork.flatMap(_.interrupt).flatten)

Abort.run(interrupted).map { result =>
    assert(result.isPanic)
}
```

In the opposite direction, when a Kyo fiber running under `ZIOs.run` is interrupted from the ZIO side, the Kyo fiber's `unsafe.interrupt()` is called and the resulting `Result.Panic` is mapped to `Exit.die`.
