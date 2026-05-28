# LSP over JSON-RPC: requirements for a unified `kyo-jsonrpc`

LSP 3.17 is the most demanding JSON-RPC profile of the three consumers we care
about (LSP, MCP, CDP). It runs symmetric bidirectional traffic over an
HTTP-like header-framed stream, layers cancellation and streaming progress on
top of vanilla JSON-RPC, and adds its own error-code dialect. This document
characterises what `kyo-lsp` would need from `kyo-jsonrpc` so the unified
module ships those primitives once instead of three times.

References:
- LSP 3.17 specification (Base Protocol, Lifecycle, Cancellation, Progress, Error codes)
- JSON-RPC 2.0 specification (transport-agnostic, error-code ranges)
- `vscode-languageserver-node` (`jsonrpc/src/common/messageReader.ts`)
- `eclipse-lsp4j` (`StreamMessageProducer.java`)
- `kyo-ai-plugin:kyo-http/.../JsonRpc.scala` — existing kyo primitives

---

## 1. Transport / framing

LSP frames every JSON-RPC message with an HTTP-like header block followed by
a UTF-8 JSON body. The grammar is fixed, the ABNF flat, and every reference
implementation reduces to the same character-by-character state machine.

### Headers

Quoting the spec:

> The header part consists of header fields. Each header field is comprised
> of a name and a value, separated by ': ' (a colon and a space).

> Considering the last header field and the overall header itself are each
> terminated with '\r\n', and that at least one header is mandatory, this
> means that two '\r\n' sequences always immediately precede the content
> part of a message.

So the body is preceded by the exact byte sequence `\r\n\r\n`. The spec's
example:

```
Content-Length: ...\r\n
\r\n
{ "jsonrpc": "2.0", ... }
```

Defined headers:

| Header | Type | Default | Mandatory |
|--------|------|---------|-----------|
| `Content-Length` | integer byte-count | — | yes |
| `Content-Type` | MIME with charset | `application/vscode-jsonrpc; charset=utf-8` | no |

### Charset

> The content part is encoded using the charset provided in the Content-Type
> field. It defaults to `utf-8`, which is the only encoding supported right
> now. If a server or client receives a header with a different encoding
> than `utf-8` it should respond with an error.

> For backwards compatibility it is highly recommended that a client and a
> server treat the string `utf8` as `utf-8`.

i.e. parse the charset, but only `utf-8` and the alias `utf8` are accepted;
anything else triggers an error reply (this is a `kyo-lsp` concern, not
`kyo-jsonrpc`'s, but the transport must expose enough metadata to make the
decision — i.e. surface the parsed Content-Type to the upper layer or do the
check itself).

### Case sensitivity

The LSP spec does not state this explicitly. The two reference impls disagree
in detail:

- `vscode-languageserver-node` lower-cases on lookup (`headers.get('content-length')`).
- `lsp4j` uses a case-sensitive switch on `CONTENT_LENGTH_HEADER` / `CONTENT_TYPE_HEADER`.

`HTTP/1.1` (the cited inspiration) makes field names case-insensitive. The
safe interop choice is case-insensitive header lookup — vscode's behaviour
is the de-facto contract. Anything else risks rejecting traffic from
implementations that send `content-length:` or `CONTENT-LENGTH:`.

### Parsing state machine

Common shape across both impls:

1. Accumulate bytes into a buffer. Scan for the next `\r\n`.
2. For each header line: split at the first `':'`, trim, lower-case the name,
   store into a header map. An empty line (the `\r\n` immediately following
   the previous `\r\n`) signals end of headers.
3. Require `Content-Length`; parse as integer; otherwise emit error
   `"Header must provide a Content-Length property"` (vscode) /
   `"Missing header Content-Length-Header in input"` (lsp4j) and tear down
   the stream.
4. Optionally parse `Content-Type` for `charset=...`; default `utf-8`;
   reject anything else.
5. Wait until `Content-Length` bytes are available; slice exactly that many;
   decode using the chosen charset; deliver as one JSON-RPC message.

Edge cases an implementation must handle:

- **Missing `Content-Length`**: fatal at the framing layer; emit error and
  drop the connection / surface a `JsonRpcTransport` failure.
- **Non-numeric `Content-Length`**: same.
- **Mismatched / unknown charset**: per spec, reply with an error; vscode
  treats this as fatal to the message.
