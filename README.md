<img src="https://raw.githubusercontent.com/getkyo/kyo/main/kyo.png" width="200" alt="Kyo">

[![Build](https://img.shields.io/github/actions/workflow/status/getkyo/kyo/build-main.yml?branch=main&logo=github&label=build)](https://github.com/getkyo/kyo/actions/workflows/build-main.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3?logo=apachemaven&label=maven%20central)](https://search.maven.org/search?q=g:io.getkyo)
[![Scala 3](https://img.shields.io/badge/scala-3-DC322F?logo=scala&logoColor=white)](https://www.scala-lang.org)
[![Scaladoc](https://img.shields.io/badge/scaladoc-latest-blue)](https://javadoc.io/doc/io.getkyo/kyo-core_3)
[![Discord](https://img.shields.io/discord/1087005439859904574?logo=discord&logoColor=white&label=discord&color=5865F2)](https://discord.gg/KxxkBbW8bq)
[![License](https://img.shields.io/github/license/getkyo/kyo?color=blue)](LICENSE.txt)

Kyo is a Scala 3 toolkit for building applications. Its starting point is what it makes impossible: an error path left unhandled does not compile. An effect used without being declared in the signature is rejected at the call site. A resource cannot outlive its scope, even when something fails partway through. Concurrent work started together cannot leak its children. A durable workflow's progress survives a crash and resumes from the last completed step. One source tree compiles to JVM, JavaScript, Scala Native, and WebAssembly.

The mechanism behind those guarantees is algebraic effects with modular handlers, exposed through a compact infix type `A < S` ("A pending S"): `A` is the value the computation produces, and `S` is the open, type-level *set* of effects it needs. A function returning `A < (Sync & Abort[E] & Env[Config] & Emit[Log])` names its four effects in its return type. Handlers remove effects from the set one at a time, the call site decides where each one runs, and the compiler refuses to execute anything until the set is empty. Composition stays ordinary Scala: `map`, `flatMap`, and for-comprehensions over plain values. The theory traces back to Plotkin and Pretnar and to languages like Koka and Eff.

For a deeper context, see [Suspension: the magic behind composability](https://www.youtube.com/watch?v=y3KiuFczOFE) (Lambda Days 2025), or jump to [Modules](#modules) for the ecosystem map.

> Coming from ZIO or Cats Effect? Jump straight to [the migration table](#coming-from-zio-or-cats-effect).

## Getting Started

Add the modules you want to `build.sbt`. Most application code starts with `kyo-core` (see the [Modules](#modules) tables below for what each module provides). Add more modules as needed.

```sbt doctest:expect=skipped
libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-core" % "<version>",
    "io.getkyo" %% "kyo-http" % "<version>"    // optional: HTTP client/server
)
```

Use `%%` for JVM and Scala Native, `%%%` for the Scala.js and WebAssembly backends. See the [Modules](#modules) tables below for platform support per module. Replace `<version>` with: ![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3)

A minimal application extends `KyoApp`, the entrypoint trait that discharges the effect row your `main` body produces:

```scala doctest:expect=skipped
import kyo.*

object Hello extends KyoApp:
    run {
        Console.printLine("Hello from Kyo")
    }
end Hello
```

A first read-through of [`kyo-core/README.md`](kyo-core/README.md) covers `Sync`, `Async`, `Scope`, `Fiber`, `KyoApp`, and the standard concurrent primitives. The natural follow-up for an application developer is [`kyo-http`](kyo-http/README.md). From there, drop into the module map below as your application grows.

### Prerequisites

Kyo uses advanced features from the latest Scala 3 versions that IntelliJ IDEA may not fully support. For a smoother development experience, we recommend using a [Metals-based](https://scalameta.org/metals/) IDE with the SBT BSP server, which offers better stability. Refer to the Metals [guide](https://scalameta.org/metals/docs/build-tools/sbt/#sbt-build-server) to switch from Bloop to SBT BSP.

These Scala compiler flags are required when working with Kyo. They catch common mistakes (silently discarded effects, untyped equality) that the API surface relies on you not making:

1. `-Wvalue-discard`: Warns when non-Unit expression results are unused.
2. `-Wnonunit-statement`: Warns when non-Unit expressions are used in statement position.
3. `-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error`: Elevates the warnings from the previous flags to compilation errors.
4. `-language:strictEquality`: Enforces type-safe equality comparisons by requiring explicit evidence that types can be safely compared.

Add these to your `build.sbt`:

```scala doctest:expect=skipped
scalacOptions ++= Seq(
    "-Wvalue-discard",
    "-Wnonunit-statement",
    "-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error",
    "-language:strictEquality"
)
```

## Introduction

This section is how the guarantees work: the pending type, how to import Kyo, how to compose effects, the shape every effect follows, how handler ordering shows up in the result type, and the recommended writing style. It is the working vocabulary for every module README, and each subsection links into the relevant per-module deep dive.

### The pending type

The pending type is the one piece of vocabulary you need before reading the rest of the docs. Kyo wraps every effectful computation in an opaque type `A < S` ("`A` pending `S`"), where `A` is the value the computation will produce and `S` is the *set* of effects it depends on. `S` is a Scala 3 intersection type built from individual effect markers like `Sync`, `Async`, `Abort[E]`, `Env[R]`, `Var[V]`, `Emit[V]`, `Scope`, and any custom effect you define. An effect is *added* to the row by mentioning it in the type; an effect is *removed* by applying its handler.

A plain value lifts into the empty row (`A < Any`) automatically via an implicit conversion, so a bare `42` is already a valid `Int < S` for any `S`. There is no need for a `pure` / `Kyo.lift` call: any non-computation expression in a position that wants a computation is lifted on the spot.

```scala doctest:scope=inherited
import kyo.*

// implicit conversion: the bare 42 lifts into the empty effect row
val a: Int < Any = 42

// an Abort effect joins the row (map extracts the Int before the conditional)
val b: Int < Abort[String] =
    a.map(v => if v > 0 then v else Abort.fail("not positive"))

// adding Sync wraps b in a side-effectful step: the row now carries both
val c: Int < (Abort[String] & Sync) =
    Sync.defer(b)
```

Handlers discharge one effect at a time, returning a value whose row no longer mentions that effect:

```scala doctest:scope=inherited
// Abort.run handles Abort[String], leaving < Sync
val d: Result[String, Int] < Sync = Abort.run(c)
```

`.eval` is the "give me the value" call for computations without side effects (no pending `Sync`). The compiler will refuse it until every effect in the row has been handled. A `< (Sync & Abort[E])` value cannot escape into plain Scala without `Sync` and `Abort` being explicitly handled first; the unhandled-effect set is part of the value's type. This is the single property that makes the rest of Kyo work: every signature spells out exactly which effects it consumes, and the call site decides where each one gets interpreted.

The expected way to discharge `Sync` is through an entrypoint that owns the side-effecting boundary: `kyo.KyoApp` for applications (see [`kyo-core`](kyo-core/README.md)), `kyo.test.Test` for tests (the project's own cross-platform test framework, see [`kyo-test`](kyo-test/README.md)). An escape hatch for scripts and REPL exploration exists (`Sync.Unsafe.evalOrThrow`, gated by an explicit `AllowUnsafe` witness), but it bypasses the runtime guarantees the effect system provides; see [`kyo-core`](kyo-core/README.md) for when and how to use it.

### Imports: all you need is `import kyo.*`

One import covers everything. `import kyo.*` brings in the pending type, the value types (`Maybe`, `Result`, `Chunk`, `Span`), the effects (`Sync`, `Async`, `Abort`, `Env`, `Var`, `Scope`, `Emit`), and the runtime primitives (`Fiber`, `Channel`, `Promise`, `AtomicRef`). Adding a module to the classpath adds its types to that same import: no per-module import to remember. Two subpackages exist for specialized use, `kyo.kernel.*` (defining new effects) and `kyo.compat.*` (the runtime-portable library-author surface, see [`kyo-compat`](kyo-compat/README.md)), and any module asking for an import beyond `kyo.*` says so in its README.

### Composing computations: `map`, `flatMap`, and `for`

`A < S` is a monad. You build larger computations out of smaller ones with `map` and `flatMap`, the same way you would with `Option`, `Future`, `IO`, or `ZIO`:

```scala
val parsed: Int < Sync =
    Sync.defer("42").map(_.toInt)

val chained: String < Sync =
    Sync.defer("42").flatMap { s =>
        Sync.defer(s.toInt).map(_ + 1).map(_.toString)
    }
```

In Kyo, `map` and `flatMap` are the same method. Both have this signature:

```scala doctest:expect=skipped
extension [A, S](v: A < S)
    def map[B, S2](f: A => B < S2): B < (S & S2)
    def flatMap[B, S2](f: A => B < S2): B < (S & S2)
```

The function argument is always `A => B < S2`. A plain function `A => B` passes too because of **automatic lifting**: any value of type `A` lifts to `A < S` through a zero-cost type-level conversion (no runtime wrapping, no allocation), so `A => B` is already a valid `A => B < Any`. There is no `pure`, `succeed`, or `point` to call, ever: wherever a computation is expected, a plain value works.

```scala
def describe(n: Int < Sync): String < Sync =
    n.map(v => if v > 0 then "positive" else "non-positive")

// A plain value lifts on the spot: no wrapping call, no allocation
val fromValue: String < Sync = describe(42)

// An effectful computation passes unchanged
val fromEffect: String < Sync = describe(Sync.defer(42))

// map takes pure and effectful functions alike: a pure function's result
// lifts, an effectful function's row merges into the result type
val pureStep: Int < Sync      = Sync.defer(20).map(_ + 1)
val effectfulStep: Int < Sync = pureStep.map(v => Sync.defer(v * 2))
```

This is what keeps Kyo code free of wrapping ceremony. For-comprehensions mix pure and effectful steps without marking which is which, the bind never needs a separate `pure` operation the way classical monad encodings do, and a function that accepts `A < S` accepts a constant from a test as easily as a database call from production.

> **Note:** Automatic lifting is the type-level dual of JS-Promise-style flattening, and it is what makes the encoding sound. `Promise.then` accepts either `A => B` or `A => Promise<B>` and at runtime *flattens* a returned `Promise<Promise<A>>` down to `Promise<A>` by running the inner promise dynamically. That flatten loses the ability to represent a real `Promise<Promise<A>>`, breaks the monad laws, and forces every consumer to reason about an implicit runtime collapse. Kyo's automatic lifting does the opposite: there is no runtime collapse, `B < S2 < S3` is a real distinct type. Use `Kyo.lift` when you explicitly want to nest a computation (`Kyo.lift(inner: B < S2): (B < S2) < S3`); use `.flatten` to collapse one. The one bind operator stays both honest and law-abiding.

The idiomatic use in Kyo is composing computations with `map`. The `flatMap` method is offered for compatibility with for-comprehensions given the compiler's requirements:

```scala
val program: String < Sync =
    for
        a <- Sync.defer("40")
        b <- Sync.defer(a.toInt + 2)
    yield b.toString
```

This is the same programs-as-values style you might know from cats-effect, ZIO, fs2, or scalaz. The difference is the row: instead of one `IO[A]` per computation, each `A < S` carries the open *set* of effects it uses, and handlers shrink that set toward `Any` until `.eval` can run.

### Composing many computations: sequential vs parallel

Once you have a collection of `A < S` values, two companions cover collection-style operations:

- **`Kyo.*` for sequential execution** (defined in kyo-prelude, works over any effect row). `Kyo.collectAll(xs)` turns a `Seq[A < S]` into a `Seq[A] < S`; `Kyo.foreach(xs)(f)` maps an effectful function over a collection; `Kyo.fill(n)(v)` runs the same effect `n` times; `Kyo.zip(a, b, c)` combines several effects into a tuple. None of these require `Sync` or `Async`.
- **`Async.*` for parallel execution** (defined in kyo-core, requires `Async` in the row). `Async.foreach(xs, concurrency = 8)(f)` fans out with a bounded pool; `Async.gather(...)` collects results from concurrent computations; `Async.zip(a, b)` runs two in parallel.

Mental model: reach for `Kyo.*` when sequential processing is sufficient, `Async.*` when concurrent execution would help. The Kyo companion lives in kyo-prelude alongside the core effects, not on `Sync` or `Async` themselves, because it works for any effect row.

### The shape of an effect

Every Kyo effect has the same three-phase shape: it is *introduced* into the row, *used* inside a computation, and *discharged* by its handler. Once you have read one effect, you have read all of them. The companion-object methods follow a small naming convention that mirrors those three phases:

- `init*` constructs an instance of the effect's container type (`Fiber.init`, `Promise.init`, `Channel.init`, `Hub.init`).
- `get*` lifts an existing value into the effect's row (`Abort.get(Right(42))` produces an `Int < Abort[Nothing]`, `Env.get[Config]` reads the environment value, `Var.get[V]` reads the current state).
- `run*` discharges the effect, returning a computation whose row no longer mentions it (`Abort.run`, `Env.run(config)`, `Var.run(init)`, `Sync.Unsafe.evalOrThrow`).

> **Note:** A common misconception is that Kyo tracks every side effect a computation performs, a separate effect for console access, another for clock reads, and so on. It does the opposite. The pending set is kept deliberately small and canonical: `S` names coarse effects (can this perform side effects, can it fail, can it park), not individual operations. Printing to the console, for instance, is just `Sync`, with no `Console` effect in the row (`Console` is an ordinary API whose methods return `Unit < Sync`). Keeping the set small is what makes rows readable and effects compose, instead of turning every signature into a ledger of operations.

Defining a brand-new effect is therefore an advanced, rarely-needed step. The three-phase shape applies to your own effects too (`ArrowEffect` / `ContextEffect`, see [`kyo-kernel`](kyo-kernel/README.md)), but most of what looks like it needs a new effect is better served by an opaque type with extension methods layered over the existing effects. Reach for a genuinely new effect only when no existing one captures the control-flow shape you need.

```scala doctest:scope=inherited
val program: Int < (Abort[String] & Env[Int]) =
    for
        v <- Abort.get(Right(42)) // introduce a value into Abort[String]'s row
        e <- Env.get[Int]         // read the Env[Int] context value
    yield v + e // use both; Kyo tracks the row in the type

// run* discharges one effect at a time, narrowing the row
val handled: Result[String, Int] < Any =
    Env.run(10)(Abort.run(program))

val result: Result[String, Int] = handled.eval
```

When more than one handler is needed, the `.handle` method chains them left-to-right without nesting parentheses. It takes any number of functions and applies them in order, so a handler pipeline reads top-down the way the row shrinks:

```scala doctest:scope=inherited
val viaHandle: Result[String, Int] =
    program.handle(Abort.run(_), Env.run(10)).eval

// equivalent to the nested form above
val viaNesting: Result[String, Int] =
    Env.run(10)(Abort.run(program)).eval

// .handle also accepts ordinary transformations and `.eval` itself
val unwrapped: Int =
    program.handle(Abort.run(_), Env.run(10), _.map(_.getOrElse(0)), _.eval)
```

### Effect ordering

Swapping the order in which two handlers run changes the result type of the computation, not just its runtime semantics. The inner handler's residual type is the outer handler's input, so the resulting shape is composed bottom-up. The type checker catches mismatches at the call site.

A short illustration with `Var[Int]` and `Abort[String]`. `Var.runTuple(init)(v)` pairs the final state with the result; `Abort.run(v)` lifts a possible failure into a `Result`. Running them in opposite orders produces different return types:

```scala doctest:scope=inherited
val counted: Int < (Abort[String] & Var[Int]) =
    for
        _ <- Var.update[Int](_ + 1)
        n <- Var.get[Int]
        _ <- if n < 5 then ().asInstanceOf[Unit < Any] else Abort.fail("limit reached")
    yield n

// Var.runTuple inside, Abort.run outside.
// The Var folds its state into a tuple. If Abort fires, the whole computation aborts
// before the tuple is built. Result: Result[String, (Int, Int)].
val varInner: Result[String, (Int, Int)] =
    counted.handle(Var.runTuple(0), Abort.run(_)).eval

// Abort.run inside, Var.runTuple outside.
// Abort.run wraps the Int in a Result while Var is still in the row, so when
// Var.runTuple runs it pairs its state with that Result. State survives the abort.
// Result: (Int, Result[String, Int]).
val varOuter: (Int, Result[String, Int]) =
    counted.handle(Abort.run(_), Var.runTuple(0)).eval
```

The state visibility differs: `varOuter` lets you read the final counter even when the computation aborted, because `Var.runTuple` saw the `Result` instead of being short-circuited; `varInner` discards any state that did not survive into the produced tuple. The same pattern shows up for `Choice` with `Abort` (a `Seq[Result[E, A]]` versus a `Result[E, Seq[A]]`), for `Emit` with `Abort` (emitted values up to the failure point are kept or lost), and for any pair of effects where one observes the other. See [`kyo-prelude`](kyo-prelude/README.md) and [`kyo-kernel`](kyo-kernel/README.md) for the per-effect discussion.

### Idiomatic style: monadic by default

Kyo is programs-as-values: every `A < S` is a pure description that handlers interpret. The recommended way to write Kyo code is the monadic style introduced above: `map` and Scala 3's for-comprehensions over `A < S` values, with handler chains (`comp.handle(Abort.run, Env.run(config))`) discharging effects runs the program. The rest of the documentation, the worked examples, and the library code itself are written in this form because monadic composition needs no macro, no compiler-plugin syntax, and no rewrite rules: it is just the pure-monad style.

If you prefer direct-style code, [kyo-direct](kyo-direct/README.md) ships a `direct { ... }` macro that lets you write `val x = effect1.now; val y = effect2.now; x + y` and compiles it to the same `map` chain. Direct style is an alternative, not the default. Reach for it when a function's suspension graph is dense enough that nested `map` calls or a long for-comprehension would obscure the logic; stay with the monadic style everywhere else. Either way, the underlying values are still pure monadic computation: `direct { ... }` is sugar, not a different runtime.

## Platforms

One source tree, one Scala 3 LTS compiler, four published targets:

| Platform     | Runtime              | Coordinate |
| ------------ | -------------------- | ---------- |
| JVM          | JDK 21+              | `%%`       |
| Scala.js     | Node.js, browsers    | `%%%`      |
| Scala Native | Native binary (LLVM) | `%%`       |
| WebAssembly  | Node.js 24+          | `%%%`      |

Scala.js and the WebAssembly backend share a single-threaded, event-loop concurrency model; on the JVM, Kyo runs its multi-threaded work-stealing scheduler.

WebAssembly uses the experimental Scala.js WebAssembly backend (WasmGC). It runs on Node.js 24+, where V8's Turboshaft Wasm pipeline is the default; Kyo passes `--experimental-wasm-exnref` for the exception-handling opcodes the backend emits. Because the backend shares Scala.js's source and model, WASM coverage matches Scala.js.

## Modules

Every module ships its own README. Open the linked README for the full surface, features, callouts, and worked examples. The tables below name each module's identity in one sentence so you can pick the right one fast. Each identity cell names types and operations defined inside that module; expect unfamiliar names on first scan and treat the linked README as the source for what each one does. Platform columns mean published artifacts: ✅ marks the platforms each module is published for.

### Core

What every Kyo program uses. `kyo-core` and `kyo-prelude` carry the effects you touch most, `kyo-data` the value types they return. `kyo-kernel` defines `A < S` itself and is where effect authors look. `kyo-scheduler` is the engine fibers run on, also usable as a standalone jar (see [the drop-in scheduler](#drop-in-scheduler-for-zio-pekko-finagle)). `kyo-data` also works standalone: `Maybe`, `Result`, and `Chunk` without the effect system.

| Module                                       | JVM | JS  | Native | WASM | Identity                                                                                                   |
| -------------------------------------------- | --- | --- | ------ | ---- | ---------------------------------------------------------------------------------------------------------- |
| [kyo-core](kyo-core/README.md)               | ✅  | ✅  | ✅     | ✅   | I/O and concurrency: `Sync`, `Async`, `Scope`, `Fiber`, `Channel`, `Hub`, `Queue`, `Clock`, `Log`, `Path`  |
| [kyo-prelude](kyo-prelude/README.md)         | ✅  | ✅  | ✅     | ✅   | Strictly-pure effect layer: `Abort`, `Env`, `Var`, `Memo`, `Choice`, `Emit`, `Poll`, `Stream`, `Layer`     |
| [kyo-data](kyo-data/README.md)               | ✅  | ✅  | ✅     | ✅   | Low-allocation data types: `Maybe`, `Result`, `Chunk`, `Span`, `Duration`, `Instant`, `Schedule`, `TypeMap`|
| [kyo-kernel](kyo-kernel/README.md)           | ✅  | ✅  | ✅     | ✅   | Algebraic-effects substrate; defines `A < S`, `ArrowEffect`, `ContextEffect`, multi-shot continuations     |
| [kyo-scheduler](kyo-scheduler/README.md)     | ✅  | ✅  | ✅     | ✅   | Adaptive work-stealing pool with automatic blocking detection and admission control                        |

### Applications

The vertical an application developer assembles: HTTP services and clients, derived codecs, runtime config and feature flags, durable workflows, web UIs, and a GraphQL surface.

| Module                                       | JVM | JS  | Native | WASM | Identity                                                                                                   |
| -------------------------------------------- | --- | --- | ------ | ---- | ---------------------------------------------------------------------------------------------------------- |
| [kyo-http](kyo-http/README.md)               | ✅  | ✅  | ✅     | ✅   | HTTP/1.1 client and server with shared API across JVM/JS/Native/WASM, bidirectional OpenAPI                |
| [kyo-schema](kyo-schema/README.md)           | ✅  | ✅  | ✅     | ✅   | One `derives Schema` powers validation, lenses, diffs, builders, and structural conversion; codecs plug in |
| [kyo-schema-json](kyo-schema-json/README.md) | ✅  | ✅  | ✅     | ✅   | JSON codec for kyo-schema: `Json.encode`/`decode` text and bytes, safety limits, JSON Schema generation    |
| [kyo-schema-protobuf](kyo-schema-protobuf/README.md) | ✅  | ✅  | ✅     | ✅   | Protocol Buffers codec for kyo-schema: `Protobuf.encode`/`decode` binary plus `.proto` schema export       |
| [kyo-schema-msgpack](kyo-schema-msgpack/README.md) | ✅  | ✅  | ✅     | ✅   | MessagePack codec for kyo-schema: `MsgPack.encode`/`decode` compact binary                                 |
| [kyo-schema-bson](kyo-schema-bson/README.md) | ✅  | ✅  | ✅     | ✅   | BSON codec for kyo-schema: `Bson.encode`/`decode` document bytes                                           |
| [kyo-schema-ion](kyo-schema-ion/README.md)   | ✅  | ✅  | ✅     | ✅   | Amazon Ion codec for kyo-schema: `Ion` text/binary, standalone `IonBinary`, Ion Schema generation          |
| [kyo-schema-yaml](kyo-schema-yaml/README.md) | ✅  | ✅  | ✅     | ✅   | YAML 1.2 codec for kyo-schema: `Yaml.encode`/`decode` plus CST and event-stream APIs                       |
| [kyo-config](kyo-config/README.md)           | ✅  | ✅  | ✅     | ✅   | Type-safe config + feature flags with a percentage-rollout DSL, optional kyo-http admin and live sync      |
| [kyo-flow](kyo-flow/README.md)               | ✅  | ✅  | ✅     | ✅   | Durable workflow engine (Temporal/Cadence/ZIO-Flow space); value-replay execution, auto-generated REST     |
| [kyo-ui](kyo-ui/README.md)                   | ✅  | ✅  | ✅     | ✅   | Web UIs as pure values: Scala.js DOM app, server HTML-over-SSE or SSR stream with first-class reactivity   |
| [kyo-markdown](kyo-markdown/README.md)       | ✅  | ✅  | ✅     | ✅   | Markdown to a kyo-ui article tree plus a heading outline; pure, total, no third-party Markdown dependency  |
| [kyo-ai](kyo-ai/README.md)                   | ✅  | ✅  | ✅     | ✅   | Typed LLM programs: prompts, tools, thoughts, agents, streaming, provider backends                         |
| [kyo-caliban](kyo-caliban/README.md)         | ✅  |     |        |      | Caliban GraphQL mounted on kyo-http: typed Kyo effects in resolvers, WebSocket subscriptions               |

### Writing style

Alternative dialects to write Kyo code more fluently. Pick `kyo-direct` for straight-line code with `.now` suspension points; pick `kyo-combinators` for ZIO-style fluent operators and the `forAbort[E1]` failure-narrowing DSL.

| Module                                         | JVM | JS  | Native | WASM | Identity                                                                                                  |
| ---------------------------------------------- | --- | --- | ------ | ---- | --------------------------------------------------------------------------------------------------------- |
| [kyo-direct](kyo-direct/README.md)             | ✅  | ✅  | ✅     | ✅   | Direct-style: `direct { val x = effect.now; ... }` desugars to the equivalent `flatMap` chain             |
| [kyo-combinators](kyo-combinators/README.md)   | ✅  | ✅  | ✅     | ✅   | Sanctioned home for symbolic operators (`*>`, `<*>`, `&>`) and the `forAbort[E1]` narrowing DSL           |

### Testing

The project's own reusable cross-platform test framework, plus a bridge for running `zio-test` suites with Kyo bodies.

| Module                                       | JVM | JS  | Native | WASM | Identity                                                                                                  |
| -------------------------------------------- | --- | --- | ------ | ---- | --------------------------------------------------------------------------------------------------------- |
| [kyo-test](kyo-test/README.md)               | ✅  | ✅  | ✅     | ✅   | Cross-platform test framework for kyo-based codebases                                                     |
| [kyo-zio-test](kyo-zio-test/README.md)       | ✅  | ✅  | ✅     | ✅   | Write `zio-test` `Spec`s whose bodies are Kyo computations (`KyoSpecDefault`, `KyoSpecAbstract`)          |

### Concurrency

Higher-level concurrency built on `kyo-core`'s fiber runtime. Reach for `kyo-actor` for typed message passing; `kyo-stm` for multi-cell atomicity; `kyo-offheap` for typed arrays outside the JVM heap.

| Module                                         | JVM | JS  | Native | WASM | Identity                                                                                                  |
| ---------------------------------------------- | --- | --- | ------ | ---- | --------------------------------------------------------------------------------------------------------- |
| [kyo-actor](kyo-actor/README.md)               | ✅  | ✅  | ✅     | ✅   | Typed actors over `Channel` and `Fiber`: `Subject[A]`, `ask`, supervision by composition                  |
| [kyo-stm](kyo-stm/README.md)                   | ✅  | ✅  | ✅     | ✅   | STM with `TRef` / `TMap` / `TChunk` / `TTable`, including compile-checked `TTable.Indexed` queries        |
| [kyo-offheap](kyo-offheap/README.md)           | ✅  |     | ✅     |      | Arena-scoped typed primitive arrays via JEP 442 (JVM 22+) and `calloc`/`free` (Native)                    |

### Specialized tools

Domain-shaped modules: parsing, durable workflows, container management, low-latency messaging, browser automation, web UIs, Slack bots, native C bindings, and TASTy reflection.

| Module                                  | JVM | JS  | Native | WASM | Identity                                                                                                   |
| --------------------------------------- | --- | --- | ------ | ---- | ---------------------------------------------------------------------------------------------------------- |
| [kyo-parse](kyo-parse/README.md)        | ✅  | ✅  | ✅     | ✅   | Parser combinators in the effect row; supports dual-input-type parsers (e.g. `Parse[Char] & Parse[Int]`)   |
| [kyo-pod](kyo-pod/README.md)            | ✅  | ✅  | ✅     | ✅   | Docker and Podman client cross-compiled to JVM/JS/Native/WASM, streaming logs/stats, scope-managed cleanup |
| [kyo-slack](kyo-slack/README.md)        | ✅  | ✅  | ✅     | ✅   | Slack Socket Mode bot client: structural acking, Web API, typed Block Kit + `dsl`, lossless reconnect      |
| [kyo-browser](kyo-browser/README.md)    | ✅  | ✅  | ✅     | ✅   | Browser automation over Chrome DevTools Protocol; settlement-aware actions, `readableContent` as Markdown  |
| [kyo-jsonrpc](kyo-jsonrpc/README.md)    | ✅  | ✅  | ✅     | ✅   | JSON-RPC 2.0 peers over pluggable transports with typed routes, calls, notifications, progress, and cancel |
| [kyo-mcp](kyo-mcp/README.md)            | ✅  | ✅  | ✅     | ✅   | Model Context Protocol client and server built on kyo-jsonrpc with typed tools, prompts, and resources     |
| [kyo-lsp](kyo-lsp/README.md)            | ✅  | ✅  | ✅     | ✅   | Language Server Protocol 3.17 servers and clients with typed handlers, documents, progress, and cancel     |
| [kyo-compiler](kyo-compiler/README.md)  | ✅  |     |        |      | Scala 3 presentation compiler pool for diagnostics, completions, hover, signatures, and symbols            |
| [kyo-aeron](kyo-aeron/README.md)        | ✅  |     |        |      | Typed pub/sub on Aeron: shared-memory IPC, UDP unicast, UDP multicast through one `Topic` API              |
| [kyo-ffi](kyo-ffi/README.md)            | ✅  | ✅  | ✅     | ✅   | Bind a C library once with typed Scala signatures; safe calls from JVM (Panama), JS/WASM (koffi), and Native |
| [kyo-tasty](kyo-tasty/README.md)        | ✅  | ✅  | ✅     | ✅   | Cross-platform TASTy reflection over a pure sealed model; Scala 3 reflection without a live JVM             |

### Observability

In-process metrics and tracing registry, OTLP exporter that activates from `OTEL_EXPORTER_OTLP_ENDPOINT`, and two bridges from `kyo.Log` to the JDK or SLF4J logging APIs.

| Module                                                   | JVM | JS  | Native | WASM | Identity                                                                                                  |
| -------------------------------------------------------- | --- | --- | ------ | ---- | --------------------------------------------------------------------------------------------------------- |
| [kyo-stats-registry](kyo-stats-registry/README.md)       | ✅  | ✅  | ✅     | ✅   | Process-global registry; counters / gauges / counter-gauges / histograms; `TraceExporter` SPI             |
| [kyo-stats-otlp](kyo-stats-otlp/README.md)               | ✅  | ✅  | ✅     | ✅   | Zero-code OTLP/HTTP+JSON exporter; W3C `traceparent` propagation auto-installed on kyo-http               |
| [kyo-stats-machine](kyo-stats-machine/README.md)         | ✅  | ✅  | ✅     | ✅   | Zero-code host metrics (CPU, memory, swap, disk, load, cgroup, PSI) into `kyo.Stat`; auto-loads on classpath |
| [kyo-logging-jpl](kyo-logging-jpl/README.md)             | ✅  |     |        |      | Bridge `kyo.Log` to `java.lang.System.Logger` (JEP 264, JDK 9+); zero third-party deps                    |
| [kyo-logging-slf4j](kyo-logging-slf4j/README.md)         | ✅  |     |        |      | Bridge `kyo.Log` to any SLF4J binding the host application already configures (Logback, Log4j 2, etc.)    |

### Interop

Whatever you keep from your current stack, there is a bridge. Bidirectional bridges to neighbouring effect systems, plus `kyo-compat` for writing a library once and shipping it to five runtimes.

| Module                                                   | JVM | JS  | Native | WASM | Identity                                                                                                  |
| -------------------------------------------------------- | --- | --- | ------ | ---- | --------------------------------------------------------------------------------------------------------- |
| [kyo-compat](kyo-compat/README.md)                       | ✅  | ✅* | ✅*    | ✅*  | Library-author API: write once against `kyo.compat.*`, ship to ZIO, Kyo, Future, Twitter Future, Ox       |
| [kyo-reactive-streams](kyo-reactive-streams/README.md)   | ✅  | ✅  | ✅     | ✅   | Bidirectional bridge between Kyo `Stream` and `Publisher`/`Subscriber`; verified against the TCK          |
| [kyo-zio](kyo-zio/README.md)                             | ✅  | ✅  | ✅     | ✅   | Three-object bridge: `ZIOs` (effects), `ZStreams` (streams), `ZLayers` (layers)                           |

*kyo-compat platform support depends on the runtime binding (-kyo / -future / -zio: JVM+JS+Native+WASM; -ox / -twitter-future: JVM).

### Dev tools

CLI-parser bridge, README example validation, runnable end-to-end programs, and the cross-runtime benchmark suite.

| Module                                       | JVM | JS  | Native | WASM | Identity                                                                                                  |
| -------------------------------------------- | --- | --- | ------ | ---- | --------------------------------------------------------------------------------------------------------- |
| [kyo-case-app](kyo-case-app/README.md)       | ✅  | ✅  | ✅     | ✅   | Bridge case-app annotation-driven CLI parsing into a Kyo `run { options => ... }` entrypoint              |
| [kyo-doctest](kyo-doctest/README.md)         | ✅  |     |        |      | Validates Markdown code blocks against the Scala 3 compiler; sbt plugin runs them on `sbt doctest`        |
| [kyo-examples](kyo-examples)                 | ✅  |     |        |      | Two runnable programs: a ledger HTTP service and an N-queens solver (run with `sbt`)                      |
| [kyo-bench](kyo-bench)                       | ✅  |     |        |      | JMH suite with side-by-side Kyo / Cats Effect / ZIO implementations for each scenario                     |

### Scheduler interop

Replace the host runtime's executors with Kyo's adaptive work-stealing scheduler. One pool covers compute and blocking work, with admission control and CPU-based blocking detection; no application code change beyond a one-line swap.

| Module                                                       | JVM | JS  | Native | WASM | Identity                                                                                                  |
| ------------------------------------------------------------ | --- | --- | ------ | ---- | --------------------------------------------------------------------------------------------------------- |
| [kyo-scheduler-zio](kyo-scheduler-zio/README.md)             | ✅  |     | ✅     |      | ZIO: `extends KyoSchedulerZIOAppDefault` or `KyoSchedulerZIORuntime.default` standalone                   |
| [kyo-scheduler-pekko](kyo-scheduler-pekko/README.md)         | ✅  |     |        |      | Pekko: one HOCON line replaces any dispatcher's executor                                                  |
| [kyo-scheduler-finagle](kyo-scheduler-finagle/README.md)     | ✅  |     |        |      | Twitter Finagle: activated by `-Dcom.twitter.finagle.exp.scheduler=kyo` (Scala 2.13 only)                 |

## Coming from ZIO or Cats Effect

Two things stay exactly as you know them. First, `A < S` is a monad: the same programs-as-values style you write today, composed with `map`, `flatMap`, and for-comprehensions, referentially transparent, no macros and no capture-based tricks involved. Second, direct-style syntax is not the programming model: it ships as a separate, optional module ([`kyo-direct`](kyo-direct/README.md)), and the core is plain monadic composition.

What changes is the effect channel. Kyo's pending set generalizes what these libraries fix in advance: where `ZIO[R, E, A]` carries two type-level channels and `cats.effect.IO[A]` carries one concrete effect type, `S` is an open set, so each requirement (`Env[R1]`, `Abort[E]`, `Sync`, `Async`) is a separate member assembled per call. Effects are not threaded through an `R` environment, hidden inside a typeclass dictionary, or stacked through monad transformers; handlers are supplied in any order at the call site.

| You currently use                                            | Kyo translation                                                                                                                                                                                |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ZIO[R, E, A]`                                               | `A < S` where `S` carries each requirement (`Env[R1]`, `Abort[E]`, `Sync`, `Async`, ...) as a separate effect member                                                                           |
| `cats.effect.IO[A]`                                          | `A < (Sync & Async & Abort[Throwable])`, plus any extra effects                                                                                                                                |
| `scala.concurrent.Future[A]`                                 | `A < (Async & Abort[Throwable])`, referentially transparent, one auto-sized scheduler in place of ad-hoc `ExecutionContext`s                                                                   |
| ZIO `ZLayer`                                                 | Kyo `Layer` (see [kyo-prelude](kyo-prelude/README.md))                                                                                                                                         |
| ZIO `ZStream` / fs2 `Stream` / Akka Streams                  | Kyo `Stream` (pull-based, chunked, fused), see [kyo-prelude](kyo-prelude/README.md)                                                                                                            |
| ZIO Test                                                     | [`kyo-test`](kyo-test/README.md), the project's own cross-platform test framework; or [`kyo-zio-test`](kyo-zio-test/README.md) to keep writing `zio-test` `Spec`s with Kyo bodies                                                                                                                           |
| zio-schema / circe / jsoniter                                | [`kyo-schema`](kyo-schema/README.md)                                                                                                                                                           |
| `Resource[IO, A]` / `ZIO.scoped`                             | `Scope` effect with `Scope.acquireRelease`, see [kyo-core](kyo-core/README.md)                                                                                                                 |
| `Ref[IO, A]` / `zio.Ref`                                     | `AtomicRef`, `AtomicInt`, `AtomicLong`, `AtomicBoolean`                                                                                                                                        |
| `Deferred[IO, A]` / `zio.Promise`                            | `Promise`                                                                                                                                                                                      |
| `cats.effect.std.Queue` / `zio.Queue`                        | `Queue` with `Access` policy (MPMC / MPSC / SPMC / SPSC)                                                                                                                                       |
| tagless final / `Sync[F]` typeclasses (library authors)      | [`kyo-compat`](kyo-compat/README.md): write once, ship to ZIO + Kyo + Future + Twitter Future + Ox via inline lowering, no typeclass dispatch                                                  |
| http4s / tapir / Play / sttp                                 | [`kyo-http`](kyo-http/README.md)                                                                                                                                                               |

Three migration paths cover most adopters:

- **Adopt Kyo end-to-end**: start at [Core](#core) and [Applications](#applications), then pick the other module groups you need.
- **Add Kyo inside an existing ZIO app**: use [`kyo-zio`](kyo-zio/README.md) for bidirectional effect interop; optionally swap the runtime scheduler with [`kyo-scheduler-zio`](kyo-scheduler-zio/README.md).
- **Write a runtime-portable library**: use [`kyo-compat`](kyo-compat/README.md) to target ZIO, Kyo, `scala.concurrent.Future`, Twitter Future, and Ox from one source tree.

ZIO migrants looking for fluent extension methods (`.race`, `.timeout`, `.retry`, `.provide`, etc.) over Kyo effects should also see [`kyo-combinators`](kyo-combinators/README.md), which is also where Cats Effect migrants find the cats-syntax-style operators (`*>`, `<*`, `>>`) and the `forAbort[E1]` failure-narrowing DSL. Migrants whose fs2 / ZStream code crosses into Kyo can route through the bidirectional `Stream` bridge in [`kyo-reactive-streams`](kyo-reactive-streams/README.md).

## Drop-in scheduler for ZIO, Pekko, Finagle

`kyo-scheduler` is the auto-sized work-stealing pool that Kyo fibers run on. Three things set it apart from a plain thread pool:

- **Work-stealing.** Idle workers pull tasks from busier workers' queues, keeping every core fed.
- **Admission control.** The pool measures its own latency under load and rejects new submissions when it cannot meet a target, rather than queuing them indefinitely.
- **CPU-based blocking detection.** It watches per-worker CPU time and reacts when a task stops making progress on-CPU, so blocking work needs no `blocking { }` annotation at the call site.

It ships as an independent jar that runs on any Scala 2 or Scala 3 codebase, with or without the rest of Kyo. Dropping it into an existing ZIO, Pekko, or Finagle service is a one-config swap: the host code keeps its native effect APIs, and the scheduler underneath adds the three additions above.

See [`kyo-scheduler`](kyo-scheduler/README.md) for the standalone API, and one of the adapter READMEs for runtime-specific wiring:

- ZIO: [`kyo-scheduler-zio`](kyo-scheduler-zio/README.md)
- Pekko: [`kyo-scheduler-pekko`](kyo-scheduler-pekko/README.md)
- Finagle: [`kyo-scheduler-finagle`](kyo-scheduler-finagle/README.md) (Scala 2.13 only)

## Talks

| Title                                                                                                                | Speaker(s)                 | Host                   | Date           | Resources                                                                                                |
| -------------------------------------------------------------------------------------------------------------------- | -------------------------- | ---------------------- | -------------- | -------------------------------------------------------------------------------------------------------- |
| [Beyond `flatMap`: Is Kyo the Future of Scala Effects?](https://youtu.be/SOHbEo7UH28)                                | Jonathan Winandy           | Scalar                 | March, 2026    |                                                                                                          |
| [Suspension: the magic behind composability (or "The Kyo Monad")](https://www.youtube.com/watch?v=y3KiuFczOFE)       | Flavio Brasil              | Lambda Days            | June, 2025     | [Slides](https://speakerdeck.com/fwbrasil/suspension-the-magic-behind-composability-or-the-kyo-monad)    |
| [An Algebra of Thoughts: When Kyo effects meet LLMs](https://www.youtube.com/watch?v=KIjtXM5dlgY)                    | Flavio Brasil              | Func Prog Sweden       | May, 2025      | [Slides](https://www.canva.com/design/DAGoBBQ3oJw/vFGuHA0z_ZFtZgbJQbQHXw/view)                           |
| [Redefining Stream Composition with Algebraic Effects](https://www.youtube.com/watch?v=WcYKTyQwEA0)                  | Adam Hearn                 | LambdaConf             | May, 2025      |                                                                                                          |
| [Kyo: A New Approach to Functional Effects in Scala](https://www.youtube.com/watch?v=uA2_TWP5WF4)                    | Flavio Brasil & Adam Hearn | Scala for Fun & Profit | February, 2025 |                                                                                                          |
| [The Actor Model Beyond Akka With Kyo](https://www.youtube.com/watch?v=VU31k3lQ8yU)                                  | Damian Reeves              | Functional Scala       | December, 2024 |                                                                                                          |
| [Building Robust Applications with Kyo: A Hands on Introduction](https://www.youtube.com/watch?v=QW8mAJr0Wso)        | Adam Hearn                 | ScalaIO                | November, 2024 | [Workshop + Slides](https://github.com/hearnadam/kyo-workshop)                                           |
| [Comparing Approaches to Structured Concurrency](https://www.youtube.com/watch?v=g6dyLhAublQ)                        | James Ward & Adam Hearn    | LambdaConf             | May, 2024      | [Slides](https://jamesward.github.io/easyracer/lambdaconf_2024.html)                                     |
| [Algebraic Effects from Scratch](https://www.youtube.com/watch?v=qPvPdRbTF-E)                                        | Kit Langton                | Func Prog Sweden       | April, 2024    |                                                                                                          |
| [Releasing Kyo: When Performance Meets Elegance In Scala](https://www.youtube.com/watch?v=FXkYKQRC9LI)               | Flavio Brasil              | Functional Scala       | December, 2023 | [Slides](https://speakerdeck.com/fwbrasil/kyo-functional-scala-2023)                                     |

A podcast interview that doubles as a one-hour live-coded tour:

<a href="http://www.youtube.com/watch?v=uA2_TWP5WF4" title="Kyo: A New Approach to Functional Effects in Scala">
    <img src="https://img.youtube.com/vi/uA2_TWP5WF4/maxresdefault.jpg" alt="Kyo: A New Approach to Functional Effects in Scala" width="500" height="300">
</a>

## Contributing

- [CONTRIBUTING.md](CONTRIBUTING.md) covers the contribution workflow, the API-design conventions, and the per-module README structure.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) covers community norms.
- [AGENTS.md](AGENTS.md) is the entry point for agent-driven contributions.
- [Discord](https://discord.gg/KxxkBbW8bq) is the fastest way to ask a question.

This project exists because of a specific belief about what software is for and who gets to build it. [Read the manifesto](MANIFESTO.md).

## Acknowledgements

Kyo's development was originally inspired by the paper ["Do Be Do Be Do"](https://arxiv.org/pdf/1611.09259.pdf) and its implementation in the [Unison](https://www.unison-lang.org/learn/language-reference/abilities-and-ability-handlers/) programming language. Kyo's effect semantics are close to Unison's abilities, expressed through monadic composition (`A < S`) rather than Unison's direct-style syntax. The runtimes differ sharply: Unison captures and resumes effects with delimited continuations that copy runtime stack segments, while Kyo represents the same continuations as pure, immutable value composition. That encoding, suspending concrete values tied to specific effects, is what makes Kyo efficient and stack-safe on the JVM without VM-level continuation support.

Kyo also draws on [ZIO](https://zio.dev/). Its core algebraic-effect mechanism generalizes ZIO's [effect rotation](https://degoes.net/articles/rotating-effects): where ZIO fixes two type-level channels (`R` and `E`), Kyo opens the same idea to an arbitrary set of effects. Several primitives map directly onto their ZIO counterparts, `Env` and `Abort` onto its environment and error channels, `Scope` and `Hub` onto their namesakes, which keeps the two libraries easy to compose in one program and lowers the barrier for ZIO developers adopting Kyo.

Kyo's asynchronous primitives take several aspects from [Twitter's util](https://github.com/twitter/util) and [Finagle](https://github.com/twitter/finagle), including features like async root compression, to provide stack safety, and support for cancellations (interruptions in Kyo).

Lastly, the name "Kyo" comes from a Buddhist term for "Sutra," a compiled teaching. The character's older sense is the *warp* threads of a loom, the lengthwise threads everything else is woven across, a fitting image for a library built on composition.

## License

See the [LICENSE.txt](LICENSE.txt) file for details.
