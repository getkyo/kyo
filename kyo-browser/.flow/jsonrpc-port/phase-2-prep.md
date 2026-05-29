# Phase 2 prep

Phase name: Cut over Browser / BrowserTab / BrowserSnapshot to CdpBackend; delete CdpClient
Files to produce: 0
Files to modify: ~14
Files to delete: 1 (CdpClient.scala)
Tests: 11
Plan cites: ./05-plan.yaml §Phase 2

## Verbatim API signatures

### CdpBackend class (Phase 01 output; impl agent reads, not modifies)

Located at `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:33-113`

```scala
private[kyo] def send[P: Schema, R: Schema](method: String, params: P)(using
    Frame
): R < (Async & Abort[BrowserReadException])
// at CdpBackend.scala:33

private[kyo] def sendUnit[P: Schema](method: String, params: P)(using
    Frame
): Unit < (Async & Abort[BrowserReadException])
// at CdpBackend.scala:73

private[kyo] def withSession(sid: SessionId): CdpBackend
// at CdpBackend.scala:83

private[kyo] def close(gracePeriod: Duration)(using Frame): Unit < Async
// at CdpBackend.scala:99

private[kyo] def closeNow(using Frame): Unit < Async
// at CdpBackend.scala:105

private[kyo] def awaitDrain(using Frame): Unit < Async
// at CdpBackend.scala:111
```

### CdpBackend companion (keep: init / initUnscoped / RuntimeEvaluateMethod)

```scala
private[kyo] val RuntimeEvaluateMethod = "Runtime.evaluate"
// at CdpBackend.scala:129

private[kyo] val maxInFlight: Int = 8
// at CdpBackend.scala:135

def init(wsUrl: String, launchCfg: Browser.LaunchConfig)(using
    Frame
): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException])
// at CdpBackend.scala:138

def initUnscoped(wsUrl: String, launchCfg: Browser.LaunchConfig)(using
    Frame
): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException])
// at CdpBackend.scala:147

// Transport-based init for testing (keep):
private[kyo] def initUnscoped(transport: JsonRpcTransport, launchCfg: Browser.LaunchConfig)(using
    Frame
): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException])
// at CdpBackend.scala:359
```

### CdpBackend instance fields (accessed from BrowserTab / Browser.scala)

```scala
private[kyo] val endpoint: JsonRpcEndpoint
private[kyo] val dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]]
private[kyo] val dialogDrainer: Fiber[Unit, Any]
private[kyo] val dialogQueue: Channel[(Boolean, String, Maybe[SessionId])]
private[kyo] val frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
private[kyo] val downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
private[kyo] val dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]]
private[kyo] val lastEvaluateParams: AtomicRef[Maybe[String]]
private[kyo] val sessionId: Maybe[SessionId]
// at CdpBackend.scala:17-27
```

### New Phase 02 companion wrappers (replace CdpBackendOld + forwarders)

Each wrapper is a two-line method on the `CdpBackend` companion taking `backend: CdpBackend` instead of `sender: CdpSender`:

```scala
// Unit-returning wrappers (use sendUnit):
private[kyo] def navigate(backend: CdpBackend, params: NavigateParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[NavigateParams]("Page.navigate", params)

private[kyo] def navigateToHistoryEntry(backend: CdpBackend, params: NavigateToEntryParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[NavigateToEntryParams]("Page.navigateToHistoryEntry", params)

private[kyo] def reload(backend: CdpBackend, params: ReloadParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[ReloadParams]("Page.reload", params)

private[kyo] def setDeviceMetricsOverride(backend: CdpBackend, params: ViewportParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[ViewportParams]("Emulation.setDeviceMetricsOverride", params)

private[kyo] def clearDeviceMetricsOverride(backend: CdpBackend)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.send[CdpNoParams, Unit]("Emulation.clearDeviceMetricsOverride", CdpNoParams())

private[kyo] def dispatchKeyEvent(backend: CdpBackend, params: DispatchKeyEventParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[DispatchKeyEventParams]("Input.dispatchKeyEvent", params)

private[kyo] def dispatchMouseEvent(backend: CdpBackend, params: MouseEventParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[MouseEventParams]("Input.dispatchMouseEvent", params)

private[kyo] def getDocument(backend: CdpBackend, params: GetDocumentParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.send[GetDocumentParams, Unit]("DOM.getDocument", params)

private[kyo] def setCookie(backend: CdpBackend, params: NetworkSetCookieParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[NetworkSetCookieParams]("Network.setCookie", params)

private[kyo] def deleteCookies(backend: CdpBackend, params: NetworkDeleteCookiesParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[NetworkDeleteCookiesParams]("Network.deleteCookies", params)

private[kyo] def closeTarget(backend: CdpBackend, params: CloseTargetParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[CloseTargetParams]("Target.closeTarget", params)

private[kyo] def disposeBrowserContext(backend: CdpBackend, params: DisposeBrowserContextParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[DisposeBrowserContextParams]("Target.disposeBrowserContext", params)

private[kyo] def setFileInputFiles(backend: CdpBackend, params: SetFileInputFilesParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[SetFileInputFilesParams]("DOM.setFileInputFiles", params)

// Result-returning wrappers (use send[P, R]):
private[kyo] def getNavigationHistory(backend: CdpBackend)(using Frame): NavigationHistory < (Async & Abort[BrowserReadException]) =
    backend.send[CdpNoParams, NavigationHistory]("Page.getNavigationHistory", CdpNoParams())

private[kyo] def getFrameTree(backend: CdpBackend)(using Frame): GetFrameTreeResult < (Async & Abort[BrowserReadException]) =
    backend.send[CdpNoParams, GetFrameTreeResult]("Page.getFrameTree", CdpNoParams())

private[kyo] def captureScreenshot(backend: CdpBackend, params: ScreenshotParams)(using Frame): ScreenshotResult < (Async & Abort[BrowserReadException]) =
    backend.send[ScreenshotParams, ScreenshotResult]("Page.captureScreenshot", params)

private[kyo] def printToPDF(backend: CdpBackend)(using Frame): PrintToPdfResult < (Async & Abort[BrowserReadException]) =
    backend.send[CdpNoParams, PrintToPdfResult]("Page.printToPDF", CdpNoParams())

private[kyo] def runtimeEvaluate(backend: CdpBackend, params: EvalParams)(using Frame): String < (Async & Abort[BrowserReadException]) =
    backend.send[EvalParams, String](RuntimeEvaluateMethod, params)

private[kyo] def getProperties(backend: CdpBackend, params: GetPropertiesParams)(using Frame): GetPropertiesResult < (Async & Abort[BrowserReadException]) =
    backend.send[GetPropertiesParams, GetPropertiesResult]("Runtime.getProperties", params)

private[kyo] def requestNode(backend: CdpBackend, params: RequestNodeParams)(using Frame): RequestNodeResult < (Async & Abort[BrowserReadException]) =
    backend.send[RequestNodeParams, RequestNodeResult]("DOM.requestNode", params)

private[kyo] def describeNodeByNodeId(backend: CdpBackend, params: DescribeNodeParams)(using Frame): DescribeNodeResult < (Async & Abort[BrowserReadException]) =
    backend.send[DescribeNodeParams, DescribeNodeResult]("DOM.describeNode", params)

private[kyo] def describeNodeByBackendId(backend: CdpBackend, params: DescribeNodeByBackendIdParams)(using Frame): DescribeNodeResult < (Async & Abort[BrowserReadException]) =
    backend.send[DescribeNodeByBackendIdParams, DescribeNodeResult]("DOM.describeNode", params)

private[kyo] def getBoxModel(backend: CdpBackend, params: GetBoxModelParams)(using Frame): BoxModel < (Async & Abort[BrowserReadException]) =
    backend.send[GetBoxModelParams, BoxModel]("DOM.getBoxModel", params)

private[kyo] def getCookies(backend: CdpBackend)(using Frame): NetworkGetCookiesResult < (Async & Abort[BrowserReadException]) =
    backend.send[CdpNoParams, NetworkGetCookiesResult]("Network.getCookies", CdpNoParams())

private[kyo] def getCookies(backend: CdpBackend, params: NetworkGetCookiesParams)(using Frame): NetworkGetCookiesResult < (Async & Abort[BrowserReadException]) =
    backend.send[NetworkGetCookiesParams, NetworkGetCookiesResult]("Network.getCookies", params)

private[kyo] def getTargets(backend: CdpBackend)(using Frame): GetTargetsResult < (Async & Abort[BrowserReadException]) =
    backend.send[CdpNoParams, GetTargetsResult]("Target.getTargets", CdpNoParams())

private[kyo] def attachToTarget(backend: CdpBackend, params: AttachParams)(using Frame): AttachResult < (Async & Abort[BrowserReadException]) =
    backend.send[AttachParams, AttachResult]("Target.attachToTarget", params)

private[kyo] def createTarget(backend: CdpBackend, params: CreateTargetParams)(using Frame): CreateTargetResult < (Async & Abort[BrowserReadException]) =
    backend.send[CreateTargetParams, CreateTargetResult]("Target.createTarget", params)

private[kyo] def createBrowserContext(backend: CdpBackend)(using Frame): CreateBrowserContextResult < (Async & Abort[BrowserReadException]) =
    backend.send[CdpNoParams, CreateBrowserContextResult]("Target.createBrowserContext", CdpNoParams())
```

