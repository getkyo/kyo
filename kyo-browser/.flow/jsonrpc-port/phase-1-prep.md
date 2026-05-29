# Phase 1 prep

Phase name: Introduce CdpBackend runtime class behind feature parity
Files to produce: 2
Files to modify: 1 (build.sbt)
Tests: 24 (10 in CdpBackendSmokeTest.scala, 14 in JsonRpcPortInvariantsSpec.scala)
Plan cites: ./05-plan.md §Phase 01

---

## Verbatim API signatures

### JsonRpcEndpoint (kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala)

```scala
// Line 101-106
def init(
    transport: JsonRpcTransport,
    methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
    config: Config = Config()
)(using Frame): JsonRpcEndpoint < (Sync & Async & Scope) =
    internal.JsonRpcEndpointImpl.init(transport, methods, config).map(new JsonRpcEndpoint(_))
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:101-106`

There is NO `JsonRpcEndpoint.initUnscoped`. The only factory is `init`, which is Scope-bound. See "Gotchas" below for how `initUnscoped` in the plan pseudocode absorbs the Scope.

```scala
// Line 9-14 (call on endpoint instance)
def call[In: Schema, Out: Schema](
    method: String,
    params: In,
    extras: ExtrasEncoder = ExtrasEncoder.empty
)(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
    impl.call[In, Out](method, params, extras)
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:9-14`

```scala
// Line 16-21
def notify[In: Schema](
    method: String,
    params: In,
    extras: ExtrasEncoder = ExtrasEncoder.empty
)(using Frame): Unit < (Async & Abort[Closed]) =
    impl.notify[In](method, params, extras)
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:16-21`

```scala
// Line 23-29
def sendUnmatched[In: Schema](
    method: String,
    params: In,
    id: JsonRpcId,
    extras: ExtrasEncoder = ExtrasEncoder.empty
)(using Frame): Unit < (Async & Abort[Closed]) =
    impl.sendUnmatched[In](method, params, id, extras)
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:23-29`

```scala
// Line 68
def close(gracePeriod: Duration)(using Frame): Unit < Async = impl.close(gracePeriod)
// Line 61
def close(using Frame): Unit < Async = impl.close(Duration.Zero)
// Line 75
def closeNow(using Frame): Unit < Async = impl.close(Duration.Zero)
// Line 54
def awaitDrain(using Frame): Unit < Async = impl.awaitDrain
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:54-75`

```scala
// Line 89-99 (Config)
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
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:89-99`

### JsonRpcMethod (kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala)

```scala
// Line 48-57
def notification[In: Schema, S](name: String)(handler: (In, HandlerCtx) => Unit < S)(
    using
    Frame,
    (Async & Abort[JsonRpcError]) <:< S
): JsonRpcMethod[S]
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala:48-57`

Handler signature: `(In, HandlerCtx) => Unit < S` where `S` must satisfy `(Async & Abort[JsonRpcError]) <:< S`.
The call wraps this in `Sync.defer(JsonRpcMethod.notification[...](name) { (params, ctx) => ... })` to suspend construction.

```scala
// Line 29-39
def apply[In: Schema, Out: Schema, S](name: String)(handler: (In, HandlerCtx) => Out < S)(
    using Frame,
    (Async & Abort[JsonRpcError]) <:< S
): JsonRpcMethod[S]
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala:29-39`

```scala
// Line 63-79
def dispatch[S](
    name: String,
    methods: Seq[JsonRpcMethod[S]],
    params: Structure.Value,
    ctx: HandlerCtx
)(using Frame, (Async & Abort[JsonRpcError]) <:< S): Maybe[Structure.Value < (Async & Abort[JsonRpcError])]
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala:63-79`

### JsonRpcHttpTransport (kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala)

```scala
// Line 6-10
def webSocket(
    url: HttpUrl,
    headers: HttpHeaders = HttpHeaders.empty,
    codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
)(using Frame): JsonRpcTransport < (Async & Scope & Abort[HttpException])
```

at `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:6-10`

Note: returns `< (Async & Scope & Abort[HttpException])`. The Scope and Abort[HttpException] must both be discharged. See "Gotchas" for how the plan handles this.

### JsonRpcCodec.Cdp (kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala)

```scala
// Line 16
val Cdp: JsonRpcCodec = internal.JsonRpcCodecImpl.Cdp
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala:16`

CDP wire shape: no `jsonrpc` field; `id` is a plain integer or string; fields outside the reserved set `{id, method, params, result, error, jsonrpc}` are treated as `extras`. On encode, `extras` fields are flattened at the top level of the wire object (see `buildWithExtras` at `JsonRpcCodecImpl.scala:153-176`). On decode, all fields NOT in the reserved set are collected into `extras = Present(Structure.Value.Record(unknownFields))` (see `JsonRpcCodecImpl.scala:199-202`).

