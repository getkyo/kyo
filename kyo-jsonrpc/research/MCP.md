# MCP and JSON-RPC: research notes for kyo-jsonrpc

Scope: how the Model Context Protocol (MCP) uses JSON-RPC 2.0, what it adds on top, and what the existing `kyo` implementation on the `kyo-ai-plugin` branch already encodes. The goal is to enumerate concrete requirements that a unified `kyo-jsonrpc` module must satisfy if MCP is one of its consumers (alongside LSP and CDP).

Spec revision under study: **MCP 2025-06-18** (current). Notes on 2025-03-26 (the version the existing kyo code targets) are called out where they differ.

---

## 1. Transport

MCP defines two standard transports. JSON-RPC framing is identical at the envelope level; only delivery differs.

### 1.1 stdio

- Client launches server as subprocess.
- Newline-delimited JSON-RPC messages over `stdin`/`stdout`. UTF-8.
- Messages **MUST NOT** contain embedded newlines.
- `stderr` is reserved for free-form server logs.
- Shutdown is by closing `stdin`, then `SIGTERM`, then `SIGKILL`.

The kyo-cli daemon already uses this pattern (`kyo-cli/.../McpHandler.scala`): it spawns the plugin binary with arg `"mcp"`, keeps the child warm, serialises requests via a `Meter.initMutex`, writes one newline-terminated payload, reads until pipe close, drains stderr on a background thread to avoid pipe-buffer deadlock.

```scala
os.write(payload.getBytes(StandardCharsets.UTF_8))
os.write('\n'.toInt)
os.flush()
val sb = new StringBuilder
val buf = new Array[Byte](4096)
var n = is.read(buf)
while n > 0 do
  sb.append(new String(buf, 0, n, StandardCharsets.UTF_8))
  n = is.read(buf)
```

This is a single-shot "write request, read until EOF" handshake; the kyo-cli daemon does NOT implement full bidirectional stdio framing (no async notifications from server back to it).

### 1.2 Streamable HTTP (replaces the 2024-11-05 "HTTP+SSE" transport)

Single MCP endpoint URL (e.g. `/mcp`) that handles BOTH `POST` and `GET` (and optionally `DELETE` for explicit session termination).

#### POST flow

Every JSON-RPC message from the client is a fresh HTTP POST. The body is a **single** JSON-RPC request, notification, or response (no batching as of 2025-06-18). Client must send `Accept: application/json, text/event-stream`.

Server response shape depends on body type:

| Body type | Server response |
| --- | --- |
| Notification or response | `202 Accepted`, empty body |
| Request | Either `Content-Type: application/json` with one JSON-RPC response, OR `Content-Type: text/event-stream` initiating an SSE stream |

When the server opens SSE in reply to a POST:

- SSE stream MAY carry server-to-client requests/notifications *related to* the originating request before the final response.
- Stream SHOULD eventually contain the JSON-RPC response for the original request.
- Server SHOULD close the stream after sending the response.
- Disconnection is NOT cancellation — client must send `notifications/cancelled` to cancel.

#### GET flow

Client MAY issue `GET` to the MCP endpoint with `Accept: text/event-stream`. This opens a server-push SSE channel for server-to-client requests/notifications **unrelated** to any in-flight POST. Server MAY return `405 Method Not Allowed` if it does not offer one.

On this stream:
- Server MAY send requests and notifications.
- Server MUST NOT send a response except when *resuming* a previously-broken stream.

#### Resumability

Servers MAY tag SSE events with the standard SSE `id:` field. IDs are per-stream cursors, globally unique within the session. Clients reconnect with `Last-Event-ID` header on a GET; server replays missed events *from the same stream*.

#### Session management

- Server MAY assign a session by returning an `Mcp-Session-Id` header on the `InitializeResult` HTTP response. Visible ASCII, cryptographically secure (UUID/JWT/hash).
- Client MUST echo `Mcp-Session-Id` on all subsequent requests.
- Server returning `404` ⇒ session expired ⇒ client re-initialises with no session header.
- Server returning `400` ⇒ missing session header on a request that requires one.
- Client MAY send `DELETE` with the session id to terminate; server MAY answer `405`.

#### Protocol version header

`MCP-Protocol-Version: <protocol-version>` MUST be sent by the HTTP client on every request after `initialize`. Invalid/unsupported ⇒ server returns `400`. Missing on a server without other version-discovery ⇒ server SHOULD assume `2025-03-26`.

#### Multiple connections

Client MAY hold multiple SSE streams open. Server MUST NOT broadcast: each message goes on exactly one stream.

### 1.3 Custom transports

Spec is transport-agnostic. Implementers MAY add their own (e.g. WebSocket) as long as JSON-RPC framing and the lifecycle (§4 below) are preserved.

---

## 2. JSON-RPC envelope

MCP is JSON-RPC 2.0 with the following deltas from the base spec:

| | JSON-RPC 2.0 | MCP |
| --- | --- | --- |
| `id` allowed types | string, number, **null** | string, number (no `null`) |
| Reused ids in same session | allowed | forbidden |
| Batching | required | **removed in 2025-06-18** (was allowed in 2025-03-26) |
| Notification = no id | yes | yes |
| `result` xor `error` on response | yes | yes |
| Error code ranges | -32700..-32603 reserved, -32000..-32099 server-defined | inherits all of these; uses standard codes only |

