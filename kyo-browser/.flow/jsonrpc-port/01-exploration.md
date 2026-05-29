# 01 Exploration: Port kyo-browser CDP client to kyo-jsonrpc

Task type: migration
Primary module: kyo-browser
Scope: kyo-browser/{shared,jvm,js,native,jvm-native}/src/**/scala/kyo/**/{Cdp,Browser,Navigation,Mutation,Stability,Shared,Actionability,Resolver,Selector,IFrame,Probes,JsString,PageDownload,Accessibility}*.scala plus kyo-jsonrpc{,-http}/**/*.scala

## Task statement

> Port kyo-browser to use kyo-jsonrpc instead of its in-tree CDP wire/codec/dispatch primitives. Every test-stability and speed optimization in kyo-browser MUST be preserved. Cross-platform: shared/JVM/JS/Native all stay green. End state: kyo-browser owns zero JSON-RPC wire framing, codec dispatch, request/notification routing, or correlation logic; all such concerns flow through kyo-jsonrpc.

## Inputs and prior art

The seed audit `kyo-jsonrpc/research/CDP-vs-kyo-browser.md` (cited paths use the
`concurrent-imagining-stroustrup` snapshot) drives this exploration. Every
audit row was re-verified against HEAD in `crispy-swinging-lemur`. The 6
BACKPORT items the audit identified all shipped in the `kyo-jsonrpc protocol
coverage` campaign (tasks #34 / #42-#46), so the engine surface is now ready
to absorb the CDP client. The remaining work is purely on the kyo-browser
side: rewrite `CdpClient` against `JsonRpcEndpoint`, replace `CdpBackend`
wrapper bodies, delete the codec / dispatch / id-allocation primitives that
have been generalised.

`CD` below = `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur` (current worktree).

## Module map

### kyo-browser CDP surface (the targets of removal/refactor)

- `CD/kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala` (627 LoC).
  The connection-level CDP engine: WebSocket relay, `Exchange` correlation,
  per-connection in-flight cap, drain signal, decode-and-route reader,
  per-sessionId dispatcher tables, dialog drainer, last-evaluate diagnostic.
  `private[kyo] final class CdpClient` plus six envelope/case-class
  helpers (`CdpEnvelope`, `CdpNoParams`, `CdpWireMessage`, `CdpReply`,
  `CdpEventParams`, `JavascriptDialogOpeningParams`, `FallbackIdEnvelope`,
  `CdpEvent`, `CdpError`, `CdpSender`). Used by `BrowserTab`,
  `BrowserTabSetup`, `Browser.scala` (call sites at `Browser.scala:258, 273,
  2157-2194, 2533-2535`), every `CdpBackend` wrapper.
- `CD/kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` (228 LoC).
  Typed wrapper layer: 28 CDP methods (Page, Runtime, Emulation, Input, DOM,
  Network, Target). Each calls `CdpSender.send(method, params)` then
  `decodeOrFail[Result](_, method)`. The `decodeOrFail` helper
  (`CdpBackend.scala:25-44`) peels a `CdpReply[A]` envelope (`Maybe[result] /
  Maybe[error]`) and surfaces `BrowserProtocolErrorException`.
- `CD/kyo-browser/shared/src/main/scala/kyo/internal/CdpWire.scala` (195 LoC).
  Three sibling helpers all consumed by the eval pipeline:
  `CdpBase64Decode` (Base64 → bytes / Image with typed error mapping for
  `Page.captureScreenshot` / `Page.printToPDF`), `ExceptionDetailsFormat` (CDP
  `ExceptionDetails` → human-readable v8-style stack), `CdpEvalEnvelope` /
  `CdpEvalDecoder` (`Runtime.evaluate` reply envelope + per-`RemoteObject`-variant
  value extraction).
- `CD/kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala` (452 LoC).
  Opaque-type IDs (`TargetId`, `SessionId`, `NodeId`, `FrameId`,
  `ExecutionContextId`, `NodeRef`), 50+ `private[kyo] case class … derives
  Schema` shapes covering every CDP method's params and result, plus the
  flat-discriminator `RemoteObject` Schema (`CdpTypes.scala:200`). Pure
  payload schemas; no wire/dispatch logic; survives the port unchanged.
- `CD/kyo-browser/shared/src/main/scala/kyo/internal/CdpErrorStrings.scala` (21 LoC).
  Two CDP error-message substrings (`StaleNodeErrorMessage`,
  `ContextDestroyedErrorMessage`) consumed by `Resolver` and `BrowserEval` for
  retry classification. Domain-specific; stays.
- `CD/kyo-browser/shared/src/main/scala/kyo/internal/cdp/PageDownload.scala` (94 LoC).
  `Browser.DownloadBehavior` enum + `Page.setDownloadBehavior` wrapper +
  `DownloadWillBeginWire` / `DownloadProgressWire` schemas. Per-domain CDP
  glue; survives.
- `CD/kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala` (182 LoC).
  `Accessibility.getFullAXTree` / `getFullAXTreeForFrame` + `AxNode` /
  `AxValue` types + `parseAxTree`. Domain glue; survives.

### kyo-browser test-stability layer (must survive port unchanged)

- `BrowserNetworkTracker.scala` (127 LoC). In-page fetch/XHR JS trackers.
  `installerScript` (lines 22-52) and `responseTrackerScript`
  (lines 66-88) installed via `BrowserEval.evalJs`; `waitForNetworkIdleFor`
  (lines 99-126) polls the in-page counters with a Retry schedule.
- `MutationSettlement.scala` (262 LoC). Body-rooted MutationObserver with
  ref-counted shared lifecycle (`__kyoMutObsRef`), single-eval in-page
  quiescence loop (`awaitQuiescence`). The two-regime polling
  (lines 51-60); `startCount` snapshot, fast path when no mutation
  observed, quiescence-window path when mutations did fire; is critical to
  keep clicks-with-no-DOM-effect cheap.
- `NavigationWatcher.scala` (585 LoC). `armAround` / `armAroundNavigation` /
  `armAroundReload` / `waitForNext` install an in-page recorder
  (`history.pushState` patch + `beforeunload`) and poll readyState +
  network-idle; deliberately polling, not event-channel-based, so the CDP
  event channel stays free for downloads.
- `StabilitySampler.scala` (81 LoC). Single-eval in-page sampling loop
  (`sampleWindow`, lines 48-69). Solves the alias problem in
  multi-round-trip stability sampling under high CDP latency. Returns a
  `SampleReply{tag, value}` JSON.
- `Actionability.scala` (497 LoC). One-shot in-page actionability check
  (`Actionability.check`, line 54) merged into a single eval (`buildJs`,
  line 92); per-reason diagnostic enrichment, retry-on-stale (`withRetry`,
  line 487).
- `SharedChrome.scala` (84 LoC). Process-wide cached Chrome URL. `init`
  (line 35) returns a single WebSocket URL launched on first call; tests
  reuse one Chrome process, each creates its own `CdpClient` /
  `JsonRpcEndpoint`.
- `BrowserLauncher.scala` (213 LoC). `launch` (line 22) spawns Chrome and
  polls `DevToolsActivePort` for the ws URL; `killOrphans` (line 115) sweeps
  prior-run zombie Chrome processes. Platform-agnostic; orchestrates the
  WS URL kyo-browser then hands to the CDP client.
- `BrowserLauncherPlatform.scala` (jvm-native: 27 LoC, js: 11 LoC). JVM /
  Native shutdown hook to SIGKILL Chrome on parent exit; JS no-op.
- `JsStringUtil.scala` (53 LoC). Cross-platform percent-encoding /
  JS-string escape used by the resolver. Survives.
- `Resolver.scala` (242 LoC). `resolveOne` / `resolveAll` two-step
  (`Runtime.evaluate` returning objectId, then `DOM.requestNode`,
  `DOM.describeNode`). Retry-on-stale-node via `CdpErrorStrings`.
- `Selector.scala` (694 LoC). User-facing selector ADT + JS-template
  rendering.

### kyo-browser test files exercising the wire/stability layer

Counted by `" in run|" in \{|" in withBrowser` per file (1308 test cases
across 56 shared test files plus 4 platform tests).

Notable highest-count suites that depend on stability/wire behavior:

- `BrowserReadTest.scala` (91), `SelectorIntegrationTest.scala` (83),
  `BrowserAssertionTest.scala` (72), `BrowserMutationTest.scala` (70),
  `SelectorTest.scala` (56), `BrowserCoreTest.scala` (56 inferred from `in
  run` style),  `BrowserIsolateTest.scala` (55),
  `BrowserCookieTest.scala` (48), `BrowserKeyboardTest.scala` (46),
  `BrowserActionabilityTest.scala` (42), `CdpBackendTest.scala` (41),
  `BrowserSettlementTest.scala` (41), `BrowserIFrameTest.scala` (33),
  `BrowserConfigTest.scala` (33).

Wire-layer-specific suites (will move/rewrite during the port):

- `CdpClientLifecycleTest.scala` (1262 LoC, 25 test cases). Exercises
  `awaitDrain`, `close(gracePeriod)`, `closeNow`, drainSignal swap on 0->1
  / 1->0 transitions, `lastEvaluateParams` diagnostic recording, dialog
  drainer fire-and-forget invariant, frame-event dispatcher per-session
  isolation, download-event dispatcher add/remove.
- `CdpClientTest.scala` (286 LoC, 15 cases). Exercises `submit`'s
  `inFlight` counter and `cdpMeter` cap.
- `CdpClientDecoderTest.scala` (179 LoC, 7 cases). Exercises
  `decodeCdpMessage` event-routing branches and the fallback `id` recovery
  path.
- `CdpBackendTest.scala` (669 LoC, 41 cases). Exercises every typed
  wrapper's `decodeOrFail` shape.
- `CdpParamsRoundTripTest.scala` (359 LoC, 15 cases). Schema round-trip
  for `CdpEnvelope[P]`, every param / result case class.
- `CdpTypesTest.scala` (29 LoC, 5 cases). Opaque-id constructors.
- `CdpTypesSchemaFailureTest.scala` (63 LoC, 6 cases). Schema rejection
  on malformed wire shapes.
- `CdpEvalDecoderTest.scala` (220 LoC, 15 cases). `RemoteObject`
  variant decoding.
- `BrowserWireDecodeFailureTest.scala` (117 LoC, 6 cases). End-to-end
  malformed-wire surface tests via in-memory fakes.

### kyo-jsonrpc engine surface (the source of the port)

- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala` (108 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala` (54 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala` (143 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala` (17 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala` (26 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala` (29 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala` (41 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala` (17 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala` (34 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala` (8 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala` (75 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala` (35 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/Framer.scala` (37 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/WireTransport.scala` (18 LoC).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala` (245 LoC, holds `Strict2_0` and `Cdp` codecs).
- `CD/kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala` (1288 LoC, the engine).
- `CD/kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala` (109 LoC). The kyo-http WebSocket bridge.

## Relevant APIs (verbatim signatures)

### kyo-jsonrpc

```scala
// JsonRpcEndpoint.scala:7
final class JsonRpcEndpoint private[kyo] (private[kyo] val impl: internal.JsonRpcEndpointImpl):

    def call[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) // line 9-14

    def notify[In: Schema](
        method: String,
        params: In,
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Unit < (Async & Abort[Closed]) // line 16-21

    def sendUnmatched[In: Schema](
        method: String,
        params: In,
        id: JsonRpcId,
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Unit < (Async & Abort[Closed]) // line 23-29

    def cancel(id: JsonRpcId, reason: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[Closed]) // line 51-52

    def awaitDrain(using Frame): Unit < Async = impl.awaitDrain  // line 54

    def close(using Frame): Unit < Async                // line 61, == close(Duration.Zero)
    def close(gracePeriod: Duration)(using Frame): Unit < Async // line 68
    def closeNow(using Frame): Unit < Async             // line 75
```

```scala
// JsonRpcEndpoint.scala:89-99 (Config)
final case class Config(
    codec: JsonRpcCodec = JsonRpcCodec.Strict2_0,
    cancellation: Maybe[CancellationPolicy] = Absent,
    progress: Maybe[ProgressPolicy] = Absent,
    unknownMethod: UnknownMethodPolicy = UnknownMethodPolicy.minimal,
    gate: Maybe[MessageGate] = Absent,
    maxInFlight: Maybe[Int] = Absent,
    requestTimeout: Duration = Duration.Infinity,
    idStrategy: IdStrategy = IdStrategy.SequentialLong,
    progressResetsTimeout: Boolean = false
)

// JsonRpcEndpoint.scala:101-106 (init)
def init(
    transport: JsonRpcTransport,
    methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
    config: Config = Config()
)(using Frame): JsonRpcEndpoint < (Sync & Async & Scope)
```

```scala
// JsonRpcTransport.scala:6-10
trait JsonRpcTransport:
    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed])
    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]]
    def close(using Frame): Unit < Async
```

```scala
// JsonRpcMethod.scala:25-57
object JsonRpcMethod:
    enum Kind: case Request, Notification

    def apply[In: Schema, Out: Schema, S](name: String)(handler: (In, HandlerCtx) => Out < S)(using Frame, (Async & Abort[JsonRpcError]) <:< S): JsonRpcMethod[S] // line 29
    def apply[In: Schema, Out: Schema, S](name: String)(handler: In => Out < S)(using ...): JsonRpcMethod[S] // line 41
    def notification[In: Schema, S](name: String)(handler: (In, HandlerCtx) => Unit < S)(using ...): JsonRpcMethod[S] // line 48
```

```scala
// HandlerCtx.scala:14-19
final class HandlerCtx private[kyo] (
    val cancelled: Fiber.Promise[Unit, Sync],
    val requestId: Maybe[JsonRpcId],
    val extras: Maybe[Structure.Value],
    private[kyo] val progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
)
```

```scala
// ExtrasEncoder.scala:4-13
opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync

object ExtrasEncoder:
    val empty: ExtrasEncoder
    def const(extras: Structure.Value): ExtrasEncoder
    extension (self: ExtrasEncoder)
        def resolve(id: JsonRpcId)(using Frame): Maybe[Structure.Value] < Sync
```

```scala
// JsonRpcEnvelope.scala:7-26
enum JsonRpcEnvelope derives CanEqual:
    case Request(id: JsonRpcId, method: String, params: Maybe[Structure.Value], extras: Maybe[Structure.Value])
    case Notification(method: String, params: Maybe[Structure.Value], extras: Maybe[Structure.Value])
    case Response(id: JsonRpcId, result: Maybe[Structure.Value], error: Maybe[JsonRpcError], extras: Maybe[Structure.Value])
    case Malformed(id: Maybe[JsonRpcId], reason: String, raw: Structure.Value)
```

```scala
// JsonRpcId.scala:9-12
enum JsonRpcId derives CanEqual:
    case Num(value: Long)
    case Str(value: String)
```

```scala
// IdStrategy.scala:4-8
enum IdStrategy derives CanEqual:
    case SequentialLong
    case SequentialInt
    case Custom(next: () => JsonRpcId < Sync)
```

```scala
// JsonRpcCodec.scala:9-17
trait JsonRpcCodec:
    def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError])
    def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync

object JsonRpcCodec:
    val Strict2_0: JsonRpcCodec
    val Cdp: JsonRpcCodec  // {id, method, params, ...extras}; no jsonrpc field
```

```scala
// JsonRpcHttpTransport.scala:6-10  (kyo-jsonrpc-http; depends on kyo-http)
def webSocket(
    url: HttpUrl,
    headers: HttpHeaders = HttpHeaders.empty,
    codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
)(using Frame): JsonRpcTransport < (Async & Scope & Abort[HttpException])
```

### kyo-browser CDP today

```scala
// CdpClient.scala:23-40 (constructor signature)
final class CdpClient private[kyo] (
    private[kyo] val exchange: Exchange[Int => String, String, CdpEvent, Closed],
    private[kyo] val outbound: Channel[String],
    private[kyo] val inbound: Channel[String],
    private[kyo] val relay: Fiber[Unit, Sync],
    private[kyo] val dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]],
    private[kyo] val dialogDrainer: Fiber[Unit, Any],
    private[kyo] val dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
    private[kyo] val inFlight: AtomicInt,
    private[kyo] val drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
    private[kyo] val frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]],
    private[kyo] val lastEvaluateParams: AtomicRef[Maybe[String]],
    private[kyo] val cdpMeter: Meter,
    private[kyo] val requestTimeout: Duration,
    private[kyo] val sessionId: Maybe[SessionId] = Absent
) extends CdpSender
```

```scala
// CdpClient.scala:42-55
def send[P: Schema](method: String, params: P)(using Frame): String < (Async & Abort[BrowserReadException])
def send(method: String)(using Frame): String < (Async & Abort[BrowserReadException])
def sendUnit[P: Schema](method: String, params: P)(using Frame): Unit < (Async & Abort[BrowserReadException])
def sendUnit(method: String)(using Frame): Unit < (Async & Abort[BrowserReadException])

