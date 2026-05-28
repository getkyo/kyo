# Round 2 Completeness audit

Scope: `kyo-jsonrpc/DESIGN.md` (1044 lines) re-audited after revisions that introduced Exchange-rationalization, `callerRegistry`, `pendingInbound` Running/Replying state machine, `ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync`, `protectedMethods`, `enforceMonotonic`, race-safe writer suppression, six LSP error codes, awaitDrain semantics, scope finalizer order, and explicit phase tests. Anchored to `kyo-jsonrpc-PROMPT.md` (located at `cached-inventing-quasar/kyo-jsonrpc-PROMPT.md`), `research/MCP.md` (R1–R26 + body), `research/LSP.md` (DI1–DI8 + body), `research/CDP.md` (10 DIs + body), and `audit/COMPLETENESS.md` (round 1: 5 critical, 12 important, 8 minor).

---

## Round-1 regressions (previously-fixed items that are broken again)

None. Every round-1 critical and every important item that round 2 attempted to close is at least partially addressed in the current DESIGN. The remaining round-1 items that are still open are catalogued under their original category below as "carried forward".

---

## Critical gaps (block implementation)

### C1. `ExtrasEncoder` cannot reach engine-emitted outbound notifications

- **Source**: `research/CDP.md` DI #3, lines 256–257 ("`Req = Id => Wire`"). `research/MCP.md` lines 196–202 (cancel-notification flows S↔C). Prompt §6 (`endpoint.notify`).
- **DESIGN.md**: §6 plumbs `ExtrasEncoder` on `call`, `notify`, `callWithProgress`, `callPartialResults`. But the engine itself emits TWO outbound surfaces that bypass user code: (a) the cancel notification fired by `endpoint.cancel(id)` / timeout (§7), and (b) the `$/progress` / `notifications/progress` notification emitted from `HandlerCtx.progress` (§8). Neither path takes an `ExtrasEncoder`. For CDP this is moot (no cancellation policy). But for a hypothetical bidirectional CDP-like consumer needing a `sessionId` on engine-emitted notifications, there is no hook. More concretely for MCP: when a server-initiated request gets a `notifications/cancelled` from the engine, MCP's transport must route that to the right SSE channel by session, which it learned from the inbound request's `extras`. The cancel notification carries no extras → routing fails.
- **Impact**: MCP-over-Streamable-HTTP cannot route engine-emitted cancel/progress traffic to the originating SSE channel. CDP-like consumers cannot stamp sessionId on engine-emitted traffic.
- **Fix**: When the engine emits a cancel/progress notification on behalf of an outbound `call`, propagate the call's `ExtrasEncoder` to the notification. For inbound-triggered progress (handler `ctx.progress`), capture the inbound envelope's `extras` and re-emit on the notification. Make this explicit in §6.5 and §8.

### C2. `pendingInbound` lifecycle is missing the `Replying → done` removal step

- **Source**: §6.5 (lifecycle), §6.2 step 3 (dispatch).
- **DESIGN.md**: §6.5 says "Handler completion does NOT remove the entry. Instead, the entry transitions from `Running` to `Replying`... Either way, remove the entry after." `Either way, remove the entry after.` is the only sentence specifying when `Replying` exits the map. But removal is not anchored: it happens in the writer fiber after attempting to send. There is no specification of what happens if (a) the writer fiber is interrupted between dequeue and remove, (b) the transport `send` fails (`Abort[Closed]`) and the writer panics out, or (c) scope close fires §6.4's "interrupt every pendingInbound" before the writer drained. The race: a `notifications/cancelled` arriving while the writer is mid-send and another `notifications/cancelled` arriving after removal both need defined semantics, but the design only describes the first.
- **Impact**: `pendingInbound` can leak `Replying` entries on writer failure / scope close mid-write. `endpoint.cancel(id)` after that would see stale `Replying` state and call `suppress.set(true)` on an already-discarded envelope, which is harmless, but `awaitDrain` blocks on a never-cleared entry.
- **Fix**: Specify removal under `Sync.ensure` inside the writer's per-envelope action, not "after". Make §6.5 explicit: "writer dequeues `SuppressIfCancelled(id, env)` → `Sync.ensure { send(env) }(_ => pendingInbound.remove(id))`". Add §6.4 invariant: scope-close step 7 forcibly clears `pendingInbound` regardless of state.

