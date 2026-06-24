# kyo-aeron

`Topic` is a typed publish-subscribe channel built on the Aeron transport. You name a destination with an Aeron URI (`aeron:ipc` for shared-memory IPC on one machine, `aeron:udp?endpoint=...` for unicast or multicast across machines), pick a message type with a `Schema` (derive it with `derives Schema`), and call `Topic.publish` or `Topic.stream`. Messages travel as MsgPack-encoded envelopes, so the producer and the consumer must agree on the exact Scala type. The transport handles fragmentation, reassembly, and flow control; you handle the program shape.

The mental model is "one Aeron stream per exact Scala type." `Topic` derives the stream ID from the message type's `Tag` hash, so `Topic.stream[Message]` and `Topic.publish[Message]` only see each other. There is no subtype polymorphism, and `Topic.stream[Base]` will not receive `Derived` messages even when `Derived <: Base`. A `Topic.run` handler stands up an embedded Aeron media driver and discharges the effect; everything inside the handler shares that driver. The same `Topic.run`/`publish`/`stream` API compiles and runs unchanged on JVM, Scala Native, and Scala.js, with only the I/O backend differing underneath.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L))

Topic.run {
    Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
}
// : Unit < (Async & Abort[TopicBackpressureException | TopicPublishException | TopicTransportException])
```

`derives Schema` wires the codec, `Topic.run` owns the embedded driver, and `Topic.publish` streams values onto the named URI.

## Getting started

The minimum useful program is a publisher and a subscriber on the same URI with the same exact message type. The subscriber runs on its own fiber so the publisher can run concurrently in the same handler.

### Deriving `Schema`

A message type's only requirement is a `Schema[A]` instance from kyo-schema, derivable with `derives Schema`. The same instance serves both publish and subscribe of that type, and payloads are MsgPack-encoded on the wire.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
```

`Schema` is the only typeclass requirement on a message type. Standard types that kyo-schema already provides a `Schema` for work without an explicit derive.

### Running a `Topic` handler

`Topic.run` stands up an Aeron media driver and client, runs the inner computation, and closes both on exit. The zero-arg overload is the path you take when you want a working topic; the inner computation can publish, subscribe, and fork freely.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

Topic.run {
    Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
}
// : Unit < (Async & Abort[TopicBackpressureException | TopicPublishException | TopicTransportException])
```

The `Topic` effect is discharged by `run`; what remains in the row is `Async` (because Aeron polling is suspended on the fiber scheduler) and the `Abort` channels each call carries.

The zero-arg `Topic.run(v)` carries no `Abort` for startup: an embedded-startup defect (a temp dir that cannot be allocated, or an embedded driver that fails to launch, e.g. a missing `--add-opens` below) surfaces as a panic, not a recoverable abort, because it is an environment defect rather than a per-call condition. The external overloads (`Topic.run(aeronDir)` and `AeronClient.connect`) instead surface a missing external driver as a typed `Abort[TopicTransportFailedException]`, because that connect failure is a per-call recoverable condition.

> **Note:** On the JVM, any build that hosts an embedded Aeron driver must set `fork := true` and pass four `--add-opens` flags, because Aeron's off-heap log buffers reach into JDK internals:
> ```
> fork := true
> javaOptions ++= Seq(
>   "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
>   "--add-opens=java.base/java.lang=ALL-UNNAMED",
>   "--add-opens=java.base/java.nio=ALL-UNNAMED",
>   "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
> )
> ```
> Without these, `Topic.run` fails when it launches the embedded media driver. Scala Native and Scala.js run an embedded C media driver through kyo-ffi and need no `--add-opens`.

### Publishing

`Topic.publish` takes a URI and a `Stream[A, S]` of messages. Each chunk that flows through the input stream becomes one Aeron message frame; the publisher encodes the chunk as a MsgPack `Envelope` (kyo-schema) and offers it to the publication.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema

val ticks = Stream.init(
    Seq(
        Tick("AAPL", 19023, 1L),
        Tick("AAPL", 19045, 2L)
    ),
    4096
)

Topic.run {
    Topic.publish[Tick]("aeron:ipc")(ticks)
}
```

