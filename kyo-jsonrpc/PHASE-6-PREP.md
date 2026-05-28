# kyo-jsonrpc Phase 6 Prep

Phase 6 implements `ProgressPolicy` and all engine wiring: `callWithProgress`, `callPartialResults[T]`,
`subscribeProgress`, `unsubscribeProgress`, `ctx.progress`, monotonicity enforcement, and
post-handler `progressSink` invalidation. 14 tests in `ProgressPolicyTest.scala`.

---

## 1. ProgressPolicy verbatim definition

Replace the Phase 4 skeleton (`sealed trait ProgressPolicy private[kyo]`) with the full case class.
The DESIGN.md §8 merge helper uses `groupBy`/`last`; the prep below uses a simpler last-write-wins
`Chunk` concatenation that avoids `groupBy` (which returns a `Map`, requiring `toChunk` back). Both
are correct; the simpler form is preferred per `feedback_code_quality`.

```scala
package kyo

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Structure
import kyo.Sync

final case class ProgressPolicy(
    progressMethod:       String,
    extractInboundToken:  Structure.Value => (Maybe[Structure.Value] < Sync),
    extractRequestToken:  Structure.Value => (Maybe[Structure.Value] < Sync),
    stampOutboundToken:   (Structure.Value, Structure.Value) => (Structure.Value < Sync),
    encodeProgressParams: (Structure.Value, Structure.Value) => (Structure.Value < Sync),
    enforceMonotonic:     Boolean
) derives CanEqual

object ProgressPolicy:
    import Structure.Value.{Record, Null}

    // Field lookup in a Record; Absent for non-records or missing keys.
    // Private inline: not a public API, inlined at each policy lambda.
    private inline def field(v: Structure.Value, name: String): Maybe[Structure.Value] =
        v match
            case Record(fields) =>
                Maybe.fromOption(fields.iterator.collectFirst { case (k, x) if k == name => x })
            case _ => Absent

    // Merge two Records: b's keys win on collision (last-write-wins via Chunk concatenation).
    private inline def merge(a: Structure.Value, b: Structure.Value): Structure.Value =
        (a, b) match
            case (Record(af), Record(bf)) => Record(af ++ bf)
            case (Record(_), other)       => other
            case (_, Record(bf))          => Record(bf)
            case (_, other)               => other

    val lsp: ProgressPolicy = ProgressPolicy(
        progressMethod       = "$/progress",
        extractInboundToken  = p => Sync.defer(field(p, "token")),
        extractRequestToken  = p => Sync.defer(field(p, "workDoneToken")),
        stampOutboundToken   = (p, t) => Sync.defer(merge(p, Record(Chunk("workDoneToken" -> t)))),
        encodeProgressParams = (t, v) => Sync.defer(Record(Chunk("token" -> t, "value" -> v))),
        enforceMonotonic     = false
    )

    val mcp: ProgressPolicy = ProgressPolicy(
        progressMethod      = "notifications/progress",
        extractInboundToken = p => Sync.defer(field(p, "progressToken")),
        extractRequestToken = p =>
            Sync.defer(field(p, "_meta").map(meta => field(meta, "progressToken")).getOrElse(Absent)),
        stampOutboundToken = (p, t) =>
            Sync.defer:
                val existingMeta = field(p, "_meta").getOrElse(Record(Chunk.empty))
                val newMeta      = merge(existingMeta, Record(Chunk("progressToken" -> t)))
                merge(p, Record(Chunk("_meta" -> newMeta))),
        encodeProgressParams = (t, v) =>
            Sync.defer(merge(Record(Chunk("progressToken" -> t)), v)),
        enforceMonotonic = true
    )
end ProgressPolicy
```

Implementation note: `private inline def field(...)` returns `Maybe[Structure.Value]` directly,
not `Maybe[Structure.Value] < Sync`. Each policy lambda wraps the call in `Sync.defer`. Because
`field` and `merge` are `inline`, there is no closure allocation and they are valid inside
`Sync.defer { ... }` blocks.

---

## 2. New file: `internal/ProgressEngine.scala`

The four stub bodies in `JsonRpcEndpoint.scala` delegate to `impl.callWithProgress`, etc.
Phase 6 puts the real logic in a new file `internal/ProgressEngine.scala` and wires it into
`JsonRpcEndpointImpl`. Keeping the progress logic separate mirrors how `CancellationPolicy`
lives in its own companion without polluting the 472-line impl file.

`JsonRpcEndpointImpl` gains two new fields (passed through the constructor and wired in `initEngine`):

```scala
private val progressPolicy:  Maybe[ProgressPolicy],
private val progressStreams:  ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]
```

---

## 3. Engine wiring touchpoints in `JsonRpcEndpointImpl.scala`

### 3a. Constructor / init

Add to `JsonRpcEndpointImpl` constructor params:

```scala
private val progressPolicy:  Maybe[ProgressPolicy],
private val progressStreams:  ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]
```

In `initEngine`, before the `new JsonRpcEndpointImpl(...)` call:

```scala
// Unsafe: ConcurrentHashMap mirrors Exchange's own internal pattern
val progressStreams = new ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]()
```

Pass `progressPolicy = config.progress` and `progressStreams = progressStreams` to the constructor.

### 3b. `finalizer` step 7 (currently a no-op comment)

Replace the `// Step 7: no progressStreams in Phase 4 (no-op)` comment with:

```scala
// Step 7: close all progress channels so stream consumers see Closed
// Unsafe: bulk-close from outside the originating fibers
Sync.Unsafe.defer {
    progressStreams.forEach { (_, ch) =>
        discard(ch.unsafe.close()(using AllowUnsafe.embrace.danger))
    }
    progressStreams.clear()
}.andThen {
    // Step 8: interrupt all pendingInbound handler fibers (existing code follows)
```

### 3c. `decodeCallback`: step 1b intercept (progress notifications)

In the `Notification` branch of `decodeCallback`, BEFORE the `methodMap.get(method)` lookup:

```scala
case JsonRpcEnvelope.Notification(method, params, extras) =>
    progressPolicy match
        case Present(policy) if method == policy.progressMethod =>
            // Step 1b: route progress notification to the registered channel
            val paramsVal = params.getOrElse(Structure.Value.Null)
            policy.extractInboundToken(paramsVal).map { tokenOpt =>
                tokenOpt match
                    case Absent => Exchange.Message.Skip  // unknown token: silent drop
                    case Present(token) =>
                        // Unsafe: offer to progress channel inside Exchange decode callback
                        Sync.Unsafe.defer {
                            Maybe(progressStreams.get(token)) match
                                case Absent     => ()  // token not registered: silent drop
                                case Present(ch) =>
                                    // Unsafe: non-blocking offer; backpressure not applied here
                                    discard(ch.unsafe.offer(paramsVal)(using AllowUnsafe.embrace.danger, frame))
                        }.andThen(Exchange.Message.Skip)
            }

        case _ =>
            methodMap.get(method) match
            // ... existing notification dispatch ...
```

### 3d. `decodeCallback`: `HandlerCtx` construction for requests

In the `Request` branch, when building `HandlerCtx`, replace `Absent` for `progressSink` with:

```scala
val progressSinkOpt: Maybe[Structure.Value => Unit < (Async & Abort[Closed])] =
    progressPolicy match
        case Absent => Absent
        case Present(policy) =>
            val paramsVal  = params.getOrElse(Structure.Value.Null)
            val tokenOpt   = policy.extractRequestToken(paramsVal)
            // tokenOpt is < Sync; we are inside Sync.Unsafe.defer so we can run it:
            // Unsafe: run Sync effect inside decode callback context
            val token = tokenOpt.eval(using frame)   // safe: Sync.defer wraps the whole block
            token match
                case Absent => Absent
                case Present(t) =>
                    // Per-invocation monotonicity ref; lives inside closure (not global)
                    // Unsafe: AtomicRef.Unsafe.init inside Sync.Unsafe.defer block
                    val monoRef = AtomicRef.Unsafe.init[Maybe[Double]](Absent)(using AllowUnsafe.embrace.danger).safe
                    val sink: Structure.Value => Unit < (Async & Abort[Closed]) =
                        value =>
                            // Check handler state atomically before emitting
                            Sync.defer(Maybe(pendingInbound.get(id))).map {
                                case Present(_: InboundEntry.Running) | Absent =>
                                    // Absent means Cancelled; Running means still active
                                    val proceed: Boolean < Sync =
                                        if policy.enforceMonotonic then
                                            val newPct: Maybe[Double] =
                                                value match
                                                    case Structure.Value.Record(fields) =>
                                                        fields.iterator.collectFirst {
                                                            case ("progress", Structure.Value.Num(n)) => n.toDouble
                                                        }.fold[Maybe[Double]](Absent)(Present(_))
                                                    case _ => Absent
                                            newPct match
                                                case Absent => Sync.defer(true)  // no progress field: always emit
                                                case Present(newVal) =>
                                                    monoRef.update { current =>
                                                        current match
                                                            case Absent => Present(newVal)
                                                            case Present(prev) =>
                                                                if newVal > prev then Present(newVal) else current
                                                    }.andThen {
                                                        monoRef.get.map {
                                                            case Present(v) => v == newVal  // we won the CAS
                                                            case Absent     => false
                                                        }
                                                    }
                                        else
                                            Sync.defer(true)
                                    proceed.map {
                                        case false => Kyo.unit
                                        case true =>
                                            policy.encodeProgressParams(t, value).map { encoded =>
                                                val env = JsonRpcEnvelope.Notification(
                                                    policy.progressMethod,
                                                    Present(encoded),
                                                    extras  // capture inbound extras (C1 fix)
                                                )
                                                Abort.run[Closed](writerChannel.put(WriterMsg.SendEnvelope(env))).unit
                                            }
                                    }
                                case Present(_: InboundEntry.Replying) |
                                     Present(_: InboundEntry.Cancelled) =>
                                    Kyo.unit  // handler done: silent no-op
                            }
                    Present(sink)

val ctx = new HandlerCtx(cancelledUnsafe.safe, Present(id), extras, progressSinkOpt)
```