// CdpClient.scala:118-136
def withSession(sid: SessionId): CdpClient  // returns a re-wrapped CdpClient sharing all fields except sessionId

// CdpClient.scala:142-189
def close(gracePeriod: Duration)(using Frame): Unit < Async
def closeNow(using Frame): Unit < Async
def awaitDrain(using Frame): Unit < Async
```

```scala
// CdpClient.scala:206
private[kyo] val maxInFlight: Int = 8

// CdpClient.scala:209-212 (init signatures)
def init(wsUrl: String, launchCfg: Browser.LaunchConfig)(using Frame): CdpClient < (Async & Scope & Abort[BrowserReadException])
def initUnscoped(wsUrl: String, launchCfg: Browser.LaunchConfig)(using Frame): CdpClient < (Async & Abort[BrowserReadException])
```

```scala
// CdpBackend.scala:25-44
private[kyo] def decodeOrFail[A: Schema](wire: String, method: String)(using Frame): A < Abort[BrowserReadException]
```

```scala
// CdpClient.scala:561-617 (the envelopes that disappear)
final private[kyo] case class CdpEnvelope[P](id: Int, method: String, params: P, sessionId: Maybe[String]) derives Schema
final private[kyo] case class CdpNoParams() derives Schema
final private[kyo] case class CdpWireMessage(id: Maybe[Int] = Absent, method: Maybe[String] = Absent, sessionId: Maybe[String] = Absent, error: Maybe[CdpError] = Absent) derives Schema
final private[kyo] case class CdpReply[A](result: Maybe[A] = Absent, error: Maybe[CdpError] = Absent) derives Schema
final private[kyo] case class CdpEventParams[P](params: P) derives Schema
final private[kyo] case class FallbackIdEnvelope(id: Maybe[Int] = Absent) derives Schema
final private[kyo] case class CdpError(code: Int, message: String) derives Schema, CanEqual
sealed private[kyo] trait CdpEvent; CdpEvent.Generic(method: String, paramsJson: String, sessionId: Maybe[SessionId] = Absent)
```

```scala
// Browser.scala:3113-3114, 3188-3210  (LaunchConfig timeouts the port must thread)
requestTimeout = 60.seconds      // -> JsonRpcEndpoint.Config.requestTimeout
closeGrace     = 30.seconds      // -> JsonRpcEndpoint.close(gracePeriod)
```

## Conventions in this module

- **Internal-only package**: every CDP type is `private[kyo]` and lives in
  `kyo.internal` per `CdpClient.scala:1`, `CdpBackend.scala:1`,
  `CdpTypes.scala:1`. The port maintains this; only `Browser.scala` is the
  public surface.
- **Schema-derived case classes for wire**: all CDP params/results use
  `derives Schema` directly (`CdpTypes.scala:122-453`). The flat-discriminator
  pattern on `RemoteObject` (`CdpTypes.scala:200`) is the canonical example.
- **Optional fields default to `Absent`**: every wire shape uses `Maybe[T] =
  Absent` (e.g. `NavigateResult.loaderId`, `EvalResult.exceptionDetails`),
  per [[feedback_json_use_option.md]].
- **In-page work for stability gates**: every settle/quiescence/sample
  primitive (`MutationSettlement.afterAction`, `StabilitySampler.sampleWindow`,
  `NavigationWatcher.armAround`, `BrowserNetworkTracker.waitForNetworkIdleFor`,
  `Actionability.check`) runs the polling loop INSIDE the page (`async () =>`
  with `Runtime.evaluate(awaitPromise=true)`), not Scala-side. This minimises
  CDP round-trip count and is alias-proof against transport latency. The port
  is invisible to these: they sit above the wire layer and only call
  `BrowserEval.evalJs` / `evalJsAwaiting`.
- **Per-session opaque routing**: `SessionId` (`CdpTypes.scala:11`) is a
  string-opaque type; `BrowserTab.session = client.withSession(sessionId)`
  (`BrowserTab.scala:35`) is the canonical per-tab capture. Frame-event and
  download-event dispatcher maps are keyed by `sessionId.value`.
- **Test fixture**: `BrowserTest extends AsyncFreeSpec` in
  `shared/src/test/scala/kyo/Test.scala` (115 LoC). The `run` helper threads
  Browser effects; `withBrowser` uses `SharedChrome.init`. Test style:
  `"name" in run { … }` (1308 cases across 56 shared test files; see
  per-file table above).
- **Cross-platform**: `shared/` for portable code; `jvm-native/` for code
  shared between JVM and Native; `jvm/`, `js/`, `native/` only for
  platform-specific code (e.g. `BrowserLauncherPlatform.scala`). Per
  [[feedback_all_platforms_all_tests]] the port must not demote any shared
  test to `jvm/` to dodge cross-platform cost.

## Mapping table; kyo-browser CDP concern -> kyo-jsonrpc API

| kyo-browser concern | kyo-jsonrpc surface | Notes / migration |
|---|---|---|
| Request dispatch: `CdpClient.submit` + `Exchange[Int => String, String, …]` (CdpClient.scala:57-111, 298-311) | `JsonRpcEndpoint.call[In, Out](method, params, extras)` (JsonRpcEndpoint.scala:9-14) | The engine handles id allocation, in-flight metering, timeout, and `{result, error}` peeling in one call. `decodeOrFail` disappears at every `CdpBackend` site. |
| Notification dispatch (CDP events the client routes to per-session handlers); `decodeCdpMessage` (CdpClient.scala:398-502) | `JsonRpcMethod.notification[In](name)(handler)` registered at `JsonRpcEndpoint.init` (JsonRpcMethod.scala:48-57) | The 180-line `decodeCdpMessage` collapses to N handler bodies. Handlers read sid via `HandlerCtx.extras` (see RI-001 below). |
| Per-sessionId routing; `frameEventDispatchers`, `downloadEventDispatchers`, `dialogHandlers`, `dialogRecorders` `AtomicRef[Dict[String, …]]` maps (CdpClient.scala:28-35; registration at BrowserTab.scala:93, Browser.scala:2157, 2193, 2533) | Single registered `JsonRpcMethod.notification` per CDP method; the handler reads `ctx.extras` for `sessionId`, looks up an internal `AtomicRef[Dict[SessionId, Handler]]` owned at the kyo-browser caller layer. CDP codec puts unknown top-level fields (including `sessionId`) into `extras` (JsonRpcCodecImpl.scala:199-202). | The dispatcher tables stay in kyo-browser; what disappears is the dispatch *inside* the engine. Caller registers ONE notification handler per CDP method; the kyo-browser-level table demultiplexes by sid extracted from `HandlerCtx.extras`. |
| Correlation / id allocation; Exchange-driven int counter, dialog drainer uses `AtomicInt.init(Int.MinValue)` (CdpClient.scala:325-340) | `Config.idStrategy = IdStrategy.SequentialInt` for the main path (IdStrategy.scala:6); dialog drainer uses `sendUnmatched(method, params, id, extras)` with a `JsonRpcId.Num(negativeCounter)` (JsonRpcEndpoint.scala:23-29). | The negative-id sentinel pattern is preserved: `sendUnmatched` bypasses the pending map. |
| Malformed envelope handling; strict `CdpWireMessage` decode; `fallbackDecode` (CdpClient.scala:542-557) recovers id via permissive `FallbackIdEnvelope` so the awaiting promise gets a decodeFailure not a hang | `JsonRpcEnvelope.Malformed(id: Maybe[JsonRpcId], reason, raw)` (JsonRpcEnvelope.scala:25); the engine routes Malformed-with-id to the awaiting caller's pending promise; Malformed-no-id is logged-and-dropped. The CDP codec puts the id into `Malformed` when recoverable (JsonRpcCodecImpl.scala:228-230). | Equivalent behavior; the explicit fallback case class is gone. |
| Lifecycle close; `close(gracePeriod) = Async.timeout(g)(closeOrderly) fallback closeNow` (CdpClient.scala:142-178) | `JsonRpcEndpoint.close(gracePeriod: Duration)` (JsonRpcEndpoint.scala:68); single-phase `close` and `closeNow` are also exposed (lines 61, 75). | Identical semantics. `LaunchConfig.closeGrace = 30.seconds` (Browser.scala:3114) threads through. |
| Cancellation; N/A (CDP has no cancel wire form) | `Config.cancellation: Maybe[CancellationPolicy] = Absent` (JsonRpcEndpoint.scala:91; default IS Absent in HEAD; the audit said it defaulted to LSP, but the protocol-coverage campaign changed the default). | Default already neutral; no override needed. |
| Progress; N/A (CDP) | `Config.progress: Maybe[ProgressPolicy] = Absent` (line 92). | Default already neutral. |
| Fire-and-forget id semantics; dialog drainer (CdpClient.scala:324-340) | `JsonRpcEndpoint.sendUnmatched(method, params, id, extras)` (JsonRpcEndpoint.scala:23-29). | Direct mapping. |
| Error envelope shape; `CdpError(code: Int, message: String)` (CdpClient.scala:617) | `JsonRpcError(code: Int, message: String, data: Maybe[Structure.Value])` (JsonRpcError.scala:11). | The engine decodes errors into `JsonRpcError` via Schema; data is `Absent` for CDP. `Abort.run[JsonRpcError]` at the `CdpBackend` boundary translates to `BrowserProtocolErrorException`. |
| Per-connection in-flight cap; `cdpMeter: Meter`, constant 8 (CdpClient.scala:38, 206, 345) | `Config.maxInFlight: Maybe[Int] = Absent` (JsonRpcEndpoint.scala:95). | Set `maxInFlight = Present(8)`. The Meter cleanup the engine owns. |
| Drain signal; `inFlight.getAndIncrement` + `drainSignal: AtomicRef[Fiber.Promise]` swap on 0->1, 1->0 (CdpClient.scala:69-110) | `JsonRpcEndpoint.awaitDrain: Unit < Async` (JsonRpcEndpoint.scala:54). | Identical mechanism in engine; `LifecycleTest` cases for the swap move to engine-side tests (already present in kyo-jsonrpc test suite). |
| Per-call timeout; `Async.timeout(requestTimeout)(exchange(mkWire))` (CdpClient.scala:102) | `Config.requestTimeout: Duration = Duration.Infinity` (JsonRpcEndpoint.scala:96). | Set from `LaunchConfig.requestTimeout` (Browser.scala:3113 = 60s). Engine raises `Closed` on timeout; recover to `BrowserConnectionLostException` at the `CdpBackend` boundary. |
| WebSocket transport; `HttpClient.webSocket(wsUrl, HttpHeaders.empty) { ws => relay sender/receiver }` (CdpClient.scala:230-265) plus `connectReady` gate (line 238) | `JsonRpcHttpTransport.webSocket(url, headers, codec)` (JsonRpcHttpTransport.scala:6-10) returning `JsonRpcTransport < (Async & Scope & Abort[HttpException])` | Direct replacement. The connectReady gate is implicit in the bridge: the WS body opens the channels and `init` returns the transport reference, with the background fiber feeding inbound/outbound. The HEAD impl (JsonRpcHttpTransport.scala:44-94) does NOT surface a typed connect-ready failure (see RI-002). |
| Schema for params/result; `derives Schema` on every CDP case class (CdpTypes.scala:122-453) | Unchanged; `JsonRpcEndpoint.call[In: Schema, Out: Schema]` consumes the same Schema. | Pure no-op. |
| Last-evaluate-params diagnostic; `lastEvaluateParams: AtomicRef[Maybe[String]]` set on every `Runtime.evaluate` (CdpClient.scala:36, 64-67, 342) | ABSENT (kyo-browser-only) | Keep this on a new kyo-browser-owned wrapper around `JsonRpcEndpoint` (call it `CdpClient` still); update the `.call(method, params, …)` site to also write `Json.encode(params)` to the ref when `method == RuntimeEvaluateMethod`. Used by `CdpClientLifecycleTest.scala:749-818`. |
| Wire-payload Base64 decoding; `CdpBase64Decode` (CdpWire.scala:15-33) | ABSENT (correctly CDP-specific) | Stays. |
| Exception stack formatting; `ExceptionDetailsFormat.format` (CdpWire.scala:44-62) | ABSENT (correctly CDP-specific) | Stays. |
| Error-string classification; `CdpErrorStrings` (CdpErrorStrings.scala:9-19) | ABSENT (correctly CDP-specific) | Stays. |

## Gaps & risks

### 1. Per-sessionId notification dispatch path (semi-resolved)

`CdpClient.decodeCdpMessage` (CdpClient.scala:398-502) routes
`Page.javascriptDialogOpening`, `Runtime.executionContext{Created,Destroyed}`,
`Page.downloadWillBegin`, `Page.downloadProgress` per-sessionId via dispatcher
tables. In the jsonrpc world the engine dispatches by method name only
(JsonRpcMethod.scala:64-79, `JsonRpcEndpointImpl.dispatch` matches against a
`methodMap: Map[String, JsonRpcMethod[S]]`), but the CDP codec puts unknown
top-level fields (including `sessionId`) into `Maybe[Structure.Value]` extras
(`JsonRpcCodecImpl.scala:199-202`), and `HandlerCtx.extras` (HandlerCtx.scala:17)
surfaces them at the handler. So a single registered handler per CDP method
that reads sid from `ctx.extras` and dispatches to a kyo-browser-owned map
implements the same routing.

**Verification needed**: confirm that for `Notification` envelopes the engine
actually populates `HandlerCtx.extras` from the decoded envelope before
invoking the handler. The HandlerCtx field exists; the wiring through
`JsonRpcEndpointImpl` notification dispatch needs spot-check (RI-001).

### 2. Native CDP transport

Question from the task spec: "Native CDP transport; does it currently use
WebSocket via kyo-http, or something else?" Search of
`kyo-browser/native/src/main/scala` returns only
`BrowserLauncherPlatform.scala` (27 LoC, shared with JVM via `jvm-native/`).
There is no `native/` source under
`kyo-browser/native/src/main/scala/kyo/internal/` apart from a test file. So
the CDP transport is **fully shared**; `CdpClient.scala` calls
`HttpClient.webSocket(wsUrl, …)` (line 245) which dispatches into kyo-http's
cross-platform NIO / native epoll-kqueue stack documented in memory under
"kyo-http (current)". No Native-specific transport to port.

This means a single replacement; `JsonRpcHttpTransport.webSocket` (in
`kyo-jsonrpc-http`, which already depends on kyo-http); covers JVM and
Native; for JS it's the same kyo-http JS backend. **However**, this introduces
a new dependency on `kyo-jsonrpc-http` from `kyo-browser`. Today
`kyo-browser/build.sbt` (verify) depends on kyo-http directly; adding
kyo-jsonrpc-http is a build-graph change. See RI-003.

### 3. WebSocket connect-ready failure surfacing

`CdpClient` uses a `connectReady: Fiber.Promise[Unit, Abort[BrowserReadException]]`
(CdpClient.scala:238) completed inside the ws block or with the connect
failure, gating `init`'s return. Without this gate, init returns a "live"
CdpClient pointing at a dead WS, and the first request hangs.

`JsonRpcHttpTransport.webSocket` (JsonRpcHttpTransport.scala:44-94) opens the
ws in a `Fiber.initUnscoped` and returns the transport handle immediately;
the WS connect failure lands in an inner `Abort.run[HttpException]` whose
`andThen(Abort.run[Closed](inbound.close).unit)` closes the inbound channel
but does NOT propagate a typed connect failure to the caller. So if Chrome
crashes between `pollDevToolsActivePort` returning a URL and the WS
connect, the kyo-browser caller would see a `Closed` on the first `call(...)`
not an upfront `BrowserSetupException`.

This is a real semantic regression unless mitigated. Options: (a) have
`JsonRpcHttpTransport.webSocket` synchronously await ws-open before returning
(matching `CdpClient.connectReady`); (b) keep the kyo-browser-side gate by
issuing a dummy notification or `Target.getTargets` call right after
`init` and treating any `Closed` there as `BrowserSetupException`. See
RI-004.

### 4. Fire-and-forget on close ("wedge under load")

Per memory note [[project_cdp_wedge.md]] the wedge is Chrome-side not
transport-side, so this is not a regression risk for the port. The
`sendUnmatched` API (JsonRpcEndpoint.scala:23) cleanly maps the dialog
drainer's negative-id pattern (CdpClient.scala:324-340).

### 5. Custom CDP error envelope shape

CDP error: `{code: Int, message: String}` (CdpClient.scala:617). JSON-RPC 2.0
error: `{code: Int, message: String, data: Maybe[Structure.Value]}`
(JsonRpcError.scala:11). The kyo-jsonrpc CDP codec decodes the strict CDP
error into a `JsonRpcError` with `data = Absent` (per
JsonRpcCodecImpl.scala:107-108 / 232-233: Structure.decode[JsonRpcError]).
Decoding succeeds for CDP wire shape because the JsonRpcError Schema treats
`data` as `Maybe[…] = Absent`. **One risk**: kyo-schema rejects strict-shape
mismatches by default (e.g. CDP sometimes emits `error: "string"` as a
string-not-object; the audit's BACKPORT #2 motivation). The HEAD codec
returns `JsonRpcEnvelope.Malformed(id, "error field is not a Record", raw)`
for that case (JsonRpcCodecImpl.scala:104-105 / 229-230), and the engine
routes Malformed-with-id to the awaiting caller, so the awaiting promise
fails with the malformed reason rather than hanging; equivalent to the
fallbackDecode behavior. Verify by porting `CdpClientDecoderTest` cases
that drive this path (RI-005).

### 6. Notification handlers and effect rows

`JsonRpcMethod.notification[In, S](name)(handler)` requires
`(Async & Abort[JsonRpcError]) <:< S` and the handler returns `Unit < S`. The
CDP per-session handlers today have shape `CdpEvent.Generic => Unit < Sync`
(BrowserTab.scala:89, Browser.scala:2522). The new handlers need to widen to
`Async` and need to accept the engine's `HandlerCtx`. The handler bodies
themselves (e.g. `BrowserTabSetup.updateFrameContexts`) already use
`AtomicRef.updateAndGet` which is `< Sync ⊆ Async`, so this is mechanical.

## Acceptance criteria

- All 60 shared test files (1308 test cases) plus the 4 platform-specific
  test files pass byte-equivalent on JVM, JS, and Native (per
  [[feedback_all_platforms_all_tests]]).
- `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala`,
  `CdpWire.scala`, and `CdpBackend.scala` no longer define wire envelope
  case classes (`CdpEnvelope`, `CdpNoParams`, `CdpWireMessage`, `CdpReply`,
  `CdpEventParams`, `FallbackIdEnvelope`), the `CdpError` ADT, or any
  Exchange / id-allocation / in-flight-metering / drain-signal /
  decode-and-route reader code. The `CdpEvalEnvelope` helper still exists,
  but its body decodes `EvalResult` directly from the typed call result, not
  from a raw wire string.
- `kyo-browser/shared/src/main/scala/kyo/internal/` no longer imports
  `Channel`, `Exchange`, `Meter`, `Fiber.Promise`, or `AtomicInt` for the
  purposes of wire framing / id allocation / drain signalling (uses for
  in-page state like `dialogIdCounter` legitimately stay only if used as
  `sendUnmatched` id source).
- `CdpClient` (or its successor wrapper) exposes the SAME shape to call
  sites: `send[P: Schema](method, params): String < (Async &
  Abort[BrowserReadException])` (or a richer typed `call`), `withSession`,
  `close(gracePeriod)`, `closeNow`, `awaitDrain`, the per-session dispatcher
  registration fields used by `BrowserTab.scala:93` / `Browser.scala:2157,
  2193, 2533`, and the `lastEvaluateParams` diagnostic AtomicRef referenced
  by `CdpClientLifecycleTest.scala:749, 811`.
- `MutationSettlement`, `NavigationWatcher`, `StabilitySampler`,
  `Actionability`, `BrowserNetworkTracker`, `SharedChrome`, `BrowserLauncher`,
  `CdpErrorStrings`, `CdpWire.{CdpBase64Decode, ExceptionDetailsFormat,
  CdpEvalDecoder}`, `Resolver`, `Selector`, `IFrame`, `ProbesJs`,
  `JsStringUtil`, `Image`, `Key`, `KeyModifiers`, `CookieWire`, `BrowserTab`,
  `BrowserAssertion`, `BrowserEval`, `BrowserSnapshot`, `ChromeDownloader`,
  `cdp.PageDownload`, `cdp.Accessibility` are unchanged in behavior; the
  per-session dispatcher table layout in `CdpClient` survives even if its
  internals change. Tests under `kyo-browser/shared/src/test/scala/kyo/internal/`
  for these modules pass without test-body edits.
- `JVM`, `JS`, `Native` test counts unchanged; no demotion of any test from
  `shared/` to a platform folder.

## Open questions

- **RI-001**: Does `JsonRpcEndpointImpl` populate `HandlerCtx.extras` from
  the decoded envelope's `extras: Maybe[Structure.Value]` BEFORE invoking a
  `JsonRpcMethod.notification` handler? `HandlerCtx.extras` is declared
  (`HandlerCtx.scala:17`) and the CDP codec produces extras during decode
  (`JsonRpcCodecImpl.scala:199-202, 222, 225, 234`); the gap is whether the
  notification dispatch path wires the two. [needs flow-resolve-open]
  Citation: `JsonRpcEndpointImpl.scala` is 1288 LoC and was not fully read in
  this pass; a targeted grep for `Notification` + `HandlerCtx` will resolve.

- **RI-002**: Does `JsonRpcHttpTransport.webSocket` need an upfront-connect
  gate? The HEAD impl (JsonRpcHttpTransport.scala:44-94) starts the ws in a
  background fiber and returns the transport handle synchronously, so a
  failed connect surfaces only on the first `transport.send` /
  `transport.incoming` interaction. For kyo-browser, where `BrowserLauncher`
  has already returned a URL Chrome wrote to disk, the most common failure
  mode is "Chrome died between launch and connect"; we want a typed
  upfront error there, not a hang. Options: (a) thread a synchronous
  connect-ready promise through `JsonRpcHttpTransport.webSocket`; (b) do a
  no-op kyo-browser-side probe call right after `JsonRpcEndpoint.init` and
  recover `Closed` to `BrowserSetupException`. [needs user input on
  value-judgment]

- **RI-003**: Build graph; `kyo-browser/build.sbt` currently depends on
  kyo-http (per memory `[[project_kyo_js_facade.md]]` and the
  `HttpClient.webSocket` use). The port adds a dep on `kyo-jsonrpc-http`
  (which itself depends on kyo-http + kyo-jsonrpc). Confirm
  `kyo-jsonrpc-http` is published on JS and Native (or that its sources are
  shared/cross-platform); per BACKPORT #5 in the seed audit it was meant to
  be cross-platform from day one. Spot-check the build file structure.
  [needs flow-resolve-open]

- **RI-004**: Does the port retain the JVM-only `unixDomain` transport
  variant that landed in Phase 04 of the kyo-jsonrpc protocol-coverage
  campaign (task #45)? CDP is WS-only, so probably not; but if a future
  user wanted to run kyo-browser against a unix-domain CDP socket
  (Chromium's `--remote-debugging-pipe` mode), the engine already supports
  it. Defer decision; flag as future-work. [needs user input on
  value-judgment]

- **RI-005**: How many existing `CdpClient` / `CdpClientDecoder` /
  `CdpBackend` test cases need to be rewritten vs deleted vs kept-as-is?
  The wire-layer tests (`CdpClientDecoderTest`, `CdpClientLifecycleTest`,
  `CdpClientTest`, `CdpBackendTest`, `CdpParamsRoundTripTest`,
  `CdpTypesSchemaFailureTest`, `BrowserWireDecodeFailureTest`) total 90+
  cases that directly probe internals about to disappear. Some are
  duplicated by kyo-jsonrpc's own test suite (drain signal, in-flight cap,
  close gracePeriod, sendUnmatched, malformed routing); deleting those
  loses no real coverage. Others are CDP-specific (typed CdpBackend
  wrappers, RemoteObject discriminator decoding, Base64 decoding, dialog
  drainer integration) and stay. Need a per-test rule call. [needs
  flow-resolve-open]

- **RI-006**: How does the port surface the WS `BrowserReadException` error
  type? `CdpClient` today uses `Abort[BrowserReadException]` everywhere
  (CdpClient.scala:42-56, 210-220). `JsonRpcEndpoint.call` returns
  `Abort[JsonRpcError | Closed]` (JsonRpcEndpoint.scala:13). The new
  `CdpClient` adapter recovers `JsonRpcError` to
  `BrowserProtocolErrorException` and `Closed` to
  `BrowserConnectionLostException`. The `Abort.timeout` case
  (CdpClient.scala:96-99); does the engine raise `Closed` or a separate
  `Timeout` on `requestTimeout` exhaustion? Need to verify and route to
  `BrowserConnectionLostException` per existing kyo-browser shape.
  [needs flow-resolve-open]

- **RI-007**: `IdStrategy.SequentialInt` (IdStrategy.scala:6) allocates ids
  as `JsonRpcId.Num` with values from `0` upward. The CDP dialog drainer
  uses `Int.MinValue` upward as a SENTINEL-negative space (CdpClient.scala:325).
  Both spaces are disjoint as long as the main-path counter stays below
  ~2^31. `SequentialInt` semantics need spot-check: does it use Int or Long
  range? If Long, the disjointness still holds for any realistic test
  duration. [needs flow-resolve-open]

- **RI-008**: Cross-platform `JsonRpcHttpTransport`: `kyo-jsonrpc-http` lives
  at `CD/kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala`
  with no `shared/jvm/js/native` split visible. Is this module configured
  cross-platform in build.sbt, or is it JVM-only? Per audit BACKPORT #1 it
  should be cross-platform; per HEAD layout it might be JVM-only by
  default. Check `build.sbt`. [needs flow-resolve-open]

## Citations index

- CDP-1: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:23-40` (CdpClient constructor)
- CDP-2: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:42-56` (`send` / `sendUnit` shape)
- CDP-3: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:57-111` (submit + meter + drain bookkeeping)
- CDP-4: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:113-136` (withSession)
- CDP-5: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:138-189` (close / closeOrderly / awaitDrain)
- CDP-6: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:206` (maxInFlight = 8)
- CDP-7: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:209-367` (init / initUnscoped; relay, exchange, dialogDrainer, cdpMeter)
- CDP-8: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:298-311` (Exchange parameterisation)
- CDP-9: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:324-340` (dialog drainer fire-and-forget with negative id)
- CDP-10: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:369-380` (eventWhitelist)
- CDP-11: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:398-502` (decodeCdpMessage routing)
- CDP-12: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:542-557` (fallbackDecode)
- CDP-13: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:561-617` (CdpEnvelope, CdpNoParams, CdpWireMessage, CdpReply, CdpEventParams, FallbackIdEnvelope, CdpError, CdpEvent)
- CDP-14: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:619-627` (CdpSender)
- CDP-15: `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:25-44` (decodeOrFail)
- CDP-16: `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:46-227` (28 typed wrappers)
- CDP-17: `kyo-browser/shared/src/main/scala/kyo/internal/CdpWire.scala:15-33` (CdpBase64Decode)
- CDP-18: `kyo-browser/shared/src/main/scala/kyo/internal/CdpWire.scala:44-62` (ExceptionDetailsFormat)
- CDP-19: `kyo-browser/shared/src/main/scala/kyo/internal/CdpWire.scala:68-101` (CdpEvalEnvelope)
- CDP-20: `kyo-browser/shared/src/main/scala/kyo/internal/CdpWire.scala:108-195` (CdpEvalDecoder)
- CDP-21: `kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala:122-453` (all params/result schemas + RemoteObject discriminator)
- CDP-22: `kyo-browser/shared/src/main/scala/kyo/internal/CdpErrorStrings.scala:9-19`
- STAB-1: `kyo-browser/shared/src/main/scala/kyo/internal/BrowserNetworkTracker.scala:22-52` (installerScript)
- STAB-2: `kyo-browser/shared/src/main/scala/kyo/internal/BrowserNetworkTracker.scala:66-88` (responseTrackerScript)
- STAB-3: `kyo-browser/shared/src/main/scala/kyo/internal/BrowserNetworkTracker.scala:99-126` (waitForNetworkIdleFor)
- STAB-4: `kyo-browser/shared/src/main/scala/kyo/internal/MutationSettlement.scala:35-69` (afterAction; quiescence regimes)
- STAB-5: `kyo-browser/shared/src/main/scala/kyo/internal/MutationSettlement.scala:82-100` (installObserver)
- STAB-6: `kyo-browser/shared/src/main/scala/kyo/internal/StabilitySampler.scala:48-69` (sampleWindow)
- STAB-7: `kyo-browser/shared/src/main/scala/kyo/internal/NavigationWatcher.scala:36-93` (armAround / armAroundNavigation / armInternal)
- STAB-8: `kyo-browser/shared/src/main/scala/kyo/internal/Actionability.scala:54-92` (Actionability.check / buildJs)
- STAB-9: `kyo-browser/shared/src/main/scala/kyo/internal/Actionability.scala:487-497` (withRetry)
- STAB-10: `kyo-browser/shared/src/main/scala/kyo/internal/SharedChrome.scala:35-78` (init / ensureStarted)
- STAB-11: `kyo-browser/shared/src/main/scala/kyo/internal/BrowserLauncher.scala:22-29` (launch)
- STAB-12: `kyo-browser/shared/src/main/scala/kyo/internal/BrowserLauncher.scala:115-130` (killOrphans)
- STAB-13: `kyo-browser/shared/src/main/scala/kyo/internal/BrowserLauncher.scala:184-211` (pollDevToolsActivePort)
- STAB-14: `kyo-browser/jvm-native/src/main/scala/kyo/internal/BrowserLauncherPlatform.scala:11-25` (registerShutdownHook)
- TAB-1: `kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala:19-36` (BrowserTab fields + `session = client.withSession(sessionId)`)
- TAB-2: `kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala:84-96` (installFrameContextTracker registration into client.frameEventDispatchers)
- BR-1: `kyo-browser/shared/src/main/scala/kyo/Browser.scala:258` (CdpClient.init use)
- BR-2: `kyo-browser/shared/src/main/scala/kyo/Browser.scala:2157-2194` (dialogHandlers / dialogRecorders registration)
- BR-3: `kyo-browser/shared/src/main/scala/kyo/Browser.scala:2510-2553` (onDownload → downloadEventDispatchers)
- BR-4: `kyo-browser/shared/src/main/scala/kyo/Browser.scala:3113-3114` (LaunchConfig defaults: requestTimeout = 60s, closeGrace = 30s)
- JR-1: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:7-77` (class JsonRpcEndpoint surface)
- JR-2: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:89-107` (Config + init)
- JR-3: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:6-10` (trait JsonRpcTransport)
- JR-4: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala:14-57` (sealed trait + Kind + factory methods)
- JR-5: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala:7-26` (envelope ADT)
- JR-6: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala:9-29` (id ADT + Schema)
- JR-7: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala:11-40` (error ADT)
- JR-8: `kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala:4-17` (opaque type + factory)
- JR-9: `kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala:14-33` (HandlerCtx surface incl. extras)
- JR-10: `kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala:4-8`
- JR-11: `kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala:10-75`
- JR-12: `kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala:5-35`
- JR-13: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala:9-17` (codec trait + presets)
- JR-14: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala:120-241` (Cdp codec encode/decode; reserved-keys check at lines 7-8, 161-169)
- JR-15: `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:6-107` (webSocket adapter)
- AUDIT-1: `kyo-jsonrpc/research/CDP-vs-kyo-browser.md` (seed audit, BACKPORT/MIGRATION lists; cited under `concurrent-imagining-stroustrup` paths but content matches HEAD)
