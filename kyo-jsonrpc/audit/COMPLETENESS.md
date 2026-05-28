# Completeness audit

Scope: `kyo-jsonrpc/DESIGN.md` (820 lines) against `kyo-jsonrpc-PROMPT.md`, `research/MCP.md`, `research/LSP.md`, `research/CDP.md`. Items below are requirements *present in the source documents* that DESIGN.md either does not address, addresses too thinly to implement against, or punts to a consumer module without proving the consumer hooks exist.

---

## Critical gaps (must fix before implementation)

### C1. `initialize` must not be cancelable (MCP)

- **Source**: `research/MCP.md` line 205 — *"`initialize` MUST NOT be cancelled by the client."*
- **DESIGN.md**: `endpoint.cancel(id)` (§6) is policy-driven and accepts any id. No carve-out, no hook for the consumer to veto a cancel by method name. MCP would need to either inspect outbound notification envelopes before they reach the writer (no such hook) or wrap `cancel`.
- **Impact**: kyo-mcp cannot implement MCP-compliant cancellation without wrapping. Violates "no consumer wraps".
- **Fix**: Either document this as a kyo-mcp wrapper concern (and accept the wrap), or add an outbound `MessageGate` symmetric to the inbound gate so the MCP consumer can drop a cancel for `initialize`.

### C2. Encoder cannot reach the assigned id

- **Source**: `research/CDP.md` lines 256–257, design implication #3 — *"The send signature should hand the caller the id. Mirror `Req = Id => Wire` — let the encoder be a closure that knows external state (sessionId, custom headers, dynamic params) and receives the assigned id from the engine."* Also §1 example: `mkWire = (id: Int) => Json.encode(CdpEnvelope(id, method, params, sid))`.
- **DESIGN.md**: §6 `call[In: Schema, Out: Schema](method, params, extras)` takes only static `extras`. The id is allocated inside the engine and never handed to caller code. `JsonRpcCodec` runs against a fully-built `JsonRpcEnvelope` after id assignment; the codec sees the id but not user state.
- **Impact**: CDP-style consumers that need id-dependent envelope shaping (less common than sessionId-only, but the report calls it out as the engine contract) lose the `Req = Id => Wire` flexibility the report explicitly requires. Forces them back to "stamp via extras only".
- **Fix**: Either (a) make the design's commitment explicit — "we restrict to (params, extras) and the CDP-style id-aware closure is not supported, sessionId via extras is sufficient" and confirm with CDP requirements; or (b) add an `encodeExtras: JsonRpcId => Maybe[Json.Value]` overload.

### C3. No hook for "MUST NOT emit progress after response sent" (MCP)

- **Source**: `research/MCP.md` line 228 — *"The receiver MUST NOT emit progress notifications after the response has been sent."*
- **DESIGN.md**: §8 describes outbound progress (engine-cleaned on response) but inbound handler-side `HandlerCtx.progress` (§5/§8) has no lifecycle gate. A handler that has already returned can in principle hold a `progressSink` closure and call it. The engine doesn't disable the sink after the response goes out.
- **Impact**: MCP spec violation possible; subtle bug surface for any consumer.
- **Fix**: After a request handler completes (or is cancelled), invalidate the `progressSink` closure so `ctx.progress(...)` after-completion is a no-op (or fails) instead of emitting on the wire.

### C4. Progress monotonicity (MCP)

- **Source**: `research/MCP.md` line 226 — *"`progress` MUST be monotonically increasing"*.
- **DESIGN.md**: §8 carries progress as opaque `Json.Value`. No validation of monotonicity for outbound or inbound. No documentation that this is the policy author's responsibility.
- **Impact**: kyo-mcp handlers emitting non-monotonic progress will silently violate spec. Test coverage cannot exercise this since the engine doesn't enforce it.
- **Fix**: Either add a per-policy validator hook (`ProgressPolicy.validate: (prev, next) => Boolean`) or document explicitly in §8 / §20 invariants that monotonicity is consumer-enforced.

### C5. Test-coverage matrix from the prompt is incompletely mapped