Implementation note: `tokenOpt.eval(using frame)` works here because `tokenOpt` is the result of
`policy.extractRequestToken(paramsVal)` which returns `Maybe[Structure.Value] < Sync`, and the
enclosing block is a `Sync.Unsafe.defer` context. Phase 6 impl must verify this compiles; if
`eval` requires `AllowUnsafe`, use `Sync.Unsafe.run` or restructure with `.map`.

### 3e. Monotonicity CAS detail

The `monoRef.update` call above is a kyo `AtomicRef` safe update (takes `Maybe[Double] => Maybe[Double]`).
It retries if the underlying CAS fails. The "did we win?" check reads back the ref. This is NOT a true
CAS-and-check (two operations, not one). The correct approach for "only emit if I updated":

```scala
// Pattern: try to CAS from old to new; if ref ends up at newVal after update, we wrote it.
// AtomicRef.update is a spin-CAS loop that always succeeds eventually.
// For monotonicity we need: if we found non-monotonic, we MUST NOT update, just return false.
```

Because `AtomicRef.update` always applies the function (no conditional abort), the implementation
must use `AtomicRef.compareAndSet` or read-then-update pattern. Use the following pattern:

```scala
monoRef.get.map { current =>
    current match
        case Present(prev) if newVal <= prev =>
            false  // non-monotonic: drop
        case _ =>
            monoRef.update { c =>
                c match
                    case Present(prev) if newVal <= prev => c  // lost race: another fiber updated
                    case _                               => Present(newVal)
            }.andThen {
                monoRef.get.map {
                    case Present(v) => v == newVal
                    case Absent     => false
                }
            }
}
```

This is still a read-then-update pattern (not lock-free CAS in one step). Under concurrent calls
from the same handler (Test 78), the race window is: both `ctx.progress(10)` and `ctx.progress(5)`
read `Absent`. Both try to update. The `update` lambda for value 5 will update to `Present(5)`.
The `update` lambda for value 10 will update to `Present(10)` (if it reads `Present(5)`, it's still
monotonic). Both may end up emitted if they interleave at exactly the right point.

Per DESIGN.md §8: "If non-monotonic or CAS fails (concurrent call won), log and drop." The correct
implementation is:

1. `monoRef.get` reads current value.
2. If `newVal <= current`, drop immediately (no-op).
3. Otherwise call `monoRef.compareAndSet(current, Present(newVal))`.
4. If CAS returns true, proceed to emit. If false, drop (another concurrent `ctx.progress` won).

This requires `AtomicRef` to expose `compareAndSet`. Verify `kyo.AtomicRef` has this method in the
current codebase. If it does not, use `AtomicRef.update` with a flag captured via a mutable local
(inside `Sync.Unsafe.defer`) as a fallback.

---

## 4. `callWithProgress` real body

Replace the stub in `JsonRpcEndpointImpl`:

```scala
def callWithProgress[In: Schema, Out: Schema](
    method: String,
    params: In,
    extras: ExtrasEncoder
)(using Frame): JsonRpcEndpoint.Pending[Out] < (Async & Abort[JsonRpcError | Closed]) =
    progressPolicy match
        case Absent =>
            Abort.fail(JsonRpcError.internalError(
                "progress not configured: pass Config.progress = Present(ProgressPolicy.lsp / .mcp)"
            ))
        case Present(policy) =>
            // Allocate token and register progress channel
            // Unsafe: ConcurrentHashMap put inside Sync.Unsafe.defer
            Sync.Unsafe.defer {
                val token     = Structure.Value.Str(java.util.UUID.randomUUID().toString)
                val tokenVal  = token
                Channel.Unsafe.init[Structure.Value](64)(using AllowUnsafe.embrace.danger).safe
            }.map { progChan =>
                // stamp token into outbound params
                policy.stampOutboundToken(Structure.encode[In](params), progChan).flatMap { ... }
            }
```

Simpler approach: use a counter-based token (avoid UUID allocation on every call):