### ExtrasEncoder (kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala)

```scala
// Line 4
opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync

// Line 9
val empty: ExtrasEncoder = (_: JsonRpcId) => Absent

// Line 11-12
def const(extras: Structure.Value): ExtrasEncoder =
    (_: JsonRpcId) => Present(extras)
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala:4-16`

Canonical sessionId injection:
```scala
ExtrasEncoder.const(
    Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value)))
)
```

### JsonRpcEnvelope.Malformed (kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala)

```scala
// Line 25
case Malformed(id: Maybe[JsonRpcId], reason: String, raw: Structure.Value)
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala:25`

### IdStrategy.SequentialInt (kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala)

```scala
// Line 6
case SequentialInt
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala:6`

Produces positive 32-bit integer ids (1, 2, 3, ...). Disjoint from the negative-id space that `dialogIdCounter` (starting at `Int.MinValue`) uses for fire-and-forget dialog responses (INV-018).

### JsonRpcTransport.inMemory (kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala)

```scala
// Line 18-31
def inMemory(capacity: Int)(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync
def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync  // capacity = 64
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:18-31`

Returns `(a, b)` where `a.send` goes to `b.incoming` and vice versa.

---

## HandlerCtx surface

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala:14-18
final class HandlerCtx private[kyo] (
    val cancelled: Fiber.Promise[Unit, Sync],
    val requestId: Maybe[JsonRpcId],
    val extras: Maybe[Structure.Value],
    private[kyo] val progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
)
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala:14-18`

For notification handlers, the engine constructs `HandlerCtx` at `JsonRpcEndpointImpl.scala:910-916`:

```scala
// Lines 904-916
// Unsafe: Promise.Unsafe.init for cancelled signal on notification handlers
val cancelledUnsafe =
    Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger)
val ctx =
    new HandlerCtx(
        cancelledUnsafe.safe,
        Absent,          // notifications have no requestId
        env.extras,      // *** the extras field carries sessionId for CDP notifications ***
        Absent           // no progressSink for notifications
    )
```

at `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:904-916`

Phase 01 notification handlers read `ctx.extras` to extract sessionId via `readSessionIdFromExtras`. For notifications, `requestId` is always `Absent`.

---

## CDP wire envelope

`JsonRpcCodec.Cdp` (from `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala`):

**Reserved keys** (line 8):
```scala
private val cdpReservedKeys: Set[String] =
    Set("id", "method", "params", "result", "error", "jsonrpc")
```

**Encode** (lines 122-176): Request envelope writes `{id, method, params, ...extras}` with no `jsonrpc` field. The `buildWithExtras` helper (line 153) inlines `extras` fields at the top level:
```scala
// lines 160-169
case Present(Structure.Value.Record(extraFields)) =>
    val badKey = extraFields.iterator.map(_._1).find(cdpReservedKeys.contains)
    badKey match
        case Some(key) => Abort.fail(JsonRpcError.invalidRequest(s"extras key '$key' is reserved"))
        case None      => Sync.defer(Structure.Value.Record(base ++ extraFields))
```
For `sessionId`, the record `Chunk("sessionId" -> Str("abc"))` merges at the top level: `{"id":1,"method":"...","params":{...},"sessionId":"abc"}`.

**Decode** (lines 178-241): Any field not in `cdpReservedKeys` is collected into `extras`:
```scala
// lines 199-202
val unknownFields = fields.filter((k, _) => !known.contains(k))
val extras: Maybe[Structure.Value] =
    if unknownFields.isEmpty then Absent
    else Present(Structure.Value.Record(unknownFields))
```
Inbound notification `{"method":"Runtime.executionContextCreated","params":{...},"sessionId":"abc"}` yields `JsonRpcEnvelope.Notification(method, Present(params), Present(Record(Chunk("sessionId" -> Str("abc")))))`.

at `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala:120-243`

---

## File anchors

### kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala (the existing object)

Lines 1-228 (full file). This file currently contains only `private[kyo] object CdpBackend` with `decodeOrFail` (lines 25-44), `RuntimeEvaluateMethod` constant (line 18), and 28 typed wrapper methods (`getNavigationHistory`, `navigate`, `navigateToHistoryEntry`, `reload`, `getFrameTree`, `captureScreenshot`, `printToPDF`, `runtimeEvaluate`, `setDeviceMetricsOverride`, `clearDeviceMetricsOverride`, `dispatchKeyEvent`, `dispatchMouseEvent`, `getProperties`, `getDocument`, `requestNode`, `describeNodeByNodeId`, `describeNodeByBackendId`, `getBoxModel`, `setFileInputFiles`, `getCookies` (2 overloads), `setCookie`, `deleteCookies`, `getTargets`, `attachToTarget`, `createTarget`, `createBrowserContext`, `closeTarget`, `disposeBrowserContext`).

**Phase 01 action**: Transform this file to three top-level definitions:
1. `final private[kyo] class CdpBackend` (new runtime class)
2. `private[kyo] object CdpBackend` (companion with `init`/`initUnscoped` + helpers)
3. `private[kyo] object CdpBackendOld` (the existing 228 LoC object renamed verbatim from `object CdpBackend`)

The rename is annotated: `// flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02`