- **Source**: `kyo-jsonrpc-PROMPT.md` §8 "Test coverage" lists 11 categories with specific sub-tests.
- **DESIGN.md**: §18 phases mention tests but several prompt-required cases are absent from the phase plan:
  - "A response with both `result` and `error` should be rejected" (prompt §8 Wire types) — NOT in phase 1's stated tests.
  - "`Maybe[JsonRpcId]` Absent → no `id` key in JSON" (prompt §8 Wire types) — NOT in phase 1.
  - "Notification handler called: NO response emitted (even on failure). Assert by counting frames" (prompt §8 Method dispatch) — NOT in phase 2 stated tests.
  - "Closing the transport mid-call: outstanding `call`s complete with `Abort.fail(Closed)`" (prompt §8 Lifecycle) — NOT in phase 4.
  - "A sends notification to B; B receives it; no reply on the wire" (prompt §8 Bidirectional) — NOT in phase 4.
- **Impact**: The phased plan undercounts what the prompt demands. Implementer will either invent the missing tests ad-hoc or skip them.
- **Fix**: Annotate §18 to enumerate each of the prompt's 11 test categories and which phase owns it.

---

## Important gaps (should fix; design is incomplete without them)

### I1. `_meta` is treated as progressToken-only

- **Source**: `research/MCP.md` lines 108–112 — *"`_meta` on additional interface types (added in 2025-06-18): general metadata escape hatch. Reserved key-prefix format ... for MCP-defined keys; otherwise free."*
- **DESIGN.md**: §3 + §8 only handle `_meta.progressToken`. General `_meta` passthrough on inbound/outbound is not addressed. Consumers cannot read or write arbitrary `_meta` keys without re-parsing params.
- **Fix**: Document that handlers access raw params via the `JsonRpcMethod` decode step, and that `_meta` other than progressToken is the consumer's responsibility, OR widen the `HandlerCtx` to surface decoded `_meta` distinctly.

### I2. Timeout reset on progress notification (MCP)

- **Source**: `research/MCP.md` line 188 — *"Implementations MAY reset the timeout on receiving a related progress notification but MUST enforce a maximum."*
- **DESIGN.md**: §7 timeout fires the cancellation policy. No mention of resetting the timeout when inbound progress arrives for that request. No "maximum cap" knob beyond `requestTimeout`.
- **Fix**: Add a `Config.progressResetsTimeout: Boolean = false` knob and a `Config.maxTimeout: Maybe[Duration]` cap, or document explicitly that this MAY-clause is consumer-implemented.

### I3. `awaitDrain` semantics are not specified

- **Source**: `research/CDP.md` line 263 — *"Surface `awaitDrain` as a public API. Graceful shutdown of an RPC client universally needs 'wait until pending requests resolve, then close.'"*
- **DESIGN.md**: §6 lists `def awaitDrain(using Frame): Unit < Async`. No specification of what it waits for (pending outbound only? plus pending inbound? plus the writer channel? plus progress streams?). No interaction with new outbound calls during drain (does it gate further `call`s?).
- **Fix**: Document the wait set and whether `awaitDrain` blocks new outbound. Without this, two implementations could disagree.

### I4. ContentModified / ServerCancelled / RequestFailed: emission path

- **Source**: `research/LSP.md` lines 304–305, 213–216 — these codes are LSP-spec'd handler outcomes.
- **DESIGN.md**: §15 promotes the constants. §17 says "emitted by handler via `Abort.fail`". No example or test of a handler aborting with `JsonRpcError.ContentModified`, no documentation that the engine forwards the typed error code unchanged.
- **Fix**: Make the engine's "handler `Abort.fail(JsonRpcError(...)) → wire error response unchanged" path explicit in §6 and add a test row.

### I5. Event whitelist semantics differ from `UnknownMethodPolicy`

- **Source**: `research/CDP.md` lines 109–118 — CDP's whitelist drops *known* method names the user hasn't opted into ("opt-in firehose suppression"; default-drop on unsubscribed events).
- **DESIGN.md**: §16.3 says "kyo-browser registers only desired events; rest drop via `UnknownMethodPolicy.strict`". But `UnknownMethodPolicy` triggers only when the method is *unregistered*, which conflates "I haven't written a handler" with "I deliberately ignore this event flavor". CDP today wants opt-in subscription, not "did anyone register a handler".
- **Fix**: Confirm whether `UnknownMethodPolicy.strict` (drop) is genuinely equivalent (an unregistered method is functionally not-subscribed). If yes, add a one-line note clarifying. If no, add a `subscribed: Set[String]` whitelist field on the consumer-supplied dispatcher.

### I6. `Sync.Unsafe.defer` in §6.1 conflicts with CLAUDE.md

