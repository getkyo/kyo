# kyo-jsonrpc design

A layered design that maps the three target consumers (LSP, MCP, CDP) onto a single shared engine without forcing any of them to wrap. Synthesised from the three per-consumer reports in `research/{LSP,MCP,CDP}.md`.

The non-obvious decision driving everything: **the engine is a thin layer over the existing `kyo.Exchange` primitive in kyo-core, not a reimplementation**. Exchange already does id-keyed multiplexing, reader-fiber routing, push events with backpressure, transport-error propagation, and orderly close. kyo-jsonrpc adds: bidirectionality (Exchange is one-direction by default), JSON-RPC envelope conventions, typed method dispatch, cancellation, and progress. Everything else is policy.

---

## 1. The three consumers compared

| Concern | MCP (2025-06-18) | CDP | LSP 3.17 |
|---|---|---|---|
| Wire envelope | strict JSON-RPC 2.0, no batches, `id` non-null, ids unique-per-session | NOT 2.0: no `jsonrpc` field, extra top-level `sessionId`, no `error.data` | strict JSON-RPC 2.0 |
| Transport | Streamable HTTP (POST/GET-SSE) OR newline-delimited stdio | WebSocket (single duplex stream) | stdio with `Content-Length:` framing |
| Bidirectional? | yes (sampling/elicitation/roots/list/ping S→C) | one-way RPC; events S→C as notifications | yes, fully symmetric |
| Id namespace | per-session, never null, never reused | global per-connection `Int` counter spanning sessions | per-direction (each side independent) |
| Cancellation | `notifications/cancelled` → **no reply ever** | none on wire; timeout / socket close only | `$/cancelRequest` → MUST reply, error `-32800` |
| Progress | `notifications/progress`, opt-in via `params._meta.progressToken`, monotonic | none | `$/progress`, two flavours (workDone 3-phase, partialResult streamed) |
| Error code dialect | base -32700..-32603 only | base codes observed; no published table; `code` opaque | base + six LSP extensions (`-32800`..`-32803`, `-32001`, `-32002`) |
| Unknown methods | `MethodNotFound` for requests; drop notifications | (returned as base error) | `$/`-prefix: drop unknown notifications, `MethodNotFound` for requests |
| Backpressure / cap | none | `maxInFlight=8` REQUIRED on JS/Native single-threaded runtimes or Chrome kills socket | none |
| Lifecycle gate above wire | initialize + capability negotiation + version header | none | pre-`initialize` → reply `-32002`, drop notifications except `exit` |
| Server-initiated routing | POST-reply may upgrade to SSE; GET opens dedicated S→C stream; replies to S→C come back via fresh POST; SSE event-id resumability | events flow on the same WebSocket | both sides write to the same byte stream |

The table is the load-bearing constraint set. Every design choice below traces back to a row in this table.

---

## 2. Layered architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Consumer protocol modules                                                │
│   kyo-lsp        kyo-mcp        kyo-browser (CDP)                        │
│   • method      • Streamable    • per-session                            │
│     namespaces    HTTP adapter    dispatch tables                        │
│   • capability  • session        • dialog drainer                        │
│     negotiation   id, version    • event whitelist                       │
│   • initialize    headers        • CDP envelope codec                    │
│     state       • _meta /                                                │
│     machine       progressToken                                          │
└────┬────────────────┬─────────────────────────────────────┬─────────────┘
     │                │                                     │
     ▼                ▼                                     ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ kyo-jsonrpc (this module)                                                │
│                                                                          │
│  ┌─── Layer 4 ────────────────────────────────────────────────────────┐  │
│  │ JsonRpcEndpoint  (bidirectional dispatch + policy layer)           │  │
│  │   • inbound Request -> fork handler -> reply                       │  │
│  │   • pendingInbound  (id -> handler fiber + suppress flag)          │  │
│  │   • writer serialization (single fiber, no interleaved frames)     │  │
│  │   • callerRegistry  (id -> method + caller fiber, for cancel)      │  │
│  │   • cancellation policy + progress policy + unknown-method policy  │  │
│  │   • ExtrasEncoder closure plumbed per call                         │  │
│  │   • optional Meter (maxInFlight), optional per-call timeout        │  │
│  │   • awaitDrain                                                     │  │
│  │                                                                    │  │
│  │ NOTE: outbound id allocation, the id->promise pending map, and the │  │
│  │ reader fiber that routes responses are ALL Exchange's job          │  │
│  │ (Layer 0). The engine does not duplicate them.                     │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌─── Layer 3 ────────────────────────────────────────────────────────┐  │
│  │ JsonRpcMethod[+S]  (typed method descriptor)                       │  │
│  │   • captured Schema[In], Schema[Out]                               │  │
│  │   • InvalidParams on decode failure, InternalError on panic        │  │
│  │   • request flavour AND notification flavour                       │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌─── Layer 2 ────────────────────────────────────────────────────────┐  │
│  │ Wire envelope                                                      │  │
│  │   JsonRpcCodec[Envelope]  (pluggable)                              │  │
│  │     • Strict2_0 envelope  (LSP, MCP)                               │  │
│  │     • Cdp envelope        (no jsonrpc, extra sessionId)            │  │
│  │   JsonRpcRequest / JsonRpcResponse / JsonRpcError / JsonRpcId      │  │
│  │     (with hand-written flat Schema[JsonRpcId])                     │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌─── Layer 1 ────────────────────────────────────────────────────────┐  │
│  │ JsonRpcTransport  (envelopes in / envelopes out)                   │  │
│  │   trait JsonRpcTransport:                                          │  │
│  │     send(env: Env): Unit < (Async & Abort[Closed])                 │  │
│  │     incoming: Stream[Env, Async & Abort[Closed]]                   │  │
│  │     close: Unit < Async                                            │  │
│  │   inMemory: (Transport, Transport)  // for tests, in this module   │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌─── Layer 0 ────────────────────────────────────────────────────────┐  │
│  │ kyo.Exchange    (already exists in kyo-core)                       │  │
│  │   • id-keyed pending map, reader fiber, push events,               │  │
│  │     transport-error propagation, orderly close                     │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

Layer 0 (Exchange) is unchanged. Layer 1 is the smallest possible transport interface. Layer 2 makes the wire codec parametric so CDP can plug in without breaking the strict-2.0 path. Layer 3 ports the existing `JsonRpcMethod` builder. Layer 4 is the new bidirectional engine.

---

## 3. Layer 2 in detail: pluggable wire codec

This is where CDP forces our hand. CDP messages are not JSON-RPC 2.0. If kyo-browser is to migrate to this module without wrapping, the envelope decoder must be a parameter, not a hardcoded `Json.decode[JsonRpcRequest]`.

**Value type used throughout:** the design uses `Structure.Value` (an enum from `kyo-schema/Structure.scala`: `Str | Bool | Integer | Decimal | BigNum | Null | Record(fields: Chunk[(String, Value)]) | Sequence(elements: Chunk[Value])`). It is the universal JSON-shaped value type in kyo.

The policy lambdas (§7 / §8) inspect and build `Structure.Value` directly using:

