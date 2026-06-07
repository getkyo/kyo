# kyo-kernel

A `<` (pronounced "pending") is Kyo's effect type. `A < S` means a computation that will eventually produce an `A` while potentially performing effects from the set `S`. Effects accumulate in `S` as an intersection: `Int < (Abort[String] & Emit[Log])` may both fail with a `String` and emit `Log` values, and intersections are unordered, so the type tracks *which* effects can occur, not the order they occur in. Plain values lift into `A < Any` automatically, so the same `map` serves as both `map` and `flatMap` and most code never has to distinguish lifting from chaining.

The kernel itself ships only the substrate: the pending type, a `Kyo` companion of sequential collection combinators, the `Loop` construct for stack-safe iteration, and the two building blocks every concrete effect is defined on top of. `ArrowEffect[Input, Output]` describes a suspended function call (input shape, output shape, handler decides the rest) and supports multi-shot continuations. `ContextEffect[A]` describes a value-shaped requirement (a handler installs a value of `A` within a scope). Concrete effects like `Abort`, `Env`, `Var`, `Emit` live in `kyo-prelude` and other modules; this module is what they extend.

A concrete effect is two things: a suspended call site and a handler that decides what the call means. Here is the smallest complete example, grading a number against a threshold:

```scala
import kyo.*
import kyo.kernel.*

sealed trait Grade extends ArrowEffect[Const[Int], Const[String]]

def grade(score: Int): String < Grade =
    ArrowEffect.suspend[Any](Tag[Grade], score)

def runGrade[A, S](v: A < (Grade & S)): A < S =
    ArrowEffect.handle(Tag[Grade], v):
        [C] => (score, cont) => cont(if score >= 60 then "pass" else "fail")

assert(runGrade(grade(80)).eval == "pass")
assert(runGrade(grade(40)).eval == "fail")
```

Everything below is the kernel API that makes this pattern work.

## The pending type

The type parameter `A` is the value the computation will produce; `S` is an intersection of effects it may perform on the way. The same operations work whether `S` is empty (`Any`) or rich with many effects.

### Plain value or suspended computation

When you write `Greeting < (Greet & Locale)`, the runtime value is either a plain `Greeting` (already done) or a suspended `Kyo` description of the work that remains. The branching is invisible at the API level: every operation on a pending value handles both cases.

```scala
import kyo.*

val plain: Int < Any = 42 // pure value, lifts automatically

assert(plain.eval == 42)
```

> **Note:** `<` is an opaque type aliasing `A | Kyo[A, S]`. A pending value is either the plain `A` or a suspended `Kyo`, and the runtime branches on that at every step. Reader code never observes the union directly.

### Chaining: `map`, `flatMap`, `andThen`, `unit`

`map` is the monadic bind. It threads a function over the eventual value and accumulates effects. `flatMap` exists only to support for-comprehensions; it has identical behavior. Use `map` everywhere else.

```scala
import kyo.*

case class Visitor(name: String, language: String)

def initials(v: Visitor): String < Any =
    (v.name: String < Any).map(name => name.take(1).toUpperCase)

assert(initials(Visitor("Ada", "en")).eval == "A")
```

`andThen` executes the receiver for its effects, discards its value, and runs another computation. `unit` does the same but produces `()`.

```scala
import kyo.*

val first: Unit < Any    = println("greeting")
val logged: String < Any = first.andThen("done")

assert(logged.eval == "done")
```

> **Note:** `map` and `flatMap` are identical in behavior. The README and the source consistently use `map`. `flatMap` is exposed only so `for` notation desugars correctly.

### Collapsing nested pending: `flatten`

When inference produces `A < S1 < S2` (a pending of a pending), `flatten` collapses it into a single `A < (S1 & S2)`. This is most common when a handler returns a pending computation.

```scala
import kyo.*

val nested: (Int < Any) < Any = Kyo.lift[Int < Any, Any](Kyo.lift(42))
val flat: Int < Any           = nested.flatten

assert(flat.eval == 42)
```

> **Note:** The implicit `lift` wraps an *already-effectful* value in a `Nested` marker to prevent unsound flattening. Lifting is not pure assignment when the value is itself a `Kyo`. Use `.flatten` explicitly.

### Running a handled computation: `eval`

After every effect in the row has been handled, calling `eval` runs the computation and returns its result. It is callable *only* on `A < Any` (everything handled).

