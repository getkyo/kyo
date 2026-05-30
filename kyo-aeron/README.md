# kyo-aeron

`Topic` is a typed publish-subscribe channel built on the Aeron transport. You name a destination with an Aeron URI (`aeron:ipc` for shared-memory IPC on one machine, `aeron:udp?endpoint=...` for unicast or multicast across machines), pick a message type that has an upickle `ReadWriter` instance, and call `Topic.publish` or `Topic.stream`. The transport handles fragmentation, reassembly, and flow control; you handle the program shape.

The mental model is "one Aeron stream per exact Scala type." `Topic` derives the stream ID from the message type's `Tag` hash, so `Topic.stream[Message]` and `Topic.publish[Message]` only see each other. There is no subtype polymorphism, and `Topic.stream[Base]` will not receive `Derived` messages even when `Derived <: Base`. A `Topic.run` handler stands up an embedded Aeron `MediaDriver` (or wraps one you supply) and discharges the effect; everything inside the handler shares that driver.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L))

Topic.run {
    Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
}
// : Unit < (Async & Abort[Closed | Backpressured])
```

`derives Topic.AsMessage` wires the codec, `Topic.run` owns the embedded driver, and `Topic.publish` streams values onto the named URI. The rest of this document walks each capability one cluster at a time.

## Getting started

The minimum useful program is a publisher and a subscriber on the same URI with the same exact message type. The subscriber runs on its own fiber so the publisher can run concurrently in the same handler.

### Deriving `Topic.AsMessage`

`Topic.AsMessage[A]` is a type alias for upickle's `ReadWriter[A]`. Any case class can derive it directly, and the same derive enables publish and subscribe of that type.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
```

`Topic.AsMessage` is the only typeclass requirement on a message type. Standard library types that upickle handles out of the box (`String`, `Int`, `Long`, `Double`, `List`, `Map`, ...) work without an explicit derive.

> **Note:** `derives Topic.AsMessage` desugars to `derives ReadWriter`. If the type already has a `ReadWriter` in scope from upickle, no further derive is needed.

### Running a `Topic` handler

`Topic.run` stands up an Aeron `MediaDriver` and an `Aeron` client, runs the inner computation, and closes both on exit. The zero-arg overload is the path you take when you just want a working topic; the inner computation can publish, subscribe, and fork freely.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

Topic.run {
    Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
}
// : Unit < (Async & Abort[Closed | Backpressured])
```

The `Topic` effect is discharged by `run`; what remains in the row is `Async` (because Aeron polling is suspended on the fiber scheduler) and the `Abort` channels each call carries.

> **Note:** Any build that hosts an embedded Aeron driver must set `fork := true` and pass four `--add-opens` flags, because Aeron's off-heap log buffers reach into JDK internals:
> ```
> fork := true
> javaOptions ++= Seq(
>   "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
>   "--add-opens=java.base/java.lang=ALL-UNNAMED",
>   "--add-opens=java.base/java.nio=ALL-UNNAMED",
>   "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
> )
> ```
> Without these, `Topic.run` fails when it launches the embedded `MediaDriver`.

### Publishing

`Topic.publish` takes a URI and a `Stream[A, S]` of messages. Each chunk that flows through the input stream becomes one Aeron message frame; the publisher serializes the chunk with upickle binary, claims buffer space, and commits.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage

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

`Topic.stream` returns a `Stream[A, Topic & Abort[Backpressured] & Async]` that emits chunks of messages as they arrive. Each Aeron frame carries a whole chunk, so consumers observe chunk-granularity arrival rather than message-by-message.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage

Topic.run {
    Topic.stream[Tick]("aeron:ipc").take(2).run
}
// : Chunk[Tick] < (Async & Abort[Backpressured])
```

> **Note:** `Topic.stream` emits in chunks because the publisher commits one chunk per Aeron frame. A consumer doing `.take(n)` counts messages, not chunks; the underlying stream stays chunk-shaped end to end.

### Round-trip on one fiber pair

Run both sides under one `Topic.run` block. The subscriber must be started before the publisher writes, so a `Latch` synchronizes the handoff.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage

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

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

