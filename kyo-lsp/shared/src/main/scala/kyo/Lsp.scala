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
        positionEncoding: LspHandler.PositionEncodingKind,
        trace: LspHandler.TraceValue = LspHandler.TraceValue.Off,
        _rawInitializationOptions: Maybe[String] = Absent,
        _rawClientExperimental: Maybe[String] = Absent,
        _rawServerExperimental: Maybe[String] = Absent
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

    private def decodeRaw[X](raw: Maybe[String], target: String)(using
        Frame,
        Schema[X]
    ): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) =
        raw match
            case Absent => Absent
            case Present(s) =>
                Json.decode[X](s) match
                    case Result.Success(v) => Present(v)
                    case Result.Failure(e) => Abort.fail(LspException.Execution.Decode(target, e.toString, e))
                    case Result.Panic(e)   => Abort.fail(LspException.Execution.Decode(target, e.getMessage, e))

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
    def extras[T](using Frame, Schema[T]): Maybe[T] < (Sync & Abort[LspException.Dispatch.InvalidParams]) =
        ctx("extras").map { c =>
            c.jsonRpc.extras match
                case Absent => Absent
                case Present(sv) =>
                    Structure.decode[T](sv) match
                        case Result.Success(v) => Present(v)
                        case Result.Failure(e) =>
                            Abort.fail(LspException.Dispatch.InvalidParams("extras", e.getMessage, e))
                        case Result.Panic(e) =>
                            Abort.fail(LspException.Dispatch.InvalidParams("extras", e.getMessage, e))
        }

    /** The current trace level for the session. */
    def trace(using Frame): LspHandler.TraceValue < Sync =
        ctx("trace").map(_.trace)

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
    )(using Frame): Unit < (Async & Abort[Closed]) =
        server.flatMap(_.workDoneProgress(
            token,
            LspHandler.WorkDoneProgressValue.Begin(
                title = title,
                cancellable = Present(cancellable),
                message = message,
                percentage = percentage
            )
        ))

    /** Reports progress on a work-done token. */
    def workDoneReport(
        token: LspHandler.ProgressToken,
        message: Maybe[String] = Absent,
        percentage: Maybe[Int] = Absent,
        cancellable: Maybe[Boolean] = Absent
    )(using Frame): Unit < (Async & Abort[Closed]) =
        server.flatMap(_.workDoneProgress(
            token,
            LspHandler.WorkDoneProgressValue.Report(
                cancellable = cancellable,
                message = message,
                percentage = percentage
            )
        ))

    /** Ends a work-done progress notification on the given token. */
    def workDoneEnd(
        token: LspHandler.ProgressToken,
        message: Maybe[String] = Absent
    )(using Frame): Unit < (Async & Abort[Closed]) =
        server.flatMap(_.workDoneProgress(token, LspHandler.WorkDoneProgressValue.End(message)))

    /** Emits a partial result on the given token. */
    def emitPartialResult[T](
        token: LspHandler.ProgressToken,
        value: T
    )(using Frame, Schema[T]): Unit < (Async & Abort[Closed]) =
        given Schema[LspHandler.ProgressParams[T]] = Schema.derived
        server.flatMap(_.underlying.notify[LspHandler.ProgressParams[T]]("$/progress", LspHandler.ProgressParams(token, value)))
    end emitPartialResult

    /** Logs a trace message to the client. */
    def logTrace(
        message: String,
        verbose: Maybe[String] = Absent
    )(using Frame): Unit < (Async & Abort[Closed]) =
        server.flatMap(_.logTrace(message, verbose))

    // =========================================================================
    // Typed raw-payload accessors
    // =========================================================================

    /** Decodes the `initializationOptions` field from the `initialize` request. */
    def initializationOptions[X](using Frame, Schema[X]): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) =
        ctx("initializationOptions").flatMap(c => decodeRaw[X](c._rawInitializationOptions, "initializationOptions"))

    /** Decodes the client's experimental capabilities. */
    def clientExperimentalCapabilities[X](using Frame, Schema[X]): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) =
        ctx("clientExperimentalCapabilities").flatMap(c => decodeRaw[X](c._rawClientExperimental, "clientExperimental"))

    /** Decodes the server's experimental capabilities. */
    def serverExperimentalCapabilities[X](using Frame, Schema[X]): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) =
        ctx("serverExperimentalCapabilities").flatMap(c => decodeRaw[X](c._rawServerExperimental, "serverExperimental"))

    /** Decodes the metadata of a notebook document. */
    def notebookMetadataAs[X](nb: LspHandler.Notebook)(using
        Frame,
        Schema[X]
    ): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) =
        decodeRaw[X](nb._rawMetadata, "notebookDocument.metadata")

    /** Decodes the metadata of a notebook cell. */
    def notebookCellMetadataAs[X](cell: LspHandler.NotebookCell)(using
        Frame,
        Schema[X]
    ): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) =
        decodeRaw[X](cell._rawMetadata, "notebookCell.metadata")

    /** Decodes the `registerOptions` field from a `Registration`. */
    def registerOptionsAs[X](reg: LspHandler.Registration)(using
        Frame,
        Schema[X]
    ): Maybe[X] < (Sync & Abort[LspException.Execution.Decode]) =
        reg.registerOptionsAs[X]