```scala
import kyo.*

val ready: Int < Any = 1 + 1
assert(ready.eval == 2)
```

> **Caution:** `eval` is defined on `A < Any`. Calling it on a value with unhandled effects in the row fails at runtime via `bug.failTag`, not at compile time. The compile-time signal is the type: if the row is not `Any`, there are effects to handle first.

### Pipeline-style application: `handle`

When you want to apply several handlers and transformations in order without nesting them, `handle` takes them as a left-to-right pipeline. It is purely sugar over function application, so `Env.run(1)(Abort.run(c))` and `c.handle(Abort.run, Env.run(1))` are identical. Up to 10 stages are supported.

```scala
import kyo.*
import kyo.kernel.*

sealed trait Greet  extends ArrowEffect[Const[String], Const[String]]
sealed trait Locale extends ContextEffect[String]

def runGreet[A, S](v: A < (Greet & S)): A < S =
    ArrowEffect.handle(Tag[Greet], v):
        [C] => (name, cont) => cont(s"Hello, $name!")

val greeting: String < Greet = ArrowEffect.suspend[Any](Tag[Greet], "Ada")

val out: String =
    greeting
        .handle(runGreet, _.map(_.toUpperCase))
        .eval
// out == "HELLO, ADA!"
```

### Lifting plain values: `lift` and `CanLift`

Plain values lift into `A < S` implicitly. The `CanLift[A]` constraint blocks two specific mistakes: lifting an already-effectful value (`A < S < S2`, which would silently flatten incorrectly), and lifting kyo module singletons (`Abort.type`, `Env.type`) which is almost always a forgotten apply.

```scala
import kyo.*

case class Visitor(name: String, language: String)

// Pure value lifts implicitly into the effect row.
val v: Visitor < Any = Visitor("Ada", "en")

// Explicit lift, primarily useful where if/else inference needs help:
def maybeVisitor(name: Option[String]): Visitor < Any =
    name match
        case Some(n) => Visitor(n, "en")
        case None    => Visitor("anonymous", "en")
```

If you write code that produces `Visitor < S1` and assign it to a `Visitor < S2` (forgetting to handle the difference), the macro rejects the lift with a message that walks you through `.flatten` and splitting the statement into two `val`s.

> **Note:** `CanLift` is a *soft* constraint: it forbids the common mistakes (`A < S < S2`, module singletons) but cannot strictly enforce non-nesting in every generic context. If you genuinely need to bypass it for an unusual case, `CanLift.unsafe.bypass` is public but flagged "Warning" in the scaladoc.

### Lifting pure functions into the effect row

A pure `A => B` lifts implicitly into `A => B < Any` so it can be passed where the effect row expects an effectful function. Arities 1 through 6 are provided.

```scala
import kyo.*

case class Visitor(name: String, language: String)

val toUpper: String => String < Any = (s: String) => s.toUpperCase

assert(toUpper("ada").eval == "ADA")

// A pure two-arg function lifts the same way:
val mkVisitor: (String, String) => Visitor < Any =
    (n: String, l: String) => Visitor(n, l)

assert(mkVisitor("Ada", "en").eval == Visitor("Ada", "en"))
```

### Input/output shapes for effect definitions: `Id` and `Const`

When you define an `ArrowEffect`, you choose what shape the input and output take per type parameter `A`. `Id[A] = A` is the identity type constructor; `Const[A] = [B] =>> A` is the constant type constructor (ignores its argument). Both are used as the `Input` / `Output` parameters of `ArrowEffect` to express the shape of the suspended call. An effect that always takes a `Visitor` and always returns a `Greeting` uses `Const[Visitor]` and `Const[Greeting]`. An effect that takes a value of arbitrary type and returns the same type uses `Id` for both.

```scala
import kyo.*
import kyo.kernel.*

case class Visitor(name: String, language: String)
enum Greeting:
    case Hello, GoodMorning, GoodEvening

// Both Input and Output are constants: same value-types regardless of A.
sealed trait Greet extends ArrowEffect[Const[Visitor], Const[Greeting]]

// Input is Id, Output is Id: this is the shape of a "choose among As" effect.
sealed trait Choose extends ArrowEffect[Seq, Id]
```

### Pretty-printing pending values

Pending values pretty-print via the underlying `Render[A]` of their result type. A pending `Visitor < Any` prints just like its `Visitor` would.

