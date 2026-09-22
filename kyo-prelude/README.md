<!-- doctest:default expect=runs -->

# kyo-prelude

Kyo's library of pure, handler-based effects. Each effect names a capability the program declares in its signature (an `Abort[E]` for typed failure, an `Env[R]` for required dependencies, a `Var[V]` for tracked mutable state, an `Emit[V]` / `Poll[V]` for push/pull streaming, a `Choice` for non-determinism), and the program stays a value until you run it. To execute, you hand each effect off to a matching handler (`Abort.run`, `Env.run`, `Var.run`, ...), which discharges that capability from the row and decides what its values mean: collect them, fold them, lift them into another effect, or drop them. Effects compose freely in a single computation through Kyo's `&` intersection in the pending-effects slot, and they are discharged independently in whatever order the program chooses; the order decides what each handler sees.

On top of these primitives the module builds `Stream[V, S]`, a chunked, lazy sequence backed by `Emit[Chunk[V]]` underneath and connectable to `Poll`-shaped consumers through `Pipe[A, B, S]` (transforms) and `Sink[V, A, S]` (terminal folds). Dependency wiring is handled by `Layer`, which is compile-time-resolved by `Layer.init` and discharged into `Env`. Cross-cutting concerns get a handful more tools: `Local[A]` for thread-local-style context with defaults, `Aspect` for AOP-style interception with multi-shot continuations, `Memo` for memoizing expensive computations, `Check` for accumulating validation failures, and `Batch` for transparent N+1 grouping.

The examples below all operate on a small request-handling domain: looking up users by id, fetching their orders, validating field values, wiring a repository and configuration as dependencies. Defining it once here lets later sections introduce one capability at a time on values you have already seen.

```scala doctest:setup
case class User(id: Int, email: String, name: String) derives CanEqual

case class Order(id: Int, userId: Int, total: BigDecimal, status: Order.Status) derives CanEqual
object Order:
    enum Status derives CanEqual:
        case Pending, Confirmed, Shipped, Cancelled

case class ValidationError(field: String, reason: String) derives CanEqual

case class Config(maxOrders: Int, currency: String)

trait UserRepo:
    def fetchUsers(ids: Seq[Int]): Map[Int, User] < Any
    def fetchOrders(userId: Int): Chunk[Order] < Any
```

## Failure and recovery

When a function can fail in a way you want the caller to see in the type system, declare an `Abort[E]` in the pending row. The error type `E` can be anything (a sealed `enum`, a `String`, a `Throwable`). The handler at the recovery boundary decides whether to log, retry, recover, or rethrow.

A computation in `Abort[E]` completes in one of three ways: success with a value, `Failure(e: E)` for a modelled domain error, or `Panic(throwable)` for an unexpected exception. The three handlers differ in how they treat `Panic`: `run` returns it as part of `Result`, `recover` leaves it unhandled in `Abort[Nothing]`, and the `OrThrow` variants rethrow it.

### Declaring a failure

`Abort.fail` lifts a value into the failure channel; `Abort.when` / `Abort.unless` / `Abort.ensuring` fail conditionally; `Abort.catching` wraps an exception-throwing block so the typed channel sees the throw as an `E`. `Abort.panic(throwable)` introduces a `Panic` directly (bypassing the typed channel); `Abort.error(error)` lifts an explicit `Error[E]` (either `Failure` or `Panic`) back into the effect; `Abort.loopUntil(body)` repeats `body` until the computation short-circuits via `Abort[E]`.

```scala
def parseId(s: String): Int < Abort[ValidationError] =
    Abort.catching[NumberFormatException](_ => ValidationError("id", "not a number"))(s.toInt).map { i =>
        Abort.when(i < 0)(ValidationError("id", "must be non-negative")).andThen(i)
    }

assert(Abort.run(parseId("42")).eval == Result.succeed(42))
assert(Abort.run(parseId("-1")).eval == Result.fail(ValidationError("id", "must be non-negative")))
```

### Lifting standard types

`Abort.get` is overloaded for `Either`, `Option`, `scala.util.Try`, `kyo.Maybe`, and `kyo.Result`. `Option` / `Maybe` lift `None` / `Absent` to a failure of type `Absent`, so the pending effect is `Abort[Absent]`; `Either[L, R]` lifts a `Left(l)` to `Abort[L]`; `Try` lifts a thrown exception to `Abort[Throwable]`.

```scala
import kyo.*

val fromEither: Int < Abort[String] = Abort.get(Right(1))
val fromOption: Int < Abort[Absent] = Abort.get(Option(7))
val fromTry: Int < Abort[Throwable] = Abort.get(scala.util.Try("3".toInt))

assert(Abort.run(fromEither).eval == Result.succeed(1))
assert(Abort.run(fromOption).eval == Result.succeed(7))
```

### Handling failure: run, recover, fold

`Abort.run[E]` discharges the effect into a `Result[E, A]` value, preserving both `Failure` and `Panic`. `recover` with one handler runs it on `Failure` and leaves `Panic` in `Abort[Nothing]` for an upstream handler; with a second handler, as in the example below, it consumes panics too. `fold` requires handlers for both success and failure.