- `summon[Schema[T]].toStructureValue(t)` (kyo-schema, `private[kyo]`, accessible since we're in package `kyo`) to project a typed case class to `Structure.Value`.
- Pattern matching on `Structure.Value.Record(fields)` to read field values.
- `Record(a ++ b)` to merge two records inline.

No new helpers, no kyo-schema changes.

```scala
package kyo

// Logical envelope after codec, regardless of wire shape.
enum JsonRpcEnvelope derives Schema, CanEqual:
    case Request (id: JsonRpcId, method: String, params: Maybe[Structure.Value], extras: Maybe[Structure.Value])
    case Notification(method: String, params: Maybe[Structure.Value], extras: Maybe[Structure.Value])
    case Response(id: JsonRpcId, result: Maybe[Structure.Value], error: Maybe[JsonRpcError], extras: Maybe[Structure.Value])
    case Malformed(reason: String, raw: Structure.Value)

// extras = whole envelope minus the JSON-RPC-shaped fields, retained verbatim.
// CDP's sessionId lands here. MCP's _meta on params is decoded one level deeper.
// LSP has no extras.
// extras = Absent       : the wire envelope has no extra fields
// extras = Present(Null): the wire has an extras slot whose value is JSON null
// The two are observably different on the wire.
```

```scala
trait JsonRpcCodec:
    def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < Sync
    def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync

object JsonRpcCodec:
    val Strict2_0: JsonRpcCodec  // adds jsonrpc:"2.0", rejects null id where spec requires
    val Cdp:       JsonRpcCodec  // no jsonrpc field; sessionId allowed at top level

    // Reserved keys the Cdp codec MUST reject in caller-supplied extras.
    // Prevents caller-controlled extras from overwriting wire fields like
    // method, id, params, result, error.
    private[kyo] val cdpReservedKeys: Set[String] =
        Set("id", "method", "params", "result", "error", "jsonrpc")
```

Two ready-built codecs ship. Both produce/consume the same `JsonRpcEnvelope` ADT. Everything above this layer sees only the ADT.

**Why this works for all three consumers:**

- **LSP, MCP**: `Strict2_0`. `extras` is always `Absent`.
- **CDP**: `Cdp`. `extras` carries `Present({sessionId: "..."})`. kyo-browser's outbound encoder closure (see §6, "Encoder for per-call envelope shaping") returns the extras object per call. On encode, the Cdp codec stamps every extras key as a top-level wire field, REJECTING any extras key in `cdpReservedKeys` with a `JsonRpcError.invalidRequest` to prevent envelope hijacking.
- **MCP `_meta.progressToken`**: this lives one level deeper, *inside* `params`. Not the codec's concern; the progress policy (§8) extracts it.

**Decoded once, classified, then handed up.** `decode` is `Sync` only (Exchange contract); it must not park. Parsing failures become `Malformed` so the dispatcher can reply with `-32700 ParseError` without bringing down the reader fiber.

---

## 4. Layer 1 in detail: transport

```scala
trait JsonRpcTransport:
    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed])
    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]]
    def close(using Frame): Unit < Async
```

No type parameter on `JsonRpcTransport`: the envelope type is always `JsonRpcEnvelope`. The wire shape variation (CDP vs JSON-RPC 2.0) is absorbed by `JsonRpcCodec` inside the transport adapter, BEFORE the envelope reaches the engine. Earlier drafts of this design parameterized the transport over an envelope type; that parameterization is gone.

The transport delivers *already-codec'd* envelopes, not raw bytes. Codec selection happens at transport construction so the engine never sees wire format.

Why "envelopes in" rather than "bytes in":

- **CDP** uses a single WebSocket; codec runs per frame.
- **LSP** uses `Content-Length:` framed stdio; the framing adapter knows how to peel off headers and hand a JSON body to the codec.
- **MCP Streamable HTTP** is multi-stream: the adapter ingests POSTs, GETs, and SSE upgrades, demuxes them into a single inbound envelope stream, and remuxes outbound envelopes back to the right HTTP response or SSE channel.

The bytes ↔ envelopes layer therefore belongs *inside the transport*, where the protocol's specific framing rules live. The engine sees a single duplex envelope stream regardless.

**In-memory transport ships with the module:**

```scala
object JsonRpcTransport:
    def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync
```

Two transports cross-wired via Channels. Used for every bidirectional test; no need for actual HTTP/WS/stdio plumbing in this module's test suite.

---

## 5. Layer 3 in detail: typed method descriptor

Direct port of the existing `JsonRpcMethod[+S]` from `kyo-ai-plugin:kyo-http/.../JsonRpc.scala`, plus a notification flavour and a `HandlerCtx` for cancellation/progress hooks:

```scala
sealed trait JsonRpcMethod[+S]:
    def name: String
    def kind: JsonRpcMethod.Kind   // Request | Notification
    private[kyo] def schemaIn:  Schema[?]
    private[kyo] def schemaOut: Schema[?]
    private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using Frame):
        Structure.Value < S

object JsonRpcMethod:
    enum Kind derives CanEqual:
        case Request, Notification

    def apply[In: Schema, Out: Schema, S](name: String)(handler: (In, HandlerCtx) => Out < S)(
        using Frame, (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S]

    // Convenience overload for handlers that don't need ctx
    def apply[In: Schema, Out: Schema, S](name: String)(handler: In => Out < S)(
        using Frame, (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S]

    def notification[In: Schema, S](name: String)(handler: (In, HandlerCtx) => Unit < S)(
        using Frame, (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S]
```

`HandlerCtx` is the per-invocation context:

```scala
final class HandlerCtx private (
    val cancelled: Fiber.Promise[Unit, Any],     // completed when peer requests cancel
    val requestId: Maybe[JsonRpcId],             // Absent for notifications
    val extras:    Maybe[Structure.Value],            // CDP sessionId, anything else from envelope.extras
    progressSink:  Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
):
    // Emits a progress notification keyed by this request's token, if any.
    // No-op when the caller didn't attach a progress token (or progress policy is Absent).
    def progress(value: Structure.Value)(using Frame): Unit < (Async & Abort[Closed])
```

The policy (§8) extracts the progress token from inbound params and installs the `progressSink` closure into `HandlerCtx`. CDP installs `progress = Absent`; `ctx.progress(...)` becomes a no-op without the handler having to know.

**Why no `Outcome` ADT:** the engine knows whether a method is `Request` or `Notification` from `method.kind`. For notifications it discards the handler's `Unit` return. For requests it encodes the typed return via `Schema[Out]` to `Structure.Value` and sends as a `Response`. If the request was cancelled and the policy says "no reply" (MCP-style), the engine interrupts the handler fiber and drops any reply it might have produced. The handler never has to express "send nothing"; it just produces a value or aborts. Cleaner than the earlier `Outcome.Reply | NoReply` ADT.

---

## 6. Layer 4 in detail: JsonRpcEndpoint

The new piece. Bidirectional, symmetric, runs over a `JsonRpcTransport` and a registry of `JsonRpcMethod`s.

```scala
// Encoder for per-call envelope shaping. The closure receives the engine-assigned
// JsonRpcId so callers can incorporate it (CDP's withSession pattern: the closure
// captures sessionId in its lexical scope and shapes the envelope per id).
// Returns the extras object to merge into the outbound envelope's `extras` slot.
// Returns Absent to send no extras at all.
type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync

final class JsonRpcEndpoint private (...):
    // Outgoing: typed call
    def call[In: Schema, Out: Schema](
        method:  String,
        params:  In,
        extras:  ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Out < (Async & Abort[JsonRpcError | Closed])

    // Outgoing: notification (fire-and-forget, bypasses pending map).
    // ExtrasEncoder still receives an id (synthesized non-routing id) so the
    // closure shape stays uniform with `call`; CDP uses a negative-id slot if
    // its server expects one, otherwise Absent.
    def notify[In: Schema](
        method:  String,
        params:  In,
        extras:  ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Unit < (Async & Abort[Closed])

    // Outgoing: call with live progress side-channel
    def callWithProgress[In: Schema, Out: Schema](
        method:  String,
        params:  In,
        extras:  ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): JsonRpcEndpoint.Pending[Out]

    // Outgoing: streaming partial-result call (LSP-style)
    // The final empty response from the peer closes the stream; chunks delivered
    // via $/progress notifications are emitted as the stream produces.
    def callPartialResults[In: Schema, T: Schema](
        method:  String,
        params:  In,
        extras:  ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Stream[T, Async & Abort[JsonRpcError | Closed]]

    // Out-of-band progress subscription (LSP window/workDoneProgress/create flow)
    def subscribeProgress(token: Structure.Value)(using Frame):
        Stream[Structure.Value, Async & Abort[Closed]]

    def unsubscribeProgress(token: Structure.Value)(using Frame):
        Unit < Async

    // Engine-level cancel. With CancellationPolicy present: dispatches the
    // policy's cancel notification AND aborts the local pending entry.
    // Without policy: aborts the local pending entry only.
    //
    // Refuses (no-op + log) when the in-flight call's method name is in
    // `Config.cancellation.protectedMethods` (MCP: initialize cannot be cancelled).
    def cancel(id: JsonRpcId, reason: Maybe[String])(using Frame):
        Unit < (Async & Abort[Closed])

    // Waits for: (1) writer channel to drain; (2) Exchange's pending map to empty
    // (all outbound calls resolved: response, error, timeout, or cancel); (3) all
    // pendingInbound handler fibers to complete. Returns when all three are
    // quiescent. Does NOT close the endpoint; for that use Scope or `close`.
    def awaitDrain(using Frame): Unit < Async

    def close(using Frame): Unit < Async

object ExtrasEncoder:
    val empty: ExtrasEncoder = _ => Sync.defer(Absent)
    def const(extras: Structure.Value): ExtrasEncoder = _ => Sync.defer(Present(extras))

object JsonRpcEndpoint:
    final class Pending[Out](
        val id:       JsonRpcId,
        val result:   Out < (Async & Abort[JsonRpcError | Closed]),
        val progress: Stream[Structure.Value, Async],
        val cancel:   Unit < (Async & Abort[Closed])
    )

    final case class Config(
        codec:          JsonRpcCodec               = JsonRpcCodec.Strict2_0,
        cancellation:   Maybe[CancellationPolicy]  = Present(CancellationPolicy.lsp),
        progress:       Maybe[ProgressPolicy]      = Present(ProgressPolicy.lsp),
        unknownMethod:  UnknownMethodPolicy        = UnknownMethodPolicy.lsp,
        gate:           Maybe[MessageGate]         = Absent,
        maxInFlight:    Maybe[Int]                 = Absent,
        requestTimeout: Duration                   = Duration.Infinity,
        idStrategy:     IdStrategy                 = IdStrategy.SequentialLong
    )

    def init(
        transport: JsonRpcTransport,
        methods:   Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
        config:    Config = Config()
    )(using Frame): JsonRpcEndpoint < (Async & Scope)
```

Key shape decisions:
- **`extras: ExtrasEncoder`** on every outbound. The encoder is a closure `JsonRpcId => Maybe[Structure.Value]` that receives the engine-assigned id. This preserves the `Req = Id => Wire` pattern that CDP's existing Exchange usage requires (per research/CDP.md design implication #3). Default `ExtrasEncoder.empty` so LSP/MCP pay no cost. CDP's `withSession(sid)` becomes a facade that returns `ExtrasEncoder.const(summon[Schema[SessionExtras]].toStructureValue(SessionExtras(sid)))`.
- **Policies are `Maybe`d**, not `.none` sentinel objects. `Config(cancellation = Absent)` gives the CDP shape with no cancellation method baked in.
- **Notifications return `Unit`.** `JsonRpcMethod.notification(name)(handler: In => Unit < S)` returns `Unit`; the engine never sends a reply for it. Cancellation reply semantics are entirely an engine concern (see §7), not handler-visible.
- **No `Outcome` ADT.** Request handlers return `Out < S` (typed); the engine encodes to `Structure.Value` via the captured `Schema[Out]`. Cancellation-no-reply is enforced by the engine interrupting the handler fiber and suppressing any in-flight reply via the writer-side filter described in §6.5.

### 6.1 Internals

The engine sits on top of an `Exchange[Structure.Value, Structure.Value, JsonRpcEnvelope, Closed]` (or similar; exact `Req/Resp/Event/E` parameterization is pinned by the codec). **Exchange owns the outbound id-to-promise map, the id allocator, and the reader fiber that routes responses by id.** This is the same pattern kyo-browser's `CdpClient` already uses. The engine does NOT maintain a parallel `Exchange's pending map`.

What the engine adds on top of Exchange:

- `outbound: Channel[OutboundMsg]`: single mpmc channel feeding the writer fiber. Exchange's `send` parameter is wired to `outbound.put`. Serializes writes so frames don't interleave on transports that aren't atomic (stdio, HTTP/2 streams). This mirrors CDP's `outbound: Channel[String]` exactly.
- `callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo]`: side-table consulted by `endpoint.cancel(id)` to (a) look up the method name for `protectedMethods` check, (b) get the caller fiber to interrupt. NOT a duplicate of Exchange's pending map; this is just a lookup index. Populated when an outbound call enters Exchange; removed via `Sync.ensure` on exit (response, timeout, scope close, cancel).

```scala
private[kyo] case class CallerInfo(method: String, callerFiber: Fiber[Any, Any])
```

- `pendingInbound: ConcurrentHashMap[JsonRpcId, InboundEntry]`: the genuinely new piece. Tracks inbound requests we're SERVING (not ones we issued) so `$/cancelRequest` (LSP) / `notifications/cancelled` (MCP) can find the handler fiber to interrupt and, for MCP no-reply, suppress the queued response. Exchange does NOT cover this; Exchange's inbound side handles only `Response` (matched by id) and `Push` (unsolicited events).

```scala
private[kyo] sealed trait InboundEntry
private[kyo] object InboundEntry:
    // Handler is still running. Cancellation completes `cancelled` (the AtomicBoolean
    // backing HandlerCtx.cancelled).
    case class Running(method: String, handler: Fiber[Structure.Value, Any],
                       cancelled: AtomicBoolean) extends InboundEntry
    // Handler finished; response is in the writer queue. Cancellation flips
    // `suppress` to true; writer checks it before sending and drops if set.
    case class Replying(method: String, suppress: AtomicBoolean) extends InboundEntry
