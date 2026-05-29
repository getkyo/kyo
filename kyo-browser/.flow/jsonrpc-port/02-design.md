# 02 Design: Port kyo-browser CDP client to kyo-jsonrpc

Task type: migration
Cites exploration: ./01-exploration.md

## Goal

Make `kyo-browser` consume `kyo-jsonrpc` for ALL JSON-RPC wire framing,
codec dispatch, request correlation, per-call timeout, in-flight metering,
drain signal, graceful close, sendUnmatched fire-and-forget, and malformed-
envelope routing. The in-tree primitives that today live in
`kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala`,
`CdpBackend.scala`, and `CdpWire.scala` get deleted or shrunk to a thin
CDP-shaped wrapper (`CdpBackend`) and CDP-domain decoders
(`CdpBase64Decode`, `ExceptionDetailsFormat`, `CdpEvalDecoder`). The
public `kyo.Browser` / `kyo.BrowserException` surface stays
byte-identical, all 1308 shared + 4 platform-specific test cases stay
green on JVM, JS, and Native, and every test-stability and speed
optimization the kyo-browser test suite uses (mutation
settlement, navigation watcher, stability sampler, network tracker,
SharedChrome reuse, BrowserLauncher cleanup, actionability fast path,
resolver retry, JsStringUtil caching) survives byte-equivalent because
those primitives sit above the wire layer and only call
`BrowserEval.evalJs` / `evalJsAwaiting`.

## Background

