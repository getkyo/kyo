# 03a Open-question resolutions

Feature: kyo-browser jsonrpc-port
Inputs:
- `01-exploration.md` (RI-001..RI-008)
- `02-design.md` (Q-001..Q-008, §15 Open questions)
- `03-candidates.json` (one escalation: Q-002)

Pass: `flow-resolve-open --pass apply`
Findings dir: none (zero research items emitted; all questions either resolved in-design or routed to supervisor as value-underdetermined ratification)

## Summary

| Question | Type                   | Status                  | Resolution source                |
|----------|------------------------|-------------------------|----------------------------------|
| Q-001    | research-knowable      | RESOLVED (in-design)    | design §15 lines 1213-1217        |
| Q-002    | value-underdetermined  | RATIFIED (supervisor)   | option (b), per no-backcompat     |
| Q-003    | research-knowable      | RESOLVED (in-design)    | design §15 lines 1228-1233        |
| Q-004    | value-underdetermined  | DECIDED in-design       | design §15 lines 1235-1239        |
| Q-005    | value-underdetermined  | DECIDED in-design       | design §15 lines 1241-1250        |
| Q-006    | research-knowable      | RESOLVED (in-design)    | design §15 lines 1252-1260        |
| Q-007    | research-knowable      | RESOLVED (in-design)    | design §15 lines 1262-1269        |
| Q-008    | research-knowable      | RESOLVED (in-design)    | design §15 lines 1271-1274        |

All eight questions are closed. The design is open-question-free and may proceed to `flow-invariants` and `flow-plan`.

## Per-question detail

### Q-001 — notification dispatch HandlerCtx.extras population