at `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:1-228`

### kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala

Key lines for the 5 notification handlers (currently in `decodeCdpMessage`):

- `Page.javascriptDialogOpening` route (lines 425-443):
```scala
if method == "Page.javascriptDialogOpening" then
    dialogHandlers.use { handlers =>
        val sidKey                               = sid.fold("")(_.value)
        val handlerOpt: Maybe[(Boolean, String)] = handlers.get(sidKey)
        val (accept, promptText)                 = handlerOpt.getOrElse((false, ""))
        Abort.run[Closed](dialogQueue.offer((accept, promptText, sid))).andThen {
            recordDialogEvent(dialogRecorders, sidKey, paramsStr, handlerOpt, accept, promptText)
        }
    }.andThen(Exchange.Message.Skip)
```

- `Runtime.executionContextCreated` / `Runtime.executionContextDestroyed` route (lines 445-458):
```scala
else if method == "Runtime.executionContextCreated" || method == "Runtime.executionContextDestroyed" then
    val ev = CdpEvent.Generic(method, paramsStr, sid)
    frameEventDispatchers.use { dispatchers =>
        sid match
            case Present(s) =>
                dispatchers.get(s.value) match
                    case Present(handler) => handler(ev)
                    case Absent           => Kyo.unit
            case Absent => Kyo.unit
    }.andThen(Exchange.Message.Skip)
```

- `Page.downloadWillBegin` / `Page.downloadProgress` route (lines 459-491):
```scala
else if isWhitelistedEvent(method) then
    val ev = CdpEvent.Generic(method, paramsStr, sid)
    if method == "Page.downloadWillBegin" || method == "Page.downloadProgress" then
        downloadEventDispatchers.use { dispatchers =>
            sid match
                case Present(s) =>
                    dispatchers.get(s.value) match
                        case Present(handler) =>
                            handler(ev).andThen(Exchange.Message.Skip)
                        case Absent =>
                            Exchange.Message.Push(ev)
                case Absent =>
                    Exchange.Message.Push(ev)
        }
```

Dialog drainer fiber (lines 326-340):
```scala
dialogDrainer <- Fiber.initUnscoped {
    Abort.run[Closed] {
        dialogQueue.stream().foreach { case (accept, promptText, sessionId) =>
            dialogIdCounter.getAndIncrement.map { id =>
                val wire = Json.encode(CdpEnvelope(
                    id,
                    "Page.handleJavaScriptDialog",
                    HandleJavaScriptDialogParams(accept, promptText),
                    sessionId.map(_.value)
                ))
                Abort.run[Closed](outbound.put(wire)).unit
            }
        }
    }.unit
}
```

`lastEvaluateParams` write site (line 66):
```scala
if method == CdpBackend.RuntimeEvaluateMethod then lastEvaluateParams.set(Present(Json.encode(params)))
else Kyo.unit
```

`dialogIdCounter` init (line 325):
```scala
dialogIdCounter <- AtomicInt.init(Int.MinValue)
```

`lastEvaluateParams` init (line 342):
```scala
lastEvaluateParams <- AtomicRef.init[Maybe[String]](Absent)
```

### kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala

Frame-event dispatcher registration (lines 84-95 in `installFrameContextTracker`):
```scala
private[kyo] def installFrameContextTracker(tab: BrowserTab)(using Frame): Unit < (Sync & Scope) =
    val key = tab.sessionId.value
    val handler: CdpEvent.Generic => Unit < Sync = ev =>
        if ev.method == "Runtime.executionContextCreated" || ev.method == "Runtime.executionContextDestroyed" then
            updateFrameContexts(tab, ev)
        else Kyo.unit
    tab.client.frameEventDispatchers.updateAndGet(_.update(key, handler)).andThen(
        Scope.ensure(tab.client.frameEventDispatchers.updateAndGet(_.remove(key)).unit)
    )
```