The exploration in `./01-exploration.md` established three load-bearing
facts. First, every BACKPORT item the seed audit
`kyo-jsonrpc/research/CDP-vs-kyo-browser.md` flagged has already
shipped in the `kyo-jsonrpc protocol coverage` campaign (tasks #34 /
#42-#46): `JsonRpcCodec.Cdp` (`JsonRpcCodecImpl.scala:120`),
`IdStrategy.SequentialInt` (`IdStrategy.scala:6`), `Config.maxInFlight`
(`JsonRpcEndpoint.scala:95`), `close(gracePeriod)`
(`JsonRpcEndpoint.scala:68`), `sendUnmatched` (`JsonRpcEndpoint.scala:23`),
`Config.cancellation = Absent` default (`JsonRpcEndpoint.scala:91`),
`ExtrasEncoder` (`ExtrasEncoder.scala:4`), `HandlerCtx.extras`
(`HandlerCtx.scala:17`), `JsonRpcEnvelope.Malformed` with recoverable
id (`JsonRpcEnvelope.scala:25`, `JsonRpcCodecImpl.scala:228-230`), and
the cross-platform `JsonRpcHttpTransport.webSocket`
(`kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:6`,
`build.sbt:742-755` confirms JS/JVM/Native crossProject). Second, the
test-stability layer (`MutationSettlement`, `NavigationWatcher`,
`StabilitySampler`, `BrowserNetworkTracker`, `Actionability`,
`SharedChrome`, `BrowserLauncher`, `Resolver`, `Selector`,
`JsStringUtil`, `CdpErrorStrings`, the in-page polling loops) all run
INSIDE Chrome via `Runtime.evaluate(awaitPromise=true)` and only
interact with the wire through `BrowserEval.evalJs` /
`evalJsAwaiting`; the port is invisible to them. Third, the
notification dispatch RI-001 is resolved by reading the engine impl:
`JsonRpcEndpointImpl.scala:911-916` constructs
`new HandlerCtx(cancelledUnsafe.safe, Absent, env.extras, Absent)`
when invoking a `JsonRpcMethod.notification` handler, and the CDP
codec routes every non-reserved top-level field (including
`sessionId`) into `extras` (`JsonRpcCodecImpl.scala:199-202`). So a
single `JsonRpcMethod.notification` per CDP event-method name,
combined with `ctx.extras` lookup, replaces the bespoke
`decodeCdpMessage` routing.

## Architecture overview

The new layering, top to bottom:

```
                                   Public surface (UNCHANGED)
+-------------------------------------------------------------------+
| kyo.Browser, kyo.BrowserException                                 |
| (Browser.scala 3509 LoC; BrowserException.scala 416 LoC)          |
+----------------------------+--------------------------------------+
                             |
                             v
                Internal user-facing types (UNCHANGED in shape)
+-------------------------------------------------------------------+
| BrowserTab, BrowserSnapshot, BrowserEval, BrowserAssertion,       |
| BrowserLauncher, SharedChrome, IFrame, Image, Key, Selector,      |
| Resolver, Actionability, MutationSettlement, NavigationWatcher,   |
| StabilitySampler, BrowserNetworkTracker, ChromeDownloader,        |
| CookieWire, ProbesJs, JsStringUtil, cdp.PageDownload,             |
| cdp.Accessibility, CdpTypes, CdpErrorStrings                      |
+----------------------------+--------------------------------------+
                             |
                             v
            CdpBackend (THIS LAYER IS REWRITTEN)
+-------------------------------------------------------------------+
| - typed wrappers (28 CDP methods) -> endpoint.call[In, Out]       |
| - holds: endpoint, lastEvaluateParams: AtomicRef[Maybe[String]],  |
|   dialogHandlers / dialogRecorders / frameEventDispatchers /      |
|   downloadEventDispatchers : AtomicRef[Dict[String, _]],          |
|   dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],      |
|   dialogIdCounter: AtomicInt(Int.MinValue),                       |
|   sessionId: Maybe[SessionId] = Absent                            |
| - withSession(sid: SessionId): CdpBackend (shares all refs)       |
| - close(grace), closeNow, awaitDrain delegate to endpoint         |
+----------------------------+--------------------------------------+
                             |
                             v       JsonRpcMethod.notification handlers
                             |       registered for the 4 CDP events the
                             |       client routes per-session:
                             |         Page.javascriptDialogOpening
                             |         Runtime.executionContextCreated
                             |         Runtime.executionContextDestroyed
                             |         Page.downloadWillBegin
                             |         Page.downloadProgress
                             v
                  kyo.JsonRpcEndpoint (UNCHANGED)
+-------------------------------------------------------------------+
| call[In, Out](method, params, extras) ; notify ; sendUnmatched    |
| awaitDrain ; close(gracePeriod) ; closeNow                        |
| Config(codec = JsonRpcCodec.Cdp,                                  |
|        cancellation = Absent,                                     |
|        progress = Absent,                                         |
|        unknownMethod = UnknownMethodPolicy.minimal,               |
|        maxInFlight = Present(8),                                  |
|        requestTimeout = launchCfg.requestTimeout (= 60.seconds),  |
|        idStrategy = IdStrategy.SequentialInt)                     |
+----------------------------+--------------------------------------+
                             |
                             v
              JsonRpcTransport (the WS bridge instance)
+-------------------------------------------------------------------+
| JsonRpcHttpTransport.webSocket(HttpUrl, HttpHeaders.empty,        |
|                                JsonRpcCodec.Cdp)                  |
|   (kyo-jsonrpc-http subproject; uses kyo-http; cross-platform)    |
+----------------------------+--------------------------------------+
                             |
                             v
                  kyo-http NIO / native (UNCHANGED)
```

Session routing flows through `ExtrasEncoder`: every outbound
`call(method, params, extras)` from a session-scoped `CdpBackend`
attaches `ExtrasEncoder.const(Structure.Value.Record(Chunk("sessionId"
-> Structure.Value.Str(sid.value))))`. The CDP codec inlines this as a
top-level `sessionId` field (`JsonRpcCodecImpl.scala:160-169`), so
the wire bytes match what CDP already accepts. Inbound notifications
arrive at the engine's `JsonRpcMethod.notification` handler with
`ctx.extras = Present(Record(..."sessionId" -> Str(value)...))`
(`JsonRpcEndpointImpl.scala:911-916`). The handler extracts the
sessionId and demultiplexes into the kyo-browser-owned dispatcher
table (`frameEventDispatchers` / `downloadEventDispatchers` /
`dialogHandlers`). The `notify` callback (the
`JsonRpcMethod.notification[In, S](name)(handler)` argument) is the
seam where the dispatcher tables get consulted.

## API surface

This is a refactor: zero new public API. Every public symbol
(`kyo.Browser.*`, `kyo.BrowserException.*`) keeps its identical
signature. The internal API surface changes the SHAPE but not the
NAMES of the wrappers callers depend on. The contract below
enumerates the internal symbols touched.

- `private[kyo] final class CdpBackend(private[kyo] val endpoint: JsonRpcEndpoint, ...)` ; replaces the old `CdpClient` final class; consumes `JsonRpcEndpoint` rather than holding `exchange / outbound / inbound / relay / cdpMeter / drainSignal / inFlight` directly.
  Rationale: the dispatcher tables and `lastEvaluateParams` AtomicRef stay (used by `Browser.scala:2157, 2193, 2533` and `BrowserTab.scala:93` and `CdpClientLifecycleTest.scala:749, 811`); the wire plumbing moves into the endpoint.
  Source file: kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
  Test file:   kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala

- `private[kyo] def send[P: Schema](method: String, params: P)(using Frame): String < (Async & Abort[BrowserReadException])` ; CDP-call entry point used by the typed wrappers; recovers `JsonRpcError | Closed | Timeout` into `BrowserConnectionLostException` / `BrowserProtocolErrorException`.
  Rationale: existing wrappers in `CdpBackend.scala:46-227` already take a `CdpSender` (`CdpBackend.scala` lines 47, 51, 57, ...) and call `sender.send(...).map(decodeOrFail[...])`. Keeping the `send` signature byte-identical lets us delete `decodeOrFail` once the body returns the already-typed result via `endpoint.call[In, Out]`. We keep the String-returning `send` to minimise churn in `BrowserEval` / `Resolver` call sites that work on the raw wire today; the typed `send[In, Out]` companion sits beside it.
  Source file: kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
  Test file:   kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala

- `private[kyo] def send[P: Schema, R: Schema](method: String, params: P)(using Frame): R < (Async & Abort[BrowserReadException])` ; typed-result companion that consumes the engine's `JsonRpcEndpoint.call[In, Out]` directly. Used by the rewritten wrappers in `CdpBackend.scala:46-227`; replaces `sender.send(method, params).map(decodeOrFail[Result](_, method))`.
  Rationale: 25+ wrappers fold to two lines each; `decodeOrFail` disappears.
  Source file: kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
  Test file:   kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala

- `private[kyo] def withSession(sid: SessionId): CdpBackend` ; returns a session-scoped backend sharing all dispatcher tables and the underlying endpoint, with an `ExtrasEncoder` baked in for sessionId stamping. Mirrors `CdpClient.withSession` (`CdpClient.scala:118-136`).
  Rationale: every call site (`BrowserTab.scala:35`) uses this exact name. Keep it.
  Source file: kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
  Test file:   kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala

- `private[kyo] def close(gracePeriod: Duration)(using Frame): Unit < Async` ; delegates to `endpoint.close(gracePeriod)` then closes the kyo-browser-owned dialog queue.
  Source file: kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
  Test file:   kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala (renamed to CdpBackendLifecycleTest.scala in Phase 03)

- `private[kyo] def closeNow(using Frame): Unit < Async` ; delegates to `endpoint.closeNow` then dialog-queue close.
  Source file: kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
  Test file:   kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala

- `private[kyo] def awaitDrain(using Frame): Unit < Async` ; delegates to `endpoint.awaitDrain`.
  Source file: kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
  Test file:   kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala

- `private[kyo] val lastEvaluateParams: AtomicRef[Maybe[String]]` ; diagnostic AtomicRef set on every `Runtime.evaluate` call. Stays on `CdpBackend` as a kyo-browser-owned diagnostic field; not a kyo-jsonrpc concern.
  Source file: kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
  Test file:   kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala (lines 749-818)

- `private[kyo] object CdpBackend { def init(wsUrl: String, launchCfg: Browser.LaunchConfig)(using Frame): CdpBackend < (Async & Scope & Abort[BrowserReadException]); def initUnscoped(...)(using Frame): CdpBackend < (Async & Abort[BrowserReadException]) }` ; replaces `CdpClient.init` / `initUnscoped` (`CdpClient.scala:209-220`).
  Rationale: `Browser.scala:258, 273` calls `CdpClient.init`. The names `CdpClient.init` are kept as `CdpBackend.init` (the type rename is the only churn at the call sites; see Phase 02 / 05 wiring).
  Source file: kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
  Test file:   kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala (covers init too)

The internal type rename `CdpClient -> CdpBackend` is the natural
landing: today `CdpBackend` is a method-namespace object holding the
28 typed wrappers and a `CdpSender` trait; tomorrow it is the runtime
class that owns the endpoint, the dispatcher tables, the dialog
queue, and the typed wrappers. The single `CdpSender` trait is
deleted (the wrappers can directly call `backend.send` since there is
no longer a need to fake a sender in tests; the engine ships
`JsonRpcTransport.inMemory` and the codec can be exercised directly).

## Package surface verdicts

Every file under
`kyo-browser/shared/src/main/scala/kyo/*.scala` (i.e. NOT under
`kyo/internal/`) is the public `package kyo` surface. There are two:

- Browser.scala: PUBLIC ; user-facing `kyo.Browser` namespace (BR-1, BR-2, BR-3, BR-4). No changes to its public method signatures; only internal call-site adjustments where it constructs `CdpClient.init` (now `CdpBackend.init`) and where dispatcher-registration call sites refer to `tab.client.<dispatcherMap>` (now `tab.backend.<dispatcherMap>`).
- BrowserException.scala: PUBLIC ; user-facing exception ADTs (`BrowserReadException`, `BrowserProtocolErrorException`, `BrowserConnectionLostException`, `BrowserDecodingException`, `BrowserSetupException`). NO changes.

For every file under
`kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala`:

- internal/CdpClient.scala: DELETE ; the entire 627 LoC file dissolves into the new `CdpBackend.scala`. Reason: every primitive it owned (Exchange, inFlight, drainSignal, cdpMeter, relay, dialogDrainer, decodeCdpMessage, fallbackDecode, plus the seven envelope/event case classes `CdpEnvelope`, `CdpNoParams`, `CdpWireMessage`, `CdpReply`, `CdpEventParams`, `FallbackIdEnvelope`, `CdpError`, `CdpEvent`, `CdpSender`) is now owned by `kyo-jsonrpc` or trivially by `CdpBackend`.
- internal/CdpBackend.scala: SHRINK ; from 228 LoC (the typed wrappers + `decodeOrFail` helper + `CdpSender` parameter) to ~280 LoC owning the runtime class fields (dispatcher tables, dialog queue + drainer, sessionId + ExtrasEncoder helper, lastEvaluateParams, init), the 28 typed wrappers (now two lines each), the `withSession` / `close` / `closeNow` / `awaitDrain` methods, and the notification-handler bodies that demultiplex per-sessionId. Target: 280-330 LoC. The wrappers SHRINK because each `decodeOrFail[X]` peeling collapses to `endpoint.call[Params, X]`. The added LoC comes from the four CDP-event notification handlers and the init wiring. Net delta from CdpClient + CdpBackend: 627+228 = 855 LoC -> ~280-330 LoC, an ~60% reduction.
- internal/CdpWire.scala: SHRINK ; from 195 LoC to ~120 LoC. KEEP: `CdpBase64Decode` (lines 15-33), `ExceptionDetailsFormat` (44-62), `CdpEvalDecoder` (108-195). DELETE: `CdpEvalEnvelope.decodeEvalEnvelope` (lines 68-101) which peels `Json.decode[CdpReply[EvalResult]](wire)` from a raw wire string. The new `CdpBackend.evalCall` returns the already-typed `EvalResult` (via `endpoint.call[EvaluateParams, EvalResult]`), and the `CdpEvalDecoder.parseAndExtractEvalValue` callers (BrowserEval.evalJsChecked, NavigationWatcher.waitForLoad, etc.) shift from "give me the raw wire string and I'll parse" to "give me the typed `EvalResult` and I'll project". Concretely: `CdpEvalDecoder.extractEvalValue(env: EvalResult)` (line 157) already takes a typed `EvalResult`; `parseAndExtractEvalValue` (line 132) is the only function that peels the raw wire. It is replaced by a thin caller-side helper that takes an `EvalResult` directly (or `Abort.run`-converts the call-result error to the "" sentinel that `isUnreturnableValueError` (line 114) handled). Same for `decodeStringListReply` (line 186): the call sites already have a String from `evalJs` and stay String-shaped; this stays.
- internal/CdpTypes.scala: KEEP ; 452 LoC of opaque-type IDs and `derives Schema` shapes (CDP-21). Pure payload; no wire/dispatch logic; survives unchanged. Verdict: KEEP at 452 LoC.
- internal/CdpErrorStrings.scala: KEEP ; CDP-domain error substrings (CDP-22). Verdict: KEEP at 21 LoC.
- internal/cdp/PageDownload.scala: KEEP ; CDP `Page.setDownloadBehavior` wrapper + download-event schemas. Verdict: KEEP at 94 LoC. (The two notification handlers for `Page.downloadWillBegin` / `Page.downloadProgress` move into `CdpBackend` as `JsonRpcMethod.notification` registrations, but the `DownloadWillBeginWire` / `DownloadProgressWire` Schemas they consume stay in this file.)
- internal/cdp/Accessibility.scala: KEEP ; CDP-domain glue. Verdict: KEEP at 182 LoC.

For every NEW file introduced by the port:

- (none) ; the port adds NO new files. The new `CdpBackend` runtime class lives in the existing `internal/CdpBackend.scala`. The notification handlers are inline methods on the new class. The shrunk-down dispatcher logic is inline. Reason: zero net new files keeps the package surface trivially auditable, matches the exploration's KEEP/DELETE inventory, and avoids surprising the per-source-test rule.

Non-CDP internal files (the test-stability layer) are NOT touched by
the port; their verdicts are KEEP-AS-IS (cited under §10
preservation proof). For completeness:

- internal/Actionability.scala: KEEP ; in-page actionability check (STAB-8, STAB-9).
- internal/BrowserAssertion.scala: KEEP ; assertion DSL.
- internal/BrowserEval.scala: KEEP ; thin `evalJs` / `evalJsAwaiting` wrappers over `CdpBackend.send("Runtime.evaluate", ...)`.
- internal/BrowserLauncher.scala: KEEP ; Chrome spawn + DevToolsActivePort polling (STAB-11, STAB-12, STAB-13).
- internal/BrowserNetworkTracker.scala: KEEP ; in-page network-idle tracker (STAB-1, STAB-2, STAB-3).
- internal/BrowserSnapshot.scala: KEEP ; snapshot logic.
- internal/BrowserTab.scala: KEEP-with-rename ; `val client: CdpClient` field renamed to `val backend: CdpBackend` (single field-name change; one type rename). Otherwise byte-equivalent.
- internal/ChromeDownloader.scala: KEEP.
- internal/CookieWire.scala: KEEP.
- internal/IFrame.scala: KEEP.
- internal/Image.scala: KEEP.
- internal/JsStringUtil.scala: KEEP ; in-page JS-string escape (STAB).
- internal/Key.scala: KEEP.
- internal/KeyModifiers.scala: KEEP.
- internal/MutationSettlement.scala: KEEP ; body-rooted MutationObserver (STAB-4, STAB-5).
- internal/NavigationWatcher.scala: KEEP ; readyState + network-idle polling (STAB-7).
- internal/ProbesJs.scala: KEEP.
- internal/Resolver.scala: KEEP ; two-step resolve + retry-on-stale.
- internal/Selector.scala: KEEP.
- internal/SharedChrome.scala: KEEP ; process-wide Chrome reuse (STAB-10).
- internal/StabilitySampler.scala: KEEP ; in-page sampling loop (STAB-6).

## API delta (proof public surface is byte-identical)

The public `kyo-browser` API lives entirely in two files. The port
changes their INTERNAL callees but not their PUBLIC method signatures.

Browser.scala:
- All `def` / `val` / `enum` / `case class` symbols declared at top
  level of `object Browser` (and sub-objects like `Browser.LaunchConfig`,
  `Browser.DownloadBehavior`, etc.) keep identical signatures.
- Three call-site groups change body, not signature:
  - `Browser.run` at line 258, 273: today reads
    `CdpClient.init(wsUrl, launchCfg)`; becomes
    `CdpBackend.init(wsUrl, launchCfg)`. Effect row identical:
    `CdpClient < (Async & Scope & Abort[BrowserReadException])`
    becomes `CdpBackend < (Async & Scope & Abort[BrowserReadException])`.
    The change is the type name; the type is private[kyo] so no
    public surface affected.
  - `withDialogs.install` (line 2157), `withDialogs.recorded`
    (line 2193): today read `client.dialogHandlers` and
    `client.dialogRecorders`; become `tab.backend.dialogHandlers` /
    `tab.backend.dialogRecorders`. Field-name change on a
    private[kyo] type; not user-visible.
  - `onDownload` (line 2533): today reads
    `client.downloadEventDispatchers`; becomes
    `tab.backend.downloadEventDispatchers`. Same.

BrowserException.scala:
- No changes. Every exception case class keeps its identical shape:
  `BrowserReadException`, `BrowserProtocolErrorException`,
  `BrowserConnectionLostException`, `BrowserDecodingException`,
  `BrowserSetupException`. The error-routing in the new
  `CdpBackend.send` recovers `JsonRpcError -> BrowserProtocolErrorException`
  (`Abort.recover[JsonRpcError]`), `Closed -> BrowserConnectionLostException`
  (`Abort.recover[Closed]`), `Timeout -> BrowserConnectionLostException`
  (`Abort.recover[Timeout]`); same three branches that
  `CdpClient.submit` performs today (CdpClient.scala:93-104).

Proof technique: a class-A grep gate runs after Phase 02 cuts over
`Browser.scala`: `grep -nE 'class|object|def|val|enum|case class' kyo-browser/shared/src/main/scala/kyo/Browser.scala kyo-browser/shared/src/main/scala/kyo/BrowserException.scala` against pre-port HEAD and post-port HEAD must produce byte-identical output. Any drift fails the phase.

## Internal restructure

CdpClient.scala (deleted, 627 LoC). Each section's destination:

- Constructor fields (lines 24-39): `exchange`, `outbound`, `inbound`,
  `relay`, `inFlight`, `drainSignal`, `cdpMeter`, `requestTimeout`
  delete; their work moves into `JsonRpcEndpoint`. `dialogHandlers`,
  `dialogDrainer`, `dialogQueue`, `frameEventDispatchers`,
  `downloadEventDispatchers`, `dialogRecorders`, `lastEvaluateParams`,
  `sessionId` move to `CdpBackend`'s constructor.
- `send` / `sendUnit` (lines 42-55) -> new `CdpBackend.send` /
  `sendUnit` calling `endpoint.call`.
- `submit` (lines 57-111) DELETE; engine owns the meter, drain,
  timeout, recovery. Only the `lastEvaluateParams.set` call (lines
  64-67) survives, hoisted into the new `CdpBackend.send` body as the
  pre-`endpoint.call` step.
- `withSession` (lines 113-136) -> new `CdpBackend.withSession`,
  which clones the new `CdpBackend` with `Present(sid)` for the
  sessionId and bakes the `ExtrasEncoder` into every call.
- `close(gracePeriod)` / `closeNow` / `closeOrderly` / `awaitDrain`
  (lines 142-189) DELETE; engine provides identical surface. The new
  `CdpBackend.close(gracePeriod)` is `endpoint.close(gracePeriod).andThen(dialogQueue.close).andThen(dialogDrainer.interrupt)`.
- `maxInFlight = 8` (line 206) MOVE to `CdpBackend` as a
  `private[kyo] val` consumed by `Config.maxInFlight = Present(8)`.
- `init` / `initUnscoped` (lines 209-367) REWRITE: build the
  `JsonRpcTransport` via `JsonRpcHttpTransport.webSocket(wsUrl,
  HttpHeaders.empty, JsonRpcCodec.Cdp)`, then
  `JsonRpcEndpoint.init(transport, methods, config)`, where
  `methods` is the Seq of 5 `JsonRpcMethod.notification` handlers
  (one for each CDP event the client routes per-session) plus zero
  request methods (the client is purely a client, never a server),
  and `config` is the per-LaunchConfig instance. The `dialogIdCounter`
  AtomicInt and `dialogDrainer` Fiber stay (drainer now uses
  `endpoint.sendUnmatched` instead of `outbound.put`).
- `eventWhitelist` / `isWhitelistedEvent` (lines 375-380) DELETE;
  `UnknownMethodPolicy.minimal` (`UnknownMethodPolicy.scala`) drops
  unregistered notification methods. The five registered notifications
  cover exactly the whitelist plus the three dialog/frame events.
- `decodeCdpMessage` (lines 398-502) DELETE; engine dispatches by
  method-name. The body of each method-specific branch (dialog
  intercept, frame-context dispatch, download dispatch) moves into
  the corresponding `JsonRpcMethod.notification` handler.
- `recordDialogEvent` (lines 510-540) MOVE into `CdpBackend` as a
  private helper; called from the
  `Page.javascriptDialogOpening` notification handler.
- `fallbackDecode` (lines 542-557) DELETE; engine emits
  `JsonRpcEnvelope.Malformed(id, reason, raw)` for unparseable wire,
  and the engine routes Malformed-with-id to the awaiting caller's
  pending promise (`JsonRpcCodecImpl.scala:228-230`,
  `JsonRpcEndpointImpl.scala` malformed-routing path).
- `CdpEnvelope` (lines 564-569), `CdpNoParams` (line 572),
  `CdpWireMessage` (lines 578-583), `CdpReply` (lines 588-591),
  `CdpEventParams` (line 594), `JavascriptDialogOpeningParams` (lines
  599-604), `FallbackIdEnvelope` (line 609), `CdpEvent` (lines
  611-614), `CdpError` (line 617), `CdpSender` (lines 624-627)
  DELETE. Replacement:
  - `CdpEnvelope` -> `JsonRpcEnvelope.Request` (encoded by
    `JsonRpcCodec.Cdp`).
  - `CdpNoParams` -> the engine accepts `Structure.Value.Null` for
    "no params"; the wrappers that previously passed `CdpNoParams()`
    now pass `()` (Unit) with `Schema[Unit]` derived in kyo-schema, or
    a typed empty case class per the param Schema convention.
  - `CdpWireMessage` -> engine internal; engine peels the inbound
    envelope to `JsonRpcEnvelope.{Request, Notification, Response,
    Malformed}` via `JsonRpcCodec.Cdp`.
  - `CdpReply` -> engine peels `{result, error}` and returns the typed
    result directly from `endpoint.call[In, Out]`.
  - `CdpEventParams` -> notification handlers receive the typed
    `In` directly via `JsonRpcMethod.notification[In]`.
  - `JavascriptDialogOpeningParams` MOVE to `CdpBackend.scala`
    (still needed as the typed `In` for the
    `Page.javascriptDialogOpening` notification).
  - `FallbackIdEnvelope` -> engine handles via
    `JsonRpcEnvelope.Malformed(id, reason, raw)`.
  - `CdpEvent.Generic` MOVE to `CdpBackend.scala` (still the type
    that the kyo-browser dispatcher tables hold; the new
    notification handlers wrap their typed params into a
    `CdpEvent.Generic(method, Json.encode(params), sid)` for
    backward compatibility with the in-place dispatcher map). The
    dispatcher-table maps stay keyed by `String` (the sessionId
    value) and hold `CdpEvent.Generic => Unit < Sync` handlers, so
    existing `tab.client.frameEventDispatchers.updateAndGet(...)`
    call-sites in `BrowserTab.scala:93`, `Browser.scala:2533` keep
    their shape (modulo `client` -> `backend` rename).
  - `CdpError` -> `JsonRpcError(code, message, Absent)`.
  - `CdpSender` -> deleted. The 28 wrappers now take a `backend:
    CdpBackend` instead of a `sender: CdpSender`; the trait that
    abstracted them was only used to inject test fakes, and the test
    fakes are replaced by `JsonRpcTransport.inMemory` round-trips.

CdpBackend.scala (rewritten in place, from 228 LoC to ~280-330 LoC):

- `RuntimeEvaluateMethod` constant (line 18) STAYS.
- `decodeOrFail[A]` (lines 25-44) DELETE; the wrappers now call
  `backend.send[Params, Result](method, params)` and get the typed
  `Result` directly.
- 28 typed wrappers (lines 46-227) REWRITE to two-line bodies. Example:
  `private[kyo] def navigate(backend: CdpBackend, params: NavigateParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) = backend.send[NavigateParams, NavigateResult]("Page.navigate", params).unit`
  (today: `sender.send("Page.navigate", params).unit` ; same shape,
  same call site count, just different name).
- ADD: the runtime class `CdpBackend` (the type that today is
  `CdpClient`) with the surviving fields, `send`, `withSession`,
  `close`, `closeNow`, `awaitDrain`, and the 5 notification handlers
  as private methods.

CdpWire.scala (shrunk in place, from 195 LoC to ~120 LoC):

- `CdpBase64Decode` (lines 15-33) STAYS.
- `ExceptionDetailsFormat` (lines 44-62) STAYS.
- `CdpEvalEnvelope.decodeEvalEnvelope` (lines 68-101) DELETE; not
  needed once `BrowserEval` calls `backend.send[Params, EvalResult]`
  directly. The error-routing in
  `CdpEvalEnvelope.decodeEvalEnvelope` (mapping
  `JsonRpcError -> BrowserProtocolErrorException` for the eval
  pipeline) moves into the `BrowserEval` call site as an
  `Abort.recover` clause.
- `CdpEvalDecoder` object (lines 108-195) STAYS. Its
  `parseAndExtractEvalValue` (line 132) is the one function that
  peels a raw wire string; it gets a new typed companion
  `extractEvalValueFromCall(callResult: Result[BrowserReadException,
  EvalResult]): String < Abort[BrowserReadException]` that
  callers in `BrowserEval`, `Browser.tryAcceptCookies`,
  `NavigationWatcher.waitForLoad` cut over to. The old String-API
  `parseAndExtractEvalValue` survives until the final cutover phase
  to keep migration atomic per phase, then deletes in Phase 03.

CdpTypes.scala STAYS: all 50+ `derives Schema` case classes, opaque
IDs, and the flat-discriminator `RemoteObject` Schema survive. They
are the typed `In` / `Out` of `endpoint.call`; the port consumes them
unchanged.

## Wiring

Concrete pseudocode for `CdpBackend.initUnscoped`:

```scala
def initUnscoped(wsUrl: String, launchCfg: Browser.LaunchConfig)(using
    Frame
): CdpBackend < (Async & Abort[BrowserReadException]) =
    for
        // kyo-browser-owned mutable state (survives port)
        dialogHandlers           <- AtomicRef.init[Dict[String, (Boolean, String)]](Dict.empty)
        dialogQueue              <- Channel.initUnscoped[(Boolean, String, Maybe[SessionId])](16)
        frameEventDispatchers    <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
        downloadEventDispatchers <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
        dialogRecorders          <- AtomicRef.init[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]](Dict.empty)
        lastEvaluateParams       <- AtomicRef.init[Maybe[String]](Absent)
        dialogIdCounter          <- AtomicInt.init(Int.MinValue)

        // Build the kyo-jsonrpc transport. JsonRpcHttpTransport.webSocket
        // is Async & Scope & Abort[HttpException]; we recover HttpException
        // to BrowserSetupException at this boundary.
        url <- HttpUrl.parse(wsUrl) // Abort recovered to BrowserSetupException
        transport <- Abort.recover[HttpException] { e =>
            Abort.fail(BrowserSetupException(s"WS transport setup: ${e.getMessage}"))
        } {
            JsonRpcHttpTransport.webSocket(url, HttpHeaders.empty, JsonRpcCodec.Cdp)
        }

        // The 5 notification handlers (one per CDP event the client routes
        // per-session). Each reads sessionId from ctx.extras and demultiplexes
        // into the kyo-browser-owned dispatcher tables.
        dialogMethod <- Sync.defer(JsonRpcMethod.notification[JavascriptDialogOpeningParams, Async & Abort[JsonRpcError]](
            "Page.javascriptDialogOpening"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            handleDialogOpening(dialogHandlers, dialogQueue, dialogRecorders, params, sid)
        })

        frameCreatedMethod <- Sync.defer(JsonRpcMethod.notification[ExecutionContextCreatedParams, Async & Abort[JsonRpcError]](
            "Runtime.executionContextCreated"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchFrameEvent(frameEventDispatchers, "Runtime.executionContextCreated", params, sid)
        })

        frameDestroyedMethod <- Sync.defer(JsonRpcMethod.notification[ExecutionContextDestroyedParams, Async & Abort[JsonRpcError]](
            "Runtime.executionContextDestroyed"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchFrameEvent(frameEventDispatchers, "Runtime.executionContextDestroyed", params, sid)
        })

        downloadWillBeginMethod <- Sync.defer(JsonRpcMethod.notification[PageDownload.DownloadWillBeginWire, Async & Abort[JsonRpcError]](
            "Page.downloadWillBegin"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchDownloadEvent(downloadEventDispatchers, "Page.downloadWillBegin", params, sid)
        })

        downloadProgressMethod <- Sync.defer(JsonRpcMethod.notification[PageDownload.DownloadProgressWire, Async & Abort[JsonRpcError]](
            "Page.downloadProgress"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchDownloadEvent(downloadEventDispatchers, "Page.downloadProgress", params, sid)
        })

        config = JsonRpcEndpoint.Config(
            codec          = JsonRpcCodec.Cdp,                          // CdpCodec; no jsonrpc field; extras roundtrip
            cancellation   = Absent,                                    // CDP has no cancel wire form
            progress       = Absent,
            unknownMethod  = UnknownMethodPolicy.minimal,               // drops un-registered notifications
            gate           = Absent,
            maxInFlight    = Present(CdpBackend.maxInFlight),           // = 8
            requestTimeout = launchCfg.requestTimeout,                  // = 60.seconds default
            idStrategy     = IdStrategy.SequentialInt,                  // Int range; positive ids
            progressResetsTimeout = false
        )

        endpoint <- JsonRpcEndpoint.init(
            transport,
            Seq(dialogMethod, frameCreatedMethod, frameDestroyedMethod,
                downloadWillBeginMethod, downloadProgressMethod),
            config
        )

        // Dialog drainer fiber: drains dialogQueue and uses endpoint.sendUnmatched
        // with a NEGATIVE id to fire-and-forget Page.handleJavaScriptDialog.
        // Negative ids do not collide with SequentialInt's positive allocator.
        dialogDrainer <- Fiber.initUnscoped {
            Abort.run[Closed] {
                dialogQueue.stream().foreach { case (accept, promptText, sessionId) =>
                    dialogIdCounter.getAndIncrement.map { id =>
                        val extras = sessionId match
                            case Present(sid) => ExtrasEncoder.const(
                                Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value)))
                            )
                            case Absent => ExtrasEncoder.empty
                        Abort.run[Closed](
                            endpoint.sendUnmatched(
                                "Page.handleJavaScriptDialog",
                                HandleJavaScriptDialogParams(accept, promptText),
                                JsonRpcId.Num(id.toLong),
                                extras
                            )
                        ).unit
                    }
                }
            }.unit
        }

    yield new CdpBackend(
        endpoint              = endpoint,
        dialogHandlers        = dialogHandlers,
        dialogDrainer         = dialogDrainer,
        dialogQueue           = dialogQueue,
        frameEventDispatchers = frameEventDispatchers,
        downloadEventDispatchers = downloadEventDispatchers,
        dialogRecorders       = dialogRecorders,
        lastEvaluateParams    = lastEvaluateParams,
        sessionId             = Absent
    )
```

The `CdpBackend.send` body:

```scala
private[kyo] def send[P: Schema, R: Schema](method: String, params: P)(using
    Frame
): R < (Async & Abort[BrowserReadException]) =
    val record =
        if method == CdpBackend.RuntimeEvaluateMethod
        then lastEvaluateParams.set(Present(Json.encode(params)))
        else Kyo.unit
    val extras = sessionId match
        case Present(sid) => ExtrasEncoder.const(
            Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value)))
        )
        case Absent => ExtrasEncoder.empty
    record.andThen {
        Abort.recover[Closed] { (closed: Closed) =>
            Abort.fail(BrowserConnectionLostException(s"Connection lost: ${closed.getMessage}", Present(closed)))
        } {
            Abort.recover[JsonRpcError] { (err: JsonRpcError) =>
                Abort.fail(BrowserProtocolErrorException(method, err.message))
            } {
                endpoint.call[P, R](method, params, extras)
            }
        }
    }
```

`CdpBackend.withSession`:

```scala
private[kyo] def withSession(sid: SessionId): CdpBackend =
    new CdpBackend(
        endpoint, dialogHandlers, dialogDrainer, dialogQueue,
        frameEventDispatchers, downloadEventDispatchers,
        dialogRecorders, lastEvaluateParams,
        sessionId = Present(sid)
    )
```

`CdpBackend.close(gracePeriod)`:

```scala
private[kyo] def close(gracePeriod: Duration)(using Frame): Unit < Async =
    endpoint.close(gracePeriod)
        .andThen(Abort.run[Closed](dialogQueue.close).unit)
        .andThen(dialogDrainer.interrupt.unit)
```

## Per-sessionId routing strategy

CDP sends events with `sessionId` at the JSON envelope top level
alongside `method` and `params`:
`{"method":"Runtime.executionContextCreated","params":{...},"sessionId":"abc"}`.
The kyo-jsonrpc CDP codec
(`JsonRpcCodecImpl.scala:182, 199-202`) treats `sessionId` (any
field outside the reserved set `{id, method, params, result, error,
jsonrpc}`) as `extras`. So the decoded
`JsonRpcEnvelope.Notification(method = "Runtime.executionContextCreated",
params = Present(...), extras = Present(Record(...,"sessionId" -> Str("abc"),...)))`
arrives at the engine's notification dispatch (RESOLVED RI-001 against
`JsonRpcEndpointImpl.scala:911-916`). The engine constructs
`HandlerCtx(..., env.extras, ...)` and invokes the registered
notification handler with `(params, ctx)`.

