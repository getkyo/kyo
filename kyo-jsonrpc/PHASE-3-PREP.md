# Phase 3 prep: JsonRpcTransport + JsonRpcTransport.inMemory

Source citations are file:line references into this worktree.

---

## 1. Verbatim API signatures

### Channel.init

```scala
// kyo-core/shared/src/main/scala/kyo/Channel.scala:325
def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Channel[A] < (Sync & Scope) =
    initWith[A](capacity, access)(identity)
```

Registers a `Scope.ensure(Channel.close(channel))` finalizer internally (line 339). The channel is closed when the enclosing `Scope.run` completes. Use this in `inMemory` if the caller will run inside a `Scope`.

### Channel.initUnscoped

```scala
// kyo-core/shared/src/main/scala/kyo/Channel.scala:374
def initUnscoped[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Channel[A] < Sync
```

No automatic cleanup. The `inMemory` factory signature in DESIGN.md §4 is `(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync`, which is effect-`Sync`-only with no `Scope`. That means `inMemory` MUST use `initUnscoped` for both channels, and `close` on either transport must explicitly close both.

### Channel[A].put

```scala
// kyo-core/shared/src/main/scala/kyo/Channel.scala:128
def put(value: A)(using Frame): Unit < (Abort[Closed] & Async)
```

Parks when the channel is full. Completes with `Abort.fail(Closed)` if the channel is closed while parked (see ChannelTest line 520-524: parked put gets a failure result after `c.close`).

### Channel[A].take

```scala
// kyo-core/shared/src/main/scala/kyo/Channel.scala:157
def take(using Frame): A < (Abort[Closed] & Async)
```

Parks when empty. Completes with `Abort.fail(Closed)` when the channel is closed while parked (ChannelTest line 509-513).

### Channel[A].stream

```scala
// kyo-core/shared/src/main/scala/kyo/Channel.scala:288
def stream(maxChunkSize: Int = Int.MaxValue)(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Abort[Closed] & Async]
```

Fails with `Abort[Closed]` on channel close. Wrong termination semantics for `incoming`: the peer's consumer stream should END, not abort, when the transport closes.

### Channel[A].streamUntilClosed

```scala
// kyo-core/shared/src/main/scala/kyo/Channel.scala:299-304
def streamUntilClosed(maxChunkSize: Int = Int.MaxValue)(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Async] =
    Stream:
        Abort.run[Closed](emitChunks(maxChunkSize)).map:
            case Result.Success(v) => v
            case Result.Failure(_) => ()
            case Result.Panic(e)   => Abort.panic(e)
```

Terminates cleanly (returns `Unit`) when the channel closes. `Abort[Closed]` is absorbed internally; callers see `Stream[A, Async]` with no `Closed` in the effect row. This is the correct choice for `incoming`.

### Channel[A].close

```scala
// kyo-core/shared/src/main/scala/kyo/Channel.scala:218
def close(using Frame): Maybe[Seq[A]] < Sync
```

Returns `Absent` if the channel was already closed, `Present(remaining)` with any unsent buffered elements if this call closed it. Effect is `Sync` (not `Async`). Calling `close` while a fiber is parked on `put` or `take` unblocks that fiber with `Abort.fail(Closed)` (ChannelTest lines 506-524).

### Access constants

```scala
// kyo-core/shared/src/main/scala/kyo/Access.scala:17-46
enum Access derives CanEqual:
    case MultiProducerMultiConsumer      // default
    case MultiProducerSingleConsumer
    case SingleProducerMultiConsumer
    case SingleProducerSingleConsumer
```