Frame context update logic (lines 104-134 in `updateFrameContexts`): decodes `ExecutionContextCreatedParams` from `ev.paramsJson` (the wire string) via `Json.decode[CdpEventParams[ExecutionContextCreatedParams]]`. In Phase 01, notification handlers receive typed params directly (no re-decode from wire needed); the handler dispatches a pre-encoded `CdpEvent.Generic` with `json = Json.encode(paramsValue)` so `updateFrameContexts` continues to work.

### kyo-browser/shared/src/main/scala/kyo/internal/CdpWire.scala

Lines 68-101 (`CdpEvalEnvelope.decodeEvalEnvelope`) and lines 108-195 (`CdpEvalDecoder`): these are KEPT in Phase 01, not touched. Only Phase 04 deletes `CdpEvalEnvelope` and the raw-JSON variant of `isUnreturnableValueError`.

Line 73: `Json.decode[CdpReply[EvalResult]](wire)` -- wire-layer decode site (alive through Phase 03).
Line 115: `Json.decode[CdpReply[EvalResult]](rawJson)` -- raw-JSON variant of `isUnreturnableValueError` (deleted Phase 04).
Line 135: `CdpEvalEnvelope.decodeEvalEnvelope(resultJson, "eval") {...}` -- eval decode via `parseAndExtractEvalValue` (deleted Phase 04).

### build.sbt

Current `kyo-browser` definition (lines 1161-1169):
```scala
lazy val `kyo-browser` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .withoutSuffixFor(JVMPlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-browser"))
        .dependsOn(`kyo-http`)
        .settings(
            `kyo-settings`
        )
```

Phase 01 change (line 1166): replace `.dependsOn(`kyo-http`)` with `.dependsOn(`kyo-http`, `kyo-jsonrpc`, `kyo-jsonrpc-http`)`.

at `build.sbt:1161-1169`

---

## 5 notification handlers to wire (Phase 01)

### 1. `Page.javascriptDialogOpening` (currently CdpClient.scala:425-443)

Current decode-and-route logic (verbatim):
```scala
if method == "Page.javascriptDialogOpening" then
    dialogHandlers.use { handlers =>
        val sidKey                               = sid.fold("")(_.value)
        val handlerOpt: Maybe[(Boolean, String)] = handlers.get(sidKey)
        val (accept, promptText)                 = handlerOpt.getOrElse((false, ""))
        Abort.run[Closed](dialogQueue.offer((accept, promptText, sid))).andThen {
            recordDialogEvent(dialogRecorders, sidKey, paramsStr, handlerOpt, accept, promptText)
        }
    }.andThen(Exchange.Message.Skip)
```

Phase 01 handler body wraps this logic into `handleDialogOpening`. Params type is `JavascriptDialogOpeningParams` (defined at `CdpClient.scala:599-604`):
```scala
final private[kyo] case class JavascriptDialogOpeningParams(
    url: String = "",
    message: String = "",
    `type`: String = "",
    defaultPrompt: Maybe[String] = Absent
) derives Schema
```
The handler receives typed `params: JavascriptDialogOpeningParams` directly (no re-decode from wire). The `recordDialogEvent` logic that re-decoded `CdpEventParams[JavascriptDialogOpeningParams]` from wire is replaced by direct use of the typed `params`.

### 2. `Runtime.executionContextCreated` (currently BrowserTab.scala:107-121)

Current decode-and-route logic (verbatim):
```scala
if method == "Runtime.executionContextCreated" then
    Json.decode[CdpEventParams[ExecutionContextCreatedParams]](wire) match
        case Result.Success(env) =>
            val ctx = env.params.context
            if ctx.auxData.isDefault && ctx.auxData.frameId.nonEmpty then
                tab.frameContexts
                    .updateAndGet(_.update(FrameId(ctx.auxData.frameId), ExecutionContextId(ctx.id)))
                    .unit
            else Kyo.unit
        case other =>
            Log.warn(s"BrowserTabSetup: unexpected wire shape decoding ExecutionContextCreatedParams: $other; raw=$wire")
```

Phase 01: notification handler receives typed `params: ExecutionContextCreatedParams` (or `Structure.Value` for generic dispatch). The handler dispatches via `dispatchFrameEvent` which re-encodes to `Json.encode(paramsValue)` for the `CdpEvent.Generic` that `updateFrameContexts` expects.

Params type at `CdpTypes.scala:382-384`:
```scala
final private[kyo] case class ExecutionContextCreatedParams(
    context: ExecutionContextDescription
) derives Schema
```

### 3. `Runtime.executionContextDestroyed` (currently BrowserTab.scala:122-133)

