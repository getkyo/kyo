# Correctness audit

## Disproven claims

### 1. `Json.Value` does not exist on main; `.field` / `.merge` / `.mergeNested` do not exist anywhere
**Claim**: DESIGN.md uses `Json.Value` as a first-class type throughout (Layer 2 envelope, `extras: Maybe[Json.Value]`, `HandlerCtx.progress(value: Json.Value)`, policy lambdas like `extractInboundToken: Json.Value => Maybe[Json.Value]`, and method calls `p.field("token")`, `p.merge("workDoneToken" -> t)`, `p.mergeNested("_meta", "progressToken" -> t)`).

**Evidence**:
- `kyo-schema/shared/src/main/scala/kyo/Json.scala` (lines 1-200): defines `object Json` with `encode`/`decode`/`JsonSchema`. It does NOT define `Json.Value` or any type alias to one.
- `git show kyo-ai-plugin:kyo-schema/shared/src/main/scala/kyo/Json.scala` line 141: `type Value = Structure.Value` — this alias exists ONLY on the `kyo-ai-plugin` branch. Not on main.
- `grep -rn "def field\|def merge\|def mergeNested"` across `kyo-schema/` and `kyo-prelude/` finds NO such methods on `Structure.Value` (`kyo-schema/shared/src/main/scala/kyo/Structure.scala` line 288). The only `def field(name: String): Path` (line 507) is on `Path`, not `Value`. `merge`/`mergeNested` are NOT defined on any JSON value type in the repository.

**Severity**: blocker. The progress policy section, the cancellation `encodeParams`, and the codec contract all assume an API surface that does not exist. The design must either (a) re-introduce the `Json.Value` alias on main alongside this module, or (b) restate every signature in terms of `Structure.Value` and adapt the policy lambdas to work without `.field`/`.merge`/`.mergeNested`.

**Suggested fix**: Decide whether `Json.Value` becomes a real first-class type with the navigation/merging methods the design needs (and ship those as part of the kyo-jsonrpc PR), or rewrite §3, §7, §8 of the design in terms of `Structure.Value` with explicit Record/MapEntries pattern matches. Either way, the API surface in DESIGN.md is currently unimplementable.

### 2. `Json.encode(...)` returns `String`, not `Json.Value`
**Claim**: §7 `CancellationPolicy.lsp.encodeParams = (id, _) => Json.encode(Map("id" -> id))` and the analogous mcp/progress encoders all expect `Json.Value`.

**Evidence**: `kyo-schema/shared/src/main/scala/kyo/Json.scala` line 36: `inline def encode[A](value: A)(using schema: Schema[A], frame: Frame): String`. There is no overload returning `Json.Value`.

**Severity**: blocker. Every policy lambda type-errors.

**Suggested fix**: Either expose a `Json.encodeValue[A](v: A): Json.Value` companion API (which would also need to exist), or restate the policies to build the params via `Structure.encode`/`Structure.Value.Record(...)` directly. Note also that `Json.encode` requires a `Schema[A]`; `Map("id" -> id)` is heterogeneous and won't derive one.

### 3. `Span.Builder[T]` does not exist
**Claim**: §6.1 `partialResults: ConcurrentHashMap[Json.Value, Span.Builder[Json.Value]]`.

**Evidence**: `kyo-data/shared/src/main/scala/kyo/Span.scala` defines `opaque type Span[+A] = Array[? <: A]` and a flat `object Span`. `grep -nE "Builder" Span.scala` returns one hit: a `java.lang.StringBuilder` used internally in `toString`. There is no `Span.Builder` API.

**Severity**: major. The partial-result accumulator type as named is fictional.

**Suggested fix**: Use `Chunk.Builder` (real) or accumulate into a mutable `scala.collection.mutable.ArrayBuffer[Structure.Value]` flushed to `Chunk` / `Span` on emit. Adjust §6.1 and §20 accordingly.

### 4. Exchange is NOT bidirectional in the sense the design implies
**Claim**: Repeated framing that "Exchange already does X" and "kyo-jsonrpc adds bidirectionality (Exchange is one-direction by default), JSON-RPC envelope conventions, typed method dispatch, cancellation, and progress. Everything else is policy."