### Subscribing

`Topic.stream` returns a `Stream[A, Topic & Abort[TopicBackpressureException | TopicTransportException] & Async]` that emits chunks of messages as they arrive. Each Aeron frame carries a whole chunk, so consumers observe chunk-granularity arrival rather than message-by-message.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema

Topic.run {
    Topic.stream[Tick]("aeron:ipc").take(2).run
}
// : Chunk[Tick] < (Async & Abort[TopicBackpressureException | TopicTransportException])
```

> **Note:** `Topic.stream` emits in chunks because the publisher commits one chunk per Aeron frame. A consumer doing `.take(n)` counts messages, not chunks; the underlying stream stays chunk-shaped end to end.

### Round-trip on one fiber pair

Run both sides under one `Topic.run` block. The subscriber must be started before the publisher writes, so a `Latch` synchronizes the handoff.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema

val messages = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L))

Topic.run {
    for
        started <- Latch.init(1)
        fiber <- Fiber.initUnscoped(
            started.release.andThen(
                Topic.stream[Tick]("aeron:ipc").take(messages.size).run
            )
        )
        _ <- started.await
        _ <- Fiber.initUnscoped(
            Topic.publish[Tick]("aeron:ipc")(Stream.init(messages, 4096))
        )
        received <- fiber.get
    yield received
}
```

## Aeron URIs and transports

The URI you pass to `publish` and `stream` selects the wire format. The API surface is the same across all three; only the URI changes.

### Shared-memory IPC

`aeron:ipc` uses Aeron's IPC ring buffer between processes (or between threads in the same process). It has the lowest latency Aeron offers and no network setup. Use it for in-process fan-out or for cooperating processes on one host.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

Topic.run {
    Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
}
```

### UDP unicast

`aeron:udp?endpoint=host:port` sets up reliable UDP between two endpoints. Subscribers bind the same endpoint the publisher writes to; the URI must match exactly on both sides.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

val uri = "aeron:udp?endpoint=127.0.0.1:40123"

Topic.run {
    Topic.publish[Tick](uri)(Stream.init(ticks, 4096))
}

Topic.run {
    Topic.stream[Tick](uri).take(ticks.size).run
}
```

### UDP multicast

`aeron:udp?endpoint=group:port|interface=iface` joins a multicast group. Many subscribers receive the same stream off one publisher write, useful for market-data style fan-out across hosts.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

val uri = "aeron:udp?endpoint=224.1.1.1:40123|interface=192.168.1.1"

Topic.run {
    Topic.publish[Tick](uri)(Stream.init(ticks, 4096))
}
```

> **Note:** Aeron URIs accept many more options than these three families illustrate (term length, MTU, congestion control, ...). Anything Aeron accepts on `addPublication`/`addSubscription` is valid; see <https://aeron.io/> for the full grammar.

## Type-keyed streams

The stream ID Aeron sees is derived from the message type's `Tag.hash`. Two consequences follow, and both surprise readers who think of pub-sub as subtype-routed.

### Distinct types coexist on one URI

You can publish `Tick`s and `Trade`s to the same `aeron:ipc` URI from separate calls; the two streams are independent because their type tags hash to different stream IDs.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
case class Trade(symbol: String, qty: Int, priceCents: Long) derives CanEqual, Schema

val ticks  = Seq(Tick("AAPL", 19023, 1L))
val trades = Seq(Trade("AAPL", 100, 19023L))

Topic.run {
    for
        started <- Latch.init(2)
        tickFiber <- Fiber.initUnscoped(
            started.release.andThen(Topic.stream[Tick]("aeron:ipc").take(ticks.size).run)
        )
        tradeFiber <- Fiber.initUnscoped(
            started.release.andThen(Topic.stream[Trade]("aeron:ipc").take(trades.size).run)
        )
        _           <- started.await
        _           <- Fiber.initUnscoped(Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096)))
        _           <- Fiber.initUnscoped(Topic.publish[Trade]("aeron:ipc")(Stream.init(trades, 4096)))
        tickResult  <- tickFiber.get
        tradeResult <- tradeFiber.get
    yield (tickResult, tradeResult)
}
```