Current decode-and-route logic (verbatim):
```scala
else if method == "Runtime.executionContextDestroyed" then
    Json.decode[CdpEventParams[ExecutionContextDestroyedParams]](wire) match
        case Result.Success(env) =>
            val cid = ExecutionContextId(env.params.executionContextId)
            tab.frameContexts.updateAndGet { m =>
                m.filter((_, v) => v != cid)
            }.unit
        case other =>
            Log.warn(s"BrowserTabSetup: unexpected wire shape decoding ExecutionContextDestroyedParams: $other; raw=$wire")
```

Same dispatch shape as Created: `buildFrameMethod(frameEventDispatchers, "Runtime.executionContextDestroyed")`.

Params type at `CdpTypes.scala:387-389`:
```scala
final private[kyo] case class ExecutionContextDestroyedParams(
    executionContextId: Int
) derives Schema
```

### 4. `Page.downloadWillBegin` (currently CdpClient.scala:475-488, "download" branch)

Current route (verbatim, for `downloadWillBegin` sub-branch):
```scala
if method == "Page.downloadWillBegin" || method == "Page.downloadProgress" then
    downloadEventDispatchers.use { dispatchers =>
        sid match
            case Present(s) =>
                dispatchers.get(s.value) match
                    case Present(handler) =>
                        handler(ev).andThen(Exchange.Message.Skip)
                    case Absent =>
                        Exchange.Message.Push(ev)
            case Absent =>
                Exchange.Message.Push(ev)
    }
```

Phase 01: `buildDownloadWillMethod` registers `JsonRpcMethod.notification[PageDownload.DownloadWillBeginWire, ...]("Page.downloadWillBegin")`. Params type at `cdp/PageDownload.scala:73-77`:
```scala
final private[kyo] case class DownloadWillBeginWire(
    guid: String,
    url: String,
    suggestedFilename: String
) derives Schema
```

### 5. `Page.downloadProgress` (currently CdpClient.scala:475-488, same branch)

Phase 01: `buildDownloadProgressMethod` registers `JsonRpcMethod.notification[PageDownload.DownloadProgressWire, ...]("Page.downloadProgress")`. Params type at `cdp/PageDownload.scala:82-87`:
```scala
final private[kyo] case class DownloadProgressWire(
    guid: String,
    totalBytes: Long,
    receivedBytes: Long,
    state: String
) derives Schema
```

---

## Browser.getVersion probe

Design §9.A (02-design.md:695-707) pseudocode (verbatim):
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

The plan's `initUnscoped` absorbs this into an `Abort.recover[BrowserReadException]` block (05-plan.md:234-241):
```scala
_ <- Abort.recover[BrowserReadException] {
    case _: BrowserConnectionLostException =>
        Abort.fail(BrowserSetupException("WS handshake failed: probe call returned Closed"))
    case other => Abort.fail(other)
} {
    backend.send[BrowserGetVersionParams, BrowserVersionResult](
        "Browser.getVersion", BrowserGetVersionParams())
}
```

`BrowserGetVersionParams` and `BrowserVersionResult` are NOT yet in `CdpTypes.scala`. The impl agent must add them. Based on the CDP `Browser.getVersion` spec:
```scala
final private[kyo] case class BrowserGetVersionParams() derives Schema
final private[kyo] case class BrowserVersionResult(
    protocolVersion: String,
    product: String,
    revision: String,
    userAgent: String,
    jsVersion: String
) derives Schema
```

These go in `CdpTypes.scala` as part of the Phase 01 commit.

---

## lastEvaluateParams diagnostic

Current write site at `CdpClient.scala:65-67`:
```scala
val record =
    if method == CdpBackend.RuntimeEvaluateMethod then lastEvaluateParams.set(Present(Json.encode(params)))
    else Kyo.unit
```

Current field declaration at `CdpClient.scala:36`:
```scala
private[kyo] val lastEvaluateParams: AtomicRef[Maybe[String]],
```

Init at `CdpClient.scala:342`:
```scala
lastEvaluateParams <- AtomicRef.init[Maybe[String]](Absent)
```

New home in Phase 01: `CdpBackend` class field `private[kyo] val lastEvaluateParams: AtomicRef[Maybe[String]]`, init inside `initUnscoped`, write inside `CdpBackend.send` at the `if method == CdpBackend.RuntimeEvaluateMethod` check (05-plan.md:90-92):
```scala
val record =
    if method == CdpBackend.RuntimeEvaluateMethod
    then lastEvaluateParams.set(Present(Json.encode(params)))
    else Kyo.unit
```

---

## Dialog drainer

Current drainer init in `CdpClient.initUnscoped` (CdpClient.scala:325-340):
```scala
dialogIdCounter <- AtomicInt.init(Int.MinValue)
dialogDrainer <- Fiber.initUnscoped {
    Abort.run[Closed] {
        dialogQueue.stream().foreach { case (accept, promptText, sessionId) =>
            dialogIdCounter.getAndIncrement.map { id =>
                val wire = Json.encode(CdpEnvelope(
                    id,
                    "Page.handleJavaScriptDialog",
                    HandleJavaScriptDialogParams(accept, promptText),
                    sessionId.map(_.value)
                ))
                Abort.run[Closed](outbound.put(wire)).unit
            }
        }
    }.unit
}
```

