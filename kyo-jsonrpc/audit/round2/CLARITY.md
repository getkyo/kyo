# Round 2 Clarity audit

Audit perspective: smart engineer reading only DESIGN.md, asked to implement from scratch. Round 1's 10 implementation-blocking issues were verified one by one against the current text; status noted at the end. All line numbers cite `kyo-jsonrpc/DESIGN.md`.

## Implementation-blocking ambiguities

### 1. `HandlerCtx.cancelled` type contradicts the inbound state machine

Line 228: `val cancelled: Fiber.Promise[Unit, Any], // completed when peer requests cancel`.
Line 369-371: `// Cancellation completes 'cancelled' (the AtomicBoolean backing HandlerCtx.cancelled). ... case class Running(method: String, handler: Fiber[Structure.Value, Any], cancelled: AtomicBoolean)`.
Line 548: "If present, complete `HandlerCtx.cancelled`."

`Fiber.Promise` and `AtomicBoolean` are not the same thing, you "complete" a Promise but "set" an AtomicBoolean. The §6 internal note explicitly says the AtomicBoolean *backs* the Promise (some adapter), but no adapter is shown, and `Promise[Unit, Any]` is the wrong arity (`Promise[E, A]` would be `Promise[Nothing, Unit]` or similar — Kyo's actual `Fiber.Promise[E, A]` has `E` first). An implementer can't tell whether to expose a Promise, an AtomicBoolean, or something composite.

**Fix:** Pick one. Either `cancelled: AtomicBoolean` (cheaper, matches `InboundEntry.Running.cancelled`), or `cancelled: Fiber.Promise[Nothing, Unit]` (matches "complete" verb in §7). Update lines 228, 369-371, 548 to be the same type, and verify the type parameter order matches Kyo's actual `Fiber.Promise`.

### 2. `progressSink = Absent` vs. `progress = Absent` field naming

Line 231: `progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]`.
Line 238: "CDP installs `progress = Absent`".

Either the field is `progressSink` (matches line 231 and §8 lines 667/676) or `progress` (line 238). An implementer copying the prose-named field hits a `not found: progress` compile error.

**Fix:** Line 238: `progressSink = Absent`.

### 3. `JsonRpcId` ADT definition is never shown in the document

Line 76, 77, 798: "JsonRpcId (with hand-written flat Schema[JsonRpcId])" / "enum Num | Str (flat Schema)". The §3 envelope ADT uses `JsonRpcId` (line 120) and so does every API method. But the enum's case constructors and field types are nowhere declared. Line 961 implies `JsonRpcId.Num(1L)` (Long) and `JsonRpcId.Str("a")` (String). What about `JsonRpcId.Null`? Line 13 says MCP requires "id non-null" while JSON-RPC 2.0 permits Null. The codec rejection rule depends on a case that may or may not exist.

**Fix:** Add a code block in §3 before the envelope ADT:
```scala
enum JsonRpcId derives CanEqual:
    case Num(value: Long)
    case Str(value: String)
// Null id: represented as Maybe[JsonRpcId] = Absent. Wire `id: null` is rejected
// by Strict2_0 codec for requests, accepted only for notification-style 2.0 responses
// to unparseable requests.
```

### 4. `JsonRpcError` is referenced as a value before its case-class shape appears

Line 122: `error: Maybe[JsonRpcError]`. Line 821: `val ParseError = JsonRpcError(-32700, "Parse error", Absent)` (used as a constructor). But the case class fields are never declared. By inference it's `JsonRpcError(code: Int, message: String, data: Maybe[Structure.Value])`. Implementer must reverse-engineer the third positional field, which the rest of the doc calls "data" (line 459, 839).

**Fix:** Add a code block in §3 (or open §15 with):
```scala
case class JsonRpcError(code: Int, message: String, data: Maybe[Structure.Value]) derives Schema
```

### 5. `Exchange` parameterization is asserted but never shown concretely

Line 352: "`Exchange[Structure.Value, Structure.Value, JsonRpcEnvelope, Closed]` (or similar; exact `Req/Resp/Event/E` parameterization is pinned by the codec)".

The hedge "(or similar; ... pinned by the codec)" is exactly what an implementer cannot work from. `Req=Structure.Value` and `Resp=Structure.Value` makes no sense for engine-level dispatch (the engine wants `Req=JsonRpcMethod[?] applied params`, `Resp=typed Out`). The whole §6.1 surface (Exchange's `nextId`, `encode`/`decode` callbacks, `apply`, `Message.Response`, `Message.Push`, `Message.Skip`) is invoked without ever defining what Exchange's API actually looks like.

**Fix:** Either (a) embed a 10-line summary of Exchange's type signature and the four `Message` cases this design uses, OR (b) commit to a single concrete `Exchange[A,B,C,D]` instantiation with each parameter named (`Req = Structure.Value`, `Resp = Structure.Value`, `Event = JsonRpcEnvelope`, `E = Closed`?). Without one of these, §6.1 is a stub.

### 6. `Pending[Out].cancel` field is documented but the API to construct it is unspecified

Line 323: `val cancel: Unit < (Async & Abort[Closed])`. This is a stored *computation*, not a function. So calling `pending.cancel` runs cancellation by simply evaluating the field? Once? Twice? If the user evaluates `pending.cancel` twice, what happens (second call no-op, error)?

**Fix:** Either change to `def cancel(using Frame): Unit < (Async & Abort[Closed])` with idempotency stated, or document that "the `cancel` value is idempotent; subsequent evaluations are no-ops".

### 7. §6.2 step 4 "Response routing" loses the partial-result path

Line 424-426: "If id in progressStreams (partial-result token equals the request id): interpret the empty `result` as stream close." But progress tokens and request ids are different namespaces (line 644: "Allocates a fresh token (string UUID or counter)"). The token is *attached* to params via `stampOutboundToken`; the request id is allocated by Exchange. They are unrelated. The condition "id in progressStreams" can never be true unless tokens and ids coincide.

Separately, `partialResults` (the dedicated `ConcurrentHashMap` at line 378) is the one keyed by token to a `Channel[Structure.Value]` for `callPartialResults`. Step 4 references `progressStreams`, not `partialResults`. Inconsistent.

**Fix:** Rewrite line 424-426: "If the request was issued via `callPartialResults` (engine tracks this via `partialResults[token]`, where `token` was registered for this id at outbound time): interpret the empty `result` as stream close on `partialResults[token]`." Add an id→token side-table to internals at §6.1 so the reader fiber can look up the token from the response id.

### 8. `notify` ExtrasEncoder receives "synthesized non-routing id" — what value?

Line 265-267: "ExtrasEncoder still receives an id (synthesized non-routing id) so the closure shape stays uniform... CDP uses a negative-id slot if its server expects one, otherwise Absent."

What is the "synthesized id"? An implementer needs a concrete value. Is it `JsonRpcId.Num(-1L)` always? A fresh sequential negative? `JsonRpcId.Str("notify")`? CDP code currently uses negative ids by convention (per existing kyo-browser). Without naming the value, two implementations diverge.

**Fix:** Specify: "Engine passes `JsonRpcId.Num(-1L)` to the `ExtrasEncoder` for `notify`. Wire envelope omits the id field regardless (notifications have no id). CDP's encoder closure may use this sentinel to detect notification context."

### 9. Decode failure handling inside the `decode` callback is undocumented

Line 388-392: "Inbound routing → engine-supplied `decode` callback. For `Response` envelopes... For `Request` and `Notification` envelopes it does the engine-side dispatch (fork handler / fire policy intercept) inside `decode` and returns `Exchange.Message.Skip`."

§6.2 step 4 says "Malformed: if env.raw has a parseable id → send Response(id, ParseError); else log and drop." But §6.2 runs in the reader. If dispatch happens inside `decode`, where does Malformed-recovery sending happen? `decode` is `Sync` and writing to the outbound channel (`outbound.put`) might be `Async`. Implementer can't tell whether to enqueue the error reply synchronously, fork a sender fiber, or let Exchange handle it.

**Fix:** Add to §6.1 or §6.2: "Malformed envelope with parseable id → reader enqueues `WriterMsg.Send(Response(id, ParseError))` via `outbound.put`. `outbound.put` on a `Channel` is `Sync` when the channel is unbounded or has free capacity; engine sizes `outbound` such that the writer-side enqueue is always non-blocking."

### 10. `requestTimeout` cancellation order is asserted but not implementable

Line 562-564: "If `Config.cancellation = Present`: engine fires the cancellation policy (same as `endpoint.cancel(id)`)... Matches MCP spec".

What "fires" means concretely:
- Does the engine emit the cancel notification first, then abort the caller? Or abort first?
- The "same as `endpoint.cancel(id)`" path interrupts `callerRegistry[id].callerFiber`. But the *caller* is the fiber that just timed out — it cannot interrupt itself; the engine fiber must do it.
- Test E in round 1 asked for ordering; the doc still doesn't say.

**Fix:** Specify: "On timeout, the engine (a) enqueues the cancellation notification to the writer channel, (b) completes the caller's Exchange pending entry with `Abort.fail(JsonRpcError.RequestCancelled)` or the policy's `cancelledError`. Steps happen in that order; the caller observes the abort after the cancel envelope is enqueued (not necessarily after it's transmitted)."

---

## Test-blocking ambiguities

### A. "Exchange drops the response silently" is asserted four times but has no observable hook

Lines 381, 430, 458, 558, 1019: every cancellation path ends with "Exchange drops silently". A test for "outbound cancel + late reply is dropped" needs to count frames somewhere. The in-memory transport's outbound channel is the natural probe, but the design never says whether the in-memory `JsonRpcTransport` exposes its outbound `Channel` for introspection.

**Fix:** Add to §4 in-memory transport spec: "In-memory transport exposes `sent: Stream[JsonRpcEnvelope, Async]` and `received: Stream[JsonRpcEnvelope, Async]` for test inspection. Late responses arriving after a cancelled caller are visible in `sent` of the sender (the peer's transport) but absent from `received` after `awaitDrain` (the local reader saw and discarded)."

### B. `progressStreams` channel capacity and overflow policy still unspecified

Lines 307, 377, 478: progress channels referred to without capacity. Line 478: "Pushing to progress channels: `Channel.Unsafe.offer` (non-blocking)." `offer` semantics: returns `false` on full channel? Drops? Throws? An implementer cannot pick capacity without knowing overflow.

**Fix:** Specify in §6.1: "`progressStreams[token]: Channel[Structure.Value]` is created with capacity 64 and drop-oldest overflow. The reader fiber uses `Channel.Unsafe.offer`; on rejection the progress value is logged and dropped (reader never parks)."

### C. `Pending[Out].progress: Stream[Structure.Value, Async]` cannot signal channel close

Line 322: `progress: Stream[Structure.Value, Async]`. Effect row is `Async` only — no `Abort[Closed]`. But progress channels close when the final response arrives (line 648) or when the endpoint shuts down (§6.4). A `Stream[_, Async]` has no way to express "the underlying channel was closed by us, here's a sentinel"; it just terminates.

That's fine if termination is normal; but how does the consumer distinguish "no more progress (final response received)" from "endpoint shut down"? Test cannot assert the latter.

**Fix:** Either (a) widen to `Stream[Structure.Value, Async & Abort[Closed]]` and have the engine fail on shutdown, OR (b) commit to "stream terminates normally on both final response and shutdown" and explicitly call out that callers wanting shutdown detection should use `endpoint.close`'s scope.

### D. `partialResults` final-empty-response detection has no schema

Line 378, 426, 641, 649: the "final empty response" terminates the stream. What does *empty* mean for `Maybe[Structure.Value]`? `Present(Structure.Value.Null)`? `Absent`? `Present(Record(Chunk.empty))`? LSP says `result: null` ends partial-result; the doc never says how the engine matches that.

**Fix:** Specify: "`callPartialResults` treats `Response(id, result = Present(Structure.Value.Null), error = Absent)` as the terminator. Any other final result (including `Absent` or non-null `Record`) is decoded as the last `T` chunk."

### E. "Handler panic" definition still partially open

Lines 349, 459, 805 (and round 1 finding E noted the same): panic still defined informally. §6.5 line 459 narrows it to "panic" without naming Kyo's `Result.Panic` machinery. An LSP/MCP implementer wants to write a unit test that throws a particular exception and asserts the reply is `InternalError`.

**Fix:** Tighten line 459: "Handler panic means any `Result.Panic` produced by the handler fiber: uncaught exceptions, aborts whose error type is not `JsonRpcError`, defects. The engine maps the panic to `JsonRpcError.internalError(panic.toString, data = Present(Structure.Value.Str(stackTrace)))`."

---

## Prose-code mismatches

### M1. Line 238 says `progress`, line 231 declares `progressSink` (covered above as ambiguity #2).

### M2. Line 228 declares `Fiber.Promise[Unit, Any]`; line 371 stores `AtomicBoolean` (covered above as ambiguity #1).

### M3. `JsonRpcEnvelope.extras` is `Maybe[Structure.Value]` everywhere now (round 1 finding #1 fixed)

Lines 120-122 (ADT), line 230 (HandlerCtx), line 254 (ExtrasEncoder), line 805, line 1027. All match. Resolved.

### M4. `Fiber[Outcome, Any]` removed from §6.1 (round 1 finding #2 fixed)

Line 370 now declares `Fiber[Structure.Value, Any]`. No `Outcome` references remain except as historical negation ("No Outcome ADT"). Resolved.

### M5. §6.1 says "outbound id allocation is Exchange's job, not ours" (line 383) but §10 (line 711) `IdStrategy.Custom(next: () => JsonRpcId < Sync)` still implies the engine has a `next()` function

If Exchange owns allocation, then `IdStrategy` is passed *to Exchange's `nextId`* at init. The doc says exactly that at line 383: "translates its `Config.idStrategy` to Exchange's `nextId: => Id < Sync` parameter at init time." Fine. But the §10 reader sees a thunk-shaped API and wonders who calls it. Round 1 finding #7 ("who calls `next()`") still half-open: now it's clearly Exchange's reader/whatever, but the question of *concurrency safety* (multiple outbound calls allocating in parallel) is still not stated.

**Fix:** Append to §10: "Engine passes `idStrategy.next` to Exchange's `nextId` parameter. Exchange may call `next` from any fiber serving an outbound call; the user's `Custom` implementation must be thread-safe (typically `AtomicLong.incrementAndGet`)."

### M6. `JsonRpcRequest / JsonRpcResponse` listed as public (line 795-796) but never defined

§15 lists them as "wire types (retained for thin HTTP convenience layer)". The convenience layer is not described anywhere (round 1 finding M6 still open). §3 only defines `JsonRpcEnvelope`. A reader cannot tell what these types are or how they relate to `JsonRpcEnvelope.Request` / `JsonRpcEnvelope.Response`.

**Fix:** Either drop the two from §15 (they live in kyo-http or wherever the "convenience layer" lives), or define them in §3 as concrete case classes with their schemas. The current text is a dangling reference.

### M7. `JsonRpcCodec.encode` signature returns `Structure.Value < Sync`; transport `send` takes `JsonRpcEnvelope`

Lines 135-136 (codec): `encode(env: JsonRpcEnvelope): Structure.Value < Sync` and `decode(raw: Structure.Value): JsonRpcEnvelope < Sync`. But the transport at line 165 says `send(env: JsonRpcEnvelope): Unit < (Async & Abort[Closed])` and incoming yields envelopes. Where does the codec get invoked? Per §4 line 172: "transport delivers *already-codec'd* envelopes". OK, so the codec lives *inside* the transport adapter. But the transport's signature doesn't take a codec as a constructor argument anywhere in §4 or §15. How does the user wire a `JsonRpcCodec.Cdp` to a WebSocket transport?

**Fix:** Add to §4: "Transport implementations accept a `JsonRpcCodec` at construction. Example: `JsonRpcTransport.webSocket(uri, codec = JsonRpcCodec.Cdp)`. This module ships only the in-memory transport; in-memory uses `Strict2_0` by default."

---

## Structural friction

### S1. §1 table still presumes vocabulary defined later (round 1 finding S1 not addressed)

"Per-direction id namespace", "Server-initiated routing", "maxInFlight=8 REQUIRED" all appear before §2 defines what owns each. Glossary not added.

**Fix:** As round 1 proposed: one-line glossary at end of §1.

### S2. §6 still mixes API + internals + scope + leaks + reader-Sync (round 1 finding S2 not addressed)

§6 still spans subsections 6.1 through 6.6 covering six distinct concerns under one heading.

**Fix:** Move §6.4-§6.6 into §20 ("Invariants") with forward-references, OR split §6 into §6 "API" and §6.A "Engine internals".

### S3. Timeout-fires-cancellation buried in §7 (round 1 finding S3)

Line 333 declares `requestTimeout: Duration = Duration.Infinity` with no hint that it interacts with cancellation. The interaction is at line 560-569.

**Fix:** Inline-comment line 333: `requestTimeout: Duration = Duration.Infinity, // auto-fires cancellation when Config.cancellation = Present; see §7`.

### S4. No wire-format examples (round 1 finding S4)

No JSON blob examples anywhere. CDP `{"id": 1, "method": "Page.navigate", "sessionId": "X", "params": {...}}` not shown. MCP `{"method": "notifications/progress", "params": {"progressToken": "t1", "progress": 50}}` not shown. LSP `Content-Length: 47\r\n\r\n{"jsonrpc":"2.0","method":"$/cancelRequest","params":{"id":7}}` not shown. An implementer must mentally simulate every codec.

**Fix:** Add a §3.1 "Wire examples" with three short blobs (one per consumer).

### S5. §13 option (b) still doesn't explain server-initiated routing for multi-GET sessions (round 1 finding S5)

Line 768 still asserts "transport tracks pending POSTs by request id itself" without describing fan-out for unsolicited server-initiated requests when 0 GETs are open or multiple GETs are open.

**Fix:** As round 1 proposed.

### S6. §16 tables duplicate §6-§8 in declarative form (round 1 finding S6)

Tables are still excellent coverage proof; not a blocker. Recommend marking as skippable.

### S7. §6.1 internals list grows; relationships unclear

`outbound`, `callerRegistry`, `pendingInbound`, `progressStreams`, `partialResults`, `meter`. Six side-tables. Their interactions (id→token, token→channel, id→fiber, id→method) need a small map.

**Fix:** Add an ASCII diagram or a 3-line table at the top of §6.1:
```
callerRegistry  [outbound id → method + caller fiber]    // engine-owned cancel lookup
pendingInbound  [inbound  id → handler fiber/suppress]   // engine-owned inbound state
progressStreams [token    → Channel[Structure.Value]]    // outbound progress + subscribe
partialResults  [token    → Channel[Structure.Value]]    // callPartialResults specifically
```

---

## LLM-tells (em-dashes, hedges, filler)

Em-dashes (`—`): **0 occurrences.** Round 1 fixes landed cleanly.

### Vague hedges still present

- Line 240: `Cleaner than the earlier 'Outcome.Reply | NoReply' ADT.` Historical justification, doesn't help an implementer. Cut.
- Line 352: `(or similar; exact Req/Resp/Event/E parameterization is pinned by the codec)` — exact opposite of what the implementer needs. See ambiguity #5.
- Line 381: `(Earlier drafts of this design had one; removed as over-engineering once we confirmed Exchange covers it.)` Historical, cut.
- Line 565: `engine just aborts the local pending entry`. Replace "just" with "only".
- Line 663: `just a registration entry-point`. Same.
- Line 933: `mostly compatible`. Replace "mostly" with the actual list of incompatibilities (§17 has them).
- Line 1026: `Engine just calls the policy.` Replace "just" with "delegates to".

### Filler / cross-references-to-research-the-reader-doesnt-have

- Line 25: `The table is the load-bearing constraint set. Every design choice below traces back to a row in this table.` Restates the previous sentence's purpose. Cut.
- Line 149: `Two ready-built codecs ship. Both produce/consume the same 'JsonRpcEnvelope' ADT. Everything above this layer sees only the ADT.` Sentence 3 restates the §2 diagram. Cut sentence 3.
- Line 180: `The engine sees a single duplex envelope stream regardless.` Restates lines 168-179. Cut or merge.
- Line 451: `The MCP report's R6... and the LSP report's "bidirectional, no client/server asymmetry" are both satisfied.` Cross-references reports the reader doesn't have.
- Line 469: `This is the fix called out by the correctness audit's race-condition #3.` Cross-reference; cut or rephrase as "The race is described in §6.5 ¶1."
- Line 569: `This satisfies LSP's report §3... MCP's report R9, MCP-body §4 timeout requirement, and CDP's report point #7.` Cross-reference; cut.
- Line 678: `This satisfies LSP report §4... MCP report R10/R17, MCP body §5..., CDP report (no progress).` Cross-reference; cut.
- Line 770: `This satisfies MCP report R13...` Cross-reference; cut.
- Line 847: `The prompt's "exactly these seven public types" target was too tight to express what CDP needs (pluggable codec) and what LSP needs (cancellation/progress policies). The deviation is documented in §17.` Two sentences of meta-commentary; cut. §17 stands on its own.
- Line 1043: `End of design. Ready for review.` Filler-of-filler. Cut.

---

## Quick wins

| Line | Current | Suggested |
|---|---|---|
| 228 / 369-371 / 548 | mixed `Fiber.Promise[Unit, Any]` + `AtomicBoolean` + verb "complete" | Pick one type, fix three sites consistently (ambiguity #1) |
| 238 | `CDP installs progress = Absent` | `CDP installs progressSink = Absent` |
| 322 | `progress: Stream[Structure.Value, Async]` | Specify whether close-on-shutdown is `Abort[Closed]` or normal termination (ambiguity C) |
| 333 | `requestTimeout: Duration = Duration.Infinity` | Append inline comment: `// auto-fires cancellation if Config.cancellation = Present; see §7` |
| 352 | `Exchange[Structure.Value, Structure.Value, JsonRpcEnvelope, Closed] (or similar; ...)` | Drop the "or similar" hedge; commit to the four type parameters and what each means (ambiguity #5) |
| 459 | "panic" | Define as "any `Result.Panic`: uncaught exceptions, non-`JsonRpcError` aborts, defects" |
| 565, 663, 1026 | `just` (filler) | `only` / `delegates to` / etc. |
| 795-796 | `JsonRpcRequest / JsonRpcResponse` in public surface with no definition | Drop, or define in §3 |
| 798 | `JsonRpcId // enum Num \| Str (flat Schema)` | Add the actual `enum JsonRpcId` declaration to §3 |
| 805 | `ExtrasEncoder // type alias JsonRpcId => Maybe[Structure.Value] < Sync` | Match line 254 exactly (or fix line 254); the `< Sync` placement is unusual for a `type =` (ambiguity in parsing) |
| 933 | `mostly compatible` | "compatible except for the deviations listed below" |
| 1043 | `End of design. Ready for review.` | Cut. |

---

## Round-1 issue verification

| Round 1 # | Status |
|---|---|
| 1. `extras` typing inconsistency | Resolved (lines 120-122 now `Maybe[Structure.Value]`) |
| 2. `Fiber[Outcome, Any]` ghost type | Resolved (line 370 is `Fiber[Structure.Value, Any]`) |
| 3. `JsonRpcTransport[Env]` parameterization | Resolved (line 164 has no type parameter; §4 explicitly removes it) |
| 4. `unsubscribeProgress` not in surface | Resolved (line 294 declares it; line 805 implies it; §15 includes via the ProgressPolicy area) |
| 5. progress-channel capacity/overflow | NOT resolved (still no capacity; see test-blocking B) |
| 6. Reader pseudocode under-specified | Resolved (§6.2 now has 4 numbered steps with explicit gate / dispatch / response order) |
| 7. `IdStrategy.Custom` who calls `next` | Partially resolved (Exchange calls it; thread-safety still implicit; see prose-code M5) |
| 8. `awaitDrain` semantics undefined | Resolved (line 306-310 names all three quiescence conditions) |
| 9. `endpoint.cancel(id)` on unknown id | Resolved (line 555 specifies no-op) |
| 10. Late-reply handling drop direction | Resolved (§6.5 specifies writer-side `suppress` flag, §7 inbound clause is explicit about handler interrupt + writer drop) |

Net: 7/10 cleanly resolved, 2/10 partially resolved (5 and 7), 1/10 unresolved is moot because the new findings supersede it.

---

## Coverage notes

- §6 API (lines 256-312): 8 declared methods. `notify`, `subscribeProgress`, `unsubscribeProgress`, `close` implementable. `call`, `callWithProgress`, `callPartialResults`, `cancel`, `awaitDrain` clearer than round 1; still gated on ambiguities #5 (Exchange shape), #6 (Pending.cancel idempotency), #7 (partial-result terminator).
- §7 `CancellationPolicy`: fully consistent; `protectedMethods` integrated cleanly.
- §8 `ProgressPolicy`: fully consistent; `enforceMonotonic` honored in §16/§19/§20.
- §15 surface: 21 types listed. 2 (`JsonRpcRequest`, `JsonRpcResponse`) still have weak justification ("thin HTTP convenience layer" not defined). `JsonRpcId` and `JsonRpcError` need their actual definitions in §3 / §15.
- §20 invariants: 12 listed, all consistent with §6-§8.