## Defining effects

Concrete effects (Abort, Env, Var, Emit, Choice, ...) are defined as either an `ArrowEffect` or a `ContextEffect`, then expose user-facing functions that call `suspend` to request work from a handler. The kernel itself ships no concrete effects: this section teaches the pattern every kyo module uses.

### Catching throwables: `Effect.catching`

Every `ArrowEffect` and `ContextEffect` extends a shared base, `Effect`. The only operation it provides directly is `catching`, which intercepts non-fatal throwables raised during evaluation of any effect chain.

```scala
import kyo.*
import kyo.kernel.*

val safe: Int < Any =
    Effect.catching((throw new RuntimeException("boom")): Int < Any): t =>
        -1

assert(safe.eval == -1)
```

### Function-shaped effects: `ArrowEffect`

When the effect you are modeling is a function call (a user invokes it with an input, a handler returns an output), use `ArrowEffect[Input[_], Output[_]]`. The handler decides what the function does. `Input[A]` is what the call carries (often `Const[X]` when the value is always the same shape) and `Output[A]` is what the handler returns (often `Const[X]` again, or `Id` to thread through unchanged).

```scala
import kyo.*
import kyo.kernel.*

case class Visitor(name: String, language: String)
enum Greeting:
    case Hello, GoodMorning, GoodEvening

sealed trait Greet extends ArrowEffect[Const[Visitor], Const[Greeting]]

// The user-facing API: a function that suspends the call.
def greet(v: Visitor): Greeting < Greet =
    ArrowEffect.suspend[Any](Tag[Greet], v)
```

> **Note:** `ArrowEffect` supports *multi-shot continuations*. A handler may invoke the continuation multiple times (powering Choice / non-determinism) or never (powering early returns). Most callers expect single-shot resumption; the multi-shot capability is what makes `Choice` and friends work without runtime tricks.

### Value-shaped effects: `ContextEffect`

When the effect you are modeling is a value that handlers supply ambient to a scope (locale, request id, configuration), use `ContextEffect[A]`. A handler installs a value of `A` within a scope; computations inside that scope can request the value. This is dependency-injection-style.

```scala
import kyo.*
import kyo.kernel.*

sealed trait Locale extends ContextEffect[String]

def currentLanguage: String < Locale =
    ContextEffect.suspend(Tag[Locale])
```

#### Opting out of async-boundary inheritance

By default, a context effect's value is inherited across async boundaries when a computation is suspended and resumed (on another fiber, after parking, etc.). Mix in `ContextEffect.Noninheritable` to opt out: child fibers start with fresh values, similar to non-inheritable thread locals.

```scala
import kyo.*
import kyo.kernel.*

sealed trait RequestId extends ContextEffect[String] with ContextEffect.Noninheritable
```

### Suspending: how an effect's API requests work

A user-facing effect function calls `ArrowEffect.suspend` (or `ContextEffect.suspend`) to build the suspended value. The kernel provides `suspendWith` variants that combine suspending with an immediate transformation of the result.

```scala
import kyo.*
import kyo.kernel.*

case class Visitor(name: String, language: String)
enum Greeting:
    case Hello, GoodMorning, GoodEvening

sealed trait Greet  extends ArrowEffect[Const[Visitor], Const[Greeting]]
sealed trait Locale extends ContextEffect[String]

// Plain suspend: request, return the raw output.
def greet(v: Visitor): Greeting < Greet =
    ArrowEffect.suspend[Any](Tag[Greet], v)

// suspendWith: request, then transform in one step.
def greetLoud(v: Visitor): String < Greet =
    ArrowEffect.suspendWith[Any](Tag[Greet], v): g =>
        g.toString.toUpperCase

// ContextEffect.suspend: request a value of the context type.
def currentLanguage: String < Locale =
    ContextEffect.suspend(Tag[Locale])

// ContextEffect.suspend with default: removes the requirement from the type.
def currentLanguageOr(default: String): String < Any =
    ContextEffect.suspend(Tag[Locale], default)
```

> **Caution:** `ContextEffect.suspend(tag, default)` removes the effect from the type entirely. The compiler will not warn that the effect is "unhandled" because there is no requirement, only a fallback. Use this for genuinely optional context, not as a workaround when an effect is missing from the type.