NOTE on `runtimeEvaluate` return type: `CdpBackendOld.runtimeEvaluate` returns `String` (the raw wire JSON). In the new backend, `send[EvalParams, String]` will ask kyo-schema to decode the result slot as `String`. The existing consumers (`BrowserEval`, `BrowserSnapshot`, etc.) all pass the returned value to `CdpEvalDecoder.parseAndExtractEvalValue` or `CdpEvalEnvelope.decodeEvalEnvelope`. The new typed send decodes the CDP `{result: ...}` envelope; if the result slot in the CDP response IS a string value, this works. If Chrome returns the runtime eval result as a nested JSON object (not a primitive string), the schema decode will fail. VERIFY: in practice `Runtime.evaluate` with `returnByValue=false` returns a JSON object in the `result` slot, not a bare string. The existing `CdpBackendOld.runtimeEvaluate` returns the ENTIRE wire (the `CdpReply` envelope); callers then decode it again with `CdpEvalDecoder`. The Phase 02 impl must preserve this "return the raw wire string to the caller" semantics. The correct approach for `runtimeEvaluate` is `backend.send[EvalParams, EvalResult](...)` OR keep sending raw via `endpoint.call` directly. See the Risk section below for the safe resolution.

## File anchors

Each file the phase modifies or deletes, with the specific line range to edit.

### DELETE (entire file)
- `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala`
  - All 628 lines; also deletes `CdpEnvelope`, `CdpNoParams`, `CdpWireMessage`, `CdpReply`, `CdpEventParams`, `JavascriptDialogOpeningParams`, `FallbackIdEnvelope`, `CdpEvent`, `CdpEvent.Generic`, `CdpError`, `CdpSender` defined in that file.
  - IMPORTANT: `CdpReply`, `CdpEventParams`, `CdpError`, and `CdpEvent`/`CdpEvent.Generic` are referenced by other files. Check each before deleting.

### MODIFY

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`
  - Lines 221-353: Delete all 28 forwarder methods (the `// --- Forwarding methods ---` block and `// --- End of forwarding methods ---`).
  - Lines 228: Delete `decodeOrFail` forwarder.
  - Lines 633-829: Delete `object CdpBackendOld` entirely.
  - Lines 620-627: Delete `CdpSender` trait (defined at bottom of CdpClient.scala; Phase 02 keeps the trait deleted since no consumer will need it).
  - ADD: the 28 new two-line wrapper methods in the companion using `backend: CdpBackend` parameter (replacing `sender: CdpSender`).

- `kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala`
  - Lines 19-36: Rename `val client: CdpClient` to `val backend: CdpBackend` in the class definition; rename `val session: CdpClient = client.withSession(sessionId)` to `val session: CdpBackend = backend.withSession(sessionId)`.
  - Line 22: `val client: CdpClient` -> `val backend: CdpBackend`
  - Line 35: `val session: CdpClient = client.withSession(sessionId)` -> `val session: CdpBackend = backend.withSession(sessionId)`
  - Lines 52-76 (`mkBrowserTab`): rename `client: CdpClient` parameter to `backend: CdpBackend`; update construction.
  - Lines 84-96 (`installFrameContextTracker`): `tab.client.frameEventDispatchers` -> `tab.backend.frameEventDispatchers`.
  - Lines 93-94: `tab.client.frameEventDispatchers.updateAndGet(...)` -> `tab.backend.frameEventDispatchers.updateAndGet(...)`.
  - Line 214 (`attachAndSetupTab`): `client: CdpClient` -> `backend: CdpBackend`; all internal uses of `client` -> `backend`.
  - Lines 218-234: All `CdpBackend.createBrowserContext(client)`, `CdpBackend.createTarget(client, ...)`, etc. become `CdpBackend.createBrowserContext(backend)`.
  - Lines 245-253 (`enableDomains`): parameter `sender: CdpSender` -> `sender: CdpBackend`. The method signature must change because `CdpSender` is deleted.
  - Lines 264-283 (`createChildTab`): `parent: BrowserTab` references via `parent.client` -> `parent.backend`.

- `kyo-browser/shared/src/main/scala/kyo/Browser.scala`
  - Line 258: `CdpClient.init(wsUrl, launch)` -> `CdpBackend.init(wsUrl, launch)`; effect row changes from `Async & Scope & Abort[BrowserReadException]` to `Async & Scope & Abort[BrowserReadException | BrowserSetupException]` (must propagate to `run` return type check).
  - Line 259: `BrowserTabSetup.attachAndSetupTab(client)` -> `BrowserTabSetup.attachAndSetupTab(backend)` (name follows local variable rename).
  - Line 273: `CdpClient.init(wsUrl, ...)` -> `CdpBackend.init(wsUrl, ...)`.
  - Lines 2154, 2157-2162: `val client = tab.client` -> `val backend = tab.backend`; `client.dialogHandlers` -> `backend.dialogHandlers`.
  - Lines 2190, 2193-2198: `val client = tab.client` -> `val backend = tab.backend`; `client.dialogRecorders` -> `backend.dialogRecorders`.
  - Lines 2517, 2533-2538: `val client = tab.client` -> `val backend = tab.backend`; `client.downloadEventDispatchers` -> `backend.downloadEventDispatchers`.
  - Lines 2737, 2744, 2757, 2760, 2765: `parent.client` -> `parent.backend`.
  - Lines 2817, 2819, 2821, 2825: `parent.client` -> `parent.backend`.
  - Line 2112 (comment only): Update Scaladoc reference to CdpClient.

- `kyo-browser/shared/src/main/scala/kyo/internal/NavigationWatcher.scala`
  - Line 560: `tab.client.withSession(tab.sessionId)` -> `tab.backend.withSession(tab.sessionId)`.