```scala
val program: Int < Abort[ValidationError] =
    Abort.fail(ValidationError("age", "negative"))

val asResult: Result[ValidationError, Int] < Any =
    Abort.run(program)

val recovered: Int < Any =
    Abort.recover[ValidationError](e => 0, _ => -1)(program)

val message: String < Any =
    Abort.fold[ValidationError](
        onSuccess = (n: Int) => s"got $n",
        onFail = (e: ValidationError) => s"bad ${e.field}: ${e.reason}",
        onPanic = (t: Throwable) => s"panic: ${t.getMessage}"
    )(program)

assert(recovered.eval == 0)
assert(message.eval == "bad age: negative")
```

> **Note:** `Abort.run[E]` requires a `ConcreteTag[E]` and discharges only the failure subtypes that satisfy it. Given `Abort[E1 | E2]`, `Abort.run[E1]` peels off `E1` and leaves `Abort[E2]` in the remaining row. To handle the whole union in one call, name the union: `Abort.run[E1 | E2](...)`.

### Run variants that drop panic

`Abort.runPartial` returns a `Result.Partial[E, A]` (only `Success` or `Failure`) and re-raises any `Panic` through `Abort[ER]`. `Abort.runPartialOrThrow` returns `Result.Partial` and throws on panic. `Abort.ignore` drops both success and failure values, re-raising panic.

```scala
import kyo.*

val ok: Int < Abort[String] = 1

val partial: Result.Partial[String, Int] < Abort[Nothing] =
    Abort.runPartial[String](ok)

assert(Abort.run(partial).eval == Result.succeed(Result.succeed(1)))
```

### Recover-only-failure vs fold-and-rethrow

When you want recovery only for declared domain errors and want unexpected panics to escape, use `recover` (leaves `Abort[Nothing]`) or `recoverOrThrow` (throws). When you want to consume the whole result including unexpected exceptions, use `recover(onFail, onPanic)` or `fold(onSuccess, onFail, onPanic)`. `recover(onFail)` and `recoverOrThrow` leave panic in some form; the multi-handler variants consume it. `Abort.recoverError` recovers from any `Error[E]` (both `Failure` and `Panic`) with a single handler. `Abort.foldError` folds over an `Error[E]` alongside a success branch. `Abort.foldOrThrow` folds success and typed failures while rethrowing panics as exceptions.

### Observing a failure without consuming it

`Abort.tap` runs a side effect when a computation fails with an `E` and then re-raises the same failure, so the error stays in the row for a downstream handler to decide its fate; success values and panics pass through without evaluating the observer. `Abort.tapError` additionally observes panics, receiving the full `Error[E]`. They are the non-consuming siblings of `recover` / `recoverError`, for intermediate observation such as logging or metrics.

```scala
import kyo.*

val (seen, result) =
    Var.runTuple(Maybe.empty[String]) {
        Abort.run[String](Abort.tap[String](e => Var.set(Present(e): Maybe[String]))(Abort.fail("boom")))
    }.eval

assert(result == Result.fail("boom"))
assert(seen == Present("boom"))
```

### Singleton-typed errors

`Abort.literal.fail("not_found")` preserves the singleton type `"not_found"` instead of widening to `String`, useful when you want the type system to track exactly which symbolic errors a function can produce.

```scala
import kyo.*

val checked: Int < Abort["not_found" | "denied"] =
    Abort.literal.fail("not_found")
```

## Dependencies

When a function needs a value that the caller must supply (a `UserRepo`, a `Config`), declare it in the pending row as `Env[R]`. The compiler then refuses to run the program until every required `R` has a handler. Compose multiple requirements as an intersection: `Env[UserRepo & Config]`.

`Env` differs from `Local` in one axis: `Env` requires a handler to discharge and the compiler enforces it; `Local` has a default and never appears in the pending row. Use `Env` when missing the value is a programmer error you want to catch at compile time; use `Local` when missing the value should fall back to a default.

### Reading the environment

`Env.get[R]` retrieves a single value; `Env.use[R](f)` reads `R` and continues with `f` in one step; `Env.getAll[R1 & R2]` reads the complete TypeMap when you want several services at once.

```scala
val limit: Int < Env[Config] =
    Env.use[Config](_.maxOrders)

assert(Env.run(Config(maxOrders = 10, currency = "USD"))(limit).eval == 10)
```

### Providing values directly

For small programs and tests, `Env.run(value)(computation)` provides a single value, and `Env.runAll(typeMap)(computation)` provides a precomputed `TypeMap`.

```scala
val rendered: String < Env[Config] =
    Env.use[Config](c => s"${c.currency} cap=${c.maxOrders}")

assert(Env.run(Config(5, "EUR"))(rendered).eval == "EUR cap=5")
```

### Wiring with layers

`Layer[Out, S]` describes how to build an `Out` value (possibly using other layers' outputs through `Env`). Layers compose with `and` (independent), `to` (the right side uses the left's output), and `using` (combine and chain). For most code you do not write these manually: `Layer.init[Target](layers*)` resolves the graph at compile time, and `Env.runLayer(layers*)(program)` discharges the resulting `Env[Target]` from `program`.

