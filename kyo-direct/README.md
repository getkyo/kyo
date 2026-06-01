# kyo-direct

`kyo-direct` lets you write effectful Kyo computations in plain imperative style. You wrap a block in `direct { ... }`, then write straight-line code; at each point where you would otherwise have a `T < S` value, you append `.now` to sequence the effect and bind its result, or `.later` to keep the effect un-sequenced for explicit composition. A macro rewrites the block into ordinary monadic `flatMap` chains over Kyo's pending type `A < S`, summing the effect rows of every `.now` site into the block's pending type. The translation is purely syntactic sugar over `flatMap`; nothing new appears at runtime.

Because the block is a regular Scala expression but its terms cross effect boundaries, `kyo-direct` restricts which language constructs are legal inside it. `var`, `lazy val`, nested `class`/`trait`/`object`, `try`/`catch`, `throw`, `synchronized`, mutable field access, and `def`s that contain `.now` are all rejected at compile time with targeted error messages that name the proper Kyo replacement (`Atomic*`, `Abort`, `Async.memoize`, and others).

```scala
import kyo.*

case class User(id: Int, name: String)
case class NotFound(id: Int)

def fetchUser(id: Int): User < (Async & Abort[NotFound]) =
    if id > 0 then User(id, s"user-$id")
    else Abort.fail(NotFound(id))

val greeting: String < (Async & Abort[NotFound]) =
    direct {
        val a = fetchUser(1).now
        val b = fetchUser(2).now
        s"Hello ${a.name} and ${b.name}"
    }
```

## Writing effects in direct style

When you have a sequence of effectful steps where each one needs the previous step's result, the natural shape is straight-line code: bind the result, use it on the next line. `direct` gives you that shape over Kyo's pending type. Wrap the block, append `.now` to bind each effect's result, and the macro produces a single `A < S` value whose `S` is the union of every effect row encountered at a `.now` site.

### `direct`

When you have effectful values that feed each other and the for-comprehension or `.map`/`.flatMap` chain reads like noise, reach for `direct`. The macro entry point is `direct[A](inline f: A): A < S`; the block's return type stays `A` and the effect row `S` is inferred from the `.now` sites inside.

```scala
import kyo.*

case class User(id: Int, name: String)
case class NotFound(id: Int)

def fetchUser(id: Int): User < (Async & Abort[NotFound]) =
    if id > 0 then User(id, s"user-$id")
    else Abort.fail(NotFound(id))

val result: String < (Async & Abort[NotFound]) =
    direct {
        val u = fetchUser(7).now
        s"got ${u.name}"
    }
```

> **Note:** `direct` is `transparent inline`. The inferred return type carries the precise effect row produced by the body, not a widened upper bound.

### `.now`

When the next line needs the effect's result as a plain value, append `.now`. It is an extension on `A < S` that the macro rewrites into a monadic bind at the call site, exposing the result as a plain `A` to the rest of the block. Outside `direct`, calling it produces a tailored compile error telling you to wrap the surrounding code in a block.

```scala
import kyo.*

case class User(id: Int, name: String)
case class NotFound(id: Int)

def fetchUser(id: Int): User < (Async & Abort[NotFound]) =
    if id > 0 then User(id, s"user-$id")
    else Abort.fail(NotFound(id))

val pair: (User, User) < (Async & Abort[NotFound]) =
    direct {
        val first  = fetchUser(1).now
        val second = fetchUser(first.id + 1).now
        (first, second)
    }
```

### `.later`

When you need to keep the un-sequenced effect around (storing it in a tuple, threading it through a lazy structure, building a reusable effect combination for a helper to sequence later), reach for `.later`. It is the dual of `.now`: it leaves the value at its position as `A < S` instead of binding the result. Use cases include lazy-structure lambdas where `.now` would be rejected, and combinator helpers that hand the pending effect back to the caller.

```scala
import kyo.*

case class User(id: Int, name: String)
case class NotFound(id: Int)

def fetchUser(id: Int): User < (Async & Abort[NotFound]) =
    if id > 0 then User(id, s"user-$id")
    else Abort.fail(NotFound(id))

// `.now` binds the result inline; the surrounding code sees a plain User.
val immediate: User < (Async & Abort[NotFound]) =
    direct {
        val user = fetchUser(1).now // User, bound here
        user
    }

// `.later` keeps the A < S value so the surrounding code can route it.
// The pending effect is sequenced only when .now is called on it.
val deferred: User < (Async & Abort[NotFound]) =
    direct {
        val pending: User < (Async & Abort[NotFound]) = fetchUser(1).later
        pending.now
    }
```

`.now` and `.later` divide the work cleanly. Reach for `.now` whenever the next line needs the result as a plain value; that is the default in straight-line code. Reach for `.later` only when the surrounding code needs the un-sequenced effect, for example when storing it in a tuple, passing it into a `Stream` lambda, or returning it from a helper that another `direct` block will sequence.

> **Caution:** Nesting `.now` inside `.later` (or the other way around) is rejected at compile time. The macro forces you to introduce an intermediate `val` so the evaluation order matches what the source reads.

## What `direct` rejects, and what to use instead