For the in-memory pair: each channel has one logical sender side (the transport whose `send` puts into this channel) and one logical receiver side (the peer transport's `incoming` that drains it). In practice the engine's writer fiber is the single producer and the reader fiber is the single consumer, making `SingleProducerSingleConsumer` optimal. However, `MultiProducerMultiConsumer` (the default) is always safe and avoids incorrectly constraining concurrent callers. Use `Access.MultiProducerMultiConsumer` unless profiling drives otherwise.

### Stream construction

```scala
// kyo-prelude/shared/src/main/scala/kyo/Stream.scala:903
inline def apply[V, S](inline v: => Unit < (Emit[Chunk[V]] & S)): Stream[V, S]
```

`Channel.streamUntilClosed()` returns a `Stream[A, Async]` directly; the impl does not need to call `Stream.apply` manually. Just call `inChannel.streamUntilClosed()` to produce the `incoming` stream.

### Stream.run

```scala
// kyo-prelude/shared/src/main/scala/kyo/Stream.scala:645
def run[VV >: V](using tag: Tag[Emit[Chunk[VV]]], frame: Frame): Chunk[VV] < S
```

Collects all emitted elements into a `Chunk`. Used in tests to drive the stream to completion.

### Scope.acquireRelease

```scala
// kyo-core/shared/src/main/scala/kyo/Scope.scala:86
def acquireRelease[A, S](acquire: => A < S)(release: A => Any < (Async & Abort[Throwable]))(using Frame): A < (Scope & Sync & S)
```

Not needed for `inMemory` itself (which is `< Sync`), but relevant if a caller wraps the transport pair in a `Scope`.

---

## 2. Concrete inMemory implementation sketch

The DESIGN.md §4 signature is `def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync`.

Because the return type is `< Sync` (no `Scope`), the channels must be created with `initUnscoped`. Lifecycle is owned by the `InMemoryTransport.close` method, which closes both channels explicitly.

```scala
object JsonRpcTransport:

    def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync =
        for
            // aToB: A sends, B receives
            aToB <- Channel.initUnscoped[JsonRpcEnvelope](capacity = 64)
            // bToA: B sends, A receives
            bToA <- Channel.initUnscoped[JsonRpcEnvelope](capacity = 64)
        yield
            val transportA = new InMemoryTransport(send = aToB, receive = bToA)
            val transportB = new InMemoryTransport(send = bToA, receive = aToB)
            (transportA, transportB)

    // Private implementation. Both channel handles are stored so that close()
    // can close BOTH channels regardless of which transport closes first.
    private final class InMemoryTransport(
        send: Channel[JsonRpcEnvelope],
        receive: Channel[JsonRpcEnvelope]
    ) extends JsonRpcTransport:

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            send.put(env)

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async] =
            receive.streamUntilClosed()

        def close(using Frame): Unit < Async =
            // Close both channels: this terminates both incoming streams cleanly.
            // Discard the returned Maybe[Seq[_]] (unsent messages are dropped on close).
            send.close.andThen(receive.close).unit
    end InMemoryTransport
```

Note: the `send` method on the trait and the `send` Channel field have the same name. Use `private` rename or restructure to avoid shadowing. The idiomatic fix is to name the fields `out` and `in`:

```scala
private final class InMemoryTransport(
    out: Channel[JsonRpcEnvelope],
    in:  Channel[JsonRpcEnvelope]
) extends JsonRpcTransport:

    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
        out.put(env)

    def incoming(using Frame): Stream[JsonRpcEnvelope, Async] =
        in.streamUntilClosed()

    def close(using Frame): Unit < Async =
        out.close.andThen(in.close).unit
```

`close` is `Unit < Async` per the trait but both `Channel.close` calls are `Sync`. `Sync` is a subtype of `Async` so the types unify without widening.

---

## 3. File:line anchors

| Item | File:line | Note |
|---|---|---|
| `Channel.close` return type `Maybe[Seq[A]] < Sync` | `Channel.scala:218` | `Sync` only, not `Async` |
| `streamUntilClosed` definition | `Channel.scala:299-304` | absorbs `Abort[Closed]` internally |
| `streamUntilClosed` semantics: stops cleanly on close | `ChannelTest.scala:1557-1566` | "should stop when channel is closed" |
| `stream` fails with `Abort[Closed]` on close | `Channel.scala:288` | wrong for `incoming` semantics |
| `Channel.init` registers Scope finalizer | `Channel.scala:339` | `Scope.ensure(Channel.close(channel))` |
| `Channel.initUnscoped` no auto-cleanup | `Channel.scala:374` | required for `< Sync` return |
| `Access.MultiProducerSingleConsumer` | `Access.scala:31` | one of four enum cases |
| `Access.MultiProducerMultiConsumer` default | `Access.scala:24` | used in `Channel.init` default |
| `Stream.apply` constructor | `Stream.scala:903` | inline, takes `Unit < (Emit[Chunk[V]] & S)` |
| `Stream.run` collects to `Chunk` | `Stream.scala:645` | used in tests |
| `Scope.acquireRelease` | `Scope.scala:86` | for resource-managed callers |

---

## 4. Edge cases and gotchas

**`Channel.close` returns `Maybe[Seq[A]]`**: the unsent elements in the buffer. The `InMemoryTransport.close` method discards this with `.unit` or `.andThen(...)`. Do not forward the backlog; closed-transport semantics drop in-flight messages. This matches the general contract.

**`streamUntilClosed` is the correct method for `incoming`**: it converts `Abort[Closed]` into normal stream termination. The `stream` method (which fails on close) would propagate `Abort[Closed]` out to the engine's reader fiber, which is not the intended behavior. The engine's reader should observe end-of-stream, not an error.

**Closing only `out` leaves the peer's `incoming` alive**: `in.streamUntilClosed()` on the peer drains `in` (which is the local `out`'s counterpart). If only `out` is closed, `in` on the peer is still open and the peer's reader blocks forever waiting for more envelopes. `close` MUST close both `out` and `in` to terminate both sides.

