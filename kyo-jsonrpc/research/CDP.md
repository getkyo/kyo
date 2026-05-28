# CDP as a JSON-RPC variant: what a unified `kyo-jsonrpc` needs to accommodate

Inputs: kyo CDP layer on branch `kyo-browser`
(`kyo-browser/shared/src/main/scala/kyo/internal/{CdpClient,CdpBackend,CdpWire,CdpTypes}.scala`),
the `kyo.Exchange` primitive (`kyo-core/shared/src/main/scala/kyo/Exchange.scala`),
plus the CDP protocol docs (chromedevtools.github.io) and the canonical
"getting-started-with-cdp" tutorial. The `CdpWire.scala` file on the branch holds
helper decoders (Base64, eval result extraction); the actual wire **envelope** case
classes (`CdpEnvelope`, `CdpWireMessage`, `CdpReply`, `CdpEventParams`, `CdpError`,
`CdpEvent`) live at the bottom of `CdpClient.scala`.

## 1. Envelope shape and its deviations from JSON-RPC 2.0

CDP is JSON-RPC-shaped but not JSON-RPC 2.0 conformant. The shapes:

**Outgoing command (request)**
```json
{ "id": 1, "method": "Target.setDiscoverTargets", "params": { "discover": true }, "sessionId": "62584F..." }
```

**Successful reply**
```json
{ "id": 1, "result": { ... }, "sessionId": "62584F..." }
```

**Error reply**
```json
{ "id": 16, "error": { "code": -32601, "message": "'Inspector.enable' wasn't found" } }
```

**Event (notification)**
```json
{ "method": "Page.frameNavigated", "params": { ... }, "sessionId": "62584F..." }
```

Deviations from JSON-RPC 2.0:

- **No `"jsonrpc": "2.0"` field.** CDP omits it entirely from every message direction. JSON-RPC 2.0 mandates it on every request, response, and notification.
- **Extra top-level `sessionId` field** alongside `id`/`method`. This is the CDP "flattened sessions" extension and is not present in JSON-RPC 2.0.
- **Request `id` is a JSON number**, never a string and never `null`. JSON-RPC 2.0 allows string/number/null.
- **`params` is an object** (CDP commands never use positional/array params).
- **Notifications carry no `id` field at all** (same as JSON-RPC 2.0).
- **Error shape `{code, message}` is JSON-RPC-2.0-compatible** in structure, including the `-32xxx` code range (`-32601 = Method not found`, `-32602 = Invalid params`, `-32603 = Internal error` all observed in the wild) but CDP also returns CDP-specific positive/larger negative codes for things like "Object couldn't be returned by value" and "No frame with given id." There is **no `data` field** on errors in the canonical CDP envelope, in contrast to JSON-RPC 2.0's optional `error.data`. The kyo decoder defines `CdpError` minimally:
  ```scala
  final case class CdpError(code: Int, message: String) derives Schema, CanEqual
  ```
  No standard CDP error code table is published; clients treat `code` as an opaque int and key off `message`.

The kyo client encodes both envelopes:

```scala
final case class CdpEnvelope[P](id: Int, method: String, params: P, sessionId: Maybe[String]) derives Schema
final case class CdpWireMessage(id: Maybe[Int] = Absent, method: Maybe[String] = Absent,
                                sessionId: Maybe[String] = Absent, error: Maybe[CdpError] = Absent) derives Schema
final case class CdpReply[A](result: Maybe[A] = Absent, error: Maybe[CdpError] = Absent) derives Schema
final case class CdpEventParams[P](params: P) derives Schema
```

`CdpWireMessage` is the **routing-only header**: id (route to caller), method (event dispatch), sessionId (route to tab), error (early-classify). The polymorphic `result`/`params` payloads are not decoded at the router; they're re-decoded by the caller from the same wire string via `CdpReply[A]` or `CdpEventParams[P]`. This two-pass decode is deliberate: the dispatcher runs inside the Exchange reader fiber which is `Sync`-only, and method-specific schemas aren't known there.

## 2. Sessions

`sessionId` is the identifier returned by `Target.attachToTarget(targetId, flatten=true)`. After attach, every command targeting that tab must carry `sessionId` as a top-level field. Replies and events that originate from the attached target echo the `sessionId` back. The browser endpoint itself (Target/Browser domain) uses no `sessionId`.