- **Type**: research-knowable
- **Question**: Does `JsonRpcEndpointImpl` populate `HandlerCtx.extras` from the decoded envelope's `extras` field before invoking a `JsonRpcMethod.notification` handler?
- **Answer**: Yes. `JsonRpcEndpointImpl.scala:911-916` constructs `new HandlerCtx(cancelledUnsafe.safe, Absent, env.extras, Absent)` when invoking a notification handler.
- **Citation**: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:911-916`; design §3 Background (lines 50-58); §8 Per-sessionId routing (lines 596-650).
- **Resolution location in design**: `02-design.md` lines 1213-1217.
- **Status**: RESOLVED in-design.

### Q-002 — WS-connect failure surfacing (probe vs adapter modification)

- **Type**: value-underdetermined
- **Question**: Should the kyo-browser port handle async WS-connect failures by (b) issuing a kyo-browser-side `Browser.getVersion` probe call inside `CdpBackend.initUnscoped` that recovers `Closed` to `BrowserSetupException`, rather than (a) modifying `JsonRpcHttpTransport.webSocket` to synchronously await ws-open before returning?
- **Design recommendation**: option (b), the kyo-browser-side probe.
- **Supervisor ratified answer**: option (b). RATIFIED.
- **Supervisor rationale**:
  1. `feedback_no_backcompat` decides: a just-shipped public API in `kyo-jsonrpc-http` is not perturbed for a single consumer's need. Threading a synchronous-connect promise through `JsonRpcHttpTransport.webSocket` would mutate a cross-module public API for one specific caller; that is exactly the backcompat-hedging the principle prohibits.
  2. The probe is at the right typing layer. `BrowserSetupException` belongs to kyo-browser; surfacing the failure at the kyo-browser init scope keeps the typing local to the layer that owns it.
  3. The cost is acceptable: `Browser.getVersion` (or any trivial CDP RPC) round-trips in roughly 5-15ms once against a live Chrome that has already written `DevToolsActivePort`. The probe also proves liveness more strongly than the current `connectReady` gate (TCP+WS handshake PLUS one CDP round-trip).
- **Rejected alternative**: option (a) — thread a synchronous connect-ready promise through `JsonRpcHttpTransport.webSocket`. Rejected because it perturbs the kyo-jsonrpc-http public API for a single consumer and conflicts with the existing async-open design.
- **Resolution location in design**: `02-design.md` §9.A (lines 663-718) for the design; §15 line 1219-1226 for the open-question carry-forward.
- **Status**: RATIFIED. No code change to `JsonRpcHttpTransport.webSocket`. `CdpBackend.initUnscoped` keeps the §9.A probe block.
- **User escalation record**: `./03b-user-escalations.md` entry 1.

### Q-003 — kyo-jsonrpc-http cross-platform availability

- **Type**: research-knowable
- **Question**: Is kyo-jsonrpc-http published cross-platform (JVM/JS/Native), and is the build-graph dep from kyo-browser to kyo-jsonrpc-http clean across all platforms?
- **Answer**: Yes. `build.sbt:742-755` declares kyo-jsonrpc-http as `crossProject(JSPlatform, JVMPlatform, NativePlatform)` with `CrossType.Pure`. Adding `.dependsOn(kyo-jsonrpc-http)` to kyo-browser (CrossType.Full) is graph-clean across all three platforms.
- **Citation**: `build.sbt:742-755` and `build.sbt:1162-1164`.
- **Resolution location in design**: `02-design.md` lines 1228-1233 and 1185-1191 (Risks).
- **Status**: RESOLVED in-design.

### Q-004 — retain `unixDomain` transport variant?

- **Type**: value-underdetermined
- **Question**: Should the port retain the JVM-only `unixDomain` transport variant?
- **Answer**: No. CDP is WebSocket-only today; Chrome's `--remote-debugging-pipe` mode is not in scope. kyo-browser's `CdpBackend.init` stays WS-only. The kyo-jsonrpc-http surface still exposes `unixDomain` for future users who want pipe-mode.
- **Citation**: design §11 Phase 04 (lines 1029-1044); §13 Risks (lines 1193-1202).
- **Resolution location in design**: `02-design.md` lines 1235-1239.
- **Status**: DECIDED in-design.

### Q-005 — per-test rewrite vs delete vs keep accounting

- **Type**: value-underdetermined
- **Question**: Per-test accounting for the 90+ wire-layer test cases (CdpClientLifecycleTest, CdpClientTest, CdpClientDecoderTest, CdpBackendTest, CdpParamsRoundTripTest, CdpTypesTest, CdpTypesSchemaFailureTest, CdpEvalDecoderTest, BrowserWireDecodeFailureTest).
- **Answer**: Decided in design §11 Phase 03 (lines 955-1027):
  - `CdpClientLifecycleTest` → rename to `CdpBackendLifecycleTest`; delete ~10 engine-duplicate cases, keep ~15.
  - `CdpClientTest` → DELETE (15 cases, engine duplicates).
  - `CdpClientDecoderTest` → DELETE (7 cases, engine-internal coverage).
  - `CdpBackendTest` → KEEP + rewrite-bodies (41 cases).
  - `CdpParamsRoundTripTest` → KEEP (15 cases).
  - `CdpTypesTest` → KEEP (5 cases).
  - `CdpTypesSchemaFailureTest` → KEEP (6 cases).
  - `CdpEvalDecoderTest` → KEEP (15 cases).
  - `BrowserWireDecodeFailureTest` → KEEP + rewrite-bodies (6 cases).
  - Net delta: -32 cases. The Phase 03 commit message MUST enumerate every deletion with its engine-side equivalent.
- **Citation**: design §11 Phase 03 lines 955-1027.
- **Resolution location in design**: `02-design.md` lines 1241-1250.
- **Status**: DECIDED in-design.

### Q-006 — error-type plumbing through `CdpBackend.send`

- **Type**: research-knowable
- **Question**: How does `CdpBackend.send` recover engine errors (`JsonRpcError`, `Closed`, `Timeout`) into kyo-browser exception types (`BrowserProtocolErrorException`, `BrowserConnectionLostException`, `BrowserDecodingException`)?
- **Answer**: Three `Abort.recover` branches at the `CdpBackend` boundary:
  - `Abort.recover[Closed]` → `BrowserConnectionLostException`
  - `Abort.recover[JsonRpcError]` → `BrowserProtocolErrorException`
  - `Abort.recover[Timeout]` → `BrowserConnectionLostException`
  Same three branches that `CdpClient.submit` performs today at `CdpClient.scala:93-104`.
- **Citation**: design §6 `CdpBackend.send` pseudocode (lines 550-573); `CdpClient.scala:93-104`.
- **Resolution location in design**: `02-design.md` lines 1252-1260.
- **Status**: RESOLVED in-design.

### Q-007 — `IdStrategy.SequentialInt` range vs negative-id sentinel space

- **Type**: research-knowable
- **Question**: What range does `IdStrategy.SequentialInt` allocate from, and does it collide with the negative-id sentinel space the dialog drainer uses?
- **Answer**: `SequentialInt` allocates positive Int ids (`kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala`). The dialog drainer uses negative Long ids via `endpoint.sendUnmatched(method, params, JsonRpcId.Num(negativeLong), extras)`, which bypasses the pending map and writes caller-supplied ids verbatim. Positive and negative spaces are disjoint for any realistic test duration.
- **Citation**: `kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala:6`; `kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala`; design §10 ("Negative-id sentinel") lines 858-867.
- **Resolution location in design**: `02-design.md` lines 1262-1269.
- **Status**: RESOLVED in-design.

### Q-008 — kyo-jsonrpc-http cross-platform build configuration

- **Type**: research-knowable
- **Question**: Is kyo-jsonrpc-http configured cross-platform in `build.sbt`, or is it JVM-only by default?
- **Answer**: Cross-platform. `build.sbt:743` declares `crossProject(JSPlatform, JVMPlatform, NativePlatform)` with `CrossType.Pure` at line 755. (Same factual answer as Q-003, verified against the build file directly rather than via the BACKPORT audit's claim.)
- **Citation**: `build.sbt:743` and `build.sbt:755`.
- **Resolution location in design**: `02-design.md` lines 1271-1274.
- **Status**: RESOLVED in-design.

## Closure

All eight open questions are closed. Q-002 carries the supervisor ratification recorded in `./03b-user-escalations.md`. The plan may proceed to `flow-invariants` and `flow-plan`.