```

- `progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]`: token → progress channel for outbound calls and out-of-band subscriptions.
- `partialResults: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]`: the stream returned by `callPartialResults` IS this channel's stream; closes when the peer's final empty response arrives.
- `meter: Maybe[Meter]`: semaphore for `maxInFlight`; CDP knob, mirroring `cdpMeter`.

**Late-reply drop after cancel is Exchange's job, not ours.** When `endpoint.cancel(id)` interrupts the caller fiber, `Sync.ensure` in Exchange's `apply` removes the pending entry. A late reply arrives, Exchange's reader finds nothing in its pending map, drops silently. No separate `cancelledOutboundIds` set is needed at the engine. (Earlier drafts of this design had one; removed as over-engineering once we confirmed Exchange covers it.)

**Outbound id allocation is Exchange's job, not ours.** The engine translates its `Config.idStrategy` to Exchange's `nextId: => Id < Sync` parameter at init time. No `AtomicLong` at the engine level.

**Surface that maps to Exchange's API**:
- Outbound `call` → `exchange(req)`. Exchange assigns id, encodes via the engine-supplied `encode` callback, serialises through `outbound.put`, registers the pending promise, awaits.
- Outbound `notify` → `outbound.put(envelope)` directly, bypassing Exchange's pending machinery (Exchange has no "send without pending" API, but writing to the same Channel its `send` writes to is equivalent and cleaner than CDP's negative-id workaround).
- Inbound routing → engine-supplied `decode` callback. For `Response` envelopes it returns `Exchange.Message.Response(id, _)` (Exchange routes to the right pending promise). For `Request` and `Notification` envelopes it does the engine-side dispatch (fork handler / fire policy intercept) inside `decode` and returns `Exchange.Message.Skip` (Exchange doesn't need to do anything more).

Two subtleties about doing inbound dispatch inside `decode`:
- `decode` is `Sync`-only (Exchange contract). The engine forks handlers via `Fiber.initUnscoped`, which is `Sync`. The handler runs on its own fiber, off the reader.
- Inbound `Notification` for events that the consumer cares about (CDP `Page.frameNavigated`, etc) can either be dispatched as a registered `JsonRpcMethod.notification`-handler (forked) OR routed as an `Exchange.Message.Push` for `endpoint.events` consumption. The engine picks: if a handler is registered for that method, use it; else `Push` to the events channel.

### 6.2 Reader fiber

One fiber drains `transport.incoming` and routes each envelope. The order of operations per envelope is fixed:

```
1. Policy intercept
   - If env is a Notification AND env.method == cancellation.cancelMethod:
       extract requestId from params; handle cancellation (see §7 inbound flow); STOP.
   - If env is a Notification AND env.method == progress.progressMethod:
       extract token; route to progressStreams[token] (or partialResults[token]); STOP.

2. MessageGate (if Config.gate = Present(gate))
   - Decision = gate.beforeDispatch(env)
   - Decision.Allow:  continue
   - Decision.Reject(err): for Request -> send Response(id, err); for Notification -> drop; STOP.
   - Decision.Drop:   drop; STOP.

3. Method dispatch
   - Request: look up methods[env.method].
       - Found: register InboundEntry.Running, fork handler, on completion transition to
         InboundEntry.Replying and enqueue response.
       - Not found: send Response(id, MethodNotFound) per UnknownMethodPolicy.onUnknownRequest.
   - Notification: look up methods[env.method].
       - Found: fork handler; discard return.
       - Not found: drop per UnknownMethodPolicy.onUnknownNotification ($/-prefix override applies).
   - Response: see step 4.
   - Malformed: if env.raw has a parseable id -> send Response(id, ParseError);
                else log and drop.

4. Response routing (for Response envelopes)
   - If id in progressStreams (partial-result token equals the request id):
       interpret the empty `result` as stream close.
   - Else: return Exchange.Message.Response(id, payload).
       Exchange's reader matches the id against its pending map and completes the
       caller's promise. If the caller was already cancelled (interrupted), the
       pending entry is gone (Sync.ensure removed it) and Exchange drops the
       response silently.