```scala
case Present(policy) =>
    // Unsafe: channel init for progress side-channel
    Sync.Unsafe.defer {
        val tokenStr  = java.util.UUID.randomUUID().toString
        val tokenVal  = Structure.Value.Str(tokenStr)
        val progChan  = Channel.Unsafe.init[Structure.Value](64)(using AllowUnsafe.embrace.danger).safe
        (tokenVal, progChan)
    }.map { (tokenVal, progChan) =>
        policy.stampOutboundToken(Structure.encode[In](params), tokenVal).map { stampedParams =>
            // Unsafe: register in progressStreams before issuing call
            Sync.Unsafe.defer(discard(progressStreams.put(tokenVal, progChan))).andThen {
                Scope.acquireRelease(Kyo.unit)(_ =>
                    Sync.Unsafe.defer {
                        progressStreams.remove(tokenVal)
                        discard(progChan.unsafe.close()(using AllowUnsafe.embrace.danger))
                    }
                ).andThen {
                    Fiber.Promise.init[JsonRpcError, Any].map { abortSignal =>
                        // issue the call (reuse call's impl logic minus extras re-stamp)
                        // ... (see §4 full expansion below)
                        val resultFiber: Out < (Async & Abort[JsonRpcError | Closed]) = call[Structure.Value, Out](
                            method,
                            stampedParams,  // NOTE: call[In,Out] takes In; need raw call path
                            extras
                        )
                        val pending = new JsonRpcEndpoint.Pending[Out](
                            id       = /* not yet known; use sentinel or restructure */,
                            result   = resultFiber,
                            progress = progChan.streamUntilClosed(),
                            cancel   = Kyo.unit  // Phase 5 wires this via cancel(id)
                        )
                        pending
                    }
                }
            }
        }
    }
```

The tricky part: `Pending[Out].id` requires the Exchange-assigned id, which is only known after
`exchange(req)` runs the encode callback. The existing `call` path captures it via `idSignal`
(a `Promise.Unsafe` completed inside the encode callback). Phase 6 must re-use that mechanism:
after `call[In,Out]` is forked (or the exchange called), read `idSignal.poll()` to get the id.

Because `callWithProgress` must return `Pending[Out]` (including the id) before the call
completes, the id is ONLY known after the encode callback runs, which happens before the first
`send`. Since `exchange(req)` is synchronous up to `send`, the id is available once the exchange
fiber starts. Use `idSignal.get` (awaits resolution) and map it to construct the `Pending`.

Concrete implementation strategy:

1. Allocate `tokenVal`, `progChan`.
2. Register `progressStreams[tokenVal] = progChan`.
3. Stamp params via `policy.stampOutboundToken`.
4. Call the internal `call` machinery (not the public `call[In,Out]` which re-encodes) with
   `stampedParams` as a `Structure.Value` (use `call[Structure.Value, Out]` with an identity schema).
5. The `idSignal` from step 4 gives us the id once known (awaited via `idSignal.safe.get`).
6. On call completion (either success or failure), close `progChan` and remove from `progressStreams`.
7. Return `Pending[Out](id=..., result=..., progress=progChan.streamUntilClosed(), cancel=cancel(id))`.

Step 4 is blocked by the fact that `call[In, Out]` takes `In: Schema` and does `Structure.encode[In](params)`.
For `callWithProgress`, params are already encoded as `Structure.Value`; re-use `Structure.Value`'s own
`Schema` (which is the identity). Alternatively, refactor `call` to accept pre-encoded params
(`Maybe[Structure.Value]`) in a private overload. Phase 6 impl should use the private overload to
avoid double-encoding.

---

## 5. `callPartialResults[T]` real body

```
callPartialResults[In, T](method, params, extras):
    1. Same as callWithProgress up to registering progChan.
    2. Fork the underlying call as a background fiber (don't await the result in the returned stream).
    3. Return a Stream[T, ...] that:
       a. reads from progChan (each value is a progress notification params)
       b. decodes each via Structure.decode[T]
       c. terminates when progChan closes

    On final Response arrival (in decodeCallback, Response branch):
    - If result = Absent: close progChan normally (stream closes).
    - If result = Present(sv): decode sv as T, emit via a one-shot channel put, then close progChan.

    The challenge: the Response branch in decodeCallback currently calls
    Exchange.Message.Response(id, sv), letting Exchange complete the pending promise.
    For partial results, the stream is the primary API; the "result" field of Pending is less
    relevant. But the decodeCallback must still return Exchange.Message.Response so Exchange
    completes its pending promise (which the background fiber awaits to know the call is done).
```

Concrete approach:

1. In `decodeCallback`'s `Response(id, result, Absent, _)` branch, check if `progressStreams`
   contains an entry for a token that was registered for this `id`. If yes, and `result = Present(sv)`,
   offer `sv` to `progChan` before closing it, then close the chan.
   If `result = Absent`, just close the chan.
2. Then return `Exchange.Message.Response(id, result.getOrElse(Structure.Value.Null))` as usual.

