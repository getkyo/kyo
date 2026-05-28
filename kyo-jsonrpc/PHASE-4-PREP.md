# Phase 4 Implementation Prep

## 1. Verbatim API signatures (file:line)

### Exchange.initUnscoped (the six-param overload the engine uses)

`Exchange.scala:168`

```scala
def initUnscoped[Id, Req, Resp, Wire, Event, E](
    nextId: => Id < Sync,
    encode: (Id, Req) => Wire < Sync,
    send: Wire => Unit < (Async & Abort[E]),
    receive: Stream[Wire, Async & Abort[E]],
    decode: Wire => Exchange.Message[Id, Resp, Event] < Sync,
    eventCapacity: Int = 16
)(using frame: Frame, eTag: ConcreteTag[E], wireTag: Tag[Emit[Chunk[Wire]]]): Exchange[Req, Resp, Event, E] < Sync
```

The opaque type `Exchange[Req, Resp, Event, E]` erases `Id` and `Wire`; they appear only in the factory signature.

### Exchange.apply (safe extension)

`Exchange.scala:259`

```scala
def apply(req: Req)(using Frame): Resp < (Async & Abort[E | Closed])
```

Called as `exchange(req)`. Assigns id, encodes, sends, awaits response. Uses `Sync.ensure` internally to remove the pending entry on every exit path.

### Exchange.close (safe extension)

`Exchange.scala:307`

```scala
def close(using Frame): Unit < Sync
```

Fails all pending promises with `Closed`. Idempotent.

### Exchange.Message variants

`Exchange.scala:63`

```scala
enum Message[+Id, +Resp, +Event]:
    case Response(id: Id, value: Resp)
    case Push(value: Event)
    case Skip
```

The engine's `decode` callback returns one of these. `Skip` means "do not route anywhere"; used for every envelope the engine handles inline (Requests, Notifications, Malformed) so Exchange does nothing further.

### Exchange.Unsafe (internal class, for reference only)

`Exchange.scala:315` — six-param private class `Unsafe[Id, Wire, Req, Resp, Event, E]`. The safe `Exchange[Req, Resp, Event, E]` is an opaque alias for it. The engine does NOT call the Unsafe API; it uses the safe `initUnscoped` and safe `apply`.

### Fiber.initUnscoped

`Fiber.scala:163`

```scala
def initUnscoped[E, A, S, S2](
    using isolate: Isolate[S, Sync, S2]
)(
    v: => A < (Abort[E] & Async & S)
)(
    using
    reduce: Reducible[Abort[E]],
    frame: Frame
): Fiber[A, reduce.SReduced & S2] < (Sync & S)
```

Used to fork handler fibers from the decode callback (Sync-only context). For `Async & Abort[JsonRpcError]` the `Isolate` is resolved implicitly by kyo-core's `Isolate.derive`.

### Fiber.Promise.init

`Fiber.scala:449`

```scala
def init[E, A](using Frame): Promise[E, A] < Sync
```

Returns a fresh, incomplete promise. The engine creates one `idSignal` and one `abortSignal` per outbound call.

### Promise safe extensions (on `Promise[A, Abort[E] & S]`)

`Fiber.scala:536`

```scala
def complete(v: Result[E, A < S])(using Frame): Boolean < Sync
def completeDiscard(v: Result[E, A < S])(using Frame): Unit < Sync
```

`Fiber.scala:510` (non-Abort overload):

```scala
def poll(using Frame): Maybe[Result[Nothing, A < S]] < Sync
```

`Fiber.scala:243` (get, on `Promise[A, Abort[E] & S]`):

```scala
def get(using Frame): A < (Abort[E] & Async & S)
```

`Fiber.safe.get` parks until the promise is completed and then surfaces the result.

### Async.race (two-param overload)

`Async.scala:243`

```scala
def race[E, A, S](
    using Isolate[S, Abort[E] & Async, S]
)(
    first: A < (Abort[E] & Async & S),
    rest: A < (Abort[E] & Async & S)*
)(
    using frame: Frame
): A < (Abort[E] & Async & S)
```

Races both legs; when one completes, the other is interrupted. For the engine's cancellation pattern the two legs are `abortSignal.safe.get` and `exchange(req).map(decodeResp)`.

### Channel.initUnscoped

`Channel.scala:374`

```scala
def initUnscoped[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Channel[A] < Sync
```

### Channel safe extensions

`Channel.scala:128` — `put(value: A)(using Frame): Unit < (Abort[Closed] & Async)`

`Channel.scala:157` — `take(using Frame): A < (Abort[Closed] & Async)`

`Channel.scala:218` — `close(using Frame): Maybe[Seq[A]] < Sync`

`Channel.scala:299` — `streamUntilClosed(maxChunkSize: Int = Int.MaxValue)(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Async]`

