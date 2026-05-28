# Clarity audit

Audit perspective: smart engineer reading only DESIGN.md, asked to implement from scratch. Findings cite line numbers in `kyo-jsonrpc/DESIGN.md`.

## Implementation-blocking ambiguities

### 1. `JsonRpcEnvelope.extras` typing is inconsistent across the doc

Line 105-109 declares:
```
case Request(id, method, params: Maybe[Json.Value], extras: Json.Value)
case Notification(method, params: Maybe[Json.Value], extras: Json.Value)
case Response(id, result: Maybe[Json.Value], error: Maybe[JsonRpcError], extras: Json.Value)
```
`extras` is `Json.Value` (non-optional). But line 205 (`HandlerCtx.extras: Maybe[Json.Value]`), line 229 (`extras: Maybe[Json.Value] = Absent`), line 781 ("`Maybe[Json.Value]`... Absent ≠ Present(Json.Value.Null)"), and line 808 (invariant 11: "extras is Maybe[Json.Value], not Json.Value") all say `Maybe[Json.Value]`. The §3 ADT contradicts every other mention.

**Fix:** Update the ADT cases at lines 106-108 to `extras: Maybe[Json.Value]` to match the rest of the doc.

### 2. `Fiber[Outcome, Any]` references a type the design eliminated

Line 306: `pendingInbound: ConcurrentHashMap[JsonRpcId, Fiber[Outcome, Any]]`. Line 318: "fork(method.handle); discard Outcome". But §6 (lines 215, 297-298) and resolved decision 3 (line 783) explicitly state **"No Outcome ADT"**. The engine internals describe a type that does not exist.

**Fix:** Replace with the actual handler return type, e.g. `Fiber[Json.Value, JsonRpcError | Closed]` for request handlers, and update line 318 to "discard Unit result".

### 3. `JsonRpcTransport` type parameter inconsistency

Line 78 declares `trait JsonRpcTransport[Env]` (parameterised). Line 141 declares `trait JsonRpcTransport(using Frame)` (no type parameter, hard-wired to `JsonRpcEnvelope`). Line 595 ("`JsonRpcTransport[JsonRpcEnvelope]`") uses the parameterised form again. An implementer cannot know whether to write `trait JsonRpcTransport[Env]` or `trait JsonRpcTransport`.

**Fix:** Pick one. §4 prose ("transport delivers already-codec'd envelopes") implies no type parameter; if so, remove `[Env]` from line 78 and line 595.

### 4. `JsonRpcEndpoint.init` constructor signature missing several Config fields' wiring