New home in Phase 01 (`buildDialogDrainer` helper, 05-plan.md:303-329):
```scala
private def buildDialogDrainer(
    endpoint: JsonRpcEndpoint,
    dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
    dialogIdCounter: AtomicInt
)(using Frame): Fiber[Unit, Any] < Async =
    Fiber.initUnscoped {
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
```

Key change: replaces `outbound.put(Json.encode(CdpEnvelope(...)))` with `endpoint.sendUnmatched(method, typedParams, JsonRpcId.Num(id.toLong), extras)`. The id is caller-supplied (INV-018: negative space from `dialogIdCounter` starting at `Int.MinValue` does not collide with `IdStrategy.SequentialInt`'s positive allocator).

New class fields:
- `private[kyo] val dialogDrainer: Fiber[Unit, Any]`
- `private[kyo] val dialogQueue: Channel[(Boolean, String, Maybe[SessionId])]`

---

## Edge cases and gotchas

### 1. Scope from JsonRpcEndpoint.init must be discharged inside initUnscoped

`JsonRpcEndpoint.init` returns `JsonRpcEndpoint < (Sync & Async & Scope)`. The plan's `CdpBackend.initUnscoped` returns `CdpBackend < (Async & Abort[BrowserReadException])` with NO `Scope`. This means a `Scope.run` wraps the `JsonRpcEndpoint.init` call inside `initUnscoped`. The plan pseudocode implies this but does not show it explicitly. The impl must either:

a) Use `Scope.run(JsonRpcEndpoint.init(...))` to discharge the Scope and extract the endpoint, OR
b) Accept that `initUnscoped` actually carries `Scope` in its effect row (but then `CdpBackend.init` using `Scope.acquireRelease` would double-nest Scope).

Option (a) is correct: the plan's `CdpBackend.init` wraps `initUnscoped` in `Scope.acquireRelease(initUnscoped(...))(.close(...))`, implying `initUnscoped` itself is NOT Scope-bound. Therefore `Scope.run` must appear inside `initUnscoped` around the `JsonRpcEndpoint.init` call.

Similarly, `JsonRpcHttpTransport.webSocket` returns `< (Async & Scope & Abort[HttpException])`. The Scope from this call must also be discharged. The plan shows it inside the same for-comprehension as `JsonRpcEndpoint.init`, so both Scopes are subsumed by a wrapping `Scope.run` that produces the raw values.

The corrected shape for `initUnscoped` is:
```scala
def initUnscoped(...): CdpBackend < (Async & Abort[BrowserReadException]) =
    Scope.run {
        for
            ...atomic state...
            transport <- Abort.recover[HttpException] { e => ... } {
                JsonRpcHttpTransport.webSocket(url, HttpHeaders.empty, JsonRpcCodec.Cdp)
            }  // Scope absorbed by Scope.run
            ...methods...
            endpoint <- JsonRpcEndpoint.init(transport, methods, config)  // Scope absorbed by Scope.run
            dialogDrainer <- buildDialogDrainer(endpoint, dialogQueue, dialogIdCounter)
            backend = new CdpBackend(...)
            _ <- Abort.recover[BrowserReadException] { ... } {
                backend.send[BrowserGetVersionParams, BrowserVersionResult](...)
            }
        yield backend
    }
```

cited at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:101-106` (effect row of `init`)
and `kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:6-10` (effect row of `webSocket`)

### 2. JsonRpcMethod.notification handler effect row requires S that satisfies (Async & Abort[JsonRpcError]) <:< S

The handlers are:
```scala
JsonRpcMethod.notification[JavascriptDialogOpeningParams, Async & Abort[JsonRpcError]]("Page.javascriptDialogOpening") { (params, ctx) =>
    ...
}
```

The handler body must return `Unit < (Async & Abort[JsonRpcError])`. The `handleDialogOpening` helper's return type `Unit < (Async & Abort[JsonRpcError])` satisfies this.

cited at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala:48-57`

### 3. JsonRpcCodec.Cdp flattens sessionId from extras to the TOP LEVEL of the wire envelope (NOT inside params)

`ExtrasEncoder.const(Structure.Value.Record(Chunk("sessionId" -> Str(sid.value))))` causes the CDP codec's `buildWithExtras` to merge `sessionId` at the top level of the JSON object. The wire shape is `{"id":1,"method":"...","params":{...},"sessionId":"abc"}`, NOT `{"id":1,"method":"...","params":{"sessionId":"abc",...}}`.