`ContextEffect.suspendWith(tag)(f)` and `ContextEffect.suspendWith(tag, default)(f)` are the transform-in-one-step counterparts of the two `suspend` forms.

## Handling effects

A handler interprets the suspended computations an effect produces. Every concrete effect's `run` method is implemented in terms of one of the handler families below.

### Basic interpretation: `ArrowEffect.handle`

When every occurrence of an effect should be interpreted the same way and the handler does not itself perform effects, `handle` is the basic form. Each occurrence is processed independently. The handler cannot introduce new effects during handling (no `S2` parameter); use `handleLoop` when it must.

```scala
import kyo.*
import kyo.kernel.*

case class Visitor(name: String, language: String)
enum Greeting:
    case Hello, GoodMorning, GoodEvening

sealed trait Greet extends ArrowEffect[Const[Visitor], Const[Greeting]]

def greet(v: Visitor): Greeting < Greet =
    ArrowEffect.suspend[Any](Tag[Greet], v)

def runGreet[A, S](v: A < (Greet & S)): A < S =
    ArrowEffect.handle(Tag[Greet], v):
        [C] =>
            (visitor, cont) =>
                val out = visitor.language match
                    case "en" => Greeting.Hello
                    case "de" => Greeting.GoodMorning
                    case _    => Greeting.GoodEvening
                cont(out)

assert(runGreet(greet(Visitor("Ada", "en"))).eval == Greeting.Hello)
assert(runGreet(greet(Visitor("Lina", "de"))).eval == Greeting.GoodMorning)
```

Arities of `ArrowEffect.handle` exist for 1 through 4 simultaneous tags. The body in each is the same: one polymorphic function per effect, each consuming an input value and a continuation.

```scala
import kyo.*
import kyo.kernel.*

sealed trait E1 extends ArrowEffect[Const[Int], Const[String]]
sealed trait E2 extends ArrowEffect[Const[String], Const[Int]]

val combined =
    for
        s <- ArrowEffect.suspend[Any](Tag[E1], 42)
        i <- ArrowEffect.suspend[Any](Tag[E2], s)
    yield i

val ran = ArrowEffect.handle(Tag[E1], Tag[E2], combined)(
    [C] => (in, cont) => cont(in.toString),
    [C] => (in, cont) => cont(in.toInt)
)

assert(ran.eval == 42)
```

### Handling only the first occurrence: `handleFirst`

When you want to peek at only the first occurrence and re-suspend the rest for an outer handler to process, reach for `handleFirst`. It handles only the *first* occurrence of an effect, transforms the overall result, and leaves any later occurrences for an outer handler. It is easy to confuse with `handle`, which processes every occurrence.

When you need every occurrence handled together, use `handle`. When you want to consume only the first call (perhaps to peek at it, then re-suspend the rest for a wrapping handler to process), use `handleFirst`. The two have different signatures: `handleFirst` requires both a `handle` function and a `done` function (the latter runs when no occurrence was found).

### Handlers that perform effects: `handleLoop`

`handle` cannot introduce new effects. `handleLoop` can: the body returns a `Loop.Outcome`, threading control through the same `Continue` / `done` encoding used by `Loop`. Reach for `handleLoop` only when the handler itself needs to perform effects.

The stateful variants (`handleLoop(tag, state, v)`) also thread a state value across occurrences. The two-result variant takes both a per-occurrence `handle` and a `done` function for the final state-plus-result transformation.

```scala
import kyo.*
import kyo.kernel.*

sealed trait E extends ArrowEffect[Const[Int], Const[String]]

val twice =
    for
        s1 <- ArrowEffect.suspend[Any](Tag[E], 42)
        s2 <- ArrowEffect.suspend[Any](Tag[E], 43)
    yield (s1, s2)

// Stateful handler: thread a counter across calls.
val ran = ArrowEffect.handleLoop(Tag[E], 0, twice)(
    [C] =>
        (input, state, cont) =>
            Loop.continue(state + 1, cont((input + state).toString))
)

assert(ran.eval == ("42", "44"))
```

### Installing a context value: `ContextEffect.handle`

When a fresh value should be installed for the scope, use the two-argument form (`handle(tag, value)(v)`): the value is constant for the lifetime of `v`. The three-argument form (`handle(tag, ifUndefined, ifDefined)(v)`) supports layered handling: if no outer handler has installed a value, use `ifUndefined`; if one has, transform it through `ifDefined`.