**`Channel.close` is idempotent-ish**: the second call returns `Absent` (already closed). So if both transports call `close`, the second invocation is a harmless no-op. No guard needed.

**Backpressure (Test 31)**: `Channel.initUnscoped[JsonRpcEnvelope](capacity = 1)` gives a buffer of size 1 (actually rounded up to next power of two on JVM, so use capacity=1 to get 1 or 2 slots; use `pendingPuts` to observe blockage). A more portable approach is capacity=0 which forces rendezvous: the put parks until a take matches. For Test 31, capacity=2 and sending 3 messages ensures the 3rd parks. Use `untilTrue(fiber.done)` negated (assert `!done`) to confirm parking.

**`Channel.put` and `Abort[Closed]`**: when a parked `put` fiber is unblocked by `close`, the promise is completed with `Result.fail(Closed)`. The callers of `put` see `Abort.fail(Closed)`. Test 32 must wrap the parked `send` in `Abort.run[Closed]` and assert `Result.Failure`.

**`incoming` effect row in the trait**: DESIGN.md §4 shows `incoming: Stream[Env, Async & Abort[Closed]]`. However `streamUntilClosed` returns `Stream[A, Async]` (no `Closed`). The trait definition in `IMPLEMENTATION.md` line 199 also shows `Async & Abort[Closed]`. There is a mismatch: `streamUntilClosed` absorbs `Closed` internally. Two options: (a) change the trait `incoming` return type to `Stream[JsonRpcEnvelope, Async]` (simpler, no `Abort[Closed]` leaks to callers), or (b) keep `Async & Abort[Closed]` in the trait signature but the `InMemoryTransport` implementation still returns `Stream[A, Async]` which is a subtype (contravariance on `S` means `Stream[A, Async]` satisfies `Stream[A, Async & Abort[Closed]]` since `Async & Abort[Closed] <: Async`). Option (b) works because `Stream[+V, -S]` has `-S`. The impl can return `streamUntilClosed()` (`Stream[A, Async]`) where `Stream[A, Async & Abort[Closed]]` is expected: `Async & Abort[Closed] <: Async` so `Stream[A, Async] <: Stream[A, Async & Abort[Closed]]` by contravariance. The type checker accepts it.

---

## 5. Test data suggestions