```

All routing work is `Sync`-only (Exchange invariant). Handler fibers are forked off the reader, never awaited on the reader.

### 6.3 Writer fiber

One fiber drains `writer` and calls `transport.send` per envelope. Serializing the writer side avoids interleaved partial frames on transports that aren't atomic (e.g. stdio).

### 6.4 Scope integration

`Endpoint.init` returns under `Async & Scope`. Scope cleanup runs finalizers in this order:

1. Stop accepting new outbound calls (poison the writer channel).
2. Cancel the reader fiber.
3. Cancel the writer fiber.
4. `transport.close`.
5. Close Exchange (fails every entry in Exchange's pending map with `Closed`).
6. Close every `progressStreams` channel.
7. Interrupt every `pendingInbound` handler fiber.

The MCP report's R6 ("a proper engine pre-supposes a persistent connection") and the LSP report's "bidirectional, no client/server asymmetry" are both satisfied.

### 6.5 Pending-entry lifecycle and cancellation race

The Exchange primitive handles the common race (registered pending then send fails). The engine inherits this. Additionally:

- **Caller fiber interrupted (timeout, scope close, explicit cancel)**: `Sync.ensure` around the pending-entry registration removes it on interrupt. Mirrors `Exchange.apply`'s pattern.
- **Late response after caller cancellation**: handled by Exchange. Cancellation interrupts the caller fiber; `Sync.ensure` in Exchange removes the pending entry; the late response finds no entry and Exchange drops it silently.
- **Handler panic**: `JsonRpcMethod.handle` maps panic to `JsonRpcError.internalError`; engine sends that as the reply. Panic stack trace goes into `error.data` (not `error.message`) to avoid leaking impl details in normal logs.

**Inbound cancellation race (the writer-channel window).** When an inbound handler completes and the engine enqueues the response to the writer channel, there is a window between dequeue-from-handler and send-on-transport. If `notifications/cancelled` arrives during that window AND the policy says "no reply for cancelled" (MCP), naively the response leaks. The fix has three parts:

1. **`pendingInbound[id]` lifetime extends through the writer.** Handler completion does NOT remove the entry. Instead, the entry transitions from `Running(handler, cancelled)` to `Replying(method, suppress)`, and the response is enqueued as `WriterMsg.SuppressIfCancelled(id, env)`.
2. **Cancellation finds the queued reply.** When the reader's policy intercept (§6.2 step 1) sees `notifications/cancelled` for an id whose `pendingInbound[id]` is in `Replying` state, it sets `suppress.set(true)`.
3. **Writer fiber checks `suppress` before sending.** For `WriterMsg.SuppressIfCancelled(id, env)`: if `pendingInbound[id].suppress.get()` is true AND policy is no-reply, drop the envelope; else send. Either way, remove the entry after.

For LSP-style (`expectReplyForCancelledRequest=true`), the writer never suppresses; the reply (whatever the handler produced) always flows. The handler simply observed `ctx.cancelled` and chose its response.

This is the fix called out by the correctness audit's race-condition #3.

### 6.6 Reader fiber discipline (Sync-only)

The reader fiber's per-envelope work is strictly `Sync` (Exchange contract). Specifically:

- Codec decode: `Sync`.
- Pending map lookup, removal, complete: `Sync` (uses `Promise.Unsafe.completeDiscard`).
- Forking handler fibers for inbound requests/notifications: `Fiber.initUnscoped`, which is `Sync`.
- Pushing to progress channels: `Channel.Unsafe.offer` (non-blocking).

The reader **never parks**. Handlers run on forked fibers; the reader returns to draining immediately. This is what lets one fiber serve thousands of in-flight handlers without stalling.

---

## 7. Cancellation policy

This is the trickiest cross-consumer concern because the three protocols disagree on what cancellation means at the JSON-RPC layer.

```scala
final case class CancellationPolicy(
    cancelMethod:                   String,                                 // e.g. "$/cancelRequest"
    encodeParams:                   CancellationPolicy.ParamsEncoder,       // payload builder
    expectReplyForCancelledRequest: Boolean,                                // LSP=true, MCP=false
    cancelledError:                 Maybe[JsonRpcError],                    // LSP=Present(RequestCancelled); MCP=Absent
    protectedMethods:               Set[String]                             // method names that cannot be cancelled
)

object CancellationPolicy:
    // Each policy provides the typed params shape for its cancel notification.
    // ParamsEncoder takes (id, reason) and produces the Structure.Value to put
    // in the outbound notification's params slot.
    type ParamsEncoder = (JsonRpcId, Maybe[String]) => Structure.Value < Sync

    // LSP $/cancelRequest params: { "id": <id> }
    private case class LspCancelParams(id: JsonRpcId) derives Schema
    private val lspEncoder: ParamsEncoder = (id, _) =>
        Sync.defer(summon[Schema[LspCancelParams]].toStructureValue(LspCancelParams(id)))

    // MCP notifications/cancelled params: { "requestId": <id>, "reason"?: <string> }
    private case class McpCancelParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema
    private val mcpEncoder: ParamsEncoder = (id, reason) =>
        Sync.defer(summon[Schema[McpCancelParams]].toStructureValue(McpCancelParams(id, reason)))

    val lsp: CancellationPolicy = CancellationPolicy(
        cancelMethod                   = "$/cancelRequest",
        encodeParams                   = lspEncoder,
        expectReplyForCancelledRequest = true,
        cancelledError                 = Present(JsonRpcError.RequestCancelled),
        protectedMethods               = Set.empty
    )

    val mcp: CancellationPolicy = CancellationPolicy(
        cancelMethod                   = "notifications/cancelled",
        encodeParams                   = mcpEncoder,
        expectReplyForCancelledRequest = false,
        cancelledError                 = Absent,
        // MCP spec §5.1: initialize MUST NOT be cancelled by the client.
        protectedMethods               = Set("initialize")
    )

    // CDP: Config(cancellation = Absent). No CancellationPolicy.none constant.
```

`Config.cancellation: Maybe[CancellationPolicy]`. When `Absent` (CDP), `endpoint.cancel(id)` aborts the local pending entry only; no wire notification fires.

**Encoder pattern**: each policy declares the typed params shape as a private case class with `derives Schema` and uses `Schema[T].toStructureValue` to project to a `Structure.Value`. Avoids the `Json.encode` (returns String) trap and needs nothing beyond what kyo-schema already provides.

**`protectedMethods`** is the MCP carve-out for `initialize`. When `endpoint.cancel(id)` is called for an id whose `callerRegistry[id].method` is in this set, the engine logs and returns Unit without firing the cancel notification or interrupting the caller. The pending call continues normally.

### Engine-enforced semantics (not handler-leaked)

The handler's only job is to observe `ctx.cancelled.get` (a `Fiber.Promise[Unit, Any]`) and react appropriately (clean up, abort, return a partial result, or ignore). The handler never has to know whether to "send a reply"; the engine enforces that based on policy.

**Inbound: we receive a cancel for a request we're serving:**

1. Reader fiber sees a notification with method matching `policy.cancelMethod`.
2. Decode params → extract id.
3. Look up `pendingInbound[id]`. If absent (already-completed or never-existed), drop silently.
4. If present, complete `HandlerCtx.cancelled`.
5. If `expectReplyForCancelledRequest = true` (LSP): wait for the handler fiber to finish naturally. Send whatever it produces (result, error, or `policy.cancelledError` if the handler aborted with `RequestCancelled`).
6. If `expectReplyForCancelledRequest = false` (MCP): interrupt the handler fiber. Drop any reply it might have already produced. Send nothing for that id.

**Outbound: we cancel a call we issued:**

1. User calls `endpoint.cancel(id, reason)`.
2. Look up `callerRegistry[id]`. If absent, no-op (already completed). If `method` is in `policy.protectedMethods`, log and no-op (MCP `initialize`).
3. If `Config.cancellation = Present(policy)`: engine emits a notification envelope with `method = policy.cancelMethod`, `params = policy.encodeParams.encode(id, reason)`.
4. Engine interrupts `callerRegistry[id].callerFiber` with `JsonRpcError.cancelled(reason)` (or `policy.cancelledError` if set). The fiber's `Sync.ensure` removes the Exchange pending entry.
5. If a late reply arrives for that id: Exchange's reader finds no pending entry, drops silently. Late-drop is structurally guaranteed by Exchange's pending-map cleanup, not by an engine-side bookkeeping set.

### Timeout → cancellation auto-fire

`Config.requestTimeout: Duration` wraps every outbound `call`. On timeout:

- If `Config.cancellation = Present`: engine fires the cancellation policy (same as `endpoint.cancel(id)`). Matches MCP spec ("sender SHOULD emit notifications/cancelled").
- If `Config.cancellation = Absent` (CDP): engine just aborts the local pending entry. No wire traffic.

No additional config knob; behavior derives from policy presence.

This satisfies LSP's report §3 (state diagram for both sides), MCP's report R9, MCP-body §4 timeout requirement, and CDP's report point #7.

---

## 8. Progress policy

Same shape as cancellation: a small case class encoding the protocol's progress conventions. `Config.progress: Maybe[ProgressPolicy]`. When `Absent` (CDP), `endpoint.callWithProgress` / `callPartialResults` / `subscribeProgress` return empty streams; `ctx.progress(...)` is a no-op.

```scala
final case class ProgressPolicy(
    progressMethod:        String,                                                          // "$/progress" or "notifications/progress"
    extractInboundToken:   Structure.Value => (Maybe[Structure.Value] < Sync),              // from inbound progress notif's params
    extractRequestToken:   Structure.Value => (Maybe[Structure.Value] < Sync),              // from inbound request's params (for handler ctx.progress)
    stampOutboundToken:    (Structure.Value, Structure.Value) => (Structure.Value < Sync),  // attaches token to outbound request params
    encodeProgressParams:  (Structure.Value, Structure.Value) => (Structure.Value < Sync),  // (token, value) -> progress notif params
    enforceMonotonic:      Boolean                                                          // MCP: true; LSP/CDP: false
)