```scala
import kyo.*
import kyo.kernel.*

sealed trait Locale extends ContextEffect[String]

def currentLanguage: String < Locale =
    ContextEffect.suspend(Tag[Locale])

// Install a constant locale.
val direct: String = ContextEffect.handle(Tag[Locale], "en")(currentLanguage).eval
assert(direct == "en")

// Layered: nested handler transforms the outer-installed value.
val layered: String =
    ContextEffect.handle(Tag[Locale], "en", _.toUpperCase)(currentLanguage).eval
assert(layered == "en")
```

`ContextEffect.handle` is the typical building block for effect modules that supply ambient values (request scope, locale, configuration). The two- and three-argument forms compose, so an outer module can install a default and an inner module can transform it.

## Stack-safe iteration

Kyo computations are already stack-safe: every chain longer than the safepoint threshold suspends. `Loop` is the ergonomic, JIT-friendlier path for explicit iteration that performs effects between steps. It maintains up to four state values and decides each iteration whether to `Loop.continue(...)` or `Loop.done(...)`.

### Stateful loops: `Loop.apply` and `Loop.indexed`

When iteration carries explicit state, `Loop.apply(input)(run)` runs `run` with the current state and continues until `run` produces `Loop.done(...)`. State arities of 1 through 4 are provided.

```scala
import kyo.*

val countTo5: Int < Any =
    Loop(1): i =>
        if i < 5 then Loop.continue(i + 1)
        else Loop.done(i)

assert(countTo5.eval == 5)
```

`Loop.indexed(run)` tracks a `0`-based index automatically. The body returns `Loop.continue` or `Loop.done(result)`. Indexed variants for 1 through 4 explicit state values exist as `Loop.indexed`.

### Looping without explicit state

When iteration doesn't need explicit state, `Loop.foreach(run)` loops until `run` yields `Loop.done`. `Loop.forever(run)` loops indefinitely, returning `Nothing`. `Loop.whileTrue(condition)(run)` loops while `condition` evaluates true.

```scala
import kyo.*

// Loop.forever has a return type of Nothing < S; use it for servers / workers.
val worker: Nothing < Any = Loop.forever:
    Kyo.unit // do one unit of work
```

> **Caution:** `Loop.repeat(n)(run)` runs the body `n + 1` times, not `n` times. The body runs for `i` in `0..n` inclusive because the loop seeds at `loop(0)` and exits only when `i > n`; that is n+1 iterations. Reader expectation is "repeat n means n iterations." If you need exactly `n` iterations, use `Loop.indexed` and `Loop.done` at `i == n`, or pass `n - 1` to `Loop.repeat`.

### The iteration outcome: `continue`, `done`, `Outcome`