MCP-specific non-envelope fields:

- **`params._meta.progressToken`**: optional string/integer attached by the *requester* to opt into progress notifications for that request. Token MUST be unique across active requests.
- **`_meta`** on additional interface types (added in 2025-06-18): general metadata escape hatch. Reserved key-prefix format (`modelcontextprotocol.io/`, `mcp.dev/`, etc.) for MCP-defined keys; otherwise free.

MCP does NOT define any *transport-level* JSON-RPC extension beyond `_meta`. Session id, protocol version, resumability event ids are all HTTP headers, NOT JSON-RPC envelope fields.

---

## 3. Bidirectionality

MCP is symmetric: both sides issue requests and notifications. Specific server-to-client requests in the spec:

| Method | Direction | Capability gate |
| --- | --- | --- |
| `sampling/createMessage` | server → client | client declares `sampling` |
| `elicitation/create` | server → client | client declares `elicitation` (new in 2025-06-18) |
| `roots/list` | server → client | client declares `roots` |
| `ping` | either way | always |

And server-to-client notifications:

| Notification | Description |
| --- | --- |
| `notifications/message` | server logging (capability `logging`) |
| `notifications/resources/updated` | a subscribed resource changed |
| `notifications/resources/list_changed` | the resource set changed |
| `notifications/tools/list_changed` | tools changed |
| `notifications/prompts/list_changed` | prompts changed |
| `notifications/progress` | progress on an in-flight request |
| `notifications/cancelled` | cancel an in-flight request |

Client-to-server symmetric notifications: `notifications/initialized`, `notifications/roots/list_changed`, `notifications/cancelled`, `notifications/progress`.

### How server-initiated traffic flows over Streamable HTTP

Because the HTTP transport is asymmetric (client makes requests; server answers), server-initiated requests must travel on an SSE channel:

- If there is an active POST whose response is held open as SSE: the server MAY send unrelated requests/notifications on that same stream, but the spec says they "SHOULD relate to the originating client request". In practice clients should NOT rely on this for cross-request traffic.
- The **dedicated GET-opened SSE channel** is the proper home for server-initiated traffic that is unrelated to any specific POST.
- Replies to server-initiated requests come back as new client POSTs (server gets a 202; correlation is by request id).

This is why event-id resumability matters for MCP-over-HTTP but is irrelevant to MCP-over-stdio.

---

## 4. Lifecycle

Three phases: **initialization**, **operation**, **shutdown**.

### Initialize handshake

Client sends `initialize` with `protocolVersion`, `capabilities`, `clientInfo` (`name`, `title` (2025-06-18), `version`). Server responds with `protocolVersion`, `capabilities`, `serverInfo`, optional `instructions`. Client sends `notifications/initialized`.

Version negotiation: client sends latest it supports. Server replies with the same if supported, otherwise the latest it supports. Mismatch ⇒ client disconnects.

Server capabilities (sub-cap booleans in parentheses):
- `tools` (`listChanged`)
- `resources` (`subscribe`, `listChanged`)
- `prompts` (`listChanged`)
- `logging`
- `completions`
- `experimental`

Client capabilities:
- `roots` (`listChanged`)
- `sampling`
- `elicitation` (2025-06-18)
- `experimental`

Before the handshake completes only `ping` (and server-side `logging` notifications) may flow.

### Shutdown

No JSON-RPC message; just close the transport. stdio: close stdin, SIGTERM, SIGKILL. HTTP: close connections (and optionally DELETE the session).

### Ping

`ping` is a bidirectional request with empty params and an empty result (used for liveness).

### Timeouts

Implementations SHOULD set per-request timeouts. On timeout, sender SHOULD emit `notifications/cancelled`. Implementations MAY reset the timeout on receiving a related progress notification but MUST enforce a maximum.

---

## 5. Cancellation & progress

### `notifications/cancelled`

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/cancelled",
  "params": { "requestId": "123", "reason": "User requested cancellation" }
}
```

- Either side can cancel.
- `initialize` MUST NOT be cancelled by the client.
- Receiver SHOULD stop work, free resources, NOT send a response.
- Receiver MAY ignore if id unknown / already done / un-cancellable.
- Sender SHOULD ignore any response that arrives after sending the cancel.
- Notification-only: no JSON-RPC error code is sent back when a request is cancelled. (Compare LSP, which mandates the request reply itself comes back as an error with code `-32800 RequestCancelled`. MCP **does not use -32800** at the wire level.)

### `notifications/progress`

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/progress",
  "params": {
    "progressToken": "abc123",
    "progress": 50,
    "total": 100,
    "message": "Reticulating splines..."
  }
}
```

Progress is *opt-in*: the requester attaches `params._meta.progressToken` to a request. The handler MAY emit any number of `notifications/progress` referencing that token. `progress` MUST be monotonically increasing; `total` and `message` are optional; floats allowed.

The receiver MUST NOT emit progress notifications after the response has been sent.

---

## 6. Method namespace

Pattern is `<feature>/<verb>` with all-lowercase, slash-separated tokens. Notifications are prefixed `notifications/`.

