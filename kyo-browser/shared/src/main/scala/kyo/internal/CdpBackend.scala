package kyo.internal

import CdpTypes.*
import kyo.*

/** Typed interface for all CDP endpoints used by [[kyo.Browser]].
  *
  * Each method encapsulates one CDP endpoint: the raw `CdpSender.send(...)` call and (where applicable) the subsequent JSON decoding into a
  * typed result. Call sites in [[kyo.Browser]] use these methods instead of raw string-literal CDP calls.
  *
  * All methods are `private[kyo]`: accessible to [[kyo.Browser]] and internal helpers, but not to external callers.
  */
private[kyo] object CdpBackend:

    /** CDP method names referenced both by this object's wrappers and by downstream call sites (e.g. error messages from `Resolver`,
      * `MutationSettlement`, `CdpClient`, `CdpEvalTypes`) that need to identify the method that originated a wire failure.
      */
    private[kyo] val RuntimeEvaluateMethod = "Runtime.evaluate"

    // --- Page domain ---

    /** Decodes a CDP response JSON to a typed result. A CDP error response (routed by the `CdpClient` relay into the result slot as the
      * `CdpError` JSON) is recognized and surfaced with its real CDP message; a genuinely malformed payload raises via `decodeFailure`.
      */
    private[kyo] def decodeOrFail[A: Schema](wire: String, method: String)(using
        Frame
    )
        : A < Abort[BrowserReadException] =
        // The dispatcher passes the whole wire frame; decode it into a [[CdpReply]] envelope that captures
        // either a typed `result: A` or an `error: CdpError`. Both shapes flow through a single Json.decode call.
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

    // --- Runtime domain ---

    /** Evaluates a JavaScript expression and returns the raw CDP response JSON.
      *
      * Callers are responsible for extracting the value from the raw JSON (e.g. via `CdpEvalDecoder.parseAndExtractEvalValue` or custom
      * parsing).
      */
    private[kyo] def runtimeEvaluate(sender: CdpSender, params: EvalParams)(using
        Frame
    ): String < (Async & Abort[BrowserReadException]) =
        sender.send(RuntimeEvaluateMethod, params)

    // --- Emulation domain ---

    /** Overrides the tab's viewport dimensions and device scale factor. */
    private[kyo] def setDeviceMetricsOverride(sender: CdpSender, params: ViewportParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Emulation.setDeviceMetricsOverride", params).unit

    /** Clears any active viewport override, restoring the tab's natural viewport. */
    private[kyo] def clearDeviceMetricsOverride(sender: CdpSender)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Emulation.clearDeviceMetricsOverride").unit

    /** Sets the emulated media type and/or media features (e.g. prefers-color-scheme). */
    private[kyo] def setEmulatedMedia(sender: CdpSender, params: SetEmulatedMediaParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Emulation.setEmulatedMedia", params).unit

    /** Overrides the default background color (used for transparent-background captures). */
    private[kyo] def setDefaultBackgroundColorOverride(sender: CdpSender, params: SetDefaultBackgroundColorOverrideParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Emulation.setDefaultBackgroundColorOverride", params).unit

    // --- Page domain (screencast) ---

    /** Starts a screencast session, delivering frames via `Page.screencastFrame` events. */
    private[kyo] def startScreencast(sender: CdpSender, params: StartScreencastParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Page.startScreencast", params).unit

    /** Stops the current screencast session. */
    private[kyo] def stopScreencast(sender: CdpSender)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Page.stopScreencast").unit

    /** Acknowledges a screencast frame, allowing Chrome to deliver the next one. */
    private[kyo] def screencastFrameAck(sender: CdpSender, params: ScreencastFrameAckParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("Page.screencastFrameAck", params).unit

    // --- Input domain ---

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

    // --- Runtime domain (object handles) ---

    /** Enumerates own properties of a JS object handle. Used by the Resolver pipeline to extract per-element handles from an array. */
    private[kyo] def getProperties(sender: CdpSender, params: GetPropertiesParams)(using
        Frame
    ): GetPropertiesResult < (Async & Abort[BrowserReadException]) =
        sender.send("Runtime.getProperties", params).map(decodeOrFail[GetPropertiesResult](_, "Runtime.getProperties"))

    // --- DOM domain ---

    /** Ensures the agent's document root is initialised (sets `m_document` in the CDP agent). Result is discarded. */
    private[kyo] def getDocument(sender: CdpSender, params: GetDocumentParams)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        sender.send("DOM.getDocument", params).unit

    /** Pushes the node path from a JS objectId to the document root into the agent's tracked tree, returning the agent-local nodeId. */
    private[kyo] def requestNode(sender: CdpSender, params: RequestNodeParams)(using
        Frame
    ): RequestNodeResult < (Async & Abort[BrowserReadException]) =
        sender.send("DOM.requestNode", params).map(decodeOrFail[RequestNodeResult](_, "DOM.requestNode"))

    /** Describes a DOM node by its agent-local nodeId, returning its metadata (backendNodeId, frameId, …). */
    private[kyo] def describeNodeByNodeId(sender: CdpSender, params: DescribeNodeParams)(using
        Frame
    ): DescribeNodeResult < (Async & Abort[BrowserReadException]) =
        sender.send("DOM.describeNode", params).map(decodeOrFail[DescribeNodeResult](_, "DOM.describeNode"))

    /** Describes a DOM node by its backend node id, returning its metadata (frameId, nodeName, …). */
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

    // --- Network domain ---

    /** Returns all cookies visible to the current page. */
    private[kyo] def getCookies(sender: CdpSender)(using Frame): NetworkGetCookiesResult < (Async & Abort[BrowserReadException]) =
        sender.send("Network.getCookies").map(decodeOrFail[NetworkGetCookiesResult](_, "Network.getCookies"))

    /** Returns cookies filtered by Chrome's `urls` predicate: cookies whose Domain/Path would cause them to be sent on a request to one of
      * the supplied URLs.
      */
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

    // --- Target domain ---

    /** Returns information about all open targets (tabs, workers, etc.). */
    private[kyo] def getTargets(sender: CdpSender)(using Frame): GetTargetsResult < (Async & Abort[BrowserReadException]) =
        sender.send("Target.getTargets").map(decodeOrFail[GetTargetsResult](_, "Target.getTargets"))

    /** Attaches to an existing target (tab or worker) and returns the typed result containing the session ID. */
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

end CdpBackend