```scala
val configLayer: Layer[Config, Any] =
    Layer(Config(maxOrders = 10, currency = "USD"))

val repoLayer: Layer[UserRepo, Env[Config]] =
    Layer.from { (c: Config) =>
        new UserRepo:
            def fetchUsers(ids: Seq[Int]) =
                ids.map(id => id -> User(id, s"u$id@example.com", s"User $id")).toMap
            def fetchOrders(userId: Int) = Chunk.empty[Order]
    }

val program: Maybe[User] < Env[UserRepo] =
    Env.use[UserRepo](_.fetchUsers(Seq(42)).map(users => Maybe.fromOption(users.get(42))))

val handled: Maybe[User] < Any =
    Memo.run(Env.runLayer(configLayer, repoLayer)(program))

assert(handled.eval == Present(User(42, "u42@example.com", "User 42")))
```

> **Note:** `Layer.init` and `Env.runLayer` are `transparent inline` macros. Missing dependencies produce a compile-time error pointing at the call site, not a runtime exception.

> **Note:** `layer.run` evaluates a layer to a `TypeMap[Out]` but introduces a `Memo` effect (the layer engine memoizes constructions to avoid duplicate work). `Env.runLayer` runs the layers through `layer.run`, so it leaves that `Memo` in the row too: wrap either one in `Memo.run`, as the example above does.

## Contextual values

When a value has a sensible default and is mostly read (a request id, a trace context, a debug verbosity), use `Local[A]`. Unlike `Env`, a `Local` never appears in the pending row: it has a default, you can override it within a scope with `let` or `update`, and the read methods (`get`, `use`) work in any computation.

### Inheritable vs non-inheritable

`Local.init(default)` creates an inheritable local: child fibers spawned inside a `let` scope see the parent's value. `Local.initNoninheritable(default)` resets to the default in every new async context, useful when the value (e.g. a per-request mutable buffer) should not leak across fiber boundaries.

```scala
import kyo.*

val requestId: Local[String] =
    Local.init("anonymous")

val rendered: String < Any =
    requestId.let("req-42") {
        requestId.use(id => s"handling $id")
    }

assert(rendered.eval == "handling req-42")
assert(requestId.get.eval == "anonymous")
```

When neither fits, `Local.init(default)(forkValue, joinValue)` states the fork boundary directly: `forkValue` returns the value a child starts with, a transformation of the parent's, or `Absent` to keep it from crossing, and `joinValue` decides what the parent keeps when the child's work is joined back (by default, its own value). `initNoninheritable` is this form with a `forkValue` that always returns `Absent`.

> **Caution:** the choice of fork strategy is silent at the type level. Picking the wrong one (inheritable when you wanted per-request isolation, or vice versa) propagates or fails to propagate context without any compiler signal. Decide based on whether child computations should see the parent's override.

### Scoped override and update

`local.let(value)(body)` runs `body` with the local set to `value`; `local.update(f)(body)` runs `body` with the local set to `f(currentValue)`. Both restore the original value when `body` finishes.

```scala
import kyo.*

val depth: Local[Int] = Local.init(0)

val nested: Int < Any =
    depth.update(_ + 1) {
        depth.update(_ + 1) {
            depth.get
        }
    }

assert(nested.eval == 2)
```

## Mutable state

When a computation needs tracked mutable state (an accumulator, a parser position, a local cache), declare a `Var[V]` in the pending row. `Var` keeps the state functional: each read/write is an effect, the state is scoped to a `run` boundary, and the type system sees both that you depend on state of type `V` and where that state is discharged.

`Var.get` reads, `Var.set(v)` writes (returning the previous value), `Var.update(f)` modifies with a function. The `*With` variants chain another computation; the `*Discard` variants return `Unit` when you do not need the read-back value.

```scala
import kyo.*

val counted: (Int, Chunk[Int]) < Any =
    Var.runTuple(0) {
        Kyo.foreach(Chunk(1, 2, 3, 4)) { i =>
            Var.update[Int](_ + i).map(_ => i)
        }
    }

assert(counted.eval == (10, Chunk(1, 2, 3, 4)))
```

### Discharging state: run vs runTuple

`Var.run(initial)(body)` discharges the effect and returns only the body's result. `Var.runTuple(initial)(body)` returns `(finalState, result)` when you want to see the final state at the boundary.

```scala
import kyo.*

val onlyResult: Int < Any =
    Var.run(0) { Var.update[Int](_ + 5) }

val both: (Int, Int) < Any =
    Var.runTuple(0) { Var.update[Int](_ + 5) }

assert(onlyResult.eval == 5)
assert(both.eval == (5, 5))
```

### Isolation strategies

`Var[V]` is per-handler-scope state, not shared mutable state across fibers. When you split a computation into branches that each maintain state (for example through `Async.foreach` in `kyo-core`), each branch sees its own copy and an isolation strategy decides how to reconcile them at the end. `Var.isolate.update`, `Var.isolate.merge`, and `Var.isolate.discard` are the three reconciliation modes. `Var` has no default, so a parallel runner that needs one is given it explicitly with `use`: `Var.isolate.update[Int].use { Async.foreach(items)(step) }`.

```scala
import kyo.*

// Overwrite the outer Var with the last value from the isolated branch.
val updating: Isolate[Var[Int], Any, Var[Int]] = Var.isolate.update[Int]

// Combine outer and isolated final values with f.
val merging: Isolate[Var[Int], Any, Var[Int]] = Var.isolate.merge[Int](_ + _)

// Throw away isolated modifications; the outer Var is unchanged.
val discarding: Isolate[Var[Int], Any, Any] = Var.isolate.discard[Int]
```