Line 287-291: `init(transport, methods, config)` — but the design never says how methods that need richer effect rows than `Async & Abort[JsonRpcError]` are accepted; nor how `Config.idStrategy.Custom` is constructed (`() => JsonRpcId < Sync` — synchronous-only? what if the user wants a UUID library that's `Sync`-safe? the constraint is implicit). `subscribeProgress` (line 256) and `unsubscribeProgress` (line 486) are mentioned in prose but `unsubscribeProgress` is not in the §6 API block or §15 surface list. Implementer can't decide whether to add it.

**Fix:** Add `unsubscribeProgress(token): Unit < Async` to the §6 API block and §15 surface, or remove the reference at line 486 and document the lifecycle (Scope-bound).

### 5. `subscribeProgress` / `callPartialResults` / progress-channel buffer policy is unspecified

Lines 256, 249, 307, 469: progress channels are `Channel[Json.Value]`. Implementer must know capacity, overflow policy (drop / block / unbounded), and what happens when the consumer is slow. Backpressuring the reader fiber would violate "Reader fiber never parks" (line 798). Dropping silently could lose progress.

**Fix:** Specify capacity (e.g. "unbounded", or "bounded N with drop-oldest") and reconcile with the "reader never parks" invariant — likely via `Channel.Unsafe.offer` with drop semantics.

### 6. Reader-fiber routing pseudocode under-specifies several branches

Lines 316-321: pseudocode says `pendingInbound[id] = fork(method.handle)`. Missing: (a) method-not-found lookup happens before fork — where does `UnknownMethodPolicy` fire? (b) where does `MessageGate.beforeDispatch` fit (line 551 says "before dispatching" but order vs. method lookup is unspecified); (c) `Response` line says "also flush partialResults[token] if any" but the design never explains how an outbound Response carries a token (Response has `result`, not a token; the token came from the original outbound request's `progressStreams` registration).

**Fix:** Replace the four-line pseudocode with a numbered procedure that interleaves Gate → policy intercept (cancel/progress) → method lookup → fork. Clarify the partial-result accumulator lookup is keyed by `pendingOutbound[id].token`, not by the inbound `Response`.

### 7. `IdStrategy.Custom` signature: who calls `next()`?

Line 530: `Custom(next: () => JsonRpcId < Sync)`. Is `next` called from the writer fiber, the caller fiber issuing `endpoint.call`, or both? Concurrency safety of the user's implementation depends on this. The "outboundIds: AtomicLong" hint (line 304) implies caller-fiber, but a `Sync` thunk could be unsafe under concurrent calls without explicit guarantees.

**Fix:** State explicitly: "`next()` is called from the calling fiber; concurrent calls are possible; the user is responsible for thread-safety (use `Sync.Unsafe.defer` over an `AtomicLong`)."

### 8. `awaitDrain` semantics undefined

Line 265: `def awaitDrain: Unit < Async`. The opening lens question is verbatim called out in the prompt. Does it wait for pending outbound requests, buffered writer messages, in-flight inbound handlers, or all three? Implementer must guess. CDP requires it (line 727); kyo-browser's report mentioned drain-on-close.

**Fix:** Specify: "Suspends until `pendingOutbound` is empty AND the writer channel is empty AND every `pendingInbound` handler has terminated. New requests issued during drain are also awaited."

### 9. `endpoint.cancel(id, reason)` on unknown id

Line 262-263: not specified. Does it succeed silently, fail with `Closed`, fail with a typed error, or no-op? Line 404 specifies inbound-side behavior ("drop silently") but outbound-side `endpoint.cancel` of an unknown id is unaddressed.

**Fix:** Add a sentence: "If `id` is not in `pendingOutbound`, `cancel` is a no-op and returns `()`."

### 10. Late-reply handling between `Outcome` removal and "drop silently"

Line 414: "If a late reply arrives... reader finds no pending entry, drops silently." But for the **MCP no-reply** path, the engine **also** drops the handler's reply locally on the inbound side. These are two different drops in two directions; the prose at line 407 ("Drop any reply it might have already produced") is vague about whether "drop" means "the writer never sees it" or "the writer sees it and we filter at write time". The latter has a race.

**Fix:** Specify: "On `expectReplyForCancelledRequest=false`, the engine interrupts the handler fiber and removes its `pendingInbound[id]` entry; any value the handler had already enqueued to the writer channel is filtered there by checking `id ∈ pendingInbound` at write time."

---

## Test-blocking ambiguities

### A. "Engine drops the reply" (line 407, 414, 478) — what's the observable test pattern?

Negative-existence tests are notoriously hard. No guidance is given. An implementer writing tests for cancellation, late-reply drop, and unknown-progress-token drop must invent a probe (e.g. silent in-memory transport with an introspectable outbound channel) and then prove "never appears".

**Fix:** Add to §18 a sentence on the test pattern: "Use the in-memory transport's outbound channel; assert the channel is empty after `awaitDrain`."

### B. "Bounded replay buffer per stream" (line 578)

No bound given; not testable. Even if this is in kyo-mcp not this module, the engine's contract for SSE replay is mentioned without numbers.

**Fix:** Either remove the SSE/replay detail (not in scope per §13's "lives in kyo-mcp") or push it to a kyo-mcp design doc with concrete numbers.

### C. "Progress channel closes on final response" (line 471, 472)

How does the consumer of `callWithProgress.progress` observe close? Channel-closed is `Abort[Closed]` on the next read; the design declares `Stream[Json.Value, Async]` (no `Abort[Closed]`). Test can't be written if the error channel is wrong.

**Fix:** Either widen `progress: Stream[Json.Value, Async]` to `Stream[Json.Value, Async & Abort[Closed]]`, or specify that close-on-final-response delivers a normal stream termination (no error).

### D. "Timeout auto-fires cancellation policy" (line 418-420)

What does "fires" mean for testing? Is the cancellation notification flushed before `Abort.fail(Timeout)` is observed by the caller, or concurrently? The order matters for tests that assert "after timeout I see the cancel on the wire".

**Fix:** "On timeout, the engine first enqueues the cancellation notification to the writer (best-effort), then aborts `pendingOutbound[id]` with the timeout error. Callers observe the timeout error after the cancellation is enqueued (not necessarily after it's written)."

### E. "Handler panic → InternalError reply" (line 349, 806)

What constitutes a "panic" in Kyo terms? `Result.Panic`? Unhandled exception during `Sync`? Abort with a non-`JsonRpcError` type? An implementer cannot write a panic-recovery test without knowing the triggering shape.

**Fix:** Define: "Any `Result.Panic` from the handler fiber, including uncaught exceptions and Aborts whose error type is not `JsonRpcError`, is mapped to `JsonRpcError.internalError(panic.toString, Present(panic.stackTrace))`."

---

## Prose-code mismatches

### M1. `Outcome` ghost type (covered above as ambiguity #2)

§6.1 (line 306) and §6.2 (line 318) reference `Outcome` after §5 (line 215) and §6 (line 298) explicitly deleted it. Two of three references are in the supposedly authoritative internals section.

### M2. `JsonRpcEnvelope.Notification` is missing from §15

Line 618: "JsonRpcEnvelope // sealed ADT: Request | Notification | Response | Malformed". Matches §3. But Layer 2 box in §2 (lines 72-73) lists only `JsonRpcRequest / JsonRpcResponse / JsonRpcError / JsonRpcId`, no notification, no envelope. Reader can't tell whether `JsonRpcRequest` is a separate wire type from `JsonRpcEnvelope.Request`.

**Fix:** Reconcile. Either §15's `JsonRpcRequest/Response` are deprecated (then drop from public surface) or they exist alongside the envelope ADT (then the §2 diagram needs a one-liner explaining the relationship).

### M3. `Strict2_0` constant casing vs Scala convention

Line 122, 277, 620: `Strict2_0` as a `val`. Scala convention is PascalCase for type-like vals (acceptable here) but the underscore-digit is unusual. Not a clarity bug but every reader will pause. Document or rename to `Strict20` / `JsonRpc20`.

### M4. `Frame.internal` reference implied at line 776 ("In `kyo.internal`")

Per CLAUDE.md, `kyo.internal` is the implementation namespace. Fine. But the design never specifies which of the 17 public types live where on the file tree. Implementer must decide.

**Fix:** Either add a "File layout" subsection to §15 or strike the comment at line 658 if the package decision is "all in `kyo`".

### M5. `JsonRpcMethod` constructor needs handler with `HandlerCtx`, convenience overload mentioned as "doesn't need ctx"

Line 189: comment uses em-dash. Plus the convenience overload (`In => Out < S`) silently materializes a `HandlerCtx` the handler can't see. Where do the cancel-token reads go if the handler doesn't accept ctx? Probably nowhere; the handler can't be cancellation-aware. Should be stated.

**Fix:** Append to line 191: "Handlers built with the ctx-less overload cannot observe cancellation; engine still cancels them via fiber interrupt."

### M6. §15 lists "JsonRpcRequest / JsonRpcResponse" as public (line 614-615)

But §3 introduces only `JsonRpcEnvelope` and `JsonRpcCodec`. §15's "retained for thin HTTP convenience layer" is the only justification, and is not previously introduced. What is "the thin HTTP convenience layer"? It's not in §2, §13, or §14.

**Fix:** Drop the dangling reference or add a one-line subsection in §15 explaining the convenience layer (or push these types into `kyo.internal`).

---

## Structural friction

### S1. §1 table presumes terms defined later

Row "Per-direction id namespace", row "Server-initiated routing", row "Backpressure" all use vocabulary (`maxInFlight`, `pendingOutbound`, "routing") that is introduced in §6+. A first-pass reader bounces.

**Fix:** Add a one-line glossary at the bottom of §1: "Terms used below: *pending map*, *engine*, *envelope*, *transport*. See §2 for the layer that owns each."

### S2. §6 mixes API + internals + scope cleanup + leak prevention + reader discipline

§6.1 internals, §6.2 reader, §6.3 writer, §6.4 scope, §6.5 leaks, §6.6 reader-Sync — six subsections under one heading, each a different concern. The "what's the implementation contract" reader has to glue these together themselves.

**Fix:** Lift §6.4-§6.6 to a peer §6.A "Engine invariants" subsection or to §20 "Invariants" (already exists; move §6.4-§6.6 there and forward-reference).

### S3. Cancellation auto-fire on timeout is buried in §7

The timeout config field lives in `Config` (§6), but the auto-fire semantics are in §7 (lines 416-425). A reader looking at `Config.requestTimeout` has no signal that policy interaction exists.

**Fix:** Add a one-line comment on line 283: `requestTimeout: Duration = Duration.Infinity, // see §7.timeout for cancellation interaction`.

### S4. No wire example anywhere

Lens-4 explicitly asks for a CDP `withSession` wire example. None exists. A reader has to mentally encode "call with `extras = Present(Json.Object("sessionId" -> "X"))`" through `Cdp` codec to see what hits the wire. Same for MCP `notifications/progress` with `_meta.progressToken`.

**Fix:** Add one wire-example block per consumer (3 short JSON blobs) at the end of §3 or in §16.

### S5. §13 introduces routing-hint Option (a)/(b) decision but doesn't show how (b) actually works

Lines 583-587 declare option (b) chosen, but skip the actual mechanism: "Transport infers routing from envelope shape". For Response that's the id; for server-initiated requests/notifications there's no id-to-POST mapping, so how does the MCP transport route a server-initiated notification to a specific GET SSE channel when there are multiple GETs from the same session?

**Fix:** Add one sentence: "When multiple GET SSE channels are open for a session, server-initiated notifications fan out to all of them; the transport (not engine) handles fan-out and replay-buffer dedup."

### S6. §16 tables are excellent but duplicate §6-§8

Reader who's read §6-§8 already knows what `endpoint.callWithProgress` covers; the §16 LSP table tells them again. Worth keeping for coverage proof, but mark as "skim if you've read §6-§8".

---

## LLM-tells (em-dashes, hedges, filler)

Em-dashes (`—`): **15 occurrences**. Per CLAUDE.md, banned. Exact lines:

- Line 189: `// Convenience overload — handler that doesn't need ctx`
- Line 304: `outboundIds: AtomicLong — per-endpoint id counter.`
- Line 305: `pendingOutbound: ... — calls we issued.`
- Line 306: `pendingInbound: ... — calls we received...`
- Line 307: `progressStreams: ... — token → progress channel...`
- Line 308: `partialResults: ... — LSP partial-result accumulator...`
- Line 309: `writer: Channel[...] — single mpmc channel...`
- Line 310: `meter: Maybe[Meter] — semaphore for maxInFlight...`
- Line 398: `...whether to "send a reply" — the engine enforces...`
- Line 462: `1. **call(method, params)** — plain, no progress.`
- Line 463: `2. **callWithProgress(...)** — returns Pending[Out]...`
- Line 464: `3. **callPartialResults[T](...)** — returns Stream[T, ...]...`
- Line 566: `## 13. MCP Streamable HTTP — the hardest case`
- Line 800: `...cancellation fires — all paths use Sync.ensure...`

**Fix:** Replace each `—` with `:` (line 304-310 bullets are textbook colon territory), parentheses (line 398, 800), or sentence break (line 566 heading: "MCP Streamable HTTP: the hardest case").

### Vague hedges

- Line 215: "Cleaner than the earlier `Outcome.Reply | NoReply` ADT." — historical justification, not implementation guidance. Cut.
- Line 421, 788: "just abort locally." Replace "just" with "only".
- Line 486: "just a registration entry-point". Same.
- Line 743: "is mostly compatible". Replace with a definite list of incompatibilities (§17 already has it; cut "mostly").
- Line 807: "Engine just calls the policy." Replace "just" with the actual contract.

### Filler / summary sentences

- Line 25: "The table is the load-bearing constraint set. Every design choice below traces back to a row in this table." — restates the previous paragraph's purpose. Cut or merge into §1's opening.
- Line 126: "Two ready-built codecs ship. Both produce/consume the same `JsonRpcEnvelope` ADT. Everything above this layer sees only the ADT." — first sentence is content; second and third restate the diagram. Cut sentences 2-3.
- Line 165: "The engine sees a single duplex envelope stream regardless." — restates lines 149-155. Cut.
- Line 425: "This satisfies LSP's report §3 ..." — cross-reference to research the reader explicitly does not have. Cut all such cross-references (also line 341, 497, 589, 600, 737); they were authoring-time bookkeeping.

---

## Quick wins

| Line | Current | Suggested |
|---|---|---|
| 106-108 | `extras: Json.Value` | `extras: Maybe[Json.Value]` (match every other mention) |
| 141 | `trait JsonRpcTransport(using Frame):` | Match §2 diagram: pick `[Env]` or not, then make consistent |
| 265 | `def awaitDrain(using Frame): Unit < Async` | Add a one-line scaladoc spelling out drain semantics |
| 306, 318 | `Fiber[Outcome, Any]`, "discard Outcome" | Replace `Outcome` with the actual type (see ambiguity #2) |
| 318 | 4-line pseudocode block | Number the steps; insert Gate and policy-intercept order explicitly |
| 462-464 | em-dashes in numbered list | Use colons |
| 566 | `## 13. MCP Streamable HTTP — the hardest case` | `## 13. MCP Streamable HTTP: the hardest case` |
| 287-291 | `JsonRpcEndpoint.init(...)` | Add: scaladoc note that `methods` is captured by-reference; new methods registered after init are not visible |
| 530 | `Custom(next: () => JsonRpcId < Sync)` | Add: "called from the calling fiber; must be thread-safe under concurrent calls" |
| 660 | "The prompt's 'exactly these seven public types' target was too tight..." | Cut. §17 already covers this in table form. |

---

## Coverage notes

- §6 API (line 224-291): 8 declared methods. 3 implementable as written (`notify`, `close`, `subscribeProgress`). 5 under-specified (`call` extras handling on non-CDP, `callWithProgress` progress-channel capacity, `callPartialResults` chunk-decode failure handling, `cancel` unknown-id behavior, `awaitDrain` semantics).
- §7 `CancellationPolicy`: prose and code match field names (`cancelMethod`, `encodeParams`, `expectReplyForCancelledRequest`, `cancelledError`). No drift detected.
- §8 `ProgressPolicy`: prose and code match (`progressMethod`, `extractInboundToken`, `extractRequestToken`, `stampOutboundToken`, `encodeProgressParams`). No drift detected.
- §15 surface: 17 types declared. 2 (`JsonRpcRequest`, `JsonRpcResponse`) have weak justification ("thin HTTP convenience layer" not defined anywhere).
- §20 invariants: 12 listed, all consistent with §6-§8 except #11 which contradicts the §3 ADT (extras typing — see ambiguity #1).