Outbound: every `CdpBackend.send` and `CdpBackend.sendUnmatched`
attaches an `ExtrasEncoder` derived from the session-scoped
backend's `sessionId` field:

```scala
val extras = sessionId match
    case Present(sid) => ExtrasEncoder.const(
        Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value)))
    )
    case Absent => ExtrasEncoder.empty
```

`ExtrasEncoder.const` (`ExtrasEncoder.scala:11-12`) returns a
`JsonRpcId => Maybe[Structure.Value] < Sync` that ignores the id and
returns the constant record. The CDP codec's
`buildWithExtras` (`JsonRpcCodecImpl.scala:153-176`) inlines the
record's fields at the wire's top level, producing
`{"id":42,"method":"Page.navigate","params":{...},"sessionId":"abc"}`.

Inbound: each registered `JsonRpcMethod.notification[In]` handler
reads `ctx.extras` via a single helper:

```scala
private def readSessionIdFromExtras(extras: Maybe[Structure.Value]): Maybe[SessionId] =
    extras match
        case Present(Structure.Value.Record(fields)) =>
            Maybe.fromOption(fields.iterator.collectFirst {
                case ("sessionId", Structure.Value.Str(s)) => SessionId(s)
            })
        case _ => Absent
```

The handler then looks up the kyo-browser-owned dispatcher table
(`frameEventDispatchers` / `downloadEventDispatchers` /
`dialogHandlers`) by `sid.value` and invokes the registered
per-session callback. Today this lookup happens inline in
`CdpClient.decodeCdpMessage` (CdpClient.scala:436-490); after the
port it happens inside the notification handler body. Semantics are
byte-equivalent.