All tests use `JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "ping", Absent, Absent)` as the canonical test message. The `inMemory` factory runs inside `Scope.run(...)` to own the channel lifetimes if the test needs cleanup; otherwise call `close` explicitly at end.

**Test 27** (A sends, B receives):

```scala
"a send on transport A is received via incoming on transport B" in run {
    Scope.run:
        for
            (a, b) <- JsonRpcTransport.inMemory
            env     = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "ping", Absent, Absent)
            recv   <- Fiber.initUnscoped(b.incoming.take)
            _      <- a.send(env)
            result <- recv.get
        yield assert(result == env)
}
```

Note: `Stream.take` yields the first element. Use `b.incoming.take` if that method exists, otherwise `b.incoming.run` after sending N known envelopes and closing the transport.

**Test 28** (B sends, A receives): symmetric to Test 27 with roles swapped.

**Test 29** (send after close fails):

```scala
"a send on a closed transport fails with Abort[Closed]" in run {
    Scope.run:
        for
            (a, _) <- JsonRpcTransport.inMemory
            _      <- a.close
            env     = JsonRpcEnvelope.Request(JsonRpcId.Num(2L), "ping", Absent, Absent)
            result <- Abort.run[Closed](a.send(env))
        yield assert(result.isFailure)
}
```

**Test 30** (incoming terminates on peer close):

```scala
"the incoming stream on B terminates when A closes" in run {
    Scope.run:
        for
            (a, b)   <- JsonRpcTransport.inMemory
            env       = JsonRpcEnvelope.Request(JsonRpcId.Num(3L), "ping", Absent, Absent)
            _        <- a.send(env)
            collector <- Fiber.initUnscoped(b.incoming.run)
            _        <- a.close
            result   <- collector.get
        yield assert(result == Chunk(env))
}
```

`b.incoming` is `streamUntilClosed()` so it terminates when `in` (the `aToB` channel) is closed. `a.close` closes `aToB`, which terminates the stream. The fiber collects `Chunk(env)` (the one message sent before close).

**Test 31** (backpressure: slow consumer parks producer):

```scala
"a send parks when the consumer of incoming is slow" in run {
    Scope.run:
        for
            (a, b)  <- JsonRpcTransport.inMemory  // capacity = 64 by default; use 1 for tight backpressure
            env1     = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "v1", Absent, Absent)
            env2     = JsonRpcEnvelope.Request(JsonRpcId.Num(2L), "v2", Absent, Absent)
            // Fill the channel
            _       <- a.send(env1)
            // Second send parks until consumer takes
            putFiber <- Fiber.initUnscoped(a.send(env2))
            // Drain one element from B's side
            first   <- b.incoming.take
            // Now the parked put unblocks
            _       <- untilTrue(putFiber.done)
            second  <- b.incoming.take
        yield assert(first == env1 && second == env2)
}
```

For tight backpressure, `inMemory` needs a variant with capacity=1 or the test must fill the default buffer first. Consider adding an overload `inMemory(capacity: Int)` or just pre-filling with enough envelopes to saturate the default buffer of 64. The cleanest approach for the test is to add a private helper or use capacity=1.

**Test 32** (parked send aborts on close):

```scala
"a parked send unblocks with Abort[Closed] when the transport closes" in run {
    Scope.run:
        for
            (a, b) <- JsonRpcTransport.inMemory  // capacity=1 to force parking quickly
            // Fill the buffer
            _      <- a.send(JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "fill", Absent, Absent))
            // Fork a send that will park (buffer full)
            env2    = JsonRpcEnvelope.Request(JsonRpcId.Num(2L), "parked", Absent, Absent)
            parked <- Fiber.initUnscoped(Abort.run[Closed](a.send(env2)))
            // Wait until the fiber is actually parked
            _      <- untilTrue(a.pendingPuts.map(_ > 0))
            // Close the transport; the parked put must unblock
            _      <- a.close
            result <- parked.get
        yield assert(result.isFailure)
}
```

