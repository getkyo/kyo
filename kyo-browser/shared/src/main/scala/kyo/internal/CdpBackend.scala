package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.JsonRpcExtrasEncoder
import kyo.JsonRpcIdStrategy
import kyo.JsonRpcUnknownMethodPolicy
import kyo.internal.cdp.PageDownload

/** Runtime CDP backend built atop a [[JsonRpcHandler]]. Owns the per-connection
  * dispatcher tables (frame-event / download-event / dialog handlers / dialog
  * recorders), the dialog drainer fiber, the lastEvaluateParams diagnostic, the
  * per-session JsonRpcExtrasEncoder, and the 5 CDP notification handlers. Wire framing,
  * codec dispatch, request correlation, per-call timeout, in-flight metering,
  * drain signal, graceful close, and malformed-envelope routing are owned by the
  * embedded [[JsonRpcHandler]].
  */
final private[kyo] class CdpBackend private[kyo] (
    private[kyo] val endpoint: JsonRpcHandler,
    private[kyo] val dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]],
    private[kyo] val dialogDrainer: Fiber[Unit, Any],
    private[kyo] val dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
    private[kyo] val frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val screencastEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val consoleEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]],
    private[kyo] val lastEvaluateParams: AtomicRef[Maybe[String]],
    private[kyo] val sessionId: Maybe[SessionId] = Absent
):

    /** Typed CDP call. Records lastEvaluateParams on Runtime.evaluate, stamps
      * sessionId via JsonRpcExtrasEncoder, recovers engine errors to kyo-browser's
      * typed BrowserReadException tree (INV-017).
      */
    private[kyo] def send[P: Schema, R: Schema](method: String, params: P)(using
        Frame
    ): R < (Async & Abort[BrowserReadException]) =
        val record =
            if method == CdpBackend.RuntimeEvaluateMethod
            then lastEvaluateParams.set(Present(Json.encode(params)))
            else Kyo.unit
        val extras = sessionId match
            case Present(sid) => JsonRpcExtrasEncoder.const(
                    Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value)))
                )
            case Absent => JsonRpcExtrasEncoder.empty
        record.andThen {
            Abort.recover[Closed] { closed =>
                Abort.fail(BrowserConnectionLostException(
                    s"Connection lost: ${closed.getMessage}",
                    Present(closed)
                ))
            } {
                Abort.recover[JsonRpcError] {
                    // Timeout: code -32800 is how the engine surfaces a requestTimeout expiry.
                    // Map it to BrowserConnectionLostException to preserve legacy CdpClient.submit semantics.
                    case e: JsonRpcCustomError if e.code == -32800 =>
                        Abort.fail(BrowserConnectionLostException(
                            s"Request timeout: $method",
                            Absent
                        ))
                    // Transport/lifecycle errors: the connection is lost at the wire level.
                    case _: JsonRpcTransportError | _: JsonRpcLifecycleError =>
                        Abort.fail(BrowserConnectionLostException(
                            s"Connection lost during $method",
                            Absent
                        ))
                    case err =>
                        Abort.fail(BrowserProtocolErrorException(method, err.message, Present(err.code)))
                } {
                    endpoint.call[P, R](method, params, extras)
                }
            }
        }
    end send

    /** Unit-typed send: discards the typed result. Wrappers that do not consume
      * a result envelope (e.g. Page.navigate) call this.
      */
    private[kyo] def sendUnit[P: Schema](method: String, params: P)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        send[P, Unit](method, params)

    /** Session-scoped fork. All dispatcher tables and the endpoint are shared
      * with the parent; only the sessionId field differs. JsonRpcExtrasEncoder is
      * recomputed per-call from this field, so the wire byte shape is
      * identical to the legacy CdpClient.withSession.
      */
    private[kyo] def withSession(sid: SessionId): CdpBackend =
        new CdpBackend(
            endpoint,
            dialogHandlers,
            dialogDrainer,
            dialogQueue,
            frameEventDispatchers,
            downloadEventDispatchers,
            screencastEventDispatchers,
            consoleEventDispatchers,
            dialogRecorders,
            lastEvaluateParams,
            sessionId = Present(sid)
        )

    /** Graceful close: waits up to gracePeriod for in-flight requests to drain,
      * then sequentially closes the endpoint (transport) and stops the dialog
      * drainer. Both closeOrderly and closeNow WAIT for the drainer fiber to
      * fully stop (via getResult) before returning, so the caller is guaranteed
      * the WS is fully torn down. This prevents the two-simultaneous-clients
      * scenario: if close() returned while transport.close was still running in
      * the background, a new CdpBackend.init on the same Chrome instance would
      * open a second WS connection while the first was still live, causing Chrome
      * to silently drop events on the new connection.
      */
    private[kyo] def close(gracePeriod: Duration)(using Frame): Unit < Async =
        if gracePeriod == Duration.Zero then closeNow
        else
            Abort.run[Timeout](Async.timeout(gracePeriod)(closeOrderly))
                .map {
                    case Result.Success(_)          => Kyo.unit
                    case Result.Failure(_: Timeout) => closeNow
                    case Result.Panic(ex)           => closeNow.andThen(Abort.panic(ex))
                }

    /** Immediate close: forcefully stops the endpoint (no drain) then waits for
      * the dialog drainer fiber to fully stop before returning.
      */
    private[kyo] def closeNow(using Frame): Unit < Async =
        endpoint.closeNow
            .andThen(Abort.run[Closed](dialogQueue.close).unit)
            .andThen(dialogDrainer.interrupt.andThen(dialogDrainer.getResult.unit))

    /** Orderly close: drains in-flight requests then closes the endpoint and
      * stops the dialog drainer sequentially.
      */
    private def closeOrderly(using Frame): Unit < Async =
        awaitDrain
            .andThen(endpoint.close(Duration.Zero))
            .andThen(Abort.run[Closed](dialogQueue.close).unit)
            .andThen(dialogDrainer.interrupt.andThen(dialogDrainer.getResult.unit))

    /** Drain barrier: returns when all outstanding inflight calls have settled. */
    private[kyo] def awaitDrain(using Frame): Unit < Async =
        endpoint.awaitDrain

