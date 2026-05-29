# kyo-reactive-streams

`kyo-reactive-streams` bridges Kyo's `Stream[V, S]` with the pull-based, demand-driven Reactive Streams protocol. Two parallel surfaces ship in the same module: `kyo.interop.flow` uses the JDK's built-in `java.util.concurrent.Flow` interfaces and compiles on all platforms (JVM, JS, Native). `kyo.interop.reactivestreams` uses the legacy `org.reactivestreams` package (JVM only) and is a thin adapter on top of the flow surface. Both expose the same three operations: turn a `Publisher` into a `Stream`, turn a `Stream` into a `Publisher`, and subscribe a `Stream` to a `Subscriber` directly.

A `Stream`-to-`Publisher` bridge is a `Scope`-managed resource. Each subscriber spawns a long-running fiber that drains the source stream into `onNext` calls under the subscriber's demand. The reverse direction wraps an external publisher in a `StreamSubscriber` whose internal state machine tracks request credits, buffers incoming items per its `EmitStrategy` (eager passthrough or buffered batches), and surfaces upstream errors as `Result.Panic` on the resulting stream. Typical use never touches `StreamSubscriber`, `StreamPublisher`, or `StreamSubscription` directly. The extension method `stream.toPublisher` and the package function `fromPublisher(p, bufferSize)` cover most code.

```scala
import kyo.*
import kyo.interop.flow.*
import java.util.concurrent.Flow.Publisher

def consume(p: Publisher[Int]): Int < (Scope & Async) =
    fromPublisher(p, bufferSize = 16).map(_.fold(0)(_ + _))
```

## Converting between streams and reactive-streams

Four operations span the bridge. Two go from `Stream` outward to a `Publisher` or `Subscriber`. One goes inward, wrapping an external `Publisher` as a `Stream`. Each operation has both an extension-method ergonomic form and a package-function form with explicit isolation evidence.

The running case class for the rest of this README:

```scala
import kyo.*
case class Event(id: Long, payload: String)
```

### `fromPublisher`: external publisher to `Stream`

When something outside Kyo produces values through a `Flow.Publisher` (a JDK `SubmissionPublisher`, a third-party reactive library, a Kafka client adapter), wrap it as a `Stream[T, Async]`. The result is in `Scope`: closing the scope cancels the upstream subscription and drops any in-flight items.

```scala
import kyo.*
import kyo.interop.flow.*
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

def upstream: Publisher[Event] = ???

val sum: Long < (Scope & Async) =
    for
        events <- fromPublisher(upstream, bufferSize = 32)
        total  <- events.fold(0L)(_ + _.id)
    yield total
```

`bufferSize` is the number of items the subscriber requests at a time from the upstream `Subscription`. Larger values reduce request round-trips and improve throughput; smaller values keep memory bounded and let the stream react to cancellation sooner.

> **Note:** Upstream errors delivered via `Publisher.onError` arrive as `Result.Panic`, not `Result.Failure`. To observe them, run the stream under `Abort.run[Throwable]` rather than catching a domain-specific failure type.

```scala
import kyo.*
import kyo.interop.flow.*
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

def upstream: Publisher[Event] = ???

val observed: Result[Throwable, Long] < (Scope & Async) =
    Abort.run[Throwable]:
        fromPublisher(upstream, bufferSize = 32).map(_.fold(0L)(_ + _.id))
```

The `observed` value is `Result.Panic(t)` when the publisher signalled `onError(t)`, `Result.Success(sum)` on normal completion, and `Result.Failure(_)` only if a downstream operator aborts.

### `streamToPublisher` and `stream.toPublisher`

When you have a Kyo `Stream` and want to hand it to code that consumes a `Flow.Publisher`, use the extension method:

```scala
import kyo.*
import kyo.interop.flow.*
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

def wrap[S](using Isolate[S, Sync, Any])(events: Stream[Event, S & Sync]): Publisher[Event] < (Scope & Sync & S) =
    streamToPublisher(events)
```

The extension method `.toPublisher` is equivalent; `streamToPublisher` is the package-function form and takes the stream as an argument:

```scala
import kyo.*
import kyo.interop.flow.*
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

def wrap[S](using Isolate[S, Sync, Any])(events: Stream[Event, S & Sync]): Publisher[Event] < (Scope & Sync & S) =
    streamToPublisher(events)
```

Both forms require an `Isolate[S, Sync, Any]` for whatever effects the stream needs beyond `Sync`. For a pure `Stream[Event, Sync]` the evidence is built in; for streams that carry `Env`, `Var`, or other effects, the call site needs the matching isolation imports.

### `subscribeToStream` and `stream.subscribe(subscriber)`

When you already hold a `Flow.Subscriber` (a Pekko `Sink`, a Reactor consumer, a JDK `SubmissionPublisher` you want to fan out into) you can subscribe the stream directly without materializing an intermediate publisher:

```scala
import kyo.*
import kyo.interop.flow.*
import java.util.concurrent.Flow.{Subscriber, Subscription}

case class Event(id: Long, payload: String)

def sink: Subscriber[Event] = ???

val events: Stream[Event, Sync] =
    Stream.range(0, 10).map(i => Event(i.toLong, s"event-$i"))

val handle: Subscription < (Scope & Sync) =
    events.subscribe(sink)
```

The returned `Subscription` is the same object the subscriber received via its `onSubscribe`. Holding onto it lets you cancel from the Kyo side; in most cases the `Scope` finalizer handles cleanup when the surrounding effect finishes.

When you need package-function syntax (importing the operation under a name rather than relying on the extension):

```scala
import kyo.*
import kyo.interop.flow.*
import java.util.concurrent.Flow.{Subscriber, Subscription}

case class Event(id: Long, payload: String)

def sink: Subscriber[Event] = ???

val events: Stream[Event, Sync] = Stream.empty

val handle: Subscription < (Scope & Sync) =
    subscribeToStream(events, sink)
```

`stream.subscribe(subscriber)` and `subscribeToStream(stream, subscriber)` are the same operation. The extension is the ergonomic primary surface; the package function exists for cases where the receiver is awkward or for explicit-import call sites.

## Controlling back-pressure

Three knobs change how items move across the bridge. They cluster together because tuning one usually means thinking about the others.

### `EmitStrategy.Eager` vs `EmitStrategy.Buffer`

`fromPublisher` accepts an `emitStrategy: EmitStrategy = EmitStrategy.Eager`. The two cases trade latency for throughput:

```scala
import kyo.*
import kyo.interop.flow.*
import kyo.interop.flow.StreamSubscriber.EmitStrategy
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

def upstream: Publisher[Event] = ???

// Eager: each onNext becomes its own one-item chunk downstream
val eager: Stream[Event, Async] < (Scope & Sync) =
    fromPublisher(upstream, bufferSize = 64, emitStrategy = EmitStrategy.Eager)

// Buffer: collect up to bufferSize items, then emit a single chunk
val batched: Stream[Event, Async] < (Scope & Sync) =
    fromPublisher(upstream, bufferSize = 64, emitStrategy = EmitStrategy.Buffer)
```

`Eager` is the default. Each item forwards as soon as it arrives, which is the right shape when downstream operators react per-item (logging, side effects, item-by-item transformations).

`Buffer` accumulates up to `bufferSize` items inside the subscriber before pushing a single chunk downstream. Throughput goes up because fewer chunk boundaries cross fiber suspensions, and downstream operators see fully-formed chunks that vectorize cleanly. Latency goes up because no item is visible downstream until the chunk fills (or upstream completes).

### `bufferSize`

Across both directions and both strategies, `bufferSize` controls how many items the subscriber requests from upstream per `Subscription.request` call. The subscriber sends `request(bufferSize)`, waits to receive that many items (or fewer if upstream completes), then requests again.

Lower values keep the subscriber's internal queue small and bound memory at the cost of more request round-trips. Higher values amortize the round-trip but let upstream produce more items before back-pressure kicks in.

### `capacity` on `StreamPublisher.apply`

When you build a publisher via `streamToPublisher` (or `stream.toPublisher`), an internal channel queues incoming `subscribe` calls until the bridge fiber picks them up. By default the queue is `Int.MaxValue` (effectively unbounded). For cases where late subscribers should fail closed rather than wait, drop into `StreamPublisher.apply` directly:

```scala
import kyo.*
import kyo.interop.flow.*
import kyo.interop.flow.StreamPublisher
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

val events = Stream.range(0, 10).map(i => Event(i.toLong, s"event-$i"))

val publisher: Publisher[Event] < (Scope & Sync) =
    StreamPublisher(events, capacity = 4)
```

> **Note:** Each `subscribe` call gets a fresh `StreamSubscription` and re-evaluates the source stream from the beginning. If your stream has side effects (logging, counters, resource acquisition), they run once per subscriber.

> **Caution:** A publisher with no remaining capacity (or whose scope has closed) does not throw on `subscribe`. It calls the subscriber's `onSubscribe` with a no-op subscription and immediately invokes `onComplete`. Late subscribers silently see an empty stream. If you need them to observe a failure, layer your own rejection on top.

## Cross-platform vs JVM-only

Two parallel package surfaces cover the two Reactive Streams API generations.

### `kyo.interop.flow.*`

The cross-platform surface, built on `java.util.concurrent.Flow.{Publisher, Subscriber, Subscription}`. These interfaces ship in the JDK from Java 9 onward and are mirrored verbatim on Scala.js and Scala Native by Kyo's standard-library shims. Use this surface unless you specifically need to interoperate with a library that exposes the legacy `org.reactivestreams.*` types.