Server-feature methods:
- `tools/list`, `tools/call`
- `resources/list`, `resources/read`, `resources/templates/list`, `resources/subscribe`, `resources/unsubscribe`
- `prompts/list`, `prompts/get`
- `logging/setLevel`
- `completion/complete`

Client-feature methods (called by server):
- `sampling/createMessage`
- `elicitation/create` (2025-06-18)
- `roots/list`

Base methods (no namespace):
- `initialize`
- `ping`

Notification namespace (`notifications/...`):
- `notifications/initialized`
- `notifications/cancelled`
- `notifications/progress`
- `notifications/message` (server log)
- `notifications/resources/updated`, `notifications/resources/list_changed`
- `notifications/tools/list_changed`
- `notifications/prompts/list_changed`
- `notifications/roots/list_changed`

Names are arbitrary strings on the JSON-RPC layer; the namespace conventions are purely MCP.

---

## 7. Capabilities & versioning

- Both sides advertise capabilities on `initialize`. They MUST only use what was successfully negotiated (changed from SHOULD to MUST in 2025-06-18).
- Protocol version string is a date, e.g. `"2025-06-18"`. Negotiation happens *inside* the JSON-RPC envelope on `initialize`, but is *also* echoed on every HTTP request via `MCP-Protocol-Version`.

Implication: capability/version handling lives in the **MCP layer**, above plain JSON-RPC. A unified `kyo-jsonrpc` core should expose enough hooks for an MCP layer to plug in:
- access to per-request HTTP headers (for `MCP-Protocol-Version`),
- access to outgoing response headers (for `Mcp-Session-Id` on the `initialize` reply),
- session keying so a peer object can be threaded through call chains.

LSP and CDP do not use either of these HTTP-level concepts; the unified module should make them transport-side optionals.

---

## 8. The existing kyo MCP implementation

Files on `kyo-ai-plugin` (read via `git show`, not checked out):

```
kyo-http/shared/src/main/scala/kyo/JsonRpc.scala                                  (168 lines)
kyo-http/shared/src/main/scala/kyo/HttpHandler.scala         (postJsonRpc, getSseJsonRpc)
kyo-http/shared/src/main/scala/kyo/HttpClient.scala          (postJsonRpc, postJsonRpcUnit, getSseJsonRpc)
kyo-http/shared/src/test/scala/demo/McpServer.scala
kyo-http/shared/src/test/scala/kyo/JsonRpcTest.scala
kyo-http/shared/src/test/scala/kyo/JsonRpcHttpTest.scala
kyo-ai-harness/shared/src/main/scala/kyo/ai/harness/internal/mcp/McpProtocol.scala (172 lines, MCP types)
kyo-ai-harness/shared/src/main/scala/kyo/ai/harness/internal/mcp/McpMethods.scala  (189 lines, MCP dispatcher)
kyo-cli/shared/src/main/scala/kyo/cli/internal/daemon/McpHandler.scala             (stdio child via HTTP forward)
```

### 8.1 `JsonRpc.scala` — generic, MCP-agnostic

Four wire-type case classes and a typed-method builder. All MCP-agnostic.

```scala
case class JsonRpcRequest(
    jsonrpc: String,
    id: Maybe[JsonRpcId],
    method: String,
    params: Maybe[Json.Value]
) derives Schema, CanEqual

case class JsonRpcResponse(
    jsonrpc: String,
    id: Maybe[JsonRpcId],
    result: Maybe[Json.Value],
    error: Maybe[JsonRpcError]
) derives Schema, CanEqual

case class JsonRpcError(
    code: Int,
    message: String,
    data: Maybe[Json.Value] = Absent
) derives Schema, CanEqual

enum JsonRpcId derives CanEqual:
    case Num(value: Long)
    case Str(value: String)
```

Constants on `JsonRpcError`: `ParseError = -32700`, `InvalidRequest = -32600`, `MethodNotFound = -32601`, `InvalidParams = -32602`, `InternalError = -32603`. Factory helpers `methodNotFound`, `parseError`, `invalidParams`, `internalError`.

`JsonRpcId` has a hand-written flat `Schema`: encodes `Num` as a bare JSON number, `Str` as a bare JSON string (auto-derivation would produce `{"Num":{"value":1}}`, which violates the spec). This is one of the three "wire-format interop" bugs documented in `kyo-schema/HANDOFF-mcp-wire-interop.md` (the other two: `Structure.Value` and `Json.JsonSchema` auto-derivation also produce kyo-internal wrappers instead of canonical JSON; both fixed locally with hand-written schemas; should be folded into kyo-schema main).

`JsonRpcMethod[+S]` is the typed-handler builder:

```scala
sealed trait JsonRpcMethod[+S]:
    def name: String
    private[kyo] def schemaIn: Schema[?]
    private[kyo] def schemaOut: Schema[?]
    private[kyo] def handle(params: Json.Value)(using Frame):
        Json.Value < (Async & Abort[JsonRpcError])

object JsonRpcMethod:
    def apply[In: Schema, Out: Schema, S](name: String)(
        handler: In => Out < S
    )(using Frame, (Async & Abort[JsonRpcError]) <:< S): JsonRpcMethod[S]
```