Crucially the dispatcher tables themselves stay in kyo-browser
(`AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]`), keyed
by `sessionId.value` ; same shape, same Sync-only Effect row, same
registration sites in `BrowserTab.scala:93` (frame), `Browser.scala:2157`
(dialog handlers), `Browser.scala:2193` (dialog recorders),
`Browser.scala:2533` (download dispatchers). Only the dispatch SITE
changes: from the engine-internal `decodeCdpMessage` to the
kyo-browser-owned notification handler bodies. Per-tab isolation is
preserved because the dispatcher map remains keyed by sessionId
string.

## The two semantic risks from exploration: resolution

### A. WS-connect failure surfacing (RI-002)

Today `CdpClient.connectReady`
(`CdpClient.scala:230-282`) is a `Fiber.Promise[Unit,
Abort[BrowserReadException]]` completed inside the `ws` block or
with the connect failure; `init` awaits it before returning. Without
this gate, init returns a "live" CdpClient pointing at a dead
WebSocket and the first request hangs.

`JsonRpcHttpTransport.webSocket` (`kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:6-94`)
opens the WS in a background `Fiber.initUnscoped` (line 44) and
returns the transport handle synchronously (line 94: `.map(_ => transport)`).
A failed connect lands in `Abort.run[HttpException]` at line 45 and
the `.andThen(Abort.run[Closed](inbound.close).unit)` at line 93
closes the inbound channel, but the caller never sees a typed
`HttpException`.