The `Tick` consumer sees only `Tick`s and the `Trade` consumer sees only `Trade`s, on the same URI, with no cross-talk.

### No subtype polymorphism

A `sealed trait` with subtype variants does NOT give you "subscribe to the base, receive every variant." The variant's `Tag` is a different `Tag` from the trait's `Tag`, so the stream IDs are different.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
sealed trait Event derives Schema
case class TickEvent(t: Tick) extends Event derives CanEqual, Schema

val failSchedule = Schedule.fixed(1.millis).take(3)

// Subscriber asks for Event but publisher writes TickEvent;
// the stream ids do not match, so the subscriber sees nothing
// and eventually fails with TopicBackpressureExhaustedException.
Topic.run {
    for
        fiber <- Fiber.initUnscoped(
            Topic.stream[Event]("aeron:ipc", failSchedule).take(1).run
        )
        result <- Abort.run(
            Topic.publish[TickEvent]("aeron:ipc", failSchedule)(
                Stream.init(Seq(TickEvent(Tick("AAPL", 1, 1L))), 4096)
            )
        )
        outcome <- fiber.getResult
    yield (result.isFailure, outcome.isFailure)
}
// : (true, true)
```

If you want a union, publish each variant under its concrete type and let the subscribers pick the variants they care about. Or define a wrapper case class that erases the variance: `case class AnyEvent(payload: String) derives Schema` and serialize the union into `payload`.

> **Caution:** Stream IDs are hashes of `Tag.show`, so a hash collision between two unrelated types is theoretically possible. On receipt, the subscriber compares the full type-tag string from the wire to its own; a mismatch raises `Abort.panic` (not a normal `Abort` failure) with the expected and actual tags. A panic here means "you wired the wrong types together," not "the network blipped."

## Backpressure and retries

The Aeron transport reports back-pressure as a negative offer return code on the publish path and as "no fragments read" on the subscribe path. `Topic` routes transient conditions through a `Schedule`-driven retry before letting the failure escape as a typed `TopicException`.

### Transient vs terminal: the TopicException subcategories

`TopicException` is organized into three `sealed abstract` subcategories by failure mode:

- `TopicBackpressureException` (transient, retry-absorbed): Aeron is busy, the peer is briefly absent, or an admin action is in progress. The retry schedule absorbs these; they surface as `TopicBackpressureExhaustedException` only after the schedule exhausts. Both `publish` and `stream` carry `Abort[TopicBackpressureException]` in the row.
- `TopicPublishException` (terminal, publish-side offer path): publication or client closed (`TopicPublicationClosedException`), term-buffer position limit hit (`TopicMaxPositionExceededException`), or message too large (`TopicMessageTooLargeException`). Reachable on `publish` only; `stream` does not carry this arm.
- `TopicTransportException` (terminal, add/lifecycle path): registration rejected (`TopicRegistrationFailedException`), bounded add-loop timed out (`TopicAddTimeoutException`), or fatal conductor error (`TopicTransportFailedException`). Reachable on both `publish` and `stream`.

```scala
// publish row: Unit < (Topic & S & Abort[TopicBackpressureException | TopicPublishException | TopicTransportException] & Async)
// stream row:  Stream[A, Topic & Abort[TopicBackpressureException | TopicTransportException] & Async]
```

The two rows differ intentionally: `TopicPublishException` is unreachable on the subscribe path (it covers offer-side codes that only a publication returns), so `stream` does not carry it.

### `Topic.defaultRetrySchedule`

Both `publish` and `stream` accept a `retrySchedule: Schedule = defaultRetrySchedule`. The default is a linear 10-millisecond backoff capped at one second with 20% jitter:

```scala
val defaultRetrySchedule = Schedule.linear(10.millis).min(Schedule.fixed(1.second)).jitter(0.2)
```

This default makes a publisher willing to wait through reasonably long subscriber stalls without giving up, and makes a subscriber poll patiently against an idle channel. When the default is in effect, a "quiet" channel does NOT fail; the retry keeps trying.

### Choosing a fail-fast schedule

When you want missing-peer scenarios to surface as failures rather than indefinite waits, pass a bounded schedule. Three retries at 1 millisecond is the pattern the test suite uses:

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema

val failSchedule = Schedule.fixed(1.millis).take(3)

// Subscriber with no publisher: fails after 3 retries instead of hanging.
Topic.run {
    for
        fiber <- Fiber.initUnscoped(
            Topic.stream[Tick]("aeron:ipc", failSchedule).take(1).run
        )
        result <- fiber.getResult
    yield result.isFailure
}
// : true
```