`a.pendingPuts` is `Channel[A].pendingPuts(using Frame): Int < (Abort[Closed] & Sync)`. The `send` Channel here is `out` (the `aToB` channel). The test accesses `a`'s internal `out.pendingPuts` to check blockage, but the public `JsonRpcTransport` trait does not expose `pendingPuts`. Instead, use `Async.sleep(10.millis)` as in `ChannelTest.scala:105` and verify the fiber has not completed yet (`putFiber.done` should be false). This avoids needing to expose internals.

---

## 6. Anti-flakiness deltas

- **Tests 31 and 32**: use `Fiber.initUnscoped` + `untilTrue(fiber.done)` or `untilTrue(putFiber.done.map(!_))` to observe parking deterministically. Alternatively use `Async.sleep(10.millis)` and check `f.done == false` as in `ChannelTest.scala:105-107`. Both patterns appear in the existing test suite.
- **Test 30**: `b.incoming.run` is driven in a forked fiber. `a.close` terminates the channel; the fiber observes end-of-stream and completes. No timeout needed: `streamUntilClosed` exits on channel close without a wallclock dependency.
- **Test 32**: after `a.close`, the parked put fiber unblocks synchronously (close flushes pending puts with `Abort.fail(Closed)`). `parked.get` will not hang.
- **No `Thread.sleep`**: use `Async.sleep` (Kyo) for any delay assertions.
- **No wallclock assertions**: the only bounded-time assertion needed is in Test 32, where close guarantees the parked fiber unblocks. Use `parked.get` directly; no timeout race needed.
- **Test 31 ordering**: because the channel is FIFO, `first == env1` and `second == env2` is deterministic. No race.

---

## 7. Concerns

**C1: inMemory capacity is hardcoded.** The DESIGN.md `inMemory` signature is `def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync` with no capacity parameter. Tests 31 and 32 require backpressure, which with capacity=64 (or any large default) means filling 64 slots before parking. This is ugly in tests. Consider adding a private overload `private def inMemory(capacity: Int)(using Frame): ... < Sync` for test use, or use `capacity = 2` as the default in `inMemory` (small enough to trigger backpressure in tests cheaply, large enough to not slow normal use). The IMPLEMENTATION.md does not pin the capacity; the impl agent may choose.

**C2: `incoming` return type mismatch.** The IMPLEMENTATION.md line 199 says `incoming` returns `Stream[JsonRpcEnvelope, Async & Abort[Closed]]`. The `streamUntilClosed()` call returns `Stream[JsonRpcEnvelope, Async]`. Because `Stream[+V, -S]` is contravariant in `S`, `Stream[A, Async]` satisfies `Stream[A, Async & Abort[Closed]]` (since `Async & Abort[Closed] <: Async`). The impl agent can declare the trait method as `Stream[JsonRpcEnvelope, Async & Abort[Closed]]` and return `in.streamUntilClosed()` from `InMemoryTransport` without a cast. The Scala 3 compiler will accept this. If it does not, change the trait signature to `Stream[JsonRpcEnvelope, Async]` (simpler and semantically correct: callers do not need to handle `Abort[Closed]` from `incoming`).

**C3: `close` effect is `Unit < Async` but channel close is `Sync`.** The trait declares `def close(using Frame): Unit < Async`. Both channel closes (`out.close` and `in.close`) are `< Sync`. `Sync <: Async` so `out.close.andThen(in.close).unit` type-checks as `Unit < Sync` which satisfies `Unit < Async`. No widening operation needed.

**C4: sbt project name correction from STEERING.md.** Verification commands in IMPLEMENTATION.md say `kyo-jsonrpcJVM/testOnly`; the actual JVM project key is `kyo-jsonrpc/testOnly` (unsuffixed, per STEERING.md "Findings from impl" section). The impl agent must use the unsuffixed name for JVM.

**C5: `Stream.take` availability.** The test sketch above calls `b.incoming.take`. Verify this method exists on `Stream` before using it. If absent, use `.run` after bounding the number of sent envelopes and closing the transport, or use `Fiber.initUnscoped(b.incoming.run)` with a bounded producer.