```scala
import kyo.*
import kyo.interop.flow.*
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

def upstream: Publisher[Event] = ???

val sum: Long < (Scope & Async) =
    fromPublisher(upstream, bufferSize = 32).map(_.fold(0L)(_ + _.id))
```

### `kyo.interop.reactivestreams.*`

A JVM-only mirror of the flow surface for the legacy `org.reactivestreams` interfaces. The function names and signatures are identical; the types are different. Internally it uses `FlowAdapters` from `org.reactivestreams` to wrap and unwrap, then delegates to the flow surface.

```scala
import kyo.*
import kyo.interop.reactivestreams.*
import org.reactivestreams.Publisher

case class Event(id: Long, payload: String)

def upstream: Publisher[Event] = ???

val sum: Long < (Scope & Async) =
    kyo.interop.reactivestreams.fromPublisher(upstream, bufferSize = 32).map(_.fold(0L)(_ + _.id))
```

Reach for this surface only when integrating with a library that publishes the `org.reactivestreams` types directly (older Akka/Pekko APIs, Reactor 3.x, RxJava 2.x adapters). New code targeting Java 9+ libraries should prefer the flow surface.

> **Caution:** `kyo.interop.flow` and `kyo.interop.reactivestreams` export identically-named functions (`fromPublisher`, `streamToPublisher`, `subscribeToStream`) but for different `Publisher`/`Subscriber` types. Importing both with wildcards creates ambiguity. Pick one per file, or qualify the call site.

## Resource and error semantics

Every conversion in this module returns a value in `Scope`. The scope owns the bridge fiber and any underlying `Subscription`. Three behaviors follow from this and from the Reactive Streams specification.

### `Scope` cancels the bridge

Closing the scope (explicitly or by leaving a `Scope.run` block) cancels the bridge fiber. On the `fromPublisher` side, the subscriber's internal interrupt calls `subscription.cancel()` upstream. On the `streamToPublisher` side, the supervisor fiber interrupts the consume loop, which then drives `onComplete` (or `onError` on panic) for the live subscriber.

```scala
import kyo.*
import kyo.interop.flow.*
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

def upstream: Publisher[Event] = ???

val firstTen: Chunk[Event] < (Scope & Async) =
    fromPublisher(upstream, bufferSize = 32).map(_.take(10).run)
```

When `firstTen`'s scope closes after the fold finishes, the upstream subscription is cancelled even though `upstream` may have many more items to produce.

### Upstream errors become `Result.Panic`

The Reactive Streams specification defines `onError(Throwable)` as a terminal failure signal. `fromPublisher` surfaces it on the Kyo side as `Result.Panic`. Domain `Abort[E]` handlers will not catch it; only `Abort.run[Throwable]` (or running the stream inside a `Fiber` and inspecting `getResult`) observes the failure.

```scala
import kyo.*
import kyo.interop.flow.*
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

def upstream: Publisher[Event] = ???

case class DomainError(reason: String)

// This Abort handler does NOT catch upstream onError signals
val onlyDomainFailures: Result[DomainError, Chunk[Event]] < (Scope & Async) =
    Abort.run[DomainError]:
        fromPublisher(upstream, bufferSize = 32).map(_.run)

// This one does
val anyFailure: Result[Throwable, Chunk[Event]] < (Scope & Async) =
    Abort.run[Throwable]:
        fromPublisher(upstream, bufferSize = 32).map(_.run)
```

### Spec-mandated null and request handling

The Reactive Streams specification requires several edge-case behaviors. `kyo-reactive-streams` implements them as the spec mandates:

- `Publisher.subscribe(null)` throws `NullPointerException` synchronously (Rule 1.9).
- `Subscriber.onSubscribe(null)`, `onNext(null)`, `onError(null)` on `StreamSubscriber` throw `NullPointerException`.
- `Subscription.request(n)` with `n <= 0` does not throw; it reports an `IllegalArgumentException` through the subscriber's `onError` (Rule 3.9).
- `Subscription.cancel()` is idempotent. Subsequent `request` and `cancel` calls are no-ops (Rules 3.6, 3.7).

### Terminal markers: `StreamComplete` and `StreamCanceled`

Under the hood, the per-subscriber fiber that drives `streamToPublisher` returns one of two sentinel values:

- `StreamSubscription.StreamComplete` (delivered as `Result.Success`): the source stream ended normally, and the subscriber received `onComplete`.
- `StreamSubscription.StreamCanceled` (delivered as `Result.Failure`): the downstream subscriber cancelled before the source stream ended.

Most code never observes these directly. They surface only when you build a publisher through `StreamPublisher.Unsafe.apply` (see below) and inspect the callback fiber's result.

## Low-level entry points

The architectural classes `StreamSubscriber`, `StreamPublisher`, and `StreamSubscription` are `private[kyo]`; their companions expose entry points for specialized use cases.