To make step 1 work, the impl needs a reverse map `outboundIdToToken: ConcurrentHashMap[JsonRpcId, Structure.Value]`.
Populated in the encode callback when `callPartialResults` registers the token; cleared on response.

This is the cleanest approach: minimal coupling, no changes to Exchange's response path.

---

## 6. `subscribeProgress` real body

```scala
def subscribeProgress(token: Structure.Value)(using Frame): Stream[Structure.Value, Async & Abort[Closed]] =
    progressPolicy match
        case Absent =>
            Stream(Abort.fail[Closed](Closed("progress not configured", initFrame)))
        case Present(_) =>
            // Unsafe: channel init, ConcurrentHashMap put
            Sync.Unsafe.defer {
                val ch = Channel.Unsafe.init[Structure.Value](64)(using AllowUnsafe.embrace.danger).safe
                progressStreams.putIfAbsent(token, ch)
                Maybe(progressStreams.get(token))
            }.map {
                case Present(ch) => ch.streamUntilClosed()
                case Absent      => Stream(Kyo.unit)  // shouldn't happen; putIfAbsent always returns
            }
```

Note: `putIfAbsent` returns the existing value if already present. If another caller already
registered this token (e.g. `callWithProgress` registered it first), reuse that channel. This
ensures `subscribeProgress(token)` can coexist with `callWithProgress` using the same token.
In practice, tokens from `callWithProgress` are engine-allocated (UUIDs), and tokens from
`subscribeProgress` come from user code (`window/workDoneProgress/create` token). No collision
expected, but reuse-if-present is correct.

---

## 7. `unsubscribeProgress` real body

```scala
def unsubscribeProgress(token: Structure.Value)(using Frame): Unit < Async =
    Sync.Unsafe.defer {
        Maybe(progressStreams.remove(token)) match
            case Absent => ()
            case Present(ch) =>
                // Unsafe: close channel from outside the stream consumer
                discard(ch.unsafe.close()(using AllowUnsafe.embrace.danger))
    }
```

---

## 8. `Running -> Replying` invalidation atomicity

The `progressSink` closure (section 3d) reads `pendingInbound.get(id)` at each invocation.
The transition from `Running` to `Replying` is a `ConcurrentHashMap.replace(id, running, replying)`
CAS in the `onComplete` hook (already in Phase 4 code at line 352). After that CAS succeeds,
any subsequent `ctx.progress(v)` call sees `Replying` and returns `Kyo.unit` without wire
emission. This is correct and sufficient because:

- `ConcurrentHashMap.get` is linearizable with `replace`.
- The closure reads `pendingInbound.get(id)` on each call, not a cached snapshot.
- The `Replying` entry remains in the map until the writer fiber dequeues and removes it
  (`pendingInbound.remove(id)` in `SuppressIfCancelled` handling). During that window the
  closure still sees `Replying` and no-ops correctly.

If the entry has been removed entirely (possible if the writer already processed `SuppressIfCancelled`),
`pendingInbound.get(id)` returns `null`, mapped to `Absent` via `Maybe(...)`. The closure's
match arm `Present(_: InboundEntry.Running) | Absent` would emit. To avoid this:

```scala
Sync.defer(Maybe(pendingInbound.get(id))).map {
    case Present(_: InboundEntry.Running) => ... proceed
    case _ => Kyo.unit  // Replying, Cancelled, or removed: no-op
}
```

The `Absent` arm must return `Kyo.unit` (no-op), not emit. Update section 3d's sink accordingly.

---

## 9. Test data and anti-flakiness guide

All 14 tests live in `kyo-jsonrpc/shared/src/test/scala/kyo/ProgressPolicyTest.scala`.
Test class: `class ProgressPolicyTest extends Test`.

Use the `mkEndpoints` helper pattern from `JsonRpcEndpointTest` but parameterized with a config:

```scala
private def mkEndpoints(
    methodsA: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
    methodsB: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
    configA: JsonRpcEndpoint.Config = JsonRpcEndpoint.Config(),
    configB: JsonRpcEndpoint.Config = JsonRpcEndpoint.Config()
)(using Frame): (JsonRpcEndpoint, JsonRpcEndpoint) < (Sync & Async & Scope) =
    JsonRpcTransport.inMemory.map { (ta, tb) =>
        JsonRpcEndpoint.init(ta, methodsA, configA).map { endpointA =>
            JsonRpcEndpoint.init(tb, methodsB, configB).map { endpointB =>
                (endpointA, endpointB)
            }
        }
    }
```

For tests needing progress, pass `config = JsonRpcEndpoint.Config(progress = Present(ProgressPolicy.lsp))`.

### Test 65: basic LSP callWithProgress

Use `Latch` (or `Fiber.Promise`) to synchronize the handler's three progress emissions with A's
stream consumption. Pattern:

```scala
val progressLatch = Latch.init(1)  // handler waits before returning
val progMethod = JsonRpcMethod[..., ..., ...]("longTask") { (_, ctx) =>
    ctx.progress(beginValue).andThen {
        ctx.progress(reportValue).andThen {
            ctx.progress(endValue).andThen {
                progressLatch.await.andThen(AddResp(42))
            }
        }
    }
}
// A: callWithProgress; collect 3 values from pending.progress; then release latch
```

Do NOT use `Async.sleep`; use latches for deterministic ordering.

### Test 66: token stamping

A calls `callWithProgress`. B's handler receives params; asserts `params.field("workDoneToken") = Present(...)`.
Use a `Fiber.Promise` to capture params from inside B's handler and assert outside.

### Test 67: callPartialResults, three progress + empty-result terminator

B's handler:
1. Calls `ctx.progress(v1)`, `ctx.progress(v2)`, `ctx.progress(v3)`.
2. Returns `Structure.Value.Null` (empty-result: the engine sends response with `result = Absent`
   by... wait: a `Structure.Value.Null` result is NOT `result = Absent`).

Per DESIGN.md §8: "stream closes when the peer sends the final response with `result = Absent`
(the LSP spec: result field omitted on a partial-result terminator)."

So the handler must somehow signal "this is a partial-result request, reply with no result."
One option: `callPartialResults` does NOT use the normal `call` response path but instead
the engine intercepts the final Response in `decodeCallback` and closes the progChan.

For tests: configure B to send three `$/progress` notifications then an explicit empty-result
response. Since this is a test, the cleanest approach is to have B's handler call `ctx.progress`
three times then `Abort.fail(JsonRpcError.internalError("no result"))` is NOT the right pattern.

Per DESIGN.md: "callPartialResults[T]: returns Stream[T, ...]. Each chunk delivered via $/progress
is decoded as T and emitted. The stream closes when the peer sends the final response with result = Absent."

The engine itself must handle this: `callPartialResults` calls the transport and the server sends
normal progress notifications + then a response with no `result` field. B's handler returning
`Structure.Value.Null` would produce a response with `result = Present(Null)` (not absent), which
per DESIGN.md becomes the last chunk.

For Test 67 specifically: B's handler calls ctx.progress three times and returns
`Structure.Value.Null`. A expects three progress values as stream chunks from PROGRESS notifications,
then the stream closes. The `result = Present(Null)` would be an additional chunk per DESIGN §8:
"any non-absent final result is decoded as the last T chunk before closing."

So Test 67 flow: 3 progress notifications + 1 non-absent final result = 4 stream chunks total,
OR B needs a way to return `result = Absent`. Check if `Structure.Value.Null` maps to
`result = Present(Null)` or `result = Absent` in the encoder. Per Phase 1 steers:
`JsonRpcResponse.success(id, result: Structure.Value)` takes a `Structure.Value`, so `Null` produces
`Present(Null)`. B's handler cannot return `result = Absent` via the normal path.

Resolution: for Test 67, B emits 3 `ctx.progress` calls, and A's stream gets 3 values from
progress + potentially 1 last chunk from the non-absent result. Assert 4 total, OR structure the
test to match what the engine actually does.

Test 67 should assert whatever the engine's actual behavior is per the implemented spec.

### Test 68: MCP policy

Config both endpoints with `progress = Present(ProgressPolicy.mcp)`. B's handler reads
`params._meta.progressToken` and calls `ctx.progress`. A uses `callWithProgress` with MCP
policy and observes the progress value.

### Test 69: subscribeProgress out-of-band

```scala
val token = Structure.Value.Str("my-token")
// B sends a progress notification manually (via B.notify)
val stream = a.subscribeProgress(token)
// Inject progress via b.notify("$/progress", progressParams):
b.notify("$/progress", lspProgressParams(token, value)).andThen {
    stream.take(1).map { chunks => assert(chunks == Chunk(expectedProgressParams)) }
}
```

### Test 70: unsubscribeProgress

After `subscribeProgress(token)`, call `a.unsubscribeProgress(token)`. Then verify the stream
is closed (`.run` completes without emitting more). Then send another progress notification and
assert no crash.

### Test 71: ctx.progress with progress = Absent (CDP shape)

Config both endpoints with `progress = Absent`. B's handler calls `ctx.progress(value)`.
Use a `CountingTransport` (already in `JsonRpcEndpointTest`) to assert the send count does
NOT increase after the handler runs. Assert `ctx.progress` returns `Unit` (no exception).

### Test 72: MCP monotonicity (10.0, 5.0 dropped, 20.0 passes)

Use MCP config. B's handler calls `ctx.progress` with values `{progress: 10.0}`,
`{progress: 5.0}`, `{progress: 20.0}`. A's `callWithProgress` stream must contain exactly
`{progress: 10.0}` and `{progress: 20.0}`. Assert stream length = 2 and exact values.