**ID allocation is global per connection, not per session.** All sessions on a single WebSocket share one monotonically increasing request-id counter, because that counter is what the Exchange's `pendingMap` is keyed on. kyo's CDP code follows this:

```scala
exchange <- Exchange.initUnscoped[Int => String, String, String, CdpEvent, Closed](
  encode  = (id, mkWire) => mkWire(id),
  send    = wire => outbound.put(wire),
  receive = inbound.stream(),
  decode  = wire => decodeCdpMessage(wire, …)
)
```

The `Req` type passed to `Exchange` is `Int => String` — a request is a **wire-builder closure** that, once the Exchange assigns it an id, produces the JSON string with id stamped in. `Exchange` uses its default sequential `Int` counter. Session id is captured at construction time of the per-session `CdpClient.withSession(sid)`:

```scala
private[kyo] def withSession(sid: SessionId): CdpClient = new CdpClient(…, Present(sid))
private def submit[P: Schema](method, params) =
  val sid    = sessionId.map(_.value)
  val mkWire = (id: Int) => Json.encode(CdpEnvelope(id, method, params, sid))
  …
```

So `withSession` is a thin facade that shares the underlying exchange (and its global id counter, in-flight cap, dispatcher tables) but stamps a sessionId into outgoing envelopes. The exchange's pending-map only keys on id; sessionId is metadata for the **server's** routing, not the client's.

## 3. Events vs replies

Discrimination is by presence of `id`:

- has `id` AND (`result` or `error`) → reply, route to pending promise keyed by `id`.
- has `method` AND no `id` → notification (event), route by `method` string (and optionally by `sessionId` for per-tab fan-out).
- anything else → malformed / skip.

In kyo:

```scala
env.id match
  case Present(id) => Exchange.Message.Response(id, wire)
  case Absent =>
    env.method match
      case Present(method) =>
        // event routing: dialog interception, frame-context dispatch,
        // download dispatch, whitelist push, else Skip
      case Absent => Log.warn(...).andThen(Exchange.Message.Skip)
```

Events are not blindly pushed to the Exchange events channel. The kyo code maintains a small **opt-in whitelist** of events forwarded as `Exchange.Message.Push`:

```scala
private[kyo] val eventWhitelist: Set[String] = Set(
  "Page.downloadWillBegin",
  "Page.downloadProgress"
)
```

Everything else routes either through specialized per-session dispatchers (frame-context create/destroy events, dialog events) or returns `Skip`. This is a **CDP-specific firehose-mitigation**, not a general JSON-RPC concern: under normal browser activity Chrome emits hundreds of Page/Network/Runtime events per second, and a bounded event channel that nobody consumes would saturate immediately and stall the reader.

## 4. Multiplexing with `Exchange`

`Exchange` is kyo's general request/response multiplexer; the contract is:

> Each outgoing request is tagged with a unique ID, and incoming responses carry that ID so they can be routed back to the right caller. … A background reader fiber drains the `receive` stream and routes each decoded message by ID.

Three message classes the `decode` callback returns:

```scala
enum Message[+Id, +Resp, +Event]:
  case Response(id: Id, value: Resp)
  case Push(value: Event)
  case Skip
```

`decode` runs `Sync`-only — it cannot park. This is what forces kyo's CDP dispatcher to do all heavyweight per-event work (dialog queueing, per-session table lookup) via non-blocking `offer` / `AtomicRef.use`, never `await`.

Each in-flight request becomes a `Promise.Unsafe[Resp, Abort[E | Closed]]` in a `ConcurrentHashMap` keyed by id. The reader matches incoming `Response(id, …)` against the map; transport failure / orderly close fails every pending promise.

CDP fits Exchange cleanly because it is **single-reply per id** (no streaming results — long-running CDP operations emit progress events with no id and a terminal reply with the id). Exchange does not need an "id-keyed multi-reply" mode for CDP.

## 5. Why a `maxInFlight` cap

From `CdpClient`:

```scala
/** Maximum number of CDP commands allowed to be awaiting a response on a single connection at any instant.
  *
  * The CDP WebSocket is drained by exactly one reader fiber. When more responses arrive than that fiber can
  * decode-and-route in time, the inbound channel chain (HttpWebSocket.inbound → relay → CdpClient.inbound →
  * Exchange reader) saturates, the WebSocket read fiber stops pulling from the socket, Chrome's send buffer
  * fills, and Chrome closes the connection. On JVM the multi-threaded scheduler keeps the reader ahead of
  * the inflow; on JS/Native the single-threaded runtime cannot. Capping in-flight commands bounds the
  * reader's peak backlog to a level it can sustain across all platforms. */
private[kyo] val maxInFlight: Int = 8
```

