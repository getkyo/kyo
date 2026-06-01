package kyo

/** Handler-side accessors for the per-request LSP context.
  *
  * The engine binds a per-request [[Lsp.RequestContext]] into a [[Local]] for the lifetime of a
  * single handler invocation. Handler code reaches into that local through these accessors:
  *
  *   - [[Lsp.server]]              ; the live [[LspServer]] for reverse-direction calls (server-side handlers)
  *   - [[Lsp.client]]              ; the live [[LspClient]] for reverse-direction calls (client-side handlers)
  *   - [[Lsp.documents]]           ; the read-only document registry snapshot for this session
  *   - [[Lsp.requestId]]           ; the JSON-RPC id of the inbound request
  *   - [[Lsp.cancelled]]           ; promise that completes when the peer cancels this request
  *   - [[Lsp.workDoneToken]]       ; workDoneToken from the request params, if any
  *   - [[Lsp.partialResultToken]]  ; partialResultToken from the request params, if any
  *   - [[Lsp.positionEncoding]]    ; the session-level negotiated position encoding
  *   - [[Lsp.extras]]              ; typed accessor for protocol extension fields
  *
  * Calling any of these outside an active route-handler invocation raises an
  * `IllegalStateException` (kernel panic). This is a programmer-error path; no typed `Abort` row
  * is added to the signature.
  *
  * [[DocumentRegistry]] is the public read-interface for the document registry. Only `get`,
  * `version`, `listOpen`, `listOpenUris`, and `isOpen` are exposed; subscriptions are v1 deferred.
  */