Use a latch to hold the handler until all three `ctx.progress` calls are made; then release
and collect the stream on A's side.

### Test 73: LSP non-monotonic (all three pass)

Same as Test 72 but with `ProgressPolicy.lsp` (enforceMonotonic = false). Assert stream
length = 3.

### Test 74: post-handler invalidation

B's handler:
1. Captures a `Fiber.Promise` (`holdLatch`).
2. Calls `ctx.progress(v1)` (assert wire emits).
3. Awaits `holdLatch`.
4. Returns a result (transitions Running -> Replying).

Test control:
1. Call `callWithProgress` from A.
2. Wait until `ctx.progress(v1)` is seen (via CountingTransport frame count).
3. Release `holdLatch` (handler completes, transitions Running -> Replying).
4. Wait for B to fully process the response (use `Async.sleep(10.millis)` is allowed here
   as a last resort, but prefer: await the result future from `callWithProgress`).
5. From outside the handler (using a captured `HandlerCtx`), call `ctx.progress(v2)`.
   Assert frame count did NOT increase (no wire emit).

To capture `HandlerCtx` from outside: store it in a `java.util.concurrent.atomic.AtomicReference`
inside the test.

### Test 75: unknown token silent drop

Send a `$/progress` notification with token `"unknown-xyz"` (not registered in progressStreams).
Assert: no crash, endpoint is still alive (send a normal call after and verify it succeeds).

Use `b.notify("$/progress", Structure.Value.Record(Chunk("token" -> Structure.Value.Str("unknown-xyz"), "value" -> ...)))`.

### Test 76: non-absent final result as last chunk

B's handler returns `AddResp(99)` (non-absent). A uses `callPartialResults[AddResp]`.
Assert stream emits `AddResp(99)` as the last (and only, if no progress calls) chunk.

### Test 77: extras propagation on progress notifications

Use MCP config. Inbound request has extras `Present(Record(Chunk("x-session-id" -> Str("s1"))))`.
B's handler calls `ctx.progress(v)`.
A's transport (CountingTransport or a custom capture transport) captures the emitted
`$/progress` (or `notifications/progress`) notification envelope. Assert `envelope.extras == Present(...)`.

Note: `extras` on the outbound envelope from `ctx.progress` comes from the inbound request's `extras`
(per C1 fix: "capture the inbound envelope's `extras` and re-emit on the notification"). This is
implemented in section 3d where `val env = JsonRpcEnvelope.Notification(..., extras = extras)`.
The `extras` in scope is the inbound request's extras captured in the decode callback's closure.

For this test to be observable: use a capturing transport that records sent envelopes:

```scala
class CapturingTransport(inner: JsonRpcTransport, val captured: java.util.concurrent.CopyOnWriteArrayList[JsonRpcEnvelope])
    extends JsonRpcTransport:
    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer(discard(captured.add(env))).andThen(inner.send(env))
    ...
```

### Test 78: concurrent monotonicity CAS

B's handler spawns two concurrent `ctx.progress(10)` and `ctx.progress(5)` fibers.
Assert via transport frame count that only 1 progress notification was emitted
(the monotonicity CAS drops the non-winner).

Use `Fiber.zip(ctx.progress(v1), ctx.progress(v2)).unit` from inside B's handler.
Then collect A's stream. The stream must have at most 1 element with value 10.

---

## 10. Concerns

### Concern 1: ProgressPolicy `derives CanEqual` will compile-fail if any field type lacks `CanEqual`

The policy contains function-typed fields (`Structure.Value => Maybe[...] < Sync`). `CanEqual`
derivation via `derives CanEqual` on a case class with function fields fails at compile time
because functions do not have `CanEqual` instances.

`CancellationPolicy` has the same shape and already uses `derives CanEqual` successfully (per the
Phase 5 source at `CancellationPolicy.scala`). Verify that `derives CanEqual` on a case class with
function fields works in this Scala version. If it does (because derivation is structural-equality
only and functions use reference equality), proceed. If it fails, remove `derives CanEqual` and
manually provide a `given CanEqual[ProgressPolicy, ProgressPolicy] = CanEqual.derived`.

### Concern 2: `callWithProgress` stub return type must not change

Current stub body:
```scala
Abort.fail(JsonRpcError.internalError("progress not configured: ..."))
```
This is `Nothing < (Async & Abort[JsonRpcError | Closed])`, which widens to
`JsonRpcEndpoint.Pending[Out] < (Async & Abort[JsonRpcError | Closed])`. The signature
must stay unchanged.

### Concern 3: `callPartialResults` stub return type must not change

Current stub body:
```scala
Stream(Abort.fail[JsonRpcError](JsonRpcError.internalError("progress not configured: ...")))
```
Signature: `Stream[T, Async & Abort[JsonRpcError | Closed]]`. Must stay unchanged.