kyo-core's parallel runners restore the branches in input order once all of them finish. So when two branches both update the same `Var[Int]`, `update` leaves the final value of the last branch in the input sequence, not the last to finish. `merge(f)` folds each branch's final value into the outer one in that order, so with `merge(_ + _)` an outer 10 and a branch ending at 12 give 22: pass a function that fits absolute values, not deltas. `discard` leaves the outer state untouched. Under a race, only the winner's state is restored.

## Branching computations

When a computation has more than one possible path (parsing alternatives, search exploration, retry candidates), use `Choice`. Each branch is another value; `Choice.run` collects all surviving outcomes into a `Chunk`, `Choice.runStream` produces them incrementally as a `Stream`.

### Introducing branches

`Choice.eval(a, b, c)` introduces three branches; `Choice.evalSeq(seq)` from a sequence; `Choice.evalWith(seq)(f)` evaluates `f` on each branch (cheaper than chaining `flatMap`).

```scala
import kyo.*

val combinations: Chunk[(Int, String)] < Any =
    Choice.run {
        Choice.eval(1, 2).map { n =>
            Choice.eval("a", "b").map { s =>
                (n, s)
            }
        }
    }

assert(combinations.eval == Chunk((1, "a"), (1, "b"), (2, "a"), (2, "b")))
```

### Killing a branch

`Choice.drop` kills the current branch (the surviving collection skips it); `Choice.dropIf(cond)` does so conditionally.

```scala
import kyo.*

val evens: Chunk[Int] < Any =
    Choice.run {
        Choice.eval(1, 2, 3, 4).map { n =>
            Choice.dropIf(n % 2 != 0).andThen(n)
        }
    }

assert(evens.eval == Chunk(2, 4))
```

> **Note:** unlike `Abort.fail`, `Choice.drop` does not surface as a failure value: `Choice.run` omits the dropped path from the result `Chunk`. There is no "the third branch failed" signal in the output.

### Collect all vs stream

`Choice.run` is exhaustive: it walks every surviving branch before returning a `Chunk`. For deeply branching computations the result set is combinatorial and may blow up. `Choice.runStream` produces a `Stream[A, S]` that emits surviving outcomes incrementally so a downstream `take` can consume only as many as it needs. It explores one level of choice points at a time, advancing every pending branch to its next choice point before emitting, so `take(n)` bounds what is emitted rather than how far each level is explored.

```scala
import kyo.*

val firstThree: Chunk[Int] < Any =
    Choice.runStream {
        Choice.eval(1, 2, 3, 4, 5, 6)
    }.take(3).run

assert(firstThree.eval == Chunk(1, 2, 3))
```

`Choice.run` collects outcomes depth-first, in the order the branches were introduced. `Choice.runStream` makes no such promise for nested choice points, so code that needs `run`'s order should collect with `run`.

