package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.internal.cdp.PageDownload

/** Runtime CDP backend built atop a [[JsonRpcEndpoint]]. Owns the per-connection
  * dispatcher tables (frame-event / download-event / dialog handlers / dialog
  * recorders), the dialog drainer fiber, the lastEvaluateParams diagnostic, the
  * per-session ExtrasEncoder, and the 5 CDP notification handlers. Wire framing,
  * codec dispatch, request correlation, per-call timeout, in-flight metering,
  * drain signal, graceful close, and malformed-envelope routing are owned by the
  * embedded [[JsonRpcEndpoint]].
  *
  * Replaces the deprecated `CdpClient` (Phase 02 deletes `CdpClient.scala`).
  */
final private[kyo] class CdpBackend private[kyo] (
    private[kyo] val endpoint: JsonRpcEndpoint,
    private[kyo] val dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]],
    private[kyo] val dialogDrainer: Fiber[Unit, Any],
    private[kyo] val dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
    private[kyo] val frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]],
    private[kyo] val lastEvaluateParams: AtomicRef[Maybe[String]],
    private[kyo] val sessionId: Maybe[SessionId] = Absent
):

    /** Typed CDP call. Records lastEvaluateParams on Runtime.evaluate, stamps
      * sessionId via ExtrasEncoder, recovers engine errors to kyo-browser's
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
            case Present(sid) => ExtrasEncoder.const(
                    Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value)))
                )
            case Absent => ExtrasEncoder.empty
        record.andThen {
            Abort.recover[Closed] { closed =>
                Abort.fail(BrowserConnectionLostException(
                    s"Connection lost: ${closed.getMessage}",
                    Present(closed)
                ))
            } {
                Abort.recover[JsonRpcError] { err =>
                    // JsonRpcError.RequestCancelled (code -32800) is how the engine surfaces
                    // a requestTimeout expiry (internally Abort.run[Timeout] -> JsonRpcError.cancelled).
                    // Map it to BrowserConnectionLostException to preserve legacy CdpClient.submit semantics.
                    if err.code == JsonRpcError.RequestCancelled.code then
                        Abort.fail(BrowserConnectionLostException(
                            s"Request timeout: $method",
                            Absent
                        ))
                    else
                        Abort.fail(BrowserProtocolErrorException(method, err.message))
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
      * with the parent; only the sessionId field differs. ExtrasEncoder is
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
            dialogRecorders,
            lastEvaluateParams,
            sessionId = Present(sid)
        )

    /** Graceful close: delegates to endpoint.close(gracePeriod), then closes the
      * dialog queue, then interrupts the drainer fiber.
      */
    private[kyo] def close(gracePeriod: Duration)(using Frame): Unit < Async =
        endpoint.close(gracePeriod)
            .andThen(Abort.run[Closed](dialogQueue.close).unit)
            .andThen(dialogDrainer.interrupt.unit)

    /** Immediate close: same ordering, no grace period. */
    private[kyo] def closeNow(using Frame): Unit < Async =
        endpoint.closeNow
            .andThen(Abort.run[Closed](dialogQueue.close).unit)
            .andThen(dialogDrainer.interrupt.unit)

    /** Drain barrier: returns when all outstanding inflight calls have settled. */
    private[kyo] def awaitDrain(using Frame): Unit < Async =
        endpoint.awaitDrain

end CdpBackend

