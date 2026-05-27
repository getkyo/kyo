<!-- doctest:setup
```scala
import AllowUnsafe.embrace.danger
```
-->
<img src="https://raw.githubusercontent.com/getkyo/kyo/master/kyo.png" width="200" alt="Kyo">

[![Build Status](https://github.com/getkyo/kyo/workflows/build/badge.svg)](https://github.com/getkyo/kyo/actions)
[![Discord](https://img.shields.io/discord/1087005439859904574)](https://discord.gg/KxxkBbW8bq)
[![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3)](https://search.maven.org/search?q=g:io.getkyo)
[![javadoc](https://javadoc.io/badge2/io.getkyo/kyo-core_3/javadoc.svg)](https://javadoc.io/doc/io.getkyo/kyo-core_3)

## What is Kyo

Kyo is a Scala 3 toolkit for building applications. One source tree compiles to JVM, JavaScript, and Scala Native. The library is built on algebraic effects with modular handlers, exposed through a compact infix type `A < S` ("A pending S"), where `S` is an open, type-level *set* of effects rather than a fixed `R, E, A` triple or a single concrete `IO`. An effect such as `Var[State]`, `Emit[Log]`, or `Abort[NotFound]` becomes another member of the set at the call site. Capabilities are not threaded through an `R` environment, hidden inside a typeclass dictionary, or stacked through monad transformers.

Kyo is pure monadic computation, programs-as-values. A value of type `A < S` is a pure description of a program (an immutable value that handlers interpret, not a deferred thunk you just run), composed with `map`, `flatMap`, and Scala 3's for-comprehensions the same way you compose `Option`, `Future`, `IO`, or `ZIO`. Algebraic effects do not replace the monadic style; they sit on top of it, letting the *effect row* be open and compositional while every program remains a pure value.

Algebraic effects with handlers separate the *declaration* of an effect from its *interpretation*. The theory traces back to Plotkin and Pretnar and to languages like Koka and Eff. The Kyo encoding represents the effect row as a type-level set rather than a typeclass dictionary or monad-transformer stack, so combining effects does not require stacking transformers or chaining capability traits. The practical difference: where typed-monad systems fix a closed set of channels and rely on transformers or capability stacks to combine them, Kyo lets the effect set be an *open* type-level union you assemble per call. A function returning `A < (Sync & Abort[E] & Env[Config] & Emit[Log])` lists the four capabilities it needs in its return type; the call site supplies handlers in any order.

For a deeper context, see [Suspension: the magic behind composability](https://www.youtube.com/watch?v=y3KiuFczOFE) (Lambda Days 2025), or jump to [Modules](#modules) for the ecosystem map.

> Looking for the published documentation site? See https://getkyo.io.

## The pending type

The pending type is the one piece of vocabulary you need before reading the rest of the docs. Kyo wraps every effectful computation in an opaque type `A < S` ("`A` pending `S`"), where `A` is the value the computation will produce and `S` is the *set* of effects it depends on. `S` is a Scala 3 intersection type built from individual effect markers like `Sync`, `Async`, `Abort[E]`, `Env[R]`, `Var[V]`, `Emit[V]`, `Scope`, and any custom effect you define. An effect is *added* to the row by mentioning it in the type; an effect is *removed* by applying its handler.

```scala doctest:scope=inherited
import kyo.*

// pure value lifted into a computation: row is empty
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

// Sync.Unsafe.evalOrThrow handles Sync, returning the value
val e: Result[String, Int] = Sync.Unsafe.evalOrThrow(d)
```

`.eval` is the universal "give me the value" call, but the compiler will refuse it until every effect in the row has been handled. A `< (Sync & Abort[E])` value cannot escape into plain Scala without `Sync` and `Abort` being explicitly handled first; the unhandled-effect set is part of the value's type. This is the single property that makes the rest of Kyo work: every signature spells out exactly which capabilities it consumes, and the call site decides where each one gets interpreted.

### Composing computations: `map`, `flatMap`, and `for`

`A < S` is a monad. You build larger computations out of smaller ones with `map` and `flatMap`, the same way you would with `Option`, `Future`, `IO`, or `ZIO`:

```scala
val parsed: Int < Sync =
    Sync.defer("42").map(_.toInt)

val chained: String < Sync =
    Sync.defer("42").flatMap: s =>
        Sync.defer(s.toInt).map(_ + 1).map(_.toString)
```

In Kyo, `map` and `flatMap` are the same method. Both have this signature:

<!-- doctest:expect=skipped
Abstract method declarations without bodies are not valid Scala 3 standalone code;
this block documents the API signature shape only.
```scala
extension [A, S](v: A < S)
    def map    [B, S2](f: A => B < S2): B < (S & S2)
    def flatMap[B, S2](f: A => B < S2): B < (S & S2)
```
-->

The function argument is always `A => B < S2`. A plain function `A => B` passes too because of **automatic lifting**: any value of type `A` lifts to `A < S` through a zero-cost type-level conversion (no runtime wrapping, no allocation), so `A => B` is already a valid `A => B < Any`. The bind never needs to combine a separate `pure` operation with `flatMap` the way classical monad encodings do.

Automatic lifting is the type-level dual of JS-Promise-style flattening, and it is what makes the encoding sound. `Promise.then` accepts either `A => B` or `A => Promise<B>` and at runtime *flattens* a returned `Promise<Promise<A>>` down to `Promise<A>` by running the inner promise dynamically. That flatten loses the ability to represent a real `Promise<Promise<A>>`, breaks the monad laws, and forces every consumer to reason about an implicit runtime collapse. Kyo's automatic lifting does the opposite: there is no runtime collapse, `B < S2 < S3` is a real distinct type. Use `Kyo.lift` when you explicitly want to nest a computation (`Kyo.lift(inner: B < S2): (B < S2) < S3`); use `.flatten` to collapse one. The one bind operator stays both honest and law-abiding.

Use whichever of `map` / `flatMap` reads better at the call site, or use a for-comprehension when the chain is long enough to benefit:

```scala
val program: String < Sync =
    for
        a <- Sync.defer("40")
        b <- Sync.defer(a.toInt + 2)
    yield b.toString
```

This is the same programs-as-values style you already know from cats-effect, ZIO, fs2, or scalaz. The difference is the row: instead of one `IO[A]` per computation, each `A < S` carries the open *set* of effects it uses, and handlers shrink that set toward `Any` until `.eval` can run.

Two more vocabulary items appear throughout the module READMEs:

- A *handler* is a method, conventionally named `<Effect>.run`, that takes a computation pending `Effect & S` and returns a computation pending `S` (the residual effects). `Abort.run`, `Env.run`, `Sync.Unsafe.evalOrThrow`, `Var.run` are all handlers.
- `.now` (provided by [kyo-direct](kyo-direct/README.md)) is the direct-style alternative to `.flatMap`; see [Idiomatic style](#idiomatic-style-monadic-by-default) below for when to reach for it.

See [`kyo-kernel/README.md`](kyo-kernel/README.md) for the full substrate: how to define a new effect, multi-shot continuations, effect widening, and the difference between `ArrowEffect` and `ContextEffect`.

### Composing many computations: sequential vs parallel

Once you have a collection of `A < S` values, two companions cover collection-style operations:

- **`Kyo.*` for sequential execution** (defined in kyo-prelude, works over any effect row). `Kyo.collectAll(xs)` turns a `Seq[A < S]` into a `Seq[A] < S`; `Kyo.foreach(xs)(f)` maps an effectful function over a collection; `Kyo.fill(n)(v)` runs the same effect `n` times; `Kyo.zip(a, b, c)` combines several effects into a tuple. None of these require `Sync` or `Async`.
- **`Async.*` for parallel execution** (defined in kyo-core, requires `Async` in the row). `Async.foreach(xs, concurrency = 8)(f)` fans out with a bounded pool; `Async.gather(...)` collects results from concurrent computations; `Async.zip(a, b)` runs two in parallel.

Mental model: reach for `Kyo.*` when sequential processing is sufficient, `Async.*` when concurrent execution would help. The Kyo companion lives in kyo-prelude alongside the core effects, not on `Sync` or `Async` themselves, because it works for any effect row.

## Idiomatic style: monadic by default

Kyo is programs-as-values: every `A < S` is a pure description that handlers interpret. The recommended way to write Kyo code is the monadic style introduced above: `map`, `flatMap`, and Scala 3's for-comprehensions over `A < S` values, with handler chains (`comp.handle(Abort.run, Env.run(config))`) discharging effects until `.eval` runs the program. The rest of the documentation, the worked examples, and the library code itself are written in this form because monadic composition needs no macro, no compiler-plugin syntax, and no rewrite rules: it is just the pure-monad style you already use elsewhere.

If you prefer direct-style code, [kyo-direct](kyo-direct/README.md) ships a `direct { ... }` macro that lets you write `val x = effect1.now; val y = effect2.now; x + y` and compiles it to the same `flatMap` chain. Direct style is an alternative, not the default. Reach for it when a function's suspension graph is dense enough that nested `flatMap` calls or a long for-comprehension would obscure the logic; stay with the monadic style everywhere else. Either way, the underlying values are still pure monadic computation: `direct { ... }` is sugar, not a different runtime.

With this much vocabulary in hand, the table below maps your current ecosystem onto Kyo's effect set.

## Coming from ZIO, Cats Effect, or plain Future

| You currently use                                            | Kyo translation                                                                                                                                                                                |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ZIO[R, E, A]`                                               | `A < S` where `S` carries each requirement (`Env[R1]`, `Abort[E]`, `Sync`, `Async`, ...) as a separate effect member                                                                           |
| `cats.effect.IO[A]`                                          | `A < (Sync & Async & Abort[Throwable])`, plus any extra effects                                                                                                                                |
| `scala.concurrent.Future[A]`                                 | `A < (Async & Abort[Throwable])`, referentially transparent, one auto-sized scheduler in place of ad-hoc `ExecutionContext`s                                                                   |
| ZIO `ZLayer`                                                 | Kyo `Layer` (see [kyo-prelude](kyo-prelude/README.md))                                                                                                                                         |
| ZIO `ZStream` / fs2 `Stream` / Akka Streams                  | Kyo `Stream` (pull-based, chunked, fused), see [kyo-prelude](kyo-prelude/README.md)                                                                                                            |
| ZIO Test                                                     | [`kyo-zio-test`](kyo-zio-test/README.md), or plain ScalaTest / MUnit                                                                                                                           |
| zio-schema / circe / jsoniter                                | [`kyo-schema`](kyo-schema/README.md)                                                                                                                                                           |
| `Resource[IO, A]` / `ZIO.scoped`                             | `Scope` effect with `Scope.acquireRelease`, see [kyo-core](kyo-core/README.md)                                                                                                                 |
| `Ref[IO, A]` / `zio.Ref`                                     | `AtomicRef`, `AtomicInt`, `AtomicLong`, `AtomicBoolean`                                                                                                                                        |
| `Deferred[IO, A]` / `zio.Promise`                            | `Promise`                                                                                                                                                                                      |
| `cats.effect.std.Queue` / `zio.Queue`                        | `Queue` with `Access` policy (MPMC / MPSC / SPMC / SPSC)                                                                                                                                       |
| tagless final / `Sync[F]` typeclasses (library authors)      | [`kyo-compat`](kyo-compat/README.md): write once, ship to ZIO + Cats Effect + Kyo + Future + Twitter Future + Ox via inline lowering, no typeclass dispatch                                    |
| http4s / tapir / Play / sttp                                 | [`kyo-http`](kyo-http/README.md)                                                                                                                                                               |

Three migration paths cover most adopters:

- **Adopt Kyo end-to-end**: start at [Foundation](#foundation) and [Application runtime](#application-runtime), then pick the [HTTP and schema](#http-and-schema) and [Specialty libraries](#specialty-libraries) you need.
- **Add Kyo inside an existing ZIO or Cats Effect app**: use [`kyo-zio`](kyo-zio/README.md) or [`kyo-cats`](kyo-cats/README.md) for bidirectional effect interop; optionally swap the runtime scheduler with [`kyo-scheduler-zio`](kyo-scheduler-zio/README.md) or [`kyo-scheduler-cats`](kyo-scheduler-cats/README.md).
- **Write a runtime-portable library**: use [`kyo-compat`](kyo-compat/README.md) to target ZIO, Cats Effect, Kyo, `scala.concurrent.Future`, Twitter Future, and Ox from one source tree.

ZIO migrants looking for fluent extension methods (`.race`, `.timeout`, `.retry`, `.provide`, etc.) over Kyo effects should also see [`kyo-combinators`](kyo-combinators/README.md), which is also where Cats Effect migrants find the cats-syntax-style operators (`*>`, `<*`, `>>`) and the `forAbort[E1]` failure-narrowing DSL. Migrants whose fs2 / ZStream code crosses into Kyo can route through the bidirectional `Stream` bridge in [`kyo-reactive-streams`](kyo-reactive-streams/README.md).

## Modules

Every module ships its own README. Open the linked README for the full surface, capabilities, callouts, and worked examples. The tables below name each module's identity in one sentence so you can pick the right one fast. Each identity cell names types and operations defined inside that module; expect unfamiliar names on first scan and treat the linked README as the source for what each one does. Platform columns mean published artifacts: ✅ = supported, ❌ = not built for that platform.

### Foundation

The substrate the rest of the ecosystem builds on. Most application code never depends on these directly; they ride in transitively through `kyo-core`.

| Module                                       | JVM | JS  | Native | Identity                                                                                                  |
| -------------------------------------------- | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
| [kyo-data](kyo-data/README.md)               | ✅   | ✅   | ✅      | Opaque-type values: `Maybe`, `Result`, `Chunk`, `Span`, `Duration`, `Instant`, `Schedule`, `TypeMap`     |
| [kyo-kernel](kyo-kernel/README.md)           | ✅   | ✅   | ✅      | Algebraic-effects substrate; defines `A < S`, `ArrowEffect`, `ContextEffect`, multi-shot continuations    |
| [kyo-prelude](kyo-prelude/README.md)         | ✅   | ✅   | ✅      | Strictly-pure effect layer: `Abort`, `Env`, `Var`, `Memo`, `Choice`, `Emit`, `Poll`, `Stream`, `Layer`    |

### Application runtime

The runtime layer most apps depend on directly. `kyo-core` is the standard-library equivalent for Kyo applications; `kyo-scheduler` is the work-stealing fiber pool under it.

| Module                                       | JVM | JS  | Native | Identity                                                                                                  |
| -------------------------------------------- | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
| [kyo-scheduler](kyo-scheduler/README.md)     | ✅   | ✅   | ✅      | Adaptive work-stealing pool with automatic blocking detection, admission control, and a `top` profiler    |
| [kyo-core](kyo-core/README.md)               | ✅   | ✅   | ✅      | I/O and concurrency: `Sync`, `Async`, `Scope`, `Fiber`, `Channel`, `Hub`, `Queue`, `Clock`, `Log`, `Path` |

### HTTP and schema

Web stack: HTTP client/server, derived JSON/Protobuf codecs, runtime config + feature flags, and a GraphQL surface.

| Module                                       | JVM | JS  | Native | Identity                                                                                                  |
| -------------------------------------------- | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
| [kyo-http](kyo-http/README.md)               | ✅   | ✅   | ✅      | HTTP/1.1 client and server (no HTTP/2 or WebSockets) with shared API across JVM/JS/Native, bidirectional OpenAPI |
| [kyo-schema](kyo-schema/README.md)           | ✅   | ✅   | ✅      | One `derives Schema` powers JSON, Protobuf, validation, lenses, diffs, builders, and structural conversion |
| [kyo-config](kyo-config/README.md)           | ✅   | ✅   | ✅      | Type-safe config + feature flags with a percentage-rollout DSL, optional kyo-http admin and live sync     |
| [kyo-caliban](kyo-caliban/README.md)         | ✅   | ❌   | ❌      | Caliban GraphQL mounted on kyo-http: typed Kyo effects in resolvers, WebSocket subscriptions              |

### Direct style and combinators

Two ways to write Kyo code more fluently. Pick `kyo-direct` for straight-line code with `.now` suspension points; pick `kyo-combinators` for ZIO-style fluent operators and the `forAbort[E1]` failure-narrowing DSL.

| Module                                         | JVM | JS  | Native | Identity                                                                                                  |
| ---------------------------------------------- | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
| [kyo-direct](kyo-direct/README.md)             | ✅   | ✅   | ✅      | Direct-style: `direct { val x = effect.now; ... }` desugars to the equivalent `flatMap` chain             |
| [kyo-combinators](kyo-combinators/README.md)   | ✅   | ✅   | ✅      | Sanctioned home for symbolic operators (`*>`, `<*>`, `&>`) and the `forAbort[E1]` narrowing DSL           |

### Concurrent primitives

Higher-level concurrency built on `kyo-core`'s fiber runtime. Reach for `kyo-actor` for typed message passing; `kyo-stm` for multi-cell atomicity; `kyo-offheap` for typed arrays outside the JVM heap.

| Module                                         | JVM | JS  | Native | Identity                                                                                                  |
| ---------------------------------------------- | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
| [kyo-actor](kyo-actor/README.md)               | ✅   | ✅   | ✅      | Typed actors over `Channel` and `Fiber`: `Subject[A]`, `ask`, supervision by composition                  |
| [kyo-stm](kyo-stm/README.md)                   | ✅   | ✅   | ✅      | STM with `TRef` / `TMap` / `TChunk` / `TTable`, including compile-checked `TTable.Indexed` queries        |
| [kyo-offheap](kyo-offheap/README.md)           | ✅   | ❌   | ✅      | Arena-scoped typed primitive arrays via JEP 442 (JVM 22+) and `calloc`/`free` (Native)                    |

### Specialty libraries

Domain-shaped modules: parsing, durable workflows, container management, low-latency messaging, browser automation.

| Module                                                 | JVM | JS  | Native | Identity                                                                                                  |
| ------------------------------------------------------ | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
| [kyo-parse](kyo-parse/README.md)                       | ✅   | ✅   | ✅      | Parser combinators in the effect row; supports dual-input-type parsers (e.g. `Parse[Char] & Parse[Int]`)  |
| [kyo-flow](kyo-flow/README.md)                         | ✅   | ✅   | ✅      | Durable workflow engine (Temporal/Cadence/ZIO-Flow space); value-replay execution, auto-generated REST     |
| [kyo-pod](kyo-pod/README.md)                           | ✅   | ✅   | ✅      | Docker and Podman client cross-compiled to JVM/JS/Native, streaming logs/stats, scope-managed cleanup     |
| [kyo-aeron](kyo-aeron/README.md)                       | ✅   | ❌   | ❌      | Typed pub/sub on Aeron: shared-memory IPC, UDP unicast, UDP multicast through one `Topic` API             |
| [kyo-playwright](kyo-playwright/README.md)             | ✅   | ❌   | ❌      | Browser automation over Microsoft Playwright; `readableContent` returns page main as Markdown             |

### Observability

In-process metrics and tracing registry, OTLP exporter that activates from `OTEL_EXPORTER_OTLP_ENDPOINT`, and two bridges from `kyo.Log` to the JDK or SLF4J logging APIs.

| Module                                                   | JVM | JS  | Native | Identity                                                                                                  |
| -------------------------------------------------------- | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
| [kyo-stats-registry](kyo-stats-registry/README.md)       | ✅   | ✅   | ✅      | Process-global registry; counters / gauges / counter-gauges / histograms; `TraceExporter` SPI             |
| [kyo-stats-otlp](kyo-stats-otlp/README.md)               | ✅   | ✅   | ✅      | Zero-code OTLP/HTTP+JSON exporter; W3C `traceparent` propagation auto-installed on kyo-http               |
| [kyo-logging-jpl](kyo-logging-jpl/README.md)             | ✅   | ❌   | ❌      | Bridge `kyo.Log` to `java.lang.System.Logger` (JEP 264, JDK 9+); zero third-party deps                    |
| [kyo-logging-slf4j](kyo-logging-slf4j/README.md)         | ✅   | ❌   | ❌      | Bridge `kyo.Log` to any SLF4J binding the host application already configures (Logback, Log4j 2, etc.)    |

### Interop with other effect stacks

Bidirectional bridges to neighbouring effect systems. `kyo-compat` is the special case: write a library once, ship it to six runtimes.

| Module                                                   | JVM | JS  | Native | Identity                                                                                                  |
| -------------------------------------------------------- | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
| [kyo-cats](kyo-cats/README.md)                           | ✅   | ✅   | ❌      | Two-method bridge between Kyo and `cats.effect.IO`, with bidirectional cancellation                       |
| [kyo-zio](kyo-zio/README.md)                             | ✅   | ✅   | ✅      | Three-object bridge: `ZIOs` (effects), `ZStreams` (streams), `ZLayers` (layers)                           |
| [kyo-zio-test](kyo-zio-test/README.md)                   | ✅   | ✅   | ✅      | Write `zio-test` `Spec`s whose bodies are Kyo computations (`KyoSpecDefault`, `KyoSpecAbstract`)          |
| [kyo-reactive-streams](kyo-reactive-streams/README.md)   | ✅   | ❌   | ❌      | Bidirectional bridge between Kyo `Stream` and `Publisher`/`Subscriber`; verified against the TCK          |
| [kyo-compat](kyo-compat/README.md)                       | ✅   | ✅*  | ✅*     | Library-author API: write once against `kyo.compat.*`, ship to ZIO, CE, Kyo, Future, Twitter Future, Ox   |

*kyo-compat platform support depends on the runtime binding (-kyo / -future / -zio: JVM+JS+Native; -ce: JVM+JS; -ox / -twitter-future: JVM).

### Scheduler embedding for other runtimes

Replace the host runtime's executors with Kyo's adaptive work-stealing scheduler. One pool covers compute and blocking work, with admission control and CPU-based blocking detection; no application code change beyond a one-line swap.

| Module                                                       | JVM | JS  | Native | Identity                                                                                                  |
| ------------------------------------------------------------ | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
| [kyo-scheduler-cats](kyo-scheduler-cats/README.md)           | ✅   | ❌   | ❌      | Drop-in `IORuntime` replacement: `extends KyoSchedulerIOApp` or `import KyoSchedulerIORuntime.global`     |
| [kyo-scheduler-finagle](kyo-scheduler-finagle/README.md)     | ✅   | ❌   | ❌      | Twitter Finagle: activated by `-Dcom.twitter.finagle.exp.scheduler=kyo` (Scala 2.13 only)                 |
| [kyo-scheduler-pekko](kyo-scheduler-pekko/README.md)         | ✅   | ❌   | ❌      | Pekko: one HOCON line replaces any dispatcher's executor                                                  |
| [kyo-scheduler-zio](kyo-scheduler-zio/README.md)             | ✅   | ❌   | ✅      | ZIO: `extends KyoSchedulerZIOAppDefault` or `KyoSchedulerZIORuntime.default` standalone                   |

### Tooling

CLI-parser bridge, runnable end-to-end programs, and the cross-runtime benchmark suite.

| Module                                       | JVM | JS  | Native | Identity                                                                                                  |
| -------------------------------------------- | --- | --- | ------ | --------------------------------------------------------------------------------------------------------- |
| [kyo-case-app](kyo-case-app/README.md)       | ✅   | ✅   | ✅      | Bridge case-app annotation-driven CLI parsing into a Kyo `run { options => ... }` entrypoint              |
| [kyo-examples](kyo-examples/README.md)       | ✅   | ❌   | ❌      | Two runnable programs: a ledger HTTP service and an N-queens solver (run with `sbt`)                      |
| [kyo-bench](kyo-bench/README.md)             | ✅   | ❌   | ❌      | JMH suite with side-by-side Kyo / Cats Effect / ZIO implementations for each scenario                     |

## Getting Started

Add the modules you want to `build.sbt`. Most application code starts with `kyo-core`, which pulls in `kyo-prelude`, `kyo-kernel`, `kyo-data`, and `kyo-scheduler` transitively (see the [Foundation](#foundation) and [Application runtime](#application-runtime) tables above for what each provides). Add integrations and specialty modules as needed.

```sbt doctest:expect=skipped
libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-core" % "<version>",
    "io.getkyo" %% "kyo-http" % "<version>"    // optional: HTTP client/server
)
```

Use `%%` for JVM and Scala Native, `%%%` for Scala.js cross-compilation. See the [Modules](#modules) tables above for platform support per module. Replace `<version>` with: ![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3)

A first read-through of [`kyo-core/README.md`](kyo-core/README.md) covers `Sync`, `Async`, `Scope`, `Fiber`, `KyoApp` (the entrypoint trait that discharges the effect row your `main` body produces), and the standard concurrent primitives. The natural follow-up for an application developer is [`kyo-http`](kyo-http/README.md). From there, drop into the rest of the module map above as your application grows. Worked end-to-end programs live in [`kyo-examples`](kyo-examples/README.md).

## Drop-in scheduler for ZIO, Cats Effect, Pekko, Finagle

`kyo-scheduler` is the auto-sized work-stealing pool that Kyo fibers run on. *Work-stealing* means idle workers pull tasks from busier workers' queues to keep all cores fed; *admission control* means the pool measures its own latency under load and rejects new submissions when it cannot meet a target rather than queuing them indefinitely; *CPU-based blocking detection* means the scheduler watches per-worker CPU time and reacts when a task stops making progress on-CPU, with no `blocking { }` annotation required at the call site. It also ships as an independent jar usable from any Scala 2 or Scala 3 codebase, with or without the rest of Kyo. Adopting Kyo's scheduler inside an existing ZIO, Cats Effect, Pekko, or Finagle service is a one-line swap; the host code keeps using its native effect APIs, and the scheduler underneath gains adaptive concurrency adjustment, CPU-based blocking detection, admission control, and a `top`-style runtime profiler.

See [`kyo-scheduler`](kyo-scheduler/README.md) for the standalone API, and one of the adapter READMEs for runtime-specific wiring:

- Cats Effect: [`kyo-scheduler-cats`](kyo-scheduler-cats/README.md)
- ZIO: [`kyo-scheduler-zio`](kyo-scheduler-zio/README.md)
- Pekko: [`kyo-scheduler-pekko`](kyo-scheduler-pekko/README.md)
- Finagle: [`kyo-scheduler-finagle`](kyo-scheduler-finagle/README.md) (Scala 2.13 only)

## IDE Support

Kyo uses advanced features from the latest Scala 3 versions that [IntelliJ IDEA may not fully support](https://github.com/getkyo/kyo/issues/1249). For a smoother development experience, we recommend using a [Metals-based](https://scalameta.org/metals/) IDE with the SBT BSP server, which offers better stability. Refer to the Metals [guide](https://scalameta.org/metals/docs/build-tools/sbt/#sbt-build-server) to switch from Bloop to SBT BSP.

## Required Compiler Flags

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

These flags help catch three common issues in Kyo applications:

1. **A pure expression does nothing in statement position**: Often suggests that a Kyo computation is being discarded and will never execute, though it can also occur with other pure expressions. Common fixes include using `map` to chain transformations or explicitly handling the result.

2. **Unused/Discarded non-Unit value**: Most commonly occurs when you pass a computation to a method that handles only some of the effects your computation requires. For example, passing a computation that needs both `Sync` and `Abort[Exception]` effects as a method parameter that only accepts `Sync` can trigger this warning. While this warning can appear in other scenarios (like ignoring any non-Unit value), in Kyo applications it typically signals that you're trying to use a computation in a context that doesn't support all of its required effects.

3. **Values cannot be compared with == or !=**: The strict equality flag ensures type-safe equality comparisons by requiring that compared types are compatible. This is particularly important for Kyo's opaque types like `Maybe`, where comparing values of different types could lead to inconsistent behavior. The flag helps catch these issues at compile-time, ensuring you only compare values that can be meaningfully compared. For example, you cannot accidentally compare a `Maybe[Int]` with an `Option[Int]` or a raw `Int`, preventing subtle bugs. To disable the check for a specific scope, introduce a permissive `CanEqual` given: `given [A, B]: CanEqual[A, B] = CanEqual.derived`

> Note: You may want to selectively disable these warnings in test code, where it's common to assert side effects without using their returned values: `Test / scalacOptions --= Seq(options, to, disable)`

## Talks

| Title                                                                                                                | Speaker(s)                 | Host                   | Date           | Resources                                                                                                |
| -------------------------------------------------------------------------------------------------------------------- | -------------------------- | ---------------------- | -------------- | -------------------------------------------------------------------------------------------------------- |
| [Suspension: the magic behind composability (or "The Kyo Monad")](https://www.youtube.com/watch?v=y3KiuFczOFE)       | Flavio Brasil              | Lambda Days            | June, 2025     | [Slides](https://speakerdeck.com/fwbrasil/suspension-the-magic-behind-composability-or-the-kyo-monad)    |
| [An Algebra of Thoughts: When Kyo effects meet LLMs](https://www.youtube.com/watch?v=KIjtXM5dlgY)                    | Flavio Brasil              | Func Prog Sweden       | May, 2025      | [Slides](https://www.canva.com/design/DAGoBBQ3oJw/vFGuHA0z_ZFtZgbJQbQHXw/view)                           |
| [Redefining Stream Composition with Algebraic Effects](https://www.youtube.com/watch?v=WcYKTyQwEA0)                  | Adam Hearn                 | LambdaConf             | May, 2025      |                                                                                                          |
| [Kyo: A New Approach to Functional Effects in Scala](https://www.youtube.com/watch?v=uA2_TWP5WF4)                    | Flavio Brasil & Adam Hearn | Scala for Fun & Profit | February, 2025 |                                                                                                          |
| [The Actor Model Beyond Akka With Kyo](https://www.youtube.com/watch?v=VU31k3lQ8yU)                                  | Damian Reeves              | Functional Scala       | December, 2024 |                                                                                                          |
| [Building Robust Applications with Kyo: A Hands on Introduction](https://www.youtube.com/watch?v=QW8mAJr0Wso)        | Adam Hearn                 | ScalaIO                | November, 2024 | [Workshop + Slides](https://github.com/hearnadam/kyo-workshop)                                           |
| [Comparing Approaches to Structured Concurrency](https://www.youtube.com/watch?v=g6dyLhAublQ)                        | James Ward & Adam Hearn    | LambdaConf             | May, 2024      |                                                                                                          |
| [Algebraic Effects from Scratch](https://www.youtube.com/watch?v=qPvPdRbTF-E)                                        | Kit Langton                | Func Prog Sweden       | April, 2024    |                                                                                                          |
| [Releasing Kyo: When Performance Meets Elegance In Scala](https://www.youtube.com/watch?v=FXkYKQRC9LI)               | Flavio Brasil              | Functional Scala       | December, 2023 |                                                                                                          |

A podcast interview that doubles as a one-hour live-coded tour:

<a href="http://www.youtube.com/watch?v=uA2_TWP5WF4" title="Kyo: A New Approach to Functional Effects in Scala">
    <img src="https://img.youtube.com/vi/uA2_TWP5WF4/maxresdefault.jpg" alt="Kyo: A New Approach to Functional Effects in Scala" width="500" height="300">
</a>

## Contributing

- [CONTRIBUTING.md](CONTRIBUTING.md) covers the contribution workflow, the API-design conventions, and the per-module README structure.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) covers community norms.
- [AGENTS.md](AGENTS.md) is the entry point for agent-driven contributions.
- [Discord](https://discord.gg/KxxkBbW8bq) is the fastest way to ask a question.

## Acknowledgements

Kyo's development was originally inspired by the paper ["Do Be Do Be Do"](https://arxiv.org/pdf/1611.09259.pdf) and its implementation in the [Unison](https://www.unison-lang.org/learn/language-reference/abilities-and-ability-handlers/) programming language. Kyo's design evolved from using interface-based effects to suspending concrete values associated with specific effects, making it more efficient when executed on the JVM.

Additionally, Kyo draws inspiration from [ZIO](https://zio.dev/) in various aspects. The core mechanism for algebraic effects can be seen as a generalization of ZIO's [effect rotation](https://degoes.net/articles/rotating-effects), and many of Kyo's effects are directly influenced by ZIO's mature set of primitives. For instance, `Env` and `Abort` correspond to ZIO's effect channels, `Scope` functions similarly to `Scope`, and `Hub` was introduced based on ZIO. This design keeps a focus on easy composition when using Kyo and ZIO in the same program. It also lowers the barrier for developers familiar with ZIO to adopt Kyo.

Kyo's asynchronous primitives take several aspects from [Twitter's util](https://github.com/twitter/util) and [Finagle](https://github.com/twitter/finagle), including features like async root compression, to provide stack safety, and support for cancellations (interruptions in Kyo).

Lastly, the name "Kyo" is derived from the last character of Nam-myoho-renge-kyo, the mantra practiced in [SGI Buddhism](https://www.sokaglobal.org/). It literally translates to "Sutra," referring to a compiled teaching of Shakyamuni Buddha, and is also interpreted as the "threads" that weave the fundamental fabric of life's reality.

## License

See the [LICENSE.txt](LICENSE.txt) file for details.