- **Premature EOF mid-headers**: yield no message; the surrounding driver
  decides whether the connection has gone away.
- **Premature EOF mid-body** (more bytes promised than delivered before EOF):
  vscode arms a `partialMessageTimeout` (default 10s) that fires a partial-
  message event; on true EOF, surface a transport error. lsp4j hard-errors.
- **Empty body** (`Content-Length: 0`): legal at the framing layer; the
  upper layer will reject it as invalid JSON.
- **Header injection / oversized headers**: not addressed by the spec.
  Implementations should cap the pre-`\r\n\r\n` byte count.
- **Lone `\n` vs `\r\n`**: lsp4j tolerates `\n` and silently skips `\r`;
  vscode enforces `\r\n` via its buffer helper. Defensive behaviour is to
  accept `\n` but emit `\r\n` on send.

Headers are ASCII; body is UTF-8. The transport adapter therefore needs to
decode the header block under ASCII (or, safely, ISO-8859-1) before knowing
the body charset.

---

## 2. Bidirectional initiation and id namespacing

LSP is symmetric at the wire layer: both client and server initiate
requests, both send notifications. The spec's list of server-to-client
messages includes ordinary requests with response correlation:

| Method | Direction | Kind |
|--------|-----------|------|
| `window/showMessageRequest` | S→C | request |
| `window/workDoneProgress/create` | S→C | request |
| `workspace/configuration` | S→C | request |
| `workspace/applyEdit` | S→C | request |
| `client/registerCapability` | S→C | request |
| `client/unregisterCapability` | S→C | request |
| `textDocument/publishDiagnostics` | S→C | notification |
| `window/logMessage`, `$/logTrace`, `telemetry/event` | S→C | notification |
| `$/setTrace`, `$/cancelRequest`, `$/progress` | both | notification |

The spec is silent on whether request ids are global or per-direction. JSON-RPC
2.0 is also silent. In practice every reference implementation allocates ids
per-direction: each side maintains its own monotonic counter for outbound
requests, and the receiver routes incoming responses by matching against
its own outbound-pending table keyed by the id it allocated. So **server id
#5 and client id #5 are different requests**; there is no collision
because the disambiguator is "did I send this request?", not the id alone.

Concretely an endpoint has two tables:

- `pendingOutbound: Map[Id, Promise[Response]]` — requests we issued, awaiting their reply.
- `pendingInbound: Map[Id, CancelToken]` — requests we received and are still serving, so `$/cancelRequest` can find them.

A `Response` envelope is routed by checking `pendingOutbound`. A `Request`
envelope is routed by `method` lookup and registered in `pendingInbound`.
Ids in the two tables are independent.

`kyo-jsonrpc` therefore needs a symmetric `Endpoint` abstraction that is not
client-or-server-shaped: same API both sides, same id allocator, same
dispatch tables.

---

## 3. Cancellation

`$/cancelRequest` is a notification with params `{ id: integer | string }`.
Quoting the spec:

> A request that got canceled still needs to return from the server and
> send a response back. It can not be left open / hanging. This is in line
> with the JSON-RPC protocol that requires that every request sends a
> response back. In addition it allows for returning partial results on
> cancel.

> If the request returns an error response on cancellation it is advised
> to set the error code to `ErrorCodes.RequestCancelled`.

The contract:

1. Originator sends request with id `X`, then later sends `$/cancelRequest { id: X }`.
2. Receiver MAY interrupt the in-flight handler. It still MUST reply for id
   `X` — either with a normal result (the request finished before the cancel
   landed), with a partial result, or with an error response whose code is
   `RequestCancelled = -32800`.
3. Originator MUST tolerate either outcome: the request can resolve as
   success or as a `RequestCancelled` error. It MUST tolerate a late reply
   arriving after it has already torn down its local future for the request
   — in that case, drop the reply.
4. If the cancel arrives for an unknown id (already-completed request,
   never-sent id), drop it silently — `$/cancelRequest` is a notification,
   so there is no error to return.

State diagram for one outbound request from the originator's side:

```
SENT -- response arrives --> DONE
SENT -- local cancel issued --> CANCEL_PENDING
CANCEL_PENDING -- response arrives (any) --> DONE  (do not surface; treat as cancelled)
DONE -- late response arrives --> DROP
```

And from the receiver's side:

```
RECEIVED -- handler completes --> RESPONDED
RECEIVED -- $/cancelRequest arrives --> CANCEL_RECEIVED
CANCEL_RECEIVED -- handler observes & aborts --> RESPONDED (with -32800 or partial)
CANCEL_RECEIVED -- handler completes anyway --> RESPONDED (normal result)
```

`ServerCancelled = -32802` is the dual: the server initiates the
cancellation and replies with this code. Per spec:

> The server cancelled the request. This error code should only be used
> for requests that explicitly support being server cancellable.

These are protocol-level codes; `kyo-jsonrpc` needs them as constants and
needs the transport to plumb cancellation tokens into handler invocations.

---

## 4. Progress

Two distinct streaming mechanisms share the same `$/progress` notification:

### Work-done progress (3-phase)

The originator includes `workDoneToken?: ProgressToken` in the request
params. The receiver sends `$/progress { token, value }` with `value` typed
as one of:

- `WorkDoneProgressBegin { kind: 'begin', title, cancellable?, message?, percentage? }`
- `WorkDoneProgressReport { kind: 'report', cancellable?, message?, percentage? }` — zero or more
- `WorkDoneProgressEnd { kind: 'end', message? }`

The server can also create a token itself via `window/workDoneProgress/create`
(a S→C request), then attach progress to that token. Spec:

> The token provided in the create request should only be used once (e.g.
> only one begin, many report and one end notification should be sent to it).

### Partial result (streamed result chunks)

The originator includes `partialResultToken?: ProgressToken`. The server
streams chunks via `$/progress` whose `value` is the same shape as the
request's normal result. Spec:

> If a server reports partial result via a corresponding $/progress, the
> whole result must be reported using n $/progress notifications. Each of
> the n $/progress notification appends items to the result. The final
> response has to be empty in terms of result values.

So the final JSON-RPC response carries an empty result; the actual data has
been delivered as a sequence of partial-result tokens. The originator must
buffer chunks keyed by token until the final response arrives, then deliver
the assembled value.

### Unknown token

The spec does not explicitly say what to do for `$/progress` with an unknown
token. By the general `$/`-prefix notification rule (see §7), unknown
notifications MUST be silently dropped. In practice both reference impls
drop progress for unknown tokens, optionally logging.

### What `kyo-jsonrpc` needs

`kyo-jsonrpc` needs first-class progress plumbing:

- A `ProgressToken` type (`Long | String`).
- Request-issuing API that accepts an optional `workDoneToken` and/or
  `partialResultToken`, and returns alongside the response a way to observe
  progress notifications keyed by that token (e.g. a kyo `Stream` of progress values, or a
  `Channel`).
- A handler-side API where the running handler receives a progress emitter
  for its tokens, and a cancellation token for `$/cancelRequest`.
- The originator's partial-result accumulator: deliver the empty final
  response as the assembled stream of chunks.

These are LSP-shaped but MCP and CDP both have analogous notions (MCP
"progress notifications", CDP "events on a session"), so doing this once at
the JSON-RPC layer pays off.

---

## 5. Error codes

LSP partitions the JSON-RPC error-code space:

| Code | Name | Source | Notes |
|------|------|--------|-------|
| -32700 | ParseError | JSON-RPC | invalid JSON |
| -32600 | InvalidRequest | JSON-RPC | not a valid request object |
| -32601 | MethodNotFound | JSON-RPC | unknown method |
| -32602 | InvalidParams | JSON-RPC | invalid params |
| -32603 | InternalError | JSON-RPC | generic |
| -32099..-32000 | jsonrpcReserved | JSON-RPC | server impl-defined |
| -32002 | ServerNotInitialized | LSP | request before `initialize` |
| -32001 | UnknownErrorCode | LSP | placeholder |
| -32899..-32800 | lspReserved | LSP | LSP impl-defined |
| -32800 | RequestCancelled | LSP | client cancelled the request |
| -32801 | ContentModified | LSP | document changed under the request |
| -32802 | ServerCancelled | LSP | server-initiated cancel |
| -32803 | RequestFailed | LSP | syntactically valid request that failed |

Note LSP uses both ranges: -32002 and -32001 live in JSON-RPC's reserved
range (-32099..-32000) and the -3280x codes live in LSP's own reserved
range (-32899..-32800). The existing `kyo-ai-plugin` `JsonRpcError`
companion exposes only the five JSON-RPC standard codes — `kyo-jsonrpc`
will need a separate `LspErrorCodes` object (or similar) carrying the LSP
six.