end Lsp

/** `AtomicRef`-backed implementation of `Lsp.DocumentRegistry`.
  *
  * Must live in the same source file as `Lsp` because `Lsp.DocumentRegistry` is a sealed trait.
  * Used exclusively by the internal engine; `private[kyo]` visibility prevents external construction.
  *
  * Edge-case policies (INV-033 / RI-012): unknown-URI mutator calls are no-ops (log-and-skip);
  * duplicate didOpen is an overwrite. The encoding ref is read on every insert so the registry
  * always stamps the post-handshake negotiated encoding.
  *
  * Per INV-048 all mutators are `private[kyo]`.
  */
final private[kyo] class LspDocumentRegistryImpl(
    encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
) extends Lsp.DocumentRegistry:

    private val mapRef: AtomicRef[Map[LspHandler.LspDocument.Uri, LspHandler.LspDocument]] =
        AtomicRef.Unsafe.init(Map.empty)(using AllowUnsafe.embrace.danger).safe

    private def currentEncoding: LspHandler.PositionEncodingKind =
        encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)

    // =========================================================================
    // Public read interface
    // =========================================================================

    def get(uri: LspHandler.LspDocument.Uri)(using Frame): Maybe[LspHandler.LspDocument] < Sync =
        mapRef.get.map(m => Maybe.fromOption(m.get(uri)))

    def version(uri: LspHandler.LspDocument.Uri)(using Frame): Maybe[Int] < Sync =
        mapRef.get.map(m => Maybe.fromOption(m.get(uri).map(_.version)))

    def listOpen(using Frame): Chunk[LspHandler.LspDocument] < Sync =
        mapRef.get.map(m => Chunk.from(m.values))

    def listOpenUris(using Frame): Chunk[LspHandler.LspDocument.Uri] < Sync =
        mapRef.get.map(m => Chunk.from(m.keys))

    def isOpen(uri: LspHandler.LspDocument.Uri)(using Frame): Boolean < Sync =
        mapRef.get.map(m => m.contains(uri))

    // =========================================================================
    // Private[kyo] mutators (INV-048)
    // =========================================================================

    /** Inserts a text document, stamping the current session encoding (INV-010 / INV-035).
      * Duplicate didOpen (same URI) is treated as implicit re-open per RI-012 case d.
      */
    private[kyo] def insert(item: LspHandler.TextDocumentItem)(using Frame): Unit < Sync =
        val enc = currentEncoding
        val doc = LspHandler.LspDocument(
            uri = item.uri,
            languageId = item.languageId,
            version = item.version,
            text = item.text,
            encoding = enc
        )
        mapRef.updateAndGet(m => m.updated(doc.uri, doc)).andThen(())
    end insert

    /** Applies incremental or full changes; silently skips if URI is unknown (RI-012 a/b/c). */
    private[kyo] def applyChanges(
        uri: LspHandler.LspDocument.Uri,
        version: Int,
        changes: Chunk[LspHandler.TextDocumentContentChangeEvent]
    )(using Frame): Unit < Sync =
        mapRef.updateAndGet { m =>
            m.get(uri) match
                case None => m
                case Some(doc) =>
                    m.updated(uri, LspHandler.LspDocument.applyChanges(doc, changes).copy(version = version))
        }.andThen(())
    end applyChanges

    /** Marks as saved; no-op for unknown URI (RI-012 case e). */
    private[kyo] def setSaved(uri: LspHandler.LspDocument.Uri)(using Frame): Unit < Sync =
        Sync.defer(())

    /** Removes the document; no-op for unknown URI (RI-012 case e). */
    private[kyo] def remove(uri: LspHandler.LspDocument.Uri)(using Frame): Unit < Sync =
        mapRef.updateAndGet(m => m.removed(uri)).andThen(())

    /** Inserts a notebook cell document with session encoding (INV-092). */
    private[kyo] def insertNotebookCell(
        uri: LspHandler.LspDocument.Uri,
        languageId: String,
        text: String,
        version: Int
    )(using Frame): Unit < Sync =
        val enc = currentEncoding
        val doc = LspHandler.LspDocument(
            uri = uri,
            languageId = languageId,
            version = version,
            text = text,
            encoding = enc
        )
        mapRef.updateAndGet(m => m.updated(uri, doc)).andThen(())
    end insertNotebookCell

end LspDocumentRegistryImpl