A branch is the rest of the computation run once per value, so where a resource is acquired relative to the choice point decides how often it is released. The kernel's `Bracket` (see [kyo-kernel](../kyo-kernel/README.md#bracket-acquire-use-release)), and the brackets built on it such as kyo-core's `Sync.ensure` and `Sync.acquireReleaseWith`, pair an acquire with a release over one use. A bracket around `Choice.eval` is shared by every branch and released once, after the last one. A bracket opened inside a branch belongs to that branch and is released when its own use ends, before the next branch acquires its own. A `Scope` opened around the choice point collects every branch's registrations and releases them once, after the last branch, as [kyo-core's Resource safety section](../kyo-core/README.md#resource-safety) describes.

## Streaming

A `Stream[V, S]` is a chunked, lazy sequence of `V`-typed values requiring effects `S`. Under the hood it is `Unit < (Emit[Chunk[V]] & S)`, which means every stream operation works on chunks rather than individual elements: `mapChunk` calls its function once per chunk, while `map` sequences its function once per element inside each chunk.

You produce streams (`Stream.init`, `Stream.range`, `Stream.unfold`), transform them (`map`, `filter`, `take`, `rechunk`), and consume them by running them to a `Chunk` or folding them into a value. For reusable transformations you reach for `Pipe[A, B, S]`; for reusable consumers you reach for `Sink[V, A, S]`.

### Building a stream

```scala
import kyo.*

val firstFive: Stream[Int, Any] = Stream.range(0, 5)
assert(firstFive.run.eval == Chunk(0, 1, 2, 3, 4))

val fromSeq: Stream[String, Any] = Stream.init(Seq("a", "b", "c"))
assert(fromSeq.run.eval == Chunk("a", "b", "c"))

val unfolded: Stream[Int, Any] =
    Stream.unfold(1)(n => if n > 16 then Absent else Present((n, n * 2)))
assert(unfolded.run.eval == Chunk(1, 2, 4, 8, 16))
```

`Stream.repeatPresent(v)` calls `v` until it returns `Absent`, useful for paginating an effectful source. `Stream.empty` produces no elements. `Stream.unwrap(stream)` flattens a `Stream[V, S] < S2` into a single `Stream[V, S & S2]`.

### Transforming

```scala
import Order.Status.*

val orders: Stream[Order, Any] =
    Stream.init(Seq(
        Order(1, 1, BigDecimal(10), Confirmed),
        Order(2, 2, BigDecimal(25), Pending),
        Order(3, 1, BigDecimal(5), Shipped),
        Order(4, 3, BigDecimal(99), Confirmed)
    ))

val smallForUser1: Chunk[Order] < Any =
    orders
        .filterPure(_.userId == 1)
        .takeWhile(_.total < BigDecimal(50))
        .run

assert(smallForUser1.eval == Chunk(Order(1, 1, BigDecimal(10), Confirmed), Order(3, 1, BigDecimal(5), Shipped)))
```

`map`, `filter`, and `takeWhile` accept an effectful function and sequence it element by element; `mapPure`, `mapChunkPure`, `filterPure`, and `takeWhilePure` take a plain function and transform each chunk directly, with no per-element effect sequencing. Use the pure variants whenever the function does not require an effect. `flatMap` is a different operation: it maps each element to a whole `Stream` and concatenates them.

> **Note:** `rechunk(n)` clamps `n` to at least 1; passing `0` or a negative number silently changes the chunk granularity to one element per chunk. There is no "leave the chunking alone" overload.

### Composing streams: zip, concat, into

`stream1.concat(stream2)` emits all of the first, then all of the second. `stream.zip(other)` emits pairs in lockstep and truncates to the shorter stream. `stream.into(pipe)` produces a new stream by running the pipe over this stream; `stream.into(sink)` produces the sink's value.

```scala
import kyo.*

val zipped: Chunk[(Int, String)] < Any =
    Stream.range(1, 5).zip(Stream.init(Seq("a", "b", "c"))).run

assert(zipped.eval == Chunk((1, "a"), (2, "b"), (3, "c")))
```

> **Caution:** if you need both streams of a `zip` to drain to their end, with padding for the shorter one, you have to handle that explicitly.

### Consuming: run, fold, foreach

`stream.run` collects all elements into a `Chunk`. `stream.fold(acc)(f)` is the effectful fold; `stream.foldPure(acc)(f)` is the pure-function variant. `stream.foreach(f)` walks the stream for side effects; `stream.discard` drains it without producing a value.

```scala
import kyo.*

val sum: Int < Any =
    Stream.range(1, 6).foldPure(0)(_ + _)

assert(sum.eval == 15)
```

### Threading effects through a stream: handle

`stream.handle(f1, f2, ..., f10)` chains 1 to 10 effect-handler functions through the stream's underlying `emit` effect. Each handler discharges one effect while preserving the stream shape; the chain returns a new `Stream` you can keep transforming.

```scala
import kyo.*

// Each element updates a running total held in Var[Int].
val runningTotals: Stream[Int, Var[Int]] =
    Stream.range(0, 4).map(i => Var.update[Int](_ + i))

// Var.run discharges the Var inside the stream, leaving a plain stream.
val handled: Stream[Int, Any] =
    runningTotals.handle(Var.run(0))

assert(handled.run.eval == Chunk(0, 1, 3, 6))
```

> **Note:** the last handler in a `Stream#handle` chain must return `Any < (Emit[Chunk[V1]] & S1)` (it has to preserve a stream-shaped output). This is unlike `handle` on a plain computation (`v.handle(...)`), which can end in any value. If you migrate a non-stream handle chain to a stream, you cannot end on a non-emit-shaped handler.

### Reusable transducers: Pipe

A `Pipe[A, B, S]` is a `Stream`-to-`Stream` transducer. Under the hood it is `Unit < (Poll[Chunk[A]] & Emit[Chunk[B]] & S)`: it polls chunks of `A` on demand and emits chunks of `B`. You apply it with `pipe.transform(stream)` or symmetrically with `stream.into(pipe)`.

```scala
import kyo.*

val doublePositives: Pipe[Int, Int, Any] =
    Pipe.filterPure[Int](_ > 0)
        .join(Pipe.mapPure[Int][Int](_ * 2))

val result: Chunk[Int] < Any =
    Stream.init(Seq(-2, 1, -1, 3)).into(doublePositives).run

assert(result.eval == Chunk(2, 6))
```

Pipes have a `contramap` / `contramapPure` / `contramapChunk` family that changes the input element type, and a `map` / `mapChunk` family that changes the output element type. `pipe.join(otherPipe)` chains them; `pipe.join(sink)` produces a new sink that transforms the stream first.

### Reusable consumers: Sink

A `Sink[V, A, S]` consumes a `Stream[V, S2]` and produces an `A < (S & S2)`. Stock sinks (`Sink.collect`, `Sink.count`, `Sink.fold`, `Sink.foreach`, `Sink.foldKyo`) cover the common cases.

```scala
import Order.Status.*

val totalAndCount: ((BigDecimal, Int)) < Any =
    Stream.init(Seq(
        Order(1, 1, BigDecimal(10), Pending),
        Order(2, 1, BigDecimal(20), Shipped),
        Order(3, 2, BigDecimal(5), Confirmed)
    ))
        .into(Sink.fold[BigDecimal, Order](BigDecimal(0))(_ + _.total).zip(Sink.count[Order]))

assert(totalAndCount.eval == (BigDecimal(35), 3))
```

You change a sink's input element type with `contramap`, its output type with `map`, and run it on a stream with `sink.drain(stream)` or `stream.into(sink)`.

### Low-level: Emit and Poll

`Stream` is built on `Emit` (push) and `Poll` (pull). Reach for them directly when you want explicit control, when you are building a primitive `Stream` shape that `Stream.init` does not cover, or when you need to wire a non-stream producer to a non-stream consumer.

```scala
import kyo.*

val emitted: (Chunk[Int], Unit) < Any =
    Emit.run {
        Emit.value(1).andThen(Emit.value(2)).andThen(Emit.value(3))
    }

assert(emitted.eval == (Chunk(1, 2, 3), ()))
```

`Emit.value(v)` emits one value; `Emit.valueWhen(cond)(v)` emits only if `cond` is true; `Emit.valueWith(v)(next)` emits and then runs `next`. `Emit.run` collects all emissions, `Emit.runFold(acc)(f)` folds them, `Emit.runForeach(f)` consumes them with a function, `Emit.runDiscard` drops them. `Emit.runWhile(predicate)` hands each emitted value to `predicate` and stops the producer at the first `false`, so it can end an unbounded emitter; it returns `Maybe[A]`, `Absent` when the predicate stopped it.

`Poll.one[V]` pulls one value, returning `Maybe[V]` (`Absent` means end-of-stream). `Poll.values(f)` walks the stream until exhaustion, applying `f`. `Poll.fold(acc)(f)` folds. `Poll.run(chunk)(body)` discharges `Poll` from the given `Chunk` of inputs; subsequent polls after the chunk is exhausted receive `Absent`. `Poll.andMap(f)` polls one value and applies `f` to the `Maybe[V]` result in a single step.

```scala
import kyo.*

val firstTwo: Chunk[Int] < Any =
    Poll.run(Chunk(10, 20, 30)) {
        Poll.one[Int].map { a =>
            Poll.one[Int].map { b =>
                Chunk(a.getOrElse(0), b.getOrElse(0))
            }
        }
    }

assert(firstTwo.eval == Chunk(10, 20))
```

> **Caution:** after `Poll.one` returns `Absent`, the consumer is still in the `Poll` effect; subsequent polls keep returning `Absent`. Your loop must terminate on `Absent` explicitly (helpers like `Poll.values` and `Poll.fold` do this for you).

`Poll.runEmit(emit)(poll)` connects an `Emit` producer to a `Poll` consumer with demand-driven flow control: the consumer's polls drive the producer's emissions.

> **Note:** pacing belongs to the handler. Each `Emit.value` suspends until its handler resumes it: `Emit.run` resumes the producer immediately after each emission and buffers everything, while `Poll.runEmit` resumes it only when the consumer polls, which gives demand-driven flow.

### Isolation strategies for streaming effects

When a computation carrying these effects is split across parallel branches (kyo-core's `Async` combinators), an `Isolate` decides how each effect reconciles at the branch boundaries. The strategies are companions to the effect they apply to:

- `Emit.isolate.merge[V]` collects every emitted value during isolation and re-emits them in order when isolation ends. `Emit.isolate.discard[V]` drops them.
- `Var.isolate.update`, `Var.isolate.merge`, and `Var.isolate.discard`, covered under [mutable state](#isolation-strategies).
- `Memo.isolate` merges memo caches across branches, keeping later writes on conflict.
- `Check.isolate` accumulates failures and re-emits them when isolation ends, so parallel branches all contribute checks.

`Memo` and `Check` provide theirs as givens, so they apply without being named. `Emit` and `Var` have no default: pass the one you want with `use`, as in `Emit.isolate.merge[Int].use { Async.foreach(items)(step) }`. `Poll` has no isolate, so a `Poll` computation must be handled before it reaches a parallel runner, or the call does not compile.

## Memoization

When a computation is expensive and called many times with overlapping inputs (a derivation, a layer's construction, a parsed configuration), wrap it with `Memo` to cache results inside the current handler scope. `Memo.apply(f)` returns a memoized version of `f` that requires the `Memo` effect; `Memo.run` discharges the cache.

```scala
import kyo.*

val program: Int < Memo =
    val expensiveSquare: Int => Int < Memo = Memo((n: Int) => n * n)
    expensiveSquare(5).map { a =>
        expensiveSquare(5).map { b =>
            a + b // second call hits the cache
        }
    }
end program

assert(Memo.run(program).eval == 50)
```

> **Note:** `Memo` is for global value initialization or infrequent expensive computations, not hot paths. The implementation is a `Var[Cache]` (a functional map), so look-ups cost a map operation per call. For performance-sensitive memoization, reach for `Async.memoize` and `Cache` in kyo-core.

`Memo.isolate` (a given) is the cache's isolation strategy: when isolated computations end, their entries merge into the outer cache (later writes win on key conflicts).

## Validation

When a function checks many conditions and you want to collect all failures instead of stopping at the first, use `Check`. Each `Check.require(cond, message)` records a `CheckFailed` if `cond` is false but does not abort the computation. At the boundary, choose how to surface them: collect them as a `Chunk[CheckFailed]`, convert the first into an `Abort[CheckFailed]`, or discard them.

```scala
def validate(u: User): User < Check =
    Check.require(u.id > 0, "id must be positive").andThen(
        Check.require(u.email.contains("@"), "email must contain @").andThen(
            Check.require(u.name.nonEmpty, "name must not be empty").andThen(u)
        )
    )

val collected: (Chunk[CheckFailed], User) < Any =
    Check.runChunk(validate(User(0, "bademail", "")))

val (failures, user) = collected.eval
assert(failures.map(_.message) == Chunk("id must be positive", "email must contain @", "name must not be empty"))
```

`Check.runChunk` collects all `CheckFailed` instances along with the (possibly nonsensical) result. `Check.runAbort` converts the first failure into an `Abort[CheckFailed]` and short-circuits. `Check.runDiscard` drops failures for non-critical validations.

```scala
val asAbort: Result[CheckFailed, User] < Any =
    Abort.run(Check.runAbort(
        Check.require(false, "not allowed").andThen(User(1, "u@x", "u"))
    ))

asAbort.eval match
    case Result.Failure(f) => assert(f.message == "not allowed")
    case other             => sys.error(s"unexpected $other")
```

> **Note:** `Check.runAbort` combined with parallel composition does not short-circuit on the first failed check. The `Check.isolate` strategy accumulates failures from each parallel branch and re-emits them once the branches finish; only then does `runAbort` see them, and it aborts with the first one re-emitted. To report every failure from parallel checks, handle with `Check.runChunk` instead.

`CheckFailed(message, frame)` carries both the message and the call-site frame, so error reports include the precise location where the assertion ran.

## Batching

When a function is called many times with different inputs and each call would issue an individual request (the classic N+1 problem: looking up users one at a time), use `Batch`. You declare a batched source once, and call sites use it as if each call were independent; the engine groups them into one bulk call and reassembles results per caller.

`Batch.source(f)` takes a function `Seq[A] => (A => B < S) < S` that, given a batch of inputs, returns a per-element resolver. `Batch.sourceMap(f)` is shorter when your bulk function returns a `Map[A, B]`. `Batch.sourceSeq(f)` is the variant for `Seq[B]` aligned positionally with the inputs.

The example records every batch the source receives, to show that four lookups become one call:

```scala
def user(id: Int): User = User(id, s"u$id@example.com", s"User $id")

val fetchUser: Int => User < (Batch & Var[Chunk[Seq[Int]]]) =
    Batch.sourceMap[Int, User, Var[Chunk[Seq[Int]]]] { ids =>
        // In a real program, one DB call for the whole batch.
        Var.update[Chunk[Seq[Int]]](_.append(ids)).map(_ => ids.map(id => id -> user(id)).toMap)
    }

val (batches, users) =
    Var.runTuple(Chunk.empty[Seq[Int]])(Batch.run(Batch.foreach(Seq(1, 2, 3, 1))(fetchUser))).eval

assert(users == Chunk(user(1), user(2), user(3), user(1)))
assert(batches == Chunk(Seq(1, 2, 3)))
```

`Batch.eval(seq)` branches once per element, like `Choice`: everything downstream runs per element, and `Batch.run` gathers the per-element results into a `Chunk`, grouping the source calls the branches make into one call per source. `Batch.foreach(seq)(f)` is `Batch.eval(seq).map(f)`.

> **Caution:** a source receives the distinct inputs of a batch, and `sourceMap` and `sourceSeq` must return exactly one result per distinct input; a partial lookup panics with a `require` violation at the source's frame. If your backing service can legitimately fail to find some inputs, return a `Map[A, Maybe[B]]` or `Map[A, Result[E, B]]` and surface the missing case to the caller.

## Aspect-oriented interception

When you want to wrap or replace behavior at many call sites without rewiring callers (logging every `fetchUser`, retrying on a class of errors, swapping in a mock for tests), declare an `Aspect`. An aspect is a reified extension point: you call it like a function, and the call delegates to whatever `Cut` is installed in the current scope or falls back to the default pass-through.

`Aspect.init[I, O, S]` allocates a new aspect; the resulting `Aspect[Input, Output, S]` has a stable identity given by its type arguments (its `Tag`) plus its allocation site (its `Frame`). `aspect.let(cut)(body)` installs a cut for the dynamic extent of `body`; a nested `let` composes with the cut already installed rather than replacing it, the outer cut running first. `aspect.sandbox(body)` temporarily disables the aspect inside `body`.

```scala
import kyo.*

val logged: Aspect[Const[Int], Const[String], Any] =
    Aspect.init[Const[Int], Const[String], Any]

def labelOf(n: Int): String < Any =
    logged(n)(i => s"value=$i")

val plain: String < Any = labelOf(7)
val withCut: String < Any =
    logged.let(Aspect.Cut[Const[Int], Const[String], Any](
        [C] => (input, cont) => cont(input).map(s => s"[LOG] $s")
    )) {
        labelOf(7)
    }

assert(plain.eval == "value=7")
assert(withCut.eval == "[LOG] value=7")
```

> **Note:** an aspect's identity is its `(Tag, Frame)` pair. A generic helper like `def myAspect[A: Tag] = Aspect.init[Const[A], Const[A], Any]` creates a different aspect for each type argument. Do not try to share `let` state across `myAspect[Int]` and `myAspect[String]`; they are unrelated extension points.

> **Caution:** aspects support multi-shot continuations. A cut may call the continuation zero, one, or many times (for retry, fallback, branching). This is intentional, but it breaks linear-effect intuitions: code inside an aspect-wrapped computation must be idempotent if you ever expect to install a cut that retries.

`Aspect.Cut[I, O, S]` is a `[C] => (I[C], I[C] => O[C] < S) => O[C] < S`; `Cut.andThen(a, b)` composes two cuts; `aspect.asCut` projects an aspect itself into a `Cut` value when you want to plug it into a chain.

## Sequencing collections over any effect: `Kyo.*`

The `Kyo` object provides sequential collection operations that work over any effect row. There is no dependency on `Sync` or `Async`: the operations sequence effects whatever they happen to be, `Abort[E]`, `Var[V]`, `Env[R]`, or any combination. It is defined in kyo-kernel and available with a plain `import kyo.*`.

Key methods:

- `Kyo.collectAll(xs: CC[A < S]): CC[A] < S` sequences a collection of pending computations, returning the same collection type with results resolved.
- `Kyo.foreach(xs)(f)` maps `f` over `xs` sequentially, preserving collection type.
- `Kyo.fill(n)(v)` produces a `Chunk[A]` by evaluating `v` `n` times sequentially.
- `Kyo.zip(a, b, c, ...)` sequences 2 to 10 computations and returns a tuple, short-circuiting on the first effect that aborts.
- `Kyo.when(cond)(ifTrue, ifFalse)` evaluates either branch depending on `cond`; the single-branch overload returns `Maybe[A]`.
- `Kyo.unless(cond)(ifFalse)` is the `when`-negated form, returning `Maybe[A]`.
- `Kyo.lift(v)` is the explicit zero-cost lift: it coerces a plain value into any `A < S` without allocation (the pending row `S` is phantom).
- `Kyo.unit` is a stable `Unit < Any` value useful for discarding results.

Use `Kyo.*` for sequential execution. For parallel execution over `Async`, see `Async.foreach` and its siblings in [kyo-core's Structured concurrency section](../kyo-core/README.md#structured-concurrency).

```scala
// Sequence Abort[ValidationError] computations over a list without any Sync/Async.
def validateId(id: Int): Int < Abort[ValidationError] =
    Abort.when(id <= 0)(ValidationError("id", "must be positive")).andThen(id)

val ids: Seq[Int < Abort[ValidationError]] = Seq(1, 2, 3).map(validateId)

val allIds: Seq[Int] < Abort[ValidationError] = Kyo.collectAll(ids)

assert(Abort.run(allIds).eval == Result.succeed(Seq(1, 2, 3)))
```

## Putting it together

The clusters above each introduce one capability. Real programs declare several at once: a request handler typically reads a `Config` from `Env`, looks up a user through a `Batch.sourceMap`, validates fields with `Check.require`, and short-circuits domain errors through `Abort`. The compiler sees the full row in the type signature; the handlers at the boundary discharge each effect independently.

```scala
import Order.Status.*

val configLayer: Layer[Config, Any] =
    Layer(Config(maxOrders = 3, currency = "USD"))

val repoLayer: Layer[UserRepo, Any] =
    Layer(new UserRepo:
        // Knows users 1 to 99; any other id is missing from the result.
        def fetchUsers(ids: Seq[Int]) =
            ids.filter(_ < 100).map(id => id -> User(id, s"u$id@example.com", s"User $id")).toMap
        def fetchOrders(userId: Int) =
            Chunk(
                Order(userId * 10 + 1, userId, BigDecimal(10), Shipped),
                Order(userId * 10 + 2, userId, BigDecimal(25), Confirmed),
                Order(userId * 10 + 3, userId, BigDecimal(99), Pending)
            ))

def handle(userId: Int): Chunk[Order] < (Env[UserRepo & Config] & Abort[ValidationError] & Check) =
    Env.use[Config] { config =>
        Env.use[UserRepo] { repo =>
            Check.require(userId > 0, "userId must be positive").andThen {
                // One result per input, as sourceMap requires: Absent for a user the repository lacks.
                val fetchUser: Int => Maybe[User] < Batch =
                    Batch.sourceMap[Int, Maybe[User], Any] { ids =>
                        repo.fetchUsers(ids).map(found => ids.map(id => id -> Maybe.fromOption(found.get(id))).toMap)
                    }
                Batch.run(fetchUser(userId)).map { found =>
                    Abort.when(found.forall(_.isEmpty))(ValidationError("user", "not found")).andThen {
                        repo.fetchOrders(userId).map { orders =>
                            Stream.init(orders)
                                .takeWhile(_.total < BigDecimal(50))
                                .take(config.maxOrders)
                                .run
                        }
                    }
                }
            }
        }
    }

def program(userId: Int): (Chunk[CheckFailed], Result[ValidationError, Chunk[Order]]) < Any =
    Memo.run(Env.runLayer(configLayer, repoLayer) {
        Check.runChunk(Abort.run(handle(userId)))
    })

val (checks, result) = program(7).eval
assert(checks.isEmpty)
assert(result == Result.succeed(Chunk(Order(71, 7, BigDecimal(10), Shipped), Order(72, 7, BigDecimal(25), Confirmed))))

val (_, missing) = program(404).eval
assert(missing == Result.fail(ValidationError("user", "not found")))
```

`handle` looks up one user per call, so each `Batch.run` holds a single request and nothing is grouped; batching pays off when one `Batch.run` covers many lookups, as in [Batching](#batching), and a source is defined once rather than per call, since calls group by source.

The signature of `handle` records every capability the function needs: `Env[UserRepo & Config]` for dependencies, `Abort[ValidationError]` for typed failure, `Check` for accumulated validations. The handlers at the call site discharge them from the inside out: `Abort.run` reifies the success-or-failure result, `Check.runChunk` collects validation outcomes around it, `Env.runLayer` provides the services, and `Memo.run` discharges the `Memo` the layers introduce.