### Sync.ensure

`Sync.scala:66`

```scala
inline def ensure[A, S](inline f: => Any < (Sync & Abort[Throwable]))(v: => A < S)(using inline frame: Frame): A < (Sync & S)
```

Guarantees `f` runs after `v` completes regardless of success, failure, or interrupt.

### Scope.acquireRelease

`Scope.scala:86`

```scala
def acquireRelease[A, S](acquire: => A < S)(release: A => Any < (Async & Abort[Throwable]))(using Frame): A < (Scope & Sync & S)
```

Used to register the composite §6.4 finalizer. The release lambda is the 8-step sequence.

### Structure.encode / decode (kyo-schema public API)

```scala
// Structure.scala:44
def encode[A: Schema](a: A)(using Frame): Structure.Value

// Structure.scala:57
def decode[A: Schema](v: Structure.Value)(using Frame): Result[DecodeException, A]
```

### Json.encode / decode (kyo-schema public API)

```scala
def encode[A: Schema](a: A)(using Frame): String
def decode[A: Schema](s: String)(using Frame): Result[DecodeException, A]
```

The engine's `encode` callback calls `Json.encode[JsonRpcEnvelope]` (via the codec) to produce the `Wire = String`. The `decode` callback calls `Json.decode[JsonRpcEnvelope]` (via the codec) to parse incoming JSON.

---

## 2. The Exchange type-parameter pinning (verified against source)

DESIGN §6.1 states:

```
Exchange[JsonRpcId, OutboundReq, Structure.Value, String, Nothing, JsonRpcError]
```