Decision: option (b) ; the kyo-browser-side init does a no-op probe
call right after `JsonRpcEndpoint.init` and converts any `Closed` to
`BrowserSetupException`. Rationale: (i) the kyo-jsonrpc team's
explicit design for `JsonRpcHttpTransport.webSocket` is async-open;
threading a synchronous-connect promise through the public
`JsonRpcHttpTransport.webSocket` signature would touch the engine's
public API for one specific caller's need. (ii) the no-op probe is
already exactly what `Target.getBrowserContexts` or
`Browser.getVersion` (a CDP-side trivial RPC) does in less than 10ms
on a live Chrome. (iii) the probe lives in kyo-browser, scoped to the
exact place where "WS dead at init time" is a meaningful
`BrowserSetupException`. The probe pseudocode:

```scala
// Inside CdpBackend.initUnscoped, after JsonRpcEndpoint.init returns.
// Probes the WS by issuing a Browser.getVersion call; recovers Closed to
// BrowserSetupException so callers see an upfront init failure rather than
// a delayed first-request hang.
val backend = new CdpBackend(...)
val probe: BrowserVersionResult < (Async & Abort[BrowserReadException]) =
    Abort.recover[Closed] { (closed: Closed) =>
        Abort.fail(BrowserSetupException(s"WS handshake failed: ${closed.getMessage}"))
    } {
        backend.send[BrowserGetVersionParams, BrowserVersionResult]("Browser.getVersion", BrowserGetVersionParams())
    }
probe.andThen(backend)
```

This adds ~5-15ms of init wall-clock on the happy path (one CDP
round-trip), entirely inside `BrowserLauncher`-protected
init time, against a Chrome process that has already written
`DevToolsActivePort` to disk and is by-construction warmed-up. Worth
the cost for the typed-error semantics. Not a regression: the existing
`CdpClient.connectReady` mechanism waits for `HttpClient.webSocket`'s
TCP+WS handshake; the probe accomplishes the same by sending a
known-fast CDP method and waiting for its reply, which proves both
the TCP+WS handshake AND the CDP-protocol roundtrip is live.

### B. `lastEvaluateParams` diagnostic AtomicRef (RI ownership)

This is a kyo-browser-only diagnostic field
(`CdpClient.scala:36, 64-67, 342`) recording the last
`Runtime.evaluate`'s params JSON for inspection by
`CdpClientLifecycleTest.scala:749-818`. kyo-jsonrpc deliberately
does not own such diagnostics. Decision: the field moves verbatim
into the new `CdpBackend` as `private[kyo] val lastEvaluateParams:
AtomicRef[Maybe[String]]`, set inside `CdpBackend.send` before the
`endpoint.call` (see the wiring above). Test moves from
`CdpClientLifecycleTest` to `CdpBackendLifecycleTest` (rename in
Phase 03). Behavior byte-equivalent.

## Test-stability optimizations: preservation proof

For every optimization the exploration §2 enumerated, the proof of
preservation:

- **BrowserNetworkTracker** (STAB-1, STAB-2, STAB-3): the in-page
  installer and response-tracker scripts (`BrowserNetworkTracker.scala:22-52,
  66-88`) and the
  `waitForNetworkIdleFor` polling loop (lines 99-126) run via
  `BrowserEval.evalJs`. `BrowserEval.evalJs` calls
  `client.send("Runtime.evaluate", params)`; after the port, this
  becomes `backend.send[EvalParams, EvalResult]("Runtime.evaluate",
  params)`. Same wire bytes, same in-page semantics. Unchanged.

- **MutationSettlement** (STAB-4, STAB-5): the body-rooted
  MutationObserver and ref-counted shared lifecycle
  (`__kyoMutObsRef`), plus the single-eval `awaitQuiescence` and the
  two-regime polling (`MutationSettlement.scala:51-60`) all run
  INSIDE Chrome via one `Runtime.evaluate(awaitPromise=true)` call
  per "after-action". The wire-port changes the carrier under the
  evaluate call; the in-page logic is untouched. Unchanged.

- **NavigationWatcher** (STAB-7): `armAround` / `armAroundNavigation`
  / `armAroundReload` / `waitForNext` install an in-page recorder
  (`history.pushState` patch + `beforeunload`) and POLL readyState +
  network-idle via repeated `BrowserEval.evalJs` calls
  (`NavigationWatcher.scala:36-93`). Deliberately polling, not
  event-channel-based, so the CDP event channel stays free for
  downloads. The port preserves this: `armAround` polls via
  `evalJs`, and the only CDP event the port routes is the four
  notification methods we explicitly register; everything else is
  dropped by `UnknownMethodPolicy.minimal`. Unchanged.

- **StabilitySampler** (STAB-6): single-eval in-page sampling loop
  (`sampleWindow`, `StabilitySampler.scala:48-69`) returning a
  `SampleReply{tag, value}` JSON via one `evalJs` call. Alias-proof
  against transport latency because the in-page loop runs all
  round-trips against the same `setTimeout` clock inside Chrome.
  Port-invisible. Unchanged.

- **Actionability** (STAB-8, STAB-9): one-shot in-page actionability
  check (`Actionability.check`, line 54) merged into a single eval
  (`buildJs`, line 92), per-reason diagnostic enrichment,
  retry-on-stale (`withRetry`, line 487). All in-page; port-invisible.
  Unchanged.

- **SharedChrome** (STAB-10): process-wide cached Chrome URL
  (`SharedChrome.scala:35-78`). Each test creates its own
  CdpClient (now CdpBackend) against the same URL. The port preserves
  this: each CdpBackend instance gets its own `JsonRpcEndpoint`
  pointing at the shared WS URL via
  `JsonRpcHttpTransport.webSocket(url, ...)`. Unchanged.