### Concern 4: monotonicity uses `Double`, COMPLETENESS.md says `BigDecimal`

COMPLETENESS.md I14: "per-invocation `AtomicRef[Maybe[BigDecimal]]`". DESIGN.md §8 says "Double".
The prep sections above use `Double` to match DESIGN.md. If the impl agent sees `BigDecimal` in
IMPLEMENTATION.md line 367, use `BigDecimal` there. The critical point: whichever numeric type is
chosen, `Structure.Value.Num` carries a `BigDecimal` (check kyo-schema's `Structure.Value.Num`
definition). Extract with:
```scala
case Structure.Value.Num(n) => n  // n: BigDecimal
```
The comparison `newVal > prev` is valid for `BigDecimal`. Use `BigDecimal` in the `AtomicRef`
to avoid precision loss.

### Concern 5: `AtomicRef.compareAndSet` availability

The monotonicity CAS in section 3d requires `AtomicRef.compareAndSet(expect, update)`.
Verify this method exists on `kyo.AtomicRef` in the current codebase:
```
grep -rn "compareAndSet" kyo-core/shared/src/main/scala/kyo/
```
If absent, use the read-then-update-check pattern described in section 3e, with the understanding
that under concurrent calls, the monotonicity guarantee may admit a small window. Per Test 78
the assertion is "only the larger value emits" which the `update`-then-check pattern satisfies
for sequential calls; for truly concurrent calls, only `compareAndSet` guarantees it. If
`compareAndSet` is missing, file a note in STEERING.md under "Findings from impl."

### Concern 6: `outboundIdToToken` reverse map for `callPartialResults`

Section 5 introduces `outboundIdToToken: ConcurrentHashMap[JsonRpcId, Structure.Value]` to close
the progress channel from the decodeCallback's Response branch. This requires one more field on
`JsonRpcEndpointImpl` and initialization in `initEngine`. The impl agent must add this.

### Concern 7: `Config.progress` default value

IMPLEMENTATION.md line 375: "`update Config default to progress = Present(ProgressPolicy.lsp)`".
This would mean ALL endpoints default to LSP progress behavior, which is unexpected for CDP/MCP
users who explicitly pass `Config()`. The `JsonRpcEndpoint.Config` currently has `progress = Absent`.
Given that DESIGN.md §19 decision 2 says "Policies are `Maybe`d... CDP uses `Absent`", the correct
default is `Absent`. The IMPLEMENTATION.md line 375 note conflicts with the design decision; follow
the design. Keep `progress = Absent` as the default. The impl agent must NOT change the default.

### Concern 8: `subscribeProgress` and `callWithProgress` share `progressStreams` map

Both write to the same `ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]`.
`putIfAbsent` vs `put` semantics matter: `callWithProgress` allocates a new channel and `put`s
it (token is engine-allocated UUID, guaranteed unique). `subscribeProgress` uses `putIfAbsent`
to avoid overwriting an existing channel. These are compatible. The impl agent must use `put` in
`callWithProgress` and `putIfAbsent`/`computeIfAbsent` in `subscribeProgress`.

---

## 11. Files to produce / modify

**Produce:**
- `kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala` (replace skeleton)
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala` (optional: can inline into impl)
- `kyo-jsonrpc/shared/src/test/scala/kyo/ProgressPolicyTest.scala` (14 tests: Tests 65-78)

**Modify:**
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: add `progressPolicy`,
  `progressStreams`, `outboundIdToToken` fields; replace 4 stub bodies; wire step 1b in decodeCallback;
  update HandlerCtx construction; update finalizer step 7; update `initEngine`.

**Do not modify:** DESIGN.md, IMPLEMENTATION.md, STEERING.md, audit files, build.sbt beyond what
Phase 0 already wired.

---

## 12. Verification commands

Per STEERING.md findings: JVM target is unsuffixed.

```
sbt 'kyo-jsonrpc/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpc/testOnly *ProgressPolicyTest' 2>&1 | tail -20
```

Cross-platform (supervisor runs at phase boundary):
```
sbt 'kyo-jsonrpcJS/Test/compile' 2>&1 | tail -20
sbt 'kyo-jsonrpcNative/Test/compile' 2>&1 | tail -20
```

Convention sweep (run after writing all files):
```
grep -rn $'\xe2\x80\x94' kyo-jsonrpc/shared/src  # must be 0
grep -rn 'AllowUnsafe' kyo-jsonrpc/shared/src     # each occurrence must have // Unsafe: above it
grep -rn ': Option\[' kyo-jsonrpc/shared/src      # must be 0
grep -rn ';$' kyo-jsonrpc/shared/src              # must be 0
grep -rn 'asInstanceOf' kyo-jsonrpc/shared/src    # must be 0
```