Construction captures `Schema[In]` and `Schema[Out]` at definition time, plus the handler. The `(Async & Abort[JsonRpcError]) <:< S` evidence lets the handler be lifted into the dispatch context. `handle` decodes params (`InvalidParams` on Failure, `InternalError` on Panic), runs the handler, encodes the result, widens `S` via `ev.liftContra`.

This builder is JSON-RPC-generic. Nothing in it knows about MCP.

### 8.2 `HttpHandler.postJsonRpc` — server dispatcher

```scala
def postJsonRpc[S](path: String)(methods: JsonRpcMethod[S]*)(using Frame):
    HttpHandler["body" ~ JsonRpcRequest, "body" ~ JsonRpcResponse, Nothing] =
  val methodMap: Map[String, JsonRpcMethod[S]] = methods.map(m => m.name -> m).toMap
  val route = HttpRoute.postJson[JsonRpcResponse, JsonRpcRequest](path)
  route.handler { req =>
    val rpcReq = req.fields.body
    val id = rpcReq.id
    id match
      case Absent =>                        // notification: handler runs, HTTP 204 No Content
        methodMap.get(rpcReq.method) match
          case None => HttpResponse.halt(HttpResponse.noContent)
          case Some(method) =>
            val paramsValue = rpcReq.params.getOrElse(Structure.Value.Record(Chunk.empty))
            Abort.run[JsonRpcError](method.handle(paramsValue)).map { _ =>
              HttpResponse.halt(HttpResponse.noContent)
            }
      case Present(_) =>
        methodMap.get(rpcReq.method) match
          case None =>
            HttpResponse.ok(JsonRpcResponse.failure(id, JsonRpcError.methodNotFound(rpcReq.method)))
          case Some(method) =>
            val paramsValue = rpcReq.params.getOrElse(Structure.Value.Record(Chunk.empty))
            Abort.run[JsonRpcError](method.handle(paramsValue)).map {
              case Result.Success(result) => HttpResponse.ok(JsonRpcResponse.success(id, result))
              case Result.Failure(err)    => HttpResponse.ok(JsonRpcResponse.failure(id, err))
              case Result.Panic(t)        => HttpResponse.ok(JsonRpcResponse.failure(id, JsonRpcError.internalError(t.getMessage)))
          }
  }
```

Observations:

- **POST-only.** Handles `request` and `notification` bodies. No batching, no SSE-reply branch (the response is always plain `application/json`).
- **Notification path returns 204.** Spec calls for 202; current code uses 204. Both work, 202 is correct.
- **Unknown notification methods are silently 204'd.** Spec allows ignoring unknown notifications; this is fine.
- **Panic ⇒ InternalError.** Panic message goes into `error.message` directly (no sanitisation; potentially leaks impl info).

### 8.3 `HttpHandler.getSseJsonRpc` — server-push

```scala
def getSseJsonRpc(path: String)(stream: Stream[JsonRpcResponse, Async])(using Frame, ...):
    HttpHandler[Any, "body" ~ Stream[HttpSseEvent[JsonRpcResponse], Async], Nothing]
```

Takes a pre-built `Stream[JsonRpcResponse, Async]` and serves it as an SSE channel at the same path. The stream is **server-initiated and not request-dependent**; it has no access to client state.

`McpServer.scala` (the demo) uses this to push a heartbeat every 5 seconds for 60 iterations:

```scala
val heartbeatStream: Stream[JsonRpcResponse, Async] =
    Stream.init(1 to 60, chunkSize = 1).mapChunk { chunk =>
        Async.delay(5.seconds) {
            chunk.map { i =>
                JsonRpcResponse("2.0", Absent, Present(...), Absent)
            }
        }
    }
```

Limitations vs. spec:
- The stream type is `Stream[JsonRpcResponse, Async]` — only responses. The spec allows server-initiated *requests* and *notifications* over the GET SSE channel, which are `JsonRpcRequest`s (with or without id), not responses. Either the stream element type needs to widen to `JsonRpcMessage` or there need to be two channels.
- No SSE `id:` field, so no resumability. `Last-Event-ID` is unimplemented.
- No coordination between the POST handler and the GET stream: a POST handler cannot push out-of-band notifications on the GET channel.
- No `Mcp-Session-Id` issuance or validation.
- No `MCP-Protocol-Version` header handling.
- Cancellation is not modeled: if the SSE stream is closed while the POST handler is awaiting a downstream response, nothing tells the handler to stop.

### 8.4 Client side: `HttpClient.postJsonRpc` / `postJsonRpcUnit` / `getSseJsonRpc`

```scala
def postJsonRpc[In: Schema, Out: Schema](
    url: String | HttpUrl,
    method: String,
    params: In,
    headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty
)(using Frame): Out < (Async & Abort[HttpException | JsonRpcError])
```

Per-instance monotonic `AtomicLong` id counter:
```scala
private val jsonRpcIdCounter = new java.util.concurrent.atomic.AtomicLong(0L)
...
val id = Present(JsonRpcId.Num(jsonRpcIdCounter.incrementAndGet()))
```
This is private to `HttpClient`; reused across all calls on one client. Maps cleanly to MCP's "ids unique within session" rule *as long as one HttpClient maps to one session*. With multiple sessions on the same client the ids would collide across sessions; MCP allows that (uniqueness is per-session and per-direction) but the implementation is brittle.