The same schedule works on the publish side. A publisher with no subscriber, with a fail-fast schedule, surfaces a `TopicBackpressureExhaustedException` failure instead of stalling:

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema

val failSchedule = Schedule.fixed(1.millis).take(3)

Topic.run {
    Abort.run(
        Topic.publish[Tick]("aeron:ipc", failSchedule)(Stream.init(Seq(Tick("AAPL", 1, 1L)), 4096))
    )
}
// : Result.Failure(...)
```

> **Note:** The retry schedule applies to BOTH `publish` and `stream`. The same call passes the same schedule to both endpoints in the same handler when you want symmetric behavior; pass different schedules per endpoint when their tolerance differs (e.g. a publisher that should fail fast feeding a subscriber that should wait).

### Recovering from backpressure

`TopicBackpressureException` is the only retryable subcategory. Match on it to distinguish a transient stall from a terminal transport error:

```scala
import kyo.*

Abort.recover[TopicBackpressureException] { _ =>
    // transient: the retry schedule exhausted; handle or re-issue
    ()
} {
    Topic.publish[Int]("aeron:ipc")(Stream.init(Seq(1, 2, 3)))
}
```

Terminal errors (`TopicPublishException`, `TopicTransportException`) require a fresh `Topic.run` scope to recover; no retry of the same publication or subscription will succeed.

## Driver lifecycle

`Topic.run(v)` launches an embedded Aeron media driver and discharges the `Topic` effect
for the inner computation. Two additional overloads connect to a driver that already exists:
`Topic.run(aeronDir: Path)(v)` connects a fresh client to an external driver at `aeronDir`
and closes only the client on exit; `AeronClient.connect(aeronDir)` acquires a
`Scope`-managed client that multiple `Topic.run(client)` blocks can share.

### Embedded driver (the default)

`Topic.run(v)` (no driver argument) starts a fresh embedded driver with a unique per-instance
directory, opens a client against it, and closes both on exit. Use this when the program is
the only Aeron user in the process and you do not need to share state across `Topic.run`
blocks.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

Topic.run {
    Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
}
```

Each `Topic.run(v)` call allocates a unique temporary directory for its driver instance, so
two concurrent `Topic.run(v)` calls in the same process each get an isolated driver; they
can communicate only if they publish/subscribe on the same Aeron URI, not because they share
the same driver directory.

### External driver: `Topic.run(aeronDir)`

`Topic.run(aeronDir)(v)` connects a fresh client to an external driver already running at
`aeronDir`, runs `v`, then closes only the client. The driver is not started or stopped;
the caller owns the driver lifecycle. Use this when one driver outlives multiple
`Topic.run` blocks or when a separate process manages the driver.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

def publish(aeronDir: Path): Unit < (Async & Abort[TopicBackpressureException | TopicPublishException | TopicTransportException]) =
    Topic.run(aeronDir) {
        Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
    }
```

Connecting to a directory where no driver is running aborts with `TopicTransportFailedException`
(a `TopicTransportException` leaf), eagerly and in-band after the driver timeout on every
platform.

### Shared client: `AeronClient`

`AeronClient.connect(aeronDir)` acquires a `Scope`-managed handle to a connected Aeron
client. Multiple `Topic.run(client)` blocks can borrow the same client without closing it;
the `Scope` that produced the `AeronClient` closes it exactly once on scope exit.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

def publishWithSharedClient(client: AeronClient)
    : Unit < (Async & Abort[TopicBackpressureException | TopicPublishException | TopicTransportException]) =
    Topic.run(client) {
        Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
    }
```

