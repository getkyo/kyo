package kyo

/** Live LSP server handle managing one peer over a [[JsonRpcTransport]].
  *
  * Obtain via `LspServer.init` (Scope-managed) or `LspServer.initUnscoped` (manual close).
  * Mirrors `McpServer` at kyo-mcp. The opaque alias means `LspServer` is simply the abstract
  * class `LspServer.Unsafe` with a safety wrapper; use the extension methods in the companion
  * for all interactions.
  *
  * INV-012: `LspServer = LspServer.Unsafe` (opaque identity).
  *
  * Reverse-direction request types (`ShowMessageParams`, `ApplyWorkspaceEditParams`, etc.)
  * live inside `object LspHandler` per steering A5. This companion holds only the init quartet,
  * extension methods, and the `Unsafe` abstract class.
  *
  * @see [[LspServer.init]]
  * @see [[LspServer.initUnscoped]]
  */
opaque type LspServer = LspServer.Unsafe

object LspServer:

    // =========================================================================
    // Scoped init quartet
    // =========================================================================

    /** Initializes an LSP server over the given transport using default configuration. */
    def init(transport: JsonRpcTransport, handlers: LspHandler[?, ?, ?]*)(using Frame): LspServer < (Async & Scope) =
        init(transport, handlers, LspConfig.default)

    /** Initializes an LSP server with the given configuration. */
    def init(transport: JsonRpcTransport, config: LspConfig)(handlers: LspHandler[?, ?, ?]*)(using Frame): LspServer < (Async & Scope) =
        init(transport, handlers, config)

    /** Initializes an LSP server with explicit handler list and optional config. */
    def init(transport: JsonRpcTransport, handlers: Seq[LspHandler[?, ?, ?]], config: LspConfig = LspConfig.default)(using
        Frame
    ): LspServer < (Async & Scope) =
        LspConfig.require(config)
        Scope.acquireRelease(
            internal.lsp.LspEngine.initServer(transport, handlers, config).map(_.safe)
        )(_.closeNow)
    end init

    /** Initializes and passes the server handle to the given function in a Scope context. */
    def initWith[A, S](transport: JsonRpcTransport, handlers: LspHandler[?, ?, ?]*)(f: LspServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(transport, handlers*).map(f)

    /** Initializes with config and passes the server handle to the given function. */
    def initWith[A, S](transport: JsonRpcTransport, config: LspConfig)(handlers: LspHandler[?, ?, ?]*)(f: LspServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(transport, config)(handlers*).map(f)

    // =========================================================================
    // Unscoped init quartet
    // =========================================================================

    /** Initializes an LSP server without automatic resource cleanup. */
    def initUnscoped(transport: JsonRpcTransport, handlers: LspHandler[?, ?, ?]*)(using Frame): LspServer < Async =
        initUnscoped(transport, handlers, LspConfig.default)

    /** Initializes an LSP server with config, without automatic resource cleanup. */
    def initUnscoped(transport: JsonRpcTransport, config: LspConfig)(handlers: LspHandler[?, ?, ?]*)(using Frame): LspServer < Async =
        initUnscoped(transport, handlers, config)

    /** Initializes an LSP server with explicit handler list, without automatic resource cleanup. */
    def initUnscoped(transport: JsonRpcTransport, handlers: Seq[LspHandler[?, ?, ?]], config: LspConfig = LspConfig.default)(using
        Frame
    ): LspServer < Async =
        LspConfig.require(config)
        internal.lsp.LspEngine.initServer(transport, handlers, config).map(_.safe)
    end initUnscoped

    /** Initializes unscoped and passes the server handle to the given function. */
    def initUnscopedWith[A, S](transport: JsonRpcTransport, handlers: LspHandler[?, ?, ?]*)(f: LspServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(transport, handlers*).map(f)

    /** Initializes unscoped with config and passes the server handle to the given function. */
    def initUnscopedWith[A, S](transport: JsonRpcTransport, config: LspConfig)(handlers: LspHandler[?, ?, ?]*)(f: LspServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(transport, config)(handlers*).map(f)

    // =========================================================================
    // Extension methods (safe-tier bridge over Unsafe)
    // =========================================================================

    extension (self: LspServer)

        /** Sends a `window/showMessage` notification to the client. */
        def showMessage(params: LspHandler.ShowMessageParams)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.showMessage(params).safe.get)

        /** Sends a `window/showMessageRequest` request to the client. */
        def showMessageRequest(params: LspHandler.ShowMessageRequestParams)(using
            Frame
        ): Maybe[LspHandler.MessageActionItem] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.showMessageRequest(params).safe.get)

        /** Sends a `window/showDocument` request to the client. */
        def showDocument(params: LspHandler.ShowDocumentParams)(using
            Frame
        ): LspHandler.ShowDocumentResult < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.showDocument(params).safe.get)

        /** Sends a `window/logMessage` notification to the client. */
        def logMessage(params: LspHandler.LogMessageParams)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.logMessage(params).safe.get)

        /** Sends a `window/workDoneProgress/create` request to the client. */
        def createWorkDoneProgress(params: LspHandler.WorkDoneProgressCreateParams)(using
            Frame
        ): Unit < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.createWorkDoneProgress(params).safe.get)

        /** Sends a `telemetry/event` notification to the client with a typed payload. */
        def telemetry[T](payload: T)(using Frame, Schema[T]): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.telemetry(payload).safe.get)

        /** Sends a `workspace/applyEdit` request to the client. */
        def applyEdit(params: LspHandler.ApplyWorkspaceEditParams)(using
            Frame
        ): LspHandler.ApplyWorkspaceEditResult < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.applyEdit(params).safe.get)

        /** Sends a `workspace/configuration` request to the client and decodes the results. */
        def getConfiguration[T](params: LspHandler.ConfigurationParams)(using
            Frame,
            Schema[T]
        ): Chunk[T] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.getConfiguration[T](params).safe.get)

        /** Sends a `workspace/workspaceFolders` request to the client. */
        def getWorkspaceFolders(using Frame): Maybe[Chunk[LspHandler.WorkspaceFolder]] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.getWorkspaceFolders.safe.get)

        /** Requests the client to refresh semantic tokens. */
        def refreshSemanticTokens(using Frame): Unit < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.refreshSemanticTokens.safe.get)

        /** Requests the client to refresh inline values. */
        def refreshInlineValue(using Frame): Unit < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.refreshInlineValue.safe.get)

        /** Requests the client to refresh inlay hints. */
        def refreshInlayHint(using Frame): Unit < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.refreshInlayHint.safe.get)

        /** Requests the client to refresh diagnostics. */
        def refreshDiagnostic(using Frame): Unit < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.refreshDiagnostic.safe.get)

        /** Requests the client to refresh code lens. */
        def refreshCodeLens(using Frame): Unit < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.refreshCodeLens.safe.get)

        /** Registers a capability with the client. */
        def registerCapability(params: LspHandler.RegistrationParams)(using Frame): Unit < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.registerCapability(params).safe.get)

        /** Unregisters a capability from the client. */
        def unregisterCapability(params: LspHandler.UnregistrationParams)(using Frame): Unit < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.unregisterCapability(params).safe.get)

        /** Sends `textDocument/publishDiagnostics` to the client. */
        def publishDiagnostics(params: LspHandler.PublishDiagnosticsParams)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.publishDiagnostics(params).safe.get)

        /** Logs a trace message to the client. */
        def logTrace(message: String, verbose: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.logTrace(message, verbose).safe.get)

        /** Sends a `$/progress` notification to the client. */
        def workDoneProgress(token: LspHandler.ProgressToken, value: LspHandler.WorkDoneProgressValue)(using
            Frame
        ): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.workDoneProgress(token, value).safe.get)

        /** Sends a `$/cancelRequest` notification. */
        def cancel(id: JsonRpcId)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.cancel(id).safe.get)

        /** The LSP spec version this server implements. */
        def specVersion: String = self.specVersion

        /** The negotiated position encoding for this session. */
        def positionEncoding: LspHandler.PositionEncodingKind = self.positionEncoding

        /** The client capabilities, available after the handshake completes. */
        def clientCapabilities: Maybe[LspCapabilities.Client.Client] = self.clientCapabilities

        /** The client info, available after the handshake completes. */
        def clientInfo: Maybe[LspInfo] = self.clientInfo

        /** The workspace folders, available after the handshake completes. */
        def workspaceFolders: Maybe[Chunk[LspHandler.WorkspaceFolder]] = self.workspaceFolders

        /** The underlying JSON-RPC handler. */
        def underlying: JsonRpcHandler = self.underlying

        /** Waits for all in-flight requests to complete. */
        def awaitDrain(using Frame): Unit < Async = Sync.Unsafe.defer(self.awaitDrain.safe.get)

        /** Closes the server gracefully with the default 30-second grace period. INV-068. */
        def close(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(30.seconds).safe.get)

        /** Closes the server gracefully with the specified grace period. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(gracePeriod).safe.get)

        /** Closes the server immediately (Duration.Zero grace period). INV-068. */
        def closeNow(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(Duration.Zero).safe.get)

        /** The raw unsafe handle. */
        def unsafe: Unsafe = self

    end extension

    // =========================================================================
    // Unsafe abstract class
    // =========================================================================

    /** The underlying unsafe implementation contract.
      *
      * All methods return `Fiber.Unsafe` (non-effect-typed futures). Bridge to safe tier
      * through the extension methods declared above. Concrete implementations are produced
      * by the engine in Phase 06.
      */
    abstract class Unsafe:
        def showMessage(params: LspHandler.ShowMessageParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def showMessageRequest(params: LspHandler.ShowMessageRequestParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.MessageActionItem], Abort[LspException | Closed]]
        def showDocument(params: LspHandler.ShowDocumentParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[LspHandler.ShowDocumentResult, Abort[LspException | Closed]]
        def logMessage(params: LspHandler.LogMessageParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def createWorkDoneProgress(params: LspHandler.WorkDoneProgressCreateParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Unit, Abort[LspException | Closed]]
        def telemetry[T](payload: T)(using AllowUnsafe, Frame, Schema[T]): Fiber.Unsafe[Unit, Abort[Closed]]
        def applyEdit(params: LspHandler.ApplyWorkspaceEditParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[LspHandler.ApplyWorkspaceEditResult, Abort[LspException | Closed]]
        def getConfiguration[T](params: LspHandler.ConfigurationParams)(using
            AllowUnsafe,
            Frame,
            Schema[T]
        ): Fiber.Unsafe[Chunk[T], Abort[LspException | Closed]]
        def getWorkspaceFolders(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[Chunk[LspHandler.WorkspaceFolder]], Abort[LspException | Closed]]
        def refreshSemanticTokens(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspException | Closed]]
        def refreshInlineValue(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspException | Closed]]
        def refreshInlayHint(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspException | Closed]]
        def refreshDiagnostic(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspException | Closed]]
        def refreshCodeLens(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspException | Closed]]
        def registerCapability(params: LspHandler.RegistrationParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Unit, Abort[LspException | Closed]]
        def unregisterCapability(params: LspHandler.UnregistrationParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Unit, Abort[LspException | Closed]]
        def publishDiagnostics(params: LspHandler.PublishDiagnosticsParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def logTrace(message: String, verbose: Maybe[String])(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def workDoneProgress(token: LspHandler.ProgressToken, value: LspHandler.WorkDoneProgressValue)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Unit, Abort[Closed]]
        def cancel(id: JsonRpcId)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def specVersion: String
        def positionEncoding: LspHandler.PositionEncodingKind
        def clientCapabilities: Maybe[LspCapabilities.Client.Client]
        def clientInfo: Maybe[LspInfo]
        def workspaceFolders: Maybe[Chunk[LspHandler.WorkspaceFolder]]
        def underlying: JsonRpcHandler
        def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]
        def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]
        final def safe: LspServer = this
    end Unsafe

end LspServer