Decoder path: if response carries `error`, fail with `JsonRpcError`. Otherwise decode `result` via captured `Schema[Out]`, fail with `JsonRpcError.invalidParams` on schema failure, `JsonRpcError.internalError` on panic. If neither result nor error is present, `internalError("Response has neither result nor error")`.

`postJsonRpcUnit` is for notifications (no `id`, body discarded).

`getSseJsonRpc` returns `Stream[JsonRpcResponse, Async & Abort[HttpException]]` over an SSE GET. Like the server side, the stream element type is `JsonRpcResponse` — the same widening issue applies.

The client does NOT:
- Track in-flight request ids ⇒ no correlation across an SSE response stream.
- Implement reconnection with `Last-Event-ID`.
- Handle `Mcp-Session-Id` (no header threading).
- Implement cancellation: there is no way to cancel an in-flight `postJsonRpc` and have the corresponding `notifications/cancelled` go out.

### 8.5 `McpProtocol.scala` — MCP-2025-03-26 wire types

All `case class ... derives Schema, CanEqual`. Covers:
- `InitializeParams`, `InitializeResult`, `ClientCapabilities`, `ServerCapabilities`, sub-capability records, `ClientInfo`/`ServerInfo`.
- `ToolMeta`, `ToolsListResult`, `ToolsCallParams`, `ToolsCallResult`, `Content` (with backticked `` `type` ``).
- `ResourceMeta`, `ResourcesListResult`, `ResourcesReadParams`, `ResourcesReadResult`, `ResourceContent`.
- `PromptMeta`, `PromptsListResult`, `PromptsGetParams`, `PromptsGetResult`, `PromptMessage`.
- `NoParams` — empty params record placeholder.

`Maybe[T] = Absent` for optionals (per `feedback_json_use_option`). `Chunk[T]` for collections (per `feedback_seq_vs_chunk`).

Missing from this set (relative to current spec):
- `elicitation/create` types (added 2025-06-18).
- `sampling/createMessage` types.
- `roots/list` types.
- `notifications/progress`, `notifications/cancelled` param types.
- `notifications/resources/updated`, `*list_changed`.
- `logging/setLevel`, `notifications/message`.
- `completion/complete`.
- `ping`.
- Resource template types, resource link types.

### 8.6 `McpMethods.scala` — MCP server dispatcher

Each method is a `JsonRpcMethod[Sync]` (or `Sync & Abort[JsonRpcError]`) factory:

```scala
def initialize(name: String, version: String)(using Frame): JsonRpcMethod[Sync] =
    JsonRpcMethod[InitializeParams, InitializeResult, Sync]("initialize"): _ =>
        InitializeResult(
            protocolVersion = "2025-03-26",
            capabilities = ServerCapabilities(
                tools = Present(ToolsCapability()),
                resources = Present(ResourcesCapability()),
                prompts = Present(PromptsCapability())
            ),
            serverInfo = ServerInfo(name = name, version = version)
        )
```

`mcpHandler` mounts the eight methods (initialize, notifications/initialized, tools/list, tools/call, resources/list, resources/read, prompts/list, prompts/get) at path `mcp` via `HttpHandler.postJsonRpc`.

The handler returned has effect `Nothing` in its third type parameter — i.e. fully discharged at construction. It plugs straight into `HttpServer.init`.

What's MCP-specific (not generic JSON-RPC):
- Hard-coded `protocolVersion: String = "2025-03-26"`.
- Capability shape and contents.
- Method names and the namespace convention.
- `Content` shape (`{ type, text }`).
- `tools/call` builds a `ToolsCallResult` of `Content("text", Json.encode(encodedOut))` — a serialisation choice.

What's MCP-incomplete in this dispatcher:
- No `ping`.
- Only one notification (`notifications/initialized`); no progress/cancelled.
- No bidirectionality: `mcpHandler` is server-only, with no path for the server to *issue* a request to the client (e.g. `sampling/createMessage`). The `getSseJsonRpc` server-push exists but is decoupled — no shared id counter, no response correlation, no session.
- No session id issuance.
- No `MCP-Protocol-Version` header validation.
- No `_meta` handling (so no progress tokens, no MCP-reserved metadata).
- Errors only via `Abort.fail(JsonRpcError)`. Panics get internal-error-with-message via the dispatcher; no domain-error mapping.

### 8.7 What the author baked in as "MCP-specific" vs "JSON-RPC-generic"

| Generic (in JsonRpc.scala / HttpHandler / HttpClient) | MCP-specific (in McpProtocol / McpMethods) |
| --- | --- |
| `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcError`, `JsonRpcId` | `InitializeParams`, `ToolsListResult`, ... wire types |
| Standard error codes & factories | Method names (`tools/list`, etc.) |
| `JsonRpcMethod[+S]` builder | Capability shape |
| `postJsonRpc` dispatcher (with notif/request branching) | Session model (absent here, would be MCP-only) |
| `getSseJsonRpc` (servlet + client) | Protocol version constant |
| Per-client monotonic id counter | `tools/call` ⇒ `Content` text-encoding choice |

The split is reasonable. The generic layer is approximately what a `kyo-jsonrpc` core would contain. The current generic layer is, however, missing several things that a unified module needs (see §10).

---

## 9. What MCP needs that the other two consumers (LSP, CDP) do not — or vice versa

