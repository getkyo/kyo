# 01 Exploration: kyo-jsonrpc protocol-coverage fixes (17 items)

Task type: refactor (with new-feature seams)
Primary module: kyo-jsonrpc
Scope: kyo-jsonrpc/shared/src/{main,test}/scala/kyo/**

## Task statement

> Implement all 17 module-level fixes identified in the kyo-jsonrpc protocol-coverage audits to make kyo-jsonrpc a clean engine layer that future kyo-mcp / kyo-cdp / kyo-lsp consumer modules can sit on top of without hand-rolling JSON-RPC primitives.

## Module map

Public surface (kyo-jsonrpc/shared/src/main/scala/kyo/):
- `CancellationPolicy.scala` (51 LOC): policy record with cancel method name, encoder, suppress-late-reply flag, protected methods, plus `lsp` / `mcp` presets.
- `ExtrasEncoder.scala` (17 LOC): opaque `JsonRpcId => Maybe[Structure.Value] < Sync` for per-request envelope-extras stamping; `empty` / `const(extras)` factories.
- `HandlerCtx.scala` (34 LOC): request handler receiver carrying `cancelled` promise, `requestId`, `extras`, and `progressSink`; `forTest` escape hatch.
- `IdStrategy.scala` (8 LOC): `SequentialLong | SequentialInt | Custom(next: () => JsonRpcId < Sync)`.
- `JsonRpcCodec.scala` (17 LOC): codec trait + `Strict2_0` and `Cdp` presets surfaced from `internal.JsonRpcCodecImpl`.
- `JsonRpcEndpoint.scala` (81 LOC): primary user-facing endpoint, the `Config` case class (defaults at lines 62-72), `init(transport, methods, config)` factory; methods `call` / `notify` / `callWithProgress` / `callPartialResults` / `subscribeProgress` / `unsubscribeProgress` / `cancel` / `awaitDrain` / `close`.
- `JsonRpcEnvelope.scala` (26 LOC): wire-shape ADT `Request | Notification | Response | Malformed(reason, raw)`.
- `JsonRpcError.scala` (41 LOC): code + message + data record; JSON-RPC 2.0 and LSP-extension named constants; factory helpers.
- `JsonRpcId.scala` (29 LOC): `Num(Long) | Str(String)` enum with hand-written flat `Schema` rejecting `null`.
- `JsonRpcMethod.scala` (121 LOC): sealed builder ADT (`Kind.Request | Notification`), `apply` overloads with/without `HandlerCtx`, `notification` factory; the private `handle(params, ctx)` method drives engine-side dispatch.
- `JsonRpcResponse.scala` (24 LOC): wire-level success/failure factories (used by JSON-encoded send paths).
- `JsonRpcTransport.scala` (32 LOC): envelope-level transport trait `send` / `incoming` / `close`; `inMemory(capacity)` factory for tests.
- `MessageGate.scala` (13 LOC): stateless per-envelope gate trait, `Decision = Allow | Reject(error) | Drop`.
- `ProgressPolicy.scala` (63 LOC): policy record with `progressMethod`, `extractInboundToken` / `extractRequestToken` / `stampOutboundToken` / `encodeProgressParams` / `extractProgressValue` hooks, `enforceMonotonic` flag; `lsp` and `mcp` presets.
- `UnknownMethodPolicy.scala` (35 LOC): `onUnknownRequest` / `onUnknownNotification` / `dollarPrefixOverride`; `minimal` / `lsp` / `strict` presets.

Internal (kyo-jsonrpc/shared/src/main/scala/kyo/internal/):
- `CancellationEngine.scala` (131 LOC): inbound-cancel CAS state machine plus outbound cancel/timeout helpers. Owns the hard-coded `LspCancelParams` / `McpCancelParams` decoder branch at lines 19-27.
- `IdStrategyEngine.scala` (22 LOC): per-strategy `() => JsonRpcId < Sync` factory.
- `InMemoryTransport.scala` (19 LOC): the only shipped `JsonRpcTransport` (back-to-back `Channel[JsonRpcEnvelope]`s).
- `JsonRpcCodecImpl.scala` (228 LOC): `Strict2_0` and `Cdp` implementations; the Strict2_0 decoder emits `Malformed("unclassifiable envelope", raw)` (line 104) without an id slot; the Cdp decoder substitutes `JsonRpcError.InvalidRequest` for bad error payloads (line 216).
- `JsonRpcEndpointImpl.scala` (1259 LOC): the engine. Contains the Exchange wiring, writer fiber, decode callback, finalizer, and progress/cancellation orchestration. Hot lines for this campaign: progress-token allocation at `:368` and `:451` (no `putIfAbsent`); progress-stream registration at `:391` and `:465`; cancel-on-timeout at `:135-142` / `:283-294` / `:316-324`; `close` at `:638-708` (single-phase, no grace period); decode callback at `:830`-`:1186`.
- `JsonRpcRequest.scala` (12 LOC): internal `private[kyo]` wire shape used by HTTP-style adapters.
- `ProgressEngine.scala` (84 LOC): per-invocation progress-sink closure builder.
- `RateLimitEngine.scala` (27 LOC): `maxInFlightGuard(meter)` semaphore wrap.

Test (kyo-jsonrpc/shared/src/test/scala/kyo/):
- `JsonRpcTestBase.scala`: `AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest` base.
- Topic-based suites: `CancellationPolicyTest`, `ExtrasEncoderTest`, `HandlerCtxTest`, `IdStrategyTest`, `JsonRpcCodecTest`, `JsonRpcEndpointTest`, `JsonRpcEnvelopeTest`, `JsonRpcErrorTest`, `JsonRpcIdTest`, `JsonRpcMethodTest`, `JsonRpcResponseTest`, `JsonRpcTransportTest`, `MessageGateTest`, `ProgressPolicyTest`, `UnknownMethodPolicyTest`.
- `scenario/`: integration-style suites `BidiTest`, `HttpStyleTest`, `MaxInFlightTest`, `WsStyleTest` (note: `WsStyleTest.scala:97` and `:161` already override `cancellation = Absent` for CDP-style flows, anchoring MUST-FIX #1).

## Relevant APIs (verbatim signatures)

- `final case class Config(codec: JsonRpcCodec = JsonRpcCodec.Strict2_0, cancellation: Maybe[CancellationPolicy] = Present(CancellationPolicy.lsp), progress: Maybe[ProgressPolicy] = Absent, unknownMethod: UnknownMethodPolicy = UnknownMethodPolicy.minimal, gate: Maybe[MessageGate] = Absent, maxInFlight: Maybe[Int] = Absent, requestTimeout: Duration = Duration.Infinity, idStrategy: IdStrategy = IdStrategy.SequentialLong, progressResetsTimeout: Boolean = false)` at `JsonRpcEndpoint.scala:62-72`.
- `def init(transport: JsonRpcTransport, methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]], config: Config = Config())(using Frame): JsonRpcEndpoint < (Sync & Async & Scope)` at `JsonRpcEndpoint.scala:74-79`.
- `def close(using Frame): Unit < Async` at `JsonRpcEndpoint.scala:48`.
- `def notify[In: Schema](method: String, params: In, extras: ExtrasEncoder = ExtrasEncoder.empty)(using Frame): Unit < (Async & Abort[Closed])` at `JsonRpcEndpoint.scala:16-21`.
- `trait JsonRpcTransport { def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]); def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]]; def close(using Frame): Unit < Async }` at `JsonRpcTransport.scala:6-10`.
- `def inMemory(capacity: Int)(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync` at `JsonRpcTransport.scala:18`.
- `final case class CancellationPolicy(cancelMethod: String, encodeParams: CancellationPolicy.ParamsEncoder, expectReplyForCancelledRequest: Boolean, cancelledError: Maybe[JsonRpcError], protectedMethods: Set[String])` at `CancellationPolicy.scala:10-16`; `type ParamsEncoder = (JsonRpcId, Maybe[String]) => Frame ?=> Structure.Value < Sync` at `:19`.
- `final case class ProgressPolicy(progressMethod: String, extractInboundToken: Structure.Value => (Maybe[Structure.Value] < Sync), extractRequestToken: Structure.Value => (Maybe[Structure.Value] < Sync), stampOutboundToken: (Structure.Value, Structure.Value) => (Structure.Value < Sync), encodeProgressParams: (Structure.Value, Structure.Value) => (Structure.Value < Sync), extractProgressValue: Structure.Value => (Maybe[Structure.Value] < Sync), enforceMonotonic: Boolean)` at `ProgressPolicy.scala:10-18`.
- `enum JsonRpcEnvelope { case Request(id, method, params, extras); case Notification(method, params, extras); case Response(id, result, error, extras); case Malformed(reason: String, raw: Structure.Value) }` at `JsonRpcEnvelope.scala:7-26`.
- `private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using Frame): Structure.Value < (Async & Abort[JsonRpcError])` at `JsonRpcMethod.scala:22`.

## Conventions in this module

- Smart-constructor pattern (`Hub.scala:22`-style annotation, e.g. `JsonRpcEndpoint.scala:7`, `HandlerCtx.scala:14`, `UnknownMethodPolicy.scala:5`): `final class X private[kyo]` plus a companion `init` / preset that returns the only public construction path.
- `// flow-allow: <rationale>` annotation on every Unsafe call site, every PUBLIC type, every `scala.Option` interop arm. Examples: `JsonRpcEndpoint.scala:1`, `CancellationEngine.scala:5`, `JsonRpcCodecImpl.scala:63-70`.
- Schema derivation through `derives Schema, CanEqual` on user-shape case classes (`JsonRpcError.scala:11`, `CancellationPolicy.scala:21-22`). Wire-id custom `Schema` at `JsonRpcId.scala:16-28`.
- Effect-row style: `Sync` for pure side-effects, `Async & Abort[JsonRpcError | Closed]` for engine calls, `Async & Scope` for `init`. `Sync.defer` / `Sync.Unsafe.defer` bridges into safe contexts inside the engine.
- `Maybe[T] = Absent` for optional fields (`JsonRpcEndpoint.scala:62-72` defaults).
- `Seq` for `methods` parameter at `init` (`JsonRpcEndpoint.scala:76`), `Chunk` for internal accumulators (e.g. `JsonRpcCodecImpl.scala:16` base record-field chunk).
- `ConcurrentHashMap` for shared mutable maps with annotation `// flow-allow: ConcurrentHashMap follows kyo.Exchange precedent` (`JsonRpcEndpointImpl.scala:5`).
- Test infrastructure: `JsonRpcTestBase` (AsyncFreeSpec + NonImplicitAssertions + BaseKyoCoreTest) at `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTestBase.scala:9`. Topic-based one-file-per-source convention; `scenario/` for cross-cutting end-to-end suites.
- Internal subpackage: lower-case `internal` containing all engine wiring; `JsonRpcCodec.scala:14-16` shows the canonical pattern (public alias to internal singleton).

## Prior art for the task type

- `JsonRpcTransport.webSocket` adapter shape: `HttpClient.webSocket(url, headers, config)(f)` at `kyo-http/shared/src/main/scala/kyo/HttpClient.scala:737-758`. Surface for a JSON-RPC adapter: a single duplex `HttpWebSocket` produces a `JsonRpcTransport` whose `send` invokes `ws.put(Text(...))` and whose `incoming` consumes `ws.stream` plus a relay fiber. The live recipe is at `concurrent-imagining-stroustrup/kyo-browser/.../CdpClient.scala:230-266` (relay fiber, `connectReady` promise, text-only filter, warn-on-binary).
- Byte-stream seam under envelope codec: kyo-http itself splits at the bytes/decoded layer. Pattern lift: introduce a `JsonRpcWireTransport` (byte-stream-shaped) plus a codec adapter that lifts `JsonRpcWireTransport[Chunk[Byte]] -> JsonRpcTransport[JsonRpcEnvelope]`. Cited in `LSP-coverage-audit.md` SHOULD-FIX #1 referencing `LSP.md §1` and `§8(a)`.
- Two-phase `close(gracePeriod)`: `HttpClient.close(gracePeriod: Duration)` at `kyo-http/shared/src/main/scala/kyo/HttpClient.scala:86-87`; `def closeNow(using Frame): Unit < Async = close(Duration.Zero)` at `:93`. Identical pattern on `HttpServer.scala:52-59`. Live recipe in the kyo-browser CDP client at `concurrent-imagining-stroustrup/kyo-browser/.../CdpClient.scala:139-158` (`closeOrderly` wrapped in `Async.timeout`, fallback to `closeNow`).
- Stdio transport: `Console.readLine` / `Console.printLine` at `kyo-core/shared/src/main/scala/kyo/Console.scala:25, :53, :71+`. Hand-rolled MCP precedent in `luminous-toasting-graham/kyo-ai-harness/.../HarnessApp.scala:240-303` (parseWirePayload + readLine loop) is the direct migration source.
- Unix-Domain Socket transport: `luminous-toasting-graham/kyo-ai-harness/jvm/src/main/scala/kyo/ai/harness/internal/HookSocketServer.scala:31-54` shows the `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` + bind + `Scope.ensure` cleanup + accept-loop fiber recipe. JVM-only (no JS/Native support for UDS).
- Smart-constructor + preset library: `CancellationPolicy.lsp` / `.mcp` at `CancellationPolicy.scala:36-50`; `UnknownMethodPolicy.minimal` / `.lsp` / `.strict` at `UnknownMethodPolicy.scala:18-34`; `ProgressPolicy.lsp` / `.mcp` at `ProgressPolicy.scala:38-62`. New `Config.cdp` follows the same preset-as-companion-val pattern.
- Fire-and-forget with explicit id: `notify` at `JsonRpcEndpoint.scala:16-21` is the no-id form; the kyo-browser dialog drainer at `concurrent-imagining-stroustrup/kyo-browser/.../CdpClient.scala:324-340` uses a sentinel negative id via `outbound.put(wire)` directly. Direct prior art for `sendUnmatched`.

## Per-item readiness (17 items)

### MUST-FIX engine bugs

#### Item 1: `Config.cdp` preset (audit: `CDP-coverage-audit.md` MUST-FIX, `COVERAGE-VERIFICATION.md` §4.1)

(a) Current source state: `JsonRpcEndpoint.scala:64` defaults `cancellation = Present(CancellationPolicy.lsp)`. `WsStyleTest.scala:97` and `:161` manually override to `Absent` to model CDP correctly.
(b) Gap: a naive CDP user with `codec = JsonRpcCodec.Cdp` silently emits `$/cancelRequest` notifications onto the CDP wire on every timeout (via `CancellationEngine.handleTimeout` at `JsonRpcEndpointImpl.scala:135-142` / `:283-294` / `:316-324`).
(c) Design instinct: add `JsonRpcEndpoint.Config.cdp: Config` companion-val with `codec = JsonRpcCodec.Cdp, cancellation = Absent, idStrategy = IdStrategy.SequentialInt, unknownMethod = UnknownMethodPolicy.minimal, maxInFlight = Present(8)`. Follows the `CancellationPolicy.lsp` / `.mcp` preset shape at `CancellationPolicy.scala:36-50`. Module-level: companion-object addition only; no shape change to `Config` itself.
(d) Test impact: new positive cases verifying the preset's field values; the `WsStyleTest.scala` overrides at `:97` and `:161` can be replaced with `Config.cdp.copy(...)` to demonstrate the migration, but existing assertions stand.

#### Item 2: LSP `partialResultToken` stamping (audit: `LSP-coverage-audit.md` row 15 / MUST-FIX #1, `COVERAGE-VERIFICATION.md` §4.2)

(a) Current source state: `ProgressPolicy.scala:41-42` `lsp` preset reads `field(p, "workDoneToken")` and stamps under key `"workDoneToken"` for every progress flow.
(b) Gap: `callPartialResults` (`JsonRpcEndpoint.scala:30-35`, engine path at `JsonRpcEndpointImpl.scala:462`) stamps the wrong field; LSP peers route to work-done progress instead of partial-result progress.
(c) Design instinct: extend `ProgressPolicy` with a partial-result token pair (`extractRequestPartialResultToken: Structure.Value => Maybe[Structure.Value] < Sync` and `stampOutboundPartialResultToken: (Structure.Value, Structure.Value) => Structure.Value < Sync`); MCP retains the same `_meta.progressToken` path for both. `JsonRpcEndpointImpl.callPartialResults` selects the partial-result variant; `callWithProgress` keeps `stampOutboundToken`. The mcp preset's partial-result hooks alias the existing pair.
(d) Test impact: `ProgressPolicyTest` adds field-stamp assertions for both kinds; `scenario/HttpStyleTest` or a new partial-result LSP scenario verifies wire shape `params.partialResultToken` is present and `params.workDoneToken` is absent on `callPartialResults` issuance.

#### Item 3: Progress token uniqueness MUST (audit: `MCP-coverage-audit.md` row 19, severity raised by `COVERAGE-VERIFICATION.md` §4.3)

(a) Current source state: `JsonRpcEndpointImpl.scala:368` (callWithProgress) and `:451` (callPartialResults) allocate token via `Random.live.unsafe.nextStringAlphanumeric(32)` then `discard(progressStreams.put(tokenVal, progChan))` at `:391` and `:465` without collision check.
(b) Gap: live MCP spec says "Progress tokens MUST be unique across all active requests" (verified at `modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress`). 32-char alphanumeric collision is astronomically rare but the spec is MUST, not SHOULD.
(c) Design instinct: extract a `private def allocateProgressToken(): Structure.Value < Sync` helper that loops generate -> `progressStreams.putIfAbsent(token, channel)` until success (or fails after N attempts with `JsonRpcError.internalError("progress token exhaustion")` for defensive bound). Call from both `callWithProgress` and `callPartialResults`.
(d) Test impact: a new ProgressPolicyTest case seeds `progressStreams` with a fixed token, monkey-patches the random generator (or uses `IdStrategy.Custom` analog seam) to verify regeneration on collision. Since the engine uses `Random.live`, the test may instead inject a `Random` hook (cleaner) or assert that two concurrent `callWithProgress` from the same fiber pool never collide given the put-if-absent loop.

### SHOULD-FIX module-level seams

#### Item 4: `JsonRpcTransport.webSocket` adapter (audit: `CDP-coverage-audit.md` SHOULD-FIX #2, `CDP-vs-kyo-browser.md` BACKPORT #1)

(a) Current source state: `JsonRpcTransport.scala:12-32` companion ships only `inMemory(capacity)`.
(b) Gap: every WebSocket-based JSON-RPC user (CDP, MCP-streamable-HTTP-via-WS, anything sitting on `kyo-http`) re-implements the relay fiber. Live recipe at `concurrent-imagining-stroustrup/kyo-browser/.../CdpClient.scala:230-266`.
(c) Design instinct: add `JsonRpcTransport.webSocket(url: HttpUrl, headers: HttpHeaders = HttpHeaders.empty, codec: JsonRpcCodec = JsonRpcCodec.Strict2_0)(using Frame): JsonRpcTransport < (Async & Scope & Abort[HttpException])` that calls `HttpClient.webSocket(url, headers, ...)` and bridges text frames through the codec. Module placement: a NEW SUBMODULE `kyo-jsonrpc-http` (or `kyo-jsonrpc/jvm`+`/js`+`/native` mirror layout) is the steering choice (see Open observations Q-001) so kyo-jsonrpc core stays free of the kyo-http dep.
(d) Test impact: new `JsonRpcTransportWebSocketTest` in the appropriate subproject; existing `JsonRpcTransportTest` is unaffected.

#### Item 5: `JsonRpcTransport.stdio` adapter (audit: `MCP-vs-kyo-ai-harness.md` BACKPORT #1)

(a) Current source state: ABSENT. The harness hand-rolls at `luminous-toasting-graham/kyo-ai-harness/.../HarnessApp.scala:240-303` (Console.readLine, parseWirePayload, Console.printLine).
(b) Gap: every CLI-style MCP/LSP server hand-rolls the same readLine/printLine loop.
(c) Design instinct: add `JsonRpcTransport.stdio(codec: JsonRpcCodec = JsonRpcCodec.Strict2_0): JsonRpcTransport < (Async & Scope)` to `JsonRpcTransport.scala`. Uses `Console.readLine` for `incoming` stream (one envelope per line via `Json.decode[Structure.Value] -> codec.decode`), `Console.printLine` for `send`. Cross-platform: lives in `shared` since `Console` is on JVM/JS/Native (`kyo-core/.../Console.scala`).
(d) Test impact: new `JsonRpcTransportStdioTest` using `Console.let` or the `Console.Unsafe` test seam (lines 142-183 of Console.scala have an override hook).

#### Item 6: `JsonRpcTransport.unixDomain(sockPath)` adapter (audit: `MCP-vs-kyo-ai-harness.md` BACKPORT #2)

(a) Current source state: ABSENT. Live recipe at `luminous-toasting-graham/.../HookSocketServer.scala:31-54`.
(b) Gap: JVM-only consumers (kyo-ai-harness's hook bus, future kyo-mcp local-socket transport) hand-roll the UDS bind + accept loop.
(c) Design instinct: add `JsonRpcTransport.unixDomain(sockPath: Path, codec: JsonRpcCodec = JsonRpcCodec.Strict2_0)(using Frame): JsonRpcTransport < (Async & Scope)` to a NEW `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransport.scala` (JVM-only file, sibling to the shared file). `StandardProtocolFamily.UNIX` and `UnixDomainSocketAddress` are JVM-only. The implementation mirrors HookSocketServer: bind, `Scope.ensure` for cleanup + file delete, accept-loop fiber.
(d) Test impact: new JVM-only `JsonRpcTransportUnixDomainTest` (cannot live in `shared/`); uses a temp socket path under `Files.createTempDirectory`.

#### Item 7: Byte-stream seam UNDER envelope codec (audit: `LSP-coverage-audit.md` row 1 / SHOULD-FIX #1)

(a) Current source state: `JsonRpcTransport` is envelope-shaped at `JsonRpcTransport.scala:6-10`; there is no byte-level interface.
(b) Gap: LSP requires Content-Length framing (`Content-Length: N\r\n\r\n<N bytes>`). Every LSP host re-implements the framer.
(c) Design instinct: introduce `JsonRpcWireTransport` in `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcWireTransport.scala` with `def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed])` and `def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]`, plus `def close(using Frame): Unit < Async`. Add `JsonRpcTransport.fromWire(wire: JsonRpcWireTransport, framer: Framer)` that lifts the byte stream through a framer (`Framer.contentLength: Framer` and `Framer.lineDelimited: Framer` presets). Stdio (Item 5) then becomes `fromWire(stdioWire, Framer.lineDelimited)`; UDS (Item 6) defaults to `Framer.lineDelimited` but accepts an override.
(d) Test impact: new `JsonRpcWireTransportTest` + `FramerTest` (Content-Length and line-delimited framing, including header-malformed edge cases).

#### Item 8: Tolerant fallback id extraction on malformed responses (audit: `CDP-vs-kyo-browser.md` BACKPORT #2)

(a) Current source state: `JsonRpcCodecImpl.scala:104` (Strict2_0) and `:216` (Cdp) produce `JsonRpcEnvelope.Malformed(reason, raw)` without an id slot; pending caller's promise then hangs until `Async.timeout` fires. Live precedent: `concurrent-imagining-stroustrup/.../CdpClient.scala:519-528` does a `fallbackDecode` that re-decodes a `FallbackIdEnvelope` to recover the id.
(b) Gap: when a peer emits a malformed `error` payload (e.g. error as a string instead of an object), the awaiting caller hangs until timeout instead of failing eagerly with a decode error.
(c) Design instinct: extend `JsonRpcEnvelope.Malformed` to `Malformed(id: Maybe[JsonRpcId], reason: String, raw: Structure.Value)`. The codec's decoders attempt a best-effort `id` re-extraction before constructing the case. `JsonRpcEndpointImpl.decodeCallback` (currently `:1181-1182` returns `Exchange.Message.Skip` for Malformed) routes by id-presence: with id, fail the caller's pending promise eagerly with `JsonRpcError.invalidRequest("malformed response: " + reason)`; without id, retain current Skip semantic.
(d) Test impact: `JsonRpcCodecTest` adds cases verifying Malformed carries id when extractable. `JsonRpcEndpointTest` (or `scenario/HttpStyleTest`) adds a test that feeds a malformed `{"id": 7, "error": "stringy"}` and asserts the call fails fast instead of timing out.

#### Item 9: Two-phase `close(gracePeriod)` (audit: `CDP-vs-kyo-browser.md` BACKPORT #3)

(a) Current source state: `JsonRpcEndpoint.scala:48` `def close(using Frame): Unit < Async`; impl at `JsonRpcEndpointImpl.scala:638-708` (`finalizer`) is single-phase: close writer channel, interrupt writer fiber, close transport, fail caller registry, etc. No drain wait.
(b) Gap: every long-running RPC client needs "drain for up to N, then force". Live precedent: `concurrent-imagining-stroustrup/.../CdpClient.scala:139-158` and `HttpClient.scala:86-93`.
(c) Design instinct: add `def close(gracePeriod: Duration)(using Frame): Unit < Async` to `JsonRpcEndpoint`; keep the no-arg `close` as `close(Duration.Zero)` (matching `HttpClient.closeNow` at `kyo-http/.../HttpClient.scala:93`). Impl runs `awaitDrain` under `Async.timeout(gracePeriod)`, then runs current `finalizer` regardless. The existing `Scope.acquireRelease` registration at `JsonRpcEndpointImpl.scala:719` uses `impl.finalizer` directly; that path stays force-close. See Open observations Q-002 on whether Scope-finalizer should also accept a grace period.
(d) Test impact: new `JsonRpcEndpointTest` case driving a slow in-flight handler, calling `close(1.second)`, and asserting both (i) the handler completes if it finishes before the grace, and (ii) the force-close runs after the grace expires.

#### Item 10: `CancellationPolicy` owns its decoder (audit: `LSP-coverage-audit.md` NICE-TO-HAVE #3)

(a) Current source state: `CancellationEngine.scala:19` branches `if policy.cancelMethod == "$/cancelRequest"` to pick between `LspCancelParams` and `McpCancelParams` decoders (lines 11-12 define both privately).
(b) Gap: a third-party `CancellationPolicy` (e.g. a custom protocol) silently falls through to the MCP decoder, decoding any params as `McpCancelParams(requestId, reason)` regardless of intended shape.
(c) Design instinct: add `decodeParams: Structure.Value => Maybe[JsonRpcId] < Sync` (or `(Structure.Value, Frame) ?=> Maybe[JsonRpcId] < Sync`) field to `CancellationPolicy`. The `lsp` / `mcp` presets ship the corresponding decoders. `CancellationEngine.extractCancelId` delegates to `policy.decodeParams` instead of branching on the method-name string.
(d) Test impact: `CancellationPolicyTest` adds a custom-policy round-trip case. `CancellationEngine`-level coverage already exists via `scenario/` tests; verify no regression.

#### Item 11: Per-sessionId notification dispatch seam (audit: `CDP-vs-kyo-browser.md` BACKPORT #5)

(a) Current source state: `JsonRpcMethod.notification` registers one handler per method name; engine routes via `methodMap.get(method)` at `JsonRpcEndpointImpl.scala:888`. Per-sessionId routing (CDP's `frameEventDispatchers` / `downloadEventDispatchers` per-sid `Dict` maps) is opaque to the engine.
(b) Gap: CDP and any multi-tenant protocol over a single connection cannot key handlers by `(method, sessionId)`. The engine's `HandlerCtx.extras` (populated by `JsonRpcCodec.Cdp` decode, `JsonRpcCodecImpl.scala:217`) carries the sid raw but the handler must re-key/route itself.
(c) Design instinct (per `CDP-vs-kyo-browser.md` BACKPORT #5, option (a)): the lower-risk path is to document the `HandlerCtx.extras`-based recipe, plus ensure the extras are populated before handler invocation across both `Strict2_0` and `Cdp` codecs uniformly. Concrete change: add `JsonRpcMethod.scopedNotification[Scope, In](name)(extractScope: HandlerCtx => Maybe[Scope])(handler: (Scope, In, HandlerCtx) => Unit < S)` convenience builder that reads the scope out of `HandlerCtx.extras` and self-routes. See Open observations Q-003 on whether `Strict2_0` should also populate extras (today only Cdp does).
(d) Test impact: new `scenario/` test that drives two virtual sessions over a single CDP-mode transport, verifies handlers see the correct sid in `HandlerCtx.extras`, and asserts the scoped-notification convenience routes correctly.

#### Item 12: Public `JsonRpcMethod.dispatch(...)` (audit: `MCP-vs-kyo-ai-harness.md` BACKPORT #5)

(a) Current source state: `JsonRpcMethod.scala:22` `private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)`; the harness reaches into a parallel Map at `luminous-toasting-graham/.../HarnessApp.scala:213, :286, :294` to invoke `method.handle(paramsValue)` directly.
(b) Gap: transports that want to skip the engine's queueing / id-allocation overhead (one-shot stdio loop, HTTP POST `/mcp`) cannot reach into `handle` without internal access.
(c) Design instinct: add `JsonRpcMethod.dispatch(name: String, methods: Seq[JsonRpcMethod[S]], params: Structure.Value, ctx: HandlerCtx)(using Frame): Maybe[Structure.Value < (Async & Abort[JsonRpcError])]` (returns `Absent` for unknown method). Internally builds the `methodMap` once and delegates to `handle`. The dispatch helper stays public; the underlying `handle` remains `private[kyo]`.
(d) Test impact: new `JsonRpcMethodTest` cases for `dispatch(name, methods, params, ctx)`: known method, unknown method, notification kind vs request kind, abort propagation.

#### Item 13: `endpoint.sendUnmatched(method, params, id)` (audit: `CDP-coverage-audit.md` SHOULD-FIX #3, `CDP-vs-kyo-browser.md` BACKPORT #6)

(a) Current source state: `JsonRpcEndpoint.scala:16-21` `notify` produces a notification-shaped envelope (no id). The kyo-browser dialog drainer at `concurrent-imagining-stroustrup/.../CdpClient.scala:324-340` writes a sentinel-id request directly via `outbound.put(wire)`, bypassing exchange.
(b) Gap: protocols (CDP dialog handling) that require an id-present-but-unmatched envelope cannot model this via `notify`. The wire goes out as a request shape, but the engine does not register a pending promise.
(c) Design instinct: add `def sendUnmatched[In: Schema](method: String, params: In, id: JsonRpcId, extras: ExtrasEncoder = ExtrasEncoder.empty)(using Frame): Unit < (Async & Abort[Closed])` to `JsonRpcEndpoint`. Encodes through the configured codec as a `JsonRpcEnvelope.Request(id, method, params, extras)` and pushes onto `writerChannel` directly, bypassing `callerRegistry` and `inFlight`. No pending promise registration.
(d) Test impact: new `JsonRpcEndpointTest` case verifies the wire shape (request-with-id) and that the sender does not block / register any pending entry. Existing `notify` semantics unaffected.

### Audit-missed module-level concerns

#### Item 14: MCP malformed-id null-reply semantic (audit: `COVERAGE-VERIFICATION.md` §3.1 MISSED-BY-AUDIT MCP row 1)

(a) Current source state: `JsonRpcCodecImpl.scala` encodes Response envelopes with `Structure.encode(id)` at `:41` (Strict2_0) and `:135` (Cdp); the engine emits Malformed with `Absent` id which serialises as omitted, not as `null`. There is no path that produces a `{"id": null, "error": ...}` reply.
(b) Gap: live MCP spec says "Error responses MUST include the same ID as the request they correspond to (except in error cases where the ID could not be read due to a malformed request)". The "could not be read" clause implies `null` should appear as the id value in that error reply.
(c) Design instinct: add a `respondToMalformed: Boolean` flag (or implicit on `JsonRpcEnvelope.Response` allowing `Maybe[JsonRpcId]` instead of `JsonRpcId`). Strict2_0 / Cdp encode `null` for the missing id branch. The decode callback at `JsonRpcEndpointImpl.scala:1181-1182` recognises Malformed-with-no-id and synthesises a null-id error response when the codec policy is Strict2_0. See Open observations Q-004 on whether Cdp should mirror this.
(d) Test impact: `JsonRpcCodecTest` adds null-id-encode round-trip case. New `scenario/` test feeds a malformed top-level non-record frame and asserts the reply is `{"jsonrpc":"2.0","id":null,"error":{...}}`.

#### Item 15: MCP `_meta` reserved-prefix MUST enforcement (audit: `COVERAGE-VERIFICATION.md` §3.1 row 3, `MCP-coverage-audit.md` row 28)

(a) Current source state: `HandlerCtx.scala:17` exposes `extras: Maybe[Structure.Value]`; `_meta` inside `params` (MCP-style) is not surfaced separately. `ProgressPolicy.mcp.extractRequestToken` at `ProgressPolicy.scala:51-52` reads `_meta.progressToken` but the broader `_meta` map is opaque to handlers.
(b) Gap: MCP spec §_meta says implementations MUST NOT make assumptions about reserved-prefix keys (e.g. `modelcontextprotocol/mcp`). Today a handler that reads its own `_meta` keys has no engine-level path for the reserved-prefix awareness contract.
(c) Design instinct: add `meta: Maybe[Structure.Value]` field to `HandlerCtx`, populated by the engine from `params._meta` when present. Add `HandlerCtx.metaUserKeys` accessor that filters reserved-prefix keys (predicate provided as a function reference, e.g. `ReservedPrefix.mcp = Set("modelcontextprotocol/mcp", ...)`). Concrete reserved-prefix set is policy-configurable (likely on `JsonRpcEndpoint.Config` or on a new `MetaPolicy` to mirror existing policy shape). See Open observations Q-005 on whether `MetaPolicy` should be a separate policy or live on `JsonRpcEndpoint.Config` as a flag.
(d) Test impact: `HandlerCtxTest` adds meta-field coverage. New `scenario/` test verifies a handler sees `_meta` with reserved keys filtered out by default, and that the raw `_meta` is still accessible.

#### Item 16: MCP JSON Schema 2020-12 support (audit: `COVERAGE-VERIFICATION.md` §3.1 row 6)

(a) Current source state: kyo-schema (cross-cutting; not in kyo-jsonrpc) controls JSON Schema emission. kyo-jsonrpc currently exposes typed schemas for individual case classes (e.g. `JsonRpcError derives Schema`) but has no JSON-Schema-document emission for tools/elicitation.
(b) Gap: live MCP spec basic §JSON Schema Usage says "Clients and servers MUST support JSON Schema 2020-12". MCP tools/elicitation surfaces ship JSON Schema documents describing input/output shapes; without 2020-12 support the consumer module cannot produce spec-conformant tool descriptions.
(c) Design instinct: this is partially out-of-scope for kyo-jsonrpc per the steering OUT-OF-SCOPE list (typed MCP/CDP/LSP method libraries). What belongs HERE is a small `JsonSchema2020_12.encode[T: Schema](using Frame): Structure.Value < Sync` (or `Json.Value < Sync`) helper that emits a 2020-12-compliant schema document from a kyo-schema `Schema[T]`. The MCP-specific wrapper (tools/list payload) lives in `kyo-mcp`. See Open observations Q-006 on whether this encoder lives in kyo-jsonrpc or in kyo-schema; kyo-schema is the more natural home but kyo-jsonrpc may need a passthrough.
(d) Test impact: new `JsonSchema2020_12Test` covering Int / Str / Maybe / Chunk / Record case classes; assertions on `$schema`, `type`, `properties`, `required` field presence per 2020-12 dialect.

#### Item 17: LSP `window/workDoneProgress/create` server-initiated token seam (audit: `COVERAGE-VERIFICATION.md` §3.3 row 4)

(a) Current source state: client-initiated progress works (the caller passes a token through `params.workDoneToken` and the engine routes). Server-initiated progress (`window/workDoneProgress/create` is a server-to-client request creating a token the server will then emit progress against) has no engine-level support.
(b) Gap: LSP 3.17 §Server-Initiated Progress allows the server to request the client create a progress token. The engine's call/notify surface already supports the request shape (`endpoint.call("window/workDoneProgress/create", CreateParams(token), ...)`), but the server side cannot then EMIT progress on a token it just created without going through `HandlerCtx.progress` which assumes the token came from inbound params.
(c) Design instinct: per the audit, "engine surface (symmetric call/notify) is sufficient", meaning Item 17 reduces to documenting the `endpoint.call("window/workDoneProgress/create", ...)` + `endpoint.notify("$/progress", ...)` recipe and ensuring the `notify`-side progress emission honours the `ProgressPolicy.lsp.progressMethod` codec consistently. The engine seam may need a `def emitProgress(token: Structure.Value, value: Structure.Value)(using Frame): Unit < (Async & Abort[Closed])` convenience that builds the envelope through the configured `progressPolicy.encodeProgressParams`. See Open observations Q-007 on whether server-initiated progress lives in engine API or in kyo-lsp.
(d) Test impact: new `scenario/` test (or LSP-style additions to `WsStyleTest`) drives the server-initiated create+emit flow end-to-end across an inMemory transport.

## Open observations

- Q-001 [needs user input on value-judgment]: Module placement for `JsonRpcTransport.webSocket` (Item 4). Options: (a) new `kyo-jsonrpc-http` cross-platform subproject depending on `kyo-http`; (b) in-place `kyo-jsonrpc/jvm` + `/js` + `/native` mirror layout (kyo-jsonrpc currently has only `shared/`, see `find` of `kyo-jsonrpc/`). Steering does not lock placement. Affects build.sbt structure and downstream import paths.
- Q-002 [needs user input on value-judgment]: Should the `Scope.acquireRelease` finalizer at `JsonRpcEndpointImpl.scala:719` accept a grace period (configured via `Config.closeGracePeriod`), or stay force-close on Scope exit while only the user-facing `close(gracePeriod)` honours the grace? Design-wise: a config field allows Scope-managed endpoints to drain, but ties grace to construction; explicit `close(gracePeriod)` keeps Scope semantic crisp. Lean: explicit.
- Q-003 [needs flow-resolve-open]: For Item 11, should `Strict2_0` codec also populate `extras` (currently only `Cdp` does, per `JsonRpcCodecImpl.scala:92` returning `Absent` for extras vs `:210/:213/:217` for Cdp)? Research-knowable by reading `JsonRpcCodecImpl.scala:53-109` and confirming the audit's claim at `MCP-coverage-audit.md:55` row 28. If yes, the `scopedNotification` recipe works uniformly for non-CDP protocols too.
- Q-004 [needs flow-resolve-open]: For Item 14, does the Cdp codec need null-id-reply behaviour (CDP servers do not formally specify malformed-id replies)? Research-knowable by reading CDP method-page examples + the live spec; absence of a formal CDP spec on this means lean toward Strict2_0-only.
- Q-005 [needs user input on value-judgment]: For Item 15, `MetaPolicy` as a separate policy record (mirroring `CancellationPolicy` / `ProgressPolicy`) vs a flag on `Config`. Separate policy is consistent with the existing preset pattern (`lsp` / `mcp` / `minimal`), flag is lighter. Lean: separate policy with `lsp` / `mcp` presets for consistency.
- Q-006 [needs user input on value-judgment]: For Item 16, where does `JsonSchema2020_12.encode` live? Options: (a) kyo-jsonrpc (close to consumer needs); (b) kyo-schema (where Schema[T] lives natively). kyo-schema is the cleaner home; kyo-jsonrpc just re-exports if needed.
- Q-007 [needs user input on value-judgment]: For Item 17, does `def emitProgress(token, value)` belong on `JsonRpcEndpoint` (engine API) or in a `kyo-lsp` consumer module? The progress-emission shape is generic (any policy can use it), so engine placement is justified; but server-initiated PROGRESS without first having received an inbound token is mainly an LSP pattern.
- Items 4-7 (transport adapters + byte-stream seam) potentially compose: byte-stream seam (Item 7) underlies Item 5 (stdio) and Item 6 (UDS). The plan must sequence Item 7 before Items 5/6 to avoid re-doing the framer twice. Item 4 (WebSocket) is independent (WebSocket frames are already message-bounded).
- The `JsonRpcMethod.dispatch` lift (Item 12) interacts with `JsonRpcMethod.handle`'s `private[kyo]` visibility. The simplest path leaves `handle` private and exposes `dispatch` as the public entry; no callers outside the engine currently reach `handle`. Confirmed by grepping `JsonRpcMethod` references: only `JsonRpcEndpointImpl.scala:905, :992` invoke `m.handle`.
- The 1259-LOC `JsonRpcEndpointImpl` is the highest-churn target for this campaign; phases touching it (Items 3, 8, 9, 11, 13, 14, 15) should bundle adjacent edits within phase boundaries to limit re-reads under the `flow-phase-impl` skim contract.

## Citations index

- `JsonRpcEndpoint.scala`: 7-50 (final class + methods), 52-81 (companion: Pending, Config, init); Config defaults 62-72; default cancellation at 64; close at 48.
- `JsonRpcEndpointImpl.scala`: 5 (CHM annotation), 46-66 (engine class fields), 68-155 (call), 162-337 (callEncoded), 339-350 (notify), 352-431 (callWithProgress; token alloc at 368, registration at 391), 433-535 (callPartialResults; token alloc at 451, registration at 465), 537-555 (subscribeProgress), 570-630 (cancel), 632-660 (close + finalizer), 712-720 (init + Scope.acquireRelease), 830-1186 (decodeCallback; Malformed at 1181-1182), 1189-1253 (Exchange init + writer fiber).
- `JsonRpcTransport.scala`: 6-10 (trait), 12-32 (companion: inMemory only).
- `JsonRpcEnvelope.scala`: 7-26 (ADT; Malformed at 25).
- `JsonRpcCodec.scala`: 9-12 (trait), 14-17 (Strict2_0 / Cdp).
- `JsonRpcCodecImpl.scala`: 5-111 (Strict2_0; Malformed at 104), 113-226 (Cdp; reserved keys at 7-8; extras at 192-195, 210, 213, 217; Malformed at 218-219).
- `JsonRpcMethod.scala`: 22 (handle), 29-46 (apply overloads), 48-57 (notification), 59-89 (RequestMethod), 91-120 (NotificationMethod).
- `JsonRpcError.scala`: 11 (case class), 14-24 (named constants; -32800..-32803 LSP codes at 21-24), 26-40 (factories).
- `JsonRpcId.scala`: 9-12 (enum), 16-28 (Schema).
- `HandlerCtx.scala`: 14-24 (class), 26-34 (forTest).
- `ExtrasEncoder.scala`: 4 (opaque type), 6-17 (companion).
- `IdStrategy.scala`: 4-8 (enum).
- `CancellationPolicy.scala`: 10-16 (case class), 19 (ParamsEncoder type), 21-22 (private Lsp/McpCancelParams), 25-34 (encoders), 36-50 (presets).
- `CancellationEngine.scala`: 11-12 (private params types), 14-27 (extractCancelId; method-name branch at 19), 33-76 (handleInboundCancel), 82-92 (buildAndEnqueueOutboundCancel), 97-129 (handleTimeout).
- `ProgressPolicy.scala`: 10-18 (case class), 38-46 (lsp; workDoneToken-only at 41-42), 48-62 (mcp).
- `ProgressEngine.scala`: 16-82 (buildProgressSink).
- `UnknownMethodPolicy.scala`: 5-9 (case class), 11-16 (UnknownAction), 18-34 (presets).
- `MessageGate.scala`: 4-13 (trait + Decision).
- `JsonRpcTestBase.scala`: 9 (base class).
- `kyo-http/.../HttpClient.scala`: 86-93 (close(gracePeriod)/closeNow), 737-758 (webSocket).
- `kyo-http/.../HttpServer.scala`: 52-59 (close(gracePeriod)/closeNow).
- `kyo-core/.../Console.scala`: 25 (readLine), 53 (printLine), 71+ (Console object), 142-183 (Console.Unsafe test seam).
- `concurrent-imagining-stroustrup/kyo-browser/.../CdpClient.scala`: 24 (Exchange), 28-32 (per-sid dispatcher tables), 139-158 (close gracePeriod), 230-266 (WS relay fiber), 324-340 (fire-and-forget sentinel id), 519-528 (fallbackDecode).
- `luminous-toasting-graham/kyo-ai-harness/.../HarnessApp.scala`: 240-303 (hand-rolled stdio JSON-RPC loop).
- `luminous-toasting-graham/kyo-ai-harness/jvm/.../HookSocketServer.scala`: 31-54 (UDS bind + accept loop).
- Audits: `MCP-coverage-audit.md` rows 19, 25, 28; `LSP-coverage-audit.md` rows 1, 15; `CDP-coverage-audit.md` MUST-FIX + SHOULD-FIX 2-3; `COVERAGE-VERIFICATION.md` §4.1-§4.3, §3.1 rows 1, 3, 6, §3.3 row 4; `CDP-vs-kyo-browser.md` BACKPORT #1-#6; `MCP-vs-kyo-ai-harness.md` BACKPORT #1, #2, #5.