**Evidence**: `kyo-core/shared/src/main/scala/kyo/Exchange.scala` lines 5-58: Exchange has exactly three message classes — `Response(id, value)` routed by id to a pending request **the local side issued**, `Push(value)` for unsolicited events, and `Skip`. The pending-map is only for **outbound** requests. There is no inbound-request path: nothing in Exchange dispatches an incoming `Request` to a handler and pumps the reply back through `send`. The JSON-RPC engine's `pendingInbound` map, its handler-fork machinery, and its writer-fiber serialization are all *new* infrastructure, not "thin sugar over Exchange". Inbound notifications/requests in the design are received as `Message.Push(envelope)` because Exchange has no other inbound-non-response channel — meaning the entire JSON-RPC inbound-dispatch story rides on the `Event` slot.

**Severity**: major. The design's central framing is misleading. The actual relationship is: Exchange supplies (a) the outbound-pending-map mechanics for `pendingOutbound` and (b) a reader-fiber-with-Sync-decode that emits `Push(env)` for everything that isn't a reply to one of OUR outbound calls. Inbound dispatch, fork-per-request, response routing back through the writer channel — all of this is new.

**Suggested fix**: Restate Layer 0 honestly. Exchange contributes `pendingOutbound` + reader fiber + Sync-only decode contract. The new engine adds inbound dispatch (fork-per-request), the writer-channel-fed writer fiber, `pendingInbound`, and routes inbound `Request`/`Notification`/`Response` (other side's request reply that happens to live in `Push`!) through that. Either that, or factor a richer "bidirectional Exchange" out into kyo-core and have kyo-jsonrpc actually be thin.

### 5. `Fiber.Promise[Unit, Any]` reads wrong if A is the value type
**Claim**: §5 `HandlerCtx.cancelled: Fiber.Promise[Unit, Any]` — "completed when peer requests cancel".

**Evidence**: `kyo-core/shared/src/main/scala/kyo/Fiber.scala` line 440: `opaque type Promise[A, S] <: Fiber[A, S]` — first type param is the value type, second is the effect row. The naming on `init[E, A]: Promise[E, A]` (line 449) is misleading but consistent: E becomes the value type, A becomes the effect row. Exchange uses `Promise.Unsafe.init[Unit, Abort[E | Closed]]()` (Unit value, Abort effect row) — same convention.

So `Fiber.Promise[Unit, Any]` means "promise of value `Unit`, effect row `Any`". `Any` as the effect row is meaningless (effects are intersection-typed, `Any` carries no constraint). The design likely meant `Fiber.Promise[Unit, Sync]` or simply `Fiber.Promise[Unit, Any]` if the intent is "any effect row is fine" — but that's not how the second param is used elsewhere in the codebase.

**Severity**: minor. Probably innocent — the design likely treats the second param as "any effect" and the type does compile. Worth a doc-fix to use `Fiber.Promise[Unit, Sync]` for consistency with the rest of kyo.

### 6. `pendingInbound: ConcurrentHashMap[JsonRpcId, Fiber[Outcome, Any]]` — `Outcome` was removed
**Claim**: §6.1 stores `Fiber[Outcome, Any]` but §5 explicitly says "**No `Outcome` ADT**" and §19 resolved-decisions #3 says "**No `Outcome` ADT.** Handler returns `Json.Value < S` (or `Unit < S` for notifications)."

**Evidence**: Direct contradiction within DESIGN.md. §6.1 still references `Outcome` as a type name; §5 and §19 say no such type exists.

**Severity**: major (internal inconsistency that blocks implementation).

**Suggested fix**: `pendingInbound: ConcurrentHashMap[JsonRpcId, Fiber[Json.Value, Any]]` for request handlers; notifications don't need the map at all.

## Unverifiable claims

### A. CDP's `Page.handleJavaScriptDialog` "may never reply"
**Claim**: The research doc and §16.3 imply Chrome may never reply to handleJavaScriptDialog under certain conditions (context disposed).

**Evidence**: Confirmed by the in-repo CDP code's own comment (`git show kyo-browser:kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala`, the negative-id-drainer block). However the upstream Chromium-issue justification is not cited in the design. I did not separately verify against Chromium's bug tracker. **Resolves with**: link a Chromium issue or commit in DESIGN.md §16.3 to anchor the claim outside our own repo.

### B. "MCP 2025-06-18 removes batching" — confirmed
**Claim**: §1 table and §10 R21 — "no batching".

**Evidence**: `modelcontextprotocol.io/specification/2025-06-18/changelog` lists as the first Major Change: "Remove support for JSON-RPC batching (PR #416)". Confirmed.

### C. "MCP ids non-null, unique-per-session" — confirmed
**Evidence**: `modelcontextprotocol.io/specification/2025-06-18/basic/index` Requests section: "Requests **MUST** include a string or integer ID. Unlike base JSON-RPC, the ID **MUST NOT** be `null`. The request ID **MUST NOT** have been previously used by the requestor within the same session." Confirmed.

### D. "JSON-RPC 2.0 allows id=null in responses to malformed requests" — confirmed
**Evidence**: `jsonrpc.org/specification` §5: "If there was an error in detecting the id in the Request object (e.g. Parse error/Invalid Request), it MUST be Null." Confirmed.

### E. LSP `$/cancelRequest` MUST reply — confirmed
**Evidence**: LSP 3.17 spec §Cancellation: "A request that got canceled still needs to return from the server and send a response back. It can not be left open / hanging." Confirmed.

### F. LSP error code numbers — confirmed
Confirmed from spec: -32800 RequestCancelled, -32801 ContentModified, -32802 ServerCancelled, -32803 RequestFailed, -32001 UnknownErrorCode, -32002 ServerNotInitialized.

### G. MCP `notifications/cancelled` no-reply contract — confirmed
**Evidence**: `modelcontextprotocol.io/.../utilities/cancellation`: "Receivers of cancellation notifications **SHOULD** Not send a response for the cancelled request" and "The sender of the cancellation notification **SHOULD** ignore any response to the request that arrives afterward". Confirmed.

### H. MCP progress token lives at `params._meta.progressToken` — confirmed
**Evidence**: `modelcontextprotocol.io/.../utilities/progress`: the request JSON shows `params._meta.progressToken`, the progress notif params carry `progressToken` at top level (no `_meta` wrapping). Confirmed.

### I. MCP `progress` MUST be monotonically increasing — confirmed
**Evidence**: same spec page: "The `progress` value **MUST** increase with each notification, even if the total is unknown." Confirmed.

### J. MCP-Protocol-Version header — confirmed
**Evidence**: `modelcontextprotocol.io/.../transports`, Protocol Version Header section: "the client **MUST** include the `MCP-Protocol-Version: <protocol-version>` HTTP header on all subsequent requests". Confirmed.

### K. CDP "single Int counter per connection" — confirmed in our repo, not externally
**Evidence**: `git show kyo-browser:.../CdpClient.scala` line 300+: uses `Exchange.initUnscoped` (default sequential `Int` IDs). Matches.

## Race conditions / logical hazards

### R1. Writer-fiber serialization claim vs. simple alternatives
**Claim**: §20 invariant #2: "Writer fiber serializes outbound. Single fiber drains the writer channel. No two outbound frames interleave on a transport that isn't atomic (LSP stdio, CDP WebSocket frames)."

**Walk-through**: A simple `AtomicRef[Promise[Unit]]` CAS — claim a sequencer slot before sending, complete the slot after send — would also serialize. The channel-of-envelopes approach is heavier: it requires the per-envelope allocation, schedules the writer fiber whenever the channel transitions empty→non-empty, and adds a buffering step that decouples backpressure from the caller. The design doesn't say *why* the channel approach beats the CAS approach. For LSP stdio (a single fd) the CAS approach is strictly cheaper.

The channel approach is *correct*, but the design hand-waves the choice. The actual reason is likely: (a) backpressure decoupling (the writer can buffer while the caller's fiber returns immediately), (b) graceful shutdown (poison the channel = stop accepting new sends with no race), (c) it unifies "scheduled outbound" (responses from handler fibers) with "user-initiated outbound" (call/notify). All three are real reasons; none are stated.

**Severity**: minor. The choice is defensible; the design just doesn't justify it. **Suggested fix**: add a one-line "why a channel and not a mutex" to §6.3.

### R2. Per-direction id allocation correctness when both sides pick id=5
**Claim**: §10 "Lookup is `(my endpoint's pendingOutbound)[their id]`. Cross-direction collisions are impossible."

**Walk-through**:
- Endpoint A allocates outbound id=5, sends Request(5). It enters A's `pendingOutbound[5]`.
- Endpoint B allocates outbound id=5, sends Request(5). It enters B's `pendingOutbound[5]`.
- A reads B's Request(5). A's reader sees a Request envelope (not a Response) → goes to `pendingInbound` path, fork handler, key `pendingInbound[5]` on A. **A's `pendingOutbound[5]` is unaffected** because routing for Requests doesn't touch it.
- When A's handler finishes, A sends Response(5). B's reader sees Response → looks up B's `pendingOutbound[5]`, finds B's outstanding promise, completes it. Correct.
- Similarly for B's reply to A.

So far the routing claim holds. **But**: the design's §6.2 says "Response → `pendingOutbound[id].complete(result | error)`" — that's correct because Response only ever flows in reply to an outbound. There is no scenario where Response(5) arriving could match someone else's `pendingInbound[5]`. The claim holds.

**Caveat**: §6.1 stores `pendingInbound[id]` keyed by `JsonRpcId` (the *peer's* id), and §10 §16.1/§16.2 claims this. If a peer sends Request(5) and Request(5) again (reused id within same direction — illegal per MCP, allowed per LSP/CDP), the second Request would overwrite the first's `pendingInbound[5]` entry, leaking the first handler fiber from cancellation lookup. Design doesn't address this case.

**Severity**: minor. **Suggested fix**: when registering `pendingInbound[id]`, check for existing entry; if present, emit `InvalidRequest` to spec MCP id-reuse rule, or log+overwrite under a tolerant LSP profile. Promote this to a policy knob.

### R3. Reader routing rule "if `pendingOutbound[id]` is present, it's mine; else it's malformed"
**Claim** (paraphrased from §6.2): inbound Response routes by `pendingOutbound[id]`; missing entry → drop silently (§6.5).

**Walk-through scenario** — late reply for a cancelled outbound:
- A issues Request(5), registers `pendingOutbound[5]`.
- A times out, fires `$/cancelRequest{id:5}`, removes `pendingOutbound[5]` (§7 "completes pendingOutbound[id] immediately ... if a late reply arrives ... reader finds no pending entry, drops silently").
- B's late Response(5) arrives. A's reader sees no entry. Drops. Correct.

**Walk-through hazard** — stray response from peer that never matched anything:
- B sends Response(5) unprompted. A's reader sees no entry, drops silently. Correct per spec (LSP "Originator MUST tolerate late reply"; MCP same).

**Walk-through hazard** — `pendingInbound` reuse after handler completes but before transport reply has flushed: if peer sends notifications/cancelled for id=5 between handler completion and writer flushing the Response(5) frame, §7 step 3 says "Look up `pendingInbound[id]`. If absent (already-completed or never-existed), drop silently." But the handler may have completed and unregistered while the response is still in the writer channel queue. So the cancel is dropped, the response goes out anyway. For LSP (must-reply) that's fine. For MCP (`expectReplyForCancelledRequest=false`) it's a violation: a response goes out for a cancelled request because the cancel arrived after the handler-fiber lookup-and-remove but before the writer flushed.

**Severity**: major. The "race between cancel arrival and writer flush" needs an explicit interlock. Options: (a) the engine tags the writer-channel entry with the inbound id, and the cancel handler can pull it back out (channel doesn't support extraction, so this needs a different data structure); (b) the engine holds the handler fiber in `pendingInbound` until the Response has actually been *sent* (not just produced), so the cancel arrival can still interrupt-and-drop.

**Suggested fix**: in §6.2/§7, define `pendingInbound[id]` lifetime as "from handler fork through response-on-the-wire, not handler-fiber completion." Cancel arrival → mark the entry "cancelled, drop reply"; the writer-side path consults this flag before serializing the queued Response.

### R4. Timeout fires cancel → cancel arrives after original reply already in flight
**Claim**: §7 last paragraph asserts "Late-reply drop still correct" under timeout-fires-cancellation.

**Walk-through**:
- A's timeout fires for outbound id=5. A's `pendingOutbound[5]` is removed; A sends `$/cancelRequest{id:5}`.
- B has already completed the handler and queued Response(5) on its writer channel. B flushes it.
- The cancel from A and the Response(5) from B cross on the wire.
- A reads Response(5), `pendingOutbound[5]` is gone, drops silently. Correct.
- B reads `$/cancelRequest{id:5}`, looks up `pendingInbound[5]`, finds nothing (handler completed), drops silently. Correct.

This case actually works. **No hazard.**

But the inverse case (R3 above) does break. The design conflates the two and only spells out the safe one.

### R5. `Codec.Cdp` `extras` field-collision protection
**Claim** (§3, §16.3): "kyo-browser's outbound encoder stamps `extras = sessionId` before handing the envelope to the engine" and "Codec.Cdp stamps fields from extras to the top level."

**Walk-through hazard**: If a user calls `endpoint.call("Page.navigate", params, extras = Present(Json.Value.Record(("method", "evil"), ("sessionId", "..."))))`, the Cdp encoder writes `{method: "evil", sessionId: "...", method: "Page.navigate", params: ...}` — duplicate `method` key in JSON output. Some parsers take the last; some the first; some error. The design says nothing about collision detection.

**Severity**: major. Worst case is a security issue: an attacker controlling `extras` rewrites the wire `method` and re-routes the request to a different CDP domain.

**Suggested fix**: §3 must state explicitly: "Codec.Cdp rejects `extras` records containing any of the reserved JSON-RPC envelope field names (`id`, `method`, `params`, `result`, `error`, `jsonrpc`). On collision, emit `Malformed` or `Abort.fail(JsonRpcError.invalidParams(...))`." Without this, the codec is exploitable.

### R6. `callPartialResults[T]` distinguishing empty final from early non-empty response
**Claim** (§6 / §8.3): "The final empty response from the peer closes the stream."

**Walk-through hazard**: Spec (LSP) says the final response MUST be empty when partial-result streaming was used. What if the peer doesn't actually stream partial results and replies normally? The engine sees a non-empty Response — does it (a) interpret the response value as one more chunk and then close, (b) error the stream because the partial-result contract was violated, or (c) silently swallow the result?

The design doesn't say. The kyo-jsonrpc design needs an explicit rule for "partial-result was requested but peer ignored the token and sent a normal reply." Both LSP and MCP allow the peer to choose whether to honor the progress token (it's a SHOULD-emit thing), so this case is legal.

**Severity**: major.

**Suggested fix**: §8 should specify: "If the peer's final response is non-empty AND partialResultToken was attached, emit the final response value as the last chunk and close the stream (peer chose not to stream). If non-empty AND chunks were already received via $/progress, fail the stream with `InvalidParams`-equivalent (`RequestFailed`?) because the spec violation prevents safe assembly."

### R7. `Sync.ensure` on pending-entry registration handles caller-interrupt; but writer-channel poisoning order vs in-flight `call`
**Claim**: §6.4 finalizer order: "1. Stop accepting new outbound calls (poison the writer channel). 2. Cancel the reader fiber. ..."

**Walk-through hazard**: Step 1 poisons writer channel → future `writer.put` fails. But a `call(...)` already in progress between `pendingOutbound.put(...)` and `writer.put(envelope)` will: register pending → then writer.put raises `Closed` → Sync.ensure removes pending → caller sees Closed. Correct.

But: step 5 says "Fail every `pendingOutbound` entry with `Closed`." After step 1+2+3+4, a `call` started between steps 1 and 5 may have completed `pendingOutbound.put` (step 1 doesn't block this), then `writer.put` failed Closed, then Sync.ensure cleanup ran. The pending entry is already gone. Step 5's `forEach` finds nothing for it. OK.

But what about a `call` that registered pending, then `writer.put` *succeeded* (a write went through), then the writer fiber was cancelled at step 3 before actually flushing to the transport? The pending entry is still alive, `writer.put` succeeded, but the bytes never reached the wire. Step 5 fails the pending with `Closed` — caller sees `Closed`, never sees a stale "response will come" state. Acceptable.

**Severity**: minor. The order looks correct under inspection.

### R8. `subscribeProgress(token)` / `unsubscribeProgress(token)`
**Claim**: §8 says `endpoint.subscribeProgress(token): Stream[Json.Value, Async]` registers a token; "The stream closes when `endpoint.unsubscribeProgress(token)` is called or the endpoint shuts down."

**Evidence**: `unsubscribeProgress` is named in §8 but does NOT appear in the public-API table (§15) and is not in `JsonRpcEndpoint`'s signature block (§6).

**Severity**: minor. Need to add `unsubscribeProgress(token)(using Frame): Unit < Async` to the public API.

## API method calls that don't exist

| Used in DESIGN.md | Status | Where verified |
|---|---|---|
| `Json.Value` | Does not exist on main (only on `kyo-ai-plugin` as `type Value = Structure.Value`) | grep over `kyo-schema/` |
| `Json.Value.field(name: String)` | Does not exist anywhere | grep over `kyo-schema/`, `kyo-prelude/` |
| `Json.Value.merge(k -> v)` | Does not exist | grep |
| `Json.Value.mergeNested(k1, k2 -> v)` | Does not exist | grep |
| `Json.encode(Map(...))` returning `Json.Value` | `Json.encode` returns `String`, not `Json.Value`; also requires `Schema[A]` and heterogeneous `Map` lacks one | Json.scala line 36 |
| `Span.Builder[A]` | Does not exist | Span.scala has no `Builder` |
| `Json.Value.Null` (mentioned in §6 "Absent ≠ Present(Json.Value.Null)") | Does not exist (Structure.Value has a `Null` variant, fine if alias is created) | Structure.scala line 317 |
| `Exchange.init` | Exists. Signature matches. | Exchange.scala line 90 |
| `Exchange.Message.{Response, Push, Skip}` | Exists. | Exchange.scala line 63 |
| `events` extension, `awaitDone`, `close` on Exchange | All exist. | Exchange.scala lines 295, 303, 307 |
| `AtomicLong`, `AtomicInt` | Exist as case-class wrappers. | Atomic.scala lines 16, 205 |
| `Promise.Unsafe`, `Channel.Unsafe` | Exist. | Fiber.scala line 597, Channel.scala line 392 |
| `Fiber.Promise[E, A]` | Exists; first param is value type, second is effect row (param naming in `init[E,A]` is misleading) | Fiber.scala lines 440, 449 |
| `Meter.initSemaphore` | Exists. | Meter.scala line 169 |
| `Sync.ensure` | Exists. | Sync.scala line 66 |
| `Sync.Unsafe.defer` | Exists. | Sync.scala line 137 |
| `JsonRpcMethod[+S]` on kyo-ai-plugin | Exists with claimed shape and `(Async & Abort[JsonRpcError]) <:< S` evidence trick, decode-failure → InvalidParams, panic → InternalError | git show kyo-ai-plugin:kyo-http/.../JsonRpc.scala |
| CDP `Req = Int => String`, `maxInFlight=8`, negative-id drainer, `withSession` | All present | git show kyo-browser:.../CdpClient.scala lines 28, 206, 118, 322 |

## Confirmed correct (load-bearing claims)

- Exchange exists in `kyo-core/shared/src/main/scala/kyo/Exchange.scala` with `init`, `Message.{Response, Push, Skip}`, `events` extension, `awaitDone`, `close`.
- `AtomicLong`, `AtomicInt`, `Promise.Unsafe`, `Channel.Unsafe`, `Fiber.Promise`, `Meter.initSemaphore`, `Sync.ensure`, `Sync.Unsafe.defer` all exist in kyo-core with the shapes assumed.
- `Schema`, `Maybe`, `Span` (the type), `Chunk` all exist.
- `Frame` is auto-derived but the `kyo.*` package opts out, so user-callable methods must take `(using Frame)`. The design's `(using Frame)` ascriptions are correct.
- `JsonRpcMethod[+S]` on the `kyo-ai-plugin` branch matches the design's port (capture `Schema[In]`/`Schema[Out]`, `(Async & Abort[JsonRpcError]) <:< S` evidence, decode-failure → InvalidParams, panic → InternalError).
- CDP code on `kyo-browser` matches the design's claims about Exchange usage (`Req = Int => String`), the `maxInFlight=8` cap, the negative-id dialog drainer pattern, and `withSession` as a thin facade sharing the underlying Exchange.
- JSON-RPC 2.0 spec: error code ranges, batch semantics, notification "MUST NOT reply" rule, id-must-be-null on parse-error responses — all match the design's claims.
- LSP 3.17: `$/cancelRequest` MUST reply, error code values, `$/`-prefix drop rule, `window/workDoneProgress/create` flow, Content-Length framing — all match.
- MCP 2025-06-18: batching removed (changelog confirms), ids non-null/unique-per-session, `notifications/cancelled` no-reply contract, `notifications/progress` opt-in via `params._meta.progressToken`, monotonic progress, Streamable HTTP (POST returns JSON or SSE upgrade), MCP-Protocol-Version header — all match.
- CDP: no `jsonrpc:"2.0"`, top-level `sessionId`, single Int counter per connection, no on-wire cancellation — match (in-repo evidence).
- Per-direction id allocation routing logic is sound for the common case (each endpoint's `pendingOutbound` is keyed on ids it allocated; Response routes there; Request routes via method lookup and registers in `pendingInbound`).
- Timeout-fires-cancellation late-reply-drop scenario is benign (R4 walk-through).
- `Sync.ensure`-based pending-entry cleanup mirrors Exchange's own pattern (R7 walk-through).