---

## 6. Initialize / shutdown lifecycle

LSP imposes ordering constraints on the wire:

> If the server receives a request or notification before the `initialize`
> request it should act as follows: For a request the response should be
> an error with `code: -32002`. The message can be picked by the server.
> Notifications should be dropped, except for the exit notification.

> The server is not allowed to send any requests or notifications to the
> client until it has responded with an `InitializeResult`, with the
> exception that during the `initialize` request the server is allowed to
> send the notifications `window/showMessage`, `window/logMessage` and
> `telemetry/event` as well as the `window/showMessageRequest` request.

> The `initialize` request may only be sent once.

> Until the server has responded to the `initialize` request with an
> `InitializeResult`, the client must not send any additional requests or
> notifications to the server.

This is LSP-specific protocol semantics, not JSON-RPC. It belongs in
`kyo-lsp`, not `kyo-jsonrpc`. The only requirement on `kyo-jsonrpc` is that
its endpoint API surface lets a higher layer install a state machine that
gates dispatch. Concretely: there must be a way to intercept incoming
messages and either route them, reply with a fixed error, or drop them,
based on endpoint state. A simple "handler with full access to the envelope
and the endpoint" is enough; alternatively a `messageGate: Message =>
Either[JsonRpcError, Message]` hook.

`shutdown` and `exit` are just two more methods; the only special property
is that `exit` is a notification (no reply expected) and may cause the
process to terminate. The transport must therefore tolerate the local end
closing the stream after writing `exit`'s response/notification.

---

## 7. The `$/` prefix convention

> Notifications and requests whose methods start with '$/' are messages
> which are protocol implementation dependent and might not be
> implementable in all clients or servers.

> If a server or client receives notifications starting with '$/' it is
> free to ignore the notification.

> If a server or client receives a request starting with '$/' it must
> error the request with error code `MethodNotFound` (e.g. `-32601`).

This is the rationale for the `methodNotFoundIsError` distinction in the
`kyo-jsonrpc` design prompt. The rule splits notification handling from
request handling:

- **Unknown notification**: do not raise an error. There is no envelope to
  reply on. Silently drop. (And for `$/`-prefixed notifications, do not even
  log loudly — they are explicitly opt-in.)
- **Unknown request**: reply with `MethodNotFound = -32601`. (Even for
  `$/`-prefixed requests — the spec mandates `MethodNotFound` specifically,
  not a no-op.)

A `kyo-jsonrpc` endpoint needs configurable behaviour for both:

- For requests: always reply `MethodNotFound`; this is non-negotiable per
  JSON-RPC and LSP.
- For notifications: default to silent-drop. An "unknown-notification hook"
  for diagnostics is fine but should default to no-op for `$/`-prefixed
  methods.

Said differently: `methodNotFoundIsError` is "false for notifications, true
for requests" by default. MCP and CDP do not break this — they just don't
have the `$/` carve-out.

---

## 8. Constraints on a unified `kyo-jsonrpc`

What LSP forces into `kyo-jsonrpc` that MCP and CDP do not:

### (a) Content-Length stdio transport adapter contract

The transport abstraction (`JsonRpcTransport`) must accommodate a
header-framed byte stream, not just a WebSocket-style discrete-message
stream. The signature has to support:

- header-block parsing (state machine described in §1) before each body,
- a writer that emits `Content-Length: N\r\n\r\n<body>` per message,
- byte-level reads (the header is ASCII, the body is UTF-8 of `Content-Length` bytes exactly).

The adapter itself can live in `kyo-lsp`; the transport interface in
`kyo-jsonrpc` must be byte-stream-friendly, not message-array-friendly.

### (b) Symmetric bidirectional endpoint

There is no client/server asymmetry at the wire layer. The endpoint API
must allow both sides to:

- register methods,
- issue requests,
- send notifications,
- receive requests and reply,
- receive notifications,

with identical surface. Don't bake `client.call(method, params)` / `server.handle(method)` asymmetry into types. A single `Endpoint` that is both is the right shape — see the dual `pendingOutbound` / `pendingInbound` tables in §2.

### (c) Cancellation

First-class. Concretely:

- `Endpoint.call(method, params, ...)` must return a handle that supports
  cancellation, and on cancellation must (1) issue `$/cancelRequest` over
  the wire and (2) abandon the local pending entry.
- Handlers must run with a cancellation token in scope.
- `-32800 RequestCancelled` must be a first-class error type, distinct
  from generic `JsonRpcError`.
- Late responses for cancelled requests must drop silently, not raise.

### (d) Progress

First-class for both work-done and partial-result. See §4 for the API
shape. The accumulator semantics for partial results (chunks via
`$/progress`, empty final response) are non-trivial and belong in the
endpoint, not in user code.

### (e) `$/`-prefix drop-on-unknown rule

Configurable per-direction (notification vs request), as in §7. The default
should match LSP semantics: drop unknown notifications, reply
`MethodNotFound` to unknown requests.

### (f) Error code dialect extension

LSP adds six codes. `JsonRpcError` (kyo-ai-plugin) currently exposes only
the five JSON-RPC standard codes. Either:

- promote the six LSP codes to `JsonRpcError`'s companion (they are not LSP-
  exclusive in practice — `RequestCancelled` is genuinely useful for
  cancellation in MCP/CDP too), or
- expose an `LspErrorCodes` object in `kyo-lsp` that just declares constants
  on top of `JsonRpcError`.

The cancellation code is the strongest candidate for promotion; it's
needed at the JSON-RPC layer to surface "your request was cancelled" without
the upper layer being LSP-aware.

### (g) Lifecycle-gating hook (informative, not load-bearing)

`kyo-jsonrpc` does NOT need to know about `initialize`. It does need to
let `kyo-lsp` interpose a gate that converts pre-init traffic to
`-32002 ServerNotInitialized` or drops it (per §6). A neutral
`messageGate` / `dispatcher` interception point is sufficient.

---

## Design implications for `kyo-jsonrpc`

1. **Two-layer transport abstraction.** Split `JsonRpcTransport` into
   `JsonRpcWireTransport` (bytes ↔ envelopes) and `JsonRpcMessageTransport`
   (envelopes ↔ envelopes). The Content-Length stdio adapter is a
   `JsonRpcWireTransport` instance built once and reused by `kyo-lsp`. MCP's
   stdio uses NDJSON instead; CDP uses WebSocket frames. All three plug into
   the same wire-transport interface.

2. **Endpoint, not Client/Server.** The top-level abstraction is
   `JsonRpcEndpoint` with `call`, `notify`, `register(method)`,
   `onNotification(method)`. It runs symmetrically. MCP and CDP can ignore
   the symmetric half if they want, but the type doesn't impose asymmetry.

3. **Per-direction id allocation.** Endpoint owns one `AtomicLong`
   counter for outbound ids and one `Map[Id, ...]` table each for
   `pendingOutbound` and `pendingInbound`. No global registry, no
   collision worry.

4. **First-class cancellation.** `call` returns a handle. Cancelling the
   handle issues `$/cancelRequest` (or the protocol's equivalent — make
   the cancel-method-name part of a per-protocol policy). The pending
   entry is dropped immediately; late replies are matched against a
   "recently-cancelled" set and silently consumed. Surface
   `RequestCancelled` as a typed error.

5. **First-class progress.** Issuing a `call` accepts an optional
   `progressSink: ProgressToken => Stream[ProgressValue, ...]` (or pair
   thereof for work-done vs partial-result). The endpoint installs the
   token in the request params, routes incoming `$/progress` for that
   token, and for partial-result tokens substitutes the assembled stream
   for the (empty) final response.

6. **Dispatch policy is data, not branching.** `MethodDispatchPolicy`
   encapsulates: unknown-request-behaviour (default: reply
   `MethodNotFound`), unknown-notification-behaviour (default: drop),
   `$/`-prefix-handling (default: drop notification, error request),
   pre-dispatch gate (default: identity; LSP plugs the initialize gate in
   here).

7. **Header framing is per-message, not per-stream.** The wire transport
   does not maintain "I am inside a body" state across awaits beyond what
   the buffer holds; each loop iteration is "do I have a full header
   block? do I have N body bytes? deliver one envelope; repeat". This
   maps cleanly onto a kyo `Stream` of envelopes from a `Chunk[Byte]`
   stream.

8. **Error codes are open.** Expose `JsonRpcError` with the JSON-RPC five
   plus the LSP six. Promote `RequestCancelled` and (probably)
   `RequestFailed` as genuinely cross-protocol concepts. The remaining LSP
   codes can live there too at no cost.