`AeronClient.connect` carries `Scope & Async & Abort[TopicTransportFailedException]`; a failed connect
aborts with `TopicTransportFailedException` the same way as `Topic.run(aeronDir)` (both consume the
one shared connect primitive in `AeronPlatform.external`). `Topic.run(client)` carries only
`Async & S` because the client is already connected.

### Choosing an overload

- `Topic.run(v)`: the program is the sole Aeron user; simplest setup; no external driver needed.
- `Topic.run(aeronDir)(v)`: one long-lived driver serves multiple `Topic.run` blocks or processes.
- `AeronClient` + `Topic.run(client)`: amortize the connect cost across many short `Topic.run` blocks.

All three overloads are uniform across JVM, Scala Native, and Scala.js.

## Concurrency

`Topic` is `opaque type Topic <: Env[AeronTransport]`, so it composes wherever `Env` does. The row carries the `AeronTransport` capability, a cross-platform transport handle rather than a JVM `Aeron` value, on every computation between `Topic.run` and a `Topic.publish`/`Topic.stream` call, and forks with `Fiber.initUnscoped` carry that row across the fiber boundary.

> **Note:** Crossing a fiber boundary requires a `given Isolate[Topic, ...]`. One lives in `Topic`'s companion (`Topic.isolate`), so it is in implicit scope automatically and `Fiber.initUnscoped(...)` carries the `Topic` row into the child fiber with no explicit `using`.

### Why `Topic` cannot be constructed directly

`Topic` is an opaque type with no public constructor; the only way to get a computation tagged with it is to be inside a `Topic.run` block. This is why the examples in this document always nest `publish`/`stream` inside `Topic.run` rather than constructing a `Topic` value and passing it around: there is no value to pass.

If you need to share publishing or subscribing logic across modules, write methods that return `Unit < (Topic & Async & Abort[...])` and let the call sites discharge `Topic` with `Topic.run`. The effect row, not a `Topic` value, is what travels.

## Cross-platform backends

The same `Topic` API (`run`, `publish[A: Schema]`, `stream[A: Schema]`, `AeronClient.connect`) compiles and runs identically on JVM, Scala Native, and Scala.js. Only the transport underneath differs. On the JVM it uses the pure-Java `io.aeron` client and launches an embedded `MediaDriver`. On Scala Native and Scala.js it uses Aeron's C client and an embedded C media driver, bound through kyo-ffi and statically linked from libaeron built for the target. Native and JS therefore require libaeron staged for the target at build time. The external-driver path (`Topic.run(aeronDir)` and `AeronClient.connect(aeronDir)`) works on all three platforms with the same `kyo.Path` type and the same `Abort[TopicTransportFailedException]` failure channel. The wire format, MsgPack envelopes, is byte-identical across platforms, so a JVM publisher and a Native subscriber on the same Aeron URI interoperate.

## Putting it together

This example combines publish and subscribe in one `Topic.run` block that publishes a batch of ticks and subscribes to them on the same URI, with a `Latch` coordinating startup so the subscriber is ready before the publisher writes.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Schema
case class Trade(symbol: String, qty: Int, priceCents: Long) derives CanEqual, Schema

val ticks = Seq(
    Tick("AAPL", 19023, 1L),
    Tick("AAPL", 19045, 2L),
    Tick("MSFT", 41210, 3L)
)

// One handler discharges Topic for everything inside; the embedded driver
// is created on entry and closed on exit.
Topic.run {
    for
        started <- Latch.init(1)
        // Subscribe on shared-memory IPC; type Tick keys the Aeron stream id
        consumer <- Fiber.initUnscoped(
            started.release.andThen(
                Topic.stream[Tick]("aeron:ipc").take(ticks.size).run
            )
        )
        _ <- started.await
        // Publish onto the same URI; same exact type required on both ends
        _ <- Fiber.initUnscoped(
            Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
        )
        received <- consumer.get
    yield received
}
```