The return type of each iteration is an `Outcome[State, Result]`, an opaque sum of `Continue[State]` (carry forward) and the plain `Result` (we're done). `Outcome2`, `Outcome3`, `Outcome4` are the multi-state variants. `Loop.continue` and `Loop.done` are the constructors.

> **Caution:** The `Continue[A]` / `Outcome[A, O]` opaque-pair encoding means `Loop.done(x)` and `Loop.continue(x)` look symmetric but pivot on whether the runtime sees a `Continue` instance vs a raw `O`. If your result type `O` is itself a subtype of `Continue`, dispatch breaks. This is rare in practice (the only concrete `Continue` instances live inside the kernel), but it is the one case where the symmetric API can surprise.

## Sequential traversal of collections

The `Kyo` companion provides sequential effectful collection operations for `List[A]`, `Seq[A]`, `Chunk[A]`, `Set[A]`, `Map[K, V]`, and the generic `CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]]`. The common shape is "take a collection, apply an effectful function to each element in order, collect results."

These are *sequential*. The names give no hint: `Kyo.foreach(vs)(f)` runs `f(vs(0))` to completion, then `f(vs(1))`, and so on. Reach for `Async.foreach` (in `kyo-prelude`) when you want concurrency.

> **Caution:** `Kyo.foreach` / `foreachConcat` / `foldLeft` and the rest are sequential. The scaladoc tells you to use `Async` for parallel; the names give no hint, so it is easy to assume parallelism that isn't there.

### Mapping a collection: `foreach`, `foreachConcat`, `foreachIndexed`, `foreachDiscard`

```scala
import kyo.*
import kyo.kernel.*

case class Visitor(name: String, language: String)
enum Greeting:
    case Hello, GoodMorning, GoodEvening

sealed trait Greet extends ArrowEffect[Const[Visitor], Const[Greeting]]

def greet(v: Visitor): Greeting < Greet =
    ArrowEffect.suspend[Any](Tag[Greet], v)

val visitors = List(Visitor("Ada", "en"), Visitor("Lina", "de"))

// foreach: one result per element, same collection type.
val results: List[Greeting] < Greet = Kyo.foreach(visitors)(greet)

// foreachIndexed: f sees the 0-based index.
val labelled: List[String] < Greet =
    Kyo.foreachIndexed(visitors)((i, v) => greet(v).map(g => s"$i: $g"))

// foreachConcat: each result is itself a collection; flatten as we go.
val expanded: List[Greeting] < Greet =
    Kyo.foreachConcat(visitors)(v => greet(v).map(g => List(g, g)))

// foreachDiscard: side-effect only, returns Unit.
val sent: Unit < Greet = Kyo.foreachDiscard(visitors)(v => greet(v).map(_ => ()))
```

### Filtering, folding, collecting

```scala
import kyo.*
import kyo.kernel.*

case class Visitor(name: String, language: String)

val visitors = List(Visitor("Ada", "en"), Visitor("Bo", "en"), Visitor("Lina", "de"))

val englishOnly: List[Visitor] < Any =
    Kyo.filter(visitors)(v => v.language == "en")

assert(englishOnly.eval == List(Visitor("Ada", "en"), Visitor("Bo", "en")))

val totalChars: Int < Any =
    Kyo.foldLeft(visitors)(0)((acc, v) => acc + v.name.length)

assert(totalChars.eval == 2 + 2 + 4)

// collect: drop Absent results, keep Present values.
val longNames: List[String] < Any =
    Kyo.collect(visitors): v =>
        if v.name.length >= 3 then Present(v.name)
        else Absent

assert(longNames.eval == List("Ada", "Lina"))

// collectAll / collectAllDiscard: sequence a collection of effects.
val sequenced: List[Int] < Any = Kyo.collectAll(List(1, 2, 3).map(i => (i * 10): Int < Any))
assert(sequenced.eval == List(10, 20, 30))
```

### Searching and slicing: `findFirst`, `takeWhile`, `dropWhile`, `span`

```scala
import kyo.*

case class Visitor(name: String, language: String)

val visitors = List(Visitor("Ada", "en"), Visitor("Bo", "en"), Visitor("Lina", "de"))

val firstDe: Maybe[Visitor] < Any =
    Kyo.findFirst(visitors): v =>
        if v.language == "de" then Present(v) else Absent

assert(firstDe.eval == Present(Visitor("Lina", "de")))

val prefix: List[Visitor] < Any =
    Kyo.takeWhile(visitors)(v => v.language == "en")

assert(prefix.eval == List(Visitor("Ada", "en"), Visitor("Bo", "en")))

val rest: List[Visitor] < Any =
    Kyo.dropWhile(visitors)(v => v.language == "en")

assert(rest.eval == List(Visitor("Lina", "de")))

val (en, others) = Kyo.span(visitors)(v => v.language == "en").eval
assert(en == List(Visitor("Ada", "en"), Visitor("Bo", "en")))
assert(others == List(Visitor("Lina", "de")))
```

### Partition, scan, group

```scala
import kyo.*

case class Visitor(name: String, language: String)

val visitors = List(Visitor("Ada", "en"), Visitor("Bo", "en"), Visitor("Lina", "de"))

val (en, de) = Kyo.partition(visitors)(v => v.language == "en").eval
assert(en == List(Visitor("Ada", "en"), Visitor("Bo", "en")))
assert(de == List(Visitor("Lina", "de")))

// partitionMap: each element produces Either; lefts and rights split.
val (errors, names) =
    Kyo.partitionMap(visitors): v =>
        if v.name.length < 3 then Left(s"too short: ${v.name}")
        else Right(v.name)
    .eval
assert(errors == List("too short: Bo"))
assert(names == List("Ada", "Lina"))

// scanLeft: history of accumulator values.
val sizes: List[Int] < Any =
    Kyo.scanLeft(visitors)(0)((acc, v) => acc + v.name.length)
assert(sizes.eval == List(0, 3, 5, 9))

// groupBy: keyed result map.
val byLang: Map[String, List[Visitor]] < Any =
    Kyo.groupBy(visitors)(v => v.language)
assert(byLang.eval == Map("en" -> List(Visitor("Ada", "en"), Visitor("Bo", "en")), "de" -> List(Visitor("Lina", "de"))))

// groupMap: per-element transform alongside the key.
val namesByLang: Map[String, List[String]] < Any =
    Kyo.groupMap(visitors)(v => v.language)(v => v.name)
assert(namesByLang.eval == Map("en" -> List("Ada", "Bo"), "de" -> List("Lina")))
```

### Building and zipping: `fill`, `zip`, `when`, `unless`

When you need N independent runs of the same computation, `Kyo.fill(n)(v)` collects them into a `Chunk[A]`. `Kyo.zip(v1, v2, ...)` collects between 2 and 10 independent computations into a tuple, sequentially.

```scala
import kyo.*

val tens: Chunk[Int] < Any = Kyo.fill(3)(10)
assert(tens.eval == Chunk(10, 10, 10))

val (a, b, c) = Kyo.zip(1, 2, 3).eval
assert((a, b, c) == (1, 2, 3))

// when: effectful if/else; both branches return the same type.
val branched: String < Any =
    Kyo.when(true)("yes", "no")
assert(branched.eval == "yes")

// when (single-branch): returns Maybe to encode "ran" / "skipped".
val maybeRan: Maybe[String] < Any =
    Kyo.when(true)("ran")
assert(maybeRan.eval == Present("ran"))

// unless: same as one-branch when, with the condition negated.
val maybeSkipped: Maybe[String] < Any =
    Kyo.unless(true)("skipped")
assert(maybeSkipped.eval == Absent)
```

`Kyo.lift(v)` is an explicit, allocation-free lift of a plain value, useful when inference in an if/else needs a nudge. `Kyo.unit` is `().pure[Any]` written succinctly.

### Map-specific: `filterKeys`

`Map`-valued sources get one extra combinator: `Kyo.filterKeys` filters the map by an effectful predicate on the keys.

```scala
import kyo.*

val byLang: Map[String, Int] = Map("en" -> 2, "de" -> 1, "fr" -> 0)

val nonZero: Map[String, Int] < Any =
    Kyo.filterKeys(byLang)(k => byLang(k) > 0)

assert(nonZero.eval == Map("en" -> 2, "de" -> 1))
```

## Isolating effects across forks

When a computation forks (a new fiber, a parallel batch, a detached worker), effects need explicit handling for what crosses the boundary and what gets restored when the fork completes. `Isolate` is the abstraction: a three-phase state manager that captures state before the fork, manages it during, and restores after.

The kernel ships the abstraction. The concrete strategies (`Var.isolate.update`, `Emit.isolate.merge`, ...) live with their effects in `kyo-prelude`. For most use cases the compiler derives an `Isolate` automatically for an intersection of effects, using the per-effect instances each module provides.

### The three type parameters of `Isolate`

The three type parameters precisely control effect flow:

- `Remove`: effects that will be satisfied (handled) by the isolation.
- `Keep`: effects that remain available during isolated execution.
- `Restore`: effects that become available after isolation completes.

The distinction between `Remove` and `Restore` allows operations to transform effects during isolation. A fiber might capture `Var[Int]` and only restore the final value, not intermediate updates.

```scala
import kyo.*
import kyo.kernel.*

sealed trait Locale extends ContextEffect[String]

// Deriving for a lone ContextEffect yields the identity isolate: derivation skips
// ContextEffects (their state rides along via Context copying at the boundary).
val isolate: Isolate[Locale, Any, Any] = Isolate.derive[Locale, Any, Any]
```

### The three phases: `capture`, `isolate`, `restore`

Each phase has a specific job: `capture` obtains the state that will be managed, `isolate` runs the forked computation with that state, and `restore` propagates the transformed state back when the fork completes.

`Isolate#run` composes all three in one call. `Isolate#nest` is the alternative when you want to inspect or chain the restored effects yourself.

> **Caution:** `nest` tunnels `Remove` effects out as a *nested* `A < Restore < (Remove & Keep & S)`. Unlike `run`, the restored effects don't auto-apply; the caller must `.flatten` or chain through `.map` to reach them. This is the point of `nest`: it lets you control when the restore happens.

### `use` and `andThen`: ergonomics and composition

When a kyo-prelude operation like `Async.parallel` needs to find its isolation strategy implicitly, `isolate.use { ... }` makes the `Isolate` available as a `given` for the block. `andThen` composes two isolates: `Remove` effects union, `Keep` effects intersect, `Restore` effects union.

### Summoning and deriving instances

Most code never has to mention derivation explicitly: `Isolate.apply[Remove, Keep, Restore]` summons an instance from `given`s in scope, and `Isolate.derive[Remove, Keep, Restore]` is the explicit macro-based derivation, also exposed as the default `given`. The derivation:

- Skips effects in `Keep` (they stay available, no isolation needed).
- Skips `ContextEffect`s (handled by simple context copying, not the three-phase machinery).
- Composes per-effect `Isolate`s with `andThen` in the order they appear.

> **Caution:** Derivation skips `ContextEffect`s entirely. Adding an `Isolate.Stateful` for a `ContextEffect` will not be selected by the derivation; context effects are propagated by copying the underlying `Context`, not by the three-phase machinery.

> **Caution:** Short-circuiting effects (`Abort`, `Choice`) deliberately do not provide `Isolate` instances. The order of handling in the automatic derivation would change observable results, so the kernel forbids participation rather than picking an order silently. Handle `Abort` or `Choice` explicitly before the operation that requires isolation.

## Putting it together

The four pieces every concrete kyo effect uses: define the effect, suspend it from a user-facing function, traverse a collection sequentially, and interpret it with a handler that may itself consult other effects. The visitor-greeter below shows all four at once.

`Visitor` has a name and a language code. `Greet` is an `ArrowEffect` that picks a `Greeting` for a given visitor. `Locale` is a `ContextEffect` that supplies the active language code.

```scala
import kyo.*
import kyo.kernel.*

enum Greeting:
    case Hello, GoodMorning, GoodEvening

case class Visitor(name: String, language: String)

sealed trait Greet  extends ArrowEffect[Const[Visitor], Const[Greeting]]
sealed trait Locale extends ContextEffect[String]

// Suspend: request a greeting for one visitor; both effects appear in the row.
def greet(v: Visitor): Greeting < (Greet & Locale) =
    ArrowEffect.suspend[Any](Tag[Greet], v)

// Traverse: same shape, the row accumulates as Greet & Locale.
def greetAll(vs: List[Visitor]): List[Greeting] < (Greet & Locale) =
    Kyo.foreach(vs)(greet)

// Handle: interpret Greet by consulting Locale for each visitor.
def runGreet[A, S](v: A < (Greet & S)): A < (Locale & S) =
    ArrowEffect.handle(Tag[Greet], v):
        [C] =>
            (visitor, cont) =>
                ContextEffect.suspend(Tag[Locale], default = "en").map: lang =>
                    cont(lang match
                        case "en" => Greeting.Hello
                        case "de" => Greeting.GoodMorning
                        case _    => Greeting.GoodEvening)

// Compose: handle Greet, install a Locale, then run.
val visitors = List(Visitor("Ada", "en"), Visitor("Lina", "de"))
val result: List[Greeting] =
    greetAll(visitors)
        .handle(runGreet, ContextEffect.handle(Tag[Locale], "en"))
        .eval
// result == List(Greeting.Hello, Greeting.GoodMorning)
```

## Runtime details worth knowing

A few hardcoded behaviors of the runtime occasionally matter when reading stack traces or benchmarking.

The kernel suspends a synchronous chain every `maxStackDepth` frames. This threshold is platform-specific (set in `kyo.internal.Platform`): 512 on the JVM and JS, 256 on Native and WebAssembly, which run on smaller call stacks. A long-running pure chain that the JIT could in principle inline does not get inlined past this depth; it suspends through `Safepoint` instead. This is what makes Kyo stack-safe without unbounded recursion. Reader expectation: "stack-safe = unbounded recursion at no cost" is wrong; deep synchronous chains still pay the suspension cost periodically.

`Safepoint.enter` checks both stack depth and thread id. A `Kyo` value created on thread A and resumed on thread B forces a re-entry through the suspension machinery. Cross-thread reuse is not a no-op; if you build a pending value on one thread and execute it on another, expect the first step to take the suspension path.

These details are invisible to most users. They become relevant when measuring throughput, debugging traces, or wondering why a tight loop's profile has unexpected suspension counts.