object Lsp:

    /** Public read-interface for the live document registry. */
    sealed trait DocumentRegistry:
        def get(uri: LspHandler.LspDocument.Uri)(using Frame): Maybe[LspHandler.LspDocument] < Sync
        def version(uri: LspHandler.LspDocument.Uri)(using Frame): Maybe[Int] < Sync
        def listOpen(using Frame): Chunk[LspHandler.LspDocument] < Sync
        def listOpenUris(using Frame): Chunk[LspHandler.LspDocument.Uri] < Sync
        def isOpen(uri: LspHandler.LspDocument.Uri)(using Frame): Boolean < Sync
    end DocumentRegistry

    /** Per-request context bound by the engine for the lifetime of a single dispatch. */
    final private[kyo] case class RequestContext(
        jsonRpc: JsonRpcRoute.Context,
        peer: Peer,
        workDoneToken: Maybe[LspHandler.ProgressToken],
        partialResultToken: Maybe[LspHandler.ProgressToken],
        documents: DocumentRegistry,
        positionEncoding: LspHandler.PositionEncodingKind
    )

    /** Identifies which side of the connection the handler is executing on. */
    private[kyo] enum Peer derives CanEqual:
        case Server(local: LspServer)
        case Client(local: LspClient)
    end Peer

    /** Local holding the current request context. Absent outside handler dispatch. */
    private[kyo] val local: Local[Maybe[RequestContext]] = Local.init(Absent)

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private def ctx(method: String)(using Frame): RequestContext < Sync =
        local.use {
            case Present(c) => Sync.defer(c)
            case Absent =>
                Sync.defer(throw new IllegalStateException(s"Lsp.$method called outside an LSP route handler"))
        }

    // =========================================================================
    // Public accessors
    // =========================================================================

    /** The live server handle for the current request (server-side handlers only). */
    def server(using Frame): LspServer < Sync =
        ctx("server").map(c =>
            c.peer match
                case Peer.Server(s) => s
                case Peer.Client(_) => throw new IllegalStateException("Lsp.server called from a client-side handler")
        )

    /** The live client handle for the current request (client-side handlers only). */
    def client(using Frame): LspClient < Sync =
        ctx("client").map(c =>
            c.peer match
                case Peer.Client(cl) => cl
                case Peer.Server(_)  => throw new IllegalStateException("Lsp.client called from a server-side handler")
        )

    /** The document registry snapshot for this session. */
    def documents(using Frame): DocumentRegistry < Sync =
        ctx("documents").map(_.documents)

    /** The JSON-RPC request id for the current request, if any. */
    def requestId(using Frame): Maybe[JsonRpcId] < Sync =
        ctx("requestId").map(_.jsonRpc.requestId)

    /** A promise that resolves when the peer cancels this request. */
    def cancelled(using Frame): Fiber.Promise[Unit, Sync] < Sync =
        ctx("cancelled").map(_.jsonRpc.cancelled)

    /** The work-done progress token for the current request, if any. */
    def workDoneToken(using Frame): Maybe[LspHandler.ProgressToken] < Sync =
        ctx("workDoneToken").map(_.workDoneToken)

    /** The partial-result token for the current request, if any. */
    def partialResultToken(using Frame): Maybe[LspHandler.ProgressToken] < Sync =
        ctx("partialResultToken").map(_.partialResultToken)

    /** The session-level negotiated position encoding. */
    def positionEncoding(using Frame): LspHandler.PositionEncodingKind < Sync =
        ctx("positionEncoding").map(_.positionEncoding)

    /** Typed accessor for protocol extension fields from the current request extras. */
    def extras[T](using Frame, Schema[T]): Maybe[T] < (Sync & Abort[LspException.Dispatch.InvalidParams]) = ???

    /** The current trace level for the session. */
    def trace(using Frame): LspHandler.TraceValue < Sync = ???

    // =========================================================================
    // Progress and trace operations
    // =========================================================================

    /** Begins a work-done progress notification on the given token. */
    def workDoneBegin(
        token: LspHandler.ProgressToken,
        title: String,
        message: Maybe[String] = Absent,
        percentage: Maybe[Int] = Absent,
        cancellable: Boolean = false
    )(using Frame): Unit < (Async & Abort[Closed]) = ???

    /** Reports progress on a work-done token. */
    def workDoneReport(
        token: LspHandler.ProgressToken,
        message: Maybe[String] = Absent,
        percentage: Maybe[Int] = Absent,
        cancellable: Maybe[Boolean] = Absent
    )(using Frame): Unit < (Async & Abort[Closed]) = ???

    /** Ends a work-done progress notification on the given token. */
    def workDoneEnd(
        token: LspHandler.ProgressToken,
        message: Maybe[String] = Absent
    )(using Frame): Unit < (Async & Abort[Closed]) = ???

    /** Emits a partial result on the given token. */
    def emitPartialResult[T](
        token: LspHandler.ProgressToken,
        value: T
    )(using Frame, Schema[T]): Unit < (Async & Abort[Closed]) = ???

    /** Logs a trace message to the client. */
    def logTrace(
        message: String,
        verbose: Maybe[String] = Absent
    )(using Frame): Unit < (Async & Abort[Closed]) = ???

    // =========================================================================
    // Typed raw-payload accessors
    // =========================================================================

    /** Decodes the `initializationOptions` field from the `initialize` request. */
    def initializationOptions[X](using Frame, Schema[X]): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) = ???

    /** Decodes the client's experimental capabilities. */
    def clientExperimentalCapabilities[X](using Frame, Schema[X]): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) = ???

    /** Decodes the server's experimental capabilities. */
    def serverExperimentalCapabilities[X](using Frame, Schema[X]): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) = ???

    /** Decodes the metadata of a notebook document. */
    def notebookMetadataAs[X](nb: LspHandler.NotebookDocument)(using
        Frame,
        Schema[X]
    ): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) = ???

    /** Decodes the metadata of a notebook cell. */
    def notebookCellMetadataAs[X](cell: LspHandler.NotebookCell)(using
        Frame,
        Schema[X]
    ): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) = ???

    /** Decodes the `registerOptions` field from a `Registration`. */
    def registerOptionsAs[X](reg: LspHandler.Registration)(using
        Frame,
        Schema[X]
    ): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) = ???

end Lsp