This is the load-bearing section for the design.

### MCP-only (relative to LSP and CDP)

1. **HTTP/SSE asymmetric transport with session id and protocol-version headers.** LSP runs over stdio or named pipes; CDP runs over WebSocket. Only MCP needs an HTTP-layer with `Mcp-Session-Id`, `MCP-Protocol-Version`, GET-opened SSE, POST-as-SSE-reply, and `Last-Event-ID` resumability.
2. **Per-stream SSE event id assignment & replay.** Resumability is unique to MCP-over-HTTP. WebSocket reconnection in CDP and LSP's stdio do not need event-id cursors.
3. **`_meta` envelope extension on `params` (specifically `_meta.progressToken`).** LSP and CDP have their own progress patterns (LSP `$/progress` with a `WorkDoneToken` in params at the top level; CDP has no formal cancellation/progress, just timeouts and `id` correlation). MCP's `_meta` slot is a fixed convention.
4. **Capability negotiation with sub-capability booleans on initialize.** LSP also has capabilities, but the namespacing and the `listChanged`/`subscribe` sub-flags are MCP-specific. CDP has no capability negotiation — methods are discovered via `Schema.getDomains`.
5. **`notifications/cancelled` as a JSON-RPC notification (NOT a request-reply error).** LSP uses `$/cancelRequest` to cancel and replies to the original with `-32800 RequestCancelled`. MCP uses `notifications/cancelled` and the original request gets no response at all. CDP has no defined cancellation. This is the single most divergent semantic between MCP and LSP at the JSON-RPC layer.
6. **OAuth / authorization framework over HTTP** (since 2025-06-18: MCP servers are OAuth Resource Servers). LSP and CDP have nothing comparable.

### LSP-only (relative to MCP)

- `-32800 RequestCancelled`, `-32801 ContentModified`, `-32802 ServerCancelled`, `-32803 RequestFailed` reserved codes.
- `$/cancelRequest` and `$/progress` as JSON-RPC requests rather than `notifications/*` notifications.
- `Content-Length:` header framing over stdio (no newline-delimited single-line JSON; LSP uses HTTP-header-style framing).
- Method name convention with `$/` prefix for protocol-internal methods, `dollar/no-op` semantics.

### CDP-only (relative to MCP)

- WebSocket transport with one connection per session (no GET/POST split).
- Method namespace `Domain.method` (dot-separated), no notifications namespace (all events are notifications with method `Domain.eventName`).
- Targets / sessions multiplexed via a `sessionId` *inside the envelope* (not via HTTP header). This is the strongest argument for a generic "session" field at the envelope level.
- No formal cancellation; no formal capability negotiation; no formal versioning.
- "Flatten" command syntax (one connection can fan out across multiple targets via sessionId).

### MCP can live without (that LSP and CDP need)