The cap is enforced by a `Meter.initSemaphoreUnscoped(maxInFlight)` (`cdpMeter`) acquired around `exchange(mkWire)` in `submit`. It is **per-connection**, shared across all sessions on that WebSocket — `withSession` shares the parent's meter.

This is not strictly a JSON-RPC issue. It's a **single-threaded-runtime backpressure issue** that bites any high-fan-out RPC client on Node/Native. It would belong on a kyo-jsonrpc client as an **optional knob**, not a default. For a server (JVM only, multi-threaded) it is unnecessary; for a JS/Native browser-driving client it is essential. The smallest API: let the constructor take a `maxInFlight: Maybe[Int]`.

## 6. Cancellation

CDP has no cancellation notification on the wire. Once a command is sent, the client cannot tell Chrome "forget that one". kyo's CDP code unblocks hung commands two ways:

```scala
Async.timeout(requestTimeout)(exchange(mkWire))
```

A per-call `Async.timeout` is wrapped around the Exchange call. If it fires, the promise stays in the pending map; the eventual late reply matches and is silently discarded (the timeout already removed the caller's continuation). The `Async.timeout` is the **only** orderly cancellation mechanism a CDP client has.

The second is socket-close: `closeNow` interrupts the relay fiber and calls `exchange.close`, which fails every pending promise with `Closed`. This is the WebSocket-tear-down escape hatch.

`Page.handleJavaScriptDialog` is treated specially:

```scala
// Invariant: the drainer must NEVER await Chrome's response to handleJavaScriptDialog.
// If a browser context is disposed while a dialog is pending, Chrome will not emit a response,
// and an awaiting drainer would block indefinitely … Using `outbound.put` with a negative request id
// (which the exchange reader matches against no pending promise and silently discards) makes dialog
// dispatch fire-and-forget while still flowing through the same WebSocket as ordinary requests.
```

So the kyo CDP client deliberately injects **fire-and-forget messages** (negative ids the Exchange will Skip) for commands where Chrome may not reply. A unified JSON-RPC layer should expose a "fire and forget, do not register a pending promise" hook — at minimum on the Unsafe API path.

## 7. What kyo's CDP code currently does

Object map:

- **`CdpClient`** wraps the Exchange, owns the WebSocket relay fiber, the inbound/outbound `Channel[String]`s, the per-session dispatcher tables (frame events, download events, dialog handlers, dialog recorders), the in-flight semaphore (`cdpMeter`), the in-flight counter (for `awaitDrain`), and the request timeout. Public surface to the rest of kyo-browser: `send[P: Schema](method, params): String` and `withSession(sid)`.
- **`CdpBackend`** is the typed surface — one Scala function per CDP method (`navigate`, `captureScreenshot`, `getCookies`, `attachToTarget`, …), each calling `sender.send(method, params)` then `decodeOrFail[Result](_, method)`. This is the "stub" layer; it has no business logic.
- **`CdpWire`** holds Base64 helpers, eval-result extraction, exception formatting — none of which is JSON-RPC plumbing. The wire **envelope** types live in `CdpClient.scala` itself.
- **`CdpTypes`** holds the `Schema`-derived case classes for every CDP method's `params` and `result`, plus opaque-typed ids (`TargetId`, `SessionId`, `NodeId`, `FrameId`, `ExecutionContextId`, `NodeRef`).

Send/receive loop summary:

```scala
// Send: increment in-flight, run under the semaphore, with a timeout, encode id into envelope
val mkWire = (id: Int) => Json.encode(CdpEnvelope(id, method, params, sid))
…
cdpMeter.run { Async.timeout(requestTimeout)(exchange(mkWire)) }

// Receive: relay fiber pumps WS frames into `inbound`; Exchange reader pulls from `inbound`,
// runs decodeCdpMessage, routes Response/Push/Skip
HttpClient.webSocket(wsUrl, HttpHeaders.empty) { ws =>
  connectReady.completeUnit.andThen {
    val sender   = outbound.stream().foreach(msg => ws.put(HttpWebSocket.Payload.Text(msg)))
    val receiver = ws.stream.foreach { case HttpWebSocket.Payload.Text(s) => inbound.put(s); … }
    Async.race(sender, receiver).unit
  }
}
```

What lives in `CdpClient` ONLY because CDP is weird and would not belong in a generic kyo-jsonrpc engine:

- **`dialogQueue` + `dialogDrainer`** (fire-and-forget `Page.handleJavaScriptDialog` with negative ids). CDP-specific.
- **`frameEventDispatchers` / `downloadEventDispatchers` / `dialogRecorders`** — per-session per-method dispatch tables for specific CDP event flavors. CDP-specific.
- **`eventWhitelist`** — opt-in firehose suppression. CDP-specific in *motivation* (any RPC server can theoretically emit notifications, but only CDP does it at this volume).
- **`lastEvaluateParams`** — debugging hook for `Runtime.evaluate`. CDP-specific.
- **`drainSignal` + `awaitDrain`** — orderly-close support so the grace-period close can wait for in-flight calls. This **does belong** in a generic RPC client.
- **`requestTimeout` + `cdpMeter`** — per-call timeout and per-connection in-flight cap. These belong in the generic engine as configurable knobs.
- **`connectReady`** — gates `init` on the WebSocket handshake completing. Transport-specific; the engine should accept a pre-built bidirectional byte/text stream rather than owning WebSocket connect.

## 8. Constraints on a unified `kyo-jsonrpc`

What CDP needs, expressed as engine requirements:

1. **Pluggable envelope schema.** The engine must not assume `{jsonrpc:"2.0", id, method, params}` on the wire. It must let the caller supply both the encode-with-id closure (the `(Id, Req) => Wire` callback that Exchange already accepts) and the decode-classify function (`Wire => Message[Id, Resp, Event]`). CDP just plugs in its `CdpEnvelope` codec; a strict JSON-RPC 2.0 server plugs in one that adds `jsonrpc:"2.0"`.
2. **Routing only by id, not by jsonrpc-2.0 version field.** Discrimination of reply vs event is `presence-of-id`. Both CDP and JSON-RPC 2.0 agree on this; the engine should encode this rule directly via the `Message.Response | Push | Skip` return shape (which it already does).
3. **Sessions are out of scope for the engine.** Session multiplexing in CDP is server-side: `sessionId` is opaque to the client's id allocator. The engine should expose a way for **encoders to stamp arbitrary extra fields** into the envelope per call (CDP's `sessionId`, anyone's custom routing tag) without the engine itself knowing what those fields mean. kyo-browser already does this by parameterising `Req = Int => String` — the encoder *is* the closure that knows the sessionId.
4. **Error decoding is wire-shape-specific, not engine-level.** CDP errors come back **inside the reply slot** of the envelope; the engine returns the wire string verbatim and the caller's typed decoder (`decodeOrFail[A]`) interprets `{result|error}`. The engine should not try to interpret `error` itself. The "JSON-RPC standard error codes" table (-32700, -32600, …) is a per-protocol concern that wraps `CdpError`-shaped values; expose it as an optional helper, not a baked-in type.
5. **Notifications are per-protocol shape.** CDP events use `{method, params, sessionId}`. JSON-RPC 2.0 notifications use `{jsonrpc:"2.0", method, params}`. Both fit into `Message.Push(value)` where `value` is the post-classification typed event the user defines.
6. **Cap + timeout knobs on the engine itself.** Both `maxInFlight` (semaphore) and `requestTimeout` (per-call `Async.timeout`) are useful for every RPC protocol; bake them in as optional configs on the Exchange-wrapping client. The existing `Exchange` primitive does NOT provide these — they live in the kyo CDP layer. Generalising them up is the most useful unification step.
7. **Fire-and-forget commands.** Some CDP commands intentionally never get a reply (`Page.handleJavaScriptDialog` after a context teardown). The engine must let the caller send a wire message **without registering a pending promise**. Today the kyo CDP code does this by writing directly to `outbound` with a negative id; a cleaner shape would be a `sendNotification(wire)` method on the engine that bypasses the pending map.
8. **Caller-driven event whitelist.** Bounded event channels block readers; the dispatcher must be able to return `Skip` for events the user is not subscribed to. This is already in `Exchange.Message.Skip`; document it as the canonical "filter unwanted notifications here, NOT downstream of the channel" idiom.

### Natural seam

The seam is at **two layers**:

- **Wire-codec layer** (CDP-specific): supplies the envelope case classes (`CdpEnvelope`, `CdpWireMessage`, `CdpReply`, `CdpEventParams`, `CdpError`) and the `decode: Wire => Message[Id, Resp, Event]` classifier. This layer owns the JSON shape, the `sessionId` extension, the `result`-or-`error` post-routing decode, and the event whitelist.
- **RPC-engine layer** (generic, the kyo-jsonrpc module): wraps `kyo.Exchange` + a `Meter` (in-flight cap) + per-call `Async.timeout` + an `awaitDrain` lifecycle hook + a `sendNotification` (no-pending-promise) escape. It is parameterised over `Id`, `Wire`, `Req`, `Resp`, `Event`, `E`. CDP would instantiate it with `Id = Int`, `Wire = String`, `Req = Int => String` (the wire-builder closure pattern that lets the encoder reach a captured `sessionId`), `Resp = String` (whole-wire post-routing), `Event = (method, paramsJson, Maybe[SessionId])`, `E = Closed`.

What stays in CDP's hands, even with a unified engine:

- Per-session dispatch tables (`frameEventDispatchers`, `downloadEventDispatchers`, `dialogRecorders`, `dialogHandlers`). These are application-layer state, not RPC plumbing.
- The dialog drainer fiber.
- Negative-id "fire and forget" tagging — but only because the wire shape forces it; with a `sendNotification(wire)` engine API, this collapses to a single engine call.
- The event whitelist set, the `eventWhitelist` membership check, and the fallback-decode path for tolerant `error`-payload recovery.

## Design implications for kyo-jsonrpc

Concrete requirements derived from the CDP case:

1. **The engine is a wrapper around `kyo.Exchange`, not a reimplementation.** Exchange already does id-keyed multiplexing, reader-fiber routing, push events with backpressure, transport-error propagation, orderly close. kyo-jsonrpc adds the **JSON-RPC-shaped conveniences** layered on top, plus the runtime knobs (semaphore, per-call timeout, drain, send-notification).
2. **Make the envelope schema parameteric.** Ship two ready-built envelope codecs: strict JSON-RPC 2.0 (with `jsonrpc:"2.0"`) and "CDP-flavored" (no `jsonrpc`, top-level `sessionId` slot). Let users plug in their own. Do not bake `"jsonrpc":"2.0"` into the engine itself.
3. **The send signature should hand the caller the id.** Mirror `Req = Id => Wire` — let the encoder be a closure that knows external state (sessionId, custom headers, dynamic params) and receives the assigned id from the engine. Avoid an `(Id, Req) => Wire` signature that forces the protocol to define `Req` as a static struct.
4. **Bake in: per-call timeout, per-connection in-flight semaphore, drain-on-close.** All three are universal RPC-client concerns. CDP needs them; HTTP/2 needs them; LSP/MCP clients need them.
5. **Expose `sendNotification(wire): Unit < Async`.** Out-of-band messages that intentionally never get a reply must not enter the pending map. Without this, every RPC variant has to reinvent the negative-id-or-dummy-id workaround.
6. **Errors are decoded by the caller from the wire string.** The engine routes whole wire frames. The typed `decodeOrFail[A]` step that recognises `{result|error}` lives in the protocol layer, not the engine. This matches what CDP does today and avoids forcing the engine to know about JSON-RPC standard error codes.
7. **Provide an optional standard-error-code helper.** A `JsonRpcError(code, message, data)` case class with constants `MethodNotFound = -32601`, `InvalidParams = -32602`, etc. is useful for strict JSON-RPC 2.0 servers and as a default; not mandatory for CDP-style clients.
8. **Document the `Sync`-only-decode rule prominently.** It is non-obvious that the dispatcher cannot await. CDP's dispatcher works only because it offloads every parkable side-effect (dialog queueing, recording, error wrapping) into Channels or AtomicRefs. The kyo-jsonrpc README should call this out as the central protocol-author obligation.
9. **No `sessionId` in the engine.** Session multiplexing is server-side server-defined; the engine sees opaque envelopes. CDP's "withSession" facade pattern is the canonical way to do this — the engine doesn't need to know.
10. **Surface `awaitDrain` as a public API.** Graceful shutdown of an RPC client universally needs "wait until pending requests resolve, then close." Today this is custom code in `CdpClient`; promote it to the engine.