### C3. Cancel arriving DURING the `Running → Replying` transition has undefined semantics

- **Source**: User prompt explicitly asks: "`pendingInbound` state machine (Running | Replying) — does the design cover the transition? What if cancel arrives DURING the transition?"
- **DESIGN.md**: §6.5 describes the transition as if atomic: "the entry transitions from `Running(handler, cancelled)` to `Replying(method, suppress)`". The reader fiber's policy intercept (§6.2 step 1) reads `pendingInbound[id]` and pattern-matches `Running` vs `Replying`. With `ConcurrentHashMap` and two `AtomicBoolean`s (`cancelled` on Running, `suppress` on Replying), the transition is not actually atomic: handler-fiber completes → engine writes the response to writer channel → engine replaces map entry → some time passes. If `notifications/cancelled` arrives after the handler-fiber completed but before the map replacement, the policy intercept reads `Running` and flips `cancelled`, but the handler is already done and will never observe it; the response then enters the writer channel without `suppress` ever being set, and MCP's no-reply guarantee is violated.
- **Impact**: MCP race: late cancel can leak a reply through. The race is small but reproducible under load (any time a cancel races a fast handler).
- **Fix**: Specify the transition as a CAS or sequence: (1) handler completes → engine builds response → engine does `pendingInbound.replace(id, Running, Replying)` first, THEN enqueues to writer. If the replace fails (because cancel intervened and changed to a sentinel `Cancelled` state), drop the reply. Add a third `InboundEntry.Cancelled` variant so the policy intercept can mark "handler is done, response not yet enqueued, drop on arrival".

### C4. `callerRegistry` is not populated atomically with Exchange's pending entry

- **Source**: User prompt explicitly asks: "`callerRegistry` is the new lookup table — is its lifecycle complete (when added, when removed, what if endpoint closes mid-call)?"
- **DESIGN.md**: §6.1 says `callerRegistry` "Populated when an outbound call enters Exchange; removed via `Sync.ensure` on exit". Exchange's `apply` is the entry point. But populating BEFORE `exchange(req)` means a cancel between population and Exchange's id-allocation sees `callerRegistry[id]` for an `id` Exchange has not yet allocated to this caller. Populating AFTER means a cancel between Exchange allocation and registry insertion finds no entry and is silently dropped, but the call is already in flight. The design says "Populated when an outbound call enters Exchange" without specifying which side of the `exchange(req)` call this is. Worse: the only way to learn the assigned id is through Exchange's `encode` callback, which runs INSIDE Exchange after id allocation but BEFORE the user's continuation. There is no documented hook for `callerRegistry` insertion that runs synchronously with id allocation.
- **Impact**: Race window where `endpoint.cancel(id)` cannot find the caller fiber. Plus: on endpoint-close mid-call, §6.4 step 5 "Close Exchange" fails the pending entry, but does not specify draining `callerRegistry`. Stale entries leak.
- **Fix**: Specify that `callerRegistry` is populated INSIDE the `encode` callback (where Exchange hands us the id), and removed via `Sync.ensure` wrapped around the entire `exchange(req)` call. Add to §6.4 finalizer order: step 7.5 = `callerRegistry.clear()`. Document `encode` callback's contract regarding registry population.

### C5. `endpoint.cancel` for an id not yet in Exchange's pending map is a silent no-op

- **Source**: §7 outbound cancellation flow step 2: "Look up `callerRegistry[id]`. If absent, no-op (already completed)."
- **DESIGN.md**: The flow conflates "already completed" with "id never existed in registry". A user typo (calls `cancel(JsonRpcId.Num(999))` for a never-issued id) silently succeeds. Additionally, if the user calls `cancel(id)` for an id that's mid-allocation (after `nextId` but before `callerRegistry` insert — see C4), it also no-ops. Worse: this no-op happens even when `Config.cancellation = Present`, meaning kyo-mcp cannot rely on "cancel always fires the cancellation notification for any valid id" because the engine drops the user's intent silently.
- **Impact**: Silent failure modes for cancellation. Diagnostic blind spot.
- **Fix**: Either log a warning when `callerRegistry[id]` is absent on cancel, or accept that cancel is best-effort and document it explicitly in §7. Note in §20 invariants.