- **BrowserLauncher** (STAB-11, STAB-12, STAB-13): `launch` (line 22)
  spawns Chrome and polls `DevToolsActivePort` for the WS URL;
  `killOrphans` (line 115) sweeps prior-run zombie processes. Pure
  process-management; the port does not touch this file. Unchanged.

- **BrowserLauncherPlatform** (jvm-native, js): shutdown hook /
  SIGKILL. Untouched. Unchanged.

- **JsStringUtil** (53 LoC): cross-platform percent-encoding /
  JS-string escape used by Resolver. Untouched. Unchanged.

- **Resolver** (242 LoC): `resolveOne` / `resolveAll` two-step
  (`Runtime.evaluate` returning objectId, then `DOM.requestNode`,
  `DOM.describeNode`). Retry-on-stale-node via `CdpErrorStrings`. All
  CDP calls go through `backend.send`; semantics identical.
  Unchanged.

- **Selector** (694 LoC): user-facing selector ADT + JS-template
  rendering. Pure data + JS-template logic; no wire interaction.
  Unchanged.

- **CdpClientLifecycle** observable invariants
  (`CdpClientLifecycleTest.scala`, 25 cases): `awaitDrain`,
  `close(gracePeriod)`, `closeNow`, drainSignal swap on
  0->1 / 1->0 transitions, `lastEvaluateParams` diagnostic,
  dialog drainer fire-and-forget invariant, frame-event dispatcher
  per-session isolation, download-event dispatcher add/remove. After
  the port:
  - drainSignal swap (0->1, 1->0) is implemented by the engine's
    `JsonRpcEndpointImpl` per audit row 7; kyo-jsonrpc's own test
    suite already covers this case for the engine. The
    `CdpClientLifecycleTest` cases that probe drainSignal directly
    on the `CdpClient` internal field are MOVED to engine-side tests
    (delete in kyo-browser, count adjusts in kyo-jsonrpc; net
    coverage equivalent).
  - `awaitDrain`, `close(gracePeriod)`, `closeNow`: identical surface
    on `CdpBackend`; tests stay, just retargeted from `client`
    field to `backend` field.
  - `lastEvaluateParams` diagnostic: identical surface on `CdpBackend`;
    tests stay.
  - Dialog-drainer fire-and-forget: identical via `sendUnmatched`;
    tests stay.
  - Frame-event / download-event dispatcher isolation: identical
    map shape; tests stay.

- **JsStringUtil caching**: caching is in-JS-template; no wire
  interaction. Unchanged.

Optimizations that DO touch the wire layer:

- **Per-connection in-flight cap of 8**: today via
  `cdpMeter: Meter` in `CdpClient`
  (CdpClient.scala:38, 206, 345). After port: via
  `Config.maxInFlight = Present(8)`; the engine wires
  `Meter.initSemaphoreUnscoped(8)` identically (per audit row 6).
  Semantics byte-equivalent.
- **Per-call timeout** (60s): today via `Async.timeout(requestTimeout)(exchange(mkWire))`
  (CdpClient.scala:102). After port: via
  `Config.requestTimeout = launchCfg.requestTimeout`; the engine
  raises `Timeout` on per-call expiration. `CdpBackend.send`
  recovers `Timeout` to `BrowserConnectionLostException` (same as
  today, CdpClient.scala:96-99). Semantics byte-equivalent.
- **drainSignal-on-close**: today via
  `inFlight.getAndIncrement` + `drainSignal` swap
  (CdpClient.scala:69-110). After port: engine implements identical
  mechanism (per audit row 7). Semantics byte-equivalent.
- **WS-connect-failure on init**: today via `connectReady`
  (CdpClient.scala:238, 270-274). After port: replaced by a
  kyo-browser-side `Browser.getVersion` probe inside
  `CdpBackend.initUnscoped` (see §9.A). Semantics: same typed
  upfront failure on dead WS.
- **Negative-id sentinel for fire-and-forget dialog responses**:
  today via `dialogIdCounter.getAndIncrement` from `Int.MinValue`
  + direct `outbound.put` (CdpClient.scala:324-340). After port:
  via `endpoint.sendUnmatched("Page.handleJavaScriptDialog", params,
  JsonRpcId.Num(negCounterValue))`. The engine's `sendUnmatched`
  bypasses the pending map and writes the wire with the
  caller-supplied id, so the negative id is preserved on the wire.
  `IdStrategy.SequentialInt` allocates positive Int ids
  for the main call path; the negative space stays disjoint. Per
  RI-007: `SequentialInt` allocates positive ids in Int range, so
  the disjointness holds for any realistic test duration. Resolved.

## Migration / cutover

Five phases plus a final sweep. Each phase is a single coherent
commit, compiles and tests green on all three platforms (JVM, JS,
Native), and follows feedback principles
(`feedback_no_backcompat`: no shims, no parallel surfaces;
`feedback_commit_between_phases`: phase commits between phases;
`feedback_all_platforms_all_tests`: every shared test stays in
`shared/`).

### Phase 01: Introduce kyo-jsonrpc / kyo-jsonrpc-http dependency, scaffold new CdpBackend runtime class behind feature parity

platforms: [jvm, js, native]

Build: add `.dependsOn(\`kyo-jsonrpc\`, \`kyo-jsonrpc-http\`)` to
`kyo-browser` in `build.sbt` (line 1166). Adjust
`kyo-jsonrpc-http`'s `CrossType.Pure` to match (or confirm; HEAD
line 745 is already Pure). All three platforms get the dep.

Scope: introduce the new `CdpBackend` runtime class with the new
field set (endpoint, dispatcher tables, dialogQueue, dialogDrainer,
lastEvaluateParams, sessionId), and the new `send` / `withSession`
/ `close` / `closeNow` / `awaitDrain` / `init` / `initUnscoped`
methods. Run the new code path BEHIND the existing `CdpClient` (do
not delete `CdpClient`). Make `CdpClient` a thin delegate that builds
the new `CdpBackend` and forwards every method to it; `CdpClient`'s
public-internal symbol (`private[kyo] final class CdpClient`)
becomes a `private[kyo] type CdpClient = CdpBackend` plus a one-line
companion `def init = CdpBackend.init`. NO scoped delegation; this is
a FINAL TYPE ALIAS pattern, which `feedback_no_type_aliases` forbids.
Decision: NOT a type alias; this phase introduces both `CdpBackend`
AND `CdpClient` as distinct classes for ONE phase only, with all
`CdpClient` call sites at this point still calling the OLD
`CdpClient` (which the phase preserves byte-equivalent). The new
`CdpBackend` runtime class compiles and is exercised by a NEW
test file `CdpBackendSmokeTest.scala` covering init,
send-and-receive, withSession, close, sendUnmatched, and the four
notification routes. The old `CdpClient` remains the production code
path in Phase 01.

Acceptance:
- `sbt kyo-browser/Test/compile` green on JVM, JS, Native.
- `sbt 'kyo-browser/Test/testOnly *CdpBackendSmokeTest*'` green on
  all three platforms.
- `git diff --stat HEAD~1 -- kyo-browser/shared/src/main/scala/kyo/` shows ONLY
  CdpBackend.scala (modified) and build.sbt (1 line). No other
  source file touched.
- Full suite (all 1308 shared + 4 platform tests) remains green on
  all three platforms (validates Phase 01 introduced no regression).

### Phase 02: Cut over production call sites; delete CdpClient.scala

platforms: [jvm, js, native]

Scope:
- Rename `BrowserTab.client: CdpClient` -> `BrowserTab.backend:
  CdpBackend`. Update all reads (`tab.client.dialogHandlers` ->
  `tab.backend.dialogHandlers`, etc.) across `Browser.scala`,
  `BrowserTab.scala`, `BrowserSnapshot.scala`, `BrowserEval.scala`,
  `BrowserAssertion.scala`, `Actionability.scala`,
  `MutationSettlement.scala`, `NavigationWatcher.scala`,
  `StabilitySampler.scala`, `BrowserNetworkTracker.scala`,
  `Resolver.scala`, `ChromeDownloader.scala`, `cdp/PageDownload.scala`,
  `cdp/Accessibility.scala`, and every test file.
- Rewrite each typed wrapper in `CdpBackend.scala:46-227`: replace
  `sender.send("X", params).map(decodeOrFail[Y](_, "X"))` with
  `backend.send[X, Y]("X", params)`.
- Delete `CdpClient.scala` (627 LoC).
- Delete `decodeOrFail` (CdpBackend.scala:25-44) and the `CdpSender`
  trait it consumed.
- Delete the dummy `CdpClient.init` companion that Phase 01 left
  behind.

Acceptance:
- `sbt kyo-browser/Test/compile` green on JVM, JS, Native.
- `git diff` does NOT touch
  `kyo-browser/shared/src/main/scala/kyo/Browser.scala`'s public
  surface: byte-identical output from
  `grep -nE 'class|object|def|val|enum|case class' Browser.scala BrowserException.scala`
  vs pre-port HEAD.
- Full suite (1308 shared + 4 platform) GREEN on all three platforms
  with sole exception of the lifecycle / wire-decoder tests that
  Phase 03 will move/delete.
- `grep -l 'CdpClient' kyo-browser/` returns ZERO matches (the old
  symbol is gone).

### Phase 03: Delete CdpWire wire-envelope helpers; rename / delete wire-layer tests

platforms: [jvm, js, native]