Mapping to `Exchange[Req, Resp, Event, E]` (the opaque type's four visible params):

| Design param | Exchange param | Concrete |
|---|---|---|
| Id | (erased in opaque type, present in factory) | `JsonRpcId` |
| Req | Req | `OutboundReq` |
| Resp | Resp | `Structure.Value` |
| Wire | (erased in opaque type, present in factory) | `String` |
| Event | Event | `Nothing` |
| E | E | `JsonRpcError` |

So the opaque type is `Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError]` and the factory call is:

```scala
Exchange.initUnscoped[JsonRpcId, OutboundReq, Structure.Value, String, Nothing, JsonRpcError](
    nextId = ...,
    encode = (id: JsonRpcId, req: OutboundReq) => String < Sync,
    send   = (wire: String) => Unit < (Async & Abort[JsonRpcError]),
    receive = ...: Stream[String, Async & Abort[JsonRpcError]],
    decode = (wire: String) => Exchange.Message[JsonRpcId, Structure.Value, Nothing] < Sync
)
```

**The six type params are a factory-call concern only.** The engine stores the exchange as `Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError]` (four params). This matches the opaque type definition at `Exchange.scala:58`.

---

## 3. The `send` callback: Closed-to-JsonRpcError conversion

Exchange's `send` parameter type is `Wire => Unit < (Async & Abort[E])` where `E = JsonRpcError`. The engine's writer channel is `Channel[String]`; `channel.put(wire)` returns `Unit < (Abort[Closed] & Async)`. These effect rows do not unify directly.

The send callback must bridge them:

```scala
send = wire =>
    Abort.run[Closed](writerChannel.put(wire)).map:
        case Result.Success(_)   => ()
        case Result.Failure(c)   => Abort.fail(JsonRpcError.internalError(s"transport closed: ${c.message}", Absent))
        case Result.Panic(t)     => Abort.panic(t)
```

This converts `Abort[Closed]` to `Abort[JsonRpcError]`, satisfying Exchange's `send` type. The `internalError` factory (DESIGN §15) is the appropriate error code (-32603).

Similarly, the `receive` stream comes from `transport.incoming` which is `Stream[JsonRpcEnvelope, Async & Abort[Closed]]`. The engine must map it to `Stream[String, Async & Abort[JsonRpcError]]`:

```scala
receive = transport.incoming.map(env =>
    codec.encode(env)          // Structure.Value < (Sync & Abort[JsonRpcError])
).mapChunk(???)                // convert Abort[Closed] to Abort[JsonRpcError]
```

Concretely, the incoming envelopes are already decoded (transport delivers `JsonRpcEnvelope`). The Exchange `receive` stream must carry raw JSON `String`. But the engine's `decode` callback is the one that parses JSON into envelopes, not the transport. The transport already handles codec decoding internally.

**The correct wiring (read DESIGN §4 carefully):** The `JsonRpcTransport` delivers `JsonRpcEnvelope` objects (codec runs inside the transport). The engine wraps the transport's `incoming` stream via `Json.encode` (going backwards: envelope -> JSON string) so Exchange's reader fiber can call the engine's `decode` callback (JSON string -> classify). This round-trip is intentional: Exchange requires `Wire = String`; the engine's decode callback is the hook for routing.

Alternative: use `transport.incoming` as-is and have the engine's `decode` callback receive envelopes directly. But Exchange's type forces `Wire = String`. The practical implementation should encode envelopes to JSON on the way into Exchange and decode on the way out, using the same codec. See §6.1: "the codec operates inside Exchange's encode/decode callbacks."

The receive stream must convert `Abort[Closed]` to `Abort[JsonRpcError]`:

```scala
receive = transport.incoming
    .mapError[JsonRpcError] {
        case c: Closed => JsonRpcError.internalError(s"transport closed: ${c.message}", Absent)
    }
    .map(env => Abort.run[JsonRpcError](codec.encode(env)).map {
        case Result.Success(sv)  => Json.encode(sv) // Structure.Value -> String (codec already returned SV)
        case Result.Failure(err) => Abort.fail(err)
        case Result.Panic(t)     => Abort.panic(t)
    })
```

Note: `codec.encode` returns `Structure.Value < (Sync & Abort[JsonRpcError])`. The final wire is a JSON string; apply `Json.encode` or the codec's own serialize. Check the actual `JsonRpcCodec` API in the existing `JsonRpcCodec.scala` to confirm the exact return type and serialize step.

---

## 4. The complete shape of OutboundReq, CallerInfo, InboundEntry, WriterMsg

These live in `kyo/internal/JsonRpcEndpointImpl.scala`.

```scala
private[kyo] case class OutboundReq(
    method:        String,
    encodedParams: Maybe[Structure.Value],
    idSignal:      Fiber.Promise.Unsafe[JsonRpcId, Any],  // completed inside Exchange encode callback
    abortSignal:   Promise[Nothing, JsonRpcError],         // completed by cancel or scope drain
    extras:        ExtrasEncoder                           // closure to invoke with assigned id
)
```

Note: `idSignal` must be `Promise.Unsafe` (no Frame context available inside `Sync`-only encode callback). It is created via `Sync.Unsafe.defer(Promise.Unsafe.init[JsonRpcId, Any]())`.

```scala
private[kyo] case class CallerInfo(
    method:      String,
    extras:      Maybe[Structure.Value],  // snapshot of extras resolved with a placeholder id
    abortSignal: Promise[Nothing, JsonRpcError]
)
```

`CallerInfo` is the side-table entry keyed by `JsonRpcId` in `callerRegistry`. The `extras` snapshot is used by `endpoint.cancel` to stamp the cancel notification's envelope extras (MCP routing hint). Populated inside the Exchange `encode` callback.

```scala
private[kyo] sealed trait InboundEntry
private[kyo] object InboundEntry:
    case class Running(
        method:    String,
        handler:   Fiber[Structure.Value, Any],   // forked via Fiber.initUnscoped
        cancelled: Fiber.Promise.Unsafe[Unit, Any] // completed when peer requests cancel
    ) extends InboundEntry

    case class Replying(
        method:   String,
        suppress: AtomicBoolean                    // set to true if cancel arrives after handler done
    ) extends InboundEntry

    case class Cancelled(method: String) extends InboundEntry
```

`InboundEntry` is keyed by `JsonRpcId` (inbound request id) in `pendingInbound: ConcurrentHashMap[JsonRpcId, InboundEntry]`.

```scala
private[kyo] sealed trait WriterMsg
private[kyo] object WriterMsg:
    case class SendEnvelope(env: JsonRpcEnvelope) extends WriterMsg
    case class SuppressIfCancelled(id: JsonRpcId, env: JsonRpcEnvelope) extends WriterMsg
    case object Poison extends WriterMsg   // sent during §6.4 step 1 to stop the writer loop
```

`Poison` is not in DESIGN.md explicitly but is needed to stop the writer fiber loop during teardown (§6.4 step 1: "poison the writer channel"). Writer loop: `take` from channel; if `Poison`, exit; if `SendEnvelope`, send; if `SuppressIfCancelled`, check `Replying.suppress` then send or drop, then `Sync.ensure` removes from `pendingInbound`.

---

## 5. The §6.2 reader-fiber routing sequence (concrete pseudocode)

The engine's `decode` callback receives a raw JSON string and runs Sync-only. Steps follow DESIGN §6.2 in order.

```
decode(wire: String): Exchange.Message[JsonRpcId, Structure.Value, Nothing] < Sync

  envelope = codec.decode(wire)   // Sync; returns JsonRpcEnvelope (including Malformed)

  match envelope:

    // STEP 1: Policy intercept — Phase 4 NO-OP (cancellation = Absent, progress = Absent)
    // In Phase 5: check if envelope is a Notification matching cancellation.cancelMethod -> handle cancel; return Skip
    // In Phase 6: check if envelope is a Notification matching progress.progressMethod -> route to progressStreams; return Skip
    // Phase 4: fall through for all envelopes.

    // STEP 2: MessageGate — Phase 4 NO-OP (gate = Absent)
    // In Phase 7: gate.beforeDispatch(envelope) -> Allow/Reject(err)/Drop
    // Phase 4: fall through for all envelopes.

    // STEP 3: Method dispatch
    case Request(id, method, params, extras) =>
      // Phase 4 uses UnknownMethodPolicy.minimal:
      //   unknown requests -> reply MethodNotFound
      //   unknown notifications -> drop
      methods.get(method) match
        case Some(m) =>
          // Register inbound entry (Sync.Unsafe.defer on ConcurrentHashMap)
          val cancelled  = Promise.Unsafe.init[Unit, Any]()
          val ctx        = HandlerCtx(cancelled, Present(id), extras, Absent)
          // Fork handler fiber (Fiber.initUnscoped is Sync)
          val fiber      = Fiber.initUnscoped(m.handle(params.getOrElse(Structure.Value.Null), ctx))
          val entry      = InboundEntry.Running(method, fiber, cancelled)
          pendingInbound.put(id, entry)
          // After handler completes, transition Running -> Replying, enqueue reply
          // (wired via fiber.onComplete registered in Sync.Unsafe.defer)
          Exchange.Message.Skip   // reader does nothing more; reply flows via writer

        case None =>
          // Enqueue MethodNotFound response directly to writer channel (Channel.Unsafe.offer)
          val resp = JsonRpcEnvelope.Response(id, Absent, Present(JsonRpcError.MethodNotFound), Absent)
          writerChannel.offer(WriterMsg.SendEnvelope(resp))
          Exchange.Message.Skip

    case Notification(method, params, extras) =>
      // Phase 4: Step 1 intercept not yet wired; proceed to dispatch.
      methods.get(method) match
        case Some(m) =>
          val ctx = HandlerCtx(Promise.Unsafe.init(), Absent, extras, Absent)
          Fiber.initUnscoped(m.handle(params.getOrElse(Structure.Value.Null), ctx))
          // return value discarded for notifications
          Exchange.Message.Skip

        case None =>
          // UnknownMethodPolicy.minimal: drop unknown notifications
          Exchange.Message.Skip

    case Response(id, result, error, _) =>
      // STEP 4: Response routing — Exchange handles this.
      // Decode the result/error field to Structure.Value:
      val payload = error match
        case Present(e) => Abort.fail(e)           // surfaces as Abort[JsonRpcError] to caller
        case Absent     => result.getOrElse(Structure.Value.Null)
      Exchange.Message.Response(id, payload)
      // Exchange's reader matches id against pending map and completes the caller's promise.
      // If caller was already cancelled (pending entry gone), Exchange drops silently.

    case Malformed(reason, raw) =>
      // If raw has a parseable id: send ParseError response.
      // Otherwise: log and drop.
      // Implementation: try to extract id from raw (Structure.Value.Record); if found, enqueue ParseError.
      Exchange.Message.Skip
```

**Critical constraint:** everything in `decode` is `Sync` only. `Fiber.initUnscoped` is `Sync`. `Channel.Unsafe.offer` is non-blocking. `ConcurrentHashMap.put` is `Sync.Unsafe.defer`. The reader never parks.

The handler-completion hook (transitioning `Running -> Replying` and enqueueing reply) is registered via `Sync.Unsafe.defer(fiber.unsafe.onComplete(...))` inside the `decode` callback. The `onComplete` closure does the CAS (§6.5) and calls `Channel.Unsafe.offer(WriterMsg.SuppressIfCancelled(id, replyEnv))` on success.

---

## 6. The §6.4 scope finalizer order

Registered via `Scope.acquireRelease(initEngine)(_ => finalizer)`. The release lambda runs 8 steps in order:

```
Step 1: Poison the writer channel.
    writerChannel.close()   // or offer Poison; prefer close() so take() raises Closed
    // This unblocks the writer fiber if it is parked on take.

Step 2: Cancel the reader fiber.
    readerFiber.unsafe.interruptDiscard(Result.Panic(Interrupted(frame)))

Step 3: Cancel the writer fiber.
    writerFiber.unsafe.interruptDiscard(Result.Panic(Interrupted(frame)))

Step 4: Close the transport.
    Sync.Unsafe.defer(???)   // transport.close is Async; must be run via a fiber or Sync.Unsafe bridge
    // In practice: Fiber.initUnscoped(transport.close).flatMap(_.get) or similar Async-in-finalizer pattern.
    // See §6.4: "transport.close" listed before exchange.close intentionally (writer is already stopped
    // so no new sends; closing transport flushes any in-flight bytes then closes the socket).

Step 5: Close Exchange.
    Sync.Unsafe.defer(exchange.unsafe.close())
    // exchange.close() fails every entry in Exchange's pending map with Closed.
    // This unblocks any fiber awaiting exchange(req).

Step 6: Drain callerRegistry.
    callerRegistry.forEach { (_, info) =>
        info.abortSignal.unsafe.completeDiscard(Result.fail(Closed("JsonRpcEndpoint", frame)))
    }
    callerRegistry.clear()
    // Fibers awaiting Async.race(abortSignal.safe.get, exchange(...)) now see Closed
    // from the abortSignal arm (exchange arm already failed in step 5).

Step 7: Close every progressStreams channel.
    progressStreams.forEach { (_, ch) => ch.close() }
    progressStreams.clear()

Step 8: Interrupt every pendingInbound handler fiber.
    pendingInbound.forEach { (_, entry) =>
        entry match
            case InboundEntry.Running(_, handler, _) =>
                handler.unsafe.interruptDiscard(Result.Panic(Interrupted(frame)))
            case _ => ()
    }
    pendingInbound.clear()
```

**Note on Step 4 (transport.close in finalizer):** `Scope.acquireRelease`'s release type is `A => Any < (Async & Abort[Throwable])`, which DOES allow Async. So `transport.close` (which is `Unit < Async`) can be called directly inside the release lambda. No special wrapping needed.

**Invariant 12 (DESIGN §20):** "Closing the transport before letting Exchange drain its pending map would deadlock the writer fiber." Steps 1-3 stop the writer before step 4 closes transport. Step 5 closes Exchange before step 6 drains callerRegistry. The order is load-bearing.

---

## 7. The `Async.race(abortSignal.safe.get, exchange(req))` cancellation pattern

Full sequence for `endpoint.call[In, Out](method, params, extras)`:

```scala
def call[In: Schema, Out: Schema](method: String, params: In, extras: ExtrasEncoder = ExtrasEncoder.empty)
    (using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =

    // 1. Create signals (Sync)
    Fiber.Promise.init[Nothing, JsonRpcError].map { abortSignal =>
        Sync.Unsafe.defer {
            val idSignal = Promise.Unsafe.init[JsonRpcId, Any]()

            // 2. Build OutboundReq (the id is not yet known; idSignal is the gate)
            val encodedParams = Present(Structure.encode[In](params))
            val req = OutboundReq(method, encodedParams, idSignal, abortSignal, extras)

            // 3. Sync.ensure to remove callerRegistry entry on every exit path.
            //    We need the id to remove; read idSignal.poll after the race.
            Sync.ensure {
                Sync.Unsafe.defer {
                    idSignal.poll() match
                        case Maybe.Present(Result.Success(id)) =>
                            callerRegistry.remove(id)
                        case _ => ()
                }
            } {
                // 4. Race: abortSignal (cancel) vs exchange call (response)
                //    Async.race interrupts the loser when either leg wins.
                Async.race[JsonRpcError | Closed, Out, Any](
                    abortSignal.safe.get.map(e => Abort.fail(e)),
                    exchange(req).map { sv =>
                        // decode the Structure.Value to Out
                        Structure.decode[Out](sv) match
                            case Result.Success(v)    => v
                            case Result.Failure(e)    => Abort.fail(JsonRpcError.invalidParams(e.getMessage))
                            case Result.Panic(t)      => Abort.panic(t)
                    }
                )
            }
        }
    }
```

**Inside the Exchange `encode` callback** (Id=JsonRpcId, Req=OutboundReq, Wire=String, called from Sync context):

```scala
encode = (id: JsonRpcId, req: OutboundReq) =>
    // This is called with Sync only; no parking.
    // 1. Resolve extras closure with the now-known id.
    //    extras is a function; invoke it:
    //    val extrasValue = Sync.Unsafe.evalOrThrow(req.extras(id))  -- if extras is Sync
    //    For Phase 4 with ExtrasEncoder.empty / const this is trivial.

    // 2. Insert into callerRegistry (atomic, no race window after this point).
    callerRegistry.put(id, CallerInfo(req.method, extrasValue, req.abortSignal))

    // 3. Complete idSignal so the outer Sync.ensure can find the id on exit.
    req.idSignal.completeDiscard(Result.succeed(id))

    // 4. Build and return the wire JSON string.
    val envelope = JsonRpcEnvelope.Request(id, req.method, req.encodedParams, extrasValue)
    Sync.Unsafe.evalOrThrow(codec.encode(envelope).map(Json.encode))
    // returns String
```

**Why `Async.race` works for cancellation:** when `endpoint.cancel(id)` completes `abortSignal` with `Result.fail(err)`, the `abortSignal.safe.get` arm wins the race and `Async.race` interrupts the `exchange(req)` arm. Exchange's `apply` has its own internal `Sync.ensure` that removes the pending entry. A late response for that id finds an empty slot in Exchange's pending map and is silently dropped.

---

## 8. The two-stage idSignal pattern (summary)

The id is assigned inside Exchange's encode callback, but the outer `Sync.ensure` needs the id to clean up `callerRegistry`. The `idSignal` bridges the two:

1. Before calling `exchange(req)`: create `idSignal = Promise.Unsafe.init[JsonRpcId, Any]()`.
2. Inside `encode` callback: complete `idSignal` with the assigned id AND insert `callerRegistry[id]`.
3. Inside outer `Sync.ensure` finalizer: `idSignal.poll()` returns `Present(Success(id))` if the encode callback ran; remove `callerRegistry[id]` if so.
4. If the exchange call fails before encode runs (e.g., Exchange is already closed): `idSignal.poll()` returns `Absent`; no callerRegistry entry was ever inserted; nothing to remove.

This closes the race window: there is no point where a concurrent `endpoint.cancel(id)` could fail to find the entry, because the entry is inserted atomically inside the same encode callback that assigns the id.

---

## 9. Test data and anti-flakiness guidance

### Test 33: basic call round-trip

Use `JsonRpcTransport.inMemory(capacity = 16)`. Register `add` on side B as:

```scala
JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add")(req => AddResp(req.a + req.b))
```

Call from side A: `endpointA.call[AddReq, AddResp]("add", AddReq(1, 2))`. Assert result == `AddResp(3)`. Simple sequential, no latch needed.

### Test 34: notify, no reply

Use a `Channel[LogMsg]` as a side-channel: B's `log` notification handler puts into the channel. After `endpointA.notify[LogMsg]("log", LogMsg("hello"))`, do `notifChannel.take` with a timeout to confirm delivery. Verify the transport received exactly one frame (the notification): inspect via a counting transport wrapper or check `inMemory` channel size before B processes.

### Test 35: bidirectional simultaneous calls

A starts `call("echo-a", ...)` to B; B simultaneously starts `call("echo-b", ...)` to A. Both must resolve without cross-wiring. Use `Fiber.initUnscoped` for both and `.get` both. No Latch needed; `Fiber.get` provides the synchronization.

### Test 36: multiple concurrent calls, correct routing

Launch 10 concurrent `Fiber.initUnscoped(endpointA.call[AddReq, AddResp]("add", AddReq(i, i)))`. Collect all results. Assert each result is `AddResp(i+i)`. The test verifies id routing is correct under concurrency.

### Test 37: unknown method

A calls `endpointB.call[Unit, Unit]("nonexistent", ())`. B has no handler registered. Assert the result is `Abort.fail(JsonRpcError)` with `code == -32601`.

### Test 38: Scope cleanup, Exchange pending map drained

```scala
val ex = Scope.run {
    for
        (tA, tB) <- JsonRpcTransport.inMemory
        endpointA <- JsonRpcEndpoint.init(tA, Seq.empty)
    yield endpointA
}
result <- Abort.run[JsonRpcError | Closed](ex.call[Unit, Unit]("x", ()))
assert(result.isFailure)
```

After `Scope.run` exits, `endpointA.call` should fail with `Closed`.

### Test 39: callerRegistry drain (pending call sees Closed)

```scala
// Start a call that will never get a response (no handler on B, but block B's reply).
// Use a transport that stalls sends so the exchange call parks indefinitely.
// Then close the scope.
// The pending fiber should fail with Closed (not hang).
```

Needs a stalling transport: create a custom `JsonRpcTransport` where `send` parks on a `Channel` that is never drained. The call parks inside Exchange; scope closes; §6.4 step 5 closes Exchange (failing pending with Closed); §6.4 step 6 completes abortSignal with Closed. `Async.race` sees both arms fail with Closed; caller gets `Abort[Closed]`.

### Test 40: callerRegistry empty after normal completion

After `endpointA.call(...)` returns successfully, verify via internal access (test-only) that `callerRegistry.size == 0`. If direct field access is not exposed, infer indirectly by observing that `awaitDrain` returns immediately.

### Test 41: callerRegistry empty after fiber interrupt

```scala
val fiber = Fiber.initUnscoped(endpointA.call[AddReq, AddResp]("add", AddReq(1, 2)))
// Wait until the call is registered (id assigned) by waiting for a write on the transport.
fiber.interrupt
untilTrue(fiber.done)
// Now verify registry is empty: awaitDrain returns immediately.
endpointA.awaitDrain
```

### Test 42: transport closed mid-call

Use a custom transport where `incoming` ends (closes) after the first frame. Start a call; close the transport incoming stream while call is pending. Assert `Abort[Closed]`.

### Test 43: awaitDrain

Start 5 concurrent calls; wait for all to resolve; then call `awaitDrain`. Assert it returns without blocking. Conversely: start a call in a fiber, don't provide a response, then call `awaitDrain` concurrently; verify `awaitDrain` does not return until the call resolves.

### Test 44: late reply for cancelled outbound is silently dropped

```scala
// Start a call; get the id from the transport frame.
// Interrupt the caller fiber (simulates cancel).
// Feed a response for that id via the transport.
// Assert: no exception; exchange still works for subsequent calls.
```

This is Exchange's behavior, verified at the engine level.

### Test 45: endpoint.cancel with no CancellationPolicy

Phase 4 `Config` uses `cancellation = Absent` as the Phase 4 default. Call `endpoint.cancel(id, Absent)`. The pending caller should fail with `Abort.fail(JsonRpcError.cancelled(Absent))` locally. Count frames on the transport: no additional frame should appear beyond the original request.

### Test 46: ExtrasEncoder.const propagates to receiver

```scala
val extras = Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("s1")))
endpointA.call[Unit, Unit]("ping", (), extras = ExtrasEncoder.const(extras))
// On side B, the handler's ctx.extras should be Present(extras).
```

Use a handler on B that captures `ctx.extras` via an `AtomicRef`.

### Tests 47-48: IdStrategy sequential ids

Create a counting custom transport that captures outbound JSON strings. Parse them with `Json.decode[JsonRpcEnvelope]` (or `codec.decode`). Extract the `id` field from each captured envelope. Assert ids are `JsonRpcId.Num(1)`, `JsonRpcId.Num(2)`, `JsonRpcId.Num(3)` for three sequential calls.

For Test 47 (`SequentialLong`) and Test 48 (`SequentialInt`): both produce the same wire shape (`Num`); the difference is the counter's underlying type (Long vs Int). The test verifies the sequence, not the type.

### Test 49: no writes after close

Use a counting transport wrapper with an `AtomicInt` write counter. After `endpoint.close()` (or scope exit), assert the counter does not increase even if `call` is attempted and fails.

### Test 50: Custom.next concurrency, no collisions

```scala
val counter = AtomicLong.init(0)
val strategy = IdStrategy.Custom(() => counter.getAndIncrement.map(n => JsonRpcId.Num(n)))
// Spin up 100 concurrent calls via Fiber.initUnscoped.
// Collect all ids from the transport frames.
// Assert ids.size == 100 and ids == ids.toSet (all distinct).
```

Use a counting transport that captures all outbound ids. `AtomicLong.getAndIncrement` is safe for concurrent calls.

---

## 10. Concerns and ambiguities

### 10.1 Type parameter count: verified correct

DESIGN §6.1 says `Exchange[JsonRpcId, OutboundReq, Structure.Value, String, Nothing, JsonRpcError]` with six names. The `Exchange.initUnscoped` factory (`Exchange.scala:168`) takes six type params: `[Id, Req, Resp, Wire, Event, E]`. The opaque type `Exchange[Req, Resp, Event, E]` has four params (Id and Wire erased). No discrepancy. The design's six-param notation refers to the factory's type parameters; the stored value has four.

### 10.2 notify bypasses Exchange.apply

DESIGN §6.1: "`notify` writes to the engine's writer channel directly, bypassing `Exchange.apply`." This means the engine writes `WriterMsg.SendEnvelope(JsonRpcEnvelope.Notification(...))` directly to the writer channel. No outbound id is allocated; no pending entry is created. No negative-id workaround is needed.

However, `ExtrasEncoder` on `notify` "receives a synthesized non-routing id" per DESIGN §6. In Phase 4 the simplest approach: for `notify`, call `extras(JsonRpcId.Num(-1))` (or any sentinel) to resolve the extras value. The resolved extras go into the envelope. No pending entry, no Exchange involvement.

### 10.3 handler-completion to Replying transition: timing

When `Fiber.initUnscoped(m.handle(...))` is called inside `decode`, the fiber's `onComplete` callback must be registered in the same `Sync` block to avoid a window where the fiber completes before the callback is registered. Use:

```scala
Sync.Unsafe.defer {
    val fiber = Fiber.initUnscoped(m.handle(params, ctx)) // Sync < Sync
    // register completion callback immediately (Sync.Unsafe.defer context)
    Fiber.initUnscoped must return Fiber; attach onComplete...
}
```

Actually `Fiber.initUnscoped` returns `Fiber[...] < (Sync & S)`. Inside `decode` (which is `Sync`), you cannot call `fiber.onComplete` (which is `< Sync`) with a Kyo chain - you need `Sync.Unsafe.evalOrThrow`. This is the same pattern Exchange's own readerLoop uses. The impl agent should follow `Exchange.scala:180` and `Promise.Unsafe.completeDiscard` patterns exactly.

### 10.4 Phase 4 UnknownMethodPolicy scope

IMPLEMENTATION.md line 263: Phase 4 ships `UnknownMethodPolicy.minimal` (requests -> MethodNotFound, notifications -> drop, no dollar-prefix override). DESIGN §9 defines `UnknownMethodPolicy.lsp` and `UnknownMethodPolicy.strict`. The Phase 4 skeleton must define `UnknownMethodPolicy.scala` with the `minimal` constant only; the `lsp` and `strict` constants come in Phase 7. The `Config` default in Phase 4 is `unknownMethod = UnknownMethodPolicy.minimal` (not `.lsp`).

### 10.5 abortSignal type

The DESIGN §6.1 shows `abortSignal: Promise[Nothing, JsonRpcError]`. In Kyo, `Promise[A, S]` where `S = Abort[E]` would be `Promise[JsonRpcError, Abort[Nothing]]` or similar. The correct type is `Promise[JsonRpcError, Any]` meaning a promise that produces `JsonRpcError` on success (the "abort" case is carried as a success value here, and the caller does `abortSignal.safe.get.map(Abort.fail(_))`). Alternatively use `Fiber.Promise[Nothing, JsonRpcError]` which is the type alias for `Promise[JsonRpcError, Any]`.

Verify against `Fiber.scala:449`:

```scala
def init[E, A](using Frame): Promise[E, A] < Sync
```

`Promise[E, A]` is `Fiber[A, Abort[E] & S]` (opaque). For `abortSignal` the intent is: "when completed with a `JsonRpcError`, the caller learns the call was aborted." Use `Promise.init[Nothing, JsonRpcError]` which gives `Promise[JsonRpcError, Any]`. The caller does:

```scala
abortSignal.safe.get.map(e => Abort.fail[JsonRpcError](e))
```

This resolves the `JsonRpcError` from the promise's success channel and re-raises it as an `Abort[JsonRpcError]` for `Async.race`.

### 10.6 extras resolution: timing and idSignal

DESIGN §6.1 step 2: "Compute `extras` once via `ExtrasEncoder(placeholder)`." The purpose is to snapshot the extras value for `CallerInfo` without waiting for the real id. For Phase 4, `ExtrasEncoder.empty` always returns `Absent` and `ExtrasEncoder.const(v)` always returns `Present(v)` regardless of id. So the "placeholder" approach works. For the actual envelope, the extras closure is called again with the real id inside the `encode` callback (where the real id is known). The `CallerInfo.extras` snapshot uses the placeholder call.

### 10.7 Config defaults for Phase 4

IMPLEMENTATION.md line 263: "Config(cancellation = Absent, progress = Absent, gate = Absent, unknownMethod = UnknownMethodPolicy.minimal)." But DESIGN §6 shows the final defaults as `cancellation = Present(CancellationPolicy.lsp)` etc. The Phase 4 Config must use the conservative Absent defaults to avoid implementing Phase 5/6/7 logic. The Config's field definitions (types, names) must match DESIGN §6 exactly so Phase 5/6/7 can modify them without API breakage.

### 10.8 Async.race in Sync-only decode callback

The `decode` callback is `Sync` only. The engine does NOT call `Async.race` inside `decode`. The race happens in `endpoint.call` (outside the decode callback). Inside `decode`, the engine forks fibers (via `Fiber.initUnscoped`, which is Sync) and registers completion callbacks (via `Sync.Unsafe.defer`). The actual parking happens in `endpoint.call`'s `Async.race` expression, which runs on the caller's fiber.

### 10.9 writer fiber: Async effects allowed

The writer fiber runs as a separate fiber (forked via `Fiber.initUnscoped`). It can do `Async` work: `writerChannel.take` (Async), `transport.send` (Async). It is NOT constrained to Sync.

### 10.10 inMemory transport for tests

`JsonRpcTransport.inMemory(using Frame)` is available (Phase 3 output, confirmed at `JsonRpcTransport.scala:28`). It takes no capacity argument in the default overload. Use this for all Phase 4 tests.

### 10.11 `Async.race` semantics: first to complete wins

`Async.race` (DESIGN §7, outbound cancel step 5; Async.scala:243) completes when the FIRST leg finishes and interrupts all others. For the engine, "first" can be the abort arm (cancel signal) or the exchange arm (response). Either completing cleanly ends the race. If the abort arm fails with `JsonRpcError`, that error surfaces as `Abort[JsonRpcError]`; if the exchange arm fails with `Closed`, that surfaces as `Abort[Closed]`. Both are in the declared effect row `Abort[JsonRpcError | Closed]`.