cited at `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala:153-176`

### 4. ExtrasEncoder.const is the canonical injection idiom

```scala
ExtrasEncoder.const(Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value))))
```

This is the only correct way to stamp sessionId. `ExtrasEncoder.empty` is the default for non-session calls.

cited at `kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala:9-12`

### 5. BrowserConnectionLostException, BrowserProtocolErrorException, BrowserSetupException recovery order

`CdpBackend.send` must recover in this order:
1. `Abort.recover[Closed]` -> `BrowserConnectionLostException(s"Connection lost: ...", Present(closed))`
2. `Abort.recover[Timeout]` -> `BrowserConnectionLostException(s"Request timeout: $method", Absent)`
3. `Abort.recover[JsonRpcError]` -> `BrowserProtocolErrorException(method, err.message)`

The Abort chain wraps `endpoint.call[P, R](method, params, extras)` which returns `R < (Async & Abort[JsonRpcError | Closed])`. `Timeout` arises from the `requestTimeout` config in `JsonRpcEndpoint.Config`.

cited at `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:93-104` (legacy pattern being replicated)

### 6. Notification handlers use Sync.defer at the BUILD site, not inside the handler body

```scala
dialogMethod <- Sync.defer(JsonRpcMethod.notification[...](name) { (params, ctx) => ... })
```

The `Sync.defer` suspends construction of the `JsonRpcMethod` value (which involves capturing lambdas). The handler body itself is NOT wrapped in `Sync.defer`; it returns `Unit < (Async & Abort[JsonRpcError])` directly.

cited at `kyo-browser/.flow/jsonrpc-port/05-plan.yaml:116-117` (`side_effects.suspended_in: "Sync.defer"`)

### 7. BrowserGetVersionParams and BrowserVersionResult are new types to add in CdpTypes.scala

Neither type exists in the current codebase. The impl agent must add them to `kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala` in the same Phase 01 commit. These types are used only by the probe call in `CdpBackend.initUnscoped`.

### 8. JavascriptDialogOpeningParams lives in CdpClient.scala (lines 599-604)

This type is currently in `CdpClient.scala` (not in `CdpTypes.scala`). Phase 01 code in `CdpBackend.scala` needs to import or reference it. Since `CdpClient.scala` stays alive in Phase 01, the reference compiles. Alternatively the type can be moved to `CdpTypes.scala`; but per INV-010 ("no shim / parallel-API residue") the move is deferred to Phase 02 when CdpClient.scala is deleted.

cited at `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:599-604`

### 9. dispatchFrameEvent uses Structure.Value (not typed params) because updateFrameContexts still decodes from paramsJson

The `buildFrameMethod` helper in the plan uses:
```scala
JsonRpcMethod.notification[Structure.Value, Async & Abort[JsonRpcError]](methodName) {
    (paramsValue, ctx) =>
        val sid = readSessionIdFromExtras(ctx.extras)
        dispatchFrameEvent(frameEventDispatchers, methodName, paramsValue, sid)
}
```

`dispatchFrameEvent` re-encodes the `Structure.Value` params to JSON (`Json.encode(paramsValue)`) to produce the `CdpEvent.Generic(method, json, sid)` that `updateFrameContexts` decodes. This preserves byte-equivalent behavior with the existing `BrowserTab.updateFrameContexts` path that decodes from the wire string.

cited at `kyo-browser/.flow/jsonrpc-port/05-plan.md:271-276` and `BrowserTab.scala:104-134`

### 10. CdpBackendOld must carry flow-allow annotation

The renamed object `CdpBackendOld` requires the annotation:
```scala
// flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02
private[kyo] object CdpBackendOld:
```

This satisfies INV-024 (every audit-flag exception annotated).

cited at `kyo-browser/.flow/jsonrpc-port/05-plan.md:41`

### 11. dialogQueue uses offer (non-blocking) in the legacy path but put (blocking) in the new drainer

The legacy `decodeCdpMessage` uses `dialogQueue.offer` (non-blocking). The new `handleDialogOpening` helper uses `dialogQueue.put` (wrapped in `Abort.run[Closed]`) in the plan (05-plan.md:362). Verify that `Channel.put` vs `Channel.offer` semantics are acceptable given capacity 16. `offer` is non-blocking and may drop; `put` blocks but handles `Closed`. The plan uses `put` for correctness (no silent drop under pressure).

cited at `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala:440` (legacy `offer`) vs `kyo-browser/.flow/jsonrpc-port/05-plan.md:362` (new `put`)

### 12. Phase 01 commit must include JsonRpcPortInvariantsSpec.scala in the same commit (Rule 8c / INV-008)