object ProgressPolicy:
    import Structure.Value.{Record, Null}

    // Lookup helper: find field by name in a Record; return Absent for non-records / missing.
    // Inline in each policy lambda; not a public API.
    private inline def field(v: Structure.Value, name: String): Maybe[Structure.Value] =
        v match
            case Record(fields) => Maybe.fromOption(fields.iterator.collectFirst { case (k, x) if k == name => x })
            case _              => Absent

    // Merge two Records, with `b`'s keys winning on collision. Non-records become Records.
    private inline def merge(a: Structure.Value, b: Structure.Value): Structure.Value =
        (a, b) match
            case (Record(af), Record(bf)) =>
                val merged = (af ++ bf).groupBy(_._1).map { case (k, kvs) => (k, kvs.last._2) }.toChunk
                Record(merged)
            case (Record(_), other) => other          // b is not a record; b wins
            case (_, Record(bf))    => Record(bf)
            case (_, other)         => other

    val lsp: ProgressPolicy = ProgressPolicy(
        progressMethod       = "$/progress",
        extractInboundToken  = p => Sync.defer(field(p, "token")),
        extractRequestToken  = p => Sync.defer(field(p, "workDoneToken")),
        stampOutboundToken   = (p, t) => Sync.defer(merge(p, Record(Chunk("workDoneToken" -> t)))),
        encodeProgressParams = (t, v) => Sync.defer(Record(Chunk("token" -> t, "value" -> v))),
        enforceMonotonic     = false
    )

    val mcp: ProgressPolicy = ProgressPolicy(
        progressMethod       = "notifications/progress",
        extractInboundToken  = p => Sync.defer(field(p, "progressToken")),
        extractRequestToken  = p => Sync.defer:
            field(p, "_meta").map(meta => field(meta, "progressToken")).getOrElse(Absent)
        ,
        stampOutboundToken   = (p, t) => Sync.defer:
            // params._meta.progressToken = t  (creating _meta if missing)
            val existingMeta = field(p, "_meta").getOrElse(Record(Chunk.empty))
            val newMeta      = merge(existingMeta, Record(Chunk("progressToken" -> t)))
            merge(p, Record(Chunk("_meta" -> newMeta)))
        ,
        encodeProgressParams = (t, v) => Sync.defer:
            // MCP shape: { progressToken: <t>, progress: <number>, total?, message? }
            // `v` is expected to be a Record holding progress/total/message; merge token in.
            merge(Record(Chunk("progressToken" -> t)), v)
        ,
        enforceMonotonic     = true
    )
```

### Outbound: three flavors

1. **`call(method, params)`**: plain, no progress.
2. **`callWithProgress(method, params)`**: returns `Pending[Out]` with `result` (typed final reply) and `progress: Stream[Structure.Value, Async]` (live values as they arrive). Useful for LSP work-done progress (3-phase begin/report/end) and MCP progress.
3. **`callPartialResults[T](method, params)`**: returns `Stream[T, ...]`. Each chunk delivered via `$/progress` is decoded as `T` and emitted. The final empty response from the peer closes the stream. LSP-only pattern; works whenever `progressMethod` is set.

For (2) and (3) the engine:
- Allocates a fresh token (string UUID or counter).
- Calls `policy.stampOutboundToken(params, token)` to attach.
- Registers a `Channel[Structure.Value]` in `progressStreams[token]`.
- Sends the request.
- For `callWithProgress`: progress channel is exposed; on final response, channel closes and `result` completes.
- For `callPartialResults`: chunks streamed through; on final response (empty `result`), stream closes naturally.

### Inbound progress

1. Reader sees a notification matching `policy.progressMethod`.
2. `policy.extractInboundToken(params)` → if `Absent`, log and drop (unknown progress; LSP/MCP both spec this as silent drop).
3. Look up `progressStreams[token]`. If present, `offer(value)`. If absent, drop silently (token belonged to a request that already completed or was cancelled).

### Out-of-band token registration (LSP `window/workDoneProgress/create`)

LSP allows the server to pre-create a progress token via a request, then later send progress for it unrelated to any outbound call. The originator side needs to register the token before the server allocates progress against it.

`endpoint.subscribeProgress(token): Stream[Structure.Value, Async]` is the API. The kyo-lsp handler for `window/workDoneProgress/create` calls this with the token from the request before replying success.

Implementation: same `progressStreams[token]` map; just a registration entry-point that doesn't go through `callWithProgress`. The stream closes when `endpoint.unsubscribeProgress(token)` is called or the endpoint shuts down.

### Handler-side `ctx.progress(value)`

`policy.extractRequestToken(inboundParams)` extracts the requester's token (`workDoneToken` or `_meta.progressToken`). If present, the engine installs in `HandlerCtx.progressSink` a closure that:

- Checks the handler is still in `Running` state (not `Replying`). If the handler has already returned a value and the engine has moved its inbound entry to `Replying`, `progressSink` REFUSES with no wire effect (returns Unit). This enforces MCP's "MUST NOT emit progress after the response has been sent" rule.
- If `policy.enforceMonotonic` is true, looks up the `progress` field in `value` (pattern match on `Structure.Value.Record`) and compares against the last emitted value's `progress`. If non-monotonic, logs and drops without sending. MCP requires monotonic `progress`.
- Wraps `value` via `policy.encodeProgressParams(token, value)`.
- Sends a notification with `method = policy.progressMethod` and those params.

If no token is present, `progressSink = Absent` and `ctx.progress(...)` is a no-op. CDP: same, with no `ProgressPolicy` at all.

`HandlerCtx.progressSink` lifetime is bounded by the handler fiber's lifetime. When the engine transitions the inbound entry from `Running` to `Replying` (see §6.5), the sink is invalidated atomically; any `ctx.progress(...)` call from a captured closure that survives the handler returns silently.

This satisfies LSP report §4 (workDone), §4 (partialResult), MCP report R10/R17, MCP body §5 (monotonic, no-post-response), CDP report (no progress).

---

## 9. Unknown-method policy

```scala
final case class UnknownMethodPolicy(
    onUnknownRequest: UnknownAction,
    onUnknownNotification: UnknownAction,
    dollarPrefixOverride: Boolean                              // LSP: true (drop $/ notifs silently)
)

enum UnknownAction:
    case ReplyMethodNotFound    // requests: standard JSON-RPC
    case Drop                   // notifications: standard JSON-RPC
    case Reject                 // engine logs + closes; useful for strict servers
```

Defaults:
- **`UnknownMethodPolicy.lsp`**: requests=`ReplyMethodNotFound`, notifications=`Drop`, `dollarPrefixOverride=true`.
- **`UnknownMethodPolicy.strict`**: requests=`ReplyMethodNotFound`, notifications=`Drop`.

All three consumers use one of these. Nothing else needed.

---

## 10. Id strategy

```scala
enum IdStrategy:
    case SequentialLong                       // 1, 2, 3, ...     (LSP, MCP)
    case SequentialInt                        // 1, 2, 3, ...     (CDP, smaller wire)
    case Custom(next: () => JsonRpcId < Sync) // anything
```

Per-endpoint counter, monotonic. The LSP report's "per-direction id namespace" concern is satisfied because each *endpoint* has its own counter and is one side of the connection. Two endpoints (one each side) on the same transport allocate independently and route by `(direction, id)` which here is `(endpoint, id)`.

The CDP report's "global counter spanning sessions" pattern works as-is because kyo-browser models all sessions on one endpoint, so one counter serves everything. `withSession(sid)` becomes a method on kyo-browser's wrapper, not on the engine.

---

## 11. Backpressure / maxInFlight

`Config.maxInFlight: Maybe[Int]`. When `Present(n)`, the engine acquires a `Meter.initSemaphore(n)` around every `call`, `callWithProgress`, and `callPartialResults`. `notify` does not acquire (notifications bypass Exchange's pending map entirely; nothing to back-pressure).

This is the CDP report's #5 ("not weirdness, single-thread backpressure"). LSP and MCP both pass `Absent`. CDP's kyo-browser wrapper passes `Present(8)` on JS/Native (it can pick per-platform via `kyo.Platform`).

---

## 12. Lifecycle gate

```scala
trait MessageGate:
    def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync

object MessageGate:
    enum Decision:
        case Allow
        case Reject(error: JsonRpcError)
        case Drop