end CdpBackend

/** Static helpers + named constants + `init` / `initUnscoped` for [[CdpBackend]].
  *
  * Hosts the 28 typed CDP method wrappers (each a two-line body delegating to
  * `backend.send[P, R]` or `backend.sendUnit[P]`), plus the 5 notification handler
  * builders, dialog drainer, and the Q-002 `Browser.getVersion` connect-probe.
  */
private[kyo] object CdpBackend:

    /** CDP method name surfaced both at the call site and at the
      * lastEvaluateParams write site.
      */
    private[kyo] val RuntimeEvaluateMethod = "Runtime.evaluate"

    /** Per-connection in-flight cap. Carried verbatim from
      * CdpClient.scala:206; the engine binds this to
      * `Config.maxInFlight = Present(8)`.
      */
    private[kyo] val maxInFlight: Int = 8

    /** Scope-bound init: closes the backend on scope exit. */
    def init(wsUrl: String, launchCfg: Browser.LaunchConfig)(using
        Frame
    ): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
        Scope.acquireRelease(initUnscoped(wsUrl, launchCfg))(_.close(launchCfg.closeGrace))

    /** Unscoped init: caller-managed lifecycle. The Scope effect is preserved so
      * the caller can control the endpoint lifecycle. Use `CdpBackend.init` to
      * automatically register scope-based cleanup.
      */
    def initUnscoped(wsUrl: String, launchCfg: Browser.LaunchConfig)(using
        Frame
    ): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
        for
            dialogHandlers             <- AtomicRef.init[Dict[String, (Boolean, String)]](Dict.empty)
            dialogQueue                <- Channel.initUnscoped[(Boolean, String, Maybe[SessionId])](16)
            frameEventDispatchers      <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            downloadEventDispatchers   <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            screencastEventDispatchers <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            consoleEventDispatchers    <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            dialogRecorders            <- AtomicRef.init[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]](Dict.empty)
            lastEvaluateParams         <- AtomicRef.init[Maybe[String]](Absent)
            dialogIdCounter            <- AtomicInt.init(Int.MinValue)
            url                        <- parseWsUrl(wsUrl)
            transport <- (Abort.recover[HttpException] { e =>
                Abort.fail(BrowserConnectionLostException(s"WS transport setup: ${e.getMessage}", Absent))
            } {
                JsonRpcHttpTransport.webSocket(url, HttpHeaders.empty, JsonRpcEnvelope.lenientSchema)
            }: JsonRpcTransport < (Async & Scope & Abort[BrowserReadException]))
            dialogMethod         <- buildDialogMethod(dialogHandlers, dialogQueue, dialogRecorders)
            frameCreatedMethod   <- buildFrameCreatedMethod(frameEventDispatchers)
            frameDestroyedMethod <- buildFrameDestroyedMethod(frameEventDispatchers)
            downloadWillMethod   <- buildDownloadWillMethod(downloadEventDispatchers)
            downloadProgMethod   <- buildDownloadProgressMethod(downloadEventDispatchers)
            screencastMethod     <- buildScreencastFrameMethod(screencastEventDispatchers)
            consoleApiMethod     <- buildConsoleApiCalledMethod(consoleEventDispatchers)
            exceptionMethod      <- buildExceptionThrownMethod(consoleEventDispatchers)
            config = JsonRpcHandler.Config(
                codec = JsonRpcEnvelope.lenientSchema,
                cancellation = Absent,
                progress = Absent,
                unknownMethod = JsonRpcUnknownMethodPolicy.minimal,
                gate = Absent,
                maxInFlight = Present(maxInFlight),
                requestTimeout = launchCfg.requestTimeout,
                idStrategy = JsonRpcIdStrategy.SequentialInt,
                progressResetsTimeout = false
            )
            endpoint <- JsonRpcHandler.init(
                transport,
                Seq(
                    dialogMethod,
                    frameCreatedMethod,
                    frameDestroyedMethod,
                    downloadWillMethod,
                    downloadProgMethod,
                    screencastMethod,
                    consoleApiMethod,
                    exceptionMethod
                ),
                config
            )
            dialogDrainer <- buildDialogDrainer(endpoint, dialogQueue, dialogIdCounter)
            backend = new CdpBackend(
                endpoint,
                dialogHandlers,
                dialogDrainer,
                dialogQueue,
                frameEventDispatchers,
                downloadEventDispatchers,
                screencastEventDispatchers,
                consoleEventDispatchers,
                dialogRecorders,
                lastEvaluateParams,
                sessionId = Absent
            )
            // Q-002 ratified probe: Browser.getVersion proves the WS handshake
            // + one CDP round-trip is live. Closed -> BrowserSetupFailedException
            // surfaces an upfront typed error rather than a delayed hang.
            _ <- Abort.recover[BrowserReadException] {
                case ex: BrowserConnectionLostException =>
                    Abort.fail(BrowserSetupFailedException(
                        s"WS handshake failed: probe call returned Closed. Cause: ${ex.getMessage}",
                        ex.cause
                    ))
                case other => Abort.fail(other)
            } {
                backend.send[BrowserGetVersionParams, BrowserVersionResult](
                    "Browser.getVersion",
                    BrowserGetVersionParams()
                )
            }
        yield backend

    // --- Typed companion wrappers ---
    // Each wrapper calls backend.send[P, R] or backend.sendUnit[P] directly.

    private[kyo] def getNavigationHistory(backend: CdpBackend)(using Frame): NavigationHistory < (Async & Abort[BrowserReadException]) =
        backend.send[CdpNoParams, NavigationHistory]("Page.getNavigationHistory", CdpNoParams())

    private[kyo] def navigate(backend: CdpBackend, params: NavigateParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[NavigateParams]("Page.navigate", params)

    private[kyo] def navigateToHistoryEntry(backend: CdpBackend, params: NavigateToEntryParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[NavigateToEntryParams]("Page.navigateToHistoryEntry", params)

    private[kyo] def reload(backend: CdpBackend, params: ReloadParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[ReloadParams]("Page.reload", params)

    private[kyo] def getFrameTree(backend: CdpBackend)(using Frame): GetFrameTreeResult < (Async & Abort[BrowserReadException]) =
        backend.send[CdpNoParams, GetFrameTreeResult]("Page.getFrameTree", CdpNoParams())

    private[kyo] def captureScreenshot(backend: CdpBackend, params: ScreenshotParams)(using
        Frame
    ): ScreenshotResult < (Async & Abort[BrowserReadException]) =
        backend.send[ScreenshotParams, ScreenshotResult]("Page.captureScreenshot", params)

    private[kyo] def printToPDF(backend: CdpBackend)(using Frame): PrintToPdfResult < (Async & Abort[BrowserReadException]) =
        backend.send[CdpNoParams, PrintToPdfResult]("Page.printToPDF", CdpNoParams())

    /** Translates the destroyed-execution-context CDP error ("Cannot find context with specified id"),
      * raised when a stale iframe executionContextId is passed to Runtime.evaluate, into the typed
      * [[BrowserIFrameInvalidException]]. Shared by every Runtime.evaluate-family path so a destroyed
      * context surfaces as the same typed iframe error regardless of the typed result it decodes into.
      */
    private[kyo] def recoverContextDestroyed[A](effect: => A < (Async & Abort[BrowserReadException]))(using
        Frame
    ): A < (Async & Abort[BrowserReadException]) =
        Abort.recover[BrowserProtocolErrorException] { e =>
            if e.error.contains(CdpErrorStrings.ContextDestroyedErrorMessage) then
                Abort.fail(BrowserIFrameInvalidException(BrowserIFrameInvalidException.Reason.ContextDestroyed))
            else Abort.fail(e)
        }(effect)

    /** Evaluates a JavaScript expression and returns the typed [[EvalResult]] (`result: RemoteObject`,
      * `exceptionDetails`). The engine decodes the CDP reply through `Schema[EvalResult]`: there is no
      * raw-JSON round-trip and no hand-built envelope. Consumers project the value with
      * [[CdpEvalDecoder.extractValueOrFail]] (or read the typed fields directly).
      *
      * Two CDP error messages are recovered here so every caller gets a typed outcome:
      *   - context-destroyed (`Cannot find context with specified id`) -> [[BrowserIFrameInvalidException]],
      *     via the shared [[recoverContextDestroyed]] wrapper.
      *   - unreturnable-value (`Object couldn't be returned by value`, e.g. a JS `Symbol`) -> an
      *     `undefined` [[EvalResult]], mirroring the legacy empty-string semantics for that case.
      */
    private[kyo] def runtimeEvaluate(backend: CdpBackend, params: EvalParams)(using
        Frame
    ): EvalResult < (Async & Abort[BrowserReadException]) =
        recoverContextDestroyed {
            Abort.recover[BrowserProtocolErrorException] { e =>
                if e.error.contains(CdpErrorStrings.UnreturnableValueErrorMessage) then
                    EvalResult(RemoteObject.`undefined`(), Absent)
                else Abort.fail(e)
            } {
                backend.send[EvalParams, EvalResult](RuntimeEvaluateMethod, params)
            }
        }

    private[kyo] def setDeviceMetricsOverride(backend: CdpBackend, params: ViewportParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[ViewportParams]("Emulation.setDeviceMetricsOverride", params)

    private[kyo] def clearDeviceMetricsOverride(backend: CdpBackend)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        backend.send[CdpNoParams, Unit]("Emulation.clearDeviceMetricsOverride", CdpNoParams())

    /** Sets the emulated media type and/or media features (e.g. prefers-color-scheme). */
    private[kyo] def setEmulatedMedia(backend: CdpBackend, params: SetEmulatedMediaParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[SetEmulatedMediaParams]("Emulation.setEmulatedMedia", params)

    /** Overrides the default background color (used for transparent-background captures). */
    private[kyo] def setDefaultBackgroundColorOverride(backend: CdpBackend, params: SetDefaultBackgroundColorOverrideParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[SetDefaultBackgroundColorOverrideParams]("Emulation.setDefaultBackgroundColorOverride", params)

    // --- Page domain (screencast) ---

    /** Starts a screencast session, delivering frames via `Page.screencastFrame` events. */
    private[kyo] def startScreencast(backend: CdpBackend, params: StartScreencastParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[StartScreencastParams]("Page.startScreencast", params)

    /** Stops the current screencast session. */
    private[kyo] def stopScreencast(backend: CdpBackend)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        backend.send[CdpNoParams, Unit]("Page.stopScreencast", CdpNoParams())

    /** Acknowledges a screencast frame, allowing Chrome to deliver the next one. */
    private[kyo] def screencastFrameAck(backend: CdpBackend, params: ScreencastFrameAckParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[ScreencastFrameAckParams]("Page.screencastFrameAck", params)

    // --- Input domain ---

    /** Dispatches a keyboard event (keyDown / keyUp / char) to the focused element. */
    private[kyo] def dispatchKeyEvent(backend: CdpBackend, params: DispatchKeyEventParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[DispatchKeyEventParams]("Input.dispatchKeyEvent", params)

    private[kyo] def dispatchMouseEvent(backend: CdpBackend, params: MouseEventParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[MouseEventParams]("Input.dispatchMouseEvent", params)

    private[kyo] def getProperties(backend: CdpBackend, params: GetPropertiesParams)(using
        Frame
    ): GetPropertiesResult < (Async & Abort[BrowserReadException]) =
        backend.send[GetPropertiesParams, GetPropertiesResult]("Runtime.getProperties", params)

    private[kyo] def getDocument(backend: CdpBackend, params: GetDocumentParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.send[GetDocumentParams, Unit]("DOM.getDocument", params)

    private[kyo] def requestNode(backend: CdpBackend, params: RequestNodeParams)(using
        Frame
    ): RequestNodeResult < (Async & Abort[BrowserReadException]) =
        backend.send[RequestNodeParams, RequestNodeResult]("DOM.requestNode", params)

    private[kyo] def describeNodeByNodeId(backend: CdpBackend, params: DescribeNodeParams)(using
        Frame
    ): DescribeNodeResult < (Async & Abort[BrowserReadException]) =
        backend.send[DescribeNodeParams, DescribeNodeResult]("DOM.describeNode", params)

    private[kyo] def describeNodeByBackendId(backend: CdpBackend, params: DescribeNodeByBackendIdParams)(using
        Frame
    ): DescribeNodeResult < (Async & Abort[BrowserReadException]) =
        backend.send[DescribeNodeByBackendIdParams, DescribeNodeResult]("DOM.describeNode", params)

    private[kyo] def getBoxModel(backend: CdpBackend, params: GetBoxModelParams)(using
        Frame
    ): BoxModel < (Async & Abort[BrowserReadException]) =
        backend.send[GetBoxModelParams, BoxModel]("DOM.getBoxModel", params)

    private[kyo] def setFileInputFiles(backend: CdpBackend, params: SetFileInputFilesParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[SetFileInputFilesParams]("DOM.setFileInputFiles", params)

    private[kyo] def getCookies(backend: CdpBackend)(using Frame): NetworkGetCookiesResult < (Async & Abort[BrowserReadException]) =
        backend.send[CdpNoParams, NetworkGetCookiesResult]("Network.getCookies", CdpNoParams())

    private[kyo] def getCookies(backend: CdpBackend, params: NetworkGetCookiesParams)(using
        Frame
    ): NetworkGetCookiesResult < (Async & Abort[BrowserReadException]) =
        backend.send[NetworkGetCookiesParams, NetworkGetCookiesResult]("Network.getCookies", params)

    private[kyo] def setCookie(backend: CdpBackend, params: NetworkSetCookieParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[NetworkSetCookieParams]("Network.setCookie", params)

    private[kyo] def deleteCookies(backend: CdpBackend, params: NetworkDeleteCookiesParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[NetworkDeleteCookiesParams]("Network.deleteCookies", params)

    private[kyo] def getTargets(backend: CdpBackend)(using Frame): GetTargetsResult < (Async & Abort[BrowserReadException]) =
        backend.send[CdpNoParams, GetTargetsResult]("Target.getTargets", CdpNoParams())

    private[kyo] def attachToTarget(backend: CdpBackend, params: AttachParams)(using
        Frame
    ): AttachResult < (Async & Abort[BrowserReadException]) =
        backend.send[AttachParams, AttachResult]("Target.attachToTarget", params)

    private[kyo] def createTarget(backend: CdpBackend, params: CreateTargetParams)(using
        Frame
    ): CreateTargetResult < (Async & Abort[BrowserReadException]) =
        backend.send[CreateTargetParams, CreateTargetResult]("Target.createTarget", params)

    private[kyo] def createBrowserContext(backend: CdpBackend)(using
        Frame
    ): CreateBrowserContextResult < (Async & Abort[BrowserReadException]) =
        backend.send[CdpNoParams, CreateBrowserContextResult]("Target.createBrowserContext", CdpNoParams())

    private[kyo] def closeTarget(backend: CdpBackend, params: CloseTargetParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[CloseTargetParams]("Target.closeTarget", params)

    private[kyo] def disposeBrowserContext(backend: CdpBackend, params: DisposeBrowserContextParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        backend.sendUnit[DisposeBrowserContextParams]("Target.disposeBrowserContext", params)

    /** Transport-based init for testing: caller supplies a pre-wired [[JsonRpcTransport]]
      * instead of a WS URL. The [[Browser.getVersion]] probe still runs.
      * The Scope effect is preserved so the caller can control the endpoint lifecycle.
      */
    private[kyo] def initUnscoped(transport: JsonRpcTransport, launchCfg: Browser.LaunchConfig)(using
        Frame
    ): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
        for
            dialogHandlers             <- AtomicRef.init[Dict[String, (Boolean, String)]](Dict.empty)
            dialogQueue                <- Channel.initUnscoped[(Boolean, String, Maybe[SessionId])](16)
            frameEventDispatchers      <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            downloadEventDispatchers   <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            screencastEventDispatchers <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            consoleEventDispatchers    <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            dialogRecorders            <- AtomicRef.init[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]](Dict.empty)
            lastEvaluateParams         <- AtomicRef.init[Maybe[String]](Absent)
            dialogIdCounter            <- AtomicInt.init(Int.MinValue)
            dialogMethod               <- buildDialogMethod(dialogHandlers, dialogQueue, dialogRecorders)
            frameCreatedMethod         <- buildFrameCreatedMethod(frameEventDispatchers)
            frameDestroyedMethod       <- buildFrameDestroyedMethod(frameEventDispatchers)
            downloadWillMethod         <- buildDownloadWillMethod(downloadEventDispatchers)
            downloadProgMethod         <- buildDownloadProgressMethod(downloadEventDispatchers)
            screencastMethod           <- buildScreencastFrameMethod(screencastEventDispatchers)
            consoleApiMethod           <- buildConsoleApiCalledMethod(consoleEventDispatchers)
            exceptionMethod            <- buildExceptionThrownMethod(consoleEventDispatchers)
            config = JsonRpcHandler.Config(
                codec = JsonRpcEnvelope.lenientSchema,
                cancellation = Absent,
                progress = Absent,
                unknownMethod = JsonRpcUnknownMethodPolicy.minimal,
                gate = Absent,
                maxInFlight = Present(maxInFlight),
                requestTimeout = launchCfg.requestTimeout,
                idStrategy = JsonRpcIdStrategy.SequentialInt,
                progressResetsTimeout = false
            )
            endpoint <- JsonRpcHandler.init(
                transport,
                Seq(
                    dialogMethod,
                    frameCreatedMethod,
                    frameDestroyedMethod,
                    downloadWillMethod,
                    downloadProgMethod,
                    screencastMethod,
                    consoleApiMethod,
                    exceptionMethod
                ),
                config
            )
            dialogDrainer <- buildDialogDrainer(endpoint, dialogQueue, dialogIdCounter)
            backend = new CdpBackend(
                endpoint,
                dialogHandlers,
                dialogDrainer,
                dialogQueue,
                frameEventDispatchers,
                downloadEventDispatchers,
                screencastEventDispatchers,
                consoleEventDispatchers,
                dialogRecorders,
                lastEvaluateParams,
                sessionId = Absent
            )
            _ <- Abort.recover[BrowserReadException] {
                case ex: BrowserConnectionLostException =>
                    Abort.fail(BrowserSetupFailedException(
                        s"WS handshake failed: probe call returned Closed. Cause: ${ex.getMessage}",
                        ex.cause
                    ))
                case other => Abort.fail(other)
            } {
                backend.send[BrowserGetVersionParams, BrowserVersionResult](
                    "Browser.getVersion",
                    BrowserGetVersionParams()
                )
            }
        yield backend

    /** Parse the WS url, recovering parse failure to BrowserConnectionLostException. */
    private def parseWsUrl(wsUrl: String)(using Frame): HttpUrl < Abort[BrowserReadException] =
        HttpUrl.parse(wsUrl) match
            case Result.Success(u) => u
            case Result.Failure(e) => Abort.fail(BrowserConnectionLostException(s"WS url parse failed: ${e.getMessage}", Absent))
            case Result.Panic(t)   => Abort.fail(BrowserConnectionLostException(s"WS url parse panicked: ${t.getMessage}", Absent))

    /** Page.javascriptDialogOpening: intercept, lookup handler, enqueue
      * Page.handleJavaScriptDialog response, append to recorder if registered.
      */
    private def buildDialogMethod(
        dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]],
        dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
        dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]]
    )(using Frame): JsonRpcRoute[?, ?, JsonRpcError] < Sync =
        Sync.defer(JsonRpcRoute.notification[JavascriptDialogOpeningParams](
            "Page.javascriptDialogOpening"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            handleDialogOpening(dialogHandlers, dialogQueue, dialogRecorders, params, sid)
        })

    /** Runtime.executionContextCreated: typed params decoded once, then dispatched as the typed Wire (no JSON re-encode). */
    private def buildFrameCreatedMethod(
        frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcRoute[?, ?, JsonRpcError] < Sync =
        Sync.defer(JsonRpcRoute.notification[ExecutionContextCreatedParams](
            "Runtime.executionContextCreated"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchEvent(frameEventDispatchers, "Runtime.executionContextCreated", params, sid)
        })

    /** Runtime.executionContextDestroyed: typed params decoded once, then dispatched as the typed Wire (no JSON re-encode). */
    private def buildFrameDestroyedMethod(
        frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcRoute[?, ?, JsonRpcError] < Sync =
        Sync.defer(JsonRpcRoute.notification[ExecutionContextDestroyedParams](
            "Runtime.executionContextDestroyed"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchEvent(frameEventDispatchers, "Runtime.executionContextDestroyed", params, sid)
        })

    /** Page.downloadWillBegin notification. */
    private def buildDownloadWillMethod(
        downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcRoute[?, ?, JsonRpcError] < Sync =
        Sync.defer(JsonRpcRoute.notification[PageDownload.DownloadWillBeginWire](
            "Page.downloadWillBegin"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchDownloadEvent(downloadEventDispatchers, "Page.downloadWillBegin", params, sid)
        })

    /** Page.downloadProgress notification. */
    private def buildDownloadProgressMethod(
        downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcRoute[?, ?, JsonRpcError] < Sync =
        Sync.defer(JsonRpcRoute.notification[PageDownload.DownloadProgressWire](
            "Page.downloadProgress"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchDownloadEvent(downloadEventDispatchers, "Page.downloadProgress", params, sid)
        })

    /** Page.screencastFrame notification. Dispatches through dispatchEvent, which routes to the
      * per-session handler and drops when none is registered; screencast handlers are always pre-registered
      * by screenshotFrames before the cast starts.
      */
    private def buildScreencastFrameMethod(
        dispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcRoute[?, ?, JsonRpcError] < Sync =
        Sync.defer(JsonRpcRoute.notification[ScreencastFrameWire]("Page.screencastFrame") { (params, ctx) =>
            dispatchEvent(dispatchers, "Page.screencastFrame", params, readSessionIdFromExtras(ctx.extras))
        })

    /** Runtime.consoleAPICalled notification. Dispatches through dispatchEvent, which routes to the
      * per-session handler and drops when none is registered; console handlers are always pre-registered by
      * onConsole before the body runs.
      */
    private def buildConsoleApiCalledMethod(
        dispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcRoute[?, ?, JsonRpcError] < Sync =
        Sync.defer(JsonRpcRoute.notification[ConsoleApiCalledWire]("Runtime.consoleAPICalled") { (params, ctx) =>
            dispatchEvent(dispatchers, "Runtime.consoleAPICalled", params, readSessionIdFromExtras(ctx.extras))
        })

    /** Runtime.exceptionThrown notification. Dispatches through dispatchEvent, which routes to the
      * per-session handler and drops when none is registered; the same per-session console handler registered
      * by onConsole consumes both Runtime.consoleAPICalled and Runtime.exceptionThrown.
      */
    private def buildExceptionThrownMethod(
        dispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcRoute[?, ?, JsonRpcError] < Sync =
        Sync.defer(JsonRpcRoute.notification[ExceptionThrownWire]("Runtime.exceptionThrown") { (params, ctx) =>
            dispatchEvent(dispatchers, "Runtime.exceptionThrown", params, readSessionIdFromExtras(ctx.extras))
        })

    /** Dialog drainer: consumes dialogQueue, allocates negative ids from
      * dialogIdCounter (disjoint from JsonRpcIdStrategy.SequentialInt's positive
      * allocator, per INV-018), and writes Page.handleJavaScriptDialog
      * fire-and-forget via endpoint.sendUnmatched.
      */
    private def buildDialogDrainer(
        endpoint: JsonRpcHandler,
        dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
        dialogIdCounter: AtomicInt
    )(using Frame): Fiber[Unit, Any] < Sync =
        Fiber.initUnscoped {
            Abort.run[Closed] {
                dialogQueue.stream().foreach { case (accept, promptText, sessionId) =>
                    dialogIdCounter.getAndIncrement.map { id =>
                        val extras = sessionId match
                            case Present(sid) => JsonRpcExtrasEncoder.const(
                                    Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value)))
                                )
                            case Absent => JsonRpcExtrasEncoder.empty
                        Abort.run[Closed](
                            endpoint.sendUnmatched(
                                "Page.handleJavaScriptDialog",
                                HandleJavaScriptDialogParams(accept, promptText),
                                JsonRpcId(id.toLong),
                                extras
                            )
                        ).unit
                    }
                }
            }.unit
        }

    /** Reads sessionId from JsonRpcRoute.Context.extras (RI-001 path:
      * JsonRpcEndpointImpl.scala:911-916 constructs JsonRpcRoute.Context with env.extras).
      */
    private def readSessionIdFromExtras(extras: Maybe[Structure.Value]): Maybe[SessionId] =
        extras match
            case Present(Structure.Value.Record(fields)) =>
                Maybe.fromOption(fields.iterator.collectFirst {
                    case ("sessionId", Structure.Value.Str(s)) => SessionId(s)
                })
            case _ => Absent

    /** Handles a dialog-opening event: lookup handler, default to auto-dismiss
      * (accept=false, prompt=""), enqueue the response, and append to the
      * recorder (if any) for this session.
      *
      * Auto-dismiss is the test-stability-critical edge case: every test that
      * triggers an alert without explicit dialog handling relies on it to
      * avoid hang. The behavior here is byte-equivalent to
      * CdpClient.decodeCdpMessage's dialog-opening branch.
      */
    private def handleDialogOpening(
        dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]],
        dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
        dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]],
        params: JavascriptDialogOpeningParams,
        sid: Maybe[SessionId]
    )(using Frame): Unit < (Async & Abort[JsonRpcError]) =
        val key = sid.map(_.value).getOrElse("")
        for
            handlers <- dialogHandlers.get
            decision             = handlers.get(key).getOrElse((false, ""))
            (accept, promptText) = decision
            _         <- Abort.run[Closed](dialogQueue.put((accept, promptText, sid))).unit
            recorders <- dialogRecorders.get
            _ <- recorders.get(key) match
                case Present(ref) =>
                    val kind: Browser.DialogType = params.`type` match
                        case "alert"        => Browser.DialogType.Alert
                        case "confirm"      => Browser.DialogType.Confirm
                        case "prompt"       => Browser.DialogType.Prompt
                        case "beforeunload" => Browser.DialogType.BeforeUnload
                        case _              => Browser.DialogType.Alert
                    val response: Maybe[String] =
                        if accept && params.`type` == "prompt" then Present(promptText)
                        else Absent
                    ref.updateAndGet(_ :+ Browser.DialogEvent(kind, params.message, response)).unit
                case Absent => Kyo.unit
        yield ()
        end for
    end handleDialogOpening

    /** Dispatch a typed event to the per-session handler.
      *
      * The dispatcher table stays keyed by sessionId.value and holds `CdpEvent.Generic => Unit < Sync` handlers. The decoded typed Wire
      * (`params`) is carried straight into `CdpEvent.Generic(method, params, sid)` with no JSON round-trip; the consumer pattern-matches it
      * on the Wire type.
      */
    private def dispatchEvent(
        frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
        method: String,
        params: Matchable,
        sid: Maybe[SessionId]
    )(using Frame): Unit < (Async & Abort[JsonRpcError]) =
        val key = sid.map(_.value).getOrElse("")
        for
            table <- frameEventDispatchers.get
            _ <- table.get(key) match
                case Present(h) => h(CdpEvent.Generic(method, params, sid))
                case Absent     => Kyo.unit
        yield ()
        end for
    end dispatchEvent

    /** Same dispatch shape as dispatchEvent but for download events; carries the decoded typed Wire as
      * `CdpEvent.Generic.params` (no JSON round-trip).
      *
      * Behavior-preservation note: the pre-port CdpClient routed download events with no session or
      * no matching session handler into the shared exchange.events stream, where any registered
      * session consumer (via Browser.onDownload) could observe them. The new code preserves this by
      * broadcasting to all registered handlers when no session-specific handler is found. In practice
      * Chrome sends Page.downloadWillBegin events without a sessionId when using the browser-level
      * download path, so the broadcast path is the common case.
      */
    private def dispatchDownloadEvent(
        downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
        method: String,
        params: Matchable,
        sid: Maybe[SessionId]
    )(using Frame): Unit < (Async & Abort[JsonRpcError]) =
        val key = sid.map(_.value).getOrElse("")
        val ev  = CdpEvent.Generic(method, params, sid)
        for
            table <- downloadEventDispatchers.get
            _ <- table.get(key) match
                case Present(h) => h(ev)
                case Absent     =>
                    // No session-specific handler found. Broadcast to all registered handlers to
                    // preserve pre-port behavior where unmatched events reached exchange.events
                    // consumers. This handles Chrome emitting events without a sessionId or with a
                    // browser-level sessionId that differs from the tab's CDP session.
                    val allHandlers = table.foldLeft(Chunk.empty[CdpEvent.Generic => Unit < Sync]) { (acc, _, h) => acc.append(h) }
                    Kyo.foreachDiscard(allHandlers)(h => h(ev))
        yield ()
        end for
    end dispatchDownloadEvent

end CdpBackend