Topic.run {
    Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
}
```

### UDP unicast

`aeron:udp?endpoint=host:port` sets up reliable UDP between two endpoints. Subscribers bind the same endpoint the publisher writes to; the URI must match exactly on both sides.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
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

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
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

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
case class Trade(symbol: String, qty: Int, priceCents: Long) derives CanEqual, Topic.AsMessage

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

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
sealed trait Event derives Topic.AsMessage
case class TickEvent(t: Tick) extends Event derives CanEqual, Topic.AsMessage

val failSchedule = Schedule.fixed(1.millis).take(3)

// Subscriber asks for Event but publisher writes TickEvent;
// the stream ids do not match, so the subscriber sees nothing
// and eventually fails with Backpressured.
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

If you want a union, publish each variant under its concrete type and let the subscribers pick the variants they care about. Or define a wrapper case class that erases the variance: `case class AnyEvent(payload: String) derives Topic.AsMessage` and serialize the union into `payload`.

> **Caution:** Stream IDs are hashes of `Tag.show`, so a hash collision between two unrelated types is theoretically possible. On receipt, the subscriber compares the full type-tag string from the wire to its own; a mismatch raises `Abort.panic` (not a normal `Abort` failure) with the expected and actual tags. A panic here means "you wired the wrong types together," not "the network blipped."

## Backpressure and retries

The Aeron transport reports back-pressure as a return code from `tryClaim` on the publish path and as "no fragments read" on the subscribe path. `Topic` surfaces both through one `Abort[Backpressured]` channel, and routes that channel through a `Schedule`-driven retry before letting the failure escape.

### `Topic.Backpressured`

`Backpressured` is a `KyoException` raised when Aeron cannot accept a write right now (publisher) or has nothing to read right now (subscriber). Both publish and subscribe carry `Abort[Backpressured]` in the row, so the retry knob applies symmetrically.

```scala
// Publisher row:    Unit         < (Topic & S & Abort[Closed | Backpressured] & Async)
// Subscriber row:   Stream[A, Topic & Abort[Backpressured] & Async]
```

### `Topic.defaultRetrySchedule`

Both `publish` and `stream` accept a `retrySchedule: Schedule = defaultRetrySchedule`. The default is a linear 10-millisecond backoff capped at one second with 20% jitter:

```scala
val defaultRetrySchedule = Schedule.linear(10.millis).min(Schedule.fixed(1.second)).jitter(0.2)
```

This default makes a publisher willing to wait through reasonably long subscriber stalls without giving up, and makes a subscriber poll patiently against an idle channel. When the default is in effect, a "quiet" channel does NOT fail; the retry just keeps trying.

### Choosing a fail-fast schedule

When you want missing-peer scenarios to surface as failures rather than indefinite waits, pass a bounded schedule. Three retries at 1 millisecond is the pattern the test suite uses:

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage

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

The same schedule works on the publish side. A publisher with no subscriber, with a fail-fast schedule, surfaces a failure (`Backpressured` exhausted, or `Closed("Not connected")`) instead of stalling:

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage

val failSchedule = Schedule.fixed(1.millis).take(3)

Topic.run {
    Abort.run(
        Topic.publish[Tick]("aeron:ipc", failSchedule)(Stream.init(Seq(Tick("AAPL", 1, 1L)), 4096))
    )
}
// : Result.Failure(...)
```

> **Note:** The retry schedule applies to BOTH `publish` and `stream`. The same call passes the same schedule to both endpoints in the same handler when you want symmetric behavior; pass different schedules per endpoint when their tolerance differs (e.g. a publisher that should fail fast feeding a subscriber that should wait).

### `Backpressured` vs `Closed`

`Backpressured` is transient: Aeron is busy or the peer is briefly absent, and a retry might succeed. `Closed` is terminal: the publication or subscription is shut, the peer says `NOT_CONNECTED`, or an admin action invalidated the session. The publish path raises `Closed` directly without retry; the subscribe path raises `Backpressured` for the not-connected case as well, leaning on the schedule to decide when "not yet connected" becomes "not coming."

```scala
// publish row:  Abort[Closed | Backpressured]
// stream row:   Abort[Backpressured]
```

If you want a publisher to treat `NOT_CONNECTED` like a retryable backpressure event, wrap the publish in `Abort.recover[Closed]` and re-issue; the API does not collapse the two by default.

## Driver lifecycle