The macro statically rejects constructs whose semantics do not compose with effects. Each rejection points at a Kyo primitive that does compose. The list is small and the diagnostics walk you through the rewrite.

### Bare `T < S` as a statement

When you write an effectful expression and forget to bind or preserve it, the macro flags the bare value. Every effectful value inside a `direct` block must be followed by `.now` or `.later`; a standalone `T < S` would silently disappear from the rewritten chain because no `flatMap` binds it.

```scala
import kyo.*

def step: Int < Sync = Sync.defer(1)

// This compiles: every effect is bound.
val ok: Int < Sync =
    direct {
        val x = step.now
        x + 1
    }
```

### Shared mutable state

When you reach for `var` or a mutable field to track shared state inside a `direct` block, the macro rejects it. Monadic rewriting reorders block evaluation into chained `flatMap`s, so a `var` write and the read that follows no longer happen between the surrounding `.now` binds the way the source code suggests. The error routes you to `AtomicInt`/`AtomicRef`/... (kyo-core) for plain shared state, `TRef` (kyo-stm) for transactional state, and `Var` (kyo-prelude) for pure state in `State`-monad style.

```scala
import kyo.*

val counted: Int < (Async & Sync) =
    direct {
        val counter = AtomicInt.init(0).now
        counter.incrementAndGet.now
        counter.incrementAndGet.now
        counter.get.now
    }
```

### One-shot caching and memoization

When you reach for `lazy val` (or a nested `object`) to cache an expensive result, the macro rejects it. A lazy initializer runs the first time the value is forced, which is outside the rewritten `flatMap` chain and therefore outside every surrounding effect handler, so any `Sync`/`Async`/`Abort` work in the initializer escapes the row. Define the value outside the block, or use `Async.memoize` for one-shot caching that stays inside the effect row.

```scala
import kyo.*

def expensive: Int < Async = Async.defer(42)

val memoized: Int < Async =
    direct {
        val cached = Async.memoize(expensive).now
        val a      = cached.now
        val b      = cached.now
        a + b
    }
```

### `def` containing `.now`

When you reach for a helper `def` inside `direct` that calls `.now` in its body, the macro rejects it. The body is rewritten only when the macro can see the call site; an outer `def` cannot carry per-call effect bindings because each invocation would need its own rewrite anchored at the caller. Lift the method to the enclosing scope; the method itself can be `direct { ... }`.

```scala
import kyo.*

case class User(id: Int, name: String)

def loadName(id: Int): String < Sync =
    direct {
        Sync.defer(s"user-$id").now
    }

val program: String < Sync =
    direct {
        val a = loadName(1).now
        val b = loadName(2).now
        s"$a, $b"
    }
```

### Catching and raising failures

Catching exceptions inside a `direct` block is not allowed; the macro rejects `try`/`catch` and `throw`. The rewritten `flatMap` chain runs the effectful body asynchronously and through Kyo's effect handlers, so a thrown exception inside `.now` never reaches a surrounding Scala `try`, and a `throw` from a bound value would bypass the `Abort` row entirely. Replace `try`/`catch` with `Abort.run` and a pattern match on `Result`; replace `throw` with `Abort.fail`.

```scala
import kyo.*

case class NotFound(id: Int)

def fetch(id: Int): String < Abort[NotFound] =
    if id > 0 then s"user-$id"
    else Abort.fail(NotFound(id))

val handled: String < Any =
    direct {
        Abort.run(fetch(-1)).now match
            case Result.Success(v)            => v
            case Result.Failure(NotFound(id)) => s"missing-$id"
            case Result.Panic(e)              => s"panic-${e.getMessage}"
    }
```

### Thread coordination

When you reach for `synchronized` to coordinate access to shared state, the macro rejects it. Effect suspensions inside the block can cross thread boundaries during async scheduling, so the JVM lock acquired at the start of the block is not the lock released at the end; ownership breaks the moment a `.now` suspends. Use `AtomicRef`/`AtomicInt`/... for thread-safe single values, `Meter` for controlled concurrency, or `TRef`/STM for transactional state.

```scala
import kyo.*

val sumTwo: Int < (Async & Sync) =
    direct {
        val r = AtomicInt.init(0).now
        r.addAndGet(10).now
        r.addAndGet(20).now
        r.get.now
    }
```

### Nested `class` / `trait` / `object`

When you reach for an inline `class` or `trait` declaration inside a `direct` block, the macro rejects it. Define the type at the enclosing scope and instantiate it inside the block.

> **Note:** A `.now` inside a by-name argument is also rejected up front, because CPS sequencing cannot compose with by-name capture (left unguarded it would surface a confusing dotty-cps-async error). Bind the effect to a `val` first, then pass the value.

## Higher-order calls and lazy structures

A `direct` block needs to know what to do when `.now` appears inside a lambda passed to `map`, `flatMap`, or another higher-order method. The macro handles this by recognising a curated set of method names on a curated set of receiver types and rewriting the lambda to sequence its inner effect per element. Methods and types outside that set fall back to the "must use .now or .later" error.

### Shift-aware methods