```

`Config.gate: Maybe[MessageGate] = Absent`. When `Present`, the reader fiber consults the gate before dispatching. This is the LSP report §6 hook (pre-`initialize` → `-32002`); also useful to MCP for its initialize-handshake state.

Engine doesn't know about `initialize`. The kyo-lsp / kyo-mcp consumer modules implement the gate.

---

## 13. MCP Streamable HTTP: the hardest case

This is the design point that needs the most care because MCP's transport is multi-stream and the engine sees a single-stream envelope flow.

The transport adapter (lives in `kyo-mcp`, not this module) is responsible for:

- Accepting POSTs on `/mcp`. Body = one envelope. Hand to engine.incoming.
- For each POST, allocate a routing handle. Map `(inbound request id) → (HTTP response or SSE channel)`.
- When the engine emits an outbound envelope:
  - **Response (has id)**: look up the routing handle by id; if a POST is awaiting reply, write the response there (or stream-of-one if upgraded to SSE).
  - **Server-initiated request or notification**: write to the GET-opened SSE channel (if any). If no GET stream is open, buffer to the most recent POST's SSE upgrade. If neither, error (or drop).
- Accept GETs on `/mcp` with `Accept: text/event-stream`. Open SSE channel. Plumb engine outbound (filtered to "no in-flight POST awaits this response").
- Tag each SSE event with a monotonic `id:` per stream. Maintain a bounded replay buffer per stream. On `Last-Event-ID` header, replay.

None of this is in `kyo-jsonrpc`. The engine emits envelopes; the MCP transport adapter decides which TCP socket each goes to. This is the natural seam.

**Where this puts pressure on the engine**: the engine MUST allow outbound envelopes to carry routing hints the transport can interpret. Two options:

- (a) Add `routingHint: Maybe[Structure.Value]` to `JsonRpcEnvelope`. Engine sets it implicitly (response → matches inbound request id; server-initiated → flagged). Transport reads it.
- (b) Don't add it. Transport infers routing from envelope shape: `Response(id, ...)` → match against pending POST id; everything else → GET stream.

**Choose (b)**. The id is enough information. CDP doesn't need it. LSP doesn't need it. MCP doesn't need it once the transport tracks `pending POSTs by request id` itself. Avoid leaking transport concerns into the envelope.

This satisfies MCP report R13 ("per-correlation routing of outbound traffic, not a single fire-hose stream") without adding API surface to the engine.

---

## 14. LSP Content-Length framing

Lives in kyo-lsp. The adapter implements `JsonRpcTransport` over a `Stream[Byte, ...] / outbound Channel[Byte]` pair:

- Inbound: header-block state machine (`\r\n` line scan, case-insensitive header lookup, parse `Content-Length`, optional charset check, read exact body bytes, codec.decode).
- Outbound: serialize codec, prepend `Content-Length: N\r\n\r\n`.

The LSP report's §1 state machine is reproduced verbatim there. The engine doesn't see bytes.

---

## 15. Public API surface (final)

In package `kyo`:

```
JsonRpcEndpoint            // the bidirectional engine
JsonRpcEndpoint.Config
JsonRpcEndpoint.Pending
JsonRpcMethod              // typed method descriptor (request + notification factories)
JsonRpcMethod.Kind
JsonRpcRequest             // wire types (retained for thin HTTP convenience layer)
JsonRpcResponse
JsonRpcError               // constants for -32700..-32603 AND -32800..-32803, -32001, -32002
JsonRpcId                  // enum Num | Str (flat Schema)
JsonRpcEnvelope            // sealed ADT: Request | Notification | Response | Malformed
JsonRpcCodec
JsonRpcCodec.Strict2_0
JsonRpcCodec.Cdp
JsonRpcTransport
JsonRpcTransport.inMemory
ExtrasEncoder              // type alias JsonRpcId => Maybe[Structure.Value] < Sync; with .empty and .const
CancellationPolicy         // .lsp, .mcp; CDP uses Maybe[CancellationPolicy] = Absent
CancellationPolicy.ParamsEncoder
ProgressPolicy             // .lsp, .mcp; CDP uses Maybe[ProgressPolicy] = Absent
UnknownMethodPolicy        // .lsp, .strict
IdStrategy                 // SequentialLong | SequentialInt | Custom
MessageGate                // pre-dispatch hook for kyo-lsp / kyo-mcp init gates
MessageGate.Decision
HandlerCtx                 // cancellation token, extras, progress sink, request id
```

`JsonRpcError` constants:

```scala
object JsonRpcError:
    // JSON-RPC 2.0 base
    val ParseError     = JsonRpcError(-32700, "Parse error",     Absent)
    val InvalidRequest = JsonRpcError(-32600, "Invalid Request", Absent)
    val MethodNotFound = JsonRpcError(-32601, "Method not found",Absent)
    val InvalidParams  = JsonRpcError(-32602, "Invalid params",  Absent)
    val InternalError  = JsonRpcError(-32603, "Internal error",  Absent)

    // LSP extensions, promoted into the generic table
    val ServerNotInitialized = JsonRpcError(-32002, "Server not initialized", Absent)
    val UnknownErrorCode     = JsonRpcError(-32001, "Unknown error code",     Absent)
    val RequestCancelled     = JsonRpcError(-32800, "Request cancelled",      Absent)
    val ContentModified      = JsonRpcError(-32801, "Content modified",       Absent)
    val ServerCancelled      = JsonRpcError(-32802, "Server cancelled",       Absent)
    val RequestFailed        = JsonRpcError(-32803, "Request failed",         Absent)

    // Factories
    def methodNotFound(name: String): JsonRpcError
    def invalidRequest(reason: String): JsonRpcError
    def invalidParams(reason: String): JsonRpcError
    def internalError(cause: String, data: Maybe[Structure.Value] = Absent): JsonRpcError
    // Produces RequestCancelled (-32800) with `reason` (if present) attached as `data`.
    // Used by the engine when interrupting the caller fiber on local cancel / timeout.
    def cancelled(reason: Maybe[String] = Absent): JsonRpcError