Scope:
- Delete `CdpEvalEnvelope.decodeEvalEnvelope` (CdpWire.scala:68-101).
- Update `CdpEvalDecoder.parseAndExtractEvalValue` to take a
  `Result[BrowserReadException, EvalResult]` (or to be invoked on
  the call site after `Abort.run[BrowserReadException]`); deletes
  the raw-wire-string path.
- Update `BrowserEval.evalJs` and `evalJsAwaiting` to call
  `backend.send[EvalParams, EvalResult]("Runtime.evaluate", params)`
  directly and project via the typed decoder.
- Rename `CdpClientLifecycleTest.scala` (1262 LoC, 25 cases) to
  `CdpBackendLifecycleTest.scala`. Adjust every reference:
  `CdpClient` -> `CdpBackend`; field paths (e.g. `client.inFlight`,
  `client.drainSignal`) that probed engine-internal state get
  ROUTED to engine-equivalent tests OR deleted with a note. Per
  exploration RI-005: 25 cases total in
  `CdpClientLifecycleTest`. Of those, the cases that probe
  drainSignal swap, inFlight counter, and cdpMeter behavior
  duplicate engine-side coverage in `kyo-jsonrpc`'s test suite
  (kyo-jsonrpc's `JsonRpcEndpointImpl` has lifecycle tests already);
  these are DELETED (~10 cases). The cases probing
  `lastEvaluateParams`, the dialog drainer's fire-and-forget
  invariant via `sendUnmatched`, frame-event dispatcher
  per-session isolation, and download-event dispatcher add/remove
  STAY in `CdpBackendLifecycleTest.scala` (~15 cases). NO test is
  silently dropped without explicit accounting in the phase commit.
- Delete or rewrite `CdpClientTest.scala` (15 cases) ; the
  `submit`'s `inFlight` counter and `cdpMeter` cap tests duplicate
  engine-side coverage. DELETE.
- Delete `CdpClientDecoderTest.scala` (179 LoC, 7 cases). The
  `decodeCdpMessage` event-routing branches are now four
  `JsonRpcMethod.notification` handlers, exercised in
  `CdpBackendLifecycleTest`. The fallback `id` recovery path is
  engine-internal (`JsonRpcCodecImpl.scala:228-230`) and tested
  there.
- Keep `CdpBackendTest.scala` (669 LoC, 41 cases). Rewrite the
  bodies (each test of a typed wrapper now uses
  `JsonRpcTransport.inMemory` round-trip to drive the wrapper end
  to end). Test count stays at 41.
- Keep `CdpParamsRoundTripTest.scala` (359 LoC, 15 cases): pure
  Schema round-trip, no wire dependency; bodies unchanged.
- Keep `CdpTypesTest.scala` (29 LoC, 5 cases) and
  `CdpTypesSchemaFailureTest.scala` (63 LoC, 6 cases): pure Schema
  tests; unchanged.
- Keep `CdpEvalDecoderTest.scala` (220 LoC, 15 cases): `RemoteObject`
  variant decoding; unchanged.
- Rewrite `BrowserWireDecodeFailureTest.scala` (117 LoC, 6 cases) to
  drive malformed wire through `JsonRpcTransport.inMemory` and
  observe the `JsonRpcEnvelope.Malformed` -> engine -> awaiting
  caller's `BrowserProtocolErrorException.decodeFailure` chain. Same
  6 cases, different driver. Count stays at 6.

Acceptance:
- `sbt kyo-browser/Test/compile` green on JVM, JS, Native.
- Per-phase test-count accounting committed: 25 - 10 (deleted, engine
  duplicates) + 0 (none added) = 15 in
  `CdpBackendLifecycleTest.scala`. 15 - 15 (deleted entirely) = 0 in
  `CdpClientTest.scala` (file deleted). 7 - 7 (deleted entirely) = 0
  in `CdpClientDecoderTest.scala` (file deleted). 41 + 0 = 41 in
  `CdpBackendTest.scala`. 15 + 0 = 15 in `CdpParamsRoundTripTest`.
  5 in `CdpTypesTest`. 6 in `CdpTypesSchemaFailureTest`. 15 in
  `CdpEvalDecoderTest`. 6 in `BrowserWireDecodeFailureTest`. Net
  shared test-case delta: -32 (engine duplicates / decoder-internals
  no longer kyo-browser's concern; coverage equivalent inside
  kyo-jsonrpc). The remaining 1276 cases stay byte-equivalent. The
  phase commit MUST include explicit accounting prose justifying
  every test deletion against engine-side equivalent coverage.
- All 1276 remaining shared + 4 platform test cases GREEN on JVM, JS,
  Native.
- `grep -lE 'CdpClient|CdpEnvelope|CdpWireMessage|CdpReply|CdpEventParams|FallbackIdEnvelope|CdpSender' kyo-browser/` returns ZERO matches.

### Phase 04: Defensive backports to kyo-jsonrpc-http if needed

platforms: [jvm, js, native]

Scope: contingency phase. After Phase 02 runs, if any kyo-browser
test reveals a kyo-jsonrpc-http transport behavior incompatible with
CDP semantics (e.g. binary-frame handling for `Page.captureScreenshot`
download messages; though the screenshot result actually arrives
inline in the CDP `result` field, not as a binary frame, so this is
unlikely), the fix lands here as a kyo-jsonrpc-http patch with its
own one-commit phase. Probability low. If unneeded, this phase
contributes zero commits and the campaign advances to Phase 05.

Acceptance: this phase is conditional. If executed, the patch lands
with its own kyo-jsonrpc-http test cases, and all 1280 (1276 +
contingency) shared tests pass on JVM, JS, Native.

### Phase 05: Cross-platform sweep, test-suite parity verification, final cleanup

platforms: [jvm, js, native]

Scope:
- Run the full test suite on JVM, JS, Native; expect 1276 shared +
  4 platform tests green (after Phase 03's documented deletions).
- Dead-code grep: `grep -rE 'CdpEvent\.Generic|CdpSender|decodeOrFail|CdpEvalEnvelope|CdpReply|CdpEnvelope' kyo-browser/`
  expect either zero matches or specifically the surviving
  `CdpEvent.Generic` (the dispatcher-handler payload type) in
  `CdpBackend.scala`.
- Unused-import sweep: `sbt 'kyo-browser/Compile/compile;
  kyo-browser/Test/compile'` with `-Wunused:imports`. Fix every
  surfaced warning.
- Run `sbt kyo-browserJVM/Test`, then `sbt kyo-browserJS/Test`,
  then `sbt kyo-browserNative/Test` sequentially
  (per `feedback_sequential_test_runs`; never in parallel due to
  Chrome contention).
- Confirm `tests/internal/CdpBackendTest`, `CdpEvalDecoderTest`,
  `CdpTypesTest`, `CdpTypesSchemaFailureTest`,
  `CdpParamsRoundTripTest`, `BrowserWireDecodeFailureTest`,
  `CdpBackendLifecycleTest` all pass on every platform.
- Update the `CLAUDE.md` and memory file references mentioning
  `CdpClient` to point at the new `CdpBackend`.

Acceptance:
- All shared + platform test counts match Phase 03's documented
  numbers exactly.
- `git diff --stat HEAD~5..HEAD` shows the campaign touched ONLY
  files in `kyo-browser/` and `build.sbt` (one line for the
  kyo-jsonrpc / kyo-jsonrpc-http dep). No drive-by changes to
  `kyo-jsonrpc/` or `kyo-jsonrpc-http/` or `kyo-http/` unless Phase
  04 fired.
- The campaign is shippable.

## Cross-cutting invariants

Items that hold across every phase, validated by `flow-verify` at
each phase boundary:

- INV-001: No new public `package kyo` types under `kyo-browser`.
  Produced by Phase 02 (when `BrowserTab.client` -> `.backend`,
  the rename is `private[kyo]`). Consumed by Phase 05's package
  audit.
- INV-002: `kyo.Browser` / `kyo.BrowserException` public method
  signatures byte-identical across Phases 01-05. Produced by Phase 02
  and 03. Consumed by Phase 05's grep audit.
- INV-003: every test in `kyo-browser/shared/src/test/scala/kyo/`
  stays in `shared/`; no demotion to `jvm/`, `js/`, or `native/`
  (per `feedback_all_platforms_all_tests`). Produced by Phase 03.
  Consumed by Phase 05.
- INV-004: `// flow-allow: <rationale>` for every `AllowUnsafe`,
  `var`, manual JSON, or other audit-flag exception, per
  `feedback_no_unsafe` / `feedback_no_manual_json`. Produced by every
  phase; consumed by `flow-strip-dev` at end.
- INV-005: every `Sync.defer`, `Atomic*.init`, `Channel.initUnscoped`
  call site has a documented rationale comment per Phase 02 onwards
  (existing convention).
- INV-006: no em-dashes anywhere in the diff
  (per `feedback_no_em_dashes`). Produced by every phase; consumed
  by `flow-validate`'s LLM-tells gate.
- INV-007: no `derives Json`; CDP wire payloads use `derives Schema`
  + `Json.encode / Json.decode` per `feedback_no_manual_json`
  (already the convention).
- INV-008: Rule 8c (source + matching test in the same phase
  commit); every Phase that adds or rewrites a source file commits
  the matching test in the same commit.
- INV-009: phases must each be a green-build commit (the project
  literally `sbt compile`-and-`sbt Test`-green between phases).
- INV-010: per-platform test runs SEQUENTIAL (per
  `feedback_sequential_test_runs`); JVM, JS, Native one at a time
  during validation steps.
- INV-011: no Co-Authored-By in commit trailers
  (per `feedback_no_coauthor`).
- INV-012: no `git push` from the campaign agent
  (per `feedback_no_push`).
- INV-013: typed `Abort` recovery for `Closed` and `JsonRpcError` at
  the `CdpBackend` boundary; no panics escape into kyo-browser callers
  (per the existing exception-typing convention).
- INV-014: no `var` for shared state in `package kyo` /
  `package kyo.internal`; mutable state always inside `AtomicRef` /
  `AtomicInt` / `AtomicBoolean` (per `feedback_atomic_not_var`).
- INV-015: no `Fiber.block` anywhere (per `feedback_no_fiber_block`);
  awaits go through `safe.get` / `onComplete`.

## Risks & mitigations

Beyond the two semantic risks in §9:

- **CDP event routing behavior drift on dialog edge cases**. Today
  `CdpClient.decodeCdpMessage` (CdpClient.scala:436-444) UNCONDITIONALLY
  intercepts `Page.javascriptDialogOpening` and auto-dismisses with
  `(false, "")` defaults when no handler is registered. After port,
  the same intercept lives in the `Page.javascriptDialogOpening`
  notification handler body. The `Auto-dismiss when no handler
  registered` path is the test-stability-critical edge case (every
  test that triggers an alert without explicit dialog handling
  uses auto-dismiss to avoid hang). Mitigation: a dedicated
  `CdpBackendLifecycleTest` case in Phase 03 covering "alert with no
  registered handler is auto-dismissed within 100ms"; failing this
  is a phase-failure.

- **Lifecycle-test breakage (CdpClientLifecycleJvmTest)**: any JVM-only
  lifecycle quirks the existing JVM-platform test exercises (the
  fork-per-suite test grouping in `build.sbt:1180-1199` is JVM-only).
  Mitigation: rename `CdpClientLifecycleTest` to
  `CdpBackendLifecycleTest` in Phase 03; preserve all JVM lifecycle
  cases. Cross-platform parity verified by running the renamed file
  on JS and Native in Phase 05.

- **Native-specific transport edge cases (epoll/kqueue)**:
  `JsonRpcHttpTransport.webSocket` runs through kyo-http which on
  Native uses direct epoll/kqueue via FFI. The same `HttpClient.webSocket`
  primitive `CdpClient` uses today; the port replaces only the layer
  above it. No new Native-specific code; the existing Native CDP
  behavior is inherited via the same kyo-http transport. Mitigation:
  Phase 05 runs the suite on Native explicitly and ratifies counts.

- **WebSocket close-on-shutdown wedge memory note**: per memory
  `[[project_cdp_wedge.md]]` the wedge under load is Chrome-side,
  not transport-side. The port does not regress this: the new
  `CdpBackend.closeNow` follows the same close ordering (close
  endpoint -> close dialog queue -> interrupt dialog drainer). No
  new behavior. Mitigation: documented in INV-013.

- **`SequentialInt` id overflow vs negative dialog ids**: confirmed
  resolved in §10. The
  `IdStrategy.SequentialInt` engine
  (`IdStrategy.scala:6`, `kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala`)
  allocates positive Int ids; the dialog drainer uses
  `JsonRpcId.Num(negativeLong)` via `sendUnmatched`; the engine
  treats `sendUnmatched` ids as caller-supplied and writes them to
  the wire as-is. No overflow risk.

- **`HandlerCtx.extras` correctness in engine HEAD**: confirmed
  resolved by reading `JsonRpcEndpointImpl.scala:911-916`; the
  notification dispatch path constructs HandlerCtx with `env.extras`.
  RI-001 resolved.

- **Build-graph reachability**: kyo-jsonrpc-http is a cross-project
  with `crossProject(JSPlatform, JVMPlatform, NativePlatform)`
  and `CrossType.Pure` (`build.sbt:743-755`), so adding
  `.dependsOn(\`kyo-jsonrpc-http\`)` to kyo-browser (a `crossProject(JSPlatform,
  JVMPlatform, NativePlatform).crossType(CrossType.Full)` per
  `build.sbt:1162-1164`) is graph-clean across all platforms. RI-003
  resolved.

- **JVM-only `unixDomain` transport vs CDP**: kyo-jsonrpc-http
  ships an additional `JsonRpcHttpTransport.unixDomain` adapter for
  JVM-only contexts (per audit BACKPORT #4 / task #45). The CDP port
  does NOT use it; Chrome's `--remote-debugging-pipe` mode is a
  future feature not in scope (per `feedback_no_scope_cuts`, this is
  not a deferral: it is the existing CDP scope, which is WS-only).
  RI-004 resolved as: no, the port retains nothing of `unixDomain`;
  the underlying kyo-jsonrpc-http surface still exposes it for
  future kyo-browser users who want pipe-mode, but kyo-browser's
  `CdpBackend.init` is WS-only as today.

- **Per-test test-count drift**: the campaign explicitly documents
  every deleted test case against an engine-side replacement; the
  Phase 03 commit message MUST enumerate every deletion with its
  engine-side equivalent test file. Mitigation: `flow-verify` at
  Phase 03 boundary validates the test-count accounting against
  the prose enumeration.

## Open questions

- Q-001 (carried from exploration RI-001): The notification dispatch
  populates `HandlerCtx.extras` from `env.extras`. RESOLVED at
  `JsonRpcEndpointImpl.scala:911-916`. [research-knowable / resolved
  pre-flow-resolve-open]
  context: ./01-exploration.md RI-001 ; `JsonRpcEndpointImpl.scala:911-916`.

- Q-002 (carried from exploration RI-002): WS-connect failure
  surfacing. The port adopts a kyo-browser-side `Browser.getVersion`
  probe in `CdpBackend.initUnscoped` to convert async-connect
  failures to typed `BrowserSetupException`. The design DOES NOT
  modify `JsonRpcHttpTransport.webSocket`. [value-underdetermined /
  RATIFIED (option b, kyo-browser-side probe, per no-backcompat).
  See `./03b-user-escalations.md` entry 1.]
  context: ./01-exploration.md RI-002 ; design §9.A.

- Q-003 (carried from exploration RI-003): kyo-jsonrpc-http
  cross-platform availability. RESOLVED ; the build at
  `build.sbt:742-755` declares `crossProject(JSPlatform, JVMPlatform,
  NativePlatform)` with `CrossType.Pure`. [research-knowable /
  resolved]
  context: ./01-exploration.md RI-003 ; `build.sbt:742-755`.

- Q-004 (carried from exploration RI-004): Should the port retain
  `unixDomain` transport? Decision in §11 / risks: NO, kyo-browser
  remains WS-only. [value-underdetermined / decided in design]
  context: ./01-exploration.md RI-004 ; design §11 Phase 04 and
  risks.

- Q-005 (carried from exploration RI-005): Per-test rewrite vs
  delete vs keep accounting. Decision in §11 Phase 03:
  `CdpClientLifecycleTest` -> `CdpBackendLifecycleTest` (rename + 10
  case-deletes for engine-duplicate coverage); `CdpClientTest` ->
  DELETE; `CdpClientDecoderTest` -> DELETE; `CdpBackendTest` ->
  KEEP+rewrite-bodies; `CdpParamsRoundTripTest` -> KEEP;
  `CdpTypesTest` / `CdpTypesSchemaFailureTest` -> KEEP;
  `CdpEvalDecoderTest` -> KEEP; `BrowserWireDecodeFailureTest` ->
  KEEP+rewrite-bodies. [value-underdetermined / decided in design]
  context: ./01-exploration.md RI-005 ; design §11 Phase 03.

- Q-006 (carried from exploration RI-006): Error type plumbing
  through the `CdpBackend.send` boundary. RESOLVED ;
  `Abort.recover[Closed]` -> `BrowserConnectionLostException`,
  `Abort.recover[JsonRpcError]` -> `BrowserProtocolErrorException`,
  `Abort.recover[Timeout]` -> `BrowserConnectionLostException`
  (same three branches `CdpClient.submit` performs at
  `CdpClient.scala:93-104`). [research-knowable / resolved]
  context: ./01-exploration.md RI-006 ; design §6 (`CdpBackend.send`)
  and `CdpClient.scala:93-104`.

- Q-007 (carried from exploration RI-007): `IdStrategy.SequentialInt`
  range. `IdStrategy.scala:6` declares `SequentialInt` as a sum-type
  variant; engine impl in
  `kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala`
  allocates positive ids. Disjointness from negative-id-sentinel
  space holds. [research-knowable / resolved]
  context: ./01-exploration.md RI-007 ; design §10
  ("Negative-id sentinel").

- Q-008 (carried from exploration RI-008): kyo-jsonrpc-http
  cross-platform build. RESOLVED at `build.sbt:743`. [research-knowable
  / resolved]
  context: ./01-exploration.md RI-008 ; `build.sbt:743`.

All open questions (Q-001 through Q-008) are resolved. Q-002 is
ratified per `./03b-user-escalations.md` entry 1.

## Validation hooks for flow-validate

- public API signatures listed in `## API surface` are the contract
  (the new internal `CdpBackend` surface; the public `kyo.Browser`
  surface stays unchanged).
- invariant candidates listed in `## Cross-cutting invariants` (INV-001
  through INV-015) feed `flow-invariants`'s ledger.
- `## Open questions` (Q-001 through Q-008) is the input to
  `flow-resolve-open` pass 1.