- **Source**: `kyo-jsonrpc-PROMPT.md` line 66 — *"`AllowUnsafe` is forbidden for new code. Use safe Kyo APIs only."*
- **DESIGN.md**: §6.1 says "all set via `Sync.Unsafe.defer`". `Sync.Unsafe` is the unsafe namespace; depending on read, this may run afoul of the project rule (the rule covers `AllowUnsafe` specifically, but the `.Unsafe.` path is the same family). The Promise.Unsafe / Channel.Unsafe uses in §6.6 are similar.
- **Fix**: Either confirm that `Sync.Unsafe.defer` / `Promise.Unsafe.completeDiscard` / `Channel.Unsafe.offer` are the project-approved "engine-internal Unsafe" path (similar to kyo-sql's documented exception), and document the per-site `// Unsafe:` rationale per the kyo-sql precedent, or replace with safe APIs.

### I7. Public-API target deviation needs explicit phase 1 list

- **Source**: `kyo-jsonrpc-PROMPT.md` line 267 — exactly 7 public types.
- **DESIGN.md**: §15 enumerates 17 types and §17 justifies. But the prompt's validation step #7 will fail unless the user has reviewed and accepted the new list.
- **Fix**: Promote §17's deviation list to a "REQUIRES USER APPROVAL" section so this isn't discovered post-implementation.

### I8. `derives Schema, CanEqual` missing from `JsonRpcEnvelope`

- **Source**: `kyo-jsonrpc-PROMPT.md` line 60 — *"`derives Schema, CanEqual` on every wire-type case class."*
- **DESIGN.md**: §3 declares `enum JsonRpcEnvelope derives CanEqual` only. No `derives Schema`. The intent (Schema lives on the underlying `Json.Value` and the codec handles shape) is defensible but not stated. Tests will likely need `Schema[JsonRpcEnvelope]` for round-trip assertions.
- **Fix**: Document the deliberate omission or add `derives Schema`.

### I9. Schema-derivation bugs (HANDOFF-mcp-wire-interop.md)

- **Source**: `research/MCP.md` lines 327–328, 646 — three known schema-derivation bugs (`Structure.Value`, `Json.JsonSchema`, `JsonRpcId`) that the MCP migration calls "blockers for MCP interop with real clients ... must be addressed at schema level so we stop hand-writing `Schema[T]`".
- **DESIGN.md**: §15 mentions "hand-written flat `Schema[JsonRpcId]`". The other two derivation bugs are silent. The design assumes they're solved upstream but doesn't say so.
- **Fix**: Note in §18 phase 1 (or §20 invariants) which schema fixes are prerequisites and which are workarounds inside kyo-jsonrpc.

### I10. Build / module setup not described

- **Source**: `kyo-jsonrpc-PROMPT.md` §"Build & module setup" — crossProject JVM/JS/Native, dependencies on kyo-prelude / kyo-core / kyo-schema, `dependsOn` plain.
- **DESIGN.md**: No section on build.sbt entries, source roots, or dependency wiring. Phase 10 says "Cross-platform sweep" but assumes the project is already wired.
- **Fix**: Add a §0 or phase-0 covering `build.sbt` additions before phase 1.

### I11. `exit` after `shutdown` — transport tolerates local stream close

- **Source**: `research/LSP.md` lines 350–352 — *"the transport must therefore tolerate the local end closing the stream after writing `exit`'s response/notification."*
- **DESIGN.md**: §6.4 finalizer order is described, but the LSP-specific "send `exit` then immediately close" race is not called out. If the writer fiber poison happens before `exit` flushes, the consumer loses the notification.
- **Fix**: Add a "drain writer before transport close" step or document that `awaitDrain` is responsible.

### I12. Stdio "MUST NOT contain embedded newlines" (MCP)

- **Source**: `research/MCP.md` line 17 — *"Messages MUST NOT contain embedded newlines."*
- **DESIGN.md**: Transport adapter lives outside the module; not addressed. But the engine emits JSON via codec — if Json.Value can serialise with newlines (pretty-printing toggles), kyo-mcp could violate spec.
- **Fix**: Document in §3 that codecs MUST emit single-line JSON for line-delimited transports.

---

## Minor gaps (defer-able)

### M1. OAuth (MCP 2025-06-18)
- **Source**: `research/MCP.md` line 537 — MCP servers are OAuth Resource Servers.
- **DESIGN.md**: Silent. Implicitly out of scope (transport layer / kyo-mcp).
- Acceptable to defer; note in §21.

### M2. `$/setTrace`
- **Source**: `research/LSP.md` line 142 — both-direction notification.
- **DESIGN.md**: Consumer concern. OK to defer.

### M3. Ping handling
- **Source**: `research/MCP.md` line 124, 183 — bidirectional liveness.
- **DESIGN.md**: Consumer concern, but the engine could offer a ping helper. Defer.

### M4. `scalafmtCheckAll` in validation
- **Source**: prompt line 266 — required pre-commit check.
- **DESIGN.md**: Phase 10 says "cross-platform sweep" but doesn't list scalafmt.
- One-line fix.

### M5. SSE event-id resumability cursor surfacing
- **Source**: `research/MCP.md` R14 — engine must allow transport to "assign + persist a cursor".
- **DESIGN.md**: §13 says transport-only. The engine doesn't expose a cursor hook to the transport; the transport would tag SSE events independently. Probably fine but worth a sentence.

### M6. Body charset metadata exposure to upper layer
- **Source**: `research/LSP.md` line 65 — *"surface the parsed Content-Type to the upper layer or do the check itself"*.
- **DESIGN.md**: §14 punts to kyo-lsp. Probably fine.

### M7. Capability snapshot threading
- **Source**: `research/MCP.md` R19 — capability negotiation results threaded into the session/peer.
- **DESIGN.md**: Pure kyo-mcp concern. OK.

### M8. Partial-message timeout (LSP)
- **Source**: `research/LSP.md` line 110 — vscode's 10s `partialMessageTimeout` for mid-body EOF.
- **DESIGN.md**: Transport-layer; defer to kyo-lsp.

---

## Coverage matrix

| Source | Requirement | DESIGN.md section |
|---|---|---|
| MCP R1 | Envelope types | §3, §15 |
| MCP R2 | Maybe for optionals | §3, §15 |
| MCP R3 | Notification = id Absent | §3 (JsonRpcEnvelope.Notification) |
| MCP R4 | Standard error codes + factories | §15 |
| MCP R5 | Typed method builder | §5 |
| MCP R6 | Bidirectional engine | §6 |
| MCP R7 | Pluggable framing | §4 |
| MCP R8 | Pending-request correlation | §6.1 |
| MCP R9 | Cancellation primitive | §7 |
| MCP R10 | Progress primitive | §8 |
| MCP R11 | Frame propagation | §5, §6 (using Frame) |
| MCP R12 | Safety defaults (no AllowUnsafe) | §6.1 — **see I6** |
| MCP R13 | Streamable HTTP adapter | §13 (kyo-mcp) |
| MCP R14 | SSE event-id resumability | §13 (kyo-mcp) — **see M5** |
| MCP R15 | Mcp-Session-Id | §13 (kyo-mcp) |
| MCP R16 | MCP-Protocol-Version | §13 (kyo-mcp) |
| MCP R17 | `_meta.progressToken` | §8 ProgressPolicy.mcp |
| MCP R18 | Server-initiated requests on GET SSE | §13 + §6 |
| MCP R19 | Capability + lifecycle | §12 MessageGate |
| MCP R20 | All standard MCP methods | (kyo-mcp) |
| MCP R21 | No batching | §17, §21 |
| MCP R22-R26 | Do-not-do list | §21 |
| MCP body | initialize MUST NOT be cancelled | **MISSING — C1** |
| MCP body | Monotonic progress | **MISSING — C4** |
| MCP body | No progress after response | **MISSING — C3** |
| MCP body | Stdio no embedded newlines | **MISSING — I12** |
| MCP body | Timeout reset on progress | **MISSING — I2** |
| MCP body | General `_meta` passthrough | **MISSING — I1** |
| MCP body | Sender ignores reply after cancel | §7 ("drop silently") |
| MCP body | Timeout SHOULD emit notifications/cancelled | §7 |
| MCP body | Schema derivation bugs | **MISSING — I9** |
| LSP DI1 | Two-layer transport | §4 + §14 |
| LSP DI2 | Symmetric Endpoint | §6 |
| LSP DI3 | Per-direction ids | §10 |
| LSP DI4 | First-class cancellation | §7 |
| LSP DI5 | First-class progress | §8 |
| LSP DI6 | Dispatch policy is data | §9 |
| LSP DI7 | Header framing per-message | §14 |
| LSP DI8 | Error codes open | §15 |
| LSP body | Late reply after cancel | §7 |
| LSP body | $/-prefix carve-out | §9 |
| LSP body | Content-Length parsing | §14 (kyo-lsp) |
| LSP body | Charset rejection | §14 (kyo-lsp) — **see M6** |
| LSP body | Header case-insensitivity | §14 (kyo-lsp) |
| LSP body | Header size cap | §14 (kyo-lsp) |
| LSP body | Lone `\n` tolerance | §14 (kyo-lsp) |
| LSP body | partialResult empty final | §8 callPartialResults |
| LSP body | workDoneProgress/create | §8 subscribeProgress |
| LSP body | ContentModified / RequestFailed emission | **MISSING detail — I4** |
| LSP body | ServerCancelled (-32802) dual | §15 — **see I4** |
| LSP body | exit-after-shutdown | **MISSING — I11** |
| LSP body | $/setTrace | (consumer) — M2 |
| LSP body | partialMessageTimeout | (kyo-lsp) — M8 |
| CDP DI1 | Wrap Exchange | §2 |
| CDP DI2 | Parametric envelope schema | §3 JsonRpcCodec |
| CDP DI3 | Req = Id => Wire encoder | **PARTIAL — C2** |
| CDP DI4 | cap + timeout + drain | §6, §11 — **see I3** |
| CDP DI5 | sendNotification bypasses pending | §6 `notify` |
| CDP DI6 | Errors decoded by caller | §6 |
| CDP DI7 | Optional standard error helper | §15 |
| CDP DI8 | Sync-only decode rule | §6.6 |
| CDP DI9 | No sessionId in engine | §3 extras |
| CDP DI10 | Surface awaitDrain | §6 — **see I3** |
| CDP body | Event whitelist | §16.3 — **see I5** |
| CDP body | Negative-id fire-and-forget | §6 `notify` |
| CDP body | Dialog drainer | (consumer) |
| CDP body | maxInFlight=8 JS/Native | §11 |
| Prompt | Wire types | §15 |
| Prompt | JsonRpcMethod | §5 |
| Prompt | Transport | §4 |
| Prompt | Bidirectional endpoint | §6 |
| Prompt | Cancellation | §7 |
| Prompt | Progress | §8 |
| Prompt | Configuration | §6 Config |
| Prompt | `JsonRpcMethod.cancelRequest` factory | §17 deviation (policy-driven) |
| Prompt | `endpoint.sendProgress(token, value)` | §17 deviation (HandlerCtx.progress) |
| Prompt | `endpoint.onProgress(token)` | §6 subscribeProgress + callWithProgress |
| Prompt | Batches decision | §17 (hard out of scope) |
| Prompt | Public API 7 types | §15 (17 types) — **see I7** |
| Prompt | Test: round-trip every envelope shape | §18 phase 1 |
| Prompt | Test: JsonRpcId.Num/Str bare encoding | §18 phase 1 |
| Prompt | Test: response with both result+error rejected | **MISSING — C5** |
| Prompt | Test: Maybe[JsonRpcId] Absent → no `id` key | **MISSING — C5** |
| Prompt | Test: Method dispatch (5 sub-cases) | §18 phase 2 (4/5) |
| Prompt | Test: Notification handler no response on failure | **MISSING — C5** |
| Prompt | Test: Bidirectional (3 sub-cases) | §18 phase 4 (2/3) |
| Prompt | Test: A notifies B, no reply on wire | **MISSING — C5** |
| Prompt | Test: Cancellation (2 sub-cases) | §18 phase 5 |
| Prompt | Test: Progress (1 sub-case) | §18 phase 6 |
| Prompt | Test: Scope cleanup | §18 phase 4 |
| Prompt | Test: Closing transport mid-call | **MISSING — C5** |
| Prompt | Test: Three-consumer scenarios | §18 phase 9 |
| Prompt | Module setup (build.sbt) | **MISSING — I10** |
| Prompt | scalafmtCheckAll | **MISSING — M4** |
| Prompt | `derives Schema, CanEqual` on wire types | §15 — **see I8** |
| Prompt | Custom Schema.init for JsonRpcId | §15 |
| Prompt | Frame parameter | §5, §6 |
| Prompt | No AllowUnsafe / Frame.internal | §6.1 — **see I6** |
| Prompt | sessionId decision | §3, §17 (extras + pluggable codec) |