- `kyo-browser/shared/src/main/scala/kyo/internal/Resolver.scala`
  - Line 3: `import CdpBackend.decodeOrFail` -> DELETE (decodeOrFail is gone).
  - Lines 50, 116: `decodeOrFail[...]` calls replaced with direct `Json.decode[CdpReply[...]]` pattern (inline the logic from `CdpBackendOld.decodeOrFail`, or factor into a private helper in `Resolver.scala`).
  - Lines 212 (`describeByObjectId`): parameter type `s: CdpClient` -> `s: CdpBackend`.
  - Lines 223 (`requestAndDescribe`): parameter type `s: CdpClient` -> `s: CdpBackend`.
  - Lines 239 (`releaseObjectQuiet`): parameter type `s: CdpClient` -> `s: CdpBackend`.

- `kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala`
  - Line 4: `import kyo.internal.CdpClient` -> `import kyo.internal.CdpBackend`.
  - Lines 32, 36: `client: CdpClient` -> `client: CdpBackend`.
  - Lines 33, 37-40: `client.send(...)` calls now call on `CdpBackend` instance. Since `CdpBackend.send[P, R]` returns typed R, these must become `client.send[CdpNoParams, AxTreeRawResult]("Accessibility.getFullAXTree", CdpNoParams())` etc. The existing `parseAxTree` takes a raw `String` wire; must verify what result type Chrome returns.

- `kyo-browser/shared/src/main/scala/kyo/internal/cdp/PageDownload.scala`
  - Line 4: `import kyo.internal.CdpClient` -> `import kyo.internal.CdpBackend`.
  - Line 58: `client: CdpClient` -> `client: CdpBackend`.
  - Line 67: `client.sendUnit(...)` -> `client.sendUnit[SetDownloadBehaviorParams](...)`.

## Edge cases and gotchas

### 1. `runtimeEvaluate` return type is not `String`

The most critical gotcha. `CdpBackendOld.runtimeEvaluate` returns `String < (Async & Abort[BrowserReadException])` where the string is the ENTIRE CDP wire frame (`{"result":{"type":"string","value":"..."},...}`). Every consumer (`BrowserEval.evalJs`, `BrowserSnapshot.captureSnapshot`, etc.) then pipes this to `CdpEvalDecoder.parseAndExtractEvalValue(wire)` or `CdpEvalEnvelope.decodeEvalEnvelope(wire, ...)`.

In the new typed `send[EvalParams, R]`, if `R = String`, kyo-schema will try to decode the `result` slot of the CDP response as a JSON string. Chrome's `Runtime.evaluate` returns a complex object like `{"type":"string","value":"..."}` in the result slot, not a bare string. Schema decoding would fail.

**Resolution**: `runtimeEvaluate` in the new companion should call `backend.send[EvalParams, EvalResult](RuntimeEvaluateMethod, params)` where `EvalResult` is the typed wire shape for `Runtime.evaluate`'s result, then callers switch from `CdpEvalDecoder.parseAndExtractEvalValue(rawWire)` to `CdpEvalDecoder.extractEvalValue(result)`. Alternatively: the companion `runtimeEvaluate` calls `backend.endpoint.call[EvalParams, ...]` to bypass the schema decode and return the raw string. Check what `CdpWire.scala:78-96` already does with `CdpBackend.RuntimeEvaluateMethod` to understand the existing eval-result wire shape. The safest path matching existing `CdpEvalDecoder` is to have the companion `runtimeEvaluate` wrapper return the raw wire string by using `backend.send[EvalParams, EvalResult]` where `EvalResult` IS the whole result object (the existing `EvalResult` case class in `CdpTypes.scala`). Check `kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala` around line 454 for `EvalResult`.

### 2. `decodeOrFail` used directly in `Resolver.scala`

`Resolver.scala:3` has `import CdpBackend.decodeOrFail` and uses it at lines 50 and 116. When `CdpBackend.decodeOrFail` forwarder is deleted and `CdpBackendOld.decodeOrFail` is gone, these call sites break. The impl agent must inline the decodeOrFail logic (which is purely `Json.decode[CdpReply[A]](wire)` pattern-matching) directly into `Resolver.scala`, or factor it into a private helper within that file.
at `kyo-browser/shared/src/main/scala/kyo/internal/Resolver.scala:3,50,116`

### 3. `BrowserTab.session` field type change: `CdpClient` -> `CdpBackend`

`val session: CdpClient = client.withSession(sessionId)` at `BrowserTab.scala:35` becomes `val session: CdpBackend = backend.withSession(sessionId)`. Every `tab.session` reference throughout the codebase (used as the first argument to the companion wrappers) compiles because the companion wrappers switch their parameter type to `CdpBackend` in Phase 02.
at `kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala:35`

### 4. `enableDomains` takes `CdpSender`; must become `CdpBackend`

`BrowserTabSetup.enableDomains(sender: CdpSender)` at `BrowserTab.scala:245` calls `sender.send(...)` (the two-arg `CdpSender.send` and zero-arg form). When `CdpSender` is deleted, this must become `sender: CdpBackend`. The body uses `sender.send("Page.enable").unit` etc. which calls the zero-param `send` on `CdpClient`. In the new API, `CdpBackend` has no zero-param `send`; the wrapper must call `backend.send[CdpNoParams, Unit]("Page.enable", CdpNoParams())` or equivalently `backend.sendUnit[CdpNoParams]("Page.enable", CdpNoParams())`.
at `kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala:245-253`

### 5. `attachAndSetupTab` parameter type changes; callers in `Browser.run` change

`BrowserTabSetup.attachAndSetupTab(client: CdpClient)` -> `attachAndSetupTab(backend: CdpBackend)`. The two call sites in `Browser.scala` (lines 259 and 273) already pass the result of `CdpClient.init` / `CdpBackend.init`; the local variable rename handles this.
at `kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala:214`
at `kyo-browser/shared/src/main/scala/kyo/Browser.scala:259,274`

### 6. `Accessibility.getFullAXTree` takes `CdpClient`; raw wire returned