### `StreamSubscriber[V]` construction

When you need a `Subscriber` you can hand to a publisher that subscribes itself (rather than letting `fromPublisher` drive the subscription), build one directly. The `.stream` method then exposes the Kyo `Stream` that drains it.

```scala
import kyo.*
import kyo.interop.flow.*
import kyo.interop.flow.StreamSubscriber.EmitStrategy
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

def thirdParty: Publisher[Event] = ???

val sum: Long < (Scope & Async) =
    for
        subscriber <- StreamSubscriber[Event](bufferSize = 32, EmitStrategy.Buffer)
        _      = thirdParty.subscribe(subscriber)
        stream <- subscriber.stream
        total  <- stream.fold(0L)(_ + _.id)
    yield total
```

This is exactly what `fromPublisher` does internally. Use the explicit form when the publisher needs to be handed the subscriber as a side effect, or when you want to inspect the subscriber between construction and `.stream`.

### `StreamPublisher.Unsafe.apply` and `StreamSubscription.Unsafe.subscribe`

The TCK (Reactive Streams Technology Compatibility Kit) drives publishers and subscribers from a test harness that owns its own thread pool and lifetime. It expects to receive a `Publisher` synchronously, not a `Publisher < (Scope & Sync)`. For these cases the module exposes unsafe entry points:

```scala
import kyo.*
import kyo.interop.flow.*
import kyo.interop.flow.StreamPublisher
import kyo.interop.flow.StreamSubscription.{StreamCanceled, StreamComplete}
import java.util.concurrent.Flow.Publisher

import AllowUnsafe.embrace.danger

case class Event(id: Long, payload: String)

val events: Stream[Event, Sync] =
    Stream.range(0, 10).map(i => Event(i.toLong, s"event-$i"))

val publisher: Publisher[Event] =
    StreamPublisher.Unsafe(
        events,
        subscribeCallback = fiber =>
            val _ = Sync.Unsafe.evalOrThrow(KyoApp.runAndBlock(Duration.Infinity)(fiber))
    )
```

The callback receives the per-subscriber fiber (`Fiber[StreamComplete, Abort[StreamCanceled]] < (Sync & S)`) and is responsible for running it. The harness chooses the execution model (block-and-wait, dispatch to a thread pool, run in the calling thread).

> **Caution:** `Unsafe` entry points bypass `Scope`. Cancellation, error propagation, and resource cleanup are the caller's responsibility. Production code should use the safe `fromPublisher`, `streamToPublisher`, and `subscribeToStream` instead.

## Putting it together

A bidirectional bridge: a Kyo source stream of `Event`s is exposed as a multi-subscriber publisher, and an external publisher's items are merged back in as a stream.

```scala
import kyo.*
import kyo.interop.flow.*
import kyo.interop.flow.StreamPublisher
import kyo.interop.flow.StreamSubscriber.EmitStrategy
import java.util.concurrent.Flow.Publisher

case class Event(id: Long, payload: String)

def externalSource: Publisher[Event] = ???
def externalSink(p: Publisher[Event]): Unit = ???

val pipeline: Long < (Scope & Async) =
    for
        // Outbound: a Kyo stream of events, exposed to two downstream subscribers.
        outbound <-
            StreamPublisher(
                Stream.range(0, 100).map(i => Event(i.toLong, s"out-$i")),
                capacity = 8
            )
        _ = externalSink(outbound)

        // Inbound: an external publisher wrapped as a Kyo stream, buffered for
        // throughput, with upstream errors observable as Result.Panic.
        inboundStream <- fromPublisher(externalSource, bufferSize = 64, EmitStrategy.Buffer)

        // Mix inbound items with a Kyo-side derived stream, fold to a total.
        observed <-
            Abort.run[Throwable]:
                inboundStream
                    .map(e => Event(e.id, e.payload.toUpperCase))
                    .fold(0L)(_ + _.id)
        total = observed match
            case Result.Success(n) => n
            case _                 => -1L
    yield total
```

What this exercise covers:

- `StreamPublisher.apply` with bounded `capacity`: late subscribers past the eighth are completed immediately.
- `fromPublisher` with `EmitStrategy.Buffer` and a custom `bufferSize`: chunked downstream processing.
- `Abort.run[Throwable]`: observes upstream `onError` (delivered as `Result.Panic`).
- `Scope`: when `pipeline`'s scope closes after `total` resolves, the publisher's supervisor fiber stops, in-flight subscriptions cancel, and `externalSource`'s subscription is cancelled cleanly.

For the JVM-only legacy interface, swap `kyo.interop.flow.*` for `kyo.interop.reactivestreams.*` and `java.util.concurrent.Flow.Publisher` for `org.reactivestreams.Publisher`. The rest of the example is unchanged.