The spec file ships alongside `CdpBackend.scala` and `CdpBackendSmokeTest.scala` in ONE commit. Separate commits violate INV-008.

cited at `kyo-browser/.flow/jsonrpc-port/05-plan.md:45`

---

## Test-data suggestions

- `BrowserVersionResult("Headless/0", "0", "x86_64", "Mozilla/5.0 (Headless)", "0.0")`: realistic fake reply for probe in smoke tests.
- `SessionId("abc")` / `SessionId("s1")` / `SessionId("rt")`: stable short session IDs for round-trip tests.
- `NavigateParams("https://example.com", Absent, Absent, Absent)`: simplest page-navigate params.
- Negative dialog id: `JsonRpcId.Num(Int.MinValue.toLong)` -- exact expected value for first drainer sendUnmatched.
- `JsonRpcId.Num(1L)` -- first SequentialInt id from the endpoint allocator, disjoint from negative space.

---

## Anti-flakiness deltas

- `within 200ms` timing assertions for notification dispatch: use `Fiber.sleep(200.millis)` after pushing the inbound notification, then check the capture ref. Avoid polling loops.
- Dialog drainer is a background fiber: after enqueueing, yield control with a short `Fiber.sleep(50.millis)` before asserting the outbound envelope was captured.
- Do NOT run JVM, JS, Native test platforms in parallel: resource contention (Chrome ports). Sequential per INV-021.
- `inMemory` transport does not start a background fiber; messages arrive synchronously on the queue. The endpoint's inbound processing loop runs in a Fiber; give it a `Fiber.sleep` or an explicit await on the capture AtomicRef to avoid ABA races.

---

## Cross-platform notes

- platforms: jvm, js, native
- All new code goes in `kyo-browser/shared/` -- no platform-specific files.
- `java.util.concurrent.ConcurrentHashMap` is used inside `JsonRpcEndpointImpl` (kyo-core provides a JS/Native JDK shim). `CdpBackend.scala` must NOT import `java.util.concurrent.*` directly; use `AtomicRef` / `AtomicInt` / `Channel` from kyo-core.
- `Int.MinValue.toLong` is -2147483648L on all platforms; no platform-specific overflow risk.
- `Scope.run` is cross-platform; no quirks.
- `JsonRpcHttpTransport.webSocket` is in `kyo-jsonrpc-http` which is a NON-crossProject (single-source). It is declared `.crossType(CrossType.Pure)` in build.sbt and available on JVM/JS/Native via the crossProject dependency.

cited at `build.sbt:741-755` and `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:4-5`

---

## Concerns

- **Missing BrowserGetVersionParams / BrowserVersionResult types**: neither exists in `CdpTypes.scala` HEAD. The impl agent must add them. The plan's test case 1 references `BrowserVersionResult("Headless/0", "0", "x86_64", "user-agent", "0.0")` -- the 5-field constructor must match the Schema-derived case class. Confirm with `Browser.getVersion` CDP spec: fields are `protocolVersion`, `product`, `revision`, `userAgent`, `jsVersion`.

- **Scope discharge inside initUnscoped is not explicit in plan pseudocode**: the plan's `initUnscoped` calls `JsonRpcEndpoint.init` (which returns with `Scope`) inside a for-comprehension that returns `CdpBackend < (Async & Abort[BrowserReadException])`. A wrapping `Scope.run { ... }` is required to discharge the Scope. The impl agent must insert it. This is a critical structural detail.

- **JavascriptDialogOpeningParams location**: currently defined in `CdpClient.scala:599-604` (not in `CdpTypes.scala`). Phase 01 `CdpBackend.scala` imports from `CdpClient.scala` indirectly (both in `kyo.internal`). This compiles but is architecturally awkward; Phase 02 moves the type when `CdpClient.scala` is deleted. No action needed in Phase 01 beyond ensuring the import works.

- **handleDialogOpening helper uses `put` (blocking) vs legacy `offer` (non-blocking)**: the plan intentionally changes semantics here. If `dialogQueue` is full (capacity 16), `put` blocks the notification handler until space is available. This is safer than dropping silently. Accept this semantic change; it is explicitly in the plan.

- **dispatchFrameEvent takes Structure.Value, not typed params**: the plan's `buildFrameMethod` uses `Structure.Value` as the notification parameter type (not `ExecutionContextCreatedParams`). This avoids a double-decode path but requires `Json.encode(paramsValue)` to reconstruct the wire string for `CdpEvent.Generic`. Confirm that `Structure.Value` satisfies the `Schema` constraint for `JsonRpcMethod.notification[In: Schema, S]` -- it does, as `Structure.Value` has a `Schema` instance (used throughout kyo-schema).