### C6. Wire-type `derives Schema, CanEqual` requirement vs `JsonRpcEnvelope`

- **Source**: Prompt line 60 — "`derives Schema, CanEqual` on every wire-type case class." Round-1 I8.
- **DESIGN.md**: §3 line 119 declares `enum JsonRpcEnvelope derives Schema, CanEqual`. Round-1 said only `derives CanEqual` was present; round 2 added `Schema`. BUT: `JsonRpcEnvelope` includes `case Malformed(reason: String, raw: Structure.Value)`. `Schema` auto-derivation on an ADT containing a non-Schema-derivable variant (or one whose Schema is hand-written, per HANDOFF-mcp-wire-interop) can fail compile, OR worse, produce a wrapper shape (`{"Malformed":{"reason":...}}`) that hits the wire if anyone round-trips an envelope through Schema (e.g. for test assertions). `JsonRpcId` already needs a hand-written flat Schema per §15; `Structure.Value` is the universal value type and has its own Schema concerns (HANDOFF #2).
- **Impact**: Either compile failure, or wire-shape divergence if envelopes are ever Schema-round-tripped (which the prompt's test plan requires for round-trip tests). Round-1 I8 only partially closed.
- **Fix**: Either (a) keep `derives Schema` but document that envelope Schema is for test-only use and never hits the wire (the wire shape is codec's job), or (b) drop `Schema` derivation on `JsonRpcEnvelope` and add a hand-written Schema if tests need round-trip. Either way: explicit § entry, plus a phase-1 test that confirms the Schema-derived round-trip survives `Malformed`.

---

## Important gaps (design is incomplete without them)

### I1. `awaitDrain` block-on-pendingInbound semantics undefined for in-flight `Replying`

- **Source**: §6 line 308–310, CDP DI #10.
- **DESIGN.md**: §6 says awaitDrain waits for "(3) all pendingInbound handler fibers to complete". `Replying`-state entries have no handler fiber (handler already completed). Does awaitDrain block on `Replying` entries until the writer flushes them? If yes, it's actually waiting on the writer channel (covered by clause 1), but it's not stated. If no, the user can observe a state where `awaitDrain` returned but the response hasn't been sent yet.
- **Fix**: Specify in §6: "awaitDrain returns when (1) writer drained, (2) Exchange's pending map empty, (3) `pendingInbound` empty (both Running AND Replying gone). New outbound calls during drain: allowed (no gating) — matches CDP precedent." Round-1 I3 explicitly asks for the "gates new outbound?" answer.

### I2. `_meta` general passthrough still unaddressed (round-1 I1 carry-forward)

- **Source**: MCP.md lines 108–112 — general `_meta` escape hatch beyond progressToken.
- **DESIGN.md**: §8 only routes `_meta.progressToken`. Round 1 flagged this; round 2 has not added a hook. Handlers wanting to read `_meta.someOtherKey` must re-parse params themselves.
- **Fix**: Document in §20 invariants that `_meta` non-progressToken handling is consumer-side (consumer's `JsonRpcMethod[In]` `In` type must carry `_meta` if needed). One-line clarification.

### I3. Timeout reset on progress notification (round-1 I2 carry-forward)

- **Source**: MCP.md line 188 — "Implementations MAY reset the timeout on receiving a related progress notification but MUST enforce a maximum."
- **DESIGN.md**: §7 timeout fires unconditionally. Round 1 flagged; round 2 has not addressed. No `progressResetsTimeout` knob.
- **Fix**: Add `Config.progressResetsTimeout: Boolean = false` and `Config.maxTimeout: Maybe[Duration] = Absent`, OR document explicitly in §7 / §20 that the MAY-clause is consumer-implemented (consumer wraps `endpoint.callWithProgress` with its own resetting timeout).

### I4. ContentModified / ServerCancelled / RequestFailed emission path (round-1 I4 carry-forward)

- **Source**: LSP.md lines 213–216, 304–305.
- **DESIGN.md**: §15 promotes constants. §17 says "emitted by handler via Abort.fail". §6 does not show the explicit path that takes `Abort.fail(JsonRpcError(...))` → wire error response. Round 1 asked for this; round 2 added the constants but not the emission path or a test row.
- **Fix**: Add explicit clause in §6: "Handler `Abort.fail(JsonRpcError(code, ...))` → engine encodes that error verbatim as `Response(id, error = present)`. Engine never substitutes its own error code." Add phase 5 test row.

### I5. Event whitelist vs UnknownMethodPolicy semantics (round-1 I5 carry-forward)

- **Source**: CDP.md lines 109–118.
- **DESIGN.md**: §16.3 still says "kyo-browser registers only desired events; rest drop via UnknownMethodPolicy.strict". Round 1 questioned the equivalence. Round 2 has not clarified.
- **Fix**: One-line note in §9 or §16.3: "Registering a `JsonRpcMethod.notification` is the act of subscribing; `UnknownMethodPolicy` covers the unsubscribed case. CDP needs nothing further."

### I6. `Sync.Unsafe.defer` / `Promise.Unsafe.completeDiscard` / `Channel.Unsafe.offer` usage (round-1 I6 carry-forward)

- **Source**: Hard constraints from prompt + CLAUDE.md `feedback_no_unsafe`. Round-1 I6.
- **DESIGN.md**: §6.6 explicitly says "uses `Promise.Unsafe.completeDiscard`" and "`Channel.Unsafe.offer`". The user's prompt for this audit clarifies: "private `Sync.Unsafe.defer` only for state-init mirroring `Exchange`/kyo-sql precedent, with `// Unsafe:` comment." The design uses Unsafe in the HOT path (reader fiber per-envelope routing), not just state-init. Exchange itself uses these patterns internally, but here the engine is exposing them at its own reader-fiber level.
- **Impact**: Either (a) violates the safe-by-default rule per CLAUDE.md, or (b) the design is correct and matches Exchange's precedent and should be documented as such.
- **Fix**: Add a §6.6 paragraph explicitly stating: "These Unsafe calls match the precedent in `kyo.Exchange.readerLoop` (kyo-core) and `SqlClientBackend` (kyo-sql). Each call site annotated `// Unsafe:` with the reason. Safe alternatives would force `Async` into the reader fiber, breaking Exchange's contract." Per the constraint, this is acceptable; just make it explicit.

### I7. Public-API surface deviation requires user approval (round-1 I7 carry-forward)

- **Source**: Prompt line 267 — exactly 7 public types.
- **DESIGN.md**: §15 lists 21 types (round-1 said 17; round 2 grew the list). §17 justifies. Round-1 asked to promote §17 to "REQUIRES USER APPROVAL". Round 2 has not.
- **Fix**: Header in §15 or §17 explicitly flagging that the user must confirm before implementation. Otherwise validation step 7 of the prompt will fail.

### I8. Schema-derivation bugs prerequisite (round-1 I9 carry-forward)

- **Source**: MCP.md lines 327–328, 646.
- **DESIGN.md**: §15 mentions hand-written `Schema[JsonRpcId]`. The other two HANDOFF bugs (`Structure.Value`, `Json.JsonSchema`) are silent. Round 1 flagged. Round 2 has not addressed.
- **Fix**: §18 phase 1 sub-bullet: "Prerequisite check: confirm HANDOFF-mcp-wire-interop bugs #2 (`Structure.Value`) and #3 (`Json.JsonSchema`) are fixed upstream OR workaround in this module's hand-written Schemas."

### I9. `exit` after `shutdown` transport-close race (round-1 I11 carry-forward)

- **Source**: LSP.md lines 350–352.
- **DESIGN.md**: §6.4 finalizer order step 1 "poison writer channel" — if the user has just queued the `exit` notification reply, will it flush before transport close? Round 1 flagged. Round 2 explicit finalizer order does not include a "drain writer before transport.close" step.
- **Fix**: §6.4 step 1.5: "before cancelling writer fiber, await writer channel drain (bounded by a small timeout to prevent stuck transports holding shutdown)."

### I10. Stdio "MUST NOT contain embedded newlines" (round-1 I12 carry-forward)

- **Source**: MCP.md line 17.
- **DESIGN.md**: Not in §3 (codec) or §20 invariants. Round 1 flagged.
- **Fix**: §3 add: "Codecs MUST emit single-line JSON when serialised (no pretty-print). Transports that are line-delimited (MCP stdio) rely on this." Or document this is the transport adapter's job.

### I11. CDP "encoder receives engine-assigned id" claim is leaky

- **Source**: CDP.md DI #3, lines 256–257, §1 example.
- **DESIGN.md**: Round 2 added `ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync` which DOES hand the encoder the id. But the design's CDP-coverage table (§16.3 row 3) claims "encoder closure knows external state AND assigned id". For a closure that needs id BOTH in the `extras` slot AND in the `params` (e.g. CDP's `mkWire = id => CdpEnvelope(id, ...)`), the engine's `extras` slot is downstream of `params` construction — the id only reaches the `extras` builder, not `params`. CDP's `Req = Id => Wire` is for the WHOLE wire, not just the extras tail. The current design partially closes round-1 C2 but the `Req = Id => Wire` claim is still not fully honored: if a CDP-like consumer needed an id-dependent `params`, it can't get it.
- **Impact**: Most CDP usage today only needs sessionId in extras (covered). The strict `Req = Id => Wire` general case is not. Probably acceptable for kyo-browser; needs an explicit decision.
- **Fix**: §6 / §17 note: "ExtrasEncoder reaches the id; `params` are computed before id allocation and cannot depend on id. For id-dependent params, use `notify` (which lets the caller pre-build) or pre-allocate manually via `IdStrategy.Custom`." Or accept the limitation and document it as part of the engine contract.

### I12. Test plan coverage for prompt's 11 categories (round-1 C5 carry-forward)

- **Source**: Prompt §"Test coverage" 11 categories.
- **DESIGN.md**: §18 phases now include many of the round-1-missing tests (e.g. phase 4 has "transport-close-mid-call", phase 4 has "A.notify(B)"). But still missing or not explicitly enumerated:
  - "A response with both `result` and `error` should be rejected" — phase 1 now lists this. CLOSED.
  - "`Maybe[JsonRpcId]` Absent → no `id` key in JSON" — phase 1 lists this. CLOSED.
  - "Notification handler called: NO response emitted (even on failure). Assert by counting frames" — phase 2 mentions "verify by counting frames". CLOSED.
  - "Closing the transport mid-call: outstanding calls complete with `Abort.fail(Closed)`" — phase 3 / phase 4 covers. CLOSED.
  - "A sends notification to B; B receives it; no reply on the wire" — phase 4 has "A.notify(B)". Reply-on-wire absence is implicit. ADEQUATE.
- **Fix**: Round-1 C5 mostly closed; one remaining: phase 9 "HTTP-style server-only" test must explicitly assert the response shape matches `HttpHandler.postJsonRpc`. Currently §18 phase 9 says "Verify shape matches `HttpHandler.postJsonRpc` would produce" — adequate.

### I13. Build / module setup (round-1 I10 carry-forward)

- **Source**: Prompt §"Build & module setup".
- **DESIGN.md**: §18 phase 0 now mentions build.sbt: "Add `kyo-jsonrpc` as a crossProject (JVM + JS + Native), depending on `kyo-prelude`, `kyo-core`, `kyo-schema`. Plain `dependsOn`. Cross-platform settings matching `kyo-schema`." CLOSED.

### I14. ProgressPolicy enforceMonotonic implementation detail

- **Source**: MCP.md line 226.
- **DESIGN.md**: §8 `enforceMonotonic` is on the policy. §20 invariant 8 implies tokens are engine-side. But the monotonicity check stores per-token "last emitted value's progress" — where is that state kept? Not in `progressStreams` (those are channels). Not in `HandlerCtx`. The closure that becomes `progressSink` would need to capture a per-token `AtomicRef[Double]`. Design doesn't say.
- **Fix**: §8 add: "Per-call monotonicity state lives in `HandlerCtx.progressSink`'s closure as an `AtomicRef[Maybe[BigDecimal]]` initialised to Absent. Each call updates it via CAS."

### I15. `Config.idStrategy` value for `Custom` and concurrency

- **Source**: §10.
- **DESIGN.md**: `IdStrategy.Custom(next: () => JsonRpcId < Sync)` — the closure is called per outbound. Concurrent `call`s race the closure; the user's closure must be thread-safe. Not stated.
- **Fix**: §10 add: "`IdStrategy.Custom.next` must be safe to call concurrently. Engine does not serialise calls to it."

### I16. Reader fiber's "fork handler" path under maxInFlight

- **Source**: §6.1 + §11.
- **DESIGN.md**: §11 says maxInFlight wraps outbound calls. But inbound dispatch (§6.2 step 3 "Request: fork handler") has no cap. A consumer being flooded with inbound requests forks unboundedly. CDP relies on the `eventWhitelist` to drop; LSP/MCP have no equivalent at the engine level. Not necessarily wrong; not stated either.
- **Fix**: §20 invariant: "maxInFlight bounds outbound only. Inbound requests are dispatched without rate-limit; consumers needing inbound bounds use a `MessageGate` that returns `Decision.Reject` once over a threshold."

---

## Minor gaps (deferable)

### M1. OAuth (round-1 M1 carry-forward)
Still implicit. Note in §21. Acceptable.

### M2. `$/setTrace` (round-1 M2 carry-forward)
Consumer concern. Acceptable.

### M3. Ping (round-1 M3 carry-forward)
Consumer concern. Acceptable.

### M4. scalafmtCheckAll in validation (round-1 M4 carry-forward)
§18 phase 10 says "Cross-platform sweep" — doesn't list scalafmt. One-line addition.

### M5. SSE event-id resumability cursor surfacing (round-1 M5 carry-forward)
§13 still transport-only. Acceptable; note in §21.

### M6. Body charset metadata exposure (round-1 M6 carry-forward)
§14 punts to kyo-lsp. Acceptable.

### M7. Capability snapshot threading (round-1 M7 carry-forward)
kyo-mcp concern. Acceptable.

### M8. Partial-message timeout (round-1 M8 carry-forward)
Transport-layer. Acceptable.

### M9. `JsonRpcResponse.success` / `.failure` constructors

- **Source**: Prompt line 80 — "with `success` / `failure` constructors".
- **DESIGN.md**: §15 lists `JsonRpcResponse` but does not enumerate `success` / `failure` factories. They appear in the §8.2 existing-code excerpt of MCP.md. Probably implied; should be explicit in §15.
- **Fix**: §15 add: "`JsonRpcResponse.success(id, result)` and `JsonRpcResponse.failure(id, error)` factories enforce result-xor-error invariant. Raw `apply` is `private[kyo]`."

### M10. `JsonRpcId` `null` representation decision

- **Source**: Prompt lines 85–86 — "An id of JSON `null` is legal in responses... Decide... Pick one and document it."
- **DESIGN.md**: §3 mentions `JsonRpcCodec.Strict2_0` "rejects null id where spec requires" but does not document the chosen representation (extra `Null` variant on `JsonRpcId`, OR `Maybe[JsonRpcId]` = Absent for null). §15 lists `JsonRpcId` enum with only `Num | Str`. Implied: `Maybe[JsonRpcId] = Absent` carries the null case. Should be explicit.
- **Fix**: §3 add: "Spec-null id (response to unparseable request) is represented as `Maybe[JsonRpcId] = Absent` on the envelope; on the wire this is the explicit `id: null` key. `JsonRpcId` has no `Null` variant."

---

## Coverage matrix

| Source ref | Requirement | DESIGN.md addresses | Status |
|---|---|---|---|
| MCP R1 | Envelope types | §3 §15 | OK |
| MCP R2 | Maybe optionals | §3 §15 | OK |
| MCP R3 | Notification = id Absent | §3 (Notification variant) | OK |
| MCP R4 | Standard error codes + factories | §15 | OK (modulo M9 success/failure) |
| MCP R5 | Typed method builder | §5 | OK |
| MCP R6 | Bidirectional engine | §6 | OK |
| MCP R7 | Pluggable framing | §4 | OK |
| MCP R8 | Pending correlation | §6.1 (Exchange) | OK |
| MCP R9 | Cancellation primitive | §7 | OK |
| MCP R10 | Progress primitive | §8 | OK |
| MCP R11 | Frame propagation | §5 §6 | OK |
| MCP R12 | Safety defaults | §6.1 | **I6** |
| MCP R13 | Streamable HTTP adapter | §13 | OK (consumer) |
| MCP R14 | SSE event-id resumability | §13 | M5 |
| MCP R15 | Mcp-Session-Id | §13 | OK (consumer) |
| MCP R16 | MCP-Protocol-Version | §13 | OK (consumer) |
| MCP R17 | `_meta.progressToken` | §8 ProgressPolicy.mcp | OK |
| MCP R18 | Server-initiated requests | §13 §6 | OK |
| MCP R19 | Capability + lifecycle | §12 MessageGate | OK |
| MCP R20 | Standard MCP methods | (kyo-mcp) | OK |
| MCP R21 | No batching | §17 §21 | OK |
| MCP R22-R26 | Do-not-do list | §21 | OK |
| MCP body | initialize MUST NOT be cancelled | §7 `protectedMethods = Set("initialize")` | OK (was round-1 C1) |
| MCP body | Monotonic progress | §8 `enforceMonotonic` | OK (was round-1 C4); **I14** detail |
| MCP body | No progress after response | §8 `progressSink` invalidation | OK (was round-1 C3) |
| MCP body | Stdio no embedded newlines | — | **I10** |
| MCP body | Timeout reset on progress | — | **I3** |
| MCP body | General `_meta` passthrough | — | **I2** |
| MCP body | Sender ignores reply after cancel | §7 (Exchange drops) | OK |
| MCP body | Timeout SHOULD emit notifications/cancelled | §7 timeout auto-fires | OK |
| MCP body | Schema derivation bugs | — | **I8** |
| MCP body | Engine emits cancel/progress with extras | — | **C1** |
| LSP DI1 | Two-layer transport | §4 §14 | OK |
| LSP DI2 | Symmetric Endpoint | §6 | OK |
| LSP DI3 | Per-direction ids | §10 §20 inv 7 | OK |
| LSP DI4 | First-class cancellation | §7 | OK |
| LSP DI5 | First-class progress | §8 | OK |
| LSP DI6 | Dispatch policy is data | §9 §12 | OK |
| LSP DI7 | Header framing per-message | §14 | OK |
| LSP DI8 | Error codes open | §15 | OK |
| LSP body | Late reply after cancel | §7 (Exchange) | OK |
| LSP body | $/-prefix carve-out | §9 dollarPrefixOverride | OK |
| LSP body | Content-Length parsing | §14 | OK |
| LSP body | Charset rejection | §14 | M6 |
| LSP body | Header case-insensitivity | §14 | OK (consumer) |
| LSP body | Header size cap | §14 | OK (consumer) |
| LSP body | Lone \n tolerance | §14 | OK (consumer) |
| LSP body | partialResult empty final | §8 callPartialResults | OK |
| LSP body | workDoneProgress/create | §8 subscribeProgress | OK |
| LSP body | ContentModified / RequestFailed emission | §15 constants | **I4** |
| LSP body | ServerCancelled (-32802) dual | §15 | **I4** |
| LSP body | exit-after-shutdown | §6.4 | **I9** |
| LSP body | pendingInbound state machine cancel race | §6.5 | **C3** |
| LSP body | $/setTrace | — | M2 |
| LSP body | partialMessageTimeout | — | M8 |
| CDP DI1 | Wrap Exchange | §2 §6 | OK |
| CDP DI2 | Parametric envelope schema | §3 JsonRpcCodec | OK |
| CDP DI3 | Req = Id => Wire encoder | §6 ExtrasEncoder | **I11** partial |
| CDP DI4 | cap + timeout + drain | §6 §11 | OK; **I1** awaitDrain spec |
| CDP DI5 | sendNotification bypasses pending | §6 notify | OK |
| CDP DI6 | Errors decoded by caller | §6 | OK |
| CDP DI7 | Optional standard error helper | §15 | OK |
| CDP DI8 | Sync-only decode rule | §6.6 | OK |
| CDP DI9 | No sessionId in engine | §3 extras | OK |
| CDP DI10 | Surface awaitDrain | §6 | OK; **I1** spec gap |
| CDP body | Event whitelist | §16.3 | **I5** |
| CDP body | Negative-id fire-and-forget | §6 notify | OK |
| CDP body | Dialog drainer | (consumer) | OK |
| CDP body | maxInFlight=8 JS/Native | §11 | OK; **I16** inbound |
| Prompt | Wire types | §15 | OK; **C6** Schema-derive consistency |
| Prompt | JsonRpcMethod | §5 | OK |
| Prompt | Transport | §4 | OK |
| Prompt | Bidirectional endpoint | §6 | OK |
| Prompt | Cancellation | §7 | OK |
| Prompt | Progress | §8 | OK |
| Prompt | Configuration | §6 Config | OK |
| Prompt | success/failure constructors | — | **M9** |
| Prompt | JsonRpcId null representation | §3 implied | **M10** |
| Prompt | Batches decision | §17 (out of scope) | OK |
| Prompt | Public API 7 types | §15 (21 types) | **I7** |
| Prompt | Test: round-trip every envelope | §18 phase 1 | OK |
| Prompt | Test: JsonRpcId.Num/Str bare | §18 phase 1 | OK |
| Prompt | Test: both result+error rejected | §18 phase 1 | OK |
| Prompt | Test: Maybe[JsonRpcId] Absent | §18 phase 1 | OK |
| Prompt | Test: Method dispatch (5 sub-cases) | §18 phase 2 | OK |
| Prompt | Test: Notification handler no response | §18 phase 2 | OK |
| Prompt | Test: Bidirectional (3 sub-cases) | §18 phase 4 | OK |
| Prompt | Test: A notifies B no reply | §18 phase 4 | OK |
| Prompt | Test: Cancellation (2 sub-cases) | §18 phase 5 | OK |
| Prompt | Test: Progress | §18 phase 6 | OK |
| Prompt | Test: Scope cleanup | §18 phase 4 | OK |
| Prompt | Test: Closing transport mid-call | §18 phase 3/4 | OK |
| Prompt | Test: Three-consumer scenarios | §18 phase 9 | OK |
| Prompt | Module setup | §18 phase 0 | OK |
| Prompt | scalafmtCheckAll | — | M4 |
| Prompt | derives Schema, CanEqual | §3 §15 | OK; **C6** Envelope edge |
| Prompt | Custom Schema for JsonRpcId | §15 | OK |
| Prompt | Frame parameter | §5 §6 | OK |
| Prompt | No AllowUnsafe in public | §6.1 §6.6 | **I6** |
| Prompt | sessionId decision | §3 §17 extras + codec | OK |
| Audit (user) | Exchange owns pendingOutbound | §6.1 (Exchange) | OK |
| Audit (user) | callerRegistry lifecycle | §6.1 | **C4** |
| Audit (user) | pendingInbound Running → Replying race | §6.5 | **C3** |
| Audit (user) | ExtrasEncoder on all outbound | §6 | OK for user code; **C1** for engine-emitted |
| Audit (user) | endpoint.cancel after-completion | §7 step 2 | **C5** |
| Audit (user) | Sync.Unsafe vs AllowUnsafe constraint | §6.6 | **I6** documentation |

---

End of audit. Six critical (C1–C6), sixteen important (I1–I16), ten minor (M1–M10).