When the macro sees a higher-order call inside `direct`, it needs a rule for whether the lambda body's `.now` should run once or once-per-element. The whitelist exists so the macro only rewrites lambdas where per-element sequencing matches the method's documented semantics; an unrecognised method falls through to the rejection error rather than guessing the wrong rewrite.

The recognised methods are:

`map`, `flatMap`, `flatten`, `collect`, `collectFirst`, `find`, `filter`, `filterNot`, `withFilter`, `dropWhile`, `takeWhile`, `partition`, `partitionMap`, `span`, `fold`, `foldLeft`, `foldRight`, `groupBy`, `groupMap`, `groupMapReduce`, `exists`, `forall`, `count`, `maxByOption`, `corresponds`, `foreach`, `tapEach`, `orElse`, `getOrElse`, `recover`, `recoverWith`, `scanLeft`, `scanRight`.

The recognised receiver types are `Iterable[?]`, `IterableOps[?, ?, ?]`, `Option[?]`, `scala.util.Try[?]`, `Either[?, ?]`, `Either.LeftProjection[?, ?]`, plus the Kyo types `Maybe` and `Result` (matched by their companion-object selector).

```scala
import kyo.*

case class User(id: Int, name: String)
case class NotFound(id: Int)

def fetchUser(id: Int): User < (Async & Abort[NotFound]) =
    if id > 0 then User(id, s"user-$id")
    else Abort.fail(NotFound(id))

val ids = Chunk(1, 2, 3)

// `.now` inside `map`'s lambda runs the effect for every element.
val names: Chunk[String] < (Async & Abort[NotFound]) =
    direct {
        ids.map(id => fetchUser(id).now.name)
    }

// `fold` works the same way; effects accumulate into the block's row.
val total: Int < (Async & Abort[NotFound]) =
    direct {
        ids.foldLeft(0)((acc, id) => acc + fetchUser(id).now.id)
    }
```

> **Note:** The whitelist is name-based. A custom method called `map` on one of the supported types is also recognised; a custom method with a name outside the list is not, even on a supported type.

### Lazy structures: `Stream`

`Stream` defers its work; running an effect inside its lambda would let the effect escape stream evaluation. The macro rejects `.now` inside any `Stream.*` lambda and asks you to choose one of two rewrites: hoist the effect outside the stream with a prior `.now`, or use `.later` so the per-element effect lifts into the stream's effect row.

```scala
import kyo.*

val nums: Stream[Int, Any] = Stream.init(Seq(1, 2, 3))

def isMatch(i: Int): Boolean < Sync = Sync.defer(i % 2 == 0)

// Hoist a value out with `.now` before building the stream.
val hoisted: Stream[Int, Any] < Sync =
    direct {
        val offset = Sync.defer(10).now
        nums.map(x => x + offset)
    }

// Keep the per-element effect with `.later`; it lifts into the stream's row.
// filter is not shift-aware, so .later is required to lift the predicate effect.
val perElement: Stream[Int, Sync] < Any =
    direct {
        nums.filter(x => isMatch(x).later)
    }
```

A `.now` in the same lambda would fail at compile time with a diagnostic that points at both rewrites.

> **Note:** The rejection is not stylistic: a `.now` evaluated lazily per stream element would run its effect outside the surrounding handlers, letting the effect escape the row. `.later` keeps the effect in the lambda so it lifts into the stream's own effect row instead.

> **Caution:** The lazy-structure rule applies inside any `Stream.*` lambda regardless of the method name. Even shift-aware names like `map` are not rewritten per-element on `Stream`; the lambda is treated as deferred work.

## Error diagnostics

When a rejection fires, you see a coloured, code-highlighted error that names the rule, shows a "not OK" snippet, and shows the rewrite. The diagnostics use `kyo.Ansi` for terminal colouring. The redirected destinations are concrete Kyo APIs (`AtomicInt`, `Var`, `TRef`, `Abort.fail`, `Async.memoize`, `Meter`), not abstract advice.

## Putting it together

The three core capabilities combine in a single block. `.now` sequences each effect and binds its result; a shift-aware `map` runs an effect per element; `.later` preserves an un-sequenced effect for explicit composition.

```scala
import kyo.*

case class User(id: Int, name: String)
case class NotFound(id: Int)

def fetchUser(id: Int): User < (Async & Abort[NotFound]) =
    if id > 0 then User(id, s"user-$id")
    else Abort.fail(NotFound(id))

// Sequence two effects and combine their results
val greeting: String < (Async & Abort[NotFound]) =
    direct {
        val a = fetchUser(1).now
        val b = fetchUser(2).now
        s"Hello ${a.name} and ${b.name}"
    }

// Run an effect per element via a shift-aware map
val allNames: Chunk[String] < (Async & Abort[NotFound]) =
    direct {
        Chunk(1, 2, 3).map(id => fetchUser(id).now.name)
    }

// Preserve an effect without sequencing it, then sequence it later
val deferredFetch: User < (Async & Abort[NotFound]) =
    direct {
        val pending = fetchUser(42).later
        pending.now
    }
```

## Cross-platform

`kyo-direct` builds and runs identically on JVM, Scala.js, and Scala Native. The macro is the same on all three platforms.