```

In `kyo.internal`: writer fiber, reader fiber, pending-map types, progress channels, partial-result accumulator, codec internals.

The prompt's "exactly these seven public types" target was too tight to express what CDP needs (pluggable codec) and what LSP needs (cancellation/progress policies). The deviation is documented in §17.

---

## 16. Consumer coverage proof

How each consumer's full requirement set maps to the engine. This is the section that determines whether the design is actually done.

### 16.1 LSP (8 design implications + body details from research/LSP.md)

| LSP need | Engine surface |
|---|---|
| Two-layer transport (bytes ↔ envelopes ↔ envelopes) | §4: bytes layer inside transport adapter; engine sees envelopes |
| Symmetric Endpoint, not Client/Server | §6 `JsonRpcEndpoint.init` runs the same both sides |
| Per-direction id allocation | §10: each endpoint owns its `outboundIds` counter independently |
| `Content-Length:` framed stdio | `JsonRpcTransport` impl in kyo-lsp (§14) |
| `$/cancelRequest` (must reply) | `CancellationPolicy.lsp` with `expectReplyForCancelledRequest=true`, error `-32800` |
| Receiver MAY interrupt handler, MUST reply | Engine completes `ctx.cancelled`; waits for handler; sends reply |
| Originator MUST tolerate late reply | Exchange's pending-map cleanup on caller interrupt + Exchange drops responses with no matching pending entry |
| Cancel for unknown id → silent drop | Engine looks up `pendingInbound`, finds nothing, drops |
| `$/progress` workDone (3-phase) | `endpoint.callWithProgress` returns live `progress: Stream` |
| `$/progress` partialResult (streamed) | `endpoint.callPartialResults[T]` returns `Stream[T]` |
| `window/workDoneProgress/create` (out-of-band token) | `endpoint.subscribeProgress(token): Stream[Structure.Value, ...]` |
| Error codes -32800..-32803, -32001, -32002 | Promoted into `JsonRpcError` constants (§15) |
| `$/`-prefix: drop unknown notifications | `UnknownMethodPolicy.lsp` |
| `$/`-prefix: MethodNotFound for unknown requests | Same |
| Initialize gate (pre-init → -32002) | `Config.gate = Present(LspInitGate)` in kyo-lsp |
| ServerCancelled (-32802) dual | `JsonRpcError.ServerCancelled`; emitted by handler via `Abort.fail` |

### 16.2 MCP (R1–R26 from research/MCP.md §10)

| MCP need | Engine surface |
|---|---|
| R1–R5 (envelope, Maybe optionals, notif=no-id, error codes, typed method builder) | §3, §5, §15 |
| R6 bidirectional engine | §6 |
| R7 pluggable framing | §4 transport |
| R8 pending-request correlation | §6.1 |
| R9 cancellation primitive | §7 with `CancellationPolicy.mcp`, no-reply |
| R10 progress primitive, consumer-side token | §8 with `ProgressPolicy.mcp` |
| R11 Frame propagation | every public method takes `(using Frame)` |
| R12 safety defaults (no AllowUnsafe in public, AtomicLong) | §6.1 internals |
| R13 Streamable HTTP adapter | kyo-mcp module; engine emits single envelope stream |
| R14 SSE event-id resumability | kyo-mcp adapter |
| R15 Mcp-Session-Id management | kyo-mcp adapter |
| R16 MCP-Protocol-Version header | kyo-mcp adapter |
| R17 `_meta` + `_meta.progressToken` | `ProgressPolicy.mcp.extractRequestToken` reads `params._meta.progressToken` |
| R18 Server-initiated requests on GET SSE | `endpoint.call` works both directions; transport routes by id |
| R19 capability + lifecycle layer | `MessageGate` in kyo-mcp |
| R20 all standard MCP methods | registered as `JsonRpcMethod` in kyo-mcp |
| R21 no batching | engine never batches |
| R22–R26 (do-not-do list) | all respected |
| MCP timeout SHOULD emit notifications/cancelled | §7 timeout auto-fires cancellation policy |
| `notifications/cancelled` → NO reply ever | `expectReplyForCancelledRequest=false`; engine interrupts handler AND writer suppresses queued reply (§6.5 race fix) |
| initialize MUST NOT be cancelled (MCP §5.1) | `CancellationPolicy.mcp.protectedMethods = Set("initialize")`; `endpoint.cancel` refuses |
| Progress MUST NOT be sent after response (MCP §6) | `HandlerCtx.progressSink` invalidated atomically on handler completion |
| Progress monotonic increase (MCP §6) | `ProgressPolicy.mcp.enforceMonotonic = true`; non-monotonic dropped |

### 16.3 CDP (10 design implications from research/CDP.md)

| CDP need | Engine surface |
|---|---|
| 1. Wrap Exchange, not reimplement | §2 Layer 0 / §6 |
| 2. Parametric envelope schema | `JsonRpcCodec.Cdp` |
| 3. Encoder closure knows external state AND assigned id (`Req = Id => Wire`) | `extras: ExtrasEncoder = JsonRpcId => Maybe[Structure.Value]` closure (§6) |
| 4. per-call timeout, in-flight semaphore, drain-on-close | `Config.requestTimeout`, `Config.maxInFlight`, `endpoint.awaitDrain` |
| 5. `sendNotification(wire)` bypasses pending map | `endpoint.notify(...)` |
| 6. errors decoded by caller from wire | engine routes `Structure.Value` for result; consumer decodes via `Schema[Out]` |
| 7. optional standard-error helper | `JsonRpcError` constants (§15) |
| 8. document Sync-only decode rule | §6.6 |
| 9. no sessionId in engine | lives in `JsonRpcEnvelope.extras`, opaque to engine |
| 10. public awaitDrain | `endpoint.awaitDrain` |
| Top-level `sessionId` in envelope | `JsonRpcEnvelope.extras = Present({sessionId: "..."})`; `JsonRpcCodec.Cdp` stamps to top level (rejecting reserved keys) |
| Single Int counter across sessions | `IdStrategy.SequentialInt`; one endpoint per WebSocket |
| `withSession(sid)` facade | kyo-browser wrapper calls `endpoint.call(method, params, extras = ExtrasEncoder.const(...))` |
| Events as method-no-id notifications | engine dispatches notifications by method; kyo-browser registers `JsonRpcMethod.notification` per event |
| Event whitelist | kyo-browser registers only desired events; rest drop via `UnknownMethodPolicy.strict` |
| `maxInFlight=8` on JS/Native | `Config.maxInFlight = Present(8)` |
| Fire-and-forget `Page.handleJavaScriptDialog` | `endpoint.notify(...)` (no negative-id workaround needed) |
| No on-wire cancellation | `Config.cancellation = Absent`; `cancel` aborts locally only |

Every row maps to an engine API named in this design. No consumer is asked to wrap.

---

## 17. Gap vs the original prompt

The `kyo-jsonrpc-PROMPT.md` is mostly compatible with this design. Specific deviations, each justified by a consumer requirement that surfaced during research:

| Prompt | Design | Reason |
|---|---|---|
| Public API: 7 named types | 21 named types | CDP needs pluggable codec + closure encoder; LSP needs progress/cancellation policies; engine needs HandlerCtx + ExtrasEncoder |
| `JsonRpcMethod.cancelRequest` canonical factory using LSP semantics | Cancellation is policy-driven (engine-level), not a method factory | MCP cancellation sends no reply ever; can't be a single factory |
| `$/progress` with workDoneToken in `endpoint.onProgress` API | Three APIs: `callWithProgress`, `callPartialResults[T]`, `subscribeProgress` | MCP uses `_meta.progressToken`; LSP uses `workDoneToken`; LSP also has `partialResultToken` and out-of-band `window/workDoneProgress/create` |
| `endpoint.sendProgress(token, value)` for handlers | `HandlerCtx.progress(value)` (token implicit from inbound params) | Handler shouldn't need to know token storage convention; policy extracts it |
| "Decide explicitly: batches OR document out of scope" | Hard out of scope | MCP removed batches in 2025-06-18; LSP/CDP never used them |
| `JsonRpcRequest/Response/Error/Id` as the only envelope types | Add `JsonRpcEnvelope` ADT + `JsonRpcCodec` | CDP is not JSON-RPC 2.0; the engine must abstract over wire shape |
| `Config` default params at top-level | Same; preserved for the user-facing Config case class | Per CLAUDE.md exception for public API entry points |
| LSP error codes (`-32800` etc) treated as LSP-specific | Promoted into `JsonRpcError` table | Useful outside LSP; cost-free |
| Cancellation only via engine.cancel + per-method factory | Engine `cancel(id)` + auto-fire on timeout + handler-observed via ctx.cancelled | Matches MCP "SHOULD on timeout" and LSP must-reply semantics |
| In-memory transport only in this module | Same | Unchanged |

---

## 18. Phased implementation

Each phase commits independently per CLAUDE.md.

0. **Module scaffold** (all inside kyo-jsonrpc):
   - Build.sbt: add `kyo-jsonrpc` as a crossProject (JVM + JS + Native), depending on `kyo-prelude`, `kyo-core`, `kyo-schema`. Plain `dependsOn`. Cross-platform settings matching `kyo-schema`.
   - No new helpers anywhere. Policy lambdas use `Schema[T].toStructureValue` (kyo-schema, `private[kyo]`, accessible in package `kyo`) plus direct pattern matching on `Structure.Value.Record(fields: Chunk[(String, Value)])`. The two trivial inline helpers (`field`, `merge`) referenced in §8 are `private inline def` inside `ProgressPolicy`'s companion, not new public surface.
   - No changes to kyo-schema, kyo-http, kyo-lsp, kyo-mcp, kyo-browser, or any other module.

1. **Wire types + codec.** `JsonRpcRequest/Response/Error/Id` with hand-written `JsonRpcId` Schema; `JsonRpcEnvelope` ADT with `derives Schema, CanEqual`; `JsonRpcCodec` trait with `Strict2_0` and `Cdp` implementations; reserved-keys reject in `Cdp.encode`. Tests:
   - round-trip every envelope shape under both codecs
   - `JsonRpcId.Num(1L)` ↔ `1`; `JsonRpcId.Str("a")` ↔ `"a"` (NOT `{"Num":1}`)
   - `extras = Absent` ↔ no extras keys on the wire
   - `extras = Present(Structure.Value.Null)` ↔ wire has explicit null
   - `Malformed` recovery when JSON is unparseable
   - `Maybe[JsonRpcId] = Absent` ↔ no `id` key on the wire (NOT `id: null`)
   - **Strict2_0**: response with both `result` AND `error` populated → decoder rejects with `Malformed`
   - **Cdp**: extras containing a reserved key (`method`, `id`, etc.) → encode rejects with `JsonRpcError.invalidRequest`

2. **JsonRpcMethod[+S]** with `Kind` and `HandlerCtx`. Direct port from kyo-ai-plugin plus the notification factory and the context-carrying overload. Tests: handler success/failure/panic, params decode failures, ctx.extras propagation, notification handler returns Unit and engine never sends reply (verify by counting frames on transport).

3. **JsonRpcTransport + inMemory.** Two transports cross-wired via Channels. Tests: send/receive round-trip, close propagation, backpressure, **transport-close-mid-call** (outstanding `call`s complete with `Abort.fail(Closed)`).

4. **JsonRpcEndpoint core** wrapping Exchange: routing through Exchange's `encode`/`decode` callbacks per §6.2, writer fiber with `SuppressIfCancelled` filter, `pendingInbound: ConcurrentHashMap[Id, InboundEntry]` with `Running`/`Replying` state machine, `callerRegistry: ConcurrentHashMap[Id, CallerInfo]` side-table, `Config.idStrategy` translated to Exchange's `nextId`, `Sync.ensure`-based callerRegistry cleanup, scope cleanup with §6.4 finalizer order. NO policies yet (cancellation/progress both `Absent`). Tests: A.call(B) round-trip, A.notify(B), unknown methods, concurrent bidirectional, Scope cleanup, callerRegistry cleanup on caller interrupt, `awaitDrain` returns when writer + Exchange's pending + pendingInbound all quiescent, **late reply for cancelled outbound is dropped by Exchange** (verify directly, not via engine bookkeeping).

5. **CancellationPolicy** with `.lsp` and `.mcp`. Engine-enforced semantics: writer-side suppress for MCP no-reply, handler-fiber interrupt on inbound cancel, caller-fiber interrupt on outbound cancel (Exchange handles late-reply drop). `protectedMethods` gate. Tests: each policy's inbound + outbound flow, late-reply drop is verified end-to-end (no engine bookkeeping involved), no-reply variant (verify reply does NOT appear on transport even though handler produced a value), cancel-for-unknown-id silent drop, timeout-fires-cancel, **cancel on initialize is refused (MCP protectedMethods)**, **cancellation race: cancel arrives while reply is queued in writer channel → MCP drops the reply**.

6. **ProgressPolicy** with `.lsp` and `.mcp`. `callWithProgress`, `callPartialResults[T]`, `subscribeProgress`/`unsubscribeProgress`, `HandlerCtx.progress`. Tests: workDone 3-phase, partial-result chunk streaming, MCP `_meta.progressToken`, LSP `workDoneToken`, out-of-band token subscription, no-progress when policy Absent, **monotonicity enforced when policy.enforceMonotonic = true (non-monotonic progress dropped)**, **`ctx.progress` after handler returned is suppressed (MCP MUST NOT)**.

7. **UnknownMethodPolicy + MessageGate.** Tests: $/-prefix drop, $/-prefix request → MethodNotFound, gate rejection (Request → error reply; Notification → drop), gate drop, gate Allow → normal dispatch.

8. **maxInFlight + requestTimeout.** Tests: semaphore behaviour under load, timeout fires + cancel notification dispatched when policy present, timeout fires + local abort only when policy Absent.

9. **Three-consumer scenario tests** (in-memory; transport adapters NOT built here):
   - **HTTP-style server-only**: single endpoint, methods registered, request → response, notification → no reply. Verify shape matches `HttpHandler.postJsonRpc` would produce.
   - **WebSocket-style client + event stream**: A sends commands, B sends both replies AND unsolicited notifications interleaved. Verify A demuxes correctly without mixing.
   - **Stdio-style fully bidirectional with cancellation**: both endpoints register methods, both call each other, cancellation works in both directions, `RequestCancelled` surfaces correctly on LSP policy.

10. **Cross-platform sweep**: JVM, JS, Native green. Sequential (per `feedback_sequential_test_runs`).

---

## 19. Resolved decisions

After the audit cycle (initial design → 3-consumer audit → 4-C audit):

1. **`JsonRpcEnvelope.extras: Maybe[Structure.Value]`** on every envelope variant (Request, Notification, Response). `Absent` ≠ `Present(Structure.Value.Null)`. Codec.Cdp stamps fields from `extras` to the top level on encode; harvests unknown top-level fields on decode. Reserved keys (`id`, `method`, `params`, `result`, `error`, `jsonrpc`) rejected with `JsonRpcError.invalidRequest`.
2. **Policies are `Maybe`d**, not `.none` sentinels. `Config.cancellation: Maybe[CancellationPolicy]`, `Config.progress: Maybe[ProgressPolicy]`. CDP uses `Absent`. `endpoint.cancel(id)` is always callable; only fires wire notification when policy is `Present`.
3. **No `Outcome` ADT.** Handler returns `Structure.Value < S` (or `Unit < S` for notifications). Engine decides whether to send a reply based on `(method.kind, cancellation state, was-cancelled)`. Removes policy-leak into handler code. `pendingInbound` is a state machine (`Running | Replying`), not `Fiber[Outcome, Any]`.
4. **All six LSP error codes promoted** into `JsonRpcError` constants (§15). They cost nothing as constants and several are useful outside LSP (`RequestCancelled`, `RequestFailed`, `ServerNotInitialized` for the gate).
5. **Per-call `extras` is a closure `ExtrasEncoder = JsonRpcId => Maybe[Structure.Value]`**, not a static `Maybe[Structure.Value]`. Preserves CDP's `Req = Id => Wire` pattern (the encoder closes over external state AND receives the engine-assigned id). `ExtrasEncoder.empty` and `ExtrasEncoder.const(v)` cover the trivial cases.
6. **Out-of-band progress via `endpoint.subscribeProgress(token)` + `unsubscribeProgress(token)`**. Covers LSP `window/workDoneProgress/create`.
7. **`callPartialResults[T]`** is a separate API from `callWithProgress`. The former returns a `Stream[T]` whose chunks come from `$/progress` and which closes on the final empty response. The latter returns a typed `Out` plus a live progress side-stream.
8. **Timeout auto-fires cancellation policy.** When `Config.requestTimeout` fires AND `Config.cancellation = Present`, the engine fires the cancel notification then aborts the pending. When policy is `Absent`, just abort locally. No new config knob.
9. **Engine-enforced cancellation semantics, race-safe.** Handler observes `ctx.cancelled` and reacts. For `expectReplyForCancelledRequest=false` (MCP), engine (a) interrupts the handler fiber and (b) suppresses any reply already queued in the writer channel via the `InboundEntry.Replying.suppress` flag (§6.5).
10. **`CancellationPolicy.protectedMethods: Set[String]`** carves out method names that cannot be cancelled. `MCP.lsp.protectedMethods = Set.empty`; `MCP.mcp.protectedMethods = Set("initialize")`.
11. **Policy lambdas use kyo-schema's existing API directly.** `Schema[T].toStructureValue(t)` projects a typed case class to `Structure.Value`. Field lookup and merge are pattern matches on `Structure.Value.Record(fields)`. No new helpers in kyo-schema, no new helpers in kyo-jsonrpc public surface. The two trivial inline `field` / `merge` helpers in §8 are private to `ProgressPolicy`'s companion.
12. **`HandlerCtx.progressSink` invalidates on handler completion.** A captured closure that outlives the handler returns silently rather than sending wire traffic (MCP MUST NOT).
13. **`ProgressPolicy.enforceMonotonic`** drops non-monotonic progress values when true (MCP). LSP/CDP false.
14. **No pre-allocated id parameter on `call`.** `notify` covers all fire-and-forget cases.

---

## 20. Implementation invariants

Distilled from the three reports + the audit cycle. These are non-obvious rules the engine implementation must follow; deviating from any of them breaks at least one consumer.

1. **Reader fiber never parks.** All per-envelope work is `Sync`. Handlers run on forked fibers. (Exchange contract; CDP critical.)
2. **Writer fiber serializes outbound.** Single fiber drains the writer channel. No two outbound frames interleave on a transport that isn't atomic (LSP stdio, CDP WebSocket frames).
3. **Pending entries are removed on EVERY exit path.** Caller interrupted, timeout fires, scope closes, send fails, cancellation fires: all paths use `Sync.ensure` or finalizer order in §6.4. Late replies must always find an empty slot, not stale state.
4. **Notifications never enter Exchange's pending map.** `endpoint.notify` writes to the engine's writer channel directly (bypassing `Exchange.apply` which would register a pending entry). No outbound id allocated.
5. **Codec decode failures don't kill the reader.** `Malformed` envelopes are recovered: if they had a parseable id, reader sends `Response(id, error = ParseError)`; otherwise log and continue.
6. **Two distinct id-keyed maps, with clear ownership.** Exchange's pending map (id → outbound promise; owned by Exchange) for calls we made. `pendingInbound` (id → handler fiber + suppress flag; owned by the engine) for calls we received and are still serving. The engine also keeps a thin `callerRegistry` (id → method + caller fiber) for cancel lookup, but that's just an index over Exchange's map, not a duplicate. (LSP report §2.)
7. **Per-direction id allocation.** Each endpoint owns ONE outbound counter (Exchange's `nextId`); the receiver's id space is independent. The receiver looks up incoming Response ids in its OWN Exchange's pending map; an incoming Request id is namespaced separately and lives in `pendingInbound`. Cross-direction collisions are impossible because the maps are different.
8. **Progress token allocation is engine-side.** Tokens are opaque to the user. Engine generates them (UUID or counter), maps to channels in `progressStreams`, cleans up on response or cancel.
9. **Handler panic → `InternalError` reply.** Panic message in `error.data`, generic message in `error.message`. Don't leak stack traces into the visible message field.
10. **No `params._meta.progressToken` parsing in the engine.** That's `ProgressPolicy.mcp.extractRequestToken`. Engine just calls the policy.
11. **`extras` is `Maybe[Structure.Value]`, not `Structure.Value`.** `Absent` means "no extras"; `Present(Structure.Value.Null)` means "extras slot exists, is JSON null". The two are observably different on the wire.
12. **Scope finalizer order (§6.4) is critical.** Closing the transport before letting Exchange drain its pending map would deadlock the writer fiber. Order is: poison writer channel → cancel fibers → close transport → close Exchange (fails all its pending with Closed) → drain pendingInbound.

## 21. What this design does NOT do

Per the prompt's "what NOT to do":

- No HTTP/WebSocket/stdio transports in this module. Adapter modules: kyo-http (HTTP, WS), kyo-lsp (stdio).
- No refactoring of kyo-browser, kyo-ai-harness, kyo-http JsonRpc in this PR. Those consumers migrate later.
- No consumer-specific method names or types. No `tools/list`, no `textDocument/hover`, no `Page.frameNavigated`.
- No codegen for metaModel.json or protocol.json.
- One concept named `JsonRpcEndpoint`; no `JsonRpcSession` / `JsonRpcPeer`.
- No first-class `sessionId` envelope field. CDP carries it via `extras`; MCP via HTTP header.

---

End of design. Ready for review.