- `Content-Length:` framing for stdio. MCP uses newline-delimited JSON on stdio.
- Numeric reserved codes -32800..-32803 for cancellation/contentModified/etc. MCP doesn't use them.
- WebSocket transport. (MCP doesn't define one; users can add custom.)
- Per-envelope `sessionId` field. MCP keeps session in HTTP headers, not in the JSON-RPC body.

---

## 10. Design implications for kyo-jsonrpc

Concrete requirements derived from the above. Numbered so they're easy to reference back when the module is being designed.

### Core (must-have, used by every consumer)

R1. **Envelope types.** `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcError`, `JsonRpcId` with hand-written `Schema` for `JsonRpcId` (string OR number, no wrapper). Existing types in `JsonRpc.scala` are fit-for-purpose; lift them out of `kyo-http` into `kyo-jsonrpc`.

R2. **`Maybe` for `params` / `id` / `result` / `error`** — preserve the current spec-faithful absent-vs-null distinction.

R3. **Notification = request with `id = Absent`.** No separate type. (Matches current code.)

R4. **Standard error codes & factories.** Constants `-32700..-32603`, helpers `methodNotFound`, `invalidParams`, etc. (Matches current code.)

R5. **Typed method builder `JsonRpcMethod[+S]`** with captured `Schema[In]` and `Schema[Out]`, decode-failure ⇒ `InvalidParams`, panic ⇒ `InternalError`. (Matches current code.)

R6. **A bidirectional engine, not a "server dispatcher".** The `JsonRpcEngine` should be symmetric: both sides have a method registry and a pending-request table keyed by `JsonRpcId`. Each side can `call`, `notify`, register `handlers`. The current `postJsonRpc` is server-only and one-shot per HTTP transaction; a proper engine pre-supposes a persistent connection.

R7. **Pluggable framing.** The core must NOT bake in HTTP, stdio, or WebSocket. The transport surface is something like:
- `outbound: JsonRpcMessage => Unit < Async` (send a single message)
- `inbound: Stream[JsonRpcMessage, Async]` (receive)

Concrete transports adapt to this:
- stdio (newline-delimited or LSP `Content-Length:` framing — both should be available).
- WebSocket (one message per frame).
- HTTP Streamable (POST + GET-SSE composite).

R8. **Pending-request correlation.** Engine maintains a map `JsonRpcId -> Promise[JsonRpcResponse]`. On outbound `call`, allocates a fresh id (monotonic counter scoped to the *session*, not the client), parks a promise, sends. On inbound response, completes the promise. Timeouts handled at the call site.

R9. **Cancellation primitive.** Engine exposes `cancel(id, reason)` that:
- emits an MCP-style `notifications/cancelled` (or LSP-style `$/cancelRequest`, or whatever the consumer wires in),
- aborts the local pending promise so callers see an error.

The exact wire shape is consumer-specific. Provide:
- a hook on the engine for "the consumer's cancel notification name and params shape",
- and a hook for "the consumer's cancellation error to put in the local pending promise" (MCP: nothing — just abort; LSP: a `JsonRpcError(-32800, "Request cancelled")`).

R10. **Progress primitive.** Engine exposes `progress(token, ...)` to emit progress notifications, and a way to read incoming progress on a pending call. Token storage is *not* in the JSON-RPC envelope (it's a consumer convention: MCP puts it in `params._meta`, LSP puts it in `params.workDoneToken`). Make this a per-consumer adapter.

R11. **Frame propagation.** The library is in `kyo.*` so Frame auto-derivation is disabled; every API must take `(using Frame)` (matches existing code).

R12. **Safety defaults.** Per CLAUDE.md `feedback_no_unsafe`: no `AllowUnsafe`, no `Frame.internal`. Per `feedback_atomic_not_var`: use `AtomicRef`/`AtomicLong` for the id counter and pending-request map (current `HttpClient.jsonRpcIdCounter` already does this).

### MCP-specific extensions (in a `kyo-mcp` consumer module, not in `kyo-jsonrpc` core)

R13. **Streamable HTTP transport adapter.** A `kyo-mcp` (or similar consumer) implements:
- Single endpoint with `POST` + `GET` + optional `DELETE` handlers wired to the same engine.
- POST body ⇒ engine.inbound. POST reply ⇒ engine.outbound *for that request's response*, with branching: if no other server-initiated message has piggy-backed, return `application/json`; if there has, upgrade to `text/event-stream`.
- GET ⇒ open a long-lived SSE stream subscribed to engine.outbound for "messages unrelated to in-flight requests" (server-initiated only).
- The engine must therefore expose **per-correlation routing** of outbound traffic, not a single fire-hose stream. This is a non-trivial requirement on the core.

R14. **SSE event-id resumability.** Each SSE stream gets monotonic per-stream ids. Server keeps a bounded replay buffer per stream. `Last-Event-ID` on reconnect ⇒ replay. This is HTTP-only and belongs in the HTTP transport adapter, but the core engine must allow the transport to assign + persist a cursor.

R15. **Session id management.** `Mcp-Session-Id` is generated on `initialize` reply; validated on every subsequent request; `404` on stale; `400` on missing-when-required. The session id is the key that identifies one engine instance among many in the server.

R16. **`MCP-Protocol-Version` header.** Validate on every non-initialize request; `400` on mismatch. The session object holds the negotiated version.

R17. **`_meta` and `_meta.progressToken`.** Add support to the engine for an opt-in "request metadata" sidecar so MCP can attach/extract `params._meta.progressToken` without leaking MCP semantics into the core envelope.

R18. **Server-initiated requests on the GET SSE channel.** Whatever the engine's outbound surface looks like (R13), it must support sending JSON-RPC *requests* (with id) over SSE, parking a pending-promise on the server side, and receiving the client's POSTed response back via the POST path.

R19. **Capability + lifecycle layer.** Hard-code MCP-2025-06-18 (and emit `2025-03-26` for back-compat negotiation if a client requests it). Wire `initialize` ⇒ session creation ⇒ capability shapshot. Wire `notifications/initialized` ⇒ "operation phase" gate. Wire `ping` everywhere. Reject non-ping requests before `initialized`.

R20. **All standard MCP methods and notifications.** Beyond what the current implementation has, add `sampling/createMessage`, `elicitation/create`, `roots/list`, `notifications/progress`, `notifications/cancelled`, `notifications/message`, `notifications/resources/updated`, `notifications/*/list_changed`, `logging/setLevel`, `completion/complete`, `ping`. (Wire types in McpProtocol need expanding; the auto-derivation bugs in `kyo-schema/HANDOFF-mcp-wire-interop.md` must be addressed at schema level so we stop hand-writing `Schema[T]`.)

R21. **No batching.** Per 2025-06-18, batching is removed. Do not implement it. (LSP doesn't use batching either. JSON-RPC 2.0 spec allows it; we just won't.)

### Things the unified module should explicitly NOT do

R22. **No baked-in transport.** No assumption of HTTP, stdio, or WebSocket in `kyo-jsonrpc` core.

R23. **No baked-in cancellation semantics.** Don't mandate LSP's `-32800` or MCP's `notifications/cancelled`; let the consumer decide.

R24. **No baked-in capability or lifecycle model.** That's per-protocol.

R25. **No baked-in progress token convention.** `params._meta.progressToken` is MCP; `params.workDoneToken` is LSP; CDP has nothing. Make this consumer-side.

R26. **No baked-in session id in the envelope.** CDP puts `sessionId` in the envelope; MCP puts it in HTTP headers. The engine should support both *via the transport adapter*, not by adding a field to `JsonRpcRequest`.

### Migration notes

- The existing `JsonRpc.scala`, server `postJsonRpc`/`getSseJsonRpc`, and client `postJsonRpc*`/`getSseJsonRpc` are good as a *thin HTTP convenience layer for one-shot JSON-RPC over POST*. They are not sufficient for full bidirectional MCP. Keep the convenience surface, but layer a real engine underneath.
- The current `McpMethods.mcpHandler` is essentially `engine.bind(POST endpoint)` + `engine.register(methods)`. After the refactor it becomes `MCP.session(transport).register(methods)` with the engine doing the heavy lifting.
- The three schema-derivation bugs in `kyo-schema/HANDOFF-mcp-wire-interop.md` (`Structure.Value`, `Json.JsonSchema`, parameterless-enum-as-ADT) are blockers for MCP interop with real clients. Fold the local hand-written schemas back into kyo-schema; address them before/with kyo-jsonrpc.

---

## 11. Quick reference: relevant JSON-RPC error codes seen

| Code | Name | Where it appears |
| --- | --- | --- |
| -32700 | Parse error | JSON-RPC base, current kyo `ParseError` |
| -32600 | Invalid Request | JSON-RPC base, current kyo `InvalidRequest` |
| -32601 | Method not found | JSON-RPC base, current kyo `MethodNotFound` |
| -32602 | Invalid params | JSON-RPC base, current kyo `InvalidParams`, used for protocol version mismatch in MCP `initialize` |
| -32603 | Internal error | JSON-RPC base, current kyo `InternalError` |
| -32000..-32099 | Server-defined | reserved range; LSP uses -32800..-32803 here |
| -32800 | LSP RequestCancelled | LSP-only; **not used by MCP** |
| -32801 | LSP ContentModified | LSP-only |
| -32802 | LSP ServerCancelled | LSP-only |
| -32803 | LSP RequestFailed | LSP-only |

MCP cancellation produces *no error response at all* — the original request just goes unanswered, and the canceler `notifications/cancelled`s the id.

---

## 12. Wire examples (canonical)

Initialize:

```json
// → C2S
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-06-18",
    "capabilities": { "roots": { "listChanged": true }, "sampling": {}, "elicitation": {} },
    "clientInfo": { "name": "ExampleClient", "title": "Example Client", "version": "1.0.0" }
  }
}

// ← S2C  (HTTP: also sets Mcp-Session-Id: <id> on the response headers)
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-06-18",
    "capabilities": {
      "logging": {},
      "prompts": { "listChanged": true },
      "resources": { "subscribe": true, "listChanged": true },
      "tools": { "listChanged": true }
    },
    "serverInfo": { "name": "ExampleServer", "title": "Example Server", "version": "1.0.0" },
    "instructions": "Optional"
  }
}

// → C2S
{ "jsonrpc": "2.0", "method": "notifications/initialized" }
```

Tool call with progress token:

```json
// → C2S
{
  "jsonrpc": "2.0", "id": 42, "method": "tools/call",
  "params": {
    "name": "long_running_tool",
    "arguments": { "input": "..." },
    "_meta": { "progressToken": "tk-1" }
  }
}

// ← S2C  (any number of these, on the same SSE stream as the POST reply, or on a GET stream)
{ "jsonrpc": "2.0", "method": "notifications/progress",
  "params": { "progressToken": "tk-1", "progress": 0.3, "total": 1.0, "message": "..." } }

// ← S2C  (final response for id 42)
{ "jsonrpc": "2.0", "id": 42, "result": { "content": [ { "type": "text", "text": "done" } ] } }
```

Cancellation:

```json
// → C2S (or → S2C)
{ "jsonrpc": "2.0", "method": "notifications/cancelled",
  "params": { "requestId": 42, "reason": "User pressed escape" } }
// No response for id 42 is sent (or, if one was already on the wire, the sender of the cancel ignores it).
```

Server-initiated sampling:

```json
// ← S2C   (server sends this on a GET-SSE stream; client receives, processes, replies via POST)
{
  "jsonrpc": "2.0", "id": "srv-7", "method": "sampling/createMessage",
  "params": { "messages": [ ... ], "maxTokens": 200 }
}

// → C2S  (client POSTs the response; server gets 202 from its own outbound POST handler? — no:
//         the client posts back a JsonRpcResponse, server's POST handler routes it to the
//         pending-promise for id "srv-7" and replies 202 Accepted)
{ "jsonrpc": "2.0", "id": "srv-7", "result": { "role": "assistant", "content": {...}, "model": "...", "stopReason": "endTurn" } }
```

---

## 13. Summary checklist

The unified `kyo-jsonrpc` module is the right place for: envelope types, error codes, typed method builder, bidirectional engine with pending-request correlation, pluggable transport surface, cancellation primitive, progress primitive (with consumer-supplied wire shape), notification primitive, framing helpers (newline, LSP-style `Content-Length:`, SSE adapter shape).

NOT in the core: HTTP Streamable wiring (lives in `kyo-mcp` and reuses `kyo-http`); MCP/LSP/CDP method namespaces; capability negotiation; protocol version constants; OAuth; session id; `_meta` parsing.

Existing kyo MCP code is roughly half the design: the wire types and the typed-method builder are correct; the engine, the transport composability, the bidirectionality, and the session/version layering are missing.