/** Static helpers + named constants + `init` / `initUnscoped` for [[CdpBackend]].
  *
  * Keeps the `CdpBackendOld` object (the 228-LoC legacy method-namespace +
  * decodeOrFail helper + 28 typed wrappers + CdpSender trait) alive in the
  * SAME file for Phase 01 byte-equivalent coexistence; Phase 02 deletes
  * `CdpBackendOld` and inlines the typed wrappers as two-line bodies on the
  * runtime `CdpBackend`.
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
            dialogHandlers           <- AtomicRef.init[Dict[String, (Boolean, String)]](Dict.empty)
            dialogQueue              <- Channel.initUnscoped[(Boolean, String, Maybe[SessionId])](16)
            frameEventDispatchers    <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            downloadEventDispatchers <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            dialogRecorders          <- AtomicRef.init[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]](Dict.empty)
            lastEvaluateParams       <- AtomicRef.init[Maybe[String]](Absent)
            dialogIdCounter          <- AtomicInt.init(Int.MinValue)
            url                      <- parseWsUrl(wsUrl)
            transport <- (Abort.recover[HttpException] { e =>
                Abort.fail(BrowserConnectionLostException(s"WS transport setup: ${e.getMessage}", Absent))
            } {
                JsonRpcHttpTransport.webSocket(url, HttpHeaders.empty, JsonRpcCodec.Cdp)
            }: JsonRpcTransport < (Async & Scope & Abort[BrowserReadException]))
            dialogMethod         <- buildDialogMethod(dialogHandlers, dialogQueue, dialogRecorders)
            frameCreatedMethod   <- buildFrameCreatedMethod(frameEventDispatchers)
            frameDestroyedMethod <- buildFrameDestroyedMethod(frameEventDispatchers)
            downloadWillMethod   <- buildDownloadWillMethod(downloadEventDispatchers)
            downloadProgMethod   <- buildDownloadProgressMethod(downloadEventDispatchers)
            config = JsonRpcEndpoint.Config(
                codec = JsonRpcCodec.Cdp,
                cancellation = Absent,
                progress = Absent,
                unknownMethod = UnknownMethodPolicy.minimal,
                gate = Absent,
                maxInFlight = Present(maxInFlight),
                requestTimeout = launchCfg.requestTimeout,
                idStrategy = IdStrategy.SequentialInt,
                progressResetsTimeout = false
            )
            endpoint <- JsonRpcEndpoint.init(
                transport,
                Seq(
                    dialogMethod,
                    frameCreatedMethod,
                    frameDestroyedMethod,
                    downloadWillMethod,
                    downloadProgMethod
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

    // --- Forwarding methods for Phase 01 byte-equivalent coexistence ---
    // These delegate to CdpBackendOld so that all existing call sites in Browser.scala,
    // Resolver.scala, etc. continue to compile unchanged in Phase 01.
    // Phase 02 deletes CdpBackendOld and rewrites each call site inline.
    // flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02

    private[kyo] def decodeOrFail[A: Schema](wire: String, method: String)(using Frame): A < Abort[BrowserReadException] =
        CdpBackendOld.decodeOrFail[A](wire, method)

    private[kyo] def getNavigationHistory(sender: CdpSender)(using Frame): NavigationHistory < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.getNavigationHistory(sender)

    private[kyo] def navigate(sender: CdpSender, params: NavigateParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.navigate(sender, params)

    private[kyo] def navigateToHistoryEntry(sender: CdpSender, params: NavigateToEntryParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.navigateToHistoryEntry(sender, params)

    private[kyo] def reload(sender: CdpSender, params: ReloadParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.reload(sender, params)

    private[kyo] def getFrameTree(sender: CdpSender)(using Frame): GetFrameTreeResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.getFrameTree(sender)

    private[kyo] def captureScreenshot(sender: CdpSender, params: ScreenshotParams)(using
        Frame
    ): ScreenshotResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.captureScreenshot(sender, params)

    private[kyo] def printToPDF(sender: CdpSender)(using Frame): PrintToPdfResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.printToPDF(sender)

    private[kyo] def runtimeEvaluate(sender: CdpSender, params: EvalParams)(using Frame): String < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.runtimeEvaluate(sender, params)

    private[kyo] def setDeviceMetricsOverride(sender: CdpSender, params: ViewportParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.setDeviceMetricsOverride(sender, params)

    private[kyo] def clearDeviceMetricsOverride(sender: CdpSender)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.clearDeviceMetricsOverride(sender)

    private[kyo] def dispatchKeyEvent(sender: CdpSender, params: DispatchKeyEventParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.dispatchKeyEvent(sender, params)

    private[kyo] def dispatchMouseEvent(sender: CdpSender, params: MouseEventParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.dispatchMouseEvent(sender, params)

    private[kyo] def getProperties(sender: CdpSender, params: GetPropertiesParams)(using
        Frame
    ): GetPropertiesResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.getProperties(sender, params)

    private[kyo] def getDocument(sender: CdpSender, params: GetDocumentParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.getDocument(sender, params)

    private[kyo] def requestNode(sender: CdpSender, params: RequestNodeParams)(using
        Frame
    ): RequestNodeResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.requestNode(sender, params)

    private[kyo] def describeNodeByNodeId(sender: CdpSender, params: DescribeNodeParams)(using
        Frame
    ): DescribeNodeResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.describeNodeByNodeId(sender, params)

    private[kyo] def describeNodeByBackendId(sender: CdpSender, params: DescribeNodeByBackendIdParams)(using
        Frame
    ): DescribeNodeResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.describeNodeByBackendId(sender, params)

    private[kyo] def getBoxModel(sender: CdpSender, params: GetBoxModelParams)(using
        Frame
    ): BoxModel < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.getBoxModel(sender, params)

    private[kyo] def setFileInputFiles(sender: CdpSender, params: SetFileInputFilesParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.setFileInputFiles(sender, params)

    private[kyo] def getCookies(sender: CdpSender)(using Frame): NetworkGetCookiesResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.getCookies(sender)

    private[kyo] def getCookies(sender: CdpSender, params: NetworkGetCookiesParams)(using
        Frame
    ): NetworkGetCookiesResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.getCookies(sender, params)

    private[kyo] def setCookie(sender: CdpSender, params: NetworkSetCookieParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.setCookie(sender, params)

    private[kyo] def deleteCookies(sender: CdpSender, params: NetworkDeleteCookiesParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.deleteCookies(sender, params)

    private[kyo] def getTargets(sender: CdpSender)(using Frame): GetTargetsResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.getTargets(sender)

    private[kyo] def attachToTarget(sender: CdpSender, params: AttachParams)(using
        Frame
    ): AttachResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.attachToTarget(sender, params)

    private[kyo] def createTarget(sender: CdpSender, params: CreateTargetParams)(using
        Frame
    ): CreateTargetResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.createTarget(sender, params)

    private[kyo] def createBrowserContext(sender: CdpSender)(using
        Frame
    ): CreateBrowserContextResult < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.createBrowserContext(sender)

    private[kyo] def closeTarget(sender: CdpSender, params: CloseTargetParams)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.closeTarget(sender, params)

    private[kyo] def disposeBrowserContext(sender: CdpSender, params: DisposeBrowserContextParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackendOld.disposeBrowserContext(sender, params)

    // --- End of forwarding methods ---

    /** Transport-based init for testing: caller supplies a pre-wired [[JsonRpcTransport]]
      * instead of a WS URL. The [[Browser.getVersion]] probe still runs.
      * The Scope effect is preserved so the caller can control the endpoint lifecycle.
      */
    private[kyo] def initUnscoped(transport: JsonRpcTransport, launchCfg: Browser.LaunchConfig)(using
        Frame
    ): CdpBackend < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
        for
            dialogHandlers           <- AtomicRef.init[Dict[String, (Boolean, String)]](Dict.empty)
            dialogQueue              <- Channel.initUnscoped[(Boolean, String, Maybe[SessionId])](16)
            frameEventDispatchers    <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            downloadEventDispatchers <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            dialogRecorders          <- AtomicRef.init[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]](Dict.empty)
            lastEvaluateParams       <- AtomicRef.init[Maybe[String]](Absent)
            dialogIdCounter          <- AtomicInt.init(Int.MinValue)
            dialogMethod             <- buildDialogMethod(dialogHandlers, dialogQueue, dialogRecorders)
            frameCreatedMethod       <- buildFrameCreatedMethod(frameEventDispatchers)
            frameDestroyedMethod     <- buildFrameDestroyedMethod(frameEventDispatchers)
            downloadWillMethod       <- buildDownloadWillMethod(downloadEventDispatchers)
            downloadProgMethod       <- buildDownloadProgressMethod(downloadEventDispatchers)
            config = JsonRpcEndpoint.Config(
                codec = JsonRpcCodec.Cdp,
                cancellation = Absent,
                progress = Absent,
                unknownMethod = UnknownMethodPolicy.minimal,
                gate = Absent,
                maxInFlight = Present(maxInFlight),
                requestTimeout = launchCfg.requestTimeout,
                idStrategy = IdStrategy.SequentialInt,
                progressResetsTimeout = false
            )
            endpoint <- JsonRpcEndpoint.init(
                transport,
                Seq(
                    dialogMethod,
                    frameCreatedMethod,
                    frameDestroyedMethod,
                    downloadWillMethod,
                    downloadProgMethod
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
    )(using Frame): JsonRpcMethod[Async & Abort[JsonRpcError]] < Sync =
        Sync.defer(JsonRpcMethod.notification[JavascriptDialogOpeningParams, Async & Abort[JsonRpcError]](
            "Page.javascriptDialogOpening"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            handleDialogOpening(dialogHandlers, dialogQueue, dialogRecorders, params, sid)
        })

    /** Runtime.executionContextCreated: typed params decoded, then dispatched via dispatchFrameEvent.
      * The params are re-encoded to JSON for CdpEvent.Generic compatibility with existing BrowserTab consumers.
      */
    private def buildFrameCreatedMethod(
        frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcMethod[Async & Abort[JsonRpcError]] < Sync =
        Sync.defer(JsonRpcMethod.notification[ExecutionContextCreatedParams, Async & Abort[JsonRpcError]](
            "Runtime.executionContextCreated"
        ) { (params, ctx) =>
            val sid        = readSessionIdFromExtras(ctx.extras)
            val paramsJson = Json.encode(params)
            dispatchFrameEventJson(frameEventDispatchers, "Runtime.executionContextCreated", paramsJson, sid)
        })

    /** Runtime.executionContextDestroyed: typed params decoded, then dispatched via dispatchFrameEvent. */
    private def buildFrameDestroyedMethod(
        frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcMethod[Async & Abort[JsonRpcError]] < Sync =
        Sync.defer(JsonRpcMethod.notification[ExecutionContextDestroyedParams, Async & Abort[JsonRpcError]](
            "Runtime.executionContextDestroyed"
        ) { (params, ctx) =>
            val sid        = readSessionIdFromExtras(ctx.extras)
            val paramsJson = Json.encode(params)
            dispatchFrameEventJson(frameEventDispatchers, "Runtime.executionContextDestroyed", paramsJson, sid)
        })

    /** Page.downloadWillBegin notification. */
    private def buildDownloadWillMethod(
        downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcMethod[Async & Abort[JsonRpcError]] < Sync =
        Sync.defer(JsonRpcMethod.notification[PageDownload.DownloadWillBeginWire, Async & Abort[JsonRpcError]](
            "Page.downloadWillBegin"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchDownloadEvent(downloadEventDispatchers, "Page.downloadWillBegin", params, sid)
        })

    /** Page.downloadProgress notification. */
    private def buildDownloadProgressMethod(
        downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcMethod[Async & Abort[JsonRpcError]] < Sync =
        Sync.defer(JsonRpcMethod.notification[PageDownload.DownloadProgressWire, Async & Abort[JsonRpcError]](
            "Page.downloadProgress"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchDownloadEvent(downloadEventDispatchers, "Page.downloadProgress", params, sid)
        })

    /** Dialog drainer: consumes dialogQueue, allocates negative ids from
      * dialogIdCounter (disjoint from IdStrategy.SequentialInt's positive
      * allocator, per INV-018), and writes Page.handleJavaScriptDialog
      * fire-and-forget via endpoint.sendUnmatched.
      */
    private def buildDialogDrainer(
        endpoint: JsonRpcEndpoint,
        dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
        dialogIdCounter: AtomicInt
    )(using Frame): Fiber[Unit, Any] < Sync =
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

    /** Reads sessionId from HandlerCtx.extras (RI-001 path:
      * JsonRpcEndpointImpl.scala:911-916 constructs HandlerCtx with env.extras).
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

    /** Dispatch a typed frame event to the per-session handler.
      *
      * The dispatcher table stays keyed by sessionId.value and holds
      * `CdpEvent.Generic => Unit < Sync` handlers. The notification handler
      * wraps the typed params back into `CdpEvent.Generic(method,
      * Json.encode(params), sid)` for byte-equivalence with the legacy
      * dispatcher map shape.
      */
    /** Dispatch a frame event to the per-session handler using a pre-encoded JSON string. */
    private def dispatchFrameEventJson(
        frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
        method: String,
        paramsJson: String,
        sid: Maybe[SessionId]
    )(using Frame): Unit < (Async & Abort[JsonRpcError]) =
        val key = sid.map(_.value).getOrElse("")
        for
            table <- frameEventDispatchers.get
            _ <- table.get(key) match
                case Present(h) => h(CdpEvent.Generic(method, paramsJson, sid))
                case Absent     => Kyo.unit
        yield ()
        end for
    end dispatchFrameEventJson

    private def dispatchFrameEvent(
        frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
        method: String,
        paramsValue: Structure.Value,
        sid: Maybe[SessionId]
    )(using Frame): Unit < (Async & Abort[JsonRpcError]) =
        dispatchFrameEventJson(frameEventDispatchers, method, Json.encode(paramsValue), sid)
    end dispatchFrameEvent

    /** Same dispatch shape as dispatchFrameEvent but for download events;
      * carries the typed schema wrapper so kyo-schema validates incoming wire.
      */
    private def dispatchDownloadEvent[P: Schema](
        downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
        method: String,
        params: P,
        sid: Maybe[SessionId]
    )(using Frame): Unit < (Async & Abort[JsonRpcError]) =
        val key  = sid.map(_.value).getOrElse("")
        val json = Json.encode(params)
        for
            table <- downloadEventDispatchers.get
            _ <- table.get(key) match
                case Present(h) => h(CdpEvent.Generic(method, json, sid))
                case Absent     => Kyo.unit
        yield ()
        end for
    end dispatchDownloadEvent

end CdpBackend

/** Phase-01 byte-equivalent coexistence: the legacy method-namespace object
  * `CdpBackendOld` carries the 228-LoC of typed wrappers + decodeOrFail +
  * CdpSender trait so the existing CdpClient call sites continue to compile
  * unchanged. Phase 02 deletes this object outright and inlines two-line
  * `backend.send[Params, Result]` calls at every call site.
  *
  * // flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02
  */
private[kyo] object CdpBackendOld:

    private[kyo] val RuntimeEvaluateMethod = CdpBackend.RuntimeEvaluateMethod

    /** Decodes a CDP response JSON to a typed result. A CDP error response (routed by the `CdpClient` relay into the result slot as the
      * `CdpError` JSON) is recognized and surfaced with its real CDP message; a genuinely malformed payload raises via `decodeFailure`.
      */
    private[kyo] def decodeOrFail[A: Schema](wire: String, method: String)(using
        Frame
    )
        : A < Abort[BrowserReadException] =
        Json.decode[CdpReply[A]](wire) match
            case Result.Success(reply) =>
                reply.result match
                    case Present(v) => v
                    case Absent =>
                        reply.error match
                            case Present(cdpErr) => Abort.fail(BrowserProtocolErrorException(method, cdpErr.message))
                            case Absent =>
                                Abort.fail(BrowserProtocolErrorException.decodeFailure(
                                    method,
                                    s"reply has neither result nor error: $wire"
                                ))
            case typedFailure =>
                Abort.fail(BrowserProtocolErrorException.decodeFailure(method, typedFailure.toString))

    /** Fetches the session's browser history, returning the typed navigation history. */
    private[kyo] def getNavigationHistory(sender: CdpSender)(using Frame): NavigationHistory < (Async & Abort[BrowserReadException]) =
        sender.send("Page.getNavigationHistory").map(decodeOrFail[NavigationHistory](_, "Page.getNavigationHistory"))

    /** Navigates the current tab to the given URL. Does not wait for the navigation to settle. */
    private[kyo] def navigate(sender: CdpSender, params: NavigateParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Page.navigate", params).unit

    /** Navigates to a specific history entry by its entry id. Does not wait for the navigation to settle. */
    private[kyo] def navigateToHistoryEntry(sender: CdpSender, params: NavigateToEntryParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Page.navigateToHistoryEntry", params).unit

    /** Reloads the current page. Does not wait for the navigation to settle. */
    private[kyo] def reload(sender: CdpSender, params: ReloadParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Page.reload", params).unit

    /** Returns the full frame tree for the current page. */
    private[kyo] def getFrameTree(sender: CdpSender)(using Frame): GetFrameTreeResult < (Async & Abort[BrowserReadException]) =
        sender.send("Page.getFrameTree").map(decodeOrFail[GetFrameTreeResult](_, "Page.getFrameTree"))

    /** Captures a screenshot and returns the typed result (base-64-encoded PNG data). */
    private[kyo] def captureScreenshot(sender: CdpSender, params: ScreenshotParams)(using
        Frame
    ): ScreenshotResult < (Async & Abort[BrowserReadException]) =
        sender.send("Page.captureScreenshot", params).map(decodeOrFail[ScreenshotResult](_, "Page.captureScreenshot"))

    /** Generates a PDF of the current page and returns the typed result (base-64-encoded PDF data). */
    private[kyo] def printToPDF(sender: CdpSender)(using Frame): PrintToPdfResult < (Async & Abort[BrowserReadException]) =
        sender.send("Page.printToPDF").map(decodeOrFail[PrintToPdfResult](_, "Page.printToPDF"))

    /** Evaluates a JavaScript expression and returns the raw CDP response JSON. */
    private[kyo] def runtimeEvaluate(sender: CdpSender, params: EvalParams)(using
        Frame
    ): String < (Async & Abort[BrowserReadException]) =
        sender.send(RuntimeEvaluateMethod, params)

    /** Overrides the tab's viewport dimensions and device scale factor. */
    private[kyo] def setDeviceMetricsOverride(sender: CdpSender, params: ViewportParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Emulation.setDeviceMetricsOverride", params).unit

    /** Clears any active viewport override, restoring the tab's natural viewport. */
    private[kyo] def clearDeviceMetricsOverride(sender: CdpSender)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Emulation.clearDeviceMetricsOverride").unit

    /** Dispatches a keyboard event (keyDown / keyUp / char) to the focused element. */
    private[kyo] def dispatchKeyEvent(sender: CdpSender, params: DispatchKeyEventParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Input.dispatchKeyEvent", params).unit

    /** Dispatches a mouse event (mouseMoved / mousePressed / mouseReleased) at the given coordinates. */
    private[kyo] def dispatchMouseEvent(sender: CdpSender, params: MouseEventParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Input.dispatchMouseEvent", params).unit

    /** Enumerates own properties of a JS object handle. */
    private[kyo] def getProperties(sender: CdpSender, params: GetPropertiesParams)(using
        Frame
    ): GetPropertiesResult < (Async & Abort[BrowserReadException]) =
        sender.send("Runtime.getProperties", params).map(decodeOrFail[GetPropertiesResult](_, "Runtime.getProperties"))

    /** Ensures the agent's document root is initialised. Result is discarded. */
    private[kyo] def getDocument(sender: CdpSender, params: GetDocumentParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("DOM.getDocument", params).unit

    /** Pushes the node path from a JS objectId to the document root, returning the agent-local nodeId. */
    private[kyo] def requestNode(sender: CdpSender, params: RequestNodeParams)(using
        Frame
    ): RequestNodeResult < (Async & Abort[BrowserReadException]) =
        sender.send("DOM.requestNode", params).map(decodeOrFail[RequestNodeResult](_, "DOM.requestNode"))

    /** Describes a DOM node by its agent-local nodeId. */
    private[kyo] def describeNodeByNodeId(sender: CdpSender, params: DescribeNodeParams)(using
        Frame
    ): DescribeNodeResult < (Async & Abort[BrowserReadException]) =
        sender.send("DOM.describeNode", params).map(decodeOrFail[DescribeNodeResult](_, "DOM.describeNode"))

    /** Describes a DOM node by its backend node id. */
    private[kyo] def describeNodeByBackendId(sender: CdpSender, params: DescribeNodeByBackendIdParams)(using
        Frame
    ): DescribeNodeResult < (Async & Abort[BrowserReadException]) =
        sender.send("DOM.describeNode", params).map(decodeOrFail[DescribeNodeResult](_, "DOM.describeNode"))

    /** Returns the box model of a DOM node identified by backendNodeId. */
    private[kyo] def getBoxModel(sender: CdpSender, params: GetBoxModelParams)(using
        Frame
    ): BoxModel < (Async & Abort[BrowserReadException]) =
        sender.send("DOM.getBoxModel", params).map(decodeOrFail[BoxModel](_, "DOM.getBoxModel"))

    /** Sets the files for a file-input element identified by its backend node id. */
    private[kyo] def setFileInputFiles(sender: CdpSender, params: SetFileInputFilesParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("DOM.setFileInputFiles", params).unit

    /** Returns all cookies visible to the current page. */
    private[kyo] def getCookies(sender: CdpSender)(using Frame): NetworkGetCookiesResult < (Async & Abort[BrowserReadException]) =
        sender.send("Network.getCookies").map(decodeOrFail[NetworkGetCookiesResult](_, "Network.getCookies"))

    /** Returns cookies filtered by Chrome's `urls` predicate. */
    private[kyo] def getCookies(sender: CdpSender, params: NetworkGetCookiesParams)(using
        Frame
    ): NetworkGetCookiesResult < (Async & Abort[BrowserReadException]) =
        sender.send("Network.getCookies", params).map(decodeOrFail[NetworkGetCookiesResult](_, "Network.getCookies"))

    /** Sets a cookie in the current page's cookie jar. */
    private[kyo] def setCookie(sender: CdpSender, params: NetworkSetCookieParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Network.setCookie", params).unit

    /** Deletes a cookie from the current page's cookie jar. */
    private[kyo] def deleteCookies(sender: CdpSender, params: NetworkDeleteCookiesParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Network.deleteCookies", params).unit

    /** Returns information about all open targets (tabs, workers, etc.). */
    private[kyo] def getTargets(sender: CdpSender)(using Frame): GetTargetsResult < (Async & Abort[BrowserReadException]) =
        sender.send("Target.getTargets").map(decodeOrFail[GetTargetsResult](_, "Target.getTargets"))

    /** Attaches to an existing target and returns the typed result containing the session ID. */
    private[kyo] def attachToTarget(sender: CdpSender, params: AttachParams)(using
        Frame
    ): AttachResult < (Async & Abort[BrowserReadException]) =
        sender.send("Target.attachToTarget", params).map(decodeOrFail[AttachResult](_, "Target.attachToTarget"))

    /** Creates a new browser target (tab) and returns the typed result containing the target ID. */
    private[kyo] def createTarget(sender: CdpSender, params: CreateTargetParams)(using
        Frame
    ): CreateTargetResult < (Async & Abort[BrowserReadException]) =
        sender.send("Target.createTarget", params).map(decodeOrFail[CreateTargetResult](_, "Target.createTarget"))

    /** Creates a new isolated browser context and returns the typed result containing the context ID. */
    private[kyo] def createBrowserContext(sender: CdpSender)(using
        Frame
    ): CreateBrowserContextResult < (Async & Abort[BrowserReadException]) =
        sender.send("Target.createBrowserContext").map(decodeOrFail[CreateBrowserContextResult](_, "Target.createBrowserContext"))

    /** Closes the target (tab or worker) with the given target id. */
    private[kyo] def closeTarget(sender: CdpSender, params: CloseTargetParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Target.closeTarget", params).unit

    /** Disposes an isolated browser context, tearing down its storage and service workers. */
    private[kyo] def disposeBrowserContext(sender: CdpSender, params: DisposeBrowserContextParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Target.disposeBrowserContext", params).unit

end CdpBackendOld