Aeron has two long-lived resources: a `MediaDriver` (the IPC daemon, one per host) and an `Aeron` client (a connection to one media driver). `Topic.run` has three overloads, one per "who owns each resource" arrangement.

### Embedded driver (the default)

`Topic.run(v)` (no driver argument) launches a new embedded `MediaDriver`, opens an `Aeron` client against it, and closes both on exit. This is what every getting-started example uses, and it's the right call when your program owns its own Aeron instance.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

Topic.run {
    Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
}
```

> **Caution:** The embedded driver writes to a temporary Aeron directory derived from the OS temp dir. Two processes that both call `Topic.run()` with no argument get two separate drivers and cannot see each other's IPC traffic. To share one driver between processes, use the next overload.

### Shared `MediaDriver`

`Topic.run(driver: MediaDriver)(v)` reuses a driver you launched. Kyo opens an `Aeron` client against `driver.aeronDirectoryName()` and closes the client on exit; you remain responsible for closing the driver.

```scala
import io.aeron.driver.MediaDriver
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

val driver = MediaDriver.launchEmbedded()
try
    Topic.run(driver) {
        Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
    }
finally driver.close()
end try
```

Use this when one driver should outlive multiple `Topic.run` blocks (a long-running service, a test fixture that reuses one driver for many tests) or when several processes share a driver that an out-of-band script started.

### External `Aeron` client

`Topic.run(aeron: Aeron)(v)` reuses an `Aeron` client you opened. Kyo closes nothing; you own driver and client.

```scala
import io.aeron.Aeron
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
val ticks = Seq(Tick("AAPL", 19023, 1L), Tick("AAPL", 19045, 2L), Tick("MSFT", 41210, 3L))

val aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName("/dev/shm/aeron"))
try
    Topic.run(aeron) {
        Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096))
    }
finally aeron.close()
end try
```

This is the path when something else in the process already manages Aeron lifetime: a test harness, an embedding framework, or a service that wires Aeron at startup and tears it down at shutdown.

> **Caution:** The three overloads do NOT compose. Passing your own `MediaDriver` to the zero-arg overload by way of `Aeron.connect(...)` inside the inner computation results in a driver that you close AND that kyo's outer ensure also closes; one of the closes will fail or, worse, double-close the underlying file descriptors. Pick the overload that matches what you own, and don't reach for Aeron-the-library directly inside `Topic.run { ... }`.

### Choosing an overload

Reach for the zero-arg overload when the program is the only Aeron user in the process. Reach for the `MediaDriver` overload when several `Topic.run` blocks should share one IPC daemon. Reach for the `Aeron` overload when Aeron lifetime is managed by something outside `Topic.run` entirely.

## Concurrency

`Topic` is `opaque type Topic <: Env[Aeron]`, so it composes wherever `Env` does. The `Aeron` value lives in the `Env` row of every computation between `Topic.run` and a `Topic.publish`/`Topic.stream` call, and forks with `Fiber.initUnscoped` carry the row across the fiber boundary with no extra ceremony.

### Why `Topic` cannot be constructed directly

`Topic` is an opaque type with no public constructor; the only way to get a computation tagged with it is to be inside a `Topic.run` block. This is why the examples in this document always nest `publish`/`stream` inside `Topic.run` rather than constructing a `Topic` value and passing it around: there is no value to pass.

If you need to share publishing or subscribing logic across modules, write methods that return `Unit < (Topic & Async & Abort[...])` and let the call sites discharge `Topic` with `Topic.run`. The effect row, not a `Topic` value, is what travels.

## Putting it together

The sections above introduced each capability in isolation. This example combines them: one `Topic.run` block that publishes a batch of ticks and subscribes to them on the same URI, with a `Latch` coordinating startup so the subscriber is ready before the publisher writes.

```scala
import kyo.*

case class Tick(symbol: String, priceCents: Long, ts: Long) derives CanEqual, Topic.AsMessage
case class Trade(symbol: String, qty: Int, priceCents: Long) derives CanEqual, Topic.AsMessage

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

## Known limitations

Publishing a large batch currently panics rather than delivering messages in order. The test suite marks this edge as `pendingUntilFixed`: the 200-message case raises a `Result.Panic` instead of delivering the sequence intact. A single Aeron frame can carry a small chunk in order, but the reassembly path breaks down under multi-frame pressure. Design around small, single-frame chunks when strict ordering matters.