`Accessibility.getFullAXTree(client: CdpClient)` calls `client.send("Accessibility.getFullAXTree")` returning a raw `String` wire, then passes it to `parseAxTree(wire)`. The `parseAxTree` function does `Json.decode[CdpReply[AxTreeResponse]](wire)`, which assumes the full CDP response envelope. When switching to `CdpBackend.send[CdpNoParams, AxTreeResponse]`, the schema will decode the `result` slot as `AxTreeResponse` directly, NOT the full envelope with `error` handling. The impl agent must verify that `getFullAXTree` uses the typed path and updates `parseAxTree` to not re-unwrap (since the error branch is now handled by `send`'s `JsonRpcError -> BrowserProtocolErrorException` recovery). Alternatively: keep the raw-wire path by defining a unit companion wrapper on `CdpBackend` that returns raw string (as a special case).
at `kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala:32-40`

### 7. `PageDownload.setDownloadBehavior` raw send

`client.sendUnit("Page.setDownloadBehavior", params)` at `PageDownload.scala:67` uses the two-param `CdpSender.sendUnit`. This becomes `client.sendUnit[SetDownloadBehaviorParams]("Page.setDownloadBehavior", params)` on `CdpBackend`, which maps cleanly.
at `kyo-browser/shared/src/main/scala/kyo/internal/cdp/PageDownload.scala:67`

### 8. `CdpReply` / `CdpEventParams` / `CdpError` / `CdpEvent` are defined in `CdpClient.scala`

These types are referenced by other files that remain in Phase 02:

- `CdpReply` is used in `Resolver.scala` (via `decodeOrFail`), `Accessibility.scala` (in `parseAxTree`), `BrowserEval.scala:79` (in `translateContextDestroyed`).
- `CdpEventParams` is used in `BrowserTab.scala:108,122` (in `updateFrameContexts`).
- `CdpError` appears in `CdpReply`.
- `CdpEvent` / `CdpEvent.Generic` appear in `BrowserTab.scala:89,104` and throughout dispatcher registrations.
- `CdpEnvelope`, `CdpNoParams`, `CdpWireMessage`, `CdpSender`, `FallbackIdEnvelope`, `JavascriptDialogOpeningParams` are also in `CdpClient.scala`.

**Resolution**: Before deleting `CdpClient.scala`, these types must be moved to `CdpTypes.scala` or `CdpWire.scala`. Note `CdpWire.scala` already exists and likely holds some of them. Verify which types currently live in `CdpTypes.scala` vs `CdpClient.scala`:

- `CdpEnvelope` (line 564 of CdpClient.scala): used by `CdpBackend.initUnscoped` in the `decodeCdpMessage` lambda passed to `Exchange.initUnscoped` (but in Phase 02, the new `CdpBackend` no longer uses `Exchange` -- the Phase 01 `initUnscoped` already uses `JsonRpcEndpoint`). Check if `CdpEnvelope` is still needed after Phase 02.
- `CdpNoParams`, `CdpWireMessage`, `CdpReply`, `CdpEventParams`, `JavascriptDialogOpeningParams`, `FallbackIdEnvelope`: all defined in `CdpClient.scala` lines 572-609. These MUST be relocated (to `CdpTypes.scala` or `CdpWire.scala`) before `CdpClient.scala` is deleted.
- `CdpEvent`, `CdpEvent.Generic`, `CdpError`, `CdpSender`: lines 611-627 of `CdpClient.scala`. `CdpEvent.Generic` is used by dispatcher registrations throughout `BrowserTab.scala` and `Browser.scala`. Must be relocated.

### 9. `NavigationWatcher.waitForLoad` uses `tab.client.withSession`

At `NavigationWatcher.scala:560`: `CdpBackend.runtimeEvaluate(tab.client.withSession(tab.sessionId), ...)`. Since `tab.client` becomes `tab.backend` and the `session` field already gives `backend.withSession(sessionId)`, this can be simplified to `CdpBackend.runtimeEvaluate(tab.session, ...)` (matching all other call sites).
at `kyo-browser/shared/src/main/scala/kyo/internal/NavigationWatcher.scala:560`

### 10. `Browser.run(wsUrl)` effect row

`Browser.run(wsUrl: String)` at `Browser.scala:272` currently uses `CdpClient.init` whose effect row is `Async & Scope & Abort[BrowserReadException]`. Switching to `CdpBackend.init` adds `BrowserSetupException` to the abort. The public signature of `run(wsUrl)` must change to `Async & Abort[BrowserReadException | BrowserSetupException]` or the `BrowserSetupException` must be recovered inside `run`. Changing the public signature violates INV-001 (byte-identical public surface). The resolution is to recover `BrowserSetupException` inside `run(wsUrl)` by mapping it to `BrowserConnectionLostException`. Verify that `BrowserSetupException` is a subtype of `BrowserReadException` (check the `BrowserException` ADT hierarchy); if so, the effect row is already compatible.
at `kyo-browser/shared/src/main/scala/kyo/Browser.scala:272`

### 11. `BrowserTabSetup.attachAndSetupTab` scope/return type

Currently returns `BrowserTab < (Async & Scope & Abort[BrowserReadException])`. After Phase 02 replaces `client: CdpClient` with `backend: CdpBackend`, there are no new effects added -- the body already uses `CdpBackend.*` companion wrappers. Return type is unchanged.

### 12. `CdpEvalDecoder.parseAndExtractEvalValue` vs `extractEvalValue`

The current production code chain: `CdpBackend.runtimeEvaluate(session, params)` returns `String < ...` (the raw wire); then `.map(CdpEvalDecoder.parseAndExtractEvalValue)`. `parseAndExtractEvalValue` decodes `CdpReply[EvalResult]` from the wire. In Phase 02, if the companion `runtimeEvaluate` wrapper uses `backend.send[EvalParams, EvalResult](...)`, the result is already `EvalResult` (decoded), and callers must switch from `parseAndExtractEvalValue(wire)` to `CdpEvalDecoder.extractEvalValue(result)`. This is a widespread change across ~10 call sites. The safest approach for the impl agent is to keep `runtimeEvaluate` returning a `String` by NOT using the typed `send` for eval specifically -- instead do:

```scala
private[kyo] def runtimeEvaluate(backend: CdpBackend, params: EvalParams)(using Frame): String < (Async & Abort[BrowserReadException]) =
    backend.send[EvalParams, EvalResult](RuntimeEvaluateMethod, params).map(r => Json.encode(r))
```

This re-encodes the typed result to a JSON string so callers' `parseAndExtractEvalValue` still works unchanged. This is the zero-consumer-change approach. An alternative is to change all ~10 consumer call sites to use `extractEvalValue` instead.

## Test-data suggestions

- `JsonRpcTransport.inMemory` pair with a fake server that echos `{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"1.3","product":"Chrome/120"}}` for the `Browser.getVersion` probe: tests `CdpBackend.init` connects cleanly.
- Dialog routing: inject `Page.javascriptDialogOpening` notification via the server-side transport with and without `sessionId` extras; verify `dialogHandlers` dispatch.
- Frame-event routing: inject `Runtime.executionContextCreated` with `sessionId` in extras; verify `frameEventDispatchers` fires for the right session key.

## Anti-flakiness deltas

- Dialog-handler tests that observe `dialogQueue` contents must await a settled state (e.g. `Fiber.Promise` or `AtomicRef` poll) rather than `Async.sleep` with a fixed duration. The `dialogDrainer` fiber consumes the queue asynchronously.
- `enableDomains` uses `Async.zip` for the four `*.enable` CDP calls; after Phase 02 all four calls route through `backend.sendUnit[CdpNoParams]`. None return meaningful values, so `Async.zip` semantics are unchanged.
- Tests in `CdpClientTest.scala` and `CdpClientLifecycleTest.scala` that call `CdpClient.init` / `CdpClient.initUnscoped` MUST NOT be deleted in Phase 02 (Phase 03 handles test migration). In Phase 02 these tests still compile because `CdpClient.scala` is deleted but the test file will fail to compile. The plan says "Phase 02 must NOT delete tests; that is Phase 03." Therefore Phase 02 must keep `CdpClient.scala` deleted only AFTER the test files are confirmed to still compile -- but they WON'T compile if they import `CdpClient`. This is the atomic-green ordering concern described in section 8 below.

## Cross-platform notes

- platforms: jvm, js, native
- `CdpSender` trait is in `CdpClient.scala`; deleting that file removes the trait. No per-platform quirks; `CdpSender` only appears in shared code.
- `BrowserTab.enableDomains` is called from `attachAndSetupTab` in shared code. The `Async.zip` inside `enableDomains` is cross-platform.
- `PageDownload.setDownloadBehavior` is shared; callers in `Browser.scala` are in shared code. No platform fork needed.
- `Accessibility.getFullAXTree` is shared; callers (`AccessibilityTest`) are in shared test. No platform fork.

## Phase 01 commit summary

Commit `c025b00e6` message (verbatim):

```
[browser] Phase 01: CdpBackend runtime class on kyo-jsonrpc

Introduces new CdpBackend runtime class atop JsonRpcEndpoint. Old
object CdpBackend renamed to CdpBackendOld for one phase; 28 forwarder
methods in the new CdpBackend companion bridge existing CdpBackend.foo
call sites without touching Browser.scala / BrowserTab.scala (those
cut over in Phase 02). The forwarders + CdpBackendOld both get deleted
in Phase 02.

New shape:
- CdpBackend class: endpoint, dialogHandlers, dialogDrainer fiber,
  dialogQueue, frameEventDispatchers, downloadEventDispatchers,
  dialogRecorders, lastEvaluateParams, sessionId
- send[P,R] / sendUnit[P] / withSession / close(grace) / closeNow /
  awaitDrain / init / initUnscoped (the latter pair returning
  < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]))
- 5 notification handlers via JsonRpcMethod.notification:
  Page.javascriptDialogOpening, Runtime.executionContext{Created,
  Destroyed}, Page.downloadWillBegin, Page.downloadProgress

Q-002 ratification wired: Browser.getVersion probe inside
CdpBackend.initUnscoped recovers Closed to BrowserSetupFailedException
(per Decision 21).

New types in CdpTypes: BrowserGetVersionParams, BrowserVersionResult
(both derives Schema, private[kyo]).
```

Key file anchors produced in Phase 01:
- `CdpBackend.scala` class definition: lines 17-114
- Companion `init`/`initUnscoped` (ws-url version): lines 138-219
- Companion `initUnscoped` (transport version, for tests): lines 359-422
- 5 notification handler builders: lines 431-492 (`buildDialogMethod`, `buildFrameCreatedMethod`, `buildFrameDestroyedMethod`, `buildDownloadWillMethod`, `buildDownloadProgressMethod`)
- 28 forwarder methods (Phase 02 deletes): lines 221-353
- `CdpBackendOld` (Phase 02 deletes): lines 633-829

## CdpClient API surface to delete

All methods are `private[kyo]` (package-internal); file: `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala`.

```
CdpClient class (lines 23-191):
  - send[P: Schema](method, params)(using Frame): String < (Async & Abort[BrowserReadException])  [line 42]
  - send(method)(using Frame): String < (Async & Abort[BrowserReadException])  [line 46]
  - sendUnit[P: Schema](method, params)(using Frame): Unit < (Async & Abort[BrowserReadException])  [line 50]
  - sendUnit(method)(using Frame): Unit < (Async & Abort[BrowserReadException])  [line 54]
  - withSession(sid: SessionId): CdpClient  [line 118]
  - close(gracePeriod: Duration)(using Frame): Unit < Async  [line 142]
  - closeNow(using Frame): Unit < Async  [line 158]
  - awaitDrain(using Frame): Unit < Async  [line 185]
  Fields: exchange, outbound, inbound, relay, dialogHandlers, dialogDrainer, dialogQueue,
          inFlight, drainSignal, frameEventDispatchers, downloadEventDispatchers,
          dialogRecorders, lastEvaluateParams, cdpMeter, requestTimeout, sessionId

CdpClient object (lines 193-558):
  - maxInFlight: Int = 8  [line 206]
  - init(wsUrl, launchCfg)(using Frame): CdpClient < (Async & Scope & Abort[BrowserReadException])  [line 209]
  - initUnscoped(wsUrl, launchCfg)(using Frame): CdpClient < (Async & Abort[BrowserReadException])  [line 218]
  - eventWhitelist: Set[String]  [line 375]
  - isWhitelistedEvent(method): Boolean  [line 380]
  - decodeCdpMessage(wire, dialogHandlers, dialogQueue, frameEventDispatchers, downloadEventDispatchers, dialogRecorders): Exchange.Message[Int, String, CdpEvent] < Sync  [line 398]
  (private) recordDialogEvent(...)  [line 510]
  (private) fallbackDecode(wire, reason): Exchange.Message[Int, String, CdpEvent] < Sync  [line 548]
```

External references to `CdpClient` from outside `CdpClient.scala` (production code only):

| File | Line | Usage |
|------|------|-------|
| `Browser.scala` | 258 | `CdpClient.init(wsUrl, launch)` |
| `Browser.scala` | 273 | `CdpClient.init(wsUrl, Browser.LaunchConfig.default)` |
| `BrowserTab.scala` | 22 | `val client: CdpClient` (field type) |
| `BrowserTab.scala` | 35 | `val session: CdpClient = client.withSession(sessionId)` |
| `BrowserTab.scala` | 55 | `client: CdpClient` (mkBrowserTab param) |
| `BrowserTab.scala` | 214 | `def attachAndSetupTab(client: CdpClient)` |
| `BrowserTab.scala` | 245 | `def enableDomains(sender: CdpSender)` |
| `cdp/PageDownload.scala` | 4 | `import kyo.internal.CdpClient` |
| `cdp/PageDownload.scala` | 58 | `client: CdpClient` (setDownloadBehavior param) |
| `cdp/Accessibility.scala` | 4 | `import kyo.internal.CdpClient` |
| `cdp/Accessibility.scala` | 32 | `client: CdpClient` (getFullAXTree param) |
| `cdp/Accessibility.scala` | 36 | `client: CdpClient` (getFullAXTreeForFrame param) |
| `Resolver.scala` | 212 | `s: CdpClient` (describeByObjectId private param) |
| `Resolver.scala` | 223 | `s: CdpClient` (requestAndDescribe private param) |
| `Resolver.scala` | 239 | `s: CdpClient` (releaseObjectQuiet private param) |

Note: `Resolver.scala` also does `import CdpBackend.decodeOrFail` which references `CdpBackend.decodeOrFail` (a forwarder to `CdpBackendOld`). Both the import and the forwarder die in Phase 02.

## CdpBackendOld API surface to delete

`object CdpBackendOld` at `CdpBackend.scala:641-829`:

```
- RuntimeEvaluateMethod: String (alias to CdpBackend.RuntimeEvaluateMethod)  [line 643]
- decodeOrFail[A: Schema](wire, method)(using Frame): A < Abort[BrowserReadException]  [line 648]
- getNavigationHistory(sender: CdpSender)(using Frame): NavigationHistory < (Async & Abort[BrowserReadException])  [line 668]
- navigate(sender, params: NavigateParams): Unit  [line 672]
- navigateToHistoryEntry(sender, params: NavigateToEntryParams): Unit  [line 678]
- reload(sender, params: ReloadParams): Unit  [line 683]
- getFrameTree(sender): GetFrameTreeResult  [line 690]
- captureScreenshot(sender, params: ScreenshotParams): ScreenshotResult  [line 694]
- printToPDF(sender): PrintToPdfResult  [line 700]
- runtimeEvaluate(sender, params: EvalParams): String  [line 704]
- setDeviceMetricsOverride(sender, params: ViewportParams): Unit  [line 710]
- clearDeviceMetricsOverride(sender): Unit  [line 716]
- dispatchKeyEvent(sender, params: DispatchKeyEventParams): Unit  [line 720]
- dispatchMouseEvent(sender, params: MouseEventParams): Unit  [line 726]
- getProperties(sender, params: GetPropertiesParams): GetPropertiesResult  [line 732]
- getDocument(sender, params: GetDocumentParams): Unit  [line 738]
- requestNode(sender, params: RequestNodeParams): RequestNodeResult  [line 744]
- describeNodeByNodeId(sender, params: DescribeNodeParams): DescribeNodeResult  [line 750]
- describeNodeByBackendId(sender, params: DescribeNodeByBackendIdParams): DescribeNodeResult  [line 756]
- getBoxModel(sender, params: GetBoxModelParams): BoxModel  [line 762]
- setFileInputFiles(sender, params: SetFileInputFilesParams): Unit  [line 768]
- getCookies(sender): NetworkGetCookiesResult  [line 774]
- getCookies(sender, params: NetworkGetCookiesParams): NetworkGetCookiesResult  [line 778]
- setCookie(sender, params: NetworkSetCookieParams): Unit  [line 784]
- deleteCookies(sender, params: NetworkDeleteCookiesParams): Unit  [line 790]
- getTargets(sender): GetTargetsResult  [line 796]
- attachToTarget(sender, params: AttachParams): AttachResult  [line 800]
- createTarget(sender, params: CreateTargetParams): CreateTargetResult  [line 806]
- createBrowserContext(sender): CreateBrowserContextResult  [line 812]
- closeTarget(sender, params: CloseTargetParams): Unit  [line 818]
- disposeBrowserContext(sender, params: DisposeBrowserContextParams): Unit  [line 824]
```

## CdpBackend companion forwarder methods to delete

Lines 221-353 of `CdpBackend.scala`, 28 methods:

```
decodeOrFail[A: Schema](wire, method)  [line 227]
getNavigationHistory(sender: CdpSender)  [line 230]
navigate(sender: CdpSender, params: NavigateParams)  [line 233]
navigateToHistoryEntry(sender: CdpSender, params: NavigateToEntryParams)  [line 236]
reload(sender: CdpSender, params: ReloadParams)  [line 241]
getFrameTree(sender: CdpSender)  [line 244]
captureScreenshot(sender: CdpSender, params: ScreenshotParams)  [line 247]
printToPDF(sender: CdpSender)  [line 252]
runtimeEvaluate(sender: CdpSender, params: EvalParams)  [line 255]
setDeviceMetricsOverride(sender: CdpSender, params: ViewportParams)  [line 258]
clearDeviceMetricsOverride(sender: CdpSender)  [line 263]
dispatchKeyEvent(sender: CdpSender, params: DispatchKeyEventParams)  [line 266]
dispatchMouseEvent(sender: CdpSender, params: MouseEventParams)  [line 271]
getProperties(sender: CdpSender, params: GetPropertiesParams)  [line 276]
getDocument(sender: CdpSender, params: GetDocumentParams)  [line 281]
requestNode(sender: CdpSender, params: RequestNodeParams)  [line 284]
describeNodeByNodeId(sender: CdpSender, params: DescribeNodeParams)  [line 289]
describeNodeByBackendId(sender: CdpSender, params: DescribeNodeByBackendIdParams)  [line 294]
getBoxModel(sender: CdpSender, params: GetBoxModelParams)  [line 299]
setFileInputFiles(sender: CdpSender, params: SetFileInputFilesParams)  [line 304]
getCookies(sender: CdpSender) [overload 1]  [line 309]
getCookies(sender: CdpSender, params: NetworkGetCookiesParams) [overload 2]  [line 312]
setCookie(sender: CdpSender, params: NetworkSetCookieParams)  [line 317]
deleteCookies(sender: CdpSender, params: NetworkDeleteCookiesParams)  [line 321]
getTargets(sender: CdpSender)  [line 327]
attachToTarget(sender: CdpSender, params: AttachParams)  [line 330]
createTarget(sender: CdpSender, params: CreateTargetParams)  [line 335]
createBrowserContext(sender: CdpSender)  [line 340]
closeTarget(sender: CdpSender, params: CloseTargetParams)  [line 345]
disposeBrowserContext(sender: CdpSender, params: DisposeBrowserContextParams)  [line 348]
```

## Call-site inventory (exhaustive)

All call sites that must be rewired in Phase 02. Notation: `[prod]` = src/main/, `[test]` = src/test/.

### CdpClient references

| File:Line | Call form | Production/Test | Rewire to |
|-----------|-----------|-----------------|-----------|
| `Browser.scala:258` | `CdpClient.init(wsUrl, launch)` | prod | `CdpBackend.init(wsUrl, launch)` |
| `Browser.scala:273` | `CdpClient.init(wsUrl, Browser.LaunchConfig.default)` | prod | `CdpBackend.init(wsUrl, Browser.LaunchConfig.default)` |
| `BrowserTab.scala:22` | `val client: CdpClient` | prod | `val backend: CdpBackend` |
| `BrowserTab.scala:35` | `val session: CdpClient = client.withSession(sessionId)` | prod | `val session: CdpBackend = backend.withSession(sessionId)` |
| `BrowserTab.scala:55` | `client: CdpClient` (mkBrowserTab param) | prod | `backend: CdpBackend` |
| `BrowserTab.scala:214` | `attachAndSetupTab(client: CdpClient)` | prod | `attachAndSetupTab(backend: CdpBackend)` |
| `BrowserTab.scala:245` | `enableDomains(sender: CdpSender)` | prod | `enableDomains(sender: CdpBackend)` |
| `cdp/PageDownload.scala:4` | `import kyo.internal.CdpClient` | prod | `import kyo.internal.CdpBackend` |
| `cdp/PageDownload.scala:58` | `client: CdpClient` param | prod | `client: CdpBackend` |
| `cdp/Accessibility.scala:4` | `import kyo.internal.CdpClient` | prod | `import kyo.internal.CdpBackend` |
| `cdp/Accessibility.scala:32` | `client: CdpClient` param | prod | `client: CdpBackend` |
| `cdp/Accessibility.scala:36` | `client: CdpClient` param | prod | `client: CdpBackend` |
| `Resolver.scala:212` | `s: CdpClient` private param | prod | `s: CdpBackend` |
| `Resolver.scala:223` | `s: CdpClient` private param | prod | `s: CdpBackend` |
| `Resolver.scala:239` | `s: CdpClient` private param | prod | `s: CdpBackend` |

### CdpBackend companion forwarder call sites (production code)

These call `CdpBackend.XYZ(sender: CdpSender, ...)` where `sender` is either `tab.session` (`CdpClient`) or `tab.client` / `parent.client`. After Phase 02 the same method names remain on the companion but take `backend: CdpBackend`.

| File:Line | Call form | Rewire |
|-----------|-----------|--------|
| `Browser.scala:336` | `CdpBackend.navigate(tab.session, NavigateParams(url))` | param type changes; no rename |
| `Browser.scala:347` | `CdpBackend.getNavigationHistory(tab.session)` | same |
| `Browser.scala:368` | `CdpBackend.navigateToHistoryEntry(tab.session, ...)` | same |
| `Browser.scala:386` | `CdpBackend.navigateToHistoryEntry(tab.session, ...)` | same |
| `Browser.scala:407` | `CdpBackend.reload(tab.session, ...)` | same |
| `Browser.scala:590,601,684,695,1933,1938,1954,1973` | `CdpBackend.dispatchKeyEvent(s, ...)` | same (`s = tab.session`) |
| `Browser.scala:1591` | `CdpBackend.getBoxModel(tab.session, ...)` | same |
| `Browser.scala:1775` | `CdpBackend.captureScreenshot(tab.session, ...)` | same |
| `Browser.scala:1795` | `CdpBackend.printToPDF(tab.session)` | same |
| `Browser.scala:1824` | `CdpBackend.captureScreenshot(tab.session, ...)` | same |
| `Browser.scala:1874,1885,1907,1913,1915` | `CdpBackend.setDeviceMetricsOverride` / `clearDeviceMetricsOverride` | same |
| `Browser.scala:2290` | `CdpBackend.getFrameTree(tab.session)` | same |
| `Browser.scala:2614` | `CdpBackend.getCookies(tab.session)` | same |
| `Browser.scala:2626` | `CdpBackend.getCookies(tab.session, ...)` | same |
| `Browser.scala:2636,2657` | `CdpBackend.setCookie(tab.session, ...)` | same |
| `Browser.scala:2676,2680,2690` | `CdpBackend.runtimeEvaluate` / `CdpBackend.deleteCookies` | same |
| `Browser.scala:2737,2744` | `CdpBackend.getTargets(parent.client)` | `parent.client` -> `parent.backend` |
| `Browser.scala:2757` | `CdpBackend.closeTarget(parent.client, ...)` | `parent.client` -> `parent.backend` |
| `Browser.scala:2760,2765` | `CdpBackend.attachToTarget(parent.client, ...)` | same |
| `Browser.scala:2817` | `CdpBackend.createTarget(parent.client, ...)` | same |
| `Browser.scala:2819` | `CdpBackend.closeTarget(parent.client, ...)` | same |
| `Browser.scala:2821,2825` | `CdpBackend.attachToTarget(parent.client, ...)` | same |
| `Browser.scala:3496` | `CdpBackend.setFileInputFiles(tab.session, ...)` | same |
| `BrowserEval.scala:26,38,52` | `CdpBackend.runtimeEvaluate(tab.session, ...)` | same |
| `BrowserEval.scala:211` | `CdpBackend.getBoxModel(tab.session, ...)` | same |
| `BrowserEval.scala:230` | `CdpBackend.dispatchMouseEvent(tab.session, ...)` | same |
| `Actionability.scala:71` | `CdpBackend.runtimeEvaluate(tab.session, ...)` | same |
| `IFrame.scala:43` | `CdpBackend.describeNodeByBackendId(s, ...)` | same |
| `BrowserSnapshot.scala:118,123,165,173,181,192,201,229,233,261` | `CdpBackend.runtimeEvaluate` / `CdpBackend.getCookies` | same |
| `CookieWire.scala:148` | `CdpBackend.runtimeEvaluate(tab.session, ...)` | same |
| `BrowserTab.scala:143` | `CdpBackend.getFrameTree(tab.session)` | same |
| `BrowserTab.scala:182,202` | `CdpBackend.runtimeEvaluate(tab.session, ...)` | same |
| `BrowserTab.scala:218` | `CdpBackend.createBrowserContext(client)` | `client` -> `backend` |
| `BrowserTab.scala:222,223` | `CdpBackend.createTarget/attachToTarget(client, ...)` | same |
| `BrowserTab.scala:266,268,270,271,275` | `CdpBackend.createBrowserContext/disposeBrowserContext/createTarget/attachToTarget(parent.client, ...)` | `parent.client` -> `parent.backend` |
| `MutationSettlement.scala:187` | `CdpBackend.runtimeEvaluate(tab.session, ...)` | same |
| `NavigationWatcher.scala:560` | `CdpBackend.runtimeEvaluate(tab.client.withSession(tab.sessionId), ...)` | `tab.client.withSession(tab.sessionId)` -> `tab.session` |
| `Resolver.scala:132` | `CdpBackend.getProperties(s, ...)` | same (s type changes) |
| `Resolver.scala:216` | `CdpBackend.getDocument(s, ...)` | same |
| `Resolver.scala:226` | `CdpBackend.requestNode(s, ...)` | same |
| `Resolver.scala:229` | `CdpBackend.describeNodeByNodeId(s, ...)` | same |

### `decodeOrFail` call sites

| File:Line | Usage | Rewire |
|-----------|-------|--------|
| `Resolver.scala:3` | `import CdpBackend.decodeOrFail` | Delete import; inline decode pattern |
| `Resolver.scala:50` | `decodeOrFail[EvaluateObjectResult](evalJson, ...)` | Inline `Json.decode[CdpReply[EvaluateObjectResult]](evalJson)` pattern |
| `Resolver.scala:116` | `decodeOrFail[EvaluateObjectResult](evalJson, ...)` | Same |

### Test files that reference CdpClient (Phase 02 MUST NOT delete these)

These files will fail to compile after `CdpClient.scala` is deleted. Per the plan, Phase 02 does NOT delete tests; Phase 03 handles them. The atomic-green rule means Phase 02 cannot delete `CdpClient.scala` until either (a) these tests are fixed to compile without it, or (b) the compile step excludes test sources. Since the plan mandates compile-green at each sbt invocation, the impl agent MUST address these test files' compilation before committing the deletion.

| Test file | References |
|-----------|-----------|
| `shared/src/test/scala/kyo/internal/CdpClientTest.scala` | `CdpClient.init`, `CdpClient.initUnscoped` throughout |
| `shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala` | `CdpClient.init`, `CdpClient.initUnscoped` throughout |
| `shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala` | `CdpClient.decodeCdpMessage`, `CdpClient.eventWhitelist` |
| `shared/src/test/scala/kyo/internal/CdpBackendTest.scala` | `CdpSender` trait, `FakeCdpSender` |
| `shared/src/test/scala/kyo/BrowserCoreTest.scala` | `import kyo.internal.CdpClient`, `CdpClient.initUnscoped` |
| `shared/src/test/scala/kyo/internal/cdp/PageDownloadTest.scala` | `import kyo.internal.CdpClient`, `CdpClient.initUnscoped` |
| `shared/src/test/scala/kyo/internal/cdp/AccessibilityTest.scala` | `import kyo.internal.CdpClient`, `CdpClient.initUnscoped` |
| `jvm/src/test/scala/kyo/internal/CdpClientLifecycleJvmTest.scala` | `CdpClient.initUnscoped` throughout |

## Dependency-injection seam

`Browser.run(launch, session)` creates the `CdpClient` (post Phase 02: `CdpBackend`) instance and threads it into `BrowserTabSetup.attachAndSetupTab(client)`. The tab factory `mkBrowserTab` stores `client` as the `BrowserTab.client` field (post Phase 02: `backend: CdpBackend`). The session-scoped `tab.session` is derived once as `backend.withSession(sessionId)` and captured on the `BrowserTab` instance.

Construction sites:
- `Browser.scala:258-259`: `client <- CdpBackend.init(wsUrl, launch)` then `tab <- BrowserTabSetup.attachAndSetupTab(client)`
- `Browser.scala:273`: `CdpBackend.init(wsUrl, ...).map(client => BrowserTabSetup.attachAndSetupTab(client).map(tab => ...))`
- `BrowserTabSetup.mkBrowserTab(targetId, sessionId, client, browserContextId)`: constructs `new BrowserTab(targetId, sessionId, client, ...)` at `BrowserTab.scala:65-76`

No factory method; pure dependency injection through the constructor. No need for `Env` injection; `BrowserTab` holds the `CdpBackend` (formerly `CdpClient`) instance directly as a `val`.

## Atomic-green check: mandatory ordering

Phase 02 must maintain compile-green at every intermediate sbt invocation. Required ordering:

1. **Relocate shared types out of `CdpClient.scala`**: Move `CdpReply`, `CdpEventParams`, `CdpError`, `CdpEvent`, `CdpEvent.Generic`, `CdpNoParams`, `CdpEnvelope`, `CdpWireMessage`, `CdpSender`, `JavascriptDialogOpeningParams`, `FallbackIdEnvelope` to `CdpTypes.scala` or `CdpWire.scala`. Verify no duplicate declarations. Compile. This step does NOT delete `CdpClient.scala` yet.

2. **Replace 28 forwarders + CdpBackendOld in `CdpBackend.scala`**: Delete lines 221-353 (forwarders) and lines 633-829 (`CdpBackendOld`). Simultaneously add the 28 new two-line companion wrappers taking `backend: CdpBackend`. This is one atomic edit. Compile: all existing call sites in `Browser.scala` etc. still pass `tab.session: CdpClient` to these wrappers -- the wrappers now take `CdpBackend` but `CdpClient` is still in scope (not deleted yet). If `CdpClient extends CdpSender` and the new wrappers take `CdpBackend`, the call sites will NOT compile because `CdpClient` is not a `CdpBackend`. Therefore this step must be done TOGETHER with step 3.

3. **Rename `BrowserTab.client: CdpClient` to `BrowserTab.backend: CdpBackend`**: Change field name and type in the class definition, `mkBrowserTab`, `attachAndSetupTab`, `createChildTab`. Update all `tab.client` / `parent.client` references in `Browser.scala`, `BrowserTab.scala`, `NavigationWatcher.scala`. Update `tab.session` type. Change `enableDomains(sender: CdpSender)` to `(sender: CdpBackend)`. Compile. At this point the production code is clean.

4. **Update `Resolver.scala`**: Delete `import CdpBackend.decodeOrFail`; inline the decode logic at the two call sites. Update private method parameter types from `CdpClient` to `CdpBackend`. Compile.

5. **Update `cdp/Accessibility.scala` and `cdp/PageDownload.scala`**: Change `CdpClient` to `CdpBackend`; update `send` / `sendUnit` calls. Compile.

6. **Update `Browser.scala` init sites**: Change `CdpClient.init` to `CdpBackend.init` at lines 258 and 273. Handle `BrowserSetupException` absorption if needed for public surface preservation. Compile.

7. **Handle test compile**: The listed test files reference `CdpClient`. Per plan, Phase 02 must not delete tests but must keep compile-green. The only way to achieve both is to minimally rewire the test files' imports and `CdpClient.*` call sites to use `CdpBackend.*` (changing `CdpClient.init` -> `CdpBackend.init`, `CdpClient.initUnscoped` -> `CdpBackend.initUnscoped`). `CdpClientDecoderTest.scala` and `CdpBackendTest.scala` are more complex (they test `CdpClient.decodeCdpMessage` and `CdpSender`); these require deeper rewires or must be marked as Phase 03 deletions. Coordinate with the plan: Phase 03 deletes `CdpClientTest.scala` and `CdpClientDecoderTest.scala`, renames `CdpClientLifecycleTest.scala`. For Phase 02 compile-green without touching test logic: the minimum is to have the test files compile, which may require stub-forwarding or skipping the deep-API tests.

8. **Delete `CdpClient.scala`**: Only after all above steps compile. Run `kyo-browserJVM/Test/compile` to confirm test sources also compile.

9. **Run verification**: `sbt 'kyo-browserJVM/Test/compile' 'kyo-browserJS/Test/compile' 'kyo-browserNative/Test/compile' 'kyo-browserJVM/testOnly *JsonRpcPortInvariantsSpec*' 'kyo-browserJVM/testOnly *CdpBackendSmokeTest*'`

## Risk surface

1. **`runtimeEvaluate` result type mismatch** (HIGH): The new `send[EvalParams, R]` where `R` is typed will decode `Runtime.evaluate`'s result slot. Chrome returns `{"type":"string","value":"..."}` as the result object, NOT a bare string. If `R = String`, decoding fails. Safe resolution: use `R = EvalResult` (the typed CDP result shape) and either re-encode to JSON string OR update all consumers to use the typed path. This is the highest-risk item.

2. **Types defined in `CdpClient.scala` used elsewhere** (HIGH): `CdpReply`, `CdpEvent.Generic`, `CdpEventParams`, `CdpError` are referenced from production files that are NOT deleted. These must be moved to `CdpTypes.scala` or `CdpWire.scala` before `CdpClient.scala` is deleted.

3. **Test compile breakage** (HIGH): 8 test files reference `CdpClient`. These must compile after deletion. Phase 02 cannot simply leave them broken and still maintain compile-green. The impl agent must minimally rewire each test file's `CdpClient.*` calls to `CdpBackend.*` equivalents (or add temporary type aliases) to unblock compilation.

4. **`decodeOrFail` in `Resolver.scala`** (MEDIUM): Inlining the decode logic is straightforward but must be exact: `Json.decode[CdpReply[A]](wire)` then pattern-match on `reply.result` / `reply.error`, failing with `BrowserProtocolErrorException`.

5. **`enableDomains` zero-param sends** (MEDIUM): `sender.send("Page.enable").unit` uses `CdpSender.send(method: String)` (no params). In `CdpBackend`, `send[CdpNoParams, Unit]("Page.enable", CdpNoParams())` replaces this. Verify `CdpNoParams` is still available after type relocation.

6. **`lastEvaluateParams` diagnostic** (LOW): On `CdpClient`, `lastEvaluateParams` is accessed in tests via the `CdpClient` field directly. On `CdpBackend`, it is accessible as `backend.lastEvaluateParams`. Test rewires (Phase 03) handle this.

7. **`Accessibility.parseAxTree` decode shape** (MEDIUM): Currently decodes `CdpReply[AxTreeResponse]` from the full wire. After switching to typed `send[.., AxTreeResponse]`, the typed result is already `AxTreeResponse` (not the full `CdpReply` envelope). The `parseAxTree` function and `getFullAXTree` body must both be updated consistently.

8. **`BrowserCoreTest.scala` uses `CdpClient` for an observation client** (LOW per Phase boundary): `BrowserCoreTest.scala:946` creates a second `CdpClient.initUnscoped` for observation. After Phase 02 this becomes `CdpBackend.initUnscoped`. The test must also pass `Browser.LaunchConfig` for the `BrowserSetupException` case; since it connects to a running Chrome (not a fresh init), `BrowserSetupException` is unlikely but possible. Rewire carefully.

## Expected LoC delta

- DELETE `CdpClient.scala`: -628 LoC (whole file)
- DELETE `CdpBackendOld` object: -197 LoC (lines 633-829)
- DELETE 28 forwarders in `CdpBackend` companion: -133 LoC (lines 221-353)
- DELETE `CdpSender` trait: -5 LoC (moved or deleted)
- ADD 28 two-line companion wrappers: +84 LoC
- MODIFY `BrowserTab.scala`: ~15 LoC changed (field renames)
- MODIFY `Browser.scala`: ~25 LoC changed (init sites + field renames)
- MODIFY `Resolver.scala`: ~15 LoC (inline decodeOrFail at 2 sites)
- MODIFY other consumers: ~30 LoC across `Accessibility.scala`, `PageDownload.scala`, `NavigationWatcher.scala`, `CdpWire.scala` or `CdpTypes.scala` (type relocation)
- Test file minimal rewires (Phase 02 compile unblock): ~40 LoC

Net delta: approximately -800 to -900 LoC on the module. Total dirty tree: ~1000 LoC affected.

## Concerns

- The plan (`05-plan.yaml`) Phase 02 `files_modified` entry for `BrowserSnapshot.scala` says `after: CdpBackend.captureScreenshot(tab.backend, params)`. This implies the consumer switches from the raw-wire path to the typed wrapper. However, `captureScreenshot` currently takes `sender: CdpSender` and returns `ScreenshotResult`. After Phase 02 the wrapper takes `backend: CdpBackend`. The existing call site `CdpBackend.captureScreenshot(tab.session, params)` in `Browser.scala:1775` still resolves because `tab.session` is now `CdpBackend`. No code logic change needed -- just the parameter type on the companion. Confirm that `ScreenshotResult` is in `CdpTypes.scala` and not in `CdpClient.scala`.

- The plan `05-plan.yaml` lists `BrowserAssertion.scala` as a file to modify (Phase 02 yaml entry: `path: kyo-browser/shared/src/main/scala/kyo/internal/BrowserAssertion.scala`). The grep above did not surface any `CdpClient` or `tab.client` references in `BrowserAssertion.scala`. The impl agent should `grep -n "client\|CdpClient\|CdpSender" BrowserAssertion.scala` to verify whether any edits are actually needed; if none, skip.

- `ChromeDownloader.scala` is listed in the plan's Phase 02 modified files (`path: kyo-browser/shared/src/main/scala/kyo/internal/ChromeDownloader.scala`). The grep above did not surface `CdpClient` references there either. Verify before editing.

- INV-007 specifies that `cdp/Accessibility.scala` must be byte-identical post-Phase-02 relative to its pre-port baseline (it is in the stability-layer KEEP list per the plan's invariants test). However Phase 02 MUST rename its `CdpClient` parameter to `CdpBackend`. This is a contradiction: INV-007 says "sha256 matches" but Phase 02 changes the file. Verify the exact INV-007 wording in `04-invariants.md` -- it may be that `Accessibility.scala` was removed from the byte-identical list (it references `CdpClient` which is a Phase 02 target). Route to supervisor if the invariant text is ambiguous.
